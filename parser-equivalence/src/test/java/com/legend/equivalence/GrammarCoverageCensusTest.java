// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend.equivalence;

import org.antlr.v4.runtime.CharStream;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.Lexer;
import org.antlr.v4.runtime.Parser;
import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.TokenStream;
import org.antlr.v4.runtime.tree.ParseTreeWalker;
import org.antlr.v4.runtime.tree.ParseTreeListener;
import org.antlr.v4.runtime.tree.ErrorNode;
import org.antlr.v4.runtime.tree.TerminalNode;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.jar.JarFile;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * THE GRAMMAR-COVERAGE CENSUS (bulletproof-and-total program, step 1):
 * every parity claim quantifies over sources that EXIST in the corpus —
 * this census measures what fraction of the ENGINE'S OWN GRAMMAR the
 * corpus actually exercises, by driving the engine's generated ANTLR
 * parsers (from the pinned oracle jars) over every corpus section
 * fragment and recording which parser rules ever fire.
 *
 * <p>An uncovered rule is a grammar path where lite could diverge with
 * every gate green — the enumerated residue IS the completeness
 * work-list. Three honest limits, reported rather than hidden:
 * <ol>
 *   <li>ISLAND grammars (graph-fetch trees, connection values, embedded
 *       relational operations) are reparsed by the engine from
 *       sub-fragments its section walkers extract; phase 1 drives only
 *       SECTION-level fragments, so island grammars appear in the
 *       "discovered but not driven" list — that list is the phase-2
 *       work-list, not a pass.</li>
 *   <li>Rule-level (plus labeled-alternative context classes) — an
 *       unlabeled alternative inside a covered rule is not separately
 *       observable from the parse tree.</li>
 *   <li>Fragments that the engine parser error-recovers still produce
 *       partial trees; their coverage counts, and the fragment is
 *       tallied under errFragments.</li>
 * </ol>
 */
class GrammarCoverageCensusTest {

    private static final Pattern SECTION_SPLIT =
            Pattern.compile("(?m)^###(\\w+)[ \\t]*$");

    @Test
    void census() throws Exception {
        List<Corpus.Source> sources = Corpus.all();
        assertTrue(sources.size() > 7000,
                "corpus floor: only " + sources.size() + " sources — check"
                        + " -Dlegend.engine.root/-Dlegend.pure.root");

        // ---- 1. discover every generated engine grammar on the classpath
        Map<String, String> parserClasses = discoverParserGrammars();

        // ---- 2. split every source into (sectionName, code) fragments
        Map<String, List<String>> bySection = new TreeMap<>();
        for (Corpus.Source src : sources) {
            String text = src.text();
            Matcher m = SECTION_SPLIT.matcher(text);
            int last = 0;
            String name = "Pure";
            while (m.find()) {
                if (m.start() > last) {
                    bySection.computeIfAbsent(name, k -> new ArrayList<>())
                            .add(text.substring(last, m.start()));
                }
                name = m.group(1);
                last = m.end();
            }
            if (last < text.length()) {
                bySection.computeIfAbsent(name, k -> new ArrayList<>())
                        .add(text.substring(last));
            }
        }

        // ---- 3. map sections to grammars and drive
        Map<String, Drive> drives = new LinkedHashMap<>();
        List<String> unmappedSections = new ArrayList<>();
        for (Map.Entry<String, List<String>> e : bySection.entrySet()) {
            String grammarFqn = grammarFor(e.getKey(), parserClasses);
            if (grammarFqn == null) {
                unmappedSections.add(e.getKey() + " (" + e.getValue().size()
                        + " fragments)");
                continue;
            }
            Drive d = drives.computeIfAbsent(grammarFqn, Drive::new);
            d.sections.add(e.getKey());
            for (String code : e.getValue()) {
                d.drive(code);
            }
        }

        // ---- 4. report
        StringBuilder out = new StringBuilder();
        out.append("# GRAMMAR-COVERAGE CENSUS — corpus coverage of the")
                .append(" engine's generated ANTLR grammars\n");
        out.append("# grammar\tsections\tfragments\terrFragments\t")
                .append("rulesCovered\trulesTotal\tpct\tlabeledCtxSeen\n");
        int totalRules = 0;
        int coveredRules = 0;
        StringBuilder uncoveredDetail = new StringBuilder();
        for (Drive d : drives.values()) {
            totalRules += d.ruleNames.length;
            coveredRules += d.covered.cardinality();
            out.append(String.format("%s\t%s\t%d\t%d\t%d\t%d\t%.1f%%\t%d%n",
                    simple(d.parserFqn), d.sections, d.fragments,
                    d.errFragments, d.covered.cardinality(),
                    d.ruleNames.length,
                    100.0 * d.covered.cardinality() / d.ruleNames.length,
                    d.contextClasses.size()));
            List<String> uncovered = new ArrayList<>();
            for (int i = 0; i < d.ruleNames.length; i++) {
                if (!d.covered.get(i)) {
                    uncovered.add(d.ruleNames[i]);
                }
            }
            if (!uncovered.isEmpty()) {
                uncoveredDetail.append("## ").append(simple(d.parserFqn))
                        .append(" uncovered (").append(uncovered.size())
                        .append("):\n");
                uncovered.forEach(r ->
                        uncoveredDetail.append("  ").append(r).append('\n'));
            }
        }
        out.append(String.format("# TOTAL driven: %d/%d rules (%.1f%%)%n",
                coveredRules, totalRules,
                100.0 * coveredRules / Math.max(1, totalRules)));
        out.append("# sections with NO mapped grammar: ")
                .append(unmappedSections).append('\n');
        TreeSet<String> undriven = new TreeSet<>(parserClasses.values());
        drives.keySet().forEach(undriven::remove);
        out.append("# grammars discovered but NOT driven (islands/value")
                .append(" grammars — the phase-2 work-list, ")
                .append(undriven.size()).append("):\n");
        undriven.forEach(g -> out.append("#   ").append(g).append('\n'));
        out.append('\n').append(uncoveredDetail);
        Files.writeString(Path.of("target", "grammar-coverage.tsv"),
                out.toString());
        System.out.println(out.toString().lines().limit(40)
                .reduce("", (a, b) -> a + b + "\n"));

        // Ratchets, measured 2026-08-14 (phase 1: section-level drive).
        // Coverage floor is UP-only: a drop means the corpus shrank or
        // the mapping broke — both are regressions. The undriven-grammar
        // ceiling is DOWN-only: phase 2 (islands/value grammars) shrinks
        // it and pins the progress.
        assertTrue(drives.size() >= 24,
                "census drove only " + drives.size() + " grammars (floor 24)"
                        + " — the section->grammar mapping broke");
        assertTrue(coveredRules >= 1020,
                "grammar-rule coverage fell: " + coveredRules
                        + " < floor 1020 — corpus or mapping regression");
        assertTrue(undriven.size() <= 44,
                "undriven grammar list grew: " + undriven.size()
                        + " > 44 — a new engine grammar appeared; extend"
                        + " the census (or phase-2 it explicitly)");
        assertTrue(unmappedSections.size() <= 1,
                "unmapped sections grew: " + unmappedSections);
    }

    /** One engine grammar being driven: reflective lexer/parser pair and
     *  its coverage accumulators. */
    private static final class Drive {
        final String parserFqn;
        final List<String> sections = new ArrayList<>();
        final String[] ruleNames;
        final BitSet covered = new BitSet();
        final TreeSet<String> contextClasses = new TreeSet<>();
        int fragments;
        int errFragments;
        private final Constructor<?> lexerCtor;
        private final Constructor<?> parserCtor;
        private final Method entry;

        Drive(String parserFqn) {
            this.parserFqn = parserFqn;
            try {
                Class<?> pc = Class.forName(parserFqn);
                Class<?> lc = Class.forName(parserFqn
                        .replace("ParserGrammar", "LexerGrammar"));
                lexerCtor = lc.getConstructor(CharStream.class);
                parserCtor = pc.getConstructor(TokenStream.class);
                Parser probe = (Parser) parserCtor.newInstance(
                        new CommonTokenStream((Lexer) lexerCtor.newInstance(
                                CharStreams.fromString(""))));
                ruleNames = probe.getRuleNames();
                // engine convention: 'definition' is the section entry
                // rule; fall back to the grammar's first rule
                String entryName = List.of(ruleNames).contains("definition")
                        ? "definition" : ruleNames[0];
                entry = pc.getMethod(entryName);
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException(
                        "cannot reflect grammar " + parserFqn, e);
            }
        }

        void drive(String code) {
            fragments++;
            try {
                Lexer lexer = (Lexer) lexerCtor.newInstance(
                        CharStreams.fromString(code));
                lexer.removeErrorListeners();
                Parser parser = (Parser) parserCtor.newInstance(
                        new CommonTokenStream(lexer));
                parser.removeErrorListeners();
                ParserRuleContext tree =
                        (ParserRuleContext) entry.invoke(parser);
                if (parser.getNumberOfSyntaxErrors() > 0) {
                    errFragments++;
                }
                ParseTreeWalker.DEFAULT.walk(new ParseTreeListener() {
                    @Override
                    public void enterEveryRule(ParserRuleContext ctx) {
                        covered.set(ctx.getRuleIndex());
                        contextClasses.add(ctx.getClass().getSimpleName());
                    }

                    @Override
                    public void exitEveryRule(ParserRuleContext ctx) {
                    }

                    @Override
                    public void visitTerminal(TerminalNode node) {
                    }

                    @Override
                    public void visitErrorNode(ErrorNode node) {
                    }
                }, tree);
            } catch (Throwable t) {
                // a fragment the engine grammar dies on outright (its own
                // crash class) still counts as attempted
                errFragments++;
            }
        }
    }

    /** Scan the classpath jars for generated engine parser grammars. */
    private static Map<String, String> discoverParserGrammars()
            throws Exception {
        Map<String, String> found = new TreeMap<>();
        for (String cp : System.getProperty("java.class.path")
                .split(java.io.File.pathSeparator)) {
            if (!cp.endsWith(".jar")) {
                continue;
            }
            try (JarFile jar = new JarFile(cp)) {
                jar.stream().forEach(en -> {
                    String n = en.getName();
                    if (n.startsWith("org/finos/legend/")
                            && n.endsWith("ParserGrammar.class")
                            && !n.contains("$")) {
                        String fqn = n.substring(0, n.length() - 6)
                                .replace('/', '.');
                        found.put(simple(fqn), fqn);
                    }
                });
            } catch (Exception ignore) {
                // non-jar or unreadable classpath entry
            }
        }
        return found;
    }

    /** Section name -> generated grammar FQN (engine naming convention;
     *  'Pure' is the Domain grammar). Null when nothing matches. */
    private static String grammarFor(String section,
            Map<String, String> parserClasses) {
        String want = section.equals("Pure") ? "Domain" : section;
        // Ranked prefix match, never bare substring. Base score:
        //   6 = exact stem; 4 = section name EXTENDS the stem
        //   (DataQualityValidation -> DataQuality); 2 = stem EXTENDS the
        //   section name (MemSql -> MemSqlFunction).
        // The .connection/.authentication subpackages hold
        // connection-VALUE island grammars, not section grammars —
        // penalize them by 2 so BigQueryFunction (the ###BigQuery
        // section) beats connection.BigQueryParserGrammar, while
        // connection.ConnectionParserGrammar still wins ###Connection
        // where no other candidate exists; ties prefer the
        // non-penalized candidate.
        String best = null;
        int bestScore = 0;
        boolean bestPenalized = true;
        for (Map.Entry<String, String> e : parserClasses.entrySet()) {
            String stem = e.getKey().substring(0,
                    e.getKey().length() - "ParserGrammar".length());
            boolean penalized = e.getValue().contains(".connection.")
                    || e.getValue().contains(".authentication.");
            int score = (stem.equals(want) ? 6
                    : want.startsWith(stem) ? 4
                    : stem.startsWith(want) ? 2 : 0)
                    - (penalized ? 2 : 0);
            if (score <= 0) {
                continue;
            }
            boolean better = score > bestScore
                    || (score == bestScore && bestPenalized && !penalized)
                    || (score == bestScore && bestPenalized == penalized
                            && best != null
                            && stem.length() > bestStem(best).length());
            if (better) {
                best = e.getValue();
                bestScore = score;
                bestPenalized = penalized;
            }
        }
        return best;
    }

    private static String bestStem(String fqn) {
        String s = simple(fqn);
        return s.substring(0, s.length() - "ParserGrammar".length());
    }

    private static String simple(String fqn) {
        int i = fqn.lastIndexOf('.');
        return i < 0 ? fqn : fqn.substring(i + 1);
    }
}
