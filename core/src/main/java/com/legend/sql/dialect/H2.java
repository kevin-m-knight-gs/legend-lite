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
}
