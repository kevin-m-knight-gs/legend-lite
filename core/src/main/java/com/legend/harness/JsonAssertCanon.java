// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend.harness;

import com.legend.model.spec.AppliedFunction;
import com.legend.model.spec.AppliedProperty;
import com.legend.model.spec.CString;
import com.legend.model.spec.LambdaFunction;
import com.legend.model.spec.NewInstance;
import com.legend.model.spec.ValueSpecification;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The JSON-array SORT canonicalization idiom in graphFetch goldens:
 *
 * <pre>^JSONArray(values = $resultJson.values-&gt;sortBy(
 *     v | $v-&gt;cast(@JSONObject)-&gt;getValue('Id')-&gt;toOne()))</pre>
 *
 * wrapped in {@code toPrettyJSONString()} for an order-insensitive
 * {@code assertJsonStringsEqual} over array elements. The harness owns
 * assert evaluation (the JSON metamodel never executes through the SQL
 * pipeline): the idiom is recognized structurally, the INNER result
 * expression compares as usual, and the parsed array sorts host-side by
 * the named key.
 */
final class JsonAssertCanon {

    private JsonAssertCanon() {
    }

    /** The rebuilt-array sort: inner expression + sort key. */
    record SortSpec(ValueSpecification inner, String key) {
    }

    /** Recognize {@code ^JSONArray(values = <inner>.values->sortBy(v |
     * ... getValue('K') ...))} (canonicalization wrappers stripped from
     * both the node and the inner expression); null when not this shape. */
    static @com.legend.Nullable SortSpec sortCanon(ValueSpecification v) {
        ValueSpecification n = stripWrappers(v);
        // ^X(...) parses as new(ptr, NewInstance)
        if (n instanceof AppliedFunction nf && "new".equals(nf.function())
                && nf.parameters().size() == 2
                && nf.parameters().get(1) instanceof NewInstance inner) {
            n = inner;
        }
        if (!(n instanceof NewInstance ni)
                || !simpleOf(ni.className()).equals("JSONArray")
                || ni.properties().size() != 1
                || ni.properties().get("values") == null) {
            return null;
        }
        ValueSpecification values = ni.properties().get("values").value();
        if (!(values instanceof AppliedFunction sb)
                || !TestBody.simpleName(sb.function()).equals("sortBy")
                || sb.parameters().size() != 2
                || !(stripWrappers(sb.parameters().get(0))
                        instanceof AppliedProperty ap)
                || !ap.property().equals("values")
                || !(sb.parameters().get(1) instanceof LambdaFunction lf)) {
            return null;
        }
        String key = getValueKey(lf.body());
        if (key == null) {
            return null;
        }
        return new SortSpec(stripWrappers(ap.receiver()), key);
    }

    /** The parsed structure with LIST-of-OBJECT elements sorted by
     * {@code key} (stable; missing keys sort first). Non-list values
     * pass through unchanged. */
    static Object sortByKey(Object parsed, String key) {
        if (!(parsed instanceof List<?> l)) {
            return parsed;
        }
        List<Object> out = new ArrayList<>(l);
        out.sort(java.util.Comparator.comparing(
                e -> e instanceof Map<?, ?> m
                        && m.get(key) instanceof Comparable<?> c
                        ? String.valueOf(c) : "",
                java.util.Comparator.naturalOrder()));
        return out;
    }

    /** JSON-METAMODEL PLUMBING: a let RHS that only canonicalizes an
     * exec result for a JSON assert — a parseJSON wrapper chain ending
     * at a variable, or the rebuilt-JSONArray sort. Deferred to the
     * assert; the JSON metamodel never executes through the SQL
     * pipeline. A plain exec read (no parseJSON) is NOT plumbing — the
     * eager execute-forward stays (audit 16 F1). */
    static boolean isPlumbing(ValueSpecification v) {
        if (sortCanon(v) != null) {
            return true;
        }
        boolean sawParse = false;
        while (v instanceof AppliedFunction af) {
            String f = TestBody.simpleName(af.function());
            if (af.parameters().size() == 1
                    && (f.equals("parseJSON") || f.equals("toPrettyJSONString")
                            || f.equals("toOne"))) {
                sawParse |= f.equals("parseJSON");
                v = af.parameters().get(0);
            } else if (f.equals("cast") && af.parameters().size() == 2) {
                v = af.parameters().get(0);
            } else {
                return false;
            }
        }
        return sawParse && (v instanceof com.legend.model.spec.Variable
                || v instanceof AppliedProperty);
    }

    /** JSON reference-EXTRACTION plumbing over an exec result:
     * {@code parseJSON()->cast(@JSONArray).values->cast(@JSONObject)
     * ->map(x|$x->getValue('k')->cast(@JSONString).value)} — evaluated
     * HOST-side to a literal string collection at the LET (downstream
     * assert args AND query-lambda substitution both need the values).
     * {@code parsedEval} evaluates an expression and returns its parsed
     * JSON (null = not evaluable). Null when not this shape. */
    static @com.legend.Nullable ValueSpecification extractStrings(
            ValueSpecification rhs,
            java.util.function.Function<ValueSpecification,
                    Object> parsedEval) {
        if (!(rhs instanceof AppliedFunction mapAf)
                || !TestBody.simpleName(mapAf.function()).equals("map")
                || mapAf.parameters().size() != 2
                || !(mapAf.parameters().get(1)
                        instanceof LambdaFunction lf)
                || lf.body().size() != 1
                || lf.parameters().size() != 1) {
            return null;
        }
        ValueSpecification src = stripWrappers(mapAf.parameters().get(0));
        if (!(src instanceof AppliedProperty ap)
                || !ap.property().equals("values")) {
            return null;
        }
        // the lambda: .value over getValue($x, 'key') (wrappers stripped)
        ValueSpecification b = lf.body().get(0);
        if (!(b instanceof AppliedProperty vp)
                || !vp.property().equals("value")) {
            return null;
        }
        ValueSpecification g = stripWrappers(vp.receiver());
        if (!(g instanceof AppliedFunction gv)
                || !TestBody.simpleName(gv.function()).equals("getValue")
                || gv.parameters().size() != 2
                || !(gv.parameters().get(1) instanceof CString key)) {
            return null;
        }
        Object parsed = parsedEval.apply(stripWrappers(ap.receiver()));
        if (!(parsed instanceof List<?> l)) {
            return null;
        }
        List<ValueSpecification> out = new ArrayList<>(l.size());
        for (Object o : l) {
            if (o instanceof Map<?, ?> mm
                    && mm.get(key.value()) instanceof String sv) {
                out.add(new CString(sv));
            }
        }
        return new com.legend.model.spec.PureCollection(out);
    }

    /** Identity wrappers around a JSON value expression: parseJSON /
     * toPrettyJSONString / toOne (1-arg) and cast (value, type). */
    private static ValueSpecification stripWrappers(ValueSpecification v) {
        while (v instanceof AppliedFunction af) {
            String f = TestBody.simpleName(af.function());
            if (af.parameters().size() == 1
                    && (f.equals("parseJSON") || f.equals("toPrettyJSONString")
                            || f.equals("toOne"))) {
                v = af.parameters().get(0);
            } else if (f.equals("cast") && af.parameters().size() == 2) {
                v = af.parameters().get(0);
            } else {
                return v;
            }
        }
        return v;
    }

    private static @com.legend.Nullable String getValueKey(
            List<ValueSpecification> body) {
        java.util.ArrayDeque<ValueSpecification> work =
                new java.util.ArrayDeque<>(body);
        while (!work.isEmpty()) {
            ValueSpecification x = work.poll();
            if (x instanceof AppliedFunction af
                    && TestBody.simpleName(af.function()).equals("getValue")
                    && af.parameters().size() == 2
                    && af.parameters().get(1) instanceof CString k) {
                return k.value();
            }
            work.addAll(x.children());
        }
        return null;
    }

    private static String simpleOf(String className) {
        int cut = className.lastIndexOf("::");
        return cut < 0 ? className : className.substring(cut + 2);
    }
}
