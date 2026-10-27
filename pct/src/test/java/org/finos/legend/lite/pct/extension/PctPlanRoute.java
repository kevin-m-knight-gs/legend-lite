// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package org.finos.legend.lite.pct.extension;

import com.legend.Compiler;
import com.legend.TypedQuery;
import com.legend.compiler.element.type.ExprType;
import com.legend.exec.PlanRunner;
import com.legend.executionplan.ExecutionPlan;

import java.io.IOException;
import java.util.Map;
import java.util.Set;

/**
 * Channel A on the plan (docs/RESULT_BOUNDARY_PROGRAM_2026_10_26.md, the end state of 2026-10-27, phase 4): the
 * case's expression and its injected model planned by the one compiler under the PCT's feature flags, for the server
 * this lane's sessions are, and run by the plan runner on the sessions the lane gives ({@link PctBackend#planSessions});
 * its answer is the plan's TYPED rows, which the adapter hands the reference interpreter by the plan's shape -- a
 * relation as Pure's TDS text written from them ({@link PureTdsText}), a value's elements as the reference's own
 * instances ({@link ValueBridge}).
 */
final class PctPlanRoute {

    private PctPlanRoute() {
    }

    /** A case's answer: what the plan answers ({@code shape}), its typed rows, and the query's result type, which a
     *  value's elements are built by. */
    record Answer(ExecutionPlan.TdsResult.Shape shape, PlanRunner.Rows rows, ExprType resultType) {
    }

    static Answer execute(String model, String expression) throws IOException {
        TypedQuery query = Compiler.query(Compiler.compileModel(PctBackend.withStorelessRuntime(model)), expression);
        // the plan for the server this lane's sessions are (H2's dialect spells by the version; step 9, 2026-10-19)
        ExecutionPlan plan = query.executionPlan(com.legend.test.StorelessRuntime.RUNTIME,
                Set.of(com.legend.platform.Feature.CORRECT_SQL_SUBSTRING_INDEXING), PctBackend.serverVersion());
        // the plan's own session for an in-process lane, the harness's for a server lane (PctBackend.planSessions)
        PlanRunner.Rows rows = PlanRunner.rows(plan, Map.of(), PctBackend.planSessions());
        return new Answer(plan.answerShape(), rows, query.resultType());
    }
}
