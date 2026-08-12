// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend.harness;

import com.legend.protocol.spec.AppliedFunction;
import com.legend.protocol.spec.ValueSpecification;
import com.legend.protocol.spec.Variable;

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
        AppliedFunction t = findTerminal(v, lets, execStmts,
                java.util.Set.of("execute"));
        return t != null && TestBody.simpleName(t.function())
                .equals("execute") ? t : null;
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
        ValueSpecification cur = HarnessSubstitution.substitute(v, lets);
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
                    && !stops.contains(TestBody.simpleName(af.function()))
                    && !af.parameters().isEmpty()) {
                if (through != null && !through.contains(
                        TestBody.simpleName(af.function()))) {
                    return null;
                }
                cur = HarnessSubstitution.substitute(af.parameters().get(0), lets);
                continue;
            }
            if (cur instanceof com.legend.protocol.spec.AppliedProperty ap) {
                cur = ap.receiver();
                continue;
            }
            break;
        }
        return cur instanceof AppliedFunction ex
                && stops.contains(TestBody.simpleName(ex.function()))
                ? ex : null;
    }

    /** OUR engine-text rendering of one assert side, or null when the
     * side has no reachable generator or the generation throws (the
     * caller falls back to the row-replay/advisory channel). An
     * execute(...) terminal rebuilds the engine's own derivation
     * {@code toSQLString(query, mapping, H2, extensions)}; a direct
     * toSQLString terminal evaluates as written. */
    static @com.legend.Nullable String sideSqlText(
            @com.legend.Nullable ValueSpecification side,
            Map<String, ValueSpecification> lets,
            List<ValueSpecification> execStmts,
            java.util.Set<String> execVars,
            Map<String, ValueSpecification> execChains,
            com.legend.compiler.element.ModelContext ctx,
            com.legend.model.ImportScope imports, String runtimeFqn,
            java.sql.Connection conn) {
        AppliedFunction term = findTerminal(side, lets, execStmts,
                java.util.Set.of("execute", "toSQLString",
                        "toSQLStringPretty"),
                java.util.Set.of("sqlRemoveFormatting", "sql", "toOne",
                        "at"));
        if (term == null) {
            return null;
        }
        try {
            ValueSpecification call = term;
            if (TestBody.simpleName(term.function()).equals("execute")) {
                if (term.parameters().size() < 2) {
                    return null;
                }
                List<ValueSpecification> ps = new java.util.ArrayList<>();
                ps.add(HarnessSubstitution.substitute(term.parameters().get(0), lets));
                ps.add(term.parameters().get(1));
                ps.add(new com.legend.protocol.spec.EnumValue(
                        "meta::relational::runtime::DatabaseType", "H2"));
                // toSQLString has only the 4-arg form (func, mapping,
                // dbType, extensions) — a 5-arg execute carries an exeCtx
                // in position 3 that must NOT ride along; extensions is
                // always the trailing argument
                if (term.parameters().size() > 4) {
                    ps.add(term.parameters()
                            .get(term.parameters().size() - 1));
                } else {
                    for (int i = 3; i < term.parameters().size(); i++) {
                        ps.add(term.parameters().get(i));
                    }
                }
                call = new AppliedFunction("meta::relational::functions"
                        + "::sqlstring::toSQLString", ps);
            }
            return TestBody.evalScalar(call, lets, execStmts, execVars,
                    execChains, ctx, imports, runtimeFqn, conn)
                    instanceof String s ? s : null;
        } catch (RuntimeException | java.sql.SQLException e) {
            if (System.getenv("LL_SQLTEXT_DEBUG") != null) {
                System.err.println("[sql-text] side unverifiable: " + e);
            }
            return null;
        }
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
