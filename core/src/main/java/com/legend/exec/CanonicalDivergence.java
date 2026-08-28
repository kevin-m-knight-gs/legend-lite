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
    // R2a — the DUAL-VERDICT census (the ratified permanent referee):
    // SQL byte verdict vs host lattice, plus counted declines
    private static final AtomicLong SQL_AGREE = new AtomicLong();
    private static final AtomicLong SQL_DISAGREE = new AtomicLong();
    private static final AtomicLong SQL_DECLINED = new AtomicLong();
    private static final AtomicLong SQL_ULP_POLICY = new AtomicLong();
    private static final int SAMPLE_CAP = 200;
    private static final ConcurrentLinkedQueue<Row> SAMPLES =
            new ConcurrentLinkedQueue<>();

    /** Census an equal-family verdict ({@code assertEquals}/{@code
     * assertEq}): {@code held} is the lattice answer already computed by
     * the K-arm; the byte answer is the KIND-QUALIFIED canonical-render
     * compare — (kindClass, text) pairs, because the render is not
     * injective across kinds (String "8" and Integer 8 both spell "8";
     * CANONICAL_FORM_SPEC §3 amendment). The numeric tower shares one
     * kind class (pure's cross-kind numeric equality). */
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

    /** R1b — the GRID channel (toCSV text verdicts): the actual side is
     * ALREADY the platform's SQL-side render (Render = engine toCSV,
     * H3 headline), so the byte answer is plain string equality; the
     * census measures how often GridCompare.renderedText's KEPT
     * leniencies (row multiset, bounded float tolerance) do work the
     * byte channel would refuse. */
    public static void probeGridText(String expected, String actual,
            boolean held) {
        if (expected.equals(actual)) {
            record("gridText", held, "EQUAL");
            return;
        }
        // name the first difference so the census classifies WHICH
        // leniency did the work (row order vs cell spelling)
        String[] el = expected.split("\\n", -1);
        String[] al = actual.split("\\n", -1);
        String why;
        if (el.length != al.length) {
            why = "line-count " + el.length + "!=" + al.length;
        } else {
            int i = 0;
            while (i < el.length && el[i].equals(al[i])) {
                i++;
            }
            java.util.List<String> es = new java.util.ArrayList<>(List.of(el));
            java.util.List<String> as = new java.util.ArrayList<>(List.of(al));
            java.util.Collections.sort(es);
            java.util.Collections.sort(as);
            why = es.equals(as)
                    ? "row-order-only@line" + i
                    : "cell-diff@line" + i + " e<" + trunc(el[i]) + "> a<"
                            + trunc(al[i]) + ">";
        }
        record("gridText", held, "DIFFER:" + why);
    }

    private static String trunc(String s) {
        return s.length() > 60 ? s.substring(0, 60) + "…" : s;
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
            String left = keyOf(e.get(i));
            String right = keyOf(a.get(i));
            if (left.startsWith("residue:")) {
                return left;
            }
            if (right.startsWith("residue:")) {
                return right;
            }
            er.add(left);
            ar.add(right);
        }
        if (sorted) {
            er.sort(String::compareTo);
            ar.sort(String::compareTo);
        }
        return er.equals(ar) ? "EQUAL" : "DIFFER";
    }

    /** The byte-channel comparison key: kindClass + canonical text
     * (spec §3 amendment — the render alone is not injective across
     * kinds), or a residue marker. */
    private static String keyOf(@com.legend.Nullable Object v) {
        return switch (CanonicalForm.render(v)) {
            case CanonicalForm.Result.Text t -> kindClass(v) + "\u0000" + t.value();
            case CanonicalForm.Result.Residue r -> "residue:" + r.reason();
        };
    }

    /** Pure's equality kind classes: the numeric tower is ONE class
     * (cross-kind numeric equality); everything else compares only
     * within its own kind. */
    private static String kindClass(@com.legend.Nullable Object v) {
        return switch (v) {
            case null -> "null";
            case Number n -> "numeric";
            case Boolean b -> "boolean";
            case String s -> "string";
            case com.legend.values.PureDateLiteral d -> "temporal";
            // unreachable: keyOf calls this only for values render()
            // accepted, and render's default is Residue — throwing keeps
            // the no-plausible-bucket rule (Charter C2.4)
            default -> throw new IllegalStateException(
                    "kindClass over unrendered kind: " + v.getClass());
        };
    }

    /** V7 probe isolation for the R1 instrument family (probeEqual/
     * probeSameElements/probeGridText → AGREE/DISAGREE/RESIDUE): the
     * corpus dual channel re-runs the same asserts through the
     * production path, and un-gated probes would double-count the
     * HOST lane's pinned leniency census (disagree ≤ 27). The
     * sql-verdict channel and the declared-policy counters stay LIVE
     * during the probe — they are the probe's own instruments. */
    private static final java.util.concurrent.atomic.AtomicBoolean
            R1_SUSPENDED = new java.util.concurrent.atomic.AtomicBoolean();

    public static void r1Suspend(boolean on) {
        R1_SUSPENDED.set(on);
    }

    private static void record(String family, boolean held, String byteAns) {
        if (R1_SUSPENDED.get()) {
            return;
        }
        if (byteAns.startsWith("residue:")) {
            RESIDUE.incrementAndGet();
            sample(new Row(family, held, byteAns));
        } else if (byteAns.equals("EQUAL") == held) {
            AGREE.incrementAndGet();
        } else {
            DISAGREE.incrementAndGet();
            sample(new Row(family, held, "lattice=" + held
                    + " byte=" + byteAns.replaceFirst("^DIFFER", "false")));
        }
    }

    private static void sample(Row r) {
        if (SAMPLES.size() < SAMPLE_CAP) {
            SAMPLES.add(r);
        }
    }

    /** R2a: one dual-verdict row — the DB byte verdict against the
     * host lattice. Disagreement is the permanent referee's alarm. */
    public static void probeSqlVerdict(String family, boolean hostHeld,
            boolean sqlHeld) {
        probeSqlVerdict(family, hostHeld, sqlHeld, "");
    }

    /** RESERVED witness buffer for the DUAL-VERDICT ALARM: a
     * disagreement is the gated-to-zero signal and must never lose its
     * witness to shared-buffer crowding (a 200-cap buffer full of
     * decline rows swallowed the one alarm row, 2026-08-28). */
    private static final ConcurrentLinkedQueue<Row> SQL_DISAGREE_SAMPLES =
            new ConcurrentLinkedQueue<>();

    public static List<Row> sqlDisagreeSamples() {
        return List.copyOf(SQL_DISAGREE_SAMPLES);
    }

    /** {@code detail} carries the two canon texts + fine kinds so a
     * disagreement names its own diagnosis in the census. */
    public static void probeSqlVerdict(String family, boolean hostHeld,
            boolean sqlHeld, String detail) {
        if (hostHeld == sqlHeld) {
            SQL_AGREE.incrementAndGet();
        } else {
            SQL_DISAGREE.incrementAndGet();
            Row r = new Row(family, hostHeld,
                    "sql-verdict host=" + hostHeld + " sql=" + sqlHeld
                            + " " + detail);
            if (SQL_DISAGREE_SAMPLES.size() < 50) {
                SQL_DISAGREE_SAMPLES.add(r);
            }
            sample(r);
        }
    }

    /** R2a: a side the SQL channel declined (non-scalar kind, unclaimed
     * render, lowering refusal) — the host lattice judged instead. The
     * REASON rides the sample buffer so the V6 burn targets families,
     * not a bare count. */
    public static void sqlDeclined() {
        sqlDeclined("unclassified");
    }

    public static void sqlDeclined(String reason) {
        SQL_DECLINED.incrementAndGet();
        sample(new Row("sqlDecline", false, reason));
    }

    public static long sqlDisagreeCount() {
        return SQL_DISAGREE.get();
    }

    public static long sqlDeclinedCount() {
        return SQL_DECLINED.get();
    }

    /** OPEN_REGISTER §5 / X6: a byte-differing Double pair the DECLARED
     * 2-ULP dialect-arithmetic policy adjudicated equal (cross-dialect
     * libm last-ULP drift — H2-derived corpus goldens vs DuckDB
     * transcendentals). Counted so the R3 census can retire or ratify
     * the policy from its real witness set. */
    public static void sqlUlpPolicy(String detail) {
        SQL_ULP_POLICY.incrementAndGet();
        sample(new Row("sqlUlpPolicy", true, detail));
    }

    public static long sqlUlpPolicyCount() {
        return SQL_ULP_POLICY.get();
    }

    /** V7 batch 2: a byte-differing pair the DECLARED TDSNull-sentinel
     * policy adjudicated equal (PureAsserts equalScalar: an EXPECTED
     * 'TDSNull' equals an actual NULL cell — the engine golden's null
     * spelling). Counted like the 2-ULP policy: rides ON TOP of the
     * byte channel, never a disagreement rescue. */
    private static final AtomicLong SQL_TDSNULL_POLICY = new AtomicLong();

    public static void sqlTdsNullPolicy(String detail) {
        SQL_TDSNULL_POLICY.incrementAndGet();
        sample(new Row("sqlTdsNullPolicy", true, detail));
    }

    public static long sqlTdsNullPolicyCount() {
        return SQL_TDSNULL_POLICY.get();
    }

    // ── V7 (docs/V7_ASSERT_VERDICT_CHARTER.md §4.1): the corpus DUAL
    // CHANNEL census — the harness's host verdict vs the production
    // AssertVerdicts route, per assert FORM (name/arity). DISTINCT from
    // the sql-verdict counters above (those are the production path's
    // INNER referee: PureAsserts vs the DB byte canon); this table
    // compares two whole adjudicators, and its disagree rows ARE batch
    // 2's work list. Measurement only — no entry can affect a verdict.
    private static final java.util.concurrent.ConcurrentHashMap<String, long[]>
            V7_FORMS = new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.concurrent.ConcurrentHashMap<String, AtomicLong>
            V7_DECLINES = new java.util.concurrent.ConcurrentHashMap<>();
    private static final int V7_DECLINE_KEY_CAP = 400;
    private static final ConcurrentLinkedQueue<Row> V7_SAMPLES =
            new ConcurrentLinkedQueue<>();
    /** Side-size histogram, log2 buckets (0, 1, 2-3, 4-7, …): the
     * golden-size fact the census's §5-1 homework asked for — V12's
     * VALUES-literal cost bracket rides these counts. */
    private static final java.util.concurrent.atomic.AtomicLongArray
            V7_SIDE_ROWS = new java.util.concurrent.atomic.AtomicLongArray(16);

    /** One dual-channel verdict pair: both adjudicators judged. */
    public static void v7Verdict(String form, boolean hostPass,
            boolean prodPass, String detail) {
        long[] c = V7_FORMS.computeIfAbsent(form, k -> new long[2]);
        synchronized (c) {
            c[hostPass == prodPass ? 0 : 1]++;
        }
        if (hostPass != prodPass && V7_SAMPLES.size() < SAMPLE_CAP) {
            V7_SAMPLES.add(new Row(form, hostPass,
                    "host=" + (hostPass ? "pass" : "fail")
                            + " prod=" + (prodPass ? "pass" : "fail")
                            + " " + detail));
        }
    }

    /** A NAMED per-form decline (D2: never a silent skip) — the §2 host
     * partition, host-unsupported forms, and production walls. The
     * reason is a bounded classification key, not free prose. */
    public static void v7Declined(String form, String reason) {
        String r = reason.length() > 200 ? reason.substring(0, 200) + "…"
                : reason;
        String key = form + " :: " + r;
        if (V7_DECLINES.size() >= V7_DECLINE_KEY_CAP
                && !V7_DECLINES.containsKey(key)) {
            key = form + " :: …overflow";
        }
        V7_DECLINES.computeIfAbsent(key, k -> new AtomicLong())
                .incrementAndGet();
    }

    /** One assert side's fetched element count (histogram feed). */
    public static void v7SideRows(int n) {
        int b = n <= 0 ? 0 : Math.min(64 - Long.numberOfLeadingZeros(n), 15);
        V7_SIDE_ROWS.incrementAndGet(b);
    }

    public static long v7DisagreeCount() {
        long d = 0;
        for (long[] c : V7_FORMS.values()) {
            synchronized (c) {
                d += c[1];
            }
        }
        return d;
    }

    public static long v7DeclinedCount() {
        return V7_DECLINES.values().stream().mapToLong(AtomicLong::get).sum();
    }

    public static String v7Summary() {
        long agree = 0;
        long disagree = 0;
        for (long[] c : V7_FORMS.values()) {
            synchronized (c) {
                agree += c[0];
                disagree += c[1];
            }
        }
        StringBuilder hist = new StringBuilder();
        for (int i = 0; i < V7_SIDE_ROWS.length(); i++) {
            long n = V7_SIDE_ROWS.get(i);
            if (n > 0) {
                hist.append(' ').append(i == 0 ? "0" : i == 1 ? "1"
                        : (1L << (i - 1)) + "-" + ((1L << i) - 1))
                        .append(':').append(n);
            }
        }
        return "dual-channel agree=" + agree + " disagree=" + disagree
                + " declined=" + v7DeclinedCount()
                + " | side-rows" + (hist.isEmpty() ? " none" : hist);
    }

    /** Per-form table + classified decline reasons + disagreement
     * samples — sorted for stable console diffs (DISPLAY ordering only,
     * the SqlTypeCensus report precedent). */
    public static List<String> v7Report() {
        List<String> out = new ArrayList<>();
        V7_FORMS.entrySet().stream()
                .sorted(java.util.Map.Entry.comparingByKey())
                .forEach(e -> {
                    long[] c = e.getValue();
                    long a;
                    long d;
                    synchronized (c) {
                        a = c[0];
                        d = c[1];
                    }
                    out.add("form " + e.getKey() + " agree=" + a
                            + " disagree=" + d);
                });
        V7_DECLINES.entrySet().stream()
                .sorted(java.util.Map.Entry.comparingByKey())
                .forEach(e -> out.add("declined " + e.getKey() + " = "
                        + e.getValue().get()));
        V7_SAMPLES.forEach(r -> out.add("disagree-witness " + r.family()
                + " " + r.detail()));
        return out;
    }

    public static String summary() {
        return "agree=" + AGREE.get() + " disagree=" + DISAGREE.get()
                + " residue=" + RESIDUE.get()
                + " | sql-verdict agree=" + SQL_AGREE.get()
                + " disagree=" + SQL_DISAGREE.get()
                + " declined=" + SQL_DECLINED.get()
                + " ulp-policy=" + SQL_ULP_POLICY.get();
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
        SQL_AGREE.set(0);
        SQL_DISAGREE.set(0);
        SQL_DECLINED.set(0);
        SQL_ULP_POLICY.set(0);
        SQL_TDSNULL_POLICY.set(0);
        SAMPLES.clear();
        SQL_DISAGREE_SAMPLES.clear();
        V7_FORMS.clear();
        V7_DECLINES.clear();
        V7_SAMPLES.clear();
        for (int i = 0; i < V7_SIDE_ROWS.length(); i++) {
            V7_SIDE_ROWS.set(i, 0);
        }
    }
}
