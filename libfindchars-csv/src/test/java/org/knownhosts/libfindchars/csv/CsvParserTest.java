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
}
