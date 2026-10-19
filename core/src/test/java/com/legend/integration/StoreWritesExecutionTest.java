// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend.integration;

import com.legend.exec.ExecutionResult;
import com.legend.model.ConnectionDefinition.DatabaseType;
import com.legend.testbackend.TestBackend;
import com.legend.testbackend.TestBackends;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A write into a store table executed end to end (docs/WRITES_DESIGN_2026_10_18.md §4, L1): {@code rel->write(#>{db.t}#)}
 * typed by the checker, lowered to the DML statement that inserts the relation's rows ({@code Lowerer.lowerStoreWrite}),
 * rendered by the dialect and run by the database, its value the database's update count. A program that writes runs
 * in one transaction: its writes land together or not at all, and a transaction its caller opened is joined, not
 * committed. On DuckDB and H2 here; on Postgres 16 by {@code StoreWritesPostgresTest}.
 */
class StoreWritesExecutionTest {

    private static final String MODEL = """
            ###Relational
            Database w::DB (
              Table T (ID INTEGER PRIMARY KEY, NAME VARCHAR(20), AGE INTEGER)
              Table SRC (ID INTEGER, NAME VARCHAR(20), AGE INTEGER)
              Table REV (ID INTEGER, NAME VARCHAR(20), AGE INTEGER)
            )
            ###Pure
            Class w::Person { id: Integer[1]; name: String[0..1]; age: Integer[0..1]; }
            ###Mapping
            Mapping w::M (
              w::Person: Relational { ~mainTable [w::DB]SRC id: [w::DB]SRC.ID, name: [w::DB]SRC.NAME, age: [w::DB]SRC.AGE }
            )
            ###Connection
            RelationalDatabaseConnection w::Conn {
              store: w::DB;
              %s
            }
            ###Runtime
            Runtime w::RT { mappings: [ w::M ]; connections: [ w::DB: [ c: w::Conn ] ]; }
            """;

    @Test
    void duckDbWrites() throws Exception {
        writes(TestBackends.of(DatabaseType.DuckDB));
    }

    @Test
    void h2Writes() throws Exception {
        writes(TestBackends.of(DatabaseType.H2));
    }

    /** The checker refuses, by name, a write that names a column the table lacks, a value of another type, a
     *  destination that is not a store table, and a set value of many. */
    @Test
    void theCheckerRefusesAWriteTheTableCannotHold() {
        var ctx = com.legend.Compiler.compileModel(
                MODEL.formatted(TestBackends.of(DatabaseType.DuckDB).connectionBody()));
        for (var refused : List.of(
                List.of("#>{w::DB.SRC}#->extend(~X:r|1)->write(#>{w::DB.T}#)", "'X' is not a column of w::DB.T"),
                List.of("#>{w::DB.SRC}#->select(~[NAME])->rename(~NAME, ~AGE)->write(#>{w::DB.T}#)",
                        "cannot be written into w::DB.T's column 'AGE'"),
                List.of("#>{w::DB.T}#->update(r|true, ~[NOPE: r|1])", "'NOPE' is not a column of w::DB.T"),
                List.of("#>{w::DB.T}#->update(r|true, ~[AGE: r|'x'])", "cannot be written into w::DB.T's column 'AGE'"),
                List.of("#>{w::DB.T}#->update(r|true, ~[AGE: r|[1, 2]])", "a column holds at most one value"),
                List.of("#>{w::DB.SRC}#->select(~[ID, NAME])->merge(#>{w::DB.T}#, ~[AGE])",
                        "the key 'AGE' is not a column of the rows merged"))) {
            Exception e = assertThrows(Exception.class, () -> com.legend.Compiler.query(ctx, refused.get(0)).expression(),
                    refused.get(0));
            assertTrue(String.valueOf(e.getMessage()).contains(refused.get(1)), refused.get(0) + ": " + e.getMessage());
        }
    }

    /** Every check, on {@code backend}'s own workspace. */
    static void writes(TestBackend backend) throws Exception {
        try (Connection c = backend.openWorkspace()) {
            run(c, "CREATE TABLE T (ID INTEGER PRIMARY KEY, NAME VARCHAR(20), AGE INTEGER)");
            run(c, "CREATE TABLE SRC (ID INTEGER, NAME VARCHAR(20), AGE INTEGER)");
            run(c, "INSERT INTO SRC VALUES (1, 'a', 10), (2, 'b', 20), (3, 'c', 30)");
            String name = backend.name();
            // a filtered relation's rows into the table: the count is the database's
            assertEquals(2L, count(backend, c, "|#>{w::DB.SRC}#->filter(r|$r.AGE > 10)->write(#>{w::DB.T}#)"), name);
            assertEquals(List.of("2|b|20", "3|c|30"), rows(c), name);
            // some of the table's columns: the others take their default (null)
            assertEquals(1L, count(backend, c,
                    "|#>{w::DB.SRC}#->filter(r|$r.ID == 1)->select(~[ID, NAME])->write(#>{w::DB.T}#)"), name);
            assertEquals(List.of("1|a|null", "2|b|20", "3|c|30"), rows(c), name);
            assertTrue(c.getAutoCommit(), name + ": the execution restores the session's autocommit");
            // a failing write is the database's error, and the table is as it was
            assertThrows(RuntimeException.class,
                    () -> count(backend, c, "|#>{w::DB.SRC}#->write(#>{w::DB.T}#)"), name);
            assertEquals(List.of("1|a|null", "2|b|20", "3|c|30"), rows(c), name);
            assertTrue(c.getAutoCommit(), name);
            // a program's writes land together: the second's failure takes the first back
            run(c, "INSERT INTO SRC VALUES (4, 'd', 40)");
            assertThrows(RuntimeException.class, () -> count(backend, c, "{|#>{w::DB.SRC}#->filter(r|$r.ID == 4)"
                    + "->write(#>{w::DB.T}#); #>{w::DB.SRC}#->write(#>{w::DB.T}#);}"), name);
            assertEquals(List.of("1|a|null", "2|b|20", "3|c|30"), rows(c), name);
            assertTrue(c.getAutoCommit(), name);
            // a transaction the caller opened is joined: its rollback takes the write back
            run(c, "DELETE FROM T");
            c.setAutoCommit(false);
            assertEquals(4L, count(backend, c, "|#>{w::DB.SRC}#->write(#>{w::DB.T}#)"), name);
            assertEquals(4, rows(c).size(), name);
            c.rollback();
            c.setAutoCommit(true);
            assertEquals(List.of(), rows(c), name);
            // both writes of a program that succeeds land; its value is the last statement's
            assertEquals(2L, count(backend, c, "{|#>{w::DB.SRC}#->filter(r|$r.ID <= 2)->write(#>{w::DB.T}#);"
                    + " #>{w::DB.SRC}#->filter(r|$r.ID > 2)->write(#>{w::DB.T}#);}"), name);
            assertEquals(List.of("1|a|10", "2|b|20", "3|c|30", "4|d|40"), rows(c), name);
            // a class query's rows, its columns named as the table's
            run(c, "DELETE FROM T");
            assertEquals(2L, count(backend, c, "|w::Person.all()->filter(p|$p.age > 25)"
                    + "->project(~[ID:p|$p.id, NAME:p|$p.name])->write(#>{w::DB.T}#)"), name);
            assertEquals(List.of("3|c|null", "4|d|null"), rows(c), name);
            // update: the rows the predicate holds for, each column set from the row as it was
            run(c, "DELETE FROM T");
            assertEquals(4L, count(backend, c, "|#>{w::DB.SRC}#->write(#>{w::DB.T}#)"), name);
            assertEquals(2L, count(backend, c, "|#>{w::DB.T}#->update(r|$r.ID > 2,"
                    + " ~[AGE: r|$r.AGE->toOne() + 1, NAME: r|$r.NAME->toOne()->toUpper()])"), name);
            assertEquals(List.of("1|a|10", "2|b|20", "3|C|31", "4|D|41"), rows(c), name);
            // delete: the rows the predicate holds for
            assertEquals(1L, count(backend, c, "|#>{w::DB.T}#->delete(r|$r.NAME == 'C')"), name);
            assertEquals(List.of("1|a|10", "2|b|20", "4|D|41"), rows(c), name);
            // merge: the rows of a key the table holds updated, the others inserted
            assertEquals(4L, count(backend, c, "|#>{w::DB.SRC}#->merge(#>{w::DB.T}#, ~[ID])"), name);
            assertEquals(List.of("1|a|10", "2|b|20", "3|c|30", "4|d|40"), rows(c), name);
            // delete everything: the predicate true
            assertEquals(4L, count(backend, c, "|#>{w::DB.T}#->delete(r|true)"), name);
            assertEquals(List.of(), rows(c), name);
            // a source whose columns the database holds in another order than the store declares: written by name
            run(c, "CREATE TABLE REV (AGE INTEGER, NAME VARCHAR(20), ID INTEGER)");
            run(c, "INSERT INTO REV VALUES (70, 'g', 7)");
            assertEquals(1L, count(backend, c, "|#>{w::DB.REV}#->write(#>{w::DB.T}#)"), name);
            assertEquals(1L, count(backend, c, "|#>{w::DB.REV}#->merge(#>{w::DB.T}#, ~[ID])"), name);
            assertEquals(List.of("7|g|70"), rows(c), name);
        }
    }

    private static Object count(TestBackend backend, Connection c, String query) {
        ExecutionResult r = com.legend.Execution.execute(MODEL.formatted(backend.connectionBody()), query, "w::RT", c);
        return ((ExecutionResult.Scalar) java.util.Objects.requireNonNull(r)).value();
    }

    private static void run(Connection c, String sql) throws Exception {
        try (Statement st = c.createStatement()) {
            st.execute(sql);
        }
    }

    private static List<String> rows(Connection c) throws Exception {
        List<String> out = new ArrayList<>();
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery("SELECT ID, NAME, AGE FROM T ORDER BY ID")) {
            while (rs.next()) {
                out.add(rs.getInt(1) + "|" + rs.getString(2) + "|" + (rs.getObject(3) == null ? "null" : rs.getInt(3)));
            }
        }
        return out;
    }
}
