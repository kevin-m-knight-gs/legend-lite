// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend;

/**
 * What the platform STATES about a resolved program before running it —
 * the facts a test runner needs and must never derive by reading the
 * program's text itself: whether its statements carry effects (store
 * writes, executions, test-data generation), whether its execution
 * contexts seed inline CSV test data (a session-isolation fact), and
 * whether it calls a verdict function (an assert the statement channel
 * adjudicates). Computed by {@link Compiler#programFacts} in ONE typing
 * pass.
 */
public record ProgramFacts(boolean effects, boolean seedsInlineCsv, boolean verdicts,
        java.util.Set<String> seedsStores) {

    /** The stores (Database FQNs) the program seeds through a typed
     * element reference ({@link com.legend.compiler.spec.SeededStores}):
     * the corpus runner's fixture-on-demand index. */
    public ProgramFacts {
        seedsStores = java.util.Set.copyOf(seedsStores);
    }
}
