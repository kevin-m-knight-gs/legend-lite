// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend.lowering;

import com.legend.compiler.element.type.Type;
import com.legend.sql.OutputCol;
import com.legend.sql.SqlAgg;
import com.legend.sql.SqlExpr;
import com.legend.sql.SqlFn;
import com.legend.sql.SqlSelect;

import java.util.List;

/**
 * The SNAPSHOT (scalar-position) graph envelope: the array-aggregated
 * serialize select folded to ONE string with the engine JsonBuilder
 * cardinality rule — a SINGLETON result serializes as the bare object,
 * anything else as the array (the corpus goldens pin the dynamic
 * unwrap: {@code "values":{...}} for one row, {@code "values":[...]}
 * otherwise).
 */
final class SnapshotEnvelope {

    private SnapshotEnvelope() {
    }

    /** Simple type name; the FQN when fullyQualifiedTypePath is set. */
    static String typeName(String classFqn, boolean fq) {
        int cut = classFqn.lastIndexOf("::");
        return fq || cut < 0 ? classFqn : classFqn.substring(cut + 2);
    }

    static SqlSelect fold(SqlSelect env) {
        SqlSelect.Projection p = env.projections().get(0);
        if (!(p.expr() instanceof SqlExpr.JsonArrayAgg ja)) {
            return env;
        }
        SqlExpr count = new SqlAgg.Reducer(SqlAgg.Fn.COUNT,
                List.of(), false, List.of());
        SqlExpr sole = new SqlAgg.Reducer(SqlAgg.Fn.MIN,
                List.of(ja.value()), false, List.of());
        SqlExpr chosen = new SqlExpr.Case(List.of(
                new SqlExpr.Case.When(new SqlExpr.Call(SqlFn.EQUAL,
                        List.of(count, new SqlExpr.IntLit(1))), sole)),
                ja);
        return env.withProjections(
                List.of(new SqlSelect.Projection(chosen, "result")),
                List.of(new OutputCol("result",
                        PureSql.type(Type.Primitive.STRING), false)));
    }

    /** The null-stripping envelope object (Lowerer.serializeGraph):
     * one json_object per pair, json_merge_patch-folded — RFC 7386
     * merge REMOVES null-valued keys (removePropertiesWithNullValues);
     * removeEmptySets maps an ARRAY child's '[]' aggregate to NULL
     * first so the merge drops it too. */
    static SqlExpr mergePatchObject(java.util.List<SqlExpr> pairs,
            java.util.Set<String> arrayProps, boolean removeEmpty) {
        java.util.List<SqlExpr> pieces =
                new java.util.ArrayList<>(pairs.size() / 2);
        for (int i = 0; i < pairs.size(); i += 2) {
            SqlExpr k = pairs.get(i);
            SqlExpr v = pairs.get(i + 1);
            if (removeEmpty && k instanceof SqlExpr.StringLit sl
                    && arrayProps.contains(sl.value())) {
                v = new SqlExpr.Case(java.util.List.of(
                        new SqlExpr.Case.When(
                                SqlExpr.Call.of(com.legend.sql.SqlFn.EQUAL,
                                        new SqlExpr.Cast(v, com.legend.sql
                                                .SqlType.Scalar.VARCHAR),
                                        new SqlExpr.StringLit("[]")),
                                new SqlExpr.NullLit())), v);
            }
            pieces.add(new SqlExpr.JsonObject(java.util.List.of(k, v)));
        }
        return pieces.size() == 1 ? pieces.get(0)
                : new SqlExpr.Call(com.legend.sql.SqlFn.JSON_MERGE_PATCH,
                        pieces);
    }
}
