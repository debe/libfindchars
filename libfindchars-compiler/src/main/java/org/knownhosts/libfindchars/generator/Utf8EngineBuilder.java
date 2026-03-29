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

import org.knownhosts.libfindchars.api.ChunkFilter;
import org.knownhosts.libfindchars.api.FindEngine;
import org.knownhosts.libfindchars.api.NoOpChunkFilter;
import org.knownhosts.libfindchars.api.VpaKernel;
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

    private static final Logger logger = LoggerFactory.getLogger(Utf8EngineBuilder.class);

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
        private Class<? extends ChunkFilter> filterClass;
        private String[] filterLiteralBindings;

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

        /**
         * Attach a chunk filter for PDA processing. The filter class must provide an
         * {@code @Inline public static ByteVector apply(...)} method matching the
         * {@link ChunkFilter#apply} signature.
         *
         * @param filterClass      class with {@code @Inline} static apply method
         * @param literalBindings  literal names to bind to filter parameters, in order
         */
        public Builder chunkFilter(Class<? extends ChunkFilter> filterClass, String... literalBindings) {
            this.filterClass = filterClass;
            this.filterLiteralBindings = literalBindings;
            return this;
        }

        public Utf8BuildResult build() {
            var sp = species != null ? species : ByteVector.SPECIES_PREFERRED;

            int maxRounds = 1;
            for (var entry : entries) {
                if (!entry.asciiGroup()) {
                    maxRounds = Math.max(maxRounds, entry.utf8Bytes().length);
                }
            }

            var literalNames = assignLiteralNames(entries, maxRounds);
            var perRoundLiterals = collectPerRoundLiterals(entries, literalNames, maxRounds);
            var roundMaskGroups = solveAllRounds(perRoundLiterals, maxRounds, sp.vectorByteSize());

            var charSpecResult = buildCharSpecsAndLiteralMap(entries, literalNames, roundMaskGroups, sp);
            var literalMap = charSpecResult.literalMap();
            var lutResult = buildFlatGroupLUTs(roundMaskGroups, sp, maxRounds);
            var rangeResult = buildRangeVectors(literalMap, sp);

            var rangeLower = rangeResult.lower();
            var rangeUpper = rangeResult.upper();
            var rangeLit = rangeResult.lit();

            var cleanLUTs = buildCleanLUTs(lutResult.literalVecs(), sp);

            logger.info("Built UTF-8 engine: {} rounds, {} groups (flat), {} char specs, {} range ops, {} literals",
                    maxRounds, lutResult.totalGroups(), charSpecResult.csCount(),
                    rangeLower.length, literalMap.size());

            return compileEngine(sp, compiled, literalMap,
                    ByteVector.broadcast(sp, (byte) 0),
                    ByteVector.fromArray(sp, Arrays.copyOf(CLASSIFY_TABLE, sp.vectorByteSize()), 0),
                    ByteVector.broadcast(sp, 0x0f),
                    lutResult.lowLUTs(), lutResult.highLUTs(), lutResult.literalVecs(), cleanLUTs,
                    lutResult.roundGroupStart(), lutResult.roundGroupCount(), lutResult.flatGroupLitCounts(),
                    maxRounds, charSpecResult.csCount(), charSpecResult.csByteLengths(),
                    charSpecResult.csRoundLitVecs(), charSpecResult.csFinalLitVecs(),
                    rangeLower, rangeUpper, rangeLit);
        }

        private record RangeResult(ByteVector[] lower, ByteVector[] upper, ByteVector[] lit) {}

        private static String[][] assignLiteralNames(List<CodepointEntry> entries, int maxRounds) {
            var literalNames = new String[entries.size()][maxRounds];
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
            return literalNames;
        }

        private static List<List<ByteLiteral>> collectPerRoundLiterals(
                List<CodepointEntry> entries, String[][] literalNames, int maxRounds) {
            List<List<ByteLiteral>> perRoundLiterals = new ArrayList<>();
            for (int r = 0; r < maxRounds; r++) perRoundLiterals.add(new ArrayList<>());

            List<Set<String>> seenPerRound = new ArrayList<>();
            for (int r = 0; r < maxRounds; r++) seenPerRound.add(new HashSet<>());

            for (int e = 0; e < entries.size(); e++) {
                var entry = entries.get(e);
                byte[] bytes = entry.utf8Bytes();

                if (entry.asciiGroup()) {
                    char[] chars = new char[bytes.length];
                    for (int i = 0; i < bytes.length; i++) {
                        chars[i] = (char) (bytes[i] & 0xFF);
                    }
                    perRoundLiterals.get(0).add(new ByteLiteral(literalNames[e][0], chars));
                    seenPerRound.get(0).add(literalNames[e][0]);
                } else {
                    for (int r = 0; r < bytes.length; r++) {
                        if (seenPerRound.get(r).add(literalNames[e][r])) {
                            char[] chars = { (char) (bytes[r] & 0xFF) };
                            perRoundLiterals.get(r).add(new ByteLiteral(literalNames[e][r], chars));
                        }
                    }
                }
            }
            return perRoundLiterals;
        }

        private static List<List<AsciiFindMask>> solveAllRounds(
                List<List<ByteLiteral>> perRoundLiterals, int maxRounds, int vectorByteSize) {
            List<List<AsciiFindMask>> roundMaskGroups = new ArrayList<>();
            List<Byte> usedLiterals = new ArrayList<>();
            try (LiteralCompiler compiler = new LiteralCompiler()) {
                for (int r = 0; r < maxRounds; r++) {
                    var literals = perRoundLiterals.get(r);
                    if (literals.isEmpty()) {
                        throw new IllegalStateException("Round " + r + " has no literals");
                    }
                    var groupMasks = solveWithAutoSplit(compiler, usedLiterals, literals, r, vectorByteSize);
                    roundMaskGroups.add(groupMasks);
                    for (var mask : groupMasks) {
                        usedLiterals.addAll(mask.literals().values());
                    }
                }
            } catch (Exception e) {
                throw new IllegalStateException("Z3 solver error", e);
            }
            return roundMaskGroups;
        }

        private record CharSpecResult(
                Map<String, Byte> literalMap,
                int csCount,
                int[] csByteLengths,
                ByteVector[][] csRoundLitVecs,
                ByteVector[] csFinalLitVecs) {}

        private static CharSpecResult buildCharSpecsAndLiteralMap(
                List<CodepointEntry> entries, String[][] literalNames,
                List<List<AsciiFindMask>> roundMaskGroups, VectorSpecies<Byte> sp) {

            List<int[]> csByteLengthsList = new ArrayList<>();
            List<ByteVector[]> csRoundLitVecsList = new ArrayList<>();
            List<ByteVector> csFinalLitVecsList = new ArrayList<>();
            Map<String, Byte> literalMap = new LinkedHashMap<>();

            for (int e = 0; e < entries.size(); e++) {
                var entry = entries.get(e);
                byte[] bytes = entry.utf8Bytes();

                if (entry.asciiGroup()) {
                    byte litByte = findLiteralByte(roundMaskGroups,0, literalNames[e][0]);
                    literalMap.put(entry.name(), litByte);
                } else {
                    ByteVector[] rlVecs = new ByteVector[bytes.length];
                    byte finalLitByte = 0;
                    for (int r = 0; r < bytes.length; r++) {
                        byte litByte = findLiteralByte(roundMaskGroups,r, literalNames[e][r]);
                        rlVecs[r] = ByteVector.broadcast(sp, litByte);
                        finalLitByte = litByte;
                    }
                    csByteLengthsList.add(new int[]{bytes.length});
                    csRoundLitVecsList.add(rlVecs);
                    csFinalLitVecsList.add(ByteVector.broadcast(sp, finalLitByte));
                    literalMap.put(entry.name(), finalLitByte);
                }
            }

            int csCount = csByteLengthsList.size();
            int[] csByteLengths = new int[csCount];
            ByteVector[][] csRoundLitVecs = new ByteVector[csCount][];
            ByteVector[] csFinalLitVecs = new ByteVector[csCount];
            for (int s = 0; s < csCount; s++) {
                csByteLengths[s] = csByteLengthsList.get(s)[0];
                csRoundLitVecs[s] = csRoundLitVecsList.get(s);
                csFinalLitVecs[s] = csFinalLitVecsList.get(s);
            }
            return new CharSpecResult(literalMap, csCount, csByteLengths, csRoundLitVecs, csFinalLitVecs);
        }

        private static byte findLiteralByte(List<List<AsciiFindMask>> roundMaskGroups,
                int round, String name) {
            for (var mask : roundMaskGroups.get(round)) {
                if (mask.literals().containsKey(name)) {
                    return mask.literalOf(name);
                }
            }
            throw new IllegalStateException("Literal '" + name + "' not found in round " + round);
        }

        private record LutResult(
                ByteVector[] lowLUTs, ByteVector[] highLUTs, ByteVector[][] literalVecs,
                int[] roundGroupStart, int[] roundGroupCount, int[] flatGroupLitCounts,
                int totalGroups) {}

        private static LutResult buildFlatGroupLUTs(
                List<List<AsciiFindMask>> roundMaskGroups, VectorSpecies<Byte> sp, int maxRounds) {
            int totalGroups = 0;
            for (var groups : roundMaskGroups) totalGroups += groups.size();

            var lowLUTs = new ByteVector[totalGroups];
            var highLUTs = new ByteVector[totalGroups];
            var literalVecs = new ByteVector[totalGroups][];
            int[] roundGroupStart = new int[maxRounds];
            int[] roundGroupCount = new int[maxRounds];
            int[] flatGroupLitCounts = new int[totalGroups];

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
                    flatGroupLitCounts[flatIdx] = literalVecs[flatIdx].length;
                    flatIdx++;
                }
            }
            return new LutResult(lowLUTs, highLUTs, literalVecs,
                    roundGroupStart, roundGroupCount, flatGroupLitCounts, totalGroups);
        }

        private RangeResult buildRangeVectors(Map<String, Byte> literalMap, VectorSpecies<Byte> sp) {
            var lowerList = new ArrayList<ByteVector>();
            var upperList = new ArrayList<ByteVector>();
            var litList = new ArrayList<ByteVector>();
            int nextLit = 1;
            for (byte used : literalMap.values()) {
                nextLit = Math.max(nextLit, (used & 0xFF) + 1);
            }
            for (RangeOperation operation : rangeOperations) {
                while (literalMap.containsValue((byte) nextLit)) nextLit++;
                lowerList.add(ByteVector.broadcast(sp, operation.from()));
                upperList.add(ByteVector.broadcast(sp, operation.to()));
                litList.add(ByteVector.broadcast(sp, (byte) nextLit));
                literalMap.put(operation.name(), (byte) nextLit);
                nextLit++;
            }
            return new RangeResult(
                    lowerList.toArray(new ByteVector[0]),
                    upperList.toArray(new ByteVector[0]),
                    litList.toArray(new ByteVector[0]));
        }

        private static ByteVector[] buildCleanLUTs(ByteVector[][] literalVecs, VectorSpecies<Byte> sp) {
            var cleanLUTs = new ByteVector[literalVecs.length];
            for (int g = 0; g < literalVecs.length; g++) {
                byte[] lutBytes = new byte[sp.vectorByteSize()];
                for (var litVec : literalVecs[g]) {
                    byte litVal = litVec.lane(0);
                    if (litVal <= 0 || (litVal & 0xFF) >= sp.vectorByteSize()) {
                        throw new IllegalArgumentException(
                                "Literal byte " + (litVal & 0xFF) + " out of valid range [1, "
                                + (sp.vectorByteSize() - 1) + "] for " + sp.vectorByteSize()
                                + "-byte vectors (ARM NEON: max 15, AVX-512: max 63)");
                    }
                    lutBytes[litVal & 0xFF] = litVal;
                }
                cleanLUTs[g] = ByteVector.fromArray(sp, lutBytes, 0);
            }
            return cleanLUTs;
        }

        private static List<AsciiFindMask> solveWithAutoSplit(LiteralCompiler compiler,
                List<Byte> usedLiterals, List<ByteLiteral> literals, int round, int vectorByteSize) throws Exception {
            return solveWithAutoSplit(compiler, usedLiterals, literals, round, vectorByteSize, 0);
        }

        private static List<AsciiFindMask> solveWithAutoSplit(LiteralCompiler compiler,
                List<Byte> usedLiterals, List<ByteLiteral> literals, int round,
                int vectorByteSize, int depth) throws Exception {
            try {
                var group = new AsciiLiteralGroup("round" + round + "_d" + depth, literals);
                var masks = compiler.solve(usedLiterals, vectorByteSize, group);
                return List.of(masks.getFirst());
            } catch (Exception e) {
                if (literals.size() <= 1) {
                    throw new IllegalStateException(
                            "Z3 solver failed for single literal in round " + round
                            + " (vectorByteSize=" + vectorByteSize + ", depth=" + depth + ")", e);
                }
                logger.info("Auto-splitting round {} ({} literals, depth {}) into two groups",
                        round, literals.size(), depth);
                int mid = literals.size() / 2;

                // Solve first half (may recurse further)
                var resultLeft = solveWithAutoSplit(compiler, usedLiterals,
                        literals.subList(0, mid), round, vectorByteSize, depth + 1);

                // Accumulate used literals from left half before solving right
                var extended = new ArrayList<>(usedLiterals);
                for (var mask : resultLeft) {
                    extended.addAll(mask.literals().values());
                }

                // Solve second half (may recurse further)
                var resultRight = solveWithAutoSplit(compiler, extended,
                        literals.subList(mid, literals.size()), round, vectorByteSize, depth + 1);

                var combined = new ArrayList<>(resultLeft);
                combined.addAll(resultRight);
                return combined;
            }
        }

        private Utf8BuildResult compileEngine(
                VectorSpecies<Byte> sp, boolean compiled, Map<String, Byte> literalMap,
                ByteVector zero, ByteVector classifyVec, ByteVector lowMaskVec,
                ByteVector[] lowLUTs, ByteVector[] highLUTs, ByteVector[][] literalVecs,
                ByteVector[] cleanLUTs, int[] roundGroupStart, int[] roundGroupCount,
                int[] flatGroupLitCounts, int maxRounds, int csCount,
                int[] csByteLengths, ByteVector[][] csRoundLitVecs, ByteVector[] csFinalLitVecs,
                ByteVector[] rangeLower, ByteVector[] rangeUpper, ByteVector[] rangeLit) {

            boolean hasFilter = filterClass != null && filterLiteralBindings != null
                    && filterLiteralBindings.length > 0;
            int filterEnabledVal = hasFilter ? 1 : 0;

            ByteVector[] filterLitVecs = resolveFilterLiterals(sp, literalMap, hasFilter, zero);
            int useCompressVal = sp.vectorByteSize() >= 64 ? 1 : 0;

            Object[] ctorArgs = {
                    sp, zero, classifyVec, lowMaskVec,
                    lowLUTs, highLUTs, literalVecs, cleanLUTs,
                    roundGroupStart, roundGroupCount, flatGroupLitCounts,
                    csByteLengths, csRoundLitVecs, csFinalLitVecs,
                    rangeLower, rangeUpper, rangeLit,
                    filterEnabledVal, filterLitVecs, useCompressVal
            };

            if (!compiled) {
                var engine = (FindEngine) new Utf8EngineTemplate(
                        sp, zero, classifyVec, lowMaskVec,
                        lowLUTs, highLUTs, literalVecs, cleanLUTs,
                        roundGroupStart, roundGroupCount, flatGroupLitCounts,
                        csByteLengths, csRoundLitVecs, csFinalLitVecs,
                        rangeLower, rangeUpper, rangeLit,
                        filterEnabledVal, filterLitVecs, useCompressVal);
                return new Utf8BuildResult(engine, literalMap);
            }

            byte[] specialized = specializeTemplate(sp, maxRounds, rangeLower.length,
                    csCount, lowLUTs.length, filterEnabledVal, useCompressVal,
                    hasFilter, filterClass);

            try {
                var lookup = MethodHandles.privateLookupIn(
                        Utf8EngineTemplate.class, MethodHandles.lookup());
                var hidden = lookup.defineHiddenClass(specialized, true);
                var ctor = hidden.lookupClass().getDeclaredConstructors()[0];
                ctor.setAccessible(true);
                var engine = (FindEngine) ctor.newInstance(ctorArgs);
                return new Utf8BuildResult(engine, literalMap);
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("Failed to instantiate compiled UTF-8 engine", e);
            }
        }

        private ByteVector[] resolveFilterLiterals(VectorSpecies<Byte> sp,
                Map<String, Byte> literalMap, boolean hasFilter, ByteVector zero) {
            if (!hasFilter) return new ByteVector[0];
            var vecs = new ByteVector[filterLiteralBindings.length];
            for (int b = 0; b < filterLiteralBindings.length; b++) {
                Byte litByte = literalMap.get(filterLiteralBindings[b]);
                if (litByte == null) {
                    throw new IllegalStateException(
                            "Filter literal binding '" + filterLiteralBindings[b]
                            + "' not found in literal map. Available: " + literalMap.keySet());
                }
                vecs[b] = ByteVector.broadcast(sp, litByte);
            }
            return vecs;
        }

        private static byte[] specializeTemplate(VectorSpecies<Byte> sp,
                int maxRounds, int rangeCount, int csCount, int totalGroups,
                int filterEnabledVal, int useCompressVal, boolean hasFilter,
                Class<? extends ChunkFilter> filterClass) {
            var config = SpecializationConfig.builder()
                    .constant("maxRounds", maxRounds)
                    .constant("rangeCount", rangeCount)
                    .constant("charSpecCount", csCount)
                    .constant("totalGroups", totalGroups)
                    .constant("vectorByteSize", sp.vectorByteSize())
                    .constant("filterEnabled", filterEnabledVal)
                    .constant("useCompress", useCompressVal)
                    .build();

            byte[] classBytes = BytecodeInliner.readClassBytes(Utf8EngineTemplate.class);
            byte[] specialized = TemplateTransformer.transform(classBytes, config);
            specialized = BytecodeInliner.inline(specialized, Utf8Kernel.class);

            if (hasFilter) {
                var fromOwner = java.lang.constant.ClassDesc.of(NoOpChunkFilter.class.getName());
                var toOwner = java.lang.constant.ClassDesc.of(filterClass.getName());
                specialized = TemplateTransformer.rewriteFilterOwner(specialized, fromOwner, toOwner);
                specialized = BytecodeInliner.inline(specialized, filterClass);
                specialized = BytecodeInliner.inline(specialized, VpaKernel.class);
            }
            return specialized;
        }
    }
}
