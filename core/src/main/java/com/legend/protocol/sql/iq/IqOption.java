// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend.protocol.sql.iq;

import java.util.List;

/**
 * THE CLOSED REGISTRY of the settings an {@code #IQ{...}#} island is read under (docs/IQ_SQL_STUDY_2026_10_13.md §9.17;
 * IQ island, 2026-10-14, the options framework): every database property, connection property and option the island
 * reads or pins, each by its exact name in IQ's own spelling -- a database property as {@code DB_PROPERTY} names it
 * ({@code CaseSensitive}), a connection property as {@code CONNECTION_PROPERTY} does ({@code CommProtocol}), an option as
 * {@code SET OPTION} and {@code SYSOPTIONS} do ({@code date_order}) -- with its level, its tier (the research's: 1
 * modelled in every value, 2 modelled when a query needs it, 3 pinned to its default), the kind of value it takes, its
 * default under each engine base (IQ 16 and SQL Anywhere 17; null where the base has no such setting), the value a TDS
 * login and IQ's ODBC driver give it, and how much of it is built ({@link Status}).
 *
 * <p>Names match without regard to case, as IQ matches them; values are IQ's spellings. The vector of these settings an
 * island is read under is {@link IqSettings}; the five profile names are presets of it ({@link IqProfile}).
 */
public enum IqOption {

    // ------------------------------------------------------------------ database properties (fixed at creation)

    CASE_SENSITIVE("CaseSensitive", Level.DATABASE, 1, Kind.ON_OFF, "On", "Off", null, null, Status.SOME_VALUES),
    BLANK_PADDING("BlankPadding", Level.DATABASE, 1, Kind.ON_OFF, "On", "Off", null, null, Status.SOME_VALUES),
    COLLATION("Collation", Level.DATABASE, 1, Kind.TEXT, "ISO_BINENG", "UTF8BIN", null, null, Status.SOME_VALUES),
    CHAR_SET("CharSet", Level.DATABASE, 1, Kind.TEXT, "ISO_8859-1:1987", "UTF-8", null, null, Status.SOME_VALUES),
    ACCENT_SENSITIVE("AccentSensitive", Level.DATABASE, 1, Kind.ON_OFF, "Off", "Off", null, null, Status.SOME_VALUES),

    // ------------------------------------------------------------------ the connection

    COMM_PROTOCOL("CommProtocol", Level.CONNECTION, 1, Kind.TEXT, "CmdSeq", "CmdSeq", "TDS", null, Status.IMPLEMENTED),

    // ------------------------------------------------------------------ Tier 1: every value modelled or refused by name

    ANSINULL("ansinull", Level.OPTION, 1, Kind.ON_OFF, "On", "On", "Off", null, Status.IMPLEMENTED),
    QUOTED_IDENTIFIER("quoted_identifier", Level.OPTION, 1, Kind.ON_OFF, "On", "On", "Off", null, Status.IMPLEMENTED),
    ESCAPE_CHARACTER("escape_character", Level.OPTION, 1, Kind.ON_OFF, "On", "On", "Off", null, Status.IMPLEMENTED),
    DATE_ORDER("date_order", Level.OPTION, 1, Kind.DATE_ORDER, "YMD", "YMD", "MDY", "YMD", Status.IMPLEMENTED),
    NEAREST_CENTURY("nearest_century", Level.OPTION, 1, Kind.integer(0, 100), "50", "50", null, null,
            Status.IMPLEMENTED),
    DATE_FORMAT("date_format", Level.OPTION, 1, Kind.TEXT, "YYYY-MM-DD", "YYYY-MM-DD", "YYYY-MM-DD", "yyyy-mm-dd",
            Status.SOME_VALUES),
    TIME_FORMAT("time_format", Level.OPTION, 1, Kind.TEXT, "HH:NN:SS.SSS", "HH:NN:SS.SSS", "HH:NN:SS.SSS", "hh:nn:ss",
            Status.SOME_VALUES),
    TIMESTAMP_FORMAT("timestamp_format", Level.OPTION, 1, Kind.TEXT, "YYYY-MM-DD HH:NN:SS.SSS",
            "YYYY-MM-DD HH:NN:SS.SSS", "YYYY-MM-DD HH:NN:SS.SSS", "yyyy-mm-dd hh:nn:ss.ssssss", Status.SOME_VALUES),
    PRECISION("precision", Level.PUBLIC_ONLY, 1, Kind.integer(1, 127), "126", "30", null, null, Status.SOME_VALUES),
    SCALE("scale", Level.PUBLIC_ONLY, 1, Kind.integer(0, 127), "38", "6", null, null, Status.SOME_VALUES),
    FIRST_DAY_OF_WEEK("first_day_of_week", Level.OPTION, 1, Kind.integer(1, 7), "7", "7", null, null,
            Status.IMPLEMENTED),
    DATE_FIRST_DAY_OF_WEEK("date_first_day_of_week", Level.OPTION, 1, Kind.integer(0, 6), "0", null, null, null,
            Status.PROVISIONAL, "D-T9"),
    DIVIDE_BY_ZERO_ERROR("divide_by_zero_error", Level.OPTION, 1, Kind.ON_OFF, "On", "On", null, null,
            Status.SOME_VALUES),
    CONVERSION_ERROR("conversion_error", Level.OPTION, 1, Kind.ON_OFF, "On", "On", null, null, Status.PROVISIONAL,
            "D-C1"),
    STRING_RTRUNCATION("string_rtruncation", Level.OPTION, 1, Kind.ON_OFF, "On", "On", null, null, Status.PROVISIONAL,
            "D-C2"),
    ANSI_SUBSTRING("ansi_substring", Level.OPTION, 1, Kind.ON_OFF, "On", "On", null, null, Status.SOME_VALUES),
    MIN_NUMERIC_DIVISION_SCALE("min_numeric_division_scale", Level.OPTION, 1, Kind.integer(0, 125), "6", null, null,
            null, Status.PROVISIONAL, "D-N2"),
    /** Not an answer option in the research's tiers (it names host variables), but read: under it {@code @name} is a
     *  placeholder a client binds (review 6's M1). */
    TSQL_VARIABLES("tsql_variables", Level.OPTION, 1, Kind.ON_OFF, "Off", "Off", "On", null, Status.IMPLEMENTED),

    // ------------------------------------------------------------------ Tier 2: the default, other values when built

    TIMESTAMP_WITH_TIME_ZONE_FORMAT("timestamp_with_time_zone_format", Level.OPTION, 2, Kind.TEXT, null,
            "YYYY-MM-DD HH:NN:SS.SSS+HH:NN", null, "yyyy-mm-dd hh:nn:ss.ssssss +hh:nn", Status.IGNORED),
    RETURN_DATE_TIME_AS_STRING("return_date_time_as_string", Level.OPTION, 2, Kind.ON_OFF, "Off", "Off", null, null,
            Status.NOT_YET),
    TDS_EMPTY_STRING_IS_NULL("tds_empty_string_is_null", Level.OPTION, 2, Kind.ON_OFF, "Off", "Off", null, null,
            Status.NOT_YET),
    ROW_COUNT("row_count", Level.OPTION, 2, Kind.integer(0, Integer.MAX_VALUE), "0", null, null, null,
            Status.PROVISIONAL, "D-Q3"),
    ROUND_TO_EVEN("round_to_even", Level.PUBLIC_ONLY, 2, Kind.ON_OFF, "Off", null, null, null, Status.NOT_YET),
    CONVERSION_MODE("conversion_mode", Level.OPTION, 2, Kind.integer(0, 2), "0", null, null, null, Status.NOT_YET),
    ASE_FUNCTION_BEHAVIOR("ase_function_behavior", Level.OPTION, 2, Kind.ON_OFF, "Off", null, null, null,
            Status.NOT_YET),
    ASE_BINARY_DISPLAY("ase_binary_display", Level.OPTION, 2, Kind.ON_OFF, "Off", null, null, null, Status.NOT_YET),
    MAX_CLIENT_NUMERIC_PRECISION("max_client_numeric_precision", Level.OPTION, 2, Kind.integer(0, 126), "0", null,
            null, null, Status.NOT_YET),
    MAX_CLIENT_NUMERIC_SCALE("max_client_numeric_scale", Level.OPTION, 2, Kind.integer(0, 126), "0", null, null, null,
            Status.NOT_YET),
    RESERVED_KEYWORDS("reserved_keywords", Level.PUBLIC_ONLY, 2, Kind.TEXT, "", "", null, null, Status.NOT_YET),
    NON_KEYWORDS("non_keywords", Level.OPTION, 2, Kind.TEXT, "", "", null, null, Status.NOT_YET),
    EXTENDED_JOIN_SYNTAX("extended_join_syntax", Level.OPTION, 2, Kind.ON_OFF, "On", "On", null, null,
            Status.NOT_YET),
    TSQL_OUTER_JOINS("tsql_outer_joins", Level.OPTION, 2, Kind.ON_OFF, "Off", "Off", null, null, Status.NOT_YET),
    MAX_RECURSIVE_ITERATIONS("max_recursive_iterations", Level.OPTION, 2, Kind.integer(0, Integer.MAX_VALUE), "100",
            "100", null, null, Status.NOT_YET),
    UUID_HAS_HYPHENS("uuid_has_hyphens", Level.OPTION, 2, Kind.ON_OFF, "On", "On", null, null, Status.NOT_YET),
    TRUNCATE_TIMESTAMP_VALUES("truncate_timestamp_values", Level.PUBLIC_ONLY, 2, Kind.ON_OFF, "Off", "Off", null, null,
            Status.NOT_YET),
    DEFAULT_TIMESTAMP_INCREMENT("default_timestamp_increment", Level.OPTION, 2, Kind.integer(1, 1000000), "1", "1",
            null, null, Status.NOT_YET),

    // ------------------------------------------------------------------ Tier 3: pinned to the default

    ENABLE_LOB_VARIABLES("enable_lob_variables", Level.OPTION, 3, Kind.ON_OFF, "Off", null, null, null,
            Status.PINNED),
    FLOATING_POINT_ACCUMULATOR("floating_point_accumulator", Level.OPTION, 3, Kind.integer(1, 3), "2", null, null,
            null, Status.PINNED),
    FOR_XML_NULL_TREATMENT("for_xml_null_treatment", Level.OPTION, 3, Kind.TEXT, "Omit", "Omit", null, null,
            Status.PINNED),
    HASH_THRASHING_PERCENT("hash_thrashing_percent", Level.OPTION, 3, Kind.integer(0, 100), "10", null, null, null,
            Status.PINNED),
    MAX_CARTESIAN_RESULT("max_cartesian_result", Level.OPTION, 3, Kind.integer(0, Integer.MAX_VALUE), "100000000",
            null, null, null, Status.PINNED),
    MAX_CUBE_RESULT("max_cube_result", Level.OPTION, 3, Kind.integer(0, Integer.MAX_VALUE), "10000000", null, null,
            null, Status.PINNED),
    MAX_JOIN_ENUMERATION("max_join_enumeration", Level.OPTION, 3, Kind.integer(1, 32), "15", null, null, null,
            Status.PINNED),
    MAX_PREFIX_PER_CONTAINS_PHRASE("max_prefix_per_contains_phrase", Level.OPTION, 3, Kind.integer(0, 300), "1", null,
            null, null, Status.PINNED),
    MAX_QUERY_TIME("max_query_time", Level.OPTION, 3, Kind.integer(0, Integer.MAX_VALUE), "0", null, null, null,
            Status.PINNED),
    MAX_RV_REMOTE_TRANSFER_MB("max_rv_remote_transfer_mb", Level.OPTION, 3, Kind.integer(0, Integer.MAX_VALUE),
            "1000", null, null, null, Status.PINNED),
    MAX_TEMP_SPACE("max_temp_space", Level.OPTION, 3, Kind.TEXT, null, "0", null, null, Status.PINNED),
    MAX_TEMP_SPACE_PER_CONNECTION("max_temp_space_per_connection", Level.OPTION, 3,
            Kind.integer(0, Integer.MAX_VALUE), "0", null, null, null, Status.PINNED),
    NOEXEC("noexec", Level.OPTION, 3, Kind.ON_OFF, "Off", null, null, null, Status.PINNED),
    ON_CHARSET_CONVERSION_FAILURE("on_charset_conversion_failure", Level.OPTION, 3, Kind.TEXT, "Ignore", "Ignore",
            null, null, Status.PINNED),
    QUERY_ROWS_RETURNED_LIMIT("query_rows_returned_limit", Level.OPTION, 3, Kind.integer(0, Integer.MAX_VALUE), "0",
            null, null, null, Status.PINNED),
    QUERY_TEMP_SPACE_LIMIT("query_temp_space_limit", Level.OPTION, 3, Kind.integer(0, Integer.MAX_VALUE), "0", null,
            null, null, Status.PINNED),
    REQUEST_TIMEOUT("request_timeout", Level.OPTION, 3, Kind.integer(0, Integer.MAX_VALUE), null, "0", null, null,
            Status.PINNED),
    SIGNIFICANTDIGITSFORDOUBLEEQUALITY("significantdigitsfordoubleequality", Level.OPTION, 3, Kind.integer(0, 15),
            "0", null, null, null, Status.PINNED),
    SORT_COLLATION("sort_collation", Level.OPTION, 3, Kind.TEXT, "Internal", "Internal", null, null, Status.PINNED),
    SQL_FLAGGER_ERROR_LEVEL("sql_flagger_error_level", Level.OPTION, 3, Kind.TEXT, "Off", "Off", null, null,
            Status.PINNED),
    ST_GEOMETRY_ASBINARY_FORMAT("st_geometry_asbinary_format", Level.OPTION, 3, Kind.TEXT, null, "WKB", null, null,
            Status.PINNED),
    ST_GEOMETRY_ASTEXT_FORMAT("st_geometry_astext_format", Level.OPTION, 3, Kind.TEXT, null, "WKT", null, null,
            Status.PINNED),
    ST_GEOMETRY_ASXML_FORMAT("st_geometry_asxml_format", Level.OPTION, 3, Kind.TEXT, null, "GML", null, null,
            Status.PINNED),
    ST_GEOMETRY_DESCRIBE_TYPE("st_geometry_describe_type", Level.OPTION, 3, Kind.TEXT, null, "CHAR", null, null,
            Status.PINNED),
    ST_GEOMETRY_INTERPOLATION("st_geometry_interpolation", Level.OPTION, 3, Kind.TEXT, null, "", null, null,
            Status.PINNED),
    ST_GEOMETRY_ON_INVALID("st_geometry_on_invalid", Level.OPTION, 3, Kind.TEXT, null, "Error", null, null,
            Status.PINNED),
    TEMP_EXTRACT_FILE_PREFIX("temp_extract_file_prefix", Level.OPTION, 3, Kind.TEXT, "", null, null, null,
            Status.PINNED),
    TEMP_EXTRACT_NAME1("temp_extract_name1", Level.OPTION, 3, Kind.TEXT, "", null, null, null, Status.PINNED),
    TEMP_SPACE_LIMIT_CHECK("temp_space_limit_check", Level.PUBLIC_ONLY, 3, Kind.ON_OFF, "On", "On", null, null,
            Status.PINNED),
    TIME_ZONE("time_zone", Level.PUBLIC_ONLY, 3, Kind.TEXT, null, "", null, null, Status.PINNED),
    TIME_ZONE_ADJUSTMENT("time_zone_adjustment", Level.OPTION, 3, Kind.TEXT, null, "0", null, null, Status.PINNED);

    /** Where a setting lives, as IQ keeps it. */
    public enum Level {
        /** A database property, fixed when the database is created ({@code DB_PROPERTY}). */
        DATABASE,
        /** A connection property ({@code CONNECTION_PROPERTY}): the client's. */
        CONNECTION,
        /** An option IQ keeps only as the database's PUBLIC value ({@code SET OPTION PUBLIC.x}). */
        PUBLIC_ONLY,
        /** An option with a PUBLIC value, a user's and a connection's temporary one. */
        OPTION
    }

    /** How much of a setting the island builds. */
    public enum Status {
        /** Every value IQ accepts is read exactly. */
        IMPLEMENTED,
        /** Some values are read exactly, the others refused by name where they matter ({@link IqSettings}). */
        SOME_VALUES,
        /** Only the default: any other value is refused by name, naming the research's provisional decision. */
        PROVISIONAL,
        /** Only the default (a Tier 2 option not built yet): any other value is refused by name. */
        NOT_YET,
        /** Pinned to its default (Tier 3): any other value is refused by name. */
        PINNED,
        /** No effect on a SELECT's answer: any value accepted. */
        IGNORED
    }

    /** The kind of value a setting takes, with IQ's own reading of one. */
    public record Kind(String name, long min, long max) {

        public static final Kind ON_OFF = new Kind("On/Off", 0, 0);
        public static final Kind DATE_ORDER = new Kind("YMD, MDY or DMY", 0, 0);
        public static final Kind TEXT = new Kind("text", 0, 0);

        /** An integer from {@code min} to {@code max}. */
        public static Kind integer(long min, long max) {
            return new Kind("integer", min, max);
        }

        public boolean isInteger() {
            return name.equals("integer");
        }
    }

    private final String spelling;
    private final Level level;
    private final int tier;
    private final Kind kind;
    private final @com.legend.base.Nullable String iq16;
    private final @com.legend.base.Nullable String sa17;
    private final @com.legend.base.Nullable String tds;
    private final @com.legend.base.Nullable String odbc;
    private final Status status;
    private final @com.legend.base.Nullable String decision;

    IqOption(String spelling, Level level, int tier, Kind kind, @com.legend.base.Nullable String iq16,
            @com.legend.base.Nullable String sa17, @com.legend.base.Nullable String tds,
            @com.legend.base.Nullable String odbc, Status status) {
        this(spelling, level, tier, kind, iq16, sa17, tds, odbc, status, null);
    }

    IqOption(String spelling, Level level, int tier, Kind kind, @com.legend.base.Nullable String iq16,
            @com.legend.base.Nullable String sa17, @com.legend.base.Nullable String tds,
            @com.legend.base.Nullable String odbc, Status status, @com.legend.base.Nullable String decision) {
        this.spelling = spelling;
        this.level = level;
        this.tier = tier;
        this.kind = kind;
        this.iq16 = iq16;
        this.sa17 = sa17;
        this.tds = tds;
        this.odbc = odbc;
        this.status = status;
        this.decision = decision;
    }

    /** The setting {@code name} names, matched without regard to case as IQ matches it; null for none of the
     *  registry's. */
    public static @com.legend.base.Nullable IqOption named(String name) {
        String lower = IqLexer.lower(name);
        for (IqOption o : values()) {
            if (IqLexer.lower(o.spelling).equals(lower)) {
                return o;
            }
        }
        return null;
    }

    /** Its name, as IQ spells it. */
    public String spelling() {
        return spelling;
    }

    public Level level() {
        return level;
    }

    public int tier() {
        return tier;
    }

    public Kind kind() {
        return kind;
    }

    public Status status() {
        return status;
    }

    /** The provisional decision of the research's that a non-default value would need; null for none. */
    public @com.legend.base.Nullable String decision() {
        return decision;
    }

    /** Its default under {@code base}; null where the base has no such setting. */
    public @com.legend.base.Nullable String defaultFor(IqSettings.Base base) {
        return base == IqSettings.Base.IQ16 ? iq16 : sa17;
    }

    /** The value a TDS login gives it (SQL Anywhere's {@code sp_tsql_environment}); null where it keeps its own. */
    public @com.legend.base.Nullable String tdsLogin() {
        return tds;
    }

    /** The value IQ's ODBC driver gives it when it connects; null where it keeps its own. */
    public @com.legend.base.Nullable String odbcLogin() {
        return odbc;
    }

    /** The registry's settings of {@code level}. */
    public static List<IqOption> at(Level level) {
        List<IqOption> out = new java.util.ArrayList<>();
        for (IqOption o : values()) {
            if (o.level == level) {
                out.add(o);
            }
        }
        return out;
    }
}
