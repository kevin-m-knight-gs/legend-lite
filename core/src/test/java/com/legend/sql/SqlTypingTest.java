// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend.sql;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * TYPED-IR Slice 1 — judgment smoke pins: leaves, composition, the
 * partiality contract (no rule = null, NEVER a guess).
 */
class SqlTypingTest {

    private static final java.util.function.Function<SqlExpr.Column,
            SqlType> NO_SCOPE = c -> null;

    @Test
    @DisplayName("leaves type themselves; casts are authoritative")
    void leaves() {
        assertEquals(SqlType.Scalar.VARCHAR,
                SqlTyping.of(new SqlExpr.StringLit("a"), NO_SCOPE));
        assertEquals(SqlType.Scalar.BIGINT,
                SqlTyping.of(new SqlExpr.IntLit(1), NO_SCOPE));
        assertEquals(SqlType.Scalar.DOUBLE,
                SqlTyping.of(new SqlExpr.FloatLit(2.5), NO_SCOPE));
        assertEquals(SqlType.Scalar.JSON,
                SqlTyping.of(new SqlExpr.Cast(new SqlExpr.StringLit("x"),
                        SqlType.Scalar.JSON), NO_SCOPE));
    }

    @Test
    @DisplayName("composition: structs, arrays, list_transform binds its"
            + " lambda parameter to the element type")
    void composition() {
        SqlExpr arr = new SqlExpr.ArrayLit(List.of(
                new SqlExpr.IntLit(1), new SqlExpr.IntLit(2)));
        assertEquals(new SqlType.Array(SqlType.Scalar.BIGINT),
                SqlTyping.of(arr, NO_SCOPE));
        SqlExpr mapped = SqlExpr.Call.of(SqlFn.LIST_TRANSFORM, arr,
                new SqlExpr.Lambda(List.of("e"),
                        new SqlExpr.Cast(new SqlExpr.Column(null, "e"),
                                SqlType.Scalar.VARCHAR)));
        assertEquals(new SqlType.Array(SqlType.Scalar.VARCHAR),
                SqlTyping.of(mapped, NO_SCOPE));
        SqlExpr struct = new SqlExpr.StructLit(List.of(
                new SqlExpr.StructLit.Field("a", new SqlExpr.IntLit(1))));
        assertEquals(new SqlType.Struct(List.of(
                        new SqlType.Struct.Field("a", SqlType.Scalar.BIGINT))),
                SqlTyping.of(struct, NO_SCOPE));
    }

    @Test
    @DisplayName("partiality: numeric promotion and unknown shapes are"
            + " NULL — counted, never guessed")
    void partiality() {
        assertNull(SqlTyping.of(SqlExpr.Call.of(SqlFn.PLUS,
                new SqlExpr.IntLit(1), new SqlExpr.FloatLit(2.0)), NO_SCOPE));
        assertNull(SqlTyping.of(new SqlExpr.NullLit(), NO_SCOPE));
        // mixed CASE branches: no common type, no guess
        assertNull(SqlTyping.of(new SqlExpr.Case(List.of(
                new SqlExpr.Case.When(new SqlExpr.BoolLit(true),
                        new SqlExpr.IntLit(1))),
                new SqlExpr.StringLit("x")), NO_SCOPE));
    }
}
