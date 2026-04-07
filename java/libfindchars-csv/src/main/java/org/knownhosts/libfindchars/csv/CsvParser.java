package org.knownhosts.libfindchars.csv;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

import org.knownhosts.libfindchars.api.FindEngine;
import org.knownhosts.libfindchars.api.MatchStorage;
import org.knownhosts.libfindchars.api.MatchView;
import org.knownhosts.libfindchars.generator.CompilationMode;
import org.knownhosts.libfindchars.generator.Utf8EngineBuilder;

/**
 * SIMD-accelerated CSV parser using libfindchars VPA framework.
 *
 * <p>Two-phase architecture:
 * <ol>
 *   <li><b>SIMD scan + filter</b>: The engine detects {@code ,} {@code "} {@code \n} {@code \r}
 *       via SIMD, and the {@link CsvQuoteFilter} zeros out structural characters
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
    private static final MatchStorage EMPTY_STORAGE = new MatchStorage(0, 0);
    private MatchStorage storage;

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

    /** Internal constructor sharing the parent's immutable lookup table. */
    private CsvParser(FindEngine engine, byte quoteLit, byte delimLit,
                      byte newlineLit, byte crLit, byte quoteChar,
                      boolean hasHeader, CsvToken[] literalToToken) {
        this.engine = engine;
        this.quoteLit = quoteLit;
        this.delimLit = delimLit;
        this.newlineLit = newlineLit;
        this.crLit = crLit;
        this.quoteChar = quoteChar;
        this.hasHeader = hasHeader;
        this.literalToToken = literalToToken;
    }

    public static Builder builder() {
        return new Builder();
    }

    /**
     * Returns a new CsvParser sharing this instance's compiled engine but with
     * fresh (unallocated) storage. <b>Not thread-safe</b>: the original and
     * returned parser must not be used concurrently because the engine is not
     * thread-safe.
     *
     * <p>Useful for benchmarking single-parse cost without storage-reuse effects.
     */
    public CsvParser newInstance() {
        return new CsvParser(engine, quoteLit, delimLit, newlineLit, crLit, quoteChar, hasHeader, literalToToken);
    }

    /**
     * Zero-allocation scan: returns a {@link CsvMatchView} over the engine's
     * flat arrays. The consumer iterates token types and positions directly —
     * no CsvRow/CsvField objects are created.
     *
     * <p>The returned view shares this parser's internal storage. A subsequent
     * call to {@code scan()} or {@code parse()} on the same parser instance
     * invalidates any previously returned view. Consume the view before the
     * next call, or use {@link #newInstance()} for an independent parser.
     */
    public CsvMatchView scan(MemorySegment data) {
        long dataSize = data.byteSize();
        if (dataSize == 0) {
            return new CsvMatchView(new MatchView(0),
                    EMPTY_STORAGE, literalToToken, newlineLit);
        }
        var matchStorage = getOrCreateStorage(dataSize);
        var view = engine.find(data, matchStorage);
        return new CsvMatchView(view, matchStorage, literalToToken, newlineLit);
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
     *
     * <p>Unlike {@link #scan}, the returned result is independent of this
     * parser's internal storage and remains valid across subsequent calls.
     */
    public CsvResult parse(MemorySegment data) {
        long dataSize = data.byteSize();
        if (dataSize == 0) {
            return CsvResult.empty();
        }

        var matchStorage = getOrCreateStorage(dataSize);
        var view = engine.find(data, matchStorage);
        return walkMatches(data, view, matchStorage, dataSize);
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

    /**
     * Returns the lazily-initialized {@link MatchStorage} for this parser.
     * The initial capacity is a 25% estimate of {@code dataSize}; the engine
     * grows the buffers incrementally if actual match count exceeds this.
     * Subsequent calls reuse the same (possibly already-grown) storage,
     * so the estimate only matters for the very first parse.
     */
    private MatchStorage getOrCreateStorage(long dataSize) {
        if (storage == null) {
            storage = new MatchStorage(
                    (int) Math.min(dataSize / 4, Integer.MAX_VALUE - 64), 64);
        }
        return storage;
    }

    private CsvResult walkMatches(MemorySegment data, MatchView view,
                                   MatchStorage storage, long dataSize) {
        int matchCount = view.size();
        byte[] litBuf = storage.getLiteralBuffer();
        long[] posBuf = storage.getPositionsBuffer();

        // Count row terminators to pre-size rowFieldOffset.  For CRLF data both
        // the CR and LF are counted, so rowCapacity may be up to 2x the actual row
        // count — a harmless over-allocation that avoids a look-ahead in this pre-scan.
        int rowCapacity = 1;
        for (int i = 0; i < matchCount; i++) {
            if (litBuf[i] == newlineLit || litBuf[i] == crLit) rowCapacity++;
        }

        // Upper bound: every match could be a delimiter or newline (quote matches
        // inflate matchCount but never produce fields).  +1 for the trailing field.
        int fieldCapacity = matchCount + 1;
        long[] fieldStarts = new long[fieldCapacity];
        long[] fieldEnds = new long[fieldCapacity];
        byte[] fieldFlags = new byte[fieldCapacity];
        int[] rowFieldOffset = new int[rowCapacity + 1];

        int fieldCount = 0;
        int rowCount = 0;
        long fieldStart = 0;
        boolean fieldIsQuoted = false;

        for (int m = 0; m < matchCount; m++) {
            long pos = posBuf[m];
            byte lit = litBuf[m];

            if (lit == quoteLit) {
                if (pos == fieldStart) {
                    fieldIsQuoted = true;
                }
            } else if (lit == delimLit) {
                fieldStarts[fieldCount] = fieldStart;
                fieldEnds[fieldCount] = pos;
                fieldFlags[fieldCount] = fieldIsQuoted ? (byte) 1 : 0;
                fieldCount++;
                fieldStart = pos + 1;
                fieldIsQuoted = false;
            } else if (lit == newlineLit) {
                long fieldEnd = pos;
                if (m > 0 && litBuf[m - 1] == crLit && posBuf[m - 1] == pos - 1) {
                    fieldEnd = pos - 1;
                }
                fieldStarts[fieldCount] = fieldStart;
                fieldEnds[fieldCount] = fieldEnd;
                fieldFlags[fieldCount] = fieldIsQuoted ? (byte) 1 : 0;
                fieldCount++;
                rowFieldOffset[rowCount + 1] = fieldCount;
                rowCount++;
                fieldStart = pos + 1;
                fieldIsQuoted = false;
            } else if (lit == crLit) {
                if (m + 1 < matchCount && litBuf[m + 1] == newlineLit
                        && posBuf[m + 1] == pos + 1) {
                    continue; // CRLF: skip CR, LF will close the row
                }
                fieldStarts[fieldCount] = fieldStart;
                fieldEnds[fieldCount] = pos;
                fieldFlags[fieldCount] = fieldIsQuoted ? (byte) 1 : 0;
                fieldCount++;
                rowFieldOffset[rowCount + 1] = fieldCount;
                rowCount++;
                fieldStart = pos + 1;
                fieldIsQuoted = false;
            }
        }

        // Handle final field (no trailing newline)
        if (fieldStart <= dataSize
                && (fieldCount > rowFieldOffset[rowCount] || fieldStart < dataSize)) {
            fieldStarts[fieldCount] = fieldStart;
            fieldEnds[fieldCount] = dataSize;
            fieldFlags[fieldCount] = fieldIsQuoted ? (byte) 1 : 0;
            fieldCount++;
            rowFieldOffset[rowCount + 1] = fieldCount;
            rowCount++;
        }

        // Extract headers if configured
        String[] headers = null;
        int dataRowStart = 0;
        if (hasHeader && rowCount > 0) {
            int hStart = rowFieldOffset[0];
            int hEnd = rowFieldOffset[1];
            headers = new String[hEnd - hStart];
            for (int c = 0; c < headers.length; c++) {
                int fi = hStart + c;
                headers[c] = new CsvField(fieldStarts[fi], fieldEnds[fi],
                        fieldFlags[fi] != 0, quoteChar).value(data);
            }
            dataRowStart = 1;
        }

        return new CsvResult(data, fieldStarts, fieldEnds, fieldFlags,
                rowFieldOffset, rowCount, dataRowStart, headers, quoteChar);
    }

    public static final class Builder {
        private int delimiter = ',';
        private int quote = '"';
        private boolean hasHeader = false;
        private CompilationMode compilationMode;

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

        /**
         * Set the compilation mode for the underlying engine.
         *
         * @see CompilationMode
         */
        public Builder compilationMode(CompilationMode compilationMode) {
            this.compilationMode = compilationMode;
            return this;
        }

        public CsvParser build() {
            var engineBuilder = Utf8EngineBuilder.builder()
                    .codepoints("quote", quote)
                    .codepoints("delim", delimiter)
                    .codepoints("lf", '\n')
                    .codepoints("cr", '\r');
            if (compilationMode == CompilationMode.AOT) {
                engineBuilder.chunkFilter(CsvQuoteFilter.INSTANCE, "quote");
            } else {
                engineBuilder.chunkFilter(CsvQuoteFilter.class, "quote");
            }
            if (compilationMode != null) engineBuilder.compilationMode(compilationMode);
            var result = engineBuilder.build();

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
