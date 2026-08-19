// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend.lowering;

import com.legend.Compiler;
import com.legend.exec.ExecutionResult;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Phase 4 channel-B fixes, pinned as core semantics:
 *
 * <p>1. {@code map} over a TO-ONE/[0..1] source wraps as a singleton
 * list — {@code list_transform} over a bare scalar is a DuckDB BINDER
 * error even inside a dead CASE arm (assertEquals' many-path over a
 * {@code head()} actual walled 12 essential tests); a [0..1] source
 * null-guards (map over EMPTY is EMPTY, never {@code [f(NULL)]}).
 *
 * <p>2. A RIGID type-variable binding whose declared type is an
 * abstract value head (Number, Date) accepts actuals up the m3 lattice
 * and keeps the declared type — {@code eval({a:Number[1]|...}, 1.0)}
 * is the essential-math spec shape (testNumberExp/Log/Pow).
 */
class MapOptionalSourceTest {

    private static Connection conn;

    @BeforeAll
    static void open() throws Exception {
        conn = DriverManager.getConnection("jdbc:duckdb:");
    }

    @AfterAll
    static void close() throws Exception {
        conn.close();
    }

    private static Object run(String query) throws Exception {
        ExecutionResult r = Compiler.execute("", query, conn);
        return r instanceof ExecutionResult.Scalar s ? s.value()
                : r instanceof ExecutionResult.Collection c ? c.values() : r;
    }

    @Test
    @DisplayName("map over a present [0..1] source applies to the one value")
    void mapOverPresentOptional() throws Exception {
        assertEquals("a!", run("{|['a','b']->head()->map(x|$x + '!')}"));
    }

    @Test
    @DisplayName("map over an empty [0..1] source is EMPTY, never f(NULL)")
    void mapOverEmptyOptional() throws Exception {
        assertEquals(null,
                run("{|[]->head()->map(x|'reached')}"));
    }

    @Test
    @DisplayName("map over a to-one source is the mapped one value")
    void mapOverToOne() throws Exception {
        assertEquals(6L, ((Number) run("{|3->toOne()->map(x|$x * 2)}"))
                .longValue());
    }

    @Test
    @DisplayName("rigid Number parameter accepts a Float actual (m3 lattice)")
    void rigidNumberAcceptsFloat() throws Exception {
        assertEquals(2.0, run("{|{a:Number[1]|$a + 1}->eval(1.0)}"));
    }

    // pure equality is COLLECTION equality: [x] IS x — the one-element
    // collection literal meets a to-one scalar literal at the element
    // (CastPolicy.comparisonWireOperand; the match-arm [1] shape)
    @Test
    @DisplayName("equal: a one-element collection literal equals its element")
    void oneElementCollectionEquality() throws Exception {
        assertEquals(true, run("{|equal(1, [1])}"));
        assertEquals(true, run("{|equal([1], 1)}"));
        assertEquals(false, run("{|equal([2], 1)}"));
        assertEquals(true, run("{|equal([1,2], [1,2])}"));
    }

    @Test
    @DisplayName("rigid Number parameter accepts an Integer actual")
    void rigidNumberAcceptsInteger() throws Exception {
        assertEquals(2L, ((Number) run("{|{a:Number[1]|$a + 1}->eval(1)}"))
                .longValue());
    }
}
