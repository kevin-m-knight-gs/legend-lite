package com.legend.equivalence;

import org.finos.legend.engine.language.pure.grammar.from.PureGrammarParser;
import org.junit.jupiter.api.Test;

/**
 * R0 of the protocol-first migration: our {@code PViewFilter} carries only a
 * NAME, and {@code ProtocolEmitter:509} hardcodes {@code "joins":[]}, while the
 * engine's {@code FilterMapping} carries a {@code FilterPointer} (db + name)
 * AND a {@code List<JoinPointer> joins}. The legacy model parser reads all four
 * forms; the protocol parser cannot express two of them. This probe pins what
 * the engine actually emits for each, so the protocol side can be taught it
 * before views migrate — a latent parity bug fixed on the way.
 */
class ZViewFilterProbe {

    private void probe(String label, String src) throws Exception {
        var mapper = org.finos.legend.engine.shared.core.ObjectMapperFactory
                .getNewStandardObjectMapperWithPureProtocolExtensionSupports();
        try {
            var pmcd = PureGrammarParser.newInstance().parseModel(src);
            for (var e : pmcd.getElements()) {
                String json = mapper.writeValueAsString(e);
                int i = json.indexOf("\"views\"");
                if (i < 0) {
                    continue;
                }
                System.out.println("== " + label);
                System.out.println(json.substring(i, Math.min(json.length(), i + 700)));
            }
        } catch (Throwable t) {
            System.out.println("== " + label + " REJECTED: "
                    + String.valueOf(t.getMessage()).replaceAll("\\s+", " "));
        }
    }

    private static final String DB2 = """

            ###Relational
            Database other::DB2
            (
              Table T2 (id INTEGER PRIMARY KEY, x VARCHAR(20))
              Filter F2 (T2.x = 'y')
            )
            """;

    @Test
    void directLocal() throws Exception {
        probe("direct-local", """
                ###Relational
                Database my::DB
                (
                  Table T (id INTEGER PRIMARY KEY, v VARCHAR(20))
                  Filter F (T.v = 'a')
                  View V (~filter F  id: T.id)
                )
                """);
    }

    @Test
    void directCrossDb() throws Exception {
        probe("direct-cross-db", """
                ###Relational
                Database my::DB
                (
                  include other::DB2
                  Table T (id INTEGER PRIMARY KEY, v VARCHAR(20))
                  View V (~filter [other::DB2] F2  id: T.id)
                )
                """ + DB2);
    }

    @Test
    void joinMediatedLocalFilter() throws Exception {
        probe("join-mediated-local", """
                ###Relational
                Database my::DB
                (
                  Table T (id INTEGER PRIMARY KEY, v VARCHAR(20))
                  Table U (id INTEGER PRIMARY KEY, w VARCHAR(20))
                  Filter F (U.w = 'a')
                  Join J (T.id = U.id)
                  View V (~filter [my::DB] @J | F  id: T.id)
                )
                """);
    }

    @Test
    void joinMediatedCrossDbFilter() throws Exception {
        probe("join-mediated-cross-db", """
                ###Relational
                Database my::DB
                (
                  include other::DB2
                  Table T (id INTEGER PRIMARY KEY, v VARCHAR(20))
                  Join J (T.id = [other::DB2]T2.id)
                  View V (~filter [my::DB] @J | [other::DB2] F2  id: T.id)
                )
                """ + DB2);
    }
}
