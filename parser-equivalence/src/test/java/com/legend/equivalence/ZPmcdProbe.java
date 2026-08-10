package com.legend.equivalence;

import org.finos.legend.engine.language.pure.grammar.from.PureGrammarParser;
import org.junit.jupiter.api.Test;

/** PROBE (PMCD build): the engine's WHOLE-DOCUMENT serialization —
 *  envelope keys, the sectionIndex element's wire, element order.
 *  Diagnostic only. */
class ZPmcdProbe {

    private void probe(String label, String src) throws Exception {
        var mapper = org.finos.legend.engine.shared.core.ObjectMapperFactory
                .getNewStandardObjectMapperWithPureProtocolExtensionSupports();
        try {
            var pmcd = PureGrammarParser.newInstance().parseModel(src);
            System.out.println("== " + label);
            System.out.println(mapper.writeValueAsString(pmcd));
        } catch (Throwable t) {
            System.out.println("== " + label + " REJECTED: "
                    + String.valueOf(t.getMessage()).replaceAll("\\s+", " "));
        }
    }

    @Test
    void envelopeAndSectionIndex() throws Exception {
        probe("multi-section", """
                Class my::A
                {
                  name: String[1];
                }

                ###Relational
                Database my::DB
                (
                  Table T (ID INTEGER PRIMARY KEY)
                )

                ###Mapping
                import my::*;
                Mapping my::M
                (
                  A: Relational
                  {
                    ~mainTable [my::DB]T
                    name: [my::DB]T.ID
                  }
                )

                ###Pure
                import my::*;
                Class my::B extends A
                {
                }
                """);
        probe("no-sections", """
                Class solo::A
                {
                }
                """);
        probe("text-section", """
                ###Text
                Text my::T
                {
                  type: STRING;
                  content: 'hi';
                }
                """);
    }
}
