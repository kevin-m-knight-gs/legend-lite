package com.legend.equivalence;

import org.junit.jupiter.api.Test;

/** Dump walled snippets by source id (diagnostic). */
class ZWallDump {

    @Test
    void dump() {
        java.util.Set<String> wanted = java.util.Set.of(
                "legend-engine-xts-relationalStore/legend-engine-xt-relationalStore-generation/legend-engine-xt-relationalStore-grammar/src/test/java/org/finos/legend/engine/language/pure/compiler/test/TestRelationalCompilationFromGrammar.java#59",
                "legend-engine-xts-relationalStore/legend-engine-xt-relationalStore-generation/legend-engine-xt-relationalStore-grammar/src/test/java/org/finos/legend/engine/language/pure/compiler/test/TestRelationFunctions.java#16");
        for (Corpus.Source s : Corpus.all()) {
            if (wanted.contains(s.id())) {
                System.out.println("==== " + s.id());
                String[] lines = s.text().split("\n");
                for (int i = 0; i < Math.min(lines.length, 22); i++) {
                    System.out.println((i + 1) + ": " + lines[i]);
                }
            }
        }
    }
}
