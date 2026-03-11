package org.knownhosts.libfindchars.generator;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import jdk.incubator.vector.ByteVector;
import jdk.incubator.vector.VectorSpecies;

public record EngineConfiguration(VectorSpecies<Byte> species,
                                  ShuffleOperation shuffleOperation,
                                  List<RangeOperation> rangeOperations) {

    public EngineConfiguration {
        species = species != null ? species : ByteVector.SPECIES_PREFERRED;
        rangeOperations = rangeOperations != null ? List.copyOf(rangeOperations) : List.of();
        if (shuffleOperation == null && rangeOperations.isEmpty()) {
            throw new IllegalArgumentException("at least one operation is mandatory");
        }
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private VectorSpecies<Byte> species;
        private ShuffleOperation shuffleOperation;
        private final List<RangeOperation> rangeOperations = new ArrayList<>();

        private Builder() {}

        public Builder species(VectorSpecies<Byte> species) {
            this.species = species;
            return this;
        }

        public Builder shuffleOperation(ShuffleOperation shuffleOperation) {
            this.shuffleOperation = shuffleOperation;
            return this;
        }

        public Builder rangeOperations(RangeOperation... ops) {
            Collections.addAll(rangeOperations, ops);
            return this;
        }

        public EngineConfiguration build() {
            return new EngineConfiguration(species, shuffleOperation, rangeOperations);
        }
    }
}
