package com.legend.server;

import com.legend.model.AuthenticationSpec;
import com.legend.model.ConnectionDefinition;
import com.legend.model.ConnectionSpecification;
import com.legend.model.ParsedModel;
import com.legend.model.RuntimeDefinition;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Runtime-name &rarr; live JDBC connection, resolved directly from the core
 * parse ({@code com.legend.model} records — the engine-lite bridge record
 * round-trip is gone). In-memory connections are cached per connection FQN so
 * tables persist across requests; a spec kind the original resolver folded to
 * in-memory (e.g. {@code LocalH2}) keeps that fold. Unsupported database
 * types stay LOUD.
 */
final class ConnectionResolver {

    private ConnectionResolver() {
    }

    private static final Map<String, Connection> CACHE = new ConcurrentHashMap<>();

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

    private static Connection connect(ConnectionDefinition def) throws SQLException {
        return switch (def.databaseType()) {
            case DuckDB -> switch (def.specification()) {
                case ConnectionSpecification.LocalFile(String path) ->
                        DriverManager.getConnection("jdbc:duckdb:" + path);
                // InMemory — and every spec kind the legacy resolver folded
                // to in-memory (LocalH2, static specs DuckDB can't reach)
                default -> cached("duckdb:inmemory:" + def.qualifiedName(),
                        () -> DriverManager.getConnection("jdbc:duckdb:"));
            };
            case SQLite -> switch (def.specification()) {
                case ConnectionSpecification.LocalFile(String path) ->
                        DriverManager.getConnection("jdbc:sqlite:" + path);
                default -> cached("sqlite:inmemory:" + def.qualifiedName(),
                        () -> DriverManager.getConnection("jdbc:sqlite::memory:"));
            };
            case H2 -> auth(DriverManager.getConnection(switch (def.specification()) {
                case ConnectionSpecification.LocalFile(String path) -> "jdbc:h2:file:" + path;
                case ConnectionSpecification.StaticDatasource(String host, int port,
                        String database) -> "jdbc:h2:tcp://" + host + ":" + port + "/" + database;
                default -> "jdbc:h2:mem:testdb;DB_CLOSE_DELAY=-1";
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

    private static Connection cached(String key, ConnectionSupplier supplier)
            throws SQLException {
        Connection existing = CACHE.get(key);
        if (existing != null && !existing.isClosed()) {
            return existing;
        }
        Connection fresh = supplier.get();
        CACHE.put(key, fresh);
        return fresh;
    }

    @FunctionalInterface
    private interface ConnectionSupplier {
        Connection get() throws SQLException;
    }

    private static String simpleName(String fqn) {
        int cut = fqn.lastIndexOf("::");
        return cut < 0 ? fqn : fqn.substring(cut + 2);
    }
}
