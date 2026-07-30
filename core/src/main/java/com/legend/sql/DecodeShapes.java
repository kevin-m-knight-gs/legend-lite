// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend.sql;

import com.legend.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Structural analysis of literal-DECODE case chains (the mapping's enum
 * decode emission: every branch {@code col = literal -> 'NAME'}, null
 * terminal). Pure {@link SqlExpr} shape work — shared by the lowering's
 * toSourceValues inversion, the dialect's enum selector template, and
 * the plan text's computed-column typing.
 */
public final class DecodeShapes {

    private DecodeShapes() {
    }

    /** The chain's (condition, literal) branches, or null when {@code e}
     * is not a literal-decode case (nested via otherwise). */
    public static @Nullable List<SqlExpr.Case.When> flattenDecode(SqlExpr e) {
        List<SqlExpr.Case.When> out = new ArrayList<>();
        @Nullable SqlExpr cur = e;
        while (cur instanceof SqlExpr.Case c) {
            for (var w : c.whens()) {
                if (!(w.then() instanceof SqlExpr.StringLit)) {
                    return null;
                }
                out.add(w);
            }
            cur = c.otherwise();
        }
        return cur == null || cur instanceof SqlExpr.NullLit
                ? (out.isEmpty() ? null : out) : null;
    }

    /** The ONE source expression every branch condition compares
     * ({@code src = literal}), or null. */
    public static @Nullable SqlExpr sourceExpr(SqlExpr e) {
        List<SqlExpr.Case.When> flat = flattenDecode(e);
        if (flat == null) {
            return null;
        }
        SqlExpr src = null;
        for (var w : flat) {
            if (!(w.condition() instanceof SqlExpr.Call cc)
                    || cc.fn() != SqlFn.EQUAL || cc.args().size() != 2) {
                return null;
            }
            SqlExpr left = cc.args().get(0);
            if (src == null) {
                src = left;
            } else if (!src.equals(left)) {
                return null;
            }
        }
        return src;
    }

    /** {@link #sourceExpr} narrowed to a raw store COLUMN, or null. */
    public static SqlExpr.@Nullable Column sourceColumn(SqlExpr e) {
        return sourceExpr(e) instanceof SqlExpr.Column c ? c : null;
    }

    /** {@code e} with every interior literal-decode chain replaced by
     * its source column; {@code e} itself when nothing rewrites. */
    public static SqlExpr stripDecodes(SqlExpr e) {
        SqlExpr.Column src = sourceColumn(e);
        if (src != null) {
            return src;
        }
        switch (e) {
            case SqlExpr.Call c -> {
                List<SqlExpr> args = new ArrayList<>();
                boolean ch = false;
                for (SqlExpr a : c.args()) {
                    SqlExpr r = stripDecodes(a);
                    ch |= r != a;
                    args.add(r);
                }
                return ch ? new SqlExpr.Call(c.fn(), args) : e;
            }
            case SqlExpr.Case cs -> {
                List<SqlExpr.Case.When> ws = new ArrayList<>();
                boolean ch = false;
                for (var w : cs.whens()) {
                    SqlExpr cnd = stripDecodes(w.condition());
                    SqlExpr th = stripDecodes(w.then());
                    ch |= cnd != w.condition() || th != w.then();
                    ws.add(new SqlExpr.Case.When(cnd, th));
                }
                SqlExpr ow = cs.otherwise() == null ? null
                        : stripDecodes(cs.otherwise());
                ch |= ow != cs.otherwise();
                return ch ? new SqlExpr.Case(ws, ow) : e;
            }
            case SqlExpr.Cast ct -> {
                SqlExpr v = stripDecodes(ct.value());
                return v != ct.value()
                        ? new SqlExpr.Cast(v, ct.target()) : e;
            }
            case SqlExpr.Group g -> {
                SqlExpr i = stripDecodes(g.inner());
                return i != g.inner() ? new SqlExpr.Group(i) : e;
            }
            default -> {
                return e;
            }
        }
    }

}
