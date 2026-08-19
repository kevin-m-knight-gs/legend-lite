package com.legend.compiler.spec.typed;

import com.legend.compiler.element.TypedFunction;
import com.legend.compiler.element.type.ExprType;

import java.util.List;

/**
 * A type-checked call to a native (built-in) function &mdash; the generic node
 * for library natives on the signature-driven path (G-&eta;: core structural
 * constructs get their own distinct nodes instead). The resolved {@code callee}
 * (the chosen overload) rides <strong>on the node</strong>, never a name string
 * (§5/§6) &mdash; lowering dispatches on the callee's identity, symmetric with
 * {@link TypedUserCall}.
 *
 * @param callee the resolved native overload this call dispatches to
 * @param args   the type-checked argument expressions, in source order
 * @param info   the resolved result type
 * @param pos    the call's source span (the parser's name-token position —
 *               real pure's error SourceInformation convention), threaded
 *               from the protocol node at the Typer's generic-application
 *               site; null on synthesized calls. Database-raised guards
 *               embed it as the error's source-info channel
 *               (assertError's line/column matcher reads it back).
 */
public record TypedNativeCall(TypedFunction callee, List<TypedSpec> args, ExprType info,
        com.legend.protocol.@com.legend.Nullable SourceInfo pos) implements TypedSpec {
    public TypedNativeCall {
        args = List.copyOf(args);
    }

    /** Position-free form — synthesis, rewrites whose protocol origin has no span. */
    public TypedNativeCall(TypedFunction callee, List<TypedSpec> args, ExprType info) {
        this(callee, args, info, null);
    }

    /** The native's simple name (e.g. {@code length}) &mdash; display convenience, not a dispatch key. */
    public String function() {
        return callee.qualifiedName();
    }

    @Override
    public List<TypedSpec> children() {
        return args;
    }

    @Override
    public TypedSpec withChildren(java.util.List<TypedSpec> kids) {
        return new TypedNativeCall(callee, kids, info, pos);
    }

    /** SEMANTIC equality ignores {@code pos} — the span is provenance
     * metadata, never identity. Structurally identical expressions from
     * different source sites must stay equal: expression dedup (the
     * IN-filter SQL compression) and rewriter no-change checks key on
     * node equality (two referee regressions pinned this the day the
     * component landed). */
    @Override
    public boolean equals(Object o) {
        return o instanceof TypedNativeCall other
                && callee.equals(other.callee)
                && args.equals(other.args)
                && info.equals(other.info);
    }

    @Override
    public int hashCode() {
        return java.util.Objects.hash(callee, args, info);
    }
}
