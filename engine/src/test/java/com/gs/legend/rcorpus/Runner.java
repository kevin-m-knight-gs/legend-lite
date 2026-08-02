// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.gs.legend.rcorpus;


import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Executes real core_relational {@code <<test.Test>>} functions against
 * legend-lite + DuckDB and scores them. RUN-as-data: the test body's
 * {@code execute(|query, mapping, runtime, extensions)} supplies the query
 * and mapping; the assertions supply expected rows; golden SQL asserts are
 * ADVISORY (row equality is the contract; our SQL is DuckDB's, not H2's).
 *
 * <p>FAULT-ISOLATED assembly: shared model elements load together; each
 * MAPPING is probe-compiled and dropped (recorded as a WALL) if it uses a
 * roadmap feature — tests over working mappings still run.
 */
public final class Runner {

    /** {@code -Drcorpus.backend=h2}: the PORTABILITY SWEEP (H2_BACKEND.md
     * §12 step 10) — every test opens a FRESH in-memory H2 with the
     * engine's session settings instead of DuckDB; the dialect follows
     * the session at the Compiler H5.4 seam, and raw corpus H2 executes
     * natively (no boundary translation). The DuckDB reference path is
     * byte-for-byte untouched when the flag is absent. */
    static final boolean H2_BACKEND =
            "h2".equalsIgnoreCase(System.getProperty("rcorpus.backend", ""));
    private static final java.util.concurrent.atomic.AtomicInteger
            SESSION_IDS = new java.util.concurrent.atomic.AtomicInteger();

    /** ONE session factory — dialect and connection bind together. */
    static Connection openSession() throws java.sql.SQLException {
        if (H2_BACKEND) {
            return DriverManager.getConnection("jdbc:h2:mem:rcorpus"
                    + SESSION_IDS.getAndIncrement()
                    + com.legend.harness.H2Verify.SETTINGS, "sa", "");
        }
        Connection conn = DriverManager.getConnection("jdbc:duckdb:");
        try (var st = conn.createStatement()) {
            st.execute("SET TimeZone='UTC'");
            st.execute("SET threads=1");
        }
        return conn;
    }

    /** Class-FQN -> defining file (the corpus-wide index, incl. the M2M
     * platform test root) — set by the runner harness; null = disabled. */
    public java.util.function.Function<String, java.nio.file.Path> classLookup;

    public record Outcome(String test, Status status, String detail,
            int sqlDiffs) {
        /** Non-PASS / non-Ran outcomes carry no SQL-diff channel. */
        public Outcome(String test, Status status, String detail) {
            this(test, status, detail, 0);
        }
    }

    /** UNSUPPORTED is a PORTABILITY-SWEEP-ONLY outcome (H2_BACKEND.md
     * §10): the renderer threw a typed {@code DialectCapability} wall —
     * honest, expected, budget-counted. The DuckDB scoreboard never
     * produces it. */
    public enum Status { PASS, FAIL, ERROR, SHAPE, UNSUPPORTED }

    private final List<String> walls = new ArrayList<>();
    /** Shared-file table DDL — replayed FIRST, before ANY data. */
    /** Shared-file executeInDb data literals — replayed after ALL DDL. */
    /**
     * FQNs of every zero-arg function defined in the shared seed files:
     * their body literals already ride the shared replay, so BeforePackage
     * expansion must not run them a SECOND time. Run-once emulation at
     * FUNCTION granularity — the old statement-level dedup also destroyed
     * deliberately repeated inserts feeding distinct() tests (audit A1) and
     * silently swallowed post-REPLACE refills (audit A2).
     */
    /** {@code <<test.BeforePackage>>} setups collected corpus-wide. */
    // ===== MODULE assembly (Phase B): raw sources through the real
    // parser — the text-extraction path below it is being retired =====
    private final List<com.legend.Compiler.ModelSource> sharedRaw = new ArrayList<>();
    private final Map<String, List<com.legend.Compiler.ModelSource>> familyRaw =
            new LinkedHashMap<>();
    private final Map<String, com.legend.Compiler.ModelSource> fileRaw =
            new LinkedHashMap<>();
    private final Map<String, String> familyParent = new LinkedHashMap<>();
    private com.legend.Compiler.@com.legend.Nullable BuiltModule globalBuilt;
    private com.legend.Compiler.@com.legend.Nullable ParsedModule globalParsed;
    private java.util.Map<String, String> allDbBindings = java.util.Map.of();
    private com.legend.model.@com.legend.Nullable ConnectionDefinition rcorpusConn;
    private final Map<String, com.legend.compiler.element.ModelContext> overlayMemo =
            new LinkedHashMap<>();
    /** Databases whose DDL the CURRENT test's session replays. */
    private java.util.Set<String> currentDdlDbs = java.util.Set.of();
    private final java.util.Set<String> reportedModuleWalls = new java.util.HashSet<>();

    public Runner(List<String> sharedSources, List<String> seedSources) {
        for (int i = 0; i < sharedSources.size(); i++) {
            sharedRaw.add(new com.legend.Compiler.ModelSource(
                    "shared-" + i + ".pure", sharedSources.get(i)));
        }
        // shared files join the setup UNIVERSE too (relationalSetUp.pure
        // defines createTablesAndFillDb — cross-family setups call it)
        setupUniverse.addAll(sharedSources);
        // PHASE E: shared-file DATA seeds by EXECUTING the shared files'
        // functions through the platform, in definition order — the same
        // statement sequence the literal extraction produced. Functions
        // WITH parameters cannot be called, but their constant literals
        // replayed under the legacy extraction — the platform path's
        // gates give the identical silent-skip for the parameter-dependent
        // ones.
        for (String src : seedSources) {
            collectSetups(src);
            com.legend.model.ParsedModel unit;
            try {
                unit = com.legend.parser.ElementParser.parse(src);
            } catch (RuntimeException e) {
                continue;
            }
            for (com.legend.model.PackageableElement el : unit.elements()) {
                if (el instanceof com.legend.model.FunctionDefinition f) {
                    sharedSetupUnits.add(new SetupUnit(
                            f.qualifiedName(), f.parameters().isEmpty()));
                }
            }
        }
    }

    /** A shared-file function, definition order — executed for EVERY test
     * when zero-arg and effectful (each test gets a fresh in-memory db). */
    private record SetupUnit(String fqn, boolean zeroArg) {
    }

    private final List<SetupUnit> sharedSetupUnits = new ArrayList<>();


    // Phase D: setup functions as PARSED definitions — their bodies
    // EXECUTE through the platform (Compiler statement orchestration), no literal
    // extraction. beforePackagesParsed: {pkg, fqn} by STEREOTYPE.
    /** Every parsed corpus function: parameter names + body + imports —
     * the statement-position β-expansion index (audit 19d B1). */
    record FnDef(List<String> params,
            List<com.legend.model.spec.ValueSpecification> body,
            com.legend.model.ImportScope imports) {
    }

    private final Map<String, FnDef> fnIndex = new LinkedHashMap<>();

    private final Map<String, java.util.List<com.legend.model.spec.ValueSpecification>>
            setupFnAsts = new LinkedHashMap<>();
    private final Map<String, com.legend.model.ImportScope> setupFnImports =
            new LinkedHashMap<>();
    private final List<String[]> beforePackagesParsed = new ArrayList<>();
    private final java.util.Set<String> bpSeen = new java.util.HashSet<>();

    public void addBeforePackages(String source) {
        addBeforePackages(source, null);
    }

    /** A LIBRARY source (the platform m2m test tree): its elements join
     * {@code elementSource} so qualified references pull the defining
     * file into a module (executionPlan(..., simpleModelMapping..., ...)
     * — the testModelConnection* quintet), but its functions are NOT
     * corpus setups and never join the expansion index (library helpers
     * are not statement-position test helpers). */
    public void registerLibrarySource(String source) {
        com.legend.model.ParsedModel unit;
        try {
            unit = com.legend.parser.ElementParser.parse(source);
        } catch (RuntimeException e) {
            return;   // unparseable library file: its elements stay dark
        }
        for (com.legend.model.PackageableElement el : unit.elements()) {
            elementSource.putIfAbsent(el.qualifiedName(), source);
        }
        // the GLOBAL compile carries the library like the engine's own
        // graph does (the m2m test tree, pureToSQLQuery) — text-identical
        // corpus files dedup at assembly
        libraryRaw.add(new com.legend.Compiler.ModelSource(
                "library-" + libraryRaw.size() + ".pure", source));
    }

    private final List<com.legend.Compiler.ModelSource> libraryRaw =
            new ArrayList<>();

    /** {@code familyKey}: the corpus family this source belongs to — the
     * cross-family module pull unit (a family's files close over each
     * other's models; one file alone does not). */
    public void addBeforePackages(String source, String familyKey) {
        collectSetups(source);
        setupUniverse.add(source);
        if (familyKey != null) {
            sourceFamily.putIfAbsent(source, familyKey);
            familySources.computeIfAbsent(familyKey,
                    k -> new ArrayList<>()).add(source);
        }
    }

    private final Map<String, String> sourceFamily = new LinkedHashMap<>();
    private final Map<String, List<String>> familySources = new LinkedHashMap<>();

    /** Every scanned source, for the cross-family setup FALLBACK module. */
    private final java.util.LinkedHashSet<String> setupUniverse =
            new java.util.LinkedHashSet<>();

    /** FQN -> defining SOURCE for every parsed corpus element: module
     * assembly pulls the defining file when a test references a mapping
     * outside its family (graphFetch trees over the embedded family's
     * model — audit-17 bucket cluster). */
    private final Map<String, String> elementSource = new LinkedHashMap<>();
    private com.legend.compiler.element.ModelContext setupUniverseCtx;
    private int setupUniverseSize = -1;

    /** The whole-corpus TOLERANT module: setup functions reach across
     * family files (projection::setUp calls join's createTablesAndFillDb)
     * — the per-test module deliberately excludes foreign families, so a
     * setup that fails there retries here. Rebuilt only when new sources
     * arrived; duplicate FQNs are first-wins (module semantics). */
    private com.legend.compiler.element.ModelContext setupUniverseContext() {
        if (setupUniverseCtx == null || setupUniverseSize != setupUniverse.size()) {
            List<com.legend.Compiler.ModelSource> sources = new ArrayList<>();
            int i = 0;
            for (String src : setupUniverse) {
                // known parse walls (#50) stay out — one unparseable file
                // must not dark the whole setup universe
                try {
                    com.legend.parser.ElementParser.parse(src);
                } catch (RuntimeException unparseable) {
                    continue;
                }
                sources.add(new com.legend.Compiler.ModelSource(
                        "setup-" + (i++) + ".pure", src));
            }
            setupUniverseCtx = com.legend.Compiler.buildModule(
                    com.legend.Compiler.parseSources(sources).model()).context();
            setupUniverseSize = setupUniverse.size();
        }
        return setupUniverseCtx;
    }

    /** Parse a source and collect zero-arg function ASTs + BeforePackage
     * stereotyped functions (Phase D discovery — no regex). */
    private void collectSetups(String source) {
        com.legend.model.ParsedModel unit;
        try {
            unit = com.legend.parser.ElementParser.parse(source);
        } catch (RuntimeException e) {
            return;   // unparseable file: its tests are walled anyway
        }
        for (com.legend.model.PackageableElement el : unit.elements()) {
            elementSource.putIfAbsent(el.qualifiedName(), source);
            if (!(el instanceof com.legend.model.FunctionDefinition f)) {
                continue;
            }
            // EVERY parsed function joins the expansion index: test bodies
            // that reach execute()/toSQLString() through a HELPER call were
            // invisible to discovery AND to TestBody (audit 19d B1 — the
            // 498-SHAPE cliff). Statement-position calls β-expand with the
            // callee's parameters bound as lets.
            // OVERLOAD-aware key (fqn/arity): the corpus's wrapper idiom
            // declares a 2-arg assert helper delegating to a 3-arg one —
            // a bare-FQN key shadows the delegate (first-in wins)
            fnIndex.putIfAbsent(f.qualifiedName() + "/"
                    + f.parameters().size(), new FnDef(
                    f.parameters().stream()
                            .map(com.legend.model.FunctionDefinition.ParameterDefinition::name)
                            .toList(),
                    f.body(),
                    unit.elementImports().get(f.qualifiedName())));
            if (!f.parameters().isEmpty()) {
                continue;
            }
            setupFnAsts.putIfAbsent(f.qualifiedName(), f.body());
            var scope = unit.elementImports().get(f.qualifiedName());
            if (scope != null) {
                setupFnImports.putIfAbsent(f.qualifiedName(), scope);
            }
            boolean isBp = f.stereotypes().stream().anyMatch(st ->
                    (st.profileName().equals("test")
                            || st.profileName().equals("meta::pure::profiles::test"))
                            && st.stereotypeName().equals("BeforePackage"));
            if (isBp && bpSeen.add(f.qualifiedName())) {
                String fqn = f.qualifiedName();
                int cut = fqn.lastIndexOf("::");
                beforePackagesParsed.add(new String[]{
                        cut > 0 ? fqn.substring(0, cut) : "", fqn});
            }
        }
    }

    private String currentFileKey = "";
    private String currentFamilyKey = "";

    /**
     * Register a family's setup files (sources with NO test functions —
     * e.g. advancedRelationalSetUp.pure next to the join tests). Their
     * elements extend the shared model for EVERY test file of the family;
     * their DDL and executeInDb literals seed too.
     */
    public void useFamily(String familyKey, List<String> setupSources) {
        useFamily(familyKey, setupSources, List.of());
    }

    /**
     * {@code modelOnlySources}: sibling TEST files whose ELEMENTS join the
     * family model (cross-file references are normal — the engine compiles
     * the whole module together) but whose SEEDS stay per-file (a sibling's
     * DDL must not reshape tables under another file's test).
     */
    public void useFamily(String familyKey, List<String> setupSources,
            List<String> modelOnlySources) {
        useFamily(familyKey, setupSources, modelOnlySources, null);
    }

    /**
     * {@code parentFamilyKey}: a DEEP subfamily (tests/mapping/union/relation)
     * references its parent family's elements (~func bodies read the parent
     * db) — the engine compiles the module together. The parent's
     * already-vetted model text and DDL prepend; the child's own definitions
     * and seeds run after and win.
     */
    public void useFamily(String familyKey, List<String> setupSources,
            List<String> modelOnlySources, String parentFamilyKey) {
        currentFamilyKey = familyKey;
        if (familyRaw.containsKey(familyKey)) {
            return;
        }
        List<com.legend.Compiler.ModelSource> raw = new ArrayList<>();
        int rawIx = 0;
        for (String src : setupSources) {
            raw.add(new com.legend.Compiler.ModelSource(
                    familyKey + "/setup-" + rawIx++ + ".pure", src));
        }
        for (String src : modelOnlySources) {
            raw.add(new com.legend.Compiler.ModelSource(
                    familyKey + "/sibling-" + rawIx++ + ".pure", src));
        }
        familyRaw.put(familyKey, raw);
        if (parentFamilyKey != null) {
            familyParent.put(familyKey, parentFamilyKey);
        }
        for (String src : setupSources) {
            collectSetups(src);
        }
        for (String src : modelOnlySources) {
            collectSetups(src);
        }
    }

    /**
     * Register a test file's own model elements: mandatory elements append;
     * its mappings probe-compile against base+file (walls recorded). The
     * file's own table DDL and executeInDb literals seed too.
     */
    public void useFile(String key, String source) {
        currentFileKey = key;
        if (fileRaw.containsKey(key)) {
            return;
        }
        fileRaw.put(key, new com.legend.Compiler.ModelSource(key, source));
        collectSetups(source);
    }

    /** Two-phase protocol (compile-once, §8): registration of EVERY
     * family completes before the first test runs — the run phase
     * re-points the current family/file without re-assembly. */
    public void selectFamily(String familyKey) {
        if (!familyRaw.containsKey(familyKey)) {
            throw new IllegalStateException(
                    "family not registered: " + familyKey);
        }
        currentFamilyKey = familyKey;
    }

    public void selectFile(String key) {
        if (!fileRaw.containsKey(key)) {
            throw new IllegalStateException("file not registered: " + key);
        }
        currentFileKey = key;
    }




    public List<String> walls() {
        return walls;
    }

    /** Distinct failed seed statements across the whole run (scoreboard-reported). */
    private final java.util.LinkedHashSet<String> seedFailures = new java.util.LinkedHashSet<>();

    public List<String> seedFailures() {
        return new ArrayList<>(seedFailures);
    }

    // ===== per-test execution =====



    // ===== Phase C: test discovery + execution from the PARSED model =====

    /** One discovered {@code <<test.Test>>} function: the parsed
     * definition (body is AST), with its section's import scope. */
    public record ParsedTest(String fqn,
            com.legend.model.FunctionDefinition fn,
            com.legend.model.ImportScope imports) {}

    /**
     * Discover the runnable tests of one corpus source through the REAL
     * parser: {@code <<test.Test>>} stereotyped functions, minus
     * ToFix/Ignore (engine harness parity) and ExcludeAlloy (legend-lite
     * executes the in-process Alloy-shaped path).
     */
    /** ONE stereotype classifier for the whole harness (audit 17: two
     * hand-kept switches drifted): how does the {@code test} profile mark
     * this function? */
    enum TestKind { TEST, EXCLUDED, NONE }

    static TestKind testKindOf(com.legend.model.FunctionDefinition f) {
        boolean isTest = false;
        boolean excluded = false;
        for (com.legend.model.StereotypeApplication st : f.stereotypes()) {
            String profile = st.profileName();
            // the real profile is meta::pure::profiles::test — bare or FQN
            if (!(profile.equals("test")
                    || profile.equals("meta::pure::profiles::test"))) {
                continue;
            }
            switch (st.stereotypeName()) {
                case "Test" -> isTest = true;
                case "ToFix", "Ignore", "ExcludeAlloy" -> excluded = true;
                default -> { }
            }
        }
        return excluded ? TestKind.EXCLUDED : isTest ? TestKind.TEST : TestKind.NONE;
    }

    /** Does this source declare ANY test-stereotyped function — including
     * ToFix/Ignore/ExcludeAlloy ones (an all-excluded file is still a TEST
     * file, never a family SETUP file)? The family/test-file split runs on
     * this, through the real parser. */
    public static boolean hasTestFunctions(String source) {
        com.legend.model.ParsedModel unit;
        try {
            unit = com.legend.parser.ElementParser.parse(source);
        } catch (RuntimeException e) {
            return false;   // unparseable file: walled at model-build time
        }
        for (com.legend.model.PackageableElement el : unit.elements()) {
            if (el instanceof com.legend.model.FunctionDefinition f
                    && testKindOf(f) != TestKind.NONE) {
                return true;
            }
        }
        return false;
    }

    public static List<ParsedTest> discoverTests(String source) {
        List<ParsedTest> out = new ArrayList<>();
        com.legend.model.ParsedModel unit;
        try {
            unit = com.legend.parser.ElementParser.parse(source);
        } catch (RuntimeException e) {
            return out;   // unparseable file: walled at model-build time
        }
        for (com.legend.model.PackageableElement el : unit.elements()) {
            if (!(el instanceof com.legend.model.FunctionDefinition f)) {
                continue;
            }
            if (testKindOf(f) == TestKind.TEST) {
                out.add(new ParsedTest(f.qualifiedName(), f,
                        unit.elementImports().get(f.qualifiedName())));
            }
        }
        return out;
    }

    /** The test's import scope: its section's imports + its own package
     * (implicit in real pure). */
    private static com.legend.model.ImportScope importScopeOf(ParsedTest t) {
        List<String> wildcards = new ArrayList<>();
        Map<String, String> typeImports = new LinkedHashMap<>();
        if (t.imports() != null) {
            wildcards.addAll(t.imports().wildcards());
            typeImports.putAll(t.imports().typeImports());
        }
        int cut = t.fqn().lastIndexOf("::");
        if (cut > 0) {
            String ownPkg = t.fqn().substring(0, cut);
            // dedup: a file importing its own package must not list it
            // twice (two copies of one FQN read as a fake ambiguity)
            if (!wildcards.contains(ownPkg)) {
                wildcards.add(ownPkg);
            }
        }
        return new com.legend.model.ImportScope(wildcards, typeImports);
    }

    /** The MAPPING refs of execute()/-&gt;from() calls, AST-walked and
     * qualified via the test's imports — they feed the synthesized
     * Runtime's mappings and the no-execute SHAPE gate. */
    /**
     * β-expand statement-position calls to INDEXED corpus helpers whose
     * bodies (transitively, at this level) carry an execute/toSQLString
     * shape: parameters bind as lets, the callee's statements splice.
     * Query-level user functions (executeInDb wrappers etc.) are NOT
     * expanded here — they inline in the PLATFORM (UserCallInliner); the
     * execute-shape guard keeps this to test orchestration.
     */
    private List<com.legend.model.spec.ValueSpecification> expandHelperCalls(
            List<com.legend.model.spec.ValueSpecification> stmts,
            ParsedTest t, int depth) {
        return expandHelperCalls(stmts, t, depth, false);
    }

    private List<com.legend.model.spec.ValueSpecification> expandHelperCalls(
            List<com.legend.model.spec.ValueSpecification> stmts,
            ParsedTest t, int depth, boolean assertExpansion) {
        if (depth >= 3) {
            return stmts;
        }
        List<com.legend.model.spec.ValueSpecification> out = new ArrayList<>();
        for (com.legend.model.spec.ValueSpecification stmt : stmts) {
            FnDef callee = null;
            com.legend.model.spec.AppliedFunction call = null;
            String letName = null;
            if (stmt instanceof com.legend.model.spec.AppliedFunction af
                    && !af.function().equals("letFunction")
                    // assertEqualsH2Compatible is HARNESS vocabulary
                    // (TestBody's /3 arm verifies by rows through the H2
                    // second target) — expanding its corpus body would
                    // splice in getH2Versions()/executeInDb plumbing the
                    // module never pulls (forced-milestoning walls)
                    && !af.function()
                            .substring(af.function().lastIndexOf(':') + 1)
                            .equals("assertEqualsH2Compatible")) {
                String fqn = af.function().contains("::")
                        ? af.function() : qualify(af.function(), t);
                FnDef fd = fnIndex.get(fqn + "/" + af.parameters().size());
                if (fd != null && !fd.body().isEmpty()
                        && (containsExecuteShapeDeep(fd.body(), t, 0)
                                || (assertExpansion
                                        && containsAssertCall(fd.body())))) {
                    // assert-carrying helpers expand ONLY on the try-run
                    // path (no-execute bodies — assertConversion et al.);
                    // mainstream families keep the execute-shape gate
                    // (the widened gate regressed 600+ tests: helper
                    // bodies carrying sqlRemoveFormatting spliced into
                    // typed positions)
                    callee = fd;
                    call = af;
                }
            }
            // LET-BOUND helper calls expand ONLY for the Pair-returning
            // plan idiom (`let p = helper(...)` whose body ends in
            // pair(plan, planToString...)): the let rebinds to the pair.
            // Broader let-expansion dismembered the validate/toSQLString
            // vocabulary shapes (the -53 sweep regression) — those
            // helpers must stay CALLS for their recognizers.
            if (callee == null
                    && stmt instanceof com.legend.model.spec.AppliedFunction lf0
                    && lf0.function().equals("letFunction")
                    && lf0.parameters().size() == 2
                    && lf0.parameters().get(0)
                            instanceof com.legend.model.spec.CString ln0
                    && lf0.parameters().get(1)
                            instanceof com.legend.model.spec.AppliedFunction af2
                    && !af2.function().equals("letFunction")) {
                String fqn2 = af2.function().contains("::")
                        ? af2.function() : qualify(af2.function(), t);
                FnDef fd2 = fnIndex.get(fqn2 + "/" + af2.parameters().size());
                com.legend.model.spec.ValueSpecification last2 =
                        fd2 == null || fd2.body().isEmpty() ? null
                                : fd2.body().get(fd2.body().size() - 1);
                boolean pairIdiom = fd2 != null
                        && last2 instanceof com.legend.model.spec.AppliedFunction pl2
                        && pl2.function().endsWith("pair")
                        && containsExecuteShapeDeep(fd2.body(), t, 0);
                // ...and the SINGLE-EXPRESSION execute wrapper
                // (executeInternal(f) = execute(f, mapping, runtime, ext)):
                // nothing to dismember — the body IS the execute call, so
                // the let rebinds to it directly (router composition tests)
                boolean singleExecute = fd2 != null
                        && fd2.body().size() == 1
                        && fd2.body().get(0)
                                instanceof com.legend.model.spec.AppliedFunction ef2
                        && ef2.function()
                                .substring(ef2.function().lastIndexOf(':') + 1)
                                .equals("execute");
                boolean executeTerminal = fd2 != null
                        && fd2.body().stream().anyMatch(
                                Runner::containsDebugArityExecute)
                        && java.util.stream.Stream.of("toSQLString",
                                "validate", "scanColumns", "scanRelations",
                                "generateTestData", "planTestDataGeneration",
                                "getRelationalCSVDataFromQuery",
                                "generateSeedDataString", "executionPlan")
                            .noneMatch(v -> fd2.body().stream()
                                .anyMatch(b -> containsCallNamed(b, v)));
                // PLAN-SURFACE wrapper (m2m2r planToString helper:
                // executionPlan(q, m, rt, ext)->planToString(ext) as the
                // single body expression) — expanding exposes the
                // executionPlan shape executeMappingRefs recognizes
                boolean planChain = fd2 != null && fd2.body().size() == 1
                        && containsCallNamed(fd2.body().get(0),
                                "executionPlan")
                        && containsCallNamed(fd2.body().get(0),
                                "planToString");
                if (pairIdiom || singleExecute || executeTerminal
                        || planChain) {
                    callee = fd2;
                    call = af2;
                    letName = ln0.value();
                }
            }
            if (callee == null) {
                out.add(stmt);
                continue;
            }
            for (int i = 0; i < callee.params().size(); i++) {
                out.add(new com.legend.model.spec.AppliedFunction("letFunction",
                        List.of(new com.legend.model.spec.CString(
                                        callee.params().get(i)),
                                call.parameters().get(i))));
            }
            List<com.legend.model.spec.ValueSpecification> expanded =
                    expandHelperCalls(callee.body(), t, depth + 1,
                            assertExpansion);
            if (letName != null && !expanded.isEmpty()) {
                com.legend.model.spec.ValueSpecification last =
                        expanded.remove(expanded.size() - 1);
                if (last instanceof com.legend.model.spec.AppliedFunction ll
                        && ll.function().equals("letFunction")
                        && ll.parameters().size() == 2) {
                    last = ll.parameters().get(1);
                }
                // a trailing bare `$x;` whose x is ALREADY a spliced let
                // would rebind letName to itself downstream of renames —
                // keep the reference as-is only when names DIFFER; a
                // same-name rebinding (let result = $result) is dropped
                if (last instanceof com.legend.model.spec.Variable tv
                        && tv.name().equals(letName)) {
                    out.addAll(expanded);
                    continue;
                }
                out.addAll(expanded);
                out.add(new com.legend.model.spec.AppliedFunction(
                        "letFunction", List.of(
                                new com.legend.model.spec.CString(letName),
                                last)));
                continue;
            }
            out.addAll(expanded);
        }
        return out;
    }

    /** A DEBUG-ARITY execute call (5+ args): the forced-isolation
     * idiom's structural signature — the tight let-expansion gate. */
    /** The forced-helper idiom is identified by its
     * ^RelationalDebugContext(forcedIsolation=...) execute argument —
     * arity alone also matches validation helpers (regression source). */
    private static boolean isDebugContextNew(
            com.legend.model.spec.ValueSpecification v) {
        return v instanceof com.legend.model.spec.AppliedFunction af
                && af.function().equals("new")
                && !af.parameters().isEmpty()
                && af.parameters().get(0)
                        instanceof com.legend.model.spec.PackageableElementPtr pe
                && (pe.fullPath().equals("RelationalDebugContext")
                        || pe.fullPath().equals("meta::relational::runtime"
                                + "::RelationalDebugContext"));
    }

    private static boolean containsDebugArityExecute(
            com.legend.model.spec.ValueSpecification v) {
        if (v instanceof com.legend.model.spec.AppliedFunction af) {
            String simple = af.function()
                    .substring(af.function().lastIndexOf(':') + 1);
            if (simple.equals("execute") && af.parameters().size() >= 5
                    && af.parameters().stream().anyMatch(
                            Runner::isDebugContextNew)) {
                return true;
            }
            for (com.legend.model.spec.ValueSpecification p2 : af.parameters()) {
                if (containsDebugArityExecute(p2)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Any execute/toSQLString/from call anywhere in these statements. */
    /** The transitive gate: a helper qualifies when its body reaches an
     * execute shape DIRECTLY or through further helper calls (the corpus's
     * wrapper-overload idiom: a 2-arg assert helper delegating to the
     * 3-arg one that holds the executionPlan call). */
    private boolean containsExecuteShapeDeep(
            List<com.legend.model.spec.ValueSpecification> stmts,
            ParsedTest t, int depth) {
        if (containsExecuteShape(stmts)) {
            return true;
        }
        if (depth >= 3) {
            return false;
        }
        for (com.legend.model.spec.ValueSpecification stmt : stmts) {
            if (stmt instanceof com.legend.model.spec.AppliedFunction af
                    && !af.function().equals("letFunction")) {
                String fqn = af.function().contains("::")
                        ? af.function() : qualify(af.function(), t);
                FnDef fd = fnIndex.get(fqn + "/" + af.parameters().size());
                if (fd != null && !fd.body().isEmpty()
                        && containsExecuteShapeDeep(fd.body(), t,
                                depth + 1)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean containsExecuteShape(
            List<com.legend.model.spec.ValueSpecification> stmts) {
        java.util.ArrayDeque<com.legend.model.spec.ValueSpecification> work =
                new java.util.ArrayDeque<>(stmts);
        while (!work.isEmpty()) {
            var v = work.poll();
            if (v instanceof com.legend.model.spec.AppliedFunction af) {
                String simple = af.function()
                        .substring(af.function().lastIndexOf(':') + 1);
                if (simple.equals("execute") || simple.equals("toSQLString")
                        || simple.equals("from")
                        || simple.equals("executionPlan")
                        || simple.equals("planToString")
                        || simple.equals("planToStringWithoutFormatting")) {
                    return true;
                }
                work.addAll(af.parameters());
            } else if (v instanceof com.legend.model.spec.AppliedProperty ap) {
                work.add(ap.receiver());
            } else if (v instanceof com.legend.model.spec.LambdaFunction lf) {
                work.addAll(lf.body());
            } else if (v instanceof com.legend.model.spec.PureCollection pc) {
                work.addAll(pc.values());
            }
        }
        return false;
    }

    private List<String> executeMappingRefs(
            List<com.legend.model.spec.ValueSpecification> body, ParsedTest t) {
        List<String> out = new ArrayList<>();
        // LET-BOUND mapping refs (the corpus's dominant graphFetch idiom:
        // `let mapping = X; ... execute($q, $mapping, ...)`) resolve through
        // this binding table — TestBody substitutes them at run time, so
        // the DISCOVERY gate must see through them too (bucket analysis:
        // 133/150 graphFetch execute calls were walled by this alone).
        Map<String, com.legend.model.spec.ValueSpecification> lets =
                new LinkedHashMap<>();
        // executionPlan only counts as an execute shape when the body
        // READS the plan text — the printer's servable contract; plan
        // handles walked via other properties stay walled until built
        boolean planRead = body.stream().anyMatch(v ->
                containsCallNamed(v, "planToString")
                        || containsCallNamed(v,
                                "planToStringWithoutFormatting")
                        || containsPropertyNamed(v, "rootExecutionNode"));
        java.util.ArrayDeque<com.legend.model.spec.ValueSpecification> work =
                new java.util.ArrayDeque<>(body);
        while (!work.isEmpty()) {
            com.legend.model.spec.ValueSpecification v = work.poll();
            if (v instanceof com.legend.model.spec.AppliedFunction af) {
                if (af.function().equals("letFunction")
                        && af.parameters().size() == 2
                        && af.parameters().get(0)
                                instanceof com.legend.model.spec.CString ln) {
                    com.legend.model.spec.ValueSpecification rhs =
                            af.parameters().get(1);
                    // a β-expansion prologue rebinding (let mapping =
                    // $mapping) must not shadow the POINTER the caller
                    // bound — chase Variable RHS through current lets
                    if (rhs instanceof com.legend.model.spec.Variable rv
                            && lets.containsKey(rv.name())) {
                        rhs = lets.get(rv.name());
                    }
                    lets.put(ln.value(), rhs);
                }
                String simple = af.function()
                        .substring(af.function().lastIndexOf(':') + 1);
                boolean executeShape = (simple.equals("execute")
                                || simple.equals("toSQLString")
                                // validate(func, MAPPING, runtime, ...) —
                                // #45: desugars to execute at TestBody
                                || simple.equals("validate")
                                // scanColumns(tree, MAPPING) /
                                // scanRelations(q, MAPPING, ...) — #44:
                                // the lineage forms route at TestBody
                                || simple.equals("scanColumns")
                                || simple.equals("scanRelations")
                                // generateTestData(q, MAPPING, runtime,
                                // ids, ...) — #46: TestDataGenForm routes
                                // at TestBody
                                || simple.equals("generateTestData")
                                || simple.equals("planTestDataGeneration")
                                || simple.equals(
                                        "getRelationalCSVDataFromQuery")
                                || simple.equals("generateSeedDataString")
                                // executionPlan(q, MAPPING, rt, ext) —
                                // #47: the plan-text K-native routes at
                                // the platform
                                || simple.equals("executionPlan") && planRead)
                        && af.parameters().size() >= 2;
                boolean fromShape = simple.equals("from")
                        && af.parameters().size() >= 2;
                if (executeShape || fromShape) {
                    // validate's EXTENDED overloads put the mapping after
                    // the col/postTDS args — take the FIRST pointer arg
                    java.util.List<com.legend.model.spec.ValueSpecification>
                            cands = simple.equals("validate")
                                    ? af.parameters()
                                    : java.util.List.of(af.parameters().get(1));
                    for (com.legend.model.spec.ValueSpecification arg : cands) {
                        if (arg instanceof com.legend.model.spec.Variable var
                                && lets.containsKey(var.name())) {
                            arg = lets.get(var.name());
                        }
                        if (arg instanceof com.legend.model.spec.PackageableElementPtr ptr) {
                            String ref = qualify(ptr.fullPath(), t);
                            if (ref.matches("[\\w:]+") && !out.contains(ref)) {
                                out.add(ref);
                            }
                            break;
                        }
                    }
                }
                // DOCUMENT-ORDER descent (addFirst, reversed): a nested
                // call must deref lets AS OF ITS STATEMENT — the BFS
                // variant let a later β-expanded helper's `let mapping =
                // $mapping` rebinding shadow the real pointer before the
                // call's params were ever visited (#46 discovery bug)
                for (int i = af.parameters().size() - 1; i >= 0; i--) {
                    work.addFirst(af.parameters().get(i));
                }
            } else if (v instanceof com.legend.model.spec.AppliedProperty ap) {
                work.addFirst(ap.receiver());
            } else if (v instanceof com.legend.model.spec.LambdaFunction lf) {
                for (int i = lf.body().size() - 1; i >= 0; i--) {
                    work.addFirst(lf.body().get(i));
                }
            } else if (v instanceof com.legend.model.spec.PureCollection pc) {
                for (int i = pc.values().size() - 1; i >= 0; i--) {
                    work.addFirst(pc.values().get(i));
                }
            } else if (v instanceof com.legend.model.spec.NewInstance ni) {
                var kes = new java.util.ArrayList<>(ni.properties().values());
                for (int i = kes.size() - 1; i >= 0; i--) {
                    work.addFirst(kes.get(i).value());
                }
            }
        }
        // ALLOY-WRAPPER bodies (mayExecuteAlloyTest — the server thunk
        // never runs here, engine no-server CI parity): nothing above
        // matched a call shape, but the module is still defined by the
        // test's let-bound element pointers (mapping/db) — pull those so
        // the setup statements can run (#46 _Alloy subfamily).
        boolean fallback = out.isEmpty() && body.stream().anyMatch(v ->
                containsCallNamed(v, "mayExecuteAlloyTest")
                        || containsCallNamed(v, "pkOfFunc"));
        if (fallback) {
            java.util.List<com.legend.model.spec.ValueSpecification> ptrs =
                    new ArrayList<>(lets.values());
            java.util.ArrayDeque<com.legend.model.spec.ValueSpecification>
                    w2 = new java.util.ArrayDeque<>(body);
            while (!w2.isEmpty()) {
                var v = w2.poll();
                if (v instanceof com.legend.model.spec.PackageableElementPtr) {
                    ptrs.add(v);
                } else if (v instanceof com.legend.model.spec.AppliedFunction f2) {
                    w2.addAll(f2.parameters());
                } else if (v instanceof com.legend.model.spec.PureCollection c2) {
                    w2.addAll(c2.values());
                }
            }
            for (com.legend.model.spec.ValueSpecification v : ptrs) {
                if (v instanceof com.legend.model.spec.PackageableElementPtr p2) {
                    String path = p2.fullPath();
                    // fn-ref spellings carry the __<sig>_ mangle
                    int mangle = path.indexOf("__");
                    if (mangle > 0) {
                        path = path.substring(0, mangle);
                    }
                    String ref = qualify(path, t);
                    if (ref.matches("[\\w:]+") && !out.contains(ref)) {
                        out.add(ref);
                    }
                }
            }
        }
        return out;
    }
    private static boolean containsCallNamed(
            com.legend.model.spec.ValueSpecification n, String name) {
        if (n instanceof com.legend.model.spec.AppliedFunction af) {
            if (name.equals(af.function()
                    .substring(af.function().lastIndexOf(':') + 1))) {
                return true;
            }
            for (com.legend.model.spec.ValueSpecification p : af.parameters()) {
                if (containsCallNamed(p, name)) {
                    return true;
                }
            }
        } else if (n instanceof com.legend.model.spec.AppliedProperty ap) {
            return containsCallNamed(ap.receiver(), name);
        } else if (n instanceof com.legend.model.spec.LambdaFunction lf) {
            for (com.legend.model.spec.ValueSpecification b : lf.body()) {
                if (containsCallNamed(b, name)) {
                    return true;
                }
            }
        } else if (n instanceof com.legend.model.spec.PureCollection pc) {
            for (com.legend.model.spec.ValueSpecification e : pc.values()) {
                if (containsCallNamed(e, name)) {
                    return true;
                }
            }
        }
        return false;
    }


    private static boolean containsPropertyNamed(
            com.legend.model.spec.ValueSpecification n, String name) {
        if (n instanceof com.legend.model.spec.AppliedProperty ap) {
            return name.equals(ap.property())
                    || containsPropertyNamed(ap.receiver(), name);
        }
        if (n instanceof com.legend.model.spec.AppliedFunction af) {
            for (com.legend.model.spec.ValueSpecification p
                    : af.parameters()) {
                if (containsPropertyNamed(p, name)) {
                    return true;
                }
            }
        }
        if (n instanceof com.legend.model.spec.PureCollection pc) {
            for (com.legend.model.spec.ValueSpecification e : pc.values()) {
                if (containsPropertyNamed(e, name)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Qualify a bare mapping reference via the test's imports + the
     * PARSED element index — never substring search over source text
     * (audit 19d R2: qualify was the last text-scan name resolver; the
     * elementSource keys are the real parser's FQNs). */
    private String qualify(String name, ParsedTest t) {
        if (name.contains("::")) {
            return name;
        }
        if (t.imports() != null && t.imports().typeImports().containsKey(name)) {
            return t.imports().typeImports().get(name);
        }
        List<String> pkgs = new ArrayList<>();
        if (t.imports() != null) {
            pkgs.addAll(t.imports().wildcards());
        }
        int cut = t.fqn().lastIndexOf("::");
        if (cut > 0) {
            pkgs.add(t.fqn().substring(0, cut));
        }
        for (String pkg : pkgs) {
            String candidate = pkg + "::" + name;
            if (elementSource.containsKey(candidate)) {
                return candidate;
            }
        }
        return name;
    }

    /** Whether any statement is an assert* call (top level only). */
    private static boolean containsAssertCall(
            List<com.legend.model.spec.ValueSpecification> body) {
        for (com.legend.model.spec.ValueSpecification st : body) {
            if (st instanceof com.legend.model.spec.AppliedFunction af) {
                String fn = af.function();
                String simple = fn.substring(fn.lastIndexOf(':') + 1);
                if (simple.startsWith("assert")) {
                    return true;
                }
            }
        }
        return false;
    }

    /** A no-execute body attempted through the FULL pipeline: PASS/FAIL
     * outcomes are real; Unsupported/errors return null so the caller
     * falls back to the functional-bucket SHAPE. */
    /** The try-run result: a REAL verdict (PASS/FAIL) stands; otherwise
     * {@code wall} carries the pipeline's ACTUAL diagnosis so the
     * functional-bucket SHAPE row is not diagnosis-free (taxonomy X2:
     * 81 rows — 20% of all failures — had no diagnosis attached). */
    private record TryRun(@com.legend.Nullable Outcome verdict,
            @com.legend.Nullable String wall) { }

    private TryRun tryRunNoExecute(ParsedTest t,
            List<com.legend.model.spec.ValueSpecification> body) {
        body = expandHelperCalls(body, t, 0, true);
        java.util.Set<String> called = new java.util.LinkedHashSet<>();
        java.util.Set<String> elements = new java.util.LinkedHashSet<>();
        for (com.legend.model.spec.ValueSpecification stmt : body) {
            collectCalledFqns(stmt, called, elements);
        }
        // DEMAND-PULL retry (single-FILE vehicle — the transitive-closure
        // probe regressed 1219->1200 and stays reverted): an 'unknown
        // type/class X' typing failure whose simple name has EXACTLY ONE
        // defining element pulls that element's file and retries, max 3
        // hops (metamodel files chain).
        List<String> fileOnly = new ArrayList<>();
        List<String> moduleRefs = new ArrayList<>();
        for (java.util.Set<String> refs : java.util.List.of(called,
                elements)) {
            for (String ref : refs) {
                if (ref.contains("::") && elementSource.containsKey(ref)) {
                    moduleRefs.add(ref);
                } else if (!ref.contains("::")) {
                    // BARE refs (called functions AND element values)
                    // qualify through the test's imports and pull their
                    // single defining file — the execute path's rule
                    // (getTable under import meta::relational::functions
                    // ::toDDL::*, productMappingWithFilter under the
                    // qualifier wildcard)
                    String q = qualify(ref, t);
                    if (q.contains("::") && elementSource.containsKey(q)
                            && !fileOnly.contains(q)) {
                        fileOnly.add(q);
                    }
                }
            }
        }
        for (int attempt = 0; ; attempt++) {
        try {
            com.legend.compiler.element.ModelContext ctx =
                    moduleContextFor(moduleRefs, fileOnly);
            try (Connection conn = openSession()) {
                List<String> ledger = new ArrayList<>();
                com.legend.harness.TestBody.Outcome o =
                        com.legend.harness.TestBody.run(ctx, body,
                                importScopeOf(t), "rcorpus::Rt", conn,
                                false, ledger);
                if (System.getenv("LL_TMP_DEBUG") != null
                        && !ledger.isEmpty()) {
                    System.err.println("[try-run-ledger] " + t.fqn() + ": "
                            + ledger);
                }
                Outcome scored = score(t.fqn(), o);
                if (System.getenv("LL_TMP_DEBUG") != null) {
                    System.err.println("[try-run] " + t.fqn() + " -> "
                            + scored.status() + ": " + scored.detail());
                }
                // only REAL verdicts stand — vocabulary gaps and hollow
                // shapes keep the functional-bucket SHAPE, but their
                // COMPUTED diagnosis rides along (X2)
                if (scored.status() == Status.PASS
                        || scored.status() == Status.FAIL) {
                    return new TryRun(scored, null);
                }
                return new TryRun(null, scored.detail());
            }
        } catch (Exception e) {
            String pull = attempt < 3 ? unknownTypePull(e, t) : null;
            if (pull != null && !fileOnly.contains(pull)) {
                fileOnly.add(pull);
                continue;
            }
            if (System.getenv("LL_TMP_DEBUG") != null) {
                System.err.println("[try-run] " + t.fqn() + " threw "
                        + e);
                e.printStackTrace();
            }
            return new TryRun(null, exceptionText(e));
        }
        }
    }

    /** The FQN to demand-pull for an 'unknown type/class/function X'
     * failure — only when X's simple name has EXACTLY ONE defining
     * element, or exactly one of the candidates sits under the test's
     * import wildcards (getTable: toDDL vs testDataGeneration — the
     * file's own imports name the winner). A residual ambiguity would
     * poison resolution; it stays a wall. */
    private String unknownTypePull(Exception e, ParsedTest t) {
        String msg = String.valueOf(e.getMessage());
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("[Uu]nknown (?:type|class|function)[:]? '([\\w:]+)'")
                .matcher(msg);
        if (!m.find()) {
            return null;
        }
        String name = m.group(1);
        if (name.contains("::")) {
            return elementSource.containsKey(name) ? name : null;
        }
        List<String> candidates = new ArrayList<>();
        for (String fqn : elementSource.keySet()) {
            int cut = fqn.lastIndexOf("::");
            if (cut > 0 && fqn.substring(cut + 2).equals(name)) {
                candidates.add(fqn);
            }
        }
        if (candidates.size() == 1) {
            return candidates.get(0);
        }
        String q = qualify(name, t);
        return candidates.contains(q) ? q : null;
    }

    /** The most-called function NAMESPACE of a no-execute body — the
     * functional identity of the feature the test exercises. */
    private static String dominantNamespace(
            List<com.legend.model.spec.ValueSpecification> body) {
        java.util.Set<String> called = new java.util.LinkedHashSet<>();
        java.util.Set<String> elements = new java.util.LinkedHashSet<>();
        for (com.legend.model.spec.ValueSpecification stmt : body) {
            collectCalledFqns(stmt, called, elements);
        }
        java.util.Map<String, Integer> counts = new java.util.LinkedHashMap<>();
        for (String fqn : called) {
            int cut = fqn.lastIndexOf("::");
            if (cut < 0) {
                continue;
            }
            counts.merge(fqn.substring(0, cut), 1, Integer::sum);
        }
        return counts.entrySet().stream()
                .max(java.util.Map.Entry.comparingByValue())
                .map(java.util.Map.Entry::getKey).orElse(null);
    }

    /** Run one PARSED test through the pipeline. */
    public Outcome run(ParsedTest t) {
        // #67: record every raw corpus statement this test executes —
        // the H2 advisory second target replays them verbatim to verify
        // golden-SQL asserts by ROWS. Fresh list per test; the next run
        // replaces it.
        com.legend.exec.RawSqlBoundary.record(new ArrayList<>());
        // Statement-position HELPER calls β-expand (params bound as lets)
        // for TWO reasons, verified separable by experiment (audit 20
        // follow-up): (a) DISCOVERY — executeMappingRefs must see execute
        // calls inside helpers to assemble the right module; (b) ASSERT
        // VISIBILITY — a helper body carrying assertEquals is HARNESS
        // vocabulary the platform can never type (G: unknown function),
        // so TestBody must see the expanded statements to intercept them.
        // The original execute-visibility rationale (audit 19d B1) is
        // OBSOLETE since B2b made execute a platform native — the sweep
        // with raw bodies regressed ONLY the assert-in-helper shape.
        List<com.legend.model.spec.ValueSpecification> body =
                expandHelperCalls(t.fn().body(), t, 0);
        List<String> mappingRefs = executeMappingRefs(body, t);
        if (mappingRefs.isEmpty()) {
            // VACUOUS PLACEHOLDER: the engine's own suite contains tests
            // whose entire body is the literal `true` (testFailures.pure
            // failMoveFilterOnTop/BuildCorrelatedSubQuery — placeholders
            // for strategies the engine itself documents as failing).
            // The engine runs them as vacuous passes; scoring them SHAPE
            // would misfile engine semantics as a vocabulary gap.
            if (body.size() == 1
                    && body.get(0) instanceof com.legend.model.spec
                            .CBoolean cb && cb.value()) {
                return new Outcome(t.fqn(), Status.PASS,
                        "vacuous placeholder (engine body = true)");
            }
            // TRY-RUN-THEN-SHAPE: a no-execute body may still be fully
            // runnable through the pipeline (metamodel navigation +
            // host-evaluated natives — the typeInference family). Attempt
            // it; anything the platform cannot run falls back to the
            // FUNCTIONAL-bucket SHAPE (the denominator stays honest about
            // WHAT is not built yet).
            TryRun attempted = tryRunNoExecute(t, body);
            if (attempted.verdict() != null) {
                return attempted.verdict();
            }
            String ns = dominantNamespace(body);
            return new Outcome(t.fqn(), Status.SHAPE, "no execute(|...)"
                    + " call" + (ns == null ? "" : " [calls " + ns + "]")
                    + (attempted.wall() == null ? ""
                            : " — wall: " + attempted.wall()));
        }
        // QUALIFIED function/element references in the body pull their
        // defining families too (execute(..., other::family::runtime(),
        // ...) — the engine compiles ONE module; per-family modules must
        // close over what the test names). Same rule as the mapping pull.
        java.util.Set<String> called = new java.util.LinkedHashSet<>();
        java.util.Set<String> elements = new java.util.LinkedHashSet<>();
        for (com.legend.model.spec.ValueSpecification stmt : body) {
            collectCalledFqns(stmt, called, elements);
        }
        List<String> moduleRefs = new ArrayList<>(mappingRefs);
        // BARE element refs (^RelationalDebugContext(...) under an import
        // wildcard) qualify through the test's imports before the pull
        // check — collected pre-resolution they fail contains("::") and
        // their defining file never pulls, so the class resolves by NAME
        // but is unknown in the MODULE (the probe-pass/sweep-fail cluster).
        // Bare-resolved refs pull the SINGLE DEFINING FILE only (the
        // narrower vehicle from the reverted transitive-closure probe —
        // whole foreign families poison resolution).
        List<String> fileOnlyRefs = new ArrayList<>();
        for (String ref : called) {
            if (ref.contains("::") && elementSource.containsKey(ref)) {
                moduleRefs.add(ref);
            }
        }
        for (String ref : elements) {
            if (ref.contains("::") && elementSource.containsKey(ref)) {
                moduleRefs.add(ref);
            } else if (!ref.contains("::")) {
                String q = qualify(ref, t);
                if (q.contains("::") && elementSource.containsKey(q)) {
                    fileOnlyRefs.add(q);
                }
            }
        }
        try {
            com.legend.compiler.element.ModelContext ctx =
                    moduleContextFor(moduleRefs, fileOnlyRefs);
            try (Connection conn = openSession()) {
                List<String> failedSeeds = replaySeeds(t.fqn(), moduleRefs,
                        ctx, conn);
                seedFailures.addAll(failedSeeds);
                if (System.getenv("LL_TMP_DEBUG") != null
                        || System.getenv("LL_ORD_COUNT") != null) {
                    // wall-attribution marker: [plan-wall]/[walk]/[ord]
                    // prints that follow belong to THIS test
                    System.err.println("[run] " + t.fqn());
                }
                com.legend.harness.TestBody.Outcome o = com.legend.harness.TestBody.run(
                        ctx, body, importScopeOf(t), "rcorpus::Rt",
                        conn, !failedSeeds.isEmpty(), failedSeeds);
                // body-time setup failures (added via the sink DURING the
                // run) join the run-wide report too (audit 17)
                seedFailures.addAll(failedSeeds);
                return score(t.fqn(), o);
            }
        } catch (Exception e) {
            if (System.getenv("LEGEND_LITE_STACKS") != null) {
                e.printStackTrace();
            }
            if (H2_BACKEND && capabilityWall(e) != null) {
                return new Outcome(t.fqn(), Status.UNSUPPORTED,
                        exceptionText(java.util.Objects.requireNonNull(
                                capabilityWall(e))));
            }
            return new Outcome(t.fqn(), Status.ERROR,
                    exceptionText(e));
        }
    }

    /** The typed renderer capability wall in {@code e}'s cause chain —
     * the portability sweep's UNSUPPORTED classifier (§10); null when
     * the failure is anything else. */
    private static @com.legend.Nullable Throwable capabilityWall(Throwable e) {
        for (Throwable c = e; c != null; c = c.getCause()) {
            if (c instanceof com.legend.sql.dialect.DialectCapability) {
                return c;
            }
        }
        return null;
    }

    /** A MESSAGE-LESS exception must not render as the literal "null"
     * (taxonomy X3: HotSpot's OmitStackTraceInFastThrow strips messages
     * from hot repeat throws, silently merging distinct causes into a
     * fake "null" bucket) — fall back to the exception class name. */
    private static String exceptionText(Throwable e) {
        String m = e.getMessage();
        return (m == null ? e.getClass().getSimpleName() : m)
                .replace("\n", " | ");
    }

    private static Outcome score(String fqn, com.legend.harness.TestBody.Outcome o) {
        return switch (o) {
            case com.legend.harness.TestBody.Outcome.Unsupported u ->
                    new Outcome(fqn, Status.SHAPE, u.reason());
            case com.legend.harness.TestBody.Outcome.Ran r -> {
                if (!r.failures().isEmpty()) {
                    yield new Outcome(fqn, Status.FAIL, r.failures().get(0));
                }
                if (r.verified() == 0 && !r.sqlDiffs().isEmpty()) {
                    // sql-only test: the literal engine-text compare IS
                    // the contract — a divergence fails honestly
                    yield new Outcome(fqn, Status.FAIL, r.sqlDiffs().get(0));
                }
                if (r.verified() == 0 && r.advisory() > 0) {
                    yield new Outcome(fqn, Status.SHAPE,
                            "sql-only: " + r.advisory()
                                    + " advisory golden-SQL assert(s),"
                                    + " no row verification");
                }
                if (r.verified() == 0 && r.executed() > 0) {
                    // the engine's own test is assert-free: running its
                    // body through the pipeline to completion IS the whole
                    // contract (engine parity), not a hollow pass
                    yield new Outcome(fqn, Status.PASS, "0 asserts — "
                            + r.executed() + " statement(s) executed");
                }
                if (r.verified() == 0) {
                    yield new Outcome(fqn, Status.SHAPE, "no verifying assertions");
                }
                // C0.2 (CORRECTNESS_REMEDIATION): a row-verified pass with
                // golden-SQL divergences must SAY so — the pass count alone
                // is not evidence of SQL parity
                yield new Outcome(fqn, Status.PASS,
                        r.verified() + " assert(s)"
                                + (r.sqlDiffs().isEmpty() ? ""
                                        : ", " + r.sqlDiffs().size()
                                                + " advisory sql diff(s)"),
                        r.sqlDiffs().size());
            }
        };
    }





    private com.legend.compiler.element.ModelContext moduleContextFor(
            List<String> mappingRefs) {
        return moduleContextFor(mappingRefs, List.of());
    }

    private com.legend.compiler.element.ModelContext moduleContextFor(
            List<String> mappingRefs, List<String> fileOnlyRefs) {
        com.legend.Compiler.BuiltModule built = globalModule();
        // DDL SCOPE stays MODULE-SHAPED under the global compile: the
        // session replays only the old per-module source set's tables
        // (shared + parent family + family + file + the refs' defining
        // families) — a global DDL would create every family's tables
        // in every test session
        currentDdlDbs = ddlScopeDbs(mappingRefs, fileOnlyRefs);
        // per-test EXECUTION OVERLAY (PHASE_K §4): the runtime's mappings
        // list drives class-query dispatch, so it is per-test API data —
        // an allocation over the ONE compiled context, never a recompile.
        // The incoming refs are the module-pull grab-bag (mappings +
        // called fn/element fqns + runtimes) — the runtime declares only
        // the ACTUAL mappings among them (the old model-text runtime
        // tolerated the rest; the typed overlay validates eagerly).
        com.legend.compiler.element.PureModelContext base =
                (com.legend.compiler.element.PureModelContext) built.context();
        List<String> rtMappings = new ArrayList<>();
        for (String ref : new java.util.LinkedHashSet<>(mappingRefs)) {
            if (base.findMapping(ref).isPresent()
                    || base.findLegacyMapping(ref).isPresent()) {
                rtMappings.add(ref);
            }
        }
        String key = String.join(",", rtMappings);
        return overlayMemo.computeIfAbsent(key, k ->
                base.withExecutionOverlay(
                        new com.legend.model.RuntimeDefinition(
                                "rcorpus::Rt",
                                List.copyOf(rtMappings),
                                allDbBindings, List.of()),
                        rcorpusConn));
    }

    /** THE global corpus compile (NAME_RESOLUTION_BUG.md §8): every
     * registered source in ONE model — deterministic registration order,
     * STRICT parse (the census burned parse walls to zero; a new
     * unparseable file fails the sweep loudly by name), STRICT
     * duplicates (the corpus tree carries none), tolerant per-element
     * build (the unported-platform backlog walls individually). */
    private com.legend.Compiler.BuiltModule globalModule() {
        if (globalBuilt != null) {
            return globalBuilt;
        }
        List<com.legend.Compiler.ModelSource> sources = new ArrayList<>(sharedRaw);
        java.util.Set<String> present = new java.util.HashSet<>();
        for (com.legend.Compiler.ModelSource sh : sharedRaw) {
            present.add(sh.text());
        }
        for (List<com.legend.Compiler.ModelSource> fam : familyRaw.values()) {
            for (com.legend.Compiler.ModelSource src : fam) {
                if (present.add(src.text())) {
                    sources.add(src);
                }
            }
        }
        for (com.legend.Compiler.ModelSource f : fileRaw.values()) {
            if (present.add(f.text())) {
                sources.add(f);
            }
        }
        for (com.legend.Compiler.ModelSource lib : libraryRaw) {
            if (present.add(lib.text())) {
                sources.add(lib);
            }
        }
        List<String> parseWalls = new ArrayList<>();
        globalParsed = com.legend.Compiler.parseSources(sources,
                (name, err) -> parseWalls.add(name + " => " + err));
        if (!parseWalls.isEmpty()) {
            throw new IllegalStateException(
                    "corpus parse walls (expected ZERO): " + parseWalls);
        }
        if (!globalParsed.duplicateElements().isEmpty()) {
            throw new IllegalStateException(
                    "corpus duplicate elements (expected ZERO): "
                            + globalParsed.duplicateElements());
        }
        globalBuilt = com.legend.Compiler.buildModule(globalParsed.model());
        globalBuilt.walls().forEach((fqn, msg) ->
                wallOnce("global " + fqn + " => " + msg));
        java.util.Map<String, String> binds = new LinkedHashMap<>();
        for (com.legend.model.PackageableElement el
                : globalParsed.model().elements()) {
            if (el instanceof com.legend.model.DatabaseDefinition db) {
                binds.put(db.qualifiedName(), "rcorpus::Conn");
            }
        }
        allDbBindings = java.util.Collections.unmodifiableMap(binds);
        rcorpusConn = new com.legend.model.ConnectionDefinition(
                "rcorpus::Conn", null,
                H2_BACKEND
                        ? com.legend.model.ConnectionDefinition.DatabaseType.H2
                        : com.legend.model.ConnectionDefinition.DatabaseType.DuckDB,
                new com.legend.model.ConnectionSpecification.InMemory(),
                new com.legend.model.AuthenticationSpec.NoAuth());
        return globalBuilt;
    }

    /** The CURRENT test's DDL scope: databases whose defining source sat
     * in the old per-module assembly (by TEXT identity, the same inputs
     * the retired module compile used). */
    private java.util.Set<String> ddlScopeDbs(List<String> mappingRefs,
            List<String> fileOnlyRefs) {
        java.util.Set<String> texts = new java.util.HashSet<>();
        for (com.legend.Compiler.ModelSource sh : sharedRaw) {
            texts.add(sh.text());
        }
        String parent = familyParent.get(currentFamilyKey);
        if (parent != null && familyRaw.containsKey(parent)) {
            for (com.legend.Compiler.ModelSource src : familyRaw.get(parent)) {
                texts.add(src.text());
            }
        }
        for (com.legend.Compiler.ModelSource src
                : familyRaw.getOrDefault(currentFamilyKey, List.of())) {
            texts.add(src.text());
        }
        com.legend.Compiler.ModelSource file = fileRaw.get(currentFileKey);
        if (file != null) {
            texts.add(file.text());
        }
        for (String ref : mappingRefs) {
            String defining = elementSource.get(ref);
            if (defining == null) {
                continue;
            }
            String fam = sourceFamily.get(defining);
            texts.addAll(fam != null
                    ? familySources.getOrDefault(fam, List.of(defining))
                    : List.of(defining));
        }
        for (String ref : fileOnlyRefs) {
            String defining = elementSource.get(ref);
            if (defining != null) {
                texts.add(defining);
            }
        }
        java.util.Set<String> dbs = new java.util.LinkedHashSet<>();
        for (java.util.Map.Entry<String, String> e : elementSource.entrySet()) {
            if (allDbBindings.containsKey(e.getKey())
                    && texts.contains(e.getValue())) {
                dbs.add(e.getKey());
            }
        }
        return dbs;
    }

    private void wallOnce(String wall) {
        if (reportedModuleWalls.add(wall)) {
            walls.add(wall);
        }
    }

    /** Seed replay: ALL DDL first, then the harness-owned SETUP FUNCTIONS
     * — shared-file units (legacy dataSeeds position) and BeforePackage
     * fns — execute as ordinary pure CALLS through the platform (K-natives
     * arc S4: Compiler's statement orchestration + executeInDb dispatch).
     * Setups the TEST BODY calls run at their own statement position in
     * TestBody — no pre-replay, engine-exact ordering. */
    private List<String> moduleDdl(
            com.legend.compiler.element.ModelContext ctx) {
        List<String> out = new ArrayList<>();
        java.util.Set<String> seenTables = new java.util.HashSet<>();
        java.util.Set<String> seenSchemas = new java.util.HashSet<>();
        for (String fqn : ctx.elementFqns()) {
            // GLOBAL-COMPILE scope guard: the one context holds every
            // corpus database — the session creates only the old
            // per-module source set's tables (ddlScopeDbs)
            if (!currentDdlDbs.contains(fqn)) {
                continue;
            }
            var dbOpt = ctx.findDatabase(fqn);
            if (dbOpt.isEmpty()) {
                continue;
            }
            var db = dbOpt.get();
            for (var td : db.tables()) {
                if (seenTables.add(td.name().toLowerCase())) {
                    out.add(com.legend.exec.Ddl.createTable(td, null));
                }
            }
            for (var schema : db.schemas()) {
                boolean defaultSchema = schema.name().isEmpty()
                        || "default".equals(schema.name());
                if (!defaultSchema && seenSchemas.add(schema.name())) {
                    out.add("CREATE SCHEMA IF NOT EXISTS " + schema.name());
                }
                for (var td : schema.tables()) {
                    // default-schema tables share the FLAT key — the same
                    // physical table declared both ways must create ONCE
                    String key = defaultSchema ? td.name().toLowerCase()
                            : (schema.name() + "." + td.name()).toLowerCase();
                    if (seenTables.add(key)) {
                        out.add(com.legend.exec.Ddl.createTable(td,
                                defaultSchema ? null : schema.name()));
                    }
                }
            }
        }
        return out;
    }

    private List<String> replaySeeds(String fqn,
            com.legend.compiler.element.ModelContext ctx, Connection conn) {
        return replaySeeds(fqn, List.of(), ctx, conn);
    }

    private List<String> replaySeeds(String fqn, List<String> crossRefs,
            com.legend.compiler.element.ModelContext ctx, Connection conn) {
        // MODULE-DERIVED DDL (audit 19d B6 / task #55): every table of
        // every database the test's module compiled, spelled by Ddl.java
        // from the PARSED store — the regex extraction (tableDefsAll/
        // seedColumnTypes/pickBySeed, the last surviving shadow parser)
        // is retired. Same-named tables dedup first-wins (module order),
        // the same arbitration the module's element dedup already applies.
        List<String> allSeeds = moduleDdl(ctx);
        List<String> failedSeeds = new ArrayList<>();
        for (String sql : allSeeds) {
            for (String raw : com.legend.sql.RawSql.splitStatements(sql)) {
                // module DDL adapts to the SESSION: raw for the H2 sweep,
                // the DuckDB-target boundary translation otherwise
                String stmt = H2_BACKEND ? raw
                        : com.legend.exec.RawSqlBoundary.h2ToDuckDb(raw);
                // prepare(): DuckDB JDBC masks Statement.execute errors
                try (var st = conn.prepareStatement(stmt)) {
                    st.execute();
                } catch (Exception e) {
                    String head = stmt.strip().split("\n")[0];
                    failedSeeds.add(head + " => "
                            + String.valueOf(e.getMessage()).split("\n")[0]);
                }
            }
        }
        // shared-file zero-arg units first (the legacy dataSeeds position),
        // then this test's BeforePackage fns — each a real call through the
        // platform; parameterized shared fns run when a body CALLS them
        // CROSS-FAMILY runtime data (testProject: injection::testRuntime
        // named from tests/advanced — the tables exist via module DDL but
        // sit EMPTY). NARROW by design (probe-and-revert #13: v1 clobbered
        // shared tables, v2's broad prefix match broke setup order
        // estate-wide): only refs whose DEFINING FAMILY differs from the
        // current family; only the LONGEST BeforePackage prefix of each
        // ref; a SEPARATE dedup set so the own-family loops below keep
        // their exact order; failures go to a scratch list — a foreign
        // setup must never poison the test's own seed scoring. Cross runs
        // FIRST so shared-named tables re-seed own-last (own wins).
        java.util.Set<String> crossExecuted = new java.util.HashSet<>();
        List<String> crossScratch = new ArrayList<>();
        for (String ref : crossRefs) {
            String defining = elementSource.get(ref);
            String fam = defining == null ? null : sourceFamily.get(defining);
            if (fam == null || fam.equals(currentFamilyKey)) {
                continue;
            }
            String[] best = null;
            for (String[] bp : beforePackagesParsed) {
                if (ref.startsWith(bp[0] + "::")
                        && (best == null || bp[0].length() > best[0].length())) {
                    best = bp;
                }
            }
            if (best != null && setupFnAsts.containsKey(best[1])
                    && isEffectfulSetup(best[1]) && crossExecuted.add(best[1])) {
                callSetup(best[1], ctx, conn, crossScratch);
            }
        }
        java.util.Set<String> executed = new java.util.HashSet<>();
        for (SetupUnit unit : sharedSetupUnits) {
            if (unit.zeroArg() && isEffectfulSetup(unit.fqn())
                    && executed.add(unit.fqn())) {
                callSetup(unit.fqn(), ctx, conn, failedSeeds);
            }
        }
        // OUTERMOST-FIRST (the JUnit BeforePackage nesting rule): a
        // broader package's setup must never run AFTER a narrower one —
        // it would re-create shared tables and wipe the narrow setup's
        // late inserts (fromMapping::setUp delegates to query::setUp,
        // whose tail rows vanished under collection order).
        List<String[]> matching = new ArrayList<>();
        for (String[] bp : beforePackagesParsed) {
            if (fqn.startsWith(bp[0] + "::") && setupFnAsts.containsKey(bp[1])
                    && isEffectfulSetup(bp[1])) {
                matching.add(bp);
            }
        }
        matching.sort(java.util.Comparator.comparingInt(bp -> bp[0].length()));
        if (System.getenv("LEGEND_LITE_SEED_TRACE") != null) {
            System.err.println("[seed-trace] test=" + fqn + " matching="
                    + matching.stream().map(bp -> bp[1]).toList()
                    + " effectful(fromMapping::setUp)="
                    + isEffectfulSetup(
                            "meta::relational::tests::fromMapping::setUp")
                    + " known=" + setupFnAsts.containsKey(
                            "meta::relational::tests::fromMapping::setUp")
                    + " knownQ=" + setupFnAsts.containsKey(
                            "meta::relational::tests::query::setUp")
                    + " effQ=" + isEffectfulSetup(
                            "meta::relational::tests::query::setUp")
                    + " body=" + setupFnAsts.get(
                            "meta::relational::tests::fromMapping::setUp"));
        }
        for (String[] bp : matching) {
            if (executed.add(bp[1])) {
                callSetup(bp[1], ctx, conn, failedSeeds);
            }
        }
        return failedSeeds;
    }

    /** Can the per-test module resolve this setup and every QUALIFIED
     * function name its transitive body calls? (Bare names resolve via
     * imports and are assumed local; the observed cross-family reach is
     * FQN-shaped.) A miss means the setup must run in the universe module
     * — decided BEFORE execution, so nothing ever partially runs twice. */
    private boolean preflightResolvable(String setupFqn,
            com.legend.compiler.element.ModelContext ctx) {
        if (safeFindFunction(ctx, setupFqn).isEmpty()) {
            return false;
        }
        java.util.Set<String> seen = new java.util.HashSet<>();
        java.util.ArrayDeque<String> work = new java.util.ArrayDeque<>();
        work.add(setupFqn);
        while (!work.isEmpty()) {
            String fqn = work.poll();
            if (!seen.add(fqn)) {
                continue;
            }
            List<com.legend.model.spec.ValueSpecification> body =
                    setupFnAsts.get(fqn);
            if (body == null) {
                continue;
            }
            java.util.Set<String> called = new java.util.HashSet<>();
            java.util.Set<String> elements = new java.util.HashSet<>();
            for (com.legend.model.spec.ValueSpecification stmt : body) {
                collectCalledFqns(stmt, called, elements);
            }
            for (String c : called) {
                if (!c.contains("::")) {
                    continue;
                }
                if (safeFindFunction(ctx, c).isEmpty()) {
                    return false;
                }
                work.add(c);
            }
            // qualified ELEMENT references (^ConnectionStore(element=
            // some::family::myDB)): the engine compiles the whole project,
            // so a setup naming a foreign family's store resolves there —
            // when the per-test module can't see it, the run belongs in
            // the setup universe (same rule as unresolvable calls)
            for (String p : elements) {
                if (!p.contains("::")) {
                    continue;
                }
                if (!ctx.isExecutionContextElement(p)
                        && ctx.findClass(p).isEmpty()
                        && ctx.findEnum(p).isEmpty()
                        && safeFindFunction(ctx, p).isEmpty()) {
                    return false;
                }
            }
        }
        return true;
    }

    private static List<com.legend.compiler.element.TypedFunction> safeFindFunction(
            com.legend.compiler.element.ModelContext ctx, String fqn) {
        try {
            return ctx.findFunction(fqn);
        } catch (RuntimeException brokenOverloads) {
            return List.of();
        }
    }

    /** QUALIFIED call names + element-pointer paths in a statement tree
     * (bare names skipped by the consumers). Walks constructor arguments
     * too — the propertyLevel setups name foreign stores inside
     * {@code ^ConnectionStore(element=...)}. */
    private static void collectCalledFqns(
            com.legend.model.spec.ValueSpecification v, java.util.Set<String> out,
            java.util.Set<String> elements) {
        if (v instanceof com.legend.model.spec.AppliedFunction af) {
            if (af.function().contains("::")) {
                out.add(af.function());
            }
            af.parameters().forEach(x -> collectCalledFqns(x, out, elements));
        } else if (v instanceof com.legend.model.spec.AppliedProperty ap) {
            collectCalledFqns(ap.receiver(), out, elements);
        } else if (v instanceof com.legend.model.spec.LambdaFunction lf) {
            lf.body().forEach(x -> collectCalledFqns(x, out, elements));
        } else if (v instanceof com.legend.model.spec.PureCollection pc) {
            pc.values().forEach(x -> collectCalledFqns(x, out, elements));
        } else if (v instanceof com.legend.model.spec.NewInstance ni) {
            ni.properties().values().forEach(ke ->
                    collectCalledFqns(ke.value(), out, elements));
        } else if (v instanceof com.legend.model.spec.NewInstanceCast nic) {
            collectCalledFqns(nic.src(), out, elements);
        } else if (v instanceof com.legend.model.spec.PackageableElementPtr ptr) {
            elements.add(ptr.fullPath());
        }
    }

    /** Does this setup function (transitively, over the parsed setup-fn
     * universe) reach a K-native seeding call? Shared files also define
     * plain HELPERS (testRuntime(), result-to-json utilities) — eagerly
     * calling those is meaningless and pollutes the failed-seed ledger. */
    private boolean isEffectfulSetup(String setupFqn) {
        return isEffectfulSetup(setupFqn, new java.util.HashSet<>());
    }

    private boolean isEffectfulSetup(String setupFqn, java.util.Set<String> seen) {
        List<com.legend.model.spec.ValueSpecification> body =
                setupFnAsts.get(setupFqn);
        if (body == null || !seen.add(setupFqn)) {
            return false;
        }
        java.util.Set<String> called = new java.util.HashSet<>();
        for (com.legend.model.spec.ValueSpecification stmt : body) {
            collectCalledNames(stmt, called);
        }
        if (called.contains("executeInDb") || called.contains("dropAndCreateTableInDb")
                || called.contains("dropAndCreateSchemaInDb")
                || called.contains("setupTestData")
                || called.contains("loadCsvToDbTable")) {
            return true;
        }
        for (String name : called) {
            for (String candidate : resolveSetupName(setupFqn, name)) {
                if (isEffectfulSetup(candidate, seen)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** EXACT resolution of a called name against the setup registry: an
     * FQN matches itself; a bare name resolves through the caller's OWN
     * package and its section's import wildcards — never by suffix
     * (audit 17: endsWith matched the wrong family's setup both ways). */
    private List<String> resolveSetupName(String callerFqn, String name) {
        if (name.contains("::")) {
            return setupFnAsts.containsKey(name) ? List.of(name) : List.of();
        }
        List<String> out = new ArrayList<>();
        int cut = callerFqn.lastIndexOf("::");
        if (cut > 0) {
            String samePkg = callerFqn.substring(0, cut) + "::" + name;
            if (setupFnAsts.containsKey(samePkg)) {
                out.add(samePkg);
            }
        }
        com.legend.model.ImportScope scope = setupFnImports.get(callerFqn);
        if (scope != null) {
            for (String w : scope.wildcards()) {
                String candidate = w + "::" + name;
                if (setupFnAsts.containsKey(candidate) && !out.contains(candidate)) {
                    out.add(candidate);
                }
            }
        }
        return out;
    }

    private static void collectCalledNames(
            com.legend.model.spec.ValueSpecification v, java.util.Set<String> out) {
        if (v instanceof com.legend.model.spec.AppliedFunction af) {
            String fn = af.function();
            // BOTH spellings: the bare name feeds the effect-keyword check
            // (executeInDb etc.); the FQN feeds resolveSetupName's exact
            // branch — stripping it made a delegating BeforePackage
            // (fromMapping::setUp -> query::setUp) resolve back to ITSELF
            // and read as effect-free, so its seeds never ran
            out.add(fn.contains("::") ? fn.substring(fn.lastIndexOf(':') + 1) : fn);
            if (fn.contains("::")) {
                out.add(fn);
            }
            af.parameters().forEach(x -> collectCalledNames(x, out));
        } else if (v instanceof com.legend.model.spec.AppliedProperty ap) {
            collectCalledNames(ap.receiver(), out);
        } else if (v instanceof com.legend.model.spec.LambdaFunction lf) {
            lf.body().forEach(x -> collectCalledNames(x, out));
        } else if (v instanceof com.legend.model.spec.PureCollection pc) {
            pc.values().forEach(x -> collectCalledNames(x, out));
        }
    }

    /** One zero-arg setup call through the full pipeline; failures feed the
     * failed-seed ledger (and the emptiness guard). */
    private void callSetup(String setupFqn,
            com.legend.compiler.element.ModelContext ctx, Connection conn,
            List<String> failedSeeds) {
        com.legend.model.spec.ValueSpecification call =
                com.legend.compiler.NameResolver.resolveQuery(
                        new com.legend.model.spec.AppliedFunction(
                                setupFqn, List.of()));
        // PREFLIGHT, not retry (audit 17): the universe path re-running a
        // PARTIALLY-executed setup doubles non-drop-guarded inserts, so the
        // module choice is made BEFORE anything executes — if the per-test
        // module cannot resolve the setup or any QUALIFIED call in its
        // transitive body, the whole run happens in the setup universe.
        com.legend.compiler.element.ModelContext target =
                preflightResolvable(setupFqn, ctx) ? ctx : setupUniverseContext();
        try {
            com.legend.Compiler.executeResolved(call, target, "rcorpus::Rt", conn,
                    failedSeeds::add);
        } catch (Exception e) {
            failedSeeds.add("setup " + setupFqn + "() => "
                    + String.valueOf(e.getMessage()).split("\n")[0]);
        }
    }





    // ===== assertion evaluation =====















    // ===== pure literal parsing (expected values) =====













    // ===== text machinery =====



    // ===== name qualification (imports) =====





    // ===== scoreboard =====

    public static void writeScoreboard(Path out, Map<String, List<Outcome>> byFamily,
                                       List<String> walls, String header) throws Exception {
        StringBuilder sb = new StringBuilder(header);
        int pass = 0;
        int fail = 0;
        int error = 0;
        int shape = 0;
        Map<String, Integer> buckets = new LinkedHashMap<>();
        int diffPass = 0;
        // sqldiff-pass column sits LAST: the regression gate's baseline
        // parser reads the pass column POSITIONALLY (cells[3])
        sb.append("\n| family | tests | pass | fail | error | shape | sqldiff-pass |\n|---|---|---|---|---|---|---|\n");
        for (var e : byFamily.entrySet()) {
            int p = 0;
            int f = 0;
            int er = 0;
            int sh = 0;
            int dp = 0;
            for (Outcome o : e.getValue()) {
                switch (o.status()) {
                    case PASS -> {
                        p++;
                        if (o.sqlDiffs() > 0) {
                            dp++;
                        }
                    }
                    case FAIL -> f++;
                    case ERROR -> {
                        er++;
                        buckets.merge(o.detail(), 1, Integer::sum);
                    }
                    case SHAPE -> sh++;
                    case UNSUPPORTED -> throw new IllegalStateException(
                            "UNSUPPORTED is a portability-sweep outcome —"
                            + " the DuckDB scoreboard must never see it");
                }
            }
            pass += p;
            fail += f;
            error += er;
            shape += sh;
            diffPass += dp;
            sb.append("| ").append(e.getKey()).append(" | ").append(e.getValue().size())
                    .append(" | ").append(p).append(" | ").append(f).append(" | ")
                    .append(er).append(" | ").append(sh).append(" | ").append(dp)
                    .append(" |\n");
        }
        sb.append("| **total** | ").append(pass + fail + error + shape).append(" | **")
                .append(pass).append("** | ").append(fail).append(" | ")
                .append(error).append(" | ").append(shape).append(" | ")
                .append(diffPass).append(" |\n");
        sb.append("\n### mapping walls (dropped at assembly)\n\n");
        for (String w : walls) {
            sb.append("- ").append(w).append('\n');
        }
        sb.append("\n### top error buckets\n\n");
        buckets.entrySet().stream()
                .sorted((a, b) -> b.getValue() - a.getValue())
                .limit(30)
                .forEach(e -> sb.append("- ").append(e.getValue()).append("x ")
                        .append(e.getKey()).append('\n'));
        sb.append("\n### per-test outcomes (non-passing)\n\n");
        for (var e : byFamily.entrySet()) {
            for (Outcome o : e.getValue()) {
                if (o.status() != Status.PASS) {
                    String d = o.detail().replace("\n", "\\n");
                    // C0.1: 300 chars destroyed the got-side of every long
                    // SQL/plan diff (blocked 10 FAILs from diagnosis) —
                    // FAIL rows keep the full diff up to 4000; ERROR/SHAPE
                    // messages are short and bucketed
                    int cap = o.status() == Status.FAIL ? 4000 : 300;
                    sb.append("- ").append(o.status()).append(' ')
                            .append(o.test().substring(o.test().lastIndexOf("::") + 2))
                            .append(" [").append(e.getKey()).append("]: ")
                            .append(d, 0, Math.min(cap, d.length()))
                            .append('\n');
                }
            }
        }
        Files.writeString(out, sb.toString());
    }
}
