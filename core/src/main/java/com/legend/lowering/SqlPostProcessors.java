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
        Map<String, String> out = new LinkedHashMap<>();
        collectConnections(runtimeArg, out);
        return out;
    }

    private static void collectConnections(TypedSpec n,
            Map<String, String> out) {
        if (n instanceof TypedNewInstance ni
                && ni.properties().containsKey(
                        "sqlQueryPostProcessorsConnectionAware")) {
            TypedSpec pp = ni.properties().get(
                    "sqlQueryPostProcessorsConnectionAware");
            for (TypedSpec hook : elements(pp)) {
                readHook(hook, out);
            }
        }
        for (TypedSpec c : n.children()) {
            collectConnections(c, out);
        }
    }

    private static List<TypedSpec> elements(TypedSpec v) {
        return v instanceof TypedCollection tc ? tc.elements() : List.of(v);
    }

    /** One hook lambda: the ONLY recognized body is a terminal
     * {@code replaceTables($query, <pairs>)} call. */
    private static void readHook(TypedSpec hook, Map<String, String> out) {
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
        for (TypedSpec pair : elements(call.args().get(1))) {
            TypedSpec p = peel(pair);
            if (!(p instanceof TypedNativeCall pc)
                    || !pc.callee().qualifiedName().endsWith("::pair")
                    || pc.args().size() != 2) {
                throw new NotImplementedException("replaceTables pair"
                        + " argument is not a literal pair(): " + pair);
            }
            out.put(tableName(pc.args().get(0)),
                    tableName(pc.args().get(1)));
        }
    }

    /** {@code db->schema('X')->toOne()->table('Y')->toOne()} spelled as
     * the lowerer spells FROM sources: {@code Y}, or {@code X.Y} for a
     * non-default schema. */
    private static String tableName(TypedSpec nav) {
        TypedSpec cur = peel(nav);
        if (cur instanceof TypedNativeCall t
                && "meta::relational::metamodel::table"
                        .equals(t.callee().qualifiedName())
                && t.args().size() == 2) {
            String table = stringOf(t.args().get(1), "table name");
            TypedSpec sch = peel(t.args().get(0));
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
    private static TypedSpec peel(TypedSpec v) {
        TypedSpec cur = v;
        while (true) {
            if (cur instanceof TypedNativeCall c && c.args().size() == 1
                    && (c.callee().qualifiedName().endsWith("::toOne")
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
            default -> q;
        };
    }

    private static SqlSelect applySelect(SqlSelect s,
            java.util.function.UnaryOperator<String> m) {
        return new SqlSelect(
                s.projections().stream().map(p -> new SqlSelect.Projection(
                        expr(p.expr(), m), p.outputName())).toList(),
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
            case SqlSource.Subselect sub -> new SqlSource.Subselect(
                    apply(sub.inner(), m), sub.alias(), sub.frameName());
            default -> src;
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
            default -> e;
        };
    }
}
