package com.legend.equivalence;

import org.finos.legend.engine.language.pure.grammar.from.PureGrammarParser;
import org.junit.jupiter.api.Test;

/** PROBE (W10-L3): what the 11 MISSED rows' sources actually contain and
 *  what the oracle does with them. Diagnostic only. */
class ZMissedRowsProbe {

    @Test
    void missedRowSources() throws Exception {
        java.util.List<Corpus.Source> all = new java.util.ArrayList<>();
        all.addAll(InlineSnippets.extract(java.nio.file.Path.of(
                System.getProperty("legend.engine.root")), "inline-engine"));
        all.addAll(InlineSnippets.extract(java.nio.file.Path.of(
                System.getProperty("legend.pure.root")), "inline-pure"));
        for (Corpus.Source src : all) {
            String id = src.id();
            if (!(id.contains("TestDocumentation")
                    || id.contains("TestProfile.java"))) {
                continue;
            }
            String verdict;
            int elements = 0;
            try {
                var pmcd = PureGrammarParser.newInstance()
                        .parseModel(src.text());
                elements = pmcd.getElements().size();
                verdict = "ORACLE-ACCEPTS(" + elements + ")";
            } catch (Throwable t) {
                String m = String.valueOf(t.getMessage())
                        .replaceAll("\\s+", " ");
                verdict = "ORACLE-REJECTS: "
                        + m.substring(0, Math.min(60, m.length()));
            }
            String ours;
            try {
                com.legend.parser.ElementParser.parsePlatform(src.text());
                ours = "LITE-ACCEPTS";
            } catch (Throwable t) {
                ours = "LITE-REFUSES: " + String.valueOf(t.getMessage())
                        .replaceAll("\\s+", " ");
            }
            System.out.println("## " + id + " :: " + verdict + " :: " + ours);
            if (ours.startsWith("LITE-REFUSES") && elements > 0) {
                System.out.println(src.text().replaceAll("(?m)^", "|"));
                var mapper = org.finos.legend.engine.shared.core
                        .ObjectMapperFactory
                        .getNewStandardObjectMapperWithPureProtocolExtensionSupports();
                var pmcd = PureGrammarParser.newInstance()
                        .parseModel(src.text());
                for (var e : pmcd.getElements()) {
                    if (!e.getPath().contains("SectionIndex")) {
                        System.out.println("WIRE " + e.getPath() + " "
                                + mapper.writeValueAsString(e));
                    }
                }
            }
        }
    }
}
