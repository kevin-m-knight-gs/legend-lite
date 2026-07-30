// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend.harness;

import com.legend.compiler.element.ModelContext;
import com.legend.model.ImportScope;
import com.legend.model.spec.AppliedFunction;
import com.legend.model.spec.AppliedProperty;
import com.legend.model.spec.CString;
import com.legend.model.spec.LambdaFunction;
import com.legend.model.spec.ValueSpecification;

import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The PLAN-TEXT assert channel (relocated from {@link TestBody} at the
 * file guardrail): planToString-family subtrees inline as literal text
 * through the K-native; plan asserts compare the literal strings; walls
 * stay SHAPE, scoped to plan asserts only.
 */
final class PlanAsserts {

    private PlanAsserts() {
    }

    /** A plan-handle WALK chain: reads rootExecutionNode over an
     * executionPlan call — its terminal value (sqlQuery text) compares
     * LITERALLY like plan text. */
    static boolean containsPlanWalk(@com.legend.Nullable ValueSpecification v) {
        return (TestBody.walkHasProp(v, "rootExecutionNode")
                && TestBody.walkHasCall(v))
                // ->sqlRemoveFormatting() over an executionPlan binding is
                // the literal plan-text read by another spelling — there
                // are no rows to verify (execute()-based reads never carry
                // an executionPlan call and keep the rows-first policy)
                || (hasCallNamed(v, "sqlRemoveFormatting")
                        && hasCallNamed(v, "executionPlan"));
    }

    private static boolean hasCallNamed(@com.legend.Nullable ValueSpecification v, String simple) {
        if (v instanceof AppliedFunction af) {
            if (TestBody.simpleName(af.function()).equals(simple)) {
                return true;
            }
            for (ValueSpecification x : af.parameters()) {
                if (hasCallNamed(x, simple)) {
                    return true;
                }
            }
        } else if (v instanceof AppliedProperty ap) {
            return hasCallNamed(ap.receiver(), simple);
        }
        return false;
    }

    static boolean containsPlanToString(@com.legend.Nullable ValueSpecification v) {
        if (v instanceof AppliedFunction af) {
            if (TestBody.simpleName(af.function()).equals("planToString")
                    || TestBody.simpleName(af.function())
                            .equals("planToStringWithoutFormatting")) {
                return true;
            }
            for (ValueSpecification x : af.parameters()) {
                if (containsPlanToString(x)) {
                    return true;
                }
            }
        } else if (v instanceof AppliedProperty ap) {
            return containsPlanToString(ap.receiver());
        }
        return false;
    }

    /** A predicate over PLAN TEXT (indexOf/contains chains): the
     * plan-channel text inlines as a literal, the rest evaluates
     * ordinarily (host-string semantics); walls stay SHAPE. */
    static @com.legend.Nullable String planPredicateAssert(AppliedFunction af,
            List<ValueSpecification> args,
            Map<String, ValueSpecification> lets,
            List<ValueSpecification> execStmts, java.util.Set<String> execVars,
            Map<String, ValueSpecification> execChains, ModelContext ctx,
            ImportScope imports, String runtimeFqn, Connection conn)
            throws java.sql.SQLException {
        try {
            ValueSpecification inlined = inlinePlanText(
                    TestBody.subst(args.get(0), lets), lets, execStmts,
                    execVars, execChains, ctx, imports, runtimeFqn, conn);
            Object pv = TestBody.evalScalar(java.util.Objects.requireNonNull(inlined,
                    "plan-text assert without an inlined plan"),
                    lets, execStmts, execVars,
                    execChains, ctx, imports, runtimeFqn, conn);
            boolean pexp = af.function().equals("assert");
            return Boolean.valueOf(pexp).equals(pv) ? null
                    : "assert" + (pexp ? "" : "False")
                            + " did not hold (" + pv + ")";
        } catch (com.legend.error.NotImplementedException
                | com.legend.error.LegendCompileException
                | UnsupportedOperationException pw) {
            return TestBody.unsupported("plan wall: " + pw.getMessage());
        }
    }

    /** Replace each planToString-family SUBTREE with the literal text
     * the plan channel produces — the surrounding host-string predicate
     * then evaluates ordinarily. */
    static @com.legend.Nullable ValueSpecification inlinePlanText(ValueSpecification v,
            Map<String, ValueSpecification> lets,
            List<ValueSpecification> execStmts, java.util.Set<String> execVars,
            Map<String, ValueSpecification> execChains, ModelContext ctx,
            ImportScope imports, String runtimeFqn, Connection conn)
            throws java.sql.SQLException {
        if (v instanceof AppliedFunction af) {
            String fn = TestBody.simpleName(af.function());
            if (fn.equals("planToString")
                    || fn.equals("planToStringWithoutFormatting")) {
                TestBody.Eval e = TestBody.eval(v, lets, execStmts, execVars, execChains,
                        ctx, imports, runtimeFqn, conn);
                return new CString(String.valueOf(e.values().get(0)));
            }
            List<ValueSpecification> ps = new ArrayList<>();
            for (ValueSpecification x : af.parameters()) {
                ps.add(inlinePlanText(x, lets, execStmts, execVars,
                        execChains, ctx, imports, runtimeFqn, conn));
            }
            return new AppliedFunction(af.function(), ps);
        }
        return v;
    }

    /** LL_TMP_DEBUG first-diff print for a plan-text golden mismatch. */
    static void planGoldenDebug(String tag, String a2, String b2) {
        if (System.getenv("LL_TMP_DEBUG") == null) {
            return;
        }
        int i2 = 0;
        while (i2 < a2.length() && i2 < b2.length()
                && a2.charAt(i2) == b2.charAt(i2)) {
            i2++;
        }
        System.err.println("[" + tag + "] lens " + a2.length() + "/"
                + b2.length() + " firstDiff@" + i2 + " E<"
                + a2.substring(Math.max(0, i2 - 60), Math.min(a2.length(), i2 + 120))
                + "> G<"
                + b2.substring(Math.max(0, i2 - 60), Math.min(b2.length(), i2 + 120))
                + ">");
    }

    static @com.legend.Nullable String planTextAssert(List<ValueSpecification> args,
            Map<String, ValueSpecification> lets,
            List<ValueSpecification> execStmts, java.util.Set<String> execVars,
            Map<String, ValueSpecification> execChains, ModelContext ctx,
            ImportScope imports, String runtimeFqn, Connection conn)
            throws java.sql.SQLException {
        try {
            TestBody.Eval pa = TestBody.eval(args.get(args.size() - 1), lets, execStmts,
                    execVars, execChains, ctx, imports, runtimeFqn, conn);
            TestBody.Eval pe = TestBody.eval(args.get(0), lets, execStmts, execVars,
                    execChains, ctx, imports, runtimeFqn, conn);
            if (TestBody.compare(pe, pa, true)) {
                return null;
            }
            if (args.size() == 3) {
                TestBody.Eval p2 = TestBody.eval(args.get(1), lets, execStmts, execVars,
                        execChains, ctx, imports, runtimeFqn, conn);
                if (TestBody.compare(p2, pa, true)) {
                    return null;
                }
                planGoldenDebug("plan-2golden", p2.render(), pa.render());
            }
            planGoldenDebug("plan-golden", pe.render(), pa.render());
            return "assertEquals: expected " + pe.render() + ", got "
                    + pa.render();
        } catch (com.legend.error.NotImplementedException
                | com.legend.error.LegendCompileException
                | UnsupportedOperationException pw) {
            // the PLAN surface is a pending vocabulary — its typing/
            // resolution walls are SHAPE, scoped to plan asserts only
            // (UnsupportedOperation = the sql layer's standalone wall
            // type; com.legend.sql cannot reference com.legend.error)
            if (System.getenv("LL_TMP_DEBUG") != null) {
                System.err.println("[plan-wall] " + pw);
            }
            return TestBody.unsupported("plan wall: " + pw.getMessage());
        }
    }
}
