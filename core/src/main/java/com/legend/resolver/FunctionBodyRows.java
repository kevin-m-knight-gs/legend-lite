// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend.resolver;

import com.legend.compiler.element.ModelContext;
import com.legend.compiler.spec.typed.TypedLambda;
import com.legend.compiler.spec.typed.TypedSpec;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A function VALUE's body as rows (harness burn-down group A, 2026-09-03):
 * {@code $f.expressionSequence} over a lambda (a function reference
 * eta-expands to one) is its statements as ValueSpecification rows under
 * the lambda's scope — {@code functions(id, name)},
 * {@code value_specifications(id, function_id, ordinal, kind)} and each
 * statement's compiler-stamped inferred primary key
 * {@code vs_primary_key_columns(node_id, ordinal, name)} (PkInference).
 * The rows ride the query (constructed-scope inline relations); the
 * system database is never written for them.
 */
final class FunctionBodyRows {

    private FunctionBodyRows() {
    }

    /** The lambda's scope id — a content id (a lambda has no source span
     * of its own; the same object meets the resolver as the chain root). */
    static String scopeId(TypedLambda lam) {
        String text = lam.toString();
        return "fn:" + Integer.toHexString(text.hashCode()) + ":" + text.length();
    }

    static Map<String, List<List<String>>> rows(String scope, TypedLambda lam,
            ModelContext ctx) {
        Map<String, List<List<String>>> out = new LinkedHashMap<>();
        out.put("functions", List.of(List.of(scope, "")));
        List<List<String>> vs = new ArrayList<>();
        List<List<String>> pk = new ArrayList<>();
        for (int i = 0; i < lam.body().size(); i++) {
            TypedSpec stmt = lam.body().get(i);
            String id = scope + "/" + i;
            vs.add(List.of(id, scope, Integer.toString(i),
                    stmt.getClass().getSimpleName()));
            List<String> cols = PkInference.infer(stmt, ctx);
            for (int k = 0; k < cols.size(); k++) {
                pk.add(List.of(id, Integer.toString(k), cols.get(k)));
            }
        }
        out.put("value_specifications", vs);
        out.put("vs_primary_key_columns", pk);
        return out;
    }
}
