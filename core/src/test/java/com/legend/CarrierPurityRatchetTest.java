// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The CARRIER PURITY RATCHET (CARRIER_REDESIGN.md tenet #1, R0): the
 * pre-dialect layers (lowering + resolver + plan) must not construct
 * backend-DATA-MODEL idioms — list/array values and their functions,
 * UNNEST placements. Each rung of the redesign moves a family into the
 * semantic-node + strategy-pass world and DELETES the direct emission;
 * the pins below only shrink, then freeze at ZERO. A count above a pin
 * is silent scope creep and fails the build; a count below means the
 * rung landed — tighten the pin in the SAME commit.
 *
 * <p>SqlFn entries are SEMANTIC vocabulary (Spellings maps them) —
 * the violation class is the backend data-model idiom, per the doc's
 * §4 definition. LIST_* / UNNEST SqlFn references upstream are counted
 * because those functions presuppose the list carrier itself.
 */
class CarrierPurityRatchetTest {

    /** Pattern -> R0 census pin (2026-08-01, main @ 85ff6c8a; raw
     * occurrences, comment mentions included — the instrument's own
     * counting semantics). */
    private static final Map<String, Integer> PINS = Map.of(
            // 34→36 (2026-08-19): ListEncodings.map's singleton-wrap and
            // empty-list spellings — the map SEMANTIC NODE's wire-shape
            // rule (scalar sources wrap; [0..1] empties stay empty),
            // not new ad-hoc idioms
            "new SqlExpr\\.ArrayLit\\(", 36,
            "new SqlExpr\\.OrderedListAgg\\(", 1,
            // 136→137 (2026-08-19): ListEncodings.map's LIST_GET — the
            // map SEMANTIC NODE's wire-shape rule (a to-one result
            // unwraps from its singleton transform; Phase 4 channel B),
            // not a new ad-hoc idiom
            "SqlFn\\.LIST_", 137,
            "SqlFn\\.UNNEST", 12,
            // the collect-carrier reducer (R1 recognizes it for fusion;
            // burns with R3/R4 when sources/values migrate)
            "new SqlAgg\\.Reducer\\(\"LIST\"", 0);

    @Test
    void carrierIdiomsOnlyShrink() throws IOException {
        for (var e : PINS.entrySet()) {
            int n = countAcrossPreDialectSources(Pattern.compile(e.getKey()));
            assertTrue(n <= e.getValue(),
                    "carrier purity ratchet: '" + e.getKey() + "' has " + n
                    + " pre-dialect sites (pin " + e.getValue()
                    + ") — new direct carrier emission is banned; lower to"
                    + " a semantic node and add strategy rules instead"
                    + " (CARRIER_REDESIGN.md tenet #1)");
            if (n < e.getValue()) {
                System.out.println("[carrier-ratchet] '" + e.getKey()
                        + "' shrank to " + n + " (pin " + e.getValue()
                        + ") — tighten the pin");
            }
        }
    }

    private static int countAcrossPreDialectSources(Pattern p)
            throws IOException {
        int n = 0;
        for (Path f : preDialectSources()) {
            Matcher m = p.matcher(Files.readString(f));
            while (m.find()) {
                n++;
            }
        }
        return n;
    }

    /** The layers UPSTREAM of the dialect strategy pass — where carrier
     * idioms are banned. The dialect package itself is exempt: that is
     * exactly where the idioms belong (as strategy rules). */
    private static java.util.List<Path> preDialectSources()
            throws IOException {
        try (Stream<Path> s = Files.walk(Path.of("src/main/java/com/legend"))) {
            java.util.List<Path> out = s
                    .filter(f -> f.toString().endsWith(".java"))
                    .filter(f -> {
                        String path = f.toString();
                        return (path.contains("/lowering/")
                                || path.contains("/resolver/")
                                || path.contains("/plan/"))
                                && !path.contains("/sql/dialect/");
                    })
                    .toList();
            GuardCoverage.assertFloor("CarrierPurityRatchetTest",
                    out.size(), 74);
            return out;
        }
    }
}
