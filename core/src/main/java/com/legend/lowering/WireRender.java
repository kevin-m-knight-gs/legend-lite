// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend.lowering;

import com.legend.compiler.element.type.Type;
import com.legend.sql.SqlQuery;
import com.legend.sql.SqlSelect;
import com.legend.sql.SqlSource;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * E5 (JAVA_EVICTION_PLAN): the PRODUCT wire text is a PLAN PROJECTION —
 * the database renders the CSV/JSON bytes a product consumer observes
 * (the E1 PCT precedent, generalized to the product surface). Pure SQL
 * composition over an already-lowered plan: shape-agnostic wrapping,
 * never rewriting (F4.4).
 */
public final class WireRender {

    /** The product wire formats the plan can render. */
    public enum Format { CSV, JSON }

    /** Whose text a JSON wire's values carry (docs/OUTPUT_LAYER_DESIGN_2026_10_10.md §4, decision 2, 2026-10-18):
     *  {@code PURE}, interpreted Pure's own print forms, on lite's own outputs; {@code ENGINE}, legend-engine's
     *  conventions -- a Float as Java prints a double, a DateTime with nine fraction digits -- on the engine's API
     *  ({@code pure/v1} execute, served and hosted). The VALUES are Pure's either way. */
    /** The JSON wire's text of a value: Pure's ({@code PURE}), legend-engine's ({@code ENGINE}: the pure/v1 API), or
     *  the CARRIER's -- each cell as its slot holds it, for a decode by the plan's column (the judges' typed rows,
     *  the plan path program's step 6, 2026-10-18): a JSON or literal-labelled slot's own token, a timestamp slot
     *  always timed (a midnight is no day), a Float's and a Decimal's text by their Pure type. */
    public enum Spelling {
        PURE, ENGINE, CARRIER,
        /** The carrier of a GRID's cells: a temporal cell by the database's own type -- a DATE a day, anything else
         *  nine fraction digits, the canon the legacy host route decoded a grid's cell from (the setup's DDL can
         *  diverge from the store's declaration, so the slot does not say). */
        CARRIER_GRID
    }

    private WireRender() {
    }

    /** The wire's typed relation: a tabular root's own schema; a scalar or
     * collection root is the one-column {@code value} relation (the
     * scalarRoot contract). */
    public static Type.RelationType schema(com.legend.compiler.element.type.ExprType info) {
        Type.RelationType rt = Type.schemaView(info.type());
        return rt != null ? rt
                : new Type.RelationType(List.of(new Type.Column("value", info.type(), info.multiplicity())));
    }

    /** The whole result as ONE text value ("wire" column): RFC 4180 CSV
     * or the {@code [row,…]} JSON array. {@code schema} is the root's
     * typed relation — CSV cells dispatch on the PURE column kind
     * (F5.4), JSON rides the database's own {@code json_object} typing. */
    public static SqlQuery wrap(SqlQuery plan, Type.RelationType schema,
            Format format) {
        return wrap(plan, schema, format, Spelling.PURE);
    }

    /** {@link #wrap(SqlQuery, Type.RelationType, Format)} with the JSON wire's values in {@code spelling}'s text;
     *  the CSV is lite's own and has one. */
    public static SqlQuery wrap(SqlQuery plan, Type.RelationType schema, Format format, Spelling spelling) {
        SqlSelect inner = asSelect(plan);
        boolean dynamicPivot = hasDynamicPivot(plan);
        if (format == Format.JSON) {
            // a dynamic pivot's rows are their own JSON (the database's tokens: the plan path program's step 4,
            // 2026-10-18; Pure's Float text on such a wire is a named residue)
            return dynamicPivot ? Render.jsonWireOfRows(inner, "_wire1")
                    : Render.jsonWire(inner, typedOrNull(inner, schema), "_wire1", spelling);
        }
        if (dynamicPivot) {
            // the database names the pivot's columns itself, as for its TDS text (step 4, 2026-10-18)
            SqlSource.Pivot pivot = TdsTextWrap.pivots(plan).stream().filter(p -> p.in().isEmpty()).findFirst()
                    .orElseThrow(() -> new IllegalStateException("csv wire: no dynamic pivot in the plan"));
            return Render.csvWireOfDynamicPivot(inner, pivot, TdsTextWrap.staticColumns(inner.outputs(), schema),
                    TdsTextWrap.templates(pivot, schema), "_wire1");
        }
        if (spelling != Spelling.PURE) {
            throw new IllegalArgumentException("the CSV wire has one spelling, Pure's; the engine's is the JSON wire's");
        }
        Map<String, Type.Column> byName = new LinkedHashMap<>();
        for (Type.Column c : schema.columns()) {
            byName.put(c.name(), c);
        }
        List<Type.Column> relCols = new ArrayList<>();
        for (var oc : inner.outputs()) {
            Type.Column t = byName.get(oc.name());
            if (t == null) {
                throw new com.legend.error.NotImplementedException(
                        "csv wire: output column '" + oc.name()
                        + "' has no typed relation column (dynamic-column"
                        + " plans are not wire-rendered yet)");
            }
            relCols.add(t);
        }
        return Render.csvWire(inner, relCols, "_wire1");
    }

    /** The STREAMING JSON form: one {@code _wire_row} object text per
     * JDBC row (order preserved on the wrapper) — the executor writes
     * only the array punctuation. */
    public static SqlQuery rows(SqlQuery plan, Type.RelationType schema) {
        return rows(plan, schema, Spelling.PURE);
    }

    /** {@link #rows(SqlQuery, Type.RelationType)} with each cell's token in {@code spelling}. */
    public static SqlQuery rows(SqlQuery plan, Type.RelationType schema, Spelling spelling) {
        // A statement's own CTEs stay at its head, the wire taken over the body (the plan path program's step 6,
        // 2026-10-18): H2 parses a derived table's text again for each plan it weighs, and a verdict statement's
        // WITH wrapped inside two of them exhausted its heap on the corpus's cross-database bodies, where the same
        // statement sent bare had run. The body itself keeps its projections (the dialects' rewrites find an UNNEST,
        // a list or a variant navigation at a projection's root, never inside the row's text) and is wrapped once.
        if (plan instanceof com.legend.sql.SqlWith w) {
            return w.with(w.ctes(), rows(w.body(), schema, spelling));
        }
        // a UNION ALL's branches each wrapped once (the fused verdict batch), where every branch is a select with no
        // order of its own (an ORDER BY inside a union branch is no SQL); else the union is wrapped whole
        if (plan instanceof com.legend.sql.SqlUnion u && u.all()
                && u.branches().stream().allMatch(b -> b instanceof SqlSelect bs && bs.orderBy().isEmpty())) {
            List<SqlQuery> branches = new ArrayList<>(u.branches().size());
            for (SqlQuery b : u.branches()) {
                SqlSelect bs = (SqlSelect) b;
                branches.add(Render.jsonWireRows(bs, typedOrNull(bs, schema), "_wire1", spelling));
            }
            return com.legend.sql.SqlUnion.ofBranches(branches, true);
        }
        SqlSelect inner = asSelect(plan);
        return Render.jsonWireRows(inner, typedOrNull(inner, schema), "_wire1", spelling);
    }

    /** Each output's typed column, or null where the schema names none (a pivot's generated column): the JSON wires
     *  spell a Float and a Decimal by their Pure type and take the database's token for the rest. */
    private static List<Type.@com.legend.base.Nullable Column> typedOrNull(SqlSelect inner, Type.RelationType schema) {
        Map<String, Type.Column> byName = new LinkedHashMap<>();
        for (Type.Column c : schema.columns()) {
            byName.put(c.name(), c);
        }
        List<Type.@com.legend.base.Nullable Column> out = new ArrayList<>();
        for (var oc : inner.outputs()) {
            out.add(byName.get(oc.name()));
        }
        return out;
    }

    /** Does {@code plan} hold a pivot whose columns the database names (no {@code IN} list)? */
    public static boolean hasDynamicPivot(SqlQuery plan) {
        return TdsTextWrap.pivots(plan).stream().anyMatch(p -> p.in().isEmpty());
    }

    private static SqlSelect asSelect(SqlQuery plan) {
        return plan instanceof SqlSelect s ? s
                : SqlSelect.starOf(new SqlSource.Subselect(plan, "_wire0", null));
    }
}
