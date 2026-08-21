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
        if (spec instanceof com.legend.compiler.spec.typed.TypedNativeCall ar
                && ar.args().size() == 2) {
            String q = ar.callee().qualifiedName();
            Long folded = switch (q) {
                case "meta::pure::functions::math::plus" ->
                        intOf(ar.args().get(0)) + intOf(ar.args().get(1));
                case "meta::pure::functions::math::minus" ->
                        intOf(ar.args().get(0)) - intOf(ar.args().get(1));
                case "meta::pure::functions::math::times" ->
                        intOf(ar.args().get(0)) * intOf(ar.args().get(1));
                default -> null;
            };
            if (folded != null) {
                return folded;
            }
        }
        throw new NotImplementedException(
                "dynamic slicing bounds are not lowered yet (literal expected), got "
                        + spec.getClass().getSimpleName());
    }

}
