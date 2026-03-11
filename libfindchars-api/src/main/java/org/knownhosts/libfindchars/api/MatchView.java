package org.knownhosts.libfindchars.api;

public final class MatchView {

    private final int size;

    public MatchView(int size) {
        this.size = size;
    }

    public int size() {
        return size;
    }

    public int getPositionAt(MatchStorage autoTape, int index) {
        return autoTape.getPositionsBuffer()[index];
    }


    public byte getLiteralAt(MatchStorage autoTape, int index) {
        return autoTape.getLiteralBuffer()[index];
    }

}
