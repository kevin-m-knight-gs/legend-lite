// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend.testcases;

import com.legend.protocol.sql.SqlAst;
import com.legend.protocol.sql.SqlSyntaxException;
import com.legend.protocol.sql.iq.IqDialect;
import com.legend.protocol.sql.iq.IqLexer;
import com.legend.protocol.sql.iq.IqParser;
import com.legend.protocol.sql.iq.IqProfile;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The {@code #IQ{...}#} island's parity cases (docs/IQ_SQL_STUDY_2026_10_13.md §6): legend-lite's own, in
 * {@code core/src/test/resources/iq-parity/lite/*.yaml}, over the tables of {@code iq-parity/schema.yaml}, each run
 * under each of its profiles as an island over those tables, and judged against SQL Anywhere 17's answer snapped by
 * {@code tools/iq-oracle} into {@code iq-answers.jsonl} -- or, for a case that names a provisional decision, against
 * the rows the decision gives ({@code decision}, {@code expect}). The judging, the statuses, the ratchet and the ledger
 * are {@link SqlParity}'s; this class has what differs: the case keys, the profiles, the island's spelling, the FROM
 * rewrite (through {@link IqLexer}), the IQ types of the schema.
 *
 * <p>The snapped answers are {@link IqAnswers}', which also guards them against the cases.
 */
public final class IqParity {

    /** The database every case's tables are in, and the runtime that reaches it. */
    public static final String DATABASE = "e2e::DB";
    public static final String RUNTIME = "e2e::RT";

    /** The keys a case may carry (tools/iq-oracle/README.md): any other is an error. */
    private static final Set<String> CASE_KEYS = Set.of("id", "sql", "profile", "engine", "skip", "sa_defect",
            "decision", "expect");

    private static final Pattern DECISION = Pattern.compile("D-[A-Z]+[0-9]+[a-z]?");

    private IqParity() {
    }

    // ------------------------------------------------------------------ the cases

    /** A case: its file (relative to iq-parity/, {@code lite/...}), its id, its SQL, its profiles, the engine it pins
     *  (null for auto), why it is not run (a skip or an SA defect), and the provisional decision that judges it, with
     *  the decided rows (one line of JSON). */
    public record Case(String file, String id, String sql, List<String> profiles, @com.legend.base.Nullable String engine,
            @com.legend.base.Nullable String skip, @com.legend.base.Nullable String decision,
            @com.legend.base.Nullable String expect) {

        /** The case's key in the answers file: {@code lite/<file>/<id>}. */
        public String key() {
            return file.replaceAll("\\.yaml$", "") + "/" + id;
        }
    }

    /** One run of a case: the case under one of its profiles. Its key in the ledger is the case's,
     *  {@code @} and the profile. */
    public record Run(Case c, IqProfile profile) {

        public String key() {
            return c.key() + "@" + profile.spelling();
        }

        /** The run as SqlParity's ratchet keeps one: its file and an id that holds the profile. */
        SqlParity.Case asSqlParityCase() {
            return new SqlParity.Case(c.file(), c.id() + "@" + profile.spelling(), c.sql(), c.skip());
        }
    }

    /** Every case of the case files under {@code liteDir}, in file order (files by path). */
    public static List<Case> cases(Path liteDir) throws IOException {
        List<Path> files;
        try (java.util.stream.Stream<Path> walk = Files.walk(liteDir)) {
            files = walk.filter(p -> p.toString().endsWith(".yaml"))
                    .sorted(java.util.Comparator.comparing(p -> liteDir.relativize(p).toString().replace('\\', '/')))
                    .toList();
        }
        List<Case> cases = new ArrayList<>();
        Set<String> keys = new java.util.HashSet<>();
        for (Path f : files) {
            String file = "lite/" + liteDir.relativize(f).toString().replace('\\', '/');
            for (Map<String, Object> m : SqlParity.Yaml.tests(Files.readString(f, StandardCharsets.UTF_8), file)) {
                Case c = toCase(file, m);
                if (!keys.add(c.key())) {
                    throw new IllegalStateException(file + ": the id " + c.id() + " twice");
                }
                cases.add(c);
            }
        }
        return cases;
    }

    private static Case toCase(String file, Map<String, Object> m) {
        String where = file + ": case " + m.get("id");
        for (String k : m.keySet()) {
            if (!CASE_KEYS.contains(k)) {
                throw new IllegalStateException(where + ": unknown key " + k + " (known: " + CASE_KEYS + ")");
            }
        }
        if (!(m.get("id") instanceof String id) || !(m.get("sql") instanceof String sql)) {
            throw new IllegalStateException(where + ": a case needs an id and its sql as text");
        }
        List<String> profiles = new ArrayList<>();
        Object written = m.get("profile");
        for (String p : (written == null ? IqProfile.DEFAULT.spelling() : written.toString()).split(",")) {
            String name = p.strip();
            if (IqProfile.named(name) == null || profiles.contains(name)) {
                throw new IllegalStateException(where + ": an unknown profile, or one named twice: " + name);
            }
            profiles.add(name);
        }
        String engine = (String) m.get("engine");
        if (engine != null && !Set.of("iq", "sa", "auto").contains(engine)) {
            throw new IllegalStateException(where + ": engine is iq, sa or auto, not " + engine);
        }
        String decision = (String) m.get("decision");
        String expect = (String) m.get("expect");
        if (decision != null && !DECISION.matcher(decision).matches() || (decision == null) != (expect == null)) {
            throw new IllegalStateException(where + ": a decision is D-<letters><number>, and comes with expect");
        }
        String skip = (String) m.get("skip");
        if (skip == null && m.get("sa_defect") != null) {
            skip = "sa_defect: " + m.get("sa_defect");
        }
        return new Case(file, id, sql, profiles, engine == null || engine.equals("auto") ? null : engine, skip,
                decision, expect);
    }

    /** Every run of {@code cases}: each case under each of its profiles, in order. */
    public static List<Run> runs(List<Case> cases) {
        List<Run> runs = new ArrayList<>();
        for (Case c : cases) {
            for (String p : c.profiles()) {
                runs.add(new Run(c, java.util.Objects.requireNonNull(IqProfile.named(p))));
            }
        }
        return runs;
    }

    // ------------------------------------------------------------------ the schema, the model and the data

    /** The tables of {@code schema.yaml}. */
    public static List<SqlParity.Table> schema(Path schemaFile) throws IOException {
        return SqlParity.Yaml.schema(Files.readString(schemaFile, StandardCharsets.UTF_8));
    }

    private static final Pattern SIZED = Pattern.compile("(char|varchar)\\((\\d+)\\)");
    private static final Pattern SCALED = Pattern.compile("(numeric|decimal)\\((\\d+),\\s*(\\d+)\\)");

    /** The Pure model of the schema: one database, each column at the relational type of its IQ type, a connection
     *  and a runtime. */
    public static String model(List<SqlParity.Table> tables, String connection) {
        StringBuilder m = new StringBuilder("###Relational\nDatabase " + DATABASE + "\n(\n");
        for (SqlParity.Table t : tables) {
            m.append("    Table ").append(t.name()).append("\n    (\n");
            for (int i = 0; i < t.columns().size(); i++) {
                SqlParity.Column c = t.columns().get(i);
                m.append("        ").append(c.name()).append(' ').append(relationalType(c.type()))
                        .append(i + 1 < t.columns().size() ? ",\n" : "\n");
            }
            m.append("    )\n");
        }
        m.append(")\n###Connection\nRelationalDatabaseConnection e2e::Conn\n{\n    ").append(connection)
                .append("\n}\n###Runtime\nRuntime ").append(RUNTIME).append("\n{\n    mappings: [];\n    connections: [ ")
                .append(DATABASE).append(": [ environment: e2e::Conn ] ];\n}\n");
        return m.toString();
    }

    /** An IQ type of the schema as the relational store spells it (the store type the island reads). */
    static String relationalType(String iqType) {
        String t = iqType.toLowerCase(Locale.ROOT).strip();
        Matcher sized = SIZED.matcher(t);
        if (sized.matches()) {
            return sized.group(1).toUpperCase(Locale.ROOT) + "(" + sized.group(2) + ")";
        }
        Matcher scaled = SCALED.matcher(t);
        if (scaled.matches()) {
            return scaled.group(1).toUpperCase(Locale.ROOT) + "(" + scaled.group(2) + "," + scaled.group(3) + ")";
        }
        return switch (t) {
            case "smallint", "bigint", "tinyint", "double", "real", "float", "date", "timestamp", "bit" ->
                    t.toUpperCase(Locale.ROOT);
            case "integer", "int" -> "INTEGER";
            default -> throw new IllegalStateException("no relational type for the schema's " + iqType);
        };
    }

    /** An IQ type of the schema as the lane's database declares it: a BIT is a boolean (the relational store's BIT is
     *  Pure's Boolean), a CHAR(n) a CHAR(n) (Postgres's bpchar; DuckDB's text). */
    private static String databaseType(String iqType, boolean postgres) {
        String t = relationalType(iqType);
        return switch (t) {
            case "BIT" -> "BOOLEAN";
            case "DOUBLE", "FLOAT" -> postgres ? "DOUBLE PRECISION" : "DOUBLE";
            case "TINYINT" -> "SMALLINT";
            default -> t.startsWith("NUMERIC") ? "DECIMAL" + t.substring("NUMERIC".length()) : t;
        };
    }

    /** Creates and fills the schema's tables: each value bound by its column's type, a quoted value read by it. */
    public static void seed(Connection c, List<SqlParity.Table> tables, boolean postgres) throws SQLException {
        for (SqlParity.Table t : tables) {
            StringBuilder ddl = new StringBuilder("CREATE TABLE " + t.name() + " (");
            for (int i = 0; i < t.columns().size(); i++) {
                SqlParity.Column col = t.columns().get(i);
                ddl.append(i > 0 ? ", " : "").append(col.name()).append(' ').append(databaseType(col.type(), postgres));
            }
            try (Statement s = c.createStatement()) {
                s.execute("DROP TABLE IF EXISTS " + t.name());
                s.execute(ddl.append(')').toString());
            }
            if (t.rows().isEmpty()) {
                continue;
            }
            String marks = String.join(", ", java.util.Collections.nCopies(t.columns().size(), "?"));
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO " + t.name() + " VALUES (" + marks + ")")) {
                for (List<Object> row : t.rows()) {
                    for (int i = 0; i < row.size(); i++) {
                        bind(ps, i + 1, row.get(i), relationalType(t.columns().get(i).type()));
                    }
                    ps.addBatch();
                }
                ps.executeBatch();
            }
        }
    }

    private static void bind(PreparedStatement ps, int i, @com.legend.base.Nullable Object v, String type)
            throws SQLException {
        if (v == null) {
            ps.setNull(i, java.sql.Types.NULL);
            return;
        }
        String text = v.toString();
        if (type.equals("BIT")) {
            ps.setBoolean(i, !text.equals("0"));
        } else if (type.equals("SMALLINT") || type.equals("INTEGER") || type.equals("TINYINT")) {
            ps.setInt(i, Integer.parseInt(text));
        } else if (type.equals("BIGINT")) {
            ps.setLong(i, Long.parseLong(text));
        } else if (type.startsWith("NUMERIC") || type.startsWith("DECIMAL")) {
            ps.setBigDecimal(i, new BigDecimal(text));
        } else if (type.equals("DOUBLE") || type.equals("FLOAT") || type.equals("REAL")) {
            ps.setDouble(i, Double.parseDouble(text));
        } else if (type.equals("DATE")) {
            ps.setDate(i, java.sql.Date.valueOf(text));
        } else if (type.equals("TIMESTAMP")) {
            ps.setTimestamp(i, java.sql.Timestamp.valueOf(text));
        } else {
            ps.setString(i, text);
        }
    }

    /** The names of the schema's tables, as the rewrite matches them. */
    public static Set<String> tableNames(List<SqlParity.Table> tables) {
        return SqlParity.tableNames(tables);
    }

    // ------------------------------------------------------------------ the rewrite

    /** {@code sql} with each schema table named in FROM, after JOIN or after a comma in FROM ({@code t}, {@code [t]},
     *  {@code T}) replaced by its Legend source, {@code tb('e2e::DB.t')}, aliased by the name as written when no alias
     *  follows. The text is read by {@link IqLexer} under the run's profile; text that does not lex is returned as it
     *  is, for the island to refuse. */
    public static String rewrite(String sql, Set<String> tables, IqProfile profile) {
        List<IqLexer.Token> tokens;
        try {
            tokens = IqLexer.tokenize(sql, profile);
        } catch (SqlSyntaxException e) {
            return sql;
        }
        StringBuilder out = new StringBuilder();
        int copied = 0;
        Deque<Boolean> inFrom = new ArrayDeque<>();
        inFrom.push(false);
        for (int i = 0; i < tokens.size(); i++) {
            IqLexer.Token t = tokens.get(i);
            if (t.isOp("(")) {
                inFrom.push(false);
                continue;
            }
            if (t.isOp(")")) {
                if (inFrom.size() > 1) {
                    inFrom.pop();
                }
                continue;
            }
            if (t.kind() == IqLexer.Kind.KEYWORD) {
                boolean was = Boolean.TRUE.equals(inFrom.pop());
                inFrom.push(t.is("from") || was && !CLAUSES.contains(t.text().toLowerCase(Locale.ROOT)));
                if (t.is("from") || t.is("join")) {
                    continue;
                }
            }
            if (i == 0) {
                continue;
            }
            IqLexer.Token prev = tokens.get(i - 1);
            boolean tablePlace = prev.is("from") || prev.is("join")
                    || prev.isOp(",") && Boolean.TRUE.equals(inFrom.peek());
            IqLexer.Token after = tokens.get(Math.min(i + 1, tokens.size() - 1));
            if (!tablePlace || !t.isName() || !tables.contains(t.text().toLowerCase(Locale.ROOT))
                    || after.isOp(".") || after.isOp("(")) {
                continue;
            }
            out.append(sql, copied, t.start()).append("tb('").append(DATABASE).append('.')
                    .append(t.text().toLowerCase(Locale.ROOT)).append("')");
            boolean aliased = after.is("as") || after.isName();
            if (!aliased) {
                out.append(" AS ").append(sql, t.start(), t.end());
            }
            copied = t.end();
        }
        return out.append(sql, copied, sql.length()).toString();
    }

    /** The keywords that end a FROM clause. */
    private static final Set<String> CLAUSES = Set.of("where", "group", "having", "order", "union", "except",
            "intersect", "window", "for", "option", "into", "select", "on", "using");

    /** The island a run is: {@code #IQ{...}#} under the default profile, {@code #IQ:<profile>{...}#} under another. */
    public static String island(Run run, Set<String> tables) {
        String sql = rewrite(run.c().sql(), tables, run.profile());
        return run.profile() == IqProfile.DEFAULT ? "#IQ{" + sql + "}#"
                : "#IQ:" + run.profile().spelling() + "{" + sql + "}#";
    }

    /** A run whose case pins an engine: the engine IQ runs the island in must be it (an island cannot choose one). */
    public static @com.legend.base.Nullable String engineMismatch(Run run, Set<String> tables) {
        String pinned = run.c().engine();
        if (pinned == null) {
            return null;
        }
        SqlAst.Statement statement;
        try {
            statement = IqParser.parse(rewrite(run.c().sql(), tables, run.profile()), run.profile());
        } catch (SqlSyntaxException e) {
            return null;
        }
        String engine = IqDialect.engineOf(statement).name().toLowerCase(Locale.ROOT);
        return engine.equals(pinned) ? null : "ENGINE: " + run.key() + " pins engine=" + pinned + ", and its island"
                + " runs in engine=" + engine;
    }

    // ------------------------------------------------------------------ one run's outcome

    /** One run in one lane: its island run through legend-lite, judged against its judge by SqlParity's rules. */
    public static SqlParity.Outcome outcome(Run run, IqAnswers.@com.legend.base.Nullable Snap snap,
            Set<String> tables, SqlParity.Legend legend) {
        if (run.c().skip() != null) {
            return new SqlParity.Outcome(SqlParity.Status.SKIP, java.util.Objects.requireNonNull(run.c().skip()));
        }
        if (snap == null) {
            return new SqlParity.Outcome(SqlParity.Status.ERROR, "no snapped answer (UNSNAPPED)");
        }
        SqlParity.Answer expected = IqAnswers.expected(run, snap);
        SqlParity.Grid actual;
        try {
            actual = SqlParity.grid(legend.run(island(run, tables)));
        } catch (com.legend.error.NotImplementedException e) {
            return expected instanceof SqlParity.Rejected ? new SqlParity.Outcome(SqlParity.Status.BOTH_ERROR,
                    oneLine(e)) : new SqlParity.Outcome(SqlParity.Status.REFUSED, oneLine(e));
        } catch (Exception | StackOverflowError e) {
            return expected instanceof SqlParity.Rejected ? new SqlParity.Outcome(SqlParity.Status.BOTH_ERROR,
                    oneLine(e)) : new SqlParity.Outcome(SqlParity.Status.ERROR, oneLine(e));
        }
        return switch (expected) {
            case SqlParity.Rejected r -> new SqlParity.Outcome(SqlParity.Status.BUG, "SQL Anywhere rejects it ("
                    + r.message() + ") and legend-lite answered " + actual.rows().size() + " rows");
            case SqlParity.Rows rows -> {
                List<String> diffs = compare(run, rows.grid(), actual);
                yield diffs.isEmpty() ? new SqlParity.Outcome(SqlParity.Status.PASS, "")
                        : new SqlParity.Outcome(SqlParity.Status.FAIL, String.join("; ", diffs));
            }
        };
    }

    /** SqlParity's comparison, the run's ORDER BY read by IQ's reader: rows in order when the query orders them (the
     *  order among rows tied on keys that are all output columns is the database's), else as a set. */
    private static List<String> compare(Run run, SqlParity.Grid expected, SqlParity.Grid actual) {
        List<SqlAst.SortItem> orderBy;
        try {
            SqlAst.Statement st = IqParser.parse(run.c().sql(), run.profile());
            orderBy = st instanceof SqlAst.Select s ? s.orderBy() : List.of();
        } catch (SqlSyntaxException e) {
            boolean ordered = run.c().sql().toUpperCase(Locale.ROOT).contains("ORDER BY");
            return SqlParity.compare(ordered, null, expected, actual);
        }
        if (orderBy.isEmpty()) {
            return SqlParity.compare(false, null, expected, actual);
        }
        List<Integer> keys = new ArrayList<>();
        for (SqlAst.SortItem item : orderBy) {
            int found = -1;
            if (item.expr() instanceof SqlAst.IntegerLit n && n.value().signum() > 0
                    && n.value().intValue() <= expected.columns().size()) {
                found = n.value().intValue() - 1;
            } else if (item.expr() instanceof SqlAst.ColumnRef ref && ref.parts().size() == 1) {
                for (int i = 0; i < expected.columns().size(); i++) {
                    if (expected.columns().get(i).equalsIgnoreCase(ref.parts().get(0).text())) {
                        found = found == -1 ? i : -2;
                    }
                }
            }
            if (found < 0) {
                return SqlParity.compare(true, null, expected, actual);
            }
            keys.add(found);
        }
        return SqlParity.compare(true, keys, expected, actual);
    }

    private static String oneLine(Throwable e) {
        String m = e.getClass().getSimpleName() + ": " + e.getMessage();
        m = m.replace('\n', ' ').replace('\t', ' ');
        return m.length() > 300 ? m.substring(0, 300) + "..." : m;
    }

    // ------------------------------------------------------------------ the ledger

    private static final String LEDGER_HEADER = "# the #IQ{...}# island's parity cases (iq-parity/lite), each case's"
            + " status per profile and lane: the ratchet IqParityTest and\n# IqParityPostgresTest hold"
            + " (core/src/test/java/com/legend/testcases/IqParity.java; the statuses are SqlParity's)\n";

    /** Each run's outcome against the ledger's column for {@code lane} (SqlParity's ratchet: REGRESSION, FIX
     *  DETECTED, STATUS MISMATCH, NEW, GONE); with {@code update}, the ledger's column is written instead. */
    public static List<String> ratchet(List<Run> runs, Map<String, SqlParity.Outcome> outcomes, Path ledger,
            String lane, @com.legend.base.Nullable Path update) throws IOException {
        List<SqlParity.Case> cases = new ArrayList<>();
        Map<String, SqlParity.Outcome> byKey = new LinkedHashMap<>();
        for (Run r : runs) {
            SqlParity.Case c = r.asSqlParityCase();
            cases.add(c);
            byKey.put(SqlParity.key(c), outcomes.get(r.key()));
        }
        return SqlParity.ratchet("the #IQ{...}# island's parity cases", "iq-parity", LEDGER_HEADER, cases, byKey,
                ledger, lane, update);
    }
}
