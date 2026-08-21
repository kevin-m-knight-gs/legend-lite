// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * THE Z2 RULING'S GUARD (Charter Clause 2c, ratified 2026-08-19):
 * {@code PureAsserts.equalScalar}/{@code equal} is the VERDICT-CHANNEL
 * equality — World 1's adjudicator over database-produced values,
 * nothing else. Its TEST-CHANNEL tolerance arms (the TDSNull sentinel,
 * the 2-ULP dialect-arithmetic leniency) are sound ONLY because every
 * caller is an adjudicator; product equality is World 2 (an in-query
 * {@code equal()} lowers to SQL — the database is the authority) and
 * must never route through here. This register pins the caller FILE SET
 * mechanically: a new caller is a red build until consciously added
 * with its tenet argument.
 */
class VerdictChannelRegisterTest {

    /** The adjudication cluster — every file permitted to invoke
     * {@code PureAsserts.equal*}. */
    private static final List<String> REGISTER = List.of(
            // the K-arm: statement-root assert-family verdicts (World 1)
            "core/src/main/java/com/legend/AssertVerdicts.java",
            // grid verdicts (corpus/PCT TDS comparison, Clause 2b owner)
            "core/src/main/java/com/legend/exec/GridCompare.java",
            // tree verdicts (wire-tree leaves ride equalScalar as a
            // method reference)
            "core/src/main/java/com/legend/exec/JsonCompare.java",
            // channel-A harness adjudication (test scope, delegates here
            // precisely so it is NOT a third implementation)
            "core/src/test/java/com/legend/harness/EngineTestExecutor.java",
            // the two SPEC fixtures — they pin the adjudicator itself
            "core/src/test/java/com/legend/exec/EqualityWorldsConformanceTest.java",
            "core/src/test/java/com/legend/exec/PureAssertsTest.java");

    @Test
    void equalityCallersAreTheAdjudicationCluster() throws IOException {
        List<String> found = new ArrayList<>();
        for (String root : List.of("src/main/java", "src/test/java",
                "../pct/src/test/java")) {
            if (!Files.isDirectory(Path.of(root))) {
                continue;
            }
            try (Stream<Path> s = Files.walk(Path.of(root))) {
                for (Path f : s.filter(p -> p.toString().endsWith(".java"))
                        .toList()) {
                    if (f.getFileName().toString()
                            .equals("VerdictChannelRegisterTest.java")) {
                        continue;
                    }
                    String src = Files.readString(f)
                            .replaceAll("(?s)/\\*.*?\\*/", "")
                            .replaceAll("//.*", "");
                    if (src.contains("PureAsserts.equal")
                            || src.contains("PureAsserts::equal")) {
                        String rel = f.toString().replace('\\', '/')
                                .replace("../pct/", "pct/");
                        found.add(rel.startsWith("src/")
                                ? "core/" + rel : rel);
                    }
                }
            }
        }
        found.sort(String::compareTo);
        List<String> pinned = new ArrayList<>(REGISTER);
        pinned.sort(String::compareTo);
        assertEquals(pinned, found,
                "PureAsserts equality has a caller outside the verdict"
                + " channel (Charter Clause 2c, Z2 ruling): product"
                + " equality is World 2 — it lowers to SQL. Register an"
                + " ADJUDICATOR here with its tenet argument; a product"
                + " caller gets a spec-only comparator instead (no"
                + " tolerance arms), as a witnessed design leg.");
    }
}
