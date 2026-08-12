package com.legend.parser;

import com.legend.protocol.spec.ColSpecArray;
import com.legend.protocol.spec.ValueSpecification;

/**
 * The QUOTE/EVAL boundary for graph-tree literals carried as STRINGS:
 * {@code meta::legend::compileLegendValueSpecification('#{...}#')} reifies
 * the parser in pure, and this is its one platform entry &mdash; a parser-package
 * FRONT DOOR (like {@link ElementParser} for elements), so grammar internals
 * never leak into checker code.
 */
public final class EngineSpecParser {

    private EngineSpecParser() {
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
            fold(com.legend.protocol.spec.AppliedFunction call) {
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
        ValueSpecification tree = parseTree(src, Dialect.LEGEND_ENGINE);
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
        } catch (RuntimeException notATree) {
            return null;
        }
    }
}
