package com.legend.equivalence;

import org.finos.legend.engine.language.pure.grammar.from.PureGrammarParser;
import org.junit.jupiter.api.Test;

/** PROBE: (a) does ANY corpus source carry the binding transformer, and
 *  (b) full-universe sweep — every ORACLE-ACCEPTED corpus source that
 *  LITE refuses (the inverse-parity direction). Diagnostic. */
class ZBindingCoverageProbe {

    @Test
    void bindingPresenceAndInverseParity() {
        PureGrammarParser oracle = PureGrammarParser.newInstance();
        int bindingSources = 0;
        int oracleAccepts = 0;
        int liteRefusesOfAccepted = 0;
        java.util.Map<String, Integer> refusalCluster =
                new java.util.TreeMap<>();
        java.util.List<String> samples = new java.util.ArrayList<>();
        for (Corpus.Source src : Corpus.all()) {
            boolean hasBinding = src.text().contains(": Binding ")
                    || src.text().contains(":Binding ");
            if (hasBinding) {
                bindingSources++;
                if (bindingSources <= 5) {
                    System.out.println("@@ BINDING-SOURCE " + src.id());
                }
            }
            boolean oracleOk;
            try {
                oracle.parseModel(src.text());
                oracleOk = true;
                oracleAccepts++;
            } catch (Throwable t) {
                oracleOk = false;
            }
            if (!oracleOk) {
                continue;
            }
            try {
                Surfaces.platform(src.text());
            } catch (Throwable lite) {
                liteRefusesOfAccepted++;
                String msg = String.valueOf(lite.getMessage())
                        .replaceAll("\\[\\d+:\\d+\\]", "[N:N]")
                        .replaceAll("'[^']*'", "'…'");
                String key = msg.length() > 90 ? msg.substring(0, 90) : msg;
                refusalCluster.merge(key, 1, Integer::sum);
                if (samples.size() < 20) {
                    samples.add(src.id() + " :: " + msg);
                }
            }
        }
        System.out.println("@@ binding-carrying sources: " + bindingSources);
        System.out.println("@@ oracle-accepted: " + oracleAccepts
                + "; lite-refused-of-accepted: " + liteRefusesOfAccepted);
        refusalCluster.entrySet().stream()
                .sorted((a, b) -> b.getValue() - a.getValue())
                .forEach(e -> System.out.println("@@ [" + e.getValue() + "] "
                        + e.getKey()));
        samples.forEach(s -> System.out.println("@@ ROW " + s));
    }
}
