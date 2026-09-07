// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend.rcorpus;

import com.legend.Compiler;
import com.legend.compiler.element.ModelContext;
import com.legend.model.FunctionDefinition;
import com.legend.model.ImportScope;
import com.legend.model.PackageableElement;
import com.legend.model.ParsedModel;
import com.legend.model.StereotypeApplication;
import com.legend.protocol.spec.AppliedFunction;
import com.legend.protocol.spec.CBoolean;
import com.legend.protocol.spec.NewInstance;
import com.legend.protocol.spec.ValueSpecification;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * THE MINIMAL CORPUS HARNESS (docs/HARNESS_FROM_SCRATCH_AUDIT_2026_09_06.md,
 * rebuilt from the spec in its §1): six pieces and nothing else.
 *
 * <ol>
 * <li><b>find</b> — the engine's {@code core_relational} sources, parsed by
 *     the platform; a test is a {@code <<test.Test>>} function, a setup a
 *     {@code <<test.BeforePackage>>} function; the engine's own exclusion
 *     stereotypes ({@code ToFix}, {@code Ignore}, {@code ExcludeAlloy})
 *     are honoured;</li>
 * <li><b>assemble</b> — ONE model through {@link Compiler#parseSources} /
 *     {@link Compiler#buildModule}, every corpus database bound to one
 *     in-memory connection by an execution overlay;</li>
 * <li><b>seed + session</b> — one database workspace per test PACKAGE
 *     (the engine's grouping), the package's setups run THROUGH THE
 *     PLATFORM once per session, every raw SQL a body executes recorded
 *     for the referee (the platform's {@link
 *     com.legend.sql.dialect.RawSqlBoundary} ledger); a test that carries
 *     its own inline data runs on a private workspace;</li>
 * <li><b>run + judge</b> — the test body through the ONE production
 *     entry, {@link Compiler#executeResolved}, with the platform's
 *     {@link com.legend.exec.AssertListener}; every assert is the
 *     platform's verdict; an effectful body runs in a transaction that
 *     commits only on a pass;</li>
 * <li><b>referee</b> — {@link com.legend.harness.ReplayOracle#INSTANCE}
 *     (golden SQL and plan text brought to rows on the engine's own H2);</li>
 * <li><b>score</b> — PASS / FAIL per test with the failure's reason; the
 *     rosters are the deliverable.</li>
 * </ol>
 *
 * <p>There is no second executor, no recognized "test forms", no census.
 * A body the platform cannot run is a FAIL with the platform's own
 * message as its reason.
 */
public final class MinimalCorpus {

    /** One discovered test. */
    public record TestCase(String fqn, String pkg, FunctionDefinition fn,
            ImportScope imports) {}

    /** A test's outcome. SKIPPED (Phase 0.3): the body ran without a
     * failure but adjudicated NO verdict and the platform states the
     * program reaches no verdict function — such a test proves nothing
     * and is never counted as a pass (the engine's own serverless branch
     * of a {@code mayExecuteAlloyTest} shell is {@code | true}; a body
     * whose asserts are commented out; a placeholder body). */
    public enum Status { PASS, FAIL, SKIPPED }

    /** What a PASS proves (Phase 0.7, audit §3's ladder), derived from the
     * events the platform reported for the test — never from reading its
     * body: DIFFERENTIAL = the referee matched a rows leg against the
     * engine's golden (the strongest witness; whether a literal assert
     * also held is a second census); LITERAL = a value assert was judged
     * with no referee involved; CARDINALITY = only size / emptiness /
     * boolean asserts; SPELLING = every verdict was decided by text
     * (declined); NONE = no verdict (never a pass since batch 128). */
    public enum Strength { DIFFERENTIAL, LITERAL, CARDINALITY, SPELLING, NONE }

    /** One outcome with its reason. {@code verdicts} = assert verdicts
     * the platform reported. */
    public record Result(String fqn, Status status, int verdicts, String reason,
            Strength strength, boolean literalToo) {
        public Result(String fqn, Status status, int verdicts, String reason) {
            this(fqn, status, verdicts, reason, Strength.NONE, false);
        }

        public boolean pass() {
            return status == Status.PASS;
        }
    }

    /** The asserts that pin a COUNT or a boolean, not a value (the catalog's
     * assert family by exact FQN). */
    private static final Set<String> CARDINALITY_ASSERTS = Set.of(
            "meta::pure::functions::asserts::assert",
            "meta::pure::functions::asserts::assertFalse",
            "meta::pure::functions::asserts::assertSize",
            "meta::pure::functions::asserts::assertEmpty",
            "meta::pure::functions::asserts::assertNotEmpty");

    private static final String RUNTIME = "rcorpus::Rt";
    private static final String CONNECTION = "rcorpus::Conn";
    /** Corpus files that are the ENGINE'S IMPLEMENTATION of a platform-owned
     * function family, not test input: loaded, they would redefine the
     * family as user Pure and every test would resolve to the engine's
     * implementation instead of the platform's (measured, batch 134: with
     * this file admitted the 49 {@code lineage::scanRelations} tests inline
     * the engine's {@code scanRelations} and wall on {@code
     * openVariableValues}). The reference checkout is spec, never runtime
     * (user ruling 2026-08-28). Skipped BY NAME and reported — the file
     * defines no test (0 {@code <<test.Test>>}). */
    private static final Map<String, String> ENGINE_IMPLEMENTATION_FILES = Map.of(
            "lineage/scanRelations/scanRelations.pure",
            "the engine's implementation of the platform-owned meta::pure::lineage::scanRelations family");
    /** The engine-implementation files skipped, with their reason (reported). */
    private final List<String> engineImplementationSkips = new ArrayList<>();

    public List<String> engineImplementationSkips() {
        return List.copyOf(engineImplementationSkips);
    }

    private final ModelContext ctx;
    private final List<TestCase> tests = new ArrayList<>();
    /** {@code <<test.BeforePackage>>} functions by package. */
    private final Map<String, List<String>> setupsByPackage = new LinkedHashMap<>();
    /** Zero-arg functions of the SHARED fixture sources (the corpus-wide
     * setup — relationalSetUp.pure's createTablesAndFillDb family). */
    private final List<String> sharedSetups = new ArrayList<>();
    /** Library files skipped because they do not parse (reported). */
    private final List<String> libraryWalls = new ArrayList<>();
    /** Elements defined by library sources (model only, never tests). */
    private final Set<String> libraryElements = new LinkedHashSet<>();

    public List<String> libraryWalls() {
        return List.copyOf(libraryWalls);
    }

    private static final java.util.concurrent.atomic.AtomicInteger SESSION_IDS =
            new java.util.concurrent.atomic.AtomicInteger();

    // ---- FIND + ASSEMBLE --------------------------------------------------

    public MinimalCorpus() throws IOException {
        List<Compiler.ModelSource> shared = sharedSources();
        List<Compiler.ModelSource> all = new ArrayList<>(shared);
        Set<String> seen = new LinkedHashSet<>();
        for (Compiler.ModelSource s : shared) {
            seen.add(s.text());
        }
        for (Path f : corpusFiles()) {
            String rel = Corpus.RELATIONAL.relativize(f).toString();
            if (ENGINE_IMPLEMENTATION_FILES.containsKey(rel)) {
                engineImplementationSkips.add(rel + " — " + ENGINE_IMPLEMENTATION_FILES.get(rel));
                continue;
            }
            String text = Files.readString(f);
            if (seen.add(text)) {
                all.add(new Compiler.ModelSource(
                        Corpus.RELATIONAL.relativize(f).toString(), text));
            }
        }
        // LIBRARY sources are optional inputs (the platform's own M2M test
        // models, the graphFetch domain, two named engine files): one that
        // does not parse is skipped BY NAME — reported, never silent
        for (Path f : libraryFiles()) {
            String text = Files.readString(f);
            if (!seen.add(text)) {
                continue;
            }
            Compiler.ModelSource src = new Compiler.ModelSource(
                    "library/" + f.getFileName(), text);
            List<String> walls = new ArrayList<>();
            Compiler.parseSources(List.of(src), (name, err) -> walls.add(err),
                    com.legend.parser.Dialect.LEGEND_PLATFORM);
            if (walls.isEmpty()) {
                // library sources contribute MODEL only: their own test
                // functions (the platform's M2M suites) are not this corpus
                Compiler.ParsedModule one = Compiler.parseSources(List.of(src),
                        (name, err) -> { }, com.legend.parser.Dialect.LEGEND_PLATFORM);
                refusePlatformNamespace(one.model().elements());
                all.add(src);
                for (PackageableElement el : one.model().elements()) {
                    libraryElements.add(el.qualifiedName());
                }
            } else {
                libraryWalls.add(f.getFileName() + " => " + walls.get(0));
            }
        }
        List<String> parseWalls = new ArrayList<>();
        Compiler.ParsedModule parsed = Compiler.parseSources(all,
                (name, err) -> parseWalls.add(name + " => " + err),
                com.legend.parser.Dialect.LEGEND_PLATFORM);
        // EVERY source (the corpus's own files included) is under the
        // platform-namespace guard: nothing the harness loads may define
        // the platform's stdlib
        refusePlatformNamespace(parsed.model().elements());
        if (!parseWalls.isEmpty()) {
            throw new IllegalStateException("corpus parse walls: " + parseWalls);
        }
        if (!parsed.duplicateElements().isEmpty()) {
            throw new IllegalStateException("corpus duplicate elements: "
                    + parsed.duplicateElements());
        }
        Compiler.BuiltModule built = Compiler.buildModule(parsed.model());
        Map<String, String> dbBindings = new LinkedHashMap<>();
        for (PackageableElement el : parsed.model().elements()) {
            if (el instanceof com.legend.model.DatabaseDefinition db) {
                dbBindings.put(db.qualifiedName(), CONNECTION);
            }
        }
        ctx = ((com.legend.compiler.element.PureModelContext) built.context())
                .withExecutionOverlay(
                        new com.legend.model.RuntimeDefinition(RUNTIME, List.of(),
                                dbBindings, List.of()),
                        new com.legend.model.ConnectionDefinition(CONNECTION, null,
                                H2_BACKEND
                                        ? com.legend.model.ConnectionDefinition.DatabaseType.H2
                                        : com.legend.model.ConnectionDefinition.DatabaseType.DuckDB,
                                new com.legend.model.ConnectionSpecification.InMemory(),
                                new com.legend.model.AuthenticationSpec.NoAuth()));
        discover(parsed.model());
        // the shared fixture's own zero-arg functions (parsed apart so
        // their FQNs are known without an element→source index)
        Compiler.ParsedModule sharedParsed = Compiler.parseSources(shared,
                (name, err) -> { }, com.legend.parser.Dialect.LEGEND_PLATFORM);
        for (PackageableElement el : sharedParsed.model().elements()) {
            // a TEST is never a setup, whatever its effects; a zero-arg
            // fixture function is a setup only when the PLATFORM says its
            // body has statement effects (Phase 0.8 — the arity rule alone
            // nominated testRuntime(), the type-inference maps, … as
            // "inert setups"; the fact decides, not the arity)
            if (el instanceof FunctionDefinition f && f.parameters().isEmpty()
                    && f.stereotypes().stream().noneMatch(st ->
                            st.stereotypeName().equals("Test"))) {
                ValueSpecification resolved = Compiler.resolveQuery(
                        List.of(new AppliedFunction(f.qualifiedName(), List.of())),
                        new ImportScope(List.of()), ctx);
                if (Compiler.hasStatementEffects(resolved, ctx)) {
                    sharedSetups.add(f.qualifiedName());
                    setupPrograms.put(f.qualifiedName(), resolved);
                }
            }
        }
    }

    private static List<Compiler.ModelSource> sharedSources() throws IOException {
        List<Compiler.ModelSource> out = new ArrayList<>();
        int i = 0;
        for (String rel : List.of("tests/testModel/simpleTestModel.pure",
                "tests/testModel/inheritanceTestModel.pure",
                "tests/relationalSetUp.pure", "relationalExtension.pure")) {
            out.add(new Compiler.ModelSource("shared-" + i++ + ".pure", Corpus.read(rel)));
        }
        return out;
    }

    private static List<Path> corpusFiles() throws IOException {
        try (Stream<Path> walk = Files.walk(Corpus.RELATIONAL)) {
            return walk.filter(f -> f.toString().endsWith(".pure") && Files.isRegularFile(f))
                    .sorted().toList();
        }
    }

    private static List<Path> libraryFiles() throws IOException {
        List<Path> out = new ArrayList<>();
        Path gfDomain = Corpus.ENGINE_ROOT.resolve(
                "legend-engine-core/legend-engine-core-pure/"
                + "legend-engine-pure-code-compiled-core/"
                + "src/main/resources/core/pure/graphFetch/domain");
        for (Path dir : List.of(Corpus.M2M_TESTS, gfDomain)) {
            if (Files.isDirectory(dir)) {
                try (Stream<Path> s = Files.walk(dir)) {
                    out.addAll(s.filter(f -> f.toString().endsWith(".pure")).sorted().toList());
                }
            }
        }
        for (Path lib : Corpus.LIBRARY_FILES) {
            if (Files.isRegularFile(lib)) {
                out.add(lib);
            }
        }
        return out;
    }

    private void discover(ParsedModel model) {
        for (PackageableElement el : model.elements()) {
            if (!(el instanceof FunctionDefinition f)
                    || libraryElements.contains(el.qualifiedName())) {
                continue;
            }
            String fqn = f.qualifiedName();
            int cut = fqn.lastIndexOf("::");
            String pkg = cut > 0 ? fqn.substring(0, cut) : "";
            boolean test = false;
            boolean excluded = false;
            boolean setup = false;
            for (StereotypeApplication st : f.stereotypes()) {
                if (!(st.profileName().equals("test")
                        || st.profileName().equals("meta::pure::profiles::test"))) {
                    continue;
                }
                switch (st.stereotypeName()) {
                    case "Test" -> test = true;
                    // the engine's own exclusions (PureTestHelperFramework
                    // satisfiesConditions: !ExcludeAlloy; ToFix is the
                    // corpus's disabled mark); the profile has no "Ignore"
                    // (legend-pure essential/tests/profile.pure) — batch 134
                    // deleted that dead arm
                    case "ToFix", "ExcludeAlloy" -> excluded = true;
                    case "BeforePackage" -> setup = true;
                    default -> { }
                }
            }
            if (setup && f.parameters().isEmpty()) {
                setupsByPackage.computeIfAbsent(pkg, k -> new ArrayList<>()).add(fqn);
            }
            if (test) {
                declaredTests++;
                if (excluded) {
                    excludedTests++;
                }
            }
            if (test && !excluded) {
                List<String> wildcards = new ArrayList<>();
                ImportScope own = model.elementImports().get(fqn);
                if (own != null) {
                    wildcards.addAll(own.wildcards());
                }
                if (!pkg.isEmpty() && !wildcards.contains(pkg)) {
                    wildcards.add(pkg);
                }
                tests.add(new TestCase(fqn, pkg, f, new ImportScope(wildcards)));
            }
        }
        // the engine suite's traversal order: package tree first, then name
        tests.sort((a, b) -> engineSuiteOrder(a.fqn(), b.fqn()));
    }

    public List<TestCase> tests() {
        return List.copyOf(tests);
    }

    /** The DENOMINATOR, re-derived from the model every run (Phase 0.8):
     * {@code declared} = every {@code <<test.Test>>} function the corpus
     * defines, {@code excluded} = those the engine's own stereotypes take
     * out (ToFix / ExcludeAlloy), {@code discovered} = the runnable rest.
     * The run prints the triple and pins it against a comment-stripped
     * text scan of the corpus tree, so a bigger or smaller corpus, or a
     * discovery rule that drops a test, is loud. */
    public record Census(int declared, int excluded, int discovered) {
    }

    private int declaredTests;
    private int excludedTests;

    public Census census() {
        return new Census(declaredTests, excludedTests, tests.size());
    }

    // ---- SESSION + SEED ---------------------------------------------------

    private Connection sessionConn;
    private Connection mirrorConn;
    private String sessionPkg;
    private final Set<String> setupsDone = new LinkedHashSet<>();
    /** The session's non-query statements so far — the referee's seed
     * ledger prefix for every later test of the session. */
    private final List<com.legend.sql.dialect.RawSqlBoundary.Raw> seedLedger = new ArrayList<>();
    /** Each setup's resolved program (or {@link #INERT_SETUP} when the
     * platform says its body has no effects) — derived once. */
    private final java.util.Map<String, ValueSpecification> setupPrograms = new java.util.HashMap<>();
    private static final ValueSpecification INERT_SETUP = new CBoolean(true);

    /** {@code -Drcorpus.backend=h2}: the PORTABILITY lane — every session is
     * a fresh in-memory H2 with the engine's session settings instead of a
     * DuckDB workspace; the platform's dialect follows the connection. */
    static final boolean H2_BACKEND =
            "h2".equalsIgnoreCase(System.getProperty("rcorpus.backend", ""));

    private static Connection openSession() throws SQLException {
        if (H2_BACKEND) {
            Connection h2 = DriverManager.getConnection("jdbc:h2:mem:c2s"
                    + SESSION_IDS.getAndIncrement() + com.legend.exec.H2Settings.SETTINGS,
                    "sa", "");
            // the engine's H2 test database carries its extension functions;
            // on this lane the golden runs on THIS session (same-session
            // oracle), so the session carries them too (Phase 0.6: a golden
            // calling legend_h2_extension_lpad was a referee FAULT here)
            try (java.sql.Statement st = h2.createStatement()) {
                for (String alias : com.legend.harness.H2ExtensionFunctions.aliases()) {
                    st.execute(alias);
                }
            }
            return h2;
        }
        return DuckWorkspaces.open();
    }

    private void beginSession(String pkg) throws SQLException {
        endSession();
        sessionConn = openSession();
        // the referee's H2 mirror replays goldens beside a DuckDB session; an
        // H2 session IS the oracle's engine and needs no mirror
        if (!H2_BACKEND && com.legend.harness.H2Verify.ready()) {
            mirrorConn = DriverManager.getConnection("jdbc:h2:mem:c2Mirror"
                    + SESSION_IDS.getAndIncrement() + com.legend.exec.H2Settings.SETTINGS,
                    "sa", "");
            com.legend.harness.ReplayOracle.mirrorBegin(mirrorConn);
        }
        sessionPkg = pkg;
        setupsDone.clear();
        seedLedger.clear();
        deriveSetups(pkg);
    }

    public void endSession() {
        com.legend.harness.ReplayOracle.mirrorEnd();
        for (Connection c : new Connection[] {mirrorConn, sessionConn}) {
            if (c != null) {
                try {
                    c.close();
                } catch (SQLException ignored) {
                    // a session that fails to close cannot poison the next
                }
            }
        }
        mirrorConn = null;
        sessionConn = null;
        sessionPkg = null;
    }

    /** The setups a package inherits: the shared fixture units and every
     * BeforePackage of a package that prefixes it, outermost first. */
    private List<String> setupCandidates(String pkg) {
        List<String> candidates = new ArrayList<>(sharedSetups);
        List<String> pkgs = new ArrayList<>(setupsByPackage.keySet());
        pkgs.sort(java.util.Comparator.comparingInt(String::length));
        for (String p : pkgs) {
            if (pkg.equals(p) || pkg.startsWith(p + "::")) {
                candidates.addAll(setupsByPackage.get(p));
            }
        }
        return new ArrayList<>(new LinkedHashSet<>(candidates));
    }

    /** A setup's resolved program and its effect verdict are facts about
     * the MODEL: derived once per setup, at session start — so nothing
     * resolves between a test's own resolution and its execution (the
     * front door's per-query execution option is a thread-local today;
     * owed: the option rides the program through an execute overload with
     * the engine's own execution-context argument, bound by the reader). */
    private void deriveSetups(String pkg) {
        for (String fqn : setupCandidates(pkg)) {
            setupPrograms.computeIfAbsent(fqn, f -> {
                ValueSpecification resolved = Compiler.resolveQuery(
                        List.of(new AppliedFunction(f, List.of())), new ImportScope(List.of()), ctx);
                if (Compiler.hasStatementEffects(resolved, ctx)) {
                    return resolved;
                }
                inertSetups.add(f);
                return INERT_SETUP;
            });
        }
    }

    /** Verdicts DECIDED BY TEXT, as the platform reported them (Phase 0.6):
     * {@code reason + ' ' + test} → count; the run prints and pins them. */
    private final Map<String, Integer> textDecided = new LinkedHashMap<>();

    public Map<String, Integer> textDecided() {
        return java.util.Collections.unmodifiableMap(textDecided);
    }

    /** Setups the platform derived as INERT (no statement effects) and so
     * never ran — counted and pinned by the run (Phase 0.2): a platform
     * effect analysis that wrongly reads a seeding setup as inert would
     * silently unseed its package. */
    private final Set<String> inertSetups = new LinkedHashSet<>();

    public Set<String> inertSetups() {
        return java.util.Collections.unmodifiableSet(inertSetups);
    }

    private List<String> runSetups(TestCase t, Connection conn, boolean shared,
            com.legend.ExecuteOptions options) {
        List<String> failures = new ArrayList<>();
        for (String fqn : setupCandidates(t.pkg())) {
            if (shared && setupsDone.contains(fqn)) {
                continue;
            }
            ValueSpecification call = java.util.Objects.requireNonNull(
                    setupPrograms.get(fqn), "setup derived at session start");
            if (call == INERT_SETUP) {
                continue;
            }
            try {
                Compiler.executeResolved(call, ctx, RUNTIME, conn, null, null, options);
                if (shared) {
                    setupsDone.add(fqn);
                }
            } catch (RuntimeException e) {
                failures.add("setup " + fqn + "() => " + whole(e.getMessage()));
            }
        }
        return failures;
    }

    // ---- RUN + JUDGE --------------------------------------------------------

    /** The referee's declines and verdict roster name the test they belong
     * to (H2Verify.CURRENT_TEST — display attribution only, no verdict
     * flows through it). */
    public Result run(TestCase t) throws SQLException {
        com.legend.harness.H2Verify.CURRENT_TEST.set(t.fqn());
        try {
            return run0(t);
        } finally {
            com.legend.harness.H2Verify.CURRENT_TEST.remove();
        }
    }

    private Result run0(TestCase t) throws SQLException {
        if (!t.pkg().equals(sessionPkg)) {
            beginSession(t.pkg());
        }
        List<ValueSpecification> body = t.fn().body();
        // the platform's facts about the program decide the session: a test
        // that seeds inline CSV data gets a private workspace
        ValueSpecification resolved;
        com.legend.ProgramFacts facts;
        try {
            resolved = Compiler.resolveQuery(List.copyOf(body), t.imports(), ctx);
        } catch (RuntimeException e) {
            return new Result(t.fqn(), Status.FAIL, 0, "resolve: " + whole(e.getMessage()));
        }
        try {
            facts = Compiler.programFacts(resolved, ctx);
        } catch (RuntimeException e) {
            return new Result(t.fqn(), Status.FAIL, 0, "type: " + whole(e.getMessage()));
        }
        boolean shared = !facts.seedsInlineCsv();
        com.legend.harness.ReplayOracle.mirrorSuspend(!shared);
        Connection conn = shared ? sessionConn : openSession();
        // the raw-SQL ledger of THIS test (Phase 2b): the session's seed
        // prefix, then everything the setups and the body execute; the
        // executor appends through the options, the referee reads it
        com.legend.sql.dialect.RawSqlBoundary.Recorder recorder =
                new com.legend.sql.dialect.RawSqlBoundary.Recorder(
                        shared ? seedLedger : List.of());
        // the test-input resource resolver rides the same options (Phase 2d)
        com.legend.ExecuteOptions options = com.legend.ExecuteOptions.recording(recorder, path -> {
            try {
                return Files.readString(Corpus.RELATIONAL.getParent().getParent()
                        .resolve(path.startsWith("/") ? path.substring(1) : path));
            } catch (IOException e) {
                throw new com.legend.error.DataError("test resource '" + path + "'", e);
            }
        });
        com.legend.harness.ReplayOracle oracle = new com.legend.harness.ReplayOracle(recorder);
        try {
            // a setup that fails FAILS every test depending on it (Phase
            // 0.2): the engine's suite scores each BeforePackage function
            // as a test case of its own (PureTestBuilder.buildSuite), so a
            // failure there is scored, never tolerated; a body judged on a
            // half-seeded session is no verdict
            List<String> setupFailures = runSetups(t, conn, shared, options);
            if (!setupFailures.isEmpty()) {
                return new Result(t.fqn(), Status.FAIL, 0,
                        "setup failed: " + String.join("; ", setupFailures));
            }
            return judge(t, resolved, facts, conn, options, oracle);
        } finally {
            com.legend.harness.ReplayOracle.mirrorSuspend(false);
            if (shared) {
                seedLedger.clear();
                for (var stmt : recorder.entries()) {
                    if (!stmt.query()) {
                        seedLedger.add(stmt);
                    }
                }
            } else {
                try {
                    conn.close();
                } catch (SQLException ignored) {
                    // private workspace; nothing depends on it after this
                }
            }
        }
    }

    private Result judge(TestCase t, ValueSpecification resolved,
            com.legend.ProgramFacts facts, Connection conn,
            com.legend.ExecuteOptions options, com.legend.harness.ReplayOracle oracle)
            throws SQLException {
        boolean effectful = facts.effects();
        List<Boolean> verdicts = new ArrayList<>();
        List<String> failedAsserts = new ArrayList<>();
        // the strength ledger of this test: what judged each assert
        List<String> assertNames = new ArrayList<>();
        // keyed by the assert's INDEX (the arm reports before it decides, so
        // the upcoming verdict's index is verdicts.size()); the arm's short
        // name and the listener's FQN differ
        Set<Integer> declinedAsserts = new LinkedHashSet<>();
        Set<Integer> refereedAsserts = new LinkedHashSet<>();
        boolean[] refereeMatched = {false};
        com.legend.sql.dialect.RawSqlBoundary.Recorder.Mark mark = null;
        if (effectful) {
            mark = oracle.beginAttempt(conn);
        }
        boolean committed = false;
        try {
            String failure = null;
            try {
                Compiler.executeResolved(resolved, ctx, RUNTIME, conn,
                        new com.legend.exec.AssertListener() {
                            @Override
                            public void verdict(String name, boolean pass,
                                    @com.legend.Nullable String detail) {
                                verdicts.add(pass);
                                assertNames.add(name);
                                if (!pass) {
                                    failedAsserts.add("#" + verdicts.size() + " " + name
                                            + (detail == null ? "" : ": " + whole(detail)));
                                }
                            }

                            @Override
                            public void declined(String name, String reason) {
                                // a text-decided verdict, named by the arm (Phase 0.6)
                                textDecided.merge(reason + " " + t.fqn(), 1, Integer::sum);
                                declinedAsserts.add(verdicts.size());
                            }

                            @Override
                            public void refereed(String name, String outcome) {
                                refereedAsserts.add(verdicts.size());
                                if ("MATCH".equals(outcome)) {
                                    refereeMatched[0] = true;
                                }
                            }
                        },
                        oracle, options);
            } catch (RuntimeException e) {
                if (System.getenv("LEGEND_LITE_STACKS") != null) {
                    e.printStackTrace();
                }
                failure = e.getClass().getSimpleName() + ": " + whole(e.getMessage());
            }
            if (failure == null && !failedAsserts.isEmpty()) {
                failure = "assert " + failedAsserts.get(0);
            }
            if (failure == null && verdicts.isEmpty() && facts.verdicts()) {
                failure = "no verdict: the body calls an assert the platform"
                        + " did not adjudicate";
            }
            if (failure == null) {
                if (effectful) {
                    // the session state the body produced is what the
                    // engine's run leaves too — kept whether or not the
                    // body adjudicated anything
                    com.legend.harness.ReplayOracle.commitAttempt(conn);
                    committed = true;
                }
                if (verdicts.isEmpty()) {
                    // facts.verdicts() is false here (true + no verdict
                    // failed above): the program reaches no verdict
                    // function — nothing was proved
                    return new Result(t.fqn(), Status.SKIPPED, 0,
                            "no assertion reachable (the program calls no verdict function)");
                }
                // the strength ladder (Phase 0.7): counts of what judged the
                // asserts — a text-decided assert is the one whose decline
                // preceded its verdict (the arm reports before it decides)
                int textDecidedCount = 0;
                int cardinality = 0;
                int literal = 0;
                for (int i = 0; i < assertNames.size(); i++) {
                    String an = assertNames.get(i);
                    if (declinedAsserts.contains(i)) {
                        textDecidedCount++;
                    } else if (refereedAsserts.contains(i)) {
                        continue;          // judged by the referee's rows, not a literal
                    } else if (CARDINALITY_ASSERTS.contains(an)) {
                        cardinality++;
                    } else {
                        literal++;
                    }
                }
                Strength strength = refereeMatched[0] ? Strength.DIFFERENTIAL
                        : literal > 0 ? Strength.LITERAL
                        : cardinality > 0 ? Strength.CARDINALITY
                        : Strength.SPELLING;
                return new Result(t.fqn(), Status.PASS, verdicts.size(),
                        verdicts.size() + " verdict(s) " + strength, strength,
                        literal > 0 || cardinality > 0);
            }
            return new Result(t.fqn(), Status.FAIL, verdicts.size(), failure);
        } finally {
            if (effectful && !committed) {
                oracle.rollbackAttempt(conn,
                        java.util.Objects.requireNonNull(mark, "mark"));
            }
        }
    }

    // ---- small structural helpers ------------------------------------------

    /** The test carries its OWN data ({@code testDataSetupCsv} on an
     * instance in its body): the engine runs it on a fresh database. */
    /** THE PLATFORM-NAMESPACE GUARD (user catch 2026-08-28): reference
     * checkouts are SPEC and test input, never runtime components. A
     * library source defining {@code meta::pure::functions::} elements
     * would compile the reference stdlib into our model — refused LOUDLY.
     * Test-fixture models (corpus test classes, engine test domains) load:
     * they are the thing under test, not the thing judging. */
    static void refusePlatformNamespace(List<? extends PackageableElement> elements) {
        for (PackageableElement el : elements) {
            if (el.qualifiedName().startsWith(PLATFORM_STDLIB_PACKAGE)) {
                throw new IllegalStateException("platform-namespace library element "
                        + el.qualifiedName() + ": reference checkouts are spec, never runtime");
            }
        }
    }

    private static final String PLATFORM_STDLIB_PACKAGE = "meta::pure::functions::";

    /** PureTestBuilder.buildSuite's traversal as a comparator: compare
     *  package segments; at the first divergence sort alphabetically;
     *  an ANCESTOR package's own tests run AFTER its sub-suites (deeper
     *  fqn first); same package sorts by test name. */
    static int engineSuiteOrder(String fqnA, String fqnB) {
        String[] a = fqnA.split("::");
        String[] b = fqnB.split("::");
        int i = 0;
        while (i < a.length - 1 && i < b.length - 1 && a[i].equals(b[i])) {
            i++;
        }
        if (i < a.length - 1 && i < b.length - 1) {
            return a[i].compareTo(b[i]);
        }
        if (a.length == b.length) {
            return a[a.length - 1].compareTo(b[b.length - 1]);
        }
        return a.length < b.length ? 1 : -1;
    }




    /** The platform's message, WHOLE, on one line: the harness never
     * truncates what the platform said (Phase 0.2 — 21 of 121 roster
     * entries carried no diagnostic because the first line of an assert
     * failure is its name and the expected/actual lines followed). Lines
     * join with {@code " | "} so every FAIL stays one greppable line. */
    static String whole(@com.legend.Nullable String s) {
        if (s == null) {
            return "null";
        }
        return s.strip().replaceAll("\\s*\\R\\s*", " | ");
    }
}
