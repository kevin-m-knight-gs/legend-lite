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

    private final Model model;
    private int fresh;

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

    /** A relation: its Pure expression and its columns, in order. */
    private record Rows(ValueSpecification vs, List<Col> columns) implements State {
    }

    private record Col(String name, Kind kind, boolean nullable) {
    }

    private ValueSpecification statement(CypherAst.Statement s) {
        State state = null;
        List<Clause> clauses = s.clauses();
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
            if (toClass != null && !toClass.equals(target)) {
                throw CypherErrors.unsupported("the label " + toClass + " is not the type of " + type
                        + " (" + target + "): a label narrowing a property's type is not supported yet",
                        to.pos());
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
            throw CypherErrors.unsupported("a query starting with WITH is not supported yet", w.pos());
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
        Rows rows = project(w.items(), w.distinct(), w.orderBy(), w.skip(), w.limit(), state, w.pos());
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
        if (state == null) {
            // RETURN 1 + 1: a query over no pattern is one row, of no column the query can name
            String one = "\n" + SYNTHETIC + "row\n1\n";
            Rows row = new Rows(com.legend.protocol.spec.TdsLiteral.of(one, "#TDS" + one + "#", null),
                    List.of(new Col(SYNTHETIC + "row", Kind.INTEGER, false)));
            return project(r.items(), r.distinct(), r.orderBy(), r.skip(), r.limit(), row, r.pos());
        }
        return project(r.items(), r.distinct(), r.orderBy(), r.skip(), r.limit(), state, r.pos());
    }

    /** A projected item: its name in the result, and its translation over the scope before the projection (a
     *  non-aggregating item), or its aggregate. */
    private record Output(String name, @com.legend.base.Nullable Tx value, @com.legend.base.Nullable Aggregate agg) {
    }

    /** An aggregate: the column its argument is computed into before the grouping, the reduce over the group's
     *  values, what its result is, and a finish computed after the grouping (a sum of nothing is 0). */
    private record Aggregate(String argColumn, Tx arg, LambdaFunction reduce, Kind kind, boolean nullable,
            boolean finishWithZero) {
    }

    /**
     * A WITH's or RETURN's projection: the items computed over the state in hand (a graph's root instance, or a
     * relation's row), grouped by the non-aggregating items when an aggregate is among them (Cypher's implicit
     * grouping), then distinct, sorted, sliced.
     */
    private Rows project(List<Item> items, boolean distinct, List<SortItem> orderBy,
            @com.legend.base.Nullable Expr skip, @com.legend.base.Nullable Expr limit, State state, Pos pos) {
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
                outputs.add(new Output(name, tx(e, scope), null));
            }
        }
        // the sort keys: an item (by its alias or its text), else an expression over the scope before the projection
        List<SortKey> sortKeys = new ArrayList<>();
        List<ColSpec> hidden = new ArrayList<>();
        for (SortItem o : orderBy) {
            String key = outputNamed(items, o.expr(), outputs);
            if (key == null) {
                if (grouped || distinct) {
                    throw CypherErrors.invalid("In a WITH/RETURN with DISTINCT or an aggregation, it is not possible"
                            + " to access variables declared before the WITH/RETURN", o.pos());
                }
                Tx t = tx(o.expr(), scope);
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
                Tx t = java.util.Objects.requireNonNull(out.value());
                specs.add(new ColSpec(out.name(), lambda(scope.row(), SqlToPure.valueOf(t)), null, null));
                columns.add(new Col(out.name(), t.kind() == Kind.NULL ? Kind.STRING : t.kind(), t.nullable()));
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
                    Tx t = java.util.Objects.requireNonNull(out.value());
                    specs.add(new ColSpec(out.name(), lambda(scope.row(), SqlToPure.valueOf(t)), null, null));
                    keys.add(new ColSpec(out.name(), null, null, null));
                    columns.add(new Col(out.name(), t.kind() == Kind.NULL ? Kind.STRING : t.kind(), t.nullable()));
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
            vs = call("select", vs, new ColSpecArray(columns.stream().map(c -> new ColSpec(c.name(), null, null,
                    null)).toList()));
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
        rows.columns().forEach(c -> existing.add(c.name()));
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

    /** Where a variable or a property is found: over a graph's root instance, or over a relation's row. */
    private interface Scope {
        /** The lambda parameter naming the row the scope reads. */
        String row();

        /** The value a variable is; a node or relationship variable has no value in PR1. */
        Tx variable(CypherAst.Var v);

        /** The value a property access is: a node's property. */
        Tx property(CypherAst.Prop p);

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
        public Tx variable(CypherAst.Var v) {
            Binding b = g.bindings().get(v.name());
            if (b == null) {
                throw CypherErrors.invalid("Variable `" + v.name() + "` not defined", v.pos());
            }
            throw CypherErrors.unsupported("a " + (b.node() ? "node" : "relationship") + " as a value (" + v.name()
                    + ") is not supported yet", v.pos());
        }

        @Override
        public Tx property(CypherAst.Prop p) {
            if (!(p.target() instanceof CypherAst.Var v)) {
                throw CypherErrors.unsupported("a property of an expression (a map's) is not supported yet", p.pos());
            }
            Binding b = g.bindings().get(v.name());
            if (b == null) {
                throw CypherErrors.invalid("Variable `" + v.name() + "` not defined", v.pos());
            }
            return propertyOf(b, p.name(), p.pos());
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
            Property prop = property(cls, name);
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
            return kind == Kind.BOOLEAN ? SqlToPure.booleanValue(v, nullable) : Tx.value(v, kind, nullable);
        }
    }

    private static final class RowScope implements Scope {
        private final Rows rows;

        RowScope(Rows rows) {
            this.rows = rows;
        }

        @Override
        public String row() {
            return "r";
        }

        @Override
        public Tx variable(CypherAst.Var v) {
            for (Col c : rows.columns()) {
                if (c.name().equals(v.name())) {
                    ValueSpecification value = new AppliedProperty(new Variable(row()), c.name());
                    return c.kind() == Kind.BOOLEAN ? SqlToPure.booleanValue(value, c.nullable())
                            : Tx.value(value, c.kind(), c.nullable());
                }
            }
            throw CypherErrors.invalid("Variable `" + v.name() + "` not defined", v.pos());
        }

        @Override
        public Tx property(CypherAst.Prop p) {
            throw CypherErrors.unsupported("a property of a projected value (a node or map carried through WITH) is"
                    + " not supported yet", p.pos());
        }

        @Override
        public boolean isPatternVariable(String name) {
            return false;
        }
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

    private Tx tx(Expr e, Scope scope) {
        return switch (e) {
            case CypherAst.IntLit n -> Tx.value(new CInteger(n.value()), Kind.INTEGER, false);
            case CypherAst.FloatLit f -> Tx.value(new CFloat(f.value()), Kind.FLOAT, false);
            case CypherAst.StringLit s -> Tx.value(new CString(s.value()), Kind.STRING, false);
            case CypherAst.BoolLit b -> Tx.condition(new CBoolean(b.value()), new CBoolean(!b.value()), false);
            case CypherAst.NullLit n -> Tx.value(SqlToPure.empty(), Kind.NULL, true);
            case CypherAst.Var v -> scope.variable(v);
            case CypherAst.Prop p -> scope.property(p);
            case CypherAst.Param p -> throw CypherErrors.unsupported("a parameter ($" + p.name() + ") is not"
                    + " supported yet", p.pos());
            case CypherAst.ListLit l -> throw CypherErrors.unsupported("a list as a value is not supported yet",
                    l.pos());
            case CypherAst.MapLit m -> throw CypherErrors.unsupported("a map as a value is not supported yet",
                    m.pos());
            case CypherAst.Paren p -> tx(p.inner(), scope);
            case CypherAst.Unary u -> unary(u, scope);
            case CypherAst.Binary b -> binary(b, scope);
            case CypherAst.Call c -> call(c, scope);
            case CypherAst.CountStar c -> throw CypherErrors.invalid("Invalid use of aggregating function count(*)"
                    + " in this context", c.pos());
            case CypherAst.Case c -> caseExpr(c, scope);
        };
    }

    private Tx unary(CypherAst.Unary u, Scope scope) {
        Tx t = tx(u.operand(), scope);
        return switch (u.op()) {
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
            case IS_NULL, IS_NOT_NULL -> {
                boolean isNull = u.op() == CypherAst.UnaryOp.IS_NULL;
                if (t.kind() == Kind.NULL) {
                    yield Tx.condition(new CBoolean(isNull), new CBoolean(!isNull), false);
                }
                if (!t.nullable()) {
                    yield Tx.condition(new CBoolean(!isNull), new CBoolean(isNull), false);
                }
                ValueSpecification known = t.kind() == Kind.BOOLEAN ? call("or", t.vs(), t.falseWhen())
                        : call("isNotEmpty", t.vs());
                yield isNull ? Tx.condition(call("not", known), known, false) : Tx.condition(known, call("not",
                        known), false);
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
                yield SqlToPure.strict(t.kind(), List.of(t), v -> call("minus", v.get(0)));
            }
        };
    }

    private Tx binary(CypherAst.Binary b, Scope scope) {
        BinaryOp op = b.op();
        switch (op) {
            case AND, OR, XOR -> {
                Tx l = SqlToPure.truth(booleanOperand(b.left(), scope), op.name(), b.left().pos());
                Tx r = SqlToPure.truth(booleanOperand(b.right(), scope), op.name(), b.right().pos());
                boolean nullable = l.nullable() || r.nullable();
                return switch (op) {
                    case AND -> Tx.condition(call("and", l.vs(), r.vs()), call("or", l.falseWhen(), r.falseWhen()),
                            nullable);
                    case OR -> Tx.condition(call("or", l.vs(), r.vs()), call("and", l.falseWhen(), r.falseWhen()),
                            nullable);
                    default -> Tx.condition(call("or", call("and", l.vs(), r.falseWhen()), call("and", l.falseWhen(),
                            r.vs())), call("or", call("and", l.vs(), r.vs()), call("and", l.falseWhen(),
                            r.falseWhen())), nullable);
                };
            }
            case EQ, NE, LT, LE, GT, GE -> {
                Tx l = tx(b.left(), scope);
                Tx r = tx(b.right(), scope);
                if (op == BinaryOp.EQ || op == BinaryOp.NE) {
                    Tx eq = equal(l, r, b.pos());
                    return op == BinaryOp.EQ ? eq : SqlToPure.not(eq);
                }
                return ordered(op, l, r, b.pos());
            }
            case PLUS, MINUS, TIMES, DIVIDE, MODULO, POWER -> {
                return arithmetic(b, scope);
            }
            case STARTS_WITH, ENDS_WITH, CONTAINS -> {
                Tx l = tx(b.left(), scope);
                Tx r = tx(b.right(), scope);
                if (l.kind() == Kind.NULL || r.kind() == Kind.NULL) {
                    return SqlToPure.nullOf(Kind.BOOLEAN);
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
                return SqlToPure.strictTest(List.of(l, r), v -> call(fn, v.get(0), v.get(1)));
            }
            case IN -> {
                return in(b, scope);
            }
            case REGEX -> throw CypherErrors.unsupported("=~ (a regular expression) is not supported yet", b.pos());
        }
        throw new IllegalStateException("unreachable: " + op);
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

    /** {@code l = r}: null when either is null; values of different types are never equal in Cypher. */
    private static Tx equal(Tx l, Tx r, Pos pos) {
        if (l.kind() == Kind.NULL || r.kind() == Kind.NULL) {
            return SqlToPure.nullOf(Kind.BOOLEAN);
        }
        if (l.kind() == Kind.BOOLEAN || r.kind() == Kind.BOOLEAN) {
            if (l.kind() != r.kind()) {
                throw CypherErrors.unsupported("comparing a boolean with a " + cypherType(l.kind() == Kind.BOOLEAN
                        ? r.kind() : l.kind()) + " (never equal in Neo4j) is not supported yet", pos);
            }
            return SqlToPure.strictTest(List.of(l, r), v -> call("equal", v.get(0), v.get(1)));
        }
        if (!comparable(l.kind(), r.kind())) {
            throw CypherErrors.unsupported("comparing a " + cypherType(l.kind()) + " with a " + cypherType(r.kind())
                    + " (never equal in Neo4j) is not supported yet", pos);
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

    private Tx arithmetic(CypherAst.Binary b, Scope scope) {
        Tx l = tx(b.left(), scope);
        Tx r = tx(b.right(), scope);
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
                yield SqlToPure.strict(Kind.INTEGER, List.of(l, r), v -> call("rem", v.get(0), v.get(1)));
            }
            case POWER -> SqlToPure.strict(Kind.FLOAT, List.of(l, r), v -> call("pow", call("toFloat", v.get(0)),
                    v.get(1)));
            default -> throw new IllegalStateException("unreachable: " + op);
        };
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

    /** {@code x IN [a, b, c]} over a list literal of values none of which is null: null when {@code x} is, else
     *  whether a member equals it. */
    private Tx in(CypherAst.Binary b, Scope scope) {
        if (!(b.right() instanceof CypherAst.ListLit list)) {
            throw CypherErrors.unsupported("IN with a right side other than a list literal is not supported yet",
                    b.pos());
        }
        Tx l = tx(b.left(), scope);
        if (list.elements().isEmpty()) {
            return Tx.condition(new CBoolean(false), new CBoolean(true), false);
        }
        if (l.kind() == Kind.NULL) {
            return SqlToPure.nullOf(Kind.BOOLEAN);
        }
        List<ValueSpecification> members = new ArrayList<>();
        for (Expr e : list.elements()) {
            Tx m = tx(e, scope);
            if (m.kind() == Kind.NULL || m.nullable()) {
                throw CypherErrors.unsupported("IN over a list holding null is not supported yet", e.pos());
            }
            if (m.kind() == Kind.BOOLEAN || !comparable(l.kind(), m.kind())) {
                throw CypherErrors.unsupported("IN over a list of values of another type is not supported yet",
                        e.pos());
            }
            members.add(SqlToPure.valueOf(m));
        }
        return SqlToPure.strictTest(List.of(l), v -> call("in", v.get(0), new PureCollection(members)));
    }

    private Tx call(CypherAst.Call c, Scope scope) {
        String fn = c.name().toLowerCase(Locale.ROOT);
        if (AGGREGATES.contains(fn)) {
            throw CypherErrors.invalid("Invalid use of aggregating function " + c.name() + "(...) in this context",
                    c.pos());
        }
        List<Tx> args = new ArrayList<>();
        for (Expr a : c.args()) {
            args.add(tx(a, scope));
        }
        if (fn.equals("coalesce")) {
            return coalesce(c, args);
        }
        throw CypherErrors.unsupported("the function " + c.name() + "(...) is not supported yet", c.pos());
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
