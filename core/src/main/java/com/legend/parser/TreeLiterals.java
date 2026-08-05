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
public final class TreeLiterals {

    private TreeLiterals() {
    }

    /** Parse tree-literal SOURCE ({@code #{Class{...}}#}) to its
     * {@link ColSpecArray}, or {@code null} when the text is not a tree
     * literal the grammar carries (callers keep their own loud walls). */
    public static @com.legend.Nullable ValueSpecification parseTree(String source) {
        try {
            ValueSpecification v = SpecParser.parse(source.trim());
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
