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

    /** Literal-collection join (R1c): STRING_AGG over a transformed
     * ArrayLit expands to the CONCAT chain — both strategies row-equal. */
    @Test
    void literalArrayJoinRowEqual() throws Exception {
        SqlExpr transformed = SqlExpr.Call.of(
                com.legend.sql.SqlFn.LIST_TRANSFORM,
                new SqlExpr.ArrayLit(List.of(new SqlExpr.StringLit("a"),
                        new SqlExpr.StringLit("b"),
                        new SqlExpr.StringLit("c"))),
                new SqlExpr.Lambda(List.of("x"),
                        SqlExpr.Call.of(com.legend.sql.SqlFn.UPPER,
                                new SqlExpr.Column(null, "x"))));
        SqlExpr reduce = new SqlExpr.ReduceCollection(SqlAgg.Fn.STRING_AGG,
                transformed, List.of(new SqlExpr.StringLit("|")));
        SqlSelect q = SqlSelect.starOf(new SqlSource.Dual())
                .withProjections(List.of(
                                new SqlSelect.Projection(reduce, "joined")),
                        List.of());
        String nativeSql = new DuckDb().render(q);
        String portableSql = new AnsiSqlRenderer(Lexicon.DUCKDB,
                TypeNames.DUCKDB, Spellings.DUCKDB).render(q);
        try (Connection c = DriverManager.getConnection("jdbc:duckdb:");
                Statement st = c.createStatement()) {
            assertEquals(one(st, nativeSql), one(st, portableSql),
                    "strategy divergence:\nnative:   " + nativeSql
                    + "\nportable: " + portableSql);
        }
    }

    /** Membership NULL truth table (R2 crux): needle NULL, element
     * NULL + absent needle, present needle, empty — both strategies on
     * DuckDB. Filter-position equality is the row contract; the
     * projected false-vs-NULL difference on absent-with-NULL-element is
     * ABSORBED by the emission sites' COALESCE(_, false) wrapper, which
     * this fixture reproduces. */
    @Test
    void membershipNullEdgesRowEqual() throws Exception {
        record Cse(SqlExpr needle, java.util.List<SqlExpr> elems) { }
        java.util.List<Cse> cases = java.util.List.of(
                new Cse(new SqlExpr.StringLit("a"),
                        java.util.List.of(new SqlExpr.StringLit("a"),
                                new SqlExpr.NullLit())),
                new Cse(new SqlExpr.StringLit("x"),
                        java.util.List.of(new SqlExpr.StringLit("a"),
                                new SqlExpr.NullLit())),
                new Cse(new SqlExpr.NullLit(),
                        java.util.List.of(new SqlExpr.StringLit("a"))),
                new Cse(new SqlExpr.StringLit("a"), java.util.List.of()));
        for (Cse cse : cases) {
            SqlExpr member = SqlExpr.Call.of(com.legend.sql.SqlFn.COALESCE,
                    new SqlExpr.Membership(cse.needle(),
                            new SqlExpr.ArrayLit(cse.elems())),
                    new SqlExpr.BoolLit(false));
            SqlSelect q = SqlSelect.starOf(new SqlSource.Dual())
                    .withProjections(List.of(
                                    new SqlSelect.Projection(member, "m")),
                            List.of());
            String nativeSql = new DuckDb().render(q);
            String portableSql = new AnsiSqlRenderer(Lexicon.DUCKDB,
                    TypeNames.DUCKDB, Spellings.DUCKDB).render(q);
            try (Connection c = DriverManager.getConnection("jdbc:duckdb:");
                    Statement st = c.createStatement()) {
                assertEquals(one(st, nativeSql), one(st, portableSql),
                        "membership divergence:\nnative:   " + nativeSql
                        + "\nportable: " + portableSql);
            }
        }
    }

    /** Explode-of-collect (R5b): {@code unnest((SELECT LIST(v) FROM t))}
     * IS the collecting row set — both strategies row-equal IN ORDER. */
    @Test
    void explodeOfCollectRowEqual() throws Exception {
        SqlSelect q = unnestOverDual(collectOfV(), "e");
        try (Connection c = DriverManager.getConnection("jdbc:duckdb:");
                Statement st = c.createStatement()) {
            seed(st);
            assertEquals(all(st, new DuckDb().render(q)),
                    all(st, portable().render(q)),
                    "explode divergence");
        }
    }

    /** Sorted explode (R5b): {@code unnest(LIST_SORT(collect))} — the
     * probed list_sort contract is ASC NULLS LAST; the NULL row in the
     * seed pins the placement. */
    @Test
    void explodeOfSortedCollectRowEqual() throws Exception {
        SqlSelect q = unnestOverDual(SqlExpr.Call.of(
                com.legend.sql.SqlFn.LIST_SORT, collectOfV()), "e");
        try (Connection c = DriverManager.getConnection("jdbc:duckdb:");
                Statement st = c.createStatement()) {
            seed(st);
            st.execute("INSERT INTO t VALUES (NULL)");
            assertEquals(all(st, new DuckDb().render(q)),
                    all(st, portable().render(q)),
                    "sorted explode divergence");
        }
    }

    /** unnest(NULL) is ZERO rows on both strategies (R5b). */
    @Test
    void explodeOfNullRowEqual() throws Exception {
        SqlSelect q = unnestOverDual(new SqlExpr.NullLit(), "e");
        try (Connection c = DriverManager.getConnection("jdbc:duckdb:");
                Statement st = c.createStatement()) {
            assertEquals(all(st, new DuckDb().render(q)),
                    all(st, portable().render(q)),
                    "null explode divergence");
        }
    }

    /** Concat fold + literal explode (R5b): unnest(list_concat(a, b))
     * over compile-time collections — branch order preserved. */
    @Test
    void concatFoldedExplodeRowEqual() throws Exception {
        SqlExpr arg = SqlExpr.Call.of(com.legend.sql.SqlFn.LIST_CONCAT,
                new SqlExpr.ArrayLit(List.of(new SqlExpr.StringLit("a"),
                        new SqlExpr.StringLit("b"))),
                new SqlExpr.ArrayLit(List.of(new SqlExpr.StringLit("c"))));
        SqlSelect q = unnestOverDual(arg, "e");
        try (Connection c = DriverManager.getConnection("jdbc:duckdb:");
                Statement st = c.createStatement()) {
            assertEquals(all(st, new DuckDb().render(q)),
                    all(st, portable().render(q)),
                    "concat explode divergence");
        }
    }

    /** Through-subselect cells, k=1 (R5b): SELECT unnest(c) FROM
     * (SELECT [v] AS c FROM t) — the single-cell explode is EXACT. */
    @Test
    void throughSubselectSingletonRowEqual() throws Exception {
        SqlSelect inner = SqlSelect.starOf(
                        new SqlSource.Table("t", "s", List.of()))
                .withProjections(List.of(new SqlSelect.Projection(
                                new SqlExpr.ArrayLit(List.of(
                                        new SqlExpr.Column("s", "v"))), "c")),
                        List.of());
        SqlSelect q = SqlSelect.starOf(
                        new SqlSource.Subselect(inner, "sub", null))
                .withProjections(List.of(new SqlSelect.Projection(
                                SqlExpr.Call.of(com.legend.sql.SqlFn.UNNEST,
                                        new SqlExpr.Column("sub", "c")), "e")),
                        List.of());
        try (Connection c = DriverManager.getConnection("jdbc:duckdb:");
                Statement st = c.createStatement()) {
            seed(st);
            assertEquals(all(st, new DuckDb().render(q)),
                    all(st, portable().render(q)),
                    "through-subselect explode divergence");
        }
    }

    /** Through-subselect cells, k=2 (R5b): DuckDB unnest is ROW-major,
     * the UNION-ALL branches are COLUMN-major — the VALUE SET is equal
     * (multiset compare); observed order divergence is the sweep's
     * call, never silent (documented in the strategy arm). */
    @Test
    void throughSubselectPairMultisetEqual() throws Exception {
        SqlSelect inner = SqlSelect.starOf(
                        new SqlSource.Table("t", "s", List.of()))
                .withProjections(List.of(new SqlSelect.Projection(
                                new SqlExpr.ArrayLit(List.of(
                                        new SqlExpr.Column("s", "v"),
                                        SqlExpr.Call.of(
                                                com.legend.sql.SqlFn.UPPER,
                                                new SqlExpr.Column("s", "v")))),
                                "c")),
                        List.of());
        SqlSelect q = SqlSelect.starOf(
                        new SqlSource.Subselect(inner, "sub", null))
                .withProjections(List.of(new SqlSelect.Projection(
                                SqlExpr.Call.of(com.legend.sql.SqlFn.UNNEST,
                                        new SqlExpr.Column("sub", "c")), "e")),
                        List.of());
        try (Connection c = DriverManager.getConnection("jdbc:duckdb:");
                Statement st = c.createStatement()) {
            seed(st);
            java.util.List<String> nat =
                    new java.util.ArrayList<>(all(st, new DuckDb().render(q)));
            java.util.List<String> port =
                    new java.util.ArrayList<>(all(st, portable().render(q)));
            java.util.Collections.sort(nat);
            java.util.Collections.sort(port);
            assertEquals(nat, port, "pair explode multiset divergence");
        }
    }

    // ---- R5b fixture plumbing ----

    private static void seed(Statement st) throws Exception {
        st.execute("CREATE TABLE t (v VARCHAR)");
        st.execute("INSERT INTO t VALUES ('b'), ('a'), ('c')");
    }

    private static SqlExpr collectOfV() {
        return new SqlExpr.ScalarSubquery(SqlSelect.starOf(
                        new SqlSource.Table("t", "s", List.of()))
                .withProjections(List.of(new SqlSelect.Projection(
                                new SqlAgg.Reducer(SqlAgg.Fn.LIST, List.of(
                                        new SqlExpr.Column("s", "v")), false,
                                        List.of()),
                                null)),
                        List.of()));
    }

    private static SqlSelect unnestOverDual(SqlExpr arg, String alias) {
        return SqlSelect.starOf(new SqlSource.Dual())
                .withProjections(List.of(new SqlSelect.Projection(
                                SqlExpr.Call.of(com.legend.sql.SqlFn.UNNEST,
                                        arg), alias)),
                        List.of());
    }

    private static AnsiSqlRenderer portable() {
        return new AnsiSqlRenderer(Lexicon.DUCKDB, TypeNames.DUCKDB,
                Spellings.DUCKDB);
    }

    private static java.util.List<String> all(Statement st, String sql)
            throws Exception {
        java.util.List<String> out = new java.util.ArrayList<>();
        try (ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                out.add(String.valueOf(rs.getObject(1)));
            }
        }
        return out;
    }

    private static String one(Statement st, String sql) throws Exception {
        try (ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return String.valueOf(rs.getObject(1));
        }
    }
}
