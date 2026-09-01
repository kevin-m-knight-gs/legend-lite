// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend.compiler.spec.typed;

import com.legend.compiler.element.type.ExprType;

import java.util.List;

/**
 * {@code scanRelations(query, mapping[, runtime], extensions)} — the
 * checked CARRIER (lineage lane; the {@link TypedTestDataGen} sibling):
 * a COMPILE-TIME reflection fact over the model (no database), captured
 * at check time and folded by {@code relationTreeAsString}'s checker via
 * the platform's native {@code com.legend.lineage.ScanRelations}. The
 * carrier itself never lowers — an unconsumed scan let β-reduces away in
 * the inliner, and any other consumption is a loud wall.
 */
public record TypedScanRelations(
        com.legend.protocol.spec.LambdaFunction query,
        String mappingFqn,
        boolean runtimeVariant,
        ExprType info) implements TypedSpec {

    @Override
    public List<TypedSpec> children() {
        return List.of();
    }

    @Override
    public TypedSpec withChildren(List<TypedSpec> kids) {
        TypedSpec.expectChildren(kids, 0, "TypedScanRelations");
        return this;
    }
}
