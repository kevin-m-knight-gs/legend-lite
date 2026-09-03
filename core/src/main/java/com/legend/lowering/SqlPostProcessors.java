// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend.lowering;

import com.legend.compiler.spec.typed.TypedCollection;
import com.legend.compiler.spec.typed.TypedLambda;
import com.legend.compiler.spec.typed.TypedNativeCall;
import com.legend.compiler.spec.typed.TypedNewInstance;
import com.legend.compiler.spec.typed.TypedSpec;
import com.legend.error.NotImplementedException;
import com.legend.sql.SqlExpr;
import com.legend.sql.SqlQuery;
import com.legend.sql.SqlSelect;
import com.legend.sql.SqlSource;
import com.legend.sql.SqlUnion;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The connection's {@code sqlQueryPostProcessorsConnectionAware} hooks
 * (real relationalRuntime.pure:42), applied over legend-lite's OWN SQL
 * IR: the engine hands its SQL metamodel to opaque lambdas; we
 * RECOGNIZE the shapes the corpus builds (replaceTables over literal
 * table pairs) and run the equivalent IR rewrite — Java orchestrates,
 * the database executes. Unknown hook shapes inside the recognized
 * channel are loud, never silently dropped.
 */
public final class SqlPostProcessors {

    private SqlPostProcessors() {
    }

    /**
     * The old&rarr;new PHYSICAL table-name map carried by a runtime
     * instance's connection-aware replaceTables hook; empty when the
     * runtime carries no such hook. {@code runtimeArg} must be the
     * INLINED runtime value (user helper calls already β-expanded).
     */
    public static Map<String, String> tableReplaceMap(TypedSpec runtimeArg) {
        return hooks(runtimeArg, java.util.function.UnaryOperator.identity()).tableReplace();
    }

    /** The runtime argument's recognized SQL post-processors: the
     * replaceTables renames and whether the CTE-extraction processor
     * ({@code extractSubqueriesAsCTEs}) is installed. */
    public record Hooks(Map<String, String> tableReplace, boolean extractCtes,
            boolean nonExecutable) {
    }

    /** {@code bind}: the caller's let bindings (a pair or a table bound
     * outside the hook lambda: {@code let oldTable = ...; ...
     * replaceTables($query, pair($oldTable, $newTable))}). */
    public static Hooks hooks(TypedSpec runtimeArg,
            java.util.function.UnaryOperator<TypedSpec> bind) {
        Map<String, String> out = new LinkedHashMap<>();
        // [0] = CTE extraction installed, [1] = nonExecutable installed
        boolean[] cte = {false, false};
        collectConnections(runtimeArg, out, cte, bind);
        return new Hooks(out, cte[0], cte[1]);
    }

    private static void collectConnections(TypedSpec n,
            Map<String, String> out, boolean[] cte,
            java.util.function.UnaryOperator<TypedSpec> bind) {
        if (n instanceof TypedNewInstance ni) {
            TypedSpec aware = ni.properties().get(
                    "sqlQueryPostProcessorsConnectionAware");
            if (aware != null) {
                for (TypedSpec hook : elements(aware)) {
                    readHook(hook, out, cte, bind);
                }
            }
            // the PLAIN slot carries the same replaceTables shape (hook
            // takes (SQLQuery) instead of (SQLQuery, DatabaseConnection))
            TypedSpec plain = ni.properties().get("sqlQueryPostProcessors");
            if (plain != null) {
                for (TypedSpec hook : elements(plain)) {
                    // LOUD (deep-audit D2-4, slice zero 2026-08-15;
                    // user ruling): the old catch-and-skip silently
                    // dropped any hook the recognizer didn't parse — and
                    // the cteExtraction corpus tests were "passing" with
                    // the very feature they test skipped (a false
                    // green). A hook is either recognized-and-applied
                    // (the replaceTables pattern) or the query REFUSES;
                    // the 7 cteExtraction tests are adjudicated
                    // blocked-on-feature until an IR CTE-extraction pass
                    // exists.
                    readHook(hook, out, cte, bind);
                }
            }
        }
        if (n instanceof com.legend.compiler.spec.typed
                .TypedCopyInstance cp) {
            for (String key : new String[] {
                    "sqlQueryPostProcessorsConnectionAware",
                    "sqlQueryPostProcessors"}) {
                TypedSpec hooks = cp.overrides().get(key);
                if (hooks != null) {
                    for (TypedSpec hook : elements(hooks)) {
                        readHook(hook, out, cte, bind);
                    }
                }
            }
        }
        for (TypedSpec c : n.children()) {
            collectConnections(c, out, cte, bind);
        }
    }

    private static List<TypedSpec> elements(TypedSpec v) {
        return v instanceof TypedCollection tc ? tc.elements() : List.of(v);
    }

    /** One hook lambda: the ONLY recognized body is a terminal
     * {@code replaceTables($query, <pairs>)} call. */
    private static @com.legend.Nullable String calleeOf(TypedSpec n) {
        return switch (n) {
            case TypedNativeCall c -> c.callee().qualifiedName();
            case com.legend.compiler.spec.typed.TypedUserCall u -> u.callee().qualifiedName();
            default -> null;
        };
    }

    private static final String EXTRACT_CTES_FQN =
            "meta::relational::postProcessor::cteExtraction::extractSubqueriesAsCTEs";
    private static final String NON_EXECUTABLE_FQN =
            "meta::relational::postProcessor::nonExecutable";

    private static void readHook(TypedSpec hook, Map<String, String> out,
            boolean[] cte, java.util.function.UnaryOperator<TypedSpec> bind) {
        // {s | ^Result<SelectSQLQuery|1>(values = $s->extractSubqueriesAsCTEs())}
        // — the CTE-extraction processor (cteExtractionPostProcessor.pure:139)
        if (hook instanceof TypedLambda cl && !cl.body().isEmpty()
                && cl.body().get(cl.body().size() - 1) instanceof TypedNewInstance rni
                && rni.properties().get("values") instanceof TypedSpec vals
                && EXTRACT_CTES_FQN.equals(calleeOf(vals))) {
            cte[0] = true;
            return;
        }
        // {query | nonExecutable($query, extensions)} — the engine's
        // nonExecutable processor (nonExecutablePostProcessor.pure:24): a
        // platform post-processor, applied as the IR pass nonExecutable()
        if (hook instanceof TypedLambda nl && !nl.body().isEmpty()
                && NON_EXECUTABLE_FQN.equals(calleeOf(nl.body().get(nl.body().size() - 1)))) {
            cte[1] = true;
            return;
        }
        // IDENTITY hook (ledger cluster 63): {query|$query->postprocess(
        // {rel|$rel})} — recognized-and-applied, and the application is
        // a no-op (the inner transform returns its argument). Any other
        // postprocess body stays at the loud wall below.
        if (hook instanceof TypedLambda idl && !idl.body().isEmpty()
                && idl.body().get(idl.body().size() - 1)
                        instanceof com.legend.compiler.spec.typed
                                .TypedUserCall pu
                && "meta::relational::postProcessor::postprocess"
                        .equals(pu.callee().qualifiedName())
                && pu.args().size() == 2
                && pu.args().get(1) instanceof TypedLambda inner
                && inner.parameters().size() == 1
                && inner.body().size() == 1
                && inner.body().get(0) instanceof com.legend.compiler.spec
                        .typed.TypedVariable iv
                && iv.name().equals(inner.parameters().get(0))) {
            return;
        }
        if (!(hook instanceof TypedLambda lam) || lam.body().isEmpty()
                || !(lam.body().get(lam.body().size() - 1)
                        instanceof TypedNativeCall call)
                || !"meta::relational::postProcessor::replaceTables"
                        .equals(call.callee().qualifiedName())
                || call.args().size() != 2) {
            throw new NotImplementedException(
                    "sqlQueryPostProcessorsConnectionAware hook shape is"
                    + " not a replaceTables lambda — post-processor"
                    + " recognizer pending for: " + hook);
        }
        for (TypedSpec pair : elements(bind.apply(peel(call.args().get(1), bind)), bind)) {
            TypedSpec p = peel(pair, bind);
            if (!(p instanceof TypedNativeCall pc)
                    || !pc.callee().qualifiedName().endsWith("::pair")
                    || pc.args().size() != 2) {
                throw new NotImplementedException("replaceTables pair"
                        + " argument is not a literal pair(): " + pair);
            }
            composeRename(out, tableName(pc.args().get(0), bind),
                    tableName(pc.args().get(1), bind));
        }
    }

    /** Hooks apply SEQUENTIALLY (engine semantics): a later
     *  {@code from -> to} first rewrites the RESULTS of earlier renames
     *  (so A->B then B->A nets to identity), then registers itself for
     *  tables the earlier hooks left untouched. */
    private static void composeRename(Map<String, String> out, String from,
            String to) {
        for (var e : out.entrySet()) {
            if (e.getValue().equals(from)) {
                e.setValue(to);
            }
        }
        out.putIfAbsent(from, to);
    }

    /** {@code db->schema('X')->toOne()->table('Y')->toOne()} spelled as
     * the lowerer spells FROM sources: {@code Y}, or {@code X.Y} for a
     * non-default schema. */
    private static String tableName(TypedSpec nav,
            java.util.function.UnaryOperator<TypedSpec> bind) {
        TypedSpec cur = peel(nav, bind);
        if (cur instanceof TypedNativeCall t
                && "meta::relational::metamodel::table"
                        .equals(t.callee().qualifiedName())
                && t.args().size() == 2) {
            String table = stringOf(t.args().get(1), "table name");
            TypedSpec sch = peel(t.args().get(0), bind);
            if (sch instanceof TypedNativeCall s
                    && "meta::relational::metamodel::schema"
                            .equals(s.callee().qualifiedName())
                    && s.args().size() == 2) {
                String schema = stringOf(s.args().get(1), "schema name");
                return "default".equals(schema) ? table
                        : schema + "." + table;
            }
        }
        throw new NotImplementedException("replaceTables pair side is not"
                + " a schema()/table() navigation: " + nav);
    }

    private static String stringOf(TypedSpec v, String what) {
        if (peel(v) instanceof com.legend.compiler.spec.typed.TypedCString cs) {
            return cs.value();
        }
        throw new NotImplementedException("replaceTables " + what
                + " is not a string literal: " + v);
    }

    /** toOne()/cast wrappers peel — identity for navigation. */
    private static List<TypedSpec> elements(TypedSpec v,
            java.util.function.UnaryOperator<TypedSpec> bind) {
        List<TypedSpec> out = new java.util.ArrayList<>();
        for (TypedSpec e : elements(v)) {
            out.add(bind.apply(e));
        }
        return out;
    }

    private static TypedSpec peel(TypedSpec v) {
        return peel(v, java.util.function.UnaryOperator.identity());
    }

    private static TypedSpec peel(TypedSpec v,
            java.util.function.UnaryOperator<TypedSpec> bind) {
        TypedSpec cur = v;
        while (true) {
            if (cur instanceof com.legend.compiler.spec.typed.TypedVariable) {
                TypedSpec bound = bind.apply(cur);
                if (bound != cur) {
                    cur = bound;
                    continue;
                }
            }
            if (cur instanceof TypedNativeCall c && c.args().size() == 1
                    && (com.legend.builtin.Pure.isToOneCall(c.callee().qualifiedName())
                            || c.callee().qualifiedName()
                                    .endsWith("::toOneMany"))) {
                cur = c.args().get(0);
                continue;
            }
            if (cur instanceof com.legend.compiler.spec.typed.TypedCast tc) {
                cur = tc.source();
                continue;
            }
            return cur;
        }
    }

    // ===== the IR rewrite =====

    public static SqlQuery apply(SqlQuery q, Map<String, String> map) {
        if (map.isEmpty()) {
            return q;
        }
        return apply(q, (java.util.function.UnaryOperator<String>)
                n2 -> map.getOrDefault(n2, n2));
    }

    /** FUNCTION form — the relationalMapper channel resolves spellings
     * per-name (db identity may need model lookups). Identity output =
     * no rewrite. */
    public static SqlQuery apply(SqlQuery q,
            java.util.function.UnaryOperator<String> map) {
        return switch (q) {
            case SqlSelect s -> applySelect(s, map);
            case SqlUnion u -> new SqlUnion(u.branches().stream()
                    .map(b -> apply(b, map)).toList(), u.all(), u.outputs());
            case com.legend.sql.SqlWith w -> new com.legend.sql.SqlWith(
                    w.ctes().stream().map(c -> new com.legend.sql.SqlWith.Cte(
                            c.name(), apply(c.query(), map))).toList(),
                    apply(w.body(), map));
        };
    }

    /** The frame's recorded post-processing over a lowered query: the
     * table renames, then CTE extraction when the processor is installed
     * (the orchestrator supplies both facts — exec never calls here). */
    public static SqlQuery applyRecorded(SqlQuery q, Map<String, String> tableReplace,
            boolean extractCtes, boolean nonExecutable) {
        SqlQuery out = apply(q, tableReplace);
        out = nonExecutable ? nonExecutable(out) : out;
        return extractCtes ? extractSubqueriesAsCtes(out) : out;
    }

    // ---- nonExecutable (the engine's nonExecutablePostProcessor) ----

    /** {@code nonExecutable}: every SELECT in the tree — the root, each
     * FROM-tree subselect, each union branch, each CTE — takes
     * {@code <filter> and 1 = 2} (a bare {@code 1 = 2} when it had no
     * filter), so the query still parses and plans but returns no rows
     * (engine processRelationalOperationForNonExecutable). Join ON
     * conditions and projections are untouched. */
    public static SqlQuery nonExecutable(SqlQuery q) {
        return switch (q) {
            case SqlSelect s -> nonExecutableSelect(s);
            case com.legend.sql.SqlUnion u -> new com.legend.sql.SqlUnion(
                    u.branches().stream().map(SqlPostProcessors::nonExecutable).toList(),
                    u.all(), u.outputs());
            case com.legend.sql.SqlWith w -> new com.legend.sql.SqlWith(
                    w.ctes().stream().map(c -> new com.legend.sql.SqlWith.Cte(
                            c.name(), nonExecutable(c.query()))).toList(),
                    nonExecutable(w.body()));
            default -> q;
        };
    }

    private static SqlSelect nonExecutableSelect(SqlSelect s) {
        SqlExpr never = SqlExpr.Call.of(com.legend.sql.SqlFn.EQUAL,
                new SqlExpr.IntLit(1), new SqlExpr.IntLit(2));
        SqlExpr where = s.where() == null ? never
                : SqlExpr.Call.of(com.legend.sql.SqlFn.AND, s.where(), never);
        return new SqlSelect(s.projections(), s.distinct(),
                nonExecutableSource(s.from()), where, s.groupBy(), s.having(),
                s.qualify(), s.orderBy(), s.limit(), s.offset(), s.outputs());
    }

    private static SqlSource nonExecutableSource(SqlSource src) {
        return switch (src) {
            case SqlSource.Join j -> new SqlSource.Join(nonExecutableSource(j.left()),
                    nonExecutableSource(j.right()), j.kind(), j.on());
            case SqlSource.Subselect sub -> new SqlSource.Subselect(
                    nonExecutable(sub.inner()), sub.alias(), sub.frameName());
            default -> src;
        };
    }

    // ---- CTE extraction (the engine's cteExtractionPostProcessor) ----

    /** {@code extractSubqueriesAsCTEs}: every SUBSELECT in the FROM tree
     * (the join tree's derived tables) becomes a common table expression
     * {@code subquery_cte_<level>_<index>} — level = nesting depth from
     * the root (1 = the root's own subselects), index = a per-level
     * counter in tree order that carries across siblings; a subselect's
     * OWN subselects extract first (the child's CTEs precede the
     * parent's), and the reference keeps the derived table's alias. A
     * query without subselects is itself. */
    public static SqlQuery extractSubqueriesAsCtes(SqlQuery q) {
        if (!(q instanceof SqlSelect root)) {
            return q;
        }
        List<com.legend.sql.SqlWith.Cte> ctes = new java.util.ArrayList<>();
        java.util.Map<Integer, Integer> levelIndex = new java.util.HashMap<>();
        SqlSelect body = extractLevel(root, 1, levelIndex, ctes);
        return ctes.isEmpty() ? q : new com.legend.sql.SqlWith(ctes, body);
    }

    private static SqlSelect extractLevel(SqlSelect select, int level,
            java.util.Map<Integer, Integer> levelIndex,
            List<com.legend.sql.SqlWith.Cte> out) {
        SqlSource from = extractSource(select.from(), level, levelIndex, out);
        return from == select.from() ? select : new SqlSelect(select.projections(),
                select.distinct(), from, select.where(), select.groupBy(),
                select.having(), select.qualify(), select.orderBy(), select.limit(),
                select.offset(), select.outputs());
    }

    private static SqlSource extractSource(SqlSource src, int level,
            java.util.Map<Integer, Integer> levelIndex,
            List<com.legend.sql.SqlWith.Cte> out) {
        return switch (src) {
            case SqlSource.Join j -> {
                SqlSource l = extractSource(j.left(), level, levelIndex, out);
                SqlSource r = extractSource(j.right(), level, levelIndex, out);
                yield l == j.left() && r == j.right() ? j
                        : new SqlSource.Join(l, r, j.kind(), j.on());
            }
            case SqlSource.Subselect sub when sub.inner() instanceof SqlSelect inner -> {
                // the child's own subselects first (deeper CTEs precede)
                SqlSelect processed = extractLevel(inner, level + 1, levelIndex, out);
                int index = levelIndex.getOrDefault(level, 0) + 1;
                levelIndex.put(level, index);
                String name = "subquery_cte_" + level + "_" + index;
                out.add(new com.legend.sql.SqlWith.Cte(name, processed));
                yield new SqlSource.Table(name, sub.alias(), sub.outputs());
            }
            default -> src;
        };
    }

    private static SqlSelect applySelect(SqlSelect s,
            java.util.function.UnaryOperator<String> m) {
        return new SqlSelect(
                s.projections().stream().map(p -> new SqlSelect.Projection(
                        expr(p.expr(), m), p.outputName(), p.out())).toList(),
                s.distinct(),
                source(s.from(), m),
                s.where() == null ? null : expr(s.where(), m),
                s.groupBy().stream().map(g -> expr(g, m)).toList(),
                s.having() == null ? null : expr(s.having(), m),
                s.qualify() == null ? null : expr(s.qualify(), m),
                s.orderBy().stream().map(k -> new SqlSelect.SortKey(
                        expr(k.expr(), m), k.ascending(), k.nullOrder(),
                        k.outputName()))
                        .toList(),
                s.limit(), s.offset(), s.outputs());
    }

    private static SqlSource source(SqlSource src,
            java.util.function.UnaryOperator<String> m) {
        return switch (src) {
            case SqlSource.Table t -> {
                String nn = m.apply(t.name());
                yield nn.equals(t.name()) ? t
                        : new SqlSource.Table(nn, t.alias(), t.outputs());
            }
            case SqlSource.Join j -> new SqlSource.Join(source(j.left(), m),
                    source(j.right(), m), j.kind(),
                    j.on() == null ? null : expr(j.on(), m));
            case SqlSource.VarSetPlaceholder vp -> vp;
            case SqlSource.RawSql raw -> raw;   // carried text: opaque to rewrites
            case SqlSource.Subselect sub -> new SqlSource.Subselect(
                    apply(sub.inner(), m), sub.alias(), sub.frameName());
            // TOTAL by construction — a Pivot's INNER source and a Values
            // row expression rename like any other (the old default arm
            // silently skipped both)
            case SqlSource.Pivot p -> new SqlSource.Pivot(source(p.source(), m),
                    p.on().stream().map(x -> expr(x, m)).toList(),
                    p.in().stream().map(x -> expr(x, m)).toList(),
                    p.usings().stream().map(u -> new SqlSource.Pivot.Using(
                            (com.legend.sql.SqlAgg.Reducer) expr(u.agg(), m),
                            u.alias(), u.type())).toList(),
                    p.alias(), p.outputs());
            case SqlSource.Values v -> new SqlSource.Values(
                    v.rows().stream().map(r -> r.stream()
                            .map(x -> expr(x, m)).toList()).toList(),
                    v.columns(), v.alias(), v.outputs());
            case SqlSource.SourceUrl u -> u;
            case SqlSource.Dual d -> d;
        };
    }

    private static SqlExpr expr(SqlExpr e,
            java.util.function.UnaryOperator<String> m) {
        return switch (e) {
            case SqlExpr.Call c -> new SqlExpr.Call(c.fn(),
                    c.args().stream().map(a -> expr(a, m)).toList());
            case SqlExpr.Case cs -> new SqlExpr.Case(
                    cs.whens().stream().map(w -> new SqlExpr.Case.When(
                            expr(w.condition(), m), expr(w.then(), m)))
                            .toList(),
                    cs.otherwise() == null ? null
                            : expr(cs.otherwise(), m));
            case SqlExpr.Cast ct -> new SqlExpr.Cast(expr(ct.value(), m),
                    ct.target());
            case SqlExpr.Group g -> new SqlExpr.Group(expr(g.inner(), m));
            case SqlExpr.Exists ex -> new SqlExpr.Exists(
                    apply(ex.subquery(), m));
            case SqlExpr.ScalarSubquery sq -> new SqlExpr.ScalarSubquery(
                    apply(sq.subquery(), m));
            default -> e.mapChildren(x -> expr(x, m));
        };
    }
}
