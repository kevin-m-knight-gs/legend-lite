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
 * VERDICT SYMMETRY (HARNESS_SIMPLIFICATION_PLAN Phase 4, steps 1-3). The byte gates skip every source the oracle rejects, so
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
 * <p>The ASSERTION (step 2): every asymmetric source is a line of the
 * checked-in {@code docs/refusal-allowlist.tsv} with a stated reason.
 * The RATCHET (step 3) is the file itself — adding a line is a
 * reviewed diff, stale lines are reported for removal, and the strict
 * flip (step 4) removes every "dies at the strict flip" row wholesale.
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

        // STEP 2 — THE ALLOWLIST ASSERTION: every asymmetric source is a
        // line of docs/refusal-allowlist.tsv, a checked-in file with a
        // per-row reason. Unlike a runtime classifier, a file cannot
        // absorb a NEW asymmetry — it fails here until a human adds the
        // line (a reviewed diff) or fixes the parity. Ratchet: the file
        // only shrinks; stale lines (no longer asymmetric) are reported
        // for removal.
        java.util.Map<String, String> allow = new java.util.LinkedHashMap<>();
        for (String line : Files.readAllLines(
                Path.of("..", "docs", "refusal-allowlist.tsv"))) {
            if (line.startsWith("#") || line.isBlank()) {
                continue;
            }
            String[] f = line.split("\t", 3);
            allow.put(f[0], f.length > 2 ? f[2] : "");
        }
        List<String> unlisted = new ArrayList<>();
        java.util.Set<String> asymmetricIds = new java.util.HashSet<>();
        for (String r : rows) {
            String id = r.substring(0, r.indexOf('\t'));
            asymmetricIds.add(id);
            if (!allow.containsKey(id)) {
                unlisted.add(r);
            }
        }
        List<String> stale = allow.keySet().stream()
                .filter(id -> !asymmetricIds.contains(id)).toList();
        if (!stale.isEmpty()) {
            System.out.println("refusal-allowlist STALE rows (fixed parity —"
                    + " REMOVE the lines): " + stale.size());
            stale.stream().limit(10).forEach(s ->
                    System.out.println("  STALE " + s));
        }
        assertTrue(oracleRejects > 0,
                "the oracle rejected nothing: the corpus did not load");
        org.junit.jupiter.api.Assertions.assertEquals(0, unlisted.size(),
                () -> unlisted.size() + " NEW refusal asymmetries not in"
                        + " docs/refusal-allowlist.tsv — fix the parity or"
                        + " add a line WITH A REASON:\n  "
                        + String.join("\n  ", unlisted.subList(0,
                                Math.min(10, unlisted.size()))));
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
