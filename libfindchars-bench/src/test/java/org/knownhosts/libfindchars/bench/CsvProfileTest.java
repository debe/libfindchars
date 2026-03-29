package org.knownhosts.libfindchars.bench;

import org.junit.jupiter.api.Test;
import org.knownhosts.libfindchars.api.MatchStorage;
import org.knownhosts.libfindchars.api.MatchView;
import org.knownhosts.libfindchars.csv.CsvParser;
import org.knownhosts.libfindchars.generator.Utf8EngineBuilder;
import org.knownhosts.libfindchars.csv.CsvQuoteFilter;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.SplittableRandom;

/**
 * Profiling harness: isolate SIMD scan phase vs. match walker phase.
 */
class CsvProfileTest {

    @Test
    void profilePhases() {
        var rng = new SplittableRandom(42);
        var config = new CsvDataGenerator.CsvConfig(10, 20, 0.1, 0.3, false);
        byte[] csvData = CsvDataGenerator.generateCsvData(rng, 10 * 1024 * 1024, config);

        var arena = Arena.ofAuto();
        var segment = arena.allocate(csvData.length);
        MemorySegment.copy(MemorySegment.ofArray(csvData), 0, segment, 0, csvData.length);

        // Build engine directly for phase isolation
        var result = Utf8EngineBuilder.builder()
                .codepoints("quote", '"')
                .codepoints("delim", ',')
                .codepoints("lf", '\n')
                .codepoints("cr", '\r')
                .chunkFilter(CsvQuoteFilter.class, "quote", "delim", "lf", "cr")
                .build();
        var engine = result.engine();

        var parser = CsvParser.builder().delimiter(',').quote('"').hasHeader(true).build();

        // Warmup
        var storage = new MatchStorage(csvData.length / 4, 64);
        for (int i = 0; i < 10; i++) {
            engine.find(segment, storage);
            parser.parse(segment);
        }

        // Phase 1: SIMD scan only (engine.find)
        int iters = 50;
        long start = System.nanoTime();
        int totalMatches = 0;
        for (int i = 0; i < iters; i++) {
            var view = engine.find(segment, storage);
            totalMatches += view.size();
        }
        long scanElapsed = System.nanoTime() - start;

        // Phase 2: Zero-alloc scan (scan + token iteration)
        start = System.nanoTime();
        int totalRows = 0;
        for (int i = 0; i < iters; i++) {
            var sv = parser.scan(segment);
            int rows = 0;
            for (int j = 0; j < sv.size(); j++) {
                if (sv.tokenAt(j) instanceof org.knownhosts.libfindchars.csv.CsvToken.Newline) rows++;
            }
            totalRows += rows;
        }
        long fullElapsed = System.nanoTime() - start;

        double dataMb = csvData.length / (1024.0 * 1024.0);
        double scanMbps = (dataMb * iters) / (scanElapsed / 1e9);
        double fullMbps = (dataMb * iters) / (fullElapsed / 1e9);
        double walkerPct = 100.0 * (1.0 - scanElapsed / (double) fullElapsed);

        System.out.printf("Data: %.1f MB, %d matches/parse, %d rows/parse%n",
                dataMb, totalMatches / iters, totalRows / iters);
        System.out.printf("SIMD scan only:    %.1f MB/s%n", scanMbps);
        System.out.printf("Full parse:        %.1f MB/s%n", fullMbps);
        System.out.printf("Walker overhead:   %.1f%%%n", walkerPct);
    }

    @Test
    void profileDecodeStrategies() {
        // Compare decode strategies at CSV-typical density
        var species = jdk.incubator.vector.ByteVector.SPECIES_PREFERRED;
        int vbs = species.vectorByteSize();
        var zero = jdk.incubator.vector.ByteVector.broadcast(species, (byte) 0);

        // Build test vectors with ~7.5% density (1.2 matches per 16-byte chunk)
        var rng = new SplittableRandom(42);
        int chunks = 1_000_000;
        jdk.incubator.vector.ByteVector[] vecs = new jdk.incubator.vector.ByteVector[chunks];
        for (int c = 0; c < chunks; c++) {
            byte[] data = new byte[vbs];
            for (int j = 0; j < vbs; j++) {
                if (rng.nextDouble() < 0.075) data[j] = (byte)(1 + rng.nextInt(10));
            }
            vecs[c] = jdk.incubator.vector.ByteVector.fromArray(species, data, 0);
        }

        byte[] litBuf = new byte[chunks * vbs];
        int[] posBuf = new int[chunks * vbs];
        byte[] decodeTmp = new byte[vbs];

        // Warmup
        for (int w = 0; w < 3; w++) {
            int off = 0;
            for (int c = 0; c < chunks; c++) {
                var mask = vecs[c].compare(jdk.incubator.vector.VectorOperators.NE, 0);
                long bits = mask.toLong();
                if (bits != 0) {
                    vecs[c].intoArray(decodeTmp, 0);
                    while (bits != 0) {
                        int lane = Long.numberOfTrailingZeros(bits);
                        litBuf[off] = decodeTmp[lane];
                        posBuf[off] = lane + c * vbs;
                        off++;
                        bits &= (bits - 1);
                    }
                }
            }
        }

        // Strategy 1: intoArray + scatter (current)
        long start = System.nanoTime();
        int total1 = 0;
        for (int w = 0; w < 5; w++) {
            int off = 0;
            for (int c = 0; c < chunks; c++) {
                var mask = vecs[c].compare(jdk.incubator.vector.VectorOperators.NE, 0);
                long bits = mask.toLong();
                if (bits != 0) {
                    vecs[c].intoArray(decodeTmp, 0);
                    while (bits != 0) {
                        int lane = Long.numberOfTrailingZeros(bits);
                        litBuf[off] = decodeTmp[lane];
                        posBuf[off] = lane + c * vbs;
                        off++;
                        bits &= (bits - 1);
                    }
                }
            }
            total1 += off;
        }
        double s1ns = (double)(System.nanoTime() - start) / (5L * chunks);

        // Strategy 2: lane() direct (no temp array)
        start = System.nanoTime();
        int total2 = 0;
        for (int w = 0; w < 5; w++) {
            int off = 0;
            for (int c = 0; c < chunks; c++) {
                var acc = vecs[c];
                var mask = acc.compare(jdk.incubator.vector.VectorOperators.NE, 0);
                long bits = mask.toLong();
                if (bits != 0) {
                    while (bits != 0) {
                        int lane = Long.numberOfTrailingZeros(bits);
                        litBuf[off] = acc.lane(lane);
                        posBuf[off] = lane + c * vbs;
                        off++;
                        bits &= (bits - 1);
                    }
                }
            }
            total2 += off;
        }
        double s2ns = (double)(System.nanoTime() - start) / (5L * chunks);

        System.out.printf("intoArray+scatter: %.1f ns/chunk (%d total matches)%n", s1ns, total1 / 5);
        System.out.printf("lane() direct:     %.1f ns/chunk (%d total matches)%n", s2ns, total2 / 5);
    }

    @Test
    void profileDetectionOnly() {
        // Compare detection approaches WITHOUT decode — measure SIMD detection cost only
        var species = jdk.incubator.vector.ByteVector.SPECIES_PREFERRED;
        int vbs = species.vectorByteSize();
        var zero = jdk.incubator.vector.ByteVector.broadcast(species, (byte) 0);
        var lowMask = jdk.incubator.vector.ByteVector.broadcast(species, (byte) 0x0f);
        var highMask = jdk.incubator.vector.ByteVector.broadcast(species, (byte) 0x7f);

        // Build test LUT vectors (dummy but realistic)
        byte[] lutLow = new byte[vbs]; byte[] lutHigh = new byte[vbs];
        for (int i = 0; i < vbs; i++) { lutLow[i] = (byte)(i * 3); lutHigh[i] = (byte)(i * 7); }
        var lowLUT = jdk.incubator.vector.ByteVector.fromArray(species, lutLow, 0);
        var highLUT = jdk.incubator.vector.ByteVector.fromArray(species, lutHigh, 0);
        var lit1 = jdk.incubator.vector.ByteVector.broadcast(species, (byte) 5);
        var lit2 = jdk.incubator.vector.ByteVector.broadcast(species, (byte) 12);
        byte[] cleanBytes = new byte[vbs]; cleanBytes[5] = 5; cleanBytes[12] = 12;
        var cleanLUT = jdk.incubator.vector.ByteVector.fromArray(species, cleanBytes, 0);

        var rng = new SplittableRandom(42);
        int chunks = 2_000_000;
        jdk.incubator.vector.ByteVector[] vecs = new jdk.incubator.vector.ByteVector[chunks];
        for (int c = 0; c < chunks; c++) {
            byte[] d = new byte[vbs];
            for (int j = 0; j < vbs; j++) d[j] = (byte) rng.nextInt(128);
            vecs[c] = jdk.incubator.vector.ByteVector.fromArray(species, d, 0);
        }

        // Warmup
        for (int c = 0; c < 100000; c++) {
            var in = vecs[c % chunks];
            var lo = in.and(lowMask).selectFrom(lowLUT);
            var hi = in.lanewise(jdk.incubator.vector.VectorOperators.LSHR, 4).and(lowMask).selectFrom(highLUT);
            lo.and(hi).selectFrom(cleanLUT);
        }

        // NEW detection: selectFrom(cleanLUT)
        long start = System.nanoTime();
        var acc = zero;
        for (int c = 0; c < chunks; c++) {
            var in = vecs[c];
            var lo = in.and(lowMask).selectFrom(lowLUT);
            var hi = in.lanewise(jdk.incubator.vector.VectorOperators.LSHR, 4).and(lowMask).selectFrom(highLUT);
            var raw = lo.and(hi);
            acc = raw.selectFrom(cleanLUT).or(acc);
        }
        double newNs = (double)(System.nanoTime() - start) / chunks;

        // OLD detection: XOR + compare(EQ,0) per literal
        start = System.nanoTime();
        acc = zero;
        for (int c = 0; c < chunks; c++) {
            var in = vecs[c];
            var lo = lowLUT.rearrange(in.and(lowMask).toShuffle());
            var hi = highLUT.rearrange(in.lanewise(jdk.incubator.vector.VectorOperators.LSHR, 4).and(highMask).toShuffle());
            var buf = lo.and(hi);
            acc = acc.add(buf, buf.lanewise(jdk.incubator.vector.VectorOperators.XOR, lit1).compare(jdk.incubator.vector.VectorOperators.EQ, 0));
            acc = acc.add(buf, buf.lanewise(jdk.incubator.vector.VectorOperators.XOR, lit2).compare(jdk.incubator.vector.VectorOperators.EQ, 0));
        }
        double oldNs = (double)(System.nanoTime() - start) / chunks;

        System.out.printf("Detection only (no decode), %d-byte vectors, 2 literals:%n", vbs);
        System.out.printf("  NEW (selectFrom cleanLUT):     %.1f ns/chunk%n", newNs);
        System.out.printf("  OLD (XOR+compare per lit):     %.1f ns/chunk%n", oldNs);
        System.out.printf("  (acc lane0=%d to prevent DCE)%n", acc.lane(0));
    }

    @Test
    void profileOldVsNewDecode() {
        // Reproduce the old EngineKernel.decode pattern vs current decode
        var species = jdk.incubator.vector.ByteVector.SPECIES_PREFERRED;
        var intSpecies = species.withLanes(int.class);
        int vbs = species.vectorByteSize();
        int intBatchSize = intSpecies.length();

        var rng = new SplittableRandom(42);
        int chunks = 1_000_000;
        jdk.incubator.vector.ByteVector[] vecs = new jdk.incubator.vector.ByteVector[chunks];
        for (int c = 0; c < chunks; c++) {
            byte[] d = new byte[vbs];
            for (int j = 0; j < vbs; j++) {
                if (rng.nextDouble() < 0.20) d[j] = (byte)(1 + rng.nextInt(10));
            }
            vecs[c] = jdk.incubator.vector.ByteVector.fromArray(species, d, 0);
        }

        byte[] litBuf = new byte[chunks * vbs];
        int[] posBuf = new int[chunks * vbs];
        byte[] sparseCache = new byte[vbs];
        int[] posCache = new int[intBatchSize];
        byte[] decodeTmp = new byte[vbs];

        // Warmup both
        for (int w = 0; w < 3; w++) {
            int off = 0;
            for (int c = 0; c < chunks; c++) {
                var acc = vecs[c];
                acc.intoArray(decodeTmp, 0);
                var mask = acc.compare(jdk.incubator.vector.VectorOperators.NE, 0);
                long bits = mask.toLong();
                if (bits != 0) {
                    while (bits != 0) {
                        int lane = Long.numberOfTrailingZeros(bits);
                        litBuf[off] = decodeTmp[lane];
                        posBuf[off] = lane + c * vbs;
                        off++;
                        bits &= (bits - 1);
                    }
                }
            }
        }

        // NEW decode: anyTrue guard + intoArray + scalar scatter
        long start = System.nanoTime();
        int total1 = 0;
        for (int w = 0; w < 3; w++) {
            int off = 0;
            for (int c = 0; c < chunks; c++) {
                var acc = vecs[c];
                var mask = acc.compare(jdk.incubator.vector.VectorOperators.NE, 0);
                if (mask.anyTrue()) {
                    long bits = mask.toLong();
                    acc.intoArray(decodeTmp, 0);
                    while (bits != 0) {
                        int lane = Long.numberOfTrailingZeros(bits);
                        litBuf[off] = decodeTmp[lane];
                        posBuf[off] = lane + c * vbs;
                        off++;
                        bits &= (bits - 1);
                    }
                }
            }
            total1 = off;
        }
        double newNs = (double)(System.nanoTime() - start) / (3L * chunks);

        // OLD decode: unconditional intoArray + IntVector batched positions
        start = System.nanoTime();
        int total2 = 0;
        for (int w = 0; w < 3; w++) {
            int off = 0;
            for (int c = 0; c < chunks; c++) {
                var acc = vecs[c];
                acc.reinterpretAsBytes().intoArray(sparseCache, 0);
                var mask = acc.compare(jdk.incubator.vector.VectorOperators.NE, 0);
                long bits = mask.toLong();
                int count = mask.trueCount();
                if (count != 0) {
                    for (int k = 0; k < count; k += intBatchSize) {
                        for (int m = 0; m < posCache.length; m++) {
                            posCache[m] = Long.numberOfTrailingZeros(bits) & 0x3f;
                            litBuf[off + m] = sparseCache[posCache[m]];
                            bits = bits & (bits - 1);
                        }
                        var v = jdk.incubator.vector.IntVector.fromArray(intSpecies, posCache, 0);
                        v.add(c * vbs).intoArray(posBuf, off);
                        off += intBatchSize;
                    }
                }
            }
            total2 = off;
        }
        double oldNs = (double)(System.nanoTime() - start) / (3L * chunks);

        System.out.printf("20%% density, %d-byte vectors, intBatch=%d%n", vbs, intBatchSize);
        System.out.printf("  NEW (anyTrue+scatter):        %.1f ns/chunk (%d matches)%n", newNs, total1);
        System.out.printf("  OLD (unconditional+IntVec):   %.1f ns/chunk (%d matches)%n", oldNs, total2);
    }

    @Test
    void profileCompiledVsC2Jit() {
        // Compare: bytecode-compiled hidden class vs C2 JIT (compiled=false)
        var rng = new SplittableRandom(42);
        int size = 10 * 1024 * 1024;
        byte[] data = new byte[size];
        for (int i = 0; i < size; i++) data[i] = (byte)('a' + rng.nextInt(26));
        // 20% density with 4 different chars
        for (int i = 0; i < size; i++) {
            double r = rng.nextDouble();
            if (r < 0.05) data[i] = ',';
            else if (r < 0.10) data[i] = '"';
            else if (r < 0.15) data[i] = '\n';
            else if (r < 0.20) data[i] = '\r';
        }

        var arena = Arena.ofAuto();
        var segment = arena.allocate(size);
        MemorySegment.copy(MemorySegment.ofArray(data), 0, segment, 0, size);

        // Compiled hidden class
        var compiled = Utf8EngineBuilder.builder()
                .codepoints("quote", '"').codepoints("delim", ',')
                .codepoints("lf", '\n').codepoints("cr", '\r')
                .compiled(true).build();

        // C2 JIT only (no bytecode compilation)
        var c2jit = Utf8EngineBuilder.builder()
                .codepoints("quote", '"').codepoints("delim", ',')
                .codepoints("lf", '\n').codepoints("cr", '\r')
                .compiled(false).build();

        var storage = new MatchStorage(size / 4, 64);

        // Warmup both
        for (int i = 0; i < 30; i++) {
            compiled.engine().find(segment, storage);
            c2jit.engine().find(segment, storage);
        }

        int iters = 50;
        double dataMb = size / (1024.0 * 1024.0);

        long start = System.nanoTime();
        int m1 = 0;
        for (int i = 0; i < iters; i++) m1 += compiled.engine().find(segment, storage).size();
        double compiledMbps = (dataMb * iters) / ((System.nanoTime() - start) / 1e9);

        start = System.nanoTime();
        int m2 = 0;
        for (int i = 0; i < iters; i++) m2 += c2jit.engine().find(segment, storage).size();
        double c2jitMbps = (dataMb * iters) / ((System.nanoTime() - start) / 1e9);

        System.out.printf("20%% density, 4 chars:%n");
        System.out.printf("  Compiled (hidden class): %.1f MB/s (%d matches)%n", compiledMbps, m1/iters);
        System.out.printf("  C2 JIT (plain class):    %.1f MB/s (%d matches)%n", c2jitMbps, m2/iters);
    }

    @Test
    void profileVaryingDensity() {
        // Test scan speed at different match densities to understand the decode cost curve
        var arena = Arena.ofAuto();
        int size = 10 * 1024 * 1024;

        for (double targetDensity : new double[]{0.0, 0.01, 0.02, 0.05, 0.075, 0.10, 0.15, 0.20}) {
            var rng = new SplittableRandom(42);
            byte[] data = new byte[size];
            for (int i = 0; i < size; i++) data[i] = (byte)('a' + rng.nextInt(26));
            if (targetDensity > 0) {
                for (int i = 0; i < size; i++) {
                    if (rng.nextDouble() < targetDensity) data[i] = ',';
                }
            }

            var result = Utf8EngineBuilder.builder().codepoints("comma", ',').build();
            var engine = result.engine();
            var segment = arena.allocate(size);
            MemorySegment.copy(MemorySegment.ofArray(data), 0, segment, 0, size);
            var storage = new MatchStorage(size / 4, 64);

            for (int i = 0; i < 20; i++) engine.find(segment, storage);

            int iters = 100;
            long start = System.nanoTime();
            int matches = 0;
            for (int i = 0; i < iters; i++) matches += engine.find(segment, storage).size();
            double mbps = (size / (1024.0 * 1024.0) * iters) / ((System.nanoTime() - start) / 1e9);
            System.out.printf("Density %.1f%%: %7.1f MB/s (%d matches)%n",
                    targetDensity * 100, mbps, matches / iters);
        }
    }

    @Test
    void profileMaskConversion() {
        // How much does toLong() + trueCount() cost per chunk on ARM?
        var species = jdk.incubator.vector.ByteVector.SPECIES_PREFERRED;
        var zero = jdk.incubator.vector.ByteVector.broadcast(species, (byte) 0);
        byte[] testData = new byte[species.vectorByteSize()];
        testData[3] = 5; testData[7] = 12; // 2 matches
        var testVec = jdk.incubator.vector.ByteVector.fromArray(species, testData, 0);

        // Warmup
        for (int i = 0; i < 100_000; i++) {
            var mask = testVec.compare(jdk.incubator.vector.VectorOperators.NE, 0);
            mask.toLong();
        }

        int iters = 10_000_000;

        // Just compare
        long start = System.nanoTime();
        long dummy = 0;
        for (int i = 0; i < iters; i++) {
            var mask = testVec.compare(jdk.incubator.vector.VectorOperators.NE, 0);
            dummy += mask.trueCount();
        }
        double compareNs = (double)(System.nanoTime() - start) / iters;

        // compare + toLong
        start = System.nanoTime();
        for (int i = 0; i < iters; i++) {
            var mask = testVec.compare(jdk.incubator.vector.VectorOperators.NE, 0);
            dummy += mask.toLong();
        }
        double toLongNs = (double)(System.nanoTime() - start) / iters;

        // compare + toLong + intoArray
        byte[] tmp = new byte[species.vectorByteSize()];
        start = System.nanoTime();
        for (int i = 0; i < iters; i++) {
            var mask = testVec.compare(jdk.incubator.vector.VectorOperators.NE, 0);
            dummy += mask.toLong();
            testVec.intoArray(tmp, 0);
        }
        double intoArrayNs = (double)(System.nanoTime() - start) / iters;

        System.out.printf("compare+trueCount:   %.1f ns%n", compareNs);
        System.out.printf("compare+toLong:      %.1f ns%n", toLongNs);
        System.out.printf("compare+toLong+into: %.1f ns%n", intoArrayNs);
        System.out.printf("(dummy=%d)%n", dummy); // prevent DCE
    }

    @Test
    void profileDecodeOverhead() {
        // Measure: how much of the scan time is in the decode phase (compress+bit scan)?
        // Compare engine scan vs a "scan without decode" approach.
        var rng = new SplittableRandom(42);
        var config = new CsvDataGenerator.CsvConfig(10, 20, 0.1, 0.3, false);
        byte[] csvData = CsvDataGenerator.generateCsvData(rng, 10 * 1024 * 1024, config);

        var arena = Arena.ofAuto();
        var segment = arena.allocate(csvData.length);
        MemorySegment.copy(MemorySegment.ofArray(csvData), 0, segment, 0, csvData.length);

        // Engine that finds CSV chars
        var result = Utf8EngineBuilder.builder()
                .codepoints("quote", '"')
                .codepoints("delim", ',')
                .codepoints("lf", '\n')
                .codepoints("cr", '\r')
                .build();
        var engine = result.engine();

        // Low-density engine (finds only rare chars — minimal decode work)
        var lowDensity = Utf8EngineBuilder.builder()
                .codepoints("tilde", '~')
                .build();
        var lowEngine = lowDensity.engine();

        var storage = new MatchStorage(csvData.length / 4, 64);
        var lowStorage = new MatchStorage(1024, 64);

        // Warmup
        for (int i = 0; i < 10; i++) {
            engine.find(segment, storage);
            lowEngine.find(segment, lowStorage);
        }

        int iters = 50;
        double dataMb = csvData.length / (1024.0 * 1024.0);

        // High-density scan (CSV chars: ~7.5% match density → lots of decode work)
        long start = System.nanoTime();
        int highMatches = 0;
        for (int i = 0; i < iters; i++) {
            highMatches += engine.find(segment, storage).size();
        }
        double highMbps = (dataMb * iters) / ((System.nanoTime() - start) / 1e9);

        // Low-density scan (tilde: ~0% match density → minimal decode work)
        start = System.nanoTime();
        int lowMatches = 0;
        for (int i = 0; i < iters; i++) {
            lowMatches += lowEngine.find(segment, lowStorage).size();
        }
        double lowMbps = (dataMb * iters) / ((System.nanoTime() - start) / 1e9);

        System.out.printf("High-density scan: %.1f MB/s (%d matches/iter, %.1f%% density)%n",
                highMbps, highMatches / iters, 100.0 * (highMatches / iters) / csvData.length);
        System.out.printf("Low-density scan:  %.1f MB/s (%d matches/iter)%n", lowMbps, lowMatches / iters);
        System.out.printf("Decode overhead:   %.1f%% (from match density)%n",
                100.0 * (1.0 - highMbps / lowMbps));
    }

    @Test
    void profileRawFindOnly() {
        // Just finding commas — minimal libfindchars, no filter, no CSV
        var rng = new SplittableRandom(42);
        byte[] data = new byte[10 * 1024 * 1024];
        for (int i = 0; i < data.length; i++) data[i] = (byte) ('a' + rng.nextInt(26));
        // Sprinkle ~5% commas
        for (int i = 0; i < data.length; i++) if (rng.nextDouble() < 0.05) data[i] = ',';

        var result = Utf8EngineBuilder.builder().codepoints("comma", ',').build();
        var engine = result.engine();

        var arena = Arena.ofAuto();
        var segment = arena.allocate(data.length);
        MemorySegment.copy(MemorySegment.ofArray(data), 0, segment, 0, data.length);

        var storage = new MatchStorage(data.length / 4, 64);
        for (int i = 0; i < 10; i++) engine.find(segment, storage);

        int iters = 50;
        long start = System.nanoTime();
        int total = 0;
        for (int i = 0; i < iters; i++) {
            var v = engine.find(segment, storage);
            total += v.size();
        }
        double mbps = (data.length / (1024.0 * 1024.0) * iters) / ((System.nanoTime() - start) / 1e9);
        System.out.printf("Raw find (1 char): %.1f MB/s (%d matches/iter)%n", mbps, total / iters);
    }

    @Test
    void profilePrefixXorIsolated() {
        // Microbenchmark: how fast is prefixXor in a tight loop?
        var species = jdk.incubator.vector.ByteVector.SPECIES_PREFERRED;
        var zero = jdk.incubator.vector.ByteVector.broadcast(species, (byte) 0);
        var allOnes = jdk.incubator.vector.ByteVector.broadcast(species, (byte) -1);

        // Build a test vector with some bits set
        byte[] testData = new byte[species.vectorByteSize()];
        testData[3] = (byte) -1;
        testData[7] = (byte) -1;
        testData[12] = (byte) -1;
        var testVec = jdk.incubator.vector.ByteVector.fromArray(species, testData, 0);

        // Warmup
        for (int i = 0; i < 100_000; i++) {
            org.knownhosts.libfindchars.api.VpaKernel.prefixXor(testVec, zero, species);
        }

        int iters = 10_000_000;
        long start = System.nanoTime();
        var r = zero;
        for (int i = 0; i < iters; i++) {
            r = org.knownhosts.libfindchars.api.VpaKernel.prefixXor(testVec, zero, species);
        }
        long elapsed = System.nanoTime() - start;
        double nsPerCall = (double) elapsed / iters;

        // Also time VectorShuffle.iota alone
        start = System.nanoTime();
        jdk.incubator.vector.VectorShuffle<Byte> s = null;
        for (int i = 0; i < iters; i++) {
            s = jdk.incubator.vector.VectorShuffle.iota(species, -1, 1, false);
        }
        long iotaElapsed = System.nanoTime() - start;
        double iotaNsPerCall = (double) iotaElapsed / iters;

        System.out.printf("Species: %s (%d bytes)%n", species, species.vectorByteSize());
        System.out.printf("prefixXor:         %.1f ns/call%n", nsPerCall);
        System.out.printf("VectorShuffle.iota: %.1f ns/call%n", iotaNsPerCall);
        System.out.printf("prefixXor result lane[15] = %d%n", r.lane(Math.min(15, species.vectorByteSize() - 1)));
    }

    @Test
    void profileScanWithoutFilter() {
        var rng = new SplittableRandom(42);
        var config = new CsvDataGenerator.CsvConfig(10, 20, 0.1, 0.3, false);
        byte[] csvData = CsvDataGenerator.generateCsvData(rng, 10 * 1024 * 1024, config);

        var arena = Arena.ofAuto();
        var segment = arena.allocate(csvData.length);
        MemorySegment.copy(MemorySegment.ofArray(csvData), 0, segment, 0, csvData.length);

        // Engine WITHOUT filter for baseline scan speed
        var noFilter = Utf8EngineBuilder.builder()
                .codepoints("quote", '"')
                .codepoints("delim", ',')
                .codepoints("lf", '\n')
                .codepoints("cr", '\r')
                .build();

        // Engine WITH filter
        var withFilter = Utf8EngineBuilder.builder()
                .codepoints("quote", '"')
                .codepoints("delim", ',')
                .codepoints("lf", '\n')
                .codepoints("cr", '\r')
                .chunkFilter(CsvQuoteFilter.class, "quote", "delim", "lf", "cr")
                .build();

        var storage = new MatchStorage(csvData.length / 4, 64);

        // Warmup
        for (int i = 0; i < 10; i++) {
            noFilter.engine().find(segment, storage);
            withFilter.engine().find(segment, storage);
        }

        int iters = 50;
        double dataMb = csvData.length / (1024.0 * 1024.0);

        // No filter
        long start = System.nanoTime();
        for (int i = 0; i < iters; i++) noFilter.engine().find(segment, storage);
        double noFilterMbps = (dataMb * iters) / ((System.nanoTime() - start) / 1e9);

        // With filter
        start = System.nanoTime();
        for (int i = 0; i < iters; i++) withFilter.engine().find(segment, storage);
        double withFilterMbps = (dataMb * iters) / ((System.nanoTime() - start) / 1e9);

        System.out.printf("Scan NO filter:    %.1f MB/s%n", noFilterMbps);
        System.out.printf("Scan WITH filter:  %.1f MB/s%n", withFilterMbps);
        System.out.printf("Filter overhead:   %.1f%%%n", 100.0 * (1.0 - withFilterMbps / noFilterMbps));
    }
}
