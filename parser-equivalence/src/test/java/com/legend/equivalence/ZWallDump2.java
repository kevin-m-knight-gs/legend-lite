package com.legend.equivalence;

import org.junit.jupiter.api.Test;

class ZWallDump2 {
    @Test
    void dump() {
        java.util.Set<String> wanted = java.util.Set.of(
                "legend-pure-store/legend-pure-store-relational/legend-pure-m2-store-relational-grammar/src/test/java/org/finos/legend/pure/m2/relational/TestSimpleGrammar.java#218");
        for (Corpus.Source s : Corpus.all()) {
            if (wanted.contains(s.id())) {
                System.out.println(s.text());
            }
        }
    }
}
