package org.knownhosts.libfindchars.api;

import java.util.Map;

public interface FindMask {

    byte literalOf(String literal);

    Map<String, Byte> literals();

    byte[] lowNibbleMask();

    byte[] highNibbleMask();


}
