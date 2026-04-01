package org.knownhosts.libfindchars.compiler.inline;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Configuration for template specialization. Declares which {@code @Inline int} fields
 * should be constant-folded during bytecode transformation.
 */
public final class SpecializationConfig {

    private final Map<String, Integer> constants;

    private SpecializationConfig(Map<String, Integer> constants) {
        this.constants = Collections.unmodifiableMap(constants);
    }

    public static Builder builder() {
        return new Builder();
    }

    public Map<String, Integer> constants() { return constants; }

    public static final class Builder {
        private final Map<String, Integer> constants = new LinkedHashMap<>();

        private Builder() {}

        /** Fold an {@code @Inline int} field to a compile-time constant. */
        public Builder constant(String fieldName, int value) {
            constants.put(fieldName, value);
            return this;
        }

        public SpecializationConfig build() {
            return new SpecializationConfig(constants);
        }
    }
}
