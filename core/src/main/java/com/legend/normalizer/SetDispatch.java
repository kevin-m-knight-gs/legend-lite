// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend.normalizer;

import com.legend.compiler.ModelBuilder;
import com.legend.model.AssociationMapping;
import com.legend.model.AssociationPropertyMapping;
import com.legend.model.ClassMapping;
import com.legend.model.LegacyMappingDefinition;
import com.legend.model.PropertyMapping;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** H5 SET-ID DISPATCH: the normalized mapping's per-property routed-set
 * table (split from MappingNormalizer). */
final class SetDispatch {

    private SetDispatch() {
    }

    /**
     * The H5 SET-ID DISPATCH table: property -> the SOLE set id its routes
     * name across the closure, recorded ONLY when the target class is a
     * multi-set NON-union (a named-set navigation — the resolver
     * materializes that set's binding; union targets dispatch through the
     * union machinery instead). Conflicting routes drop the entry.
     */
    static Map<String, String> routedTargetSets(
            LegacyMappingDefinition md, ModelBuilder model) {
        List<LegacyMappingDefinition> closure = new ArrayList<>();
        MappingNormalizer.collectMappingClosure(md, model, closure, new HashSet<>());
        Map<String, String> out = new LinkedHashMap<>();
        Set<String> conflicted = new HashSet<>();
        for (LegacyMappingDefinition m : closure) {
            for (ClassMapping cm : m.classMappings()) {
                if (cm instanceof ClassMapping.Relational r) {
                    for (PropertyMapping pm : r.propertyMappings()) {
                        if (pm instanceof PropertyMapping.Join j
                                && j.targetSetId() != null) {
                            recordRoutedSet(md, model, j.propertyName(),
                                    j.targetSetId(), out, conflicted);
                        }
                    }
                }
            }
            for (AssociationMapping am : m.associationMappings()) {
                if (am instanceof AssociationMapping.Relational rel) {
                    for (AssociationPropertyMapping apm : rel.propertyMappings()) {
                        String tgt = apm.body() instanceof PropertyMapping.Join j
                                && j.targetSetId() != null
                                ? j.targetSetId() : apm.targetSetId();
                        if (tgt != null) {
                            recordRoutedSet(md, model, apm.propertyName(),
                                    tgt, out, conflicted);
                        }
                    }
                }
            }
        }
        out.keySet().removeAll(conflicted);
        return out;
    }

    private static void recordRoutedSet(LegacyMappingDefinition md,
            ModelBuilder model, String prop, String setId,
            Map<String, String> out, Set<String> conflicted) {
        ClassMapping set = MappingNormalizer.findSetById(md, model, setId);
        if (!(set instanceof ClassMapping.Relational tr) || tr.root()) {
            return;   // root/sole/unknown: class-level dispatch serves
        }
        if (UnionSynthesis.unionForClass(md, model, tr.className()) != null) {
            return;   // union member: the union machinery dispatches
        }
        String prev = out.putIfAbsent(prop, setId);
        if (prev != null && !prev.equals(setId)) {
            conflicted.add(prop);
        }
    }
}
