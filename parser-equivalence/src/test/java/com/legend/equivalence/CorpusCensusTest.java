package com.legend.equivalence;

import org.finos.legend.engine.language.pure.grammar.from.PureGrammarParser;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * THE HONEST DENOMINATOR (merge-readiness audit §4.1/§4.2, "the denominator
 * is constructed"). The parity gate reports 100% of what it compares; this
 * census reports what it never compares, and why, over the WHOLE corpus
 * rather than the sentinel's section-scoped 1,114 files.
 *
 * <p>Every source lands in exactly one bucket:
 *
 * <ul>
 *   <li><b>BOTH-PARSE</b> — the comparable population.</li>
 *   <li><b>OUR-DEFECT</b> — the reference accepts it and we fail. These are
 *       drop-in defects and nothing else; this is the parse-more worklist,
 *       ranked by error so families are visible.</li>
 *   <li><b>REFERENCE-REFUSES</b> — the reference cannot parse it either, so
 *       it can never enter the denominator. Ranked by the REFERENCE's own
 *       message, which is what tells an honest story: a file written in
 *       another tool's dialect (legend-pure's ###Diagram, whose grammar has
 *       typeView where the engine's has classView) is correctly excluded,
 *       whereas a bucket of ordinary Pure would mean the corpus is quietly
 *       shrinking around our gaps.</li>
 * </ul>
 *
 * <p>Reports only — this test asserts nothing about the counts, because its
 * job is to make the number visible, not to freeze it. The ratchets live in
 * {@link CorpusEquivalenceTest} and {@link SectionParseSentinelTest}.
 */
class CorpusCensusTest {

    @Test
    void reportWhatTheGateNeverCompares() throws Exception {
        List<Corpus.Source> sources = Corpus.all();
        Assumptions.assumeTrue(!sources.isEmpty(),
                "no corpus on disk — set -Dlegend.engine.root / -Dlegend.pure.root");

        PureGrammarParser reference = PureGrammarParser.newInstance();
        int bothParse = 0;
        int ourDefect = 0;
        int referenceRefuses = 0;
        Map<String, Integer> defectsByMessage = new TreeMap<>();
        Map<String, Integer> refusalsByReferenceMessage = new TreeMap<>();
        Map<String, Integer> defectsByTier = new TreeMap<>();
        List<String> defectLines = new ArrayList<>();

        for (Corpus.Source src : sources) {
            boolean referenceAccepts;
            String referenceMessage = "";
            try {
                reference.parseModel(src.text());
                referenceAccepts = true;
            } catch (Throwable t) {
                referenceAccepts = false;
                referenceMessage = normalize(t);
            }
            if (!referenceAccepts) {
                referenceRefuses++;
                refusalsByReferenceMessage.merge(referenceMessage, 1, Integer::sum);
                continue;
            }
            try {
                com.legend.parser.ElementParser.parse(src.text());
                bothParse++;
            } catch (Throwable t) {
                ourDefect++;
                String msg = normalize(t);
                defectsByMessage.merge(msg, 1, Integer::sum);
                defectsByTier.merge(src.tier(), 1, Integer::sum);
                defectLines.add(src.id() + " :: " + msg);
            }
        }

        int comparable = bothParse + ourDefect;
        StringBuilder b = new StringBuilder();
        b.append("CORPUS CENSUS — what the parity gate never compares, and why\n")
                .append("=".repeat(72)).append('\n')
                .append(String.format("corpus sources          : %d%n", sources.size()))
                .append(String.format("  BOTH-PARSE            : %d%n", bothParse))
                .append(String.format("  OUR-DEFECT            : %d"
                        + "  (reference accepts, we fail)%n", ourDefect))
                .append(String.format("  REFERENCE-REFUSES     : %d"
                        + "  (never comparable, at any effort)%n", referenceRefuses))
                .append(String.format("%nreachable population    : %d of %d sources (%.1f%%)%n",
                        comparable, sources.size(),
                        100.0 * comparable / sources.size()))
                .append(String.format("we parse                : %d of %d reachable (%.1f%%)%n",
                        bothParse, comparable,
                        comparable == 0 ? 0.0 : 100.0 * bothParse / comparable));

        b.append("\nOUR-DEFECT by error — THE PARSE-MORE WORKLIST\n")
                .append("-".repeat(72)).append('\n');
        defectsByMessage.entrySet().stream()
                .sorted((x, y) -> y.getValue() - x.getValue())
                .forEach(e -> b.append(String.format("  %5d  %s%n",
                        e.getValue(), e.getKey())));

        b.append("\nOUR-DEFECT by corpus tier\n").append("-".repeat(72)).append('\n');
        defectsByTier.entrySet().stream()
                .sorted((x, y) -> y.getValue() - x.getValue())
                .forEach(e -> b.append(String.format("  %5d  %s%n",
                        e.getValue(), e.getKey())));

        b.append("\nREFERENCE-REFUSES by the REFERENCE's own error\n")
                .append("-".repeat(72)).append('\n');
        refusalsByReferenceMessage.entrySet().stream()
                .sorted((x, y) -> y.getValue() - x.getValue())
                .limit(40)
                .forEach(e -> b.append(String.format("  %5d  %s%n",
                        e.getValue(), e.getKey())));

        Files.writeString(Path.of("target", "corpus-census.txt"), b.toString());
        Files.writeString(Path.of("target", "corpus-census-defects.txt"),
                String.join("\n", defectLines));
        System.out.println(b);
    }

    private static String normalize(Throwable t) {
        Throwable root = t;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        String msg = String.valueOf(root.getMessage()).replaceAll("\\s+", " ");
        // positions differ per file; the SHAPE of the error is the bucket
        msg = msg.replaceAll("\\[\\d+:\\d+\\]", "[L:C]")
                .replaceAll("line \\d+", "line N")
                .replaceAll("column \\d+", "column N");
        return msg.substring(0, Math.min(110, msg.length()));
    }
}
