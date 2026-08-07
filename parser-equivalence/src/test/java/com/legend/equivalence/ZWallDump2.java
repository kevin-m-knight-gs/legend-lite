package com.legend.equivalence;

import org.junit.jupiter.api.Test;

class ZWallDump2 {
    @Test
    void dump() {
        java.util.Set<String> wanted = java.util.Set.of(
                "legend-engine-xts-relationalStore/legend-engine-xt-relationalStore-generation/legend-engine-xt-relationalStore-grammar/src/test/java/org/finos/legend/engine/language/pure/grammar/test/TestRelationalGrammarRoundtrip.java#5");
        for (Corpus.Source s : Corpus.all()) {
            if (wanted.contains(s.id())) {
                String[] lines = s.text().split("\n");
                for (int i = 62; i < Math.min(lines.length, 74); i++) {
                    System.out.println((i + 1) + ": " + lines[i]);
                }
            }
        }
    }
}
