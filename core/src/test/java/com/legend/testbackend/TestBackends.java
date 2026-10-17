// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend.testbackend;

import com.legend.database.Databases;
import com.legend.model.ConnectionDefinition.DatabaseType;

import java.util.List;
import java.util.stream.Collectors;

/**
 * THE registry of the tests' databases (the dialect program's Phase 6): each declared {@link DatabaseType} to the
 * {@link TestBackend} its tests run on, as {@code exec.Connectors} gives it its connector and {@code Databases} its
 * dialect. Every type is listed, none with a {@code default} arm: a type no backend is built for is refused by name.
 */
public final class TestBackends {

    private TestBackends() {
    }

    /** {@code type}'s backend. */
    public static TestBackend of(DatabaseType type) {
        return switch (type) {
            case DuckDB -> DuckDbBackend.INSTANCE;
            case H2 -> H2Backend.INSTANCE;
            case SQLite -> SqliteBackend.INSTANCE;
            case Postgres -> PostgresBackend.INSTANCE;
            case MariaDB -> MariaDbBackend.INSTANCE;
            case MySQL -> MySqlBackend.INSTANCE;
            case Trino -> TrinoBackend.INSTANCE;
            case ClickHouse -> ClickHouseBackend.INSTANCE;
            case Snowflake -> SnowflakeBackend.INSTANCE;
            case Databricks -> DatabricksBackend.INSTANCE;
            case BigQuery -> BigQueryBackend.INSTANCE;
            case DB2, MemSQL, Sybase, SybaseIQ, Composite, SqlServer, Hive, Presto, Redshift, Spanner, Athena, Oracle,
                 Aurora -> throw new com.legend.error.NotImplementedException(
                         "no test backend for database type '" + type + "': the platform does not execute on it");
        };
    }

    /** The platform's own engine ({@code Databases.PLATFORM}): where a test that is about no database runs. */
    public static TestBackend platform() {
        return of(Databases.PLATFORM);
    }

    /** The database the SQL-text replay referee runs on ({@code Databases.REPLAY_REFEREE}). */
    public static TestBackend referee() {
        return of(Databases.REPLAY_REFEREE);
    }

    /** Every database the platform executes on ({@code Databases.implemented()}), each one's backend. */
    public static List<TestBackend> all() {
        return Databases.implemented().stream().map(TestBackends::of).toList();
    }

    /** Every database the platform runs in-process, each one's backend: where a test that runs on each database
     *  without a server runs. */
    public static List<TestBackend> inProcess() {
        return all().stream().filter(TestBackend::inProcess).toList();
    }

    /** The backend a lane names ({@link TestBackend#name}); a name no backend has is refused, listing those there
     *  are. */
    public static TestBackend named(String name) {
        for (TestBackend b : all()) {
            if (b.name().equals(name)) {
                return b;
            }
        }
        throw new IllegalStateException("no test backend is named '" + name + "': the backends are "
                + all().stream().map(TestBackend::name).collect(Collectors.joining(", ")));
    }
}
