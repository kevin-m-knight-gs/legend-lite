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
        // EXPLODE PLACEMENTS (R3a + R5b, witnessed): a single-projection
        // SELECT unnest(arg) with no other clauses — the portable form
        // is decided by the ARG shape (literal / NULL / collect
        // subselect / sorted collect / concat / through-subselect
        // literal cells). Unwitnessed args survive to the renderer wall.
        if (s.projections().size() == 1
                && s.where() == null && s.groupBy().isEmpty()
                && s.having() == null && s.qualify() == null
                && s.orderBy().isEmpty() && s.limit() == null
                && s.offset() == null && !s.distinct()
                && s.projections().get(0).expr() instanceof SqlExpr.Call u
                && u.fn() == com.legend.sql.SqlFn.UNNEST
                && u.args().size() == 1) {
            com.legend.sql.SqlQuery ex = explode(u.args().get(0), s,
                    s.projections().get(0).alias());
            if (ex != null) {
                return ex;
            }
        }
        return s;
    }

    /** The portable form of {@code SELECT unnest(arg) AS alias} for the
     * WITNESSED arg shapes (R5b), or null (the renderer wall stays).
     * Except the through-subselect arm, every rewriting arm requires a
     * bare Dual source (the exploded form replaces the whole select). */
    private @com.legend.Nullable com.legend.sql.SqlQuery explode(SqlExpr arg,
            SqlSelect s, @com.legend.Nullable String alias) {
        boolean dual = s.from() instanceof com.legend.sql.SqlSource.Dual;
        // unnest(NULL) yields ZERO rows (probed on DuckDB) — keep the
        // select shape, kill it with WHERE FALSE.
        if (arg instanceof SqlExpr.NullLit) {
            return s.withProjections(List.of(new SqlSelect.Projection(
                            new SqlExpr.NullLit(), alias)), s.outputs())
                    .withWhere(new SqlExpr.BoolLit(false));
        }
        // LITERAL-COLLECTION EXPLODE (R3a): UNION ALL of one-row selects
        // (duplicates preserved, order = branch order).
        if (dual && arg instanceof SqlExpr.ArrayLit al
                && !al.elements().isEmpty()) {
            List<com.legend.sql.SqlQuery> branches = new ArrayList<>();
            for (SqlExpr el : al.elements()) {
                branches.add(s.withProjections(
                        List.of(new SqlSelect.Projection(el, alias)),
                        s.outputs()));
            }
            return new com.legend.sql.SqlUnion(branches, true, s.outputs());
        }
        // EXPLODE-OF-COLLECT (R5b, witnessed): unnest((SELECT LIST(x)
        // FROM ...)) IS the collecting row set — the inner select
        // projecting the bare element, collect order keys carried over.
        if (dual) {
            SqlSelect coll = collectSelect(arg);
            if (coll != null) {
                SqlAgg.Reducer collect =
                        (SqlAgg.Reducer) coll.projections().get(0).expr();
                return coll.withProjections(
                                List.of(new SqlSelect.Projection(
                                        collect.args().get(0), alias)),
                                s.outputs())
                        .withOrderBy(collect.orderBy());
            }
        }
        // SORTED EXPLODE (R5b, witnessed): unnest(LIST_SORT(collect)) —
        // list_sort is ASC NULLS LAST (probed on DuckDB); the collect
        // keys stay secondary (stable-sort parity).
        if (dual && arg instanceof SqlExpr.Call so
                && so.fn() == com.legend.sql.SqlFn.LIST_SORT
                && so.args().size() == 1) {
            SqlSelect coll = collectSelect(so.args().get(0));
            if (coll != null) {
                SqlAgg.Reducer collect =
                        (SqlAgg.Reducer) coll.projections().get(0).expr();
                SqlExpr raw = collect.args().get(0);
                List<SqlSelect.SortKey> keys = new ArrayList<>();
                keys.add(new SqlSelect.SortKey(raw, true,
                        SqlSelect.SortKey.NullOrder.NULLS_LAST, null));
                keys.addAll(collect.orderBy());
                return coll.withProjections(
                                List.of(new SqlSelect.Projection(raw, alias)),
                                s.outputs())
                        .withOrderBy(keys);
            }
        }
        // CONCAT EXPLODE (R5b, witnessed): unnest(list_concat(a, b)) =
        // the branches of a then the branches of b.
        if (dual && arg instanceof SqlExpr.Call cc
                && cc.fn() == com.legend.sql.SqlFn.LIST_CONCAT
                && cc.args().size() >= 2) {
            List<com.legend.sql.SqlQuery> branches = new ArrayList<>();
            for (SqlExpr arm : cc.args()) {
                com.legend.sql.SqlQuery b = explode(arm, s, alias);
                if (b == null) {
                    return null;
                }
                if (b instanceof com.legend.sql.SqlUnion bu) {
                    branches.addAll(bu.branches());
                } else {
                    branches.add(b);
                }
            }
            return new com.legend.sql.SqlUnion(branches, true, s.outputs());
        }
        // THROUGH-SUBSELECT CELLS (R5b, witnessed): SELECT unnest(c)
        // FROM (SELECT [e1..ek] AS c FROM T ...) — k branches of the
        // INNER select each projecting one cell. k = 1 is exact; k > 1
        // is column-major row order where DuckDB unnest is row-major —
        // an observed divergence fails the sweep loudly, never silently.
        if (arg instanceof SqlExpr.Column c
                && s.from() instanceof com.legend.sql.SqlSource.Subselect us
                && us.inner() instanceof SqlSelect inner
                && !inner.distinct() && inner.groupBy().isEmpty()
                && inner.having() == null && inner.qualify() == null
                && inner.orderBy().isEmpty() && inner.limit() == null
                && inner.offset() == null) {
            SqlExpr src = null;
            for (SqlSelect.Projection ip : inner.projections()) {
                if (c.name().equals(ip.alias())) {
                    src = ip.expr();
                }
            }
            if (src instanceof SqlExpr.ArrayLit cells
                    && !cells.elements().isEmpty()) {
                List<com.legend.sql.SqlQuery> branches = new ArrayList<>();
                for (SqlExpr cell : cells.elements()) {
                    branches.add(inner.withProjections(
                            List.of(new SqlSelect.Projection(cell, alias)),
                            s.outputs()));
                }
                return branches.size() == 1 ? branches.get(0)
                        : new com.legend.sql.SqlUnion(branches, true,
                                s.outputs());
            }
        }
        return null;
    }

    /** The collect SELECT beneath a ScalarSubquery — single projection,
     * a bare non-distinct one-arg LIST reducer, no other clauses — or
     * null. */
    private static @com.legend.Nullable SqlSelect collectSelect(SqlExpr e) {
        return e instanceof SqlExpr.ScalarSubquery sq
                && sq.subquery() instanceof SqlSelect sel
                && sel.projections().size() == 1
                && sel.projections().get(0).expr() instanceof SqlAgg.Reducer r
                && r.fn() == SqlAgg.Fn.LIST && !r.distinct()
                && r.args().size() == 1
                && sel.groupBy().isEmpty() && sel.having() == null
                && sel.qualify() == null && sel.orderBy().isEmpty()
                && sel.limit() == null && sel.offset() == null
                && !sel.distinct()
                ? sel : null;
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
        // LIST_CONCAT over compile-time collections FOLDS (R5b,
        // witnessed: month-name lists concatenated before explode).
        // Bottom-up walk: nested concats fold inside-out.
        if (e instanceof SqlExpr.Call cf
                && cf.fn() == com.legend.sql.SqlFn.LIST_CONCAT
                && !cf.args().isEmpty()
                && cf.args().stream()
                        .allMatch(x -> x instanceof SqlExpr.ArrayLit)) {
            List<SqlExpr> els = new ArrayList<>();
            for (SqlExpr x : cf.args()) {
                els.addAll(((SqlExpr.ArrayLit) x).elements());
            }
            return new SqlExpr.ArrayLit(els);
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
        if (e instanceof SqlExpr.Call lg
                && lg.fn() == com.legend.sql.SqlFn.LIST_GET
                && lg.args().size() == 2) {
            SqlExpr got = listGetRule(lg.args().get(0), lg.args().get(1));
            if (got != null) {
                return got;
            }
        }
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
        // THROUGH-SUBSELECT ROW-MAJOR (R5c, witnessed): FLATTEN(collect)
        // where the collected COLUMN resolves to an ArrayLit in the
        // inner subselect's projection — the cells live in INNER scope,
        // so the per-row CONCAT substitutes into the INNER projection
        // (unsorted), and the sorted global-cell form explodes per-cell
        // branches of the INNER select.
        if (coll instanceof SqlExpr.Call fl2
                && fl2.fn() == com.legend.sql.SqlFn.LIST_FLATTEN
                && fl2.args().size() == 1
                && fl2.args().get(0) instanceof SqlExpr.ScalarSubquery fsq2
                && fsq2.subquery() instanceof SqlSelect fsel2
                && fsel2.projections().size() == 1
                && fsel2.projections().get(0).expr()
                        instanceof SqlAgg.Reducer fc2
                && fc2.fn() == SqlAgg.Fn.LIST && !fc2.distinct()
                && fc2.args().size() == 1
                && fc2.args().get(0) instanceof SqlExpr.Column fcol
                && fsel2.from() instanceof com.legend.sql.SqlSource.Subselect
                        fsub
                && fsub.inner() instanceof SqlSelect finner
                && !finner.distinct() && finner.groupBy().isEmpty()
                && finner.having() == null && finner.qualify() == null
                && finner.limit() == null && finner.offset() == null
                && rc.reducer() == SqlAgg.Fn.STRING_AGG
                && rc.extras().size() == 1) {
            SqlExpr src = null;
            int srcIx = -1;
            for (int i = 0; i < finner.projections().size(); i++) {
                if (fcol.name().equals(finner.projections().get(i).alias())) {
                    src = finner.projections().get(i).expr();
                    srcIx = i;
                }
            }
            if (src instanceof SqlExpr.ArrayLit cells2
                    && !cells2.elements().isEmpty()) {
                SqlExpr sep = rc.extras().get(0);
                if (!sorted) {
                    SqlExpr rowJoined = concatJoin(cells2.elements(),
                            transform, sep);
                    List<SqlSelect.Projection> np =
                            new ArrayList<>(finner.projections());
                    np.set(srcIx, new SqlSelect.Projection(rowJoined,
                            fcol.name()));
                    SqlAgg.Reducer fused = new SqlAgg.Reducer(
                            SqlAgg.Fn.STRING_AGG, List.of(fcol, sep), false,
                            fc2.orderBy());
                    return new SqlExpr.ScalarSubquery(fsel2
                            .withFrom(new com.legend.sql.SqlSource.Subselect(
                                    finner.withProjections(np,
                                            finner.outputs()),
                                    fsub.alias(), fsub.frameName()))
                            .withProjections(List.of(
                                    new SqlSelect.Projection(fused,
                                            fsel2.projections().get(0)
                                                    .alias())),
                                    fsel2.outputs()));
                }
                List<com.legend.sql.SqlQuery> branches = new ArrayList<>();
                for (SqlExpr cell : cells2.elements()) {
                    branches.add(finner.withProjections(
                            List.of(new SqlSelect.Projection(cell, "v")),
                            List.of()));
                }
                com.legend.sql.SqlUnion union =
                        new com.legend.sql.SqlUnion(branches, true,
                                List.of());
                SqlExpr vRead = new SqlExpr.Column("_cells", "v");
                SqlExpr tv = transform == null ? vRead
                        : substParam(transform.body(),
                                transform.params().get(0), vRead);
                SqlAgg.Reducer fused = new SqlAgg.Reducer(
                        SqlAgg.Fn.STRING_AGG, List.of(tv, sep), false,
                        List.of(SqlSelect.SortKey.asc(vRead)));
                return new SqlExpr.ScalarSubquery(SqlSelect.starOf(
                                new com.legend.sql.SqlSource.Subselect(union,
                                        "_cells", null))
                        .withProjections(List.of(new SqlSelect.Projection(
                                fused, null)), List.of()));
            }
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

    /** Portable LIST_GET (R5c, witnessed shapes; list_extract contract
     * probed: 1-based, -1 = last, 0 and out-of-range = NULL). Returns
     * null when the shape is unwitnessed (the renderer wall stays). */
    private static @com.legend.Nullable SqlExpr listGetRule(SqlExpr coll,
            SqlExpr idx) {
        if (!(idx instanceof SqlExpr.IntLit ix)) {
            return null;
        }
        long i = ix.value();
        // compile-time pick over a literal collection
        if (coll instanceof SqlExpr.ArrayLit al) {
            int n = al.elements().size();
            long pos = i > 0 ? i : i < 0 ? n + i + 1 : 0;
            return pos >= 1 && pos <= n ? al.elements().get((int) (pos - 1))
                    : new SqlExpr.NullLit();
        }
        // first-non-null idiom: LIST_FILTER(lit, x | x IS NOT NULL)[1]
        // IS COALESCE over the elements
        if (i == 1 && coll instanceof SqlExpr.Call ft
                && ft.fn() == com.legend.sql.SqlFn.LIST_FILTER
                && ft.args().size() == 2
                && ft.args().get(0) instanceof SqlExpr.ArrayLit fal
                && !fal.elements().isEmpty()
                && ft.args().get(1) instanceof SqlExpr.Lambda lam
                && lam.params().size() == 1
                && lam.body() instanceof SqlExpr.Call nn
                && nn.fn() == com.legend.sql.SqlFn.IS_NOT_NULL
                && nn.args().size() == 1
                && nn.args().get(0) instanceof SqlExpr.Column pc
                && pc.table() == null
                && lam.params().get(0).equals(pc.name())) {
            return fal.elements().size() == 1 ? fal.elements().get(0)
                    : new SqlExpr.Call(com.legend.sql.SqlFn.COALESCE,
                            fal.elements());
        }
        // token pick over a split: LIST_GET(SPLIT(s, sep), n) is
        // SPLIT_PART(s, sep, n) GUARDED to NULL when the token is
        // missing (differential-caught: list_extract OOB is NULL,
        // split_part is '') — literal single-char separator and
        // positive literal index only (the H2 spelling's domain).
        if (i >= 1 && coll instanceof SqlExpr.Call sp
                && sp.fn() == com.legend.sql.SqlFn.SPLIT
                && sp.args().size() == 2
                && sp.args().get(1) instanceof SqlExpr.StringLit sepLit
                && sepLit.value().length() == 1) {
            SqlExpr s0 = sp.args().get(0);
            SqlExpr missing = SqlExpr.Call.of(com.legend.sql.SqlFn.LESS,
                    SqlExpr.Call.of(com.legend.sql.SqlFn.MINUS,
                            SqlExpr.Call.of(com.legend.sql.SqlFn.LENGTH, s0),
                            SqlExpr.Call.of(com.legend.sql.SqlFn.LENGTH,
                                    SqlExpr.Call.of(
                                            com.legend.sql.SqlFn.REPLACE,
                                            s0, sp.args().get(1),
                                            new SqlExpr.StringLit("")))),
                    new SqlExpr.IntLit(i - 1));
            SqlExpr part = SqlExpr.Call.of(com.legend.sql.SqlFn.SPLIT_PART,
                    s0, sp.args().get(1), idx);
            return i == 1 ? part
                    : new SqlExpr.Case(List.of(new SqlExpr.Case.When(
                            missing, new SqlExpr.NullLit())), part);
        }
        // element pick over a collect: ORDER-carrying LIMIT/OFFSET.
        // i = -1 (last) needs keys to flip; keyless last is undefined
        // order — declined, the wall stays loud.
        SqlSelect sel = collectSelect(coll);
        if (sel != null) {
            SqlAgg.Reducer collect =
                    (SqlAgg.Reducer) sel.projections().get(0).expr();
            SqlSelect picked = sel.withProjections(
                    List.of(new SqlSelect.Projection(collect.args().get(0),
                            sel.projections().get(0).alias())),
                    sel.outputs());
            if (i >= 1) {
                return new SqlExpr.ScalarSubquery(picked
                        .withOrderBy(collect.orderBy())
                        .withLimit(1L)
                        .withOffset(i > 1 ? i - 1 : null));
            }
            if (i == -1 && !collect.orderBy().isEmpty()) {
                List<SqlSelect.SortKey> flipped = new ArrayList<>();
                for (SqlSelect.SortKey k : collect.orderBy()) {
                    flipped.add(new SqlSelect.SortKey(k.expr(),
                            !k.ascending(), flipNulls(k.nullOrder()),
                            k.outputName()));
                }
                return new SqlExpr.ScalarSubquery(picked
                        .withOrderBy(flipped).withLimit(1L));
            }
        }
        return null;
    }

    private static SqlSelect.SortKey.@com.legend.Nullable NullOrder flipNulls(
            SqlSelect.SortKey.@com.legend.Nullable NullOrder n) {
        return n == null ? null
                : n == SqlSelect.SortKey.NullOrder.NULLS_FIRST
                        ? SqlSelect.SortKey.NullOrder.NULLS_LAST
                        : SqlSelect.SortKey.NullOrder.NULLS_FIRST;
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
