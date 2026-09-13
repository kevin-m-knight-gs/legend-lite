// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend.normalizer;

import com.legend.compiler.ModelBuilder;
import com.legend.model.AssociationMapping;
import com.legend.model.ClassMapping;
import com.legend.model.EnumerationMapping;
import com.legend.model.LegacyMappingDefinition;
import com.legend.model.MappingInclude;
import com.legend.model.PropertyMapping;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A MAPPING AS IT CURRENTLY STANDS plus its include closure: every question
 * that depends only on this mapping's own sets and the mappings it includes
 * &mdash; a set by id (own first, then the closure), the closure itself, the
 * sets visible through the includes, the operation set and the root set per
 * class, the enumeration mappings and pair association entries through the
 * includes, a union member's ordinal through its {@code extends} chain.
 * Complete at every stage of the pre-pass rewrite, because nothing here
 * depends on another mapping's rewrite.
 *
 * <p><strong>TRANSITIONAL (clean-sheet homework B1, 2026-09-13).</strong>
 * This type exists apart from {@link ResolvedMapping} only because today's
 * semantics carry two facts that depend on EVERY mapping's rewrite: the
 * graph-wide mapped-class set ({@link MappedClasses}) and the implicit sets
 * the rewrite appends. B2 makes mapped-ness closure-local (the engine's
 * R1) and B3 moves the implicit sets to resolution time (R7); when both are
 * gone a mapping's record is complete in one construction and this type is
 * DELETED &mdash; {@code TransitionalShapesTest} pins its construction sites
 * shrink-only, and the homework doc's B3 done criteria name the deletion.
 */
class MappingView {

    final LegacyMappingDefinition md;
    final MappingClosures.Closure closure;

    MappingView(LegacyMappingDefinition md, MappingClosures.Closure closure) {
        this.md = md;
        this.closure = closure;
    }

    /** The view of a mapping mid-rewrite: its current sets, its closure. */
    static MappingView of(LegacyMappingDefinition md, ModelBuilder model) {
        return new MappingView(md, MappingClosures.of(model).closure(md.qualifiedName()));
    }

    // ---- the raw record's face ------------------------------------------

    LegacyMappingDefinition raw() { return md; }
    String qualifiedName() { return md.qualifiedName(); }
    List<ClassMapping> classMappings() { return md.classMappings(); }
    List<MappingInclude> includes() { return md.includes(); }
    List<AssociationMapping> associationMappings() { return md.associationMappings(); }
    List<EnumerationMapping> enumerationMappings() { return md.enumerationMappings(); }
    @com.legend.Nullable String testSuitesSource() { return md.testSuitesSource(); }

    // ---- identities -----------------------------------------------------

    /** A set's EFFECTIVE id: the declared one, else the engine's default
     * (the class FQN with {@code ::} as {@code _}). The one rule. */
    static String idOf(ClassMapping cm) {
        return cm.setId() != null ? cm.setId() : cm.className().replace("::", "_");
    }

    // ---- resolutions (today's rules; B2 adopts the engine's) -------------
    /** The set with id {@code setId}: this mapping's own first, else one
     * visible through the includes. Null for a null id or no such set. */
    @com.legend.Nullable ClassMapping set(@com.legend.Nullable String setId) {
        if (setId == null) {
            return null;
        }
        for (ClassMapping cm : md.classMappings()) {
            if (setId.equals(idOf(cm))) {
                return cm;
            }
        }
        return closure.sets().get(setId);
    }

    /** This mapping then its includes, depth-first in include order, each once. */
    List<LegacyMappingDefinition> closure() {
        List<LegacyMappingDefinition> out = new ArrayList<>(closure.mappings().size() + 1);
        out.add(md);
        Set<String> seen = new HashSet<>();
        seen.add(md.qualifiedName());
        for (LegacyMappingDefinition m : closure.mappings()) {
            if (seen.add(m.qualifiedName())) {
                out.add(m);
            }
        }
        return out;
    }

    /** Set ids visible through the includes (a later include overrides an
     * earlier one; substitutions applied); own sets excluded. */
    Map<String, ClassMapping> includedSets() {
        return closure.sets();
    }

    /** Every visible set by id: the includes', then this mapping's own on top. */
    Map<String, ClassMapping> visibleSets() {
        Map<String, ClassMapping> out = new LinkedHashMap<>(closure.sets());
        for (ClassMapping cm : md.classMappings()) {
            out.put(idOf(cm), cm);
        }
        return out;
    }

    /** The Union operation set for {@code classFqn}: own first, else the
     * first found through the includes. */
    ClassMapping.@com.legend.Nullable Union unionOf(@com.legend.Nullable String classFqn) {
        for (ClassMapping cm : md.classMappings()) {
            if (cm instanceof ClassMapping.Union u && u.className().equals(classFqn)) {
                return u;
            }
        }
        return closure.union(classFqn);
    }

    /** The Inheritance operation set for {@code classFqn}, the same rule. */
    ClassMapping.@com.legend.Nullable Inheritance inheritanceOf(String classFqn) {
        for (ClassMapping cm : md.classMappings()) {
            if (cm instanceof ClassMapping.Inheritance ih && ih.className().equals(classFqn)) {
                return ih;
            }
        }
        return closure.inheritance(classFqn);
    }

    /** ROOT set per class: the includes' (deeper first), this mapping's own
     * overriding; the {@code *} set or the class's sole set. */
    Map<String, ClassMapping> roots() {
        Map<String, ClassMapping> out = new LinkedHashMap<>(closure.roots());
        MappingClosures.Closure.ownRoots(md, out);
        return out;
    }

    /** Own enumeration mappings plus the includes', transitively. */
    List<EnumerationMapping> enumerationMappingsWithIncludes() {
        List<EnumerationMapping> out = new ArrayList<>(md.enumerationMappings());
        out.addAll(closure.enumerationMappings());
        return out;
    }

    /** Per-pair association entries for {@code classFqn}: own first, then
     * each include's, depth-first. */
    Map<String, List<PropertyMapping.Join>> pairEntries(String classFqn) {
        Map<String, List<PropertyMapping.Join>> out = new LinkedHashMap<>();
        closure.ownPairs(md, classFqn, out);
        closure.pairEntries(classFqn).forEach((setId, joins) ->
                out.computeIfAbsent(setId, k -> new ArrayList<>()).addAll(joins));
        return out;
    }

    /** A union member's ordinal for {@code setId}: the member itself, or
     * the member whose {@code extends} chain reaches it; -1 otherwise. */
    int memberOrdinal(List<String> memberIds, @com.legend.Nullable String setId) {
        int direct = memberIds.indexOf(setId);
        if (direct >= 0) {
            return direct;
        }
        for (int i = 0; i < memberIds.size(); i++) {
            ClassMapping m = set(memberIds.get(i));
            Set<String> seen = new HashSet<>();
            while (m instanceof ClassMapping.Relational r
                    && r.extendsSetId() != null && seen.add(r.extendsSetId())) {
                if (r.extendsSetId().equals(setId)) {
                    return i;
                }
                m = set(r.extendsSetId());
            }
        }
        return -1;
    }
}
