package com.legend.exec;

import com.legend.sql.Json;

import com.legend.compiler.element.type.PlatformTypes;
import com.legend.compiler.element.type.Type;
import com.legend.compiler.element.type.ExprType;
import com.legend.sql.OutputCol;
import com.legend.sql.SqlQuery;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * Executes rendered SQL and shapes the rows per the ROOT's classification.
 * Cell values are raw JDBC objects; column Pure types come from the query's
 * typed outputs (never from JDBC metadata — the no-sniffing contract).
 */
public final class Executor {

    private Executor() {
    }

    /**
     * Raw-statement execution — the K-native {@code executeInDb} boundary:
     * one already-dialect-adapted statement, no plan, no result shaping.
     */
    /** Raw-statement JDBC time + count — perf instrument (the corpus
     * duck-vs-h2 seed accounting). */
    public static final java.util.concurrent.atomic.AtomicLong RAW_NANOS =
            new java.util.concurrent.atomic.AtomicLong();
    public static final java.util.concurrent.atomic.AtomicLong RAW_CALLS =
            new java.util.concurrent.atomic.AtomicLong();

    public static void executeRaw(Connection connection, String statement)
            throws SQLException {
        long t0 = System.nanoTime();
        try (Statement st = connection.createStatement()) {
            st.execute(statement);
        } finally {
            RAW_NANOS.addAndGet(System.nanoTime() - t0);
            RAW_CALLS.incrementAndGet();
        }
    }

    public static ExecutionResult execute(String sql, SqlQuery plan, ExprType rootType,
                                          Connection connection,
                                          com.legend.sql.dialect.SqlDialect dialect)
            throws SQLException {
        return execute(sql, plan, rootType, ResultShape.of(rootType), connection, dialect);
    }

    /**
     * Shape-explicit entry: the driver decides the shape from the RESOLVED
     * root NODE (a class-typed root is GRAPH only under the resolver's
     * serialize envelope; bare it is an instance VALUE — the type alone
     * cannot tell them apart).
     */
    public static ExecutionResult execute(String sql, SqlQuery plan, ExprType rootType,
                                          ResultShape shape, Connection connection,
                                          com.legend.sql.dialect.SqlDialect dialect)
            throws SQLException {
        // TEMPORARY (2026-08-15 G4-vs-G5 wall accounting): whole
        // plan-execution boundary — prepare + executeQuery + result
        // materialization/shaping. Histogram by RESULT SHAPE (scalar
        // value-evals vs tabular/graph) + SQL duplication stats.
        long qt0 = System.nanoTime();
        try {
            return execute0(sql, plan, rootType, shape, connection, dialect);
        } finally {
            com.legend.exec.TimingLedger.add("query.exec",
                    System.nanoTime() - qt0);
            com.legend.exec.TimingLedger.add("query.exec.shape."
                    + shape, System.nanoTime() - qt0);
            HISTO.record(sql);
        }
    }

    /** TEMPORARY (2026-08-15): SQL duplication histogram — hash-keyed
     *  counts (memory-bounded), exemplars for the top repeats. */
    private static final class Histo {
        final java.util.concurrent.ConcurrentHashMap<Integer, java.util.concurrent.atomic.AtomicLong>
                counts = new java.util.concurrent.ConcurrentHashMap<>();
        final java.util.concurrent.ConcurrentHashMap<Integer, String>
                exemplars = new java.util.concurrent.ConcurrentHashMap<>();

        Histo() {
            Runtime.getRuntime().addShutdownHook(new Thread(this::dump));
        }

        void record(String sql) {
            int h = sql.hashCode();
            long n = counts.computeIfAbsent(h,
                    k -> new java.util.concurrent.atomic.AtomicLong())
                    .incrementAndGet();
            if (n == 2 && exemplars.size() < 5000) {
                exemplars.put(h, sql.length() > 200
                        ? sql.substring(0, 200) : sql);
            }
        }

        void dump() {
            long total = counts.values().stream()
                    .mapToLong(java.util.concurrent.atomic.AtomicLong::get).sum();
            StringBuilder sb = new StringBuilder();
            sb.append("total executions\t").append(total).append('\n');
            sb.append("distinct sql\t").append(counts.size()).append('\n');
            counts.entrySet().stream()
                    .sorted((a, b) -> Long.compare(b.getValue().get(),
                            a.getValue().get()))
                    .limit(25)
                    .forEach(e -> sb.append(e.getValue().get()).append("x\t")
                            .append(String.valueOf(exemplars.get(e.getKey()))
                                    .replace('\n', ' '))
                            .append('\n'));
            try {
                java.nio.file.Files.writeString(java.nio.file.Path.of(
                        "target", "query-histogram.txt"), sb.toString());
            } catch (java.io.IOException ignore) {
                // best-effort diagnostic
            }
        }
    }

    private static final Histo HISTO = new Histo();

    private static ExecutionResult execute0(String sql, SqlQuery plan, ExprType rootType,
                                          ResultShape shape, Connection connection,
                                          com.legend.sql.dialect.SqlDialect dialect)
            throws SQLException {
        boolean anyRoot = PlatformTypes.isAny(rootType.type());
        boolean variantRoot = rootType.type()
                instanceof com.legend.compiler.element.type.Type.ClassType vct
                && PlatformTypes.isVariant(vct);
        dumpSql(sql);
        // prepareStatement, not createStatement: DuckDB JDBC 1.5 masks a
        // direct Statement's real error behind 'Attempting to execute an
        // unsuccessful or closed pending query result' (audit: 74 corpus
        // errors were unreadable); prepare() surfaces the actual message
        try {
            return executePrepared(connection, sql, shape, plan, rootType,
                    dialect, anyRoot, variantRoot);
        } catch (SQLException e) {
            // error-path echo under the same diagnostic flag: a sweep's
            // failing statement is otherwise invisible (pre-exec dump
            // interleaves; the FAILING sql is the one worth reading)
            if (System.getenv("LEGEND_LITE_DUMP_SQL") != null) {
                System.err.println("[sql-fail] " + sql);
            }
            throw e;
        }
    }

    /**
     * The STREAMING entry — JSON straight to {@code out}, no materialization
     * for unbounded shapes. TABULAR iterates the ResultSet lazily through the
     * SAME cell/column machinery as {@link #execute} (fetch/unwrap/shaping —
     * one set of rules); GRAPH expects the streaming lowering's one
     * {@code json_object} per JDBC row ({@code Lowerer#withStreamingGraphRoot})
     * and writes each row's JSON verbatim inside an enclosing array. SCALAR
     * and COLLECTION are bounded by contract — materialized, then written.
     * Flushes after every row so downstream buffers (OutputStreamWriter,
     * HttpExchange, sockets) release bytes as rows arrive. {@code out} is
     * never closed — the caller owns its lifecycle.
     */
    public static void stream(String sql, SqlQuery plan, ExprType rootType,
                              ResultShape shape, Connection connection,
                              com.legend.sql.dialect.SqlDialect dialect,
                              java.io.Writer out)
            throws SQLException, java.io.IOException {
        dumpSql(sql);
        switch (shape) {
            case TABULAR -> streamTabular(sql, plan, rootType, connection, dialect, out);
            case GRAPH -> streamGraph(sql, connection, dialect, out);
            case SCALAR, COLLECTION -> {
                out.write(ResultJson.toJsonArray(
                        execute(sql, plan, rootType, shape, connection, dialect)));
                out.flush();
            }
        }
    }

    private static void streamTabular(String sql, SqlQuery plan, ExprType rootType,
            Connection connection, com.legend.sql.dialect.SqlDialect dialect,
            java.io.Writer out) throws SQLException, java.io.IOException {
        final Type.RelationType schema = tabularSchema(rootType);
        try (java.sql.PreparedStatement st = connection.prepareStatement(sql);
             ResultSet rs = st.executeQuery()) {
            int n = rs.getMetaData().getColumnCount();
            List<Column> columns = resolveColumns(rs, plan, schema, n);
            out.write('[');
            boolean first = true;
            while (rs.next()) {
                for (Row row : shapeRow(rs, n, plan, dialect, schema, columns)) {
                    if (!first) {
                        out.write(',');
                    }
                    first = false;
                    ResultJson.writeRow(out, columns, row.values());
                    out.flush();
                }
            }
            out.write(']');
            out.flush();
        }
    }

    private static void streamGraph(String sql, Connection connection,
            com.legend.sql.dialect.SqlDialect dialect, java.io.Writer out)
            throws SQLException, java.io.IOException {
        try (java.sql.PreparedStatement st = connection.prepareStatement(sql);
             ResultSet rs = st.executeQuery()) {
            out.write('[');
            boolean first = true;
            while (rs.next()) {
                if (!first) {
                    out.write(',');
                }
                first = false;
                // each cell is one complete json_object — normalized through
                // the dialect's JSON codec (H2 hands JSON back as byte[]),
                // then written verbatim: no parsing, no re-escaping.
                Object cell = dialect.normalize(rs.getObject(1),
                        com.legend.sql.SqlType.Scalar.JSON);
                out.write(cell != null ? String.valueOf(cell) : "null");
                out.flush();
            }
            out.write(']');
            out.flush();
        }
    }

    /** Opt-in diagnostic: every executed statement to stderr. */
    private static void dumpSql(String sql) {
        if (System.getenv("LEGEND_LITE_DUMP_SQL") != null) {
            System.err.println("[sql] " + sql);
        }
    }

    private static ExecutionResult executePrepared(Connection connection,
            String sql, ResultShape shape, SqlQuery plan, ExprType rootType,
            com.legend.sql.dialect.SqlDialect dialect, boolean anyRoot,
            boolean variantRoot) throws SQLException {
        try (java.sql.PreparedStatement st = connection.prepareStatement(sql);
             ResultSet rs = st.executeQuery()) {
            return switch (shape) {
                case TABULAR -> tabular(rs, plan, rootType, dialect);
                case SCALAR -> {
                    Object v = rs.next()
                            ? latticeKind(cell(rs, plan, dialect, anyRoot, variantRoot),
                                    rootType.type())
                            : null;
                    // a SECOND row under a scalar-shaped root is a resolver/
                    // lowering bug (e.g. a to-one stand-in leaking rows) —
                    // reading only the first would be a SILENT wrong value
                    // (audit 20b: this arm never checked)
                    if (rs.next()) {
                        throw new IllegalStateException("scalar-shaped result"
                                + " returned more than one row — the to-one"
                                + " contract was not enforced upstream");
                    }
                    yield new ExecutionResult.Scalar(v, rootType.type());
                }
                case COLLECTION -> {
                    List<Object> values = new ArrayList<>();
                    while (rs.next()) {
                        Object v = latticeKind(cell(rs, plan, dialect, anyRoot, variantRoot),
                                rootType.type());
                        // a NULL cell is a pure EMPTY, and no pure collection
                        // holds empties — Person.all().middleName over a row
                        // with no middle name contributes nothing, not null
                        if (v != null) {
                            values.add(v);
                        }
                    }
                    yield new ExecutionResult.Collection(values, rootType.type());
                }
                case GRAPH -> new ExecutionResult.Graph(
                        // the envelope is JSON TEXT by contract — the
                        // dialect codec canonicalizes driver-flavored
                        // carriers (H2 hands JSON back as byte[])
                        rs.next() ? String.valueOf(dialect.normalize(
                                rs.getObject(1),
                                com.legend.sql.SqlType.Scalar.JSON)) : "[]",
                        rootType.type());
            };
        }
    }

    private static @com.legend.Nullable Object cell(ResultSet rs, SqlQuery plan,
                               com.legend.sql.dialect.SqlDialect dialect, boolean anyRoot,
                               boolean variantRoot)
            throws SQLException {
        Object v = unwrap(fetch(rs, 1, sqlTypeOf(plan, 0)), sqlTypeOf(plan, 0), dialect);
        if (anyRoot) {
            return decodeAny(v);
        }
        // A JSON-carrier CELL under a non-Any VALUE root (a variant-list
        // read narrowed by cast(@Float): $row.values->at(1)->cast(@Float) —
        // the cast re-roots the declared type, but the cell still arrives
        // as the driver's JSON node). The node is SELF-DESCRIBING wire —
        // decoding it is recovery, never value guessing. NEVER for a
        // VARIANT root (audit 22 self-catch): a Variant result's contract
        // IS the JSON text — decoding it would change the wire.
        if (!variantRoot && v != null
                && v.getClass().getName().equals("org.duckdb.JsonNode")) {
            return decodeAny(v);
        }
        return v;
    }

    /**
     * LATTICE-typed roots recover their values' own kinds from the
     * identity channel's print forms (computed by the database). The
     * TIMESTAMP-midnight StrictDate heuristic that used to live here
     * (audit A10: it read the cell's MAGNITUDE, and a genuine DateTime
     * at exactly midnight was misread) is DELETED BY PROOF (F5.4): an
     * instrumented probe fired ZERO times across all three referees —
     * the full DuckDB corpus, the full H2 corpus, and the 1,109-test
     * PCT suite. Mapped DATE columns arrive as {@code java.sql.Date}
     * (the COLUMN's SQL kind carries the fact); a TIMESTAMP under an
     * abstract Date root keeps its time and decodes as a DateTime —
     * the A10-correct semantics. The other value-consulting heuristics
     * (integral-double narrowing, scale-0 decimal narrowing) WERE
     * audited out earlier.
     */
    private static @com.legend.Nullable Object latticeKind(@com.legend.Nullable Object v,
            Type rootType) {
        // The MIXED-ELEMENT IDENTITY channel: selections over mixed-kind
        // Number collections return each element's pure PRINT FORM as text
        // ('2', '2.0', '7.345D') — parsed back to its own kind here. (DATE
        // identities stay strings — the wire's date convention.)
        // V1.7 adjudication (Phase 8): the audit read this as re-parsing
        // the DB's print form; it is the DELIBERATE carrier decode of the
        // mixed-identity design (PCT burn-down: print-form carrier beat
        // the typed-sibling-column alternative) — the print form IS the
        // wire contract for NUMBER-rooted mixed collections.
        if (rootType == Type.Primitive.NUMBER && v instanceof String s) {
            if (s.endsWith("D")) {
                return new java.math.BigDecimal(s.substring(0, s.length() - 1));
            }
            if (s.contains(".") || s.contains("e") || s.contains("E")) {
                return Double.valueOf(s);
            }
            return Long.valueOf(s);
        }
        return v;
    }

    /**
     * An ANY-typed value travels as variant JSON (the heterogeneous-list
     * carrier); at the boundary each element decodes back to its own runtime
     * kind — a number is a Number again, not the string {@code "1"}. Variant
     * results are NOT decoded (their contract is the JSON text itself); only
     * the Any root takes this path.
     *
     * <p>V1.8 adjudication (Phase 8): these scalar arms are the ANY-boundary
     * decode CONTRACT, not a duplicate JSON reader — the string arm already
     * delegates to the one unescape table (F3.1d), and the number arm must
     * NOT delegate to {@code sql/Json.num}: a decimal-form JSON number under
     * an Any root is a pure Float (host {@code Double}), while the strict
     * JSON bridge deliberately reads {@code BigDecimal} (audit 18 —
     * wireEquals-grade exactness). Same grammar, different target kinds by
     * design.
     */
    private static @com.legend.Nullable Object decodeAny(@com.legend.Nullable Object v) {
        // Drivers hand JSON cells back as their own node type (DuckDB:
        // org.duckdb.JsonNode) or as text — matched by FULL class name so the
        // executor needs no driver import; the node's toString IS the JSON text.
        String s;
        if (v instanceof String str) {
            s = str;
        } else if (v != null && v.getClass().getName().equals("org.duckdb.JsonNode")) {
            s = v.toString();
        } else {
            return v;
        }
        String t = s.trim();
        if (t.length() >= 2 && t.startsWith("\"") && t.endsWith("\"")) {
            return jsonUnescape(t.substring(1, t.length() - 1));
        }
        if (t.equals("true") || t.equals("false")) {
            return Boolean.valueOf(t);
        }
        if (t.equals("null")) {
            return null;
        }
        try {
            return Long.valueOf(t);
        } catch (NumberFormatException ignored) {
            // fall through
        }
        try {
            return Double.valueOf(t);
        } catch (NumberFormatException ignored) {
            return s;
        }
    }

    /**
     * JSON string-escape decoding — the variant carrier emits PROPER JSON, so
     * a value like {@code he said "hi"} arrives as {@code "he said \"hi\""};
     * a raw quote-strip would keep the backslashes (audit finding).
     */
    private static String jsonUnescape(String s) {
        // F3.1d: this was a keep-the-backslash TWIN of the platform
        // reader's table (sql/Json drops it — the same terminal rule as
        // the Pure unescape family); the twin lost the adjudication
        return com.legend.sql.Json.unescapeString(s);
    }

    /**
     * A composite JDBC cell unwraps by its DECLARED layout: a struct cell
     * becomes an ordered field map (names from the plan's {@link SqlType.Struct}
     * — the model's canonical layout, positional values), an array cell a list;
     * leaves normalize through the dialect. Attribute-count drift from the
     * declared layout is a contract violation — loud, never zipped short.
     */
    /**
     * Typed cell retrieval. TIMESTAMP columns fetch through {@code java.time}:
     * the driver's {@code java.sql.Timestamp} construction DROPS the BC era
     * (year -21457 surfaces as +21458 — irrecoverably, the epoch itself is
     * wrong). Timestamp stays the carrier where it is faithful (AD years);
     * a BC value keeps its LocalDateTime.
     */
    private static @com.legend.Nullable Object fetch(ResultSet rs, int i,
            com.legend.sql.@com.legend.Nullable SqlType type)
            throws SQLException {
        Object o = rs.getObject(i);
        if (o instanceof java.sql.Timestamp) {
            // (a TIMESTAMP-typed output may still surface a VARCHAR cell —
            // the precision-faithful string convention — so gate on the
            // actual driver object, not the declared type)
            java.time.LocalDateTime ldt = rs.getObject(i, java.time.LocalDateTime.class);
            if (ldt != null && ldt.getYear() < 1) {
                return ldt;
            }
        }
        return o;
    }

    private static @com.legend.Nullable Object unwrap(@com.legend.Nullable Object v,
            com.legend.sql.@com.legend.Nullable SqlType type,
                                 com.legend.sql.dialect.SqlDialect dialect) throws SQLException {
        if (v == null) {
            return null;
        }
        if (type instanceof com.legend.sql.SqlType.Struct st && v instanceof java.sql.Struct s) {
            Object[] attrs = s.getAttributes();
            if (attrs.length != st.fields().size()) {
                throw new IllegalStateException("struct cell has " + attrs.length
                        + " attribute(s) but the declared layout has " + st.fields().size());
            }
            java.util.LinkedHashMap<String, Object> m = new java.util.LinkedHashMap<>();
            for (int i = 0; i < attrs.length; i++) {
                m.put(st.fields().get(i).name(),
                        unwrap(attrs[i], st.fields().get(i).type(), dialect));
            }
            return m;
        }
        // the JSON carrier (H2 and any list-less backend, §2b): an
        // Array-typed cell arriving as JSON TEXT parses back to the
        // element list — the declared type drives the decode, never the
        // value's runtime class alone
        if (type instanceof com.legend.sql.SqlType.Array
                && (v instanceof byte[] || v instanceof String)) {
            String text = v instanceof byte[] b
                    ? new String(b, java.nio.charset.StandardCharsets.UTF_8)
                    : (String) v;
            if (text.startsWith("[")) {
                return Json.parse(text);
            }
        }
        if (type instanceof com.legend.sql.SqlType.Array at && v instanceof java.sql.Array a) {
            Object[] elements = (Object[]) a.getArray();
            List<Object> out = new ArrayList<>(elements.length);
            for (Object e : elements) {
                out.add(unwrap(e, at.element(), dialect));
            }
            return out;
        }
        return dialect.normalize(v, type);
    }

    /**
     * PURE column types come from the TYPED HIR ROOT's schema (the frontend's
     * truth); SQL types for driver normalization come from the plan's
     * outputs. The two type systems meet only here, each on its own side.
     */
    private static ExecutionResult.Tabular tabular(ResultSet rs, SqlQuery plan, ExprType rootType,
                                                    com.legend.sql.dialect.SqlDialect dialect)
            throws SQLException {
        final Type.RelationType schema = tabularSchema(rootType);
        int n = rs.getMetaData().getColumnCount();
        List<Column> columns = resolveColumns(rs, plan, schema, n);
        List<Row> rows = new ArrayList<>();
        while (rs.next()) {
            rows.addAll(shapeRow(rs, n, plan, dialect, schema, columns));
        }
        return new ExecutionResult.Tabular(columns, rows, rootType.type());
    }

    /** The relation schema of a TABULAR root, struct columns flattened. */
    private static Type.RelationType tabularSchema(ExprType rootType) {
        if (!(rootType.type() instanceof Type.RelationType typedSchema)) {
            throw new IllegalStateException("TABULAR result without a relation root type: "
                    + rootType.type().typeName());
        }
        // A ROW-STRUCT column (a user navigate's slot) is typed nesting over
        // a FLAT physical reality — expand to the prefixed columns the join
        // emitted (alias_COL), mirroring the lowerer's output flattening.
        return flattenStructColumns(typedSchema);
    }

    private static List<Column> resolveColumns(ResultSet rs, SqlQuery plan,
            Type.RelationType schema, int n) throws SQLException {
        List<Column> columns = new ArrayList<>();
        if (n == schema.columns().size()) {
            // POSITIONAL on both sides (schemas are ordered); no null types.
            for (int i = 1; i <= n; i++) {
                Type.Column sc = schema.columns().get(i - 1);
                columns.add(new Column(sc.name(),
                        rs.getMetaData().getColumnTypeName(i), sc.type(),
                        sc.multiplicity()));
            }
        } else if (hasPivot(plan)) {
            // DYNAMIC PIVOT: one result column per pivoted VALUE — the static
            // schema cannot enumerate them (the checker keeps only the
            // group-by half). Statically known names match by NAME; a
            // pivot-generated '<value>__|__<agg>' column inherits its
            // aggregate TEMPLATE's type (schema.dynamicColumns(), the
            // engine-lite DynamicPivotColumn design) — the name is
            // data-dependent, the type is not. SQL-type derivation remains
            // only for schemas rebuilt downstream of the pivot, where the
            // templates no longer ride.
            for (int i = 1; i <= n; i++) {
                String name = rs.getMetaData().getColumnName(i);
                String sqlType = rs.getMetaData().getColumnTypeName(i);
                columns.add(new Column(name, sqlType, pivotColumnType(schema, name, sqlType)));
            }
        } else {
            throw new IllegalStateException("result has " + n + " columns but the typed"
                    + " schema has " + schema.columns().size() + " — plan/schema mismatch");
        }
        return columns;
    }

    /**
     * Shape ONE JDBC row into 1..k result rows (shared by the materialized
     * and streaming tabular paths — the shaping rules must never diverge).
     */
    private static List<Row> shapeRow(ResultSet rs, int n, SqlQuery plan,
            com.legend.sql.dialect.SqlDialect dialect, Type.RelationType schema,
            List<Column> columns) throws SQLException {
        List<Object> cells = new ArrayList<>(n);
        for (int i = 1; i <= n; i++) {
            Object cell = unwrap(fetch(rs, i, sqlTypeOf(plan, i - 1)),
                    sqlTypeOf(plan, i - 1), dialect);
            // E2 (JAVA_EVICTION_PLAN): the host-side row explosion is
            // DEAD — the scalar-stream projection explodes IN SQL
            // (LEFT LATERAL UNNEST at project lowering; probe: zero
            // firings on the full sweep) and the declared to-one slot
            // matches the emitted one. A list cell in a primitive
            // schema slot is a lowering defect, never repaired here.
            if ((cell instanceof List<?> || cell instanceof java.sql.Array)
                    && schema.columns().get(i - 1).type()
                            instanceof Type.Primitive) {
                throw new IllegalStateException("a many-valued cell"
                        + " reached a scalar TDS slot ('"
                        + columns.get(i - 1).name()
                        + "') — the lowering must explode scalar"
                        + " streams in SQL (E2)");
            }
            cells.add(cell);
        }
        return List.of(new Row(cells));
    }

    private static com.legend.sql.@com.legend.Nullable SqlType sqlTypeOf(SqlQuery plan, int index) {
        List<OutputCol> outputs = plan.outputs();
        if (index >= outputs.size()) {
            if (hasPivot(plan)) {
                return null; // dynamic pivot column: no static SQL type exists
            }
            throw new IllegalStateException("result column " + index
                    + " has no plan output — plan/result mismatch");
        }
        return outputs.get(index).type();
    }

    /** Whether the plan's source tree contains a (dynamic-columned) PIVOT. */
    private static boolean hasPivot(SqlQuery plan) {
        return plan instanceof com.legend.sql.SqlSelect s && hasPivot(s.from());
    }

    private static boolean hasPivot(com.legend.sql.SqlSource src) {
        return switch (src) {
            case null -> false;
            case com.legend.sql.SqlSource.Pivot p -> true;
            case com.legend.sql.SqlSource.Subselect sub -> hasPivot(sub.inner());
            case com.legend.sql.SqlSource.Join j -> hasPivot(j.left()) || hasPivot(j.right());
            default -> false;
        };
    }
    /**
     * The Pure type of one column of a pivot result. Static (group-by) names
     * match the schema; a dynamic {@code <value>__|__<agg>} name inherits its
     * aggregate template's type. A suffixed name that matches NO template while
     * templates are present is a naming-contract bug — loud, never guessed.
     */
    private static Type pivotColumnType(Type.RelationType schema, String name, String sqlType) {
        var byName = schema.columns().stream()
                .filter(c -> c.name().equals(name)).findFirst();
        if (byName.isPresent()) {
            return byName.get().type();
        }
        int sep = name.lastIndexOf(Type.RelationType.PIVOT_SEPARATOR);
        if (sep >= 0 && !schema.dynamicColumns().isEmpty()) {
            String template = name.substring(sep + Type.RelationType.PIVOT_SEPARATOR.length());
            return schema.dynamicColumns().stream()
                    .filter(c -> c.name().equals(template)).findFirst()
                    .map(Type.Column::type)
                    .orElseThrow(() -> new IllegalStateException("pivot column '" + name
                            + "' matches no aggregate template " + schema.dynamicColumns().stream()
                                    .map(Type.Column::name).toList()));
        }
        return pureOfSqlType(sqlType);
    }

    /** The Pure primitive a DYNAMIC (pivot-generated) SQL column carries.
     * Every known name is EXPLICIT — an unrecognized SQL type is a gap in
     * this table, not a String (audit 15: the silent String default
     * corrupted result typing invisibly). */
    public static Type pureOfSqlType(String sqlType) {
        // V1.9 (Phase 8): the parameter suffix strips ONCE
        // ('DECIMAL(38,9)' -> 'DECIMAL'), then the table is EXACT-match
        // with a loud default — no prefix matching (the audited
        // startsWith arms could never say what they excluded).
        String t = sqlType.toUpperCase();
        int paren = t.indexOf('(');
        if (paren > 0) {
            t = t.substring(0, paren).strip();
        }
        return switch (t) {
            case "TINYINT", "SMALLINT", "INTEGER", "BIGINT", "HUGEINT" ->
                    Type.Primitive.INTEGER;
            case "FLOAT", "DOUBLE", "REAL" -> Type.Primitive.FLOAT;
            case "BOOLEAN" -> Type.Primitive.BOOLEAN;
            case "DATE" -> Type.Primitive.STRICT_DATE;
            case "TIMESTAMP" -> Type.Primitive.DATE_TIME;
            case "DECIMAL", "NUMERIC" -> Type.Primitive.DECIMAL;
            case "VARCHAR", "CHAR", "TEXT", "STRING", "BPCHAR" ->
                    Type.Primitive.STRING;
            default -> throw new IllegalStateException(
                    "no Pure primitive mapped for SQL type '" + sqlType
                    + "' (pivot-generated column) — add it to"
                    + " Executor.pureOfSqlType");
        };
    }

    /** Expand row-struct columns (navigate slots) to their prefixed flat set. */
    private static Type.RelationType flattenStructColumns(Type.RelationType schema) {
        if (schema.columns().stream().noneMatch(c -> c.type() instanceof Type.RelationType)) {
            return schema;
        }
        List<Type.Column> flat = new ArrayList<>();
        for (Type.Column c : schema.columns()) {
            if (c.type() instanceof Type.RelationType sub) {
                for (Type.Column sc : sub.columns()) {
                    flat.add(new Type.Column(c.name() + "_" + sc.name(),
                            sc.type(), sc.multiplicity()));
                }
            } else {
                flat.add(c);
            }
        }
        return new Type.RelationType(flat);
    }

}
