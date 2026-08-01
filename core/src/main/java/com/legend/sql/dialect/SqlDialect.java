package com.legend.sql.dialect;

import com.legend.sql.SqlQuery;

/**
 * The dialect seam: rendering (Phase J) AND value normalization (Phase K).
 * A backend's JDBC driver may hand back dialect-flavored Java objects
 * (SQLite: dates as Strings, booleans as ints); {@link #normalize} converts
 * to the canonical representation for a PURE type so the typed-result
 * contract holds on every backend.
 */
public interface SqlDialect {

    String render(SqlQuery query);

    /** JDBC cell value → canonical Java value for {@code type}. Default: identity. */
    default @com.legend.Nullable Object normalize(@com.legend.Nullable Object jdbcValue,
            com.legend.sql.@com.legend.Nullable SqlType type) {
        return jdbcValue;
    }

    /** True when corpus-authored raw H2 statements execute NATIVELY on
     * this dialect's session — the {@code RawSqlBoundary.h2ToDuckDb}
     * rewrite is a DUCKDB-TARGET adaptation and must be identity here
     * (H2_BACKEND.md §12 step 12). */
    default boolean rawH2IsNative() {
        return false;
    }

}
