// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend;

import com.legend.compiler.element.TypedFunction;
import com.legend.compiler.spec.SpecCompiler;
import com.legend.compiler.spec.typed.TypedCString;
import com.legend.compiler.spec.typed.TypedCollection;
import com.legend.compiler.spec.typed.TypedLet;
import com.legend.compiler.spec.typed.TypedNativeCall;
import com.legend.compiler.spec.typed.TypedSpec;
import com.legend.compiler.spec.typed.TypedUserCall;
import com.legend.compiler.spec.typed.TypedVariable;
import com.legend.exec.CanonicalDivergence;
import com.legend.exec.ExecutionResult;
import com.legend.exec.Executor;

import java.util.List;
import java.util.Set;

/**
 * THE LINEAGE-TREE VERDICT ARM — the scanRelations sibling of
 * {@link SqlTextVerdicts}: a statement-root {@code assertEquals(<tree
 * print>, $tree->relationTreeAsString())} judges on ROWS, never on
 * text. A tree print is the engine's {@code buildUniqueName(elements,
 * alias = true)} — its join labels spell the engine's decorated SQL
 * ALIASES ({@code _d#N}, {@code _dy<i>}, {@code _m<N>}, {@code _l},
 * {@code _r}, {@code _md}, duplicate counters — pureToSQLQuery.pure
 * buildNodeId), an artifact of its SQL generation the row charter
 * retired; the tree's CONTENT is the engine's own {@code alias = false}
 * form, the relational element's name. Both prints — the GOLDEN literal
 * and OURS (the database's own print of the handle's LineageRows) —
 * become rows through ONE query the database runs ({@link #TREE_ROWS}:
 * preorder, indent, kind, name, join label with every decorated alias
 * resolved to the node the tree itself declares, columns), and the two
 * row lists compare. Counted (CanonicalDivergence lineage-rows).
 */
final class LineageTreeVerdicts {

    private static final String ASSERT_EQUALS = "meta::pure::functions::asserts::assertEquals";
    private static final Set<String> PLUS = Set.of("meta::pure::functions::math::plus");

    /** The tree-print → rows query; {@code %s} is the print as a SQL
     * string literal. Aliases resolve LONGEST NAME FIRST (a node name can
     * prefix another) — the database orders them. */
    private static final String TREE_ROWS = """
            WITH t AS (SELECT string_split(%s, chr(10)) AS ls),
            lines AS (SELECT i AS preorder, ls[i] AS line FROM t, generate_series(1, len(ls)) AS g(i) WHERE trim(ls[i]) <> ''),
            nodes AS (
              SELECT preorder,
                length(regexp_extract(line, '^( *)', 1)) AS indent,
                CASE WHEN trim(line) = 'root' THEN 'root' ELSE regexp_extract(line, '------> \\(([tv])\\) ', 1) END AS kind,
                CASE WHEN trim(line) = 'root' THEN '' ELSE regexp_extract(line, '------> \\([tv]\\) ([^( \\[]+)', 1) END AS name,
                CASE WHEN trim(line) = 'root' THEN '' ELSE regexp_extract(line, '\\((.*)\\) \\[', 1) END AS label,
                CASE WHEN trim(line) = 'root' THEN '' ELSE regexp_extract(line, '\\[([^\\]]*)\\]\\s*$', 1) END AS cols
              FROM lines),
            names AS (SELECT string_agg(name, '|' ORDER BY length(name) DESC, name) AS alt FROM (SELECT DISTINCT name FROM nodes WHERE name <> ''))
            SELECT to_json(struct_pack(preorder := preorder, indent := indent, kind := kind, name := name,
              label := regexp_replace(label, '(' || coalesce((SELECT alt FROM names), 'root') || '|root|unionBase|unionAlias|"joinleft_"|"joinright_")(_d#\\d+|_dy\\d+|_md|_d|_m\\d+|_i\\d+|_l|_r|_f|_\\d+|#\\d+)+', '\\1', 'g'),
              cols := cols))
            FROM nodes ORDER BY preorder
            """;

    private LineageTreeVerdicts() {
    }

    /** {@code held} + message; null when the statement is not the shape. */
    record Verdict(boolean held, String message) {
    }

    static @com.legend.Nullable Verdict tryArm(TypedSpec bare, List<TypedSpec> letPrefix,
            SpecCompiler specs, StatementExecutor.ExecEnv env) {
        TypedFunction callee;
        List<TypedSpec> args;
        if (bare instanceof TypedUserCall c) {
            callee = c.callee();
            args = c.args();
        } else if (bare instanceof TypedNativeCall n) {
            callee = n.callee();
            args = n.args();
        } else {
            return null;
        }
        if (args.size() != 2 || !ASSERT_EQUALS.equals(callee.qualifiedName())) {
            return null;
        }
        String golden = spelled(args.get(0), letPrefix);
        if (golden == null || !isTreePrint(golden)) {
            return null;
        }
        ExecutionResult r = StatementExecutor.evalValue(args.get(1), letPrefix, specs, env);
        if (!(r instanceof ExecutionResult.Scalar sc) || !(sc.value() instanceof String ours)
                || !isTreePrint(ours)) {
            return null;
        }
        String goldenRows = treeRows(golden, env);
        String ourRows = treeRows(ours, env);
        boolean held = goldenRows.equals(ourRows);
        CanonicalDivergence.lineageRows(held);
        return new Verdict(held, held ? "" : "lineage tree rows diverged:\n  golden " + goldenRows
                + "\n  ours   " + ourRows);
    }

    private static String treeRows(String print, StatementExecutor.ExecEnv env) {
        String lit = "'" + print.replace("'", "''") + "'";
        java.io.StringWriter out = new java.io.StringWriter();
        try {
            Executor.streamWireRows(TREE_ROWS.formatted(lit), env.connection(), out);
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
        return out.toString();
    }

    static boolean isTreePrint(String s) {
        return s.startsWith("root\n") && s.contains("------> (");
    }

    /** The golden as a STRING: spelled inline, through a let, or as a
     * concatenation of literals. */
    private static @com.legend.Nullable String spelled(TypedSpec e, List<TypedSpec> lets) {
        if (e instanceof TypedCString s) {
            return s.value();
        }
        if (e instanceof TypedVariable v) {
            for (TypedSpec l : lets) {
                if (l instanceof TypedLet let && let.name().equals(v.name())) {
                    return spelled(let.value(), lets);
                }
            }
            return null;
        }
        List<TypedSpec> parts;
        if (e instanceof TypedNativeCall n && PLUS.contains(n.callee().qualifiedName())) {
            parts = n.args().size() == 1 && n.args().get(0) instanceof TypedCollection c
                    ? c.elements() : n.args();
        } else if (e instanceof TypedCollection c) {
            parts = c.elements();
        } else {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (TypedSpec part : parts) {
            String t = spelled(part, lets);
            if (t == null) {
                return null;
            }
            sb.append(t);
        }
        return sb.toString();
    }
}
