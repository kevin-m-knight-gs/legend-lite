// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend.normalizer;

/**
 * F7.8 VERDICT funnel. The full-sweep census over every
 * {@code findClass(...).orElse(null)} default in this package split the
 * 33 sites in two:
 *
 * <ul>
 *   <li>23 sites NEVER fired — they now {@code orElseThrow} at the call
 *       site (an unresolvable class was the audit's feared silent
 *       semantic default; it is loud there now).</li>
 *   <li>10 sites fire LEGITIMATELY and funnel through
 *       {@link #knownMiss}: UnionSynthesis's metamodel probe (the miss
 *       IS the answer — engine metamodel/protocol class names are not
 *       user classes), and nine sites whose keys are BARE super-class
 *       simple names ("Person", "Firm") the normalizer never
 *       import-resolved — a REAL name-resolution gap, filed in
 *       FOUNDATIONS_PLAN §9: a temporal superclass referenced by bare
 *       name would silently contribute nothing. The corpus carries no
 *       such case today; the funnel keeps the sites enumerable.</li>
 * </ul>
 */
final class MissProbe {

    private MissProbe() {
    }

    /** A censused, legitimate empty-answer site (see class doc). */
    static <T> @com.legend.Nullable T knownMiss(java.util.Optional<T> o) {
        return o.orElse(null);
    }
}
