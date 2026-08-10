package com.legend.model;

import java.util.List;
import java.util.Objects;

/**
 * A parsed {@code ExecutionEnvironment name { executions: [ KEY: { mapping;
 * runtime; } ]; }} element from a {@code ###Service} section &mdash; a named
 * set of keyed mapping/runtime environments a multi-execution service can
 * reference.
 *
 * @param qualifiedName fully qualified name
 * @param executions    keyed environments, in declaration order
 */
public record ExecutionEnvironmentDefinition(
        String qualifiedName,
        List<ServiceDefinition.KeyedExecution> executions)
        implements PackageableElement {

    public ExecutionEnvironmentDefinition {
        Objects.requireNonNull(qualifiedName, "Qualified name cannot be null");
        executions = List.copyOf(executions);
    }
}
