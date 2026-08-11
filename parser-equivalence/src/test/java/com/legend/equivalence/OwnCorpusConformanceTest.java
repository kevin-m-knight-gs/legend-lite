package com.legend.equivalence;

import org.finos.legend.engine.language.pure.grammar.from.PureGrammarParser;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * THE MIRROR CORPUS (deep audit): the same Java-literal extractor that
 * harvests Pure from the ENGINE'S tests, run over OUR OWN test sources —
 * every Pure snippet legend-lite's core/engine/pct tests embed goes to
 * the REAL engine oracle. A refusal must classify exactly like a corpus
 * leniency row (the dialect constructs our tests deliberately exercise);
 * an UNCLASSIFIED refusal means our own test surface bakes in grammar
 * that is neither engine nor named pure-dialect — a lite-only invention,
 * and a build failure here.
 */
class OwnCorpusConformanceTest {

    @Test
    void ourOwnTestPureIsRealLegend() throws Exception {
        PureGrammarParser oracle = PureGrammarParser.newInstance();
        Path repo = Path.of(System.getProperty("user.dir")).getParent();
        List<Corpus.Source> ours = new ArrayList<>();
        for (String module : new String[]{"core", "engine", "pct", "nlq"}) {
            ours.addAll(InlineSnippets.extract(repo.resolve(module),
                    "lite-" + module));
        }
        Map<String, Integer> byClass = new TreeMap<>();
        List<String> unclassified = new ArrayList<>();
        int accepted = 0;
        int bothRefuse = 0;
        StringBuilder report = new StringBuilder();
        for (Corpus.Source src : ours) {
            Throwable refusal;
            try {
                oracle.parseModel(src.text());
                accepted++;
                continue;
            } catch (Throwable t) {
                refusal = t;
            }
            try {
                com.legend.parser.ElementParser.parse(src.text());
            } catch (Throwable oursToo) {
                bothRefuse++;
                continue;       // we refuse it too — a negative fixture
            }
            Throwable root = refusal;
            while (root.getCause() != null && root.getCause() != root) {
                root = root.getCause();
            }
            String cls = LeniencyCatalogTest.classify(root, src.text());
            String msg = String.valueOf(root.getMessage())
                    .replaceAll("\\s+", " ");
            if (cls == null) {
                unclassified.add(src.id() + " :: " + msg);
                cls = "UNCLASSIFIED";
            }
            byClass.merge(cls, 1, Integer::sum);
            report.append(cls).append('\t').append(src.id()).append('\t')
                    .append(msg, 0, Math.min(160, msg.length()))
                    .append('\n');
        }
        java.nio.file.Files.createDirectories(Path.of("target"));
        java.nio.file.Files.writeString(
                Path.of("target", "own-corpus-conformance.txt"),
                report.toString());
        System.out.println("own-corpus: " + ours.size() + " snippets, "
                + accepted + " oracle-accepted, " + bothRefuse
                + " both-refuse, refusal classes: " + byClass);
        assertTrue(unclassified.isEmpty(),
                () -> "OUR OWN test Pure contains grammar that is neither"
                        + " engine nor named pure-dialect (lite-only"
                        + " inventions — fix the tests or the parser):\n  "
                        + String.join("\n  ", unclassified.subList(0,
                                Math.min(25, unclassified.size()))));
    }
}
