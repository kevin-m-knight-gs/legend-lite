// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend.normalizer;

import com.legend.model.KeyThread;
import com.legend.model.LegacyMappingDefinition;
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
    /** set id &rarr; (link key name &rarr; the set's physical column): the
     * keys a member set publishes for the navigations routed into it
     * (B3.1b) — the resolver's mixed-union arms read them per set. */
    final Map<String, Map<String, String>> linkKeys = new LinkedHashMap<>();
    /** mapping FQN &rarr; the link keys THAT mapping publishes on its own
     * (every mapping's publication, computed once): the includer compares
     * an included operation's members against it to know whether the
     * included body already carries the keys this mapping needs. */
    final Map<String, Map<String, Map<String, String>>> everyPublication;
    /** bucket &rarr; witnesses of the [1]-over-nullable-column census. */
    final Map<String, Set<String>> nullableCensus = new TreeMap<>();
    /** The graph-wide mapped-class fact (computed before any synthesis). */
    final MappedClasses mapped;

    MappingLedger(MappedClasses mapped) {
        this(mapped, Map.of());
    }

    MappingLedger(MappedClasses mapped,
            Map<String, Map<String, Map<String, String>>> everyPublication) {
        this.mapped = mapped;
        this.everyPublication = everyPublication;
    }

    void census(String bucket, String witness) {
        nullableCensus.computeIfAbsent(bucket, k -> new TreeSet<>()).add(witness);
    }

    /** The compiled mapping's facts: what this synthesis recorded, plus
     * the SURFACE facts Phase F reads off the artifact (T4.1 step 4b). */
    MappingDefinition.NormalizationFacts facts(LegacyMappingDefinition surface,
            LegacyMappingDefinition md, com.legend.compiler.ModelBuilder model) {
        return new MappingDefinition.NormalizationFacts(
                poisons, mixedUnions, unionKeyThreads, nullableCensus,
                MappingFacts.unionMembers(surface), MappingFacts.routedTargetClasses(surface),
                MappingFacts.routedSets(md, model), linkKeys);
    }
}
