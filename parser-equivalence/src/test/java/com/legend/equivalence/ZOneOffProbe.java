package com.legend.equivalence;

import org.finos.legend.engine.language.pure.grammar.from.PureGrammarParser;
import org.junit.jupiter.api.Test;

/** PROBE: full oracle message for one remaining skew row. Diagnostic. */
class ZOneOffProbe {
    @Test
    void probeOne() throws Exception {
        String p = "/Users/neemsandv/legend/legend-engine/legend-engine-core/"
                + "legend-engine-core-pure/legend-engine-pure-code-functions-"
                + "standard/legend-engine-pure-functions-standard-pure/src/"
                + "main/resources/core_functions_standard/date/aggregator/min.pure";
        try {
            PureGrammarParser.newInstance().parseModel(
                    java.nio.file.Files.readString(java.nio.file.Path.of(p)));
            System.out.println("@@ ACCEPTS");
        } catch (Throwable t) {
            System.out.println("@@ " + t.getClass().getSimpleName() + " :: "
                    + String.valueOf(t.getMessage()).replaceAll("\\s+", " "));
        }
        // BISECT: parse prefixes of the file to find the refusing line
        String[] lines = java.nio.file.Files.readString(
                java.nio.file.Path.of(p)).split("\n", -1);
        int lastGood = 0;
        for (int n = 1; n <= lines.length; n++) {
            String prefix = String.join("\n",
                    java.util.Arrays.copyOfRange(lines, 0, n));
            try {
                PureGrammarParser.newInstance().parseModel(prefix);
                lastGood = n;
            } catch (Throwable t) {
                // keep going; report the last line that ever parsed
            }
        }
        System.out.println("@@ lastGood=" + lastGood + " of " + lines.length);
        for (int i = lastGood; i < Math.min(lastGood + 8, lines.length); i++) {
            System.out.println("@@ L" + (i + 1) + ": " + lines[i]);
        }
    }
}
