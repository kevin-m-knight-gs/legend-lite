package com.legend.model;

import java.util.Objects;

/**
 * A parsed {@code Persistence} element &mdash; a service-fed persistence
 * pipeline. Top-level keys typed; the deep sub-DSLs (trigger body,
 * persister, output targets, notifier, tests) ride as raw source. Nothing
 * in legend-lite executes persistence; the record exists so
 * {@code ###Persistence} sections parse to typed, indexed elements.
 */
public record PersistenceDefinition(
        String qualifiedName,
        @com.legend.Nullable String doc,
        @com.legend.Nullable String triggerSource,
        @com.legend.Nullable String service,
        @com.legend.Nullable String persisterSource,
        @com.legend.Nullable String serviceOutputTargetsSource,
        @com.legend.Nullable String notifierSource,
        @com.legend.Nullable String testsSource) implements PackageableElement {

    public PersistenceDefinition {
        Objects.requireNonNull(qualifiedName, "Qualified name cannot be null");
    }
}
