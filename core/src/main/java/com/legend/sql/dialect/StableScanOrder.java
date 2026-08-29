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
        return ScanOrder.stabilize(q);
    }
}
