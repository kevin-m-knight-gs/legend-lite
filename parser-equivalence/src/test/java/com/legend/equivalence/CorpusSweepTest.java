// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend.equivalence;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.antlr.v4.runtime.BaseErrorListener;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.RecognitionException;
import org.antlr.v4.runtime.Recognizer;
import org.finos.legend.engine.language.pure.grammar.from.PureGrammarParser;
import org.finos.legend.engine.language.pure.grammar.from.extension.PureGrammarParserExtensions;
import org.finos.legend.engine.shared.core.ObjectMapperFactory;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * THE SWEEP — the equivalence harness's end state
 * (HARNESS_SIMPLIFICATION_PLAN): ONE pass over the manifest-pinned
 * corpus, ONE oracle parse per source, every claim computed as a column
 * of the same iteration and asserted TOGETHER at the end. It replaces
 * six test classes (CorpusEquivalence, PmcdEquivalence, RefusalSymmetry,
 * LeniencyCatalog, StrictDialectParity, SpiSeamProof) and the
 * {@code OracleParses} cache whose eviction tuning existed only because
 * those six each ran their own corpus loop.
 *
 * <p>THE CLAIM FAMILIES (each with its numeric ratchets):
 * <ol>
 *   <li><b>Byte equality where both accept</b> — lite's document parser
 *       AND the SPI seam (engine+lite bridge) byte-match the oracle's
 *       full PMCD; the seam's engine-serialize-only bucket is
 *       membership-proven per row ({@link Comparators}).</li>
 *   <li><b>Verdict symmetry</b> — oracle threw ⟺ we threw, every
 *       exception a line of a checked-in, shrink-only allowlist file
 *       ({@code docs/refusal-allowlist.tsv},
 *       {@code docs/model-refuse-allowlist.tsv}); stale lines are
 *       reported for removal.</li>
 *   <li><b>Instrument honesty</b> — the M3 second-reference calibrates
 *       itself every run; the comparator self-test lives in
 *       {@code ComparatorSelfTest}; the corpus is SHA-pinned by
 *       {@code CorpusManifestTest}.</li>
 * </ol>
 *
 * <p>Element-level comparison is a DIAGNOSTIC ({@code ParserEquivalence}
 * localises a document failure to an element index and JSON path); byte-
 * equal documents imply byte-equal elements — proven empirically at the
 * Phase-2 demotion (joint property zero over the full corpus, commit
 * 89c5907d) and structurally since the ledger feeds from the same
 * {@code parseSections} the document serialises.
 *
 * <p>Row-level attribution does NOT live here: the allowlist TSVs are
 * the system of record and only shrink; this sweep merely enforces them.
 */
public class CorpusSweepTest {

    private static final Pattern SECTION = Pattern.compile("(?m)^###(\\w+)");

    // ------------------------------------------------------------------
    // Ratchets (histories in git: SpiSeamProofTest / PmcdEquivalenceTest
    // / RefusalSymmetryTest at commit d4a70c00 and earlier)
    // ------------------------------------------------------------------

    /** Oracle-accepted documents lite must byte-match. Up-only. */
    private static final int MIN_DOCS_MATCHED = 6489;   // 2026-08-14: EVERY oracle-accepted source byte-matches — 100%

    /** Seam byte coverage floor. Up-only. */
    private static final int MIN_SEAM_MATCHED = 5911;

    /** Vanilla-rejected sources the SPI seam accepts — post-flip residue
     *  (upstream walker defects + the reviewed allowlist). Down-only. */
    private static final int MAX_SEAM_LENIENT_ACCEPTS = 22;

    /** Seam rows whose delta is the ENGINE's serialize-only field,
     *  membership-proven per row. Down-only. */
    private static final int MAX_ENGINE_JSON_ASYMMETRY = 9;

    /** Pure-only vanilla-rejected files raw parseStrict accepts — the
     *  strict element surface's own census. Down-only. */
    private static final int MAX_PARSER_LENIENT_ACCEPTS = 181;   // measured 2026-08-12 post burn-down (was 187)

    /** CEILING on the platform-surface leniency catalog (deep-audit #2
     *  2d: the population was unbounded — every row classifies, but
     *  nothing stopped the TOTAL from growing silently). Shrink-only;
     *  measured 2026-08-14. */
    private static final int MAX_LENIENCY_CATALOG = 1470;

    /** M3 second-reference agreement floor on oracle-accepted
     *  section-free sources — below this the "m3-corroborated"
     *  allowlist label stops meaning anything. */
    private static final double M3_CALIBRATION_FLOOR = 95.0;

    @Test
    void oneSweep() throws Exception {
        List<Corpus.Source> sources = Corpus.all();
        // CORPUS-SIZE FLOOR, not an Assumptions skip (deep-audit: "an
        // absent corpus skips GREEN") — a missing checkout must fail
        org.junit.jupiter.api.Assertions.assertTrue(sources.size() > 7000,
                "corpus floor: only " + sources.size() + " sources loaded —"
                        + " check -Dlegend.engine.root/-Dlegend.pure.root");
        Assumptions.assumeTrue(!sources.isEmpty(),
                "no corpus on disk — set -Dlegend.engine.root / -Dlegend.pure.root");
        ObjectMapper mapper = ObjectMapperFactory
                .getNewStandardObjectMapperWithPureProtocolExtensionSupports();
        PureGrammarParser oracle = PureGrammarParser.newInstance();
        List<org.finos.legend.engine.language.pure.grammar.from.extension
                .PureGrammarParserExtension> withLite = new ArrayList<>();
        withLite.add(LegendLiteSectionParser.extension());
        withLite.addAll(org.finos.legend.engine.language.pure.grammar.from
                .extension.PureGrammarParserExtensionLoader.extensions());
        PureGrammarParser spi = PureGrammarParser.newInstance(
                PureGrammarParserExtensions.fromExtensions(withLite));

        Map<String, String> refusalAllow =
                readAllowlist("refusal-allowlist.tsv");
        java.util.Map<String, String> c12Walls =
                readAllowlist("c12-walls.tsv");
        java.util.Map<String, String> c12Diffs =
                readAllowlist("c12-known-diffs.tsv");
        Map<String, String> modelRefuseAllow =
                readAllowlist("model-refuse-allowlist.tsv");

        // accumulators
        int docsMatched = 0;
        int seamMatched = 0;
        int bothReject = 0;
        int oracleAccepts = 0;
        int calAccepted = 0;
        int calAgree = 0;
        int strictLenient = 0;
        List<String> docDiffs = new ArrayList<>();
        List<String> weRefuse = new ArrayList<>();
        List<String> modelRefuse = new ArrayList<>();
        List<String> seamDiffs = new ArrayList<>();
        List<String> seamRejects = new ArrayList<>();
        List<String> seamAccepts = new ArrayList<>();
        List<String> engineAsym = new ArrayList<>();
        List<String> asymRows = new ArrayList<>();
        List<String> unlistedAsym = new ArrayList<>();
        List<String> dialectLeaks = new ArrayList<>();
        List<String> unclassified = new ArrayList<>();
        List<String> strictLenientIds = new ArrayList<>();
        List<String> strictUnexplained = new ArrayList<>();
        List<String> protocolInvalid = new ArrayList<>();
        Map<String, Integer> strictLenientByClass = new TreeMap<>();
        Map<String, Integer> catalogByClass = new TreeMap<>();
        StringBuilder catalog = new StringBuilder();
        java.util.Set<String> asymIds = new java.util.HashSet<>();

        for (Corpus.Source src : sources) {
            String oracleJson = null;
            Throwable oracleRoot = null;
            try {
                oracleJson = mapper.writeValueAsString(
                        oracle.parseModel(src.text()));
            } catch (Throwable t) {
                oracleRoot = rootOf(t);
            }

            if (oracleJson != null) {
                oracleAccepts++;
                // CLAIM 1a: the document parser byte-matches
                try {
                    String doc = com.legend.parser.PmcdParser
                            .parseDocument(src.text());
                    if (Comparators.sameBytes(oracleJson, doc)) {
                        docsMatched++;
                    } else if (!c12Diffs.containsKey(src.id())) {
                        docDiffs.add(src.id() + " :: "
                                + firstDivergence(oracleJson, doc));
                    }
                } catch (Throwable t) {
                    if (!c12Walls.containsKey(src.id())) weRefuse.add(src.id() + " :: "
                            + msgOf(rootOf(t)));
                }
                // CLAIM 2a: the MODEL transform reads every accepted
                // source (compile-seam family excepted, BY ID)
                try {
                    Surfaces.platform(src.text());
                } catch (Throwable t) {
                    if (!modelRefuseAllow.containsKey(src.id())) {
                        modelRefuse.add(src.id() + " :: "
                                + msgOf(rootOf(t)));
                    }
                }
                // CLAIM 1b: the SPI seam byte-matches (the drop-in shape)
                try {
                    String spiJson = mapper.writeValueAsString(
                            spi.parseModel(src.text()));
                    if (Comparators.sameBytes(oracleJson, spiJson)) {
                        seamMatched++;
                    } else if (Comparators.engineSerializeOnlyDelta(mapper,
                            oracleJson, spiJson)) {
                        engineAsym.add(src.id());
                    } else {
                        seamDiffs.add(src.id());
                    }
                } catch (Throwable t) {
                    seamRejects.add(src.id() + " :: " + msgOf(rootOf(t)));
                }
                // CLAIM 3a: M3 self-calibration (section-free only)
                if (!SECTION.matcher(src.text()).find()) {
                    calAccepted++;
                    if (m3Accepts(src.text())) {
                        calAgree++;
                    }
                }
                continue;
            }

            // ---------------- oracle REFUSED this source ----------------
            boolean docAccepts = accepts(() -> com.legend.parser.PmcdParser
                    .parseDocument(src.text()));
            boolean strictAccepts = accepts(() -> Surfaces.engine(src.text()));
            boolean lenientAccepts = accepts(() -> Surfaces.platform(src.text()));
            boolean pureOnly = !SECTION.matcher(src.text()).find();

            if (!docAccepts && !strictAccepts) {
                bothReject++;
            } else {
                if (docAccepts) {
                    // PROTOCOL-CHECK (sibling handoff instrument, adopted
                    // 2026-08-14): a document WE accept that the oracle
                    // refuses must still emit JSON the ENGINE's own
                    // protocol classes deserialize — a wrong-kind key
                    // emits wire no downstream consumer can read (the
                    // Persistence corruption class). Byte-matched docs
                    // are exempt by construction: their wire IS the
                    // engine's.
                    try {
                        mapper.readValue(com.legend.parser.PmcdParser
                                .parseDocument(src.text()),
                                org.finos.legend.engine.protocol.pure.v1
                                        .model.context.PureModelContextData.class);
                    } catch (Throwable t) {
                        protocolInvalid.add(src.id() + " :: "
                                + msgOf(rootOf(t)));
                    }
                }
                // CLAIM 2b: verdict symmetry — every asymmetry is an
                // allowlist line
                String category = !pureOnly ? "sectioned"
                        : m3Accepts(src.text()) ? "m3-corroborated"
                                : "m3-rejects";
                String surfaces = (docAccepts ? "document" : "")
                        + (docAccepts && strictAccepts ? "+" : "")
                        + (strictAccepts ? "strict" : "");
                asymIds.add(src.id());
                asymRows.add(src.id() + "\t" + category + "\t" + surfaces
                        + "\t" + msgOf(oracleRoot));
                if (!refusalAllow.containsKey(src.id())) {
                    unlistedAsym.add(src.id() + " [" + category + "/"
                            + surfaces + "] " + msgOf(oracleRoot));
                }
            }
            if (pureOnly && strictAccepts) {
                strictLenient++;
                strictLenientIds.add(src.id() + " :: vanilla: "
                        + msgOf(oracleRoot));
                // LEG-1 CLOSURE (2026-08-13): every strict-lenient row must
                // CLASSIFY — the bare count looked like unadjudicated debt
                // when it was really 154 oracle-NPE + skew-claims + nullmsg
                // pure-dialect files; an unexplained row goes red here
                CLASSIFYING_ID.set(src.id());
                String scls = classify(oracleRoot, src.text());
                CLASSIFYING_ID.remove();
                if (scls == null) {
                    strictUnexplained.add(src.id() + " :: "
                            + msgOf(oracleRoot));
                } else {
                    strictLenientByClass.merge(scls, 1, Integer::sum);
                }
            }
            // seam census: the drop-in accepting what vanilla refuses
            if (accepts(() -> spi.parseModel(src.text()))) {
                seamAccepts.add(src.id() + " :: vanilla: "
                        + msgOf(oracleRoot));
            }
            // leniency catalog + dialect parity (the lenient MODEL surface)
            if (lenientAccepts) {
                CLASSIFYING_ID.set(src.id());
                String cls = classify(oracleRoot, src.text());
                CLASSIFYING_ID.remove();
                if (cls == null) {
                    unclassified.add(src.id() + " :: " + msgOf(oracleRoot));
                    cls = "UNCLASSIFIED";
                }
                catalogByClass.merge(cls, 1, Integer::sum);
                catalog.append(cls).append('\t').append(src.id())
                        .append('\t').append(msgOf(oracleRoot)).append('\n');
                if (cls.startsWith("DIALECT-") && strictAccepts) {
                    dialectLeaks.add(cls + " :: " + src.id());
                }
            }
        }

        // stale allowlist rows — parity fixed, lines to REMOVE
        List<String> stale = refusalAllow.keySet().stream()
                .filter(id -> !asymIds.contains(id)).toList();

        writeReports(sources.size(), oracleAccepts, docsMatched, docDiffs,
                weRefuse, bothReject, seamMatched, engineAsym, seamDiffs,
                seamAccepts, seamRejects, strictLenient, strictLenientIds,
                asymRows, catalog, catalogByClass, stale);
        double calibration = calAccepted == 0 ? 0
                : 100.0 * calAgree / calAccepted;
        System.out.printf("SWEEP: %d sources | oracle accepts %d | docs"
                + " matched %d diff %d weRefuse %d | seam %d/%d asym %d |"
                + " both-reject %d asymmetric %d (stale %d) | strictLenient"
                + " %d | M3 cal %.1f%%%n",
                sources.size(), oracleAccepts, docsMatched, docDiffs.size(),
                weRefuse.size(), seamMatched, seamRejects.size(),
                engineAsym.size(), bothReject, asymRows.size(), stale.size(),
                strictLenient, calibration);

        final int fDocs = docsMatched;
        final int fSeam = seamMatched;
        final int fStrict = strictLenient;
        final double fCal = calibration;
        final int fAccepts = oracleAccepts;
        final int fBoth = bothReject;
        final int fCatalogTotal = catalogByClass.values().stream()
                .mapToInt(Integer::intValue).sum();
        System.out.println("strictLenient by class: " + strictLenientByClass);
        assertAll(
                () -> assertEquals(0, strictUnexplained.size(),
                        () -> "STRICT-LENIENT rows with NO catalog class —"
                                + " unadjudicated acceptance:\n  "
                                + head(strictUnexplained)),
                () -> assertEquals(0, docDiffs.size(), () -> "document byte"
                        + " diffs:\n  " + head(docDiffs)),
                () -> assertEquals(0, weRefuse.size(), () -> "oracle-accepted"
                        + " sources the document parser refuses:\n  "
                        + head(weRefuse)),
                () -> {
                    try {
                        java.nio.file.Files.write(java.nio.file.Path.of(
                                "target", "model-refuse.tsv"), modelRefuse);
                    } catch (java.io.IOException ignored) {
                        // diagnostic artifact only
                    }
                    assertEquals(0, modelRefuse.size(), () -> "oracle-"
                        + "accepted sources the MODEL path refuses (not in"
                        + " model-refuse-allowlist.tsv):\n  "
                        + head(modelRefuse));
                },
                () -> assertEquals(0, seamDiffs.size(), () -> "SPI seam byte"
                        + " diffs:\n  " + head(seamDiffs)),
                () -> assertEquals(0, seamRejects.size(), () -> "the SPI seam"
                        + " refused vanilla-accepted sources:\n  "
                        + head(seamRejects)),
                () -> assertEquals(0, unlistedAsym.size(), () -> "NEW refusal"
                        + " asymmetries not in docs/refusal-allowlist.tsv —"
                        + " fix the parity or add a line WITH A REASON:\n  "
                        + head(unlistedAsym)),
                () -> assertEquals(0, dialectLeaks.size(), () -> "DIALECT"
                        + " rows still parse on the strict surface:\n  "
                        + head(dialectLeaks)),
                () -> assertEquals(0, unclassified.size(), () -> "UNCLASSIFIED"
                        + " leniency rows — adjudicate or fix, never"
                        + " ignore:\n  " + head(unclassified)),
                () -> assertTrue(fDocs >= MIN_DOCS_MATCHED,
                        "document coverage shrank: " + fDocs + " < "
                                + MIN_DOCS_MATCHED),
                () -> assertTrue(fSeam >= MIN_SEAM_MATCHED,
                        "seam coverage shrank: " + fSeam + " < "
                                + MIN_SEAM_MATCHED),
                () -> assertTrue(seamAccepts.size() <= MAX_SEAM_LENIENT_ACCEPTS,
                        "seam leniency census grew: " + seamAccepts.size()
                                + " > " + MAX_SEAM_LENIENT_ACCEPTS),
                () -> assertTrue(engineAsym.size() <= MAX_ENGINE_JSON_ASYMMETRY,
                        "engine JSON-asymmetry bucket grew: "
                                + engineAsym.size()),
                () -> assertTrue(fStrict <= MAX_PARSER_LENIENT_ACCEPTS,
                        "parseStrict leniency census grew: " + fStrict
                                + " > " + MAX_PARSER_LENIENT_ACCEPTS),
                () -> assertTrue(fCal >= M3_CALIBRATION_FLOOR,
                        String.format("M3 second-reference calibration %.1f%%"
                                + " below floor %.1f%% — the m3-corroborated"
                                + " label is no longer trustworthy",
                                fCal, M3_CALIBRATION_FLOOR)),
                () -> assertTrue(fAccepts > 0 && fBoth > 0,
                        "degenerate sweep: the corpus did not load"),
                () -> assertTrue(fCatalogTotal <= MAX_LENIENCY_CATALOG,
                        "leniency catalog grew: " + fCatalogTotal + " > "
                                + MAX_LENIENCY_CATALOG
                                + " — new leniency must be adjudicated"),
                () -> assertEquals(0, protocolInvalid.size(),
                        () -> "PROTOCOL-INVALID wire on lenient-accepted"
                                + " docs — the engine's own deserializer"
                                + " refuses what we emit:\n  "
                                + head(protocolInvalid)));
    }

    // ------------------------------------------------------------------
    // The leniency classifier. NOTE (deep-audit H2 correction): for the
    // PLATFORM-surface population this method IS the gate — a non-null
    // return is the pass (unclassified==0 asserts below). The allowlist
    // TSVs gate only the docAccepts/strictAccepts population. Returning
    // a label here is therefore an ACCEPTANCE decision, not diagnostics.
    // ------------------------------------------------------------------

    /** Class a refusal by the ORACLE'S OWN evidence (message/exception),
     *  with one mechanical assist: a bare "Unexpected token" is adjudicated
     *  by OUR strict surface — its engine-verbatim gates name the dialect
     *  construct row by row (ZSkewResidueProbe), and a strict ACCEPT means
     *  the construct is checkout-unreleased grammar (true version skew).
     *  Returns null when nothing matches — the failing case. */
    /** The shrink-only skew-claims ledger (docs/version-skew-claims.tsv):
     *  corpus-source ids whose generic grammar refusals are CLAIMED as
     *  pinned-oracle-vs-checkout skew, pending row-by-row adjudication. */
    private static final java.util.Set<String> SKEW_CLAIMS = loadSkewClaims();

    private static java.util.Set<String> loadSkewClaims() {
        try {
            java.nio.file.Path f = java.nio.file.Path.of("..", "docs",
                    "version-skew-claims.tsv");
            if (!java.nio.file.Files.exists(f)) {
                f = java.nio.file.Path.of("docs", "version-skew-claims.tsv");
            }
            java.util.Set<String> out = new java.util.HashSet<>();
            for (String line : java.nio.file.Files.readAllLines(f)) {
                int tab = line.indexOf('\t');
                out.add(tab < 0 ? line : line.substring(0, tab));
            }
            return out;
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    private static final ThreadLocal<String> CLASSIFYING_ID = new ThreadLocal<>();

    private static boolean versionSkewClaim(Throwable ignored) {
        String id = CLASSIFYING_ID.get();
        return id != null && SKEW_CLAIMS.contains(id);
    }

    public static @com.legend.Nullable String classify(Throwable root, String text) {
        String msg = String.valueOf(root.getMessage());
        if ("Unexpected token".equals(msg.trim())) {
            try {
                Surfaces.engine(text);
                // OUR engine-dialect parser accepting is NOT evidence of
                // unreleased grammar — that's what an over-permissive
                // lite bug looks like (deep-audit #2 2c: this arm was
                // CIRCULAR). Skew is a CLAIM: each row needs a reviewed
                // line in version-skew-claims.tsv or it goes red.
                return versionSkewClaim(root) ? "VERSION-SKEW-claimed"
                        : null;
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
                // an UNRECOGNIZED strict refusal is not skew — it must be
                // named (deep-audit H2: this arm was a pardon)
                return versionSkewClaim(root) ? "VERSION-SKEW-claimed" : null;
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
            // an InputMismatchException with a null message is ANTLR's
            // ORDINARY grammar-refusal path, NOT an oracle defect — the
            // audit found 346 rows laundered under the old label
            // (HARNESS_SIMPLIFICATION_PLAN Phase 7). Real crashes carry
            // messages and classify above.
            if (root.getClass().getSimpleName()
                    .equals("InputMismatchException")) {
                return "GRAMMAR-REFUSAL-nullmsg";
            }
            return "ORACLE-DEFECT-" + root.getClass().getSimpleName();
        }
        // VERSION-SKEW is a CLAIM, not a default: the old fall-through
        // pardoned every generic ANTLR refusal as skew (deep-audit H2 —
        // "essentially any grammar divergence on that surface is labelled
        // version skew"). A claim must be a ROW in
        // docs/version-skew-claims.tsv (shrink-only; each row is an
        // adjudication obligation per DEEP_AUDIT_HANDOFF Leg 1) — an
        // unclaimed generic refusal goes UNCLASSIFIED and the build reds.
        if (msg.startsWith("Unexpected token")
                || msg.contains("mismatched input")
                || msg.contains("extraneous input")
                || msg.contains("missing ") && msg.contains(" at ")) {
            return versionSkewClaim(root) ? "VERSION-SKEW-claimed" : null;
        }
        if (msg.contains("Field '") && msg.contains("' is required")) {
            // any REMAINING required-field refusal is a NEW genuinely-lite
            // leniency — those must be fixed, never cataloged
            return null;
        }
        return null;
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private static Map<String, String> readAllowlist(String name)
            throws java.io.IOException {
        Map<String, String> out = new LinkedHashMap<>();
        for (String line : Files.readAllLines(
                Path.of("..", "docs", name))) {
            if (!line.startsWith("#") && !line.isBlank()) {
                String[] f = line.split("\t", 2);
                out.put(f[0], f.length > 1 ? f[1] : "");
            }
        }
        return out;
    }

    private interface ThrowingRunnable {
        void run() throws Throwable;
    }

    private static boolean accepts(ThrowingRunnable parse) {
        try {
            parse.run();
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private static Throwable rootOf(Throwable t) {
        Throwable r = t;
        while (r.getCause() != null && r.getCause() != r) {
            r = r.getCause();
        }
        return r;
    }

    private static String msgOf(Throwable root) {
        String m = String.valueOf(root.getMessage())
                .replaceAll("\\s+", " ").trim();
        return m.length() > 160 ? m.substring(0, 160) : m;
    }

    private static String head(List<String> rows) {
        return String.join("\n  ",
                rows.subList(0, Math.min(10, rows.size())));
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
                        Math.min(actual.length(), i + 50)) + "…";
    }

    /** Raw ANTLR syntax adjudication by legend-pure's OWN grammar — the
     *  second reference. Accept iff {@code definition()} consumes the
     *  source with zero syntax errors. */
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

    private static void writeReports(int sources, int oracleAccepts,
            int docsMatched, List<String> docDiffs, List<String> weRefuse,
            int bothReject, int seamMatched, List<String> engineAsym,
            List<String> seamDiffs, List<String> seamAccepts,
            List<String> seamRejects, int strictLenient,
            List<String> strictLenientIds, List<String> asymRows,
            StringBuilder catalog, Map<String, Integer> catalogByClass,
            List<String> stale) throws java.io.IOException {
        // equivalence-report.txt — the gate-log extraction reads lines 4-10
        StringBuilder eq = new StringBuilder();
        eq.append("CORPUS SWEEP — one pass, every claim (CorpusSweepTest)\n")
                .append("=".repeat(72)).append('\n')
                .append(String.format("corpus sources        : %d%n", sources))
                .append(String.format("oracle accepts        : %d%n",
                        oracleAccepts))
                .append(String.format("  docs byte-MATCH     : %d%n",
                        docsMatched))
                .append(String.format("  docs DIFF (BUG)     : %d%n",
                        docDiffs.size()))
                .append(String.format("  we-refuse (BUG)     : %d%n",
                        weRefuse.size()))
                .append(String.format("oracle rejects        : %d (both-reject %d)%n",
                        sources - oracleAccepts, bothReject));
        docDiffs.stream().limit(15).forEach(d ->
                eq.append("  DIFF ").append(d).append('\n'));
        try {
            java.nio.file.Files.write(java.nio.file.Path.of("target",
                    "we-refuse.tsv"), weRefuse);
        } catch (java.io.IOException ignored) {
            // diagnostic artifact only
        }
        Files.writeString(Path.of("target", "equivalence-report.txt"),
                eq.toString());

        StringBuilder seam = new StringBuilder();
        seam.append("SPI SEAM — engine+legend-lite vs vanilla engine, full PMCD\n")
                .append("=".repeat(72)).append('\n')
                .append(String.format("files byte-identical  : %d%n", seamMatched))
                .append(String.format("engine JSON-asymmetry : %d%n",
                        engineAsym.size()))
                .append(String.format("DIFF (BUG)            : %d%n",
                        seamDiffs.size()))
                .append(String.format("asymmetric rejects    : %d%n",
                        seamAccepts.size() + seamRejects.size()))
                .append(String.format("parseStrict lenient   : %d%n",
                        strictLenient));
        engineAsym.stream().limit(20).forEach(d ->
                seam.append("  ENGINE-ASYM ").append(d).append('\n'));
        seamAccepts.stream().limit(400).forEach(d ->
                seam.append("  SPI-ACCEPTS ").append(d).append('\n'));
        Files.writeString(Path.of("target", "spi-seam-report.txt"),
                seam.toString());

        StringBuilder asym = new StringBuilder();
        asym.append("# REFUSAL ASYMMETRY — sources the ORACLE rejects that a lite surface accepts\n");
        asym.append("# stale allowlist rows (REMOVE): ").append(stale.size());
        stale.stream().limit(20).forEach(s2 ->
                asym.append("\n#   STALE ").append(s2));
        asym.append("\n# id\tcategory\taccepting-surfaces\toracle-message\n");
        asymRows.stream().sorted().forEach(r ->
                asym.append(r).append('\n'));
        Files.writeString(Path.of("target", "refusal-asymmetry.tsv"),
                asym.toString());

        Files.writeString(Path.of("target", "leniency-catalog.txt"),
                "by class: " + catalogByClass + "\n" + catalog);
        Files.writeString(Path.of("target", "parser-leniency.txt"),
                String.join("\n", strictLenientIds));
        if (!stale.isEmpty()) {
            System.out.println("refusal-allowlist STALE rows (fixed parity —"
                    + " REMOVE the lines): " + stale.size());
            stale.stream().limit(10).forEach(s2 ->
                    System.out.println("  STALE " + s2));
        }
    }
}
