// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend.harness;

import com.legend.compiler.element.ModelContext;
import com.legend.model.spec.AppliedFunction;
import com.legend.model.spec.CInteger;
import com.legend.model.spec.CString;
import com.legend.model.spec.PureCollection;
import com.legend.model.spec.ValueSpecification;

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
        if (!(rhs instanceof AppliedFunction af)) {
            return null;
        }
        String fn = TestBody.simpleName(af.function());
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
            String ref = prefix(mappingPath.value(), rootSetId.value(),
                    setId)
                    + String.format("%010d", pkJson.length()) + ":" + pkJson;
            arr.append("\"ASOR:").append(java.util.Base64.getEncoder()
                    .withoutPadding().encodeToString(ref.getBytes(
                            java.nio.charset.StandardCharsets.UTF_8)))
                    .append("\"");
        }
        return new CString(arr.append("]").toString());
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
                    || !TestBody.simpleName(nm.function()).equals("newMap")) {
                return null;
            }
            ValueSpecification pairs = nm.parameters().get(0);
            List<ValueSpecification> ps = pairs instanceof PureCollection pp
                    ? pp.values() : List.of(pairs);
            Map<String, Object> m = new LinkedHashMap<>();
            for (ValueSpecification pv : ps) {
                if (!(pv instanceof AppliedFunction pr)
                        || !TestBody.simpleName(pr.function()).equals("pair")
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

    /** The ASOR static prefix (GraphEmission.asorPrefix protocol; the
     * DECODE side reads only the trailing pk segment, the rest keeps
     * the well-formed engine shape). */
    private static String prefix(String mappingPath, String rootSetId,
            String setId) {
        String conn = "{\"_type\":\"RelationalDatabaseConnection\","
                + "\"authenticationStrategy\":{\"_type\":\"h2Default\"},"
                + "\"datasourceSpecification\":{\"_type\":\"h2Local\"},"
                + "\"element\":\"\",\"postProcessorWithParameter\":[],"
                + "\"postProcessors\":[],\"timeZone\":\"GMT\","
                + "\"type\":\"H2\"}";
        return "001:010:" + seg("Relational") + seg(mappingPath)
                + seg(rootSetId) + seg(setId) + seg(conn);
    }

    private static String seg(String v) {
        return String.format("%010d", v.length()) + ":" + v + ":";
    }
}
