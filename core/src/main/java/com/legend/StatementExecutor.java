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
            com.legend.model.spec.ValueSpecification resolved, ModelContext ctx,
            @com.legend.Nullable String runtimeFqn,
            com.legend.sql.dialect.SqlDialect dialect,
            java.sql.Connection connection,
            java.util.function.@com.legend.Nullable Consumer<String> rawSqlFailureSink)
            throws java.sql.SQLException {
        SpecCompiler specs = new SpecCompiler(ctx);
        ExecEnv env = new ExecEnv(ctx, runtimeFqn, dialect, connection,
                rawSqlFailureSink,
                com.legend.validation.DriverPkOption.get());
        return executeStatements(specs.typeQueryBody(resolved),
                new java.util.ArrayList<>(), specs, env,
                new java.util.ArrayDeque<>());
    }

    /** The K-phase execution environment: ONE ambient connection, ONE
     * dialect (audit 17: recomputing it per arm invited a future
     * mixed-dialect bug), the driver runtime, the optional raw-SQL
     * failure sink, and the addDriverTablePkForProject execution option
     * (#45 — see {@link com.legend.validation.DriverPkOption}). */
    record ExecEnv(ModelContext ctx, @com.legend.Nullable String runtimeFqn,
            com.legend.sql.dialect.SqlDialect dialect,
            java.sql.Connection connection,
            @com.legend.Nullable java.util.function.Consumer<String> rawSqlFailureSink,
            boolean addDriverTablePk,
            java.util.Map<String, TypedSpec> queryLets,
            java.util.Map<String, String> tableReplace) {
        ExecEnv(ModelContext ctx, @com.legend.Nullable String runtimeFqn,
                com.legend.sql.dialect.SqlDialect dialect,
                java.sql.Connection connection,
                @com.legend.Nullable java.util.function.Consumer<String>
                        rawSqlFailureSink,
                boolean addDriverTablePk,
                java.util.Map<String, TypedSpec> queryLets) {
            // historical arity: no connection post-processor hooks
            this(ctx, runtimeFqn, dialect, connection, rawSqlFailureSink,
                    addDriverTablePk, queryLets, java.util.Map.of());
        }

        ExecEnv(ModelContext ctx, @com.legend.Nullable String runtimeFqn,
                com.legend.sql.dialect.SqlDialect dialect,
                java.sql.Connection connection,
                @com.legend.Nullable java.util.function.Consumer<String>
                        rawSqlFailureSink,
                boolean addDriverTablePk) {
            // run-scoped accumulator of inliner-consumed lets: graph-tree
            // date args keep their source spelling (the serialize key), so
            // every resolver seeds its let env from here (engine
            // inScopeVars)
            this(ctx, runtimeFqn, dialect, connection, rawSqlFailureSink,
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
            SpecCompiler specs, ExecEnv env, java.util.Deque<String> frames)
            throws java.sql.SQLException {
        ExecutionResult result = null;
        java.util.Map<String, Boolean> effectMemo = new java.util.HashMap<>();
        java.util.Map<String, ExecFrame> execFrames = new java.util.LinkedHashMap<>();
        for (int i = 0; i < stmts.size(); i++) {
            TypedSpec stmt = stmts.get(i);
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
                        && com.legend.compiler.element.type.PlatformTypes
                                .isExecuteFqn(ec.callee().qualifiedName())) {
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
                    for (int j = i + 1; j < stmts.size(); j++) {
                        if (referencesVar(stmts.get(j), let.name())) {
                            throw new IllegalStateException("reading an"
                                    + " executeInDb result binding ('"
                                    + let.name() + "') is not supported");
                        }
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
                        inlined = new com.legend.resolver.StoreResolver(
                                env.ctx(), specs)
                                .withLetBindings(env.queryLets())
                                .resolve(inlined, env.runtimeFqn());
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
            if (bare instanceof com.legend.compiler.spec.typed.TypedUserCall call
                    && containsEffect(call, specs, effectMemo)) {
                result = executeCallStatement(call, letPrefix, specs, env, frames);
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
            java.util.List<TypedSpec> body =
                    stmtInliner.inlineBody(single);                       // Phase G½
            env.queryLets().putAll(stmtInliner.queryLets());
            // toSQLString dispatches PRE-H: its query lambda resolves
            // against the EXPLICIT mapping argument, never the ambient
            // runtime's (audit 19d B3 — the K-native replacing the
            // harness's name-interception)
            TypedSpec preRoot = body.get(body.size() - 1);
            if (preRoot instanceof com.legend.compiler.spec.typed.TypedLet pl) {
                preRoot = pl.value();
            }
            while (preRoot instanceof com.legend.compiler.spec.typed.TypedFrom pf) {
                preRoot = pf.source();
            }
            preRoot = foldPairProjection(preRoot);
            if (preRoot instanceof com.legend.compiler.spec.typed.TypedNativeCall tsc
                    && com.legend.compiler.element.type.PlatformTypes.TO_SQL_STRING
                            .equals(tsc.callee().qualifiedName())) {
                result = toSqlString(tsc, specs, env);
                continue;
            }
            // planToString(executionPlan(q, m, rt, ...), ext) — the plan
            // surface (#47): LITERAL engine plan text (toSQLString
            // doctrine) over the same engine-style SQL pipeline
            if (preRoot instanceof com.legend.compiler.spec.typed.TypedNativeCall pln
                    && com.legend.compiler.element.type.PlatformTypes
                            .PLAN_TO_STRING.equals(pln.callee().qualifiedName())) {
                result = planToString(pln, specs, env);
                continue;
            }
            // replace(...) over the plan TEXT (the datetime helpers'
            // ->replace('\n',' ') presentation) — the text is an
            // orchestration artifact; the string op applies host-side
            if (preRoot instanceof com.legend.compiler.spec.typed.TypedNativeCall rp
                    && rp.callee().qualifiedName().equals(
                            "meta::pure::functions::string::replace")
                    && rp.args().size() == 3
                    && rp.args().get(0)
                            instanceof com.legend.compiler.spec.typed.TypedNativeCall rpi
                    && com.legend.compiler.element.type.PlatformTypes
                            .PLAN_TO_STRING.equals(rpi.callee().qualifiedName())
                    && rp.args().get(1)
                            instanceof com.legend.compiler.spec.typed.TypedCString rf
                    && rp.args().get(2)
                            instanceof com.legend.compiler.spec.typed.TypedCString rt2) {
                ExecutionResult r1 = planToString(rpi, specs, env);
                result = new ExecutionResult.Scalar(
                        String.valueOf(((ExecutionResult.Scalar)
                                java.util.Objects.requireNonNull(r1)).value())
                                .replace(rf.value(), rt2.value()),
                        com.legend.compiler.element.type.Type
                                .Primitive.STRING);
                continue;
            }
            // planToStringWithoutFormatting = planToString minus newlines
            // and spaces (executionPlan_print.pure:27)
            if (preRoot instanceof com.legend.compiler.spec.typed.TypedNativeCall pwf
                    && com.legend.compiler.element.type.PlatformTypes
                            .PLAN_TO_STRING_WITHOUT_FORMATTING
                            .equals(pwf.callee().qualifiedName())) {
                ExecutionResult r0 = planToString(pwf, specs, env);
                result = new ExecutionResult.Scalar(
                        String.valueOf(((ExecutionResult.Scalar)
                                java.util.Objects.requireNonNull(r0)).value())
                                .replace("\n", "").replace(" ", ""),
                        com.legend.compiler.element.type.Type
                                .Primitive.STRING);
                continue;
            }
            // $plan.processingTemplateFunctions — the ExecutionPlan class
            // property (executionPlan.pure:67): every relational node
            // carries relationalPlanSupportFunctions(connection), deduped
            // plan-wide (executionPlan_generation.pure:215)
            if (preRoot instanceof com.legend.compiler.spec.typed
                            .TypedPropertyAccess ppa
                    && ppa.property().equals("processingTemplateFunctions")
                    && foldPairProjection(ppa.source())
                            instanceof com.legend.compiler.spec.typed
                                    .TypedNativeCall pep
                    && com.legend.compiler.element.type.PlatformTypes
                            .EXECUTION_PLAN.equals(
                                    pep.callee().qualifiedName())) {
                java.util.List<Object> supportFns = new java.util.ArrayList<>(
                        com.legend.plan.PlanSupportFunctions
                                .relationalPlanSupportFunctions(
                                        pep.args().size() > 2
                                                ? timeZoneOf(
                                                        pep.args().get(2))
                                                : null));
                // enum-typed plan parameters ADD their dynamic enum-map
                // freemarker function (deduped plan-wide)
                if (pep.args().get(0) instanceof com.legend.compiler.spec
                                .typed.TypedLambda plam
                        && pep.args().get(1) instanceof com.legend.compiler
                                .spec.typed.TypedPackageableRef pmr
                        && plam.info().type() instanceof com.legend.compiler
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
                result = new ExecutionResult.Collection(supportFns,
                        com.legend.compiler.element.type.Type
                                .Primitive.STRING);
                continue;
            }
            // PLAN-HANDLE WALKS ($plan.rootExecutionNode->allNodes(...)
            // ->filter(instanceOf(X))->cast(@X).sqlQuery — the engine's
            // own plan API): evaluate over the PLAN NODE MODEL
            Object walked = planWalk(preRoot, specs, env);
            if (walked != null) {
                result = walkResult(walked);
                continue;
            }
            if (System.getenv("LL_TMP_DEBUG") != null
                    && preRoot.toString().contains("rootExecutionNode")) {
                System.err.println("[walk-miss] " + preRoot.getClass()
                        .getSimpleName() + ": " + preRoot);
            }
            // execute() in RESULT position: the eager frame run IS the value
            // (the Result envelope is typing-only — the chain's rows are what
            // a reader observes).
            if (preRoot instanceof com.legend.compiler.spec.typed.TypedNativeCall xc
                    && com.legend.compiler.element.type.PlatformTypes
                            .isExecuteFqn(xc.callee().qualifiedName())) {
                result = buildFrame(xc, letPrefix, true, specs, env).result();
                continue;
            }
            body = new com.legend.resolver.StoreResolver(env.ctx(), specs)
                    .withLetBindings(env.queryLets())
                    .resolve(body, env.runtimeFqn());                     // Phase H
            if (env.addDriverTablePk()) {
                // the engine's addDriverTablePkForProject option (#45):
                // projections gain driver-table PK columns; non-projection
                // statements pass through unchanged
                body = com.legend.validation.DriverPkAppend.apply(
                        body, env.ctx());
            }
            result = executeTyped(body, env);
        }
        return result;
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
        String db = typedEnumTail(call.args().get(2));
        com.legend.sql.dialect.EngineStyleH2 renderer = switch (db) {
            // Composite renders the engine-default text — the goldens pin
            // it identical to H2's; a divergent golden fails honestly
            case "H2", "Composite" -> new com.legend.sql.dialect.EngineStyleH2();
            case "DB2" -> new com.legend.sql.dialect.EngineStyleDB2();
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
    private record EngineSql(com.legend.sql.SqlQuery plan, String sql,
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
    private static EngineSql engineSql(java.util.List<TypedSpec> raw,
            String mappingFqn, com.legend.compiler.spec.SpecCompiler specs,
            ExecEnv env,
            com.legend.sql.dialect.EngineStyleH2 renderer,
            java.util.Map<String, com.legend.sql.SqlExpr.PlanParam>
                    planParams,
            java.util.function.UnaryOperator<String> tableRenames) {
        java.util.List<TypedSpec> body =
                new com.legend.compiler.spec.UserCallInliner(specs)
                        .inlineBody(raw);
        body = new com.legend.resolver.StoreResolver(env.ctx(), specs)
                .resolve(body, env.runtimeFqn(), mappingFqn);
        body = com.legend.resolver.RelationalRootForm.apply(
                body, env.ctx(), mappingFqn);
        com.legend.lowering.Lowerer lw = new com.legend.lowering.Lowerer(
                t -> com.legend.compiler.element.ClassLayouts.layoutOf(env.ctx(), t),
                f -> env.ctx().findClass(f).isPresent());
        planParams.values().forEach(lw::bindPlanParam);
        // ENGINE-TEXT lowering: wire coercions (castAsDeclared) read bare
        com.legend.sql.SqlQuery plan;
        try (var ignored = com.legend.lowering.EngineTextBoundary.enter()) {
            plan = lw.lower(body);
        }
        // engine plans keep enum columns RAW (host-side decode) — the
        // plan-text form of enum-mapped columns/parameters
        if (plan instanceof com.legend.sql.SqlSelect sel
                && body.get(body.size() - 1).info().type()
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
        return new EngineSql(plan, renderer.render(plan), body);
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
            com.legend.compiler.spec.SpecCompiler specs, ExecEnv env)
            throws java.sql.SQLException {
        boolean rootSetup = bare
                instanceof com.legend.compiler.spec.typed.TypedNativeCall rnc
                && com.legend.compiler.element.type.PlatformTypes
                        .EXECUTE_IN_DB.equals(rnc.callee().qualifiedName());
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
        if (!com.legend.exec.HostEval.wantsHostEval(bare, hostLets)) {
            return null;
        }
        return com.legend.exec.HostEval.evalToResult(
                bare, env.ctx(), specs, hostLets);
    }

    /** {@code planToString(executionPlan(func, MAPPING, runtime, ...),
     * ext)}: the SINGLE-RELATIONAL literal plan text (#47 pilot —
     * com.legend.plan.PlanText owns the format). */
    private static @com.legend.Nullable ExecutionResult planToString(
            com.legend.compiler.spec.typed.TypedNativeCall call,
            com.legend.compiler.spec.SpecCompiler specs, ExecEnv env) {
        if (!(call.args().get(0)
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
                throw new com.legend.error.NotImplementedException(
                        "executionPlan mapping argument must be a reference"
                        + " (or the query must carry ->from), got "
                        + ep.args().get(1).getClass().getSimpleName());
            }
        }
        boolean quote = hasRuntimeArg
                && quoteIdentifiersOf(ep.args().get(2));
        String tz = hasRuntimeArg
                ? timeZoneOf(ep.args().get(2)) : null;
        String connName = hasRuntimeArg
                ? connectionNameOf(ep.args().get(2))
                : "TestDatabaseConnection(type = \"H2\")";
        String dbType = hasRuntimeArg
                ? databaseTypeOf(ep.args().get(2)) : "H2";
        if (!lam.parameters().isEmpty() || lam.body().size() > 1) {
            return sequencePlan(lam, mappingFqn, specs, env, quote, tz,
                    connName, dbType);
        }
        String rootClass = rootGetAllClass(lam.body());
        if (rootClass == null) {
            throw new com.legend.error.NotImplementedException(
                    "planToString: no getAll root (multi-node plans"
                    + " pending)");
        }
        EngineSql es = engineSql(lam.body(), mappingFqn, specs, env,
                planDialect(dbType, quote, tz), java.util.Map.of(),
                java.util.function.UnaryOperator.identity());
        return new ExecutionResult.Scalar(
                com.legend.plan.PlanText.single(env.ctx(), rootClass,
                        mappingFqn, es.plan(), es.sql(),
                        // PRE-resolution body: the TDS-vs-Class shape and
                        // the documentation channel live in the G output
                        // (post-H everything is a relation)
                        lam.body(), connName),
                com.legend.compiler.element.type.Type.Primitive.STRING);
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
        var fnType = (com.legend.compiler.element.type.Type.FunctionType)
                lam.info().type();
        java.util.LinkedHashMap<String, com.legend.sql.SqlExpr.PlanParam>
                params = new java.util.LinkedHashMap<>();
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
            children.add(allocationNode(let, mappingFqn, specs, env,
                    params, quote, timeZone, dbType));
            params.put(let.name(), new com.legend.sql.SqlExpr.PlanParam(
                    let.name(), com.legend.lowering.PlanParams.kindOf(
                            let.info().type())));
        }
        TypedSpec term = lam.body().get(lam.body().size() - 1);
        String rootClass = rootGetAllClass(java.util.List.of(term));
        if (rootClass == null) {
            throw new com.legend.error.NotImplementedException(
                    "plan: sequence terminal without a getAll root");
        }
        EngineSql es = engineSql(java.util.List.of(term), mappingFqn, specs,
                env, planDialect(dbType, quote, timeZone), params,
                java.util.function.UnaryOperator.identity());
        children.add(com.legend.plan.PlanText.single(env.ctx(), rootClass,
                mappingFqn, es.plan(), es.sql(), java.util.List.of(term),
                connName));
        String[] impl = com.legend.lineage.ScanRelations.rootImpl(
                env.ctx(), mappingFqn, rootClass);
        return new ExecutionResult.Scalar(
                com.legend.plan.PlanText.sequence(
                        com.legend.plan.PlanText.typeBlock(env.ctx(),
                                rootClass, impl, es.plan(),
                                java.util.List.of(term), mappingFqn),
                        children),
                com.legend.compiler.element.type.Type.Primitive.STRING);
    }

    /** An Allocation child for one plan let: LITERAL values print as
     * Constant nodes, scalar query values as SCALAR-projection
     * Relational nodes (bare-typed, alias-less select), and CLASS query
     * values as full Class-envelope Relational nodes — the engine's
     * three Allocation value forms. */
    private static @com.legend.Nullable String allocationNode(
            com.legend.compiler.spec.typed.TypedLet let, String mappingFqn,
            com.legend.compiler.spec.SpecCompiler specs, ExecEnv env,
            java.util.Map<String, com.legend.sql.SqlExpr.PlanParam> params,
            boolean quote, @com.legend.Nullable String timeZone, @com.legend.Nullable String dbType) {
        String literal = switch (let.value()) {
            case com.legend.compiler.spec.typed.TypedCString cs -> cs.value();
            case com.legend.compiler.spec.typed.TypedCInteger ci ->
                    String.valueOf(ci.value());
            case com.legend.compiler.spec.typed.TypedCFloat cf ->
                    String.valueOf(cf.value());
            case com.legend.compiler.spec.typed.TypedCBoolean cb ->
                    String.valueOf(cb.value());
            case com.legend.compiler.spec.typed.TypedCDate cd ->
                    cd.value().toEngineString();
            default -> null;
        };
        if (literal != null) {
            String typeName = com.legend.plan.PlanText
                    .pureTypeName(let.info().type());
            String size = sizeRange(let.info().multiplicity());
            return com.legend.plan.PlanText.allocation(let.name(),
                    com.legend.plan.PlanText.scalarTypeBlock(typeName, size),
                    com.legend.plan.PlanText.constant(typeName, literal));
        }
        String rootClass = rootGetAllClass(java.util.List.of(let.value()));
        if (rootClass == null) {
            throw new com.legend.error.NotImplementedException(
                    "plan: Allocation value without a getAll root");
        }
        EngineSql es = engineSql(java.util.List.of(let.value()),
                mappingFqn, specs, env,
                planDialect(dbType, quote, timeZone), params,
                java.util.function.UnaryOperator.identity());
        String[] impl = com.legend.lineage.ScanRelations.rootImpl(
                env.ctx(), mappingFqn, rootClass);
        if (let.info().type()
                instanceof com.legend.compiler.element.type.Type.ClassType) {
            // class-valued allocation: the full Class-envelope node, and
            // the Allocation's own type block is the impls form
            String inner = com.legend.plan.PlanText.single(env.ctx(),
                    rootClass, mappingFqn, es.plan(), es.sql(),
                    java.util.List.of(let.value()));
            return com.legend.plan.PlanText.allocation(let.name(),
                    com.legend.plan.PlanText.typeBlock(env.ctx(), rootClass,
                            impl, es.plan(), java.util.List.of(let.value())),
                    inner);
        }
        String typeName = com.legend.plan.PlanText
                .pureTypeName(let.info().type());
        String size = sizeRange(let.info().multiplicity());
        if (!(es.plan() instanceof com.legend.sql.SqlSelect sel)) {
            throw new com.legend.error.NotImplementedException(
                    "plan: Allocation value lowers to a non-select");
        }
        com.legend.sql.SqlSelect bareSel = new com.legend.sql.SqlSelect(
                sel.projections().stream().map(p ->
                        new com.legend.sql.SqlSelect.Projection(
                                p.expr(), null)).toList(),
                sel.distinct(), sel.from(), sel.where(), sel.groupBy(),
                sel.having(), sel.qualify(), sel.orderBy(), sel.limit(),
                sel.offset(), sel.outputs());
        var renderer = planDialect(dbType, quote, timeZone);
        String bareSql = renderer.render(bareSel);
        String inner = com.legend.plan.PlanText.scalarRelational(env.ctx(),
                impl[2], sel, typeName, size, bareSql,
                renderer::renderedAlias);
        return com.legend.plan.PlanText.allocation(let.name(),
                com.legend.plan.PlanText.scalarTypeBlock(typeName, size),
                inner);
    }

    private static @com.legend.Nullable String multBracket(
            com.legend.compiler.element.type.Multiplicity m) {
        return "[" + sizeRange(m) + "]";
    }

    private static @com.legend.Nullable String sizeRange(
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

    /** The engine connection's quoteIdentifiers flag, read off the
     * executionPlan call's RUNTIME argument (a Runtime instance literal
     * carrying a TestDatabaseConnection(quoteIdentifiers=true)). */
    private static boolean quoteIdentifiersOf(TypedSpec runtimeArg) {
        java.util.ArrayDeque<TypedSpec> work = new java.util.ArrayDeque<>();
        work.add(runtimeArg);
        while (!work.isEmpty()) {
            TypedSpec t = work.poll();
            if (t instanceof com.legend.compiler.spec.typed.TypedNewInstance ni
                    && ni.properties().get("quoteIdentifiers")
                            instanceof com.legend.compiler.spec.typed
                                    .TypedCBoolean b2) {
                return b2.value();
            }
            work.addAll(t.children());
        }
        return false;
    }

    /** The engine connection's timeZone, read off the RUNTIME argument
     * (an inline DatabaseConnection(timeZone='US/Arizona')). Null when
     * absent — the default-zone connection. */
    private static @com.legend.Nullable String timeZoneOf(TypedSpec runtimeArg) {
        java.util.ArrayDeque<TypedSpec> work = new java.util.ArrayDeque<>();
        work.add(runtimeArg);
        while (!work.isEmpty()) {
            TypedSpec t = work.poll();
            if (t instanceof com.legend.compiler.spec.typed.TypedNewInstance ni
                    && ni.properties().get("timeZone")
                            instanceof com.legend.compiler.spec.typed
                                    .TypedCString tzs) {
                return tzs.value();
            }
            work.addAll(t.children());
        }
        return null;
    }

    /** The runtime connection's plan spelling — the instance's own CLASS
     * simple name (exact-FQN dispatch) with its declared DatabaseType
     * ({@code DatabaseConnection(type = "DB2")}). */
    private static @com.legend.Nullable String connectionNameOf(TypedSpec runtimeArg) {
        var ni = connectionInstanceOf(runtimeArg);
        if (ni == null) {
            return "TestDatabaseConnection(type = \"H2\")";
        }
        String simple = switch (ni.classFqn()) {
            case "meta::external::store::relational::runtime"
                    + "::DatabaseConnection" -> "DatabaseConnection";
            case "meta::external::store::relational::runtime"
                    + "::RelationalDatabaseConnection" ->
                    "RelationalDatabaseConnection";
            default -> "TestDatabaseConnection";
        };
        return simple + "(type = \"" + dbTypeOf(ni) + "\")";
    }

    /** The FIRST connection instance under {@code runtimeArg} (exact-FQN
     * dispatch), or null. */
    private static com.legend.compiler.spec.typed.@com.legend.Nullable TypedNewInstance
            connectionInstanceOf(TypedSpec runtimeArg) {
        java.util.ArrayDeque<TypedSpec> work = new java.util.ArrayDeque<>();
        work.add(runtimeArg);
        while (!work.isEmpty()) {
            TypedSpec t = work.poll();
            if (t instanceof com.legend.compiler.spec.typed
                            .TypedNewInstance ni
                    && ("meta::external::store::relational::runtime::DatabaseConnection"
                                    .equals(ni.classFqn())
                        || "meta::external::store::relational::runtime::TestDatabaseConnection"
                                    .equals(ni.classFqn())
                        || "meta::external::store::relational::runtime::RelationalDatabaseConnection"
                                    .equals(ni.classFqn()))) {
                return ni;
            }
            work.addAll(t.children());
        }
        return null;
    }

    private static @com.legend.Nullable String dbTypeOf(
            com.legend.compiler.spec.typed.TypedNewInstance ni) {
        return ni.properties().get("type") instanceof
                com.legend.compiler.spec.typed.TypedEnumValue ev
                ? String.valueOf(ev.value()) : "H2";
    }

    /** The runtime connection's DatabaseType name ("H2" when absent). */
    private static @com.legend.Nullable String databaseTypeOf(TypedSpec runtimeArg) {
        var ni = connectionInstanceOf(runtimeArg);
        return ni == null ? "H2" : dbTypeOf(ni);
    }

    /** The engine-style PLAN renderer for a connection DatabaseType —
     * the plan goldens pin Composite to the DB2-family spelling
     * (paren-wrapped conjunctions, quoted boolean placeholders). */
    private static com.legend.sql.dialect.EngineStyleH2 planDialect(
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
    private static @com.legend.Nullable Object planWalk(TypedSpec n,
            com.legend.compiler.spec.SpecCompiler specs, ExecEnv env) {
        if (n instanceof com.legend.compiler.spec.typed.TypedNativeCall ep
                && com.legend.compiler.element.type.PlatformTypes
                        .EXECUTION_PLAN.equals(ep.callee().qualifiedName())) {
            return planModel(ep, specs, env);
        }
        if (n instanceof com.legend.compiler.spec.typed.TypedPackageableRef pr9) {
            // a Database ELEMENT in value position: the store-metamodel
            // walk surface (typeInference family)
            Object dbh = com.legend.exec.MetamodelWalk.database(env.ctx(),
                    pr9.fullPath());
            return dbh != null ? dbh
                    : com.legend.exec.MetamodelWalk.mapping(env.ctx(),
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
            if (System.getenv("LL_TMP_DEBUG") != null) {
                System.err.println("[walk] copy of " + cp.classFqn()
                        + " does not walk: src="
                        + (src == null ? "null" : src.getClass()
                                .getSimpleName())
                        + " overrides=" + cp.overrides().keySet());
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
            return walkMapOver(planWalk(tm.source(), specs, env), tml);
        }
        if (n instanceof com.legend.compiler.spec.typed.TypedNativeCall c
                && !c.args().isEmpty()) {
            String fn = c.callee().qualifiedName();
            String simple = fn.substring(fn.lastIndexOf(':') + 1);
            Object recv = planWalk(c.args().get(0), specs, env);
            if (recv == null) {
                return null;
            }
            switch (simple) {
                case "allNodes" -> {
                    if (recv instanceof com.legend.plan.PlanNode pn) {
                        return new java.util.ArrayList<Object>(pn.allNodes());
                    }
                }
                case "filter" -> {
                    if (recv instanceof java.util.List<?> l
                            && c.args().get(1)
                                    instanceof com.legend.compiler.spec.typed
                                            .TypedLambda lam2) {
                        return walkFilter(l, lam2);
                    }
                }
                case "cast", "toOne", "toOneMany" -> {
                    return recv;
                }
                case "at" -> {
                    if (recv instanceof java.util.List<?> l
                            && c.args().get(1)
                                    instanceof com.legend.compiler.spec.typed
                                            .TypedCInteger ix) {
                        return l.get((int) (long) ix.value());
                    }
                }
                case "first" -> {
                    if (recv instanceof java.util.List<?> l) {
                        return l.isEmpty() ? null : l.get(0);
                    }
                }
                case "schema" -> {
                    if (c.args().size() == 2 && c.args().get(1)
                            instanceof com.legend.compiler.spec.typed
                                    .TypedCString sn9) {
                        return com.legend.exec.MetamodelWalk.schema(recv,
                                sn9.value());
                    }
                }
                case "table" -> {
                    if (c.args().size() == 2 && c.args().get(1)
                            instanceof com.legend.compiler.spec.typed
                                    .TypedCString tn9) {
                        return com.legend.exec.MetamodelWalk.table(recv,
                                tn9.value());
                    }
                }
                case "convertElement" -> {
                    return com.legend.exec.MetamodelWalk
                            .convertElement(recv);
                }
                case "convertSelectSqlQuery" -> {
                    Object body = com.legend.exec.MetamodelWalk
                            .convertElement(recv);
                    return body == null ? null
                            : com.legend.exec.MetamodelWalk.nodeOf("Query",
                                    new java.util.TreeMap<>(java.util.Map
                                            .of("queryBody", body)));
                }
                case "view" -> {
                    if (c.args().size() == 2 && c.args().get(1)
                            instanceof com.legend.compiler.spec.typed
                                    .TypedCString vn) {
                        return com.legend.exec.MetamodelWalk.view(recv,
                                vn.value());
                    }
                }
                case "map" -> {
                    if (recv instanceof java.util.List<?> l
                            && c.args().get(1) instanceof
                                    com.legend.compiler.spec.typed
                                            .TypedLambda ml) {
                        java.util.List<Object> out =
                                new java.util.ArrayList<>();
                        for (Object e : l) {
                            Object v = walkMapBody(e, ml);
                            if (v != null) {
                                out.add(v);
                            }
                        }
                        return out;
                    }
                }
                case "rootClassMappingByClass" -> {
                    if (c.args().size() == 2 && c.args().get(1) instanceof
                            com.legend.compiler.spec.typed
                                    .TypedPackageableRef cref) {
                        return com.legend.exec.MetamodelWalk
                                .rootClassMappingByClass(recv,
                                        cref.fullPath());
                    }
                }
                case "classMappingById", "superMapping",
                        "allSuperSetImplementations", "mainTable",
                        "resolvePrimaryKey" -> {
                    return mappingNav(simple, recv, c, specs, env);
                }
                case "propertyMappingsByPropertyName" -> {
                    if (c.args().size() == 2 && c.args().get(1) instanceof
                            com.legend.compiler.spec.typed
                                    .TypedCString pn) {
                        return com.legend.exec.MetamodelWalk
                                .propertyMappingsByName(recv, pn.value());
                    }
                }
                case "inferRelationalType" -> {
                    return com.legend.exec.MetamodelWalk.infer(recv);
                }
                case "dataTypeToSqlText" -> {
                    return com.legend.exec.MetamodelWalk.sqlText(recv);
                }
                default -> {
                    return null;
                }
            }
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
                if (System.getenv("LL_TMP_DEBUG") != null) {
                    System.err.println("[walk] " + simple + "." + e.getKey()
                            + " does not walk: " + e.getValue().getClass()
                                    .getSimpleName());
                }
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

    /** NARROW map-lambda body: one native call over the parameter
     * ({@code x|$x->view('name')}) — evaluated per element; null on any
     * other shape (the walk falls through to its walls). */
    private static @com.legend.Nullable Object walkMapBody(Object e,
            com.legend.compiler.spec.typed.TypedLambda ml) {
        if (ml.body().size() != 1 || ml.parameters().isEmpty()
                || !(ml.body().get(0) instanceof
                        com.legend.compiler.spec.typed.TypedNativeCall mb)
                || mb.args().isEmpty()
                || !(mb.args().get(0) instanceof
                        com.legend.compiler.spec.typed.TypedVariable mv)
                || !mv.name().equals(ml.parameters().get(0))) {
            return null;
        }
        String mfn = mb.callee().qualifiedName();
        String msimple = mfn.substring(mfn.lastIndexOf(':') + 1);
        return switch (msimple) {
            case "view" -> mb.args().size() == 2
                    && mb.args().get(1) instanceof
                            com.legend.compiler.spec.typed.TypedCString mvn
                    ? com.legend.exec.MetamodelWalk.view(e, mvn.value())
                    : null;
            case "mainTable" -> com.legend.exec.MetamodelWalk.mainTable(e);
            case "resolvePrimaryKey" ->
                    com.legend.exec.MetamodelWalk.resolvePrimaryKey(e);
            default -> null;
        };
    }

    /** {@code ->map(x|...)} over walked handles; a single IS a [1]
     * collection (pure semantics), so classMappingById's [0..1] result
     * maps like the metamodel families' lists. */
    private static @com.legend.Nullable Object walkMapOver(@com.legend.Nullable Object recvM,
            com.legend.compiler.spec.typed.TypedLambda tml) {
        if (recvM != null && !(recvM instanceof java.util.List)) {
            recvM = java.util.List.of(recvM);
        }
        if (recvM instanceof java.util.List<?> lm) {
            java.util.List<Object> outM = new java.util.ArrayList<>();
            for (Object e : lm) {
                Object v = walkMapBody(e, tml);
                if (v != null) {
                    outM.add(v);
                }
            }
            return outM;
        }
        return null;
    }

    /** The extends-chain mapping-metamodel natives (classMappingById /
     * superMapping / allSuperSetImplementations / mainTable /
     * resolvePrimaryKey) — recv-dispatched to MetamodelWalk. */
    private static @com.legend.Nullable Object mappingNav(String simple, Object recv,
            com.legend.compiler.spec.typed.TypedNativeCall c,
            com.legend.compiler.spec.SpecCompiler specs, ExecEnv env) {
        return switch (simple) {
            case "classMappingById" -> c.args().size() == 2
                    && c.args().get(1) instanceof
                            com.legend.compiler.spec.typed.TypedCString mid
                    ? com.legend.exec.MetamodelWalk.classMappingById(recv,
                            mid.value())
                    : null;
            case "superMapping" ->
                    com.legend.exec.MetamodelWalk.superMapping(recv);
            case "allSuperSetImplementations" -> c.args().size() == 2
                    ? com.legend.exec.MetamodelWalk
                            .allSuperSetImplementations(recv,
                                    planWalk(c.args().get(1), specs, env))
                    : null;
            case "mainTable" ->
                    com.legend.exec.MetamodelWalk.mainTable(recv);
            case "resolvePrimaryKey" ->
                    com.legend.exec.MetamodelWalk.resolvePrimaryKey(recv);
            default -> null;
        };
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
                case "functionParameters" ->
                        new java.util.ArrayList<Object>(
                                pn.functionParameters());
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
    private static @com.legend.Nullable Object walkFilter(java.util.List<?> l,
            com.legend.compiler.spec.typed.TypedLambda lam) {
        TypedSpec body = lam.body().get(lam.body().size() - 1);
        if (body instanceof com.legend.compiler.spec.typed.TypedNativeCall io
                && io.callee().qualifiedName().endsWith("instanceOf")
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
                && eq.callee().qualifiedName().endsWith("equal")
                && eq.args().size() == 2
                && eq.args().get(0)
                        instanceof com.legend.compiler.spec.typed
                                .TypedPropertyAccess pa2
                && eq.args().get(1)
                        instanceof com.legend.compiler.spec.typed
                                .TypedCString lit) {
            // GENERIC property==literal predicate: plan Params (name)
            // and metamodel handles (columnName) share the arm
            java.util.List<Object> out = new java.util.ArrayList<>();
            for (Object e : l) {
                Object v = e instanceof com.legend.plan.PlanNode.Param pp
                        && pa2.property().equals("name")
                        ? pp.name()
                        : com.legend.exec.MetamodelWalk.prop(e,
                                pa2.property());
                if (lit.value().equals(v)) {
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

    private static ExecutionResult walkResult(Object w) {
        if (w instanceof java.util.List<?> l) {
            java.util.List<Object> vals = new java.util.ArrayList<>(l);
            return new ExecutionResult.Collection(vals,
                    vals.stream().allMatch(x -> x instanceof Boolean)
                            ? com.legend.compiler.element.type.Type
                                    .Primitive.BOOLEAN
                            : com.legend.compiler.element.type.Type
                                    .Primitive.STRING);
        }
        if (w instanceof Boolean b3) {
            return new ExecutionResult.Scalar(b3,
                    com.legend.compiler.element.type.Type.Primitive.BOOLEAN);
        }
        return new ExecutionResult.Scalar(String.valueOf(w),
                com.legend.compiler.element.type.Type.Primitive.STRING);
    }

    /** The PLAN NODE MODEL for an executionPlan call — same shapes the
     * text printer spells (Sequence / FunctionParametersValidation /
     * RelationalInstantiation / SQLExecution). */
    private static com.legend.plan.PlanNode planModel(
            com.legend.compiler.spec.typed.TypedNativeCall ep,
            com.legend.compiler.spec.SpecCompiler specs, ExecEnv env) {
        if (!(ep.args().get(0)
                instanceof com.legend.compiler.spec.typed.TypedLambda lam)
                || !(ep.args().get(1) instanceof
                        com.legend.compiler.spec.typed.TypedPackageableRef pr)) {
            throw new com.legend.error.NotImplementedException(
                    "plan walk: executionPlan argument shapes pending");
        }
        boolean quote = ep.args().size() > 2
                && quoteIdentifiersOf(ep.args().get(2));
        String tz = ep.args().size() > 2
                ? timeZoneOf(ep.args().get(2)) : null;
        var fnType = (com.legend.compiler.element.type.Type.FunctionType)
                lam.info().type();
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
            // supportsStream: TRUE for collection-multiplicity params
            fps.add(new com.legend.plan.PlanNode.Param(
                    lam.parameters().get(i), many));
        }
        TypedSpec term = lam.body().get(lam.body().size() - 1);
        // the runtime argument may carry relationalMapperPostProcessor
        // renames — extracted structurally, applied over the lowered IR
        java.util.function.UnaryOperator<String> mapperRenames =
                ep.args().size() > 2
                ? com.legend.plan.RelationalMapperRenames.extract(
                        ep.args().get(2), specs, env.queryLets(), env.ctx())
                : java.util.function.UnaryOperator.identity();
        EngineSql es = engineSql(java.util.List.of(term), pr.fullPath(),
                specs, env, new com.legend.sql.dialect.EngineStyleH2(quote,
                        tz), params, mapperRenames);
        com.legend.plan.PlanNode sqlNode = new com.legend.plan.PlanNode(
                "SQLExecutionNode", java.util.List.of(), es.sql(),
                java.util.List.of());
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

    private static @com.legend.Nullable String rootGetAllClass(java.util.List<TypedSpec> body) {
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
            @com.legend.Nullable ExecutionResult result) {
    }

    /** Envelope-read recognizers — generic natives identified by EXACT FQN
     * (never suffix matching). */
    private static final String AT_FQN = "meta::pure::functions::collection::at";
    private static final String FIRST_FQN = "meta::pure::functions::collection::first";
    private static final String TO_ONE_FQN =
            "meta::pure::functions::multiplicity::toOne";
    private static final java.util.Set<String> SIZE_FQNS = java.util.Set.of(
            "meta::pure::functions::relation::size",
            "meta::pure::functions::collection::size");

    /**
     * Build the frame for one {@code execute(f, mapping, runtime, ext)}
     * call: fold the query lambda's (and the caller's) lets, attach the
     * EXPLICIT mapping argument as the chain's execution context, and — for
     * a let binding — run it eagerly through the pipeline.
     */
    private static ExecFrame buildFrame(
            com.legend.compiler.spec.typed.TypedNativeCall ec,
            java.util.List<TypedSpec> letPrefix, boolean eager,
            SpecCompiler specs, ExecEnv env) throws java.sql.SQLException {
        TypedSpec q = letBound(ec.args().get(0), letPrefix);
        // a LAMBDA-BUILDING user call in query position (corpus
        // buildQuery(value) returning FunctionDefinition<{->Person[*]}>):
        // β-inline it — the body's single expression IS the lambda literal
        if (q instanceof com.legend.compiler.spec.typed.TypedUserCall) {
            q = new com.legend.compiler.spec.UserCallInliner(specs)
                    .inlineBody(java.util.List.of(q)).get(0);
        }
        // preval(query, extensions) / withFeatureFlags(query, flags):
        // plan-time wrappers, IDENTITY for row semantics — read through
        // to the wrapped query lambda.
        while (q instanceof com.legend.compiler.spec.typed.TypedNativeCall pv
                && ("meta::pure::router::preeval::preval"
                        .equals(pv.callee().qualifiedName())
                    || "meta::pure::executionPlan::featureFlag::withFeatureFlags"
                        .equals(pv.callee().qualifiedName()))) {
            q = letBound(pv.args().get(0), letPrefix);
        }
        // concatenateTemporalTdsQueries(lfs): the real body folds the
        // queries into concatenate SFEs (reflection metamodel) — the SAME
        // semantics BY EMISSION: fold the lambdas' result expressions
        // into a TypedConcatenate chain under one zero-arg lambda.
        if (q instanceof com.legend.compiler.spec.typed.TypedNativeCall cq
                && "meta::relational::milestoning::concatenateTemporalTdsQueries"
                        .equals(cq.callee().qualifiedName())) {
            TypedSpec lfsArg = letBound(cq.args().get(0), letPrefix);
            // evaluateAndDeactivate may wrap the WHOLE collection
            // ([...]->evaluateAndDeactivate()) — identity, peel first
            while (lfsArg instanceof com.legend.compiler.spec.typed
                    .TypedNativeCall ow
                    && ow.args().size() == 1
                    && "meta::pure::functions::meta::evaluateAndDeactivate"
                            .equals(ow.callee().qualifiedName())) {
                lfsArg = letBound(ow.args().get(0), letPrefix);
            }
            // MAP-BUILT collections ($bds->map(bd|{|...}->eAD())): β-expand
            // the map over the literal elements — one TypedEval per element,
            // reduced by the inliner (the full β-substitution engine)
            if (lfsArg instanceof com.legend.compiler.spec.typed
                            .TypedMap mapC
                    && letBound(mapC.mapper(), letPrefix)
                            instanceof com.legend.compiler.spec.typed
                                    .TypedLambda mapLam
                    && mapLam.parameters().size() == 1
                    && letBound(mapC.source(), letPrefix)
                            instanceof com.legend.compiler.spec.typed
                                    .TypedCollection dc) {
                java.util.List<TypedSpec> expanded =
                        new java.util.ArrayList<>(dc.elements().size());
                for (TypedSpec d : dc.elements()) {
                    expanded.add(new com.legend.compiler.spec.UserCallInliner(
                            specs).inlineBody(java.util.List.of(
                                    new com.legend.compiler.spec.typed.TypedEval(
                                            mapLam, java.util.List.of(d),
                                            mapLam.body().get(
                                                    mapLam.body().size() - 1)
                                                    .info())))
                            .get(0));
                }
                lfsArg = new com.legend.compiler.spec.typed.TypedCollection(
                        expanded, lfsArg.info());
            }
            java.util.List<TypedSpec> els =
                    lfsArg instanceof com.legend.compiler.spec.typed
                            .TypedCollection tc
                    ? tc.elements() : java.util.List.of(lfsArg);
            java.util.List<TypedSpec> queries = new java.util.ArrayList<>();
            for (TypedSpec e : els) {
                TypedSpec le = letBound(e, letPrefix);
                while (le instanceof com.legend.compiler.spec.typed
                        .TypedNativeCall w
                        && w.args().size() == 1
                        && "meta::pure::functions::meta::evaluateAndDeactivate"
                                .equals(w.callee().qualifiedName())) {
                    le = letBound(w.args().get(0), letPrefix);
                }
                if (!(le instanceof com.legend.compiler.spec.typed
                        .TypedLambda ql) || !ql.parameters().isEmpty()) {
                    throw new com.legend.error.NotImplementedException(
                            "concatenateTemporalTdsQueries over a non-literal"
                            + " lambda collection is not supported yet"
                            + " (element " + le.getClass().getSimpleName()
                            + ", carrier " + lfsArg.getClass().getSimpleName()
                            + ")");
                }
                queries.add(ql.body().get(ql.body().size() - 1));
            }
            TypedSpec folded = queries.get(0);
            for (int qi = 1; qi < queries.size(); qi++) {
                folded = new com.legend.compiler.spec.typed.TypedConcatenate(
                        folded, queries.get(qi), folded.info());
            }
            q = new com.legend.compiler.spec.typed.TypedLambda(
                    java.util.List.of(), java.util.List.of(folded),
                    new com.legend.compiler.element.type.ExprType(
                            new com.legend.compiler.element.type.Type
                                    .FunctionType(java.util.List.of(),
                                    new com.legend.compiler.element.type.Type
                                            .Param(folded.info().type(),
                                            folded.info().multiplicity())),
                            com.legend.compiler.element.type.Multiplicity
                                    .Bounded.ONE));
        }
        if (!(q instanceof com.legend.compiler.spec.typed.TypedLambda lam)
                || !lam.parameters().isEmpty()) {
            throw new com.legend.error.NotImplementedException(
                    "execute() whose query argument is not a lambda");
        }
        TypedSpec mArg = letBound(ec.args().get(1), letPrefix);
        // the EMPTY-MAPPING SENTINEL ^Mapping(name='') (testFrom.pure:30):
        // every branch carries its own ->from() — no explicit mapping to
        // attach; the chain's from() walls stay the honest failure
        boolean sentinelMapping = mArg
                instanceof com.legend.compiler.spec.typed.TypedNewInstance sni
                && "meta::pure::mapping::Mapping".equals(sni.classFqn());
        com.legend.compiler.spec.typed.TypedPackageableRef mref = null;
        if (!sentinelMapping) {
            if (!(mArg instanceof
                    com.legend.compiler.spec.typed.TypedPackageableRef mr)) {
                throw new com.legend.error.NotImplementedException(
                        "execute() mapping argument must be a mapping reference");
            }
            mref = mr;
        }
        // the RUNTIME ARGUMENT's effectful user calls (the corpus's
        // createDbAndGetConnection: DDL + seed, returns the handle) run
        // ONCE here — engine order: runtime construction precedes
        // execution; the value itself stays an opaque handle (re-running
        // on a non-eager chain build would double the DDL)
        if (eager && ec.args().size() >= 3) {
            runRuntimeArgEffects(letBound(ec.args().get(2), letPrefix),
                    letPrefix, specs, env);
        }
        // connection POST-PROCESSOR hooks ride the runtime argument
        // (sqlQueryPostProcessorsConnectionAware): inline the runtime
        // helper, recognize the replaceTables shape, thread the rename
        // map to the lowering seam (applied over OUR SQL IR)
        if (ec.args().size() >= 3) {
            TypedSpec rtArg = letBound(ec.args().get(2), letPrefix);
            if (rtArg instanceof com.legend.compiler.spec.typed.TypedUserCall) {
                rtArg = new com.legend.compiler.spec.UserCallInliner(specs)
                        .inlineBody(java.util.List.of(rtArg)).get(0);
            }
            java.util.Map<String, String> tr = com.legend.lowering
                    .SqlPostProcessors.tableReplaceMap(rtArg);
            com.legend.exec.PostProcessBoundary.record(tr);
            if (!tr.isEmpty()) {
                env = new ExecEnv(env.ctx(), env.runtimeFqn(), env.dialect(),
                        env.connection(), env.rawSqlFailureSink(),
                        env.addDriverTablePk(), env.queryLets(), tr);
            }
        }
        java.util.List<TypedSpec> qb = new java.util.ArrayList<>(letPrefix);
        qb.addAll(lam.body());
        var inliner = new com.legend.compiler.spec.UserCallInliner(specs);
        TypedSpec chain = inliner.inlineBody(qb).get(0);
        env.queryLets().putAll(inliner.queryLets());
        if (!containsTypedFrom(chain)) {
            if (mref == null) {
                throw new com.legend.error.NotImplementedException(
                        "execute() with the empty-mapping sentinel requires"
                        + " ->from() context inside the query");
            }
            java.util.Optional<com.legend.compiler.spec.typed.TypedPackageableRef>
                    runtime = env.runtimeFqn() == null ? java.util.Optional.empty()
                            : java.util.Optional.of(
                                    new com.legend.compiler.spec.typed.TypedPackageableRef(
                                            env.runtimeFqn(), mref.info()));
            // the execute() RUNTIME ARGUMENT's connection content is
            // harness-ambient EXCEPT ModelChainConnection mappings — the
            // XStore chain: an M2M mapping's ~src classes resolve THROUGH
            // them (same rule as FromChecker's instance-runtime arm)
            java.util.List<String> chainMappings = ec.args().size() >= 3
                    ? com.legend.compiler.spec.typed.TypedFrom.chainMappingsIn(
                            letBound(ec.args().get(2), letPrefix))
                    : java.util.List.of();
            java.util.Map<String, String> jsonSources = ec.args().size() >= 3
                    ? com.legend.compiler.spec.typed.TypedFrom.jsonSourcesIn(
                            letBound(ec.args().get(2), letPrefix))
                    : java.util.Map.of();
            chain = new com.legend.compiler.spec.typed.TypedFrom(chain,
                    java.util.Optional.of(mref), runtime, chainMappings,
                    jsonSources, chain.info());
        }
        boolean relationRooted = chain.info().type()
                instanceof com.legend.compiler.element.type.Type.RelationType;
        ExecutionResult run = null;
        if (eager) {
            // the inliner consumed the query's lets; graph-tree date args
            // still spell the variables (serialize-key source form) — the
            // resolver's let env resolves them (engine inScopeVars)
            java.util.List<TypedSpec> body =
                    new com.legend.resolver.StoreResolver(env.ctx(), specs)
                            .withLetBindings(env.queryLets())
                            .resolve(java.util.List.of(chain), env.runtimeFqn());
            // the engine's RelationalExecutionContext option: driver-table
            // PK columns join every projection (#45 validation)
            if (env.addDriverTablePk()) {
                body = com.legend.validation.DriverPkAppend.apply(
                        body, env.ctx());
            }
            run = executeTyped(body, env);
        }
        return new ExecFrame(chain, relationRooted, run);
    }

    /** Effectful user calls inside an execute() RUNTIME argument run once
     * (executeCallStatement); the walk stops AT each call — its own args
     * are the callee's business, and non-effectful calls (testRuntime())
     * stay unevaluated orchestration handles. */
    private static void runRuntimeArgEffects(TypedSpec n,
            java.util.List<TypedSpec> letPrefix, SpecCompiler specs,
            ExecEnv env) throws java.sql.SQLException {
        if (n instanceof com.legend.compiler.spec.typed.TypedUserCall uc) {
            if (containsEffect(uc, specs, new java.util.HashMap<>())) {
                executeCallStatement(uc, letPrefix, specs, env,
                        new java.util.ArrayDeque<>());
            }
            return;
        }
        for (TypedSpec c : n.children()) {
            runRuntimeArgEffects(c, letPrefix, specs, env);
        }
    }

    /** A let-bound argument resolves through the caller's let prefix
     * ({@code let q = |...|; execute($q, ...)}). */

    /** Inliner-consumed lets prefix the lowering as TypedLet statements
     * (the classic lower(List) path records them as letBindings) so a
     * surviving $let read — a graph-tree qualifier ARG, engine
     * inScopeVars — resolves at the leaf. A let whose value has no
     * scalar lowering (runtime handles, relation frames) is not seeded:
     * its reads keep their existing loud walls. */
    private static java.util.List<com.legend.compiler.spec.typed.TypedSpec>
            withQueryLetPrefix(
                    java.util.List<com.legend.compiler.spec.typed.TypedSpec> body,
                    ExecEnv env, com.legend.compiler.element.ModelContext ctx) {
        java.util.List<com.legend.compiler.spec.typed.TypedSpec> out =
                new java.util.ArrayList<>();
        for (var qe : env.queryLets().entrySet()) {
            var let = new com.legend.compiler.spec.typed.TypedLet(
                    qe.getKey(), qe.getValue(), qe.getValue().info());
            try {
                new com.legend.lowering.Lowerer(
                        t -> com.legend.compiler.element.ClassLayouts
                                .layoutOf(ctx, t),
                        f -> ctx.findClass(f).isPresent())
                        .lower(java.util.List.of(let, qe.getValue()));
                out.add(let);
            } catch (RuntimeException notScalar) {
                // not seedable — reads of this let keep their loud walls
            }
        }
        out.addAll(body);
        return out;
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
                && pc2.callee().qualifiedName().endsWith("::pair")
                && pc2.args().size() == 2) {
            n = pc2.args().get("first".equals(pp2.property()) ? 0 : 1);
        }
        return n;
    }

    private static TypedSpec letBound(TypedSpec arg,
            java.util.List<TypedSpec> letPrefix) {
        if (arg instanceof com.legend.compiler.spec.typed.TypedVariable v) {
            for (int i = letPrefix.size() - 1; i >= 0; i--) {
                if (letPrefix.get(i)
                        instanceof com.legend.compiler.spec.typed.TypedLet let
                        && let.name().equals(v.name())) {
                    return let.value();
                }
            }
        }
        return arg;
    }

    private static boolean containsTypedFrom(TypedSpec n) {
        if (n instanceof com.legend.compiler.spec.typed.TypedFrom) {
            return true;
        }
        for (TypedSpec c : n.children()) {
            if (containsTypedFrom(c)) {
                return true;
            }
        }
        return false;
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
                    && (AT_FQN.equals(nc.callee().qualifiedName())
                            || TO_ONE_FQN.equals(nc.callee().qualifiedName())
                            || FIRST_FQN.equals(nc.callee().qualifiedName()))
                    && !nc.args().isEmpty()) {
                if (AT_FQN.equals(nc.callee().qualifiedName())
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
     * The TYPED splice — rides the inliner's per-node hook: {@code $r.values}
     * becomes the frame's query chain; {@code ->at(0)}/{@code ->toOne()}
     * over it collapse for a relation root (real selections for a class or
     * scalar root); {@code $r->size()} over a relation-rooted frame is the
     * envelope's ONE; an inline {@code execute(...).values} splices in place.
     */
    private static java.util.function.BiFunction<TypedSpec, java.util.Set<String>, TypedSpec> spliceHook(
            java.util.Map<String, ExecFrame> allFrames,
            java.util.List<TypedSpec> letPrefix, SpecCompiler specs, ExecEnv env) {
        return (n, boundVars) -> {
            // a lambda-bound variable spelled like an exec-let is NOT a frame
            // read (corpus: `let r = execute(...)` + `->map(r|$r.values...)`
            // — the map binder's $r.values is the ROW's cells, never the
            // Result envelope); shadowed names drop out of the frame map
            java.util.Map<String, ExecFrame> execFrames = allFrames;
            if (!boundVars.isEmpty()
                    && boundVars.stream().anyMatch(allFrames::containsKey)) {
                execFrames = new java.util.LinkedHashMap<>(allFrames);
                execFrames.keySet().removeAll(boundVars);
            }
            // the Typer's `.rows` MARKER (identity over a relation value):
            // it exists so the arms below can tell a REAL row index
            // ($r.values.rows->at(k)) from the Result envelope
            // ($r.values->at(k)) — once seen, it erases to its source.
            if (n instanceof com.legend.compiler.spec.typed.TypedPropertyAccess rp
                    && rp.property().equals("rows")
                    && rp.source().info().type() instanceof
                            com.legend.compiler.element.type.Type.RelationType) {
                return rp.source();
            }
            // the Typer's `.columns.documentation` MARKER: the receiver is
            // spliced by the time this hook sees the node — walk to the
            // PROJECT and fold col()'s doc metadata (String[0..1] per
            // column: undocumented columns flatten away)
            if (n instanceof com.legend.compiler.spec.typed.TypedPropertyAccess dm
                    && dm.property().equals("columns.documentation")) {
                TypedSpec un = dm.source();
                boolean walked = true;
                while (walked) {
                    walked = false;
                    if (un instanceof com.legend.compiler.spec.typed.TypedFrom f2) {
                        un = f2.source();
                        walked = true;
                    } else if (un instanceof com.legend.compiler.spec.typed
                            .TypedNativeCall w2
                            && !w2.args().isEmpty()
                            && w2.args().get(0).info().type() instanceof
                                    com.legend.compiler.element.type
                                            .Type.RelationType) {
                        un = w2.args().get(0);
                        walked = true;
                    } else if (un instanceof com.legend.compiler.spec.typed
                            .TypedPropertyAccess pv2) {
                        // an UNSPLICED envelope read ($result.values):
                        // resolve through the exec frame ourselves
                        TypedSpec spl = spliceValuesRead(pv2, execFrames,
                                letPrefix, specs, env);
                        if (spl != null) {
                            un = spl;
                            walked = true;
                        }
                    }
                }
                if (un instanceof com.legend.compiler.spec.typed.TypedProject tp2) {
                    return tp2.docsFold();
                }
                throw new IllegalStateException("columns.documentation read"
                        + " did not reach a project after the splice (source="
                        + un.getClass().getSimpleName() + ")");
            }
            // $r->size() / $tds->size(): ONE TDS value, never the row count
            if (n instanceof com.legend.compiler.spec.typed.TypedNativeCall sz
                    && SIZE_FQNS.contains(sz.callee().qualifiedName())
                    && sz.args().size() == 1
                    && sz.args().get(0)
                            instanceof com.legend.compiler.spec.typed.TypedVariable sv
                    && execFrames.containsKey(sv.name())
                    && execFrames.get(sv.name()).relationRooted()) {
                return new com.legend.compiler.spec.typed.TypedCInteger(1L,
                        sz.info());
            }
            // $r.values->at(k) / ->toOne(): collapse (relation root) or a
            // REAL selection over the spliced chain (class/scalar root)
            if (n instanceof com.legend.compiler.spec.typed.TypedNativeCall w
                    && (AT_FQN.equals(w.callee().qualifiedName())
                            || TO_ONE_FQN.equals(w.callee().qualifiedName())
                            || FIRST_FQN.equals(w.callee().qualifiedName()))
                    && !w.args().isEmpty()) {
                TypedSpec spliced = spliceValuesRead(w.args().get(0),
                        execFrames, letPrefix, specs, env);
                if (spliced != null) {
                    // relation-rootedness IS the spliced chain's root type
                    boolean relation = spliced.info().type() instanceof
                            com.legend.compiler.element.type.Type.RelationType;
                    if (relation) {
                        if (AT_FQN.equals(w.callee().qualifiedName())
                                && !(w.args().size() == 2 && w.args().get(1)
                                        instanceof com.legend.compiler.spec.typed
                                                .TypedCInteger k
                                        && k.value().longValue() == 0)) {
                            throw new IllegalStateException(
                                    "Result.values->at(k>0) on a relation-rooted"
                                    + " query — the values envelope holds one TDS");
                        }
                        return spliced;
                    }
                    java.util.List<TypedSpec> args =
                            new java.util.ArrayList<>(w.args());
                    args.set(0, spliced);
                    return new com.legend.compiler.spec.typed.TypedNativeCall(
                            w.callee(), args, w.info());
                }
            }
            // $r.activities: the engine's execution-activity trail (routing/
            // aggregationAware rewrite records). We record NONE — the read
            // is the EMPTY collection, so absence asserts (assertEmpty over
            // an activity filter) hold and presence asserts fail honestly.
            // A filter DIRECTLY over the read folds here (hook is top-down;
            // filter([]) ≡ [] — its predicate never evaluates, so activity-
            // class vocabulary like instanceOf needs no scalar lowering).
            if (n instanceof com.legend.compiler.spec.typed.TypedFilter tf
                    && activitiesRead(tf.source(), execFrames)) {
                return new com.legend.compiler.spec.typed.TypedCollection(
                        java.util.List.of(), tf.info());
            }
            if (activitiesRead(n, execFrames)) {
                return new com.legend.compiler.spec.typed.TypedCollection(
                        java.util.List.of(), n.info());
            }
            // $r.values / execute(...).values → the spliced chain
            TypedSpec direct = spliceValuesRead(n, execFrames, letPrefix,
                    specs, env);
            if (direct != null) {
                return direct;
            }
            // a BARE frame variable reads as the chain (harness parity)
            if (n instanceof com.legend.compiler.spec.typed.TypedVariable bv
                    && execFrames.containsKey(bv.name())) {
                return execFrames.get(bv.name()).chain();
            }
            return n;
        };
    }

    /** A {@code <frameVar>.activities} read (the Result envelope's
     * execution-activity trail). */
    private static boolean activitiesRead(TypedSpec n,
            java.util.Map<String, ExecFrame> execFrames) {
        return n instanceof com.legend.compiler.spec.typed.TypedPropertyAccess ap
                && ap.property().equals("activities")
                && ap.source()
                        instanceof com.legend.compiler.spec.typed.TypedVariable av
                && execFrames.containsKey(av.name());
    }

    /** The frame behind a {@code <frameVar>.values} read; null otherwise. */
    private static @com.legend.Nullable ExecFrame valuesFrame(TypedSpec n,
            java.util.Map<String, ExecFrame> execFrames) {
        if (n instanceof com.legend.compiler.spec.typed.TypedPropertyAccess pa
                && pa.property().equals("values")
                && pa.source() instanceof com.legend.compiler.spec.typed.TypedVariable v
                && execFrames.containsKey(v.name())) {
            return execFrames.get(v.name());
        }
        return null;
    }

    /** Splice a {@code .values} read (over a frame variable or an INLINE
     * execute call) into the underlying typed query chain; null when the
     * node is not a values read the frames can answer. */
    private static @com.legend.Nullable TypedSpec spliceValuesRead(TypedSpec n,
            java.util.Map<String, ExecFrame> execFrames,
            java.util.List<TypedSpec> letPrefix, SpecCompiler specs, ExecEnv env) {
        ExecFrame f = valuesFrame(n, execFrames);
        if (f != null) {
            return f.chain();
        }
        if (n instanceof com.legend.compiler.spec.typed.TypedPropertyAccess pa
                && pa.property().equals("values")) {
            TypedSpec src = pa.source();
            while (src instanceof com.legend.compiler.spec.typed.TypedFrom sf) {
                src = sf.source();
            }
            if (src instanceof com.legend.compiler.spec.typed.TypedNativeCall ec
                    && com.legend.compiler.element.type.PlatformTypes
                            .isExecuteFqn(ec.callee().qualifiedName())) {
                try {
                    // inline read: the value is observed where it stands —
                    // no separate eager run (it would execute twice)
                    return buildFrame(ec, letPrefix, false, specs, env).chain();
                } catch (java.sql.SQLException e) {
                    throw new IllegalStateException(e);
                }
            }
        }
        return null;
    }

    /** Bind an effectful map's parameter: TypedVariable(param) reads in
     * the body's native-call arguments replace with the STRING literal
     * (the corpus shape: executeInDb($sql, $connection)); a read anywhere
     * deeper is loud — never silently unbound. */
    private static TypedSpec bindParam(TypedSpec node, String param, String value) {
        var lit = new com.legend.compiler.spec.typed.TypedCString(value,
                com.legend.compiler.element.type.ExprType.one(
                        com.legend.compiler.element.type.Type.Primitive.STRING));
        if (node instanceof com.legend.compiler.spec.typed.TypedVariable tv
                && tv.name().equals(param)) {
            return lit;
        }
        if (node instanceof com.legend.compiler.spec.typed.TypedNativeCall nc) {
            java.util.List<TypedSpec> args = new java.util.ArrayList<>();
            for (TypedSpec a : nc.args()) {
                args.add(a instanceof com.legend.compiler.spec.typed.TypedVariable v2
                        && v2.name().equals(param) ? lit : a);
            }
            return new com.legend.compiler.spec.typed.TypedNativeCall(
                    nc.callee(), args, nc.info());
        }
        if (referencesVar(node, param)) {
            throw new IllegalStateException("effectful map body reads the"
                    + " parameter '" + param + "' in an unsupported position");
        }
        return node;
    }

    private static boolean referencesVar(TypedSpec node, String name) {
        if (node instanceof com.legend.compiler.spec.typed.TypedVariable tv
                && tv.name().equals(name)) {
            return true;
        }
        for (TypedSpec c : node.children()) {
            if (referencesVar(c, name)) {
                return true;
            }
        }
        return false;
    }

    /** The member name of a typed enum-shaped read (DatabaseType.H2). */
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
            java.util.Deque<String> frames) throws java.sql.SQLException {
        String key = call.callee().signatureKey();
        if (frames.contains(key)) {
            throw new IllegalStateException("recursive effectful call: "
                    + call.callee().qualifiedName());
        }
        frames.push(key);
        try {
            java.util.List<TypedSpec> frame = new java.util.ArrayList<>();
            for (int p = 0; p < call.callee().parameters().size(); p++) {
                java.util.List<TypedSpec> argBody = new java.util.ArrayList<>(letPrefix);
                argBody.add(call.args().get(p));
                TypedSpec argValue = new com.legend.compiler.spec.UserCallInliner(specs)
                        .inlineBody(argBody).get(0);
                if (containsEffectfulNode(java.util.List.of(argValue))) {
                    // same rule as the effectful-let guard: the frame binds
                    // arguments as lets, and β-substitution drops an unused
                    // one (or doubles a twice-used one) — refuse loudly
                    // (audit 17: ignore(executeInDb(...)) silently lost the
                    // insert)
                    throw new IllegalStateException("effectful argument to '"
                            + call.callee().qualifiedName()
                            + "' (parameter '"
                            + call.callee().parameters().get(p).name()
                            + "' binds an executeInDb-family call) is not"
                            + " supported");
                }
                frame.add(new com.legend.compiler.spec.typed.TypedLet(
                        call.callee().parameters().get(p).name(), argValue,
                        argValue.info()));
            }
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
    static ExecutionResult executeTyped(
            java.util.List<TypedSpec> body, ExecEnv env)
            throws java.sql.SQLException {
        ModelContext ctx = env.ctx();
        String runtimeFqn = env.runtimeFqn();
        java.sql.Connection connection = env.connection();
        TypedSpec root = body.get(body.size() - 1);
        // from() is context-only, but its info is the PRE-RESOLUTION
        // declared type — kept: a primitive-many declared root whose
        // resolved source became relation-shaped (scalar ->map lowers to
        // a one-column project) still executes as a VALUE COLLECTION, so
        // the Executor's null-drop applies (pure collections hold no
        // empties — the no-match parent contributes nothing, task #78).
        com.legend.compiler.element.type.ExprType declaredInfo = null;
        while (root instanceof com.legend.compiler.spec.typed.TypedFrom fr) {
            if (declaredInfo == null) {
                declaredInfo = fr.info();
            }
            root = fr.source();
        }
        // K-NATIVE dispatch: executeInDb never lowers — it IS the phase-K
        // boundary (raw SQL over the ambient JDBC connection).
        if (root instanceof com.legend.compiler.spec.typed.TypedNativeCall nc
                && com.legend.compiler.element.type.PlatformTypes.EXECUTE_IN_DB
                        .equals(nc.callee().qualifiedName())) {
            return executeInDb(body, nc, env);
        }
        // ORCHESTRATION-VALUE channel: fetchDb* metadata reads evaluate
        // HOST-SIDE against the H2 second target (task #43 slice B2)
        if (com.legend.exec.HostEval.wantsHostEval(root)) {
            return com.legend.exec.HostEval.evalToResult(root, env.ctx());
        }
        if (root instanceof com.legend.compiler.spec.typed.TypedNativeCall dc
                && com.legend.compiler.element.type.PlatformTypes.DROP_AND_CREATE_TABLE_IN_DB
                        .equals(dc.callee().qualifiedName())) {
            return dropAndCreateTableInDb(body, dc, env);
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
        // CONNECTION/RUNTIME values are ORCHESTRATION HANDLES (the
        // executeInDb convention below: connections are harness-ambient,
        // never host object graphs). A setup returning ^Runtime(...) or
        // binding connectionByElement(...) must not force them through
        // the SQL pipeline. Effects nested in ctor args would be dropped
        // — loud, never silent.
        if (root instanceof com.legend.compiler.spec.typed.TypedNativeCall cbe
                && "meta::core::runtime::connectionByElement"
                        .equals(cbe.callee().qualifiedName())) {
            return new ExecutionResult.Scalar(null, cbe.info().type());
        }
        if (root instanceof com.legend.compiler.spec.typed.TypedCast castC
                && castC.source()
                        instanceof com.legend.compiler.spec.typed.TypedNativeCall cbe2
                && "meta::core::runtime::connectionByElement"
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
        if (root instanceof com.legend.compiler.spec.typed.TypedNativeCall pn
                && (com.legend.compiler.element.type.PlatformTypes.PRINT
                        .equals(pn.callee().qualifiedName())
                        || com.legend.compiler.element.type.PlatformTypes.PRINTLN
                                .equals(pn.callee().qualifiedName()))) {
            // debug output: a NO-OP — the argument is NEVER evaluated (it
            // may introspect a ResultSet, which never materializes host-
            // side). Divergence from the engine (which prints) is deliberate
            // harness behavior. A REAL effect nested inside the argument
            // would be dropped — that must never be silent (audit 17): it
            // feeds the failure ledger (arming the emptiness guard), or
            // throws when no ledger is listening.
            if (containsEffectfulNode(pn.args())) {
                if (env.rawSqlFailureSink() == null) {
                    throw new IllegalStateException("print/println argument"
                            + " contains an executeInDb-family call; the print"
                            + " arm never evaluates arguments, so the effect"
                            + " would be dropped");
                }
                env.rawSqlFailureSink().accept(
                        "print => argument contains an executeInDb-family call;"
                        + " not evaluated (effect dropped)");
            }
            return new ExecutionResult.Scalar(null, pn.info().type());
        }
        // The engine's CSV-seed SQL generator: strings from the parsed
        // store's column types (CsvSeed) — dbConfig is never evaluated.
        // The corpus's own setupTestData body maps the result through
        // executeInDb, which the TypedMap arm below sequences.
        if (root instanceof com.legend.compiler.spec.typed.TypedNativeCall gen
                && (com.legend.compiler.element.type.PlatformTypes.SET_UP_DATA_SQLS_V2
                        .equals(gen.callee().qualifiedName())
                    || com.legend.compiler.element.type.PlatformTypes.SET_UP_DATA_SQLS
                        .equals(gen.callee().qualifiedName()))) {
            String csv = evalStringArg(body, gen.args().get(0), env);
            String dbFqn = gen.args().get(1)
                    instanceof com.legend.compiler.spec.typed.TypedPackageableRef pr
                    ? pr.fullPath() : null;
            java.util.List<Object> sqls = new java.util.ArrayList<>(
                    com.legend.exec.CsvSeed.sqls(csv, dbFqn, ctx));
            return new ExecutionResult.Collection(sqls,
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
            ExecutionResult values = executeTyped(src, env);
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
                    one.add(bindParam(stmt2, param, sv));
                }
                last = executeTyped(one, env);
            }
            return last;
        }
        if (root instanceof com.legend.compiler.spec.typed.TypedNativeCall sc
                && com.legend.compiler.element.type.PlatformTypes.DROP_AND_CREATE_SCHEMA_IN_DB
                        .equals(sc.callee().qualifiedName())) {
            return dropAndCreateSchemaInDb(body, sc, env);
        }
        if (System.getenv("LL_DUMP_RESOLVED") != null) {
            System.err.println("[resolved] " + body);
        }
        com.legend.sql.SqlQuery plan = new com.legend.lowering.Lowerer(
                t -> com.legend.compiler.element.ClassLayouts.layoutOf(ctx, t),
                f -> ctx.findClass(f).isPresent())
                .lower(withQueryLetPrefix(body, env, ctx));
        plan = com.legend.lowering.SqlPostProcessors.apply(plan,
                env.tableReplace());
        com.legend.sql.dialect.SqlDialect dialect = env.dialect();
        boolean collectionDeclared = declaredInfo != null
                && declaredInfo.type()
                        instanceof com.legend.compiler.element.type.Type.Primitive
                && declaredInfo.multiplicity()
                        .requireBounded("result shape").isMany()
                && root.info().type()
                        instanceof com.legend.compiler.element.type.Type.RelationType;
        if (System.getenv("LL_TMP_SQL") != null) {
            System.err.println("[exec-sql] " + dialect.render(plan));
        }
        ExecutionResult res = Executor.execute(
                dialect.render(plan), plan,
                collectionDeclared ? java.util.Objects.requireNonNull(declaredInfo)
                        : root.info(),
                collectionDeclared ? com.legend.exec.ResultShape.COLLECTION
                        : com.legend.exec.ResultShape.of(root),
                connection, dialect);
        // rows->toOne() READER enforcement (audit 22b F1): the lowering is
        // row-identical to the relation (engine toOne throws at the READER,
        // never in SQL) — so THE reader enforces exactly-one here for a
        // TABULAR-consumed toOne root; the scalar arm's second-row guard
        // covers scalar reads, this covers whole-TDS consumption and the
        // ZERO-row lower bound.
        if (root instanceof com.legend.compiler.spec.typed.TypedNativeCall tw
                && "meta::pure::functions::multiplicity::toOne"
                        .equals(tw.callee().qualifiedName())
                && !tw.args().isEmpty()
                && tw.args().get(0).info().type()
                        instanceof com.legend.compiler.element.type.Type.RelationType
                && res instanceof ExecutionResult.Tabular tab
                && tab.rows().size() != 1) {
            throw new IllegalStateException("toOne() over a relation returned "
                    + tab.rows().size() + " row(s) — the exactly-one contract"
                    + " (engine reader semantics)");
        }
        return res;
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
    static ExecutionResult executeInDb(
            java.util.List<TypedSpec> body,
            com.legend.compiler.spec.typed.TypedNativeCall call, ExecEnv env)
            throws java.sql.SQLException {
        String raw = evalStringArg(body, call.args().get(0), env);
        // split FIRST: adaptation is per-statement (its recognizers anchor
        // at statement start). Corpus-authored raw H2 goes through THE
        // boundary translator — never a dialect renderer (R0 rule).
        for (String stmt : com.legend.sql.RawSql.splitStatements(raw)) {
            try {
                Executor.executeRaw(env.connection(),
                        com.legend.exec.RawSqlBoundary.h2ToDuckDb(stmt));
            } catch (java.sql.SQLException e) {
                if (env.rawSqlFailureSink() == null) {
                    throw e;
                }
                // per-statement tolerance (engine-harness semantics): report
                // and CONTINUE — the caller's ledger drives its emptiness guard
                env.rawSqlFailureSink().accept(stmt.strip().split("\\n")[0]
                        + " => " + String.valueOf(e.getMessage()).split("\\n")[0]);
            }
        }
        // an opaque ResultSet handle: setup statements ignore it; a test
        // that READS it will surface loudly here when that day comes
        return new ExecutionResult.Scalar(null, call.info().type());
    }

    /** The K-native {@code dropAndCreateSchemaInDb}: the engine DROPS +
     * creates; here create-if-missing — the DDL seeds already own tables
     * in the schema, and the setup's own dropAndCreateTableInDb calls
     * recreate what it manages. Recorded on the METADATA channel only —
     * the H2Verify row-replay stream stays exactly the corpus's own
     * statements. */
    static ExecutionResult dropAndCreateSchemaInDb(
            java.util.List<TypedSpec> body,
            com.legend.compiler.spec.typed.TypedNativeCall sc, ExecEnv env)
            throws java.sql.SQLException {
        String schemaDdl = "Create schema if not exists "
                + evalStringArg(body, sc.args().get(0), env);
        Executor.executeRaw(env.connection(), schemaDdl);
        com.legend.exec.RawSqlBoundary.recordMeta(schemaDdl);
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
            com.legend.compiler.spec.typed.TypedNativeCall call, ExecEnv env)
            throws java.sql.SQLException {
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
        Executor.executeRaw(connection,
                com.legend.exec.RawSqlBoundary.h2ToDuckDb(Ddl.dropTable(schema, table)));
        Executor.executeRaw(connection,
                com.legend.exec.RawSqlBoundary.h2ToDuckDb(Ddl.createTable(def, schema)));
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
                com.legend.exec.RawSqlBoundary.recordMeta("Alter table "
                        + qn + " alter column " + pk + " set not null");
            }
            com.legend.exec.RawSqlBoundary.recordMeta("Alter table " + qn
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
            return Ddl.dropTableStatementText(sch, tbl);
        }
        String lookup = "default".equals(sch) ? tbl : sch + "." + tbl;
        com.legend.model.DatabaseDefinition.TableDefinition def =
                env.ctx().findTableDefinition(db.fullPath(), lookup)
                        .orElseThrow(() -> new IllegalStateException(
                                "createTableStatement: no table '" + lookup
                                        + "' in store " + db.fullPath()));
        // the ENGINE TEXT (NOT NULL / PRIMARY KEY constraints) — the
        // EXECUTION form (dropAndCreateTableInDb -> Ddl.createTable)
        // stays constraint-free for DuckDB re-seeds
        return Ddl.createTableStatementText(def, sch);
    }

    /**
     * Evaluate one String[1] argument of a K-native THROUGH the pipeline:
     * the let statements it (transitively) references ride along; all
     * others — crucially connection chains — are dropped, never evaluated.
     */
    static String evalStringArg(java.util.List<TypedSpec> body, TypedSpec arg,
            ExecEnv env) throws java.sql.SQLException {
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
