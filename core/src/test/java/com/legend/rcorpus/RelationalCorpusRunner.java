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
        // ENGINE-CORPUS-COMPAT (user ruling 2026-08-29, the ENGINE_CASED
        // precedent): the engine's tests assert positionally while
        // relying on H2's implicit scan order — replaying them on DuckDB
        // opts into the explicit scan-order pass. The PLATFORM default
        // stays order-honest (no sort demanded = no order guaranteed).
        System.setProperty("legend.exec.engineScanOrder", "true");

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
        // V7 TENET CORRECTION (2026-08-28, user catch): the assert
        // family is PLATFORM-OWNED registry natives (Pure.java, real
        // signatures verified verbatim; AssertVerdicts is the
        // implementation) — an earlier slice loaded the real
        // legend-pure assert SOURCES here as library files, which made
        // the reference implementation a runtime component of our
        // model. Reference checkouts are SPEC and TEST INPUT, never
        // platform machinery; registerLibrarySource now REFUSES
        // platform-namespace elements outright.
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
                        // 1372 -> 1375 (§4AD batch 5, THE ROUTER FLIP):
                        // +3 h2-lane passes from the lifted fan-out shapes
                        () -> org.junit.jupiter.api.Assertions.assertTrue(
                                p >= 1375, "h2 sweep pass fell: " + p
                                        + " < floor 1375 (SQL-IR slice 2"
                                        + " outputs-from-projections:"
                                        + " 1367 -> 1372 — the milestoning"
                                        + " union-wrap residue healed +"
                                        + " 3 more label-consistency rows."
                                        + " Known residue testChained"
                                        + "JoinsWithUnionsAndIsolation"
                                        + "WithProjectionQueryTableFilter"
                                        + " is a DEMAND divergence, not"
                                        + " origin: the union extent"
                                        + " projects an undemanded"
                                        + " legalName the engine prunes;"
                                        + " our prune is blocked by the"
                                        + " star over the union frame —"
                                        + " charter ORIGIN_ARCHITECTURE"
                                        + "_AUDIT landing record)"),
                        () -> org.junit.jupiter.api.Assertions.assertTrue(
                                seedFails.size() <= 6,
                                "h2 failed seeds grew: " + seedFails.size()
                                        + " > 6"),
                        // 945 -> 946 (slice-3 equality half, 2026-08-28,
                        // JUSTIFIED): one of the 94 newly-compiling
                        // sqltext asserts reaches a REGISTERED h2
                        // renderer capability wall under the h2 backend
                        // — one more plan reading a known gap, not a
                        // widened gap (DuckDB sweep pass counts and all
                        // EQUALITY-0 gates unchanged)
                        // 946 -> 947 (§4AD batch 5, JUSTIFIED — the
                        // design doc's predicted pattern: UNNEST 903 ->
                        // 904; a lifted fan-out shape reads the
                        // REGISTERED h2 UNNEST-placement gap, not a
                        // widened gap; h2 floor +3 in the same commit)
                        () -> org.junit.jupiter.api.Assertions.assertTrue(
                                u <= 947, "h2 capability walls grew: " + u
                                        + " > 947 — a renderer gap widened"
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
                    // STORY CORRECTED 2026-08-28 (probe on stock
                    // h2-2.1.214): these are NOT engine-golden defects
                    // and NO fork leniency exists — the engine's patched
                    // jar replaces only Mode/TypeInfo. The goldens alias
                    // e.g. "city" AND CITY in one subselect, legal on
                    // the engine's case-SENSITIVE session (H2Defaults);
                    // only OUR CASE_INSENSITIVE_IDENTIFIERS session
                    // collides them. The oracle now retries on
                    // H2Settings.ENGINE_CASED — rows landing here are
                    // seeds that cannot replay case-sensitively.
                    "Duplicate column name", 0,
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
                    // 57→66 (slice 3 equality half, 2026-08-28,
                    // JUSTIFIED): sides acquire text by REAL EVALUATION;
                    // the old findTerminal returned null SILENTLY for
                    // unmatchable shapes (their failures scattered into
                    // other buckets) — every acquisition failure is now
                    // COUNTED HERE with its cause (EngineStyleH2 dialect
                    // walls: array/LIST/UNNEST/ROUND; splice shapes:
                    // sql() on non-frame receivers). RECATEGORIZATION,
                    // not lost verification: verified 320→321, rescued
                    // 632, diverged 0, unverifiable 145 — all equal or
                    // better in the same sweep, zero test regressions.
                    "sql-text side", 66);
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
        // declaration-vs-fixture skew census (charter §4bZ): every
        // column the setup stream created with a kind contradicting the
        // ###Relational declaration — engine test-data debt, the named
        // explanation for the wire-diverge rows the deleted coercion
        // arms used to hide. Printed on every run BEFORE the gate
        // asserts (scoped iteration needs it); the count pins in the
        // full-run assert block below.
        long skewCols = Runner.FIXTURE_SKEW.values().stream()
                .mapToLong(java.util.Set::size).sum();
        System.out.println("[rcorpus] fixture-skew columns: " + skewCols);
        Runner.FIXTURE_SKEW.forEach((cls, ws) -> {
            System.out.println("[rcorpus] fixture-skew-class: "
                    + ws.size() + "x " + cls);
            ws.forEach(w -> System.out.println(
                    "[rcorpus] fixture-skew-witness: " + cls + " :: " + w));
        });
        // [1]-over-nullable-column census (typed-IR queue item 2):
        // computed per compile at the platform's DeclaredCoercions
        // pairing seam, aggregated by the harness — the unchecked
        // "[1]-property => NOT NULL column" implication, per bucket
        // with honesty buckets for unadjudicated arms. Census NOT
        // warning (engine fixtures fire it wholesale; the diagnostic
        // waits for the dialect split); quantifies model debt inside
        // the 925 wire-breach census. Count pins in the full-run
        // assert block below.
        System.out.println("[rcorpus] required-over-nullable pairings: "
                + reqNullAdjudicated());
        Runner.REQUIRED_OVER_NULLABLE.forEach((bucket, ws) -> {
            System.out.println("[rcorpus] required-over-nullable "
                    + bucket + ": " + ws.size());
            ws.forEach(w -> System.out.println(
                    "[rcorpus] required-over-nullable-witness: "
                            + bucket + " :: " + w));
        });
        // M1 GATE PINNING (H2_BACKEND.md §12 step 13): on a FULL sweep,
        // any divergence fails the build (they already FAIL per-test —
        // this pins the aggregate against silent scoring drift), and the
        // verified count must hold its floor (289 at c43, 296 after the
        // c46 enum-decode rung, 309 after slice 10's engine-text NULLS
        // suppression — 13 rows had diverged from golden text only by a
        // nulls clause; ratchet on deliberate gains). 309→320
        // (2026-08-20 stamp C2-i): provably-single cell reads lower as
        // PLAIN scalar subqueries — 11 more texts byte-match.
        // DIAGNOSTICS BEFORE VERDICTS (the program's first-failure rule):
        // the canon/v7 census prints precede the lane-guard asserts — a
        // tripped guard must never hide the very numbers that diagnose it.
        System.out.println("[canon] " + com.legend.exec.CanonicalDivergence.summary());
        // the ALARM witnesses print first from their reserved buffer —
        // never lost to shared-sample crowding
        com.legend.exec.CanonicalDivergence.sqlDisagreeSamples().forEach(r ->
                System.out.println("[canon] ALARM " + r.family() + " "
                        + r.detail()));
        com.legend.exec.CanonicalDivergence.samples().forEach(r ->
                System.out.println("[canon] " + r.family() + " " + r.detail()));
        System.out.println("[v7] "
                + com.legend.exec.CanonicalDivergence.v7Summary());
        com.legend.exec.CanonicalDivergence.v7Report().forEach(l ->
                System.out.println("[v7] " + l));
        if (onlyFilters.isEmpty()) {
            org.junit.jupiter.api.Assertions.assertEquals(0,
                    com.legend.harness.H2Verify.M1_DIVERGED.sum(),
                    "M1 h2-exec divergences on a full sweep");
            // 320 -> 436 (diff-noreplay burndown 2026-08-28): Graph
            // frames replay — byte-matched goldens of class-mapped
            // queries now row-verify instead of declining non-tabular
            org.junit.jupiter.api.Assertions.assertTrue(
                    com.legend.harness.H2Verify.M1_VERIFIED.sum() >= 455,
                    "M1 h2-exec verified fell below the 455 floor: "
                    + com.legend.harness.H2Verify.M1_VERIFIED.sum());
            // V7 §8.0 leg 0 — the LANE-CLASSIFICATION GUARD (charter
            // scope table, user-ratified 2026-08-28): the sql-text/TDG
            // partition counts pin EXACTLY — an assert can never change
            // lanes silently; a corpus change that moves these updates
            // the pin AND the charter table in the same commit.
            // 961 -> 1529 (§8 leg 4 census split): content-based
            // classification — an assert whose args pull a sql-producer
            // call is a plan-text compare whatever its form name.
            // CONFIRMED at 1529 by the task-#13 slice-2 rewire: the
            // RESOLUTION-backed classifier (exact FQNs, resolvesTo)
            // reproduces this count exactly — the name-sniffing
            // deletion moved the mechanism, not one row.
            // The user-ratified OUTCOME buckets (2026-08-28), measured
            // then pinned EXACTLY (sum = the ratified 1529+123; csv lost
            // its 6 plan-let rows to text-only — the old tdg reason
            // conflated them). exec-passing may only GROW by burndown;
            // UNABLE-TO-EXEC (esp. diff-noreplay 321, the weakest class:
            // text DIFFERS and no replay ran) may only SHRINK.
            // 989 -> 990 (equality half: +1 text-match row-verified)
            // 990 -> 1276 (diff-noreplay burndown 2026-08-28, charter
            // §4AB): GRAPH frames row-verify (goldenGraphCompare — the
            // instance array the database built compares by LABEL
            // against the golden's data aliases; pk_$i/k_* bookkeeping
            // excluded by the engine's own spelling) + the microsecond
            // temporal floor (DuckDB storage precision). 286 rows
            // converted, every one a REAL golden-vs-ours row compare.
            // 1276 -> 1385 (slices 2-4 same day): union/milestoning
            // bookkeeping aliases + frame-side context echo + empty
            // frame row-count verdict (+84); per-key enum decode for
            // class frames (+14); case-collision goldens retried on
            // the engine's own casing, H2Settings.ENGINE_CASED (+11).
            // 1385 -> 1387 (§4AD slice 1 batch 1, value-position
            // fan-out): testQualifierWithIsolationXX +
            // testChainedInnerJoinsWithQualifierInGroupBy row-verify
            // against the engine goldens; 2 more UPGRADED
            // rescued -> byte-matched (testQualifierWithVariableArg ×2).
            // 1387 -> 1396 (§4AD batch 5, THE ROUTER FLIP): +9
            // row-verified — testQualifierWithIsolation (a baseline
            // ERROR, the topology round's predicted win), the
            // filter-mapping overlap pair, and six wrapper/hop-rich
            // projection qualifiers (first()/head() unwrap +
            // per-occurrence bundling), all via the one-owner router.
            // 1396 -> 1448 (§4AD P0.5, THE CORRECTED ORACLE UNPARK —
            // NAV_ROUTING_PLACEMENT_ADDENDUM_4AD): Collection/Scalar
            // verify lane ON, golden rows flattened at the VALUE
            // observable (H2Verify.goldenRowsCompare receipt). The lane
            // is NOT clean: testQualifierWithOperation +
            // testTwoQualifiersWithOperation FAIL as NAMED DEFECTS
            // (batch-5 placement defect, tests/advanced baseline 66 ->
            // 64) — burned by P1, which restores 66. Never re-parked,
            // never re-adjudicated.
            // 1448 -> 1449 (§4AD task #72, strict-read hoist): the
            // formerly-WALLED testInputNotIsolatedWhenPropertyPathIsToOne
            // now executes — its sql-text assert row-verifies (our
            // presence spelling vs the engine's hoisted-pred text).
            // THE 9 DUAL-CHANNEL DISAGREEMENTS — CLOSED AS NAMED RESIDUE
            // (docs/VERDICT_DISAGREEMENT_BURN_2026_08_30.md, receipts
            // R1-R8): production equality is pure-faithful (Decimal
            // scale-sensitive, temporal subsecond-string-exact); the
            // harness referee matches the engine's decode-lenient
            // observable — a DESIGNED split, not a defect. Classes:
            // Decimal-scale x4 (testSimpleTypeMapping/Project),
            // nine-digit temporal x2 (same tests' ts column), TDS-lane
            // temporal x1 (testDayOfMonth), TDSNull row-string x1
            // (testDeepUnionOperation...), sort-tie x1
            // (testConcatenateWithJoin — the phantom class). EXACT pin:
            // any movement is a semantic change needing adjudication.
            org.junit.jupiter.api.Assertions.assertEquals(9,
                    com.legend.exec.CanonicalDivergence.v7DisagreeCount(),
                    "dual-channel disagreements moved off the NAMED 9 —"
                            + " see VERDICT_DISAGREEMENT_BURN_2026_08_30");
            org.junit.jupiter.api.Assertions.assertEquals(1449,
                    com.legend.exec.CanonicalDivergence
                            .v7DeclinedByReasonPrefix(
                                    "assert-sql-text-with-exec-passing"),
                    "lane guard: assert-sql-text-with-exec-passing moved —"
                            + " update the charter §8.0 scope table");
            org.junit.jupiter.api.Assertions.assertEquals(44,
                    com.legend.exec.CanonicalDivergence
                            .v7DeclinedByReasonPrefix("assert-sql-text-only"),
                    "lane guard: assert-sql-text-only moved — update the"
                            + " charter §8.0 scope table");
            // 502 -> 492 (slice 3 real evaluation): the predicate 16
            // became 10 REAL verified passes (dual-channel agree) + 6
            // recorded divergences (predicate-diverged — dialect-owned
            // text, same policy as assertSameSQL mismatch)
            // 492 -> 206 -> 97 (diff-noreplay burndown slices 1-4):
            // diff-noreplay 321 -> 71, match-noreplay 142 -> 8; then
            // 97 -> 45 (§4AD P0.5, the corrected unpark): the 45
            // collection/scalar PARKED rows all verify now (the park's
            // "set-vs-row adjudication" was the batch-5 placement
            // defect wearing a policy name — addendum §7 item 4), plus
            // the 2 value-observable flatten conversions and 5 more
            // reclassified by execution. Residue = enum underivable,
            // case-sensitive seed replay, graph-keys tail,
            // tempTableForIn, arity, skew, no-gen, predicate-diverged,
            // both-ours (per-cause census in the sweep log).
            org.junit.jupiter.api.Assertions.assertEquals(45,
                    com.legend.exec.CanonicalDivergence
                            .v7DeclinedByReasonPrefix(
                                    "assert-sql-text-unable-to-exec"),
                    "lane guard: assert-sql-text-unable-to-exec moved —"
                            + " update the charter §8.0 scope table");
            org.junit.jupiter.api.Assertions.assertEquals(117,
                    com.legend.exec.CanonicalDivergence
                            .v7DeclinedByReasonPrefix("assert-test-data-csv"),
                    "lane guard: assert-test-data-csv moved — update the"
                            + " charter §8.0 scope table");
            // leg 7 ratchets: row-verification coverage holds its
            // floor; the unverifiable residue only SHRINKS (the 145
            // burndown — each fix converts an advisory pass into a
            // row-verified pass and moves these two in lockstep).
            // 632/145 -> 791/30 (diff-noreplay burndown 2026-08-28):
            // Graph-frame replay converts divergent-text rows to
            // row-verified rescues and byte-matched rows out of the
            // unverifiable residue — ratchet to measured
            org.junit.jupiter.api.Assertions.assertTrue(
                    com.legend.harness.H2Verify.M1_RESCUED.sum() >= 880,
                    "M1 h2-exec rescued fell below the 880 floor: "
                    + com.legend.harness.H2Verify.M1_RESCUED.sum());
            org.junit.jupiter.api.Assertions.assertTrue(
                    com.legend.harness.H2Verify.M1_UNVERIFIABLE.sum() <= 11,
                    "M1 h2-exec unverifiable grew past the 11 ceiling"
                    + " (leg-7 burndown is shrink-only): "
                    + com.legend.harness.H2Verify.M1_UNVERIFIABLE.sum());
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
        // §0) and the V7 dual-channel census (V7_ASSERT_VERDICT_CHARTER
        // §4.1) print ABOVE, before the lane guards.
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
            // surface). 60 -> 120 (N0, §4bZ-V E): the bottom-mult key
            // split by SHAPE — the smallest classes (6x DATE pads) must
            // stay visible for the machine count.
            com.legend.exec.SqlTypeCensus.classes(120).forEach(c ->
                    System.out.println("[rcorpus] sqltypes-class: " + c));
            com.legend.exec.SqlTypeCensus.allSamples().forEach((cls, ws) ->
                    ws.forEach(w -> System.out.println(
                            "[rcorpus] sqltypes-witness: " + cls + " :: "
                                    + w)));
            // §E3 M-N1 — the nullability differential (fact vs label),
            // census-first: the M-N3 flip's payload. Summary on the
            // console, full class/witness decomposition to target/
            // (the h2-verdicts dump idiom — attributable by diffing
            // two sweeps' files). No pin this slice: measured, then
            // adjudicated at M-N2/M-N3 (the converse-tripwire
            // precedent).
            System.out.println("[rcorpus] nullable-diff: "
                    + com.legend.exec.SqlTypeCensus
                            .nullableDifferentialSummary());
            // top classes ALSO on the console (the sqltypes-class
            // idiom): the target/ dump dies at gate 8's `-am clean`
            // (the TimingLedger lesson — a chain-run G4's file is
            // gone by chain end; the console line survives in g4.out)
            com.legend.exec.SqlTypeCensus.nullableDifferentialReport()
                    .stream().skip(1).limit(160).forEach(c ->
                            System.out.println(
                                    "[rcorpus] nullable-diff-class: "
                                            + c));
            try {
                java.nio.file.Files.writeString(
                        java.nio.file.Path.of("target",
                                "nullable-differential.txt"),
                        String.join("\n", com.legend.exec.SqlTypeCensus
                                .nullableDifferentialReport()) + "\n");
            } catch (java.io.IOException ignore) {
                // best-effort diagnostic (histogram precedent)
            }
            // §E3 SLACK CENSUS (the breach tripwire's converse,
            // post-flip precision instrument): nullable-labeled
            // columns that delivered values and never a NULL —
            // evidence, not proof (test-data dependent, deliberately
            // unpinned); ranks the precision refinements. Console
            // classes survive the chain (dump dies at gate 8's clean).
            System.out.println("[rcorpus] nullable-slack: "
                    + com.legend.exec.SqlTypeCensus.slackSummary());
            // §E3-S pad price tag: construction-event upper bound for
            // the WHERE≡INNER refinement (read flips; frame weakening
            // is now a derived fact of Join.outputs() — uncounted)
            System.out.println("[rcorpus] pad-weaken: reads="
                    + com.legend.sql.SqlTyping.PAD_READ_FLIPPED.sum());
            // §4AD navigation-arm census: blast radius of the
            // relational-conformance redesign as NAMED witness lists
            // (charter execution step 1) — console counts here, full
            // per-test lists in target/nav-arm-census.txt (the
            // h2-verdicts dump idiom)
            var navArms = com.legend.lowering.NavArmCensus.snapshot();
            StringBuilder navDump = new StringBuilder();
            navArms.forEach((arm, tests) -> {
                System.out.println("[rcorpus] nav-arm " + arm + ": "
                        + tests.size() + " tests");
                tests.forEach(t2 -> navDump.append(arm).append(' ')
                        .append(t2).append('\n'));
            });
            try {
                java.nio.file.Files.writeString(
                        java.nio.file.Path.of("target",
                                "nav-arm-census.txt"),
                        navDump.toString());
            } catch (java.io.IOException e) {
                System.out.println("[rcorpus] nav-arm dump failed: " + e);
            }
            com.legend.exec.SqlTypeCensus.slackReport().stream().skip(1)
                    .limit(160).forEach(c -> System.out.println(
                            "[rcorpus] nullable-slack-class: " + c));
            try {
                java.nio.file.Files.writeString(
                        java.nio.file.Path.of("target",
                                "nullable-slack.txt"),
                        String.join("\n", com.legend.exec.SqlTypeCensus
                                .slackReport()) + "\n");
            } catch (java.io.IOException ignore) {
                // best-effort diagnostic (histogram precedent)
            }
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
            // +3 2026-08-25 (§4bZ-U leg 2, JUSTIFIED): scalar-typed
            // collection egress boxes as [e] before the compact+UNNEST
            // (the bare-scalar list_filter cannot BIND — DuckDB binder
            // receipt on testSubAggregationMultiLevel's lateral) — the
            // boxed spelling diverges from engine golden text on 3
            // row-verified tests; rows are the contract, all pass
            // counts unchanged, corpus untyped hit 0 with this slice.
            // +6 2026-08-28 (slice 3 predicate real-evaluation,
            // JUSTIFIED): six fragment-check predicates (contains
            // 'union_gen_source_pk_0' etc.) now EVALUATE and record
            // their dialect divergence here instead of being invisible
            // advisory skips — strictly more information, rows verified
            // by the same tests' row asserts (charter §4Z addendum)
            // 318 -> 157 -> 76 (diff-noreplay burndown 2026-08-28, down-only
            // ratchet): 161 divergent-text sql asserts converted to
            // row-verified rescues — their diffs now ride the rescue
            // channel (counted, visible), not the advisory-diff channel
            int maxAdvisorySqlDiffs = 76;
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
            // 257/303 -> 258/304 (§4bZ-U leg 2, 2026-08-25, JUSTIFIED
            // with the advisory-ceiling move in the same commit): the
            // scalar-typed collection egress boxes as [e] (the bare
            // scalar could not BIND under list_filter), so previously
            // byte-exact/advisory-clean passes now differ from engine
            // golden text by exactly that wrap; rows verified, corpus
            // untyped 0.
            // 258 -> 264 (slice 3 predicate real-evaluation 2026-08-28,
            // JUSTIFIED with the advisory-ceiling move in the same
            // commit): six fragment-check predicates now EVALUATE and
            // their tests pass CARRYING a recorded divergence instead
            // of an invisible advisory skip — no exact pass demoted
            // (exec-passing 989 and the pass total unchanged).
            org.junit.jupiter.api.Assertions.assertAll(
                    () -> org.junit.jupiter.api.Assertions.assertTrue(
                            softDiff <= 264, "sqldiff-pass grew: " + softDiff
                                    + " > 264 — exact passes may have been"
                                    + " demoted; bump only with written"
                                    + " justification"),
                    () -> org.junit.jupiter.api.Assertions.assertTrue(
                            softAdv <= 304, "adv-pass grew: " + softAdv
                                    + " > 304"),
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
                    // 614 -> 751 -> 816 (diff-noreplay slices 1-4,
                    // JUSTIFIED with the advisory-ceiling drop 318->157
                    // in the same commit): Graph-frame replay upgrades
                    // divergent-text advisory skips on PASSING tests to
                    // counted row-verified rescues — the flag moved from
                    // the advisory channel to the rescue channel on the
                    // same tests; no exact pass was demoted (exec-passing
                    // 990 -> 1276, pass baselines unchanged).
                    // 816 -> 823 (§4AD batch 5, THE ROUTER FLIP —
                    // JUSTIFIED with exec-passing 1,387 -> 1,396 in the
                    // same commit): the lift's per-occurrence bundled
                    // frames are row-equal to the engine's flat form by
                    // LEFT-join associativity but text-divergent (nested
                    // vs flat bundling) — 7 passes moved byte-match ->
                    // row-verified rescue; zero passes demoted. Text
                    // re-convergence = the emission-anatomy leg (mirror
                    // the engine's frame shape), not a routing concern.
                    // 823 -> 861 (§4AD P0.5, the corrected unpark —
                    // JUSTIFIED by exec-passing 1,396 -> 1,448 and
                    // unable-to-exec 97 -> 45 in the SAME commit): 38
                    // formerly-unverifiable value-frame passes now
                    // carry the ROW-VERIFIED rescue flag —
                    // verification gained, not text decayed.
                    // 861 -> 863 (§4AD P2): the filter-position PAD
                    // GUARD (the conjoined qualifier pred) is SEMANTIC
                    // text — 2 byte-matched asserts became
                    // row-verified rescues (0 diverged; measured
                    // matched 466->464, rescued 929->931; ATTRIBUTED:
                    // filterFunctionExpressionWithConditionOnLeftAnd-
                    // RightTable + ...WithAndConditionOnRootAndRight-
                    // Table — engine spells the pred ONCE in the ON,
                    // ours ALSO guards the WHERE).
                    // 863 -> 864 (§4AD task #72): the un-walled
                    // testInputNotIsolatedWhenPropertyPathIsToOne passes
                    // CARRYING the row-verified rescue (ERROR -> PASS,
                    // functions/tests 241 -> 242, corpus 2,353 -> 2,354).
                    () -> org.junit.jupiter.api.Assertions.assertTrue(
                            softRescued <= 864, "text-rescued passes grew: "
                                    + softRescued + " > 864"),
                    // contract-program wire ratchets (RE-PINNED at the
                    // 2026-08-24 label flip: 181->114->56 and 130->13 —
                    // adopted HUGEINT labels, registered carriages, then
                    // the pure-Decimal erasure ADOPTION (labels take the
                    // wire's own precision: 58 more exact wire matches);
                    // deterministic counts, ratcheted to measured).
                    // 56 -> 7 (T4 attempt 2, charter §4bR Slice A): the
                    // concrete-Float-over-DECIMAL conform cast at the
                    // MappingNormalizer pairing seam — the 48 DOUBLE<>
                    // DECIMAL(18,6) rows (mapping::dataType family) now
                    // speak DOUBLE on the wire.
                    // 7 -> 4 (the wire-7 review, 2026-08-25): the 3
                    // HUGEINT<>DOUBLE rows healed (SUM tolerance
                    // transport). 4 -> 2 (§4bZ-U leg 4, 2026-08-25):
                    // the fetchDb catalog grids got their DECLARED
                    // JDBC-spec schemas (CatalogGrids.gridSchema) — the
                    // 2x JSON<>VARCHAR SQL_TYPE_NAME rows healed to
                    // typed VARCHAR labels. 2 -> 0 (§4bZ-U ruling,
                    // 2026-08-25 — "burn 2 and 3 to zero"): late-bound
                    // frames DID learn runtime schemas — a by-name
                    // FIELD read now DEMANDS the LIMIT-0 probe
                    // (RawGridSchema's widened gate) and the probe
                    // carries the database's own column types
                    // (GridProbe.probeTypedColumns), so the
                    // dropAndCreateTable cells label BIGINT and the
                    // wire agrees. The bare .rows egress stays
                    // single-query (ExecuteInDbProbeCountTest pins
                    // both sides). HARDENED TO EQUALITY at zero.
                    () -> org.junit.jupiter.api.Assertions.assertEquals(
                            0, com.legend.exec.SqlTypeCensus
                                    .wireDivergeCount(),
                            "corpus wire divergence reappeared: "
                                    + com.legend.exec.SqlTypeCensus
                                            .summary()),
                    // adopt-pending burned 130 -> 13 -> 0 (the label
                    // flip + the arithmetic promotion rules): every
                    // integer-aggregate/arith label now speaks its
                    // wire. Hardened to EQUALITY at zero.
                    () -> org.junit.jupiter.api.Assertions.assertEquals(
                            0, com.legend.exec.SqlTypeCensus
                                    .wireAdoptPendingCount(),
                            "wire adopt-pending reappeared: "
                                    + com.legend.exec.SqlTypeCensus
                                            .summary()),
                    // THE GUEST LIST (charter §4bZ, 2026-08-25): the
                    // two blanket coercion arms are DELETED; a label/
                    // wire mismatch is tolerated ONLY for reads tagged
                    // at the mapping seam (a declared property/column
                    // kind mismatch — engine carry-through compat).
                    // First audit sweep: ALL 111 arrived tagged (97
                    // VARCHAR + 14 DOUBLE — the sampled attribution is
                    // now machine-proven row-by-row), and the pardon's
                    // deletion EXPOSED 20 hidden rows (the CEILING
                    // rule-vs-emission lie, fixed same sweep). Ceiling:
                    // growth = a new mismatched mapping or an accident
                    // — justify in the commit either way.
                    // 111 -> 153 (wire-7 review, JUSTIFIED move): SUM
                    // transports the tolerance, so sum-over-a-tagged-
                    // read keeps the PURE contract label (Float ->
                    // DOUBLE) instead of adopting the stamp promotion
                    // (HUGEINT) — the 3 testReprocessGroupByAlias wire
                    // rows healed (fixture-skew FLOAT wires genuinely
                    // sum to DOUBLE), +33 DOUBLE<-HUGEINT + 9 equal-
                    // pair propagation slots joined the registered
                    // guest list; row verdicts and scoreboard
                    // byte-stable.
                    // 153 SPLIT BY PROVENANCE (§4Z ledger #1 repin,
                    // 2026-08-26; refined same day to a SHAPE split —
                    // the pair alone cannot tell a seam read from an
                    // aggregate over one, and the machine count showed
                    // even the audited "111" hid 3 aggregate rows):
                    // ORIGIN 108 = bare COLUMN READS with a differing
                    // pair — one row per real mapping-seam kind
                    // mismatch (97 VARCHAR<-BIGINT + 11
                    // DOUBLE<-BIGINT); growth here is a NEW mismatched
                    // mapping, a model fact that must be justified in
                    // the commit. DERIVED 36 = operations over tagged
                    // reads keeping the pure contract label (33 SUM
                    // DOUBLE<-HUGEINT — the wire-7 transport family —
                    // + 3 MAX-style DOUBLE<-BIGINT); moves with
                    // aggregate shapes. TRANSPORTED 9 = equal-pair
                    // propagation slots (DOUBLE<-DOUBLE) — plumbing
                    // that grows with query shape only.
                    // 108/36/15/56 -> 122/68/27/63 (slice-3 equality
                    // half, 2026-08-28, JUSTIFIED as ONE move): the
                    // position-independent toSQLString fold compiles 94
                    // previously-walled sqltext asserts (+~1000 plans);
                    // the tolerance slots are PER-PLAN counts over the
                    // SAME registered seam kinds on the SAME mappings —
                    // more plans reading a known seam, not new model
                    // facts. The EQUALITY-0 quality gates (mismatch,
                    // wire diverge, null-breach, unknown) all HELD in
                    // the same sweep.
                    () -> org.junit.jupiter.api.Assertions.assertTrue(
                            com.legend.exec.SqlTypeCensus
                                    .toleratedOriginCount() <= 122,
                            "mapping-seam ORIGIN tolerated slots grew"
                                    + " (a new mismatched mapping?): "
                                    + com.legend.exec.SqlTypeCensus
                                            .summary()),
                    () -> org.junit.jupiter.api.Assertions.assertTrue(
                            com.legend.exec.SqlTypeCensus
                                    .toleratedDerivedCount() <= 68,
                            "tolerance-derived slots grew (an op over"
                                    + " a tagged read): "
                                    + com.legend.exec.SqlTypeCensus
                                            .summary()),
                    // 9 -> 15 (§4bZ-V D1, 2026-08-26, JUSTIFIED): the
                    // D1 value-evidence tripwire exposed 6 valued
                    // INTEGER wires under VARCHAR labels
                    // (testSQLQueryMergingForInnerJoins2's
                    // String-declared p3 over dTable.pk) — the member
                    // frames carried the mapping-seam tag all along,
                    // but SqlUnion's ctor rebuilt outputs from the
                    // pure contract and DROPPED it; union-label
                    // reconciliation now transports tag/type/
                    // nullability across the union node, so the six
                    // slots land here (equal-pair plumbing) and their
                    // wire rows move diverge -> tolerated.
                    () -> org.junit.jupiter.api.Assertions.assertTrue(
                            com.legend.exec.SqlTypeCensus
                                    .toleratedTransportedCount() <= 27,
                            "tolerance-transport slots grew: "
                                    + com.legend.exec.SqlTypeCensus
                                            .summary()),
                    // D2 (§4bZ-V, 2026-08-26): every wire probe must
                    // adjudicate — the two old unknowns were
                    // zero-output no-claim frames (now skipped by
                    // doctrine); a NEW unknown is an unreadable or
                    // shape-broken probe, classed + witnessed in the
                    // failure. EQUALITY at zero.
                    () -> org.junit.jupiter.api.Assertions.assertEquals(
                            0, com.legend.exec.SqlTypeCensus
                                    .wireUnknownCount(),
                            "unadjudicated wire probes appeared: "
                                    + com.legend.exec.SqlTypeCensus
                                            .summary()),
                    // D1 (§4bZ-V, 2026-08-26): int-or-null settled by
                    // VALUE evidence — every row here is a PROVEN
                    // all-NULL column (no factual wire type exists;
                    // DuckDB spells it INTEGER); a valued column lands
                    // in diverge (EQUALITY-0 above) instead. Ceiling —
                    // grows only with all-NULL result columns (query
                    // shape), ratchet down as shapes burn.
                    () -> org.junit.jupiter.api.Assertions.assertTrue(
                            com.legend.exec.SqlTypeCensus
                                    .wireIntOrNullEmptyCount() <= 63,
                            "proven-empty int-or-null columns grew: "
                                    + com.legend.exec.SqlTypeCensus
                                            .summary()),
                    // THE LABEL FLIP (TYPED_SQL_IR.md, 2026-08-24):
                    // reconciliation makes a label lie structurally
                    // impossible — the census's mismatch bucket is
                    // EMPTY by construction, pinned as the completed
                    // label-lie program (instrument -> census -> flip).
                    () -> org.junit.jupiter.api.Assertions.assertEquals(
                            0, com.legend.exec.SqlTypeCensus
                                    .mismatchCount(),
                            "a label lie escaped reconciliation: "
                                    + com.legend.exec.SqlTypeCensus
                                            .summary()),
                    // POST-JUDGE TRIPWIRE (TYPED_SQL_IR.md, judge
                    // deleted 2026-08-24): untyped projection roots =
                    // rule-coverage debt AND the leaf-regression
                    // signal (a new unstamped construction site GROWS
                    // this). 1,116 -> 737 -> 717 -> 424 (M4) -> 24
                    // (rules burn) -> 4 (2026-08-25 FULL burn: the
                    // SPLIT->VARCHAR[] rule closed the 20-row XStore
                    // family) -> 0 (§4bZ-U legs, 2026-08-25: fetchDb
                    // grids got DECLARED JDBC-spec schemas; the
                    // scalar-typed collection egress boxes as [e] —
                    // the bare-scalar list_filter could not even BIND,
                    // so the subagg-lateral and concatenate roots were
                    // unbindable emissions the census had been
                    // flagging as type debt). Hardened to EQUALITY at
                    // zero — a new untyped root is a regression, with
                    // its witness in the failure message.
                    () -> org.junit.jupiter.api.Assertions.assertEquals(
                            0, com.legend.exec.SqlTypeCensus
                                    .untypedCount(),
                            "untyped projection roots reappeared — a"
                                    + " missing rule or an unstamped leaf: "
                                    + com.legend.exec.SqlTypeCensus
                                            .summary()),
                    // THE NULLABILITY LEDGER (§4bZ-V E, 2026-08-26 —
                    // §4Z ledger #4, the last open burn-down ledger):
                    // N0 machine-counted the 6,472-row backlog as 100%
                    // literal NullLit union-member pads (5707 BIGINT +
                    // 598 VARCHAR + 161 BOOLEAN + 6 DATE — member key
                    // pads, stc_* subtype columns, bitemporal member
                    // columns); N1 made a projected literal NULL
                    // declare its slot nullable at construction
                    // (reconcileLabels), burning 6,472 -> 0 with every
                    // other census bucket byte-identical. Residue
                    // adjudicated EMPTY — EQUALITY at zero on both
                    // corpus lanes: a row here is a COMPUTED bottom (a
                    // NULL-propagating expression) under a required
                    // label, witness in the failure message.
                    () -> org.junit.jupiter.api.Assertions.assertEquals(
                            0, com.legend.exec.SqlTypeCensus
                                    .bottomMultCount(),
                            "computed NULL under a required-multiplicity"
                                    + " label: "
                                    + com.legend.exec.SqlTypeCensus
                                            .summary()),
                    // G3 (§4bZ-V G3, 2026-08-26): the fixture-skew
                    // census PROMOTED to a pinned ceiling — 469
                    // measured, +4 from the two recorded undercount
                    // fixes (schema-qualified creates, constraint-word
                    // columns) = 473. Engine test-data debt
                    // (docs/UPSTREAM_DEFECTS.md U19); growth = a new
                    // contradicting fixture, shrink = upstream fixes —
                    // ratchet down.
                    () -> org.junit.jupiter.api.Assertions.assertTrue(
                            Runner.FIXTURE_SKEW.values().stream()
                                    .mapToLong(java.util.Set::size)
                                    .sum() <= 473,
                            "fixture-skew columns grew past the pinned"
                                    + " ceiling 473 — a new declaration-"
                                    + "contradicting CREATE in the setup"
                                    + " streams"),
                    // [1]-OVER-NULLABLE census (typed-IR queue item 2,
                    // 2026-08-26): 520 measured (487 direct + 33
                    // join-terminal) class-mapped required properties
                    // over columns the store leaves nullable — the
                    // engine-fixture model debt the future dialect-split
                    // warning will name, and the static slice of the
                    // 925 wire-breach census. Ceiling, down-only.
                    () -> org.junit.jupiter.api.Assertions.assertTrue(
                            reqNullAdjudicated() <= 520,
                            "required-over-nullable pairings grew past"
                                    + " the pinned ceiling 520 — a new"
                                    + " [1]-property over a nullable"
                                    + " column entered the corpus"
                                    + " models"),
                    // the census's own blindness must not grow: honesty
                    // buckets (unresolved property/column lookups) pin
                    // at 97 (55 column + 42 property, association-end
                    // injections and scope-block reads) — growth means
                    // the instrument stopped seeing pairings it used to
                    () -> org.junit.jupiter.api.Assertions.assertTrue(
                            Runner.REQUIRED_OVER_NULLABLE.entrySet()
                                    .stream()
                                    .filter(e -> e.getKey()
                                            .startsWith("unresolved-"))
                                    .mapToLong(e -> e.getValue().size())
                                    .sum() <= 97,
                            "required-over-nullable HONESTY buckets grew"
                                    + " past 97 — the census is going"
                                    + " blind on pairings it cannot"
                                    + " adjudicate"),
                    // E2E-AUDIT CONVERSE CENSUS → §E3 M-N3 TRIPWIRE
                    // (2026-08-27): labels adopt slot-truth nullability
                    // at construction (TypeFact.nullable through the
                    // fact funnel: DDL base frames + join-pad
                    // provenance + probed composition rules + GROUP-BY
                    // refinement), so a wire NULL under a
                    // nullable=false label is a COMPILER BUG — the
                    // fact's never-null proof was false. Burn-down:
                    // 925 measured (E2E audit) -> 841 (M-N2 pad
                    // weakening) -> 0 (the flip; the 605 tightened
                    // over-declared labels produced ZERO breaches —
                    // the DDL proofs held). EQUALITY at zero, always
                    // loud, witnesses in the failure.
                    () -> org.junit.jupiter.api.Assertions.assertEquals(
                            0, com.legend.exec.SqlTypeCensus
                                    .nullBreachCount(),
                            "wire NULL under a never-null label (a fact"
                                    + " proof was false): "
                                    + com.legend.exec.SqlTypeCensus
                                            .summary()),
                    // §E3 M-N3: the fact-vs-label differential is a
                    // CONSTRUCTION INVARIANT post-flip (reconciled
                    // labels ARE slotNullable) — a nonzero row means a
                    // frame door bypassed reconciliation or a rebuild
                    // dropped adopted labels. EQUALITY at zero.
                    () -> org.junit.jupiter.api.Assertions.assertEquals(
                            0L, com.legend.exec.SqlTypeCensus
                                    .nullableUnderDeclaredCount()
                                    + com.legend.exec.SqlTypeCensus
                                            .nullableOverDeclaredCount(),
                            "label nullability diverged from slot truth: "
                                    + com.legend.exec.SqlTypeCensus
                                            .nullableDifferentialSummary()),
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
                                            .summary()
                                    + " witnesses="
                                    + com.legend.exec.CanonicalDivergence
                                            .sqlDisagreeSamples()));
            System.out.println("[rcorpus] soft ceilings: sqldiff " + softDiff
                    + "/258, adv " + softAdv + "/304, 0-asserts " + softZero
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

    /** ADJUDICATED [1]-over-nullable pairings (the two real buckets;
     * honesty buckets excluded). */
    private static long reqNullAdjudicated() {
        return Runner.REQUIRED_OVER_NULLABLE.entrySet().stream()
                .filter(e -> e.getKey().equals("direct")
                        || e.getKey().equals("join-terminal"))
                .mapToLong(e -> e.getValue().size()).sum();
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
