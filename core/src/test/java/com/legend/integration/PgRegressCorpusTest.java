// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend.integration;

import com.legend.protocol.sql.SqlLexer;
import com.legend.server.QueryService;
import com.legend.testcases.Psql;
import com.legend.testcases.SqlParity;
import com.legend.testing.EmbeddedPostgres;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * The {@code #SQL{...}#} island over the PostgreSQL regression suite (the corpus program's first corpus, 2026-10-17:
 * the yardstick Postgres-compatible projects publish): its read-only statements, judged live against Postgres 16.
 *
 * <p>The suite under {@code -Dsql.corpus.regress} (a {@code src/test/regress} directory: {@code parallel_schedule},
 * {@code sql/}, {@code data/}) is replayed as {@code pg_regress} runs it, serially: {@code test_setup} first, then each
 * file of the schedule in order, each in its own session of one database, by {@link Psql}. Each read-only statement
 * ({@code SELECT}, {@code WITH}, {@code VALUES}, {@code TABLE}; not {@code SELECT ... INTO}, not a data-modifying
 * {@code WITH}) is judged where the script reaches it: Postgres's answer is the script's own; Postgres runs it a second
 * time (a different answer is NONDETERMINISTIC, judged no further); then it runs as an island over a Legend model of
 * the tables it names, read from the catalog at that moment, on the same session, and the parity harness judges the
 * rows ({@link SqlParity#outcome}). The second run and the island run are rolled back (a savepoint inside the script's
 * transaction, else a transaction of their own) under a {@code statement_timeout}, so the script runs on as written.
 *
 * <p>Statuses: SqlParity's (PASS, FAIL, BUG, REFUSED, ERROR, BOTH_ERROR), NONDETERMINISTIC, UNMODELED (the island
 * failed on a statement that reads a column of a type no Legend column models), and SKIP (the script's transaction was
 * already aborted). FAIL and BUG are wrong answers. The report -- every status counted, per file and in all, each
 * failing status's messages normalized and ranked -- is written to {@code -Dsql.corpus.report}. The suite is not this
 * repository's: the lane runs where the property names it (core/BUILD.bazel's {@code pg_regress_corpus_test}).
 */
class PgRegressCorpusTest {

    private record Judged(String file, int line, String status, long millis, String detail, String sql) {
    }

    private record Col(String name, String pgType, @com.legend.base.Nullable String legendType) {
    }

    @Test
    void regressionSuite() throws Exception {
        String root = System.getProperty("sql.corpus.regress");
        if (root == null) {
            throw new IllegalStateException("no -Dsql.corpus.regress: the lane runs where it names the suite"
                    + " (core/BUILD.bazel's pg_regress_corpus_test says how)");
        }
        Path dir = Path.of(root);
        String only = System.getProperty("sql.corpus.files");
        int timeoutSeconds = Integer.parseInt(System.getProperty("sql.corpus.timeout.s", "20"));
        EmbeddedPostgres pg = EmbeddedPostgres.shared();
        try (Connection admin = DriverManager.getConnection(pg.jdbcUrl("postgres"));
                Statement s = admin.createStatement()) {
            s.execute("DROP DATABASE IF EXISTS regression WITH (FORCE)");
            s.execute("CREATE DATABASE regression TEMPLATE template0 ENCODING 'UTF8' LC_COLLATE 'C' LC_CTYPE 'C'");
        }
        Map<String, String> vars = new HashMap<>();
        for (String v : List.of("abs_srcdir", "abs_builddir")) {
            vars.put(v, dir.toAbsolutePath().toString());
        }
        vars.put("libdir", "/nonexistent");
        vars.put("dlsuffix", ".so");
        vars.put("env:PG_ABS_SRCDIR", dir.toAbsolutePath().toString());
        vars.put("env:PG_ABS_BUILDDIR", dir.toAbsolutePath().toString());
        vars.put("env:PG_LIBDIR", "/nonexistent");
        vars.put("env:PG_DLSUFFIX", ".so");
        List<String> files = schedule(dir.resolve("parallel_schedule"));
        List<Judged> judged = new ArrayList<>();
        QueryService service = new QueryService();
        for (String file : files) {
            if (only != null && !file.equals("test_setup") && !Set.of(only.split(",")).contains(file)) {
                continue;
            }
            Path sql = dir.resolve("sql").resolve(file + ".sql");
            if (!Files.exists(sql)) {
                continue;
            }
            List<Psql.Item> items = Psql.parse(Files.readString(sql, StandardCharsets.UTF_8));
            Map<String, String> fileVars = new HashMap<>(vars);
            Connection conn = DriverManager.getConnection(pg.jdbcUrl("regression"));
            List<Connection> opened = new ArrayList<>(List.of(conn));
            Psql.Runner runner = new Psql.Runner(conn, fileVars, () -> {
                try {
                    Connection c = DriverManager.getConnection(pg.jdbcUrl("regression"));
                    opened.add(c);
                    return c;
                } catch (SQLException e) {
                    throw new IllegalStateException(e);
                }
            }, timeoutSeconds);
            try {
                runner.run(items, (text, item, answer) -> {
                    if (readOnly(text)) {
                        judged.add(judge(file, item.line(), text, answer, runner.connection(), service,
                                timeoutSeconds, pg.port()));
                    }
                });
            } finally {
                for (Connection c : opened) {
                    c.close();
                }
            }
            System.out.println("[pg-regress] " + file + ": " + counts(judged, file));
        }
        String report = summary(judged);
        System.out.println(report);
        String out = System.getProperty("sql.corpus.report");
        if (out != null) {
            Path o = Path.of(out);
            Files.createDirectories(o);
            Files.writeString(o.resolve("pg-regress.md"), report, StandardCharsets.UTF_8);
            StringBuilder tsv = new StringBuilder("file\tline\tstatus\tms\tdetail\tsql\n");
            for (Judged j : judged) {
                tsv.append(j.file()).append('\t').append(j.line()).append('\t').append(j.status()).append('\t')
                        .append(j.millis()).append('\t').append(clean(j.detail(), 400)).append('\t')
                        .append(clean(j.sql(), 600)).append('\n');
            }
            Files.writeString(o.resolve("pg-regress.tsv"), tsv, StandardCharsets.UTF_8);
        }
    }

    /** The schedule's tests, in order ({@code test: a b c} lines; a group runs serially here). */
    static List<String> schedule(Path file) throws java.io.IOException {
        List<String> tests = new ArrayList<>();
        for (String l : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            String t = l.strip();
            if (t.startsWith("test:")) {
                for (String name : t.substring(5).strip().split("\\s+")) {
                    if (!name.isEmpty()) {
                        tests.add(name);
                    }
                }
            }
        }
        return tests;
    }

    // ------------------------------------------------------------------ which statements are judged

    private static final Pattern FIRST_WORD = Pattern.compile("(?is)^[\\s(]*(select|with|values|table)\\b");

    private static final Set<String> FIRST = Set.of("select", "with", "values", "table");

    private static final Set<String> WRITES = Set.of("insert", "delete", "merge");

    /** A statement that reads only: it starts as a query does, and writes nothing ({@code INSERT}, {@code DELETE},
     *  {@code MERGE}, an {@code UPDATE} that is not {@code FOR [NO KEY] UPDATE}, a top-level {@code INTO}). */
    static boolean readOnly(String sql) {
        List<SqlLexer.Token> tokens;
        try {
            tokens = SqlLexer.tokenize(sql);
        } catch (RuntimeException e) {
            // a statement the island's lexer cannot read (SqlSyntaxException and the like): its first word decides
            return FIRST_WORD.matcher(sql).lookingAt();
        }
        int k = 0;
        while (k < tokens.size() && tokens.get(k).kind() == SqlLexer.Kind.LPAREN) {
            k++;
        }
        if (k >= tokens.size() || tokens.get(k).kind() != SqlLexer.Kind.IDENT
                || !FIRST.contains(tokens.get(k).text().toLowerCase(Locale.ROOT))) {
            return false;
        }
        int depth = 0;
        String prev = "";
        for (SqlLexer.Token t : tokens) {
            if (t.kind() == SqlLexer.Kind.LPAREN) {
                depth++;
            } else if (t.kind() == SqlLexer.Kind.RPAREN) {
                depth--;
            }
            String w = t.kind() == SqlLexer.Kind.IDENT ? t.text().toLowerCase(Locale.ROOT) : "";
            if (WRITES.contains(w) || w.equals("update") && !prev.equals("for") && !prev.equals("key")
                    || w.equals("into") && depth == 0) {
                return false;
            }
            prev = w;
        }
        return true;
    }

    // ------------------------------------------------------------------ the judging

    private static Judged judge(String file, int line, String sql, SqlParity.Answer answer, Connection conn,
            QueryService service, int timeoutSeconds, int port) {
        long start = System.nanoTime();
        if (answer instanceof SqlParity.Rejected r && r.sqlState().equals("25P02")) {
            return new Judged(file, line, "SKIP", 0, "the script's transaction is aborted", sql);
        }
        try {
            boolean savepoint = open(conn, timeoutSeconds);
            SqlParity.Answer second = send(conn, sql);
            reset(conn, savepoint, timeoutSeconds);
            if (answer instanceof SqlParity.Rows && !SqlParity.agrees(answer, second)) {
                close(conn, savepoint);
                return new Judged(file, line, "NONDETERMINISTIC", ms(start), "Postgres answered it twice"
                        + " differently", sql);
            }
            Map<String, List<Col>> tables = tables(conn, sql);
            reset(conn, savepoint, timeoutSeconds);
            String model = model(tables, port);
            Set<String> names = tables.keySet();
            SqlParity.Case c = new SqlParity.Case("pg-regress/" + file, "L" + line, sql, null);
            SqlParity.Outcome o = SqlParity.outcome(c, answer, names,
                    island -> service.execute(model, island, SqlParity.RUNTIME, conn));
            close(conn, savepoint);
            String status = o.status().name();
            if ((status.equals("ERROR") || status.equals("REFUSED")) && readsUnmodeled(sql, tables)
                    || status.equals("FAIL") && o.detail().startsWith("Column count mismatch") && starOverUnmodeled(sql,
                    tables)) {
                status = "UNMODELED";
            }
            return new Judged(file, line, status, ms(start), o.detail(), sql);
        } catch (SQLException | RuntimeException e) {
            return new Judged(file, line, "HARNESS", ms(start), e.getClass().getSimpleName() + ": "
                    + e.getMessage(), sql);
        }
    }

    private static long ms(long start) {
        return (System.nanoTime() - start) / 1_000_000;
    }

    /** A rollback point for what the judge runs: a savepoint in the script's transaction (true), else a transaction
     *  of its own (false); either with the judge's own statement timeout. */
    private static boolean open(Connection conn, int timeoutSeconds) throws SQLException {
        boolean savepoint;
        try (Statement s = conn.createStatement()) {
            s.execute("SAVEPOINT corpus_judge");
            savepoint = true;
        } catch (SQLException e) {
            if (!"25P01".equals(e.getSQLState())) {
                throw e;
            }
            savepoint = false;
            try (Statement s = conn.createStatement()) {
                s.execute("BEGIN");
            }
        }
        timeout(conn, timeoutSeconds);
        return savepoint;
    }

    private static void timeout(Connection conn, int seconds) throws SQLException {
        try (Statement s = conn.createStatement()) {
            s.execute("SET LOCAL statement_timeout = '" + seconds + "s'");
        }
    }

    /** Undoes what ran since {@link #open}, and opens again. */
    private static void reset(Connection conn, boolean savepoint, int timeoutSeconds) throws SQLException {
        try (Statement s = conn.createStatement()) {
            s.execute(savepoint ? "ROLLBACK TO SAVEPOINT corpus_judge" : "ROLLBACK");
            if (!savepoint) {
                s.execute("BEGIN");
            }
        }
        timeout(conn, timeoutSeconds);
    }

    private static void close(Connection conn, boolean savepoint) throws SQLException {
        try (Statement s = conn.createStatement()) {
            if (savepoint) {
                s.execute("ROLLBACK TO SAVEPOINT corpus_judge");
                s.execute("RELEASE SAVEPOINT corpus_judge");
            } else {
                s.execute("ROLLBACK");
            }
        }
    }

    private static SqlParity.Answer send(Connection conn, String sql) {
        try (Statement st = conn.createStatement()) {
            if (st.execute(sql)) {
                try (ResultSet rs = st.getResultSet()) {
                    return new SqlParity.Rows(SqlParity.grid(rs));
                }
            }
            return new SqlParity.Rows(new SqlParity.Grid(List.of(), List.of()));
        } catch (SQLException e) {
            String message = e.getMessage() == null ? "" : e.getMessage().split("\n")[0];
            return new SqlParity.Rejected(e.getSQLState() == null ? "" : e.getSQLState(), message);
        }
    }

    // ------------------------------------------------------------------ the model

    private static final Pattern SIMPLE = Pattern.compile("[a-z_][a-z0-9_]*");

    /** The relations on the search path that {@code sql} names, each with its columns, read from the catalog. */
    private static Map<String, List<Col>> tables(Connection conn, String sql) throws SQLException {
        Set<String> words = new java.util.HashSet<>();
        Matcher m = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*").matcher(sql);
        while (m.find()) {
            words.add(m.group().toLowerCase(Locale.ROOT));
        }
        Map<String, List<Col>> tables = new LinkedHashMap<>();
        String q = "SELECT c.relname, a.attname, format_type(a.atttypid, a.atttypmod)"
                + " FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace"
                + " JOIN pg_attribute a ON a.attrelid = c.oid"
                + " WHERE c.relkind IN ('r', 'v', 'm', 'p', 'f') AND a.attnum > 0 AND NOT a.attisdropped"
                + " AND n.nspname = ANY (current_schemas(true)) AND n.nspname <> 'pg_catalog'"
                + " ORDER BY array_position(current_schemas(true), n.nspname), c.relname, a.attnum";
        Set<String> taken = new java.util.HashSet<>();
        String current = null;
        try (Statement s = conn.createStatement(); ResultSet rs = s.executeQuery(q)) {
            while (rs.next()) {
                String rel = rs.getString(1);
                if (!words.contains(rel) || !SIMPLE.matcher(rel).matches()) {
                    continue;
                }
                if (!rel.equals(current)) {
                    current = rel;
                    if (!taken.add(rel)) {
                        current = null; // a name earlier on the search path hides it
                    }
                }
                if (current == null) {
                    continue;
                }
                String type = rs.getString(3);
                String col = rs.getString(2);
                tables.computeIfAbsent(rel, k -> new ArrayList<>()).add(new Col(col, type,
                        SIMPLE.matcher(col).matches() ? legendType(type) : null));
            }
        }
        return tables;
    }

    private static final Pattern SIZED = Pattern.compile("(character varying|character|numeric)\\((\\d+)(?:,(\\d+))?\\)");

    /** The Legend column type a Postgres type is, or null when none models it. */
    static @com.legend.base.Nullable String legendType(String pgType) {
        Matcher m = SIZED.matcher(pgType);
        if (m.matches()) {
            return switch (m.group(1)) {
                case "character varying" -> "VARCHAR(" + m.group(2) + ")";
                case "character" -> "CHAR(" + m.group(2) + ")";
                default -> "NUMERIC(" + m.group(2) + "," + (m.group(3) == null ? "0" : m.group(3)) + ")";
            };
        }
        if (pgType.startsWith("timestamp") && pgType.endsWith("without time zone")) {
            return "TIMESTAMP";
        }
        return switch (pgType) {
            case "smallint" -> "SMALLINT";
            case "integer" -> "INTEGER";
            case "bigint" -> "BIGINT";
            case "real" -> "REAL";
            case "double precision" -> "DOUBLE";
            // a Legend VARCHAR is declared with its size: text's is Postgres's longest varchar
            case "text", "character varying" -> "VARCHAR(10485760)";
            // name: 63 bytes, compared as text (the database's collation is C)
            case "name" -> "VARCHAR(63)";
            case "boolean" -> "BIT";
            case "date" -> "DATE";
            case "json", "jsonb" -> "SEMISTRUCTURED";
            default -> null;
        };
    }

    /** The model of {@code tables}: their modeled columns, on the embedded Postgres the island runs on (through
     *  the caller's own connection, the judged session). */
    private static String model(Map<String, List<Col>> tables, int port) {
        StringBuilder m = new StringBuilder("###Relational\nDatabase " + SqlParity.DATABASE + "\n(\n");
        for (Map.Entry<String, List<Col>> t : tables.entrySet()) {
            List<Col> cols = t.getValue().stream().filter(c -> c.legendType() != null).toList();
            if (cols.isEmpty()) {
                continue;
            }
            m.append("    Table ").append(t.getKey()).append("\n    (\n");
            for (int i = 0; i < cols.size(); i++) {
                m.append("        ").append(cols.get(i).name()).append(' ').append(cols.get(i).legendType())
                        .append(i + 1 < cols.size() ? ",\n" : "\n");
            }
            m.append("    )\n");
        }
        if (tables.values().stream().allMatch(cols -> cols.stream().allMatch(c -> c.legendType() == null))) {
            m.append("    Table corpus_none\n    (\n        x INTEGER\n    )\n");
        }
        m.append(")\n###Connection\nRelationalDatabaseConnection e2e::Conn\n{\n    type: Postgres;\n"
                + "    specification: Static { host: '127.0.0.1'; port: " + port + "; name: 'regression'; };\n"
                + "    auth: Test;\n}\n###Runtime\nRuntime ").append(SqlParity.RUNTIME)
                .append("\n{\n    mappings: [];\n    connections: [ ").append(SqlParity.DATABASE)
                .append(": [ environment: e2e::Conn ] ];\n}\n");
        return m.toString();
    }

    /** {@code sql} names a column of a type no Legend column models, or a relation all of whose columns are such. */
    private static boolean readsUnmodeled(String sql, Map<String, List<Col>> tables) {
        String lower = sql.toLowerCase(Locale.ROOT);
        for (List<Col> cols : tables.values()) {
            for (Col c : cols) {
                if (c.legendType() == null && Pattern.compile("\\b" + Pattern.quote(c.name().toLowerCase(Locale.ROOT))
                        + "\\b").matcher(lower).find()) {
                    return true;
                }
            }
            if (cols.stream().allMatch(c -> c.legendType() == null)) {
                return true;
            }
        }
        return false;
    }

    /** {@code sql} selects {@code *} and a relation it names has a column no Legend column models (the island's
     *  {@code *} has fewer columns than Postgres's). */
    private static boolean starOverUnmodeled(String sql, Map<String, List<Col>> tables) {
        return sql.contains("*") && tables.values().stream().anyMatch(cols -> cols.stream().anyMatch(c ->
                c.legendType() == null));
    }

    // ------------------------------------------------------------------ the report

    private static final List<String> ORDER = List.of("PASS", "FAIL", "BUG", "REFUSED", "UNMODELED", "ERROR",
            "BOTH_ERROR", "NONDETERMINISTIC", "SKIP", "HARNESS");

    private static String counts(List<Judged> judged, String file) {
        Map<String, Integer> n = new TreeMap<>();
        for (Judged j : judged) {
            if (j.file().equals(file)) {
                n.merge(j.status(), 1, Integer::sum);
            }
        }
        return n.toString();
    }

    private static String summary(List<Judged> judged) {
        Map<String, Integer> total = new LinkedHashMap<>();
        ORDER.forEach(s -> total.put(s, 0));
        for (Judged j : judged) {
            total.merge(j.status(), 1, Integer::sum);
        }
        StringBuilder r = new StringBuilder("# #SQL{} over the PostgreSQL regression suite (read-only statements)\n\n");
        r.append("Judged: ").append(judged.size()).append("\n\n| status | n |\n|---|---|\n");
        total.forEach((s, n) -> r.append("| ").append(s).append(" | ").append(n).append(" |\n"));
        for (String status : List.of("FAIL", "BUG", "ERROR", "REFUSED", "UNMODELED", "HARNESS")) {
            Map<String, Integer> reasons = new HashMap<>();
            Map<String, String> example = new HashMap<>();
            for (Judged j : judged) {
                if (j.status().equals(status)) {
                    String key = normalized(j.detail());
                    reasons.merge(key, 1, Integer::sum);
                    example.putIfAbsent(key, j.file() + ":" + j.line());
                }
            }
            if (reasons.isEmpty()) {
                continue;
            }
            r.append("\n## ").append(status).append(" (").append(reasons.values().stream().mapToInt(Integer::intValue)
                    .sum()).append(")\n\n| n | reason | e.g. |\n|---|---|---|\n");
            reasons.entrySet().stream().sorted((a, b) -> b.getValue() - a.getValue()).limit(60)
                    .forEach(e -> r.append("| ").append(e.getValue()).append(" | ").append(clean(e.getKey(), 200)
                            .replace("|", "\\|")).append(" | ").append(example.get(e.getKey())).append(" |\n"));
        }
        return r.toString();
    }

    /** A message with its particulars (quoted text, numbers) masked, so messages of one kind count together. */
    static String normalized(String detail) {
        return detail.replaceAll("'[^']*'", "'…'").replaceAll("\"[^\"]*\"", "\"…\"").replaceAll("\\d+", "N")
                .replaceAll("\\s+", " ").strip();
    }

    private static String clean(String s, int max) {
        String t = s.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ');
        return t.length() > max ? t.substring(0, max) + "…" : t;
    }
}
