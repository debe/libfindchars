package org.knownhosts.libfindchars.csv;

import java.util.Arrays;
import java.util.stream.Stream;

/**
 * Result of parsing a CSV. Holds pre-computed row/field boundaries.
 * All data access is zero-copy until String materialization.
 *
 * @param rows    all data rows (excluding header if hasHeader was true)
 * @param headers header names (if hasHeader was true), otherwise null
 */
public record CsvResult(CsvRow[] rows, String[] headers) {

    public int rowCount() {
        return rows.length;
    }

    public CsvRow row(int index) {
        return rows[index];
    }

    /** Stream all data rows. */
    public Stream<CsvRow> stream() {
        return Arrays.stream(rows);
    }
}
