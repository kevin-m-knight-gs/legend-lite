// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend.lowering;

import com.legend.sql.SqlExpr;
import com.legend.sql.SqlFn;
import com.legend.sql.SqlType;

import java.util.List;

/**
 * The COLLECTION-value list encodings (extracted from {@link Lowerer} at
 * the file guardrail): map/slice over values that travel as DuckDB
 * lists. Relation-typed sources never come here — they take the
 * relation arms.
 */
final class ListEncodings {

    private ListEncodings() {
    }

    /**
     * {@code map} over a collection VALUE. WIRE SHAPE FOLLOWS THE TYPE
     * (tenet: types drive construction): a TO-ONE/[0..1] source cannot
     * feed {@code list_transform} bare — a BINDER error even in a dead
     * CASE arm (DuckDB type-checks both arms; assertEquals' many-path
     * over a {@code head()} actual — Phase 4 channel B, 12 essential
     * tests) — so a SCALAR-shaped source wraps as its singleton list
     * (one already list-shaped — a one-element collection literal —
     * passes through); and when the map RESULT is itself to-one/[0..1],
     * the value unwraps back to the scalar wire. A [0..1] source
     * null-guards both ways: map over EMPTY is EMPTY, never
     * {@code f(NULL)}.
     */
    static SqlExpr map(SqlExpr src, SqlExpr lam, boolean srcOne,
            boolean srcNullable, boolean resultOne, boolean collectionMapper) {
        boolean alreadyList = src instanceof SqlExpr.ArrayLit
                || src instanceof SqlExpr.NullLit;
        boolean srcOptional = srcOne && srcNullable && !alreadyList;
        SqlExpr listSrc = src;
        if (srcOne && !alreadyList) {
            SqlExpr singleton = new SqlExpr.ArrayLit(List.of(src));
            listSrc = srcOptional && !resultOne
                    ? new SqlExpr.Case(List.of(new SqlExpr.Case.When(
                            SqlExpr.Call.of(SqlFn.IS_NULL, src),
                            new SqlExpr.ArrayLit(List.of()))), singleton)
                    : singleton;
        }
        SqlExpr transformed = SqlExpr.Call.of(SqlFn.LIST_TRANSFORM,
                listSrc, lam);
        if (srcOne && resultOne) {
            SqlExpr applied = SqlExpr.Call.of(SqlFn.LIST_GET, transformed,
                    new SqlExpr.IntLit(1));
            return srcOptional
                    ? new SqlExpr.Case(List.of(new SqlExpr.Case.When(
                            SqlExpr.Call.of(SqlFn.IS_NULL, src),
                            new SqlExpr.NullLit())), applied)
                    : applied;
        }
        return collectionMapper
                ? SqlExpr.Call.of(SqlFn.LIST_FLATTEN, transformed)
                : transformed;
    }

    /** {@code zip(a, b)}: pairwise {first, second} structs up to the
     * SHORTER side. An EMPTY side is SQL NULL and len(NULL) is NULL —
     * which LEAST would IGNORE (it skips nulls), silently zipping
     * against the non-empty side — so the count zeroes explicitly, and
     * a NULL whole-zip coalesces to pure's EMPTY list. */
    static SqlExpr zip(SqlExpr a, SqlExpr b) {
        SqlExpr count = SqlExpr.Call.of(SqlFn.LEAST,
                SqlExpr.Call.of(SqlFn.COALESCE,
                        SqlExpr.Call.of(SqlFn.LIST_LENGTH, a),
                        new SqlExpr.IntLit(0)),
                SqlExpr.Call.of(SqlFn.COALESCE,
                        SqlExpr.Call.of(SqlFn.LIST_LENGTH, b),
                        new SqlExpr.IntLit(0)));
        SqlExpr i = new SqlExpr.Column(null, "_zip_i");
        SqlExpr body = new SqlExpr.StructLit(List.of(
                new SqlExpr.StructLit.Field("first",
                        SqlExpr.Call.of(SqlFn.LIST_GET, a, i)),
                new SqlExpr.StructLit.Field("second",
                        SqlExpr.Call.of(SqlFn.LIST_GET, b, i))));
        return SqlExpr.Call.of(SqlFn.COALESCE,
                SqlExpr.Call.of(SqlFn.LIST_TRANSFORM,
                        SqlExpr.Call.of(SqlFn.RANGE_FN,
                                new SqlExpr.IntLit(1), onePlus(count)),
                        new SqlExpr.Lambda(List.of("_zip_i"), body)),
                new SqlExpr.ArrayLit(List.of()));
    }

    /** {@code slice(start, stop)}: 0-based exclusive-stop → 1-based
     * inclusive array_slice; NEGATIVE bounds clamp to the list head
     * (PCT — DuckDB reads a negative bound FROM THE END), and inverted
     * bounds RAISE real pure's message, in the database. */
    static SqlExpr slice(SqlExpr src, SqlExpr start, SqlExpr stop) {
        SqlExpr lo = clamp0(start);
        SqlExpr hi = clamp0(stop);
        SqlExpr sliced = SqlExpr.Call.of(SqlFn.LIST_SLICE, src,
                onePlus(lo), hi);
        return new SqlExpr.Case(List.of(new SqlExpr.Case.When(
                SqlExpr.Call.of(SqlFn.GREATER, lo, hi),
                SqlExpr.Call.of(SqlFn.ERROR,
                        SqlExpr.Call.of(SqlFn.CONCAT,
                                SqlExpr.Call.of(SqlFn.CONCAT,
                                        SqlExpr.Call.of(SqlFn.CONCAT,
                                                new SqlExpr.StringLit("The low bound ("),
                                                new SqlExpr.Cast(lo, SqlType.Scalar.VARCHAR)),
                                        new SqlExpr.StringLit(") can't be higher than the high bound (")),
                                SqlExpr.Call.of(SqlFn.CONCAT,
                                        new SqlExpr.Cast(hi, SqlType.Scalar.VARCHAR),
                                        new SqlExpr.StringLit(") in a slice operation")))))),
                sliced);
    }

    /**
     * FIRST-OCCURRENCE dedup (real removeDuplicates semantics — its PCT
     * asserts order without sorting). LIST_DISTINCT is UNORDERED in DuckDB;
     * keep element x at 1-based index i iff its first position is i.
     */
    static SqlExpr orderedDedup(SqlExpr list) {
        return new SqlExpr.Call(SqlFn.LIST_FILTER, List.of(list,
                new SqlExpr.Lambda(List.of("_ddx", "_ddi"),
                        new SqlExpr.Call(SqlFn.EQUAL, List.of(
                                SqlExpr.Call.of(SqlFn.LIST_POSITION, list,
                                        new SqlExpr.Column(null, "_ddx")),
                                new SqlExpr.Column(null, "_ddi"))))));
    }

    /** Clamp a (possibly negative) index to zero — PCT's slice/drop/take edge semantics. */
    static SqlExpr clamp0(SqlExpr e) {
        return e instanceof SqlExpr.IntLit i
                ? new SqlExpr.IntLit(Math.max(0, i.value()))
                : SqlExpr.Call.of(SqlFn.GREATEST, e, new SqlExpr.IntLit(0));
    }

    /** 0-based → 1-based shift, constant-folded for literals. */
    static SqlExpr onePlus(SqlExpr e) {
        return e instanceof SqlExpr.IntLit i
                ? new SqlExpr.IntLit(i.value() + 1)
                : SqlExpr.Call.of(SqlFn.PLUS, e, new SqlExpr.IntLit(1));
    }
}
