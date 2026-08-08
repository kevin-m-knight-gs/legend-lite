package com.legend.equivalence;

import com.legend.parser.ElementParser;
import org.finos.legend.engine.language.pure.grammar.from.PureGrammarParser;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * THE PULL SENTINEL. When the upstream checkouts move, new grammar spellings in the
 * sections our pipeline consumes (###Mapping/###Relational/###Connection/###Runtime —
 * the lexed sections beyond ###Pure, which byte-parity owns) can silently poison the
 * corpus gate: the 2026-08-04 pull introduced {@code ~src}-style relation mappings and
 * gate 4 collapsed to 2/2567 with nobody watching. This test converts that failure mode
 * into a named, immediate signal: every corpus file containing those sections goes through
 * the REAL pipeline entry ({@code ElementParser.parse}, the lenient dialect the corpus
 * runner uses) AND through the reference, and the two verdicts are compared.
 *
 * <p>The ratchet guards <b>agreement</b>, not throughput. It used to count "files that
 * parse", which quietly made leniency look like coverage and correctness look like
 * regression — refusing a file the engine ALSO refuses scored as a loss. Three buckets
 * now: MATCHED (both accept or both refuse), LENIENT (we accept, the engine refuses) and
 * DEFECT (the engine accepts, we refuse). MATCHED may not fall; LENIENT and DEFECT may
 * only fall.
 *
 * <p>This is parse BEHAVIOUR, not byte parity — no bytes are compared here. Failures are
 * reported by file with the parse error so a pull regression reads as "wall with a name".
 */
class SectionParseSentinelTest {

    /**
     * WHY we accepted a file the reference refused. legend-lite sits between
     * legend-pure and legend-engine, so some leniency is the project working as
     * intended — but "we are a superset" is a rationalisation magnet, so the
     * split is made by EVIDENCE, never by our own say-so. The rule:
     *
     * <p><b>A leniency is justified only if we can NAME the construct we accept
     * and we actually PARSED it. Accepting because we IGNORED something is a
     * bug wearing a superset's clothes.</b>
     *
     * <ul>
     *   <li>{@code JUSTIFIED-crash} — the engine did not refuse, it CRASHED
     *       (NPE, "please notify developer"). Reproducing a crash was never a
     *       compatibility property.</li>
     *   <li>{@code JUSTIFIED-engine-subsets-pure} — the engine deliberately
     *       refuses a construct that is legal Pure and says so ("not supported
     *       yet", "not authorized in Legend Engine"). These files are the
     *       engine's OWN platform sources, which legend-pure compiles in
     *       production; parsing them is the blend thesis, working.</li>
     *   <li>{@code UNJUSTIFIED-we-skipped-it} — the reference has no grammar
     *       registered for a section and refuses the file; we take it only
     *       because unknown {@code ###Section} headers are skipped in silence.
     *       We do not support that section — we cannot see it, and we would
     *       accept arbitrary nonsense inside it just as happily. This bucket
     *       is the audit's "reject unknown sections loudly", and it is also
     *       partly an artifact of which grammar jars the oracle loads.</li>
     *   <li>{@code UNJUSTIFIED-unclassified} — everything else. Unexamined
     *       leniency is not credited.</li>
     * </ul>
     */
    private static String leniencyKind(Throwable referenceRefuses) {
        Throwable root = referenceRefuses;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        String msg = String.valueOf(referenceRefuses.getMessage());
        if (root instanceof NullPointerException
                || root instanceof IndexOutOfBoundsException
                || root instanceof ClassCastException
                || msg.contains("please notify developer")) {
            return "JUSTIFIED-crash";
        }
        if (msg.contains("is not supported yet")
                || msg.contains("not authorized in Legend Engine")) {
            return "JUSTIFIED-engine-subsets-pure";
        }
        if (msg.contains("is not a known section parser")) {
            return "UNJUSTIFIED-we-skipped-it";
        }
        return "UNJUSTIFIED-unclassified";
    }

    /** The lexed sections beyond Pure (Lexer.LEXABLE_SECTIONS minus Pure). */
    private static final Pattern SENTINEL_SECTIONS =
            Pattern.compile("(?m)^###(Mapping|Relational|Connection|Runtime)\\b");

    @Test
    void everyMappingRelationalSectionFileStillParses() throws Exception {
        List<Corpus.Source> sources = Corpus.all();
        Assumptions.assumeTrue(!sources.isEmpty(),
                "no corpus on disk — set -Dlegend.engine.root / -Dlegend.pure.root");

        int inScope = 0;
        int parsed = 0;
        int defects = 0;
        int legalRefusals = 0;
        int matched = 0;
        int lenient = 0;
        int unjustifiedLeniency = 0;
        PureGrammarParser reference = PureGrammarParser.newInstance();
        Map<String, Integer> lenientByKind = new TreeMap<>();
        List<String> lenientFiles = new ArrayList<>();
        List<String> failures = new ArrayList<>();
        Map<String, Integer> byMessage = new TreeMap<>();
        for (Corpus.Source src : sources) {
            Matcher m = SENTINEL_SECTIONS.matcher(src.text());
            if (!m.find()) {
                continue;
            }
            inScope++;
            try {
                ElementParser.parse(src.text());
                parsed++;
                // ACCEPTING is only right when the reference accepts too. A file
                // the reference REFUSES and we take is leniency — the divergence
                // a raw parse count cannot see, and the one that made this gate
                // punish correctness (rejecting legend-pure's ###Diagram dialect
                // the way the engine does used to read here as a REGRESSION).
                try {
                    reference.parseModel(src.text());
                    matched++;
                } catch (Throwable referenceRefuses) {
                    lenient++;
                    String why = leniencyKind(referenceRefuses);
                    if (why.startsWith("UNJUSTIFIED")) {
                        unjustifiedLeniency++;
                    }
                    lenientByKind.merge(why, 1, Integer::sum);
                    lenientFiles.add(why + "  " + src.id() + " :: "
                            + String.valueOf(referenceRefuses.getMessage())
                                    .replaceAll("\\s+", " "));
                }
            } catch (Throwable t) {
                // THE ORACLE (implementation audit §3.4): a failure only counts
                // as a drop-in DEFECT when the reference parser ACCEPTS the
                // file — a file the reference also rejects is a legal refusal,
                // not a gap. Without this split the ratchet cannot tell a
                // fixed defect from an upstream file that got less legal.
                String kind;
                try {
                    reference.parseModel(src.text());
                    defects++;
                    kind = "DEFECT";
                } catch (Throwable alsoRejected) {
                    legalRefusals++;
                    matched++;                  // both refuse — matching behaviour
                    kind = "LEGAL-REFUSAL";
                }
                String msg = String.valueOf(t.getMessage()).replaceAll("\\s+", " ");
                msg = msg.substring(0, Math.min(120, msg.length()));
                failures.add(kind + " " + src.id() + " :: " + msg);
                byMessage.merge(kind + " :: " + msg, 1, Integer::sum);
            }
        }

        StringBuilder report = new StringBuilder();
        report.append("SECTION PARSE SENTINEL — Mapping/Relational/Connection/Runtime\n")
                .append("=".repeat(72)).append('\n')
                .append(String.format("files in scope        : %d%n", inScope))
                .append(String.format("parse cleanly         : %d%n", parsed))
                .append(String.format("parse failures        : %d%n", failures.size()))
                .append(String.format("  reference ACCEPTS   : %d (drop-in DEFECTS)%n", defects))
                .append(String.format("  reference rejects   : %d (legal refusals)%n",
                        legalRefusals))
                .append(String.format("%nBEHAVIOUR vs the reference (what the ratchet"
                        + " actually guards)%n"))
                .append(String.format("  MATCHED             : %d"
                        + " (both accept, or both refuse)%n", matched))
                .append(String.format("  LENIENT             : %d"
                        + " (we accept, reference REFUSES)%n", lenient))
                .append(String.format("  DEFECT              : %d"
                        + " (reference accepts, we refuse)%n", defects));
        if (!lenientFiles.isEmpty()) {
            report.append("\nLENIENT by JUSTIFICATION — see leniencyKind(): a superset"
                            + " claim needs a NAMED construct we actually parsed\n")
                    .append("-".repeat(72)).append('\n');
            lenientByKind.entrySet().stream()
                    .sorted((x, y) -> y.getValue() - x.getValue())
                    .forEach(e -> report.append(String.format("  %5d  %s%n",
                            e.getValue(), e.getKey())));
            report.append("\nLENIENT — files we take that the engine will not\n")
                    .append("-".repeat(72)).append('\n');
            lenientFiles.stream().limit(20).forEach(f ->
                    report.append("  ").append(f).append('\n'));
        }
        report.append("\nFAILURES by message (a NEW message after a pull = the drift)\n")
                .append("-".repeat(72)).append('\n');
        byMessage.entrySet().stream().sorted((x, y) -> y.getValue() - x.getValue())
                .limit(20)
                .forEach(e -> report.append(String.format("  %5d  %s%n",
                        e.getValue(), e.getKey())));
        report.append('\n');
        failures.stream().limit(40).forEach(f -> report.append("  ").append(f).append('\n'));
        Files.writeString(Path.of("target", "section-sentinel-report.txt"),
                report.toString());
        System.out.println(report);

        assertTrue(inScope > 0, "sentinel matched no files: the corpus shape changed");
        assertTrue(matched >= MIN_BEHAVIOUR_MATCHED,
                "files whose accept/reject MATCHES the engine DROPPED: " + matched
                        + " < " + MIN_BEHAVIOUR_MATCHED + " — an upstream pull likely"
                        + " introduced new grammar; see"
                        + " target/section-sentinel-report.txt before running the"
                        + " corpus gates.");
        assertTrue(lenient <= MAX_LENIENT,
                "files we accept that the engine REFUSES grew: " + lenient + " > "
                        + MAX_LENIENT + " — a drop-in that takes what the engine"
                        + " rejects is not a drop-in; see"
                        + " target/section-sentinel-report.txt");
        assertTrue(unjustifiedLeniency <= MAX_UNJUSTIFIED_LENIENCY,
                "leniency we cannot justify grew: " + unjustifiedLeniency + " > "
                        + MAX_UNJUSTIFIED_LENIENCY + " — accepting a file because we"
                        + " SKIPPED what we could not read is a bug, not a superset;"
                        + " see the LENIENT-by-justification table in"
                        + " target/section-sentinel-report.txt");
        assertTrue(defects <= MAX_DROP_IN_DEFECTS,
                "reference-accepted files we fail to parse GREW: " + defects + " > "
                        + MAX_DROP_IN_DEFECTS + " — a real drop-in gap opened; see"
                        + " target/section-sentinel-report.txt");
    }

    /** Failures on files the reference ACCEPTS — the honest defect count the
     *  coverage ratchet above cannot see (implementation audit §3.4). Ratcheted
     *  DOWN only; section parity burns it to zero. */
    private static final int MAX_DROP_IN_DEFECTS = 126;   // 146 - 20 AggregationAware-Pure

    /**
     * Files whose ACCEPT/REJECT decision matches the engine's — the property a
     * drop-in actually has to hold, and the pull-drift signal this test exists
     * for. It replaced a raw "files that parse" count, which silently rewarded
     * LENIENCY: refusing a file the engine also refuses scored as a coverage
     * regression, so every fix in the leniency programme (742 cases queued)
     * would have had to fight its own gate. Bump when matching grows; a drop
     * means a pull moved the grammar under us.
     */
    private static final int MIN_BEHAVIOUR_MATCHED = 840;

    /** Files we accept that the engine REFUSES. Ratcheted DOWN only — this is
     *  the leniency surface, and a drop-in's is zero. */
    private static final int MAX_LENIENT = 148;

    /** Leniency we CANNOT justify — files we take only because we skipped what
     *  we could not read, plus anything unexamined. Ratcheted DOWN only; this
     *  is the half of {@link #MAX_LENIENT} that is simply a bug. */
    private static final int MAX_UNJUSTIFIED_LENIENCY = 127;
}
