package com.legend.equivalence;

import org.finos.legend.engine.language.pure.grammar.from.PureGrammarParser;
import org.junit.jupiter.api.Test;

/** Does the ORACLE parse-accept a PREFIX spelling of json-column-get
 *  ({@code get(T.DATA, 'key')}) in a relational property mapping? If yes,
 *  the LITE extension can move from the PARSER (grammar invention) to the
 *  COMPILER (a lite-supported dynafunction) and the arrow grammar dies. */
class ZJsonGetSpellingProbe {

    private static final String PREFIX = """
            ###Relational
            Database store::OrderDB
            (
              Table T_ORDERS(ID INTEGER PRIMARY KEY, DATA VARCHAR(1000))
            )
            ###Pure
            Class model::Order { id: Integer[1]; customerName: String[1]; total: Float[1]; }
            ###Mapping
            Mapping model::OrderMapping
            (
              model::Order: Relational
              {
                ~mainTable [store::OrderDB] T_ORDERS
                id: [store::OrderDB] T_ORDERS.ID,
            """;
    private static final String SUFFIX = """
              }
            )
            """;

    private void probe(String label, String rhsLines) {
        var mapper = org.finos.legend.engine.shared.core.ObjectMapperFactory
                .getNewStandardObjectMapperWithPureProtocolExtensionSupports();
        try {
            var pmcd = PureGrammarParser.newInstance()
                    .parseModel(PREFIX + rhsLines + SUFFIX);
            for (var e : pmcd.getElements()) {
                if (!e.getPath().contains("OrderMapping")) {
                    continue;
                }
                String json = mapper.writeValueAsString(e);
                System.out.println("== " + label + " ACCEPTED");
                System.out.println(json);
            }
        } catch (Throwable t) {
            System.out.println("== " + label + " REJECTED: "
                    + String.valueOf(t.getMessage()).replaceAll("\\s+", " "));
        }
    }

    @Test
    void spellings() {
        probe("arrow-current",
                "customerName: [store::OrderDB] T_ORDERS.DATA->get('customerName', @String)\n");
        probe("prefix-no-type",
                "customerName: [store::OrderDB] get(T_ORDERS.DATA, 'customerName')\n");
        probe("prefix-at-type",
                "total: [store::OrderDB] get(T_ORDERS.DATA, 'total', @Float)\n");
        probe("prefix-nested-to",
                "total: [store::OrderDB] to(get(T_ORDERS.DATA, 'total'), @Float)\n");
        probe("prefix-string-type",
                "total: [store::OrderDB] get(T_ORDERS.DATA, 'total', 'Float')\n");
        probe("prefix-unbracketed-fn",
                "total: [store::OrderDB] extractFromSemiStructured(T_ORDERS.DATA, 'total', 'Float')\n");
    }
}
