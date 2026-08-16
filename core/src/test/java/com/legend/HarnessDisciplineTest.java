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

    /** The audited sites, each two-sided (BOTH expected and actual
     *  transformed identically = judging, never repairing arity/order):
     *  EngineTestExecutor 4 — the makeString split-multiset order
     *  policy (2 sorts, one per side) and the TDS-text unordered
     *  compare (2 sorts, `ordered`-gated);
     *  H2Verify 2 — the replay oracle's order-insensitive row multiset
     *  (2 sorts, one per side; the oracle discards row order by DESIGN,
     *  counted by F2.4's leniency census). */
    private static final Map<String, Integer> ALLOWED = Map.of(
            "EngineTestExecutor.java", 4,
            "H2Verify.java", 2);

    private static final Pattern SITE = Pattern.compile(
            "Collections\\.sort\\(|\\.sorted\\(|\\.distinct\\(\\)");

    @Test
    void resultReorderingIsEnumeratedComparisonPolicyOnly()
            throws IOException {
        Map<String, Integer> found = new TreeMap<>();
        Path root = Path.of("src/test/java/com/legend/harness");
        try (Stream<Path> files = Files.walk(root)) {
            for (Path f : files.filter(p -> p.toString().endsWith(".java"))
                    .toList()) {
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
        assertEquals(new TreeMap<>(ALLOWED), found,
                "harness sort/distinct sites moved — a NEW site must be"
                + " two-sided comparison policy, gated on a compile-time"
                + " fact, and listed here with its reason (Charter C2.3);"
                + " a removed site shrinks the list");
    }
}
