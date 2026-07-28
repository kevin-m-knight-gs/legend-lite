package com.legend.compiler.spec.typed;

import com.legend.compiler.element.type.Type;
import com.legend.compiler.element.type.ExprType;

import java.util.List;

/**
 * A type conversion (engine {@code TypedCast}): {@code cast(@T)} ({@code T[m]}),
 * {@code to(@T)} ({@code T[0..1]}), {@code toMany(@T)} ({@code T[*]}). Checked
 * generically (the {@code @Type} argument is a {@link TypedTypeRef} prototype
 * value binding the signature's target variable); this node carries the resolved
 * target for lowering (SQL {@code CAST}).
 *
 * @param source the value being converted
 * @param target the resolved conversion target
 * @param info   the result &mdash; target type at the signature's multiplicity
 * @param wire   a mapping WIRE coercion (castAsDeclared): SQL cast at
 *               execution, bare in engine-text lowering
 */
public record TypedCast(TypedSpec source, Type target, ExprType info,
        boolean wire) implements TypedSpec {

    // NO short overload: a defaulted wire flag re-founded the user-cast vs
    // conformance-cast provenance at rebuild sites (remediation T2.2);
    // every construction names every field.

    @Override
    public List<TypedSpec> children() {
        return List.of(source);
    }

    @Override
    public TypedSpec withChildren(java.util.List<TypedSpec> kids) {
        TypedSpec.expectChildren(kids, 1, "TypedCast");
        return new TypedCast(kids.get(0), target, info, wire);
    }
}
