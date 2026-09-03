// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend.compiler.spec.typed;

import com.legend.compiler.element.type.ExprType;

import java.util.List;

/**
 * The engine's RESULT JSON of a TDS- or class-rooted query — the string
 * entry's ({@code meta::legend::executeLegendQuery}) value for those
 * roots, BY EMISSION over the query chain: {@code {"builder":{"_type":
 * "tdsBuilder","columns":[{name,type}…]},"activities":[{"_type":
 * "relational","sql":…}],"result":{"columns":[…],"rows":[{"values":
 * [...]}…]}}} (engine TDSResult serialization), or the classBuilder /
 * {@code objects} form for a class root. {@code sql} is the activity's
 * SQL text (the engine-style render of the same chain), null when no
 * render exists — the activities array is then empty. Lowered as ONE
 * scalar subquery aggregating the chain's rows.
 */
public record TypedJsonResult(TypedSpec chain, Kind kind,
        @com.legend.Nullable String sql, ExprType info) implements TypedSpec {

    public enum Kind { TDS, CLASS }

    @Override
    public List<TypedSpec> children() {
        return List.of(chain);
    }

    @Override
    public TypedSpec withChildren(List<TypedSpec> kids) {
        TypedSpec.expectChildren(kids, 1, "TypedJsonResult");
        return new TypedJsonResult(kids.get(0), kind, sql, info);
    }
}
