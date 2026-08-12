package com.legend.equivalence;

import org.finos.legend.engine.language.pure.grammar.from.PureGrammarParser;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * AUDIT Leg 1 (docs/DEEP_AUDIT_HANDOFF.md): for every unverified row —
 * VERSION-SKEW, strict-accepted ORACLE-DEFECT, non-dialect strict
 * refusals — bisect the source against the release oracle to the line
 * where acceptance stops and CLUSTER the choking constructs. Output is
 * the family census the three-reference adjudication works from.
 * Diagnostic only.
 */
class ZAuditBisectProbe {

    @Test
    void bisectAndCluster() throws Exception {
        PureGrammarParser oracle = PureGrammarParser.newInstance();
        List<Corpus.Source> all = new ArrayList<>(Corpus.all());
        all.addAll(InlineSnippets.extract(java.nio.file.Path.of(
                System.getProperty("legend.engine.root")), "ie"));
        all.addAll(InlineSnippets.extract(java.nio.file.Path.of(
                System.getProperty("legend.pure.root")), "ip"));
        Map<String, List<String>> clusters = new TreeMap<>();
        int rows = 0;
        for (Corpus.Source src : all) {
            Throwable refusal;
            try {
                oracle.parseModel(src.text());
                continue;
            } catch (Throwable t) {
                refusal = t;
            }
            Throwable root = refusal;
            while (root.getCause() != null && root.getCause() != root) {
                root = root.getCause();
            }
            String m = String.valueOf(root.getMessage());
            boolean bare = "Unexpected token".equals(m.trim());
            boolean crash = root instanceof NullPointerException
                    || m.contains("Cannot invoke")
                    || m.contains("NullPointerException")
                    || m.contains("please notify developer")
                    || m.contains("under radix") || "null".equals(m);
            if (!bare && !crash) {
                continue;               // named refusals: already adjudicated
            }
            try {
                com.legend.parser.ElementParser.parsePlatform(src.text());
            } catch (Throwable ours) {
                continue;               // both fail — not a leniency row
            }
            String strictVerdict;
            try {
                com.legend.parser.ElementParser.parseStrict(src.text());
                strictVerdict = "strict-accepts";
            } catch (Throwable t) {
                String sm = String.valueOf(t.getMessage());
                if (sm.contains("not authorized")
                        || sm.contains("is not supported yet")
                        || sm.contains(".allVersionsInRange")
                        || sm.contains("Unsupported syntax")) {
                    continue;           // dialect-named: already adjudicated
                }
                strictVerdict = "strict-refuses-other[" + sm.replaceAll(
                        "\\s+", " ").substring(0,
                                Math.min(50, sm.length())) + "]";
            }
            rows++;
            String[] lines = src.text().split("\n", -1);
            // STRIDE-then-refine: coarse max-accepted prefix every 32
            // lines, then a linear forward walk (prefix acceptance is not
            // monotone — mid-element truncation fails spuriously — so both
            // passes track the MAX accepted n and the refine walk gives up
            // after 40 consecutive failures past it)
            int lastGood = 0;
            for (int n = 32; n <= lines.length; n += 32) {
                if (parses(oracle, lines, n)) {
                    lastGood = n;
                }
            }
            if (parses(oracle, lines, lines.length)) {
                lastGood = lines.length;
            }
            int misses = 0;
            for (int n = lastGood + 1;
                    n <= lines.length && misses < 40; n++) {
                if (parses(oracle, lines, n)) {
                    lastGood = n;
                    misses = 0;
                } else {
                    misses++;
                }
            }
            StringBuilder construct = new StringBuilder();
            for (int i = lastGood; i < Math.min(lastGood + 2, lines.length);
                    i++) {
                construct.append(lines[i].strip()).append(" | ");
            }
            String key = strictVerdict + " :: "
                    + normalize(construct.toString());
            clusters.computeIfAbsent(key, k -> new ArrayList<>())
                    .add(src.id() + " @L" + (lastGood + 1));
        }
        System.out.println("@@ rows bisected: " + rows + ", clusters: "
                + clusters.size());
        clusters.entrySet().stream()
                .sorted((a, b) -> b.getValue().size()
                        - a.getValue().size())
                .forEach(e -> {
                    System.out.println("@@ [" + e.getValue().size() + "] "
                            + e.getKey());
                    System.out.println("@@    e.g. " + e.getValue().get(0));
                });
    }

    private static boolean parses(PureGrammarParser oracle, String[] lines,
            int n) {
        try {
            oracle.parseModel(String.join("\n",
                    Arrays.copyOfRange(lines, 0, n)));
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** Cluster key: the construct line with names/literals blurred so the
     *  same GRAMMAR shape lands in one bucket. */
    private static String normalize(String s) {
        StringBuilder out = new StringBuilder();
        boolean inWord = false;
        for (char c : s.toCharArray()) {
            if (Character.isLetterOrDigit(c) || c == '_' || c == '$') {
                if (!inWord) {
                    out.append('w');
                }
                inWord = true;
            } else {
                inWord = false;
                out.append(c);
            }
        }
        return out.length() > 90 ? out.substring(0, 90) : out.toString();
    }
}
