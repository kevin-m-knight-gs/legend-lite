package com.legend.equivalence;

import org.finos.legend.engine.language.pure.grammar.from.PureGrammarParser;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** PROBE (sections normalization): does the MECHANICAL {@link Sectionize}
 *  transform turn the own-corpus "Unexpected token" refusals into oracle
 *  ACCEPTS? Diagnostic only. */
class ZSectionizeProbe {

    @Test
    void sectionizeVerdicts() throws Exception {
        PureGrammarParser oracle = PureGrammarParser.newInstance();
        Path repo = Path.of(System.getProperty("user.dir")).getParent();
        List<Corpus.Source> ours = new ArrayList<>();
        for (String module : new String[]{"core", "pct", "nlq"}) {
            ours.addAll(InlineSnippets.extract(repo.resolve(module),
                    "lite-" + module));
        }
        int rows = 0;
        int fixed = 0;
        int pureOnlyRefused = 0;
        int unsectionizable = 0;
        Map<String, Integer> stillRefused = new TreeMap<>();
        List<String> stillSamples = new ArrayList<>();
        List<String> pureOnlySamples = new ArrayList<>();
        for (Corpus.Source src : ours) {
            try {
                oracle.parseModel(src.text());
                continue;
            } catch (Throwable t) {
                // a refusal row candidate
            }
            try {
                com.legend.parser.ElementParser.parse(src.text(), com.legend.parser.Dialect.LEGEND_PLATFORM);
            } catch (Throwable oursToo) {
                continue;               // both refuse — not our worklist
            }
            rows++;
            String normalized = Sectionize.apply(src.text());
            if (normalized == null) {
                unsectionizable++;
                continue;
            }
            if (normalized.equals(src.text())) {
                pureOnlyRefused++;
                if (pureOnlySamples.size() < 12) {
                    pureOnlySamples.add(src.id());
                }
                continue;
            }
            try {
                oracle.parseModel(normalized);
                fixed++;
            } catch (Throwable t) {
                Throwable root = t;
                while (root.getCause() != null && root.getCause() != root) {
                    root = root.getCause();
                }
                String msg = String.valueOf(root.getMessage())
                        .replaceAll("\\s+", " ");
                String key = msg.length() > 80 ? msg.substring(0, 80) : msg;
                stillRefused.merge(key, 1, Integer::sum);
                if (stillSamples.size() < 15) {
                    stillSamples.add(src.id() + " :: " + msg);
                }
            }
        }
        System.out.println("@@ rows=" + rows + " fixedBySectionize=" + fixed
                + " pureOnlyStillRefused=" + pureOnlyRefused
                + " unsectionizable=" + unsectionizable);
        System.out.println("@@ still-refused messages: " + stillRefused);
        stillSamples.forEach(s -> System.out.println("@@ STILL " + s));
        pureOnlySamples.forEach(s -> System.out.println("@@ PURE-ONLY " + s));
    }
}
