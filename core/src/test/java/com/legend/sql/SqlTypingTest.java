// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend.sql;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The tree's own types (TYPED_SQL_IR.md): every expression stores its
 * type fact at construction — literals intrinsically, compositions via
 * the {@link SqlTyping} rule table over their children's stored facts,
 * lambda params through the attachment doors. (The former judge's
 * tests re-homed here when it deleted — same semantic claims, read
 * from the tree.)
 */
class SqlTypingTest {

    private static TypeFact t(SqlExpr e) {
        return e.type();
    }

    @Test
    void literalsAndCastsCarryTheirTypes() {
        assertEquals(SqlTyping.typed(SqlType.Scalar.VARCHAR),
                t(new SqlExpr.StringLit("a")));
        assertEquals(SqlTyping.typed(SqlType.Scalar.BIGINT),
                t(new SqlExpr.IntLit(1)));
        assertEquals(SqlTyping.typed(SqlType.Scalar.DOUBLE),
                t(new SqlExpr.FloatLit(2.5)));
        assertEquals(SqlTyping.typed(SqlType.Scalar.DATE),
                t(new SqlExpr.Cast(new SqlExpr.StringLit("x"),
                        SqlType.Scalar.DATE)));
    }

    @Test
    void compositionsComputeFromStoredChildren() {
        SqlExpr arr = new SqlExpr.ArrayLit(List.of(
                new SqlExpr.IntLit(1), new SqlExpr.IntLit(2)));
        assertEquals(SqlTyping.typed(
                        new SqlType.Array(SqlType.Scalar.BIGINT)),
                t(arr));
        // LIST_TRANSFORM through the ATTACHMENT door: the param stamps
        // as the collection's element; the body's stored type flows up
        SqlExpr mapped = SqlExpr.Call.of(SqlFn.LIST_TRANSFORM, arr,
                SqlExpr.Lambda.bind(new SqlExpr.Lambda(List.of("x"),
                        SqlExpr.Call.of(SqlFn.CONCAT,
                                new SqlExpr.Column(null, "x"),
                                new SqlExpr.StringLit("!"))), arr));
        assertEquals(SqlTyping.typed(
                        new SqlType.Array(SqlType.Scalar.VARCHAR)),
                t(mapped));
        SqlExpr struct = new SqlExpr.StructLit(List.of(
                new SqlExpr.StructLit.Field("a", new SqlExpr.IntLit(1)),
                new SqlExpr.StructLit.Field("b",
                        new SqlExpr.StringLit("s"))));
        assertEquals(SqlTyping.typed(new SqlType.Struct(List.of(
                        new SqlType.Struct.Field("a", SqlType.Scalar.BIGINT),
                        new SqlType.Struct.Field("b",
                                SqlType.Scalar.VARCHAR)))),
                t(struct));
    }

    @Test
    void bottomIsTheNullValue() {
        assertEquals(SqlTyping.BOTTOM, t(new SqlExpr.NullLit()));
        // a CASE whose every branch is the NULL value IS the NULL value
        assertEquals(SqlTyping.BOTTOM, t(new SqlExpr.Case(List.of(
                new SqlExpr.Case.When(new SqlExpr.BoolLit(true),
                        new SqlExpr.NullLit())), new SqlExpr.NullLit())));
    }

    @Test
    void arithmeticPromotesPerTheProbedMatrix() {
        // any DOUBLE operand wins; all-integer keeps the widest width
        assertEquals(SqlTyping.typed(SqlType.Scalar.DOUBLE),
                t(SqlExpr.Call.of(SqlFn.PLUS,
                        new SqlExpr.IntLit(1), new SqlExpr.FloatLit(2.0))));
        assertEquals(SqlTyping.typed(SqlType.Scalar.BIGINT),
                t(SqlExpr.Call.of(SqlFn.TIMES,
                        new SqlExpr.IntLit(2), new SqlExpr.IntLit(3))));
        // the NULL value propagates — arithmetic is strict
        assertEquals(SqlTyping.BOTTOM, t(SqlExpr.Call.of(SqlFn.PLUS,
                new SqlExpr.IntLit(1), new SqlExpr.NullLit())));
    }

    @Test
    void errorBranchesAreBottomLikeInBranchFamilies() {
        // the checked-extract shape: an error() guard branch plus a
        // LIST_GET over Array(LITERAL) — error() raises, it never
        // yields a value, so the family types from the value branch
        SqlExpr carried = new SqlExpr.Cast(
                new SqlExpr.ArrayLit(List.of(new SqlExpr.StringLit("1"))),
                new SqlType.Array(SqlType.Scalar.LITERAL));
        SqlExpr checked = new SqlExpr.Case(List.of(
                new SqlExpr.Case.When(new SqlExpr.BoolLit(true),
                        SqlExpr.Call.of(SqlFn.ERROR,
                                new SqlExpr.StringLit("index out of bounds")))),
                SqlExpr.Call.of(SqlFn.LIST_GET, carried,
                        new SqlExpr.IntLit(1)));
        assertEquals(SqlTyping.typed(SqlType.Scalar.LITERAL), t(checked));
        // an ALL-error family is bottom-like as a whole (raise dominates)
        assertEquals(SqlTyping.BOTTOM, t(new SqlExpr.Case(List.of(
                new SqlExpr.Case.When(new SqlExpr.BoolLit(false),
                        SqlExpr.Call.of(SqlFn.ERROR,
                                new SqlExpr.StringLit("boom")))),
                new SqlExpr.NullLit())));
    }

    @Test
    void noRuleMeansUnknownNeverAGuess() {
        // DECIMAL arithmetic: probed on the reference jar 2026-08-25
        // (was "deliberately UNKNOWN" until the version-specific
        // formula was pinned by probe) — BIGINT (19,0) + literal
        // (3,2): s=2, w=max(19,1)+2+1=22. RECEIPT: DuckDB 1.5.0
        // typeof(1::BIGINT + 1.50::DECIMAL(3,2)) = DECIMAL(22,2).
        assertEquals(SqlTyping.typed(new SqlType.Decimal(22, 2)),
                t(SqlExpr.Call.of(SqlFn.PLUS,
                        new SqlExpr.IntLit(1),
                        new SqlExpr.DecimalLit(
                                new java.math.BigDecimal("1.50")))));
        // MOD over decimals stays an unprobed corner — no rule, UNKNOWN
        assertEquals(SqlTyping.UNKNOWN, t(SqlExpr.Call.of(SqlFn.MOD,
                new SqlExpr.DecimalLit(new java.math.BigDecimal("1.5")),
                new SqlExpr.DecimalLit(new java.math.BigDecimal("1.0")))));
        // an unstamped column is UNKNOWN until its builder supplies it
        assertEquals(SqlTyping.UNKNOWN, t(new SqlExpr.Column("t", "c")));
    }
}
