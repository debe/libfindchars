package org.knownhosts.libfindchars.generator;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.knownhosts.libfindchars.api.FindEngine;
import org.knownhosts.libfindchars.api.FindMask;
import org.knownhosts.libfindchars.compiler.AsciiLiteralGroup;
import org.knownhosts.libfindchars.compiler.ByteLiteral;
import org.knownhosts.libfindchars.compiler.Literal;
import org.knownhosts.libfindchars.compiler.LiteralCompiler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jdk.incubator.vector.ByteVector;
import jdk.incubator.vector.VectorSpecies;

public class Utf8EngineBuilder {

    static final Logger logger = LoggerFactory.getLogger(Utf8EngineBuilder.class);

    private static final byte[] CLASSIFY_TABLE = {
        1, 1, 1, 1, 1, 1, 1, 1,  // 0x0-0x7: ASCII
        0, 0, 0, 0,              // 0x8-0xB: continuation
        2, 2,                    // 0xC-0xD: 2-byte start
        3,                       // 0xE: 3-byte start
        4                        // 0xF: 4-byte start
    };

    public record Utf8BuildResult(FindEngine engine, Map<String, Byte> literals) {}

    public static Builder builder() {
        return new Builder();
    }

    private record CodepointEntry(String name, byte[] utf8Bytes, boolean asciiGroup) {}

    public static final class Builder {
        private VectorSpecies<Byte> species;
        private final List<CodepointEntry> entries = new ArrayList<>();

        private Builder() {}

        public Builder species(VectorSpecies<Byte> species) {
            this.species = species;
            return this;
        }

        /**
         * Add multiple ASCII codepoints that share the same literal name.
         * All codepoints must be ASCII (single-byte UTF-8).
         */
        public Builder codepoints(String name, int... codepoints) {
            for (int cp : codepoints) {
                if (cp > 0x7F) {
                    throw new IllegalArgumentException(
                            "codepoints() only accepts ASCII (<=0x7F). Use codepoint() for multi-byte: U+" +
                            Integer.toHexString(cp));
                }
            }
            byte[] allBytes = new byte[codepoints.length];
            for (int i = 0; i < codepoints.length; i++) {
                allBytes[i] = (byte) codepoints[i];
            }
            entries.add(new CodepointEntry(name, allBytes, true));
            return this;
        }

        /**
         * Add a single codepoint (ASCII or multi-byte).
         */
        public Builder codepoint(String name, int codepoint) {
            byte[] utf8 = new String(Character.toChars(codepoint)).getBytes(StandardCharsets.UTF_8);
            entries.add(new CodepointEntry(name, utf8, utf8.length == 1));
            return this;
        }

        public Utf8BuildResult build() {
            var sp = species != null ? species : ByteVector.SPECIES_PREFERRED;

            // Determine max rounds needed (ASCII groups are always 1 round)
            int maxRounds = 1;
            for (var entry : entries) {
                if (!entry.asciiGroup()) {
                    maxRounds = Math.max(maxRounds, entry.utf8Bytes().length);
                }
            }

            // Assign literal names per entry per round
            String[][] literalNames = new String[entries.size()][maxRounds];
            for (int e = 0; e < entries.size(); e++) {
                var entry = entries.get(e);
                if (entry.asciiGroup()) {
                    literalNames[e][0] = entry.name() + "_r0";
                } else {
                    for (int r = 0; r < entry.utf8Bytes().length; r++) {
                        literalNames[e][r] = entry.name() + "_r" + r;
                    }
                }
            }

            // Collect per-round ByteLiterals
            List<List<Literal>> perRoundLiterals = new ArrayList<>();
            for (int r = 0; r < maxRounds; r++) {
                perRoundLiterals.add(new ArrayList<>());
            }

            for (int e = 0; e < entries.size(); e++) {
                var entry = entries.get(e);
                byte[] bytes = entry.utf8Bytes();

                if (entry.asciiGroup()) {
                    // ASCII group: one ByteLiteral at round 0 with all byte values as chars
                    char[] chars = new char[bytes.length];
                    for (int i = 0; i < bytes.length; i++) {
                        chars[i] = (char) (bytes[i] & 0xFF);
                    }
                    perRoundLiterals.get(0).add(new ByteLiteral(literalNames[e][0], chars));
                } else {
                    // Multi-byte: one ByteLiteral per round with a single byte value
                    for (int r = 0; r < bytes.length; r++) {
                        char[] chars = { (char) (bytes[r] & 0xFF) };
                        perRoundLiterals.get(r).add(new ByteLiteral(literalNames[e][r], chars));
                    }
                }
            }

            // Solve each round sequentially, passing used literals forward
            List<FindMask> roundMasks = new ArrayList<>();
            List<Byte> usedLiterals = new ArrayList<>();

            try (LiteralCompiler compiler = new LiteralCompiler()) {
                for (int r = 0; r < maxRounds; r++) {
                    var literals = perRoundLiterals.get(r);
                    if (literals.isEmpty()) {
                        throw new IllegalStateException("Round " + r + " has no literals");
                    }
                    var group = new AsciiLiteralGroup("round" + r, literals);
                    var masks = compiler.solve(usedLiterals, group);
                    var mask = masks.getFirst();
                    roundMasks.add(mask);
                    usedLiterals.addAll(mask.literals().values());
                }
            } catch (Exception e) {
                throw new IllegalStateException("Z3 solver error", e);
            }

            // Build charSpec arrays and literal map for codegen
            List<int[]> charSpecByteLengthsList = new ArrayList<>();
            List<ByteVector[]> charSpecRoundLitVecsList = new ArrayList<>();
            List<ByteVector> charSpecFinalLitVecsList = new ArrayList<>();
            Map<String, Byte> literalMap = new LinkedHashMap<>();

            for (int e = 0; e < entries.size(); e++) {
                var entry = entries.get(e);
                byte[] bytes = entry.utf8Bytes();

                if (entry.asciiGroup()) {
                    // ASCII: map name -> round 0 literal byte
                    byte litByte = roundMasks.get(0).literalOf(literalNames[e][0]);
                    literalMap.put(entry.name(), litByte);
                } else {
                    // Multi-byte: build per-round literal vectors
                    ByteVector[] rlVecs = new ByteVector[bytes.length];
                    byte finalLitByte = 0;
                    for (int r = 0; r < bytes.length; r++) {
                        byte litByte = roundMasks.get(r).literalOf(literalNames[e][r]);
                        rlVecs[r] = ByteVector.broadcast(sp, litByte);
                        finalLitByte = litByte;
                    }
                    charSpecByteLengthsList.add(new int[]{bytes.length});
                    charSpecRoundLitVecsList.add(rlVecs);
                    charSpecFinalLitVecsList.add(ByteVector.broadcast(sp, finalLitByte));
                    literalMap.put(entry.name(), finalLitByte);
                }
            }

            // Extract vectors for codegen
            var zero = ByteVector.broadcast(sp, (byte) 0);
            var classifyVec = ByteVector.fromArray(sp,
                    java.util.Arrays.copyOf(CLASSIFY_TABLE, sp.vectorByteSize()), 0);
            var lowMaskVec = ByteVector.broadcast(sp, 0x0f);

            var lowLUTs = new ByteVector[maxRounds];
            var highLUTs = new ByteVector[maxRounds];
            var literalVecs = new ByteVector[maxRounds][];
            for (int r = 0; r < maxRounds; r++) {
                var mask = roundMasks.get(r);
                lowLUTs[r] = ByteVector.fromArray(sp,
                        java.util.Arrays.copyOf(mask.lowNibbleMask(), sp.vectorByteSize()), 0);
                highLUTs[r] = ByteVector.fromArray(sp,
                        java.util.Arrays.copyOf(mask.highNibbleMask(), sp.vectorByteSize()), 0);
                var lits = mask.literals().values();
                literalVecs[r] = new ByteVector[lits.size()];
                int j = 0;
                for (byte lit : lits) {
                    literalVecs[r][j++] = ByteVector.broadcast(sp, lit);
                }
            }

            int csCount = charSpecByteLengthsList.size();
            int[] csByteLengths = new int[csCount];
            ByteVector[][] csRoundLitVecs = new ByteVector[csCount][];
            ByteVector[] csFinalLitVecs = new ByteVector[csCount];
            for (int s = 0; s < csCount; s++) {
                csByteLengths[s] = charSpecByteLengthsList.get(s)[0];
                csRoundLitVecs[s] = charSpecRoundLitVecsList.get(s);
                csFinalLitVecs[s] = charSpecFinalLitVecsList.get(s);
            }

            var intSpecies = sp.withLanes(int.class);
            var intBatchSize = intSpecies.length();

            logger.info("Built UTF-8 engine: {} rounds, {} char specs, {} literals",
                    maxRounds, csCount, literalMap.size());

            var engine = Utf8EngineCodeGen.compile(sp, zero, classifyVec, lowMaskVec,
                    lowLUTs, highLUTs, literalVecs,
                    csByteLengths, csRoundLitVecs, csFinalLitVecs,
                    intSpecies, intBatchSize);
            return new Utf8BuildResult(engine, literalMap);
        }
    }
}
