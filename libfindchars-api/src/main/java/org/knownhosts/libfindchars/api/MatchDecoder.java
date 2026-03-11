package org.knownhosts.libfindchars.api;

import jdk.incubator.vector.ByteVector;
import jdk.incubator.vector.IntVector;
import jdk.incubator.vector.Vector;
import jdk.incubator.vector.VectorOperators;
import jdk.incubator.vector.VectorSpecies;

public final class MatchDecoder {
    private final VectorSpecies<Integer> intSpecies;
    private final int intBatchSize;

    private final byte[] literalCacheSparse;
    private final int[] positionCache;

    public MatchDecoder(VectorSpecies<Byte> byteSpecies) {
        this.intSpecies = byteSpecies.withLanes(int.class);
        this.intBatchSize = intSpecies.length();
        this.literalCacheSparse = new byte[intSpecies.vectorByteSize()];
        this.positionCache = new int[intBatchSize];
    }

    public MatchDecoder() {
        this(ByteVector.SPECIES_PREFERRED);
    }

    int decode8WithLiteral(MatchStorage tapeStorage, int bits, int offset, int arrayOffset) {
        for (int i = 0; i < positionCache.length; i++) {
            positionCache[i] = Integer.numberOfTrailingZeros(bits) & 0x1f; // modulo 32 in fast;
            tapeStorage.getLiteralBuffer()[(arrayOffset) + i] = literalCacheSparse[positionCache[i]];
            bits = bits & (bits - 1);
        }
        var v = IntVector.fromArray(intSpecies, positionCache, 0);
        var added = v.add(offset);

        added.intoArray(tapeStorage.getPositionsBuffer(), arrayOffset);

        return bits;
    }

    public int decodePos(MatchStorage tapeStorage, Vector<Byte> positions, int segmentOffset, int fileOffset) {

        positions.reinterpretAsBytes().intoArray(literalCacheSparse, 0);
        var findMask = positions.compare(VectorOperators.NE, 0);

        var bits = (int) findMask.toLong();
        var count = findMask.trueCount();

        if (count == 0) {
            return 0;
        }

        var arrayOffset = segmentOffset;

        for (int i = 0; i < (count); i = i + intBatchSize) {
            bits = decode8WithLiteral(tapeStorage, bits, fileOffset, arrayOffset);
            arrayOffset += intBatchSize; // peek batch size
        }

        return count;

    }

}
