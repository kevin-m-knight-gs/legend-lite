// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend.normalizer;

import com.legend.model.KeyThread;
import com.legend.model.MappingDefinition;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Phase E's per-MAPPING ledger: what the synthesis of ONE mapping learns
 * and records on the way &mdash; poisons, mixed-kind unions, union key
 * threads, the nullable census &mdash; stamped onto the compiled
 * {@link MappingDefinition} as {@link MappingDefinition.NormalizationFacts}
 * when the mapping's normalization completes (T4.1 step 2). Before, these
 * were five write channels into the shared model index, copied across the
 * E&rarr;F gate; now the artifact carries them and nothing is copied.
 *
 * <p>Rides the {@link Pipeline} so the deep emission sites reach it
 * without a parameter sweep; the graph-wide {@link MappedClasses} fact
 * rides along (read-only).
 */
final class MappingLedger {

    /** "class", "class[setId]" or an association FQN &rarr; the reason
     * its binding is withheld (loud at use). */
    final Map<String, String> poisons = new LinkedHashMap<>();
    /** class &rarr; member set ids of a MIXED-KIND Operation union. */
    final Map<String, List<String>> mixedUnions = new LinkedHashMap<>();
    /** class &rarr; the primary-key threads of an Operation union's row. */
    final Map<String, List<KeyThread>> unionKeyThreads = new LinkedHashMap<>();
    /** bucket &rarr; witnesses of the [1]-over-nullable-column census. */
    final Map<String, Set<String>> nullableCensus = new TreeMap<>();
    /** The graph-wide mapped-class fact (computed before any synthesis). */
    final MappedClasses mapped;

    MappingLedger(MappedClasses mapped) {
        this.mapped = mapped;
    }

    void census(String bucket, String witness) {
        nullableCensus.computeIfAbsent(bucket, k -> new TreeSet<>()).add(witness);
    }

    MappingDefinition.NormalizationFacts facts() {
        return new MappingDefinition.NormalizationFacts(
                poisons, mixedUnions, unionKeyThreads, nullableCensus);
    }
}
