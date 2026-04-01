package org.knownhosts.libfindchars.csv;

import java.lang.foreign.MemorySegment;
import java.util.stream.IntStream;
import java.util.stream.Stream;

/**
 * Result of parsing a CSV. Holds flat field boundary arrays internally;
 * creates {@link CsvRow} views on demand. All data access is zero-copy
 * until String materialization.
 */
public final class CsvResult {

    private final MemorySegment data;

    // Flat field boundary arrays.  Arrays may be over-allocated (capacity > actual
    // field count) because the pre-scan counts all matches including quotes.
    // fieldStarts[i] / fieldEnds[i] : half-open byte range [start, end) for field i.
    // fieldFlags[i] bit 0 : 1 if the field was quoted.
    // rowFieldOffset[r] .. rowFieldOffset[r+1] : field index range for row r.
    final long[] fieldStarts;
    final long[] fieldEnds;
    final byte[] fieldFlags;
    final int[] rowFieldOffset;
    private final int rowCount;
    private final int dataRowStart; // 0 if no header, 1 if header was extracted
    private final String[] headers;
    final byte quoteChar;           // quote byte for CsvField dequoting

    CsvResult(MemorySegment data, long[] fieldStarts, long[] fieldEnds,
              byte[] fieldFlags, int[] rowFieldOffset, int rowCount,
              int dataRowStart, String[] headers, byte quoteChar) {
        this.data = data;
        this.fieldStarts = fieldStarts;
        this.fieldEnds = fieldEnds;
        this.fieldFlags = fieldFlags;
        this.rowFieldOffset = rowFieldOffset;
        this.rowCount = rowCount;
        this.dataRowStart = dataRowStart;
        this.headers = headers;
        this.quoteChar = quoteChar;
    }

    /** Number of data rows (excluding header). */
    public int rowCount() {
        return rowCount - dataRowStart;
    }

    /**
     * Get a view of row at the given index (zero-based, excludes header).
     *
     * @throws IndexOutOfBoundsException if index is negative or &ge; {@link #rowCount()}
     */
    public CsvRow row(int index) {
        int count = rowCount();
        if (index < 0 || index >= count)
            throw new IndexOutOfBoundsException("Row " + index + ", size " + count);
        int actualRow = index + dataRowStart;
        int fieldStart = rowFieldOffset[actualRow];
        int fieldEnd = rowFieldOffset[actualRow + 1];
        return new CsvRow(data, this, fieldStart, fieldEnd - fieldStart);
    }

    /** Header names (if hasHeader was true), otherwise null. The returned array is shared; do not modify it. */
    public String[] headers() {
        return headers;
    }

    /** Stream all data rows. Views are created lazily. */
    public Stream<CsvRow> stream() {
        return IntStream.range(0, rowCount()).mapToObj(this::row);
    }

    /** Materialize all rows as a fresh array. Allocates on each call; prefer {@link #row(int)} or {@link #stream()} for lazy access. */
    public CsvRow[] rows() {
        int count = rowCount();
        CsvRow[] result = new CsvRow[count];
        for (int i = 0; i < count; i++) {
            result[i] = row(i);
        }
        return result;
    }

    private static final CsvResult EMPTY = new CsvResult(
            MemorySegment.NULL, new long[0], new long[0],
            new byte[0], new int[]{0}, 0, 0, null, (byte) '"');

    static CsvResult empty() {
        return EMPTY;
    }
}
