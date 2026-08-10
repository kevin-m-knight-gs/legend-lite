package com.legend.equivalence;

import org.finos.legend.engine.language.pure.grammar.from.PureGrammarParser;
import org.junit.jupiter.api.Test;

/** PROBE: structural diff of one PMCD divergence. Diagnostic only. */
class ZPmcdDiffProbe {

    @Test
    void diffOne() throws Exception {
        var mapper = org.finos.legend.engine.shared.core.ObjectMapperFactory
                .getNewStandardObjectMapperWithPureProtocolExtensionSupports();
        for (Corpus.Source src : InlineSnippets.extract(java.nio.file.Path.of(
                System.getProperty("legend.engine.root")), "ie")) {
            if (!src.id().endsWith("TestFlatDataBindingCompilation.java#0")) {
                continue;
            }
            String expected = mapper.writeValueAsString(
                    PureGrammarParser.newInstance().parseModel(src.text()));
            String actual = com.legend.parser.PmcdParser
                    .parseDocument(src.text());
            var a = mapper.readTree(expected);
            var b = mapper.readTree(actual);
            System.out.println("@@ SOURCE:");
            System.out.println(src.text().replaceAll("(?m)^", "|"));
            for (int i = 0; i < a.get("elements").size(); i++) {
                var ea = a.get("elements").get(i);
                var eb = b.get("elements").size() > i
                        ? b.get("elements").get(i) : null;
                if (!ea.equals(eb)) {
                    System.out.println("@@ DIVERGES elements[" + i + "] type="
                            + ea.get("_type"));
                    System.out.println("@@ expected: "
                            + mapper.writeValueAsString(ea));
                    System.out.println("@@ actual:   "
                            + (eb == null ? "null"
                                    : mapper.writeValueAsString(eb)));
                }
            }
            break;
        }
    }
}
