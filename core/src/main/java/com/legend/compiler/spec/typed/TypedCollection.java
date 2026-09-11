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
        boolean rowCells, boolean operatorRun) implements TypedSpec {
    public TypedCollection {
        elements = List.copyOf(elements);
    }

    public TypedCollection(List<TypedSpec> elements, ExprType info) {
        this(elements, info, false, false);
    }

    public TypedCollection(List<TypedSpec> elements, ExprType info, boolean rowCells) {
        this(elements, info, rowCells, false);
    }

    /** The parser's INFIX marker carried into the typed tree: this collection
     *  is the operand run of {@code a + b (+ …)} — the engine's n-ary
     *  arithmetic carrier (upstream's variadic {@code plus(Number[*])} & co.).
     *  Its operands are SQL-lane (null-propagating, never compacted) and the
     *  run is row-wise, never a reduction. */
    /** A rewrite pass's REBUILD of this collection over new elements: the
     *  operator-run marker is the run's identity and rides along; the
     *  row-cells marker describes the ORIGINAL elements and does not. */
    public TypedCollection rebuilt(List<TypedSpec> kids) {
        return new TypedCollection(kids, info, false, operatorRun);
    }

    public TypedCollection asOperatorRun() {
        return new TypedCollection(elements, info, rowCells, true);
    }

    @Override
    public List<TypedSpec> children() {
        return elements;
    }

    @Override
    public TypedSpec withChildren(java.util.List<TypedSpec> kids) {
        return new TypedCollection(kids, info, rowCells, operatorRun);
    }
    /** Re-stamped with {@code info} — every marker (rowCells, operatorRun) kept. */
    @Override
    public TypedSpec withInfo(ExprType info) {
        return new TypedCollection(elements, info, rowCells, operatorRun);
    }
}
