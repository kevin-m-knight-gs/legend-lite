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

    /** Rows-level ->toOne() over a relation ($r.values.rows->toOne(),
     * the corpus's single-ROW claim): row-identical to the relation
     * itself. The exactly-one contract is enforced where the value
     * is CONSUMED (the executor's scalar second-row guard, audit
     * 21b F10) — engine toOne throws at the reader, never in SQL.
     * ANY 1-arg toOne in relation position looks through — the
     * POSITION is the contract (a class-typed nav arg arrives
     * here after resolution; the inner dispatch stays loud when
     * the arg is genuinely not relation-lowerable). */
    static boolean isRelationToOne(TypedNativeCall nc) {
        return com.legend.builtin.Pure.isToOneCall(nc.callee().qualifiedName())
                && nc.args().size() == 1;
    }

    static Lowerer.@com.legend.Nullable RelationPredicate of(TypedNativeCall n) {
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
                // engine processRowCount (pureToSQLQuery.pure:8985): a
                // SINGLE projected column counts NULL-SKIPPING — count(col);
                // anything else is the bare row count.
                List<SqlSelect.Projection> ps = base.projections();
                SqlAgg.Reducer counter = ps.size() == 1
                        && !(ps.get(0).expr() instanceof SqlExpr.Star)
                        ? new SqlAgg.Reducer(SqlAgg.Fn.COUNT, List.of(ps.get(0).expr()),
                                false, java.util.List.of())
                        : SqlAgg.Reducer.of(SqlAgg.Fn.COUNT);
                // §4AD census: correlated scalar COUNT subquery — the
                // rule bans correlated scalar subqueries for navigations
                NavArmCensus.fire("correlated-count-reducer");
                return new SqlExpr.ScalarSubquery(base
                        .withProjections(List.of(new SqlSelect.Projection(
                                counter, null, null))));
            };
        }
        // GENERAL reducer over a single-scalar-column RELATION argument
        // (the graph derived-leaf sub-aggregation: average($this.employees
        // .age) — engine renders a correlated scalar aggregate subquery)
        com.legend.sql.SqlAgg.Fn fam = Aggregates.reducerOrNull(n.callee());
        if (fam != null && fam != com.legend.sql.SqlAgg.Fn.COUNT && fam != com.legend.sql.SqlAgg.Fn.ANY_VALUE
                && n.args().size() == 1
                && Type.relationSchema(n.args().get(0).info().type())
                        instanceof Type.RelationType rt2
                && rt2.columns().size() == 1
                && !(rt2.columns().get(0).type() instanceof Type.ClassType)) {
            return (lw, call) -> {
                SqlSelect src = lw.relation(call.args().get(0));
                SqlSelect base = Fold.groupByFolds(src)
                        && !Fold.unnestInProjections(src)
                        ? src : lw.isolate(src);
                SqlExpr col = base.projections().size() == 1
                        && !(base.projections().get(0).expr()
                                instanceof SqlExpr.Star)
                        ? base.projections().get(0).expr()
                        : Fold.sourceColumn(base.from(),
                                rt2.columns().get(0).name());
                // §4AD census: correlated scalar aggregate subquery
                NavArmCensus.fire("correlated-agg-reducer");
                return new SqlExpr.ScalarSubquery(base.withProjections(
                        List.of(new SqlSelect.Projection(
                                new SqlAgg.Reducer(fam, List.of(col), false,
                                        List.of()), null, null))));
            };
        }
        if ((Lowerer.isFamily(n, "isEmpty") || Lowerer.isFamily(n, "isNotEmpty"))
                && n.args().size() == 1
                && Type.relationSchema(n.args().get(0).info().type())
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
