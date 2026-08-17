// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend.harness;

import com.legend.compiler.spec.SourceSubst;
import com.legend.protocol.spec.AppliedFunction;
import com.legend.protocol.spec.AppliedProperty;
import com.legend.protocol.spec.CInteger;
import com.legend.protocol.spec.CString;
import com.legend.protocol.spec.LambdaFunction;
import com.legend.protocol.spec.PureCollection;
import com.legend.protocol.spec.ValueSpecification;
import com.legend.protocol.spec.Variable;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * F3.2e — the fork is DELETED (audit A8 closed): one substitution
 * engine, the compiler's. The harness injects CORPUS_FOLD (the
 * metaprogramming fold + the TDSNull wire sentinel) through
 * SourceSubst's PostFold hook. This test pins the hook's charter: on
 * every shape OUTSIDE the fold vocabulary, the hooked bridge and bare
 * SourceSubst produce IDENTICAL trees — the injection ADDS folds, it
 * never alters substitution semantics.
 */
class SubstitutionParityTest {

    private static AppliedFunction let(String name, ValueSpecification v) {
        return new AppliedFunction("letFunction",
                List.of(new CString(name), v));
    }

    private static void agree(ValueSpecification v,
            Map<String, ValueSpecification> lets) {
        assertEquals(SourceSubst.substitute(v, lets),
                EngineTestExecutor.substitute(v, lets),
                "the corpus PostFold altered plain substitution on: " + v);
    }

    @Test
    void enginesAgreeOnTheSharedSemantics() {
        Map<String, ValueSpecification> lets = Map.of(
                "x", new CInteger(1L),
                "s", new CString("v"));
        // plain reads, unknown vars, nested applications
        agree(new Variable("x"), lets);
        agree(new Variable("unknown"), lets);
        agree(new AppliedFunction("plus",
                List.of(new Variable("x"), new Variable("x"))), lets);
        // property chains (NOT pair projections — those are the harness
        // fold's business, deliberately outside the shared surface)
        agree(new AppliedProperty(new Variable("x"), "prop"), lets);
        // collections
        agree(new PureCollection(
                List.of(new Variable("x"), new Variable("s"))), lets);
        // lambda parameter shadowing
        agree(new LambdaFunction(List.of(new Variable("x")),
                List.of(new Variable("x"))), lets);
        // lambda-local let shadowing (the scoping F3.2b taught the owner)
        agree(new LambdaFunction(List.of(), List.of(
                new Variable("x"),
                let("x", new CInteger(9L)),
                new Variable("x"))), lets);
    }
}
