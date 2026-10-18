// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend.generators;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * The reference differential's JOIN, call by call (a port of {@code tools/reference/join.py}, plan W1.1; the script
 * was deleted 2026-10-18): the key is (source id, line, column) of the call-name token, which both dumps print. At the
 * pinned release the two sides read the same source text (the H3 spike: SOURCE_DRIFT gone), so there is no drift file
 * and no elision list.
 *
 * <p>Buckets: AGREE (same declaration id); OVERLOAD (same declaration name, other overload); PACKAGE (other
 * declaration); DRIFT (the reference's declaration is absent on our side and ours on its side); ABSENT (no call of ours
 * at a reference call's position: a form node, a property read, a rewrite); PROPERTY_AS_CALL (a member read on the
 * reference's side, a call on ours); EXTRA (a call of ours the reference has none for). Only calls inside functions
 * both sides typed, and that we typed without failure, are compared.
 *
 * <p>Two spellings do not join by column alone. An INFIX OPERATOR RUN is positioned at its last operator token by the
 * reference and by the engine grammar's span convention by us: an operator row joins by exact column when the same
 * operator sits there, else with the next unmatched row of the same operator on the same line, in column order. A
 * PROPERTY READ is a member on the reference's side (spelling {@code null}).
 */
final class ReferenceJoin {

    static final Set<String> OPERATORS = Set.of("plus", "minus", "times", "divide", "equal", "and", "or", "not",
            "lessThan", "lessThanEqual", "greaterThan", "greaterThanEqual");

    static final List<String> KINDS = List.of("AGREE", "OVERLOAD", "PACKAGE", "DRIFT", "ABSENT", "PROPERTY_AS_CALL",
            "EXTRA");

    /** One disagreement class: the kind, the reference's spelling (or our callee's simple name for EXTRA and
     * PROPERTY_AS_CALL), and for OVERLOAD/PACKAGE the two declaration ids. */
    record Key(String kind, String spelling, String refId, String ourId) {
    }

    /** The join's outcome. {@code examples} carries one position per disagreement class, for the report only. */
    record Result(int refFunctions, int ourFunctions, int ourFailed, int inBoth, int refOnly, int oursOnly,
                  int refTypedWeFailed, Map<String, Integer> buckets, Map<Key, Integer> classes,
                  Map<Key, String> examples) {
    }

    private record RefCall(String spelling, String fqn, String id, String enclosing) {
    }

    private record OurCall(String fqn, String id, String enclosing) {
    }

    private ReferenceJoin() {
    }

    private static String simple(String fqn) {
        int i = fqn.lastIndexOf("::");
        return i < 0 ? fqn : fqn.substring(i + 2);
    }

    private static List<Map<String, String>> rows(BufferedReader r) throws IOException {
        String[] hdr = r.readLine().split("\t", -1);
        List<Map<String, String>> out = new ArrayList<>();
        for (String line = r.readLine(); line != null; line = r.readLine()) {
            String[] c = line.split("\t", -1);
            Map<String, String> m = new HashMap<>();
            for (int i = 0; i < hdr.length; i++) {
                m.put(hdr[i], i < c.length ? c[i] : "");
            }
            out.add(m);
        }
        return out;
    }

    static Result join(Path reference, String ours) throws IOException {
        List<Map<String, String>> refRows;
        try (BufferedReader r = Files.newBufferedReader(reference, StandardCharsets.UTF_8)) {
            refRows = rows(r);
        }
        List<Map<String, String>> ourRows = rows(new BufferedReader(new StringReader(ours)));

        Map<List<String>, RefCall> refCalls = new LinkedHashMap<>();
        Set<List<String>> refProps = new HashSet<>();
        Set<String> refFns = new HashSet<>();
        Set<String> refDecls = new HashSet<>();
        for (Map<String, String> r : refRows) {
            refFns.add(r.get("enclosingFqn"));
            List<String> key = List.of(r.get("sourceId"), r.get("line"), r.get("column"));
            if (r.get("spelling").equals("null")) {
                refProps.add(key);
                continue;
            }
            if (r.get("resolvedId").isEmpty()) {
                continue;
            }
            refDecls.add(r.get("resolvedId"));
            refCalls.put(key, new RefCall(r.get("spelling"), r.get("resolvedFqn"), r.get("resolvedId"),
                    r.get("enclosingFqn")));
        }

        Map<List<String>, OurCall> ourCalls = new LinkedHashMap<>();
        Map<List<String>, List<String>> ourOps = new HashMap<>();
        Set<String> ourFns = new HashSet<>();
        Set<String> ourFailed = new HashSet<>();
        Set<String> ourDecls = new HashSet<>();
        for (Map<String, String> r : ourRows) {
            ourFns.add(r.get("enclosingFqn"));
            if (r.get("kind").equals("FAILED")) {
                ourFailed.add(r.get("enclosingFqn"));
            } else if (r.get("kind").equals("CALL") && !r.get("line").isEmpty()) {
                ourDecls.add(r.get("resolvedId"));
                List<String> key = List.of(r.get("sourceId"), r.get("line"), r.get("column"));
                ourCalls.put(key, new OurCall(r.get("resolvedFqn"), r.get("resolvedId"), r.get("enclosingFqn")));
                String sp = simple(r.get("resolvedFqn"));
                if (OPERATORS.contains(sp)) {
                    ourOps.computeIfAbsent(List.of(r.get("sourceId"), r.get("line"), sp), k -> new ArrayList<>())
                            .add(r.get("column"));
                }
            }
        }
        for (List<String> cols : ourOps.values()) {
            cols.sort(Comparator.comparingInt(Integer::parseInt));
        }
        Set<String> both = new HashSet<>(refFns);
        both.retainAll(ourFns);

        Map<String, Integer> buckets = new LinkedHashMap<>();
        KINDS.forEach(k -> buckets.put(k, 0));
        Map<Key, Integer> classes = new TreeMap<>(Comparator.comparing(Key::kind).thenComparing(Key::spelling)
                .thenComparing(Key::refId).thenComparing(Key::ourId));
        Map<Key, String> examples = new HashMap<>();
        Set<List<String>> matched = new HashSet<>();

        java.util.function.BiConsumer<Key, List<String>> count = (k, pos) -> {
            classes.merge(k, 1, Integer::sum);
            examples.putIfAbsent(k, String.join(":", pos));
        };
        java.util.function.BiConsumer<List<String>, List<String>> compare = (refKey, partner) -> {
            matched.add(partner);
            RefCall rc = refCalls.get(refKey);
            OurCall oc = ourCalls.get(partner);
            if (oc.id().equals(rc.id())) {
                buckets.merge("AGREE", 1, Integer::sum);
            } else if (!ourDecls.contains(rc.id()) && !refDecls.contains(oc.id())) {
                buckets.merge("DRIFT", 1, Integer::sum);
                count.accept(new Key("DRIFT", rc.spelling(), rc.id(), oc.id()), refKey);
            } else {
                String kind = oc.fqn().equals(rc.fqn()) ? "OVERLOAD" : "PACKAGE";
                buckets.merge(kind, 1, Integer::sum);
                count.accept(new Key(kind, rc.spelling(), rc.id(), oc.id()), refKey);
            }
        };

        List<List<String>> pendingOps = new ArrayList<>();
        for (Map.Entry<List<String>, RefCall> e : refCalls.entrySet()) {
            List<String> key = e.getKey();
            RefCall rc = e.getValue();
            if (!both.contains(rc.enclosing()) || ourFailed.contains(rc.enclosing())) {
                continue;
            }
            OurCall at = ourCalls.get(key);
            if (at != null && !((OPERATORS.contains(simple(rc.spelling())) || OPERATORS.contains(simple(at.fqn())))
                    && !simple(at.fqn()).equals(simple(rc.spelling())))) {
                compare.accept(key, key);
            } else if (OPERATORS.contains(simple(rc.spelling()))) {
                pendingOps.add(key);
            } else {
                buckets.merge("ABSENT", 1, Integer::sum);
                count.accept(new Key("ABSENT", rc.spelling(), "", ""), key);
            }
        }
        for (List<String> key : pendingOps) {
            RefCall rc = refCalls.get(key);
            List<String> partner = null;
            for (String col : ourOps.getOrDefault(List.of(key.get(0), key.get(1), simple(rc.spelling())), List.of())) {
                List<String> cand = List.of(key.get(0), key.get(1), col);
                if (!matched.contains(cand)) {
                    partner = cand;
                    break;
                }
            }
            if (partner == null) {
                buckets.merge("ABSENT", 1, Integer::sum);
                count.accept(new Key("ABSENT", rc.spelling(), "", ""), key);
            } else {
                compare.accept(key, partner);
            }
        }
        for (Map.Entry<List<String>, OurCall> e : ourCalls.entrySet()) {
            List<String> key = e.getKey();
            OurCall oc = e.getValue();
            if (matched.contains(key) || !both.contains(oc.enclosing()) || ourFailed.contains(oc.enclosing())) {
                continue;
            }
            String kind = refProps.contains(key) ? "PROPERTY_AS_CALL" : "EXTRA";
            buckets.merge(kind, 1, Integer::sum);
            count.accept(new Key(kind, oc.fqn(), "", ""), key);
        }
        Set<String> refOnly = new HashSet<>(refFns);
        refOnly.removeAll(ourFns);
        Set<String> oursOnly = new HashSet<>(ourFns);
        oursOnly.removeAll(refFns);
        Set<String> refTypedWeFailed = new HashSet<>(refFns);
        refTypedWeFailed.retainAll(ourFailed);
        return new Result(refFns.size(), ourFns.size(), ourFailed.size(), both.size(), refOnly.size(),
                oursOnly.size(), refTypedWeFailed.size(), buckets, classes, examples);
    }
}
