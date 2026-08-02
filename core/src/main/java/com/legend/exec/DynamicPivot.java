// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend.exec;

import com.legend.sql.SqlExpr;
import com.legend.sql.SqlQuery;
import com.legend.sql.SqlRewriter;
import com.legend.sql.SqlSelect;
import com.legend.sql.SqlSource;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * DYNAMIC-PIVOT STATICIZATION (PV1b) — the TWO-PHASE execution a
 * backend without native dynamic PIVOT needs (the engine's own H2
 * route): a {@code Pivot} whose IN list is EMPTY has data-dependent
 * output columns, so the key's DISTINCT values are discovered by a
 * FIRST query on the SAME connection and pinned as literals; the now
 * STATIC pivot then takes the ordinary emulation strategy
 * ({@code CarrierStrategies}). Value order is ascending — the
 * reference target's dynamic-pivot column order. NULL keys are
 * skipped: a NULL never names an output column.
 *
 * <p>Dialects with native dynamic PIVOT ({@link
 * com.legend.sql.dialect.SqlDialect#needsStaticPivot} false) pass
 * through untouched — this pre-pass runs at the EXECUTION seam, where
 * a connection exists, never inside rendering.
 */
public final class DynamicPivot {

    private DynamicPivot() {
    }

    public static SqlQuery staticize(SqlQuery plan,
            com.legend.sql.dialect.SqlDialect dialect,
            Connection connection) throws SQLException {
        if (!dialect.needsStaticPivot()) {
            return plan;
        }
        try {
            return new SqlRewriter() {
                @Override
                protected SqlSource source(SqlSource s) {
                    if (!(s instanceof SqlSource.Pivot p) || !p.in().isEmpty()
                            || p.on().size() != 1) {
                        return s;
                    }
                    try {
                        return new SqlSource.Pivot(p.source(), p.on(),
                                discover(p, dialect, connection), p.usings(),
                                p.alias(), p.outputs());
                    } catch (SQLException e) {
                        throw new Wrapped(e);
                    }
                }
            }.rewrite(plan);
        } catch (Wrapped w) {
            throw w.cause;
        }
    }

    /** The key's distinct non-null values, ascending, as literals. */
    private static List<SqlExpr> discover(SqlSource.Pivot p,
            com.legend.sql.dialect.SqlDialect dialect, Connection connection)
            throws SQLException {
        SqlExpr key = p.on().get(0);
        SqlSelect q = SqlSelect.starOf(p.source())
                .withProjections(List.of(new SqlSelect.Projection(key, "v")),
                        List.of())
                .withDistinct()
                .withWhere(SqlExpr.Call.of(com.legend.sql.SqlFn.IS_NOT_NULL,
                        key))
                .withOrderBy(List.of(SqlSelect.SortKey.asc(
                        new SqlExpr.Column(null, "v"))));
        List<SqlExpr> in = new ArrayList<>();
        try (Statement st = connection.createStatement();
                ResultSet rs = st.executeQuery(dialect.render(q))) {
            while (rs.next()) {
                Object v = rs.getObject(1);
                in.add(switch (v) {
                    case Integer i -> new SqlExpr.IntLit(i);
                    case Long l -> new SqlExpr.IntLit(l);
                    case Boolean b -> new SqlExpr.BoolLit(b);
                    case null -> throw new IllegalStateException(
                            "NULL pivot key past the IS NOT NULL guard");
                    default -> new SqlExpr.StringLit(String.valueOf(v));
                });
            }
        }
        return in;
    }

    private static final class Wrapped extends RuntimeException {
        final SQLException cause;

        Wrapped(SQLException cause) {
            this.cause = cause;
        }
    }
}
