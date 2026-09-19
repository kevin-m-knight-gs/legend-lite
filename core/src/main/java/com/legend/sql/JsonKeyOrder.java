// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend.sql;

import java.util.ArrayList;
import java.util.List;

/**
 * The verdict plan's JSON objects with their keys SORTED — one IR pass
 * (post-children) over a lowered plan. Key order carries no meaning in
 * pure's JSON equality (the engine's own asserts compare structurally,
 * and its serializer's order is an execution artifact: a cross-store or
 * subtype pass emits its properties first), so one fixed order on both
 * sides lets BYTES decide in the database. Applied to a verdict side's
 * plan only; the product's own output keeps the tree's order. An object
 * whose keys are not all literals is left as built.
 */
public final class JsonKeyOrder extends SqlRewriter {

    private JsonKeyOrder() {
    }

    public static SqlQuery sort(SqlQuery plan) {
        return new JsonKeyOrder().rewrite(plan);
    }

    @Override
    protected SqlExpr expr(SqlExpr e) {
        if (e instanceof SqlExpr.Call c && c.fn() == SqlFn.JSON_MERGE_PATCH
                && c.args().size() > 1) {
            // an object composed by MERGE of single-key pieces (the
            // removeNull / removeEmpty serializer form): the merge keeps
            // the pieces' order, so the pieces sort by their one key
            List<SqlExpr> pieces = new ArrayList<>(c.args());
            for (SqlExpr piece : pieces) {
                if (!(piece instanceof SqlExpr.JsonObject pj) || pj.kv().size() != 2
                        || !(pj.kv().get(0) instanceof SqlExpr.StringLit)) {
                    return e;
                }
            }
            pieces.sort((x, y) -> ((SqlExpr.StringLit) ((SqlExpr.JsonObject) x).kv().get(0)).value()
                    .compareTo(((SqlExpr.StringLit) ((SqlExpr.JsonObject) y).kv().get(0)).value()));
            return pieces.equals(c.args()) ? e : new SqlExpr.Call(SqlFn.JSON_MERGE_PATCH, pieces);
        }
        if (!(e instanceof SqlExpr.JsonObject j) || j.kv().size() < 4) {
            return e;
        }
        List<SqlExpr> kv = j.kv();
        List<int[]> pairs = new ArrayList<>(kv.size() / 2);
        for (int i = 0; i + 1 < kv.size(); i += 2) {
            if (!(kv.get(i) instanceof SqlExpr.StringLit)) {
                return e;
            }
            pairs.add(new int[]{i});
        }
        pairs.sort((x, y) -> ((SqlExpr.StringLit) kv.get(x[0])).value()
                .compareTo(((SqlExpr.StringLit) kv.get(y[0])).value()));
        List<SqlExpr> sorted = new ArrayList<>(kv.size());
        for (int[] p : pairs) {
            sorted.add(kv.get(p[0]));
            sorted.add(kv.get(p[0] + 1));
        }
        return sorted.equals(kv) ? e : new SqlExpr.JsonObject(sorted);
    }
}
