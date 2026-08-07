package com.legend.equivalence;

import org.finos.legend.engine.language.pure.grammar.from.PureGrammarParser;
import org.finos.legend.engine.protocol.pure.v1.model.context.PureModelContextData;
import org.junit.jupiter.api.Test;

/** Ground-truth probe: the engine's sourceInformation for `=> Relation #{...}#`
 *  assertion payloads across island geometries. Diagnostic only — prints, no asserts. */
class ZAssertSpanProbe {

    private static final String HEAD = """
            function model::Q(): meta::pure::metamodel::relation::Relation<(a:String)>[1]
            {
              #>{store::TestDB.T}#->select(~[a])
            }
            {
              suite_1
              (
            """;
    private static final String TAIL = """
              )
            }
            """;

    private void probe(String label, String testBlock) throws Exception {
        String src = HEAD + testBlock + TAIL;
        try {
            PureModelContextData pmcd = PureGrammarParser.newInstance().parseModel(src);
            var mapper = org.finos.legend.engine.shared.core.ObjectMapperFactory
                    .getNewStandardObjectMapperWithPureProtocolExtensionSupports();
            for (var e : pmcd.getElements()) {
                String json = mapper.writeValueAsString(e);
                int i = json.indexOf("expected");
                if (i >= 0) {
                    System.out.println("== " + label);
                    System.out.println(json.substring(i, Math.min(json.length(), i + 400)));
                }
            }
        } catch (Throwable t) {
            System.out.println("== " + label + " REJECTED: " + t);
        }
    }

    @Test
    void geometries() throws Exception {
        // content on next line, indented 2 past the #{
        probe("nextline-indent+2", """
                t1 | Q() => Relation
                #{
                  a
                  x;
                }#;
            """);
        // content on next line, indented 6 past the #{
        probe("nextline-indent+6", """
                t1 | Q() => Relation
                #{
                      a
                      x;
                }#;
            """);
        // content on next line, LESS indented than #{
        probe("nextline-outdent", """
                    t1 | Q() => Relation
                    #{
              a
              x;
                    }#;
            """);
        // content on the #{ line, one space
        probe("sameline-1sp", """
                t1 | Q() => Relation #{ a
                  x;
                }#;
            """);
        // content on the #{ line, three spaces
        probe("sameline-3sp", """
                t1 | Q() => Relation #{   a
                  x;
                }#;
            """);
        // single-line block, content on next line
        probe("singleline-block", """
                t1 | Q() => Relation
                #{
                  a x;
                }#;
            """);
        // single-line whole island on the #{ line
        probe("all-on-one-line", """
                t1 | Q() => Relation #{ a x; }#;
            """);
        // content TWO lines after #{
        probe("blank-line-then-content", """
                t1 | Q() => Relation
                #{

                  a
                  x;
                }#;
            """);
    }
}
