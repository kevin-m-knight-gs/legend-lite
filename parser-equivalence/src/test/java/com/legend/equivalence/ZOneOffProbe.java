package com.legend.equivalence;

import org.finos.legend.engine.language.pure.grammar.from.PureGrammarParser;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

/** PROBE: full oracle exception chain for one own-corpus row. Diagnostic. */
class ZOneOffProbe {
    @Test
    void probeOne() throws Exception {
        String want = System.getProperty("probe.id",
                "src/test/java/com/legend/integration/DuckDBIntegrationTest.java#238");
        Path repo = Path.of(System.getProperty("user.dir")).getParent();
        java.util.List<Corpus.Source> all = new java.util.ArrayList<>(
                Corpus.all());
        String extra = System.getProperty("probe.file");
        if (extra != null) {
            all.add(new Corpus.Source("probe-file",
                    java.nio.file.Files.readString(Path.of(extra)), "Z"));
        }
        for (Corpus.Source src : all) {
            {
                if (!src.id().equals(want)) {
                    continue;
                }
                try {
                    var pmcd = PureGrammarParser.newInstance()
                            .parseModel(src.text());
                    System.out.println("@@ ACCEPTS");
                    if (System.getProperty("probe.json") != null) {
                        System.out.println("@@ JSON "
                                + org.finos.legend.engine.shared.core
                                .ObjectMapperFactory
                                .getNewStandardObjectMapperWithPureProtocolExtensionSupports()
                                .writeValueAsString(pmcd));
                    }
                    try {
                        com.legend.parser.ElementParser.parseLegendPlatform(src.text());
                        System.out.println("@@ LITE-OK");
                        System.out.println("@@ LITE-JSON "
                                + com.legend.parser.PmcdParser
                                        .parseDocument(src.text())
                                        .substring(0, Math.min(2000,
                                                com.legend.parser.PmcdParser
                                                .parseDocument(src.text())
                                                .length())));
                    } catch (Throwable lt) {
                        System.out.println("@@ LITE-FAIL " + lt);
                        for (var st : lt.getStackTrace()) {
                            if (st.getClassName().startsWith("com.legend")) {
                                System.out.println("@@   at " + st);
                            }
                        }
                        String[] ls = src.text().split("\n", -1);
                        for (int i = 0; i < ls.length; i++) {
                            System.out.println("@@ L" + (i + 1) + "| " + ls[i]);
                        }
                    }
                } catch (Throwable t) {
                    for (Throwable x = t; x != null;
                            x = x.getCause() == x ? null : x.getCause()) {
                        String si = "";
                        if (x instanceof org.finos.legend.engine.shared.core
                                .operational.errorManagement
                                .EngineException ee
                                && ee.getSourceInformation() != null) {
                            si = " @[" + ee.getSourceInformation().startLine
                                    + ":" + ee.getSourceInformation()
                                            .startColumn + "-"
                                    + ee.getSourceInformation().endLine + ":"
                                    + ee.getSourceInformation().endColumn
                                    + "]";
                        }
                        System.out.println("@@ " + x.getClass().getName()
                                + si + " :: " + String.valueOf(x.getMessage())
                                        .replaceAll("\\s+", " "));
                    }
                    // show the named lines
                    String[] lines = src.text().split("\n", -1);
                    for (int i = 0; i < lines.length; i++) {
                        System.out.println("@@ L" + (i + 1) + "| " + lines[i]);
                    }
                }
            }
        }
    }
}
