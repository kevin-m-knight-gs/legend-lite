package com.legend.equivalence;

import org.finos.legend.engine.language.pure.grammar.from.PureGrammarParser;
import org.junit.jupiter.api.Test;

/** Can the ENGINE's ModelJoin association mapping subsume legend-lite's
 *  inline-association extension ({@code Assoc: AssociationMapping
 *  { {p, f | ...} } }), the way extractFromSemiStructured subsumed
 *  json-column-get? Probes untyped/typed lambda and fn-ref bodies. */
class ZInlineAssocSpellingProbe {

    private static final String PREFIX = """
            ###Pure
            Class model::Person { firmId: Integer[1]; }
            Class model::Firm { id: Integer[1]; }
            Association model::Person_Firm
            {
              firm: model::Firm[1];
              person: model::Person[*];
            }
            ###Mapping
            Mapping acme::M
            (
            """;
    private static final String SUFFIX = """
            )
            """;

    private void probe(String label, String member) {
        var mapper = org.finos.legend.engine.shared.core.ObjectMapperFactory
                .getNewStandardObjectMapperWithPureProtocolExtensionSupports();
        try {
            var pmcd = PureGrammarParser.newInstance()
                    .parseModel(PREFIX + member + SUFFIX);
            for (var e : pmcd.getElements()) {
                if (!e.getPath().contains("acme::M")) {
                    continue;
                }
                System.out.println("== " + label + " ACCEPTED");
                System.out.println(mapper.writeValueAsString(e));
            }
        } catch (Throwable t) {
            System.out.println("== " + label + " REJECTED: "
                    + String.valueOf(t.getMessage()).replaceAll("\\s+", " "));
        }
    }

    @Test
    void spellings() {
        probe("lite-inline-untyped",
                "  model::Person_Firm: AssociationMapping { {p, f | $p.firmId == $f.id} }\n");
        probe("modeljoin-untyped",
                "  model::Person_Firm: ModelJoin { {p, f | $p.firmId == $f.id} }\n");
        probe("modeljoin-typed",
                "  model::Person_Firm: ModelJoin { {p: model::Person[1], f: model::Firm[1] | $p.firmId == $f.id} }\n");
        probe("modeljoin-fnref",
                "  model::Person_Firm: ModelJoin { acme::funcs::personFirmMatch }\n");
        probe("xstore-lambda",
                "  model::Person_Firm: XStore { firm: $this.firmId == $that.id }\n");
    }
}
