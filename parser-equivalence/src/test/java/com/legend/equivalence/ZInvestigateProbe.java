package com.legend.equivalence;

import org.junit.jupiter.api.Test;

/** PROBE: dump the 2 INVESTIGATE rows (strict-accepted, no reference
 *  grammar accepts) with all verdicts. Diagnostic. */
class ZInvestigateProbe {
    @Test
    void probe() {
        java.util.Set<String> want = java.util.Set.of(
                "legend-pure-core/legend-pure-m3-core/src/test/java/org/finos/legend/pure/m3/tests/elements/_class/AbstractTestConstraints.java#110",
                "legend-pure-core/legend-pure-m3-core/src/test/java/org/finos/legend/pure/m3/tests/elements/property/TestDefaultValue.java#41");
        for (Corpus.Source s : Corpus.all()) {
            if (!want.contains(s.id())) continue;
            System.out.println("@@ ID " + s.id());
            System.out.println("@@ SOURCE\n" + s.text());
            try {
                org.finos.legend.engine.language.pure.grammar.from
                        .PureGrammarParser.newInstance().parseModel(s.text());
                System.out.println("@@ oracle ACCEPTS");
            } catch (Throwable t) {
                Throwable r = t;
                while (r.getCause() != null && r.getCause() != r) r = r.getCause();
                System.out.println("@@ oracle refuses: " + r.getClass().getSimpleName()
                        + " " + String.valueOf(r.getMessage()).replaceAll("\\s+", " "));
            }
        }
    }
}
