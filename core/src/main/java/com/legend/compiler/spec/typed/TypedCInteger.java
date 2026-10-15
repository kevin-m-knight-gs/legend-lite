package com.legend.compiler.spec.typed;

import com.legend.compiler.element.type.ExprType;

import java.util.List;

/** A type-checked integer literal (engine {@code TypedCInteger}). */
public record TypedCInteger(Number value, ExprType info) implements TypedSpec {
    @Override
    public List<TypedSpec> children() {
        return List.of();
    }

    @Override
    public TypedSpec withChildren(java.util.List<TypedSpec> kids) {
        TypedSpec.expectChildren(kids, 0, "TypedCInteger");
        return this;
    }
    @Override
    public TypedSpec withInfo(ExprType info) {
        return new TypedCInteger(value, info);
    }

    /** The value exact: a literal beyond long stays the {@link java.math.BigInteger} the parser kept, and a
     *  fold that compares two spelled integers reads this, never {@code longValue()}, which wraps it. */
    public java.math.BigInteger big() {
        return value instanceof java.math.BigInteger b ? b : java.math.BigInteger.valueOf(value.longValue());
    }
}
