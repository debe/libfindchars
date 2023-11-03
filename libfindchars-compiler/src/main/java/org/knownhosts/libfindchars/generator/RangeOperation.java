package org.knownhosts.libfindchars.generator;

public final class RangeOperation extends Operation {

    private byte from;
    private byte to;
    private final String name;

    public String getName() {
        return name;
    }

    public RangeOperation(String name) {
        this.name = name;
    }

    public RangeOperation withRange(int from, int to) {
        this.from = (byte) from;
        this.to = (byte) to;
        return this;
    }

    public byte getFrom() {
        return from;
    }

    public byte getTo() {
        return to;
    }
}
