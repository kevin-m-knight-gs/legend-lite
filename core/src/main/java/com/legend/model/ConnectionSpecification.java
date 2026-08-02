package com.legend.model;

/**
 * How to connect to a specific database instance. Sealed root for the three
 * connection-specification flavors recognized inside a
 * {@code RelationalDatabaseConnection}'s {@code specification:} key.
 *
 * <p>Mirrors engine's {@code com.gs.legend.model.def.ConnectionSpecification}.
 */
public sealed interface ConnectionSpecification
        permits ConnectionSpecification.InMemory,
                ConnectionSpecification.LocalFile,
                ConnectionSpecification.LocalH2,
                ConnectionSpecification.StaticDatasource {

    /**
     * In-memory database connection (DuckDB / SQLite / H2). No configuration.
     * Pure: {@code specification: InMemory {};}.
     */
    record InMemory() implements ConnectionSpecification {}

    /**
     * Local file-based database connection (e.g. DuckDB pointed at a file path).
     * Pure: {@code specification: LocalFile { path: '/tmp/db.duckdb'; };}.
     */
    record LocalFile(String path) implements ConnectionSpecification {}

    /** Local H2-style test datasource ({@code LocalH2 {}} or
     *  {@code LocalH2 { url: '...' }}). The engine's
     *  LocalH2DatasourceSpecification carries NO url at all (it
     *  synthesizes an in-memory database) — a bare {@code LocalH2 {}}
     *  is the common grammar form; an explicit url is carried verbatim
     *  when present. */
    record LocalH2(@com.legend.Nullable String url)
            implements ConnectionSpecification {}

    /**
     * Static datasource with explicit host/port/database. Used for remote servers.
     * Pure: {@code specification: Static { host: '...'; port: 5432; database: '...'; };}.
     */
    record StaticDatasource(String host, int port, String database) implements ConnectionSpecification {}
}
