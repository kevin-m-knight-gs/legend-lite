package com.legend.sql.dialect;

import com.legend.sql.SqlFn;

import java.util.EnumMap;
import java.util.Map;

/**
 * Dialect FUNCTION SPELLINGS &mdash; pure DATA (remediation T3.2): the
 * plain {@code name(args)} function names a backend uses, extracted from
 * what were 77 hardcoded switch arms with no override hook. Only
 * PURE spellings live here &mdash; an arm with any shape logic (arity
 * dispatch, wrapping casts, composite expansions) stays code. Named
 * honestly: today's row is DUCKDB's vocabulary (the audit: "AnsiSqlRenderer
 * is a DuckDB renderer with an ANSI name"); a genuine ANSI row is authored
 * when a backend needs different names, and adding it cannot break DuckDB.
 */
public record Spellings(Map<SqlFn, String> fnNames) {

    public Spellings {
        fnNames = Map.copyOf(fnNames);
    }

    public static final Spellings DUCKDB = new Spellings(build());

    private static Map<SqlFn, String> build() {
        Map<SqlFn, String> m = new EnumMap<>(SqlFn.class);
        m.put(SqlFn.ABS, "abs");
        m.put(SqlFn.ACOS, "acos");
        m.put(SqlFn.ASCII_CODE, "ascii");
        m.put(SqlFn.ASIN, "asin");
        m.put(SqlFn.ATAN, "atan");
        m.put(SqlFn.ATAN2, "atan2");
        m.put(SqlFn.CBRT, "cbrt");
        m.put(SqlFn.CHR, "chr");
        m.put(SqlFn.COALESCE, "coalesce");
        m.put(SqlFn.COS, "cos");
        m.put(SqlFn.COSH, "cosh");
        m.put(SqlFn.COT, "cot");
        m.put(SqlFn.DATE_DIFF, "date_diff");
        m.put(SqlFn.DATE_TRUNC, "date_trunc");
        m.put(SqlFn.DAYNAME, "dayname");
        m.put(SqlFn.DEGREES, "degrees");
        m.put(SqlFn.ENDS_WITH, "ends_with");
        m.put(SqlFn.EPOCH_MS, "epoch_ms");
        m.put(SqlFn.EPOCH_SECONDS, "epoch");
        m.put(SqlFn.ERROR, "error");
        m.put(SqlFn.EXP, "exp");
        m.put(SqlFn.EXTRACT, "date_part");
        m.put(SqlFn.FLOOR_RAW, "floor");
        m.put(SqlFn.FORMAT, "printf");
        m.put(SqlFn.FROM_EPOCH_SECONDS, "to_timestamp");
        m.put(SqlFn.GREATEST, "greatest");
        m.put(SqlFn.HASH, "hash");
        m.put(SqlFn.JARO_WINKLER, "jaro_winkler_similarity");
        m.put(SqlFn.JSON_TYPE, "json_type");
        m.put(SqlFn.LEAST, "least");
        m.put(SqlFn.LEFT, "left");
        m.put(SqlFn.LENGTH, "length");
        m.put(SqlFn.LEVENSHTEIN, "levenshtein");
        m.put(SqlFn.LIST_FLATTEN, "flatten");
        m.put(SqlFn.LIST_LENGTH, "len");
        m.put(SqlFn.LN, "ln");
        m.put(SqlFn.LOG10, "log10");
        m.put(SqlFn.LOWER, "lower");
        m.put(SqlFn.LTRIM, "ltrim");
        m.put(SqlFn.MAKE_DATE, "make_date");
        m.put(SqlFn.MAP_CONCAT, "map_concat");
        m.put(SqlFn.MAP_EXTRACT, "map_extract");
        m.put(SqlFn.MAP_FROM_ENTRIES, "map_from_entries");
        m.put(SqlFn.MAP_FROM_LISTS, "map");
        m.put(SqlFn.MAP_KEYS, "map_keys");
        m.put(SqlFn.MAP_VALUES, "map_values");
        m.put(SqlFn.MATCHES, "regexp_matches");
        m.put(SqlFn.MD5, "md5");
        m.put(SqlFn.MONTHNAME, "monthname");
        m.put(SqlFn.POW, "power");
        m.put(SqlFn.RADIANS, "radians");
        m.put(SqlFn.REGEXP_EXTRACT, "regexp_extract");
        m.put(SqlFn.REGEXP_EXTRACT_ALL, "regexp_extract_all");
        m.put(SqlFn.REGEXP_FULL_MATCH, "regexp_full_match");
        m.put(SqlFn.REGEXP_REPLACE, "regexp_replace");
        m.put(SqlFn.REPEAT_STR, "repeat");
        m.put(SqlFn.REPLACE, "replace");
        m.put(SqlFn.REVERSE_STRING, "reverse");
        m.put(SqlFn.RIGHT, "right");
        m.put(SqlFn.RTRIM, "rtrim");
        m.put(SqlFn.SHA1, "sha1");
        m.put(SqlFn.SHA256, "sha256");
        m.put(SqlFn.SIN, "sin");
        m.put(SqlFn.SINH, "sinh");
        m.put(SqlFn.SPLIT, "string_split");
        m.put(SqlFn.SPLIT_PART, "split_part");
        m.put(SqlFn.SQRT, "sqrt");
        m.put(SqlFn.STARTS_WITH, "starts_with");
        m.put(SqlFn.STRFTIME, "strftime");
        m.put(SqlFn.STRPOS, "strpos");
        m.put(SqlFn.STRPTIME, "strptime");
        m.put(SqlFn.SUBSTRING, "substr");
        m.put(SqlFn.TAN, "tan");
        m.put(SqlFn.TANH, "tanh");
        m.put(SqlFn.TIMEZONE, "timezone");
        m.put(SqlFn.TRIM, "trim");
        m.put(SqlFn.UPPER, "upper");
        return m;
    }
}
