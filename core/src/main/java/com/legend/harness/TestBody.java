// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend.harness;

import com.legend.Compiler;

import com.legend.compiler.NameResolver;
import com.legend.compiler.element.ModelContext;
import com.legend.protocol.spec.KeyExpression;
import com.legend.model.ImportScope;
import com.legend.parser.SpecParser;
import com.legend.protocol.spec.AppliedFunction;
import com.legend.protocol.spec.AppliedProperty;
import com.legend.protocol.spec.CBoolean;
import com.legend.protocol.spec.CString;
import com.legend.protocol.spec.LambdaFunction;
import com.legend.protocol.spec.NewInstance;
import com.legend.protocol.spec.PureCollection;
import com.legend.protocol.spec.ValueSpecification;
import com.legend.protocol.spec.Variable;

import java.sql.Connection;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import static java.util.Objects.requireNonNull;
import java.util.Map;

/**
 * NATIVE test-body execution &mdash; runs a real pure {@code <<test.Test>>}
 * function body (a STATEMENT SEQUENCE of lets, {@code execute(...)} calls
 * and {@code assert*} calls) through the ordinary compile-to-SQL pipeline.
 *
 * <p><strong>No interpreter</strong> (tenet #1): {@code let r =
 * execute(|Q, ...)} binds a lazy handle + execution context; downstream
 * reads SPLICE into ONE SQL statement; {@code assert*} natives are the
 * orchestration boundary — both sides execute through the pipeline, Java
 * compares wire values strictly (one shared wire convention).
 *
 * <p><strong>The one driver-level form.</strong> execute()'s runtime/
 * extensions args are engine-harness plumbing (runtime objects
 * legend-lite deliberately does not model): the driver consumes the
 * QUERY (arg 0) and the MAPPING (arg 1, caller-import-resolved);
 * trailing config args are un-typed and the CALLER supplies the physical
 * connection + runtime — the same boundary the engine's own execute
 * crosses into Java.
 *
 * <p><strong>Failure polarity.</strong> Anything unrecognized is
 * {@link Outcome.Unsupported} (named, loud), never a silent skip; a
 * compile error propagates; assertion evaluation STOPS at the first
 * failing assert (real pure {@code assert} raises).
 */
public final class TestBody {

    private TestBody() {
    }

    /** The result of driving one test body. */
    public sealed interface Outcome {

        /** The body ran to completion or first assert failure.
         * verified = row/value asserts run; advisory = golden-SQL
         * recognized not compared (our SQL is our dialect's, by design);
         * executed = statements run THROUGH the platform (an assert-free
         * executed body is an engine-parity pass, not hollow);
         * failures = first assert failure (empty = all held). */
        record Ran(int verified, int advisory, int executed,
                List<String> failures, List<String> sqlDiffs) implements Outcome {
            public Ran(int verified, int advisory, int executed,
                    List<String> failures) {
                this(verified, advisory, executed, failures, List.of());
            }
        }

        /** A statement/assert shape the driver does not support yet — NAMED. */
        record Unsupported(String reason) implements Outcome {
        }
    }

    // execute() bindings and every read over them run PLATFORM-SIDE (audit
    // 19d B2): the statements forward VERBATIM to the statement executor's
    // result frame — the harness no longer owns any envelope semantics
    // (the values/at/toOne/size splice rules live in StatementExecutor).

    /** Does the expression (transitively) contain an {@code execute()} call? */
    private static boolean containsExecute(ValueSpecification v) {
        if (v instanceof AppliedFunction af && isExecuteCall(af)) {
            return true;
        }
        return switch (v) {
            case AppliedFunction af -> af.parameters().stream()
                    .anyMatch(TestBody::containsExecute);
            case AppliedProperty ap -> containsExecute(ap.receiver());
            case PureCollection pc -> pc.values().stream()
                    .anyMatch(TestBody::containsExecute);
            case LambdaFunction lf -> lf.body().stream()
                    .anyMatch(TestBody::containsExecute);
            default -> false;
        };
    }

    /** Does the expression read any of the given variables? (No shadow
     * tracking — execute bindings are never usefully shadowed, and
     * over-forwarding a statement prefix is safe.) */
    private static boolean referencesAny(ValueSpecification v,
            java.util.Set<String> names) {
        return switch (v) {
            case Variable var -> names.contains(var.name());
            case AppliedFunction af -> af.parameters().stream()
                    .anyMatch(p -> referencesAny(p, names));
            case AppliedProperty ap -> referencesAny(ap.receiver(), names);
            case PureCollection pc -> pc.values().stream()
                    .anyMatch(p -> referencesAny(p, names));
            case LambdaFunction lf -> lf.body().stream()
                    .anyMatch(p -> referencesAny(p, names));
            default -> false;
        };
    }

    /**
     * ORDER-POLICY VIEW ONLY: rewrite {@code $r.values(->at(0)/->toOne())}
     * reads to the bound query's chain expression so {@link #endsInSort}
     * sees a sort INSIDE the query lambda (the platform frame owns the
     * actual evaluation; this rewrite never executes).
     */
    private static ValueSpecification orderView(ValueSpecification v,
            Map<String, ValueSpecification> execChains) {
        if (v instanceof AppliedProperty ap && ap.property().equals("values")
                && ap.receiver() instanceof Variable var) {
            ValueSpecification chain = execChains.get(var.name());
            if (chain != null) {
                return chain;
            }
        }
        if (v instanceof Variable var) {
            ValueSpecification chain = execChains.get(var.name());
            if (chain != null) {
                return chain;
            }
        }
        return switch (v) {
            case AppliedFunction af -> new AppliedFunction(af.function(),
                    af.parameters().stream()
                            .map(p -> orderView(p, execChains)).toList());
            case AppliedProperty ap -> new AppliedProperty(
                    orderView(ap.receiver(), execChains), ap.property());
            default -> v.mapChildren(x -> orderView(x, execChains));
        };
    }

    /** BARE {@code $result.values} = the engine Result envelope's values:
     * a TDS is ONE object (carrier); instance/scalar collections SPLAT to
     * their element count (the router composition goldens pin both). */
    private static @com.legend.Nullable String carrierSizeCheck(Object n, ValueSpecification arg,
            Map<String, ValueSpecification> lets,
            List<ValueSpecification> execStmts, java.util.Set<String> execVars,
            Map<String, ValueSpecification> execChains, ModelContext ctx,
            ImportScope imports, String runtimeFqn, Connection conn)
            throws java.sql.SQLException {
        Eval av = eval(arg, lets, execStmts, execVars, execChains, ctx,
                imports, runtimeFqn, conn);
        boolean tdsCarrier = av.result()
                instanceof com.legend.exec.ExecutionResult.Tabular tb
                && (tb.returnType() instanceof com.legend.compiler.element
                        .type.Type.RelationType
                        || com.legend.compiler.element.type.PlatformTypes
                                .isTdsType(tb.returnType()));
        long carriers = tdsCarrier ? 1L : av.size();
        return (n instanceof Number cn && cn.longValue() == carriers) ? null
                : "assertSize(result.values): expected " + n + ", got "
                        + carriers + " (TDS = one carrier; collections splat)";
    }

    /** The query CHAIN of a forwarded execute binding ({@code let name =
     * execute(|chain, ...)}) — for the order-policy view; aliases follow. */
    private static void recordExecChain(String name, ValueSpecification rhs,
            Map<String, ValueSpecification> execChains) {
        ValueSpecification cur = rhs;
        while (true) {
            if (cur instanceof AppliedProperty ap
                    && ap.property().equals("values")) {
                cur = ap.receiver();
                continue;
            }
            if (cur instanceof AppliedFunction w
                    && (w.function().equals("at") || w.function().equals("toOne"))
                    && !w.parameters().isEmpty()) {
                cur = w.parameters().get(0);
                continue;
            }
            break;
        }
        if (cur instanceof Variable var && execChains.containsKey(var.name())) {
            execChains.put(name, execChains.get(var.name()));
            return;
        }
        if (cur instanceof AppliedFunction ex && isExecuteCall(ex)
                && ex.parameters().get(0) instanceof LambdaFunction lf
                && !lf.body().isEmpty()) {
            execChains.put(name, lf.body().get(lf.body().size() - 1));
        }
    }

    /**
     * Drive one test body.
     *
     * @param ctx        the compiled model (compile once per model text,
     *                   reuse across the file's tests)
     * @param body       the test function's body source (statements between
     *                   the braces)
     * @param imports    the enclosing section's import scope (plus the
     *                   test's own package)
     * @param runtimeFqn the driver-supplied runtime (connections; also the
     *                   dialect)
     */
    public static @com.legend.Nullable Outcome run(ModelContext ctx, String body, ImportScope imports,
            String runtimeFqn, Connection conn) throws java.sql.SQLException {
        return run(ctx, body, imports, runtimeFqn, conn, false);
    }

    /**
     * {@code emptinessUnverifiable}: the caller knows the database may be
     * missing rows for environmental reasons (failed seed replay) — an
     * emptiness-shaped assertion (assertEmpty, assertSize 0, an empty
     * expected grid) proves nothing then and the body reports Unsupported
     * instead of a hollow pass.
     */
    public static @com.legend.Nullable Outcome run(ModelContext ctx, String body, ImportScope imports,
            String runtimeFqn, Connection conn, boolean emptinessUnverifiable)
            throws java.sql.SQLException {
        return run(ctx, SpecParser.parseCodeBlock(body), imports, runtimeFqn,
                conn, emptinessUnverifiable);
    }

    /**
     * AST entry (Phase C): the test body arrives ALREADY PARSED — the
     * harness discovers test functions from the parsed model, so their
     * statement lists come straight off the FunctionDefinition, no
     * re-parse of extracted text.
     */
    public static @com.legend.Nullable Outcome run(ModelContext ctx,
            java.util.List<ValueSpecification> statements, ImportScope imports,
            String runtimeFqn, Connection conn, boolean emptinessUnverifiable)
            throws java.sql.SQLException {
        return run(ctx, statements, imports, runtimeFqn, conn,
                emptinessUnverifiable, java.util.List.of());
    }

    /**
     * {@code seedFailures}: the caller's failed-seed LEDGER — setup calls
     * the body makes report per-statement raw-SQL failures here instead of
     * aborting (engine-harness tolerance), and a non-empty ledger makes
     * emptiness-shaped assertions unverifiable from that point on.
     */
    public static @com.legend.Nullable Outcome run(ModelContext ctx,
            java.util.List<ValueSpecification> statements, ImportScope imports,
            String runtimeFqn, Connection conn, boolean emptinessUnverifiable,
            java.util.List<String> seedFailures)
            throws java.sql.SQLException {
        ElqSplice.ELQ_PARAMS.get().clear();   // per-test param-let names
        Preamble pre = preamble(ctx, statements, imports, runtimeFqn);
        if (pre.lineage() != null) {
            return pre.lineage();
        }
        statements = pre.statements();
        java.util.ArrayDeque<ValueSpecification> work =
                new java.util.ArrayDeque<>(statements);
        Map<String, ValueSpecification> lets = new LinkedHashMap<>();
        // the PLATFORM-forwarded statements (execute bindings + reads over
        // them, in order) and their bound names; execChains is the
        // order-policy view of each binding's query chain
        List<ValueSpecification> execStmts = new ArrayList<>();
        java.util.Set<String> execVars = new java.util.HashSet<>();
        Map<String, ValueSpecification> execChains = new LinkedHashMap<>();
        // #46 state — see tdgLetArm/checkTdgAssert for the semantics of
        // each surface (generator results, plan-transparent executionPlan
        // bindings, inert plan-text lets)
        Map<String, com.legend.testdatagen.TestDataGenerator.Result> tdg =
                new LinkedHashMap<>();
        Map<String, AppliedFunction> planLets = new LinkedHashMap<>();
        java.util.Set<String> planText = new java.util.HashSet<>();
        int verified = 0;
        int advisory = 0;
        List<String> sqlDiffs = new ArrayList<>();
        int executed = 0;
        while (!work.isEmpty()) {
            ValueSpecification stmt = work.poll();
            // print/println: the OUTPUT is noise, but the engine still
            // EVALUATES the argument (plan-print bodies: executionPlan ->
            // planToString -> println IS the test's whole contract) — a
            // clean run counts as engine-parity execution; a wall keeps
            // the old skip (tolerant: print text is never asserted)
            if (stmt instanceof AppliedFunction pln
                    && harnessVocabName(pln.function())
                    && ("println".equals(simpleName(pln.function()))
                            || "print".equals(simpleName(pln.function())))) {
                if (pln.parameters().size() == 1
                        && !(pln.parameters().get(0) instanceof CString)) {
                    try {
                        evalSpliced(subst(pln.parameters().get(0), lets),
                                execStmts, execVars, ctx, imports,
                                runtimeFqn, conn);
                        executed++;
                    } catch (com.legend.error.NotImplementedException
                            | java.sql.SQLException walled) {
                        // unported print material — noise either way
                    }
                }
                continue;
            }
            // engine test-harness WRAPPERS: the lambda argument's body IS
            // the test — inline its statements at the front of the worklist
            if (stmt instanceof AppliedFunction wrap
                    && harnessVocabName(wrap.function())
                    && java.util.Set.of("runLegendTest", "runTest",
                            "runGraphFetchTest", "mayExecuteAlloyTest",
                            "mayExecuteLegendTest")
                            .contains(simpleName(wrap.function()))) {
                LambdaFunction inner = null;
                // mayExecute* carries TWO legs (alloy-lambda, pure-lambda):
                // legend-lite executes the in-process Alloy-shaped path, so
                // the PARAMETERIZED alloy leg is the test — inline it when
                // its clientVersion/serverVersion/host/port parameters are
                // decorative (unreferenced). A leg that really reads them
                // (dials a server) falls through to the zero-arg leg.
                if (simpleName(wrap.function()).startsWith("mayExecute")) {
                    for (ValueSpecification arg : wrap.parameters()) {
                        ValueSpecification a2 = arg instanceof Variable av
                                && lets.get(av.name()) != null
                                ? lets.get(av.name()) : arg;
                        if (a2 instanceof LambdaFunction lfA
                                && !lfA.parameters().isEmpty()) {
                            java.util.Set<String> ps = new java.util.HashSet<>();
                            lfA.parameters().forEach(p -> ps.add(p.name()));
                            if (lfA.body().stream()
                                    .noneMatch(st -> referencesAny(st, ps))) {
                                inner = lfA;
                            }
                            break;
                        }
                    }
                }
                if (inner == null) {
                    inner = zeroArgLambdaArg(wrap, lets);
                }
                if (inner != null) {
                    List<ValueSpecification> bodyStmts =
                            new ArrayList<>(inner.body());
                    for (int i = bodyStmts.size() - 1; i >= 0; i--) {
                        work.addFirst(bodyStmts.get(i));
                    }
                    continue;
                }
                // PARAMETERIZED lambda + pair-bound variables (the
                // WithVariables idiom): β-bind the pairs into the query and
                // synthesize the wrapper's OWN assertions in the corpus
                // spellings the harness already evaluates — engine parity
                // (executeLegendQuery binds vars, the wrapper asserts the
                // flattened values / SQL + count).
                List<ValueSpecification> synth = etaExpandWrapper(wrap, lets);
                if (synth != null) {
                    for (int i = synth.size() - 1; i >= 0; i--) {
                        work.addFirst(synth.get(i));
                    }
                    continue;
                }
                return new Outcome.Unsupported("harness wrapper '"
                        + simpleName(wrap.function())
                        + "' carries no zero-arg lambda body");
            }
            List<ValueSpecification> unrolledLoop = spliceForms(stmt);
            if (unrolledLoop != null) {
                for (int i = unrolledLoop.size() - 1; i >= 0; i--) {
                    work.addFirst(unrolledLoop.get(i));
                }
                continue;
            }
            // let name = rhs
            if (stmt instanceof AppliedFunction af && af.function().equals("letFunction")
                    && af.parameters().size() == 2
                    && af.parameters().get(0) instanceof CString name) {
                // bind-time folds: literal-if thunks + parse-through-
                // our-own-parser grammar strings (foldLiteralIf / clgArm)
                ValueSpecification rhs = clgArm(foldLiteralIf(
                        subst(af.parameters().get(1), lets)), lets);
                // #46 arms: generateTestData binding / literal read
                // inlining / plan-transparent executionPlan chain
                TdgLet tl = tdgLetArm(name, rhs, lets, tdg, planLets,
                        planText, ctx, imports, conn);
                if (tl.wall() != null) {
                    return tl.wall();
                }
                if (tl.consumed()) {
                    executed++;
                    continue;
                }
                rhs = requireNonNull(tl.rhs(), "tdg arm not rewritten");
                List<ValueSpecification> elq = ElqSplice.splice(name, rhs, lets);
                if (elq != null) {
                    for (int i = elq.size() - 1; i >= 0; i--) {
                        work.addFirst(elq.get(i));
                    }
                    continue;
                }
                // an execute() binding — or any read over one — forwards to
                // the PLATFORM's result frame (audit 19d B2). Forwarding is
                // EAGER (audit 16 F1, engine parity): the statement executor
                // runs the query AT the let, so a broken pipeline surfaces
                // even when no assert ever reads the binding.
                // let-arm HOST FOLDS (ConnEquality.letFold): JSON plumbing
                // defers, predicate verdicts bind, objectReferences build
                ValueSpecification lf0 = ConnEquality.letFold(rhs,
                        subst(rhs, lets), ctx, imports);
                if (lf0 != null) {
                    lets.put(name.value(), lf0);
                    continue;
                }
                java.util.function.Function<ValueSpecification, Object>
                        parsedEval = e2 -> {
                            try {
                                Object r = jsonValueOf(eval(e2, lets,
                                        execStmts, execVars, execChains, ctx,
                                        imports, runtimeFqn, conn));
                                return r == null ? "" : r;   // non-List = miss
                            } catch (java.sql.SQLException se) {
                                throw new IllegalStateException(se);
                            }
                        };
                ValueSpecification exd = JsonAssertCanon.extractStrings(rhs,
                        parsedEval);
                if (exd == null) {
                    exd = ObjectRefs.decodePkMaps(rhs, ctx, parsedEval);
                }
                if (exd != null) {
                    lets.put(name.value(), exd);
                    continue;
                }
                if (containsExecute(rhs) || referencesAny(rhs, execVars)) {
                    execStmts.add(new AppliedFunction("letFunction",
                            List.of(name, rhs)));
                    execVars.add(name.value());
                    recordExecChain(name.value(), rhs, execChains);
                    evalStatements(execStmts, ctx, imports, runtimeFqn, conn);
                    executed++;
                    continue;
                }
                Outcome sw = letSetupArm(rhs, lets, tdg, ctx, imports,
                        runtimeFqn, conn, seedFailures);
                if (sw != null) {
                    return sw;
                }
                // a PLAIN let carrying an inline testDataSetupCsv runtime
                // copy seeds NOW (engine: the test connection's own data;
                // the query that names this runtime sees it) — the
                // execute-binding path collects via evalStatements, this
                // arm covers the executeLegendQuery/from() shapes
                List<ValueSpecification> csvs = new ArrayList<>();
                collectInlineCsv(rhs, csvs);
                for (ValueSpecification csvExpr : csvs) {
                    seedInlineCsv(csvExpr, ctx, conn);
                }
                lets.put(name.value(), purifiedSetup(rhs, ctx));
                continue;
            }
            // The per-driver golden idiom:
            //   $expected->map(p| let driver = $p.first; let expectedSql =
            //   $p.second; ...; assertEquals(...);)->distinct() == [true]
            // — HOST-side orchestration (the multi-statement lambda is
            // harness vocabulary, not a query). Every declared driver must
            // be H2: verifying an H2 subset of a multi-driver list would be
            // silent partial verification.
            if (stmt instanceof AppliedFunction eqf
                    && simpleName(eqf.function()).equals("equal")
                    && eqf.parameters().size() == 2) {
                List<AppliedFunction> pairs = new ArrayList<>();
                LambdaFunction perDriver = driverPairLoop(
                        eqf.parameters().get(0), lets, pairs);
                if (perDriver != null) {
                    int[] counters = {verified, advisory};
                    Outcome o = runPerDriverLoop(pairs, perDriver, lets,
                            execStmts, execVars, execChains, ctx, imports,
                            runtimeFqn, conn,
                            emptinessUnverifiable || seedFailures != null
                                    && !seedFailures.isEmpty(), counters);
                    verified = counters[0];
                    advisory = counters[1];
                    if (o != null) {
                        return o;
                    }
                    continue;
                }
            }
            if (stmt instanceof AppliedFunction af
                    && harnessVocabName(af.function())
                    && simpleName(af.function()).startsWith("assert")) {
                String failure = checkAssert(af, lets, execStmts, execVars,
                        execChains, ctx, imports,
                        runtimeFqn, conn, emptinessUnverifiable
                                || seedFailures != null && !seedFailures.isEmpty(),
                        tdg, planText);
                int[] cs = {verified, advisory};
                Outcome oc = scoreAssert(af, failure, cs, sqlDiffs,
                        executed);
                verified = cs[0];
                advisory = cs[1];
                if (oc != null) {
                    return oc;
                }
                continue;
            }
            if (stmt instanceof CBoolean) {   // conventional trailing true
                continue;
            }
            // runtime-conditional if (RuntimeIfForm): branch re-enters
            if (RuntimeIfForm.splice(subst(stmt, lets), lets, execStmts,
                    execVars, execChains, ctx, imports, runtimeFqn, conn,
                    work)) {
                executed++;
                continue;
            }
            // K-natives arc (S4): any other EXPRESSION STATEMENT runs
            // through the platform (setup calls are ordinary pure code).
            // SQLExceptions propagate (honest ERROR); compile/type
            // failures report Unsupported (body data untrusted after).
            if (stmt instanceof AppliedFunction af3) {
                try {
                    ValueSpecification sub = java.util.Objects.requireNonNull(
                            TestDataGenForm.inlineReads(
                                    subst(stmt, lets), tdg));
                    ValueSpecification wrapped =
                            referencesAny(sub, execVars)
                                    ? new LambdaFunction(List.of(),
                                            append(execStmts, sub))
                                    : sub;
                    Compiler.executeResolved(
                            NameResolver.resolveQuery(wrapped,
                                    imports, ctx.elementFqns()),
                            ctx, runtimeFqn, conn,
                            seedFailures == null ? null : seedFailures::add);
                    executed++;
                    continue;
                } catch (java.sql.SQLException sql) {
                    throw sql;
                } catch (com.legend.error.NotImplementedException e) {
                    // a VOCABULARY gap — honestly SHAPE; any OTHER
                    // RuntimeException is a real pipeline defect and must
                    // surface as ERROR, not hide in the SHAPE bucket
                    // (audit 17) — it propagates to the runner's scorer
                    return new Outcome.Unsupported("statement '" + af3.function()
                            + "' failed through the pipeline: "
                            + String.valueOf(e.getMessage()).split("\\n")[0]);
                }
            }
            return new Outcome.Unsupported("unsupported statement: "
                    + stmt.getClass().getSimpleName());
        }
        return new Outcome.Ran(verified, advisory, executed, List.of(),
                List.copyOf(sqlDiffs));
    }

    private record Preamble(java.util.List<ValueSpecification> statements,
            @com.legend.Nullable Outcome lineage) {
    }

    /** FEATURE-TRACK preprocessing before statement routing:
     * validate(...) desugars to the engine's own synthesized query over
     * the ORDINARY execute path (#45 — before routing, so the exec-frame
     * machinery sees the execute binding; a fired desugar runs the body
     * with the addDriverTablePkForProject option, set FRESH every run),
     * and the canonical scanColumns lineage form (#44) routes whole to
     * the real analyzer (see LineageForm's why-not-K-natives note). */
    private static Preamble preamble(ModelContext ctx,
            java.util.List<ValueSpecification> statements,
            ImportScope imports, String runtimeFqn) {
        java.util.List<ValueSpecification> desugared =
                new ArrayList<>(statements.size());
        boolean fired = false;
        for (ValueSpecification s : statements) {
            ValueSpecification r = com.legend.validation.ValidateDesugar
                    .rewrite(s, ctx, imports.wildcards());
            desugared.add(r);
            fired |= r != s;
        }
        com.legend.validation.DriverPkOption.set(fired);
        Outcome lineage = LineageForm.tryRun(ctx, desugared, imports,
                runtimeFqn);
        if (lineage == null) {
            lineage = LineageRelationsForm.tryRun(ctx, desugared, imports,
                    runtimeFqn);
        }
        return new Preamble(desugared, lineage);
    }

    /** Fold {@code if(<literal>, |a, |b)} (zero-param thunks, one body
     * expression each) to the chosen branch — the checked/unchecked
     * helper idiom resolves to a plain query lambda. */
    /** The first zero-arg lambda among {@code wrap}'s arguments, looking
     * through let-bound variables and through
     * {@code meta::pure::router::preeval::preval(query, extensions)} — the
     * engine's PLAN-TIME pre-evaluation, identity for row semantics: the
     * wrapped query IS the query. */
    private static @com.legend.Nullable LambdaFunction zeroArgLambdaArg(
            AppliedFunction wrap, Map<String, ValueSpecification> lets) {
        for (ValueSpecification arg : wrap.parameters()) {
            ValueSpecification a2 = arg instanceof Variable av
                    && lets.get(av.name()) != null
                    ? lets.get(av.name()) : arg;
            if (a2 instanceof AppliedFunction pf
                    && pf.function().equals(
                            "meta::pure::router::preeval::preval")
                    && !pf.parameters().isEmpty()) {
                a2 = pf.parameters().get(0);
                if (a2 instanceof Variable av2
                        && lets.get(av2.name()) != null) {
                    a2 = lets.get(av2.name());
                }
            }
            if (a2 instanceof LambdaFunction lf0
                    && lf0.parameters().isEmpty()) {
                return lf0;
            }
        }
        return null;
    }

    private static @com.legend.Nullable ValueSpecification foldLiteralIf(ValueSpecification v) {
        while (v instanceof AppliedFunction f && f.function().equals("if")
                && f.parameters().size() == 3
                && f.parameters().get(0)
                        instanceof com.legend.protocol.spec.CBoolean b
                && f.parameters().get(1) instanceof LambdaFunction t
                && t.parameters().isEmpty() && t.body().size() == 1
                && f.parameters().get(2) instanceof LambdaFunction e
                && e.parameters().isEmpty() && e.body().size() == 1) {
            v = b.value() ? t.body().get(0) : e.body().get(0);
        }
        return v;
    }

    /** Strip JSON canonicalization wrappers (parseJSON / toPrettyJSONString)
     * from an assertJsonStringsEqual argument — the assert parses and
     * deep-compares both sides itself, so the wrappers are identity. */
    private static com.legend.protocol.spec.@com.legend.Nullable ValueSpecification stripJsonCanon(
            com.legend.protocol.spec.ValueSpecification v) {
        while (v instanceof com.legend.protocol.spec.AppliedFunction af
                && af.parameters().size() == 1
                && (af.function().equals("parseJSON")
                        || af.function().equals("toPrettyJSONString")
                        || af.function().endsWith("::parseJSON")
                        || af.function().endsWith("::toPrettyJSONString"))) {
            v = af.parameters().get(0);
        }
        return v;
    }

    /** One side of a JSON assert as a PARSED structure: a GRAPH result's
     * envelope, or a String value holding JSON text. Null = not JSON-shaped
     * (the caller reports Unsupported, never a false verdict). */
    private static @com.legend.Nullable Object jsonValueOf(Eval e) {
        if (e.result instanceof com.legend.exec.ExecutionResult.Graph g) {
            return com.legend.sql.Json.parse(g.json());
        }
        List<Object> vals = e.values();
        if (vals.size() == 1 && vals.get(0) instanceof String str) {
            try {
                // parseOne: real pure parseJSON reads the LEADING value
                // (a golden with stray text after the root still compares)
                return com.legend.sql.Json.parseOne(str);
            } catch (RuntimeException notJson) {
                return null;
            }
        }
        return null;
    }

    private static @com.legend.Nullable String abbreviate(String s) {
        return s.length() <= 160 ? s : s.substring(0, 157) + "...";
    }

    /** The elements of a CONSTANT string collection ({@code ['a'+'b', $x]}
     * with let-resolved, concat-folded elements), or null if any element
     * is not a compile-time string. */
    private static @com.legend.Nullable List<String> constantStrings(ValueSpecification v) {
        List<ValueSpecification> elems =
                v instanceof PureCollection pc ? pc.values() : List.of(v);
        List<String> out = new ArrayList<>(elems.size());
        for (ValueSpecification e : elems) {
            String sv = constantString(e);
            if (sv == null) {
                return null;
            }
            out.add(sv);
        }
        return out;
    }

    private static @com.legend.Nullable String constantString(ValueSpecification v) {
        if (v instanceof CString cs) {
            return cs.value();
        }
        if (v instanceof AppliedFunction af && af.parameters().size() == 2
                && ("plus".equals(af.function()) || "+".equals(af.function()))) {
            String l = constantString(af.parameters().get(0));
            String r = constantString(af.parameters().get(1));
            return l != null && r != null ? l + r : null;
        }
        if (v instanceof AppliedFunction af && "plus".equals(af.function())
                && af.parameters().size() == 1
                && af.parameters().get(0) instanceof PureCollection pc) {
            StringBuilder sb = new StringBuilder();
            for (ValueSpecification e : pc.values()) {
                String sv = constantString(e);
                if (sv == null) {
                    return null;
                }
                sb.append(sv);
            }
            return sb.toString();
        }
        return null;
    }

    /** One CSV seed block: {@code schema\ntable\nHEADER\nrows...} —
     * DROP + CREATE from the model's OWN table definition (engine
     * setUpDataSQLsV2 semantics: the test connection holds exactly the
     * CSV tables, so a bulk-seeded base table sharing the name cannot
     * shadow the family's — audit: 37 modelJoin binder errors), then
     * typed INSERTs ('default' schema is bare; empty tokens are NULL;
     * numerics ride bare, everything else quotes). */


    // ===== assert dispatch =====

    static final String UNSUPPORTED_MARKER = new String("unsupported");

    /** C0.3: the marker is an IDENTITY sentinel — the wall reason is lost
     * by construction. Sites that KNOW their reason set it via
     * {@link #unsupported}; the two marker consumers read and CLEAR it. */
    static final ThreadLocal<String> UNSUPPORTED_REASON = new ThreadLocal<>();

    static @com.legend.Nullable String unsupported(String reason) {
        UNSUPPORTED_REASON.set(reason);
        return UNSUPPORTED_MARKER;
    }

    private static @com.legend.Nullable String takeUnsupportedReason() {
        String why = UNSUPPORTED_REASON.get();
        UNSUPPORTED_REASON.remove();
        return why;
    }
    private static final String ADVISORY_MARKER = new String("advisory");
    private static final String NOT_TDG_MARKER = new String("not-tdg");

    /** The statement-splice forms, first match wins: per-driver golden
     * loops, result-var loops, the alloy fallback. */
    private static @com.legend.Nullable List<ValueSpecification> spliceForms(
            ValueSpecification stmt) {
        List<ValueSpecification> out = enumDriverLoop(stmt);
        if (out == null) {
            out = resultVarLoop(stmt);
        }
        if (out == null) {
            out = alloyFallback(stmt);
        }
        return out;
    }

    /** {@code mayExecuteAlloyTest(serverThunk, fallbackThunk)}: no Alloy
     * server exists in this environment, so the FALLBACK thunk's body
     * splices — the engine's own no-server CI takes the same branch
     * (usually {@code {|true}}). A non-lambda fallback returns null and
     * the statement walls loudly downstream. */
    private static @com.legend.Nullable List<ValueSpecification> alloyFallback(
            ValueSpecification stmt) {
        if (stmt instanceof AppliedFunction af
                && simpleName(af.function()).equals("mayExecuteAlloyTest")
                && af.parameters().size() == 2
                && af.parameters().get(1) instanceof LambdaFunction fb
                && fb.parameters().isEmpty()) {
            return new ArrayList<>(fb.body());
        }
        return null;
    }

    /** {@code meta::legend::compileLegendGrammar(<foldable string>)}
     * behind optional {@code ->at(i)}/{@code ->cast(@...)} wraps: parse
     * the grammar with the platform's own parser and return the selected
     * FunctionDefinition's BODY as a zero-arg lambda; any other shape
     * passes through untouched. */
    private static @com.legend.Nullable ValueSpecification clgArm(
            @com.legend.Nullable ValueSpecification rhs,
            Map<String, ValueSpecification> lets) {
        ValueSpecification cur = rhs;
        long idx = 0;
        while (cur instanceof AppliedFunction af
                && !af.parameters().isEmpty()) {
            String n = simpleName(af.function());
            if (n.equals("cast") || n.equals("toOne")) {
                cur = af.parameters().get(0);
            } else if (n.equals("at") && af.parameters().size() == 2
                    && af.parameters().get(1)
                            instanceof com.legend.protocol.spec.CInteger ci) {
                idx = ci.value().longValue();
                cur = af.parameters().get(0);
            } else {
                break;
            }
        }
        if (!(cur instanceof AppliedFunction clg)
                || !harnessVocabName(clg.function())
                || !simpleName(clg.function()).equals("compileLegendGrammar")
                || clg.parameters().size() != 1) {
            return rhs;
        }
        String src = TestDataGenForm.foldString(
                subst(clg.parameters().get(0), lets));
        if (src == null) {
            return rhs;
        }
        List<com.legend.model.FunctionDefinition> fns = new ArrayList<>();
        for (com.legend.model.PackageableElement el
                : com.legend.parser.ElementParser.parsePlatform(src).elements()) {
            if (el instanceof com.legend.model.FunctionDefinition fd) {
                fns.add(fd);
            }
        }
        if (idx < 0 || idx >= fns.size()) {
            return rhs;
        }
        return new LambdaFunction(List.of(),
                new ArrayList<>(fns.get((int) idx).body()));
    }

    /** One assert's terminal outcome from its checkAssert result, or
     * null to continue; {@code counters} = {verified, advisory}. A
     * divergent golden text records into {@code sqlDiffs} — rows stay
     * the contract for tests that verify anything else; a test with NO
     * other verification fails on the diff (runner scoring). */
    /** assertContains(collection, value[, message…]) — real pure
     * membership (assertContains.pure:20); message args ignored. */
    private static @com.legend.Nullable String assertContainsCheck(List<ValueSpecification> args,
            Map<String, ValueSpecification> lets,
            List<ValueSpecification> execStmts, java.util.Set<String> execVars,
            Map<String, ValueSpecification> execChains, ModelContext ctx,
            ImportScope imports, String runtimeFqn, Connection conn,
            boolean emptinessUnverifiable) throws java.sql.SQLException {
        if (args.size() < 2) {
            return UNSUPPORTED_MARKER;
        }
        Eval col = eval(args.get(0), lets, execStmts, execVars, execChains,
                ctx, imports, runtimeFqn, conn);
        if (emptinessUnverifiable && col.size() == 0) {
            return UNSUPPORTED_MARKER;   // see the assertEquals guard
        }
        Eval val = eval(args.get(1), lets, execStmts, execVars, execChains,
                ctx, imports, runtimeFqn, conn);
        if (val.values().size() != 1) {
            return UNSUPPORTED_MARKER;
        }
        for (Object x : col.values()) {
            if (wireEquals(x, val.values().get(0))) {
                return null;
            }
        }
        return "assertContains: " + col.render()
                + " does not contain " + val.render();
    }

    private static @com.legend.Nullable Outcome scoreAssert(
            AppliedFunction af, @com.legend.Nullable String failure,
            int[] counters, List<String> sqlDiffs, int executed) {
        if (failure == UNSUPPORTED_MARKER) {
            String why = takeUnsupportedReason();
            return new Outcome.Unsupported("assert form '" + af.function()
                    + "/" + af.parameters().size() + "' is not supported yet"
                    + (why == null ? "" : " — " + why));
        }
        if (failure == ADVISORY_MARKER) {
            counters[1]++;
            return null;
        }
        if (failure != null && failure.startsWith("sql-text: ")) {
            counters[1]++;
            sqlDiffs.add(failure);
            return null;
        }
        counters[0]++;
        if (failure != null) {
            return new Outcome.Ran(counters[0], counters[1], executed,
                    List.of(failure));
        }
        return null;
    }

    /** {@code assertInstanceOf}: metamodel-walk values carry their
     * KIND — compared against the type ref's simple name. */
    private static @com.legend.Nullable String instanceOfAssert(List<ValueSpecification> args,
            Map<String, ValueSpecification> lets,
            List<ValueSpecification> execStmts,
            java.util.Set<String> execVars,
            Map<String, ValueSpecification> execChains, ModelContext ctx,
            ImportScope imports, String runtimeFqn, Connection conn)
            throws java.sql.SQLException {
        if (args.size() != 2) {
            return UNSUPPORTED_MARKER;
        }
        Eval v9 = eval(args.get(0), lets, execStmts, execVars, execChains,
                ctx, imports, runtimeFqn, conn);
        Object sv9 = v9.result() instanceof
                com.legend.exec.ExecutionResult.Scalar sc9
                ? sc9.value() : null;
        String tn9 = args.get(1) instanceof
                com.legend.protocol.spec.PackageableElementPtr pep9
                ? pep9.fullPath().substring(
                        pep9.fullPath().lastIndexOf(':') + 1)
                : null;
        if (sv9 == null || tn9 == null) {
            return UNSUPPORTED_MARKER;
        }
        String got9 = String.valueOf(sv9);
        return got9.startsWith("NodeH[kind=" + tn9 + ",")
                || got9.startsWith(tn9 + "[")
                ? null
                : "assertInstanceOf: expected " + tn9 + ", got " + got9;
    }

    /** The ENGINE's own contract for golden-SQL asserts: render the
     * SAME query through the toSQLString surface (the EngineStyleH2
     * dialect over the one SQL IR — a sibling of the DuckDB renderer,
     * no side-band conversion) and compare LITERALLY. Byte-exact match
     * verifies; a text diff falls back to the #67 H2 row-replay (rows
     * equal = execution-equivalent, SQL divergence stays visible in the
     * census); when neither verifies, the TEXT DIFF is the failure —
     * never a silent advisory skip. */
    private static @com.legend.Nullable String sqlTextVerify(List<ValueSpecification> args,
            Map<String, ValueSpecification> lets,
            List<ValueSpecification> execStmts,
            java.util.Set<String> execVars,
            Map<String, ValueSpecification> execChains, ModelContext ctx,
            ImportScope imports, String runtimeFqn, Connection conn)
            throws java.sql.SQLException {
        String golden = null;
        ValueSpecification actual = null;
        for (ValueSpecification a : args) {
            String s = TestDataGenForm.foldString(subst(a, lets));
            if (s != null && golden == null) {
                golden = s;
            } else {
                actual = a;
            }
        }
        long gt0 = System.nanoTime();   // GOLDEN_NANOS perf instrument
        String sql = ExecCallFinder.sideSqlText(actual, lets, execStmts,
                execVars, execChains, ctx, imports, runtimeFqn, conn);
        H2Verify.GOLDEN_NANOS.addAndGet(System.nanoTime() - gt0);
        if (golden == null && args.size() == 2 && sql != null) {
            // NO golden literal: the contract is the two sides' SQL being
            // IDENTICAL (slice-0-is-take shape) — both texts are OURS, so
            // the compare verifies without any engine-text parity
            String other = ExecCallFinder.sideSqlText(args.get(0), lets,
                    execStmts, execVars, execChains, ctx, imports,
                    runtimeFqn, conn);
            if (other != null) {
                return other.equals(sql) ? null
                        : "sql sides differ: " + other + " vs " + sql;
            }
        }
        if (golden != null && sql != null) {
            if (golden.equals(sql)) {
                // MILESTONE 1 (H2_BACKEND.md §12.5): the matched text
                // IS our rendering — execute it on H2, hold its rows
                // to our DuckDB rows; a divergence is a REAL renderer
                // bug (H5.1 class), never advisory.
                String h2rows = h2Upgrade(args, lets, execStmts,
                        execVars, execChains, ctx, imports,
                        runtimeFqn, conn);
                if (h2rows == null) {
                    H2Verify.M1_VERIFIED.increment();
                    return null;
                }
                if (java.util.Objects.equals(h2rows, ADVISORY_MARKER)) {
                    H2Verify.M1_UNVERIFIABLE.increment();
                    return null;
                }
                H2Verify.M1_DIVERGED.increment();
                return "h2-exec: OUR byte-matched SQL on H2 diverged"
                        + " from our DuckDB rows — " + h2rows;
            }
            // divergent text: execution-equivalence may still verify
            String rows = h2Upgrade(args, lets, execStmts, execVars,
                    execChains, ctx, imports, runtimeFqn, conn);
            if (rows != ADVISORY_MARKER) {
                return rows;
            }
            return "sql-text: expected " + golden + ", got " + sql;
        }
        return h2Upgrade(args, lets, execStmts, execVars, execChains, ctx,
                imports, runtimeFqn, conn);
    }



    /** ONE decline channel for every h2-replay early-out (§12.4) —
     * printing and the per-reason census live in {@link H2Verify#decline}. */
    private static void h2Decline(String reason) {
        H2Verify.decline(reason);
    }

    /** #67: a pure golden-SQL assert upgrades to ROW-VERIFIED when the
     * H2 second target can replay the test's raw seeds (recorded at the
     * RawSqlBoundary — H2-flavored BY DEFINITION) and execute the golden
     * on the engine's own dialect: golden-H2 rows vs our DuckDB rows,
     * order-insensitive. null = verified match (a REAL verification, not
     * a hollow pass); text = divergence FAIL; unverifiable inputs return
     * the advisory marker — exactly the pre-#67 behavior. */
    private static @com.legend.Nullable String h2Upgrade(List<ValueSpecification> args,
            Map<String, ValueSpecification> lets,
            List<ValueSpecification> execStmts,
            java.util.Set<String> execVars,
            Map<String, ValueSpecification> execChains, ModelContext ctx,
            ImportScope imports, String runtimeFqn, Connection conn) {
        if (!H2Verify.ready()
                || com.legend.exec.RawSqlBoundary.recording() == null
                || args.size() != 2) {
            // COUNTED decline (H2_BACKEND.md §12 step 4): these
            // early-outs were the two silent ADVISORY_MARKER paths —
            // without the print the sweep's unverifiable total lied low
            h2Decline(!H2Verify.ready() ? "h2 driver not ready"
                    : com.legend.exec.RawSqlBoundary.recording() == null
                            ? "no recorded seed statements"
                            : "assert arity " + args.size() + " != 2");
            return ADVISORY_MARKER;
        }
        String golden = null;
        ValueSpecification actual = null;
        for (ValueSpecification a : args) {
            String s = TestDataGenForm.foldString(subst(a, lets));
            if (s != null && golden == null) {
                golden = s;
            } else {
                actual = a;
            }
        }
        String var = actual == null ? null
                : rootExecVar(actual, execVars, lets);
        if (golden == null || var == null) {
            h2Decline(golden == null ? "no foldable golden string"
                    : "no root exec variable in the actual arg");
            return ADVISORY_MARKER;
        }
        try {
            Eval rows = eval(new AppliedProperty(
                    new Variable(var, null, null), "values"), lets,
                    execStmts, execVars, execChains, ctx, imports,
                    runtimeFqn, conn);
            // session-direct on an H2 backend, seed-replay elsewhere —
            // the routing lives with the oracle (H2Verify.verifyAuto)
            return H2Verify.verifyAuto(conn,
                    com.legend.exec.RawSqlBoundary.recording(), golden,
                    rows.result(), H2Verify.enumDecodeFor(rows.result(),
                            actual, lets, execStmts, ctx, imports));
        } catch (java.sql.SQLException | RuntimeException e) {
            // audit (TENET V2.1): this decline was visible ONLY under
            // LL_H2_DEBUG — a row-verification opportunity silently fell
            // back to advisory. The fallback stays (pre-#67 status quo;
            // hardening it to FAIL waits on the CsvSeed producer fix),
            // but every sweep now COUNTS it: grep '\[h2-unverifiable\]'.
            h2Decline("replay/verify failed: "
                    + String.valueOf(e.getMessage()).replace('\n', ' '));
            return ADVISORY_MARKER;
        }
    }

    /** {@code assertEquals/assertSameElements(cols, pkOfFunc(fnRef))} —
     * PK auto-inference (#78): the referenced corpus function's parsed
     * body walks through {@link com.legend.lineage.PkInference}; list
     * equality VERIFIES (assertSameElements order-insensitively). */
    private static @com.legend.Nullable String pkAssert(AppliedFunction af,
            List<ValueSpecification> args, ModelContext ctx) {
        String fn = simpleName(af.function());
        if (!(fn.equals("assertEquals") || fn.equals("assertSameElements"))
                || args.size() != 2) {
            return NOT_TDG_MARKER;
        }
        AppliedFunction pk = args.get(1) instanceof AppliedFunction c
                && simpleName(c.function()).equals("pkOfFunc") ? c : null;
        if (pk == null || pk.parameters().size() != 1
                || !(pk.parameters().get(0)
                        instanceof com.legend.protocol.spec
                                .PackageableElementPtr ptr)) {
            return NOT_TDG_MARKER;
        }
        String path = ptr.fullPath();
        int mangle = path.indexOf("__");
        String fqn = mangle > 0 ? path.substring(0, mangle) : path;
        var fd = ctx.findFunctionDefinition(fqn);
        if (fd.isEmpty() || fd.get().body().isEmpty()) {
            return NOT_TDG_MARKER;
        }
        List<String> got = com.legend.lineage.PkInference.infer(ctx,
                fd.get().body().get(0));
        List<String> expected = new ArrayList<>();
        ValueSpecification e = args.get(0);
        List<ValueSpecification> items = e instanceof PureCollection pc
                ? pc.values() : List.of(e);
        for (ValueSpecification it : items) {
            if (!(it instanceof CString cs)) {
                return NOT_TDG_MARKER;
            }
            expected.add(cs.value());
        }
        boolean ok = fn.equals("assertSameElements")
                ? new java.util.HashSet<>(expected)
                        .equals(new java.util.HashSet<>(got))
                : expected.equals(got);
        return ok ? null : "pkOfFunc: expected " + expected + ", got " + got;
    }


    static boolean walkHasProp(@com.legend.Nullable ValueSpecification v, String name) {
        if (v instanceof AppliedProperty ap) {
            return name.equals(ap.property())
                    || walkHasProp(ap.receiver(), name);
        }
        if (v instanceof AppliedFunction af) {
            return af.parameters().stream()
                    .anyMatch(x -> walkHasProp(x, name));
        }
        return false;
    }

    static boolean walkHasCall(@com.legend.Nullable ValueSpecification v) {
        if (v instanceof AppliedFunction af) {
            return simpleName(af.function()).equals("executionPlan")
                    || af.parameters().stream()
                            .anyMatch(TestBody::walkHasCall);
        }
        if (v instanceof AppliedProperty ap) {
            return walkHasCall(ap.receiver());
        }
        return false;
    }




    /** The exec-frame variable an expression reads through (receiver /
     * first-arg chains), or null. */
    private static @com.legend.Nullable String rootExecVar(ValueSpecification v,
            java.util.Set<String> execVars,
            Map<String, ValueSpecification> lets) {
        v = substitute(v, lets);
        while (true) {
            if (v instanceof Variable var) {
                return execVars.contains(var.name()) ? var.name() : null;
            }
            if (v instanceof AppliedProperty ap) {
                v = ap.receiver();
            } else if (v instanceof AppliedFunction af
                    && !af.parameters().isEmpty()) {
                v = af.parameters().get(0);
            } else {
                return null;
            }
        }
    }

    /** #46 let-arm result: a wall, a consumed binding, or a (possibly
     * rewritten) rhs for the ordinary let path. */
    private record TdgLet(@com.legend.Nullable Outcome wall, @com.legend.Nullable ValueSpecification rhs,
            boolean consumed) {
    }

    /** A let-bound SETUP HELPER (a corpus function whose body issues
     * executeInDb DDL/inserts — {@code let runtime = model::setUp()})
     * runs NOW for its side effects through the platform; the binding
     * itself still rides lazily (its value is the runtime handle).
     * Returns null normally, an Outcome wall on compile failure. */
    private static @com.legend.Nullable Outcome letSetupArm(ValueSpecification rhs,
            Map<String, ValueSpecification> lets,
            Map<String, com.legend.testdatagen.TestDataGenerator.Result> tdg,
            ModelContext ctx, ImportScope imports, String runtimeFqn,
            Connection conn, List<String> seedFailures)
            throws java.sql.SQLException {
        if (!(rhs instanceof AppliedFunction af)) {
            return null;
        }
        var fd = ctx.findFunctionDefinition(af.function());
        if (fd.isEmpty()) {
            for (String c : af.candidateFqns()) {
                fd = ctx.findFunctionDefinition(c);
                if (fd.isPresent()) {
                    break;
                }
            }
        }
        if (fd.isEmpty() || !hasExecuteInDb(fd.get().body())) {
            return null;
        }
        try {
            Compiler.executeResolved(NameResolver.resolveQuery(
                    java.util.Objects.requireNonNull(TestDataGenForm
                            .inlineReads(subst(rhs, lets), tdg)),
                    imports, ctx.elementFqns()),
                    ctx, runtimeFqn, conn,
                    seedFailures == null ? null : seedFailures::add);
            return null;
        } catch (com.legend.error.NotImplementedException
                | com.legend.error.LegendCompileException e) {
            return new Outcome.Unsupported("let-bound setup: "
                    + String.valueOf(e.getMessage()).split("\\n")[0]);
        }
    }

    /** The lazy binding a RAN setup helper leaves behind: its RETURN
     * EXPRESSION (body's last statement, own lets substituted forward,
     * executed side-effect statements dropped). A raw multi-statement
     * call would hit the inliner's non-let wall when a consumer reads
     * the binding — but the statements already ran through the platform
     * (letSetupArm), so the value IS the remainder. 0-arg helpers only;
     * anything else keeps the raw call (walls stay honest). */
    private static @com.legend.Nullable ValueSpecification purifiedSetup(ValueSpecification rhs,
            ModelContext ctx) {
        if (!(rhs instanceof AppliedFunction af)
                || !af.parameters().isEmpty()) {
            return rhs;
        }
        var fd = ctx.findFunctionDefinition(af.function());
        if (fd.isEmpty()) {
            for (String c : af.candidateFqns()) {
                fd = ctx.findFunctionDefinition(c);
                if (fd.isPresent()) {
                    break;
                }
            }
        }
        if (fd.isEmpty() || fd.get().body().isEmpty()
                || !fd.get().parameters().isEmpty()) {
            return rhs;
        }
        List<ValueSpecification> body = fd.get().body();
        // ONLY the genuine setup shape purifies: statement-position
        // executeInDb side effects (the setUp() DDL/seed idiom). An
        // extension BUILDER whose executeInDb hides inside constructor
        // lambdas keeps its raw call — inlining its body would drag
        // module-private references into the consumer's compile scope.
        boolean setupShape = false;
        for (int i = 0; i < body.size() - 1; i++) {
            if (body.get(i) instanceof AppliedFunction sf
                    && !sf.function().equals("letFunction")
                    && hasExecuteInDb(List.of(body.get(i)))) {
                setupShape = true;
                break;
            }
        }
        if (!setupShape) {
            return rhs;
        }
        Map<String, ValueSpecification> inner = new java.util.LinkedHashMap<>();
        for (int i = 0; i < body.size() - 1; i++) {
            if (body.get(i) instanceof AppliedFunction lf
                    && lf.function().equals("letFunction")
                    && lf.parameters().size() == 2
                    && lf.parameters().get(0) instanceof CString ln) {
                inner.put(ln.value(),
                        substitute(lf.parameters().get(1), inner));
            }
            // non-let side-effect statements already executed — dropped
        }
        ValueSpecification last = body.get(body.size() - 1);
        if (last instanceof AppliedFunction lf2
                && lf2.function().equals("letFunction")
                && lf2.parameters().size() == 2) {
            last = lf2.parameters().get(1);
        }
        return substitute(last, inner);
    }

    private static boolean hasExecuteInDb(List<ValueSpecification> body) {
        for (ValueSpecification v : body) {
            if (v instanceof AppliedFunction af
                    && (simpleName(af.function()).equals("executeInDb")
                            || hasExecuteInDb(af.parameters()))) {
                return true;
            }
            if (v instanceof AppliedFunction af2
                    && hasExecuteInDb(af2.parameters())) {
                return true;
            }
        }
        return false;
    }

    /** Test-level lets the plan lambda reads, injected as LEADING
     * lambda-local lets in first-use order (engine inScopeVars — each
     * prints as an Allocation node). */
    private static @com.legend.Nullable AppliedFunction injectOpenLets(AppliedFunction ep,
            Map<String, ValueSpecification> lets) {
        if (!(ep.parameters().get(0) instanceof LambdaFunction plam)) {
            return ep;
        }
        java.util.LinkedHashSet<String> open = new java.util.LinkedHashSet<>();
        java.util.Set<String> bound = new java.util.HashSet<>();
        plam.parameters().forEach(p -> bound.add(p.name()));
        for (ValueSpecification st : plam.body()) {
            collectOpenVars(st, lets.keySet(), bound, open);
            if (st instanceof AppliedFunction lfn
                    && lfn.function().equals("letFunction")
                    && lfn.parameters().size() == 2
                    && lfn.parameters().get(0) instanceof CString ln) {
                bound.add(ln.value());
            }
        }
        if (open.isEmpty()) {
            return ep;
        }
        List<ValueSpecification> body = new ArrayList<>();
        for (String n : open) {
            body.add(new AppliedFunction("letFunction", List.of(
                    new CString(n), substitute(lets.get(n), lets))));
        }
        body.addAll(plam.body());
        List<ValueSpecification> ps = new ArrayList<>(ep.parameters());
        ps.set(0, new LambdaFunction(plam.parameters(), body));
        return new AppliedFunction(ep.function(), ps);
    }

    private static void collectOpenVars(ValueSpecification v,
            java.util.Set<String> lets, java.util.Set<String> bound,
            java.util.LinkedHashSet<String> out) {
        switch (v) {
            case Variable var -> {
                if (lets.contains(var.name()) && !bound.contains(var.name())) {
                    out.add(var.name());
                }
            }
            case AppliedFunction af -> af.parameters()
                    .forEach(x -> collectOpenVars(x, lets, bound, out));
            case AppliedProperty ap -> collectOpenVars(ap.receiver(),
                    lets, bound, out);
            case PureCollection pc -> pc.values()
                    .forEach(x -> collectOpenVars(x, lets, bound, out));
            case LambdaFunction lf -> {
                java.util.Set<String> inner = new java.util.HashSet<>(bound);
                lf.parameters().forEach(p -> inner.add(p.name()));
                lf.body().forEach(x -> collectOpenVars(x, lets, inner, out));
            }
            case NewInstance ni -> ni.properties().values().forEach(
                    ke -> collectOpenVars(ke.value(), lets, bound, out));
            default -> {
            }
        }
    }

    /** The #46 let-arm rewrites: a generateTestData binding runs NOW
     * (setup statements above already executed — engine parity, all data
     * work in the database); testDataGen reads inline as literals so the
     * corpus's loadAndTestExecution tail runs through the platform
     * unchanged; executionPlan bindings are PLAN-TRANSPARENT — the handle
     * only ever flows into {@code $plan->execute(...)}, which re-forms as
     * the execute native (identical row semantics; plan text is never
     * inspected here). */
    private static TdgLet tdgLetArm(CString name, @com.legend.Nullable ValueSpecification rhs,
            Map<String, ValueSpecification> lets,
            Map<String, com.legend.testdatagen.TestDataGenerator.Result> tdg,
            Map<String, AppliedFunction> planLets,
            java.util.Set<String> planText, ModelContext ctx,
            ImportScope imports, Connection conn)
            throws java.sql.SQLException {
        if (TestDataGenForm.hasPlanGenerate(rhs)) {
            // the binding rides lets so a plan-text assert can
            // substitute $plan back to the planTestDataGeneration call
            // (checkTdgAssert builds the MultiResultSequence text);
            // wrapper-only tests that never read the plan keep their
            // engine-parity pass
            planText.add(name.value());
            lets.put(name.value(), rhs);
            return new TdgLet(null, null, true);
        }
        if (TestDataGenForm.hasSeedDataString(rhs)) {
            try {
                tdg.put(name.value(), TestDataGenForm.runSeedDataString(
                        rhs, ctx, imports, conn));
            } catch (com.legend.error.NotImplementedException e) {
                return new TdgLet(new Outcome.Unsupported(String.valueOf(
                        e.getMessage()).split("\\n")[0]), null, false);
            }
            return new TdgLet(null, null, true);
        }
        if (TestDataGenForm.hasCsvCensus(rhs)) {
            try {
                tdg.put(name.value(),
                        TestDataGenForm.runCsvCensus(rhs, ctx, imports));
            } catch (com.legend.error.NotImplementedException e) {
                return new TdgLet(new Outcome.Unsupported(String.valueOf(
                        e.getMessage()).split("\\n")[0]), null, false);
            }
            return new TdgLet(null, null, true);
        }
        if (TestDataGenForm.hasGenerate(rhs)) {
            try {
                tdg.put(name.value(), TestDataGenForm.run(rhs, ctx,
                        imports, conn));
            } catch (com.legend.error.NotImplementedException e) {
                return new TdgLet(new Outcome.Unsupported(String.valueOf(
                        e.getMessage()).split("\\n")[0]), null, false);
            }
            return new TdgLet(null, null, true);
        }
        rhs = TestDataGenForm.inlineReads(rhs, tdg);
        if (rhs instanceof AppliedFunction ep
                && simpleName(ep.function()).equals("executionPlan")
                && (ep.function().equals("executionPlan")
                        || ep.function().startsWith("meta::"))
                && ep.parameters().size() >= 3) {
            // OPEN VARIABLES become Allocations (engine inScopeVars): a
            // test-level let the plan lambda reads is injected as a
            // LAMBDA-LOCAL leading let — the local shadows the outer
            // binding under substitute(), so the plan printer sees the
            // let (name + value) instead of an inlined literal
            ep = injectOpenLets(ep, lets);
            // recorded for the plan->execute desugar; the binding ALSO
            // rides the ordinary lazy let so planToString reads type
            // through the platform (the #47 plan-text K-native)
            planLets.put(name.value(), ep);
            return new TdgLet(null, ep, false);
        }
        // the plan binding ALSO rides the lazy lets (planToString typing),
        // so rhs arrives with $plan already substituted to the
        // executionPlan CALL — match either spelling
        AppliedFunction planSrc = null;
        if (rhs instanceof AppliedFunction pe0
                && simpleName(pe0.function()).equals("execute")
                && !pe0.parameters().isEmpty()) {
            ValueSpecification p0 = pe0.parameters().get(0);
            if (p0 instanceof Variable pv && planLets.containsKey(pv.name())) {
                planSrc = planLets.get(pv.name());
            } else if (p0 instanceof AppliedFunction epc
                    && simpleName(epc.function()).equals("executionPlan")
                    && epc.parameters().size() >= 3) {
                planSrc = epc;
            }
        }
        if (planSrc != null && rhs instanceof AppliedFunction pe) {
            if (pe.parameters().size() >= 2
                    && !(substitute(pe.parameters().get(1), lets)
                            instanceof PureCollection epc
                            && epc.values().isEmpty())) {
                return new TdgLet(new Outcome.Unsupported(
                        "plan->execute with bound parameters"), null, false);
            }
            AppliedFunction plan = planSrc;
            rhs = new AppliedFunction("execute",
                    List.of(plan.parameters().get(0),
                            plan.parameters().get(1),
                            plan.parameters().get(2),
                            plan.parameters().size() > 3
                                    ? plan.parameters().get(3)
                                    : new PureCollection(List.of())));
            // rides the caller's exec-forward arm
        }
        return new TdgLet(null, rhs, false);
    }

    /** Plan-text literal compare (toSQLString doctrine) with NAMED walls
     * staying SHAPE. 3-arg H2Compatible = (legacy, h2New, actual): the
     * ACTUAL is always LAST, and EITHER golden may match (h2New is our
     * own dialect generation). */


    /** testDataGen assert arms (#46): assertTestData is the ROW contract
     * (typed set compare in the database), .sqls text is engine H2 SQL —
     * advisory (the golden-SQL doctrine), .sqls COUNTS verify. Returns
     * {@link #NOT_TDG_MARKER} when the assert doesn't touch a
     * generateTestData binding. */
    private static @com.legend.Nullable String checkTdgAssert(AppliedFunction af,
            List<ValueSpecification> args,
            Map<String, ValueSpecification> lets,
            Map<String, com.legend.testdatagen.TestDataGenerator.Result> tdg,
            java.util.Set<String> planText,
            List<ValueSpecification> execStmts, java.util.Set<String> execVars,
            Map<String, ValueSpecification> execChains, ModelContext ctx,
            ImportScope imports, String runtimeFqn, Connection conn)
            throws java.sql.SQLException {
        switch (simpleName(af.function())) {
            case "assertTestData" -> {
                if (args.size() != 3) {
                    return UNSUPPORTED_MARKER;
                }
                TestDataGenForm.Read r = TestDataGenForm.read(
                        subst(args.get(1), lets));
                var bound = r == null ? null : tdg.get(r.var());
                String expected = TestDataGenForm.foldString(
                        subst(args.get(0), lets));
                if (r == null || bound == null || expected == null
                        || !"dataCsvString".equals(r.kind())
                        || !(substitute(args.get(2), lets)
                                instanceof com.legend.protocol.spec
                                        .PackageableElementPtr dbp)) {
                    return UNSUPPORTED_MARKER;
                }
                try {
                    return com.legend.testdatagen.TestDataGenerator
                            .compareCsv(ctx, java.util.Objects.requireNonNull(
                                    TestDataGenForm.qualify(
                                            dbp.fullPath(), ctx, imports),
                                    "unresolvable db reference"),
                                    expected, java.util.Objects.requireNonNull(
                                            bound.dataCsvString(),
                                            "tdg binding without csv"),
                                    conn);
                } catch (com.legend.error.NotImplementedException e) {
                    return UNSUPPORTED_MARKER;
                }
            }
            case "assertSqlEquals" -> {
                TestDataGenForm.Read r = TestDataGenForm.read(
                        subst(args.size() == 2 ? args.get(1)
                                : args.get(0), lets));
                return r != null && tdg.containsKey(r.var())
                        ? ADVISORY_MARKER : UNSUPPORTED_MARKER;
            }
            default -> {
            }
        }
        if (!tdg.isEmpty() && !args.isEmpty()) {
            TestDataGenForm.Read r0 = TestDataGenForm.read(
                    subst(args.get(0), lets));
            if (r0 != null && tdg.containsKey(r0.var())
                    && "sqls".equals(r0.kind())) {
                if (simpleName(af.function()).equals("assertSize")
                        && args.size() == 2) {
                    Object n = evalScalar(args.get(1), lets, execStmts,
                            execVars, execChains, ctx, imports,
                            runtimeFqn, conn);
                    long actual = tdg.get(r0.var()).sqls().size();
                    return n instanceof Number num
                            && num.longValue() == actual ? null
                            : "assertSize(sqls): expected " + n + ", got "
                                    + actual;
                }
                return ADVISORY_MARKER;   // SQL-text reads: engine H2 text
            }
            for (ValueSpecification a : args) {
                TestDataGenForm.Read r = TestDataGenForm.read(
                        subst(a, lets));
                if (r != null && tdg.containsKey(r.var())) {
                    return ADVISORY_MARKER;
                }
            }
        }
        if (simpleName(af.function()).equals("assertEquals")
                && args.size() == 2
                && args.get(1) instanceof Variable sv
                && tdg.get(sv.name()) != null
                && tdg.get(sv.name()).tables() == null
                && tdg.get(sv.name()).sqls().isEmpty()
                && tdg.get(sv.name()).dataCsvString() != null) {
            // a STRING-product binding (generateSeedDataString): literal
            String got2 = java.util.Objects.requireNonNull(
                    java.util.Objects.requireNonNull(tdg.get(sv.name())).dataCsvString());
            String exp2 = TestDataGenForm.foldString(
                    subst(args.get(0), lets));
            return got2.equals(exp2) ? null
                    : "assertEquals: expected " + exp2 + ", got " + got2;
        }
        String cz = csvCensusAssert(af, args, lets, tdg);
        if (cz != NOT_TDG_MARKER) {
            return cz;
        }
        if (!planText.isEmpty()) {
            for (ValueSpecification arg : args) {
                if (referencesAnyVar(arg, planText)) {
                    String text;
                    try {
                        text = TestDataGenForm.planText(
                                subst(arg, lets), ctx, imports);
                    } catch (com.legend.error.NotImplementedException e) {
                        if (System.getenv("LL_TMP_DEBUG") != null) {
                            System.err.println("[tdg-plan-wall] " + e);
                        }
                        return unsupported(String.valueOf(
                                e.getMessage()).split("\\n")[0]);
                    }
                    if (text == null) {
                        return UNSUPPORTED_MARKER;
                    }
                    // literal plan-text compare — EITHER golden of the
                    // H2Compatible pair may match
                    for (ValueSpecification g : args) {
                        if (g == arg) {
                            continue;
                        }
                        if (text.equals(TestDataGenForm.foldString(
                                subst(g, lets)))) {
                            return null;
                        }
                    }
                    return "assertEquals: expected "
                            + TestDataGenForm.foldString(
                                    subst(args.get(0), lets))
                            + ", got " + text;
                }
            }
        }
        return NOT_TDG_MARKER;
    }

    /** getRelationalCSVDataFromQuery reads: {@code $x.tables->size()}
     * and the schema/table/values map-join idiom — host-side over the
     * census triples. */
    private static @com.legend.Nullable String csvCensusAssert(AppliedFunction af,
            List<ValueSpecification> args,
            Map<String, ValueSpecification> lets,
            Map<String, com.legend.testdatagen.TestDataGenerator.Result> tdg) {
        if (!simpleName(af.function()).equals("assertEquals")
                || args.size() != 2) {
            return NOT_TDG_MARKER;
        }
        ValueSpecification actual = args.get(1);
        if (actual instanceof AppliedFunction sz
                && simpleName(sz.function()).equals("size")
                && sz.parameters().size() == 1
                && sz.parameters().get(0) instanceof AppliedProperty tp
                && tp.property().equals("tables")
                && tp.receiver() instanceof Variable v
                && tdg.get(v.name()) != null
                && tdg.get(v.name()).tables() != null) {
            long got = java.util.Objects.requireNonNull(
                    java.util.Objects.requireNonNull(tdg.get(v.name())).tables()).size();
            if (!(args.get(0)
                    instanceof com.legend.protocol.spec.CInteger ci)) {
                return NOT_TDG_MARKER;
            }
            return ci.value().longValue() == got ? null
                    : "assertEquals: expected " + ci.value() + ", got "
                            + got;
        }
        if (actual instanceof AppliedFunction js
                && simpleName(js.function()).equals("joinStrings")
                && js.parameters().size() == 2
                && js.parameters().get(1) instanceof CString sep
                && js.parameters().get(0) instanceof AppliedFunction mp2
                && simpleName(mp2.function()).equals("map")
                && mp2.parameters().size() == 2
                && mp2.parameters().get(1) instanceof LambdaFunction ml
                && java.util.Objects.equals(propertyReadOrder(ml),
                        List.of("schema", "table", "values"))) {
            // the source may carry an optional sortBy(schema+table)
            ValueSpecification src = mp2.parameters().get(0);
            boolean sorted = false;
            if (src instanceof AppliedFunction sb
                    && simpleName(sb.function()).equals("sortBy")
                    && sb.parameters().size() == 2
                    && sb.parameters().get(1) instanceof LambdaFunction sl
                    && java.util.Objects.equals(propertyReadOrder(sl),
                            List.of("schema", "table"))) {
                sorted = true;
                src = sb.parameters().get(0);
            }
            if (!(src instanceof AppliedProperty tp2
                    && tp2.property().equals("tables")
                    && tp2.receiver() instanceof Variable v2
                    && tdg.get(v2.name()) != null
                    && tdg.get(v2.name()).tables() != null)) {
                return NOT_TDG_MARKER;
            }
            List<String[]> triples =
                    new ArrayList<>(java.util.Objects.requireNonNull(
                            java.util.Objects.requireNonNull(tdg.get(v2.name())).tables()));
            if (sorted) {
                triples.sort(java.util.Comparator.comparing(
                        t -> t[0] + t[1]));
            }
            String got = triples.stream()
                    .map(t -> t[0] + "\n" + t[1] + "\n" + t[2])
                    .collect(java.util.stream.Collectors
                            .joining(sep.value()));
            String exp = TestDataGenForm.foldString(
                    subst(args.get(0), lets));
            return got.equals(exp) ? null
                    : "assertEquals: expected " + exp + ", got " + got;
        }
        return NOT_TDG_MARKER;
    }

    /** The lambda body's property-read names in source order (the
     * census join idiom pin — anything else stays a wall). */
    private static @com.legend.Nullable List<String> propertyReadOrder(LambdaFunction ml) {
        List<String> out = new ArrayList<>();
        java.util.ArrayDeque<ValueSpecification> work =
                new java.util.ArrayDeque<>(ml.body());
        while (!work.isEmpty()) {
            ValueSpecification v = work.poll();
            if (v instanceof AppliedProperty ap) {
                out.add(ap.property());
            } else if (v instanceof AppliedFunction f) {
                // left-to-right over plus chains
                for (int i = f.parameters().size() - 1; i >= 0; i--) {
                    work.addFirst(f.parameters().get(i));
                }
            } else if (v instanceof PureCollection pc) {
                for (int i = pc.values().size() - 1; i >= 0; i--) {
                    work.addFirst(pc.values().get(i));
                }
            }
        }
        return out;
    }

    private static boolean referencesAnyVar(ValueSpecification v,
            java.util.Set<String> names) {
        if (v instanceof Variable var) {
            return names.contains(var.name());
        }
        if (v instanceof AppliedFunction af2) {
            for (ValueSpecification p : af2.parameters()) {
                if (referencesAnyVar(p, names)) {
                    return true;
                }
            }
        } else if (v instanceof AppliedProperty ap) {
            return referencesAnyVar(ap.receiver(), names);
        } else if (v instanceof PureCollection pc) {
            for (ValueSpecification e : pc.values()) {
                if (referencesAnyVar(e, names)) {
                    return true;
                }
            }
        } else if (v instanceof LambdaFunction lf) {
            for (ValueSpecification b2 : lf.body()) {
                if (referencesAnyVar(b2, names)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** null = held; ADVISORY_MARKER = golden-SQL; UNSUPPORTED_MARKER; else the failure text. */
    private static @com.legend.Nullable String checkAssert(AppliedFunction af,
            Map<String, ValueSpecification> lets,
            List<ValueSpecification> execStmts, java.util.Set<String> execVars,
            Map<String, ValueSpecification> execChains,
            ModelContext ctx, ImportScope imports, String runtimeFqn, Connection conn,
            boolean emptinessUnverifiable,
            Map<String, com.legend.testdatagen.TestDataGenerator.Result> tdg,
            java.util.Set<String> planText)
            throws java.sql.SQLException {
        List<ValueSpecification> args = af.parameters();
        // testDataGen reads (#46) route to the bound generator result —
        // extracted arm (checkAssert length guardrail)
        String tdgOut = checkTdgAssert(af, args, lets, tdg, planText,
                execStmts, execVars, execChains, ctx, imports, runtimeFqn,
                conn);
        if (tdgOut != NOT_TDG_MARKER) {
            return tdgOut;
        }
        String pkOut = pkAssert(af, args, ctx);
        if (pkOut != NOT_TDG_MARKER) {
            return pkOut;
        }
        switch (simpleName(af.function())) {
            case "assert", "assertFalse" -> {
                if (args.isEmpty()) {
                    return UNSUPPORTED_MARKER;
                }
                if (PlanAsserts.containsPlanToString(subst(args.get(0), lets))) {
                    return PlanAsserts.planPredicateAssert(af, args, lets, execStmts,
                            execVars, execChains, ctx, imports,
                            runtimeFqn, conn);
                }
                if (containsSqlText(args.get(0))) {
                    // predicate PURELY over golden SQL text is advisory; a
                    // MIXED assert (sql text AND value reads) must not have
                    // its value conjuncts silently skipped (audit 9)
                    return containsValuesRead(args.get(0))
                            ? UNSUPPORTED_MARKER : ADVISORY_MARKER;
                }
                if (emptinessUnverifiable) {
                    // seeds failed: a predicate like isEmpty(...) would
                    // hollow-PASS over the tables the failed seeds left
                    // empty — same guard as the equals/size-0 spellings
                    // (audit 16 F4); assert over verifiable state is rare
                    // enough that blanket-unsupported stays honest
                    return UNSUPPORTED_MARKER;
                }
                // connection-equality contract folds HOST-side (ConnEquality)
                Object v = ConnEquality.tryEval(subst(args.get(0), lets), ctx, imports);
                v = v != null ? v : evalScalar(args.get(0), lets, execStmts, execVars, execChains, ctx, imports, runtimeFqn, conn);
                boolean expect = af.function().equals("assert");
                return Boolean.valueOf(expect).equals(v) ? null
                        : "assert" + (expect ? "" : "False") + " did not hold (" + v + ")";
            }
            case "assertEquals", "assertEq", "assertEqualsH2Compatible", "assertNotEquals" -> {
                if (args.size() < 2) {
                    return UNSUPPORTED_MARKER;
                }
                // plan-text asserts read planToString — their goldens
                // CONTAIN sql text but the compare is the LITERAL plan
                // string through the K-native (toSQLString doctrine):
                // skip the golden-SQL advisory routing entirely
                if (PlanAsserts.wantsPlanText(args, lets)) {
                    return PlanAsserts.planTextAssert(args, lets, execStmts, execVars,
                            execChains, ctx, imports, runtimeFqn, conn);
                } else {
                // legacy 3-arg H2-compat: (legacySql, h2NewSql, actual) —
                // the NEW golden is H2 2.1.214, exactly the advisory
                // second target's dialect: verify by ROWS through it
                if (args.size() == 3 && simpleName(af.function())
                        .equals("assertEqualsH2Compatible")) {
                    return sqlTextVerify(List.of(args.get(1), args.get(2)),
                            lets, execStmts, execVars, execChains, ctx,
                            imports, runtimeFqn, conn);
                }
                // golden-SQL spellings are advisory: our SQL is DuckDB's.
                // A MIXED side (sql text AND value reads) is loud instead —
                // skipping its value conjuncts would be silent (audit 9).
                // #67: a PURE golden-SQL assert upgrades to ROW-VERIFIED
                // when the H2 second target can replay the seeds and run
                // the golden (h2Upgrade; unverifiable stays advisory).
                if (containsSqlText(args.get(args.size() - 1))
                        || containsSqlText(args.get(0))) {
                    if (containsValuesRead(args.get(0))
                            || containsValuesRead(args.get(args.size() - 1))) {
                        return UNSUPPORTED_MARKER;
                    }
                    return sqlTextVerify(args, lets, execStmts, execVars,
                            execChains, ctx, imports, runtimeFqn, conn);
                }
                }
                Eval e = eval(args.get(0), lets, execStmts, execVars, execChains, ctx, imports, runtimeFqn, conn);
                if (emptinessUnverifiable && e.size() == 0) {
                    // seeds failed: an EMPTY expectation would hollow-PASS
                    // against the empty tables (audit 9 — the assertSize-0/
                    // assertEmpty guard alone missed the equals spellings)
                    return UNSUPPORTED_MARKER;
                }
                Eval a = eval(args.get(1), lets, execStmts, execVars, execChains, ctx, imports, runtimeFqn, conn);
                boolean equal = compare(e, a, /* ordered */ true);
                if (af.function().equals("assertNotEquals")) {
                    return equal ? "assertNotEquals: both sides are " + e.render() : null;
                }
                if (!equal && System.getenv("LEGEND_LITE_CMP_DEBUG") != null) {
                    System.err.println("[cmp] assertEquals FAIL arg0=" + args.get(0)
                            + "\n[cmp] e.sortedChain=" + e.sortedChain()
                            + " a.sortedChain=" + a.sortedChain()
                            + "\n[cmp] e types=" + e.values().stream().map(o ->
                                    o == null ? "null" : o.getClass().getSimpleName()).toList()
                            + "\n[cmp] a types=" + a.values().stream().map(o ->
                                    o == null ? "null" : o.getClass().getSimpleName()).toList());
                }
                return equal ? null : "assertEquals: expected " + e.render()
                        + ", got " + a.render();
            }
            case "assertSameElements" -> {
                if (args.size() != 2) {
                    return UNSUPPORTED_MARKER;
                }
                Eval e = eval(args.get(0), lets, execStmts, execVars, execChains, ctx, imports, runtimeFqn, conn);
                if (emptinessUnverifiable && e.size() == 0) {
                    return UNSUPPORTED_MARKER;   // see the assertEquals guard
                }
                Eval a = eval(args.get(1), lets, execStmts, execVars, execChains, ctx, imports, runtimeFqn, conn);
                return compare(e, a, /* ordered */ false) ? null
                        : "assertSameElements: expected " + e.render() + ", got " + a.render();
            }
            case "assertContains" -> {
                return assertContainsCheck(args, lets, execStmts, execVars, execChains,
                        ctx, imports, runtimeFqn, conn, emptinessUnverifiable);
            }
            case "assertEqWithinTolerance" -> {
                if (args.size() != 3) {
                    return UNSUPPORTED_MARKER;
                }
                Object e = evalScalar(args.get(0), lets, execStmts, execVars, execChains, ctx, imports,
                        runtimeFqn, conn);
                Object a = evalScalar(args.get(1), lets, execStmts, execVars, execChains, ctx, imports,
                        runtimeFqn, conn);
                Object tol = evalScalar(args.get(2), lets, execStmts, execVars, execChains, ctx,
                        imports, runtimeFqn, conn);
                if (!(e instanceof Number en && a instanceof Number an
                        && tol instanceof Number tn)) {
                    return "assertEqWithinTolerance: non-numeric operand ("
                            + e + "/" + (e == null ? "null" : e.getClass().getSimpleName())
                            + ", " + a + "/" + (a == null ? "null" : a.getClass().getSimpleName())
                            + ", " + tol + "/" + (tol == null ? "null" : tol.getClass().getSimpleName())
                            + ")";
                }
                return Math.abs(en.doubleValue() - an.doubleValue())
                        <= tn.doubleValue() ? null
                        : "assertEqWithinTolerance: expected " + e + " ± "
                                + tol + ", got " + a;
            }
            case "assertSize" -> {
                if (args.size() != 2) {
                    return UNSUPPORTED_MARKER;
                }
                Object n = evalScalar(args.get(1), lets, execStmts, execVars, execChains, ctx, imports,
                        runtimeFqn, conn);
                if (args.get(0) instanceof AppliedProperty vp
                        && vp.property().equals("values")
                        && vp.receiver() instanceof Variable rv
                        && execChains.containsKey(rv.name())) {
                    return carrierSizeCheck(n, args.get(0), lets, execStmts,
                            execVars, execChains, ctx, imports, runtimeFqn, conn);
                }
                if (emptinessUnverifiable && n instanceof Number zn && zn.longValue() == 0) {
                    return UNSUPPORTED_MARKER;
                }
                Eval a = eval(args.get(0), lets, execStmts, execVars, execChains, ctx, imports, runtimeFqn, conn);
                long actual = a.size();
                return (n instanceof Number num && num.longValue() == actual) ? null
                        : "assertSize: expected " + n + ", got " + actual;
            }
            case "assertEmpty" -> {
                if (args.isEmpty() || args.size() > 2) {
                    return UNSUPPORTED_MARKER;   // optional message arg
                }
                if (emptinessUnverifiable) {
                    return UNSUPPORTED_MARKER;
                }
                Eval a = eval(args.get(0), lets, execStmts, execVars, execChains, ctx, imports, runtimeFqn, conn);
                return a.size() == 0 ? null : "assertEmpty: got " + a.size() + " values";
            }
            case "assertNotEmpty" -> {
                if (args.isEmpty() || args.size() > 2) {
                    return UNSUPPORTED_MARKER;   // optional message arg
                }
                Eval a = eval(args.get(0), lets, execStmts, execVars, execChains, ctx, imports, runtimeFqn, conn);
                return a.size() > 0 ? null : "assertNotEmpty: got 0 values";
            }
            case "assertInstanceOf" -> {
                return instanceOfAssert(args, lets, execStmts, execVars,
                        execChains, ctx, imports, runtimeFqn, conn);
            }
            case "assertTdsEquivalent" -> {
                return args.size() == 3 || args.size() == 4
                        ? TdsEquivalence.assertArm(args, lets, execStmts, execVars,
                                execChains, ctx, imports, runtimeFqn, conn)
                        : UNSUPPORTED_MARKER;
            }
            case "assertSameSQL" -> {
                // planToString/planWalk operands are LITERAL plan-text
                // compares (same pre-check as assertEquals)
                if (!args.isEmpty() && PlanAsserts.wantsPlanText(args, lets)) {
                    return PlanAsserts.planTextAssert(args, lets,
                            execStmts, execVars, execChains, ctx,
                            imports, runtimeFqn, conn);
                }
                return sqlTextVerify(af.parameters(), lets, execStmts,
                        execVars, execChains, ctx, imports, runtimeFqn,
                        conn);
            }
            case "assertJsonStringsEqual" -> {
                // engine semantics: object keys order-INSENSITIVE, arrays
                // order-SENSITIVE — deep equality over PARSED structures
                if (args.size() != 2) {
                    return UNSUPPORTED_MARKER;
                }
                // canon wrappers = identity; JSONArray sort host-side
                var sc0 = JsonAssertCanon.sortCanon(subst(args.get(0), lets));
                var sc1 = JsonAssertCanon.sortCanon(subst(args.get(1), lets));
                args = java.util.List.of(
                        stripJsonCanon(sc0 != null ? sc0.inner() : args.get(0)),
                        stripJsonCanon(sc1 != null ? sc1.inner() : args.get(1)));
                Eval e = eval(args.get(0), lets, execStmts, execVars, execChains, ctx, imports,
                        runtimeFqn, conn);
                if (emptinessUnverifiable) {
                    return UNSUPPORTED_MARKER;
                }
                Eval a = eval(args.get(1), lets, execStmts, execVars, execChains, ctx, imports,
                        runtimeFqn, conn);
                Object expected = jsonValueOf(e);
                Object actual = jsonValueOf(a);
                if (expected == null || actual == null) {
                    return UNSUPPORTED_MARKER;
                }
                if (sc0 != null) {
                    expected = JsonAssertCanon.sortByKey(expected, sc0.key());
                }
                if (sc1 != null) {
                    actual = JsonAssertCanon.sortByKey(actual, sc1.key());
                }
                // pure's [x] ≡ x value semantics at the ROOT: the engine
                // serializes a one-element result as the bare object; our
                // envelope always arrays. Bridge exactly that case — an
                // object-shaped expectation against a singleton array.
                if (!(expected instanceof List) && actual instanceof List<?> al
                        && al.size() == 1) {
                    actual = al.get(0);
                }
                String diff = jsonDiffPath(expected, actual, "$");
                return diff == null ? null
                        : "assertJsonStringsEqual: FIRST DIFF at " + diff
                                + " | expected "
                                + abbreviate(String.valueOf(expected))
                                + ", got " + abbreviate(String.valueOf(actual));
            }
            default -> {
                return UNSUPPORTED_MARKER;
            }
        }
    }

    /** Harness vocabulary matches by SIMPLE name only for BARE or
     * meta::-qualified spellings — a user function my::pkg::assertFoo
     * must route to the platform, never be hijacked (audit 17). */
    static boolean harnessVocabName(String fn) {
        return !fn.contains("::") || fn.startsWith("meta::");
    }

    /** The WithVariables wrapper idiom (runLegendTest($f, pairs,
     * expected) / runTest($f, vars, sql, count), $f a PARAMETERIZED query
     * lambda): β-bind pair values over the params and return the
     * wrapper's assertions in spellings the harness already evaluates
     * (flattened .rows.values; advisory golden SQL + row count). Null =
     * not this idiom (the caller keeps its wall). */
    private static @com.legend.Nullable List<ValueSpecification> etaExpandWrapper(
            AppliedFunction wrap, Map<String, ValueSpecification> lets) {
        String fn = simpleName(wrap.function());
        List<ValueSpecification> args = wrap.parameters();
        boolean legend = fn.equals("runLegendTest") && args.size() == 3;
        boolean paginate = fn.equals("runTest") && args.size() == 4;
        if (!legend && !paginate) {
            return null;
        }
        if (!(substitute(args.get(0), lets) instanceof LambdaFunction lf)
                || lf.parameters().isEmpty() || lf.body().size() != 1) {
            return null;
        }
        ValueSpecification varsArg = substitute(args.get(1), lets);
        List<ValueSpecification> pairSpecs = varsArg instanceof PureCollection pc
                ? pc.values() : List.of(varsArg);
        Map<String, ValueSpecification> binding = new LinkedHashMap<>();
        for (ValueSpecification p : pairSpecs) {
            if (!(p instanceof AppliedFunction pf)
                    || !simpleName(pf.function()).equals("pair")
                    || pf.parameters().size() != 2
                    || !(pf.parameters().get(0) instanceof CString key)) {
                return null;
            }
            binding.put(key.value(), pf.parameters().get(1));
        }
        for (var prm : lf.parameters()) {
            if (!binding.containsKey(prm.name())) {
                return null;
            }
        }
        ValueSpecification bound = subst(lf.body().get(0), binding);
        if (legend) {
            return List.of(new AppliedFunction("assertEquals", List.of(
                    args.get(2),
                    new AppliedProperty(
                            new AppliedProperty(bound, "rows"), "values"))));
        }
        return List.of(
                new AppliedFunction("assertSameSQL",
                        List.of(args.get(2), bound)),
                new AppliedFunction("assertSize",
                        List.of(bound, args.get(3))));
    }


    /** The per-driver golden loop body — null when every pair verified
     * clean; counters = {verified, advisory} accumulate in place. */
    private static @com.legend.Nullable Outcome runPerDriverLoop(List<AppliedFunction> pairs,
            LambdaFunction perDriver, Map<String, ValueSpecification> lets,
            List<ValueSpecification> execStmts, java.util.Set<String> execVars,
            Map<String, ValueSpecification> execChains, ModelContext ctx,
            ImportScope imports, String runtimeFqn, Connection conn,
            boolean unverifiable, int[] counters)
            throws java.sql.SQLException {
            for (AppliedFunction pair : pairs) {
                String db = enumTail(pair.parameters().get(0));
                if (!"H2".equals(db) && !"DB2".equals(db)
                        && !"Composite".equals(db)) {
                    return new Outcome.Unsupported(
                            "per-driver golden loop declares"
                            + " DatabaseType." + db
                            + " — only the H2/DB2 renderers are built");
                }
            }
            for (AppliedFunction pair : pairs) {
                Map<String, ValueSpecification> loopLets =
                        new LinkedHashMap<>(lets);
                for (ValueSpecification ls : perDriver.body()) {
                    ValueSpecification s2 = substPairReads(ls,
                            perDriver.parameters().get(0).name(),
                            pair.parameters().get(0),
                            pair.parameters().get(1));
                    if (s2 instanceof AppliedFunction lf
                            && lf.function().equals("letFunction")
                            && lf.parameters().size() == 2
                            && lf.parameters().get(0)
                                    instanceof CString ln) {
                        loopLets.put(ln.value(), lf.parameters().get(1));
                        continue;
                    }
                    if (s2 instanceof AppliedFunction af2
                            && harnessVocabName(af2.function())
                            && simpleName(af2.function())
                                    .startsWith("assert")) {
                        String failure = checkAssert(af2, loopLets,
                                execStmts, execVars, execChains, ctx,
                                imports, runtimeFqn, conn,
                                unverifiable, Map.of(), java.util.Set.of());
                        if (failure == UNSUPPORTED_MARKER) {
                            String why2 = takeUnsupportedReason();
                            return new Outcome.Unsupported(
                                    "assert form '" + af2.function()
                                    + "' in a per-driver golden loop"
                                    + (why2 == null ? "" : " — " + why2));
                        }
                        if (failure == ADVISORY_MARKER) {
                            counters[1]++;
                            continue;
                        }
                        counters[0]++;
                        if (failure != null) {
                            // per-driver loop: asserts are the substance —
                            // executed stays 0, verified carries the count
                            return new Outcome.Ran(counters[0], counters[1], 0,
                                    List.of(failure));
                        }
                        continue;
                    }
                    return new Outcome.Unsupported("unrecognized"
                            + " statement in a per-driver golden loop");
                }
            }
        return null;
    }

    /** STATEMENT-position map over a literal collection of VARIABLES
     * with a (possibly multi-statement) lambda — the per-result
     * assert-block idiom ({@code [$r1,$r2]->map(r|let o=$r.values;
     * assertEquals(..);)}): HOST-side unroll, sibling of the per-driver
     * enum loop (enum literals bind the loop var, body statements splice
     * back into the work queue). Variables only — a map over computed
     * elements is a QUERY and must not unroll. */
    private static @com.legend.Nullable List<ValueSpecification> resultVarLoop(ValueSpecification stmt) {
        if (!(stmt instanceof AppliedFunction m
                && harnessVocabName(m.function())
                && simpleName(m.function()).equals("map")
                && m.parameters().size() == 2
                && m.parameters().get(0) instanceof PureCollection pc
                && !pc.values().isEmpty()
                && pc.values().stream().allMatch(v -> v instanceof Variable)
                && m.parameters().get(1) instanceof LambdaFunction lf
                && lf.parameters().size() == 1)) {
            return null;
        }
        List<ValueSpecification> out = new ArrayList<>();
        for (ValueSpecification el : pc.values()) {
            for (ValueSpecification b : lf.body()) {
                out.add(substitute(b, Map.of(lf.parameters().get(0).name(), el)));
            }
        }
        return out;
    }

    private static @com.legend.Nullable List<ValueSpecification> enumDriverLoop(
            ValueSpecification stmt) {
        ValueSpecification enumLoop = stmt;
        if (stmt instanceof AppliedFunction eqw
                && simpleName(eqw.function()).equals("equal")
                && eqw.parameters().size() == 2
                && eqw.parameters().get(0) instanceof AppliedFunction dw
                && simpleName(dw.function()).equals("distinct")
                && dw.parameters().size() == 1) {
            // the asserts INSIDE the body carry the verification
            enumLoop = dw.parameters().get(0);
        }
        if (enumLoop instanceof AppliedFunction emap
                && simpleName(emap.function()).equals("map")
                && emap.parameters().size() == 2
                && emap.parameters().get(1) instanceof LambdaFunction dl
                && dl.parameters().size() == 1) {
            ValueSpecification esrc = emap.parameters().get(0);
            List<ValueSpecification> evs = esrc instanceof PureCollection pc0
                    ? pc0.values() : List.of(esrc);
            // STRICT literal-enum elements only (DatabaseType.H2 — an
            // EnumValue or a dotted read off an element POINTER): a map
            // over an arbitrary property chain is a QUERY, and enumTail's
            // loose property match must never unroll it (the
            // testComplexOrExistsToManyProperty misfire)
            if (!evs.isEmpty() && evs.stream().allMatch(
                    x -> x instanceof com.legend.protocol.spec.EnumValue
                            || (x instanceof AppliedProperty ap0
                                && ap0.receiver() instanceof com.legend
                                    .protocol.spec.PackageableElementPtr))) {
                List<ValueSpecification> unrolled = new ArrayList<>();
                for (ValueSpecification ev : evs) {
                    for (ValueSpecification b : dl.body()) {
                        unrolled.add(substitute(b, Map.of(
                                dl.parameters().get(0).name(), ev)));
                    }
                }
                return unrolled;
            }
        }
        return null;
    }

    private static @com.legend.Nullable LambdaFunction driverPairLoop(ValueSpecification v,
            Map<String, ValueSpecification> lets,
            List<AppliedFunction> pairsOut) {
        if (!(v instanceof AppliedFunction d
                && simpleName(d.function()).equals("distinct")
                && d.parameters().size() == 1
                && d.parameters().get(0) instanceof AppliedFunction m
                && simpleName(m.function()).equals("map")
                && m.parameters().size() == 2
                && m.parameters().get(1) instanceof LambdaFunction lam
                && lam.parameters().size() == 1)) {
            return null;
        }
        ValueSpecification src = m.parameters().get(0);
        if (src instanceof Variable var) {
            src = lets.get(var.name());
        }
        List<ValueSpecification> elems = src instanceof PureCollection pc
                ? pc.values() : src == null ? List.of() : List.of(src);
        if (elems.isEmpty()) {
            return null;
        }
        for (ValueSpecification e : elems) {
            if (e instanceof AppliedFunction p
                    && simpleName(p.function()).equals("pair")
                    && p.parameters().size() == 2) {
                pairsOut.add(p);
            } else {
                return null;
            }
        }
        return lam;
    }

    /** Rewrite {@code $p.first}/{@code $p.second} reads to the pair's
     * concrete values (shadowing lambdas stop the walk). */
    private static @com.legend.Nullable ValueSpecification substPairReads(
            @com.legend.Nullable ValueSpecification v,
            String pVar, ValueSpecification first, ValueSpecification second) {
        return switch (v) {
            case null -> null;
            case AppliedProperty ap when ap.receiver() instanceof Variable pv
                    && pv.name().equals(pVar)
                    && ap.property().equals("first") -> first;
            case AppliedProperty ap when ap.receiver() instanceof Variable pv
                    && pv.name().equals(pVar)
                    && ap.property().equals("second") -> second;
            case AppliedProperty ap -> new AppliedProperty(
                    java.util.Objects.requireNonNull(substPairReads(
                            ap.receiver(), pVar, first, second)),
                    ap.property());
            case AppliedFunction af -> new AppliedFunction(af.function(),
                    af.parameters().stream()
                            .map(x -> substPairReads(x, pVar, first, second))
                            .toList());
            case LambdaFunction lf when lf.parameters().stream()
                    .noneMatch(pv2 -> pv2.name().equals(pVar)) ->
                    new LambdaFunction(lf.parameters(), lf.body().stream()
                            .map(x -> substPairReads(x, pVar, first, second))
                            .toList());
            case PureCollection pc -> new PureCollection(pc.values().stream()
                    .map(x -> substPairReads(x, pVar, first, second))
                    .toList());
            default -> v.mapChildren(x -> requireNonNull(substPairReads(x, pVar, first, second)));
        };
    }

    /** The trailing member name of an enum-shaped read ({@code DatabaseType.H2}
     * as an EnumValue or a property read); null when neither shape. */
    private static @com.legend.Nullable String enumTail(ValueSpecification v) {
        if (v instanceof com.legend.protocol.spec.EnumValue ev) {
            return ev.value();
        }
        if (v instanceof AppliedProperty ap) {
            return ap.property();
        }
        return null;
    }

    /** Deep JSON equality with NUMERIC BigDecimal compare (scale drops:
     * the engine prints 5.0 where our envelope prints 5.000000000 for the
     * same DECIMAL(38,9) value). Long-vs-BigDecimal stays UNEQUAL on
     * purpose — an integer-typed expectation against a decimal wire value
     * is a typing bug this compare must catch, same stance as wireEquals'
     * int/fp split. */
    /** First diverging path between parsed JSON structures, or null when
     * deep-equal — the SAME semantics as {@link #jsonDeepEquals} (objects
     * key-order-insensitive, arrays order-sensitive), reported as a
     * dotted/indexed path with the local expected/actual values. */
    private static @com.legend.Nullable String jsonDiffPath(@com.legend.Nullable Object e,
            @com.legend.Nullable Object a, String path) {
        if (e instanceof java.math.BigDecimal be
                && a instanceof java.math.BigDecimal ba) {
            return be.compareTo(ba) == 0 ? null
                    : path + " expected " + be + ", got " + ba;
        }
        if (e instanceof Map<?, ?> em && a instanceof Map<?, ?> am) {
            for (Object k : em.keySet()) {
                if (!am.containsKey(k)) {
                    return path + " missing key '" + k + "'";
                }
            }
            for (Object k : am.keySet()) {
                if (!em.containsKey(k)) {
                    return path + " unexpected key '" + k + "'";
                }
            }
            for (Object k : em.keySet()) {
                String d = jsonDiffPath(em.get(k), am.get(k),
                        path + "." + k);
                if (d != null) {
                    return d;
                }
            }
            return null;
        }
        if (e instanceof List<?> el && a instanceof List<?> al) {
            if (el.size() != al.size()) {
                return path + " expected " + el.size()
                        + " element(s), got " + al.size();
            }
            for (int i = 0; i < el.size(); i++) {
                String d = jsonDiffPath(el.get(i), al.get(i),
                        path + "[" + i + "]");
                if (d != null) {
                    return d;
                }
            }
            return null;
        }
        return java.util.Objects.equals(e, a) ? null
                : path + " expected " + abbreviate(String.valueOf(e))
                        + ", got " + abbreviate(String.valueOf(a));
    }

    private static boolean jsonDeepEquals(@com.legend.Nullable Object e, @com.legend.Nullable Object a) {
        if (e instanceof java.math.BigDecimal be
                && a instanceof java.math.BigDecimal ba) {
            return be.compareTo(ba) == 0;
        }
        if (e instanceof Map<?, ?> em && a instanceof Map<?, ?> am) {
            if (!em.keySet().equals(am.keySet())) {
                return false;
            }
            for (Object k : em.keySet()) {
                if (!jsonDeepEquals(em.get(k), am.get(k))) {
                    return false;
                }
            }
            return true;
        }
        if (e instanceof List<?> el && a instanceof List<?> al) {
            if (el.size() != al.size()) {
                return false;
            }
            for (int i = 0; i < el.size(); i++) {
                if (!jsonDeepEquals(el.get(i), al.get(i))) {
                    return false;
                }
            }
            return true;
        }
        return java.util.Objects.equals(e, a);
    }

    static String simpleName(String fn) {
        int cut = fn.lastIndexOf("::");
        return cut < 0 ? fn : fn.substring(cut + 2);
    }

    /** A golden-SQL spelling: any chain ending in sqlRemoveFormatting()/sql(). */
    private static boolean isSqlText(ValueSpecification v) {
        // audit 23 D3: harness-vocab gate — a user function named 'sql'
        // must not demote a whole assert to advisory
        return v instanceof AppliedFunction af
                && harnessVocabName(af.function())
                && (af.function().equals("sqlRemoveFormatting")
                        || af.function().endsWith("::sqlRemoveFormatting")
                        || af.function().equals("sql")
                        || af.function().endsWith("::sql"));
    }

    /** A Result VALUES read anywhere in the expression — the assert also
     * verifies row data, so it must not be swallowed as advisory. */
    private static boolean containsValuesRead(ValueSpecification v) {
        if (v instanceof AppliedProperty ap && ap.property().equals("values")) {
            return true;
        }
        if (v instanceof AppliedFunction af) {
            for (ValueSpecification p2 : af.parameters()) {
                if (containsValuesRead(p2)) {
                    return true;
                }
            }
        }
        if (v instanceof AppliedProperty ap2) {
            return containsValuesRead(ap2.receiver());
        }
        return false;
    }

    /** A golden-SQL read ANYWHERE in the expression (nested spellings:
     * {@code $r->sqlRemoveFormatting()->toLower()->contains(...)}) — the
     * whole assertion is about SQL text, advisory by policy. */
    private static boolean containsSqlText(ValueSpecification v) {
        if (isSqlText(v)) {
            return true;
        }
        if (v instanceof AppliedFunction af) {
            for (ValueSpecification p : af.parameters()) {
                if (containsSqlText(p)) {
                    return true;
                }
            }
        }
        if (v instanceof AppliedProperty ap) {
            return containsSqlText(ap.receiver());
        }
        return false;
    }

    // ===== evaluation: compile one side through the pipeline =====

    /** One evaluated side: the execution result + how it compares. */
    record Eval(com.legend.exec.ExecutionResult result, boolean sortedChain,
            boolean csvTail, @com.legend.Nullable String joinSep, boolean flatCells) {

        Eval(com.legend.exec.ExecutionResult result, boolean sortedChain,
                boolean csvTail) {
            this(result, sortedChain, csvTail, null, false);
        }

        Eval(com.legend.exec.ExecutionResult result, boolean sortedChain,
                boolean csvTail, String joinSep) {
            this(result, sortedChain, csvTail, joinSep, false);
        }

        long size() {
            return switch (result) {
                case com.legend.exec.ExecutionResult.Scalar sc ->
                        sc.value() == null ? 0 : flatten(sc.value()).size();
                case com.legend.exec.ExecutionResult.Collection c -> c.values().size();
                case com.legend.exec.ExecutionResult.Tabular t -> t.rows().size();
                case com.legend.exec.ExecutionResult.Graph g -> {
                    Object p = com.legend.sql.Json.parse(g.json());
                    yield p instanceof List<?> l ? l.size() : 1;
                }
            };
        }

        List<Object> values() {
            return switch (result) {
                case com.legend.exec.ExecutionResult.Scalar sc ->
                        sc.value() == null ? List.of()
                                : H2Verify.coerceTemporal(flatten(sc.value()),
                                        sc.returnType());
                case com.legend.exec.ExecutionResult.Collection c ->
                        H2Verify.coerceTemporal(c.values(), c.returnType());
                case com.legend.exec.ExecutionResult.Tabular t -> {
                    List<Object> out = new ArrayList<>();
                    t.rows().forEach(r -> out.addAll(r.values()));
                    yield out;
                }
                case com.legend.exec.ExecutionResult.Graph g -> {
                    Object p = com.legend.sql.Json.parse(g.json());
                    yield p instanceof List<?> l ? new ArrayList<>(l) : List.of(p);
                }
            };
        }


        String render() {
            List<Object> v = values();
            return v.size() == 1 ? String.valueOf(v.get(0)) : String.valueOf(v);
        }

        /** A collection-literal root arrives as an ARRAY-valued scalar. */
        private static List<Object> flatten(Object v) {
            if (v == null) {
                return new ArrayList<>();   // SQL NULL = pure empty
            }
            if (v instanceof List<?> l) {
                return new ArrayList<>(l);
            }
            // native java.sql.Array and byte[] JSON-carrier arrivals —
            // one decoder, hoisted (H2Verify.carrierList)
            List<Object> carried = H2Verify.carrierList(v);
            return carried != null ? carried : List.of(v);
        }
    }

    static Eval eval(ValueSpecification expr,
            Map<String, ValueSpecification> lets,
            List<ValueSpecification> execStmts, java.util.Set<String> execVars,
            Map<String, ValueSpecification> execChains,
            ModelContext ctx, ImportScope imports, String runtimeFqn, Connection conn)
            throws java.sql.SQLException {
        ValueSpecification spliced = subst(expr, lets);
        // A TOP-LEVEL LET ALIAS (let res = rows->map(..)->makeString(','))
        // lives in the exec-statement frame, not in lets — the shape
        // sniffs below (joinSep/toCSV/replace) must see the real chain,
        // not the Variable. Same last-binding-wins chase as
        // ExecCallFinder, cycle-guarded.
        java.util.Set<String> seenLets = new java.util.HashSet<>();
        while (spliced instanceof Variable av && seenLets.add(av.name())) {
            ValueSpecification bound =
                    ExecCallFinder.lastLetBinding(av.name(), execStmts);
            if (bound == null) {
                break;
            }
            spliced = subst(bound, lets);
        }
        // SERIALIZATION TAILS (toCSV/toString over a TDS) strip: the grid
        // compares STRUCTURALLY (or renders for a string-literal peer) —
        // rendering is a wire concern, not a query. A tail whose receiver
        // turns out non-relational falls back to the original expression.
        boolean csv = false;
        // toCSV(tds)->replace(a, b): render the grid to CSV text, apply the
        // replace LITERALLY, compare as a string (the calendar family's
        // one-line assert spelling)
        if (spliced instanceof AppliedFunction rep
                && simpleName(rep.function()).equals("replace")
                && rep.parameters().size() == 3
                && rep.parameters().get(0) instanceof AppliedFunction innerCsv
                && simpleName(innerCsv.function()).equals("toCSV")
                && innerCsv.parameters().size() == 1
                && rep.parameters().get(1) instanceof CString from
                && "\n".equals(from.value())
                && rep.parameters().get(2) instanceof CString to) {
            com.legend.exec.ExecutionResult stripped2 = evalSpliced(
                    innerCsv.parameters().get(0), execStmts, execVars,
                    ctx, imports, runtimeFqn, conn);
            if (stripped2 instanceof com.legend.exec.ExecutionResult.Tabular tab2) {
                // structured compare: keep the TABULAR and the joined-line
                // separator — string-exact comparison broke on ROW ORDER
                // (unordered groupBy) and float ULPs (5.72 vs 5.7199...);
                // csvJoinedEquals below applies the header/multiset/
                // tolerant-cell policy instead
                return new Eval(stripped2,
                        endsInSort(orderView(innerCsv.parameters().get(0),
                                execChains)), false,
                        "CSVJOIN:" + to.value());
            }
        }
        if (spliced instanceof AppliedFunction tail
                && (simpleName(tail.function()).equals("toCSV")
                        || simpleName(tail.function()).equals("toString"))
                && tail.parameters().size() == 1) {
            com.legend.exec.ExecutionResult stripped = evalSpliced(
                    tail.parameters().get(0), execStmts, execVars,
                    ctx, imports, runtimeFqn, conn);
            if (stripped instanceof com.legend.exec.ExecutionResult.Tabular) {
                return new Eval(stripped,
                        endsInSort(orderView(tail.parameters().get(0),
                                execChains)),
                        simpleName(tail.function()).equals("toCSV"));
            }
        }
        com.legend.exec.ExecutionResult r = evalSpliced(spliced, execStmts,
                execVars, ctx, imports, runtimeFqn, conn);
        // A makeString/joinStrings tail over an UNSORTED chain: the joined
        // string's element order is the DB's incidental row order — record
        // the separator so the compare can fall back to split-multiset
        // (the ORDER POLICY at string granularity).
        String joinSep = null;
        if (spliced instanceof AppliedFunction jf
                && (simpleName(jf.function()).equals("makeString")
                        || simpleName(jf.function()).equals("joinStrings"))
                && jf.parameters().size() == 2
                && jf.parameters().get(1) instanceof CString sep
                && !endsInSort(orderView(jf.parameters().get(0),
                        execChains))) {
            joinSep = sep.value();
        }
        return new Eval(java.util.Objects.requireNonNull(r, "spliced eval without a result"),
                endsInSort(orderView(spliced, execChains)),
                csv, joinSep, isFlatCellsRead(spliced));
    }

    /** {@code ...rows.values} — the flat-CELLS spelling. Engine semantics
     * is {@code TDSRow.values} ({@code Any[*]}): column names are OUT of
     * the comparison. Our platform erases the {@code .rows} marker and
     * returns the TDS for both spellings, so the compare must know the
     * read shape (audit 21 follow-up: testQualifierFunctionConsistency*
     * compares two TDSes with DIFFERENT column names via rows.values —
     * the grid arm's column-name pin is wrong there, engine-verified). */
    private static boolean isFlatCellsRead(ValueSpecification v) {
        return v instanceof AppliedProperty ap && "values".equals(ap.property())
                && ap.receiver() instanceof AppliedProperty rp
                && "rows".equals(rp.property());
    }

    static Object evalScalar(ValueSpecification expr,
            Map<String, ValueSpecification> lets,
            List<ValueSpecification> execStmts, java.util.Set<String> execVars,
            Map<String, ValueSpecification> execChains,
            ModelContext ctx, ImportScope imports, String runtimeFqn, Connection conn)
            throws java.sql.SQLException {
        Eval e = eval(expr, lets, execStmts, execVars, execChains, ctx, imports, runtimeFqn, conn);
        List<Object> v = e.values();
        return v.size() == 1 ? v.get(0) : v;
    }

    /** Compile + execute ONE expression through THE one back-half sequence
     * ({@link Compiler#executeResolved}); an expression that reads an
     * execute() binding rides behind the forwarded statement PREFIX — the
     * platform's result frame owns the envelope splice (audit 19d B2). */
    private static com.legend.exec.@com.legend.Nullable ExecutionResult evalSpliced(ValueSpecification expr,
            List<ValueSpecification> execStmts, java.util.Set<String> execVars,
            ModelContext ctx, ImportScope imports, String runtimeFqn, Connection conn)
            throws java.sql.SQLException {
        List<ValueSpecification> stmts = new ArrayList<>();
        if (referencesAny(expr, execVars) || containsExecute(expr)) {
            stmts.addAll(execStmts);
        }
        stmts.add(expr);
        LambdaFunction wrapped = new LambdaFunction(List.of(), stmts);
        ValueSpecification resolved = NameResolver.resolveQuery(wrapped, imports,
                ctx.elementFqns());
        return Compiler.executeResolved(resolved, ctx, runtimeFqn, conn);
    }

    /** Evaluate the forwarded statement list AS-IS (a trailing let IS its
     * value) — the EAGER run at an execute() binding. */
    private static void evalStatements(List<ValueSpecification> stmts,
            ModelContext ctx, ImportScope imports, String runtimeFqn,
            Connection conn) throws java.sql.SQLException {
        for (ValueSpecification s : stmts) {
            List<ValueSpecification> csvs = new ArrayList<>();
            collectInlineCsv(s, csvs);
            for (ValueSpecification csvExpr : csvs) {
                seedInlineCsv(csvExpr, ctx, conn);
            }
        }
        LambdaFunction wrapped = new LambdaFunction(List.of(),
                new ArrayList<>(stmts));
        ValueSpecification resolved = NameResolver.resolveQuery(wrapped, imports,
                ctx.elementFqns());
        Compiler.executeResolved(resolved, ctx, runtimeFqn, conn);
    }

    /** A runtime COPY carrying an inline {@code testDataSetupCsv} override
     * (^$connection(testDataSetupCsv=...)) declares the test's OWN seed
     * data — engine semantics: the test connection seeds from this
     * property before the query runs. The harness runs the SAME CsvSeed
     * synthesis the corpus's setUpDataSQLsV2 path uses; each test has a
     * FRESH DuckDB connection (Runner opens jdbc:duckdb: per test), so
     * DELETE+INSERT over the family-DDL tables is exactly the override. */
    private static void collectInlineCsv(ValueSpecification v,
            List<ValueSpecification> sink) {
        switch (v) {
            case NewInstance ni -> {
                KeyExpression k = ni.properties().get("testDataSetupCsv");
                if (k != null) {
                    sink.add(k.value());
                }
                ni.properties().values().forEach(x ->
                        collectInlineCsv(x.value(), sink));
            }
            case AppliedFunction af ->
                    af.parameters().forEach(x -> collectInlineCsv(x, sink));
            case AppliedProperty ap -> collectInlineCsv(ap.receiver(), sink);
            case PureCollection pc ->
                    pc.values().forEach(x -> collectInlineCsv(x, sink));
            case LambdaFunction lf ->
                    lf.body().forEach(x -> collectInlineCsv(x, sink));
            default -> v.children().forEach(x -> collectInlineCsv(x, sink));
        }
    }

    private static void seedInlineCsv(ValueSpecification csvExpr,
            ModelContext ctx, Connection conn) throws java.sql.SQLException {
        String csv = foldStringLiteral(csvExpr);
        for (String sql : com.legend.exec.CsvSeed.sqls(csv, null, ctx)) {
            try (var st = conn.createStatement()) {
                st.execute(sql);
            }
        }
    }

    /** Fold a '+'-concatenated string literal tree to its value — the
     * corpus spells inline CSVs as 'a\n'+'b\n'+... Loud on anything
     * non-literal (a computed CSV cannot be seeded honestly). */
    private static String foldStringLiteral(ValueSpecification v) {
        return switch (v) {
            case CString cs -> cs.value();
            case AppliedFunction af when af.function().equals("plus") -> {
                StringBuilder sb = new StringBuilder();
                for (ValueSpecification p : af.parameters()) {
                    sb.append(foldStringLiteral(p));
                }
                yield sb.toString();
            }
            case PureCollection pc -> {
                StringBuilder sb = new StringBuilder();
                for (ValueSpecification p : pc.values()) {
                    sb.append(foldStringLiteral(p));
                }
                yield sb.toString();
            }
            default -> throw new com.legend.error.NotImplementedException(
                    "inline testDataSetupCsv is not a foldable string literal ("
                    + v.getClass().getSimpleName() + ") — computed CSVs are"
                    + " not seeded yet");
        };
    }

    private static List<ValueSpecification> append(
            List<ValueSpecification> prefix, ValueSpecification last) {
        List<ValueSpecification> out = new ArrayList<>(prefix);
        out.add(last);
        return out;
    }

    // ===== comparison (both sides share ONE wire convention — strict) =====

    static boolean compare(Eval expected, Eval actual, boolean ordered) {
        // toCSV(..)->replace('\n', SEP) actual vs a string-literal expected:
        // header EXACT, rows as an (un)ordered multiset, CELLS via the
        // tolerant wire comparison (numeric ULP policy included)
        if (actual.joinSep() != null && actual.joinSep().startsWith("CSVJOIN:")
                && actual.result()
                        instanceof com.legend.exec.ExecutionResult.Tabular tj
                && expected.values().size() == 1
                && expected.values().get(0) instanceof String es) {
            return csvJoinedEquals(es,
                    actual.joinSep().substring("CSVJOIN:".length()), tj,
                    ordered && actual.sortedChain());
        }
        // TDS grids compare STRUCTURALLY: column names ordered, rows under
        // the order policy — both sides evaluated by the same pipeline.
        // NOT for a flat-cells side (rows.values): that spelling compares
        // raw cell values only — column names are out (engine TDSRow
        // semantics; see isFlatCellsRead).
        if (expected.result() instanceof com.legend.exec.ExecutionResult.Tabular te
                && actual.result() instanceof com.legend.exec.ExecutionResult.Tabular ta
                && !expected.flatCells() && !actual.flatCells()) {
            return gridEquals(te, ta, ordered && actual.sortedChain());
        }
        // ->toString() over a TDS against a '#TDS\n…#' STRING literal:
        // engine relation toString (core_functions_relation toString.pure)
        // renders '#TDS\n   col,col\n   cell,cell\n…\n#'. Header exact,
        // rows under the order policy.
        if (actual.result() instanceof com.legend.exec.ExecutionResult.Tabular tds
                && expected.values().size() == 1
                && expected.values().get(0) instanceof String tdsGolden
                && tdsGolden.startsWith("#TDS\n")) {
            return tdsStringEquals(tdsGolden, tds, ordered && actual.sortedChain());
        }
        // toCSV() against a STRING literal: render the grid (wire concern);
        // header pinned, data lines under the order policy
        if (actual.csvTail()
                && actual.result() instanceof com.legend.exec.ExecutionResult.Tabular tt
                && expected.values().size() == 1
                && expected.values().get(0) instanceof String es) {
            return csvEquals(es, tt, actual.sortedChain());
        }
        if (expected.csvTail()
                && expected.result() instanceof com.legend.exec.ExecutionResult.Tabular tt2
                && actual.values().size() == 1
                && actual.values().get(0) instanceof String as) {
            return csvEquals(as, tt2, true);
        }
        // MIXED flat-cells vs whole-TDS VALUE (audit 22b F2): pure equality
        // of a raw-cell list against a TabularDataSet instance is FALSE —
        // flattening both sides would drop the TDS side's column-name pin.
        // (A flat-cells side vs a plain literal list stays the values path.)
        if (expected.flatCells() != actual.flatCells()
                && (expected.flatCells() ? actual : expected).result()
                        instanceof com.legend.exec.ExecutionResult.Tabular) {
            return false;
        }
        List<Object> e = expected.values();
        List<Object> a = actual.values();
        if (e.size() != a.size()) {
            return false;
        }
        // ORDER POLICY (the single deliberate leniency, documented): pure
        // assertEquals is ordered, but an actual side with NO sort in its
        // chain has no defined SQL row order — the engine's expectation
        // encodes H2's incidental order, ours is DuckDB's. Multiset-compare
        // exactly then; a sorted chain compares exactly ordered.
        if (ordered && actual.sortedChain()) {
            for (int i = 0; i < e.size(); i++) {
                if (!wireEquals(e.get(i), a.get(i))) {
                    return false;
                }
            }
            return true;
        }
        if (ordered) {
            // try ordered first — identical orders stay strongest evidence
            boolean ok = true;
            for (int i = 0; i < e.size() && ok; i++) {
                ok = wireEquals(e.get(i), a.get(i));
            }
            if (ok) {
                return true;
            }
        }
        // ORDER POLICY at STRING granularity: a makeString over an
        // unsorted chain joined the DB's incidental row order — compare
        // the split parts as a multiset.
        if (actual.joinSep() != null && !actual.joinSep().isEmpty()
                && e.size() == 1 && a.size() == 1
                && e.get(0) instanceof String es2 && a.get(0) instanceof String as2
                && !es2.equals(as2)) {
            List<String> ep = new ArrayList<>(List.of(
                    es2.split(java.util.regex.Pattern.quote(actual.joinSep()), -1)));
            List<String> ap = new ArrayList<>(List.of(
                    as2.split(java.util.regex.Pattern.quote(actual.joinSep()), -1)));
            if (ep.size() == ap.size()) {
                java.util.Collections.sort(ep);
                java.util.Collections.sort(ap);
                return ep.equals(ap);
            }
            return false;
        }
        // ROW COHESION (audit 9): an ORDERED compare's multiset fallback
        // (the order policy) must match ROW TUPLES, not loose cells —
        // cross-row cell shuffles must not compare equal. assertSameElements
        // stays a loose pool: the corpus itself writes its flat expected
        // sets column-grouped (testGreaterThanWithOptionalProperty), so
        // loose multiset IS that assert's reference semantics.
        if (ordered
                && actual.result() instanceof com.legend.exec.ExecutionResult.Tabular tab
                && tab.columns().size() > 1
                && (!(expected.result()
                        instanceof com.legend.exec.ExecutionResult.Tabular)
                        // audit 22b F3: BOTH-flat-cells exec-vs-exec compares
                        // must keep row cohesion too — a loose cell multiset
                        // let cross-row shuffles pass
                        || (expected.flatCells() && actual.flatCells()))
                && e.size() == a.size() && a.size() % tab.columns().size() == 0) {
            int w = tab.columns().size();
            List<List<Object>> ep = chunk(e, w);
            List<List<Object>> ap = chunk(a, w);
            for (List<Object> row : ep) {
                int hit = -1;
                for (int i = 0; i < ap.size(); i++) {
                    boolean all = true;
                    for (int j = 0; j < w && all; j++) {
                        all = wireEquals(row.get(j), ap.get(i).get(j));
                    }
                    if (all) {
                        hit = i;
                        break;
                    }
                }
                if (hit < 0) {
                    return false;
                }
                ap.remove(hit);
            }
            return true;
        }
        List<Object> pool = new ArrayList<>(a);
        for (Object x : e) {
            int hit = -1;
            for (int i = 0; i < pool.size(); i++) {
                if (wireEquals(x, pool.get(i))) {
                    hit = i;
                    break;
                }
            }
            if (hit < 0) {
                if (System.getenv("LEGEND_LITE_CMP_DEBUG") != null) {
                    System.err.println("[cmp] pool miss: expected " + x + " ("
                            + (x == null ? "null" : x.getClass().getSimpleName())
                            + ") pool types=" + pool.stream().map(o ->
                            o == null ? "null" : o.getClass().getSimpleName())
                            .toList());
                }
                return false;
            }
            pool.remove(hit);
        }
        return true;
    }

    private static List<List<Object>> chunk(List<Object> flat, int w) {
        List<List<Object>> out = new ArrayList<>(flat.size() / w);
        for (int i = 0; i + w <= flat.size(); i += w) {
            out.add(new ArrayList<>(flat.subList(i, i + w)));
        }
        return out;
    }

    /** Column-name + row-grid equality (rows ordered iff the chain sorts). */
    private static boolean gridEquals(com.legend.exec.ExecutionResult.Tabular expected,
            com.legend.exec.ExecutionResult.Tabular actual, boolean ordered) {
        if (expected.columns().size() != actual.columns().size()) {
            return false;
        }
        for (int i = 0; i < expected.columns().size(); i++) {
            if (!expected.columns().get(i).name().equals(actual.columns().get(i).name())) {
                return false;
            }
        }
        List<List<Object>> e = new ArrayList<>();
        expected.rows().forEach(r -> e.add(r.values()));
        List<List<Object>> a = new ArrayList<>();
        actual.rows().forEach(r -> a.add(r.values()));
        if (e.size() != a.size()) {
            return false;
        }
        if (ordered) {
            return rowsPositional(e, a);
        }
        List<List<Object>> pool = new ArrayList<>(a);
        for (List<Object> row : e) {
            int hit = -1;
            for (int i = 0; i < pool.size(); i++) {
                if (rowEquals(row, pool.get(i))) {
                    hit = i;
                    break;
                }
            }
            if (hit < 0) {
                return false;
            }
            pool.remove(hit);
        }
        // C0.4: a multiset pass the POSITIONAL compare would reject is a
        // pass that depends on order leniency — countable per sweep
        ordLeniency(() -> rowsPositional(e, a));
        return true;
    }

    private static boolean rowsPositional(List<List<Object>> e,
            List<List<Object>> a) {
        for (int i = 0; i < e.size(); i++) {
            if (!rowEquals(e.get(i), a.get(i))) {
                return false;
            }
        }
        return true;
    }

    /** C0.4 (CORRECTNESS_REMEDIATION): under {@code LL_ORD_COUNT}, emit an
     * {@code [ord]} line when a comparison passed ONLY because of multiset
     * row leniency — {@code strictHolds} is the order-strict re-check.
     * Attribution: pair each line with the preceding {@code [run] <fqn>}.
     * Measurement only; never changes the verdict. */
    private static void ordLeniency(java.util.function.BooleanSupplier strictHolds) {
        if (System.getenv("LL_ORD_COUNT") != null && !strictHolds.getAsBoolean()) {
            System.err.println("[ord] order-leniency-dependent pass");
        }
    }

    private static boolean rowEquals(List<Object> e, List<Object> a) {
        if (e.size() != a.size()) {
            return false;
        }
        for (int i = 0; i < e.size(); i++) {
            if (!wireEquals(e.get(i), a.get(i))) {
                return false;
            }
        }
        return true;
    }

    /** toCSV wire rendering vs an expected CSV string (header pinned). */
    /** {@code toCSV->replace('\n', sep)} against a string literal: tokens
     * split on {@code sep}; the first nCols are the HEADER (exact); the
     * rest group into rows of nCols compared as a multiset (ordered when
     * the chain sorts) with wireEquals cells (numeric tolerance). */
    private static boolean csvJoinedEquals(String expected, String sep,
            com.legend.exec.ExecutionResult.Tabular t, boolean ordered) {
        int n = t.columns().size();
        if (n == 0 || sep.isEmpty()) {
            return false;
        }
        List<String> tokens = new ArrayList<>(
                List.of(expected.split(java.util.regex.Pattern.quote(sep), -1)));
        // a trailing separator leaves one empty tail token
        if (!tokens.isEmpty() && tokens.get(tokens.size() - 1).isEmpty()) {
            tokens.remove(tokens.size() - 1);
        }
        if (tokens.size() < n || tokens.size() % n != 0) {
            return false;
        }
        for (int i = 0; i < n; i++) {
            if (!t.columns().get(i).name().equals(tokens.get(i))) {
                return false;
            }
        }
        List<List<String>> expRows = new ArrayList<>();
        for (int i = n; i < tokens.size(); i += n) {
            expRows.add(tokens.subList(i, i + n));
        }
        List<List<Object>> actRows = new ArrayList<>();
        t.rows().forEach(r -> actRows.add(r.values()));
        if (expRows.size() != actRows.size()) {
            return false;
        }
        if (ordered) {
            for (int i = 0; i < expRows.size(); i++) {
                if (!csvRowEquals(expRows.get(i), actRows.get(i))) {
                    return false;
                }
            }
            return true;
        }
        List<List<Object>> pool = new ArrayList<>(actRows);
        for (List<String> er : expRows) {
            int hit = -1;
            for (int i = 0; i < pool.size(); i++) {
                if (csvRowEquals(er, pool.get(i))) {
                    hit = i;
                    break;
                }
            }
            if (hit < 0) {
                return false;
            }
            pool.remove(hit);
        }
        return true;
    }

    private static boolean csvRowEquals(List<String> expected, List<Object> actual) {
        for (int i = 0; i < expected.size(); i++) {
            String e = expected.get(i);
            Object a = actual.get(i);
            String aCell = csvCell(a);
            if (e.equals(aCell)) {
                continue;
            }
            // numeric cells: the expected CSV prints ROUNDED (the engine
            // truncates to ~12 significant digits — 0.383333333333 vs our
            // 0.38333333333333336) — equal iff the actual rounds to the
            // expected at the EXPECTED's own printed precision
            try {
                double ev = Double.parseDouble(e);
                double av = a instanceof Number num ? num.doubleValue()
                        : Double.parseDouble(aCell);
                int dp = e.contains(".")
                        ? e.length() - e.indexOf('.') - 1 : 0;
                // Two leniencies, both bounded by "engine prints ~12 sig
                // digits + non-associative double summation" (testPwaValue):
                // 1. relative 1e-11 accumulation epsilon — always;
                // 2. half-ulp at the expected's PRINTED precision — only
                //    when the token carries >= 10 sig digits (a genuine
                //    truncation artifact; trimmed zeros can shave to 10).
                //    A coarse golden like '100' gets the relative floor
                //    only (audit 16: dp=0 granted +-0.5 — exact output).
                int sig = 0;
                boolean seenNonZero = false;
                for (int ci = 0; ci < e.length(); ci++) {
                    char ch = e.charAt(ci);
                    if (ch >= '1' && ch <= '9') {
                        seenNonZero = true;
                    }
                    if (Character.isDigit(ch) && (seenNonZero || ch != '0')) {
                        sig++;
                    }
                }
                double tol = sig >= 10
                        ? Math.max(0.5 * Math.pow(10, -dp), Math.abs(ev) * 1e-11)
                        : Math.abs(ev) * 1e-11;
                if (Math.abs(av - ev) > tol) {
                    return false;
                }
                // audit 23 D2 instrumentation (measurement-only): count
                // comparisons that pass ONLY because of the tolerance
                if (av != ev && Math.abs(av - ev) <= tol
                        && System.getenv("LL_TOL_COUNT") != null) {
                    System.err.println("[tol] csv " + e + " vs " + a);
                }
            } catch (NumberFormatException nfe) {
                return false;
            }
        }
        return true;
    }

    /** The toCSV wire text: header line + one line per row, every line
     * newline-terminated (the engine's Result->toCSV convention). */
    private static String csvText(com.legend.exec.ExecutionResult.Tabular t) {
        StringBuilder header = new StringBuilder();
        for (var c : t.columns()) {
            if (header.length() > 0) {
                header.append(',');
            }
            header.append(c.name());
        }
        StringBuilder out = new StringBuilder(header).append('\n');
        for (var r : t.rows()) {
            StringBuilder line = new StringBuilder();
            for (Object v : r.values()) {
                if (line.length() > 0) {
                    line.append(',');
                }
                line.append(csvCell(v));
            }
            out.append(line).append('\n');
        }
        return out.toString();
    }

    private static boolean csvEquals(String expected,
            com.legend.exec.ExecutionResult.Tabular actual, boolean sorted) {
        StringBuilder header = new StringBuilder();
        for (var c : actual.columns()) {
            if (header.length() > 0) {
                header.append(',');
            }
            header.append(c.name());
        }
        List<String> lines = new ArrayList<>();
        for (var r : actual.rows()) {
            StringBuilder line = new StringBuilder();
            for (Object v : r.values()) {
                if (line.length() > 0) {
                    line.append(',');
                }
                line.append(csvCell(v));
            }
            lines.add(line.toString());
        }
        String rendered = header + "\n"
                + lines.stream().map(l -> l + "\n").reduce("", String::concat);
        if (expected.equals(rendered)) {
            return true;
        }
        if (sorted) {
            return false;
        }
        // order policy: header line pinned, data lines as a multiset
        String[] el = expected.split("\n", -1);
        if (el.length == 0 || !el[0].equals(header.toString())) {
            return false;
        }
        List<String> pool = new ArrayList<>(lines);
        int dataLines = 0;
        for (int i = 1; i < el.length; i++) {
            if (el[i].isEmpty() && i == el.length - 1) {
                continue;   // trailing newline
            }
            dataLines++;
            if (!pool.remove(el[i])) {
                return false;
            }
        }
        boolean ok = pool.isEmpty() && dataLines == lines.size();
        if (ok) {
            // the exact-string compare above already FAILED, so reaching a
            // multiset success is order-leniency-dependent by construction
            ordLeniency(() -> false);
        }
        return ok;
    }

    /** STRICT wire equality: integral kinds normalize; decimal by compareTo; no cross-kind. */
    private static boolean isTemporal(Object v) {
        return v instanceof java.sql.Timestamp || v instanceof java.sql.Date
                || v instanceof java.time.LocalDate
                || v instanceof java.time.LocalDateTime
                || v instanceof java.time.OffsetDateTime;
    }

    private static boolean temporalEquals(String s, Object t) {
        // wire temporals are NAIVE (UTC-normalized); strip a UTC-zero
        // offset/zone suffix from the string form
        String v = s.trim().replaceFirst("(Z|\\+00(:?00)?|\\+0000)$", "")
                .replace('T', ' ').trim();
        try {
            if (t instanceof java.sql.Date d) {
                return java.time.LocalDate.parse(v).equals(d.toLocalDate());
            }
            if (t instanceof java.time.LocalDate ld) {
                return java.time.LocalDate.parse(v).equals(ld);
            }
            java.time.LocalDateTime other = t instanceof java.sql.Timestamp ts
                    ? ts.toLocalDateTime()
                    : t instanceof java.time.LocalDateTime ldt ? ldt
                    : t instanceof java.time.OffsetDateTime odt
                            ? odt.toLocalDateTime() : null;
            if (other == null) {
                return false;
            }
            String norm = v.contains(" ") ? v.replace(' ', 'T') : v + "T00:00";
            return java.time.LocalDateTime.parse(norm).equals(other);
        } catch (java.time.format.DateTimeParseException ex) {
            return false;
        }
    }

    /** RFC4180 cell rendering (the engine's toCSV): a cell containing a
     * comma, quote or newline wraps in quotes, inner quotes double. */
    private static String csvCell(Object v) {
        if (v == null) {
            return "";
        }
        String s = String.valueOf(v);
        if (s.indexOf(',') < 0 && s.indexOf('"') < 0 && s.indexOf('\n') < 0) {
            return s;
        }
        return '"' + s.replace("\"", "\"\"") + '"';
    }

    /** Engine relation toString format: header line exact ('   ' + names
     * joined by ','), each row '   ' + cells joined by ',', '#' framing.
     * Rows compare ordered when the chain sorts, else as a line multiset
     * (the standard order policy). Cells render String.valueOf with NULL
     * as 'null'; space-bearing names quote ('First Name'). */
    private static boolean tdsStringEquals(String expected,
            com.legend.exec.ExecutionResult.Tabular t, boolean ordered) {
        List<String> lines = new ArrayList<>();
        lines.add("#TDS");
        lines.add("   " + t.columns().stream().map(c -> c.name()
                .contains(" ") ? "'" + c.name() + "'" : c.name())
                .collect(java.util.stream.Collectors.joining(",")));
        for (var r : t.rows()) {
            StringBuilder sb = new StringBuilder("   ");
            for (int i = 0; i < r.values().size(); i++) {
                if (i > 0) {
                    sb.append(',');
                }
                Object v = r.values().get(i);
                sb.append(v == null ? "null" : String.valueOf(v));
            }
            lines.add(sb.toString());
        }
        lines.add("#");
        List<String> exp = List.of(expected.split("\n", -1));
        if (exp.size() != lines.size()
                || !exp.get(0).equals(lines.get(0))
                || !exp.get(1).equals(lines.get(1))
                || !exp.get(exp.size() - 1).equals(lines.get(lines.size() - 1))) {
            return false;
        }
        List<String> er = exp.subList(2, exp.size() - 1);
        List<String> ar = lines.subList(2, lines.size() - 1);
        if (ordered) {
            return er.equals(ar);
        }
        List<String> es = new ArrayList<>(er);
        List<String> as = new ArrayList<>(ar);
        java.util.Collections.sort(es);
        java.util.Collections.sort(as);
        return es.equals(as);
    }

    private static boolean wireEquals(@com.legend.Nullable Object e, @com.legend.Nullable Object a) {
        // the null-cell wire sentinel: an expected ^TDSNull() (or a TDS-grid
        // 'TDSNull' cell) equals an actual NULL cell — 'TDSNull' is never a
        // genuine string payload (established: real pure parses it as the
        // instance, our literals decode it to SQL NULL)
        if ("TDSNull".equals(e) && a == null) {
            return true;
        }
        // HOST INSTANCES compare STRUCTURALLY (pure instance-graph
        // assertEquals — the debugPrint tree goldens)
        if (e instanceof com.legend.exec.HostEval.HostInstance
                || a instanceof com.legend.exec.HostEval.HostInstance) {
            return com.legend.exec.HostEval.hostEquals(e, a);
        }
        // NO actual-side bridge (audit 16 F5): if a bug ever put the
        // literal string 'TDSNull' on OUR wire where a NULL belongs, the
        // symmetric grant would mask it — same refusal as the temporal
        // bridge below (audit 9)
        if (e == null || a == null) {
            return e == a;
        }
        boolean eInt = e instanceof Long || e instanceof Integer
                || e instanceof Short || e instanceof Byte
                || e instanceof java.math.BigInteger;
        boolean aInt = a instanceof Long || a instanceof Integer
                || a instanceof Short || a instanceof Byte
                || a instanceof java.math.BigInteger;
        if (eInt || aInt) {
            if (!(eInt && aInt)) {
                return false;
            }
            return ((Number) e).longValue() == ((Number) a).longValue();
        }
        if (e instanceof java.math.BigDecimal be && a instanceof java.math.BigDecimal ba) {
            return be.compareTo(ba) == 0;
        }
        boolean eFp = e instanceof Double || e instanceof Float || e instanceof java.math.BigDecimal;
        boolean aFp = a instanceof Double || a instanceof Float || a instanceof java.math.BigDecimal;
        if (eFp || aFp) {
            if (!(eFp && aFp)) {
                return false;
            }
            if (new java.math.BigDecimal(String.valueOf(e))
                    .compareTo(new java.math.BigDecimal(String.valueOf(a))) == 0) {
                return true;
            }
            // DIALECT-ARITHMETIC leniency (documented, the only float one):
            // corpus expectations encode H2's libm (ln/asin/acos/atan...);
            // DuckDB's differs in the LAST ULP on the same real number.
            // Two DOUBLE wire values within 2 ULP compare equal. Exact-zero,
            // kind and BigDecimal (pure Decimal) compares stay strict.
            if (e instanceof Double de && a instanceof Double da
                    && !de.isNaN() && !da.isNaN()) {
                double ulp = Math.ulp(Math.max(Math.abs(de), Math.abs(da)));
                boolean ok = Math.abs(de - da) <= 2 * ulp;
                // audit 23 D2 instrumentation (measurement-only)
                if (ok && de.doubleValue() != da.doubleValue()
                        && System.getenv("LL_TOL_COUNT") != null) {
                    System.err.println("[tol] ulp " + de + " vs " + da);
                }
                return ok;
            }
            return false;
        }
        // TEMPORAL through the Any-carrier: a mixed-collection LITERAL's
        // date decodes as its JSON STRING (the variant carrier is untyped
        // for temporals) — bridge by PARSING, value-exact, and ONLY in the
        // expected-string vs actual-temporal direction: an ACTUAL that
        // comes back as a string where the engine returns a Date is a
        // TYPING BUG this compare must catch (audit 9), never bridge.
        if (e instanceof String es && isTemporal(a)) {
            return temporalEquals(es, a);
        }
        if (e instanceof Map<?, ?> em && a instanceof Map<?, ?> am) {
            if (!em.keySet().equals(am.keySet())) {
                return false;
            }
            for (Object k : em.keySet()) {
                if (!wireEquals(em.get(k), am.get(k))) {
                    return false;
                }
            }
            return true;
        }
        return e.equals(a);
    }

    // ===== substitution: lets inline, handles splice =====

    private static boolean isExecuteCall(AppliedFunction af) {
        // audit 23 D3: harness-vocab gate — a USER function named
        // 'execute' (my::execute) must not be commandeered into the
        // platform result-frame path
        return harnessVocabName(af.function())
                && (af.function().equals("execute")
                        || af.function().endsWith("::execute"))
                && af.parameters().size() >= 2;
    }

    /**
     * Replace let-bound variables with their expressions (shadowing lambda
     * params stop substitution). Reads over execute() bindings are NOT
     * substituted here — those statements forward to the platform's result
     * frame, which owns the envelope splice (audit 19d B2).
     */
    /** {@link #substitute} with the non-null passthrough asserted. */
    static ValueSpecification subst(ValueSpecification v,
            Map<String, ValueSpecification> lets) {
        return requireNonNull(substitute(v, lets), "substitute(v, lets)");
    }

    static @com.legend.Nullable ValueSpecification substitute(
            @com.legend.Nullable ValueSpecification v,
            Map<String, ValueSpecification> lets) {
        if (v == null) {
            return null;
        }
        // ^TDSNull() in a TEST literal is the engine's null-cell INSTANCE
        // (a real value, not a pure empty — an empty would VANISH from
        // [^TDSNull(), 5.0] and break the comparison): it travels as the
        // wire sentinel, which wireEquals equates with an actual null cell
        if (v instanceof NewInstance tn
                && (tn.className().equals("TDSNull")
                        || tn.className().equals("meta::pure::tds::TDSNull"))) {
            return new CString("TDSNull");
        }
        if (v instanceof AppliedFunction nf && nf.function().equals("new")
                && nf.parameters().size() == 2
                && nf.parameters().get(1) instanceof NewInstance tn2
                && (tn2.className().equals("TDSNull")
                        || tn2.className().equals("meta::pure::tds::TDSNull"))) {
            return new CString("TDSNull");
        }
        return switch (v) {
            // RECURSIVE: the pulled RHS may itself read lets bound earlier
            // (the per-driver loop's toSQLString($driver) — audit 19d B3
            // exposed the shallow pull when the K-native began TYPING what
            // the old harness arm resolved by hand)
            case Variable var when lets.containsKey(var.name()) ->
                    substitute(lets.get(var.name()), lets);
            case AppliedFunction af -> new AppliedFunction(af.function(),
                    substituteAll(af.parameters(), lets));
            case AppliedProperty ap3 -> {
                ValueSpecification recv = substitute(ap3.receiver(), lets);
                // pair(a, b).first/.second is a CONSTANT fold (real pure
                // anonymousCollections semantics) — the datetime plan
                // helpers return Pair<ExecutionPlan, String>
                if (recv instanceof AppliedFunction pf
                        && simpleName(pf.function()).equals("pair")
                        && pf.parameters().size() == 2) {
                    if (ap3.property().equals("first")) {
                        yield pf.parameters().get(0);
                    }
                    if (ap3.property().equals("second")) {
                        yield pf.parameters().get(1);
                    }
                }
                yield new AppliedProperty(java.util.Objects.requireNonNull(recv, "recv"),
                        ap3.property());
            }
            case PureCollection pc -> new PureCollection(
                    substituteAll(pc.values(), lets));
            case LambdaFunction lf -> {
                // shadowing params stop LET substitution; a LAMBDA-LOCAL
                // let shadows the outer binding for the statements below
                // it (real pure scoping — the plan-printer's injected
                // Allocation lets rely on it)
                Map<String, ValueSpecification> visible = new LinkedHashMap<>(lets);
                lf.parameters().forEach(p2 -> visible.remove(p2.name()));
                List<ValueSpecification> body = new ArrayList<>(lf.body().size());
                for (ValueSpecification st : lf.body()) {
                    body.add(substitute(st, visible));
                    if (st instanceof AppliedFunction lfn
                            && lfn.function().equals("letFunction")
                            && lfn.parameters().size() == 2
                            && lfn.parameters().get(0) instanceof CString ln) {
                        visible.remove(ln.value());
                    }
                }
                yield new LambdaFunction(lf.parameters(), body);
            }
            // graph-tree NODE ARGS read lets too (milestoned property
            // calls in fetch trees: authors($businessDate) { ... } — the
            // tree rides its own let and the date var must inline like
            // any other read)
            case com.legend.protocol.spec.ColSpecArray csa ->
                    new com.legend.protocol.spec.ColSpecArray(
                            csa.colSpecs().stream().map(cs2 ->
                                    new com.legend.protocol.spec.ColSpec(
                                            cs2.name(),
                                            (LambdaFunction) substitute(
                                                    cs2.function1(), lets),
                                            (LambdaFunction) substitute(
                                                    cs2.function2(), lets),
                                            ElqSplice.keyAlias(cs2, lets),
                                            substituteAll(cs2.args(), lets),
                                            cs2.qualified()))
                                    .toList());
            // ^X(prop=$let, ...) / ^$let(prop=...) — the binding
            // EXPRESSIONS read lets too (the XStore runtime copy-ctors:
            // ^$dbRuntime(connectionStores=$dbRuntime.connectionStores
            // ->concatenate(...)) reached the Typer with free vars)
            case NewInstance ni -> {
                Map<String, KeyExpression> props = new LinkedHashMap<>();
                ni.properties().forEach((k, ke) -> props.put(k,
                        new KeyExpression(subst(ke.value(), lets),
                                ke.isAdd(), ke.isLocal())));
                yield new NewInstance(ni.className(), ni.typeArguments(),
                        props);
            }
            default -> v.mapChildren(x -> subst(x, lets));
        };
    }

    private static List<ValueSpecification> substituteAll(List<ValueSpecification> vs,
            Map<String, ValueSpecification> lets) {
        List<ValueSpecification> out = new ArrayList<>(vs.size());
        for (ValueSpecification v : vs) {
            out.add(substitute(v, lets));
        }
        return out;
    }

    /** The chain's OUTER tail carries a sort — its order is a contract. */
    private static boolean endsInSort(@com.legend.Nullable ValueSpecification v) {
        // names compare by SIMPLE name uniformly — an FQN-spelled sort must
        // still count as sorted (audit 9: raw-name matching left FQN
        // spellings silently lenient)
        if (!(v instanceof AppliedFunction af)) {
            return false;
        }
        String fn = simpleName(af.function());
        if (fn.equals("sort") || fn.equals("sortBy")) {
            return true;
        }
        // order survives through order-preserving tails only. filter/
        // select/rename/restrict/concatenate-free projections preserve
        // pure's order too (audit 23 D1: their absence granted multiset
        // leniency where order was contractual — sweep-classified strict)
        return switch (fn) {
            case "map", "limit", "take", "drop", "slice", "rows", "toOne", "at",
                    "makeString", "toCSV", "toString", "from",
                    "filter", "select", "rename", "renameColumns", "restrict",
                    "project", "distinct" ->
                    !af.parameters().isEmpty() && endsInSort(af.parameters().get(0));
            default -> false;
        };
    }
}
