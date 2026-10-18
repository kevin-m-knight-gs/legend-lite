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
    public enum Spelling { PURE, ENGINE }

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
        if (format == Format.JSON) {
            return Render.jsonWire(inner, typedOrNull(inner, schema), "_wire1", spelling);
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
        SqlSelect inner = asSelect(plan);
        return Render.jsonWireRows(inner, typedOrNull(inner, schema), "_wire1");
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

    private static SqlSelect asSelect(SqlQuery plan) {
        return plan instanceof SqlSelect s ? s
                : SqlSelect.starOf(new SqlSource.Subselect(plan, "_wire0", null));
    }
}
