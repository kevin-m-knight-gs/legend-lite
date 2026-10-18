// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0
package com.legend.testcases;

import com.legend.database.Databases;
import com.legend.executionplan.ExecutionPlan;
import com.legend.executionplan.PlanJson;
import com.legend.model.ConnectionDefinition.DatabaseType;
import com.legend.sql.dialect.RenderedStatement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The plan path program's round-trip gate (docs/PLAN_PATH_PROGRAM_2026_10_17.md §2, step 1b): a plan written to its
 * JSON and read back is the same plan, its typed SQL tree included, and the tree read back renders the same statement
 * with as many bindings on the plan's own dialect. Every test that makes a plan can judge it so; a plan with no typed
 * tree is a planner defect here, never a pass.
 */
public final class PlanRoundTrip {
    private PlanRoundTrip() {
    }

    public static void assertRoundTrips(ExecutionPlan plan, String what) {
        ExecutionPlan back = PlanJson.read(PlanJson.write(plan));
        assertEquals(plan, back, what + ": the plan read back differs");
        assertRenders(back.root(), what);
    }

    private static void assertRenders(ExecutionPlan.Node node, String what) {
        switch (node) {
            case ExecutionPlan.Sequence s -> s.steps().forEach(step -> assertRenders(step, what));
            case ExecutionPlan.TdsResult t -> assertRenders(t.sql(), what);
            case ExecutionPlan.TextResult t -> assertRenders(t.sql(), what);
        }
    }

    private static void assertRenders(ExecutionPlan.Sql sql, String what) {
        assertTrue(sql.tree() != null, what + ": the plan carries no typed tree");
        DatabaseType type = switch (sql.target().database()) {
            case ExecutionPlan.Database.Declared d -> d.connection().databaseType();
            case ExecutionPlan.Database.Platform p -> p.type();
        };
        RenderedStatement rendered = Databases.dialect(type).renderStatement(sql.tree());
        assertEquals(sql.statement(), rendered.sql(), what + ": the tree read back renders differently");
        assertEquals(sql.slots().size(), rendered.binds().size(), what + ": the tree read back binds differently");
    }
}
