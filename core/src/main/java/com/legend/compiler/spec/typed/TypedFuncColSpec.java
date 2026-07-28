package com.legend.compiler.spec.typed;

import com.legend.compiler.element.type.ExprType;

import java.util.List;

/**
 * A type-checked mapped column specification {@code ~alias:x|…} &mdash; a value of
 * type {@code FuncColSpec<F, (alias:BodyType)>[1]}: the checked lambda plus the
 * one-column schema it produces (the {@code Z} the enclosing signature binds).
 *
 * @param col  the checked column (alias + lambda)
 * @param info {@code FuncColSpec<F, (alias:…)>[1]}
 */
public record TypedFuncColSpec(TypedFuncCol col, ExprType info) implements TypedSpec {
    @Override
    public List<TypedSpec> children() {
        return List.of(col.fn());
    }

    @Override
    public TypedSpec withChildren(java.util.List<TypedSpec> kids) {
        TypedSpec.expectChildren(kids, 1, "TypedFuncColSpec");
        return new TypedFuncColSpec(new TypedFuncCol(col.name(),
                (TypedLambda) kids.get(0), col.documentation()), info);
    }
}
