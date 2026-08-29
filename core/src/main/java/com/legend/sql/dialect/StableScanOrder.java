// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend.sql.dialect;

import com.legend.sql.SqlExpr;
import com.legend.sql.SqlQuery;
import com.legend.sql.SqlRewriter;
import com.legend.sql.SqlSelect;
import com.legend.sql.SqlSource;

import java.util.List;

/**
 * ROOT-ONLY scan-order determinism (§4AD batch 5): a TDS query with no
 * user ORDER BY answers in BASE-SCAN order on the engine's H2
 * (nested-loop joins stream the left scan); DuckDB's hash joins may
 * reorder when joined SUBSELECT frames are present (the router flip's
 * per-occurrence bundling exposed it — testProjectionDeeper's rows came
 * back David-first). Same doctrine as the STRING_AGG rowid arm
 * ("ORDER DETERMINISM" in Lowerer): the faithful key is the driving
 * table's physical row order.
 *
 * <p>TWO shapes, ONE rule — both verdict channels must read the same
 * deterministic row (the dual-channel census counted 44 skews when only
 * the host channel ordered):
 * <ul>
 *   <li>the statement root itself (host channel; LIMIT/OFFSET roots
 *       included — a bare cap without order is an arbitrary pick);</li>
 *   <li>a root WRAPPING an inner select that carries the LIMIT/OFFSET
 *       (the in-DB rows-&gt;at(N) canonization wrapper) — the ORDER BY
 *       belongs INSIDE that inner, at the same level as its cap.</li>
 * </ul>
 * Scope (measured, not asserted): only join trees carrying a SUBSELECT
 * frame — plain-table joins keep their historical natural order; never
 * over DISTINCT/GROUP BY/aggregate roots (one-row or set semantics).
 */
final class StableScanOrder extends SqlRewriter {

    @Override
    public SqlQuery rewriteRoot(SqlQuery q) {
        if (!(q instanceof SqlSelect s)) {
            return q;
        }
        SqlSource.Table base = orderableBase(s);
        if (base != null) {
            return ordered(s, base);
        }
        // the rows->at(N) verdict wrapper: inner carries the cap
        if (s.from() instanceof SqlSource.Subselect sub
                && sub.inner() instanceof SqlSelect inner
                && (inner.limit() != null || inner.offset() != null)) {
            SqlSource.Table innerBase = orderableBase(inner);
            if (innerBase != null) {
                return s.withFrom(new SqlSource.Subselect(
                        ordered(inner, innerBase), sub.alias(),
                        sub.frameName()));
            }
        }
        return q;
    }

    private static SqlSelect ordered(SqlSelect s, SqlSource.Table base) {
        return s.withOrderBy(List.of(new SqlSelect.SortKey(
                new SqlExpr.RowOrder(base.alias()), true, null, null)));
    }

    /** The driving base-table scan IF the select is order-stabilizable:
     * no user order, row-preserving clauses only, and a join tree that
     * contains a SUBSELECT frame (the flip's changed shapes) rooted at a
     * bare table scan. Null otherwise. */
    private static SqlSource.@com.legend.Nullable Table orderableBase(SqlSelect s) {
        if (!s.orderBy().isEmpty()
                || s.distinct()
                || !s.groupBy().isEmpty()
                || s.qualify() != null
                || !(s.from() instanceof SqlSource.Join)
                || s.projections().stream().anyMatch(
                        p -> containsReducer(p.expr()))) {
            return null;
        }
        boolean hasFrame = false;
        SqlSource leftmost = s.from();
        while (leftmost instanceof SqlSource.Join j) {
            hasFrame |= j.right() instanceof SqlSource.Subselect;
            leftmost = j.left();
        }
        return hasFrame && leftmost instanceof SqlSource.Table t ? t : null;
    }

    /** A top-level aggregate anywhere in the expression — Reducer,
     * JsonArrayAgg (graph serialization), OrderedListAgg — collapses the
     * select to one row (subqueries are their own scope, do not count). */
    private static boolean containsReducer(SqlExpr e) {
        if (e instanceof com.legend.sql.SqlAgg.Reducer
                || e instanceof SqlExpr.JsonArrayAgg
                || e instanceof SqlExpr.OrderedListAgg) {
            return true;
        }
        if (e instanceof SqlExpr.ScalarSubquery
                || e instanceof SqlExpr.Exists) {
            return false;
        }
        for (SqlExpr c : e.children()) {
            if (containsReducer(c)) {
                return true;
            }
        }
        return false;
    }
}
