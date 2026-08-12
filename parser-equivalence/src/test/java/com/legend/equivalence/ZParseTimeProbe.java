package com.legend.equivalence;

import org.junit.jupiter.api.Test;

/** PROBE: wall time of LITE's parseDocument over the full corpus —
 *  2 warmup + 3 measured passes, no oracle involved. Diagnostic. */
class ZParseTimeProbe {
    @Test
    void time() {
        var sources = Corpus.all();
        for (int pass = 0; pass < 5; pass++) {
            long t0 = System.nanoTime();
            int ok = 0;
            int fail = 0;
            for (Corpus.Source s : sources) {
                try {
                    com.legend.parser.PmcdParser.parseDocument(s.text());
                    ok++;
                } catch (Throwable t) {
                    fail++;
                }
            }
            System.out.println("@@ PASS " + pass + (pass < 2 ? " (warmup)" : "")
                    + " ms=" + (System.nanoTime() - t0) / 1_000_000
                    + " ok=" + ok + " fail=" + fail);
        }
    }
}
