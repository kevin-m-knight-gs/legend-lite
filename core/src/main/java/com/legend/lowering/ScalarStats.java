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
        // Statistical reductions: a LIST-shaped value reduces via DuckDB
        // list_aggregate(x, '<agg>'); a SCALAR column read (the mapping
        // dyna stdDevSample(int1) — engine golden stddev_samp(col)) is
        // the whole-select SQL AGGREGATE (shape-decided, ListShapes rule).
        for (var e : Map.of(
                "stdDevSample", SqlAgg.Fn.STDDEV_SAMP, "stdDev", SqlAgg.Fn.STDDEV_SAMP,
                "stdDevPopulation", SqlAgg.Fn.STDDEV_POP,
                "varianceSample", SqlAgg.Fn.VAR_SAMP,
                "variancePopulation", SqlAgg.Fn.VAR_POP).entrySet()) {
            for (String f : Pure.nativeKeysAt(e.getKey())) {
                rules.put(f, (n, args) -> ListShapes.listShaped(args.get(0))
                        ? new SqlExpr.ReduceCollection(e.getValue(),
                                Numerics.numList(args.get(0)),
                                java.util.List.of())
                        : new SqlAgg.Reducer(e.getValue(),
                                List.of(args.get(0)), false, java.util.List.of()));
            }
        }
    }
}
