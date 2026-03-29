package org.knownhosts.libfindchars.csv;

import java.lang.foreign.MemorySegment;

/**
 * Zero-copy view of a single CSV row. Fields are not materialized until accessed.
 */
public final class CsvRow {

    private final CsvField[] fields;
    private final MemorySegment data;

    CsvRow(MemorySegment data, CsvField[] fields) {
        this.data = data;
        this.fields = fields;
    }

    public int fieldCount() {
        return fields.length;
    }

    /** Get field value as String (materializes on demand). */
    public String get(int column) {
        return fields[column].value(data);
    }

    /** Get raw field reference (zero-copy). */
    public CsvField field(int column) {
        return fields[column];
    }

    /** Get raw MemorySegment slice for a field (zero-copy). */
    public MemorySegment rawField(int column) {
        return fields[column].rawSlice(data);
    }
}
