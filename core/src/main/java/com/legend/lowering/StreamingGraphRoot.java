// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend.lowering;

import com.legend.compiler.element.type.Type;
import com.legend.sql.OutputCol;
import com.legend.sql.SqlExpr;
import com.legend.sql.SqlSelect;

import java.util.ArrayList;
import java.util.List;

/**
 * The STREAMING graph-root form ({@code Lowerer#withStreamingGraphRoot}):
 * one {@code json_object} per JDBC row instead of a single
 * {@code json_group_array} row, ordered by the SAME keys the aggregate
 * would have used (the agg's desc flag inverts ascending) — a streaming
 * executor concatenates rows in O(one row) memory. Nested serializes keep
 * aggregating inside each row.
 */
final class StreamingGraphRoot {

    private StreamingGraphRoot() {
    }

    static SqlSelect select(SqlSelect envelope, SqlExpr obj,
            List<SqlExpr.JsonArrayAgg.Key> okeys) {
        List<SqlSelect.SortKey> order = new ArrayList<>(okeys.size());
        for (SqlExpr.JsonArrayAgg.Key k : okeys) {
            order.add(k.desc() ? SqlSelect.SortKey.desc(k.expr())
                    : SqlSelect.SortKey.asc(k.expr()));
        }
        SqlSelect s = envelope.withProjections(
                List.of(new SqlSelect.Projection(obj, "result")),
                List.of(new OutputCol("result",
                        PureSql.type(Type.Primitive.STRING), false)));
        return new SqlSelect(s.projections(), s.distinct(), s.from(),
                s.where(), s.groupBy(), s.having(), s.qualify(), order,
                s.limit(), s.offset(), s.outputs());
    }
}
