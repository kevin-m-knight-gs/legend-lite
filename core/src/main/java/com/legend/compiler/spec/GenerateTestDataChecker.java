// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend.compiler.spec;

import com.legend.compiler.element.type.ExprType;
import com.legend.compiler.element.type.Multiplicity;
import com.legend.compiler.element.type.Type;
import com.legend.compiler.spec.typed.TypedSpec;
import com.legend.compiler.spec.typed.TypedTestDataGen;
import com.legend.protocol.spec.AppliedFunction;
import com.legend.protocol.spec.LambdaFunction;
import com.legend.protocol.spec.PackageableElementPtr;

/**
 * {@code generateTestData(func, mapping, runtime, ...)} — TDG lane S2.
 * A RUNTIME data-extraction native (fetches execute through the
 * database), so no fold happens here: the checker validates the shape
 * (this CoreFn arm owns EVERY engine overload — 8 spellings differing
 * in parameters/exeCtx/hashStrings, testDataGeneration.pure:104-144 —
 * classified structurally by the orchestrator's parser) and emits the
 * protocol-capturing carrier {@link TypedTestDataGen}.
 */
public final class GenerateTestDataChecker {

    private static final String RESULT_FQN =
            "meta::relational::testDataGeneration::TestDataGenResult";

    private GenerateTestDataChecker() {
    }

    static TypedSpec check(Typer t, AppliedFunction af, Env env) {
        if (af.parameters().size() < 4
                || !(af.parameters().get(0) instanceof LambdaFunction)
                || !(af.parameters().get(1) instanceof PackageableElementPtr)) {
            // the harness β-inlines lets today; a let-bound argument is
            // un-witnessed until S4 (SourceSubst.inlineLets is the
            // mechanism that arm will reuse)
            throw new TypeInferenceException(
                    "generateTestData needs its query lambda and mapping"
                            + " reference INLINE at the call site (>=4 args;"
                            + " let-bound arguments are S4 work)");
        }
        return new TypedTestDataGen(af.parameters(),
                new ExprType(new Type.ClassType(RESULT_FQN),
                        Multiplicity.Bounded.ONE));
    }
}
