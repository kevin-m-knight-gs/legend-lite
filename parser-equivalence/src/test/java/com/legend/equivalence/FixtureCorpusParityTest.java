package com.legend.equivalence;

import org.finos.legend.engine.language.pure.grammar.from.PureGrammarParser;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Ratchets the GRAMMAR-DRIVEN fixture corpus (scripts/parser) against legend-lite.
 *
 * Distinct from {@link RejectionParityTest}, and the distinction is the point. That corpus
 * is harvested from upstream's own test files, so it covers what upstream happened to write.
 * This one was generated from the GRAMMAR — every keyword legend-engine declares and a user
 * can reach — which is why it found nineteen constructs legend-lite does not implement, all
 * of which legend-lite itself describes as "corpus-censused". A corpus cannot find what it
 * was derived from.
 *
 * Two populations, two meanings:
 *
 *   POSITIVES  the engine accepts all 51. lite rejecting one is a MISSING CONSTRUCT.
 *   NEGATIVES  the engine rejects all 215, each pinning its reason. lite accepting one is
 *              OVER-PERMISSIVENESS -- it would load a model the reference refuses, and
 *              nothing fails until that model reaches an engine that cares.
 *
 * Verdicts are compared; message TEXT is not. Two parsers can refuse the same input for the
 * same reason and word it differently. Message parity is a separate question with its own
 * harness, and asking it before the verdicts agree buries the real queue in noise.
 *
 * The baselines below are a RATCHET: raise them when a divergence is closed, never lower
 * them to make a run green. The corpus-size floors exist because agreement counts alone can
 * be improved by deleting fixtures.
 */
class FixtureCorpusParityTest {

    /* -------------------------------------------------------------------------------- *
     * Baselines. Raise on improvement; a drop is a regression and should fail.
     * -------------------------------------------------------------------------------- */

    /** Fixtures the engine accepts and lite also accepts. 37 of 51 on 2026-08-14. */
    private static final int MIN_POSITIVE_AGREEMENT = 37;

    /** Fixtures the engine rejects and lite also rejects. 179 of 215 on 2026-08-14.
     *
     *  Both baselines were first set from a STALE build of core -- 32 and 164 -- and were
     *  wrong in the direction that flatters this harness and slanders legend-lite. Rebuild
     *  core before trusting a number here; `mvn -o -pl core install -DskipTests` is what
     *  refreshes what this test actually links against, and a merge that touches core will
     *  not do it for you.
     *
     *  Baselines belong to the environment that asserts them. scripts/parser/parity.py can
     *  report slightly different figures for the same corpus because it drives
     *  tools/engine-runner's classpath, which carries every published extension, while this
     *  module's oracle is deliberately production-shaped. Neither is wrong; copying one into
     *  the other produces a red build with no defect behind it. */
    private static final int MIN_NEGATIVE_AGREEMENT = 179;

    /** Corpus floors -- agreement can otherwise be "improved" by deleting fixtures. */
    private static final int MIN_POSITIVES = 51;
    private static final int MIN_NEGATIVES = 215;

    private static Path repoRoot() {
        Path p = Path.of("").toAbsolutePath();
        while (p != null && !Files.isDirectory(p.resolve("scripts/parser/fixtures"))) {
            p = p.getParent();
        }
        if (p == null) {
            throw new IllegalStateException("cannot locate scripts/parser from "
                    + Path.of("").toAbsolutePath());
        }
        return p;
    }

    private static List<Path> corpus(String dir) throws IOException {
        try (Stream<Path> s = Files.walk(repoRoot().resolve(dir))) {
            return s.filter(f -> f.toString().endsWith(".pure"))
                    .sorted(Comparator.naturalOrder()).toList();
        }
    }

    /** Empty when accepted, else the rejection message. */
    private static String engineVerdict(String src) {
        try {
            PureGrammarParser.newInstance().parseModel(src);
            return "";
        } catch (Exception e) {
            return message(e);
        }
    }

    private static String liteVerdict(String src) {
        try {
            com.legend.parser.PmcdParser.parseDocument(src);
            return "";
        } catch (Throwable t) {
            // Throwable, not Exception: a parser under construction can fail with
            // StackOverflowError, and reporting that as a harness crash rather than a
            // rejection would lose the result entirely.
            return message(t);
        }
    }

    private static String message(Throwable t) {
        String m = t.getMessage();
        if (m == null || m.isBlank()) {
            m = t.getClass().getSimpleName();
        }
        return m.replaceAll("\\s+", " ").trim();
    }

    /** name -> reason, from scripts/parser/parity-quarantine.tsv. A quarantine entry is a
     *  promise that somebody looked, not a way to make a number go green. */
    private static Map<String, String> quarantine() throws IOException {
        Path f = repoRoot().resolve("scripts/parser/parity-quarantine.tsv");
        Map<String, String> out = new LinkedHashMap<>();
        if (!Files.isRegularFile(f)) {
            return out;
        }
        for (String line : Files.readAllLines(f)) {
            if (line.startsWith("#") || line.isBlank()) {
                continue;
            }
            int tab = line.indexOf('\t');
            out.put(tab < 0 ? line.trim() : line.substring(0, tab).trim(),
                    tab < 0 ? "" : line.substring(tab + 1).trim());
        }
        return out;
    }

    @Test
    void positivesLiteAcceptsWhatEngineAccepts() throws IOException {
        List<Path> files = corpus("scripts/parser/fixtures");
        assertTrue(files.size() >= MIN_POSITIVES,
                "positive corpus shrank: " + files.size() + " < " + MIN_POSITIVES);

        Map<String, String> quarantined = quarantine();
        List<String> engineRejected = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        int agree = 0;

        for (Path f : files) {
            String src = Files.readString(f);
            String name = f.getFileName().toString();
            if (!engineVerdict(src).isEmpty()) {
                // The engine must accept every positive; if it does not, the FIXTURE is
                // broken, which is fixtures.py's job to catch. Recorded, not silently
                // treated as agreement.
                engineRejected.add(name);
                continue;
            }
            String lite = liteVerdict(src);
            if (lite.isEmpty()) {
                agree++;
            } else if (!quarantined.containsKey(name)) {
                missing.add(name + "  ->  " + lite);
            }
        }

        assertTrue(engineRejected.isEmpty(),
                "engine rejected a POSITIVE fixture -- the fixture is broken, not lite: "
                        + engineRejected);
        assertTrue(agree >= MIN_POSITIVE_AGREEMENT,
                "positive parity regressed: " + agree + " < " + MIN_POSITIVE_AGREEMENT
                        + "\nconstructs legend-lite does not implement:\n  "
                        + String.join("\n  ", missing));
    }

    @Test
    void negativesLiteRejectsWhatEngineRejects() throws IOException {
        List<Path> files = corpus("scripts/parser/negative");
        assertTrue(files.size() >= MIN_NEGATIVES,
                "negative corpus shrank: " + files.size() + " < " + MIN_NEGATIVES);

        Map<String, String> quarantined = quarantine();
        List<String> engineAccepted = new ArrayList<>();
        List<String> overPermissive = new ArrayList<>();
        int agree = 0;

        for (Path f : files) {
            String src = Files.readString(f);
            String name = f.getFileName().toString();
            String engine = engineVerdict(src);
            if (engine.isEmpty()) {
                engineAccepted.add(name);
                continue;
            }
            if (!liteVerdict(src).isEmpty()) {
                agree++;
            } else if (!quarantined.containsKey(name)) {
                overPermissive.add(name + "  (engine: " + trim(engine) + ")");
            }
        }

        assertTrue(engineAccepted.isEmpty(),
                "engine ACCEPTED a negative fixture -- either the construct started working "
                        + "upstream or the fixture never tested what it claimed: "
                        + engineAccepted);
        assertTrue(agree >= MIN_NEGATIVE_AGREEMENT,
                "negative parity regressed: " + agree + " < " + MIN_NEGATIVE_AGREEMENT
                        + "\ninputs legend-lite accepts and legend-engine refuses:\n  "
                        + String.join("\n  ", overPermissive));
    }

    private static String trim(String s) {
        return s.length() > 80 ? s.substring(0, 80) + "..." : s;
    }
}
