// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend.database;

import com.legend.model.ConnectionDefinition.DatabaseType;
import com.legend.sql.dialect.SqlDialect;

/**
 * THE plan-side owner of every per-database decision (docs/PLAN_EXECUTION_SPLIT_AND_DATABASE_OWNER_2026_10_03.md
 * C3): given a DECLARED {@link DatabaseType}, the SQL this platform writes for it — the execution dialect and its
 * plan-protocol facts. legend-engine's golden TEXT for a type is not here: its renderers are quarantined to the root
 * layer (AGENTS invariant 4d), so their one owner is {@code com.legend.EngineText}. Upstream's own split: SQL generation per database type
 * ({@code dbExtension.pure}, {@code loadDbExtension}) apart from connection management (the execution side,
 * {@code com.legend.exec.Sessions}). Every type is listed in every switch, none has a {@code default} arm, and a
 * capability a database lacks is refused by name. Nothing here touches JDBC: the browser planner uses it.
 */
public final class Databases {

    private Databases() {
    }

    /** The database the platform runs a query on when its runtime binds no database at all — only model data
     *  (a {@code ModelStore}'s JSON, instances): its own in-process engine (SEMANTICS_REGISTER S27, decision D3). */
    public static final DatabaseType PLATFORM = DatabaseType.DuckDB;

    /** The database the SQL-text replay referee runs on: its ledger records DDL in this database's spelling. */
    public static final DatabaseType REPLAY_REFEREE = DatabaseType.H2;

    /** A {@code DatabaseType} by its Pure enum name ({@code meta::relational::runtime::DatabaseType.DB2} → DB2).
     *  The Pure enum also has {@code SparkSQL} and {@code DebugPrint}, which this platform does not model: refused. */
    public static DatabaseType named(String name) {
        for (DatabaseType t : DatabaseType.values()) {
            if (t.name().equals(name)) {
                return t;
            }
        }
        throw new com.legend.error.NotImplementedException("database type '" + name
                + "' is not one this platform models " + java.util.Arrays.toString(DatabaseType.values()));
    }

    /** The dialect a query on {@code type} is planned and executed with. */
    public static SqlDialect dialect(DatabaseType type) {
        return switch (type) {
            case DuckDB -> new com.legend.sql.dialect.DuckDb();
            case H2 -> new com.legend.sql.dialect.H2();
            case Postgres -> new com.legend.sql.dialect.Postgres();
            case Databricks -> new com.legend.sql.dialect.Databricks();
            case Snowflake -> new com.legend.sql.dialect.Snowflake();
            case BigQuery -> new com.legend.sql.dialect.BigQuery();
            // a dialect of its own since 2026-10-11 (docs/CLOUD_BACKENDS_PCT_2026_10_11.md §12): T3.2 made it a
            // Lexicon row when it differed only lexically; its PCT lane measured otherwise
            case SQLite -> new com.legend.sql.dialect.SQLite();
            // docs/CLOUD_BACKENDS_PCT_2026_10_11.md §13
            case MariaDB -> new com.legend.sql.dialect.MariaDb();
            // docs/CLOUD_BACKENDS_PCT_2026_10_11.md §14: MariaDB's dialect where the two agree
            case MySQL -> new com.legend.sql.dialect.MySql();
            // docs/CLOUD_BACKENDS_PCT_2026_10_11.md §18
            case Trino -> new com.legend.sql.dialect.Trino();
            // docs/CLOUD_BACKENDS_PCT_2026_10_11.md §19
            case ClickHouse -> new com.legend.sql.dialect.ClickHouse();
            // docs/MONGODB_PLAN_2026_10_16.md (D1, D2): the aggregation pipeline as the one compiler's target
            case MongoDB -> new com.legend.sql.dialect.MongoDb();
            case DB2, MemSQL, Sybase, SybaseIQ, Composite, SqlServer, Hive, Presto,
                 Redshift, Spanner, Athena, Oracle, Aurora ->
                    throw new com.legend.error.NotImplementedException(
                            "SQL dialect for database type '" + type + "' is not implemented yet");
        };
    }

    /** The server versions a statement planned for {@code type} is written for ({@link #dialect}'s spelling): H2's
     *  depends on the server's version ({@code H2.forServer}), so a plan names the versions its text is for and its
     *  runner refuses another; the other databases' spelling depends on none. */
    public static com.legend.executionplan.ExecutionPlan.Servers servers(DatabaseType type) {
        return switch (type) {
            case H2 -> new com.legend.executionplan.ExecutionPlan.Servers.Versions(com.legend.sql.dialect.H2.SERVERS);
            case DuckDB, Postgres, SQLite, Databricks, Snowflake, BigQuery, MariaDB, MySQL, Trino, ClickHouse,
                 MongoDB ->
                    new com.legend.executionplan.ExecutionPlan.Servers.Every();
            case DB2, MemSQL, Sybase, SybaseIQ, Composite, SqlServer, Hive, Presto,
                 Redshift, Spanner, Athena, Oracle, Aurora ->
                    throw new com.legend.error.NotImplementedException(
                            "SQL dialect for database type '" + type + "' is not implemented yet");
        };
    }

    /** Whether a target on {@code type} loads its declared rows through the database's own bulk loader (a plan's
     *  {@code SetupStep.Rows}) rather than one INSERT: DuckDB, where one giant INSERT cost most of a 61 s first query
     *  (docs/EXECUTION_PLAN_BOUNDARY_2026_10_05.md §9, "setup at plan time, one form per database"). */
    public static boolean loadsRowsInBulk(DatabaseType type) {
        return switch (type) {
            case DuckDB -> true;
            case H2, Postgres, SQLite, Databricks, Snowflake, BigQuery, MariaDB, MySQL, Trino, ClickHouse, MongoDB -> false;
            case DB2, MemSQL, Sybase, SybaseIQ, Composite, SqlServer, Hive, Presto,
                 Redshift, Spanner, Athena, Oracle, Aurora ->
                    throw new com.legend.error.NotImplementedException(
                            "loading rows into database type '" + type + "' is not implemented yet");
        };
    }

    /** The servers a plan written for a session of {@code serverVersion} names: where {@code type}'s spelling depends
     *  on the version ({@link ExecutionPlan.Servers.Versions}, H2's 2.1 and 2.2 the engine-parity dialect, 2.3 and
     *  later the modern one), that version's {@code major.minor} alone, so the plan's runner accepts the session the
     *  plan was written for (the dialect is {@code SqlDialect.forServer}'s); else the declared servers. A null
     *  version is the declared target (the plan path program's step 9, 2026-10-19: the H2 PCT lane and channel B's
     *  H2 judge run the modern H2, and a plan for the declared target was refused on every case). */
    public static com.legend.executionplan.ExecutionPlan.Servers serversFor(DatabaseType type,
            @com.legend.base.Nullable String serverVersion) {
        com.legend.executionplan.ExecutionPlan.Servers declared = servers(type);
        if (serverVersion != null && declared instanceof com.legend.executionplan.ExecutionPlan.Servers.Versions) {
            return new com.legend.executionplan.ExecutionPlan.Servers.Versions(java.util.List.of(majorMinor(serverVersion)));
        }
        return declared;
    }

    /** A server version's {@code major.minor} ({@code 2.4.240} is {@code 2.4}): the prefix a plan's versions name and
     *  its runner checks the session's version against. A version with no dot is itself. */
    public static String majorMinor(String version) {
        int first = version.indexOf('.');
        int second = first < 0 ? -1 : version.indexOf('.', first + 1);
        return second < 0 ? version : version.substring(0, second);
    }

    /** Whether {@code type} runs IN THIS PROCESS (an embedded engine, a private instance of which the platform opens:
     *  H2, DuckDB, SQLite) or on a SERVER. The system metamodel store's tables are a private instance's own tables in
     *  their schema, and a server session's own TEMPORARY tables (exec.SystemDatabase's form, the user's ruling of
     *  2026-10-11; a plan's, the plan path program's step 9, 2026-10-19). */
    public static boolean inProcess(DatabaseType type) {
        return switch (type) {
            case H2, DuckDB, SQLite -> true;
            case Postgres, DB2, MemSQL, Sybase, SybaseIQ, Composite, SqlServer, Hive, Snowflake, Presto, Trino,
                 BigQuery, Redshift, Databricks, Spanner, Athena, Oracle, ClickHouse, Aurora, MariaDB, MySQL,
                 MongoDB -> false;
        };
    }

    /** legend-engine's IN-list-to-temp-table facts for {@code type}: the temp table's name prefix and the list size past
     *  which the engine uses one (null: never, for this database). */
    public record InListTempTables(String tablePrefix, @com.legend.base.Nullable Integer threshold) {
    }

    public static InListTempTables inListTempTables(DatabaseType type) {
        return switch (type) {
            case DB2 -> new InListTempTables("SESSION.tempTableForIn_", 32767);
            case H2, DuckDB, Postgres, SQLite, MemSQL, Sybase, SybaseIQ, Composite, SqlServer, Hive, Snowflake, Presto,
                 Trino, BigQuery, Redshift, Databricks, Spanner, Athena, Oracle, ClickHouse, Aurora, MariaDB, MySQL,
                 MongoDB ->
                    new InListTempTables("tempTableForIn_", null);
        };
    }
}
