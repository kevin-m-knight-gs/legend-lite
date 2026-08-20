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
 * CHANNEL B over the Relation suite (One-Platform Plan Phase 4): the
 * multi-root runner over legend-engine's {@code core_functions_relation} tree (which
 * imports the legend-pure platform), diffed against channel A's ledger
 * and the engine's relational-DuckDB manifest (the frontier oracle).
 */
class ChannelBRelationTest {

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
                "legend-engine-core/legend-engine-core-pure/legend-engine-pure-code-functions-relation/legend-engine-pure-functions-relation-pure"
                        + "/src/main/resources/core_functions_relation");
        List<ChannelB.Outcome> out = ChannelB.run(
                List.of(platform, scope), List.of(scope),
                new java.util.ArrayList<>());
        Map<ChannelB.Status, Integer> census =
                new EnumMap<>(ChannelB.Status.class);
        for (ChannelB.Outcome o : out) {
            census.merge(o.status(), 1, Integer::sum);
            System.out.println("[chB-Relation] " + o.status() + " " + o.testFqn()
                    + (o.detail().isEmpty() ? "" : " :: " + o.detail()));
        }
        System.out.println("[chB-Relation] census=" + census
                + " total=" + out.size());
        ChannelBDiff.Counts c = ChannelBDiff.report("chB-Relation", out,
                Path.of("src/test/java/org/finos/legend/lite/pct/"
                        + "Test_LegendLite_RelationFunctions_PCT.java"),
                Path.of("src/test/resources/oracle/"
                        + "RelationFunctions_manifest.duckdb.json"));
        // measured 2026-08-19 at the relation-scope landing (the
        // let-indirection adapter arm + the assertTdsEquivalent GRID
        // VERDICT [Clause 2c's chartered GridCompare route, 79-row
        // witness] + Variant toString-as-JSON-text). The 51 DECLINED are
        // deeper non-identity adapter shapes; the TRUE tail (33, pinned
        // SHRINK-ONLY) is the recorded burn queue — window semantics,
        // pivot column orders, chunk, temporal precision.
        assertTrue(out.size() == 287,
                "relation discovery moved: " + out.size() + " != 287");
        assertTrue(c.pass() >= 285, "relation PASS fell: " + c.pass());
        // 33→28 (slice 1: singleton extremes, carrier norm, chunk)
        // →24 (slice 4: CANONICAL variant text — to_json over the
        // JSON-cast value, compact with leaf quoting preserved)
        assertTrue(c.wireBug() <= 2,
                "relation WIRE-BUG census grew: " + c.wireBug());
        assertTrue(c.trueWireBug() <= 2,
                "relation TRUE wire-bug census grew: " + c.trueWireBug());
    }
}
