// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guard-coverage self-assertion (ADVERSARIAL_TENET_AUDIT_2026_08_18
 * fix-plan item 7): every guard that walks a source scope asserts how
 * many files it actually scanned, against a pinned floor. Scope is a
 * SILENT parameter — {@code Path.of("src/main/java")} was correct when
 * written and became wrong the moment 7,564 lines moved to
 * {@code src/test} (six of the audit's nine compliance-theater
 * instances were guards whose scope rotted, not guards whose logic
 * broke). A floor turns that rot into a loud failure.
 *
 * <p>Floors move DOWN only with a written justification (files
 * legitimately deleted — e.g. the fetchDb leg retiring GridReads);
 * growth is free.
 */
final class GuardCoverage {

    private GuardCoverage() {
    }

    static void assertFloor(String guard, long scanned, long floor) {
        assertTrue(scanned >= floor,
                guard + " coverage DROPPED: scanned " + scanned
                + " files, floor " + floor + " — the guard's scope"
                + " rotted (moved root, renamed dir, walk filter);"
                + " re-point the walk before trusting this guard,"
                + " or lower the floor with a written justification");
    }
}
