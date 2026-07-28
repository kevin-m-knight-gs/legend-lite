package com.legend.compiler.spec.typed;

import com.legend.compiler.element.type.ExprType;

import java.util.List;

import java.util.Optional;

/**
 * A type-checked {@code if(cond, |then, |else)} (engine {@code TypedIf}). The
 * branches are the bodies of the zero-parameter lambda thunks; {@link #info()}
 * is the common supertype of the branch types (the {@code then} type if there is
 * no {@code else}).
 */
public record TypedIf(TypedSpec condition, TypedSpec thenBranch,
                      Optional<TypedSpec> elseBranch, ExprType info) implements TypedSpec {
    @Override
    public List<TypedSpec> children() {
        return elseBranch
                .map(e -> List.of(condition, thenBranch, e))
                .orElseGet(() -> List.of(condition, thenBranch));
    }

    @Override
    public TypedSpec withChildren(java.util.List<TypedSpec> kids) {
        TypedSpec.expectChildren(kids, elseBranch.isPresent() ? 3 : 2, "TypedIf");
        return new TypedIf(kids.get(0), kids.get(1),
                elseBranch.isPresent() ? java.util.Optional.of(kids.get(2))
                        : java.util.Optional.empty(), info);
    }
}
