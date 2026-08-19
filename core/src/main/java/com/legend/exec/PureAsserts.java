// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend.exec;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * THE PLATFORM ASSERT FAMILY (One-Platform Plan Phase 2a, Clause 2b —
 * legend-pure's assert files are the SPEC, this Java is the platform's
 * definition): {@code assertEquals} = {@code assert(equal(e, a), msg)};
 * {@code assertSameElements(e, a)} = {@code assertEquals(sort(e),
 * sort(a))}; {@code assertSize(c, n)} = {@code assertEq(n,
 * c->size())} — read from
 * {@code platform/pure/essential/tests/*.pure} verbatim. Failure
 * messages use the spec's own {@code toRepresentation} +
 * {@code joinStrings} forms.
 *
 * <p>TWO layers, deliberately separated in this one owner:
 * <ol>
 * <li><b>SPEC CORE</b> — pure {@code equal()} over wire values
 * (ordered collection equality; integral kinds normalize, Decimal by
 * {@code compareTo}: pure numeric equality is by VALUE within kind),
 * {@code toRepresentation}, the message formats.</li>
 * <li><b>ADJUDICATED WIRE POLICIES</b> (moved from the harness's
 * {@code wireEquals} at its deletion — the policies survive, the
 * private copy dies): the {@code TDSNull} sentinel (an expected
 * {@code 'TDSNull'} equals an actual NULL cell — never a genuine
 * payload); the 2-ULP dialect-arithmetic leniency (corpus float
 * expectations encode H2's libm; DuckDB differs in the last ULP —
 * DOUBLE-vs-DOUBLE only, exact kinds stay strict); the temporal
 * Any-carrier bridge (a mixed-collection literal's date decodes as its
 * JSON string — parse-and-compare, EXPECTED-string direction only: an
 * actual-side string where the engine returns a Date is a typing bug
 * this compare must catch, never bridge).</li>
 * </ol>
 */
public final class PureAsserts {

    private PureAsserts() {
    }

    // ================================================================
    // The assert family (spec semantics; null = holds, else the spec's
    // failure message)
    // ================================================================

    /** {@code assertEquals(expected:Any[*], actual:Any[*])}
     * (assertEquals.pure:17): {@code assert(equal(e, a), msg)} with the
     * spec's own message — {@code %r} single values, represented-and-
     * joined collections. */
    public static @com.legend.Nullable String assertEquals(
            List<Object> expected, List<Object> actual) {
        if (equal(expected, actual)) {
            return null;
        }
        return "\nexpected: " + reprSide(expected)
                + "\nactual:   " + reprSide(actual);
    }

    /** {@code assertSameElements(expected, actual)}
     * (assertSameElements.pure:17): {@code assertEquals(e->sort(),
     * a->sort())} — the multiset rule IS sort-then-ordered-equal. */
    public static @com.legend.Nullable String assertSameElements(
            List<Object> expected, List<Object> actual) {
        List<Object> es = sorted(expected);
        List<Object> as = sorted(actual);
        if (equal(es, as)) {
            return null;
        }
        return "\nexpected: " + joined(es) + "\nactual:   " + joined(as);
    }

    /** {@code assertSize(collection, size)} (assertSize.pure:17). */
    public static @com.legend.Nullable String assertSize(
            List<Object> collection, long size) {
        if (collection.size() == size) {
            return null;
        }
        return "expected size: " + size + ", actual size: "
                + collection.size();
    }

    /** {@code assertEqWithinTolerance(expected, actual, delta)}
     * (assertEqWithinTolerance.pure): {@code abs(e - a) <= abs(delta)},
     * message in the spec's {@code %r} form. */
    public static @com.legend.Nullable String assertEqWithinTolerance(
            Number expected, Number actual, Number delta) {
        if (Math.abs(expected.doubleValue() - actual.doubleValue())
                <= Math.abs(delta.doubleValue())) {
            return null;
        }
        return "\nexpected: " + repr(expected)
                + "\nactual:   " + repr(actual);
    }

    // ================================================================
    // equal() — pure's equality over wire values (ONE owner)
    // ================================================================

    /** Pure {@code equal(left:Any[*], right:Any[*])} (equal.pure):
     * collection equality is ordered, element-wise. */
    public static boolean equal(List<Object> left, List<Object> right) {
        if (left.size() != right.size()) {
            return false;
        }
        for (int i = 0; i < left.size(); i++) {
            if (!equalScalar(left.get(i), right.get(i))) {
                return false;
            }
        }
        return true;
    }

    /** Element equality: the spec core plus the adjudicated wire
     * policies (class doc). The EXPECTED side is the corpus's literal;
     * the ACTUAL side is the platform's produced value — the sentinel
     * and the temporal bridge are direction-aware for exactly that
     * reason. */
    public static boolean equalScalar(@com.legend.Nullable Object e,
            @com.legend.Nullable Object a) {
        // POLICY: the TDSNull wire sentinel (expected-direction only —
        // a literal 'TDSNull' on OUR wire where a NULL belongs must
        // fail, the symmetric grant would mask it; audit 16 F5)
        if ("TDSNull".equals(e) && a == null) {
            return true;
        }
        if (e == null || a == null) {
            return e == a;
        }
        boolean eInt = isIntegral(e);
        boolean aInt = isIntegral(a);
        if (eInt || aInt) {
            // SPEC: integral kinds compare by value; cross-kind
            // (integral vs anything else) is FALSE
            return eInt && aInt
                    && ((Number) e).longValue() == ((Number) a).longValue();
        }
        if (e instanceof BigDecimal be && a instanceof BigDecimal ba) {
            // SPEC: pure Decimal equality is numeric (scale-blind)
            return be.compareTo(ba) == 0;
        }
        boolean eFp = e instanceof Double || e instanceof Float
                || e instanceof BigDecimal;
        boolean aFp = a instanceof Double || a instanceof Float
                || a instanceof BigDecimal;
        if (eFp || aFp) {
            if (!(eFp && aFp)) {
                return false;
            }
            // NON-FINITE first (latent in the harness copy, exposed by
            // the spec pins): NaN never equals anything (IEEE + pure);
            // infinities compare by identity — BigDecimal can parse
            // neither
            if (nonFinite(e) || nonFinite(a)) {
                return e instanceof Double de2 && a instanceof Double da2
                        ? de2.doubleValue() == da2.doubleValue()
                        : e.equals(a);
            }
            if (new BigDecimal(String.valueOf(e))
                    .compareTo(new BigDecimal(String.valueOf(a))) == 0) {
                return true;
            }
            // POLICY: 2-ULP dialect-arithmetic leniency — DOUBLE vs
            // DOUBLE only; NaN, exact zero, and Decimal stay strict
            if (e instanceof Double de && a instanceof Double da
                    && !de.isNaN() && !da.isNaN()) {
                double ulp = Math.ulp(Math.max(Math.abs(de), Math.abs(da)));
                boolean ok = Math.abs(de - da) <= 2 * ulp;
                // audit 23 D2 measurement instrument (rides the policy)
                if (ok && de.doubleValue() != da.doubleValue()
                        && System.getenv("LL_TOL_COUNT") != null) {
                    System.err.println("[tol] ulp " + de + " vs " + da);
                }
                return ok;
            }
            return false;
        }
        // POLICY: temporal Any-carrier bridge (expected-string vs
        // actual-temporal ONLY)
        if (e instanceof String es && isTemporal(a)) {
            return temporalEquals(es, a);
        }
        if (e instanceof Map<?, ?> em && a instanceof Map<?, ?> am) {
            if (!em.keySet().equals(am.keySet())) {
                return false;
            }
            for (Object k : em.keySet()) {
                if (!equalScalar(em.get(k), am.get(k))) {
                    return false;
                }
            }
            return true;
        }
        return e.equals(a);
    }

    private static boolean nonFinite(Object v) {
        return (v instanceof Double d && !Double.isFinite(d))
                || (v instanceof Float f && !Float.isFinite(f));
    }

    private static boolean isIntegral(Object v) {
        return v instanceof Long || v instanceof Integer
                || v instanceof Short || v instanceof Byte
                || v instanceof BigInteger;
    }

    private static boolean isTemporal(Object v) {
        return v instanceof java.sql.Timestamp || v instanceof java.sql.Date
                || v instanceof java.time.LocalDate
                || v instanceof java.time.LocalDateTime
                || v instanceof java.time.OffsetDateTime;
    }

    private static boolean temporalEquals(String s, Object t) {
        // wire temporals are NAIVE (UTC-normalized); strip a UTC-zero
        // offset/zone suffix from the string form
        String v = s.trim().replaceFirst("(Z|\\+00(:?00)?|\\+0000)$", "")
                .replace('T', ' ').trim();
        try {
            if (t instanceof java.sql.Date d) {
                return java.time.LocalDate.parse(v).equals(d.toLocalDate());
            }
            if (t instanceof java.time.LocalDate ld) {
                return java.time.LocalDate.parse(v).equals(ld);
            }
            java.time.LocalDateTime other = t instanceof java.sql.Timestamp ts
                    ? ts.toLocalDateTime()
                    : t instanceof java.time.LocalDateTime ldt ? ldt
                    : t instanceof java.time.OffsetDateTime odt
                            ? odt.toLocalDateTime() : null;
            if (other == null) {
                return false;
            }
            String norm = v.contains(" ") ? v.replace(' ', 'T') : v + "T00:00";
            return java.time.LocalDateTime.parse(norm).equals(other);
        } catch (java.time.format.DateTimeParseException ex) {
            return false;
        }
    }

    // ================================================================
    // toRepresentation() — pure source spelling of a value (ONE owner;
    // toRepresentation.pure — the testdatagen port, generalized)
    // ================================================================

    /** Pure {@code toRepresentation(any:Any[1])}: strings
     * backslash-escape, temporals take the {@code %} literal form,
     * Decimal the {@code D} suffix. Class instances would take the
     * {@code <id instanceOf T>} form — LOUD until a consumer needs it
     * (never guess an id). */
    public static String repr(@com.legend.Nullable Object v) {
        return switch (v) {
            case null -> "[]";   // an empty [0..1] renders as empty
            case String s -> "'" + s.replace("\\", "\\\\")
                    .replace("'", "\\'").replace("\n", "\\n") + "'";
            case BigDecimal d -> d.toPlainString() + "D";
            case Number n -> n.toString();
            case Boolean b -> b.toString();
            case java.sql.Date d -> "%" + d.toLocalDate();
            case java.time.LocalDate d -> "%" + d;
            case java.sql.Timestamp ts -> "%" + ts.toLocalDateTime()
                    .toString().replace('T', 'T');
            case java.time.LocalDateTime ldt -> "%" + ldt;
            default -> throw new com.legend.error.NotImplementedException(
                    "toRepresentation for " + v.getClass().getName()
                    + " is not modeled (spec: '<id instanceOf Type>' —"
                    + " loud until a consumer pins the id form)");
        };
    }

    /** The spec's single-value message side ({@code %r}) or the
     * collection form ({@code joinStrings('[', ', ', ']')}). */
    private static String reprSide(List<Object> side) {
        return side.size() == 1 ? repr(side.get(0)) : joined(side);
    }

    private static String joined(List<Object> side) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < side.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(repr(side.get(i)));
        }
        return sb.append(']').toString();
    }

    // ================================================================
    // sort() — pure's total order over Any, for assertSameElements
    // (numbers by value, then strings lexically, then booleans,
    // temporals by instant — the spec's own failure examples pin
    // number-before-string: sort([1, 3, '2']) = [1, 3, '2'])
    // ================================================================

    static List<Object> sorted(List<Object> values) {
        List<Object> out = new ArrayList<>(values);
        out.sort(Comparator.comparingInt(PureAsserts::typeRank)
                .thenComparing(PureAsserts::withinRank));
        return out;
    }

    private static int typeRank(@com.legend.Nullable Object v) {
        // EXPLICIT kinds only (Charter C2.4: an unmatched kind THROWS,
        // never becomes a plausible bucket)
        return switch (v) {
            case null -> 0;
            case Number n -> 1;
            case String s -> 2;
            case Boolean b -> 3;
            case java.sql.Date d -> 4;
            case java.sql.Timestamp t -> 4;
            case java.time.LocalDate d -> 4;
            case java.time.LocalDateTime d -> 4;
            case Map<?, ?> m -> 5;
            default -> throw new com.legend.error.NotImplementedException(
                    "assertSameElements sort over "
                            + v.getClass().getName() + " is not modeled");
        };
    }

    @SuppressWarnings("unchecked")
    private static Comparable<Object> withinRank(@com.legend.Nullable Object v) {
        return (Comparable<Object>) (Comparable<?>) switch (v) {
            case null -> "";
            case Number n -> new BigDecimal(String.valueOf(n));
            case String s -> s;
            case Boolean b -> b;
            default -> String.valueOf(v);   // temporals/maps: stable text order
        };
    }
}
