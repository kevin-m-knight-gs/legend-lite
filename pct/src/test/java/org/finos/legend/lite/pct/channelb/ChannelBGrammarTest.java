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
        // 132 (2026-08-23 F13c): testEq/testEqualNonPrimitive — the
        // in-SQL eq/equal arm compiles the engine relation (identity
        // __id compare / key-tree canon) on the identity lane; BOTH
        // land as B-FIXES-A (channel A excludes them — identity was
        // unobservable on its value wire; ours carries it as data).
        assertTrue(c.pass() >= 132, "grammar PASS fell: " + c.pass());
        assertTrue(c.wireBug() <= 1,
                "grammar WIRE-BUG census grew: " + c.wireBug());
        assertTrue(c.trueWireBug() == 0,
                "a TRUE wire bug appeared (both oracles corroborate the"
                + " platform is wrong): " + c.trueWireBug());
        // V1 (OPEN_REGISTER): THE DUAL-VERDICT ALARM — the DB byte
        // verdict of record and the host-lattice referee may never
        // disagree silently; a disagreement fails the suite with the
        // census line (CANONICAL_FORM_SPEC §0, ratified design).
        assertTrue(com.legend.exec.CanonicalDivergence.sqlDisagreeCount() == 0,
                "DUAL-VERDICT DISAGREEMENT: "
                        + com.legend.exec.CanonicalDivergence.summary());

        // V6b (OPEN_REGISTER): the decline CEILING — the surviving
        // declines are DECLARED residue (class instances + wire-tree
        // containers, out of the byte channel's claimed domain per
        // CANONICAL_FORM_SPEC §4, + a handful of unrefinable Number
        // stamps). Shrink-only: a NEW undeclared decline family fails
        // here and must be claimed or declared.
        // 100 -> 35 BANKED DOWN (2026-08-22 X5): keyed-instance byte
        // verdicts (equality.Key canon — JSON framing, kind-tagged
        // leaves, Pair struct + List array carriers) and the Nil/empty
        // claim ('[]' canon unification) burned the class-instance and
        // empty-side buckets; the remainder is the NAMED boundary
        // (Map/mapEquals, Any wire trees, keyless classes,
        // mixed-identity F10, NUL literal).
        // BANKED DOWN 2026-08-22 F13: keyless classes CLAIMED
        // (identity as data, site-minted __id) — cumulative
        // PCT-lane declines 19 -> 13; residue = Any wire trees,
        // Pair unclaimable leaves, one canon-exec array shape,
        // mixed-kind, kind-gate.
        assertTrue(com.legend.exec.CanonicalDivergence.sqlDeclinedCount() <= 15,
                "byte-verdict declines grew past the declared residue: "
                        + com.legend.exec.CanonicalDivergence.summary());

    }
}
