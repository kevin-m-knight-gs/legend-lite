// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend.compiler.element;

import com.legend.compiler.element.type.PlatformTypes;
import com.legend.error.LegendCompileException;
import com.legend.error.ModelException;
import com.legend.model.AssociationDefinition;
import com.legend.model.ClassDefinition;
import com.legend.model.ProfileDefinition;
import com.legend.model.StereotypeApplication;
import com.legend.protocol.DerivedPropertyDefinition;
import com.legend.protocol.Multiplicity;
import com.legend.protocol.Protocol;
import com.legend.protocol.TypeExpression;
import com.legend.protocol.spec.EnumValue;
import com.legend.protocol.spec.ValueSpecification;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

/**
 * The model's edge classes (docs/CYPHER_STUDY_2026_10_15.md §5.6; the profile is {@code projects/graph}), read
 * from the declarations in phase F the way {@link EqualityKeys} reads {@code <<equality.Key>>}: a class carrying
 * {@code <<graph.Edge>>} (itself or a superclass) is a relationship type with properties; its two ends are the
 * properties carrying {@code <<graph.from>>} and {@code <<graph.to>>}, each to-one and typed by a class, declared
 * on the class, inherited, or injected by an association (whose other end is then the node class's navigation to
 * the edge, the inverse); its type is the enumeration value the derived property carrying {@code <<graph.type>>}
 * spells, else the class's simple name; every other stored property is the relationship's.
 *
 * <p>The rule is checked here, in phase F ({@link ModelIntegrity}), with an error naming the class: exactly one
 * end of each kind, each {@code [1]} and class-typed; an end stereotype without the class marker, or the marker
 * without both ends, is an error; a to-many property holding a class is refused on an edge (a relationship has
 * two ends); the profile at {@link PlatformTypes#GRAPH_PROFILE} must be declared with exactly the four
 * stereotypes. The engine checks every reference in the declaration; only lite checks the rule, since only lite
 * runs the island. The Cypher seam reads the result as a derived view of the model
 * ({@code ModelContext.derived}).
 */
public record GraphEdges(List<Edge> edges) {

    /** An edge class: its path, its relationship type, its two ends. */
    public record Edge(String classFqn, String type, End from, End to) {
    }

    /** One end of an edge: the property on the edge class holding the node, the node's class, and, when the
     *  property is an association end, the inverse end the node class holds (its name and whether it holds
     *  many edges); null for an end that is the edge class's own property. */
    public record End(String property, String classFqn, @com.legend.base.Nullable String inverse,
            boolean inverseMany) {
    }

    /** The declarations the reader walks: the compiled model's or, in phase F, the builder's. */
    public interface Source {
        Stream<ClassDefinition> classes();

        Stream<AssociationDefinition> associations();

        Optional<ClassDefinition> findClass(String fqn);

        Optional<ProfileDefinition> findProfile(String fqn);
    }

    public GraphEdges {
        edges = List.copyOf(edges);
    }

    /** The edge class at {@code classFqn}, or null when the class is none. */
    public @com.legend.base.Nullable Edge edgeAt(String classFqn) {
        for (Edge e : edges) {
            if (e.classFqn().equals(classFqn)) {
                return e;
            }
        }
        return null;
    }

    /** The compiled model's edge classes (the Cypher seam's derived view). */
    public static GraphEdges of(ModelContext ctx) {
        return read(new Source() {
            @Override
            public Stream<ClassDefinition> classes() {
                return new TreeSet<>(ctx.elementFqns()).stream().map(ctx::findClassDefinition)
                        .flatMap(Optional::stream);
            }

            @Override
            public Stream<AssociationDefinition> associations() {
                return new TreeSet<>(ctx.elementFqns()).stream().map(ctx::findAssociation).flatMap(Optional::stream);
            }

            @Override
            public Optional<ClassDefinition> findClass(String fqn) {
                return ctx.findClassDefinition(fqn);
            }

            @Override
            public Optional<ProfileDefinition> findProfile(String fqn) {
                return ctx.findProfile(fqn);
            }
        });
    }

    /** The builder's declarations, as phase F checks them. */
    public static Source sourceOf(com.legend.compiler.ModelBuilder model) {
        return new Source() {
            @Override
            public Stream<ClassDefinition> classes() {
                return model.classes().sorted(java.util.Comparator.comparing(ClassDefinition::qualifiedName));
            }

            @Override
            public Stream<AssociationDefinition> associations() {
                return model.associations();
            }

            @Override
            public Optional<ClassDefinition> findClass(String fqn) {
                return model.findClass(fqn);
            }

            @Override
            public Optional<ProfileDefinition> findProfile(String fqn) {
                return model.findProfile(fqn);
            }
        };
    }

    /** Reads every edge class of {@code src}, refusing a misdeclared one with an error naming it. */
    public static GraphEdges read(Source src) {
        List<Edge> edges = new ArrayList<>();
        List<AssociationDefinition> associations = null;
        boolean profileChecked = false;
        for (ClassDefinition cd : src.classes().toList()) {
            List<String> lineage = lineage(src, cd);
            boolean marked = false;
            for (String fqn : lineage) {
                ClassDefinition c = src.findClass(fqn).orElse(null);
                if (c != null && c.stereotypes().stream().anyMatch(st -> is(st, "Edge"))) {
                    marked = true;
                }
            }
            // the ends: own and inherited stored properties carrying from/to (a redeclaration shadows by name),
            // then the association ends injected onto the class or a superclass
            List<EndRead> ends = new ArrayList<>();
            Set<String> seen = new LinkedHashSet<>();
            for (String fqn : lineage) {
                ClassDefinition c = src.findClass(fqn).orElse(null);
                if (c == null) {
                    continue;
                }
                for (ClassDefinition.PropertyDefinition pd : c.properties()) {
                    if (!seen.add(pd.name())) {
                        continue;
                    }
                    String kind = endKind(pd.stereotypes());
                    if (kind != null) {
                        ends.add(new EndRead(pd.name(), kind, pd.type(), pd.multiplicity(), null, false, fqn));
                    }
                }
            }
            if (associations == null && (marked || !ends.isEmpty() || cd.derivedProperties().stream()
                    .anyMatch(dp -> typeMarked(dp)))) {
                associations = src.associations().toList();
            }
            if (associations != null) {
                for (String fqn : lineage) {
                    for (AssociationDefinition ad : associations) {
                        for (int i = 0; i < 2; i++) {
                            AssociationDefinition.AssociationEndDefinition end = i == 0 ? ad.property1()
                                    : ad.property2();
                            AssociationDefinition.AssociationEndDefinition other = i == 0 ? ad.property2()
                                    : ad.property1();
                            // an end injects onto the class the other end names
                            if (!fqn.equals(className(other.targetClass())) || !seen.add(end.propertyName())) {
                                continue;
                            }
                            String kind = endKind(end.stereotypes());
                            if (kind != null) {
                                ends.add(new EndRead(end.propertyName(), kind, end.targetClass(),
                                        end.multiplicity(), other.propertyName(), !other.isToOne(), fqn));
                            }
                        }
                    }
                }
            }
            if (!marked) {
                if (!ends.isEmpty()) {
                    throw refuse(cd, "carries " + spell(ends) + " but not <<" + PlatformTypes.GRAPH_PROFILE
                            + ".Edge>>: an end belongs to an edge class");
                }
                continue;
            }
            if (!profileChecked) {
                checkProfile(src, cd);
                profileChecked = true;
            }
            EndRead from = one(cd, ends, "from");
            EndRead to = one(cd, ends, "to");
            for (String fqn : lineage) {
                ClassDefinition c = src.findClass(fqn).orElse(null);
                if (c == null) {
                    continue;
                }
                for (ClassDefinition.PropertyDefinition pd : c.properties()) {
                    String target = className(pd.type());
                    if (target != null && src.findClass(target).isPresent() && !toOne(pd.multiplicity())
                            && endKind(pd.stereotypes()) == null) {
                        throw refuse(cd, "holds many " + target + " in " + pd.name() + ": a relationship has two"
                                + " ends, each <<" + PlatformTypes.GRAPH_PROFILE + ".from>> or <<"
                                + PlatformTypes.GRAPH_PROFILE + ".to>> and [1]");
                    }
                }
            }
            edges.add(new Edge(cd.qualifiedName(), typeOf(src, cd, lineage), from.end(), to.end()));
        }
        return new GraphEdges(edges);
    }

    /** An end as read: the property, its kind (from, to), its declared type and multiplicity, its inverse when it
     *  is an association end, and the class it is declared on. */
    private record EndRead(String property, String kind, TypeExpression type, Multiplicity multiplicity,
            @com.legend.base.Nullable String inverse, boolean inverseMany, String declaredOn) {
        End end() {
            return new End(property, java.util.Objects.requireNonNull(className(type)), inverse, inverseMany);
        }
    }

    /** The one end of {@code kind} the class has: exactly one, to-one, typed by a class. */
    private static EndRead one(ClassDefinition cd, List<EndRead> ends, String kind) {
        List<EndRead> of = ends.stream().filter(e -> e.kind().equals(kind)).toList();
        String marker = "<<" + PlatformTypes.GRAPH_PROFILE + "." + kind + ">>";
        if (of.isEmpty()) {
            throw refuse(cd, "has no property carrying " + marker + ": an edge has a " + kind + " end");
        }
        if (of.size() > 1) {
            throw refuse(cd, "carries " + marker + " on " + of.size() + " properties (" + String.join(", ",
                    of.stream().map(EndRead::property).toList()) + "): an edge has one " + kind + " end");
        }
        EndRead e = of.get(0);
        if (!toOne(e.multiplicity()) || !(e.multiplicity() instanceof Multiplicity.Concrete c) || c.lowerBound() != 1) {
            throw refuse(cd, "carries " + marker + " on " + e.property() + ", which is not [1]: a relationship always"
                    + " has its " + kind + " node");
        }
        if (className(e.type()) == null) {
            throw refuse(cd, "carries " + marker + " on " + e.property() + ", which is not typed by a class: the "
                    + kind + " end is a node");
        }
        return e;
    }

    /** The relationship type: the enumeration value the derived property carrying {@code <<graph.type>>} spells
     *  (the class's own, else an inherited one), else the class's simple name. */
    private static String typeOf(Source src, ClassDefinition cd, List<String> lineage) {
        for (String fqn : lineage) {
            ClassDefinition c = src.findClass(fqn).orElse(null);
            if (c == null) {
                continue;
            }
            List<DerivedPropertyDefinition> typed = c.derivedProperties().stream().filter(GraphEdges::typeMarked)
                    .toList();
            if (typed.isEmpty()) {
                continue;
            }
            String marker = "<<" + PlatformTypes.GRAPH_PROFILE + ".type>>";
            if (typed.size() > 1) {
                throw refuse(cd, "carries " + marker + " on " + typed.size() + " derived properties: an edge has one"
                        + " type");
            }
            DerivedPropertyDefinition dp = typed.get(0);
            List<ValueSpecification> body = dp.realization() instanceof com.legend.protocol.Realization.Inline inline
                    ? inline.body() : List.of();
            if (!dp.parameters().isEmpty() || body.size() != 1 || !(body.get(0) instanceof EnumValue ev)) {
                throw refuse(cd, "carries " + marker + " on " + dp.name() + ", whose body is not one spelled"
                        + " enumeration value: the type is a typed constant");
            }
            return ev.value();
        }
        return Protocol.splitFqn(cd.qualifiedName())[1];
    }

    /** The profile at its path, declaring exactly the four stereotypes and no tag: checked once a model declares
     *  an edge class (the island reads the stereotypes by the profile's full name). */
    private static void checkProfile(Source src, ClassDefinition cd) {
        ProfileDefinition p = src.findProfile(PlatformTypes.GRAPH_PROFILE).orElse(null);
        if (p == null) {
            throw refuse(cd, "is an edge class, but the profile " + PlatformTypes.GRAPH_PROFILE + " is not declared:"
                    + " a model with edge classes depends on the graph project");
        }
        if (!new TreeSet<>(p.stereotypes()).equals(new TreeSet<>(List.of("Edge", "from", "to", "type")))
                || !p.tags().isEmpty()) {
            throw refuse(cd, "is an edge class, but the profile " + PlatformTypes.GRAPH_PROFILE + " declares "
                    + p.stereotypes() + " and tags " + p.tags() + ", not the stereotypes [Edge, from, to, type] and no"
                    + " tag");
        }
    }

    /** The class, then its superclasses, each once (a diamond contributes once; a cycle is phase F's own error). */
    private static List<String> lineage(Source src, ClassDefinition cd) {
        List<String> out = new ArrayList<>();
        java.util.Deque<String> todo = new java.util.ArrayDeque<>(List.of(cd.qualifiedName()));
        while (!todo.isEmpty()) {
            String fqn = todo.poll();
            if (out.contains(fqn)) {
                continue;
            }
            out.add(fqn);
            ClassDefinition c = src.findClass(fqn).orElse(null);
            if (c != null) {
                for (TypeExpression sup : c.superClasses()) {
                    String name = className(sup);
                    if (name != null) {
                        todo.add(name);
                    }
                }
            }
        }
        return out;
    }

    private static boolean is(StereotypeApplication st, String name) {
        return PlatformTypes.isProfile(st.profileName(), PlatformTypes.GRAPH_PROFILE)
                && name.equals(st.stereotypeName());
    }

    private static boolean typeMarked(DerivedPropertyDefinition dp) {
        for (Protocol.PStereotype st : dp.stereotypes()) {
            if (PlatformTypes.isProfile(st.profile(), PlatformTypes.GRAPH_PROFILE) && "type".equals(st.value())) {
                return true;
            }
        }
        return false;
    }

    /** "from" or "to" when the stereotypes carry one of the end markers; null otherwise. */
    private static @com.legend.base.Nullable String endKind(List<StereotypeApplication> stereotypes) {
        String kind = null;
        for (StereotypeApplication st : stereotypes) {
            if (is(st, "from")) {
                kind = "from";
            } else if (is(st, "to")) {
                kind = "to";
            }
        }
        return kind;
    }

    private static boolean toOne(Multiplicity m) {
        return m instanceof Multiplicity.Concrete c && Integer.valueOf(1).equals(c.upperBound());
    }

    private static @com.legend.base.Nullable String className(TypeExpression type) {
        return type instanceof TypeExpression.NameRef nr ? nr.name() : null;
    }

    private static String spell(List<EndRead> ends) {
        List<String> out = new ArrayList<>();
        for (EndRead e : ends) {
            out.add("<<" + PlatformTypes.GRAPH_PROFILE + "." + e.kind() + ">> on " + e.property());
        }
        return String.join(", ", out);
    }

    private static ModelException refuse(ClassDefinition cd, String what) {
        return new ModelException(LegendCompileException.Phase.MODEL, "the class " + cd.qualifiedName() + " " + what,
                cd.qualifiedName());
    }
}
