package com.legend.warehouse.server;

import com.legend.base.Nullable;
import com.legend.warehouse.server.duck.Conn;
import java.io.IOException;
import java.util.List;

/**
 * The databases a catalog can attach and pass its SQL through to (docs/DATACUBE_APP_PLAN_2026_10_02.md, A4):
 * one row per kind, as data, and the only place the warehouse names one (the dialect program's D4, 2026-10-18: the
 * command line, the password prompt and libpq's words reach Postgres through here too). A catalog is NATIVE (DuckDB's
 * own tables: per-object grants, the Authorizer, sessions) or ATTACHED to one of these (its SQL is the attached
 * database's, passed through unread; a reader needs USAGE of the whole catalog). Each operation below is an
 * exhaustive switch: a new row does not compile until every operation answers for it.
 */
public enum Attachment {

    /** A Postgres database, through DuckDB's postgres extension ({@link Postgres}). */
    POSTGRES("Postgres", "postgres", "postgres_scanner.duckdb_extension", List.of("pg_catalog", "information_schema"),
            "--postgres", "sales='host=db dbname=sales user=reader'");

    /** What an attached catalog's database calls the database it attached. */
    static final String ALIAS = "attached";

    /** Its database type as a Pure connection names it ({@code type: Postgres}): what a model of its tables declares. */
    final String databaseType;
    /** DuckDB's {@code ATTACH ... (TYPE <duckdbType>)}. */
    final String duckdbType;
    /** The extension file a {@code --duckdb-extensions} directory holds for it. */
    final String extensionFile;
    /** The attached database's own schemas: not the user's data, never listed. */
    final List<String> systemSchemas;
    /** The command-line option that names a catalog of this kind: {@code <flag> NAME=CONNECTION}. */
    public final String flag;
    /** An example of the flag's {@code NAME=CONNECTION}, for the refusal of a malformed one. */
    final String flagExample;

    Attachment(String databaseType, String duckdbType, String extensionFile, List<String> systemSchemas, String flag,
            String flagExample) {
        this.databaseType = databaseType;
        this.duckdbType = duckdbType;
        this.extensionFile = extensionFile;
        this.systemSchemas = systemSchemas;
        this.flag = flag;
        this.flagExample = flagExample;
    }

    /** The kind whose command-line option is {@code arg}, or null. */
    static @Nullable Attachment byFlag(String arg) {
        for (Attachment a : values()) {
            if (a.flag.equals(arg)) return a;
        }
        return null;
    }

    /** A catalog named on the command line by its URL alone: its name, and its kind and connection string. */
    record Named(String catalog, Catalogs.Attach attach) {
    }

    /** The catalog {@code arg} names when it is an attachable database's URL ({@code postgresql://...}), else null. */
    static @Nullable Named byUrl(String arg) {
        for (Attachment a : values()) {
            Named named = a.fromUrl(arg);
            if (named != null) return named;
        }
        return null;
    }

    /** The catalog {@code arg} names when it is one of this kind's URLs, else null. */
    private @Nullable Named fromUrl(String arg) {
        return switch (this) {
            case POSTGRES -> {
                if (!PostgresUrl.isUrl(arg)) yield null;
                PostgresUrl url = PostgresUrl.parse(arg);
                yield new Named(url.catalog(), new Catalogs.Attach(this, url.dsn()));
            }
        };
    }

    /** The connection string the attach is made with: the session contract pinned in it (the UTC zone). */
    String connectionString(String catalog, String dsn) {
        return switch (this) {
            case POSTGRES -> Postgres.inSessionZone(catalog, dsn);
        };
    }

    /** Whether an attach failed, saying {@code why}, for want of a password that a terminal could be asked for. */
    boolean missingPassword(String why) {
        return switch (this) {
            case POSTGRES -> Postgres.missingPassword(why);
        };
    }

    /** {@code connection} with the {@code password} the terminal was asked for; null when it cannot carry one. */
    @Nullable String withPassword(String connection, char[] password) {
        return switch (this) {
            case POSTGRES -> PostgresUrl.isUrl(connection) ? null : PostgresUrl.withPassword(connection, password);
        };
    }

    /** Where a password goes when there is no terminal to ask on. */
    String passwordHelp() {
        return switch (this) {
            case POSTGRES -> Postgres.PASSWORD_HELP;
        };
    }

    /** The DuckDB statement that runs the client's {@code sql} in the attached database, tagged for a cancel. */
    String passthrough(String sql, String statementId) {
        return switch (this) {
            case POSTGRES -> Postgres.query(sql, statementId);
        };
    }

    /** The DuckDB statement that stops, in the attached database, what {@code statementId} started there. */
    String cancel(String statementId) {
        return switch (this) {
            case POSTGRES -> Postgres.cancel(statementId);
        };
    }

    /** The DuckDB statement that lists the attached database's tables and columns from ITS OWN catalog, in the
     *  listing's shape -- so a model of them is written by that database's rules. */
    String catalogListing() {
        return switch (this) {
            case POSTGRES -> Postgres.catalogListing(systemSchemas);
        };
    }

    /** Refuses, by name, an attached server older than the dialect is written for. */
    void requireSupported(String catalog, Conn c) throws IOException {
        switch (this) {
            case POSTGRES -> Postgres.requireSupportedVersion(catalog, c);
        }
    }
}
