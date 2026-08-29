// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend.testing;

import java.nio.file.Path;

/**
 * A walked path carries the platform separator (81347e4e) — the two
 * operations every scope-matching harness in the tree kept re-deriving,
 * in three different spellings: {@code getFileSystem().getSeparator()},
 * a hardcoded {@code '\\'}, and a {@code '/'} that is a no-op on the
 * platform it was written for.
 *
 * <p>NOT a {@code util/} package in disguise — Invariant 2
 * ({@code ArchitectureTest.coreModuleHasNoUtilPackage}) bans that, and
 * bans it over production classes for a reason. These live in test
 * scope because that is where every caller is: {@link #containsSubPath}
 * has no production caller at all, and the one production site that
 * spells a path ({@code sql.dialect.DuckDb}, rendering a DuckDB glob
 * where a backslash is the ESCAPE character) is a dialect concern that
 * stays with its dialect.
 *
 * <p>DUPLICATED on purpose: {@code parser-equivalence} carries a
 * byte-identical copy in its own test tree. A core test-jar was tried
 * and reverted — it is not a surgical share. Under {@code mvn test} the
 * reactor substitutes core's whole {@code target/test-classes} for the
 * dependency, the jar plugin's {@code <includes>} notwithstanding, so
 * {@code src/test/resources/META-INF/services/com.legend.spi.SectionGrammar}
 * reaches the consumer, {@code ServiceLoader} finds
 * {@code ToySectionGrammar}, and {@code ###Toy} becomes a KNOWN section
 * parser over there — which flipped one {@code OwnDialectCensusTest} row
 * from LITE-refused to LITE-accepted (census 25 -> 24). Fifty lines twice
 * beats exporting a test tree. Keep the two copies in step; the tests
 * for both live in core's {@code WalkedPathTest}.
 */
public final class WalkedPath {

    private WalkedPath() {
    }

    /**
     * {@code path} spelled with {@code separator} between its elements —
     * the general form of the {@code DuckDb} idiom, including its
     * short-circuit when the platform already agrees.
     *
     * <p>The separator is not always {@code "/"}: a class FQN derived
     * from a relativized {@code .class} path spells with {@code "."}.
     */
    public static String spell(Path path, String separator) {
        String platform = path.getFileSystem().getSeparator();
        return platform.equals(separator)
                ? path.toString()
                : path.toString().replace(platform, separator);
    }

    /**
     * True when {@code elements} appear as a CONSECUTIVE run of
     * {@code path}'s name elements — {@code containsSubPath(p, "src",
     * "test")} for what a string match spells {@code "/src/test/"}.
     *
     * <p>Element-wise on purpose, rather than {@code spell(p, "/")
     * .contains("/src/test/")}. There is no normalization step for a
     * caller to forget, so it cannot go separator-blind; it matches a
     * FIRST or LAST element, which the slash-delimited form silently
     * misses (a {@code target/} at the head of a relative walk); and it
     * cannot partial-match, so {@code "test"} never fires on
     * {@code testing/}. {@link Path} already carries element-wise
     * {@code startsWith} and {@code endsWith} — containment is the
     * member the JDK left out, and this is it.
     */
    public static boolean containsSubPath(Path path, String... elements) {
        if (elements.length == 0) {
            throw new IllegalArgumentException(
                    "containsSubPath needs at least one element");
        }
        int names = path.getNameCount();
        for (int i = 0; i + elements.length <= names; i++) {
            if (matchesAt(path, i, elements)) {
                return true;
            }
        }
        return false;
    }

    private static boolean matchesAt(Path path, int start, String[] elements) {
        for (int j = 0; j < elements.length; j++) {
            if (!path.getName(start + j).toString().equals(elements[j])) {
                return false;
            }
        }
        return true;
    }
}
