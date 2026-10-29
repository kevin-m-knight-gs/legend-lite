// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend;

import com.legend.compiler.element.ModelContext;

/**
 * THE EXECUTION FRONT DOOR (C2a, docs/PLAN_EXECUTION_SPLIT_AND_DATABASE_OWNER_2026_10_03.md): a query planned by
 * {@link Compiler} (the planner, which never touches a database — its library has no {@code exec}) run on a
 * session — handed by the caller and checked, or opened from the target the planner decided
 * ({@code Compiler.executesOn}, {@code exec.Sessions}). Execution reaches the planner only through its public API:
 * the compiled model, the resolved query, the target, and the lowered query ({@link Compiler.LoweredQuery}).
 */
public final class Execution {

    private Execution() {
    }

    /** How a query that only reads takes its session: established as legend-engine establishes a connection on every
     *  acquisition, the runtime's declared test data run on it first ({@code pure/v1} execute), or as the caller set it
     *  up (its own tables, its own connection). */
    public enum Establish { DECLARED_SETUP, AS_GIVEN }

    /**
     * THE PRODUCTION EXECUTION (docs/RESULT_BOUNDARY_PROGRAM_2026_10_26.md, the end state recorded 2026-10-27): a query
     * typed once, planned as typed rows, run by the plan's runner, its rows streamed in batches to the sink the caller
     * makes from the plan that runs. {@code sinkOf} is called before any statement runs, so a sink that refuses the plan
     * (an answer its format has no form for) refuses it untouched -- a program's writes never land behind a refused
     * answer. A query that only reads runs on the session {@code sessions} opens for its runtime's target, the plan
     * written for that session's server; a program that writes runs on {@code planSessions} (decision A: a write runs
     * on a database of its own, a database the user names or a URL reaches keeps it). Returns the plan that ran.
     */
    public static com.legend.executionplan.ExecutionPlan run(TypedQuery typed,
            @com.legend.base.Nullable String runtimeFqn, com.legend.exec.Sessions.Source sessions,
            com.legend.exec.PlanSessions.Source planSessions, Establish establish,
            java.util.function.Function<com.legend.executionplan.ExecutionPlan, com.legend.result.ResultSink> sinkOf)
            throws java.io.IOException {
        return run(typed, runtimeFqn, sessions, planSessions, establish, sinkOf,
                com.legend.result.Batcher.DEFAULT_CAPACITY);
    }

    /** {@link #run(TypedQuery, String, com.legend.exec.Sessions.Source, com.legend.exec.PlanSessions.Source,
     *  Establish, java.util.function.Function)} with the rows handed to the sink in batches of up to
     *  {@code batchRows} (the stream's granularity: a writer writes and flushes each batch as it arrives). */
    public static com.legend.executionplan.ExecutionPlan run(TypedQuery typed,
            @com.legend.base.Nullable String runtimeFqn, com.legend.exec.Sessions.Source sessions,
            com.legend.exec.PlanSessions.Source planSessions, Establish establish,
            java.util.function.Function<com.legend.executionplan.ExecutionPlan, com.legend.result.ResultSink> sinkOf,
            int batchRows) throws java.io.IOException {
        if (typed.writes()) {
            com.legend.executionplan.ExecutionPlan plan = typed.executionPlan(runtimeFqn);
            com.legend.result.ResultSink sink = sinkOf.apply(plan);
            com.legend.exec.PlanRunner.stream(plan, java.util.Map.of(), planSessions, sink, batchRows);
            return plan;
        }
        ModelContext ctx = typed.context();
        try (com.legend.exec.Sessions.Session session = sessions.open(Compiler.executesOn(ctx, runtimeFqn), ctx)) {
            java.sql.Connection connection = session.connection();
            com.legend.executionplan.ExecutionPlan plan = typed.executionPlan(runtimeFqn, java.util.Set.of(),
                    com.legend.exec.Sessions.version(connection));
            com.legend.result.ResultSink sink = sinkOf.apply(plan);
            com.legend.exec.PlanSessions.Source on = switch (establish) {
                case DECLARED_SETUP -> com.legend.exec.PlanSessions.setUp(connection, plan.answerSql().target());
                case AS_GIVEN -> com.legend.exec.PlanSessions.given(connection);
            };
            com.legend.exec.PlanRunner.stream(plan, java.util.Map.of(), on, sink, batchRows);
            return plan;
        }
    }

    /** {@link #run(TypedQuery, String, com.legend.exec.Sessions.Source, com.legend.exec.PlanSessions.Source,
     *  Establish, java.util.function.Function)} for a model's text and a query's text, the session as the caller set
     *  it up. */
    public static com.legend.executionplan.ExecutionPlan run(String model, String query,
            @com.legend.base.Nullable String runtimeFqn, com.legend.exec.Sessions.Source sessions,
            com.legend.exec.PlanSessions.Source planSessions,
            java.util.function.Function<com.legend.executionplan.ExecutionPlan, com.legend.result.ResultSink> sinkOf)
            throws java.io.IOException {
        return run(model, query, runtimeFqn, sessions, planSessions, sinkOf, com.legend.result.Batcher.DEFAULT_CAPACITY);
    }

    /** {@link #run(String, String, String, com.legend.exec.Sessions.Source, com.legend.exec.PlanSessions.Source,
     *  java.util.function.Function)} in batches of up to {@code batchRows}. */
    public static com.legend.executionplan.ExecutionPlan run(String model, String query,
            @com.legend.base.Nullable String runtimeFqn, com.legend.exec.Sessions.Source sessions,
            com.legend.exec.PlanSessions.Source planSessions,
            java.util.function.Function<com.legend.executionplan.ExecutionPlan, com.legend.result.ResultSink> sinkOf,
            int batchRows) throws java.io.IOException {
        return run(Compiler.query(Compiler.compileModel(model), query), runtimeFqn, sessions, planSessions,
                Establish.AS_GIVEN, sinkOf, batchRows);
    }

    /**
     * THE dialect of a query that executes on {@code connection}: the database its runtime executes on
     * ({@link Compiler#executesOn}, upstream's {@code createDbConfig(connection.type)}), refined by the server's
     * version ({@code SqlDialect.forServer}), with its session setup run once. The session is only
     * CHECKED ({@code Sessions.check}): connected to another database than the one declared is refused,
     * never reinterpreted.
     */
    static com.legend.sql.dialect.SqlDialect dialectOf(ModelContext ctx,
            @com.legend.base.Nullable String runtimeFqn,
            java.sql.Connection connection) {
        com.legend.model.ConnectionDefinition.DatabaseType declared = Compiler.executesOn(ctx, runtimeFqn).type();
        com.legend.exec.Sessions.check(connection, declared);
        com.legend.sql.dialect.SqlDialect dialect = com.legend.database.Databases.dialect(declared)
                .forServer(com.legend.exec.Sessions.version(connection));
        // B6: session setup rides the connection-dialect resolution -- the ONE seam every
        // connection-bearing entry passes through; the dialect states the FACTS, the exec funnel executes
        com.legend.exec.Statements session = com.legend.exec.PlanSessions.statementsOf(connection, null);
        for (String s : dialect.sessionSetup()) {
            try (var __o = com.legend.exec.StatementOrigin.enter(com.legend.exec.StatementOrigin.SESSION)) {
                com.legend.exec.SetupRunner.send(session, s);
            }
        }
        return dialect;
    }

    /**
     * The core QUERY SERVICE: frontend + Phase G + lowering + rendering +
     * EXECUTION over the caller's connection, shaped per the result-type
     * classification ({@link com.legend.plan.ResultShape}). The corpus
     * bridge's target (PHASE_K_EXECUTION.md). Class queries need an
     * execution context in the query itself ({@code ->from(...)}) on this
     * overload; the 4-arg overload supplies a driver runtime.
     */
    public static com.legend.exec.@com.legend.base.Nullable ExecutionResult execute(
            String model, String query,
            java.sql.Connection connection) {
        return execute(model, query, null, connection);
    }

    /**
     * The full pipeline with a DRIVER-SUPPLIED execution context — the
     * service shape: queries carry no {@code ->from(...)}; the runtime
     * arrives as an API argument (PHASE_K_EXECUTION.md §4). Phase H
     * resolves class queries against the runtime's mapping between G and
     * I; an explicit {@code from()} in the query always wins.
     */
    public static com.legend.exec.@com.legend.base.Nullable ExecutionResult execute(
            String model, String query,
            @com.legend.base.Nullable String runtimeFqn,
            java.sql.Connection connection) {
        return execute(model, query, null, runtimeFqn, connection);
    }

    /**
     * {@link #execute(String, String, String, java.sql.Connection)} with a
     * SECTION import scope: the query resolves under {@code imports} (plus
     * the prelude) against the model's element universe — real pure's rule
     * for a query written in an import-bearing section. A {@code null}
     * scope is the sectionless-query behavior.
     */
    public static com.legend.exec.@com.legend.base.Nullable ExecutionResult execute(
            String model, String query,
            com.legend.model.@com.legend.base.Nullable ImportScope imports,
            @com.legend.base.Nullable String runtimeFqn,
            java.sql.Connection connection) {
        return execute(model, query, imports, runtimeFqn, connection, ExecuteOptions.NONE);
    }

    /** With the caller's execute OPTIONS (the PCT adapter's wire render). */
    public static com.legend.exec.@com.legend.base.Nullable ExecutionResult execute(
            String model, String query,
            com.legend.model.@com.legend.base.Nullable ImportScope imports,
            @com.legend.base.Nullable String runtimeFqn,
            java.sql.Connection connection, ExecuteOptions options) {
        ModelContext ctx = Compiler.compileModel(model);
        // the ONE front door (resolveQuery: names, the statement splice, the
        // desugars) — a text query is its statements under its section scope
        com.legend.protocol.spec.ValueSpecification parsed = Compiler.parseQuery(query);
        java.util.List<com.legend.protocol.spec.ValueSpecification> statements =
                parsed instanceof com.legend.protocol.spec.LambdaFunction lf
                        && lf.parameters().isEmpty() ? lf.body() : java.util.List.of(parsed);
        return executeResolved(
                Compiler.resolveQuery(statements,
                        imports == null ? new com.legend.model.ImportScope(java.util.List.of())
                                : imports, ctx),
                ctx, runtimeFqn, connection, null, null, options);
    }

    /** {@link #execute(String, String, com.legend.model.ImportScope, String, java.sql.Connection, ExecuteOptions)}
     *  on the session {@code sessions} opens for the target the query's runtime declares ({@link Compiler#executesOn}):
     *  the model compiled once, the connection decided from it, then opened (C3b). */
    public static com.legend.exec.@com.legend.base.Nullable ExecutionResult execute(
            String model, String query, @com.legend.base.Nullable String runtimeFqn,
            com.legend.exec.Sessions.Source sessions, ExecuteOptions options) {
        ModelContext ctx = Compiler.compileModel(model);
        com.legend.protocol.spec.ValueSpecification parsed = Compiler.parseQuery(query);
        java.util.List<com.legend.protocol.spec.ValueSpecification> statements =
                parsed instanceof com.legend.protocol.spec.LambdaFunction lf
                        && lf.parameters().isEmpty() ? lf.body() : java.util.List.of(parsed);
        var resolved = Compiler.resolveQuery(statements, new com.legend.model.ImportScope(java.util.List.of()), ctx);
        try (com.legend.exec.Sessions.Session session = sessions.open(Compiler.executesOn(ctx, runtimeFqn), ctx)) {
            return executeResolved(resolved, ctx, runtimeFqn, session.connection(), null, null, options);
        }
    }

    /**
     * Phases G&frac12;&rarr;K for an already NAME-RESOLVED query AST — THE
     * one back-half sequence. Every driver path (text queries above,
     * EngineTestExecutor's handle-splice path) comes through here; a second
     * hand-rolled sequence is an orchestrator bug (audit 15 unified two).
     */
    public static com.legend.exec.@com.legend.base.Nullable ExecutionResult executeResolved(
            com.legend.protocol.spec.ValueSpecification resolved, ModelContext ctx,
            @com.legend.base.Nullable String runtimeFqn,
            java.sql.Connection connection) {
        return StatementExecutor.execute(resolved, ctx,
                runtimeFqn, dialectOf(ctx, runtimeFqn, connection),
                // the driver holds a session, never a connection (track B's B1b, 2026-10-29)
                com.legend.exec.PlanSessions.session(connection));
    }

    /** Listener overload — the runner's scoring seam: observes each
     * statement-root assert verdict; the platform keeps the judgment. */
    public static com.legend.exec.@com.legend.base.Nullable ExecutionResult executeResolved(
            com.legend.protocol.spec.ValueSpecification resolved, ModelContext ctx,
            @com.legend.base.Nullable String runtimeFqn,
            java.sql.Connection connection,
            com.legend.exec.@com.legend.base.Nullable AssertListener assertListener) {
        return executeResolved(resolved, ctx, runtimeFqn, connection,
                assertListener, null);
    }

    /** Registration overload (SQLTEXT charter §2): the harness supplies
     * its {@link com.legend.exec.SqlReplayReferee} beside the listener;
     * the env carries both. Production never calls this arity. */
    public static com.legend.exec.@com.legend.base.Nullable ExecutionResult executeResolved(
            com.legend.protocol.spec.ValueSpecification resolved, ModelContext ctx,
            @com.legend.base.Nullable String runtimeFqn,
            java.sql.Connection connection,
            com.legend.exec.@com.legend.base.Nullable AssertListener assertListener,
            com.legend.exec.@com.legend.base.Nullable SqlReplayReferee replayReferee) {
        return executeResolved(resolved, ctx, runtimeFqn, connection, assertListener,
                replayReferee, ExecuteOptions.NONE);
    }

    public static com.legend.exec.@com.legend.base.Nullable ExecutionResult executeResolved(
            com.legend.protocol.spec.ValueSpecification resolved, ModelContext ctx,
            @com.legend.base.Nullable String runtimeFqn,
            java.sql.Connection connection,
            com.legend.exec.@com.legend.base.Nullable AssertListener assertListener,
            com.legend.exec.@com.legend.base.Nullable SqlReplayReferee replayReferee,
            ExecuteOptions options) {
        return StatementExecutor.execute(resolved, ctx,
                runtimeFqn, dialectOf(ctx, runtimeFqn, connection),
                // the driver holds a session, never a connection (track B's B1b, 2026-10-29)
                com.legend.exec.PlanSessions.session(connection),
                assertListener, replayReferee, options);
    }
}
