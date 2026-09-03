// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend;

import com.legend.compiler.element.ModelContext;
import com.legend.exec.Ddl;
import com.legend.exec.ExecutionResult;
import com.legend.exec.Executor;
import com.legend.compiler.spec.SpecCompiler;
import com.legend.compiler.spec.typed.TypedSpec;

/**
 * The K-PHASE STATEMENT EXECUTOR — the orchestration layer extracted from
 * the driver (audit 17): statement sequencing over effectful bodies, call
 * frames, effect analysis, and the K-native dispatch arms (executeInDb,
 * dropAndCreateTableInDb/Schema, print). {@link com.legend.Compiler} stays
 * the ONE driver seam and delegates here; nothing in this class decides
 * pipeline ORDER — it executes already-resolved statements.
 *
 * <p>The execution environment is ONE ambient JDBC connection, one dialect,
 * one optional raw-SQL failure sink ({@link ExecEnv}) — connection-valued
 * expressions are never evaluated.
 */
final class StatementExecutor {

    private StatementExecutor() {
    }

    /** The G½→H→I→J→K back half over a name-RESOLVED query AST. */
    static @com.legend.Nullable ExecutionResult execute(
            com.legend.protocol.spec.ValueSpecification resolved, ModelContext ctx,
            @com.legend.Nullable String runtimeFqn,
            com.legend.sql.dialect.SqlDialect dialect,
            java.sql.Connection connection) {
        return execute(resolved, ctx, runtimeFqn, dialect, connection, null,
                null);
    }

    /** Listener overload: {@code assertListener} observes every
     * statement-root assert verdict (the runner's scoring seam —
     * judging stays here, in one place); {@code replayOracle} is the
     * registered SQL-replay oracle (SQLTEXT charter §2 — same
     * registration seam, same nullable carriage; production passes
     * neither). */
    static @com.legend.Nullable ExecutionResult execute(
            com.legend.protocol.spec.ValueSpecification resolved, ModelContext ctx,
            @com.legend.Nullable String runtimeFqn,
            com.legend.sql.dialect.SqlDialect dialect,
            java.sql.Connection connection,
            com.legend.exec.@com.legend.Nullable AssertListener assertListener,
            com.legend.exec.@com.legend.Nullable SqlReplayOracle replayOracle) {
        SpecCompiler specs = new SpecCompiler(ctx);
        ExecEnv env0 = new ExecEnv(ctx, runtimeFqn, dialect, connection,
                com.legend.validation.DriverPkOption.get());
        ExecEnv env = assertListener == null && replayOracle == null ? env0
                : new ExecEnv(env0.ctx(), env0.runtimeFqn(), env0.dialect(),
                        env0.connection(), env0.addDriverTablePk(),
                        env0.queryLets(), env0.tableReplace(),
                        env0.instanceIds(), assertListener, replayOracle);
        return executeStatements(specs.typeQueryBody(resolved),
                new java.util.ArrayList<>(), specs, env,
                new java.util.ArrayDeque<>());
    }

    /** The K-phase execution environment: ONE ambient connection, ONE
     * dialect (audit 17: recomputing it per arm invited a future
     * mixed-dialect bug), the driver runtime, and the
     * addDriverTablePkForProject execution option (#45 — see
     * {@link com.legend.validation.DriverPkOption}). F7.1: the raw-SQL
     * failure sink is GONE — a failed raw statement THROWS (zero live
     * sink firings on both full sweeps; the corpus runner records
     * failures per SETUP UNIT and keeps its emptiness guard). */
    record ExecEnv(ModelContext ctx, @com.legend.Nullable String runtimeFqn,
            com.legend.sql.dialect.SqlDialect dialect,
            java.sql.Connection connection,
            boolean addDriverTablePk,
            java.util.Map<String, TypedSpec> queryLets,
            java.util.Map<String, String> tableReplace,
            com.legend.exec.InstanceIds instanceIds,
            com.legend.exec.@com.legend.Nullable AssertListener assertListener,
            com.legend.exec.@com.legend.Nullable SqlReplayOracle replayOracle) {
        /** The same environment over another session — the system
         * database's connection for a body that reads the metamodel. */
        ExecEnv withConnection(java.sql.Connection other) {
            return other == connection ? this : new ExecEnv(ctx, runtimeFqn,
                    dialect, other, addDriverTablePk, queryLets, tableReplace,
                    instanceIds, assertListener, replayOracle);
        }

        ExecEnv(ModelContext ctx, @com.legend.Nullable String runtimeFqn,
                com.legend.sql.dialect.SqlDialect dialect,
                java.sql.Connection connection,
                boolean addDriverTablePk,
                java.util.Map<String, TypedSpec> queryLets,
                java.util.Map<String, String> tableReplace,
                com.legend.exec.InstanceIds instanceIds) {
            this(ctx, runtimeFqn, dialect, connection, addDriverTablePk,
                    queryLets, tableReplace, instanceIds, null, null);
        }

        ExecEnv(ModelContext ctx, @com.legend.Nullable String runtimeFqn,
                com.legend.sql.dialect.SqlDialect dialect,
                java.sql.Connection connection,
                boolean addDriverTablePk,
                java.util.Map<String, TypedSpec> queryLets,
                java.util.Map<String, String> tableReplace) {
            // F13: one site-id minter per env — both sides of every
            // verdict share it, so identity ids agree across lowerings
            this(ctx, runtimeFqn, dialect, connection, addDriverTablePk,
                    queryLets, tableReplace, new com.legend.exec.InstanceIds());
        }

        ExecEnv(ModelContext ctx, @com.legend.Nullable String runtimeFqn,
                com.legend.sql.dialect.SqlDialect dialect,
                java.sql.Connection connection,
                boolean addDriverTablePk,
                java.util.Map<String, TypedSpec> queryLets) {
            // historical arity: no connection post-processor hooks
            this(ctx, runtimeFqn, dialect, connection,
                    addDriverTablePk, queryLets, java.util.Map.of());
        }

        ExecEnv(ModelContext ctx, @com.legend.Nullable String runtimeFqn,
                com.legend.sql.dialect.SqlDialect dialect,
                java.sql.Connection connection,
                boolean addDriverTablePk) {
            // run-scoped accumulator of inliner-consumed lets: graph-tree
            // date args keep their source spelling (the serialize key), so
            // every resolver seeds its let env from here (engine
            // inScopeVars)
            this(ctx, runtimeFqn, dialect, connection,
                    addDriverTablePk, new java.util.LinkedHashMap<>());
        }
    }

    /**
     * STATEMENT SEQUENCING — the K-phase orchestration layer. Pure bodies
     * (lets + one result expression) take exactly the classic path:
     * inline (G&frac12;) &rarr; resolve (H) &rarr; lower/execute, one
     * statement. EFFECTFUL bodies — corpus setup functions: a sequence of
     * {@code executeInDb} statements — cannot &beta;-reduce to one
     * expression; each statement executes in order through the full
     * pipeline, and a statement-position call to an effectful function
     * expands as a statement sequence in a FRESH call frame (parameters
     * bound as lets; closed bodies make frames capture-proof — no
     * &alpha;-renaming needed). Value evaluation still ALWAYS lowers to
     * SQL; only the sequencing lives host-side.
     */
    static @com.legend.Nullable ExecutionResult executeStatements(
            java.util.List<TypedSpec> stmts, java.util.List<TypedSpec> letPrefix,
            SpecCompiler specs, ExecEnv env, java.util.Deque<String> frames) {
        ExecutionResult result = null;
        java.util.Map<String, Boolean> effectMemo = new java.util.HashMap<>();
        java.util.Map<String, ExecFrame> execFrames = new java.util.LinkedHashMap<>();
        for (int i = 0; i < stmts.size(); i++) {
            // TDG lane S1: the checker's census CARRIER folds to instance
            // literals HERE (orchestration owns testdatagen; the compiler
            // cannot — layering), before resolve sees the statement
            TypedSpec stmt = com.legend.testdatagen.TestDataGenerationNatives.foldCensus(stmts.get(i), env.ctx(), env.connection(), letPrefix);
            boolean last = i == stmts.size() - 1;
            if (stmt instanceof com.legend.compiler.spec.typed.TypedLet let && !last) {
                // let tds = $r.values(->at(0)/->toOne()): over a RELATION-
                // rooted frame these wrappers are the Result ENVELOPE — the
                // alias IS the same frame (audit 19d B2: the splice rules
                // move verbatim from the harness). Class/scalar roots fall
                // through: their at/toOne are REAL selections.
                ExecFrame alias = aliasFrame(let.value(), execFrames);
                if (alias != null) {
                    execFrames.put(let.name(), alias);
                    continue;
                }
                TypedSpec rhs = let.value();
                while (rhs instanceof com.legend.compiler.spec.typed.TypedFrom rf) {
                    rhs = rf.source();
                }
                if (rhs instanceof com.legend.compiler.spec.typed.TypedNativeCall ec
                        && (com.legend.compiler.element.type.PlatformTypes
                                .isExecuteFqn(ec.callee().qualifiedName())
                            || com.legend.compiler.element.type.PlatformTypes
                                .isLegendQueryFqn(ec.callee().qualifiedName()))) {
                    // EAGER run (engine parity, audit 16 F1): a broken
                    // pipeline surfaces AT the let even when nothing reads
                    // the frame.
                    execFrames.put(let.name(),
                            buildFrame(ec, letPrefix, true, specs, env));
                    continue;
                }
                if (containsEffect(let.value(), specs, effectMemo)) {
                    // let x = executeInDb(...): the effect runs exactly ONCE,
                    // here at the let (engine parity — the corpus binds an
                    // opaque ResultSet handle as a smoke check and never
                    // reads it; β-substitution would drop or double it). The
                    // rhs may be the K-native OR the corpus's own executeInDb
                    // wrapper (a user call). A later READ of the binding has
                    // no frame — wall it up front, never an unbound-variable
                    // surprise.
                    if (!ConnectionLets.onlyConnectionReads(stmts, i + 1,
                            let.name())) {
                        throw new IllegalStateException("reading an"
                                + " executeInDb result binding ('"
                                + let.name() + "') is not supported");
                    }
                    if (rhs instanceof com.legend.compiler.spec.typed.TypedUserCall uc) {
                        executeCallStatement(uc, letPrefix, specs, env, frames);
                    } else {
                        java.util.List<TypedSpec> single =
                                new java.util.ArrayList<>(letPrefix);
                        single.add(let.value());
                        java.util.List<TypedSpec> inlined =
                                new com.legend.compiler.spec.UserCallInliner(
                                specs, spliceHook(execFrames, letPrefix, specs, env))
                                .inlineBody(single);
                        // Phase H runs HERE too (remediation T1.9): an
                        // effect arg derived from a class query must not
                        // reach the Lowerer with TypedGetAll intact
                        com.legend.resolver.StoreResolver letResolver =
                                new com.legend.resolver.StoreResolver(env.ctx(), specs)
                                        .withLetBindings(env.queryLets());
                        inlined = letResolver.resolve(inlined, env.runtimeFqn());
                        executeTyped(inlined, env);
                    }
                    continue;
                }
                letPrefix.add(let);
                continue;
            }
            // a trailing let IS its value (real pure)
            TypedSpec bare = stmt instanceof com.legend.compiler.spec.typed.TypedLet l
                    ? l.value() : stmt;
            // (Phase 1c: a grid VALUE READ never reaches here as a user
            // call — the Typer types it as a relation property read; the
            // TYPE decides, no recognizer needed)
            // EFFECTFUL call statements need the STATEMENT-ORCHESTRATION
            // machinery (sequential effect execution, recursion guard,
            // argument frames) — a nested executeInDb cannot lower, so
            // the side path can never claim these. This is the gate's
            // OWN ground (V11 adjudication: the old double-execution
            // citation died with runCanon; the gate did not). Effectful
            // ASSERTS therefore route to body inlining and get host
            // verdicts, not byte verdicts — register row, V7 territory.
            if (bare instanceof com.legend.compiler.spec.typed.TypedUserCall call
                    && (containsEffect(call, specs, effectMemo) || com.legend.testdatagen.TestDataGenerationNatives.needsBodyRoute(call, specs))) {
                result = executeCallStatement(call, letPrefix, specs, env, frames);
                continue;
            }
            // Clause 2c: a STATEMENT-ROOT assert-family call is a
            // VERDICT — arguments execute in the database, the judgment
            // is World 1's (AssertVerdicts; pre-inline so the assert
            // library's pure bodies never β-inline into SQL). V7 batch
            // 2: the verdict side evaluation carries the SAME envelope
            // splice hook as ordinary statements — an assert reading an
            // execute() handle adjudicates over the spliced chain
            // (audit 19d B2; the splice pin: AssertVerdictSpliceTest).
            ExecutionResult verdict = AssertVerdicts.tryAdjudicate(
                    bare, letPrefix, specs, env,
                    spliceHook(execFrames, letPrefix, specs, env));
            if (verdict != null) {
                result = verdict;
                continue;
            }
            ExecutionResult hosted = hostChannel(bare, letPrefix, specs, env);
            if (hosted != null) {
                result = hosted;
                continue;
            }
            java.util.List<TypedSpec> single = new java.util.ArrayList<>(letPrefix);
            single.add(stmt);
            var stmtInliner = new com.legend.compiler.spec.UserCallInliner(specs,
                    spliceHook(execFrames, letPrefix, specs, env));
            java.util.List<TypedSpec> body = new java.util.ArrayList<>(
                    stmtInliner.inlineBody(single));                      // Phase G½
            env.queryLets().putAll(stmtInliner.queryLets());
            final java.util.List<TypedSpec> stageEnv = body;
            body.replaceAll(b -> com.legend.compiler.spec.NativeDispatch
                    .stage(b, stageEnv, nativeRoutines(specs, env)));

            TypedSpec preRoot = body.get(body.size() - 1);
            if (preRoot instanceof com.legend.compiler.spec.typed.TypedLet pl) {
                preRoot = pl.value();
            }
            while (preRoot instanceof com.legend.compiler.spec.typed.TypedFrom pf) {
                preRoot = pf.source();
            }
            preRoot = foldPairProjection(preRoot);
            // $plan.processingTemplateFunctions — the ExecutionPlan class
            // property (executionPlan.pure:67): every relational node
            // carries relationalPlanSupportFunctions(connection), deduped
            // plan-wide (executionPlan_generation.pure:215)
            // CATALOG DISPATCH at the statement's value position (§4AG
            // — ladder migration #22, zero function-name if-checks):
            // CONTEXT_OWNER rows run their registered ARM (assertError:
            // f's body runs in the database under the arm's catch);
            // HANDLE rows run their registered FORCE (execute: the eager
            // frame run IS the value); everything the plan reader can
            // answer ($plan navigation — the engine's own plan API,
            // evaluated over the PLAN NODE MODEL) returns its value.
            if (preRoot instanceof com.legend.compiler.spec.typed
                            .TypedNativeCall cat) {
                var kind = com.legend.compiler.element.type.PlatformTypes
                        .IMPLEMENTATION_KIND.get(
                                cat.callee().qualifiedName());
                if (kind == com.legend.compiler.element.type.PlatformTypes
                        .NativeImpl.CONTEXT_OWNER) {
                    result = AssertErrorNative.run(cat, letPrefix, specs,
                            env, frames);
                    continue;
                }
                if (kind == com.legend.compiler.element.type.PlatformTypes
                        .NativeImpl.HANDLE
                        && com.legend.compiler.element.type.PlatformTypes
                                .handleForcesAtValuePosition(
                                        cat.callee().qualifiedName())) {
                    result = buildFrame(cat, letPrefix, true, specs, env)
                            .result();
                    continue;
                }
            }
            Object walked = planWalk(preRoot, specs, env);
            if (walked != null) {
                result = walkResult(walked, preRoot.info().type());
                continue;
            }
            com.legend.resolver.StoreResolver resolver =
                    new com.legend.resolver.StoreResolver(env.ctx(), specs)
                            .withLetBindings(env.queryLets());
            body = resolver.resolve(body, env.runtimeFqn());              // Phase H
            // C2.2: stores bound to DIFFERENT connections cannot share
            // the one session connection — wall, never wrong-database rows
            CrossStoreGuard.check(body, env.ctx(), env.runtimeFqn());
            if (env.addDriverTablePk()) {
                // the engine's addDriverTablePkForProject option (#45):
                // projections gain driver-table PK columns; non-projection
                // statements pass through unchanged
                body = com.legend.resolver.DriverPkAppend.apply(
                        body, env.ctx());
            }
            result = executeTyped(body, frameReplaceEnv(stmt, execFrames,
                    env));
        }
        return result;
    }

    /** The statement's env widened with the tableReplace maps of every
     * exec frame the statement REFERENCES (union; conflicting renames
     * throw — never a silent pick). The re-plan of a spliced chain is
     * the architecture; the renames must ride with it (ledger cluster
     * 59). */
    private static ExecEnv frameReplaceEnv(TypedSpec stmt,
            java.util.Map<String, ExecFrame> execFrames, ExecEnv env) {
        java.util.Map<String, String> union = null;
        for (var e : execFrames.entrySet()) {
            if (e.getValue().tableReplace().isEmpty()
                    || !com.legend.compiler.spec.UserCallInliner
                            .referencesVar(stmt, e.getKey())) {
                continue;
            }
            if (union == null) {
                union = new java.util.LinkedHashMap<>(env.tableReplace());
            }
            for (var r : e.getValue().tableReplace().entrySet()) {
                String prev = union.putIfAbsent(r.getKey(), r.getValue());
                if (prev != null && !prev.equals(r.getValue())) {
                    throw new IllegalStateException("conflicting table"
                            + " renames for '" + r.getKey() + "': '" + prev
                            + "' vs '" + r.getValue() + "'");
                }
            }
        }
        return union == null ? env
                : new ExecEnv(env.ctx(), env.runtimeFqn(), env.dialect(),
                        env.connection(),
                        env.addDriverTablePk(), env.queryLets(), union,
                        env.instanceIds(), env.assertListener(),
                        env.replayOracle());
    }

    /**
     * The engine's SQL-text surface: lower the query lambda through the
     * platform's own G½->H->I against the MAPPING ARGUMENT and render with
     * the engine-style dialect. H2 only — other DatabaseTypes throw until
     * their renderers exist. Never lowers, never touches the connection.
     */
    private static @com.legend.Nullable ExecutionResult toSqlString(
            com.legend.compiler.spec.typed.TypedNativeCall call,
            com.legend.compiler.spec.SpecCompiler specs, ExecEnv env) {
        // toSQLStringPretty's RUNTIME overload carries the connection in
        // arg 2 — resolve its DatabaseType; the enum overloads keep the
        // direct tail read
        TypedSpec dbArg = call.args().get(2);
        String db = dbArg instanceof com.legend.compiler.spec.typed.TypedEnumValue
                ? typedEnumTail(dbArg)
                : String.valueOf(ConnectionFlags.databaseTypeOf(dbArg));
        com.legend.sql.dialect.EngineStyleH2 renderer = switch (db) {
            case "H2" -> new com.legend.sql.dialect.EngineStyleH2();
            case "DB2" -> new com.legend.sql.dialect.EngineStyleDB2();
            // Composite = the engine-DEFAULT spellings (native trim/pad/
            // cbrt, plain char_length); divergent goldens fail honestly
            case "Composite" -> new com.legend.sql.dialect.EngineStyleComposite();
            default -> throw new com.legend.error.NotImplementedException(
                    "toSQLString for DatabaseType." + db
                    + " — only the H2/DB2 engine-style renderers are built");
        };
        if (!(call.args().get(0)
                instanceof com.legend.compiler.spec.typed.TypedLambda lam)) {
            throw new com.legend.error.NotImplementedException(
                    "toSQLString whose query argument is not a lambda literal");
        }
        if (!(call.args().get(1)
                instanceof com.legend.compiler.spec.typed.TypedPackageableRef pr)) {
            throw new com.legend.error.NotImplementedException(
                    "toSQLString mapping argument must be a mapping reference");
        }
        EngineSql es = engineSql(lam, pr.fullPath(), specs, env, renderer);
        com.legend.sql.SqlQuery post = com.legend.lowering.SqlPostProcessors
                .apply(es.plan(), com.legend.exec.PostProcessBoundary
                        .tableReplace());
        return new ExecutionResult.Scalar(post == es.plan() ? es.sql()
                        : renderer.render(post),
                com.legend.compiler.element.type.Type.Primitive.STRING);
    }

    /** The engine-style SQL pipeline shared by toSQLString and the plan
     * printer: G½ inline, H resolve against the MAPPING ARGUMENT, root
     * form, I lower — IR plus rendered text. */
    record EngineSql(com.legend.sql.SqlQuery plan, String sql,
            java.util.List<TypedSpec> body) {
    }

    private static EngineSql engineSql(
            com.legend.compiler.spec.typed.TypedLambda lam,
            String mappingFqn, com.legend.compiler.spec.SpecCompiler specs,
            ExecEnv env,
            com.legend.sql.dialect.EngineStyleH2 renderer) {
        return engineSql(lam.body(), mappingFqn, specs, env, renderer,
                java.util.Map.of(),
                java.util.function.UnaryOperator.identity());
    }

    /** The body form, with plan-TEMPLATE parameters: each named free
     * variable lowers to the engine's {@code ${name}} placeholder
     * (value = string-typed, driving the freemarker quote template). */
    static EngineSql engineSql(java.util.List<TypedSpec> raw,
            String mappingFqn, com.legend.compiler.spec.SpecCompiler specs,
            ExecEnv env,
            com.legend.sql.dialect.EngineStyleH2 renderer,
            java.util.Map<String, com.legend.sql.SqlExpr.PlanParam>
                    planParams,
            java.util.function.UnaryOperator<String> tableRenames) {
        return engineSql(raw, mappingFqn, specs, env, renderer, planParams,
                tableRenames, java.util.List.of());
    }

    /** {@code chainMappings}: ModelChainConnection mappings from the plan
     * surface's runtime argument (M2M2R — ~src classes resolve through
     * them). */
    private static EngineSql engineSql(java.util.List<TypedSpec> raw,
            String mappingFqn, com.legend.compiler.spec.SpecCompiler specs,
            ExecEnv env,
            com.legend.sql.dialect.EngineStyleH2 renderer,
            java.util.Map<String, com.legend.sql.SqlExpr.PlanParam>
                    planParams,
            java.util.function.UnaryOperator<String> tableRenames,
            java.util.List<String> chainMappings) {
        java.util.List<TypedSpec> body =
                new com.legend.compiler.spec.UserCallInliner(specs)
                        .inlineBody(raw);
        boolean temporalRoot = com.legend.compiler.element.Temporal
                .anyTemporalGetAll(body, env.ctx());
        body = new com.legend.resolver.StoreResolver(env.ctx(), specs)
                .resolve(body, env.runtimeFqn(), mappingFqn, chainMappings);
        body = com.legend.resolver.RelationalRootForm.apply(
                body, env.ctx(), mappingFqn);
        com.legend.lowering.Lowerer lw = new com.legend.lowering.Lowerer(
                t -> com.legend.compiler.element.ClassLayouts.layoutOf(env.ctx(), t),
                f -> env.ctx().findClass(f).isPresent());
        if (!temporalRoot) {
            lw = lw.withEngineExistsJoinForm();
        }
        planParams.values().forEach(lw::bindPlanParam);
        com.legend.sql.SqlQuery plan; // ENGINE-TEXT: wire coercions read bare
        try (var ignored = com.legend.lowering.EngineTextBoundary.enter();
                var ignored2 = com.legend.sql.dialect.TextGoldens.enter()) {
            plan = lw.lower(body);
        }
        // engine plans keep enum columns RAW (host-side decode) — the
        // plan-text form of enum-mapped columns/parameters
        if (plan instanceof com.legend.sql.SqlSelect sel
                && com.legend.compiler.element.type.Type.relationSchema(
                        body.get(body.size() - 1).info().type())
                        instanceof com.legend.compiler.element.type.Type
                                .RelationType rt) {
            plan = com.legend.plan.PlanEnumForm.apply(sel, rt);
        }
        // the runtime's relationalMapperPostProcessor renames (extracted
        // structurally from the plan call's runtime argument)
        if (plan instanceof com.legend.sql.SqlQuery p2) {
            plan = com.legend.lowering.SqlPostProcessors.apply(p2,
                    tableRenames);
        }
        // TEXT-channel rendering (synthetic scalar-map aliases drop)
        String text;
        try (var ignored3 = com.legend.sql.dialect.TextGoldens.enter()) {
            text = renderer.render(plan);
        }
        return new EngineSql(plan, text, body);
    }

    /** The host-channel dispatch (oracle-not-runtime principle,
     * user-ratified): recognized grid-read chains lower to MIR and
     * execute through the standard Executor (typed relations since
     * Phase 1c), store navigation resolves against the COMPILED
     * MODEL (StoreNav), and anything else walls with the principle's
     * name — the interpreter that executed engine compiler source is
     * DELETED. */
    private static @com.legend.Nullable ExecutionResult hostEvalAtSeam(TypedSpec root,
            java.util.Map<String, TypedSpec> lets, ExecEnv env) {
        // (Phase 1c grid endgame: ResultNav is DELETED — grid chains are
        // typed relations the ordinary pipeline serves; the seam is
        // StoreNav's model-fact channel alone)
        ExecutionResult nav = com.legend.exec.StoreNav.tryEval(
                root, lets, env.ctx());
        if (nav != null) {
            return nav;
        }
        throw new com.legend.error.NotImplementedException(
                "host channel: this chain would need interpreted engine"
                + " code — engine/legend-pure source is ORACLE material,"
                + " never our runtime (user-ratified 2026-08-18); build"
                + " the feature natively (typed relations/StoreNav/walk family)"
                + " or decline the test with a verdict"
                + (System.getenv("LL_TMP_DEBUG") != null
                        ? " [root=" + root + "]" : ""));
    }

    /** HOST channel BEFORE the inliner: recursive corpus functions over
     * metamodel instances cannot β-inline (the inliner is loud on
     * cycles) — the host evaluator runs them with real call frames.
     * ROOT-position executeInDb stays a SETUP statement (the ambient-
     * connection arm in executeTyped owns it — the same ordering that
     * protects the post-inline hook); only VALUE-position reads route.
     * Null = not host-routed. */
    private static @com.legend.Nullable ExecutionResult hostChannel(TypedSpec bare,
            java.util.List<TypedSpec> letPrefix,
            com.legend.compiler.spec.SpecCompiler specs, ExecEnv env) {
        boolean rootSetup = bare
                instanceof com.legend.compiler.spec.typed.TypedNativeCall rnc
                && com.legend.compiler.element.type.PlatformTypes
                        .isRawSqlBoundary(rnc.callee().qualifiedName());
        if (rootSetup) {
            return null;
        }
        java.util.Map<String, TypedSpec> hostLets =
                new java.util.LinkedHashMap<>();
        for (TypedSpec lp : letPrefix) {
            if (lp instanceof com.legend.compiler.spec.typed.TypedLet hl) {
                hostLets.put(hl.name(), hl.value());
            }
        }
        if (!com.legend.exec.StoreNav.owns(bare, hostLets)) {
            return null;
        }
        return hostEvalAtSeam(bare, hostLets, env);
    }

    /** {@code planToString(executionPlan(func, MAPPING, runtime, ...),
     * ext)}: the SINGLE-RELATIONAL literal plan text (#47 pilot —
     * com.legend.plan.PlanText owns the format). */
    /** The executor's routine table for catalog-dispatched natives
     * (NativeDispatch): exactly the catalog's JAVA_ROUTINE rows. The
     * rules and literal minting live in the COMPILER (NativeDispatch,
     * Invariant 7); this supplies plan compilation only. */
    private static java.util.Map<String, com.legend.compiler.spec
            .NativeDispatch.Routine> nativeRoutines(
            com.legend.compiler.spec.SpecCompiler specs, ExecEnv env) {
        com.legend.compiler.spec.NativeDispatch.Routine text =
                (call, letPrefix) -> {
                    ExecutionResult r = planToString(call, letPrefix,
                            specs, env);
                    return String.valueOf(((ExecutionResult.Scalar)
                            java.util.Objects.requireNonNull(r,
                                    "plan text")).value());
                };
        com.legend.compiler.spec.NativeDispatch.Routine sqlText =
                (call, letPrefix) -> {
                    ExecutionResult r = toSqlString(call, specs, env);
                    return String.valueOf(((ExecutionResult.Scalar)
                            java.util.Objects.requireNonNull(r,
                                    "sql text")).value());
                };
        return java.util.Map.of(
                com.legend.compiler.element.type.PlatformTypes
                        .PLAN_TO_STRING, text,
                com.legend.compiler.element.type.PlatformTypes
                        .PLAN_TO_STRING_WITHOUT_FORMATTING,
                (call, letPrefix) -> text.value(call, letPrefix)
                        .replace("\n", "").replace(" ", ""),
                com.legend.compiler.element.type.PlatformTypes
                        .TO_SQL_STRING, sqlText,
                com.legend.compiler.element.type.PlatformTypes
                        .TO_SQL_STRING_PRETTY, sqlText);
    }

    private static @com.legend.Nullable ExecutionResult planToString(
            com.legend.compiler.spec.typed.TypedNativeCall call,
            com.legend.compiler.spec.SpecCompiler specs, ExecEnv env) {
        return planToString(call, java.util.List.of(), specs, env);
    }

    private static @com.legend.Nullable ExecutionResult planToString(
            com.legend.compiler.spec.typed.TypedNativeCall call,
            java.util.List<TypedSpec> letPrefix,
            com.legend.compiler.spec.SpecCompiler specs, ExecEnv env) {
        // the plan value chases through the LET PREFIX (getAll-76 lane:
        // `let plan = executionPlan(...)` then `$plan->planToString(...)`
        // inside an assert — the handle is a symbolic binding)
        TypedSpec a0 = com.legend.compiler.spec.ExecuteChainAssembly
                .letBound(call.args().get(0), letPrefix);
        if (!(a0
                instanceof com.legend.compiler.spec.typed.TypedNativeCall ep)
                || !com.legend.compiler.element.type.PlatformTypes
                        .EXECUTION_PLAN.equals(ep.callee().qualifiedName())) {
            throw new com.legend.error.NotImplementedException(
                    "planToString over a non-executionPlan value");
        }
        // preval(lambda, ext) pre-folds constants at plan time — the
        // wrapped lambda IS the query (our lowering folds literals
        // anyway, so the unwrap is semantically inert here)
        com.legend.compiler.spec.typed.TypedSpec q = ep.args().get(0);
        if (q instanceof com.legend.compiler.spec.typed.TypedNativeCall pv
                && com.legend.compiler.element.type.PlatformTypes.PREVAL
                        .equals(pv.callee().qualifiedName())) {
            q = pv.args().get(0);
        }
        if (!(q instanceof com.legend.compiler.spec.typed.TypedLambda lam)) {
            throw new com.legend.error.NotImplementedException(
                    "executionPlan whose query argument is not a lambda");
        }
        // the 2-arg overload executionPlan(func, extensions) carries its
        // context IN the query — ->from(mapping, runtime) on the terminal
        String mappingFqn;
        boolean hasRuntimeArg;
        java.util.List<String> queryChain = java.util.List.of();
        if (ep.args().get(1) instanceof
                com.legend.compiler.spec.typed.TypedPackageableRef pr) {
            mappingFqn = pr.fullPath();
            hasRuntimeArg = ep.args().size() > 2;
        } else {
            // a DUMMY ^Mapping(name='') argument (or the 2-arg overload)
            // defers to the query's own ->from calls — cross-mapping
            // queries carry one per branch; the FIRST one names the plan
            mappingFqn = firstFromMapping(
                    lam.body().get(lam.body().size() - 1));
            hasRuntimeArg = false;
            if (mappingFqn == null) {
                // QUERY-SIDE CHAIN dispatch (withChainedMappings->from(rt)
                // — a runtime-only from carrying chainMappings): the chain
                // mapping that BINDS the root class names the plan
                java.util.List<String> qChain = firstFromChainMappings(
                        lam.body().get(lam.body().size() - 1));
                String rc0 = rootGetAllClass(lam.body());
                if (!qChain.isEmpty() && rc0 != null) {
                    var srcs = new com.legend.resolver.ClassSources(
                            env.ctx(), specs);
                    java.util.List<String> binders = qChain.stream()
                            .distinct()
                            .filter(m2 -> srcs.binds(m2, rc0)).toList();
                    if (binders.size() == 1) {
                        mappingFqn = binders.get(0);
                        queryChain = qChain;
                    }
                }
            }
            if (mappingFqn == null) {
                throw new com.legend.error.NotImplementedException(
                        "executionPlan mapping argument must be a reference"
                        + " (or the query must carry ->from), got "
                        + ep.args().get(1).getClass().getSimpleName());
            }
        }
        // connection FLAGS ride instance properties; a testRuntime(true)
        // HELPER call carries them inside its body — inline once so the
        // property walkers see the constructed connection (the
        // quoteIdentifiers-flag goldens' testRuntime(quote) idiom)
        TypedSpec rtArg = hasRuntimeArg
                ? new com.legend.compiler.spec.UserCallInliner(specs)
                        .inlineBody(java.util.List.of(ep.args().get(2)))
                        .get(0)
                : null;
        boolean quote = rtArg != null && ConnectionFlags.quoteIdentifiersOf(rtArg);
        String tz = rtArg != null ? ConnectionFlags.timeZoneOf(rtArg) : null;
        String fromConn = rtArg == null
                ? firstFromConnectionName(
                        lam.body().get(lam.body().size() - 1))
                : null;
        String connName = rtArg != null
                ? ConnectionFlags.connectionNameOf(rtArg)
                : fromConn != null ? fromConn
                : "TestDatabaseConnection(type = \"H2\")";
        String dbType = rtArg != null ? ConnectionFlags.databaseTypeOf(rtArg) : "H2";
        if (!lam.parameters().isEmpty() || lam.body().size() > 1
                // a lone LET is a sequence too (E2E §4.4 cluster 1):
                // the engine prints Allocation, never bare Relational
                || (lam.body().size() == 1 && lam.body().get(0)
                        instanceof com.legend.compiler.spec.typed.TypedLet)) {
            return sequencePlan(lam, mappingFqn, specs, env, quote, tz,
                    connName, dbType);
        }
        String rootClass = rootGetAllClass(lam.body());
        if (rootClass == null) {
            throw new com.legend.error.NotImplementedException(
                    "planToString: no getAll root (multi-node plans"
                    + " pending)");
        }
        java.util.List<String> chainMaps = new java.util.ArrayList<>(
                rtArg != null
                        ? com.legend.compiler.spec.typed.TypedFrom
                                .chainMappingsIn(rtArg)
                        : java.util.List.of());
        queryChain.stream().filter(m2 -> !chainMaps.contains(m2))
                .forEach(chainMaps::add);
        ExecutionResult crossDb = crossDbTdsPlan(lam, mappingFqn, specs,
                env, quote, tz, connName, dbType, rootClass, chainMaps);
        if (crossDb != null) {
            return crossDb;
        }
        EngineSql es = engineSql(lam.body(), mappingFqn, specs, env,
                planDialect(dbType, quote, tz), java.util.Map.of(),
                java.util.function.UnaryOperator.identity(), chainMaps);
        return new ExecutionResult.Scalar(
                com.legend.plan.PlanText.single(env.ctx(), rootClass,
                        mappingFqn, es.plan(), es.sql(),
                        // PRE-resolution body: the TDS-vs-Class shape and
                        // the documentation channel live in the G output
                        // (post-H everything is a relation)
                        lam.body(), connName, chainMaps),
                com.legend.compiler.element.type.Type.Primitive.STRING);
    }

    /** The engine's CROSS-STORE plan split (executionPlan tdsJoinTwoDB*
     * goldens): a TDS join whose sides live in DIFFERENT databases
     * becomes Sequence(Allocation(name=tdsVar, value=(Relational(left))),
     * Relational(join sql with the left side spliced as
     * {@code (${tdsVar})} — the engine VarSetPlaceHolder)). Null when the
     * terminal has no cross-store TDS join (the single-node path
     * continues). Type and resultColumns for the terminal resolve over
     * the ORIGINAL (pre-splice) plan — physical typing needs the real
     * from tree; only the SQL TEXT renders the placeholder form. */
    private static @com.legend.Nullable ExecutionResult crossDbTdsPlan(
            com.legend.compiler.spec.typed.TypedLambda lam,
            String mappingFqn, com.legend.compiler.spec.SpecCompiler specs,
            ExecEnv env, boolean quote, @com.legend.Nullable String tz,
            @com.legend.Nullable String connName,
            @com.legend.Nullable String dbType, String rootClass,
            java.util.List<String> chainMaps) {
        TypedSpec term = lam.body().get(lam.body().size() - 1);
        com.legend.compiler.spec.typed.TypedJoin xj = null;
        java.util.ArrayDeque<TypedSpec> work = new java.util.ArrayDeque<>();
        work.add(term);
        while (!work.isEmpty()) {
            TypedSpec t = work.poll();
            if (t instanceof com.legend.compiler.spec.typed.TypedJoin j
                    && j.prefix().isEmpty()) {
                String a = streamStoreOf(j.left(), env.ctx(), mappingFqn);
                String b = streamStoreOf(j.right(), env.ctx(), mappingFqn);
                if (!a.isEmpty() && !b.isEmpty() && !a.equals(b)) {
                    xj = j;
                    break;
                }
            }
            work.addAll(t.children());
        }
        if (xj == null || !com.legend.compiler.element.type.Type
                .isRelation(xj.left().info().type())) {
            return null;
        }
        // the LEFT SPINE of nested cross-store joins, outermost first:
        // each spine join's LEFT becomes one Allocation (tdsVar,
        // tdsVar_0, ...) whose SQL splices the PREVIOUS var; the
        // outermost join is the terminal (tdsTwoJoinThreeDB: two flat
        // Allocations, never nested Sequences)
        java.util.List<com.legend.compiler.spec.typed.TypedJoin> spine =
                new java.util.ArrayList<>();
        com.legend.compiler.spec.typed.TypedJoin cur = xj;
        while (true) {
            spine.add(cur);
            com.legend.compiler.spec.typed.TypedJoin inner = null;
            java.util.ArrayDeque<TypedSpec> lw = new java.util.ArrayDeque<>();
            lw.add(cur.left());
            while (!lw.isEmpty()) {
                TypedSpec t = lw.poll();
                if (t instanceof com.legend.compiler.spec.typed.TypedJoin j2
                        && j2.prefix().isEmpty()) {
                    String a = streamStoreOf(j2.left(), env.ctx(), mappingFqn);
                    String b = streamStoreOf(j2.right(), env.ctx(), mappingFqn);
                    if (!a.isEmpty() && !b.isEmpty() && !a.equals(b)) {
                        inner = j2;
                        break;
                    }
                }
                lw.addAll(t.children());
            }
            if (inner == null) {
                break;
            }
            cur = inner;
        }
        java.util.List<String> allocs = new java.util.ArrayList<>();
        String prevVar = null;
        for (int k = spine.size() - 1; k >= 0; k--) {
            TypedSpec at = k == spine.size() - 1
                    ? spine.get(k).left() : spine.get(k + 1);
            String var = prevVar == null ? "tdsVar"
                    : "tdsVar_" + (allocs.size() - 1);
            String aRoot = rootGetAllClass(java.util.List.of(at));
            if (aRoot == null) {
                return null;
            }
            EngineSql aEs = engineSql(java.util.List.of(at), mappingFqn,
                    specs, env, planDialect(dbType, quote, tz),
                    java.util.Map.of(),
                    java.util.function.UnaryOperator.identity(), chainMaps);
            String aSql = prevVar == null ? aEs.sql()
                    : com.legend.plan.PlanText.spliceLeftVar(aEs.plan(),
                            prevVar,
                            planDialect(dbType, quote, tz)::render);
            if (aSql == null) { return null; }
            String[] aImpl = com.legend.lineage.ScanRelations.rootImpl(
                    env.ctx(), mappingFqn, aRoot, chainMaps);
            allocs.add(com.legend.plan.PlanText.allocation(var,
                    com.legend.plan.PlanText.typeBlock(env.ctx(), aRoot,
                            aImpl, aEs.plan(), java.util.List.of(at),
                            mappingFqn),
                    com.legend.plan.PlanText.single(env.ctx(), aRoot,
                            mappingFqn, aEs.plan(), aSql,
                            java.util.List.of(at), connName, chainMaps)));
            prevVar = var;
        }
        EngineSql fullEs = engineSql(lam.body(), mappingFqn, specs, env,
                planDialect(dbType, quote, tz), java.util.Map.of(),
                java.util.function.UnaryOperator.identity(), chainMaps);
        String splicedSql = com.legend.plan.PlanText.spliceLeftVar(
                fullEs.plan(), java.util.Objects.requireNonNull(prevVar),
                planDialect(dbType, quote, tz)::render);
        if (splicedSql == null) { return null; }
        String terminal = com.legend.plan.PlanText.single(env.ctx(),
                rootClass, mappingFqn, fullEs.plan(), splicedSql,
                lam.body(), connName, chainMaps,
                com.legend.plan.PlanText.colsPlanFor(
                        fullEs.plan(), prevVar));
        String[] impl = com.legend.lineage.ScanRelations.rootImpl(
                env.ctx(), mappingFqn, rootClass, chainMaps);
        java.util.List<String> children = new java.util.ArrayList<>(allocs);
        children.add(terminal);
        return new ExecutionResult.Scalar(
                com.legend.plan.PlanText.sequence(
                        com.legend.plan.PlanText.typeBlock(env.ctx(),
                                rootClass, impl, fullEs.plan(),
                                lam.body(), mappingFqn),
                        children),
                com.legend.compiler.element.type.Type.Primitive.STRING);
    }

    /** Pre-order search for the first {@code TypedFrom} carrying a
     * connection-name hint (instance-runtime from()). */
    private static @com.legend.Nullable String firstFromConnectionName(
            com.legend.compiler.spec.typed.TypedSpec t) {
        if (t instanceof com.legend.compiler.spec.typed.TypedFrom fr
                && fr.connectionName() != null) {
            return fr.connectionName();
        }
        for (com.legend.compiler.spec.typed.TypedSpec c : t.children()) {
            String r = firstFromConnectionName(c);
            if (r != null) {
                return r;
            }
        }
        return null;
    }

    /** Pre-order search for the first {@code TypedFrom} carrying
     * chainMappings (the query-side withChainedMappings channel). */
    private static java.util.List<String> firstFromChainMappings(
            com.legend.compiler.spec.typed.TypedSpec t) {
        if (t instanceof com.legend.compiler.spec.typed.TypedFrom fr
                && !fr.chainMappings().isEmpty()) {
            return fr.chainMappings();
        }
        for (com.legend.compiler.spec.typed.TypedSpec c : t.children()) {
            java.util.List<String> r = firstFromChainMappings(c);
            if (!r.isEmpty()) {
                return r;
            }
        }
        return java.util.List.of();
    }

    /** Pre-order search for the first {@code ->from(mapping, …)} in the
     * query tree — the branch-level context of cross-mapping queries. */
    private static @com.legend.Nullable String firstFromMapping(
            com.legend.compiler.spec.typed.TypedSpec t) {
        if (t instanceof com.legend.compiler.spec.typed.TypedFrom fr
                && fr.mapping().isPresent()) {
            return fr.mapping().get().fullPath();
        }
        for (com.legend.compiler.spec.typed.TypedSpec c : t.children()) {
            String m = firstFromMapping(c);
            if (m != null) {
                return m;
            }
        }
        return null;
    }

    /** The SEQUENCE envelope: parameterized lambdas open with a
     * FunctionParametersValidationNode, each let becomes an Allocation
     * (literal values = Constant nodes), and the terminal Relational
     * lowers with every open variable as a {@code ${name}} plan-template
     * parameter. */
    private static @com.legend.Nullable ExecutionResult sequencePlan(
            com.legend.compiler.spec.typed.TypedLambda lam,
            String mappingFqn, com.legend.compiler.spec.SpecCompiler specs,
            ExecEnv env, boolean quote, @com.legend.Nullable String timeZone,
            @com.legend.Nullable String connName, @com.legend.Nullable String dbType) {
        var fnType = lam.functionType();
        java.util.LinkedHashMap<String, com.legend.sql.SqlExpr.PlanParam>
                params = new java.util.LinkedHashMap<>();
        java.util.LinkedHashMap<String, String> paramSpells =
                new java.util.LinkedHashMap<>();
        java.util.List<String> children = new java.util.ArrayList<>();
        if (!lam.parameters().isEmpty()) {
            StringBuilder ps = new StringBuilder();
            for (int i = 0; i < lam.parameters().size(); i++) {
                var p = fnType.params().get(i);
                if (i > 0) {
                    ps.append(", ");
                }
                ps.append(lam.parameters().get(i)).append(':')
                        .append(com.legend.plan.PlanText
                                .pureTypeName(p.type()))
                        .append(multBracket(p.multiplicity()));
                paramSpells.put(lam.parameters().get(i),
                        com.legend.plan.PlanText.pureTypeName(p.type())
                                + multBracket(p.multiplicity()));
                boolean opt = p.multiplicity() instanceof
                        com.legend.compiler.element.type.Multiplicity
                                .Bounded ob
                        && ob.lower() == 0
                        && Integer.valueOf(1).equals(ob.upper());
                String emFn = p.type() instanceof com.legend.compiler
                        .element.type.Type.EnumType et
                        ? com.legend.plan.PlanText.enumMapFnOf(env.ctx(),
                                mappingFqn, et.fqn())
                        : null;
                params.put(lam.parameters().get(i),
                        new com.legend.sql.SqlExpr.PlanParam(
                                lam.parameters().get(i),
                                com.legend.lowering.PlanParams.kindOf(
                                        p.type()), opt, emFn));
            }
            children.add(com.legend.plan.PlanText
                    .functionParametersNode(ps.toString()));
        }
        for (int i = 0; i < lam.body().size() - 1; i++) {
            if (!(lam.body().get(i)
                    instanceof com.legend.compiler.spec.typed.TypedLet let)) {
                throw new com.legend.error.NotImplementedException(
                        "plan: non-let intermediate statement");
            }
            children.add(PlanAllocations.node(let, mappingFqn, specs, env,
                    params, paramSpells, quote, timeZone, dbType));
            params.put(let.name(), new com.legend.sql.SqlExpr.PlanParam(
                    let.name(), com.legend.lowering.PlanParams.kindOf(
                            let.info().type())));
        }
        TypedSpec term = lam.body().get(lam.body().size() - 1);
        if (term instanceof com.legend.compiler.spec.typed.TypedLet tlet) {
            // TRAILING let = Allocation node (ledger cluster 36; engine
            // processes a let cluster into AllocationExecutionNode and
            // emits Sequence only when clusters != 1) — a lone let IS
            // the plan, no Sequence envelope.
            children.add(java.util.Objects.requireNonNull(PlanAllocations
                    .node(tlet, mappingFqn, specs, env, params, paramSpells,
                            quote, timeZone, dbType)));
            if (children.size() != 1) {
                throw new com.legend.error.NotImplementedException(
                        "plan: trailing let in a multi-node sequence"
                        + " (envelope type block from the let pending)");
            }
            return new ExecutionResult.Scalar(children.get(0),
                    com.legend.compiler.element.type.Type.Primitive.STRING);
        }
        String rootClass = rootGetAllClass(java.util.List.of(term));
        if (rootClass == null) {
            throw new com.legend.error.NotImplementedException(
                    "plan: sequence terminal without a getAll root");
        }
        EngineSql es = engineSql(java.util.List.of(term), mappingFqn, specs,
                env, planDialect(dbType, quote, timeZone), params,
                java.util.function.UnaryOperator.identity());
        String[] impl = com.legend.lineage.ScanRelations.rootImpl(
                env.ctx(), mappingFqn, rootClass);
        // temp-table IN protocol + envelope assembly (extracted at the
        // file guardrail — PlanEnvelope owns the block-vs-sequence rule)
        return PlanEnvelope.emit(es, children, env, rootClass, impl,
                mappingFqn, term, connName, dbType,
                (com.legend.sql.dialect.EngineStyleH2)
                        planDialect(dbType, quote, timeZone),
                !lam.parameters().isEmpty());
    }

    private static @com.legend.Nullable String multBracket(
            com.legend.compiler.element.type.Multiplicity m) {
        return "[" + sizeRange(m) + "]";
    }

    static @com.legend.Nullable String sizeRange(
            com.legend.compiler.element.type.Multiplicity m) {
        if (m instanceof com.legend.compiler.element.type.Multiplicity
                .Bounded b) {
            if (b.upper() != null) {
                return b.lower() == b.upper() ? String.valueOf(b.lower())
                        : b.lower() + ".." + b.upper();
            }
            // unbounded: [*] (lower 0) or [n..*]
            return b.lower() == 0 ? "*" : b.lower() + "..*";
        }
        throw new com.legend.error.NotImplementedException(
                "plan: multiplicity spelling for " + m + " pending");
    }


    /** The plan connection handle (cluster 60): the engine's generated
     * plan carries the runtime connection on SQLExecutionNode, with
     * processRuntimeTestConnections' DDL expansion on testDataSetupSqls
     * — a TestDatabaseConnection with a PRESENT csv ('' is a present
     * [0..1] value in pure) and no declared sqls gets setUpDataSQLs
     * text; a LocalH2DatasourceSpecification concatenates the expansion
     * onto its declared sqls. */
    private static com.legend.plan.@com.legend.Nullable PlanConn planConnOf(
            @com.legend.Nullable TypedSpec rtArg, ExecEnv env) {
        if (rtArg == null) {
            return new com.legend.plan.PlanConn(
                    "TestDatabaseConnection", "H2", null,
                    java.util.List.of(), null);
        }
        var ni = ConnectionFlags.connectionInstanceOf(rtArg);
        String storeFqn = connectionStoreElementOf(rtArg);
        com.legend.model.DatabaseDefinition db = storeFqn == null ? null
                : env.ctx().findDatabase(storeFqn).orElse(null);
        java.util.function.Function<String, java.util.Optional<
                com.legend.model.DatabaseDefinition>> lookup =
                f -> env.ctx().findDatabase(f);
        if (ni == null) {
            return new com.legend.plan.PlanConn(
                    "TestDatabaseConnection", "H2", null,
                    java.util.List.of(), null);
        }
        String kind = switch (ni.classFqn()) {
            case "meta::external::store::relational::runtime"
                    + "::DatabaseConnection" -> "DatabaseConnection";
            case "meta::external::store::relational::runtime"
                    + "::RelationalDatabaseConnection" ->
                    "RelationalDatabaseConnection";
            case "meta::external::store::relational::runtime"
                    + "::TestDatabaseConnection" -> "TestDatabaseConnection";
            default -> throw new IllegalStateException(
                    "plan connection: unmatched connection class "
                    + ni.classFqn());
        };
        String type = String.valueOf(ConnectionFlags.dbTypeOf(ni));
        String csv = ni.properties().get("testDataSetupCsv")
                instanceof com.legend.compiler.spec.typed.TypedCString c
                ? c.value() : null;
        java.util.List<String> sqls = java.util.List.of();
        if (csv != null && db != null) {
            sqls = com.legend.exec.Ddl.setUpDataSqlsText(csv, db, lookup);
        }
        com.legend.plan.PlanConn.DsSpec spec = null;
        if (ni.properties().get("datasourceSpecification")
                instanceof com.legend.compiler.spec.typed
                        .TypedNewInstance ds
                && ds.classFqn().equals("meta::pure::alloy::connections"
                        + "::alloy::specification"
                        + "::LocalH2DatasourceSpecification")) {
            String specCsv = ds.properties().get("testDataSetupCsv")
                    instanceof com.legend.compiler.spec.typed
                            .TypedCString sc2
                    ? sc2.value() : null;
            java.util.List<String> specSqls = specCsv != null && db != null
                    ? com.legend.exec.Ddl.setUpDataSqlsText(specCsv, db,
                            lookup)
                    : java.util.List.of();
            spec = new com.legend.plan.PlanConn.DsSpec(
                    "LocalH2DatasourceSpecification", specCsv, specSqls);
        }
        return new com.legend.plan.PlanConn(kind, type, csv, sqls, spec);
    }

    /** The ConnectionStore's {@code element} store reference under the
     * runtime argument (exact-FQN dispatch), or null. */
    private static @com.legend.Nullable String connectionStoreElementOf(
            TypedSpec rtArg) {
        java.util.ArrayDeque<TypedSpec> work = new java.util.ArrayDeque<>();
        work.add(rtArg);
        while (!work.isEmpty()) {
            TypedSpec t = work.poll();
            if (t instanceof com.legend.compiler.spec.typed
                            .TypedNewInstance ni
                    && "meta::core::runtime::ConnectionStore"
                            .equals(ni.classFqn())
                    && ni.properties().get("element")
                            instanceof com.legend.compiler.spec.typed
                                    .TypedPackageableRef pr) {
                return pr.fullPath();
            }
            work.addAll(t.children());
        }
        return null;
    }

    /** The engine-style PLAN renderer for a connection DatabaseType —
     * the plan goldens pin Composite to the DB2-family spelling
     * (paren-wrapped conjunctions, quoted boolean placeholders). */
    static com.legend.sql.dialect.EngineStyleH2 planDialect(
            @com.legend.Nullable String dbType, boolean quote,
            @com.legend.Nullable String tz) {
        if (dbType == null) {
            return new com.legend.sql.dialect.EngineStyleH2(quote, tz);
        }
        return switch (dbType) {
            case "DB2", "Composite" ->
                    new com.legend.sql.dialect.EngineStyleDB2(quote, tz);
            default ->
                    new com.legend.sql.dialect.EngineStyleH2(quote, tz);
        };
    }

    // ===== the plan-handle WALK vocabulary (plan node model) =========

    /** Non-null when {@code n} is a walk chain bottoming in an
     * executionPlan call; the value is the walked result (node, list,
     * param, scalar). Unknown steps return null — the chain falls back
     * to the ordinary pipeline and its own walls. */
    static @com.legend.Nullable Object planWalk(TypedSpec n,
            com.legend.compiler.spec.SpecCompiler specs, ExecEnv env) {
        if (n instanceof com.legend.compiler.spec.typed
                        .TypedPropertyAccess ppa
                && ppa.property().equals("processingTemplateFunctions")
                && foldPairProjection(ppa.source())
                        instanceof com.legend.compiler.spec.typed
                                .TypedNativeCall pep
                && com.legend.compiler.element.type.PlatformTypes
                        .EXECUTION_PLAN.equals(
                                pep.callee().qualifiedName())) {
            // plan-handle vocabulary (was a statement-root arm; ladder
            // migration #22): the plan's freemarker support functions +
            // enum-typed parameters' dynamic enum-map functions
            java.util.List<Object> supportFns = new java.util.ArrayList<>(
                    com.legend.plan.PlanSupportFunctions
                            .relationalPlanSupportFunctions(
                                    pep.args().size() > 2
                                            ? ConnectionFlags.timeZoneOf(
                                                    pep.args().get(2))
                                            : null));
            if (pep.args().get(0) instanceof com.legend.compiler.spec
                            .typed.TypedLambda plam
                    && pep.args().get(1) instanceof com.legend.compiler
                            .spec.typed.TypedPackageableRef pmr
                    && com.legend.compiler.element.type.PlatformTypes
                            .functionTypeOf(plam.info().type())
                            instanceof com.legend.compiler
                            .element.type.Type.FunctionType pft) {
                java.util.Set<String> seenFns =
                        new java.util.LinkedHashSet<>();
                for (var prm : pft.params()) {
                    if (!(prm.type() instanceof com.legend.compiler
                            .element.type.Type.EnumType et)) {
                        continue;
                    }
                    String fn = com.legend.plan.PlanText.enumMapFnOf(
                            env.ctx(), pmr.fullPath(), et.fqn());
                    var em = com.legend.plan.PlanText.enumMappingOf(
                            env.ctx(), pmr.fullPath(), et.fqn());
                    if (fn != null && em != null && seenFns.add(fn)) {
                        supportFns.add(com.legend.plan
                                .PlanSupportFunctions
                                .enumMapTemplateFunction(fn, em));
                    }
                }
            }
            return supportFns;
        }
        if (n instanceof com.legend.compiler.spec.typed.TypedNativeCall ep
                && com.legend.compiler.element.type.PlatformTypes
                        .EXECUTION_PLAN.equals(ep.callee().qualifiedName())) {
            return planModel(ep, specs, env);
        }
        if (n instanceof com.legend.compiler.spec.typed.TypedPackageableRef pr9) {
            // a Database ELEMENT in value position: the store-metamodel
            // walk surface (typeInference family)
            return com.legend.exec.MetamodelWalk.database(env.ctx(),
                    pr9.fullPath());
        }
        if (n instanceof com.legend.compiler.spec.typed.TypedNewInstance ni9
                && (ni9.classFqn().startsWith(
                        "meta::relational::metamodel::")
                    || ni9.classFqn().startsWith(
                        "meta::external::query::sql::metamodel::")
                    || ni9.classFqn().startsWith(
                        "meta::relational::functions::pureToSqlQuery"
                        + "::metamodel::")
                    || ni9.classFqn().startsWith(
                        "meta::pure::router::clustering::"))) {
            // CONSTRUCTED metamodel instance: SQL-protocol nodes build
            // HOST records (the bridge's structural-equality values);
            // relational ops build RelationalOperations for inference
            Object node = constructNode(ni9, specs, env);
            if (node != null) {
                return node;
            }
            com.legend.model.RelationalOperation ro = constructOp(ni9,
                    specs, env);
            if (ro != null) {
                return new com.legend.exec.MetamodelWalk.Rop(null,
                        env.ctx(), ro);
            }
            // MIXED-args DynaFunction (walked handles among relational
            // ops): per-arg conversion channel
            if (ni9.classFqn().endsWith("::DynaFunction")
                    && ni9.properties().get("name") instanceof
                            com.legend.compiler.spec.typed.TypedCString dn9) {
                TypedSpec ps9 = ni9.properties().get("parameters");
                java.util.List<TypedSpec> els9 = ps9 == null
                        ? java.util.List.of()
                        : ps9 instanceof com.legend.compiler.spec.typed
                                .TypedCollection tc9 ? tc9.elements()
                                : java.util.List.of(ps9);
                java.util.List<Object> dargs =
                        new java.util.ArrayList<>();
                for (TypedSpec e9 : els9) {
                    Object w9 = planWalk(e9, specs, env);
                    if (w9 instanceof java.util.List<?> lw9
                            && lw9.size() == 1) {
                        w9 = lw9.get(0);
                    }
                    if (w9 == null) {
                        return null;
                    }
                    dargs.add(w9);
                }
                return new com.legend.exec.MetamodelWalk.DynH(dn9.value(),
                        dargs);
            }
            return null;
        }
        if (n instanceof com.legend.compiler.spec.typed.TypedCopyInstance cp) {
            // ^$joinTreeNode(alias = ...) — a walked join-tree handle
            // copy-constructed with a subselect alias override
            Object src = planWalk(cp.source(), specs, env);
            if (src instanceof java.util.List<?> ls && ls.size() == 1) {
                src = ls.get(0);
            }
            if (src instanceof com.legend.exec.MetamodelWalk.JtnH jt
                    && cp.overrides().size() == 1
                    && cp.overrides().containsKey("alias")) {
                Object al = nodeValue(cp.overrides().get("alias"), specs,
                        env);
                if (al instanceof com.legend.exec.MetamodelWalk.AliasH) {
                    return jt.withAlias(al);
                }
            }
            return null;
        }
        if (n instanceof com.legend.compiler.spec.typed.TypedPropertyAccess pa) {
            Object recv = planWalk(pa.source(), specs, env);
            if (recv == null) {
                return null;
            }
            return walkProp(recv, pa.property());
        }
        if (n instanceof com.legend.compiler.spec.typed.TypedCast tc) {
            return planWalk(tc.source(), specs, env);
        }
        if (n instanceof com.legend.compiler.spec.typed.TypedFilter tf) {
            Object recvF = planWalk(tf.source(), specs, env);
            return recvF instanceof java.util.List<?> lf
                    ? walkFilter(lf, tf.predicate()) : null;
        }
        if (n instanceof com.legend.compiler.spec.typed.TypedMap tm
                && tm.mapper() instanceof com.legend.compiler.spec.typed
                        .TypedLambda tml) {
            return MetamodelSteps.walkMapOver(
                    planWalk(tm.source(), specs, env), tml, specs, env);
        }
        if (n instanceof com.legend.compiler.spec.typed.TypedNativeCall c
                && !c.args().isEmpty()) {
            Object recv = planWalk(c.args().get(0), specs, env);
            if (recv == null) {
                return null;
            }
            // F7.7: exact-FQN dispatch inside metamodelStep
            Object step = MetamodelSteps.metamodelStep(
                    c.callee().qualifiedName(), recv, c, specs, env);
            return step == MetamodelSteps.WALK_UNRECOGNIZED ? null : step;
        }
        return null;
    }

    /** A constructed relational-op instance's HOST value: DynaFunction/
     * Literal/LiteralList convert structurally; walked sub-chains
     * contribute their own Rop ops; anything else nulls. */
    private static com.legend.model.@com.legend.Nullable RelationalOperation constructOp(
            com.legend.compiler.spec.typed.TypedNewInstance ni,
            com.legend.compiler.spec.SpecCompiler specs, ExecEnv env) {
        String simple = ni.classFqn().substring(
                ni.classFqn().lastIndexOf(':') + 1);
        switch (simple) {
            case "DynaFunction" -> {
                TypedSpec nameV = ni.properties().get("name");
                if (!(nameV instanceof
                        com.legend.compiler.spec.typed.TypedCString cs)) {
                    return null;
                }
                java.util.List<com.legend.model.RelationalOperation> args =
                        new java.util.ArrayList<>();
                TypedSpec ps = ni.properties().get("parameters");
                java.util.List<TypedSpec> els = ps == null
                        ? java.util.List.of()
                        : ps instanceof com.legend.compiler.spec.typed
                                .TypedCollection tc ? tc.elements()
                                : java.util.List.of(ps);
                for (TypedSpec e : els) {
                    com.legend.model.RelationalOperation a = argOp(e,
                            specs, env);
                    if (a == null) {
                        return null;
                    }
                    args.add(a);
                }
                return new com.legend.model.RelationalOperation
                        .FunctionCall(cs.value(), args);
            }
            case "Literal" -> {
                TypedSpec v = ni.properties().get("value");
                Object lit = switch (v) {
                    case null -> null;
                    case com.legend.compiler.spec.typed.TypedCString c2 ->
                            c2.value();
                    case com.legend.compiler.spec.typed.TypedCInteger i2 ->
                            i2.value();
                    case com.legend.compiler.spec.typed.TypedCFloat f2 ->
                            f2.value();
                    case com.legend.compiler.spec.typed.TypedCBoolean b3 ->
                            b3.value();
                    case com.legend.compiler.spec.typed.TypedCDate cd3 ->
                            cd3.value();
                    case com.legend.compiler.spec.typed.TypedEnumValue ev3 ->
                            ev3.value();
                    default -> null;
                };
                // ^Literal(value=^SQLNull()) — the null marker rides as
                // the sqlNull dynafunction (one downstream shape)
                if (lit == null && v instanceof com.legend.compiler.spec
                        .typed.TypedNewInstance sn
                        && sn.classFqn().endsWith("::SQLNull")) {
                    return new com.legend.model.RelationalOperation
                            .FunctionCall("sqlNull", java.util.List.of());
                }
                return lit == null ? null
                        : new com.legend.model.RelationalOperation
                                .Literal(lit);
            }
            case "LiteralList" -> {
                TypedSpec vs = ni.properties().get("values");
                java.util.List<com.legend.model.RelationalOperation> els2 =
                        new java.util.ArrayList<>();
                java.util.List<TypedSpec> raw = vs instanceof
                        com.legend.compiler.spec.typed.TypedCollection tc2
                        ? tc2.elements()
                        : vs == null ? java.util.List.of()
                                : java.util.List.of(vs);
                for (TypedSpec e : raw) {
                    com.legend.model.RelationalOperation a = argOp(e,
                            specs, env);
                    if (a == null) {
                        return null;
                    }
                    els2.add(a);
                }
                return new com.legend.model.RelationalOperation
                        .ArrayLiteral(els2);
            }
            default -> {
                return null;
            }
        }
    }

    /** A constructed SQL-protocol/bridge node as a HOST record, or null
     * when the class is not a bridge node (relational ops fall through
     * to {@link #constructOp}). */
    private static @com.legend.Nullable Object constructNode(
            com.legend.compiler.spec.typed.TypedNewInstance ni,
            com.legend.compiler.spec.SpecCompiler specs, ExecEnv env) {
        String simple = ni.classFqn().substring(
                ni.classFqn().lastIndexOf(':') + 1);
        switch (simple) {
            case "QualifiedName" -> {
                return new com.legend.exec.MetamodelWalk.QnH(
                        java.util.Objects.requireNonNull(
                                stringsOf(ni.properties().get("parts")),
                                "QualifiedName without parts"));
            }
            case "QualifiedNameReference" -> {
                Object nm = ni.properties().get("name") instanceof
                        com.legend.compiler.spec.typed.TypedNewInstance nn
                        ? constructNode(nn, specs, env) : null;
                return nm instanceof com.legend.exec.MetamodelWalk.QnH q
                        ? new com.legend.exec.MetamodelWalk.QnrH(q) : null;
            }
            case "ColumnName" -> {
                return ni.properties().get("name") instanceof
                        com.legend.compiler.spec.typed.TypedCString cs2
                        ? new com.legend.exec.MetamodelWalk.CnH(cs2.value())
                        : null;
            }
            case "Alias", "TableAlias" -> {
                if (ni.properties().get("name") instanceof
                        com.legend.compiler.spec.typed.TypedCString an9) {
                    TypedSpec rel = ni.properties().get("relationalElement");
                    Object rv = rel == null ? null
                            : planWalk(rel, specs, env);
                    if (rv instanceof java.util.List<?> lw9
                            && lw9.size() == 1) {
                        rv = lw9.get(0);
                    }
                    return rv == null ? null
                            : new com.legend.exec.MetamodelWalk.AliasH(
                                    an9.value(), rv);
                }
                return null;
            }
            case "TableAliasColumnName" -> {
                TypedSpec al2 = ni.properties().get("alias");
                String an2 = al2 instanceof com.legend.compiler.spec.typed
                        .TypedNewInstance ai
                        && ai.properties().get("name") instanceof
                                com.legend.compiler.spec.typed
                                        .TypedCString acs2
                        ? acs2.value() : null;
                return an2 != null && ni.properties().get("columnName")
                        instanceof com.legend.compiler.spec.typed
                                .TypedCString cn2
                        ? new com.legend.exec.MetamodelWalk.TacH(an2,
                                cn2.value())
                        : null;
            }
            case "TableAliasColumn" -> {
                TypedSpec al = ni.properties().get("alias");
                String aliasName = al instanceof com.legend.compiler.spec
                        .typed.TypedNewInstance an
                        && an.properties().get("name") instanceof
                                com.legend.compiler.spec.typed
                                        .TypedCString acs
                        ? acs.value() : null;
                if (aliasName == null) {
                    return null;
                }
                Object colV = null;
                TypedSpec colSpec = ni.properties().get("column");
                if (colSpec != null) {
                    Object w = planWalk(colSpec, specs, env);
                    if (w instanceof java.util.List<?> lw
                            && lw.size() == 1) {
                        w = lw.get(0);
                    }
                    colV = w;
                }
                return new com.legend.exec.MetamodelWalk.TacH(aliasName,
                        colV);
            }
            default -> {
                if (ni.classFqn().startsWith(
                        "meta::external::query::sql::metamodel::")
                        || ni.classFqn().startsWith(
                                "meta::relational::functions::"
                                + "pureToSqlQuery::metamodel::")
                        || ni.classFqn().startsWith(
                                "meta::pure::router::clustering::")
                        || GENERIC_RELATIONAL_KINDS.contains(
                                ni.classFqn())) {
                    return genericNode(ni, simple, specs, env);
                }
                return null;
            }
        }
    }

    /** Relational-metamodel classes carried as GENERIC NodeH handles
     * (no inference/op semantics of their own — only the dialect
     * conversion consumes them). DynaFunction/Literal stay on the
     * constructOp channel. */
    private static final java.util.Set<String> GENERIC_RELATIONAL_KINDS =
            java.util.Set.of(
                    "meta::relational::metamodel::Window",
                    "meta::relational::metamodel::SortByInfo",
                    "meta::relational::metamodel::WindowColumn",
                    "meta::relational::metamodel::relation::TabularFunction",
                    "meta::relational::metamodel::relation::SelectSQLQuery",
                    "meta::relational::metamodel::relation::TdsSelectSqlQuery",
                    "meta::relational::metamodel::relation::Union",
                    "meta::relational::metamodel::relation::UnionAll",
                    "meta::relational::metamodel::relation::CommonTableExpression",
                    "meta::relational::metamodel::relation::CommonTableExpressionReference",
                    "meta::relational::metamodel::join::RootJoinTreeNode",
                    "meta::relational::metamodel::OrderBy",
                    "meta::relational::metamodel::operation::JoinStrings");

    /** GENERIC SQL-protocol node: kind + converted ctor props (nested
     * instances recurse; collections map; enum values spell their
     * NAME; walked chains contribute their host values). */
    private static @com.legend.Nullable Object genericNode(
            com.legend.compiler.spec.typed.TypedNewInstance ni,
            String simple, com.legend.compiler.spec.SpecCompiler specs,
            ExecEnv env) {
        java.util.TreeMap<String, Object> props = new java.util.TreeMap<>();
        for (var e : ni.properties().entrySet()) {
            Object v = nodeValue(e.getValue(), specs, env);
            if (v == null) {
                return null;
            }
            props.put(e.getKey(), v);
        }
        return com.legend.exec.MetamodelWalk.nodeOf(simple, props);
    }

    private static @com.legend.Nullable Object nodeValue(TypedSpec v,
            com.legend.compiler.spec.SpecCompiler specs, ExecEnv env) {
        return switch (v) {
            case com.legend.compiler.spec.typed.TypedCString c ->
                    c.value();
            case com.legend.compiler.spec.typed.TypedCInteger i ->
                    i.value().longValue();
            case com.legend.compiler.spec.typed.TypedCBoolean b2 ->
                    b2.value();
            case com.legend.compiler.spec.typed.TypedCFloat f ->
                    f.value();
            case com.legend.compiler.spec.typed.TypedEnumValue ev ->
                    ev.value();
            case com.legend.compiler.spec.typed.TypedCDate cd9 ->
                    cd9.value();
            case com.legend.compiler.spec.typed.TypedNewInstance nn -> {
                Object x = constructNode(nn, specs, env);
                // relational-op instances (DynaFunction/Literal) ride
                // their walk channels as handle values
                yield x != null ? x : planWalk(nn, specs, env);
            }
            case com.legend.compiler.spec.typed.TypedCollection tc -> {
                java.util.List<Object> out = new java.util.ArrayList<>();
                for (TypedSpec e2 : tc.elements()) {
                    Object x = nodeValue(e2, specs, env);
                    if (x == null) {
                        yield null;
                    }
                    out.add(x);
                }
                yield out;
            }
            default -> {
                Object w = planWalk(v, specs, env);
                if (w instanceof java.util.List<?> lw && lw.size() == 1) {
                    w = lw.get(0);
                }
                if (w == null && v instanceof com.legend.compiler.spec
                        .typed.TypedPackageableRef pr) {
                    // class/type REFERENCE prop values (VarPlaceHolder
                    // .type, CrossSetImplementation.class): the FQN
                    // string is the handle — only construction identity
                    // needs it, never evaluation
                    yield pr.fullPath();
                }
                yield w;
            }
        };
    }

    /** String elements of a literal collection value. */
    private static java.util.@com.legend.Nullable List<String> stringsOf(
            @com.legend.Nullable TypedSpec v) {
        java.util.List<TypedSpec> els = v instanceof
                com.legend.compiler.spec.typed.TypedCollection tc
                ? tc.elements()
                : v == null ? java.util.List.of() : java.util.List.of(v);
        java.util.List<String> out = new java.util.ArrayList<>();
        for (TypedSpec e : els) {
            if (e instanceof com.legend.compiler.spec.typed.TypedCString c) {
                out.add(c.value());
            }
        }
        return out;
    }

    /** One constructor-argument op: nested instance, or a WALKED chain
     * whose value is already a relational-op handle. */
    private static com.legend.model.@com.legend.Nullable RelationalOperation argOp(TypedSpec e,
            com.legend.compiler.spec.SpecCompiler specs, ExecEnv env) {
        if (e instanceof com.legend.compiler.spec.typed.TypedNewInstance nni) {
            return constructOp(nni, specs, env);
        }
        Object w = planWalk(e, specs, env);
        if (w instanceof java.util.List<?> lw && lw.size() == 1) {
            w = lw.get(0);
        }
        return w instanceof com.legend.exec.MetamodelWalk.Rop r
                ? r.op() : null;
    }

    /** Property step, AUTO-MAPPING over lists (pure semantics). */
    private static @com.legend.Nullable Object walkProp(Object recv, String prop) {
        Object mm = com.legend.exec.MetamodelWalk.prop(recv, prop);
        if (mm != null) {
            return mm;
        }
        if (recv instanceof java.util.List<?> l) {
            java.util.List<Object> out = new java.util.ArrayList<>();
            for (Object e : l) {
                Object v = walkProp(e, prop);
                if (v instanceof java.util.List<?> vl) {
                    out.addAll(vl);
                } else if (v != null) {
                    out.add(v);
                }
            }
            return out;
        }
        if (recv instanceof com.legend.plan.PlanNode pn) {
            return switch (prop) {
                case "rootExecutionNode" -> pn;
                case "executionNodes" ->
                        new java.util.ArrayList<Object>(pn.children());
                case "sqlQuery" -> pn.sqlQuery();
                case "sqlComment" -> pn.sqlComment();
                case "connection" -> pn.connection();
                case "functionParameters" ->
                        new java.util.ArrayList<Object>(
                                pn.functionParameters());
                default -> null;
            };
        }
        if (recv instanceof com.legend.plan.PlanConn pc2) {
            return switch (prop) {
                case "type" -> pc2.type();
                case "testDataSetupCsv" -> pc2.testDataSetupCsv();
                case "testDataSetupSqls" -> new java.util.ArrayList<Object>(
                        pc2.testDataSetupSqls());
                case "datasourceSpecification" ->
                        pc2.datasourceSpecification();
                default -> null;
            };
        }
        if (recv instanceof com.legend.plan.PlanConn.DsSpec ds2) {
            return switch (prop) {
                case "testDataSetupCsv" -> ds2.testDataSetupCsv();
                case "testDataSetupSqls" -> new java.util.ArrayList<Object>(
                        ds2.testDataSetupSqls());
                default -> null;
            };
        }
        if (recv instanceof com.legend.plan.PlanNode.Param pp) {
            return switch (prop) {
                case "name" -> pp.name();
                case "supportsStream" -> pp.supportsStream();
                default -> null;
            };
        }
        return null;
    }

    /** filter lambda bodies the walk understands: instanceOf($n, X) and
     * {@code $p.name == 'lit'}. */
    static @com.legend.Nullable Object walkFilter(java.util.List<?> l,
            com.legend.compiler.spec.typed.TypedLambda lam) {
        TypedSpec body = lam.body().get(lam.body().size() - 1);
        if (body instanceof com.legend.compiler.spec.typed.TypedNativeCall io
                && "meta::pure::functions::meta::instanceOf"
                        .equals(io.callee().qualifiedName())
                && io.args().size() == 2) {
            String cls = typeRefSimple(io.args().get(1));
            if (cls == null) {
                return null;
            }
            java.util.List<Object> out = new java.util.ArrayList<>();
            for (Object e : l) {
                if (e instanceof com.legend.plan.PlanNode pn
                        && pn.kind().equals(cls)) {
                    out.add(e);
                }
            }
            return out;
        }
        if (body instanceof com.legend.compiler.spec.typed.TypedNativeCall eq
                && "meta::pure::functions::boolean::equal"
                        .equals(eq.callee().qualifiedName())
                && eq.args().size() == 2
                && eq.args().get(0)
                        instanceof com.legend.compiler.spec.typed
                                .TypedPropertyAccess pa2) {
            // GENERIC property==literal predicate: plan Params (name)
            // and metamodel handles (columnName, root) share the arm
            Object litVal = switch (eq.args().get(1)) {
                case com.legend.compiler.spec.typed.TypedCString cs2 ->
                        cs2.value();
                case com.legend.compiler.spec.typed.TypedCBoolean cb2 ->
                        cb2.value();
                default -> null;
            };
            if (litVal == null) {
                return null;
            }
            java.util.List<Object> out = new java.util.ArrayList<>();
            for (Object e : l) {
                Object v = e instanceof com.legend.plan.PlanNode.Param pp
                        && pa2.property().equals("name")
                        ? pp.name()
                        : com.legend.exec.MetamodelWalk.prop(e,
                                pa2.property());
                if (litVal.equals(v)) {
                    out.add(e);
                }
            }
            return out;
        }
        return null;
    }

    /** The SIMPLE class name a type-valued argument refers to. */
    private static @com.legend.Nullable String typeRefSimple(TypedSpec t) {
        if (t instanceof com.legend.compiler.spec.typed.TypedPackageableRef pr2) {
            String f = pr2.fullPath();
            return f.substring(f.lastIndexOf(':') + 1);
        }
        return null;
    }

    /** Walk results carry the TYPER's declared type — never a type
     * inferred from runtime classes (audit 2026-08-18 finding N: the
     * old {@code allMatch} inference typed an EMPTY list BOOLEAN
     * vacuously and coerced unknown scalars through
     * {@code String.valueOf}). */
    private static ExecutionResult walkResult(Object w,
            com.legend.compiler.element.type.Type declared) {
        if (w instanceof java.util.List<?> l) {
            return new ExecutionResult.Collection(
                    new java.util.ArrayList<>(l), declared);
        }
        return new ExecutionResult.Scalar(w, declared);
    }

    /** The PLAN NODE MODEL for an executionPlan call — same shapes the
     * text printer spells (Sequence / FunctionParametersValidation /
     * RelationalInstantiation / SQLExecution). */
    private static com.legend.plan.PlanNode planModel(
            com.legend.compiler.spec.typed.TypedNativeCall ep,
            com.legend.compiler.spec.SpecCompiler specs, ExecEnv env) {
        if (!(ep.args().get(0)
                instanceof com.legend.compiler.spec.typed.TypedLambda lam)) {
            throw new com.legend.error.NotImplementedException(
                    "plan walk: executionPlan argument shapes pending");
        }
        // mapping arg: the generalized reader (a bare ref, a dummy
        // ^Mapping, or the 2-arg overload with in-query from) — the
        // SAME rules the text printer already applies at planText
        // (E2E §4.4 cluster 8: this path demanded a bare ref only)
        String pmFqn = ep.args().size() > 1 && ep.args().get(1)
                instanceof com.legend.compiler.spec.typed.TypedPackageableRef pr
                ? pr.fullPath()
                : firstFromMapping(lam.body().get(lam.body().size() - 1));
        if (pmFqn == null) {
            throw new com.legend.error.NotImplementedException(
                    "plan walk: no mapping (arg or in-query from) named");
        }
        // same helper-call inlining as planToString: testRuntime(true)
        // carries the connection flags inside its body
        TypedSpec rtArg2 = ep.args().size() > 2
                ? new com.legend.compiler.spec.UserCallInliner(specs)
                        .inlineBody(java.util.List.of(ep.args().get(2)))
                        .get(0)
                : null;
        boolean quote = rtArg2 != null && ConnectionFlags.quoteIdentifiersOf(rtArg2);
        String tz = rtArg2 != null ? ConnectionFlags.timeZoneOf(rtArg2) : null;
        var fnType = lam.functionType();
        java.util.LinkedHashMap<String, com.legend.sql.SqlExpr.PlanParam>
                params = new java.util.LinkedHashMap<>();
        java.util.List<com.legend.plan.PlanNode.Param> fps =
                new java.util.ArrayList<>();
        for (int i = 0; i < lam.parameters().size(); i++) {
            var p = fnType.params().get(i);
            boolean many = !(p.multiplicity()
                    instanceof com.legend.compiler.element.type.Multiplicity
                            .Bounded ob
                    && Integer.valueOf(1).equals(ob.upper()));
            boolean opt = p.multiplicity()
                    instanceof com.legend.compiler.element.type.Multiplicity
                            .Bounded ob2
                    && ob2.lower() == 0
                    && Integer.valueOf(1).equals(ob2.upper());
            params.put(lam.parameters().get(i),
                    new com.legend.sql.SqlExpr.PlanParam(
                            lam.parameters().get(i),
                            com.legend.lowering.PlanParams.kindOf(p.type()),
                            opt));
            // supportsStream (engine storeContract:221 + executionPlan_
            // generation findParamsSupportedForStreamInput): the param
            // streams iff it is read under an in(...) call at least once
            // and NEVER in any other call position
            fps.add(new com.legend.plan.PlanNode.Param(
                    lam.parameters().get(i),
                    supportsStream(lam, lam.parameters().get(i),
                            src -> streamStoreOf(src, env.ctx(), pmFqn))));
        }
        TypedSpec term = lam.body().get(lam.body().size() - 1);
        // the runtime argument may carry relationalMapperPostProcessor
        // renames — extracted structurally, applied over the lowered IR
        java.util.function.UnaryOperator<String> mapperRenames =
                ep.args().size() > 2
                ? com.legend.plan.RelationalMapperRenames.extract(
                        ep.args().get(2), specs, env.queryLets(), env.ctx())
                : java.util.function.UnaryOperator.identity();
        EngineSql es = engineSql(java.util.List.of(term), pmFqn,
                specs, env, new com.legend.sql.dialect.EngineStyleH2(quote,
                        tz), params, mapperRenames);
        com.legend.plan.PlanNode sqlNode = new com.legend.plan.PlanNode(
                "SQLExecutionNode", java.util.List.of(), es.sql(),
                java.util.List.of(),
                com.legend.plan.PlanNode.EXEC_TRACE_COMMENT,
                planConnOf(rtArg2, env));
        com.legend.plan.PlanNode rel = new com.legend.plan.PlanNode(
                "RelationalInstantiationExecutionNode",
                java.util.List.of(sqlNode), null, java.util.List.of());
        if (lam.parameters().isEmpty() && lam.body().size() == 1) {
            return rel;
        }
        com.legend.plan.PlanNode fpvn = new com.legend.plan.PlanNode(
                "FunctionParametersValidationNode", java.util.List.of(),
                null, fps);
        return new com.legend.plan.PlanNode("SequenceExecutionNode",
                java.util.List.of(fpvn, rel), null, java.util.List.of());
    }

    static @com.legend.Nullable String rootGetAllClass(java.util.List<TypedSpec> body) {
        java.util.ArrayDeque<TypedSpec> work = new java.util.ArrayDeque<>(body);
        while (!work.isEmpty()) {
            TypedSpec t = work.poll();
            if (t instanceof com.legend.compiler.spec.typed.TypedGetAll ga) {
                return ga.classFqn();
            }
            work.addAll(t.children());
        }
        return null;
    }

    // =====================================================================
    // The RESULT FRAME (audit 19d B2): let-bound execute() runs EAGERLY and
    // becomes a frame; downstream reads over the frame splice into typed
    // queries — Result is a typing surface plus an orchestration handle,
    // NEVER a host object graph (tenet #1: Java orchestrates, the database
    // executes). The splice rules moved VERBATIM from the harness.
    // =====================================================================

    /** One executed {@code execute()} binding: the from-wrapped typed query
     * chain (unresolved — downstream reads compose over it and resolve as a
     * whole), whether the query ROOT is relation-shaped (the engine's
     * {@code Result.values} for a TDS query holds ONE TDS; for a class or
     * scalar root, values IS the collection), and the eager run's result. */
    record ExecFrame(TypedSpec chain, boolean relationRooted,
            @com.legend.Nullable ExecutionResult result,
            java.util.Map<String, String> tableReplace,
            @com.legend.Nullable com.legend.compiler.spec.typed
                    .TypedNativeCall sourceExec) {
        /** Pre-activity-model constructor (alias frames keep it). */
        ExecFrame(TypedSpec chain, boolean relationRooted,
                @com.legend.Nullable ExecutionResult result,
                java.util.Map<String, String> tableReplace) {
            this(chain, relationRooted, result, tableReplace, null);
        }
    }

    /**
     * Build the frame for one {@code execute(f, mapping, runtime, ext)}
     * call: fold the query lambda's (and the caller's) lets, attach the
     * EXPLICIT mapping argument as the chain's execution context, and — for
     * a let binding — run it eagerly through the pipeline.
     */
    /** The executor's SchemaOracle: the LIMIT-0 probe with its checked
     * failure wrapped — F1.3 keeps the JDBC surface (its exception type
     * included) out of the resolver. */
    private static com.legend.resolver.RawGridSchema.SchemaOracle gridOracle(
            java.sql.Connection connection, ExecEnv env) {
        return sql -> {
            try {
                return com.legend.exec.GridProbe.probeTypedColumns(
                        sql, connection, env.dialect());
            } catch (java.sql.SQLException e) {
                throw new IllegalStateException(e);
            }
        };
    }

    private static ExecFrame buildFrame(
            com.legend.compiler.spec.typed.TypedNativeCall ec,
            java.util.List<TypedSpec> letPrefix, boolean eager,
            SpecCompiler specs, ExecEnv env) {
        // PLAN-EXECUTE normalization (burn map: unlocks the TDG wall
        // cohort + the §5 program-replayer class): executionPlan::
        // execute(plan, values, ext) peels the plan argument to its
        // executionPlan(...) BUILD — the same positional shape (query,
        // mapping, runtime, extensions), so the ordinary frame
        // machinery serves it whole. Plan TEXT is engine-text and
        // never executes here (single-compiler tenet). Non-traceable
        // plans and non-empty parametersValues WALL counted
        // (values-binding is the §5 referee-binding cut, later).
        if (com.legend.compiler.element.type.PlatformTypes
                .EXECUTION_PLAN_EXECUTE.equals(
                        ec.callee().qualifiedName())) {
            TypedSpec plan = com.legend.compiler.spec.ExecuteChainAssembly
                    .letBound(ec.args().get(0), letPrefix);
            if (!(plan instanceof com.legend.compiler.spec.typed
                            .TypedNativeCall pb
                    && com.legend.compiler.element.type.PlatformTypes
                            .EXECUTION_PLAN.equals(
                                    pb.callee().qualifiedName()))) {
                throw new com.legend.error.NotImplementedException(
                        "plan-execute: plan argument does not trace to"
                                + " an executionPlan(...) build");
            }
            if (ec.args().size() > 1
                    && !(ec.args().get(1) instanceof com.legend.compiler
                            .spec.typed.TypedCollection pv
                            && pv.elements().isEmpty())) {
                throw new com.legend.error.NotImplementedException(
                        "plan-execute: parametersValues binding pending"
                                + " (the referee-binding cut)");
            }
            ec = pb;
        }
        // the STRING ENTRY (meta::legend::executeLegendQuery): the query
        // lambda's parameters bind from the vars pairs, the chain rides
        // the same frame, the READ is the engine's result JSON string
        // (envelope emitted over the chain — relationRooted is false:
        // the frame's value is one string). The eager run executes the
        // RAW chain (pipeline validation at the let, engine parity).
        if (com.legend.compiler.element.type.PlatformTypes
                .isLegendQueryFqn(ec.callee().qualifiedName())) {
            var lq = com.legend.compiler.spec.ExecuteChainAssembly
                    .prepareLegendQuery(ec, letPrefix, specs);
            var lqChain = com.legend.compiler.spec.ExecuteChainAssembly
                    .chain(lq, ec, letPrefix, specs, env.runtimeFqn(),
                            env.queryLets());
            TypedSpec envelope = com.legend.compiler.spec.ExecuteChainAssembly
                    .legendQueryEnvelope(lqChain.chain(), env.ctx());
            ExecutionResult lqRun = null;
            if (eager) {
                com.legend.resolver.StoreResolver lqResolver =
                        new com.legend.resolver.StoreResolver(env.ctx(), specs)
                                .withLetBindings(env.queryLets());
                lqRun = executeTyped(lqResolver.resolve(
                        java.util.List.of(lqChain.chain()), env.runtimeFqn()),
                        env);
            }
            return new ExecFrame(envelope, false, lqRun, env.tableReplace(), ec);
        }
        var prepared = com.legend.compiler.spec.ExecuteChainAssembly
                .prepare(ec, letPrefix, specs);
        // the RUNTIME ARGUMENT's effectful user calls (the corpus's
        // createDbAndGetConnection: DDL + seed, returns the handle) run
        // ONCE here — engine order: runtime construction precedes
        // execution; the value itself stays an opaque handle (re-running
        // on a non-eager chain build would double the DDL)
        if (eager && ec.args().size() >= 3) {
            runRuntimeArgEffects(com.legend.compiler.spec
                    .ExecuteChainAssembly.letBound(ec.args().get(2),
                            letPrefix), letPrefix, specs, env);
        }
        // connection POST-PROCESSOR hooks ride the runtime argument
        // (sqlQueryPostProcessorsConnectionAware): inline the runtime
        // helper, recognize the replaceTables shape, thread the rename
        // map to the lowering seam (applied over OUR SQL IR)
        if (ec.args().size() >= 3) {
            TypedSpec rtArg = com.legend.compiler.spec
                    .ExecuteChainAssembly.letBound(
                            ec.args().get(2), letPrefix);
            if (rtArg instanceof com.legend.compiler.spec.typed.TypedUserCall) {
                rtArg = new com.legend.compiler.spec.UserCallInliner(specs)
                        .inlineBody(java.util.List.of(rtArg)).get(0);
            }
            java.util.Map<String, String> tr = com.legend.lowering
                    .SqlPostProcessors.tableReplaceMap(rtArg);
            com.legend.exec.PostProcessBoundary.record(tr);
            if (!tr.isEmpty()) {
                env = new ExecEnv(env.ctx(), env.runtimeFqn(), env.dialect(),
                        env.connection(),
                        env.addDriverTablePk(), env.queryLets(), tr,
                        env.instanceIds(), env.assertListener(),
                        env.replayOracle());
            }
        }
        var assembled = com.legend.compiler.spec.ExecuteChainAssembly
                .chain(prepared, ec, letPrefix, specs, env.runtimeFqn(),
                        env.queryLets());
        ExecutionResult run = null;
        if (eager) {
            // the inliner consumed the query's lets; graph-tree date args
            // still spell the variables (serialize-key source form) — the
            // resolver's let env resolves them (engine inScopeVars)
            com.legend.resolver.StoreResolver chainResolver =
                    new com.legend.resolver.StoreResolver(env.ctx(), specs)
                            .withLetBindings(env.queryLets());
            java.util.List<TypedSpec> body = chainResolver.resolve(
                    java.util.List.of(assembled.chain()), env.runtimeFqn());
            // the engine's RelationalExecutionContext option: driver-table
            // PK columns join every projection (#45 validation)
            if (env.addDriverTablePk()) {
                body = com.legend.resolver.DriverPkAppend.apply(
                        body, env.ctx());
            }
            run = executeTyped(body, env);
        }
        return new ExecFrame(assembled.chain(),
                assembled.relationRooted(), run, env.tableReplace(), ec);
    }

    /** Effectful user calls inside an execute() RUNTIME argument run once
     * (executeCallStatement); the walk stops AT each call — its own args
     * are the callee's business, and non-effectful calls (testRuntime())
     * stay unevaluated orchestration handles. */
    private static void runRuntimeArgEffects(TypedSpec n,
            java.util.List<TypedSpec> letPrefix, SpecCompiler specs,
            ExecEnv env) {
        if (n instanceof com.legend.compiler.spec.typed.TypedUserCall uc) {
            if (containsEffect(uc, specs, new java.util.HashMap<>())) {
                executeCallStatement(uc, letPrefix, specs, env,
                        new java.util.ArrayDeque<>());
            }
            return;
        }
        // post-processor CONFIG values never run as effects and must not
        // be compiled by the effect scan (ledger cluster 63 — the same
        // skip containsEffect applies, mirrored on this walk's own
        // recursion)
        if (n instanceof com.legend.compiler.spec.typed
                .TypedNewInstance ni8) {
            for (var pe : ni8.properties().entrySet()) {
                if (!com.legend.compiler.element.type.PlatformTypes
                        .isPostProcessorConfigProperty(pe.getKey())) {
                    runRuntimeArgEffects(pe.getValue(), letPrefix, specs,
                            env);
                }
            }
            return;
        }
        if (n instanceof com.legend.compiler.spec.typed
                .TypedCopyInstance cp8) {
            runRuntimeArgEffects(cp8.source(), letPrefix, specs, env);
            for (var pe : cp8.overrides().entrySet()) {
                if (!com.legend.compiler.element.type.PlatformTypes
                        .isPostProcessorConfigProperty(pe.getKey())) {
                    runRuntimeArgEffects(pe.getValue(), letPrefix, specs,
                            env);
                }
            }
            return;
        }
        for (TypedSpec c : n.children()) {
            runRuntimeArgEffects(c, letPrefix, specs, env);
        }
    }

    /** pair(a, b).first/.second folds STRUCTURALLY (the datetime
     * helpers thread plan + plan-text through a pair) — pure data
     * selection, no evaluation order. */
    private static TypedSpec foldPairProjection(TypedSpec n) {
        while (n instanceof com.legend.compiler.spec.typed
                        .TypedPropertyAccess pp2
                && ("first".equals(pp2.property())
                        || "second".equals(pp2.property()))
                && pp2.source() instanceof com.legend.compiler.spec
                        .typed.TypedNativeCall pc2
                && "meta::pure::functions::collection::pair"
                        .equals(pc2.callee().qualifiedName())
                && pc2.args().size() == 2) {
            n = pc2.args().get("first".equals(pp2.property()) ? 0 : 1);
        }
        return n;
    }

    /**
     * {@code let tds = $r.values(->at(0)/->toOne()/->first())} over a RELATION-rooted
     * frame: the wrappers are the Result envelope and the alias IS the same
     * frame ({@code $tds->size()} keeps ONE-TDS semantics). {@code at(k>0)}
     * is loud — the envelope holds one TDS. Class/scalar roots return null:
     * their at/toOne are REAL selections and the binding is an ordinary let.
     */
    private static @com.legend.Nullable ExecFrame aliasFrame(TypedSpec rhs,
            java.util.Map<String, ExecFrame> execFrames) {
        TypedSpec cur = rhs;
        boolean badIndex = false;
        while (true) {
            if (cur instanceof com.legend.compiler.spec.typed.TypedPropertyAccess pa
                    && pa.property().equals("values")) {
                cur = pa.source();
                continue;
            }
            if (cur instanceof com.legend.compiler.spec.typed.TypedNativeCall nc
                    && (com.legend.compiler.spec.ResultEnvelopeSplice.AT_FQN.equals(nc.callee().qualifiedName())
                            || com.legend.compiler.spec.ResultEnvelopeSplice.TO_ONE_FQN.equals(nc.callee().qualifiedName())
                            || com.legend.compiler.spec.ResultEnvelopeSplice.FIRST_FQN.equals(nc.callee().qualifiedName()))
                    && !nc.args().isEmpty()) {
                if (com.legend.compiler.spec.ResultEnvelopeSplice.AT_FQN.equals(nc.callee().qualifiedName())
                        && !(nc.args().size() == 2 && nc.args().get(1)
                                instanceof com.legend.compiler.spec.typed.TypedCInteger k
                                && k.value().longValue() == 0)) {
                    badIndex = true;
                }
                cur = nc.args().get(0);
                continue;
            }
            break;
        }
        if (cur instanceof com.legend.compiler.spec.typed.TypedVariable v
                && execFrames.containsKey(v.name())
                && execFrames.get(v.name()).relationRooted()) {
            if (badIndex) {
                throw new IllegalStateException("Result.values->at(k>0) on a"
                        + " relation-rooted query — the values envelope holds"
                        + " one TDS");
            }
            return execFrames.get(v.name());
        }
        return null;
    }

    /**
     * The TYPED splice — the REWRITE RULES live in the compiler
     * ({@link com.legend.compiler.spec.ResultEnvelopeSplice}, Invariant
     * 7: minting typed nodes is compiler work); this adapter supplies
     * the execution-bound half only — frame lookup, inline-execute
     * frame builds (JDBC), and the aggregationAware rewrittenQuery
     * print.
     */
    private static java.util.function.BiFunction<TypedSpec, java.util.Set<String>, TypedSpec> spliceHook(
            java.util.Map<String, ExecFrame> allFrames,
            java.util.List<TypedSpec> letPrefix, SpecCompiler specs, ExecEnv env) {
        return com.legend.compiler.spec.ResultEnvelopeSplice.hook(
                new com.legend.compiler.spec.ResultEnvelopeSplice.Frames() {
            @Override
            public com.legend.compiler.spec.ResultEnvelopeSplice
                    .@com.legend.Nullable View frame(String name) {
                ExecFrame f = allFrames.get(name);
                return f == null ? null
                        : new com.legend.compiler.spec.ResultEnvelopeSplice
                                .View(f.chain(), f.relationRooted());
            }

            @Override
            public com.legend.compiler.spec.ResultEnvelopeSplice.View
                    inlineExecute(com.legend.compiler.spec.typed
                            .TypedNativeCall ec, boolean eager) {
                ExecFrame f = buildFrame(ec, letPrefix, eager, specs, env);
                return new com.legend.compiler.spec.ResultEnvelopeSplice
                        .View(f.chain(), f.relationRooted());
            }

            @Override
            public @com.legend.Nullable String aggAwareRewrittenQuery(
                    TypedSpec chain) {
                return AggAwareActivities.rewrittenQuery(chain, env.ctx());
            }

            @Override
            public @com.legend.Nullable String relationalActivitySql(
                    String frameName, long activityNumber) {
                ExecFrame f = allFrames.get(frameName);
                if (f == null || f.sourceExec() == null
                        || activityNumber != 0) {
                    // single-statement execution: exactly one
                    // RelationalActivity per execute; other indices (and
                    // alias frames, which lost the source call) stay at
                    // the loud activities wall
                    return null;
                }
                var ec = f.sourceExec();
                if (!(ec.args().get(0) instanceof com.legend.compiler.spec
                                .typed.TypedLambda lam)
                        || !(ec.args().get(1) instanceof com.legend.compiler
                                .spec.typed.TypedPackageableRef pr)) {
                    return null;
                }
                // the engine-style render of the frame's own query — the
                // SAME pipeline as toSQLString(query, mapping, H2, ext)
                // (the activity log records the SQL the engine GENERATED;
                // goldens are engine-H2-spelled)
                var renderer = new com.legend.sql.dialect.EngineStyleH2();
                EngineSql es = engineSql(lam, pr.fullPath(), specs, env,
                        renderer);
                com.legend.sql.SqlQuery post =
                        com.legend.lowering.SqlPostProcessors.apply(
                                es.plan(), com.legend.exec
                                        .PostProcessBoundary.tableReplace());
                return post == es.plan() ? es.sql() : renderer.render(post);
            }

        });
    }


    /** The EFFECT rows' registered arms — a REAL registry (LINQ's
     * dictionary, user push 2026-08-31: "or just moving the ifs into a
     * table?"): one map, method references, LOUD on a catalogued row
     * with no arm. {@link #registeredEffectKeys} feeds the governance
     * test pinning registry == catalog. */
    @FunctionalInterface
    private interface EffectRoutine {
        ExecutionResult run(java.util.List<TypedSpec> body,
                com.legend.compiler.spec.typed.TypedNativeCall nc,
                ExecEnv env);
    }

    private static final java.util.Map<String, EffectRoutine> EFFECT_ARMS =
            java.util.Map.of(
                    com.legend.compiler.element.type.PlatformTypes
                            .EXECUTE_IN_DB,
                    StatementExecutor::executeInDb,
                    com.legend.compiler.element.type.PlatformTypes
                            .DROP_AND_CREATE_TABLE_IN_DB,
                    StatementExecutor::dropAndCreateTableInDb,
                    com.legend.compiler.element.type.PlatformTypes
                            .DROP_AND_CREATE_SCHEMA_IN_DB,
                    StatementExecutor::dropAndCreateSchemaInDb,
                    com.legend.compiler.element.type.PlatformTypes
                            .SET_UP_DATA_SQLS,
                    SeedSqlForms::assertForm,
                    com.legend.compiler.element.type.PlatformTypes
                            .SET_UP_DATA_SQLS_V2,
                    SeedSqlForms::assertForm,
                    com.legend.compiler.element.type.PlatformTypes.PRINT,
                    (body, nc, env) -> new ExecutionResult.Scalar(null,
                            com.legend.compiler.element.type.Type
                                    .Primitive.STRING),
                    com.legend.compiler.element.type.PlatformTypes.PRINTLN,
                    // debug output: a NO-OP — the argument is NEVER
                    // evaluated (it may be an unlowerable diagnostic);
                    // engine parity is the statement's inertness
                    (body, nc, env) -> new ExecutionResult.Scalar(null,
                            com.legend.compiler.element.type.Type
                                    .Primitive.STRING),
                    com.legend.compiler.element.type.PlatformTypes
                            .CONNECTION_BY_ELEMENT,
                    (body, nc, env) -> new ExecutionResult.Scalar(null,
                            nc.info().type()));

    /** Governance surface: the registry's keys — pinned equal to the
     * catalog's EFFECT rows by NativeDispatchTest. */
    public static java.util.Set<String> registeredEffectKeys() {
        return EFFECT_ARMS.keySet();
    }

    /** Governance surface: the staged-routine keys — pinned equal to
     * the catalog's JAVA_ROUTINE rows by NativeDispatchTest. */
    public static java.util.Set<String> registeredRoutineKeys() {
        return java.util.Set.of(
                com.legend.compiler.element.type.PlatformTypes
                        .PLAN_TO_STRING,
                com.legend.compiler.element.type.PlatformTypes
                        .PLAN_TO_STRING_WITHOUT_FORMATTING,
                com.legend.compiler.element.type.PlatformTypes
                        .TO_SQL_STRING,
                com.legend.compiler.element.type.PlatformTypes
                        .TO_SQL_STRING_PRETTY);
    }

    /** The member name of a typed enum-shaped read (DatabaseType.H2). */
    /** Engine stream-input rule (storeContract.pure:221 supportsStream +
     * executionPlan_generation.pure findParamsSupportedForStreamInput): a
     * parameter SUPPORTS STREAM iff at least one read sits under an
     * {@code in(...)} call (directly or through a collection literal) and
     * NO read sits anywhere else. The supported flag re-evaluates at
     * every call boundary (a read under {@code isEmpty}/{@code map}/...
     * is an unsupported occurrence, even inside an in's argument tree). */
    private static boolean supportsStream(
            com.legend.compiler.spec.typed.TypedLambda lam, String param,
            java.util.function.Function<TypedSpec, String> storeOf) {
        boolean[] flags = new boolean[2];   // {under-in, elsewhere}
        java.util.Set<String> inStores = new java.util.LinkedHashSet<>();
        for (TypedSpec b : lam.body()) {
            streamScan(b, param, false, "", flags, inStores, storeOf);
        }
        // engine findParamsSupportedForStreamInput: supported usages
        // spanning MORE THAN ONE STORE also disqualify (the TwoDB pin)
        return flags[0] && !flags[1] && inStores.size() <= 1;
    }

    private static void streamScan(TypedSpec n, String param,
            boolean underIn, String store, boolean[] flags,
            java.util.Set<String> inStores,
            java.util.function.Function<TypedSpec, String> storeOf) {
        if (n instanceof com.legend.compiler.spec.typed.TypedVariable v
                && v.name().equals(param)) {
            if (underIn) {
                flags[0] = true;
                inStores.add(store);
            } else {
                flags[1] = true;
            }
            return;
        }
        if (n instanceof com.legend.compiler.spec.typed.TypedLambda il
                && il.parameters().contains(param)) {
            return;   // shadowed
        }
        // a FILTER's predicate reads belong to ITS chain's store
        // (engine elementPathForCluster — each cluster carries its store)
        if (n instanceof com.legend.compiler.spec.typed.TypedFilter tf) {
            streamScan(tf.source(), param, false, store, flags, inStores,
                    storeOf);
            streamScan(tf.predicate(), param, false,
                    storeOf.apply(tf.source()), flags, inStores, storeOf);
            return;
        }
        boolean isIn = n instanceof
                com.legend.compiler.spec.typed.TypedNativeCall nc
                && "meta::pure::functions::collection::in"
                        .equals(nc.callee().qualifiedName());
        boolean carry = n instanceof
                com.legend.compiler.spec.typed.TypedCollection && underIn;
        for (TypedSpec c : n.children()) {
            streamScan(c, param, isIn || carry, store, flags, inStores,
                    storeOf);
        }
    }

    /** The DATABASE behind a chain's first class extent — the stream
     * scan's store key (falls back to the class FQN when unmapped). */
    private static String streamStoreOf(TypedSpec source,
            com.legend.compiler.element.ModelContext ctx, String mappingFqn) {
        TypedSpec cur = source;
        java.util.ArrayDeque<TypedSpec> q = new java.util.ArrayDeque<>();
        q.add(cur);
        while (!q.isEmpty()) {
            TypedSpec x = q.poll();
            if (x instanceof com.legend.compiler.spec.typed.TypedGetAll ga) {
                try {
                    return com.legend.lineage.ScanRelations.rootImpl(ctx,
                            mappingFqn, ga.classFqn())[2];
                } catch (com.legend.error.NotImplementedException e) {
                    return ga.classFqn();
                }
            }
            q.addAll(x.children());
        }
        return "";
    }

    private static String typedEnumTail(TypedSpec v) {
        if (v instanceof com.legend.compiler.spec.typed.TypedEnumValue ev) {
            return ev.value();
        }
        if (v instanceof com.legend.compiler.spec.typed.TypedPropertyAccess pa) {
            return pa.property();
        }
        return String.valueOf(v);
    }

    /**
     * A statement-position call to an EFFECTFUL function: bind the caller's
     * arguments as parameter lets (caller lets substituted in — the callee
     * body is otherwise closed) and run the body as a statement sequence.
     */
    static @com.legend.Nullable ExecutionResult executeCallStatement(
            com.legend.compiler.spec.typed.TypedUserCall call,
            java.util.List<TypedSpec> letPrefix, SpecCompiler specs, ExecEnv env,
            java.util.Deque<String> frames) {
        String key = call.callee().signatureKey();
        if (frames.contains(key)) {
            throw new IllegalStateException("recursive effectful call: "
                    + call.callee().qualifiedName());
        }
        frames.push(key);
        try {
            java.util.List<TypedSpec> frame =
                    com.legend.compiler.spec.UserCallInliner.callArgumentFrame(
                            call, letPrefix, specs,
                            v -> containsEffectfulNode(java.util.List.of(v)));
            return executeStatements(specs.compile(call.callee()).body(), frame,
                    specs, env, frames);
        } finally {
            frames.pop();
        }
    }

    /**
     * Does this expression (transitively, through user calls) reach the
     * {@code executeInDb} K-native? Memoized per callee signature; a cycle
     * scores the in-progress callee non-effectful — real recursion is
     * caught loudly at execution time.
     */
    static boolean containsEffect(TypedSpec node, SpecCompiler specs,
            java.util.Map<String, Boolean> memo) {
        if (node instanceof com.legend.compiler.spec.typed.TypedNativeCall nc
                && com.legend.compiler.element.type.PlatformTypes
                        .isEffectfulNative(nc.callee().qualifiedName())) {
            return true;
        }
        if (node instanceof com.legend.compiler.spec.typed.TypedUserCall uc) {
            String key = uc.callee().signatureKey();
            Boolean known = memo.get(key);
            if (known == null) {
                memo.put(key, false);   // in-progress: cycles score false
                boolean effectful = false;
                for (TypedSpec stmt : specs.compile(uc.callee()).body()) {
                    if (containsEffect(stmt, specs, memo)) {
                        effectful = true;
                        break;
                    }
                }
                memo.put(key, effectful);
                known = effectful;
            }
            if (known) {
                return true;
            }
        }
        // post-processor CONFIG properties carry plan-time SQL-rewrite
        // hooks, never DDL/executeInDb effects — compiling them drags in
        // relational-metamodel vocabulary the prelude does not declare
        // (ledger cluster 63)
        if (node instanceof com.legend.compiler.spec.typed
                .TypedNewInstance ni9) {
            for (var pe : ni9.properties().entrySet()) {
                if (!com.legend.compiler.element.type.PlatformTypes
                                .isPostProcessorConfigProperty(pe.getKey())
                        && containsEffect(pe.getValue(), specs, memo)) {
                    return true;
                }
            }
            return false;
        }
        if (node instanceof com.legend.compiler.spec.typed
                .TypedCopyInstance cp9) {
            if (containsEffect(cp9.source(), specs, memo)) {
                return true;
            }
            for (var pe : cp9.overrides().entrySet()) {
                if (!com.legend.compiler.element.type.PlatformTypes
                                .isPostProcessorConfigProperty(pe.getKey())
                        && containsEffect(pe.getValue(), specs, memo)) {
                    return true;
                }
            }
            return false;
        }
        for (TypedSpec c : node.children()) {
            if (containsEffect(c, specs, memo)) {
                return true;
            }
        }
        return false;
    }

    /**
     * The I&rarr;J&rarr;K tail over a resolved TYPED body — shared by
     * {@link #executeResolved} and the K-native argument evaluation below.
     */
    /** The runtime's LocalH2 {@code testDataSetupSqls}: the engine
     * executes them when it ESTABLISHES the connection — here, on the
     * ambient session before the query (per-statement tolerance, engine
     * harness semantics). {@code fromChain} carries the unwrapped
     * top-level from() setups; nested from() (a graph query whose
     * serialize wraps the from) contribute via the walk. */
    private static void runRuntimeSetups(java.util.List<String> fromChain,
            TypedSpec root, ExecEnv env) {
        java.util.List<String> setups = new java.util.ArrayList<>(fromChain);
        java.util.ArrayDeque<TypedSpec> walk = new java.util.ArrayDeque<>();
        walk.add(root);
        while (!walk.isEmpty()) {
            TypedSpec t = walk.poll();
            if (t instanceof com.legend.compiler.spec.typed.TypedFrom fr) {
                setups.addAll(fr.sqlSetups());
            }
            walk.addAll(t.children());
        }
        for (String blob : setups) {
            for (String stmt : com.legend.sql.RawSql.splitStatements(blob)) {
                try {
                    Executor.executeRaw(env.connection(), adaptRaw(stmt, env));
                } catch (com.legend.error.DataError e) {
                    if (!env.dialect().rawH2IsNative()) {
                        com.legend.sql.dialect.RawSqlBoundary.unrecordLast();
                    }
                    throw e;
                }
            }
        }
    }

    /** THE SYSTEM DATABASE ROUTE (user ruling 2026-09-02): a body reading
     * the metamodel store executes on the GRAPH's own system database —
     * separate from every user connection, written once per graph, never
     * per execution ({@link com.legend.exec.SystemDatabase}). A body reading
     * BOTH the store and a user store has no single session: LOUD (the
     * corpus census — none fired at this landing); a body reading no
     * store pays one tree walk and nothing else. */
    private static ExecEnv routeSystemDatabase(java.util.List<TypedSpec> body,
            ExecEnv env) {
        java.util.Set<String> stores = new java.util.TreeSet<>();
        for (TypedSpec n : body) {
            collectStores(n, stores);
        }
        String storeFqn = com.legend.builtin.SystemMetamodel.STORE_FQN;
        if (!stores.contains(storeFqn)) {
            return env;
        }
        if (stores.size() > 1) {
            throw new com.legend.error.NotImplementedException("a query reading"
                    + " the system metamodel store AND a user store " + stores
                    + " has no single session — the system database is"
                    + " separate from user connections");
        }
        var store = env.ctx().findDatabase(storeFqn).orElseThrow(() ->
                new IllegalStateException("the system metamodel store is"
                        + " not in the model — injection regressed"));
        ModelContext ctx = env.ctx();
        return env.withConnection(com.legend.exec.SystemDatabase.of(ctx)
                .connectionFor(env.connection(), env.dialect(), store,
                        table -> MetamodelSeeds.rows(table, ctx)));
    }

    private static void collectStores(TypedSpec n, java.util.Set<String> out) {
        if (n instanceof com.legend.compiler.spec.typed.TypedTableReference tr) {
            out.add(tr.store());
        }
        for (TypedSpec c : n.children()) {
            collectStores(c, out);
        }
    }

    /** ONE VALUE expression through the ordinary back half (G½ inline →
     * H resolve → lower/execute) — the assert-verdict arm's side
     * evaluator. The same sequence as the generic statement tail. */
    /** LOWER → post-process → two-phase dynamic pivot → deferred
     * relation-toString resolution: the whole plan-preparation ladder
     * (split from executeTyped at the 250-line shape guard). */
    private static com.legend.sql.SqlQuery lowerAndPrepare(
            java.util.List<TypedSpec> body, ExecEnv env, ModelContext ctx,
            com.legend.sql.dialect.SqlDialect dialect,
            java.sql.Connection connection, boolean identity) {
        // F13: identity-bearing layouts (keyless classes gain the __id
        // field, minted per construction site) ride ONLY the verdict-
        // side lane (canon rider) — golden-SQL text lanes and corpus
        // value lanes keep the plain layout, unperturbed.
        com.legend.lowering.Lowerer lowerer = new com.legend.lowering.Lowerer(
                t -> com.legend.compiler.element.ClassLayouts.layoutOf(ctx, t,
                        identity),
                f -> ctx.findClass(f).isPresent()).withEngineExistsJoinForm();
        // D91: the <<equality.Key>> resolver rides EVERY lane — equal()
        // over keyed instances is the key relation on the execute path
        // too (the structural fallback erased class identity and read
        // non-key fields). Identity minting stays verdict-lane-only.
        lowerer = lowerer.withInstanceKeys(t2 -> com.legend.compiler
                .element.EqualityKeys.resolve(ctx, t2));
        if (identity) {
            lowerer = lowerer.withInstanceIds(env.instanceIds()::idOf);
        }
        com.legend.sql.SqlQuery plan =
                lowerer.lower(com.legend.lowering.SeedableLets
                        .withSeedableLetPrefix(body, env.queryLets(), ctx));
        // post-process, then two-phase dynamic pivot (DynamicPivot doc)
        plan = com.legend.exec.DynamicPivot.staticize(
                com.legend.lowering.SqlPostProcessors.apply(plan,
                        env.tableReplace()), dialect, connection);
        // DEFERRED relation-toString (dynamic-pivot inners): the column
        // list exists only NOW, post-staticize. The LOWERING layer owns
        // the composition pass (invariant 6d — exec never calls the
        // middle-end); THIS orchestrator supplies the LIMIT-0 probe
        // (a SCHEMA read, DynamicPivot's two-phase discipline).
        if (!lowerer.deferredTds().isEmpty()) {
            final var fDialect = dialect;
            final var fConn = connection;
            class ProbeFailed extends RuntimeException {
                ProbeFailed(java.sql.SQLException c) { super(c); }
            }
            // the seam: PctProbe raises DataError (unchecked) itself —
            // the ProbeFailed checked-exception tunnel is gone
            plan = com.legend.lowering.Render.resolveAllDeferredTds(
                    plan, lowerer.deferredTds(),
                    inner -> com.legend.exec.PctProbe.probe(
                            inner, fDialect, fConn),
                    com.legend.exec.Executor::pureOfSqlType);
        }
        return plan;
    }

    static @com.legend.Nullable ExecutionResult evalValue(TypedSpec value,
            java.util.List<TypedSpec> letPrefix,
            com.legend.compiler.spec.SpecCompiler specs, ExecEnv env) {
        return evalValue(value, letPrefix, specs, env, null, false);
    }

    /** V11 rider entry: the canon rides the value query itself — one
     * execution serves the host referee AND the byte verdict. */
    static @com.legend.Nullable ExecutionResult evalValue(TypedSpec value,
            java.util.List<TypedSpec> letPrefix,
            com.legend.compiler.spec.SpecCompiler specs, ExecEnv env,
            com.legend.exec.@com.legend.Nullable CanonRider rider) {
        return evalValue(value, letPrefix, specs, env, rider, false);
    }

    /** F13c: {@code identity} asks for the identity lane WITHOUT a
     * canon rider — assert-CONDITION sides (the value is a boolean; the
     * in-SQL eq/equal arm needs instance identity to compile the
     * engine's equality relation). A rider implies identity. */
    static @com.legend.Nullable ExecutionResult evalValue(TypedSpec value,
            java.util.List<TypedSpec> letPrefix,
            com.legend.compiler.spec.SpecCompiler specs, ExecEnv env,
            com.legend.exec.@com.legend.Nullable CanonRider rider,
            boolean identity) {
        return evalValue(value, letPrefix, specs, env, rider, identity, null);
    }

    /** V7 batch 2: {@code hook} non-null threads the statement loop's
     * result-envelope splice into the verdict side evaluation — the
     * SAME {@code UserCallInliner} hook ordinary statements get, so an
     * assert side reading an execute() frame compiles the spliced
     * chain (never a raw variable read). */
    static @com.legend.Nullable ExecutionResult evalValue(TypedSpec value,
            java.util.List<TypedSpec> letPrefix,
            com.legend.compiler.spec.SpecCompiler specs, ExecEnv env,
            com.legend.exec.@com.legend.Nullable CanonRider rider,
            boolean identity,
            java.util.function.@com.legend.Nullable BiFunction<TypedSpec,
                    java.util.Set<String>, TypedSpec> hook) {
        java.util.List<TypedSpec> single = new java.util.ArrayList<>(letPrefix);
        single.add(value);
        var inliner = hook == null
                ? new com.legend.compiler.spec.UserCallInliner(specs)
                : new com.legend.compiler.spec.UserCallInliner(specs, hook);
        java.util.List<TypedSpec> body = new java.util.ArrayList<>(
                inliner.inlineBody(single));
        env.queryLets().putAll(inliner.queryLets());
        final java.util.List<TypedSpec> stageEnv = body;
        body.replaceAll(b -> com.legend.compiler.spec.NativeDispatch
                .stage(b, stageEnv, nativeRoutines(specs, env)));
        com.legend.resolver.StoreResolver sideResolver =
                new com.legend.resolver.StoreResolver(env.ctx(), specs)
                        .withLetBindings(env.queryLets());
        body = sideResolver.resolve(body, env.runtimeFqn());
        // the addDriverTablePkForProject option is part of the EXECUTION
        // ENV — a verdict side must project the same columns the generic
        // statement path projects (#45; the validation-family probe
        // caught the missing ID column, V7 batch 2)
        if (env.addDriverTablePk()) {
            body = com.legend.resolver.DriverPkAppend.apply(body, env.ctx());
        }
        return executeTyped(body, env,
                rider, identity);
    }

    static ExecutionResult executeTyped(
            java.util.List<TypedSpec> body, ExecEnv env) {
        return executeTyped(body, env, null, false);
    }

    static ExecutionResult executeTyped(
            java.util.List<TypedSpec> body, ExecEnv env,
            com.legend.exec.@com.legend.Nullable CanonRider rider) {
        return executeTyped(body, env, rider, false);
    }

    /** V11: {@code rider} non-null asks the SQL path to carry the
     * canonical renders as appended columns (wrapWithCanon); every
     * non-SQL arm leaves the rider's initial "non-sql-arm" decline in
     * place — counted at the verdict seam, never silent. F13c:
     * {@code identityLane} joins the identity lane without a rider
     * (assert-condition sides). */
    static ExecutionResult executeTyped(
            java.util.List<TypedSpec> body, ExecEnv env,
            com.legend.exec.@com.legend.Nullable CanonRider rider,
            boolean identityLane) {
        ModelContext ctx = env.ctx();
        String runtimeFqn = env.runtimeFqn();
        env = routeSystemDatabase(body, env);
        java.sql.Connection connection = env.connection();
        // late-bound grids: FIRST-query schema pin — staged compilation:
        // the resolver pass takes the probed roster through the oracle
        body = com.legend.resolver.RawGridSchema.stamp(body,
                gridOracle(connection, env));
        TypedSpec root = body.get(body.size() - 1);
        // from() is context-only, but its info is the PRE-RESOLUTION
        // declared type — kept: a primitive-many declared root whose
        // resolved source became relation-shaped (scalar ->map lowers to
        // a one-column project) still executes as a VALUE COLLECTION, so
        // the Executor's null-drop applies (pure collections hold no
        // empties — the no-match parent contributes nothing, task #78).
        com.legend.compiler.element.type.ExprType declaredInfo = null;
        java.util.List<String> runtimeSetups = new java.util.ArrayList<>();
        while (root instanceof com.legend.compiler.spec.typed.TypedFrom fr) {
            if (declaredInfo == null) {
                declaredInfo = fr.info();
            }
            runtimeSetups.addAll(fr.sqlSetups());
            root = fr.source();
        }
        runRuntimeSetups(runtimeSetups, root, env);
        // CATALOG DISPATCH (EFFECT rows, ladder census §10m): the
        // effectful K-natives run their registered arm when evaluation
        // reaches the call — one lookup, no name literals.
        if (root instanceof com.legend.compiler.spec.typed.TypedNativeCall nc
                && com.legend.compiler.element.type.PlatformTypes
                        .IMPLEMENTATION_KIND.get(nc.callee().qualifiedName())
                        == com.legend.compiler.element.type.PlatformTypes
                                .NativeImpl.EFFECT) {
            EffectRoutine arm = EFFECT_ARMS.get(nc.callee().qualifiedName());
            if (arm == null) {
                throw new com.legend.error.NotImplementedException(
                        "catalog says '" + nc.callee().qualifiedName()
                        + "' is EFFECT but no arm is registered");
            }
            return arm.run(body, nc, env);
        }
        // ORCHESTRATION-VALUE channel: store navigation resolves against
        // the compiled model (grid reads are typed relations now —
        // Phase 1c endgame; ResultNav deleted)
        if (com.legend.exec.StoreNav.owns(root, java.util.Map.of())) {
            ExecutionResult hosted = hostEvalAtSeam(root, java.util.Map.of(), env);
            if (hosted != null) { return hosted; }
        }
        // DDL STRING generators (toDDL deprecated forms): evaluated HERE —
        // the engine walks its Database metamodel, we render from the
        // compiled store model (the lowerer has no model access)
        if (root instanceof com.legend.compiler.spec.typed.TypedNativeCall ds
                && com.legend.compiler.element.type.PlatformTypes
                        .isDdlStatementFn(ds.callee().qualifiedName())) {
            return new ExecutionResult.Scalar(ddlStatementString(ds, env),
                    ds.info().type());
        }
        ExecutionResult handle = orchestrationHandleArm(root, env);
        if (handle != null) {
            return handle;
        }
        // a COLLECTION whose elements include DDL string generators (the
        // aggregationAware setup shape: [dropSchemaStatement(..), ...]
        // ->map(s|executeInDb(..))): every element evaluates here
        if (root instanceof com.legend.compiler.spec.typed.TypedCollection ddlColl
                && ddlColl.elements().stream().anyMatch(e ->
                        e instanceof com.legend.compiler.spec.typed.TypedNativeCall enc
                        && com.legend.compiler.element.type.PlatformTypes
                                .isDdlStatementFn(enc.callee().qualifiedName()))) {
            java.util.List<Object> strs = new java.util.ArrayList<>();
            for (TypedSpec e : ddlColl.elements()) {
                if (e instanceof com.legend.compiler.spec.typed.TypedNativeCall enc
                        && com.legend.compiler.element.type.PlatformTypes
                                .isDdlStatementFn(enc.callee().qualifiedName())) {
                    strs.add(ddlStatementString(enc, env));
                } else if (e instanceof com.legend.compiler.spec.typed.TypedCString cs2) {
                    strs.add(cs2.value());
                } else {
                    throw new IllegalStateException("DDL statement collection"
                            + " carries a non-DDL, non-literal element: "
                            + e.getClass().getSimpleName());
                }
            }
            return new ExecutionResult.Collection(strs,
                    com.legend.compiler.element.type.Type.Primitive.STRING);
        }
        // map over an EFFECTFUL lambda ($sqls->map(sql|executeInDb(...))):
        // the source collection evaluates through the pipeline; each
        // element executes the lambda body with the parameter bound (the
        // one statement-orchestration shape the corpus's setup bodies use).
        if (root instanceof com.legend.compiler.spec.typed.TypedMap tm
                && containsEffectfulNode(java.util.List.of(tm.mapper()))) {
            java.util.List<TypedSpec> src = new java.util.ArrayList<>(
                    body.subList(0, body.size() - 1));
            src.add(tm.source());
            ExecutionResult seedForm = SeedSqlForms.mappedExecutionForm(body, tm, env);
            ExecutionResult values = seedForm != null ? seedForm
                    : executeTyped(src, env);
            java.util.List<Object> vals = switch (values) {
                case ExecutionResult.Collection c -> c.values();
                case ExecutionResult.Scalar sc2 -> sc2.value() == null
                        ? java.util.List.of() : java.util.List.of(sc2.value());
                default -> throw new IllegalStateException(
                        "effectful map over a non-collection source");
            };
            String param = tm.mapper().parameters().get(0);
            ExecutionResult last = new ExecutionResult.Scalar(null,
                    tm.info().type());
            for (Object v : vals) {
                if (!(v instanceof String sv)) {
                    throw new IllegalStateException("effectful map element is"
                            + " not a string: " + v);
                }
                java.util.List<TypedSpec> one = new java.util.ArrayList<>(
                        body.subList(0, body.size() - 1));
                for (TypedSpec stmt2 : tm.mapper().body()) {
                    one.add(com.legend.compiler.spec.UserCallInliner
                            .bindStringParam(stmt2, param, sv));
                }
                last = executeTyped(one, env);
            }
            return last;
        }
        // V11: a canon-riding side SKIPS the literal fold — the fold is
        // a value-fetch optimization, but a requested canon is computed
        // BY THE DATABASE, so the side executes (same cost as the
        // deleted runCanon round trip; the values now come through the
        // full pipeline too, one road for both). The fold survives as
        // the LAST-RESORT value source for literals SQL cannot spell
        // (NUL-bearing strings — DuckDB VARCHAR is NUL-free): the
        // executePlan tunnel returns it with a counted decline.
        com.legend.exec.ExecutionResult folded = LiteralFold.fold(root);
        if (folded != null && rider == null) {
            return folded;
        }
        if (System.getenv("LL_DUMP_RESOLVED") != null) {
            System.err.println("[resolved] " + body);
        }
        com.legend.sql.dialect.SqlDialect dialect = env.dialect();
        com.legend.sql.SqlQuery plan = lowerAndPrepare(body, env, ctx,
                dialect, connection, rider != null || identityLane);
        boolean collectionDeclared = declaredInfo != null
                && declaredInfo.type()
                        instanceof com.legend.compiler.element.type.Type.Primitive
                && declaredInfo.multiplicity()
                        .requireBounded("result shape").isMany()
                && com.legend.compiler.element.type.Type
                        .isRelation(root.info().type());
        if (System.getenv("LL_TMP_SQL") != null) {
            System.err.println("[exec-sql] " + dialect.render(plan));
        }
        // E1 (JAVA_EVICTION_PLAN): post-staticize wrap — the plan
        // emits the PCT wire text as one Scalar String
        if (com.legend.exec.PctRenderOption.enabled()
                && com.legend.compiler.element.type.Type
                        .isRelation(root.info().type())) {
            return executePctTds(plan, root, dialect, connection);
        }
        ExecutionResult res = executePlan(plan, root,
                collectionDeclared ? declaredInfo : null, rider, folded, env);
        enforceToOneReader(root, res);
        return res;
    }

    /** ORCHESTRATION-HANDLE arms of {@link #executeTyped} (extracted at
     * the file guard, V11): connection/runtime values never lower.
     * Null = not a handle (the caller continues). */
    private static @com.legend.Nullable ExecutionResult orchestrationHandleArm(
            TypedSpec root, ExecEnv env) {
        if (root instanceof com.legend.compiler.spec.typed.TypedCast castC
                && castC.source()
                        instanceof com.legend.compiler.spec.typed.TypedNativeCall cbe2
                && com.legend.compiler.element.type.PlatformTypes
                        .CONNECTION_BY_ELEMENT
                        .equals(cbe2.callee().qualifiedName())) {
            return new ExecutionResult.Scalar(null, castC.info().type());
        }
        if (root instanceof com.legend.compiler.spec.typed.TypedNewInstance rni
                && ("meta::core::runtime::Runtime".equals(rni.classFqn())
                        || "meta::core::runtime::ConnectionStore"
                                .equals(rni.classFqn()))) {
            if (containsEffectfulNode(new java.util.ArrayList<>(
                    rni.properties().values()))) {
                throw new IllegalStateException("^" + rni.classFqn()
                        + "(...) constructor argument carries an"
                        + " executeInDb-family effect; the orchestration-"
                        + "handle arm never evaluates arguments");
            }
            return new ExecutionResult.Scalar(null, rni.info().type());
        }
        // TYPE-driven handle rule (XStore slice 2b): a CONNECTION/RUNTIME-
        // typed VALUE is an orchestration handle regardless of expression
        // shape — the corpus's connection-picking idiom
        // (testRuntime().connectionStores->filter(c|...)->toOne()) must
        // never lower to SQL (it list_filter'd a struct literal). Same
        // effect guard as the ctor arm: nested effects never drop silently.
        if (root.info().type()
                instanceof com.legend.compiler.element.type.Type.ClassType hct
                // Nil (the []-born BOTTOM type) subtypes EVERYTHING —
                // including Connection — but a Nil-typed root is an empty
                // VALUE ([]->tail() is an empty collection), never an
                // orchestration handle: the handle arm returned Scalar(null)
                // where the caller expects an empty Collection
                && !com.legend.compiler.element.type.PlatformTypes.isNil(hct)
                && ("meta::core::runtime::Runtime".equals(hct.fqn())
                        || "meta::core::runtime::ConnectionStore".equals(hct.fqn())
                        || env.ctx().isSubtype(hct.fqn(),
                                "meta::core::runtime::Connection"))) {
            if (containsEffectfulNode(java.util.List.of(root))) {
                throw new IllegalStateException("a connection-typed value"
                        + " expression carries an executeInDb-family effect;"
                        + " the orchestration-handle arm never evaluates it");
            }
            return new ExecutionResult.Scalar(null, root.info().type());
        }
        return null;
    }

    /** The plan-execution tail of {@link #executeTyped}: V11 canon
     * wrap (when a rider asks) + the one Executor call. */
    private static ExecutionResult executePlan(com.legend.sql.SqlQuery plan,
            TypedSpec root,
            com.legend.compiler.element.type.@com.legend.Nullable ExprType declaredInfo,
            com.legend.exec.@com.legend.Nullable CanonRider rider,
            @com.legend.Nullable ExecutionResult folded, ExecEnv env) {
        com.legend.compiler.element.type.ExprType shapeInfo =
                declaredInfo != null ? declaredInfo : root.info();
        com.legend.sql.SqlQuery bare = plan;
        com.legend.exec.ResultShape shape = declaredInfo != null
                ? com.legend.exec.ResultShape.COLLECTION
                : com.legend.exec.ResultShape.of(root);
        if (rider != null && shape == com.legend.exec.ResultShape.TABULAR) {
            // V7 §8 leg 1 — a TABULAR side rides the GRID canon: one
            // per-ROW canonical text as the appended last column (the
            // fusion-spike F2 shape); the tabular decode strips it into
            // the rider, row-aligned.
            var gw = com.legend.lowering.CanonicalRenderSql.wrapTdsCanon(
                    plan, com.legend.compiler.element.type.Type.schemaView(
                            shapeInfo.type()));
            if (gw.declineReason() != null) {
                rider.decline(gw.declineReason());
            } else {
                rider.tdsWrap(plan.outputs().size());
                plan = gw.plan();
            }
        } else if (rider != null) {
            var w = com.legend.lowering.CanonicalRenderSql.wrapWithCanon(
                    plan, shapeInfo, rider.canonicalOrder(),
                    // substitution-aware (Pair-of-Pairs): the stamp's
                    // instantiation decides key nesting
                    com.legend.compiler.element.EqualityKeys
                            .resolve(env.ctx(), shapeInfo.type()));
            if (w.declineReason() != null) {
                rider.decline(w.declineReason());
            } else {
                rider.wrap(w.kinds(), w.many(), w.literalIndex());
                plan = w.plan();
            }
        }
        com.legend.sql.dialect.SqlDialect dialect = env.dialect();
        if (rider == null) {
            return Executor.execute(dialect.render(plan), plan, shapeInfo,
                    shape, env.connection(), dialect, null);
        }
        try {
            return Executor.execute(dialect.render(plan), plan, shapeInfo,
                    shape, env.connection(), dialect, rider);
        } catch (RuntimeException e) {
            // THE DECLINE TUNNEL, V11 form (DataError included — the
            // seam made the boundary translation a RuntimeException) (prepCanon/runCanon caught
            // exactly this class — a caught failure becomes the DESIGNED
            // decline sentinel, counted, never a rescue): a canon column
            // must never poison the value fetch. Witness: the
            // MIXED-ELEMENT IDENTITY carrier (F10) — pure PRINT-FORM
            // VARCHAR ('7.345D') errors under the candidate casts,
            // undetectable at wrap time (OutputCol types are
            // stamp-derived, the V6 circularity). The side re-executes
            // BARE (pure SELECT — effectful statements never reach the
            // K-arm) and the canon declines.
            boolean wasWrapped = rider.wrapped() || rider.tdsWrapped();
            boolean hadDroppableLiteral = rider.literalIndex() >= 1;
            rider.rows().clear();
            if (wasWrapped) {
                rider.decline("canon-exec: "
                        + String.valueOf(e.getMessage()).split("\\n")[0]);
            }
            // MIDDLE RUNG (F10 v1): a TYPED side whose failure may be
            // its literal candidate alone (stamp-derived column types
            // lie about the wire — the BLOB byte carrier under a STRING
            // stamp) re-wraps WITHOUT the literal channel: the bare
            // candidates keep byte-deciding instead of demoting to the
            // host. A second failure falls through to the bare rung.
            if (wasWrapped && hadDroppableLiteral) {
                try {
                    var w2 = com.legend.lowering.CanonicalRenderSql
                            .wrapWithCanon(bare, shapeInfo,
                                    rider.canonicalOrder(),
                                    com.legend.compiler.element.EqualityKeys
                                            .resolve(env.ctx(),
                                                    shapeInfo.type()),
                                    false);
                    if (w2.declineReason() == null) {
                        rider.wrap(w2.kinds(), w2.many(), w2.literalIndex());
                        return Executor.execute(
                                dialect.render(w2.plan()), w2.plan(),
                                shapeInfo, shape, env.connection(), dialect,
                                rider);
                    }
                } catch (RuntimeException e15) {
                    rider.rows().clear();
                    rider.decline("canon-exec: "
                            + String.valueOf(e15.getMessage())
                                    .split("\\n")[0]);
                }
            }
            try {
                if (!wasWrapped) {
                    // the canon never rode (wrap already declined) —
                    // the failure is the side's own
                    throw e;
                }
                return Executor.execute(dialect.render(bare), bare,
                        shapeInfo, shape, env.connection(), dialect, null);
            } catch (RuntimeException e2) {
                // the BARE side itself cannot execute: an unSQLable
                // literal (NUL-bearing string — DuckDB VARCHAR is
                // NUL-free). The literal fold answers, canon declines.
                if (folded != null) {
                    rider.decline("unsqlable-literal: "
                            + String.valueOf(e2.getMessage()).split("\\n")[0]);
                    return folded;
                }
                throw e2;
            }
        }
    }

    /** E1: probe (pivot plans only) → lowering-side wrap → SCALAR
     * String execution — the wire text is the plan's projection. */
    private static ExecutionResult executePctTds(com.legend.sql.SqlQuery plan,
            TypedSpec root, com.legend.sql.dialect.SqlDialect dialect,
            java.sql.Connection connection) {
        com.legend.sql.PlanProbe probe =
                com.legend.lowering.PctTdsWrap.pivots(plan).isEmpty() ? null
                        : com.legend.exec.PctProbe.probe(plan, dialect,
                                connection);
        com.legend.sql.SqlQuery rendered =
                com.legend.lowering.PctTdsWrap.wrap(plan,
                        com.legend.compiler.element.type.Type
                                .requireRelationSchema(root.info().type()),
                        probe, com.legend.exec.Executor::pureOfSqlType);
        com.legend.exec.PctRenderOption.markRendered();
        return Executor.execute(dialect.render(rendered), rendered,
                com.legend.compiler.element.type.ExprType.one(
                        com.legend.compiler.element.type.Type.Primitive
                                .STRING),
                com.legend.exec.ResultShape.SCALAR, connection, dialect);
    }

    /** rows->toOne() READER enforcement (audit 22b F1): the lowering is
     * row-identical to the relation (engine toOne throws at the READER,
     * never in SQL) — the reader enforces exactly-one for a
     * TABULAR-consumed toOne root; the scalar arm's second-row guard
     * covers scalar reads, this covers whole-TDS consumption and the
     * ZERO-row lower bound. */
    private static void enforceToOneReader(TypedSpec root, ExecutionResult res) {
        if (root instanceof com.legend.compiler.spec.typed.TypedNativeCall tw
                && com.legend.builtin.Pure.isToOneCall(tw.callee().qualifiedName())
                && !tw.args().isEmpty()
                && com.legend.compiler.element.type.Type
                        .isRelation(tw.args().get(0).info().type())
                && res instanceof ExecutionResult.Tabular tab
                && tab.rows().size() != 1) {
            throw new IllegalStateException("toOne() over a relation returned "
                    + tab.rows().size() + " row(s) — the exactly-one contract"
                    + " (engine reader semantics)");
        }
    }

    /**
     * The K-native {@code executeInDb} (PlatformTypes.EXECUTE_IN_DB): the
     * engine's JDBC boundary. The SQL argument is an ordinary Pure
     * expression and is evaluated THROUGH the pipeline (Java orchestrates,
     * the database evaluates); the connection argument is NEVER evaluated —
     * there is exactly one ambient connection per execution context, and
     * the corpus's connection-resolution chains
     * ({@code testRuntime()->connectionByElement(...)}) exist only to
     * type-check. Let statements the SQL argument does not (transitively)
     * reference — crucially those connection chains — are dropped before
     * evaluation. The blob is dialect-adapted, split on top-level
     * {@code ;}, and executed statement by statement.
     */
    /** Corpus-authored raw H2 adapts to the SESSION: identity on a
     * dialect that executes H2 natively, the boundary translator for the
     * DuckDB reference target (H2_BACKEND.md §12 step 12 — the rewrite
     * is a DuckDB-target adaptation, never generic). */
    private static String adaptRaw(String sql, ExecEnv env) {
        return env.dialect().rawH2IsNative() ? sql
                : com.legend.sql.dialect.RawSqlBoundary.h2ToDuckDb(sql);
    }

    static ExecutionResult executeInDb(
            java.util.List<TypedSpec> body,
            com.legend.compiler.spec.typed.TypedNativeCall call, ExecEnv env) {
        String raw = evalStringArg(body, call.args().get(0), env);
        // split FIRST: adaptation is per-statement (its recognizers anchor
        // at statement start). Corpus-authored raw H2 goes through THE
        // boundary translator — never a dialect renderer (R0 rule).
        for (String stmt : com.legend.sql.RawSql.splitStatements(raw)) {
            try {
                Executor.executeRaw(env.connection(), adaptRaw(stmt, env));
            } catch (com.legend.error.DataError e) {
                // the recording must mirror EXECUTED reality — a failed
                // statement leaves the H2-replay ledger (task #112)
                if (!env.dialect().rawH2IsNative()) {
                    com.legend.sql.dialect.RawSqlBoundary.unrecordLast();
                }
                throw e;
            }
        }
        // an opaque ResultSet handle: setup statements ignore it; a test
        // that READS it will surface loudly here when that day comes
        return new ExecutionResult.Scalar(null, call.info().type());
    }

    /** The K-native {@code dropAndCreateSchemaInDb}: the engine DROPS +
     * creates; here create-if-missing — the DDL seeds already own tables
     * in the schema, and the setup's own dropAndCreateTableInDb calls
     * recreate what it manages. Recorded on BOTH channels
     * (FULL_RESIDUE_CENSUS §9a root cause, 2026-08-30): the old
     * metadata-only recording WITHHELD a corpus-authored, executed
     * statement from the H2 row-replay ledger — violating the
     * recording's own mirror-executed-reality invariant. The mirror
     * only stayed alive because the module-DDL layer's recorded creates
     * happened to provide the schemas; without them one suppressed
     * create per family POISONED the family mirror and failed every
     * later advisory-dependent test (9 across 2 families, measured).
     * `Create schema if not exists` is valid H2 and idempotent — the
     * replay is safe on both fresh and live mirrors. */
    static ExecutionResult dropAndCreateSchemaInDb(
            java.util.List<TypedSpec> body,
            com.legend.compiler.spec.typed.TypedNativeCall sc, ExecEnv env) {
        String schemaDdl = "Create schema if not exists "
                + evalStringArg(body, sc.args().get(0), env);
        Executor.executeRaw(env.connection(), schemaDdl);
        var rec = com.legend.sql.dialect.RawSqlBoundary.recording();
        if (rec != null) {
            rec.add(schemaDdl);
        }
        com.legend.sql.dialect.RawSqlBoundary.recordMeta(schemaDdl);
        return new ExecutionResult.Scalar(true, sc.info().type());
    }

    /**
     * The K-native {@code dropAndCreateTableInDb}
     * (PlatformTypes.DROP_AND_CREATE_TABLE_IN_DB): the real engine spells
     * DDL by walking the Database metamodel; here it renders from the
     * compiled store model ({@link com.legend.exec.Ddl}) and executes over
     * the ambient connection — same connection convention as executeInDb.
     */
    static ExecutionResult dropAndCreateTableInDb(
            java.util.List<TypedSpec> body,
            com.legend.compiler.spec.typed.TypedNativeCall call, ExecEnv env) {
        ModelContext ctx = env.ctx();
        java.sql.Connection connection = env.connection();
        if (!(call.args().get(0)
                instanceof com.legend.compiler.spec.typed.TypedPackageableRef db)) {
            throw new IllegalStateException("dropAndCreateTableInDb: the database"
                    + " argument must be a store reference, got "
                    + call.args().get(0).getClass().getSimpleName());
        }
        boolean hasSchema = call.args().size() == 4;
        String schema = hasSchema
                ? evalStringArg(body, call.args().get(1), env)
                : "default";
        String table = evalStringArg(body, call.args().get(hasSchema ? 2 : 1), env);
        String lookup = "default".equals(schema) ? table : schema + "." + table;
        com.legend.model.DatabaseDefinition.TableDefinition def =
                ctx.findTableDefinition(db.fullPath(), lookup)
                        .orElseThrow(() -> new IllegalStateException(
                                "dropAndCreateTableInDb: no table '" + lookup
                                        + "' in store " + db.fullPath()));
        // F7.4: model-derived DDL routes DIRECTLY — spelled for the
        // target from the TYPE, never rendered-H2-then-regexed. The H2
        // advisory mirror still needs its H2-flavored stream: the SAME
        // model spells it a second time (recorded only after the session
        // executed — the recording mirrors executed reality).
        boolean rawH2 = env.dialect().rawH2IsNative();
        String drop = Ddl.dropTable(schema, table);
        Executor.executeRaw(connection, drop);
        Executor.executeRaw(connection,
                Ddl.createTable(def, schema, !rawH2));
        java.util.List<String> mirror =
                com.legend.sql.dialect.RawSqlBoundary.recording();
        if (!rawH2 && mirror != null) {
            mirror.add(drop);
            mirror.add(Ddl.createTable(def, schema));
        }
        // the ENGINE's dropAndCreateTableInDb applies PRIMARY KEY
        // constraints; our DuckDB DDL deliberately omits them (milestoned
        // re-seeds) — the H2 second target's stream keeps the engine
        // semantics via a record-only ALTER (fetchDbPrimaryKeysMetaData)
        java.util.List<String> pks = def.columns().stream()
                .filter(com.legend.model.DatabaseDefinition
                        .ColumnDefinition::primaryKey)
                .map(com.legend.model.DatabaseDefinition
                        .ColumnDefinition::name)
                .toList();
        if (!pks.isEmpty()) {
            String qn = "default".equals(schema) ? table
                    : schema + "." + table;
            for (String pk : pks) {
                // H2 2.x requires PK columns NOT NULL before the ALTER
                com.legend.sql.dialect.RawSqlBoundary.recordMeta("Alter table "
                        + qn + " alter column " + pk + " set not null");
            }
            com.legend.sql.dialect.RawSqlBoundary.recordMeta("Alter table " + qn
                    + " add primary key (" + String.join(", ", pks) + ")");
        }
        return new ExecutionResult.Scalar(true, call.info().type());
    }

    /** One toDDL string-generator call — engine golden spellings
     * (testDDL.pure:42-45); createTableStatement renders from the
     * compiled store model like dropAndCreateTableInDb. */
    private static String ddlStatementString(
            com.legend.compiler.spec.typed.TypedNativeCall ds, ExecEnv env) {
        String fqn = ds.callee().qualifiedName();
        if (com.legend.compiler.element.type.PlatformTypes
                .DROP_SCHEMA_STATEMENT.equals(fqn)
                || com.legend.compiler.element.type.PlatformTypes
                        .CREATE_SCHEMA_STATEMENT.equals(fqn)) {
            if (!(ds.args().get(0)
                    instanceof com.legend.compiler.spec.typed.TypedCString sc)) {
                throw new IllegalStateException(fqn + ": only literal schema"
                        + " names are supported, got "
                        + ds.args().get(0).getClass().getSimpleName());
            }
            return com.legend.compiler.element.type.PlatformTypes
                    .DROP_SCHEMA_STATEMENT.equals(fqn)
                    ? "Drop schema if exists " + sc.value() + " cascade;"
                    : "Create Schema if not exists " + sc.value() + ";";
        }
        // 2-arg forms default the schema (toDDL.pure:34-42)
        boolean twoArg = ds.args().size() == 2;
        if (!(ds.args().get(0)
                instanceof com.legend.compiler.spec.typed.TypedPackageableRef db)
                || !(ds.args().get(twoArg ? 1 : 1)
                        instanceof com.legend.compiler.spec.typed.TypedCString a1)
                || (!twoArg && !(ds.args().get(2)
                        instanceof com.legend.compiler.spec.typed.TypedCString))) {
            throw new IllegalStateException(fqn + ": literal"
                    + " (database, ['schema',] 'table') arguments required");
        }
        String sch = twoArg ? "default" : a1.value();
        String tbl = twoArg ? a1.value()
                : ((com.legend.compiler.spec.typed.TypedCString)
                        ds.args().get(2)).value();
        if (com.legend.compiler.element.type.PlatformTypes
                .DROP_TABLE_STATEMENT.equals(fqn)) {
            return Ddl.dropTable(sch, tbl);
        }
        String lookup = "default".equals(sch) ? tbl : sch + "." + tbl;
        com.legend.model.DatabaseDefinition.TableDefinition def =
                env.ctx().findTableDefinition(db.fullPath(), lookup)
                        .orElseThrow(() -> new IllegalStateException(
                                "createTableStatement: no table '" + lookup
                                        + "' in store " + db.fullPath()));
        // the ENGINE_TEXT flavor of the ONE generator (NOT NULL /
        // PRIMARY KEY constraints) — the EXECUTION flavors stay
        // constraint-free for DuckDB re-seeds
        return Ddl.createTable(def, sch, Ddl.Flavor.ENGINE_TEXT);
    }

    /**
     * Evaluate one String[1] argument of a K-native THROUGH the pipeline:
     * the let statements it (transitively) references ride along; all
     * others — crucially connection chains — are dropped, never evaluated.
     */
    static String evalStringArg(java.util.List<TypedSpec> body, TypedSpec arg,
            ExecEnv env) {
        java.util.Set<String> needed = new java.util.HashSet<>();
        collectVariableRefs(arg, needed);
        java.util.List<TypedSpec> kept = new java.util.ArrayList<>();
        for (int i = body.size() - 2; i >= 0; i--) {
            TypedSpec stmt = body.get(i);
            if (!(stmt instanceof com.legend.compiler.spec.typed.TypedLet let)) {
                throw new IllegalStateException("K-native dispatch: non-let statement"
                        + " preceding the call is not supported: "
                        + stmt.getClass().getSimpleName());
            }
            if (needed.contains(let.name())) {
                kept.add(0, let);
                collectVariableRefs(let.value(), needed);
            }
        }
        kept.add(arg);
        ExecutionResult evaluated = executeTyped(kept, env);
        if (!(evaluated instanceof ExecutionResult.Scalar sc)
                || !(sc.value() instanceof String str)) {
            throw new IllegalStateException("K-native dispatch: the argument"
                    + " must evaluate to one String, got " + evaluated);
        }
        return str;
    }

    /** Does any node in these (post-inline) trees carry a REAL K effect?
     * An executeInDb whose SQL is a literal SELECT is provably read-only
     * (the corpus prints such probes) and does not count. */
    static boolean containsEffectfulNode(java.util.List<TypedSpec> nodes) {
        for (TypedSpec n : nodes) {
            if (n instanceof com.legend.compiler.spec.typed.TypedNativeCall nc
                    && com.legend.compiler.element.type.PlatformTypes
                            .isEffectfulNative(nc.callee().qualifiedName())
                    && !isLiteralSelect(nc)) {
                return true;
            }
            if (containsEffectfulNode(n.children())) {
                return true;
            }
        }
        return false;
    }

    static boolean isLiteralSelect(
            com.legend.compiler.spec.typed.TypedNativeCall nc) {
        String fqn = nc.callee().qualifiedName();
        boolean sqlCarrier = com.legend.compiler.element.type.PlatformTypes
                .EXECUTE_IN_DB.equals(fqn);
        return sqlCarrier && !nc.args().isEmpty()
                && nc.args().get(0)
                        instanceof com.legend.compiler.spec.typed.TypedCString cs
                && cs.value().strip().toLowerCase(java.util.Locale.ROOT)
                        .startsWith("select");
    }

    /** Conservative free-variable scan (shadowed names over-collect — over-KEEPING lets is safe). */
    static void collectVariableRefs(TypedSpec node, java.util.Set<String> out) {
        if (node instanceof com.legend.compiler.spec.typed.TypedVariable v) {
            out.add(v.name());
        }
        node.children().forEach(c -> collectVariableRefs(c, out));
    }
}
