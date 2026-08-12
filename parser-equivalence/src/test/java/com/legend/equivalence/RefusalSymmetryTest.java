// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend.equivalence;

import org.antlr.v4.runtime.BaseErrorListener;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.RecognitionException;
import org.antlr.v4.runtime.Recognizer;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * VERDICT SYMMETRY, step 1 — REPORT ONLY (HARNESS_SIMPLIFICATION_PLAN
 * Phase 4). The byte gates skip every source the oracle rejects, so
 * nothing asserts what lite does on that quarter of the corpus — a
 * keyword-deletion mutation once survived every gate because the
 * reference rejected each file exercising it. The target assertion is
 * {@code oracle threw ⟺ we threw}; it cannot land as a switch because
 * most of the asymmetry is CHOSEN (the m3 dialect). So: measure and
 * categorise first, assert nothing; the committed
 * {@code docs/refusal-asymmetry.tsv} is the population, one row per
 * source — id, category, accepting lite surfaces, the oracle's message.
 *
 * <p>Categories, mechanically assigned:
 * <ul>
 *   <li>{@code m3-corroborated} — legend-pure's OWN M3Parser (the second
 *       reference grammar, raw ANTLR syntax check) accepts the
 *       section-free source: the leniency is the dialect, by
 *       construction.</li>
 *   <li>{@code m3-rejects} — section-free and NO reference grammar
 *       accepts it: the real "did we invent something?" rows.</li>
 *   <li>{@code sectioned} — carries {@code ###} sections, out of
 *       M3Parser's reach; needs per-row review.</li>
 * </ul>
 *
 * <p>Step 2 turns this report into a checked-in ALLOWLIST asserted
 * exactly; step 3 ratchets the file shrink-only; step 4 burns it down
 * (the strict flip removes the dialect lines wholesale).
 */
class RefusalSymmetryTest {

    private static final Pattern SECTION = Pattern.compile("(?m)^###(\\w+)");

    @Test
    void refusalAsymmetryReport() throws Exception {
        List<Corpus.Source> sources = Corpus.all();
        Assumptions.assumeTrue(!sources.isEmpty(),
                "no corpus on disk — set -Dlegend.engine.root / -Dlegend.pure.root");

        List<String> rows = new ArrayList<>();
        Map<String, Integer> byCategory = new TreeMap<>();
        int oracleRejects = 0;
        int symmetric = 0;
        for (Corpus.Source src : sources) {
            String oracleMsg;
            try {
                OracleParses.acquire(src);
                continue;                       // oracle accepts — the byte gates own it
            } catch (Throwable t) {
                Throwable r = t;
                while (r.getCause() != null && r.getCause() != r) {
                    r = r.getCause();
                }
                oracleMsg = String.valueOf(r.getMessage())
                        .replaceAll("\\s+", " ").trim();
            }
            oracleRejects++;
            boolean docAccepts = accepts(() ->
                    com.legend.parser.PmcdParser.parseDocument(src.text()));
            boolean strictAccepts = accepts(() ->
                    com.legend.parser.ElementParser.parseStrict(src.text()));
            if (!docAccepts && !strictAccepts) {
                symmetric++;                    // both refuse — the target state
                continue;
            }
            boolean sectioned = SECTION.matcher(src.text()).find();
            String category = sectioned ? "sectioned"
                    : m3Accepts(src.text()) ? "m3-corroborated"
                            : "m3-rejects";
            String surfaces = (docAccepts ? "document" : "")
                    + (docAccepts && strictAccepts ? "+" : "")
                    + (strictAccepts ? "strict" : "");
            byCategory.merge(category + "/" + surfaces, 1, Integer::sum);
            rows.add(src.id() + "\t" + category + "\t" + surfaces + "\t"
                    + (oracleMsg.length() > 160
                            ? oracleMsg.substring(0, 160) : oracleMsg));
        }

        StringBuilder b = new StringBuilder();
        b.append("# REFUSAL ASYMMETRY — sources the ORACLE rejects that a lite surface accepts\n");
        b.append("# oracle-rejected ").append(oracleRejects)
                .append(" | symmetric (both refuse) ").append(symmetric)
                .append(" | asymmetric ").append(rows.size()).append('\n');
        b.append("# categories: ");
        byCategory.forEach((k, v) -> b.append(k).append('=').append(v).append(' '));
        b.append('\n');
        b.append("# id\tcategory\taccepting-surfaces\toracle-message\n");
        rows.sort(String::compareTo);
        rows.forEach(r -> b.append(r).append('\n'));
        Files.writeString(Path.of("target", "refusal-asymmetry.tsv"),
                b.toString());
        System.out.println("refusal asymmetry: " + oracleRejects
                + " oracle-rejected, " + symmetric + " symmetric, "
                + rows.size() + " asymmetric — " + byCategory);

        // REPORT ONLY at step 1 — the single guard is that the sweep ran
        assertTrue(oracleRejects > 0,
                "the oracle rejected nothing: the corpus did not load");
    }

    private static boolean accepts(ThrowingRunnable parse) {
        try {
            parse.run();
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private interface ThrowingRunnable {
        void run() throws Throwable;
    }

    /** Raw ANTLR syntax adjudication by legend-pure's OWN grammar — the
     *  second reference. No model building, no compile: accept iff the
     *  {@code definition()} rule consumes the source with zero syntax
     *  errors. */
    private static boolean m3Accepts(String text) {
        var errors = new ArrayList<String>();
        var listener = new BaseErrorListener() {
            @Override
            public void syntaxError(Recognizer<?, ?> recognizer,
                    Object offendingSymbol, int line, int charPositionInLine,
                    String msg, RecognitionException e) {
                errors.add(line + ":" + charPositionInLine + " " + msg);
            }
        };
        try {
            var lexer = new org.finos.legend.pure.m3.serialization.grammar
                    .m3parser.antlr.M3Lexer(CharStreams.fromString(text));
            lexer.removeErrorListeners();
            lexer.addErrorListener(listener);
            var parser = new org.finos.legend.pure.m3.serialization.grammar
                    .m3parser.antlr.M3Parser(new CommonTokenStream(lexer));
            parser.removeErrorListeners();
            parser.addErrorListener(listener);
            parser.definition();
            return errors.isEmpty();
        } catch (Throwable t) {
            return false;
        }
    }
}
