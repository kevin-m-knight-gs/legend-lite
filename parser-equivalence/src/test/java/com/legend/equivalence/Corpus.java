package com.legend.equivalence;

import com.legend.testing.WalkedPath;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * Where the harness gets real input.
 *
 * <p>Roots are read <b>in place</b> from local checkouts, overridable by system property so CI can
 * point at pinned clones. If a root is missing the harness is skipped rather than silently passing
 * on an empty corpus — see {@code EquivalenceGuards}.
 *
 * <p>The corpora are the ones inventoried in {@code docs/PARSER_DROP_IN_PLAN.md} §3.2. This class
 * covers the <b>file-based</b> tiers (C3, C10); the ~3,191 inline {@code ###} snippets embedded in
 * Java test sources (C2) need a source-level extractor and are deliberately not faked here — a
 * corpus you cannot enumerate is a corpus you cannot report coverage for.
 */
public final class Corpus {

    private Corpus() {
    }

    public static Path engineRoot() {
        return Path.of(System.getProperty("legend.engine.root",
                System.getProperty("user.home") + "/legend/legend-engine"));
    }

    public static Path pureRoot() {
        return Path.of(System.getProperty("legend.pure.root",
                System.getProperty("user.home") + "/legend/legend-pure"));
    }

    /** One unit of input: a source file, its text, and where it came from. */
    public record Source(String id, String text, String tier) {
    }

    /** Every {@code .pure} under a root, excluding build output. */
    private static List<Path> pureFiles(Path root) {
        return filesWith(root, ".pure");
    }

    private static List<Path> filesWith(Path root, String ext) {
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        try (Stream<Path> s = Files.walk(root)) {
            return s.filter(p -> p.toString().endsWith(ext))
                    .filter(p -> !WalkedPath.containsSubPath(p, "target"))
                    .sorted()
                    .toList();
        } catch (IOException e) {
            throw new IllegalStateException("cannot walk corpus root " + root, e);
        }
    }

    /** C11 — Pure DOCUMENTS living in {@code .txt} resources (the
     *  persistence test-runner's models and scattered
     *  json/xml/lineage/graphQL fixtures): the first non-blank line is a
     *  {@code ###Section} header or a top-level declaration. 55 files at
     *  enrollment (2026-08-12); the oracle adjudicates each like every
     *  tier. */
    private static void addTxtDocuments(List<Source> out, Path root,
            String tier) {
        for (Path p : filesWith(root, ".txt")) {
            String t;
            try {
                t = Files.readString(p);
            } catch (Exception e) {
                // NOT counted in UNREADABLE: most .txt resources are not
                // Pure and non-UTF8 ones cannot be candidates
                continue;
            }
            String head = t.lines().filter(l -> !l.isBlank()).findFirst()
                    .orElse("").strip();
            if (head.matches("###\\w+")
                    || head.matches("(Class|Enum|Association|Profile"
                            + "|function|import)\\b.*")) {
                out.add(new Source(root.relativize(p).toString(), t, tier));
            }
        }
    }

    /** Files the loader could not read this run — COUNTED, never a silent
     *  {@code continue} (HARNESS_SIMPLIFICATION_PLAN Phase 6). */
    static final List<String> UNREADABLE = new ArrayList<>();

    /** Exact-text duplicates dropped by {@link #all()} this run. */
    static final java.util.concurrent.atomic.AtomicInteger DEDUPED =
            new java.util.concurrent.atomic.AtomicInteger();

    private static void add(List<Source> out, Path root, String tier, java.util.function.Predicate<String> accept) {
        for (Path p : pureFiles(root)) {
            String t;
            try {
                t = Files.readString(p);
            } catch (Exception e) {
                UNREADABLE.add(root.relativize(p) + " :: " + e);
                continue;
            }
            if (accept.test(t)) {
                out.add(new Source(root.relativize(p).toString(), t, tier));
            }
        }
    }

    /**
     * The full corpus.
     *
     * <p><b>C3 — EMIT models.</b> Upstream's multi-file, cross-referencing, user-shaped models; the
     * only tier that exercises cross-file behaviour, and the fastest-growing.
     * <b>C10 — standalone grammar files</b> from both checkouts.
     *
     * <p>{@code m3.pure} is excluded: 3,607 lines of {@code ^Root.children[...]} bootstrap-instance
     * syntax with zero normal declarations, which skews every count.
     */
    /** C6: the committed engine-fixture snapshot (see the harvest note
     *  in {@link #all()}); empty when the resource is absent. */
    static List<Source> engineFixtures() {
        List<Source> out = new ArrayList<>();
        java.nio.file.Path p = java.nio.file.Path.of("src/test/resources/"
                + "engine-grammar-fixtures-4.138.2.jsonl");
        if (!java.nio.file.Files.exists(p)) {
            return out;
        }
        try {
            var om = new com.fasterxml.jackson.databind.ObjectMapper();
            int i = 0;
            for (String line : java.nio.file.Files.readAllLines(p)) {
                out.add(new Source("engine-fixture#" + (i++),
                        om.readTree(line).get("source").asText(),
                        "C6 engine-fixtures"));
            }
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
        return out;
    }

    public static List<Source> all() {
        List<Source> out = new ArrayList<>();
        // the WHOLE engine checkout — every module's .pure, not a curated subset
        // (the three-root cut left 1,161 files in other xts modules invisible)
        add(out, engineRoot(), "C3/C10 engine", t -> true);
        add(out, pureRoot(), "C10 pure", t -> true);
        // C4/C5 — Pure snippets embedded in upstream Java TEST sources; the reference
        // parser adjudicates every candidate, so extraction is tolerant by design
        out.addAll(InlineSnippets.extract(engineRoot(), "C4 engine-inline"));
        // C12 — section-only documents in test Java (no domain decl, so C4's
        // pinned pattern never saw them: the DB-flavor/auth scope hole)
        out.addAll(InlineSnippets.extract(engineRoot(),
                "C12 section-doc-inline", InlineSnippets.SECTION_DOC));
        out.addAll(InlineSnippets.extract(pureRoot(), "C5 pure-inline"));
        // C6 — the engine's own grammar-test fixtures, harvested by
        // EXECUTION (ZEngineFixtureHarvest: the 4.138.2 tests-jars run
        // under recording shims; snapshot committed). The per-production
        // coverage the static tiers cannot see — the Binding transformer
        // hid here. Adjudicated like every tier: the oracle decides.
        out.addAll(engineFixtures());
        // C11 — Pure documents in .txt resources (resource sweep,
        // 2026-08-12); legend-pure has none (measured)
        addTxtDocuments(out, engineRoot(), "C11 resource-txt");
        out.removeIf(s -> s.id().toLowerCase(Locale.ROOT).endsWith("grammar/m3.pure"));
        // DEDUPE by exact text, first occurrence wins (tier order above):
        // the reported count IS the distinct count — the audit measured a
        // 6.6% inflation from fixture rows duplicating inline snippets
        // verbatim (HARNESS_SIMPLIFICATION_PLAN Phase 6)
        java.util.Set<String> seen = new java.util.HashSet<>();
        int before = out.size();
        out.removeIf(s -> !seen.add(s.text()));
        DEDUPED.set(before - out.size());
        String only = System.getProperty("legend.corpus.containing");
        if (only != null) {
            // ITERATION ONLY — a section leg's inner loop. The ratchet gate is
            // the FULL sweep; a filtered run cannot raise it (the test's own
            // MIN_ELEMENTS_COMPARED floor fails long before it could).
            out.removeIf(s -> !s.text().contains(only));
        }
        return out;
    }
}
