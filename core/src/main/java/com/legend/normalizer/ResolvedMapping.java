// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend.normalizer;

import com.legend.model.ClassMapping;
import com.legend.model.LegacyMappingDefinition;
import com.legend.model.MappingDefinition;

import java.util.Map;

/**
 * ONE RESOLVED MAPPING (clean-sheet homework B1): what synthesis consumes.
 * A {@link MappingView} (the pre-passed mapping and its closure) plus the
 * facts that exist only once EVERY mapping's pre-pass has run: the
 * graph-wide mapped-class set, the sets' declared keys, the validation
 * results, and the surface the F-side facts are stamped from. The rules
 * are the ones the retired walkers had (T4.1 step 4a); B2 replaces them
 * with the engine's, in the view. Mirrors the raw record's accessors so
 * the synthesis code reads {@code md.classMappings()} as before.
 */
final class ResolvedMapping extends MappingView {

    private final LegacyMappingDefinition surface;
    private final Map<String, MappingDefinition.ClassBinding.DeclaredKeys> declaredKeys;
    private final Map<ClassMapping, String> invalid;
    private final MappedClasses mapped;

    ResolvedMapping(LegacyMappingDefinition md, LegacyMappingDefinition surface,
            Map<String, MappingDefinition.ClassBinding.DeclaredKeys> declaredKeys,
            Map<ClassMapping, String> invalid, MappingClosures.Closure closure,
            MappedClasses mapped) {
        super(md, closure);
        this.surface = surface;
        this.declaredKeys = declaredKeys;
        this.invalid = invalid;
        this.mapped = mapped;
    }

    /** The same record over a rewritten mapping (the multi-hop injection). */
    ResolvedMapping withMapping(LegacyMappingDefinition rewritten) {
        return new ResolvedMapping(rewritten, surface, declaredKeys, invalid, closure, mapped);
    }

    LegacyMappingDefinition surface() { return surface; }
    Map<String, MappingDefinition.ClassBinding.DeclaredKeys> declaredKeys() { return declaredKeys; }
    Map<ClassMapping, String> invalid() { return invalid; }
    MappedClasses mapped() { return mapped; }

    /** Whether some mapping of the graph maps {@code classFqn} (graph-wide
     * today; closure-local after B2). */
    boolean isMapped(String classFqn) {
        return mapped.contains(classFqn);
    }
}
