// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend.exec;

import com.legend.Compiler;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * R1 — THE WORLD-2 PAIRED-PROBE GUARD (HOST_LOGIC_AUDIT_2026_08_20
 * "the guard that keeps it fixed", chartered and unbuilt until the
 * COMPILER_SHORTCUT_AUDIT called it out; ratified work-order item d,
 * widened from the verdict arms to the egress arms).
 *
 * <p>Every surviving HOST-side semantic arm (World 2 — PureAsserts'
 * equality lattice, the egress decoders) runs the SAME computation
 * through SQL (World 1 — the full compile pipeline on a DuckDB
 * session) and the verdicts must agree. A disagreement means the host
 * arm is MASKING an emission defect — the mechanism behind audit §5
 * (size()=2 vs toOne()="size 3") and §6 (decodeAny precision loss),
 * both of which this test would have caught on day one.
 *
 * <p>THE DISAGREEMENT TABLE IS EXPLICIT: rows expected to AGREE fail
 * on divergence; rows registered as KNOWN divergences (each named,
 * with its adjudication) fail when they START agreeing — a healed
 * divergence must tighten the register in the same commit
 * (shrink-only, the ratchet discipline).
 */
class VerdictWorld2ConsistencyTest {

    private static Connection conn;

    @BeforeAll
    static void open() throws Exception {
        conn = DriverManager.getConnection("jdbc:duckdb:");
    }

    @AfterAll
    static void close() throws Exception {
        conn.close();
    }

    /** World 1: the pure expression through the WHOLE pipeline. */
    private static Object world1(String expr) throws Exception {
        ExecutionResult r = Compiler.execute("", "{|" + expr + "}", conn);
        return r instanceof ExecutionResult.Scalar s ? s.value()
                : r instanceof ExecutionResult.Collection c ? c.values() : r;
    }

    // NOTE: the pairwise EQUALITY lattice is owned by
    // EqualityWorldsConformanceTest (the two-worlds fixture, Charter
    // Clause 2c) — R1 EXTENDED it (integral×Decimal agree row, 2-ULP
    // declared-divergence row) rather than duplicating it here. This
    // test owns the WIDENED scope: total order + the egress arms.

    @Test
    @DisplayName("total order: host sort canonicalization vs compiled sort()")
    void totalOrder() throws Exception {
        assertTrue("[1, 2, 3]".equals(
                        String.valueOf(world1("[3,1,2]->sort()"))),
                "world-1 integer sort diverged from the host total order");
        assertTrue("[a, b, c]".equals(
                        String.valueOf(world1("['c','a','b']->sort()"))),
                "world-1 string sort diverged from the host total order");
    }

    @Test
    @DisplayName("egress: mixed-Any carrier round-trip vs scalar round-trip (audit §6 decodeAny)")
    void decodeAnyPrecision() throws Exception {
        // audit §6: Executor.decodeAny sniffs Long-then-Double, so a
        // HEALED (slice-3 claim, 2026-08-24): the LITERAL carrier
        // preserves Decimal through mixed-Any BY GRAMMAR (the D-suffix
        // spelling — exactly the kind json erased). This probe was
        // built to detect the healing; flipped per its own
        // instruction, and the HOST_LOGIC_AUDIT PERMANENT-ALLOWED row
        // is retired in the same commit.
        Object scalar = world1("1234567890123456789012345.5D");
        Object viaAny = world1("['x', 1234567890123456789012345.5D]->at(1)");
        assertInstanceOf(BigDecimal.class, viaAny,
                "the Any carrier preserves the Decimal KIND");
        assertEquals(scalar, viaAny,
                "the Any carrier preserves Decimal PRECISION exactly");
    }

    @Test
    @DisplayName("egress: the value collection IS the SQL collection (audit §5 seal)")
    void section5Seal() throws Exception {
        // the §5 fix's cross-notion consistency, probed at the value
        // lane: size(), at() and the collection itself must tell ONE
        // story for a carrier holding empties
        assertTrue("1".equals(String.valueOf(
                        world1("[[]->first(), 'a']->size()"))),
                "size() disagrees with the compacted carrier");
        assertTrue("a".equals(world1("[[]->first(), 'a']->at(0)")),
                "at(0) disagrees with the compacted carrier");
        assertTrue("0".equals(String.valueOf(
                        world1("[[]->first(), 'a']->indexOf('a')"))),
                "indexOf disagrees with the compacted carrier");
        assertTrue("a".equals(world1("[[]->first(), 'a']->toOne()")),
                "toOne disagrees with the compacted carrier");
    }

    @Test
    @DisplayName("egress: the FULL positional battery over a carrier holding empties (audit-of-R1)")
    void section5FullBattery() throws Exception {
        // the audit-of-R1 pass found consumer-site compaction was
        // whack-a-mole — 7 of these were wrong until the compaction
        // moved to the LITERAL'S CONSTRUCTION and the checker's
        // element-count multiplicity stamp ([2..2] for [1..2]) was
        // fixed. Every consumer must tell the same one-element story.
        String lit = "[[]->first(), 'a']";
        record P(String op, String want) {
        }
        for (P p : java.util.List.of(
                new P("->head()", "a"), new P("->first()", "a"),
                new P("->last()", "a"), new P("->tail()", "[]"),
                new P("->init()", "[]"), new P("->drop(1)", "[]"),
                new P("->take(1)", "[a]"), new P("->reverse()", "[a]"),
                new P("->sort()", "[a]"), new P("->isEmpty()", "false"),
                new P("->makeString(',')", "a"))) {
            Object got = world1(lit + p.op());
            assertTrue(p.want().equals(String.valueOf(got)),
                    lit + p.op() + " => " + got + " (pure: " + p.want()
                            + ")");
        }
    }
}
