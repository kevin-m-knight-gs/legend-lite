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
        // fires for DECIMAL-bearing literal lists (exact binary decimal
        // arithmetic vs LIST_PRODUCT's DOUBLE degradation) AND — Part-1
        // fix 2026-08-26 — for ALL-INTEGER literal lists (LIST_PRODUCT
        // re-kinded [2,3]->times() to DOUBLE 6.0; a TIMES chain keeps
        // the integer kind with zero new carrier sites). Non-literal
        // integer collections keep LIST_PRODUCT (no demanded traffic;
        // the wire census flags any kind drift it produces).
        if (list instanceof SqlExpr.ArrayLit la
                && la.elements().size() >= 2
                && (la.elements().stream()
                        .anyMatch(e -> e instanceof SqlExpr.DecimalLit)
                        || la.elements().stream().allMatch(e ->
                                e instanceof SqlExpr.IntLit))
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

    /** Real Compare.java's KIND ordering: Numbers < Dates < Booleans <
     * Strings; -1 = not a primitive kind (moved from Scalars at the
     * shape limit — the cross-kind comparison vocabulary). */
    static int compareKind(com.legend.compiler.element.type.Type t) {
        if (t == com.legend.compiler.element.type.Type.Primitive.INTEGER
                || t == com.legend.compiler.element.type.Type.Primitive.FLOAT
                || t == com.legend.compiler.element.type.Type.Primitive.NUMBER
                || t == com.legend.compiler.element.type.Type.Primitive.DECIMAL
                || t instanceof com.legend.compiler.element.type.Type.PrecisionDecimal) {
            return 0;
        }
        if (t == com.legend.compiler.element.type.Type.Primitive.DATE
                || t == com.legend.compiler.element.type.Type.Primitive.STRICT_DATE
                || t == com.legend.compiler.element.type.Type.Primitive.DATE_TIME) {
            return 1;
        }
        if (t == com.legend.compiler.element.type.Type.Primitive.BOOLEAN) {
            return 2;
        }
        if (t == com.legend.compiler.element.type.Type.Primitive.STRING) {
            return 3;
        }
        return -1;
    }

    /** The mixed-number VARIANT carrier's literal array unwraps to raw
     * numerics for aggregates/reductions (sum(JSON) does not bind). */
    static SqlExpr numList(SqlExpr e) {
        if (e instanceof SqlExpr.ArrayLit la && !la.elements().isEmpty()
                && la.elements().stream().allMatch(
                        MixedEncoding::variantWrapped)) {
            return new SqlExpr.ArrayLit(la.elements().stream()
                    .map(MixedEncoding::unwrapVariant)
                    .toList());
        }
        return e;
    }
}
