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
 *   <li>the sites that fire LEGITIMATELY funnel through
 *       {@link #knownMiss}: the metamodel probes (the miss IS the answer
 *       — engine metamodel/protocol class names are not user classes)
 *       and owner classes a synthesis asks about before it knows they
 *       exist. (An earlier version of this note named a bare-superclass
 *       name-resolution gap; {@code NameResolver.resolveClass} resolves
 *       superclasses through the import scope, and
 *       {@code KnowledgeLayerTest} pins it — T4.1 step 3a.)</li>
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
