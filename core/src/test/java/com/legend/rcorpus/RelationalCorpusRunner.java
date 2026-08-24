// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend.rcorpus;

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
                """,
                // engine-core date-format constants (VERBATIM from
                // core/pure/corefunctions/dateExtension.pure:384-392 —
                // the corpus's toCSV date rendering)
                """
                function meta::pure::functions::date::SimpleDateTimeFormat():String[1]
                {
                   '%t{yyyy-MM-dd HH:mm:ss}';
                }

                function meta::pure::functions::date::ISO8601DateFormat():String[1]
                {
                   '%t{yyyy-MM-dd}';
                }
                """,
                // engine-core geo distances (VERBATIM from
                // core/pure/corefunctions/mathExtension.pure:15-48 —
                // the olap rank fail-stubs are not carried)
                """
                function meta::pure::functions::math::earthRadius():Float[1]
                {
                   6371.0;
                }

                function meta::pure::functions::math::distanceHaversineDegrees(lat1Degrees:Number[1],lon1Degrees:Number[1],lat2Degrees:Number[1],lon2Degrees:Number[1]):Number[1]
                {
                   distanceHaversineRadians(toRadians($lat1Degrees),toRadians($lon1Degrees),toRadians($lat2Degrees),toRadians($lon2Degrees));
                }

                function meta::pure::functions::math::distanceHaversineRadians(lat1Radians:Number[1],lon1Radians:Number[1],lat2Radians:Number[1],lon2Radians:Number[1]):Number[1]
                {
                   earthRadius() * angularDistanceInRadians(squareOfHalfTheChord($lat1Radians, $lon1Radians, $lat2Radians, $lon2Radians));
                }

                function <<access.private>> meta::pure::functions::math::squareOfHalfTheChord(lat1Radians:Number[1],lon1Radians:Number[1],lat2Radians:Number[1],lon2Radians:Number[1]):Number[1]
                {
                   pow((sin(($lat2Radians - $lat1Radians) / 2)), 2) + (cos($lat1Radians) * cos($lat2Radians) * pow(sin(($lon2Radians - $lon1Radians) / 2), 2));
                }

                function <<access.private>> meta::pure::functions::math::angularDistanceInRadians(a:Number[1]):Float[1]
                {
                   2.0 * atan2(sqrt($a), sqrt(1 - $a));
                }

                function meta::pure::functions::math::distanceSphericalLawOfCosinesDegrees(lat1Degrees:Number[1],lon1Degrees:Number[1],lat2Degrees:Number[1],lon2Degrees:Number[1]):Number[1]
                {
                   distanceSphericalLawOfCosinesRadians(toRadians($lat1Degrees), toRadians($lon1Degrees), toRadians($lat2Degrees), toRadians($lon2Degrees));
                }

                function meta::pure::functions::math::distanceSphericalLawOfCosinesRadians(lat1Radians:Number[1],lon1Radians:Number[1],lat2Radians:Number[1],lon2Radians:Number[1]):Number[1]
                {
                   earthRadius() * acos((sin($lat1Radians) * sin($lat2Radians)) + (cos($lat1Radians) * cos($lat2Radians) * cos($lon2Radians - $lon1Radians)));
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
        // the graphFetch DOMAIN-MANAGEMENT library (engine-core
        // core/pure/graphFetch/domain — the Domain/DataSpace test model
        // the relational graphFetch/domain family maps onto); library
        // elements only, pulled by reference like the m2m test library
        Path gfDomain = Corpus.ENGINE_ROOT.resolve(
                "legend-engine-core/legend-engine-core-pure/"
                + "legend-engine-pure-code-compiled-core/"
                + "src/main/resources/core/pure/graphFetch/domain");
        if (Files.isDirectory(gfDomain)) {
            try (Stream<Path> gf = Files.walk(gfDomain)) {
                for (Path f : gf.filter(x -> x.toString().endsWith(".pure"))
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
        // .sorted(): filesystem order is not a contract (STATE_AUDIT S4.4).
        // addBeforePackages feeds putIfAbsent chains that decide WHICH body a
        // helper call expands to, so an unsorted walk makes the scoreboard's
        // wall TEXT flap between runs — demonstrated on testViewToTDS and
        // testResultToJsonStream, which reported different first-failures on
        // consecutive sweeps at identical HEAD and corpus root.
        try (Stream<Path> s = Files.walk(Corpus.RELATIONAL.resolve("functions/tests"))) {
            s.filter(f -> f.toString().endsWith(".pure"))
                    .sorted()
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
                // .sorted(): see the note on the functions/tests walk above
                for (Path f : s.filter(x -> x.toString().endsWith(".pure"))
                        .sorted().toList()) {
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

        // THE DENOMINATOR, stated by the discovery path itself — no external
        // grep, no arithmetic, nothing to argue with. Every core_relational
        // test is either runnable or excluded-with-a-named-reason.
        Map<String, Long> byReason = Runner.CENSUS_EXCLUDED.values().stream()
                .collect(java.util.stream.Collectors.groupingBy(
                        r -> r == null ? "unknown" : r,
                        java.util.TreeMap::new,
                        java.util.stream.Collectors.counting()));
        int runnable = Runner.CENSUS_RUNNABLE.size();
        int excluded = Runner.CENSUS_EXCLUDED.size();
        String census = "\n## Census (core_relational)\n\n"
                + "| | count |\n|---|---:|\n"
                + "| **total `<<test.Test>>` functions** | **" + (runnable + excluded) + "** |\n"
                + "| runnable (this scoreboard) | " + runnable + " |\n"
                + "| excluded by stereotype | " + excluded + " |\n"
                + byReason.entrySet().stream()
                        .map(e -> "| …`<<test." + e.getKey() + ">>` | " + e.getValue() + " |\n")
                        .collect(java.util.stream.Collectors.joining())
                + "\nCounted by the discovery path (`Runner.discoverTests`), keyed by test FQN so a\n"
                + "shared source registered by several families cannot double-count. Run with\n"
                + "`-Drcorpus.includeExcluded` to run the excluded ones too.\n";
        System.out.println("[rcorpus] census: " + (runnable + excluded)
                + " total, " + runnable + " runnable, " + excluded
                + " excluded " + byReason
                + (Runner.INCLUDE_EXCLUDED ? "  (INCLUDED THIS RUN)" : ""));

        String header = "# Relational corpus scoreboard (real legend-engine core_relational)\n\n"
                + "RUN-as-data over the local legend-engine checkout; row equality is the\n"
                + "contract, golden SQL is advisory. SHAPE = test body/assert form the\n"
                + "runner does not yet recognize (accounted, not skipped silently).\n"
                + "Scope: <<test.ToFix>>/<<test.Ignore>> are excluded (engine harness\n"
                + "parity) and so is <<test.ExcludeAlloy>> (legend-lite executes the\n"
                + "in-process Alloy-shaped path)"
                + (Runner.INCLUDE_EXCLUDED
                        ? " — BUT THIS RUN INCLUDED THEM\n(-Drcorpus.includeExcluded).\n"
                        : ".\n")
                + "\nADJUDICATION LEDGER: every non-passing row carries a per-test\n"
                + "evidence-backed verdict (REAL_DEFECT / MISSING_FEATURE /\n"
                + "TESTS_ENGINE_INTERNALS / GOLDEN_TEXT_ONLY /\n"
                + "EXECUTION_TARGET_ARTIFACT / HARNESS_GAP / NEEDS_PROBE), effort,\n"
                + "confidence and falsifier in\n"
                + "docs/e2e-diagnosis-2026-08-15/diagnoses.csv (keyed by test name;\n"
                + "reconciliation log in docs/E2E_DEEP_DIAGNOSIS_2026_08_15.md —\n"
                + "retirements are shrink-only, verdict changes need the row's own\n"
                + "falsifier to fire).\n"
                + census;
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
        // GATE BEFORE WRITE. The scoreboard is a COMMITTED artifact, and this
        // sweep used to rewrite it in place and only then assert — so a
        // regression (or, worse, a run against the wrong corpus root) left a
        // corrupted file in the working tree and relied on the operator
        // reading the failure text before committing. The gate's own message
        // said "do not commit the rewritten scoreboard", which is advice, not
        // a mechanism. Compute the verdict here; write only when clean.
        List<String> regressions = new ArrayList<>();
        if (System.getProperty("rcorpus.test", "").trim().isEmpty()
                && !Runner.INCLUDE_EXCLUDED) {
            byFamily.forEach((f, outs) -> {
                long p = outs.stream()
                        .filter(o -> o.status() == Runner.Status.PASS).count();
                Integer b = baseline.get(f);
                if (b != null && p < b) {
                    regressions.add(f + " " + p + " < baseline " + b);
                }
            });
        }
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
            seedFails.forEach(f -> System.out.println("[rcorpus]   seed-fail: " + f));
            System.out.println("[rcorpus] seed replay: "
                    + Runner.SEED_CALLS.get() + " calls, "
                    + (Runner.SEED_NANOS.get() / 1_000_000) + " ms; raw jdbc "
                    + com.legend.exec.Executor.RAW_CALLS.get() + " stmts, "
                    + (com.legend.exec.Executor.RAW_NANOS.get() / 1_000_000)
                    + " ms");
            // THE H2 LANE ASSERTS (DEEP_AUDIT §11c: gate 5 swept 2,575
            // tests and asserted NOTHING — "1362 could become 1 without
            // moving the verdict"). Floors measured 2026-08-21; pass
            // RATCHETS UP (raise the floor when earned), seeds and the
            // capability budget only shrink.
            if (onlyFilters.isEmpty()) {
                org.junit.jupiter.api.Assertions.assertAll(
                        () -> org.junit.jupiter.api.Assertions.assertTrue(
                                p >= 1361, "h2 sweep pass fell: " + p
                                        + " < floor 1361"),
                        () -> org.junit.jupiter.api.Assertions.assertTrue(
                                seedFails.size() <= 6,
                                "h2 failed seeds grew: " + seedFails.size()
                                        + " > 6"),
                        () -> org.junit.jupiter.api.Assertions.assertTrue(
                                u <= 945, "h2 capability walls grew: " + u
                                        + " > 945 — a renderer gap widened"
                                        + " silently"));
            }
            return;
        }
        if (onlyFilters.isEmpty() && Runner.INCLUDE_EXCLUDED) {
            // the 100% ledger is a DIFFERENT denominator (it runs the
            // upstream-skipped tests), so it gets its own file and never
            // touches the DuckDB baseline — same rule as the H2 sweep.
            // Promoting it would make every later normal run look like a
            // mass regression.
            Runner.writeScoreboard(Path.of("../docs/RELATIONAL_CORPUS_ALL.md"), byFamily,
                    runner.walls(), header);
            System.out.println("[rcorpus] 100% ledger written to"
                    + " docs/RELATIONAL_CORPUS_ALL.md (baseline untouched)");
        } else if (!System.getProperty("rcorpus.test", "").trim().isEmpty()) {
            // F4.3 hole-plug: a -Drcorpus.test scoped run bypassed BOTH the
            // only-filter check and the regression gate (which skips when
            // test-scoped), so it wrote a TRUNCATED scoreboard — caught when
            // a stash carried one. Test-scoped runs NEVER write.
            System.out.println("[rcorpus] TEST-SCOPED run (rcorpus.test) —"
                    + " scoreboard NOT written");
            // scoped iteration needs the verdict detail on stdout (the
            // full-run path prints it via the regression gate)
            byFamily.forEach((f, outs) -> outs.stream()
                    .filter(o -> o.status() != Runner.Status.PASS)
                    .forEach(o -> System.out.println("[rcorpus]   " + o.status()
                            + " " + o.test() + ": " + o.detail())));
        } else if (onlyFilters.isEmpty() && regressions.isEmpty()) {
            Runner.writeScoreboard(Path.of("../docs/RELATIONAL_CORPUS.md"), byFamily,
                    runner.walls(), header);
        } else if (onlyFilters.isEmpty()) {
            System.out.println("[rcorpus] REGRESSION — scoreboard NOT written;"
                    + " the committed docs/RELATIONAL_CORPUS.md is intact");
            byFamily.forEach((f, outs) -> outs.stream()
                    .filter(o -> o.status() != Runner.Status.PASS)
                    .forEach(o -> System.out.println("[rcorpus]   " + o.status()
                            + " " + o.test() + ": " + o.detail())));
        } else {
            System.out.println("[rcorpus] SCOPED run (" + only
                    + ") — scoreboard NOT written");
            byFamily.forEach((f, outs) -> outs.stream()
                    .filter(o -> o.status() != Runner.Status.PASS)
                    .forEach(o -> System.out.println("[rcorpus]   " + o.status()
                            + " " + o.test() + ": " + o.detail())));
        }
        System.out.println("[rcorpus] failed seeds: " + seedFails.size());
        seedFails.forEach(f -> System.out.println("[rcorpus]   seed-fail: " + f));
        byFamily.forEach((f, outs) -> {
            long p = outs.stream().filter(o -> o.status() == Runner.Status.PASS).count();
            System.out.println("[rcorpus] " + f + ": " + p + "/" + outs.size() + " pass");
        });
        // MILESTONE 1 (H2_BACKEND.md §12 step 5): real H2 execution of
        // OUR byte-matched SQL, held to our DuckDB rows — additive
        // instrumentation; a diverged count > 0 surfaces as test FAILs.
        System.out.println("[rcorpus] h2-exec (our SQL on H2): "
                + com.legend.harness.H2Verify.M1_VERIFIED.sum()
                + " text-matched + "
                + com.legend.harness.H2Verify.M1_RESCUED.sum()
                + " text-divergent-rescued row-verified, "
                + com.legend.harness.H2Verify.M1_DIVERGED.sum() + " diverged, "
                + com.legend.harness.H2Verify.M1_UNVERIFIABLE.sum()
                + " unverifiable");
        // Per-test M1 verdict roster — UNCONDITIONAL dump (the
        // query-histogram idiom): target/h2-verdicts.txt, one sorted
        // "kind test xN" line each, so a floor move is attributable by
        // diffing two sweeps' files.
        try {
            java.nio.file.Files.writeString(
                    java.nio.file.Path.of("target", "h2-verdicts.txt"),
                    com.legend.harness.H2Verify.VERDICT_ROSTER.entrySet()
                            .stream().sorted(java.util.Map.Entry.comparingByKey())
                            .map(e -> e.getKey() + " x" + e.getValue().sum())
                            .collect(java.util.stream.Collectors.joining("\n"))
                    + "\n");
        } catch (java.io.IOException ignore) {
            // best-effort diagnostic (histogram precedent)
        }
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
                    // relaxes duplicate result columns; stock H2 rejects.
                    // 10 -> 11 (2026-08-08, ###Mapping protocol switch): the
                    // Relation class-mapping arm of the protocol parser now
                    // reads `~func <fqn>` with no signature spelling, so one
                    // more tests/mapping/modelJoin test compiles and reaches
                    // H2 replay (that family went 42 -> 43 against the REAL
                    // checkout; re-verified by reverting to 10 and watching
                    // it fail, so this is not a stale-corpus artifact).
                    // Every census row
                    // here is a `golden execution` failure — it is ENGINE's
                    // own golden that selects a name twice, not our SQL — so
                    // this is one more instance of the registered gap, not a
                    // new one.
                    "Duplicate column name", 11,
                    // engine plan-level temp-table for IN lists — a
                    // machinery gap, not a rendering one
                    "tempTableForIn", 6,
                    // F2.3 seed (2026-08-16): the golden-SQL side
                    // channel's catch-and-null, now counted — 56 declines
                    // (the printed census truncates its bucket list; this
                    // ceiling is the assert's own full sum). Dominant
                    // buckets: array/list encodings in the golden-text
                    // dialect, toSQLString shapes, banker's ROUND,
                    // object-space TypedFilter. Shrink-only; each bucket
                    // is a REAL renderer/recognizer gap — adjudicate
                    // before raising.
                    // 56→57 (E2, 2026-08-17): testConcatenateWithFilter's
                    // new PASS rides the LEFT-LATERAL row explosion,
                    // which H2 cannot replay (no LATERAL) — one more
                    // sql-text-side decline, tied to a GAINED test
                    "sql-text side", 57);
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
        // c46 enum-decode rung, 309 after slice 10's engine-text NULLS
        // suppression — 13 rows had diverged from golden text only by a
        // nulls clause; ratchet on deliberate gains). 309→320
        // (2026-08-20 stamp C2-i): provably-single cell reads lower as
        // PLAIN scalar subqueries — 11 more texts byte-match.
        if (onlyFilters.isEmpty()) {
            org.junit.jupiter.api.Assertions.assertEquals(0,
                    com.legend.harness.H2Verify.M1_DIVERGED.sum(),
                    "M1 h2-exec divergences on a full sweep");
            org.junit.jupiter.api.Assertions.assertTrue(
                    com.legend.harness.H2Verify.M1_VERIFIED.sum() >= 320,
                    "M1 h2-exec verified fell below the 320 floor: "
                    + com.legend.harness.H2Verify.M1_VERIFIED.sum());
        }
        System.out.println("[rcorpus] seed replay: "
                + Runner.SEED_CALLS.get() + " calls, "
                + (Runner.SEED_NANOS.get() / 1_000_000) + " ms");
        System.out.println("[rcorpus] seed split: ddl "
                + (Runner.DDL_NANOS.get() / 1_000_000) + " ms; raw jdbc "
                + com.legend.exec.Executor.RAW_CALLS.get() + " stmts, "
                + (com.legend.exec.Executor.RAW_NANOS.get() / 1_000_000)
                + " ms");
        System.out.println("[rcorpus] golden channel: "
                + (com.legend.harness.H2Verify.GOLDEN_NANOS.get() / 1_000_000)
                + " ms; xlate: "
                + (com.legend.sql.dialect.RawSqlBoundary.XLATE_NANOS.get() / 1_000_000)
                + " ms");
        System.out.println("[rcorpus] h2-mirror verify: "
                + (com.legend.harness.H2Verify.MIRROR_NANOS.get() / 1_000_000)
                + " ms");
        // TEMPORARY (2026-08-15): full wall reconciliation ledger
        com.legend.exec.TimingLedger.dump();
        // R1 canonical-byte-channel divergence table (CANONICAL_FORM_SPEC
        // §0): does render(e)==render(a) agree with the host lattice
        // across every K-arm assert this sweep computed? DISAGREE rows
        // are R2 cutover blockers; RESIDUE sizes the walls.
        System.out.println("[canon] " + com.legend.exec.CanonicalDivergence.summary());
        com.legend.exec.CanonicalDivergence.samples().forEach(r ->
                System.out.println("[canon] " + r.family() + " " + r.detail()));
        System.out.println("[rcorpus] walls (mappings + dropped base elements): "
                + runner.walls().size());
        if (System.getProperty("rcorpus.walls") != null) {
            runner.walls().forEach(w ->
                    System.out.println("[rcorpus] WALL " + w));
        }
        if (onlyFilters.isEmpty() && regressions.isEmpty()) {
            System.out.println("[rcorpus] scoreboard written to docs/RELATIONAL_CORPUS.md");
            // TYPED-IR Slice 1: the label-lie census over the whole
            // corpus sweep (instrument -> census -> flip)
            System.out.println("[rcorpus] sqltypes: "
                    + com.legend.exec.SqlTypeCensus.summary());
            // 20 -> 60 (TYPED-IR M1): the top-20 cut hid the mismatch
            // TAIL exactly when the flip needs every class adjudicable
            // (doctrine addendum: an instrument without a consumer is a
            // receipt without an audit — no silent caps on the review
            // surface)
            com.legend.exec.SqlTypeCensus.classes(60).forEach(c ->
                    System.out.println("[rcorpus] sqltypes-class: " + c));
            com.legend.exec.SqlTypeCensus.allSamples().forEach((cls, ws) ->
                    ws.forEach(w -> System.out.println(
                            "[rcorpus] sqltypes-witness: " + cls + " :: "
                                    + w)));
        }
        // MECHANICAL REGRESSION GATE (audit: this runner carried NO
        // asserts — BUILD SUCCESS regardless of outcome). Every family
        // run IN FULL must meet the committed per-family pass baseline;
        // improvements advance the baseline through the rewritten
        // scoreboard, regressions FAIL the build. Viable only since the
        // flapper elimination (deterministic runner — consecutive sweeps
        // identical). -Drcorpus.test runs skip: partial family counts.
        // computed above, BEFORE the write — see the gate-before-write note
        // ADVISORY-SQL CEILING (deep-audit H5: golden-SQL divergence could
        // not fail the build — structurally wrong SQL passed if one row
        // assert also passed). Down-only: improvements lower it here.
        if (onlyFilters.isEmpty()) {
            int advisorySqlDiffs = byFamily.values().stream()
                    .flatMap(List::stream)
                    .mapToInt(Runner.Outcome::sqlDiffs).sum();
            // measured 2026-08-12 (the deep-audit's 246 counted TESTS,
            // not diffs); +1 2026-08-16: ledger clusters 35/40 changed
            // advisory SQL shape on row-verified tests (expression
            // membership 'in (<expr>)', value-polymorphic Date literals)
            // — rows are the contract, both changes make rows RIGHT.
            // +1 2026-08-16 (batch c45/c51/c52/c53): a newly-flipped
            // row-verified pass carries divergent advisory SQL text
            // (net: pass 2336->2341, sqldiff-pass 246->247, zero
            // pass-count regressions).
            // +10 2026-08-21 (shortcut-audit Blocker 1, ADJUDICATED):
            // the null-drop moved into the compiler — value-collection
            // egress now emits WHERE <cell> IS NOT NULL. The engine
            // performs the SAME drop CLIENT-side (SQLNull -> [] in
            // relationalMappingExecution.pure:480), so its golden text
            // structurally cannot carry the filter; the 10 diffs are
            // that one clause on row-verified tests (functions/tests 8,
            // mapping/join 1, aggregationAware/NOP 1 — witness:
            // testAssociationToManyAutoMap). Rows identical everywhere;
            // pass baseline unchanged at 2332.
            int maxAdvisorySqlDiffs = 309;
            org.junit.jupiter.api.Assertions.assertTrue(
                    advisorySqlDiffs <= maxAdvisorySqlDiffs,
                    "advisory golden-SQL diffs grew: " + advisorySqlDiffs
                            + " > ceiling " + maxAdvisorySqlDiffs);
            System.out.println("[rcorpus] advisory sql diffs: "
                    + advisorySqlDiffs + " (ceiling " + maxAdvisorySqlDiffs + ")");
            // LIVE SOFT-PASS CEILINGS (audit-of-audits #13):
            // CorpusSoftCeilingTest read the COMMITTED markdown while
            // the corpus never runs in CI — it could not go red on a
            // live regression, binding only through the human commit
            // loop. The ceilings now bind HERE, against THIS sweep's
            // own outcomes, and that test is DELETED. Down-only; bump
            // only with a written justification in the same commit
            // (2026-08-21 adjudication set sqldiff 257 / adv 303).
            java.util.List<Runner.Outcome> passes = byFamily.values().stream()
                    .flatMap(java.util.List::stream)
                    .filter(o -> o.status() == Runner.Status.PASS)
                    .toList();
            final long softDiff = passes.stream()
                    .filter(o -> o.sqlDiffs() > 0).count();
            final long softAdv = passes.stream()
                    .filter(o -> o.advisory() > 0).count();
            final long softZero = passes.stream()
                    .filter(o -> o.detail().startsWith("0 asserts")).count();
            final long softRescued = passes.stream()
                    .filter(o -> o.rescued() > 0).count();
            org.junit.jupiter.api.Assertions.assertAll(
                    () -> org.junit.jupiter.api.Assertions.assertTrue(
                            softDiff <= 257, "sqldiff-pass grew: " + softDiff
                                    + " > 257 — exact passes may have been"
                                    + " demoted; bump only with written"
                                    + " justification"),
                    () -> org.junit.jupiter.api.Assertions.assertTrue(
                            softAdv <= 303, "adv-pass grew: " + softAdv
                                    + " > 303"),
                    () -> org.junit.jupiter.api.Assertions.assertTrue(
                            softZero <= 27, "0-assert passes grew: "
                                    + softZero + " > 27"),
                    // 613 -> 614 (2026-08-23, relation wall burn): a
                    // PREVIOUSLY-FAILING test (modelJoin testChainedTwoHops)
                    // now PASSES — the aggregate-ORDER-BY hoist kept its
                    // declared null placement (pure DESC null-largest:
                    // 'Apple,null' leads), and its exec text differs from
                    // the golden by exactly that semantic clause, so the
                    // pass carries the rescue flag. Corpus 2332 -> 2333;
                    // a gained pass, not text decay.
                    () -> org.junit.jupiter.api.Assertions.assertTrue(
                            softRescued <= 614, "text-rescued passes grew: "
                                    + softRescued + " > 614"),
                    // contract-program wire ratchets (2026-08-23):
                    // corpus residue 181 (store cross-kind reads lead),
                    // adopt-pending 130 (integer aggregates — contract
                    // widens at construction)
                    () -> org.junit.jupiter.api.Assertions.assertTrue(
                            com.legend.exec.SqlTypeCensus
                                    .wireDivergeCount() <= 181,
                            "corpus wire divergence grew: "
                                    + com.legend.exec.SqlTypeCensus
                                            .summary()),
                    () -> org.junit.jupiter.api.Assertions.assertTrue(
                            com.legend.exec.SqlTypeCensus
                                    .wireAdoptPendingCount() <= 130,
                            "corpus wire adopt-pending grew: "
                                    + com.legend.exec.SqlTypeCensus
                                            .summary()),
                    // TYPED-IR M1/M2 (TYPED_SQL_IR.md): the judge-vs-node
                    // differential is an INVARIANT, not a trend — the two
                    // channels share one rule table and differ only in
                    // leaf knowledge, so any divergence is a WRONG LEAF
                    // STAMP or a rule bug (measured 0 at M1; every M2
                    // stamping slice must keep it 0)
                    () -> org.junit.jupiter.api.Assertions.assertEquals(
                            0, com.legend.exec.SqlTypeCensus
                                    .nodeDivergeCount(),
                            "node-vs-judge divergence — a wrong leaf stamp"
                                    + " or rule bug: "
                                    + com.legend.exec.SqlTypeCensus
                                            .summary()),
                    // M2 COMPLETE on the census surface (2026-08-24):
                    // pending-leaf burned 28,307 -> 0 (sourceColumn +
                    // the schema-loop sites stamp every resolved
                    // reference). Pinned AT ZERO — a regression here
                    // means a new construction site dropped leaf
                    // knowledge the judge still has (pin-to-invariant
                    // lifecycle; the constructor-invariant conversion
                    // lands with M3's judge deletion).
                    () -> org.junit.jupiter.api.Assertions.assertEquals(
                            0, com.legend.exec.SqlTypeCensus
                                    .nodePendingLeafCount(),
                            "node channel lost leaf knowledge the judge"
                                    + " has — a new unstamped site: "
                                    + com.legend.exec.SqlTypeCensus
                                            .summary()),
                    // R1b census pin (CANONICAL_FORM_SPEC §0, measured
                    // 2026-08-22): 27 grid-text verdicts pass only via
                    // the kept leniencies — 6 row-order-only (R2's
                    // canonical ORDER BY burns them) + 21 cross-engine
                    // float arithmetic (H2 decimal vs DuckDB binary —
                    // VALUE differences, the declared numeric policy).
                    // Shrink-only; a bump means a byte-exact verdict
                    // regressed to leniency.
                    () -> org.junit.jupiter.api.Assertions.assertTrue(
                            com.legend.exec.CanonicalDivergence
                                    .disagreeCount() <= 27,
                            "canonical-byte divergence grew: "
                                    + com.legend.exec.CanonicalDivergence
                                            .summary() + " (pin 27)"),
                    // V1 (OPEN_REGISTER): the DUAL-VERDICT alarm — the
                    // DB byte verdict and the host referee may NEVER
                    // disagree silently; any disagreement fails the
                    // sweep with the census line
                    () -> org.junit.jupiter.api.Assertions.assertTrue(
                            com.legend.exec.CanonicalDivergence
                                    .sqlDisagreeCount() == 0,
                            "DUAL-VERDICT DISAGREEMENT: "
                                    + com.legend.exec.CanonicalDivergence
                                            .summary()));
            System.out.println("[rcorpus] soft ceilings: sqldiff " + softDiff
                    + "/257, adv " + softAdv + "/303, 0-asserts " + softZero
                    + "/27, rescued " + softRescued + "/614");
        }
        org.junit.jupiter.api.Assertions.assertTrue(regressions.isEmpty(),
                "CORPUS REGRESSION vs committed docs/RELATIONAL_CORPUS.md: "
                + regressions
                + " — the scoreboard was NOT rewritten and the committed file is"
                + " intact, so there is nothing to revert. Fix the regression, or"
                + " check that -Dlegend.engine.root points at the checkout the"
                + " baseline was generated against.");
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
                throw new IllegalStateException("baseline has no 'pass'"
                        + " header — the regression gate would fail OPEN"
                        + " and the sweep would still WRITE (PX.1; audit"
                        + " §5.1). Fix docs/RELATIONAL_CORPUS.md.");
            }
        } catch (java.io.IOException e) {
            throw new IllegalStateException("baseline unreadable — the"
                    + " regression gate would fail OPEN and the sweep"
                    + " would still WRITE (PX.1; audit §5.1): " + e, e);
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
        // ONE session per family (task #112): seeds replay incrementally
        // inside it — the engine's per-package shared-server semantics
        runner.beginFamilySession();
        try {
            // Phase C: discovery through the REAL parser — stereotyped
            // functions off the parsed unit, body as AST.
            // ENGINE EXECUTION ORDER (PureTestBuilder.buildSuite):
            // sibling package suites sort by name and run BEFORE a
            // package's own tests, which sort by name — NOT source
            // declaration order (a declaration-first polluting INSERT
            // test poisoned 12 downstream tests; study §5.1, proven by
            // exact arithmetic, per-test sessions, and a name filter).
            List<Map.Entry<Path, Runner.ParsedTest>> ordered = new ArrayList<>();
            for (Map.Entry<Path, String> e : testSources.entrySet()) {
                for (Runner.ParsedTest t : Runner.discoverTests(e.getValue())) {
                    if (!onlyTest.isEmpty() && !t.fqn().contains(onlyTest)) {
                        continue;
                    }
                    ordered.add(Map.entry(e.getKey(), t));
                }
            }
            ordered.sort((a, b) -> engineSuiteOrder(a.getValue().fqn(),
                    b.getValue().fqn()));
            for (Map.Entry<Path, Runner.ParsedTest> e : ordered) {
                runner.selectFile(e.getKey().toString());
                outcomes.add(runner.run(e.getValue()));
            }
        } finally {
            runner.endFamilySession();
        }
        return outcomes;
    }

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
                            String t = tok.strip();
                            if (t.contains("::")) {
                                wanted.add(t);
                            } else if (!t.isEmpty()) {
                                // a supertype resolved via import
                                // wildcard (study §5.4b): try each
                                // import prefix — unknown candidates
                                // skip at the index lookup below
                                for (String imp : imps) {
                                    wanted.add(imp + "::" + t);
                                }
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
