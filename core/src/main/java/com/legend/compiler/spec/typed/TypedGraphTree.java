package com.legend.compiler.spec.typed;

import java.util.List;
import java.util.Objects;

/**
 * One node of a checked graph-fetch tree {@code #{Class{a, b{c}}}#}: a property
 * (validated against its owner class) and its sub-tree. A component of
 * {@link TypedGraphFetch} / {@link TypedSerialize} &mdash; not a {@link TypedSpec}.
 *
 * @param property the property name at this level
 * @param children the nested sub-tree, empty for a leaf
 */
public record TypedGraphTree(String property, List<TypedGraphTree> children,
        String alias, List<TypedSpec> args) {
    public TypedGraphTree {
        Objects.requireNonNull(property, "property");
        children = List.copyOf(children);
        args = args == null ? List.of() : List.copyOf(args);
    }

    /** Aliased node without call args. */
    public TypedGraphTree(String property, List<TypedGraphTree> children,
            String alias) {
        this(property, children, alias, List.of());
    }

    /** Un-aliased node (the common spelling). */
    public TypedGraphTree(String property, List<TypedGraphTree> children) {
        this(property, children, null, List.of());
    }
}
