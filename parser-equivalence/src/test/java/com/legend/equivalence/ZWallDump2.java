package com.legend.equivalence;

import org.junit.jupiter.api.Test;

class ZWallDump2 {
    @Test
    void dump() {
        java.util.Set<String> wanted = java.util.Set.of(
                "legend-engine-xts-relationalStore/legend-engine-xt-relationalStore-generation/legend-engine-xt-relationalStore-grammar/src/test/java/org/finos/legend/engine/language/pure/compiler/test/TestRelationalCompilationFromGrammar.java#278");
        for (Corpus.Source s : Corpus.all()) {
            if (wanted.contains(s.id())) {
                System.out.println(s.text());
            }
        }
    }
}
