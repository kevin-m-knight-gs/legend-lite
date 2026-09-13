// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend.normalizer;

import com.legend.model.ClassMapping;
import com.legend.model.CleanSheetMappingDefinition;
import com.legend.model.LegacyMappingDefinition;
import com.legend.model.PackageableElement;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * THE MAPPED-CLASS FACT of one graph: the classes some mapping maps
 * &mdash; through a declared class mapping, a clean-sheet binding, a
 * JSON-connection identity set, or an IMPLICIT Operation set the pre-pass
 * appended (an unmapped association-end parent, a routed strict-subclass
 * target) &mdash; computed ONCE, before any synthesis, from every
 * mapping's pre-passed class-mapping list.
 *
 * <p>Order-independent by construction (T4.1 verified item 1): the
 * implicit sets used to be registered into the shared index as each
 * mapping normalized, so a later mapping's answer depended on element
 * order &mdash; the corpus probe of 2026-09-13 found unrelated mappings
 * reading a class the projection::exists mapping had implied, and one
 * class ({@code milestoned::Vehicle}) answered FALSE to two mappings that
 * ran before its implying mapping. Here every mapping sees the same set.
 */
final class MappedClasses {

    private final Set<String> classes;

    private MappedClasses(Set<String> classes) {
        this.classes = classes;
    }

    /** From the PRE-PASSED legacy mappings (implicit sets appended) and
     * the parsed model's clean-sheet mappings. */
    static MappedClasses of(Collection<LegacyMappingDefinition> prePassed,
            List<PackageableElement> elements) {
        Set<String> out = new HashSet<>();
        for (LegacyMappingDefinition md : prePassed) {
            for (ClassMapping cm : md.classMappings()) {
                out.add(cm.className());
            }
        }
        for (PackageableElement el : elements) {
            if (el instanceof CleanSheetMappingDefinition cs) {
                for (var cb : cs.classBindings()) {
                    out.add(cb.classFqn());
                }
            }
        }
        return new MappedClasses(Set.copyOf(out));
    }

    /** {@code true} iff some mapping maps {@code classFqn} (explicitly or
     * implicitly) &mdash; the Layer-3 "is this class-typed target a
     * navigation" question. */
    boolean contains(String classFqn) {
        return classes.contains(classFqn);
    }
}
