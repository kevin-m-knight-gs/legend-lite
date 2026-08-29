// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend.testing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** The two edge cases that motivated the element-wise containment. */
class WalkedPathTest {

    @Nested
    class Spell {

        @Test
        void spellsWithTheRequestedSeparator() {
            assertEquals("com/legend/lowering/Fold.java",
                    WalkedPath.spell(
                            Path.of("com", "legend", "lowering", "Fold.java"),
                            "/"));
        }

        @Test
        void spellsAClassFqnWithDots() {
            assertEquals("com.legend.lowering.Fold",
                    WalkedPath.spell(
                            Path.of("com", "legend", "lowering", "Fold"), "."));
        }

        @Test
        void aSingleElementCarriesNoSeparator() {
            assertEquals("Fold.java",
                    WalkedPath.spell(Path.of("Fold.java"), "/"));
        }
    }

    @Nested
    class ContainsSubPath {

        @Test
        void findsASingleElement() {
            assertTrue(WalkedPath.containsSubPath(
                    Path.of("core", "src", "main", "java"), "main"));
        }

        @Test
        void findsAConsecutiveRun() {
            assertTrue(WalkedPath.containsSubPath(
                    Path.of("core", "src", "test", "java"), "src", "test"));
        }

        @Test
        void refusesANonConsecutiveRun() {
            assertFalse(WalkedPath.containsSubPath(
                    Path.of("core", "src", "main", "test"), "src", "test"));
        }

        /** The slash-delimited form needs a separator on BOTH sides. */
        @Test
        void matchesTheFirstAndLastElement() {
            assertTrue(WalkedPath.containsSubPath(
                    Path.of("target", "classes", "Foo.class"), "target"));
            assertTrue(WalkedPath.containsSubPath(
                    Path.of("core", "src", "target"), "target"));
        }

        /** "/test/" must never fire on a "testing/" directory. */
        @Test
        void refusesAPartialElementMatch() {
            assertFalse(WalkedPath.containsSubPath(
                    Path.of("core", "testing", "Foo.java"), "test"));
        }

        @Test
        void aRunLongerThanThePathCannotMatch() {
            assertFalse(WalkedPath.containsSubPath(
                    Path.of("src"), "src", "test"));
        }

        @Test
        void refusesAnEmptyRun() {
            assertThrows(IllegalArgumentException.class,
                    () -> WalkedPath.containsSubPath(Path.of("src")));
        }
    }
}
