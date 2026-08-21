// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend.sql.dialect;

import com.legend.sql.SqlExpr;
import com.legend.sql.SqlFn;
import com.legend.sql.SqlRewriter;

import java.util.ArrayList;
import java.util.List;

/**
 * DuckDB-exec SUBSTRING start clamp (Phase 1 audit: the LAST
 * dialect-mode branch left the LOWERING layer — Scalars now emits ONE
 * verbatim SUBSTRING and this pass owns the backend difference, the
 * single-compiler tenet's shape). H2 clamps a sub-1 start at its own
 * runtime; DuckDB counts empties — so the DuckDB EXECUTION dialect
 * clamps in the rewrite chain, and the engine-TEXT dialect (which has
 * no such pass) stays verbatim for the advisory goldens.
 */
public final class SubstringClamp extends SqlRewriter {

    @Override
    protected SqlExpr expr(SqlExpr e) {
        if (e instanceof SqlExpr.Call c && c.fn() == SqlFn.SUBSTRING
                && c.args().size() >= 2) {
            SqlExpr st = c.args().get(1);
            SqlExpr clamped = st instanceof SqlExpr.IntLit il
                    ? (il.value() < 1 ? new SqlExpr.IntLit(1) : st)
                    : SqlExpr.Call.of(SqlFn.GREATEST, st,
                            new SqlExpr.IntLit(1));
            if (clamped != st) {
                List<SqlExpr> a2 = new ArrayList<>(c.args());
                a2.set(1, clamped);
                return new SqlExpr.Call(SqlFn.SUBSTRING, a2);
            }
        }
        return super.expr(e);
    }
}
