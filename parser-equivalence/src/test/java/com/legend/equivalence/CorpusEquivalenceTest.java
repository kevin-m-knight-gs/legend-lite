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
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The gate: run both parsers over the real corpus and compare emitted bytes.
 *
 * <h2>Anti-false-green rules, and why each exists</h2>
 * <ol>
 *   <li><b>A run that did no work fails.</b> A harness of mine once scored a {@code BUILD FAILURE}
 *       as "0 failures" because it counted failures in a report that was never written.</li>
 *   <li><b>Every comparison asserts a non-zero input count.</b></li>
 *   <li><b>Coverage is reported, not assumed</b> — including what was skipped and why.</li>
 *   <li><b>The corpus may not shrink.</b> A baseline is committed; if fewer elements are compared
 *       than last time, that is a failure, not a quieter green.</li>
 * </ol>
 *
 * <p><b>DIFF is the only outcome that fails.</b> WALL means we refused to emit something, loudly
 * and by name — expected while coverage grows, and the report ranks those walls into the worklist.
 */
class CorpusEquivalenceTest {

    /** Bumped deliberately as coverage grows. Lowering it requires saying why in the commit. */
    private static final int MIN_ELEMENTS_COMPARED = 15269;
    private static final int MIN_MATCHES = 15269;

    @Test
    void legendLiteEmitsByteIdenticalProtocolForEveryClassItClaims() throws Exception {
        List<Corpus.Source> sources = Corpus.all();
        Assumptions.assumeTrue(!sources.isEmpty(),
                "no corpus on disk — set -Dlegend.engine.root / -Dlegend.pure.root");

        ParserEquivalence eq = new ParserEquivalence();
        List<Verdict> all = new ArrayList<>();
        for (Corpus.Source s : sources) {
            all.addAll(eq.compare(s));
        }

        Map<Kind, Integer> counts = new EnumMap<>(Kind.class);
        for (Kind k : Kind.values()) {
            counts.put(k, 0);
        }
        Map<String, Integer> walls = new TreeMap<>();
        List<Verdict> diffs = new ArrayList<>();
        for (Verdict v : all) {
            counts.merge(v.kind(), 1, Integer::sum);
            if (v.kind() == Kind.WALL) {
                walls.merge(ParserEquivalence.rule(v.detail()), 1, Integer::sum);
            } else if (v.kind() == Kind.DIFF) {
                diffs.add(v);
            }
        }

        int compared = counts.get(Kind.MATCH) + counts.get(Kind.DIFF);
        String report = report(sources.size(), all.size(), counts, compared, walls, diffs);
        Files.writeString(Path.of("target", "equivalence-report.txt"), report);
        // per-wall detail — the burn-down worklist, one line per walled element
        StringBuilder wd = new StringBuilder();
        StringBuilder pf = new StringBuilder();
        for (Verdict v : all) {
            if (v.kind() == Kind.WALL) {
                wd.append(ParserEquivalence.rule(v.detail())).append('\t')
                        .append(v.sourceId()).append('\t').append(v.element()).append('\n');
            } else if (v.kind() == Kind.PARSE_FAIL) {
                pf.append(v.detail().replaceAll("\\s+", " ")).append('\t')
                        .append(v.sourceId()).append('\t').append(v.element()).append('\n');
            }
        }
        Files.writeString(Path.of("target", "walls-detail.txt"), wd.toString());
        Files.writeString(Path.of("target", "parsefails-detail.txt"), pf.toString());
        System.out.println(report);

        // (1) the run must have done work — an empty run is a failure, never a pass
        assertTrue(all.size() > 0, "no verdicts produced: the harness did not run");
        // (2) non-zero comparisons
        assertTrue(compared > 0, "nothing was actually compared");
        // (4) the corpus may not shrink
        assertTrue(all.size() >= MIN_ELEMENTS_COMPARED,
                "corpus shrank: " + all.size() + " verdicts < baseline " + MIN_ELEMENTS_COMPARED
                        + ". A smaller corpus is a failure, not a quieter green.");
        assertTrue(counts.get(Kind.MATCH) >= MIN_MATCHES,
                "byte-identical matches regressed: " + counts.get(Kind.MATCH) + " < " + MIN_MATCHES);
        // the actual gate
        assertEquals(0, diffs.size(),
                "byte divergence on elements legend-lite claims to emit:\n"
                        + diffs.stream().limit(10)
                        .map(d -> "  " + d.sourceId() + " :: " + d.element() + "\n      " + d.detail())
                        .reduce("", (x, y) -> x + y + "\n"));
    }

    private static String report(int sources, int verdicts, Map<Kind, Integer> counts, int compared,
                                 Map<String, Integer> walls, List<Verdict> diffs) {
        StringBuilder b = new StringBuilder();
        b.append("PARSER EQUIVALENCE — legend-lite vs legend-engine, byte comparison\n")
                .append("=".repeat(72)).append('\n')
                .append(String.format("corpus sources        : %d files%n", sources))
                .append(String.format("verdicts              : %d%n", verdicts))
                .append(String.format("  MATCH (byte-equal)  : %d%n", counts.get(Kind.MATCH)))
                .append(String.format("  DIFF  (BUG)         : %d%n", counts.get(Kind.DIFF)))
                .append(String.format("  WALL  (no rule yet) : %d%n", counts.get(Kind.WALL)))
                .append(String.format("  PARSE_FAIL          : %d%n", counts.get(Kind.PARSE_FAIL)))
                .append(String.format("  REFERENCE_REJECTED  : %d%n", counts.get(Kind.REFERENCE_REJECTED)))
                .append(String.format("%ncoverage: %d of %d comparable (%.1f%%)%n",
                        counts.get(Kind.MATCH), compared,
                        compared == 0 ? 0.0 : 100.0 * counts.get(Kind.MATCH) / compared));
        b.append("\nWALLS — the ranked worklist\n").append("-".repeat(72)).append('\n');
        walls.entrySet().stream().sorted((x, y) -> y.getValue() - x.getValue()).limit(20)
                .forEach(e -> b.append(String.format("  %6d  %s%n", e.getValue(), e.getKey())));
        if (!diffs.isEmpty()) {
            b.append("\nDIFFS — real divergence, these are bugs\n").append("-".repeat(72)).append('\n');
            diffs.stream().limit(20).forEach(d ->
                    b.append("  ").append(d.sourceId()).append(" :: ").append(d.element())
                            .append("\n      ").append(d.detail()).append('\n'));
        }
        return b.toString();
    }
}
