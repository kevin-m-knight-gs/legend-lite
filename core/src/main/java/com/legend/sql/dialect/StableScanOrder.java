// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend.sql.dialect;

import com.legend.sql.SqlQuery;
import com.legend.sql.SqlRewriter;
import com.legend.sql.ScanOrder;

/**
 * ENGINE-CORPUS-COMPAT root pass (flag-gated in {@link DuckDb}): the
 * HOST-channel statements of the corpus replay get the deterministic
 * scan-order key — the engine's tests assert positionally while
 * relying on H2's implicit scan order. The key itself (and the
 * always-on ASSERT-boundary application) lives in {@link ScanOrder} —
 * one owner.
 */
final class StableScanOrder extends SqlRewriter {

    @Override
    public SqlQuery rewriteRoot(SqlQuery q) {
        // deep walk FIRST (fires the select hook on every nested
        // select), then the root-shape special cases (cap wrappers)
        return ScanOrder.stabilize(rewrite(q));
    }

    /** SUBSELECT inners stabilize too (disagree-9 burn): a verdict
     * statement compiles the asserted relation as a SUBQUERY (the
     * value-collection collect), where the root-only application
     * missed it — the HOST channel ran the same relation as its own
     * statement root and got the key, so the two channels read
     * different orders. Scoped to FROM-subselects (where ORDER BY is
     * legal SQL — a select hook also caught UNION BRANCHES and emitted
     * `... ORDER BY x UNION ALL ...`, a parser error). Same owner,
     * same orderable gate ({@link ScanOrder#stabilize} declines
     * DISTINCT/GROUP BY/aggregate/user-ordered shapes), same flag. */
    @Override
    protected com.legend.sql.SqlSource source(com.legend.sql.SqlSource s) {
        if (s instanceof com.legend.sql.SqlSource.Subselect sub
                && sub.inner() instanceof com.legend.sql.SqlSelect inner) {
            SqlQuery st = ScanOrder.stabilize(inner);
            if (st != inner) {
                return new com.legend.sql.SqlSource.Subselect(st,
                        sub.alias(), sub.frameName());
            }
        }
        return s;
    }
}
