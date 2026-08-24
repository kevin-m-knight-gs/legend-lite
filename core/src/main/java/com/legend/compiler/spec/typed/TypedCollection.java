package com.legend.compiler.spec.typed;

import com.legend.compiler.element.type.ExprType;

import java.util.List;

/**
 * A type-checked collection literal {@code [a, b, c]} (engine {@code TypedCollection}).
 * Its {@link #info()} type is the least common supertype of the elements; its
 * multiplicity is the exact element count.
 *
 * <p>{@code rowCells} is a CONSTRUCTION-DECLARED fact: true only when the
 * Typer's {@code rowCells()} synthesis built this collection as a TDSRow's
 * cells ({@code $r.values} / the rows.values flatten body — every element a
 * property read off one row variable, covering the full column roster in
 * order). Consumers that need the distinction (the makeString TDSNull
 * sentinel, the variant lane's cell-slot law) read THIS declaration — never
 * re-derive it by shape (label at construction, don't sniff at consumption;
 * the shape-matcher ValueCollections.isRowCells this replaced was the
 * disease's own idiom applied to ourselves).
 */
public record TypedCollection(List<TypedSpec> elements, ExprType info,
        boolean rowCells) implements TypedSpec {
    public TypedCollection {
        elements = List.copyOf(elements);
    }

    public TypedCollection(List<TypedSpec> elements, ExprType info) {
        this(elements, info, false);
    }

    @Override
    public List<TypedSpec> children() {
        return elements;
    }

    @Override
    public TypedSpec withChildren(java.util.List<TypedSpec> kids) {
        return new TypedCollection(kids, info, rowCells);
    }
}
