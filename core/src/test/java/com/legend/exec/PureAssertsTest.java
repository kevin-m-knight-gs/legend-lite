// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend.exec;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * THE SPEC'S OWN CASES as pins (One-Platform Plan Phase 2a; the
 * port-engine-tests-as-spec rule): success cases and EXACT failure
 * messages from {@code platform/pure/essential/tests/assertEquals.pure}
 * / {@code assertSameElements.pure} / {@code assertSize.pure}, plus the
 * adjudicated wire policies the platform owner carries.
 */
class PureAssertsTest {

    // ---- assertEquals.pure's own tests --------------------------------

    @Test
    @DisplayName("spec: testSuccessAssertEquals + WithCollections")
    void specSuccess() {
        assertNull(PureAsserts.assertEquals(List.of(1L), List.of(1L)));
        assertNull(PureAsserts.assertEquals(List.of("aaa"), List.of("aaa")));
        assertNull(PureAsserts.assertEquals(List.of(1L, 2L), List.of(1L, 2L)));
        assertNull(PureAsserts.assertEquals(
                List.of("aaa", 2L), List.of("aaa", 2L)));
    }

    @Test
    @DisplayName("spec: testFailureAssertEquals — the exact message")
    void specFailureMessage() {
        assertEquals("\nexpected: 1\nactual:   2",
                PureAsserts.assertEquals(List.of(1L), List.of(2L)));
    }

    @Test
    @DisplayName("spec: testFailureAssertEqualsWithCollections — exact messages")
    void specFailureCollections() {
        assertEquals("\nexpected: [1, 3, 2]\nactual:   [2, 4, 1, 5]",
                PureAsserts.assertEquals(List.of(1L, 3L, 2L),
                        List.of(2L, 4L, 1L, 5L)));
        assertEquals("\nexpected: [1, 2]\nactual:   [2, 1]",
                PureAsserts.assertEquals(List.of(1L, 2L), List.of(2L, 1L)));
        assertEquals("\nexpected: ['aaa', 2]\nactual:   [2, 'aaa']",
                PureAsserts.assertEquals(List.of("aaa", 2L),
                        List.of(2L, "aaa")));
    }

    // ---- assertSameElements.pure's own tests --------------------------

    @Test
    @DisplayName("spec: testSuccessAssertSameElements")
    void sameElementsSuccess() {
        assertNull(PureAsserts.assertSameElements(
                List.of(1L, 2L), List.of(2L, 1L)));
        assertNull(PureAsserts.assertSameElements(
                List.of("aaa", 2L), List.of(2L, "aaa")));
    }

    @Test
    @DisplayName("spec: testFailureAssertSameElements — SORTED renders, number-before-string")
    void sameElementsFailureMessage() {
        assertEquals("\nexpected: [1, 2, 3]\nactual:   [1, 2, 4, 5]",
                PureAsserts.assertSameElements(List.of(1L, 3L, 2L),
                        List.of(2L, 4L, 1L, 5L)));
        assertEquals("\nexpected: [1, 3, '2']\nactual:   [1, 4, 5, '2']",
                PureAsserts.assertSameElements(List.of(1L, 3L, "2"),
                        List.of("2", 4L, 1L, 5L)));
    }

    // ---- assertSize.pure ----------------------------------------------

    @Test
    @DisplayName("spec: assertSize message form")
    void sizeMessage() {
        assertNull(PureAsserts.assertSize(List.of(1L, 2L), 2));
        assertEquals("expected size: 3, actual size: 2",
                PureAsserts.assertSize(List.of(1L, 2L), 3));
    }

    // ---- the adjudicated wire policies (documented divergences) -------

    @Test
    @DisplayName("policy: TDSNull sentinel is expected-direction ONLY")
    void tdsNullSentinel() {
        assertTrue(PureAsserts.equalScalar("TDSNull", null));
        assertFalse(PureAsserts.equalScalar(null, "TDSNull"),
                "a literal 'TDSNull' on OUR wire where a NULL belongs"
                        + " must FAIL (audit 16 F5)");
    }

    @Test
    @DisplayName("policy: integral kinds normalize; cross-kind is false")
    void integralNormalization() {
        assertTrue(PureAsserts.equalScalar(1L, 1));
        assertTrue(PureAsserts.equalScalar((short) 5, 5L));
        assertFalse(PureAsserts.equalScalar(1L, 1.0d),
                "integral vs float is CROSS-KIND — strict false");
        assertFalse(PureAsserts.equalScalar(1L, "1"));
    }

    @Test
    @DisplayName("policy: Decimal by compareTo (scale-blind); 2-ULP doubles only")
    void numericPolicies() {
        assertTrue(PureAsserts.equalScalar(
                new BigDecimal("1.50"), new BigDecimal("1.5")));
        double base = 0.1 + 0.2;   // 0.30000000000000004
        assertTrue(PureAsserts.equalScalar(base, 0.3 + Math.ulp(0.3)),
                "within 2 ULP compares equal (dialect libm)");
        assertFalse(PureAsserts.equalScalar(0.3d, 0.4d));
        assertFalse(PureAsserts.equalScalar(Double.NaN, Double.NaN),
                "NaN stays strict — never lenient");
    }

    @Test
    @DisplayName("policy: temporal bridge is expected-string direction only")
    void temporalBridge() {
        assertTrue(PureAsserts.equalScalar("2014-01-01",
                java.time.LocalDate.of(2014, 1, 1)));
        assertTrue(PureAsserts.equalScalar("2014-01-01T00:00:00Z",
                java.time.LocalDateTime.of(2014, 1, 1, 0, 0)));
        assertFalse(PureAsserts.equalScalar(
                java.time.LocalDate.of(2014, 1, 1), "2014-01-01"),
                "actual-side string where a Date belongs is a typing bug"
                        + " — never bridged");
    }

    // ---- toRepresentation (the one owner) -----------------------------

    @Test
    @DisplayName("toRepresentation: spec spellings (escape, %-dates, D-suffix)")
    void representation() {
        assertEquals("'a\\'b'", PureAsserts.repr("a'b"));
        assertEquals("'a\\nb'", PureAsserts.repr("a\nb"));
        assertEquals("%2014-01-01",
                PureAsserts.repr(java.time.LocalDate.of(2014, 1, 1)));
        assertEquals("3.14D", PureAsserts.repr(new BigDecimal("3.14")));
        assertEquals("1", PureAsserts.repr(1L));
        assertEquals("true", PureAsserts.repr(Boolean.TRUE));
    }

    @Test
    @DisplayName("equal() over collections: ordered, arity-strict, null-elements")
    void collectionEquality() {
        assertTrue(PureAsserts.equal(List.of(1L, "a"), List.of(1L, "a")));
        assertFalse(PureAsserts.equal(List.of(1L, 2L), List.of(2L, 1L)),
                "pure equal() is ORDERED");
        assertFalse(PureAsserts.equal(List.of(1L), List.of(1L, 1L)));
        assertTrue(PureAsserts.equal(Arrays.asList((Object) null),
                Arrays.asList((Object) null)));
    }
}
