package org.knownhosts.libfindchars.api;

import java.util.Map;

public record AsciiFindMask(byte[] lowNibbleMask, byte[] highNibbleMask, Map<String, Byte> literals) implements FindMask {


	@Override
	public byte getLiteral(String literal) {
		return literals.get(literal);
	}

	
	public String toString() {
		StringBuffer sb = new StringBuffer();
		for (var entry : literals.entrySet()) {
			sb.append("literal: ");
			sb.append(entry.getKey());
			sb.append(" = ");
			sb.append(entry.getValue());
			sb.append("\n");
		}
		sb.append("low nibble Mask: [");
		 for (int i = 0; i < lowNibbleMask.length; i++) {
			 sb.append(lowNibbleMask[i]);
			 sb.append(" ");
		 } 
		sb.append("]\n");
		sb.append("high nibble Mask: [");
		 for (int i = 0; i < highNibbleMask.length; i++) {
			 sb.append(highNibbleMask[i]);
			 sb.append(" ");
		 } 
		sb.append("]\n");
		return sb.toString();	
	}



}
