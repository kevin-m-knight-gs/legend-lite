// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * D4 (V7_ARCH_AUDIT 2026-08-28) — the decline-taxonomy guard: the
 * burn-down census keys on decline-reason SPELLINGS, so every
 * classification prefix an emitter uses must be registered in
 * {@link com.legend.exec.CanonDeclines}. An unregistered spelling —
 * a typo, a casual rewording, a new class added without registering —
 * fails the build here instead of silently splitting a census class.
 */
class CanonDeclineTaxonomyTest {

    /** The decline-emitting files (the byte channel's refusal
     * surface). A new emitter file joins this list with its first
     * decline. */
    private static final List<String> EMITTERS = List.of(
            "src/main/java/com/legend/AssertVerdicts.java",
            "src/main/java/com/legend/StatementExecutor.java",
            "src/main/java/com/legend/exec/TdsCompare.java",
            "src/main/java/com/legend/exec/CanonRider.java",
            "src/main/java/com/legend/lowering/CanonicalRenderSql.java");

    /** First string fragment of a decline call — the classification
     * head the census reader matches on. */
    private static final Pattern DECLINE_LITERAL = Pattern.compile(
            "(?:sqlDeclined\\(|declined = |\\bdecline\\(plan,)\\s*\"([^\"]+)\"");

    @Test
    void everyDeclineSpellingIsRegistered() throws IOException {
        List<String> violations = new ArrayList<>();
        int found = 0;
        for (String f : EMITTERS) {
            String src = Files.readString(Path.of(f))
                    .replaceAll("//.*", "");
            Matcher m = DECLINE_LITERAL.matcher(src);
            while (m.find()) {
                found++;
                String head = m.group(1);
                boolean registered = com.legend.exec.CanonDeclines
                        .REGISTERED_PREFIXES.stream()
                        .anyMatch(p -> head.startsWith(p)
                                || p.startsWith(head.strip()));
                if (!registered) {
                    violations.add(f + ": unregistered decline spelling '"
                            + head + "' — register the classification"
                            + " prefix in CanonDeclines (same commit),"
                            + " never respell a census class casually");
                }
            }
        }
        assertTrue(found >= 20, "decline scan found only " + found
                + " sites — the DECLINE_LITERAL pattern or the EMITTERS"
                + " list rotted");
        assertTrue(violations.isEmpty(), String.join("\n", violations));
    }
}
