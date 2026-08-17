// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend.compiler.spec;

import com.legend.protocol.spec.AppliedFunction;
import com.legend.protocol.spec.CInteger;
import com.legend.protocol.spec.CString;
import com.legend.protocol.spec.LambdaFunction;
import com.legend.protocol.spec.ValueSpecification;
import com.legend.protocol.spec.Variable;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Pins for LET-SUBSTITUTION SEMANTICS (F3.2 groundwork): Pure lets are
 * EAGER, non-recursive value bindings — a let's right-hand side captures
 * the environment AS OF ITS OWN POSITION, and later re-bindings never
 * reach earlier captures (capture-at-binding, i.e. lexical/sequential
 * semantics; the engine evaluates statements in order).
 *
 * <p>These pins exist because the F3.2 corpus differential proved the
 * corpus NEVER exercises the distinguishing shapes — meaning nothing
 * else guards them. The harness's retired inliner (HarnessSubstitution)
 * resolved a let's RHS at USE time — dynamic scoping — and would answer
 * the {@link #laterRebindDoesNotReachEarlierCapture} case with 2; the
 * audit named that fork A8. The compiler's semantics pinned here is the
 * one every caller migrates onto.
 */
class SourceSubstTest {

    private static AppliedFunction let(String name, ValueSpecification v) {
        return new AppliedFunction("letFunction",
                List.of(new CString(name), v));
    }

    private static ValueSpecification folded(ValueSpecification... stmts) {
        LambdaFunction out = SourceSubst.inlineLets(
                new LambdaFunction(List.of(), List.of(stmts)));
        return out == null ? null : out.body().get(0);
    }

    @Test
    void theAuditCase_shadowingLetKeepsTheOuterFreeVariable() {
        // let a = $x; let x = 5; $a   =>   $a is the OUTER $x, never 5
        ValueSpecification r = folded(
                let("a", new Variable("x")),
                let("x", new CInteger(5L)),
                new Variable("a"));
        assertEquals(new Variable("x"), r,
                "a captured the FREE outer x at its binding; the later"
                + " let x must not reach it (dynamic scoping would say 5"
                + " — audit A8's fork)");
    }

    @Test
    void laterRebindDoesNotReachEarlierCapture() {
        // let x = 1; let a = $x; let x = 2; $a   =>   1
        ValueSpecification r = folded(
                let("x", new CInteger(1L)),
                let("a", new Variable("x")),
                let("x", new CInteger(2L)),
                new Variable("a"));
        assertEquals(new CInteger(1L), r,
                "a captured x's value AT BINDING (1); the rebind to 2"
                + " is later and must not reach it");
    }

    @Test
    void sequentialRebindWinsForReadsAfterIt() {
        // let x = 1; let x = 2; $x   =>   2
        ValueSpecification r = folded(
                let("x", new CInteger(1L)),
                let("x", new CInteger(2L)),
                new Variable("x"));
        assertEquals(new CInteger(2L), r,
                "reads AFTER a rebind see the latest binding — plain"
                + " sequential shadowing");
    }

    @Test
    void lambdaParameterShadowingStopsSubstitution() {
        // let x = 1; {x | $x}   =>   the inner $x is the BINDER, not 1
        LambdaFunction inner = new LambdaFunction(
                List.of(new Variable("x")),
                List.of(new Variable("x")));
        ValueSpecification r = folded(
                let("x", new CInteger(1L)),
                inner);
        assertEquals(inner, r,
                "a lambda parameter shadows the let for its whole body");
    }

    @Test
    void chainedCapturesFoldEagerly() {
        // let x = 1; let a = $x; $a   =>   1 (a's RHS already folded)
        ValueSpecification r = folded(
                let("x", new CInteger(1L)),
                let("a", new Variable("x")),
                new Variable("a"));
        assertEquals(new CInteger(1L), r);
    }

    @Test
    void lambdaLocalLetShadowsForStatementsBelowIt() {
        // let x = 1; {| $x; let x = 9; $x }  =>  first read folds to 1,
        // the read BELOW the lambda-local let stays the binder's
        // (F3.2b: real pure scoping — the plan-printer's injected
        // Allocation lets rely on it; the harness engine had it right
        // before the owner did)
        LambdaFunction inner = new LambdaFunction(List.of(), List.of(
                new Variable("x"),
                let("x", new CInteger(9L)),
                new Variable("x")));
        ValueSpecification r = folded(
                let("x", new CInteger(1L)),
                inner);
        LambdaFunction expect = new LambdaFunction(List.of(), List.of(
                new CInteger(1L),
                let("x", new CInteger(9L)),
                new Variable("x")));
        assertEquals(expect, r);
    }

    @Test
    void nonLetIntermediateRefusesLoudly() {
        // [stmt; final] with a non-let stmt: null — the caller keeps its
        // loud wall, never a silently dropped statement
        assertNull(SourceSubst.inlineLets(new LambdaFunction(List.of(),
                List.of(new CInteger(1L), new Variable("x")))));
    }
}
