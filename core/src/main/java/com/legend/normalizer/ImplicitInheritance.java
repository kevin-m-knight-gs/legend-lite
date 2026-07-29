// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend.normalizer;

import com.legend.compiler.ModelBuilder;
import com.legend.model.ClassMapping;
import com.legend.model.LegacyMappingDefinition;
import com.legend.model.PropertyMapping;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * IMPLICIT same-extent inheritance pre-pass (relocated from
 * {@link MappingNormalizer} at the file guardrail) — see {@link #apply}.
 */
final class ImplicitInheritance {

    private ImplicitInheritance() {
    }

    /** IMPLICIT same-extent inheritance (memory/ProductWithConstraint
     * diagnosis — the engine's per-property routing serves an INHERITED
     * property through the declaring ancestor's set): a Relational class
     * mapping WITHOUT explicit {@code extends} whose CLASS extends an
     * ancestor that has exactly ONE Relational mapping in scope over the
     * SAME main table inherits that ancestor's UNQUALIFIED property
     * mappings for property names the child does not map itself
     * (set-qualified routes never inherit — they name the ancestor's
     * sets; different main tables never inherit — distinct extents stay
     * loud). Child attributes are authoritative (no table-attribute
     * merge — unlike explicit extends, the extents are the child's). */
    static LegacyMappingDefinition apply(
            LegacyMappingDefinition md, ModelBuilder model) {
        Map<String, ClassMapping> bySetId = new HashMap<>();
        MappingNormalizer.collectIncludedSetIds(md, model, bySetId, new HashSet<>());
        for (ClassMapping cm : md.classMappings()) {
            bySetId.put(MappingNormalizer.setIdOf(cm), cm);
        }
        // class fqn -> its Relational mappings in scope
        Map<String, List<ClassMapping.Relational>> byClass = new HashMap<>();
        for (ClassMapping cm : bySetId.values()) {
            if (cm instanceof ClassMapping.Relational r) {
                byClass.computeIfAbsent(r.className(), x -> new ArrayList<>())
                        .add(r);
            }
        }
        boolean any = false;
        List<ClassMapping> rewritten = new ArrayList<>(md.classMappings().size());
        for (ClassMapping cm : md.classMappings()) {
            if (!(cm instanceof ClassMapping.Relational child)
                    || child.extendsSetId() != null) {
                rewritten.add(cm);
                continue;
            }
            LegacyMappingDefinition.TableReference childMain =
                    child.mainTable() != null ? child.mainTable()
                            : MappingNormalizer.inferMainTableQuiet(child);
            if (childMain == null) {
                rewritten.add(cm);
                continue;
            }
            ClassMapping.Relational ancestor = nearestMappedAncestor(
                    childMain, child.className(), model, byClass);
            if (ancestor == null) {
                rewritten.add(cm);
                continue;
            }
            Set<String> own = new HashSet<>();
            for (PropertyMapping pm : child.propertyMappings()) {
                own.add(pm.propertyName());
            }
            List<PropertyMapping> merged =
                    new ArrayList<>(child.propertyMappings());
            boolean added = false;
            for (PropertyMapping pm : ancestor.propertyMappings()) {
                if (!own.contains(pm.propertyName())
                        && (!(pm instanceof PropertyMapping.Join j)
                                || j.targetSetId() == null)) {
                    merged.add(pm);
                    added = true;
                }
            }
            if (!added) {
                rewritten.add(cm);
                continue;
            }
            any = true;
            rewritten.add(new ClassMapping.Relational(
                    child.className(), child.setId(), child.extendsSetId(),
                    child.root(), child.mainTable(), child.filter(),
                    child.distinct(), child.groupBy(), child.primaryKey(),
                    merged, child.sourceUrl(), child.propertyTargetSets()));
        }
        return any ? md.withClassMappings(rewritten) : md;
    }

    /** The NEAREST class-hierarchy ancestor with exactly ONE Relational
     * mapping in scope over the child's main table; null otherwise. */
    private static ClassMapping.Relational nearestMappedAncestor(
            LegacyMappingDefinition.TableReference childMain,
            String childClass, ModelBuilder model,
            Map<String, List<ClassMapping.Relational>> byClass) {
        java.util.ArrayDeque<String> level = new java.util.ArrayDeque<>();
        Set<String> visited = new HashSet<>();
        level.add(childClass);
        while (!level.isEmpty()) {
            String cls = level.poll();
            if (!visited.add(cls)) {
                continue;
            }
            if (!cls.equals(childClass)) {
                List<ClassMapping.Relational> cands = byClass
                        .getOrDefault(cls, List.of()).stream()
                        .filter(a -> {
                            var am = a.mainTable() != null ? a.mainTable()
                                    : MappingNormalizer.inferMainTableQuiet(a);
                            return am != null && am.equals(childMain);
                        })
                        .toList();
                if (cands.size() == 1) {
                    return cands.get(0);
                }
                if (!cands.isEmpty()) {
                    return null;   // ambiguous — stay loud downstream
                }
            }
            model.findClass(cls).ifPresent(cd -> {
                for (var sup : cd.superClasses()) {
                    if (sup instanceof com.legend.model.TypeExpression.NameRef nr) {
                        level.add(nr.name());
                    }
                }
            });
        }
        return null;
    }

}
