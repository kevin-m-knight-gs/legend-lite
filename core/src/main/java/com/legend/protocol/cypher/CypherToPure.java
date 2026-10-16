// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend.protocol.cypher;

import com.legend.protocol.cypher.CypherAst.BinaryOp;
import com.legend.protocol.cypher.CypherAst.Clause;
import com.legend.protocol.cypher.CypherAst.Expr;
import com.legend.protocol.cypher.CypherAst.Item;
import com.legend.protocol.cypher.CypherAst.NodePattern;
import com.legend.protocol.cypher.CypherAst.PathPattern;
import com.legend.protocol.cypher.CypherAst.PatternElement;
import com.legend.protocol.cypher.CypherAst.RelPattern;
import com.legend.protocol.cypher.CypherAst.SortItem;
import com.legend.protocol.spec.AppliedFunction;
import com.legend.protocol.spec.AppliedProperty;
import com.legend.protocol.spec.CBoolean;
import com.legend.protocol.spec.CFloat;
import com.legend.protocol.spec.CInteger;
import com.legend.protocol.spec.CString;
import com.legend.protocol.spec.ColSpec;
import com.legend.protocol.spec.ColSpecArray;
import com.legend.protocol.spec.EnumValue;
import com.legend.protocol.spec.LambdaFunction;
import com.legend.protocol.spec.PackageableElementPtr;
import com.legend.protocol.spec.PureCollection;
import com.legend.protocol.spec.ValueSpecification;
import com.legend.protocol.spec.Variable;
import com.legend.protocol.sql.SqlAst;
import com.legend.protocol.sql.SqlAst.Pos;
import com.legend.protocol.sql.SqlToPure;
import com.legend.protocol.sql.SqlToPure.Kind;
import com.legend.protocol.sql.SqlToPure.Tx;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Cypher to Pure (docs/CYPHER_STUDY_2026_10_15.md §5.2, docs/CYPHER_PLAN_2026_10_15.md): a Cypher query over the
 * class model, as the Pure Relation expression a person would write, typed once by the Typer through
 * {@code compiler/spec/CypherIslands}. The graph is the model (C2): a label is a class, a relationship type without
 * properties is the property or association end that holds the other node, and a node's properties are the class's.
 * Java computes no value: every operation is a Pure spelling the database runs, and Neo4j's three-valued logic is
 * {@link SqlToPure.Tx}'s, the Postgres island's own, which Cypher shares (a comparison with {@code null} is
 * {@code null}; {@code WHERE} keeps the rows where the condition is true).
 *
 * <p>PR1 (the plan's first slice) reads {@code MATCH} over labels with fixed-length patterns, {@code WHERE},
 * {@code WITH}, {@code RETURN}, {@code ORDER BY}, {@code SKIP}, {@code LIMIT}, {@code DISTINCT}, literals,
 * comparisons, boolean logic, {@code CASE}, {@code coalesce}, and the implicit grouping of {@code count}, {@code sum},
 * {@code avg}, {@code min} and {@code max}. Everything else is refused by name: Neo4j's answer is never guessed.
 *
 * <p>A pattern is read from one ROOT node: the node from which every hop of the pattern navigates to at most one
 * node (a to-one property, or the to-one inverse end of a to-many one), so that each row of the result is one
 * instance of the root's class and every other node is a navigation from it ({@code $p.firm.studio}). A pattern with
 * no such root (one that fans out) is refused by name until the join form lands.
 */
public final class CypherToPure {

    /** What the translator asks of the model (C2): answered over the compiled model by the island seam. A label or a
     *  property is a name as written in the query, case-sensitive, as Cypher's names are. */
    public interface Model {

        /** The classes {@code label} names, as their full paths: the one at that path when the label is a full path
         *  (a backticked {@code demo::Person}), else every class of that simple name; empty when none. */
        List<String> classesOf(String label);

        /** Reports the property {@code name} of the class at {@code classPath} (its own, inherited, or injected by an
         *  association) to {@code sink}; reports nothing when the class has none of that name. */
        void property(String classPath, String name, PropertySink sink);

        /** Reports to {@code sink} the property at the other end of the association that injects {@code property}
         *  onto {@code classPath} (the one on the target class that navigates back); reports nothing when
         *  {@code property} is not an association end. */
        void inverse(String classPath, String property, PropertySink sink);

        /** Whether the class at {@code classPath} is the one at {@code superPath}, or a subclass of it. */
        boolean isSubclass(String classPath, String superPath);

        /** Every class of the model, by its full path, in a fixed order: what a label-less node reads. */
        List<String> classes();

        /** Reports every stored property of the class at {@code classPath} (its own and its superclasses', in
         *  declaration order; an association end is injected, not declared, and is not among them) to {@code sink}:
         *  what a node as a value carries. */
        void properties(String classPath, PropertySink sink);

        /** The label of the class at {@code classPath}: the simple name a pattern names it by. */
        String labelOf(String classPath);

        /** Reports the relationships of the class at {@code classPath} read forward, to {@code sink}: its stored
         *  properties holding a class, and the associations whose first end is its (the end a relationship type
         *  names; the inverse end is the other class's); its superclasses' too. What an untyped relationship
         *  pattern enumerates (the study's §12.17). */
        void ends(String classPath, PropertySink sink);
    }

    /** Where the model reports a property: its name, its type's name (a primitive's simple name, a class's or an
     *  enumeration's full path), what the type is, and its multiplicity's bounds ({@code upper} -1 for unbounded). */
    public interface PropertySink {
        void property(String name, String type, boolean classTyped, boolean enumTyped, int lower, int upper);
    }

    /** A property as the translator reads it, as the model reported it ({@link PropertySink}). */
    private record Property(String name, String type, boolean classTyped, boolean enumTyped, int lower, int upper) {

        /** Whether the property may hold more than one value. */
        boolean many() {
            return upper < 0 || upper > 1;
        }

        /** Whether the property may hold no value. */
        boolean optional() {
            return lower == 0;
        }
    }

    /** The model's property {@code name} of the class at {@code classPath}; null when it has none. */
    /** The class's stored properties, as the model reports them. */
    private List<Property> properties(String classPath) {
        List<Property> out = new ArrayList<>();
        model.properties(classPath, (n, type, classTyped, enumTyped, lower, upper) ->
                out.add(new Property(n, type, classTyped, enumTyped, lower, upper)));
        return out;
    }

    /** A node carried through a projection as its columns (the study's §12.14): the column specs that compute them
     *  over the row before the projection, the columns they are after it, and the shape the node's own (virtual)
     *  column carries. */
    private record NodeCarry(NodeShape shape, List<ColSpec> specs, List<Col> columns) {
    }

    /** The node {@code v} names in {@code scope}, to carry through a projection as its columns: a pattern's node,
     *  or a node already carried; null when {@code v} names no node. */
    private @com.legend.base.Nullable NodeCarry carriedNode(CypherAst.Var v, Scope scope, Set<String> names) {
        if (scope instanceof GraphScope gs) {
            Binding b = gs.g.bindings().get(v.name());
            if (b == null || !b.node()) {
                return null;
            }
            return gs.carry(b, v.pos(), names, gs.g.variableClasses().getOrDefault(v.name(), List.of()));
        }
        if (scope instanceof RowScope rs) {
            Col c = rs.column(v.name());
            if (c == null || !(c.detail() instanceof NodeShape shape)) {
                return null;
            }
            // carried again: the same columns, kept under their names
            List<ColSpec> specs = new ArrayList<>();
            List<Col> columns = new ArrayList<>();
            Col labels = java.util.Objects.requireNonNull(rs.column(shape.labels()));
            specs.add(new ColSpec(shape.labels(), lambda("r", new AppliedProperty(new Variable("r"), shape.labels())),
                    null, null));
            columns.add(new Col(shape.labels(), Kind.STRING, labels.nullable()));
            for (NodeProp p : shape.props().values()) {
                specs.add(new ColSpec(p.column(), lambda("r", new AppliedProperty(new Variable("r"), p.column())),
                        null, null));
                columns.add(new Col(p.column(), p.kind(), p.nullable()));
            }
            return new NodeCarry(shape, specs, columns);
        }
        return null;
    }

    /** A node as a value (C8, the study's §12.14): the JSON the judge reads as a node,
     *  {@code {"@labels":[...],"<property>":<value>,...}}, each property present when it holds a value (Neo4j stores
     *  no null), built as text by the database and read as a Variant. {@code labels} is the labels array's JSON
     *  text; each property's value is spelled by its kind. */
    private static Tx nodeJson(NodeParts parts, Pos pos) {
        ValueSpecification text = plus(new CString("{\"@labels\":"), parts.labels());
        text = plus(text, propertyPieces(parts, pos), new CString("}"));
        return new Tx(present(parts, call("fromJson", text)), Kind.JSON, parts.presence() != null, null,
                new NodeValue(), null, null);
    }

    /** A node's parts: its labels' JSON array text (a constant, or the column carrying it), its properties, and,
     *  for an OPTIONAL MATCH's node, what its presence is read from (a navigation, or the labels column: empty
     *  when the hop found none, the node then null). */
    private record NodeParts(ValueSpecification labels, List<Map.Entry<String, Tx>> props,
            @com.legend.base.Nullable ValueSpecification presence) {
        NodeParts(ValueSpecification labels, List<Map.Entry<String, Tx>> props) {
            this(labels, props, null);
        }
    }

    /** {@code value} where the node is present, null where its hop found none. */
    private static ValueSpecification present(NodeParts parts, ValueSpecification value) {
        ValueSpecification presence = parts.presence();
        if (presence == null) {
            return value;
        }
        return call("if", call("isEmpty", presence), thunk(SqlToPure.nullOf(Kind.JSON).vs()), thunk(value));
    }

    /** {@code labels(n)}: the node's labels, a list. */
    private static Tx nodeLabels(NodeParts parts) {
        return Tx.value(present(parts, call("fromJson", parts.labels())), Kind.JSON, parts.presence() != null);
    }

    /** {@code properties(n)}: the node's properties that hold a value, a map (the pieces each lead with a comma: the
     *  first is dropped). */
    private static Tx nodeProperties(NodeParts parts, Pos pos) {
        ValueSpecification text = plus(new CString("{"), call("substring", propertyPieces(parts, pos),
                new CInteger(2L)), new CString("}"));
        return Tx.value(present(parts, call("fromJson", text)), Kind.JSON, parts.presence() != null);
    }

    /** The JSON text of a node's properties, each as {@code ,"name":value} when it holds a value (Neo4j stores no
     *  null); empty when it holds none. */
    private static ValueSpecification propertyPieces(NodeParts parts, Pos pos) {
        ValueSpecification text = null;
        for (Map.Entry<String, Tx> p : parts.props()) {
            Tx t = p.getValue();
            if (t.kind() == Kind.NULL) {
                continue;
            }
            ValueSpecification v = SqlToPure.valueOf(t);
            ValueSpecification key = new CString("," + com.legend.json.Json.toCompact(p.getKey()) + ":");
            ValueSpecification piece = t.nullable()
                    ? call("if", call("isEmpty", v), thunk(new CString("")),
                            thunk(plus(key, jsonText(call("toOne", v), t.kind(), p.getKey(), pos))))
                    : plus(key, jsonText(v, t.kind(), p.getKey(), pos));
            text = text == null ? piece : plus(text, piece);
        }
        return text == null ? new CString("") : text;
    }

    /** A property value's JSON text, as the database spells it: a number's or a boolean's spelling (a whole float
     *  with its {@code .0}, which Postgres's text drops), a string in quotes with its quotes and backslashes
     *  escaped. */
    private static ValueSpecification jsonText(ValueSpecification v, Kind kind, String name, Pos pos) {
        return switch (kind) {
            case INTEGER, WHOLE, BOOLEAN -> call("toString", v);
            case FLOAT, INEXACT, DECIMAL, NUMBER -> call("if", call("or", call("contains", call("toString", v),
                    new CString(".")), call("contains", call("toString", v), new CString("e"))),
                    thunk(call("toString", v)), thunk(plus(call("toString", v), new CString(".0"))));
            case STRING -> plus(new CString("\""), call("replace", call("replace", v, new CString("\\"),
                    new CString("\\\\")), new CString("\""), new CString("\\\"")), new CString("\""));
            default -> throw CypherErrors.unsupported("a node holding a " + cypherType(kind) + " property (" + name
                    + "), as a value, is not supported yet (the Variant spells it as text)", pos);
        };
    }

    private @com.legend.base.Nullable Property property(String classPath, String name) {
        Property[] found = new Property[1];
        model.property(classPath, name, (n, type, classTyped, enumTyped, lower, upper) ->
                found[0] = new Property(n, type, classTyped, enumTyped, lower, upper));
        return found[0];
    }

    /** The inverse end of the association that injects {@code propertyName} onto {@code classPath}; null when it is
     *  not an association end. */
    private @com.legend.base.Nullable Property inverse(String classPath, String propertyName) {
        Property[] found = new Property[1];
        model.inverse(classPath, propertyName, (n, type, classTyped, enumTyped, lower, upper) ->
                found[0] = new Property(n, type, classTyped, enumTyped, lower, upper));
        return found[0];
    }

    private static final String NULL_ORDER = "meta::pure::functions::relation::NullOrder";

    /** The prefix of the names the translator makes up for columns the query does not name. */
    private static final String SYNTHETIC = "_cypher_";

    private static final Set<String> AGGREGATES = Set.of("count", "sum", "avg", "min", "max", "collect",
            "stdev", "stdevp", "percentilecont", "percentiledisc");

    /** The functions the translator reads (PR2's table), each by its Cypher name in lower case (Cypher's function
     *  names are case-insensitive) and, for one the database computes under one Pure name, that name; any other is
     *  refused by name. */
    private enum Function {
        COALESCE("coalesce"), SIZE("size"), CHAR_LENGTH("char_length"), CHARACTER_LENGTH("character_length"),
        KEYS("keys"), HEAD("head"), LAST("last"), TAIL("tail"), REVERSE("reverse"), IS_EMPTY("isempty"),
        RANGE("range"), SPLIT("split"), TO_STRING("tostring"), TO_INTEGER("tointeger"), TO_FLOAT("tofloat"),
        TO_BOOLEAN("toboolean"), ABS("abs", "abs"), SIGN("sign", "sign"), SQRT("sqrt", "sqrt"), EXP("exp", "exp"),
        LOG("log", "log"), SIN("sin", "sin"), COS("cos", "cos"), TAN("tan", "tan"), ASIN("asin", "asin"),
        ACOS("acos", "acos"), ATAN("atan", "atan"), CEIL("ceil", "ceiling"), FLOOR("floor", "floor"),
        ROUND("round", "round"), TO_LOWER("tolower", "toLower"), TO_UPPER("toupper", "toUpper"), TRIM("trim", "trim"),
        LTRIM("ltrim", "ltrim"), RTRIM("rtrim", "rtrim"), LEFT("left", "left"), RIGHT("right", "right"),
        REPLACE("replace", "replace"), SUBSTRING("substring"), RAND("rand"), RANDOM_UUID("randomuuid"),
        TIMESTAMP("timestamp");

        final String cypher;
        final @com.legend.base.Nullable String pure;

        Function(String cypher) {
            this(cypher, null);
        }

        Function(String cypher, @com.legend.base.Nullable String pure) {
            this.cypher = cypher;
            this.pure = pure;
        }

    }

    /** The function table by Cypher name (immutable, in declaration order: ArchitectureTest's invariant 3,
     *  NoSaltedIterationOrderTest). */
    private static final Map<String, Function> FUNCTIONS_BY_NAME = functionsByName();

    private static Map<String, Function> functionsByName() {
        Map<String, Function> byName = new LinkedHashMap<>();
        for (Function f : Function.values()) {
            byName.put(f.cypher, f);
        }
        return java.util.Collections.unmodifiableMap(byName);
    }

    /** The four list predicates, by their Cypher name in lower case. */
    private enum Quantifier { ALL, ANY, NONE, SINGLE }

    private static final Map<String, Quantifier> QUANTIFIERS = Map.of("all", Quantifier.ALL, "any", Quantifier.ANY,
            "none", Quantifier.NONE, "single", Quantifier.SINGLE);

    private final Model model;
    private int fresh;

    /** How many lambdas the database computes (a comprehension's or predicate's over a collection it computes)
     *  the expression being read is inside: {@code %} and integer {@code /} are refused in one by name (lite's
     *  Postgres lowering types {@code rem}'s divide-by-zero guard as text inside an array lambda, 2026-10-15). */
    private int databaseLambdas;

    /** The relationship types the MATCH clause being read has hops of: Neo4j binds two relationship slots of one
     *  MATCH to different relationships (relationship uniqueness), which needs identity (C5, PR3); until then a
     *  second hop of a type already hopped in the same MATCH is refused by name. */
    private final Set<String> typesInThisMatch = new HashSet<>();

    private CypherToPure(Model model) {
        this.model = model;
    }

    /** The Pure Relation expression {@code cypher} means over {@code model}'s classes; a refusal is a
     *  {@link com.legend.protocol.sql.SqlTranslationException} of the kind the seam maps. */
    public static ValueSpecification translate(String cypher, Model model) {
        CypherAst.Statement statement = CypherParser.parse(cypher);
        return new CypherToPure(model).statement(statement);
    }

    // ------------------------------------------------------------------ the pipeline of clauses

    /** The rows in hand between clauses: a graph (a MATCH's bindings over the root class, with its filters, not yet
     *  projected) or a relation (projected columns). */
    private sealed interface State permits Graph, Rows, Arms {
    }

    /** A label-less pattern's arms: one graph per class of the model, each with the same bindings and its own
     *  conditions; projected to the same columns and concatenated at the first projection (the study's §12.13). */
    private record Arms(List<Graph> arms) implements State {
    }

    /** A pattern's bindings over its root class: each variable's navigation from the root instance, and the
     *  conditions the rows must satisfy. {@code row} is the lambda parameter that names the root instance. */
    private record Graph(String rootClass, String row, Map<String, Binding> bindings,
            List<ValueSpecification> conditions, List<String> candidates, boolean fanning,
            List<NodeConstraint> deferredConstraints, List<Expr> deferredWhere,
            Map<String, List<String>> variableClasses) implements State {
        Graph(String rootClass, String row, Map<String, Binding> bindings, List<ValueSpecification> conditions,
                List<String> candidates) {
            this(rootClass, row, bindings, conditions, candidates, false, new ArrayList<>(), new ArrayList<>(),
                    Map.of());
        }

        Graph(String rootClass, String row, Map<String, Binding> bindings, List<ValueSpecification> conditions,
                List<String> candidates, boolean fanning, List<NodeConstraint> deferredConstraints,
                List<Expr> deferredWhere) {
            this(rootClass, row, bindings, conditions, candidates, fanning, deferredConstraints, deferredWhere,
                    Map.of());
        }
    }

    /** A far node's property map in a fanning graph, read over the materialized rows (the study's §12.16). */
    private record NodeConstraint(String variable, Map<String, Expr> properties, Pos pos) {
    }

    /** A pattern variable: a node (its class, the properties navigated from the root to reach it, whether that
     *  navigation may find nothing, whether it fans out: a to-many hop on its path, one row per node reached) or a
     *  relationship (a hop, with no value of its own in PR1). */
    private record Binding(boolean node, @com.legend.base.Nullable String classPath, List<String> path,
            boolean optional, boolean fanning, boolean absent, boolean maybeAbsent) {
        Binding(boolean node, @com.legend.base.Nullable String classPath, List<String> path, boolean optional) {
            this(node, classPath, path, optional, false, false, false);
        }

        Binding(boolean node, @com.legend.base.Nullable String classPath, List<String> path, boolean optional,
                boolean fanning) {
            this(node, classPath, path, optional, fanning, false, false);
        }

        /** A node an OPTIONAL MATCH never finds (its relationship's type names nothing of the model, or a label
         *  the hop does not reach): null everywhere (the study's §12.18). */
        static Binding absentNode() {
            return new Binding(true, null, List.of(), true, false, true, true);
        }

        /** An OPTIONAL MATCH's node: present where its hop finds one, null where none ({@code maybeAbsent}; a
         *  MATCH's optional hop is never absent, its isNotEmpty condition being the graph's). */
        static Binding optionalNode(String classPath, List<String> path, boolean fanning) {
            return new Binding(true, classPath, path, true, fanning, false, true);
        }
    }

    /** A relation: its Pure expression and its columns, in order. A column may be CARRIED: a list or map of known
     *  shape a WITH named, every leaf of it a literal, kept as its shape for the clauses after the WITH to read rather
     *  than computed by the database; it is no column of the relation. */
    private record Rows(ValueSpecification vs, List<Col> columns) implements State {
    }

    private record Col(String name, Kind kind, boolean nullable, @com.legend.base.Nullable Val carried,
            com.legend.protocol.sql.InputDialect.@com.legend.base.Nullable TypeDetail detail) {
        Col(String name, Kind kind, boolean nullable) {
            this(name, kind, nullable, null, null);
        }

        Col(String name, Kind kind, boolean nullable, @com.legend.base.Nullable Val carried) {
            this(name, kind, nullable, carried, null);
        }

        /** Whether no column of the relation bears this name: a carried shape, or a node carried as its columns. */
        boolean virtual() {
            return carried != null || detail instanceof NodeShape;
        }
    }

    private ValueSpecification statement(CypherAst.Statement s) {
        State state = null;
        List<Clause> clauses = s.clauses();
        for (Clause c : clauses) {
            if (c instanceof CypherAst.OtherClause o) {
                // refused by name first: a UNION after a RETURN is a UNION, not a misplaced RETURN
                throw CypherErrors.unsupported(o.keyword() + " is not supported yet", o.pos());
            }
        }
        for (int i = 0; i < clauses.size(); i++) {
            Clause c = clauses.get(i);
            boolean last = i == clauses.size() - 1;
            switch (c) {
                case CypherAst.Use u -> throw CypherErrors.unsupported("USE (selecting the graph by its data space)"
                        + " is not supported yet: name the mapping and runtime with ->from(...) after the island",
                        u.pos());
                case CypherAst.OtherClause o -> throw CypherErrors.unsupported(o.keyword() + " is not supported yet",
                        o.pos());
                case CypherAst.Match m -> state = match(m, state);
                case CypherAst.Unwind u -> state = unwind(u, state);
                case CypherAst.With w -> state = with(w, state);
                case CypherAst.Return r -> {
                    if (!last) {
                        throw CypherErrors.invalid("RETURN can only be used at the end of the query", r.pos());
                    }
                    state = returnClause(r, state);
                }
            }
        }
        if (!(state instanceof Rows rows)) {
            Clause end = clauses.get(clauses.size() - 1);
            throw CypherErrors.invalid("Query cannot conclude with " + keyword(end) + " (must be a RETURN clause, a"
                    + " FINISH clause, an update clause, a unit subquery call, or a procedure call with no YIELD)",
                    end.pos());
        }
        return rows.vs();
    }

    private static String keyword(Clause c) {
        return switch (c) {
            case CypherAst.Match m -> m.optional() ? "OPTIONAL MATCH" : "MATCH";
            case CypherAst.With w -> "WITH";
            case CypherAst.Unwind u -> "UNWIND";
            case CypherAst.Return r -> "RETURN";
            case CypherAst.Use u -> "USE";
            case CypherAst.OtherClause o -> o.keyword();
        };
    }

    // ------------------------------------------------------------------ MATCH

    private State match(CypherAst.Match m, @com.legend.base.Nullable State state) {
        if (m.optional()) {
            return optionalMatch(m, state);
        }
        if (state instanceof Rows) {
            throw CypherErrors.unsupported("a MATCH after a WITH that projected its variables is not supported yet",
                    m.pos());
        }
        if (state instanceof Arms) {
            throw CypherErrors.unsupported("a MATCH after a label-less pattern (every class of the model) is not"
                    + " supported yet", m.pos());
        }
        if (state == null && m.patterns().size() == 1 && m.patterns().get(0).elements().size() == 1
                && m.patterns().get(0).variable() == null
                && ((NodePattern) m.patterns().get(0).elements().get(0)).labels().isEmpty()) {
            // a label-less node alone: a node of any class of the model, one arm per class
            return arms(m);
        }
        if (m.patterns().stream().anyMatch(p -> p.elements().stream().anyMatch(e -> e instanceof RelPattern r
                && r.types().isEmpty()))) {
            return untypedArms(m, state);
        }
        Graph g = state instanceof Graph existing ? existing : null;
        typesInThisMatch.clear();
        for (PathPattern p : m.patterns()) {
            if (p.variable() != null) {
                throw CypherErrors.unsupported("a named path (" + p.variable() + " = ...) is not supported yet",
                        p.pos());
            }
            g = pattern(p, g);
        }
        Graph graph = java.util.Objects.requireNonNull(g);
        Expr where = m.where();
        if (where != null) {
            if (graph.fanning()) {
                graph.deferredWhere().add(where);
            } else {
                graph.conditions().add(condition(where, new GraphScope(graph), "WHERE"));
            }
        }
        return graph.fanning() ? materialize(graph, m.pos(), List.of()) : graph;
    }

    /** {@code MATCH (a)-->(b)}, {@code (a)-[r]-(b)}: an untyped relationship is one arm per relationship the
     *  model declares (the study's §12.17) -- from the node before it, read forward, each end of its class; read
     *  backward, each end of another class reaching it; both for an undirected pattern -- the pattern rewritten
     *  with that type, that direction and the classes it decides as labels, each arm read as a typed pattern is,
     *  materialized to rows (every node carried as the union of its classes' properties, the same columns in every
     *  arm) and the arms concatenated. */
    private State untypedArms(CypherAst.Match m, @com.legend.base.Nullable State state) {
        if (state != null) {
            throw CypherErrors.unsupported("an untyped relationship pattern after a bound pattern is not supported"
                    + " yet", m.pos());
        }
        if (m.patterns().size() != 1) {
            throw CypherErrors.unsupported("an untyped relationship in a MATCH of several patterns is not supported"
                    + " yet", m.pos());
        }
        PathPattern p = m.patterns().get(0);
        if (p.variable() != null) {
            throw CypherErrors.unsupported("a named path (" + p.variable() + " = ...) is not supported yet", p.pos());
        }
        // every node named before the arms are read (an unnamed one synthetically), so the arms' columns agree
        List<PatternElement> elements = new ArrayList<>(p.elements());
        List<String> order = new ArrayList<>();
        Set<String> taken = new HashSet<>();
        for (PatternElement e : elements) {
            if (e instanceof NodePattern n && n.variable() != null) {
                taken.add(n.variable());
            }
        }
        for (int i = 0; i < elements.size(); i += 2) {
            NodePattern n = (NodePattern) elements.get(i);
            if (n.variable() == null) {
                n = new NodePattern(freshName(taken), n.labels(), n.properties(), n.pos());
                elements.set(i, n);
            }
            order.add(java.util.Objects.requireNonNull(n.variable()));
        }
        NodePattern root = (NodePattern) elements.get(0);
        String rootLabel = labelClass(root);
        List<String> rootClasses = rootLabel != null ? List.of(rootLabel) : new ArrayList<>(model.classes());
        rootClasses = new ArrayList<>(rootClasses);
        rootClasses.removeAll(durationClasses());
        List<List<PatternElement>> arms = new ArrayList<>();
        for (String cls : rootClasses) {
            List<PatternElement> head = new ArrayList<>();
            head.add(new NodePattern(root.variable(), List.of(cls), root.properties(), root.pos()));
            assign(elements, 2, cls, head, arms);
        }
        if (arms.isEmpty()) {
            throw CypherErrors.unsupported("an untyped relationship pattern the model declares no relationship for"
                    + " (no rows) is not supported yet", p.pos());
        }
        // every variable's classes across the arms: what its carried columns are the union of
        Map<String, List<String>> variableClasses = new LinkedHashMap<>();
        for (List<PatternElement> arm : arms) {
            for (PatternElement e : arm) {
                if (e instanceof NodePattern n && n.variable() != null) {
                    List<String> classes = variableClasses.computeIfAbsent(n.variable(), k -> new ArrayList<>());
                    if (!classes.contains(n.labels().get(0))) {
                        classes.add(n.labels().get(0));
                    }
                }
            }
        }
        List<String> everyClass = new ArrayList<>();
        variableClasses.values().forEach(cs -> cs.forEach(c -> {
            if (!everyClass.contains(c)) {
                everyClass.add(c);
            }
        }));
        List<Graph> graphs = new ArrayList<>();
        for (List<PatternElement> arm : arms) {
            typesInThisMatch.clear();
            Graph g = pattern(new PathPattern(null, arm, p.pos()), null);
            // (the WHERE read against the arm's own classes; C4 lenient across every class an arm reads)
            g = new Graph(g.rootClass(), g.row(), g.bindings(), g.conditions(), everyClass, g.fanning(),
                    g.deferredConstraints(), g.deferredWhere(), variableClasses);
            Expr where = m.where();
            if (where != null) {
                if (g.fanning()) {
                    g.deferredWhere().add(where);
                } else {
                    g.conditions().add(condition(where, new GraphScope(g), "WHERE"));
                }
            }
            graphs.add(g);
        }
        return materializeArms(graphs, order, m.pos());
    }

    /** The arms from {@code at} on: the hop before {@code at} typed each way the model allows from {@code cls}, the
     *  node at {@code at} labelled with the class that way reaches (its own label must agree), and on. */
    private void assign(List<PatternElement> elements, int at, String cls, List<PatternElement> head,
            List<List<PatternElement>> arms) {
        if (at >= elements.size()) {
            arms.add(List.copyOf(head));
            return;
        }
        RelPattern rel = (RelPattern) elements.get(at - 1);
        NodePattern node = (NodePattern) elements.get(at);
        String label = labelClass(node);
        // (type, direction, the class reached)
        List<Object[]> ways = new ArrayList<>();
        if (!rel.types().isEmpty()) {
            for (String type : rel.types()) {
                if (rel.direction() != CypherAst.Direction.IN) {
                    Property forward = property(cls, type);
                    if (forward != null && forward.classTyped()) {
                        ways.add(new Object[] {type, CypherAst.Direction.OUT, forward.type()});
                    }
                }
                if (rel.direction() != CypherAst.Direction.OUT) {
                    for (String other : model.classes()) {
                        Property back = property(other, type);
                        if (back != null && back.classTyped() && model.isSubclass(cls, back.type())) {
                            ways.add(new Object[] {type, CypherAst.Direction.IN, other});
                        }
                    }
                }
            }
        } else {
            if (rel.direction() != CypherAst.Direction.IN) {
                for (Property end : ends(cls)) {
                    ways.add(new Object[] {end.name(), CypherAst.Direction.OUT, end.type()});
                }
            }
            if (rel.direction() != CypherAst.Direction.OUT) {
                for (String other : model.classes()) {
                    for (Property end : ends(other)) {
                        if (model.isSubclass(cls, end.type())) {
                            ways.add(new Object[] {end.name(), CypherAst.Direction.IN, other});
                        }
                    }
                }
            }
        }
        for (Object[] way : ways) {
            String reached = (String) way[2];
            if (label != null && !label.equals(reached) && !model.isSubclass(reached, label)) {
                continue;
            }
            if (rel.direction() == CypherAst.Direction.BOTH && reached.equals(cls)) {
                throw CypherErrors.unsupported("an undirected relationship over a self-association (" + way[0]
                        + " of " + cls + "; a loop matches once in Neo4j, which needs identity) is not supported"
                        + " yet", rel.pos());
            }
            List<PatternElement> next = new ArrayList<>(head);
            next.add(new RelPattern(rel.variable(), List.of((String) way[0]), (CypherAst.Direction) way[1],
                    rel.properties(), rel.lengthRange(), rel.pos()));
            next.add(new NodePattern(node.variable(), List.of(reached), node.properties(), node.pos()));
            assign(elements, at + 2, reached, next, arms);
        }
    }

    /** The class's relationships read forward, as the model reports them. */
    private List<Property> ends(String classPath) {
        List<Property> out = new ArrayList<>();
        model.ends(classPath, (n, type, classTyped, enumTyped, lower, upper) ->
                out.add(new Property(n, type, classTyped, enumTyped, lower, upper)));
        return out;
    }

    /** OPTIONAL MATCH (the study's §12.18): the pattern extends the graph in hand from a bound node, each hop
     *  optional -- no row is lost: a node the hop finds none of is null, its properties null; a to-many hop fans out
     *  as §12.16's and the arms are read as rows (lite's outer join keeps one row for a root with no leaf); a type the
     *  model declares nowhere, or a label the hop does not reach, binds an absent node (null everywhere). As the
     *  first clause, a MATCH (the model has classes, so there are rows). */
    private State optionalMatch(CypherAst.Match m, @com.legend.base.Nullable State state) {
        if (state == null) {
            return match(new CypherAst.Match(false, m.patterns(), m.where(), m.pos()), null);
        }
        if (m.where() != null) {
            throw CypherErrors.unsupported("OPTIONAL MATCH with a WHERE (the WHERE is the optional pattern's, not the"
                    + " rows') is not supported yet", m.pos());
        }
        if (state instanceof Rows) {
            throw CypherErrors.unsupported("OPTIONAL MATCH after a WITH that projected its variables, or after a"
                    + " pattern read as rows, is not supported yet", m.pos());
        }
        if (m.patterns().size() != 1) {
            throw CypherErrors.unsupported("OPTIONAL MATCH of several patterns is not supported yet", m.pos());
        }
        PathPattern p = m.patterns().get(0);
        if (p.variable() != null) {
            throw CypherErrors.unsupported("a named path (" + p.variable() + " = ...) is not supported yet", p.pos());
        }
        List<PatternElement> elements = new ArrayList<>(p.elements());
        if (elements.size() == 1) {
            throw CypherErrors.unsupported("an OPTIONAL MATCH of a single node (a cartesian product) is not"
                    + " supported yet", p.pos());
        }
        for (PatternElement e : elements) {
            if (e instanceof RelPattern r) {
                if (r.lengthRange() != null) {
                    throw CypherErrors.unsupported("a variable-length relationship (*) is not supported yet",
                            r.pos());
                }
                if (r.types().isEmpty()) {
                    throw CypherErrors.unsupported("an untyped relationship in an OPTIONAL MATCH is not supported"
                            + " yet", r.pos());
                }
                if (r.variable() != null) {
                    throw CypherErrors.unsupported("a relationship variable (" + r.variable() + ") in an OPTIONAL"
                            + " MATCH (a relationship as a value) is not supported yet", r.pos());
                }
                if (!r.properties().isEmpty()) {
                    throw CypherErrors.unsupported("properties on a relationship pattern are not supported yet (an"
                            + " edge class)", r.pos());
                }
            }
        }
        // every node named (an unnamed one synthetically), the same names in every arm
        List<Graph> arms = state instanceof Arms a ? a.arms() : List.of((Graph) state);
        Set<String> taken = new HashSet<>(arms.get(0).bindings().keySet());
        for (PatternElement e : elements) {
            if (e instanceof NodePattern n && n.variable() != null) {
                taken.add(n.variable());
            }
        }
        for (int i = 2; i < elements.size(); i += 2) {
            NodePattern n = (NodePattern) elements.get(i);
            if (!n.properties().isEmpty()) {
                throw CypherErrors.unsupported("a property map on an optionally matched node (a filter of the"
                        + " optional pattern) is not supported yet", n.pos());
            }
            if (n.variable() == null) {
                elements.set(i, new NodePattern(freshName(taken), n.labels(), n.properties(), n.pos()));
            }
        }
        boolean fanning = false;
        for (Graph g : arms) {
            fanning |= optionalExtend(elements, g);
        }
        if (!fanning) {
            // the graphs read on: an optional node reads null where its hop finds none
            return state;
        }
        return materializeArms(arms, new ArrayList<>(arms.get(0).bindings().keySet()), m.pos());
    }

    /** The optional hops of {@code elements} bound onto {@code g} from its first node (bound already): whether any
     *  fans out. */
    private boolean optionalExtend(List<PatternElement> elements, Graph g) {
        NodePattern first = (NodePattern) elements.get(0);
        String firstVar = first.variable();
        if (firstVar == null || !g.bindings().containsKey(firstVar)) {
            throw CypherErrors.unsupported("an OPTIONAL MATCH whose pattern does not start at a bound node is not"
                    + " supported yet", first.pos());
        }
        if (!first.properties().isEmpty()) {
            throw CypherErrors.unsupported("a property map on the bound node of an OPTIONAL MATCH (a filter of the"
                    + " optional pattern) is not supported yet", first.pos());
        }
        typesInThisMatch.clear();
        Binding current = java.util.Objects.requireNonNull(g.bindings().get(firstVar));
        if (!current.node()) {
            throw CypherErrors.unsupported("an OPTIONAL MATCH from a relationship variable is not supported yet",
                    first.pos());
        }
        boolean fanning = false;
        int at = 0;
        while (at + 2 < elements.size()) {
            RelPattern rel = (RelPattern) elements.get(at + 1);
            NodePattern next = (NodePattern) elements.get(at + 2);
            String nextVar = java.util.Objects.requireNonNull(next.variable());
            if (g.bindings().containsKey(nextVar)) {
                throw CypherErrors.unsupported("a pattern whose two ends are both bound already (a cycle, compared"
                        + " by identity) is not supported yet", next.pos());
            }
            Binding target;
            if (current.absent()) {
                target = Binding.absentNode();
            } else {
                String cls = java.util.Objects.requireNonNull(current.classPath());
                Hop hop = optionalHop(cls, rel, next);
                String labelled = labelClass(next);
                if (hop == null || labelled != null && !labelled.equals(hop.target())
                        && !model.isSubclass(hop.target(), labelled)) {
                    target = Binding.absentNode();
                } else {
                    if (!typesInThisMatch.add(rel.types().get(0))) {
                        throw CypherErrors.unsupported("two relationships of the type " + rel.types().get(0)
                                + " in one MATCH (Neo4j binds them to different relationships: relationship"
                                + " uniqueness) are not supported yet", rel.pos());
                    }
                    List<String> path = new ArrayList<>(current.path());
                    path.add(hop.property());
                    fanning |= hop.many();
                    target = Binding.optionalNode(hop.target(), List.copyOf(path), current.fanning() || hop.many());
                }
            }
            bind(g, nextVar, target, next.pos());
            current = target;
            at += 2;
        }
        return fanning;
    }

    /** The hop an OPTIONAL MATCH's relationship pattern spells from {@code from}, or null when its type names no
     *  property of the model on either end (an absent node, not an error: the optional pattern never matches). */
    private @com.legend.base.Nullable Hop optionalHop(String from, RelPattern rel, NodePattern to) {
        if (rel.types().size() > 1) {
            throw CypherErrors.unsupported("a relationship pattern with several types (:A|B) is not supported yet",
                    rel.pos());
        }
        String type = rel.types().get(0);
        String toClass = labelClass(to);
        Property forward = rel.direction() == CypherAst.Direction.IN ? null : property(from, type);
        Property backward = rel.direction() == CypherAst.Direction.OUT || toClass == null ? null
                : property(toClass, type);
        boolean known = forward != null && forward.classTyped() || backward != null && backward.classTyped();
        if (!known) {
            if (rel.direction() != CypherAst.Direction.OUT && toClass == null) {
                throw CypherErrors.unsupported("an incoming relationship to a label-less node in an OPTIONAL MATCH is"
                        + " not supported yet", rel.pos());
            }
            return null;
        }
        return hop(from, rel, to, true, true);
    }

    /** Arms read as rows and concatenated: every node carried as the union of its classes' properties across the
     *  arms (the same fresh names in every arm, so the columns agree), each arm's deferred conditions its own. */
    private Rows materializeArms(List<Graph> arms, List<String> order, Pos pos) {
        Map<String, List<String>> variableClasses = new LinkedHashMap<>();
        for (Graph arm : arms) {
            for (Map.Entry<String, Binding> b : arm.bindings().entrySet()) {
                String cls = b.getValue().classPath();
                if (!b.getValue().node() || cls == null) {
                    continue;
                }
                List<String> classes = variableClasses.computeIfAbsent(b.getKey(), k -> new ArrayList<>());
                if (!classes.contains(cls)) {
                    classes.add(cls);
                }
            }
        }
        List<String> everyClass = new ArrayList<>();
        variableClasses.values().forEach(cs -> cs.forEach(c -> {
            if (!everyClass.contains(c)) {
                everyClass.add(c);
            }
        }));
        int mark = fresh;
        Rows out = null;
        for (Graph arm : arms) {
            fresh = mark;
            Graph g = new Graph(arm.rootClass(), arm.row(), arm.bindings(), arm.conditions(), everyClass,
                    arm.fanning(), arm.deferredConstraints(), arm.deferredWhere(), variableClasses);
            Rows rows = materialize(g, pos, order);
            out = out == null ? rows : new Rows(call("concatenate", out.vs(), rows.vs()), out.columns());
        }
        return java.util.Objects.requireNonNull(out);
    }

    /** A fanning graph as rows (the study's §12.16): every node carried as its columns, one row per match (a
     *  to-many navigation multiplies the rows; a fanning node's labels column navigates its path, so a node without
     *  a property still counts), then the deferred conditions -- the far nodes' property maps, the WHERE -- filtered
     *  over the rows, where a far node's property is a typed column. */
    private Rows materialize(Graph g, Pos pos, List<String> order) {
        List<Item> items = new ArrayList<>();
        List<String> names = new ArrayList<>(order);
        g.bindings().keySet().forEach(n -> {
            if (!names.contains(n)) {
                names.add(n);
            }
        });
        for (String name : names) {
            Binding b = g.bindings().get(name);
            if (b == null) {
                continue;
            }
            if (!b.node()) {
                throw CypherErrors.unsupported("a relationship variable (" + name + ") in a pattern read as rows (a"
                        + " relationship as a value) is not supported yet", pos);
            }
            items.add(new Item(new CypherAst.Var(name, pos), name, name, pos));
        }
        Rows rows = project(items, false, List.of(), null, null, null, g, pos, false);
        RowScope scope = new RowScope(rows);
        ValueSpecification cond = null;
        for (NodeConstraint c : g.deferredConstraints()) {
            for (Map.Entry<String, Expr> e : c.properties().entrySet()) {
                Tx value = scalarOf(scope.property(new CypherAst.Prop(new CypherAst.Var(c.variable(), c.pos()),
                        e.getKey(), c.pos())), c.pos());
                Tx wanted = tx(e.getValue(), scope);
                ValueSpecification x = SqlToPure.truth(equal(value, wanted, e.getValue().pos()), "a property map",
                        c.pos()).vs();
                cond = cond == null ? x : call("and", cond, x);
            }
        }
        for (Expr w : g.deferredWhere()) {
            ValueSpecification x = condition(w, scope, "WHERE");
            cond = cond == null ? x : call("and", cond, x);
        }
        if (cond == null) {
            return rows;
        }
        return new Rows(call("filter", rows.vs(), lambda(scope.row(), cond)), rows.columns());
    }

    /** {@code MATCH (n)}, {@code MATCH (n {p: v}) WHERE ...}: a node of any class of the model (the study's §12.13),
     *  one arm per class, each the graph of that class with the pattern's constraints and the WHERE read against it
     *  (a property the class lacks reads as null where another class declares it: Neo4j's heterogeneous nodes). */
    private Arms arms(CypherAst.Match m) {
        NodePattern root = (NodePattern) m.patterns().get(0).elements().get(0);
        // every class of the model but the duration value's (a value the island carries, never a node)
        List<String> classes = new ArrayList<>(model.classes());
        classes.removeAll(durationClasses());
        if (classes.isEmpty()) {
            throw CypherErrors.invalid("the model declares no class (the graph is the model)", root.pos());
        }
        List<Graph> out = new ArrayList<>();
        String row = rowName(root.variable());
        for (String cls : classes) {
            Graph g = new Graph(cls, row, new LinkedHashMap<>(), new ArrayList<>(), classes);
            Binding rootBinding = new Binding(true, cls, List.of(), false);
            bind(g, root.variable(), rootBinding, root.pos());
            nodeConstraints(root, rootBinding, g);
            Expr where = m.where();
            if (where != null) {
                g.conditions().add(condition(where, new GraphScope(g), "WHERE"));
            }
            out.add(g);
        }
        return new Arms(out);
    }

    /** One path pattern read into the graph: the first pattern of a query chooses the root; a later one must start
     *  or end at a node already bound (else it is a cartesian product, which PR1 does not read). */
    private Graph pattern(PathPattern p, @com.legend.base.Nullable Graph g) {
        List<PatternElement> elements = p.elements();
        for (PatternElement e : elements) {
            if (e instanceof RelPattern r && r.lengthRange() != null) {
                throw CypherErrors.unsupported("a variable-length relationship (*) is not supported yet", r.pos());
            }
        }
        if (g == null) {
            return rooted(p);
        }
        NodePattern first = (NodePattern) elements.get(0);
        NodePattern last = (NodePattern) elements.get(elements.size() - 1);
        String firstVar = first.variable();
        String lastVar = last.variable();
        boolean firstBound = firstVar != null && g.bindings().containsKey(firstVar);
        boolean lastBound = lastVar != null && g.bindings().containsKey(lastVar);
        if (firstBound && lastBound && elements.size() > 1) {
            throw CypherErrors.unsupported("a pattern whose two ends are both bound already (a cycle, compared by"
                    + " identity) is not supported yet", p.pos());
        }
        if (!firstBound && !lastBound) {
            throw CypherErrors.unsupported("a second pattern sharing no variable with the first (a cartesian"
                    + " product) is not supported yet", p.pos());
        }
        List<PatternElement> ordered = firstBound ? elements : reversed(elements);
        NodePattern start = (NodePattern) ordered.get(0);
        Binding bound = java.util.Objects.requireNonNull(g.bindings().get(start.variable()));
        nodeConstraints(start, bound, g);
        extend(ordered, 0, bound, g);
        return g;
    }

    /** The first pattern: its root is the node from which every hop navigates to at most one node. */
    private Graph rooted(PathPattern p) {
        List<PatternElement> elements = p.elements();
        for (int rootAt = 0; rootAt < elements.size(); rootAt += 2) {
            NodePattern root = (NodePattern) elements.get(rootAt);
            String rootClass = labelClass(root);
            if (rootClass == null) {
                continue;
            }
            String row = rowName(root.variable());
            Graph g = new Graph(rootClass, row, new LinkedHashMap<>(), new ArrayList<>(), List.of());
            Binding rootBinding = new Binding(true, rootClass, List.of(), false);
            bind(g, root.variable(), rootBinding, root.pos());
            nodeConstraints(root, rootBinding, g);
            // forward from the root, then backward, each hop to-one from the node before it
            if (navigable(elements, rootAt, true) && navigable(elements, rootAt, false)) {
                extend(elements, rootAt, rootBinding, g);
                extend(reversed(elements), elements.size() - 1 - rootAt, rootBinding, g);
                return g;
            }
        }
        // no to-one root: the first node is the root and each hop goes forward, a to-many hop fanning out into one
        // row per pair; the graph is materialized to rows at the end of the MATCH (the study's §12.16)
        NodePattern root = (NodePattern) elements.get(0);
        String rootClass = labelClass(root);
        if (rootClass == null) {
            if (elements.size() == 1) {
                throw CypherErrors.unsupported("a label-less node (" + root.variable() + ") in a MATCH of several"
                        + " patterns (a cartesian product over every class) is not supported yet", root.pos());
            }
            rootClass = startClassOf((RelPattern) elements.get(1), root.pos());
        }
        String row = rowName(root.variable());
        Graph g = new Graph(rootClass, row, new LinkedHashMap<>(), new ArrayList<>(), List.of(), true,
                new ArrayList<>(), new ArrayList<>());
        Binding rootBinding = new Binding(true, rootClass, List.of(), false);
        bind(g, root.variable(), rootBinding, root.pos());
        nodeConstraints(root, rootBinding, g);
        extend(elements, 0, rootBinding, g);
        return g;
    }

    /** The class of a label-less start node, read from its relationship's type: the one class of the model
     *  declaring that type as a property holding a class (an outgoing hop; the harness's model declares a type on
     *  one class pair); refused by name when none or several declare it, or the hop is incoming. */
    private String startClassOf(RelPattern rel, Pos pos) {
        if (rel.types().size() != 1) {
            throw CypherErrors.unsupported("a relationship pattern without a type ([] or -->) from a label-less node"
                    + " is not supported yet", rel.pos());
        }
        if (rel.direction() == CypherAst.Direction.IN) {
            throw CypherErrors.unsupported("an incoming relationship from a label-less node (<-[:" + rel.types().get(0)
                    + "]-) is not supported yet", rel.pos());
        }
        String type = rel.types().get(0);
        List<String> declaring = new ArrayList<>();
        for (String cls : model.classes()) {
            Property p = property(cls, type);
            if (p != null && p.classTyped()) {
                declaring.add(cls);
            }
        }
        if (declaring.size() != 1) {
            throw CypherErrors.unsupported("a label-less start node of a relationship pattern whose type " + type
                    + " is declared on " + (declaring.isEmpty() ? "no class" : declaring.size() + " classes ("
                    + String.join(", ", declaring) + ")") + " is not supported yet", pos);
        }
        return declaring.get(0);
    }

    private static List<PatternElement> reversed(List<PatternElement> elements) {
        List<PatternElement> out = new ArrayList<>(elements);
        java.util.Collections.reverse(out);
        for (int i = 0; i < out.size(); i++) {
            if (out.get(i) instanceof RelPattern r) {
                CypherAst.Direction d = switch (r.direction()) {
                    case OUT -> CypherAst.Direction.IN;
                    case IN -> CypherAst.Direction.OUT;
                    case BOTH -> CypherAst.Direction.BOTH;
                };
                out.set(i, new RelPattern(r.variable(), r.types(), d, r.properties(), r.lengthRange(), r.pos()));
            }
        }
        return out;
    }

    /** Whether every hop from {@code from} onward ({@code forward}: to the end; else to the start) navigates to-one. */
    private boolean navigable(List<PatternElement> elements, int from, boolean forward) {
        List<PatternElement> ordered = forward ? elements : reversed(elements);
        int at = forward ? from : elements.size() - 1 - from;
        String cls = labelClass((NodePattern) ordered.get(at));
        while (at + 2 < ordered.size()) {
            RelPattern rel = (RelPattern) ordered.get(at + 1);
            NodePattern next = (NodePattern) ordered.get(at + 2);
            Hop hop = hop(java.util.Objects.requireNonNull(cls), rel, next, false, false);
            if (hop == null) {
                return false;
            }
            cls = hop.target();
            at += 2;
        }
        return true;
    }

    /** Binds the nodes after {@code at} in {@code ordered}, each navigated from the one before it. */
    private void extend(List<PatternElement> ordered, int at, Binding from, Graph g) {
        Binding current = from;
        while (at + 2 < ordered.size()) {
            RelPattern rel = (RelPattern) ordered.get(at + 1);
            NodePattern next = (NodePattern) ordered.get(at + 2);
            String cls = java.util.Objects.requireNonNull(current.classPath());
            Hop hop = java.util.Objects.requireNonNull(hop(cls, rel, next, true, g.fanning()));
            String nextVar = next.variable();
            if (g.fanning() && nextVar == null) {
                // every node of a fanning graph is carried by name when the graph is materialized
                nextVar = freshName(new HashSet<>(g.bindings().keySet()));
                next = new NodePattern(nextVar, next.labels(), next.properties(), next.pos());
            }
            if (g.fanning() && rel.variable() != null) {
                throw CypherErrors.unsupported("a relationship variable (" + rel.variable() + ") in a pattern that"
                        + " fans out is not supported yet", rel.pos());
            }
            if (nextVar != null && g.bindings().containsKey(nextVar)) {
                throw CypherErrors.unsupported("a pattern whose two ends are both bound already (a cycle, compared"
                        + " by identity) is not supported yet", next.pos());
            }
            if (!rel.properties().isEmpty()) {
                throw CypherErrors.unsupported("properties on a relationship pattern are not supported yet (an edge"
                        + " class)", rel.pos());
            }
            if (!typesInThisMatch.add(rel.types().get(0))) {
                throw CypherErrors.unsupported("two relationships of the type " + rel.types().get(0) + " in one MATCH"
                        + " (Neo4j binds them to different relationships: relationship uniqueness) are not supported"
                        + " yet", rel.pos());
            }
            List<String> path = new ArrayList<>(current.path());
            path.add(hop.property());
            boolean optional = current.optional() || hop.optional();
            boolean fanning = current.fanning() || hop.many();
            Binding target = new Binding(true, hop.target(), List.copyOf(path), optional, fanning);
            if (hop.optional() || hop.many()) {
                // the hop must find a node: a row where it finds none is not a match
                g.conditions().add(call("isNotEmpty", navigate(g.row(), path)));
            }
            bind(g, rel.variable(), new Binding(false, null, List.copyOf(path), optional, fanning), rel.pos());
            bind(g, nextVar, target, next.pos());
            nodeConstraints(next, target, g);
            current = target;
            at += 2;
        }
    }

    /** A hop: the property navigated from the node before it, the class it reaches, whether it may find nothing,
     *  and whether it may find many (a fanning hop, read only by a fanning graph). */
    private record Hop(String property, String target, boolean optional, boolean many) {
    }

    /**
     * The to-one navigation a relationship pattern between a node of class {@code from} and {@code to} spells, or
     * null when there is none: the property named by the relationship's type on {@code from} (an outgoing hop, or an
     * undirected one), or the inverse end of the property named on {@code to}'s class (an incoming hop: the node
     * before is what the property holds). When {@code refuse}, a hop that exists but fans out is refused by name
     * instead of null.
     */
    private @com.legend.base.Nullable Hop hop(String from, RelPattern rel, NodePattern to, boolean refuse,
            boolean allowMany) {
        if (rel.types().isEmpty()) {
            throw CypherErrors.unsupported("a relationship pattern without a type ([] or -->) is not supported yet:"
                    + " name the property or association end", rel.pos());
        }
        if (rel.types().size() > 1) {
            throw CypherErrors.unsupported("a relationship pattern with several types (:A|B) is not supported yet",
                    rel.pos());
        }
        String type = rel.types().get(0);
        String toClass = labelClass(to);
        Property forward = property(from, type);
        Property backward = toClass == null ? null : property(toClass, type);
        if (forward != null && !forward.classTyped()) {
            forward = null;
        }
        if (backward != null && !backward.classTyped()) {
            backward = null;
        }
        if (rel.direction() == CypherAst.Direction.OUT) {
            backward = null;
        } else if (rel.direction() == CypherAst.Direction.IN) {
            forward = null;
        }
        if (forward == null && backward == null) {
            throw CypherErrors.invalid("the relationship type " + type + " names no property or association end of "
                    + from + (toClass == null ? "" : " or " + toClass) + " holding a class"
                    + " (the graph is the model: a relationship type is a property's name)", rel.pos());
        }
        if (forward != null && backward != null) {
            throw CypherErrors.unsupported("an undirected relationship whose type names a property on both ends is"
                    + " not supported yet", rel.pos());
        }
        if (forward != null) {
            // the node before holds the node after
            String target = forward.type();
            if (toClass != null && !toClass.equals(target) && !model.isSubclass(target, toClass)) {
                throw CypherErrors.unsupported("the label " + toClass + " is not the type of " + type
                        + " (" + target + ") nor one of its superclasses: a label narrowing a property's type is"
                        + " not supported yet", to.pos());
            }
            if (forward.many()) {
                if (allowMany) {
                    return new Hop(type, target, false, true);
                }
                if (refuse) {
                    throw CypherErrors.unsupported("the relationship " + type + " holds many " + target
                            + " per " + from + ": a pattern that fans out is not supported yet",
                            rel.pos());
                }
                return null;
            }
            return new Hop(type, target, forward.optional(), false);
        }
        // the node after holds the node before, through the property on its class: navigate the inverse end
        String toCls = java.util.Objects.requireNonNull(toClass);
        Property back = java.util.Objects.requireNonNull(backward);
        if (!model.isSubclass(from, back.type())) {
            throw CypherErrors.unsupported("the label " + from + " is not the type of " + type + " ("
                    + back.type() + "): a label narrowing a property's type is not supported yet",
                    rel.pos());
        }
        Property inverse = inverse(toCls, type);
        if (inverse == null) {
            if (refuse) {
                throw CypherErrors.unsupported("the relationship " + type + " is a property of " + toCls
                        + " with no inverse (not an association end): reading it from the " + from
                        + " side is not supported yet", rel.pos());
            }
            return null;
        }
        if (inverse.many()) {
            if (allowMany) {
                return new Hop(inverse.name(), toCls, false, true);
            }
            if (refuse) {
                throw CypherErrors.unsupported("the relationship " + type + " reaches many " + toCls
                        + " per " + from + " (its inverse " + inverse.name() + " holds many): a pattern"
                        + " that fans out is not supported yet", rel.pos());
            }
            return null;
        }
        return new Hop(inverse.name(), toCls, inverse.optional(), false);
    }

    /** The class a node pattern's labels name: one label's class (PR1 reads one label); null for a label-less node. */
    private @com.legend.base.Nullable String labelClass(NodePattern n) {
        if (n.labels().isEmpty()) {
            return null;
        }
        if (n.labels().size() > 1) {
            throw CypherErrors.unsupported("a node with several labels (" + String.join(":", n.labels()) + ") is not"
                    + " supported yet", n.pos());
        }
        String label = n.labels().get(0);
        List<String> classes = model.classesOf(label);
        if (classes.isEmpty()) {
            throw CypherErrors.invalid("the label " + label + " names no class of the model (the graph is the model:"
                    + " a label is a class's name)", n.pos());
        }
        if (classes.size() > 1) {
            throw CypherErrors.invalid("the label " + label + " names " + classes.size() + " classes ("
                    + String.join(", ", classes) + "): write the class's full path in backticks", n.pos());
        }
        return classes.get(0);
    }

    private void bind(Graph g, @com.legend.base.Nullable String variable, Binding b, Pos pos) {
        if (variable == null) {
            return;
        }
        Binding existing = g.bindings().get(variable);
        if (existing != null) {
            if (existing.node() != b.node()) {
                throw CypherErrors.invalid("Type mismatch: " + variable + " defined with conflicting type", pos);
            }
            throw CypherErrors.unsupported("a variable bound twice in a pattern (" + variable + ", compared by"
                    + " identity) is not supported yet", pos);
        }
        g.bindings().put(variable, b);
    }

    /** A node pattern's constraints: its label (a bound node's must be its class) and its property map. */
    private void nodeConstraints(NodePattern n, Binding b, Graph g) {
        String cls = java.util.Objects.requireNonNull(b.classPath());
        String labelled = labelClass(n);
        if (labelled != null && !labelled.equals(cls)) {
            if (model.isSubclass(cls, labelled)) {
                // a superclass's label: every instance of the class carries it
                return;
            }
            throw CypherErrors.unsupported("the label " + labelled + " on " + cls
                    + " (a label narrowing a node's class) is not supported yet", n.pos());
        }
        if (b.fanning() && !n.properties().isEmpty()) {
            // read over the materialized rows, where the node's properties are typed columns
            g.deferredConstraints().add(new NodeConstraint(java.util.Objects.requireNonNull(n.variable()),
                    n.properties(), n.pos()));
            return;
        }
        GraphScope scope = new GraphScope(g);
        for (Map.Entry<String, Expr> e : n.properties().entrySet()) {
            Tx value = scope.propertyOf(b, e.getKey(), n.pos());
            Tx wanted = tx(e.getValue(), scope);
            g.conditions().add(SqlToPure.truth(equal(value, wanted, e.getValue().pos()), "a property map", n.pos())
                    .vs());
        }
    }

    /** The lambda parameter naming the root instance: the root node's variable when it is a plain identifier (so the
     *  Pure reads as the query does), else {@code node}. */
    private static String rowName(@com.legend.base.Nullable String variable) {
        if (variable == null || variable.isEmpty()) {
            return "node";
        }
        for (int i = 0; i < variable.length(); i++) {
            char c = variable.charAt(i);
            boolean ok = c == '_' || (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (i > 0 && c >= '0' && c <= '9');
            if (!ok) {
                return "node";
            }
        }
        return variable;
    }

    private static ValueSpecification navigate(String row, List<String> path) {
        ValueSpecification v = new Variable(row);
        for (String p : path) {
            v = new AppliedProperty(v, p);
        }
        return v;
    }

    // ------------------------------------------------------------------ WITH and RETURN

    private State with(CypherAst.With w, @com.legend.base.Nullable State state) {
        if (state == null) {
            // WITH 1 AS x ...: a query over no pattern starts from one row
            state = oneRow();
        }
        if (state instanceof Graph g && carriesGraph(w)) {
            // WITH n, m [WHERE ...]: the pattern's variables pass through, renamed where aliased
            Map<String, Binding> kept = new LinkedHashMap<>();
            for (Item item : w.items()) {
                String name = ((CypherAst.Var) java.util.Objects.requireNonNull(item.expr())).name();
                String alias = item.alias() != null ? item.alias() : name;
                if (kept.containsKey(alias)) {
                    throw CypherErrors.invalid("Multiple result columns with the same name are not supported",
                            item.pos());
                }
                Binding b = g.bindings().get(name);
                if (b == null) {
                    throw CypherErrors.invalid("Variable `" + name + "` not defined", item.pos());
                }
                kept.put(alias, b);
            }
            Graph next = new Graph(g.rootClass(), g.row(), kept, g.conditions(), g.candidates());
            Expr where = w.where();
            if (where != null) {
                next.conditions().add(condition(where, new GraphScope(next), "WHERE"));
            }
            return next;
        }
        if (state instanceof Arms a && carriesGraph(w)) {
            // each arm carried as one graph is
            List<Graph> carried = new ArrayList<>();
            for (Graph arm : a.arms()) {
                carried.add((Graph) with(w, arm));
            }
            return new Arms(carried);
        }
        return project(w.items(), w.distinct(), w.orderBy(), w.skip(), w.limit(), w.where(), state, w.pos(), false);
    }

    /** Whether a WITH over a graph keeps it a graph: every item a pattern variable, nothing else to do. */
    private boolean carriesGraph(CypherAst.With w) {
        if (w.distinct() || !w.orderBy().isEmpty() || w.skip() != null || w.limit() != null) {
            return false;
        }
        for (Item item : w.items()) {
            if (!(item.expr() instanceof CypherAst.Var)) {
                return false;
            }
        }
        return true;
    }

    private State returnClause(CypherAst.Return r, @com.legend.base.Nullable State state) {
        return project(r.items(), r.distinct(), r.orderBy(), r.skip(), r.limit(), null, state == null ? oneRow()
                : state, r.pos(), true);
    }

    /** {@code RETURN 1 + 1}, {@code WITH 1 AS x}: a query over no pattern is one row, of no column the query can
     *  name. */
    private static Rows oneRow() {
        String one = "\n" + SYNTHETIC + "row\n1\n";
        return new Rows(com.legend.protocol.spec.TdsLiteral.of(one, "#TDS" + one + "#", null),
                List.of(new Col(SYNTHETIC + "row", Kind.INTEGER, false)));
    }

    /** A projected item: its name in the result, and its translation over the scope before the projection (a
     *  non-aggregating item), or its aggregate. */
    private record Output(String name, @com.legend.base.Nullable Val value, @com.legend.base.Nullable Aggregate agg) {
    }

    /** An aggregate: the column its argument is computed into before the grouping, the reduce over the group's
     *  values, what its result is, and a finish computed after the grouping (a sum of nothing is 0). */
    private record Aggregate(String argColumn, Tx arg, LambdaFunction reduce, Kind kind, boolean nullable,
            boolean finishWithZero) {
    }

    /**
     * A WITH's or RETURN's projection: the items computed over the state in hand (a graph's root instance, or a
     * relation's row), grouped by the non-aggregating items when an aggregate is among them (Cypher's implicit
     * grouping), then distinct, sorted, sliced. An item that is a list or map of known shape is, when
     * {@code returning}, the Variant the database writes (C8); named by a WITH, it is carried as its shape when it
     * holds only literals, for the clauses after the WITH to read.
     */
    private Rows project(List<Item> items, boolean distinct, List<SortItem> orderBy,
            @com.legend.base.Nullable Expr skip, @com.legend.base.Nullable Expr limit,
            @com.legend.base.Nullable Expr where, State state, Pos pos, boolean returning) {
        // RETURN *, WITH * over projected rows: every column, by name (over a pattern: nodes as values)
        List<Item> expanded = new ArrayList<>();
        for (Item item : items) {
            if (item.expr() != null) {
                expanded.add(item);
                continue;
            }
            List<String> columnNames = new ArrayList<>();
            if (state instanceof Rows rows) {
                for (Col c : rows.columns()) {
                    if (!c.name().startsWith(SYNTHETIC)) {
                        columnNames.add(c.name());
                    }
                }
            } else {
                // over a pattern: its variables, the nodes as values (the study's §12.14)
                Graph g = state instanceof Arms a ? a.arms().get(0) : (Graph) state;
                columnNames.addAll(g.bindings().keySet());
            }
            java.util.Collections.sort(columnNames);
            for (String name : columnNames) {
                expanded.add(new Item(new CypherAst.Var(name, item.pos()), name, name, item.pos()));
            }
        }
        items = expanded;
        // a label-less pattern's arms (one graph per class) are each projected to the same columns and
        // concatenated; the grouping, the sort, the SKIP and the LIMIT then read the whole (the study's §12.13)
        List<State> arms = state instanceof Arms a ? new ArrayList<>(a.arms()) : List.of(state);
        int mark = fresh;
        ArmProjection first = null;
        ValueSpecification vs = null;
        for (State arm : arms) {
            // the same fresh names in every arm, so the arms' columns agree
            fresh = mark;
            ArmProjection ap = projectArm(items, distinct, orderBy, skip, limit, where, arm, pos, returning);
            if (first == null) {
                first = ap;
                vs = ap.base();
                continue;
            }
            for (int i = 0; i < first.columns().size(); i++) {
                Col c = first.columns().get(i);
                Col d = ap.columns().get(i);
                if (c.kind() != d.kind() && c.kind() != Kind.NULL && d.kind() != Kind.NULL) {
                    throw CypherErrors.unsupported("the property " + c.name() + " typed differently across the"
                            + " classes a label-less node reads (" + cypherType(c.kind()) + " and "
                            + cypherType(d.kind()) + ") is not supported yet", pos);
                }
            }
            vs = call("concatenate", java.util.Objects.requireNonNull(vs), ap.base());
        }
        ArmProjection ap = java.util.Objects.requireNonNull(first);
        vs = java.util.Objects.requireNonNull(vs);
        List<Col> columns = ap.columns();
        List<SortKey> sortKeys = ap.sortKeys();
        List<ColSpec> hidden = ap.hidden();
        if (ap.grouped()) {
            vs = ap.keys().isEmpty() ? call("aggregate", vs, new ColSpecArray(ap.aggregates()))
                    : call("groupBy", vs, new ColSpecArray(ap.keys()), new ColSpecArray(ap.aggregates()));
            if (!ap.finishes().isEmpty()) {
                vs = call("extend", vs, new ColSpecArray(ap.finishes()));
            }
            // the result's columns in the items' order (the grouping puts the keys first), the finished sums in
            // place of their raw ones
            vs = call("select", vs, new ColSpecArray(columns.stream().map(c -> new ColSpec(c.name(), null, null,
                    null)).toList()));
        }
        if (distinct) {
            vs = call("distinct", vs);
        }
        if (where != null) {
            // a WITH's WHERE: before its sort, SKIP and LIMIT; over the projected names and, before a grouping or
            // a DISTINCT, the scope before the projection too (Cypher's: `WITH a.x AS x WHERE a.y > 1`)
            if (ap.whereColumn() != null) {
                vs = call("filter", vs, lambda("r", new AppliedProperty(new Variable("r"), ap.whereColumn())));
            } else {
                Scope after = new KeyedScope(new Rows(vs, List.copyOf(columns)), keyTexts(items, columns));
                vs = call("filter", vs, lambda(after.row(), condition(where, after, "WHERE")));
            }
        }
        if (!ap.afterGrouping().isEmpty()) {
            // the sort keys over the projected columns and the keys' expressions (an alias shadows nothing here:
            // Neo4j reads only the items)
            Scope after = new KeyedScope(new Rows(vs, List.copyOf(columns)), keyTexts(items, columns));
            List<ColSpec> computed = new ArrayList<>();
            int k = 0;
            for (SortKey sk : sortKeys) {
                if (!sk.hidden()) {
                    continue;
                }
                SortItem o = ap.afterGrouping().get(k++);
                Tx t = tx(o.expr(), after);
                computed.add(new ColSpec(sk.column(), lambda(after.row(), SqlToPure.valueOf(t)), null, null));
            }
            vs = call("extend", vs, new ColSpecArray(computed));
            hidden.addAll(computed);
        }
        if (!sortKeys.isEmpty()) {
            List<ValueSpecification> sortInfos = new ArrayList<>();
            for (SortKey k : sortKeys) {
                // Cypher orders null after every value ascending, before them descending, as Postgres does
                sortInfos.add(call(k.descending() ? "descending" : "ascending", new ColSpec(k.column(), null, null,
                        null), new EnumValue(NULL_ORDER, k.descending() ? "FIRST" : "LAST")));
            }
            vs = call("sort", vs, new PureCollection(sortInfos));
        }
        if (!hidden.isEmpty()) {
            vs = call("select", vs, new ColSpecArray(columns.stream().filter(c -> !c.virtual())
                    .map(c -> new ColSpec(c.name(), null, null, null)).toList()));
        }
        if (skip != null) {
            vs = call("drop", vs, rowCount(skip, "SKIP"));
        }
        if (limit != null) {
            vs = call("limit", vs, rowCount(limit, "LIMIT"));
        }
        return new Rows(vs, List.copyOf(columns));
    }

    /** One arm's projection: its relation before the grouping ({@code base}: the projected columns, or the keys and
     *  the aggregates' arguments), and what the grouping, the sort and the selection after need. */
    private record ArmProjection(ValueSpecification base, boolean grouped, List<Col> columns,
            List<SortKey> sortKeys, List<ColSpec> hidden, List<SortItem> afterGrouping, List<ColSpec> keys,
            List<ColSpec> aggregates, List<ColSpec> finishes, @com.legend.base.Nullable String whereColumn) {
    }

    /** The grouping keys' spelled expressions, each to the column it is projected as (the items and the columns
     *  are in the same order; an aggregating item is no key). */
    private static Map<String, String> keyTexts(List<Item> items, List<Col> columns) {
        Map<String, String> keys = new LinkedHashMap<>();
        for (int i = 0; i < items.size(); i++) {
            Expr e = items.get(i).expr();
            if (e != null && !isAggregate(e)) {
                keys.putIfAbsent(text(e), columns.get(i).name());
            }
        }
        return keys;
    }

    private ArmProjection projectArm(List<Item> items, boolean distinct, List<SortItem> orderBy,
            @com.legend.base.Nullable Expr skip, @com.legend.base.Nullable Expr limit,
            @com.legend.base.Nullable Expr where, State state, Pos pos, boolean returning) {
        Scope scope = state instanceof Graph g ? new GraphScope(g) : new RowScope((Rows) state);
        List<Output> outputs = new ArrayList<>();
        Set<String> names = new HashSet<>();
        Map<String, NodeCarry> nodes = new LinkedHashMap<>();
        boolean grouped = false;
        for (Item item : items) {
            Expr e = item.expr();
            if (e == null) {
                throw CypherErrors.unsupported("RETURN * (a node as a value) is not supported yet", item.pos());
            }
            String name = item.alias() != null ? item.alias() : item.text();
            if (!names.add(name)) {
                throw CypherErrors.invalid("Multiple result columns with the same name are not supported", item.pos());
            }
            NodeCarry node = !returning && e instanceof CypherAst.Var v ? carriedNode(v, scope, names) : null;
            if (node != null) {
                // a node through WITH: carried as its columns; its JSON only when returned or used as a value
                nodes.put(name, node);
                outputs.add(new Output(name, null, null));
                continue;
            }
            if (isAggregate(e)) {
                grouped = true;
                outputs.add(new Output(name, null, aggregate(e, scope, names)));
            } else {
                if (containsAggregate(e)) {
                    throw CypherErrors.unsupported("an expression around an aggregate (" + item.text() + ") is not"
                            + " supported yet: alias the aggregate in a WITH", item.pos());
                }
                outputs.add(new Output(name, val(e, scope), null));
            }
        }
        // each non-aggregating item's scalar (a list or map returned is its Variant), or the shape it is carried as
        Map<String, Tx> scalars = new HashMap<>();
        Map<String, Val> carried = new LinkedHashMap<>();
        for (Output out : outputs) {
            Val v = out.value();
            if (v == null) {
                continue;
            }
            if (v instanceof Scalar s) {
                scalars.put(out.name(), s.tx());
            } else if (returning) {
                scalars.put(out.name(), variant(v, pos));
            } else if (!rowFree(v)) {
                throw CypherErrors.unsupported("a list or map holding a value of the row (" + out.name() + "),"
                        + " carried through WITH, is not supported yet", pos);
            } else if (grouped) {
                throw CypherErrors.unsupported("a list or map (" + out.name() + ") among the grouping keys is not"
                        + " supported yet", pos);
            } else if (distinct) {
                throw CypherErrors.unsupported("WITH DISTINCT over a list or map (" + out.name() + ") is not"
                        + " supported yet", pos);
            } else {
                carried.put(out.name(), v);
            }
        }
        for (Output out : outputs) {
            boolean node = nodes.containsKey(out.name()) || isNode(scalars.get(out.name()));
            if (node && grouped) {
                throw CypherErrors.unsupported("a node among the grouping keys (" + out.name() + "; Neo4j groups"
                        + " nodes by identity) is not supported yet", pos);
            }
            if (node && distinct) {
                throw CypherErrors.unsupported((returning ? "RETURN" : "WITH") + " DISTINCT over a node ("
                        + out.name() + "; Neo4j tells nodes apart by identity) is not supported yet", pos);
            }
        }
        // the sort keys: an item (by its alias or its text), else an expression over the projected aliases first
        // (an alias shadows the variable it was computed from: the TCK's WithOrderBy4) and the scope before the
        // projection
        List<SortKey> sortKeys = new ArrayList<>();
        List<ColSpec> hidden = new ArrayList<>();
        Map<String, Val> projected = new HashMap<>();
        for (Output out : outputs) {
            if (out.value() != null) {
                projected.put(out.name(), out.value());
            }
        }
        Scope sortScope = new ProjectedScope(scope, projected);
        // a sort key over the projected columns after a grouping or a DISTINCT: computed after them
        List<SortItem> afterGrouping = new ArrayList<>();
        for (SortItem o : orderBy) {
            String key = outputNamed(items, o.expr(), outputs);
            if (key != null && carried.containsKey(key)) {
                throw CypherErrors.unsupported("ORDER BY a list or map (" + key + ") is not supported yet", o.pos());
            }
            if (key != null && (nodes.containsKey(key) || isNode(scalars.get(key)))) {
                throw CypherErrors.unsupported("ORDER BY a node (" + key + "; Neo4j orders nodes by identity) is not"
                        + " supported yet", o.pos());
            }
            if (key == null && (skip != null || limit != null) && o.expr() instanceof CypherAst.Prop pr
                    && pr.target() instanceof CypherAst.Var pv && nodes.containsKey(pv.name())) {
                // a carried node's property: its own column is the key (the node is carried under its name)
                NodeProp np = nodes.get(pv.name()).shape().props().get(pr.name());
                if (np == null) {
                    throw CypherErrors.invalid("the node " + pv.name() + " has no property " + pr.name() + " (the"
                            + " graph is the model: a property no class declares is an error, not null)", o.pos());
                }
                sortKeys.add(new SortKey(np.column(), o.descending(), false));
                continue;
            }
            if (key == null) {
                if (grouped && containsAggregate(o.expr())) {
                    throw CypherErrors.unsupported("an ORDER BY aggregate that is not one of the items is not"
                            + " supported yet", o.pos());
                }
                if (grouped || distinct) {
                    afterGrouping.add(o);
                    sortKeys.add(new SortKey(freshName(names), o.descending(), true));
                    continue;
                }
                if (skip == null && limit == null) {
                    // the lowering's execute path cannot render a sort key that is not an output column (Render:
                    // "render over an ordered relation whose sort key is not an output column"; reported to the
                    // database owner, IN_FLIGHT 2026-10-15); with a SKIP or LIMIT the sort is a subquery's and renders
                    throw CypherErrors.unsupported("an ORDER BY on an expression that is not a returned item, without a"
                            + " SKIP or LIMIT, is not supported yet (the lowering renders no such sort key)", o.pos());
                }
                Tx t = tx(o.expr(), sortScope);
                key = freshName(names);
                hidden.add(new ColSpec(key, lambda(scope.row(), SqlToPure.valueOf(t)), null, null));
                sortKeys.add(new SortKey(key, o.descending(), true));
            } else {
                sortKeys.add(new SortKey(key, o.descending(), false));
            }
        }
        // the WHERE of a WITH over an ungrouped projection: a hidden column, read before the sort
        String whereColumn = null;
        if (where != null && !grouped && !distinct) {
            whereColumn = freshName(names);
            hidden.add(new ColSpec(whereColumn, lambda(scope.row(), condition(where, sortScope, "WHERE")), null,
                    null));
        }
        ValueSpecification vs;
        List<Col> columns = new ArrayList<>();
        if (!grouped) {
            List<ColSpec> specs = new ArrayList<>();
            for (Output out : outputs) {
                Val shape = carried.get(out.name());
                if (shape != null) {
                    columns.add(new Col(out.name(), Kind.JSON, false, shape));
                    continue;
                }
                NodeCarry node = nodes.get(out.name());
                if (node != null) {
                    // the node's columns, then the node itself under its name (a virtual column)
                    specs.addAll(node.specs());
                    columns.addAll(node.columns());
                    columns.add(new Col(out.name(), Kind.JSON, false, null, node.shape()));
                    continue;
                }
                Tx t = java.util.Objects.requireNonNull(scalars.get(out.name()));
                specs.add(new ColSpec(out.name(), lambda(scope.row(), SqlToPure.valueOf(t)), null, null));
                // (a zoned date-time's zone rides with its column, so the clauses after read its fields in it)
                columns.add(new Col(out.name(), t.kind(), t.nullable(), null, t.detail()));
            }
            if (specs.isEmpty()) {
                // every item carried: the rows stay (one per row in hand), under a column nothing names
                String keep = freshName(names);
                specs.add(new ColSpec(keep, lambda(scope.row(), new CInteger(1L)), null, null));
                columns.add(new Col(keep, Kind.INTEGER, false));
            }
            specs.addAll(hidden);
            vs = state instanceof Graph g ? call("project", source(g), new ColSpecArray(specs))
                    : reproject((Rows) state, specs);
        } else {
            // the keys and the aggregates' arguments as columns, then the grouping
            List<ColSpec> specs = new ArrayList<>();
            List<ColSpec> keys = new ArrayList<>();
            List<ColSpec> aggregates = new ArrayList<>();
            List<ColSpec> finishes = new ArrayList<>();
            for (Output out : outputs) {
                Aggregate agg = out.agg();
                if (agg == null) {
                    Tx t = java.util.Objects.requireNonNull(scalars.get(out.name()));
                    specs.add(new ColSpec(out.name(), lambda(scope.row(), SqlToPure.valueOf(t)), null, null));
                    keys.add(new ColSpec(out.name(), null, null, null));
                    columns.add(new Col(out.name(), t.kind(), t.nullable(), null, t.detail()));
                } else {
                    specs.add(new ColSpec(agg.argColumn(), lambda(scope.row(), SqlToPure.valueOf(agg.arg())), null,
                            null));
                    String computed = agg.finishWithZero() ? freshName(names) : out.name();
                    aggregates.add(new ColSpec(computed, lambda("x", new AppliedProperty(new Variable("x"),
                            agg.argColumn())), agg.reduce(), null));
                    if (agg.finishWithZero()) {
                        ValueSpecification sum = new AppliedProperty(new Variable("r"), computed);
                        finishes.add(new ColSpec(out.name(), lambda("r", call("if", call("isEmpty", sum),
                                thunk(new CInteger(0L)), thunk(call("toOne", sum)))), null, null));
                    }
                    columns.add(new Col(out.name(), agg.kind(), agg.nullable()));
                }
            }
            vs = state instanceof Graph g ? call("project", source(g), new ColSpecArray(specs))
                    : reproject((Rows) state, specs);
            return new ArmProjection(vs, true, columns, sortKeys, hidden, afterGrouping, keys, aggregates, finishes,
                    null);
        }
        return new ArmProjection(vs, false, columns, sortKeys, hidden, afterGrouping, List.of(), List.of(), List.of(),
                whereColumn);
    }

    private record SortKey(String column, boolean descending, boolean hidden) {
    }

    /** The root class's instances, filtered by the graph's conditions. */
    private static ValueSpecification source(Graph g) {
        ValueSpecification all = call("getAll", new PackageableElementPtr(g.rootClass()));
        if (g.conditions().isEmpty()) {
            return all;
        }
        ValueSpecification cond = g.conditions().get(0);
        for (int i = 1; i < g.conditions().size(); i++) {
            cond = call("and", cond, g.conditions().get(i));
        }
        return call("filter", all, lambda(g.row(), cond));
    }

    /** A relation's rows projected again: the new columns computed by {@code extend} (under fresh names where a
     *  new column takes an old one's name), then the new columns alone, in order, under their names. */
    private ValueSpecification reproject(Rows rows, List<ColSpec> specs) {
        Set<String> existing = new HashSet<>();
        rows.columns().stream().filter(c -> !c.virtual()).forEach(c -> existing.add(c.name()));
        List<ColSpec> computed = new ArrayList<>();
        List<ColSpec> selected = new ArrayList<>();
        List<String[]> renames = new ArrayList<>();
        Set<String> taken = new HashSet<>(existing);
        specs.forEach(s -> taken.add(s.name()));
        for (ColSpec s : specs) {
            LambdaFunction f = java.util.Objects.requireNonNull(s.function1());
            if (f.body().get(0) instanceof AppliedProperty p && p.property().equals(s.name())
                    && p.receiver() instanceof Variable) {
                // the column itself, kept under its name
                selected.add(new ColSpec(s.name(), null, null, null));
                continue;
            }
            if (existing.contains(s.name())) {
                String tmp = freshName(taken);
                computed.add(new ColSpec(tmp, f, null, null));
                selected.add(new ColSpec(tmp, null, null, null));
                renames.add(new String[] {tmp, s.name()});
            } else {
                computed.add(s);
                selected.add(new ColSpec(s.name(), null, null, null));
            }
        }
        ValueSpecification vs = rows.vs();
        if (!computed.isEmpty()) {
            vs = call("extend", vs, new ColSpecArray(computed));
        }
        vs = call("select", vs, new ColSpecArray(selected));
        for (String[] r : renames) {
            vs = call("rename", vs, new ColSpec(r[0], null, null, null), new ColSpec(r[1], null, null, null));
        }
        return vs;
    }

    /** The output a sort expression names: by an item's alias, or by its text. */
    private static @com.legend.base.Nullable String outputNamed(List<Item> items, Expr e, List<Output> outputs) {
        if (e instanceof CypherAst.Var v) {
            for (Output o : outputs) {
                if (o.name().equals(v.name())) {
                    return o.name();
                }
            }
        }
        String text = text(e);
        for (int i = 0; i < items.size(); i++) {
            Item item = items.get(i);
            if (item.expr() != null && text(item.expr()).equals(text)) {
                return outputs.get(i).name();
            }
        }
        return null;
    }

    private ValueSpecification rowCount(Expr e, String clause) {
        if (e instanceof CypherAst.IntLit n) {
            if (n.value() < 0) {
                throw CypherErrors.invalid("Invalid input. '" + n.value() + "' is not a valid value. Must be a"
                        + " non-negative integer.", e.pos());
            }
            return new CInteger(n.value());
        }
        if (e instanceof CypherAst.Param) {
            throw CypherErrors.unsupported("a parameter as " + clause + "'s count is not supported yet", e.pos());
        }
        throw CypherErrors.unsupported(clause + " with an expression other than an integer literal is not"
                + " supported yet", e.pos());
    }

    private String freshName(Set<String> taken) {
        String name;
        do {
            name = SYNTHETIC + (++fresh);
        } while (taken.contains(name));
        taken.add(name);
        return name;
    }

    // ------------------------------------------------------------------ aggregates

    private static boolean isAggregate(Expr e) {
        return e instanceof CypherAst.CountStar
                || e instanceof CypherAst.Call c && AGGREGATES.contains(c.name().toLowerCase(Locale.ROOT));
    }

    private static boolean containsAggregate(Expr e) {
        if (isAggregate(e)) {
            return true;
        }
        for (Expr child : children(e)) {
            if (containsAggregate(child)) {
                return true;
            }
        }
        return false;
    }

    private static List<Expr> children(Expr e) {
        return switch (e) {
            case CypherAst.Prop p -> List.of(p.target());
            case CypherAst.ListLit l -> l.elements();
            case CypherAst.MapLit m -> List.copyOf(m.entries().values());
            case CypherAst.Unary u -> List.of(u.operand());
            case CypherAst.Binary b -> List.of(b.left(), b.right());
            case CypherAst.Call c -> c.args();
            case CypherAst.Case c -> {
                List<Expr> all = new ArrayList<>();
                if (c.operand() != null) {
                    all.add(c.operand());
                }
                for (CypherAst.When w : c.whens()) {
                    all.add(w.condition());
                    all.add(w.result());
                }
                if (c.elseResult() != null) {
                    all.add(c.elseResult());
                }
                yield all;
            }
            case CypherAst.Paren p -> List.of(p.inner());
            case CypherAst.Quantifier q -> List.of(q.list(), q.where());
            case CypherAst.ListComprehension lc -> {
                List<Expr> all = new ArrayList<>();
                all.add(lc.list());
                if (lc.where() != null) {
                    all.add(lc.where());
                }
                if (lc.map() != null) {
                    all.add(lc.map());
                }
                yield all;
            }
            case CypherAst.Index ix -> List.of(ix.target(), ix.index());
            case CypherAst.Slice sl -> {
                List<Expr> all = new ArrayList<>();
                all.add(sl.target());
                if (sl.from() != null) {
                    all.add(sl.from());
                }
                if (sl.to() != null) {
                    all.add(sl.to());
                }
                yield all;
            }
            case CypherAst.IntLit i -> List.of();
            case CypherAst.FloatLit f -> List.of();
            case CypherAst.StringLit s -> List.of();
            case CypherAst.BoolLit b -> List.of();
            case CypherAst.NullLit n -> List.of();
            case CypherAst.Var v -> List.of();
            case CypherAst.Param p -> List.of();
            case CypherAst.CountStar c -> List.of();
        };
    }

    private Aggregate aggregate(Expr e, Scope scope, Set<String> names) {
        if (e instanceof CypherAst.CountStar) {
            return new Aggregate(freshName(names), Tx.value(new CInteger(1L), Kind.INTEGER, false),
                    lambda("y", call("count", new Variable("y"))), Kind.INTEGER, false, false);
        }
        CypherAst.Call c = (CypherAst.Call) e;
        String fn = c.name().toLowerCase(Locale.ROOT);
        if (fn.equals("percentiledisc") || fn.equals("percentilecont")) {
            throw CypherErrors.unsupported(c.name() + " (a percentile aggregate) is not supported yet", c.pos());
        }
        if (c.args().size() != 1) {
            throw CypherErrors.invalid("Insufficient parameters for function '" + c.name() + "'", c.pos());
        }
        Expr argExpr = c.args().get(0);
        if (containsAggregate(argExpr)) {
            throw CypherErrors.invalid("Can't use aggregate functions inside of aggregate functions.", c.pos());
        }
        if (c.distinct()) {
            throw CypherErrors.unsupported(c.name() + "(DISTINCT ...) is not supported yet", c.pos());
        }
        if (fn.equals("count") && argExpr instanceof CypherAst.Var v && scope.isPatternVariable(v.name())) {
            // count(n) for a matched node or relationship: every row has one
            return new Aggregate(freshName(names), Tx.value(new CInteger(1L), Kind.INTEGER, false),
                    lambda("y", call("count", new Variable("y"))), Kind.INTEGER, false, false);
        }
        Tx arg = tx(argExpr, scope);
        ValueSpecification values = new Variable("y");
        if (fn.equals("count")) {
            return new Aggregate(freshName(names), arg, lambda("y", call("count", values)), Kind.INTEGER, false,
                    false);
        }
        if (fn.equals("sum")) {
            requireNumeric(arg, c);
            // Cypher's sum of no values is 0; the database's is null
            return new Aggregate(freshName(names), arg, lambda("y", call("sum", values)), arg.kind(), false, true);
        }
        if (fn.equals("avg")) {
            requireNumeric(arg, c);
            return new Aggregate(freshName(names), arg, lambda("y", call("average", values)), Kind.FLOAT, true,
                    false);
        }
        if (fn.equals("min") || fn.equals("max")) {
            if (arg.kind() == Kind.BOOLEAN || arg.kind() == Kind.NULL || arg.kind() == Kind.OTHER) {
                throw CypherErrors.unsupported(c.name() + " of this value is not supported yet", c.pos());
            }
            return new Aggregate(freshName(names), arg, lambda("y", call(fn, values)), arg.kind(), true, false);
        }
        throw CypherErrors.unsupported(c.name() + "(...) is not supported yet", c.pos());
    }

    private static void requireNumeric(Tx arg, CypherAst.Call c) {
        if (arg.kind() == Kind.NULL) {
            throw CypherErrors.unsupported(c.name() + " of null is not supported yet", c.pos());
        }
        if (!arg.kind().numeric()) {
            throw CypherErrors.invalid("Type mismatch: expected Float, Integer or Duration but was "
                    + cypherType(arg.kind()), c.pos());
        }
    }

    // ------------------------------------------------------------------ scopes

    /** Where a variable or a property is found: over a graph's root instance, over a relation's row, or bound by a
     *  list predicate or comprehension. */
    private interface Scope {
        /** The lambda parameter naming the row the scope reads. */
        String row();

        /** The value a variable is; a node or relationship variable has no value in PR1. */
        Val variable(CypherAst.Var v);

        /** The column an expression is, when the scope reads a grouping's or a DISTINCT's result and the expression
         *  re-spells one of its keys (Neo4j's rule for the WHERE and ORDER BY of such a WITH: `WITH a.name AS name,
         *  count(*) AS cnt ORDER BY a.name + 'C'` orders by the key, whose expression is `a.name`); null otherwise. */
        default @com.legend.base.Nullable Val keyed(Expr e) {
            return null;
        }

        /** The parts of the node {@code v} names (its labels' JSON text, its properties), for a node as a value and
         *  for {@code labels(n)} and {@code properties(n)}; null when {@code v} names no node. */
        default @com.legend.base.Nullable NodeParts nodeParts(CypherAst.Var v) {
            return null;
        }

        /** Whether {@code v} names a node an OPTIONAL MATCH never finds (null everywhere). */
        default boolean isAbsentNode(CypherAst.Var v) {
            return false;
        }

        /** The value a property access is: a node's property, or a map's entry. */
        Val property(CypherAst.Prop p);

        /** Whether {@code name} is a node or relationship variable of a pattern. */
        boolean isPatternVariable(String name);
    }

    private final class GraphScope implements Scope {
        private final Graph g;

        GraphScope(Graph g) {
            this.g = g;
        }

        @Override
        public String row() {
            return g.row();
        }

        @Override
        public Val variable(CypherAst.Var v) {
            Binding b = g.bindings().get(v.name());
            if (b == null) {
                throw CypherErrors.invalid("Variable `" + v.name() + "` not defined", v.pos());
            }
            if (!b.node()) {
                throw CypherErrors.unsupported("a relationship as a value (" + v.name() + ") is not supported yet",
                        v.pos());
            }
            if (b.absent()) {
                return scalar(absentNodeValue());
            }
            return scalar(nodeJson(parts(b, v.pos()), v.pos()));
        }

        /** An absent node as a value: a null the arms unite (typed as a node's JSON). */
        Tx absentNodeValue() {
            return new Tx(SqlToPure.nullOf(Kind.JSON).vs(), Kind.JSON, true, null, new NodeValue(), null, null);
        }

        /** An absent node's property: a null typed as the property is where a class the arms read declares it
         *  (the columns of the arms unite), else untyped. */
        Tx absentProperty(String name, Pos pos) {
            for (String other : g.candidates()) {
                Property elsewhere = CypherToPure.this.property(other, name);
                if (elsewhere != null && !elsewhere.classTyped() && !elsewhere.enumTyped() && !elsewhere.many()) {
                    return SqlToPure.nullOf(kindOf(elsewhere.type(), name, pos));
                }
            }
            return SqlToPure.nullOf(Kind.NULL);
        }

        @Override
        public @com.legend.base.Nullable NodeParts nodeParts(CypherAst.Var v) {
            Binding b = g.bindings().get(v.name());
            return b == null || !b.node() || b.absent() ? null : parts(b, v.pos());
        }

        @Override
        public boolean isAbsentNode(CypherAst.Var v) {
            Binding b = g.bindings().get(v.name());
            return b != null && b.absent();
        }

        /** A bound node's parts: its class's label, its properties navigated from the root instance; a node an
         *  OPTIONAL MATCH reached is present where its navigation finds one (a MATCH's optional hop is never
         *  absent: the hop's isNotEmpty condition is the graph's). */
        NodeParts parts(Binding b, Pos pos) {
            String cls = java.util.Objects.requireNonNull(b.classPath());
            List<Map.Entry<String, Tx>> props = new ArrayList<>();
            for (Property p : properties(cls)) {
                if (!p.classTyped() && !p.enumTyped() && !p.many()) {
                    props.add(Map.entry(p.name(), propertyOf(b, p.name(), pos)));
                }
            }
            ValueSpecification presence = b.maybeAbsent() ? navigate(g.row(), b.path()) : null;
            return new NodeParts(new CString(labelsJson(cls)), props, presence);
        }

        /** A bound node carried through a projection: its labels' text and its properties as columns -- of every
         *  class a label-less root may be (the arms' columns agree: null where a class lacks the property, the
         *  same fresh names in every arm). */
        NodeCarry carry(Binding b, Pos pos, Set<String> names, List<String> acrossArms) {
            if (b.absent()) {
                // null everywhere: the columns the node has in the other arms, each null
                List<ColSpec> specs = new ArrayList<>();
                List<Col> columns = new ArrayList<>();
                String labels = freshName(names);
                specs.add(new ColSpec(labels, lambda(g.row(), SqlToPure.nullOf(Kind.STRING).vs()), null, null));
                columns.add(new Col(labels, Kind.STRING, true));
                Map<String, NodeProp> props = new LinkedHashMap<>();
                for (Map.Entry<String, Kind> k : unionKinds(acrossArms, pos).entrySet()) {
                    String column = freshName(names);
                    specs.add(new ColSpec(column, lambda(g.row(), SqlToPure.nullOf(k.getValue()).vs()), null, null));
                    columns.add(new Col(column, k.getValue(), true));
                    props.put(k.getKey(), new NodeProp(column, k.getValue(), true));
                }
                return new NodeCarry(new NodeShape(labels, props, true), specs, columns);
            }
            String cls = java.util.Objects.requireNonNull(b.classPath());
            List<String> classes = !acrossArms.isEmpty() ? acrossArms
                    : b.path().isEmpty() && g.candidates().size() > 1 ? g.candidates() : List.of(cls);
            Map<String, Kind> kinds = unionKinds(classes, pos);
            List<ColSpec> specs = new ArrayList<>();
            List<Col> columns = new ArrayList<>();
            if (b.fanning() && kinds.isEmpty()) {
                // the rows multiply through the properties navigated along the to-many path: none, no rows (lite's
                // project of a navigation mapped to a constant stays one row per root, measured)
                throw CypherErrors.unsupported("a pattern that fans out to a node of " + cls + ", a class without a"
                        + " property, is not supported yet (one row per node needs a navigated column)", pos);
            }
            String labels = freshName(names);
            // an OPTIONAL MATCH's node: its labels where its navigation finds one, null where none (the node's
            // presence, read by the clauses after)
            boolean optional = b.maybeAbsent();
            ValueSpecification labelsText = optional ? call("if", call("isEmpty", navigate(g.row(), b.path())),
                    thunk(SqlToPure.nullOf(Kind.STRING).vs()), thunk(new CString(labelsJson(cls))))
                    : new CString(labelsJson(cls));
            specs.add(new ColSpec(labels, lambda(g.row(), labelsText), null, null));
            columns.add(new Col(labels, Kind.STRING, optional));
            Map<String, NodeProp> props = new LinkedHashMap<>();
            for (Map.Entry<String, Kind> k : kinds.entrySet()) {
                String column = freshName(names);
                Tx t = CypherToPure.this.property(cls, k.getKey()) != null ? propertyOf(b, k.getKey(), pos)
                        : SqlToPure.nullOf(k.getValue());
                boolean nullable = classes.size() > 1 || t.nullable() || optional;
                specs.add(new ColSpec(column, lambda(g.row(), SqlToPure.valueOf(t)), null, null));
                columns.add(new Col(column, k.getValue(), nullable));
                props.put(k.getKey(), new NodeProp(column, k.getValue(), nullable));
            }
            return new NodeCarry(new NodeShape(labels, props), specs, columns);
        }

        /** The labels array's JSON text of a class: its label. */
        private String labelsJson(String cls) {
            return "[" + com.legend.json.Json.toCompact(model.labelOf(cls)) + "]";
        }

        /** The stored properties of these classes, in first-declaration order, each of one kind across them (a
         *  property typed differently across the classes a node may be is refused by name). */
        private Map<String, Kind> unionKinds(List<String> classes, Pos pos) {
            Map<String, Kind> kinds = new LinkedHashMap<>();
            for (String c : classes) {
                for (Property p : properties(c)) {
                    if (p.classTyped() || p.enumTyped() || p.many()) {
                        continue;
                    }
                    Kind kind = kindOf(p.type(), p.name(), pos);
                    Kind before = kinds.putIfAbsent(p.name(), kind);
                    if (before != null && before != kind) {
                        throw CypherErrors.unsupported("the property " + p.name() + " typed differently across the"
                                + " classes a label-less node reads (" + cypherType(before) + " and "
                                + cypherType(kind) + ") is not supported yet", pos);
                    }
                }
            }
            return kinds;
        }

        @Override
        public Val property(CypherAst.Prop p) {
            if (!(p.target() instanceof CypherAst.Var v)) {
                return member(val(p.target(), this), p.name(), p.pos());
            }
            Binding b = g.bindings().get(v.name());
            if (b == null) {
                throw CypherErrors.invalid("Variable `" + v.name() + "` not defined", v.pos());
            }
            if (b.absent()) {
                return scalar(absentProperty(p.name(), p.pos()));
            }
            return scalar(propertyOf(b, p.name(), p.pos()));
        }

        @Override
        public boolean isPatternVariable(String name) {
            return g.bindings().containsKey(name);
        }

        /** A bound node's property: the class's, navigated from the root instance (C4: a property the class does not
         *  declare is an error, not null). */
        Tx propertyOf(Binding b, String name, Pos pos) {
            if (!b.node()) {
                throw CypherErrors.unsupported("a property of a relationship is not supported yet (an edge class)",
                        pos);
            }
            String cls = java.util.Objects.requireNonNull(b.classPath());
            // the outer class's lookup: this scope's own property(Prop) shadows its name
            Property prop = CypherToPure.this.property(cls, name);
            if (prop == null) {
                // a label-less node of a class without the property: null (Neo4j's heterogeneous nodes), typed as
                // the property is where a class declares it
                for (String other : g.candidates()) {
                    Property elsewhere = CypherToPure.this.property(other, name);
                    if (elsewhere != null && !elsewhere.classTyped() && !elsewhere.enumTyped()
                            && !elsewhere.many()) {
                        return SqlToPure.nullOf(kindOf(elsewhere.type(), name, pos));
                    }
                }
                throw CypherErrors.invalid("the class " + cls + " has no property " + name + " (the"
                        + " graph is the model: a property a class does not declare is an error, not null)", pos);
            }
            if (prop.classTyped()) {
                throw CypherErrors.unsupported("the property " + name + " holds instances of " + prop.type()
                        + ": a node as a value is not supported yet", pos);
            }
            if (prop.enumTyped()) {
                throw CypherErrors.unsupported("the property " + name + " holds an enumeration value: not supported"
                        + " yet", pos);
            }
            if (prop.many()) {
                throw CypherErrors.unsupported("the property " + name + " holds many values (a list): not supported"
                        + " yet", pos);
            }
            Kind kind = kindOf(prop.type(), name, pos);
            List<String> path = new ArrayList<>(b.path());
            path.add(name);
            ValueSpecification v = navigate(g.row(), path);
            boolean nullable = prop.optional() || b.optional();
            return columnLike(v, kind, nullable);
        }
    }

    /** The scope an ORDER BY reads after a projection: the projected items by their names (each its value over the
     *  row before the projection), then the scope before it. */
    private final class ProjectedScope implements Scope {
        private final Scope inner;
        private final Map<String, Val> projected;

        ProjectedScope(Scope inner, Map<String, Val> projected) {
            this.inner = inner;
            this.projected = projected;
        }

        @Override
        public String row() {
            return inner.row();
        }

        @Override
        public Val variable(CypherAst.Var v) {
            Val t = projected.get(v.name());
            return t != null ? t : inner.variable(v);
        }

        @Override
        public @com.legend.base.Nullable NodeParts nodeParts(CypherAst.Var v) {
            return projected.containsKey(v.name()) ? null : inner.nodeParts(v);
        }

        @Override
        public boolean isAbsentNode(CypherAst.Var v) {
            return !projected.containsKey(v.name()) && inner.isAbsentNode(v);
        }

        @Override
        public Val property(CypherAst.Prop p) {
            if (p.target() instanceof CypherAst.Var v && projected.containsKey(v.name())) {
                return member(projected.get(v.name()), p.name(), p.pos());
            }
            return inner.property(p);
        }

        @Override
        public boolean isPatternVariable(String name) {
            return !projected.containsKey(name) && inner.isPatternVariable(name);
        }
    }

    /** The scope of a WHERE or an ORDER BY after a grouping or a DISTINCT: the projected columns, and an expression
     *  that re-spells a key's expression reads the key (the TCK's WithWhere1 [2]: `WITH DISTINCT a.name2 AS name
     *  WHERE a.name2 = 'B'`; WithOrderBy2 [23]: `WITH a.name AS name, count(*) AS cnt ORDER BY a.name + 'C'`). The
     *  match is on the spelled text, as Neo4j's is on the expression's shape; a projected name wins over a key. */
    private final class KeyedScope implements Scope {
        private final RowScope inner;
        /** A key's spelled expression, to the column it is projected as. */
        private final Map<String, String> keys;

        KeyedScope(Rows rows, Map<String, String> keys) {
            this.inner = new RowScope(rows);
            this.keys = keys;
        }

        @Override
        public String row() {
            return inner.row();
        }

        @Override
        public Val variable(CypherAst.Var v) {
            return inner.variable(v);
        }

        @Override
        public @com.legend.base.Nullable NodeParts nodeParts(CypherAst.Var v) {
            return inner.nodeParts(v);
        }

        @Override
        public boolean isAbsentNode(CypherAst.Var v) {
            return inner.isAbsentNode(v);
        }

        @Override
        public Val property(CypherAst.Prop p) {
            return inner.property(p);
        }

        @Override
        public @com.legend.base.Nullable Val keyed(Expr e) {
            if (e instanceof CypherAst.Var v && inner.has(v.name())) {
                return null;
            }
            String column = keys.get(text(e));
            return column == null ? null : inner.variable(new CypherAst.Var(column, e.pos()));
        }

        @Override
        public boolean isPatternVariable(String name) {
            return false;
        }
    }

    private final class RowScope implements Scope {
        private final Rows rows;

        /** Whether the rows have a column of this name. */
        boolean has(String name) {
            return column(name) != null;
        }

        /** The column of this name; null when none. */
        @com.legend.base.Nullable Col column(String name) {
            for (Col c : rows.columns()) {
                if (c.name().equals(name)) {
                    return c;
                }
            }
            return null;
        }

        RowScope(Rows rows) {
            this.rows = rows;
        }

        @Override
        public String row() {
            return "r";
        }

        @Override
        public Val variable(CypherAst.Var v) {
            for (Col c : rows.columns()) {
                if (c.name().equals(v.name())) {
                    if (c.carried() != null) {
                        return c.carried();
                    }
                    if (c.detail() instanceof NodeShape shape) {
                        // a carried node as a value: its JSON from its columns; an absent one null
                        return shape.absent() ? scalar(SqlToPure.nullOf(Kind.JSON))
                                : scalar(nodeJson(parts(shape), v.pos()));
                    }
                    if (c.kind() == Kind.NULL) {
                        // a null the query named: null wherever it is read
                        return nullValue();
                    }
                    Tx read = columnLike(new AppliedProperty(new Variable(row()), c.name()), c.kind(),
                            c.nullable());
                    return scalar(c.detail() == null ? read : new Tx(read.vs(), read.kind(), read.nullable(),
                            read.whenFalse(), c.detail()));
                }
            }
            throw CypherErrors.invalid("Variable `" + v.name() + "` not defined", v.pos());
        }

        @Override
        public @com.legend.base.Nullable NodeParts nodeParts(CypherAst.Var v) {
            Col c = column(v.name());
            return c != null && c.detail() instanceof NodeShape shape && !shape.absent() ? parts(shape) : null;
        }

        @Override
        public boolean isAbsentNode(CypherAst.Var v) {
            Col c = column(v.name());
            return c != null && c.detail() instanceof NodeShape shape && shape.absent();
        }

        /** A carried node's parts, read from its columns: present where its labels column holds a value (an
         *  OPTIONAL MATCH's node carries null there when its hop found none). */
        NodeParts parts(NodeShape shape) {
            List<Map.Entry<String, Tx>> props = new ArrayList<>();
            for (Map.Entry<String, NodeProp> p : shape.props().entrySet()) {
                NodeProp np = p.getValue();
                props.add(Map.entry(p.getKey(), columnLike(new AppliedProperty(new Variable(row()), np.column()),
                        np.kind(), np.nullable())));
            }
            Col labels = java.util.Objects.requireNonNull(column(shape.labels()));
            ValueSpecification read = new AppliedProperty(new Variable(row()), shape.labels());
            // (the column is read as one: a projected column types [0..1], the labels' text is there when present)
            return new NodeParts(call("toOne", read), props, labels.nullable() ? read : null);
        }

        @Override
        public Val property(CypherAst.Prop p) {
            if (!(p.target() instanceof CypherAst.Var v)) {
                return member(val(p.target(), this), p.name(), p.pos());
            }
            Col found = null;
            for (Col c : rows.columns()) {
                if (c.name().equals(v.name())) {
                    found = c;
                }
            }
            if (found == null) {
                throw CypherErrors.invalid("Variable `" + v.name() + "` not defined", v.pos());
            }
            if (found.carried() != null) {
                return member(found.carried(), p.name(), p.pos());
            }
            if (found.detail() instanceof NodeShape shape) {
                // a carried node's property: its column (C4: a property no class declares is an error, not null);
                // an absent node's null
                if (shape.absent()) {
                    return nullValue();
                }
                NodeProp np = shape.props().get(p.name());
                if (np == null) {
                    throw CypherErrors.invalid("the node " + v.name() + " has no property " + p.name() + " (the"
                            + " graph is the model: a property no class declares is an error, not null)", p.pos());
                }
                return scalar(columnLike(new AppliedProperty(new Variable(row()), np.column()), np.kind(),
                        np.nullable()));
            }
            if (found.kind() == Kind.JSON) {
                throw CypherErrors.unsupported("a property of a projected value (a node or map carried through WITH)"
                        + " is not supported yet", p.pos());
            }
            return member(variable(v), p.name(), p.pos());
        }

        @Override
        public boolean isPatternVariable(String name) {
            return false;
        }
    }

    /** The scope inside a list predicate or comprehension: its variable bound to one element, then the scope
     *  around it. */
    private final class BoundScope implements Scope {
        private final Scope inner;
        private final String name;
        private final Val value;

        BoundScope(Scope inner, String name, Val value) {
            this.inner = inner;
            this.name = name;
            this.value = value;
        }

        @Override
        public String row() {
            return inner.row();
        }

        @Override
        public Val variable(CypherAst.Var v) {
            return v.name().equals(name) ? value : inner.variable(v);
        }

        @Override
        public Val property(CypherAst.Prop p) {
            if (p.target() instanceof CypherAst.Var v && v.name().equals(name)) {
                return member(value, p.name(), p.pos());
            }
            return inner.property(p);
        }

        @Override
        public boolean isPatternVariable(String n) {
            return !n.equals(name) && inner.isPatternVariable(n);
        }
    }

    /** A column's or a lambda parameter's value: a boolean one is a condition. */
    private static Tx columnLike(ValueSpecification v, Kind kind, boolean nullable) {
        return kind == Kind.BOOLEAN ? SqlToPure.booleanValue(v, nullable) : Tx.value(v, kind, nullable);
    }

    /** A Pure primitive's kind, as the translator reasons about a value. */
    private static Kind kindOf(String type, String name, Pos pos) {
        return switch (type) {
            case "Integer" -> Kind.INTEGER;
            case "Float" -> Kind.FLOAT;
            case "Decimal" -> Kind.DECIMAL;
            case "Number" -> Kind.NUMBER;
            case "String" -> Kind.STRING;
            case "Boolean" -> Kind.BOOLEAN;
            case "StrictDate" -> Kind.DATE;
            case "DateTime", "Date" -> Kind.DATETIME;
            case "StrictTime" -> Kind.TIME;
            default -> throw CypherErrors.unsupported("the property " + name + " is of type " + type + ": not"
                    + " supported yet", pos);
        };
    }

    /** A kind's name as Neo4j's messages spell its type. */
    private static String cypherType(Kind kind) {
        return switch (kind) {
            case INTEGER, WHOLE -> "Integer";
            case FLOAT, INEXACT, DECIMAL, NUMBER -> "Float";
            case STRING -> "String";
            case BOOLEAN -> "Boolean";
            case DATE -> "Date";
            case DATETIME -> "LocalDateTime";
            case TIME -> "LocalTime";
            case ZONED -> "DateTime";
            case ZONED_TIME -> "Time";
            case DURATION -> "Duration";
            case JSON -> "Map";
            case NULL -> "Null";
            case OTHER -> "Any";
        };
    }

    // ------------------------------------------------------------------ expressions

    /** A condition: the rows where {@code e} is true (null and false alike are not). */
    private ValueSpecification condition(Expr e, Scope scope, String clause) {
        Tx t = tx(e, scope);
        if (t.kind() != Kind.BOOLEAN && t.kind() != Kind.NULL) {
            throw CypherErrors.invalid("Type mismatch: expected Boolean but was " + cypherType(t.kind()), e.pos());
        }
        return SqlToPure.truth(t, clause, e.pos()).vs();
    }

    /** An expression's value over a scope: a scalar, or a list or map of known shape. */
    private Val val(Expr e, Scope scope) {
        Val keyed = scope.keyed(e);
        if (keyed != null) {
            return keyed;
        }
        return switch (e) {
            case CypherAst.IntLit n -> literal(Tx.value(new CInteger(n.value()), Kind.INTEGER, false), n.value());
            case CypherAst.FloatLit f -> floatLiteral(f, false);
            case CypherAst.StringLit s -> literal(Tx.value(new CString(s.value()), Kind.STRING, false), s.value());
            case CypherAst.BoolLit b -> literal(Tx.condition(new CBoolean(b.value()), new CBoolean(!b.value()),
                    false), b.value());
            case CypherAst.NullLit n -> nullValue();
            case CypherAst.Var v -> scope.variable(v);
            case CypherAst.Prop p -> scope.property(p);
            case CypherAst.Param p -> throw CypherErrors.unsupported("a parameter ($" + p.name() + ") is not"
                    + " supported yet", p.pos());
            case CypherAst.ListLit l -> {
                List<Val> elements = new ArrayList<>();
                for (Expr x : l.elements()) {
                    elements.add(val(x, scope));
                }
                yield new ListVal(elements);
            }
            case CypherAst.MapLit m -> {
                Map<String, Val> entries = new LinkedHashMap<>();
                for (Map.Entry<String, Expr> x : m.entries().entrySet()) {
                    entries.put(x.getKey(), val(x.getValue(), scope));
                }
                yield new MapVal(entries);
            }
            case CypherAst.Paren p -> val(p.inner(), scope);
            case CypherAst.Unary u -> unary(u, scope);
            case CypherAst.Binary b -> binary(b, scope);
            case CypherAst.Call c -> call(c, scope);
            case CypherAst.CountStar c -> throw CypherErrors.invalid("Invalid use of aggregating function count(*)"
                    + " in this context", c.pos());
            case CypherAst.Case c -> scalar(caseExpr(c, scope));
            case CypherAst.Quantifier q -> scalar(quantifier(q, scope));
            case CypherAst.ListComprehension lc -> comprehension(lc, scope);
            case CypherAst.Index ix -> index(ix, scope);
            case CypherAst.Slice sl -> slice(sl, scope);
        };
    }

    /** An expression's scalar: a list or map becomes the Variant the database holds. */
    private Tx tx(Expr e, Scope scope) {
        return scalarOf(val(e, scope), e.pos());
    }

    private Tx scalarOf(Val v, Pos pos) {
        return v instanceof Scalar s ? s.tx() : variant(v, pos);
    }

    /** A float literal, the sign folded in: Cypher's float is IEEE 754, so toFloat makes the database compute in
     *  double (0.1 + 0.2 is 0.30000000000000004); a bare Float literal lowers to a decimal
     *  (docs/SEMANTICS_REGISTER.md S9). */
    private static Val floatLiteral(CypherAst.FloatLit f, boolean negated) {
        double v = Math.abs(f.value());
        if (v >= 1e15 || (v != 0 && v < 1e-15)) {
            // lite's Postgres dialect spells a Float literal in plain digits, which Postgres types numeric
            // (1e308 answers an exact numeric, Neo4j a float): reported to the database owner (IN_FLIGHT,
            // 2026-10-15); refused here until it spells a float
            throw CypherErrors.unsupported("a float literal beyond 1e15 or below 1e-15 (" + f.text()
                    + ") is not supported yet", f.pos());
        }
        if (!negated) {
            return literal(Tx.value(call("toFloat", new CFloat(f.value())), Kind.FLOAT, false), f.value());
        }
        // IEEE negation: -0.0 is a value of its own in Cypher; a spelled -0.0 reaches the database as 0.0
        return literal(Tx.value(AppliedFunction.infixRun("times", List.of(call("toFloat", new CFloat(f.value())),
                new CFloat(-1.0))), Kind.FLOAT, false), -f.value());
    }

    private Val unary(CypherAst.Unary u, Scope scope) {
        if (u.op() == CypherAst.UnaryOp.NEG && u.operand() instanceof CypherAst.FloatLit f) {
            // the sign folded into the literal, as the parser folds an integer's: a list of literals stays literal
            return floatLiteral(f, true);
        }
        Val v = val(u.operand(), scope);
        if (u.op() == CypherAst.UnaryOp.IS_NULL || u.op() == CypherAst.UnaryOp.IS_NOT_NULL) {
            boolean isNull = u.op() == CypherAst.UnaryOp.IS_NULL;
            if (!(v instanceof Scalar s)) {
                // a list or map is a value: never null
                return scalar(constant(!isNull));
            }
            Tx t = s.tx();
            if (t.kind() == Kind.NULL) {
                return scalar(constant(isNull));
            }
            if (!t.nullable()) {
                return scalar(constant(!isNull));
            }
            ValueSpecification known = t.kind() == Kind.BOOLEAN ? call("or", t.vs(), t.falseWhen())
                    : call("isNotEmpty", t.vs());
            return scalar(isNull ? Tx.condition(call("not", known), known, false) : Tx.condition(known, call("not",
                    known), false));
        }
        Tx t = scalarOf(v, u.operand().pos());
        return scalar(switch (u.op()) {
            case NOT -> {
                if (t.kind() == Kind.NULL) {
                    yield SqlToPure.nullOf(Kind.BOOLEAN);
                }
                if (t.kind() != Kind.BOOLEAN) {
                    throw CypherErrors.invalid("Type mismatch: expected Boolean but was " + cypherType(t.kind()),
                            u.pos());
                }
                yield SqlToPure.not(t);
            }
            case NEG, POS -> {
                if (t.kind() == Kind.NULL) {
                    yield SqlToPure.nullOf(Kind.INTEGER);
                }
                if (!t.kind().numeric()) {
                    throw CypherErrors.invalid("Type mismatch: expected Float or Integer but was "
                            + cypherType(t.kind()), u.pos());
                }
                if (u.op() == CypherAst.UnaryOp.POS) {
                    yield t;
                }
                if (t.kind() == Kind.FLOAT || t.kind() == Kind.INEXACT) {
                    // IEEE negation: -0.0 is a value of its own in Cypher; Pure's minus lowers to 0 - x, which is +0.0
                    yield SqlToPure.strict(t.kind(), List.of(t), x -> AppliedFunction.infixRun("times", List.of(
                            x.get(0), new CFloat(-1.0))));
                }
                yield SqlToPure.strict(t.kind(), List.of(t), x -> call("minus", x.get(0)));
            }
            default -> throw new IllegalStateException("unreachable: " + u.op());
        });
    }

    private Val binary(CypherAst.Binary b, Scope scope) {
        BinaryOp op = b.op();
        switch (op) {
            case AND, OR, XOR -> {
                Tx l = SqlToPure.truth(booleanOperand(b.left(), scope), op.name(), b.left().pos());
                Tx r = SqlToPure.truth(booleanOperand(b.right(), scope), op.name(), b.right().pos());
                boolean nullable = l.nullable() || r.nullable();
                return scalar(switch (op) {
                    case AND -> and3(l, r);
                    case OR -> or3(l, r);
                    default -> Tx.condition(call("or", call("and", l.vs(), r.falseWhen()), call("and", l.falseWhen(),
                            r.vs())), call("or", call("and", l.vs(), r.vs()), call("and", l.falseWhen(),
                            r.falseWhen())), nullable);
                });
            }
            case EQ, NE -> {
                Tx eq = equalVal(val(b.left(), scope), val(b.right(), scope), b.pos());
                return scalar(op == BinaryOp.EQ ? eq : SqlToPure.not(eq));
            }
            case LT, LE, GT, GE -> {
                return scalar(ordered(op, tx(b.left(), scope), tx(b.right(), scope), b.pos()));
            }
            case PLUS -> {
                Val l = val(b.left(), scope);
                Val r = val(b.right(), scope);
                if (l instanceof Scalar ls && r instanceof Scalar rs) {
                    return scalar(arithmetic(b, ls.tx(), rs.tx()));
                }
                return concat(l, r, b.pos());
            }
            case MINUS, TIMES, DIVIDE, MODULO, POWER -> {
                return scalar(arithmetic(b, tx(b.left(), scope), tx(b.right(), scope)));
            }
            case STARTS_WITH, ENDS_WITH, CONTAINS -> {
                Tx l = tx(b.left(), scope);
                Tx r = tx(b.right(), scope);
                if (l.kind() == Kind.NULL || r.kind() == Kind.NULL) {
                    return scalar(SqlToPure.nullOf(Kind.BOOLEAN));
                }
                if (l.kind() != Kind.STRING || r.kind() != Kind.STRING) {
                    throw CypherErrors.unsupported(op.name().replace('_', ' ') + " on a value that is not a string"
                            + " (null in Neo4j) is not supported yet", b.pos());
                }
                String fn = switch (op) {
                    case STARTS_WITH -> "startsWith";
                    case ENDS_WITH -> "endsWith";
                    case CONTAINS -> "contains";
                    default -> throw new IllegalStateException("unreachable: " + op);
                };
                return scalar(SqlToPure.strictTest(List.of(l, r), v -> call(fn, v.get(0), v.get(1))));
            }
            case IN -> {
                return scalar(in(b, scope));
            }
            case REGEX -> throw CypherErrors.unsupported("=~ (a regular expression) is not supported yet", b.pos());
        }
        throw new IllegalStateException("unreachable: " + op);
    }

    /** Cypher's three-valued AND: null when neither side is false and either is null. A side that is known true
     *  is left out. */
    private static Tx and3(Tx l, Tx r) {
        if (isConstant(l, true)) {
            return r;
        }
        if (isConstant(r, true)) {
            return l;
        }
        return Tx.condition(call("and", l.vs(), r.vs()), call("or", l.falseWhen(), r.falseWhen()),
                l.nullable() || r.nullable());
    }

    /** Cypher's three-valued OR: null when neither side is true and either is null. A side that is known false is
     *  left out. */
    private static Tx or3(Tx l, Tx r) {
        if (isConstant(l, false)) {
            return r;
        }
        if (isConstant(r, false)) {
            return l;
        }
        return Tx.condition(call("or", l.vs(), r.vs()), call("and", l.falseWhen(), r.falseWhen()),
                l.nullable() || r.nullable());
    }

    /** A condition that is known: true or false. */
    private static Tx constant(boolean value) {
        return Tx.condition(new CBoolean(value), new CBoolean(!value), false);
    }

    private static boolean isConstant(Tx t, boolean value) {
        return !t.nullable() && t.vs() instanceof CBoolean c && c.value() == value;
    }

    /** A boolean operand: a condition, or null (Cypher's unknown). */
    private Tx booleanOperand(Expr e, Scope scope) {
        Tx t = tx(e, scope);
        if (t.kind() == Kind.NULL) {
            return SqlToPure.nullOf(Kind.BOOLEAN);
        }
        if (t.kind() != Kind.BOOLEAN) {
            throw CypherErrors.invalid("Type mismatch: expected Boolean but was " + cypherType(t.kind()), e.pos());
        }
        return t;
    }

    /** {@code l = r}: null when either is null; values of different types are never equal in Cypher (a type is a
     *  fact of the spelling, so the comparison is known false here; null when either side may be null). */
    private static Tx equal(Tx l, Tx r, Pos pos) {
        if (l.kind() == Kind.NULL || r.kind() == Kind.NULL) {
            return SqlToPure.nullOf(Kind.BOOLEAN);
        }
        if (l.kind() == Kind.JSON || r.kind() == Kind.JSON || l.kind() == Kind.OTHER || r.kind() == Kind.OTHER) {
            throw CypherErrors.unsupported("comparing a " + cypherType(l.kind()) + " with a " + cypherType(r.kind())
                    + " is not supported yet", pos);
        }
        boolean sameType = l.kind() == Kind.BOOLEAN || r.kind() == Kind.BOOLEAN ? l.kind() == r.kind()
                : comparable(l.kind(), r.kind());
        if (!sameType) {
            return l.nullable() || r.nullable() ? SqlToPure.strictTest(List.of(l, r), v -> new CBoolean(false))
                    : constant(false);
        }
        if (l.kind() == Kind.TIME && (nanos(l) || nanos(r))) {
            // a nanosecond time of day is the date-time at it on the epoch day: both compare so
            return SqlToPure.strictTest(List.of(atEpoch(l), atEpoch(r)), v -> call("equal", v.get(0), v.get(1)));
        }
        if (l.kind() == Kind.DURATION) {
            // two durations are equal when their four components are (Neo4j's: months, days, seconds, nanoseconds)
            return SqlToPure.strictTest(List.of(l, r), v -> {
                ValueSpecification all = null;
                for (String f : DURATION_SLOTS) {
                    ValueSpecification one = call("equal", new AppliedProperty(v.get(0), f),
                            new AppliedProperty(v.get(1), f));
                    all = all == null ? one : call("and", all, one);
                }
                return java.util.Objects.requireNonNull(all);
            });
        }
        return SqlToPure.strictTest(List.of(l, r), v -> call("equal", v.get(0), v.get(1)));
    }

    /** {@code l < r} and the others: null when either is null, or when the values are of different types. Booleans
     *  order false before true, as the databases order them. */
    private static Tx ordered(BinaryOp op, Tx l, Tx r, Pos pos) {
        if (l.kind() == Kind.NULL || r.kind() == Kind.NULL) {
            return SqlToPure.nullOf(Kind.BOOLEAN);
        }
        if (l.kind() == Kind.BOOLEAN && r.kind() == Kind.BOOLEAN) {
            String fn = switch (op) {
                case LT -> "lessThan";
                case LE -> "lessThanEqual";
                case GT -> "greaterThan";
                case GE -> "greaterThanEqual";
                default -> throw new IllegalStateException("unreachable: " + op);
            };
            return SqlToPure.strictTest(List.of(l, r), v -> call(fn, v.get(0), v.get(1)));
        }
        if (l.kind() == Kind.BOOLEAN || r.kind() == Kind.BOOLEAN) {
            // null in Neo4j: values of different types do not order
            return SqlToPure.nullOf(Kind.BOOLEAN);
        }
        if (!comparable(l.kind(), r.kind())) {
            throw CypherErrors.unsupported("comparing a " + cypherType(l.kind()) + " with a " + cypherType(r.kind())
                    + " (null in Neo4j) is not supported yet", pos);
        }
        if (l.kind() == Kind.DURATION) {
            throw CypherErrors.unsupported("ordering durations is not supported yet", pos);
        }
        SqlAst.BinaryOp sqlOp = switch (op) {
            case LT -> SqlAst.BinaryOp.LT;
            case LE -> SqlAst.BinaryOp.LE;
            case GT -> SqlAst.BinaryOp.GT;
            default -> SqlAst.BinaryOp.GE;
        };
        if (l.kind() == Kind.TIME) {
            // Pure orders no StrictTime: two times of day order as the date-times at them on one day
            return SqlToPure.compare(sqlOp, atEpoch(l), atEpoch(r));
        }
        return SqlToPure.compare(sqlOp, l, r);
    }

    private static boolean comparable(Kind a, Kind b) {
        if (a == b) {
            return a != Kind.OTHER && a != Kind.JSON;
        }
        return a.numeric() && b.numeric();
    }

    private Tx arithmetic(CypherAst.Binary b, Tx l, Tx r) {
        BinaryOp op = b.op();
        if (op == BinaryOp.PLUS && (l.kind() == Kind.STRING || r.kind() == Kind.STRING)) {
            // string concatenation; a number is concatenated as Neo4j prints it, which is not read yet
            if (l.kind() == Kind.NULL || r.kind() == Kind.NULL) {
                return SqlToPure.nullOf(Kind.STRING);
            }
            if (l.kind() != Kind.STRING || r.kind() != Kind.STRING) {
                throw CypherErrors.unsupported("+ of a string and a " + cypherType(l.kind() == Kind.STRING ? r.kind()
                        : l.kind()) + " is not supported yet", b.pos());
            }
            return SqlToPure.strict(Kind.STRING, List.of(l, r), v -> AppliedFunction.infixRun("plus", v));
        }
        if (l.kind() == Kind.NULL && r.kind() == Kind.NULL) {
            return SqlToPure.nullOf(Kind.INTEGER);
        }
        if (l.kind() == Kind.DURATION || r.kind() == Kind.DURATION) {
            return durationArithmetic(b, l, r);
        }
        if ((l.kind() == Kind.NULL || r.kind() == Kind.NULL) && (op == BinaryOp.PLUS || op == BinaryOp.MINUS)) {
            // a temporal value and null: null, of the value's kind (a duration would be the other operand)
            Tx other = l.kind() == Kind.NULL ? r : l;
            if (other.kind() == Kind.DATE || other.kind() == Kind.DATETIME || other.kind() == Kind.TIME
                    || other.kind() == Kind.ZONED || other.kind() == Kind.ZONED_TIME) {
                return SqlToPure.nullOf(other.kind());
            }
        }
        numericOperand(l, b.left());
        numericOperand(r, b.right());
        Kind kind = l.kind() == Kind.NULL ? r.kind() : r.kind() == Kind.NULL ? l.kind() : arithmeticKind(l.kind(),
                r.kind());
        if (l.kind() == Kind.NULL || r.kind() == Kind.NULL) {
            return SqlToPure.nullOf(kind);
        }
        return switch (op) {
            // Pure's plus, minus and times take one collection: the operator run, as the parser spells a + b
            case PLUS -> SqlToPure.strict(kind, List.of(l, r), v -> AppliedFunction.infixRun("plus", v));
            case MINUS -> SqlToPure.strict(kind, List.of(l, r), v -> AppliedFunction.infixRun("minus", v));
            case TIMES -> SqlToPure.strict(kind, List.of(l, r), v -> AppliedFunction.infixRun("times", v));
            case DIVIDE -> {
                if (l.kind() == Kind.INTEGER && r.kind() == Kind.INTEGER) {
                    // Cypher's integer division truncates toward zero, as Postgres's does
                    noDatabaseLambda("/ of integers", b.pos());
                    yield SqlToPure.strict(Kind.INTEGER, List.of(l, r), v -> SqlToPure.integerQuotient(v.get(0),
                            v.get(1)));
                }
                yield SqlToPure.strict(Kind.FLOAT, List.of(l, r), v -> call("divide", v.get(0), v.get(1)));
            }
            case MODULO -> {
                if (l.kind() != Kind.INTEGER || r.kind() != Kind.INTEGER) {
                    throw CypherErrors.unsupported("% of values that are not both integers is not supported yet",
                            b.pos());
                }
                noDatabaseLambda("%", b.pos());
                yield SqlToPure.strict(Kind.INTEGER, List.of(l, r), v -> call("rem", v.get(0), v.get(1)));
            }
            case POWER -> SqlToPure.strict(Kind.FLOAT, List.of(l, r), v -> call("pow", call("toFloat", v.get(0)),
                    v.get(1)));
            default -> throw new IllegalStateException("unreachable: " + op);
        };
    }

    private void noDatabaseLambda(String what, Pos pos) {
        if (databaseLambdas > 0) {
            throw CypherErrors.unsupported(what + " inside a list comprehension or predicate the database computes is"
                    + " not supported yet (lite's Postgres lowering types rem's divide-by-zero guard as text there)",
                    pos);
        }
    }

    private static void numericOperand(Tx t, Expr e) {
        if (t.kind() == Kind.NULL) {
            return;
        }
        if (t.kind() == Kind.DATE || t.kind() == Kind.DATETIME || t.kind() == Kind.TIME || t.kind() == Kind.ZONED
                || t.kind() == Kind.ZONED_TIME || t.kind() == Kind.DURATION) {
            throw CypherErrors.unsupported("temporal arithmetic (" + cypherType(t.kind()) + ") is not supported yet",
                    e.pos());
        }
        if (!t.kind().numeric()) {
            throw CypherErrors.invalid("Type mismatch: expected Float or Integer but was " + cypherType(t.kind()),
                    e.pos());
        }
    }

    /** Cypher's numeric promotion: integer with integer stays integer; a float anywhere makes a float. */
    private static Kind arithmeticKind(Kind a, Kind b) {
        if (a == Kind.INTEGER && b == Kind.INTEGER) {
            return Kind.INTEGER;
        }
        if (a == Kind.FLOAT || b == Kind.FLOAT || a == Kind.INEXACT || b == Kind.INEXACT) {
            return Kind.FLOAT;
        }
        if (a == Kind.DECIMAL || b == Kind.DECIMAL || a == Kind.NUMBER || b == Kind.NUMBER) {
            return Kind.DECIMAL;
        }
        return Kind.WHOLE;
    }

    /** {@code x IN list}: null when {@code x} is null, or when no member equals it and one is null; whether a member
     *  equals it otherwise. A scalar among literal scalars of its kind is Pure's {@code in}; any other list of known
     *  shape is unrolled into a disjunction of equalities (nulls, nested lists and maps among the members). */
    private Tx in(CypherAst.Binary b, Scope scope) {
        Val l = val(b.left(), scope);
        Val r = val(b.right(), scope);
        if (r instanceof Scalar rs) {
            if (rs.tx().kind() == Kind.NULL) {
                return SqlToPure.nullOf(Kind.BOOLEAN);
            }
            if (rs.tx().kind() == Kind.JSON) {
                throw CypherErrors.unsupported("IN over a list the database holds as a Variant is not supported yet",
                        b.pos());
            }
            throw CypherErrors.invalid("Type mismatch: expected List<T> but was " + cypherType(rs.tx().kind()),
                    b.right().pos());
        }
        if (r instanceof MapVal) {
            throw CypherErrors.invalid("Type mismatch: expected List<T> but was Map", b.right().pos());
        }
        if (r instanceof Coll c) {
            if (!(l instanceof Scalar ls)) {
                throw CypherErrors.unsupported("IN of a list or map over a list the database computes is not"
                        + " supported yet", b.pos());
            }
            if (ls.tx().kind() == Kind.NULL) {
                return SqlToPure.nullOf(Kind.BOOLEAN);
            }
            if (ls.tx().kind() == Kind.BOOLEAN ? c.kind() != Kind.BOOLEAN : !comparable(ls.tx().kind(), c.kind())) {
                return SqlToPure.strictTest(List.of(ls.tx()), v -> new CBoolean(false));
            }
            return SqlToPure.strictTest(List.of(ls.tx()), v -> call("in", v.get(0), c.vs()));
        }
        ListVal list = (ListVal) r;
        if (list.elements().isEmpty()) {
            return constant(false);
        }
        if (isNull(l)) {
            return SqlToPure.nullOf(Kind.BOOLEAN);
        }
        if (l instanceof Scalar ls && ls.tx().kind() != Kind.BOOLEAN && plainMembers(ls.tx().kind(), list)) {
            List<ValueSpecification> members = new ArrayList<>();
            for (Val e : list.elements()) {
                members.add(SqlToPure.valueOf(((Scalar) e).tx()));
            }
            return SqlToPure.strictTest(List.of(ls.tx()), v -> call("in", v.get(0), new PureCollection(members)));
        }
        Tx any = constant(false);
        for (Val e : list.elements()) {
            any = or3(any, equalVal(l, e, b.pos()));
        }
        return any;
    }

    /** Whether every member is a literal scalar of a kind comparable with {@code kind}, none null. */
    private static boolean plainMembers(Kind kind, ListVal list) {
        if (list.elements().size() < 2) {
            return false;
        }
        for (Val e : list.elements()) {
            if (!(e instanceof Scalar s) || !s.literal() || s.tx().kind() == Kind.NULL
                    || s.tx().kind() == Kind.BOOLEAN || !comparable(kind, s.tx().kind())) {
                return false;
            }
        }
        return true;
    }

    private Val call(CypherAst.Call c, Scope scope) {
        String fn = c.name().toLowerCase(Locale.ROOT);
        if (AGGREGATES.contains(fn)) {
            throw CypherErrors.invalid("Invalid use of aggregating function " + c.name() + "(...) in this context",
                    c.pos());
        }
        TemporalFamily family = TEMPORAL_FAMILIES.get(fn.contains(".") ? fn.substring(0, fn.indexOf('.')) : fn);
        if (family != null) {
            return temporalCall(c, fn, family, scope);
        }
        if ((fn.equals("labels") || fn.equals("properties")) && c.args().size() == 1
                && c.args().get(0) instanceof CypherAst.Var v) {
            // of a node: its labels, a list; its properties that hold a value, a map (the study's §12.15); of a node
            // an OPTIONAL MATCH never finds, null (typed, so the arms unite)
            if (scope.isAbsentNode(v)) {
                return scalar(SqlToPure.nullOf(Kind.JSON));
            }
            NodeParts parts = scope.nodeParts(v);
            if (parts != null) {
                return scalar(fn.equals("labels") ? nodeLabels(parts) : nodeProperties(parts, c.pos()));
            }
        }
        Function f = FUNCTIONS_BY_NAME.get(fn);
        if (f == null) {
            // refused by name before its arguments are read: the refusal names the function, not an argument
            throw CypherErrors.unsupported("the function " + c.name() + "(...) is not supported yet", c.pos());
        }
        List<Val> args = new ArrayList<>();
        for (Expr a : c.args()) {
            args.add(val(a, scope));
        }
        String pure = f.pure;
        return switch (f) {
            case COALESCE -> {
                List<Tx> txs = new ArrayList<>();
                for (Val a : args) {
                    txs.add(scalarOf(a, c.pos()));
                }
                yield scalar(coalesce(c, txs));
            }
            case SIZE, CHAR_LENGTH, CHARACTER_LENGTH -> size(c, arity(c, args, 1).get(0));
            case KEYS -> keys(c, arity(c, args, 1).get(0));
            case HEAD -> head(c, arity(c, args, 1).get(0), true);
            case LAST -> head(c, arity(c, args, 1).get(0), false);
            case TAIL -> tail(c, arity(c, args, 1).get(0));
            case REVERSE -> reverse(c, arity(c, args, 1).get(0));
            case IS_EMPTY -> isEmpty(c, arity(c, args, 1).get(0));
            case RANGE -> range(c, args);
            case SPLIT -> split(c, arity(c, args, 2));
            case TO_STRING -> toStringFn(c, arity(c, args, 1).get(0));
            case TO_INTEGER -> toInteger(c, arity(c, args, 1).get(0));
            case TO_FLOAT -> toFloatFn(c, arity(c, args, 1).get(0));
            case TO_BOOLEAN -> toBoolean(c, arity(c, args, 1).get(0));
            case ABS -> scalar(numeric(c, arity(c, args, 1).get(0), null, java.util.Objects.requireNonNull(pure)));
            case SIGN -> scalar(numeric(c, arity(c, args, 1).get(0), Kind.INTEGER,
                    java.util.Objects.requireNonNull(pure)));
            case SQRT, EXP, LOG, SIN, COS, TAN, ASIN, ACOS, ATAN -> scalar(numeric(c, arity(c, args, 1).get(0),
                    Kind.FLOAT, java.util.Objects.requireNonNull(pure)));
            case CEIL, FLOOR, ROUND -> scalar(rounding(c, arity(c, args, 1).get(0),
                    java.util.Objects.requireNonNull(pure)));
            case TO_LOWER, TO_UPPER, TRIM, LTRIM, RTRIM -> scalar(string(c, arity(c, args, 1),
                    java.util.Objects.requireNonNull(pure), 1));
            case LEFT, RIGHT -> scalar(string(c, arity(c, args, 2), java.util.Objects.requireNonNull(pure), 1));
            case REPLACE -> scalar(string(c, arity(c, args, 3), java.util.Objects.requireNonNull(pure), 3));
            case SUBSTRING -> scalar(substring(c, args));
            case RAND, RANDOM_UUID, TIMESTAMP -> throw CypherErrors.unsupported("the function " + c.name()
                    + "() (a value of the call, not of the data) is not supported yet", c.pos());
        };
    }

    /** The arguments of a function of {@code n}; Neo4j's two errors otherwise. */
    private static List<Val> arity(CypherAst.Call c, List<Val> args, int n) {
        if (args.size() < n) {
            throw CypherErrors.invalid("Insufficient parameters for function '" + c.name() + "'", c.pos());
        }
        if (args.size() > n) {
            throw CypherErrors.invalid("Too many parameters for function '" + c.name() + "'", c.pos());
        }
        return args;
    }

    /** {@code size(x)}: a list's count (a fact of a known shape's spelling, or the database's count of a computed
     *  one), a string's length. */
    private Val size(CypherAst.Call c, Val v) {
        return switch (v) {
            case ListVal l -> literal(Tx.value(new CInteger(l.elements().size()), Kind.INTEGER, false),
                    (long) l.elements().size());
            case MapVal m -> throw CypherErrors.invalid("Type mismatch: expected String or List<T> but was Map",
                    c.pos());
            case Coll col -> scalar(Tx.value(call("size", col.vs()), Kind.INTEGER, false));
            case Scalar s -> switch (s.tx().kind()) {
                case NULL -> scalar(SqlToPure.nullOf(Kind.INTEGER));
                case STRING -> scalar(SqlToPure.strict(Kind.INTEGER, List.of(s.tx()), x -> call("length",
                        x.get(0))));
                case JSON -> throw CypherErrors.unsupported("size of a list the database holds as a Variant is not"
                        + " supported yet", c.pos());
                default -> throw CypherErrors.invalid("Type mismatch: expected String or List<T> but was "
                        + cypherType(s.tx().kind()), c.pos());
            };
        };
    }

    /** {@code keys(map)}: a known map's keys, in their order. */
    private Val keys(CypherAst.Call c, Val v) {
        return switch (v) {
            case MapVal m -> {
                List<Val> out = new ArrayList<>();
                for (String k : m.entries().keySet()) {
                    out.add(literal(Tx.value(new CString(k), Kind.STRING, false), k));
                }
                yield new ListVal(out);
            }
            case Scalar s when s.tx().kind() == Kind.NULL -> nullValue();
            case Scalar s when s.tx().kind() == Kind.JSON -> throw CypherErrors.unsupported("keys of a map the"
                    + " database holds as a Variant is not supported yet", c.pos());
            case Scalar s -> throw CypherErrors.invalid("Type mismatch: expected Map, Node or Relationship but was "
                    + cypherType(s.tx().kind()), c.pos());
            default -> throw CypherErrors.invalid("Type mismatch: expected Map, Node or Relationship but was"
                    + " List<T>", c.pos());
        };
    }

    /** {@code head(list)} and {@code last(list)}: null for an empty list. */
    private Val head(CypherAst.Call c, Val v, boolean first) {
        return switch (v) {
            case ListVal l -> l.elements().isEmpty() ? nullValue()
                    : l.elements().get(first ? 0 : l.elements().size() - 1);
            case Coll col -> scalar(columnLike(call(first ? "first" : "last", col.vs()), col.kind(), true));
            case Scalar s when s.tx().kind() == Kind.NULL -> nullValue();
            default -> throw notAList(c, v);
        };
    }

    /** {@code tail(list)}: every element but the first. */
    private Val tail(CypherAst.Call c, Val v) {
        return switch (v) {
            case ListVal l -> new ListVal(l.elements().isEmpty() ? List.of()
                    : l.elements().subList(1, l.elements().size()));
            case Coll col -> new Coll(call("drop", col.vs(), new CInteger(1L)), col.kind(), col.rowFree());
            case Scalar s when s.tx().kind() == Kind.NULL -> nullValue();
            default -> throw notAList(c, v);
        };
    }

    /** {@code reverse(list)}, {@code reverse(string)}. */
    private Val reverse(CypherAst.Call c, Val v) {
        return switch (v) {
            case ListVal l -> {
                List<Val> out = new ArrayList<>(l.elements());
                java.util.Collections.reverse(out);
                yield new ListVal(out);
            }
            case Coll col -> new Coll(call("reverse", col.vs()), col.kind(), col.rowFree());
            case Scalar s when s.tx().kind() == Kind.NULL -> nullValue();
            case Scalar s when s.tx().kind() == Kind.STRING -> scalar(SqlToPure.strict(Kind.STRING,
                    List.of(s.tx()), x -> call("reverseString", x.get(0))));
            case Scalar s -> throw CypherErrors.invalid("Type mismatch: expected String or List<T> but was "
                    + cypherType(s.tx().kind()), c.pos());
            case MapVal m -> throw CypherErrors.invalid("Type mismatch: expected String or List<T> but was Map",
                    c.pos());
        };
    }

    /** {@code isEmpty(x)}: of a list, a map or a string. */
    private Val isEmpty(CypherAst.Call c, Val v) {
        return switch (v) {
            case ListVal l -> scalar(constant(l.elements().isEmpty()));
            case MapVal m -> scalar(constant(m.entries().isEmpty()));
            case Coll col -> scalar(Tx.condition(call("isEmpty", col.vs()), call("isNotEmpty", col.vs()), false));
            case Scalar s when s.tx().kind() == Kind.NULL -> nullValue();
            case Scalar s when s.tx().kind() == Kind.STRING -> scalar(SqlToPure.strictTest(List.of(s.tx()),
                    x -> call("equal", call("length", x.get(0)), new CInteger(0L))));
            case Scalar s -> throw CypherErrors.invalid("Type mismatch: expected String, List<T> or Map but was "
                    + cypherType(s.tx().kind()), c.pos());
        };
    }

    private static com.legend.protocol.sql.SqlTranslationException notAList(CypherAst.Call c, Val v) {
        return CypherErrors.invalid("Type mismatch: expected List<T> but was " + (v instanceof Scalar s
                ? cypherType(s.tx().kind()) : "Map"), c.pos());
    }

    /** {@code range(start, end [, step])}: the integers from start, as many steps as fit before the end, the
     *  database's. Neo4j takes {@code (end - start) / step} steps, the quotient truncated toward zero, so
     *  {@code range(0, 1, -123)} is {@code [0]} (Neo4j 2026.09.0; the TCK's text says empty); Pure's range stops
     *  before its end, so the last value is spelled and the end moved one past it, by the step's sign, which must
     *  be spelled. */
    private Val range(CypherAst.Call c, List<Val> args) {
        if (args.size() < 2 || args.size() > 3) {
            throw CypherErrors.invalid((args.size() < 2 ? "Insufficient" : "Too many") + " parameters for function"
                    + " 'range'", c.pos());
        }
        List<Tx> bounds = new ArrayList<>();
        boolean rowFree = true;
        for (int i = 0; i < 2; i++) {
            Val a = args.get(i);
            if (!(a instanceof Scalar s) || s.tx().kind() != Kind.INTEGER) {
                throw CypherErrors.invalid("Type mismatch: expected Integer but was " + (a instanceof Scalar s
                        ? cypherType(s.tx().kind()) : "List<T>"), c.args().get(i).pos());
            }
            if (s.tx().nullable()) {
                throw CypherErrors.unsupported("range(...) of a bound that may be null is not supported yet", c.pos());
            }
            bounds.add(s.tx());
            rowFree &= s.literal();
        }
        long step = 1;
        if (args.size() == 3) {
            Val a = args.get(2);
            if (!(a instanceof Scalar s) || s.tx().kind() != Kind.INTEGER) {
                throw CypherErrors.invalid("Type mismatch: expected Integer but was " + (a instanceof Scalar s
                        ? cypherType(s.tx().kind()) : "List<T>"), c.args().get(2).pos());
            }
            Long n = integerLiteral(s);
            if (n == null) {
                throw CypherErrors.unsupported("range(...) with a step that is not a literal is not supported yet",
                        c.pos());
            }
            if (n == 0) {
                throw CypherErrors.invalid("step argument to range() cannot be zero", c.pos());
            }
            step = n;
        }
        ValueSpecification start = bounds.get(0).vs();
        ValueSpecification last = bounds.get(1).vs();
        if (args.size() == 3) {
            // start + ((end - start) / step) * step
            ValueSpecification steps = SqlToPure.integerQuotient(AppliedFunction.infixRun("minus", List.of(last,
                    start)), new CInteger(step));
            last = AppliedFunction.infixRun("plus", List.of(start, AppliedFunction.infixRun("times", List.of(steps,
                    new CInteger(step)))));
        }
        ValueSpecification end = AppliedFunction.infixRun(step > 0 ? "plus" : "minus", List.of(last,
                new CInteger(1L)));
        ValueSpecification vs = args.size() == 3 ? call("range", start, end, new CInteger(step))
                : call("range", start, end);
        return new Coll(vs, Kind.INTEGER, rowFree);
    }

    /** {@code split(string, delimiter)}: the parts, a list the database computes. */
    private Val split(CypherAst.Call c, List<Val> args) {
        List<Tx> txs = new ArrayList<>();
        boolean rowFree = true;
        for (int i = 0; i < 2; i++) {
            Val a = args.get(i);
            if (!(a instanceof Scalar s)) {
                throw CypherErrors.invalid("Type mismatch: expected String but was " + (a instanceof MapVal ? "Map"
                        : "List<T>"), c.args().get(i).pos());
            }
            if (s.tx().kind() == Kind.NULL) {
                return nullValue();
            }
            if (s.tx().kind() != Kind.STRING) {
                throw CypherErrors.invalid("Type mismatch: expected String but was " + cypherType(s.tx().kind()),
                        c.args().get(i).pos());
            }
            if (s.tx().nullable()) {
                throw CypherErrors.unsupported("split of a value that may be null is not supported yet", c.pos());
            }
            txs.add(s.tx());
            rowFree &= s.literal();
        }
        return new Coll(call("split", txs.get(0).vs(), txs.get(1).vs()), Kind.STRING, rowFree);
    }

    /** {@code toString(x)}: a number's or a boolean's spelling, as the database spells it. */
    private Val toStringFn(CypherAst.Call c, Val v) {
        Tx t = scalarArg(c, v, "String, Boolean, Integer, Float");
        return switch (t.kind()) {
            case NULL -> scalar(SqlToPure.nullOf(Kind.STRING));
            case STRING -> scalar(t);
            case INTEGER, WHOLE, FLOAT, DECIMAL, NUMBER, INEXACT, BOOLEAN, DATE -> scalar(SqlToPure.strict(
                    Kind.STRING, List.of(t), x -> call("toString", x.get(0))));
            case DATETIME -> throw CypherErrors.unsupported("toString of a DateTime (Neo4j spells it as Java does,"
                    + " the database its own way) is not supported yet", c.pos());
            case TIME, ZONED, ZONED_TIME, DURATION -> throw CypherErrors.unsupported("toString of a "
                    + cypherType(t.kind())
                    + " (Neo4j spells it as Java does, the database its own way) is not supported yet", c.pos());
            default -> throw CypherErrors.unsupported("toString of a " + cypherType(t.kind()) + " is not supported"
                    + " yet", c.pos());
        };
    }

    /** {@code toInteger(x)}: a float truncated toward zero. */
    private Val toInteger(CypherAst.Call c, Val v) {
        Tx t = scalarArg(c, v, "Boolean, Integer, Float, String");
        return switch (t.kind()) {
            case NULL -> scalar(SqlToPure.nullOf(Kind.INTEGER));
            case INTEGER, WHOLE -> scalar(t);
            case FLOAT, DECIMAL, NUMBER, INEXACT -> scalar(SqlToPure.strict(Kind.INTEGER, List.of(t), x -> call("if",
                    call("greaterThanEqual", x.get(0), new CInteger(0L)), thunk(call("floor", x.get(0))),
                    thunk(call("ceiling", x.get(0))))));
            default -> throw CypherErrors.unsupported("toInteger of a " + cypherType(t.kind()) + " (null for text"
                    + " that is not a number) is not supported yet", c.pos());
        };
    }

    private Val toFloatFn(CypherAst.Call c, Val v) {
        Tx t = scalarArg(c, v, "Integer, Float, String");
        return switch (t.kind()) {
            case NULL -> scalar(SqlToPure.nullOf(Kind.FLOAT));
            case FLOAT, INEXACT -> scalar(t);
            case INTEGER, WHOLE, DECIMAL, NUMBER -> scalar(SqlToPure.strict(Kind.FLOAT, List.of(t), x -> call(
                    "toFloat", x.get(0))));
            default -> throw CypherErrors.unsupported("toFloat of a " + cypherType(t.kind()) + " (null for text"
                    + " that is not a number) is not supported yet", c.pos());
        };
    }

    /** {@code toBoolean(x)}: of a string, {@code true} or {@code false} in any case, else null. */
    private Val toBoolean(CypherAst.Call c, Val v) {
        Tx t = scalarArg(c, v, "Boolean, Integer, String");
        return switch (t.kind()) {
            case NULL -> scalar(SqlToPure.nullOf(Kind.BOOLEAN));
            case BOOLEAN -> scalar(t);
            case STRING -> {
                ValueSpecification text = t.nullable() ? call("toOne", t.vs()) : t.vs();
                ValueSpecification lower = call("toLower", text);
                ValueSpecification read = call("if", call("equal", lower, new CString("true")),
                        thunk(new CBoolean(true)), thunk(call("if", call("equal", lower, new CString("false")),
                                thunk(new CBoolean(false)), thunk(call("cast", SqlToPure.empty(),
                                        SqlToPure.typeArg("Boolean"))))));
                ValueSpecification value = t.nullable() ? call("if", call("isEmpty", t.vs()), thunk(call("cast",
                        SqlToPure.empty(), SqlToPure.typeArg("Boolean"))), thunk(read)) : read;
                yield scalar(SqlToPure.booleanValue(value, true));
            }
            default -> throw CypherErrors.unsupported("toBoolean of a " + cypherType(t.kind()) + " is not supported"
                    + " yet", c.pos());
        };
    }

    private static Tx scalarArg(CypherAst.Call c, Val v, String expected) {
        if (v instanceof Scalar s) {
            return s.tx();
        }
        throw CypherErrors.invalid("Type mismatch: expected " + expected + " but was " + (v instanceof MapVal ? "Map"
                : "List<T>"), c.pos());
    }

    /** A numeric function the database computes: of the argument's kind, or of {@code result} when given. */
    private static Tx numeric(CypherAst.Call c, Val v, @com.legend.base.Nullable Kind result, String fn) {
        Tx t = scalarArg(c, v, "Float or Integer");
        if (t.kind() == Kind.NULL) {
            return SqlToPure.nullOf(result == null ? Kind.INTEGER : result);
        }
        if (!t.kind().numeric()) {
            throw CypherErrors.invalid("Type mismatch: expected Float or Integer but was " + cypherType(t.kind()),
                    c.pos());
        }
        return SqlToPure.strict(result == null ? t.kind() : result, List.of(t), x -> call(fn, x.get(0)));
    }

    /** {@code ceil}, {@code floor}, {@code round}: a float, as Cypher's are (Pure's give an integer). Neo4j's round
     *  is Java's: half up, toward positive infinity ({@code round(-2.5)} is {@code -2.0}; Neo4j 2026.09.0), where the
     *  database's is half to even (DuckDB's, 2026-10-15): the floor of the value and a half. */
    private static Tx rounding(CypherAst.Call c, Val v, String fn) {
        Tx t = scalarArg(c, v, "Float or Integer");
        if (t.kind() == Kind.NULL) {
            return SqlToPure.nullOf(Kind.FLOAT);
        }
        if (!t.kind().numeric()) {
            throw CypherErrors.invalid("Type mismatch: expected Float or Integer but was " + cypherType(t.kind()),
                    c.pos());
        }
        if (fn.equals("round")) {
            return SqlToPure.strict(Kind.FLOAT, List.of(t), x -> call("toFloat", call("floor",
                    AppliedFunction.infixRun("plus", List.of(call("toFloat", x.get(0)), new CFloat(0.5))))));
        }
        return SqlToPure.strict(Kind.FLOAT, List.of(t), x -> call("toFloat", call(fn, call("toFloat", x.get(0)))));
    }

    /** A string function the database computes, of {@code strings} string arguments first and integers after. */
    private static Tx string(CypherAst.Call c, List<Val> args, String fn, int strings) {
        List<Tx> txs = new ArrayList<>();
        for (int i = 0; i < args.size(); i++) {
            Tx t = scalarArg(c, args.get(i), i < strings ? "String" : "Integer");
            if (t.kind() == Kind.NULL) {
                return SqlToPure.nullOf(Kind.STRING);
            }
            Kind wanted = i < strings ? Kind.STRING : Kind.INTEGER;
            if (t.kind() != wanted) {
                throw CypherErrors.invalid("Type mismatch: expected " + cypherType(wanted) + " but was "
                        + cypherType(t.kind()), c.args().get(i).pos());
            }
            txs.add(t);
        }
        return SqlToPure.strict(Kind.STRING, txs, x -> call(fn, x.toArray(ValueSpecification[]::new)));
    }

    /** {@code substring(string, start [, length])}: from the 0-based start. Lite's {@code substring} is SQL's
     *  (legend-engine's own without its CORRECT_SQL_SUBSTRING_INDEXING flag: a 1-based start and a length, passed
     *  through), so Cypher's start is moved by one. */
    private static Tx substring(CypherAst.Call c, List<Val> args) {
        if (args.size() < 2 || args.size() > 3) {
            throw CypherErrors.invalid((args.size() < 2 ? "Insufficient" : "Too many") + " parameters for function"
                    + " 'substring'", c.pos());
        }
        Tx s = scalarArg(c, args.get(0), "String");
        Tx start = scalarArg(c, args.get(1), "Integer");
        Tx length = args.size() == 3 ? scalarArg(c, args.get(2), "Integer") : null;
        if (s.kind() == Kind.NULL || start.kind() == Kind.NULL || length != null && length.kind() == Kind.NULL) {
            return SqlToPure.nullOf(Kind.STRING);
        }
        if (s.kind() != Kind.STRING || start.kind() != Kind.INTEGER || length != null && length.kind() != Kind.INTEGER) {
            throw CypherErrors.invalid("Type mismatch: substring expects a String and Integers", c.pos());
        }
        if (length == null) {
            return SqlToPure.strict(Kind.STRING, List.of(s, start), x -> call("substring", x.get(0),
                    AppliedFunction.infixRun("plus", List.of(x.get(1), new CInteger(1L)))));
        }
        return SqlToPure.strict(Kind.STRING, List.of(s, start, length), x -> call("substring", x.get(0),
                AppliedFunction.infixRun("plus", List.of(x.get(1), new CInteger(1L))), x.get(2)));
    }

    /** {@code coalesce(a, b, ...)}: the first value that is not null; the arguments after one that is never null are
     *  never read. The arguments must share a type in PR1 (Cypher's are any). */
    private static Tx coalesce(CypherAst.Call c, List<Tx> arguments) {
        if (arguments.isEmpty()) {
            throw CypherErrors.invalid("Insufficient parameters for function 'coalesce'", c.pos());
        }
        List<Tx> args = new ArrayList<>();
        Kind kind = Kind.NULL;
        for (Tx a : arguments) {
            if (a.kind() == Kind.NULL) {
                continue;
            }
            if (kind == Kind.NULL) {
                kind = a.kind();
            } else if (kind != a.kind()) {
                if (kind.numeric() && a.kind().numeric()) {
                    kind = arithmeticKind(kind, a.kind());
                } else {
                    throw CypherErrors.unsupported("coalesce over values of different types (" + cypherType(kind)
                            + ", " + cypherType(a.kind()) + ") is not supported yet", c.pos());
                }
            }
            args.add(a);
            if (!a.nullable()) {
                break;
            }
        }
        if (args.isEmpty()) {
            return SqlToPure.nullOf(Kind.STRING);
        }
        if (args.size() == 1) {
            return args.get(0);
        }
        boolean nullable = true;
        for (Tx a : args) {
            nullable &= a.nullable();
        }
        ValueSpecification tail = SqlToPure.valueOf(args.get(args.size() - 1));
        for (int i = args.size() - 2; i >= 0; i--) {
            ValueSpecification v = SqlToPure.valueOf(args.get(i));
            tail = call("if", call("isEmpty", v), thunk(tail), thunk(v));
        }
        if (!nullable) {
            // one that is never null is read as one: the typer types the if's branches [0..1]
            tail = call("toOne", tail);
        }
        return kind == Kind.BOOLEAN ? SqlToPure.booleanValue(tail, nullable) : Tx.value(tail, kind, nullable);
    }

    /** {@code CASE}: the first WHEN that holds gives the value, else the ELSE, else null. */
    private Tx caseExpr(CypherAst.Case c, Scope scope) {
        List<ValueSpecification> tests = new ArrayList<>();
        List<Tx> results = new ArrayList<>();
        Expr operand = c.operand();
        Tx operandValue = operand == null ? null : tx(operand, scope);
        for (CypherAst.When w : c.whens()) {
            if (operandValue != null) {
                Tx wanted = tx(w.condition(), scope);
                tests.add(SqlToPure.truth(equal(operandValue, wanted, w.condition().pos()), "CASE", w.condition()
                        .pos()).vs());
            } else {
                tests.add(condition(w.condition(), scope, "CASE/WHEN"));
            }
            results.add(tx(w.result(), scope));
        }
        Expr otherwise = c.elseResult();
        Tx last = otherwise == null ? Tx.value(SqlToPure.empty(), Kind.NULL, true) : tx(otherwise, scope);
        results.add(last);
        Kind kind = Kind.NULL;
        for (Tx r : results) {
            if (r.kind() == Kind.NULL) {
                continue;
            }
            if (kind == Kind.NULL) {
                kind = r.kind();
            } else if (kind != r.kind()) {
                if (kind.numeric() && r.kind().numeric()) {
                    kind = arithmeticKind(kind, r.kind());
                } else {
                    throw CypherErrors.unsupported("a CASE whose results are of different types (" + cypherType(kind)
                            + ", " + cypherType(r.kind()) + ") is not supported yet", c.pos());
                }
            }
        }
        if (kind == Kind.NULL) {
            return SqlToPure.nullOf(Kind.STRING);
        }
        boolean nullable = last.nullable();
        ValueSpecification vs = last.kind() == Kind.NULL ? SqlToPure.nullOf(kind).vs() : SqlToPure.valueOf(last);
        for (int i = results.size() - 2; i >= 0; i--) {
            Tx r = results.get(i);
            nullable |= r.nullable();
            ValueSpecification v = r.kind() == Kind.NULL ? SqlToPure.nullOf(kind).vs() : SqlToPure.valueOf(r);
            vs = call("if", tests.get(i), thunk(v), thunk(vs));
        }
        return kind == Kind.BOOLEAN ? SqlToPure.booleanValue(vs, nullable) : Tx.value(vs, kind, nullable);
    }

    // ------------------------------------------------------------------ values

    /**
     * A translated expression: a scalar the database computes; a list or a map of KNOWN SHAPE, each element
     * translated (the shape is read from the query's spelling and rearranged here, the elements' values the database
     * computes: the world map's compare-not-compute); or a collection the database computes (a range, a split, a
     * comprehension's filter: a Pure {@code T[*]} of one kind, holding no null).
     */
    private sealed interface Val permits Scalar, ListVal, MapVal, Coll {
    }

    /** A scalar; {@code literal} when it is a literal of the query, {@code constant} then its value (null for
     *  {@code null}), so that a shape of literals is written as one JSON constant. */
    private record Scalar(Tx tx, boolean literal, @com.legend.base.Nullable Object constant) implements Val {
    }

    private record ListVal(List<Val> elements) implements Val {
    }

    private record MapVal(Map<String, Val> entries) implements Val {
    }

    /** A collection the database computes; {@code rowFree} when it reads nothing of the row (literals only). */
    private record Coll(ValueSpecification vs, Kind kind, boolean rowFree) implements Val {
    }

    private static Scalar scalar(Tx tx) {
        return new Scalar(tx, false, null);
    }

    private static Scalar literal(Tx tx, @com.legend.base.Nullable Object constant) {
        return new Scalar(tx, true, constant);
    }

    private static Scalar nullValue() {
        return new Scalar(Tx.value(SqlToPure.empty(), Kind.NULL, true), true, null);
    }

    private static boolean isNull(Val v) {
        return v instanceof Scalar s && s.tx().kind() == Kind.NULL;
    }

    private static @com.legend.base.Nullable Long integerLiteral(Scalar s) {
        return s.literal() && s.constant() instanceof Long n ? n : null;
    }

    /** Whether a value reads nothing of the row: a literal, a shape of literals, a collection of literals. */
    private static boolean rowFree(Val v) {
        return switch (v) {
            case Scalar s -> s.literal();
            case ListVal l -> l.elements().stream().allMatch(CypherToPure::rowFree);
            case MapVal m -> m.entries().values().stream().allMatch(CypherToPure::rowFree);
            case Coll c -> c.rowFree();
        };
    }

    /** Whether a value is a literal or a shape of literals: one the island writes as a JSON constant. */
    private static boolean isLiteral(Val v) {
        return switch (v) {
            case Scalar s -> s.literal();
            case ListVal l -> l.elements().stream().allMatch(CypherToPure::isLiteral);
            case MapVal m -> m.entries().values().stream().allMatch(CypherToPure::isLiteral);
            case Coll c -> false;
        };
    }

    /** The JSON text of a literal value (a float as Java spells it, which JSON reads). */
    private static String json(Val v) {
        return switch (v) {
            case Scalar s -> switch (s.constant()) {
                case null -> "null";
                case String text -> com.legend.json.Json.toCompact(text);
                case Object other -> other.toString();
            };
            case ListVal l -> "[" + String.join(",", l.elements().stream().map(CypherToPure::json).toList()) + "]";
            case MapVal m -> "{" + String.join(",", m.entries().entrySet().stream().map(e ->
                    com.legend.json.Json.toCompact(e.getKey()) + ":" + json(e.getValue())).toList()) + "}";
            case Coll c -> throw new IllegalStateException("not a literal: " + c);
        };
    }

    /**
     * A list or map as a value the database holds: a Variant (C8). A shape of literals is a JSON constant; a shape
     * holding values of the row is built by the database ({@code toVariant} of a collection), within what Pure's
     * collections carry: one kind, no null, two or more elements (Pure reads {@code [x]} as {@code x}); a collection
     * it computes is {@code toVariant} of it; a map holding values of the row is refused by name ({@code newMap}'s
     * Variant is a list of pairs on Postgres).
     */
    private Tx variant(Val v, Pos pos) {
        if (isLiteral(v)) {
            return Tx.value(call("fromJson", new CString(json(v))), Kind.JSON, false);
        }
        return switch (v) {
            case Scalar s -> s.tx();
            case Coll c -> Tx.value(call("toVariant", c.vs()), Kind.JSON, false);
            case ListVal l -> {
                if (l.elements().size() < 2) {
                    throw CypherErrors.unsupported("a list of one value of the row, as a value, is not supported yet"
                            + " (Pure reads [x] as x)", pos);
                }
                List<ValueSpecification> values = new ArrayList<>();
                Kind kind = null;
                for (Val e : l.elements()) {
                    Tx t = element(e, pos);
                    if (kind == null) {
                        kind = t.kind();
                    } else if (kind != t.kind()) {
                        throw CypherErrors.unsupported("a list holding values of different types, as a value, is not"
                                + " supported yet", pos);
                    }
                    values.add(SqlToPure.valueOf(t));
                }
                yield Tx.value(call("toVariant", new PureCollection(values)), Kind.JSON, false);
            }
            case MapVal m -> throw CypherErrors.unsupported("a map holding a value of the row, as a value, is not"
                    + " supported yet (lite's Postgres lowering writes toVariant of newMap as a list of pairs where"
                    + " DuckDB's writes an object; reported, docs/IN_FLIGHT.md 2026-10-15)", pos);
        };
    }

    /** An element of a shape the database builds: a scalar that is never null (a Pure collection holds no empty,
     *  and lite's {@code toOne} asserts on one), or a nested shape's Variant. */
    private Tx element(Val e, Pos pos) {
        if (e instanceof Scalar s) {
            if (s.tx().nullable() || s.tx().kind() == Kind.NULL) {
                throw CypherErrors.unsupported("a list or map holding a value that may be null, as a value, is not"
                        + " supported yet", pos);
            }
            if (s.tx().kind() == Kind.DATE || s.tx().kind() == Kind.DATETIME || s.tx().kind() == Kind.TIME
                    || s.tx().kind() == Kind.ZONED || s.tx().kind() == Kind.ZONED_TIME
                    || s.tx().kind() == Kind.DURATION) {
                throw CypherErrors.unsupported("a list or map holding a " + cypherType(s.tx().kind()) + ", as a"
                        + " value, is not supported yet (the Variant spells it as text)", pos);
            }
            return s.tx();
        }
        return variant(e, pos);
    }

    /** {@code value.name}: a known map's entry (null when it has none), null's null. */
    private Val member(Val target, String name, Pos pos) {
        return switch (target) {
            case MapVal m -> {
                Val v = m.entries().get(name);
                yield v == null ? nullValue() : v;
            }
            case Scalar s when s.tx().kind() == Kind.NULL -> nullValue();
            case Scalar s when s.tx().kind() == Kind.DATE || s.tx().kind() == Kind.DATETIME
                    || s.tx().kind() == Kind.TIME || s.tx().kind() == Kind.ZONED || s.tx().kind() == Kind.ZONED_TIME ->
                    scalar(temporalField(s.tx(), name, pos));
            case Scalar s when s.tx().kind() == Kind.DURATION -> scalar(durationField(s.tx(), name, pos));
            case Scalar s when s.tx().kind() == Kind.JSON -> throw CypherErrors.unsupported("a property of a value"
                    + " the database holds as a Variant is not supported yet", pos);
            case Scalar s -> throw CypherErrors.invalid("Type mismatch: expected Map, Node, Relationship, Point,"
                    + " Duration, Date, Time, LocalTime, LocalDateTime or DateTime but was "
                    + cypherType(s.tx().kind()), pos);
            default -> throw CypherErrors.invalid("Type mismatch: expected Map, Node, Relationship, Point, Duration,"
                    + " Date, Time, LocalTime, LocalDateTime or DateTime but was List<T>", pos);
        };
    }

    /** {@code l = r} over values: lists of different sizes, maps of different keys, a list and a scalar, are never
     *  equal; else the elements compared pairwise (null when a pair's comparison is, unless another's is false). */
    private Tx equalVal(Val l, Val r, Pos pos) {
        if (isNull(l) || isNull(r)) {
            return SqlToPure.nullOf(Kind.BOOLEAN);
        }
        if (l instanceof Coll || r instanceof Coll) {
            throw CypherErrors.unsupported("comparing a list the database computes is not supported yet", pos);
        }
        if (l instanceof Scalar ls && r instanceof Scalar rs) {
            return equal(ls.tx(), rs.tx(), pos);
        }
        if (l instanceof ListVal a && r instanceof ListVal b) {
            if (a.elements().size() != b.elements().size()) {
                return constant(false);
            }
            Tx all = constant(true);
            for (int i = 0; i < a.elements().size(); i++) {
                all = and3(all, equalVal(a.elements().get(i), b.elements().get(i), pos));
            }
            return all;
        }
        if (l instanceof MapVal a && r instanceof MapVal b) {
            if (!a.entries().keySet().equals(b.entries().keySet())) {
                return constant(false);
            }
            Tx all = constant(true);
            for (String k : a.entries().keySet()) {
                all = and3(all, equalVal(java.util.Objects.requireNonNull(a.entries().get(k)),
                        java.util.Objects.requireNonNull(b.entries().get(k)), pos));
            }
            return all;
        }
        // a list, a map and a scalar are values of different types: never equal
        return constant(false);
    }

    /** {@code list + list}, {@code list + value}, {@code value + list}: the elements in a row. */
    private Val concat(Val l, Val r, Pos pos) {
        if (isNull(l) || isNull(r)) {
            return nullValue();
        }
        if (l instanceof Coll || r instanceof Coll) {
            throw CypherErrors.unsupported("+ of a list the database computes is not supported yet", pos);
        }
        if (l instanceof MapVal || r instanceof MapVal) {
            throw CypherErrors.invalid("Type mismatch: expected Float, Integer, String, List<T> or Duration but was Map",
                    pos);
        }
        List<Val> out = new ArrayList<>();
        if (l instanceof ListVal ll) {
            out.addAll(ll.elements());
        } else {
            out.add(l);
        }
        if (r instanceof ListVal rl) {
            out.addAll(rl.elements());
        } else {
            out.add(r);
        }
        return new ListVal(out);
    }

    /** {@code target[index]}: a known list's element by a spelled index (from the end when negative; null beyond
     *  either end), a known map's entry by a spelled key, a computed list's element by the database. */
    private Val index(CypherAst.Index ix, Scope scope) {
        Val target = val(ix.target(), scope);
        Val idx = val(ix.index(), scope);
        if (isNull(target) || isNull(idx)) {
            return nullValue();
        }
        if (!(idx instanceof Scalar is)) {
            throw CypherErrors.invalid("Type mismatch: expected Integer or String but was " + (idx instanceof MapVal
                    ? "Map" : "List<T>"), ix.index().pos());
        }
        Tx i = is.tx();
        return switch (target) {
            case ListVal l -> {
                if (i.kind() != Kind.INTEGER) {
                    throw CypherErrors.invalid("Type mismatch: expected Integer but was " + cypherType(i.kind()),
                            ix.index().pos());
                }
                Long n = integerLiteral(is);
                if (n == null) {
                    throw CypherErrors.unsupported("indexing a list by a value that is not a literal is not supported"
                            + " yet", ix.pos());
                }
                int size = l.elements().size();
                long at = n < 0 ? n + size : n;
                yield at < 0 || at >= size ? nullValue() : l.elements().get((int) at);
            }
            case MapVal m -> {
                if (i.kind() != Kind.STRING) {
                    throw CypherErrors.invalid("Type mismatch: expected String but was " + cypherType(i.kind())
                            + " (a map is indexed by a key)", ix.index().pos());
                }
                if (!is.literal()) {
                    throw CypherErrors.unsupported("indexing a map by a value that is not a literal is not supported"
                            + " yet", ix.pos());
                }
                Val v = m.entries().get((String) java.util.Objects.requireNonNull(is.constant()));
                yield v == null ? nullValue() : v;
            }
            case Coll c -> {
                if (i.kind() != Kind.INTEGER) {
                    throw CypherErrors.invalid("Type mismatch: expected Integer but was " + cypherType(i.kind()),
                            ix.index().pos());
                }
                Long n = integerLiteral(is);
                if (n == null || n < 0) {
                    throw CypherErrors.unsupported("indexing a list the database computes by a value that is not a"
                            + " literal, or from its end, is not supported yet", ix.pos());
                }
                yield scalar(columnLike(call("first", call("drop", c.vs(), new CInteger(n))), c.kind(), true));
            }
            case Scalar s -> {
                if (s.tx().kind() == Kind.JSON) {
                    throw CypherErrors.unsupported("indexing a value the database holds as a Variant is not supported"
                            + " yet", ix.pos());
                }
                throw CypherErrors.invalid("Type mismatch: expected List<T> or Map but was "
                        + cypherType(s.tx().kind()), ix.target().pos());
            }
        };
    }

    /** {@code target[from..to]}: a known list's slice by spelled bounds (from the end when negative, clamped to the
     *  list, empty when from is not before to). */
    private Val slice(CypherAst.Slice sl, Scope scope) {
        Val target = val(sl.target(), scope);
        Val from = sl.from() == null ? null : val(sl.from(), scope);
        Val to = sl.to() == null ? null : val(sl.to(), scope);
        if (isNull(target) || from != null && isNull(from) || to != null && isNull(to)) {
            return nullValue();
        }
        Long a = sliceBound(from, sl.from() == null ? sl.pos() : sl.from().pos());
        Long b = sliceBound(to, sl.to() == null ? sl.pos() : sl.to().pos());
        return switch (target) {
            case ListVal l -> {
                int n = l.elements().size();
                long lo = a == null ? 0 : a < 0 ? a + n : a;
                long hi = b == null ? n : b < 0 ? b + n : b;
                lo = Math.max(0, Math.min(n, lo));
                hi = Math.max(0, Math.min(n, hi));
                yield new ListVal(lo >= hi ? List.of() : List.copyOf(l.elements().subList((int) lo, (int) hi)));
            }
            case Coll c -> throw CypherErrors.unsupported("slicing a list the database computes is not supported yet",
                    sl.pos());
            case MapVal m -> throw CypherErrors.invalid("Type mismatch: expected List<T> but was Map", sl.pos());
            case Scalar s -> throw CypherErrors.invalid("Type mismatch: expected List<T> but was "
                    + cypherType(s.tx().kind()), sl.target().pos());
        };
    }

    private static @com.legend.base.Nullable Long sliceBound(@com.legend.base.Nullable Val v, Pos pos) {
        if (v == null) {
            return null;
        }
        if (!(v instanceof Scalar s) || s.tx().kind() != Kind.INTEGER) {
            throw CypherErrors.invalid("Type mismatch: expected Integer but was " + (v instanceof Scalar s
                    ? cypherType(s.tx().kind()) : v instanceof MapVal ? "Map" : "List<T>"), pos);
        }
        Long n = integerLiteral(s);
        if (n == null) {
            throw CypherErrors.unsupported("a slice bound that is not a literal is not supported yet", pos);
        }
        return n;
    }

    /**
     * {@code all}, {@code any}, {@code none}, {@code single}{@code (x IN list WHERE predicate)}. Over a list of known
     * shape, the predicate is read once per element with {@code x} bound to it and the results combined by Cypher's
     * three-valued rules; over a list the database computes, Pure's {@code forAll} and {@code exists}.
     */
    private Tx quantifier(CypherAst.Quantifier q, Scope scope) {
        Quantifier name = java.util.Objects.requireNonNull(QUANTIFIERS.get(q.name().toLowerCase(Locale.ROOT)),
                q.name());
        Val list = val(q.list(), scope);
        if (isNull(list)) {
            return SqlToPure.nullOf(Kind.BOOLEAN);
        }
        String var = q.variable();
        switch (list) {
            case Scalar s when s.tx().kind() == Kind.JSON -> throw CypherErrors.unsupported("a list predicate over a"
                    + " list the database holds as a Variant is not supported yet", q.pos());
            case Scalar s -> throw CypherErrors.invalid("Type mismatch: expected List<T> but was "
                    + cypherType(s.tx().kind()), q.list().pos());
            case MapVal m -> throw CypherErrors.invalid("Type mismatch: expected List<T> but was Map", q.list().pos());
            case Coll c -> {
                Scope inner = new BoundScope(scope, var, scalar(columnLike(new Variable(var), c.kind(), false)));
                databaseLambdas++;
                Tx pred;
                try {
                    pred = SqlToPure.truth(booleanOperand(q.where(), inner), q.name(), q.where().pos());
                } finally {
                    databaseLambdas--;
                }
                if (pred.nullable()) {
                    throw CypherErrors.unsupported("a list predicate whose condition may be null, over a list the"
                            + " database computes, is not supported yet", q.pos());
                }
                return switch (name) {
                    case ALL -> SqlToPure.booleanValue(call("forAll", c.vs(), lambda(var, pred.vs())), false);
                    case ANY -> SqlToPure.booleanValue(call("exists", c.vs(), lambda(var, pred.vs())), false);
                    case NONE -> SqlToPure.not(SqlToPure.booleanValue(call("exists", c.vs(), lambda(var,
                            pred.vs())), false));
                    case SINGLE -> throw CypherErrors.unsupported("single(...) over a list the database computes is"
                            + " not supported yet", q.pos());
                };
            }
            case ListVal l -> {
                List<Tx> preds = new ArrayList<>();
                for (Val e : l.elements()) {
                    preds.add(SqlToPure.truth(booleanOperand(q.where(), new BoundScope(scope, var, e)), q.name(),
                            q.where().pos()));
                }
                return switch (name) {
                    case ALL -> {
                        Tx all = constant(true);
                        for (Tx p : preds) {
                            all = and3(all, p);
                        }
                        yield all;
                    }
                    case ANY -> any(preds);
                    case NONE -> SqlToPure.not(any(preds));
                    case SINGLE -> single(preds);
                };
            }
        }
    }

    private static Tx any(List<Tx> preds) {
        Tx any = constant(false);
        for (Tx p : preds) {
            any = or3(any, p);
        }
        return any;
    }

    /** {@code single}: true when exactly one element holds and none is unknown; false when two or more hold, or
     *  none does and none is unknown; else null. The database counts the holds and the unknowns. */
    private static Tx single(List<Tx> preds) {
        if (preds.isEmpty()) {
            return constant(false);
        }
        ValueSpecification holds = new CInteger(0L);
        ValueSpecification unknowns = null;
        for (Tx p : preds) {
            ValueSpecification one = call("if", p.vs(), thunk(new CInteger(1L)), thunk(new CInteger(0L)));
            holds = AppliedFunction.infixRun("plus", List.of(holds, one));
            if (p.nullable()) {
                ValueSpecification unknown = call("if", call("or", p.vs(), p.falseWhen()), thunk(new CInteger(0L)),
                        thunk(new CInteger(1L)));
                unknowns = unknowns == null ? unknown : AppliedFunction.infixRun("plus", List.of(unknowns, unknown));
            }
        }
        ValueSpecification exactlyOne = call("equal", holds, new CInteger(1L));
        if (unknowns == null) {
            return Tx.condition(exactlyOne, call("not", exactlyOne), false);
        }
        ValueSpecification noUnknown = call("equal", unknowns, new CInteger(0L));
        return Tx.condition(call("and", exactlyOne, noUnknown), call("or", call("greaterThanEqual", holds,
                new CInteger(2L)), call("and", call("equal", holds, new CInteger(0L)), noUnknown)), true);
    }

    /**
     * {@code [x IN list WHERE predicate | expression]}. Without a WHERE, a known list's elements are each mapped
     * and the list stays known; with one, the database filters (and maps) a collection of one kind.
     */
    private Val comprehension(CypherAst.ListComprehension lc, Scope scope) {
        Val list = val(lc.list(), scope);
        if (isNull(list)) {
            return nullValue();
        }
        String var = lc.variable();
        switch (list) {
            case Scalar s when s.tx().kind() == Kind.JSON -> throw CypherErrors.unsupported("a list comprehension"
                    + " over a list the database holds as a Variant is not supported yet", lc.pos());
            case Scalar s -> throw CypherErrors.invalid("Type mismatch: expected List<T> but was "
                    + cypherType(s.tx().kind()), lc.list().pos());
            case MapVal m -> throw CypherErrors.invalid("Type mismatch: expected List<T> but was Map",
                    lc.list().pos());
            default -> {
            }
        }
        if (list instanceof ListVal l && lc.where() == null) {
            Expr map = lc.map();
            if (map == null) {
                return l;
            }
            List<Val> out = new ArrayList<>();
            for (Val e : l.elements()) {
                out.add(val(map, new BoundScope(scope, var, e)));
            }
            return new ListVal(out);
        }
        Coll c = list instanceof Coll cc ? cc : collection((ListVal) list, lc.pos());
        ValueSpecification vs = c.vs();
        Kind kind = c.kind();
        Set<String> bound = Set.of(var);
        boolean rowFree = c.rowFree();
        Scope inner = new BoundScope(scope, var, scalar(columnLike(new Variable(var), kind, false)));
        Expr where = lc.where();
        databaseLambdas++;
        try {
            return comprehensionBody(lc, inner, vs, kind, rowFree, bound);
        } finally {
            databaseLambdas--;
        }
    }

    private Val comprehensionBody(CypherAst.ListComprehension lc, Scope inner, ValueSpecification collection,
            Kind elementKind, boolean collectionRowFree, Set<String> bound) {
        String var = lc.variable();
        ValueSpecification vs = collection;
        Kind kind = elementKind;
        boolean rowFree = collectionRowFree;
        Expr where = lc.where();
        if (where != null) {
            Tx pred = SqlToPure.truth(booleanOperand(where, inner), "WHERE", where.pos());
            if (pred.nullable()) {
                throw CypherErrors.unsupported("a list comprehension whose condition may be null is not supported"
                        + " yet", lc.pos());
            }
            vs = call("filter", vs, lambda(var, pred.vs()));
            rowFree &= !readsRow(where, bound);
        }
        Expr map = lc.map();
        if (map != null) {
            Val mapped = val(map, inner);
            if (!(mapped instanceof Scalar ms)) {
                throw CypherErrors.unsupported("a list comprehension producing lists or maps is not supported yet",
                        lc.pos());
            }
            if (ms.tx().nullable() || ms.tx().kind() == Kind.NULL) {
                throw CypherErrors.unsupported("a list comprehension producing values that may be null is not"
                        + " supported yet", lc.pos());
            }
            vs = call("map", vs, lambda(var, SqlToPure.valueOf(ms.tx())));
            kind = ms.tx().kind();
            rowFree &= !readsRow(map, bound);
        }
        return new Coll(vs, kind, rowFree);
    }

    /** A list of known shape as the collection the database reads: its elements scalars of one kind, none null. */
    private Coll collection(ListVal l, Pos pos) {
        List<ValueSpecification> values = new ArrayList<>();
        Kind kind = null;
        boolean rowFree = true;
        for (Val e : l.elements()) {
            if (!(e instanceof Scalar s)) {
                throw CypherErrors.unsupported("a list of lists or maps, read by the database, is not supported yet",
                        pos);
            }
            Tx t = s.tx();
            if (t.kind() == Kind.NULL || t.nullable()) {
                throw CypherErrors.unsupported("a list holding a value that may be null, read by the database, is"
                        + " not supported yet", pos);
            }
            if (kind == null) {
                kind = t.kind();
            } else if (kind != t.kind()) {
                throw CypherErrors.unsupported("a list holding values of different types, read by the database, is"
                        + " not supported yet", pos);
            }
            values.add(SqlToPure.valueOf(t));
            rowFree &= s.literal();
        }
        if (kind == null) {
            return new Coll(call("cast", SqlToPure.empty(), SqlToPure.typeArg("Integer")), Kind.INTEGER, true);
        }
        return new Coll(new PureCollection(values), kind, rowFree);
    }

    /** Whether an expression reads a variable other than those bound within it. */
    private static boolean readsRow(Expr e, Set<String> bound) {
        return switch (e) {
            case CypherAst.Var v -> !bound.contains(v.name());
            case CypherAst.Quantifier q -> readsRow(q.list(), bound) || readsRow(q.where(), with(bound,
                    q.variable()));
            case CypherAst.ListComprehension lc -> readsRow(lc.list(), bound)
                    || lc.where() != null && readsRow(lc.where(), with(bound, lc.variable()))
                    || lc.map() != null && readsRow(lc.map(), with(bound, lc.variable()));
            default -> children(e).stream().anyMatch(c -> readsRow(c, bound));
        };
    }

    private static Set<String> with(Set<String> bound, String name) {
        Set<String> out = new HashSet<>(bound);
        out.add(name);
        return out;
    }

    // ------------------------------------------------------------------ temporal values

    /**
     * Cypher's temporal values (docs/CYPHER_STUDY_2026_10_15.md §12.6, §12.7): a {@code date} is Pure's StrictDate, a
     * {@code localdatetime} Pure's DateTime (the database's DATE and TIMESTAMP, to the microsecond) and a
     * {@code localtime} Pure's StrictTime (the database's TIME); a zoned {@code datetime} or {@code time} (no Pure
     * type carries a zone) and a {@code duration} (no Pure type) are refused by name. The database computes every
     * value: a date from its components is Pure's {@code date(y, m, d[, h, mi, s])}, a week, quarter or ordinal
     * date is spelled with {@code adjust} from the year's first days, a field is the native that reads it, a
     * truncation the native that gives the first day or the components recomposed. Pure's date-time natives read
     * a time of day through the date-time at it on 1970-01-01 (lite's {@code atTime}, the date a token the
     * database never sees as a value) and {@code timeOf} takes the time back.
     */
    private enum TemporalFamily {
        DATE("date"), LOCALDATETIME("localdatetime"), DATETIME("datetime"), TIME("time"), LOCALTIME("localtime"),
        DURATION("duration");

        final String cypher;

        TemporalFamily(String cypher) {
            this.cypher = cypher;
        }
    }

    /** What a temporal family's namespace names: the constructor, {@code truncate}, the clock reads. */
    private enum TemporalMember {
        CONSTRUCT(""), TRUNCATE("truncate"), REALTIME("realtime"), STATEMENT("statement"), TRANSACTION("transaction"),
        FROM_EPOCH("fromepoch"), FROM_EPOCH_MILLIS("fromepochmillis"), BETWEEN("between"), IN_MONTHS("inmonths"),
        IN_DAYS("indays"), IN_SECONDS("inseconds");

        final String cypher;

        TemporalMember(String cypher) {
            this.cypher = cypher;
        }
    }

    /** A component of a temporal value's map, by its Cypher key in lower case (the keys are case-insensitive). */
    private enum Component {
        YEAR("year"), MONTH("month"), DAY("day"), WEEK("week"), DAY_OF_WEEK("dayofweek"), QUARTER("quarter"),
        DAY_OF_QUARTER("dayofquarter"), ORDINAL_DAY("ordinalday"), HOUR("hour"), MINUTE("minute"), SECOND("second"),
        MILLISECOND("millisecond"), MICROSECOND("microsecond"), NANOSECOND("nanosecond"), DATE("date"),
        DATETIME("datetime"), TIME("time"), TIMEZONE("timezone");

        final String cypher;

        Component(String cypher) {
            this.cypher = cypher;
        }

        boolean time() {
            return this == HOUR || this == MINUTE || this == SECOND || this == MILLISECOND || this == MICROSECOND
                    || this == NANOSECOND;
        }
    }

    /** A field of a temporal value ({@code d.weekYear}), by its Cypher name (case-sensitive, as properties are). */
    private enum TemporalField {
        YEAR("year"), QUARTER("quarter"), MONTH("month"), WEEK("week"), WEEK_YEAR("weekYear"), DAY("day"),
        ORDINAL_DAY("ordinalDay"), WEEK_DAY("weekDay"), DAY_OF_QUARTER("dayOfQuarter"), HOUR("hour"), MINUTE("minute"),
        SECOND("second"), MILLISECOND("millisecond"), MICROSECOND("microsecond"), NANOSECOND("nanosecond"),
        TIMEZONE("timezone"), OFFSET("offset"), OFFSET_MINUTES("offsetMinutes"), OFFSET_SECONDS("offsetSeconds"),
        EPOCH_SECONDS("epochSeconds"), EPOCH_MILLIS("epochMillis");

        final String cypher;

        TemporalField(String cypher) {
            this.cypher = cypher;
        }

        boolean time() {
            return this == HOUR || this == MINUTE || this == SECOND || this == MILLISECOND || this == MICROSECOND
                    || this == NANOSECOND;
        }

        /** A field only a zoned date-time has. */
        boolean zoned() {
            return this == TIMEZONE || this == OFFSET || this == OFFSET_MINUTES || this == OFFSET_SECONDS
                    || this == EPOCH_SECONDS || this == EPOCH_MILLIS;
        }
    }

    /** A truncation unit, by its Cypher name in lower case. */
    private enum TruncateUnit {
        MILLENNIUM("millennium"), CENTURY("century"), DECADE("decade"), YEAR("year"), WEEK_YEAR("weekyear"),
        QUARTER("quarter"), MONTH("month"), WEEK("week"), DAY("day"), HOUR("hour"), MINUTE("minute"), SECOND("second"),
        MILLISECOND("millisecond"), MICROSECOND("microsecond");

        final String cypher;

        TruncateUnit(String cypher) {
            this.cypher = cypher;
        }

        boolean time() {
            return this == HOUR || this == MINUTE || this == SECOND || this == MILLISECOND || this == MICROSECOND;
        }
    }

    /** Neo4j's name of a time unit in its truncation error (java.time's ChronoUnit). */
    private static String chronoName(TruncateUnit unit) {
        return unit == TruncateUnit.MILLISECOND ? "Millis" : unit == TruncateUnit.MICROSECOND ? "Micros"
                : capitalized(unit.cypher) + "s";
    }

    private static <E extends Enum<E>> Map<String, E> byName(E[] values, java.util.function.Function<E, String> name) {
        Map<String, E> out = new LinkedHashMap<>();
        for (E e : values) {
            out.put(name.apply(e), e);
        }
        return java.util.Collections.unmodifiableMap(out);
    }

    private static final Map<String, TemporalFamily> TEMPORAL_FAMILIES = byName(TemporalFamily.values(),
            f -> f.cypher);
    private static final Map<String, TemporalMember> TEMPORAL_MEMBERS = byName(TemporalMember.values(),
            m -> m.cypher);
    private static final Map<String, Component> COMPONENTS = byName(Component.values(), c -> c.cypher);
    private static final Map<String, TemporalField> TEMPORAL_FIELDS = byName(TemporalField.values(), f -> f.cypher);
    private static final Map<String, TruncateUnit> TRUNCATE_UNITS = byName(TruncateUnit.values(), u -> u.cypher);

    private static final String DURATION_UNIT = "meta::pure::functions::date::DurationUnit";

    /** Lite's own natives for a time of day ({@code Pure.Lite.TIME_OF}, {@code Pure.Lite.AT_TIME}; the protocol
     *  layer reads no catalog, AGENTS invariant 7b, so their names are spelled here): the time of day of a date-time,
     *  and the date-time at a time of a date. */
    private static final String TIME_OF = "meta::legend::lite::timeOf";
    private static final String AT_TIME = "meta::legend::lite::atTime";

    private static ValueSpecification days() {
        return new EnumValue(DURATION_UNIT, "DAYS");
    }

    private static ValueSpecification unit(String name) {
        return new EnumValue(DURATION_UNIT, name);
    }

    /** A time of day read as the date-time at it on 1970-01-01: the day Pure's date-time natives (the fields, the
     *  truncations, the comparisons) read a time through; the date is a token, the database never sees it as a
     *  value. */
    private static ValueSpecification atEpoch(ValueSpecification time) {
        return call(AT_TIME, epoch(), time);
    }

    private static Tx atEpoch(Tx time) {
        if (nanos(time)) {
            // a nanosecond time of day is the date-time at it on the epoch day already
            return new Tx(time.vs(), Kind.DATETIME, time.nullable(), time.whenFalse(), time.detail(), time.guard(),
                    time.body());
        }
        return SqlToPure.strict(Kind.DATETIME, List.of(time), x -> atEpoch(x.get(0)));
    }

    private static ValueSpecification epoch() {
        return new com.legend.protocol.spec.CDate(new com.legend.values.PureDateLiteral.StrictDate(1970, 1, 1),
                "1970-01-01", null);
    }

    /** Lite's own natives for a named zone ({@code Pure.Lite.INSTANT_IN}, {@code Pure.Lite.LOCAL_IN}): the instant
     *  a wall time names in a zone, and the wall time of an instant in a zone, both the database's. */
    private static final String INSTANT_IN = "meta::legend::lite::instantIn";
    private static final String LOCAL_IN = "meta::legend::lite::localIn";

    /**
     * A zoned date-time's zone, as the translator carries it beside the instant (the study's §12.8): an offset is
     * its sign and its hours, minutes and seconds as written, which the translator spells into {@code adjust} steps
     * (the database computes the instant); a named zone is its name, which the database reads. {@code Z}, {@code
     * UTC} and a zero offset are the UTC offset, as Neo4j prints them.
     */
    private record Zone(String written, int sign, int hours, int minutes, int seconds,
            @com.legend.base.Nullable String name) implements com.legend.protocol.sql.InputDialect.TypeDetail {

        static final Zone UTC = new Zone("Z", 1, 0, 0, 0, null);

        boolean named() {
            return name != null;
        }

        boolean zero() {
            return name == null && hours == 0 && minutes == 0 && seconds == 0;
        }

        /** The offset as Neo4j prints it ({@code +01:00}, {@code Z}, {@code +02:05:59}). */
        String offsetText() {
            if (zero()) {
                return "Z";
            }
            String text = (sign < 0 ? "-" : "+") + two(hours) + ":" + two(minutes);
            return seconds == 0 ? text : text + ":" + two(seconds);
        }

        private static String two(int n) {
            return (n < 10 ? "0" : "") + n;
        }
    }

    /** A zone as Cypher writes one: {@code Z}, an offset {@code ±hh[:mm[:ss]]} (the colons optional in a text, as
     *  Neo4j reads one; required in a component), or a region's name ({@code Europe/Stockholm}); anything else is
     *  Neo4j's error. */
    private static Zone zone(String text, boolean inText, Pos pos) {
        if (text.equals("Z") || text.equals("z")) {
            return Zone.UTC;
        }
        if (text.startsWith("+") || text.startsWith("-")) {
            int sign = text.startsWith("-") ? -1 : 1;
            String rest = text.substring(1).replace(":", "");
            boolean colons = inText || text.length() == 3 || text.charAt(3) == ':';
            if (colons && (rest.length() == 2 || rest.length() == 4 || rest.length() == 6)
                    && digits(rest, 0, rest.length())) {
                int h = Integer.parseInt(rest.substring(0, 2));
                int m = rest.length() >= 4 ? Integer.parseInt(rest.substring(2, 4)) : 0;
                int s = rest.length() == 6 ? Integer.parseInt(rest.substring(4, 6)) : 0;
                if (h <= 18 && m <= 59 && s <= 59) {
                    return new Zone(text, sign, h, m, s, null);
                }
            }
            throw CypherErrors.invalid("Invalid value for TimeZone: Text '" + text + "' could not be parsed at"
                    + " index 0", pos);
        }
        if (text.equals("UTC") || text.equals("GMT")) {
            return Zone.UTC;
        }
        boolean name = !text.isEmpty();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (!Character.isLetterOrDigit(c) && c != '/' && c != '_' && c != '-' && c != '+') {
                name = false;
            }
        }
        if (!name || !text.contains("/")) {
            throw CypherErrors.invalid("Invalid value for TimeZone: Text '" + text + "' could not be parsed at"
                    + " index 0", pos);
        }
        // a region the database does not know is its error (Neo4j's names the text); the island does not check
        // the name here
        return new Zone(text, 1, 0, 0, 0, text);
    }

    /** What a translation carries beside its value: the zone of a zoned value (§12.8, §12.11) and whether it is of
     *  nanosecond precision (§12.12: the database's TIMESTAMP_NS, every operation on it spelled over the nanosecond
     *  epoch, which DuckDB keeps and a microsecond engine refuses by name). */
    private record Carried(@com.legend.base.Nullable Zone zone, boolean nanos)
            implements com.legend.protocol.sql.InputDialect.TypeDetail {
    }

    /** A node carried through a projection as columns (the study's §12.14): its labels' JSON text in one column
     *  and each property of its class (of every class a label-less node may be, null where a class lacks it) in its
     *  own, so the clauses after read {@code n.prop} as a typed column; the node is a Variant ({@link #nodeJson})
     *  only when returned or used as a value. The node's own column is virtual: none of the relation bears its
     *  name. */
    private record NodeShape(String labels, Map<String, NodeProp> props, boolean absent)
            implements com.legend.protocol.sql.InputDialect.TypeDetail {
        NodeShape(String labels, Map<String, NodeProp> props) {
            this(labels, props, false);
        }
    }

    /** One property of a carried node: its column, its kind, whether it may be null. */
    private record NodeProp(String column, Kind kind, boolean nullable) {
    }

    /** The mark of a node's JSON value ({@link #nodeJson}): what DISTINCT, ORDER BY and a grouping refuse by name
     *  (Neo4j tells nodes apart by identity, which the JSON does not carry). */
    private record NodeValue() implements com.legend.protocol.sql.InputDialect.TypeDetail {
    }

    /** Whether a value is a node's JSON, or a column holding one. */
    private static boolean isNode(@com.legend.base.Nullable Tx t) {
        return t != null && t.detail() instanceof NodeValue;
    }

    private static @com.legend.base.Nullable Zone zoneOf(Tx t) {
        return t.detail() instanceof Carried c ? c.zone() : null;
    }

    /** Whether a value is of nanosecond precision. */
    private static boolean nanos(Tx t) {
        return t.detail() instanceof Carried c && c.nanos();
    }

    /** {@code t} marked of nanosecond precision, or not (its zone kept). */
    private static Tx withNanos(Tx t, boolean nanos) {
        if (!nanos && !(t.detail() instanceof Carried)) {
            return t;
        }
        return new Tx(t.vs(), t.kind(), t.nullable(), t.whenFalse(), new Carried(zoneOf(t), nanos), t.guard(),
                t.body());
    }

    // ------------------------------------------------------------------ nanoseconds (the study's §12.12)

    /** The nanoseconds since the epoch, the database's ({@code dateDiff} with {@code NANOSECONDS}: exact where the
     *  engine keeps nanoseconds, refused by name by a microsecond engine). */
    private static ValueSpecification epochNanos(ValueSpecification x) {
        return call("dateDiff", epochInstant(), x, unit("NANOSECONDS"));
    }

    /** {@code moved}, a microsecond result of a calendar step or a zone conversion of {@code source}, with the
     *  source's sub-microsecond digits put back (an interval step and a zone conversion keep microseconds). */
    private static ValueSpecification remainderKept(ValueSpecification moved, ValueSpecification source) {
        return call("adjust", moved, integer(call("rem", epochNanos(source), new CInteger(1000L))),
                unit("NANOSECONDS"));
    }

    /** The time of day of a nanosecond date-time, as the date-time at it on the epoch day. */
    private static ValueSpecification timeOfNanos(ValueSpecification dt) {
        return call("adjust", epochInstant(), integer(call("dateDiff", call("datePart", dt), dt,
                unit("NANOSECONDS"))), unit("NANOSECONDS"));
    }

    /** The time of day of a date-time (Pure's StrictTime; of nanosecond precision, the date-time at it on the epoch
     *  day). */
    private static Tx timeOf(Tx dt) {
        boolean nanos = nanos(dt);
        return withNanos(SqlToPure.strict(Kind.TIME, List.of(dt), x -> nanos ? timeOfNanos(x.get(0))
                : call(TIME_OF, x.get(0))), nanos);
    }

    /** The database's TIMESTAMP_NS holds the years 1678 to 2261 (DuckDB 1.4.4, measured): a nanosecond value
     *  whose written year is outside them is refused by name (one of the row is the database's error). */
    private static void nanosRange(long year, Pos pos) {
        if (year < 1678 || year > 2261) {
            throw CypherErrors.unsupported("a nanosecond value of the year " + year + " (the database's"
                    + " nanosecond timestamps hold 1678 to 2261) is not supported yet", pos);
        }
    }

    /** A date-time literal's sub-microsecond digits as nanoseconds: 0 when it has none. */
    private static long subMicro(String fraction) {
        return fraction.length() <= 6 ? 0 : Long.parseLong((fraction.substring(6) + "000").substring(0, 3));
    }

    /** {@code lit}, a literal to the microsecond, moved by the sub-microsecond digits the text wrote: the database's
     *  nanosecond step over it (the translator splits the written digits, computing nothing). */
    private static Tx finer(ValueSpecification lit, long subMicro, Kind kind) {
        return withNanos(Tx.value(call("adjust", lit, new CInteger(subMicro), unit("NANOSECONDS")), kind, false),
                true);
    }

    /** {@code t}, an instant, as a zoned date-time of zone {@code z}. */
    private static Tx zoned(Tx t, Zone z) {
        return new Tx(t.vs(), Kind.ZONED, t.nullable(), t.whenFalse(), new Carried(z, nanos(t)), t.guard(),
                t.body());
    }

    /** The wall time of a zoned date-time in its zone (Pure's DateTime): the instant shifted by the offset, or the
     *  database's reading of it in the named zone (a nanosecond value's sub-microsecond digits put back after it). */
    private static Tx localOf(Tx zoned, Zone z) {
        boolean nanos = nanos(zoned);
        return withNanos(SqlToPure.strict(Kind.DATETIME, List.of(zoned), x -> z.named()
                ? inZone(LOCAL_IN, x.get(0), z, nanos) : shifted(x.get(0), z, 1, nanos)), nanos);
    }

    /** The instant a wall time names in a zone, as a zoned date-time of that zone. */
    private static Tx instantOf(Tx local, Zone z) {
        boolean nanos = nanos(local);
        return zoned(withNanos(SqlToPure.strict(Kind.DATETIME, List.of(local), x -> z.named()
                ? inZone(INSTANT_IN, x.get(0), z, nanos) : shifted(x.get(0), z, -1, nanos)), nanos), z);
    }

    /** The database's reading of {@code x} in a named zone ({@code instantIn}, {@code localIn}: microseconds), a
     *  nanosecond value's sub-microsecond digits put back. */
    private static ValueSpecification inZone(String fn, ValueSpecification x, Zone z, boolean nanos) {
        ValueSpecification converted = call(fn, x, new CString(java.util.Objects.requireNonNull(z.name())));
        return nanos ? remainderKept(converted, x) : converted;
    }

    /** A date-time shifted by an offset's parts ({@code direction} 1 towards the wall time, -1 towards the
     *  instant), each part an {@code adjust} step the database computes; a zero part is no step. A nanosecond
     *  value steps over the nanosecond epoch (an interval step keeps microseconds). */
    private static ValueSpecification shifted(ValueSpecification x, Zone z, int direction, boolean nanos) {
        ValueSpecification out = x;
        int sign = z.sign * direction;
        if (z.hours != 0) {
            out = nanos ? call("adjust", out, integer(times(3_600_000_000_000L, new CInteger((long) sign
                    * z.hours))), unit("NANOSECONDS"))
                    : call("adjust", out, new CInteger((long) sign * z.hours), unit("HOURS"));
        }
        if (z.minutes != 0) {
            out = nanos ? call("adjust", out, integer(times(60_000_000_000L, new CInteger((long) sign
                    * z.minutes))), unit("NANOSECONDS"))
                    : call("adjust", out, new CInteger((long) sign * z.minutes), unit("MINUTES"));
        }
        if (z.seconds != 0) {
            out = nanos ? call("adjust", out, integer(times(1_000_000_000L, new CInteger((long) sign
                    * z.seconds))), unit("NANOSECONDS"))
                    : call("adjust", out, new CInteger((long) sign * z.seconds), unit("SECONDS"));
        }
        return out;
    }

    /** A zoned date-time's wall time, for the constructors that read a date-time's fields (Neo4j reads a zoned value
     *  by its wall time); any other value stays. */
    private Val unzoned(Val v, Pos pos) {
        if (v instanceof Scalar s && s.tx().kind() == Kind.ZONED) {
            return scalar(localOf(s.tx(), zoneKnown(s.tx(), pos)));
        }
        if (v instanceof Scalar s && s.tx().kind() == Kind.ZONED_TIME) {
            return scalar(wallTimeOf(s.tx(), zoneKnown(s.tx(), pos)));
        }
        return v;
    }

    // ------------------------------------------------------------------ zoned times of day (the study's §12.11)

    /** A zoned time of day (Cypher's {@code time}, Neo4j's OffsetTime): carried as its instant on the epoch day
     *  (Pure's DateTime; the day before or after when the offset crosses midnight, so two such times order and
     *  compare as Neo4j's do), the offset beside, as a zoned date-time is carried. */
    private static Tx zonedTime(Tx instant, Zone z) {
        return new Tx(instant.vs(), Kind.ZONED_TIME, instant.nullable(), instant.whenFalse(),
                new Carried(z, nanos(instant)), instant.guard(), instant.body());
    }

    /** A zoned time of day's instant on the epoch day, as the date-time it is (no zone beside). */
    private static Tx instantOfTime(Tx zt) {
        return new Tx(zt.vs(), Kind.DATETIME, zt.nullable(), zt.whenFalse(), new Carried(null, nanos(zt)),
                zt.guard(), zt.body());
    }

    /** The wall time of a zoned time of day (Pure's StrictTime; of nanosecond precision, the date-time at it on the
     *  epoch day): the instant shifted by the offset. */
    private static Tx wallTimeOf(Tx zt, Zone z) {
        boolean nanos = nanos(zt);
        return withNanos(SqlToPure.strict(Kind.TIME, List.of(zt), x -> nanos ? shifted(x.get(0), z, 1, true)
                : call(TIME_OF, shifted(x.get(0), z, 1, false))), nanos);
    }

    /** The wall time of a zoned time of day as the date-time at it on the epoch day: what its fields and its
     *  truncation read. */
    private static Tx wallAtEpoch(Tx zt, Zone z) {
        boolean nanos = nanos(zt);
        return withNanos(SqlToPure.strict(Kind.DATETIME, List.of(zt), x -> shifted(x.get(0), z, 1, nanos)), nanos);
    }

    /** A zoned time of day from its wall time (Pure's StrictTime) in an offset: the instant on the epoch day. */
    private static Tx zonedTimeOf(Tx wall, Zone z) {
        boolean nanos = nanos(wall);
        return zonedTime(withNanos(SqlToPure.strict(Kind.DATETIME, List.of(wall), x -> shifted(nanos ? x.get(0)
                : atEpoch(x.get(0)), z, -1, nanos)), nanos), z);
    }

    /** A time of day's zone: an offset. A region's name is refused by name: Neo4j takes its offset at the current
     *  date, a value of the call, not of the data. */
    private static Zone timeZone(String text, boolean inText, Pos pos) {
        Zone z = zone(text, inText, pos);
        if (z.named()) {
            throw CypherErrors.unsupported("a named zone for a time of day (" + text + ": Neo4j takes its offset at"
                    + " the current date, a value of the call, not of the data) is not supported yet", pos);
        }
        return z;
    }

    /** {@code time(x)}: from a map of components (an offset among them), a text with its offset, a time of day or
     *  a local date-time (the default zone, UTC), a zoned date-time (its wall time, its offset), or another zoned
     *  time. */
    private Val zonedTimeFrom(CypherAst.Call c, Scope scope) {
        if (c.args().isEmpty()) {
            throw CypherErrors.unsupported("the function " + c.name() + "() (the current time: a value of the call,"
                    + " not of the data) is not supported yet", c.pos());
        }
        if (c.args().size() > 1) {
            throw CypherErrors.invalid("Too many parameters for function '" + c.name() + "'", c.pos());
        }
        Val a = val(c.args().get(0), scope);
        return switch (a) {
            case Scalar s when s.tx().kind() == Kind.NULL -> nullValue();
            case Scalar s when s.tx().kind() == Kind.STRING -> zonedTimeFromText(c, s);
            case Scalar s when s.tx().kind() == Kind.ZONED_TIME -> a;
            case Scalar s when s.tx().kind() == Kind.TIME -> scalar(zonedTimeOf(s.tx(), Zone.UTC));
            case Scalar s when s.tx().kind() == Kind.DATETIME -> scalar(zonedTimeOf(timeOf(s.tx()), Zone.UTC));
            case Scalar s when s.tx().kind() == Kind.ZONED -> {
                Zone z = zoneKnown(s.tx(), c.pos());
                if (z.named()) {
                    throw CypherErrors.unsupported("the time of a date-time in a named zone (its offset is that"
                            + " date's) is not supported yet", c.pos());
                }
                yield scalar(zonedTimeOf(timeOf(localOf(s.tx(), z)), z));
            }
            case Scalar s when s.tx().kind() == Kind.DATE -> {
                if (s.tx().vs() instanceof com.legend.protocol.spec.CDate d
                        && d.value() instanceof com.legend.values.PureDateLiteral.StrictDate sd) {
                    throw CypherErrors.invalid("Cannot get the time of: " + sd.toEngineString(), c.pos());
                }
                throw CypherErrors.unsupported("the time of a date of the row (Neo4j's error names the date) is not"
                        + " supported yet", c.pos());
            }
            case Scalar s -> throw CypherErrors.invalid("Type mismatch: expected Map, String, Time, LocalTime,"
                    + " LocalDateTime or DateTime but was " + cypherType(s.tx().kind()), c.pos());
            case MapVal m -> zonedTimeFromComponents(c, m);
            default -> throw CypherErrors.invalid("Type mismatch: expected Map, String, Time, LocalTime,"
                    + " LocalDateTime or DateTime but was List<T>", c.pos());
        };
    }

    /** A zoned time of day from a text: the time forms, then {@code Z} or an offset (none: the default zone, UTC);
     *  a region's name in brackets is Neo4j's error; a text of the row is refused by name. */
    private Val zonedTimeFromText(CypherAst.Call c, Scalar s) {
        if (!s.literal()) {
            throw CypherErrors.unsupported("a zoned time text of the row (its offset read by the database) is not"
                    + " supported yet", c.pos());
        }
        String text = (String) java.util.Objects.requireNonNull(s.constant());
        TemporalText tt = TemporalText.read(text, false, true);
        if (tt == null) {
            throw CypherErrors.invalid("Text cannot be parsed to a Time: \"" + text + "\"", c.pos());
        }
        if (tt.zoneName() != null) {
            throw CypherErrors.invalid("Using a named time zone e.g. [UTC] is not valid for a time without a date."
                    + " Instead, use a specific time zone string e.g. +00:00.", c.pos());
        }
        Zone z = tt.zoneText() == null ? Zone.UTC : zone(tt.zoneText(), true, c.pos());
        return scalar(zonedTimeOf(timeValue(timeLiteralOf(tt)), z));
    }

    /** {@code time({...})}: the wall time the components spell, in the written offset, else a zoned {@code time} or
     *  {@code datetime} base's, else UTC. A zoned base is read by its wall time; {@code {time: t, timezone: z}}
     *  alone converts (the instant kept); with fields written over it the fields apply to the wall time in the
     *  base's zone, then the result is converted to the written zone. */
    private Val zonedTimeFromComponents(CypherAst.Call c, MapVal m) {
        Zone written = null;
        Tx zonedBase = null;
        Zone baseZone = null;
        Map<String, Val> rest = new LinkedHashMap<>();
        for (Map.Entry<String, Val> e : m.entries().entrySet()) {
            Component k = COMPONENTS.get(e.getKey().toLowerCase(Locale.ROOT));
            if (k == Component.TIMEZONE) {
                if (!(e.getValue() instanceof Scalar s) || !s.literal() || !(s.constant() instanceof String zt)) {
                    throw CypherErrors.unsupported("a time zone that is not a spelled text is not supported yet",
                            c.pos());
                }
                written = timeZone(zt, false, c.pos());
                continue;
            }
            if (k == Component.TIME && e.getValue() instanceof Scalar s && s.tx().kind() == Kind.NULL) {
                throw CypherErrors.invalid("Cannot construct time from: NO_VALUE", c.pos());
            }
            if (k == Component.TIME && e.getValue() instanceof Scalar s
                    && (s.tx().kind() == Kind.ZONED_TIME || s.tx().kind() == Kind.ZONED)) {
                zonedBase = s.tx();
                baseZone = zoneKnown(s.tx(), c.pos());
                if (baseZone.named()) {
                    throw CypherErrors.unsupported("the time of a date-time in a named zone (its offset is that"
                            + " date's) is not supported yet", c.pos());
                }
            }
            rest.put(e.getKey(), e.getValue());
        }
        if (rest.isEmpty()) {
            throw CypherErrors.unsupported("the function " + c.name() + "({timezone: ...}) (the current time in a"
                    + " zone: a value of the call, not of the data) is not supported yet", c.pos());
        }
        if (zonedBase != null && written != null && rest.size() == 1) {
            // the conversion: the instant kept, the zone changed
            Tx instant = zonedBase.kind() == Kind.ZONED_TIME ? instantOfTime(zonedBase)
                    : instantOfTime(zonedTimeOf(timeOf(localOf(zonedBase, java.util.Objects.requireNonNull(baseZone))),
                            baseZone));
            return scalar(zonedTime(instant, written));
        }
        Val wall = timeFromComponents(c, new MapVal(rest));
        if (!(wall instanceof Scalar ws) || ws.tx().kind() == Kind.NULL) {
            return wall;
        }
        Zone in = baseZone != null ? baseZone : written != null ? written : Zone.UTC;
        Tx zt = zonedTimeOf(ws.tx(), in);
        return scalar(written != null && baseZone != null ? zonedTime(instantOfTime(zt), written) : zt);
    }

    /** The zone of a zoned date-time the translator carries; a zoned value whose zone it does not carry (one of a
     *  list, an aggregate's) is refused by name: the island keeps the instant. */
    private static Zone zoneKnown(Tx t, Pos pos) {
        Zone z = zoneOf(t);
        if (z == null) {
            throw CypherErrors.unsupported("a zoned date-time whose zone is not carried here (a value of a list, an"
                    + " aggregate's, or one computed) read in its zone is not supported yet (the island keeps the"
                    + " instant)", pos);
        }
        return z;
    }

    // ------------------------------------------------------------------ durations (the study's §12.9)

    /** A duration's four slots, Neo4j's model: months and days (calendar units, kept apart), seconds and
     *  nanoseconds (the nanoseconds in {@code [0, 1e9)}, the seconds floored). */
    private static final List<String> DURATION_SLOTS = List.of("months", "days", "seconds", "nanoseconds");

    private static final long NANOS = 1_000_000_000L;

    /** The model's Duration class: the one class named {@code Duration} declaring the four slots as Integer[1] (a
     *  PROGRAM of the model's, not a platform type: the island carries a duration as an instance of it, the
     *  database's struct); refused by name when the model has none. */
    private String durationClass(Pos pos) {
        List<String> classes = durationClasses();
        String declaration = "Class graph::Duration { months: Integer[1]; days: Integer[1]; seconds: Integer[1];"
                + " nanoseconds: Integer[1]; }";
        if (classes.size() != 1) {
            throw CypherErrors.unsupported("a duration needs one class named Duration in the model, with months,"
                    + " days, seconds and nanoseconds as Integer[1] (the island carries a duration as an instance"
                    + " of it; declare `" + declaration + "`): the model has " + (classes.isEmpty() ? "none"
                    : classes.size() + " (" + classes + ")"), pos);
        }
        return classes.get(0);
    }

    /** An instance of the Duration class from its four slots (each an Integer the database computes). */
    private ValueSpecification duration(String cls, ValueSpecification months, ValueSpecification days,
            ValueSpecification seconds, ValueSpecification nanos) {
        List<com.legend.protocol.spec.NewInstance.KeyBinding> bindings = new ArrayList<>();
        ValueSpecification[] values = {months, days, seconds, nanos};
        for (int i = 0; i < DURATION_SLOTS.size(); i++) {
            bindings.add(new com.legend.protocol.spec.NewInstance.KeyBinding(DURATION_SLOTS.get(i),
                    new com.legend.protocol.spec.KeyExpression(integer(values[i]), false, false)));
        }
        return new com.legend.protocol.spec.NewInstance(cls, List.of(), bindings);
    }

    /** A slot read, cast to Pure's Integer (the database's BIGINT: a struct built of literals holds 32-bit fields on
     *  DuckDB, and 3661 seconds times a billion overflowed them, measured). */
    private static ValueSpecification slot(ValueSpecification d, String name) {
        return call("cast", new AppliedProperty(d, name), SqlToPure.typeArg("Integer"));
    }

    private static ValueSpecification lit(long n) {
        return new CInteger(n);
    }

    private static ValueSpecification plus(ValueSpecification... terms) {
        List<ValueSpecification> list = new ArrayList<>();
        for (ValueSpecification t : terms) {
            if (!(t instanceof CInteger c && c.value().longValue() == 0)) {
                list.add(t);
            }
        }
        return list.isEmpty() ? lit(0) : list.size() == 1 ? list.get(0) : AppliedFunction.infixRun("plus", list);
    }

    private static ValueSpecification times(long k, ValueSpecification x) {
        return x instanceof CInteger c && c.value().longValue() == 0 ? lit(0)
                : k == 1 ? x : AppliedFunction.infixRun("times", List.of(lit(k), x));
    }

    private static ValueSpecification minus(ValueSpecification a, ValueSpecification b) {
        return AppliedFunction.infixRun("minus", List.of(a, b));
    }

    /** The model's classes named Duration declaring the four slots (the platform's own
     *  meta::pure::functions::date::Duration, one number and one unit, shares the simple name: the four slots tell
     *  the model's class apart): the duration value's class, never a node. */
    private List<String> durationClasses() {
        List<String> classes = new ArrayList<>();
        for (String cls : model.classesOf("Duration")) {
            boolean slots = true;
            for (String slot : DURATION_SLOTS) {
                Property p = property(cls, slot);
                if (p == null || !p.type().equals("Integer") || p.many() || p.optional()) {
                    slots = false;
                }
            }
            if (slots) {
                classes.add(cls);
            }
        }
        return classes;
    }

    /** The whole seconds of a nanosecond count, floored (Neo4j keeps the nanoseconds in {@code [0, 1e9)}). */
    private static ValueSpecification wholeSeconds(ValueSpecification nanos) {
        return call("floor", call("divide", nanos, lit(NANOS)));
    }

    /** A duration from its raw seconds and nanoseconds: the nanoseconds carried into the seconds, floored. */
    private ValueSpecification normalized(String cls, ValueSpecification months, ValueSpecification days,
            ValueSpecification seconds, ValueSpecification nanos) {
        if (nanos instanceof CInteger c && c.value().longValue() >= 0 && c.value().longValue() < NANOS) {
            return duration(cls, months, days, seconds, nanos);
        }
        ValueSpecification carry = wholeSeconds(nanos);
        return duration(cls, months, days, plus(seconds, carry), minus(nanos, times(NANOS, carry)));
    }

    /** {@code duration(x)}: from a map of components, a text, or another duration. */
    private Val durationFrom(CypherAst.Call c, Scope scope) {
        if (c.args().size() != 1) {
            throw CypherErrors.invalid((c.args().isEmpty() ? "Insufficient" : "Too many") + " parameters for"
                    + " function '" + c.name() + "'", c.pos());
        }
        Val a = val(c.args().get(0), scope);
        return switch (a) {
            case Scalar s when s.tx().kind() == Kind.NULL -> nullValue();
            case Scalar s when s.tx().kind() == Kind.STRING -> durationFromText(c, s);
            case Scalar s when s.tx().kind() == Kind.DURATION -> a;
            case Scalar s when s.tx().kind() == Kind.DATE || s.tx().kind() == Kind.DATETIME
                    || s.tx().kind() == Kind.TIME || s.tx().kind() == Kind.ZONED || s.tx().kind() == Kind.ZONED_TIME -> {
                // Neo4j's error names the value: spelled for a written one, refused by name for one of the row
                String printed = printed(s.tx());
                if (printed == null) {
                    throw CypherErrors.unsupported("a duration of a " + cypherType(s.tx().kind()) + " of the row"
                            + " (Neo4j's error names the value) is not supported yet", c.pos());
                }
                throw CypherErrors.invalid("Invalid call signature for DurationFunction: Provided input was ["
                        + printed + ", String(\"DEFAULT_TEMPORAL_ARGUMENT\")]", c.pos());
            }
            case Scalar s -> throw CypherErrors.invalid("Type mismatch: expected Map, String or Duration but was "
                    + cypherType(s.tx().kind()), c.pos());
            case MapVal m -> durationFromComponents(c, m);
            default -> throw CypherErrors.invalid("Type mismatch: expected Map, String or Duration but was List<T>",
                    c.pos());
        };
    }

    /** A component of a duration map, by its Cypher key in lower case. */
    private enum DurationUnit {
        YEARS("years", 12, 0), MONTHS("months", 1, 0), WEEKS("weeks", 7, 1),
        DAYS("days", 1, 1), HOURS("hours", 3600, 2), MINUTES("minutes", 60, 2), SECONDS("seconds", 1, 2),
        MILLISECONDS("milliseconds", 1_000_000, 3), MICROSECONDS("microseconds", 1_000, 3),
        NANOSECONDS("nanoseconds", 1, 3);

        final String cypher;
        /** How many of the slot one of this unit is. */
        final long factor;
        /** The slot (0 months, 1 days, 2 seconds, 3 nanoseconds). */
        final int slot;

        DurationUnit(String cypher, long factor, int slot) {
            this.cypher = cypher;
            this.factor = factor;
            this.slot = slot;
        }
    }

    private static final Map<String, DurationUnit> DURATION_UNITS = byName(DurationUnit.values(), u -> u.cypher);

    /** {@code duration({years: .., days: .., ...})}: each component a whole number (a fractional one is refused
     *  by name: Neo4j carries its fraction down the units), the slots their sums, the database's. */
    private Val durationFromComponents(CypherAst.Call c, MapVal m) {
        String cls = durationClass(c.pos());
        Map<DurationUnit, Tx> parts = new java.util.EnumMap<>(DurationUnit.class);
        for (Map.Entry<String, Val> e : m.entries().entrySet()) {
            DurationUnit u = DURATION_UNITS.get(e.getKey().toLowerCase(Locale.ROOT));
            if (u == null) {
                throw CypherErrors.invalid("Unknown field: " + e.getKey(), c.pos());
            }
            if (!(e.getValue() instanceof Scalar s)) {
                throw CypherErrors.invalid("Type mismatch: the component " + e.getKey() + " must be a number",
                        c.pos());
            }
            Tx t = s.tx();
            if (t.kind() == Kind.NULL) {
                return nullValue();
            }
            if (t.kind() != Kind.INTEGER) {
                if (t.kind().numeric()) {
                    throw CypherErrors.unsupported("a fractional duration component (" + e.getKey() + ": Neo4j"
                            + " carries the fraction down the units) is not supported yet", c.pos());
                }
                throw CypherErrors.invalid("Type mismatch: " + e.getKey() + " must be a number but was "
                        + cypherType(t.kind()), c.pos());
            }
            parts.put(u, t);
        }
        List<Tx> inputs = new ArrayList<>(parts.values());
        List<DurationUnit> keys = new ArrayList<>(parts.keySet());
        Tx ns = parts.get(DurationUnit.NANOSECONDS);
        boolean fine = ns != null && ns.vs() instanceof CInteger n && n.value().longValue() % 1000 != 0;
        return scalar(withNanos(SqlToPure.strict(Kind.DURATION, inputs, v -> {
            ValueSpecification[] slots = {lit(0), lit(0), lit(0), lit(0)};
            for (int i = 0; i < keys.size(); i++) {
                DurationUnit u = keys.get(i);
                slots[u.slot] = plus(slots[u.slot], times(u.factor, v.get(i)));
            }
            return normalized(cls, slots[0], slots[1], slots[2], slots[3]);
        }), fine));
    }

    /** {@code duration('P1Y2M3DT4H5M6.007S')}: the unit form, each number whole but the seconds, which may carry a
     *  fraction (to nine digits); the date-time form ({@code P2012-02-02T14:37:21}), fractions on other units and
     *  a text of the row are refused by name. */
    private Val durationFromText(CypherAst.Call c, Scalar s) {
        if (!s.literal()) {
            throw CypherErrors.unsupported("a duration text of the row is not supported yet", c.pos());
        }
        String text = (String) java.util.Objects.requireNonNull(s.constant());
        String cls = durationClass(c.pos());
        boolean negated = text.startsWith("-");
        String body = negated ? text.substring(1) : text.startsWith("+") ? text.substring(1) : text;
        if (!body.startsWith("P") && !body.startsWith("p")) {
            throw CypherErrors.invalid("Text cannot be parsed to a Duration", c.pos());
        }
        long[] slots = new long[4];
        int i = 1;
        boolean time = false;
        boolean any = false;
        while (i < body.length()) {
            char ch = body.charAt(i);
            if (ch == 'T' || ch == 't') {
                if (time) {
                    throw CypherErrors.invalid("Text cannot be parsed to a Duration", c.pos());
                }
                time = true;
                i++;
                continue;
            }
            int start = i;
            if (ch == '-' || ch == '+') {
                i++;
            }
            while (i < body.length() && body.charAt(i) >= '0' && body.charAt(i) <= '9') {
                i++;
            }
            String whole = body.substring(start, i);
            String fraction = "";
            if (i < body.length() && (body.charAt(i) == '.' || body.charAt(i) == ',')) {
                int f = i + 1;
                while (f < body.length() && body.charAt(f) >= '0' && body.charAt(f) <= '9') {
                    f++;
                }
                fraction = body.substring(i + 1, f);
                i = f;
            }
            if (i < body.length() && (body.charAt(i) == '-' && !whole.isEmpty() || body.charAt(i) == ':')) {
                throw CypherErrors.unsupported("a duration text in the date-time form (" + text + ") is not"
                        + " supported yet", c.pos());
            }
            if (i >= body.length() || whole.isEmpty() || whole.equals("-") || whole.equals("+")) {
                throw CypherErrors.invalid("Text cannot be parsed to a Duration", c.pos());
            }
            char unit = Character.toUpperCase(body.charAt(i++));
            long n = Long.parseLong(whole.startsWith("+") ? whole.substring(1) : whole);
            if (!fraction.isEmpty() && !(time && unit == 'S')) {
                throw CypherErrors.unsupported("a fractional duration component (" + text + ": Neo4j carries the"
                        + " fraction down the units) is not supported yet", c.pos());
            }
            any = true;
            switch (unit) {
                case 'Y' -> slots[0] += 12 * n;
                case 'M' -> {
                    if (time) {
                        slots[2] += 60 * n;
                    } else {
                        slots[0] += n;
                    }
                }
                case 'W' -> slots[1] += 7 * n;
                case 'D' -> slots[1] += n;
                case 'H' -> slots[2] += 3600 * n;
                case 'S' -> {
                    slots[2] += n;
                    if (!fraction.isEmpty()) {
                        if (fraction.length() > 9) {
                            throw CypherErrors.invalid("Text cannot be parsed to a Duration", c.pos());
                        }
                        long nanos = Long.parseLong((fraction + "000000000").substring(0, 9));
                        slots[3] += whole.startsWith("-") ? -nanos : nanos;
                    }
                }
                default -> throw CypherErrors.invalid("Text cannot be parsed to a Duration", c.pos());
            }
            if (time && (unit == 'Y' || unit == 'W' || unit == 'D') || !time && (unit == 'H' || unit == 'S')) {
                throw CypherErrors.invalid("Text cannot be parsed to a Duration", c.pos());
            }
        }
        if (!any) {
            throw CypherErrors.invalid("Text cannot be parsed to a Duration", c.pos());
        }
        if (negated) {
            for (int k = 0; k < 4; k++) {
                slots[k] = -slots[k];
            }
        }
        // the text's numbers, read as written (a sign, digits): the sums above are the reading of a literal, as a
        // date's year is; the normalisation of the nanoseconds is the database's; a fraction finer than a
        // microsecond makes the duration of nanosecond precision
        return scalar(withNanos(Tx.value(normalized(cls, lit(slots[0]), lit(slots[1]), lit(slots[2]),
                lit(slots[3])), Kind.DURATION, false), slots[3] % 1000 != 0));
    }

    /** A field of a duration ({@code d.minutesOfHour}), by its Cypher name (case-sensitive, as properties are). */
    private enum DurationField {
        YEARS("years"), QUARTERS("quarters"), MONTHS("months"), WEEKS("weeks"), DAYS("days"), HOURS("hours"),
        MINUTES("minutes"), SECONDS("seconds"), MILLISECONDS("milliseconds"), MICROSECONDS("microseconds"),
        NANOSECONDS("nanoseconds"), QUARTERS_OF_YEAR("quartersOfYear"), MONTHS_OF_QUARTER("monthsOfQuarter"),
        MONTHS_OF_YEAR("monthsOfYear"), DAYS_OF_WEEK("daysOfWeek"), MINUTES_OF_HOUR("minutesOfHour"),
        SECONDS_OF_MINUTE("secondsOfMinute"), MILLISECONDS_OF_SECOND("millisecondsOfSecond"),
        MICROSECONDS_OF_SECOND("microsecondsOfSecond"), NANOSECONDS_OF_SECOND("nanosecondsOfSecond");

        final String cypher;

        DurationField(String cypher) {
            this.cypher = cypher;
        }
    }

    private static final Map<String, DurationField> DURATION_FIELDS = byName(DurationField.values(), f -> f.cypher);

    /** A duration's fields, Neo4j's: the whole units of a slot ({@code years} is the months by twelve, truncated;
     *  {@code nanoseconds} the seconds and nanoseconds together) and the remainders within the next unit
     *  ({@code minutesOfHour}), each the database's arithmetic over the slots. */
    private Tx durationField(Tx t, String name, Pos pos) {
        DurationField field = DURATION_FIELDS.get(name);
        if (field == null) {
            throw CypherErrors.invalid("No such field: " + name, pos);
        }
        return field(t, d -> {
            ValueSpecification months = slot(d, "months");
            ValueSpecification days = slot(d, "days");
            ValueSpecification seconds = slot(d, "seconds");
            ValueSpecification nanos = slot(d, "nanoseconds");
            return switch (field) {
                case YEARS -> SqlToPure.integerQuotient(months, lit(12));
                case QUARTERS -> SqlToPure.integerQuotient(months, lit(3));
                case MONTHS -> months;
                case WEEKS -> SqlToPure.integerQuotient(days, lit(7));
                case DAYS -> days;
                case HOURS -> SqlToPure.integerQuotient(seconds, lit(3600));
                case MINUTES -> SqlToPure.integerQuotient(seconds, lit(60));
                case SECONDS -> seconds;
                case MILLISECONDS -> integer(plus(times(1000, seconds), SqlToPure.integerQuotient(nanos,
                        lit(1_000_000))));
                case MICROSECONDS -> integer(plus(times(1_000_000, seconds), SqlToPure.integerQuotient(nanos,
                        lit(1000))));
                case NANOSECONDS -> integer(plus(times(NANOS, seconds), nanos));
                case QUARTERS_OF_YEAR -> call("rem", SqlToPure.integerQuotient(months, lit(3)), lit(4));
                case MONTHS_OF_QUARTER -> call("rem", months, lit(3));
                case MONTHS_OF_YEAR -> call("rem", months, lit(12));
                case DAYS_OF_WEEK -> call("rem", days, lit(7));
                case MINUTES_OF_HOUR -> call("rem", SqlToPure.integerQuotient(seconds, lit(60)), lit(60));
                case SECONDS_OF_MINUTE -> call("rem", seconds, lit(60));
                case MILLISECONDS_OF_SECOND -> SqlToPure.integerQuotient(nanos, lit(1_000_000));
                case MICROSECONDS_OF_SECOND -> SqlToPure.integerQuotient(nanos, lit(1000));
                case NANOSECONDS_OF_SECOND -> nanos;
            };
        });
    }

    /** {@code temporal + duration}, {@code temporal - duration}, {@code duration + duration}, {@code duration -
     *  duration}, {@code duration * integer}: the database's. A date takes the months and days (the seconds are
     *  not a date's), a time of day the seconds (it wraps at midnight), a date-time all four (months, then days,
     *  then seconds, as Neo4j adds them), a zoned date-time the months and days on its wall time and the seconds on
     *  its instant. */
    private Tx durationArithmetic(CypherAst.Binary b, Tx l, Tx r) {
        BinaryOp op = b.op();
        if (l.kind() == Kind.NULL || r.kind() == Kind.NULL) {
            return Tx.value(SqlToPure.empty(), Kind.DURATION, true);
        }
        String cls = durationClass(b.pos());
        if (l.kind() == Kind.DURATION && r.kind() == Kind.DURATION) {
            if (op != BinaryOp.PLUS && op != BinaryOp.MINUS) {
                throw CypherErrors.invalid("Type mismatch: a duration can be added to or subtracted from a"
                        + " duration, not " + op, b.pos());
            }
            boolean subtract = op == BinaryOp.MINUS;
            return withNanos(SqlToPure.strict(Kind.DURATION, List.of(l, r), v -> {
                ValueSpecification[] s = new ValueSpecification[4];
                for (int i = 0; i < 4; i++) {
                    ValueSpecification x = slot(v.get(0), DURATION_SLOTS.get(i));
                    ValueSpecification y = slot(v.get(1), DURATION_SLOTS.get(i));
                    s[i] = subtract ? minus(x, y) : plus(x, y);
                }
                return normalized(cls, s[0], s[1], s[2], s[3]);
            }), nanos(l) || nanos(r));
        }
        if (op == BinaryOp.TIMES && (l.kind() == Kind.INTEGER || r.kind() == Kind.INTEGER)) {
            Tx d = l.kind() == Kind.DURATION ? l : r;
            Tx k = l.kind() == Kind.DURATION ? r : l;
            return withNanos(SqlToPure.strict(Kind.DURATION, List.of(d, k), v -> {
                ValueSpecification[] s = new ValueSpecification[4];
                for (int i = 0; i < 4; i++) {
                    s[i] = AppliedFunction.infixRun("times", List.of(slot(v.get(0), DURATION_SLOTS.get(i)),
                            v.get(1)));
                }
                return normalized(cls, s[0], s[1], s[2], s[3]);
            }), nanos(d));
        }
        if (op == BinaryOp.TIMES || op == BinaryOp.DIVIDE) {
            throw CypherErrors.unsupported("a duration " + (op == BinaryOp.TIMES ? "multiplied" : "divided")
                    + " by a " + cypherType(l.kind() == Kind.DURATION ? r.kind() : l.kind()) + " (Neo4j carries"
                    + " the fractions down the units) is not supported yet", b.pos());
        }
        if (op != BinaryOp.PLUS && op != BinaryOp.MINUS || op == BinaryOp.MINUS && l.kind() == Kind.DURATION) {
            throw CypherErrors.invalid("Type mismatch: a duration can be added to a temporal value, or subtracted"
                    + " from one", b.pos());
        }
        Tx temporal = l.kind() == Kind.DURATION ? r : l;
        Tx d = l.kind() == Kind.DURATION ? l : r;
        boolean subtract = op == BinaryOp.MINUS;
        Kind kind = temporal.kind();
        if (kind != Kind.DATE && kind != Kind.DATETIME && kind != Kind.TIME && kind != Kind.ZONED
                && kind != Kind.ZONED_TIME) {
            throw CypherErrors.invalid("Type mismatch: a duration can be added to a temporal value, not a "
                    + cypherType(kind), b.pos());
        }
        Zone zone = kind == Kind.ZONED || kind == Kind.ZONED_TIME ? zoneKnown(temporal, b.pos()) : null;
        boolean temporalNanos = nanos(temporal);
        // of nanosecond precision when either is: the seconds and nanoseconds as one nanosecond step, a calendar
        // step or a zone conversion with the sub-microsecond digits put back after it
        boolean fine = temporalNanos || nanos(d);
        Tx out = withNanos(SqlToPure.strict(kind == Kind.ZONED ? Kind.DATETIME : kind == Kind.ZONED_TIME ? Kind.TIME
                : kind, List.of(temporal, d), v -> {
            ValueSpecification x = v.get(0);
            ValueSpecification dur = v.get(1);
            ValueSpecification months = slot(dur, "months");
            ValueSpecification days = slot(dur, "days");
            ValueSpecification seconds = slot(dur, "seconds");
            ValueSpecification micros = SqlToPure.integerQuotient(slot(dur, "nanoseconds"), lit(1000));
            ValueSpecification nanos = slot(dur, "nanoseconds");
            if (subtract) {
                months = minus(lit(0), months);
                days = minus(lit(0), days);
                seconds = minus(lit(0), seconds);
                micros = minus(lit(0), micros);
                nanos = minus(lit(0), nanos);
            }
            ValueSpecification secs = seconds;
            ValueSpecification ns = nanos;
            return switch (kind) {
                case DATE -> day(call("adjust", call("adjust", x, integer(months), unit("MONTHS")), integer(days),
                        unit("DAYS")));
                case TIME -> fine ? timeOfNanos(secondsAndNanos(temporalNanos ? x : atEpoch(x), secs, ns,
                        temporalNanos))
                        : call(TIME_OF, call("adjust", call("adjust", atEpoch(x), integer(seconds),
                                unit("SECONDS")), integer(micros), unit("MICROSECONDS")));
                // a zoned time of day: its wall time moved by the seconds, wrapping within the day (Neo4j's)
                case ZONED_TIME -> fine ? timeOfNanos(secondsAndNanos(shifted(x,
                        java.util.Objects.requireNonNull(zone), 1, temporalNanos), secs, ns, temporalNanos))
                        : call(TIME_OF, call("adjust", call("adjust", shifted(x,
                                java.util.Objects.requireNonNull(zone), 1, false), integer(seconds),
                                unit("SECONDS")), integer(micros), unit("MICROSECONDS")));
                case ZONED -> {
                    Zone z = java.util.Objects.requireNonNull(zone);
                    ValueSpecification wall = z.named() ? inZone(LOCAL_IN, x, z, temporalNanos)
                            : shifted(x, z, 1, temporalNanos);
                    ValueSpecification moved = step(step(wall, months, "MONTHS", temporalNanos), days, "DAYS",
                            temporalNanos);
                    ValueSpecification instant = z.named() ? inZone(INSTANT_IN, moved, z, temporalNanos)
                            : shifted(moved, z, -1, temporalNanos);
                    yield fine ? secondsAndNanos(instant, secs, ns, temporalNanos)
                            : call("adjust", call("adjust", instant, integer(seconds), unit("SECONDS")),
                                    integer(micros), unit("MICROSECONDS"));
                }
                default -> {
                    ValueSpecification moved = step(step(x, months, "MONTHS", temporalNanos), days, "DAYS",
                            temporalNanos);
                    yield fine ? secondsAndNanos(moved, secs, ns, temporalNanos)
                            : call("adjust", call("adjust", moved, integer(seconds), unit("SECONDS")),
                                    integer(micros), unit("MICROSECONDS"));
                }
            };
        }), fine);
        if (kind == Kind.ZONED_TIME) {
            return zonedTimeOf(out, java.util.Objects.requireNonNull(zone));
        }
        return kind == Kind.ZONED ? zoned(out, java.util.Objects.requireNonNull(zone)) : out;
    }

    /** {@code duration.between(a, b)}, {@code inMonths}, {@code inDays}, {@code inSeconds}: Neo4j's split, the
     *  database's — the whole months from a to b (the calendar count, one back when the month's end overshoots),
     *  then the whole days from there, then the seconds and the nanoseconds of the rest; {@code inMonths} stops at
     *  the months, {@code inDays} counts whole days only, {@code inSeconds} seconds only. Two dates or date-times
     *  (a date at midnight; a zoned date-time its instant), or two times of day. */
    private Val between(CypherAst.Call c, TemporalMember member, Scope scope) {
        if (c.args().size() != 2) {
            throw CypherErrors.invalid((c.args().size() < 2 ? "Insufficient" : "Too many") + " parameters for"
                    + " function '" + c.name() + "'", c.pos());
        }
        String cls = durationClass(c.pos());
        Val a = val(c.args().get(0), scope);
        Val b = val(c.args().get(1), scope);
        if (isNull(a) || isNull(b)) {
            return nullValue();
        }
        if (!(a instanceof Scalar sa) || !(b instanceof Scalar sb)) {
            throw CypherErrors.invalid("Type mismatch: expected temporal values", c.pos());
        }
        Tx x = sa.tx();
        Tx y = sb.tx();
        Zone ztx = x.kind() == Kind.ZONED_TIME ? zoneKnown(x, c.pos()) : null;
        Zone zty = y.kind() == Kind.ZONED_TIME ? zoneKnown(y, c.pos()) : null;
        if (ztx != null && zty != null) {
            // two zoned times of day: their instants on the epoch day
            x = instantOfTime(x);
            y = instantOfTime(y);
        } else if (ztx != null || zty != null) {
            // Neo4j's reading of a zoned time of day and another value: a local time read in the zoned one's zone
            // (the instants on the epoch day); a date or date-time by its instant (a local one read in the time's
            // zone), the time placed on its wall date in the time's own zone
            boolean first = ztx != null;
            Tx zt = first ? x : y;
            Zone tz = java.util.Objects.requireNonNull(first ? ztx : zty);
            Tx other = first ? y : x;
            Tx placed;
            Tx otherSide;
            if (other.kind() == Kind.TIME) {
                placed = instantOfTime(zt);
                boolean otherNanos = nanos(other);
                otherSide = withNanos(SqlToPure.strict(Kind.DATETIME, List.of(other), v -> shifted(otherNanos
                        ? v.get(0) : atEpoch(v.get(0)), tz, -1, otherNanos)), otherNanos);
            } else if (isDateLike(other.kind())) {
                Zone oz = other.kind() == Kind.ZONED ? zoneKnown(other, c.pos()) : null;
                Tx otherWall = oz == null ? asDateTime(other) : localOf(other, oz);
                Tx wallTime = wallTimeOf(zt, tz);
                boolean timeNanos = nanos(wallTime);
                Tx onDate = withNanos(SqlToPure.strict(Kind.DATETIME, List.of(otherWall, wallTime),
                        v -> atTimeOf(call("datePart", v.get(0)), v.get(1), timeNanos)), timeNanos || nanos(otherWall));
                placed = instantOf(onDate, tz);
                otherSide = oz != null ? other : instantOf(otherWall, tz);
            } else {
                throw CypherErrors.unsupported("duration.between of a Time and a " + cypherType(other.kind())
                        + " is not supported yet", c.pos());
            }
            x = first ? placed : otherSide;
            y = first ? otherSide : placed;
        }
        boolean dates = isDateLike(x.kind()) && isDateLike(y.kind());
        boolean times = x.kind() == Kind.TIME && y.kind() == Kind.TIME;
        boolean mixed = x.kind() == Kind.TIME && isDateLike(y.kind()) || isDateLike(x.kind()) && y.kind() == Kind.TIME;
        if (!dates && !times && !mixed) {
            throw CypherErrors.unsupported("duration.between of a " + cypherType(x.kind()) + " and a "
                    + cypherType(y.kind()) + " is not supported yet", c.pos());
        }
        if (times) {
            x = atEpoch(x);
            y = atEpoch(y);
        }
        if (mixed) {
            // Neo4j's reading of a time of day and a date or date-time: the time on the other's date (a date at its
            // midnight; a zoned date-time's wall date, the time then read in its zone), so only the times tell
            boolean timeFirst = x.kind() == Kind.TIME;
            Tx time = timeFirst ? x : y;
            Tx other = timeFirst ? y : x;
            Zone z = other.kind() == Kind.ZONED ? zoneKnown(other, c.pos()) : null;
            Tx wall = z == null ? asDateTime(other) : localOf(other, z);
            boolean timeNanos = nanos(time);
            Tx onDate = withNanos(SqlToPure.strict(Kind.DATETIME, List.of(wall, time), v -> atTimeOf(call("datePart",
                    v.get(0)), v.get(1), timeNanos)), timeNanos || nanos(wall));
            Tx placed = z == null ? onDate : instantOf(onDate, z);
            Tx otherSide = z == null ? wall : other;
            x = timeFirst ? placed : otherSide;
            y = timeFirst ? otherSide : placed;
        }
        // Neo4j's reading of a zoned and a local value: the local one (a date at its midnight) in the zoned one's
        // zone, then the instants (the TCK's Temporal10 [8]: a Stockholm midnight and a local 04:00 on the day
        // summer time ends are five hours apart); two zoned values are their instants, two local their wall times
        Zone zx = x.kind() == Kind.ZONED ? zoneKnown(x, c.pos()) : null;
        Zone zy = y.kind() == Kind.ZONED ? zoneKnown(y, c.pos()) : null;
        if (zx != null && zy == null) {
            y = instantOf(asDateTime(y), zx);
        } else if (zy != null && zx == null) {
            x = instantOf(asDateTime(x), zy);
        }
        // of nanosecond precision when either is: the calendar steps with the sub-microsecond digits put back, the
        // rest the nanoseconds' difference
        boolean fine = nanos(x) || nanos(y);
        return scalar(withNanos(SqlToPure.strict(Kind.DURATION, List.of(x, y), v -> {
            ValueSpecification from = v.get(0);
            ValueSpecification to = v.get(1);
            ValueSpecification months = lit(0);
            if (member == TemporalMember.BETWEEN || member == TemporalMember.IN_MONTHS) {
                months = wholeUnits(from, to, "MONTHS", fine);
                from = step(from, months, "MONTHS", fine);
            }
            if (member == TemporalMember.IN_MONTHS) {
                return duration(cls, months, lit(0), lit(0), lit(0));
            }
            ValueSpecification days = lit(0);
            if (member == TemporalMember.BETWEEN || member == TemporalMember.IN_DAYS) {
                days = wholeUnits(from, to, "DAYS", fine);
                from = step(from, days, "DAYS", fine);
            }
            if (member == TemporalMember.IN_DAYS) {
                return duration(cls, lit(0), days, lit(0), lit(0));
            }
            if (fine) {
                ValueSpecification all = call("dateDiff", from, to, unit("NANOSECONDS"));
                ValueSpecification seconds = call("floor", call("divide", all, lit(1_000_000_000)));
                return duration(cls, months, days, seconds, minus(all, times(1_000_000_000L, seconds)));
            }
            ValueSpecification micros = call("dateDiff", from, to, unit("MICROSECONDS"));
            ValueSpecification seconds = call("floor", call("divide", micros, lit(1_000_000)));
            ValueSpecification nanos = times(1000, minus(micros, times(1_000_000, seconds)));
            return duration(cls, months, days, seconds, nanos);
        }), fine));
    }

    private static boolean isDateLike(Kind k) {
        return k == Kind.DATE || k == Kind.DATETIME || k == Kind.ZONED;
    }

    /** A date as the date-time at its midnight; a date-time as it is. */
    private static Tx asDateTime(Tx t) {
        if (t.kind() != Kind.DATE) {
            return t;
        }
        return SqlToPure.strict(Kind.DATETIME, List.of(t), v -> call(AT_TIME, v.get(0),
                new com.legend.protocol.spec.CTime(new com.legend.values.PureTimeLiteral.TimeWithMinute(0, 0),
                        "00:00", null)));
    }

    /** The whole units of {@code unit} from {@code from} to {@code to}: the calendar count, one back when stepping
     *  it from {@code from} overshoots {@code to} (forward), one on when it undershoots (backward). */
    private static ValueSpecification wholeUnits(ValueSpecification from, ValueSpecification to, String unitName,
            boolean nanos) {
        ValueSpecification count = call("dateDiff", from, to, unit(unitName));
        ValueSpecification stepped = step(from, count, unitName, nanos);
        ValueSpecification forward = call("if", call("greaterThan", stepped, to), thunk(minus(count, lit(1))),
                thunk(count));
        ValueSpecification backward = call("if", call("lessThan", stepped, to), thunk(plus(count, lit(1))),
                thunk(count));
        return call("if", call("greaterThanEqual", to, from), thunk(forward), thunk(backward));
    }

    private static ValueSpecification epochInstant() {
        return new com.legend.protocol.spec.CDate(new com.legend.values.PureDateLiteral.DateWithSecond(1970, 1, 1,
                0, 0, 0), "1970-01-01T00:00:00", null);
    }

    /** A value read as an Integer: Pure types the arithmetic of integers as Number, and {@code date}, {@code adjust}
     *  take Integers. */
    private static ValueSpecification integer(ValueSpecification v) {
        return v instanceof CInteger ? v : call("cast", v, SqlToPure.typeArg("Integer"));
    }

    /** A date the database built by adjusting another: DuckDB's date plus an interval is a timestamp, so the
     *  day is taken back (Pure's datePart). */
    private static ValueSpecification day(ValueSpecification adjusted) {
        return call("datePart", adjusted);
    }

    private Val temporalCall(CypherAst.Call c, String fn, TemporalFamily family, Scope scope) {
        String suffix = fn.contains(".") ? fn.substring(fn.indexOf('.') + 1) : "";
        boolean withTime = family == TemporalFamily.LOCALDATETIME;
        TemporalMember member = TEMPORAL_MEMBERS.get(suffix);
        if (member == null) {
            throw CypherErrors.unsupported("the function " + c.name() + "(...) is not supported yet", c.pos());
        }
        if (family == TemporalFamily.DURATION) {
            return switch (member) {
                case CONSTRUCT -> durationFrom(c, scope);
                case BETWEEN, IN_MONTHS, IN_DAYS, IN_SECONDS -> between(c, member, scope);
                default -> throw CypherErrors.invalid("Unknown function '" + c.name() + "'", c.pos());
            };
        }
        return switch (member) {
            case CONSTRUCT -> switch (family) {
                case LOCALTIME -> timeFrom(c, scope);
                case TIME -> zonedTimeFrom(c, scope);
                case DATETIME -> zonedFrom(c, scope);
                default -> temporalFrom(c, withTime, scope);
            };
            case TRUNCATE -> truncate(c, family, scope);
            case BETWEEN, IN_MONTHS, IN_DAYS, IN_SECONDS -> throw CypherErrors.invalid("Unknown function '"
                    + c.name() + "'", c.pos());
            case FROM_EPOCH, FROM_EPOCH_MILLIS -> {
                if (family != TemporalFamily.DATETIME) {
                    throw CypherErrors.invalid("Unknown function '" + c.name() + "'", c.pos());
                }
                yield fromEpoch(c, member == TemporalMember.FROM_EPOCH_MILLIS, scope);
            }
            case REALTIME, STATEMENT, TRANSACTION -> throw CypherErrors.unsupported("the function " + c.name()
                    + "() (the current date or time: a value of the call, not of the data) is not supported yet",
                    c.pos());
        };
    }

    /** {@code date(x)}, {@code localdatetime(x)}: from a map of components, an ISO text, or another temporal value. */
    private Val temporalFrom(CypherAst.Call c, boolean withTime, Scope scope) {
        if (c.args().isEmpty()) {
            throw CypherErrors.unsupported("the function " + c.name() + "() (the current date or time: a value of the"
                    + " call, not of the data) is not supported yet", c.pos());
        }
        if (c.args().size() > 1) {
            throw CypherErrors.invalid("Too many parameters for function '" + c.name() + "'", c.pos());
        }
        Val a = unzoned(val(c.args().get(0), scope), c.pos());
        Kind result = withTime ? Kind.DATETIME : Kind.DATE;
        return switch (a) {
            case Scalar s when s.tx().kind() == Kind.NULL -> nullValue();
            case Scalar s when s.tx().kind() == Kind.STRING -> temporalFromText(c, s, withTime);
            case Scalar s when s.tx().kind() == Kind.DATE -> {
                if (withTime) {
                    throw CypherErrors.invalid("Cannot construct a LocalDateTime from a Date without a time", c.pos());
                }
                yield a;
            }
            case Scalar s when s.tx().kind() == Kind.DATETIME -> withTime ? a
                    : scalar(SqlToPure.strict(Kind.DATE, List.of(s.tx()), x -> call("datePart", x.get(0))));
            case Scalar s -> throw CypherErrors.invalid("Type mismatch: expected Map, String, Date, LocalDateTime or"
                    + " DateTime but was " + cypherType(s.tx().kind()), c.pos());
            case MapVal m -> temporalFromComponents(c, m, withTime, result);
            default -> throw CypherErrors.invalid("Type mismatch: expected Map, String, Date, LocalDateTime or"
                    + " DateTime but was List<T>", c.pos());
        };
    }

    /** {@code datetime(x)}: from a map of components (a zone among them), an ISO text with its offset, a local
     *  date-time (the default zone, UTC), or another zoned date-time. */
    private Val zonedFrom(CypherAst.Call c, Scope scope) {
        if (c.args().isEmpty()) {
            throw CypherErrors.unsupported("the function " + c.name() + "() (the current date-time: a value of the"
                    + " call, not of the data) is not supported yet", c.pos());
        }
        if (c.args().size() > 1) {
            throw CypherErrors.invalid("Too many parameters for function '" + c.name() + "'", c.pos());
        }
        Val a = val(c.args().get(0), scope);
        return switch (a) {
            case Scalar s when s.tx().kind() == Kind.NULL -> nullValue();
            case Scalar s when s.tx().kind() == Kind.STRING -> zonedFromText(c, s);
            case Scalar s when s.tx().kind() == Kind.ZONED -> a;
            case Scalar s when s.tx().kind() == Kind.DATETIME -> scalar(zoned(s.tx(), Zone.UTC));
            case Scalar s when s.tx().kind() == Kind.DATE || s.tx().kind() == Kind.TIME -> {
                // Neo4j's error names the value, as it prints it: spelled for a written one, refused by name for
                // one of the row
                String printed = printed(s.tx());
                if (printed == null) {
                    throw CypherErrors.unsupported("a date-time of a " + cypherType(s.tx().kind()) + " of the row"
                            + " (Neo4j's error names the value) is not supported yet", c.pos());
                }
                throw CypherErrors.invalid("Cannot select datetime from: " + printed, c.pos());
            }
            case Scalar s -> throw CypherErrors.invalid("Type mismatch: expected Map, String, Date, LocalDateTime or"
                    + " DateTime but was " + cypherType(s.tx().kind()), c.pos());
            case MapVal m -> zonedFromComponents(c, m);
            default -> throw CypherErrors.invalid("Type mismatch: expected Map, String, Date, LocalDateTime or"
                    + " DateTime but was List<T>", c.pos());
        };
    }

    /** A written temporal value as Neo4j prints it in a message (a date {@code 1984-10-11}, a time or date-time with
     *  its seconds, the fraction's zero groups trimmed); null for a value of the row, or a form Neo4j prints
     *  otherwise. */
    private static @com.legend.base.Nullable String printed(Tx t) {
        if (t.vs() instanceof com.legend.protocol.spec.CDate d) {
            return switch (d.value()) {
                case com.legend.values.PureDateLiteral.StrictDate sd -> sd.toEngineString();
                case com.legend.values.PureDateLiteral.DateWithMinute m -> m.toEngineString() + ":00";
                case com.legend.values.PureDateLiteral.DateWithSecond s -> s.toEngineString();
                case com.legend.values.PureDateLiteral.DateWithSubsecond s -> s.toEngineString().substring(0, 19)
                        + fraction(s.subsecond());
                default -> null;
            };
        }
        if (t.vs() instanceof com.legend.protocol.spec.CTime ct && ct.value() != null) {
            return switch (ct.value()) {
                case com.legend.values.PureTimeLiteral.TimeWithMinute m -> m.toEngineString() + ":00";
                case com.legend.values.PureTimeLiteral.TimeWithSecond s -> s.toEngineString();
                case com.legend.values.PureTimeLiteral.TimeWithSubsecond s -> s.toEngineString().substring(0, 8)
                        + fraction(s.subsecond());
            };
        }
        return null;
    }

    /** A fraction of a second as Neo4j prints one: in groups of three digits, the trailing zero groups dropped. */
    private static String fraction(String digits) {
        String nine = (digits + "000000000").substring(0, 9);
        int keep = 9;
        while (keep > 0 && nine.substring(keep - 3, keep).equals("000")) {
            keep -= 3;
        }
        return keep == 0 ? "" : "." + nine.substring(0, keep);
    }

    /** A zoned date-time from a text: a literal in the ISO calendar form with {@code Z} or an offset (no zone, as
     *  Neo4j, the default zone UTC) is Pure's date literal, the instant the database's shift of it; a named zone in
     *  brackets, the other literal forms, and a text of the row are refused by name. */
    private Val zonedFromText(CypherAst.Call c, Scalar s) {
        if (!s.literal()) {
            throw CypherErrors.unsupported("a zoned date-time text of the row (its offset read by the database) is"
                    + " not supported yet", c.pos());
        }
        String text = (String) java.util.Objects.requireNonNull(s.constant());
        expandedYear(text, c.pos());
        TemporalText tt = TemporalText.read(text, true, true);
        if (tt == null) {
            throw CypherErrors.invalid("Text cannot be parsed to a DateTime: \"" + text + "\"", c.pos());
        }
        // the instant from the offset when one is written, else the database's reading of the wall time in the
        // named zone; the zone kept is the named one when written (Neo4j prints both)
        Zone offset = tt.zoneText() == null ? null : zone(tt.zoneText(), true, c.pos());
        Zone named = tt.zoneName() == null ? null : zone(tt.zoneName(), false, c.pos());
        Val wall = dateTimeOfText(c, tt, text);
        if (!(wall instanceof Scalar ws)) {
            return wall;
        }
        Zone in = offset != null ? offset : named != null ? named : Zone.UTC;
        Tx instant = instantOf(ws.tx(), in);
        if (named == null || offset == null) {
            return scalar(instant);
        }
        // Neo4j's check that the offset is the zone's at that instant (an overlap's second reading passes: the
        // instant read back in the zone is the wall time written): the database judges, and raises as Neo4j does
        String zoneName = java.util.Objects.requireNonNull(named.name());
        boolean fine = nanos(instant);
        Tx checked = withNanos(SqlToPure.strict(Kind.DATETIME, List.of(instant, ws.tx()), v -> call("if",
                call("equal", inZone(LOCAL_IN, v.get(0), named, fine), v.get(1)), thunk(v.get(0)),
                new LambdaFunction(List.of(), List.of(call("fail", new CString("Timezone and offset do not match: "
                        + text)), v.get(0))))), fine);
        return scalar(zoned(checked, named));
    }

    /** {@code datetime({...})}: the wall time the components spell, in the written zone, else a zoned {@code datetime}
     *  or {@code time} base's, else UTC (the TCK's Temporal3 [8] to [11]: a zoned {@code date} base lends its wall
     *  date, not its zone). A zoned {@code datetime} or {@code time} base is read by its wall time in the result's
     *  zone (its instant converted: {@code {datetime: d, timezone: '+05:00'}} alone keeps the instant, with fields
     *  written over it the fields apply to the converted wall time); a zoned {@code date} base by its own. */
    private Val zonedFromComponents(CypherAst.Call c, MapVal m) {
        Zone written = null;
        Tx zonedBase = null;
        Component zonedBaseKey = null;
        Map<String, Val> rest = new LinkedHashMap<>();
        for (Map.Entry<String, Val> e : m.entries().entrySet()) {
            Component k = COMPONENTS.get(e.getKey().toLowerCase(Locale.ROOT));
            if (k == Component.TIMEZONE) {
                if (!(e.getValue() instanceof Scalar s) || !s.literal() || !(s.constant() instanceof String zt)) {
                    throw CypherErrors.unsupported("a time zone that is not a spelled text is not supported yet",
                            c.pos());
                }
                written = zone(zt, false, c.pos());
                continue;
            }
            if (e.getValue() instanceof Scalar s && (s.tx().kind() == Kind.ZONED
                    && (k == Component.DATETIME || k == Component.TIME)
                    || s.tx().kind() == Kind.ZONED_TIME && k == Component.TIME)) {
                zonedBase = s.tx();
                zonedBaseKey = k;
            }
            rest.put(e.getKey(), e.getValue());
        }
        Zone baseZone = zonedBase != null ? zoneKnown(zonedBase, c.pos()) : null;
        Zone result = written != null ? written : baseZone != null ? baseZone : Zone.UTC;
        if (zonedBase != null && zonedBaseKey == Component.DATETIME && rest.size() == 1) {
            // the conversion: the instant kept, the zone changed
            return scalar(zoned(zonedBase, result));
        }
        // every zoned base read by its wall time in its own zone; the wall time the fields spell is read in the
        // datetime or time base's zone on the date it spells (a named zone's offset is the result date's: the
        // TCK's Temporal3 [10] #32, a March date with an October Stockholm time), then converted to the written
        // zone, the instant kept
        Map<String, Val> wall = new LinkedHashMap<>();
        for (Map.Entry<String, Val> e : rest.entrySet()) {
            Val v = e.getValue();
            if (v instanceof Scalar s && s.tx().kind() == Kind.ZONED) {
                v = scalar(localOf(s.tx(), zoneKnown(s.tx(), c.pos())));
            } else if (v instanceof Scalar s && s.tx().kind() == Kind.ZONED_TIME) {
                v = scalar(wallTimeOf(s.tx(), zoneKnown(s.tx(), c.pos())));
            }
            wall.put(e.getKey(), v);
        }
        Val local = temporalFromComponents(c, new MapVal(wall), true, Kind.DATETIME);
        if (!(local instanceof Scalar ls) || ls.tx().kind() == Kind.NULL) {
            return local;
        }
        Tx instant = instantOf(ls.tx(), baseZone != null ? baseZone : result);
        return scalar(written != null && baseZone != null ? zoned(instant, written) : instant);
    }

    /** {@code datetime.fromepoch(seconds, nanoseconds)}, {@code datetime.fromepochmillis(milliseconds)}: the epoch
     *  instant adjusted, at UTC. */
    private Val fromEpoch(CypherAst.Call c, boolean millis, Scope scope) {
        int arity = millis ? 1 : 2;
        if (c.args().size() != arity) {
            throw CypherErrors.invalid((c.args().size() < arity ? "Insufficient" : "Too many") + " parameters for"
                    + " function '" + c.name() + "'", c.pos());
        }
        List<Tx> inputs = new ArrayList<>();
        for (Expr e : c.args()) {
            Val v = val(e, scope);
            if (isNull(v)) {
                return nullValue();
            }
            if (!(v instanceof Scalar s) || s.tx().kind() != Kind.INTEGER) {
                throw CypherErrors.invalid("Type mismatch: expected Integer but was " + (v instanceof Scalar s2
                        ? cypherType(s2.tx().kind()) : "List<T>"), e.pos());
            }
            inputs.add(s.tx());
        }
        boolean fine = !millis && nanosComponent(inputs.get(1), c.pos());
        return scalar(zoned(withNanos(SqlToPure.strict(Kind.DATETIME, inputs, v -> millis
                ? call("adjust", epochInstant(), integer(v.get(0)), unit("MILLISECONDS"))
                : fine ? call("adjust", call("adjust", epochInstant(), integer(v.get(0)), unit("SECONDS")),
                        integer(v.get(1)), unit("NANOSECONDS"))
                : call("adjust", call("adjust", epochInstant(), integer(v.get(0)), unit("SECONDS")),
                        integer(call("divide", v.get(1), new CInteger(1000L))), unit("MICROSECONDS"))), fine),
                Zone.UTC));
    }

    /** {@code localtime(x)}: from a map of components, an ISO text, or a date-time or time of day. */
    private Val timeFrom(CypherAst.Call c, Scope scope) {
        if (c.args().isEmpty()) {
            throw CypherErrors.unsupported("the function " + c.name() + "() (the current time: a value of the call,"
                    + " not of the data) is not supported yet", c.pos());
        }
        if (c.args().size() > 1) {
            throw CypherErrors.invalid("Too many parameters for function '" + c.name() + "'", c.pos());
        }
        Val a = unzoned(val(c.args().get(0), scope), c.pos());
        return switch (a) {
            case Scalar s when s.tx().kind() == Kind.NULL -> nullValue();
            case Scalar s when s.tx().kind() == Kind.STRING -> timeFromText(c, s);
            case Scalar s when s.tx().kind() == Kind.TIME -> a;
            case Scalar s when s.tx().kind() == Kind.DATETIME -> scalar(timeOf(s.tx()));
            case Scalar s when s.tx().kind() == Kind.DATE -> {
                // Neo4j's error names the date: spelled for a written date, refused by name for one of the row
                if (s.tx().vs() instanceof com.legend.protocol.spec.CDate d
                        && d.value() instanceof com.legend.values.PureDateLiteral.StrictDate sd) {
                    throw CypherErrors.invalid("Cannot get the time of: " + sd.toEngineString(), c.pos());
                }
                throw CypherErrors.unsupported("the time of a date of the row (Neo4j's error names the date) is not"
                        + " supported yet", c.pos());
            }
            case Scalar s -> throw CypherErrors.invalid("Type mismatch: expected Map, String, Time, LocalTime,"
                    + " LocalDateTime or DateTime but was " + cypherType(s.tx().kind()), c.pos());
            case MapVal m -> timeFromComponents(c, m);
            default -> throw CypherErrors.invalid("Type mismatch: expected Map, String, Time, LocalTime,"
                    + " LocalDateTime or DateTime but was List<T>", c.pos());
        };
    }

    /** A time of day from a text: a literal {@code hh:mm[:ss[.f{1,9}]]} is Pure's time literal; any other literal
     *  form (compact, a zone) is refused by name; a text of the row is the database's parse, as the time of the
     *  epoch day. */
    private Val timeFromText(CypherAst.Call c, Scalar s) {
        Tx t = s.tx();
        if (!s.literal()) {
            return scalar(SqlToPure.strict(Kind.TIME, List.of(t), x -> call(TIME_OF, call("parseDate",
                    AppliedFunction.infixRun("plus", List.of(new CString("1970-01-01T"), x.get(0)))))));
        }
        String text = (String) java.util.Objects.requireNonNull(s.constant());
        com.legend.values.PureTimeLiteral lit = timeLiteral(text);
        if (lit == null) {
            TemporalText tt = TemporalText.read(text, false, true);
            if (tt == null || tt.zoneText() != null || tt.zoneName() != null) {
                throw CypherErrors.invalid("Text cannot be parsed to a LocalTime: \"" + text + "\"", c.pos());
            }
            lit = timeLiteralOf(tt);
        }
        return scalar(timeValue(lit));
    }

    /** A time literal as the island's value: to the microsecond Pure's literal; finer, the literal to the
     *  microsecond moved by the sub-microsecond digits, on the epoch day (a nanosecond time of day). */
    private static Tx timeValue(com.legend.values.PureTimeLiteral lit) {
        if (lit instanceof com.legend.values.PureTimeLiteral.TimeWithSubsecond sub && sub.subsecond().length() > 6) {
            com.legend.values.PureTimeLiteral micro = new com.legend.values.PureTimeLiteral.TimeWithSubsecond(
                    sub.hour(), sub.minute(), sub.second(), sub.subsecond().substring(0, 6));
            return finer(atEpoch(new com.legend.protocol.spec.CTime(micro, micro.toEngineString(), null)),
                    subMicro(sub.subsecond()), Kind.TIME);
        }
        return Tx.value(new com.legend.protocol.spec.CTime(lit, lit.toEngineString(), null), Kind.TIME, false);
    }

    /** Pure's time literal for the clock a text spelled (the fraction's trailing zeros past the sixth digit
     *  dropped). */
    private static com.legend.values.PureTimeLiteral timeLiteralOf(TemporalText tt) {
        int h = java.util.Objects.requireNonNull(tt.hour());
        int mi = java.util.Objects.requireNonNull(tt.minute());
        int sec = java.util.Objects.requireNonNull(tt.second());
        String fraction = tt.fraction();
        int keep = fraction.length();
        while (keep > 6 && fraction.charAt(keep - 1) == '0') {
            keep--;
        }
        return keep == 0 ? new com.legend.values.PureTimeLiteral.TimeWithSecond(h, mi, sec)
                : new com.legend.values.PureTimeLiteral.TimeWithSubsecond(h, mi, sec, fraction.substring(0, keep));
    }

    /** The time form read character by character: {@code hh:mm[:ss[.f{1,9}]]}; null when the text is not of that
     *  form. A fraction's trailing zeros past the sixth digit are dropped (the database keeps microseconds). */
    private static com.legend.values.@com.legend.base.Nullable PureTimeLiteral timeLiteral(String text) {
        int n = text.length();
        if (n < 5 || !digits(text, 0, 2) || text.charAt(2) != ':' || !digits(text, 3, 2)) {
            return null;
        }
        int h = Integer.parseInt(text.substring(0, 2));
        int mi = Integer.parseInt(text.substring(3, 5));
        if (h > 23 || mi > 59) {
            return null;
        }
        if (n == 5) {
            return new com.legend.values.PureTimeLiteral.TimeWithMinute(h, mi);
        }
        if (n < 8 || text.charAt(5) != ':' || !digits(text, 6, 2)) {
            return null;
        }
        int sec = Integer.parseInt(text.substring(6, 8));
        if (sec > 59) {
            return null;
        }
        if (n == 8) {
            return new com.legend.values.PureTimeLiteral.TimeWithSecond(h, mi, sec);
        }
        if (text.charAt(8) != '.' || n > 18 || !digits(text, 9, n - 9)) {
            return null;
        }
        String fraction = text.substring(9);
        int keep = fraction.length();
        while (keep > 6 && fraction.charAt(keep - 1) == '0') {
            keep--;
        }
        return new com.legend.values.PureTimeLiteral.TimeWithSubsecond(h, mi, sec, fraction.substring(0, keep));
    }

    /** {@code localtime({...})}: a time of day from its components, or another time (or a date-time's) with
     *  fields written over it. */
    private Val timeFromComponents(CypherAst.Call c, MapVal m) {
        Map<Component, Tx> parts = new LinkedHashMap<>();
        Tx base = null;
        for (Map.Entry<String, Val> e : m.entries().entrySet()) {
            String key = e.getKey();
            Component k = COMPONENTS.get(key.toLowerCase(Locale.ROOT));
            if (k == null) {
                throw CypherErrors.invalid("No such field: " + key, c.pos());
            }
            if (!(unzoned(e.getValue(), c.pos()) instanceof Scalar s)) {
                throw CypherErrors.invalid("Type mismatch: the component " + key + " must be a scalar", c.pos());
            }
            Tx t = s.tx();
            if (t.kind() == Kind.NULL) {
                if (k == Component.TIME) {
                    throw CypherErrors.invalid("Cannot construct local time from: NO_VALUE", c.pos());
                }
                // (Neo4j 2026.09.0 raises an internal error for a null field, as for a date's; the island answers
                // null)
                return nullValue();
            }
            switch (k) {
                case TIME -> {
                    if (t.kind() != Kind.TIME && t.kind() != Kind.DATETIME) {
                        throw CypherErrors.invalid("Type mismatch: time must be a Time but was "
                                + cypherType(t.kind()), c.pos());
                    }
                    base = t;
                }
                case TIMEZONE -> throw CypherErrors.unsupported("a time zone (a zoned time: no Pure type carries"
                        + " one) is not supported yet", c.pos());
                case DATE, DATETIME -> throw CypherErrors.invalid("Not supported: " + k.cypher, c.pos());
                default -> {
                    if (!k.time()) {
                        throw CypherErrors.invalid("Unsupported field: " + capitalized(k.cypher), c.pos());
                    }
                    if (t.kind() != Kind.INTEGER) {
                        throw CypherErrors.invalid("Type mismatch: " + key + " must be an Integer but was "
                                + cypherType(t.kind()), c.pos());
                    }
                    parts.put(k, t);
                }
            }
        }
        if (base == null && !parts.containsKey(Component.HOUR)) {
            throw CypherErrors.invalid("hour must be specified", c.pos());
        }
        boolean nanosComponent = nanosComponent(parts.get(Component.NANOSECOND), c.pos());
        if (base != null && parts.isEmpty()) {
            Tx b = base;
            return b.kind() == Kind.TIME ? scalar(b) : scalar(timeOf(b));
        }
        List<Tx> inputs = new ArrayList<>(parts.values());
        if (base != null) {
            inputs.add(0, base);
        }
        boolean hasBase = base != null;
        boolean baseIsTime = base != null && base.kind() == Kind.TIME;
        boolean baseNanos = base != null && nanos(base);
        boolean fine = baseNanos || nanosComponent;
        Set<Component> keys = parts.keySet();
        return scalar(withNanos(SqlToPure.strict(Kind.TIME, inputs, v -> {
            Map<Component, ValueSpecification> p = new java.util.EnumMap<>(Component.class);
            int i = hasBase ? 1 : 0;
            for (Component k : keys) {
                p.put(k, v.get(i++));
            }
            // (a nanosecond time of day is the date-time at it on the epoch day already)
            ValueSpecification baseTime = hasBase ? baseIsTime && !baseNanos ? atEpoch(v.get(0)) : v.get(0) : null;
            ValueSpecification one = new CInteger(1L);
            ValueSpecification at = timeParts(new CInteger(1970L), one, one, baseTime, p, baseNanos, nanosComponent);
            return fine ? at : call(TIME_OF, at);
        }), fine));
    }

    /** A date-time: the date (its year, month and day) at the time the components spell — each of hour, minute and
     *  second as written, else the base time's, else 0; the seconds whole (a fractional second makes lite's
     *  {@code date(...)} a text carrier, 2026-10-16), the fraction the base time's, kept through a written-over
     *  second (the TCK's Temporal3 [7]) unless a sub-second field is written, which is then added as milliseconds
     *  and microseconds. */
    private static ValueSpecification timeParts(ValueSpecification year, ValueSpecification month,
            ValueSpecification day, @com.legend.base.Nullable ValueSpecification baseTime,
            Map<Component, ValueSpecification> p, boolean baseNanos, boolean nanosComponent) {
        ValueSpecification h = p.containsKey(Component.HOUR) ? p.get(Component.HOUR) : baseTime != null
                ? call("hour", baseTime) : new CInteger(0L);
        ValueSpecification mi = p.containsKey(Component.MINUTE) ? p.get(Component.MINUTE) : baseTime != null
                ? call("minute", baseTime) : new CInteger(0L);
        ValueSpecification s = p.containsKey(Component.SECOND) ? p.get(Component.SECOND) : baseTime != null
                ? call("second", baseTime) : new CInteger(0L);
        ValueSpecification dt = call("date", year, month, day, integer(h), integer(mi), integer(s));
        if (baseTime != null && !p.containsKey(Component.MILLISECOND) && !p.containsKey(Component.MICROSECOND)
                && !p.containsKey(Component.NANOSECOND)) {
            String u = baseNanos ? "NANOSECONDS" : "MICROSECONDS";
            dt = call("adjust", dt, call("dateDiff", call("firstMillisecondOfSecond", baseTime), baseTime,
                    unit(u)), unit(u));
        }
        if (p.containsKey(Component.MILLISECOND)) {
            dt = call("adjust", dt, integer(p.get(Component.MILLISECOND)), unit("MILLISECONDS"));
        }
        if (p.containsKey(Component.MICROSECOND)) {
            dt = call("adjust", dt, integer(p.get(Component.MICROSECOND)), unit("MICROSECONDS"));
        }
        if (p.containsKey(Component.NANOSECOND)) {
            dt = nanosComponent ? call("adjust", dt, integer(p.get(Component.NANOSECOND)), unit("NANOSECONDS"))
                    : call("adjust", dt, integer(call("divide", p.get(Component.NANOSECOND), new CInteger(1000L))),
                            unit("MICROSECONDS"));
        }
        return dt;
    }

    /** Whether a nanosecond component makes the value of nanosecond precision: a spelled number that is not a
     *  multiple of a thousand (a multiple is the database's microseconds, every engine's); one of the row is
     *  refused by name. */
    private static boolean nanosComponent(@com.legend.base.Nullable Tx t, Pos pos) {
        if (t == null) {
            return false;
        }
        if (!(t.vs() instanceof CInteger n)) {
            throw CypherErrors.unsupported("a nanosecond component that is not a spelled number (the database's"
                    + " nanoseconds are DuckDB's) is not supported yet", pos);
        }
        return n.value().longValue() % 1000 != 0;
    }

    /** {@code dt} with the sub-second fields written over it added, the nanoseconds as the database's nanosecond
     *  step when of nanosecond precision, else as the microseconds they are. */
    private static ValueSpecification withSubSecond(ValueSpecification dt, Map<Component, ValueSpecification> o,
            boolean nanosField) {
        ValueSpecification out = dt;
        if (o.containsKey(Component.MILLISECOND)) {
            out = call("adjust", out, integer(o.get(Component.MILLISECOND)), unit("MILLISECONDS"));
        }
        if (o.containsKey(Component.MICROSECOND)) {
            out = call("adjust", out, integer(o.get(Component.MICROSECOND)), unit("MICROSECONDS"));
        }
        if (o.containsKey(Component.NANOSECOND)) {
            out = nanosField ? call("adjust", out, integer(o.get(Component.NANOSECOND)), unit("NANOSECONDS"))
                    : call("adjust", out, SqlToPure.integerQuotient(o.get(Component.NANOSECOND), new CInteger(
                            1000L)), unit("MICROSECONDS"));
        }
        return out;
    }

    /** {@code x} moved by a duration's seconds and nanoseconds: the seconds an interval step (a nanosecond value's
     *  sub-microsecond digits put back after it), the nanoseconds a nanosecond step — two steps, so no product of
     *  the seconds and a billion (which the database folds to 32 bits over a literal-built slot). */
    private static ValueSpecification secondsAndNanos(ValueSpecification x, ValueSpecification seconds,
            ValueSpecification nanos, boolean xNanos) {
        ValueSpecification moved = call("adjust", x, integer(seconds), unit("SECONDS"));
        if (xNanos) {
            moved = remainderKept(moved, x);
        }
        return call("adjust", moved, integer(nanos), unit("NANOSECONDS"));
    }

    /** A calendar step of a value ({@code adjust} by months, days, ...: an interval, microseconds), a nanosecond
     *  value's sub-microsecond digits put back. */
    private static ValueSpecification step(ValueSpecification x, ValueSpecification n, String unitName,
            boolean nanos) {
        ValueSpecification moved = call("adjust", x, integer(n), unit(unitName));
        return nanos ? remainderKept(moved, x) : moved;
    }

    /** A temporal value from a text: a literal in the ISO calendar form is Pure's date literal; any other literal
     *  form (compact, week, ordinal, a zone) is refused by name; a text of the row is the database's parse. */
    private Val temporalFromText(CypherAst.Call c, Scalar s, boolean withTime) {
        Tx t = s.tx();
        if (!s.literal()) {
            return scalar(SqlToPure.strict(withTime ? Kind.DATETIME : Kind.DATE, List.of(t), x -> withTime
                    ? call("parseDate", x.get(0)) : call("datePart", call("parseDate", x.get(0)))));
        }
        String text = (String) java.util.Objects.requireNonNull(s.constant());
        com.legend.values.PureDateLiteral lit = isoLiteral(text, withTime);
        if (lit == null) {
            expandedYear(text, c.pos());
            TemporalText tt = TemporalText.read(text, true, withTime);
            if (tt == null || tt.zoneText() != null || tt.zoneName() != null) {
                throw CypherErrors.invalid("Text cannot be parsed to a " + (withTime ? "LocalDateTime" : "Date")
                        + ": \"" + text + "\"", c.pos());
            }
            return withTime ? dateTimeOfText(c, tt, text) : scalar(Tx.value(dateOfText(c, tt), Kind.DATE, false));
        }
        // (the literal carries its written form, so the Pure the island writes prints)
        return scalar(dateTimeValue(lit, withTime ? Kind.DATETIME : Kind.DATE, c.pos()));
    }

    /** A date or date-time literal as the island's value: to the microsecond Pure's literal; finer, the literal to
     *  the microsecond moved by the sub-microsecond digits (a nanosecond date-time). */
    private static Tx dateTimeValue(com.legend.values.PureDateLiteral lit, Kind kind, Pos pos) {
        if (lit instanceof com.legend.values.PureDateLiteral.DateWithSubsecond sub && sub.subsecond().length() > 6) {
            nanosRange(sub.year(), pos);
            com.legend.values.PureDateLiteral micro = new com.legend.values.PureDateLiteral.DateWithSubsecond(
                    sub.year(), sub.month(), sub.day(), sub.hour(), sub.minute(), sub.second(),
                    sub.subsecond().substring(0, 6));
            return finer(new com.legend.protocol.spec.CDate(micro, micro.toEngineString(), null),
                    subMicro(sub.subsecond()), kind);
        }
        return Tx.value(new com.legend.protocol.spec.CDate(lit, lit.toEngineString(), null), kind, false);
    }

    /**
     * A temporal text in the forms Neo4j reads beyond the ISO calendar one ({@code isoLiteral}): the date compact
     * ({@code 20150721}), a month ({@code 2015-07}, {@code 201507}), a year ({@code 2015}), the week forms
     * ({@code 2015-W30-2}, {@code 2015W302}, {@code 2015-W30}, {@code 2015W30}), the ordinal forms ({@code 2015-202},
     * {@code 2015202}), the quarter forms ({@code 2015-Q2-60}, {@code 2015Q260}, {@code 2015-Q2}, {@code 2015Q2});
     * a time ({@code 21:40:32.142}, {@code 214032.142}, {@code 21:40}, {@code 2140}, {@code 21}) after a {@code T},
     * or alone; a zone ({@code Z}, an offset, a region's name in brackets) after the time. Null when the text is
     * none of these. The parts are the digits as written; what they spell is the database's. (Package-private:
     * its one construction is inside its own body, which the dead-code guard would read as no use of a private
     * one.)
     */
    record TemporalText(Map<Component, Long> date, boolean calendar, @com.legend.base.Nullable Integer hour,
            @com.legend.base.Nullable Integer minute, @com.legend.base.Nullable Integer second, String fraction,
            @com.legend.base.Nullable String zoneText, @com.legend.base.Nullable String zoneName) {

        boolean hasTime() {
            return hour != null;
        }

        static @com.legend.base.Nullable TemporalText read(String text, boolean wantDate, boolean wantTime) {
            String rest = text;
            String zoneName = null;
            if (rest.endsWith("]")) {
                int open = rest.lastIndexOf('[');
                if (open < 0) {
                    return null;
                }
                zoneName = rest.substring(open + 1, rest.length() - 1);
                rest = rest.substring(0, open);
            }
            String datePart = null;
            String timePart = null;
            if (wantDate && wantTime) {
                int t = rest.indexOf('T');
                if (t < 0) {
                    return null;
                }
                datePart = rest.substring(0, t);
                timePart = rest.substring(t + 1);
            } else if (wantDate) {
                datePart = rest;
            } else {
                timePart = rest;
            }
            Map<Component, Long> date = new java.util.EnumMap<>(Component.class);
            boolean calendar = true;
            if (datePart != null) {
                calendar = readDate(datePart, date);
                if (date.isEmpty()) {
                    return null;
                }
            }
            Integer hour = null;
            Integer minute = null;
            Integer second = null;
            String fraction = "";
            String zoneText = null;
            if (timePart != null) {
                int at = -1;
                for (int i = 0; i < timePart.length(); i++) {
                    char ch = timePart.charAt(i);
                    if (ch == 'Z' || ch == 'z' || ch == '+' || ch == '-') {
                        at = i;
                        break;
                    }
                }
                String clock = at < 0 ? timePart : timePart.substring(0, at);
                zoneText = at < 0 ? null : timePart.substring(at);
                int[] hms = new int[3];
                String[] frac = new String[1];
                int parts = readTime(clock, hms, frac);
                if (parts == 0) {
                    return null;
                }
                hour = hms[0];
                minute = parts > 1 ? hms[1] : 0;
                second = parts > 2 ? hms[2] : 0;
                fraction = frac[0] == null ? "" : frac[0];
            }
            return new TemporalText(date, calendar, hour, minute, second, fraction, zoneText, zoneName);
        }

        /** The date part into {@code out}; true for a calendar form (year, month, day — the month and day 1 when
         *  not written); false for a week, ordinal or quarter form; nothing in {@code out} when malformed. */
        private static boolean readDate(String d, Map<Component, Long> out) {
            boolean dashed = d.length() > 4 && d.charAt(4) == '-';
            String s = dashed ? d.replace("-", "") : d;
            if (s.length() < 4 || !digits(s, 0, 4)) {
                return true;
            }
            long year = Long.parseLong(s.substring(0, 4));
            String tail = s.substring(4);
            if (dashed && d.contains("--")) {
                return true;
            }
            if (tail.isEmpty()) {
                out.put(Component.YEAR, year);
                out.put(Component.MONTH, 1L);
                out.put(Component.DAY, 1L);
                return true;
            }
            if (tail.charAt(0) == 'W') {
                String w = tail.substring(1);
                if (w.length() == 2 && digits(w, 0, 2)) {
                    out.put(Component.YEAR, year);
                    out.put(Component.WEEK, Long.parseLong(w));
                    out.put(Component.DAY_OF_WEEK, 1L);
                    return false;
                }
                if (w.length() == 3 && digits(w, 0, 3)) {
                    out.put(Component.YEAR, year);
                    out.put(Component.WEEK, Long.parseLong(w.substring(0, 2)));
                    out.put(Component.DAY_OF_WEEK, Long.parseLong(w.substring(2)));
                    return false;
                }
                return true;
            }
            if (tail.charAt(0) == 'Q') {
                String q = tail.substring(1);
                if (q.length() == 1 && digits(q, 0, 1)) {
                    out.put(Component.YEAR, year);
                    out.put(Component.QUARTER, Long.parseLong(q));
                    out.put(Component.DAY_OF_QUARTER, 1L);
                    return false;
                }
                if (q.length() == 3 && digits(q, 0, 3)) {
                    out.put(Component.YEAR, year);
                    out.put(Component.QUARTER, Long.parseLong(q.substring(0, 1)));
                    out.put(Component.DAY_OF_QUARTER, Long.parseLong(q.substring(1)));
                    return false;
                }
                return true;
            }
            if (!digits(tail, 0, tail.length())) {
                return true;
            }
            if (tail.length() == 2) {
                out.put(Component.YEAR, year);
                out.put(Component.MONTH, Long.parseLong(tail));
                out.put(Component.DAY, 1L);
                return true;
            }
            if (tail.length() == 4) {
                out.put(Component.YEAR, year);
                out.put(Component.MONTH, Long.parseLong(tail.substring(0, 2)));
                out.put(Component.DAY, Long.parseLong(tail.substring(2)));
                return true;
            }
            if (tail.length() == 3) {
                out.put(Component.YEAR, year);
                out.put(Component.ORDINAL_DAY, Long.parseLong(tail));
                return false;
            }
            return true;
        }

        /** The clock part: how many of hour, minute and second were written (0 when malformed), the fraction. */
        private static int readTime(String clock, int[] hms, String[] fraction) {
            String s = clock;
            int dot = s.indexOf('.');
            if (dot < 0) {
                dot = s.indexOf(',');
            }
            if (dot >= 0) {
                String f = s.substring(dot + 1);
                if (f.isEmpty() || !digits(f, 0, f.length()) || f.length() > 9) {
                    return 0;
                }
                fraction[0] = f;
                s = s.substring(0, dot);
            }
            String compact = s.replace(":", "");
            if (compact.isEmpty() || !digits(compact, 0, compact.length())) {
                return 0;
            }
            boolean colons = s.contains(":");
            if (colons && !(s.length() == 5 && s.charAt(2) == ':' || s.length() == 8 && s.charAt(2) == ':'
                    && s.charAt(5) == ':')) {
                return 0;
            }
            int parts = compact.length() == 2 ? 1 : compact.length() == 4 ? 2 : compact.length() == 6 ? 3 : 0;
            if (parts == 0 || fraction[0] != null && parts < 3) {
                return 0;
            }
            hms[0] = Integer.parseInt(compact.substring(0, 2));
            hms[1] = parts > 1 ? Integer.parseInt(compact.substring(2, 4)) : 0;
            hms[2] = parts > 2 ? Integer.parseInt(compact.substring(4, 6)) : 0;
            if (hms[0] > 23 || hms[1] > 59 || hms[2] > 59) {
                return 0;
            }
            return parts;
        }
    }

    /** A text whose year is signed (ISO's expanded form, {@code +999999999-12-31}, {@code -0044-03-15}): Neo4j's
     *  nine-digit years are beyond the database's calendar, and a year before 1 its own reading; refused by name. */
    private static void expandedYear(String text, Pos pos) {
        if (text.startsWith("+") || text.startsWith("-")) {
            throw CypherErrors.unsupported("a signed year (" + text + ", ISO's expanded form) is not supported yet",
                    pos);
        }
    }

    /** The component a calendar text spelled (the reader put all three there). */
    private static int part(Map<Component, Long> d, Component k) {
        return java.util.Objects.requireNonNull(d.get(k), k.name()).intValue();
    }

    /** A date a text spells: the calendar form as Pure's literal, the others as the components' spelling. */
    private ValueSpecification dateOfText(CypherAst.Call c, TemporalText t) {
        Map<Component, Long> d = t.date();
        if (t.calendar()) {
            com.legend.values.PureDateLiteral lit = new com.legend.values.PureDateLiteral.StrictDate(
                    part(d, Component.YEAR), part(d, Component.MONTH), part(d, Component.DAY));
            return new com.legend.protocol.spec.CDate(lit, lit.toEngineString(), null);
        }
        Map<Component, ValueSpecification> p = new java.util.EnumMap<>(Component.class);
        d.forEach((k, v) -> p.put(k, lit(v)));
        return dateOf(c, null, p);
    }

    /** A date-time a text spells: the calendar form with its time as Pure's literal, the others as the date's
     *  spelling at the time written (the fraction as microseconds). */
    private Val dateTimeOfText(CypherAst.Call c, TemporalText t, String text) {
        String fraction = t.fraction();
        int keep = fraction.length();
        while (keep > 6 && fraction.charAt(keep - 1) == '0') {
            keep--;
        }
        fraction = fraction.substring(0, keep);
        long rest = subMicro(fraction);
        Map<Component, Long> d = t.date();
        if (t.calendar()) {
            int y = part(d, Component.YEAR);
            int mo = part(d, Component.MONTH);
            int day = part(d, Component.DAY);
            int h = java.util.Objects.requireNonNull(t.hour());
            int mi = java.util.Objects.requireNonNull(t.minute());
            int s = java.util.Objects.requireNonNull(t.second());
            com.legend.values.PureDateLiteral lit = fraction.isEmpty()
                    ? new com.legend.values.PureDateLiteral.DateWithSecond(y, mo, day, h, mi, s)
                    : new com.legend.values.PureDateLiteral.DateWithSubsecond(y, mo, day, h, mi, s, fraction);
            return scalar(dateTimeValue(lit, Kind.DATETIME, c.pos()));
        }
        ValueSpecification date = dateOfText(c, t);
        Map<Component, ValueSpecification> p = new java.util.EnumMap<>(Component.class);
        p.put(Component.HOUR, lit(java.util.Objects.requireNonNull(t.hour())));
        p.put(Component.MINUTE, lit(java.util.Objects.requireNonNull(t.minute())));
        p.put(Component.SECOND, lit(java.util.Objects.requireNonNull(t.second())));
        if (!fraction.isEmpty()) {
            p.put(Component.MICROSECOND, lit(Long.parseLong((fraction + "000000").substring(0, 6))));
        }
        if (rest != 0) {
            nanosRange(part(d, Component.YEAR), c.pos());
            p.put(Component.NANOSECOND, lit(rest));
        }
        return scalar(withNanos(Tx.value(timeParts(call("year", date), call("monthNumber", date),
                call("dayOfMonth", date), null, p, false, rest != 0), Kind.DATETIME, false), rest != 0));
    }

    /** The ISO calendar form read character by character: {@code yyyy-mm-dd}, and with a time
     *  {@code Thh:mm[:ss[.f{1,9}]]}; null when the text is not of that form. A fraction's trailing zeros past the
     *  sixth digit are dropped (the database keeps microseconds); other digits there make it longer than six, which
     *  the caller refuses. */
    private static com.legend.values.@com.legend.base.Nullable PureDateLiteral isoLiteral(String text,
            boolean withTime) {
        int n = text.length();
        if (n < 10 || !digits(text, 0, 4) || text.charAt(4) != '-' || !digits(text, 5, 2) || text.charAt(7) != '-'
                || !digits(text, 8, 2)) {
            return null;
        }
        int y = Integer.parseInt(text.substring(0, 4));
        int mo = Integer.parseInt(text.substring(5, 7));
        int d = Integer.parseInt(text.substring(8, 10));
        if (!withTime) {
            return n == 10 ? new com.legend.values.PureDateLiteral.StrictDate(y, mo, d) : null;
        }
        if (n < 16 || text.charAt(10) != 'T' || !digits(text, 11, 2) || text.charAt(13) != ':'
                || !digits(text, 14, 2)) {
            return null;
        }
        int h = Integer.parseInt(text.substring(11, 13));
        int mi = Integer.parseInt(text.substring(14, 16));
        if (n == 16) {
            return new com.legend.values.PureDateLiteral.DateWithMinute(y, mo, d, h, mi);
        }
        if (n < 19 || text.charAt(16) != ':' || !digits(text, 17, 2)) {
            return null;
        }
        int sec = Integer.parseInt(text.substring(17, 19));
        if (n == 19) {
            return new com.legend.values.PureDateLiteral.DateWithSecond(y, mo, d, h, mi, sec);
        }
        if (text.charAt(19) != '.' || n > 29 || !digits(text, 20, n - 20)) {
            return null;
        }
        String fraction = text.substring(20);
        int keep = fraction.length();
        while (keep > 6 && fraction.charAt(keep - 1) == '0') {
            keep--;
        }
        return new com.legend.values.PureDateLiteral.DateWithSubsecond(y, mo, d, h, mi, sec,
                fraction.substring(0, keep));
    }

    private static boolean digits(String text, int from, int count) {
        if (count < 1 || from + count > text.length()) {
            return false;
        }
        for (int i = from; i < from + count; i++) {
            if (text.charAt(i) < '0' || text.charAt(i) > '9') {
                return false;
            }
        }
        return true;
    }

    /** A temporal value from its components: the calendar, week, quarter or ordinal form, or another temporal
     *  value under {@code date} or {@code datetime} with fields written over it. */
    private Val temporalFromComponents(CypherAst.Call c, MapVal m, boolean withTime, Kind result) {
        Map<Component, Tx> parts = new LinkedHashMap<>();
        Tx base = null;
        Tx timeBase = null;
        boolean baseHasTime = false;
        for (Map.Entry<String, Val> e : m.entries().entrySet()) {
            String key = e.getKey();
            Component k = COMPONENTS.get(key.toLowerCase(Locale.ROOT));
            if (k == null) {
                throw CypherErrors.invalid("No such field: " + key, c.pos());
            }
            if (!(unzoned(e.getValue(), c.pos()) instanceof Scalar s)) {
                throw CypherErrors.invalid("Type mismatch: the component " + key + " must be a scalar", c.pos());
            }
            Tx t = s.tx();
            if (t.kind() == Kind.NULL) {
                // (Neo4j 2026.09.0 raises an internal error here, 50N42; the island answers null)
                return nullValue();
            }
            switch (k) {
                case DATE -> {
                    if (t.kind() != Kind.DATE && t.kind() != Kind.DATETIME) {
                        throw CypherErrors.invalid("Type mismatch: date must be a Date but was "
                                + cypherType(t.kind()), c.pos());
                    }
                    base = t.kind() == Kind.DATE ? t : Tx.value(call("datePart", SqlToPure.valueOf(t)), Kind.DATE,
                            t.nullable());
                }
                case DATETIME -> {
                    if (t.kind() != Kind.DATETIME) {
                        throw CypherErrors.invalid("Type mismatch: datetime must be a DateTime but was "
                                + cypherType(t.kind()), c.pos());
                    }
                    base = t;
                    baseHasTime = true;
                }
                case TIME -> {
                    if (!withTime) {
                        throw CypherErrors.invalid("Not supported: time", c.pos());
                    }
                    if (t.kind() != Kind.TIME && t.kind() != Kind.DATETIME) {
                        throw CypherErrors.invalid("Type mismatch: time must be a Time but was "
                                + cypherType(t.kind()), c.pos());
                    }
                    timeBase = t;
                }
                case TIMEZONE -> throw CypherErrors.invalid("Cannot assign time zone if also assigning other"
                        + " fields.", c.pos());
                default -> {
                    if (t.kind() != Kind.INTEGER) {
                        throw CypherErrors.invalid("Type mismatch: " + key + " must be an Integer but was "
                                + cypherType(t.kind()), c.pos());
                    }
                    parts.put(k, t);
                }
            }
        }
        boolean anyTime = parts.keySet().stream().anyMatch(Component::time);
        if (!withTime && anyTime) {
            throw CypherErrors.invalid("Cannot assign a time component to a Date", c.pos());
        }
        boolean nanosComponent = withTime && nanosComponent(parts.get(Component.NANOSECOND), c.pos());
        // the date: the components the database reads, in order
        List<Tx> inputs = new ArrayList<>(parts.values());
        if (timeBase != null) {
            inputs.add(0, timeBase);
        }
        if (base != null) {
            inputs.add(0, base);
        }
        boolean hasBase = base != null;
        boolean hasBaseTime = baseHasTime;
        boolean hasTimeBase = timeBase != null;
        boolean timeBaseIsTime = timeBase != null && timeBase.kind() == Kind.TIME;
        boolean timeBaseNanos = timeBase != null && nanos(timeBase);
        boolean baseNanos = timeBase != null ? timeBaseNanos : baseHasTime && base != null && nanos(base);
        boolean fine = withTime && (baseNanos || nanosComponent);
        if (fine && parts.get(Component.YEAR) != null && parts.get(Component.YEAR).vs() instanceof CInteger y) {
            nanosRange(y.value().longValue(), c.pos());
        }
        Set<Component> keys = parts.keySet();
        if (hasBaseTime && keys.isEmpty() && !hasTimeBase) {
            return scalar(java.util.Objects.requireNonNull(base));
        }
        if (hasBase && !hasBaseTime && timeBaseIsTime && keys.isEmpty()) {
            // localdatetime({date: d, time: t}): the date-time at that time of that day (a nanosecond time's
            // nanoseconds since its midnight on the date's)
            return scalar(withNanos(SqlToPure.strict(result, inputs, v -> timeBaseNanos
                    ? call("adjust", asMidnight(v.get(0)), integer(epochNanos(v.get(1))), unit("NANOSECONDS"))
                    : call(AT_TIME, v.get(0), v.get(1))), fine));
        }
        return scalar(withNanos(SqlToPure.strict(result, inputs, v -> {
            Map<Component, ValueSpecification> p = new java.util.EnumMap<>(Component.class);
            int i = (hasBase ? 1 : 0) + (hasTimeBase ? 1 : 0);
            for (Component k : keys) {
                p.put(k, v.get(i++));
            }
            ValueSpecification date = dateOf(c, hasBase ? v.get(0) : null, p);
            if (!withTime) {
                return date;
            }
            ValueSpecification tb = hasTimeBase ? v.get(hasBase ? 1 : 0) : null;
            ValueSpecification baseTime = tb != null ? timeBaseIsTime && !timeBaseNanos ? atEpoch(tb) : tb
                    : hasBaseTime ? v.get(0) : null;
            return timeParts(call("year", date), call("monthNumber", date), call("dayOfMonth", date), baseTime, p,
                    baseNanos, nanosComponent);
        }), fine));
    }

    /** The date-time at a time of day of a date ({@code atTime}); a nanosecond time's nanoseconds since its
     *  midnight added to the date's. */
    private static ValueSpecification atTimeOf(ValueSpecification date, ValueSpecification time, boolean nanos) {
        return nanos ? call("adjust", asMidnight(date), integer(epochNanos(time)), unit("NANOSECONDS"))
                : call(AT_TIME, date, time);
    }

    /** A date as the date-time at its midnight. */
    private static ValueSpecification asMidnight(ValueSpecification d) {
        return call(AT_TIME, d, new com.legend.protocol.spec.CTime(
                new com.legend.values.PureTimeLiteral.TimeWithMinute(0, 0), "00:00", null));
    }

    /** The date (Pure's StrictDate) the components spell: over a base date, its fields written over; else the
     *  calendar form {@code date(y, m, d)}, the week form (the Monday of ISO week 1 is the Monday of the week of
     *  January 4th), the quarter form, the ordinal form. */
    private ValueSpecification dateOf(CypherAst.Call c, @com.legend.base.Nullable ValueSpecification base,
            Map<Component, ValueSpecification> p) {
        ValueSpecification one = new CInteger(1L);
        boolean week = p.containsKey(Component.WEEK) || p.containsKey(Component.DAY_OF_WEEK);
        boolean quarter = p.containsKey(Component.QUARTER) || p.containsKey(Component.DAY_OF_QUARTER);
        boolean ordinal = p.containsKey(Component.ORDINAL_DAY);
        boolean calendar = p.containsKey(Component.MONTH) || p.containsKey(Component.DAY);
        if ((week ? 1 : 0) + (quarter ? 1 : 0) + (ordinal ? 1 : 0) + (calendar ? 1 : 0) > 1) {
            throw CypherErrors.invalid("Cannot mix the calendar, week, quarter and ordinal date forms", c.pos());
        }
        if (base != null) {
            if (week || quarter || ordinal) {
                // the form's fields not written are the base's (Neo4j's: {date: d, week: 1} keeps d's day of the
                // week in its week-year; {date: d, quarter: 3} keeps d's day of the quarter), the database's reads
                Map<Component, ValueSpecification> q = new java.util.EnumMap<>(Component.class);
                if (week) {
                    q.put(Component.YEAR, p.getOrDefault(Component.YEAR, call("year", thursdayOf(base))));
                    q.put(Component.WEEK, p.getOrDefault(Component.WEEK, call("weekOfYear", base)));
                    q.put(Component.DAY_OF_WEEK, p.getOrDefault(Component.DAY_OF_WEEK, call("dayOfWeekNumber",
                            base)));
                } else if (quarter) {
                    q.put(Component.YEAR, p.getOrDefault(Component.YEAR, call("year", base)));
                    q.put(Component.QUARTER, p.getOrDefault(Component.QUARTER, call("quarterNumber", base)));
                    q.put(Component.DAY_OF_QUARTER, p.getOrDefault(Component.DAY_OF_QUARTER, integer(
                            AppliedFunction.infixRun("plus", List.of(call("dateDiff", call("firstDayOfQuarter",
                                    base), base, days()), one)))));
                } else {
                    q.put(Component.YEAR, p.getOrDefault(Component.YEAR, call("year", base)));
                    q.put(Component.ORDINAL_DAY, p.getOrDefault(Component.ORDINAL_DAY, call("dayOfYear", base)));
                }
                return dateOf(c, null, q);
            }
            if (!calendar && !p.containsKey(Component.YEAR)) {
                return base;
            }
            return call("date", integer(p.getOrDefault(Component.YEAR, call("year", base))),
                    integer(p.getOrDefault(Component.MONTH, call("monthNumber", base))),
                    integer(p.getOrDefault(Component.DAY, call("dayOfMonth", base))));
        }
        ValueSpecification year = p.get(Component.YEAR);
        if (year == null) {
            throw CypherErrors.invalid("year must be specified", c.pos());
        }
        year = integer(year);
        if (week) {
            // the Monday of week 1, then (week - 1) weeks and (dayOfWeek - 1) days on
            ValueSpecification jan4 = call("date", year, one, new CInteger(4L));
            ValueSpecification monday = call("adjust", jan4, integer(AppliedFunction.infixRun("minus", List.of(one,
                    call("dayOfWeekNumber", jan4)))), days());
            ValueSpecification on = AppliedFunction.infixRun("plus", List.of(AppliedFunction.infixRun("times",
                    List.of(AppliedFunction.infixRun("minus", List.of(p.getOrDefault(Component.WEEK, one), one)),
                            new CInteger(7L))), AppliedFunction.infixRun("minus", List.of(p.getOrDefault(
                                    Component.DAY_OF_WEEK, one), one))));
            return day(call("adjust", monday, integer(on), days()));
        }
        if (quarter) {
            ValueSpecification firstMonth = AppliedFunction.infixRun("plus", List.of(AppliedFunction.infixRun("times",
                    List.of(AppliedFunction.infixRun("minus", List.of(p.getOrDefault(Component.QUARTER, one), one)),
                            new CInteger(3L))), one));
            return day(call("adjust", call("date", year, integer(firstMonth), one), integer(AppliedFunction.infixRun(
                    "minus", List.of(p.getOrDefault(Component.DAY_OF_QUARTER, one), one))), days()));
        }
        if (ordinal) {
            return day(call("adjust", call("date", year, one, one), integer(AppliedFunction.infixRun("minus",
                    List.of(p.get(Component.ORDINAL_DAY), one))), days()));
        }
        return call("date", year, integer(p.getOrDefault(Component.MONTH, one)), integer(p.getOrDefault(Component.DAY,
                one)));
    }

    /** {@code d.year}, {@code d.quarter}, {@code d.month}, {@code d.week}, {@code d.weekYear}, {@code d.day},
     *  {@code d.ordinalDay}, {@code d.weekDay}, {@code d.dayOfQuarter}; of a date-time also {@code hour},
     *  {@code minute}, {@code second}; the sub-second fields are refused (microseconds are the database's). */
    private static Tx temporalField(Tx t, String name, Pos pos) {
        TemporalField field = TEMPORAL_FIELDS.get(name);
        if (field == null) {
            throw CypherErrors.invalid("No such field: " + name, pos);
        }
        boolean dateTime = t.kind() == Kind.DATETIME || t.kind() == Kind.ZONED;
        boolean time = t.kind() == Kind.TIME || t.kind() == Kind.ZONED_TIME;
        if (time && !field.time() && !field.zoned()) {
            throw CypherErrors.invalid("Unsupported field: " + capitalized(name), pos);
        }
        if (field.zoned()) {
            if (t.kind() == Kind.ZONED_TIME && (field == TemporalField.EPOCH_SECONDS
                    || field == TemporalField.EPOCH_MILLIS)) {
                throw CypherErrors.invalid("Epoch not supported.", pos);
            }
            if (t.kind() != Kind.ZONED && t.kind() != Kind.ZONED_TIME) {
                // Neo4j's error names the value: spelled for a written one, refused by name for one of the row
                String printed = printed(t);
                if (printed == null) {
                    throw CypherErrors.unsupported("the " + name + " of a " + cypherType(t.kind()) + " of the row"
                            + " (Neo4j's error names the value) is not supported yet", pos);
                }
                throw CypherErrors.invalid("Cannot get the offset of: " + printed, pos);
            }
            return zoneField(t, field, zoneKnown(t, pos), pos);
        }
        // a time of day's fields are read off the date-time at it; a zoned date-time's off its wall time
        Tx x = t.kind() == Kind.TIME ? atEpoch(t) : t.kind() == Kind.ZONED_TIME ? wallAtEpoch(t, zoneKnown(t, pos))
                : t.kind() == Kind.ZONED ? localOf(t, zoneKnown(t, pos)) : t;
        return switch (field) {
            case YEAR -> field(x, v -> call("year", v));
            case QUARTER -> field(x, v -> call("quarterNumber", v));
            case MONTH -> field(x, v -> call("monthNumber", v));
            case WEEK -> field(x, v -> call("weekOfYear", v));
            case WEEK_YEAR -> field(x, v -> call("year", thursdayOf(v)));
            case DAY -> field(x, v -> call("dayOfMonth", v));
            case ORDINAL_DAY -> field(x, v -> call("dayOfYear", v));
            case WEEK_DAY -> field(x, v -> call("dayOfWeekNumber", v));
            case DAY_OF_QUARTER -> field(x, v -> integer(AppliedFunction.infixRun("plus", List.of(call("dateDiff",
                    call("firstDayOfQuarter", v), v, days()), new CInteger(1L)))));
            case HOUR, MINUTE, SECOND -> {
                if (!dateTime && !time) {
                    throw CypherErrors.invalid("No such field: " + name + " (a Date has no time)", pos);
                }
                String fn = field == TemporalField.HOUR ? "hour" : field == TemporalField.MINUTE ? "minute"
                        : "second";
                yield field(x, v -> call(fn, v));
            }
            case MILLISECOND, MICROSECOND, NANOSECOND -> {
                if (!dateTime && !time) {
                    throw CypherErrors.invalid("No such field: " + name + " (a Date has no time)", pos);
                }
                // the whole fraction of the second in that unit (Neo4j's millisecond of 12:31:14.645876 is 645,
                // its microsecond 645876): the database's distance from the second's start; nanoseconds are the
                // microseconds a thousandfold (the database keeps microseconds)
                boolean fine = nanos(x);
                yield field(x, v -> {
                    if (fine) {
                        // the nanoseconds since the second's start, the database's; the coarser fields their quotients
                        ValueSpecification ns = call("dateDiff", call("firstMillisecondOfSecond", v), v,
                                unit("NANOSECONDS"));
                        return field == TemporalField.NANOSECOND ? ns : SqlToPure.integerQuotient(ns,
                                new CInteger(field == TemporalField.MILLISECOND ? 1_000_000L : 1000L));
                    }
                    ValueSpecification micros = call("dateDiff", call("firstMillisecondOfSecond", v), v,
                            unit(field == TemporalField.MILLISECOND ? "MILLISECONDS" : "MICROSECONDS"));
                    return field == TemporalField.NANOSECOND ? integer(AppliedFunction.infixRun("times",
                            List.of(micros, new CInteger(1000L)))) : micros;
                });
            }
            case TIMEZONE, OFFSET, OFFSET_MINUTES, OFFSET_SECONDS, EPOCH_SECONDS, EPOCH_MILLIS ->
                    throw new IllegalStateException("unreachable: a zone field reads above");
        };
    }

    /** A zoned date-time's zone fields: the zone's name or offset as Neo4j prints them (a spelled zone's are
     *  spelled; a named zone's offset is the database's, the instant against its wall time), the epoch distances
     *  the database's. */
    private static Tx zoneField(Tx t, TemporalField field, Zone z, Pos pos) {
        return switch (field) {
            case TIMEZONE -> SqlToPure.strict(Kind.STRING, List.of(t), x -> new CString(z.named()
                    ? java.util.Objects.requireNonNull(z.name()) : z.offsetText()));
            case OFFSET -> {
                if (z.named()) {
                    throw CypherErrors.unsupported("the offset of a named zone as a text is not supported yet",
                            pos);
                }
                yield SqlToPure.strict(Kind.STRING, List.of(t), x -> new CString(z.offsetText()));
            }
            case OFFSET_MINUTES, OFFSET_SECONDS -> {
                String u = field == TemporalField.OFFSET_MINUTES ? "MINUTES" : "SECONDS";
                yield field(t, v -> call("dateDiff", v, z.named() ? call(LOCAL_IN, v, new CString(
                        java.util.Objects.requireNonNull(z.name()))) : shifted(v, z, 1, false), unit(u)));
            }
            case EPOCH_SECONDS, EPOCH_MILLIS -> field(t, v -> call("dateDiff", epochInstant(), v,
                    unit(field == TemporalField.EPOCH_SECONDS ? "SECONDS" : "MILLISECONDS")));
            default -> throw new IllegalStateException("not a zone field: " + field);
        };
    }

    /** A field's name as Neo4j's messages spell it ({@code Year}). */
    private static String capitalized(String name) {
        return name.isEmpty() ? name : Character.toUpperCase(name.charAt(0)) + name.substring(1);
    }

    private static Tx field(Tx t, java.util.function.Function<ValueSpecification, ValueSpecification> read) {
        return SqlToPure.strict(Kind.INTEGER, List.of(t), x -> read.apply(x.get(0)));
    }

    /** The Thursday of a date's ISO week (whose year is the week-year). */
    private static ValueSpecification thursdayOf(ValueSpecification d) {
        return call("adjust", d, integer(AppliedFunction.infixRun("minus", List.of(new CInteger(4L),
                call("dayOfWeekNumber", d)))), days());
    }

    /** {@code date.truncate(unit, d[, fields])}, {@code localdatetime.truncate(...)}, {@code localtime.truncate(...)},
     *  {@code datetime.truncate(...)}: the value at the start of its unit, the fields written over it; the unit must
     *  be spelled. A time of day truncates as the date-time at it on the epoch day, and its time is taken back; a
     *  zoned date-time truncates by its wall time, and the result is in the zone written over it, else the source's
     *  (UTC for a date or local date-time). */
    private Val truncate(CypherAst.Call c, TemporalFamily family, Scope scope) {
        boolean withTime = family != TemporalFamily.DATE;
        boolean toTime = family == TemporalFamily.LOCALTIME || family == TemporalFamily.TIME;
        boolean toZoned = family == TemporalFamily.DATETIME;
        boolean toZonedTime = family == TemporalFamily.TIME;
        if (c.args().size() < 2 || c.args().size() > 3) {
            throw CypherErrors.invalid((c.args().size() < 2 ? "Insufficient" : "Too many") + " parameters for"
                    + " function '" + c.name() + "'", c.pos());
        }
        Val unitVal = val(c.args().get(0), scope);
        if (!(unitVal instanceof Scalar us) || !us.literal() || !(us.constant() instanceof String unitText)) {
            throw CypherErrors.unsupported("a truncation unit that is not a spelled text is not supported yet",
                    c.pos());
        }
        TruncateUnit unit = TRUNCATE_UNITS.get(unitText.toLowerCase(Locale.ROOT));
        if (unit == null) {
            throw CypherErrors.invalid("Unsupported unit: " + unitText, c.pos());
        }
        if (!withTime && unit.time()) {
            throw CypherErrors.invalid("Unit too small for truncation: " + chronoName(unit), c.pos());
        }
        if (toTime && !unit.time() && unit != TruncateUnit.DAY) {
            throw CypherErrors.invalid("Unit is too large to be used for truncation", c.pos());
        }
        Val source = val(c.args().get(1), scope);
        if (isNull(source)) {
            // Neo4j's: a null written in place of the value is not a signature it has
            throw CypherErrors.invalid("Invalid call signature for Truncate: Provided input was [String(\"" + unitText
                    + "\"), NO_VALUE, NO_VALUE]", c.pos());
        }
        if (toTime) {
            if (!(source instanceof Scalar ss) || ss.tx().kind() != Kind.TIME && ss.tx().kind() != Kind.DATETIME
                    && ss.tx().kind() != Kind.ZONED && ss.tx().kind() != Kind.ZONED_TIME) {
                throw CypherErrors.invalid("Type mismatch: expected Time, LocalTime, DateTime or LocalDateTime but"
                        + " was " + (source instanceof Scalar s ? cypherType(s.tx().kind()) : "a list or map"),
                        c.args().get(1).pos());
            }
        } else if (!(source instanceof Scalar ss) || ss.tx().kind() != Kind.DATE && ss.tx().kind() != Kind.DATETIME
                && ss.tx().kind() != Kind.ZONED) {
            throw CypherErrors.invalid("Type mismatch: expected Date, LocalDateTime or DateTime but was " + (source
                    instanceof Scalar s ? cypherType(s.tx().kind()) : "a list or map"), c.args().get(1).pos());
        }
        Tx t = ((Scalar) source).tx();
        if (unit.time() && t.kind() == Kind.DATE) {
            throw CypherErrors.invalid("Unit too small for truncation: " + chronoName(unit), c.args().get(1).pos());
        }
        Zone sourceZone = Zone.UTC;
        if (t.kind() == Kind.TIME) {
            t = atEpoch(t);
        } else if (t.kind() == Kind.ZONED) {
            sourceZone = zoneKnown(t, c.args().get(1).pos());
            t = localOf(t, sourceZone);
        } else if (t.kind() == Kind.ZONED_TIME) {
            sourceZone = zoneKnown(t, c.args().get(1).pos());
            t = wallAtEpoch(t, sourceZone);
        }
        Zone written = null;
        Map<Component, Tx> over = new LinkedHashMap<>();
        if (c.args().size() == 3) {
            Val fields = val(c.args().get(2), scope);
            if (!(fields instanceof MapVal fm)) {
                throw CypherErrors.invalid("Type mismatch: expected Map but was a value", c.args().get(2).pos());
            }
            for (Map.Entry<String, Val> e : fm.entries().entrySet()) {
                Component k = COMPONENTS.get(e.getKey().toLowerCase(Locale.ROOT));
                if (k == null) {
                    throw CypherErrors.invalid("No such field: " + e.getKey(), c.pos());
                }
                if (k == Component.TIMEZONE) {
                    if (!toZoned && !toZonedTime) {
                        throw CypherErrors.invalid("Not supported: timezone", c.pos());
                    }
                    if (!(e.getValue() instanceof Scalar s) || !s.literal() || !(s.constant() instanceof String zt)) {
                        throw CypherErrors.unsupported("a time zone that is not a spelled text is not supported"
                                + " yet", c.pos());
                    }
                    written = toZonedTime ? timeZone(zt, false, c.pos()) : zone(zt, false, c.pos());
                    continue;
                }
                if (toTime && !k.time()) {
                    throw CypherErrors.unsupported("the field " + e.getKey() + " over a time truncation is not"
                            + " supported yet", c.pos());
                }
                boolean positional = k == Component.DAY_OF_WEEK && unit == TruncateUnit.WEEK
                        || k == Component.DAY_OF_QUARTER && unit == TruncateUnit.QUARTER
                        || k == Component.ORDINAL_DAY && unit == TruncateUnit.YEAR;
                if (k != Component.YEAR && k != Component.MONTH && k != Component.DAY && !k.time() && !positional) {
                    throw CypherErrors.invalid("No such field: " + e.getKey(), c.pos());
                }
                if (!(e.getValue() instanceof Scalar s) || s.tx().kind() != Kind.INTEGER) {
                    throw CypherErrors.invalid("Type mismatch: the field " + e.getKey() + " must be an Integer",
                            c.pos());
                }
                if (!withTime && k.time()) {
                    throw CypherErrors.invalid("Cannot assign a time component to a Date", c.pos());
                }
                over.put(k, s.tx());
            }
        }
        List<Tx> inputs = new ArrayList<>();
        inputs.add(t);
        inputs.addAll(over.values());
        Set<Component> keys = over.keySet();
        Kind result = toTime ? Kind.TIME : withTime ? Kind.DATETIME : Kind.DATE;
        boolean sourceNanos = nanos(t);
        boolean nanosField = nanosComponent(over.get(Component.NANOSECOND), c.pos());
        Tx truncated = withNanos(SqlToPure.strict(result, inputs, v -> {
            ValueSpecification x = v.get(0);
            Map<Component, ValueSpecification> o = new java.util.EnumMap<>(Component.class);
            int i = 1;
            for (Component k : keys) {
                o.put(k, v.get(i++));
            }
            ValueSpecification start = truncated(unit, x, sourceNanos);
            // a day of the week, quarter or year written over the unit's start: that many days on
            for (Component positional : List.of(Component.DAY_OF_WEEK, Component.DAY_OF_QUARTER,
                    Component.ORDINAL_DAY)) {
                if (o.containsKey(positional)) {
                    start = day(call("adjust", start, integer(AppliedFunction.infixRun("minus", List.of(
                            o.get(positional), new CInteger(1L)))), days()));
                }
            }
            if (o.isEmpty() && !withTime) {
                return call("datePart", start);
            }
            ValueSpecification dt;
            if (unit == TruncateUnit.MILLISECOND || unit == TruncateUnit.MICROSECOND) {
                // the start keeps its fraction: no recomposition
                dt = start;
            } else {
                ValueSpecification y = integer(o.getOrDefault(Component.YEAR, call("year", start)));
                ValueSpecification mo = integer(o.getOrDefault(Component.MONTH, call("monthNumber", start)));
                ValueSpecification d = integer(o.getOrDefault(Component.DAY, call("dayOfMonth", start)));
                if (!withTime) {
                    return call("date", y, mo, d);
                }
                ValueSpecification zero = new CInteger(0L);
                ValueSpecification h = integer(o.getOrDefault(Component.HOUR, unit.time() ? call("hour", start)
                        : zero));
                ValueSpecification mi = integer(o.getOrDefault(Component.MINUTE, unit == TruncateUnit.MINUTE
                        || unit == TruncateUnit.SECOND ? call("minute", start) : zero));
                ValueSpecification s = integer(o.getOrDefault(Component.SECOND, unit == TruncateUnit.SECOND
                        ? call("second", start) : zero));
                dt = call("date", y, mo, d, h, mi, s);
            }
            // the sub-second fields written over the start (a nanosecond one makes the result of nanosecond
            // precision: the date-time at it on the epoch day for a time)
            dt = withSubSecond(dt, o, nanosField);
            return toTime ? nanosField ? timeOfNanos(dt) : call(TIME_OF, dt) : dt;
        }), nanosField);
        if (toZonedTime) {
            // the zone written over a truncation replaces the source's (Neo4j's: no conversion)
            return scalar(zonedTimeOf(truncated, written != null ? written : sourceZone));
        }
        return scalar(toZoned ? instantOf(truncated, written != null ? written : sourceZone) : truncated);
    }

    /** The start of a value's unit, as the database computes it. */
    private static ValueSpecification truncated(TruncateUnit unit, ValueSpecification x, boolean nanos) {
        ValueSpecification one = new CInteger(1L);
        return switch (unit) {
            case MILLENNIUM, CENTURY, DECADE -> {
                long n = unit == TruncateUnit.MILLENNIUM ? 1000 : unit == TruncateUnit.CENTURY ? 100 : 10;
                ValueSpecification year = call("year", x);
                yield call("date", integer(AppliedFunction.infixRun("minus", List.of(year, call("rem", year,
                        new CInteger(n))))), one, one);
            }
            case YEAR -> call("firstDayOfYear", x);
            case WEEK_YEAR -> {
                ValueSpecification jan4 = call("date", call("year", thursdayOf(x)), one, new CInteger(4L));
                yield day(call("adjust", jan4, integer(AppliedFunction.infixRun("minus", List.of(one,
                        call("dayOfWeekNumber", jan4)))), days()));
            }
            case QUARTER -> call("firstDayOfQuarter", x);
            case MONTH -> call("firstDayOfMonth", x);
            case WEEK -> call("firstDayOfWeek", x);
            case DAY -> call("datePart", x);
            case HOUR -> call("firstMinuteOfHour", x);
            case MINUTE -> call("firstSecondOfMinute", x);
            case SECOND -> call("firstMillisecondOfSecond", x);
            // the second's start plus the whole milliseconds since it; the database keeps microseconds, so the
            // microsecond's start is the value itself
            case MILLISECOND -> call("adjust", call("firstMillisecondOfSecond", x), call("dateDiff",
                    call("firstMillisecondOfSecond", x), x, unit("MILLISECONDS")), unit("MILLISECONDS"));
            // a nanosecond value's microsecond start: the second's start plus the whole microseconds since it
            case MICROSECOND -> nanos ? call("adjust", call("firstMillisecondOfSecond", x), call("dateDiff",
                    call("firstMillisecondOfSecond", x), x, unit("MICROSECONDS")), unit("MICROSECONDS")) : x;
        };
    }

    // ------------------------------------------------------------------ UNWIND

    /**
     * {@code UNWIND list AS x}: a row per element. A collection the database computes, or a list of known shape
     * holding scalars of one kind and no null, is Pure's {@code flatten}; one holding nulls is read by place (a row
     * per place, the element at it); null and the empty list give no row. After a projection, the rows in hand are
     * each paired with the list's elements (Pure's {@code lateral}).
     */
    private State unwind(CypherAst.Unwind u, @com.legend.base.Nullable State state) {
        if (state instanceof Graph || state instanceof Arms) {
            throw CypherErrors.unsupported("UNWIND after a MATCH is not supported yet", u.pos());
        }
        Rows rows = (Rows) state;
        Scope scope = new RowScope(rows == null ? oneRow() : rows);
        Val v = val(u.list(), scope);
        String x = u.variable();
        if (rows != null && rows.columns().stream().anyMatch(c -> c.name().equals(x))) {
            throw CypherErrors.invalid("Variable `" + x + "` already declared", u.pos());
        }
        ValueSpecification rel;
        Col col;
        switch (v) {
            case Scalar s when s.tx().kind() == Kind.NULL -> {
                rel = noRows(x);
                col = new Col(x, Kind.STRING, true);
            }
            case Scalar s when s.tx().kind() == Kind.JSON -> throw CypherErrors.unsupported("UNWIND over a list the"
                    + " database holds as a Variant is not supported yet", u.pos());
            case Scalar s -> throw CypherErrors.unsupported("UNWIND of a value that is not a list (one row in Neo4j)"
                    + " is not supported yet", u.pos());
            case MapVal m -> throw CypherErrors.unsupported("UNWIND of a map (one row in Neo4j) is not supported"
                    + " yet", u.pos());
            case Coll c -> {
                rel = call("flatten", c.vs(), new ColSpec(x, null, null, null));
                col = new Col(x, c.kind(), true);
            }
            case ListVal l -> {
                if (l.elements().isEmpty()) {
                    rel = noRows(x);
                    col = new Col(x, Kind.STRING, true);
                    break;
                }
                List<Tx> elements = new ArrayList<>();
                Kind kind = null;
                boolean nulls = false;
                for (Val e : l.elements()) {
                    if (!(e instanceof Scalar s)) {
                        throw CypherErrors.unsupported("UNWIND over a list of lists or maps is not supported yet",
                                u.pos());
                    }
                    Tx t = s.tx();
                    if (t.kind() == Kind.NULL) {
                        nulls = true;
                    } else {
                        nulls |= t.nullable();
                        if (kind == null) {
                            kind = t.kind();
                        } else if (kind != t.kind()) {
                            throw CypherErrors.unsupported("UNWIND over a list holding values of different types is"
                                    + " not supported yet", u.pos());
                        }
                    }
                    elements.add(t);
                }
                // a list holding a temporal value of nanosecond precision: every element at that precision (a
                // time of day as the date-time at it on the epoch day), the column of it
                boolean fine = elements.stream().anyMatch(CypherToPure::nanos);
                Kind carrier = kind;
                if (fine && kind == Kind.TIME) {
                    List<Tx> moved = new ArrayList<>();
                    for (Tx t : elements) {
                        moved.add(t.kind() == Kind.NULL ? t : atEpoch(t));
                    }
                    elements = moved;
                    carrier = Kind.DATETIME;
                }
                com.legend.protocol.sql.InputDialect.TypeDetail detail = fine ? new Carried(null, true) : null;
                if (kind == null) {
                    // every element null
                    rel = places(elements.size(), x, Kind.STRING, elements);
                    col = new Col(x, Kind.STRING, true);
                } else if (!nulls && elements.size() > 1) {
                    // (a list of one element is read by place: Pure reads [x] as x, which no database unnests)
                    List<ValueSpecification> values = new ArrayList<>();
                    for (Tx t : elements) {
                        values.add(SqlToPure.valueOf(t));
                    }
                    rel = call("flatten", new PureCollection(values), new ColSpec(x, null, null, null));
                    col = new Col(x, kind, true, null, detail);
                } else {
                    rel = places(elements.size(), x, java.util.Objects.requireNonNull(carrier), elements);
                    col = new Col(x, kind, true, null, detail);
                }
            }
        }
        // (the typer types a flattened column [0..1], whatever the collection's elements: the column is read as one
        // that may be null)
        if (rows == null) {
            return new Rows(rel, List.of(col));
        }
        List<Col> columns = new ArrayList<>(rows.columns());
        columns.add(col);
        return new Rows(call("lateral", rows.vs(), lambda("r", rel)), columns);
    }

    /** No row, under a column of strings. */
    private static ValueSpecification noRows(String column) {
        return call("flatten", call("cast", SqlToPure.empty(), SqlToPure.typeArg("String")),
                new ColSpec(column, null, null, null));
    }

    /** A row per place of a list, the element at that place as the column (null for a null element): the places
     *  are a range the database flattens, the element picked by place. */
    private static ValueSpecification places(int n, String column, Kind kind, List<Tx> elements) {
        String place = SYNTHETIC + "place";
        ValueSpecification placesRel = call("flatten", call("range", new CInteger(1L), new CInteger((long) n + 1)),
                new ColSpec(place, null, null, null));
        ValueSpecification pick = emptyOf(kind);
        for (int k = n - 1; k >= 0; k--) {
            Tx t = elements.get(k);
            if (t.kind() == Kind.NULL) {
                // a null element is the else (a CASE of two bare NULLs is text to Postgres)
                continue;
            }
            pick = call("if", call("equal", new AppliedProperty(new Variable("p"), place), new CInteger(k + 1L)),
                    thunk(SqlToPure.valueOf(t)), thunk(pick));
        }
        return call("select", call("extend", placesRel, new ColSpecArray(List.of(new ColSpec(column, lambda("p",
                pick), null, null)))), new ColSpecArray(List.of(new ColSpec(column, null, null, null))));
    }

    /** No value, of a kind's Pure type. */
    private static ValueSpecification emptyOf(Kind kind) {
        String type = switch (kind) {
            case INTEGER, WHOLE -> "Integer";
            case FLOAT, INEXACT -> "Float";
            case DECIMAL, NUMBER -> "Decimal";
            case STRING, NULL -> "String";
            case BOOLEAN -> "Boolean";
            case DATE -> "StrictDate";
            case DATETIME, ZONED, ZONED_TIME -> "DateTime";
            case TIME -> "StrictTime";
            case JSON -> "meta::pure::metamodel::variant::Variant";
            case DURATION, OTHER -> throw new IllegalStateException("no type for " + kind);
        };
        return call("cast", SqlToPure.empty(), SqlToPure.typeArg(type));
    }

    // ------------------------------------------------------------------ the text of an expression

    /** An expression's text, as Neo4j names an unaliased item: the parser keeps the source's text for an item, and
     *  this spells an ORDER BY expression the same way to match it. */
    private static String text(Expr e) {
        return switch (e) {
            case CypherAst.IntLit n -> n.text();
            case CypherAst.FloatLit f -> f.text();
            case CypherAst.StringLit s -> "'" + s.value() + "'";
            case CypherAst.BoolLit b -> String.valueOf(b.value());
            case CypherAst.NullLit n -> "null";
            case CypherAst.Var v -> v.name();
            case CypherAst.Prop p -> text(p.target()) + "." + p.name();
            case CypherAst.Param p -> "$" + p.name();
            case CypherAst.ListLit l -> "[" + String.join(", ", l.elements().stream().map(CypherToPure::text).toList())
                    + "]";
            case CypherAst.MapLit m -> "{" + String.join(", ", m.entries().entrySet().stream().map(x -> x.getKey()
                    + ": " + text(x.getValue())).toList()) + "}";
            case CypherAst.Unary u -> switch (u.op()) {
                case NEG -> "-" + text(u.operand());
                case POS -> "+" + text(u.operand());
                case NOT -> "NOT " + text(u.operand());
                case IS_NULL -> text(u.operand()) + " IS NULL";
                case IS_NOT_NULL -> text(u.operand()) + " IS NOT NULL";
            };
            case CypherAst.Binary b -> text(b.left()) + " " + symbol(b.op()) + " " + text(b.right());
            case CypherAst.Call c -> c.name() + "(" + (c.distinct() ? "DISTINCT " : "") + String.join(", ",
                    c.args().stream().map(CypherToPure::text).toList()) + ")";
            case CypherAst.CountStar c -> "count(*)";
            case CypherAst.Case c -> "CASE";
            case CypherAst.Paren p -> "(" + text(p.inner()) + ")";
            case CypherAst.Quantifier q -> q.name() + "(" + q.variable() + " IN " + text(q.list()) + " WHERE "
                    + text(q.where()) + ")";
            case CypherAst.ListComprehension lc -> "[" + lc.variable() + " IN " + text(lc.list())
                    + (lc.where() == null ? "" : " WHERE " + text(lc.where()))
                    + (lc.map() == null ? "" : " | " + text(lc.map())) + "]";
            case CypherAst.Index ix -> text(ix.target()) + "[" + text(ix.index()) + "]";
            case CypherAst.Slice sl -> text(sl.target()) + "[" + (sl.from() == null ? "" : text(sl.from())) + ".."
                    + (sl.to() == null ? "" : text(sl.to())) + "]";
        };
    }

    private static String symbol(BinaryOp op) {
        return switch (op) {
            case OR -> "OR";
            case XOR -> "XOR";
            case AND -> "AND";
            case EQ -> "=";
            case NE -> "<>";
            case LT -> "<";
            case LE -> "<=";
            case GT -> ">";
            case GE -> ">=";
            case PLUS -> "+";
            case MINUS -> "-";
            case TIMES -> "*";
            case DIVIDE -> "/";
            case MODULO -> "%";
            case POWER -> "^";
            case STARTS_WITH -> "STARTS WITH";
            case ENDS_WITH -> "ENDS WITH";
            case CONTAINS -> "CONTAINS";
            case IN -> "IN";
            case REGEX -> "=~";
        };
    }

    // ------------------------------------------------------------------ building Pure

    private static AppliedFunction call(String fn, ValueSpecification... args) {
        return SqlToPure.call(fn, args);
    }

    private static LambdaFunction lambda(String parameter, ValueSpecification body) {
        return new LambdaFunction(List.of(new Variable(parameter)), List.of(body));
    }

    private static LambdaFunction thunk(ValueSpecification body) {
        return SqlToPure.thunk(body);
    }
}
