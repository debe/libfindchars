package org.knownhosts.libfindchars.compiler;

import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public record AsciiFindMask(byte[] lowNibbleMask, byte[] highNibbleMask,
                            Map<String, Byte> literals) {

    public byte literalOf(String literal) {
        return literals.get(literal);
    }

    /**
     * Exhaustive 256-value check of this mask against the group it was solved for.
     *
     * <p>Every target byte must AND to its assigned literal, and no non-target byte
     * may collide with any literal once masked to {@code [0, vectorByteSize)}.
     * Non-target results are <em>not</em> required to be zero — a secondary clean
     * LUT maps non-literal values to zero at runtime — so what is checked here is
     * non-collision, not zero output (SOLVE-001, ENGINE-004).
     *
     * <p>Z3 answering "sat" only means the constraint system we built is
     * satisfiable, not that we built the right one. This check closes that gap for
     * 256 iterations of work at engine-construction time.
     *
     * @param group          the literals this mask was solved for
     * @param vectorByteSize SIMD vector width in bytes, or 0 for unconstrained
     * @return empty if the mask is valid, otherwise the first violation found
     */
    public Optional<String> verify(AsciiLiteralGroup group, int vectorByteSize) {
        int resultMask = vectorByteSize > 0 ? vectorByteSize - 1 : 0xFF;

        Set<Integer> literalValues = new HashSet<>();
        for (byte lit : literals.values()) {
            literalValues.add(lit & 0xFF);
        }

        Set<Integer> targets = new HashSet<>();
        for (ByteLiteral literal : group.literals()) {
            Byte assigned = literals.get(literal.name());
            if (assigned == null) {
                return Optional.of("literal '" + literal.name() + "' has no assigned value");
            }
            for (char c : literal.chars()) {
                // The constraint builder indexes nibbles from the low byte, so a
                // char above 0xFF would have been solved for silently truncated.
                if (c > 0xFF) {
                    return Optional.of(String.format(
                            "literal '%s' has target U+%04X above the byte range",
                            literal.name(), (int) c));
                }
                int target = c & 0xFF;
                targets.add(target);
                int result = andAt(target);
                if (result != (assigned & 0xFF)) {
                    return Optional.of(String.format(
                            "target byte 0x%02x yielded 0x%02x, expected literal 0x%02x",
                            target, result, assigned & 0xFF));
                }
            }
        }

        for (int b = 0; b < 256; b++) {
            if (targets.contains(b)) {
                continue;
            }
            int result = andAt(b) & resultMask;
            if (literalValues.contains(result)) {
                return Optional.of(String.format(
                        "non-target byte 0x%02x collides with literal 0x%02x", b, result));
            }
        }
        return Optional.empty();
    }

    /** The nibble-matrix AND for a single byte. */
    private int andAt(int b) {
        return (lowNibbleMask[b & 0x0F] & highNibbleMask[(b >> 4) & 0x0F]) & 0xFF;
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
