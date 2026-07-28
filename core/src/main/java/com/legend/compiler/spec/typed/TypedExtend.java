package com.legend.compiler.spec.typed;

import com.legend.compiler.element.type.ExprType;

import java.util.ArrayList;
import java.util.List;

/**
 * A type-checked relation {@code extend} (engine {@code TypedExtend}, scalar form)
 * &mdash; {@code extend<T,Z>(r, ~newCol:x|…):Relation<T+Z>[1]}: adds computed
 * columns. {@code Z}'s column types come from type-checking the mapping lambdas
 * against the source row (bound via the signature's {@code FuncColSpec<{T[1]->…},Z>});
 * the output schema is still {@code resolveOutput(T+Z)} &mdash; signature-driven.
 * Window ({@code over}) and aggregate forms are later slices.
 *
 * @param source  the relation being extended
 * @param columns the added columns (alias + checked lambda each)
 * @param info    the result &mdash; {@code T+Z} resolved
 */
public record TypedExtend(TypedSpec source, List<TypedFuncCol> columns, ExprType info) implements TypedSpec {
    public TypedExtend {
        columns = List.copyOf(columns);
    }

    @Override
    public List<TypedSpec> children() {
        List<TypedSpec> out = new ArrayList<>();
        out.add(source);
        columns.forEach(c -> out.add(c.fn()));
        return out;
    }

    @Override
    public TypedSpec withChildren(java.util.List<TypedSpec> kids) {
        TypedSpec.expectChildren(kids, 1 + columns.size(), "TypedExtend");
        java.util.List<TypedFuncCol> cs = new java.util.ArrayList<>(columns.size());
        for (int i = 0; i < columns.size(); i++) {
            TypedFuncCol c = columns.get(i);
            cs.add(new TypedFuncCol(c.name(), (TypedLambda) kids.get(1 + i), c.documentation()));
        }
        return new TypedExtend(kids.get(0), cs, info);
    }
}
