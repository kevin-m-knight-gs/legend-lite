// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend.normalizer;

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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * TRANSITIONAL SHAPES must not stay (USER 2026-09-13: "let's make sure it
 * does not stay this way"). {@link MappingView} exists apart from
 * {@link ResolvedMapping} only while two facts depend on EVERY mapping's
 * rewrite: the graph-wide {@link MappedClasses} and the implicit sets the
 * pre-pass appends. This pin counts every place that builds a bare view
 * (shrink-only), and asserts the two die together: when
 * {@code MappedClasses} is gone, {@code MappingView.of} must be gone too
 * (docs/NORMALIZER_CLEAN_SHEET_HOMEWORK_2026_09_13.md, B3 done criteria).
 */
class TransitionalShapesTest {

    private static final Path NORMALIZER = Path.of("src/main/java/com/legend/normalizer");

    /** Bare-view construction sites by file. SHRINK ONLY. */
    private static final Map<String, Integer> VIEW_SITES = new TreeMap<>(Map.of(
            "AssociationSynthesis.java", 1,     // the multi-hop injection (a pre-pass rewrite)
            "ImplicitInheritance.java", 2,      // implicit inheritance + implicit ops (pre-pass rewrites)
            "MappingNormalizer.java", 1,        // routedTargetGainsOperation over a closure mapping (dies in B3)
            "MappingValidation.java", 1));      // the route guard before synthesis

    @Test
    void bareViewConstructionSitesArePinnedAndDieWithTheGlobalMappedSet() throws IOException {
        Map<String, Integer> actual = new TreeMap<>();
        boolean mappedClassesExists = Files.exists(NORMALIZER.resolve("MappedClasses.java"));
        boolean viewExists = Files.exists(NORMALIZER.resolve("MappingView.java"));
        Pattern site = Pattern.compile("\\bMappingView\\.of\\(");
        try (Stream<Path> s = Files.walk(NORMALIZER)) {
            for (Path f : s.filter(p -> p.toString().endsWith(".java")).toList()) {
                int n = 0;
                for (String line : Files.readAllLines(f)) {
                    String code = line.strip();
                    if (code.startsWith("*") || code.startsWith("//") || code.startsWith("/*")) {
                        continue;   // documentation names the factory; only code counts
                    }
                    Matcher m = site.matcher(line);
                    while (m.find()) {
                        n++;
                    }
                }
                if (n > 0) {
                    actual.put(f.getFileName().toString(), n);
                }
            }
        }
        assertEquals(VIEW_SITES, actual, "bare MappingView construction sites drifted: GROWTH means a new"
                + " pre-pass rewrite asking a closure question — put it in ResolvedMapping's construction"
                + " instead; SHRINKAGE means a rewrite died — ratchet the row down in the same commit");
        assertTrue(mappedClassesExists == viewExists,
                "MappingView and MappedClasses are one transitional shape: they must be deleted together (B3)");
    }
}
