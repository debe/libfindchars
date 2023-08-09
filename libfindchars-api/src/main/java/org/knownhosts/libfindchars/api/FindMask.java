package org.knownhosts.libfindchars.api;

import java.util.List;

public interface FindMask {

//	public byte getLiteral(String literal);
		
	public List<MultiByteLiteral> literals();
		
	public byte[] lowNibbleMask();
	
	public byte[] highNibbleMask();
	
	
}
