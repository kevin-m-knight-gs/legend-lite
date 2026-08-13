package com.legend.parser;

import com.legend.protocol.spec.ColSpecArray;
import com.legend.protocol.spec.ValueSpecification;

/**
 * Parses QUOTED CODE — the string inside
 * {@code compileLegendValueSpecification('#{...}#')} — into a spec tree
 * at the {@code payloadDialect} THE CALLER states. Pure mechanism: this
 * class holds no dialect and knows no regime. (Why callers differ is
 * their business, documented at their call sites: SpecParser passes the
 * host parse's own level, the corpus inliners pass LEGEND_ENGINE.)
 */
public final class QuotedSpecParser {

    private QuotedSpecParser() {
    }

    /** The quote/eval fold, AT PARSE TIME: when {@code call} is
     * {@code compileLegendValueSpecification('<literal>')} (either
     * spelling, argument a string literal or a literal {@code +} chain)
     * and the string parses as a tree literal, wrap it as the two-faced
     * {@link com.legend.protocol.spec.QuotedTreeCall} — wire face the
     * original call, pipeline face the parsed tree. The parse level is
     * LEGEND_ENGINE: the engine's own native routes the string through
     * {@code PureGrammarParser} (LegendCompile.java:57). Returns
     * {@code null} when the call is not foldable — it then stays a plain
     * {@code AppliedFunction}, typed {@code Any[1]} like the engine's
     * native. */
    public static com.legend.protocol.spec.@com.legend.Nullable QuotedTreeCall
            fold(com.legend.protocol.spec.AppliedFunction call,
                    Dialect payloadDialect) {
        if (!(call.function().equals("compileLegendValueSpecification")
                || call.function().equals(
                        "meta::legend::compileLegendValueSpecification"))
                || call.parameters().size() != 1) {
            return null;
        }
        String src = foldStringConcat(call.parameters().get(0));
        if (src == null) {
            return null;
        }
        ValueSpecification tree = parseTree(src, payloadDialect);
        return tree == null ? null
                : new com.legend.protocol.spec.QuotedTreeCall(call, tree,
                        call.pos());
    }

    /** Fold a literal string-concatenation chain ('a' + 'b' + ...) to its
     * value, or null when any operand is not a literal string. */
    private static @com.legend.Nullable String foldStringConcat(ValueSpecification v) {
        if (v instanceof com.legend.protocol.spec.CString cs) {
            return cs.value();
        }
        if (v instanceof com.legend.protocol.spec.AppliedFunction pf
                && (pf.function().equals("plus")
                        || pf.function().equals("meta::pure::functions::math::plus"))) {
            StringBuilder sb = new StringBuilder();
            for (ValueSpecification p : pf.parameters()) {
                if (p instanceof com.legend.protocol.spec.PureCollection pc) {
                    for (ValueSpecification e : pc.values()) {
                        String part = foldStringConcat(e);
                        if (part == null) {
                            return null;
                        }
                        sb.append(part);
                    }
                    continue;
                }
                String part = foldStringConcat(p);
                if (part == null) {
                    return null;
                }
                sb.append(part);
            }
            return sb.toString();
        }
        return null;
    }

    /** Parse tree-literal SOURCE ({@code #{Class{...}}#}) to its
     * {@link ColSpecArray}, or {@code null} when the text is not a tree
     * literal the grammar carries (callers keep their own loud walls). */
    public static @com.legend.Nullable ValueSpecification parseTree(String source,
            Dialect dialect) {
        try {
            ValueSpecification v = SpecParser.parse(source.trim(), dialect);
            // the parse product became the wire-facing CARRIER when GraphFetchLiteral
            // landed — dissolve to the desugared tree, restoring this method's
            // ColSpecArray contract (regression: every string-built
            // compileLegendValueSpecification tree returned null and its test died
            // with the checker's arity message)
            if (v instanceof com.legend.protocol.spec.GraphFetchLiteral gf) {
                v = gf.desugared();
            }
            return v instanceof ColSpecArray ? v : null;
        } catch (ParseException notATree) {
            // ONLY a parse refusal means "not a tree literal" — any other
            // RuntimeException here is a parser defect and must surface, not
            // silently downgrade the call to an untyped fold (adversarial
            // audit F33: the broad catch masked exactly that regression once)
            return null;
        }
    }
}
