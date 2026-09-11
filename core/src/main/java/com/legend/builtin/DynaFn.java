package com.legend.builtin;

import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * THE ENGINE'S DYNAFUNCTION REGISTRY, as data: every operator name a relational
 * mapping expression may use ({@code hash(col)}, {@code isDistinct(a, b)},
 * {@code concat(…)}), read from the pinned legend-engine checkout's SQL rendering
 * registries — {@code dynaFnToSql('<name>', …)} in {@code extensionDefaults.pure}
 * and every dialect extension — and how THIS platform resolves each one:
 * <ul>
 *   <li>{@link Resolution#PURE}: passes through to the Pure native(s) of the same
 *       bare name in {@link Pure} — the engine's operator IS pure's function;</li>
 *   <li>{@link Resolution#SHIM}: an engine-only operator with no pure signature
 *       (or a shape pure's differs from) — its {@link Pure.Lite} identity;</li>
 *   <li>{@link Resolution#TRANSLATED}: the mapping translator ({@code RelOpTranslator})
 *       rewrites the call into pure's own spelling (concat → the string run,
 *       add/sub → the arithmetic run, isNull → isEmpty, md5 → hash(…, MD5), …)
 *       and NOTHING passes through — a shape no arm rewrites is an error;</li>
 *   <li>{@link Resolution#UNSUPPORTED}: registered by the engine, handled by nothing
 *       here yet — a mapping using it fails LOUD naming the operator.</li>
 * </ul>
 * A PURE name may ALSO carry a translator arm for the engine's extra shape
 * ({@code and}/{@code or} with more than two operands, {@code parseDate} with a
 * format): the arm rewrites that shape and pure's own shape passes through —
 * {@code DynaFnArms.ARMS} lists every name with an arm, TRANSLATED or PURE.
 * GENERATED from the checkouts by {@code DynaFnRegistryTest -Ddynafn.generate=1}
 * (members, dialects); the resolution column is the platform's own decision, kept
 * by hand and VERIFIED by the same test (a PURE name must exist in the catalog, a
 * SHIM must name a Lite constant, every TRANSLATED name has an arm and every armed
 * name not TRANSLATED is PURE).
 * Never a name set anywhere else: {@link #of(String)} is the one lookup.
 */
public enum DynaFn {
    ABS("abs", Resolution.PURE, null, Dialect.DEFAULT),
    ACOS("acos", Resolution.PURE, null, Dialect.DEFAULT),
    ADD("add", Resolution.TRANSLATED, null, Dialect.DEFAULT),
    ADJUST("adjust", Resolution.TRANSLATED, null, Dialect.BIGQUERY, Dialect.CLICKHOUSE, Dialect.DATABRICKS, Dialect.DB2, Dialect.DUCKDB, Dialect.H2, Dialect.MEMSQL, Dialect.ORACLE, Dialect.POSTGRES, Dialect.PRESTO, Dialect.SNOWFLAKE, Dialect.SPARKSQL, Dialect.SQLSERVER, Dialect.SYBASE, Dialect.SYBASEIQ, Dialect.TRINO),
    AND("and", Resolution.PURE, null, Dialect.DEFAULT, Dialect.SPANNER),
    ARRAY_APPEND("array_append", Resolution.UNSUPPORTED, null, Dialect.DUCKDB, Dialect.SNOWFLAKE),
    ARRAY_CONCATENATE("array_concatenate", Resolution.UNSUPPORTED, null, Dialect.DUCKDB, Dialect.SNOWFLAKE),
    ARRAY_CONTAINS("array_contains", Resolution.UNSUPPORTED, null, Dialect.DUCKDB, Dialect.SNOWFLAKE),
    ARRAY_DISTINCT("array_distinct", Resolution.UNSUPPORTED, null, Dialect.DUCKDB, Dialect.SNOWFLAKE),
    ARRAY_DROP("array_drop", Resolution.UNSUPPORTED, null, Dialect.DUCKDB, Dialect.SNOWFLAKE),
    ARRAY_FIRST("array_first", Resolution.UNSUPPORTED, null, Dialect.DUCKDB, Dialect.SNOWFLAKE),
    ARRAY_FLATTEN("array_flatten", Resolution.UNSUPPORTED, null, Dialect.DUCKDB, Dialect.SNOWFLAKE),
    ARRAY_INIT("array_init", Resolution.UNSUPPORTED, null, Dialect.DUCKDB, Dialect.SNOWFLAKE),
    ARRAY_LAST("array_last", Resolution.UNSUPPORTED, null, Dialect.DUCKDB, Dialect.SNOWFLAKE),
    ARRAY_MAX("array_max", Resolution.UNSUPPORTED, null, Dialect.CLICKHOUSE, Dialect.DUCKDB, Dialect.SNOWFLAKE),
    ARRAY_MIN("array_min", Resolution.UNSUPPORTED, null, Dialect.CLICKHOUSE, Dialect.DUCKDB, Dialect.SNOWFLAKE),
    ARRAY_POSITION("array_position", Resolution.UNSUPPORTED, null, Dialect.DUCKDB, Dialect.SNOWFLAKE),
    ARRAY_REVERSE("array_reverse", Resolution.UNSUPPORTED, null, Dialect.DUCKDB, Dialect.SNOWFLAKE),
    ARRAY_SIZE("array_size", Resolution.UNSUPPORTED, null, Dialect.DUCKDB, Dialect.SNOWFLAKE),
    ARRAY_SLICE("array_slice", Resolution.UNSUPPORTED, null, Dialect.DUCKDB, Dialect.SNOWFLAKE),
    ARRAY_SORT("array_sort", Resolution.UNSUPPORTED, null, Dialect.DUCKDB, Dialect.SNOWFLAKE),
    ARRAY_SUM("array_sum", Resolution.UNSUPPORTED, null, Dialect.DUCKDB, Dialect.SNOWFLAKE),
    ARRAY_TAIL("array_tail", Resolution.UNSUPPORTED, null, Dialect.DUCKDB, Dialect.SNOWFLAKE),
    ARRAY_TAKE("array_take", Resolution.UNSUPPORTED, null, Dialect.DUCKDB, Dialect.SNOWFLAKE),
    ARRAY_TO_STRING("array_to_string", Resolution.UNSUPPORTED, null, Dialect.DUCKDB, Dialect.SNOWFLAKE),
    ASCII("ascii", Resolution.PURE, null, Dialect.DEFAULT, Dialect.SPANNER),
    ASIN("asin", Resolution.PURE, null, Dialect.DEFAULT),
    ATAN("atan", Resolution.PURE, null, Dialect.DEFAULT),
    ATAN2("atan2", Resolution.PURE, null, Dialect.DEFAULT, Dialect.MEMSQL, Dialect.SQLSERVER, Dialect.SYBASE),
    AVERAGE("average", Resolution.PURE, null, Dialect.DEFAULT),
    AVERAGE_RANK("averageRank", Resolution.UNSUPPORTED, null, Dialect.DEFAULT),
    BIT_AND("bitAnd", Resolution.PURE, null, Dialect.DUCKDB, Dialect.SNOWFLAKE),
    BIT_NOT("bitNot", Resolution.PURE, null, Dialect.DUCKDB, Dialect.SNOWFLAKE),
    BIT_OR("bitOr", Resolution.PURE, null, Dialect.DUCKDB, Dialect.SNOWFLAKE),
    BIT_SHIFT_LEFT("bitShiftLeft", Resolution.PURE, null, Dialect.DUCKDB, Dialect.SNOWFLAKE),
    BIT_SHIFT_RIGHT("bitShiftRight", Resolution.PURE, null, Dialect.DUCKDB, Dialect.SNOWFLAKE),
    BIT_XOR("bitXor", Resolution.PURE, null, Dialect.DUCKDB, Dialect.SNOWFLAKE),
    BOOLAND("booland", Resolution.UNSUPPORTED, null, Dialect.BIGQUERY, Dialect.DATABRICKS, Dialect.DUCKDB, Dialect.H2, Dialect.MEMSQL, Dialect.ORACLE, Dialect.POSTGRES, Dialect.PRESTO, Dialect.REDSHIFT, Dialect.SNOWFLAKE, Dialect.SPANNER, Dialect.SQLSERVER, Dialect.SYBASE, Dialect.SYBASEIQ, Dialect.TRINO),
    BOOLOR("boolor", Resolution.UNSUPPORTED, null, Dialect.BIGQUERY, Dialect.DATABRICKS, Dialect.DUCKDB, Dialect.H2, Dialect.MEMSQL, Dialect.ORACLE, Dialect.POSTGRES, Dialect.PRESTO, Dialect.REDSHIFT, Dialect.SNOWFLAKE, Dialect.SPANNER, Dialect.SQLSERVER, Dialect.SYBASE, Dialect.SYBASEIQ, Dialect.TRINO),
    CAST("cast", Resolution.PURE, null, Dialect.DEFAULT),
    CAST_BOOLEAN("castBoolean", Resolution.UNSUPPORTED, null, Dialect.DUCKDB),
    CBRT("cbrt", Resolution.PURE, null, Dialect.DEFAULT, Dialect.ORACLE),
    CEILING("ceiling", Resolution.PURE, null, Dialect.DEFAULT, Dialect.ORACLE, Dialect.SNOWFLAKE),
    CHAR("char", Resolution.PURE, null, Dialect.CLICKHOUSE, Dialect.DB2, Dialect.DEFAULT, Dialect.H2, Dialect.MEMSQL, Dialect.SPANNER, Dialect.SPARKSQL, Dialect.SQLSERVER, Dialect.SYBASE, Dialect.SYBASEIQ),
    CHR("chr", Resolution.UNSUPPORTED, null, Dialect.DUCKDB),
    COALESCE("coalesce", Resolution.PURE, null, Dialect.DEFAULT, Dialect.SPANNER),
    CONCAT("concat", Resolution.TRANSLATED, null, Dialect.BIGQUERY, Dialect.CLICKHOUSE, Dialect.DATABRICKS, Dialect.DB2, Dialect.DUCKDB, Dialect.H2, Dialect.MEMSQL, Dialect.ORACLE, Dialect.POSTGRES, Dialect.PRESTO, Dialect.REDSHIFT, Dialect.SNOWFLAKE, Dialect.SPANNER, Dialect.SPARKSQL, Dialect.SQLSERVER, Dialect.SYBASE, Dialect.SYBASEIQ, Dialect.TRINO),
    CONTAINS("contains", Resolution.PURE, null, Dialect.DEFAULT, Dialect.SNOWFLAKE, Dialect.SPANNER, Dialect.SPARKSQL, Dialect.SYBASEIQ),
    CONVERT_DATE("convertDate", Resolution.TRANSLATED, null, Dialect.DATABRICKS, Dialect.DB2, Dialect.DUCKDB, Dialect.H2, Dialect.MEMSQL, Dialect.PRESTO, Dialect.SNOWFLAKE, Dialect.SPARKSQL, Dialect.SYBASE, Dialect.SYBASEIQ, Dialect.TRINO),
    CONVERT_DATE_TIME("convertDateTime", Resolution.TRANSLATED, null, Dialect.DATABRICKS, Dialect.DB2, Dialect.DUCKDB, Dialect.H2, Dialect.PRESTO, Dialect.SNOWFLAKE, Dialect.SPARKSQL, Dialect.SYBASE, Dialect.SYBASEIQ, Dialect.TRINO),
    CONVERT_TIME_ZONE("convertTimeZone", Resolution.TRANSLATED, null, Dialect.H2, Dialect.MEMSQL, Dialect.SNOWFLAKE),
    CONVERT_VARCHAR128("convertVarchar128", Resolution.TRANSLATED, null, Dialect.DATABRICKS, Dialect.DB2, Dialect.DUCKDB, Dialect.H2, Dialect.MEMSQL, Dialect.PRESTO, Dialect.SNOWFLAKE, Dialect.SPARKSQL, Dialect.SYBASE, Dialect.SYBASEIQ, Dialect.TRINO),
    CORR("corr", Resolution.PURE, null, Dialect.DEFAULT),
    COS("cos", Resolution.PURE, null, Dialect.DEFAULT),
    COSH("cosh", Resolution.PURE, null, Dialect.DEFAULT),
    COT("cot", Resolution.PURE, null, Dialect.CLICKHOUSE, Dialect.DEFAULT, Dialect.TRINO),
    COUNT("count", Resolution.PURE, null, Dialect.DEFAULT, Dialect.SPANNER),
    COVAR_POPULATION("covarPopulation", Resolution.PURE, null, Dialect.DEFAULT),
    COVAR_SAMPLE("covarSample", Resolution.PURE, null, Dialect.DEFAULT),
    CUMULATIVE_DISTRIBUTION("cumulativeDistribution", Resolution.PURE, null, Dialect.DEFAULT),
    CURRENT_USER_ID("currentUserId", Resolution.PURE, null, Dialect.DEFAULT, Dialect.ORACLE, Dialect.SNOWFLAKE),
    DATE("date", Resolution.PURE, null, Dialect.CLICKHOUSE, Dialect.DUCKDB, Dialect.SNOWFLAKE),
    DATE_DIFF("dateDiff", Resolution.PURE, null, Dialect.CLICKHOUSE, Dialect.DATABRICKS, Dialect.DUCKDB, Dialect.H2, Dialect.MEMSQL, Dialect.ORACLE, Dialect.POSTGRES, Dialect.PRESTO, Dialect.REDSHIFT, Dialect.SNOWFLAKE, Dialect.SPANNER, Dialect.SPARKSQL, Dialect.SQLSERVER, Dialect.SYBASE, Dialect.SYBASEIQ, Dialect.TRINO),
    DATE_PART("datePart", Resolution.PURE, null, Dialect.BIGQUERY, Dialect.CLICKHOUSE, Dialect.DATABRICKS, Dialect.DB2, Dialect.DUCKDB, Dialect.H2, Dialect.MEMSQL, Dialect.ORACLE, Dialect.POSTGRES, Dialect.PRESTO, Dialect.REDSHIFT, Dialect.SNOWFLAKE, Dialect.SPANNER, Dialect.SPARKSQL, Dialect.SQLSERVER, Dialect.SYBASE, Dialect.SYBASEIQ, Dialect.TRINO),
    DAY_OF_MONTH("dayOfMonth", Resolution.PURE, null, Dialect.BIGQUERY, Dialect.CLICKHOUSE, Dialect.DATABRICKS, Dialect.DUCKDB, Dialect.H2, Dialect.MEMSQL, Dialect.ORACLE, Dialect.POSTGRES, Dialect.REDSHIFT, Dialect.SNOWFLAKE, Dialect.SPANNER, Dialect.SPARKSQL, Dialect.SQLSERVER, Dialect.SYBASE, Dialect.SYBASEIQ, Dialect.TRINO),
    DAY_OF_WEEK("dayOfWeek", Resolution.TRANSLATED, null, Dialect.BIGQUERY, Dialect.CLICKHOUSE, Dialect.DATABRICKS, Dialect.DB2, Dialect.DUCKDB, Dialect.H2, Dialect.MEMSQL, Dialect.ORACLE, Dialect.POSTGRES, Dialect.PRESTO, Dialect.REDSHIFT, Dialect.SNOWFLAKE, Dialect.SPANNER, Dialect.SPARKSQL, Dialect.SQLSERVER, Dialect.SYBASE, Dialect.SYBASEIQ, Dialect.TRINO),
    DAY_OF_WEEK_NUMBER("dayOfWeekNumber", Resolution.TRANSLATED, null, Dialect.BIGQUERY, Dialect.CLICKHOUSE, Dialect.DATABRICKS, Dialect.DB2, Dialect.DUCKDB, Dialect.H2, Dialect.MEMSQL, Dialect.ORACLE, Dialect.POSTGRES, Dialect.PRESTO, Dialect.REDSHIFT, Dialect.SNOWFLAKE, Dialect.SPANNER, Dialect.SPARKSQL, Dialect.SQLSERVER, Dialect.SYBASE, Dialect.SYBASEIQ, Dialect.TRINO),
    DAY_OF_YEAR("dayOfYear", Resolution.PURE, null, Dialect.BIGQUERY, Dialect.CLICKHOUSE, Dialect.DATABRICKS, Dialect.DB2, Dialect.DUCKDB, Dialect.H2, Dialect.MEMSQL, Dialect.ORACLE, Dialect.POSTGRES, Dialect.PRESTO, Dialect.REDSHIFT, Dialect.SNOWFLAKE, Dialect.SPANNER, Dialect.SPARKSQL, Dialect.SQLSERVER, Dialect.SYBASE, Dialect.SYBASEIQ, Dialect.TRINO),
    DECODE_BASE64("decodeBase64", Resolution.PURE, null, Dialect.BIGQUERY, Dialect.CLICKHOUSE, Dialect.DATABRICKS, Dialect.DUCKDB, Dialect.H2, Dialect.MEMSQL, Dialect.ORACLE, Dialect.POSTGRES, Dialect.SNOWFLAKE, Dialect.SPANNER, Dialect.SQLSERVER),
    DENSE_RANK("denseRank", Resolution.PURE, null, Dialect.DEFAULT),
    DISTINCT("distinct", Resolution.PURE, null, Dialect.DEFAULT, Dialect.SPANNER),
    DIVIDE("divide", Resolution.PURE, null, Dialect.DEFAULT),
    DIVIDE_ROUND("divideRound", Resolution.SHIM, Pure.Lite.DIVIDE_ROUND, Dialect.DEFAULT),
    ENCODE_BASE64("encodeBase64", Resolution.PURE, null, Dialect.BIGQUERY, Dialect.CLICKHOUSE, Dialect.DATABRICKS, Dialect.DUCKDB, Dialect.H2, Dialect.MEMSQL, Dialect.ORACLE, Dialect.POSTGRES, Dialect.SNOWFLAKE, Dialect.SPANNER, Dialect.SQLSERVER),
    ENDS_WITH("endsWith", Resolution.PURE, null, Dialect.DEFAULT, Dialect.SNOWFLAKE, Dialect.SPANNER, Dialect.SPARKSQL, Dialect.SYBASEIQ),
    EQUAL("equal", Resolution.PURE, null, Dialect.DEFAULT),
    EXISTS("exists", Resolution.PURE, null, Dialect.DEFAULT),
    EXP("exp", Resolution.PURE, null, Dialect.DEFAULT, Dialect.SPANNER),
    EXTRACT_FROM_SEMI_STRUCTURED("extractFromSemiStructured", Resolution.TRANSLATED, null, Dialect.DATABRICKS, Dialect.DUCKDB, Dialect.H2, Dialect.MEMSQL, Dialect.POSTGRES, Dialect.SNOWFLAKE),
    FIRST("first", Resolution.PURE, null, Dialect.DEFAULT, Dialect.DUCKDB),
    FIRST_DAY_OF_MONTH("firstDayOfMonth", Resolution.PURE, null, Dialect.BIGQUERY, Dialect.CLICKHOUSE, Dialect.DATABRICKS, Dialect.DB2, Dialect.DUCKDB, Dialect.H2, Dialect.MEMSQL, Dialect.ORACLE, Dialect.POSTGRES, Dialect.PRESTO, Dialect.REDSHIFT, Dialect.SNOWFLAKE, Dialect.SPANNER, Dialect.SPARKSQL, Dialect.SQLSERVER, Dialect.SYBASE, Dialect.SYBASEIQ, Dialect.TRINO),
    FIRST_DAY_OF_QUARTER("firstDayOfQuarter", Resolution.PURE, null, Dialect.BIGQUERY, Dialect.CLICKHOUSE, Dialect.DATABRICKS, Dialect.DB2, Dialect.DUCKDB, Dialect.H2, Dialect.MEMSQL, Dialect.ORACLE, Dialect.POSTGRES, Dialect.PRESTO, Dialect.REDSHIFT, Dialect.SNOWFLAKE, Dialect.SPANNER, Dialect.SPARKSQL, Dialect.SQLSERVER, Dialect.SYBASE, Dialect.SYBASEIQ, Dialect.TRINO),
    FIRST_DAY_OF_THIS_MONTH("firstDayOfThisMonth", Resolution.PURE, null, Dialect.BIGQUERY, Dialect.DATABRICKS, Dialect.DB2, Dialect.DUCKDB, Dialect.H2, Dialect.MEMSQL, Dialect.ORACLE, Dialect.POSTGRES, Dialect.PRESTO, Dialect.REDSHIFT, Dialect.SNOWFLAKE, Dialect.SPANNER, Dialect.SPARKSQL, Dialect.SQLSERVER, Dialect.SYBASE, Dialect.SYBASEIQ, Dialect.TRINO),
    FIRST_DAY_OF_THIS_QUARTER("firstDayOfThisQuarter", Resolution.PURE, null, Dialect.BIGQUERY, Dialect.DATABRICKS, Dialect.DB2, Dialect.DUCKDB, Dialect.H2, Dialect.MEMSQL, Dialect.ORACLE, Dialect.POSTGRES, Dialect.PRESTO, Dialect.REDSHIFT, Dialect.SNOWFLAKE, Dialect.SPANNER, Dialect.SPARKSQL, Dialect.SQLSERVER, Dialect.SYBASE, Dialect.SYBASEIQ, Dialect.TRINO),
    FIRST_DAY_OF_THIS_YEAR("firstDayOfThisYear", Resolution.PURE, null, Dialect.BIGQUERY, Dialect.DATABRICKS, Dialect.DB2, Dialect.DUCKDB, Dialect.H2, Dialect.MEMSQL, Dialect.ORACLE, Dialect.POSTGRES, Dialect.PRESTO, Dialect.REDSHIFT, Dialect.SNOWFLAKE, Dialect.SPANNER, Dialect.SPARKSQL, Dialect.SQLSERVER, Dialect.SYBASE, Dialect.SYBASEIQ, Dialect.TRINO),
    FIRST_DAY_OF_WEEK("firstDayOfWeek", Resolution.PURE, null, Dialect.BIGQUERY, Dialect.CLICKHOUSE, Dialect.DATABRICKS, Dialect.DUCKDB, Dialect.H2, Dialect.MEMSQL, Dialect.ORACLE, Dialect.POSTGRES, Dialect.PRESTO, Dialect.REDSHIFT, Dialect.SNOWFLAKE, Dialect.SPANNER, Dialect.SPARKSQL, Dialect.SQLSERVER, Dialect.SYBASE, Dialect.SYBASEIQ, Dialect.TRINO),
    FIRST_DAY_OF_YEAR("firstDayOfYear", Resolution.PURE, null, Dialect.BIGQUERY, Dialect.CLICKHOUSE, Dialect.DATABRICKS, Dialect.DB2, Dialect.DUCKDB, Dialect.H2, Dialect.MEMSQL, Dialect.ORACLE, Dialect.POSTGRES, Dialect.PRESTO, Dialect.REDSHIFT, Dialect.SNOWFLAKE, Dialect.SPANNER, Dialect.SPARKSQL, Dialect.SQLSERVER, Dialect.SYBASE, Dialect.SYBASEIQ, Dialect.TRINO),
    FIRST_HOUR_OF_DAY("firstHourOfDay", Resolution.PURE, null, Dialect.BIGQUERY, Dialect.CLICKHOUSE, Dialect.DATABRICKS, Dialect.DUCKDB, Dialect.H2, Dialect.MEMSQL, Dialect.ORACLE, Dialect.POSTGRES, Dialect.PRESTO, Dialect.REDSHIFT, Dialect.SNOWFLAKE, Dialect.SPANNER, Dialect.SPARKSQL, Dialect.SQLSERVER, Dialect.SYBASEIQ, Dialect.TRINO),
    FIRST_MILLISECOND_OF_SECOND("firstMillisecondOfSecond", Resolution.PURE, null, Dialect.BIGQUERY, Dialect.DATABRICKS, Dialect.DUCKDB, Dialect.H2, Dialect.MEMSQL, Dialect.ORACLE, Dialect.POSTGRES, Dialect.PRESTO, Dialect.REDSHIFT, Dialect.SNOWFLAKE, Dialect.SPANNER, Dialect.SPARKSQL, Dialect.SQLSERVER, Dialect.SYBASEIQ, Dialect.TRINO),
    FIRST_MINUTE_OF_HOUR("firstMinuteOfHour", Resolution.PURE, null, Dialect.BIGQUERY, Dialect.CLICKHOUSE, Dialect.DATABRICKS, Dialect.DUCKDB, Dialect.H2, Dialect.MEMSQL, Dialect.ORACLE, Dialect.POSTGRES, Dialect.PRESTO, Dialect.REDSHIFT, Dialect.SNOWFLAKE, Dialect.SPANNER, Dialect.SPARKSQL, Dialect.SQLSERVER, Dialect.SYBASEIQ, Dialect.TRINO),
    FIRST_SECOND_OF_MINUTE("firstSecondOfMinute", Resolution.PURE, null, Dialect.BIGQUERY, Dialect.CLICKHOUSE, Dialect.DATABRICKS, Dialect.DUCKDB, Dialect.H2, Dialect.MEMSQL, Dialect.ORACLE, Dialect.POSTGRES, Dialect.PRESTO, Dialect.REDSHIFT, Dialect.SNOWFLAKE, Dialect.SPANNER, Dialect.SPARKSQL, Dialect.SQLSERVER, Dialect.SYBASEIQ, Dialect.TRINO),
    FLOOR("floor", Resolution.PURE, null, Dialect.DEFAULT, Dialect.MEMSQL),
    FORMAT_DATE("formatDate", Resolution.PURE, null, Dialect.BIGQUERY, Dialect.CLICKHOUSE, Dialect.DATABRICKS, Dialect.DUCKDB, Dialect.H2, Dialect.MEMSQL, Dialect.ORACLE, Dialect.POSTGRES, Dialect.PRESTO, Dialect.REDSHIFT, Dialect.SNOWFLAKE, Dialect.SPANNER, Dialect.SPARKSQL, Dialect.SQLSERVER, Dialect.SYBASE, Dialect.SYBASEIQ, Dialect.TRINO),
    GENERATE_GUID("generateGuid", Resolution.PURE, null, Dialect.CLICKHOUSE, Dialect.DUCKDB, Dialect.H2, Dialect.MEMSQL, Dialect.POSTGRES, Dialect.SNOWFLAKE, Dialect.SQLSERVER, Dialect.SYBASE, Dialect.SYBASEIQ),
    GREATER_THAN("greaterThan", Resolution.SHIM, Pure.Lite.GREATER_THAN_ANY, Dialect.DEFAULT),
    GREATER_THAN_EQUAL("greaterThanEqual", Resolution.SHIM, Pure.Lite.GREATER_THAN_EQUAL_ANY, Dialect.DEFAULT),
    GREATEST("greatest", Resolution.PURE, null, Dialect.DEFAULT, Dialect.SYBASE, Dialect.SYBASEIQ),
    GROUP("group", Resolution.TRANSLATED, null, Dialect.DEFAULT),
    HASH_AGG("hashAgg", Resolution.UNSUPPORTED, null, Dialect.SNOWFLAKE),
    HASH_CODE("hashCode", Resolution.PURE, null, Dialect.DATABRICKS, Dialect.DUCKDB, Dialect.SNOWFLAKE),
    HOUR("hour", Resolution.PURE, null, Dialect.BIGQUERY, Dialect.CLICKHOUSE, Dialect.COMPOSITE, Dialect.DATABRICKS, Dialect.DB2, Dialect.DUCKDB, Dialect.H2, Dialect.MEMSQL, Dialect.ORACLE, Dialect.POSTGRES, Dialect.PRESTO, Dialect.REDSHIFT, Dialect.SNOWFLAKE, Dialect.SPANNER, Dialect.SPARKSQL, Dialect.SQLSERVER, Dialect.SYBASE, Dialect.SYBASEIQ, Dialect.TRINO),
    IF("if", Resolution.TRANSLATED, null, Dialect.DEFAULT),
    IN("in", Resolution.PURE, null, Dialect.DEFAULT, Dialect.SPANNER),
    INDEX_OF("indexOf", Resolution.TRANSLATED, null, Dialect.BIGQUERY, Dialect.CLICKHOUSE, Dialect.DATABRICKS, Dialect.DUCKDB, Dialect.H2, Dialect.MEMSQL, Dialect.ORACLE, Dialect.POSTGRES, Dialect.PRESTO, Dialect.REDSHIFT, Dialect.SNOWFLAKE, Dialect.SPANNER, Dialect.SPARKSQL, Dialect.SQLSERVER, Dialect.SYBASE, Dialect.SYBASEIQ, Dialect.TRINO),
    IS_ALPHA_NUMERIC("isAlphaNumeric", Resolution.UNSUPPORTED, null, Dialect.DATABRICKS, Dialect.DUCKDB, Dialect.H2, Dialect.SNOWFLAKE, Dialect.SPARKSQL, Dialect.SYBASE, Dialect.SYBASEIQ),
    IS_DISTINCT("isDistinct", Resolution.SHIM, Pure.Lite.IS_DISTINCT, Dialect.DEFAULT),
    IS_EMPTY("isEmpty", Resolution.PURE, null, Dialect.DEFAULT, Dialect.ORACLE, Dialect.SPARKSQL, Dialect.SYBASE, Dialect.SYBASEIQ),
    IS_NOT_EMPTY("isNotEmpty", Resolution.PURE, null, Dialect.DEFAULT, Dialect.ORACLE, Dialect.SPANNER, Dialect.SPARKSQL, Dialect.SYBASE, Dialect.SYBASEIQ),
    IS_NOT_NULL("isNotNull", Resolution.TRANSLATED, null, Dialect.DEFAULT, Dialect.ORACLE, Dialect.SPARKSQL, Dialect.SYBASE, Dialect.SYBASEIQ),
    IS_NULL("isNull", Resolution.TRANSLATED, null, Dialect.DEFAULT, Dialect.ORACLE, Dialect.SPANNER, Dialect.SPARKSQL, Dialect.SYBASE, Dialect.SYBASEIQ),
    IS_NUMERIC("isNumeric", Resolution.SHIM, Pure.Lite.IS_NUMERIC, Dialect.DATABRICKS, Dialect.DUCKDB, Dialect.H2, Dialect.SPARKSQL, Dialect.SYBASE, Dialect.SYBASEIQ),
    JARO_WINKLER_SIMILARITY("jaroWinklerSimilarity", Resolution.PURE, null, Dialect.CLICKHOUSE, Dialect.DUCKDB, Dialect.H2, Dialect.SNOWFLAKE),
    JOIN_STRINGS("joinStrings", Resolution.PURE, null, Dialect.BIGQUERY, Dialect.CLICKHOUSE, Dialect.DATABRICKS, Dialect.DB2, Dialect.DUCKDB, Dialect.H2, Dialect.MEMSQL, Dialect.ORACLE, Dialect.POSTGRES, Dialect.REDSHIFT, Dialect.SNOWFLAKE, Dialect.SPANNER, Dialect.SPARKSQL, Dialect.SYBASE, Dialect.SYBASEIQ),
    KEYS("keys", Resolution.PURE, null, Dialect.DUCKDB, Dialect.SNOWFLAKE),
    LAG("lag", Resolution.PURE, null, Dialect.CLICKHOUSE, Dialect.DEFAULT),
    LAST("last", Resolution.PURE, null, Dialect.DEFAULT, Dialect.DUCKDB),
    LEAD("lead", Resolution.PURE, null, Dialect.DEFAULT),
    LEAST("least", Resolution.PURE, null, Dialect.DEFAULT, Dialect.SYBASE, Dialect.SYBASEIQ),
    LEFT("left", Resolution.PURE, null, Dialect.DB2, Dialect.DEFAULT, Dialect.MEMSQL, Dialect.ORACLE, Dialect.PRESTO, Dialect.SPANNER, Dialect.TRINO),
    LENGTH("length", Resolution.PURE, null, Dialect.BIGQUERY, Dialect.CLICKHOUSE, Dialect.COMPOSITE, Dialect.DATABRICKS, Dialect.DB2, Dialect.DUCKDB, Dialect.H2, Dialect.MEMSQL, Dialect.ORACLE, Dialect.POSTGRES, Dialect.PRESTO, Dialect.REDSHIFT, Dialect.SNOWFLAKE, Dialect.SPANNER, Dialect.SPARKSQL, Dialect.SQLSERVER, Dialect.SYBASE, Dialect.SYBASEIQ, Dialect.TRINO),
    LESS_THAN("lessThan", Resolution.SHIM, Pure.Lite.LESS_THAN_ANY, Dialect.DEFAULT),
    LESS_THAN_EQUAL("lessThanEqual", Resolution.SHIM, Pure.Lite.LESS_THAN_EQUAL_ANY, Dialect.DEFAULT),
    LEVENSHTEIN_DISTANCE("levenshteinDistance", Resolution.PURE, null, Dialect.CLICKHOUSE, Dialect.DUCKDB, Dialect.H2, Dialect.SNOWFLAKE),
    LOG("log", Resolution.PURE, null, Dialect.CLICKHOUSE, Dialect.DEFAULT, Dialect.SPANNER, Dialect.SQLSERVER, Dialect.SYBASE),
    LOG10("log10", Resolution.PURE, null, Dialect.CLICKHOUSE, Dialect.DEFAULT, Dialect.ORACLE, Dialect.POSTGRES, Dialect.REDSHIFT, Dialect.SNOWFLAKE),
    LPAD("lpad", Resolution.PURE, null, Dialect.DEFAULT, Dialect.DUCKDB, Dialect.MEMSQL, Dialect.SQLSERVER, Dialect.SYBASE, Dialect.TRINO),
    LTRIM("ltrim", Resolution.PURE, null, Dialect.DEFAULT),
    MAP_CONCATENATE("mapConcatenate", Resolution.UNSUPPORTED, null, Dialect.DUCKDB, Dialect.SNOWFLAKE),
    MATCHES("matches", Resolution.PURE, null, Dialect.CLICKHOUSE, Dialect.DATABRICKS, Dialect.DUCKDB, Dialect.H2, Dialect.MEMSQL, Dialect.ORACLE, Dialect.POSTGRES, Dialect.PRESTO, Dialect.SNOWFLAKE, Dialect.SPARKSQL, Dialect.SYBASE, Dialect.SYBASEIQ, Dialect.TRINO),
    MAX("max", Resolution.PURE, null, Dialect.DEFAULT),
    MAX_BY("maxBy", Resolution.PURE, null, Dialect.CLICKHOUSE, Dialect.DATABRICKS, Dialect.DUCKDB, Dialect.SNOWFLAKE),
    MD5("md5", Resolution.TRANSLATED, null, Dialect.CLICKHOUSE, Dialect.DB2, Dialect.DEFAULT, Dialect.DUCKDB, Dialect.H2, Dialect.SPARKSQL, Dialect.SQLSERVER, Dialect.SYBASE, Dialect.SYBASEIQ, Dialect.TRINO),
    MEDIAN("median", Resolution.PURE, null, Dialect.DEFAULT),
    MIN("min", Resolution.PURE, null, Dialect.DEFAULT),
    MIN_BY("minBy", Resolution.PURE, null, Dialect.CLICKHOUSE, Dialect.DATABRICKS, Dialect.DUCKDB, Dialect.SNOWFLAKE),
    MINUS("minus", Resolution.PURE, null, Dialect.DEFAULT),
    MINUTE("minute", Resolution.PURE, null, Dialect.BIGQUERY, Dialect.CLICKHOUSE, Dialect.DATABRICKS, Dialect.DB2, Dialect.DUCKDB, Dialect.H2, Dialect.MEMSQL, Dialect.ORACLE, Dialect.POSTGRES, Dialect.PRESTO, Dialect.REDSHIFT, Dialect.SNOWFLAKE, Dialect.SPANNER, Dialect.SPARKSQL, Dialect.SQLSERVER, Dialect.SYBASE, Dialect.SYBASEIQ, Dialect.TRINO),
    MOD("mod", Resolution.PURE, null, Dialect.DEFAULT, Dialect.DUCKDB, Dialect.SQLSERVER, Dialect.SYBASE),
    MODE("mode", Resolution.PURE, null, Dialect.CLICKHOUSE, Dialect.DEFAULT),
    MONTH("month", Resolution.PURE, null, Dialect.BIGQUERY, Dialect.CLICKHOUSE, Dialect.DATABRICKS, Dialect.DB2, Dialect.DUCKDB, Dialect.H2, Dialect.MEMSQL, Dialect.ORACLE, Dialect.POSTGRES, Dialect.PRESTO, Dialect.REDSHIFT, Dialect.SNOWFLAKE, Dialect.SPANNER, Dialect.SPARKSQL, Dialect.SQLSERVER, Dialect.SYBASE, Dialect.SYBASEIQ, Dialect.TRINO),
    MONTH_NAME("monthName", Resolution.UNSUPPORTED, null, Dialect.BIGQUERY, Dialect.CLICKHOUSE, Dialect.DATABRICKS, Dialect.DB2, Dialect.DUCKDB, Dialect.H2, Dialect.MEMSQL, Dialect.ORACLE, Dialect.POSTGRES, Dialect.PRESTO, Dialect.REDSHIFT, Dialect.SNOWFLAKE, Dialect.SQLSERVER, Dialect.SYBASE, Dialect.SYBASEIQ, Dialect.TRINO),
    MONTH_NUMBER("monthNumber", Resolution.PURE, null, Dialect.BIGQUERY, Dialect.CLICKHOUSE, Dialect.DATABRICKS, Dialect.DB2, Dialect.DUCKDB, Dialect.H2, Dialect.MEMSQL, Dialect.ORACLE, Dialect.POSTGRES, Dialect.PRESTO, Dialect.REDSHIFT, Dialect.SNOWFLAKE, Dialect.SPANNER, Dialect.SPARKSQL, Dialect.SQLSERVER, Dialect.SYBASE, Dialect.SYBASEIQ, Dialect.TRINO),
    MOST_RECENT_DAY_OF_WEEK("mostRecentDayOfWeek", Resolution.PURE, null, Dialect.DATABRICKS, Dialect.DB2, Dialect.DUCKDB, Dialect.H2, Dialect.MEMSQL, Dialect.ORACLE, Dialect.POSTGRES, Dialect.PRESTO, Dialect.SNOWFLAKE, Dialect.SPARKSQL, Dialect.SYBASE, Dialect.SYBASEIQ, Dialect.TRINO),
    NOT_EQUAL("notEqual", Resolution.UNSUPPORTED, null, Dialect.DEFAULT),
    NOT_EQUAL_ANSI("notEqualAnsi", Resolution.SHIM, Pure.Lite.NOT_EQUAL_ANSI, Dialect.DEFAULT),
    NOW("now", Resolution.PURE, null, Dialect.BIGQUERY, Dialect.DATABRICKS, Dialect.DB2, Dialect.DUCKDB, Dialect.H2, Dialect.MEMSQL, Dialect.ORACLE, Dialect.POSTGRES, Dialect.PRESTO, Dialect.REDSHIFT, Dialect.SNOWFLAKE, Dialect.SPANNER, Dialect.SPARKSQL, Dialect.SQLSERVER, Dialect.SYBASE, Dialect.SYBASEIQ, Dialect.TRINO),
    NTH("nth", Resolution.PURE, null, Dialect.DEFAULT),
    NTILE("ntile", Resolution.PURE, null, Dialect.DEFAULT),
    OBJECT_REFERENCE_IN("objectReferenceIn", Resolution.PURE, null, Dialect.DEFAULT),
    OR("or", Resolution.PURE, null, Dialect.DEFAULT, Dialect.SPANNER),
    PARSE_BOOLEAN("parseBoolean", Resolution.PURE, null, Dialect.CLICKHOUSE, Dialect.SNOWFLAKE),
    PARSE_DATE("parseDate", Resolution.PURE, null, Dialect.DATABRICKS, Dialect.DUCKDB, Dialect.H2, Dialect.PRESTO, Dialect.SNOWFLAKE, Dialect.SPARKSQL, Dialect.SYBASE, Dialect.SYBASEIQ, Dialect.TRINO),
    PARSE_DECIMAL("parseDecimal", Resolution.PURE, null, Dialect.BIGQUERY, Dialect.CLICKHOUSE, Dialect.DATABRICKS, Dialect.DUCKDB, Dialect.H2, Dialect.MEMSQL, Dialect.ORACLE, Dialect.POSTGRES, Dialect.REDSHIFT, Dialect.SNOWFLAKE, Dialect.SPANNER, Dialect.SPARKSQL, Dialect.SYBASE, Dialect.SYBASEIQ),
    PARSE_FLOAT("parseFloat", Resolution.PURE, null, Dialect.BIGQUERY, Dialect.CLICKHOUSE, Dialect.COMPOSITE, Dialect.DATABRICKS, Dialect.DB2, Dialect.DUCKDB, Dialect.H2, Dialect.MEMSQL, Dialect.ORACLE, Dialect.POSTGRES, Dialect.PRESTO, Dialect.REDSHIFT, Dialect.SNOWFLAKE, Dialect.SPANNER, Dialect.SPARKSQL, Dialect.SQLSERVER, Dialect.SYBASE, Dialect.SYBASEIQ, Dialect.TRINO),
    PARSE_INTEGER("parseInteger", Resolution.PURE, null, Dialect.BIGQUERY, Dialect.CLICKHOUSE, Dialect.COMPOSITE, Dialect.DATABRICKS, Dialect.DB2, Dialect.DUCKDB, Dialect.H2, Dialect.MEMSQL, Dialect.ORACLE, Dialect.POSTGRES, Dialect.PRESTO, Dialect.REDSHIFT, Dialect.SNOWFLAKE, Dialect.SPANNER, Dialect.SPARKSQL, Dialect.SQLSERVER, Dialect.SYBASE, Dialect.SYBASEIQ, Dialect.TRINO),
    PARSE_JSON("parseJson", Resolution.UNSUPPORTED, null, Dialect.DATABRICKS, Dialect.DUCKDB, Dialect.H2, Dialect.ORACLE, Dialect.POSTGRES, Dialect.SNOWFLAKE),
    PERCENT_RANK("percentRank", Resolution.PURE, null, Dialect.DEFAULT),
    PERCENTILE("percentile", Resolution.PURE, null, Dialect.CLICKHOUSE, Dialect.DEFAULT),
    PLUS("plus", Resolution.PURE, null, Dialect.DEFAULT, Dialect.SPANNER),
    POSITION("position", Resolution.TRANSLATED, null, Dialect.BIGQUERY, Dialect.COMPOSITE, Dialect.DATABRICKS, Dialect.DB2, Dialect.DUCKDB, Dialect.H2, Dialect.MEMSQL, Dialect.ORACLE, Dialect.POSTGRES, Dialect.PRESTO, Dialect.REDSHIFT, Dialect.SNOWFLAKE, Dialect.SPANNER, Dialect.SPARKSQL, Dialect.SQLSERVER, Dialect.SYBASE, Dialect.SYBASEIQ, Dialect.TRINO),
    POW("pow", Resolution.PURE, null, Dialect.DEFAULT, Dialect.SPANNER),
    PREVIOUS_DAY_OF_WEEK("previousDayOfWeek", Resolution.PURE, null, Dialect.DATABRICKS, Dialect.DB2, Dialect.DUCKDB, Dialect.H2, Dialect.MEMSQL, Dialect.ORACLE, Dialect.POSTGRES, Dialect.PRESTO, Dialect.SNOWFLAKE, Dialect.SPARKSQL, Dialect.SYBASE, Dialect.SYBASEIQ, Dialect.TRINO),
    QUARTER("quarter", Resolution.PURE, null, Dialect.DATABRICKS, Dialect.DB2, Dialect.DUCKDB, Dialect.H2, Dialect.MEMSQL, Dialect.ORACLE, Dialect.POSTGRES, Dialect.PRESTO, Dialect.SNOWFLAKE, Dialect.SPANNER, Dialect.SPARKSQL, Dialect.SQLSERVER, Dialect.SYBASE, Dialect.SYBASEIQ, Dialect.TRINO),
    QUARTER_NUMBER("quarterNumber", Resolution.PURE, null, Dialect.BIGQUERY, Dialect.DATABRICKS, Dialect.DB2, Dialect.DUCKDB, Dialect.H2, Dialect.MEMSQL, Dialect.ORACLE, Dialect.POSTGRES, Dialect.PRESTO, Dialect.REDSHIFT, Dialect.SNOWFLAKE, Dialect.SPANNER, Dialect.SPARKSQL, Dialect.SQLSERVER, Dialect.SYBASE, Dialect.SYBASEIQ, Dialect.TRINO),
    RANGE("range", Resolution.PURE, null, Dialect.CLICKHOUSE, Dialect.DUCKDB, Dialect.SNOWFLAKE),
    RANK("rank", Resolution.PURE, null, Dialect.DEFAULT),
    REGEXP_COUNT("regexpCount", Resolution.PURE, null, Dialect.DUCKDB, Dialect.SNOWFLAKE),
    REGEXP_EXTRACT("regexpExtract", Resolution.PURE, null, Dialect.DUCKDB, Dialect.SNOWFLAKE),
    REGEXP_INDEX_OF("regexpIndexOf", Resolution.PURE, null, Dialect.DUCKDB, Dialect.SNOWFLAKE),
    REGEXP_LIKE("regexpLike", Resolution.PURE, null, Dialect.DUCKDB, Dialect.SNOWFLAKE),
    REGEXP_REPLACE("regexpReplace", Resolution.PURE, null, Dialect.DUCKDB, Dialect.SNOWFLAKE),
    REM("rem", Resolution.PURE, null, Dialect.DEFAULT, Dialect.MEMSQL, Dialect.SYBASE),
    REPEAT_STRING("repeatString", Resolution.PURE, null, Dialect.DEFAULT, Dialect.MEMSQL, Dialect.SQLSERVER, Dialect.SYBASE, Dialect.TRINO),
    REPLACE("replace", Resolution.PURE, null, Dialect.DEFAULT),
    REVERSE_STRING("reverseString", Resolution.PURE, null, Dialect.DEFAULT, Dialect.DUCKDB, Dialect.H2),
    RIGHT("right", Resolution.PURE, null, Dialect.DB2, Dialect.DEFAULT, Dialect.MEMSQL, Dialect.ORACLE, Dialect.PRESTO, Dialect.SPANNER, Dialect.TRINO),
    ROUND("round", Resolution.PURE, null, Dialect.BIGQUERY, Dialect.CLICKHOUSE, Dialect.COMPOSITE, Dialect.DATABRICKS, Dialect.DB2, Dialect.DEFAULT, Dialect.DUCKDB, Dialect.H2, Dialect.MEMSQL, Dialect.ORACLE, Dialect.POSTGRES, Dialect.PRESTO, Dialect.REDSHIFT, Dialect.SNOWFLAKE, Dialect.SPANNER, Dialect.SPARKSQL, Dialect.SQLSERVER, Dialect.SYBASE, Dialect.SYBASEIQ, Dialect.TRINO),
    ROW_NUMBER("rowNumber", Resolution.PURE, null, Dialect.DEFAULT),
    RPAD("rpad", Resolution.PURE, null, Dialect.DEFAULT, Dialect.DUCKDB, Dialect.MEMSQL, Dialect.SQLSERVER, Dialect.SYBASE, Dialect.TRINO),
    RTRIM("rtrim", Resolution.PURE, null, Dialect.DEFAULT, Dialect.MEMSQL),
    SECOND("second", Resolution.PURE, null, Dialect.BIGQUERY, Dialect.CLICKHOUSE, Dialect.DATABRICKS, Dialect.DB2, Dialect.DUCKDB, Dialect.H2, Dialect.MEMSQL, Dialect.ORACLE, Dialect.POSTGRES, Dialect.PRESTO, Dialect.REDSHIFT, Dialect.SNOWFLAKE, Dialect.SPANNER, Dialect.SPARKSQL, Dialect.SQLSERVER, Dialect.SYBASE, Dialect.SYBASEIQ, Dialect.TRINO),
    SHA1("sha1", Resolution.TRANSLATED, null, Dialect.CLICKHOUSE, Dialect.DB2, Dialect.DEFAULT, Dialect.DUCKDB, Dialect.H2, Dialect.ORACLE, Dialect.POSTGRES, Dialect.SPARKSQL, Dialect.SQLSERVER, Dialect.SYBASE, Dialect.SYBASEIQ, Dialect.TRINO),
    SHA256("sha256", Resolution.TRANSLATED, null, Dialect.CLICKHOUSE, Dialect.DATABRICKS, Dialect.DB2, Dialect.DEFAULT, Dialect.DUCKDB, Dialect.H2, Dialect.MEMSQL, Dialect.ORACLE, Dialect.POSTGRES, Dialect.REDSHIFT, Dialect.SNOWFLAKE, Dialect.SPARKSQL, Dialect.SQLSERVER, Dialect.SYBASE, Dialect.SYBASEIQ, Dialect.TRINO),
    SIGN("sign", Resolution.PURE, null, Dialect.DEFAULT),
    SIN("sin", Resolution.PURE, null, Dialect.DEFAULT),
    SINH("sinh", Resolution.PURE, null, Dialect.DEFAULT),
    SIZE("size", Resolution.PURE, null, Dialect.DEFAULT, Dialect.SPANNER),
    SPLIT_PART("splitPart", Resolution.TRANSLATED, null, Dialect.CLICKHOUSE, Dialect.DEFAULT, Dialect.DUCKDB, Dialect.H2, Dialect.SNOWFLAKE, Dialect.SPANNER, Dialect.SQLSERVER),
    SQL_FALSE("sqlFalse", Resolution.PURE, null, Dialect.DATABRICKS, Dialect.DEFAULT, Dialect.SPANNER),
    SQL_NULL("sqlNull", Resolution.PURE, null, Dialect.DEFAULT, Dialect.SPANNER),
    SQL_TRUE("sqlTrue", Resolution.PURE, null, Dialect.DATABRICKS, Dialect.DEFAULT, Dialect.SPANNER),
    SQRT("sqrt", Resolution.PURE, null, Dialect.DEFAULT),
    STARTS_WITH("startsWith", Resolution.PURE, null, Dialect.DEFAULT, Dialect.SNOWFLAKE, Dialect.SPANNER, Dialect.SPARKSQL, Dialect.SYBASEIQ),
    STD_DEV_POPULATION("stdDevPopulation", Resolution.PURE, null, Dialect.BIGQUERY, Dialect.CLICKHOUSE, Dialect.DATABRICKS, Dialect.DB2, Dialect.DUCKDB, Dialect.H2, Dialect.MEMSQL, Dialect.ORACLE, Dialect.POSTGRES, Dialect.PRESTO, Dialect.REDSHIFT, Dialect.SNOWFLAKE, Dialect.SPANNER, Dialect.SPARKSQL, Dialect.SQLSERVER, Dialect.SYBASE, Dialect.SYBASEIQ, Dialect.TRINO),
    STD_DEV_SAMPLE("stdDevSample", Resolution.PURE, null, Dialect.BIGQUERY, Dialect.CLICKHOUSE, Dialect.DATABRICKS, Dialect.DB2, Dialect.DUCKDB, Dialect.H2, Dialect.MEMSQL, Dialect.ORACLE, Dialect.POSTGRES, Dialect.PRESTO, Dialect.REDSHIFT, Dialect.SNOWFLAKE, Dialect.SPANNER, Dialect.SPARKSQL, Dialect.SQLSERVER, Dialect.SYBASE, Dialect.SYBASEIQ, Dialect.TRINO),
    SUB("sub", Resolution.SHIM, Pure.Lite.SUB, Dialect.DEFAULT),
    SUBSTRING("substring", Resolution.TRANSLATED, null, Dialect.BIGQUERY, Dialect.CLICKHOUSE, Dialect.COMPOSITE, Dialect.DATABRICKS, Dialect.DB2, Dialect.DUCKDB, Dialect.H2, Dialect.MEMSQL, Dialect.ORACLE, Dialect.POSTGRES, Dialect.PRESTO, Dialect.REDSHIFT, Dialect.SNOWFLAKE, Dialect.SPANNER, Dialect.SPARKSQL, Dialect.SQLSERVER, Dialect.SYBASE, Dialect.SYBASEIQ, Dialect.TRINO),
    SUM("sum", Resolution.PURE, null, Dialect.DEFAULT),
    TAN("tan", Resolution.PURE, null, Dialect.DEFAULT),
    TANH("tanh", Resolution.PURE, null, Dialect.CLICKHOUSE, Dialect.DEFAULT),
    TIME_BUCKET("timeBucket", Resolution.PURE, null, Dialect.CLICKHOUSE, Dialect.DUCKDB, Dialect.SNOWFLAKE),
    TIMES("times", Resolution.PURE, null, Dialect.DEFAULT),
    TO_DECIMAL("toDecimal", Resolution.PURE, null, Dialect.CLICKHOUSE, Dialect.DATABRICKS, Dialect.DUCKDB, Dialect.H2, Dialect.ORACLE, Dialect.POSTGRES, Dialect.PRESTO, Dialect.REDSHIFT, Dialect.SNOWFLAKE, Dialect.SPARKSQL, Dialect.SQLSERVER, Dialect.SYBASE, Dialect.SYBASEIQ),
    TO_FLOAT("toFloat", Resolution.PURE, null, Dialect.BIGQUERY, Dialect.CLICKHOUSE, Dialect.DATABRICKS, Dialect.DUCKDB, Dialect.H2, Dialect.ORACLE, Dialect.POSTGRES, Dialect.PRESTO, Dialect.REDSHIFT, Dialect.SNOWFLAKE, Dialect.SPARKSQL, Dialect.SQLSERVER, Dialect.SYBASE, Dialect.SYBASEIQ),
    TO_JSON("toJson", Resolution.UNSUPPORTED, null, Dialect.DATABRICKS, Dialect.DUCKDB, Dialect.POSTGRES, Dialect.SNOWFLAKE),
    TO_LOWER("toLower", Resolution.PURE, null, Dialect.DEFAULT),
    TO_ONE("toOne", Resolution.PURE, null, Dialect.DEFAULT, Dialect.SPANNER),
    TO_STRING("toString", Resolution.PURE, null, Dialect.BIGQUERY, Dialect.CLICKHOUSE, Dialect.DATABRICKS, Dialect.DB2, Dialect.DUCKDB, Dialect.H2, Dialect.MEMSQL, Dialect.ORACLE, Dialect.POSTGRES, Dialect.PRESTO, Dialect.REDSHIFT, Dialect.SNOWFLAKE, Dialect.SPANNER, Dialect.SPARKSQL, Dialect.SQLSERVER, Dialect.SYBASE, Dialect.SYBASEIQ, Dialect.TRINO),
    TO_TIMESTAMP("toTimestamp", Resolution.TRANSLATED, null, Dialect.DUCKDB, Dialect.H2, Dialect.MEMSQL, Dialect.ORACLE, Dialect.POSTGRES, Dialect.PRESTO, Dialect.SNOWFLAKE, Dialect.SPARKSQL, Dialect.SYBASE, Dialect.SYBASEIQ, Dialect.TRINO),
    TO_UPPER("toUpper", Resolution.PURE, null, Dialect.DEFAULT),
    TO_VARIANT("toVariant", Resolution.PURE, null, Dialect.DUCKDB, Dialect.POSTGRES, Dialect.SNOWFLAKE),
    TO_VARIANT_LIST("toVariantList", Resolution.UNSUPPORTED, null, Dialect.CLICKHOUSE, Dialect.DUCKDB, Dialect.POSTGRES, Dialect.SNOWFLAKE),
    TO_VARIANT_OBJECT("toVariantObject", Resolution.UNSUPPORTED, null, Dialect.DUCKDB, Dialect.POSTGRES, Dialect.SNOWFLAKE),
    TODAY("today", Resolution.PURE, null, Dialect.BIGQUERY, Dialect.CLICKHOUSE, Dialect.DATABRICKS, Dialect.DB2, Dialect.DUCKDB, Dialect.H2, Dialect.MEMSQL, Dialect.ORACLE, Dialect.POSTGRES, Dialect.PRESTO, Dialect.REDSHIFT, Dialect.SNOWFLAKE, Dialect.SPANNER, Dialect.SPARKSQL, Dialect.SQLSERVER, Dialect.SYBASE, Dialect.SYBASEIQ, Dialect.TRINO),
    TRIM("trim", Resolution.PURE, null, Dialect.DEFAULT, Dialect.SYBASE),
    VALUES("values", Resolution.PURE, null, Dialect.DUCKDB, Dialect.SNOWFLAKE),
    VARIANCE("variance", Resolution.PURE, null, Dialect.CLICKHOUSE, Dialect.DEFAULT, Dialect.DUCKDB),
    VARIANCE_POPULATION("variancePopulation", Resolution.PURE, null, Dialect.CLICKHOUSE, Dialect.DEFAULT, Dialect.DUCKDB, Dialect.SQLSERVER),
    VARIANCE_SAMPLE("varianceSample", Resolution.PURE, null, Dialect.CLICKHOUSE, Dialect.DEFAULT, Dialect.DUCKDB, Dialect.SQLSERVER),
    VARIANT_TO("variantTo", Resolution.UNSUPPORTED, null, Dialect.DEFAULT, Dialect.DUCKDB, Dialect.POSTGRES),
    WEEK_OF_YEAR("weekOfYear", Resolution.PURE, null, Dialect.BIGQUERY, Dialect.CLICKHOUSE, Dialect.DATABRICKS, Dialect.DB2, Dialect.DUCKDB, Dialect.H2, Dialect.MEMSQL, Dialect.ORACLE, Dialect.POSTGRES, Dialect.PRESTO, Dialect.REDSHIFT, Dialect.SNOWFLAKE, Dialect.SPANNER, Dialect.SPARKSQL, Dialect.SQLSERVER, Dialect.SYBASE, Dialect.SYBASEIQ, Dialect.TRINO),
    YEAR("year", Resolution.PURE, null, Dialect.BIGQUERY, Dialect.CLICKHOUSE, Dialect.DATABRICKS, Dialect.DB2, Dialect.DUCKDB, Dialect.H2, Dialect.MEMSQL, Dialect.ORACLE, Dialect.POSTGRES, Dialect.PRESTO, Dialect.REDSHIFT, Dialect.SNOWFLAKE, Dialect.SPANNER, Dialect.SPARKSQL, Dialect.SQLSERVER, Dialect.SYBASE, Dialect.SYBASEIQ, Dialect.TRINO);

    /** How the platform resolves an engine dynafunction. */
    public enum Resolution { PURE, SHIM, TRANSLATED, UNSUPPORTED }

    /** The engine dialect extension files that register a name. */
    public enum Dialect { BIGQUERY, CLICKHOUSE, COMPOSITE, DATABRICKS, DB2, DEFAULT, DUCKDB, H2, MEMSQL, ORACLE, POSTGRES, PRESTO, REDSHIFT, SNOWFLAKE, SPANNER, SPARKSQL, SQLSERVER, SYBASE, SYBASEIQ, TRINO }

    private final String name;
    private final Resolution resolution;
    private final @com.legend.Nullable String liteFqn;
    private final EnumSet<Dialect> dialects;

    DynaFn(String name, Resolution resolution, @com.legend.Nullable String liteFqn, Dialect first, Dialect... rest) {
        this.name = name;
        this.resolution = resolution;
        this.liteFqn = liteFqn;
        this.dialects = EnumSet.of(first, rest);
    }

    /** The engine's spelling of the operator. */
    public String dynaName() {
        return name;
    }

    public Resolution resolution() {
        return resolution;
    }

    /** The dialects whose rendering registry declares this name. */
    public java.util.Set<Dialect> dialects() {
        return java.util.Collections.unmodifiableSet(dialects);
    }

    /** The {@link Pure.Lite} identity a SHIM resolves to (the constant itself,
     *  spelled in the member — the compiler holds it, never a lookup). */
    public String liteFqn() {
        return java.util.Objects.requireNonNull(liteFqn, name + " is not a SHIM");
    }

    private static final Map<String, DynaFn> BY_NAME;

    static {
        Map<String, DynaFn> m = new java.util.HashMap<>();
        for (DynaFn d : values()) {
            m.put(d.name, d);
        }
        BY_NAME = Map.copyOf(m);
    }

    /** The registry entry for an engine operator name, or empty when the engine
     *  registers no such dynafunction (the name is then a plain Pure function
     *  the mapping expression calls, resolved like any other). */
    public static Optional<DynaFn> of(String dynaName) {
        return Optional.ofNullable(BY_NAME.get(dynaName));
    }

    /** Every member of one resolution kind. */
    public static List<DynaFn> withResolution(Resolution r) {
        return java.util.Arrays.stream(values()).filter(d -> d.resolution == r).toList();
    }
}
