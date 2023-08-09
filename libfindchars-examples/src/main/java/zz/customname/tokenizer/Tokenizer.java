package zz.customname.tokenizer;

import jdk.incubator.vector.*;
import org.knownhosts.libfindchars.api.MatchStorage;
import org.knownhosts.libfindchars.api.MatchView;
import zz.customname.FindCharsLiterals;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

public class Tokenizer {
    private static int INT_BATCH_SIZE = VectorShape.preferredShape().vectorBitSize() / Integer.SIZE;
    private static IntVector INDEX_VECTOR = IntVector.SPECIES_PREFERRED.fromArray(new int[]{1,2,3,4,5,6,7,8},0).reinterpretAsInts();
    private static final int LITERAL_SIZE = 1;
    private boolean stripbom;

    public Tokenizer(boolean stripBom){
        this.stripbom=stripBom;
    }

    public Tokenizer(){
        this(true);
    }
    public TokenView tokenize(MatchView matchView, MatchStorage matchStorage, TokenStorage tokenStorage, MemorySegment data){
        var lastPos = stripbom?
                data.get(ValueLayout.JAVA_INT,0) >> 4 == 0xefbbbf?
                        3 : 0
                : 0;
        int next = 0;

        for (int i = 0; i < matchView.size(); i++) {
            switch (matchView.getLiteralAt(matchStorage,i)){
                case FindCharsLiterals.WHITESPACES, FindCharsLiterals.PUNCTIATIONS  -> {
                    int nextPos = matchView.getPositionAt(matchStorage, i);
                    int size = nextPos - lastPos;
                    tokenStorage.getPositionsBuffer()[next]= lastPos;
                    tokenStorage.getSizeBuffer()[next] = size;
                    next+= size > 0 ? 1 : 0;
                    lastPos = nextPos + LITERAL_SIZE;
                }
            }
        }
        return new TokenView(next);
    }

    public TokenView tokenizeBranchy(MatchView matchView, MatchStorage matchStorage, TokenStorage tokenStorage, MemorySegment data){
        var lastPos = stripbom?
                data.get(ValueLayout.JAVA_INT,0) >> 4 == 0xefbbbf?
                        3 : 0
                : 0;
        int next = 0;

        for (int i = 0; i < matchView.size(); i++) {
            switch (matchView.getLiteralAt(matchStorage,i)){
                case FindCharsLiterals.WHITESPACES, FindCharsLiterals.PUNCTIATIONS -> {
                    int nextPos = matchView.getPositionAt(matchStorage, i);
                    int size = nextPos - lastPos;
                    if (size != 0) {
                        next++;
                        tokenStorage.getPositionsBuffer()[next] = lastPos;
                        tokenStorage.getSizeBuffer()[next] = size;
                    }
                    lastPos = nextPos + LITERAL_SIZE;
                }
            }
        }
        return new TokenView(next);
    }
}
