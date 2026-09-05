// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0
package com.legend.harness;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * THE ASSERT LEDGER (user design 2026-09-04): for every test that is not a
 * clean platform pass, one row PER ASSERT — pass, or the truthful bucket
 * that names why the platform could not verify it — plus one row for the
 * asserts the attempt never reached. A clean test counts at the test
 * level (no rows here). Buckets are facts about the assert, never a
 * euphemism for failure:
 * <ul>
 *   <li>{@code pass} — verified on rows or values;</li>
 *   <li>{@code zero-assert} — the test has no verdict statement;</li>
 *   <li>{@code sql-text-assert} — the assert's subject is SQL TEXT
 *       (a {@code contains}/equality on the emitted text — the engine's
 *       spelling, ours judged as text, not rows);</li>
 *   <li>{@code referee-cannot-replay} — golden SQL the referee cannot
 *       execute (temp tables, executeInDb setups);</li>
 *   <li>{@code decision:<name>} — the standing decisions (chained fetch,
 *       objectReferenceIn, routeFunction, protocol transforms,
 *       recursion, dynamic compilation);</li>
 *   <li>{@code wall:<owner>} — a platform gap with its owner;</li>
 *   <li>{@code divergence} — rows produced and wrong;</li>
 *   <li>{@code not-reached} — asserts after the first failure.</li>
 * </ul>
 */
public final class AssertLedger {

    public record Row(String test, int ordinal, String form, String outcome, String detail) {
    }

    private static final Map<String, List<Row>> ROWS =
            java.util.Collections.synchronizedMap(new LinkedHashMap<>());

    private AssertLedger() {
    }

    public static void record(String test, List<Row> rows) {
        ROWS.put(test, List.copyOf(rows));
    }

    public static Map<String, List<Row>> rows() {
        synchronized (ROWS) {
            return new LinkedHashMap<>(ROWS);
        }
    }

    public static void reset() {
        ROWS.clear();
    }

    /** The bucket of a whole-test fallback reason (the flip's reason text). */
    public static String bucketOf(String reason, boolean subjectIsSqlText) {
        String r = String.valueOf(reason);
        if (r.startsWith("assert-free")) {
            return "zero-assert";
        }
        if (r.contains("generateObjectReferences")) {
            return "decision:objectReferenceIn";
        }
        if (r.contains("routeFunction")) {
            return "decision:routeFunction";
        }
        if (r.contains("compileLegendGrammar") || r.contains("getNoArgFlattenMapping")) {
            return "decision:dynamic-compilation";
        }
        if (r.contains("transformPlan") || r.contains("PureModelContextData")) {
            return "decision:protocol-transform";
        }
        if (r.contains("convertSemiStructuredArrayFlatten") || r.contains("recursive")) {
            return "decision:recursion";
        }
        if (r.contains("chained fetch")) {
            return "decision:tdg-chained-fetch";
        }
        if (r.contains("population statement of a chained plan")) {
            // the engine's two-statement in-list plan asserted by index:
            // golden(0) is its population statement — a plan-structure
            // contract with no counterpart in our one-statement plan
            return "decision:plan-structure";
        }
        if (r.contains("oracle declined") || r.contains("rows underivable")
                || r.contains("declined:")) {
            return "referee-cannot-replay";
        }
        if (r.startsWith("platform-fail")) {
            return subjectIsSqlText ? "sql-text-assert" : "divergence";
        }
        if (r.startsWith("wall-type")) {
            return "wall:typer";
        }
        if (r.startsWith("wall-resolve")) {
            return "wall:resolver";
        }
        if (r.startsWith("wall-exec")) {
            String m = r.toLowerCase(java.util.Locale.ROOT);
            if (m.contains("lowering") || m.contains("no sql type") || m.contains("dialect")) {
                return "wall:lowering";
            }
            if (m.contains("resolvable") || m.contains("substitutable") || m.contains("navigation")
                    || m.contains("store resolution") || m.contains("mappingresolution")) {
                return "wall:resolver";
            }
            if (m.contains("typeinferenceexception") || m.contains("unknown function")) {
                return "wall:typer";
            }
            return "wall:exec";
        }
        return "wall:" + r.replaceAll(":.*", "");
    }

    /** The ledger text: totals by bucket, then one block per test. */
    public static String render() {
        Map<String, Integer> totals = new LinkedHashMap<>();
        StringBuilder rows = new StringBuilder();
        List<String> tests = new ArrayList<>(rows().keySet());
        for (String t : tests) {
            List<Row> rs = rows().get(t);
            for (Row r : rs) {
                totals.merge(r.outcome(), 1, Integer::sum);
                rows.append("- ").append(r.test().substring(r.test().lastIndexOf("::") + 2))
                        .append(" #").append(r.ordinal()).append(' ').append(r.form())
                        .append(" -> ").append(r.outcome());
                if (!r.detail().isEmpty()) {
                    rows.append(": ").append(r.detail().replace("\n", "\\n"));
                }
                rows.append('\n');
            }
        }
        StringBuilder sb = new StringBuilder();
        sb.append("\n### assert ledger (partial and failing tests; clean tests count at the test level)\n\n");
        sb.append("tests in the ledger: ").append(tests.size()).append("\n\n");
        sb.append("| bucket | asserts |\n|---|---|\n");
        for (var e : totals.entrySet()) {
            sb.append("| ").append(e.getKey()).append(" | ").append(e.getValue()).append(" |\n");
        }
        sb.append("\n").append(rows);
        return sb.toString();
    }
}
