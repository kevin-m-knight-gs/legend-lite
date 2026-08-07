package com.legend.equivalence;

import org.finos.legend.engine.language.pure.grammar.from.PureGrammarParser;
import org.junit.jupiter.api.Test;

/** Wire-shape probe for ###Connection (section-parity leg 2). Diagnostic only. */
class ZConnectionProbe {

    private void probe(String label, String src) throws Exception {
        var mapper = org.finos.legend.engine.shared.core.ObjectMapperFactory
                .getNewStandardObjectMapperWithPureProtocolExtensionSupports();
        try {
            var pmcd = PureGrammarParser.newInstance().parseModel(src);
            for (var e : pmcd.getElements()) {
                if (e.getPath().contains("SectionIndex")) {
                    continue;
                }
                System.out.println("== " + label + " :: " + e.getPath());
                System.out.println(mapper.writeValueAsString(e));
            }
        } catch (Throwable t) {
            System.out.println("== " + label + " REJECTED: "
                    + String.valueOf(t.getMessage()).replaceAll("\\s+", " "));
        }
    }

    @Test
    void shapes() throws Exception {
        probe("json", """
                ###Connection
                JsonModelConnection my::J
                {
                  class: my::K;
                  url: 'data:x';
                }
                """);
        probe("xml", """
                ###Connection
                XmlModelConnection my::X
                {
                  class: my::K;
                  url: 'data:y';
                }
                """);
        probe("model-chain", """
                ###Connection
                ModelChainConnection my::MC
                {
                  mappings: [my::M1, my::M2];
                }
                """);
        probe("relational-h2", """
                ###Connection
                RelationalDatabaseConnection my::R
                {
                  store: my::DB;
                  type: H2;
                  specification: LocalH2 { testDataSetupSqls: ['drop table x;']; };
                  auth: DefaultH2;
                }
                """);
        probe("relational-static-kerberos", """
                ###Connection
                RelationalDatabaseConnection my::R2
                {
                  store: my::DB;
                  type: MemSQL;
                  specification: Static
                  {
                    name: 'person_schema';
                    host: 'test_memsql_database';
                    port: 3306;
                  };
                  auth: DelegatedKerberos;
                }
                """);
        probe("relational-test-auth", """
                ###Connection
                RelationalDatabaseConnection my::R3
                {
                  store: my::DB;
                  type: H2;
                  specification: LocalH2 {};
                  auth: Test;
                }
                """);
        probe("kerberos-with-principal", """
                ###Connection
                RelationalDatabaseConnection my::R4
                {
                  store: my::DB;
                  type: MemSQL;
                  specification: Static { name: 'n'; host: 'h'; port: 1; };
                  auth: DelegatedKerberos { serverPrincipal: 'sp'; };
                }
                """);
    }
}
