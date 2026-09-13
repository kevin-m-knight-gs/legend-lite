// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend.normalizer;

import com.legend.compiler.ModelBuilder;
import com.legend.error.LegendCompileException;
import com.legend.error.ModelException;
import com.legend.model.ClassDefinition;
import com.legend.model.ClassMapping;
import com.legend.model.LegacyMappingDefinition;
import com.legend.model.PropertyMapping;

import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * VALIDATION BEFORE SYNTHESIS (T4.1 step 5): the user-model checks Phase
 * E used to raise from inside a class's synthesis — a property mapping
 * naming a property its class does not declare, an M2M binding whose
 * {@code [source, target]} route is not benign — run over every set of
 * the pre-passed mapping before any synthesis, with §6's line held:
 * a STRICT build rejects them (the engine rejects them at compile time),
 * a MODULE build records them as per-set poisons and synthesis skips
 * the set (the binding is withheld; the reason raises at use). The
 * roadmap gaps ({@code NotImplementedException}) are not validation:
 * they stay per-set poisons in both builds.
 */
final class MappingValidation {

    private MappingValidation() {}

    /** The invalid sets of {@code md} (by identity) with their reasons.
     * Strict: the first invalid set throws. */
    static Map<ClassMapping, String> run(ResolvedMapping r, ModelBuilder model,
            boolean tolerant) {
        LegacyMappingDefinition md = r.raw();
        // MAPPING-level errors the engine's compiler raises (R5): a class
        // mapping id taken by two distinct sets across the include closure,
        // (an include listed twice is rejected where the include graph is
        // first walked, StoreSubstitutionRewrite.resolveAllStores). Thrown in BOTH builds — a module build
        // walls the whole mapping through the pre-pass's own catch, exactly
        // as the engine rejects the mapping.
        MappingClosures.Closure closure = MappingClosures.of(model).closure(md.qualifiedName());
        List<String> dupIds = closure.duplicateIds();
        if (!dupIds.isEmpty()) {
            throw new ModelException(LegendCompileException.Phase.MODEL,
                    "Duplicated class mappings found with ID " + dupIds
                    + " in mapping '" + md.qualifiedName() + "'", md.qualifiedName());
        }
        Map<ClassMapping, String> invalid = new IdentityHashMap<>();
        for (ClassMapping cm : md.classMappings()) {
            try {
                switch (cm) {
                    case ClassMapping.Relational rcm -> validatePmNames(rcm, model, md);
                    case ClassMapping.Pure pcm -> validateM2mRoutes(pcm, model, r);
                    default -> { }
                }
            } catch (ModelException e) {
                if (!tolerant) {
                    throw e;
                }
                invalid.put(cm, String.valueOf(e.getMessage()));
            }
        }
        return invalid;
    }

    /** Every property mapping names a property its class declares or
     * inherits (engine parity: property lookup walks generalizations). */
    private static void validatePmNames(ClassMapping.Relational rcm,
            ModelBuilder model, LegacyMappingDefinition md) {
        ClassDefinition cd = MissProbe.knownMiss(model.knowledge().hierarchyClass(rcm.className()));
        if (cd == null) return;
        for (PropertyMapping pm : rcm.propertyMappings()) {
            if (pm instanceof PropertyMapping.LocalProperty) continue;
            if (model.knowledge().propertyType(cd, pm.propertyName()) == null) {
                throw new ModelException(LegendCompileException.Phase.MODEL,
                        "PropertyMapping '" + pm.propertyName() + "' references property "
                      + "not declared on class '" + rcm.className() + "'; mapping="
                      + md.qualifiedName());
            }
        }
    }

    /** Every routed M2M binding names a benign route. */
    private static void validateM2mRoutes(ClassMapping.Pure pcm,
            ModelBuilder model, ResolvedMapping md) {
        ClassDefinition tgt = MissProbe.knownMiss(model.knowledge().hierarchyClass(pcm.className()));
        for (ClassMapping.Pure.PropertyBinding pb : pcm.propertyBindings()) {
            M2mRouteGuards.requireBenignRoute(pb, pcm, tgt, md, model);
        }
    }
}
