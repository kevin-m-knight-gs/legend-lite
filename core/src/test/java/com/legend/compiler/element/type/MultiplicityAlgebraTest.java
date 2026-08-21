// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend.compiler.element.type;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Pins THE multiplicity algebra owner (multiplicity audit
 * docs/MULTIPLICITY_AUDIT_2026_08_20.md §1d: the arithmetic was
 * re-derived at four sites with four different {@code Var} fallbacks;
 * the owner ends the divergence, and these tests pin its semantics —
 * before this slice, zero tests pinned any of the four copies).
 */
class MultiplicityAlgebraTest {

    private static Multiplicity.Bounded b(int lower, Integer upper) {
        return new Multiplicity.Bounded(lower, upper);
    }

    // ---- union (range union — branch joins, shared-var accumulation) ----

    @Test
    void unionIsTheRangeUnion() {
        // the audit §1c reproduction: arms [2] and [1] are [1..2] —
        // NOT the old hardcoded [0..1], which lost the upper bound AND
        // falsely asserted emptiness (a tightening, the dangerous way)
        assertEquals(b(1, 2), Multiplicity.union(b(2, 2), b(1, 1)));
        assertEquals(b(0, 1), Multiplicity.union(b(0, 0), b(1, 1)));
        assertEquals(Multiplicity.Bounded.ZERO_MANY,
                Multiplicity.union(b(0, 0), Multiplicity.Bounded.ZERO_MANY));
        // fold's []-init [0] meets a [*] body at [*] (the kernel's
        // covariant mult-var accumulation routes here)
        assertEquals(b(0, null),
                Multiplicity.union(b(0, 0), b(1, null)));
        assertEquals(b(1, 3), Multiplicity.union(b(1, 3), b(2, 2)));
    }

    @Test
    void unionOfTheSameVariableIsThatVariable() {
        // a generic body's branches both stamped [m] stay [m]
        Multiplicity m = new Multiplicity.Var("m");
        assertEquals(m, Multiplicity.union(m, new Multiplicity.Var("m")));
    }

    @Test
    void unionOfAVariableAndABoundIsLoud() {
        // the four old copies each silently returned a DIFFERENT
        // operand here — position-dependent answers were the bug class
        assertThrows(IllegalStateException.class, () ->
                Multiplicity.union(new Multiplicity.Var("m"), b(1, 1)));
        assertThrows(IllegalStateException.class, () ->
                Multiplicity.union(b(1, 1), new Multiplicity.Var("m")));
        assertThrows(IllegalStateException.class, () ->
                Multiplicity.union(new Multiplicity.Var("m"),
                        new Multiplicity.Var("n")));
    }

    // ---- product (path composition — navigation chains) ----

    @Test
    void productComposesNavigationPaths() {
        assertEquals(b(0, 1), Multiplicity.product(b(0, 1), b(1, 1)));
        assertEquals(b(0, null), Multiplicity.product(b(0, null), b(1, 1)));
        assertEquals(b(0, null), Multiplicity.product(b(1, 1), b(0, null)));
        // an optional hop makes the result optional; a [*] hop makes
        // everything after it [*]
        assertEquals(b(0, 2), Multiplicity.product(b(0, 1), b(2, 2)));
        assertEquals(b(2, 6), Multiplicity.product(b(1, 2), b(2, 3)));
        // [0..0] annihilates
        assertEquals(b(0, 0), Multiplicity.product(b(0, 0), b(1, null)));
    }

    @Test
    void productIdentityKeepsTheVariable() {
        // audit §1e: the old Typer.compose returned the OTHER operand
        // when a side was a Var, silently dropping a variable source's
        // cardinality across inlining ($p.name over p:[n] stamped [1]).
        // [1] is the product's identity — [n].[1] stays [n].
        Multiplicity n = new Multiplicity.Var("n");
        assertEquals(n, Multiplicity.product(n, Multiplicity.Bounded.ONE));
        assertEquals(n, Multiplicity.product(Multiplicity.Bounded.ONE, n));
    }

    @Test
    void productOfAVariableAndANonIdentityBoundIsLoud() {
        assertThrows(IllegalStateException.class, () ->
                Multiplicity.product(new Multiplicity.Var("n"), b(0, 1)));
        assertThrows(IllegalStateException.class, () ->
                Multiplicity.product(b(0, null), new Multiplicity.Var("n")));
    }
}
