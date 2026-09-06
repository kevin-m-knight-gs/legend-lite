// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0
package com.legend.compiler.spec;

import com.legend.compiler.element.type.ExprType;
import com.legend.compiler.element.type.Type;
import com.legend.compiler.spec.typed.TypedJsonResult;
import com.legend.compiler.spec.typed.TypedSpec;
import com.legend.protocol.spec.AppliedFunction;
import java.util.List;

/**
 * {@code toJSON(tds)} (REAL declaration: {@code meta::json::toJSON(obj:
 * Any[*]):String[1]}) over a TABULAR argument — the engine serializes a
 * TabularDataSet as {@code {"columns":[{"name","type","metaType"}],
 * "rows":[{"values":[..]}]}} (toJSON.pure's TabularDataSet arm). The
 * document is EMITTED by the database over the chain (a
 * {@link TypedJsonResult} of kind TDS_JSON — one scalar subquery
 * aggregating the rows, the column metadata a static fact of the typed
 * relation). Every other argument shape rides the generic native.
 */
final class TdsJsonChecker {
    private static final String TO_JSON_FQN = "meta::json::toJSON";

    private TdsJsonChecker() {
    }

    static TypedSpec check(Typer t, AppliedFunction af, Env env) {
        if (af.parameters().size() != 1) {
            return t.applyGeneric(af, env);
        }
        TypedSpec arg = t.synth(af.parameters().get(0), env);
        Type.RelationType rt = Type.relationSchema(arg.info().type());
        if (rt == null || rt.isLateBound()) {
            return t.applyGeneric(af, env);
        }
        // validate against the REGISTERED native signature — never bypassed
        t.kernel().resolveOverload(t.model().findFunction(TO_JSON_FQN),
                List.of(arg.info()));
        return new TypedJsonResult(arg, TypedJsonResult.Kind.TDS_JSON, null,
                ExprType.one(Type.Primitive.STRING));
    }
}
