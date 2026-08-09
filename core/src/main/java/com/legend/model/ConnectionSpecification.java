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
                ConnectionSpecification.StaticDatasource,
                ConnectionSpecification.Snowflake,
                ConnectionSpecification.Spanner,
                ConnectionSpecification.Databricks,
                ConnectionSpecification.BigQuery {

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
     *  is the common grammar form; an explicit url is a legend-lite
     *  extension carried verbatim when present. Engine's
     *  {@code testDataSetupCSV} / {@code testDataSetupSqls} keys are
     *  carried as data for the test-seeding path. */
    record LocalH2(@com.legend.Nullable String url,
            @com.legend.Nullable String testDataSetupCsv,
            java.util.@com.legend.Nullable List<String> testDataSetupSqls)
            implements ConnectionSpecification {
        public LocalH2(@com.legend.Nullable String url) {
            this(url, null, null);
        }
    }

    /**
     * Static datasource with explicit host/port/database. Used for remote servers.
     * Pure: {@code specification: Static { host: '...'; port: 5432; database: '...'; };}.
     */
    record StaticDatasource(String host, int port, String database) implements ConnectionSpecification {}

    /** Engine's Snowflake datasource; carried for parse coverage — dialect
     *  selection refuses Snowflake execution loudly. */
    record Snowflake(String databaseName, String accountName, String warehouseName,
            String region, @com.legend.Nullable String accountType,
            @com.legend.Nullable String cloudType,
            @com.legend.Nullable Boolean enableQueryTags,
            @com.legend.Nullable String organization,
            @com.legend.Nullable String role)
            implements ConnectionSpecification {}

    /** Engine's GCP Spanner datasource; carried for parse coverage. */
    record Spanner(String projectId, String instanceId, String databaseId)
            implements ConnectionSpecification {}

    /** Engine's Databricks datasource; carried for parse coverage. */
    record Databricks(String hostname, String port, String protocol,
            String httpPath) implements ConnectionSpecification {}

    /** Engine's BigQuery datasource; carried for parse coverage. */
    record BigQuery(String projectId, String defaultDataset)
            implements ConnectionSpecification {}
}
