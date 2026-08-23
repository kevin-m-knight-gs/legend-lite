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
        // audit-of-audits #12: the wall count is ASSERTED shrink-only —
        // 20 measured live (8 are legend-lite parse failures on the
        // reference's own pure; 3 essential PCT tests vanish with
        // them, 330 on disk vs 327 discovered). A GROWTH here is the
        // suite quietly shrinking its own denominator.
        assertTrue(walls.size() <= 20,
                "channel-B essential walls grew: " + walls.size()
                        + " > 20 — dropped source files shrink the test"
                        + " universe silently; burn or adjudicate");
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
        // 293 (slice 11): exists ×2 (adapter shadow-stop), concatenate
        // (type() sig [1]→[*] per real type.pure:18), + the is/assertIs
        // World-1 identity leg
        // 295 (host-logic audit slice): parseDate zone inputs normalize to
        // the naive-UTC carrier AT EMISSION (timezone('UTC',...)) — the
        // compensation-free fix passed the rows the deleted verdict
        // patch was written for
        assertTrue(pass >= 295,
                "channel-B essential PASS fell below the pinned floor: "
                        + pass + " < 295");

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
        // measured 2026-08-19 under the CLAUSE-2c REDESIGN (verdicts in
        // World 1, zero verdict-in-SQL machinery): AGREE-PASS=286
        // AGREE-FAIL=21 WIRE-BUG=14 B-FIXES-A=4 DECLINED=2 — better on
        // every honest axis than the seam-arm era (280/15/20/10). The 6
        // assertError line/col rows reclassified B-FIXES-A→AGREE-FAIL
        // (source position is unobservable from database errors; channel
        // A cannot pass them either). Wire-bug census SHRINKS only;
        // agreement GROWS only.
        // 288/11 -> 292/10 BANKED (2026-08-22 F13, synthetic instance
        // identity): keyless-class equality became engine-true identity
        // (site-minted __id in both channels) — four rows moved into
        // agreement and one wire-bug row died with the eq() wall.
        assertTrue(agreePass >= 292, "AGREE-PASS fell: " + agreePass);
        assertTrue(wireBug <= 10, "WIRE-BUG census grew: " + wireBug);
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
        // 100 -> 30 BANKED DOWN (2026-08-22 X5): keyed-instance byte
        // verdicts (equality.Key canon — JSON framing, kind-tagged
        // leaves, Pair struct + List array carriers) and the Nil/empty
        // claim ('[]' canon unification) burned the class-instance and
        // empty-side buckets; the remainder is the NAMED boundary
        // (Map/mapEquals, Any wire trees, keyless classes,
        // mixed-identity F10, NUL literal).
        // 30 -> 15 BANKED DOWN (2026-08-22 F13): the genuinely-keyless
        // model classes are CLAIMED (identity as data — site-minted
        // __id, {_type,_id} canon; PCT-lane declines 19 -> 13). The
        // remainder is the NAMED boundary: Any wire trees (F10), the 3
        // Pair unclaimable-leaf shapes, one array-shaped keyless side
        // riding the canon-exec tunnel, mixed-kind, kind-gate.
        assertTrue(com.legend.exec.CanonicalDivergence.sqlDeclinedCount() <= 15,
                "byte-verdict declines grew past the declared residue: "
                        + com.legend.exec.CanonicalDivergence.summary());

        // THE PHASE-4 MILESTONE NUMBER: rows OUR platform fails that BOTH
        // reference channels pass. ZERO (2026-08-19) — every remaining
        // failure is corroborated by a reference channel. Stays zero.
        assertTrue(trueWireBug == 0,
                "TRUE wire-bug census grew from ZERO: " + trueWireBug);
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
