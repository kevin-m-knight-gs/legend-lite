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
    /** Filter/map over a raw grid: splice the SOURCE to the relation
     * node and rewrite the SPEC accessor within the lambda —
     * {@code $r.value('N')} (direct user-call, or its G-half-inlined
     * {@code at($r.values, indexOf($r.parent.columnNames,'N'))} form)
     * becomes a binder PROPERTY read the relation lowering resolves
     * (the dynamic trust-the-name rule). Surface stays spec-exact;
     * the rewrite is implementation. */
    /** The hook entry: lambda forms first, then the bare rows form. */
    public static @com.legend.Nullable TypedSpec spliceAny(TypedSpec n) {
        TypedSpec lam = gridLambdaForm(n);
        return lam != null ? lam : rawGridRelation(n);
    }

    public static @com.legend.Nullable TypedSpec gridLambdaForm(
            TypedSpec n) {
        com.legend.compiler.spec.typed.TypedLambda lam;
        TypedSpec src;
        if (n instanceof com.legend.compiler.spec.typed.TypedFilter f) {
            lam = f.predicate();
            src = f.source();
        } else if (n instanceof com.legend.compiler.spec.typed.TypedMap m) {
            lam = m.mapper();
            src = m.source();
        } else {
            return null;
        }
        TypedSpec grid = src instanceof TypedRawSqlRelation
                ? src : rawGridRelation(src);
        if (grid == null || lam.parameters().size() != 1) {
            return null;
        }
        String binder = lam.parameters().get(0);
        java.util.List<TypedSpec> body = new java.util.ArrayList<>();
        boolean changed = !src.equals(grid);
        for (TypedSpec stmt : lam.body()) {
            TypedSpec rw = rewriteValueReads(stmt, binder);
            changed |= rw != stmt;
            body.add(rw);
        }
        if (!changed) {
            return null;
        }
        var lam2 = new com.legend.compiler.spec.typed.TypedLambda(
                lam.parameters(), body, lam.info());
        // the rebuilt FILTER is relation-typed like its source (its
        // declared Row[*] surface was the spec's; the implementation
        // world is relational) — map keeps its value-collection info
        // (the map-binder channel)
        return n instanceof com.legend.compiler.spec.typed.TypedFilter
                ? new com.legend.compiler.spec.typed.TypedFilter(
                        grid, lam2, grid.info())
                : new com.legend.compiler.spec.typed.TypedMap(
                        grid, lam2,
                        ((com.legend.compiler.spec.typed.TypedMap) n).info());
    }

    private static TypedSpec rewriteValueReads(TypedSpec n, String binder) {
        // direct spec accessor: value($binder, 'N')
        if (n instanceof com.legend.compiler.spec.typed.TypedUserCall uc
                && uc.callee().qualifiedName().contains("::execute::")
                && uc.callee().qualifiedName().endsWith("value")
                && uc.args().size() == 2
                && uc.args().get(0) instanceof
                        com.legend.compiler.spec.typed.TypedVariable v
                && v.name().equals(binder)
                && uc.args().get(1) instanceof TypedCString col) {
            return new TypedPropertyAccess(uc.args().get(0), col.value(),
                    uc.info());
        }
        // the G-half-inlined form: at($binder.values,
        //   indexOf($binder.parent.columnNames, 'N'))
        if (n instanceof TypedNativeCall at
                && "meta::pure::functions::collection::at"
                        .equals(at.callee().qualifiedName())
                && at.args().size() == 2
                && at.args().get(0) instanceof TypedPropertyAccess vals
                && "values".equals(vals.property())
                && vals.source() instanceof
                        com.legend.compiler.spec.typed.TypedVariable bv
                && bv.name().equals(binder)
                && at.args().get(1) instanceof TypedNativeCall idx
                && "meta::pure::functions::collection::indexOf"
                        .equals(idx.callee().qualifiedName())
                && idx.args().size() == 2
                && idx.args().get(1) instanceof TypedCString col2) {
            return new TypedPropertyAccess(vals.source(), col2.value(),
                    at.info());
        }
        java.util.List<TypedSpec> kids = n.children();
        if (kids.isEmpty()) {
            return n;
        }
        java.util.List<TypedSpec> out = new java.util.ArrayList<>(kids.size());
        boolean changed = false;
        for (TypedSpec k : kids) {
            TypedSpec rw = rewriteValueReads(k, binder);
            changed |= rw != k;
            out.add(rw);
        }
        return changed ? n.withChildren(out) : n;
    }

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
