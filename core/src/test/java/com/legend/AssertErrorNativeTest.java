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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * {@code assertError} — the platform native (One-Platform Plan Phase 4).
 * The behavioral spec is legend-pure's own: the {@code test.Test}
 * functions in essential/tests/assertError.pure (ported here — the
 * port-the-spec discipline) plus interpreted {@code AssertError.java}'s
 * contract (run f in the database, catch, adjudicate message and the
 * error's source-info channel with assertError.pure:24-26's exact
 * failure spellings).
 */
class AssertErrorNativeTest {

    private static Connection conn;

    @BeforeAll
    static void open() throws Exception {
        conn = DriverManager.getConnection("jdbc:duckdb:");
    }

    @AfterAll
    static void close() throws Exception {
        conn.close();
    }

    private static ExecutionResult run(String query) throws SQLException {
        return Compiler.execute("", query, conn);
    }

    // assertError.pure:36 testSimpleAssertError, verbatim body
    @Test
    @DisplayName("matching message passes (spec testSimpleAssertError)")
    void simpleAssertError() throws Exception {
        ExecutionResult r = run("{|assertError(|[1,2]->at(3),"
                + "'The system is trying to get an element at offset 3"
                + " where the collection is of size 2')}");
        assertEquals(Boolean.TRUE, ((ExecutionResult.Scalar) r).value());
    }

    @Test
    @DisplayName("message mismatch fails with assertError.pure:24's exact spelling")
    void messageMismatch() {
        SQLException e = assertThrows(SQLException.class, () -> run(
                "{|assertError(|[1,2]->at(3), 'wrong expectation')}"));
        assertEquals("Execution error message mismatch.\n"
                + "The actual message was \"The system is trying to get an"
                + " element at offset 3 where the collection is of size 2\"\n"
                + "where the expected message was:\"wrong expectation\"",
                e.getMessage());
    }

    @Test
    @DisplayName("no error is itself the failure (interpreted AssertError.java)")
    void noErrorThrown() {
        SQLException e = assertThrows(SQLException.class, () -> run(
                "{|assertError(|1 + 2, 'anything')}"));
        assertEquals("No error was thrown", e.getMessage());
    }

    // the Phase-4 redesign DELETED the U+001E span channel: source
    // position is not observable from a database error — non-empty
    // line/column expectations refuse LOUDLY (never a silent pass)
    @Test
    @DisplayName("line/column expectations refuse loudly — position is unobservable")
    void lineColumnRefusesLoudly() {
        assertThrows(com.legend.error.NotImplementedException.class, () -> run(
                "{|assertError(|[1,2]->at(3),"
                + "'The system is trying to get an element at offset 3"
                + " where the collection is of size 2', 1, 23)}"));
    }


    // assertError.pure:30 — the /2 overload delegates with [] line/[] col:
    // empty expectations skip the source-info checks
    @Test
    @DisplayName("empty line/column expectations skip the source-info checks")
    void emptyLineColumn() throws Exception {
        ExecutionResult r = run("{|assertError(|[1,2]->at(3),"
                + "'The system is trying to get an element at offset 3"
                + " where the collection is of size 2', [], [])}");
        assertEquals(Boolean.TRUE, ((ExecutionResult.Scalar) r).value());
    }

    // date.pure:69 testNewDateError shape — the date ctor's guards speak
    // DateFunctions.validateDay's exact spellings: too-large days name the
    // attempted y-m-d (month un-padded, MONTH-AWARE incl. leap years)
    @Test
    @DisplayName("date ctor guards: month-aware day validation, spec spellings")
    void newDateErrors() throws Exception {
        run("{|assertError(|date(2016, 13), 'Invalid month: 13')}");
        run("{|assertError(|date(2016, 12, 32), 'Invalid day: 2016-12-32')}");
        run("{|assertError(|date(2016, 2, 30), 'Invalid day: 2016-2-30')}");
        run("{|assertError(|date(2015, 2, 29), 'Invalid day: 2015-2-29')}");
        run("{|assertError(|date(2016, 12, 31, 24), 'Invalid hour: 24')}");
        // leap-year Feb 29 is LEGAL — no error is the assert failure
        SQLException e = assertThrows(SQLException.class, () -> run(
                "{|assertError(|date(2016, 2, 29), 'anything')}"));
        assertEquals("No error was thrown", e.getMessage());
    }

    // ---- the decoder (backend prefix strip only — the U+001E span
    // channel is DELETED; source position is unobservable) ----

    @Test
    @DisplayName("decode strips the backend's single-line error-kind prefix")
    void decodePrefix() {
        assertEquals("Cannot get hour for 2017", AssertErrorNative.decode(
                "Invalid Input Error: Cannot get hour for 2017"));
        assertEquals("expected:\nA\nactual:\nB", AssertErrorNative.decode(
                "Invalid Input Error: expected:\nA\nactual:\nB"));
        assertEquals("Could not convert string 'x' to INT64",
                AssertErrorNative.decode(
                        "Conversion Error: Could not convert string 'x'"
                        + " to INT64"));
    }
}
