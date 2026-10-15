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
            "decision", "expect", "params", "settings");

    private static final Pattern DECISION = Pattern.compile("D-[A-Z]+[0-9]+[a-z]?");

    private IqParity() {
    }

    // ------------------------------------------------------------------ the cases

    /** A case: its file (relative to iq-parity/, {@code lite/...}), its id, its SQL, its profiles, the engine it pins
     *  (null for auto), why it is not run (a skip or an SA defect), and the provisional decision that judges it, with
     *  the decided rows (one line of JSON). */
    public record Case(String file, String id, String sql, List<String> profiles, @com.legend.base.Nullable String engine,
            @com.legend.base.Nullable String skip, @com.legend.base.Nullable String decision,
            @com.legend.base.Nullable String expect, @com.legend.base.Nullable String params,
            @com.legend.base.Nullable String settings) {

        /** A case without settings (IQ island, 2026-10-14, the options framework: {@code settings}, IQ's settings by
         *  level as written, {@code database.<name>=<value>, query.<name>=<value>, ...}). */
        public Case(String file, String id, String sql, List<String> profiles,
                @com.legend.base.Nullable String engine, @com.legend.base.Nullable String skip,
                @com.legend.base.Nullable String decision, @com.legend.base.Nullable String expect,
                @com.legend.base.Nullable String params) {
            this(file, id, sql, profiles, engine, skip, decision, expect, params, null);
        }

        /** A case that binds no variables. */
        public Case(String file, String id, String sql, List<String> profiles,
                @com.legend.base.Nullable String engine, @com.legend.base.Nullable String skip,
                @com.legend.base.Nullable String decision, @com.legend.base.Nullable String expect) {
            this(file, id, sql, profiles, engine, skip, decision, expect, null, null);
        }

        /** The case's key in the answers file: {@code lite/<file>/<id>}. */
        public String key() {
            return file.replaceAll("\\.yaml$", "") + "/" + id;
        }
    }

    /** IQ island, 2026-10-14 (M1e): a variable a case binds ({@code params}: {@code name = value; ...}), for its host
     *  variables ({@code @name}, {@code :name}): its name, its Pure type, its value as a Pure literal (a {@code let}
     *  before the island), and the SQL Anywhere variable the oracle holds it in (tools/iq-oracle/snap.py reads the same
     *  spelling): its type and its value as SQL Anywhere's constant, null for a NULL. IQ island, 2026-10-14 (review 5's
     *  M5): the oracle binds a real variable of that type on the case's connection (it wrote the constant into the SQL
     *  before, which SQL Anywhere reads otherwise in measured places); {@code null:<type>} is a NULL, a Pure empty value
     *  of that type. */
    public record Param(String name, String type, String pure, String saType, @com.legend.base.Nullable String saValue) {

        /** Whether the value is a NULL (an empty Pure value). */
        public boolean isNull() {
            return saValue == null;
        }
    }

    private static final Pattern PARAM = Pattern.compile("([A-Za-z_][A-Za-z0-9_]*)\\s*=\\s*(.+)");
    private static final Pattern PARAM_INTEGER = Pattern.compile("-?[0-9]+");
    private static final Pattern PARAM_FLOAT = Pattern.compile("-?[0-9]+\\.[0-9]+");
    private static final Pattern PARAM_DECIMAL = Pattern.compile("-?[0-9]+\\.([0-9]+)D");
    private static final Pattern PARAM_DATE = Pattern.compile("%([0-9]{4}-[0-9]{2}-[0-9]{2})");
    private static final Pattern PARAM_TIMESTAMP = Pattern.compile(
            "%([0-9]{4}-[0-9]{2}-[0-9]{2})T([0-9]{2}:[0-9]{2}:[0-9]{2}(\\.[0-9]{1,6})?)");
    private static final Pattern PARAM_NULL = Pattern.compile(
            "null:(Integer|Float|String|StrictDate|DateTime|Decimal\\(38,([0-9]+)\\))");

    /** A case's {@code params}, in order: {@code name = 5} (an Integer, SQL Anywhere's BIGINT), {@code 1.5} (a Float, a
     *  DOUBLE), {@code 1.50D} (a Decimal of scale 2, a NUMERIC(38,2)), {@code 'text'} (a String, a LONG VARCHAR; no
     *  quote or backslash inside, which the two readers would read otherwise), {@code %2026-03-04} (a StrictDate, a
     *  DATE), {@code %2026-03-04T05:06:07.123456} (a DateTime, a TIMESTAMP), {@code null:Integer} (a NULL of that type:
     *  Integer, Float, String, StrictDate, DateTime or Decimal(38,s)). Names are distinct ignoring case (SQL Anywhere's
     *  variable names are). */
    public static List<Param> params(@com.legend.base.Nullable String params) {
        List<Param> out = new ArrayList<>();
        if (params == null) {
            return out;
        }
        Set<String> seen = new java.util.HashSet<>();
        for (String entry : params.split(";")) {
            if (entry.strip().isEmpty()) {
                continue;
            }
            Matcher m = PARAM.matcher(entry.strip());
            if (!m.matches()) {
                throw new IllegalStateException("a param is name = value, not " + entry);
            }
            String name = m.group(1);
            if (!seen.add(name.toLowerCase(Locale.ROOT))) {
                throw new IllegalStateException("the param " + name + " twice (SQL Anywhere's variable names ignore"
                        + " case)");
            }
            String v = m.group(2).strip();
            Matcher d;
            if (PARAM_INTEGER.matcher(v).matches()) {
                out.add(new Param(name, "Integer", v, "BIGINT", v));
            } else if (PARAM_FLOAT.matcher(v).matches()) {
                out.add(new Param(name, "Float", v, "DOUBLE", "CAST(" + v + " AS DOUBLE)"));
            } else if ((d = PARAM_DECIMAL.matcher(v)).matches()) {
                int scale = d.group(1).length();
                // IQ island, 2026-10-14 (review 5's m6): Pure types a negative decimal literal (-0.50D, a minus of
                // 0.50D) a plain Decimal, which the island refuses: the engine-neutrality check reads it so too
                out.add(new Param(name, v.startsWith("-") ? "Decimal" : "Decimal(38," + scale + ")", v,
                        "NUMERIC(38," + scale + ")", v.substring(0, v.length() - 1)));
            } else if (v.length() >= 2 && v.startsWith("'") && v.endsWith("'")
                    && v.indexOf('\'', 1) == v.length() - 1) {
                if (v.indexOf('\\') >= 0) {
                    throw new IllegalStateException("a string param holds no backslash (SQL Anywhere reads one as an"
                            + " escape, Pure's let does not), not " + v);
                }
                out.add(new Param(name, "String", v, "LONG VARCHAR", v));
            } else if ((d = PARAM_DATE.matcher(v)).matches()) {
                out.add(new Param(name, "StrictDate", v, "DATE", "CAST('" + d.group(1) + "' AS DATE)"));
            } else if ((d = PARAM_TIMESTAMP.matcher(v)).matches()) {
                out.add(new Param(name, "DateTime", v, "TIMESTAMP", "CAST('" + d.group(1) + " " + d.group(2)
                        + "' AS TIMESTAMP)"));
            } else if ((d = PARAM_NULL.matcher(v)).matches()) {
                String pure = d.group(2) != null ? "Decimal" : d.group(1);
                String sa = switch (d.group(1)) {
                    case "Integer" -> "BIGINT";
                    case "Float" -> "DOUBLE";
                    case "String" -> "LONG VARCHAR";
                    case "StrictDate" -> "DATE";
                    case "DateTime" -> "TIMESTAMP";
                    default -> "NUMERIC(38," + d.group(2) + ")";
                };
                out.add(new Param(name, d.group(2) != null ? "Decimal(38," + d.group(2) + ")" : pure,
                        "[]->cast(@" + pure + ")", sa, null));
            } else {
                throw new IllegalStateException("a param's value is an integer, a float, a decimal (1.5D), a 'string',"
                        + " a %date, a %date-time or null:<type>, not " + v);
            }
        }
        return out;
    }

    /** One run of a case: the case under one of its profiles. Its key in the ledger is the case's,
     *  {@code @} and the profile. */
    public record Run(Case c, IqProfile profile) {

        public String key() {
            return c.key() + "@" + profile.spelling();
        }

        /** IQ island, 2026-10-14 (the options framework): the settings the run's SQL is read under -- its profile's,
         *  with its case's settings given ({@link IqParity#settings}, {@code IqSettings.resolve}) -- before the SET
         *  statements its SQL may begin with; the profile's when the settings are IQ's error (the run raises it). */
        public com.legend.protocol.sql.iq.IqSettings settings() {
            if (c.settings() == null) {
                return profile.settings();
            }
            try {
                return com.legend.protocol.sql.iq.IqSettings.resolve(profile, IqParity.settings(
                        java.util.Objects.requireNonNull(c.settings())));
            } catch (com.legend.protocol.sql.SqlTranslationException e) {
                return profile.settings();
            }
        }

        /** The run's SELECT as the island reads it: its text with the SET statements before it blanked, and the
         *  settings they make; {@link SqlSyntaxException} or {@code SqlTranslationException} when they are not read. */
        public com.legend.protocol.sql.iq.IqSetPrefix.Prefix select(String sql) {
            com.legend.protocol.sql.iq.IqSetPrefix.Prefix p = com.legend.protocol.sql.iq.IqSetPrefix.read(sql,
                    settings());
            return p != null ? p : new com.legend.protocol.sql.iq.IqSetPrefix.Prefix(settings(), sql);
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
        String params = (String) m.get("params");
        params(params);
        String settings = (String) m.get("settings");
        if (settings != null) {
            settings(settings);
        }
        return new Case(file, id, sql, profiles, engine == null || engine.equals("auto") ? null : engine, skip,
                decision, expect, params, settings);
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
     *  Pure's Boolean), a CHAR(n) a VARCHAR(n), holding the value as written (DuckDB's CHAR is its text; Postgres's
     *  bpchar would pad it, which is the IQ engine's rule to apply -- D-S1, the island's read -- not the store's). */
    private static String databaseType(String iqType, boolean postgres) {
        String t = relationalType(iqType);
        if (t.startsWith("CHAR(")) {
            return "VARCHAR" + t.substring("CHAR".length());
        }
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
        return rewrite(sql, tables, profile.settings());
    }

    /** {@link #rewrite(String, Set, IqProfile)} under {@code settings} (IQ island, 2026-10-14, the options framework:
     *  a run's, its case's settings given over its profile's). */
    public static String rewrite(String sql, Set<String> tables, com.legend.protocol.sql.iq.IqSettings settings) {
        List<IqLexer.Token> tokens;
        try {
            tokens = IqLexer.tokenize(sql, settings);
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
                // IQ island, 2026-10-14 (M1b): a parenthesis at a table's place (a parenthesized join, a derived table)
                // is in the FROM clause too; any other (a call's, a subquery's in a condition) is not
                IqLexer.Token before = i == 0 ? t : tokens.get(i - 1);
                inFrom.push(Boolean.TRUE.equals(inFrom.peek()) && (before.is("from") || before.is("join")
                        || before.isOp(",") || before.isOp("(")));
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
                inFrom.push(t.is("from") || t.is("join") || was && !CLAUSES.contains(t.text().toLowerCase(Locale.ROOT)));
                if (t.is("from") || t.is("join")) {
                    continue;
                }
            }
            if (i == 0) {
                continue;
            }
            IqLexer.Token prev = tokens.get(i - 1);
            boolean tablePlace = prev.is("from") || prev.is("join")
                    || (prev.isOp(",") || prev.isOp("(")) && Boolean.TRUE.equals(inFrom.peek());
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

    /** The keywords that end a FROM clause (IQ island, 2026-10-14, M1b: not ON, whose condition a comma or a JOIN
     *  ends, both back in the FROM clause). */
    private static final Set<String> CLAUSES = Set.of("where", "group", "having", "order", "union", "except",
            "intersect", "window", "for", "option", "into", "select", "using");

    /** The island a run is: {@code #IQ{...}#} under the default profile, {@code #IQ:<profile>{...}#} under another. */
    public static String island(Run run, Set<String> tables) {
        String sql = rewrite(run.c().sql(), tables, run.profile());
        String island = run.profile() == IqProfile.DEFAULT ? "#IQ{" + sql + "}#"
                : "#IQ:" + run.profile().spelling() + "{" + sql + "}#";
        // IQ island, 2026-10-14 (M1e): the variables a case binds, each a let before the island
        List<Param> params = params(run.c().params());
        if (params.isEmpty()) {
            return island;
        }
        StringBuilder lets = new StringBuilder("|");
        params.forEach(p -> lets.append("let ").append(p.name()).append(" = ").append(p.pure()).append("; "));
        return lets.append(island).append(';').toString();
    }

    /** A run whose case pins an engine: the engine IQ runs the island in must be it (an island cannot choose one). */
    public static @com.legend.base.Nullable String engineMismatch(Run run, Set<String> tables) {
        String pinned = run.c().engine();
        if (pinned == null) {
            return null;
        }
        SqlAst.Statement statement;
        com.legend.protocol.sql.iq.IqSetPrefix.Prefix select;
        try {
            select = run.select(rewrite(run.c().sql(), tables, run.settings()));
            statement = IqParser.parse(select.select(), select.settings());
        } catch (SqlSyntaxException | com.legend.protocol.sql.SqlTranslationException e) {
            return null;
        }
        String engine = IqDialect.engineOf(statement, select.settings()).name().toLowerCase(Locale.ROOT);
        return engine.equals(pinned) ? null : "ENGINE: " + run.key() + " pins engine=" + pinned + ", and its island"
                + " runs in engine=" + engine;
    }

    /** A run whose case SQL Anywhere judges (no decision, no pinned engine) over tables, translated under the IQ
     *  engine's rules: those must be SQL Anywhere's too (the study's §6.3, engine neutrality), else the case names the
     *  decision that judges it, or pins {@code engine: sa}. A translation the IQ engine's rules refuse is never judged
     *  by SQL Anywhere, so it is no breach; one they answer and SQL Anywhere's rules do not, or answer otherwise, is. */
    public static @com.legend.base.Nullable String engineNeutrality(Run run, List<SqlParity.Table> tables,
            Set<String> names) {
        Case c = run.c();
        if ("iq".equals(c.engine()) && c.decision() == null && c.skip() == null) {
            // IQ island, 2026-10-14 (the second review's m2): a pin to the IQ engine would escape this guard while
            // SQL Anywhere still judges the case
            return "ENGINE NEUTRALITY: " + run.key() + " pins engine=iq and names no decision: SQL Anywhere judges it,"
                    + " so drop the pin (the guard then checks it translates alike under both engines' rules), or name"
                    + " the decision that judges it";
        }
        if (c.engine() != null || c.decision() != null || c.skip() != null) {
            return null;
        }
        String sql = rewrite(c.sql(), names, run.settings());
        SqlAst.Statement statement;
        com.legend.protocol.sql.iq.IqSetPrefix.Prefix select;
        try {
            select = run.select(sql);
            statement = IqParser.parse(select.select(), select.settings());
        } catch (SqlSyntaxException | com.legend.protocol.sql.SqlTranslationException e) {
            return null;
        }
        if (IqDialect.engineOf(statement, select.settings()) != IqDialect.Engine.IQ) {
            return null;
        }
        List<Param> params = params(c.params());
        String iq = pureOf(sql, tables, IqDialect.of(run.settings(), IqDialect.Engine.IQ), params);
        if (iq == null) {
            return null;
        }
        String sa = pureOf(sql, tables, IqDialect.of(run.settings(), IqDialect.Engine.SA), params);
        return iq.equals(sa) ? null : "ENGINE NEUTRALITY: " + run.key() + " translates otherwise under the IQ engine's"
                + " rules than under SQL Anywhere's, which judges it: name its decision, or pin engine: sa";
    }

    /** The skip a case takes when its answer depends on the order IQ evaluates a search condition's operands in (the
     *  study's provisional decision D-E2). */
    public static final String PLAN_DEPENDENT = "plan-dependent run-time error (D-E2)";

    /** A run whose WHERE puts an operation that can raise (arithmetic or NULLIF over a column) beside AND or OR
     *  (outside a CASE's or an IF's condition): SQL Anywhere evaluates such an operand, or not, as its plan has it, and
     *  lite's databases short-circuit, so the answer is no evidence either way (D-E2). Such a case is skipped
     *  ({@link #PLAN_DEPENDENT}) or rewritten; null for any other. */
    public static @com.legend.base.Nullable String evaluationOrder(Run run) {
        if (run.c().skip() != null) {
            return null;
        }
        SqlAst.Statement statement;
        try {
            com.legend.protocol.sql.iq.IqSetPrefix.Prefix select = run.select(run.c().sql());
            statement = IqParser.parse(select.select(), select.settings());
        } catch (SqlSyntaxException | com.legend.protocol.sql.SqlTranslationException e) {
            return null;
        }
        // IQ island, 2026-10-14 (M1b): every query's WHERE (a subquery's, a branch's, a derived table's) and every
        // join's ON is such a search condition; (review 3's M4) and every HAVING
        List<SqlAst.Expr> conditions = new ArrayList<>();
        for (SqlAst.Statement q : IqDialect.queries(statement)) {
            if (q instanceof SqlAst.Select s) {
                if (s.where() != null) {
                    conditions.add(java.util.Objects.requireNonNull(s.where()));
                }
                if (s.having() != null) {
                    conditions.add(java.util.Objects.requireNonNull(s.having()));
                }
                s.from().forEach(f -> onConditions(f, conditions));
            }
        }
        if (conditions.stream().noneMatch(IqParity::raisesBesideAndOr)) {
            return null;
        }
        return "EVALUATION ORDER: " + run.key() + " puts an operation that can raise beside AND or OR in a WHERE, an"
                + " ON or a HAVING: whether IQ raises depends on its plan (D-E2), so skip it (skip: " + PLAN_DEPENDENT
                + ") or rewrite it";
    }

    private static void onConditions(SqlAst.FromItem f, List<SqlAst.Expr> out) {
        if (f instanceof SqlAst.Join j) {
            if (j.on() != null) {
                out.add(java.util.Objects.requireNonNull(j.on()));
            }
            onConditions(j.left(), out);
            onConditions(j.right(), out);
        }
    }

    /** Whether a search condition has an AND or OR (outside a CASE) one of whose operands holds an operation that
     *  can raise. */
    private static boolean raisesBesideAndOr(SqlAst.Expr e) {
        return switch (e) {
            case SqlAst.Binary b when b.op() == SqlAst.BinaryOp.AND || b.op() == SqlAst.BinaryOp.OR ->
                    canRaise(b.left()) || canRaise(b.right()) || raisesBesideAndOr(b.left())
                            || raisesBesideAndOr(b.right());
            case SqlAst.Unary u when u.op() == SqlAst.UnaryOp.NOT -> raisesBesideAndOr(u.operand());
            // IQ island, 2026-10-14 (M1a): a truth test's condition is the condition the AND or OR stands in
            case SqlAst.IsTruth t -> raisesBesideAndOr(t.operand());
            default -> false;
        };
    }

    /** Whether {@code e} holds integer arithmetic, a minus or NULLIF over a column (a CASE's or an IF's branches
     *  included: IQ evaluates the one it takes). */
    private static boolean canRaise(SqlAst.Expr e) {
        boolean here = switch (e) {
            case SqlAst.Binary b -> switch (b.op()) {
                case PLUS, MINUS, TIMES, DIVIDE, MODULO -> readsAColumn(e);
                default -> false;
            };
            case SqlAst.Unary u -> u.op() == SqlAst.UnaryOp.MINUS && readsAColumn(e);
            // IQ island, 2026-10-14 (M1d): the functions and the CASTs that can raise IQ's errors over a column
            case SqlAst.FunctionCall f -> RAISING_FUNCTIONS.contains(f.name().get(f.name().size() - 1).text()
                    .toLowerCase(Locale.ROOT)) && readsAColumn(e);
            case SqlAst.Cast c -> readsAColumn(e);
            default -> false;
        };
        return here || subexpressions(e).stream().anyMatch(IqParity::canRaise);
    }

    /** The functions whose result can be IQ's run-time error (NULLIF's conversion; M1d's: SUBSTRING's negative length,
     *  MOD's zero divisor, ABS's and ROUND's overflow, the IQ engine's domain errors; review 3's M4: SUM's and AVG's
     *  overflow, D-E3). */
    private static final Set<String> RAISING_FUNCTIONS = Set.of("nullif", "substring", "substr", "mod", "remainder",
            "abs", "round", "sqrt", "exp", "ln", "log", "log10", "sum", "avg");

    private static boolean readsAColumn(SqlAst.Expr e) {
        return e instanceof SqlAst.ColumnRef || subexpressions(e).stream().anyMatch(IqParity::readsAColumn);
    }

    /** The expressions directly inside {@code e}, for the forms IQ's reader makes. */
    private static List<SqlAst.Expr> subexpressions(SqlAst.Expr e) {
        return switch (e) {
            case SqlAst.Binary b -> List.of(b.left(), b.right());
            case SqlAst.Unary u -> List.of(u.operand());
            case SqlAst.IsNull n -> List.of(n.operand());
            // IQ island, 2026-10-14 (M1a): the predicates IQ's reader now makes
            case SqlAst.IsTruth t -> List.of(t.operand());
            case SqlAst.IsDistinct d -> List.of(d.left(), d.right());
            case SqlAst.Between b -> List.of(b.operand(), b.low(), b.high());
            case SqlAst.InList in -> {
                List<SqlAst.Expr> all = new ArrayList<>(List.of(in.operand()));
                all.addAll(in.values());
                yield all;
            }
            case SqlAst.Like l -> l.escape() == null ? List.of(l.operand(), l.pattern())
                    : List.of(l.operand(), l.pattern(), java.util.Objects.requireNonNull(l.escape()));
            case SqlAst.FunctionCall f -> f.args();
            // IQ island, 2026-10-14 (M1b): a subquery's own expressions are its query's
            case SqlAst.InSubquery q -> List.of(q.operand());
            case SqlAst.Quantified q -> List.of(q.left());
            case SqlAst.Case c -> {
                List<SqlAst.Expr> all = new ArrayList<>();
                if (c.operand() != null) {
                    all.add(java.util.Objects.requireNonNull(c.operand()));
                }
                for (SqlAst.When w : c.whens()) {
                    all.add(w.condition());
                    all.add(w.result());
                }
                if (c.otherwise() != null) {
                    all.add(java.util.Objects.requireNonNull(c.otherwise()));
                }
                yield all;
            }
            // IQ island, 2026-10-14 (M1d): CAST and CONVERT
            case SqlAst.Cast c -> List.of(c.operand());
            default -> List.of();
        };
    }

    /** {@code sql}'s Pure under {@code dialect}, the schema's tables its sources; null when the dialect refuses it,
     *  the refusal's kind and message when it rejects it. */
    private static @com.legend.base.Nullable String pureOf(String sql, List<SqlParity.Table> tables,
            IqDialect dialect, List<Param> params) {
        try {
            return com.legend.protocol.PureComposer.valueSpecification(com.legend.protocol.sql.SqlToPure.translate(sql,
                    sources(tables, params), dialect), com.legend.protocol.PureComposer.Style.STANDARD);
        } catch (com.legend.protocol.sql.SqlTranslationException e) {
            return e.kind() == com.legend.protocol.sql.SqlTranslationException.Kind.UNSUPPORTED ? null
                    : e.kind() + ": " + e.getMessage();
        }
    }

    /**
     * IQ island, 2026-10-14 (the options framework): the query a run is, as the lanes run it -- its island; for a case
     * with settings, which reach no island yet (their Pure plumbing, tagged values of the IQ profile on a Database and a
     * function, lands separately), the translation of its SQL under them ({@code IqSettings.resolve}) done here as the
     * island's seam does it (SqlIslands: the schema's tables its sources, each column's store type, the case's
     * variables), printed, its variables let before it. A translation's refusal or error is thrown as the
     * {@code SqlTranslationException} it is.
     */
    public static String query(Run run, List<SqlParity.Table> schema, Set<String> tables) {
        if (run.c().settings() == null) {
            return island(run, tables);
        }
        com.legend.protocol.sql.iq.IqSettings settings = com.legend.protocol.sql.iq.IqSettings.resolve(run.profile(),
                settings(java.util.Objects.requireNonNull(run.c().settings())));
        List<Param> params = params(run.c().params());
        String pure = parsedTds(com.legend.protocol.PureComposer.valueSpecification(
                com.legend.protocol.sql.SqlToPure.translate(rewrite(run.c().sql(), tables, settings),
                        sources(schema, params), IqDialect.of(settings)), com.legend.protocol.PureComposer.Style.STANDARD));
        if (params.isEmpty()) {
            return pure;
        }
        StringBuilder lets = new StringBuilder("|");
        params.forEach(p -> lets.append("let ").append(p.name()).append(" = ").append(p.pure()).append("; "));
        return lets.append(pure).append(';').toString();
    }

    /** {@code pure} with each TDS literal the printer writes {@code #TDS{...}#} written {@code #TDS...#}, the spelling
     *  the parser reads back as the same rows (it reads the braced one's braces as rows: a FROM-less SELECT's one row
     *  read back as three). */
    private static String parsedTds(String pure) {
        StringBuilder out = new StringBuilder();
        int at = 0;
        for (int i = pure.indexOf("#TDS{"); i >= 0; i = pure.indexOf("#TDS{", at)) {
            int end = pure.indexOf("}#", i);
            out.append(pure, at, i).append("#TDS").append(pure, i + "#TDS{".length(), end).append('#');
            at = end + 2;
        }
        return out.append(pure.substring(at)).toString();
    }

    /**
     * IQ island, 2026-10-14 (the options framework): a case's {@code settings}, as {@code IqSettings.resolve} takes
     * them -- each {@code database.<name>=<value>} (a Database element's tagged value: a database property, a PUBLIC
     * option, CommProtocol) or {@code query.<name>=<value>} (the enclosing function's: the connection's temporary
     * value), IQ's names, a value as text (quoted, {@code ''} a quote in it, or bare up to the next comma), separated by
     * commas.
     */
    public static List<com.legend.protocol.sql.iq.IqSettings.Setting> settings(String text) {
        List<com.legend.protocol.sql.iq.IqSettings.Setting> out = new ArrayList<>();
        int i = 0;
        int n = text.length();
        while (true) {
            while (i < n && text.charAt(i) == ' ') {
                i++;
            }
            int dot = text.indexOf('.', i);
            int eq = text.indexOf('=', i);
            if (dot < 0 || eq < dot) {
                throw new IllegalStateException("a setting is database.<name>=<value> or query.<name>=<value>: "
                        + text.substring(i));
            }
            String level = text.substring(i, dot).strip().toLowerCase(Locale.ROOT);
            String name = text.substring(dot + 1, eq).strip();
            if (!level.equals("database") && !level.equals("query") || name.isEmpty()) {
                throw new IllegalStateException("a setting is database.<name>=<value> or query.<name>=<value>: "
                        + text.substring(i));
            }
            i = eq + 1;
            while (i < n && text.charAt(i) == ' ') {
                i++;
            }
            StringBuilder value = new StringBuilder();
            if (i < n && text.charAt(i) == '\'') {
                i++;
                while (true) {
                    if (i >= n) {
                        throw new IllegalStateException("a setting's quoted value is not closed: " + text);
                    }
                    if (text.charAt(i) == '\'' && i + 1 < n && text.charAt(i + 1) == '\'') {
                        value.append('\'');
                        i += 2;
                    } else if (text.charAt(i) == '\'') {
                        i++;
                        break;
                    } else {
                        value.append(text.charAt(i++));
                    }
                }
            } else {
                while (i < n && text.charAt(i) != ',') {
                    value.append(text.charAt(i++));
                }
                if (value.toString().strip().isEmpty()) {
                    throw new IllegalStateException("a setting has a value: " + text);
                }
            }
            out.add(new com.legend.protocol.sql.iq.IqSettings.Setting(level.equals("database")
                    ? com.legend.protocol.sql.iq.IqSettings.Level.DATABASE
                    : com.legend.protocol.sql.iq.IqSettings.Level.QUERY, name, value.toString().strip()));
            while (i < n && text.charAt(i) == ' ') {
                i++;
            }
            if (i >= n) {
                return out;
            }
            if (text.charAt(i) != ',') {
                throw new IllegalStateException("',' expected between settings: " + text.substring(i));
            }
            i++;
        }
    }

    /** The translator's sources for a run: the schema's tables (in the model's default schema), each column of its
     *  Pure type and store type, and the case's variables. */
    private static com.legend.protocol.sql.SqlToPure.Sources sources(List<SqlParity.Table> tables,
            List<Param> params) {
        // IQ island, 2026-10-14 (M1e): the model's tables (in its default schema) and the case's variables
        return new com.legend.protocol.sql.SqlToPure.Sources() {
            @Override
            public void columns(com.legend.protocol.spec.ValueSpecification source,
                    com.legend.protocol.sql.SqlToPure.Column column) {
                String printed = com.legend.protocol.PureComposer.valueSpecification(source,
                        com.legend.protocol.PureComposer.Style.STANDARD);
                String table = printed.substring(printed.lastIndexOf('.') + 1, printed.lastIndexOf("}#"));
                for (SqlParity.Table t : tables) {
                    if (t.name().equals(table)) {
                        t.columns().forEach(col -> column.column(col.name(), pureType(col.type()), true,
                                relationalType(col.type())));
                    }
                }
            }

            @Override
            public void tables(com.legend.protocol.sql.SqlToPure.Table table) {
                tables.forEach(t -> table.table(DATABASE, "default", t.name()));
            }

            @Override
            public boolean variable(String name, com.legend.protocol.sql.SqlToPure.Scoped type) {
                for (Param p : params) {
                    if (p.name().equals(name)) {
                        type.variable(p.type(), p.isNull(), false);
                        return true;
                    }
                }
                return false;
            }

            @Override
            public void variables(java.util.function.Consumer<String> names) {
                params.forEach(p -> names.accept(p.name()));
            }
        };
    }

    /** The Pure type of a schema column of IQ type {@code iqType}, as the typer reports it to the island. */
    private static String pureType(String iqType) {
        String t = relationalType(iqType);
        if (t.startsWith("CHAR") || t.startsWith("VARCHAR")) {
            return "String";
        }
        if (t.startsWith("NUMERIC") || t.startsWith("DECIMAL")) {
            return "Decimal";
        }
        return switch (t) {
            case "BIT" -> "Boolean";
            case "DOUBLE", "FLOAT", "REAL" -> "Float";
            case "DATE" -> "StrictDate";
            case "TIMESTAMP" -> "DateTime";
            default -> "Integer";
        };
    }

    // ------------------------------------------------------------------ one run's outcome

    /** One run in one lane: its island run through legend-lite, judged against its judge by this harness's exact
     *  comparison ({@link #compare}). An error is a both-error only when it is the island's own (its syntax error, its
     *  refusal, IQ's error it raises) or the database's; any other exception in legend-lite is an ERROR. */
    public static SqlParity.Outcome outcome(Run run, IqAnswers.@com.legend.base.Nullable Snap snap,
            List<SqlParity.Table> schema, Set<String> tables, SqlParity.Legend legend) {
        if (run.c().skip() != null) {
            return new SqlParity.Outcome(SqlParity.Status.SKIP, java.util.Objects.requireNonNull(run.c().skip()));
        }
        if (snap == null) {
            return new SqlParity.Outcome(SqlParity.Status.ERROR, "no snapped answer (UNSNAPPED)");
        }
        SqlParity.Answer expected = IqAnswers.expected(run, snap);
        com.legend.exec.ExecutionResult result;
        try {
            result = legend.run(query(run, schema, tables));
        } catch (com.legend.protocol.sql.SqlTranslationException e) {
            // IQ island, 2026-10-14 (the options framework): a case with settings, translated here: a refusal by
            // name, or the island's own error (its syntax error, IQ's error it raises)
            if (e.kind() == com.legend.protocol.sql.SqlTranslationException.Kind.UNSUPPORTED) {
                return expected instanceof SqlParity.Rejected r ? bothError(e, r)
                        : new SqlParity.Outcome(SqlParity.Status.REFUSED, oneLine(e));
            }
            return expected instanceof SqlParity.Rejected r ? bothError(e, r)
                    : new SqlParity.Outcome(SqlParity.Status.ERROR, oneLine(e));
        } catch (com.legend.error.NotImplementedException e) {
            return expected instanceof SqlParity.Rejected r ? bothError(e, r)
                    : new SqlParity.Outcome(SqlParity.Status.REFUSED, oneLine(e));
        } catch (Exception | StackOverflowError e) {
            if (!liteError(e)) {
                return new SqlParity.Outcome(SqlParity.Status.ERROR, "a Java exception in legend-lite: " + oneLine(e));
            }
            return expected instanceof SqlParity.Rejected r ? bothError(e, r)
                    : new SqlParity.Outcome(SqlParity.Status.ERROR, oneLine(e));
        }
        SqlParity.Grid actual = SqlParity.grid(result);
        List<String> types = result.columns().stream().map(c -> c.pureType().typeName()).toList();
        return switch (expected) {
            case SqlParity.Rejected r -> new SqlParity.Outcome(SqlParity.Status.BUG, "SQL Anywhere rejects it ("
                    + r.message() + ") and legend-lite answered " + actual.rows().size() + " rows");
            case SqlParity.Rows rows -> {
                List<String> diffs = compare(run, rows.grid(), snap, actual, types);
                yield diffs.isEmpty() ? new SqlParity.Outcome(SqlParity.Status.PASS, "")
                        : new SqlParity.Outcome(SqlParity.Status.FAIL, String.join("; ", diffs));
            }
        };
    }

    private static final Pattern LITE_CODE = Pattern.compile("\\(IQ: SQLCODE (-?\\d+), SQLSTATE ([0-9A-Z]{5})\\)");
    private static final Pattern SA_CODE = Pattern.compile("^SQLCODE (-?\\d+), SQLSTATE ([0-9A-Z]{5})");

    /** Both reject the run: a BOTH_ERROR, unless legend-lite's error names IQ's SQLCODE ({@code (IQ: SQLCODE n,
     *  SQLSTATE s)}, as the island's own errors do) and SQL Anywhere raised another one -- then lite's claim is
     *  wrong, a FAIL. An error without a code (a refusal, a database's run-time error) is judged by being one. */
    public static SqlParity.Outcome bothError(Throwable e, SqlParity.Rejected sa) {
        Matcher theirs = SA_CODE.matcher(sa.message());
        String code = theirs.find() ? theirs.group(1) : null;
        for (Throwable t = e; t != null && code != null; t = t.getCause() == t ? null : t.getCause()) {
            Matcher ours = LITE_CODE.matcher(String.valueOf(t.getMessage()));
            if (ours.find()) {
                if (!ours.group(1).equals(code)) {
                    return new SqlParity.Outcome(SqlParity.Status.FAIL, "both reject it, but legend-lite names IQ's"
                            + " SQLCODE " + ours.group(1) + " where SQL Anywhere raised " + code + " (" + sa.message()
                            + "): " + oneLine(e));
                }
                break;
            }
        }
        return new SqlParity.Outcome(SqlParity.Status.BOTH_ERROR, oneLine(e));
    }

    /** Whether {@code e} is an error legend-lite reports on purpose: the island's syntax error or IQ's error it raises
     *  in translation (its message begins with the island's opening), or the database's (a {@code DataError}). */
    private static boolean liteError(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof com.legend.error.IslandSyntaxException || t instanceof com.legend.error.DataError) {
                return true;
            }
            if (t.getClass().getSimpleName().equals("TypeInferenceException") && t.getMessage() != null
                    && t.getMessage().startsWith("#IQ")) {
                return true;
            }
            if (t.getCause() == t) {
                break;
            }
        }
        return false;
    }

    /** The judge's comparison: column names exactly as SQL Anywhere reports them (the island names a column as IQ
     *  does, so case counts), each column's type family against SQL Anywhere's type, a NUMERIC column's scale against
     *  each of lite's decimal cells ({@link #scale}), each cell exactly by SQL
     *  Anywhere's type ({@link #cellEquals}); rows in order when the query orders them (the order among rows tied on
     *  keys that are all output columns is the database's), else as a set. The run's ORDER BY is read by IQ's
     *  reader. Public for its test (IQ island, 2026-10-14, review 4's B2). */
    public static List<String> compare(Run run, SqlParity.Grid expected, IqAnswers.Snap snap, SqlParity.Grid actual,
            List<String> liteTypes) {
        List<String> diffs = new ArrayList<>();
        if (expected.columns().size() != actual.columns().size()) {
            diffs.add("Column count mismatch: expected " + expected.columns().size() + " but got "
                    + actual.columns().size());
            return diffs;
        }
        for (int i = 0; i < expected.columns().size(); i++) {
            if (!expected.columns().get(i).equals(actual.columns().get(i))) {
                diffs.add("Column name mismatch at index " + i + ": expected '" + expected.columns().get(i)
                        + "' but got '" + actual.columns().get(i) + "'");
            }
            String family = family(snap.types().get(i), i < snap.scales().size() ? snap.scales().get(i) : 0,
                    liteTypes.get(i));
            if (family != null) {
                diffs.add("Column '" + expected.columns().get(i) + "': " + family);
            }
            String scale = scale(snap.types().get(i), i < snap.scales().size() ? snap.scales().get(i) : 0, actual, i);
            if (scale != null) {
                diffs.add("Column '" + expected.columns().get(i) + "': " + scale);
            }
        }
        if (!diffs.isEmpty()) {
            return diffs;
        }
        List<SqlAst.SortItem> orderBy;
        SqlAst.Statement st;
        try {
            com.legend.protocol.sql.iq.IqSetPrefix.Prefix select = run.select(run.c().sql());
            st = IqParser.parse(select.select(), select.settings());
            // IQ island, 2026-10-14 (M1b): a set operation's ORDER BY is its own
            orderBy = st instanceof SqlAst.Select s ? s.orderBy() : st instanceof SqlAst.SetOperation o ? o.orderBy()
                    : List.of();
        } catch (SqlSyntaxException | com.legend.protocol.sql.SqlTranslationException e) {
            boolean ordered = run.c().sql().toUpperCase(Locale.ROOT).contains("ORDER BY");
            return rows(ordered, null, expected, actual, snap.types(), run.settings());
        }
        if (orderBy.isEmpty()) {
            return rows(false, null, expected, actual, snap.types(), run.settings());
        }
        if (rounded(st)) {
            // IQ island, 2026-10-14 (review 4's B2): the tie rule reads the shown keys; a value of a quotient or an
            // AVG is shown rounded where SQL Anywhere sorts by the unrounded one (.9990 and .9990 sort as 997/998 <
            // 998/999), so where any item of the statement is (or reads) one, the rows are compared in their order
            return rows(true, null, expected, actual, snap.types(), run.settings());
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
                return rows(true, null, expected, actual, snap.types(), run.settings());
            }
            keys.add(found);
        }
        return rows(true, keys, expected, actual, snap.types(), run.settings());
    }

    /** Whether a select item of any query of {@code statement} is, or reads, a quotient ({@code /}) or an AVG or a
     *  MEDIAN (a quotient of its values): values SQL Anywhere shows rounded (IQ island, 2026-10-14, review 4's B2). */
    private static boolean rounded(SqlAst.Statement statement) {
        for (SqlAst.Statement q : IqDialect.queries(statement)) {
            if (q instanceof SqlAst.Select s) {
                for (SqlAst.SelectItem item : s.items()) {
                    if (item instanceof SqlAst.SelectExpr x && roundedValue(x.expr())) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static boolean roundedValue(SqlAst.Expr e) {
        boolean here = e instanceof SqlAst.Binary b && b.op() == SqlAst.BinaryOp.DIVIDE
                || e instanceof SqlAst.FunctionCall f && Set.of("avg", "median").contains(
                f.name().get(f.name().size() - 1).text().toLowerCase(Locale.ROOT));
        return here || subexpressions(e).stream().anyMatch(IqParity::roundedValue);
    }

    /** The rows: in order ({@code ordered}, no {@code keys}), as a set, or as a set whose {@code keys} columns are in
     *  order -- a string key by IQ's collation under {@code profile} (IQ island, 2026-10-14: rows whose string keys
     *  differ only in trailing spaces, or in a {@code sqla17} profile in the ASCII letters' case, tie, and their order
     *  is the plan's, measured on SQL Anywhere 17). */
    private static List<String> rows(boolean ordered, @com.legend.base.Nullable List<Integer> keys,
            SqlParity.Grid expected, SqlParity.Grid actual, List<String> types,
            com.legend.protocol.sql.iq.IqSettings profile) {
        List<String> diffs = new ArrayList<>();
        if (expected.rows().size() != actual.rows().size()) {
            diffs.add("Row count mismatch: expected " + expected.rows().size() + " but got " + actual.rows().size());
            return diffs;
        }
        List<List<Object>> e = ordered && keys == null ? expected.rows() : sorted(expected.rows());
        List<List<Object>> a = ordered && keys == null ? actual.rows() : sorted(actual.rows());
        for (int r = 0; r < e.size() && diffs.size() < 10; r++) {
            for (int c = 0; c < types.size() && diffs.size() < 10; c++) {
                if (!cellEquals(e.get(r).get(c), a.get(r).get(c), types.get(c))) {
                    diffs.add("Row " + r + ", column '" + expected.columns().get(c) + "': expected '"
                            + e.get(r).get(c) + "' but got '" + a.get(r).get(c) + "'");
                }
            }
        }
        if (keys != null && diffs.isEmpty()) {
            for (int r = 0; r < expected.rows().size() && diffs.isEmpty(); r++) {
                for (int k : keys) {
                    Object ev = collationKey(expected.rows().get(r).get(k), types.get(k), profile);
                    Object av = collationKey(actual.rows().get(r).get(k), types.get(k), profile);
                    if (!cellEquals(ev, av, types.get(k))) {
                        diffs.add("Row " + r + ", sort key '" + expected.columns().get(k) + "': expected '" + ev
                                + "' but got '" + av + "'");
                    }
                }
            }
        }
        return diffs;
    }

    /** A sort key's cell as IQ orders it: a string of SQL Anywhere's type {@code saType} by its collation under
     *  {@code profile} -- its ASCII letters in lower case in a case-insensitive database, its trailing spaces dropped in
     *  a blank-padding one -- any other cell itself. */
    static @com.legend.base.Nullable Object collationKey(@com.legend.base.Nullable Object cell, String saType,
            com.legend.protocol.sql.iq.IqSettings profile) {
        if (!(cell instanceof String s) || !(saType.equals("char") || saType.equals("varchar")
                || saType.equals("long varchar"))) {
            return cell;
        }
        StringBuilder b = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            b.append(!profile.caseSensitive() && c >= 'A' && c <= 'Z' ? (char) (c + ('a' - 'A')) : c);
        }
        int end = b.length();
        while (profile.blankPadding() && end > 0 && b.charAt(end - 1) == ' ') {
            end--;
        }
        return b.substring(0, end);
    }

    /** Rows in one order whatever their source: by each cell's canonical text ({@link #canonical}). */
    private static List<List<Object>> sorted(List<List<Object>> rows) {
        List<List<Object>> out = new ArrayList<>(rows);
        out.sort(java.util.Comparator.comparing(r -> String.join("\001", r.stream().map(IqParity::canonical)
                .toList())));
        return out;
    }

    /** A cell's text for ordering a set of rows: a number by its exact value, whatever its Java type. */
    private static String canonical(@com.legend.base.Nullable Object v) {
        if (v == null) {
            return "\000";
        }
        if (v instanceof Double || v instanceof Float) {
            return "d" + v;
        }
        BigDecimal n = exact(v);
        return n != null ? "n" + n.stripTrailingZeros().toPlainString() : "s" + v;
    }

    /** An exact number's value: a Long, an Integer, a Short, a Byte, a BigInteger, a BigDecimal; null otherwise. */
    private static @com.legend.base.Nullable BigDecimal exact(Object v) {
        return switch (v) {
            case BigDecimal d -> d;
            case java.math.BigInteger i -> new BigDecimal(i);
            case Long l -> BigDecimal.valueOf(l);
            case Integer i -> BigDecimal.valueOf(i);
            case Short i -> BigDecimal.valueOf(i);
            case Byte i -> BigDecimal.valueOf(i);
            default -> null;
        };
    }

    /** How close two doubles must be when SQL Anywhere's column is a DOUBLE: a few units in the last place. */
    private static final double RELATIVE = 1e-15;

    /** A cell against SQL Anywhere's, by SQL Anywhere's type {@code saType}: an integer exactly (as a whole number, of
     *  any Java integer type), a numeric exactly by value (its scale is the column's, {@link #scale}), a double within
     *  {@link #RELATIVE} of it (a NaN or an infinity exactly), anything else as the same text. */
    public static boolean cellEquals(@com.legend.base.Nullable Object expected, @com.legend.base.Nullable Object actual,
            String saType) {
        if (expected == null || actual == null) {
            return expected == actual;
        }
        if (IqAnswers.INTEGER_TYPES.contains(saType) || saType.equals("numeric") || saType.equals("decimal")) {
            BigDecimal e = exact(expected);
            BigDecimal a = exact(actual);
            return e != null && a != null && e.compareTo(a) == 0
                    && (!IqAnswers.INTEGER_TYPES.contains(saType) || !(actual instanceof BigDecimal));
        }
        if (saType.equals("double") || saType.equals("float") || saType.equals("real")) {
            if (!(expected instanceof Double e) || !(actual instanceof Double || actual instanceof Float)) {
                return false;
            }
            double a = ((Number) actual).doubleValue();
            if (Double.isNaN(e) || Double.isInfinite(e) || Double.isNaN(a) || Double.isInfinite(a)) {
                return e.equals(a);
            }
            return Math.abs(e - a) <= RELATIVE * Math.max(Math.abs(e), Math.abs(a));
        }
        return !(actual instanceof Number) && !(actual instanceof Boolean) && expected.toString().equals(
                actual.toString());
    }

    /** Whether lite's column type (its Pure type's name) is of SQL Anywhere's type's family: null when it is, else
     *  the difference. An integer type (a BIT too) is an Integer; a numeric a Decimal (one of scale 0 an Integer too);
     *  a double a Float; text a String; a date a StrictDate; a timestamp a DateTime. */
    private static @com.legend.base.Nullable String family(String saType, int scale, String liteType) {
        String lite = liteType.contains("(") ? liteType.substring(0, liteType.indexOf('(')) : liteType;
        Set<String> allowed;
        if (IqAnswers.INTEGER_TYPES.contains(saType)) {
            allowed = Set.of("Integer");
        } else if (saType.equals("numeric") || saType.equals("decimal")) {
            allowed = scale == 0 ? Set.of("Decimal", "Integer") : Set.of("Decimal");
        } else if (saType.equals("double") || saType.equals("float") || saType.equals("real")) {
            allowed = Set.of("Float");
        } else if (saType.equals("char") || saType.equals("varchar") || saType.equals("long varchar")) {
            allowed = Set.of("String");
        } else if (saType.equals("date")) {
            allowed = Set.of("StrictDate", "Date");
        } else if (saType.equals("timestamp")) {
            allowed = Set.of("DateTime");
        } else {
            return null;
        }
        return allowed.contains(lite) ? null : "SQL Anywhere's type " + saType + " is " + allowed + ", legend-lite's is "
                + liteType;
    }

    /** Whether lite's numbers in column {@code i} carry SQL Anywhere's scale where its type is a NUMERIC of
     *  {@code scale} places (IQ shows {@code 1.50} as NUMERIC(2,1) {@code 1.5}): null when every decimal cell does (an
     *  integer cell is a NUMERIC of scale 0 read as one), else the difference. */
    public static @com.legend.base.Nullable String scale(String saType, int scale, SqlParity.Grid actual, int i) {
        if (!saType.equals("numeric") && !saType.equals("decimal")) {
            return null;
        }
        for (List<Object> row : actual.rows()) {
            Object v = row.get(i);
            int places = v instanceof BigDecimal d ? d.scale() : 0;
            if (v != null && places != scale) {
                return "SQL Anywhere's NUMERIC has " + scale + " places, legend-lite's value " + v + " has " + places;
            }
        }
        return null;
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
            SqlParity.Outcome o = outcomes.get(r.key());
            if (o != null && (o.status() == SqlParity.Status.FAIL || o.status() == SqlParity.Status.BUG
                    || o.status() == SqlParity.Status.ERROR || System.getProperty("iq.parity.verbose") != null)) {
                // the line the ledger cannot hold: why a run is not as SQL Anywhere answers
                System.out.println("[iq-parity] " + lane + " " + o.status() + " " + r.key() + ": " + o.detail());
            }
            SqlParity.Case c = r.asSqlParityCase();
            cases.add(c);
            byKey.put(SqlParity.key(c), outcomes.get(r.key()));
        }
        return SqlParity.ratchet("the #IQ{...}# island's parity cases", "iq-parity", LEDGER_HEADER, cases, byKey,
                ledger, lane, update);
    }
}
