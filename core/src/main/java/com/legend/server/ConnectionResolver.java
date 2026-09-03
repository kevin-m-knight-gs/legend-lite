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
 * connection DEFINITION + FQN + the model's STORE declarations (type
 * audit D100), so the same stores + definition keep their database
 * across requests (tables persist — the feature) while an EDITED
 * definition or store gets a fresh one (an FQN-only key desynced:
 * engine planCache scar). A spec kind the original resolver folded to
 * in-memory (e.g. {@code LocalH2}) keeps that fold. File-backed
 * embedded connections are cached too, but keyed on the JDBC URL —
 * see {@link #embeddedFile}. Unsupported database types stay LOUD.
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
        return connect(storesKey(model), def);
    }

    /** The model's STORE-SHAPING content: every parsed
     * {@code ###Relational} Database definition, FQN-sorted (record
     * toString covers tables/columns/joins deterministically; source
     * whitespace and non-store elements do not perturb it — the
     * /engine/execute model+query blob must key like the /engine/sql
     * model that seeded the tables). */
    private static Hash storesKey(ParsedModel model) {
        return Hash.ofUtf8(model.elements().stream()
                .filter(el -> el instanceof com.legend.model.DatabaseDefinition)
                .map(el -> (com.legend.model.DatabaseDefinition) el)
                .sorted(java.util.Comparator.comparing(
                        com.legend.model.DatabaseDefinition::qualifiedName))
                .map(Object::toString)
                .reduce("", (a, b) -> a + "\n" + b));
    }

    /** Content key: the model's STORE declarations + the definition's
     * full record content + FQN. The value behind the key is a live
     * in-memory database whose tables are shaped by the model's stores
     * and the SQL the caller runs — a connection-text-only key handed
     * two UNRELATED models each other's tables, the compiler's static
     * type violated by the returned rows (type audit D100, the one
     * cross-caller leak in the audit; its repro differed exactly in
     * the store's column type). Same stores + same definition keeps
     * the database across requests (tables persist — the feature, and
     * the interactive model+query blob flow); an edited STORE rotates
     * (the planCache-scar direction: a changed declaration must never
     * desync onto a stale physical schema). */
    private static Hash contentKey(Hash storesKey, ConnectionDefinition def) {
        return Hash.combine(storesKey,
                Hash.ofUtf8(def.qualifiedName()),
                Hash.ofUtf8(def.toString()));
    }

    /**
     * A file-backed embedded database, cached like its in-memory
     * siblings. Two reasons the LocalFile arms cannot stay on a bare
     * {@code DriverManager.getConnection}:
     *
     * <ul>
     *   <li><b>Nothing closes them.</b> {@code resolve} hands the
     *       connection to a caller that (correctly, for the cached
     *       arms) never closes it, so every request leaked a handle
     *       for the life of the process. POSIX hides this until the
     *       fd ceiling; Windows also pins the file, which is how
     *       {@code QueryServiceDirectTest} found it.</li>
     *   <li><b>Identity is the FILE.</b> Keyed on the JDBC URL, not
     *       on {@link #contentKey} — two models naming the same path
     *       are the same database, and handing them separate handles
     *       to one embedded file is what the driver's own lock
     *       exists to stop.</li>
     * </ul>
     *
     * <p>Unchanged (and still one connection per call): the H2 arms.
     * Their in-memory spellings keep the database alive with
     * {@code DB_CLOSE_DELAY=-1} rather than with the handle, so
     * caching them would fold per-connection session state together —
     * a semantic change this fix has no evidence to make.
     */
    private static Connection embeddedFile(String jdbcUrl) throws SQLException {
        return STORE.getOrOpen(Hash.ofUtf8(jdbcUrl),
                ConnectionResolver::dead,
                () -> DriverManager.getConnection(jdbcUrl));
    }

    private static Connection connect(Hash storesKey, ConnectionDefinition def)
            throws SQLException {
        return switch (def.databaseType()) {
            case DuckDB -> switch (def.specification()) {
                case ConnectionSpecification.LocalFile(String path) ->
                        embeddedFile("jdbc:duckdb:" + path);
                // InMemory — and every spec kind the legacy resolver folded
                // to in-memory (LocalH2, static specs DuckDB can't reach)
                default -> STORE.getOrOpen(contentKey(storesKey, def),
                        ConnectionResolver::dead,
                        () -> DriverManager.getConnection("jdbc:duckdb:"));
            };
            case SQLite -> switch (def.specification()) {
                case ConnectionSpecification.LocalFile(String path) ->
                        embeddedFile("jdbc:sqlite:" + path);
                default -> STORE.getOrOpen(contentKey(storesKey, def),
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
                // across every embedded connection). A USER-NAMED db is
                // user-chosen identity and shares BY DESIGN.
                case ConnectionSpecification.EmbeddedH2(String dbName,
                        String dir, boolean auto) ->
                        "jdbc:h2:mem:" + dbName + ";DB_CLOSE_DELAY=-1";
                // D5: the default arm shared ONE fixed testdb across
                // every unnamed in-memory H2 connection (the same A19
                // disease one arm up) — name per (model, definition)
                // content, same persistence semantics as the cached arms
                default -> "jdbc:h2:mem:c_"
                        + contentKey(storesKey, def).hex().substring(0, 16)
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
