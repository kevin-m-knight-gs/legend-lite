package com.legend.equivalence;

import org.finos.legend.engine.language.pure.grammar.from.PureGrammarParser;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * REJECTION PARITY for the dialect quarantine: every corpus source the
 * oracle refuses for a DIALECT reason (generics, function types,
 * {@code native}, {@code .allVersionsInRange}, m2 forms — the classes
 * {@link LeniencyCatalogTest#classify} names {@code DIALECT-*}) must ALSO
 * refuse on the ENGINE-STRICT drop-in surface
 * ({@code ElementParser.parseStrict}). Pure mode keeps accepting them
 * (that's the catalog test's invariant); strict mode matching the engine's
 * accept AND refuse sets is what makes the drop-in claim two-sided.
 */
class StrictDialectParityTest {

    @Test
    void strictSurfaceRefusesEveryDialectRow() throws Exception {
        PureGrammarParser oracle = PureGrammarParser.newInstance();
        List<String> leaks = new ArrayList<>();
        int checked = 0;
        for (Corpus.Source src : Corpus.all()) {
            Throwable refusal;
            try {
                oracle.parseModel(src.text());
                continue;                   // oracle accepts — parity is G8's job
            } catch (Throwable t) {
                refusal = t;
            }
            try {
                com.legend.parser.ElementParser.parse(src.text());
            } catch (Throwable ours) {
                continue;                   // pure mode refuses too — no dialect row
            }
            Throwable root = refusal;
            while (root.getCause() != null && root.getCause() != root) {
                root = root.getCause();
            }
            String cls = LeniencyCatalogTest.classify(root);
            if (cls == null || !cls.startsWith("DIALECT-")) {
                continue;                   // skew/extension/defect rows: not dialect
            }
            checked++;
            try {
                com.legend.parser.ElementParser.parseStrict(src.text());
                leaks.add(cls + " :: " + src.id());
            } catch (Throwable expectedRefusal) {
                // strict refuses like the engine — parity holds
            }
        }
        System.out.println("strict dialect parity: " + checked
                + " rows checked, " + leaks.size() + " leaks");
        assertTrue(leaks.isEmpty(), () -> leaks.size()
                + " DIALECT rows still PARSE on the strict surface:\n  "
                + String.join("\n  ",
                        leaks.subList(0, Math.min(30, leaks.size()))));
    }
}
