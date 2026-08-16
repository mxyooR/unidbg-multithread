package com.github.unidbg.thread;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Immutable caller-provided context for one native invocation. */
public final class InvocationContext {

    private final String operation;
    private final String origin;
    private final String contextKey;
    private final Map<String, String> attributes;

    private InvocationContext(Builder builder) {
        this.operation = builder.operation;
        this.origin = builder.origin;
        this.contextKey = builder.contextKey;
        this.attributes = Collections.unmodifiableMap(
                new LinkedHashMap<>(builder.attributes));
    }

    public static Builder builder() {
        return new Builder();
    }

    public String getOperation() {
        return operation;
    }

    public String getOrigin() {
        return origin;
    }

    public String getContextKey() {
        return contextKey;
    }

    public Map<String, String> getAttributes() {
        return attributes;
    }

    public static final class Builder {
        private String operation = "native-invocation";
        private String origin = "unknown";
        private String contextKey;
        private final Map<String, String> attributes = new LinkedHashMap<>();

        public Builder operation(String operation) {
            this.operation = requireText(operation, "operation");
            return this;
        }

        public Builder origin(String origin) {
            this.origin = requireText(origin, "origin");
            return this;
        }

        public Builder contextKey(String contextKey) {
            this.contextKey = contextKey;
            return this;
        }

        public Builder attribute(String name, String value) {
            attributes.put(requireText(name, "attribute name"), value);
            return this;
        }

        public InvocationContext build() {
            return new InvocationContext(this);
        }

        private static String requireText(String value, String name) {
            if (value == null || value.trim().isEmpty()) {
                throw new IllegalArgumentException(name + " must not be blank");
            }
            return value;
        }
    }
}
