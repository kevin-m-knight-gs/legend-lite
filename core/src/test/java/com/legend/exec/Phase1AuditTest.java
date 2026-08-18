// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend.exec;

import com.legend.Compiler;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PHASE 1 AUDIT PINS (One-Platform Plan; verdict: PARTIAL — the
 * execution is platform-honest, the GENERALITY is not yet).
 *
 * <p>Pin 1 — bare {@code .rows} returns REAL Row objects with real
 * cells (user-forced honesty 2026-08-18: "don't we execute and get
 * something back?"), including rows whose FIRST column is NULL (the
 * shape that broke both prior stand-ins).
 *
 * <p>Pin 2 — the GENERALITY TRIPWIRE: {@code .rows->size()} is a
 * perfectly well-typed expression that today WALLS, because ResultNav
 * is a closed-vocabulary recognizer, not a typed platform feature.
 * This pin holds the honest current behavior and MUST FLIP when the
 * execution-backed metamodel-classes leg lands (Phase 3a: model
 * Result/ResultSet/Row so ANY well-typed expression compiles) — a
 * green flip here is that leg's acceptance test.
 */
class Phase1AuditTest {

    private static final String CONN_LET =
            "{| let c = ^meta::external::store::relational::runtime::TestDatabaseConnection("
                    + "type=meta::relational::runtime::DatabaseType.DuckDB);\n";

    private static Connection conn;

    @BeforeAll
    static void open() throws Exception {
        conn = DriverManager.getConnection("jdbc:duckdb:");
    }

    @AfterAll
    static void close() throws Exception {
        conn.close();
    }

    @Test
    @DisplayName("Pin 1: bare .rows = real Row objects, real cells, NULL-first-column included")
    void bareRowsAreRealRows() throws Exception {
        ExecutionResult r = Compiler.execute("", CONN_LET
                + "meta::relational::metamodel::execute::executeInDb("
                + "'select null as A, 7 as B union all"
                + " select null, 8 order by 2', $c, 0, 1000).rows;}", conn);
        List<Object> rows = ((ExecutionResult.Collection) r).values();
        assertEquals(2, rows.size(),
                "NULL-first-column rows must COUNT (both prior stand-ins"
                + " failed exactly here)");
        Row first = assertInstanceOf(Row.class, rows.get(0),
                "bare rows are the platform's Row carrier, not a witness");
        assertEquals(7L, ((Number) first.get(1)).longValue(),
                "the Row holds its REAL cells");
    }

    @Test
    @DisplayName("Pin 2 (generality tripwire): .rows->size() WALLS today; Phase 3a flips this to 2")
    void rowsSizeWallsUntilTheMetamodelLegLands() {
        Exception e = assertThrows(Exception.class,
                () -> Compiler.execute("", CONN_LET
                        + "meta::relational::metamodel::execute::executeInDb("
                        + "'select 1 as A union all select 2', $c, 0, 1000)"
                        + ".rows->size();}", conn));
        assertTrue(e.getMessage() != null
                        && e.getMessage().contains("host channel"),
                "the wall must be the honest oracle-not-runtime decline,"
                + " never a wrong value; got: " + e.getMessage());
    }
}
