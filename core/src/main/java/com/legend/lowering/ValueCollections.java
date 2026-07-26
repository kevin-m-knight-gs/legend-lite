// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend.lowering;

import com.legend.compiler.element.type.Type;
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
        return SqlSelect.starOf(new SqlSource.Subselect(rel, sub))
                .withProjections(List.of(new SqlSelect.Projection(
                                new SqlAgg.Reducer("LIST", List.of(
                                        new SqlExpr.Column(sub, col)), false),
                                null)),
                        List.of(new OutputCol(col, SqlType.Scalar.VARCHAR,
                                true)));
    }

    /** {@code SELECT flatten(LIST([cells...]))} over {@code rel} — a
     * MULTI-column relation as a ROW-MAJOR value collection. Cells cast
     * to ONE list type (VARCHAR — DuckDB refuses mixed list_value). */
    static SqlSelect rowMajorCellList(SqlSelect rel, Type.RelationType rt,
            String sub) {
        List<SqlExpr> cells = new ArrayList<>();
        for (Type.Column c : rt.columns()) {
            cells.add(new SqlExpr.Cast(new SqlExpr.Column(sub, c.name()),
                    SqlType.Scalar.VARCHAR));
        }
        return SqlSelect.starOf(new SqlSource.Subselect(rel, sub))
                .withProjections(List.of(new SqlSelect.Projection(
                                SqlExpr.Call.of(SqlFn.LIST_FLATTEN,
                                        new SqlAgg.Reducer("LIST", List.of(
                                                new SqlExpr.ArrayLit(cells)),
                                                false)),
                                null)),
                        List.of(new OutputCol("value", SqlType.Scalar.VARCHAR,
                                true)));
    }
}
