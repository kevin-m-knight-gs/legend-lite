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

    /** The Typer's {@code $r.values} TDSRow-cells synthesis — the one
     * shape that prints TDSNull: every element a property read off the
     * SAME row variable, AND the reads cover the row's FULL column
     * roster in order. The roster requirement is the synthesis
     * SIGNATURE: it distinguishes {@code $r.values} from a HAND-WRITTEN
     * cell list ({@code [$r.getString(a), $r.getString(b)]} — engine
     * joinStrings semantics, bare columns, no TDSNull) — a distinction
     * the old toOne getter-desugar wraps carried by ACCIDENT until the
     * honest desugar made hand-written reads bare too (stamp program,
     * testHashFunctions witness). */
    static boolean isRowCells(TypedCollection tc) {
        String var = null;
        TypedVariable rowVar = null;
        for (TypedSpec e : tc.elements()) {
            if (!(e instanceof TypedPropertyAccess pa
                    && pa.source()
                            instanceof TypedVariable v)) {
                return false;
            }
            if (var == null) {
                var = v.name();
                rowVar = v;
            } else if (!var.equals(v.name())) {
                return false;
            }
        }
        if (tc.elements().isEmpty() || rowVar == null
                || !(rowVar.info().type()
                        instanceof Type.RelationType rt)) {
            return false;
        }
        java.util.List<Type.RelationType.Column> cols = rt.columns();
        if (cols.size() != tc.elements().size()) {
            return false;
        }
        for (int i = 0; i < cols.size(); i++) {
            if (!cols.get(i).name().equals(
                    ((TypedPropertyAccess) tc.elements().get(i)).property())) {
                return false;
            }
        }
        return true;
    }

    /** The rowCells makeString/joinStrings join as a STATIC CONCAT
     * interleave: (start?) c1 (sep c2 …) (end?) — args beyond the
     * collection arrive lowered in order ([sep] or [start, sep, end]). */
    static com.legend.sql.SqlExpr rowCellsJoin(
            java.util.List<com.legend.sql.SqlExpr> cells,
            java.util.List<com.legend.sql.SqlExpr> rest) {
        com.legend.sql.SqlExpr sep = rest.size() == 1 ? rest.get(0)
                : rest.size() == 3 ? rest.get(1) : null;
        java.util.List<com.legend.sql.SqlExpr> parts = new ArrayList<>();
        if (rest.size() == 3) {
            parts.add(rest.get(0));
        }
        for (int i = 0; i < cells.size(); i++) {
            if (i > 0 && sep != null) {
                parts.add(sep);
            }
            parts.add(cells.get(i));
        }
        if (rest.size() == 3) {
            parts.add(rest.get(2));
        }
        return parts.size() == 1 ? parts.get(0)
                : parts.isEmpty() ? new com.legend.sql.SqlExpr.StringLit("")
                : new com.legend.sql.SqlExpr.Call(
                        com.legend.sql.SqlFn.CONCAT, parts);
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
