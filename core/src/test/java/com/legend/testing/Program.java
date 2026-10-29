// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend.testing;

import com.legend.Compiler;
import com.legend.ExecuteOptions;
import com.legend.Execution;
import com.legend.compiler.element.ModelContext;
import com.legend.exec.AssertListener;
import com.legend.exec.Sessions;

import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;

/**
 * A TEST BODY run through the one entry for a program ({@link Execution#runProgram}; the result boundary program's
 * track B, B2) for the tests OF the body driver's judging -- the assert arms, the verdict channel, the effect natives,
 * instance identity (track T, 2026-10-29): the body typed once, run on the caller's connection, every verdict the
 * platform reports kept in order. A test of a VALUE reads the product's output instead ({@link Lean}); a test of a
 * verdict reads what the platform judged, exactly as the corpus runner does. Test code only.
 */
public final class Program {

    private Program() {
    }

    /** One verdict the platform reported: the assert's name, whether it passed, its detail. */
    public record Verdict(String name, boolean pass, @com.legend.base.Nullable String detail) {
    }

    /** What a program left: its verdicts in order, and the last statement's side read through the twin's reader
     *  ({@link Lean#of}); null for a body whose last statement answers nothing. */
    public record Report(List<Verdict> verdicts, Lean.@com.legend.base.Nullable Result last) {

        /** Every verdict passed and there was at least one. */
        public boolean passed() {
            return !verdicts.isEmpty() && verdicts.stream().allMatch(Verdict::pass);
        }

        /** The last statement's side, which must exist. */
        public Lean.Result side() {
            if (last == null) {
                throw new AssertionError("the body's last statement answered nothing");
            }
            return last;
        }
    }

    /** {@code body} (a lambda text: {@code |assertEquals(1, 1)} or {@code {|let x = 1; assert(...);}}) over
     *  {@code model}, on {@code runtime}, on the caller's connection {@code c}, with the platform's default options. */
    public static Report run(String model, String body, @com.legend.base.Nullable String runtime, Connection c) {
        return run(model, body, runtime, c, ExecuteOptions.NONE);
    }

    /** The last statement's side of {@code body} run through the program entry, read through the twin's reader: for
     *  a body only the program entry runs (a read of the system metamodel store, a DDL native, a staged let). */
    public static Lean.Result last(String model, String body, @com.legend.base.Nullable String runtime, Connection c) {
        return run(model, body, runtime, c).side();
    }

    /** {@link #run(String, String, String, Connection)} with the caller's execute {@code options} (a judge mode). */
    public static Report run(String model, String body, @com.legend.base.Nullable String runtime, Connection c,
            ExecuteOptions options) {
        ModelContext ctx = Compiler.compileModel(model);
        List<Verdict> verdicts = new ArrayList<>();
        AssertListener listener = new AssertListener() {
            @Override
            public void verdict(String assertName, boolean pass, @com.legend.base.Nullable String detail) {
                verdicts.add(new Verdict(assertName, pass, detail));
            }
        };
        Execution.ProgramReport report = Execution.runProgram(Compiler.query(ctx, body), runtime, Sessions.given(c),
                listener, null, options);
        return new Report(List.copyOf(verdicts), report.last() == null ? null : Lean.of(report.last()));
    }
}
