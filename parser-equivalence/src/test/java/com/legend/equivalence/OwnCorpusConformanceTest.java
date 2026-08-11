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
 * every Pure snippet legend-lite's core/pct/nlq tests embed goes to
 * the REAL engine oracle. A refusal must classify exactly like a corpus
 * leniency row (the dialect constructs our tests deliberately exercise);
 * an UNCLASSIFIED refusal means our own test surface bakes in grammar
 * that is neither engine nor named pure-dialect — a lite-only invention,
 * and a build failure here.
 */
class OwnCorpusConformanceTest {

    private static List<Corpus.Source> ownSnippets() {
        Path repo = Path.of(System.getProperty("user.dir")).getParent();
        List<Corpus.Source> ours = new ArrayList<>();
        for (String module : new String[]{"core", "pct", "nlq"}) {
            ours.addAll(InlineSnippets.extract(repo.resolve(module),
                    "lite-" + module, InlineSnippets.OWN_DECL));
        }
        return ours;
    }

    /**
     * SECTIONS-NORMALIZATION RATCHET: no test snippet declares a non-Pure
     * element (Database/Mapping/Runtime/Connection/Service/...) without its
     * {@code ###Section} header. The check is TOTAL (every extracted
     * snippet, oracle not consulted) and mechanical: a non-empty
     * {@link Sectionize} plan IS the violation — the engine binds element
     * kinds to sections, so a headerless one is a lite-only convenience
     * the strict flip would have to refuse. Normalized 2026-08-11 by
     * {@link ZSectionNormalizeRewrite} (579 snippets, 1170 headers, two
     * passes: PURE_DECL then the widened OWN_DECL population) plus hand
     * edits for the formatted/generated models (union probes, stress
     * generators); this pin keeps the population at ZERO.
     */
    @Test
    void noSectionlessSnippets() {
        List<String> violations = new ArrayList<>();
        for (Corpus.Source src : ownSnippets()) {
            List<Sectionize.Insertion> plan = Sectionize.plan(src.text());
            if (plan != null && !plan.isEmpty()) {
                violations.add(src.id() + " :: needs "
                        + plan.stream().map(Sectionize.Insertion::section)
                                .toList());
            }
        }
        assertTrue(violations.isEmpty(),
                () -> "SECTIONLESS test snippets (write the ###Section"
                        + " headers — ZSectionNormalizeRewrite mechanizes"
                        + " it):\n  " + String.join("\n  ",
                                violations.subList(0,
                                        Math.min(25, violations.size()))));
    }

    /**
     * DELIBERATE lite design surfaces our own tests exercise — the short,
     * explicit exceptions list (never grown casually; every entry names
     * the design that wants it). The engine's own message is the evidence
     * the construct was hit.
     */
    private static @com.legend.Nullable String liteDesign(Throwable root) {
        String msg = String.valueOf(root.getMessage());
        if (msg.contains("No parser for AssociationMapping")) {
            // clean-sheet inline association predicate:
            //   Assoc: AssociationMapping { {p, f | ...} }
            // (MAPPING_CLEAN_SHEET; MappingNormalizerTest M5) — engine has
            // no such mapping-element parser, by design on our side
            return "LITE-DESIGN-inline-association";
        }
        if (msg.contains("Unknown database type 'SQLite'")) {
            // the SQLite execution backend (BACKEND_PORTABILITY) — a lite
            // connection type the engine's DatabaseType enum lacks
            return "LITE-DESIGN-sqlite-backend";
        }
        return null;
    }

    /** A required-field row is acceptable ONLY as a deliberate
     *  lenient-tier fixture: our STRICT surface must refuse it with the
     *  same required-field story (ElementParserTest pins the lenient
     *  default; the drop-in surface stays engine-parity). Strict
     *  accepting it would be a genuine leniency — unclassified. */
    private static @com.legend.Nullable String lenientTierFixture(
            Throwable root, String text) {
        String msg = String.valueOf(root.getMessage());
        if (!(msg.contains("Field '") && msg.contains("' is required"))) {
            return null;
        }
        try {
            com.legend.parser.ElementParser.parseStrict(text);
        } catch (Throwable strict) {
            if (String.valueOf(strict.getMessage()).contains("is required")) {
                return "LENIENT-TIER-fixture";
            }
        }
        return null;
    }

    @Test
    void ourOwnTestPureIsRealLegend() throws Exception {
        PureGrammarParser oracle = PureGrammarParser.newInstance();
        List<Corpus.Source> ours = ownSnippets();
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
            String cls = liteDesign(root);
            if (cls == null) {
                cls = lenientTierFixture(root, src.text());
            }
            if (cls == null) {
                cls = LeniencyCatalogTest.classify(root, src.text());
            }
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
        // THE RATCHET (sections normalization, 2026-08-11): 712/949
        // oracle-accepted (OWN_DECL population), 127 classified leniency
        // rows. Every class is PINNED — a new row in any class (or a new
        // class) fails the build, so own-corpus leniency can only shrink.
        // The remaining rows are the invention census's worklist
        // (VERSION-SKEW here is a PROVISIONAL label: our strict surface
        // accepts, but for our own tests the true story is usually a lite
        // construct to adjudicate).
        Map<String, Integer> pins = new TreeMap<>(Map.of(
                "DIALECT-function-types", 13,
                "DIALECT-generics", 7,
                "DIALECT-milestoning-range", 1,
                "DIALECT-native-or-m2", 5,
                "ENGINE-TEST-SCOPED-section", 1,
                "LENIENT-TIER-fixture", 1,
                "LITE-DESIGN-inline-association", 2,
                "LITE-DESIGN-sqlite-backend", 2,
                "ORACLE-DEFECT-InputMismatchException", 42,
                "VERSION-SKEW-grammar", 53));
        List<String> overflows = new ArrayList<>();
        for (Map.Entry<String, Integer> e : byClass.entrySet()) {
            int pin = pins.getOrDefault(e.getKey(), 0);
            if (e.getValue() > pin) {
                overflows.add(e.getKey() + " " + e.getValue() + " > pin "
                        + pin);
            }
        }
        assertTrue(overflows.isEmpty(),
                () -> "own-corpus leniency GREW (the ratchet only goes"
                        + " down — conform the new test text to the oracle,"
                        + " or re-pin with review):\n  "
                        + String.join("\n  ", overflows));
        assertTrue(unclassified.isEmpty(),
                () -> "OUR OWN test Pure contains grammar that is neither"
                        + " engine nor named pure-dialect (lite-only"
                        + " inventions — fix the tests or the parser):\n  "
                        + String.join("\n  ", unclassified.subList(0,
                                Math.min(25, unclassified.size()))));
    }
}
