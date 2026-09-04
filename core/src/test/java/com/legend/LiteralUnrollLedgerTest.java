// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * WORLD_MAP §4 / TENET_CHARTER C6.2 — the unroll COMPARES, it never
 * COMPUTES. This pins the exact set of natives {@code LiteralUnroll} folds
 * over literals: every entry decides something visible in the program text
 * (list shape over a spelled list, identity of two spelled scalars of one
 * kind, a short-circuit on a spelled boolean). Adding a native that
 * produces a NEW value (toLower, plus, arithmetic, cross-kind equality) is
 * a tenet violation, not an optimization — the database computes it
 * through a residual CASE. Moving this set is a reviewed design decision.
 */
class LiteralUnrollLedgerTest {

    private static final Set<String> COMPARE_ONLY = Set.of(
            "instanceOf", "equal", "eq", "not", "and", "or", "in",
            "isEmpty", "isNotEmpty", "at",
            "toOne", "toOneMany", "first", "last");

    @Test
    @DisplayName("LiteralUnroll folds compare-only natives (the pinned set)")
    void foldSetIsCompareOnly() throws Exception {
        String src = Files.readString(Path.of(
                "src/main/java/com/legend/compiler/spec/LiteralUnroll.java"));
        Matcher m = Pattern.compile("is\\(c, \"(\\w+)\"\\)").matcher(src);
        Set<String> found = new TreeSet<>();
        while (m.find()) {
            found.add(m.group(1));
        }
        assertEquals(new TreeSet<>(COMPARE_ONLY), found,
                "LiteralUnroll's fold set moved — WORLD_MAP §4: the unroll compares,"
                        + " the database computes; a value-producing fold is not admitted");
    }
}
