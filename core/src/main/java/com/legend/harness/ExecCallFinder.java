// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend.harness;

import com.legend.model.spec.AppliedFunction;
import com.legend.model.spec.ValueSpecification;
import com.legend.model.spec.Variable;

import java.util.List;
import java.util.Map;

/** Locates the {@code execute(...)} call that feeds a golden-SQL read
 * chain (extracted from TestBody at the file-size guardrail). */
final class ExecCallFinder {

    private ExecCallFinder() {
    }

    /** The execute(...) call behind a golden-SQL read chain
     * ({@code $r->sqlRemoveFormatting()} / direct), or null. */
    static @com.legend.Nullable AppliedFunction find(
            @com.legend.Nullable ValueSpecification v,
            Map<String, ValueSpecification> lets,
            List<ValueSpecification> execStmts) {
        if (v == null) {
            return null;
        }
        ValueSpecification cur = TestBody.substitute(v, lets);
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
                ValueSpecification bound = null;
                for (ValueSpecification st : execStmts) {
                    if (st instanceof AppliedFunction lf
                            && lf.function().equals("letFunction")
                            && lf.parameters().size() == 2
                            && lf.parameters().get(0)
                                    instanceof com.legend.model.spec
                                            .CString n
                            && n.value().equals(var.name())) {
                        bound = lf.parameters().get(1);
                    }
                }
                if (bound == null) {
                    break;
                }
                cur = bound;
                continue;
            }
            if (cur instanceof AppliedFunction af
                    && !TestBody.simpleName(af.function()).equals("execute")
                    && !af.parameters().isEmpty()) {
                cur = TestBody.substitute(af.parameters().get(0), lets);
                continue;
            }
            if (cur instanceof com.legend.model.spec.AppliedProperty ap) {
                cur = ap.receiver();
                continue;
            }
            break;
        }
        return cur instanceof AppliedFunction ex
                && TestBody.simpleName(ex.function()).equals("execute")
                ? ex : null;
    }
}
