package com.legend.equivalence;

import org.junit.jupiter.api.Test;

class ZWallDump2 {
    @Test
    void dump() {
        java.util.Set<String> wanted = java.util.Set.of(
                "legend-engine-core/legend-engine-core-base/legend-engine-core-language-pure/legend-engine-language-pure-compiler/src/test/java/org/finos/legend/engine/language/pure/compiler/test/fromGrammar/TestMappingCompilationFromGrammar.java#287");
        for (Corpus.Source s : Corpus.all()) {
            if (wanted.contains(s.id())) {
                System.out.println(s.text());
            }
        }
    }
}
