package org.knownhosts.libfindchars.api;

import java.util.Arrays;

public record MultiByteLiteral(String name, byte multibyteLiteral, MultiByteCharacter[] characters ) {

    public MultiByteCharacter[] filterBySize(int size){
        return Arrays.stream(characters())
                .filter(mbc -> mbc.literals().length == size)
                .toArray(MultiByteCharacter[]::new);
    }

    public MultiByteCharacter[] onlyAscii(){
        return filterBySize(1);
    }

    public MultiByteCharacter[] onlyTwoBytes(){
        return filterBySize(2);
    }

    public MultiByteCharacter[] onlyThreeBytes(){
        return filterBySize(3);
    }

    public MultiByteCharacter[] onlyFourBytes(){
        return filterBySize(4);
    }
}
