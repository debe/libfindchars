package org.knownhosts.libfindchars.engine;

import java.nio.file.Paths;

public class RangeTplOp implements TplOp {

	private final byte lowerBound;
	private final byte upperBound;
	private final byte literal;
	
	public byte getLowerBound() {
		return lowerBound;
	}

	public byte getUpperBound() {
		return upperBound;
	}

	public byte getLiteral() {
		return literal;
	}

	@Override
	public String getFieldsTpl() {
		return Paths.get("templates","rangeop","fields.vm").toString();
	}

	@Override
	public String getInitTpl() {
		
		return Paths.get("templates","rangeop","initialize.vm").toString();
	}

	@Override
	public String getInlineTpl() {
		return Paths.get("templates","rangeop","inline.vm").toString();
	}

	public RangeTplOp(byte lowerBound, byte upperBound, byte literal) {
		super();
		this.lowerBound = lowerBound;
		this.upperBound = upperBound;
		this.literal = literal;
	}
	
}
