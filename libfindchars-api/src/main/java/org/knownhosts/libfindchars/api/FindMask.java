package org.knownhosts.libfindchars.api;

import java.util.Map;

public interface FindMask {

	public byte getLiteral(String literal);
		
	public Map<String,Byte> literals();
		
	public byte[] lowNibbleMask();
	
	public byte[] highNibbleMask();
	
	
}
