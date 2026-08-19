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
        assertTrue(pass >= 189,
                "channel-B essential PASS fell below the pinned floor: "
                        + pass + " < 189");
    }
}
