// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend;

import com.legend.compiler.spec.typed.TypedCBoolean;
import com.legend.compiler.spec.typed.TypedCString;
import com.legend.compiler.spec.typed.TypedSpec;
import com.legend.exec.ExecutionResult;

/**
 * BARE-LITERAL FOLD (2026-08-15 round-trip census): a bare string or
 * boolean literal in result position is already a value — the SQL path
 * renders {@code SELECT '<text>' AS value} and reads the identical
 * value back, an identity with a round-trip tax that dominated the
 * corpus (344k of 357k plan executions, mostly executeInDb argument
 * strings). The engine's own plans fold constants in memory
 * (ConstantExecutionNode), so folding is engine-faithful; the tenet's
 * point (no shadow EVALUATOR) is untouched — nothing is computed, the
 * constant unwraps. ONLY the bare node folds: composites still lower
 * to SQL, and numeric/date literals ride the DB path so their
 * type-lattice coercions stay byte-identical.
 */
final class LiteralFold {

    private LiteralFold() {
    }

    static @Nullable ExecutionResult fold(TypedSpec root) {
        if (root instanceof TypedCString s) {
            return new ExecutionResult.Scalar(s.value(), root.info().type());
        }
        if (root instanceof TypedCBoolean b) {
            return new ExecutionResult.Scalar(b.value(), root.info().type());
        }
        return null;
    }
}
