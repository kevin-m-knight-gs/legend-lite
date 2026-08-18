// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend.plan;

import com.legend.compiler.spec.typed.TypedCInteger;
import com.legend.compiler.spec.typed.TypedCString;
import com.legend.compiler.spec.typed.TypedNativeCall;
import com.legend.compiler.spec.typed.TypedSpec;
import com.legend.compiler.spec.typed.TypedVariable;
import com.legend.error.NotImplementedException;

/**
 * A MINIMAL pure-source printer for {@code PureExp} plan nodes (the
 * engine prints the let expression's source): variables, string/integer
 * literals, and arrow-form native calls. Every other shape is a LOUD
 * wall — arms grow per golden, never by guess.
 */
public final class PurePrint {

    private PurePrint() {
    }

    public static String source(TypedSpec n) {
        return switch (n) {
            case TypedVariable v -> "$" + v.name();
            case TypedCString cs -> "'" + cs.value().replace("'", "\\'")
                    + "'";
            case TypedCInteger ci -> String.valueOf(ci.value());
            case TypedNativeCall nc -> {
                if (nc.args().isEmpty()) {
                    yield simpleName(nc) + "()";
                }
                StringBuilder sb = new StringBuilder(
                        source(nc.args().get(0)))
                        .append("->").append(simpleName(nc)).append('(');
                for (int i = 1; i < nc.args().size(); i++) {
                    if (i > 1) {
                        sb.append(", ");
                    }
                    sb.append(source(nc.args().get(i)));
                }
                yield sb.append(')').toString();
            }
            default -> throw new NotImplementedException(
                    "PureExp source printing for "
                    + n.getClass().getSimpleName() + " pending");
        };
    }

    private static String simpleName(TypedNativeCall nc) {
        String fqn = nc.callee().qualifiedName();
        return fqn.substring(fqn.lastIndexOf(':') + 1);
    }
}
