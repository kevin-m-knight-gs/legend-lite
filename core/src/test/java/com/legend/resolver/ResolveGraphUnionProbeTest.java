// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend.resolver;

import com.legend.Compiler;
import com.legend.exec.ExecutionResult;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Graph-over-union probe (Leg 5 / graphFetch union::propertyLevel): a
 * graph tree whose CHILD property is union-mapped — the child computation
 * must stitch by the parent key against the union's member-suffixed pk
 * projections, not a raw physical column (the corpus wall: "filter
 * predicate references column 'ID', unresolvable even after isolation").
 */
class ResolveGraphUnionProbeTest {

    private static final String UNION_FQN =
            "meta::pure::router::operations::union_OperationSetImplementation_1__SetImplementation_MANY_";

    private static final String MODEL = ("""
            Class g::Firm { legalName: String[1]; }
            Class g::Person { lastName: String[1]; }
            Association g::FP { firm: g::Firm[0..1]; employees: g::Person[*]; }
            Database g::DB (
              Table F (ID INTEGER PRIMARY KEY, LEGAL VARCHAR)
              Table P1 (ID INTEGER PRIMARY KEY, NAME VARCHAR, FID INTEGER)
              Table P2 (ID INTEGER PRIMARY KEY, NAME VARCHAR, FID INTEGER)
              Join F_P1 (F.ID = P1.FID)
              Join F_P2 (F.ID = P2.FID)
            )
            Mapping g::M (
              *g::Person : Operation { %s(p1, p2) }
              g::Firm[f1] : Relational { ~mainTable [g::DB] F
                legalName: F.LEGAL,
                employees[f1, p1]: [g::DB] @F_P1,
                employees[f1, p2]: [g::DB] @F_P2 }
              g::Person[p1] : Relational { ~mainTable [g::DB] P1
                lastName: P1.NAME }
              g::Person[p2] : Relational { ~mainTable [g::DB] P2
                lastName: P2.NAME }
            )
            Runtime g::RT { mappings: [g::M]; }
            """).formatted(UNION_FQN);

    private static Connection conn;

    @BeforeAll
    static void setUp() throws SQLException {
        conn = DriverManager.getConnection("jdbc:duckdb:");
        try (Statement st = conn.createStatement()) {
            st.execute("CREATE TABLE F (ID INTEGER, LEGAL VARCHAR)");
            st.execute("CREATE TABLE P1 (ID INTEGER, NAME VARCHAR, FID INTEGER)");
            st.execute("CREATE TABLE P2 (ID INTEGER, NAME VARCHAR, FID INTEGER)");
            st.execute("INSERT INTO F VALUES (1, 'ACME'), (2, 'Globex')");
            st.execute("INSERT INTO P1 VALUES (10, 'Ann', 1)");
            st.execute("INSERT INTO P2 VALUES (20, 'Bob', 1), (21, 'Cid', 2)");
        }
    }

    @AfterAll
    static void tearDown() throws SQLException {
        conn.close();
    }

    @Test
    @DisplayName("graph tree with a union-mapped to-many child")
    void graphOverUnionChild() throws SQLException {
        String query = "g::Firm.all()"
                + "->graphFetch(#{g::Firm{legalName, employees{lastName}}}#)"
                + "->serialize(#{g::Firm{legalName, employees{lastName}}}#)"
                + "->from(g::M, g::RT)";
        ExecutionResult r = Compiler.execute(MODEL, query, "g::RT", conn);
        String json = r instanceof ExecutionResult.Graph g ? g.json()
                : String.valueOf(r);
        System.out.println("[graph-union] " + json);
        assertEquals(true,
                json.contains("\"legalName\":\"ACME\"")
                        && json.contains("Ann") && json.contains("Bob")
                        && json.contains("Cid"),
                json);
    }
}
