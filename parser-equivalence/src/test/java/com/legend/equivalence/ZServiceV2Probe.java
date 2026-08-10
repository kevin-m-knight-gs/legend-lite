package com.legend.equivalence;

import org.finos.legend.engine.language.pure.grammar.from.PureGrammarParser;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

/** PROBE (W10-L2): engine wire for the 4.138 compact service test-suite
 *  grammar (testable/service-new-grammar fixtures). Diagnostic only. */
class ZServiceV2Probe {

    private static final String DIR = "/Users/neemsandv/legend/legend-engine/"
            + "legend-engine-xts-service/legend-engine-test-runner-service/"
            + "src/test/resources/testable/service-new-grammar/";

    private void probeFile(String rel) throws Exception {
        var mapper = org.finos.legend.engine.shared.core.ObjectMapperFactory
                .getNewStandardObjectMapperWithPureProtocolExtensionSupports();
        String src = Files.readString(Path.of(DIR + rel));
        try {
            var pmcd = PureGrammarParser.newInstance().parseModel(src);
            for (var e : pmcd.getElements()) {
                if (!e.getClass().getSimpleName().contains("Service")) {
                    continue;
                }
                System.out.println("== " + rel + " :: " + e.getPath());
                System.out.println(mapper.writeValueAsString(e));
            }
        } catch (Throwable t) {
            System.out.println("== " + rel + " REJECTED: "
                    + String.valueOf(t.getMessage()).replaceAll("\\s+", " "));
        }
    }

    @Test
    void compactParamsAndExternalFormat() throws Exception {
        var mapper = org.finos.legend.engine.shared.core.ObjectMapperFactory
                .getNewStandardObjectMapperWithPureProtocolExtensionSupports();
        String src = """
                ###Service
                Service test::S
                {
                  pattern: '/x';
                  documentation: 'd';
                  execution: Single
                  {
                    query: |1;
                    mapping: test::M;
                    runtime: test::R;
                  }
                  testSuites:
                  [
                    suite_1
                    (
                      test_1 (nameFilter = 'B') ['prod'] : PURE_TDSOBJECT =>
                        ExternalFormat
                        #{
                          contentType: 'application/json';
                          data: '[]';
                        }#;
                    )
                  ]
                }
                """;
        var pmcd = PureGrammarParser.newInstance().parseModel(src);
        for (var e : pmcd.getElements()) {
            if (e.getPath().equals("test::S")) {
                System.out.println("== inline-compact");
                System.out.println(mapper.writeValueAsString(e));
            }
        }
    }

    @Test
    void multiLineStringAndDoc() throws Exception {
        var mapper = org.finos.legend.engine.shared.core.ObjectMapperFactory
                .getNewStandardObjectMapperWithPureProtocolExtensionSupports();
        for (String[] c : new String[][] {
                {"mls-let", """
                function test::f(): String[1]
                {
                  let x = '''
                  Hello
                  World
                  ''';
                  $x;
                }
                """},
                {"doc-class", "'''\n"
                        + "A person in the system.\n"
                        + "Identity is established by name.\n"
                        + "'''\n"
                        + "Class model::Person\n"
                        + "{\n"
                        + "  '''\n"
                        + "  Given name.\n"
                        + "  '''\n"
                        + "  firstName: String[1];\n"
                        + "}\n"}}) {
            try {
                var pmcd = PureGrammarParser.newInstance().parseModel(c[1]);
                for (var e : pmcd.getElements()) {
                    if (e.getPath().contains("SectionIndex")) {
                        continue;
                    }
                    System.out.println("== " + c[0] + " :: " + e.getPath());
                    System.out.println(mapper.writeValueAsString(e));
                }
            } catch (Throwable t) {
                System.out.println("== " + c[0] + " REJECTED: "
                        + String.valueOf(t.getMessage())
                                .replaceAll("\\s+", " "));
            }
        }
    }

    @Test
    void serviceV2Shapes() throws Exception {
        probeFile("service-baseResolver.pure");
        probeFile("service-failing.pure");
        probeFile("service-multiExec.pure");
        probeFile("service-overrideResolver.pure");
        probeFile("service-referenceResolver.pure");
    }
}
