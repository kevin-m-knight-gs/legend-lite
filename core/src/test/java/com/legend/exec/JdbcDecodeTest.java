// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend.exec;

import com.legend.executionplan.ExecutionPlan;
import com.legend.values.PureDateLiteral;
import com.legend.values.PureTimeLiteral;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The typed seam's decode (docs/PLAN_AND_RESULT_MODEL_2026_10_18.md §3 and §4; the plan path program's step 7,
 * 2026-10-18): a JDBC session's typed rows, each cell read from the driver by the plan's column -- the slot first,
 * the Pure type where the slot is none -- as the one carrier the judges read; on DuckDB and on H2, whose drivers
 * hand the same kinds back in different Java classes.
 */
class JdbcDecodeTest {

    private static ExecutionPlan.Column col(String name, String type, String slot) {
        return new ExecutionPlan.Column(name, type, new ExecutionPlan.Multiplicity(1, 1), slot, null);
    }

    private static ExecutionPlan.Column opt(String name, String type, String slot) {
        return new ExecutionPlan.Column(name, type, new ExecutionPlan.Multiplicity(0, 1), slot, null);
    }

    private static ExecutionPlan.Column many(String name, String type, String slot) {
        return new ExecutionPlan.Column(name, type, new ExecutionPlan.Multiplicity(0, null), slot, null);
    }

    private static List<List<Object>> rows(Connection c, String sql, List<ExecutionPlan.Column> columns)
            throws Exception {
        List<List<Object>> out = new ArrayList<>();
        // checked against its database first, as the runner checks every session (the timestamp re-read is its fact)
        JdbcStatements st = new JdbcStatements(c);
        st.check(new ExecutionPlan.Target(new ExecutionPlan.Database.Platform(
                c.getMetaData().getDatabaseProductName().equals("DuckDB")
                        ? com.legend.model.ConnectionDefinition.DatabaseType.DuckDB
                        : com.legend.model.ConnectionDefinition.DatabaseType.H2),
                new ExecutionPlan.Servers.Every(), List.of(), List.of()));
        st.rows(sql, List.of(), List.of(), columns, out::add);
        return out;
    }

    private static List<Object> row(Connection c, String sql, List<ExecutionPlan.Column> columns) throws Exception {
        List<List<Object>> rows = rows(c, sql, columns);
        assertEquals(1, rows.size(), sql);
        return rows.get(0);
    }

    @Test
    void theSlotDecidesTheCarrier_onDuckDb() throws Exception {
        try (Connection c = DriverManager.getConnection("jdbc:duckdb:")) {
            List<Object> r = row(c, "SELECT 7::BIGINT AS i, 'seven' AS s, 1.5::DOUBLE AS f, 2.50::DECIMAL(10,2) AS d,"
                    + " DATE '2024-01-02' AS day, TIMESTAMP '2024-01-02 10:30:00.123' AS ts, TIME '10:30:00' AS t,"
                    + " true AS b, NULL::INTEGER AS n, [1, 2]::BIGINT[] AS l, {'name': 'a', 'age': 1} AS st,"
                    + " '{\"k\": 1}'::JSON AS j, 170141183460469231731687303715884105727::HUGEINT AS h",
                    List.of(col("i", "Integer", "BIGINT"), col("s", "String", "VARCHAR"), col("f", "Float", "DOUBLE"),
                            col("d", "Decimal", "DECIMAL(10,2)"), col("day", "StrictDate", "DATE"),
                            col("ts", "DateTime", "TIMESTAMP"), col("t", "Time", "TIME"), col("b", "Boolean", "BOOLEAN"),
                            opt("n", "Integer", "INTEGER"), many("l", "Integer", "ARRAY<BIGINT>"),
                            col("st", "t::P", "STRUCT<name VARCHAR, age INTEGER>"),
                            col("j", "meta::pure::metamodel::variant::Variant", "JSON"),
                            col("h", "Integer", "HUGEINT")));
            assertEquals(7L, r.get(0));
            assertEquals("seven", r.get(1));
            assertEquals(1.5d, r.get(2));
            assertEquals(new BigDecimal("2.50"), r.get(3));
            assertEquals(PureDateLiteral.parse("2024-01-02"), r.get(4));
            // a timestamp as the engine reads one: nine digits (PureDateLiteral.fromDatabaseTimestamp)
            assertEquals(PureDateLiteral.parse("2024-01-02T10:30:00.123000000"), r.get(5));
            assertEquals(PureTimeLiteral.parse("10:30:00"), r.get(6));
            assertEquals(true, r.get(7));
            assertNull(r.get(8));
            assertEquals(List.of(1L, 2L), r.get(9));
            assertEquals(Map.of("name", "a", "age", 1L), r.get(10));
            assertEquals("{\"k\":1}", r.get(11));
            assertEquals(new BigInteger("170141183460469231731687303715884105727"), r.get(12));
        }
    }

    @Test
    void theSlotDecidesTheCarrier_onH2() throws Exception {
        try (Connection c = DriverManager.getConnection("jdbc:h2:mem:jdbcdecode" + H2Settings.SETTINGS)) {
            List<Object> r = row(c, "SELECT CAST(7 AS BIGINT) AS I, 'seven' AS S, CAST(1.5 AS DOUBLE) AS F,"
                    + " CAST(2.50 AS DECIMAL(10,2)) AS D, DATE '2024-01-02' AS DAY,"
                    + " TIMESTAMP '2024-01-02 10:30:00.123' AS TS, TIME '10:30:00' AS T, TRUE AS B,"
                    + " CAST(NULL AS INTEGER) AS N, JSON '{\"k\": 1}' AS J,"
                    + " (SELECT SUM(X) FROM (VALUES (CAST(1.5 AS DOUBLE)), (CAST(2.5 AS DOUBLE))) AS V(X)) AS SUMMED",
                    List.of(col("I", "Integer", "BIGINT"), col("S", "String", "VARCHAR"), col("F", "Float", "DOUBLE"),
                            col("D", "Decimal", "DECIMAL(10,2)"), col("DAY", "StrictDate", "DATE"),
                            col("TS", "DateTime", "TIMESTAMP"), col("T", "Time", "TIME"), col("B", "Boolean", "BOOLEAN"),
                            opt("N", "Integer", "INTEGER"), col("J", "meta::pure::metamodel::variant::Variant", "JSON"),
                            col("SUMMED", "Float", "DOUBLE")));
            assertEquals(7L, r.get(0));
            assertEquals("seven", r.get(1));
            assertEquals(1.5d, r.get(2));
            assertEquals(new BigDecimal("2.50"), r.get(3));
            assertEquals(PureDateLiteral.parse("2024-01-02"), r.get(4));
            assertEquals(PureDateLiteral.parse("2024-01-02T10:30:00.123000000"), r.get(5));
            assertEquals(PureTimeLiteral.parse("10:30:00"), r.get(6));
            assertEquals(true, r.get(7));
            assertNull(r.get(8));
            assertEquals("{\"k\":1}", r.get(9));
            // H2's LEGACY mode hands a DOUBLE aggregate back as a BigDecimal: the slot says the double
            assertEquals(4.0d, r.get(10));
        }
    }

    /** A database whose driver re-reads no timestamp as java.time keeps the driver's Timestamp, the same date-time
     *  for an AD year (2026-10-21: Snowflake's driver refuses the re-read, and the typed seam re-read every timestamp
     *  regardless; the legacy executor's rule, {@code SqlDialect.refetchesTimestampAsJavaTime}). */
    @Test
    void aTimestampTheDriverDoesNotReReadIsTheDriversOwn() throws Exception {
        try (Connection c = DriverManager.getConnection("jdbc:h2:mem:jdbcdecode_keep" + H2Settings.SETTINGS);
                java.sql.Statement st = c.createStatement();
                java.sql.ResultSet rs = st.executeQuery("SELECT TIMESTAMP '2024-01-02 10:30:00.123456' AS TS")) {
            rs.next();
            assertTrue(rs.getObject(1) instanceof java.sql.Timestamp, "H2 hands a TIMESTAMP back as a Timestamp");
            List<ExecutionPlan.Column> cols = List.of(col("TS", "DateTime", "TIMESTAMP"));
            assertEquals(List.of(PureDateLiteral.parse("2024-01-02T10:30:00.123456000")),
                    JdbcDecode.row(rs, cols, false));
            assertEquals(JdbcDecode.row(rs, cols, true), JdbcDecode.row(rs, cols, false));
        }
    }

    /** A text the database holds under a numeric slot is the column's own text unless the Pure type is numeric (the
     *  engine's test store declares an INT over a VARCHAR column; step 6's finding), and a Float the store holds as
     *  an integer is the integer the database holds. */
    @Test
    void aTextUnderANumericSlotIsTheTextUnlessThePureTypeIsNumeric() throws Exception {
        try (Connection c = DriverManager.getConnection("jdbc:duckdb:")) {
            assertEquals("7", row(c, "SELECT '7' AS id", List.of(col("id", "String", "INTEGER"))).get(0));
            assertEquals(7L, row(c, "SELECT '7' AS id", List.of(col("id", "Integer", "INTEGER"))).get(0));
            assertEquals(1000000L, row(c, "SELECT 1000000::INTEGER AS q", List.of(col("q", "Float", "INTEGER"))).get(0));
            // a number under a text slot keeps its digits
            assertEquals("2.50", row(c, "SELECT 2.50::DECIMAL(10,2) AS d", List.of(col("d", "String", "VARCHAR"))).get(0));
        }
    }

    /** No slot (a late-bound grid's column): the Pure type decides, and a class or Any takes the driver's own kind. */
    @Test
    void withoutASlotThePureTypeDecides() throws Exception {
        try (Connection c = DriverManager.getConnection("jdbc:duckdb:")) {
            List<Object> r = row(c, "SELECT 7 AS i, 1.5 AS f, 'x' AS s, DATE '2024-01-02' AS d, 3 AS any",
                    List.of(col("i", "Integer", null), col("f", "Float", null), col("s", "String", null),
                            col("d", "Date", null), col("any", "meta::pure::metamodel::type::Any", null)));
            assertEquals(7L, r.get(0));
            assertEquals(1.5d, r.get(1));
            assertEquals("x", r.get(2));
            assertEquals(PureDateLiteral.parse("2024-01-02"), r.get(3));
            assertEquals(7L, r.get(4) instanceof Long ? 7L : r.get(4));
            assertEquals(3L, r.get(4));
        }
    }

    /** A cell no rule reads is refused by name, never guessed; a statement answering other columns than the plan's
     *  is refused before a row is read. */
    @Test
    void whatNoRuleReadsIsRefusedByName() throws Exception {
        try (Connection c = DriverManager.getConnection("jdbc:duckdb:")) {
            String refused = assertThrows(IllegalStateException.class,
                    () -> row(c, "SELECT 'x' AS b", List.of(col("b", "Boolean", "BOOLEAN")))).getMessage();
            assertTrue(refused.startsWith("column 'b' is declared Boolean in slot BOOLEAN, and the driver's value, a"
                    + " java.lang.String x, is not of that kind"), refused);
            String columns = assertThrows(IllegalStateException.class,
                    () -> row(c, "SELECT 1 AS a, 2 AS b", List.of(col("a", "Integer", "INTEGER")))).getMessage();
            assertEquals("the statement answers 2 columns, and the plan declares 1 [a]: a typed-rows statement"
                    + " answers the plan's columns in their order", columns);
        }
    }
}
