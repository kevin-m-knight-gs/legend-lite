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

    /** One verdict: pass, or the reason. {@code verdicts} = assert
     * verdicts the platform reported. */
    public record Result(String fqn, boolean pass, int verdicts, String reason) {}

    private static final String RUNTIME = "rcorpus::Rt";
    private static final String CONNECTION = "rcorpus::Conn";
    private static final String ASSERTS_PACKAGE = "meta::pure::functions::asserts::";
    private static final Set<String> ENGINE_IMPLEMENTATION_FILES = Set.of(
            "lineage/scanRelations/scanRelations.pure");

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
            // a TEST is never a setup, whatever its effects
            if (el instanceof FunctionDefinition f && f.parameters().isEmpty()
                    && f.stereotypes().stream().noneMatch(st ->
                            st.stereotypeName().equals("Test"))) {
                sharedSetups.add(f.qualifiedName());
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
                    .filter(f -> !ENGINE_IMPLEMENTATION_FILES.contains(
                            Corpus.RELATIONAL.relativize(f).toString()))
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
                    case "ToFix", "Ignore", "ExcludeAlloy" -> excluded = true;
                    case "BeforePackage" -> setup = true;
                    default -> { }
                }
            }
            if (setup && f.parameters().isEmpty()) {
                setupsByPackage.computeIfAbsent(pkg, k -> new ArrayList<>()).add(fqn);
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

    // ---- SESSION + SEED ---------------------------------------------------

    private Connection sessionConn;
    private Connection mirrorConn;
    private String sessionPkg;
    private final Set<String> setupsDone = new LinkedHashSet<>();
    /** The session's non-query statements so far — the referee's seed
     * ledger prefix for every later test of the session. */
    private final List<String> seedLedger = new ArrayList<>();
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
            return DriverManager.getConnection("jdbc:h2:mem:c2s"
                    + SESSION_IDS.getAndIncrement() + com.legend.exec.H2Settings.SETTINGS,
                    "sa", "");
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

    /** The setups a test's package inherits: the shared fixture units and
     * every BeforePackage of a package that prefixes the test's, outermost
     * first — each run at most once per session, and only when the
     * platform says its body has effects. Failures are reported, not
     * fatal (the engine's harness tolerance). */
    private List<String> runSetups(TestCase t, Connection conn, boolean shared) {
        List<String> failures = new ArrayList<>();
        List<String> candidates = new ArrayList<>(sharedSetups);
        List<String> pkgs = new ArrayList<>(setupsByPackage.keySet());
        pkgs.sort(java.util.Comparator.comparingInt(String::length));
        for (String p : pkgs) {
            if (t.pkg().equals(p) || t.pkg().startsWith(p + "::")) {
                candidates.addAll(setupsByPackage.get(p));
            }
        }
        Set<String> seen = new LinkedHashSet<>();
        for (String fqn : candidates) {
            if (!seen.add(fqn) || (shared && setupsDone.contains(fqn))) {
                continue;
            }
            // a setup's resolved program and its effect verdict are facts
            // about the MODEL: derived once per setup, never per test
            ValueSpecification call = setupPrograms.computeIfAbsent(fqn, f -> {
                ValueSpecification resolved = Compiler.resolveQuery(
                        List.of(new AppliedFunction(f, List.of())), new ImportScope(List.of()), ctx);
                return Compiler.hasStatementEffects(resolved, ctx) ? resolved : INERT_SETUP;
            });
            if (call == INERT_SETUP) {
                continue;
            }
            try {
                Compiler.executeResolved(call, ctx, RUNTIME, conn);
                if (shared) {
                    setupsDone.add(fqn);
                }
            } catch (RuntimeException e) {
                failures.add("setup " + fqn + "() => " + firstLine(e.getMessage()));
            }
        }
        return failures;
    }

    // ---- RUN + JUDGE --------------------------------------------------------

    public Result run(TestCase t) throws SQLException {
        if (!t.pkg().equals(sessionPkg)) {
            beginSession(t.pkg());
        }
        List<ValueSpecification> body = t.fn().body();
        if (body.size() == 1 && body.get(0) instanceof CBoolean cb && cb.value()) {
            return new Result(t.fqn(), true, 0, "vacuous (engine body = true)");
        }
        boolean shared = !carriesInlineCsv(body);
        com.legend.harness.ReplayOracle.mirrorSuspend(!shared);
        Connection conn = shared ? sessionConn : openSession();
        List<String> recording = new ArrayList<>();
        if (shared) {
            recording.addAll(seedLedger);
        }
        com.legend.sql.dialect.RawSqlBoundary.record(recording);
        com.legend.exec.TestResources.register(path -> {
            try {
                return Files.readString(Corpus.RELATIONAL.getParent().getParent()
                        .resolve(path.startsWith("/") ? path.substring(1) : path));
            } catch (IOException e) {
                throw new com.legend.error.DataError("test resource '" + path + "'", e);
            }
        });
        try {
            List<String> setupFailures = runSetups(t, conn, shared);
            Result r = judge(t, body, conn);
            if (!setupFailures.isEmpty()) {
                r = new Result(r.fqn(), r.pass(), r.verdicts(),
                        r.reason() + " [setup: " + String.join("; ", setupFailures) + "]");
            }
            return r;
        } finally {
            com.legend.harness.ReplayOracle.mirrorSuspend(false);
            if (shared) {
                seedLedger.clear();
                for (String stmt : recording) {
                    if (!isQuery(stmt)) {
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

    private Result judge(TestCase t, List<ValueSpecification> body, Connection conn)
            throws SQLException {
        ValueSpecification resolved;
        try {
            resolved = Compiler.resolveQuery(List.copyOf(body), t.imports(), ctx);
        } catch (RuntimeException e) {
            return new Result(t.fqn(), false, 0, "resolve: " + firstLine(e.getMessage()));
        }
        boolean effectful;
        try {
            effectful = Compiler.hasStatementEffects(resolved, ctx);
        } catch (RuntimeException e) {
            return new Result(t.fqn(), false, 0, "type: " + firstLine(e.getMessage()));
        }
        List<Boolean> verdicts = new ArrayList<>();
        List<String> failedAsserts = new ArrayList<>();
        com.legend.sql.dialect.RawSqlBoundary.LedgerMark mark = null;
        if (effectful) {
            mark = com.legend.harness.ReplayOracle.beginAttempt(conn);
        }
        boolean committed = false;
        try {
            String failure = null;
            try {
                Compiler.executeResolved(resolved, ctx, RUNTIME, conn,
                        (name, pass, detail) -> {
                            verdicts.add(pass);
                            if (!pass) {
                                failedAsserts.add("#" + verdicts.size() + " " + name
                                        + (detail == null ? "" : ": " + firstLine(detail)));
                            }
                        },
                        com.legend.harness.ReplayOracle.INSTANCE);
            } catch (RuntimeException e) {
                if (System.getenv("LEGEND_LITE_STACKS") != null) {
                    e.printStackTrace();
                }
                failure = e.getClass().getSimpleName() + ": " + firstLine(e.getMessage());
            }
            if (failure == null && !failedAsserts.isEmpty()) {
                failure = "assert " + failedAsserts.get(0);
            }
            if (failure == null && verdicts.isEmpty() && callsAssert(resolved)) {
                failure = "no verdict: the body calls an assert the platform"
                        + " did not adjudicate";
            }
            if (failure == null) {
                if (effectful) {
                    com.legend.harness.ReplayOracle.commitAttempt(conn);
                    committed = true;
                }
                return new Result(t.fqn(), true, verdicts.size(),
                        verdicts.isEmpty() ? "ran, no asserts" : verdicts.size() + " verdict(s)");
            }
            return new Result(t.fqn(), false, verdicts.size(), failure);
        } finally {
            if (effectful && !committed) {
                com.legend.harness.ReplayOracle.rollbackAttempt(conn,
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

    private static boolean carriesInlineCsv(List<ValueSpecification> body) {
        java.util.ArrayDeque<ValueSpecification> q = new java.util.ArrayDeque<>(body);
        while (!q.isEmpty()) {
            ValueSpecification v = q.poll();
            if (v instanceof NewInstance ni && ni.first("testDataSetupCsv") != null) {
                return true;
            }
            q.addAll(v.children());
        }
        return false;
    }

    /** Whether the RESOLVED body calls an assert-family function (exact
     * package of the engine's assert natives). */
    private static boolean callsAssert(ValueSpecification resolved) {
        java.util.ArrayDeque<ValueSpecification> q = new java.util.ArrayDeque<>();
        q.add(resolved);
        while (!q.isEmpty()) {
            ValueSpecification v = q.poll();
            if (v instanceof AppliedFunction af
                    && af.function().startsWith(ASSERTS_PACKAGE)) {
                return true;
            }
            q.addAll(v.children());
        }
        return false;
    }

    private static boolean isQuery(String stmt) {
        String head = stmt.stripLeading();
        head = head.substring(0, Math.min(7, head.length())).toUpperCase(java.util.Locale.ROOT);
        return head.startsWith("SELECT") || head.startsWith("WITH")
                || head.startsWith("SHOW") || head.startsWith("EXPLAIN");
    }

    private static String firstLine(@com.legend.Nullable String s) {
        if (s == null) {
            return "null";
        }
        int nl = s.indexOf('\n');
        return nl < 0 ? s : s.substring(0, nl);
    }
}
