// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend.sql.dialect;

import com.legend.sql.SqlQuery;
import com.legend.sql.SqlRewriter;
import com.legend.sql.ScanOrder;

/**
 * ENGINE-CORPUS-COMPAT root pass (flag-gated in {@link DuckDb}): the
 * HOST-channel statements of the corpus replay get the deterministic
 * scan-order key — the engine's tests assert positionally while
 * relying on H2's implicit scan order. The key itself (and the
 * always-on ASSERT-boundary application) lives in {@link ScanOrder} —
 * one owner.
 */
final class StableScanOrder extends SqlRewriter {

    @Override
    public SqlQuery rewriteRoot(SqlQuery q) {
        // deep walk FIRST (fires the select hook on every nested
        // select), then the root-shape special cases (cap wrappers)
        return ScanOrder.stabilize(rewrite(q));
    }
    // (A root-level union ORDER BY was BUILT AND REVERTED: ordering a
    // root fetch by union-leg ordinals broke aggregate/graph roots —
    // the unsorted two-renders compare is ASSERT-BOUNDARY comparison
    // policy (renderedArm's line multiset), per the user's ruling.)

    /** SCAN-ORDER AGGREGATION KEY THROUGH FRAMES (user ruling
     * 2026-08-31: tie order is undefined in the language — the
     * platform stays order-honest; replay determinism is ENGINE-COMPAT
     * ONLY, here): an order-sensitive STRING_AGG with no declared
     * order aggregates in H2's own order — the input's USER SORT keys
     * first (H2 sorts stably), then BASE-TABLE SCAN ORDER, probe-major
     * (H2's unindexed equi-joins emit probe/right-side major —
     * groupByAfterASort pins Smith*Johnson*Hill*Allen = personTable
     * scan order; testConcatenateWithJoin's ties are the same table's
     * scan order). Rowids thread through plain subselect frames as
     * appended hidden ordinal projections; union frames end the walk
     * (their legs' contribution is unaddressed — a named gap until a
     * witness demands leg ordinals). */
    @Override
    protected SqlQuery select(com.legend.sql.SqlSelect s) {
        if (!(s.from() instanceof com.legend.sql.SqlSource.Subselect sub)
                || !(sub.inner() instanceof com.legend.sql.SqlSelect inner)) {
            return s;
        }
        // the reducer's VALUE must read the from-subselect's own alias
        // (the witnesses' shape) — anything else (double-sort chains
        // whose collect embeds cross-scope aliases) stays untouched
        // (binder receipt: LIST(t2...) whose t2 is another scope)
        boolean wants = s.projections().stream().anyMatch(
                p -> p.expr() instanceof com.legend.sql.SqlAgg.Reducer r
                        && orderSensitive(r) && r.orderBy().isEmpty()
                        && !r.args().isEmpty()
                        && readsAlias(r.args().get(0), sub.alias()));
        if (!wants) {
            return s;
        }
        Threaded t = threadScan(inner);
        if (t == null || t.ordNames().isEmpty()) {
            return s;
        }
        // user sort keys: reference EXPORTED outputs by name; a key the
        // inner does not export is EXPORTED as a hidden projection
        // (binder receipt: ordering by t2.<unexported> resolves nothing)
        java.util.List<com.legend.sql.SqlSelect.SortKey> keys =
                new java.util.ArrayList<>();
        com.legend.sql.SqlSelect widened = t.select();
        for (com.legend.sql.SqlSelect.SortKey k : inner.orderBy()) {
            String name = k.outputName() != null ? k.outputName()
                    : k.expr() instanceof com.legend.sql.SqlExpr.Column kc
                            ? kc.name() : null;
            final String want = name;
            boolean exported = want != null && widened.outputs().stream()
                    .anyMatch(c -> c.name().equals(want));
            if (!exported) {
                String hidden = "__agg_key" + keys.size();
                com.legend.sql.OutputCol col = new com.legend.sql.OutputCol(
                        hidden, com.legend.sql.SqlType.Scalar.VARCHAR,
                        true);
                java.util.List<com.legend.sql.SqlSelect.Projection> wp =
                        new java.util.ArrayList<>(widened.projections());
                java.util.List<com.legend.sql.OutputCol> wo =
                        new java.util.ArrayList<>(widened.outputs());
                wp.add(new com.legend.sql.SqlSelect.Projection(k.expr(),
                        hidden, col));
                wo.add(col);
                widened = new com.legend.sql.SqlSelect(wp,
                        widened.distinct(), widened.from(),
                        widened.where(), widened.groupBy(),
                        widened.having(), widened.qualify(),
                        widened.orderBy(), widened.limit(),
                        widened.offset(), java.util.List.copyOf(wo));
                name = hidden;
            }
            keys.add(new com.legend.sql.SqlSelect.SortKey(
                    com.legend.sql.SqlExpr.Column.of(sub.alias(),
                            widened.outputs(),
                            java.util.Objects.requireNonNull(name)),
                    k.ascending(), k.nullOrder(), null));
        }
        for (String ord : t.ordNames()) {
            keys.add(new com.legend.sql.SqlSelect.SortKey(
                    com.legend.sql.SqlExpr.Column.of(sub.alias(),
                            widened.outputs(), ord),
                    true, null, null));
        }
        java.util.List<com.legend.sql.SqlSelect.Projection> out =
                new java.util.ArrayList<>(s.projections());
        for (int i = 0; i < out.size(); i++) {
            var p = out.get(i);
            if (p.expr() instanceof com.legend.sql.SqlAgg.Reducer r
                    && orderSensitive(r) && r.orderBy().isEmpty()
                    && !r.args().isEmpty()
                    && readsAlias(r.args().get(0), sub.alias())) {
                out.set(i, new com.legend.sql.SqlSelect.Projection(
                        new com.legend.sql.SqlAgg.Reducer(r.fn(), r.args(),
                                r.distinct(), keys),
                        p.outputName(), p.out()));
            }
        }
        return new com.legend.sql.SqlSelect(out, s.distinct(),
                new com.legend.sql.SqlSource.Subselect(widened,
                        sub.alias(), sub.frameName()),
                s.where(), s.groupBy(), s.having(), s.qualify(),
                s.orderBy(), s.limit(), s.offset(), s.outputs());
    }


    /** The value expression's EVERY column reference resolves in
     * {@code alias} (or is column-free). */
    private static boolean readsAlias(com.legend.sql.SqlExpr e,
            String alias) {
        if (e instanceof com.legend.sql.SqlExpr.Column c) {
            return alias.equals(c.table());
        }
        for (com.legend.sql.SqlExpr k : e.children()) {
            if (!readsAlias(k, alias)) {
                return false;
            }
        }
        return true;
    }

    /** Aggregations whose RESULT depends on input order: group concat
     * and the LIST collect (a value collection's element order —
     * testConcatenateWithJoin's makeString rides a LIST collect).
     * DISTINCT forms are set-shaped and stay untouched. */
    private static boolean orderSensitive(com.legend.sql.SqlAgg.Reducer r) {
        return (r.fn() == com.legend.sql.SqlAgg.Fn.STRING_AGG
                || r.fn() == com.legend.sql.SqlAgg.Fn.LIST)
                && !r.distinct();
    }

    private record Threaded(com.legend.sql.SqlSelect select,
            java.util.List<String> ordNames) {
    }

    /** {@code sel} with hidden {@code __agg_ordN} projections appended
     * for every base-table rowid reachable probe-major through its
     * from tree (recursing through plain subselect frames); null when
     * the shape refuses (set semantics or star frames). */
    private static @com.legend.Nullable Threaded threadScan(
            com.legend.sql.SqlSelect sel) {
        if (sel.distinct() || !sel.groupBy().isEmpty()
                || sel.limit() != null || sel.offset() != null
                // EMPTY projections = an implicit star frame — appending
                // would REPLACE the whole row (binder receipt: a filtered
                // join frame reduced to its ordinal alone)
                || sel.projections().isEmpty()
                || sel.projections().stream().anyMatch(
                        p -> p.expr() instanceof com.legend.sql.SqlExpr.Star
                            || p.expr() instanceof com.legend.sql.SqlExpr
                                    .StarExcept
                            || p.out() == null)) {
            return null;
        }
        java.util.List<com.legend.sql.SqlExpr> ordExprs =
                new java.util.ArrayList<>();
        com.legend.sql.SqlSource from2 = walkFrom(sel.from(), ordExprs);
        if (ordExprs.isEmpty()) {
            return null;
        }
        java.util.List<com.legend.sql.SqlSelect.Projection> ps =
                new java.util.ArrayList<>(sel.projections());
        java.util.List<com.legend.sql.OutputCol> outs =
                new java.util.ArrayList<>(sel.outputs());
        java.util.List<String> names = new java.util.ArrayList<>();
        for (com.legend.sql.SqlExpr e : ordExprs) {
            String name = "__agg_ord" + names.size();
            com.legend.sql.OutputCol col = new com.legend.sql.OutputCol(
                    name, com.legend.sql.SqlType.Scalar.BIGINT, true);
            ps.add(new com.legend.sql.SqlSelect.Projection(e, name, col));
            outs.add(col);
            names.add(name);
        }
        return new Threaded(new com.legend.sql.SqlSelect(ps,
                sel.distinct(), from2, sel.where(), sel.groupBy(),
                sel.having(), sel.qualify(), sel.orderBy(), sel.limit(),
                sel.offset(), java.util.List.copyOf(outs)), names);
    }

    /** Probe-major rowid walk: right side before left on joins; a
     * plain base table contributes its rowid; a subselect frame
     * recurses and re-exports its ordinals; unions and other sources
     * contribute nothing. Returns the (possibly rewritten) source. */
    private static com.legend.sql.SqlSource walkFrom(
            com.legend.sql.SqlSource src,
            java.util.List<com.legend.sql.SqlExpr> ordExprs) {
        if (src instanceof com.legend.sql.SqlSource.Table t) {
            ordExprs.add(new com.legend.sql.SqlExpr.RowOrder(t.alias()));
            return src;
        }
        if (src instanceof com.legend.sql.SqlSource.Join j) {
            java.util.List<com.legend.sql.SqlExpr> rightOrds =
                    new java.util.ArrayList<>();
            com.legend.sql.SqlSource r = walkFrom(j.right(), rightOrds);
            java.util.List<com.legend.sql.SqlExpr> leftOrds =
                    new java.util.ArrayList<>();
            com.legend.sql.SqlSource l = walkFrom(j.left(), leftOrds);
            ordExprs.addAll(rightOrds);
            ordExprs.addAll(leftOrds);
            return r == j.right() && l == j.left() ? src
                    : new com.legend.sql.SqlSource.Join(l, r, j.kind(),
                            j.on());
        }
        if (src instanceof com.legend.sql.SqlSource.Subselect sub
                && sub.inner() instanceof com.legend.sql.SqlSelect inner) {
            Threaded t = threadScan(inner);
            if (t == null) {
                return src;
            }
            for (String name : t.ordNames()) {
                ordExprs.add(com.legend.sql.SqlExpr.Column.of(sub.alias(),
                        t.select().outputs(), name));
            }
            return new com.legend.sql.SqlSource.Subselect(t.select(),
                    sub.alias(), sub.frameName());
        }
        // UNION ALL frame: H2 executes legs SEQUENTIALLY (leg-major
        // scan order — testProjectThroughAsso's golden) — every leg
        // appends its LEG INDEX plus its own first scan ordinal
        // (arity-normalized: exactly two columns per leg, NULL when a
        // leg has no base scan), and the frame re-exports both.
        if (src instanceof com.legend.sql.SqlSource.Subselect sub
                && sub.inner() instanceof com.legend.sql.SqlUnion u
                && u.all()) {
            java.util.List<com.legend.sql.SqlQuery> branches =
                    new java.util.ArrayList<>();
            for (int i = 0; i < u.branches().size(); i++) {
                if (!(u.branches().get(i)
                        instanceof com.legend.sql.SqlSelect leg)) {
                    return src;
                }
                com.legend.sql.SqlSelect leg2 = unionLegOrdinals(leg, i);
                if (leg2 == null) {
                    return src;
                }
                branches.add(leg2);
            }
            java.util.List<com.legend.sql.OutputCol> outs =
                    new java.util.ArrayList<>(u.outputs());
            com.legend.sql.OutputCol legCol = new com.legend.sql.OutputCol(
                    "__agg_leg", com.legend.sql.SqlType.Scalar.BIGINT,
                    true);
            com.legend.sql.OutputCol ordCol = new com.legend.sql.OutputCol(
                    "__agg_legord", com.legend.sql.SqlType.Scalar.BIGINT,
                    true);
            outs.add(legCol);
            outs.add(ordCol);
            com.legend.sql.SqlUnion u2 = new com.legend.sql.SqlUnion(
                    branches, true, java.util.List.copyOf(outs));
            ordExprs.add(com.legend.sql.SqlExpr.Column.of(sub.alias(),
                    outs, "__agg_leg"));
            ordExprs.add(com.legend.sql.SqlExpr.Column.of(sub.alias(),
                    outs, "__agg_legord"));
            return new com.legend.sql.SqlSource.Subselect(u2,
                    sub.alias(), sub.frameName());
        }
        return src;
    }

    /** A union leg with {@code __agg_leg} (its index) and
     * {@code __agg_legord} (its first probe-major scan ordinal, NULL
     * when none) appended; null when the leg's shape refuses. */
    private static com.legend.sql.@com.legend.Nullable SqlSelect
            unionLegOrdinals(com.legend.sql.SqlSelect leg, int index) {
        if (leg.distinct() || !leg.groupBy().isEmpty()
                || leg.limit() != null || leg.offset() != null
                || leg.projections().stream().anyMatch(
                        p -> p.expr() instanceof com.legend.sql.SqlExpr.Star
                            || p.expr() instanceof com.legend.sql.SqlExpr
                                    .StarExcept
                            || p.out() == null)) {
            return null;
        }
        java.util.List<com.legend.sql.SqlExpr> ords =
                new java.util.ArrayList<>();
        walkFrom(leg.from(), ords);
        com.legend.sql.SqlExpr ord = ords.isEmpty()
                ? new com.legend.sql.SqlExpr.Cast(
                        new com.legend.sql.SqlExpr.NullLit(),
                        com.legend.sql.SqlType.Scalar.BIGINT)
                : ords.get(0);
        java.util.List<com.legend.sql.SqlSelect.Projection> ps =
                new java.util.ArrayList<>(leg.projections());
        if (ps.isEmpty()) {
            // an implicit star frame keeps its row EXPLICITLY
            // (`SELECT *, <ordinals>`), never replaced by the ordinals
            ps.add(new com.legend.sql.SqlSelect.Projection(
                    new com.legend.sql.SqlExpr.Star(null), null, null));
        }
        java.util.List<com.legend.sql.OutputCol> outs =
                new java.util.ArrayList<>(leg.outputs());
        com.legend.sql.OutputCol legCol = new com.legend.sql.OutputCol(
                "__agg_leg", com.legend.sql.SqlType.Scalar.BIGINT, true);
        com.legend.sql.OutputCol ordCol = new com.legend.sql.OutputCol(
                "__agg_legord", com.legend.sql.SqlType.Scalar.BIGINT, true);
        ps.add(new com.legend.sql.SqlSelect.Projection(
                new com.legend.sql.SqlExpr.IntLit(index), "__agg_leg",
                legCol));
        ps.add(new com.legend.sql.SqlSelect.Projection(ord, "__agg_legord",
                ordCol));
        outs.add(legCol);
        outs.add(ordCol);
        return new com.legend.sql.SqlSelect(ps, leg.distinct(), leg.from(),
                leg.where(), leg.groupBy(), leg.having(), leg.qualify(),
                leg.orderBy(), leg.limit(), leg.offset(),
                java.util.List.copyOf(outs));
    }

    /** SUBSELECT inners stabilize too (disagree-9 burn): a verdict
     * statement compiles the asserted relation as a SUBQUERY (the
     * value-collection collect), where the root-only application
     * missed it — the HOST channel ran the same relation as its own
     * statement root and got the key, so the two channels read
     * different orders. Scoped to FROM-subselects (where ORDER BY is
     * legal SQL — a select hook also caught UNION BRANCHES and emitted
     * `... ORDER BY x UNION ALL ...`, a parser error). Same owner,
     * same orderable gate ({@link ScanOrder#stabilize} declines
     * DISTINCT/GROUP BY/aggregate/user-ordered shapes), same flag. */
    @Override
    protected com.legend.sql.SqlSource source(com.legend.sql.SqlSource s) {
        if (s instanceof com.legend.sql.SqlSource.Subselect sub
                && sub.inner() instanceof com.legend.sql.SqlSelect inner) {
            SqlQuery st = ScanOrder.stabilize(inner);
            if (st != inner) {
                return new com.legend.sql.SqlSource.Subselect(st,
                        sub.alias(), sub.frameName());
            }
        }
        return s;
    }
}
