package org.knownhosts.libfindchars.api;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class AsciiLiteralTest {

	@Test
	void testUtf8LiteralNotAllowed() {
		Assertions.assertThrows(IllegalArgumentException.class, () -> {
			new AsciiLiteral("utf8 fail", 0, "üöä".toCharArray());
		});
	}

	
	@Test
	void testAsciiLiteral() {
		Assertions.assertDoesNotThrow( () -> {
			new AsciiLiteral("ascii ok", 0, "asdfgh!\"/)(&%$%)".toCharArray());
		});
	}
}
