// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend.lowering;

import com.legend.compiler.element.type.Type;
import com.legend.compiler.spec.typed.TypedCast;
import com.legend.compiler.spec.typed.TypedCollection;
import com.legend.compiler.spec.typed.TypedLambda;
import com.legend.compiler.spec.typed.TypedPropertyAccess;
import com.legend.compiler.spec.typed.TypedSpec;
import com.legend.compiler.spec.typed.TypedVariable;
import com.legend.sql.OutputCol;
import com.legend.sql.SqlAgg;
import com.legend.sql.SqlExpr;
import com.legend.sql.SqlFn;
import com.legend.sql.SqlSelect;
import com.legend.sql.SqlSource;
import com.legend.sql.SqlType;

import java.util.ArrayList;
import java.util.List;

/**
 * VALUE-COLLECTION select builders — a relation consumed as a LIST value
 * (contains/in/makeString tails, the assert idiom
 * {@code $result.values.rows.values}). Pure SQL-IR construction; the
 * Lowerer supplies the lowered relation and the enclosing-scope channel.
 */
final class ValueCollections {

    private ValueCollections() {
    }

    /** {@code SELECT LIST(col)} over {@code rel} — the single-column
     * value collection. */
    static SqlSelect columnList(SqlSelect rel, String col, String sub) {
        return SqlSelect.starOf(new SqlSource.Subselect(rel, sub, null))
                .withProjections(List.of(new SqlSelect.Projection(
                                new SqlAgg.Reducer(SqlAgg.Fn.LIST, List.of(
                                        new SqlExpr.Column(sub, col)), false, java.util.List.of()),
                                null)),
                        List.of(new OutputCol(col, SqlType.Scalar.VARCHAR,
                                true)));
    }

    /** {@code SELECT flatten(LIST([cells...]))} over {@code rel} — a
     * MULTI-column relation as a ROW-MAJOR value collection. Cells ride
     * the VARIANT carrier (to_json — the mixed-list discipline: exact
     * type preservation, same spelling the Any-LUB collection literal
     * uses), never a text CAST. */
    static SqlSelect rowMajorCellList(SqlSelect rel, Type.RelationType rt,
            String sub) {
        List<SqlExpr> cells = new ArrayList<>();
        for (Type.Column c : rt.columns()) {
            cells.add(SqlExpr.Call.of(SqlFn.TO_VARIANT,
                    new SqlExpr.Column(sub, c.name())));
        }
        return SqlSelect.starOf(new SqlSource.Subselect(rel, sub, null))
                .withProjections(List.of(new SqlSelect.Projection(
                                SqlExpr.Call.of(SqlFn.LIST_FLATTEN,
                                        new SqlAgg.Reducer(SqlAgg.Fn.LIST, List.of(
                                                new SqlExpr.ArrayLit(cells)),
                                                false, java.util.List.of())),
                                null)),
                        List.of(new OutputCol("value", SqlType.Scalar.VARCHAR,
                                true)));
    }

    /** Every element is a property read off the SAME row variable — the
     * Typer's TDSRow cells synthesis, the one shape that prints TDSNull. */
    static boolean isRowCells(TypedCollection tc) {
        String var = null;
        for (TypedSpec e : tc.elements()) {
            if (!(e instanceof TypedPropertyAccess pa
                    && pa.source()
                            instanceof TypedVariable v)) {
                return false;
            }
            if (var == null) {
                var = v.name();
            } else if (!var.equals(v.name())) {
                return false;
            }
        }
        return !tc.elements().isEmpty();
    }

    static boolean isCollectionMapper(TypedLambda ml) {
        // Collection mapper iff the lowered value is a SQL LIST, which is
        // exactly a TypedCollection body (list_value carrier). A loose
        // declared multiplicity over a non-collection body still lowers to
        // a plain scalar column — wrapping it in UNNEST/flatten is a type
        // error, not a flatten. Casts distribute element-wise over
        // collections (the value stays a LIST) — look through them
        // ($x.values->cast(@StrictDate), calendar DateRange).
        TypedSpec last = ml.body().get(ml.body().size() - 1);
        while (last instanceof TypedCast tc) {
            last = tc.source();
        }
        return last instanceof TypedCollection;
    }
}
