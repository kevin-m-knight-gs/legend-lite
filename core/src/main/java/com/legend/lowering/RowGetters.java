// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0
package com.legend.lowering;

import com.legend.compiler.spec.typed.TypedCString;
import com.legend.compiler.spec.typed.TypedNativeCall;
import com.legend.compiler.spec.typed.TypedVariable;
import com.legend.sql.SqlExpr;

import java.util.Set;

/**
 * The TDSRow getters (real tds.pure getString/getInteger/…) over the
 * column lambda's ROW variable with a literal name: the column read —
 * the typer's desugar when the row's schema is known at typing; here
 * the row was typed by its TDSRow annotation (a schema-erased source)
 * and the schema is the base's.
 */
final class RowGetters {

    private static final Set<String> GETTERS = Set.of(
            "meta::pure::tds::getString", "meta::pure::tds::getInteger",
            "meta::pure::tds::getFloat", "meta::pure::tds::getDecimal",
            "meta::pure::tds::getNumber", "meta::pure::tds::getBoolean",
            "meta::pure::tds::getDate", "meta::pure::tds::getDateTime",
            "meta::pure::tds::getStrictDate", "meta::pure::tds::getEnum");

    private RowGetters() {
    }

    static boolean isRowGetter(TypedNativeCall g) {
        return GETTERS.contains(g.callee().qualifiedName())
                && g.args().size() == 2
                && g.args().get(0) instanceof TypedVariable
                && g.args().get(1) instanceof TypedCString;
    }

    static SqlExpr read(TypedNativeCall g, Resolvers.ColumnResolver columns) {
        String row = ((TypedVariable) g.args().get(0)).name();
        String column = ((TypedCString) g.args().get(1)).value();
        SqlExpr r = columns.resolve(row, column);
        if (r == null) {
            throw new Resolvers.UnfoldableRef(column);
        }
        return r;
    }
}
