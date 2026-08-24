package org.knownhosts.libfindchars.generator;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * UTF8-002 / UTF8-013: the classification contract, over all 256 byte values.
 *
 * <p>Twin of the Rust {@code utf8::tests::utf8_002_classifies_all_256_bytes}.
 * UTF8-002 AC1 has always required this and neither implementation had it.
 */
class Utf8ClassifyTableTest {

    private static final byte ASCII = 1;
    private static final byte CONTINUATION = 0;
    private static final byte LEAD2 = 2;
    private static final byte LEAD3 = 3;
    private static final byte LEAD4 = 4;

    /** Independent range-based oracle, so a wrong table entry cannot agree with itself. */
    private static byte reference(int b) {
        if (b <= 0x7F) return ASCII;
        if (b <= 0xBF) return CONTINUATION;
        if (b <= 0xDF) return LEAD2;
        if (b <= 0xEF) return LEAD3;
        return LEAD4;
    }

    @Test
    void classifiesAll256Bytes() {
        Assertions.assertEquals(16, Utf8EngineBuilder.CLASSIFY_TABLE.length,
                "classification table must have exactly 16 entries");

        for (int b = 0; b <= 0xFF; b++) {
            byte actual = Utf8EngineBuilder.CLASSIFY_TABLE[(b >> 4) & 0x0F];
            Assertions.assertEquals(reference(b), actual,
                    String.format("byte 0x%02x misclassified", b));
        }
    }

    /**
     * UTF8-013: classification deliberately admits bytes that cannot begin a
     * well-formed sequence. Pinned so that turning the engine into a validator
     * becomes a deliberate change rather than a silent one.
     */
    @Test
    void classificationIsNotValidation() {
        byte[] t = Utf8EngineBuilder.CLASSIFY_TABLE;
        Assertions.assertEquals(LEAD2, t[0xC0 >> 4], "overlong lead 0xC0");
        Assertions.assertEquals(LEAD2, t[0xC1 >> 4], "overlong lead 0xC1");
        Assertions.assertEquals(LEAD3, t[0xED >> 4], "surrogate lead 0xED");
        for (int b : new int[] {0xF5, 0xF7, 0xF8, 0xFE, 0xFF}) {
            Assertions.assertEquals(LEAD4, t[(b >> 4) & 0x0F],
                    String.format("byte 0x%02x", b));
        }
    }
}
