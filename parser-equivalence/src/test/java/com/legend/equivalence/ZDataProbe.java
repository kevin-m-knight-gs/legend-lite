package com.legend.equivalence;

import org.finos.legend.engine.language.pure.grammar.from.PureGrammarParser;
import org.junit.jupiter.api.Test;

/** Wire-shape probe for ###Data (section-parity leg 5). Census of the
 *  7,219-file corpus: Relational 37, Relation 17, ExternalFormat 7,
 *  store-keyed 3, ServiceStore 1. */
class ZDataProbe {

    private void probe(String label, String src) throws Exception {
        var mapper = org.finos.legend.engine.shared.core.ObjectMapperFactory
                .getNewStandardObjectMapperWithPureProtocolExtensionSupports();
        try {
            var pmcd = PureGrammarParser.newInstance().parseModel(src);
            for (var e : pmcd.getElements()) {
                String json = mapper.writeValueAsString(e);
                if (!json.startsWith("{\"_type\":\"dataElement\"")) {
                    continue;
                }
                System.out.println("== " + label + " :: " + e.getPath());
                System.out.println(json);
            }
        } catch (Throwable t) {
            System.out.println("== " + label + " REJECTED: "
                    + String.valueOf(t.getMessage()).replaceAll("\\s+", " "));
        }
    }

    @Test
    void relationalCsv() throws Exception {
        probe("relational-csv", """
                ###Data
                Data data::P
                {
                  Relational
                  #{
                    default.PersonTable:
                      'id,firstName\\n' +
                      '1,Ada\\n';
                  }#
                }
                """);
    }

    @Test
    void relationalMultiTable() throws Exception {
        probe("relational-multi", """
                ###Data
                Data data::P
                {
                  Relational
                  #{
                    default.A:
                      'id\\n1\\n';

                    myschema.B:
                      'x\\n2\\n';
                  }#
                }
                """);
    }

    @Test
    void relationCsv() throws Exception {
        probe("relation-csv", """
                ###Data
                Data data::R
                {
                  Relation
                  #{
                    default.EmployeeTable:
                      ID,FIRST_NAME
                      1,Alice
                      2,Bob;
                  }#
                }
                """);
    }

    /** Path arity + a single (non-concatenated) literal + odd island
     *  indentation — pins the table span anchors and the value decode. */
    @Test
    void relationalShapes() throws Exception {
        probe("relational-3part", """
                ###Data
                Data data::P
                {
                  Relational
                  #{
                    a.b.c:
                        'y,z\\n2,3\\n';
                      }#
                }
                """);
        probe("relational-single-literal", """
                ###Data
                Data data::P
                {
                  Relational
                  #{
                    s.t:
                      'x\\n1\\n';
                        }#
                }
                """);
        probe("relational-multi-concat", """
                ###Data
                Data data::P
                {
                  Relational
                  #{
                    s.t: 'a\\n' +
                       'b\\n' +
                         'c\\n';
                  }#
                }
                """);
    }

    /** Quoted path segments and an escaped quote inside the CSV literal. */
    @Test
    void relationalQuoting() throws Exception {
        probe("relational-quoting", """
                ###Data
                Data data::P
                {
                  Relational
                  #{
                    s.t:
                      'a\\n\\'q\\'\\n';
                  }#
                }
                """);
    }

    /** RFC4180 quoting inside a Relation island — the corpus's
     *  {@code "Doe, Jr"} row (walls-detail DIFF). */
    @Test
    void relationQuoting() throws Exception {
        probe("relation-quoted-cells", """
                ###Data
                Data data::RelationalData
                {
                  Relation
                  #{
                    default.PersonTable:
                      id,firstName,lastName
                      1,I'm John,"Doe, Jr"
                      2, spaced ," quoted spaced "
                      3,"has ""inner"" quotes",x;
                  }#
                }
                """);
    }

    /** {@code ModelStore #{ my::P: [ ^my::P(...) ] }#} — instance-value
     *  model data (walls-detail "embedded data kind '['"). */
    @Test
    void modelInstances() throws Exception {
        probe("model-instances", """
                ###Data
                Data meta::data::MyData
                {
                  ModelStore #{
                    my::Address: [
                      ^my::Address(street = 'A Road')
                    ],
                    my::Person: [
                      ^my::Person(
                        givenNames  = ['Fred', 'William'],
                        lastName    = 'Bloggs'
                      )
                    ]
                  }#
                }

                ###Pure
                Class my::Address { street: String[1]; }
                Class my::Person { givenNames: String[*]; lastName: String[1]; }
                """);
    }

    @Test
    void storeKeyed() throws Exception {
        probe("store-keyed", """
                ###Data
                Data data::ovr::DefaultFirms
                {
                  store::ovr::FirmDB:
                    Relation
                    #{
                      default.FIRM_TABLE:
                        ID,NAME
                        1,Alice
                        2,Bob;
                    }#;
                }
                """);
    }

    @Test
    void storeKeyedMulti() throws Exception {
        probe("store-keyed-multi", """
                ###Data
                Data data::D
                {
                  store::A:
                    Relation
                    #{
                      default.T:
                        ID
                        1;
                    }#;
                  store::B:
                    ExternalFormat
                    #{
                      contentType: 'application/json';
                      data: '{}';
                    }#;
                }
                """);
    }

    /** Negative numeric literals inside instance data — the ###Pure walker
     *  builds a unary-minus call; what does the DATA walker build? */
    @Test
    void negativeLiterals() throws Exception {
        probe("neg-literals", """
                ###Data
                Data meta::data::MyData
                {
                  ModelStore #{
                    my::P: [
                      ^my::P(
                        a = -1,
                        b = -1.3,
                        c = -1.8D,
                        d = 10
                      )
                    ]
                  }#
                }

                ###Pure
                Class my::P { a: Integer[1]; b: Float[1]; c: Decimal[1]; d: Integer[1]; }
                """);
    }

    /** {@code enums::Gender.MALE} inside instance data — the ###Pure walker
     *  builds a property access; the DATA walker builds an enumValue. */
    @Test
    void enumRefs() throws Exception {
        probe("enum-refs", """
                ###Data
                Data meta::data::MyData
                {
                  ModelStore #{
                    my::P: [
                      ^my::P(
                        g = enums::Gender.MALE
                      )
                    ]
                  }#
                }

                ###Pure
                Enum enums::Gender { MALE, FEMALE }
                Class my::P { g: enums::Gender[1]; }
                """);
    }

    /** The envelope itself: element span, decorations, and the two simplest
     *  bodies (ExternalFormat, ModelStore). */
    @Test
    void envelopeAndDecorations() throws Exception {
        probe("data-envelope", """
                ###Data
                Data my::TestData
                {
                  ExternalFormat
                  #{
                    contentType: 'application/json';
                    data: '{"n": "x"}';
                  }#
                }

                Data <<meta::pure::profiles::typemodifiers.abstract>> {doc.doc = 'd'} my::Data2
                {
                  ModelStore
                  #{
                    my::PD:
                      ExternalFormat
                      #{
                        contentType: 'application/json';
                        data: '{}';
                      }#
                  }#
                }

                ###Pure
                Class my::PD { n: String[1]; }
                """);
    }
}
