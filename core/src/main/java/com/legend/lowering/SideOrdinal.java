// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend.lowering;

import com.legend.sql.OutputCol;
import com.legend.sql.SqlAgg;
import com.legend.sql.SqlExpr;
import com.legend.sql.SqlQuery;
import com.legend.sql.SqlSelect;
import com.legend.sql.SqlSource;
import com.legend.sql.SqlType;

import java.util.ArrayList;
import java.util.List;

/**
 * A grid side's row ordinal: its rows' order as one more output column, {@link #ORD}, computed where the plan defines
 * that order, so a judge that reads the side through other selects (the database judge's verdict statement, its sides
 * as CTEs) numbers the rows by it, never by their arrival there. A database need not keep a derived table's or a CTE's
 * order through the selects that read it (measured 2026-10-20: MariaDB and Trino return a sorted CTE, and a literal's
 * UNION ALL, in another order, so the verdict's positional zip of assertTdsEquivalent paired the wrong rows: channel
 * B's reduce and pivot tests).
 *
 * <ul>
 *   <li>A literal's rows (a VALUES source): its row index, a literal column of each row.</li>
 *   <li>A sorted select: {@code ROW_NUMBER() OVER (ORDER BY <its sort keys>)} over its outputs, each key an output
 *       column of it.</li>
 *   <li>A select that keeps its source's rows in order (no sort, no grouping, no DISTINCT, one source) over a source
 *       whose order is defined: the source's ordinal, passed through.</li>
 *   <li>Any other: its rows as the database returns them ({@code ROW_NUMBER() OVER ()} over the side itself), the order
 *       the host judge reads it in.</li>
 * </ul>
 */
final class SideOrdinal {

    /** The ordinal's column. */
    static final String ORD = "__ord";

    private static final OutputCol ORD_OUT = new OutputCol(ORD, SqlType.Scalar.BIGINT, false);

    private SideOrdinal() {
    }

    /** {@code q} with its rows' ordinal as its last output. */
    static SqlQuery withOrdinal(SqlQuery q) {
        SqlQuery defined = defined(q);
        return defined != null ? defined : numbered(q, List.of());
    }

    /** {@code q} with the ordinal its plan defines, or null where it defines none. */
    private static @com.legend.base.Nullable SqlQuery defined(SqlQuery q) {
        if (!(q instanceof SqlSelect s)) {
            return null;
        }
        if (!s.orderBy().isEmpty()) {
            List<SqlSelect.SortKey> keys = outputKeys(s);
            return keys == null ? null : numbered(s, keys);
        }
        if (s.distinct() || !s.groupBy().isEmpty() || s.having() != null || s.limit() != null
                || s.offset() != null) {
            return null;
        }
        String alias;
        SqlSource from;
        if (s.from() instanceof SqlSource.Values v) {
            alias = v.alias();
            from = indexed(v);
        } else if (s.from() instanceof SqlSource.Subselect sub && defined(sub.inner()) instanceof SqlQuery inner) {
            alias = sub.alias();
            from = new SqlSource.Subselect(inner, sub.alias(), sub.frameName());
        } else {
            return null;
        }
        SqlSelect over = s.withFrom(from);
        SqlExpr ord = SqlExpr.Column.of(alias, ORD_OUT);
        if (s.projections().isEmpty()) {
            List<OutputCol> outs = new ArrayList<>(s.outputs());
            outs.add(ORD_OUT);
            return over.withOutputs(outs);
        }
        List<SqlSelect.Projection> ps = new ArrayList<>(s.projections());
        ps.add(new SqlSelect.Projection(ord, ORD, ORD_OUT));
        return over.withProjections(ps);
    }

    /** A literal's rows, each with its index. */
    private static SqlSource.Values indexed(SqlSource.Values v) {
        List<List<SqlExpr>> rows = new ArrayList<>(v.rows().size());
        for (int r = 0; r < v.rows().size(); r++) {
            List<SqlExpr> row = new ArrayList<>(v.rows().get(r));
            row.add(new SqlExpr.IntLit(r + 1));
            rows.add(row);
        }
        List<String> columns = new ArrayList<>(v.columns());
        columns.add(ORD);
        List<OutputCol> outs = new ArrayList<>(v.outputs());
        outs.add(ORD_OUT);
        return new SqlSource.Values(rows, columns, v.alias(), outs);
    }

    /** The sort keys of {@code s} as its output columns (read from outside it), or null where one is not an output. */
    private static @com.legend.base.Nullable List<SqlSelect.SortKey> outputKeys(SqlSelect s) {
        List<SqlSelect.SortKey> keys = new ArrayList<>(s.orderBy().size());
        for (SqlSelect.SortKey k : s.orderBy()) {
            String name = outputOf(s, k);
            if (name == null) {
                return null;
            }
            OutputCol out = s.outputs().stream().filter(o -> o.name().equals(name)).findFirst().orElseThrow();
            keys.add(new SqlSelect.SortKey(SqlExpr.Column.of("__o", out), k.ascending(), k.nullOrder(), null));
        }
        return keys;
    }

    /** The output of {@code s} a sort key orders by: the output it names, or the output whose expression it is (a
     *  star frame's outputs are its source's columns, by name). */
    private static @com.legend.base.Nullable String outputOf(SqlSelect s, SqlSelect.SortKey k) {
        if (k.outputName() != null && s.outputs().stream().anyMatch(o -> o.name().equals(k.outputName()))) {
            return k.outputName();
        }
        if (s.projections().isEmpty()) {
            return k.expr() instanceof SqlExpr.Column c
                    && s.outputs().stream().anyMatch(o -> o.name().equals(c.name())) ? c.name() : null;
        }
        for (SqlSelect.Projection p : s.projections()) {
            if (p.alias() != null && p.expr().equals(k.expr())) {
                return p.alias();
            }
        }
        return null;
    }

    /** {@code SELECT __o.*, ROW_NUMBER() OVER (ORDER BY keys) AS __ord FROM (q) __o}. */
    private static SqlQuery numbered(SqlQuery q, List<SqlSelect.SortKey> keys) {
        List<SqlSelect.Projection> ps = new ArrayList<>();
        for (OutputCol o : q.outputs()) {
            ps.add(new SqlSelect.Projection(SqlExpr.Column.of("__o", o), o.name(), o));
        }
        ps.add(new SqlSelect.Projection(new SqlExpr.WindowCall(
                new SqlAgg.RankingFn(SqlAgg.Fn.ROW_NUMBER, List.of()), List.of(), keys, null), ORD, ORD_OUT));
        List<OutputCol> outs = new ArrayList<>(q.outputs());
        outs.add(ORD_OUT);
        return new SqlSelect(ps, false, new SqlSource.Subselect(q, "__o", null), null, List.of(), null, null,
                List.of(), null, null, outs);
    }
}
