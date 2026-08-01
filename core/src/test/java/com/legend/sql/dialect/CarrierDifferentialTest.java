// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend.sql.dialect;

import com.legend.sql.SqlAgg;
import com.legend.sql.SqlExpr;
import com.legend.sql.SqlSelect;
import com.legend.sql.SqlSource;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * THE DIFFERENTIAL ORACLE (CARRIER_REDESIGN.md tenet #2): every
 * semantic collection node executes on DuckDB through BOTH strategies —
 * the native rule and the portable rule — and the rows must be EQUAL.
 * A strategy pair that can diverge is a bug this gate catches before
 * any backend sees it.
 */
class CarrierDifferentialTest {

    /** ReduceCollection over a collecting subselect: DuckDB renders
     * list_aggregate; PORTABLE fuses into the subselect (the engine's
     * grouped-subselect shape). Same rows, both strategies, on DuckDB. */
    @Test
    void reduceCollectionFusionRowEqual() throws Exception {
        SqlSelect collect = SqlSelect.starOf(
                        new SqlSource.Table("t", "s", List.of()))
                .withProjections(List.of(new SqlSelect.Projection(
                                new SqlAgg.Reducer(SqlAgg.Fn.LIST, List.of(
                                        new SqlExpr.Column("s", "v")), false,
                                        List.of()),
                                null)),
                        List.of());
        SqlExpr reduce = new SqlExpr.ReduceCollection(SqlAgg.Fn.STRING_AGG,
                new SqlExpr.ScalarSubquery(collect),
                List.of(new SqlExpr.StringLit("*")));
        SqlSelect q = SqlSelect.starOf(new SqlSource.Dual())
                .withProjections(List.of(
                                new SqlSelect.Projection(reduce, "joined")),
                        List.of());

        String nativeSql = new DuckDb().render(q);
        // the PORTABLE strategy must also be VALID DuckDB — that is the
        // whole differential contract
        String portableSql = new AnsiSqlRenderer(Lexicon.DUCKDB,
                TypeNames.DUCKDB, Spellings.DUCKDB).render(q);
        assertTrue(nativeSql.contains("list_aggregate"),
                "native strategy should use the list carrier: " + nativeSql);
        assertTrue(portableSql.contains("STRING_AGG"),
                "portable strategy should fuse into the subselect: "
                + portableSql);

        try (Connection c = DriverManager.getConnection("jdbc:duckdb:");
                Statement st = c.createStatement()) {
            st.execute("CREATE TABLE t (v VARCHAR)");
            st.execute("INSERT INTO t VALUES ('a'), ('b'), ('c')");
            assertEquals(one(st, nativeSql), one(st, portableSql),
                    "strategy divergence:\nnative:   " + nativeSql
                    + "\nportable: " + portableSql);
        }
    }

    /** The witnessed corpus shape (R1b): LIST_TRANSFORM between collect
     * and reduce — the element transform substitutes into the fused
     * projection. Both strategies row-equal on DuckDB. */
    @Test
    void transformedReduceFusionRowEqual() throws Exception {
        SqlSelect collect = SqlSelect.starOf(
                        new SqlSource.Table("t", "s", List.of()))
                .withProjections(List.of(new SqlSelect.Projection(
                                new SqlAgg.Reducer(SqlAgg.Fn.LIST, List.of(
                                        new SqlExpr.Column("s", "v")), false,
                                        List.of()),
                                null)),
                        List.of());
        SqlExpr transformed = SqlExpr.Call.of(
                com.legend.sql.SqlFn.LIST_TRANSFORM,
                new SqlExpr.ScalarSubquery(collect),
                new SqlExpr.Lambda(List.of("x"),
                        SqlExpr.Call.of(com.legend.sql.SqlFn.UPPER,
                                new SqlExpr.Column(null, "x"))));
        SqlExpr reduce = new SqlExpr.ReduceCollection(SqlAgg.Fn.STRING_AGG,
                transformed, List.of(new SqlExpr.StringLit(",")));
        SqlSelect q = SqlSelect.starOf(new SqlSource.Dual())
                .withProjections(List.of(
                                new SqlSelect.Projection(reduce, "joined")),
                        List.of());
        String nativeSql = new DuckDb().render(q);
        String portableSql = new AnsiSqlRenderer(Lexicon.DUCKDB,
                TypeNames.DUCKDB, Spellings.DUCKDB).render(q);
        try (Connection c = DriverManager.getConnection("jdbc:duckdb:");
                Statement st = c.createStatement()) {
            st.execute("CREATE TABLE t (v VARCHAR)");
            st.execute("INSERT INTO t VALUES ('a'), ('b')");
            assertEquals(one(st, nativeSql), one(st, portableSql),
                    "strategy divergence:\nnative:   " + nativeSql
                    + "\nportable: " + portableSql);
        }
    }

    private static String one(Statement st, String sql) throws Exception {
        try (ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return String.valueOf(rs.getObject(1));
        }
    }
}
