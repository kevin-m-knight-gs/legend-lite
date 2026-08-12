package com.legend.equivalence;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * WHOLE-DOCUMENT parity: for every corpus source the oracle accepts,
 * {@code PmcdParser.parseDocument} must produce the engine's ENTIRE PMCD
 * byte-for-byte — envelope, element order, and the
 * {@code __internal__::SectionIndex} (spans included). This subsumes the
 * per-element ledger: any deviation anywhere in parsing, ordering, or
 * section bookkeeping surfaces here as a document diff.
 */
class PmcdEquivalenceTest {

    @Test
    void wholeDocumentByteParity() throws Exception {
        var mapper = org.finos.legend.engine.shared.core.ObjectMapperFactory
                .getNewStandardObjectMapperWithPureProtocolExtensionSupports();
        int match = 0;
        int oracleRejects = 0;
        List<String> weRefuse = new ArrayList<>();
        List<String> modelRefuse = new ArrayList<>();
        List<String> diffs = new ArrayList<>();
        Map<String, Integer> refuseByMsg = new TreeMap<>();
        // Corpus.all() already includes the inline-snippet tiers (C4/C5) —
        // re-adding them here double-counted accepted rows (the parallel
        // audit session's correction: 5,259 distinct, not 8,186)
        for (Corpus.Source src : Corpus.all()) {
            String expected;
            try {
                // shared with the element ledger — ONE oracle parse per source
                expected = mapper.writeValueAsString(OracleParses.acquire(src));
            } catch (Throwable t) {
                oracleRejects++;
                continue;
            }
            String actual;
            try {
                actual = com.legend.parser.PmcdParser
                        .parseDocument(src.text());
            } catch (Throwable t) {
                Throwable r = t;
                while (r.getCause() != null && r.getCause() != r) {
                    r = r.getCause();
                }
                String m = String.valueOf(r.getMessage())
                        .replaceAll("\\s+", " ");
                weRefuse.add(src.id() + " :: " + m);
                refuseByMsg.merge(m.length() > 60 ? m.substring(0, 60) : m,
                        1, Integer::sum);
                continue;
            }
            if (Comparators.sameBytes(expected, actual)) {
                match++;
            } else {
                diffs.add(src.id() + " :: "
                        + firstDivergence(expected, actual));
            }
            // INVERSE-PARITY NET, MODEL PATH (2026-08-11): the protocol
            // path above proves bytes; this proves the MODEL transform
            // also reads every oracle-accepted source. It caught two
            // wrong-arity associations CRASHING FromProtocol (the engine
            // parses them and refuses at COMPILE — lite now refuses with
            // the engine-verbatim compile text, the one allowed family).
            try {
                com.legend.parser.ElementParser.parse(src.text());
            } catch (Throwable t) {
                Throwable r = t;
                while (r.getCause() != null && r.getCause() != r) {
                    r = r.getCause();
                }
                String m = String.valueOf(r.getMessage());
                // the COMPILE-SEAM family: the engine parse-accepts these
                // and ITS compiler refuses; lite refuses at FromProtocol
                // with compile-style text (protocol bytes still match).
                // ID-LEVEL allowlist, not a message prefix — a prefix
                // absorbs unrelated future refusals
                // (HARNESS_SIMPLIFICATION_PLAN 5c)
                if (!modelRefuseAllowlist().contains(src.id())) {
                    modelRefuse.add(src.id() + " :: "
                            + m.replaceAll("\\s+", " "));
                }
            }
        }
        System.out.println("PMCD document parity: " + match + " match, "
                + diffs.size() + " diff, " + weRefuse.size()
                + " we-refuse, " + oracleRejects + " oracle-rejects");
        refuseByMsg.entrySet().stream().limit(15).forEach(e ->
                System.out.println("  REFUSE " + e.getValue() + "x "
                        + e.getKey()));
        diffs.stream().limit(15).forEach(d ->
                System.out.println("  DIFF " + d));
        weRefuse.stream().limit(10).forEach(d ->
                System.out.println("  REFUSE-ROW " + d));
        assertEquals(0, diffs.size(),
                () -> diffs.size() + " document diffs; first:\n  "
                        + String.join("\n  ",
                                diffs.subList(0, Math.min(10, diffs.size()))));
        assertEquals(0, weRefuse.size(),
                () -> weRefuse.size() + " oracle-accepted files we refuse;"
                        + " first:\n  " + String.join("\n  ", weRefuse
                                .subList(0, Math.min(10, weRefuse.size()))));
        assertEquals(0, modelRefuse.size(),
                () -> modelRefuse.size() + " oracle-accepted sources the"
                        + " MODEL path refuses (inverse-parity net — a"
                        + " grammar arm or transform we lack):\n  "
                        + String.join("\n  ", modelRefuse.subList(0,
                                Math.min(10, modelRefuse.size()))));
    }

    private static String firstDivergence(String expected, String actual) {
        int n = Math.min(expected.length(), actual.length());
        int i = 0;
        while (i < n && expected.charAt(i) == actual.charAt(i)) {
            i++;
        }
        return "byte " + i + " | expected …"
                + expected.substring(Math.max(0, i - 30),
                        Math.min(expected.length(), i + 50))
                + "… | actual …"
                + actual.substring(Math.max(0, i - 30),
                        Math.min(actual.length(), i + 50))
                + "…";
    }

    private static java.util.Set<String> modelRefuseIds;

    /** The id-level compile-seam allowlist (docs/model-refuse-allowlist.tsv). */
    private static java.util.Set<String> modelRefuseAllowlist() {
        if (modelRefuseIds == null) {
            java.util.Set<String> ids = new java.util.HashSet<>();
            try {
                for (String line : java.nio.file.Files.readAllLines(
                        java.nio.file.Path.of("..", "docs",
                                "model-refuse-allowlist.tsv"))) {
                    if (!line.startsWith("#") && !line.isBlank()) {
                        ids.add(line.split("\t", 2)[0]);
                    }
                }
            } catch (java.io.IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
            modelRefuseIds = ids;
        }
        return modelRefuseIds;
    }
}
