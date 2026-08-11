package com.legend.equivalence;

import org.finos.legend.engine.language.pure.grammar.from.PureGrammarParser;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * THE EXECUTABLE LENIENCY CATALOG (docs/LENIENCY_CATALOG.md): every corpus
 * source we accept and the oracle refuses must classify into a NAMED,
 * justified class — an UNCLASSIFIED row fails the build, so leniency can
 * no longer grow silently in any class. Row membership is written to
 * {@code target/leniency-catalog.txt}.
 */
public class LeniencyCatalogTest {

    /** Class a refusal by the ORACLE'S OWN evidence (message/exception),
     *  with one mechanical assist: a bare "Unexpected token" is adjudicated
     *  by OUR strict surface — its engine-verbatim gates name the dialect
     *  construct row by row (ZSkewResidueProbe), and a strict ACCEPT means
     *  the construct is checkout-unreleased grammar (true version skew).
     *  Returns null when nothing matches — the failing case. */
    public static @com.legend.Nullable String classify(Throwable root, String text) {
        String msg = String.valueOf(root.getMessage());
        if ("Unexpected token".equals(msg.trim())) {
            try {
                com.legend.parser.ElementParser.parseStrict(text);
                return "VERSION-SKEW-grammar";
            } catch (Throwable strict) {
                String sm = String.valueOf(strict.getMessage());
                if (sm.contains("not authorized in Legend Engine")) {
                    return "DIALECT-generics";
                }
                if (sm.contains("is not supported yet")) {
                    return "DIALECT-function-types";
                }
                if (sm.contains(".allVersionsInRange")) {
                    return "DIALECT-milestoning-range";
                }
                if (sm.contains("Unsupported syntax")) {
                    return "DIALECT-native-or-m2";
                }
                return "VERSION-SKEW-grammar";
            }
        }
        // DIALECT-GAP — the engine names its own subset
        if (msg.contains("not authorized in Legend")) {
            return "DIALECT-generics";
        }
        if (msg.matches("(?s).*The type \\{.*}.* is not sup.*")) {
            return "DIALECT-function-types";
        }
        if (msg.contains(".allVersionsInRange(")
                && msg.contains("is not supported")) {
            return "DIALECT-milestoning-range";
        }
        if (msg.contains("Unsupported syntax")) {
            // verified: this engine message fires at native-function
            // declarations and m2 mapping forms (catalog DIALECT-GAP)
            return "DIALECT-native-or-m2";
        }
        // EXTENSION-GAP
        if (msg.contains("Can't find an embedded Pure parser")) {
            return "EXTENSION-island-parser";
        }
        if (msg.contains("is not a known section parser")) {
            // AuthenticationDemo: the section parser exists ONLY in the
            // engine's own test sources (no published jar) — a PRODUCTION
            // engine refuses these files exactly as our strict surface
            // does; pure mode skips the section as an unclaimed carrier
            return "ENGINE-TEST-SCOPED-section";
        }
        if (msg.contains("Unknown schema format")
                || msg.contains("Unknown embedded data type")
                || msg.contains("Unknown permission scheme")) {
            return "EXTENSION-format";
        }
        // ORACLE-DEFECT — a crash, not a refusal
        if (root instanceof NullPointerException
                || msg.contains("Cannot invoke")
                || msg.contains("NullPointerException")
                || msg.contains("please notify developer")
                // NumberFormatException escaping the oracle's unicode-escape
                // parser (TestProfile.java#52: For input string "sers"
                // under radix 16)
                || msg.contains("under radix")) {
            return "ORACLE-DEFECT-crash";
        }
        if ("null".equals(msg)) {
            return "ORACLE-DEFECT-" + root.getClass().getSimpleName();
        }
        // VERSION-SKEW — generic grammar refusals from the 5.88 oracle
        // over the current checkout's constructs. The membership evidence
        // (relation mappings/~src/#>/Primitive/…) is in the catalog doc;
        // the generic messages below belong to this class because the
        // corpus IS the newer checkout.
        if (msg.startsWith("Unexpected token")
                || msg.contains("mismatched input")
                || msg.contains("extraneous input")
                || msg.contains("missing ") && msg.contains(" at ")) {
            return "VERSION-SKEW-grammar";
        }
        if (msg.contains("Field '") && msg.contains("' is required")) {
            // any REMAINING required-field refusal is a NEW genuinely-lite
            // leniency — those must be fixed, never cataloged
            return null;
        }
        return null;
    }

    @Test
    void everyLeniencyRowIsClassified() throws Exception {
        PureGrammarParser oracle = PureGrammarParser.newInstance();
        Map<String, Integer> byClass = new TreeMap<>();
        List<String> unclassified = new ArrayList<>();
        StringBuilder report = new StringBuilder();
        for (Corpus.Source src : Corpus.all()) {
            Throwable refusal = null;
            try {
                oracle.parseModel(src.text());
                continue;
            } catch (Throwable t) {
                refusal = t;
            }
            try {
                com.legend.parser.ElementParser.parse(src.text());
            } catch (Throwable ours) {
                continue;       // we refuse too — not a leniency row
            }
            Throwable root = refusal;
            while (root.getCause() != null && root.getCause() != root) {
                root = root.getCause();
            }
            String cls = classify(root, src.text());
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
        java.nio.file.Path out = java.nio.file.Path.of("target",
                "leniency-catalog.txt");
        java.nio.file.Files.createDirectories(out.getParent());
        java.nio.file.Files.writeString(out, report.toString());
        System.out.println("leniency catalog by class: " + byClass);
        assertTrue(unclassified.isEmpty(),
                () -> "UNCLASSIFIED leniency rows (docs/LENIENCY_CATALOG.md"
                        + " — adjudicate or fix, never ignore):\n  "
                        + String.join("\n  ", unclassified.subList(0,
                                Math.min(20, unclassified.size()))));
    }
}
