// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend.resolver;

import com.legend.compiler.element.ModelContext;
import com.legend.compiler.element.type.ExprType;
import com.legend.compiler.element.type.Multiplicity;
import com.legend.compiler.element.type.Type;
import com.legend.compiler.spec.typed.TypedPropertyAccess;
import com.legend.compiler.spec.typed.TypedSpec;
import com.legend.compiler.spec.typed.TypedTds;
import com.legend.compiler.spec.typed.TypedVariable;
import com.legend.error.NotImplementedException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * THE JSON SOURCE FRAME (XStore leg §1): a
 * {@code JsonModelConnection(class=C, url='data:application/json,<payload>')}
 * is STATIC ROWS — the class realizes as a typed VALUES relation
 * ({@link TypedTds}; {@code Scalars.tdsCell} coerces per column type, dates
 * included). The CLASS DECLARATION is the schema (model-driven DDL
 * discipline): scalar properties become columns; class-typed properties
 * contribute nothing (reads through them keep their own walls). Payload
 * variants: one JSON object = one row; an array of objects = n rows.
 * Everything else is loud.
 */
final class JsonSourceFrame {

    /** The hidden VALUES row-identity column (never a binding). */
    static final String FRAME_ORDINAL = "u_frame_ord__";

    private JsonSourceFrame() {
    }

    /** {@code ${var}} URL-template substitution from the let env (the
     * engine binds url parameters from in-scope query lets); a var whose
     * let is not a string LITERAL stays verbatim — its row is textual and
     * downstream filters treat it as data (loud divergence, never crash). */
    static Map<String, String> substituteUrlParams(Map<String, String> urls,
            Map<String, TypedSpec> letBindings) {
        Map<String, String> out = new LinkedHashMap<>();
        for (var e : urls.entrySet()) {
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("\\$\\{(\\w+)\\}").matcher(e.getValue());
            StringBuilder sb = new StringBuilder();
            while (m.find()) {
                TypedSpec b = letBindings.get(m.group(1));
                m.appendReplacement(sb,
                        java.util.regex.Matcher.quoteReplacement(
                                b instanceof com.legend.compiler.spec.typed
                                        .TypedCString cs
                                        ? cs.value() : m.group(0)));
            }
            m.appendTail(sb);
            out.put(e.getKey(), sb.toString());
        }
        return out;
    }

    /** One JSON value as a TDS-grid cell, typed by the DECLARED column
     * (F7.3: the old {@code String.valueOf} erased every JSON type back
     * to text). A Variant cell re-emits REAL JSON, quote-wrapped for the
     * grid ({@code Scalars.tdsCell}'s variant arm strips one outer quote
     * pair); a structured value under a SCALAR column walls; a JSON
     * string that spells the grid's null tokens walls (the string grid
     * cannot carry it — blocked on the SourceUrl re-platform, §9). */
    static String cellText(@com.legend.Nullable Object v,
            Type t, String classFqn, String prop) {
        if (v == null) {
            return com.legend.compiler.element.type.PlatformTypes
                    .TDS_NULL_CELL;
        }
        if (com.legend.compiler.element.type.PlatformTypes.isVariant(t)) {
            return "\"" + com.legend.exec.ResultJson.jsonText(v) + "\"";
        }
        if (v instanceof Map || v instanceof List) {
            throw new NotImplementedException("JSON source for '" + classFqn
                    + "': structured value under scalar property '" + prop
                    + "' (" + t.typeName() + ") — declare the property as"
                    + " Variant, or wait for the SourceUrl re-platform");
        }
        if (v instanceof String sv && (sv.isEmpty() || sv.equals("null")
                || sv.equals(com.legend.compiler.element.type.PlatformTypes
                        .TDS_NULL_CELL))) {
            throw new NotImplementedException("JSON source for '" + classFqn
                    + "': string value '" + sv + "' for property '" + prop
                    + "' collides with the TDS-grid null spelling — the"
                    + " string grid cannot carry it (SourceUrl re-platform,"
                    + " §9)");
        }
        return String.valueOf(v);
    }

    static ClassSource classSource(ModelContext ctx, String mappingFqn,
            String classFqn, String url) {
        String prefix = "data:application/json,";
        if (!url.startsWith(prefix)) {
            throw new NotImplementedException("JsonModelConnection url for '"
                    + classFqn + "' is not a data:application/json literal —"
                    + " remote/parameterized sources are not supported yet");
        }
        // one object = one row; [array] = n rows; CONCATENATED objects
        // ({..}{..}) = the engine's row-stream spelling, one per row
        List<Object> values = com.legend.sql.Json.parseAll(
                url.substring(prefix.length()));
        List<Map<?, ?>> objects = new ArrayList<>();
        for (Object payload : values) {
            if (payload instanceof Map<?, ?> one) {
                objects.add(one);
            } else if (payload instanceof List<?> arr) {
                for (Object o : arr) {
                    if (!(o instanceof Map<?, ?> m)) {
                        throw new NotImplementedException("JSON source for '"
                                + classFqn + "' carries a non-object array"
                                + " element — not supported");
                    }
                    objects.add(m);
                }
            } else {
                throw new NotImplementedException("JSON source for '" + classFqn
                        + "' is neither an object nor an array of objects");
            }
        }
        var cls = ctx.findClass(classFqn).orElseThrow(() ->
                new IllegalStateException("resolver bug: JSON-sourced class '"
                        + classFqn + "' unknown to the model"));
        List<Type.Column> cols = new ArrayList<>();
        for (var p : cls.properties()) {
            // F7.3: Variant IS a column (the platform's JSON carrier) —
            // a nested object/array realizes as real JSON, never Java
            // toString. Other class-typed properties contribute nothing
            // (reads through them keep their own walls).
            if (p.type() instanceof Type.ClassType ct
                    && !com.legend.compiler.element.type.PlatformTypes
                            .isVariant(ct)) {
                continue;
            }
            cols.add(new Type.Column(p.name(), p.type(), p.multiplicity()));
        }
        if (cols.isEmpty()) {
            throw new NotImplementedException("JSON-sourced class '" + classFqn
                    + "' declares no scalar properties — nothing to realize");
        }
        List<List<String>> rows = new ArrayList<>(objects.size());
        for (Map<?, ?> o : objects) {
            List<String> row = new ArrayList<>(cols.size());
            for (Type.Column c : cols) {
                row.add(cellText(o.get(c.name()), c.type(), classFqn,
                        c.name()));
            }
            rows.add(row);
        }
        // HIDDEN ROW ORDINAL — the VALUES row identity: two sets composed
        // over the SAME frame correlate on it (mixed-union per-member
        // children, XSTORE_LEG design). Not a class property: excluded
        // from bindings, so no serialize leaf or query read ever sees it.
        for (int i = 0; i < rows.size(); i++) {
            rows.get(i).add(String.valueOf(i));
        }
        cols.add(new Type.Column(FRAME_ORDINAL, Type.Primitive.INTEGER,
                com.legend.compiler.element.type.Multiplicity.Bounded.ONE));
        Type.RelationType rowType = new Type.RelationType(cols);
        ExprType rowInfo = new ExprType(rowType, Multiplicity.Bounded.ONE);
        TypedSpec pipeline = new TypedTds(rows, rowInfo);
        String rowVar = "src_json";
        Map<String, TypedSpec> bindings = new LinkedHashMap<>();
        for (Type.Column c : cols) {
            if (c.name().equals(FRAME_ORDINAL)) {
                continue;
            }
            bindings.put(c.name(), new TypedPropertyAccess(
                    new TypedVariable(rowVar, rowInfo), c.name(),
                    new ExprType(c.type(), c.multiplicity())));
        }
        return new ClassSource(mappingFqn, classFqn, "json", pipeline,
                rowVar, bindings, rowType, classFqn);
    }

    /** ONE from()-scope entry (StoreResolver.fromContext): the re-scoped
     * Context; the runtime rides ALONGSIDE an explicit mapping (a
     * self-sourced M2M's upstream dispatch needs the candidate set). */
    static StoreResolver.Context fromContext(
            com.legend.compiler.spec.typed.TypedFrom fr,
            StoreResolver.Context outer, ClassSources sources,
            java.util.Map<String, com.legend.compiler.spec.typed.TypedSpec>
                    letBindings) {
        if (!fr.jsonSources().isEmpty()) {
            sources.setJsonSources(substituteUrlParams(
                    fr.jsonSources(), letBindings));
        }
        if (fr.mapping().isPresent()) {
            return new StoreResolver.Context(fr.mapping().get().fullPath(),
                    fr.runtime().map(r -> r.fullPath())
                            .orElse(outer.runtimeFqn()),
                    fr.chainMappings(), fr.jsonSources());
        }
        if (fr.runtime().isPresent()) {
            return new StoreResolver.Context(null,
                    fr.runtime().get().fullPath(),
                    fr.chainMappings(), fr.jsonSources());
        }
        return outer;
    }
}
