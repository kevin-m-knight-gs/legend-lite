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
     *  Runner 2 / RelationalCorpusRunner 14 — rcorpus orchestration and
     *  scoreboard-RENDER ordering (deterministic output, not result
     *  comparison) — in scope so comparison sorts cannot hide here. */
    private static final Map<String, Integer> ALLOWED = Map.of(
            // F4.3 ratchet-DOWN 5 -> 3: the harness RENDERER died (the
            // platform's RENDER lowerings produce the text; the probe and
            // its sorts died with it) — the survivors are the makeString
            // split-multiset order policy
            "EngineTestExecutor.java", 3,
            "H2Verify.java", 2,
            "JsonAssertCanon.java", 1,
            "LineageForm.java", 1,
            "Runner.java", 2,
            "RelationalCorpusRunner.java", 14,
            // PX.1: TreeSet as a deterministic-iteration REGISTRY
            // (workspace names), not a result reorder
            "DuckWorkspaces.java", 1);

    /** Extremum spellings joined 2026-08-18 (Tier-2 audit; the
     * original audit's probe 12 — {@code Collections.max} in the
     * harness — landed GREEN). Zero sites today; a new one registers
     * like any reorder. */
    private static final Pattern SITE = Pattern.compile(
            "Collections\\.sort\\(|\\.sorted\\(|\\.distinct\\(\\)"
            + "|\\.sort\\(|new TreeSet|new TreeMap"
            + "|Collections\\.max\\(|Collections\\.min\\("
            + "|new PriorityQueue|\\.stream\\(\\)\\.max\\("
            + "|\\.stream\\(\\)\\.min\\(");

    @Test
    void resultReorderingIsEnumeratedComparisonPolicyOnly()
            throws IOException {
        Map<String, Integer> found = new TreeMap<>();
        int scanned = 0;
        for (Path root : new Path[] {
                Path.of("src/test/java/com/legend/harness"),
                Path.of("src/test/java/com/legend/rcorpus")}) {
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
