// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.gs.legend.rcorpus;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * The relational-corpus scoreboard run (docs/RELATIONAL_CORPUS.md): every
 * {@code <<test.Test>>} function in the covered families executes as data.
 * RECORDS results — regression pinning arrives once the first burn-down
 * stabilizes the counts.
 */
public class RelationalCorpusRunner {

    /**
     * THE WHOLE core_relational estate: every directory (recursively) under
     * the corpus root that directly contains .pure files is a family. No
     * hand-picked first wave — the denominator is reality; unsupported
     * territories (milestoning, union, ...) show up as walls/errors, never
     * silently out of scope.
     */
    private static List<String> allFamilies() throws Exception {
        List<String> out = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(Corpus.RELATIONAL)) {
            walk.filter(Files::isDirectory)
                    .filter(d -> {
                        try (Stream<Path> files = Files.list(d)) {
                            return files.anyMatch(f -> f.toString().endsWith(".pure"));
                        } catch (Exception e) {
                            return false;
                        }
                    })
                    .sorted()
                    .forEach(d -> out.add(Corpus.RELATIONAL.relativize(d).toString()));
        }
        return out;
    }

    @Test
    void scoreboard() throws Exception {
        Assumptions.assumeTrue(Corpus.available(), "legend-engine checkout not present");

        List<String> shared = List.of(
                Corpus.read("tests/testModel/simpleTestModel.pure"),
                Corpus.read("tests/testModel/inheritanceTestModel.pure"),
                Corpus.read("tests/relationalSetUp.pure"),
                // the corpus's OWN executeInDb wrapper surface — its 2-arg
                // wrapper inlines to the 4-arg K-native leaf (S4)
                Corpus.read("relationalExtension.pure"),
                // engine-core collection helpers the corpus consumes
                // (VERBATIM from legend-engine core/pure/corefunctions/
                // collectionExtension.pure:155-166 — only the pair the
                // tests name; the whole file would double-register
                // natives we already carry)
                """
                function meta::pure::tds::extensions::firstNotNull<T>(set:T[*]):T[0..1]
                {
                  $set->filter(v | $v != TDSNull)->first();
                }
                """);
        Runner runner = new Runner(shared, shared);
        // the platform m2m TEST LIBRARY (Corpus.M2M_TESTS): elements only
        // — qualified refs (testModelConnection*'s M2M mappings) pull the
        // defining files into modules; never setups/expansion
        if (Files.isDirectory(Corpus.M2M_TESTS)) {
            try (Stream<Path> m2m = Files.walk(Corpus.M2M_TESTS)) {
                for (Path f : m2m.filter(x -> x.toString().endsWith(".pure"))
                        .sorted().toList()) {
                    try {
                        runner.registerLibrarySource(Files.readString(f));
                    } catch (Exception ignore) {
                        // unreadable library file: its elements stay dark
                    }
                }
            }
        }
        // the relational compiler's OWN model vocabulary
        // (RelationalDebugContext / IsolationStrategy — the tests/advanced
        // testForced* family constructs them): pureToSQLQuery.pure parses
        // clean since the #50 walls landed; library elements only, pulled
        // by reference exactly like the m2m test library
        Path p2s = Corpus.RELATIONAL.resolve(
                "pureToSQLQuery/pureToSQLQuery.pure");
        if (Files.isRegularFile(p2s)) {
            try {
                runner.registerLibrarySource(Files.readString(p2s));
            } catch (Exception ignore) {
                // unreadable: the family stays walled as before
            }
        }
        runner.classLookup = fqn -> {
            try {
                return classIndex().get(fqn);
            } catch (Exception e) {
                return null;
            }
        };
        // BeforePackage setups live NEXT TO the tests (functions/tests,
        // query, mapping families) — scan every covered file plus the
        // functions/tests dir (meta::relational::tests::query::setUp et al)
        try (Stream<Path> s = Files.walk(Corpus.RELATIONAL.resolve("functions/tests"))) {
            s.filter(f -> f.toString().endsWith(".pure"))
                    .forEach(f -> {
                        try {
                            runner.addBeforePackages(Files.readString(f));
                        } catch (Exception ignore) {
                            // unreadable corpus file: the tests in it bucket anyway
                        }
                    });
        }

        // PRE-SCAN every family file: the setup registry and the setup
        // UNIVERSE must be complete before the FIRST family runs —
        // cross-family setup calls (projection::setUp reaches join's
        // createTablesAndFillDb) resolve regardless of family order
        for (String family : allFamilies()) {
            Path p = Corpus.RELATIONAL.resolve(family);
            try (Stream<Path> s = Files.list(p)) {
                for (Path f : s.filter(x -> x.toString().endsWith(".pure")).toList()) {
                    runner.addBeforePackages(Files.readString(f), family);
                }
            }
        }

        // PHASE 1 — REGISTER EVERY family (unscoped: the ONE global model
        // always compiles the whole corpus, so scoped probes see exactly
        // the model a full sweep sees). PHASE 2 runs tests. Interleaving
        // these froze the global compile at the first family's sources.
        Map<String, Map<Path, String>> familyTests = new LinkedHashMap<>();
        for (String family : allFamilies()) {
            familyTests.put(family, registerFamily(runner, family));
        }
        Map<String, List<Runner.Outcome>> byFamily = new LinkedHashMap<>();
        // -Drcorpus.only=<family-substring>[,<substring>...] scopes the run
        // for fast leg iteration; a scoped run NEVER writes the scoreboard
        // (a partial ledger must not clobber the full one).
        String only = System.getProperty("rcorpus.only", "").trim();
        List<String> onlyFilters = only.isEmpty() ? List.of()
                : List.of(only.split(","));
        for (Map.Entry<String, Map<Path, String>> fam : familyTests.entrySet()) {
            String family = fam.getKey();
            if (!onlyFilters.isEmpty()
                    && onlyFilters.stream().noneMatch(family::contains)) {
                continue;
            }
            List<Runner.Outcome> outcomes = runFamily(runner, family,
                    fam.getValue());
            if (!outcomes.isEmpty()) {
                byFamily.put(family, outcomes);
            }
        }

        String header = "# Relational corpus scoreboard (real legend-engine core_relational)\n\n"
                + "RUN-as-data over the local legend-engine checkout; row equality is the\n"
                + "contract, golden SQL is advisory. SHAPE = test body/assert form the\n"
                + "runner does not yet recognize (accounted, not skipped silently).\n"
                + "Scope: <<test.ToFix>>/<<test.Ignore>> are excluded (engine harness\n"
                + "parity) and so is <<test.ExcludeAlloy>> (legend-lite executes the\n"
                + "in-process Alloy-shaped path).\n";
        List<String> seedFails = runner.seedFailures();
        if (!seedFails.isEmpty()) {
            StringBuilder sf = new StringBuilder("\n## Failed seed statements ("
                    + seedFails.size() + ")\n\n");
            seedFails.forEach(f -> sf.append("- `").append(f).append("`\n"));
            header = header + sf;
        }
        // the COMMITTED baseline reads BEFORE the sweep rewrites it
        Map<String, Integer> baseline =
                readBaseline(Path.of("../docs/RELATIONAL_CORPUS.md"));
        if (Runner.H2_BACKEND) {
            // the PORTABILITY SWEEP is a different execution target: its
            // ledger never clobbers the DuckDB scoreboard and the DuckDB
            // baseline gate does not apply (H2_BACKEND.md §10 — an H2
            // FAIL must not touch the DuckDB row)
            long p = byFamily.values().stream().flatMap(List::stream)
                    .filter(o -> o.status() == Runner.Status.PASS).count();
            long u = byFamily.values().stream().flatMap(List::stream)
                    .filter(o -> o.status() == Runner.Status.UNSUPPORTED)
                    .count();
            long n = byFamily.values().stream().mapToLong(List::size).sum();
            System.out.println("[rcorpus] h2-backend sweep: " + p + "/" + n
                    + " pass, " + u + " unsupported (typed capability"
                    + " walls) — scoreboard NOT written (DuckDB baseline"
                    + " untouched)");
            // the CAPABILITY BUDGET (§9/§10): every declared renderer gap,
            // counted — growth in a bucket is a visible decision, never
            // silent scope creep
            Map<String, Long> budget = byFamily.values().stream()
                    .flatMap(List::stream)
                    .filter(o -> o.status() == Runner.Status.UNSUPPORTED)
                    .collect(java.util.stream.Collectors.groupingBy(
                            o -> o.detail(),
                            java.util.TreeMap::new,
                            java.util.stream.Collectors.counting()));
            budget.entrySet().stream()
                    .sorted(Map.Entry.<String, Long>comparingByValue()
                            .reversed())
                    .forEach(e -> System.out.println(
                            "[rcorpus] h2-capability " + e.getValue() + "x "
                            + e.getKey()));
            byFamily.forEach((f, outs) -> {
                long fp = outs.stream()
                        .filter(o -> o.status() == Runner.Status.PASS).count();
                System.out.println("[rcorpus] h2-backend " + f + ": " + fp
                        + "/" + outs.size() + " pass");
            });
            if (!onlyFilters.isEmpty()) {
                // scoped h2 probe: per-test detail, exactly like the
                // scoped DuckDB run
                byFamily.forEach((f, outs) -> outs.stream()
                        .filter(o -> o.status() != Runner.Status.PASS)
                        .forEach(o -> System.out.println("[rcorpus]   "
                                + o.status() + " " + o.test() + ": "
                                + o.detail())));
            }
            System.out.println("[rcorpus] failed seeds: " + seedFails.size());
            return;
        }
        if (onlyFilters.isEmpty()) {
            Runner.writeScoreboard(Path.of("../docs/RELATIONAL_CORPUS.md"), byFamily,
                    runner.walls(), header);
        } else {
            System.out.println("[rcorpus] SCOPED run (" + only
                    + ") — scoreboard NOT written");
            byFamily.forEach((f, outs) -> outs.stream()
                    .filter(o -> o.status() != Runner.Status.PASS)
                    .forEach(o -> System.out.println("[rcorpus]   " + o.status()
                            + " " + o.test() + ": " + o.detail())));
        }
        System.out.println("[rcorpus] failed seeds: " + seedFails.size());
        byFamily.forEach((f, outs) -> {
            long p = outs.stream().filter(o -> o.status() == Runner.Status.PASS).count();
            System.out.println("[rcorpus] " + f + ": " + p + "/" + outs.size() + " pass");
        });
        // MILESTONE 1 (H2_BACKEND.md §12 step 5): real H2 execution of
        // OUR byte-matched SQL, held to our DuckDB rows — additive
        // instrumentation; a diverged count > 0 surfaces as test FAILs.
        System.out.println("[rcorpus] h2-exec (our SQL on H2): "
                + com.legend.harness.H2Verify.M1_VERIFIED.sum() + " verified, "
                + com.legend.harness.H2Verify.M1_DIVERGED.sum() + " diverged, "
                + com.legend.harness.H2Verify.M1_UNVERIFIABLE.sum()
                + " unverifiable");
        // step 13 registry feed: the per-reason unverifiable census
        com.legend.harness.H2Verify.UNVERIFIABLE_CENSUS.entrySet().stream()
                .sorted((a, b) -> Long.compare(b.getValue().sum(),
                        a.getValue().sum()))
                .forEach(e -> System.out.println(
                        "[rcorpus] h2-unverifiable-census " + e.getValue().sum()
                        + "x " + e.getKey()));
        // DECLARED-GAP REGISTRY (step 13, §9 semantics): each registered
        // H2-oracle gap has an EXPECTED count measured at registration
        // (c47 census) — GROWTH is a FAIL (silent scope creep), SHRINK
        // prints a retire hint (the row is stale, tighten it).
        if (onlyFilters.isEmpty()) {
            java.util.Map<String, Integer> registry = java.util.Map.of(
                    // forked-H2 leniency: the engine's own 2.1.214 fork
                    // relaxes duplicate result columns; stock H2 rejects
                    "Duplicate column name", 10,
                    // engine plan-level temp-table for IN lists — a
                    // machinery gap, not a rendering one
                    "tempTableForIn", 6,
                    // engine Java-extension UDFs, a route we ban (no
                    // CREATE ALIAS) — today only base64 surfaces
                    "legend_h2_extension_", 1);
            registry.forEach((needle, expected) -> {
                long got = com.legend.harness.H2Verify.UNVERIFIABLE_CENSUS
                        .entrySet().stream()
                        .filter(e -> e.getKey().contains(needle))
                        .mapToLong(e -> e.getValue().sum()).sum();
                org.junit.jupiter.api.Assertions.assertTrue(got <= expected,
                        "registered H2-oracle gap '" + needle + "' grew: "
                        + got + " > " + expected);
                if (got < expected) {
                    System.out.println("[rcorpus] registered gap '" + needle
                            + "' shrank (" + got + " < " + expected
                            + ") — retire/tighten the registry row");
                }
            });
        }
        // M1 GATE PINNING (H2_BACKEND.md §12 step 13): on a FULL sweep,
        // any divergence fails the build (they already FAIL per-test —
        // this pins the aggregate against silent scoring drift), and the
        // verified count must hold its floor (289 at c43, 296 after the
        // c46 enum-decode rung; ratchet on deliberate gains).
        if (onlyFilters.isEmpty()) {
            org.junit.jupiter.api.Assertions.assertEquals(0,
                    com.legend.harness.H2Verify.M1_DIVERGED.sum(),
                    "M1 h2-exec divergences on a full sweep");
            org.junit.jupiter.api.Assertions.assertTrue(
                    com.legend.harness.H2Verify.M1_VERIFIED.sum() >= 296,
                    "M1 h2-exec verified fell below the 296 floor: "
                    + com.legend.harness.H2Verify.M1_VERIFIED.sum());
        }
        System.out.println("[rcorpus] walls (mappings + dropped base elements): "
                + runner.walls().size());
        if (System.getProperty("rcorpus.walls") != null) {
            runner.walls().forEach(w ->
                    System.out.println("[rcorpus] WALL " + w));
        }
        if (onlyFilters.isEmpty()) {
            System.out.println("[rcorpus] scoreboard written to docs/RELATIONAL_CORPUS.md");
        }
        // MECHANICAL REGRESSION GATE (audit: this runner carried NO
        // asserts — BUILD SUCCESS regardless of outcome). Every family
        // run IN FULL must meet the committed per-family pass baseline;
        // improvements advance the baseline through the rewritten
        // scoreboard, regressions FAIL the build. Viable only since the
        // flapper elimination (deterministic runner — consecutive sweeps
        // identical). -Drcorpus.test runs skip: partial family counts.
        if (System.getProperty("rcorpus.test", "").trim().isEmpty()) {
            List<String> regressions = new ArrayList<>();
            byFamily.forEach((f, outs) -> {
                long p = outs.stream()
                        .filter(o -> o.status() == Runner.Status.PASS).count();
                Integer b = baseline.get(f);
                if (b != null && p < b) {
                    regressions.add(f + " " + p + " < baseline " + b);
                }
            });
            org.junit.jupiter.api.Assertions.assertTrue(regressions.isEmpty(),
                    "CORPUS REGRESSION vs committed docs/RELATIONAL_CORPUS.md: "
                    + regressions
                    + " — fix or revert; do not commit the rewritten scoreboard");
        }
    }

    /** The committed scoreboard's per-family PASS counts, parsed BY
     * HEADER NAME — a positional cells[3] read meant any column inserted
     * before it silently degraded the regression gate to green
     * (H2_BACKEND.md §10). Empty (gate skipped, loud) when the file is
     * absent, unreadable, or carries no recognizable 'pass' column. */
    private static Map<String, Integer> readBaseline(Path p) {
        Map<String, Integer> m = new LinkedHashMap<>();
        int passCol = -1;
        try {
            for (String line : java.nio.file.Files.readAllLines(p)) {
                if (!line.startsWith("| ")) {
                    continue;
                }
                String[] cells = line.split("\\|");
                if (line.startsWith("| family")) {
                    for (int i = 0; i < cells.length; i++) {
                        if (cells[i].trim().equals("pass")) {
                            passCol = i;
                        }
                    }
                    continue;
                }
                if (passCol < 0 || cells.length <= passCol
                        || line.contains("**total**")) {
                    continue;
                }
                try {
                    m.put(cells[1].trim(),
                            Integer.parseInt(cells[passCol].trim()));
                } catch (NumberFormatException ignore) {
                    // separator / non-table rows
                }
            }
            if (passCol < 0) {
                System.out.println("[rcorpus] baseline has no 'pass' header"
                        + " — regression gate SKIPPED");
            }
        } catch (Exception e) {
            System.out.println("[rcorpus] baseline unreadable (" + e
                    + ") — regression gate SKIPPED");
        }
        return m;
    }

    /** ONE family through the pipeline — shared by the scoreboard and the
     * family-scoped fast sweep (FamilySweep probe): the family/test-file
     * split, parent setUp/store-only inheritance, module assembly, and the
     * per-test run. */
    public static List<Runner.Outcome> runFamily(Runner runner, String family)
            throws Exception {
        return runFamily(runner, family, registerFamily(runner, family));
    }

    /** RUN phase (two-phase compile-once protocol): every family is
     * already registered; re-point the runner and execute. */
    public static List<Runner.Outcome> runFamily(Runner runner, String family,
            Map<Path, String> testSources) throws Exception {
        runner.selectFamily(family);
        List<Runner.Outcome> outcomes = new ArrayList<>();
        String onlyTest = System.getProperty("rcorpus.test", "").trim();
        for (Map.Entry<Path, String> e : testSources.entrySet()) {
            runner.selectFile(e.getKey().toString());
            // Phase C: discovery through the REAL parser — stereotyped
            // functions off the parsed unit, body as AST
            // -Drcorpus.test=<name-substring> narrows a scoped run to
            // matching TEST functions (fast single-test iteration; the
            // family model still assembles in full)
            for (Runner.ParsedTest t : Runner.discoverTests(e.getValue())) {
                if (!onlyTest.isEmpty() && !t.fqn().contains(onlyTest)) {
                    continue;
                }
                outcomes.add(runner.run(t));
            }
        }
        return outcomes;
    }

    /** REGISTRATION phase: assemble the family's source set (setups,
     * parent inheritance, cross-family closure) and register it with the
     * runner. Must run for EVERY family before the first test executes —
     * the global model compiles ONCE over the completed registry. */
    public static Map<Path, String> registerFamily(Runner runner,
            String family) throws Exception {
        Path p = Corpus.RELATIONAL.resolve(family);
        List<Path> files = new ArrayList<>();
        try (Stream<Path> s = Files.list(p)) {
            s.filter(f -> f.toString().endsWith(".pure")).sorted().forEach(files::add);
        }
        for (Path f : files) {
            runner.addBeforePackages(Files.readString(f));
        }
        // SETUP files (no test functions) extend the model for every
        // test file of the family. Test files stay per-file: one
        // unparseable sibling must not wall the whole family, and some
        // siblings carry intentionally divergent models.
        List<String> familySources = new ArrayList<>();
        Map<Path, String> testSources = new LinkedHashMap<>();
        for (Path f : files) {
            String src = Files.readString(f);
            if (!Runner.hasTestFunctions(src)) {
                familySources.add(src);
            } else {
                testSources.put(f, src);
            }
        }
        // ANCESTOR setup inheritance was tried and REVERTED: sibling-dir
        // models conflict (tests/ direct files carry alternative Person
        // models) — net 48 vs 64 passes. Families see only their own
        // directory's files — EXCEPT a parent-directory setUp.pure
        // (dedicated setup, no tests): extends/union references the
        // extends family's model/store, the one such file in the corpus.
        Path parentSetup = p.getParent().resolve("setUp.pure");
        if (!p.getParent().equals(Corpus.RELATIONAL) && Files.exists(parentSetup)) {
            String src = Files.readString(parentSetup);
            if (!Runner.hasTestFunctions(src)) {
                familySources.add(0, src);
            }
        }
        // STORE-ONLY parent files (calendarAggregation/calendarStore
        // .pure): a parent-directory source defining ONLY Database
        // elements is the family's store — inheriting it cannot
        // conflict (the reverted ancestor experiment tripped on
        // parent CLASS models, never stores)
        if (!p.getParent().equals(Corpus.RELATIONAL)) {
            try (var sib = Files.list(p.getParent())) {
                for (Path f2 : sib.filter(x ->
                    x.toString().endsWith(".pure")
                    && Files.isRegularFile(x)).sorted().toList()) {
                if (f2.equals(parentSetup)) {
                    continue;
                }
                String src2 = Files.readString(f2);
                boolean storeOnly = !Runner.hasTestFunctions(src2)
                        && src2.lines().anyMatch(l ->
                            l.startsWith("Database "))
                        && src2.lines().noneMatch(l ->
                            l.startsWith("Class ")
                            || l.startsWith("function ")
                            || l.startsWith("Mapping "));
                // FUNCTION-ONLY parent files (tds/tdsExtension.pure,
                // tds/tds.pure): a parent source defining only pure
                // FUNCTIONS is as conflict-free as a store — no model
                // elements to collide (the reverted ancestor experiment
                // tripped on parent CLASS models, never function libs)
                boolean funcOnly = !Runner.hasTestFunctions(src2)
                        && src2.lines().anyMatch(l ->
                            l.startsWith("function "))
                        && src2.lines().noneMatch(l ->
                            l.startsWith("Class ")
                            || l.startsWith("Database ")
                            || l.startsWith("Enum ")
                            || l.startsWith("Association ")
                            || l.startsWith("Mapping "));
                if (storeOnly || funcOnly) {
                    familySources.add(0, src2);
                }
                }
            }
        }
        List<String> modelOnly = new ArrayList<>(testSources.values());
        // DEEP subfamilies reference their parent family's elements
        // (union/relation ~func bodies read union's myDB) — the engine
        // compiles the module together. Depth-guarded: parents at the
        // tests/ root carry alternative models (the reverted ancestor
        // experiment), so only parents >= 3 segments deep inherit.
        String parentKey = null;
        Path parentDir = p.getParent();
        if (parentDir != null && !parentDir.equals(Corpus.RELATIONAL)) {
            String cand = Corpus.RELATIONAL.relativize(parentDir).toString();
            if (cand.split("/").length >= 3) {
                parentKey = cand;
            }
        }
        // CROSS-FAMILY DEPENDENCY CLOSURE: a Database include naming a db
        // DEFINED IN ANOTHER FAMILY's file pulls that file in MODEL-ONLY
        // (the engine compiles the whole PURE graph together; the pulled
        // elements compile, its tests do NOT run here). First-wins module
        // semantics keep this family's own elements on duplicate FQNs.
        {
            Set<String> defined = new HashSet<>();
            List<String> all = new ArrayList<>(familySources);
            all.addAll(testSources.values());
            for (String s2 : all) {
                collectDbNames(s2, defined);
                collectClassNames(s2, defined);
            }
            Deque<String> pending = new ArrayDeque<>(all);
            Set<Path> pulledFiles = new HashSet<>(files);
            while (!pending.isEmpty()) {
                String s2 = pending.poll();
                // the source's import packages — mapping files declare
                // class-mapping heads UNQUALIFIED (shared.pure: `_Person :
                // Relational` under import ...shared::dest::*)
                List<String> imps = s2.lines().map(String::strip)
                        .filter(l -> l.startsWith("import ")
                                && l.endsWith("::*;"))
                        .map(l -> l.substring(7, l.length() - 4))
                        .toList();
                for (String line : s2.lines().map(String::strip).toList()) {
                    List<String> wanted = new ArrayList<>();
                    java.util.regex.Matcher cmHead = java.util.regex.Pattern
                            .compile("^\\*?([\\w:]+)(\\[[\\w,]+\\])? *: *(Relational|Pure)\\b")
                            .matcher(line);
                    if (cmHead.find()) {
                        String cn = cmHead.group(1);
                        if (cn.contains("::")) {
                            wanted.add(cn);
                        } else {
                            for (String imp : imps) {
                                wanted.add(imp + "::" + cn);
                            }
                        }
                    }
                    if (line.startsWith("include ")) {
                        wanted.add(line.substring("include ".length())
                                .strip());
                    } else if (line.startsWith("Class ")
                            && line.contains(" extends ")) {
                        // cross-family EXTENDS closure — the validation
                        // corpus subclasses tests/milestoning classes;
                        // the superclass's file must compile alongside
                        for (String tok : line.substring(
                                line.indexOf(" extends ") + 9)
                                .split("[,\\[{]")) {
                            if (tok.strip().contains("::")) {
                                wanted.add(tok.strip());
                            }
                        }
                    }
                    for (String fqn : wanted) {
                        if (defined.contains(fqn)) {
                            continue;
                        }
                        Path dep = dbIndex().get(fqn);
                        if (dep == null) {
                            dep = classIndex().get(fqn);
                        }
                        if (dep == null || !pulledFiles.add(dep)) {
                            continue;   // unknown stays a loud wall
                        }
                        if (System.getenv("LL_TMP_DEBUG") != null) {
                            System.err.println("[pull] " + fqn + " <- " + dep);
                        }
                        String depSrc = Files.readString(dep);
                        modelOnly.add(depSrc);
                        collectDbNames(depSrc, defined);
                        collectClassNames(depSrc, defined);
                        pending.add(depSrc);
                    }
                }
            }
        }
        runner.useFamily(family, familySources, modelOnly, parentKey);
        for (Map.Entry<Path, String> e : testSources.entrySet()) {
            runner.useFile(e.getKey().toString(), e.getValue());
        }
        return testSources;
    }

    /** Database FQNs defined in {@code src} (line-level indexing only —
     * the model itself still compiles through the platform). */
    private static void collectDbNames(String src, Set<String> out) {
        src.lines().map(String::strip)
                .filter(l -> l.startsWith("Database "))
                .forEach(l -> out.add(dbNameOf(l)));
    }

    private static String dbNameOf(String databaseLine) {
        return databaseLine.substring("Database ".length())
                .replace("(", " ").strip().split("\\s+")[0];
    }

    /** Class FQNs defined in {@code src} (line-level; stereotype block
     * tolerated between the keyword and the FQN). */
    private static void collectClassNames(String src, Set<String> out) {
        src.lines().map(String::strip)
                .filter(l -> l.startsWith("Class "))
                .forEach(l -> {
                    String n = classNameOf(l);
                    if (n != null) {
                        out.add(n);
                    }
                });
    }

    private static String classNameOf(String classLine) {
        String t = classLine.substring("Class ".length()).strip();
        if (t.startsWith("<<")) {
            int e = t.indexOf(">>");
            if (e < 0) {
                return null;
            }
            t = t.substring(e + 2).strip();
        }
        if (t.startsWith("{")) {
            // tagged-value block {doc.doc='...'}
            int e = t.indexOf('}');
            if (e < 0) {
                return null;
            }
            t = t.substring(e + 1).strip();
        }
        String n = t.split("[\\s\\[{(]")[0].strip();
        return n.contains("::") ? n : null;
    }

    /** Corpus-wide CLASS index: FQN -> defining file. */
    private static Map<String, Path> classIndexCache;

    private static Map<String, Path> classIndex() throws Exception {
        if (classIndexCache == null) {
            Map<String, Path> ix = new LinkedHashMap<>();
            for (Path root : java.util.List.of(Corpus.RELATIONAL,
                    Corpus.M2M_TESTS)) {
                if (!Files.isDirectory(root)) {
                    continue;
                }
                try (Stream<Path> s = Files.walk(root)) {
                    for (Path f : s.filter(x -> x.toString().endsWith(".pure"))
                            .sorted().toList()) {
                        for (String l : Files.readAllLines(f)) {
                            String t = l.strip();
                            if (t.startsWith("Class ")) {
                                String n = classNameOf(t);
                                if (n != null) {
                                    ix.putIfAbsent(n, f);
                                }
                            }
                        }
                    }
                }
            }
            classIndexCache = ix;
        }
        return classIndexCache;
    }

    /** Corpus-wide Database index: FQN -> defining file (first in sorted
     * walk order — deterministic across sibling duplicates). */
    private static Map<String, Path> dbIndexCache;

    private static Map<String, Path> dbIndex() throws Exception {
        if (dbIndexCache == null) {
            Map<String, Path> ix = new LinkedHashMap<>();
            try (Stream<Path> s = Files.walk(Corpus.RELATIONAL)) {
                for (Path f : s.filter(x -> x.toString().endsWith(".pure"))
                        .sorted().toList()) {
                    for (String l : Files.readAllLines(f)) {
                        String t = l.strip();
                        if (t.startsWith("Database ")) {
                            ix.putIfAbsent(dbNameOf(t), f);
                        }
                    }
                }
            }
            dbIndexCache = ix;
        }
        return dbIndexCache;
    }
}
