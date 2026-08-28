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
        EngineTestExecutor.Eval exp = EngineTestExecutor.eval(EngineTestExecutor.subst(args.get(0), lets),
                lets, execStmts, execVars, execChains, ctx, imports,
                runtimeFqn, conn);
        EngineTestExecutor.Eval got = EngineTestExecutor.eval(EngineTestExecutor.subst(args.get(1), lets),
                lets, execStmts, execVars, execChains, ctx, imports,
                runtimeFqn, conn);
        double delta = asNumber(EngineTestExecutor.eval(EngineTestExecutor.subst(args.get(2), lets),
                lets, execStmts, execVars, execChains, ctx, imports,
                runtimeFqn, conn).values());
        double timeDelta = args.size() == 4 ? asNumber(EngineTestExecutor.eval(
                EngineTestExecutor.subst(args.get(3), lets), lets, execStmts, execVars,
                execChains, ctx, imports, runtimeFqn, conn).values()) : 0.0;
        return com.legend.exec.TdsCompare.tdsEquivalent(
                exp.values(), got.values(), delta, timeDelta);
    }

    /** First value as a double — the delta operands are literal numbers. */
    static double asNumber(List<Object> values) {
        return values.size() == 1 && values.get(0) instanceof Number n
                ? n.doubleValue() : 0.0;
    }
}
