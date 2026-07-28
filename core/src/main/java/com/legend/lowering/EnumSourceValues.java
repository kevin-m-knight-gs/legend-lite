// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend.lowering;

import com.legend.sql.SqlExpr;

import java.util.ArrayList;
import java.util.List;

/**
 * Enum toSourceValues (engine pureToSQLQuery:5866): the mapping's enum
 * DECODE emission compares by SOURCE value, never by decoded name.
 */
final class EnumSourceValues {

    private EnumSourceValues() {
    }

    /**
     * Enum toSourceValues (engine pureToSQLQuery:5866): comparing a
     * DECODE case-chain (every branch a distinct string literal, null
     * terminal — the mapping's enum decode emission) against a string
     * literal inverts to the matching branch's SOURCE condition
     * ({@code "root".active = 0}) — the engine never compares decoded
     * names. Pure algebra: valid for any literal-decode case shape.
     */
    static SqlExpr decodeInvert(SqlExpr a, SqlExpr b) {
        SqlExpr lit = b instanceof SqlExpr.StringLit ? b
                : a instanceof SqlExpr.StringLit ? a : null;
        if (lit == null) {
            return null;
        }
        SqlExpr chain = lit == b ? a : b;
        List<SqlExpr.Case.When> flat = flattenDecode(chain);
        if (flat == null) {
            return null;
        }
        SqlExpr match = null;
        String want = ((SqlExpr.StringLit) lit).value();
        for (var w : flat) {
            if (((SqlExpr.StringLit) w.then()).value().equals(want)) {
                if (match != null) {
                    return null;   // ambiguous decode — keep the compare
                }
                match = w.condition();
            }
        }
        return match;
    }

    /** The decode chain's (condition, literal) branches, or null when
     * {@code e} is not a literal-decode case (nested via otherwise). */
    private static List<SqlExpr.Case.When> flattenDecode(SqlExpr e) {
        List<SqlExpr.Case.When> out = new ArrayList<>();
        while (e instanceof SqlExpr.Case c) {
            for (var w : c.whens()) {
                if (!(w.then() instanceof SqlExpr.StringLit)) {
                    return null;
                }
                out.add(w);
            }
            e = c.otherwise();
        }
        return e == null || e instanceof SqlExpr.NullLit
                ? (out.isEmpty() ? null : out) : null;
    }

}
