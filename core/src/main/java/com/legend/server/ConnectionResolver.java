package com.legend.server;

import com.legend.model.AuthenticationSpec;
import com.legend.model.ConnectionDefinition;
import com.legend.model.ConnectionSpecification;
import com.legend.model.ParsedModel;
import com.legend.model.RuntimeDefinition;

import com.legend.cache.HandleStore;
import com.legend.cache.Hash;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

/**
 * Runtime-name &rarr; live JDBC connection, resolved directly from the core
 * parse ({@code com.legend.model} records — the engine-lite bridge record
 * round-trip is gone). In-memory connections are cached in the
 * content-addressed {@link HandleStore} (D5): the key hashes the
 * connection DEFINITION + FQN, so the same definition keeps its
 * database across requests (tables persist — the feature) while an
 * EDITED definition gets a fresh one (an FQN-only key desynced: engine
 * planCache scar). A spec kind the original resolver folded to
 * in-memory (e.g. {@code LocalH2}) keeps that fold. Unsupported
 * database types stay LOUD.
 */
final class ConnectionResolver {

    private ConnectionResolver() {
    }

    private static final HandleStore<Connection> STORE = new HandleStore<>();

    /** A connection is DEAD when closed — or unanswerable, which only a
     * broken handle produces; treating it live would cache the wreck. */
    private static boolean dead(Connection c) {
        try {
            return c.isClosed();
        } catch (SQLException e) {
            return true;
        }
    }

    static Connection resolve(String pureSource, String runtimeName)
            throws SQLException {
        ParsedModel model = com.legend.Compiler.parseModel(pureSource);
        RuntimeDefinition runtime = null;
        for (var el : model.elements()) {
            if (el instanceof RuntimeDefinition r
                    && (r.qualifiedName().equals(runtimeName)
                            || simpleName(r.qualifiedName()).equals(runtimeName))) {
                runtime = r;
            }
        }
        if (runtime == null) {
            throw new IllegalArgumentException("Runtime not found: " + runtimeName);
        }
        var bindings = runtime.connectionBindings();
        if (bindings.isEmpty()) {
            throw new IllegalArgumentException(
                    "Runtime has no connection bindings: " + runtimeName);
        }
        var connRefs = bindings.values().iterator().next();
        if (connRefs.isEmpty()) {
            throw new IllegalArgumentException(
                    "Runtime has no connection bindings: " + runtimeName);
        }
        String connectionRef = connRefs.get(0);
        ConnectionDefinition def = null;
        for (var el : model.elements()) {
            if (el instanceof ConnectionDefinition c
                    && (c.qualifiedName().equals(connectionRef)
                            || simpleName(c.qualifiedName()).equals(connectionRef))) {
                def = c;
            }
        }
        if (def == null) {
            throw new IllegalArgumentException("Connection not found: " + connectionRef);
        }
        return connect(def);
    }

    /** Content key: the definition's full record content + FQN. Two
     * connections are the same database iff they are the same
     * DEFINITION — record toString covers every component (type, spec,
     * auth) deterministically. */
    private static Hash contentKey(ConnectionDefinition def) {
        return Hash.combine(Hash.ofUtf8(def.qualifiedName()),
                Hash.ofUtf8(def.toString()));
    }

    private static Connection connect(ConnectionDefinition def) throws SQLException {
        return switch (def.databaseType()) {
            case DuckDB -> switch (def.specification()) {
                case ConnectionSpecification.LocalFile(String path) ->
                        DriverManager.getConnection("jdbc:duckdb:" + path);
                // InMemory — and every spec kind the legacy resolver folded
                // to in-memory (LocalH2, static specs DuckDB can't reach)
                default -> STORE.getOrOpen(contentKey(def),
                        ConnectionResolver::dead,
                        () -> DriverManager.getConnection("jdbc:duckdb:"));
            };
            case SQLite -> switch (def.specification()) {
                case ConnectionSpecification.LocalFile(String path) ->
                        DriverManager.getConnection("jdbc:sqlite:" + path);
                default -> STORE.getOrOpen(contentKey(def),
                        ConnectionResolver::dead,
                        () -> DriverManager.getConnection("jdbc:sqlite::memory:"));
            };
            case H2 -> auth(DriverManager.getConnection(switch (def.specification()) {
                case ConnectionSpecification.LocalFile(String path) -> "jdbc:h2:file:" + path;
                case ConnectionSpecification.StaticDatasource(String host, int port,
                        String database) -> "jdbc:h2:tcp://" + host + ":" + port + "/" + database;
                // A19: a DISTINCT in-memory db per databaseName — the
                // engine's directory-backed isolation without disk side
                // effects (the old fold shared ONE fixed testdb instance
                // across every embedded connection)
                case ConnectionSpecification.EmbeddedH2(String dbName,
                        String dir, boolean auto) ->
                        "jdbc:h2:mem:" + dbName + ";DB_CLOSE_DELAY=-1";
                // D5: the default arm shared ONE fixed testdb across
                // every unnamed in-memory H2 connection (the same A19
                // disease one arm up) — name per DEFINITION content,
                // same persistence semantics as the cached arms
                default -> "jdbc:h2:mem:c_"
                        + contentKey(def).hex().substring(0, 16)
                        + ";DB_CLOSE_DELAY=-1";
            }), def);
            case Postgres -> {
                if (!(def.specification()
                        instanceof ConnectionSpecification.StaticDatasource(
                                String host, int port, String database))) {
                    throw new com.legend.error.NotImplementedException(
                            "Postgres requires a static datasource with host/port/database");
                }
                yield auth(DriverManager.getConnection(
                        "jdbc:postgresql://" + host + ":" + port + "/" + database), def);
            }
            default -> throw new com.legend.error.NotImplementedException(
                    "connection resolution for database type '" + def.databaseType()
                            + "' is not implemented");
        };
    }

    private static Connection auth(Connection connection, ConnectionDefinition def) {
        if (def.authentication() instanceof AuthenticationSpec.UsernamePassword) {
            throw new com.legend.error.NotImplementedException(
                    "UsernamePassword authentication is not implemented — use NoAuth");
        }
        // NoAuth / DefaultH2 / TestAuth: nothing to apply.
        return connection;
    }

    private static String simpleName(String fqn) {
        int cut = fqn.lastIndexOf("::");
        return cut < 0 ? fqn : fqn.substring(cut + 2);
    }
}
