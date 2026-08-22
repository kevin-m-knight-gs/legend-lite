// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend.exec;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/**
 * R0 spec pins (docs/CANONICAL_FORM_SPEC.md §2) — each row cites its
 * H-table witness from docs/CANONICAL_RENDER_HOMEWORK.md. These pin the
 * HOST reference render the R1 divergence instrument compares with;
 * R2's SQL renders are held to the same rows.
 */
class CanonicalFormTest {

    private static String text(Object v) {
        return assertInstanceOf(CanonicalForm.Result.Text.class,
                CanonicalForm.render(v)).value();
    }

    private static String residue(Object v) {
        return assertInstanceOf(CanonicalForm.Result.Residue.class,
                CanonicalForm.render(v)).reason();
    }

    @Test
    void integersAndBooleans() {
        assertEquals("1", text(1L));
        assertEquals("-1", text(-1));
        assertEquals("true", text(true));
        assertEquals("false", text(false));
    }

    /** H1 testFloatToString×4: fixed-point always, shortest form,
     * integral keeps .0, leading zero enforced. */
    @Test
    void floatsNeverExponent() {
        assertEquals("3.14", text(3.14));
        assertEquals("17.0", text(17.000));
        assertEquals("134210000.0", text(1.3421e8));
        assertEquals("0.000000013421", text(134.21e-10));
        assertEquals("0.01", text(.01));
    }

    /** Spec §2 Decimal: scale-normalized; integral renders BARE —
     * forced by pure numeric equality (assertEq(8D, toDecimal(8))). */
    @Test
    void decimalsScaleNormalized() {
        assertEquals("8", text(new BigDecimal("8.00")));
        assertEquals("3.8", text(new BigDecimal("3.80")));
        assertEquals("800", text(new BigDecimal("8.00E+2")));
    }

    /** Spec §4: non-finite floats and -0.0 are OUT of the claimed
     * domain — named residue, never a guessed spelling. */
    @Test
    void residues() {
        assertEquals("non-finite-float", residue(Double.NaN));
        assertEquals("non-finite-float", residue(Double.POSITIVE_INFINITY));
        assertEquals("negative-zero", residue(-0.0));
    }

    /** H1/H2 temporals, scalar channel: date bare ISO; DateTime
     * T-separated, UTC-normalized, minimal subseconds, +0000. */
    @Test
    void temporals() {
        assertEquals("2014-01-01", text(LocalDate.of(2014, 1, 1)));
        assertEquals("2014-01-01T00:00:00+0000",
                text(LocalDateTime.of(2014, 1, 1, 0, 0)));
        assertEquals("2014-01-01T10:01:35.231+0000",
                text(LocalDateTime.of(2014, 1, 1, 10, 1, 35, 231_000_000)));
        // GMT normalization: -0500 input prints shifted +0000 (H1)
        assertEquals("2014-01-01T15:01:00+0000",
                text(OffsetDateTime.of(2014, 1, 1, 10, 1, 0, 0,
                        ZoneOffset.ofHours(-5))));
    }

    /** H1 testListToString: multi-element sides take the list form;
     * a single element renders as the scalar. */
    @Test
    void sides() {
        assertEquals("[1, 2, 3]", assertInstanceOf(
                CanonicalForm.Result.Text.class,
                CanonicalForm.renderSide(List.of(1L, 2L, 3L))).value());
        assertEquals("a", assertInstanceOf(
                CanonicalForm.Result.Text.class,
                CanonicalForm.renderSide(List.of((Object) "a"))).value());
        assertEquals("[]", assertInstanceOf(
                CanonicalForm.Result.Text.class,
                CanonicalForm.renderSide(List.of())).value());
    }

    /** The instrument's bridge: a string paired against a temporal
     * canonicalizes through the parse — bridge pairs byte-agree exactly
     * where the lattice's bridge grants equality. */
    @Test
    void divergenceProbeBridge() {
        CanonicalDivergence.reset();
        CanonicalDivergence.probeEqual("assertEquals",
                List.of((Object) "2014-01-01"),
                List.of((Object) LocalDate.of(2014, 1, 1)), true);
        assertEquals(0, CanonicalDivergence.disagreeCount(),
                "string-vs-temporal bridge pair must byte-agree");
        CanonicalDivergence.probeEqual("assertEquals",
                List.of((Object) 1L), List.of((Object) 2L), false);
        assertEquals(0, CanonicalDivergence.disagreeCount());
        assertEquals("agree=2 disagree=0 residue=0",
                CanonicalDivergence.summary());
        CanonicalDivergence.reset();
    }
}
