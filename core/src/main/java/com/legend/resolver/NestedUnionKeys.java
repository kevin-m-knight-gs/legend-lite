// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend.resolver;

import com.legend.compiler.spec.typed.TypedSpec;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A NESTED slot target's pipeline, widened for the keys the downstream
 * hops read off it (group F burn 2026-09-02). A flatten hop materializes
 * its target's demanded navigate slots INTO the hop, and each slot's target
 * class in turn; when that nested target is a UNION (a metaclass hierarchy
 * such as RelationalOperationElement) its projection is demand-pruned to
 * the inbound route keys — but a LATER hop off the nested row (an
 * association end hoisted onto the union, {@code .inferredType}) joins on
 * the union row's own key columns, which every member thread must project.
 * The downstream paths name that later hop; its step condition names the
 * columns.
 */
final class NestedUnionKeys {

    private NestedUnionKeys() {
    }

    static TypedSpec pipeline(ClassSources sources, String mappingFqn, String nestedClass,
            String slotAlias, Map<String, String> headNavAlias,
            Set<List<String>> downstreamPaths) {
        ClassSource cs = sources.get(mappingFqn, nestedClass);
        TypedSpec pipe = cs.pipeline();
        if (!Pipelines.containsConcatenate(pipe)) {
            return pipe;
        }
        Set<String> reads = new LinkedHashSet<>();
        var steps = Pipelines.navSteps(pipe);
        for (List<String> path : downstreamPaths) {
            if (path.size() < 2 || !slotAlias.equals(headNavAlias.get(path.get(0)))) {
                continue;
            }
            TypedSpec b = cs.bindings().get(SyntheticHeads.realHead(path.get(1)));
            String a = b == null ? null
                    : InnerDemand.navSlotAlias(b, cs.rowVar(), steps.keySet());
            var st = a == null ? null : steps.get(a);
            if (st != null && st.predicate().parameters().size() == 2) {
                for (TypedSpec pb : st.predicate().body()) {
                    Pipelines.collectVarReads(pb, st.predicate().parameters().get(0), reads);
                }
            }
        }
        return Pipelines.widenConcatenateBelow(pipe, reads);
    }
}
