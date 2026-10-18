package com.legend.warehouse.server;

import com.legend.base.Nullable;
import com.legend.warehouse.server.duck.Conn;
import com.legend.warehouse.server.duck.Database;
import com.legend.warehouse.server.duck.DuckException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * The warehouse's catalogs: in W1, one DuckDB database file per catalog,
 * owned by this process (W0 Q4: a writable file belongs to one process),
 * opened through DuckDB's C API. Behind this class so DuckLake (W0 Q5) can
 * take its place for on-demand readers without the API noticing.
 *
 * <p>An ATTACHED catalog ({@link Attachment}) is an in-memory DuckDB database with one other database
 * attached, READ_ONLY, before it is locked down. Both kinds share one namespace of names.
 *
 * <p>Every connection belongs to one principal: what
 * {@code system.main.authenticated_user()} answers on it, from outside SQL.
 */
public final class Catalogs implements AutoCloseable {

    private static final Pattern NAME = Pattern.compile("[a-z][a-z0-9_]{0,62}");

    private final Path dataDir;
    private final Map<String, Database> databases = new TreeMap<>();
    private final Map<String, Attachment> attached = new HashMap<>();

    public Catalogs(Path dataDir, List<String> names) throws IOException, DuckException {
        this(dataDir, names, Map.of(), null);
    }

    /** An attached catalog's kind and the connection string it attaches with. */
    public record Attach(Attachment kind, String connection) {
    }

    /**
     * {@code attach}: the attached catalogs by name; {@code extensions}: the directory holding the DuckDB
     * extension each kind loads, required when there are any.
     */
    Catalogs(Path dataDir, List<String> names, Map<String, Attach> attach, @Nullable Path extensions)
            throws IOException, DuckException {
        this.dataDir = dataDir;
        Files.createDirectories(dataDir);
        Files.createDirectories(dataDir.resolve("import"));
        try {
            for (String n : names) open(n);
            for (Map.Entry<String, Attach> e : attach.entrySet()) {
                if (extensions == null) {
                    throw new IllegalArgumentException("an attached catalog needs --duckdb-extensions DIR"
                            + " (the directory holding " + e.getValue().kind().extensionFile + ")");
                }
                openAttached(e.getKey(), e.getValue().kind(), e.getValue().connection(), extensions);
            }
        } catch (IOException | DuckException | RuntimeException e) {
            close();
            throw e;
        }
    }

    public static boolean validName(String name) {
        return NAME.matcher(name).matches();
    }

    private void open(String name) throws DuckException {
        claim(name);
        Database db = Database.open(dataDir.resolve(name + ".duckdb"));
        db.lockDown(importDir());
        databases.put(name, db);
    }

    private void openAttached(String name, Attachment kind, String dsn, Path extensions) throws IOException, DuckException {
        claim(name);
        Path extension = extensions.resolve(kind.extensionFile);
        if (!Files.isRegularFile(extension)) {
            throw new IOException("DuckDB's " + kind.duckdbType + " extension is not at " + extension + " (--duckdb-extensions)");
        }
        // the connection string with the session contract pinned in it (the UTC zone)
        String connection = kind.connectionString(name, dsn);
        Database db = Database.open(null);
        try {
            db.attach(extension, connection, Attachment.ALIAS, kind.duckdbType);
            db.lockDown(null);
            try (Conn c = db.connect(Statements.SERVER)) {
                kind.requireSupported(name, c);
            }
        } catch (DuckException | IOException e) {
            db.close();
            // the DSN may carry a password: name the catalog, never echo the connection string
            String why = String.valueOf(e.getMessage()).replace(connection, "<dsn>").replace(dsn, "<dsn>");
            throw new AttachFailed(name, kind, why, kind.missingPassword(why));
        }
        databases.put(name, db);
        attached.put(name, kind);
    }

    /** An attached catalog that could not be attached, by name; whether it lacked a password a terminal could give. */
    public static final class AttachFailed extends IOException {
        public final String catalog;
        final Attachment kind;
        public final boolean missingPassword;

        AttachFailed(String catalog, Attachment kind, String why, boolean missingPassword) {
            super("could not attach " + kind.databaseType + " catalog " + catalog + ": " + why);
            this.catalog = catalog;
            this.kind = kind;
            this.missingPassword = missingPassword;
        }
    }

    private void claim(String name) {
        if (!validName(name)) throw new IllegalArgumentException("bad catalog name: " + name);
        if (databases.containsKey(name)) throw new IllegalArgumentException("catalog " + name + " is named twice");
    }

    /** A native catalog's database type, as a Pure connection names it: the warehouse's own tables are DuckDB's. */
    static final String NATIVE_DATABASE_TYPE = "DuckDB";

    /** {@code catalog}'s database type, as a Pure connection names it: the SQL its statements are written in. */
    public synchronized String databaseType(String catalog) {
        Attachment a = attached.get(catalog);
        return a == null ? NATIVE_DATABASE_TYPE : a.databaseType;
    }

    /** What {@code catalog} is attached to (its statements are that database's SQL, passed through); null when native. */
    public synchronized @Nullable Attachment attachment(String catalog) {
        return attached.get(catalog);
    }

    /** Where an owner puts files to load: the one directory a catalog may read files from. */
    public Path importDir() {
        return dataDir.resolve("import");
    }

    public synchronized List<String> names() {
        return List.copyOf(databases.keySet());
    }

    /** A new connection to the catalog for {@code principal}, or null when there is no such catalog. */
    public @Nullable Conn connect(String catalog, String principal) throws DuckException {
        Database db;
        synchronized (this) {
            db = databases.get(catalog);
        }
        return db == null ? null : db.connect(principal);
    }

    @Override
    public synchronized void close() {
        for (Database db : databases.values()) db.close();
        databases.clear();
        attached.clear();
    }
}
