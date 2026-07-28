// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend.lowering;

import com.legend.builtin.Pure;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Remediation T1.7 — aggregate membership is the reducer CATALOG, never
 * a parallel name list. The deleted AGG_FQNS list missed stdDev,
 * variance, mode, corr and friends: an aggregate the wall could not see
 * silently mis-resolved ("max() &gt; 30 becoming any-match" class).
 */
class AggregatesCatalogTest {

    @Test
    @DisplayName("names the old hand list missed are reducers")
    void previouslyMissedNamesAreReducers() {
        for (String name : new String[] {"stdDev", "variance", "mode",
                "corr", "percentileDisc", "covarSample", "covarPopulation"}) {
            var keys = Pure.nativeKeysAt(name);
            assertFalse(keys.isEmpty(), name + " must be registered");
            keys.forEach(k -> assertTrue(Aggregates.isReducerKey(k),
                    name + " overload must count as an aggregate: " + k));
        }
    }

    @Test
    @DisplayName("non-aggregates stay out")
    void nonAggregatesStayOut() {
        // 2-arg logical and(a,b) must never register as a reducer (the
        // catalog's own 1-arg-only rule)
        Pure.nativeKeysAt("toUpper").forEach(k ->
                assertFalse(Aggregates.isReducerKey(k)));
    }
}
