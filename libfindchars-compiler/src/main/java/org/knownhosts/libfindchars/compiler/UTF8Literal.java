package org.knownhosts.libfindchars.compiler;

import org.checkerframework.checker.units.qual.C;

import java.nio.CharBuffer;
import java.nio.charset.Charset;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

public record UTF8Literal(String name, char... chars) implements Literal {
	public UTF8Literal {
		for (int i = 0; i < chars.length; i++) {

		}
	}
	
	@Override
	public String getName() {
		return name;
	}

	@Override
	public char[] getChars() {
		return chars;
	}


	public byte[] decodeChar(int i){
		return String.copyValueOf(chars,i,1).getBytes(StandardCharsets.UTF_8);
	}

}
