// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend.rcorpus;


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

    /** The m2 corpus's OUTER provenance, named ONCE: legend-pure test
     *  sources parse at PLATFORM (compileLegendGrammar payloads get
     *  LEGEND_ENGINE inside EngineTestExecutor — the engine's own two-level
     *  architecture). */
    private static com.legend.model.ParsedModel corpusParse(
            com.legend.lexer.TokenStream tokens) {
        return com.legend.parser.ElementParser.parse(tokens,
                com.legend.parser.Dialect.LEGEND_PLATFORM);
    }

    private static com.legend.model.ParsedModel corpusParse(String source) {
        return corpusParse(com.legend.lexer.Lexer.tokenize(source));
    }

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

    /** ONE session factory — dialect and connection bind together.
     *  H2 keeps instance-per-session (1.6ms/boot: instances ARE cheap
     *  catalogs); DuckDB sessions are attached-catalog workspaces over
     *  one long-lived instance ({@link DuckWorkspaces} — the old
     *  per-session native boot cost 19.4ms x 938 sessions/run). */
    static Connection openSession() throws java.sql.SQLException {
        // TEMPORARY (2026-08-15 wall accounting): session-open cost
        long st0 = System.nanoTime();
        try {
            if (H2_BACKEND) {
                return DriverManager.getConnection("jdbc:h2:mem:rcorpus"
                        + SESSION_IDS.getAndIncrement()
                        + com.legend.exec.H2Settings.SETTINGS, "sa", "");
            }
            // NOTE (2026-08-15 perf program): txn-batching the seed
            // writes was BUILT AND MEASURED at zero savings — DuckDB's
            // per-statement cost is parse+plan+JNI, not commit — and
            // its DDL-bearing variant hit a 1.1.3 native abort
            // (DuckTransaction::Commit -> std::terminate through JNI).
            // The remaining seed cost is per-statement intrinsic;
            // reducing it means changing the statement stream
            // (multi-row consolidation), a fidelity decision, not a
            // transport tweak.
            return DuckWorkspaces.open();
        } finally {
            com.legend.exec.TimingLedger.add("session.open",
                    System.nanoTime() - st0);
        }
    }

    /** Class-FQN -> defining file (the corpus-wide index, incl. the M2M
     * platform test root) — set by the runner harness; null = disabled. */
    public java.util.function.Function<String, java.nio.file.Path> classLookup;

    public record Outcome(String test, Status status, String detail,
            int sqlDiffs, int advisory, int rescued) {
        /** Non-PASS / non-Ran outcomes carry no SQL-diff channel. */
        public Outcome(String test, Status status, String detail) {
            this(test, status, detail, 0, 0, 0);
        }

        /** Pre-F2.1 arity (sqlDiffs only). */
        public Outcome(String test, Status status, String detail,
                int sqlDiffs) {
            this(test, status, detail, sqlDiffs, 0, 0);
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
    private final java.util.Set<String> reportedModuleWalls = new java.util.HashSet<>();

    public Runner(List<String> sharedSources, List<String> seedSources) {
        for (int i = 0; i < sharedSources.size(); i++) {
            sharedRaw.add(new com.legend.Compiler.ModelSource(
                    "shared-" + i + ".pure", sharedSources.get(i)));
        }
        // shared files join the setup UNIVERSE too (relationalSetUp.pure
        // defines createTablesAndFillDb — cross-family setups call it)
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
                unit = corpusParse(src);
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
            List<com.legend.protocol.spec.ValueSpecification> body,
            com.legend.model.ImportScope imports) {
    }

    private final Map<String, FnDef> fnIndex = new LinkedHashMap<>();

    private final Map<String, java.util.List<com.legend.protocol.spec.ValueSpecification>>
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
            unit = corpusParse(source);
        } catch (RuntimeException e) {
            return;   // unparseable library file: its elements stay dark
        }
        // THE PLATFORM-NAMESPACE GUARD (V7 tenet correction 2026-08-28,
        // user catch): a library source defining meta::pure::functions::
        // elements would import the REFERENCE IMPLEMENTATION's stdlib
        // into our runtime model. The platform stdlib is OURS (registry
        // natives, signatures verified against the real sources);
        // reference checkouts feed test FIXTURES only. Refuse LOUDLY —
        // never a silent smuggle.
        for (com.legend.model.PackageableElement el : unit.elements()) {
            if (el.qualifiedName().startsWith("meta::pure::functions::")) {
                throw new IllegalStateException("library source defines"
                        + " platform-namespace element '"
                        + el.qualifiedName() + "' — the platform stdlib"
                        + " is legend-lite's own (registry natives);"
                        + " reference sources are spec and test input,"
                        + " never runtime components");
            }
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
        if (familyKey != null) {
            familySources.computeIfAbsent(familyKey,
                    k -> new ArrayList<>()).add(source);
        }
    }

    private final Map<String, List<String>> familySources = new LinkedHashMap<>();


    /** FQN -> defining SOURCE for every parsed corpus element: module
     * assembly pulls the defining file when a test references a mapping
     * outside its family (graphFetch trees over the embedded family's
     * model — audit-17 bucket cluster). */
    private final Map<String, String> elementSource = new LinkedHashMap<>();

    /** Parse a source and collect zero-arg function ASTs + BeforePackage
     * stereotyped functions (Phase D discovery — no regex). */
    private void collectSetups(String source) {
        com.legend.model.ParsedModel unit;
        try {
            unit = corpusParse(source);
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
            // invisible to discovery AND to EngineTestExecutor (audit 19d B1 — the
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
            unit = corpusParse(source);
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

    /**
     * AUTHORITATIVE CENSUS — keyed by test FQN so the repeated registration of
     * a shared source file cannot double-count. External greps over the corpus
     * kept disagreeing with the runner's own discovery (by 4 to 29 depending on
     * the method), which made the denominator perpetually arguable. These sets
     * ARE the denominator: they are populated by the discovery path itself.
     */
    public static final java.util.Set<String> CENSUS_RUNNABLE =
            new java.util.LinkedHashSet<>();

    /** Test FQN -> the stereotype that excluded it ({@code ToFix} etc.). */
    public static final Map<String, String> CENSUS_EXCLUDED =
            new LinkedHashMap<>();

    /**
     * Run the upstream-skipped tests too ({@code -Drcorpus.includeExcluded}).
     * They are excluded by default for engine-harness parity — but a corpus you
     * only ever measure at 94% cannot tell you what is left. In this mode every
     * test in core_relational runs and lands in the scoreboard, so each row ends
     * up either passing or explicitly classified.
     */
    public static final boolean INCLUDE_EXCLUDED =
            Boolean.getBoolean("rcorpus.includeExcluded");

    /** Which {@code test} stereotype excludes {@code f}, or null. */
    static String excludeReasonOf(com.legend.model.FunctionDefinition f) {
        for (com.legend.model.StereotypeApplication st : f.stereotypes()) {
            String profile = st.profileName();
            if (!(profile.equals("test")
                    || profile.equals("meta::pure::profiles::test"))) {
                continue;
            }
            switch (st.stereotypeName()) {
                case "ToFix", "Ignore", "ExcludeAlloy" -> {
                    return st.stereotypeName();
                }
                default -> { }
            }
        }
        return null;
    }

    public static List<ParsedTest> discoverTests(String source) {
        List<ParsedTest> out = new ArrayList<>();
        com.legend.model.ParsedModel unit;
        try {
            unit = corpusParse(source);
        } catch (RuntimeException e) {
            return out;   // unparseable file: walled at model-build time
        }
        for (com.legend.model.PackageableElement el : unit.elements()) {
            if (!(el instanceof com.legend.model.FunctionDefinition f)) {
                continue;
            }
            TestKind kind = testKindOf(f);
            if (kind == TestKind.TEST) {
                CENSUS_RUNNABLE.add(f.qualifiedName());
                out.add(new ParsedTest(f.qualifiedName(), f,
                        unit.elementImports().get(f.qualifiedName())));
            } else if (kind == TestKind.EXCLUDED) {
                CENSUS_EXCLUDED.putIfAbsent(f.qualifiedName(), excludeReasonOf(f));
                if (INCLUDE_EXCLUDED) {
                    out.add(new ParsedTest(f.qualifiedName(), f,
                            unit.elementImports().get(f.qualifiedName())));
                }
            }
        }
        return out;
    }

    /** The test's import scope: its section's imports + its own package
     * (implicit in real pure). */
    private static com.legend.model.ImportScope importScopeOf(ParsedTest t) {
        List<String> wildcards = new ArrayList<>();
        if (t.imports() != null) {
            wildcards.addAll(t.imports().wildcards());
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
        return new com.legend.model.ImportScope(wildcards);
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
    private List<com.legend.protocol.spec.ValueSpecification> expandHelperCalls(
            List<com.legend.protocol.spec.ValueSpecification> stmts,
            ParsedTest t, int depth) {
        if (depth >= 3) {
            return stmts;
        }
        List<com.legend.protocol.spec.ValueSpecification> out = new ArrayList<>();
        for (com.legend.protocol.spec.ValueSpecification stmt : stmts) {
            FnDef callee = null;
            com.legend.protocol.spec.AppliedFunction call = null;
            String letName = null;
            if (stmt instanceof com.legend.protocol.spec.AppliedFunction af
                    && !af.function().equals("letFunction")
                    // assertEqualsH2Compatible is HARNESS vocabulary
                    // (EngineTestExecutor's /3 arm verifies by rows through the H2
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
                        && containsExecuteShapeDeep(fd.body(), t, 0)) {
                    // execute-shape gate only (the old widened
                    // assert-helper gate regressed 600+ tests; the
                    // try-run-only assertExpansion flag died with that
                    // lane — assert-in-helper no-execute bodies wall
                    // honestly as unknown-function now, Phase-B fuel)
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
                    && stmt instanceof com.legend.protocol.spec.AppliedFunction lf0
                    && lf0.function().equals("letFunction")
                    && lf0.parameters().size() == 2
                    && lf0.parameters().get(0)
                            instanceof com.legend.protocol.spec.CString ln0
                    && lf0.parameters().get(1)
                            instanceof com.legend.protocol.spec.AppliedFunction af2
                    && !af2.function().equals("letFunction")) {
                String fqn2 = af2.function().contains("::")
                        ? af2.function() : qualify(af2.function(), t);
                FnDef fd2 = fnIndex.get(fqn2 + "/" + af2.parameters().size());
                com.legend.protocol.spec.ValueSpecification last2 =
                        fd2 == null || fd2.body().isEmpty() ? null
                                : fd2.body().get(fd2.body().size() - 1);
                boolean pairIdiom = fd2 != null
                        && last2 instanceof com.legend.protocol.spec.AppliedFunction pl2
                        && pl2.function().endsWith("pair")
                        && containsExecuteShapeDeep(fd2.body(), t, 0);
                // ...and the SINGLE-EXPRESSION execute wrapper
                // (executeInternal(f) = execute(f, mapping, runtime, ext)):
                // nothing to dismember — the body IS the execute call, so
                // the let rebinds to it directly (router composition tests)
                boolean singleExecute = fd2 != null
                        && fd2.body().size() == 1
                        && fd2.body().get(0)
                                instanceof com.legend.protocol.spec.AppliedFunction ef2
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
                out.add(new com.legend.protocol.spec.AppliedFunction("letFunction",
                        List.of(new com.legend.protocol.spec.CString(
                                        callee.params().get(i)),
                                call.parameters().get(i))));
            }
            List<com.legend.protocol.spec.ValueSpecification> expanded =
                    expandHelperCalls(callee.body(), t, depth + 1);
            if (letName != null && !expanded.isEmpty()) {
                com.legend.protocol.spec.ValueSpecification last =
                        expanded.remove(expanded.size() - 1);
                if (last instanceof com.legend.protocol.spec.AppliedFunction ll
                        && ll.function().equals("letFunction")
                        && ll.parameters().size() == 2) {
                    last = ll.parameters().get(1);
                }
                // a trailing bare `$x;` whose x is ALREADY a spliced let
                // would rebind letName to itself downstream of renames —
                // keep the reference as-is only when names DIFFER; a
                // same-name rebinding (let result = $result) is dropped
                if (last instanceof com.legend.protocol.spec.Variable tv
                        && tv.name().equals(letName)) {
                    out.addAll(expanded);
                    continue;
                }
                out.addAll(expanded);
                out.add(new com.legend.protocol.spec.AppliedFunction(
                        "letFunction", List.of(
                                new com.legend.protocol.spec.CString(letName),
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
            com.legend.protocol.spec.ValueSpecification v) {
        return v instanceof com.legend.protocol.spec.AppliedFunction af
                && af.function().equals("new")
                && !af.parameters().isEmpty()
                && af.parameters().get(0)
                        instanceof com.legend.protocol.spec.PackageableElementPtr pe
                && (pe.fullPath().equals("RelationalDebugContext")
                        || pe.fullPath().equals("meta::relational::runtime"
                                + "::RelationalDebugContext"));
    }

    private static boolean containsDebugArityExecute(
            com.legend.protocol.spec.ValueSpecification v) {
        if (v instanceof com.legend.protocol.spec.AppliedFunction af) {
            String simple = af.function()
                    .substring(af.function().lastIndexOf(':') + 1);
            if (simple.equals("execute") && af.parameters().size() >= 5
                    && af.parameters().stream().anyMatch(
                            Runner::isDebugContextNew)) {
                return true;
            }
            for (com.legend.protocol.spec.ValueSpecification p2 : af.parameters()) {
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
            List<com.legend.protocol.spec.ValueSpecification> stmts,
            ParsedTest t, int depth) {
        if (containsExecuteShape(stmts)) {
            return true;
        }
        if (depth >= 3) {
            return false;
        }
        for (com.legend.protocol.spec.ValueSpecification stmt : stmts) {
            if (stmt instanceof com.legend.protocol.spec.AppliedFunction af
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
            List<com.legend.protocol.spec.ValueSpecification> stmts) {
        java.util.ArrayDeque<com.legend.protocol.spec.ValueSpecification> work =
                new java.util.ArrayDeque<>(stmts);
        while (!work.isEmpty()) {
            var v = work.poll();
            if (v instanceof com.legend.protocol.spec.AppliedFunction af) {
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
            } else if (v instanceof com.legend.protocol.spec.AppliedProperty ap) {
                work.add(ap.receiver());
            } else if (v instanceof com.legend.protocol.spec.LambdaFunction lf) {
                work.addAll(lf.body());
            } else if (v instanceof com.legend.protocol.spec.PureCollection pc) {
                work.addAll(pc.values());
            }
        }
        return false;
    }


    private static boolean containsCallNamed(
            com.legend.protocol.spec.ValueSpecification n, String name) {
        if (n instanceof com.legend.protocol.spec.AppliedFunction af) {
            if (name.equals(af.function()
                    .substring(af.function().lastIndexOf(':') + 1))) {
                return true;
            }
            for (com.legend.protocol.spec.ValueSpecification p : af.parameters()) {
                if (containsCallNamed(p, name)) {
                    return true;
                }
            }
        } else if (n instanceof com.legend.protocol.spec.AppliedProperty ap) {
            return containsCallNamed(ap.receiver(), name);
        } else if (n instanceof com.legend.protocol.spec.LambdaFunction lf) {
            for (com.legend.protocol.spec.ValueSpecification b : lf.body()) {
                if (containsCallNamed(b, name)) {
                    return true;
                }
            }
        } else if (n instanceof com.legend.protocol.spec.PureCollection pc) {
            for (com.legend.protocol.spec.ValueSpecification e : pc.values()) {
                if (containsCallNamed(e, name)) {
                    return true;
                }
            }
        }
        return false;
    }


    private static boolean containsPropertyNamed(
            com.legend.protocol.spec.ValueSpecification n, String name) {
        if (n instanceof com.legend.protocol.spec.AppliedProperty ap) {
            return name.equals(ap.property())
                    || containsPropertyNamed(ap.receiver(), name);
        }
        if (n instanceof com.legend.protocol.spec.AppliedFunction af) {
            for (com.legend.protocol.spec.ValueSpecification p
                    : af.parameters()) {
                if (containsPropertyNamed(p, name)) {
                    return true;
                }
            }
        }
        if (n instanceof com.legend.protocol.spec.PureCollection pc) {
            for (com.legend.protocol.spec.ValueSpecification e : pc.values()) {
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


    /** A no-execute body attempted through the FULL pipeline: PASS/FAIL
     * outcomes are real; Unsupported/errors return null so the caller
     * falls back to the functional-bucket SHAPE. */
    /** The try-run result: a REAL verdict (PASS/FAIL) stands; otherwise
     * {@code wall} carries the pipeline's ACTUAL diagnosis so the
     * functional-bucket SHAPE row is not diagnosis-free (taxonomy X2:
     * 81 rows — 20% of all failures — had no diagnosis attached). */
    /** Run one PARSED test through the pipeline. */
    public Outcome run(ParsedTest t) {
        // TEMPORARY (2026-08-15 wall accounting): total per-test wall
        long tt0 = System.nanoTime();
        try {
            return run0(t);
        } finally {
            com.legend.exec.TimingLedger.add("test.wall",
                    System.nanoTime() - tt0);
            com.legend.exec.TimingLedger.addNamed(t.fqn(),
                    System.nanoTime() - tt0);
        }
    }

    private Outcome run0(ParsedTest t) {
        com.legend.harness.H2Verify.CURRENT_TEST.set(t.fqn());
        // forced-isolation tests: value-frame row compare declines
        // (H2Verify.FORCED_MECHANISM) — the golden pins an engine
        // debug-mechanism strategy, not default-mode semantics
        FnDef selfFd = fnIndex.get(t.fqn() + "/0");
        com.legend.harness.H2Verify.FORCED_MECHANISM.set(selfFd != null
                && selfFd.body().stream()
                        .anyMatch(Runner::containsDebugArityExecute));
        com.legend.lowering.StampCensus.CONTEXT.set(t.fqn());
        com.legend.exec.CanonicalDivergence.CONTEXT_SOURCE =
                com.legend.lowering.StampCensus.CONTEXT::get;
        com.legend.exec.SqlTypeCensus.CONTEXT.set(t.fqn());
        // #67: record every raw corpus statement this test executes —
        // the H2 advisory second target replays them verbatim to verify
        // golden-SQL asserts by ROWS. Under a FAMILY session (#112) the
        // recording starts from the session's ledger (earlier tests'
        // seeds and mutations are part of this test's visible state);
        // after the run the ledger becomes the recording.
        List<String> recording = new ArrayList<>();
        com.legend.sql.dialect.RawSqlBoundary.record(recording);
        lastRunShared = false;
        try {
            return run0(t, recording);
        } finally {
            if (lastRunShared) {
                familySeedLedger.clear();
                for (String stmt : recording) {
                    // the inherited history is STATE only ("seeds and
                    // mutations", the #67 contract) — a test's read
                    // probes (H2Test's raw SELECT diagnostics) are its
                    // own surface, and replaying them against the H2
                    // mirror would fail siblings on dialect gaps the
                    // shared STATE never has
                    String head = stmt.stripLeading();
                    head = head.substring(0, Math.min(7, head.length()))
                            .toUpperCase(java.util.Locale.ROOT);
                    if (!head.startsWith("SELECT") && !head.startsWith("WITH")
                            && !head.startsWith("SHOW")
                            && !head.startsWith("EXPLAIN")) {
                        familySeedLedger.add(stmt);
                    }
                }
            }
            if (System.getenv("LL_LEDGER_DUMP") != null
                    && t.fqn().contains(System.getenv("LL_LEDGER_DUMP"))) {
                try {
                    java.nio.file.Files.write(
                            java.nio.file.Path.of("/tmp/ledger-dump.sql"),
                            recording);
                } catch (java.io.IOException ignore) {
                    // debug channel only
                }
            }
        }
    }

    private Outcome run0(ParsedTest t, List<String> recording) {
        // Statement-position HELPER calls β-expand (params bound as lets)
        // for TWO reasons, verified separable by experiment (audit 20
        // follow-up): (a) DISCOVERY — executeMappingRefs must see execute
        // calls inside helpers to assemble the right module; (b) ASSERT
        // VISIBILITY — a helper body carrying assertEquals is HARNESS
        // vocabulary the platform can never type (G: unknown function),
        // so EngineTestExecutor must see the expanded statements to intercept them.
        // The original execute-visibility rationale (audit 19d B1) is
        // OBSOLETE since B2b made execute a platform native — the sweep
        // with raw bodies regressed ONLY the assert-in-helper shape.
        List<com.legend.protocol.spec.ValueSpecification> body =
                expandHelperCalls(t.fn().body(), t, 0);
        // VACUOUS PLACEHOLDER: the engine's own suite contains tests
        // whose entire body is the literal `true` (testFailures.pure
        // failMoveFilterOnTop/BuildCorrelatedSubQuery — placeholders
        // for strategies the engine itself documents as failing).
        // The engine runs them as vacuous passes.
        if (body.size() == 1
                && body.get(0) instanceof com.legend.protocol.spec
                        .CBoolean cb && cb.value()) {
            return new Outcome(t.fqn(), Status.PASS,
                    "vacuous placeholder (engine body = true)");
        }
        try {
            // G4-vs-G5 wall attribution (user-ordered timing program):
            // the ledger's four sections + query.exec decompose
            // test.wall into module assembly / seeding / executor body
            // / DB execution / mirror — the unattributed remainder is
            // compile+lower+assert.
            long tCtx0 = System.nanoTime();
            com.legend.compiler.element.ModelContext ctx = globalContext();
            com.legend.exec.TimingLedger.add("ctx.module",
                    System.nanoTime() - tCtx0);
            // PER-PACKAGE WORKSPACES (census §10f/§10g: the engine's
            // grouping semantics, one isolation grant tighter): fresh
            // workspace + mirror at each package boundary, unconditional
            // sharing within the package — package-chain setups rebuild
            // shared tables just-in-time, so clobber is safe by
            // construction (the §10g differential: verdicts and observed
            // reads identical, the old shape router deleted).
            if (familyConn != null) {
                int cutW = t.fqn().lastIndexOf("::");
                String pkgW = cutW > 0 ? t.fqn().substring(0, cutW) : t.fqn();
                if (!pkgW.equals(currentSetupPkg)) {
                    beginFamilySession();   // fresh workspace + mirror
                }
            }
            boolean shared = familyConn != null
                    // inline testDataSetupCsv = the test's OWN data over
                    // the shared tables (DELETE+INSERT) — engine runs it
                    // on a FRESH test database; a private session is that
                    && !carriesInlineCsv(body);
            lastRunShared = shared;
            // a PRIVATE test's recording is its own history, not the
            // family ledger — its golden checks use the fresh-replay path
            com.legend.harness.ReplayOracle.mirrorSuspend(!shared);
            if (shared) {
                int cut = t.fqn().lastIndexOf("::");
                String pkg = cut > 0 ? t.fqn().substring(0, cut) : t.fqn();
                if (!pkg.equals(currentSetupPkg)) {
                    familySetupsDone.clear();
                    currentSetupPkg = pkg;
                }
            }
            Connection conn = shared ? familyConn : openSession();
            if (shared) {
                // this test's visible state includes everything the
                // session executed before it — the mirror replays it
                recording.addAll(0, familySeedLedger);
            }
            try {
                long tSeed0 = System.nanoTime();
                List<String> failedSeeds = replaySeeds(t.fqn(),
                        ctx, conn, shared);
                com.legend.exec.TimingLedger.add("seed.replay",
                        System.nanoTime() - tSeed0);
                seedFailures.addAll(failedSeeds);
                if (System.getenv("LL_TMP_DEBUG") != null
                        || System.getenv("LL_ORD_COUNT") != null) {
                    // wall-attribution marker: [plan-wall]/[walk]/[ord]
                    // prints that follow belong to THIS test
                    System.err.println("[run] " + t.fqn());
                }
                long rescued0 = com.legend.harness.H2Verify.M1_RESCUED.sum();
                // per-test TEXT-VERDICT attribution (the sqltext homework
                // roster, 2026-09-03): reason counters before/after
                java.util.Map<String, Long> tv0 = textVerdictSnapshot();
                long tExec0 = System.nanoTime();
                com.legend.harness.EngineTestExecutor.Outcome o = com.legend.harness.EngineTestExecutor.run(
                        ctx, body, importScopeOf(t), "rcorpus::Rt",
                        conn, !failedSeeds.isEmpty(), failedSeeds);
                com.legend.exec.TimingLedger.add("engine.exec",
                        System.nanoTime() - tExec0);
                for (var e : textVerdictSnapshot().entrySet()) {
                    long d = e.getValue() - tv0.getOrDefault(e.getKey(), 0L);
                    if (d > 0) {
                        com.legend.harness.SqlTextShapes.TEXT_VERDICT_ROSTER
                                .add(e.getKey() + " x" + d + " :: " + t.fqn());
                    }
                }
                // body-time setup failures (added via the sink DURING the
                // run) join the run-wide report too (audit 17)
                seedFailures.addAll(failedSeeds);
                return score(t.fqn(), o, (int) (com.legend.harness.H2Verify
                        .M1_RESCUED.sum() - rescued0));
            } finally {
                com.legend.harness.ReplayOracle.mirrorSuspend(false);
                if (shared) {
                    // live-shape census (t5 root cause): everything this
                    // test executed — module DDL AND setup-fn streams —
                    // updates the session's live table shapes
                } else {
                    conn.close();
                }
                // declaration-vs-fixture skew census (§4bZ) — both
                // session kinds; dedupe inside
                noteFixtureSkew(ctx, recording);
                // [1]-over-nullable census: the PLATFORM computed the
                // rows per compile (DeclaredCoercions pairing seam);
                // the harness only AGGREGATES across the corpus's
                // models — scoreboard state, witness sets dedupe
                ctx.requiredNullableCensus().forEach((bucket, ws) ->
                        REQUIRED_OVER_NULLABLE.computeIfAbsent(bucket,
                                k -> java.util.Collections.synchronizedSet(
                                        new java.util.TreeSet<>()))
                                .addAll(ws));
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
            com.legend.exec.CanonicalDivergence.noteWall(t.fqn(),
                    String.valueOf(e.getMessage()));
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

    private static Outcome score(String fqn,
            com.legend.harness.EngineTestExecutor.Outcome o, int rescued) {
        return switch (o) {
            case com.legend.harness.EngineTestExecutor.Outcome.Unsupported u ->
                    new Outcome(fqn, Status.SHAPE, u.reason());
            case com.legend.harness.EngineTestExecutor.Outcome.Ran r -> {
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
                        r.sqlDiffs().size(), r.advisory(), rescued);
            }
        };
    }





    /** THE one execution context (slice-1 job 1): the global compile
     * plus ONE overlay — the harness runtime {@code rcorpus::Rt} with an
     * EMPTY mapping list (every class dispatch threads the call site's
     * own mapping now; the ambient candidate list is deleted) and the
     * global database->connection bindings. Memoized once. */
    private com.legend.compiler.element.ModelContext globalContext() {
        long ct0 = System.nanoTime();
        try {
            com.legend.Compiler.BuiltModule built = globalModule();
            com.legend.compiler.element.PureModelContext base =
                    (com.legend.compiler.element.PureModelContext)
                            built.context();
            return overlayMemo.computeIfAbsent("", k ->
                    base.withExecutionOverlay(
                            new com.legend.model.RuntimeDefinition(
                                    "rcorpus::Rt",
                                    List.of(),
                                    allDbBindings, List.of()),
                            rcorpusConn));
        } finally {
            com.legend.exec.TimingLedger.add("ctx.overlay",
                    System.nanoTime() - ct0);
        }
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
        java.util.Map<String, String> byName = new java.util.HashMap<>();
        for (com.legend.Compiler.ModelSource src : sources) {
            byName.put(src.name(), src.text());
        }
        globalParsed = com.legend.Compiler.parseSources(sources,
                (name, err) -> parseWalls.add(name + " => " + err
                        + wallContext(byName.get(name), err)),
                com.legend.parser.Dialect.LEGEND_PLATFORM);
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
     * EngineTestExecutor — no pre-replay, engine-exact ordering. */
    /** One module-DDL unit: the session-dedup KEY (physical table
     * identity), the drop spelling for shape-clobber, and the create. */
    /** One module-DDL unit: the session-dedup KEY, the drop spelling,
     * the H2-flavored create (shape identity + the mirror's replay
     * stream), and the DuckDB-target create (F7.4: spelled from the
     * TYPE, never text-rewritten). */
    /** Wall-clock spent in seed replay across the run — the per-family
     * seeding leg's before/after instrument (task #112). */
    public static final java.util.concurrent.atomic.AtomicLong SEED_NANOS =
            new java.util.concurrent.atomic.AtomicLong();
    public static final java.util.concurrent.atomic.AtomicLong SEED_CALLS =
            new java.util.concurrent.atomic.AtomicLong();
    public static final java.util.concurrent.atomic.AtomicLong DDL_NANOS =
            new java.util.concurrent.atomic.AtomicLong();

    /** PER-FAMILY SESSION (task #112, engine beforePackage semantics):
     * one connection per family, seeds replayed INCREMENTALLY — the
     * family's first test seeds its scope, later tests only add DDL
     * statements and setup fns not yet run this session. State carries
     * across a family's tests exactly like the engine's shared server
     * carries it across a package's tests; per-test order is unchanged. */
    private @com.legend.Nullable Connection familyConn;

    /** FIXTURE-SKEW CENSUS (charter §4bZ): the engine's own setup
     * streams create tables whose column KINDS contradict the
     * ###Relational declaration (InteractionTable.id VARCHAR(200)
     * created vs ID INT declared — relationalSetUp.pure:1397). Our
     * column stamps derive from the DECLARATION, so every skewed
     * column is a place the typed tree is honestly wrong about the
     * actual database — the named explanation for label-vs-wire
     * divergences the deleted coercion arms used to hide, and engine
     * test-data debt (docs/UPSTREAM_DEFECTS.md). Class key =
     * declared-vs-created kind pair; witnesses = table.column
     * [family]; the count pins in the corpus runner. */
    public static final java.util.Map<String, java.util.Set<String>>
            FIXTURE_SKEW = java.util.Collections.synchronizedMap(
                    new java.util.TreeMap<>());

    /** Run-wide AGGREGATE of the per-compile [1]-over-nullable census
     * (platform computes the rows on each ModelBuilder at the
     * DeclaredCoercions pairing seam; the harness merges across the
     * corpus's models). Bucket &rarr; witnesses; printed + ceiling-
     * pinned in the corpus runner. */
    public static final java.util.Map<String, java.util.Set<String>>
            REQUIRED_OVER_NULLABLE = java.util.Collections.synchronizedMap(
                    new java.util.TreeMap<>());
    private final java.util.Set<String> skewChecked =
            new java.util.HashSet<>();

    private void noteFixtureSkew(
            com.legend.compiler.element.ModelContext ctx,
            java.util.List<String> stmts) {
        java.util.Map<String, java.util.Map<String, String>> module = null;
        for (String raw : stmts) {
            String sql = raw.strip();
            if (!sql.toLowerCase(java.util.Locale.ROOT)
                    .startsWith("create table")) {
                continue;
            }
            int open = sql.indexOf('(');
            if (open <= 12) {
                continue;
            }
            // G3 undercount 1 (recorded 2026-08): a SCHEMA-QUALIFIED
            // create ("schema.table (...)") never matched the module
            // map's bare table keys, silently exempting whole tables
            // from the census
            String qualified = sql.substring(12, open).strip()
                    .toLowerCase(java.util.Locale.ROOT).replace("\"", "");
            int dot = qualified.lastIndexOf('.');
            String tname = dot >= 0
                    ? qualified.substring(dot + 1).strip() : qualified;
            // dedupe by the exact STATEMENT, not the table: a family
            // stream carries both the module-generated CREATE (declared
            // shape — never skewed) and the setup-fn's raw CREATE (the
            // fixture truth, last-write-wins in the live database) —
            // every distinct create text must be examined
            if (!skewChecked.add(currentFamilyKey + "|" + sql)) {
                continue;
            }
            if (module == null) {
                module = moduleColumnKinds(ctx);
            }
            java.util.Map<String, String> declared = module.get(tname);
            if (declared == null) {
                continue;
            }
            parseCreateColumns(sql, (col, typeTok) -> {
                String dk = declared.get(col);
                String fk = fixtureKind(typeTok);
                if (dk != null && fk != null && !dk.equals(fk)) {
                    FIXTURE_SKEW.computeIfAbsent(
                            "declared " + dk + ", created " + fk,
                            k -> java.util.Collections.synchronizedSet(
                                    new java.util.TreeSet<>()))
                            .add(tname + "." + col
                                    + " [" + currentFamilyKey + "]");
                }
            });
        }
    }

    /** The executed-DDL type token's pure kind — the fixture side of
     * the skew comparison (mirrors RelationalKinds.pureKindOf for the
     * token spellings the corpus setup streams actually use); null =
     * unmodeled token, skipped (never guessed). */
    private static @com.legend.Nullable String fixtureKind(String token) {
        return switch (token) {
            case "INT", "INTEGER", "BIGINT", "SMALLINT", "TINYINT"
                    -> "Integer";
            case "VARCHAR", "CHAR", "CHARACTER", "NVARCHAR", "CLOB",
                    "TEXT", "STRING" -> "String";
            case "FLOAT", "DOUBLE", "REAL" -> "Float";
            case "DECIMAL", "NUMERIC" -> "Decimal";
            case "BIT", "BOOLEAN" -> "Boolean";
            case "TIMESTAMP", "DATETIME", "SMALLDATETIME" -> "DateTime";
            case "DATE" -> "StrictDate";
            default -> null;
        };
    }


    /** Lowercase table name -> (lowercase column -> declared pure kind)
     * over EVERY database in the global model — the declared side of the
     * skew comparison. RE-SCOPED with the module-DDL deletion (census
     * §10a: the instrument survives, its scope is now the whole corpus —
     * measurement only, nothing is created from this walk). */
    private java.util.Map<String, java.util.Map<String, String>>
            moduleColumnKinds(com.legend.compiler.element.ModelContext ctx) {
        java.util.Map<String, java.util.Map<String, String>> out =
                new java.util.HashMap<>();
        for (String fqn : ctx.elementFqns()) {
            var dbOpt = ctx.findDatabase(fqn);
            if (dbOpt.isEmpty()) {
                continue;
            }
            var db = dbOpt.get();
            java.util.List<com.legend.model.DatabaseDefinition
                    .TableDefinition> tds = new ArrayList<>(db.tables());
            db.schemas().forEach(s -> tds.addAll(s.tables()));
            for (var td : tds) {
                java.util.Map<String, String> cols =
                        out.computeIfAbsent(td.name().toLowerCase(
                                java.util.Locale.ROOT),
                                k -> new java.util.HashMap<>());
                for (var cd : td.columns()) {
                    cols.putIfAbsent(cd.name().toLowerCase(
                            java.util.Locale.ROOT),
                            com.legend.normalizer.RelationalKinds
                                    .pureKindOf(cd.dataType()));
                }
            }
        }
        return out;
    }

    /** Column entries of an executed CREATE TABLE: (lowercase name,
     * UPPERCASE bare type token) — the same crude statement sniffing
     * {@link #noteExecutedDdl} established for live shapes; constraint
     * clauses skip. */
    private static void parseCreateColumns(String createSql,
            java.util.function.BiConsumer<String, String> sink) {
        int open = createSql.indexOf('(');
        int close = createSql.lastIndexOf(')');
        if (open < 0 || close <= open) {
            return;
        }
        String list = createSql.substring(open + 1, close);
        int depth = 0;
        int start = 0;
        java.util.List<String> entries = new ArrayList<>();
        for (int i = 0; i < list.length(); i++) {
            char c = list.charAt(i);
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
            } else if (c == ',' && depth == 0) {
                entries.add(list.substring(start, i));
                start = i + 1;
            }
        }
        entries.add(list.substring(start));
        for (String e : entries) {
            String s = e.strip();
            if (s.isEmpty()) {
                continue;
            }
            String[] tok = s.split("\\s+");
            if (tok.length < 2) {
                continue;
            }
            String name = tok[0].replace("\"", "").replace("`", "");
            String head = name.toUpperCase(java.util.Locale.ROOT);
            String type = tok[1];
            int p = type.indexOf('(');
            if (p > 0) {
                type = type.substring(0, p);
            }
            // G3 undercount 2 (recorded 2026-08): a COLUMN NAMED with a
            // constraint word ("key INT") was skipped as a clause. The
            // disambiguator is the second token: a real constraint
            // clause never follows its keyword with a bare type token
            // ("PRIMARY KEY (id)" / "CONSTRAINT fk ..."), a column
            // always does.
            if ((head.equals("PRIMARY") || head.equals("CONSTRAINT")
                    || head.equals("FOREIGN") || head.equals("UNIQUE")
                    || head.equals("KEY") || head.equals("CHECK"))
                    && fixtureKind(type.toUpperCase(
                            java.util.Locale.ROOT)) == null) {
                continue;
            }
            sink.accept(name.toLowerCase(java.util.Locale.ROOT),
                    type.toUpperCase(java.util.Locale.ROOT));
        }
    }

    private final java.util.Set<String> familySetupsDone = new java.util.HashSet<>();
    /** The package whose setups the session last replayed — the engine
     * runs BeforePackage functions per PACKAGE, so a package transition
     * re-arms the whole setup set (drop+create+fill re-establishes the
     * shared tables the previous package's setups clobbered). */
    private @com.legend.Nullable String currentSetupPkg;
    /** Every raw statement the family session has executed, in order —
     * the H2 advisory mirror replays a test's WHOLE session history
     * (skipped re-seeds included), or its fresh mirror starts empty. */
    private final List<String> familySeedLedger = new ArrayList<>();
    /** Whether the LAST run(t) executed on the shared family session —
     * a test whose DDL scope CONFLICTS with the session's established
     * table shapes runs on a private per-test session instead (its
     * state and recording never join the family ledger). */
    private boolean lastRunShared;

    private static boolean carriesInlineCsv(
            List<com.legend.protocol.spec.ValueSpecification> body) {
        java.util.ArrayDeque<com.legend.protocol.spec.ValueSpecification> q =
                new java.util.ArrayDeque<>(body);
        while (!q.isEmpty()) {
            com.legend.protocol.spec.ValueSpecification v = q.poll();
            if (v instanceof com.legend.protocol.spec.NewInstance ni
                    && ni.first("testDataSetupCsv") != null) {
                return true;
            }
            q.addAll(v.children());
        }
        return false;
    }

    /** True when any table this test's scope declares already exists in
     * the family session under a DIFFERENT shape. */
    public void beginFamilySession() throws java.sql.SQLException {
        endFamilySession();
        // -Drcorpus.perTestSessions bypasses family sessions (A/B lever:
        // familyConn == null routes every test through the old per-test
        // fresh-session path)
        if (System.getProperty("rcorpus.perTestSessions") != null) {
            return;
        }
        familyConn = openSession();
        // the INCREMENTAL H2 mirror rides the family session (DuckDB
        // sweeps only — the h2 sweep verifies session-direct): one live
        // mirror fed each ledger statement ONCE, replacing the
        // fresh-replay-of-full-history per verification
        if (!H2_BACKEND && com.legend.harness.H2Verify.ready()) {
            mirrorConn = DriverManager.getConnection(
                    "jdbc:h2:mem:famMirror" + SESSION_IDS.getAndIncrement()
                            + com.legend.exec.H2Settings.SETTINGS, "sa", "");
            com.legend.harness.ReplayOracle.mirrorBegin(mirrorConn);
        }
    }

    private @com.legend.Nullable Connection mirrorConn;

    public void endFamilySession() {
        com.legend.harness.ReplayOracle.mirrorEnd();
        if (mirrorConn != null) {
            try {
                mirrorConn.close();
            } catch (Exception ignore) {
                // mirror teardown must never poison the next family
            }
            mirrorConn = null;
        }
        if (familyConn != null) {
            try {
                familyConn.close();
            } catch (Exception ignore) {
                // a session that fails to close cannot poison the next
                // family: the reference is dropped either way
            }
            familyConn = null;
        }
        familySetupsDone.clear();
        familySeedLedger.clear();
        currentSetupPkg = null;
    }

    /** True when {@code fnFqn} already ran this family session (and
     * marks it run). Per-test mode (no family session) never skips. */
    private boolean setupAlreadyRun(boolean shared, String fnFqn) {
        return shared && !familySetupsDone.add(fnFqn);
    }

    private List<String> replaySeeds(String fqn,
            com.legend.compiler.element.ModelContext ctx, Connection conn,
            boolean shared) {
        long t0 = System.nanoTime();
        try {
            return replaySeeds0(fqn, ctx, conn, shared);
        } finally {
            SEED_NANOS.addAndGet(System.nanoTime() - t0);
            SEED_CALLS.incrementAndGet();
        }
    }

    private List<String> replaySeeds0(String fqn,
            com.legend.compiler.element.ModelContext ctx, Connection conn,
            boolean shared) {
        // PACKAGE-CHAIN provisioning ONLY (census §10e/§10g): a test's
        // world is what its own package chain's authored setups build —
        // engine semantics. The module-derived DDL guessing layer and
        // the cross-family demand layer are deleted; the ONE remaining
        // declaration seam is inline-CSV creation (CsvSeed's model-typed
        // create branch, §9a).
        List<String> failedSeeds = new ArrayList<>();
        java.util.Set<String> executed = new java.util.HashSet<>();
        for (SetupUnit unit : sharedSetupUnits) {
            if (unit.zeroArg() && isEffectfulSetup(unit.fqn())
                    && executed.add(unit.fqn())
                    && !setupAlreadyRun(shared, unit.fqn())) {
                int before = failedSeeds.size();
                callSetup(unit.fqn(), ctx, conn, failedSeeds);
                if (failedSeeds.size() > before) {
                    familySetupsDone.remove(unit.fqn());
                }
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
        for (String[] bp : matching) {
            if (executed.add(bp[1]) && !setupAlreadyRun(shared, bp[1])) {
                int before = failedSeeds.size();
                callSetup(bp[1], ctx, conn, failedSeeds);
                if (failedSeeds.size() > before) {
                    familySetupsDone.remove(bp[1]);
                }
            }
        }
        return failedSeeds;
    }



    /** Does this setup function (transitively, over the parsed setup-fn
     * universe) reach a K-native seeding call? Shared files also define
     * plain HELPERS (testRuntime(), result-to-json utilities) — eagerly
     * calling those is meaningless and pollutes the failed-seed ledger. */
    private boolean isEffectfulSetup(String setupFqn) {
        return isEffectfulSetup(setupFqn, new java.util.HashSet<>());
    }

    private boolean isEffectfulSetup(String setupFqn, java.util.Set<String> seen) {
        List<com.legend.protocol.spec.ValueSpecification> body =
                setupFnAsts.get(setupFqn);
        if (body == null || !seen.add(setupFqn)) {
            return false;
        }
        java.util.Set<String> called = new java.util.HashSet<>();
        for (com.legend.protocol.spec.ValueSpecification stmt : body) {
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
            com.legend.protocol.spec.ValueSpecification v, java.util.Set<String> out) {
        if (v instanceof com.legend.protocol.spec.AppliedFunction af) {
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
        } else if (v instanceof com.legend.protocol.spec.AppliedProperty ap) {
            collectCalledNames(ap.receiver(), out);
        } else if (v instanceof com.legend.protocol.spec.LambdaFunction lf) {
            lf.body().forEach(x -> collectCalledNames(x, out));
        } else if (v instanceof com.legend.protocol.spec.PureCollection pc) {
            pc.values().forEach(x -> collectCalledNames(x, out));
        }
    }

    /** One zero-arg setup call through the full pipeline; failures feed the
     * failed-seed ledger (and the emptiness guard). */
    private void callSetup(String setupFqn,
            com.legend.compiler.element.ModelContext ctx, Connection conn,
            List<String> failedSeeds) {
        com.legend.protocol.spec.ValueSpecification call =
                com.legend.compiler.NameResolver.resolveQuery(
                        new com.legend.protocol.spec.AppliedFunction(
                                setupFqn, List.of()));
        try {
            // setups run in THE global context (engine semantics: one
            // compiled universe; setups are self-sufficient by
            // composition) — the per-test-module preflight and the
            // separate setup-universe module died with the module layer
            com.legend.Compiler.executeResolved(call, ctx, "rcorpus::Rt",
                    conn);
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
        int advPass = 0;
        int zeroAsserts = 0;
        int rescuedPass = 0;
        // sqldiff-pass column sits LAST: the regression gate's baseline
        // parser reads the pass column POSITIONALLY (cells[3])
        // F2.1 soft-pass columns (adv-pass / 0-asserts / rescued) sit
        // AFTER sqldiff-pass: the baseline parser reads pass at cells[3]
        sb.append("\n| family | tests | pass | fail | error | shape |"
                + " sqldiff-pass | adv-pass | 0-asserts | rescued |"
                + "\n|---|---|---|---|---|---|---|---|---|---|\n");
        for (var e : byFamily.entrySet()) {
            int p = 0;
            int f = 0;
            int er = 0;
            int sh = 0;
            int dp = 0;
            int ap = 0;
            int za = 0;
            int rc = 0;
            for (Outcome o : e.getValue()) {
                switch (o.status()) {
                    case PASS -> {
                        p++;
                        if (o.sqlDiffs() > 0) {
                            dp++;
                        }
                        if (o.advisory() > 0) {
                            ap++;
                        }
                        if (o.detail().startsWith("0 asserts")) {
                            za++;
                        }
                        if (o.rescued() > 0) {
                            rc++;
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
            advPass += ap;
            zeroAsserts += za;
            rescuedPass += rc;
            sb.append("| ").append(e.getKey()).append(" | ").append(e.getValue().size())
                    .append(" | ").append(p).append(" | ").append(f).append(" | ")
                    .append(er).append(" | ").append(sh).append(" | ").append(dp)
                    .append(" | ").append(ap).append(" | ").append(za)
                    .append(" | ").append(rc)
                    .append(" |\n");
        }
        sb.append("| **total** | ").append(pass + fail + error + shape).append(" | **")
                .append(pass).append("** | ").append(fail).append(" | ")
                .append(error).append(" | ").append(shape).append(" | ")
                .append(diffPass).append(" | ").append(advPass).append(" | ")
                .append(zeroAsserts).append(" | ").append(rescuedPass)
                .append(" |\n");
        // F2.1 reconciliation: a PASS is CLEAN only when it carries none
        // of the four softness flags — the burn-down's left term,
        // finally visible (audit §5.1: "the scoreboard cannot see its
        // own softness")
        long soft = byFamily.values().stream().flatMap(List::stream)
                .filter(o -> o.status() == Status.PASS)
                .filter(o -> o.sqlDiffs() > 0 || o.advisory() > 0
                        || o.rescued() > 0
                        || o.detail().startsWith("0 asserts"))
                .count();
        sb.append("\nSOFT-PASS RECONCILIATION (F2.1): ").append(pass)
                .append(" PASS = ").append(pass - soft)
                .append(" clean + ").append(soft)
                .append(" carrying softness (sqldiff ").append(diffPass)
                .append(", advisory ").append(advPass)
                .append(", 0-asserts ").append(zeroAsserts)
                .append(", text-rescued ").append(rescuedPass)
                .append("; flags overlap — the union is ").append(soft)
                .append(").\n");
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

    /** The source line a parse wall points at. A wall names a SYNTHESISED
     *  file ("family/setup-1.pure"), so the line number alone cannot be
     *  looked up by hand — printing the line turns a guessing game into a
     *  reading. */
    private static String wallContext(@com.legend.Nullable String text,
            String err) {
        if (text == null) {
            return "";
        }
        java.util.regex.Matcher m =
                java.util.regex.Pattern.compile("\\[(\\d+):(\\d+)\\]").matcher(err);
        if (!m.find()) {
            return "";
        }
        String[] lines = text.split("\n", -1);
        int ln = Integer.parseInt(m.group(1));
        if (ln < 1 || ln > lines.length) {
            return "";
        }
        return "  |" + lines[ln - 1].strip() + "|";
    }

    /** reason -> count of the text-verdict counters (sqltext homework). */
    private static java.util.Map<String, Long> textVerdictSnapshot() {
        java.util.Map<String, Long> m = new java.util.HashMap<>();
        com.legend.exec.SqlTextEmission.TEXT_VERDICT.forEach(
                (k, v) -> m.put(k, v.sum()));
        return m;
    }
}
