package org.knownhosts.libfindchars.api;

import java.util.Arrays;
import java.util.List;

import jdk.incubator.vector.ByteVector;
import jdk.incubator.vector.VectorOperators;
import jdk.incubator.vector.VectorSpecies;

public final class Utf8ShuffleMaskOp {

    public record CharSpec(int byteLength, ByteVector[] roundLiteralVecs, ByteVector finalLiteralVec) {}

    private static final byte[] CLASSIFY_TABLE = {
        1, 1, 1, 1, 1, 1, 1, 1,  // 0x0-0x7: ASCII
        0, 0, 0, 0,              // 0x8-0xB: continuation
        2, 2,                    // 0xC-0xD: 2-byte start
        3,                       // 0xE: 3-byte start
        4                        // 0xF: 4-byte start
    };

    private final int maxRounds;
    private final ByteVector classifyVec;
    private final ByteVector lowMask;
    private final ByteVector zero;

    private final ByteVector[] lowLUTs;
    private final ByteVector[] highLUTs;
    private final ByteVector[][] literalVecs;
    private final CharSpec[] charSpecs;

    public Utf8ShuffleMaskOp(VectorSpecies<Byte> species, List<FindMask> roundMasks, List<CharSpec> charSpecs) {
        this.maxRounds = roundMasks.size();
        this.classifyVec = ByteVector.fromArray(species,
                Arrays.copyOf(CLASSIFY_TABLE, species.vectorByteSize()), 0);
        this.lowMask = ByteVector.broadcast(species, 0x0f);
        this.zero = ByteVector.broadcast(species, (byte) 0);

        this.lowLUTs = new ByteVector[maxRounds];
        this.highLUTs = new ByteVector[maxRounds];
        this.literalVecs = new ByteVector[maxRounds][];

        for (int r = 0; r < maxRounds; r++) {
            var mask = roundMasks.get(r);
            lowLUTs[r] = ByteVector.fromArray(species,
                    Arrays.copyOf(mask.lowNibbleMask(), species.vectorByteSize()), 0);
            highLUTs[r] = ByteVector.fromArray(species,
                    Arrays.copyOf(mask.highNibbleMask(), species.vectorByteSize()), 0);

            var literals = mask.literals().values();
            literalVecs[r] = new ByteVector[literals.size()];
            int j = 0;
            for (byte lit : literals) {
                literalVecs[r][j++] = ByteVector.broadcast(species, lit);
            }
        }

        this.charSpecs = charSpecs.toArray(new CharSpec[0]);
    }

    public int maxRounds() {
        return maxRounds;
    }

    public ByteVector apply(ByteVector chunk, ByteVector r1Input, ByteVector r2Input, ByteVector r3Input, ByteVector accumulator) {
        // 1. Round 0 shuffle mask + literal filter (needed for both paths)
        var r0 = applyRoundMask(chunk, 0);

        if (!Utf8Kernel.hasNonAscii(chunk)) {
            // ASCII fast path — no classify needed, skip round 1+, skip all gateMultiByte
            return Utf8Kernel.gateAsciiOnly(accumulator, r0);
        }

        // Multi-byte slow path — classify only computed here
        var classify = Utf8Kernel.classify(chunk, classifyVec, lowMask);
        var r1 = (maxRounds >= 2) ? applyRoundMask(r1Input, 1) : zero;
        var r2 = (maxRounds >= 3) ? applyRoundMask(r2Input, 2) : zero;
        var r3 = (maxRounds >= 4) ? applyRoundMask(r3Input, 3) : zero;

        // 3a. ASCII gate
        accumulator = Utf8Kernel.gateAscii(accumulator, r0, classify);

        // 3b. Multi-byte: per-character gating to prevent cross-byte contamination
        for (var spec : charSpecs) {
            accumulator = switch (spec.byteLength()) {
                case 2 -> Utf8Kernel.gateMultiByte2(accumulator, classify, spec.finalLiteralVec(),
                        r0, r1, spec.roundLiteralVecs()[0], spec.roundLiteralVecs()[1]);
                case 3 -> Utf8Kernel.gateMultiByte3(accumulator, classify, spec.finalLiteralVec(),
                        r0, r1, r2,
                        spec.roundLiteralVecs()[0], spec.roundLiteralVecs()[1], spec.roundLiteralVecs()[2]);
                case 4 -> Utf8Kernel.gateMultiByte4(accumulator, classify, spec.finalLiteralVec(),
                        r0, r1, r2, r3,
                        spec.roundLiteralVecs()[0], spec.roundLiteralVecs()[1],
                        spec.roundLiteralVecs()[2], spec.roundLiteralVecs()[3]);
                default -> gateMultiByteLoop(accumulator, classify, spec, r0, r1, r2, r3);
            };
        }

        return accumulator;
    }

    private ByteVector applyRoundMask(ByteVector input, int round) {
        return switch (literalVecs[round].length) {
            case 1 -> Utf8Kernel.applyRoundMask1(input, lowLUTs[round], highLUTs[round],
                    lowMask, zero, literalVecs[round][0]);
            case 2 -> Utf8Kernel.applyRoundMask2(input, lowLUTs[round], highLUTs[round],
                    lowMask, zero, literalVecs[round][0], literalVecs[round][1]);
            case 3 -> Utf8Kernel.applyRoundMask3(input, lowLUTs[round], highLUTs[round],
                    lowMask, zero, literalVecs[round][0], literalVecs[round][1], literalVecs[round][2]);
            case 4 -> Utf8Kernel.applyRoundMask4(input, lowLUTs[round], highLUTs[round],
                    lowMask, zero,
                    literalVecs[round][0], literalVecs[round][1],
                    literalVecs[round][2], literalVecs[round][3]);
            case 5 -> Utf8Kernel.applyRoundMask5(input, lowLUTs[round], highLUTs[round],
                    lowMask, zero,
                    literalVecs[round][0], literalVecs[round][1],
                    literalVecs[round][2], literalVecs[round][3],
                    literalVecs[round][4]);
            case 6 -> Utf8Kernel.applyRoundMask6(input, lowLUTs[round], highLUTs[round],
                    lowMask, zero,
                    literalVecs[round][0], literalVecs[round][1],
                    literalVecs[round][2], literalVecs[round][3],
                    literalVecs[round][4], literalVecs[round][5]);
            case 7 -> Utf8Kernel.applyRoundMask7(input, lowLUTs[round], highLUTs[round],
                    lowMask, zero,
                    literalVecs[round][0], literalVecs[round][1],
                    literalVecs[round][2], literalVecs[round][3],
                    literalVecs[round][4], literalVecs[round][5],
                    literalVecs[round][6]);
            case 8 -> Utf8Kernel.applyRoundMask8(input, lowLUTs[round], highLUTs[round],
                    lowMask, zero,
                    literalVecs[round][0], literalVecs[round][1],
                    literalVecs[round][2], literalVecs[round][3],
                    literalVecs[round][4], literalVecs[round][5],
                    literalVecs[round][6], literalVecs[round][7]);
            default -> applyRoundMaskLoop(input, round);
        };
    }

    private ByteVector applyRoundMaskLoop(ByteVector input, int round) {
        var lo = lowLUTs[round].rearrange(input.and(lowMask).toShuffle());
        var hi = highLUTs[round].rearrange(
                input.lanewise(VectorOperators.LSHR, 4).and(lowMask).toShuffle());
        var raw = lo.and(hi);
        var cleaned = zero;
        for (var litVec : literalVecs[round]) {
            cleaned = cleaned.add(raw, raw.compare(VectorOperators.EQ, litVec));
        }
        return cleaned;
    }

    private ByteVector gateMultiByteLoop(ByteVector accumulator, ByteVector classify, CharSpec spec,
            ByteVector r0, ByteVector r1, ByteVector r2, ByteVector r3) {
        ByteVector[] roundResults = {r0, r1, r2, r3};
        var gate = classify.compare(VectorOperators.EQ, spec.byteLength());
        for (int r = 0; r < spec.byteLength(); r++) {
            gate = gate.and(roundResults[r].compare(VectorOperators.EQ, spec.roundLiteralVecs()[r]));
        }
        return accumulator.add(spec.finalLiteralVec(), gate);
    }
}
