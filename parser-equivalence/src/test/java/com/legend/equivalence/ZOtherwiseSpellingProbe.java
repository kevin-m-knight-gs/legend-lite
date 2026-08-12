package com.legend.equivalence;

import org.finos.legend.engine.language.pure.grammar.from.PureGrammarParser;
import org.junit.jupiter.api.Test;

/** The two {@code Otherwise} spellings: engine's grammar is
 *  {@code Otherwise ([id]: [db]@Join)} (joinSequence only,
 *  RelationalParserGrammar#otherwisePropertyMapping); m2's grammar also
 *  allows a plain column pointer ({@code [id]: [db]T.col}). Pins the
 *  oracle's acceptance of the join form and its EXACT refusal message for
 *  the m2 column form, so the strict-surface gate can be engine-verbatim. */
class ZOtherwiseSpellingProbe {

    private static final String PREFIX = """
            ###Relational
            Database store::DB
            (
              Table T_PERSON(ID INTEGER PRIMARY KEY, FIRM_ID INTEGER)
              Table T_FIRM(ID INTEGER PRIMARY KEY, NAME VARCHAR(100))
              Join PersonFirm(T_PERSON.FIRM_ID = T_FIRM.ID)
            )
            ###Pure
            Class model::Person { name: String[1]; firm: model::Firm[1]; }
            Class model::Firm { name: String[1]; }
            ###Mapping
            Mapping acme::M
            (
              model::Person[personSet]: Relational
              {
                ~mainTable [store::DB] T_PERSON
                firm
                (
                ) Otherwise ( %s )
              }
              model::Firm[firmSet]: Relational
              {
                ~mainTable [store::DB] T_FIRM
                name: [store::DB] T_FIRM.NAME
              }
            )
            """;

    private void probe(String label, String otherwiseBody) {
        try {
            PureGrammarParser.newInstance()
                    .parseModel(PREFIX.formatted(otherwiseBody));
            System.out.println("== " + label + " ACCEPTED");
        } catch (Throwable t) {
            System.out.println("== " + label + " REJECTED: "
                    + String.valueOf(t.getMessage()).replaceAll("\\s+", " "));
        }
    }

    @Test
    void spellings() {
        probe("engine-join-form", "[firmSet]: [store::DB]@PersonFirm");
        probe("m2-column-form", "[firmSet]: [store::DB]T_FIRM.NAME");
        probe("m2-column-form-noDb", "[firmSet]: T_FIRM.NAME");
        probe("join-chain-form", "[firmSet]: [store::DB]@PersonFirm->@PersonFirm");
    }
}
