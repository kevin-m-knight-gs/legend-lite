package com.legend.compiler.spec.typed;

import com.legend.compiler.element.type.ExprType;
import com.legend.compiler.element.type.Multiplicity;
import com.legend.compiler.element.type.Type;

import java.util.ArrayList;
import java.util.List;

/**
 * A type-checked relation {@code concatenate} (SQL {@code UNION ALL}) &mdash;
 * {@code concatenate<T>(rel1, rel2):Relation<T>[1]}: both sides must carry the
 * <em>same</em> schema (the shared {@code T} enforces it in unification).
 *
 * @param left  the first relation
 * @param right the second relation
 * @param info  the shared schema
 */
public record TypedConcatenate(TypedSpec left, TypedSpec right, ExprType info) implements TypedSpec {
    @Override
    public List<TypedSpec> children() {
        return List.of(left, right);
    }

    @Override
    public TypedSpec withChildren(java.util.List<TypedSpec> kids) {
        TypedSpec.expectChildren(kids, 2, "TypedConcatenate");
        return new TypedConcatenate(kids.get(0), kids.get(1), info);
    }
    @Override
    public TypedSpec withInfo(ExprType info) {
        return new TypedConcatenate(left, right, info);
    }

    /**
     * The info of a concatenate of {@code left} and {@code right} at {@code m}: when both are relations, the
     * left's carrier, names and types with each common column's stamp the union of both sides' (a column beyond
     * the common prefix keeps the left's, as the arity rule in {@code ConcatenateChecker} says); otherwise the
     * left's type. Every builder of a relation concatenate takes it, the typer's and the resolver's rebuilds
     * alike: until 2026-10-14 each took one side's schema whole, stamps included, so a NULL-constant first
     * branch ({@code []->cast(@String)}, [0..0]) stamped the united column always-empty and an enclosing test
     * of it folded away (the tenth line's find, from both islands).
     */
    public static ExprType unionInfo(TypedSpec left, TypedSpec right, Multiplicity m) {
        Type leftType = left.info().type();
        Type.RelationType ls = Type.relationSchema(leftType);
        Type.RelationType rs = Type.relationSchema(right.info().type());
        if (ls == null || rs == null) {
            return new ExprType(leftType, m);
        }
        int common = Math.min(ls.columns().size(), rs.columns().size());
        List<Type.Column> cols = new ArrayList<>();
        for (int i = 0; i < ls.columns().size(); i++) {
            Type.Column lc = ls.columns().get(i);
            cols.add(i < common
                    ? new Type.Column(lc.name(), lc.type(),
                            Multiplicity.union(lc.multiplicity(), rs.columns().get(i).multiplicity()))
                    : lc);
        }
        if (!(leftType instanceof Type.GenericType g)) {
            // relationSchema reads the schema out of a carrier GenericType and nothing else
            throw new IllegalStateException("a relation type that is no carrier: " + leftType.typeName());
        }
        return new ExprType(new Type.GenericType(g.rawFqn(),
                List.of(new Type.RelationType(cols, ls.dynamicColumns())), g.multArguments()), m);
    }
}
