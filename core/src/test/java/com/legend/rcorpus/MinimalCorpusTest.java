// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend.rcorpus;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * The minimal harness's run (docs/HARNESS_FROM_SCRATCH_AUDIT_2026_09_06.md):
 * every runnable corpus test through {@link MinimalCorpus}, two rosters
 * written ({@code target/corpus2-pass.txt}, {@code target/corpus2-fail.txt}
 * with the reason), one summary line. Scope with {@code -Drcorpus.test=
 * <substring>}. ACCEPTANCE of the rebuild = the pass roster equals the
 * old runner's platform-scored roster; the pin arrives at cutover.
 */
@Tag("heavy")
class MinimalCorpusTest {

    /** The H2 portability lane's floor (batch 115, 2026-09-06): 1866 = the
     * old runner's PLATFORM-scored H2 roster at cutover (1855, from its
     * flip file — its reported 1976 counted 121 walk answers on top) + the
     * three walk-only trivial passes + the batch-113/114 platform gains;
     * set difference against the old platform roster empty. Shrink-only. */
    private static final int H2_FLOOR = 1866;

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
        // the slowest tests (wall time includes the package session's setups
        // when this test opened it) — the timing ledger a slow run reads
        elapsed.entrySet().stream()
                .sorted((a, b) -> Long.compare(b.getValue(), a.getValue()))
                .limit(15)
                .forEach(e -> System.out.println("[corpus2] slow " + e.getValue() + "ms " + e.getKey()));
        if (only.isEmpty()) {
            // the ONE pin: the pass roster never shrinks (2454 at batch 114,
            // 2026-09-06 = the old runner's platform-scored 2451 + the
            // assert-free twin and the two vacuous placeholders it walked)
            // per lane: DuckDB (gate 4) and the H2 portability lane (gate 5,
            // -Drcorpus.backend=h2) each keep their own floor
            int floor = MinimalCorpus.H2_BACKEND ? H2_FLOOR : 2454;
            org.junit.jupiter.api.Assertions.assertTrue(pass.size() >= floor,
                    "corpus pass roster shrank: " + pass.size() + " < " + floor);
        }
    }
}
