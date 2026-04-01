package org.knownhosts.libfindchars.csv;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;

/**
 * Zero-copy reference to a single CSV field within a {@link MemorySegment}.
 *
 * <p>No data is copied until {@link #value(MemorySegment)} is called.
 * For quoted fields, the value method strips outer quotes and unescapes {@code ""} → {@code "}.
 *
 * @param startOffset byte offset of the field start in the data segment
 * @param endOffset   byte offset past the last byte of the field
 * @param quoted      whether the field was enclosed in quotes
 */
public record CsvField(long startOffset, long endOffset, boolean quoted, byte quoteByte) {

    /**
     * Materialize the field value as a String from the underlying data.
     *
     * <p>For quoted fields, strips outer quotes and replaces doubled quotes ({@code ""})
     * with single quotes ({@code "}).
     */
    public String value(MemorySegment data) {
        if (startOffset >= endOffset) {
            return "";
        }
        if (!quoted) {
            int len = (int) (endOffset - startOffset);
            byte[] bytes = new byte[len];
            MemorySegment.copy(data, ValueLayout.JAVA_BYTE, startOffset, bytes, 0, len);
            return new String(bytes, StandardCharsets.UTF_8);
        }
        return unescapeQuoted(data);
    }

    /**
     * Return a zero-copy {@link MemorySegment} slice for the raw field bytes.
     */
    public MemorySegment rawSlice(MemorySegment data) {
        return data.asSlice(startOffset, endOffset - startOffset);
    }

    private String unescapeQuoted(MemorySegment data) {
        // Skip outer quotes
        long innerStart = startOffset + 1;
        long innerEnd = endOffset - 1;
        if (innerStart >= innerEnd) {
            return "";
        }
        int len = (int) (innerEnd - innerStart);
        byte[] bytes = new byte[len];
        MemorySegment.copy(data, ValueLayout.JAVA_BYTE, innerStart, bytes, 0, len);

        // Check if we need to unescape (fast path: no doubled quotes)
        boolean hasEscaped = false;
        for (int i = 0; i < len - 1; i++) {
            if (bytes[i] == quoteByte && bytes[i + 1] == quoteByte) {
                hasEscaped = true;
                break;
            }
        }
        if (!hasEscaped) {
            return new String(bytes, StandardCharsets.UTF_8);
        }

        // Slow path: unescape "" → "
        byte[] out = new byte[len];
        int w = 0;
        for (int i = 0; i < len; i++) {
            out[w++] = bytes[i];
            if (bytes[i] == quoteByte && i + 1 < len && bytes[i + 1] == quoteByte) {
                i++; // skip second quote
            }
        }
        return new String(out, 0, w, StandardCharsets.UTF_8);
    }
}
