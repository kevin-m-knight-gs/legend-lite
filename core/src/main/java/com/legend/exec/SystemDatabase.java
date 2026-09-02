package com.legend.exec;

import com.legend.compiler.element.ModelContext;
import com.legend.model.DatabaseDefinition;
import com.legend.sql.dialect.SqlDialect;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

/**
 * THE system database of a compiled graph (user ruling 2026-09-02): the
 * metamodel rows live in a database of their own, SEPARATE from every user
 * connection &mdash; one per graph per database engine, written ONCE the
 * first time a query of that graph reads the metamodel, alive as long as
 * the graph ({@link ModelContext#derived}). Nothing is seeded per
 * execution any more: the executor ROUTES a store-reading body to this
 * connection (the corpus re-seeded ~20 tables of a corpus-sized graph on
 * every store-reading test &mdash; docs/GATES.md, 2026-09-02 budget entry).
 *
 * <p>Two kinds of rows. The GRAPH's rows are a pure function of the
 * compiled graph, derived once per table (the derivation is the caller's
 * &mdash; this package cannot see the seed derivations). A QUERY's own
 * constructed instances ({@code ^DynaFunction(...)} trees) are
 * content-addressed rows: inserted once per id, never twice.
 *
 * <p>The engine follows the SESSION the query would have run on (an H2
 * lane keeps exercising the metamodel queries on H2 &mdash; the 21-kind
 * union hang was an H2 finding); the connection is the ONE in-memory
 * database of that engine.
 */
public final class SystemDatabase {

    private static final java.lang.ref.Cleaner CLEANER = java.lang.ref.Cleaner.create();
    private static final AtomicInteger IDS = new AtomicInteger();

    /** The Cleaner action: holds the open connections, NEVER the store
     * (a self-reference would keep the graph alive). */
    private static final class Closer implements Runnable {
        private final List<Connection> open =
                java.util.Collections.synchronizedList(new ArrayList<>());

        @Override
        public void run() {
            for (Connection c : open) {
                try {
                    c.close();
                } catch (SQLException ignore) {
                    // closing a dead in-memory session: nothing to report
                }
            }
        }
    }

    /** One engine's session: the connection + the constructed ids it holds. */
    private static final class Session {
        private final Connection connection;
        private final Set<String> constructedIds = new HashSet<>();

        private Session(Connection connection) {
            this.connection = connection;
        }
    }

    private final Map<String, Session> sessions = new HashMap<>();
    private final Map<String, List<List<String>>> rows = new ConcurrentHashMap<>();
    private final Closer closer = new Closer();

    private SystemDatabase() {
        CLEANER.register(this, closer);
    }

    /** The graph's system store (created on first ask). */
    public static SystemDatabase of(ModelContext ctx) {
        return ctx.derived(SystemDatabase.class, c -> new SystemDatabase());
    }

    /**
     * The connection holding the graph's metamodel for the engine of
     * {@code session}, opened and written on first use. {@code store} is
     * the store's Database element (its DDL enumerates the tables);
     * {@code rowsOf} derives one table's rows (called once per table per
     * graph); {@code constructed} are this query's own rows per table.
     */
    public synchronized Connection connectionFor(Connection session,
            SqlDialect dialect, DatabaseDefinition store,
            Function<String, List<List<String>>> rowsOf,
            Map<String, List<List<String>>> constructed) {
        String engine = product(session);
        Session s = sessions.get(engine);
        if (s == null) {
            s = open(engine, dialect, store, rowsOf);
            sessions.put(engine, s);
        }
        insertConstructed(s, store, constructed);
        return s.connection;
    }

    private Session open(String engine, SqlDialect dialect,
            DatabaseDefinition store, Function<String, List<List<String>>> rowsOf) {
        Connection c;
        try {
            c = switch (engine) {
                case "H2" -> DriverManager.getConnection("jdbc:h2:mem:sysstore"
                        + IDS.getAndIncrement() + H2Settings.SETTINGS, "sa", "");
                case "DuckDB" -> DriverManager.getConnection("jdbc:duckdb:");
                case "SQLite" -> DriverManager.getConnection("jdbc:sqlite::memory:");
                default -> throw new IllegalStateException("system database: no"
                        + " in-memory engine for a '" + engine + "' session");
            };
        } catch (SQLException e) {
            throw new IllegalStateException("system database: cannot open the "
                    + engine + " session", e);
        }
        closer.open.add(c);
        for (String setup : dialect.sessionSetup()) {
            Executor.executeRaw(c, setup);
        }
        boolean duckTarget = !dialect.rawH2IsNative();
        for (DatabaseDefinition.SchemaDefinition schema : store.schemas()) {
            for (DatabaseDefinition.TableDefinition def : schema.tables()) {
                List<List<String>> r = rows.computeIfAbsent(def.name(), rowsOf);
                for (String stmt : Ddl.metamodelSeed(def, schema.name(), r, duckTarget)) {
                    Executor.executeRaw(c, stmt);
                }
            }
        }
        return new Session(c);
    }

    /** A query's constructed rows, keyed by their first column (the
     * content id): only ids the session does not hold yet insert. */
    private static void insertConstructed(Session s, DatabaseDefinition store,
            Map<String, List<List<String>>> constructed) {
        for (Map.Entry<String, List<List<String>>> e : constructed.entrySet()) {
            List<List<String>> fresh = new ArrayList<>();
            for (List<String> row : e.getValue()) {
                if (s.constructedIds.add(e.getKey() + "|" + row.get(0))) {
                    fresh.add(row);
                }
            }
            if (fresh.isEmpty()) {
                continue;
            }
            boolean inserted = false;
            for (DatabaseDefinition.SchemaDefinition schema : store.schemas()) {
                for (DatabaseDefinition.TableDefinition def : schema.tables()) {
                    if (def.name().equals(e.getKey())) {
                        String ins = Ddl.metamodelInsert(def, schema.name(), fresh);
                        if (ins != null) {
                            Executor.executeRaw(s.connection, ins);
                        }
                        inserted = true;
                    }
                }
            }
            if (!inserted) {
                throw new IllegalStateException("system database: constructed rows"
                        + " for unknown store table '" + e.getKey() + "'");
            }
        }
    }

    /** The session's engine — java.sql stops here. */
    private static String product(Connection session) {
        try {
            return session.getMetaData().getDatabaseProductName();
        } catch (SQLException e) {
            throw new IllegalStateException("system database: the session's"
                    + " engine is unreadable", e);
        }
    }
}
