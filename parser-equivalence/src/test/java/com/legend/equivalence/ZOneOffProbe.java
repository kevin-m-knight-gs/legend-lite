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
        for (String module : new String[]{"core", "pct", "nlq"}) {
            for (Corpus.Source src : InlineSnippets.extract(
                    repo.resolve(module), "lite-" + module,
                    InlineSnippets.OWN_DECL)) {
                if (!src.id().equals(want)) {
                    continue;
                }
                try {
                    PureGrammarParser.newInstance().parseModel(src.text());
                    System.out.println("@@ ACCEPTS");
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
