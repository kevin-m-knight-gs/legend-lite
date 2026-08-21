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
 * CHANNEL B over the GRAMMAR suite (One-Platform Plan Phase 4, second
 * scope — the same runner over {@code platform/pure/grammar}): the
 * PCT.test functions channel A's GrammarFunctions suite runs, executed
 * by OUR platform alone, diffed against channel A's ledger and the
 * engine's relational-DuckDB manifest (the frontier oracle).
 */
class ChannelBGrammarTest {

    private static Path pureRoot() {
        return Path.of(System.getProperty("legend.pure.root",
                System.getProperty("user.home") + "/legend/legend-pure"));
    }

    @Test
    void grammarCensus() throws Exception {
        Path modelRoot = pureRoot().resolve(
                "legend-pure-core/legend-pure-m3-core/src/main/resources"
                        + "/platform/pure");
        // channel A's ReportScope is /platform/pure/grammar/functions/ —
        // grammar's OTHER subtrees (tests/, m3.pure …) belong to no
        // adapter suite
        Path scope = modelRoot.resolve("grammar/functions");
        java.util.List<String> walls = new java.util.ArrayList<>();
        List<ChannelB.Outcome> out = ChannelB.run(modelRoot,
                List.of(scope), walls);
        walls.forEach(w -> System.out.println("[chB-gram-wall] " + w));
        System.out.println("[chB-gram] walls=" + walls.size());
        // audit-of-Blocker-3: the ONE suite #12 missed — walls ASSERTED
        // shrink-only like its four siblings (20 measured 2026-08-21)
        assertTrue(walls.size() <= 20,
                "grammar walls grew: " + walls.size() + " > 20");
        Map<ChannelB.Status, Integer> census =
                new EnumMap<>(ChannelB.Status.class);
        for (ChannelB.Outcome o : out) {
            census.merge(o.status(), 1, Integer::sum);
            System.out.println("[chB-gram] " + o.status() + " " + o.testFqn()
                    + (o.detail().isEmpty() ? "" : " :: " + o.detail()));
        }
        System.out.println("[chB-gram] census=" + census
                + " total=" + out.size());
        ChannelBDiff.Counts c = ChannelBDiff.report("chB-gram", out,
                Path.of("src/test/java/org/finos/legend/lite/pct/"
                        + "Test_LegendLite_GrammarFunctions_PCT.java"),
                Path.of("src/test/resources/oracle/"
                        + "GrammarFunctions_manifest.duckdb.json"));
        // measured 2026-08-19 UNDER THE CLAUSE-2c REDESIGN (K-arm
        // verdicts; the parked seam-arm numbers are superseded), after
        // the two TRUE-wire-bug burns: the engine-verbatim empty-equality
        // ladder (nullSafeEqualsOperation, witness testEqualEmpty) and
        // the numList unwrap on collection sum/product (witness
        // testPlusNumber). Discovery exact; PASS grows-only; wire-bug
        // census shrinks-only; TRUE pinned at ZERO like essential.
        assertTrue(out.size() == 137,
                "grammar discovery moved: " + out.size() + " != 137");
        // 128 (slice 11): letFn ×2 (inline multi-statement hoist),
        // testSingle{Plus,Minus}Type + OneToOne (is/assertIs World-1
        // identity: type refs canonicalized, instance provenance)
        // 130 (host-logic audit slice): Decimal literal-list arithmetic
        // folds to exact BINARY DECIMAL chains at emission (DuckDB list
        // aggregates run DOUBLE — probed)
        assertTrue(c.pass() >= 130, "grammar PASS fell: " + c.pass());
        assertTrue(c.wireBug() <= 1,
                "grammar WIRE-BUG census grew: " + c.wireBug());
        assertTrue(c.trueWireBug() == 0,
                "a TRUE wire bug appeared (both oracles corroborate the"
                + " platform is wrong): " + c.trueWireBug());
    }
}
