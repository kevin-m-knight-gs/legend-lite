// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend.integration;

import com.legend.TypedQuery;
import com.legend.exec.PlanSessions;
import com.legend.executionplan.ExecutionPlan;
import com.legend.model.ConnectionDefinition.DatabaseType;
import com.legend.testbackend.TestBackends;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link WritePlansTest}'s write plans on H2 (in process), and a write plan on a database the runner shares: an H2
 *  connection's declared test data, the in-memory database decision A shares by its target. */
class WritePlansH2Test {

    @Test
    void plans() throws Exception {
        WritePlansTest.plans(TestBackends.of(DatabaseType.H2));
    }

    /** A write plan on a database the runner shares runs on one of its own, set up from the target: the shared one is
     *  never changed, so the next run reads it as set up (decision A's second rule). */
    @Test
    void aWritePlanNeverChangesADatabaseTheRunnerShares() throws Exception {
        String model = """
                ###Relational
                Database w::DB ( Table T (ID INTEGER PRIMARY KEY, NAME VARCHAR(20)) )
                ###Connection
                RelationalDatabaseConnection w::Conn {
                  store: w::DB;
                  type: H2;
                  specification: LocalH2 { testDataSetupCSV: 'default\\nT\\nID,NAME\\n1,a\\n2,b\\n'; };
                  auth: DefaultH2;
                }
                ###Runtime
                Runtime w::RT { mappings: [ ]; connections: [ w::DB: [ c: w::Conn ] ]; }
                """;
        var ctx = com.legend.Compiler.compileModel(model);
        ExecutionPlan delete = com.legend.Compiler.query(ctx, "|#>{w::DB.T}#->delete(r|$r.ID == 1)")
                .executionPlan("w::RT", TypedQuery.Output.JSON);
        ExecutionPlan read = com.legend.Compiler.query(ctx, "|#>{w::DB.T}#->select(~[NAME])->sort(~NAME->ascending())")
                .executionPlan("w::RT", TypedQuery.Output.JSON);
        ExecutionPlan count = com.legend.Compiler.query(ctx, "|1").executionPlan("w::RT", TypedQuery.Output.JSON);
        String before = WritePlansTest.run(read, PlanSessions.shared(), Map.of());
        assertTrue(before.contains("\"a\"") && before.contains("\"b\""), before);
        // the delete's count, on a database of the run's own, set up as the shared one is
        assertEquals(WritePlansTest.run(count, PlanSessions.shared(), Map.of()),
                WritePlansTest.run(delete, PlanSessions.shared(), Map.of()));
        assertEquals(before, WritePlansTest.run(read, PlanSessions.shared(), Map.of()));
    }
}
