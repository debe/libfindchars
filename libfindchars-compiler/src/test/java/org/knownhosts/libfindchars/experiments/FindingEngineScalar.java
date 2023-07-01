package org.knownhosts.libfindchars.experiments;

import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.util.BitSet;

import com.carrotsearch.hppc.IntArrayList;

public class FindingEngineScalar {

	
    BitSet whitespaceBitset;

  
    public FindingEngineScalar(String chars){
        whitespaceBitset = new BitSet();
        final String matchString = chars; //.toCharArray(); //"+;:\r\n\t\f&()!\\#$%&()*+:;<=>?@\\[\\]^_{|}~ ";
        for (int i = 0; i < matchString.length(); i++)
            whitespaceBitset.set(matchString.charAt(i));
    }


    public IntArrayList tokenize_bitset(byte[] text) {
    	
        var res = new IntArrayList(text.length / 7);
        var charseq = Charset.defaultCharset().decode(ByteBuffer.wrap(text));
        for (int i = 0; i < charseq.length(); i++) {
            int ch = charseq.get(i);
            if(whitespaceBitset.get(ch)){
                res.add(i);
            }
        }
        return res;
    }

}
