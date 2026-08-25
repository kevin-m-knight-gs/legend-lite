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
import com.legend.sql.TypeFact;

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


    /** The admissibility relation MOVED to {@link SqlTyping#admissible}
     * (the label flip encodes it at SqlSelect construction; the census
     * reads the SAME relation — one owner). */
    private static boolean admissible(SqlType declared, SqlType computed) {
        return SqlTyping.admissible(declared, computed);
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

    private static final LongAdder WIRE_AGREE = new LongAdder();
    private static final LongAdder WIRE_DELIVERED = new LongAdder();
    private static final LongAdder WIRE_ADOPT_PENDING = new LongAdder();
    private static final LongAdder WIRE_NULL_AMBIG = new LongAdder();
    private static final LongAdder WIRE_DIVERGE = new LongAdder();
    private static final LongAdder WIRE_UNKNOWN = new LongAdder();

    /** CONTRACT PROGRAM step 1 — THE WIRE CENSUS: our label (the
     * contract) vs the ResultSet's OWN metadata (the factual wire, no
     * extra round trip — it rides with the data). Ground truth per
     * dialect, total expression coverage, zero inference. Phase 1 it
     * MEASURES the guess-vs-reality gap (each divergence adjudicates
     * adopt / conform / fix-emitter); phase 3 it is the always-green
     * tripwire. FAILURE-ISOLATED like every instrument (the
     * Dual.alias lesson): a metadata hiccup counts as unknown, never
     * throws into execution. */
    public static void probeWire(SqlQuery plan, java.sql.ResultSet rs,
            String dialect) {
        try {
            java.sql.ResultSetMetaData md = rs.getMetaData();
            List<OutputCol> outs = plan.outputs();
            if (md.getColumnCount() != outs.size()) {
                WIRE_UNKNOWN.increment();
                return;
            }
            for (int i = 0; i < outs.size(); i++) {
                String label = wireSpelling(outs.get(i).type());
                String meta = normalizeMeta(md.getColumnTypeName(i + 1));
                if (label == null || meta.isEmpty()) {
                    WIRE_UNKNOWN.increment();
                    continue;
                }
                if (label.equals(meta)) {
                    WIRE_AGREE.increment();
                } else if (delivers(outs.get(i).type(), meta)) {
                    // THE DELIVERY RELATION (adjudicated 2026-08-23):
                    // value-subset narrowing (every INTEGER fits the
                    // BIGINT contract; DECIMAL(p,s) fits DECIMAL(P>=p,s))
                    // and the registered carrier conventions — the
                    // admissible() pairs read at the wire
                    WIRE_DELIVERED.increment();
                    classify("wire-delivered[" + dialect + "] " + label
                            + " <- " + meta);
                } else if (meta.equals("HUGEINT")
                        && label.equals("BIGINT")) {
                    // ADOPT-PENDING (the testLargePlus adjudication):
                    // integer aggregates legitimately exceed 64 bits —
                    // pure semantics sides with the WIRE; the CONTRACT
                    // is what widens (at construction, the builder
                    // slice). Counted visibly until labels widen —
                    // decode is BigInteger-safe today.
                    WIRE_ADOPT_PENDING.increment();
                    classify("wire-adopt-pending[" + dialect
                            + "] BIGINT <- HUGEINT");
                    sample("wire-adopt-pending[" + dialect
                            + "] BIGINT <- HUGEINT", outs.get(i).name());
                } else if (meta.equals("INTEGER")
                        && !integerFamily(outs.get(i).type())) {
                    // AMBIGUOUS: DuckDB metadata spells an all-NULL
                    // column INTEGER — indistinguishable from a real
                    // integer wire without VALUE evidence (the decode
                    // tripwire's territory, with the nullability
                    // re-label). Own bucket, neither agree nor diverge.
                    WIRE_NULL_AMBIG.increment();
                    classify("wire-int-or-null[" + dialect + "] label="
                            + label);
                } else {
                    String cls = "wire[" + dialect + "] label=" + label
                            + " <> meta=" + meta;
                    WIRE_DIVERGE.increment();
                    classify(cls);
                    sample(cls, outs.get(i).name());
                }
            }
        } catch (java.sql.SQLException | RuntimeException e) {
            // instrument isolation: measurement must never throw into
            // execution — an unreadable metadata is a counted unknown
            WIRE_UNKNOWN.increment();
        }
    }

    /** Does the wire's factual type SATISFY the label's contract
     * without value loss? Exact is handled by the caller; this covers
     * value-subset narrowing and the registered carrier conventions
     * (metaToType + admissible — ONE relation, read from both sides). */
    private static boolean delivers(SqlType label, String meta) {
        // the kind-faithful carrier: spelling TEXT is its physical form
        // on every backend (F10 proper — the registered pair)
        if (label == SqlType.Scalar.LITERAL && meta.equals("VARCHAR")) {
            return true;
        }
        // integer-width chain: every narrower integer fits
        if (label == SqlType.Scalar.BIGINT
                && (meta.equals("INTEGER") || meta.equals("SMALLINT")
                        || meta.equals("TINYINT"))) {
            return true;
        }
        if (label == SqlType.Scalar.INTEGER
                && (meta.equals("SMALLINT") || meta.equals("TINYINT"))) {
            return true;
        }
        // decimal narrowing at the same scale
        if (label instanceof SqlType.Decimal d && meta.startsWith("DECIMAL(")) {
            try {
                String[] ps = meta.substring(8, meta.length() - 1).split(",");
                int p = Integer.parseInt(ps[0].trim());
                int sc = Integer.parseInt(ps[1].trim());
                return sc == d.scale() && p <= d.precision();
            } catch (NumberFormatException | IndexOutOfBoundsException e) {
                return false;
            }
        }
        SqlType mt = metaToType(meta);
        return mt != null && admissibleWire(label, mt);
    }

    private static boolean integerFamily(SqlType t) {
        return t == SqlType.Scalar.BIGINT || t == SqlType.Scalar.INTEGER
                || t == SqlType.Scalar.HUGEINT;
    }

    /** The carrier conventions read at the wire: the SAME registered
     * pairs as {@link #admissible} (label may be delivered by its
     * convention carrier). */
    private static boolean admissibleWire(SqlType label, SqlType meta) {
        return admissible(label, meta);
    }

    private static @com.legend.Nullable SqlType metaToType(String meta) {
        return switch (meta) {
            case "VARCHAR" -> SqlType.Scalar.VARCHAR;
            case "JSON" -> SqlType.Scalar.JSON;
            case "DATE" -> SqlType.Scalar.DATE;
            case "TIMESTAMP" -> SqlType.Scalar.TIMESTAMP;
            case "DOUBLE" -> SqlType.Scalar.DOUBLE;
            case "BOOLEAN" -> SqlType.Scalar.BOOLEAN;
            case "BIGINT" -> SqlType.Scalar.BIGINT;
            default -> null;
        };
    }

    /** Our label in the wire's own vocabulary (DuckDB-family type
     * names), for comparison against getColumnTypeName. Composites
     * compare by HEAD (full struct field lists are driver-fragile).
     * Null = no spelling (compare impossible — counted unknown). */
    private static @com.legend.Nullable String wireSpelling(SqlType t) {
        if (t instanceof SqlType.Scalar sc) {
            return sc.name();
        }
        if (t instanceof SqlType.Decimal d) {
            return "DECIMAL(" + d.precision() + "," + d.scale() + ")";
        }
        if (t instanceof SqlType.Array) {
            return "ARRAY";
        }
        if (t instanceof SqlType.Struct) {
            return "STRUCT";
        }
        if (t instanceof SqlType.Map) {
            return "MAP";
        }
        return null;
    }

    private static String normalizeMeta(@com.legend.Nullable String name) {
        if (name == null) {
            return "";
        }
        String n = name.toUpperCase(java.util.Locale.ROOT).trim();
        if (n.startsWith("STRUCT")) {
            return "STRUCT";
        }
        if (n.startsWith("MAP")) {
            return "MAP";
        }
        if (n.endsWith("[]") || n.startsWith("ARRAY")) {
            return "ARRAY";
        }
        // driver spelling families -> our scalar names
        if (n.equals("CHARACTER VARYING") || n.startsWith("VARCHAR")) {
            return "VARCHAR";
        }
        if (n.startsWith("DECIMAL") || n.startsWith("NUMERIC")) {
            return n.replace("NUMERIC", "DECIMAL").replace(" ", "");
        }
        if (n.startsWith("TIMESTAMP")) {
            return "TIMESTAMP";
        }
        return n;
    }

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
        List<SqlSource> sources = new ArrayList<>();
        collect(s.from(), sources);
        for (SqlSource src : sources) {
            if (src instanceof SqlSource.Subselect sub) {
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
            // the TREE is the one type channel (the judge deleted
            // 2026-08-24 — its parity with the stored types was pinned
            // at zero divergence on every lane before deletion)
            TypeFact v = e.type();
            switch (v) {
                case TypeFact.Bottom b -> {
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
                case TypeFact.Unknown u -> {
                    UNTYPED.increment();
                    String cls = "untyped: " + shapeOf(e);
                    classify(cls);
                    sample(cls, declared.name() + " := " + sketch(e));
                }
                case TypeFact.Typed t -> {
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

    private static String shapeOf(SqlExpr e) {
        return e instanceof SqlExpr.Call c ? "Call:" + c.fn()
                : e.getClass().getSimpleName();
    }

    /** Same-package instruments (Executor's carrier-migration census)
     * count through the one classifier — a named class per probe. */
    static void classifyExternal(String cls) {
        classify(cls);
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

    /** Projection roots the tree cannot type — RULE coverage debt
     * (and, post-judge, the leaf-regression tripwire: a new unstamped
     * construction site GROWS this). Ceiling-pinned in the corpus
     * runner; ratchets down as rules land. */
    public static long untypedCount() {
        return UNTYPED.sum();
    }

    public static long wireDivergeCount() {
        return WIRE_DIVERGE.sum();
    }

    public static long wireAdoptPendingCount() {
        return WIRE_ADOPT_PENDING.sum();
    }

    public static String summary() {
        return "plans=" + PLANS.sum() + " cols: agree=" + AGREE.sum()
                + " admissible=" + ADMISSIBLE.sum()
                + " bottom-ok=" + BOTTOM_OK.sum()
                + " bottom-mult-backlog=" + BOTTOM_MULT.sum()
                + " mismatch=" + MISMATCH.sum() + " untyped=" + UNTYPED.sum()
                + " | wire: agree=" + WIRE_AGREE.sum()
                + " delivered=" + WIRE_DELIVERED.sum()
                + " adopt-pending=" + WIRE_ADOPT_PENDING.sum()
                + " int-or-null=" + WIRE_NULL_AMBIG.sum()
                + " diverge=" + WIRE_DIVERGE.sum()
                + " unknown=" + WIRE_UNKNOWN.sum();
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
