package org.knownhosts.libfindchars.compiler;

import java.util.Map;

import org.knownhosts.libfindchars.api.FindMask;

public record AsciiFindMask(byte[] lowNibbleMask, byte[] highNibbleMask,
                            Map<String, Byte> literals) implements FindMask {


    @Override
    public byte getLiteral(String literal) {
        return literals.get(literal);
    }


    public String toString() {
        StringBuilder sb = new StringBuilder();
        for (var entry : literals.entrySet()) {
            sb.append("literal: ");
            sb.append(entry.getKey());
            sb.append(" = ");
            sb.append(entry.getValue());
            sb.append("\n");
        }
        sb.append("low nibble Mask: [");
        for (byte b : lowNibbleMask) {
            sb.append(b);
            sb.append(" ");
        }
        sb.append("]\n");
        sb.append("high nibble Mask: [");
        for (byte b : highNibbleMask) {
            sb.append(b);
            sb.append(" ");
        }
        sb.append("]\n");
        return sb.toString();
    }

}
