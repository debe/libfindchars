package org.knownhosts.libfindchars.engine;

import jdk.incubator.vector.VectorShape;

public record FindingEngineConfiguration(VectorShape vectorShape) {
	
	  public static final class Builder {

		  VectorShape vectorShape;
		  
		  public Builder(VectorShape vectorShape) {
		      this.vectorShape = vectorShape;
		  }
	  
	  
		  public FindingEngineConfiguration build() {
			  return new FindingEngineConfiguration(vectorShape);
		  }
	  }
}
