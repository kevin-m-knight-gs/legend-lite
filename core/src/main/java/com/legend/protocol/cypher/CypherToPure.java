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
    private sealed interface State permits Graph, Rows {
    }

    /** A pattern's bindings over its root class: each variable's navigation from the root instance, and the
     *  conditions the rows must satisfy. {@code row} is the lambda parameter that names the root instance. */
    private record Graph(String rootClass, String row, Map<String, Binding> bindings,
            List<ValueSpecification> conditions) implements State {
    }

    /** A pattern variable: a node (its class, the properties navigated from the root to reach it, whether that
     *  navigation may find nothing) or a relationship (a hop, with no value of its own in PR1). */
    private record Binding(boolean node, @com.legend.base.Nullable String classPath, List<String> path,
            boolean optional) {
    }

    /** A relation: its Pure expression and its columns, in order. A column may be CARRIED: a list or map of known
     *  shape a WITH named, every leaf of it a literal, kept as its shape for the clauses after the WITH to read rather
     *  than computed by the database; it is no column of the relation. */
    private record Rows(ValueSpecification vs, List<Col> columns) implements State {
    }

    private record Col(String name, Kind kind, boolean nullable, @com.legend.base.Nullable Val carried) {
        Col(String name, Kind kind, boolean nullable) {
            this(name, kind, nullable, null);
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
            throw CypherErrors.unsupported("OPTIONAL MATCH is not supported yet", m.pos());
        }
        if (state instanceof Rows) {
            throw CypherErrors.unsupported("a MATCH after a WITH that projected its variables is not supported yet",
                    m.pos());
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
            graph.conditions().add(condition(where, new GraphScope(graph), "WHERE"));
        }
        return graph;
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
            Graph g = new Graph(rootClass, row, new LinkedHashMap<>(), new ArrayList<>());
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
        throw CypherErrors.unsupported("a pattern that fans out (no node from which every relationship navigates to"
                + " at most one node) is not supported yet", p.pos());
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
            Hop hop = hop(java.util.Objects.requireNonNull(cls), rel, next, false);
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
            Hop hop = java.util.Objects.requireNonNull(hop(cls, rel, next, true));
            String nextVar = next.variable();
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
            Binding target = new Binding(true, hop.target(), List.copyOf(path), optional);
            if (hop.optional()) {
                // the hop must find a node: a row where it finds none is not a match
                g.conditions().add(call("isNotEmpty", navigate(g.row(), path)));
            }
            bind(g, rel.variable(), new Binding(false, null, List.copyOf(path), optional), rel.pos());
            bind(g, nextVar, target, next.pos());
            nodeConstraints(next, target, g);
            current = target;
            at += 2;
        }
    }

    /** A hop: the property navigated from the node before it, the class it reaches, and whether it may find nothing. */
    private record Hop(String property, String target, boolean optional) {
    }

    /**
     * The to-one navigation a relationship pattern between a node of class {@code from} and {@code to} spells, or
     * null when there is none: the property named by the relationship's type on {@code from} (an outgoing hop, or an
     * undirected one), or the inverse end of the property named on {@code to}'s class (an incoming hop: the node
     * before is what the property holds). When {@code refuse}, a hop that exists but fans out is refused by name
     * instead of null.
     */
    private @com.legend.base.Nullable Hop hop(String from, RelPattern rel, NodePattern to, boolean refuse) {
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
                if (refuse) {
                    throw CypherErrors.unsupported("the relationship " + type + " holds many " + target
                            + " per " + from + ": a pattern that fans out is not supported yet",
                            rel.pos());
                }
                return null;
            }
            return new Hop(type, target, forward.optional());
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
            if (refuse) {
                throw CypherErrors.unsupported("the relationship " + type + " reaches many " + toCls
                        + " per " + from + " (its inverse " + inverse.name() + " holds many): a pattern"
                        + " that fans out is not supported yet", rel.pos());
            }
            return null;
        }
        return new Hop(inverse.name(), toCls, inverse.optional());
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
            Graph next = new Graph(g.rootClass(), g.row(), kept, g.conditions());
            Expr where = w.where();
            if (where != null) {
                next.conditions().add(condition(where, new GraphScope(next), "WHERE"));
            }
            return next;
        }
        Rows rows = project(w.items(), w.distinct(), w.orderBy(), w.skip(), w.limit(), state, w.pos(), false);
        Expr where = w.where();
        if (where != null) {
            RowScope scope = new RowScope(rows);
            ValueSpecification filtered = call("filter", rows.vs(), lambda(scope.row(), condition(where, scope,
                    "WHERE")));
            return new Rows(filtered, rows.columns());
        }
        return rows;
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
        return project(r.items(), r.distinct(), r.orderBy(), r.skip(), r.limit(), state == null ? oneRow() : state,
                r.pos(), true);
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
            @com.legend.base.Nullable Expr skip, @com.legend.base.Nullable Expr limit, State state, Pos pos,
            boolean returning) {
        Scope scope = state instanceof Graph g ? new GraphScope(g) : new RowScope((Rows) state);
        List<Output> outputs = new ArrayList<>();
        Set<String> names = new HashSet<>();
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
                Tx t = java.util.Objects.requireNonNull(scalars.get(out.name()));
                specs.add(new ColSpec(out.name(), lambda(scope.row(), SqlToPure.valueOf(t)), null, null));
                columns.add(new Col(out.name(), t.kind(), t.nullable()));
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
                    columns.add(new Col(out.name(), t.kind(), t.nullable()));
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
            ValueSpecification input = state instanceof Graph g ? call("project", source(g), new ColSpecArray(specs))
                    : reproject((Rows) state, specs);
            vs = keys.isEmpty() ? call("aggregate", input, new ColSpecArray(aggregates))
                    : call("groupBy", input, new ColSpecArray(keys), new ColSpecArray(aggregates));
            if (!finishes.isEmpty()) {
                vs = call("extend", vs, new ColSpecArray(finishes));
            }
            // the result's columns in the items' order (the grouping puts the keys first), the finished sums in
            // place of their raw ones
            vs = call("select", vs, new ColSpecArray(columns.stream().map(c -> new ColSpec(c.name(), null, null,
                    null)).toList()));
        }
        if (distinct) {
            vs = call("distinct", vs);
        }
        if (!afterGrouping.isEmpty()) {
            // the sort keys over the projected columns (an alias shadows nothing here: Neo4j reads only the items)
            RowScope after = new RowScope(new Rows(vs, List.copyOf(columns)));
            List<ColSpec> computed = new ArrayList<>();
            int k = 0;
            for (SortKey sk : sortKeys) {
                if (!sk.hidden()) {
                    continue;
                }
                SortItem o = afterGrouping.get(k++);
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
            vs = call("select", vs, new ColSpecArray(columns.stream().filter(c -> c.carried() == null)
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
        rows.columns().stream().filter(c -> c.carried() == null).forEach(c -> existing.add(c.name()));
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
            throw CypherErrors.unsupported("a " + (b.node() ? "node" : "relationship") + " as a value (" + v.name()
                    + ") is not supported yet", v.pos());
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

    private final class RowScope implements Scope {
        private final Rows rows;

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
                    if (c.kind() == Kind.NULL) {
                        // a null the query named: null wherever it is read
                        return nullValue();
                    }
                    return scalar(columnLike(new AppliedProperty(new Variable(row()), c.name()), c.kind(),
                            c.nullable()));
                }
            }
            throw CypherErrors.invalid("Variable `" + v.name() + "` not defined", v.pos());
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
            case DATETIME -> "DateTime";
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
        return SqlToPure.strictTest(List.of(l, r), v -> call("equal", v.get(0), v.get(1)));
    }

    /** {@code l < r} and the others: null when either is null, or when the values are of different types. */
    private static Tx ordered(BinaryOp op, Tx l, Tx r, Pos pos) {
        if (l.kind() == Kind.NULL || r.kind() == Kind.NULL) {
            return SqlToPure.nullOf(Kind.BOOLEAN);
        }
        if (l.kind() == Kind.BOOLEAN || r.kind() == Kind.BOOLEAN) {
            throw CypherErrors.unsupported("ordering booleans (" + op.name() + ") is not supported yet", pos);
        }
        if (!comparable(l.kind(), r.kind())) {
            throw CypherErrors.unsupported("comparing a " + cypherType(l.kind()) + " with a " + cypherType(r.kind())
                    + " (null in Neo4j) is not supported yet", pos);
        }
        SqlAst.BinaryOp sqlOp = switch (op) {
            case LT -> SqlAst.BinaryOp.LT;
            case LE -> SqlAst.BinaryOp.LE;
            case GT -> SqlAst.BinaryOp.GT;
            default -> SqlAst.BinaryOp.GE;
        };
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
        if (t.kind() == Kind.DATE || t.kind() == Kind.DATETIME) {
            throw CypherErrors.unsupported("temporal arithmetic is not supported yet", e.pos());
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
            case INTEGER, WHOLE, FLOAT, DECIMAL, NUMBER, INEXACT, BOOLEAN -> scalar(SqlToPure.strict(Kind.STRING,
                    List.of(t), x -> call("toString", x.get(0))));
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

    // ------------------------------------------------------------------ UNWIND

    /**
     * {@code UNWIND list AS x}: a row per element. A collection the database computes, or a list of known shape
     * holding scalars of one kind and no null, is Pure's {@code flatten}; one holding nulls is read by place (a row
     * per place, the element at it); null and the empty list give no row. After a projection, the rows in hand are
     * each paired with the list's elements (Pure's {@code lateral}).
     */
    private State unwind(CypherAst.Unwind u, @com.legend.base.Nullable State state) {
        if (state instanceof Graph) {
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
                    col = new Col(x, kind, true);
                } else {
                    rel = places(elements.size(), x, kind, elements);
                    col = new Col(x, kind, true);
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
            case DATETIME -> "DateTime";
            case JSON -> "meta::pure::metamodel::variant::Variant";
            case OTHER -> throw new IllegalStateException("no type for " + kind);
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
