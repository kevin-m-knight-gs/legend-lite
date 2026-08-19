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
 * CHANNEL B over the STANDARD suite (One-Platform Plan Phase 4, third
 * scope — the FIRST multi-root scope): the standard functions live in
 * legend-ENGINE ({@code core_functions_standard}), importing the
 * legend-pure platform tree — {@code ChannelB.run}'s multi-root form
 * compiles the union. Diffed against channel A's ledger and the
 * engine's relational-DuckDB manifest (the frontier oracle).
 */
class ChannelBStandardTest {

    private static Path pureRoot() {
        return Path.of(System.getProperty("legend.pure.root",
                System.getProperty("user.home") + "/legend/legend-pure"));
    }

    private static Path engineRoot() {
        return Path.of(System.getProperty("legend.engine.root",
                System.getProperty("user.home") + "/legend/legend-engine"));
    }

    @Test
    void standardCensus() throws Exception {
        Path platform = pureRoot().resolve(
                "legend-pure-core/legend-pure-m3-core/src/main/resources"
                        + "/platform/pure");
        Path standard = engineRoot().resolve(
                "legend-engine-core/legend-engine-core-pure"
                        + "/legend-engine-pure-code-functions-standard"
                        + "/legend-engine-pure-functions-standard-pure"
                        + "/src/main/resources/core_functions_standard");
        List<ChannelB.Outcome> out = ChannelB.run(
                List.of(platform, standard), List.of(standard),
                new java.util.ArrayList<>());
        Map<ChannelB.Status, Integer> census =
                new EnumMap<>(ChannelB.Status.class);
        for (ChannelB.Outcome o : out) {
            census.merge(o.status(), 1, Integer::sum);
            System.out.println("[chB-std] " + o.status() + " " + o.testFqn()
                    + (o.detail().isEmpty() ? "" : " :: " + o.detail()));
        }
        System.out.println("[chB-std] census=" + census
                + " total=" + out.size());
        ChannelBDiff.Counts c = ChannelBDiff.report("chB-std", out,
                Path.of("src/test/java/org/finos/legend/lite/pct/"
                        + "Test_LegendLite_StandardFunctions_PCT.java"),
                Path.of("src/test/resources/oracle/"
                        + "StandardFunctions_manifest.duckdb.json"));
        // measured 2026-08-19 after the standard-scope burn (all FIVE
        // true wire bugs died as platform fixes: the PCT.function
        // suppression rule [native is the definition], the assertError
        // deferred-guard catch [timeBucket], the assertInstanceOf
        // platform native + K-arm). Discovery exact: the tree carries
        // 205 PCT.test spellings, ONE commented out in the engine's own
        // source (testBetween_Empty). The 64 DECLINED are the window/
        // non-identity adapter shapes — the named future adapter arm.
        assertTrue(out.size() == 204,
                "standard discovery moved: " + out.size() + " != 204");
        assertTrue(c.pass() >= 133, "standard PASS fell: " + c.pass());
        assertTrue(c.wireBug() <= 7,
                "standard WIRE-BUG census grew: " + c.wireBug());
        assertTrue(c.trueWireBug() == 0,
                "a TRUE wire bug appeared (both oracles corroborate the"
                + " platform is wrong): " + c.trueWireBug());
    }
}
