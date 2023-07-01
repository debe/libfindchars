package org.knownhosts.libfindchars.generator;

import java.util.ArrayList;
import java.util.List;

public class EngineConfiguration {
	
	private List<RangeOperation> rangeOperations = new ArrayList<>();
	private ShuffleOperation shuffleOperation;
	
	private Target target;
	 
	public List<RangeOperation> getRangeOperations() {
		return rangeOperations;
	}


	public ShuffleOperation getShuffleOperation() {
		return shuffleOperation;
	}


	public EngineConfiguration withRangeOperations(RangeOperation... rangeOperations){
		if(rangeOperations != null) {
			for (RangeOperation op : rangeOperations) {
				this.rangeOperations.add(op);
			}
		}
	 	return this; 
	}
	 
	
	public EngineConfiguration withShuffleOperation(ShuffleOperation shuffleOperation) {
		this.shuffleOperation = shuffleOperation;
		return this;
	}
	
	public EngineConfiguration withTarget(Target target) {
		this.target = target;
		return this;
	}

	public Target getTarget() {
		return target;
	}
	 
	  
}
