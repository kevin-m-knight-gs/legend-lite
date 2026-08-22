// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend.exec;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;

/**
 * R1's divergence instrument (docs/CANONICAL_FORM_SPEC.md §0): for
 * every assert-family verdict the K-arm computes, ALSO decide the
 * byte-channel answer — {@code render(e) == render(a)} over the R0
 * canonical form — and census the agreement. The harness publishes the
 * table at end of run. PURE MEASUREMENT: nothing here can affect a
 * verdict (the probe returns void), and the classes stay out of every
 * production path by construction — the TimingLedger idiom.
 *
 * <p>Row classes: AGREE (byte answer == lattice answer — the ⟺ claim
 * holds on this operand pair), DISAGREE (the claim fails — an R0 spec
 * gap, a render bug, or a policy row like 2-ULP tolerance doing work),
 * RESIDUE (an operand outside the byte channel's claimed domain — §4).
 * DISAGREE rows are the R2 blockers; RESIDUE rows size the walls.
 */
public final class CanonicalDivergence {

    private CanonicalDivergence() {
    }

    /** One disagreement/residue witness (bounded sample). */
    public record Row(String family, boolean held, String detail) {
    }

    private static final AtomicLong AGREE = new AtomicLong();
    private static final AtomicLong DISAGREE = new AtomicLong();
    private static final AtomicLong RESIDUE = new AtomicLong();
    private static final int SAMPLE_CAP = 200;
    private static final ConcurrentLinkedQueue<Row> SAMPLES =
            new ConcurrentLinkedQueue<>();

    /** Census an equal-family verdict ({@code assertEquals}/{@code
     * assertEq}): {@code held} is the lattice answer already computed by
     * the K-arm; the byte answer is the canonical-render compare with
     * the temporal string-carrier bridge applied PAIRWISE (mirroring
     * {@code PureAsserts.equalScalar}'s designed-carrier policy). */
    public static void probeEqual(String family, List<Object> e,
            List<Object> a, boolean held) {
        record(family, held, byteEqual(e, a, false));
    }

    /** Census an {@code assertSameElements} verdict: the byte channel's
     * multiset rule is canonical-render each element, SORT the rendered
     * strings, compare — the census-side stand-in for R2's canonical
     * ORDER BY. */
    public static void probeSameElements(List<Object> e, List<Object> a,
            boolean held) {
        record("assertSameElements", held, byteEqual(e, a, true));
    }

    /** Byte-channel answer encoded as a STRING — {@code "EQUAL"},
     * {@code "DIFFER"}, or {@code "residue:<reason>"} naming what fell
     * out of the claimed domain (the census's wall-sizing detail). */
    private static String byteEqual(List<Object> e, List<Object> a,
            boolean sorted) {
        if (e.size() != a.size()) {
            return "DIFFER";
        }
        List<String> er = new ArrayList<>(e.size());
        List<String> ar = new ArrayList<>(a.size());
        for (int i = 0; i < e.size(); i++) {
            Object left = renderBridged(e.get(i), a.get(i));
            Object right = renderBridged(a.get(i), e.get(i));
            if (left instanceof CanonicalForm.Result.Residue r) {
                return "residue:" + r.reason();
            }
            if (right instanceof CanonicalForm.Result.Residue r) {
                return "residue:" + r.reason();
            }
            er.add(((CanonicalForm.Result.Text) left).value());
            ar.add(((CanonicalForm.Result.Text) right).value());
        }
        if (sorted) {
            er.sort(String::compareTo);
            ar.sort(String::compareTo);
        }
        return er.equals(ar) ? "EQUAL" : "DIFFER";
    }

    /** Render {@code v}; when it is a STRING paired against a temporal
     * (either direction of the designed string-carrier bridge), the
     * string canonicalizes through the temporal parse so bridge pairs
     * byte-agree exactly where the lattice's bridge grants equality. A
     * non-parsing string stays verbatim (the typing-bug catch is the
     * parse — same rule as the lattice). */
    private static CanonicalForm.Result renderBridged(
            @com.legend.Nullable Object v, @com.legend.Nullable Object other) {
        if (v instanceof String s && isTemporalCarrier(other)) {
            Object parsed = parseTemporal(s);
            if (parsed != null) {
                v = parsed;
            }
        }
        return CanonicalForm.render(v);
    }

    private static boolean isTemporalCarrier(@com.legend.Nullable Object v) {
        // java.time ONLY — sql types never escape the fetch (a java.sql
        // carrier here would surface as an unmodeled-kind residue)
        return v instanceof java.time.LocalDate
                || v instanceof java.time.LocalDateTime
                || v instanceof java.time.OffsetDateTime;
    }

    private static @com.legend.Nullable Object parseTemporal(String s) {
        String v = s.trim().replaceFirst("Z$", "+0000").replace(' ', 'T');
        java.time.ZoneOffset zo = null;
        java.util.regex.Matcher off = java.util.regex.Pattern
                .compile("([+-])(\\d{2}):?(\\d{2})$").matcher(v);
        if (off.find()) {
            zo = java.time.ZoneOffset.of(
                    off.group(1) + off.group(2) + ":" + off.group(3));
            v = v.substring(0, off.start());
        }
        try {
            if (!v.contains("T")) {
                return java.time.LocalDate.parse(v);
            }
            java.time.LocalDateTime ldt = java.time.LocalDateTime.parse(v);
            return zo == null ? ldt
                    : ldt.atOffset(zo).withOffsetSameInstant(
                            java.time.ZoneOffset.UTC).toLocalDateTime();
        } catch (java.time.format.DateTimeParseException ex) {
            return null;
        }
    }

    private static void record(String family, boolean held, String byteAns) {
        if (byteAns.startsWith("residue:")) {
            RESIDUE.incrementAndGet();
            sample(new Row(family, held, byteAns));
        } else if (byteAns.equals("EQUAL") == held) {
            AGREE.incrementAndGet();
        } else {
            DISAGREE.incrementAndGet();
            sample(new Row(family, held,
                    "lattice=" + held + " byte=" + byteAns.equals("EQUAL")));
        }
    }

    private static void sample(Row r) {
        if (SAMPLES.size() < SAMPLE_CAP) {
            SAMPLES.add(r);
        }
    }

    public static String summary() {
        return "agree=" + AGREE.get() + " disagree=" + DISAGREE.get()
                + " residue=" + RESIDUE.get();
    }

    public static long disagreeCount() {
        return DISAGREE.get();
    }

    public static long residueCount() {
        return RESIDUE.get();
    }

    public static List<Row> samples() {
        return List.copyOf(SAMPLES);
    }

    public static void reset() {
        AGREE.set(0);
        DISAGREE.set(0);
        RESIDUE.set(0);
        SAMPLES.clear();
    }
}
