// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0
package com.legend.sql.dialect;

import com.legend.sql.SqlExpr;
import com.legend.sql.SqlFn;
import com.legend.sql.SqlRewriter;
import java.util.ArrayList;
import java.util.List;

/**
 * DuckDB's spelling of the CHECKED-ENVELOPE nodes ({@link
 * SqlExpr.CheckedDefects}, {@link SqlExpr.CheckedChildValue}) — the
 * native list carriers with lambdas (CARRIER_REDESIGN tenet #1: the
 * lowering emits the semantic node, this strategy owns the idiom):
 * <ul>
 *   <li>defects = own ++ per child: to-many —
 *       {@code flatten(list_transform(elements(env), (e, i) ->
 *       list_transform(elements(e->defects), d -> prefixed(d, path, i-1))))}
 *       (DuckDB's lambda index is 1-based; the engine's is 0-based);
 *       to-one — {@code coalesce(list_transform(elements(env->defects),
 *       d -> prefixed(d, path, null)), [])};</li>
 *   <li>prefixed(d) = {@code json_merge_patch(d, {path: [nodes…] ++
 *       d->path})} — RFC 7386 replaces the array;</li>
 *   <li>child value = {@code to_json(list_transform(elements(env), e ->
 *       e->value))} for a to-many, {@code env->value} for a to-one.</li>
 * </ul>
 */
final class CheckedDefectsToLists extends SqlRewriter {

    @Override
    protected SqlExpr expr(SqlExpr e) {
        if (e instanceof SqlExpr.CheckedChildValue cv) {
            if (!cv.toMany()) {
                return get(cv.envelope(), "value");
            }
            return SqlExpr.Call.of(SqlFn.TO_VARIANT, SqlExpr.Call.of(SqlFn.LIST_TRANSFORM,
                    elements(cv.envelope()),
                    new SqlExpr.Lambda(List.of("e"), get(SqlExpr.Column.derived(null, "e"), "value"))));
        }
        if (!(e instanceof SqlExpr.CheckedDefects cd)) {
            return e;
        }
        SqlExpr all = cd.own();
        for (SqlExpr.CheckedDefects.Hoist h : cd.hoists()) {
            all = SqlExpr.Call.of(SqlFn.LIST_CONCAT, all, hoisted(h));
        }
        return all;
    }

    private static SqlExpr hoisted(SqlExpr.CheckedDefects.Hoist h) {
        if (!h.toMany()) {
            return SqlExpr.Call.of(SqlFn.COALESCE, SqlExpr.Call.of(SqlFn.LIST_TRANSFORM,
                    elements(get(h.envelope(), "defects")),
                    new SqlExpr.Lambda(List.of("d"),
                            prefixed(SqlExpr.Column.derived(null, "d"), h.path(), new SqlExpr.NullLit()))),
                    new SqlExpr.ArrayLit(List.of()));
        }
        SqlExpr index = SqlExpr.Call.of(SqlFn.MINUS, SqlExpr.Column.derived(null, "i"),
                new SqlExpr.IntLit(1));
        return SqlExpr.Call.of(SqlFn.LIST_FLATTEN, SqlExpr.Call.of(SqlFn.LIST_TRANSFORM,
                elements(h.envelope()),
                new SqlExpr.Lambda(List.of("e", "i"), SqlExpr.Call.of(SqlFn.LIST_TRANSFORM,
                        elements(get(SqlExpr.Column.derived(null, "e"), "defects")),
                        new SqlExpr.Lambda(List.of("d"),
                                prefixed(SqlExpr.Column.derived(null, "d"), h.path(), index))))));
    }

    private static SqlExpr prefixed(SqlExpr d, List<String> path, SqlExpr index) {
        List<SqlExpr> nodes = new ArrayList<>();
        for (int i = 0; i < path.size(); i++) {
            nodes.add(new SqlExpr.JsonObject(List.of(
                    new SqlExpr.StringLit("propertyName"), new SqlExpr.StringLit(path.get(i)),
                    new SqlExpr.StringLit("index"),
                    i == path.size() - 1 ? index : new SqlExpr.NullLit())));
        }
        SqlExpr full = SqlExpr.Call.of(SqlFn.TO_VARIANT, SqlExpr.Call.of(SqlFn.LIST_CONCAT,
                new SqlExpr.ArrayLit(nodes), elements(get(d, "path"))));
        return SqlExpr.Call.of(SqlFn.JSON_MERGE_PATCH, d,
                new SqlExpr.JsonObject(List.of(new SqlExpr.StringLit("path"), full)));
    }

    private static SqlExpr get(SqlExpr v, String key) {
        return SqlExpr.Call.of(SqlFn.VARIANT_GET, v, new SqlExpr.StringLit(key));
    }

    private static SqlExpr elements(SqlExpr v) {
        return SqlExpr.Call.of(SqlFn.VARIANT_ELEMENTS, v);
    }
}
