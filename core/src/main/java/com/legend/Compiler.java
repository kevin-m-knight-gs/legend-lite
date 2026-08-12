package com.legend;

import com.legend.compiler.NameResolver;
import com.legend.compiler.element.PureModelContext;
import com.legend.compiler.element.ModelContext;
import com.legend.compiler.spec.SpecCompiler;
import com.legend.compiler.spec.typed.TypedSpec;
import com.legend.normalizer.ModelNormalizer;
import com.legend.parser.SpecParser;
import com.legend.model.NormalizedModel;
import com.legend.parser.ElementParser;
import com.legend.model.ParsedModel;

import java.util.List;
import java.util.Objects;

/**
 * Top-level entry point for the Legend Lite compiler pipeline.
 *
 * <p>Drives the steps listed in {@code package-info.java}:
 * lex → parse-element → parse-spec → resolve-names → normalize (E) →
 * compile-element (F) → compile-spec (G) → resolve-mapping (H) →
 * build-sql (I) → render-sql (J) → execute (K).
 *
 * <p><strong>Status.</strong> {@link #compileModel} runs lex→F;
 * {@link #compileQuery} carries a query through Phase G to its typed HIR;
 * {@link #execute} is the FULL pipeline — lower (I), render (J), execute (K)
 * over the caller's connection with a typed result. Phase H (class sources /
 * mappings) is the remaining gap; {@link #compile(String, String, String)}
 * throws until a runtime-resolved plan-string form is worth having.
 *
 * <p>This is the single DRIVER seam: it owns step ordering and module
 * assembly. Statement-level EXECUTION semantics (effectful setup bodies,
 * call frames, K-native dispatch) live in
 * {@link StatementExecutor}, to which
 * {@link #executeResolved} delegates — the driver never re-implements a
 * step, and the executor never decides pipeline order.
 */
public final class Compiler {

    private Compiler() {}

    /** Parse a model at the PRODUCT level (LEGEND_LITE) — the front door
     *  for product endpoints (servers, nlq), so they never touch the
     *  parser package directly. The Compiler is the product's provenance
     *  router: which level users get is decided HERE. */
    public static ParsedModel parseModel(String source) {
        return com.legend.parser.ElementParser.parse(source,
                com.legend.parser.Dialect.LEGEND_LITE);
    }

    /** Parse one query/expression at the PRODUCT level. */
    public static com.legend.protocol.spec.ValueSpecification
            parseQuery(String query) {
        return com.legend.parser.SpecParser.parse(query,
                com.legend.parser.Dialect.LEGEND_LITE);
    }


    /**
     * Frontend pipeline: Pure model source &rarr; typed {@link ModelContext}.
     *
     * <p>Drives steps 1&ndash;6 (the steps implemented in {@code core/} today;
     * the query/spec and backend steps land later):
     * <ol>
     *   <li><b>parse</b> &mdash; {@link ElementParser#parse} (lex + parse-element).</li>
     *   <li><b>resolve-names</b> &mdash; {@link NameResolver#resolve(ParsedModel)}
     *       rewrites simple names to FQNs against the user imports + platform
     *       prelude (the prelude is owned by the resolver, not this driver).</li>
     *   <li><b>normalize</b> (Phase E) &mdash; {@link ModelNormalizer#normalize}
     *       externalizes body sites into synthesized functions.</li>
     *   <li><b>element-compile</b> (Phase F) &mdash; {@link PureModelContext#from}
     *       builds the typed model; synth functions flatten into
     *       {@code findFunction} uniformly with user functions.</li>
     * </ol>
     *
     * <p>This is the single orchestration point: it owns step <em>ordering</em>.
     * Each step is the same method its own unit tests exercise &mdash; there is
     * no orchestrator-only code path.
     *
     * @param model Pure model source (classes, enums, associations, databases,
     *              mappings, services, runtimes, ...).
     * @return the populated, queryable {@link ModelContext}.
     */
    public static ModelContext compileModel(@com.legend.Nullable String model) {
        Objects.requireNonNull(model, "model");
        ParsedModel parsed = ElementParser.parse(model,
                com.legend.parser.Dialect.LEGEND_LITE);
        try {
            return buildModel(parsed);
        } catch (com.legend.error.ModelException e) {
            // Decorate with the offending ELEMENT's [line:col] — the offsets
            // live on the original parse (resolution rebuilds ParsedModel
            // without them), so the driver is where source meets failure.
            Integer off = e.element() == null ? null
                    : parsed.elementOffsets().get(e.element());
            if (off == null || parsed.source() == null) {
                throw e;
            }
            throw new com.legend.error.ModelException(e.phase(),
                    com.legend.error.LegendCompileException.position(parsed.source(), off)
                            + " " + e.getMessage(), e.element());
        }
    }

    /** One named source unit of a multi-file model (a MODULE member). */
    public record ModelSource(String name, String text) {
        public ModelSource {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(text, "text");
        }
    }

    /**
     * A parsed multi-source MODULE: the merged {@link ParsedModel} plus the
     * duplicate elements that were dropped (first definition wins; each
     * loser is reported as {@code kind fqn (source, kept source)} so the
     * caller can wall it) and the per-unit texts for error decoration.
     */
    public record ParsedModule(ParsedModel model, List<String> duplicateElements,
                               java.util.Map<String, String> sourceTexts) {
        public ParsedModule {
            duplicateElements = List.copyOf(duplicateElements);
            sourceTexts = java.util.Map.copyOf(sourceTexts);
        }
    }

    /**
     * Parse each source as its OWN unit — per-file import sections, per-file
     * positions — and merge into one model: the MODULE compile every real
     * legend project needs (the engine compiles a repository's files
     * together; cross-file references are normal). Imports never leak
     * across units: each element resolves against its own section's scope,
     * and the merged model's GLOBAL scope is empty (per-element scopes are
     * total, so the fallback never widens).
     */
    public static ParsedModule parseSources(List<ModelSource> sources) {
        return parseSources(sources, null);
    }

    /**
     * {@link #parseSources(List)} with an optional PER-FILE parse wall
     * sink (source name &rarr; first error line): an unparseable file is
     * reported and EXCLUDED instead of failing the whole batch — and the
     * parse result is REUSED for the merge, so callers never pre-parse
     * for validation and re-parse for assembly (the corpus runner's
     * throwaway-parse pattern). Null = strict (first parse error throws).
     */
    public static ParsedModule parseSources(List<ModelSource> sources,
            java.util.function.@com.legend.Nullable BiConsumer<String, String> parseWallSink) {
        return parseSources(sources, parseWallSink,
                com.legend.parser.Dialect.LEGEND_LITE);
    }

    /** As above, DIALECT-EXPLICIT: the caller declares its sources'
     *  provenance level (the corpus runner's m2 corpus is
     *  LEGEND_PLATFORM; user batches are LEGEND_LITE). */
    public static ParsedModule parseSources(List<ModelSource> sources,
            java.util.function.@com.legend.Nullable BiConsumer<String, String> parseWallSink,
            com.legend.parser.Dialect dialect) {
        Objects.requireNonNull(sources, "sources");
        List<com.legend.model.PackageableElement> elements = new java.util.ArrayList<>();
        java.util.Map<String, Integer> offsets = new java.util.HashMap<>();
        java.util.Map<String, com.legend.model.ImportScope> elementImports =
                new java.util.HashMap<>();
        java.util.Map<String, String> elementSources = new java.util.HashMap<>();
        java.util.Map<String, String> sourceTexts = new java.util.LinkedHashMap<>();
        java.util.Map<String, String> seen = new java.util.HashMap<>();   // key -> source
        List<String> duplicates = new java.util.ArrayList<>();
        for (ModelSource src : sources) {
            sourceTexts.put(src.name(), src.text());
            ParsedModel unit;
            try {
                unit = ElementParser.parse(src.text(), dialect);
            } catch (com.legend.error.LegendCompileException e) {
                if (parseWallSink == null) {
                    throw e;
                }
                parseWallSink.accept(src.name(),
                        String.valueOf(e.getMessage()).split("\n")[0]);
                continue;
            }
            for (com.legend.model.PackageableElement el : unit.elements()) {
                // FUNCTIONS overload: same FQN with different signatures is
                // NOT a duplicate — the dedup key carries the parameter
                // shape (dropping overloads silently lost the corpus's own
                // executeInDb wrappers)
                String key = el instanceof com.legend.model.FunctionDefinition fd
                        ? "Function::" + fd.qualifiedName() + "(" + fd.parameters()
                                .stream().map(pd -> String.valueOf(pd.type())
                                        + String.valueOf(pd.multiplicity()))
                                .reduce("", (x, y) -> x + "," + y) + ")"
                        : el.getClass().getSimpleName() + "::" + el.qualifiedName();
                String prior = seen.putIfAbsent(key, src.name());
                if (prior != null) {
                    // FIRST definition wins (the corpus carries alternative
                    // models in parent directories); the drop is REPORTED,
                    // never silent
                    duplicates.add(key + " (" + src.name()
                            + ", kept " + prior + ")");
                    continue;
                }
                elements.add(el);
                String fqn = el.qualifiedName();
                Integer off = unit.elementOffsets().get(fqn);
                if (off != null) {
                    offsets.put(fqn, off);
                }
                com.legend.model.ImportScope own = unit.elementImports().get(fqn);
                if (own != null) {
                    elementImports.put(fqn, own);
                }
                elementSources.put(fqn, src.name());
            }
        }
        return new ParsedModule(
                new ParsedModel(elements, com.legend.model.ImportScope.empty(),
                        null, offsets, elementImports, elementSources),
                duplicates, sourceTexts);
    }

    /**
     * The back half of {@link #compileModel(String)} over an
     * already-parsed model: resolve names, normalize, build the context.
     * Multi-source callers decorate errors themselves (they hold the
     * per-unit texts).
     */
    public static ModelContext buildModel(ParsedModel parsed) {
        ParsedModel resolved = NameResolver.resolve(parsed);
        NormalizedModel normalized = ModelNormalizer.normalize(resolved);
        return PureModelContext.from(normalized);
    }

    /** A module built TOLERANTLY: the context over every element that
     * compiles, plus the walls (element FQN => first error line) for every
     * element that does not — the engine-parity behavior for compiling a
     * repository (compile what compiles, report the rest). */
    public record BuiltModule(ModelContext context,
                              java.util.Map<String, String> walls) {
        public BuiltModule {
            walls = java.util.Collections.unmodifiableMap(
                    new java.util.LinkedHashMap<>(walls));
        }
    }

    /**
     * Tolerant module build — POISON, DON'T DROP: every element stays in
     * the model; the walls map records each broken element's FIRST failure
     * reason (eager DIAGNOSIS over the whole module). A broken element
     * harms nothing that merely references it — the failure fires at USE
     * time (compiling the function on call, materializing the binding),
     * loudly, when something actually enters the quarantine. Dropping
     * instead cascaded: removing a walled helper failed every element
     * referencing it, and every test touching THOSE — 182 corpus tests
     * died in the blast radius of functions they never called.
     * One exception: a mapping that fails to NORMALIZE has no canonical
     * form to keep and is excluded (its absence is walled; the legacy
     * per-family harness behaved identically). Unattributed failures
     * still throw — a genuine bug must fail the build.
     */
    public static BuiltModule buildModule(ParsedModel parsed) {
        java.util.Map<String, String> walls = new java.util.LinkedHashMap<>();
        ParsedModel resolved = NameResolver.resolve(parsed, walls);
        NormalizedModel normalized = ModelNormalizer.normalize(resolved, walls);
        PureModelContext ctx = PureModelContext.from(normalized, walls);
        return new BuiltModule(ctx, walls);
    }

    /**
     * Compile a multi-source MODULE. Errors carry the offending element's
     * SOURCE NAME and [line:col] within that source.
     */
    public static ModelContext compileModel(List<ModelSource> sources) {
        ParsedModule module = parseSources(sources);
        try {
            return buildModel(module.model());
        } catch (com.legend.error.ModelException e) {
            String fqn = e.element();
            String srcName = fqn == null ? null
                    : module.model().elementSources().get(fqn);
            Integer off = fqn == null ? null
                    : module.model().elementOffsets().get(fqn);
            if (srcName == null || off == null) {
                throw e;
            }
            throw new com.legend.error.ModelException(e.phase(),
                    srcName + " " + com.legend.error.LegendCompileException
                            .position(java.util.Objects.requireNonNull(
                                    module.sourceTexts().get(srcName)), off)
                            + " " + e.getMessage(), e.element());
        }
    }

    /**
     * Compile a Pure model + query against a runtime to a SQL execution plan.
     * The plan half of {@link #execute(String, String, String, java.sql.Connection)}:
     * the same pipeline (frontend &rarr; G &rarr; H resolve against the
     * driver-supplied runtime &rarr; lower &rarr; render) WITHOUT executing
     * &mdash; the {@code planSql} seam for SQL-shape assertions and plan
     * inspection.
     *
     * @param model      Pure model source (classes, mappings, stores, runtimes, ...).
     * @param query      Pure query expression (a {@code ValueSpecification} in legacy terms).
     * @param runtime    FQN of the runtime to compile against.
     * @return rendered SQL in the runtime's dialect.
     */
    public static String compile(String model, String query, String runtime) {
        return plan(model, query, runtime).sql();
    }

    /**
     * {@link #compile} with the full plan contract: rendered SQL plus the
     * root's {@link com.legend.compiler.element.type.ExprType} and
     * {@link com.legend.exec.ResultShape} &mdash; exactly what
     * {@link com.legend.exec.Executor} would consume, minus execution.
     * Bridges re-wrap these fields verbatim (no invented metadata).
     */
    /** {@link #plan} with the STREAMING graph root
     *  ({@code Lowerer#withStreamingGraphRoot}): one json_object per JDBC
     *  row so a streaming executor stays O(one row) — the core home of the
     *  capability the legacy engine-lite Mode.STREAMING provided. */
    public static com.legend.exec.QueryPlan planStreaming(String model,
            String query, String runtime) {
        return plan(model, query, runtime, true);
    }

    public static com.legend.exec.QueryPlan plan(String model, String query, String runtime) {
        return plan(model, query, runtime, false);
    }

    private static com.legend.exec.QueryPlan plan(String model, String query,
            String runtime, boolean streaming) {
        Lowered l = lowerQuery(model, query, runtime, streaming);
        String sql = dialectOf(l.ctx(), runtime).render(l.plan());
        return new com.legend.exec.QueryPlan(sql, l.root().info(),
                com.legend.exec.ResultShape.of(l.root()));
    }

    /** The lowered plan plus what result shaping needs — shared by the plan
     *  surface and {@link #executeStreaming} (ONE phase sequence, audit 15). */
    private record Lowered(com.legend.sql.SqlQuery plan, TypedSpec root,
                           ModelContext ctx) {
    }

    private static Lowered lowerQuery(String model, String query,
            @com.legend.Nullable String runtime, boolean streaming) {
        ModelContext ctx = compileModel(model);
        SpecCompiler specs = new SpecCompiler(ctx);
        java.util.List<TypedSpec> body = specs.typeQueryBody(
                NameResolver.resolveQuery(SpecParser.parse(query,
                        com.legend.parser.Dialect.LEGEND_LITE)));
        body = new com.legend.compiler.spec.UserCallInliner(specs).inlineBody(body);   // Phase G½
        boolean temporalRoot = com.legend.compiler.element.Temporal
                .anyTemporalGetAll(body, ctx);
        body = new com.legend.resolver.StoreResolver(ctx, specs)
                .resolve(body, runtime);                          // Phase H
        TypedSpec root = body.get(body.size() - 1);
        com.legend.lowering.Lowerer planLw = new com.legend.lowering.Lowerer(
                t -> com.legend.compiler.element.ClassLayouts.layoutOf(ctx, t),
                f -> ctx.findClass(f).isPresent());
        if (!temporalRoot) {
            planLw = planLw.withEngineExistsJoinForm();
        }
        if (streaming) {
            planLw = planLw.withStreamingGraphRoot();
        }
        return new Lowered(planLw.lower(body), root, ctx);
    }

    /**
     * STREAMING execution — the {@link #planStreaming} lowering pushed all
     * the way through {@link com.legend.exec.Executor#stream}: JSON rows go
     * to {@code out} as they arrive from JDBC, O(one row) regardless of
     * result size. The dialect binds to the ACTUAL SESSION (the H5.4
     * reconciliation), unlike the plan-only surface which has no connection
     * to consult. {@code out} is flushed per row and never closed.
     */
    public static void executeStreaming(String model, String query,
            @com.legend.Nullable String runtimeFqn, java.sql.Connection connection,
            java.io.Writer out) throws java.sql.SQLException, java.io.IOException {
        Lowered l = lowerQuery(model, query, runtimeFqn, true);
        com.legend.sql.dialect.SqlDialect dialect =
                dialectOf(l.ctx(), runtimeFqn, connection);
        com.legend.exec.Executor.stream(dialect.render(l.plan()), l.plan(),
                l.root().info(), com.legend.exec.ResultShape.of(l.root()),
                connection, dialect, out);
    }

    /**
     * The runtime's SQL dialect: its connections' declared
     * {@code DatabaseType} selects the renderer; an undeclared type is LOUD.
     * A runtime with no relational connection binding (or no runtime at all
     * &mdash; the caller-supplied-connection path) defaults to DuckDB, the
     * reference dialect.
     */
    /**
     * H5.4 RECONCILIATION (H2_BACKEND.md §12 step 10): the dialect binds
     * to the ACTUAL SESSION, never to runtime metadata alone — a dialect
     * paired with a connection it does not render for is silent
     * corruption. An H2 session selects the H2 EXECUTION dialect and
     * requires every declared relational connection type to be H2 (LOUD
     * mismatch otherwise); any other session resolves exactly as before
     * (declared-H2-on-DuckDB stays the ANSI-subset DuckDB rendering —
     * today's reference path, unchanged).
     */
    static com.legend.sql.dialect.SqlDialect dialectOf(ModelContext ctx,
            @com.legend.Nullable String runtimeFqn,
            java.sql.Connection connection) throws java.sql.SQLException {
        String product = connection.getMetaData().getDatabaseProductName();
        if (!"H2".equals(product)) {
            return dialectOf(ctx, runtimeFqn);
        }
        if (runtimeFqn != null) {
            var rt = ctx.findRuntime(runtimeFqn);
            if (rt.isPresent()) {
                var bound = new java.util.TreeSet<String>();
                rt.get().connectionBindings().values().forEach(bound::addAll);
                for (String connFqn : bound) {
                    var decl = ctx.findConnection(connFqn);
                    if (decl.isPresent() && decl.get().databaseType()
                            != com.legend.model.ConnectionDefinition
                                    .DatabaseType.H2) {
                        throw new com.legend.error.NotImplementedException(
                                "session is H2 but connection '" + connFqn
                                + "' of runtime '" + runtimeFqn
                                + "' declares " + decl.get().databaseType()
                                + " — dialect/connection mismatch");
                    }
                }
            }
        }
        // CAPABILITY BY CONNECTED VERSION (the session-policy seam):
        // 2.3+ has typed-JSON navigation ((j)."f", 1-based [i]) — the
        // modern profile spells it natively; the 2.1 engine-parity
        // target keeps the walls.
        String ver = connection.getMetaData().getDatabaseProductVersion();
        return ver.startsWith("2.1") || ver.startsWith("2.2")
                ? new com.legend.sql.dialect.H2()
                : new com.legend.sql.dialect.H2Modern();
    }

    static com.legend.sql.dialect.SqlDialect dialectOf(ModelContext ctx,
            @com.legend.Nullable String runtimeFqn) {
        if (runtimeFqn == null) {
            return new com.legend.sql.dialect.DuckDb();
        }
        var rt = ctx.findRuntime(runtimeFqn);
        if (rt.isEmpty()) {
            return new com.legend.sql.dialect.DuckDb();
        }
        // EVERY binding is inspected, in sorted (deterministic) order —
        // connection bindings are an unordered map, and first-match-wins
        // was nondeterministic AND skipped later unsupported types (audit).
        var types = new java.util.TreeMap<String,
                com.legend.model.ConnectionDefinition.DatabaseType>();
        var bound = new java.util.TreeSet<String>();
        rt.get().connectionBindings().values().forEach(bound::addAll);
        for (String connFqn : bound) {
            var conn = ctx.findConnection(connFqn);
            if (conn.isEmpty()) {
                // a MODEL-store connection is defined but carries no
                // database type — it cannot vote on the dialect
                if (ctx.isModelConnection(connFqn)) {
                    continue;
                }
                throw new com.legend.error.MappingResolutionException(
                        "connection '" + connFqn + "' of runtime '" + runtimeFqn
                                + "' is not defined", runtimeFqn);
            }
            types.put(connFqn, conn.get().databaseType());
        }
        var distinct = new java.util.TreeSet<String>();
        for (var e : types.entrySet()) {
            switch (e.getValue()) {
                case DuckDB, SQLite -> distinct.add(e.getValue().name());
                // H2 rides the ANSI-flavored DuckDB renderer: the corpus
                // executes H2-typed connections on the session's DuckDB, and
                // every emission H2 sees is the ANSI subset.
                case H2 -> distinct.add("DuckDB");
                default -> throw new com.legend.error.NotImplementedException(
                        "SQL dialect for database type '" + e.getValue()
                                + "' (connection '" + e.getKey() + "' of runtime '"
                                + runtimeFqn + "') is not implemented yet");
            }
        }
        if (distinct.size() > 1) {
            throw new com.legend.error.NotImplementedException(
                    "runtime '" + runtimeFqn + "' mixes database types "
                            + distinct + " — one dialect per query is supported");
        }
        // SQLite differs from the ANSI baseline ONLY lexically — it is a
        // Lexicon row, not a dialect subclass (remediation T3.2).
        return distinct.contains("SQLite")
                ? new com.legend.sql.dialect.AnsiSqlRenderer(
                        com.legend.sql.dialect.Lexicon.SQLITE,
                        com.legend.sql.dialect.TypeNames.ANSI,
                        com.legend.sql.dialect.Spellings.DUCKDB)
                : new com.legend.sql.dialect.DuckDb();
    }

    /**
     * The core QUERY SERVICE: frontend + Phase G + lowering + rendering +
     * EXECUTION over the caller's connection, shaped per the result-type
     * classification ({@link com.legend.exec.ResultShape}). The corpus
     * bridge's target (PHASE_K_EXECUTION.md). Class queries need an
     * execution context in the query itself ({@code ->from(...)}) on this
     * overload; the 4-arg overload supplies a driver runtime.
     */
    public static com.legend.exec.@com.legend.Nullable ExecutionResult execute(
            String model, String query,
            java.sql.Connection connection) throws java.sql.SQLException {
        return execute(model, query, null, connection);
    }

    /**
     * The full pipeline with a DRIVER-SUPPLIED execution context — the
     * service shape: queries carry no {@code ->from(...)}; the runtime
     * arrives as an API argument (PHASE_K_EXECUTION.md §4). Phase H
     * resolves class queries against the runtime's mapping between G and
     * I; an explicit {@code from()} in the query always wins.
     */
    public static com.legend.exec.@com.legend.Nullable ExecutionResult execute(
            String model, String query,
            @com.legend.Nullable String runtimeFqn,
            java.sql.Connection connection) throws java.sql.SQLException {
        return execute(model, query, null, runtimeFqn, connection);
    }

    /**
     * {@link #execute(String, String, String, java.sql.Connection)} with a
     * SECTION import scope: the query resolves under {@code imports} (plus
     * the prelude) against the model's element universe — real pure's rule
     * for a query written in an import-bearing section. A {@code null}
     * scope is the sectionless-query behavior.
     */
    public static com.legend.exec.@com.legend.Nullable ExecutionResult execute(
            String model, String query,
            com.legend.model.@com.legend.Nullable ImportScope imports,
            @com.legend.Nullable String runtimeFqn,
            java.sql.Connection connection) throws java.sql.SQLException {
        ModelContext ctx = compileModel(model);
        return executeResolved(
                imports == null
                        ? NameResolver.resolveQuery(SpecParser.parse(query,
                                com.legend.parser.Dialect.LEGEND_LITE))
                        : NameResolver.resolveQuery(SpecParser.parse(query,
                                com.legend.parser.Dialect.LEGEND_LITE),
                                imports, ctx.elementFqns()),
                ctx, runtimeFqn, connection);
    }

    /**
     * Phases G&frac12;&rarr;K for an already NAME-RESOLVED query AST — THE
     * one back-half sequence. Every driver path (text queries above,
     * EngineTestExecutor's handle-splice path) comes through here; a second
     * hand-rolled sequence is an orchestrator bug (audit 15 unified two).
     */
    public static com.legend.exec.@com.legend.Nullable ExecutionResult executeResolved(
            com.legend.protocol.spec.ValueSpecification resolved, ModelContext ctx,
            @com.legend.Nullable String runtimeFqn,
            java.sql.Connection connection)
            throws java.sql.SQLException {
        return executeResolved(resolved, ctx, runtimeFqn, connection, null);
    }

    /**
     * Phases G&frac12;&rarr;I for an already NAME-RESOLVED query AST — the
     * SQL PLAN without execution (the {@code toSQLString} surface: the
     * caller renders with a dialect of its choosing and compares text).
     */
    /**
     * {@code relationalRootForm}: a BARE class root renders as the engine's
     * flat relational SELECT — primary-key columns ({@code pk_0}..) plus
     * the property leaves — instead of the platform's JSON envelope. The
     * engine assembles objects HOST-side from that flat select, so its
     * {@code toSQLString} goldens pin this form; execution paths never use
     * it (Java orchestrates, the database executes — the envelope stays).
     */
    public static com.legend.sql.SqlQuery lowerResolved(
            com.legend.protocol.spec.ValueSpecification resolved, ModelContext ctx,
            String runtimeFqn, boolean relationalRootForm) {
        SpecCompiler specs = new SpecCompiler(ctx);
        java.util.List<TypedSpec> body = specs.typeQueryBody(resolved);
        body = new com.legend.compiler.spec.UserCallInliner(specs).inlineBody(body);
        boolean temporalRoot = com.legend.compiler.element.Temporal
                .anyTemporalGetAll(body, ctx);
        body = new com.legend.resolver.StoreResolver(ctx, specs)
                .resolve(body, runtimeFqn);
        if (relationalRootForm) {
            body = com.legend.resolver.RelationalRootForm.apply(body, ctx);
        }
        com.legend.lowering.Lowerer lw = new com.legend.lowering.Lowerer(
                t -> com.legend.compiler.element.ClassLayouts.layoutOf(ctx, t),
                f -> ctx.findClass(f).isPresent());
        if (!temporalRoot) {
            lw = lw.withEngineExistsJoinForm();
        }
        return lw.lower(body);
    }

    /**
     * {@code rawSqlFailureSink}: OPTIONAL per-statement tolerance at the
     * {@code executeInDb} boundary — a failed raw statement is reported
     * to the sink and the setup CONTINUES (the engine's own harness
     * semantics: one dialect-incompatible INSERT must not abort the whole
     * seed; the caller's ledger feeds its emptiness guard). Null = throw.
     */
    public static com.legend.exec.@com.legend.Nullable ExecutionResult executeResolved(
            com.legend.protocol.spec.ValueSpecification resolved, ModelContext ctx,
            @com.legend.Nullable String runtimeFqn, java.sql.Connection connection,
            java.util.function.@com.legend.Nullable Consumer<String> rawSqlFailureSink)
            throws java.sql.SQLException {
        return StatementExecutor.execute(resolved, ctx,
                runtimeFqn, dialectOf(ctx, runtimeFqn, connection), connection,
                rawSqlFailureSink);
    }


    /**
     * EAGER G — the compileAll mode: type-check every user function BODY
     * in the module UP FRONT (the default path compiles lazily at call
     * sites, so a function nobody calls never surfaces its type errors).
     * Failures come back as a wall map keyed by overload signature, never
     * thrown — corpus-wide diagnostics for the construct taxonomy. Bodiless
     * (native) overloads are skipped; an FQN whose whole overload set is
     * signature-broken walls under the plain FQN.
     */
    public static java.util.Map<String, String> compileAllBodies(ModelContext ctx) {
        SpecCompiler specs = new SpecCompiler(ctx);
        java.util.Map<String, String> walls = new java.util.LinkedHashMap<>();
        for (String fqn : new java.util.TreeSet<>(ctx.functionFqns())) {
            java.util.List<com.legend.compiler.element.TypedFunction> overloads;
            try {
                overloads = ctx.findFunction(fqn);
            } catch (RuntimeException e) {
                walls.put(fqn, String.valueOf(e.getMessage()));
                continue;
            }
            for (com.legend.compiler.element.TypedFunction tf : overloads) {
                if (tf.body().isEmpty()) {
                    continue;
                }
                try {
                    specs.compile(tf);
                } catch (RuntimeException e) {
                    walls.put(tf.signatureKey(), String.valueOf(e.getMessage()));
                }
            }
        }
        return walls;
    }

    /**
     * Frontend + Phase G for a standalone query: Pure model source + query
     * expression &rarr; the query's typed HIR (the FRONT half only; use
     * {@link #execute} for the full pipeline).
     *
     * <p>The query is parsed by {@link SpecParser}, name-resolved under real
     * legend-engine's <em>sectionless-lambda</em> scope
     * ({@link NameResolver#resolveQuery}: the platform prelude is always in
     * scope &mdash; {@code JoinKind.INNER} works bare &mdash; while user
     * elements require full paths, e.g. {@code test::Person.all()}), then
     * type-checked against the compiled model snapshot.
     *
     * @param model Pure model source.
     * @param query Pure query expression (user elements fully qualified).
     * @return the type-checked query (schema/type on {@link TypedSpec#info()}).
     */
    public static TypedSpec compileQuery(String model, String query) {
        Objects.requireNonNull(query, "query");
        ModelContext ctx = compileModel(model);
        return new SpecCompiler(ctx).typeExpression(
                NameResolver.resolveQuery(SpecParser.parse(query,
                        com.legend.parser.Dialect.LEGEND_LITE)));
    }
}
