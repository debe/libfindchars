package org.knownhosts.libfindchars.csv;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Direct unit tests for the CSV quote filter.
 * Tests are exercised through the full engine pipeline since the filter
 * is an {@code @Inline} static method that gets bytecode-inlined.
 */
class CsvQuoteFilterTest {

    @Test
    void quotedFieldsHideCommasFromParser() {
        var parser = CsvParser.builder().delimiter(',').quote('"').build();
        var result = parser.parse("a,\"b,c\",d\n".getBytes());
        assertEquals(1, result.rowCount());
        assertEquals(3, result.row(0).fieldCount());
        assertEquals("a", result.row(0).get(0));
        assertEquals("b,c", result.row(0).get(1));
        assertEquals("d", result.row(0).get(2));
    }

    @Test
    void quotedFieldsHideNewlinesFromParser() {
        var parser = CsvParser.builder().delimiter(',').quote('"').build();
        var result = parser.parse("a,\"line1\nline2\",b\n".getBytes());
        assertEquals(1, result.rowCount());
        assertEquals("line1\nline2", result.row(0).get(1));
    }

    @Test
    void noQuotesNoCarryFastPath() {
        var parser = CsvParser.builder().delimiter(',').quote('"').build();
        var result = parser.parse("a,b,c\nd,e,f\n".getBytes());
        assertEquals(2, result.rowCount());
        assertEquals("a", result.row(0).get(0));
        assertEquals("f", result.row(1).get(2));
    }

    @Test
    void crossChunkQuoteCarryHandled() {
        // Create a quoted field large enough to span multiple SIMD chunks (>64 bytes)
        var sb = new StringBuilder();
        sb.append("\"");
        sb.append("x".repeat(200)); // well beyond any vector size
        sb.append(",hidden"); // comma inside quotes
        sb.append("\"\n");
        var parser = CsvParser.builder().delimiter(',').quote('"').build();
        var result = parser.parse(sb.toString().getBytes());
        assertEquals(1, result.rowCount());
        assertEquals(1, result.row(0).fieldCount());
        assertTrue(result.row(0).get(0).contains(",hidden"));
    }

    @Test
    void consecutiveQuotedFields() {
        var parser = CsvParser.builder().delimiter(',').quote('"').build();
        var result = parser.parse("\"a,b\",\"c,d\",\"e,f\"\n".getBytes());
        assertEquals(1, result.rowCount());
        assertEquals(3, result.row(0).fieldCount());
        assertEquals("a,b", result.row(0).get(0));
        assertEquals("c,d", result.row(0).get(1));
        assertEquals("e,f", result.row(0).get(2));
    }

    @Test
    void emptyQuotedField() {
        var parser = CsvParser.builder().delimiter(',').quote('"').build();
        var result = parser.parse("\"\",a\n".getBytes());
        assertEquals(1, result.rowCount());
        assertEquals("", result.row(0).get(0));
        assertEquals("a", result.row(0).get(1));
    }
}
