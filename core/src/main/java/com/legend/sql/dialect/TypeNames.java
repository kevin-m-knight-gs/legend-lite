package com.legend.sql.dialect;

import com.legend.sql.SqlType;

import java.util.EnumMap;
import java.util.Map;

/**
 * Dialect TYPE SPELLINGS &mdash; pure DATA (remediation T3.2, extraction 2):
 * the scalar CAST names a backend uses, plus its composite-type
 * capabilities. Absence is LOUD at the read site &mdash; a scalar with no
 * spelling here is "this backend cannot cast to that type", never a
 * silent fallback. Composite RULES (DECIMAL(p,s), array suffix, MAP,
 * STRUCT field lists) are structural and live with the renderer; this
 * record only says which are supported and what the leaves are called.
 *
 * @param scalarNames   scalar &rarr; spelling; absent = unsupported (loud)
 * @param structSupport whether STRUCT(...) composite casts exist
 */
public record TypeNames(Map<SqlType.Scalar, String> scalarNames,
        boolean structSupport) {

    public TypeNames {
        scalarNames = Map.copyOf(scalarNames);
    }

    /** ANSI baseline: no JSON, no STRUCT. */
    public static final TypeNames ANSI = new TypeNames(base(), false);

    /** DuckDB: bare DOUBLE, native JSON, STRUCT(...). */
    public static final TypeNames DUCKDB = new TypeNames(duck(), true);

    private static Map<SqlType.Scalar, String> base() {
        Map<SqlType.Scalar, String> m = new EnumMap<>(SqlType.Scalar.class);
        m.put(SqlType.Scalar.BOOLEAN, "BOOLEAN");
        m.put(SqlType.Scalar.INTEGER, "INTEGER");
        m.put(SqlType.Scalar.BIGINT, "BIGINT");
        m.put(SqlType.Scalar.HUGEINT, "HUGEINT");
        m.put(SqlType.Scalar.TIMESTAMPTZ, "TIMESTAMPTZ");
        m.put(SqlType.Scalar.DOUBLE, "DOUBLE PRECISION");
        m.put(SqlType.Scalar.VARCHAR, "VARCHAR");
        m.put(SqlType.Scalar.DATE, "DATE");
        m.put(SqlType.Scalar.TIMESTAMP, "TIMESTAMP");
        return m;
    }

    private static Map<SqlType.Scalar, String> duck() {
        Map<SqlType.Scalar, String> m = base();
        m.put(SqlType.Scalar.DOUBLE, "DOUBLE");
        m.put(SqlType.Scalar.JSON, "JSON");
        return m;
    }
}
