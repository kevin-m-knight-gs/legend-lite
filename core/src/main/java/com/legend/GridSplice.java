// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend;

import com.legend.compiler.element.type.ExprType;
import com.legend.compiler.element.type.PlatformTypes;
import com.legend.compiler.element.type.Type;
import com.legend.compiler.spec.typed.TypedCString;
import com.legend.compiler.spec.typed.TypedFrom;
import com.legend.compiler.spec.typed.TypedNativeCall;
import com.legend.compiler.spec.typed.TypedPropertyAccess;
import com.legend.compiler.spec.typed.TypedRawSqlRelation;
import com.legend.compiler.spec.typed.TypedSpec;

/**
 * Phase 1c (One-Platform Plan): the {@code .rows}-over-authored-SQL
 * splice — the SURFACE stays legend-pure-spec-exact ({@code executeInDb
 * : ResultSet[1]}, {@code rows: Row[*]}, user-ratified), while the
 * IMPLEMENTATION types the grid as a relation the ordinary pipeline
 * lowers ({@link TypedRawSqlRelation} &rarr; {@code SqlSource.RawSql};
 * DuckDb's {@code RawSqlAdapt} pass owns the boundary translation).
 * This is the arm that makes grid chains ORDINARY: size/slice/anything
 * relation-shaped composes with zero recognizer vocabulary.
 */
public final class GridSplice {

    private GridSplice() {
    }

    /** {@code <executeInDb(literal sql, ...)>.rows} &rarr; the typed
     * relation node; null when the node is not that shape. */
    public static @com.legend.Nullable TypedSpec rawGridRelation(
            TypedSpec n) {
        if (!(n instanceof TypedPropertyAccess rra)
                || !rra.property().equals("rows")) {
            return null;
        }
        TypedSpec src = rra.source();
        while (src instanceof TypedFrom sf) {
            src = sf.source();
        }
        if (src instanceof TypedNativeCall enc
                && PlatformTypes.EXECUTE_IN_DB
                        .equals(enc.callee().qualifiedName())
                && enc.args().get(0) instanceof TypedCString rawSql) {
            String text = rawSql.value().strip();
            if (text.endsWith(";")) {
                text = text.substring(0, text.length() - 1);
            }
            return new TypedRawSqlRelation(text, ExprType.one(
                    new Type.RelationType(java.util.List.of(),
                            java.util.List.of())));
        }
        return null;
    }
}
