// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * F1.4 — a POSITIVE rule on the harness (Charter C2.3): result
 * reordering is comparison POLICY only — two-sided, declared, and
 * enumerated. {@code EngineTestExecutor.compare} already applies this
 * discipline to itself (every unordered compare gated on
 * {@code ordered && actual.sortedChain()}, a compile-time fact about
 * the QUERY); this test makes the discipline required rather than
 * voluntary. The allowlist is EXACT-MATCH: a new sort/distinct site in
 * the harness fails until it is either gated-and-listed here (with the
 * reason) or removed; a removed site forces the list to shrink.
 */
class HarnessDisciplineTest {

    /** The audited sites (F1.11 re-enumeration — the first census
     *  missed the {@code List.sort(cmp)} spelling, and audit A7's own
     *  site was among the escapees):
     *  EngineTestExecutor 5 — makeString split-multiset order policy
     *  (2, two-sided), TDS-text unordered compare (2, `ordered`-gated),
     *  and the graph-triples canonicalization at :1720 (two-sided);
     *  H2Verify 2 — the replay oracle's order-insensitive row multiset
     *  (two-sided BY DESIGN, counted by F2.4);
     *  JsonAssertCanon 1 — audit A7 RESOLVED by F6.5 (2026-08-17): the
     *  site re-creates the TEST'S OWN canonicalization idiom
     *  (^JSONArray(values=...->sortBy(getValue('K')))) with pure
     *  sortBy comparator semantics — numbers numerically, mixed kinds
     *  wall — replacing the lexical String.valueOf sort. The sort
     *  itself is the test's, not harness compensation, so the SITE
     *  stays listed (the JSON metamodel never executes through the
     *  SQL pipeline);
     *  LineageForm 1 — want.sort on the property-name existence check
     *  (two-sided: both lists sorted before compare);
     *  Runner 2 / RelationalCorpusRunner 15 — rcorpus orchestration and
     *  scoreboard-RENDER ordering (deterministic output, not result
     *  comparison) — in scope so comparison sorts cannot hide here.
     *  The 15th (2026-08-20): the h2-verdicts.txt roster dump sorts by
     *  key for a DIFFABLE diagnostic file (the floor-attribution
     *  instrument) — deterministic output, never comparison. */
    private static final Map<String, Integer> ALLOWED = Map.ofEntries(
            // F4.3 ratchet-DOWN 5 -> 3: the harness RENDERER died (the
            // platform's RENDER lowerings produce the text; the probe and
            // its sorts died with it) — the survivors are the makeString
            // split-multiset order policy. 3 -> 4 (audit-of-audits #9):
            // the pool.remove( spelling joined the regex and FOUND the
            // assertSameElements value-multiset loop this guard had
            // never seen — order-insensitivity there is the assert's
            // own PURE-SPEC semantics (assertSameElements), not
            // leniency; registered, [ord]-tagged.
            // 4 -> 3 (TDG S4): a sort site died with csvCensusAssert —
            // the census asserts route through the platform now
            Map.entry("EngineTestExecutor.java", 3),
            // 2 -> 4 (diff-noreplay burndown 2026-08-28): the GRAPH
            // frame compare (goldenGraphCompare) sorts BOTH sides for
            // the same order-insensitive row-multiset verdict the
            // tabular pair implements — two-sided by design, the §4AB
            // label-mapped twin of the positional oracle
            // 4 -> 7 (TDG 49er replay): tdgSqlReplay's TWO-SIDED
            // multiset compare (both row lists sort under the shared
            // canon) + rawRows' column-NAME normalization — comparison
            // policy gated on the compile-time no-ORDER-BY fact (an
            // ordered fetch declines by name before any reorder)
            Map.entry("H2Verify.java", 7),
            Map.entry("JsonAssertCanon.java", 1),
            Map.entry("LineageForm.java", 1),
            Map.entry("Runner.java", 2),
            Map.entry("RelationalCorpusRunner.java", 15),
            // PX.1: TreeSet as a deterministic-iteration REGISTRY
            // (workspace names), not a result reorder
            Map.entry("DuckWorkspaces.java", 1),
            // ---- src/main/com/legend/exec (audit-of-audits #9): the
            // comparison policy MOVED here from the harness and walked
            // out of this guard's scope — the walk now covers it. ----
            // TdsCompare: the FOUR pool-matching loops (row multiset,
            // row-TUPLE multiset [ap.remove — the regex blind spot the
            // audit-of-Blocker-3 closed], CSVJOIN cell multiset, text
            // line multiset) — two-sided comparison policy gated on the
            // chain's sortedness, each with a distinct [ord] tag (#10)
            Map.entry("TdsCompare.java", 4),
            // PureAsserts: the typeRank sort inside sameElements — the
            // pure total-order comparator applied to BOTH sides
            Map.entry("PureAsserts.java", 1),
            // Executor: the opt-in TIMING DIAGNOSTIC dump orders by
            // elapsed time (deterministic output, never comparison)
            Map.entry("Executor.java", 1),
            // TimingLedger: TreeMap render of the diagnostic dump
            // (deterministic output, never comparison)
            Map.entry("TimingLedger.java", 1),
            // TYPED-IR Slice 1: census-class DISPLAY ordering
            // (largest-first report lines) — reporting, never a result
            // reordering; two-sided by construction (both sides of no
            // comparison flow through it). 1 -> 2 (§E3 M-N1): the
            // nullability differential's report sorts its OWN class
            // map largest-first. 2 -> 3 (§E3 slack census): the slack
            // report, same display-only shape.
            Map.entry("SqlTypeCensus.java", 3),
            // CanonicalDivergence: the assertSameElements byte-channel
            // stand-in sorts RENDERED STRINGS on BOTH sides (two-sided
            // comparison policy — the census-side mirror of R2's
            // canonical ORDER BY; CANONICAL_FORM_SPEC §0), and the R1b
            // grid-text CLASSIFIER sorts both sides' lines to name
            // row-order-only divergences. Measurement only — no probe
            // can affect a verdict. 2→4 (2026-08-22, R1b). 4→6
            // (2026-08-28, V7 batch 1): the dual-channel census REPORT
            // sorts its own form/decline tables for stable console
            // diffs — DISPLAY ordering only, the SqlTypeCensus report
            // precedent; no comparison flows through it.
            Map.entry("CanonicalDivergence.java", 6),
            // MetamodelWalk: `.distinct()` here is the RECORD ACCESSOR
            // cm().distinct() (a mapping fact), not a stream reorder —
            // counted because the spelling matches; the honest fix is
            // reading the register, not renaming the accessor
            Map.entry("MetamodelWalk.java", 3));

    /** Extremum spellings joined 2026-08-18 (Tier-2 audit; the
     * original audit's probe 12 — {@code Collections.max} in the
     * harness — landed GREEN). Zero sites today; a new one registers
     * like any reorder. */
    private static final Pattern SITE = Pattern.compile(
            "Collections\\.sort\\(|\\.sorted\\(|\\.distinct\\(\\)"
            + "|\\.sort\\(|new TreeSet|new TreeMap"
            + "|Collections\\.max\\(|Collections\\.min\\("
            + "|new PriorityQueue|\\.stream\\(\\)\\.max\\("
            + "|\\.stream\\(\\)\\.min\\("
            // audit-of-audits #9: the POOL-MATCHING loop spelling —
            // TdsCompare's hand-rolled multiset compares carry no
            // .sorted( for the regex to find. `.remove(hit)` is the
            // idiom's TRUE stable token (the first spelling,
            // `pool.remove(`, missed rowTupleMultiset's `ap.remove(hit)`
            // — caught by the audit-of-Blocker-3 pass).
            + "|\\.remove\\(hit\\)");

    @Test
    void resultReorderingIsEnumeratedComparisonPolicyOnly()
            throws IOException {
        Map<String, Integer> found = new TreeMap<>();
        int scanned = 0;
        for (Path root : new Path[] {
                Path.of("src/test/java/com/legend/harness"),
                Path.of("src/test/java/com/legend/rcorpus"),
                // audit-of-audits #9: the comparison policy lives in
                // PRODUCTION exec now (TdsCompare/PureAsserts moved
                // from the harness) — the discipline follows the code
                Path.of("src/main/java/com/legend/exec")}) {
            try (Stream<Path> files = Files.walk(root)) {
                for (Path f : files
                        .filter(p -> p.toString().endsWith(".java"))
                        .toList()) {
                    scanned++;
                    Matcher m = SITE.matcher(Files.readString(f));
                    int n = 0;
                    while (m.find()) {
                        n++;
                    }
                    if (n > 0) {
                        found.put(f.getFileName().toString(), n);
                    }
                }
            }
        }
        GuardCoverage.assertFloor("HarnessDisciplineTest", scanned, 22);
        assertEquals(new TreeMap<>(ALLOWED), found,
                "harness sort/distinct sites moved — a NEW site must be"
                + " two-sided comparison policy, gated on a compile-time"
                + " fact, and listed here with its reason (Charter C2.3);"
                + " a removed site shrinks the list");
    }
}
