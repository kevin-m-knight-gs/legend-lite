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
    protected SqlExpr expr(SqlExpr e) {
        if (caps.nativeLists()) {
            return e;
        }
        // FUSION (R1, the engine's shape — pureToSQLQuery aggregates
        // inside the isolated grouped subselect, never a list value):
        // reducing a COLLECTING SUBSELECT pushes the reduction into it.
        //   ReduceCollection(name, (SELECT LIST(x) FROM ...), extras)
        //     -> (SELECT NAME(x, extras...) FROM ...)
        // The collect's ORDER KEYS carry over — the ordering contract
        // (insertion order via RowOrder) is preserved, not re-derived.
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
        // LITERAL collection (witnessed R1c: makeString over TDS-row
        // cells): the elements are compile-time-known — STRING_AGG
        // expands to the CONCAT chain t(e1)||sep||t(e2)||…; no subquery
        // at all. STRING_AGG only (join semantics).
        if (coll instanceof SqlExpr.ArrayLit al
                && rc.reducer() == SqlAgg.Fn.STRING_AGG
                && rc.extras().size() == 1 && !al.elements().isEmpty()) {
            return concatJoin(al.elements(), transform, rc.extras().get(0));
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
            SqlExpr rowJoined = concatJoin(cells.elements(), transform, sep);
            SqlAgg.Reducer fused = new SqlAgg.Reducer(SqlAgg.Fn.STRING_AGG,
                    List.of(rowJoined, sep), false, fcollect.orderBy());
            return new SqlExpr.ScalarSubquery(fsel.withProjections(
                    List.of(new SqlSelect.Projection(fused,
                            fsel.projections().get(0).alias())),
                    fsel.outputs()));
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
        SqlExpr value = collect.args().get(0);
        if (transform != null) {
            value = substParam(transform.body(), transform.params().get(0),
                    value);
        }
        List<SqlExpr> args = new ArrayList<>();
        args.add(value);
        args.addAll(rc.extras());
        SqlAgg.Reducer fused = new SqlAgg.Reducer(rc.reducer(), args, false,
                collect.orderBy());
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
