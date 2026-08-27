// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend.compiler.spec.typed;

import com.legend.compiler.element.type.ExprType;
import java.util.List;

/**
 * {@code deactivate(e)} and its {@code .genericType} hop — the
 * COMPILE-TIME reflection carrier (leg 3b, dossier D2/D4): interpreted
 * pure's {@code deactivate} defers its parameter and wraps the RAW
 * expression; its only chartered read here is
 * {@code .genericType.rawType} — the expression's statically inferred
 * type, a model-space fact ({@code TENET_CHARTER} C1.6: computable with
 * no database attached). The whole chain folds at TYPE time to a
 * {@link TypedTypeRef}, so this node never reaches lowering and costs
 * zero MIR variants and zero render arms. Any OTHER property read walls
 * loudly at ordinary property resolution (the metamodel class is
 * deliberately not modeled — absence is a wall, never a fabrication).
 *
 * @param inner    the checked argument expression
 * @param declared the argument's DECLARED type — for a match, the
 *                 all-branch LUB ({@link TypedMatch#declared()}), never
 *                 the emission-narrowed selection
 * @param generic  false = the {@code ValueSpecification[1]} value;
 *                 true = after the {@code .genericType} hop
 * @param info     the metamodel carrier type at this hop
 */
public record TypedDeactivate(TypedSpec inner, ExprType declared,
        boolean generic, ExprType info) implements TypedSpec {
    @Override
    public List<TypedSpec> children() {
        return List.of(inner);
    }

    @Override
    public TypedSpec withChildren(java.util.List<TypedSpec> kids) {
        TypedSpec.expectChildren(kids, 1, "TypedDeactivate");
        return new TypedDeactivate(kids.get(0), declared, generic, info);
    }
}
