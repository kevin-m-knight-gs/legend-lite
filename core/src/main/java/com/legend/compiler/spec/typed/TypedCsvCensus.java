// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend.compiler.spec.typed;

import com.legend.compiler.element.type.ExprType;

import java.util.List;

/**
 * {@code getRelationalCSVDataFromQuery(query, mapping)} — the checked
 * CARRIER (TDG lane S1, the {@link TypedDeactivate} pattern): the
 * checker validates the registered signature and captures the inputs
 * the census needs — the PROTOCOL query lambda and the mapping FQN —
 * and the TOP layer folds this node to instance literals before
 * resolution (the census implementation lives above the compiler:
 * compiler &rarr; testdatagen would cycle, so the node carries its
 * inputs down and the orchestrator folds — check-time capture,
 * orchestration-time fold). Never reaches lowering; a stray is a loud
 * wall.
 */
public record TypedCsvCensus(
        com.legend.protocol.spec.LambdaFunction query,
        String mappingFqn,
        ExprType info) implements TypedSpec {

    @Override
    public List<TypedSpec> children() {
        return List.of();
    }

    @Override
    public TypedSpec withChildren(List<TypedSpec> kids) {
        TypedSpec.expectChildren(kids, 0, "TypedCsvCensus");
        return this;
    }
}
