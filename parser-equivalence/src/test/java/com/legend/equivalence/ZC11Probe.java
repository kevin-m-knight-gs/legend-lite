package com.legend.equivalence;

import org.finos.legend.engine.language.pure.grammar.from.PureGrammarParser;
import org.junit.jupiter.api.Test;

/** PROBE: the ORACLE's verdict on every C11 resource-txt row. Diagnostic. */
class ZC11Probe {

    @Test
    void oracleVerdicts() {
        PureGrammarParser oracle = PureGrammarParser.newInstance();
        for (Corpus.Source s : Corpus.all()) {
            if (!"C11 resource-txt".equals(s.tier())) {
                continue;
            }
            try {
                oracle.parseModel(s.text());
            } catch (Throwable t) {
                Throwable r = t;
                while (r.getCause() != null && r.getCause() != r) {
                    r = r.getCause();
                }
                System.out.println("@@ C11-REJECT " + s.id() + " :: "
                        + String.valueOf(r.getMessage())
                                .replaceAll("\\s+", " "));
            }
        }
    }
}
