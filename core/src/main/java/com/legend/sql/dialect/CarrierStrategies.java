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

    /** How this dialect carries collections. */
    public enum Mode {
        /** Native list values exist (DuckDB): semantic nodes render via
         * the dialect's list hooks — no structural rewrite needed. */
        NATIVE_LISTS,
        /** No list values (ANSI/H2): structural rules re-shape semantic
         * nodes into portable SQL — the engine's shapes. */
        PORTABLE
    }

    private final Mode mode;

    public CarrierStrategies(Mode mode) {
        this.mode = mode;
    }

    @Override
    protected SqlExpr expr(SqlExpr e) {
        if (mode == Mode.NATIVE_LISTS) {
            return e;
        }
        // FUSION (R1, the engine's shape — pureToSQLQuery aggregates
        // inside the isolated grouped subselect, never a list value):
        // reducing a COLLECTING SUBSELECT pushes the reduction into it.
        //   ReduceCollection(name, (SELECT LIST(x) FROM ...), extras)
        //     -> (SELECT NAME(x, extras...) FROM ...)
        // The collect's ORDER KEYS carry over — the ordering contract
        // (insertion order via RowOrder) is preserved, not re-derived.
        if (e instanceof SqlExpr.ReduceCollection rc
                && rc.collection() instanceof SqlExpr.ScalarSubquery sq
                && sq.subquery() instanceof SqlSelect sel
                && sel.projections().size() == 1
                && sel.projections().get(0).expr()
                        instanceof SqlAgg.Reducer collect
                && collect.fn() == SqlAgg.Fn.LIST
                && !collect.distinct()
                && collect.args().size() == 1) {
            List<SqlExpr> args = new ArrayList<>(collect.args());
            args.addAll(rc.extras());
            SqlAgg.Reducer fused = new SqlAgg.Reducer(
                    rc.reducer(), args,
                    false, collect.orderBy());
            return new SqlExpr.ScalarSubquery(sel.withProjections(
                    List.of(new SqlSelect.Projection(fused,
                            sel.projections().get(0).alias())),
                    sel.outputs()));
        }
        return e;
    }
}
