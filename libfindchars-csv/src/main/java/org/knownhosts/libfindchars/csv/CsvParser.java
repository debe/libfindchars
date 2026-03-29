package org.knownhosts.libfindchars.csv;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;

import org.knownhosts.libfindchars.api.FindEngine;
import org.knownhosts.libfindchars.api.MatchStorage;
import org.knownhosts.libfindchars.api.MatchView;
import org.knownhosts.libfindchars.generator.Utf8EngineBuilder;

/**
 * SIMD-accelerated CSV parser using libfindchars VPA framework.
 *
 * <p>Two-phase architecture:
 * <ol>
 *   <li><b>SIMD scan + filter</b>: The engine detects {@code ,} {@code "} {@code \n} {@code \r}
 *       at ~1.8 GB/s, and the {@link CsvQuoteFilter} zeros out structural characters
 *       inside quoted regions using vectorized prefix XOR.</li>
 *   <li><b>Match walker</b>: A simple linear scan over filtered matches — every comma is
 *       a field boundary, every newline is a row boundary. No state machine needed.</li>
 * </ol>
 *
 * <pre>{@code
 * var parser = CsvParser.builder().delimiter(',').quote('"').hasHeader(true).build();
 * try (var arena = Arena.ofConfined()) {
 *     var result = parser.parse(Path.of("data.csv"), arena);
 *     for (int i = 0; i < result.rowCount(); i++) {
 *         System.out.println(result.row(i).get(0));
 *     }
 * }
 * }</pre>
 */
public final class CsvParser {

    private final FindEngine engine;
    private final byte quoteLit;
    private final byte delimLit;
    private final byte newlineLit;
    private final byte crLit;
    private final byte quoteChar;
    private final boolean hasHeader;

    private final CsvToken[] literalToToken;

    private CsvParser(FindEngine engine, byte quoteLit, byte delimLit,
                      byte newlineLit, byte crLit, byte quoteChar,
                      boolean hasHeader) {
        this.engine = engine;
        this.quoteLit = quoteLit;
        this.delimLit = delimLit;
        this.newlineLit = newlineLit;
        this.crLit = crLit;
        this.quoteChar = quoteChar;
        this.hasHeader = hasHeader;

        // Pre-build literal-byte → CsvToken lookup table (256 entries, indexed by unsigned byte)
        this.literalToToken = new CsvToken[256];
        literalToToken[quoteLit & 0xFF] = CsvToken.QUOTE;
        literalToToken[delimLit & 0xFF] = CsvToken.DELIMITER;
        literalToToken[newlineLit & 0xFF] = CsvToken.NEWLINE;
        literalToToken[crLit & 0xFF] = CsvToken.CR;
    }

    public static Builder builder() {
        return new Builder();
    }

    /**
     * Zero-allocation scan: returns a {@link CsvMatchView} over the engine's
     * flat arrays. The consumer iterates token types and positions directly —
     * no CsvRow/CsvField objects are created.
     */
    public CsvMatchView scan(MemorySegment data) {
        long dataSize = data.byteSize();
        if (dataSize == 0) {
            return new CsvMatchView(new MatchView(0),
                    new MatchStorage(0, 0), literalToToken, newlineLit);
        }
        var storage = new MatchStorage((int) Math.min(dataSize / 4, Integer.MAX_VALUE), 64);
        var view = engine.find(data, storage);
        return new CsvMatchView(view, storage, literalToToken, newlineLit);
    }

    /**
     * Zero-allocation scan from byte array. No padding required —
     * the engine handles sub-vector-width inputs internally.
     */
    public CsvMatchView scan(byte[] data) {
        return scan(MemorySegment.ofArray(data));
    }

    /**
     * Parse CSV data from a MemorySegment. Zero-copy: the returned CsvResult
     * holds field boundaries as offsets into the original segment.
     */
    public CsvResult parse(MemorySegment data) {
        long dataSize = data.byteSize();
        if (dataSize == 0) {
            return new CsvResult(new CsvRow[0], null);
        }

        var storage = new MatchStorage((int) Math.min(dataSize / 4, Integer.MAX_VALUE), 64);
        var view = engine.find(data, storage);
        return walkMatches(data, view, storage, dataSize);
    }

    /** Parse CSV data from a byte array. */
    public CsvResult parse(byte[] data) {
        return parse(MemorySegment.ofArray(data));
    }

    /**
     * Memory-map a file and parse it. The caller controls the Arena lifetime —
     * the CsvResult remains valid as long as the Arena is open.
     */
    public CsvResult parse(Path path, Arena arena) throws IOException {
        try (var channel = FileChannel.open(path, StandardOpenOption.READ)) {
            long size = channel.size();
            var mapped = channel.map(FileChannel.MapMode.READ_ONLY, 0, size, arena);
            return parse(mapped);
        }
    }

    private CsvResult walkMatches(MemorySegment data, MatchView view,
                                   MatchStorage storage, long dataSize) {
        int matchCount = view.size();
        // Count newlines to pre-size rows list (single pass over compact literal buffer)
        byte[] litBuf = storage.getLiteralBuffer();
        int rowEstimate = 0;
        for (int i = 0; i < matchCount; i++) {
            if (litBuf[i] == newlineLit) rowEstimate++;
        }
        var rows = new ArrayList<CsvRow>(Math.max(16, rowEstimate));
        var currentFields = new ArrayList<CsvField>(16);

        long fieldStart = 0;
        boolean fieldIsQuoted = false;

        for (int m = 0; m < matchCount; m++) {
            long pos = view.getPositionAt(storage, m);
            byte lit = view.getLiteralAt(storage, m);

            if (lit == quoteLit) {
                // Quote in output: at field start → mark as quoted
                if (pos == fieldStart) {
                    fieldIsQuoted = true;
                }
                // Other quote positions are closing quotes or escaped "" pairs.
                // CsvField.value() handles unescaping during materialization.
            } else if (lit == delimLit) {
                // Field boundary
                currentFields.add(new CsvField(fieldStart, pos, fieldIsQuoted, quoteChar));
                fieldStart = pos + 1;
                fieldIsQuoted = false;
            } else if (lit == newlineLit) {
                // Row boundary. Check for CRLF: if previous match was CR at pos-1
                long fieldEnd = pos;
                if (m > 0) {
                    long prevPos = view.getPositionAt(storage, m - 1);
                    byte prevLit = view.getLiteralAt(storage, m - 1);
                    if (prevLit == crLit && prevPos == pos - 1) {
                        fieldEnd = pos - 1;
                    }
                }
                currentFields.add(new CsvField(fieldStart, fieldEnd, fieldIsQuoted, quoteChar));
                rows.add(new CsvRow(data, currentFields.toArray(CsvField[]::new)));
                currentFields.clear();
                fieldStart = pos + 1;
                fieldIsQuoted = false;
            } else if (lit == crLit) {
                // CR: if next match is LF at pos+1, let LF handle it (CRLF)
                if (m + 1 < matchCount) {
                    long nextPos = view.getPositionAt(storage, m + 1);
                    byte nextLit = view.getLiteralAt(storage, m + 1);
                    if (nextLit == newlineLit && nextPos == pos + 1) {
                        continue; // CRLF: skip CR, LF will close the row
                    }
                }
                // Bare CR as line ending
                currentFields.add(new CsvField(fieldStart, pos, fieldIsQuoted, quoteChar));
                rows.add(new CsvRow(data, currentFields.toArray(CsvField[]::new)));
                currentFields.clear();
                fieldStart = pos + 1;
                fieldIsQuoted = false;
            }
        }

        // Handle final field (no trailing newline)
        if (fieldStart <= dataSize && (!currentFields.isEmpty() || fieldStart < dataSize)) {
            currentFields.add(new CsvField(fieldStart, dataSize, fieldIsQuoted, quoteChar));
            rows.add(new CsvRow(data, currentFields.toArray(CsvField[]::new)));
        }

        // Extract headers if configured
        String[] headers = null;
        if (hasHeader && !rows.isEmpty()) {
            var headerRow = rows.removeFirst();
            headers = new String[headerRow.fieldCount()];
            for (int c = 0; c < headerRow.fieldCount(); c++) {
                headers[c] = headerRow.get(c);
            }
        }

        return new CsvResult(rows.toArray(CsvRow[]::new), headers);
    }

    public static final class Builder {
        private int delimiter = ',';
        private int quote = '"';
        private boolean hasHeader = false;

        private Builder() {}

        public Builder delimiter(int delimiter) {
            this.delimiter = delimiter;
            return this;
        }

        public Builder quote(int quote) {
            this.quote = quote;
            return this;
        }

        public Builder hasHeader(boolean hasHeader) {
            this.hasHeader = hasHeader;
            return this;
        }

        public CsvParser build() {
            var result = Utf8EngineBuilder.builder()
                    .codepoints("quote", quote)
                    .codepoints("delim", delimiter)
                    .codepoints("lf", '\n')
                    .codepoints("cr", '\r')
                    .chunkFilter(CsvQuoteFilter.class, "quote", "delim", "lf", "cr")
                    .build();

            var engine = result.engine();
            var literals = result.literals();

            return new CsvParser(
                    engine,
                    literals.get("quote"),
                    literals.get("delim"),
                    literals.get("lf"),
                    literals.get("cr"),
                    (byte) quote,
                    hasHeader);
        }
    }
}
