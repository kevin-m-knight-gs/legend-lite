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
 * without a parameter sweep; the closure-local mapped fact
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
    /** The classes MAPPED for this mapping (engine R1: a set in the queried
     * mapping's closure — own pre-passed record and the included mappings'
     * pre-passed records, implicit operation sets included). Never a
     * graph-wide fact: a class another mapping happens to map is not
     * navigable here. */
    private final Set<String> mappedInClosure;

    /** Every mapping's resolved record (the include re-bind question asks
     * the DEFINING mapping's; the pre-passed closure reads the included
     * mappings'). Empty for a scratch ledger. */
    final Map<String, ResolvedMapping> resolved;

    MappingLedger(Set<String> mappedInClosure) {
        this(mappedInClosure, Map.of(), Map.of());
    }

    MappingLedger(Set<String> mappedInClosure,
            Map<String, Map<String, Map<String, String>>> everyPublication,
            Map<String, ResolvedMapping> resolved) {
        this.mappedInClosure = mappedInClosure;
        this.everyPublication = everyPublication;
        this.resolved = resolved;
    }

    /** Whether {@code classFqn} has a set in this mapping's closure. */
    boolean isMapped(String classFqn) {
        return mappedInClosure.contains(classFqn);
    }

    /** The mapped classes of {@code md}'s pre-passed closure. */
    static Set<String> mappedInClosure(ResolvedMapping md, Map<String, ResolvedMapping> resolved) {
        Set<String> out = new java.util.HashSet<>();
        for (com.legend.model.LegacyMappingDefinition m : UnionSynthesis.prePassedClosure(md, resolved)) {
            for (com.legend.model.ClassMapping cm : m.classMappings()) {
                out.add(cm.className());
            }
        }
        return Set.copyOf(out);
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
