package org.knownhosts.libfindchars.csv;

import org.junit.jupiter.api.Test;

import java.lang.foreign.MemorySegment;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class CsvParserTest {

    private final CsvParser parser = CsvParser.builder().build();
    private final CsvParser headerParser = CsvParser.builder().hasHeader(true).build();

    private CsvResult parse(String csv) {
        return parser.parse(csv.getBytes(StandardCharsets.UTF_8));
    }

    private CsvResult parseWithHeader(String csv) {
        return headerParser.parse(csv.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void simpleThreeRows() {
        var result = parse("a,b,c\n1,2,3\nx,y,z\n");
        assertEquals(3, result.rowCount());
        assertEquals("a", result.row(0).get(0));
        assertEquals("b", result.row(0).get(1));
        assertEquals("c", result.row(0).get(2));
        assertEquals("1", result.row(1).get(0));
        assertEquals("3", result.row(1).get(2));
        assertEquals("z", result.row(2).get(2));
    }

    @Test
    void quotedFields() {
        var result = parse("\"hello\",world\n");
        assertEquals(1, result.rowCount());
        assertEquals("hello", result.row(0).get(0));
        assertEquals("world", result.row(0).get(1));
    }

    @Test
    void quotedFieldWithComma() {
        var result = parse("\"a,b\",c\n");
        assertEquals(1, result.rowCount());
        assertEquals(2, result.row(0).fieldCount());
        assertEquals("a,b", result.row(0).get(0));
        assertEquals("c", result.row(0).get(1));
    }

    @Test
    void quotedFieldWithNewline() {
        var result = parse("\"line1\nline2\",b\n");
        assertEquals(1, result.rowCount());
        assertEquals("line1\nline2", result.row(0).get(0));
        assertEquals("b", result.row(0).get(1));
    }

    @Test
    void escapedQuotes() {
        var result = parse("\"he said \"\"hi\"\"\",b\n");
        assertEquals(1, result.rowCount());
        assertEquals("he said \"hi\"", result.row(0).get(0));
    }

    @Test
    void crlfLineEndings() {
        var result = parse("a,b\r\nc,d\r\n");
        assertEquals(2, result.rowCount());
        assertEquals("a", result.row(0).get(0));
        assertEquals("b", result.row(0).get(1));
        assertEquals("c", result.row(1).get(0));
    }

    @Test
    void headerParsing() {
        var result = parseWithHeader("name,age\nAlice,30\nBob,25\n");
        assertNotNull(result.headers());
        assertArrayEquals(new String[]{"name", "age"}, result.headers());
        assertEquals(2, result.rowCount());
        assertEquals("Alice", result.row(0).get(0));
        assertEquals("25", result.row(1).get(1));
    }

    @Test
    void emptyFields() {
        var result = parse(",,,\n");
        assertEquals(1, result.rowCount());
        assertEquals(4, result.row(0).fieldCount());
        assertEquals("", result.row(0).get(0));
        assertEquals("", result.row(0).get(1));
    }

    @Test
    void singleColumn() {
        var result = parse("hello\nworld\n");
        assertEquals(2, result.rowCount());
        assertEquals(1, result.row(0).fieldCount());
        assertEquals("hello", result.row(0).get(0));
        assertEquals("world", result.row(1).get(0));
    }

    @Test
    void noTrailingNewline() {
        var result = parse("a,b\nc,d");
        assertEquals(2, result.rowCount());
        assertEquals("d", result.row(1).get(1));
    }

    @Test
    void emptyInput() {
        var result = parser.parse(MemorySegment.ofArray(new byte[0]));
        assertEquals(0, result.rowCount());
    }

    @Test
    void emptyQuotedField() {
        var result = parse("\"\",b\n");
        assertEquals(1, result.rowCount());
        assertEquals("", result.row(0).get(0));
        assertEquals("b", result.row(0).get(1));
    }

    @Test
    void customDelimiter() {
        var tabParser = CsvParser.builder().delimiter('\t').build();
        var result = tabParser.parse("a\tb\tc\n".getBytes(StandardCharsets.UTF_8));
        assertEquals(1, result.rowCount());
        assertEquals(3, result.row(0).fieldCount());
        assertEquals("b", result.row(0).get(1));
    }

    @Test
    void multipleQuotedFieldsPerRow() {
        var result = parse("\"a,1\",\"b,2\",\"c,3\"\n");
        assertEquals(1, result.rowCount());
        assertEquals(3, result.row(0).fieldCount());
        assertEquals("a,1", result.row(0).get(0));
        assertEquals("b,2", result.row(0).get(1));
        assertEquals("c,3", result.row(0).get(2));
    }

    @Test
    void largeFieldSpanningMultipleVectors() {
        // Field larger than vector byte size (typically 32 or 64 bytes)
        String large = "x".repeat(200);
        var result = parse(large + ",short\n");
        assertEquals(1, result.rowCount());
        assertEquals(large, result.row(0).get(0));
        assertEquals("short", result.row(0).get(1));
    }

    @Test
    void manyColumns() {
        var sb = new StringBuilder();
        for (int i = 0; i < 100; i++) {
            if (i > 0) sb.append(',');
            sb.append("col").append(i);
        }
        sb.append('\n');
        var result = parse(sb.toString());
        assertEquals(1, result.rowCount());
        assertEquals(100, result.row(0).fieldCount());
        assertEquals("col0", result.row(0).get(0));
        assertEquals("col99", result.row(0).get(99));
    }

    @Test
    void customQuoteWithEscapedQuotes() {
        // Use single-quote as quote character — exercises the quoteByte path in CsvField
        var singleQuoteParser = CsvParser.builder().quote('\'').build();
        var result = singleQuoteParser.parse("'he said ''hi''',b\n".getBytes(StandardCharsets.UTF_8));
        assertEquals(1, result.rowCount());
        assertEquals("he said 'hi'", result.row(0).get(0));
        assertEquals("b", result.row(0).get(1));
    }

    @Test
    void bareCrLineEndings() {
        // Bare CR (no LF) as line ending — old Mac style
        var result = parse("a,b\rc,d\r");
        assertEquals(2, result.rowCount());
        assertEquals("a", result.row(0).get(0));
        assertEquals("b", result.row(0).get(1));
        assertEquals("c", result.row(1).get(0));
        assertEquals("d", result.row(1).get(1));
    }

    @Test
    void crossChunkQuoteCarry() {
        // Create data where a quoted region spans across a SIMD vector boundary.
        // A quoted field containing 200+ bytes forces the quote-open and quote-close
        // to land in different chunks, exercising CsvQuoteFilter's cross-chunk carry.
        String innerCommas = ",".repeat(200); // commas inside quotes — must be masked
        var result = parse("\"" + innerCommas + "\",b\n");
        assertEquals(1, result.rowCount());
        assertEquals(2, result.row(0).fieldCount());
        assertEquals(innerCommas, result.row(0).get(0));
        assertEquals("b", result.row(0).get(1));
    }

    @Test
    void newInstanceSharesEngine() {
        var original = parse("a,b\n1,2\n");
        var copy = parser.newInstance();
        var fromCopy = copy.parse("x,y,z\n".getBytes(StandardCharsets.UTF_8));
        // Both produce correct, independent results
        assertEquals(2, original.rowCount());
        assertEquals("a", original.row(0).get(0));
        assertEquals(1, fromCopy.rowCount());
        assertEquals("z", fromCopy.row(0).get(2));
    }

    @Test
    void storageReuseAcrossParses() {
        var first = parse("a,b\n");
        var second = parse("x,y,z\n1,2,3\n");
        // Second parse reuses grown storage — both results correct
        assertEquals(1, first.rowCount());
        assertEquals("a", first.row(0).get(0));
        assertEquals("b", first.row(0).get(1));
        assertEquals(2, second.rowCount());
        assertEquals("z", second.row(0).get(2));
        assertEquals("3", second.row(1).get(2));
    }

    @Test
    void rowsMatchesIndexedAccess() {
        var result = parse("a,b\nc,d\ne,f\n");
        var rows = result.rows();
        assertEquals(result.rowCount(), rows.length);
        for (int i = 0; i < rows.length; i++) {
            assertEquals(result.row(i).fieldCount(), rows[i].fieldCount());
            for (int c = 0; c < rows[i].fieldCount(); c++) {
                assertEquals(result.row(i).get(c), rows[i].get(c));
            }
        }
    }

    @Test
    void headerOnlyFile() {
        var result = parseWithHeader("name,age\n");
        assertArrayEquals(new String[]{"name", "age"}, result.headers());
        assertEquals(0, result.rowCount());
    }

    @Test
    void emptyFileWithHeader() {
        var result = headerParser.parse(MemorySegment.ofArray(new byte[0]));
        assertEquals(0, result.rowCount());
        assertNull(result.headers());
    }

    @Test
    void rowIndexOutOfBounds() {
        var result = parse("a,b\nc,d\n");
        assertThrows(IndexOutOfBoundsException.class, () -> result.row(-1));
        assertThrows(IndexOutOfBoundsException.class, () -> result.row(2));
    }

    @Test
    void columnIndexOutOfBounds() {
        var result = parse("a,b\n");
        var row = result.row(0);
        assertThrows(IndexOutOfBoundsException.class, () -> row.get(-1));
        assertThrows(IndexOutOfBoundsException.class, () -> row.get(2));
        assertThrows(IndexOutOfBoundsException.class, () -> row.field(-1));
        assertThrows(IndexOutOfBoundsException.class, () -> row.rawField(2));
    }

    @Test
    void streamMatchesRowCount() {
        var result = parse("a,b\nc,d\ne,f\n");
        assertEquals(result.rowCount(), result.stream().count());
        assertTrue(result.stream().findFirst().isPresent());
        assertEquals("a", result.stream().findFirst().get().get(0));
    }

    @Test
    void rawFieldReturnsCorrectSlice() {
        var result = parse("hello,world\n");
        var row = result.row(0);
        // Unquoted fields: rawField returns the exact byte range
        assertEquals("hello", new String(row.rawField(0).toArray(java.lang.foreign.ValueLayout.JAVA_BYTE), StandardCharsets.UTF_8));
        assertEquals("world", new String(row.rawField(1).toArray(java.lang.foreign.ValueLayout.JAVA_BYTE), StandardCharsets.UTF_8));
        assertEquals(5, row.rawField(0).byteSize());
        assertEquals(5, row.rawField(1).byteSize());

        // Quoted field: rawField returns the raw bytes including quotes
        var quoted = parse("\"a,b\",c\n");
        var qRow = quoted.row(0);
        var raw = qRow.rawField(0);
        byte[] rawBytes = raw.toArray(java.lang.foreign.ValueLayout.JAVA_BYTE);
        assertEquals('"', (char) rawBytes[0]);
        assertEquals('"', (char) rawBytes[rawBytes.length - 1]);
        // get() unescapes, rawField() does not
        assertEquals("a,b", qRow.get(0));
    }

    @Test
    void mixedLineEndings() {
        // LF, CRLF, and bare CR in the same file
        var result = parse("a\nb\r\nc\r");
        assertEquals(3, result.rowCount());
        assertEquals("a", result.row(0).get(0));
        assertEquals("b", result.row(1).get(0));
        assertEquals("c", result.row(2).get(0));
    }
}
