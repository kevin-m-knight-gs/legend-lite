// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend.exec;

import com.legend.sql.OutputCol;
import com.legend.sql.SqlExpr;
import com.legend.sql.SqlQuery;
import com.legend.sql.SqlSelect;
import com.legend.sql.SqlSource;
import com.legend.sql.SqlType;
import com.legend.sql.SqlTyping;
import com.legend.sql.SqlUnion;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

/**
 * TYPED-IR Slice 1 — THE LABEL-LIE CENSUS (instrument &rarr; census
 * &rarr; flip): for every EXECUTED plan, compare each output column's
 * DECLARED label (stamp-derived today) against the type the
 * {@link SqlTyping} judgment computes from the expression ACTUALLY
 * built. Pure measurement beside the comparison layer (the
 * CanonicalDivergence pattern): probes consume a finished plan and
 * count; nothing here can produce a result or affect execution.
 *
 * <p>Three buckets per column: AGREE (label == computed), MISMATCH
 * (both known, different — the lie census, classified by the
 * declared/computed pair; some pairs will adjudicate as ADMISSIBLE
 * CARRIERS — the temporal VARCHAR convention — and the flip encodes
 * that admissibility relation), UNTYPED (the judgment has no rule yet
 * — the coverage census, classified by expression shape).
 */
public final class SqlTypeCensus {

    private SqlTypeCensus() {
    }

    private static final LongAdder PLANS = new LongAdder();
    private static final LongAdder AGREE = new LongAdder();
    private static final LongAdder ADMISSIBLE = new LongAdder();
    private static final LongAdder BOTTOM_OK = new LongAdder();
    private static final LongAdder BOTTOM_MULT = new LongAdder();
    private static final LongAdder MISMATCH = new LongAdder();
    private static final LongAdder UNTYPED = new LongAdder();

    /** THE ADMISSIBILITY RELATION (T3, user-audited 2026-08-23): the
     * registered (declared, computed) carrier pairs — each a
     * DELIBERATE representation choice with its justification.
     * Everything NOT here that differs is a MISMATCH — the bug list.
     *
     * <p>HONESTY NOTE (recorded from the audit): these are TYPE-PAIR
     * rules, and three of them are COARSER than their justifying
     * conventions — the temporal-text pair's true scope is
     * PARTIAL-PRECISION carriage (full-precision values should ride
     * native temporals); the TIMESTAMP&larr;DATE pair is SUBSUMPTION
     * (a StrictDate value in an ABSTRACT-Date slot — a DateTime-
     * stamped slot receiving DATE would be a real kind bug the pair
     * cannot distinguish); the DOUBLE&larr;VARCHAR pair is the
     * NUMBER-slot identity carrier (pure literal spellings for ALL
     * fine kinds — the DOUBLE label is where the abstract-Number
     * stamp erases to), not a Float convention. Enforcement (T4)
     * conditions these on the pure STAMP or retires them by emission;
     * the census keeps every admitted class counted AND witnessed.
     * The reverse temporal direction (DATE label &larr; TIMESTAMP
     * wire: a datetime in a strict-date slot) is DELIBERATELY absent
     * — that is a bug, never a carrier. A previously-registered
     * INTEGER&larr;BIGINT rule was REMOVED same day: label-narrowing
     * is only value-safe, which a type rule cannot see. */
    private static boolean admissible(SqlType declared, SqlType computed) {
        // partial-precision temporal carriage (D-arc): SQL temporals
        // cannot hold pure's partial precisions, so temporal slots may
        // carry the precision-faithful VARCHAR wire
        if ((declared == SqlType.Scalar.TIMESTAMP
                || declared == SqlType.Scalar.DATE)
                && computed == SqlType.Scalar.VARCHAR) {
            return true;
        }
        // SUBSUMPTION at the abstract-Date slot (F5.4): the TIMESTAMP
        // label is where abstract Date erases; a StrictDate value's
        // DATE wire is a subtype in a supertype slot
        if (declared == SqlType.Scalar.TIMESTAMP
                && computed == SqlType.Scalar.DATE) {
            return true;
        }
        // the NUMBER-slot identity carrier: pure literal spellings
        // (1 / 7.345 / 2D) keep every fine kind's identity in text;
        // DOUBLE is where the abstract-Number stamp erases
        if (declared == SqlType.Scalar.DOUBLE
                && computed == SqlType.Scalar.VARCHAR) {
            return true;
        }
        // serialize-as-text (the m2m/graphFetch egress): DuckDB serves
        // JSON as its text; the conform-by-emission cast is a later,
        // golden-text-gated slice
        if (declared == SqlType.Scalar.VARCHAR
                && computed == SqlType.Scalar.JSON) {
            return true;
        }
        // Decimal WIDENING is lossless: a narrower computed decimal
        // fits any wider label at the same scale
        return declared instanceof SqlType.Decimal d
                && computed instanceof SqlType.Decimal c2
                && d.scale() == c2.scale()
                && d.precision() >= c2.precision();
    }
    /** declared-&gt;computed pair (mismatch) / expr shape (untyped)
     * &rarr; occurrence count. */
    private static final Map<String, LongAdder> CLASSES =
            new ConcurrentHashMap<>();
    /** First few WITNESSES per class — enough detail to locate the
     * emission seam (column name + expression sketch). Bounded. */
    private static final Map<String, List<String>> SAMPLES =
            new ConcurrentHashMap<>();
    private static final int SAMPLES_PER_CLASS = 3;

    /** The runners' per-test marker (set beside StampCensus.CONTEXT
     * by ChannelB and the corpus Runner — test-side wiring): witness
     * attribution without exec reaching into the middle-end
     * (Invariant 6d). */
    public static final ThreadLocal<String> CONTEXT = new ThreadLocal<>();

    public static void probe(SqlQuery plan) {
        PLANS.increment();
        walk(plan);
    }

    private static void walk(SqlQuery q) {
        if (q instanceof SqlUnion u) {
            u.branches().forEach(SqlTypeCensus::walk);
            return;
        }
        SqlSelect s = (SqlSelect) q;
        List<SqlSource> leaves = new ArrayList<>();
        collect(s.from(), leaves);
        for (SqlSource leaf : leaves) {
            if (leaf instanceof SqlSource.Subselect sub) {
                walk(sub.inner());
            }
        }
        if (s.projections().size() != s.outputs().size()) {
            return;   // star selects and shape-mismatched frames: no
                      // per-column claim to check
        }
        for (int i = 0; i < s.projections().size(); i++) {
            SqlExpr e = s.projections().get(i).expr();
            if (e instanceof SqlExpr.Star
                    || e instanceof SqlExpr.StarExcept) {
                continue;
            }
            OutputCol declared = s.outputs().get(i);
            SqlTyping.Verdict v = SqlTyping.judge(e,
                    col -> resolve(col, leaves));
            switch (v) {
                case SqlTyping.Verdict.Bottom b -> {
                    // ADJUDICATED (user challenge 2026-08-23): today the
                    // nullable flag MEANS "the pure multiplicity is
                    // required" (PureSql.nullable is its only writer) —
                    // it is NOT a wire-nullability contract, so a
                    // padding NULL under it is not a lie; it is the
                    // measured DIVERGENCE between multiplicity-derived
                    // labels and wire NULLs (union set-pk pads, subtype
                    // markers — engine-side these are unlabeled
                    // plumbing). The T4 flip re-labels WHOLESALE to
                    // wire meaning — one meaning change, one owner;
                    // this bucket is that flip's measured backlog,
                    // never a per-site fixer's hunting ground (the
                    // rejected NullPadLabels pass: builder+fixer split,
                    // mixed-meaning flags).
                    if (declared.nullable()) {
                        BOTTOM_OK.increment();
                    } else {
                        String cls = "null-under-required-multiplicity: "
                                + declared.type();
                        BOTTOM_MULT.increment();
                        classify(cls);
                        sample(cls, declared.name() + " := " + sketch(e));
                    }
                }
                case SqlTyping.Verdict.Unknown u -> {
                    UNTYPED.increment();
                    classify("untyped: " + shapeOf(e));
                }
                case SqlTyping.Verdict.Typed t -> {
                    if (t.type().equals(declared.type())) {
                        AGREE.increment();
                    } else if (admissible(declared.type(), t.type())) {
                        String cls = "admissible " + declared.type()
                                + " <- " + t.type();
                        ADMISSIBLE.increment();
                        classify(cls);
                        sample(cls, declared.name() + " := " + sketch(e));
                    } else {
                        String cls = "declared " + declared.type()
                                + " <> computed " + t.type();
                        MISMATCH.increment();
                        classify(cls);
                        sample(cls, declared.name() + " := " + sketch(e));
                    }
                }
            }
        }
    }

    private static void collect(SqlSource src, List<SqlSource> out) {
        if (src instanceof SqlSource.Join j) {
            collect(j.left(), out);
            collect(j.right(), out);
        } else {
            out.add(src);
        }
    }

    private static @com.legend.Nullable SqlType resolve(SqlExpr.Column c,
            List<SqlSource> leaves) {
        SqlType found = null;
        for (SqlSource leaf : leaves) {
            if (leaf instanceof SqlSource.Dual) {
                continue;   // FROM-less: no alias, no columns (alias()
                            // throws by contract — caller-bug guard)
            }
            if (c.table() != null && !c.table().equals(leaf.alias())) {
                continue;
            }
            for (OutputCol o : leaf.outputs()) {
                if (o.name().equals(c.name())) {
                    if (found != null && !found.equals(o.type())) {
                        return null;   // ambiguous across sources
                    }
                    found = o.type();
                }
            }
        }
        return found;   // null = unresolvable (correlated outer ref …)
    }

    private static String shapeOf(SqlExpr e) {
        return e instanceof SqlExpr.Call c ? "Call:" + c.fn()
                : e.getClass().getSimpleName();
    }

    private static void classify(String cls) {
        CLASSES.computeIfAbsent(cls, k -> new LongAdder()).increment();
    }

    private static void sample(String cls, String witness) {
        List<String> ws = SAMPLES.computeIfAbsent(cls,
                k -> java.util.Collections.synchronizedList(
                        new ArrayList<>()));
        if (ws.size() < SAMPLES_PER_CLASS) {
            String ctx = CONTEXT.get();
            ws.add(witness + (ctx == null ? "" : " [" + ctx + "]"));
        }
    }

    /** A one-line expression sketch — enough to grep the emission seam,
     * never a full plan dump. */
    private static String sketch(SqlExpr e) {
        return switch (e) {
            case SqlExpr.Call c -> c.fn() + "(" + c.args().stream()
                    .map(SqlTypeCensus::sketchLeaf)
                    .collect(java.util.stream.Collectors.joining(","))
                    + ")";
            case SqlExpr.Cast c -> "CAST(" + sketchLeaf(c.value()) + " AS "
                    + c.target() + ")";
            default -> sketchLeaf(e);
        };
    }

    private static String sketchLeaf(SqlExpr e) {
        return switch (e) {
            case SqlExpr.Column c ->
                    (c.table() == null ? "" : c.table() + ".") + c.name();
            case SqlExpr.StringLit sl -> "'…'";
            case SqlExpr.IntLit il -> String.valueOf(il.value());
            case SqlExpr.Call c -> c.fn() + "(…)";
            default -> e.getClass().getSimpleName();
        };
    }

    /** Bounded witnesses for a class (attribution — the emission-seam
     * locator). */
    public static List<String> samplesOf(String cls) {
        return List.copyOf(SAMPLES.getOrDefault(cls, List.of()));
    }

    /** Every sampled class with its witnesses. */
    public static Map<String, List<String>> allSamples() {
        Map<String, List<String>> out = new java.util.TreeMap<>();
        SAMPLES.forEach((k, v) -> out.put(k, List.copyOf(v)));
        return out;
    }

    public static long mismatchCount() {
        return MISMATCH.sum();
    }

    public static String summary() {
        return "plans=" + PLANS.sum() + " cols: agree=" + AGREE.sum()
                + " admissible=" + ADMISSIBLE.sum()
                + " bottom-ok=" + BOTTOM_OK.sum()
                + " bottom-mult-backlog=" + BOTTOM_MULT.sum()
                + " mismatch=" + MISMATCH.sum() + " untyped=" + UNTYPED.sum();
    }

    /** The classified census, largest classes first. */
    public static List<String> classes(int top) {
        return CLASSES.entrySet().stream()
                .sorted((a, b) -> Long.compare(b.getValue().sum(),
                        a.getValue().sum()))
                .limit(top)
                .map(en -> en.getValue().sum() + "x " + en.getKey())
                .toList();
    }
}
