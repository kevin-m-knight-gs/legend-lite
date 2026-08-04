// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend.resolver;

import com.legend.compiler.element.ModelContext;
import com.legend.compiler.element.type.ExprType;
import com.legend.compiler.element.type.Multiplicity;
import com.legend.compiler.element.type.Type;
import com.legend.compiler.spec.SpecCompiler;
import com.legend.compiler.spec.typed.TypedFrom;
import com.legend.compiler.spec.typed.TypedFuncCol;
import com.legend.compiler.spec.typed.TypedGraphFetch;
import com.legend.compiler.spec.typed.TypedLambda;
import com.legend.compiler.spec.typed.TypedNativeCall;
import com.legend.compiler.spec.typed.TypedPackageableRef;
import com.legend.compiler.spec.typed.TypedProject;
import com.legend.compiler.spec.typed.TypedPropertyAccess;
import com.legend.compiler.spec.typed.TypedSpec;
import com.legend.compiler.spec.typed.TypedVariable;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * IN-QUERY CLASS SUBQUERY (the XStore result-sourcing idiom): a
 * {@code <chain>.all()->toOne().prop} read INSIDE another query's lambda
 * resolves RECURSIVELY under the SAME execution context into a
 * {@code [0..1]}-stamped single-column projection — consumed by the
 * lowerer as a correlated-free SCALAR SUBQUERY (the filteredNavLeafRead
 * emission idiom). Engine parity: the router clusters the sub-expression
 * per store and feeds the value back; our one-SQL-plan doctrine inlines
 * it as a scalar subselect. Shapes beyond the scalar read keep their
 * loud walls.
 */
final class SubQueryLift {

    private SubQueryLift() {
    }

    /** Rewrite value-position class subqueries UNDER LAMBDAS in {@code n};
     * returns {@code n} unchanged when none match. */
    static TypedSpec lift(TypedSpec n, StoreResolver.Context context,
            ModelContext ctx, SpecCompiler specs,
            Map<String, TypedSpec> letBindings) {
        return walk(n, context, ctx, specs, letBindings, false);
    }

    private static TypedSpec walk(TypedSpec n, StoreResolver.Context context,
            ModelContext ctx, SpecCompiler specs,
            Map<String, TypedSpec> letBindings, boolean underLambda) {
        if (underLambda && n instanceof TypedPropertyAccess pa) {
            TypedSpec chain = peelScalarWraps(pa.source());
            if (chain != null && StoreResolver.containsGetAll(chain)
                    && uncorrelated(chain,
                            new java.util.LinkedHashSet<>(
                                    letBindings.keySet()))) {
                return resolveScalarRead(chain, pa, context, ctx, specs,
                        letBindings);
            }
        }
        boolean nowUnder = underLambda || n instanceof TypedLambda;
        return SyntheticHeads.rebuildChildren(n,
                c -> walk(c, context, ctx, specs, letBindings, nowUnder));
    }

    /** {@code toOne/first/graphFetch} wrappers peel down to the
     * object-space chain; any other source shape returns null. */
    private static @com.legend.Nullable TypedSpec peelScalarWraps(TypedSpec s) {
        TypedSpec cur = s;
        boolean peeled = false;
        while (true) {
            if (cur instanceof TypedGraphFetch gf) {
                cur = gf.source();
                peeled = true;
                continue;
            }
            if (cur instanceof TypedNativeCall c && c.args().size() == 1
                    && (c.callee().qualifiedName().equals(
                            "meta::pure::functions::multiplicity::toOne")
                        || c.callee().qualifiedName().equals(
                            "meta::pure::functions::collection::first"))) {
                cur = c.args().get(0);
                peeled = true;
                continue;
            }
            break;
        }
        return peeled && cur.info().type() instanceof Type.ClassType
                ? cur : null;
    }

    /** UNCORRELATED check, SHADOW-AWARE: every variable read is either
     * bound by a lambda INSIDE the chain (a filter predicate's own
     * parameter — {@code FiscalCalendarDate.all()->filter(d|$d.date ==
     * $endDate)->toOne()}) or a top-level LET name the recursive
     * resolution serves through {@code letBindings}. Any other read means
     * the chain reads the enclosing row — a correlated class subquery,
     * its own rung, loud downstream. */
    private static boolean uncorrelated(TypedSpec n,
            java.util.Set<String> bound) {
        if (n instanceof TypedVariable v) {
            return bound.contains(v.name());
        }
        if (n instanceof TypedLambda l) {
            java.util.Set<String> inner =
                    new java.util.LinkedHashSet<>(bound);
            inner.addAll(l.parameters());
            for (TypedSpec c : l.children()) {
                if (!uncorrelated(c, inner)) {
                    return false;
                }
            }
            return true;
        }
        for (TypedSpec c : n.children()) {
            if (!uncorrelated(c, bound)) {
                return false;
            }
        }
        return true;
    }

    private static TypedSpec resolveScalarRead(TypedSpec chain,
            TypedPropertyAccess pa, StoreResolver.Context context,
            ModelContext ctx, SpecCompiler specs,
            Map<String, TypedSpec> letBindings) {
        Type.ClassType ct = (Type.ClassType) chain.info().type();
        var one = Multiplicity.Bounded.ONE;
        var optional = Multiplicity.Bounded.ZERO_ONE;
        String v = "_sq";
        TypedSpec read = new TypedPropertyAccess(
                new TypedVariable(v, new ExprType(ct, one)),
                pa.property(), pa.info());
        TypedLambda mapper = new TypedLambda(List.of(v), List.of(read),
                new ExprType(new Type.FunctionType(
                        List.of(new Type.Param(ct, one)),
                        new Type.Param(read.info().type(),
                                read.info().multiplicity())), one));
        Type.RelationType row = new Type.RelationType(List.of(
                new Type.Column(pa.property(), read.info().type(),
                        read.info().multiplicity())));
        TypedProject proj = new TypedProject(chain,
                List.of(new TypedFuncCol(pa.property(), mapper)),
                new ExprType(row, optional));
        // recursion through a FRESH resolver (shared per-chain state —
        // temporal frames, synthetic heads — must not leak); the TypedFrom
        // wrapper threads the FULL context including chain mappings
        Optional<TypedPackageableRef> m = context.explicitMapping() == null
                ? Optional.empty()
                : Optional.of(new TypedPackageableRef(
                        context.explicitMapping(), proj.info()));
        Optional<TypedPackageableRef> r = context.runtimeFqn() == null
                ? Optional.empty()
                : Optional.of(new TypedPackageableRef(
                        context.runtimeFqn(), proj.info()));
        TypedSpec wrapped = new TypedFrom(proj, m, r,
                context.chainMappings(), context.jsonSources(), proj.info());
        TypedSpec resolved = new StoreResolver(ctx, specs)
                .withLetBindings(letBindings)
                .resolve(List.of(wrapped), null).get(0);
        while (resolved instanceof TypedFrom fr) {
            resolved = fr.source();
        }
        // the [0..1] stamp IS the scalar-subquery contract
        // (filteredNavLeafRead idiom): value = the single column
        if (resolved instanceof TypedProject rp) {
            return new TypedProject(rp.source(), rp.columns(),
                    new ExprType(rp.info().type(), optional));
        }
        return resolved;
    }
}
