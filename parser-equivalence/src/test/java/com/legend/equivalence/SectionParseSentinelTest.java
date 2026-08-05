package com.legend.equivalence;

import com.legend.parser.ElementParser;
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
 * into a named, immediate signal: every corpus file containing those sections must parse
 * through the REAL pipeline entry ({@code ElementParser.parse}, the lenient dialect the
 * corpus runner uses), and the count of parsing files may not drop.
 *
 * <p>This is parse COVERAGE, not parity — no bytes are compared. Failures are reported
 * by file with the parse error so a pull regression reads as "wall with a name".
 */
class SectionParseSentinelTest {

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
            } catch (Throwable t) {
                String msg = String.valueOf(t.getMessage()).replaceAll("\\s+", " ");
                msg = msg.substring(0, Math.min(120, msg.length()));
                failures.add(src.id() + " :: " + msg);
                byMessage.merge(msg, 1, Integer::sum);
            }
        }

        StringBuilder report = new StringBuilder();
        report.append("SECTION PARSE SENTINEL — Mapping/Relational/Connection/Runtime\n")
                .append("=".repeat(72)).append('\n')
                .append(String.format("files in scope        : %d%n", inScope))
                .append(String.format("parse cleanly         : %d%n", parsed))
                .append(String.format("parse failures        : %d%n", failures.size()));
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
        assertTrue(parsed >= MIN_FILES_PARSED,
                "section parse coverage DROPPED: " + parsed + " < " + MIN_FILES_PARSED
                        + " — an upstream pull likely introduced new grammar; see"
                        + " target/section-sentinel-report.txt before running the"
                        + " corpus gates.");
    }

    /** Baseline at introduction (2026-08-05). Bump when coverage grows; a drop is the
     *  pull-drift signal this test exists for. */
    private static final int MIN_FILES_PARSED = 856;
}
