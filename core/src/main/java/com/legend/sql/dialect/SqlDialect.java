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

    /** B6 (truthfulness burn) — SESSION SETUP IS DIALECT-OWNED as a
     * FACT: the statements a backend's session needs for the
     * platform's value contracts. The dialect DECIDES; the exec layer
     * EXECUTES (F1.3: java.sql never enters this package) — applied at
     * {@code Compiler.dialectOf}'s connection seam, idempotent by
     * contract. Default: none. */
    default java.util.List<String> sessionSetup() {
        return java.util.List.of();
    }

    /** JDBC cell value → canonical Java value for {@code type}. Default: identity. */
    default @com.legend.Nullable Object normalize(@com.legend.Nullable Object jdbcValue,
            com.legend.sql.@com.legend.Nullable SqlType type) {
        return jdbcValue;
    }

    /** True when corpus-authored raw H2 statements execute NATIVELY on
     * this dialect's session — the {@code RawSqlBoundary.h2ToDuckDb}
     * rewrite is a DUCKDB-TARGET adaptation and must be identity here
     * (H2_BACKEND.md §12 step 12). */
    /** Whether dynamic PIVOT needs the two-phase staticization
     * pre-pass ({@link com.legend.exec.DynamicPivot} — no native
     * dynamic pivot on this backend). */
    default boolean needsStaticPivot() {
        return false;
    }

    default boolean rawH2IsNative() {
        return false;
    }

}
