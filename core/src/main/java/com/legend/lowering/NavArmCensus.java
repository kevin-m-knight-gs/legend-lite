// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend.lowering;

/**
 * §4AD NAVIGATION-ARM CENSUS (V7_ASSERT_VERDICT_CHARTER §4AD, execution
 * step 1): the relational-conformance redesign replaces every arm that
 * compiles a MAPPED NAVIGATION to something other than the engine's row
 * algebra (left-outer-join fan-out, conditions in join/WHERE, one row
 * per surviving joined row) — EXISTS forms, correlated scalar
 * subqueries, per-object reductions. The charter demands the blast
 * radius as a NAMED list, never an estimate: this instrument records
 * (arm, test) firings during a corpus sweep, attributed through the
 * {@link StampCensus#CONTEXT} holder the harness
 * already sets per test.
 *
 * <p>Measurement only — runtime accumulation, the StampCensus/H2Verify
 * static-counter precedent; always on (no env flag — the flag
 * vocabulary is frozen), dumped by the corpus runner at sweep end.
 * Arms are named at their EMISSION sites; an arm here is a redesign
 * work item, not a defect claim.
 */
public final class NavArmCensus {

    private NavArmCensus() {
    }

    private static final java.util.concurrent.ConcurrentHashMap<String,
            java.util.concurrent.ConcurrentSkipListSet<String>> FIRINGS =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** Record one firing of {@code arm} for the currently-compiling
     * test (harness attribution; "&lt;unattributed&gt;" outside one). */
    public static void fire(String arm) {
        FIRINGS.computeIfAbsent(arm,
                        k -> new java.util.concurrent.ConcurrentSkipListSet<>())
                .add(StampCensus.CONTEXT.get());
    }

    /** Sorted arm → sorted witness-test set, for the runner's dump. */
    public static java.util.SortedMap<String,
            java.util.SortedSet<String>> snapshot() {
        java.util.TreeMap<String, java.util.SortedSet<String>> out =
                new java.util.TreeMap<>();
        FIRINGS.forEach((arm, tests) ->
                out.put(arm, new java.util.TreeSet<>(tests)));
        return out;
    }
}
