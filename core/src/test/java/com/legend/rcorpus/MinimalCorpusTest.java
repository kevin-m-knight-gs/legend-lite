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

    /** The denominator per lane (the ceiling's other half: a pass-count
     * jump is either a GAINED name or a bigger corpus, and both must be
     * explained). 2575 = 2721 declared − 146 excluded by the engine's own
     * stereotypes (audit §9); re-derived against a corpus scan in Phase
     * 0.8. */
    private static final int DISCOVERED = 2575;

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
                    r = new MinimalCorpus.Result(t.fqn(), false, 0,
                            "harness: " + e.getClass().getSimpleName() + ": "
                                    + String.valueOf(e.getMessage()).split("\n")[0]);
                }
                ran.add(r.fqn());
                (r.pass() ? pass : fail).add(r.fqn() + (r.pass() ? "" : " :: " + r.reason()));
                elapsed.put(r.fqn(), (System.nanoTime() - tStart) / 1_000_000L);
            }
        } finally {
            corpus.endSession();
        }
        Files.createDirectories(Path.of("target"));
        Files.write(Path.of("target/corpus2-pass.txt"), pass);
        Files.write(Path.of("target/corpus2-fail.txt"), fail);
        System.out.println("[corpus2] pass=" + pass.size() + " fail=" + fail.size()
                + " of " + (pass.size() + fail.size()) + " in "
                + (System.nanoTime() - t0) / 1_000_000_000L + "s");
        for (String f : fail) {
            System.out.println("[corpus2] FAIL " + f);
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
        pinRoster(only, ran, fail);
    }

    /** The pin: fail names == the committed roster (restricted to the tests
     * that ran when scoped); the denominator when not scoped. */
    private static void pinRoster(String only, List<String> ran, List<String> fail)
            throws IOException {
        String lane = MinimalCorpus.H2_BACKEND ? "h2" : "duckdb";
        List<String> roster = readRoster(MinimalCorpus.H2_BACKEND ? H2_ROSTER : DUCKDB_ROSTER);
        Set<String> failNames = new LinkedHashSet<>();
        for (String f : fail) {
            failNames.add(f.substring(0, f.indexOf(" :: ")));
        }
        Set<String> rosterNames = new HashSet<>(roster);
        Set<String> ranNames = new HashSet<>(ran);
        if (only.isEmpty()) {
            org.junit.jupiter.api.Assertions.assertEquals(DISCOVERED, ran.size(),
                    "[" + lane + "] the corpus denominator moved (" + ran.size()
                    + " tests ran, " + DISCOVERED + " pinned): a bigger or smaller"
                    + " corpus must be explained, never absorbed");
        } else {
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
            StringBuilder sb = new StringBuilder("[" + lane + "] fail roster != "
                    + "committed roster (" + (only.isEmpty() ? "full run" : "scoped to '" + only + "'")
                    + "): LOST " + lost.size() + " (failing now, not in the roster)"
                    + ", GAINED " + gained.size() + " (in the roster, passing now)."
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
                + " fail of " + ran.size() + (only.isEmpty() ? "" : " (scoped)")
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
