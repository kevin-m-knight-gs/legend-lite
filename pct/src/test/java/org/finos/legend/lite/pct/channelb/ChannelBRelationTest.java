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
        java.util.List<String> walls = new java.util.ArrayList<>();
        List<ChannelB.Outcome> out = ChannelB.run(
                List.of(platform, scope), List.of(scope), walls);
        walls.forEach(w -> System.out.println("[chB-Relation-wall] " + w));
        System.out.println("[chB-Relation] walls=" + walls.size());
        // audit-of-audits #12: walls ASSERTED shrink-only (23 measured
        // 2026-08-21); growth silently shrinks the discovery universe
        assertTrue(walls.size() <= 20,
                "relation walls grew: " + walls.size() + " > 20");
        // 2026-08-23: the over.pure + pctQualifiers.pure walls BURNED
        // (the '?' schema-algebra wildcard classifies as the anonymous
        // TypeVar; Profile self-stereotypes parse in platform lanes,
        // engine-grammar-refused in LEGEND) — their by-name tripwires
        // retired, the discovery pin moved as they demanded.
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
        // audit-of-audits #12: the honest denominator. 287 -> 355
        // (2026-08-23, the wall burn): over.pure's 68 window tests and
        // the qualifier profile are DISCOVERED — more than the 348 the
        // reference suite itself enumerates (its qualifier config
        // filters ~7). Exact in both directions, as before.
        assertTrue(out.size() == 355,
                "relation discovery moved: " + out.size() + " != 355");
        // 100% (2026-08-19): the DESC nulls-first sort burned the last
        // pair — pure null ordering is NULL-IS-LARGEST
        // 287 -> 355 (2026-08-23): 100% at the EXPANDED universe —
        // 66 of the 68 new window tests passed out of the box; the two
        // RANGE-with-nulls DESC failures were ONE renderer bug (the
        // aggregate ORDER BY hoist dropped declared null placement —
        // AggOrderNullPlacementTest pins it).
        assertTrue(c.pass() >= 355, "relation PASS fell: " + c.pass());
        // 33→28 (slice 1: singleton extremes, carrier norm, chunk)
        // →24 (slice 4: CANONICAL variant text — to_json over the
        // JSON-cast value, compact with leaf quoting preserved)
        assertTrue(c.wireBug() == 0,
                "relation WIRE-BUG census grew: " + c.wireBug());
        assertTrue(c.trueWireBug() == 0,
                "relation TRUE wire-bug census grew: " + c.trueWireBug());
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
        // 100 -> 5 BANKED DOWN (2026-08-22 X5): keyed-instance byte
        // verdicts (equality.Key canon — JSON framing, kind-tagged
        // leaves, Pair struct + List array carriers) and the Nil/empty
        // claim ('[]' canon unification) burned the class-instance and
        // empty-side buckets; the remainder is the NAMED boundary
        // (Map/mapEquals, Any wire trees, keyless classes,
        // mixed-identity F10, NUL literal).
        // 5 -> 3 (2026-08-23): letFn burned by the Any-root
        // FIX-EMITTER; 3 -> 2: map burned by the F13b(a) flatten fix;
        // 2 -> 0 (F10 slice 2): mixedSort burned by the LITERAL
        // carrier. ZERO declared residue — new declines are new work.
        assertTrue(com.legend.exec.CanonicalDivergence.sqlDeclinedCount() <= 0,
                "byte-verdict declines grew past the declared residue: "
                        + com.legend.exec.CanonicalDivergence.summary());
        // CONTRACT PROGRAM wire ratchets (adjudicated 2026-08-23,
        // shrink-only): DIVERGE = the true residue (hash UBIGINT,
        // percentile input-type, Number-erasure decimal delivery —
        // witnesses attached); ADOPT-PENDING = integer aggregates
        // whose CONTRACT widens at construction (testLargePlus rule).
        // 80 -> 75 (2026-08-23): hash UBIGINT family burned by the
        // dialect hashSigned conform (single owner; Lowerer's private
        // agg shift DELETED) — measured full-lane residue 74
        assertTrue(com.legend.exec.SqlTypeCensus.wireDivergeCount() <= 75,
                "wire divergence grew: "
                        + com.legend.exec.SqlTypeCensus.summary());
        assertTrue(com.legend.exec.SqlTypeCensus.wireAdoptPendingCount() <= 110,
                "wire adopt-pending grew: "
                        + com.legend.exec.SqlTypeCensus.summary());


    }
}
