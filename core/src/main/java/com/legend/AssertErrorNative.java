// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend;

import com.legend.compiler.spec.SpecCompiler;
import com.legend.compiler.spec.typed.TypedCInteger;
import com.legend.compiler.spec.typed.TypedCString;
import com.legend.compiler.spec.typed.TypedCollection;
import com.legend.compiler.spec.typed.TypedLambda;
import com.legend.compiler.spec.typed.TypedNativeCall;
import com.legend.compiler.spec.typed.TypedSpec;
import com.legend.exec.ExecutionResult;

import java.util.ArrayList;
import java.util.List;

/**
 * The {@code assertError} K-arm (One-Platform Plan Phase 4): the platform
 * definition of {@code meta::pure::functions::asserts::assertError} — a
 * {@code PCT.platformOnly} native whose reference contract is interpreted
 * {@code AssertError.java}: run {@code f}; no error is itself the failure
 * ("No error was thrown"); a caught error hands (message, source info) to
 * the matcher. Here {@code f}'s body executes IN THE DATABASE through the
 * ordinary statement pipeline (tenet #1 — the database raises the error);
 * the orchestrator catches the database error, decodes the message and
 * the source-info channel ({@code com.legend.lowering.Scalars#withSrc} —
 * the U+001E-separated span a guard embedded), and adjudicates with the
 * pure {@code assertError/4} body's EXACT failure spellings
 * (assertError.pure:24-26). Adjudication of orchestration artifacts is
 * host-side by charter (the {@code exec.PureAsserts} precedent).
 */
final class AssertErrorNative {

    private AssertErrorNative() {
    }

    static ExecutionResult run(TypedNativeCall ae, List<TypedSpec> letPrefix,
            SpecCompiler specs, StatementExecutor.ExecEnv env,
            java.util.Deque<String> frames) throws java.sql.SQLException {
        if (!(ae.args().get(0) instanceof TypedLambda f)
                || !f.parameters().isEmpty()) {
            throw new com.legend.error.NotImplementedException(
                    "assertError whose function argument is not a"
                    + " zero-parameter lambda literal");
        }
        if (!(ae.args().get(1) instanceof TypedCString exp)) {
            throw new com.legend.error.NotImplementedException(
                    "assertError whose message argument is not a string"
                    + " literal");
        }
        String expected = exp.value();
        Long expLine = optionalInt(ae, 2);
        Long expCol = optionalInt(ae, 3);
        java.sql.SQLException caught = null;
        try {
            StatementExecutor.executeStatements(f.body(),
                    new ArrayList<>(letPrefix), specs, env,
                    new java.util.ArrayDeque<>(frames));
        } catch (java.sql.SQLException e) {
            caught = e;
        }
        if (caught == null) {
            // interpreted AssertError.java: PureAssertFail("No error was
            // thrown") — a FAIL, not an orchestration error
            throw new java.sql.SQLException("No error was thrown");
        }
        Decoded d = decode(String.valueOf(caught.getMessage()));
        if (!d.message().equals(expected)) {
            // assertError.pure:24 — the /4 body's assertEquals format,
            // verbatim
            throw new java.sql.SQLException(
                    "Execution error message mismatch.\nThe actual message"
                    + " was \"" + d.message() + "\"\nwhere the expected"
                    + " message was:\"" + expected + "\"");
        }
        if (expLine != null) {
            if (d.line() == null) {
                throw new com.legend.error.NotImplementedException(
                        "assertError line/column: the raising site carried"
                        + " no source-info channel (span-less guard — the"
                        + " typed-tree span channel grows per guard site)");
            }
            if (!expLine.equals(d.line())) {
                // assertError.pure:25 — verbatim
                throw new java.sql.SQLException("Execution error line"
                        + " mismatch. Actual: " + d.line()
                        + " where expected: " + expLine);
            }
        }
        if (expCol != null) {
            if (d.column() == null) {
                throw new com.legend.error.NotImplementedException(
                        "assertError line/column: the raising site carried"
                        + " no source-info channel (span-less guard — the"
                        + " typed-tree span channel grows per guard site)");
            }
            if (!expCol.equals(d.column())) {
                // assertError.pure:26 — verbatim
                throw new java.sql.SQLException("Execution error column"
                        + " mismatch. Actual: " + d.column()
                        + " where expected: " + expCol);
            }
        }
        return new ExecutionResult.Scalar(Boolean.TRUE,
                com.legend.compiler.element.type.Type.Primitive.BOOLEAN);
    }

    /** {@code Integer[0..1]} argument: a literal, {@code []}, or absent
     * (the 2-arg overload). Null = empty. */
    private static @com.legend.Nullable Long optionalInt(TypedNativeCall ae,
            int i) {
        if (ae.args().size() <= i) {
            return null;
        }
        TypedSpec a = ae.args().get(i);
        if (a instanceof TypedCollection c && c.elements().isEmpty()) {
            return null;
        }
        if (a instanceof TypedCInteger n) {
            return n.value().longValue();
        }
        throw new com.legend.error.NotImplementedException(
                "assertError line/column argument must be an integer"
                + " literal or []");
    }

    /** The decoded database error: pure-level message, and the raising
     * call's source position when the guard embedded one. */
    record Decoded(String message, @com.legend.Nullable Long line,
            @com.legend.Nullable Long column) {
    }

    /** Strip the backend's error-kind prefix ({@code "Invalid Input
     * Error: "} etc. — single-line kind, anchored at the start) and split
     * the U+001E source-info suffix. */
    static Decoded decode(String raw) {
        String msg = raw;
        java.util.regex.Matcher m = PREFIX.matcher(msg);
        if (m.find()) {
            msg = msg.substring(m.end());
        }
        int rs = msg.indexOf('\u001E');
        if (rs < 0) {
            return new Decoded(msg, null, null);
        }
        String src = msg.substring(rs + 1);
        msg = msg.substring(0, rs);
        // the channel is machine-written (withSrc emits line:col digits);
        // anything else is NOT the channel — the sentinel char appeared in
        // ordinary message text, keep the split-off part as message
        if (!SPAN.matcher(src).matches()) {
            return new Decoded(msg + '\u001E' + src, null, null);
        }
        int colon = src.indexOf(':');
        return new Decoded(msg, Long.parseLong(src.substring(0, colon)),
                Long.parseLong(src.substring(colon + 1)));
    }

    private static final java.util.regex.Pattern PREFIX =
            java.util.regex.Pattern.compile("^[A-Za-z]+(?: [A-Za-z]+)* Error: ");
    private static final java.util.regex.Pattern SPAN =
            java.util.regex.Pattern.compile("\\d{1,9}:\\d{1,9}");
}
