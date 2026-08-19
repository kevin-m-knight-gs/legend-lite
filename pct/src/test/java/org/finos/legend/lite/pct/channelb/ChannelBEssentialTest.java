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
        assertTrue(pass >= 259,
                "channel-B essential PASS fell below the pinned floor: "
                        + pass + " < 259");

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
        // measured 2026-08-19, post-assertError burn (AGREE-PASS=252
        // AGREE-FAIL=18 WIRE-BUG=48 B-FIXES-A=7 DECLINED=2): the wire-bug
        // census may only SHRINK; agreement may only GROW
        assertTrue(agreePass >= 252, "AGREE-PASS fell: " + agreePass);
        assertTrue(wireBug <= 48, "WIRE-BUG census grew: " + wireBug);
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
