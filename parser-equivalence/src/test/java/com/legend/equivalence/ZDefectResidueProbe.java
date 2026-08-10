package com.legend.equivalence;

import org.finos.legend.engine.language.pure.grammar.from.PureGrammarParser;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.TreeMap;

/** PROBE: the strict surface's verdict over every ORACLE-DEFECT row (the
 *  oracle crashed instead of refusing) — a DIALECT-named strict refusal
 *  proves the row's construct. Diagnostic only. */
class ZDefectResidueProbe {

    @Test
    void strictVerdictPerDefectRow() throws Exception {
        PureGrammarParser oracle = PureGrammarParser.newInstance();
        java.util.List<Corpus.Source> all = new java.util.ArrayList<>(
                Corpus.all());
        all.addAll(InlineSnippets.extract(java.nio.file.Path.of(
                System.getProperty("legend.engine.root")), "ie"));
        all.addAll(InlineSnippets.extract(java.nio.file.Path.of(
                System.getProperty("legend.pure.root")), "ip"));
        Map<String, Integer> verdicts = new TreeMap<>();
        int accepts = 0;
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
            String m = String.valueOf(root.getMessage());
            boolean crash = root instanceof NullPointerException
                    || m.contains("Cannot invoke")
                    || m.contains("NullPointerException")
                    || m.contains("please notify developer")
                    || m.contains("under radix") || "null".equals(m);
            if (!crash) {
                continue;
            }
            try {
                com.legend.parser.ElementParser.parse(src.text());
            } catch (Throwable ours) {
                continue;               // both fail — not a leniency row
            }
            String verdict;
            try {
                com.legend.parser.ElementParser.parseStrict(src.text());
                verdict = "STRICT-ACCEPTS";
                accepts++;
                if (accepts <= 12) {
                    System.out.println("@@ ACCEPT-ROW " + src.id());
                }
            } catch (Throwable t) {
                String sm = String.valueOf(t.getMessage());
                verdict = sm.contains("not authorized") ? "dialect:generics"
                        : sm.contains("is not supported yet")
                                ? "dialect:function-types"
                        : sm.contains(".allVersionsInRange")
                                ? "dialect:milestoning"
                        : sm.contains("Unsupported syntax")
                                ? "dialect:native-or-m2"
                        : "strict-refuses:other";
            }
            verdicts.merge(verdict, 1, Integer::sum);
        }
        System.out.println("@@ defect-row strict verdicts: " + verdicts);
    }
}
