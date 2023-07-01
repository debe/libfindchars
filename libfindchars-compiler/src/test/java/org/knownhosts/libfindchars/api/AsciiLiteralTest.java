package org.knownhosts.libfindchars.api;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.knownhosts.libfindchars.compiler.AsciiLiteral;

class AsciiLiteralTest {

	@Test
	void testUtf8LiteralNotAllowed() {
		Assertions.assertThrows(IllegalArgumentException.class, () -> {
			new AsciiLiteral("utf8 fail", "üöä".toCharArray());
		});
	}

	
	@Test
	void testAsciiLiteral() {
		Assertions.assertDoesNotThrow( () -> {
			new AsciiLiteral("ascii ok", "asdfgh!\"/)(&%$%)".toCharArray());
		});
	}
}
