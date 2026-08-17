// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend.harness;

import com.legend.compiler.element.ModelContext;
import com.legend.compiler.element.type.Type;
import com.legend.model.ImportScope;
import com.legend.protocol.Multiplicity;
import com.legend.protocol.TypeExpression;
import com.legend.protocol.spec.AppliedFunction;
import com.legend.protocol.spec.AppliedProperty;
import com.legend.protocol.spec.CInteger;
import com.legend.protocol.spec.CString;
import com.legend.protocol.spec.LambdaFunction;
import com.legend.protocol.spec.PureCollection;
import com.legend.protocol.spec.ValueSpecification;
import com.legend.protocol.spec.Variable;

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
                && EngineTestExecutor.simpleName(af.function()).equals(
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
        if (stmtIdx[0] >= lam.body().size()) {
            return null;
        }
        ValueSpecification stmt = lam.body().get(stmtIdx[0]);
        if (!(stmt instanceof AppliedFunction call
                && argIdx[0] < call.parameters().size())) {
            return null;
        }
        // F3.3: the answer comes from the TYPER — the navigated ARGUMENT
        // types alone inside the lambda's parameter environment (a
        // synthetic one-statement lambda: typing the WHOLE original
        // lambda would drag its metamodel-plumbing siblings into the
        // type checker, which the prelude deliberately does not
        // declare). The hand-written multiplicity/type walk this
        // replaced was character-for-character Typer.java's arithmetic,
        // hand-maintained twice over the UNTYPED spec (audit F3.3). A
        // shape the platform cannot type falls to the caller's honest
        // wall, never a guessed verdict.
        com.legend.compiler.spec.typed.TypedSpec typedArg;
        try {
            LambdaFunction single = new LambdaFunction(lam.parameters(),
                    java.util.List.of(call.parameters().get(argIdx[0])));
            ValueSpecification resolved = com.legend.compiler.NameResolver
                    .resolveQuery(single, imports, ctx.elementFqns());
            com.legend.compiler.spec.typed.TypedSpec typedLam =
                    new com.legend.compiler.spec.SpecCompiler(ctx)
                            .typeExpression(resolved);
            if (!(typedLam instanceof com.legend.compiler.spec.typed
                    .TypedLambda tl) || tl.body().size() != 1) {
                return null;
            }
            typedArg = tl.body().get(0);
        } catch (com.legend.error.LegendCompileException
                | com.legend.error.NotImplementedException e) {
            return null;   // untypeable here: the caller's wall owns it
        }
        var info = typedArg.info();
        boolean data = info.type()
                        instanceof com.legend.compiler.element.type
                                .Type.Primitive
                || info.type() instanceof com.legend.compiler.element.type
                        .Type.EnumType;
        boolean atLeastOne = info.multiplicity()
                instanceof com.legend.compiler.element.type
                        .Multiplicity.Bounded b
                && b.lower() >= 1;
        return data && atLeastOne;
    }





    /** cast/toOne wrappers are identity for this navigation. */
    private static ValueSpecification stripCasts(ValueSpecification v) {
        while (v instanceof AppliedFunction f
                && (EngineTestExecutor.simpleName(f.function()).equals("cast")
                        && f.parameters().size() == 2
                    || EngineTestExecutor.simpleName(f.function()).equals("toOne")
                        && f.parameters().size() == 1)) {
            v = f.parameters().get(0);
        }
        return v;
    }

    /** {@code at(x, k)} — writes k, returns x; identity otherwise. */
    private static ValueSpecification stripAt(ValueSpecification v,
            int[] idx) {
        if (v instanceof AppliedFunction f
                && EngineTestExecutor.simpleName(f.function()).equals("at")
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
                        && (EngineTestExecutor.simpleName(f.function())
                                        .equals("deactivate")
                                || EngineTestExecutor.simpleName(f.function())
                                        .equals("cast")
                                || EngineTestExecutor.simpleName(f.function())
                                        .equals("at")
                                || EngineTestExecutor.simpleName(f.function())
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
