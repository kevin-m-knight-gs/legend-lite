package com.legend.model;

import java.util.Map;
import java.util.Objects;

/** A parsed function activator &mdash; {@code kind} names which ({@code SnowflakeApp}, {@code SnowflakeM2MUdf}); its
 *  censused keys carried as a flat field map ({@code function} keeps its full pointer signature as written). Named
 *  {@code SnowflakeActivatorDefinition} until 2026-10-16 (the dialect program's Phase 3): nothing in it is one
 *  activator's. */
public record FunctionActivatorDefinition(
        String qualifiedName,
        String kind,
        Map<String, String> fields) implements PackageableElement {

    public FunctionActivatorDefinition {
        Objects.requireNonNull(qualifiedName, "Qualified name cannot be null");
        Objects.requireNonNull(kind, "Kind cannot be null");
        fields = java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(fields));
    }
}
