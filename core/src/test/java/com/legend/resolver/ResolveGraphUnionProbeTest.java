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
            Class g::Address { name: String[1]; }
            Association g::FP { firm: g::Firm[0..1]; employees: g::Person[*]; }
            Association g::PA { resident: g::Person[0..1]; address: g::Address[0..1]; }
            Database g::DB (
              Table F1 (ID INTEGER PRIMARY KEY, LEGAL VARCHAR)
              Table F2 (ID INTEGER PRIMARY KEY, LEGAL VARCHAR)
              Table P1 (ID INTEGER PRIMARY KEY, NAME VARCHAR, FID INTEGER, AID INTEGER)
              Table P2 (ID INTEGER PRIMARY KEY, NAME VARCHAR, FID INTEGER, AID INTEGER)
              Table A1 (ID INTEGER PRIMARY KEY, NAME VARCHAR)
              Table A2 (ID INTEGER PRIMARY KEY, NAME VARCHAR)
              Join F1_P1 (F1.ID = P1.FID)
              Join F1_P2 (F1.ID = P2.FID)
              Join F2_P1 (F2.ID = P1.FID)
              Join F2_P2 (F2.ID = P2.FID)
              Join P1_A1 (P1.AID = A1.ID)
              Join P1_A2 (P1.AID = A2.ID)
              Join P2_A1 (P2.AID = A1.ID)
              Join P2_A2 (P2.AID = A2.ID)
            )
            Mapping g::M (
              *g::Firm : Operation { %s(f1, f2) }
              *g::Person : Operation { %s(p1, p2) }
              *g::Address : Operation { %s(a1, a2) }
              g::Firm[f1] : Relational { ~mainTable [g::DB] F1
                legalName: F1.LEGAL,
                employees[p1]: [g::DB] @F1_P1,
                employees[p2]: [g::DB] @F1_P2 }
              g::Firm[f2] : Relational { ~mainTable [g::DB] F2
                legalName: F2.LEGAL,
                employees[p1]: [g::DB] @F2_P1,
                employees[p2]: [g::DB] @F2_P2 }
              g::Person[p1] : Relational { ~mainTable [g::DB] P1
                lastName: P1.NAME,
                address[a1]: [g::DB] @P1_A1,
                address[a2]: [g::DB] @P1_A2 }
              g::Person[p2] : Relational { ~mainTable [g::DB] P2
                lastName: P2.NAME,
                address[a1]: [g::DB] @P2_A1,
                address[a2]: [g::DB] @P2_A2 }
              g::Address[a1] : Relational { ~mainTable [g::DB] A1 name: A1.NAME }
              g::Address[a2] : Relational { ~mainTable [g::DB] A2 name: A2.NAME }
            )
            Runtime g::RT { mappings: [g::M]; }
            """).formatted(UNION_FQN, UNION_FQN, UNION_FQN);

    private static Connection conn;

    @BeforeAll
    static void setUp() throws SQLException {
        conn = DriverManager.getConnection("jdbc:duckdb:");
        try (Statement st = conn.createStatement()) {
            st.execute("CREATE TABLE F1 (ID INTEGER, LEGAL VARCHAR)");
            st.execute("CREATE TABLE F2 (ID INTEGER, LEGAL VARCHAR)");
            st.execute("CREATE TABLE P1 (ID INTEGER, NAME VARCHAR, FID INTEGER, AID INTEGER)");
            st.execute("CREATE TABLE P2 (ID INTEGER, NAME VARCHAR, FID INTEGER, AID INTEGER)");
            st.execute("CREATE TABLE A1 (ID INTEGER, NAME VARCHAR)");
            st.execute("CREATE TABLE A2 (ID INTEGER, NAME VARCHAR)");
            st.execute("INSERT INTO F1 VALUES (1, 'ACME')");
            st.execute("INSERT INTO F2 VALUES (2, 'Globex')");
            st.execute("INSERT INTO P1 VALUES (10, 'Ann', 1, 100)");
            st.execute("INSERT INTO P2 VALUES (20, 'Bob', 1, 200), (21, 'Cid', 2, 100)");
            st.execute("INSERT INTO A1 VALUES (100, 'New York')");
            st.execute("INSERT INTO A2 VALUES (200, 'Hoboken')");
        }
    }

    @AfterAll
    static void tearDown() throws SQLException {
        conn.close();
    }

    private static final String MODEL_ROOT_ONLY = ("""
            Class g::Firm { legalName: String[1]; }
            Class g::Person { lastName: String[1]; }
            Association g::FP { firm: g::Firm[0..1]; employees: g::Person[*]; }
            Database g::DB (
              Table F1 (ID INTEGER PRIMARY KEY, LEGAL VARCHAR)
              Table F2 (ID INTEGER PRIMARY KEY, LEGAL VARCHAR)
              Table P1 (ID INTEGER PRIMARY KEY, NAME VARCHAR, FID INTEGER)
              Join F1_P1 (F1.ID = P1.FID)
              Join F2_P1 (F2.ID = P1.FID)
            )
            Mapping g::M2 (
              *g::Firm : Operation { %s(f1, f2) }
              g::Firm[f1] : Relational { ~mainTable [g::DB] F1
                legalName: F1.LEGAL,
                employees[p1]: [g::DB] @F1_P1 }
              g::Firm[f2] : Relational { ~mainTable [g::DB] F2
                legalName: F2.LEGAL,
                employees[p1]: [g::DB] @F2_P1 }
              g::Person[p1] : Relational { ~mainTable [g::DB] P1
                lastName: P1.NAME }
            )
            Runtime g::RT2 { mappings: [g::M2]; }
            """).formatted(UNION_FQN);

    private static final String MODEL_DIAGONAL = ("""
            Class g::Trade { tradeId: Integer[1]; }
            Class g::Product { productName: String[1]; }
            Association g::TP { trade: g::Trade[0..1]; product: g::Product[0..1]; }
            Database g::DB3 (
              Table T1 (tradeId INTEGER PRIMARY KEY, productId VARCHAR)
              Table T2 (tradeId INTEGER PRIMARY KEY, productId VARCHAR)
              Table PR1 (productId VARCHAR PRIMARY KEY, NAME VARCHAR)
              Table PR2 (productId VARCHAR PRIMARY KEY, NAME VARCHAR)
              Join trade_product (PR1.productId = T1.productId)
              Join trade2_product2 (PR2.productId = T2.productId)
            )
            Mapping g::M3 (
              *g::Trade : Operation { %s(t1, t2) }
              *g::Product : Operation { %s(p1, p2) }
              g::Trade[t1] : Relational { ~mainTable [g::DB3] T1
                tradeId: T1.tradeId,
                product[p1]: [g::DB3] @trade_product }
              g::Trade[t2] : Relational { ~mainTable [g::DB3] T2
                tradeId: T2.tradeId,
                product[p2]: [g::DB3] @trade2_product2 }
              g::Product[p1] : Relational { ~mainTable [g::DB3] PR1
                productName: PR1.NAME }
              g::Product[p2] : Relational { ~mainTable [g::DB3] PR2
                productName: PR2.NAME }
            )
            Runtime g::RT3 { mappings: [g::M3]; }
            """).formatted(UNION_FQN, UNION_FQN);

    @Test
    @DisplayName("DIAGONAL union routes: member pairing yields NULL, never a cross-member match")
    void diagonalUnionPairing() throws SQLException {
        try (Statement st = conn.createStatement()) {
            st.execute("CREATE TABLE T1 (tradeId INTEGER, productId VARCHAR)");
            st.execute("CREATE TABLE T2 (tradeId INTEGER, productId VARCHAR)");
            st.execute("CREATE TABLE PR1 (productId VARCHAR, NAME VARCHAR)");
            st.execute("CREATE TABLE PR2 (productId VARCHAR, NAME VARCHAR)");
            // TRAPS both directions (corpus SameStore data shape: same
            // column NAME on both sides, VARCHAR keys, target-first join
            // spelling): trade 5 (member 1, product 40 ONLY in PR2) and
            // trade 3 (member 2, product 30 ONLY in PR1) must both be NULL
            st.execute("INSERT INTO T1 VALUES (1, '30'), (5, '40')");
            st.execute("INSERT INTO T2 VALUES (2, '31'), (3, '30')");
            st.execute("INSERT INTO PR1 VALUES ('30', 'Prod_1')");
            st.execute("INSERT INTO PR2 VALUES ('31', 'Prod_2'), ('40', 'Prod_3')");
        }
        String query = "g::Trade.all()"
                + "->graphFetch(#{g::Trade{tradeId, product{productName}}}#)"
                + "->serialize(#{g::Trade{tradeId, product{productName}}}#)"
                + "->from(g::M3, g::RT3)";
        ExecutionResult r = Compiler.execute(MODEL_DIAGONAL, query, "g::RT3", conn);
        String json = r instanceof ExecutionResult.Graph g ? g.json()
                : String.valueOf(r);
        System.out.println("[graph-diagonal] " + json);
        assertEquals("[{\"tradeId\":1,\"product\":{\"productName\":\"Prod_1\"}},"
                + "{\"tradeId\":5,\"product\":null},"
                + "{\"tradeId\":2,\"product\":{\"productName\":\"Prod_2\"}},"
                + "{\"tradeId\":3,\"product\":null}]", json);
    }

    @Test
    @DisplayName("graph tree from a UNION ROOT into a single-set child")
    void graphUnionRootOnly() throws SQLException {
        String query = "g::Firm.all()"
                + "->graphFetch(#{g::Firm{legalName, employees{lastName}}}#)"
                + "->serialize(#{g::Firm{legalName, employees{lastName}}}#)"
                + "->from(g::M2, g::RT2)";
        ExecutionResult r = Compiler.execute(MODEL_ROOT_ONLY, query, "g::RT2", conn);
        String json = r instanceof ExecutionResult.Graph g ? g.json()
                : String.valueOf(r);
        System.out.println("[graph-union-root] " + json);
        assertEquals(true, json.contains("ACME") && json.contains("Ann"), json);
    }

    @Test
    @DisplayName("graph tree with a union-mapped to-many child")
    void graphOverUnionChild() throws SQLException {
        String query = "g::Firm.all()"
                + "->graphFetch(#{g::Firm{legalName, employees{lastName,"
                + " address{name}}}}#)"
                + "->serialize(#{g::Firm{legalName, employees{lastName,"
                + " address{name}}}}#)"
                + "->from(g::M, g::RT)";
        ExecutionResult r = Compiler.execute(MODEL, query, "g::RT", conn);
        String json = r instanceof ExecutionResult.Graph g ? g.json()
                : String.valueOf(r);
        System.out.println("[graph-union] " + json);
        assertEquals(true,
                json.contains("\"legalName\":\"ACME\"")
                        && json.contains("Ann") && json.contains("Bob")
                        && json.contains("Cid")
                        && json.contains("New York") && json.contains("Hoboken"),
                json);
    }
}
