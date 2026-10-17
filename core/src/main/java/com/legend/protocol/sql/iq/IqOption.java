// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend.protocol.sql.iq;

import java.util.List;

/**
 * THE CLOSED REGISTRY of the settings an {@code #IQ{...}#} island is read under (docs/IQ_SQL_STUDY_2026_10_13.md
 * §9.17; IQ island, 2026-10-14, the options framework): every database property, connection property and option the
 * island reads or pins, each by its exact name in IQ's own spelling -- a database property as {@code DB_PROPERTY}
 * names it ({@code CaseSensitive}), a connection property as {@code CONNECTION_PROPERTY} does ({@code CommProtocol}),
 * an option as {@code SET OPTION} and {@code SYSOPTIONS} do ({@code date_order}) -- with its level, its tier (the
 * research's: 1 modelled in every value, 2 modelled when a query needs it, 3 pinned to its default), the kind of value
 * it takes, its default under each engine base (IQ 16 and SQL Anywhere 17; null where the base has no such setting),
 * the value a TDS login and IQ's ODBC driver give it, and how much of it is built ({@link Status}). The options with no
 * effect on a SELECT (the research's Tier 4) are known by name only ({@link #ignored}).
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

    // ------------------------------------------------------------------ Tier 1: each value modelled or refused

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
            Status.IMPLEMENTED),
    CONVERSION_ERROR("conversion_error", Level.OPTION, 1, Kind.ON_OFF, "On", "On", null, null, Status.PROVISIONAL,
            "D-C1"),
    STRING_RTRUNCATION("string_rtruncation", Level.OPTION, 1, Kind.ON_OFF, "On", "On", null, null, Status.PROVISIONAL,
            "D-C2"),
    ANSI_SUBSTRING("ansi_substring", Level.OPTION, 1, Kind.ON_OFF, "On", "On", null, null, Status.IMPLEMENTED),
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
    /** IQ's option; under SQL Anywhere 17 no option of that name, but T-SQL's {@code SET ROWCOUNT n} is it. */
    ROW_COUNT("row_count", Level.OPTION, 2, Kind.integer(0, Integer.MAX_VALUE), "0", "0", null, null,
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
    /** IQ island, 2026-10-15 (checklist 0.6, measured on IQ 17.1): On, {@code *=} and {@code =*} are T-SQL outer joins
     *  (the real IQ 17.1's PUBLIC value, its {@code iq17-*} presets'); Off, IQ's error -1099. */
    TSQL_OUTER_JOINS("tsql_outer_joins", Level.OPTION, 2, Kind.ON_OFF, "Off", "Off", null, null, Status.IMPLEMENTED),
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
        /** A database property, its creation's ({@code DB_PROPERTY}): no statement changes it. */
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

    // The research's Tier 4 by name (lower case), from its options table: IQ's documentation's alone, SQL Anywhere
    // 17's alone (its SYSOPTIONS), both's -- the options with no effect on a SELECT's answer.
    private static final String TIER4_IQ_ONLY =
            "aes_encrypt_header_format affinity_autoexclude_timeout aggregation_preference"
            + " allow_snapshot_versioning base_tables_in_rlv_store bit_vector_pinnable_cache_percent"
            + " bitmap_count_algorithm bt_prefetch_max_miss bt_prefetch_size btree_page_split_pad_percent"
            + " cache_affinity_percent cache_partitions collect_iq_performance_stats commit_threads_percent"
            + " convert_varchar_to_1242 create_equal_striped_backup create_hg_and_force_physical_delete"
            + " create_hg_with_exact_distincts cursor_window_rows dbcc_log_progress dbcc_pinnable_cache_percent"
            + " default_disk_striping default_having_selectivity_ppm default_isql_encoding default_kb_per_stripe"
            + " default_like_match_selectivity_ppm default_like_range_selectivity_ppm default_proxy_table_row_count"
            + " default_table_udf_row_count disable_ri_check dml_options90 early_predicate_execution"
            + " enable_aio_perf_tracing enable_async_io enable_quarantine_map file_preallocate_sampling_threshold"
            + " force_drop force_no_scroll_cursors force_updatable_cursors fp_lookup_size fp_lookup_size_ppm"
            + " fp_nbit_autosize_limit fp_nbit_enforce_limits fp_nbit_iq15_compatibility fp_nbit_lookup_mb"
            + " fp_nbit_rollover_max_mb fp_predicate_workunit_pages fpl_expression_memory_kb"
            + " garray_fill_factor_percent garray_insert_prefetch_size garray_page_split_pad_percent"
            + " garray_ro_prefetch_size hash_pinnable_cache_percent hg_delete_method hg_search_range"
            + " identity_enforce_uniqueness identity_insert in_subquery_preference index_advisor"
            + " index_advisor_max_rows index_preference infer_subquery_predicates iq_log_backup_interval"
            + " iq_log_max_size iq_log_threshold_size iq_point_in_time_recovery_logging"
            + " iq_system_main_allocation_ratio iq_system_main_recovery_threshold iqgovern_max_priority"
            + " iqgovern_priority iqgovern_priority_time join_expansion_factor join_optimization join_preference"
            + " join_simplification_threshold lf_bitmap_cache_kb load_zerolength_asnull log_connect"
            + " log_cursor_operations main_reserved_dbspace_mb max_hash_rows max_iq_threads_per_connection"
            + " max_iq_threads_per_team max_partitioned_hash_mb max_query_parallelism minimize_storage"
            + " monitor_output_directory mpx_autoexclude_timeout mpx_das_communication_enabled mpx_gtr_enabled"
            + " mpx_heartbeat_frequency mpx_idle_connection_timeout mpx_liveness_timeout"
            + " mpx_max_connection_pool_size mpx_max_unused_pool_size mpx_mipc_timeout mpx_shtemp_alloc_lease_time"
            + " mpx_shtemp_intspace_clean non_ansi_null_varchar notify_modulus number_of_prefetch_sub_managers"
            + " on_error post_verify_password_function prefetch_buffer_limit prefetch_buffer_percent"
            + " prefetch_fp_percent prefetch_garray_percent prefetch_hash_percent prefetch_lob_percent"
            + " prefetch_manager_type prefetch_sort_percent prefetch_textpost_percent prefetch_threads_percent"
            + " query_detail query_name query_plan query_plan_after_run query_plan_append_date_to_file"
            + " query_plan_as_html query_plan_as_html_directory query_plan_command_line query_plan_min_time"
            + " query_plan_text_access query_plan_text_caching query_timing redirection_preference"
            + " revert_to_v15_optimizer rv_auto_merge rv_auto_merge_eval_interval rv_block_size_allocation_strategy"
            + " rv_delta_increase_in_fix_data_blocksize rv_fix_data_blocksize rv_initial_fix_data_blocksize"
            + " rv_max_active_subfragment_count rv_max_lookup_mb rv_max_token rv_merge_node_memsize"
            + " rv_merge_table_commit_age rv_merge_table_delpercent rv_merge_table_mempercent"
            + " rv_merge_table_numrows rv_percent_increase_in_fix_data_blocksize rv_reserved_dbspace_mb"
            + " rv_var_data_blocksize snapshot_versioning sort_pinnable_cache_percent subquery_caching_preference"
            + " subquery_flattening_percent subquery_flattening_preference subquery_placement_preference"
            + " sweeper_threads_percent table_udf_row_block_chunk_size_kb temp_data_in_shared_temp"
            + " temp_extract_append temp_extract_binary temp_extract_column_delimiter temp_extract_compress"
            + " temp_extract_directory temp_extract_escape_quotes temp_extract_file_extension"
            + " temp_extract_gz_compression_level temp_extract_length_prefix temp_extract_max_parallel_degree"
            + " temp_extract_name2 temp_extract_name3 temp_extract_name4 temp_extract_name5 temp_extract_name6"
            + " temp_extract_name7 temp_extract_name8 temp_extract_null_as_empty temp_extract_null_as_zero"
            + " temp_extract_quote temp_extract_quotes temp_extract_quotes_all temp_extract_row_delimiter"
            + " temp_extract_size temp_extract_size1 temp_extract_size2 temp_extract_size3 temp_extract_size4"
            + " temp_extract_size5 temp_extract_size6 temp_extract_size7 temp_extract_size8 temp_extract_swap"
            + " temp_extract_varying temp_reserved_dbspace_mb text_delete_method top_nsort_cutoff_pages"
            + " trim_partial_mbc use_aio_subsystem_type user_resource_reservation verify_password_function_v2"
            + " wash_area_buffers_percent wd_delete_method";
    private static final String TIER4_SA_ONLY =
            "allow_snapshot_isolation allow_write_client_file ansi_blanks assume_distinct_servers audit_log"
            + " auditing_options auto_commit_on_create_local_temp_index background_priority blob_threshold"
            // a hidden option SQL Anywhere 17 accepts (not in its SYSOPTIONS; review 8, m6)
            + " upgrade_database_capability"
            + " blocking_others_timeout cis_option collect_statistics_on_dml_updates compression conn_auditing"
            + " connection_authentication connection_type database_authentication db_publisher delete_old_logs"
            + " disk_sandbox exclude_operators extern_login_credentials external_remote_options fire_triggers"
            + " force_view_creation global_database_id http_connection_pool_basesize http_connection_pool_timeout"
            + " integrated_server_name java_class_path java_main_userid lock_rejected_rows"
            + " materialized_view_optimization max_client_statements_cached max_connections max_hash_size"
            + " max_parallel_statements max_plans_cached max_priority max_query_tasks ml_remote_id"
            + " odbc_describe_binary_as_varbinary oem_string optimization_goal optimization_level"
            + " optimization_workload parameterization_level pinned_cursor_percent_of_cache"
            + " prevent_article_pkey_update priority qualify_owners query_mem_timeout quote_all_identifiers"
            + " read_past_deleted remote_idle_timeout replicate_all replication_error replication_error_piece"
            + " rollback_on_deadlock row_counts save_remote_passwords secure_feature_key sr_date_format"
            + " sr_time_format sr_timestamp_format sr_timestamp_with_time_zone_format subscribe_by_remote"
            + " subsume_row_locks synchronize_mirror_on_commit updatable_statement_isolation update_statistics"
            + " user_estimates verify_all_columns verify_threshold webservice_namespace_host"
            + " webservice_sessionid_name";
    private static final String TIER4_BOTH =
            "allow_nulls_by_default allow_optional_semicolon_in_tsql allow_read_client_file"
            + " ansi_close_cursors_on_rollback ansi_permissions ansi_update_constraints auditing auto_commit"
            + " blocking blocking_timeout chained checkpoint_time cis_rowset_size close_on_endtrans"
            + " continue_after_raiserror cooperative_commit_timeout cooperative_commits debug_messages"
            + " dedicated_task default_dbspace delayed_commit_timeout delayed_commits http_session_timeout"
            + " isolation_level java_location java_vm_options log_deadlocks login_mode login_procedure"
            + " max_cursor_count max_statement_count min_password_length min_role_admins"
            + " odbc_distinguish_char_and_varchar on_tsql_error post_login_procedure prefetch"
            + " preserve_source_format progress_messages recovery_time reserved_connections"
            + " sql_flagger_warning_level suppress_tds_debugging trusted_certificates_file verify_password_function"
            + " wait_for_commit";

    private static final java.util.Set<String> TIER4_IQ_ONLY_SET = words(TIER4_IQ_ONLY);
    private static final java.util.Set<String> TIER4_SA_ONLY_SET = words(TIER4_SA_ONLY);
    private static final java.util.Set<String> TIER4_BOTH_SET = words(TIER4_BOTH);

    private static java.util.Set<String> words(String text) {
        java.util.Set<String> out = new java.util.HashSet<>();
        int start = 0;
        for (int i = 0; i <= text.length(); i++) {
            if (i == text.length() || text.charAt(i) == ' ') {
                if (i > start) {
                    out.add(text.substring(start, i));
                }
                start = i + 1;
            }
        }
        return java.util.Collections.unmodifiableSet(out);
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

    /** Its default under {@code base}; null where the base has no such setting. An option of SQL Anywhere's that IQ's
     *  documentation does not name is IQ's too, with SQL Anywhere's default (IQ's catalog store is SQL Anywhere; the
     *  research's OPTIONS, unverified on IQ). */
    public @com.legend.base.Nullable String defaultFor(IqSettings.Base base) {
        return base == IqSettings.Base.IQ16 ? iq16 != null ? iq16 : sa17 : sa17;
    }

    /** Whether {@code base} has an option of this name that {@code SET OPTION} sets: not a database or connection
     *  property, not an option the base lacks (SQL Anywhere 17 has no {@code row_count}: T-SQL's {@code SET ROWCOUNT}
     *  is its form there). */
    public boolean settable(IqSettings.Base base) {
        return (level == Level.OPTION || level == Level.PUBLIC_ONLY) && defaultFor(base) != null
                && !(this == ROW_COUNT && base == IqSettings.Base.SA17);
    }

    /** Whether {@code name} (any case) is one of the options IQ (under {@code base}) has that change no SELECT's answer
     *  (the research's Tier 4: performance, locks, transactions, logging, security, multiplex, extraction): any value
     *  is accepted, at any level, and ignored. */
    public static boolean ignored(String name, IqSettings.Base base) {
        String n = IqLexer.lower(name);
        return TIER4_BOTH_SET.contains(n) || TIER4_SA_ONLY_SET.contains(n)
                || base == IqSettings.Base.IQ16 && TIER4_IQ_ONLY_SET.contains(n);
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
