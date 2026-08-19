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
        // RE-MEASURED 2026-08-19 after the let-indirection adapter arm
        // un-declined the 64 window/non-identity rows (declines hide,
        // measurements name): PASS 133->180, and the previously hidden
        // tail is now the RECORDED burn queue — window frame semantics,
        // temporal-precision equality, columns(), chunk, INTEGER[]->
        // DOUBLE casts. TRUE pinned SHRINK-ONLY at measured (16); the
        // essential suite walked this exact arc to zero over five
        // slices.
        assertTrue(out.size() == 204,
                "standard discovery moved: " + out.size() + " != 204");
        assertTrue(c.pass() >= 199, "standard PASS fell: " + c.pass());
        // 24→20 (slice 1) →17 (slice 2) →5 (slice 3: the DECIMAL-wire
        // float spelling — trailing zeros were masking 12 CORRECT
        // window tests). Remaining: date min/max extend, dayOfWeek,
        // columns() ×2.
        assertTrue(c.wireBug() <= 5,
                "standard WIRE-BUG census grew: " + c.wireBug());
        assertTrue(c.trueWireBug() <= 4,
                "standard TRUE wire-bug census grew: " + c.trueWireBug());
    }
}
