package org.knownhosts.libfindchars.generator;

import java.lang.invoke.MethodHandles;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.knownhosts.libfindchars.compiler.inline.BytecodeInliner;
import org.knownhosts.libfindchars.compiler.inline.SpecializationConfig;
import org.knownhosts.libfindchars.compiler.inline.TemplateTransformer;

import org.knownhosts.libfindchars.api.FindEngine;
import org.knownhosts.libfindchars.api.Utf8EngineTemplate;
import org.knownhosts.libfindchars.api.Utf8Kernel;
import org.knownhosts.libfindchars.compiler.AsciiFindMask;
import org.knownhosts.libfindchars.compiler.AsciiLiteralGroup;
import org.knownhosts.libfindchars.compiler.ByteLiteral;
import org.knownhosts.libfindchars.compiler.LiteralCompiler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jdk.incubator.vector.ByteVector;
import jdk.incubator.vector.VectorSpecies;

/**
 * Unified entry point for building character detection engines.
 *
 * <p>Use the fluent {@link #builder()} API to declare ASCII character groups
 * ({@link Builder#codepoints}), individual multi-byte UTF-8 codepoints
 * ({@link Builder#codepoint}), and byte range operations ({@link Builder#range}),
 * then call {@link Builder#build()} to obtain a ready-to-use
 * {@link Utf8BuildResult} containing the compiled {@link FindEngine} and
 * a literal map.
 *
 * <pre>{@code
 * var result = Utf8EngineBuilder.builder()
 *         .codepoints("whitespace", '\r', '\n', '\t', ' ')
 *         .codepoint("eacute", 0xE9)
 *         .range("comparison", (byte) 0x3c, (byte) 0x3e)
 *         .build();
 * FindEngine engine = result.engine();
 * Map<String, Byte> literals = result.literals();
 * }</pre>
 */
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

    private record RangeOperation(String name, byte from, byte to) {}

    public static final class Builder {
        private VectorSpecies<Byte> species;
        private boolean compiled = true;
        private final List<CodepointEntry> entries = new ArrayList<>();
        private final List<RangeOperation> rangeOperations = new ArrayList<>();

        private Builder() {}

        /**
         * When {@code false}, skip bytecode specialization and return a plain
         * {@link Utf8EngineTemplate} instance (C2 JIT fallback path).
         * Default is {@code true} (compiled hidden class).
         */
        public Builder compiled(boolean compiled) {
            this.compiled = compiled;
            return this;
        }

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

        /**
         * Add a range operation matching all bytes from {@code from} to {@code to} inclusive.
         * Only meaningful for ASCII-range bytes (0x00-0x7F) since higher bytes are UTF-8
         * lead/continuation bytes.
         */
        public Builder range(String name, byte from, byte to) {
            rangeOperations.add(new RangeOperation(name, from, to));
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

            // Assign literal names per entry per round.
            // Multi-byte entries sharing the same byte value in a round get the same
            // canonical literal name so Z3 only sees one constraint per unique byte.
            String[][] literalNames = new String[entries.size()][maxRounds];
            for (int r = 0; r < maxRounds; r++) {
                Map<Byte, String> byteToName = new HashMap<>();
                final int round = r;
                for (int e = 0; e < entries.size(); e++) {
                    var entry = entries.get(e);
                    if (entry.asciiGroup()) {
                        if (r == 0) literalNames[e][0] = entry.name() + "_r0";
                    } else if (r < entry.utf8Bytes().length) {
                        byte b = entry.utf8Bytes()[r];
                        String canonical = byteToName.computeIfAbsent(b,
                                k -> "mb_r" + round + "_0x" + String.format("%02x", k & 0xFF));
                        literalNames[e][r] = canonical;
                    }
                }
            }

            // Collect per-round ByteLiterals
            List<List<ByteLiteral>> perRoundLiterals = new ArrayList<>();
            for (int r = 0; r < maxRounds; r++) {
                perRoundLiterals.add(new ArrayList<>());
            }

            // Track which literal names have already been added per round
            // to avoid duplicate ByteLiterals for shared-byte multi-byte entries
            List<Set<String>> seenPerRound = new ArrayList<>();
            for (int r = 0; r < maxRounds; r++) seenPerRound.add(new HashSet<>());

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
                    seenPerRound.get(0).add(literalNames[e][0]);
                } else {
                    // Multi-byte: one ByteLiteral per round with a single byte value,
                    // but skip if we already created one for this canonical name
                    for (int r = 0; r < bytes.length; r++) {
                        if (seenPerRound.get(r).add(literalNames[e][r])) {
                            char[] chars = { (char) (bytes[r] & 0xFF) };
                            perRoundLiterals.get(r).add(new ByteLiteral(literalNames[e][r], chars));
                        }
                    }
                }
            }

            // Solve each round sequentially, passing used literals forward.
            // If Z3 can't find a single shuffle mask for all literals in a round,
            // auto-split into two groups and solve each half separately.
            List<List<AsciiFindMask>> roundMaskGroups = new ArrayList<>();
            List<Byte> usedLiterals = new ArrayList<>();

            try (LiteralCompiler compiler = new LiteralCompiler()) {
                for (int r = 0; r < maxRounds; r++) {
                    var literals = perRoundLiterals.get(r);
                    if (literals.isEmpty()) {
                        throw new IllegalStateException("Round " + r + " has no literals");
                    }
                    var groupMasks = solveWithAutoSplit(compiler, usedLiterals, literals, r, sp.vectorByteSize());
                    roundMaskGroups.add(groupMasks);
                    for (var mask : groupMasks) {
                        usedLiterals.addAll(mask.literals().values());
                    }
                }
            } catch (Exception e) {
                throw new IllegalStateException("Z3 solver error", e);
            }

            // Helper: find literal byte across all groups in a round
            java.util.function.BiFunction<Integer, String, Byte> findLiteral = (round, name) -> {
                for (var mask : roundMaskGroups.get(round)) {
                    if (mask.literals().containsKey(name)) {
                        return mask.literalOf(name);
                    }
                }
                throw new IllegalStateException("Literal '" + name + "' not found in round " + round);
            };

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
                    byte litByte = findLiteral.apply(0, literalNames[e][0]);
                    literalMap.put(entry.name(), litByte);
                } else {
                    // Multi-byte: build per-round literal vectors
                    ByteVector[] rlVecs = new ByteVector[bytes.length];
                    byte finalLitByte = 0;
                    for (int r = 0; r < bytes.length; r++) {
                        byte litByte = findLiteral.apply(r, literalNames[e][r]);
                        rlVecs[r] = ByteVector.broadcast(sp, litByte);
                        finalLitByte = litByte;
                    }
                    charSpecByteLengthsList.add(new int[]{bytes.length});
                    charSpecRoundLitVecsList.add(rlVecs);
                    charSpecFinalLitVecsList.add(ByteVector.broadcast(sp, finalLitByte));
                    literalMap.put(entry.name(), finalLitByte);
                }
            }

            // Flatten round mask groups for codegen: each group gets its own LUT pair + literal vecs
            int totalGroups = 0;
            for (var groups : roundMaskGroups) totalGroups += groups.size();

            var zero = ByteVector.broadcast(sp, (byte) 0);
            var classifyVec = ByteVector.fromArray(sp,
                    Arrays.copyOf(CLASSIFY_TABLE, sp.vectorByteSize()), 0);
            var lowMaskVec = ByteVector.broadcast(sp, 0x0f);

            var lowLUTs = new ByteVector[totalGroups];
            var highLUTs = new ByteVector[totalGroups];
            var literalVecs = new ByteVector[totalGroups][];
            int[] roundGroupStart = new int[maxRounds];
            int[] roundGroupCount = new int[maxRounds];

            int flatIdx = 0;
            for (int r = 0; r < maxRounds; r++) {
                var groups = roundMaskGroups.get(r);
                roundGroupStart[r] = flatIdx;
                roundGroupCount[r] = groups.size();
                for (var mask : groups) {
                    lowLUTs[flatIdx] = ByteVector.fromArray(sp,
                            Arrays.copyOf(mask.lowNibbleMask(), sp.vectorByteSize()), 0);
                    highLUTs[flatIdx] = ByteVector.fromArray(sp,
                            Arrays.copyOf(mask.highNibbleMask(), sp.vectorByteSize()), 0);
                    var lits = mask.literals().values();
                    literalVecs[flatIdx] = new ByteVector[lits.size()];
                    int j = 0;
                    for (byte lit : lits) {
                        literalVecs[flatIdx][j++] = ByteVector.broadcast(sp, lit);
                    }
                    flatIdx++;
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

            // Build range vectors
            List<ByteVector> rangeLowerList = new ArrayList<>();
            List<ByteVector> rangeUpperList = new ArrayList<>();
            List<ByteVector> rangeLitList = new ArrayList<>();
            // Find next free literal byte above all Z3-assigned literals
            int nextLit = 1;
            for (byte used : literalMap.values()) {
                nextLit = Math.max(nextLit, (used & 0xFF) + 1);
            }
            for (RangeOperation operation : rangeOperations) {
                while (literalMap.containsValue((byte) nextLit)) nextLit++;
                rangeLowerList.add(ByteVector.broadcast(sp, operation.from()));
                rangeUpperList.add(ByteVector.broadcast(sp, operation.to()));
                rangeLitList.add(ByteVector.broadcast(sp, (byte) nextLit));
                literalMap.put(operation.name(), (byte) nextLit);
                nextLit++;
            }

            var rangeLower = rangeLowerList.toArray(new ByteVector[0]);
            var rangeUpper = rangeUpperList.toArray(new ByteVector[0]);
            var rangeLit = rangeLitList.toArray(new ByteVector[0]);

            int[] flatGroupLitCounts = new int[totalGroups];
            var cleanLUTs = new ByteVector[totalGroups];
            for (int g = 0; g < totalGroups; g++) {
                flatGroupLitCounts[g] = literalVecs[g].length;
                // Build cleanup LUT: lut[lit_val] = lit_val, all others = 0
                byte[] lutBytes = new byte[sp.vectorByteSize()];
                for (var litVec : literalVecs[g]) {
                    byte litVal = litVec.lane(0);
                    assert litVal > 0 && (litVal & 0xFF) < sp.vectorByteSize()
                        : "Literal byte " + (litVal & 0xFF) + " exceeds vector size " + sp.vectorByteSize();
                    lutBytes[litVal & 0xFF] = litVal;
                }
                cleanLUTs[g] = ByteVector.fromArray(sp, lutBytes, 0);
            }

            logger.info("Built UTF-8 engine: {} rounds, {} groups (flat), {} char specs, {} range ops, {} literals",
                    maxRounds, totalGroups, csCount, rangeLower.length, literalMap.size());

            if (!compiled) {
                // C2 JIT fallback: use Utf8EngineTemplate directly
                var engine = (FindEngine) new Utf8EngineTemplate(
                        sp, zero, classifyVec, lowMaskVec,
                        lowLUTs, highLUTs, literalVecs, cleanLUTs,
                        roundGroupStart, roundGroupCount, flatGroupLitCounts,
                        csByteLengths, csRoundLitVecs, csFinalLitVecs,
                        rangeLower, rangeUpper, rangeLit);
                return new Utf8BuildResult(engine, literalMap);
            }

            // Build specialization config for the template transformer
            var config = SpecializationConfig.builder()
                    .constant("maxRounds", maxRounds)
                    .constant("rangeCount", rangeLower.length)
                    .constant("charSpecCount", csCount)
                    .constant("totalGroups", totalGroups)
                    .constant("vectorByteSize", sp.vectorByteSize())
                    .build();

            // Read the template class bytes and apply full specialization pipeline:
            // 1. Inline @Inline private methods (applyRound, applyGroup, gateCharSpec)
            // 2. Constant-fold @Inline int fields (maxRounds, rangeCount, etc.)
            // 3. Dead code elimination (removes unreachable multi-byte branches for ASCII)
            // 4. Inline @Inline static methods from kernel classes (shuffle, cleanLit, etc.)
            byte[] classBytes = BytecodeInliner.readClassBytes(Utf8EngineTemplate.class);
            byte[] specialized = TemplateTransformer.transform(classBytes, config);
            specialized = BytecodeInliner.inline(specialized, Utf8Kernel.class);

            try {
                // Use privateLookupIn to get a lookup in the template's package
                var lookup = MethodHandles.privateLookupIn(Utf8EngineTemplate.class, MethodHandles.lookup());
                var hidden = lookup.defineHiddenClass(specialized, true);
                var clazz = hidden.lookupClass();

                var ctor = clazz.getDeclaredConstructors()[0];
                ctor.setAccessible(true);
                var engine = (FindEngine) ctor.newInstance(
                        sp, zero, classifyVec, lowMaskVec,
                        lowLUTs, highLUTs, literalVecs, cleanLUTs,
                        roundGroupStart, roundGroupCount, flatGroupLitCounts,
                        csByteLengths, csRoundLitVecs, csFinalLitVecs,
                        rangeLower, rangeUpper, rangeLit);
                return new Utf8BuildResult(engine, literalMap);
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("Failed to instantiate compiled UTF-8 engine", e);
            }
        }

        private static List<AsciiFindMask> solveWithAutoSplit(LiteralCompiler compiler,
                List<Byte> usedLiterals, List<ByteLiteral> literals, int round, int vectorByteSize) throws Exception {
            try {
                var group = new AsciiLiteralGroup("round" + round, literals);
                var masks = compiler.solve(usedLiterals, vectorByteSize, group);
                return List.of(masks.getFirst());
            } catch (Exception e) {
                if (literals.size() <= 1) {
                    throw new IllegalStateException("Z3 solver failed for single literal in round " + round, e);
                }
                logger.info("Auto-splitting round {} ({} literals) into two groups", round, literals.size());
                int mid = literals.size() / 2;
                var group1 = new AsciiLiteralGroup("round" + round + "_a", literals.subList(0, mid));
                var masks1 = compiler.solve(usedLiterals, vectorByteSize, group1);
                var mask1 = masks1.getFirst();

                var extended = new ArrayList<>(usedLiterals);
                extended.addAll(mask1.literals().values());
                var group2 = new AsciiLiteralGroup("round" + round + "_b", literals.subList(mid, literals.size()));
                var masks2 = compiler.solve(extended, vectorByteSize, group2);
                return List.of(mask1, masks2.getFirst());
            }
        }
    }
}
