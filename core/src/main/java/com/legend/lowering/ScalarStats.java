// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend.lowering;

import com.legend.builtin.Pure;
import com.legend.sql.SqlAgg;
import com.legend.sql.SqlExpr;
import com.legend.sql.SqlFn;

import java.util.List;
import java.util.Map;

/**
 * Statistical scalar rules (Scalars satellite — guardrail extraction):
 * list reductions vs whole-select SQL aggregates, shape-decided, plus
 * the numeric tolerance assert.
 */
final class ScalarStats {

    private ScalarStats() {
    }

    static void register(Map<String, Scalars.Rule> rules) {
        // Statistical reductions, STAMP-decided (pair-#4 elimination):
        // a MANY-stamped value reduces via list_aggregate(x, '<agg>');
        // a SCALAR-stamped operand (the mapping dyna stdDevSample(int1)
        // — engine golden stddev_samp(col)) is the whole-select SQL
        // AGGREGATE. Group-by lambdas never reach these rules
        // (Aggregates.reducerFor owns them).
        for (var e : Map.of(
                "stdDevSample", SqlAgg.Fn.STDDEV_SAMP, "stdDev", SqlAgg.Fn.STDDEV_SAMP,
                "stdDevPopulation", SqlAgg.Fn.STDDEV_POP,
                "varianceSample", SqlAgg.Fn.VAR_SAMP,
                "variancePopulation", SqlAgg.Fn.VAR_POP).entrySet()) {
            for (String f : Pure.nativeKeysAt(e.getKey())) {
                rules.put(f, (n, args) ->
                        n.args().get(0).info().multiplicity()
                                        instanceof com.legend.compiler.element
                                                .type.Multiplicity.Bounded b
                                && b.upper() != null && b.upper() <= 1
                        ? new SqlAgg.Reducer(e.getValue(),
                                List.of(args.get(0)), false, java.util.List.of())
                        : new SqlExpr.ReduceCollection(e.getValue(),
                                Numerics.numList(args.get(0)),
                                java.util.List.of()));
            }
        }
    }
}
