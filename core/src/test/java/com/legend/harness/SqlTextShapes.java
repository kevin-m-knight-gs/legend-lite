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
        boolean anySql = shapes.containsKey("tosqlstring-simple")
                || shapes.containsKey("assertsamesql-simple")
                || shapes.containsKey("execsqlread-simple")
                || shapes.containsKey("h2compat-simple");
        return anySql && shapes.keySet().stream().allMatch(k ->
                k.equals("tosqlstring-simple")
                        || k.equals("assertsamesql-simple")
                        || k.equals("execsqlread-simple")
                        || k.equals("h2compat-simple")
                        || k.equals("plain"));
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
            // 3b split: the SIMPLE assertSameSQL(goldenLit, $execVar)
            // shape is the platform arm's cohort (SqlTextVerdicts
            // .tryArmSameSql — the root arm); H2Compatible,
            // assertSqlEquals and computed-golden spellings stay
            // fallback shapes for now
            if (EngineTestExecutor.resolvesTo(af, ctx, java.util.Set.of(
                    "meta::relational::functions::asserts::assertSameSQL"))
                    && af.parameters().size() == 2) {
                ValueSpecification g = af.parameters().get(0);
                ValueSpecification r = af.parameters().get(1);
                boolean goldenLit = TestDataGenForm.foldString(
                        EngineTestExecutor.substitute(g, lets)) != null;
                ValueSpecification rs =
                        EngineTestExecutor.substitute(r, lets);
                boolean frameArg = rs instanceof
                        com.legend.protocol.spec.Variable
                        || EngineTestExecutor.containsExecute(rs);
                if (goldenLit && frameArg) {
                    return "assertsamesql-simple";
                }
            }
            // §8.3d split: the dual-golden SIMPLE shape — 3 args, BOTH
            // goldens fold to literals, actual is an executed frame.
            // Computed-golden spellings stay assert-form (next chunk).
            if (EngineTestExecutor.resolvesTo(af, ctx, java.util.Set.of(
                    "meta::relational::functions::sqlQueryToString::h2"
                            + "::assertEqualsH2Compatible"))
                    && af.parameters().size() == 3) {
                boolean g0 = TestDataGenForm.foldString(EngineTestExecutor
                        .substitute(af.parameters().get(0), lets)) != null;
                boolean g1 = TestDataGenForm.foldString(EngineTestExecutor
                        .substitute(af.parameters().get(1), lets)) != null;
                ValueSpecification rs = EngineTestExecutor.substitute(
                        af.parameters().get(2), lets);
                boolean frameArg = rs instanceof
                        com.legend.protocol.spec.Variable
                        || EngineTestExecutor.containsExecute(rs);
                if (g0 && g1 && frameArg) {
                    return "h2compat-simple";
                }
            }
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
            // §8.3c split: the SIMPLE exec-sql-read shape is the
            // platform arm's cohort (SqlTextVerdicts.tryArmExecRead) —
            // the admission MIRRORS the arm's preconditions exactly so
            // nothing flips that the arm will not own: 2-arg
            // assertEquals, foldable golden, and the read call in its
            // FIRST-STATEMENT form (1 argument — sql($res, n>0) names
            // the n-th activity and stays a fallback shape) over an
            // executed frame (Variable-or-containsExecute, the
            // assertsamesql-simple precedent).
            if (golden && af.parameters().size() == 2
                    && EngineTestExecutor.resolvesTo(af, ctx,
                            java.util.Set.of(
                            "meta::pure::functions::asserts::assertEquals"))
                    && simpleReadCall(actual, lets, ctx)) {
                return "execsqlread-simple";
            }
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

    /** The exec-sql-read call in its ARM-OWNED form: a
     * {@code sql}/{@code sqlRemoveFormatting} call (exact splice FQNs)
     * with EXACTLY one parameter that is an executed frame
     * (Variable-or-containsExecute). Walks the substituted tree. */
    private static boolean simpleReadCall(
            @com.legend.Nullable ValueSpecification v,
            java.util.Map<String, ValueSpecification> lets,
            ModelContext ctx) {
        if (v == null) {
            return false;
        }
        ValueSpecification s = EngineTestExecutor.substitute(v, lets);
        if (s instanceof AppliedFunction af) {
            if (EngineTestExecutor.resolvesTo(af, ctx, java.util.Set.of(
                    com.legend.compiler.spec.ResultEnvelopeSplice.SQL_FQN,
                    com.legend.compiler.spec.ResultEnvelopeSplice
                            .SQL_REMOVE_FORMATTING_FQN))) {
                if (af.parameters().size() != 1) {
                    return false;
                }
                ValueSpecification arg = EngineTestExecutor.substitute(
                        af.parameters().get(0), lets);
                return arg instanceof com.legend.protocol.spec.Variable
                        || EngineTestExecutor.containsExecute(arg);
            }
            for (ValueSpecification p : af.parameters()) {
                if (simpleReadCall(p, lets, ctx)) {
                    return true;
                }
            }
        }
        if (s instanceof com.legend.protocol.spec.AppliedProperty ap) {
            return simpleReadCall(ap.receiver(), lets, ctx);
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
