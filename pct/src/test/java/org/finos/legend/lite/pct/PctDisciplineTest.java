// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package org.finos.legend.lite.pct;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * F1.11 — PCT's best property, pinned (audit §4.3.1, verbatim: "There
 * is no comparison logic in Java anywhere in the PCT module... A 'fix'
 * that moved comparison into Java to dodge the rendering problem would
 * be far worse than what is there"). assertEquals stays in interpreted
 * Pure with both sides in Pure's own value domain; row order is the
 * database's. Zero sort/dedupe/tolerance spellings in this module;
 * stays zero.
 */
class PctDisciplineTest {

    private static final Pattern SITE = Pattern.compile(
            "Collections\\.sort\\(|\\.sorted\\(|\\.distinct\\(\\)"
            + "|\\.sort\\(|Math\\.abs\\(.*<|new TreeSet|new TreeMap");

    @Test
    void noJavaSideComparisonInPct() throws IOException {
        List<String> bad = new ArrayList<>();
        try (Stream<Path> files = Files.walk(Path.of("src"))) {
            for (Path f : files.filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> !p.getFileName().toString()
                            .equals("PctDisciplineTest.java"))
                    .toList()) {
                Matcher m = SITE.matcher(Files.readString(f));
                while (m.find()) {
                    bad.add(f.getFileName() + ": " + m.group());
                }
            }
        }
        assertTrue(bad.isEmpty(),
                "Java-side comparison/ordering machinery appeared in the"
                + " PCT module: " + bad + " — comparison stays in"
                + " interpreted Pure (audit §4.3.1); do not dodge a"
                + " rendering problem by comparing in Java");
    }
}
