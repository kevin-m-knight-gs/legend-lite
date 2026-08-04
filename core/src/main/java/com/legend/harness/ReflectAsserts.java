// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend.harness;

import com.legend.compiler.element.ModelContext;
import com.legend.compiler.element.type.Type;
import com.legend.model.ImportScope;
import com.legend.model.Multiplicity;
import com.legend.model.TypeExpression;
import com.legend.model.spec.AppliedFunction;
import com.legend.model.spec.AppliedProperty;
import com.legend.model.spec.CInteger;
import com.legend.model.spec.CString;
import com.legend.model.spec.LambdaFunction;
import com.legend.model.spec.PureCollection;
import com.legend.model.spec.ValueSpecification;
import com.legend.model.spec.Variable;

/**
 * M3-REFLECTION asserts over LAMBDA LITERALS host-evaluated: the
 * pureToSQLQuery tests deactivate a lambda and navigate its metamodel
 * ({@code .expressionSequence->at(i)->cast(@FunctionExpression)
 * .parametersValues->at(k)}) into {@code
 * expressionSequenceReturnsAtLeastToOneDataType} — TRUE iff the
 * expression's result is a DATA (primitive) type with multiplicity
 * lower bound &ge; 1 (the router's isToOne analysis). The deactivate/
 * cast/InstanceValue plumbing is IDENTITY at spec level, so the fold
 * extracts the statement's argument from the ORIGINAL lambda spec and
 * runs the multiplicity walk against the model: declared param
 * multiplicity, property-step multiplication ({@code lower*lower},
 * {@code upper*upper} with null as unbounded), {@code toOne()} to
 * {@code [1]}, literal collections to {@code [n]}.
 *
 * <p>Null = not this shape or a step outside the walk's vocabulary —
 * the caller keeps its honest fallback.
 */
final class ReflectAsserts {

    private ReflectAsserts() {
    }

    static @com.legend.Nullable Boolean tryEval(ValueSpecification v,
            ModelContext ctx, ImportScope imports) {
        if (!(v instanceof AppliedFunction af
                && TestBody.simpleName(af.function()).equals(
                        "expressionSequenceReturnsAtLeastToOneDataType")
                && af.parameters().size() == 1)) {
            return null;
        }
        // [.parametersValues->at(k)] over cast over
        // [.expressionSequence->at(i)] over the lambda plumbing
        ValueSpecification x = stripCasts(af.parameters().get(0));
        int[] argIdx = {0};
        x = stripAt(x, argIdx);
        x = stripCasts(x);
        if (!(x instanceof AppliedProperty pv
                && pv.property().equals("parametersValues"))) {
            return null;
        }
        x = stripCasts(pv.receiver());
        int[] stmtIdx = {0};
        x = stripAt(x, stmtIdx);
        x = stripCasts(x);
        if (!(x instanceof AppliedProperty es
                && es.property().equals("expressionSequence"))) {
            return null;
        }
        LambdaFunction lam = lambdaOf(es.receiver());
        if (lam == null || stmtIdx[0] >= lam.body().size()
                || !(lam.parameters().size() == 1
                        && lam.parameters().get(0) instanceof Variable p)) {
            return null;
        }
        ValueSpecification stmt = lam.body().get(stmtIdx[0]);
        if (!(stmt instanceof AppliedFunction call
                && argIdx[0] < call.parameters().size())) {
            return null;
        }
        Walk w = walk(call.parameters().get(argIdx[0]), p, ctx, imports);
        return w == null ? null : Boolean.valueOf(w.data() && w.lower() >= 1);
    }

    /** (result kind, multiplicity) of the walked expression. */
    private record Walk(@com.legend.Nullable String classFqn, boolean data,
            int lower, @com.legend.Nullable Integer upper) {
    }

    private static @com.legend.Nullable Walk walk(ValueSpecification n,
            Variable param, ModelContext ctx, ImportScope imports) {
        switch (n) {
            case Variable var when var.name().equals(param.name()) -> {
                if (!(param.type() instanceof TypeExpression.NameRef ref)) {
                    return null;
                }
                String fqn = resolveClass(ref.name(), ctx, imports);
                if (fqn == null
                        || !(param.multiplicity()
                                instanceof Multiplicity.Concrete m)) {
                    return null;
                }
                return new Walk(fqn, false, m.lowerBound(), m.upperBound());
            }
            case AppliedProperty ap -> {
                Walk r = walk(ap.receiver(), param, ctx, imports);
                if (r == null || r.classFqn() == null) {
                    return null;
                }
                var prop = ctx.findProperty(r.classFqn(), ap.property())
                        .orElse(null);
                if (prop == null || !(prop.multiplicity()
                        instanceof com.legend.compiler.element.type
                                .Multiplicity.Bounded pm)) {
                    return null;
                }
                String cls = prop.type() instanceof Type.ClassType ct
                        ? ct.fqn() : null;
                boolean data = prop.type() instanceof Type.Primitive
                        || prop.type() instanceof Type.EnumType;
                return new Walk(cls, data, r.lower() * pm.lower(),
                        r.upper() == null || pm.upper() == null ? null
                                : r.upper() * pm.upper());
            }
            case AppliedFunction f
                    when TestBody.simpleName(f.function()).equals("toOne")
                    && f.parameters().size() == 1 -> {
                Walk r = walk(f.parameters().get(0), param, ctx, imports);
                return r == null ? null
                        : new Walk(r.classFqn(), r.data(), 1, 1);
            }
            case PureCollection c -> {
                boolean allData = c.values().stream().allMatch(e ->
                        e instanceof CString || e instanceof CInteger);
                if (!allData && !c.values().isEmpty()) {
                    return null;
                }
                return new Walk(null, true, c.values().size(),
                        c.values().size());
            }
            default -> {
                return null;
            }
        }
    }

    private static @com.legend.Nullable String resolveClass(String name,
            ModelContext ctx, ImportScope imports) {
        if (name.contains("::")) {
            return ctx.findClass(name).isPresent() ? name : null;
        }
        String direct = imports.typeImports().get(name);
        if (direct != null) {
            return direct;
        }
        for (String w : imports.wildcards()) {
            String cand = w + "::" + name;
            if (ctx.findClass(cand).isPresent()) {
                return cand;
            }
        }
        return null;
    }

    /** cast/toOne wrappers are identity for this navigation. */
    private static ValueSpecification stripCasts(ValueSpecification v) {
        while (v instanceof AppliedFunction f
                && (TestBody.simpleName(f.function()).equals("cast")
                        && f.parameters().size() == 2
                    || TestBody.simpleName(f.function()).equals("toOne")
                        && f.parameters().size() == 1)) {
            v = f.parameters().get(0);
        }
        return v;
    }

    /** {@code at(x, k)} — writes k, returns x; identity otherwise. */
    private static ValueSpecification stripAt(ValueSpecification v,
            int[] idx) {
        if (v instanceof AppliedFunction f
                && TestBody.simpleName(f.function()).equals("at")
                && f.parameters().size() == 2
                && f.parameters().get(1) instanceof CInteger k) {
            idx[0] = k.value().intValue();
            return f.parameters().get(0);
        }
        return v;
    }

    /** The lambda literal behind the deactivate/cast/InstanceValue
     * plumbing ({@code ->deactivate()->cast(@InstanceValue).values
     * ->at(0)->cast(@LambdaFunction<…>)}). */
    private static @com.legend.Nullable LambdaFunction lambdaOf(
            ValueSpecification v) {
        while (true) {
            switch (v) {
                case LambdaFunction lf -> {
                    return lf;
                }
                case AppliedFunction f when f.parameters().size() >= 1
                        && (TestBody.simpleName(f.function())
                                        .equals("deactivate")
                                || TestBody.simpleName(f.function())
                                        .equals("cast")
                                || TestBody.simpleName(f.function())
                                        .equals("at")
                                || TestBody.simpleName(f.function())
                                        .equals("toOne")) ->
                        v = f.parameters().get(0);
                case AppliedProperty ap
                        when ap.property().equals("values") ->
                        v = ap.receiver();
                default -> {
                    return null;
                }
            }
        }
    }
}
