package com.legend.compiler.spec;

import com.legend.compiler.spec.typed.TypedConcatenate;
import com.legend.compiler.spec.typed.TypedSpec;
import com.legend.protocol.spec.AppliedFunction;

/**
 * Relation {@code concatenate} (engine {@code ConcatenateChecker}, SQL
 * {@code UNION ALL}) &mdash; fully generic: the shared {@code T} enforces that
 * both sides carry the same schema.
 */
final class ConcatenateChecker {

    private ConcatenateChecker() {
    }

    static TypedSpec check(Typer t, AppliedFunction af, Env env) {
        Application a = t.checkGeneric(af, env);
        // The COLLECTION overload (set1:T[*], set2:T[*]) is a plain value
        // operation (SQL list concat), not the relation set-op node.
        if (!com.legend.compiler.element.type.Type.isRelation(a.out().type())) {
            return Typer.emitCall(a.chosen(), a.args(), a.out());
        }
        // n-ary TDS concatenate (ledger cluster 22): p1->concatenate(
        // [p2, p3, p4]) binds the collection overload with a relation-
        // typed TypedCollection RHS — fold it into a LEFT-ASSOCIATIVE
        // TypedConcatenate chain (engine tds.pure:480-496 returns
        // TabularDataSet[1], hence multiplicity ONE on the fold). Guard
        // on ALL elements relation-typed so a genuine scalar-collection
        // concatenate is never captured.
        if (a.args().get(1) instanceof
                        com.legend.compiler.spec.typed.TypedCollection tc
                && !tc.elements().isEmpty()
                && tc.elements().stream().allMatch(e ->
                        com.legend.compiler.element.type.Type
                                .isRelation(e.info().type()))) {
            var one = new com.legend.compiler.element.type.ExprType(
                    a.out().type(),
                    com.legend.compiler.element.type.Multiplicity
                            .Bounded.ONE);
            TypedSpec acc = a.args().get(0);
            for (TypedSpec e : tc.elements()) {
                acc = new TypedConcatenate(acc, e, one);
            }
            return acc;
        }
        return new TypedConcatenate(a.args().get(0), a.args().get(1), a.out());
    }
}
