// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend.resolver;

import com.legend.compiler.spec.typed.TypedGetAll;
import com.legend.compiler.spec.typed.TypedSpec;

/** POST-CONDITION (core/README rule 9): no {@code TypedGetAll} or
 * {@code TypedUserCall} survives store resolution — a RESOLVER-phase
 * gap named as such, with its ancestry path. */
final class StoreEscapees {

    private StoreEscapees() {
    }

    static void check(TypedSpec n) {
        check(n, "root");
    }

    private static void check(TypedSpec n, String path) {
        if (n instanceof TypedGetAll ga) {
            throw new com.legend.error.NotImplementedException(
                    "store resolution left getAll(" + ga.classFqn()
                    + ") unresolved — the query shape around it is not"
                    + " supported by the resolver yet [at " + path + "]");
        }
        if (n instanceof com.legend.compiler.spec.typed.TypedUserCall uc) {
            throw new com.legend.error.NotImplementedException(
                    "store resolution left user call '"
                    + uc.callee().qualifiedName()
                    + "' uninlined — the call shape is not supported by the"
                    + " resolver yet [at " + path + "]");
        }
        String next = path + " > " + n.getClass().getSimpleName();
        for (TypedSpec c : n.children()) {
            check(c, next);
        }
    }
}
