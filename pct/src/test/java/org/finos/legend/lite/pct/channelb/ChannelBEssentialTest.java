// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package org.finos.legend.lite.pct.channelb;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.EnumMap;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CHANNEL B over the ESSENTIAL suite (One-Platform Plan Phase 4, first
 * milestone): the 327 PCT.test functions channel A runs, executed by
 * OUR platform alone. The census pins the measured baseline — PASS may
 * only GROW; total discovery is exact (channel A's own 327). The
 * FAIL/ERROR rows are the wire-bug census feeding the three-bucket
 * diff; every burn adjudicates rows, never loosens pins.
 */
class ChannelBEssentialTest {

    private static Path pureRoot() {
        return Path.of(System.getProperty("legend.pure.root",
                System.getProperty("user.home") + "/legend/legend-pure"));
    }

    @Test
    void essentialCensus() throws Exception {
        Path modelRoot = pureRoot().resolve(
                "legend-pure-core/legend-pure-m3-core/src/main/resources"
                        + "/platform/pure");
        Path scope = modelRoot.resolve("essential");
        java.util.List<String> walls = new java.util.ArrayList<>();
        List<ChannelB.Outcome> out = ChannelB.run(modelRoot, List.of(scope), walls);
        walls.forEach(w -> System.out.println("[chB-wall] " + w));
        System.out.println("[chB] walls=" + walls.size());
        Map<ChannelB.Status, Integer> census =
                new EnumMap<>(ChannelB.Status.class);
        for (ChannelB.Outcome o : out) {
            census.merge(o.status(), 1, Integer::sum);
            System.out.println("[chB] " + o.status() + " " + o.testFqn()
                    + (o.detail().isEmpty() ? "" : " :: " + o.detail()));
        }
        System.out.println("[chB] census=" + census + " total=" + out.size());
        // measured 2026-08-19 at the channel-B landing
        assertTrue(out.size() == 327,
                "essential discovery moved: " + out.size() + " != 327");
        int pass = census.getOrDefault(ChannelB.Status.PASS, 0);
        assertTrue(pass >= 286,
                "channel-B essential PASS fell below the pinned floor: "
                        + pass + " < 286");

        // THE THREE-BUCKET DIFF (plan addendum #6): channel A's outcome
        // per test is its suite ledger — the expectedFailures list IS
        // channel A's report. AGREE rows corroborate; WIRE-BUG rows
        // (A passes, B fails) are the census the phase exists to
        // produce; B-FIXES-A rows (A's reference-adapter limit, B
        // passes) are equally findings. Buckets are PINNED — movement
        // is adjudicated, never silent.
        java.util.Set<String> aFail = channelAExpectedFailures();
        int agreePass = 0;
        int agreeFail = 0;
        int wireBug = 0;
        int bFixesA = 0;
        int declined = 0;
        for (ChannelB.Outcome o : out) {
            boolean aFails = aFail.contains(o.testFqn());
            switch (o.status()) {
                case PASS -> {
                    if (aFails) {
                        bFixesA++;
                    } else {
                        agreePass++;
                    }
                }
                case DECLINED -> declined++;
                default -> {
                    if (aFails) {
                        agreeFail++;
                    } else {
                        wireBug++;
                    }
                }
            }
        }
        System.out.println("[chB] diff: AGREE-PASS=" + agreePass
                + " AGREE-FAIL=" + agreeFail + " WIRE-BUG=" + wireBug
                + " B-FIXES-A=" + bFixesA + " DECLINED=" + declined);

        // THE FRONTIER ORACLE (third channel): the engine's OWN
        // relational-DuckDB PCT manifest (snapshot from legend-engine
        // 943d38b3 / 2026-08-06 — the oracle-pin discipline) names the
        // tests the reference RELATIONAL executor itself cannot pass
        // (indexOf 0-vs-1-base, partial-precision dates, mixed-type Any
        // …). A wire-bug row the engine also excludes is the RELATIONAL
        // frontier, corroborated — not our bug; the TRUE wire-bug count
        // is what burns.
        java.util.Set<String> engineExcluded = engineDuckDbExclusions();
        int frontier = 0;
        int trueWireBug = 0;
        for (ChannelB.Outcome o : out) {
            if (o.status() == ChannelB.Status.PASS
                    || o.status() == ChannelB.Status.DECLINED
                    || aFail.contains(o.testFqn())) {
                continue;
            }
            if (engineExcluded.contains(o.testFqn())) {
                frontier++;
            } else {
                trueWireBug++;
                System.out.println("[chB] TRUE-WIRE-BUG " + o.testFqn());
            }
        }
        System.out.println("[chB] frontier: ENGINE-FRONTIER=" + frontier
                + " TRUE-WIRE-BUG=" + trueWireBug);
        // measured 2026-08-19, post empty-equality burn (AGREE-PASS=276
        // AGREE-FAIL=15 WIRE-BUG=24 B-FIXES-A=10 DECLINED=2): the
        // wire-bug census may only SHRINK; agreement may only GROW
        assertTrue(agreePass >= 276, "AGREE-PASS fell: " + agreePass);
        assertTrue(wireBug <= 24, "WIRE-BUG census grew: " + wireBug);
        // the sharpest number: rows OUR platform fails that BOTH
        // reference channels pass (measured 2026-08-19: testValues/
        // testKeys Map-get + testComplexPow decimal scale)
        assertTrue(trueWireBug <= 3,
                "TRUE wire-bug census grew: " + trueWireBug);
    }

    /** The engine's relational-DuckDB essential manifest exclusions
     * (pinned oracle snapshot; names carry the reference suffix —
     * stripped to plain FQNs). */
    private static java.util.Set<String> engineDuckDbExclusions()
            throws java.io.IOException {
        Path manifest = Path.of("src/test/resources/oracle/"
                + "EssentialFunctions_manifest.duckdb.json");
        java.util.Set<String> names = new java.util.HashSet<>();
        var m = java.util.regex.Pattern
                .compile("\"test\"\\s*:\\s*\"(meta::[a-zA-Z:_0-9]+?)_Function_")
                .matcher(java.nio.file.Files.readString(manifest));
        while (m.find()) {
            names.add(m.group(1));
        }
        if (names.isEmpty()) {
            throw new IllegalStateException(
                    "engine DuckDB manifest scan found nothing — the oracle"
                    + " snapshot moved");
        }
        return names;
    }

    /** Channel A's per-test outcome ledger — the suite's
     * expectedFailures list, read from its OWN source (names carry the
     * reference's {@code _Function_1__Boolean_1_} suffix; stripped to
     * match channel B's plain FQNs). */
    private static java.util.Set<String> channelAExpectedFailures()
            throws java.io.IOException {
        Path suite = Path.of("src/test/java/org/finos/legend/lite/pct/"
                + "Test_LegendLite_EssentialFunctions_PCT.java");
        java.util.Set<String> names = new java.util.HashSet<>();
        var m = java.util.regex.Pattern.compile("one\\(\"(meta::[a-zA-Z:_0-9]+)_Function")
                .matcher(java.nio.file.Files.readString(suite));
        while (m.find()) {
            names.add(m.group(1));
        }
        if (names.isEmpty()) {
            throw new IllegalStateException(
                    "channel A ledger scan found nothing — the suite moved");
        }
        return names;
    }
}
