// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend.lowering;

import com.legend.compiler.element.type.Type;
import com.legend.compiler.spec.typed.TypedNativeCall;
import com.legend.sql.SqlAgg;
import com.legend.sql.SqlExpr;
import com.legend.sql.SqlFn;
import com.legend.sql.SqlSelect;

import java.util.List;

/**
 * The relation-level predicate family (EXISTS forms) — size / exists /
 * forAll / isEmpty / isNotEmpty over RELATION-valued arguments (extracted
 * registry; map §2 positional rules, engine processEmpty L4441 and
 * buildExistsPredicate L6278).
 */
final class RelationPredicates {

    private RelationPredicates() {
    }

    static Lowerer.RelationPredicate of(TypedNativeCall n) {
        // count over a RELATION argument is size (row count) — the graph-
        // leaf sub-aggregation emission rewrites nav-slot reads to their
        // correlated target relation and counts them (H4b)
        if (Lowerer.isFamily(n, "size") || Lowerer.isFamily(n, "count")) {
            // NOTE (audit 22b F1 residual): size over a RELATION value
            // counts ROWS regardless of the value's [1] multiplicity (one
            // relation != one row — a value-mult constant fold here broke
            // three correlated-count pins). rows->toOne()->size() therefore
            // still answers the row count when toOne sits mid-expression;
            // the exactly-one contract is reader-enforced at toOne ROOTS
            // only. Documented residual, not silently folded.
            // COUNT(*) is a zero-key aggregation: a grouped/deduped/truncated
            // source must count from OUTSIDE (COUNT(*) per group is a row per
            // group — a multi-row scalar subquery).
            return (lw, call) -> {
                SqlSelect src = lw.relation(call.args().get(0));
                SqlSelect base = Fold.groupByFolds(src) && !Fold.unnestInProjections(src)
                        ? src : lw.isolate(src);
                return new SqlExpr.ScalarSubquery(base
                        .withProjections(List.of(new SqlSelect.Projection(
                                SqlAgg.Reducer.of("COUNT"), null)), List.of()));
            };
        }
        if ((Lowerer.isFamily(n, "isEmpty") || Lowerer.isFamily(n, "isNotEmpty"))
                && n.args().size() == 1 && n.args().get(0).info().type()
                        instanceof Type.RelationType rt0) {
            // An OPTIONAL-VALUE read encoded as a relation (single scalar
            // column, [0..1] stamp — the filtered-nav leaf): emptiness is
            // the VALUE's NULL-ness, not row-set existence (engine
            // processIsEmpty scalar arm; a row with a NULL leaf IS empty).
            // Returning null routes through the scalar bridge + IS NULL.
            if (rt0.columns().size() == 1
                    && !(rt0.columns().get(0).type()
                            instanceof Type.ClassType)
                    && n.args().get(0).info().multiplicity()
                            instanceof com.legend.compiler.element.type
                                    .Multiplicity.Bounded b0
                    && b0.isToOne() && b0.lower() == 0) {
                return null;
            }
            // EXISTS over the relation (map §2 rule; engine processEmpty
            // Class-arm L4441) — never a serialized graph or list carrier.
            boolean isNot = Lowerer.isFamily(n, "isNotEmpty");
            return (lw, call) -> {
                SqlExpr ex = new SqlExpr.Exists(lw.relation(call.args().get(0)));
                return isNot ? ex : SqlExpr.Call.of(SqlFn.NOT, ex);
            };
        }
        if (Lowerer.isFamily(n, "exists")) {
            return (lw, call) -> new SqlExpr.Exists(Lowerer.select1(
                    lw.whereLambda(call.args().get(0), call.args().get(1), false)));
        }
        if (Lowerer.isFamily(n, "forAll")) {
            return (lw, call) -> SqlExpr.Call.of(SqlFn.NOT, new SqlExpr.Exists(Lowerer.select1(
                    lw.whereLambda(call.args().get(0), call.args().get(1), true))));
        }
        if (Lowerer.isFamily(n, "isEmpty")) {
            return (lw, call) -> SqlExpr.Call.of(SqlFn.NOT,
                    new SqlExpr.Exists(Lowerer.select1(lw.relation(call.args().get(0)))));
        }
        if (Lowerer.isFamily(n, "isNotEmpty")) {
            return (lw, call) -> new SqlExpr.Exists(Lowerer.select1(lw.relation(call.args().get(0))));
        }
        return null;
    }
}
