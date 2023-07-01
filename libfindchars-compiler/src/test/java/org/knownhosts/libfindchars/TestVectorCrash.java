package org.knownhosts.libfindchars;

import java.net.URISyntaxException;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import jdk.incubator.vector.ByteVector;
import jdk.incubator.vector.Vector;
import jdk.incubator.vector.VectorOperators;

@Disabled
public class TestVectorCrash {
	
    int lit_len;
    
    @BeforeEach
    void setup() {
    	lit_len=5;
    }
    
    
	class TestOP {
		public Vector<Byte> find(){
			
			var reduced = ByteVector.SPECIES_PREFERRED.broadcast(0L);
			int rounds = 1_000;

		for (int i = 0; i < rounds; i++) {
	        var buf = ByteVector.SPECIES_PREFERRED.broadcast(0L);

	        for (int j = 0; j < lit_len; j++) {
        		var vOnlyLiteral = ByteVector.SPECIES_PREFERRED.broadcast(-64L);
        		reduced = reduced.add(buf, vOnlyLiteral.compare(VectorOperators.EQ, 0));
			}
	        
		}
		return reduced;
		}
	}
	
	@Test
	public void crashes() throws URISyntaxException, Exception {
				
		var op = new TestOP();
		for (int i = 0; i < 2_000; i++) {
			System.out.println(i);
			var reduced = op.find();
			Assertions.assertNotNull(reduced);
		}
		
	}

}
