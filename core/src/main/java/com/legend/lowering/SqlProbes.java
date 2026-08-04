// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend.lowering;

import com.legend.sql.SqlExpr;
import com.legend.sql.SqlRewriter;

/** Read-only MIR probes shared by scalar lowering rules. */
final class SqlProbes {

    private SqlProbes() {
    }

    /** Whether the expression tree carries a scalar subquery or exists
     * (walked via the shared MIR rewriter; probe-only). */
    static boolean containsSubquery(SqlExpr e) {
        boolean[] hit = {false};
        var probe = new SqlRewriter() {
            @Override
            protected SqlExpr expr(SqlExpr x) {
                if (x instanceof SqlExpr.ScalarSubquery
                        || x instanceof SqlExpr.Exists) {
                    hit[0] = true;
                }
                return x;
            }

            void scan(SqlExpr x) {
                rewriteExpr(x);
            }
        };
        probe.scan(e);
        return hit[0];
    }
}
