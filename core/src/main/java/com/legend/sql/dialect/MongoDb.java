// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend.sql.dialect;

import com.legend.sql.SqlAgg;
import com.legend.sql.SqlExpr;
import com.legend.sql.SqlFn;
import com.legend.sql.SqlQuery;
import com.legend.sql.SqlType;
import com.legend.sql.TypeFact;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The MongoDB target (docs/MONGODB_PLAN_2026_10_16.md, 2026-10-16; decisions D1, D2, D5, D7, D10, D11): the one query
 * compiler's render target whose text is an {@code aggregate} command -- a pipeline in MongoDB's Extended JSON --
 * rather than SQL. It extends the base renderer as every target does, so the dialect program's guards read it, and
 * overrides the writing of a query ({@link MongoDbPipeline}: the stages) and of an expression (here: MongoDB's
 * aggregation operators), keeping the base's compositions that are pure IR (the checked narrowing, the compaction).
 *
 * <p>What this dialect decides, in its own tables:
 * <ul>
 *   <li>every function ({@link #call}) and every aggregate ({@link #reducer}): a spelling, an emulation over
 *       MongoDB's operators, or a refusal by name -- never a fallback;</li>
 *   <li>SQL's three-valued comparisons over BSON's total ones (D7): an operator whose answer MongoDB defines over
 *       null is written through a null guard ({@link MongoJson#guarded}) where an operand may be null;</li>
 *   <li>a plan parameter as a marker document the connector replaces in place ({@code {"$legendParam": n}}), bound
 *       in the statement's order as every dialect's parameters are;</li>
 *   <li>an aggregate as a {@code $group} accumulator and the read of its field ({@link #accumulator}): the native
 *       accumulators where MongoDB has one, else the group's values pushed and reduced at the read (a distinct or
 *       ordered aggregate, a median, a quantile, a string aggregate);</li>
 *   <li>a raised error as a conversion that cannot succeed, its message between the platform's U+001F sentinels, so
 *       the connector extracts it as the executor extracts a SQL raise.</li>
 * </ul>
 * No server-side JavaScript is written (D11), and nothing from MongoDB's own documentation or code is copied.
 */
public final class MongoDb extends AnsiSqlRenderer {

    /** No quoting: a field or collection name is spelled as a JSON string, never delimited. */
    public static final Lexicon LEXICON = new Lexicon('"', Set.of());

    /** Lists are native BSON arrays; a correlated explosion, a JSON carrier and a native full join are not shaped. */
    public static final CarrierStrategies.Caps CAPS = new CarrierStrategies.Caps(true, false, false, false);

    /** The field a group stage computes an aggregate into, by its index ({@code __agg0}); the projection reads it
     *  through the read {@link #accumulator} registers. */
    static final String AGGREGATE_MARKER = "__agg";

    /** A group key's read after the group stage ({@code _id.k0}). */
    private static final String GROUP_KEY = "_id.k";

    private static final String RAISE_SENTINEL = "\u001f";
    private static final String POSITION_DIVIDER = "\u001e";

    private static final String G0 = "\"$$g0\"";
    private static final String G1 = "\"$$g1\"";
    private static final String G2 = "\"$$g2\"";
    private static final String THIS = "\"$$this\"";
    private static final String VALUE = "\"$$value\"";

    private static final List<String> DAY_NAMES = List.of("Sunday", "Monday", "Tuesday", "Wednesday", "Thursday",
            "Friday", "Saturday");
    private static final List<String> MONTH_NAMES = List.of("January", "February", "March", "April", "May", "June",
            "July", "August", "September", "October", "November", "December");

    /** The aggregates with no native accumulator, or whose exact answer needs the group's values: pushed into an
     *  array by the group stage and reduced at the read. */
    private static final Set<SqlAgg.Fn> PUSHED = Set.of(SqlAgg.Fn.VAR_SAMP, SqlAgg.Fn.VAR_POP, SqlAgg.Fn.VARIANCE,
            SqlAgg.Fn.MEDIAN, SqlAgg.Fn.STRING_AGG, SqlAgg.Fn.QUANTILE_CONT, SqlAgg.Fn.QUANTILE_DISC);

    public MongoDb() {
        super(LEXICON);
    }

    // ==================================================================
    // Scope: how a column reference spells inside the pipeline
    // ==================================================================

    /** One level of the pipeline: inside a lookup, the left row's aliases are {@code let} variables and the right
     *  source's rows are flat; at the top, every alias is a field of the current row. */
    private static final class Scope {
        final List<String> variableAliases;
        final @com.legend.base.Nullable String flatAlias;
        /** The reads of this level's group stage, by marker: filled by the group, read by the clauses after it. */
        final Map<String, SqlWriter.Piece> aggregateReads = new java.util.LinkedHashMap<>();

        Scope(List<String> variableAliases, @com.legend.base.Nullable String flatAlias) {
            this.variableAliases = variableAliases;
            this.flatAlias = flatAlias;
        }
    }

    private final Deque<Scope> scopes = new ArrayDeque<>();

    /** A lambda's parameters in scope, each the pipeline variable it reads as ({@code $$v_x}, or {@code $$this} and
     *  {@code $$value} inside a reduce). */
    private final Deque<Map.Entry<String, String>> lambdaVariables = new ArrayDeque<>();

    void pushLookupScope(List<String> left, String right) {
        scopes.push(new Scope(List.copyOf(left), right));
    }

    void popScope() {
        scopes.pop();
    }

    void aggregateReads(Map<String, SqlWriter.Piece> reads) {
        Scope scope = scopes.getFirst();
        scope.aggregateReads.clear();
        scope.aggregateReads.putAll(reads);
    }

    private void reset() {
        scopes.clear();
        scopes.push(new Scope(List.of(), null));
        lambdaVariables.clear();
    }

    // ==================================================================
    // The query: a pipeline
    // ==================================================================

    @Override
    protected SqlWriter query(SqlWriter writer, SqlQuery q, int depth) {
        if (depth != 0) {
            throw new DialectCapability("a nested query reached MongoDb as a subquery: only a relation's own"
                    + " pipeline is written");
        }
        reset();
        return new MongoDbPipeline(this).command(writer, q);
    }

    @Override
    protected java.util.List<com.legend.sql.SqlRewriter> passes() {
        return List.of(new CarrierStrategies(CAPS));
    }

    /** {@inheritDoc} A collection's {@code _id} declared {@code OTHER} (an objectId) is read as its hex text. */
    @Override
    protected boolean readsStored(com.legend.sql.SqlDdl.ColumnType t) {
        return t instanceof com.legend.sql.SqlDdl.ColumnType.Plain p
                && p.kind() == com.legend.sql.SqlDdl.ColumnType.Kind.OTHER;
    }

    @Override
    protected SqlWriter storedRead(SqlWriter writer, SqlExpr.StoredRead r) {
        return writer.append("{\"$toString\": ").expr(r.column(), 0).append("}");
    }

    /** {@inheritDoc} The engine's H2 text has no translation into a pipeline. */
    @Override
    public String fromEngineText(String statement) {
        throw new DialectCapability("the engine's H2 text reached MongoDb, which runs no SQL text");
    }

    // ==================================================================
    // Expressions
    // ==================================================================

    @Override
    protected SqlWriter expr(SqlWriter writer, SqlExpr e, int parentPrec) {
        return switch (e) {
            case SqlExpr.Group g -> writer.expr(g.inner(), 0);
            case SqlExpr.TempTableInSplice t -> throw new IllegalStateException("temp-table IN splice '"
                    + t.tempTableName() + "' reached an executable dialect — plan-text vocabulary only");
            case SqlExpr.PlanParam p -> placeholder(writer, oneValue(p));
            case SqlExpr.RowOrder r -> throw new DialectCapability("a physical row order reached MongoDb: a"
                    + " collection's documents have no row order to read");
            case SqlExpr.Column c -> column(writer, c);
            case SqlExpr.StoredRead r -> storedRead(writer, r);
            case SqlExpr.Star s -> throw new IllegalStateException("a star reached the expression writer: the"
                    + " projection stage merges the sources it names");
            case SqlExpr.StarExcept se -> throw new DialectCapability("a star with excluded columns reached MongoDb:"
                    + " no shape yet");
            case SqlExpr.StringLit s -> MongoJson.literal(writer, s.value());
            case SqlExpr.FormatLit fl -> MongoJson.literal(writer, formatText(fl));
            case SqlExpr.DatePartLit p -> throw new IllegalStateException("a date part (" + p.part()
                    + ") reached the expression writer: the arm of its call spells it");
            case SqlExpr.JsonKindLit k -> MongoJson.literal(writer, jsonKindName(k.kind()));
            case SqlExpr.TypeNameLit t -> MongoJson.literal(writer, typeofName(t.scalar()));
            case SqlExpr.IntLit i -> writer.append("{\"$numberLong\": \"").append(i.value()).append("\"}");
            case SqlExpr.FloatLit f -> writer.append("{\"$numberDouble\": \"").append(floatLiteral(f.value()))
                    .append("\"}");
            case SqlExpr.DecimalLit d -> writer.append("{\"$numberDecimal\": \"").append(d.value().toPlainString())
                    .append("\"}");
            case SqlExpr.BoolLit b -> writer.append(b.value() ? "true" : "false");
            case SqlExpr.NullLit n -> writer.append("null");
            case SqlExpr.DateLit d -> writer.append(MongoJson.dateLiteral(d.iso()));
            case SqlExpr.TimestampLit t -> writer.append(MongoJson.dateLiteral(t.iso()));
            case SqlExpr.TimeLit t -> throw new DialectCapability("a time-of-day literal reached MongoDb, which has"
                    + " no time type");
            case SqlExpr.OrderedListAgg ola -> throw new IllegalStateException("an ordered list aggregate reached"
                    + " the expression writer outside a group stage");
            case SqlExpr.ArrayLit a -> arrayLit(writer, a.elements());
            case SqlExpr.StructLit s -> structLit(writer, s);
            case SqlExpr.StructGet g -> structGet(writer, g);
            case SqlExpr.Call c -> call(writer, c, parentPrec);
            case SqlExpr.Case c -> caseExpr(writer, c);
            case SqlExpr.Exists ex -> throw new DialectCapability("an EXISTS subquery reached MongoDb: a correlated"
                    + " subquery has no shape yet");
            case SqlExpr.InSubquery i -> throw new DialectCapability("an IN subquery reached MongoDb: a subquery in"
                    + " expression position has no shape yet");
            case SqlExpr.CheckedDefects ignored -> throw new DialectCapability("nested checked defects reached"
                    + " MongoDb: no shape yet");
            case SqlExpr.CheckedChildValue ignored -> throw new DialectCapability("a checked child's value reached"
                    + " MongoDb: no shape yet");
            case SqlExpr.Quantified q -> throw new DialectCapability("a quantified comparison (ANY/ALL over a"
                    + " subquery) reached MongoDb: no shape yet");
            case SqlExpr.ScalarSubquery sq -> throw new DialectCapability("a scalar subquery reached MongoDb: no"
                    + " shape yet");
            case SqlExpr.CheckedOne co -> checkedOne(writer, co, parentPrec);
            case SqlExpr.CompactList cl -> compactList(writer, cl, parentPrec);
            case SqlExpr.DeferredTdsString d -> throw new IllegalStateException("deferred relation-toString reached"
                    + " the renderer — the execution boundary must resolve the dynamic column list first"
                    + " (DeferredTdsString id " + d.id() + ")");
            case SqlExpr.WindowCall w -> windowCall(writer, w);
            case SqlExpr.Lambda l -> lambda(writer, l);
            case SqlExpr.Cast c -> variantAwareCast(writer, c);
            case SqlExpr.FoldCall f -> foldCall(writer, f);
            case SqlExpr.JsonObject j -> jsonObject(writer, j);
            case SqlExpr.JsonArray j -> jsonArray(writer, j);
            case SqlExpr.JsonArrayAgg j -> jsonArrayAgg(writer, j);
            case SqlExpr.ReduceCollection rc -> reduceCollection(writer, rc);
            case SqlExpr.Membership m -> membership(writer, m);
            case SqlAgg.Reducer r -> reducer(writer, r);
        };
    }

    /** A column reference as a path: an aggregate's marker reads the group field; a group key reads {@code _id};
     *  a lambda's parameter is its variable; inside a lookup the left row's aliases are variables and the right
     *  source's row is flat; otherwise the row holds each source under its alias. */
    private SqlWriter column(SqlWriter writer, SqlExpr.Column c) {
        String name = c.name();
        String table = c.table();
        Scope scope = scopes.getFirst();
        if (table == null) {
            if (name.startsWith(AGGREGATE_MARKER)) {
                SqlWriter.Piece read = scope.aggregateReads.get(name);
                if (read == null) {
                    throw new IllegalStateException("aggregate marker " + name + " has no read in this scope");
                }
                return writer.piece(read);
            }
            if (name.startsWith(GROUP_KEY)) {
                return writer.append(MongoJson.string("$" + name));
            }
            for (Map.Entry<String, String> v : lambdaVariables) {
                if (v.getKey().equals(name)) {
                    return writer.append(MongoJson.string("$$" + v.getValue()));
                }
            }
            MongoJson.key(name);
            return writer.append(MongoJson.path(name));
        }
        MongoJson.key(name);
        if (scope.variableAliases.contains(table)) {
            return writer.append(MongoJson.string("$$" + MongoJson.variable(table) + "." + name));
        }
        if (table.equals(scope.flatAlias)) {
            return writer.append(MongoJson.path(name));
        }
        MongoJson.key(table);
        return writer.append(MongoJson.path(table, name));
    }

    /** A plan parameter: a marker the connector replaces in place by the bound value, numbered in the statement's
     *  binding order. */
    @Override
    protected SqlWriter placeholder(SqlWriter writer, SqlExpr.PlanParam p) {
        return writer.bind("{\"$legendParam\": " + writer.bound() + "}", scalarBind(p));
    }

    /** A parameter written where ONE value goes: a list parameter is bound only as a whole list. */
    private static SqlExpr.PlanParam oneValue(SqlExpr.PlanParam p) {
        if (p.type() instanceof TypeFact.Typed t && t.type() instanceof SqlType.Array) {
            throw new DialectCapability("list parameter '" + p.name() + "' is bound only as a whole list (in,"
                    + " contains); written where one value goes, it is not bound");
        }
        return p;
    }

    @Override
    protected SqlWriter arrayLit(SqlWriter writer, List<SqlExpr> elements) {
        return writer.append("[").list(elements).append("]");
    }

    /** A struct is a document: each field by its name. */
    @Override
    protected SqlWriter structLit(SqlWriter writer, SqlExpr.StructLit s) {
        writer.append("{");
        for (int i = 0; i < s.fields().size(); i++) {
            if (i > 0) {
                writer.append(", ");
            }
            writer.append(MongoJson.key(s.fields().get(i).name())).append(": ").expr(s.fields().get(i).value(), 0);
        }
        return writer.append("}");
    }

    @Override
    protected SqlWriter structGet(SqlWriter writer, SqlExpr.StructGet g) {
        return writer.append("{\"$getField\": {\"field\": ").append(MongoJson.key(g.field())).append(", \"input\": ")
                .expr(g.source(), 0).append("}}");
    }

    /** A CASE as {@code $switch}: a null condition takes no branch, as SQL's does; no ELSE answers null. */
    @Override
    protected SqlWriter caseExpr(SqlWriter writer, SqlExpr.Case c) {
        writer.append("{\"$switch\": {\"branches\": [");
        for (int i = 0; i < c.whens().size(); i++) {
            if (i > 0) {
                writer.append(", ");
            }
            writer.append("{\"case\": ").expr(c.whens().get(i).condition(), 0).append(", \"then\": ")
                    .expr(c.whens().get(i).then(), 0).append("}");
        }
        writer.append("], \"default\": ");
        if (c.otherwise() == null) {
            writer.append("null");
        } else {
            writer.expr(c.otherwise(), 0);
        }
        return writer.append("}}");
    }

    @Override
    protected SqlWriter windowCall(SqlWriter writer, SqlExpr.WindowCall w) {
        throw new DialectCapability("a window function reached MongoDb: $setWindowFields is not shaped yet");
    }

    @Override
    protected SqlWriter lambda(SqlWriter writer, SqlExpr.Lambda l) {
        throw new IllegalStateException("a lambda reached the expression writer outside the list operator that"
                + " applies it");
    }

    /** A cast as {@code $convert} to the BSON type the target names; a date to text in Pure's date spelling; a
     *  decimal rounded to its scale; a Variant (JSON) target is the value itself, a BSON value being the variant. */
    @Override
    protected SqlWriter variantAwareCast(SqlWriter writer, SqlExpr.Cast c) {
        SqlType target = c.target();
        if (target == SqlType.Scalar.JSON) {
            return writer.expr(c.value(), 0);
        }
        if (target instanceof SqlType.Decimal d) {
            return writer.append("{\"$round\": [{\"$convert\": {\"input\": ").expr(c.value(), 0)
                    .append(", \"to\": \"decimal\"}}, ").append(d.scale()).append("]}");
        }
        if (!(target instanceof SqlType.Scalar s)) {
            throw new DialectCapability("a cast to " + target + " reached MongoDb: only scalar and decimal targets"
                    + " convert");
        }
        boolean text = s == SqlType.Scalar.VARCHAR || s == SqlType.Scalar.LITERAL;
        if (text && (c.value() instanceof SqlExpr.JsonObject || c.value() instanceof SqlExpr.JsonArray)) {
            // the wire's JSON object as TEXT: written by the pipeline (D10), MongoDB having no JSON writer of its own
            return jsonText(writer, c.value());
        }
        if (text && c.value().type() instanceof TypeFact.Typed t && t.type() == SqlType.Scalar.JSON) {
            throw new DialectCapability("a Variant's JSON text reached MongoDb: no JSON text writer for a nested"
                    + " document yet");
        }
        if (text && temporal(c.value()) != null) {
            return writer.append("{\"$dateToString\": {\"date\": ").expr(c.value(), 0).append(", \"format\": ")
                    .append(MongoJson.string(temporal(c.value()) == SqlType.Scalar.DATE ? "%Y-%m-%d"
                            : "%Y-%m-%dT%H:%M:%S.%L+0000")).append("}}");
        }
        return writer.append("{\"$convert\": {\"input\": ").expr(c.value(), 0).append(", \"to\": ")
                .append(MongoJson.string(scalarTypeName(s))).append("}}");
    }

    /** The format a date's text takes in a wire: a date alone, or Pure's date-time to the millisecond. */
    private static String dateFormat(SqlExpr e) {
        return temporal(e) == SqlType.Scalar.DATE ? "%Y-%m-%d" : "%Y-%m-%dT%H:%M:%S.%L+0000";
    }

    /**
     * The JSON text of an object or an array the wire builds ({@code CAST(json_object(...) AS VARCHAR)}): the
     * pipeline writes it as text, key by key, each value's text by its kind at run time -- null, a string escaped and
     * quoted, a date in its format, a number or a boolean as its own text -- and a nested document refused (raised,
     * since the value's kind is the row's).
     */
    private SqlWriter jsonText(SqlWriter writer, SqlExpr value) {
        return switch (value) {
            case SqlExpr.JsonObject j -> objectText(writer, j);
            case SqlExpr.JsonArray a -> arrayText(writer, a.elements());
            default -> throw new IllegalStateException("not a JSON constructor: " + value);
        };
    }

    private SqlWriter objectText(SqlWriter writer, SqlExpr.JsonObject j) {
        writer.append("{\"$concat\": [\"{\"");
        for (int i = 0; i + 1 < j.kv().size(); i += 2) {
            if (!(j.kv().get(i) instanceof SqlExpr.StringLit key)) {
                throw new DialectCapability("a JSON object with a computed key reached MongoDb: a document's field"
                        + " names are spelled");
            }
            writer.append(", ").append(MongoJson.string((i > 0 ? "," : "") + MongoJson.string(key.value()) + ":"))
                    .append(", ");
            cellText(writer, j.kv().get(i + 1));
        }
        return writer.append(", \"}\"]}");
    }

    private SqlWriter arrayText(SqlWriter writer, List<SqlExpr> elements) {
        writer.append("{\"$concat\": [\"[\"");
        for (int i = 0; i < elements.size(); i++) {
            writer.append(i > 0 ? ", \",\", " : ", ");
            cellText(writer, elements.get(i));
        }
        return writer.append(", \"]\"]}");
    }

    /** One value's JSON text, by its kind at run time. */
    private SqlWriter cellText(SqlWriter writer, SqlExpr value) {
        return writer.append("{\"$let\": {\"vars\": {\"v\": ").expr(value, 0).append("}, \"in\": {\"$switch\":"
                + " {\"branches\": [{\"case\": {\"$eq\": [\"$$v\", null]}, \"then\": \"null\"},"
                + " {\"case\": {\"$eq\": [{\"$type\": \"$$v\"}, \"string\"]}, \"then\": {\"$concat\": [\"\\\"\", "
                + escapedJsonString() + ", \"\\\"\"]}},"
                + " {\"case\": {\"$eq\": [{\"$type\": \"$$v\"}, \"date\"]}, \"then\": {\"$concat\": [\"\\\"\","
                + " {\"$dateToString\": {\"date\": \"$$v\", \"format\": ").append(MongoJson.string(dateFormat(value)))
                .append("}}, \"\\\"\"]}},"
                + " {\"case\": {\"$in\": [{\"$type\": \"$$v\"}, [\"object\", \"array\"]]}, \"then\": ")
                .append(raiseText("a nested document in a JSON wire reached MongoDb: no JSON text writer for it yet"))
                .append("}], \"default\": {\"$toString\": \"$$v\"}}}}}");
    }

    /** {@code $$v}, a string, with JSON's escapes: a backslash, a quote, a newline, a return, a tab. */
    private static String escapedJsonString() {
        String s = "\"$$v\"";
        for (String[] r : List.of(new String[] {"\\", "\\\\"}, new String[] {"\"", "\\\""},
                new String[] {"\n", "\\n"}, new String[] {"\r", "\\r"}, new String[] {"\t", "\\t"})) {
            s = "{\"$replaceAll\": {\"input\": " + s + ", \"find\": " + MongoJson.string(r[0]) + ", \"replacement\": "
                    + MongoJson.string(r[1]) + "}}";
        }
        return s;
    }

    /** A raise of a fixed message, as {@link #raise} writes one: the message depends on the row, so the optimizer
     *  cannot fold it ({@link #UNFOLDED}). */
    private static String raiseText(String message) {
        return "{\"$convert\": {\"input\": {\"$concat\": [" + MongoJson.string(RAISE_SENTINEL + message
                + RAISE_SENTINEL) + ", " + UNFOLDED + "]}, \"to\": \"int\"}}";
    }

    /** DATE or TIMESTAMP when {@code e} is typed so; null otherwise. */
    private static SqlType.@com.legend.base.Nullable Scalar temporal(SqlExpr e) {
        return e.type() instanceof TypeFact.Typed t
                && (t.type() == SqlType.Scalar.DATE || t.type() == SqlType.Scalar.TIMESTAMP)
                ? (SqlType.Scalar) t.type() : null;
    }

    /** A fold as {@code $reduce}, the lambda's Pure order (element, accumulator) read as {@code $$this} and
     *  {@code $$value}; the empty (null) collection folds to the initial value. */
    @Override
    protected SqlWriter foldCall(SqlWriter writer, SqlExpr.FoldCall f) {
        List<String> params = f.lambda().params();
        if (params.size() != 2) {
            throw new IllegalStateException("a fold's lambda takes (element, accumulator): " + params);
        }
        writer.append("{\"$reduce\": {\"input\": {\"$ifNull\": [").expr(f.source(), 0)
                .append(", []]}, \"initialValue\": ").expr(f.init(), 0).append(", \"in\": ");
        withLambda(writer, f.lambda(), List.of("this", "value"), x -> x.expr(f.lambda().body(), 0));
        return writer.append("}}");
    }

    /** A JSON object: alternating literal keys and values, as a document. */
    @Override
    protected SqlWriter jsonObject(SqlWriter writer, SqlExpr.JsonObject j) {
        writer.append("{");
        for (int i = 0; i + 1 < j.kv().size(); i += 2) {
            if (i > 0) {
                writer.append(", ");
            }
            if (!(j.kv().get(i) instanceof SqlExpr.StringLit key)) {
                throw new DialectCapability("a JSON object with a computed key reached MongoDb: a document's field"
                        + " names are spelled");
            }
            writer.append(MongoJson.key(key.value())).append(": ").expr(j.kv().get(i + 1), 0);
        }
        return writer.append("}");
    }

    @Override
    protected SqlWriter jsonArray(SqlWriter writer, SqlExpr.JsonArray j) {
        return writer.append("[").list(j.elements()).append("]");
    }

    @Override
    protected SqlWriter jsonArrayAgg(SqlWriter writer, SqlExpr.JsonArrayAgg j) {
        throw new IllegalStateException("a JSON array aggregate reached the expression writer outside a group"
                + " stage");
    }

    /** A collection value reduced by an aggregate: its non-null elements, as the group reads reduce. */
    @Override
    protected SqlWriter reduceCollection(SqlWriter writer, SqlExpr.ReduceCollection rc) {
        SqlWriter.Piece nonNull = x -> nonNull(x, y -> y.expr(rc.collection(), 0));
        return arrayReduction(writer, rc.reducer(), nonNull, rc.extras());
    }

    /** {@code needle} in a collection value: null when the needle is null (SQL's answer); a list parameter is bound
     *  whole, as one array. */
    @Override
    protected SqlWriter membership(SqlWriter writer, SqlExpr.Membership m) {
        if (m.collection() instanceof SqlExpr.PlanParam p) {
            return MongoJson.guarded(writer, List.of(m.needle()), x -> x.append("{\"$in\": [" + G0 + ", ")
                    .bind("{\"$legendParam\": " + x.bound() + "}", listBind(p)).append("]}"));
        }
        return MongoJson.guardedOp(writer, "$in", List.of(m.needle(), m.collection()));
    }

    private static RenderedStatement.Bind listBind(SqlExpr.PlanParam p) {
        if (!(p.type() instanceof TypeFact.Typed t && t.type() instanceof SqlType.Array array
                && array.element() instanceof SqlType.Scalar element)) {
            throw new DialectCapability("plan parameter '" + p.name() + "' is not a list parameter (an array of a"
                    + " named element type): it is not bound as a whole list");
        }
        return new RenderedStatement.Bind(p.name(), new RenderedStatement.Binding.Array(element.name()));
    }

    // ==================================================================
    // Functions
    // ==================================================================

    @Override
    protected SqlWriter call(SqlWriter writer, SqlExpr.Call c, int parentPrec) {
        List<SqlExpr> a = c.args();
        // every function, each by name: MongoDB's operator, an emulation over its operators, or a refusal
        return switch (c.fn()) {
            // ---- logic: MongoDB's AND and OR read null as false, which is SQL's answer where a predicate decides;
            // NOT answers null for null (D7)
            case AND -> MongoJson.op(writer, "$and", a);
            case OR -> MongoJson.op(writer, "$or", a);
            case NOT -> MongoJson.guardedOp(writer, "$not", a);
            // ---- comparison: three-valued over BSON's total comparisons (D7)
            case EQUAL -> MongoJson.guardedOp(writer, "$eq", a);
            case NOT_EQUAL -> MongoJson.guardedOp(writer, "$ne", a);
            case LESS -> MongoJson.guardedOp(writer, "$lt", a);
            case LESS_EQUAL -> MongoJson.guardedOp(writer, "$lte", a);
            case GREATER -> MongoJson.guardedOp(writer, "$gt", a);
            case GREATER_EQUAL -> MongoJson.guardedOp(writer, "$gte", a);
            case IS_DISTINCT_FROM, NULL_SAFE_NOT_EQUAL -> MongoJson.op(writer, "$ne", a);
            case NULL_SAFE_EQUAL -> MongoJson.op(writer, "$eq", a);
            case XOR -> MongoJson.guardedOp(writer, "$ne", a);
            // ---- arithmetic: MongoDB's operators answer null for a null operand already
            case PLUS -> MongoJson.op(writer, "$add", a);
            case MINUS -> MongoJson.op(writer, "$subtract", a);
            case TIMES -> MongoJson.op(writer, "$multiply", a);
            case DIVIDE -> MongoJson.op(writer, "$divide", a);
            case MOD -> flooredMod(writer, a);
            case REM -> MongoJson.op(writer, "$mod", a);
            case NEGATE -> negated(writer, a);
            case ABS -> MongoJson.op1(writer, "$abs", a.get(0));
            case INT_DIVIDE -> intDivide(writer, a);
            // ---- null and membership
            case IS_NULL -> nullTest(writer, a, "$eq");
            case IS_NOT_NULL -> nullTest(writer, a, "$ne");
            case IN -> inList(writer, a);
            case COALESCE -> coalesce(writer, a);
            // ---- numbers
            case PARSE_INT -> MongoJson.op1(writer, "$toLong", a.get(0));
            case SQRT -> MongoJson.op1(writer, "$sqrt", a.get(0));
            case CBRT -> cubeRoot(writer, a);
            case EXP -> MongoJson.op1(writer, "$exp", a.get(0));
            case LN -> MongoJson.op1(writer, "$ln", a.get(0));
            case LOG10 -> MongoJson.op1(writer, "$log10", a.get(0));
            case POW -> MongoJson.op(writer, "$pow", a);
            case PI -> writer.append("{\"$numberDouble\": \"3.141592653589793\"}");
            case SIN -> MongoJson.op1(writer, "$sin", a.get(0));
            case COS -> MongoJson.op1(writer, "$cos", a.get(0));
            case TAN -> MongoJson.op1(writer, "$tan", a.get(0));
            case ASIN -> domainGuarded(writer, "$asin", a);
            case ACOS -> domainGuarded(writer, "$acos", a);
            case ATAN -> MongoJson.op1(writer, "$atan", a.get(0));
            case ATAN2 -> MongoJson.op(writer, "$atan2", a);
            case SINH -> MongoJson.op1(writer, "$sinh", a.get(0));
            case COSH -> MongoJson.op1(writer, "$cosh", a.get(0));
            case TANH -> MongoJson.op1(writer, "$tanh", a.get(0));
            case COT -> cotangent(writer, a);
            case RADIANS -> MongoJson.op1(writer, "$degreesToRadians", a.get(0));
            case DEGREES -> MongoJson.op1(writer, "$radiansToDegrees", a.get(0));
            case CEILING -> longOf(writer, "$ceil", a);
            case FLOOR -> longOf(writer, "$floor", a);
            case FLOOR_RAW -> MongoJson.op1(writer, "$floor", a.get(0));
            case ROUND -> roundHalfEven(writer, a);
            case ROUND_HALF_UP -> roundHalfUp(writer, a);
            case SIGN -> sign(writer, a);
            case GREATEST -> MongoJson.op(writer, "$max", a);
            case LEAST -> MongoJson.op(writer, "$min", a);
            case BIT_AND, BIT_OR, BIT_XOR, BIT_SHIFT_LEFT, BIT_SHIFT_RIGHT -> bitOp(writer, c.fn(), a);
            case BIT_NOT -> MongoJson.op1(writer, "$bitNot", a.get(0));
            case HASH -> hashSigned(writer, a);
            // ---- strings (a string operator over null errors or answers "", so each is null-guarded)
            case CONCAT -> concatSkippingNull(writer, a);
            case CONCAT_JOIN -> MongoJson.op(writer, "$concat", a);
            case LENGTH -> length(writer, a);
            case UPPER -> MongoJson.guardedOp(writer, "$toUpper", a);
            case LOWER -> MongoJson.guardedOp(writer, "$toLower", a);
            case SUBSTRING -> substring(writer, a);
            case STRPOS -> MongoJson.guarded(writer, a, MongoJson.text("{\"$add\": [{\"$indexOfCP\": [" + G0 + ", "
                    + G1 + "]}, 1]}"));
            case STARTS_WITH -> MongoJson.guarded(writer, a, MongoJson.text("{\"$eq\": [{\"$indexOfCP\": [" + G0
                    + ", " + G1 + "]}, 0]}"));
            case ENDS_WITH -> endsWith(writer, a);
            case MATCHES -> regexMatch(writer, a, false);
            case REGEXP_FULL_MATCH -> regexMatch(writer, a, true);
            case REGEXP_EXTRACT -> regexExtract(writer, a);
            case REGEXP_EXTRACT_ALL -> regexExtractAll(writer, a);
            case REGEXP_REPLACE -> throw new DialectCapability("a regular-expression replace reached MongoDb,"
                    + " which has no operator for one ($replaceAll replaces text)");
            case LEFT -> MongoJson.guarded(writer, a, MongoJson.text("{\"$substrCP\": [" + G0 + ", 0, {\"$max\":"
                    + " [0, {\"$min\": [" + G1 + ", {\"$strLenCP\": " + G0 + "}]}]}]}"));
            case RIGHT -> rightChars(writer, a);
            case LPAD -> pad(writer, a, true);
            case RPAD -> pad(writer, a, false);
            case TRIM -> trim(writer, "$trim", a);
            case LTRIM -> trim(writer, "$ltrim", a);
            case RTRIM -> trim(writer, "$rtrim", a);
            case REPLACE -> replace(writer, a);
            case SPLIT -> MongoJson.op(writer, "$split", a);
            case SPLIT_PART -> splitPart(writer, a, false);
            case PURE_SPLIT_PART -> splitPartCall(writer, a);
            case TRANSLATE -> throw new DialectCapability("translate reached MongoDb, which has no character-map"
                    + " operator");
            case REVERSE_STRING -> MongoJson.guarded(writer, a, MongoJson.text("{\"$reduce\": {\"input\":"
                    + " {\"$range\": [0, {\"$strLenCP\": " + G0 + "}]}, \"initialValue\": \"\", \"in\":"
                    + " {\"$concat\": [{\"$substrCP\": [" + G0 + ", " + THIS + ", 1]}, " + VALUE + "]}}}"));
            case ASCII_CODE -> throw new DialectCapability("ascii reached MongoDb, which has no character-code"
                    + " operator");
            case CHR -> throw new DialectCapability("char reached MongoDb, which has no code-to-character operator");
            case UC_FIRST -> firstCased(writer, a, "$toUpper");
            case LC_FIRST -> firstCased(writer, a, "$toLower");
            case REPEAT_STR -> MongoJson.guarded(writer, a, MongoJson.text("{\"$reduce\": {\"input\": {\"$range\":"
                    + " [0, {\"$max\": [0, " + G1 + "]}]}, \"initialValue\": \"\", \"in\": {\"$concat\": [" + VALUE
                    + ", " + G0 + "]}}}"));
            case ENCODE_BASE64, DECODE_BASE64 -> throw new DialectCapability(c.fn() + " reached MongoDb, which has"
                    + " no base64 operator");
            case LEVENSHTEIN, JARO_WINKLER -> throw new DialectCapability(c.fn() + " reached MongoDb, which has no"
                    + " string-distance operator");
            case GUID -> throw new DialectCapability("generateGuid reached MongoDb, whose pipeline has no uuid"
                    + " operator");
            case FORMAT -> throw new DialectCapability("format reached MongoDb, which has no printf operator");
            case MD5, SHA1, SHA256 -> throw new DialectCapability(c.fn() + " reached MongoDb, which has no digest"
                    + " operator");
            case CURRENT_USER_FN -> throw new DialectCapability("currentUserId reached MongoDb, whose pipeline"
                    + " names no user");
            // ---- dates
            case EXTRACT -> extract(writer, a);
            case TODAY -> writer.append("{\"$dateTrunc\": {\"date\": \"$$NOW\", \"unit\": \"day\"}}");
            case NOW -> writer.append("\"$$NOW\"");
            case DATE_TRUNC_DAY -> dateTrunc(writer, a.get(0), com.legend.sql.DatePart.DAY);
            case DATE_TRUNC -> dateTrunc(writer, a.get(1), part(a.get(0)));
            case MAKE_DATE, MAKE_TIMESTAMP -> dateFromParts(writer, a);
            case ADD_INTERVAL, ADD_INTERVAL_TEMPORAL -> dateAdd(writer, a);
            case DATE_DIFF -> dateDiff(writer, a);
            case TIME_BUCKET -> throw new DialectCapability("timeBucket reached MongoDb: $dateTrunc's bin size is"
                    + " not mapped to the engine's bucket origin yet");
            case TIMEZONE -> throw new DialectCapability("a time zone conversion reached MongoDb: not shaped yet");
            case DATE_AT_TIME -> throw new DialectCapability("a date at a time of day reached MongoDb, which has"
                    + " no time type");
            case EPOCH_NS, FROM_EPOCH_NS -> throw new DialectCapability(c.fn() + " reached MongoDb, whose date"
                    + " holds milliseconds");
            case EPOCH_SECONDS -> writer.append("{\"$divide\": [{\"$toLong\": ").expr(a.get(0), 0)
                    .append("}, 1000]}");
            case EPOCH_MS -> MongoJson.op1(writer, "$toLong", a.get(0));
            case FROM_EPOCH_SECONDS -> writer.append("{\"$toDate\": {\"$multiply\": [").expr(a.get(0), 0)
                    .append(", 1000]}}");
            case FROM_EPOCH_MS -> MongoJson.op1(writer, "$toDate", a.get(0));
            case DAYNAME -> calendarName(writer, a, DAY_NAMES, "$dayOfWeek");
            case MONTHNAME -> calendarName(writer, a, MONTH_NAMES, "$month");
            case STRFTIME -> writer.append("{\"$dateToString\": {\"date\": ").expr(a.get(0), 0)
                    .append(", \"format\": ").expr(a.get(1), 0).append("}}");
            case STRPTIME -> writer.append("{\"$dateFromString\": {\"dateString\": ").expr(a.get(0), 0)
                    .append(", \"format\": ").expr(a.get(1), 0).append("}}");
            case PARSE_DATE -> writer.append("{\"$dateFromString\": {\"dateString\": ").expr(a.get(0), 0)
                    .append("}}");
            // ---- lists (BSON arrays)
            case LIST_FILTER -> listFilter(writer, a);
            case LIST_TRANSFORM -> listMap(writer, a);
            case LIST_CONCAT -> MongoJson.op(writer, "$concatArrays", a);
            case LIST_GET -> writer.append("{\"$arrayElemAt\": [").expr(a.get(0), 0).append(", {\"$subtract\": [")
                    .expr(a.get(1), 0).append(", 1]}]}");
            case LIST_POSITION -> listPosition(writer, a);
            case LIST_EXISTS -> listExists(writer, a);
            case LIST_FOR_ALL -> listForAll(writer, a);
            case LIST_LENGTH, JSON_ARRAY_LENGTH -> MongoJson.guardedOp(writer, "$size", a);
            case LIST_FLATTEN -> writer.append("{\"$reduce\": {\"input\": ").expr(a.get(0), 0)
                    .append(", \"initialValue\": [], \"in\": {\"$concatArrays\": [" + VALUE + ", " + THIS + "]}}}");
            case ALL_DISTINCT -> allDistinct(writer, a);
            case LIST_ZIP, LIST_DISTINCT, LIST_APPEND, LIST_SUM, LIST_MIN, LIST_MAX, LIST_AVG, LIST_MEDIAN, LIST_MODE,
                 LIST_SORT, LIST_SORT_DESC, LIST_TAIL, LIST_INIT, RANGE_FN, LIST_PRODUCT, LIST_REDUCE, LIST_SLICE,
                 REPEAT_VALUE, LIST_BOOL_AND, LIST_BOOL_OR, LIST_REVERSE, TYPEOF -> listCall(writer, c.fn(), a);
            case UNNEST -> unnestProjection(writer, a);
            case STRUCT_INSERT -> structInsert(writer, a);
            // ---- maps (documents)
            case MAP_FROM_LISTS -> writer.append("{\"$arrayToObject\": {\"$zip\": {\"inputs\": [").list(a)
                    .append("]}}}");
            case MAP_FROM_ENTRIES -> throw new DialectCapability("a map from entries reached MongoDb: the entry"
                    + " shape is not mapped yet");
            case MAP_EMPTY -> writer.append("{\"$literal\": {}}");
            case MAP_EXTRACT -> mapExtract(writer, a);
            case MAP_KEYS -> mapSide(writer, a, "k");
            case MAP_VALUES -> mapSide(writer, a, "v");
            case MAP_CONCAT -> MongoJson.op(writer, "$mergeObjects", a);
            // ---- variants (a BSON value is the variant)
            case TO_VARIANT -> variantConstruct(writer, a);
            case VARIANT_ELEMENTS -> variantElements(writer, a);
            case VARIANT_GET -> variantGet(writer, a);
            case JSON_TYPE -> jsonType(writer, a);
            case JSON_MERGE_PATCH -> mergePatch(writer, a);
            case JSON_PRETTY -> throw new DialectCapability("a pretty-printed JSON text reached MongoDb, which has"
                    + " no JSON text operator");
            case BOOL_TO_TEXT -> MongoJson.op1(writer, "$toString", a.get(0));
            case FLOAT_CANON -> floatCanonByText(writer, a, parentPrec);
            // ---- raising
            case ERROR -> raise(writer, a);
        };
    }

    // ---- the arms' helpers, each one spelling

    /** {@code ((a mod b) + b) mod b}: the always-positive modulus. */
    private static SqlWriter flooredMod(SqlWriter writer, List<SqlExpr> a) {
        return writer.append("{\"$mod\": [{\"$add\": [{\"$mod\": [").expr(a.get(0), 0).append(", ")
                .expr(a.get(1), 0).append("]}, ").expr(a.get(1), 0).append("]}, ").expr(a.get(1), 0).append("]}");
    }

    private static SqlWriter negated(SqlWriter writer, List<SqlExpr> a) {
        return writer.append("{\"$multiply\": [").expr(a.get(0), 0).append(", -1]}");
    }

    private static SqlWriter intDivide(SqlWriter writer, List<SqlExpr> a) {
        return writer.append("{\"$toLong\": {\"$trunc\": [{\"$divide\": [").expr(a.get(0), 0).append(", ")
                .expr(a.get(1), 0).append("]}, 0]}}");
    }

    /** {@code {"$eq": [x, null]}}: a missing field is null too. */
    private static SqlWriter nullTest(SqlWriter writer, List<SqlExpr> a, String op) {
        return writer.append("{\"").append(op).append("\": [").expr(a.get(0), 0).append(", null]}");
    }

    /** {@code x IN (a, b, ...)}: null for a null needle. */
    private static SqlWriter inList(SqlWriter writer, List<SqlExpr> a) {
        List<SqlExpr> rest = a.subList(1, a.size());
        return MongoJson.guarded(writer, List.of(a.get(0)),
                x -> x.append("{\"$in\": [" + G0 + ", [").list(rest).append("]]}"));
    }

    /** {@code $ifNull}, which takes two or more arguments; one argument is itself. */
    private static SqlWriter coalesce(SqlWriter writer, List<SqlExpr> a) {
        return a.size() == 1 ? writer.expr(a.get(0), 0) : MongoJson.op(writer, "$ifNull", a);
    }

    /** A cube root with the sign of its argument ({@code $pow} of a negative base errors). */
    private static SqlWriter cubeRoot(SqlWriter writer, List<SqlExpr> a) {
        return MongoJson.guarded(writer, a, MongoJson.text("{\"$cond\": [{\"$lt\": [" + G0 + ", 0]}, {\"$multiply\":"
                + " [-1, {\"$pow\": [{\"$abs\": " + G0 + "}, 0.3333333333333333]}]}, {\"$pow\": [" + G0
                + ", 0.3333333333333333]}]}"));
    }

    /** NaN outside [-1, 1], the engine's answer, where MongoDB's operator errors. */
    private static SqlWriter domainGuarded(SqlWriter writer, String op, List<SqlExpr> a) {
        return MongoJson.guarded(writer, a, MongoJson.text("{\"$cond\": [{\"$and\": [{\"$gte\": [" + G0 + ", -1]},"
                + " {\"$lte\": [" + G0 + ", 1]}]}, {\"" + op + "\": " + G0 + "}, {\"$numberDouble\": \"NaN\"}]}"));
    }

    private static SqlWriter cotangent(SqlWriter writer, List<SqlExpr> a) {
        return writer.append("{\"$divide\": [1, {\"$tan\": ").expr(a.get(0), 0).append("}]}");
    }

    /** {@code ceil}/{@code floor} as an integer. */
    private static SqlWriter longOf(SqlWriter writer, String op, List<SqlExpr> a) {
        return writer.append("{\"$toLong\": {\"").append(op).append("\": ").expr(a.get(0), 0).append("}}");
    }

    /** {@code $round} rounds half to even, Pure's banker's rounding. */
    @Override
    protected SqlWriter roundHalfEven(SqlWriter writer, List<SqlExpr> a) {
        return writer.append("{\"$round\": [").expr(a.get(0), 0).append(", ")
                .piece(x -> scaleOrZero(x, a)).append("]}");
    }

    private static SqlWriter scaleOrZero(SqlWriter writer, List<SqlExpr> a) {
        return a.size() > 1 ? writer.expr(a.get(1), 0) : writer.append("0");
    }

    /** Half away from zero at a scale: {@code trunc(x * 10^s + sign(x) / 2) / 10^s}. */
    private static SqlWriter roundHalfUp(SqlWriter writer, List<SqlExpr> a) {
        writer.append("{\"$let\": {\"vars\": {\"x\": ").expr(a.get(0), 0).append(", \"f\": {\"$pow\": [10, ");
        scaleOrZero(writer, a);
        return writer.append("]}}, \"in\": {\"$divide\": [{\"$trunc\": [{\"$add\": [{\"$multiply\": [\"$$x\","
                + " \"$$f\"]}, {\"$cond\": [{\"$lt\": [\"$$x\", 0]}, -0.5, 0.5]}]}, 0]}, \"$$f\"]}}}");
    }

    private static SqlWriter sign(SqlWriter writer, List<SqlExpr> a) {
        return MongoJson.guarded(writer, a, MongoJson.text("{\"$cond\": [{\"$gt\": [" + G0 + ", 0]}, 1, {\"$cond\":"
                + " [{\"$lt\": [" + G0 + ", 0]}, -1, 0]}]}"));
    }

    /** {@code $bitAnd}, {@code $bitOr}, {@code $bitXor} over integers; a shift has no operator. */
    @Override
    protected SqlWriter bitOp(SqlWriter writer, SqlFn fnName, List<SqlExpr> a) {
        return switch (fnName) {
            case BIT_AND -> MongoJson.op(writer, "$bitAnd", a);
            case BIT_OR -> MongoJson.op(writer, "$bitOr", a);
            case BIT_XOR -> MongoJson.op(writer, "$bitXor", a);
            case BIT_SHIFT_LEFT, BIT_SHIFT_RIGHT -> throw new DialectCapability(fnName + " reached MongoDb, which"
                    + " has no bit-shift operator");
            default -> throw new IllegalStateException("not a bit operation: " + fnName);
        };
    }

    @Override
    protected SqlWriter hashSigned(SqlWriter writer, List<SqlExpr> a) {
        throw new DialectCapability("hashCode reached MongoDb: its hashed index key is not Pure's hash");
    }

    /** Null-skipping concat: each argument's null is the empty string, nested concats flattened. */
    private static SqlWriter concatSkippingNull(SqlWriter writer, List<SqlExpr> a) {
        writer.append("{\"$concat\": [");
        List<SqlExpr> flat = flattenConcat(a);
        for (int i = 0; i < flat.size(); i++) {
            if (i > 0) {
                writer.append(", ");
            }
            writer.append("{\"$ifNull\": [").expr(flat.get(i), 0).append(", \"\"]}");
        }
        return writer.append("]}");
    }

    /** The length in code points; a number's, of its text (the corpus spells length() over numbers). */
    private static SqlWriter length(SqlWriter writer, List<SqlExpr> a) {
        boolean text = a.get(0).type() instanceof TypeFact.Typed t && (t.type() == SqlType.Scalar.VARCHAR
                || t.type() == SqlType.Scalar.LITERAL) || a.get(0) instanceof SqlExpr.StringLit;
        return MongoJson.guarded(writer, a, MongoJson.text(text ? "{\"$strLenCP\": " + G0 + "}"
                : "{\"$strLenCP\": {\"$toString\": " + G0 + "}}"));
    }

    /** 1-based SQL substring, its start and length clamped at zero as the engine's substring is. */
    private static SqlWriter substring(SqlWriter writer, List<SqlExpr> a) {
        String len = a.size() > 2 ? "{\"$max\": [0, " + G2 + "]}" : "{\"$strLenCP\": " + G0 + "}";
        return MongoJson.guarded(writer, a, MongoJson.text("{\"$substrCP\": [" + G0 + ", {\"$max\": [0,"
                + " {\"$subtract\": [" + G1 + ", 1]}]}, " + len + "]}"));
    }

    private static SqlWriter endsWith(SqlWriter writer, List<SqlExpr> a) {
        return MongoJson.guarded(writer, a, MongoJson.text("{\"$let\": {\"vars\": {\"ls\": {\"$strLenCP\": " + G0
                + "}, \"lp\": {\"$strLenCP\": " + G1 + "}}, \"in\": {\"$and\": [{\"$gte\": [\"$$ls\", \"$$lp\"]},"
                + " {\"$eq\": [{\"$substrCP\": [" + G0 + ", {\"$subtract\": [\"$$ls\", \"$$lp\"]}, \"$$lp\"]}, "
                + G1 + "]}]}}}"));
    }

    /** {@code $regexMatch}; a full match anchors the pattern. */
    private static SqlWriter regexMatch(SqlWriter writer, List<SqlExpr> a, boolean full) {
        String regex = full ? "{\"$concat\": [\"^(?:\", " + G1 + ", \")$\"]}" : G1;
        return MongoJson.guarded(writer, a, MongoJson.text("{\"$regexMatch\": {\"input\": " + G0 + ", \"regex\": "
                + regex + "}}"));
    }

    /** The first match, or its group; the empty string when nothing matches (the engine's answer). */
    private static SqlWriter regexExtract(SqlWriter writer, List<SqlExpr> a) {
        String found = a.size() > 2 ? "{\"$ifNull\": [{\"$arrayElemAt\": [\"$$m.captures\", {\"$subtract\": [" + G2
                + ", 1]}]}, \"\"]}" : "\"$$m.match\"";
        return MongoJson.guarded(writer, a, MongoJson.text("{\"$let\": {\"vars\": {\"m\": {\"$regexFind\":"
                + " {\"input\": " + G0 + ", \"regex\": " + G1 + "}}}, \"in\": {\"$cond\": [{\"$eq\": [\"$$m\","
                + " null]}, \"\", " + found + "]}}}"));
    }

    private static SqlWriter regexExtractAll(SqlWriter writer, List<SqlExpr> a) {
        String each = a.size() > 2 ? "{\"$arrayElemAt\": [\"$$this.captures\", {\"$subtract\": [" + G2 + ", 1]}]}"
                : "\"$$this.match\"";
        return MongoJson.guarded(writer, a, MongoJson.text("{\"$map\": {\"input\": {\"$regexFindAll\": {\"input\": "
                + G0 + ", \"regex\": " + G1 + "}}, \"in\": " + each + "}}"));
    }

    private static SqlWriter rightChars(SqlWriter writer, List<SqlExpr> a) {
        return MongoJson.guarded(writer, a, MongoJson.text("{\"$let\": {\"vars\": {\"n\": {\"$max\": [0, {\"$min\":"
                + " [" + G1 + ", {\"$strLenCP\": " + G0 + "}]}]}}, \"in\": {\"$substrCP\": [" + G0
                + ", {\"$subtract\": [{\"$strLenCP\": " + G0 + "}, \"$$n\"]}, \"$$n\"]}}}"));
    }

    /** {@code lpad}/{@code rpad}: the string cut to the length, or the pad text repeated and cut to fill it. */
    private static SqlWriter pad(SqlWriter writer, List<SqlExpr> a, boolean left) {
        String padText = a.size() > 2 ? G2 : "\" \"";
        String fill = "{\"$substrCP\": [{\"$reduce\": {\"input\": {\"$range\": [0, \"$$k\"]}, \"initialValue\": \"\","
                + " \"in\": {\"$concat\": [" + VALUE + ", " + padText + "]}}}, 0, \"$$k\"]}";
        String padded = left ? "{\"$concat\": [" + fill + ", " + G0 + "]}"
                : "{\"$concat\": [" + G0 + ", " + fill + "]}";
        return MongoJson.guarded(writer, a, MongoJson.text("{\"$let\": {\"vars\": {\"k\": {\"$subtract\": [" + G1
                + ", {\"$strLenCP\": " + G0 + "}]}}, \"in\": {\"$cond\": [{\"$lte\": [\"$$k\", 0]}, {\"$substrCP\":"
                + " [" + G0 + ", 0, {\"$max\": [0, " + G1 + "]}]}, " + padded + "]}}}"));
    }

    private static SqlWriter trim(SqlWriter writer, String op, List<SqlExpr> a) {
        writer.append("{\"").append(op).append("\": {\"input\": ").expr(a.get(0), 0);
        if (a.size() > 1) {
            writer.append(", \"chars\": ").expr(a.get(1), 0);
        }
        return writer.append("}}");
    }

    private static SqlWriter replace(SqlWriter writer, List<SqlExpr> a) {
        return writer.append("{\"$replaceAll\": {\"input\": ").expr(a.get(0), 0).append(", \"find\": ")
                .expr(a.get(1), 0).append(", \"replacement\": ").expr(a.get(2), 0).append("}}");
    }

    /** The n-th (1-based) part of a split; dropping empty parts for Pure's splitPart. */
    private static SqlWriter splitPart(SqlWriter writer, List<SqlExpr> a, boolean dropEmpty) {
        String parts = dropEmpty ? "{\"$filter\": {\"input\": {\"$split\": [" + G0 + ", " + G1 + "]}, \"cond\":"
                + " {\"$ne\": [" + THIS + ", \"\"]}}}" : "{\"$split\": [" + G0 + ", " + G1 + "]}";
        return MongoJson.guarded(writer, a, MongoJson.text("{\"$let\": {\"vars\": {\"p\": " + parts + "}, \"in\":"
                + " {\"$cond\": [{\"$gt\": [" + G2 + ", {\"$size\": \"$$p\"}]}, null, {\"$arrayElemAt\": [\"$$p\","
                + " {\"$subtract\": [" + G2 + ", 1]}]}]}}}"));
    }

    @Override
    protected SqlWriter splitPartCall(SqlWriter writer, List<SqlExpr> a) {
        return splitPart(writer, a, true);
    }

    private static SqlWriter firstCased(SqlWriter writer, List<SqlExpr> a, String op) {
        return MongoJson.guarded(writer, a, MongoJson.text("{\"$concat\": [{\"" + op + "\": {\"$substrCP\": [" + G0
                + ", 0, 1]}}, {\"$substrCP\": [" + G0 + ", 1, {\"$strLenCP\": " + G0 + "}]}]}"));
    }

    /** A date part: MongoDB's operator for it; a part finer than a millisecond has none. */
    private static SqlWriter extract(SqlWriter writer, List<SqlExpr> a) {
        com.legend.sql.DatePart part = part(a.get(0));
        SqlExpr date = a.get(1);
        return switch (part) {
            case YEAR -> MongoJson.op1(writer, "$year", date);
            case QUARTER -> writer.append("{\"$toLong\": {\"$ceil\": {\"$divide\": [{\"$month\": ").expr(date, 0)
                    .append("}, 3]}}}");
            case MONTH -> MongoJson.op1(writer, "$month", date);
            case WEEK -> MongoJson.op1(writer, "$isoWeek", date);
            case DAY -> MongoJson.op1(writer, "$dayOfMonth", date);
            case HOUR -> MongoJson.op1(writer, "$hour", date);
            case MINUTE -> MongoJson.op1(writer, "$minute", date);
            case SECOND -> MongoJson.op1(writer, "$second", date);
            case MILLISECOND -> MongoJson.op1(writer, "$millisecond", date);
            case DAY_OF_YEAR -> MongoJson.op1(writer, "$dayOfYear", date);
            case ISO_DAY_OF_WEEK -> MongoJson.op1(writer, "$isoDayOfWeek", date);
            case MICROSECOND, NANOSECOND -> throw new DialectCapability("a " + part + " reached MongoDb, whose"
                    + " date holds milliseconds");
        };
    }

    /** A date part as {@code $dateTrunc}'s, {@code $dateAdd}'s and {@code $dateDiff}'s unit; weeks start Monday. */
    private static String unit(com.legend.sql.DatePart part) {
        return switch (part) {
            case YEAR -> "year";
            case QUARTER -> "quarter";
            case MONTH -> "month";
            case WEEK -> "week";
            case DAY -> "day";
            case HOUR -> "hour";
            case MINUTE -> "minute";
            case SECOND -> "second";
            case MILLISECOND -> "millisecond";
            case MICROSECOND, NANOSECOND, DAY_OF_YEAR, ISO_DAY_OF_WEEK -> throw new DialectCapability("a " + part
                    + " unit reached MongoDb, which has no such date unit");
        };
    }

    private static SqlWriter dateTrunc(SqlWriter writer, SqlExpr date, com.legend.sql.DatePart part) {
        return writer.append("{\"$dateTrunc\": {\"date\": ").expr(date, 0).append(", \"unit\": \"")
                .append(unit(part)).append("\", \"startOfWeek\": \"monday\"}}");
    }

    /** {@code (unit, amount, date)}: {@code $dateAdd}. */
    private static SqlWriter dateAdd(SqlWriter writer, List<SqlExpr> a) {
        return writer.append("{\"$dateAdd\": {\"startDate\": ").expr(a.get(2), 0).append(", \"unit\": \"")
                .append(unit(part(a.get(0)))).append("\", \"amount\": {\"$toLong\": ").expr(a.get(1), 0)
                .append("}}}");
    }

    /** {@code (unit, start, end)}: {@code $dateDiff}, the unit boundaries crossed. */
    private static SqlWriter dateDiff(SqlWriter writer, List<SqlExpr> a) {
        return writer.append("{\"$dateDiff\": {\"startDate\": ").expr(a.get(1), 0).append(", \"endDate\": ")
                .expr(a.get(2), 0).append(", \"unit\": \"").append(unit(part(a.get(0))))
                .append("\", \"startOfWeek\": \"monday\"}}");
    }

    /** {@code (year, month, day[, hour, minute, second])}: {@code $dateFromParts}, a fractional second split into
     *  its milliseconds. */
    private static SqlWriter dateFromParts(SqlWriter writer, List<SqlExpr> a) {
        writer.append("{\"$dateFromParts\": {\"year\": ").expr(a.get(0), 0).append(", \"month\": ").expr(a.get(1), 0)
                .append(", \"day\": ").expr(a.get(2), 0);
        if (a.size() == 6) {
            writer.append(", \"hour\": ").expr(a.get(3), 0).append(", \"minute\": ").expr(a.get(4), 0)
                    .append(", \"second\": {\"$toLong\": {\"$floor\": ").expr(a.get(5), 0)
                    .append("}}, \"millisecond\": {\"$toLong\": {\"$round\": [{\"$multiply\": [{\"$mod\": [")
                    .expr(a.get(5), 0).append(", 1]}, 1000]}, 0]}}");
        } else if (a.size() != 3) {
            throw new IllegalStateException("a date from " + a.size() + " parts");
        }
        return writer.append("}}");
    }

    /** A day's or a month's name, from the calendar operator's 1-based number. */
    private static SqlWriter calendarName(SqlWriter writer, List<SqlExpr> a, List<String> names, String op) {
        writer.append("{\"$arrayElemAt\": [[");
        for (int i = 0; i < names.size(); i++) {
            writer.append(i > 0 ? ", " : "").append(MongoJson.string(names.get(i)));
        }
        return writer.append("], {\"$subtract\": [{\"").append(op).append("\": ").expr(a.get(0), 0)
                .append("}, 1]}]}");
    }

    /** {@code $filter} over the list, the lambda's parameter its variable. */
    private SqlWriter listFilter(SqlWriter writer, List<SqlExpr> a) {
        SqlExpr.Lambda l = lambdaOf(a.get(1));
        String v = MongoJson.variable(l.params().get(0));
        writer.append("{\"$filter\": {\"input\": ").expr(a.get(0), 0).append(", \"as\": ").append(MongoJson.string(v))
                .append(", \"cond\": ");
        withLambda(writer, l, List.of(v), x -> x.expr(l.body(), 0));
        return writer.append("}}");
    }

    /** {@code $map} over the list. */
    private SqlWriter listMap(SqlWriter writer, List<SqlExpr> a) {
        SqlExpr.Lambda l = lambdaOf(a.get(1));
        String v = MongoJson.variable(l.params().get(0));
        writer.append("{\"$map\": {\"input\": ").expr(a.get(0), 0).append(", \"as\": ").append(MongoJson.string(v))
                .append(", \"in\": ");
        withLambda(writer, l, List.of(v), x -> x.expr(l.body(), 0));
        return writer.append("}}");
    }

    private static SqlWriter listPosition(SqlWriter writer, List<SqlExpr> a) {
        return writer.append("{\"$let\": {\"vars\": {\"i\": {\"$indexOfArray\": [").expr(a.get(0), 0).append(", ")
                .expr(a.get(1), 0).append("]}}, \"in\": {\"$cond\": [{\"$lt\": [\"$$i\", 0]}, null, {\"$add\":"
                        + " [\"$$i\", 1]}]}}}");
    }

    /** {@code exists}: any element true, false over the empty (or null) list. */
    @Override
    protected SqlWriter listExists(SqlWriter writer, List<SqlExpr> a) {
        return quantified(writer, a, "$anyElementTrue");
    }

    /** {@code forAll}: all elements true, true over the empty (or null) list. */
    @Override
    protected SqlWriter listForAll(SqlWriter writer, List<SqlExpr> a) {
        return quantified(writer, a, "$allElementsTrue");
    }

    private SqlWriter quantified(SqlWriter writer, List<SqlExpr> a, String op) {
        SqlExpr.Lambda l = lambdaOf(a.get(1));
        String v = MongoJson.variable(l.params().get(0));
        writer.append("{\"").append(op).append("\": [{\"$map\": {\"input\": {\"$ifNull\": [").expr(a.get(0), 0)
                .append(", []]}, \"as\": ").append(MongoJson.string(v)).append(", \"in\": ");
        withLambda(writer, l, List.of(v), x -> x.expr(l.body(), 0));
        return writer.append("}}]}");
    }

    @Override
    protected SqlWriter allDistinct(SqlWriter writer, List<SqlExpr> a) {
        return MongoJson.guarded(writer, a, MongoJson.text("{\"$eq\": [{\"$size\": " + G0 + "}, {\"$size\":"
                + " {\"$setUnion\": [" + G0 + "]}}]}"));
    }

    /** The list functions beyond the lambda family, each over BSON's array operators. */
    @Override
    protected SqlWriter listCall(SqlWriter writer, SqlFn fn, List<SqlExpr> a) {
        return switch (fn) {
            case LIST_ZIP -> writer.append("{\"$zip\": {\"inputs\": [").list(a).append("]}}");
            case LIST_DISTINCT -> writer.append("{\"$setUnion\": [").expr(a.get(0), 0).append("]}");
            case LIST_APPEND -> writer.append("{\"$concatArrays\": [").expr(a.get(0), 0).append(", [")
                    .expr(a.get(1), 0).append("]]}");
            case LIST_SUM -> MongoJson.op1(writer, "$sum", a.get(0));
            case LIST_MIN -> MongoJson.op1(writer, "$min", a.get(0));
            case LIST_MAX -> MongoJson.op1(writer, "$max", a.get(0));
            case LIST_AVG -> MongoJson.op1(writer, "$avg", a.get(0));
            case LIST_MEDIAN -> arrayReduction(writer, SqlAgg.Fn.MEDIAN, x -> nonNull(x, y -> y.expr(a.get(0), 0)),
                    List.of());
            case LIST_MODE -> throw new DialectCapability("a list's mode reached MongoDb: not shaped yet");
            case LIST_SORT -> writer.append("{\"$sortArray\": {\"input\": ").expr(a.get(0), 0)
                    .append(", \"sortBy\": 1}}");
            case LIST_SORT_DESC -> writer.append("{\"$sortArray\": {\"input\": ").expr(a.get(0), 0)
                    .append(", \"sortBy\": -1}}");
            case LIST_TAIL -> MongoJson.guarded(writer, List.of(a.get(0)), MongoJson.text("{\"$cond\": [{\"$gt\":"
                    + " [{\"$size\": " + G0 + "}, 1]}, {\"$slice\": [" + G0 + ", 1, {\"$subtract\": [{\"$size\": "
                    + G0 + "}, 1]}]}, []]}"));
            case LIST_INIT -> MongoJson.guarded(writer, List.of(a.get(0)), MongoJson.text("{\"$cond\": [{\"$gt\":"
                    + " [{\"$size\": " + G0 + "}, 1]}, {\"$slice\": [" + G0 + ", 0, {\"$subtract\": [{\"$size\": "
                    + G0 + "}, 1]}]}, []]}"));
            case RANGE_FN -> MongoJson.op(writer, "$range", a);
            case LIST_PRODUCT -> writer.append("{\"$reduce\": {\"input\": ").expr(a.get(0), 0)
                    .append(", \"initialValue\": 1, \"in\": {\"$multiply\": [" + VALUE + ", " + THIS + "]}}}");
            case LIST_REDUCE -> listReduce(writer, a);
            case LIST_SLICE -> throw new DialectCapability("a list slice reached MongoDb: its bounds are not mapped"
                    + " yet");
            case REPEAT_VALUE -> writer.append("{\"$map\": {\"input\": {\"$range\": [0, ").expr(a.get(1), 0)
                    .append("]}, \"in\": ").expr(a.get(0), 0).append("}}");
            case LIST_BOOL_AND -> writer.append("{\"$allElementsTrue\": [").expr(a.get(0), 0).append("]}");
            case LIST_BOOL_OR -> writer.append("{\"$anyElementTrue\": [").expr(a.get(0), 0).append("]}");
            case LIST_REVERSE -> MongoJson.op1(writer, "$reverseArray", a.get(0));
            case TYPEOF -> MongoJson.op1(writer, "$type", a.get(0));
            default -> throw new IllegalStateException("not a list call: " + fn);
        };
    }

    /** {@code (list, (accumulator, element) -> ...[, initial])}: {@code $reduce}; without an initial value the
     *  first element is it and the rest are reduced. */
    private SqlWriter listReduce(SqlWriter writer, List<SqlExpr> a) {
        SqlExpr.Lambda l = lambdaOf(a.get(1));
        if (l.params().size() != 2) {
            throw new IllegalStateException("a reduce's lambda takes (accumulator, element): " + l.params());
        }
        if (a.size() > 2) {
            writer.append("{\"$reduce\": {\"input\": ").expr(a.get(0), 0).append(", \"initialValue\": ")
                    .expr(a.get(2), 0).append(", \"in\": ");
            withLambda(writer, l, List.of("value", "this"), x -> x.expr(l.body(), 0));
            return writer.append("}}");
        }
        writer.append("{\"$let\": {\"vars\": {\"l\": ").expr(a.get(0), 0).append("}, \"in\": {\"$cond\": [{\"$lte\":"
                + " [{\"$size\": \"$$l\"}, 1]}, {\"$arrayElemAt\": [\"$$l\", 0]}, {\"$reduce\": {\"input\":"
                + " {\"$slice\": [\"$$l\", 1, {\"$subtract\": [{\"$size\": \"$$l\"}, 1]}]}, \"initialValue\":"
                + " {\"$arrayElemAt\": [\"$$l\", 0]}, \"in\": ");
        withLambda(writer, l, List.of("value", "this"), x -> x.expr(l.body(), 0));
        return writer.append("}}]}}}");
    }

    private static SqlExpr.Lambda lambdaOf(SqlExpr e) {
        if (e instanceof SqlExpr.Lambda l) {
            return l;
        }
        throw new IllegalStateException("a list operator's second argument is a lambda, not " + e);
    }

    /** {@code body} written with the lambda's parameters bound to {@code variables}, in order. */
    private void withLambda(SqlWriter writer, SqlExpr.Lambda l, List<String> variables, SqlWriter.Piece body) {
        if (l.params().size() != variables.size()) {
            throw new IllegalStateException("lambda " + l.params() + " bound to " + variables);
        }
        for (int i = 0; i < variables.size(); i++) {
            lambdaVariables.push(Map.entry(l.params().get(i), variables.get(i)));
        }
        try {
            writer.piece(body);
        } finally {
            for (int i = 0; i < variables.size(); i++) {
                lambdaVariables.pop();
            }
        }
    }

    @Override
    protected SqlWriter unnestProjection(SqlWriter writer, List<SqlExpr> a) {
        throw new DialectCapability("an unnest reached MongoDb in expression position: an $unwind stage is not"
                + " placed yet");
    }

    /** {@code struct_insert(struct, name, value)}: the document with the field merged in. */
    @Override
    protected SqlWriter structInsert(SqlWriter writer, List<SqlExpr> a) {
        if (!(a.get(1) instanceof SqlExpr.StringLit name)) {
            throw new DialectCapability("a struct insert with a computed field name reached MongoDb");
        }
        return writer.append("{\"$mergeObjects\": [").expr(a.get(0), 0).append(", {")
                .append(MongoJson.key(name.value())).append(": ").expr(a.get(2), 0).append("}]}");
    }

    /** The values under a key: a list of one, or none (the map-extract contract). */
    private static SqlWriter mapExtract(SqlWriter writer, List<SqlExpr> a) {
        return writer.append("{\"$map\": {\"input\": {\"$filter\": {\"input\": {\"$objectToArray\": ")
                .expr(a.get(0), 0).append("}, \"cond\": {\"$eq\": [\"$$this.k\", ").expr(a.get(1), 0)
                .append("]}}}, \"in\": \"$$this.v\"}}");
    }

    private static SqlWriter mapSide(SqlWriter writer, List<SqlExpr> a, String side) {
        return writer.append("{\"$map\": {\"input\": {\"$objectToArray\": ").expr(a.get(0), 0)
                .append("}, \"in\": \"$$this.").append(side).append("\"}}");
    }

    /** A BSON value is the variant. */
    @Override
    protected SqlWriter variantConstruct(SqlWriter writer, List<SqlExpr> a) {
        return writer.expr(a.get(0), 0);
    }

    /** An array value's elements are the array itself. */
    @Override
    protected SqlWriter variantElements(SqlWriter writer, List<SqlExpr> a) {
        return writer.expr(a.get(0), 0);
    }

    /** {@code (value, key)}: a field by its literal name, an element by its 0-based index, or a field by a computed
     *  name through the document's entries. */
    @Override
    protected SqlWriter variantGet(SqlWriter writer, List<SqlExpr> a) {
        if (a.get(1) instanceof SqlExpr.StringLit key) {
            return writer.append("{\"$getField\": {\"field\": ").append(MongoJson.key(key.value()))
                    .append(", \"input\": ").expr(a.get(0), 0).append("}}");
        }
        if (a.get(1) instanceof SqlExpr.IntLit) {
            return writer.append("{\"$arrayElemAt\": [").expr(a.get(0), 0).append(", ").expr(a.get(1), 0).append("]}");
        }
        return writer.append("{\"$arrayElemAt\": [").piece(x -> mapExtract(x, a)).append(", 0]}");
    }

    /** The JSON kind of a value, by the names {@link #jsonKindName} answers. */
    private static SqlWriter jsonType(SqlWriter writer, List<SqlExpr> a) {
        return writer.append("{\"$let\": {\"vars\": {\"t\": {\"$type\": ").expr(a.get(0), 0).append("}}, \"in\":"
                + " {\"$switch\": {\"branches\": [{\"case\": {\"$in\": [\"$$t\", [\"int\", \"long\"]]}, \"then\":"
                + " \"INTEGER\"}, {\"case\": {\"$in\": [\"$$t\", [\"double\", \"decimal\"]]}, \"then\": \"FLOAT\"},"
                + " {\"case\": {\"$eq\": [\"$$t\", \"bool\"]}, \"then\": \"BOOLEAN\"}, {\"case\": {\"$eq\": [\"$$t\","
                + " \"string\"]}, \"then\": \"STRING\"}, {\"case\": {\"$eq\": [\"$$t\", \"object\"]}, \"then\":"
                + " \"OBJECT\"}, {\"case\": {\"$eq\": [\"$$t\", \"array\"]}, \"then\": \"ARRAY\"}], \"default\":"
                + " \"NULL\"}}}}");
    }

    /** A merge patch: the patch's fields over the object's, a null-valued field dropped. */
    private static SqlWriter mergePatch(SqlWriter writer, List<SqlExpr> a) {
        return writer.append("{\"$arrayToObject\": {\"$filter\": {\"input\": {\"$objectToArray\": {\"$mergeObjects\":"
                + " [").expr(a.get(0), 0).append(", ").expr(a.get(1), 0).append("]}}, \"cond\": {\"$ne\":"
                + " [\"$$this.v\", null]}}}}");
    }

    /**
     * A raise: a conversion that cannot succeed, whose error carries its input -- the message between the platform's
     * U+001F sentinels (the executor's envelope, B7), an optional source position ahead of it behind U+001E -- so
     * the connector extracts the platform's own text from the server's error.
     */
    private static SqlWriter raise(SqlWriter writer, List<SqlExpr> a) {
        writer.append("{\"$convert\": {\"input\": {\"$concat\": [").append(MongoJson.string(RAISE_SENTINEL))
                .append(", ");
        if (a.size() > 1) {
            writer.expr(a.get(1), 0).append(", ").append(MongoJson.string(POSITION_DIVIDER)).append(", ");
        }
        return writer.append("{\"$toString\": ").expr(a.get(0), 0).append("}, ")
                .append(MongoJson.string(RAISE_SENTINEL)).append(", ").append(UNFOLDED)
                .append("]}, \"to\": \"int\"}}");
    }

    /** An empty text that depends on the row ({@code $$ROOT}'s type, cut to nothing): concatenated into a raise so
     *  the server's optimizer cannot fold the conversion of a constant message and raise before any row is read
     *  (measured 8.0: a constant conversion in a branch not taken fails the pipeline's optimization). */
    private static final String UNFOLDED = "{\"$substrCP\": [{\"$type\": \"$$ROOT\"}, 0, 0]}";

    // ==================================================================
    // Aggregates: the group stage's accumulators and their reads
    // ==================================================================

    /** The native accumulator for an aggregate, one argument, unordered and not distinct; every other form is
     *  pushed and reduced at the read ({@link #accumulator}). The window-only functions and the lowering's markers
     *  never reach a group stage. */
    @Override
    protected SqlWriter reducer(SqlWriter writer, SqlAgg.Reducer r) {
        return switch (r.fn()) {
            case SUM -> accumulate(writer, "$sum", r);
            case COUNT -> countAccumulator(writer, r);
            case AVG -> accumulate(writer, "$avg", r);
            case MIN -> accumulate(writer, "$min", r);
            case MAX -> accumulate(writer, "$max", r);
            case ANY_VALUE -> accumulate(writer, "$first", r);
            case STDDEV_SAMP, STDDEV -> accumulate(writer, "$stdDevSamp", r);
            case STDDEV_POP -> accumulate(writer, "$stdDevPop", r);
            case LIST -> accumulate(writer, "$push", r);
            case BOOL_AND -> accumulate(writer, "$min", r);
            case BOOL_OR -> accumulate(writer, "$max", r);
            case VAR_SAMP, VAR_POP, VARIANCE, MEDIAN, STRING_AGG, QUANTILE_CONT, QUANTILE_DISC -> pushed(writer, r);
            case MODE -> throw new DialectCapability("mode reached MongoDb: not shaped yet");
            case CORR, COVAR_SAMP, COVAR_POP -> throw new DialectCapability(r.fn() + " reached MongoDb: a two-column"
                    + " statistic is not shaped yet");
            case ARG_MAX, ARG_MIN -> throw new DialectCapability(r.fn() + " reached MongoDb: not shaped yet");
            case WAVG, HASH_LIST, IS_DISTINCT_MARK, UNIQUE_VALUE_ONLY -> throw loweringMarker(r);
            case ROW_NUMBER, RANK, DENSE_RANK, PERCENT_RANK, CUME_DIST, NTILE, LAG, LEAD, FIRST_VALUE, LAST_VALUE,
                 NTH_VALUE -> throw windowFunction(r);
        };
    }

    /** {@code {"$op": <arg>}}. */
    private static SqlWriter accumulate(SqlWriter writer, String op, SqlAgg.Reducer r) {
        if (r.args().isEmpty()) {
            throw new IllegalStateException(r.fn() + " accumulates no argument");
        }
        return writer.append("{\"").append(op).append("\": ").expr(r.args().get(0), 0).append("}");
    }

    /** {@code count(*)} counts rows; {@code count(x)} the non-null values. */
    private static SqlWriter countAccumulator(SqlWriter writer, SqlAgg.Reducer r) {
        if (r.args().isEmpty()) {
            return writer.append("{\"$sum\": 1}");
        }
        return writer.append("{\"$sum\": {\"$cond\": [{\"$eq\": [").expr(r.args().get(0), 0)
                .append(", null]}, 0, 1]}}");
    }

    /** The group's values pushed, each with its order keys when the aggregate is ordered. */
    private static SqlWriter pushed(SqlWriter writer, SqlAgg.Reducer r) {
        if (r.args().isEmpty()) {
            throw new IllegalStateException(r.fn() + " accumulates no argument");
        }
        writer.append("{\"$push\": ");
        if (r.orderBy().isEmpty()) {
            writer.expr(r.args().get(0), 0);
        } else {
            writer.append("{\"v\": ").expr(r.args().get(0), 0);
            for (int i = 0; i < r.orderBy().size(); i++) {
                writer.append(", \"k").append(i).append("\": ").expr(r.orderBy().get(i).expr(), 0);
            }
            writer.append("}");
        }
        return writer.append("}");
    }

    /** Whether the aggregate is pushed and reduced at the read, rather than accumulated natively. */
    private static boolean pushRoute(SqlAgg.Reducer r) {
        return r.distinct() || !r.orderBy().isEmpty() || PUSHED.contains(r.fn());
    }

    /**
     * An aggregate's accumulator, written as the group field's value, and the read of that field the projection
     * writes in the marker's place: the field itself for a native accumulator; for a pushed aggregate, the pushed
     * values sorted by their keys, their nulls dropped, made distinct, and reduced.
     */
    SqlWriter.Piece accumulator(SqlWriter w, SqlExpr node, String field) {
        String path = MongoJson.string("$" + field);
        switch (node) {
            case SqlAgg.Reducer r -> {
                if (!pushRoute(r)) {
                    reducer(w, r);
                    return MongoJson.text(path);
                }
                pushed(w, r);
                List<SqlExpr> extras = r.args().subList(1, r.args().size());
                SqlWriter.Piece values = pushedValues(path, r.orderBy());
                SqlWriter.Piece set = r.distinct() ? x -> distinctInOrder(x, y -> nonNull(y, values))
                        : x -> nonNull(x, values);
                return x -> arrayReduction(x, r.fn(), set, extras);
            }
            case SqlExpr.OrderedListAgg ola -> {
                w.append("{\"$push\": {\"v\": ").expr(ola.value(), 0).append(", \"k0\": ").expr(ola.orderBy(), 0)
                        .append("}}");
                SqlWriter.Piece values = pushedValues(path, List.of(com.legend.sql.SqlSelect.SortKey.asc(
                        ola.orderBy())));
                return x -> arrayReduction(x, SqlAgg.Fn.LIST, values, List.of());
            }
            case SqlExpr.JsonArrayAgg j -> {
                w.append("{\"$push\": ");
                if (j.orderKeys().isEmpty()) {
                    w.expr(j.value(), 0);
                } else {
                    w.append("{\"v\": ").expr(j.value(), 0);
                    for (int i = 0; i < j.orderKeys().size(); i++) {
                        w.append(", \"k").append(i).append("\": ").expr(j.orderKeys().get(i).expr(), 0);
                    }
                    w.append("}");
                }
                w.append("}");
                List<com.legend.sql.SqlSelect.SortKey> keys = new ArrayList<>();
                for (SqlExpr.JsonArrayAgg.Key k : j.orderKeys()) {
                    keys.add(new com.legend.sql.SqlSelect.SortKey(k.expr(), !k.desc(), null, null));
                }
                SqlWriter.Piece values = pushedValues(path, keys);
                return x -> x.append("{\"$ifNull\": [").piece(values).append(", []]}");
            }
            default -> throw new IllegalStateException("not an aggregate: " + node);
        }
    }

    /** The value the group's field holds when no row reached it: SQL's one row over no input. */
    static String emptyInput(SqlExpr node) {
        return switch (node) {
            case SqlAgg.Reducer r -> pushRoute(r) ? "[]" : r.fn() == SqlAgg.Fn.COUNT ? "0" : "null";
            case SqlExpr.OrderedListAgg ola -> "[]";
            case SqlExpr.JsonArrayAgg j -> "[]";
            default -> throw new IllegalStateException("not an aggregate: " + node);
        };
    }

    /** The pushed values in their keys' order: the array sorted by the keys, each element's value read. */
    private static SqlWriter.Piece pushedValues(String path, List<com.legend.sql.SqlSelect.SortKey> keys) {
        if (keys.isEmpty()) {
            return MongoJson.text(path);
        }
        StringBuilder sortBy = new StringBuilder("{");
        for (int i = 0; i < keys.size(); i++) {
            sortBy.append(i > 0 ? ", " : "").append("\"k").append(i).append("\": ").append(keys.get(i).ascending()
                    ? 1 : -1);
        }
        sortBy.append("}");
        return MongoJson.text("{\"$map\": {\"input\": {\"$sortArray\": {\"input\": " + path + ", \"sortBy\": "
                + sortBy + "}}, \"in\": \"$$this.v\"}}");
    }

    /** The array without its nulls. */
    private static SqlWriter nonNull(SqlWriter writer, SqlWriter.Piece array) {
        return writer.append("{\"$filter\": {\"input\": {\"$ifNull\": [").piece(array)
                .append(", []]}, \"cond\": {\"$ne\": [" + THIS + ", null]}}}");
    }

    /** The array's distinct elements, the first occurrence of each kept in place. */
    private static SqlWriter distinctInOrder(SqlWriter writer, SqlWriter.Piece array) {
        return writer.append("{\"$reduce\": {\"input\": ").piece(array).append(", \"initialValue\": [], \"in\":"
                + " {\"$cond\": [{\"$in\": [" + THIS + ", " + VALUE + "]}, " + VALUE + ", {\"$concatArrays\": ["
                + VALUE + ", [" + THIS + "]]}]}}}");
    }

    /** An aggregate over an array of non-null values: its count, or SQL's null over no value, else its
     *  reduction. */
    private static SqlWriter arrayReduction(SqlWriter writer, SqlAgg.Fn fn, SqlWriter.Piece array,
            List<SqlExpr> extras) {
        writer.append("{\"$let\": {\"vars\": {\"a\": ").piece(array).append("}, \"in\": ");
        if (fn == SqlAgg.Fn.COUNT) {
            return writer.append("{\"$size\": \"$$a\"}}}");
        }
        writer.append("{\"$cond\": [{\"$eq\": [{\"$size\": \"$$a\"}, 0]}, null, ");
        reduceArray(writer, fn, extras);
        return writer.append("]}}}");
    }

    /** The reduction of the bound array {@code $$a}, by aggregate. */
    private static SqlWriter reduceArray(SqlWriter writer, SqlAgg.Fn fn, List<SqlExpr> extras) {
        String sorted = "{\"$sortArray\": {\"input\": \"$$a\", \"sortBy\": 1}}";
        return switch (fn) {
            case SUM -> writer.append("{\"$sum\": \"$$a\"}");
            case AVG -> writer.append("{\"$avg\": \"$$a\"}");
            case MIN -> writer.append("{\"$min\": \"$$a\"}");
            case MAX -> writer.append("{\"$max\": \"$$a\"}");
            case ANY_VALUE -> writer.append("{\"$arrayElemAt\": [\"$$a\", 0]}");
            case STDDEV_SAMP, STDDEV -> writer.append("{\"$stdDevSamp\": \"$$a\"}");
            case STDDEV_POP -> writer.append("{\"$stdDevPop\": \"$$a\"}");
            case VAR_SAMP, VARIANCE -> writer.append("{\"$pow\": [{\"$stdDevSamp\": \"$$a\"}, 2]}");
            case VAR_POP -> writer.append("{\"$pow\": [{\"$stdDevPop\": \"$$a\"}, 2]}");
            case LIST -> writer.append("\"$$a\"");
            case BOOL_AND -> writer.append("{\"$allElementsTrue\": [\"$$a\"]}");
            case BOOL_OR -> writer.append("{\"$anyElementTrue\": [\"$$a\"]}");
            case MEDIAN -> writer.append("{\"$let\": {\"vars\": {\"s\": " + sorted + ", \"n\": {\"$size\": \"$$a\"}},"
                    + " \"in\": {\"$cond\": [{\"$eq\": [{\"$mod\": [\"$$n\", 2]}, 1]}, {\"$arrayElemAt\": [\"$$s\","
                    + " {\"$toInt\": {\"$divide\": [{\"$subtract\": [\"$$n\", 1]}, 2]}}]}, {\"$avg\":"
                    + " [{\"$arrayElemAt\": [\"$$s\", {\"$toInt\": {\"$subtract\": [{\"$divide\": [\"$$n\", 2]},"
                    + " 1]}}]}, {\"$arrayElemAt\": [\"$$s\", {\"$toInt\": {\"$divide\": [\"$$n\", 2]}}]}]}]}}}");
            case STRING_AGG -> writer.append("{\"$reduce\": {\"input\": \"$$a\", \"initialValue\": null, \"in\":"
                    + " {\"$cond\": [{\"$eq\": [" + VALUE + ", null]}, {\"$toString\": " + THIS + "},"
                    + " {\"$concat\": [" + VALUE + ", ").piece(x -> separator(x, extras))
                    .append(", {\"$toString\": " + THIS + "}]}]}}}");
            case QUANTILE_CONT -> writer.append("{\"$let\": {\"vars\": {\"s\": " + sorted + ", \"p\": {\"$multiply\":"
                    + " [").piece(x -> quantile(x, extras)).append(", {\"$subtract\": [{\"$size\": \"$$a\"}, 1]}]}},"
                    + " \"in\": {\"$let\": {\"vars\": {\"lo\": {\"$arrayElemAt\": [\"$$s\", {\"$toInt\": {\"$floor\":"
                    + " \"$$p\"}}]}, \"hi\": {\"$arrayElemAt\": [\"$$s\", {\"$toInt\": {\"$ceil\": \"$$p\"}}]}},"
                    + " \"in\": {\"$add\": [\"$$lo\", {\"$multiply\": [{\"$subtract\": [\"$$p\", {\"$floor\":"
                    + " \"$$p\"}]}, {\"$subtract\": [\"$$hi\", \"$$lo\"]}]}]}}}}}");
            case QUANTILE_DISC -> writer.append("{\"$arrayElemAt\": [" + sorted + ", {\"$max\": [0, {\"$subtract\":"
                    + " [{\"$toInt\": {\"$ceil\": {\"$multiply\": [").piece(x -> quantile(x, extras))
                    .append(", {\"$size\": \"$$a\"}]}}}, 1]}]}]}");
            case COUNT -> writer.append("{\"$size\": \"$$a\"}");
            case MODE -> throw new DialectCapability("mode reached MongoDb: not shaped yet");
            case CORR, COVAR_SAMP, COVAR_POP -> throw new DialectCapability(fn + " reached MongoDb: a two-column"
                    + " statistic is not shaped yet");
            case ARG_MAX, ARG_MIN -> throw new DialectCapability(fn + " reached MongoDb: not shaped yet");
            case WAVG, HASH_LIST, IS_DISTINCT_MARK, UNIQUE_VALUE_ONLY -> throw new IllegalStateException(
                    "lowering marker " + fn + " reached the renderer");
            case ROW_NUMBER, RANK, DENSE_RANK, PERCENT_RANK, CUME_DIST, NTILE, LAG, LEAD, FIRST_VALUE, LAST_VALUE,
                 NTH_VALUE -> throw new IllegalStateException("window-only function " + fn + " in a reduction");
        };
    }

    private static SqlWriter separator(SqlWriter writer, List<SqlExpr> extras) {
        return extras.isEmpty() ? writer.append("\"\"") : writer.expr(extras.get(0), 0);
    }

    private static SqlWriter quantile(SqlWriter writer, List<SqlExpr> extras) {
        if (extras.isEmpty()) {
            throw new IllegalStateException("a quantile without its fraction");
        }
        return writer.expr(extras.get(0), 0);
    }

    // ==================================================================
    // Names and types
    // ==================================================================

    /** The BSON type name a cast converts to, a refusal where MongoDB has no such type. */
    @Override
    protected String scalarTypeName(SqlType.Scalar s) {
        return switch (s) {
            case BOOLEAN -> "bool";
            case INTEGER, BIGINT -> "long";
            case DOUBLE -> "double";
            case VARCHAR, LITERAL, TEMPORAL_TEXT -> "string";
            case DATE, TIMESTAMP -> "date";
            case HUGEINT -> throw new DialectCapability("a cast to a 128-bit integer reached MongoDb, which has"
                    + " no such type");
            case TIMESTAMPTZ -> throw new DialectCapability("a cast to a zoned timestamp reached MongoDb, whose"
                    + " dates are UTC instants");
            case TIME -> throw new DialectCapability("a cast to a time of day reached MongoDb, which has no time"
                    + " type");
            case JSON -> throw new IllegalStateException("a cast to JSON is the value itself on MongoDb");
            case DECIMAL_TEXT -> throw new DialectCapability("a cast to a decimal's text reached MongoDb");
        };
    }

    @Override
    protected String structTypeName(SqlType.Struct st) {
        throw new DialectCapability("a cast to a struct type reached MongoDb, whose documents are untyped");
    }

    /** {@code $dateToString}'s codes: the date and time parts to the millisecond; a name or a 12-hour part has none. */
    @Override
    protected String formatText(SqlExpr.FormatLit fl) {
        StringBuilder out = new StringBuilder();
        for (com.legend.sql.DateFmt p : fl.parts()) {
            out.append(switch (p) {
                case com.legend.sql.DateFmt.Text t -> t.s().replace("%", "%%");
                case com.legend.sql.DateFmt.Part part -> switch (part) {
                    case YEAR4 -> "%Y";
                    case MONTH2 -> "%m";
                    case DAY2 -> "%d";
                    case HOUR2 -> "%H";
                    case MIN2 -> "%M";
                    case SEC2 -> "%S";
                    case SUBSEC_MICRO, SUBSEC_NANO, SUBSEC_MIN -> throw new DialectCapability("a date format with a"
                            + " " + part + " part reached MongoDb, whose date holds milliseconds (%L)");
                    case MONTH_ABBREV, MONTH_NAME, WEEKDAY_NAME, HOUR12, HOUR12_NOPAD, AMPM -> throw
                            new DialectCapability("a date format with a " + part + " part reached MongoDb, whose"
                                    + " $dateToString has no code for it");
                };
            });
        }
        return out.toString();
    }

    /** {@inheritDoc} The kind names {@link #call}'s JSON_TYPE answers. */
    @Override
    protected String jsonKindName(com.legend.sql.JsonKind kind) {
        return switch (kind) {
            case OBJECT -> "OBJECT";
            case ARRAY -> "ARRAY";
            case NULL -> "NULL";
            case STRING -> "STRING";
            case INTEGER, UNSIGNED_INTEGER -> "INTEGER";
            case FLOAT -> "FLOAT";
            case BOOLEAN -> "BOOLEAN";
        };
    }

    /** {@inheritDoc} {@code $type}'s name of a date; no other type's name is compared. */
    @Override
    protected String typeofName(SqlType.Scalar type) {
        if (type == SqlType.Scalar.DATE || type == SqlType.Scalar.TIMESTAMP) {
            return "date";
        }
        throw new DialectCapability("a TYPEOF compared with " + type + "'s name reached MongoDb");
    }

    /** A name is a JSON string: no quoting, no reserved words. */
    @Override
    protected String ident(String name) {
        return name;
    }

    @Override
    protected String delimited(String name) {
        return name;
    }

    @Override
    protected String ddlIdentifier(String name, boolean declaredQuoted) {
        return name;
    }

    /** A declared column's BSON type, for the store's own reading; a collection declares no columns to the server. */
    @Override
    protected String ddlType(com.legend.sql.SqlDdl.ColumnType t) {
        return switch (t) {
            case com.legend.sql.SqlDdl.ColumnType.Plain p -> switch (p.kind()) {
                case BIGINT, INTEGER, SMALLINT, TINYINT -> "long";
                case FLOAT, DOUBLE, REAL -> "double";
                case BIT -> "bool";
                case TIMESTAMP, DATE -> "date";
                case VARCHAR -> "string";
                case JSON, OBJECT -> "object";
                case ARRAY -> "array";
                case OTHER -> "objectId";
                case DISTINCT -> throw DdlSpelling.unspelled(p.kind());
            };
            case com.legend.sql.SqlDdl.ColumnType.Sized z -> "string";
            case com.legend.sql.SqlDdl.ColumnType.Scaled s -> "decimal";
        };
    }

    // ==================================================================
    // DDL and DML: commands
    // ==================================================================

    /** A table is a collection: the {@code create} and {@code drop} commands; a schema has no counterpart (a
     *  collection's database is the connection's), nor has a temporary collection. */
    @Override
    public String render(com.legend.sql.SqlDdl ddl) {
        return switch (ddl) {
            case com.legend.sql.SqlDdl.CreateTable ct -> {
                if (ct.temporary()) {
                    throw new DialectCapability("a temporary table reached MongoDb, which has no temporary"
                            + " collection");
                }
                yield "{\"create\": " + MongoJson.string(collection(ct.schema(), ct.table())) + "}";
            }
            case com.legend.sql.SqlDdl.DropTable dt -> "{\"drop\": "
                    + MongoJson.string(collection(dt.schema(), dt.table())) + "}";
            case com.legend.sql.SqlDdl.CreateSchema cs -> throw new DialectCapability("a schema (" + cs.schema()
                    + ") reached MongoDb: a collection's database is the connection's, and has no schema");
            case com.legend.sql.SqlDdl.DropSchema ds -> throw new DialectCapability("a schema (" + ds.schema()
                    + ") reached MongoDb: a collection's database is the connection's, and has no schema");
        };
    }

    private static String collection(@com.legend.base.Nullable String schema, String table) {
        if (schema != null && !schema.isEmpty() && !"default".equals(schema)) {
            throw new DialectCapability("a schema-qualified table (" + schema + "." + table + ") reached MongoDb: a"
                    + " collection has no schema");
        }
        if (table.indexOf('.') >= 0) {
            throw new DialectCapability("a schema-qualified table (" + table + ") reached MongoDb: a collection has"
                    + " no schema");
        }
        return table;
    }

    /** Rows in: the {@code insert} command, each row a document of values; rows out: {@code delete} of every
     *  document. A copy between collections and a temporary collection from a query are not shaped. */
    @Override
    public String render(com.legend.sql.SqlDml dml) {
        return switch (dml) {
            case com.legend.sql.SqlDml.InsertValues iv -> insert(iv);
            case com.legend.sql.SqlDml.InsertFromTable it -> throw new DialectCapability("a copy from a table"
                    + " reached MongoDb: an insert from another collection is not shaped yet");
            case com.legend.sql.SqlDml.DeleteAll da -> "{\"delete\": "
                    + MongoJson.string(collection(da.schema(), da.table())) + ", \"deletes\": [{\"q\": {},"
                    + " \"limit\": 0}]}";
            case com.legend.sql.SqlDml.CreateTempTableAs ct -> tempTableAs(ct);
        };
    }

    private String insert(com.legend.sql.SqlDml.InsertValues iv) {
        if (iv.columns().isEmpty()) {
            throw new DialectCapability("an insert naming no columns reached MongoDb: a document's fields are"
                    + " named");
        }
        SqlWriter writer = newWriter().append("{\"insert\": ").append(MongoJson.string(collection(iv.schema(),
                iv.table()))).append(", \"documents\": [");
        for (int r = 0; r < iv.rows().size(); r++) {
            List<SqlExpr> row = iv.rows().get(r);
            if (row.size() != iv.columns().size()) {
                throw new IllegalStateException("a row of " + row.size() + " values for " + iv.columns().size()
                        + " columns");
            }
            writer.append(r > 0 ? ", {" : "{");
            for (int c = 0; c < row.size(); c++) {
                writer.append(c > 0 ? ", " : "").append(MongoJson.key(iv.columns().get(c))).append(": ");
                value(writer, row.get(c));
            }
            writer.append("}");
        }
        return writer.append("]}").text();
    }

    /** A row's cell as a VALUE in Extended JSON (an inserted document is data, not an expression: a string is a JSON
     *  string here, never {@code $literal}): a literal, or null; anything else is refused by name. */
    private static SqlWriter value(SqlWriter writer, SqlExpr cell) {
        return switch (cell) {
            case SqlExpr.StringLit s -> writer.append(MongoJson.string(s.value()));
            case SqlExpr.IntLit i -> writer.append("{\"$numberLong\": \"").append(i.value()).append("\"}");
            case SqlExpr.FloatLit f -> writer.append("{\"$numberDouble\": \"").append(plainFloat(f.value()))
                    .append("\"}");
            case SqlExpr.DecimalLit d -> writer.append("{\"$numberDecimal\": \"").append(d.value().toPlainString())
                    .append("\"}");
            case SqlExpr.BoolLit b -> writer.append(b.value() ? "true" : "false");
            case SqlExpr.NullLit n -> writer.append("null");
            case SqlExpr.DateLit d -> writer.append(MongoJson.dateLiteral(d.iso()));
            case SqlExpr.TimestampLit t -> writer.append(MongoJson.dateLiteral(t.iso()));
            case SqlExpr.Cast c -> value(writer, c.value());
            default -> throw new DialectCapability("a row's cell is " + cell.getClass().getSimpleName()
                    + ": a MongoDb insert holds values, not expressions");
        };
    }
}
