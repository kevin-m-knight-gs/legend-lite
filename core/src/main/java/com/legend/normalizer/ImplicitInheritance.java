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
                    merged, child.sourceUrl(), child.propertyTargetSets(),
                    child.aggregation()));
        }
        return any ? md.withClassMappings(rewritten) : md;
    }

    /** IMPLICIT inheritance OP for UNMAPPED routed targets (engine
     * parity — router_operations getMappedLeafTypes: a class with no set
     * of its own is served by its mapped subclasses). Two spellings name
     * such a target: (a) a per-pair AssociationMapping entry
     * ({@code vehicle[o,c]: @Owner_Car}, MilestonedInheritanceMapping
     * golden) whose member set's class is a strict subclass of an
     * association END that has NO set anywhere in scope; (b) a routed
     * class-typed property mapping ({@code fnScope[map2]: @privateFnJoin}
     * + {@code fnScope[map3]: @publicFnJoin}, projection::exists
     * testExistsAsNullWithSubType — batch 140) whose set's class is a
     * strict subclass of the property's DECLARED class that has no set.
     * Appending an explicit {@code Inheritance} op makes every downstream
     * mechanism (route classification with member ordinals, the routed
     * union navigation, union synthesis, witness casts, milestoned heads)
     * engage unchanged — before (b), the first routed PM silently won the
     * navigation slot and {@code ->subType(@Other)} found no binding.
     * Conservative: only END / DECLARED classes with at least one routed
     * strict-subclass set qualify. */
    static LegacyMappingDefinition implicitOpsForRoutedTargets(
            LegacyMappingDefinition md, ModelBuilder model) {
        Map<String, ClassMapping> bySetId = new HashMap<>();
        MappingNormalizer.collectIncludedSetIds(md, model, bySetId, new HashSet<>());
        for (ClassMapping cm : md.classMappings()) {
            bySetId.put(MappingNormalizer.setIdOf(cm), cm);
        }
        Set<String> mappedClasses = new HashSet<>();
        for (ClassMapping cm : bySetId.values()) {
            mappedClasses.add(cm.className());
        }
        Set<String> implied = new java.util.LinkedHashSet<>();
        for (com.legend.model.AssociationMapping am : md.associationMappings()) {
            if (!(am instanceof com.legend.model.AssociationMapping.Relational rel)) {
                continue;
            }
            var ad = AssociationSynthesis.resolveAssociation(model, md, am)
                    .orElse(null);
            if (ad == null) {
                continue;
            }
            for (com.legend.model.AssociationPropertyMapping apm
                    : rel.propertyMappings()) {
                String tgtSet = apm.body() instanceof PropertyMapping.Join j
                        && j.targetSetId() != null
                        ? j.targetSetId() : apm.targetSetId();
                if (tgtSet == null) {
                    continue;
                }
                String end = AssociationSynthesis.associationTargetClass(
                        ad, apm.propertyName());
                ClassMapping tgt = bySetId.get(tgtSet);
                if (end == null || mappedClasses.contains(end)
                        || tgt == null || tgt.className().equals(end)
                        || !UnionSynthesis.isSubclassOf(
                                tgt.className(), end, model)) {
                    continue;
                }
                implied.add(end);
            }
        }
        // (b) routed class-typed property mappings
        for (ClassMapping cm : md.classMappings()) {
            if (!(cm instanceof ClassMapping.Relational rcm)) {
                continue;
            }
            Map<String, List<PropertyMapping.Join>> routedByProp =
                    new java.util.LinkedHashMap<>();
            Map<String, String> ownerByProp = new HashMap<>();
            UnionSynthesis.collectRoutedJoins(rcm.propertyMappings(),
                    rcm.className(), md, model, routedByProp, ownerByProp);
            for (var e : routedByProp.entrySet()) {
                String prop = e.getKey();
                com.legend.model.ClassDefinition owner = MappingNormalizer
                        .classDef(model, ownerByProp.getOrDefault(prop,
                                rcm.className())).orElse(null);
                com.legend.protocol.TypeExpression pt = owner == null ? null
                        : MappingNormalizer.findPropertyTypeDeep(owner, prop, model);
                if (!(pt instanceof com.legend.protocol.TypeExpression.NameRef nr)
                        || MappingNormalizer.classDef(model, nr.name()).isEmpty()
                        || mappedClasses.contains(nr.name())) {
                    continue;
                }
                for (PropertyMapping.Join j : e.getValue()) {
                    ClassMapping tgt = bySetId.get(j.targetSetId());
                    if (tgt != null && !tgt.className().equals(nr.name())
                            && UnionSynthesis.isSubclassOf(
                                    tgt.className(), nr.name(), model)) {
                        implied.add(nr.name());
                        break;
                    }
                }
            }
        }
        if (implied.isEmpty()) {
            return md;
        }
        List<ClassMapping> rewritten = new ArrayList<>(md.classMappings());
        for (String cls : implied) {
            rewritten.add(new ClassMapping.Inheritance(cls, null, null, false));
            model.registerMappedClass(cls);
        }
        return md.withClassMappings(rewritten);
    }

    /** The NEAREST class-hierarchy ancestor with exactly ONE Relational
     * mapping in scope over the child's main table; null otherwise. */
    private static ClassMapping.@com.legend.Nullable Relational nearestMappedAncestor(
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
            MappingNormalizer.classDef(model, cls).ifPresent(cd -> {
                for (var sup : cd.superClasses()) {
                    if (sup instanceof com.legend.protocol.TypeExpression.NameRef nr) {
                        level.add(nr.name());
                    }
                }
            });
        }
        return null;
    }

}
