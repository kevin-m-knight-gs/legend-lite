// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend.harness;

import com.legend.compiler.element.ModelContext;
import com.legend.protocol.spec.AppliedFunction;
import com.legend.protocol.spec.CInteger;
import com.legend.protocol.spec.CString;
import com.legend.protocol.spec.PureCollection;
import com.legend.protocol.spec.ValueSpecification;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The {@code generateObjectReferences(clientVersion, mappingPath,
 * rootSetId[, setId], runtimeFnPath, pkMaps, extensions)} builder
 * (engine objectReference.pure:20-42): per pk-map, ONE store-object
 * reference in the SAME ASOR protocol the serialize envelope emits
 * (asorPrefix) — producer and consumer are both ours, and
 * objectReferenceIn decodes the trailing pk segment. The engine's
 * column-keyed pkMap ({@code pair('name','C')->newMap()}) maps onto
 * {@code pk$_i} in the set's ~primaryKey column order
 * (case-insensitive name match, resolvePrimaryKeysNames parity).
 * Output = a JSON array literal of 'ASOR:' strings. Null = not this
 * shape or not resolvable (the unknown-function wall stays).
 */
final class ObjectRefs {

    private ObjectRefs() {
    }

    static @com.legend.Nullable ValueSpecification build(
            ValueSpecification rhs, ModelContext ctx) {
        // a COLLECTION of generateObjectReferences calls folds element-
        // wise — each element yields a JSON array; concatenate them
        // (adjudication ledger cluster 5: the bracketed
        // [generateObjectReferences(...), generateObjectReferences(...)]
        // binding was declined whole, leaving the raw calls for the
        // typer, which has no such native)
        if (rhs instanceof PureCollection pc && !pc.values().isEmpty()) {
            StringBuilder all = new StringBuilder("[");
            for (int i = 0; i < pc.values().size(); i++) {
                if (!(build(pc.values().get(i), ctx) instanceof CString cs)) {
                    return null;
                }
                String body = cs.value();
                all.append(i > 0 ? "," : "")
                        .append(body, 1, body.length() - 1);
            }
            return new CString(all.append("]").toString());
        }
        if (!(rhs instanceof AppliedFunction af)) {
            return null;
        }
        String fn = EngineTestExecutor.simpleName(af.function());
        boolean forSet = fn.equals("generateObjectReferencesForGivenSetId");
        if (!forSet && !fn.equals("generateObjectReferences")) {
            return null;
        }
        List<ValueSpecification> a = af.parameters();
        int n = forSet ? 7 : 6;
        if (a.size() != n
                || !(a.get(1) instanceof CString mappingPath)
                || !(a.get(2) instanceof CString rootSetId)
                || (forSet && !(a.get(3) instanceof CString))) {
            return null;
        }
        String setId = forSet ? ((CString) a.get(3)).value()
                : rootSetId.value();
        // the pk segment carries the USER'S column-keyed map VERBATIM —
        // the objectReferenceIn decode matches keys against row columns
        // (the pk\$_N positional form stays the envelope's own spelling)
        List<Map<String, Object>> pkMaps = pkMaps(a.get(n - 2));
        if (pkMaps == null || pkMaps.isEmpty()) {
            return null;
        }
        StringBuilder arr = new StringBuilder("[");
        for (int i = 0; i < pkMaps.size(); i++) {
            String pkJson = pkJson(pkMaps.get(i));
            if (i > 0) {
                arr.append(",");
            }
            arr.append('"').append(com.legend.resolver.AsorRef.ref(
                    mappingPath.value(), rootSetId.value(), setId, pkJson))
                    .append('"');
        }
        return new CString(arr.append("]").toString());
    }

    /** The {@code decodeObjectReferencesAndGetPkMap} extraction idiom
     * (objectReference.pure): {@code parsed.values->map(je|
     * decode...(v, getValue('objectReference')...value->toOne(), ext))
     * ->joinStrings('[',',',']')} over the envelope — each reference
     * decodes to {@code {"pathToMapping":..,"pkMap":{col:v},"setId":..}}
     * with positional {@code pk\$_i} keys resolved to the set's TABLE pk
     * column names (the realizing fn's tableReference ->
     * findTableDefinition). Null = not this shape. */
    static @com.legend.Nullable ValueSpecification decodePkMaps(
            ValueSpecification rhs, ModelContext ctx,
            java.util.function.Function<ValueSpecification, Object> parsedEval) {
        if (!(rhs instanceof AppliedFunction js)
                || !EngineTestExecutor.simpleName(js.function()).equals("joinStrings")
                || js.parameters().size() != 4
                || !(js.parameters().get(0) instanceof AppliedFunction mapAf)
                || !EngineTestExecutor.simpleName(mapAf.function()).equals("map")
                || mapAf.parameters().size() != 2
                || !(mapAf.parameters().get(1) instanceof
                        com.legend.protocol.spec.LambdaFunction lf)
                || lf.body().size() != 1
                || !(lf.body().get(0) instanceof AppliedFunction dec)
                || !EngineTestExecutor.simpleName(dec.function())
                        .equals("decodeObjectReferencesAndGetPkMap")) {
            return null;
        }
        ValueSpecification src = stripJson(mapAf.parameters().get(0));
        if (src instanceof com.legend.protocol.spec.AppliedProperty ap
                && ap.property().equals("values")) {
            src = stripJson(ap.receiver());
        }
        Object parsed = parsedEval.apply(src);
        if (!(parsed instanceof List<?> l)) {
            return null;
        }
        StringBuilder out = new StringBuilder("[");
        boolean first = true;
        for (Object o : l) {
            if (!(o instanceof Map<?, ?> mm)
                    || !(mm.get("objectReference") instanceof String ref)) {
                return null;
            }
            String dec2 = decodeRef(ref, ctx);
            if (dec2 == null) {
                return null;
            }
            if (!first) {
                out.append(",");
            }
            first = false;
            out.append(dec2);
        }
        return new CString(out.append("]").toString());
    }

    /** parseJSON/cast/toOne wrappers strip (the extraction-plumbing
     * convention — the INNER exec read evaluates, wrappers are ours). */
    private static ValueSpecification stripJson(ValueSpecification v) {
        while (v instanceof AppliedFunction af) {
            String f = EngineTestExecutor.simpleName(af.function());
            if (af.parameters().size() == 1
                    && (f.equals("parseJSON") || f.equals("toOne")
                            || f.equals("toPrettyJSONString"))) {
                v = af.parameters().get(0);
            } else if (f.equals("cast") && af.parameters().size() == 2) {
                v = af.parameters().get(0);
            } else {
                return v;
            }
        }
        return v;
    }

    private static @com.legend.Nullable String decodeRef(String ref,
            ModelContext ctx) {
        // F3.4: decode via the ONE protocol owner
        com.legend.resolver.AsorRef.Ref r =
                com.legend.resolver.AsorRef.decode(ref);
        if (r == null) {
            return null;
        }
        String mapping = r.mapping();
        String setId = r.setId();
        Object pkObj = com.legend.sql.Json.parseOne(r.pkJson());
        if (!(pkObj instanceof Map<?, ?> pkMap)) {
            return null;
        }
        List<String> pkCols = tablePkColumns(ctx, mapping, setId);
        StringBuilder pk = new StringBuilder("{");
        boolean first = true;
        for (var e : pkMap.entrySet()) {
            String k = String.valueOf(e.getKey());
            if (k.startsWith("pk$_")) {
                int idx = Integer.parseInt(k.substring(4));
                if (pkCols == null || idx >= pkCols.size()) {
                    return null;
                }
                k = pkCols.get(idx);
            }
            if (!first) {
                pk.append(",");
            }
            first = false;
            pk.append("\"").append(k).append("\":");
            pk.append(e.getValue() instanceof String sv
                    ? "\"" + sv.replace("\"", "\\\"") + "\""
                    : e.getValue());
        }
        return "{\"pathToMapping\":\"" + mapping + "\",\"pkMap\":"
                + pk.append("}") + ",\"setId\":\"" + setId + "\"}";
    }

    /** The set's table pk columns: binding -> realizing body ->
     * tableReference(db, table) -> table definition. */
    private static @com.legend.Nullable List<String> tablePkColumns(
            ModelContext ctx, String mappingFqn, String setId) {
        var m = ctx.findMapping(mappingFqn).orElse(null);
        if (m == null) {
            return null;
        }
        for (var cb : m.classBindings()) {
            if (!setId.equals(cb.setId())
                    && !setId.equals(cb.classFqn().replace("::", "_"))) {
                continue;
            }
            List<ValueSpecification> body = null;
            if (cb.realization() instanceof com.legend.protocol
                    .Realization.Inline in) {
                body = in.body();
            } else if (cb.realization() instanceof com.legend.protocol
                    .Realization.Ref rf) {
                var fns = ctx.findFunction(rf.functionFqn());
                if (!fns.isEmpty() && fns.get(0).body().isPresent()) {
                    body = fns.get(0).body().get();
                }
            }
            if (body == null) {
                return null;
            }
            String[] dbTable = findTableRef(body);
            if (dbTable == null) {
                return null;
            }
            var td = ctx.findTableDefinition(dbTable[0], dbTable[1])
                    .orElse(null);
            if (td == null) {
                return null;
            }
            List<String> out = new ArrayList<>();
            for (var cd : td.columns()) {
                if (cd.primaryKey()) {
                    out.add(cd.name());
                }
            }
            return out.isEmpty() ? null : out;
        }
        for (var inc : m.includes()) {
            var r = tablePkColumns(ctx, inc.mappingPath(), setId);
            if (r != null) {
                return r;
            }
        }
        return null;
    }

    private static String @com.legend.Nullable [] findTableRef(
            List<ValueSpecification> body) {
        java.util.ArrayDeque<ValueSpecification> work =
                new java.util.ArrayDeque<>(body);
        while (!work.isEmpty()) {
            ValueSpecification v = work.poll();
            if (v instanceof AppliedFunction tr
                    && tr.function().equals("tableReference")
                    && tr.parameters().size() >= 2
                    && tr.parameters().get(0) instanceof
                            com.legend.protocol.spec.PackageableElementPtr db
                    && tr.parameters().get(1) instanceof CString tb) {
                return new String[] {db.fullPath(), tb.value()};
            }
            work.addAll(v.children());
        }
        return null;
    }

    /** The user's map as JSON, entry order preserved. */
    private static String pkJson(Map<String, Object> pkMap) {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (var e : pkMap.entrySet()) {
            if (!first) {
                sb.append(",");
            }
            first = false;
            sb.append("\"").append(e.getKey()).append("\":");
            sb.append(e.getValue() instanceof String sv
                    ? "\"" + sv.replace("\"", "\\\"") + "\""
                    : e.getValue());
        }
        return sb.append("}").toString();
    }

    /** The literal pk maps: a {@code pair(k,v)->newMap()} (or a
     * collection of pairs under one newMap) per map; a collection of
     * newMap calls = several maps. */
    private static @com.legend.Nullable List<Map<String, Object>> pkMaps(
            ValueSpecification v) {
        List<ValueSpecification> maps = v instanceof PureCollection pc
                ? pc.values() : List.of(v);
        List<Map<String, Object>> out = new ArrayList<>();
        for (ValueSpecification mv : maps) {
            if (!(mv instanceof AppliedFunction nm)
                    || !EngineTestExecutor.simpleName(nm.function()).equals("newMap")) {
                return null;
            }
            ValueSpecification pairs = nm.parameters().get(0);
            List<ValueSpecification> ps = pairs instanceof PureCollection pp
                    ? pp.values() : List.of(pairs);
            Map<String, Object> m = new LinkedHashMap<>();
            for (ValueSpecification pv : ps) {
                if (!(pv instanceof AppliedFunction pr)
                        || !EngineTestExecutor.simpleName(pr.function()).equals("pair")
                        || pr.parameters().size() != 2
                        || !(pr.parameters().get(0) instanceof CString k)) {
                    return null;
                }
                ValueSpecification val = pr.parameters().get(1);
                if (val instanceof CString cs) {
                    m.put(k.value(), cs.value());
                } else if (val instanceof CInteger ci) {
                    m.put(k.value(), ci.value());
                } else {
                    return null;
                }
            }
            out.add(m);
        }
        return out;
    }

}
