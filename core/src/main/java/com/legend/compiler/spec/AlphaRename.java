// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0
package com.legend.compiler.spec;

import com.legend.protocol.spec.AppliedFunction;
import com.legend.protocol.spec.LambdaFunction;
import com.legend.protocol.spec.ValueSpecification;

import java.util.ArrayList;
import java.util.List;

/**
 * Renames every lambda binder in an INLINED body to a fresh
 * {@code _nr<N>} (outermost-first; occurrence substitution is
 * shadow-aware, so inner same-named binders keep their own scopes until
 * their own rename) — the capture-avoiding half of the typer's raw
 * β-expansion of a helper body. One counter per typer.
 */
final class AlphaRename {

    private int nrFresh;

    ValueSpecification apply(ValueSpecification v) {
        return switch (v) {
            case LambdaFunction lf -> {
                java.util.Map<String, ValueSpecification> ren = new java.util.LinkedHashMap<>();
                List<com.legend.protocol.spec.Variable> params = new ArrayList<>(lf.parameters().size());
                for (com.legend.protocol.spec.Variable p : lf.parameters()) {
                    String fresh = "_nr" + nrFresh++;
                    ren.put(p.name(), new com.legend.protocol.spec.Variable(
                            fresh, p.type(), p.multiplicity()));
                    params.add(new com.legend.protocol.spec.Variable(
                            fresh, p.type(), p.multiplicity()));
                }
                yield new LambdaFunction(params, lf.body().stream()
                        .map(b -> apply(SourceSubst.substitute(b, ren)))
                        .toList());
            }
            case AppliedFunction af2 -> af2.withParameters(
                    af2.parameters().stream().map(this::apply).toList());
            case com.legend.protocol.spec.AppliedProperty ap -> new com.legend.protocol.spec.AppliedProperty(
                    apply(ap.receiver()), ap.property());
            case com.legend.protocol.spec.PureCollection pc -> new com.legend.protocol.spec.PureCollection(
                    pc.values().stream().map(this::apply).toList());
            case com.legend.protocol.spec.ColSpec cs -> new com.legend.protocol.spec.ColSpec(cs.name(),
                    cs.function1() == null ? null : (LambdaFunction) apply(cs.function1()),
                    cs.function2() == null ? null : (LambdaFunction) apply(cs.function2()),
                    cs.alias(),
                    cs.args().stream().map(this::apply).toList());
            case com.legend.protocol.spec.ColSpecArray ca -> new com.legend.protocol.spec.ColSpecArray(
                    ca.colSpecs().stream()
                            .map(c -> (com.legend.protocol.spec.ColSpec) apply(c)).toList());
            default -> v.mapChildren(this::apply);
        };
    }
}
