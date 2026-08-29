// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend.harness;

import com.legend.protocol.spec.AppliedFunction;
import com.legend.protocol.spec.ValueSpecification;
import com.legend.protocol.spec.Variable;

import java.util.List;
import java.util.Map;

/** Locates the {@code execute(...)} call that feeds a golden-SQL read
 * chain (extracted from EngineTestExecutor at the file-size guardrail). */
final class ExecCallFinder {

    private ExecCallFinder() {
    }

    /** THE execute entry point, as a matcher set (router FQN — the one
     * real spelling after the R8 cutover). */
    static final java.util.Set<String> EXECUTE_FQNS = java.util.Set.of(
            com.legend.compiler.element.type.PlatformTypes.EXECUTE);

    /** The execute(...) call behind a golden-SQL read chain
     * ({@code $r->sqlRemoveFormatting()} / direct), or null. */
    static @com.legend.Nullable AppliedFunction find(
            @com.legend.Nullable ValueSpecification v,
            Map<String, ValueSpecification> lets,
            List<ValueSpecification> execStmts) {
        AppliedFunction t = findTerminal(v, lets, execStmts, EXECUTE_FQNS);
        return t != null && EngineTestExecutor.resolvesTo(t, null,
                EXECUTE_FQNS) ? t : null;
    }

    /** The terminal call (its simple name &isin; {@code stops}) behind a
     * read chain — the {@link #find} walk generalized so a DIRECT
     * {@code toSQLString(|q|, mapping, type, ext)} test (no execute()
     * anywhere) still surfaces its generator call. */
    static @com.legend.Nullable AppliedFunction findTerminal(
            @com.legend.Nullable ValueSpecification v,
            Map<String, ValueSpecification> lets,
            List<ValueSpecification> execStmts,
            java.util.Set<String> stops) {
        return findTerminal(v, lets, execStmts, stops, null);
    }

    /** As {@link #findTerminal(ValueSpecification, Map, List,
     * java.util.Set)}, but a non-null {@code through} restricts which
     * intermediate calls the walk may descend past: a chain carrying a
     * TRANSFORM (e.g. {@code ->replace(...)}) must NOT resolve to its
     * generator as if the transform weren't there — the caller would
     * compare untransformed text against a transformed contract. */
    static @com.legend.Nullable AppliedFunction findTerminal(
            @com.legend.Nullable ValueSpecification v,
            Map<String, ValueSpecification> lets,
            List<ValueSpecification> execStmts,
            java.util.Set<String> stops,
            @com.legend.Nullable java.util.Set<String> through) {
        if (v == null) {
            return null;
        }
        ValueSpecification cur = EngineTestExecutor.substitute(v, lets);
        // CYCLE GUARD: a self-referential binding (let result = $result —
        // the helper-expansion rebinding shape) must terminate, not spin
        java.util.Set<String> seenVars = new java.util.HashSet<>();
        while (true) {
            if (cur instanceof Variable var) {
                if (!seenVars.add(var.name())) {
                    break;
                }
                // execute() bindings live in the exec-statement frame,
                // not in lets — find the binding and descend into it
                ValueSpecification bound =
                        lastLetBinding(var.name(), execStmts);
                if (bound == null) {
                    break;
                }
                cur = bound;
                continue;
            }
            if (cur instanceof AppliedFunction af
                    && !EngineTestExecutor.resolvesTo(af, null, stops)
                    && !af.parameters().isEmpty()) {
                if (through != null && !EngineTestExecutor.resolvesTo(
                        af, null, through)) {
                    return null;
                }
                cur = EngineTestExecutor.substitute(af.parameters().get(0), lets);
                continue;
            }
            if (cur instanceof com.legend.protocol.spec.AppliedProperty ap) {
                cur = ap.receiver();
                continue;
            }
            break;
        }
        return cur instanceof AppliedFunction ex
                && EngineTestExecutor.resolvesTo(ex, null, stops)
                ? ex : null;
    }


    /** The LAST exec-frame {@code let <name> = <expr>} binding (statement
     * order — later shadowing bindings win), or null. The single owner of
     * the letFunction name compare (string-dispatch freeze). */
    static @com.legend.Nullable ValueSpecification lastLetBinding(
            String name, List<ValueSpecification> execStmts) {
        ValueSpecification bound = null;
        for (ValueSpecification st : execStmts) {
            if (st instanceof AppliedFunction lf
                    && lf.function().equals("letFunction")
                    && lf.parameters().size() == 2
                    && lf.parameters().get(0)
                            instanceof com.legend.protocol.spec.CString n
                    && n.value().equals(name)) {
                bound = lf.parameters().get(1);
            }
        }
        return bound;
    }
}
