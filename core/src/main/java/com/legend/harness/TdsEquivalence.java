// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend.harness;

import java.util.List;

/**
 * {@code assertTdsEquivalent} cell comparison (engine
 * tdsEquivalent.pure): numeric cells within {@code |delta|}, temporal
 * cells within {@code |timeDeltaInSeconds|} seconds, everything else by
 * rendered equality; both-empty cells agree. Rows compare IN ORDER
 * (the engine zips) — callers pass the already-sorted actual.
 */
final class TdsEquivalence {

    private TdsEquivalence() {
    }

    /** The whole assert arm: evaluate (expected, actual, delta[, timeDelta])
     *  through the pipeline and compare. */
    static @com.legend.Nullable String assertArm(
            List<com.legend.protocol.spec.ValueSpecification> args,
            java.util.Map<String, com.legend.protocol.spec.ValueSpecification> lets,
            List<com.legend.protocol.spec.ValueSpecification> execStmts,
            java.util.Set<String> execVars,
            java.util.Map<String, com.legend.protocol.spec.ValueSpecification> execChains,
            com.legend.compiler.element.ModelContext ctx,
            com.legend.model.ImportScope imports,
            String runtimeFqn,
            java.sql.Connection conn) throws java.sql.SQLException {
        TestBody.Eval exp = TestBody.eval(TestBody.subst(args.get(0), lets),
                lets, execStmts, execVars, execChains, ctx, imports,
                runtimeFqn, conn);
        TestBody.Eval got = TestBody.eval(TestBody.subst(args.get(1), lets),
                lets, execStmts, execVars, execChains, ctx, imports,
                runtimeFqn, conn);
        double delta = asNumber(TestBody.eval(TestBody.subst(args.get(2), lets),
                lets, execStmts, execVars, execChains, ctx, imports,
                runtimeFqn, conn).values());
        double timeDelta = args.size() == 4 ? asNumber(TestBody.eval(
                TestBody.subst(args.get(3), lets), lets, execStmts, execVars,
                execChains, ctx, imports, runtimeFqn, conn).values()) : 0.0;
        return compare(exp.values(), got.values(), delta, timeDelta);
    }

    static @com.legend.Nullable String compare(List<Object> expected,
            List<Object> got, double delta, double timeDeltaSeconds) {
        if (expected.size() != got.size()) {
            return "assertTdsEquivalent: expected " + expected.size()
                    + " cells, got " + got.size();
        }
        for (int i = 0; i < expected.size(); i++) {
            Object x = expected.get(i);
            Object y = got.get(i);
            if (x == null && y == null) {
                continue;
            }
            if (x instanceof Number nx && y instanceof Number ny) {
                if (Math.abs(nx.doubleValue() - ny.doubleValue())
                        <= Math.abs(delta)) {
                    continue;
                }
            } else {
                Double ex = epochSeconds(x);
                Double ey = epochSeconds(y);
                if (ex != null && ey != null) {
                    if (Math.abs(ex - ey) <= Math.abs(timeDeltaSeconds)) {
                        continue;
                    }
                } else if (String.valueOf(x).equals(String.valueOf(y))) {
                    continue;
                }
            }
            return "assertTdsEquivalent: cell " + i + " expected " + x
                    + ", got " + y;
        }
        return null;
    }

    private static @com.legend.Nullable Double epochSeconds(@com.legend.Nullable Object v) {
        return switch (v) {
            case java.time.LocalDate d ->
                    (double) d.toEpochDay() * 86400.0;
            case java.time.LocalDateTime dt ->
                    (double) dt.toEpochSecond(java.time.ZoneOffset.UTC);
            case java.time.Instant in -> (double) in.getEpochSecond();
            case java.sql.Timestamp ts -> ts.getTime() / 1000.0;
            case java.sql.Date d -> d.getTime() / 1000.0;
            case null, default -> null;
        };
    }

    /** First value as a double — the delta operands are literal numbers. */
    static double asNumber(List<Object> values) {
        return values.size() == 1 && values.get(0) instanceof Number n
                ? n.doubleValue() : 0.0;
    }
}
