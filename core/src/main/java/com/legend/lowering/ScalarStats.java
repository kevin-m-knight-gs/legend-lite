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
        // assertEqWithinTolerance(e, a, d) = abs(e - a) <= d (platform
        // assertEqWithinTolerance.pure:19 — the boolean IS the assert)
        for (String f : Pure.nativeKeysAt("assertEqWithinTolerance")) {
            rules.put(f, (n, args) -> SqlExpr.Call.of(SqlFn.LESS_EQUAL,
                    SqlExpr.Call.of(SqlFn.ABS,
                            SqlExpr.Call.of(SqlFn.MINUS, args.get(0),
                                    args.get(1))),
                    args.get(2)));
        }
        // Statistical reductions: a LIST-shaped value reduces via DuckDB
        // list_aggregate(x, '<agg>'); a SCALAR column read (the mapping
        // dyna stdDevSample(int1) — engine golden stddev_samp(col)) is
        // the whole-select SQL AGGREGATE (shape-decided, ListShapes rule).
        for (var e : Map.of(
                "stdDevSample", "stddev_samp", "stdDev", "stddev_samp",
                "stdDevPopulation", "stddev_pop",
                "varianceSample", "var_samp",
                "variancePopulation", "var_pop").entrySet()) {
            for (String f : Pure.nativeKeysAt(e.getKey())) {
                rules.put(f, (n, args) -> ListShapes.listShaped(args.get(0))
                        ? new SqlExpr.Call(SqlFn.LIST_AGG, List.of(
                                new SqlExpr.StringLit(e.getValue()),
                                Scalars.numList(args.get(0))))
                        : new SqlAgg.Reducer(e.getValue(),
                                List.of(args.get(0)), false, java.util.List.of()));
            }
        }
    }
}
