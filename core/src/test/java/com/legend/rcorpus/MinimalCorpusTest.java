// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend.rcorpus;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The minimal harness's run (docs/HARNESS_FROM_SCRATCH_AUDIT_2026_09_06.md):
 * every runnable corpus test through {@link MinimalCorpus}, two rosters
 * written ({@code target/corpus2-pass.txt}, {@code target/corpus2-fail.txt}
 * with the reason), one summary line. Scope with {@code -Drcorpus.test=
 * <substring>}.
 *
 * <p>THE PIN (Phase 0.1, 2026-09-08 — docs/END_TO_END_PLAN_2026_09_08.md
 * "THE ORDER (v2)"): the FAIL roster is a SET of test names per lane,
 * equal to a committed file ({@code rcorpus/duckdb-fail-roster.txt},
 * {@code rcorpus/h2-fail-roster.txt}); the denominator is pinned per lane.
 * A set is both floor and ceiling: a test that starts failing is LOST, a
 * test that starts passing is GAINED, and either fails the gate until the
 * roster file is changed with a written reason (docs/GATES.md). A count
 * (the pin until batch 125, {@code pass.size() >= floor}) let a red flip
 * hide behind a green one and let manufactured passes through unseen
 * (docs/HARNESS_AUDIT_2026_09_07.md §4.2). Under {@code -Drcorpus.test}
 * the same pin holds on the scoped subset: the scoped fails equal the
 * roster restricted to the tests that ran, and the scope must select at
 * least one test (a typo never reads green). Set difference is by NAME;
 * messages are printed, never compared.
 */
@Tag("heavy")
class MinimalCorpusTest {

    /** The roster files: one test FQN per line, sorted, no messages. */
    private static final String DUCKDB_ROSTER = "/rcorpus/duckdb-fail-roster.txt";
    private static final String H2_ROSTER = "/rcorpus/h2-fail-roster.txt";
    /** The SKIPPED rosters (Phase 0.3): tests whose program reaches no
     * verdict function and that adjudicated none — never a pass. */
    private static final String DUCKDB_SKIPPED = "/rcorpus/duckdb-skipped-roster.txt";
    private static final String H2_SKIPPED = "/rcorpus/h2-skipped-roster.txt";
    /** The ORDER-LENIENCY registers (Phase 0.5): "ordered-keys-unmappable
     * <test>" per ORDERED row verdict that fell back to the multiset compare
     * because its sort keys were not derivable or not carried by the compared
     * output — 0 firings or every one registered (the arrival-order class,
     * unordered-leniency, is run-dependent and has a ceiling instead). */
    private static final String DUCKDB_ORD = "/rcorpus/duckdb-ord-register.txt";
    private static final String H2_ORD = "/rcorpus/h2-ord-register.txt";

    /** The denominator per lane (the ceiling's other half: a pass-count
     * jump is either a GAINED name or a bigger corpus, and both must be
     * explained). 2575 = 2721 declared − 146 excluded by the engine's own
     * stereotypes (audit §9); re-derived against a corpus scan in Phase
     * 0.8. */
    private static final int DISCOVERED = 2575;

    /** Setups the platform derives as INERT on the full run (Phase 0.2;
     * measured 2026-09-08, the names print as {@code [corpus2] inert-setup}):
     * the five are zero-arg functions of the shared fixture that are not
     * setups at all (testRuntime, testRuntimeForBQ,
     * createTestDatabaseConnection, the two typeInference maps) — the
     * arity rule in {@code MinimalCorpus.sharedSetups} nominates them; the
     * platform's effect analysis is what keeps them from running. */
    private static final int INERT_SETUPS = 5;

    @Test
    void corpus() throws Exception {
        Assumptions.assumeTrue(Corpus.available(), "legend-engine checkout not present");
        // the engine's scan order for the corpus goldens (the old runner's
        // setting); restored on exit so no later test in the JVM sees it
        String scanOrder = System.getProperty("legend.exec.engineScanOrder");
        System.setProperty("legend.exec.engineScanOrder", "true");
        try {
            run();
        } finally {
            if (scanOrder == null) {
                System.clearProperty("legend.exec.engineScanOrder");
            } else {
                System.setProperty("legend.exec.engineScanOrder", scanOrder);
            }
        }
    }

    private static void run() throws Exception {
        String only = System.getProperty("rcorpus.test", "").trim();
        MinimalCorpus corpus = new MinimalCorpus();
        for (String w : corpus.libraryWalls()) {
            System.out.println("[corpus2] library skipped: " + w);
        }
        List<String> pass = new ArrayList<>();
        List<String> fail = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        /** the strength census of the passes (Phase 0.7) */
        java.util.Map<String, Integer> strength = new java.util.LinkedHashMap<>();
        /** every test that RAN, in discovery order, pass or fail */
        List<String> ran = new ArrayList<>();
        java.util.Map<String, Long> elapsed = new java.util.LinkedHashMap<>();
        long t0 = System.nanoTime();
        try {
            for (MinimalCorpus.TestCase t : corpus.tests()) {
                if (!only.isEmpty() && !t.fqn().contains(only)) {
                    continue;
                }
                MinimalCorpus.Result r;
                long tStart = System.nanoTime();
                try {
                    r = corpus.run(t);
                } catch (Exception e) {
                    r = new MinimalCorpus.Result(t.fqn(), MinimalCorpus.Status.FAIL, 0,
                            "harness: " + e.getClass().getSimpleName() + ": "
                                    + MinimalCorpus.whole(e.getMessage()));
                }
                ran.add(r.fqn());
                if (r.status() == MinimalCorpus.Status.PASS) {
                    strength.merge(r.strength().name()
                            + (r.strength() == MinimalCorpus.Strength.DIFFERENTIAL
                                    ? (r.literalToo() ? "+literal" : "-only") : ""), 1, Integer::sum);
                }
                switch (r.status()) {
                    case PASS -> pass.add(r.fqn() + " :: " + r.reason());
                    case FAIL -> fail.add(r.fqn() + " :: " + r.reason());
                    case SKIPPED -> skipped.add(r.fqn() + " :: " + r.reason());
                }
                elapsed.put(r.fqn(), (System.nanoTime() - tStart) / 1_000_000L);
            }
        } finally {
            corpus.endSession();
        }
        Files.createDirectories(Path.of("target"));
        Files.write(Path.of("target/corpus2-pass.txt"), pass);
        Files.write(Path.of("target/corpus2-fail.txt"), fail);
        Files.write(Path.of("target/corpus2-skipped.txt"), skipped);
        System.out.println("[corpus2] pass=" + pass.size() + " fail=" + fail.size()
                + " skipped=" + skipped.size() + " of " + ran.size() + " in "
                + (System.nanoTime() - t0) / 1_000_000_000L + "s");
        for (String f : fail) {
            System.out.println("[corpus2] FAIL " + f);
        }
        for (String k : skipped) {
            System.out.println("[corpus2] SKIP " + k);
        }
        // the referee's own roster: row verdicts by kind and the decline
        // buckets — DISPLAYED, no verdict flows through it
        java.util.Map<String, Long> kinds = new java.util.TreeMap<>();
        com.legend.harness.H2Verify.VERDICT_ROSTER.forEach((k, v) ->
                kinds.merge(k.substring(0, k.indexOf(' ')), v.sum(), Long::sum));
        kinds.forEach((k, v) -> System.out.println("[corpus2] referee " + k + "=" + v));
        new java.util.TreeMap<>(com.legend.harness.ReplayOracle.OUTCOMES).forEach((k, v) ->
                System.out.println("[corpus2] referee-outcome " + k + "=" + v.sum()));
        new java.util.TreeMap<>(com.legend.harness.H2Verify.UNVERIFIABLE_CENSUS).forEach((k, v) ->
                System.out.println("[corpus2] referee-declined " + v.sum() + "x " + k));
        // the slowest tests (wall time includes the package session's setups
        // when this test opened it) — the timing ledger a slow run reads
        elapsed.entrySet().stream()
                .sorted((a, b) -> Long.compare(b.getValue(), a.getValue()))
                .limit(15)
                .forEach(e -> System.out.println("[corpus2] slow " + e.getValue() + "ms " + e.getKey()));
        // setups the platform derived as inert (never ran): named, and
        // pinned exactly on the full run — Phase 0.2
        for (String s : corpus.inertSetups()) {
            System.out.println("[corpus2] inert-setup " + s);
        }
        System.out.println("[corpus2] inert-setups=" + corpus.inertSetups().size());
        if (only.isEmpty()) {
            org.junit.jupiter.api.Assertions.assertEquals(INERT_SETUPS, corpus.inertSetups().size(),
                    "inert setups (the platform says the body has no effects) moved:"
                    + " a seeding setup read as inert unseeds its package silently;"
                    + " explain, then re-pin. Names: " + corpus.inertSetups());
        }
        pinRoster(only, ran, fail, "fail",
                MinimalCorpus.H2_BACKEND ? H2_ROSTER : DUCKDB_ROSTER, true);
        pinRoster(only, ran, skipped, "skipped",
                MinimalCorpus.H2_BACKEND ? H2_SKIPPED : DUCKDB_SKIPPED, false);
        // the referee's ORDER-LENIENCY census (Phase 0.5): every row verdict
        // that held only as a multiset. Two tags, two pins: an ORDERED chain
        // whose sort keys the compared output could not carry is a
        // compile-time fact — pinned as an exact set; an UNORDERED chain
        // whose two sides arrived in different orders is arrival order —
        // run-dependent (three DuckDB union/concatenate tests flapped
        // between two runs of batch 130), so its COUNT has a ceiling
        List<String> unmappable = new ArrayList<>();
        long unordered = 0;
        for (var e : com.legend.harness.H2Verify.ORD_CENSUS.entrySet()) {
            System.out.println("[corpus2] ord " + e.getKey() + " x" + e.getValue().sum());
            if (e.getKey().startsWith(ORD_UNMAPPABLE + " ")) {
                unmappable.add(e.getKey() + " :: x" + e.getValue().sum());
            } else {
                unordered++;      // TESTS, not firings: a test with several verdicts fires per verdict
            }
        }
        System.out.println("[corpus2] ord-unordered-leniency=" + unordered);
        if (only.isEmpty()) {
            long ceiling = MinimalCorpus.H2_BACKEND ? H2_ORD_UNORDERED : DUCKDB_ORD_UNORDERED;
            org.junit.jupiter.api.Assertions.assertTrue(unordered <= ceiling,
                    "unordered-leniency passes (row verdicts held only as multisets"
                    + " over an unordered chain) grew past the ceiling: " + unordered
                    + " > " + ceiling + " — explain, then re-pin");
        }
        List<String> ranTagged = new ArrayList<>();
        for (String t : ran) {
            ranTagged.add(ORD_UNMAPPABLE + " " + t);
        }
        pinRoster(only, ranTagged, unmappable, "ord",
                MinimalCorpus.H2_BACKEND ? H2_ORD : DUCKDB_ORD, false);
        pinChannels(only, corpus);
        pinStrength(only, strength);
    }

    /** Phase 0.7 — the STRENGTH census of the passes (audit §3's ladder),
     * derived from listener events; pinned MONOTONE per lane: the
     * differential count may only grow, the spelling-only and
     * cardinality-only counts may only shrink. */
    private static void pinStrength(String only, java.util.Map<String, Integer> strength) {
        strength.forEach((k, v) -> System.out.println("[corpus2] strength " + k + "=" + v));
        if (!only.isEmpty()) {
            return;
        }
        int differential = strength.getOrDefault("DIFFERENTIAL+literal", 0)
                + strength.getOrDefault("DIFFERENTIAL-only", 0);
        int spelling = strength.getOrDefault("SPELLING", 0);
        int weak = strength.getOrDefault("CARDINALITY", 0);
        int[] floor = MinimalCorpus.H2_BACKEND ? H2_STRENGTH : DUCKDB_STRENGTH;
        org.junit.jupiter.api.Assertions.assertTrue(differential >= floor[0],
                "differential passes (a referee row verdict matched) SHRANK: " + differential
                + " < " + floor[0] + " — a rows leg stopped being judged; explain or fix");
        org.junit.jupiter.api.Assertions.assertTrue(spelling <= floor[1],
                "spelling-only passes (every verdict decided by text) GREW: " + spelling
                + " > " + floor[1]);
        org.junit.jupiter.api.Assertions.assertTrue(weak <= floor[2],
                "cardinality-only passes GREW: " + weak + " > " + floor[2]);
    }

    /** {differential floor, spelling ceiling, cardinality ceiling} per lane
     * (Phase 0.7; measured 2026-09-08, batch 133). */
    private static final int[] DUCKDB_STRENGTH = {1512, 49, 22};
    private static final int[] H2_STRENGTH = {1198, 56, 18};

    /** Phase 0.6 — the verdict CHANNELS the platform and the referee
     * reported: text-decided verdicts by the arm's reason (ceilings per
     * reason), referee FAULTS (pinned at ZERO — a fault of our own machinery
     * never stands in for a verdict and never hides in a decline count),
     * and the referee's leniencies (ceilings). Counts are TESTS, not
     * firings, where a test may fire several times. */
    private static void pinChannels(String only, MinimalCorpus corpus) {
        java.util.Map<String, Integer> byReason = new java.util.LinkedHashMap<>();
        for (String k : corpus.textDecided().keySet()) {
            String reason = k.substring(0, k.indexOf(' '));
            byReason.merge(reason, 1, Integer::sum);
            System.out.println("[corpus2] text-decided " + k);
        }
        byReason.forEach((r, n) -> System.out.println("[corpus2] text-decided-tests " + r + "=" + n));
        long faults = 0;
        for (var e : com.legend.harness.H2Verify.UNVERIFIABLE_CENSUS.entrySet()) {
            if (e.getKey().startsWith("FAULT ")) {
                faults += e.getValue().sum();
            }
        }
        System.out.println("[corpus2] referee-faults=" + faults);
        java.util.Map<String, Integer> lenTests = new java.util.LinkedHashMap<>();
        for (String k : com.legend.harness.H2Verify.LENIENCY_CENSUS.keySet()) {
            lenTests.merge(k.substring(0, k.indexOf(' ')), 1, Integer::sum);
        }
        for (var kind : List.of("golden-fanout-collapsed", "golden-stitch-keys-dropped")) {
            int n = (int) com.legend.harness.H2Verify.VERDICT_ROSTER.keySet().stream()
                    .filter(k -> k.startsWith(kind + " ")).count();
            if (n > 0) {
                lenTests.put(kind, n);
            }
        }
        lenTests.forEach((t, n) -> System.out.println("[corpus2] leniency-tests " + t + "=" + n));
        if (!only.isEmpty()) {
            return;
        }
        org.junit.jupiter.api.Assertions.assertEquals(0, faults,
                "referee FAULTS (our own machinery failed — a seed would not replay,"
                + " an extension function we ship is missing, the session failed) must"
                + " be ZERO: " + com.legend.harness.H2Verify.UNVERIFIABLE_CENSUS.keySet()
                        .stream().filter(k -> k.startsWith("FAULT ")).toList());
        java.util.Map<String, Integer> ceilings = MinimalCorpus.H2_BACKEND
                ? H2_TEXT_DECIDED : DUCKDB_TEXT_DECIDED;
        List<String> over = new ArrayList<>();
        byReason.forEach((r, n) -> {
            if (n > ceilings.getOrDefault(r, 0)) {
                over.add(r + "=" + n + " > " + ceilings.getOrDefault(r, 0));
            }
        });
        org.junit.jupiter.api.Assertions.assertTrue(over.isEmpty(),
                "text-decided verdicts grew past their ceilings (a rows leg stopped"
                + " being judged): " + over + " — explain, then re-pin");
        java.util.Map<String, Integer> lenCeil = MinimalCorpus.H2_BACKEND
                ? H2_LENIENCY : DUCKDB_LENIENCY;
        List<String> overLen = new ArrayList<>();
        lenTests.forEach((t, n) -> {
            if (n > lenCeil.getOrDefault(t, 0)) {
                overLen.add(t + "=" + n + " > " + lenCeil.getOrDefault(t, 0));
            }
        });
        org.junit.jupiter.api.Assertions.assertTrue(overLen.isEmpty(),
                "referee leniencies grew past their ceilings: " + overLen
                + " — explain, then re-pin");
    }

    /** Ceilings on TESTS with a text-decided verdict, per reason (Phase 0.6;
     * measured 2026-09-08, batch 132). */
    private static final java.util.Map<String, Integer> DUCKDB_TEXT_DECIDED = java.util.Map.of(
            "rows-underivable", 29, "plan-params-unbindable", 6, "oracle-declined", 22,
            "foreign-dialect:DB2", 30, "foreign-dialect:Composite", 7);
    private static final java.util.Map<String, Integer> H2_TEXT_DECIDED = java.util.Map.of(
            "rows-underivable", 38, "plan-params-unbindable", 6, "oracle-declined", 28,
            "foreign-dialect:DB2", 30, "foreign-dialect:Composite", 7);
    /** Ceilings on TESTS with a referee leniency, per tag (Phase 0.6). */
    private static final java.util.Map<String, Integer> DUCKDB_LENIENCY = java.util.Map.of(
            "float-10-digits", 48, "micro-floor", 7,
            "golden-fanout-collapsed", 1, "golden-stitch-keys-dropped", 8);
    private static final java.util.Map<String, Integer> H2_LENIENCY = java.util.Map.of(
            "float-10-digits", 32, "micro-floor", 7,
            "golden-fanout-collapsed", 1, "golden-stitch-keys-dropped", 8);

    private static final String ORD_UNMAPPABLE = "ordered-keys-unmappable";
    /** Ceilings on the number of TESTS with an arrival-order leniency pass
     * per lane (batch 130, measured over three runs: DuckDB 108 / 105 / 106
     * — three union/concatenate tests flap; H2 11). */
    private static final long DUCKDB_ORD_UNORDERED = 108;
    private static final long H2_ORD_UNORDERED = 11;

    /** The pin: the {@code kind} names == the committed roster (restricted
     * to the tests that ran when scoped); the denominator when not scoped. */
    private static void pinRoster(String only, List<String> ran, List<String> rows,
            String kind, String resource, boolean denominator) throws IOException {
        String lane = MinimalCorpus.H2_BACKEND ? "h2" : "duckdb";
        List<String> roster = readRoster(resource);
        Set<String> failNames = new LinkedHashSet<>();
        for (String f : rows) {
            failNames.add(f.substring(0, f.indexOf(" :: ")));
        }
        Set<String> rosterNames = new HashSet<>(roster);
        Set<String> ranNames = new HashSet<>(ran);
        if (only.isEmpty() && denominator) {
            org.junit.jupiter.api.Assertions.assertEquals(DISCOVERED, ran.size(),
                    "[" + lane + "] the corpus denominator moved (" + ran.size()
                    + " tests ran, " + DISCOVERED + " pinned): a bigger or smaller"
                    + " corpus must be explained, never absorbed");
        } else if (denominator) {
            org.junit.jupiter.api.Assertions.assertFalse(ran.isEmpty(),
                    "[" + lane + "] -Drcorpus.test=" + only + " selected no test");
        }
        // LOST: failing now, not in the roster. GAINED: in the roster (and
        // ran), passing now. Both in discovery/roster order — no sort site.
        List<String> lost = new ArrayList<>();
        for (String f : failNames) {
            if (!rosterNames.contains(f)) {
                lost.add(f);
            }
        }
        List<String> gained = new ArrayList<>();
        for (String r : roster) {
            if (ranNames.contains(r) && !failNames.contains(r)) {
                gained.add(r);
            }
        }
        if (!lost.isEmpty() || !gained.isEmpty()) {
            StringBuilder sb = new StringBuilder("[" + lane + "] " + kind + " roster != "
                    + "committed roster (" + (only.isEmpty() ? "full run" : "scoped to '" + only + "'")
                    + "): LOST " + lost.size() + " (" + kind + " now, not in the roster)"
                    + ", GAINED " + gained.size() + " (in the roster, not " + kind + " now)."
                    + " Every change to the roster file carries a written reason"
                    + " in docs/GATES.md.");
            for (String l : lost) {
                sb.append("\n  LOST   ").append(l);
            }
            for (String g : gained) {
                sb.append("\n  GAINED ").append(g);
            }
            org.junit.jupiter.api.Assertions.fail(sb.toString());
        }
        System.out.println("[corpus2] roster " + lane + " EXACT: " + failNames.size()
                + " " + kind + " of " + ran.size() + (only.isEmpty() ? "" : " (scoped)")
                // USER DECISION 2026-09-08: the H2 lane is KEPT as a
                // PORTABILITY check — its golden runs on the same connection
                // as our query, so it is not an independent oracle; the
                // DuckDB lane with the H2 mirror is
                + (MinimalCorpus.H2_BACKEND ? " oracle=same-session" : " oracle=h2-mirror"));
    }

    private static List<String> readRoster(String resource) throws IOException {
        try (InputStream in = MinimalCorpusTest.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("roster file missing on the classpath: " + resource);
            }
            List<String> out = new ArrayList<>();
            for (String line : new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\n")) {
                String s = line.trim();
                if (!s.isEmpty()) {
                    if (!out.isEmpty() && s.compareTo(out.get(out.size() - 1)) <= 0) {
                        throw new IllegalStateException("roster " + resource
                                + " is not sorted-unique at: " + s);
                    }
                    out.add(s);
                }
            }
            return out;
        }
    }
}
