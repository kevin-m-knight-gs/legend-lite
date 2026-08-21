// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package org.finos.legend.lite.pct.channelb;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CHANNEL B over the Unclassified suite (One-Platform Plan Phase 4): the
 * multi-root runner over legend-engine's {@code core_functions_unclassified} tree (which
 * imports the legend-pure platform), diffed against channel A's ledger
 * and the engine's relational-DuckDB manifest (the frontier oracle).
 */
class ChannelBUnclassifiedTest {

    private static Path pureRoot() {
        return Path.of(System.getProperty("legend.pure.root",
                System.getProperty("user.home") + "/legend/legend-pure"));
    }

    private static Path engineRoot() {
        return Path.of(System.getProperty("legend.engine.root",
                System.getProperty("user.home") + "/legend/legend-engine"));
    }

    @Test
    void census() throws Exception {
        Path platform = pureRoot().resolve(
                "legend-pure-core/legend-pure-m3-core/src/main/resources"
                        + "/platform/pure");
        Path scope = engineRoot().resolve(
                "legend-engine-core/legend-engine-core-pure/legend-engine-pure-code-functions-unclassified/legend-engine-pure-functions-unclassified-pure"
                        + "/src/main/resources/core_functions_unclassified");
        java.util.List<String> walls = new java.util.ArrayList<>();
        List<ChannelB.Outcome> out = ChannelB.run(
                List.of(platform, scope), List.of(scope), walls);
        walls.forEach(w -> System.out.println("[chB-Unclassified-wall] " + w));
        System.out.println("[chB-Unclassified] walls=" + walls.size());
        // audit-of-audits #12: walls ASSERTED shrink-only (27 measured
        // 2026-08-21); growth silently shrinks the discovery universe
        assertTrue(walls.size() <= 27,
                "unclassified walls grew: " + walls.size() + " > 27");
        Map<ChannelB.Status, Integer> census =
                new EnumMap<>(ChannelB.Status.class);
        for (ChannelB.Outcome o : out) {
            census.merge(o.status(), 1, Integer::sum);
            System.out.println("[chB-Unclassified] " + o.status() + " " + o.testFqn()
                    + (o.detail().isEmpty() ? "" : " :: " + o.detail()));
        }
        System.out.println("[chB-Unclassified] census=" + census
                + " total=" + out.size());
        ChannelBDiff.Counts c = ChannelBDiff.report("chB-Unclassified", out,
                Path.of("src/test/java/org/finos/legend/lite/pct/"
                        + "Test_LegendLite_UnclassifiedFunctions_PCT.java"),
                Path.of("src/test/resources/oracle/"
                        + "UnclassifiedFunctions_manifest.duckdb.json"));
        // measured 2026-08-19: PERFECT out of the box — 95/95 PASS,
        // every row corroborated, zero declines, zero wire bugs.
        assertTrue(out.size() == 95,
                "unclassified discovery moved: " + out.size() + " != 95");
        assertTrue(c.pass() >= 95, "unclassified PASS fell: " + c.pass());
        assertTrue(c.trueWireBug() == 0,
                "a TRUE wire bug appeared: " + c.trueWireBug());
    }
}
