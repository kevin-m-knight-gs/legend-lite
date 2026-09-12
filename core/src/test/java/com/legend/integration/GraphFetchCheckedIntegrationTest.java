// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend.integration;

import com.legend.exec.ExecutionResult;
import com.legend.server.QueryService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code graphFetchChecked} defect reporting witnesses (type audit D102).
 *
 * <p>The defect CASE must treat a SQL-NULL predicate as the engine's
 * generated checker treats a mid-evaluation throw: a required property
 * absent on the wire yields the catch-branch defect {@code Unable to
 * evaluate constraint [id]: data not available - check your mappings}
 * (oracle: legendJavaPlatformBinding shared/constraints.pure
 * generateConstraintMethod). Before the fix, {@code NOT NULL} was
 * not-true and an object violating EVERY constraint reported
 * {@code "defects":[]}.
 */
class GraphFetchCheckedIntegrationTest {

    private Connection conn;
    private final QueryService qs = new QueryService();

    @BeforeEach
    void setUp() throws SQLException {
        conn = DriverManager.getConnection("jdbc:duckdb:");
        try (Statement s = conn.createStatement()) {
            s.execute("CREATE TABLE T_EDGE (ID INTEGER PRIMARY KEY,"
                    + " S_STRING VARCHAR(100), S_INT INTEGER)");
            s.execute("INSERT INTO T_EDGE VALUES (1, 'plain', 5),"
                    + " (2, '', -3), (5, NULL, NULL)");
        }
    }

    @AfterEach
    void tearDown() throws SQLException {
        if (conn != null && !conn.isClosed()) {
            conn.close();
        }
    }

    private static final String MODEL = """
            ###Pure
            Class test::Edge
            [
              posInt: $this.sInt > 0,
              nonEmptyStr: $this.sString != ''
            ]
            { id: Integer[1]; sString: String[1]; sInt: Integer[1]; }

            ###Relational
            Database store::DB
            (
                Table T_EDGE (ID INTEGER PRIMARY KEY, S_STRING VARCHAR(100), S_INT INTEGER)
            )
            ###Mapping
            Mapping test::M
            (
                test::Edge: Relational
                {
                    ~mainTable [store::DB] T_EDGE
                    id: [store::DB] T_EDGE.ID,
                    sString: [store::DB] T_EDGE.S_STRING,
                    sInt: [store::DB] T_EDGE.S_INT
                }
            )
            ###Connection
            RelationalDatabaseConnection store::Conn { type: DuckDB; specification: DuckDB { }; auth: Test; }
            ###Runtime
            Runtime test::RT { mappings: [ test::M ]; connections: [ store::DB: [ environment: store::Conn ] ]; }
            """;

    private String checkedJson() throws SQLException {
        ExecutionResult r = qs.execute(MODEL, """
                test::Edge.all()
                    ->graphFetchChecked(#{test::Edge {id, sString, sInt}}#)
                    ->serialize(#{test::Edge {id, sString, sInt}}#)
                """, "test::RT", conn);
        assertInstanceOf(ExecutionResult.Graph.class, r);
        return r.asGraph().json();
    }

    @Test
    void nullPredicateReportsUnableToEvaluateDefects() throws SQLException {
        String json = checkedJson();
        // the all-NULL row (id=5): the comparison constraint cannot
        // evaluate — the engine's catch-branch defect, NOT a clean
        // envelope (pure lane: [] > 0 raises; generated-Java lane:
        // unboxing NPE)
        assertTrue(json.contains("Unable to evaluate constraint"
                        + " [posInt]: data not available - check your"
                        + " mappings"),
                "NULL predicate must defect (posInt): " + json);
        // the EQUALITY constraint is pure-faithful null-safe: [] != ''
        // is TRUE, so the absent string SATISFIES nonEmptyStr — its
        // predicate is true, not NULL, and no defect is correct (both
        // oracles agree: pure equal is size-aware, the engine's
        // generated equal is null-safe)
        assertFalse(json.contains("Unable to evaluate constraint"
                        + " [nonEmptyStr]"),
                "null-safe equality must not defect: " + json);
        assertFalse(json.contains("\"defects\":[],\"value\":{\"id\":5"),
                "the all-NULL object must not report clean: " + json);
    }

    @Test
    void falseAndTrueArmsKeepTheirSpellings() throws SQLException {
        String json = checkedJson();
        // clean row (id=1) keeps the empty defects array
        assertTrue(json.contains("\"defects\":[],\"value\":{\"id\":1"),
                "clean row must report zero defects: " + json);
        // the plainly-violating row (id=2) keeps the engine default
        // violation message and full defect shape
        assertTrue(json.contains(
                        "Constraint :[posInt] violated in the Class Edge"),
                "false predicate must keep the violation defect: " + json);
        assertTrue(json.contains(
                "Constraint :[nonEmptyStr] violated in the Class Edge"),
                "false predicate must keep the violation defect: " + json);
        assertTrue(json.contains("\"ruleDefinerPath\":\"test::Edge\"")
                        && json.contains("\"ruleType\":\"ClassConstraint\"")
                        && json.contains("\"enforcementLevel\":\"Error\""),
                "defect shape must stay engine-shaped: " + json);
        // exactly the all-NULL row's one unable-to-evaluate defect
        assertEquals(1, json.split("Unable to evaluate", -1).length - 1,
                "only the NULL row's comparison constraint carries"
                        + " unable-to-evaluate: " + json);
    }
}
