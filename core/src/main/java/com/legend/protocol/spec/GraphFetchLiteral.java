package com.legend.protocol.spec;

import java.util.List;
import java.util.Objects;

/**
 * A graph-fetch literal {@code #{Root {a, k {b}}}#} — like {@link PathLiteral}, the parse
 * product keeps BOTH representations: the wire tree (class name + property nodes with their
 * REAL token spans — graph-fetch spans are absolute, not island-shifted) for
 * {@code ProtocolEmitter}, and the desugared {@link ColSpecArray} legend-lite's compiler
 * consumes. {@code NameResolver} dissolves the node into {@link #desugared()} on first
 * touch.
 *
 * <p>Wire shape (ProbeWireShapes "typed new and gft", "alias dated tref2 gft2" d):
 * {@code classInstance} of type {@code rootGraphFetchTree}; the OUTER span and the value's
 * span are both the CLASS-NAME token span; each property node spans its name token.
 * Aliases, parameters, and subtype trees are carried as an unsupported flag and wall.
 */
public record GraphFetchLiteral(
        String className,
        List<Node> subTrees,
        ValueSpecification desugared,
        boolean unsupported,
        @com.legend.Nullable com.legend.protocol.SourceInfo pos) implements ValueSpecification {

    public GraphFetchLiteral {
        Objects.requireNonNull(className, "className");
        Objects.requireNonNull(subTrees, "subTrees");
        Objects.requireNonNull(desugared, "desugared");
        subTrees = List.copyOf(subTrees);
    }

    /**
     * One property node: name, its token span, call arguments, optional alias
     * ({@code 'nick' : prop}), optional subtype view ({@code prop->subType(@X)}), nested
     * subtrees. Arguments are protocol value specs whose spans the island scan bakes in
     * (var = name only, no {@code $}; string/date/enum = full literal).
     */
    public record Node(String property,
                       @com.legend.Nullable com.legend.protocol.SourceInfo pos,
                       List<ValueSpecification> parameters,
                       @com.legend.Nullable String alias,
                       @com.legend.Nullable String subType,
                       List<Node> subTrees) {
        public Node {
            parameters = List.copyOf(parameters);
            subTrees = List.copyOf(subTrees);
        }
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof GraphFetchLiteral other
                && className.equals(other.className())
                && desugared.equals(other.desugared())
                && unsupported == other.unsupported();
    }

    @Override
    public int hashCode() {
        return Objects.hash(className, desugared, unsupported);
    }
}
