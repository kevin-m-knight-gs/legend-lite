package com.legend.equivalence;

import com.legend.equivalence.ParserEquivalence.Kind;
import com.legend.equivalence.ParserEquivalence.Verdict;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The element-level DIAGNOSTIC sweep, demoted from a gate
 * (HARNESS_SIMPLIFICATION_PLAN Phase 3). The load-bearing byte claim is
 * {@code PmcdEquivalenceTest}'s whole-document comparison; this test's
 * job is (a) the report that localises any future document failure to an
 * element index and JSON path, and (b) the PHASE-2 JOINT PROPERTY — no
 * source where the document comparison passes and the positional element
 * comparison fails — which is the standing proof that demotion lost
 * nothing.
 *
 * <p>The retired ratchets (MIN_ELEMENTS_COMPARED 25,472 / MIN_MATCHES /
 * MAX_WALLS / MAX_PARSE_FAILS / MAX_LITE_MISSED) and their bump history
 * live in git — final ledger state before demotion: 30,410 MATCH /
 * 0 DIFF / 0 WALL / 0 everything else over 32,631 verdicts
 * (commit 89c5907d).
 */
class CorpusEquivalenceTest {

    @Test
    void elementDiagnosticAndJointProperty() throws Exception {
        List<Corpus.Source> sources = Corpus.all();
        Assumptions.assumeTrue(!sources.isEmpty(),
                "no corpus on disk — set -Dlegend.engine.root / -Dlegend.pure.root");

        ParserEquivalence eq = new ParserEquivalence();
        List<Verdict> all = new ArrayList<>();
        int docPassElementFail = 0;
        List<String> jointViolations = new ArrayList<>();
        var mapper = org.finos.legend.engine.shared.core.ObjectMapperFactory
                .getNewStandardObjectMapperWithPureProtocolExtensionSupports();
        for (Corpus.Source s : sources) {
            List<Verdict> vs = eq.compare(s);
            all.addAll(vs);
            // PHASE-2 JOINT PROPERTY: no source where the DOCUMENT
            // comparison passes and the ELEMENT comparison fails — the
            // demonstration that the element gate is implied by the
            // document gate, measured not argued (zero at demotion)
            boolean elementFail = vs.stream().anyMatch(v ->
                    v.kind() != Kind.MATCH
                            && v.kind() != Kind.REFERENCE_REJECTED);
            if (elementFail) {
                boolean docPass;
                try {
                    docPass = Comparators.sameBytes(
                            mapper.writeValueAsString(OracleParses.acquire(s)),
                            com.legend.parser.PmcdParser.parseDocument(s.text()));
                } catch (Throwable t) {
                    docPass = false;
                }
                if (docPass) {
                    docPassElementFail++;
                    jointViolations.add(s.id());
                }
            }
        }

        Map<Kind, Integer> counts = new EnumMap<>(Kind.class);
        for (Kind k : Kind.values()) {
            counts.put(k, 0);
        }
        List<Verdict> diffs = new ArrayList<>();
        for (Verdict v : all) {
            counts.merge(v.kind(), 1, Integer::sum);
            if (v.kind() == Kind.DIFF) {
                diffs.add(v);
            }
        }
        String report = report(sources.size(), all, counts, diffs);
        Files.writeString(Path.of("target", "equivalence-report.txt"), report);
        StringBuilder pf = new StringBuilder();
        for (Verdict v : all) {
            if (v.kind() == Kind.PARSE_FAIL) {
                pf.append(v.detail().replaceAll("\\s+", " ")).append('\t')
                        .append(v.sourceId()).append('\n');
            }
        }
        Files.writeString(Path.of("target", "parsefails-detail.txt"), pf.toString());
        System.out.println(report);

        // a run that did no work is a failure, never a pass
        assertTrue(all.size() > 0, "no verdicts produced: the harness did not run");
        assertEquals(0, docPassElementFail,
                () -> "JOINT-PROPERTY violation — the document gate passed"
                        + " where the element comparison failed (the"
                        + " implication argument is broken):\n  "
                        + String.join("\n  ", jointViolations.subList(0,
                                Math.min(10, jointViolations.size()))));
    }

    private static String report(int sources, List<Verdict> all,
            Map<Kind, Integer> counts, List<Verdict> diffs) {
        StringBuilder b = new StringBuilder();
        b.append("PARSER EQUIVALENCE — element diagnostic (document gate is the claim)\n")
                .append("=".repeat(72)).append('\n')
                .append(String.format("corpus sources        : %d files%n", sources))
                .append(String.format("verdicts              : %d%n", all.size()))
                .append(String.format("  MATCH (byte-equal)  : %d%n", counts.get(Kind.MATCH)))
                .append(String.format("  DIFF                : %d%n", counts.get(Kind.DIFF)))
                .append(String.format("  PARSE_FAIL          : %d%n", counts.get(Kind.PARSE_FAIL)))
                .append(String.format("  REFERENCE_REJECTED  : %d files%n",
                        counts.get(Kind.REFERENCE_REJECTED)));
        if (!diffs.isEmpty()) {
            b.append("\nDIFF rows — element index + first divergent JSON path\n")
                    .append("-".repeat(72)).append('\n');
            diffs.stream().limit(30).forEach(d ->
                    b.append("  ").append(d.sourceId()).append(" :: ").append(d.element())
                            .append("\n      ").append(d.detail()).append('\n'));
        }
        return b.toString();
    }
}
