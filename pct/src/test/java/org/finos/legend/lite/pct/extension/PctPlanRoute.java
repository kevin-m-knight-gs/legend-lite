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

    /** A case's answer: what the plan answers ({@code shape}), its typed rows, the query's result type, which a
     *  value's elements are built by, and, for a relation read as the engine's adapter reads one, the engine's JSON
     *  answer the server writes ({@code engineJson}; null otherwise). */
    record Answer(ExecutionPlan.TdsResult.Shape shape, PlanRunner.Rows rows, ExprType resultType,
            @com.legend.base.Nullable String engineJson) {
    }

    /** The measurement of the result boundary program's stage 1, step 3 (2026-10-27): a relation crosses as the
     *  engine's JSON answer read by legend-engine's own adapter steps ({@code LEGENDLITE_PCT_RELATION=engine}) or as
     *  Pure's TDS text written in Java ({@link PureTdsText}), per run, so both are measured on every lane on one head;
     *  the loser is deleted with this switch. */
    static final boolean ENGINE_RELATION = "engine".equals(System.getenv("LEGENDLITE_PCT_RELATION"));

    static Answer execute(String model, String expression) throws IOException {
        TypedQuery query = Compiler.query(Compiler.compileModel(PctBackend.withStorelessRuntime(model)), expression);
        // the plan for the server this lane's sessions are (H2's dialect spells by the version; step 9, 2026-10-19)
        ExecutionPlan plan = query.executionPlan(com.legend.test.StorelessRuntime.RUNTIME,
                Set.of(com.legend.platform.Feature.CORRECT_SQL_SUBSTRING_INDEXING), PctBackend.serverVersion());
        // the plan's own session for an in-process lane, the harness's for a server lane (PctBackend.planSessions)
        if (ENGINE_RELATION && plan.answerShape() == ExecutionPlan.TdsResult.Shape.RELATION) {
            // the server's own writer of the engine's answer (PureV1Api.engineWriter), fed the stream the rows are
            // collected from
            com.legend.result.Collector collector = new com.legend.result.Collector();
            java.io.StringWriter json = new java.io.StringWriter();
            com.legend.result.ResultSink engine = com.legend.server.PureV1Api.engineWriter(query, plan, json);
            PlanRunner.stream(plan, Map.of(), PctBackend.planSessions(), new com.legend.result.ResultSink() {
                @Override
                public void columns(java.util.List<ExecutionPlan.Column> columns) throws IOException {
                    collector.columns(columns);
                    engine.columns(columns);
                }

                @Override
                public void batch(com.legend.result.Batch batch) throws IOException {
                    collector.batch(batch);
                    engine.batch(batch);
                }

                @Override
                public void end() throws IOException {
                    collector.end();
                    engine.end();
                }
            });
            com.legend.result.Collector.Collected c = collector.collected();
            return new Answer(plan.answerShape(), new PlanRunner.Rows(c.columns(), c.rows()), query.resultType(),
                    json.toString());
        }
        PlanRunner.Rows rows = PlanRunner.rows(plan, Map.of(), PctBackend.planSessions());
        return new Answer(plan.answerShape(), rows, query.resultType(), null);
    }
}
