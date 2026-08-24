// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend.lowering;

import com.legend.compiler.spec.typed.TypedNativeCall;
import com.legend.compiler.spec.typed.TypedSpec;
import com.legend.sql.SqlExpr;
import com.legend.sql.SqlFn;
import com.legend.sql.SqlType;

import java.util.List;
import java.util.function.BinaryOperator;

/**
 * The comparator-DEDUP family (CodeShapeGuardrail file split from
 * {@link Scalars}, F10 3b groundwork slice): real removeDuplicates-
 * with-comparator walks the list accumulating the KEPT prefix; a
 * candidate joins iff no kept element satisfies eq(kept, candidate).
 */
final class Dedup {

    private Dedup() {
    }

    /** Dedup-call count inside a typed subtree — the capture-free name suffix. */
    static int countDedups(TypedSpec spec) {
        int n = spec instanceof TypedNativeCall c
                && c.callee().qualifiedName()
                        .equals("meta::pure::functions::collection::removeDuplicates") ? 1 : 0;
        for (var child : spec.children()) {
            n += countDedups(child);
        }
        return n;
    }


    static SqlExpr keptDedup(SqlExpr list, int depth,
            BinaryOperator<SqlExpr> eq) {
        String ra = "_ra" + depth, rx = "_rx" + depth, rp = "_rp" + depth, rw = "_rw" + depth;
        SqlExpr wrapped = SqlExpr.Call.of(SqlFn.LIST_TRANSFORM, list,
                new SqlExpr.Lambda(List.of(rw),
                        new SqlExpr.ArrayLit(List.of(new SqlExpr.Column(null, rw)))));
        SqlExpr kept = new SqlExpr.Column(null, ra);
        SqlExpr cand = SqlExpr.Call.of(SqlFn.LIST_GET,
                new SqlExpr.Column(null, rx), new SqlExpr.IntLit(1));
        SqlExpr dup = SqlExpr.Call.of(SqlFn.GREATER,
                SqlExpr.Call.of(SqlFn.LIST_LENGTH,
                        SqlExpr.Call.of(SqlFn.LIST_FILTER, kept,
                                new SqlExpr.Lambda(List.of(rp),
                                        eq.apply(new SqlExpr.Column(null, rp), cand)))),
                new SqlExpr.IntLit(0));
        SqlExpr step = new SqlExpr.Case(List.of(new SqlExpr.Case.When(dup, kept)),
                SqlExpr.Call.of(SqlFn.LIST_APPEND, kept, cand));
        SqlExpr reduced = SqlExpr.Call.of(SqlFn.LIST_REDUCE, wrapped,
                new SqlExpr.Lambda(List.of(ra, rx), step));
        // list_reduce rejects the empty list — the empty dedup is itself
        SqlExpr out = new SqlExpr.Case(List.of(new SqlExpr.Case.When(
                SqlExpr.Call.of(SqlFn.EQUAL,
                        SqlExpr.Call.of(SqlFn.COALESCE,
                                SqlExpr.Call.of(SqlFn.LIST_LENGTH, list),
                                new SqlExpr.IntLit(0)),
                        new SqlExpr.IntLit(0)),
                list)), reduced);
        // F10 3b: dedup preserves ELEMENTS — a LITERAL-carried input's
        // result re-marks (LIST_REDUCE is beyond the judgment; the
        // construction site knows)
        return list instanceof SqlExpr.Cast km
                && km.target() instanceof SqlType.Array ka
                && ka.element() == SqlType.Scalar.LITERAL
                ? new SqlExpr.Cast(out, new SqlType.Array(SqlType.Scalar.LITERAL))
                : out;
    }
}
