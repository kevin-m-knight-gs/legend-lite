package com.legend.integration;

import java.nio.file.*;
import java.util.*;

/**
 * Loads the stress corpus for the legend-lite side, minus what legend-lite cannot parse.
 *
 * <p>Both legend-lite tests need the same model, and both need the same exclusions — an
 * unparseable element does not fail one service, it fails the whole model load, so a
 * single unsupported construct would take the entire legend-lite side of the corpus down
 * with it.
 *
 * <p>Every exclusion is a legend-lite GAP, not a corpus error: legend-engine runs these
 * files. Each carries its reason, and removing one must be a deliberate act.
 */
final class StressCorpus {

    /** file name -> why legend-lite cannot load it. */
    static final Map<String, String> EXCLUDED = Map.of(
            "29-money.pure",
            "Measure/Unit: 'Unknown type: stress::Money~USD is not a known primitive, "
                    + "class, or enum'. legend-lite has no Measure support.",
            "55-canonical-store.pure",
            "declares canonical::MoneyMapping, which composes a unit with newUnit(...); "
                    + "unparseable for the same reason. It also holds the M2M mapping and "
                    + "the ModelChainConnection runtimes, which legend-lite does not "
                    + "implement either (see UNSUPPORTED_RUNTIMES).");

    private StressCorpus() {
    }

    static String model() throws Exception {
        StringBuilder sb = new StringBuilder();
        try (var s = Files.list(Path.of("src/test/resources/stress"))) {
            for (Path p : s.sorted().toList()) {
                if (EXCLUDED.containsKey(p.getFileName().toString())) {
                    continue;
                }
                sb.append(Files.readString(p)).append("\n");
            }
        }
        return sb.toString();
    }

    static void reportExclusions() {
        EXCLUDED.forEach((f, why) ->
                System.out.println("  EXCLUDED " + f + ": " + why));
    }
}
