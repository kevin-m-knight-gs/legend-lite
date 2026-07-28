package com.legend.compiler.spec.typed;

import com.legend.compiler.element.type.ExprType;

import java.util.List;

/**
 * A type-checked {@code slice} (SQL {@code LIMIT}+{@code OFFSET}) &mdash;
 * {@code <T>(Relation<T>[1], start:Integer[1], stop:Integer[1]):Relation<T>[1]}
 * or the collection form; the range is {@code [start, stop)}.
 *
 * @param source the relation or collection
 * @param start  inclusive start index
 * @param stop   exclusive stop index
 * @param info   the source type unchanged
 */
public record TypedSlice(TypedSpec source, TypedSpec start, TypedSpec stop, ExprType info) implements TypedSpec {
    @Override
    public List<TypedSpec> children() {
        return List.of(source, start, stop);
    }

    @Override
    public TypedSpec withChildren(java.util.List<TypedSpec> kids) {
        TypedSpec.expectChildren(kids, 3, "TypedSlice");
        return new TypedSlice(kids.get(0), kids.get(1), kids.get(2), info);
    }
}
