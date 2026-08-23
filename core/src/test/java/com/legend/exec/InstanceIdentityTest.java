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
import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * F13 — SYNTHETIC INSTANCE IDENTITY (OPEN_REGISTER): a KEYLESS class
 * (no {@code <<equality.Key>>} tree) has ENGINE equality = instance
 * identity. Identity travels as DATA: the verdict lane's identity
 * layout adds a {@code __id} field minted deterministically per
 * CONSTRUCTION SITE (node), never per evaluation — a let-bound
 * instance equals itself, two textually distinct constructors never
 * equal, a copy is a NEW instance. Both channels carry the same rule:
 * the byte canon renders {@code {_type,_id}}, the host lattice
 * compares wire maps that include the id (content fabrication dead).
 */
class InstanceIdentityTest {

    private static final String MODEL = """
            Class m::P
            {
              name: String[1];
            }
            Class m::K
            {
              <<equality.Key>> id: Integer[1];
              other: String[1];
            }
            function meta::pure::functions::asserts::assertEquals(expected:Any[*], actual:Any[*]):Boolean[1]
            {
                assert(equal($expected, $actual), 'assert failed');
            }
            function meta::pure::functions::asserts::assertNotEquals(expected:Any[*], actual:Any[*]):Boolean[1]
            {
                assert(!equal($expected, $actual), 'assertNotEquals: both sides are equal');
            }
            function meta::pure::functions::asserts::assertEq(expected:Any[1], actual:Any[1]):Boolean[1]
            {
                assert(eq($expected, $actual), 'assert failed');
            }
            function meta::pure::functions::asserts::assert(condition:Boolean[1], message:String[1]):Boolean[1]
            {
                assert($condition, | $message);
            }
            """;

    private static Connection conn;

    @BeforeAll
    static void setUp() throws SQLException {
        conn = DriverManager.getConnection("jdbc:duckdb:");
    }

    @AfterAll
    static void tearDown() throws SQLException {
        conn.close();
    }

    private static ExecutionResult run(String query) throws SQLException {
        return Compiler.execute(MODEL, query, conn);
    }

    @Test
    @DisplayName("a let-bound keyless instance equals ITSELF (identity)")
    void sameInstanceEqual() throws Exception {
        assertEquals(Boolean.TRUE, ((ExecutionResult.Scalar) run(
                "{|let p = ^m::P(name='a'); assertEquals($p, $p);}"))
                .value());
    }

    @Test
    @DisplayName("two constructor sites are DISTINCT instances even when"
            + " content-equal (the engine-FALSE the content fabrication hid)")
    void distinctSitesNotEqual() throws Exception {
        assertEquals(Boolean.TRUE, ((ExecutionResult.Scalar) run(
                "{|assertNotEquals(^m::P(name='a'), ^m::P(name='a'))}"))
                .value());
        assertEquals(Boolean.TRUE, ((ExecutionResult.Scalar) run(
                "{|let a = ^m::P(name='a'); let b = ^m::P(name='a');"
                        + " assertNotEquals($a, $b);}")).value());
    }

    @Test
    @DisplayName("a COPY is a NEW instance — its identity mints at the"
            + " copy site")
    void copyMintsNewIdentity() throws Exception {
        assertEquals(Boolean.TRUE, ((ExecutionResult.Scalar) run(
                "{|let p = ^m::P(name='a'); let q = ^$p(name='a');"
                        + " assertNotEquals($p, $q);}")).value());
    }

    @Test
    @DisplayName("eq() over keyless instances: identity is observable now"
            + " — the wire carries __id")
    void eqIdentity() throws Exception {
        assertEquals(Boolean.TRUE, ((ExecutionResult.Scalar) run(
                "{|let p = ^m::P(name='a'); assertEq($p, $p);}")).value());
        Exception e = assertThrows(Exception.class, () -> run(
                "{|let a = ^m::P(name='a'); let b = ^m::P(name='a');"
                        + " assertEq($a, $b);}"));
        assertTrue(e.getMessage().contains("distinct instances"),
                e.getMessage());
    }

    @Test
    @DisplayName("KEYED classes are untouched: key-property equality,"
            + " no identity field")
    void keyedClassesKeyEquality() throws Exception {
        // same key, different non-key content: equal (engine rule)
        assertEquals(Boolean.TRUE, ((ExecutionResult.Scalar) run(
                "{|assertEquals(^m::K(id=1, other='x'),"
                        + " ^m::K(id=1, other='y'))}")).value());
        // the failing keyed compare fails LOUDLY (the byte verdict is
        // FALSE; the failure-message repr of instance maps is its own
        // pre-existing loud wall — either way, never a silent pass)
        Exception e = assertThrows(Exception.class, () -> run(
                "{|assertEquals(^m::K(id=1, other='x'),"
                        + " ^m::K(id=2, other='x'))}"));
        assertNotNull(e.getMessage());
    }

    @Test
    @DisplayName("v1 exclusion: a keyless ctor under a LAMBDA declines the"
            + " byte verdict (counted), host referee still judges")
    void lambdaCtorDeclines() throws Exception {
        long before = CanonicalDivergence.sqlDeclinedCount();
        assertEquals(Boolean.TRUE, ((ExecutionResult.Scalar) run(
                "{|let l = [1,2]->map(x|^m::P(name=$x->toString()));"
                        + " assertEquals($l, $l);}")).value());
        assertTrue(CanonicalDivergence.sqlDeclinedCount() > before,
                "the lambda-minted ctor pair must DECLINE the byte"
                        + " verdict, never claim it");
    }

    @Test
    @DisplayName("plain (rider-less) value reads keep the PLAIN layout —"
            + " no __id leaks into ordinary wire maps")
    void plainLaneUnperturbed() throws Exception {
        Object v = ((ExecutionResult.Scalar) run(
                "{|^m::P(name='a')}")).value();
        assertTrue(v instanceof java.util.Map<?, ?> m
                        && !m.containsKey("__id")
                        && "a".equals(m.get("name")),
                "plain-lane instance wire changed: " + v);
    }
}
