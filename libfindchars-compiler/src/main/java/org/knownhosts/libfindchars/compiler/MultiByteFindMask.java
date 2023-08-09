package org.knownhosts.libfindchars.compiler;

import java.util.List;

import org.knownhosts.libfindchars.api.FindMask;
import org.knownhosts.libfindchars.api.MultiByteLiteral;

public record MultiByteFindMask(byte[] lowNibbleMask, byte[] highNibbleMask, List<MultiByteLiteral> literals) implements FindMask {
}
