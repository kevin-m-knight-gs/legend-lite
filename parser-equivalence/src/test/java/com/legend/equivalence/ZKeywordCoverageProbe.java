package com.legend.equivalence;

import org.finos.legend.engine.language.pure.grammar.from.PureGrammarParser;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/** PROBE (keyword-coverage census): every keyword literal in the engine's
 *  73 .g4 grammars, checked against the corpus — a keyword that never
 *  appears in any source BOTH parsers accept marks a grammar arm the
 *  parity harness has never exercised (how the Binding transformer hid).
 *  Diagnostic. */
class ZKeywordCoverageProbe {


    /** The committed engine-fixture snapshot as census coverage input. */
    static java.util.List<Corpus.Source> engineFixtures() {
        java.util.List<Corpus.Source> out = new java.util.ArrayList<>();
        java.nio.file.Path p = java.nio.file.Path.of("src/test/resources/"
                + "engine-grammar-fixtures-4.138.2.jsonl");
        if (!java.nio.file.Files.exists(p)) {
            return out;
        }
        try {
            var om = new com.fasterxml.jackson.databind.ObjectMapper();
            int i = 0;
            for (String line : java.nio.file.Files.readAllLines(p)) {
                var n = om.readTree(line);
                out.add(new Corpus.Source("engine-fixture#" + (i++),
                        n.get("source").asText(), "C6"));
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return out;
    }

    @Test
    void keywordCoverage() throws Exception {
        Path engineRoot = Path.of(System.getProperty("legend.engine.root"));
        // keyword literals from lexer/parser grammars: word-shaped, >= 3
        // chars (operators/punctuation can't be checked by text presence)
        Map<String, Set<String>> kwToGrammars = new TreeMap<>();
        try (Stream<Path> s = Files.walk(engineRoot)) {
            for (Path g4 : s.filter(p -> p.toString().endsWith(".g4"))
                    .toList()) {
                String text = Files.readString(g4);
                Matcher m = Pattern
                        .compile("'([A-Za-z_][A-Za-z0-9_]{2,})'")
                        .matcher(text);
                while (m.find()) {
                    kwToGrammars.computeIfAbsent(m.group(1),
                            k -> new TreeSet<>())
                            .add(g4.getFileName().toString()
                                    .replace("ParserGrammar.g4", "")
                                    .replace("LexerGrammar.g4", ""));
                }
            }
        }
        System.out.println("@@ keywords harvested: " + kwToGrammars.size());

        PureGrammarParser oracle = PureGrammarParser.newInstance();
        Set<String> covered = new HashSet<>();
        int accepted = 0;
        java.util.List<Corpus.Source> universe =
                new java.util.ArrayList<>(Corpus.all());
        universe.addAll(engineFixtures());
        for (Corpus.Source src : universe) {
            try {
                oracle.parseModel(src.text());
            } catch (Throwable t) {
                continue;
            }
            try {
                com.legend.parser.ElementParser.parse(src.text());
            } catch (Throwable t) {
                continue;
            }
            accepted++;
            String txt = src.text();
            for (String kw : kwToGrammars.keySet()) {
                if (covered.contains(kw)) {
                    continue;
                }
                int i = txt.indexOf(kw);
                while (i >= 0) {
                    boolean leftOk = i == 0 || !Character
                            .isLetterOrDigit(txt.charAt(i - 1))
                            && txt.charAt(i - 1) != '_';
                    int e = i + kw.length();
                    boolean rightOk = e >= txt.length() || !Character
                            .isLetterOrDigit(txt.charAt(e))
                            && txt.charAt(e) != '_';
                    if (leftOk && rightOk) {
                        covered.add(kw);
                        break;
                    }
                    i = txt.indexOf(kw, i + 1);
                }
            }
        }
        System.out.println("@@ both-accepted sources: " + accepted
                + "; keywords covered: " + covered.size() + "/"
                + kwToGrammars.size());
        Map<String, java.util.List<String>> byGrammar = new TreeMap<>();
        for (var e : kwToGrammars.entrySet()) {
            if (covered.contains(e.getKey())) {
                continue;
            }
            for (String g : e.getValue()) {
                byGrammar.computeIfAbsent(g, k -> new java.util.ArrayList<>())
                        .add(e.getKey());
            }
        }
        byGrammar.forEach((g, kws) -> System.out.println(
                "@@ UNCOVERED [" + g + "] " + String.join(", ", kws)));
    }
}
