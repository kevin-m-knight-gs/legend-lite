// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend.sql.dialect;

import com.legend.sql.dialect.RawSqlBoundary;
import com.legend.sql.SqlRewriter;
import com.legend.sql.SqlSource;

/**
 * DuckDB-exec adaptation of AUTHORED raw SQL (Phase 1c): a
 * {@link SqlSource.RawSql} carries corpus/user-authored text whose
 * origin dialect is the engine's H2; on a DuckDB session the R0
 * boundary translation applies — as this DIALECT's own rewrite pass,
 * keeping the compiler dialect-blind (the same split as
 * {@link SubstringClamp}). H2-native sessions have no such pass and
 * run the text verbatim.
 */
public final class RawSqlAdapt extends SqlRewriter {

    @Override
    protected SqlSource source(SqlSource s) {
        if (s instanceof SqlSource.RawSql raw) {
            String adapted = RawSqlBoundary.h2ToDuckDb(raw.sql());
            if (!adapted.equals(raw.sql())) {
                return new SqlSource.RawSql(adapted, raw.alias(),
                        raw.outputs());
            }
        }
        return super.source(s);
    }
}
