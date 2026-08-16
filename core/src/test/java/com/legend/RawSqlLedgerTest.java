// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * F1.6 — the R0 rule as a shrink-only ledger (Charter C5.2).
 * {@code RawSqlBoundary}'s contract is "text-level translation of
 * corpus-AUTHORED statements only … never against platform-GENERATED
 * SQL" — and at the F0.1 baseline the contract is not yet true: the
 * translation entry ({@code h2ToDuckDb}) has TWO callers, one clean and
 * one mixed. This ledger pins the caller set so it can only shrink;
 * F7.4 (spell model-derived DDL correctly the first time) drives it to
 * the single legitimate entry and THEN the contract becomes true.
 *
 * <p>Ledger, with text-origin classification:
 * <ul>
 *   <li>{@code StatementExecutor.adaptRaw} — the executeInDb SQL
 *       argument: corpus-AUTHORED test text. LEGITIMATE (origin is
 *       another dialect; adaptation is the boundary's charter).</li>
 *   <li>{@code rcorpus/Runner} seed replay — MIXED: corpus-authored
 *       setup statements AND Java-generated DDL
 *       ({@code Ddl.setUpDataSqlsText} output, which S4's loop spells
 *       H2-style ON PURPOSE so the boundary can rewrite it). The
 *       Java-generated share is the F7.4 work item; when it lands this
 *       entry becomes corpus-only and the ledger does NOT shrink —
 *       the FEED narrows instead (verified by F7.4's acceptance).</li>
 * </ul>
 */
class RawSqlLedgerTest {

    private static final Map<String, Integer> LEDGER = Map.of(
            "StatementExecutor.java", 1,
            "Runner.java", 1);

    private static final Pattern SITE =
            Pattern.compile("RawSqlBoundary(\\.|::)h2ToDuckDb");

    @Test
    void rawSqlTranslationCallersAreLedgered() throws IOException {
        Map<String, Integer> found = new TreeMap<>();
        for (Path root : new Path[] {Path.of("src/main/java"),
                Path.of("src/test/java")}) {
            try (Stream<Path> files = Files.walk(root)) {
                for (Path f : files
                        .filter(p -> p.toString().endsWith(".java"))
                        .filter(p -> !p.getFileName().toString()
                                .equals("RawSqlLedgerTest.java"))
                        .toList()) {
                    Matcher m = SITE.matcher(Files.readString(f)
                            .replaceAll("//.*", "")
                            .replaceAll("(?s)/\\*.*?\\*/", ""));
                    int n = 0;
                    while (m.find()) {
                        n++;
                    }
                    if (n > 0) {
                        found.put(f.getFileName().toString(), n);
                    }
                }
            }
        }
        assertEquals(new TreeMap<>(LEDGER), found,
                "RawSqlBoundary.h2ToDuckDb caller set moved — the R0"
                + " contract admits corpus-AUTHORED text only; a new"
                + " caller is a violation, a removed caller shrinks the"
                + " ledger (F7.4 narrows Runner's feed, not this list)");
    }
}
