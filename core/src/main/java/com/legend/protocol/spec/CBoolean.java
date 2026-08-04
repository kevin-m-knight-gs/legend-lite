package com.legend.protocol.spec;

/** Boolean literal. Example Pure source: {@code true} or {@code false}. */
public record CBoolean(boolean value) implements ValueSpecification {
}
