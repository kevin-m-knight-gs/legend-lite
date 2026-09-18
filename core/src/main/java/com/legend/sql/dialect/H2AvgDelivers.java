// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend.sql.dialect;

import com.legend.sql.SqlAgg;
import com.legend.sql.SqlExpr;
import com.legend.sql.SqlRewriter;
import com.legend.sql.SqlType;

/**
 * The dialect DELIVERS the platform's type facts on its wire. The
 * platform types {@code avg(...)} DOUBLE (DuckDB's truth); H2 computes
 * every average as DECFLOAT whatever its input (probed 2026-09-17: {@code
 * AVG(CAST(1.0 AS DOUBLE) * age)} is DECFLOAT), which a Float root then
 * prints as {@code 35.50000000000} or {@code 3E+1}. H2 casts its own
 * averages to DOUBLE so the fact holds; the boundary's no-cast decision
 * for a DOUBLE-typed wire stays a platform decision.
 */
public final class H2AvgDelivers extends SqlRewriter {
    @Override
    protected SqlExpr expr(SqlExpr e) {
        if (e instanceof SqlAgg a && a.fn() == SqlAgg.Fn.AVG) {
            return new SqlExpr.Cast(super.expr(e), SqlType.Scalar.DOUBLE);
        }
        return super.expr(e);
    }
}
