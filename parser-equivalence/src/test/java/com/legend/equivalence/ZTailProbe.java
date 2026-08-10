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
    void tdsShape() throws Exception {
        probe("tds-accessor", """
                function my::f(): String[1]
                {
                  let x = #TDS{
                      val, more
                      1, a
                      2, b
                  }#;
                  'ok';
                }
                """);
    }

    @Test
    void foreignMappingShape() throws Exception {
        probe("servicestore-mapping", """
                Class my::Person { name: String[1]; }

                ###ServiceStore
                ServiceStore my::Store
                (
                  ServiceGroup G
                  (
                    path : '/g';
                    Service S
                    (
                      path : '/s';
                      method : GET;
                      response : [my::Person <- my::B];
                      security : [];
                    )
                  )
                )

                ###Mapping
                Mapping my::M
                (
                  *my::Person[p_set]: ServiceStore
                  {
                    ~service [my::Store] G.S
                  }
                )
                """);
    }

    @Test
    void foreignMappingRichShape() throws Exception {
        probe("servicestore-mapping-rich", """
                Class my::Person { name: String[1]; }

                ###ServiceStore
                ServiceStore my::Store
                (
                  ServiceGroup G
                  (
                    path : '/g';
                    Service S
                    (
                      path : '/s';
                      method : GET;
                      parameters :
                      (
                        q : String (location = query)
                      );
                      response : [my::Person <- my::B];
                      security : [];
                    )
                  )
                )

                ###Mapping
                Mapping my::M
                (
                  *my::Person[p_set]: ServiceStore
                  {
                    ~service [my::Store] G.S
                    (
                      ~request
                      (
                        parameters
                        (
                          q = $this.name
                        )
                      )
                    )
                  }
                )
                """);
    }

    @Test
    void foreignMappingBodyShape() throws Exception {
        probe("servicestore-mapping-body", """
                Class my::Person { name: String[1]; }
                Class my::Syn { name: String[1]; }

                ###ServiceStore
                ServiceStore my::Store
                (
                  ServiceGroup G
                  (
                    path : '/g';
                    Service S
                    (
                      path : '/s';
                      method : POST;
                      requestBody : [my::Syn <- my::B];
                      response : [my::Person <- my::B];
                      security : [];
                    )
                  )
                )

                ###Mapping
                Mapping my::M
                (
                  *my::Person[p_set]: ServiceStore
                  {
                    ~service [my::Store] G.S
                    (
                      ~request
                      (
                        body = ^my::Syn(name = 'x')
                      )
                    )
                  }
                )
                """);
    }

    @Test
    void mongoMappingShape() throws Exception {
        probe("mongodb-mapping", """
                Class my::SomeClass { name: String[1]; }

                ###MongoDB
                Database my::db
                (
                )

                ###Mapping
                Mapping my::M
                (
                  *my::SomeClass[id1]: MongoDB
                  {
                    ~mainCollection [my::db] PersonRecord
                  }
                )
                """);
    }

    @Test
    void foreignConnShapes() throws Exception {
        probe("servicestore-conn", """
                ###ServiceStore
                ServiceStore my::Store
                (
                )

                ###Connection
                ServiceStoreConnection my::C
                {
                  store: my::Store;
                  baseUrl: 'https://prodUrl.com';
                }
                """);
        probe("deephaven-conn", """
                ###Deephaven
                Deephaven my::DStore
                (
                    Table t
                    (
                        C1: INT
                    )
                )

                ###Connection
                DeephavenConnection my::DC
                {
                    store: my::DStore;
                    serverUrl: 'http://localhost:10000'
                    authentication: # PSK {
                        psk: 'myStaticPSK';
                    }#;
                }
                """);
        probe("mongodb-conn", """
                ###MongoDB
                Database my::mdb
                (
                )

                ###Connection
                MongoDBConnection my::MC
                {
                  database: legend_db;
                  store: my::mdb;
                  serverURLs: [localhost:27071];
                  authentication: # UserPassword {
                    username: 'lgnd_usr';
                    password: SystemPropertiesSecret
                    {
                      systemPropertyName: 'sys.prop.name';
                    };
                  }#;
                }
                """);
    }

    @Test
    void richServiceStoreMappingShapes() throws Exception {
        probe("servicestore-mapping-rich2", """
                Class my::Person { name: String[1]; }

                ###ServiceStore
                ServiceStore my::Store
                (
                  ServiceGroup G
                  (
                    path : '/g';
                    Service S
                    (
                      path : '/s';
                      method : GET;
                      parameters :
                      (
                        "q param" : String (location = query)
                      );
                      response : [my::Person <- my::B];
                      security : [];
                    )
                  )
                )

                ###Mapping
                Mapping my::M
                (
                  *my::Person[p_set]: ServiceStore
                  {
                    +localName: String[1];

                    ~service [my::Store] G.S
                    (
                      ~path $service.response.name
                      ~request
                      (
                        parameters
                        (
                          "q param" = $this.name
                        )
                      )
                    )
                  }
                )
                """);
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
