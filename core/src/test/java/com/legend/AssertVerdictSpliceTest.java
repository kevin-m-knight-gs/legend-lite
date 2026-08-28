// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend;

import com.legend.exec.ExecutionResult;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * V7 batch 2, the SPLICE PIN (V7_ASSERT_VERDICT_CHARTER §5-4): a
 * statement-root assert whose ARGUMENTS read an {@code execute()}
 * handle adjudicates through the production verdict path — the
 * result-envelope splice ({@code ResultEnvelopeSplice}) serves the
 * verdict side evaluation exactly as it serves ordinary statements
 * (audit 19d B2: the platform's result frame owns the envelope).
 * Before this leg the verdict lane compiled {@code $result.values}
 * reads as raw variable reads and walled ("no row scope") — the
 * corpus dual-channel census's largest decline class.
 */
class AssertVerdictSpliceTest {

    private static final String MODEL = """
            Class e::Person { name: String[1]; age: Integer[1]; }
            function meta::pure::functions::asserts::assertEquals(expected:Any[*], actual:Any[*]):Boolean[1]
            {
                if(eq($expected->size(), 1) && eq($actual->size(), 1),
                   | assertEquals($expected, $actual, '\\nexpected: %r\\nactual:   %r', [$expected->toOne(), $actual->toOne()]),
                   | assertEquals($expected, $actual, '\\nexpected: %s\\nactual:   %s', [$expected->map(x | $x->toRepresentation())->joinStrings('[', ', ', ']'), $actual->map(x | $x->toRepresentation())->joinStrings('[', ', ', ']')]));
            }
            function meta::pure::functions::asserts::assertEquals(expected:Any[*], actual:Any[*], formatString:String[1], formatArgs:Any[*]):Boolean[1]
            {
                assert(equal($expected, $actual), $formatString, $formatArgs);
            }
            function meta::pure::functions::asserts::assert(condition:Boolean[1], formatString:String[1], formatArgs:Any[*]):Boolean[1]
            {
                assert($condition, | format($formatString, $formatArgs));
            }
            function meta::pure::functions::asserts::assertSize(collection:Any[*], size:Integer[1]):Boolean[1]
            {
                assertSize($collection, $size, '%s', ['size']);
            }
            function meta::pure::functions::asserts::assertSize(collection:Any[*], size:Integer[1], formatString:String[1], formatArgs:Any[*]):Boolean[1]
            {
                assert($collection->size() == $size, $formatString, $formatArgs);
            }
            ###Relational
            Database e::DB (
              Table P (ID INTEGER PRIMARY KEY, NAME VARCHAR(200), AGE INTEGER)
            )
            ###Mapping
            Mapping e::M (
              *e::Person : Relational { ~mainTable [e::DB] P
                name: P.NAME,
                age: P.AGE }
            )
            ###Runtime
            Runtime e::RT { mappings: [e::M]; }
            """;

    private static Connection conn;

    @BeforeAll
    static void setUp() throws SQLException {
        conn = DriverManager.getConnection("jdbc:duckdb:");
        try (Statement st = conn.createStatement()) {
            st.execute("CREATE TABLE P (ID INTEGER, NAME VARCHAR(200),"
                    + " AGE INTEGER)");
            st.execute("INSERT INTO P VALUES (10,'p1',30),(20,'p2',40)");
        }
    }

    @AfterAll
    static void tearDown() throws SQLException {
        conn.close();
    }

    private static ExecutionResult run(String query) throws SQLException {
        return Compiler.execute(MODEL, query, "e::RT", conn);
    }

    @Test
    @DisplayName("assertEquals over $result.values reads adjudicates (splice pin)")
    void assertOverExecuteHandleAdjudicates() throws Exception {
        Object v = ((ExecutionResult.Scalar) run(
                "{|let result = execute(|e::Person.all()"
                + "->filter(p|$p.age > 0), e::M, e::RT, []);"
                + " assertEquals(['p1','p2'],"
                + " $result.values.name->sort());}")).value();
        assertEquals(Boolean.TRUE, v);
    }

    @Test
    @DisplayName("polarity: a failing spliced assert still fails")
    void splicedAssertKeepsPolarity() {
        SQLException e = assertThrows(SQLException.class, () -> run(
                "{|let result = execute(|e::Person.all()"
                + "->filter(p|$p.age > 0), e::M, e::RT, []);"
                + " assertEquals(['zz'],"
                + " $result.values.name->sort());}"));
        assertTrue(String.valueOf(e.getMessage()).contains("zz"),
                e.getMessage());
    }

    @Test
    @DisplayName("assert condition + assertSize over frame reads adjudicate")
    void conditionAndSizeLanesSplice() throws Exception {
        Object c = ((ExecutionResult.Scalar) run(
                "{|let result = execute(|e::Person.all(), e::M, e::RT, []);"
                + " assert($result.values.name->size() == 2);}")).value();
        assertEquals(Boolean.TRUE, c);
        Object s = ((ExecutionResult.Scalar) run(
                "{|let result = execute(|e::Person.all(), e::M, e::RT, []);"
                + " assertSize($result.values.age, 2);}")).value();
        assertEquals(Boolean.TRUE, s);
    }
}
