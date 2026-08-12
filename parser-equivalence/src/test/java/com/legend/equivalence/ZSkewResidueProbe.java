package com.legend.equivalence;

import org.finos.legend.engine.language.pure.grammar.from.PureGrammarParser;
import org.junit.jupiter.api.Test;

/** PROBE (W10 re-adjudication): for every remaining "Unexpected token"
 *  leniency row, what does OUR strict surface say? A DIALECT-named strict
 *  refusal verifies the construct row by row. Diagnostic only. */
class ZSkewResidueProbe {

    @Test
    void strictVerdictPerSkewRow() throws Exception {
        PureGrammarParser oracle = PureGrammarParser.newInstance();
        java.util.List<Corpus.Source> all = new java.util.ArrayList<>(
                Corpus.all());
        all.addAll(InlineSnippets.extract(java.nio.file.Path.of(
                System.getProperty("legend.engine.root")), "ie"));
        all.addAll(InlineSnippets.extract(java.nio.file.Path.of(
                System.getProperty("legend.pure.root")), "ip"));
        for (Corpus.Source src : all) {
            Throwable refusal;
            try {
                oracle.parseModel(src.text());
                continue;
            } catch (Throwable t) {
                refusal = t;
            }
            Throwable root = refusal;
            while (root.getCause() != null && root.getCause() != root) {
                root = root.getCause();
            }
            if (!"Unexpected token".equals(String.valueOf(root.getMessage())
                    .trim())) {
                continue;
            }
            try {
                com.legend.parser.ElementParser.parse(src.text(), com.legend.parser.Dialect.LEGEND_PLATFORM);
            } catch (Throwable ours) {
                continue;               // both refuse — not a leniency row
            }
            String strict;
            try {
                com.legend.parser.ElementParser.parse(src.text(), com.legend.parser.Dialect.LEGEND_ENGINE);
                strict = "STRICT-ACCEPTS";
            } catch (Throwable t) {
                strict = "STRICT-REFUSES: " + String.valueOf(t.getMessage())
                        .replaceAll("\\s+", " ");
            }
            System.out.println("@@ " + src.id() + " :: " + strict);
        }
    }
}
