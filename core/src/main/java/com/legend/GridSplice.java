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
    /** The spec's Row.value qualified property (normalized to the
     * {@code Row$prop$value} user function) — a grid READ, never an
     * effect statement. */
    public static boolean isRowValueRead(
            com.legend.compiler.spec.typed.TypedUserCall call) {
        String q = call.callee().qualifiedName();
        return q.contains("::execute::") && q.endsWith("value");
    }

    /** The hook entry: lambda forms first, then the bare rows form,
     * then {@code .columnNames} (a SCHEMA FACT — served by the caller's
     * probe function; GridSplice itself never touches a connection). */
    public static @com.legend.Nullable TypedSpec spliceAny(TypedSpec n,
            com.legend.compiler.element.ModelContext ctx,
            java.util.Map<String, TypedSpec> lets,
            java.util.function.Function<String, java.util.List<String>> probe) {
        TypedSpec lam = gridLambdaForm(n, ctx, lets, probe);
        if (lam != null) {
            return lam;
        }
        TypedSpec cell = positionalCellForm(n, ctx, lets, probe);
        if (cell != null) {
            return cell;
        }
        TypedSpec rows = rawGridRelation(n, ctx, lets);
        if (rows != null) {
            // every grid relation carries its PROBED schema (Any-typed,
            // late-bound; the probe is a schema read, never values) —
            // the executor's positional column contract and by-name
            // resolution both ride it
            return schemaPinned((TypedRawSqlRelation) rows, probe);
        }
        return columnNamesForm(n, ctx, lets, probe);
    }

    private static TypedSpec schemaPinned(TypedRawSqlRelation raw,
            java.util.function.Function<String, java.util.List<String>> probe) {
        java.util.List<Type.Column> cols = new java.util.ArrayList<>();
        var any = new Type.ClassType(PlatformTypes.ANY);
        var opt = new com.legend.compiler.element.type.Multiplicity.Bounded(0, 1);
        for (String nm : probe.apply(raw.sql())) {
            cols.add(new Type.Column(nm, any, opt));
        }
        return new TypedRawSqlRelation(raw.sql(), ExprType.one(
                new Type.RelationType(cols, java.util.List.of())));
    }

    /** {@code <grid>.rows->at(k).value('N')} — the top-level positional
     * cell read (spec spelling; both accessor forms) &rarr; slice the
     * relation to row k, project column N. Rides TypedSlice + a
     * single-column TypedProject-free map: the value read is the
     * binder property over the sliced relation. */
    private static @com.legend.Nullable TypedSpec positionalCellForm(
            TypedSpec n, com.legend.compiler.element.ModelContext ctx,
            java.util.Map<String, TypedSpec> lets,
            java.util.function.Function<String, java.util.List<String>> probe) {
        // shape A: value(at(rows, k), 'N')  /  shape B: at(at(rows,k).values,
        //   indexOf(at(rows,k).parent.columnNames, 'N'))
        String col = null;
        TypedSpec atCall = null;
        if (n instanceof com.legend.compiler.spec.typed.TypedUserCall uc
                && uc.callee().qualifiedName().contains("::execute::")
                && uc.callee().qualifiedName().endsWith("value")
                && uc.args().size() == 2
                && uc.args().get(1) instanceof TypedCString c) {
            col = c.value();
            atCall = uc.args().get(0);
        } else if (n instanceof TypedNativeCall at
                && "meta::pure::functions::collection::at"
                        .equals(at.callee().qualifiedName())
                && at.args().size() == 2
                && at.args().get(0) instanceof TypedPropertyAccess vals
                && "values".equals(vals.property())
                && at.args().get(1) instanceof TypedNativeCall idx
                && "meta::pure::functions::collection::indexOf"
                        .equals(idx.callee().qualifiedName())
                && idx.args().size() == 2
                && idx.args().get(1) instanceof TypedCString c2) {
            col = c2.value();
            atCall = vals.source();
        }
        if (col == null || !(atCall instanceof TypedNativeCall rowAt)
                || !"meta::pure::functions::collection::at"
                        .equals(rowAt.callee().qualifiedName())
                || rowAt.args().size() != 2
                || !(rowAt.args().get(1) instanceof
                        com.legend.compiler.spec.typed.TypedCInteger k)) {
            return null;
        }
        // the hook is bottom-up: the inner `.rows` may ALREADY be the
        // spliced relation node by the time the outer read is visited
        TypedSpec inner = rowAt.args().get(0);
        TypedSpec grid0 = inner instanceof TypedRawSqlRelation
                ? inner : rawGridRelation(inner, ctx, lets);
        if (!(grid0 instanceof TypedRawSqlRelation raw0)) {
            return null;
        }
        TypedRawSqlRelation raw = (TypedRawSqlRelation) schemaPinned(raw0, probe);
        // relation slice [k, k+1), then PROJECT the by-name column into
        // the platform's map-binder column (SYNTH_MAP_COL): a one-
        // column relation so named IS a value collection/scalar by
        // ResultShape's own rule — the honest relational form of "row
        // k, column N", executed by the ordinary machinery
        long kk = k.value().longValue();
        ExprType i1 = ExprType.one(Type.Primitive.INTEGER);
        TypedSpec sliced = new com.legend.compiler.spec.typed.TypedSlice(raw,
                new com.legend.compiler.spec.typed.TypedCInteger(kk, i1),
                new com.legend.compiler.spec.typed.TypedCInteger(kk + 1, i1),
                raw.info());
        String binder = "_gcell";
        Type.RelationType rowT = (Type.RelationType) raw.info().type();
        var one = com.legend.compiler.element.type.Multiplicity.Bounded.ONE;
        var lam = new com.legend.compiler.spec.typed.TypedLambda(
                java.util.List.of(binder),
                java.util.List.of(new TypedPropertyAccess(
                        new com.legend.compiler.spec.typed.TypedVariable(binder,
                                ExprType.one(rowT)),
                        col, n.info())),
                ExprType.one(new Type.FunctionType(
                        java.util.List.of(new Type.Param(rowT, one)),
                        new Type.Param(n.info().type(),
                                n.info().multiplicity()))));
        String synth = com.legend.sql.SqlSelect.SYNTH_MAP_COL;
        Type.RelationType outT = new Type.RelationType(java.util.List.of(
                new Type.Column(synth, n.info().type(),
                        n.info().multiplicity())), java.util.List.of());
        // the one-row slice's projection is a [1] relation: the
        // executor's existing map-binder rule shapes it SCALAR
        return new com.legend.compiler.spec.typed.TypedProject(sliced,
                java.util.List.of(new com.legend.compiler.spec.typed
                        .TypedFuncCol(synth, lam)),
                new ExprType(outT, n.info().multiplicity()));
    }

    /** {@code <grid>.columnNames} &rarr; a String[*] literal of the
     * probed projection names (schema, never values — the E1 probe
     * discipline; catalog grids know theirs statically through the same
     * probe). Spec-exact surface: ResultSet.columnNames : String[*]. */
    private static @com.legend.Nullable TypedSpec columnNamesForm(
            TypedSpec n, com.legend.compiler.element.ModelContext ctx,
            java.util.Map<String, TypedSpec> lets,
            java.util.function.Function<String, java.util.List<String>> probe) {
        if (!(n instanceof TypedPropertyAccess cn)
                || !cn.property().equals("columnNames")) {
            return null;
        }
        // reuse the rows-splice recognition over a synthetic `.rows`
        TypedSpec asRows = rawGridRelation(new TypedPropertyAccess(
                cn.source(), "rows", cn.info()), ctx, lets);
        if (!(asRows instanceof TypedRawSqlRelation raw)) {
            return null;
        }
        java.util.List<String> names = probe.apply(raw.sql());
        java.util.List<TypedSpec> lits = new java.util.ArrayList<>(names.size());
        ExprType str1 = ExprType.one(Type.Primitive.STRING);
        for (String nm : names) {
            lits.add(new TypedCString(nm, str1));
        }
        return new com.legend.compiler.spec.typed.TypedCollection(lits,
                cn.info());
    }

    public static @com.legend.Nullable TypedSpec gridLambdaForm(
            TypedSpec n, com.legend.compiler.element.ModelContext ctx,
            java.util.Map<String, TypedSpec> lets,
            java.util.function.Function<String, java.util.List<String>> probe) {
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
                ? src : rawGridRelation(src, ctx, lets);
        if (grid == null || lam.parameters().size() != 1) {
            return null;
        }
        if (((TypedRawSqlRelation) grid).info().type()
                instanceof Type.RelationType rt && rt.columns().isEmpty()) {
            grid = schemaPinned((TypedRawSqlRelation) grid, probe);
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
        return rawGridRelation(n, null, java.util.Map.of());
    }

    /** Context-aware form: fetchDb* bottoms (catalog grids — registered
     * platform SQL, the decision rule's permitted class) splice too;
     * PK grids need the model's facts, hence {@code ctx}. */
    public static @com.legend.Nullable TypedSpec rawGridRelation(
            TypedSpec n,
            com.legend.compiler.element.@com.legend.Nullable ModelContext ctx,
            java.util.Map<String, TypedSpec> lets) {
        if (!(n instanceof TypedPropertyAccess rra)
                || !rra.property().equals("rows")) {
            return null;
        }
        TypedSpec src = rra.source();
        while (src instanceof TypedFrom sf) {
            src = sf.source();
        }
        String text = null;
        if (src instanceof TypedNativeCall enc
                && PlatformTypes.EXECUTE_IN_DB
                        .equals(enc.callee().qualifiedName())
                && enc.args().get(0) instanceof TypedCString rawSql) {
            text = rawSql.value().strip();
            if (text.endsWith(";")) {
                text = text.substring(0, text.length() - 1);
            }
        } else if (ctx != null && src instanceof TypedNativeCall fnc
                && PlatformTypes.isFetchDbFn(fnc.callee().qualifiedName())) {
            text = com.legend.exec.ResultNav.gridSql(fnc, lets, ctx);
        }
        if (text == null) {
            return null;
        }
        return new TypedRawSqlRelation(text, ExprType.one(
                new Type.RelationType(java.util.List.of(),
                        java.util.List.of())));
    }
}
