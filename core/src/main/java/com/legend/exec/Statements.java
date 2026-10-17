// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend.exec;

import com.legend.executionplan.ExecutionPlan;

import java.io.IOException;
import java.util.List;

/**
 * What a session does with a plan's text (docs/MONGODB_PLAN_2026_10_16.md §5.2, D12; 2026-10-17): the runner holds
 * the plan -- it checks the caller's values, fills the type holes, writes the result's punctuation -- and the session's
 * statements carry text in and text out. JDBC's are {@link JdbcStatements}, over the session's connection; a database
 * that speaks no JDBC gives its own through its connector ({@link DatabaseConnector}), and the runner never knows
 * which it holds.
 */
public interface Statements {

    /** The session is the database {@code target} names and, where the statements' spelling depends on the server's
     *  version, of a version they are written for; anything else is refused by name, never run. */
    void check(ExecutionPlan.Target target);

    /** A statement run for its effect: a session statement, a setup statement. */
    void execute(String statement);

    /**
     * The plan's statement, each slot bound to its parameter's checked value as the slot says, and each row's one text
     * (what the database wrote: carried, never read as a value, Charter C1.2) handed to {@code rows} in order.
     */
    void query(String statement, List<ExecutionPlan.Slot> slots, List<PlanParameters.Checked> values, Rows rows)
            throws IOException;

    /** Where a query's rows go. */
    @FunctionalInterface
    interface Rows {
        void row(@com.legend.base.Nullable String text) throws IOException;
    }
}
