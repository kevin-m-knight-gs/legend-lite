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
    private static final LongAdder MISMATCH = new LongAdder();
    private static final LongAdder UNTYPED = new LongAdder();
    /** declared-&gt;computed pair (mismatch) / expr shape (untyped)
     * &rarr; occurrence count. */
    private static final Map<String, LongAdder> CLASSES =
            new ConcurrentHashMap<>();

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
            SqlType computed = SqlTyping.of(e, col -> resolve(col, leaves));
            if (computed == null) {
                UNTYPED.increment();
                classify("untyped: " + shapeOf(e));
            } else if (computed.equals(declared.type())) {
                AGREE.increment();
            } else {
                MISMATCH.increment();
                classify("declared " + declared.type() + " <> computed "
                        + computed);
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

    public static long mismatchCount() {
        return MISMATCH.sum();
    }

    public static String summary() {
        return "plans=" + PLANS.sum() + " cols: agree=" + AGREE.sum()
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
