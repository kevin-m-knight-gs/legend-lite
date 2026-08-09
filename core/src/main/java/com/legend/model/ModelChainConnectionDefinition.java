package com.legend.model;

import java.util.List;
import java.util.Objects;

/**
 * A standalone {@code ModelChainConnection name { mappings: [...]; }}
 * element from a {@code ###Connection} section &mdash; chains M2M mappings
 * so one mapping's output feeds the next.
 *
 * @param qualifiedName fully qualified connection name
 * @param mappings      qualified mapping names, in chain order
 */
public record ModelChainConnectionDefinition(
        String qualifiedName,
        List<String> mappings) implements PackageableElement {

    public ModelChainConnectionDefinition {
        Objects.requireNonNull(qualifiedName, "Qualified name cannot be null");
        mappings = List.copyOf(mappings);
    }
}
