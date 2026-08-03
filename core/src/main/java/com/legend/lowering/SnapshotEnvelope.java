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
}
