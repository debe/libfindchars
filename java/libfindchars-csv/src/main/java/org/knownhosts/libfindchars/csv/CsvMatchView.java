package org.knownhosts.libfindchars.csv;

import org.knownhosts.libfindchars.api.MatchStorage;
import org.knownhosts.libfindchars.api.MatchView;

/**
 * Zero-allocation view over CSV structural tokens.
 *
 * <p>Row count is computed lazily on first access
 * by scanning the compact literal buffer.
 */
public final class CsvMatchView {

    private final MatchView view;
    private final MatchStorage storage;
    private final CsvToken[] literalToToken;
    private final byte newlineLit;
    private int rowCount = -1;

    CsvMatchView(MatchView view, MatchStorage storage, CsvToken[] literalToToken,
                 byte newlineLit) {
        this.view = view;
        this.storage = storage;
        this.literalToToken = literalToToken;
        this.newlineLit = newlineLit;
    }

    /** Number of structural tokens found. */
    public int size() {
        return view.size();
    }

    /** Byte position of token at index. */
    public long positionAt(int index) {
        return view.getPositionAt(storage, index);
    }

    /** Token type at index. */
    public CsvToken tokenAt(int index) {
        return literalToToken[view.getLiteralAt(storage, index) & 0xFF];
    }

    /** Raw literal byte at index (for advanced use). */
    public byte literalAt(int index) {
        return view.getLiteralAt(storage, index);
    }

    /** Row count (newlines outside quotes). Lazy — computed on first call. */
    public int rowCount() {
        if (rowCount == -1) {
            int rows = 0;
            byte[] litBuf = storage.getLiteralBuffer();
            int sz = view.size();
            for (int i = 0; i < sz; i++) {
                if (litBuf[i] == newlineLit) rows++;
            }
            this.rowCount = rows;
        }
        return rowCount;
    }
}
