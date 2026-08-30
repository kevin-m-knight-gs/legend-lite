// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend.compiler.spec.typed;

import com.legend.compiler.element.type.ExprType;

import java.util.List;

/**
 * {@code generateTestData(...)} — the checked CARRIER (TDG lane S2, the
 * {@link TypedCsvCensus} sibling): unlike the census this is a RUNTIME
 * operation (the generator executes fetches through the database), so
 * the checker captures the call's PROTOCOL parameter list and the
 * orchestrator runs the extraction at statement position and splices
 * the result as instance literals. Never reaches lowering; a stray is
 * a loud wall.
 */
public record TypedTestDataGen(
        List<com.legend.protocol.spec.ValueSpecification> params,
        String flavor,
        ExprType info) implements TypedSpec {

    /** {@code "generate"} (TestDataGenResult) | {@code "seedString"}
     * (String[1]) — the orchestrator's fold dispatch. */

    public TypedTestDataGen {
        params = List.copyOf(params);
    }

    @Override
    public List<TypedSpec> children() {
        return List.of();
    }

    @Override
    public TypedSpec withChildren(List<TypedSpec> kids) {
        TypedSpec.expectChildren(kids, 0, "TypedTestDataGen");
        return this;
    }
}
