package com.legend.parser;

import com.legend.model.spec.ColSpecArray;
import com.legend.model.spec.ValueSpecification;

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
    public static ValueSpecification parseTree(String source) {
        try {
            ValueSpecification v = SpecParser.parse(source.trim());
            return v instanceof ColSpecArray ? v : null;
        } catch (RuntimeException notATree) {
            return null;
        }
    }
}
