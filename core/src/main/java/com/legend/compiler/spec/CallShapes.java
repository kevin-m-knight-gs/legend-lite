// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0
package com.legend.compiler.spec;

import com.legend.compiler.element.ModelContext;
import com.legend.compiler.element.TypedFunction;
import com.legend.compiler.element.type.Multiplicity;
import com.legend.compiler.spec.typed.TypedSpec;
import com.legend.protocol.spec.AppliedFunction;
import com.legend.protocol.spec.LambdaFunction;
import com.legend.protocol.spec.ValueSpecification;
import com.legend.protocol.spec.Variable;

import java.util.ArrayList;
import java.util.List;

/**
 * Call-shape rules the typer applies BEFORE overload resolution: pure's
 * dot-spelling auto-map over a many-valued receiver, a let-bound lambda
 * literal in a core construct's argument position, and a packageable
 * element read as its metamodel value.
 */
final class CallShapes {

    private CallShapes() {
    }

    /** Pure's auto-map on the DOT spelling: {@code $xs.qp()} over a
     * many-valued receiver maps the [1]-receiver qualified property over
     * it ({@code relationalExtensions().routerExtensions()} — real m3's
     * SimpleFunctionExpression auto-map; the arrow spelling does not).
     * Null when the call is not that shape. */
    static @com.legend.Nullable TypedSpec autoMapReceiver(Typer t, AppliedFunction af, Env env) {
        if (!af.propertyCall() || af.parameters().isEmpty()) {
            return null;
        }
        List<TypedFunction> cands = t.functionCandidates(af).stream()
                .filter(c -> c.parameters().size() == af.parameters().size())
                .toList();
        if (cands.isEmpty() || !cands.stream().allMatch(c ->
                Multiplicity.Bounded.ONE.equals(c.parameters().get(0).multiplicity()))) {
            return null;
        }
        TypedSpec recv;
        try {
            recv = t.synth(af.parameters().get(0), env);
        } catch (TypeInferenceException notStandalone) {
            // a receiver the call's own checker types in context — no
            // auto-map decision here; the ordinary path answers loudly
            return null;
        }
        if (!recv.info().multiplicity().isMany()) {
            return null;
        }
        String e = "_am_" + af.function().replace("::", "_");
        List<ValueSpecification> rest = new ArrayList<>(af.parameters());
        rest.set(0, new Variable(e, null, null, null));
        AppliedFunction inner = new AppliedFunction(af.function(), rest, af.candidateFqns(),
                af.pos(), false, af.grouped(), af.infix());
        return t.synth(new AppliedFunction("map", List.of(af.parameters().get(0),
                new LambdaFunction(List.of(new Variable(e, null, null, null)), List.of(inner)))), env);
    }

    /** A LET-BOUND lambda literal in a CORE construct's argument position
     * ({@code let f = t|$t.quantity < 45; ->filter($f)}) is its literal:
     * pure's let is immutable and referentially transparent, and the core
     * checkers type a lambda literal against their signature
     * ({@link Args#lambda}) — the engine's router inlines the value at
     * the same point. Generic and user calls (execute's query carrier)
     * keep the variable: their checkers take the function VALUE. */
    static AppliedFunction expandLetBoundLambdaArgs(AppliedFunction af, Env env) {
        List<ValueSpecification> np = null;
        for (int i = 0; i < af.parameters().size(); i++) {
            if (af.parameters().get(i) instanceof Variable v
                    && env.exprAlias(v.name()).orElse(null) instanceof LambdaFunction bound) {
                if (np == null) {
                    np = new ArrayList<>(af.parameters());
                }
                np.set(i, bound);
            }
        }
        return np == null ? af : af.withParameters(np);
    }

    /** The platform class a packageable ELEMENT reads as when it is used
     * as a metamodel VALUE (its system-store row): a database or a
     * mapping; null for any other name. */
    static @com.legend.Nullable String metamodelElementClass(ModelContext ctx, String fqn) {
        if (ctx.findDatabase(fqn).isPresent()) {
            return "meta::relational::metamodel::Database";
        }
        if (ctx.findMapping(fqn).isPresent()) {
            return "meta::pure::mapping::Mapping";
        }
        return null;
    }

}
