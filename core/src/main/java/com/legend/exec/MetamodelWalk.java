// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend.exec;

import com.legend.compiler.element.ModelContext;
import com.legend.error.NotImplementedException;
import com.legend.model.DatabaseDefinition;
import com.legend.model.RelationalDataType;
import com.legend.model.RelationalOperation;

import java.util.ArrayList;
import java.util.List;

/**
 * HOST-side evaluation of store-METAMODEL navigations — the engine's
 * {@code db.schemas->view(...)->columnMappings...inferRelationalType()}
 * test surface (typeInference family). The handles wrap legend-lite's
 * OWN compiled model ({@link DatabaseDefinition}); {@code
 * inferRelationalType} resolves the column expression's declared SQL
 * type; {@code dataTypeToSqlText} spells it in the engine's text form.
 * Every unrecognized shape returns null (the caller's walk falls
 * through to its own walls) — never a silent wrong answer.
 */
public final class MetamodelWalk {

    private MetamodelWalk() {
    }

    public record Db(DatabaseDefinition db) {
    }

    public record Sch(DatabaseDefinition db,
            DatabaseDefinition.SchemaDefinition schema) {
    }

    public record Vw(DatabaseDefinition db,
            DatabaseDefinition.ViewDefinition view) {
    }

    public record Vcm(DatabaseDefinition db,
            DatabaseDefinition.ViewDefinition.ViewColumnMapping vcm) {
    }

    public record Rop(DatabaseDefinition db, ModelContext ctx,
            RelationalOperation op) {
    }

    public record Dt(RelationalDataType type) {
    }

    public record Tbl(DatabaseDefinition db, String schemaName,
            DatabaseDefinition.TableDefinition t) {
    }

    public record AliasH(String name, Object relationalElement) {
    }

    public record ColH(DatabaseDefinition.ColumnDefinition c) {
    }

    // SQL-protocol NODE values (the toPostgresModel bridge) — java
    // records give the STRUCTURAL equality/print the assertEquals
    // comparison rides
    public record QnH(List<String> parts) {
    }

    public record QnrH(QnH name) {
    }

    public record CnH(String name) {
    }

    public record TacH(String aliasName, Object column) {
    }

    /** A DynaFunction whose arguments MIX relational ops with walked
     * metamodel handles (getColumn chains) — converted per-arg. */
    public record DynH(String name, List<Object> args) {
    }

    /** GENERIC SQL-protocol node value: kind + ctor-provided props.
     * SORTED map (order-insensitive print+equality) and EMPTY/null
     * props dropped — the engine ctor-vs-converter default asymmetry
     * (arguments=[] vs absent) compares equal. */
    public record NodeH(String kind,
            java.util.TreeMap<String, Object> props) {
    }

    static NodeH node(String kind, Object... kv) {
        java.util.TreeMap<String, Object> m = new java.util.TreeMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return nodeOf(kind, m);
    }

    /** The ONE NodeH normalization funnel — construction (genericNode)
     * and conversion emit through the SAME rules so both comparison
     * sides agree: null/empty props drop; a one-element collection IS
     * its element (Pure multiplicity semantics); class-declared ctor
     * DEFAULTS materialize (FunctionCall.distinct = false). */
    public static NodeH nodeOf(String kind,
            java.util.TreeMap<String, Object> raw) {
        java.util.TreeMap<String, Object> m = new java.util.TreeMap<>();
        for (var e : raw.entrySet()) {
            Object v = e.getValue();
            if (v instanceof List<?> lv && lv.size() == 1) {
                v = lv.get(0);
            }
            if (v == null || (v instanceof List<?> lv2 && lv2.isEmpty())) {
                continue;
            }
            m.put(e.getKey(), v);
        }
        if ("FunctionCall".equals(kind)) {
            m.putIfAbsent("distinct", Boolean.FALSE);
        }
        return new NodeH(kind, m);
    }

    /** {@code schema('name')} navigation over a Database handle. */
    public static Object schema(Object recv, String name) {
        if (recv instanceof Db d) {
            for (var s : d.db().schemas()) {
                if (s.name().equals(name)) {
                    return new Sch(d.db(), s);
                }
            }
            if ("default".equals(name)) {
                return new Sch(d.db(), defaultSchema(d.db()));
            }
        }
        return null;
    }

    /** The synthetic 'default' schema: top-level tables/views minus
     * those the parser flattened from declared schemas. */
    private static DatabaseDefinition.SchemaDefinition defaultSchema(
            DatabaseDefinition db) {
        java.util.Set<String> dt = new java.util.HashSet<>();
        java.util.Set<String> dv = new java.util.HashSet<>();
        for (var sd : db.schemas()) {
            for (var t : sd.tables()) {
                dt.add(t.name());
            }
            for (var v : sd.views()) {
                dv.add(v.name());
            }
        }
        return new DatabaseDefinition.SchemaDefinition("default",
                db.tables().stream().filter(t -> !dt.contains(t.name()))
                        .toList(),
                db.views().stream().filter(v -> !dv.contains(v.name()))
                        .toList());
    }

    /** {@code table('name')} navigation over a Schema handle. */
    public static Object table(Object recv, String name) {
        if (recv instanceof Sch s) {
            for (var t : s.schema().tables()) {
                if (t.name().equals(name)) {
                    return new Tbl(s.db(), s.schema().name(), t);
                }
            }
        }
        return null;
    }

    /** {@code convertElement} — the toPostgresModel SIMPLE element arms
     * (toPostgresModel.pure:93-96 + convertColumn:203 +
     * convertTableAliasName:785: alias names QUOTE). */
    public static Object convertElement(Object recv) {
        Object r = recv instanceof List<?> l && l.size() == 1
                ? l.get(0) : recv;
        if (r instanceof TacH t) {
            String col = t.column() instanceof ColH ch ? ch.c().name()
                    : String.valueOf(t.column());
            return new QnrH(new QnH(List.of(
                    "\"" + t.aliasName() + "\"", col)));
        }
        if (r instanceof CnH c) {
            return new QnrH(new QnH(List.of(c.name())));
        }
        if (r instanceof ColH ch) {
            return new QnrH(new QnH(List.of(ch.c().name())));
        }
        if (r instanceof Rop rop) {
            return convertOp(rop.op());
        }
        if (r instanceof Tbl tb) {
            List<String> parts = "default".equals(tb.schemaName())
                    ? List.of(tb.t().name())
                    : List.of(tb.schemaName(), tb.t().name());
            return node("Table", "name", new QnH(parts));
        }
        if (r instanceof DynH dh) {
            List<Object> converted = new ArrayList<>();
            for (Object arg : dh.args()) {
                Object v = convertElement(arg);
                if (v == null) {
                    return null;
                }
                converted.add(v);
            }
            return dynaNode(dh.name(), converted);
        }
        if (r instanceof AliasH ah) {
            Object rel = convertElement(ah.relationalElement());
            if (rel == null) {
                return null;
            }
            // engine convertAlias dispatches on the CONVERTED node: a
            // RELATION aliases via AliasedRelation (alias QUOTES,
            // convertTableAliasName); an expression becomes a
            // SingleColumn (alias bare)
            if (isRelationNode(rel)) {
                return node("AliasedRelation", "alias",
                        "\"" + ah.name() + "\"", "relation", rel);
            }
            Object expr = rel instanceof NodeH sc
                    && "SingleColumn".equals(sc.kind())
                    ? sc.props().get("expression") : rel;
            return node("SingleColumn", "alias", ah.name(),
                    "expression", expr);
        }
        if (r instanceof NodeH nh) {
            return convertNodeKind(nh);
        }
        return null;
    }

    /** Constructed-metamodel instances carried as generic NodeH handles
     * (placeholders, window columns, tabular functions) — the
     * toPostgresModel arms at :89-101. */
    private static Object convertNodeKind(NodeH nh) {
        return switch (nh.kind()) {
            case "VarPlaceHolder" -> node("InClauseVariablePlaceholder",
                    "name", nh.props().get("name"));
            case "VarSetPlaceHolder", "VarCrossSetPlaceHolder" ->
                    node("TablePlaceholder", "name",
                            nh.props().get("varName"));
            case "WindowColumn" -> convertWindowColumn(nh);
            case "TabularFunction" -> {
                Object sch = nh.props().get("schema");
                Object nm = nh.props().get("name");
                if (!(sch instanceof Sch s) || nm == null) {
                    yield null;
                }
                yield node("TableFunction", "functionCall",
                        node("FunctionCall", "name", new QnH(List.of(
                                s.schema().name(), String.valueOf(nm)))));
            }
            default -> null;
        };
    }

    /** Engine convertWindowColumn (toPostgresModel.pure:817-831): the
     * dyna func converts to a FunctionCall which gains a window of the
     * converted partitions + sort infos; ASC sorts NULLS LAST, DESC
     * NULLS FIRST. */
    private static Object convertWindowColumn(NodeH nh) {
        Object f = convertElement(nh.props().get("func"));
        if (!(f instanceof NodeH fn) || !"FunctionCall".equals(fn.kind())
                || !(nh.props().get("window") instanceof NodeH win)) {
            return null;
        }
        List<Object> partitions = new ArrayList<>();
        for (Object p : asList(win.props().get("partition"))) {
            Object v = convertElement(p);
            if (v == null) {
                return null;
            }
            partitions.add(v);
        }
        List<Object> orderBy = new ArrayList<>();
        for (Object s : asList(win.props().get("sortBy"))) {
            if (!(s instanceof NodeH sb)
                    || !"SortByInfo".equals(sb.kind())) {
                return null;
            }
            Object key = convertElement(sb.props().get("sortByElement"));
            if (key == null) {
                return null;
            }
            boolean asc = !"DESC".equals(sb.props().get("sortDirection"));
            orderBy.add(node("SortItem", "sortKey", key,
                    "ordering", asc ? "ASCENDING" : "DESCENDING",
                    "nullOrdering", asc ? "LAST" : "FIRST"));
        }
        java.util.TreeMap<String, Object> out =
                new java.util.TreeMap<>(fn.props());
        out.put("window", node("Window", "partitions", partitions,
                "orderBy", orderBy));
        return nodeOf("FunctionCall", out);
    }

    /** sql-metamodel Relation subtypes (the convertAlias dispatch). */
    private static boolean isRelationNode(Object v) {
        return v instanceof NodeH n && switch (n.kind()) {
            case "Table", "TableFunction", "TableSubquery",
                 "AliasedRelation", "TablePlaceholder", "Join",
                 "Union", "QuerySpecification" -> true;
            default -> false;
        };
    }

    /** A [0..1]/[*] NodeH prop as a list (singletons store unwrapped). */
    private static List<Object> asList(Object v) {
        if (v == null) {
            return List.of();
        }
        if (v instanceof List<?> l) {
            return new ArrayList<>(l);
        }
        List<Object> one = new ArrayList<>();
        one.add(v);
        return one;
    }

    /** Relational-op conversions (toPostgresModel convertLiteral +
     * convertDynaFunction dispatch): literals to SQL literal nodes,
     * and/or to LogicalBinaryExpression, null-tests to predicates,
     * comparisons to ComparisonExpression, else FunctionCall. */
    private static Object convertOp(RelationalOperation op) {
        return switch (op) {
            case RelationalOperation.Literal l -> switch (l.value()) {
                case String str -> node("StringLiteral", "value", str);
                case Integer i -> node("IntegerLiteral", "value",
                        (long) (int) i);
                case Long lg -> node("IntegerLiteral", "value", lg);
                case Double d -> node("DoubleLiteral", "value", d);
                case Boolean bo -> node("BooleanLiteral", "value", bo);
                case com.legend.values.PureDateLiteral pd ->
                        node(pd.toEngineString().indexOf('T') >= 0
                                ? "TimestampLiteral" : "DateLiteral",
                                "value", pd);
                default -> null;
            };
            case RelationalOperation.ArrayLiteral al -> {
                List<Object> vs = new ArrayList<>();
                for (var e : al.elements()) {
                    Object v = convertOp(e);
                    if (v == null) {
                        yield null;
                    }
                    vs.add(v);
                }
                yield node("InListExpression", "values", vs);
            }
            case RelationalOperation.FunctionCall f -> {
                List<Object> args = new ArrayList<>();
                for (var e : f.args()) {
                    Object v = convertOp(e);
                    if (v == null) {
                        yield null;
                    }
                    args.add(v);
                }
                yield dynaNode(f.name(), args);
            }
            default -> null;
        };
    }

    public record Mm(ModelContext ctx,
            com.legend.model.LegacyMappingDefinition mapping) {
    }

    public record Cm(ModelContext ctx,
            com.legend.model.ClassMapping.Relational cm) {
    }

    public record Pm(ModelContext ctx,
            com.legend.model.PropertyMapping pm) {
    }

    /** A Mapping ELEMENT reference as a metamodel handle, or null. */
    public static Object mapping(ModelContext ctx, String fqn) {
return ctx.findLegacyMapping(fqn).map(m -> new Mm(ctx, m))
                .orElse(null);
    }

    /** {@code rootClassMappingByClass} — the class's relational set. */
    public static Object rootClassMappingByClass(Object recv,
            String classFqn) {
        if (recv instanceof Mm m) {
            for (var cm : m.mapping().classMappings()) {
                if (cm instanceof com.legend.model.ClassMapping.Relational r
                        && r.className().equals(classFqn)) {
                    return new Cm(m.ctx(), r);
                }
            }
        }
        return null;
    }

    /** {@code propertyMappingsByPropertyName} — declaration order. */
    public static Object propertyMappingsByName(Object recv, String name) {
        if (recv instanceof Cm c) {
            List<Object> out = new ArrayList<>();
            for (var pm : c.cm().propertyMappings()) {
                if (pm.propertyName().equals(name)) {
                    out.add(new Pm(c.ctx(), pm));
                }
            }
            return out;
        }
        return null;
    }

    /** A Database ELEMENT reference as a metamodel handle, or null. */
    public static Object database(ModelContext ctx, String fqn) {
        return ctx.findDatabase(fqn).map(Db::new).orElse(null);
    }

    /** Property step over a handle; null = not a metamodel property. */
    public static Object prop(Object recv, String prop) {
        if (recv instanceof Db d && prop.equals("schemas")) {
            List<Object> out = new ArrayList<>();
            for (var s : d.db().schemas()) {
                out.add(new Sch(d.db(), s));
            }
            // top-level tables/views ARE the default schema — MINUS any
            // the parser also flattened from declared schemas (top-level
            // duplication is a lookup convenience, not schema identity)
            var ds = defaultSchema(d.db());
            if (!ds.tables().isEmpty() || !ds.views().isEmpty()) {
                out.add(new Sch(d.db(), ds));
            }
            return out;
        }
        if (recv instanceof Sch s8 && prop.equals("name")) {
            return s8.schema().name();
        }
        if (recv instanceof Sch s9 && prop.equals("tables")) {
            List<Object> out = new ArrayList<>();
            for (var t : s9.schema().tables()) {
                out.add(new Tbl(s9.db(), s9.schema().name(), t));
            }
            return out;
        }
        if (recv instanceof Tbl tb9 && prop.equals("name")) {
            return tb9.t().name();
        }
        if (recv instanceof Tbl tb && prop.equals("columns")) {
            List<Object> out = new ArrayList<>();
            for (var c : tb.t().columns()) {
                out.add(new ColH(c));
            }
            return out;
        }
        if (recv instanceof ColH ch2 && prop.equals("name")) {
            return ch2.c().name();
        }
        if (recv instanceof Vw v && prop.equals("columnMappings")) {
            List<Object> out = new ArrayList<>();
            for (var cm : v.view().columnMappings()) {
                out.add(new Vcm(v.db(), cm));
            }
            return out;
        }
        if (recv instanceof Vcm c) {
            return switch (prop) {
                case "columnName" -> c.vcm().name();
                case "relationalOperationElement" ->
                        new Rop(c.db(), null, c.vcm().expression());
                default -> null;
            };
        }
        if (recv instanceof Pm p
                && prop.equals("relationalOperationElement")) {
            return switch (p.pm()) {
                case com.legend.model.PropertyMapping.Expression ex ->
                        new Rop(null, p.ctx(), ex.expression());
                case com.legend.model.PropertyMapping.Column col ->
                        new Rop(null, p.ctx(),
                                new RelationalOperation.ColumnRef(
                                        col.database(), col.table(),
                                        col.column()));
                default -> null;
            };
        }
        return null;
    }

    /** {@code schema->view('name')} navigation; null = not applicable. */
    public static Object view(Object recv, String name) {
        if (recv instanceof Sch s) {
            for (var v : s.schema().views()) {
                if (v.name().equals(name)) {
                    return new Vw(s.db(), v);
                }
            }
        }
        return null;
    }

    /** {@code inferRelationalType} over a column expression handle —
     * the DECLARED SQL type the expression carries. Single-element
     * lists unwrap (pure toOne semantics ride the walk). */
    public static Object infer(Object recv) {
        Object r = recv instanceof List<?> l && l.size() == 1
                ? l.get(0) : recv;
        if (!(r instanceof Rop rop)) {
            return null;
        }
        RelationalDataType t = inferOp(rop, rop.op());
        return t == null ? null : new Dt(t);
    }

    private static RelationalDataType inferOp(Rop env,
            RelationalOperation op) {
        return switch (op) {
            case RelationalOperation.ColumnRef c ->
                    columnType(env, c.databaseName(), c.table(), c.column());
            case RelationalOperation.FunctionCall f -> switch (
                    f.name().toLowerCase(java.util.Locale.ROOT)) {
                // aggregates carry their argument's type (engine
                // inferDynaFunctionReturnType: max/min/sum/avg family)
                case "max", "min", "distinct" ->
                        f.args().isEmpty() ? null
                                : inferOp(env, f.args().get(0));
                // sum/avg PROMOTE float-family to DOUBLE (engine
                // inferDynaFunctionReturnType aggregation rule)
                case "sum", "average", "avg" -> {
                    RelationalDataType at = f.args().isEmpty() ? null
                            : inferOp(env, f.args().get(0));
                    yield at instanceof RelationalDataType.Float_
                            || at instanceof RelationalDataType.Real
                            ? new RelationalDataType.Double_() : at;
                }
                case "count" -> new RelationalDataType.Integer_();
                // boolean dynafunctions spell BIT (engine H2)
                case "and", "or", "not", "equal", "notequal", "in",
                        "isnull", "isnotnull", "greaterthan", "lessthan",
                        "greaterthanequal", "lessthanequal", "isempty",
                        "isnotempty", "like", "startswith", "endswith",
                        "contains" -> new RelationalDataType.Bit();
                // sqlNull carries the engine's OTHER type
                case "sqlnull" -> new RelationalDataType.Other();
                // string transforms keep their input's type
                case "substring", "left", "right", "trim", "ltrim",
                        "rtrim", "toupper", "tolower", "upper", "lower" ->
                        f.args().isEmpty() ? null
                                : inferOp(env, f.args().get(0));
                case "position", "length", "charindex", "locate",
                        "indexof" -> new RelationalDataType.Integer_();
                case "sub" -> {
                    RelationalDataType acc2 = null;
                    for (var arg : f.args()) {
                        acc2 = safe(acc2, inferOp(env, arg));
                    }
                    yield acc2 == null
                            ? new RelationalDataType.Integer_() : acc2;
                }
                // string concatenation SUMS the operand sizes (engine
                // getSize over joinStrings/concat)
                // joinStrings AGGREGATES: the engine assigns the fixed
                // 4000 buffer size
                case "joinstrings" -> new RelationalDataType.Varchar(4000);
                case "concat", "group_concat" -> {
                    int size = 0;
                    for (var arg : f.args()) {
                        RelationalDataType at2 = inferOp(env, arg);
                        if (at2 instanceof RelationalDataType.Varchar v2) {
                            size += v2.size();
                        } else if (at2 instanceof RelationalDataType.Char_ c2) {
                            size += c2.size();
                        }
                    }
                    yield new RelationalDataType.Varchar(size);
                }
                // case(c1, v1, ..., else): the SAFE type over the value
                // branches (engine getSafeType lattice)
                case "case" -> {
                    RelationalDataType acc = null;
                    for (int i = 1; i < f.args().size(); i += 2) {
                        acc = safe(acc, inferOp(env, f.args().get(i)));
                    }
                    if (f.args().size() % 2 == 1) {
                        acc = safe(acc, inferOp(env,
                                f.args().get(f.args().size() - 1)));
                    }
                    yield acc;
                }
                // numeric operators widen positionally (engine math
                // compatibility: DOUBLE beats INT; DECIMAL beats both)
                case "plus", "minus", "times", "divide" -> {
                    RelationalDataType acc = null;
                    for (var arg : f.args()) {
                        acc = safe(acc, inferOp(env, arg));
                    }
                    yield acc;
                }
                default -> null;
            };
            case RelationalOperation.JoinNavigation j ->
                    j.terminal() == null ? null
                            : inferOp(env, j.terminal());
            case RelationalOperation.Group g -> inferOp(env, g.inner());
            case RelationalOperation.Literal l -> switch (l.value()) {
                case String str ->
                        new RelationalDataType.Varchar(str.length());
                case Integer ignored -> new RelationalDataType.Integer_();
                case Long ignored -> new RelationalDataType.Integer_();
                case Double ignored -> new RelationalDataType.Double_();
                default -> null;
            };
            // boolean expressions spell BIT (engine H2 boolean SQL type)
            case RelationalOperation.Comparison ignored ->
                    new RelationalDataType.Bit();
            case RelationalOperation.BooleanOp ignored ->
                    new RelationalDataType.Bit();
            case RelationalOperation.IsNull ignored ->
                    new RelationalDataType.Bit();
            case RelationalOperation.IsNotNull ignored ->
                    new RelationalDataType.Bit();
            default -> null;
        };
    }

    /** The DYNAFUNCTION-to-SQL-node dispatch (engine convertDynaFunction
     * families) over already-converted argument nodes. */
    private static Object dynaNode(String name, List<Object> args) {
        String nm = name.toLowerCase(java.util.Locale.ROOT);
        return switch (nm) {
            case "sqlnull" -> node("NullLiteral");
            case "and", "or" -> node("LogicalBinaryExpression",
                    "type", nm.toUpperCase(java.util.Locale.ROOT),
                    "left", args.get(0), "right", args.get(1));
            case "isnull" -> node("IsNullPredicate", "value", args.get(0));
            case "isnotnull" -> node("IsNotNullPredicate", "value",
                    args.get(0));
            case "equal" -> node("ComparisonExpression", "left",
                    args.get(0), "right", args.get(1), "operator", "EQUAL");
            case "notequal" -> node("ComparisonExpression", "left",
                    args.get(0), "right", args.get(1),
                    "operator", "NOT_EQUAL");
            case "greaterthan" -> node("ComparisonExpression", "left",
                    args.get(0), "right", args.get(1),
                    "operator", "GREATER_THAN");
            case "lessthan" -> node("ComparisonExpression", "left",
                    args.get(0), "right", args.get(1),
                    "operator", "LESS_THAN");
            case "in" -> node("InPredicate", "value", args.get(0),
                    "valueList", args.get(1));
            case "convertdate" -> node("FunctionCall",
                    "name", new QnH(List.of("to_date")),
                    "arguments", args);
            case "firstdayofmonth" -> dateTrunc("month", args);
            case "firstdayofquarter" -> dateTrunc("quarter", args);
            case "firstdayofyear" -> dateTrunc("year", args);
            case "firstdayofweek" -> dateTrunc("week", args);
            default -> node("FunctionCall", "name",
                    new QnH(List.of(PG_NAMES.getOrDefault(name, name))),
                    "arguments", args);
        };
    }

    /** Engine dateTruncCall (toPostgresModel.pure): date_trunc over the
     * arg, cast back to the date column type. */
    private static Object dateTrunc(String part, List<Object> args) {
        List<Object> a = new ArrayList<>();
        a.add(node("StringLiteral", "value", part));
        a.addAll(args);
        return node("Cast",
                "expression", node("FunctionCall", "name",
                        new QnH(List.of("date_trunc")), "arguments", a),
                "type", node("ColumnType", "name", "date"));
    }

    /** The engine's dyna-to-postgres FUNCTION-NAME table (toPostgresModel
     * .pure pairs where the names differ; identity renames pass
     * through the default arm). */
    private static final java.util.Map<String, String> PG_NAMES =
            java.util.Map.ofEntries(
                    java.util.Map.entry("log", "ln"),
                    java.util.Map.entry("denseRank", "dense_rank"),
                    java.util.Map.entry("joinStrings", "string_agg"),
                    java.util.Map.entry("matches", "regexp_like"),
                    java.util.Map.entry("repeatString", "repeat"),
                    java.util.Map.entry("indexOf", "strpos"),
                    java.util.Map.entry("datePart", "date"),
                    java.util.Map.entry("char", "chr"),
                    java.util.Map.entry("reverseString", "reverse"),
                    java.util.Map.entry("length", "char_length"),
                    java.util.Map.entry("splitPart", "split_part"),
                    java.util.Map.entry("toLower", "lower"),
                    java.util.Map.entry("toUpper", "upper"),
                    java.util.Map.entry("rem", "mod"),
                    java.util.Map.entry("pow", "power"),
                    java.util.Map.entry("stdDevSample", "stddev_samp"),
                    java.util.Map.entry("stdDevPopulation", "stddev_pop"),
                    java.util.Map.entry("booland", "bool_and"),
                    java.util.Map.entry("boolor", "bool_or"),
                    java.util.Map.entry("cumulativeDistribution",
                            "cume_dist"),
                    java.util.Map.entry("covarSample", "covar_samp"),
                    java.util.Map.entry("covarPopulation", "covar_pop"),
                    java.util.Map.entry("varianceSample", "var_samp"),
                    java.util.Map.entry("variancePopulation", "var_pop"),
                    java.util.Map.entry("first", "first_value"),
                    java.util.Map.entry("last", "last_value"),
                    java.util.Map.entry("nth", "nth_value"),
                    java.util.Map.entry("percentRank", "percent_rank"),
                    java.util.Map.entry("rowNumber", "row_number"),
                    java.util.Map.entry("averageRank", "average_rank"),
                    java.util.Map.entry("levenshteinDistance",
                            "edit_distance"),
                    java.util.Map.entry("timeBucket", "time_bucket"),
                    java.util.Map.entry("jaroWinklerSimilarity",
                            "jarowinkler_similarity"),
                    java.util.Map.entry("convertTimeZone",
                            "convert_timezone"),
                    java.util.Map.entry("encodeBase64", "encode_base64"),
                    java.util.Map.entry("decodeBase64", "decode_base64"),
                    java.util.Map.entry("generateGuid", "uuid"));

    /** The engine's getSafeType pair rule (typeInference.pure): equal
     * types keep; DECIMAL beats int/double/float as-is; two decimals
     * widen to DECIMAL(maxIntDigits+maxScale, maxScale); DOUBLE beats
     * integers. Null operands pass the other side through. */
    private static RelationalDataType safe(RelationalDataType a,
            RelationalDataType b) {
        if (a == null) {
            return b;
        }
        if (b == null) {
            return a;
        }
        if (a.equals(b)) {
            return a;
        }
        Integer[] da = decimalOf(a);
        Integer[] db2 = decimalOf(b);
        if (da != null && db2 != null) {
            int intDigits = Math.max(da[0] - da[1], db2[0] - db2[1]);
            int scale = Math.max(da[1], db2[1]);
            return new RelationalDataType.Decimal(intDigits + scale, scale);
        }
        if (da != null) {
            return new RelationalDataType.Decimal(da[0], da[1]);
        }
        if (db2 != null) {
            return new RelationalDataType.Decimal(db2[0], db2[1]);
        }
        boolean aFloat = a instanceof RelationalDataType.Double_
                || a instanceof RelationalDataType.Float_
                || a instanceof RelationalDataType.Real;
        boolean bFloat = b instanceof RelationalDataType.Double_
                || b instanceof RelationalDataType.Float_
                || b instanceof RelationalDataType.Real;
        if (aFloat || bFloat) {
            return new RelationalDataType.Double_();
        }
        if (a instanceof RelationalDataType.Varchar va
                && b instanceof RelationalDataType.Varchar vb) {
            return new RelationalDataType.Varchar(
                    Math.max(va.size(), vb.size()));
        }
        return a;
    }

    private static Integer[] decimalOf(RelationalDataType t) {
        if (t instanceof RelationalDataType.Decimal d) {
            return new Integer[]{d.precision(), d.scale()};
        }
        if (t instanceof RelationalDataType.Numeric n) {
            return new Integer[]{n.precision(), n.scale()};
        }
        return null;
    }

    /** The declared type of {@code table.column}: the op's OWN database
     * (mapping expressions carry it) via ctx, else the handle db —
     * searching declared schemas and the top-level default. */
    private static RelationalDataType columnType(Rop env, String opDb,
            String table, String column) {
        DatabaseDefinition db = env.db();
        if (opDb != null && env.ctx() != null) {
            db = env.ctx().findDatabase(opDb).orElse(db);
        }
        if (db == null) {
            return null;
        }
        List<DatabaseDefinition.TableDefinition> all = new ArrayList<>(
                db.tables());
        for (var s : db.schemas()) {
            all.addAll(s.tables());
        }
        // ColumnRefs may spell schema-qualified names (default.T / S.T)
        String bare = table.contains(".")
                ? table.substring(table.lastIndexOf('.') + 1) : table;
        for (var t : all) {
            if (t.name().equalsIgnoreCase(bare)) {
                for (var c : t.columns()) {
                    if (c.name().equalsIgnoreCase(column)) {
                        return c.dataType();
                    }
                }
            }
        }
        // views resolve THROUGH their column expression (view-on-view)
        List<DatabaseDefinition.ViewDefinition> vs = new ArrayList<>(
                db.views());
        for (var s : db.schemas()) {
            vs.addAll(s.views());
        }
        for (var v : vs) {
            if (v.name().equalsIgnoreCase(bare)) {
                for (var cm : v.columnMappings()) {
                    if (cm.name().equalsIgnoreCase(column)) {
                        return inferOp(new Rop(db, env.ctx(),
                                cm.expression()), cm.expression());
                    }
                }
            }
        }
        return null;
    }

    /** {@code dataTypeToSqlText} — the ENGINE's spelling: {@code
     * DECIMAL(p, s)} with a space, {@code BIT} for booleans (distinct
     * from the plan-tuple spelling in PlanText.spell). */
    public static Object sqlText(Object recv) {
        Object r = recv instanceof List<?> l && l.size() == 1
                ? l.get(0) : recv;
        if (!(r instanceof Dt d)) {
            return null;
        }
        return switch (d.type()) {
            case RelationalDataType.Integer_ ignored -> "INT";
            case RelationalDataType.BigInt ignored -> "BIGINT";
            case RelationalDataType.SmallInt ignored -> "SMALLINT";
            case RelationalDataType.TinyInt ignored -> "TINYINT";
            case RelationalDataType.Varchar v -> "VARCHAR(" + v.size() + ")";
            case RelationalDataType.Char_ c -> "CHAR(" + c.size() + ")";
            case RelationalDataType.Double_ ignored -> "DOUBLE";
            case RelationalDataType.Float_ ignored -> "FLOAT";
            case RelationalDataType.Real ignored -> "REAL";
            case RelationalDataType.Decimal dc ->
                    "DECIMAL(" + dc.precision() + ", " + dc.scale() + ")";
            case RelationalDataType.Numeric n ->
                    "NUMERIC(" + n.precision() + ", " + n.scale() + ")";
            case RelationalDataType.Timestamp ignored -> "TIMESTAMP";
            case RelationalDataType.Date_ ignored -> "DATE";
            case RelationalDataType.Bit ignored -> "BIT";
            case RelationalDataType.Other ignored -> "OTHER";
            default -> throw new NotImplementedException(
                    "dataTypeToSqlText spelling for " + d.type()
                    + " pending");
        };
    }
}
