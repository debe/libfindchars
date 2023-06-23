package org.knownhosts.libfindchars.api;

public record AsciiLiteral(String name, int index, char... chars) implements Literal {

	public AsciiLiteral {
		for (int i = 0; i < chars.length; i++) {
			if((int)chars[i] > 0x7f){
				throw new IllegalArgumentException("Only ascii characters allowed atm");
			}
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

}
