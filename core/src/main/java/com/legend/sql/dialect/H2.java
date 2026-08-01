// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend.sql.dialect;

import com.legend.sql.SqlExpr;
import com.legend.sql.SqlFn;

import java.util.List;

/**
 * The H2 EXECUTION dialect (H2_BACKEND.md §12 step 8) — a sibling of
 * {@link DuckDb} extending {@link AnsiSqlRenderer}, NEVER
 * {@link EngineStyleH2} (whose output is byte-pinned to the engine's
 * golden TEXT and stays quarantined). This dialect's contract is
 * EXECUTABLE H2 2.1.214 SQL: every spelling here was probed against the
 * real jar or read from its function catalog; anything H2 cannot express
 * inherits the base renderer's LOUD walls and graduates into the
 * declared-gap registry (§9), never a silent wrong answer.
 *
 * <p>Capability notes (probed, see the doc's verification addendum):
 * native QUALIFY; NO correlated table-function arguments in any version
 * through 2.4.240 (LATERAL absent — the collection-carrier family walls);
 * JSON array indexing only at 2.2+ (no object-field navigation in any
 * version — the engine uses a Java UDF there, a route this dialect bans).
 */
public final class H2 extends AnsiSqlRenderer {

    public H2() {
        super(Lexicon.H2, TypeNames.H2, Spellings.H2);
    }

    @Override
    protected boolean supportsQualify() {
        return true;
    }

    @Override
    public boolean rawH2IsNative() {
        return true;
    }

    @Override
    protected String call(SqlExpr.Call c, int parentPrec) {
        List<SqlExpr> a = c.args();
        // integer division: the base renderer spells DuckDB's `a // b`,
        // and `//` is a LINE COMMENT on H2 — `SELECT 7 // 2` returns 7
        // silently (H2_BACKEND.md H5.2). H2's `/` over INT operands IS
        // integer division; CAST pins the operand type against widening.
        if (c.fn() == SqlFn.INT_DIVIDE) {
            return "CAST(" + expr(a.get(0), 6) + " / " + expr(a.get(1), 6)
                    + " AS BIGINT)";
        }
        return super.call(c, parentPrec);
    }

    /** H2's row-order pseudo-column (probed 2.1.214: bare and inside
     * STRING_AGG's ORDER BY). */
    @Override
    protected String rowOrderColumn() {
        return "_ROWID_";
    }

    /** SQL-standard constructor, probed on 2.1.214:
     * {@code JSON_OBJECT('k': v, ...)}. The kv list alternates
     * key-expression, value-expression. */
    @Override
    protected String jsonObject(SqlExpr.JsonObject j) {
        StringBuilder sb = new StringBuilder("JSON_OBJECT(");
        List<SqlExpr> kv = j.kv();
        for (int i = 0; i + 1 < kv.size(); i += 2) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(expr(kv.get(i), 0)).append(": ")
                    .append(expr(kv.get(i + 1), 0));
        }
        return sb.append(')').toString();
    }

    /** SQL-standard aggregate, probed on 2.1.214:
     * {@code COALESCE(JSON_ARRAYAGG(v ORDER BY ...), JSON '[]')} — the
     * COALESCE keeps the graph contract's empty-array-over-zero-rows. */
    @Override
    protected String jsonArrayAgg(SqlExpr.JsonArrayAgg j) {
        StringBuilder sb = new StringBuilder("COALESCE(JSON_ARRAYAGG(")
                .append(expr(j.value(), 0));
        if (!j.orderKeys().isEmpty()) {
            sb.append(" ORDER BY ");
            for (int i = 0; i < j.orderKeys().size(); i++) {
                var k = j.orderKeys().get(i);
                if (i > 0) {
                    sb.append(", ");
                }
                sb.append(expr(k.expr(), 0))
                        .append(k.desc() ? " DESC" : " ASC")
                        .append(" NULLS LAST");
            }
        }
        return sb.append("), JSON '[]')").toString();
    }

    /** H2 hands JSON values back as {@code byte[]} (UTF-8 text) — the
     * canonical envelope representation is the STRING (probed 2.1.214;
     * H2_BACKEND.md §12 step 11's first codec row). */
    @Override
    public @com.legend.Nullable Object normalize(@com.legend.Nullable Object jdbcValue,
            com.legend.sql.@com.legend.Nullable SqlType type) {
        if (jdbcValue instanceof byte[] b
                && type == com.legend.sql.SqlType.Scalar.JSON) {
            return new String(b, java.nio.charset.StandardCharsets.UTF_8);
        }
        return jdbcValue;
    }
}
