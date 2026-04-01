package org.knownhosts.libfindchars.csv;

import java.lang.foreign.MemorySegment;

/**
 * Zero-copy view of a single CSV row. Backed by flat arrays in {@link CsvResult}.
 * Fields are not materialized until accessed.
 */
public final class CsvRow {

    private final MemorySegment data;  // cached from result to avoid indirection on hot path
    private final CsvResult result;
    private final int baseFieldIndex;
    private final int fieldCount;

    CsvRow(MemorySegment data, CsvResult result, int baseFieldIndex, int fieldCount) {
        this.data = data;
        this.result = result;
        this.baseFieldIndex = baseFieldIndex;
        this.fieldCount = fieldCount;
    }

    public int fieldCount() {
        return fieldCount;
    }

    /**
     * Get field value as String. Constructs a transient {@link CsvField} per call;
     * prefer {@link #rawField(int)} for zero-allocation access.
     *
     * @throws IndexOutOfBoundsException if column is negative or &ge; {@link #fieldCount()}
     */
    public String get(int column) {
        checkColumn(column);
        int fi = baseFieldIndex + column;
        return new CsvField(
                result.fieldStarts[fi],
                result.fieldEnds[fi],
                result.fieldFlags[fi] != 0,
                result.quoteChar
        ).value(data);
    }

    /**
     * Get field metadata (byte offsets, quoted flag). Constructs a transient
     * {@link CsvField} per call. Use {@link #get(int)} for the String value,
     * or {@link #rawField(int)} for a zero-copy MemorySegment slice.
     *
     * @throws IndexOutOfBoundsException if column is negative or &ge; {@link #fieldCount()}
     */
    public CsvField field(int column) {
        checkColumn(column);
        int fi = baseFieldIndex + column;
        return new CsvField(
                result.fieldStarts[fi],
                result.fieldEnds[fi],
                result.fieldFlags[fi] != 0,
                result.quoteChar
        );
    }

    /**
     * Get raw {@link MemorySegment} slice for a field (zero-copy, zero-allocation).
     *
     * @throws IndexOutOfBoundsException if column is negative or &ge; {@link #fieldCount()}
     */
    public MemorySegment rawField(int column) {
        checkColumn(column);
        int fi = baseFieldIndex + column;
        long start = result.fieldStarts[fi];
        long end = result.fieldEnds[fi];
        return data.asSlice(start, end - start);
    }

    private void checkColumn(int column) {
        if (column < 0 || column >= fieldCount)
            throw new IndexOutOfBoundsException("Column " + column + ", size " + fieldCount);
    }
}
