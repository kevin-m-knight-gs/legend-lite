// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend.equivalence;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * THE OWN-TEST DIALECT CENSUS (provenance routing, user directive
 * 2026-08-12): lite's OWN corpus should compile at LEGEND_LITE — exact
 * engine plus the DECLARED extensions and nothing more. This census
 * measures the distance: every own-corpus snippet the PLATFORM surface
 * accepts is parsed again at LEGEND_LITE; a refusal means either an
 * UNDECLARED extension (declare it in OWN_CORPUS_DECISIONS or burn it)
 * or platform machinery masquerading as a user test (relocate it).
 *
 * <p>REPORT-ONLY at introduction (the Phase-4 discipline): the
 * population lands in {@code target/own-dialect-census.tsv}, categorised
 * by refusal message; the Compiler default flips to LEGEND_LITE only
 * when this census reaches its floor.
 */
class OwnDialectCensusTest {

    @Test
    void ownCorpusAtLegendLite() throws Exception {
        Path repo = Path.of("..").toAbsolutePath().normalize();
        List<Corpus.Source> own = new ArrayList<>();
        for (String module : List.of("core", "parser-equivalence", "pct")) {
            own.addAll(InlineSnippets.extract(repo.resolve(module),
                    "lite-" + module, InlineSnippets.OWN_DECL));
        }
        Assumptions.assumeTrue(!own.isEmpty(), "no own corpus found");

        int platformAccepts = 0;
        int liteAccepts = 0;
        int engineAccepts = 0;
        List<String> rows = new ArrayList<>();
        Map<String, Integer> byMsg = new TreeMap<>();
        for (Corpus.Source s : own) {
            boolean platform;
            try {
                com.legend.parser.ElementParser.parseLegendPlatform(s.text());
                platform = true;
                platformAccepts++;
            } catch (Throwable t) {
                platform = false;
            }
            if (!platform) {
                continue;               // not even platform-parseable — not this census's row
            }
            if (accepts(() -> com.legend.parser.ElementParser
                    .parseLegendEngine(s.text()))) {
                engineAccepts++;
            }
            try {
                com.legend.parser.ElementParser.parseLegendLite(s.text());
                liteAccepts++;
            } catch (Throwable t) {
                Throwable r = t;
                while (r.getCause() != null && r.getCause() != r) {
                    r = r.getCause();
                }
                String m = String.valueOf(r.getMessage())
                        .replaceAll("\\s+", " ").trim();
                String key = m.length() > 70 ? m.substring(0, 70) : m;
                byMsg.merge(key, 1, Integer::sum);
                rows.add(s.id() + "\t" + (m.length() > 160
                        ? m.substring(0, 160) : m));
            }
        }
        StringBuilder b = new StringBuilder();
        b.append("# OWN-TEST DIALECT CENSUS — platform-accepted own snippets that LEGEND_LITE refuses\n");
        b.append("# platform-accepts ").append(platformAccepts)
                .append(" | LEGEND_LITE-accepts ").append(liteAccepts)
                .append(" | LEGEND_ENGINE-accepts ").append(engineAccepts)
                .append(" | census ").append(rows.size()).append('\n');
        byMsg.forEach((k, v) -> b.append("#   ").append(v).append("x  ")
                .append(k).append('\n'));
        b.append("# id\trefusal\n");
        rows.sort(String::compareTo);
        rows.forEach(r -> b.append(r).append('\n'));
        Files.writeString(Path.of("target", "own-dialect-census.tsv"),
                b.toString());
        System.out.println("own-dialect census: " + platformAccepts
                + " platform-accepted, " + liteAccepts + " LITE-accepted, "
                + engineAccepts + " ENGINE-accepted, " + rows.size()
                + " census rows — " + byMsg);
        assertTrue(platformAccepts > 0, "the own corpus did not load");
    }

    private interface ThrowingRunnable {
        void run() throws Throwable;
    }

    private static boolean accepts(ThrowingRunnable r) {
        try {
            r.run();
            return true;
        } catch (Throwable t) {
            return false;
        }
    }
}
