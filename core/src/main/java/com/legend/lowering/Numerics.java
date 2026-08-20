// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend.lowering;

import com.legend.sql.SqlExpr;
import com.legend.sql.SqlFn;

/**
 * Numeric-collection LITERAL helpers shared by the arithmetic rules
 * (extracted from {@code Scalars} at the shape limit, 2026-08-20 —
 * host-logic-audit slice).
 */
final class Numerics {

    private Numerics() {
    }

    /** A LITERAL numeric list containing a DECIMAL folds to a BINARY op
     * chain — DuckDB's list aggregates and LIST_REDUCE run DOUBLE
     * (probed 2026-08-20) while binary decimal arithmetic is exact;
     * null = not that shape (the aggregate path continues). */
    static @com.legend.Nullable SqlExpr decimalChain(SqlExpr list,
            SqlFn op) {
        if (list instanceof SqlExpr.ArrayLit la
                && la.elements().size() >= 2
                && la.elements().stream()
                        .anyMatch(e -> e instanceof SqlExpr.DecimalLit)
                && la.elements().stream().allMatch(e ->
                        e instanceof SqlExpr.DecimalLit
                        || e instanceof SqlExpr.IntLit
                        || e instanceof SqlExpr.FloatLit)) {
            SqlExpr acc = la.elements().get(0);
            for (int i = 1; i < la.elements().size(); i++) {
                acc = SqlExpr.Call.of(op, acc, la.elements().get(i));
            }
            return acc;
        }
        return null;
    }

    /** The mixed-number VARIANT carrier's literal array unwraps to raw
     * numerics for aggregates/reductions (sum(JSON) does not bind). */
    static SqlExpr numList(SqlExpr e) {
        if (e instanceof SqlExpr.ArrayLit la && !la.elements().isEmpty()
                && la.elements().stream().allMatch(x ->
                        x instanceof SqlExpr.Call c
                        && c.fn() == SqlFn.TO_VARIANT)) {
            return new SqlExpr.ArrayLit(la.elements().stream()
                    .map(x -> (SqlExpr) ((SqlExpr.Call) x).args().get(0))
                    .toList());
        }
        return e;
    }
}
