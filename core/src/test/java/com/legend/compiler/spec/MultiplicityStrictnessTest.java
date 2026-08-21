// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend.compiler.spec;

import com.legend.Compiler;
import com.legend.compiler.spec.typed.TypedSpec;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the STRICT lower-bound kernel (multiplicity audit
 * docs/MULTIPLICITY_AUDIT_2026_08_20.md §1, slice 2): real pure's
 * {@code MultiplicityMatch} rejects {@code [0..1]} into a {@code [1]}
 * slot — that is precisely why {@code toOne()} exists. Before this
 * slice the kernel accepted it and manufactured a false {@code [1]}
 * on the most common expression shape in Legend; ZERO tests pinned
 * the rejection.
 */
class MultiplicityStrictnessTest {

    private static final String MODEL =
            "Class m::Person { name: String[1]; middleName: String[0..1]; "
                    + "nicks: String[*]; }\n";

    private static Exception rejects(String query) {
        return assertThrows(Exception.class,
                () -> Compiler.compileQuery(MODEL, query));
    }

    @Test
    @DisplayName("audit §1: [0..1] property into a [1] native slot is REJECTED")
    void optionalPropertyIntoToOneSlotRejects() {
        // the audit's reproduction table, row 1: this used to stamp [1]
        Exception e = rejects(
                "m::Person.all()->map(p|$p.middleName->toUpper())");
        assertTrue(e.getMessage().contains("[0..1] is not compatible with [1]"),
                e.getMessage());
    }

    @Test
    @DisplayName("audit §1: [0..1] into arithmetic is REJECTED; toOne() fixes it")
    void optionalIntoArithmeticRejectsAndToOneConforms() {
        // multi-overload natives reject at SELECTION (scoring mirrors
        // the strict containment — the kernel-halves agreement)
        assertTrue(rejects("m::Person.all()->map(p|$p.middleName + '!')")
                        .getMessage().contains("no overload"),
                "plus over a [0..1] operand must reject");
        // the sanctioned spelling compiles
        TypedSpec ok = Compiler.compileQuery(MODEL,
                "m::Person.all()->map(p|$p.middleName->toOne() + '!')");
        assertEquals("[*]", ok.info().multiplicity().text());
    }

    @Test
    @DisplayName("control: [*] into [1] still rejected (the pre-existing guard)")
    void manyIntoToOneSlotStillRejects() {
        assertTrue(rejects("m::Person.all()->map(p|$p.nicks->toUpper())")
                .getMessage().contains("not compatible"));
    }

    @Test
    @DisplayName("audit §1b: declared return [1] with a [0..1] body is REJECTED at inline")
    void declaredReturnStricterThanBodyRejects() throws Exception {
        try (Connection c = DriverManager.getConnection("jdbc:duckdb:")) {
            Exception e = assertThrows(Exception.class, () -> Compiler.execute(
                    MODEL + "function m::f(a: String[0..1]): String[1] { $a }\n",
                    "|m::f('x')", c));
            assertTrue(String.valueOf(e.getMessage())
                            .contains("[0..1] is not compatible with [1]"),
                    e.getMessage());
        }
    }

    @Test
    @DisplayName("audit §1b: declared return [3] with a [2] body is REJECTED at inline")
    void declaredReturnCountMismatchRejects() throws Exception {
        try (Connection c = DriverManager.getConnection("jdbc:duckdb:")) {
            Exception e = assertThrows(Exception.class, () -> Compiler.execute(
                    MODEL + "function m::g(): String[3] { ['a', 'b'] }\n",
                    "|m::g()", c));
            assertTrue(String.valueOf(e.getMessage()).contains("not compatible"),
                    e.getMessage());
        }
    }

    @Test
    @DisplayName("audit §2: [1,2]->toOne() raises PURE's user error in the database, not an internal assertion")
    void literalCollectionToOneRaisesUserError() throws Exception {
        try (Connection c = DriverManager.getConnection("jdbc:duckdb:")) {
            Exception e = assertThrows(Exception.class,
                    () -> Compiler.execute("", "{| [1,2]->toOne() }", c));
            assertTrue(String.valueOf(e.getMessage())
                            .contains("Cannot cast a collection of size 2"
                                    + " to multiplicity [1]"),
                    e.getMessage());
            // the singleton extracts — the guard is size-exact
            var ok = Compiler.execute("", "{| [7]->toOne() }", c);
            assertEquals(7L, ((Number) ((com.legend.exec.ExecutionResult
                    .Scalar) ok).value()).longValue());
        }
    }

    @Test
    @DisplayName("audit §3: a runtime-emptied list through toOne() raises size-0 (the lower bound, checked in SQL)")
    void runtimeEmptyListToOneRaises() throws Exception {
        try (Connection c = DriverManager.getConnection("jdbc:duckdb:")) {
            Exception e = assertThrows(Exception.class, () -> Compiler.execute(
                    "", "{| [1,2,3]->filter(x|$x > 10)->toOne() }", c));
            assertTrue(String.valueOf(e.getMessage())
                            .contains("Cannot cast a collection of size 0"),
                    e.getMessage());
        }
    }

    @Test
    @DisplayName("lambda-RESULT covariance: a [0..1] key conforms to sortBy's {T[1]->U[1]} (engine-observed)")
    void lambdaResultLowerBoundIsCovariant() {
        // the reference's own corpus compiles sortBy over optional
        // association paths; only the VALUE slots are strict
        Compiler.compileQuery(MODEL,
                "m::Person.all()->sortBy(p|$p.middleName)");
    }
}
