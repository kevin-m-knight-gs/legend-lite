// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend;

import com.legend.model.ConnectionDefinition.DatabaseType;
import com.legend.testcases.PlanCases;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Object identity (docs/OBJECT_IDENTITY_2026_10_20.md): every evaluation of a construction makes a new object and a let
 * names one; {@code eq} is identity, and {@code ==} and everything built on it compare a keyed class by its keys and a
 * keyless class by identity. Each case is Pure's own answer, run through the plan on each embedded database that holds
 * an object as a value (H2 has no struct: its PCT lane runs the relation suite alone).
 */
class ObjectIdentityTest {

    private static final String MODEL = """
            Class s::P { n: Integer[1]; }
            Class s::Side { <<equality.Key>> id: String[1]; n: Integer[1]; }
            function s::mk():s::P[1] { ^s::P(n=1) }
            function s::same(p: s::P[1]):s::P[1] { $p }
            function s::mkAll(xs: Integer[*]):s::P[*] { $xs->map(x | ^s::P(n=1)) }
            """;

    /** Each case: Pure's answer, then the query. */
    private static final String[][] CASES = {
        // a let names one object; two constructions are two
        {"false", "|let a = ^s::P(n=1); let b = ^s::P(n=1); eq($a, $b);"},
        {"false", "|let a = ^s::P(n=1); let b = ^s::P(n=1); $a == $b;"},
        {"1", "|let a = ^s::P(n=1); [$a, $a]->removeDuplicates()->size();"},
        {"2", "|let a = ^s::P(n=1); [$a, $a, ^s::P(n=1)]->removeDuplicates()->size();"},
        {"false", "|let a = ^s::P(n=1); [^s::P(n=1)]->contains($a);"},
        // a keyed class compares its keys
        {"1", "|[^s::Side(id='a', n=1), ^s::Side(id='a', n=2)]->removeDuplicates()->size()"},
        {"true", "|[^s::Side(id='a', n=1)]->contains(^s::Side(id='a', n=2))"},
        {"true", "|^s::Side(id='a', n=1) == ^s::Side(id='a', n=2)"},
        {"1", "|[1, 2]->map(i | ^s::Side(id='a', n=$i))->removeDuplicates()->size()"},
        // a construction inside a map is evaluated once per element
        {"2", "|[1, 2]->map(i | ^s::P(n=1))->removeDuplicates()->size()"},
        {"4", "|[1, 2]->map(i | [^s::P(n=1), ^s::P(n=1)])->removeDuplicates()->size()"},
        {"4", "|[1, 2]->map(i | [3, 4]->map(j | ^s::P(n=1)))->removeDuplicates()->size()"},
        // a let in a lambda's body names one object per evaluation of the body
        {"2", "|[1, 2]->map(i | let a = ^s::P(n=$i); [$a, $a];)->removeDuplicates()->size()"},
        {"2", "|[1, 2]->map(i | let a = ^s::P(n=1); [$a, $a];)->removeDuplicates()->size()"},
        {"2", "|[1, 2]->map(i | let a = ^s::P(n=$i); $a == $a;)->filter(b | $b)->size()"},
        // an object built outside a map is one object inside it
        {"1", "|let a = ^s::P(n=1); [1, 2]->map(i | $a)->removeDuplicates()->size();"},
        // each call is an evaluation of its body; an argument is the caller's object
        {"false", "|s::mk() == s::mk()"},
        {"true", "|let a = ^s::P(n=1); s::same($a) == $a;"},
        {"2", "|[1, 2]->map(i | s::mk())->removeDuplicates()->size()"},
        {"4", "|s::mkAll([1, 2])->concatenate(s::mkAll([1, 2]))->removeDuplicates()->size()"},
        // a lambda's body is evaluated where the lambda is used
        {"2", "|let f = {x: Integer[1] | ^s::P(n=1)}; [1, 2]->map($f)->removeDuplicates()->size();"},
        {"false", "|let f = {| ^s::P(n=1)}; $f->eval() == $f->eval();"},
        {"true", "|let a = ^s::P(n=1); let f = {| $a}; $f->eval() == $f->eval();"},
    };

    @Test
    void everyEvaluationMakesAnObject_aLetNamesOne_everyDatabase() throws Exception {
        List<String> wrong = new ArrayList<>();
        for (DatabaseType type : List.of(DatabaseType.DuckDB, DatabaseType.SQLite)) {
            String model = com.legend.test.StorelessRuntime.with(MODEL, type);
            for (String[] c : CASES) {
                try (Connection connection = fresh(type)) {
                    String text = PlanCases.run(Compiler.query(Compiler.compileModel(model), c[1])
                            .executionPlan(com.legend.test.StorelessRuntime.RUNTIME, TypedQuery.Output.TDS_TEXT),
                            connection);
                    String got = text.substring(text.indexOf('\n') + 1);
                    if (!got.equals(c[0])) {
                        wrong.add(type + ": " + c[1] + " answered " + got + ", Pure " + c[0]);
                    }
                } catch (RuntimeException e) {
                    wrong.add(type + ": " + c[1] + " raised " + e.getMessage());
                }
            }
        }
        assertEquals(List.of(), wrong);
    }

    /** A map's result is the concatenation of its mapper's values: a mapper whose value is a list flattens. */
    @Test
    void aMapConcatenatesItsMappersValues() throws Exception {
        String model = com.legend.test.StorelessRuntime.with("Class s::Q { xs: Integer[*]; }", DatabaseType.DuckDB);
        String[][] cases = {
            {"3", "|[^s::Q(xs=[1, 2]), ^s::Q(xs=[3])]->map(q | $q.xs)->size()"},
            {"4", "|[1, 2]->map(i | [3, 4]->map(j | $i + $j))->size()"},
            {"4", "|[1, 2]->map(i | [3, 4]->filter(j | $j > 0))->size()"},
            {"2", "|[^s::Q(xs=[1, 2]), ^s::Q(xs=[3])]->map(q | $q.xs->size())->size()"},
        };
        for (String[] c : cases) {
            try (Connection connection = fresh(DatabaseType.DuckDB)) {
                String text = PlanCases.run(Compiler.query(Compiler.compileModel(model), c[1])
                        .executionPlan(com.legend.test.StorelessRuntime.RUNTIME, TypedQuery.Output.TDS_TEXT),
                        connection);
                assertEquals(c[0], text.substring(text.indexOf('\n') + 1), c[1]);
            }
        }
    }

    private static Connection fresh(DatabaseType type) throws SQLException {
        return switch (type) {
            case DuckDB -> DriverManager.getConnection("jdbc:duckdb:");
            case SQLite -> DriverManager.getConnection("jdbc:sqlite::memory:");
            default -> throw new IllegalArgumentException(type.name());
        };
    }
}
