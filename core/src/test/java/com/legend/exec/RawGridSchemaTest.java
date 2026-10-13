// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend.exec;

import com.legend.model.ConnectionDefinition.DatabaseType;
import com.legend.test.StorelessRuntime;

import com.legend.Execution;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * THE BOUNDARY RESOLVER's pins (Phase 1c grid endgame): late-bound
 * reads resolve against the FIRST-query stamped schema (the
 * dynamic-pivot model), and everything then rides the ordinary
 * pipeline — the database does the work.
 */
class RawGridSchemaTest {

    private static final String CONN_LET =
            "{| let c = ^meta::external::store::relational::runtime::TestDatabaseConnection("
                    + "type=meta::relational::runtime::DatabaseType.DuckDB);\n";

    private static final String EXEC =
            "meta::relational::metamodel::execute::executeInDb(";

    private static Connection conn;

    @BeforeAll
    static void open() throws Exception {
        conn = DriverManager.getConnection("jdbc:duckdb:");
    }

    @AfterAll
    static void close() throws Exception {
        conn.close();
    }

    /** A connection a helper builds, the way the corpus's {@code testRuntime()} does: the zero-parameter helper names
     *  an overload of one parameter, and every overload of that arity declares the same database. */
    private static final String HELPERS = """
            function test::conn():meta::external::store::relational::runtime::DatabaseConnection[1]
            { test::conn('x') }
            function test::conn(s:String[1]):meta::external::store::relational::runtime::DatabaseConnection[1]
            { ^meta::external::store::relational::runtime::TestDatabaseConnection(
                    type=meta::relational::runtime::DatabaseType.DuckDB) }
            function test::conn(i:Integer[1]):meta::external::store::relational::runtime::DatabaseConnection[1]
            { ^meta::external::store::relational::runtime::TestDatabaseConnection(
                    type=meta::relational::runtime::DatabaseType.%s) }
            """;

    @Test
    @DisplayName("a connection a helper builds declares its database through the helper's body (decision D2)")
    void helperConnectionDeclaresItsDatabase() throws Exception {
        ExecutionResult r = Execution.execute(StorelessRuntime.with(HELPERS.formatted("DuckDB"), DatabaseType.DuckDB),
                "{| " + EXEC + "'select 1 as A union all select 2', test::conn(), 0, 1000).rows.value('A')->size();}",
                StorelessRuntime.RUNTIME, conn);
        assertEquals(2L, ((Number) ((ExecutionResult.Scalar) r).value()).longValue());
    }

    @Test
    @DisplayName("overloads that declare different databases leave the text's language unknown: refused (decision D2)")
    void disagreeingOverloadsAreRefused() {
        // the raw body names test::conn, not which overload: H2 and DuckDB both answer, so neither is guessed
        Exception e = assertThrows(Exception.class, () -> Execution.execute(
                StorelessRuntime.with(HELPERS.formatted("H2"), DatabaseType.DuckDB),
                "{| " + EXEC + "'select 1 as A', test::conn(), 0, 1000).rows.value('A')->size();}",
                StorelessRuntime.RUNTIME, conn));
        assertTrue(String.valueOf(e.getMessage()).contains("decision D2"), e.toString());
    }

    @Test
    @DisplayName("columnNames resolves to the string collection and composes")
    void columnNamesComposes() throws Exception {
        ExecutionResult r = Execution.execute(StorelessRuntime.with("", DatabaseType.DuckDB), CONN_LET
                + EXEC + "'select 1 as A, 2 as B', $c, 0, 1000)"
                + ".columnNames->at(1);}", StorelessRuntime.RUNTIME, conn);
        assertEquals("B", ((ExecutionResult.Scalar) r).value());
    }

    @Test
    @DisplayName(".rows.value('N') AUTO-MAPS over many rows (pure's dot rule)")
    void rowsValueAutoMaps() throws Exception {
        ExecutionResult r = Execution.execute(StorelessRuntime.with("", DatabaseType.DuckDB), CONN_LET
                + EXEC + "'select 1 as A union all select 2', $c, 0, 1000)"
                + ".rows.value('A')->size();}", StorelessRuntime.RUNTIME, conn);
        assertEquals(2L, ((Number) ((ExecutionResult.Scalar) r).value())
                .longValue());
    }

    @Test
    @DisplayName("row-major .rows.values->at(k) resolves via the cells map")
    void rowMajorCellRead() throws Exception {
        ExecutionResult r = Execution.execute(StorelessRuntime.with("", DatabaseType.DuckDB), CONN_LET
                + "let rs = " + EXEC + "'select 1 as A, \\'x\\' as B', $c);\n"
                + "$rs.rows.values->at(1);}", StorelessRuntime.RUNTIME, conn);
        assertEquals("x", ((ExecutionResult.Scalar) r).value());
    }

    @Test
    @DisplayName("fold column-collect lowers as the per-row map")
    void foldColumnCollect() throws Exception {
        ExecutionResult r = Execution.execute(StorelessRuntime.with("", DatabaseType.DuckDB), CONN_LET
                + EXEC + "'select 1 as A, \\'x\\' as B union all"
                + " select 2, \\'y\\'', $c, 0, 1000)"
                + ".rows->fold({a,b| concatenate($a.values->at(1), $b)}, [])"
                + "->size();}", StorelessRuntime.RUNTIME, conn);
        assertEquals(2L, ((Number) ((ExecutionResult.Scalar) r).value())
                .longValue());
    }

    @Test
    @DisplayName("audit T1.1: a SHADOWING inner binder never sees the outer grid schema")
    void shadowedBinderIsNotResolved() throws Exception {
        // outer map binds `r` to the GRID row; the inner lambda over a
        // PLAIN collection re-binds `r` — its $r must stay the inner
        // value (2, 3), never the outer row's cells. A wrong resolver
        // would substitute the grid cells and change the sum.
        ExecutionResult r = Execution.execute(StorelessRuntime.with("", DatabaseType.DuckDB), CONN_LET
                + EXEC + "'select 10 as A', $c, 0, 1000)"
                + ".rows->map(r | [2, 3]->map(r | $r)->sum());}", StorelessRuntime.RUNTIME, conn);
        // one grid row -> one mapped value: 2 + 3 = 5
        assertEquals(List.of(5L),
                ((ExecutionResult.Collection) r).values().stream()
                        .map(v -> ((Number) v).longValue()).toList());
    }
}
