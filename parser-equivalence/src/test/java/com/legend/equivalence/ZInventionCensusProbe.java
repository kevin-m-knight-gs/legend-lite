package com.legend.equivalence;

import org.finos.legend.engine.language.pure.grammar.from.PureGrammarParser;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** PROBE (invention census): for every own-corpus leniency row still
 *  labeled VERSION-SKEW or ORACLE-DEFECT, prefix-parse bisect against the
 *  oracle to the CHOKING LINE and cluster the constructs. Diagnostic. */
class ZInventionCensusProbe {

    @Test
    void bisectOwnRows() throws Exception {
        PureGrammarParser oracle = PureGrammarParser.newInstance();
        Path repo = Path.of(System.getProperty("user.dir")).getParent();
        List<Corpus.Source> ours = new ArrayList<>();
        for (String module : new String[]{"core", "pct", "nlq"}) {
            ours.addAll(InlineSnippets.extract(repo.resolve(module),
                    "lite-" + module, InlineSnippets.OWN_DECL));
        }
        Map<String, List<String>> byConstruct = new TreeMap<>();
        int rows = 0;
        for (Corpus.Source src : ours) {
            Throwable refusal;
            try {
                oracle.parseModel(src.text());
                continue;
            } catch (Throwable t) {
                refusal = t;
            }
            try {
                com.legend.parser.ElementParser.parseLegendPlatform(src.text());
            } catch (Throwable oursToo) {
                continue;
            }
            Throwable root = refusal;
            while (root.getCause() != null && root.getCause() != root) {
                root = root.getCause();
            }
            String msg = String.valueOf(root.getMessage());
            // only the two unresolved classes: bare token refusals and
            // null-message crashes — everything else is already named
            boolean unresolved = msg.trim().equals("Unexpected token")
                    || msg.startsWith("Unexpected token")
                    || "null".equals(msg);
            if (!unresolved) {
                continue;
            }
            rows++;
            String[] lines = src.text().split("\n", -1);
            // the oracle's own position beats prefix bisection: walk the
            // cause chain for an EngineException sourceInformation
            int chokeLine = -1;
            for (Throwable t = refusal; t != null; t = t.getCause() == t
                    ? null : t.getCause()) {
                if (t instanceof org.finos.legend.engine.shared.core
                        .operational.errorManagement.EngineException ee
                        && ee.getSourceInformation() != null) {
                    chokeLine = ee.getSourceInformation().startLine;
                    break;
                }
            }
            StringBuilder choke = new StringBuilder();
            if (chokeLine >= 1 && chokeLine <= lines.length) {
                choke.append("L").append(chokeLine).append(": ")
                        .append(lines[chokeLine - 1].strip()).append(" | ");
            } else {
                // fall back to the prefix bisect
                int lastGood = 0;
                for (int n = 1; n <= lines.length; n++) {
                    String prefix = String.join("\n",
                            java.util.Arrays.copyOfRange(lines, 0, n));
                    try {
                        oracle.parseModel(prefix);
                        lastGood = n;
                    } catch (Throwable ignored) {
                        // keep going; report the last prefix that parsed
                    }
                }
                for (int i = lastGood;
                        i < Math.min(lastGood + 2, lines.length); i++) {
                    choke.append(lines[i].strip()).append(" | ");
                }
            }
            // cluster key: the choking text, identifiers normalized
            String key = choke.toString()
                    .replaceAll("'[^']*'", "'…'")
                    .replaceAll("\\d+", "N");
            byConstruct.computeIfAbsent(key, k -> new ArrayList<>())
                    .add(src.id());
        }
        System.out.println("@@ unresolved rows bisected: " + rows);
        byConstruct.entrySet().stream()
                .sorted((a, b) -> b.getValue().size() - a.getValue().size())
                .forEach(e -> {
                    System.out.println("@@ [" + e.getValue().size() + "] "
                            + e.getKey());
                    e.getValue().stream().limit(3).forEach(id ->
                            System.out.println("@@      " + id));
                });
    }
}
