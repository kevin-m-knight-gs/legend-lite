// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SOFT-PASS CEILINGS ({@code maxSoft} — ADVERSARIAL_TENET_AUDIT
 * 2026_08_18 §5): a row that PASSES via a soft channel (sqldiff-pass,
 * adv-pass, zero-assert, rescued) is a weaker claim than an exact
 * pass, and the audit found three of the four columns had no ceiling —
 * a fix that quietly DEMOTED exact passes into soft ones would read as
 * "corpus stable" on the total while the claim degraded underneath.
 * These ceilings pin the committed scoreboard's total row: each soft
 * column may only SHRINK (soft rows graduating to exact) without a
 * deliberate, justified bump.
 */
class CorpusSoftCeilingTest {

    // +10 each 2026-08-21 (shortcut-audit Blocker 1, ADJUDICATED — the
    // written-justification rule this guard's own message demands): the
    // null-drop compiled into value-collection egress (WHERE <cell> IS
    // NOT NULL); the engine does the same drop CLIENT-side (SQLNull->[],
    // relationalMappingExecution.pure:480) so its goldens cannot carry
    // the clause. No exact pass was demoted — the 10 moved rows are the
    // SAME tests row-verified as before, their golden text now one
    // honest clause apart (RelationalCorpusRunner ceiling 299->309, the
    // same adjudication).
    private static final int MAX_SQLDIFF_PASS = 257;
    private static final int MAX_ADV_PASS = 303;
    private static final int MAX_ZERO_ASSERTS = 27;
    private static final int MAX_RESCUED = 613;

    @Test
    void softPassColumnsOnlyShrink() throws IOException {
        Path scoreboard = Path.of("..", "docs/RELATIONAL_CORPUS.md");
        String totalLine = Files.readAllLines(scoreboard).stream()
                .filter(l -> l.startsWith("| **total** |"))
                .reduce((a, b) -> b)
                .orElseThrow(() -> new IllegalStateException(
                        "RELATIONAL_CORPUS.md has no '| **total** |' row"
                        + " — the scoreboard format moved; re-point this"
                        + " ceiling before trusting the corpus counts"));
        // numeric columns after the 'total' label: tests, pass, fail,
        // error, shape, sqldiff-pass, adv-pass, 0-asserts, rescued
        Matcher m = Pattern.compile("\\|\\s*(\\d+)\\s*(?=\\|)")
                .matcher(totalLine.replace("**", ""));
        int[] cols = new int[9];
        int n = 0;
        while (m.find() && n < 9) {
            cols[n++] = Integer.parseInt(m.group(1));
        }
        assertTrue(n == 9, "total row parsed " + n
                + " numeric columns, expected 9: " + totalLine);
        StringBuilder drift = new StringBuilder();
        ceil(drift, "sqldiff-pass", cols[5], MAX_SQLDIFF_PASS);
        ceil(drift, "adv-pass", cols[6], MAX_ADV_PASS);
        ceil(drift, "0-asserts", cols[7], MAX_ZERO_ASSERTS);
        ceil(drift, "rescued", cols[8], MAX_RESCUED);
        assertTrue(drift.length() == 0,
                "soft-pass ceiling drift (audit 2026-08-18 maxSoft):"
                + drift);
    }

    private static void ceil(StringBuilder drift, String col, int actual,
            int max) {
        if (actual > max) {
            drift.append("\n  ").append(col).append(": ").append(actual)
                    .append(" > ").append(max)
                    .append(" — soft passes GREW; exact passes may have"
                            + " been demoted. Bump only with a written"
                            + " justification in the same commit");
        }
    }
}
