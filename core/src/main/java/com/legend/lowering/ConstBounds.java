// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend.lowering;

import com.legend.compiler.spec.typed.TypedCInteger;
import com.legend.compiler.spec.typed.TypedSpec;
import com.legend.error.NotImplementedException;

/**
 * STATIC slicing-bound evaluation: limit/slice/drop bounds must be
 * knowable at plan time (engine parity). Literals, identity wrappers
 * (first/toOne over a singleton), and CONSTANT integer arithmetic fold;
 * genuinely dynamic bounds stay the loud wall.
 */
final class ConstBounds {

    private ConstBounds() {
    }

    static long intOf(TypedSpec spec) {
        if (spec instanceof TypedCInteger c) {
            return c.value().longValue();
        }
        // identity wrappers over a literal bound fold (optional-limit's
        // `let l = 1->first(); ->limit($l)`: first/toOne over a singleton)
        if (spec instanceof com.legend.compiler.spec.typed.TypedNativeCall nc
                && !nc.args().isEmpty()
                && ("meta::pure::functions::collection::first".equals(nc.callee().qualifiedName())
                        || com.legend.builtin.Pure.isToOneCall(nc.callee().qualifiedName()))) {
            return intOf(nc.args().get(0));
        }
        if (spec instanceof com.legend.compiler.spec.typed.TypedCollection tc
                && tc.elements().size() == 1) {
            return intOf(tc.elements().get(0));
        }
        // CONSTANT integer arithmetic folds — the paginated desugar's
        // (page-1)*size over literals (the engine folds these at plan
        // time too); a genuinely dynamic operand stays the loud wall
        // (upstream's arithmetic is variadic — the run plus([a, b, …]) folds
        // left to right: sum, left-fold difference, product)
        if (spec instanceof com.legend.compiler.spec.typed.TypedNativeCall ar
                && ar.args().size() == 1
                && ar.args().get(0) instanceof com.legend.compiler.spec.typed.TypedCollection run
                && run.elements().size() >= 2) {
            String q = ar.callee().qualifiedName();
            java.util.function.LongBinaryOperator op = switch (q) {
                case com.legend.compiler.element.type.PlatformTypes.PLUS -> Long::sum;
                case com.legend.compiler.element.type.PlatformTypes.MINUS -> (x, y) -> x - y;
                case com.legend.compiler.element.type.PlatformTypes.TIMES -> (x, y) -> x * y;
                default -> null;
            };
            if (op != null) {
                long acc = intOf(run.elements().get(0));
                for (int i = 1; i < run.elements().size(); i++) {
                    acc = op.applyAsLong(acc, intOf(run.elements().get(i)));
                }
                return acc;
            }
        }
        throw new NotImplementedException(
                "dynamic slicing bounds are not lowered yet (literal expected), got "
                        + spec.getClass().getSimpleName());
    }

}
