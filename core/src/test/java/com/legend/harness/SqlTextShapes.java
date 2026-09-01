// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend.harness;

import com.legend.compiler.element.ModelContext;
import com.legend.protocol.spec.AppliedFunction;
import com.legend.protocol.spec.LambdaFunction;
import com.legend.protocol.spec.ValueSpecification;

import java.util.List;
import java.util.TreeMap;

/**
 * SQLTEXT charter §8 slice 3, step 0 (measure-first, §10): the SHAPE
 * CENSUS of the text-policy fallback population. Every test the flip
 * gate routes to {@code fallback("text-policy")} classifies its assert
 * statements by the DERIVATION the verdict arms would need:
 *
 * <ul>
 *   <li>{@code tosqlstring-simple} — golden literal + a
 *       toSQLString/toSQLStringPretty producer whose query lambda is
 *       reachable (inline or let-chased): the §4 dual-derivation
 *       target — OUR TEXT from the routine, OUR ROWS from the lambda,
 *       GOLDEN ROWS from the oracle SPI.</li>
 *   <li>{@code tosqlstring-nogolden} — producer present, no foldable
 *       golden literal (both-ours compares, computed goldens).</li>
 *   <li>{@code assert-form} — the assertSameSQL family (golden vs an
 *       EXECUTED result's sql text; our side already executed).</li>
 *   <li>{@code exec-sql-read} — sql()/sqlRemoveFormatting() reads over
 *       an execute() result (the run-backed lane).</li>
 *   <li>{@code tdg-sqls} — generateTestData .sqls reads (TDG lane,
 *       already refereed).</li>
 *   <li>{@code plain} — no sql involvement (the platform judges these
 *       today).</li>
 *   <li>{@code other-producer} — anything else (named residue).</li>
 * </ul>
 *
 * The dump ({@code target/sqltext-shape-census.txt}) is the flip
 * population ledger: a test whose sql asserts are ALL
 * tosqlstring-simple is slice 3's first flip cohort.
 */
public final class SqlTextShapes {

    private SqlTextShapes() {
    }

    /** shape-combination -> count (runner-dumped histogram). */
    public static final java.util.concurrent.ConcurrentHashMap<String,
            java.util.concurrent.atomic.LongAdder> CENSUS =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** per-test line: "combination test" (runner-dumped roster). */
    public static final java.util.concurrent.ConcurrentLinkedQueue<String>
            ROSTER = new java.util.concurrent.ConcurrentLinkedQueue<>();

    private static final java.util.Set<String> TO_SQL_STRING_FQNS =
            java.util.Set.of(
                    com.legend.compiler.element.type.PlatformTypes
                            .TO_SQL_STRING,
                    com.legend.compiler.element.type.PlatformTypes
                            .TO_SQL_STRING_PRETTY);

    /** Slice-3a admission: TRUE when every sql-involved assert in the
     * body is the tosqlstring-simple shape (and one exists) — the
     * platform's SqlTextVerdicts arm owns exactly that cohort, so the
     * flip gate lets the test through instead of falling back. */
    public static boolean allSimple(List<ValueSpecification> statements,
            ModelContext ctx) {
        TreeMap<String, Integer> shapes = classify(statements, ctx);
        return shapes.containsKey("tosqlstring-simple")
                && shapes.keySet().stream().allMatch(k ->
                        k.equals("tosqlstring-simple") || k.equals("plain"));
    }

    /** Classify one text-policy test body and record it. */
    public static void record(String test,
            List<ValueSpecification> statements, ModelContext ctx) {
        TreeMap<String, Integer> shapes = classify(statements, ctx);
        StringBuilder combo = new StringBuilder();
        for (var e : shapes.entrySet()) {
            if (combo.length() > 0) {
                combo.append('+');
            }
            combo.append(e.getKey()).append('x').append(e.getValue());
        }
        String key = combo.length() == 0 ? "no-asserts" : combo.toString();
        CENSUS.computeIfAbsent(key,
                k -> new java.util.concurrent.atomic.LongAdder()).increment();
        ROSTER.add(key + " " + test);
    }

    private static TreeMap<String, Integer> classify(
            List<ValueSpecification> statements, ModelContext ctx) {
        java.util.Map<String, ValueSpecification> lets =
                new java.util.LinkedHashMap<>();
        TreeMap<String, Integer> shapes = new TreeMap<>();
        for (ValueSpecification s : statements) {
            if (s instanceof AppliedFunction let
                    && let.function().equals("letFunction")
                    && let.parameters().size() == 2
                    && let.parameters().get(0)
                            instanceof com.legend.protocol.spec.CString ln) {
                lets.put(ln.value(), let.parameters().get(1));
                continue;
            }
            if (!(s instanceof AppliedFunction af)
                    || !EngineTestExecutor.resolvesTo(af, ctx,
                            EngineTestExecutor.ASSERT_FORM_FQNS)) {
                continue;
            }
            shapes.merge(assertShape(af, lets, statements, ctx), 1,
                    Integer::sum);
        }
        return shapes;
    }

    private static String assertShape(AppliedFunction af,
            java.util.Map<String, ValueSpecification> lets,
            List<ValueSpecification> statements, ModelContext ctx) {
        if (EngineTestExecutor.resolvesTo(af, ctx,
                EngineTestExecutor.SQL_ASSERT_FORM_FQNS)) {
            return "assert-form";
        }
        boolean sql = false;
        for (ValueSpecification p : af.parameters()) {
            if (EngineTestExecutor.containsSqlProducer(p, ctx)
                    || referencesTaintedLet(p, lets, ctx)) {
                sql = true;
                break;
            }
        }
        if (!sql) {
            return "plain";
        }
        // which PRODUCER feeds this assert? Chase the actual side
        // through lets (the ExecCallFinder walk, toSQLString stops).
        boolean golden = false;
        ValueSpecification actual = null;
        for (ValueSpecification p : af.parameters()) {
            if (TestDataGenForm.foldString(
                    EngineTestExecutor.substitute(p, lets)) != null) {
                golden = true;
            } else {
                actual = p;
            }
        }
        AppliedFunction producer = ExecCallFinder.findTerminal(actual, lets,
                statements, TO_SQL_STRING_FQNS);
        if (producer != null) {
            boolean lambdaArg = !producer.parameters().isEmpty()
                    && producer.parameters().get(0) instanceof LambdaFunction;
            if (lambdaArg && golden) {
                return "tosqlstring-simple";
            }
            return lambdaArg ? "tosqlstring-nogolden"
                    : "tosqlstring-nonlambda";
        }
        if (containsFqn(actual, lets, ctx,
                com.legend.compiler.element.type.PlatformTypes
                        .GENERATE_TEST_DATA)) {
            return "tdg-sqls";
        }
        if (containsFqn(actual, lets, ctx,
                com.legend.compiler.spec.ResultEnvelopeSplice.SQL_FQN)
                || containsFqn(actual, lets, ctx,
                        com.legend.compiler.spec.ResultEnvelopeSplice
                                .SQL_REMOVE_FORMATTING_FQN)) {
            return "exec-sql-read";
        }
        return "other-producer";
    }

    private static boolean referencesTaintedLet(ValueSpecification v,
            java.util.Map<String, ValueSpecification> lets,
            ModelContext ctx) {
        for (var e : lets.entrySet()) {
            if (EngineTestExecutor.referencesAny(v,
                    java.util.Set.of(e.getKey()))
                    && EngineTestExecutor.containsSqlProducer(e.getValue(),
                            ctx)) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsFqn(
            @com.legend.Nullable ValueSpecification v,
            java.util.Map<String, ValueSpecification> lets,
            ModelContext ctx, String fqn) {
        if (v == null) {
            return false;
        }
        ValueSpecification s = EngineTestExecutor.substitute(v, lets);
        if (s instanceof AppliedFunction af) {
            if (EngineTestExecutor.resolvesTo(af, ctx,
                    java.util.Set.of(fqn))) {
                return true;
            }
            for (ValueSpecification p : af.parameters()) {
                if (containsFqn(p, lets, ctx, fqn)) {
                    return true;
                }
            }
        }
        if (s instanceof com.legend.protocol.spec.AppliedProperty ap) {
            return containsFqn(ap.receiver(), lets, ctx, fqn);
        }
        return false;
    }
}
