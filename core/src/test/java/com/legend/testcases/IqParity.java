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
        String engine = IqDialect.engineOf(statement, run.profile()).name().toLowerCase(Locale.ROOT);
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
        String sql = rewrite(c.sql(), names, run.profile());
        SqlAst.Statement statement;
        try {
            statement = IqParser.parse(sql, run.profile());
        } catch (SqlSyntaxException e) {
            return null;
        }
        if (IqDialect.engineOf(statement, run.profile()) != IqDialect.Engine.IQ) {
            return null;
        }
        String iq = pureOf(sql, tables, IqDialect.of(run.profile(), IqDialect.Engine.IQ));
        if (iq == null) {
            return null;
        }
        String sa = pureOf(sql, tables, IqDialect.of(run.profile(), IqDialect.Engine.SA));
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
            statement = IqParser.parse(run.c().sql(), run.profile());
        } catch (SqlSyntaxException e) {
            return null;
        }
        if (!(statement instanceof SqlAst.Select s) || s.where() == null
                || !raisesBesideAndOr(java.util.Objects.requireNonNull(s.where()))) {
            return null;
        }
        return "EVALUATION ORDER: " + run.key() + " puts an operation that can raise beside AND or OR in its WHERE:"
                + " whether IQ raises depends on its plan (D-E2), so skip it (skip: " + PLAN_DEPENDENT + ") or rewrite"
                + " it";
    }

    /** Whether a search condition has an AND or OR (outside a CASE) one of whose operands holds an operation that
     *  can raise. */
    private static boolean raisesBesideAndOr(SqlAst.Expr e) {
        return switch (e) {
            case SqlAst.Binary b when b.op() == SqlAst.BinaryOp.AND || b.op() == SqlAst.BinaryOp.OR ->
                    canRaise(b.left()) || canRaise(b.right()) || raisesBesideAndOr(b.left())
                            || raisesBesideAndOr(b.right());
            case SqlAst.Unary u when u.op() == SqlAst.UnaryOp.NOT -> raisesBesideAndOr(u.operand());
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
            case SqlAst.FunctionCall f -> f.name().get(f.name().size() - 1).text().equalsIgnoreCase("nullif")
                    && readsAColumn(e);
            default -> false;
        };
        return here || subexpressions(e).stream().anyMatch(IqParity::canRaise);
    }

    private static boolean readsAColumn(SqlAst.Expr e) {
        return e instanceof SqlAst.ColumnRef || subexpressions(e).stream().anyMatch(IqParity::readsAColumn);
    }

    /** The expressions directly inside {@code e}, for the forms IQ's reader makes. */
    private static List<SqlAst.Expr> subexpressions(SqlAst.Expr e) {
        return switch (e) {
            case SqlAst.Binary b -> List.of(b.left(), b.right());
            case SqlAst.Unary u -> List.of(u.operand());
            case SqlAst.IsNull n -> List.of(n.operand());
            case SqlAst.FunctionCall f -> f.args();
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
            default -> List.of();
        };
    }

    /** {@code sql}'s Pure under {@code dialect}, the schema's tables its sources; null when the dialect refuses it,
     *  the refusal's kind and message when it rejects it. */
    private static @com.legend.base.Nullable String pureOf(String sql, List<SqlParity.Table> tables,
            IqDialect dialect) {
        try {
            return com.legend.protocol.PureComposer.valueSpecification(com.legend.protocol.sql.SqlToPure.translate(sql,
                    (source, column) -> {
                        String printed = com.legend.protocol.PureComposer.valueSpecification(source,
                                com.legend.protocol.PureComposer.Style.STANDARD);
                        String table = printed.substring(printed.lastIndexOf('.') + 1, printed.lastIndexOf("}#"));
                        for (SqlParity.Table t : tables) {
                            if (t.name().equals(table)) {
                                t.columns().forEach(col -> column.column(col.name(), pureType(col.type()), true,
                                        relationalType(col.type())));
                            }
                        }
                    }, dialect), com.legend.protocol.PureComposer.Style.STANDARD);
        } catch (com.legend.protocol.sql.SqlTranslationException e) {
            return e.kind() == com.legend.protocol.sql.SqlTranslationException.Kind.UNSUPPORTED ? null
                    : e.kind() + ": " + e.getMessage();
        }
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
            Set<String> tables, SqlParity.Legend legend) {
        if (run.c().skip() != null) {
            return new SqlParity.Outcome(SqlParity.Status.SKIP, java.util.Objects.requireNonNull(run.c().skip()));
        }
        if (snap == null) {
            return new SqlParity.Outcome(SqlParity.Status.ERROR, "no snapped answer (UNSNAPPED)");
        }
        SqlParity.Answer expected = IqAnswers.expected(run, snap);
        com.legend.exec.ExecutionResult result;
        try {
            result = legend.run(island(run, tables));
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
     *  reader. */
    private static List<String> compare(Run run, SqlParity.Grid expected, IqAnswers.Snap snap, SqlParity.Grid actual,
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
        try {
            SqlAst.Statement st = IqParser.parse(run.c().sql(), run.profile());
            orderBy = st instanceof SqlAst.Select s ? s.orderBy() : List.of();
        } catch (SqlSyntaxException e) {
            boolean ordered = run.c().sql().toUpperCase(Locale.ROOT).contains("ORDER BY");
            return rows(ordered, null, expected, actual, snap.types());
        }
        if (orderBy.isEmpty()) {
            return rows(false, null, expected, actual, snap.types());
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
                return rows(true, null, expected, actual, snap.types());
            }
            keys.add(found);
        }
        return rows(true, keys, expected, actual, snap.types());
    }

    /** The rows: in order ({@code ordered}, no {@code keys}), as a set, or as a set whose {@code keys} columns are in
     *  order. */
    private static List<String> rows(boolean ordered, @com.legend.base.Nullable List<Integer> keys,
            SqlParity.Grid expected, SqlParity.Grid actual, List<String> types) {
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
                    Object ev = expected.rows().get(r).get(k);
                    Object av = actual.rows().get(r).get(k);
                    if (!cellEquals(ev, av, types.get(k))) {
                        diffs.add("Row " + r + ", sort key '" + expected.columns().get(k) + "': expected '" + ev
                                + "' but got '" + av + "'");
                    }
                }
            }
        }
        return diffs;
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
