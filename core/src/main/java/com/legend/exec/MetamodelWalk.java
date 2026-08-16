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

    public record Rop(@com.legend.Nullable DatabaseDefinition db, @com.legend.Nullable ModelContext ctx,
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

    public record TacH(String aliasName, @com.legend.Nullable Object column) {
    }

    /** A DynaFunction whose arguments MIX relational ops with walked
     * metamodel handles (getColumn chains) — converted per-arg. */
    public record DynH(String name, List<Object> args) {
    }

    /** A JoinTreeNode handle: the join's condition + display type, an
     * optional copy-constructed alias override (subselect targets), and
     * chained child hops. {@code children} is mutable — the chain
     * builder links hops in place. */
    public record JtnH(com.legend.model.DatabaseDefinition db,
            String joinName, RelationalOperation operation,
            @com.legend.Nullable String joinType, @com.legend.Nullable Object aliasOverride,
            List<Object> children) {

        public @com.legend.Nullable JtnH withAlias(Object alias) {
            return new JtnH(db, joinName, operation, joinType, alias,
                    children);
        }
    }

    /** RelationalOperationElementWithJoin — the join-slot property
     * mapping's element (relational.pure:386). */
    public record RoewjH(Object jtn) {
    }

    /** GENERIC SQL-protocol node value: kind + ctor-provided props.
     * SORTED map (order-insensitive print+equality) and EMPTY/null
     * props dropped — the engine ctor-vs-converter default asymmetry
     * (arguments=[] vs absent) compares equal. */
    public record NodeH(String kind,
            java.util.TreeMap<String, Object> props) {
    }

    static @com.legend.Nullable NodeH node(String kind,
            @com.legend.Nullable Object... kv) {
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
    public static @com.legend.Nullable NodeH nodeOf(String kind,
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
        if ("FunctionCall".equals(kind) || "Select".equals(kind)
                || "Union".equals(kind)) {
            // class-declared ctor defaults (distinct: Boolean[1] = false)
            m.putIfAbsent("distinct", Boolean.FALSE);
        }
        return new NodeH(kind, m);
    }

    /** {@code schema('name')} navigation over a Database handle. */
    public static @com.legend.Nullable Object schema(Object recv, String name) {
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
    public static @com.legend.Nullable Object table(Object recv, String name) {
        if (recv instanceof Sch s) {
            for (var t : s.schema().tables()) {
                if (t.name().equals(name)) {
                    return new Tbl(s.db(), s.schema().name(), t);
                }
            }
        }
        return null;
    }

    /** Conversion state (the engine's ModelConversionState axes that
     * change output shape): {@code rootSelect} controls the
     * TableSubquery wrap of a converted SelectSQLQuery;
     * {@code processingSelect} the SingleColumn wrap of column
     * references (convertColumn:206). */
    record ConvState(boolean rootSelect, boolean processingSelect) {
        static final ConvState ROOT = new ConvState(true, false);

        ConvState nested() {
            return new ConvState(false, false);
        }

        ConvState selectItems() {
            return new ConvState(rootSelect, true);
        }

        /** Expression position — column refs stay bare references. */
        ConvState expr() {
            return processingSelect
                    ? new ConvState(rootSelect, false) : this;
        }
    }

    public static @com.legend.Nullable Object convertElement(
            @com.legend.Nullable Object recv) {
        return convertElement(recv, ConvState.ROOT);
    }

    /** {@code convertElement} — the toPostgresModel element arms
     * (toPostgresModel.pure:82-116 + convertColumn:203 +
     * convertTableAliasName:785: alias names QUOTE). */
    static @com.legend.Nullable Object convertElement(@com.legend.Nullable Object recv,
            ConvState st) {
        Object r = recv instanceof List<?> l && l.size() == 1
                ? l.get(0) : recv;
        if (r instanceof TacH t) {
            String col = t.column() instanceof ColH ch ? ch.c().name()
                    : String.valueOf(t.column());
            return columnRef(List.of("\"" + t.aliasName() + "\"", col), st);
        }
        if (r instanceof CnH c) {
            return columnRef(List.of(c.name()), st);
        }
        if (r instanceof ColH ch) {
            return columnRef(List.of(ch.c().name()), st);
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
                Object v = convertElement(arg, st.expr());
                if (v == null) {
                    return null;
                }
                converted.add(v);
            }
            return dynaNode(dh.name(), converted);
        }
        if (r instanceof AliasH ah) {
            Object rel = convertElement(ah.relationalElement(), st);
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
            return convertNodeKind(nh, st);
        }
        return null;
    }

    /** convertColumn (:203): a bare reference, wrapped as a
     * SingleColumn in select-items position. */
    private static @com.legend.Nullable Object columnRef(List<String> parts, ConvState st) {
        Object ref = new QnrH(new QnH(parts));
        return st.processingSelect()
                ? node("SingleColumn", "expression", ref) : ref;
    }

    /** Constructed-metamodel instances carried as generic NodeH handles
     * (placeholders, window columns, tabular functions, query-level
     * relations) — the toPostgresModel arms at :89-116. */
    private static @com.legend.Nullable Object convertNodeKind(NodeH nh, ConvState st) {
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
            case "SelectSQLQuery", "TdsSelectSqlQuery" ->
                    convertSelectSql(nh, st);
            case "RootJoinTreeNode" -> convertJoinTree(nh, st);
            case "Union" -> convertRelUnion(nh, true);
            case "UnionAll" -> convertRelUnion(nh, false);
            case "JoinStrings" -> convertJoinStrings(nh, st);
            case "CommonTableExpressionReference" -> node("Table", "name",
                    new QnH(List.of(String.valueOf(
                            nh.props().get("name")))));
            default -> null;
        };
    }

    /** Engine convertSelectSQLQuery (toPostgresModel.pure:119-165): the
     * one ExtendedQuerySpecification build — CTEs hoist into a
     * QueryWithScope, and a NON-root select wraps as a TableSubquery. */
    private static @com.legend.Nullable Object convertSelectSql(NodeH nh, ConvState st) {
        ConvState inner = st.nested();
        Object from = null;
        if (nh.props().get("data") != null) {
            from = convertElement(nh.props().get("data"), inner);
            if (from == null) {
                return null;
            }
        }
        List<Object> items = new ArrayList<>();
        for (Object c : asList(nh.props().get("columns"))) {
            Object v = convertElement(c, inner.selectItems());
            if (v == null) {
                return null;
            }
            items.add(v instanceof NodeH n && ("SingleColumn"
                    .equals(n.kind()) || "AllColumns".equals(n.kind()))
                    ? v : node("SingleColumn", "expression", v));
        }
        Object select = node("Select",
                "distinct", nh.props().get("distinct"),
                "selectItems", items.isEmpty()
                        ? List.of(node("AllColumns")) : items);
        Object where = optionalExpr(nh.props().get("filteringOperation"),
                inner);
        List<Object> groupBy = new ArrayList<>();
        for (Object g : asList(nh.props().get("groupBy"))) {
            Object v = convertElement(g, inner.expr());
            if (v == null) {
                return null;
            }
            groupBy.add(v);
        }
        Object having = optionalExpr(nh.props().get("havingOperation"),
                inner);
        Object qualify = optionalExpr(nh.props().get("qualifyOperation"),
                inner);
        List<Object> orderBy = new ArrayList<>();
        for (Object o : asList(nh.props().get("orderBy"))) {
            if (!(o instanceof NodeH ob) || !"OrderBy".equals(ob.kind())) {
                return null;
            }
            Object key = convertElement(ob.props().get("column"),
                    inner.expr());
            if (key == null) {
                return null;
            }
            orderBy.add(node("SortItem", "sortKey", key,
                    "ordering", "DESC".equals(String.valueOf(
                            ob.props().get("direction")))
                            ? "DESCENDING" : "ASCENDING",
                    "nullOrdering", "UNDEFINED"));
        }
        // convertLimit (:170): limit = toRow - fromRow when both are
        // integer literals; offset = fromRow
        Long fromRow = literalLong(nh.props().get("fromRow"));
        Long toRow = literalLong(nh.props().get("toRow"));
        Object limit = toRow == null ? null
                : node("IntegerLiteral", "value",
                        fromRow == null ? toRow : toRow - fromRow);
        Object offset = fromRow == null ? null
                : node("IntegerLiteral", "value", fromRow);
        List<Object> withQueries = new ArrayList<>();
        for (Object e : asList(nh.props().get("commonTableExpressions"))) {
            if (!(e instanceof NodeH cte)
                    || !"CommonTableExpression".equals(cte.kind())) {
                return null;
            }
            Object q = convertElement(cte.props().get("sqlQuery"),
                    ConvState.ROOT);
            if (q == null) {
                return null;
            }
            withQueries.add(node("WithQuery",
                    "name", cte.props().get("name"),
                    "query", node("Query", "queryBody", q)));
        }
        Object base = node("ExtendedQuerySpecification",
                "select", select, "from", from, "where", where,
                "groupBy", groupBy, "having", having,
                "orderBy", orderBy, "limit", limit, "offset", offset,
                "qualify", qualify);
        Object query = withQueries.isEmpty() ? base
                : node("QueryWithScope",
                        "with", node("With", "withQueries", withQueries),
                        "queryBody", base);
        return st.rootSelect() ? query
                : node("TableSubquery", "query",
                        node("Query", "queryBody", query));
    }

    private static @com.legend.Nullable Object optionalExpr(@com.legend.Nullable Object v,
            ConvState inner) {
        List<Object> els = asList(v);
        return els.isEmpty() ? null
                : convertElement(els.get(0), inner.expr());
    }

    private static @com.legend.Nullable Long literalLong(@com.legend.Nullable Object v) {
        Object r = v instanceof List<?> l && l.size() == 1 ? l.get(0) : v;
        return r instanceof Rop rop
                && rop.op() instanceof RelationalOperation.Literal li
                && li.value() instanceof Number n2 ? n2.longValue() : null;
    }

    /** Engine convertJoinTreeNode (:209): pre-order relations fold into
     * left-nested LEFT joins; each child's relation is its alias
     * override (subselects) or the join's TARGET table aliased by the
     * table's own name. */
    private static @com.legend.Nullable Object convertJoinTree(NodeH nh, ConvState st) {
        Object rootAlias = nh.props().get("alias");
        Object acc = convertElement(rootAlias, st);
        if (acc == null) {
            return null;
        }
        String parentTable = rootAlias instanceof AliasH ah
                && ah.relationalElement() instanceof Tbl tb
                ? tb.t().name() : null;
        return foldChildren(acc, asList(nh.props().get("childrenData")),
                parentTable, st);
    }

    private static @com.legend.Nullable Object foldChildren(
            @com.legend.Nullable Object acc, List<Object> children,
            @com.legend.Nullable String parentTable, ConvState st) {
        for (Object c : children) {
            if (!(c instanceof JtnH jt)) {
                return null;
            }
            String target = null;
            Object rel;
            if (jt.aliasOverride() != null) {
                rel = convertElement(jt.aliasOverride(), st);
            } else {
                target = targetTable(jt.operation(), parentTable);
                Object tbl = target == null ? null
                        : tableHandle(jt.db(), target);
                rel = tbl == null ? null
                        : node("AliasedRelation",
                                "alias", "\"" + target + "\"",
                                "relation", convertElement(tbl, st));
            }
            Object cond = convertOp(jt.operation());
            if (rel == null || cond == null) {
                return null;
            }
            acc = node("Join", "left", acc, "right", rel,
                    "type", sqlJoinType(jt.joinType()),
                    "criteria", node("JoinOn", "expression", cond));
            if (!jt.children().isEmpty()) {
                acc = foldChildren(acc, jt.children(),
                        target != null ? target : parentTable, st);
                if (acc == null) {
                    return null;
                }
            }
        }
        return acc;
    }

    /** convertJoinType (:240): LEFT_OUTER default. */
    private static @com.legend.Nullable String sqlJoinType(
            @com.legend.Nullable String joinType) {
        return switch (joinType == null ? "LEFT_OUTER" : joinType) {
            case "INNER" -> "INNER";
            case "RIGHT_OUTER" -> "RIGHT";
            case "FULL_OUTER" -> "FULL";
            default -> "LEFT";
        };
    }

    /** The join operation's table that is NOT the parent's — the
     * engine mapping compiler's join-target alias. */
    private static @com.legend.Nullable String targetTable(RelationalOperation op,
            @com.legend.Nullable String parentTable) {
        List<String> tables = new ArrayList<>();
        collectTables(op, tables);
        for (String t : tables) {
            if (!t.equals(parentTable)) {
                return t;
            }
        }
        return tables.isEmpty() ? null : tables.get(0);
    }

    private static void collectTables(RelationalOperation op,
            List<String> out) {
        switch (op) {
            case RelationalOperation.ColumnRef cr -> {
                if (!out.contains(cr.table())) {
                    out.add(cr.table());
                }
            }
            case RelationalOperation.Comparison cp -> {
                collectTables(cp.left(), out);
                collectTables(cp.right(), out);
            }
            case RelationalOperation.BooleanOp bo -> {
                collectTables(bo.left(), out);
                collectTables(bo.right(), out);
            }
            case RelationalOperation.Group g ->
                    collectTables(g.inner(), out);
            case RelationalOperation.FunctionCall f -> {
                for (var a : f.args()) {
                    collectTables(a, out);
                }
            }
            default -> {
            }
        }
    }

    /** A table (any schema, default included) as a Tbl handle. */
    private static @com.legend.Nullable Object tableHandle(
            com.legend.model.DatabaseDefinition db, String name) {
        for (var s : db.schemas()) {
            for (var t : s.tables()) {
                if (t.name().equals(name)) {
                    return new Tbl(db, s.name(), t);
                }
            }
        }
        for (var t : defaultSchema(db).tables()) {
            if (t.name().equals(name)) {
                return new Tbl(db, "default", t);
            }
        }
        return null;
    }

    /** Engine convertUnion (:867): queries convert under FRESH root
     * states, pairwise-folded, always subquery-wrapped. */
    private static @com.legend.Nullable Object convertRelUnion(NodeH nh, boolean distinct) {
        Object acc = null;
        for (Object q : asList(nh.props().get("queries"))) {
            Object v = convertElement(q, ConvState.ROOT);
            if (v == null) {
                return null;
            }
            acc = acc == null ? v : node("Union", "left", acc,
                    "right", v, "distinct", distinct);
        }
        return acc == null ? null
                : node("TableSubquery", "query",
                        node("Query", "queryBody", acc));
    }

    /** Engine convertJoinStrings (:844): one string aggregates via
     * string_agg (empty-string separator default); several concat with
     * the separator interleaved between prefix/suffix. Empty-string
     * literal prefix/suffix/separator count as absent (:833). */
    private static @com.legend.Nullable Object convertJoinStrings(NodeH nh, ConvState st) {
        List<Object> strings = new ArrayList<>();
        for (Object s : asList(nh.props().get("strings"))) {
            Object v = convertElement(s, st.expr());
            if (v == null) {
                return null;
            }
            strings.add(v);
        }
        Object sep = joinStringsArg(nh.props().get("separator"));
        Object prefix = joinStringsArg(nh.props().get("prefix"));
        Object suffix = joinStringsArg(nh.props().get("suffix"));
        if (strings.size() == 1) {
            return node("FunctionCall",
                    "name", new QnH(List.of("string_agg")),
                    "arguments", List.of(strings.get(0), sep != null
                            ? sep : node("StringLiteral", "value", "")));
        }
        List<Object> args = new ArrayList<>();
        if (prefix != null) {
            args.add(prefix);
        }
        for (int i = 0; i < strings.size(); i++) {
            if (i > 0 && sep != null) {
                args.add(sep);
            }
            args.add(strings.get(i));
        }
        if (suffix != null) {
            args.add(suffix);
        }
        return node("FunctionCall", "name", new QnH(List.of("concat")),
                "arguments", args);
    }

    private static @com.legend.Nullable Object joinStringsArg(
            @com.legend.Nullable Object v) {
        Object r = v instanceof List<?> l && l.size() == 1 ? l.get(0) : v;
        if (r == null) {
            return null;
        }
        if (r instanceof Rop rop
                && rop.op() instanceof RelationalOperation.Literal li
                && "".equals(li.value())) {
            return null;
        }
        return convertElement(r, ConvState.ROOT.expr());
    }

    /** Engine convertWindowColumn (toPostgresModel.pure:817-831): the
     * dyna func converts to a FunctionCall which gains a window of the
     * converted partitions + sort infos; ASC sorts NULLS LAST, DESC
     * NULLS FIRST. */
    private static @com.legend.Nullable Object convertWindowColumn(NodeH nh) {
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
    private static List<Object> asList(@com.legend.Nullable Object v) {
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
    private static @com.legend.Nullable Object convertOp(RelationalOperation op) {
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
            // join-condition vocabulary: table-qualified refs spell the
            // table name as the QUOTED alias (the mapping compiler's
            // table-name aliases)
            case RelationalOperation.ColumnRef cr -> new QnrH(new QnH(
                    List.of("\"" + cr.table() + "\"", cr.column())));
            case RelationalOperation.Comparison cp -> {
                Object l2 = convertOp(cp.left());
                Object r2 = convertOp(cp.right());
                yield l2 == null || r2 == null ? null
                        : node("ComparisonExpression", "left", l2,
                                "right", r2, "operator",
                                switch (cp.op()) {
                                    case EQ -> "EQUAL";
                                    case NEQ -> "NOT_EQUAL";
                                    case LT -> "LESS_THAN";
                                    case LTE -> "LESS_THAN_OR_EQUAL";
                                    case GT -> "GREATER_THAN";
                                    case GTE -> "GREATER_THAN_OR_EQUAL";
                                });
            }
            case RelationalOperation.BooleanOp bo -> {
                Object l3 = convertOp(bo.left());
                Object r3 = convertOp(bo.right());
                yield l3 == null || r3 == null ? null
                        : node("LogicalBinaryExpression",
                                "type", bo.op().name(),
                                "left", l3, "right", r3);
            }
            case RelationalOperation.Group g -> convertOp(g.inner());
            default -> null;
        };
    }

    public record Mm(ModelContext ctx,
            com.legend.model.LegacyMappingDefinition mapping) {
    }

    /** A class-mapping SET handle; {@code owner} is the mapping that
     * DECLARES the set (the pure graph's {@code SetImplementation
     * .parent}) — {@code superMapping} resolves the extends target
     * there, not in the queried mapping. */
    public record Cm(ModelContext ctx,
            com.legend.model.LegacyMappingDefinition owner,
            com.legend.model.ClassMapping.Relational cm) {
    }

    public record Pm(ModelContext ctx,
            com.legend.model.PropertyMapping pm) {
    }

    /** A Mapping ELEMENT reference as a metamodel handle, or null. */
    public static @com.legend.Nullable Object mapping(ModelContext ctx, String fqn) {
return ctx.findLegacyMapping(fqn).map(m -> new Mm(ctx, m))
                .orElse(null);
    }

    /** {@code rootClassMappingByClass} — the class's relational set. */
    public static @com.legend.Nullable Object rootClassMappingByClass(Object recv,
            String classFqn) {
        // INCLUDE closure (same walk as classMappingsByClass — the
        // engine resolves rootClassMappingByClass through includes;
        // simpleRelationalMapping maps Person in its included mapping)
        if (recv instanceof Mm m) {
            java.util.List<Object> all = new java.util.ArrayList<>();
            collectClassMappings(m.ctx(), m.mapping(), classFqn, all,
                    new java.util.HashSet<>());
            return all.isEmpty() ? null : all.get(all.size() - 1);
        }
        return null;
    }

    /** Effective SET ID: explicit {@code [id]}, else the class FQN with
     * {@code ::} &rarr; {@code _} — the engine default (same rule as
     * MappingNormalizer.setIdOf; one line, kept package-local there). */
    private static @com.legend.Nullable String setIdOf(
            com.legend.model.ClassMapping.Relational r) {
        return r.setId() != null ? r.setId()
                : r.className().replace("::", "_");
    }

    /** {@code _classMappingByClass} (functions_Mapping.pure:28) — the
     * class's sets across the INCLUDE closure, includes first (the pure
     * body's order); the aggregation-aware / association enrichments are
     * not modeled (their consumers wall on their own reads). */
    public static java.util.List<Object> classMappingsByClass(Object recv,
            String classFqn) {
        java.util.List<Object> out = new java.util.ArrayList<>();
        if (recv instanceof Mm m) {
            collectClassMappings(m.ctx(), m.mapping(), classFqn, out,
                    new java.util.HashSet<>());
        }
        return out;
    }

    private static void collectClassMappings(ModelContext ctx,
            com.legend.model.LegacyMappingDefinition md, String classFqn,
            java.util.List<Object> out, java.util.Set<String> seen) {
        if (!seen.add(md.qualifiedName())) {
            return;
        }
        for (var inc : md.includes()) {
            ctx.findLegacyMapping(inc.mappingPath()).ifPresent(im ->
                    collectClassMappings(ctx, im, classFqn, out, seen));
        }
        for (var cm : md.classMappings()) {
            if (cm instanceof com.legend.model.ClassMapping.Relational r
                    && r.className().equals(classFqn)) {
                out.add(new Cm(ctx, md, r));
            }
        }
    }

    /** {@code classMappingById} (real functions_Mapping.pure:74) —
     * includes walk first, then own class mappings, matched by set id. */
    public static @com.legend.Nullable Object classMappingById(Object recv, String id) {
        return recv instanceof Mm m
                ? classMappingByIdIn(m.ctx(), m.mapping(), id) : null;
    }

    private static @com.legend.Nullable Object classMappingByIdIn(ModelContext ctx,
            com.legend.model.LegacyMappingDefinition mapping, String id) {
        for (var inc : mapping.includes()) {
            var im = ctx.findLegacyMapping(inc.mappingPath()).orElse(null);
            if (im != null) {
                Object hit = classMappingByIdIn(ctx, im, id);
                if (hit != null) {
                    return hit;
                }
            }
        }
        for (var cm : mapping.classMappings()) {
            if (cm instanceof com.legend.model.ClassMapping.Relational r
                    && java.util.Objects.equals(setIdOf(r), id)) {
                return new Cm(ctx, mapping, r);
            }
        }
        return null;
    }

    /** {@code superMapping} (real functions_PropertyMappings
     * Implementation.pure:19) — the extends target, resolved by set id
     * in the set's PARENT (declaring) mapping. */
    public static @com.legend.Nullable Object superMapping(
            @com.legend.Nullable Object recv) {
        return recv instanceof Cm c && c.cm().extendsSetId() != null
                ? classMappingByIdIn(c.ctx(), c.owner(),
                        c.cm().extendsSetId())
                : null;
    }

    /** {@code allSuperSetImplementations} (real engine mappingExtension
     * .pure:163) — the extends chain ROOT-FIRST, each hop resolved
     * against the QUERIED mapping {@code m}. */
    public static @com.legend.Nullable Object allSuperSetImplementations(
            Object set, @com.legend.Nullable Object m) {
        if (!(set instanceof Cm) || !(m instanceof Mm mm)) {
            return null;
        }
        List<Object> out = new ArrayList<>();
        Object cur = set;
        while (cur instanceof Cm cc && cc.cm().extendsSetId() != null) {
            Object sup = classMappingByIdIn(mm.ctx(), mm.mapping(),
                    cc.cm().extendsSetId());
            if (sup == null) {
                break;
            }
            out.add(0, sup);
            cur = sup;
        }
        return out;
    }

    /** {@code mainTable} (real platform functions.pure:277 —
     * {@code mainTableAlias.relationalElement}): the set's own
     * {@code ~mainTable}, else the extends chain's (the relational
     * compiler populates extends sets from their super), else the one
     * table its property mappings read (the table-less inference). */
    public static @com.legend.Nullable Object mainTable(@com.legend.Nullable Object recv) {
        if (!(recv instanceof Cm c)) {
            return null;
        }
        var mt = c.cm().mainTable();
        if (mt != null) {
            return tableHandle(c.ctx(), mt.database(), mt.table());
        }
        if (c.cm().extendsSetId() != null) {
            return mainTable(superMapping(recv));
        }
        for (var pm : c.cm().propertyMappings()) {
            if (pm instanceof com.legend.model.PropertyMapping.Column col) {
                return tableHandle(c.ctx(), col.database(), col.table());
            }
        }
        return null;
    }

    private static @com.legend.Nullable Object tableHandle(ModelContext ctx, String dbFqn,
            String tableName) {
        var dbh = database(ctx, dbFqn);
        if (!(dbh instanceof Db d)) {
            return null;
        }
        for (var s : d.db().schemas()) {
            for (var t : s.tables()) {
                if (t.name().equals(tableName)) {
                    return new Tbl(d.db(), s.name(), t);
                }
            }
        }
        var ds = defaultSchema(d.db());
        for (var t : ds.tables()) {
            if (t.name().equals(tableName)) {
                return new Tbl(d.db(), ds.name(), t);
            }
        }
        return null;
    }

    /** {@code resolvePrimaryKey} (real platform functions.pure:191) —
     * the engine's this-vs-super precedence table: groupBy beats
     * distinct beats user-declared PK, this before super at each rank;
     * no super = the set's own compiled primaryKey. */
    public static @com.legend.Nullable Object resolvePrimaryKey(Object recv) {
        if (!(recv instanceof Cm c)) {
            return null;
        }
        Object sup = superMapping(recv);
        if (sup == null) {
            return primaryKeyOf(c);
        }
        if (!c.cm().groupBy().isEmpty()) {
            return primaryKeyOf(c);
        }
        if (resolveGroupBy(sup)) {
            return resolvePrimaryKey(sup);
        }
        if (c.cm().distinct()) {
            return primaryKeyOf(c);
        }
        if (resolveDistinct(sup)) {
            return resolvePrimaryKey(sup);
        }
        if (!c.cm().primaryKey().isEmpty()) {
            return primaryKeyOf(c);
        }
        if (resolveUserDefinedPrimaryKey(sup)) {
            return resolvePrimaryKey(sup);
        }
        return primaryKeyOf(c);
    }

    /** {@code resolveGroupBy} presence (real functions.pure:155). */
    private static boolean resolveGroupBy(@com.legend.Nullable Object h) {
        return h instanceof Cm c && (!c.cm().groupBy().isEmpty()
                || resolveGroupBy(superMapping(h)));
    }

    /** {@code resolveDistinct} (real functions.pure:167). */
    private static boolean resolveDistinct(@com.legend.Nullable Object h) {
        return h instanceof Cm c && (c.cm().distinct()
                || resolveDistinct(superMapping(h)));
    }

    /** {@code resolveUserDefinedPrimaryKey} (real functions.pure:179). */
    private static boolean resolveUserDefinedPrimaryKey(@com.legend.Nullable Object h) {
        return h instanceof Cm c && (!c.cm().primaryKey().isEmpty()
                || resolveUserDefinedPrimaryKey(superMapping(h)));
    }

    /** The set's COMPILED {@code primaryKey} property — the relational
     * mapping compiler's population rule: user {@code ~primaryKey},
     * else {@code ~groupBy} columns, else {@code ~distinct} = every
     * mapped column, else the main table's PRIMARY KEY. TableAliasColumn
     * handles; any non-column PK expression nulls (wall stands). */
    private static @com.legend.Nullable Object primaryKeyOf(Cm c) {
        if (!(mainTable(c) instanceof Tbl t)) {
            return null;
        }
        List<RelationalOperation> ops;
        if (!c.cm().primaryKey().isEmpty()) {
            ops = c.cm().primaryKey();
        } else if (!c.cm().groupBy().isEmpty()) {
            ops = c.cm().groupBy();
        } else if (c.cm().distinct()) {
            ops = new ArrayList<>();
            for (var pm : c.cm().propertyMappings()) {
                if (pm instanceof com.legend.model.PropertyMapping
                        .Column pc) {
                    ops.add(new RelationalOperation.ColumnRef(
                            pc.database(), pc.table(), pc.column()));
                }
            }
        } else {
            List<Object> out = new ArrayList<>();
            for (var col : t.t().columns()) {
                if (col.primaryKey()) {
                    out.add(new TacH(t.t().name(), new ColH(col)));
                }
            }
            return out;
        }
        List<Object> out = new ArrayList<>();
        for (RelationalOperation op : ops) {
            if (!(op instanceof RelationalOperation.ColumnRef cr)) {
                return null;
            }
            DatabaseDefinition.ColumnDefinition col = null;
            for (var tc : t.t().columns()) {
                if (tc.name().equals(cr.column())) {
                    col = tc;
                    break;
                }
            }
            if (col == null) {
                return null;
            }
            out.add(new TacH(t.t().name(), new ColH(col)));
        }
        return out;
    }

    /** {@code propertyMappingsByPropertyName} — declaration order. */
    public static @com.legend.Nullable Object propertyMappingsByName(Object recv, String name) {
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
    public static @com.legend.Nullable Object database(ModelContext ctx, String fqn) {
        return ctx.findDatabase(fqn).map(Db::new).orElse(null);
    }

    /** Property step over a handle; null = not a metamodel property. */
    public static @com.legend.Nullable Object prop(Object recv, String prop) {
        if (recv instanceof Cm cmh && prop.equals("id")) {
            return setIdOf(cmh.cm());
        }
        if (recv instanceof Cm cmr && prop.equals("root")) {
            if (cmr.cm().root()) {
                return true;
            }
            // ENGINE: a class's SOLE set is implicitly root — the '*'
            // marker only disambiguates multiple sets (compiler default)
            java.util.List<Object> all = new java.util.ArrayList<>();
            collectClassMappings(cmr.ctx(), cmr.owner(),
                    cmr.cm().className(), all, new java.util.HashSet<>());
            return all.size() == 1;
        }
        if (recv instanceof TacH tac && prop.equals("column")) {
            return tac.column();
        }
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
                case com.legend.model.PropertyMapping.Join j ->
                        joinTreeOf(p.ctx(), j.database(), j.joins());
                default -> null;
            };
        }
        if (recv instanceof RoewjH rj && prop.equals("joinTreeNode")) {
            return rj.jtn();
        }
        return null;
    }

    /** A join-slot property mapping's RelationalOperationElementWithJoin
     * handle: the join chain as nested JoinTreeNode handles (each hop's
     * children carry the next). */
    private static @com.legend.Nullable Object joinTreeOf(ModelContext ctx, String dbFqn,
            List<com.legend.model.JoinChainElement> hops) {
        Object dbh = database(ctx, dbFqn);
        if (!(dbh instanceof Db d)) {
            return null;
        }
        JtnH head = null;
        JtnH tail = null;
        for (var hop : hops) {
            String hopDbFqn = hop.databaseName() != null
                    ? hop.databaseName() : dbFqn;
            var hopDb = database(ctx, hopDbFqn) instanceof Db hd
                    ? hd.db() : d.db();
            com.legend.model.DatabaseDefinition.JoinDefinition def = null;
            for (var jd : hopDb.joins()) {
                if (jd.name().equals(hop.joinName())) {
                    def = jd;
                    break;
                }
            }
            if (def == null) {
                return null;
            }
            JtnH node2 = new JtnH(hopDb, hop.joinName(), def.operation(),
                    hop.joinType() == null ? null : hop.joinType().name(),
                    null, new ArrayList<>());
            if (head == null) {
                head = node2;
            } else {
                java.util.Objects.requireNonNull(tail, "tail").children().add(node2);
            }
            tail = node2;
        }
        return head == null ? null : new RoewjH(head);
    }

    /** {@code schema->view('name')} navigation; null = not applicable. */
    public static @com.legend.Nullable Object view(Object recv, String name) {
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
    public static @com.legend.Nullable Object infer(Object recv) {
        Object r = recv instanceof List<?> l && l.size() == 1
                ? l.get(0) : recv;
        if (!(r instanceof Rop rop)) {
            return null;
        }
        RelationalDataType t = inferOp(rop, rop.op());
        return t == null ? null : new Dt(t);
    }

    private static @com.legend.Nullable RelationalDataType inferOp(Rop env,
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
                // engine inferDynaFunctionReturnType: sqlTrue/sqlFalse
                // are BIT literals (ledger cluster 56)
                case "sqltrue", "sqlfalse" -> new RelationalDataType.Bit();
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
            case RelationalOperation.JoinNavigation j -> {
                if (j.terminal() == null) {
                    yield null;
                }
                // the nav's own [DB] qualifier scopes its TERMINAL (the
                // terminal ColumnRef is stored db-relative — adjudication
                // ledger cluster 8: dropping it left the column untyped
                // and concat inferred VARCHAR(n+0))
                Rop sub = env;
                if (j.databaseName() != null && env.ctx() != null) {
                    var jdb = env.ctx().findDatabase(j.databaseName())
                            .orElse(null);
                    if (jdb != null) {
                        sub = new Rop(jdb, env.ctx(), env.op());
                    }
                }
                yield inferOp(sub, j.terminal());
            }
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
    private static @com.legend.Nullable Object dynaNode(String name, List<Object> args) {
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
    private static @com.legend.Nullable Object dateTrunc(String part, List<Object> args) {
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
    private static @com.legend.Nullable RelationalDataType safe(
            @com.legend.Nullable RelationalDataType a,
            @com.legend.Nullable RelationalDataType b) {
        if (a == null) {
            return b;
        }
        if (b == null) {
            return a;
        }
        if (a.equals(b)) {
            return a;
        }
        // engine getSafeType: OTHER absorbs into the other operand
        // (getSafeType(Other, X) = X; an Other operand contributes size 0
        // to the Char/Varchar max-size rule) — ledger cluster 56
        if (a instanceof RelationalDataType.Other) {
            return b;
        }
        if (b instanceof RelationalDataType.Other) {
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

    private static Integer @com.legend.Nullable [] decimalOf(
            RelationalDataType t) {
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
    private static @com.legend.Nullable RelationalDataType columnType(
            Rop env, @com.legend.Nullable String opDb,
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
    public static @com.legend.Nullable Object sqlText(Object recv) {
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
