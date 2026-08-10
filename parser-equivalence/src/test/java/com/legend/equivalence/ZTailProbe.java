package com.legend.equivalence;

import org.finos.legend.engine.language.pure.grammar.from.PureGrammarParser;
import org.junit.jupiter.api.Test;

/** Wire-shape probe for the burn-to-zero tail: include dataspace, #SQL
 *  islands, ServiceStore embedded data, (dataspace) function-test refs.
 *  Diagnostic only. */
class ZTailProbe {

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
        probe("include-dataspace", """
                ###Mapping
                Mapping my::M
                (
                  include dataspace my::DS
                )
                """);
        probe("sql-island", """
                function my::f(): Any[*]
                {
                  #SQL{select * from t}#
                }
                """);
        probe("servicestore-data", """
                ###Data
                Data my::D
                {
                  ServiceStore
                  #{
                    [
                      {
                        request:
                        {
                          method: GET;
                          url: '/x';
                        };
                        response:
                        {
                          body:
                            ExternalFormat
                            #{
                              contentType: 'application/json';
                              data: '[]';
                            }#;
                        };
                      }
                    ]
                  }#
                }
                """);
        probe("dataspace-testref", """
                function my::f(): Any[*]
                {
                  1
                }
                {
                  testSuite_1
                  (
                    (dataspace) my::DS:
                        DataspaceTestData
                        #{
                          my::Ref
                        }#;
                    test_1 | f() => '1';
                  )
                }
                """);
    }
}
