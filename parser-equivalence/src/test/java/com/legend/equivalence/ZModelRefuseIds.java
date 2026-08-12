package com.legend.equivalence;

import org.junit.jupiter.api.Test;

/** PROBE: ids of the compile-seam model-refuse rows. Diagnostic. */
class ZModelRefuseIds {
    @Test
    void probe() throws Exception {
        var mapper = org.finos.legend.engine.shared.core.ObjectMapperFactory
                .getNewStandardObjectMapperWithPureProtocolExtensionSupports();
        for (Corpus.Source src : Corpus.all()) {
            try {
                org.finos.legend.engine.language.pure.grammar.from
                        .PureGrammarParser.newInstance().parseModel(src.text());
            } catch (Throwable t) {
                continue;
            }
            try {
                com.legend.parser.ElementParser.parse(src.text(), com.legend.parser.Dialect.LEGEND_PLATFORM);
            } catch (Throwable t) {
                Throwable r = t;
                while (r.getCause() != null && r.getCause() != r) r = r.getCause();
                System.out.println("@@ MODEL-REFUSE " + src.id() + " :: "
                        + String.valueOf(r.getMessage()).replaceAll("\\s+", " "));
            }
        }
    }
}
