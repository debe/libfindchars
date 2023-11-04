package org.knownhosts.libfindchars.bench;

import com.carrotsearch.hppc.IntArrayList;

import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.util.BitSet;
import java.util.regex.Pattern;

public class ScalarEngine {

    BitSet whitespaceBitset;

    Pattern pattern;
  
    public ScalarEngine(String chars){
        whitespaceBitset = new BitSet();
        for (int i = 0; i < chars.length(); i++)
            whitespaceBitset.set(chars.charAt(i));
    }

    public ScalarEngine(Pattern pattern){
        this.pattern = pattern;
    }


    public IntArrayList bitset(ByteBuffer byteBuffer) {
    	
        var res = new IntArrayList(byteBuffer.capacity() / 4);
        var charseq = Charset.defaultCharset().decode(byteBuffer);
        for (int i = 0; i < charseq.length(); i++) {
            int ch = charseq.get(i);
            if(whitespaceBitset.get(ch)){
                res.add(i);
            }
        }
        return res;
    }

    public IntArrayList regex(ByteBuffer byteBuffer) {
        var res = new IntArrayList(byteBuffer.capacity() / 4);
        var charseq = Charset.defaultCharset().decode(byteBuffer);
        var matcher = pattern.matcher(charseq);
        matcher.matches();
        while(matcher.find()){
            res.add(matcher.start());
        }
        return res;
    }

}
