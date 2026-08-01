// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend.sql.dialect;

import com.legend.sql.SqlAgg;
import com.legend.sql.SqlExpr;
import com.legend.sql.SqlRewriter;
import com.legend.sql.SqlSelect;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * THE STRATEGY PASS (CARRIER_REDESIGN.md §1): rewrites SEMANTIC
 * collection nodes — ReduceCollection landed (R1); Membership,
 * CollectionSource, CollectionValue follow rung by rung — into this
 * dialect's emission. A node with no rule on this dialect survives to
 * the renderer's typed {@link DialectCapability} wall, budget-counted
 * by the portability sweep.
 *
 * <p>SINGLE-COMPILER CONTRACT (tenet #1, user-set, HARD): the Lowerer
 * emits only semantic nodes; every backend idiom — including DuckDB's
 * native {@code list()}/UNNEST/array literals — exists ONLY as a rule
 * here or a renderer hook the strategy selects. Each rung deletes the
 * corresponding direct emission upstream in the same commit
 * ({@code CarrierPurityRatchetTest} enforces the burn-down).
 */
public final class CarrierStrategies extends SqlRewriter {

    /** The dialect's collection CAPABILITIES (§2b: a record, not a
     * binary — SQLite/MariaDB have correlated explosion but no native
     * lists; H2 has neither; DuckDB has everything). Strategy rules
     * dispatch on these. */
    public record Caps(boolean nativeLists, boolean correlatedExplode,
            boolean jsonCarrier) {
        public static final Caps DUCKDB = new Caps(true, true, true);
        /** H2: no native lists, no correlated explosion (the ONLY probed
         * backend without it), JSON constructors only. */
        public static final Caps H2 = new Caps(false, false, false);
    }

    private final Caps caps;

    public CarrierStrategies(Caps caps) {
        this.caps = caps;
    }

    @Override
    protected com.legend.sql.SqlQuery select(SqlSelect s) {
        if (caps.nativeLists()) {
            return s;
        }
        // LITERAL-COLLECTION EXPLODE (R3a, witnessed): SELECT
        // unnest([e1..eN]) FROM dual — the portable form is UNION ALL of
        // one-row selects (duplicates preserved, order = branch order).
        // Conservative witnessed shape only: single projection, bare
        // Dual, no clauses.
        if (s.from() instanceof com.legend.sql.SqlSource.Dual
                && s.projections().size() == 1
                && s.where() == null && s.groupBy().isEmpty()
                && s.having() == null && s.qualify() == null
                && s.orderBy().isEmpty() && s.limit() == null
                && s.offset() == null && !s.distinct()
                && s.projections().get(0).expr() instanceof SqlExpr.Call u
                && u.fn() == com.legend.sql.SqlFn.UNNEST
                && u.args().size() == 1
                && u.args().get(0) instanceof SqlExpr.ArrayLit al
                && !al.elements().isEmpty()) {
            String alias = s.projections().get(0).alias();
            List<com.legend.sql.SqlQuery> branches = new ArrayList<>();
            for (SqlExpr el : al.elements()) {
                branches.add(s.withProjections(
                        List.of(new SqlSelect.Projection(el, alias)),
                        s.outputs()));
            }
            return new com.legend.sql.SqlUnion(branches, true, s.outputs());
        }
        return s;
    }

    /** LIST_* reducers over collection values ARE ReduceCollection —
     * mapped here so the fuse rules apply (portable mode only; DuckDB
     * keeps its native list fns). */
    private static final java.util.Map<com.legend.sql.SqlFn, SqlAgg.Fn>
            LIST_REDUCERS = java.util.Map.of(
                    com.legend.sql.SqlFn.LIST_MIN, SqlAgg.Fn.MIN,
                    com.legend.sql.SqlFn.LIST_MAX, SqlAgg.Fn.MAX,
                    com.legend.sql.SqlFn.LIST_SUM, SqlAgg.Fn.SUM,
                    com.legend.sql.SqlFn.LIST_AVG, SqlAgg.Fn.AVG,
                    com.legend.sql.SqlFn.LIST_MEDIAN, SqlAgg.Fn.MEDIAN);

    @Override
    protected SqlExpr expr(SqlExpr e) {
        if (caps.nativeLists()) {
            return e;
        }
        if (e instanceof SqlExpr.Call lc && lc.args().size() == 1) {
            SqlAgg.Fn red = LIST_REDUCERS.get(lc.fn());
            if (red != null) {
                SqlExpr fused = fuse(new SqlExpr.ReduceCollection(red,
                        lc.args().get(0), List.of()));
                if (fused != null) {
                    return fused;
                }
            }
        }
        // FUSION (R1, the engine's shape — pureToSQLQuery aggregates
        // inside the isolated grouped subselect, never a list value):
        // reducing a COLLECTING SUBSELECT pushes the reduction into it.
        //   ReduceCollection(name, (SELECT LIST(x) FROM ...), extras)
        //     -> (SELECT NAME(x, extras...) FROM ...)
        // The collect's ORDER KEYS carry over — the ordering contract
        // (insertion order via RowOrder) is preserved, not re-derived.
        if (e instanceof SqlExpr.Membership m) {
            SqlExpr rewritten = membershipRule(m);
            if (rewritten != null) {
                return rewritten;
            }
        }
        if (e instanceof SqlExpr.ReduceCollection rc) {
            SqlExpr fusedSub = fuse(rc);
            if (fusedSub != null) {
                return fusedSub;
            }
        }
        return e;
    }

    /** The fused grouped-subselect, or null when the collection operand
     * is not a recognized collect shape. Witnessed shapes (R1b, corpus):
     * a bare collect subselect, and LIST_TRANSFORM(collect, lambda) —
     * the element transform SUBSTITUTES into the collect projection
     * (same rows: the transform is element-wise). Order keys carry over
     * (the ordering contract, never re-derived). */
    private static @com.legend.Nullable SqlExpr fuse(
            SqlExpr.ReduceCollection rc) {
        SqlExpr coll = rc.collection();
        SqlExpr.Lambda transform = null;
        if (coll instanceof SqlExpr.Call c
                && c.fn() == com.legend.sql.SqlFn.LIST_TRANSFORM
                && c.args().size() == 2
                && c.args().get(1) instanceof SqlExpr.Lambda lam
                && lam.params().size() == 1) {
            transform = lam;
            coll = c.args().get(0);
        }
        // SORTED join (witnessed R1d: sort()->joinStrings): LIST_SORT
        // between collect and transform — the fused STRING_AGG orders by
        // the RAW collected value (sort precedes the element transform).
        boolean sorted = false;
        if (coll instanceof SqlExpr.Call sc
                && sc.fn() == com.legend.sql.SqlFn.LIST_SORT
                && sc.args().size() == 1) {
            sorted = true;
            coll = sc.args().get(0);
        }
        // LITERAL collection (witnessed R1c: makeString over TDS-row
        // cells): the elements are compile-time-known — STRING_AGG
        // expands to the CONCAT chain t(e1)||sep||t(e2)||…; no subquery
        // at all. STRING_AGG only (join semantics).
        if (coll instanceof SqlExpr.ArrayLit al
                && rc.reducer() == SqlAgg.Fn.STRING_AGG
                && rc.extras().size() == 1 && !al.elements().isEmpty()) {
            return concatJoin(al.elements(), transform, rc.extras().get(0));
        }
        // SINGLETON-FLATTEN UNWRAP (witnessed R4: calendar date ranges —
        // each row carries a ONE-element ArrayLit; FLATTEN(collect) of
        // singletons IS a collect of the elements): rewrite the inner
        // projection to the bare element and drop the FLATTEN, then the
        // generic collect rules apply (MIN/STRING_AGG/sorted...).
        if (coll instanceof SqlExpr.Call ufl
                && ufl.fn() == com.legend.sql.SqlFn.LIST_FLATTEN
                && ufl.args().size() == 1
                && ufl.args().get(0) instanceof SqlExpr.ScalarSubquery usq
                && usq.subquery() instanceof SqlSelect usel
                && usel.projections().size() == 1
                && usel.projections().get(0).expr()
                        instanceof SqlAgg.Reducer ucollect
                && ucollect.fn() == SqlAgg.Fn.LIST
                && !ucollect.distinct()
                && ucollect.args().size() == 1
                && ucollect.args().get(0) instanceof SqlExpr.Column ucol
                && usel.from() instanceof com.legend.sql.SqlSource.Subselect
                        usub
                && usub.inner() instanceof SqlSelect uinner
                && uinner.projections().size() == 1
                && ucol.name().equals(uinner.projections().get(0).alias())
                && uinner.projections().get(0).expr()
                        instanceof SqlExpr.ArrayLit ual
                && ual.elements().size() == 1) {
            SqlSelect newInner = uinner.withProjections(
                    List.of(new SqlSelect.Projection(ual.elements().get(0),
                            uinner.projections().get(0).alias())),
                    uinner.outputs());
            coll = new SqlExpr.ScalarSubquery(usel.withFrom(
                    new com.legend.sql.SqlSource.Subselect(newInner,
                            usub.alias(), usub.frameName())));
        }
        // ROW-MAJOR cell collect (witnessed R1c: rowMajorCellList —
        // FLATTEN(collect-of-ArrayLit)): fuse to STRING_AGG over the
        // per-row CONCAT of transformed cells, sep between rows AND
        // between cells (row-major join is separator-uniform).
        if (coll instanceof SqlExpr.Call fl
                && fl.fn() == com.legend.sql.SqlFn.LIST_FLATTEN
                && fl.args().size() == 1
                && fl.args().get(0) instanceof SqlExpr.ScalarSubquery fsq
                && fsq.subquery() instanceof SqlSelect fsel
                && fsel.projections().size() == 1
                && fsel.projections().get(0).expr()
                        instanceof SqlAgg.Reducer fcollect
                && fcollect.fn() == SqlAgg.Fn.LIST
                && !fcollect.distinct()
                && fcollect.args().size() == 1
                && fcollect.args().get(0) instanceof SqlExpr.ArrayLit cells
                && rc.reducer() == SqlAgg.Fn.STRING_AGG
                && rc.extras().size() == 1
                && !cells.elements().isEmpty()) {
            SqlExpr sep = rc.extras().get(0);
            if (!sorted) {
                SqlExpr rowJoined = concatJoin(cells.elements(), transform,
                        sep);
                SqlAgg.Reducer fused = new SqlAgg.Reducer(
                        SqlAgg.Fn.STRING_AGG, List.of(rowJoined, sep),
                        false, fcollect.orderBy());
                return new SqlExpr.ScalarSubquery(fsel.withProjections(
                        List.of(new SqlSelect.Projection(fused,
                                fsel.projections().get(0).alias())),
                        fsel.outputs()));
            }
            // SORTED row-major join (witnessed R1d): cells sort GLOBALLY
            // across rows — per-row CONCAT cannot express it. The
            // no-explode portable form: UNION ALL one branch per
            // compile-time cell, then STRING_AGG(t(v), sep ORDER BY v).
            List<com.legend.sql.SqlQuery> branches = new ArrayList<>();
            for (SqlExpr cell : cells.elements()) {
                branches.add(fsel.withProjections(
                        List.of(new SqlSelect.Projection(cell, "v")),
                        List.of()));
            }
            com.legend.sql.SqlUnion union =
                    new com.legend.sql.SqlUnion(branches, true, List.of());
            SqlExpr vRead = new SqlExpr.Column("_cells", "v");
            SqlExpr tv = transform == null ? vRead
                    : substParam(transform.body(),
                            transform.params().get(0), vRead);
            SqlAgg.Reducer fused = new SqlAgg.Reducer(SqlAgg.Fn.STRING_AGG,
                    List.of(tv, sep), false,
                    List.of(SqlSelect.SortKey.asc(vRead)));
            return new SqlExpr.ScalarSubquery(SqlSelect.starOf(
                            new com.legend.sql.SqlSource.Subselect(union,
                                    "_cells", null))
                    .withProjections(List.of(new SqlSelect.Projection(
                            fused, null)), List.of()));
        }
        if (!(coll instanceof SqlExpr.ScalarSubquery sq)
                || !(sq.subquery() instanceof SqlSelect sel)
                || sel.projections().size() != 1
                || !(sel.projections().get(0).expr()
                        instanceof SqlAgg.Reducer collect)
                || collect.fn() != SqlAgg.Fn.LIST
                || collect.distinct()
                || collect.args().size() != 1) {
            return null;
        }
        SqlExpr raw = collect.args().get(0);
        SqlExpr value = raw;
        if (transform != null) {
            value = substParam(transform.body(), transform.params().get(0),
                    raw);
        }
        List<SqlExpr> args = new ArrayList<>();
        args.add(value);
        args.addAll(rc.extras());
        SqlAgg.Reducer fused = new SqlAgg.Reducer(rc.reducer(), args, false,
                sorted ? List.of(SqlSelect.SortKey.asc(raw))
                        : collect.orderBy());
        return new SqlExpr.ScalarSubquery(sel.withProjections(
                List.of(new SqlSelect.Projection(fused,
                        sel.projections().get(0).alias())),
                sel.outputs()));
    }

    /** {@code t(e1) || sep || t(e2) || …} over compile-time elements. */
    private static SqlExpr concatJoin(List<SqlExpr> elements,
            SqlExpr.@com.legend.Nullable Lambda transform, SqlExpr sep) {
        SqlExpr out = null;
        for (SqlExpr e : elements) {
            SqlExpr v = transform == null ? e
                    : substParam(transform.body(), transform.params().get(0),
                            e);
            out = out == null ? v
                    : SqlExpr.Call.of(com.legend.sql.SqlFn.CONCAT,
                            SqlExpr.Call.of(com.legend.sql.SqlFn.CONCAT,
                                    out, sep), v);
        }
        return java.util.Objects.requireNonNull(out);
    }

    /** Portable membership (R2). LITERAL collection: the OR-chain
     * {@code needle = e1 OR needle = e2 ...} — EXACT list_contains
     * semantics (probed): NULL needle -> NULL, absent -> FALSE once
     * NULL-literal elements are DROPPED (x = NULL is never true, which
     * is precisely list_contains's no-match-on-NULL-element), empty ->
     * FALSE. COLLECT subselect: EXISTS with the equality pushed into
     * the WHERE (correlation preserved; the emission sites wrap
     * COALESCE(_, false), which absorbs the NULL-needle edge). */
    private static @com.legend.Nullable SqlExpr membershipRule(
            SqlExpr.Membership m) {
        if (m.collection() instanceof SqlExpr.ArrayLit al) {
            SqlExpr chain = null;
            for (SqlExpr el : al.elements()) {
                if (el instanceof SqlExpr.NullLit) {
                    continue;
                }
                SqlExpr eq = SqlExpr.Call.of(com.legend.sql.SqlFn.EQUAL,
                        m.needle(), el);
                chain = chain == null ? eq
                        : SqlExpr.Call.of(com.legend.sql.SqlFn.OR, chain, eq);
            }
            return chain == null ? new SqlExpr.BoolLit(false) : chain;
        }
        if (m.collection() instanceof SqlExpr.ScalarSubquery sq
                && sq.subquery() instanceof SqlSelect sel
                && sel.projections().size() == 1
                && sel.projections().get(0).expr()
                        instanceof SqlAgg.Reducer collect
                && collect.fn() == SqlAgg.Fn.LIST
                && !collect.distinct()
                && collect.args().size() == 1) {
            SqlExpr eq = SqlExpr.Call.of(com.legend.sql.SqlFn.EQUAL,
                    collect.args().get(0), m.needle());
            SqlSelect inner = sel.withProjections(
                    List.of(new SqlSelect.Projection(
                            new SqlExpr.IntLit(1), null)), List.of());
            SqlSelect withEq = inner.withWhere(inner.where() == null ? eq
                    : SqlExpr.Call.of(com.legend.sql.SqlFn.AND,
                            inner.where(), eq));
            return new SqlExpr.Exists(withEq);
        }
        return null;
    }

    /** Replace bare reads of the lambda parameter with {@code value}. */
    private static SqlExpr substParam(SqlExpr body, String param,
            SqlExpr value) {
        if (body instanceof SqlExpr.Column c && c.table() == null
                && param.equals(c.name())) {
            return value;
        }
        List<SqlExpr> kids = body.children();
        if (kids.isEmpty()) {
            return body;
        }
        List<SqlExpr> mapped = new ArrayList<>(kids.size());
        boolean changed = false;
        for (SqlExpr k : kids) {
            SqlExpr m = substParam(k, param, value);
            changed |= m != k;
            mapped.add(m);
        }
        return changed ? body.withChildren(mapped) : body;
    }
}
