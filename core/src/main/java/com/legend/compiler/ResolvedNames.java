// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend.compiler;

import com.legend.protocol.spec.AppliedFunction;

import java.util.ArrayList;
import java.util.List;

/**
 * The catalog FQNs a RESOLVED call names — the one reading of the resolver's
 * output every front-door pass shares: an exact FQN names itself; a bare
 * call carries the resolver's {@code candidateFqns} (user overloads it left
 * for signature matching), and a bare NATIVE stays bare — the catalog's
 * bare-name index holds it at the call's arity (the typer's own rule).
 * No pass matches a spelling or reads imports on its own.
 */
public final class ResolvedNames {

    private ResolvedNames() {
    }

    public static List<String> referents(AppliedFunction af) {
        if (af.function().contains("::")) {
            return List.of(af.function());
        }
        List<String> out = new ArrayList<>(af.candidateFqns());
        com.legend.builtin.Pure.nativeFunctionsAt(af.function()).stream()
                .filter(n -> n.parameters().size() == af.parameters().size())
                .map(com.legend.model.NativeFunctionDefinition::qualifiedName)
                .filter(n -> !out.contains(n)).forEach(out::add);
        return out;
    }

    /** Whether the call names {@code fqn} (exactly, or among its referents). */
    public static boolean names(AppliedFunction af, String fqn) {
        return referents(af).contains(fqn);
    }
}
