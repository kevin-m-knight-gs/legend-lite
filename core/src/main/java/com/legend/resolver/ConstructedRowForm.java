// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend.resolver;

import com.legend.compiler.element.type.ExprType;
import com.legend.compiler.element.type.Multiplicity;
import com.legend.compiler.element.type.Type;
import com.legend.compiler.spec.typed.TypedLambda;
import com.legend.compiler.spec.typed.TypedNativeCall;
import com.legend.compiler.spec.typed.TypedNewInstance;
import com.legend.compiler.spec.typed.TypedSpec;
import com.legend.compiler.spec.typed.TypedVariable;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.IntSupplier;
import java.util.function.Predicate;

/**
 * A CONSTRUCTED INSTANCE over a STORE ROW (WORLD_MAP §4 struct carrier;
 * toPostgresModel's {@code ^Table(name = … $t.schema.name … $t.name)} over
 * getTable's row): the row's chain is the ONE source, the instance is
 * projected over it — the row's navigations are its join steps, never a
 * subquery per read. The instance IS {@code chain->map(r | ni[chain :=
 * $r])} with the row's declared class and exactly-one multiplicity,
 * resolved as the scalar-map projection over the chain. Chains are found
 * by STRUCTURE: pure is referentially transparent — equal reads of a
 * store chain are one row (the inliner splices one node at every read; a
 * rebuilt copy is the same row still). SEVERAL distinct rows under one
 * instance (a Union of two tables' subqueries): each single-row subtree
 * takes the row form, the instance itself is structural over them.
 */
final class ConstructedRowForm {

    private ConstructedRowForm() {
    }

    /** The resolver's scalar-map projection funnel. */
    interface ScalarMapProject {
        TypedSpec apply(TypedSpec source, TypedLambda mapper,
                Multiplicity valueMult, StoreResolver.Context context);
    }

    /** The number of distinct toOne-wrapped object chains beneath {@code ni}. */
    static int chains(TypedNewInstance ni, Predicate<TypedSpec> objectSpace) {
        Set<TypedSpec> chains = new LinkedHashSet<>();
        collect(ni, objectSpace, chains);
        return chains.size();
    }

    static TypedSpec resolve(TypedNewInstance ni, Predicate<TypedSpec> objectSpace,
            IntSupplier fresh, ScalarMapProject project,
            BiFunction<TypedSpec, StoreResolver.Context, TypedSpec> structural,
            StoreResolver.Context context) {
        Set<TypedSpec> chains = new LinkedHashSet<>();
        collect(ni, objectSpace, chains);
        if (chains.size() != 1) {
            return structural.apply(ni, context);
        }
        TypedNativeCall wrapped = (TypedNativeCall) chains.iterator().next();
        Type.ClassType ct = (Type.ClassType) wrapped.info().type();
        var one = Multiplicity.Bounded.ONE;
        String v = "_r" + fresh.getAsInt();
        TypedVariable row = new TypedVariable(v, new ExprType(ct, one));
        TypedSpec body = substitute(ni, wrapped, row);
        TypedLambda mapper = new TypedLambda(List.of(v), List.of(body),
                new ExprType(new Type.FunctionType(
                        List.of(new Type.Param(ct, one)),
                        new Type.Param(ni.info().type(), ni.info().multiplicity())), one));
        return project.apply(wrapped.args().get(0), mapper, one, context);
    }

    private static void collect(TypedSpec n, Predicate<TypedSpec> objectSpace,
            Set<TypedSpec> out) {
        if (n instanceof TypedNativeCall c && c.args().size() == 1
                && com.legend.builtin.Pure.isToOneCall(c.callee().qualifiedName())
                && c.info().type() instanceof Type.ClassType
                && objectSpace.test(c.args().get(0))) {
            out.add(n);
            return;
        }
        for (TypedSpec c : n.children()) {
            collect(c, objectSpace, out);
        }
    }

    /** {@code n} with every occurrence of {@code target} (the same node,
     * or its structural equal) replaced by {@code replacement}. */
    private static TypedSpec substitute(TypedSpec n, TypedSpec target,
            TypedSpec replacement) {
        if (n == target || n.equals(target)) {
            return replacement;
        }
        return n.mapChildren(c -> substitute(c, target, replacement));
    }
}
