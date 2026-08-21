// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend.exec;

import java.util.ArrayList;
import java.util.List;

/**
 * THE TDS-GRID AND RENDERED-TEXT COMPARE POLICIES (One-Platform Plan
 * Phase 2b — moved from the harness at {@code wireEquals}' deletion;
 * the policies survive, the private copies die). Cell equality is
 * {@link PureAsserts#equalScalar} — the ONE owner; this class holds the
 * GRID-SHAPED adjudications:
 *
 * <ul>
 * <li><b>Header pins</b> — column names compare ordered and exactly
 * (the platform emits them; a divergence is a finding).</li>
 * <li><b>The order policy</b> — pure equality is ordered, but an
 * actual side with no {@code sort} in its chain has no defined SQL row
 * order (corpus expectations encode H2's incidental order, ours is
 * DuckDB's): rows then compare as a TUPLE multiset — row cohesion,
 * never loose cells (cross-row shuffles must fail).</li>
 * <li><b>Rendered text</b> (F4.3) — CSV/TDS frame and header lines pin
 * exactly; data lines ordered-or-multiset per the chain's sortedness;
 * cells string-equal or bounded-float-tolerant ({@code LL_TOL_COUNT}
 * counted). The cross-kind numeric collapse stays deleted.</li>
 * <li><b>Instrumentation</b> — {@code LL_ORD_COUNT} emits an
 * {@code [ord]} line when a pass depended on order leniency
 * (C0.4/F2.4); measurement only, never the verdict.</li>
 * </ul>
 */
public final class GridCompare {

    private GridCompare() {
    }

    /** TDS grids compare STRUCTURALLY: column names ordered+exact, rows
     * under the order policy. */
    public static boolean grids(ExecutionResult.Tabular expected,
            ExecutionResult.Tabular actual, boolean ordered) {
        if (expected.columns().size() != actual.columns().size()) {
            return false;
        }
        for (int i = 0; i < expected.columns().size(); i++) {
            if (!expected.columns().get(i).name()
                    .equals(actual.columns().get(i).name())) {
                return false;
            }
        }
        List<List<Object>> e = new ArrayList<>();
        expected.rows().forEach(r -> e.add(r.values()));
        List<List<Object>> a = new ArrayList<>();
        actual.rows().forEach(r -> a.add(r.values()));
        if (e.size() != a.size()) {
            return false;
        }
        if (ordered) {
            return rowsPositional(e, a);
        }
        List<List<Object>> pool = new ArrayList<>(a);
        for (List<Object> row : e) {
            int hit = -1;
            for (int i = 0; i < pool.size(); i++) {
                if (rowEquals(row, pool.get(i))) {
                    hit = i;
                    break;
                }
            }
            if (hit < 0) {
                return false;
            }
            pool.remove(hit);
        }
        // C0.4: a multiset pass the POSITIONAL compare would reject is a
        // pass that depends on order leniency — countable per sweep
        ordLeniency("row-multiset", () -> rowsPositional(e, a));
        return true;
    }

    /** ROW COHESION (audit 9): an ordered compare's multiset fallback
     * matches ROW TUPLES of width {@code w}, never loose cells. */
    public static boolean rowTupleMultiset(List<Object> e, List<Object> a,
            int w) {
        List<List<Object>> ep = chunk(e, w);
        List<List<Object>> ap = chunk(a, w);
        for (List<Object> row : ep) {
            int hit = -1;
            for (int i = 0; i < ap.size(); i++) {
                if (rowEquals(row, ap.get(i))) {
                    hit = i;
                    break;
                }
            }
            if (hit < 0) {
                return false;
            }
            ap.remove(hit);
        }
        // F2.4: row-tuple multiset leniency, instrumented
        ordLeniency("row-tuple", () -> {
            for (int i = 0; i < e.size(); i++) {
                if (!PureAsserts.equalScalar(e.get(i), a.get(i))) {
                    return false;
                }
            }
            return true;
        });
        return true;
    }

    private static List<List<Object>> chunk(List<Object> flat, int w) {
        List<List<Object>> out = new ArrayList<>(flat.size() / w);
        for (int i = 0; i < flat.size(); i += w) {
            out.add(new ArrayList<>(flat.subList(i, i + w)));
        }
        return out;
    }

    private static boolean rowsPositional(List<List<Object>> e,
            List<List<Object>> a) {
        for (int i = 0; i < e.size(); i++) {
            if (!rowEquals(e.get(i), a.get(i))) {
                return false;
            }
        }
        return true;
    }

    private static boolean rowEquals(List<Object> e, List<Object> a) {
        if (e.size() != a.size()) {
            return false;
        }
        for (int i = 0; i < e.size(); i++) {
            if (!PureAsserts.equalScalar(e.get(i), a.get(i))) {
                return false;
            }
        }
        return true;
    }

    /** {@code assertTdsEquivalent} cell comparison (engine
     * tdsEquivalent.pure — moved from the harness's TdsEquivalence at
     * Phase 2b): numeric cells within {@code |delta|}, temporal cells
     * within {@code |timeDeltaInSeconds|} seconds, everything else by
     * same-kind equality (the cross-kind String.valueOf collapse stays
     * deleted — F6.5); rows zip IN ORDER. Null = equivalent. */
    public static @com.legend.Nullable String tdsEquivalent(
            List<Object> expected, List<Object> got, double delta,
            double timeDeltaSeconds) {
        if (expected.size() != got.size()) {
            return "assertTdsEquivalent: expected " + expected.size()
                    + " cells, got " + got.size();
        }
        for (int i = 0; i < expected.size(); i++) {
            Object x = expected.get(i);
            Object y = got.get(i);
            if (x == null && y == null) {
                continue;
            }
            if (x instanceof Number nx && y instanceof Number ny) {
                if (Math.abs(nx.doubleValue() - ny.doubleValue())
                        <= Math.abs(delta)) {
                    continue;
                }
            } else {
                Double ex = epochSeconds(x);
                Double ey = epochSeconds(y);
                if (ex != null && ey != null) {
                    if (Math.abs(ex - ey) <= Math.abs(timeDeltaSeconds)) {
                        continue;
                    }
                } else if (java.util.Objects.equals(x, y)) {
                    continue;
                }
            }
            return "assertTdsEquivalent: cell " + i + " expected " + x
                    + ", got " + y;
        }
        return null;
    }

    private static @com.legend.Nullable Double epochSeconds(
            @com.legend.Nullable Object v) {
        return switch (v) {
            case java.time.LocalDate d -> (double) d.toEpochDay() * 86400.0;
            case java.time.LocalDateTime dt ->
                    (double) dt.toEpochSecond(java.time.ZoneOffset.UTC);
            case java.time.Instant in -> (double) in.getEpochSecond();
            // no JDBC value-accessor spellings (TenetRatchet C1.2): the
            // instant/local conversions carry the same value — and the
            // java.sql.Date arm now matches the LocalDate arm's UTC
            // convention (the old default-TZ getTime() wobbled by the
            // zone offset in mixed-kind compares)
            case java.sql.Timestamp ts ->
                    ts.toInstant().toEpochMilli() / 1000.0;
            case java.sql.Date d ->
                    (double) d.toLocalDate().toEpochDay() * 86400.0;
            case null, default -> null;
        };
    }

    /** C0.4: under {@code LL_ORD_COUNT}, emit an {@code [ord]} line when
     * a comparison passed ONLY because of multiset row leniency —
     * {@code strictHolds} is the order-strict re-check. Measurement
     * only; never changes the verdict. {@code tag} (audit-of-audits
     * #10) names the SITE so the census separates loose-cell matching
     * from row-tuple matching in the counted population. */
    public static void ordLeniency(String tag,
            java.util.function.BooleanSupplier strictHolds) {
        if (System.getenv("LL_ORD_COUNT") != null
                && !strictHolds.getAsBoolean()) {
            System.err.println("[ord] " + tag + " order-leniency-dependent pass");
        }
    }

    /** F4.3: RENDERED-TEXT comparison — the KEPT policies over the
     * platform's own text. {@code form}: CSVTEXT (header line + data
     * lines + trailing newline), TDSTEXT ('#TDS' frame), or
     * CSVJOIN:&lt;sep&gt; (the calendar family's one-line spelling).
     * Frame and header lines pin EXACTLY; data lines ordered or multiset
     * per the chain's sortedness; cells string-equal or bounded-float-
     * tolerant. */
    public static boolean renderedText(String expected, String actual,
            String form, boolean sorted) {
        if (expected.equals(actual)) {
            return true;
        }
        if (form.startsWith("CSVJOIN:")) {
            String sep = form.substring("CSVJOIN:".length());
            String[] et = expected.split(java.util.regex.Pattern.quote(sep), -1);
            String[] at = actual.split(java.util.regex.Pattern.quote(sep), -1);
            if (et.length != at.length) {
                return false;
            }
            if (sorted) {
                for (int i = 0; i < et.length; i++) {
                    if (!cellEquals(et[i], at[i])) {
                        return false;
                    }
                }
                return true;
            }
            // token multiset (the sep-join destroyed row boundaries;
            // bounded by equal counts + per-cell policy)
            List<String> pool = new ArrayList<>(List.of(at));
            for (String e : et) {
                int hit = -1;
                for (int i = 0; i < pool.size(); i++) {
                    if (cellEquals(e, pool.get(i))) {
                        hit = i;
                        break;
                    }
                }
                if (hit < 0) {
                    return false;
                }
                pool.remove(hit);
            }
            return true;
        }
        String[] el = expected.split("\n", -1);
        String[] al = actual.split("\n", -1);
        if (el.length != al.length) {
            return false;
        }
        // pinned frame lines: CSVTEXT {first, trailing ''}; TDSTEXT
        // {'#TDS', header, trailing '#'}
        int dataFrom = form.equals("TDSTEXT") ? 2 : 1;
        int dataTo = el.length - 1;   // last line: '' (CSV) or '#' (TDS)
        for (int i = 0; i < el.length; i++) {
            boolean pinned = i < dataFrom || i >= dataTo;
            if (pinned && !el[i].equals(al[i])) {
                return false;
            }
        }
        if (sorted) {
            for (int i = dataFrom; i < dataTo; i++) {
                if (!lineEquals(el[i], al[i])) {
                    return false;
                }
            }
            return true;
        }
        List<String> pool = new ArrayList<>();
        for (int i = dataFrom; i < dataTo; i++) {
            pool.add(al[i]);
        }
        for (int i = dataFrom; i < dataTo; i++) {
            String e = el[i];
            int hit = -1;
            for (int j = 0; j < pool.size(); j++) {
                if (lineEquals(e, pool.get(j))) {
                    hit = j;
                    break;
                }
            }
            if (hit < 0) {
                return false;
            }
            pool.remove(hit);
        }
        // F2.4 ord-leniency instrument carries over to the text policy
        final String[] elf = el;
        final String[] alf = al;
        final int from = dataFrom;
        final int to = dataTo;
        ordLeniency("text-line-multiset", () -> {
            for (int i = from; i < to; i++) {
                if (!lineEquals(elf[i], alf[i])) {
                    return false;
                }
            }
            return true;
        });
        return true;
    }

    /** One text line: exact, else per-cell (comma-split both sides
     * IDENTICALLY — symmetric ambiguity for embedded commas). */
    private static boolean lineEquals(String e, String a) {
        if (e.equals(a)) {
            return true;
        }
        String[] ec = e.split(",", -1);
        String[] ac = a.split(",", -1);
        if (ec.length != ac.length) {
            return false;
        }
        for (int i = 0; i < ec.length; i++) {
            if (!cellEquals(ec[i], ac[i])) {
                return false;
            }
        }
        return true;
    }

    /** One cell — the KEPT float tolerance, bounded and counted
     * ({@code LL_TOL_COUNT}), applied ONLY when BOTH tokens are
     * decimal-point float prints. The cross-kind collapse
     * ('007'=='7', '1e3'=='1000') stays DELETED: integers and
     * scientific forms compare as strings. */
    private static boolean cellEquals(String e, String a) {
        if (e.equals(a)) {
            return true;
        }
        if (e.indexOf('.') < 0 || a.indexOf('.') < 0) {
            return false;
        }
        try {
            double ev = Double.parseDouble(e);
            double av = Double.parseDouble(a);
            int dp = e.length() - e.indexOf('.') - 1;
            int sig = 0;
            boolean seenNonZero = false;
            for (int ci = 0; ci < e.length(); ci++) {
                char ch = e.charAt(ci);
                if (ch >= '1' && ch <= '9') {
                    seenNonZero = true;
                }
                if (Character.isDigit(ch) && (seenNonZero || ch != '0')) {
                    sig++;
                }
            }
            double tol = sig >= 10
                    ? Math.max(0.5 * Math.pow(10, -dp), Math.abs(ev) * 1e-11)
                    : Math.abs(ev) * 1e-11;
            if (Math.abs(av - ev) > tol) {
                return false;
            }
            if (av != ev && System.getenv("LL_TOL_COUNT") != null) {
                System.err.println("[tol] csv " + e + " vs " + a);
            }
            return true;
        } catch (NumberFormatException nfe) {
            return false;
        }
    }
}
