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
 * in-memory (e.g. {@code LocalH2}) keeps that fold. Unsupported
 * database types stay LOUD.
 */
final class ConnectionResolver {

    private ConnectionResolver() {
    }

    private static final HandleStore<Connection> STORE = new HandleStore<>();

    /**
     * A BORROWED connection. {@code close()} RELEASES it, and what releasing
     * means is the lease's business, not the caller's:
     *
     * <ul>
     *   <li>a STORE-OWNED handle (the in-memory arms) is released by doing
     *       NOTHING — {@link HandleStore} owns it for the life of the process
     *       because evicting it would silently drop its tables, and closing it
     *       here would break the persistence feature
     *       {@code ConnectionIsolationTest} pins;</li>
     *   <li>every other arm is a fresh {@code DriverManager} connection that
     *       this lease closes. Before leases nobody closed them, so each call
     *       through an auto-resolving {@link QueryService} method leaked one
     *       connection — 25 calls, 25 file descriptors, measured
     *       (ConnectionLeaseTest). Silent on POSIX, and on Windows the symptom
     *       was a database file that could not be deleted.</li>
     * </ul>
     *
     * <p>Callers always close. That is the whole contract, and it is the
     * engine's (a Hikari connection's {@code close} returns it to the pool)
     * reached without the engine's pool — which cannot be ported here because
     * the engine RE-SEEDS per acquisition and never persists across requests.
     * See docs/CONNECTION_LEASE_DESIGN_2026_09_09.md.
     */
    static final class Lease implements AutoCloseable {

        private final Connection connection;
        private final boolean storeOwned;

        private Lease(Connection connection, boolean storeOwned) {
            this.connection = connection;
            this.storeOwned = storeOwned;
        }

        /** A handle the {@link HandleStore} owns: releasing is a no-op. */
        static Lease borrowed(Connection c) {
            return new Lease(c, true);
        }

        /** A connection opened for this call alone: the lease closes it. */
        static Lease owned(Connection c) {
            return new Lease(c, false);
        }

        Connection connection() {
            return connection;
        }

        @Override
        public void close() throws SQLException {
            if (!storeOwned) {
                connection.close();
            }
        }
    }

    /** A connection is DEAD when closed — or unanswerable, which only a
     * broken handle produces; treating it live would cache the wreck. */
    private static boolean dead(Connection c) {
        try {
            return c.isClosed();
        } catch (SQLException e) {
            return true;
        }
    }

    static Lease resolve(String pureSource, String runtimeName)
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

    private static Lease connect(Hash storesKey, ConnectionDefinition def)
            throws SQLException {
        return switch (def.databaseType()) {
            case DuckDB -> switch (def.specification()) {
                case ConnectionSpecification.LocalFile(String path) ->
                        Lease.owned(DriverManager.getConnection("jdbc:duckdb:" + path));
                // InMemory — and every spec kind the legacy resolver folded
                // to in-memory (LocalH2, static specs DuckDB can't reach)
                default -> Lease.borrowed(STORE.getOrOpen(
                        contentKey(storesKey, def), ConnectionResolver::dead,
                        () -> DriverManager.getConnection("jdbc:duckdb:")));
            };
            case SQLite -> switch (def.specification()) {
                case ConnectionSpecification.LocalFile(String path) ->
                        Lease.owned(DriverManager.getConnection("jdbc:sqlite:" + path));
                default -> Lease.borrowed(STORE.getOrOpen(
                        contentKey(storesKey, def), ConnectionResolver::dead,
                        () -> DriverManager.getConnection("jdbc:sqlite::memory:")));
            };
            case H2 -> Lease.owned(auth(DriverManager.getConnection(switch (def.specification()) {
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
            }), def));
            case Postgres -> {
                if (!(def.specification()
                        instanceof ConnectionSpecification.StaticDatasource(
                                String host, int port, String database))) {
                    throw new com.legend.error.NotImplementedException(
                            "Postgres requires a static datasource with host/port/database");
                }
                yield Lease.owned(auth(DriverManager.getConnection(
                        "jdbc:postgresql://" + host + ":" + port + "/" + database), def));
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
