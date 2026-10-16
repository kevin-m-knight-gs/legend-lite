// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend.compiler.element;

import com.legend.Compiler;
import com.legend.error.ModelException;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The property-graph profile's reader (docs/CYPHER_STUDY_2026_10_15.md §5.6): the edge classes of a model, in
 *  the association and the compact form, inherited ends, the typed relationship type, and the rule's errors in
 *  phase F. */
class GraphEdgesTest {

    private static final String PROFILE = """
            Profile graph::graph
            {
               stereotypes: [Edge, from, to, type];
            }
            """;

    private static final String NODES = """
            Class demo::Actor { name: String[1]; }
            Class demo::Movie { title: String[1]; }
            Enum demo::RelationshipType { ACTED_IN, DIRECTED }
            """;

    @Test
    void theAssociationFormReadsItsEndsAndTheirInverses() {
        ModelContext ctx = Compiler.compileModel(PROFILE + NODES + """
                Class <<graph::graph.Edge>> demo::Acting
                {
                  <<graph::graph.type>> type() { demo::RelationshipType.ACTED_IN }: demo::RelationshipType[1];
                  role: String[1];
                }
                Association demo::ActorActing { <<graph::graph.from>> actor: demo::Actor[1]; actings: demo::Acting[*]; }
                Association demo::ActingMovie { <<graph::graph.to>> movie: demo::Movie[1]; cast: demo::Acting[*]; }
                """);
        GraphEdges edges = GraphEdges.of(ctx);
        assertEquals(List.of(new GraphEdges.Edge("demo::Acting", "ACTED_IN",
                new GraphEdges.End("actor", "demo::Actor", "actings", true),
                new GraphEdges.End("movie", "demo::Movie", "cast", true))), edges.edges());
        assertNull(edges.edgeAt("demo::Actor"));
    }

    @Test
    void theCompactFormHasNoInverseAndTheTypeIsTheSimpleName() {
        ModelContext ctx = Compiler.compileModel(PROFILE + NODES + """
                Class <<graph::graph.Edge>> demo::Directing
                {
                  <<graph::graph.from>> director: demo::Actor[1];
                  <<graph::graph.to>> movie: demo::Movie[1];
                }
                """);
        assertEquals(List.of(new GraphEdges.Edge("demo::Directing", "Directing",
                new GraphEdges.End("director", "demo::Actor", null, false),
                new GraphEdges.End("movie", "demo::Movie", null, false))), GraphEdges.of(ctx).edges());
    }

    @Test
    void aSubclassInheritsTheMarkerTheEndsAndTheType() {
        ModelContext ctx = Compiler.compileModel(PROFILE + NODES + """
                Class <<graph::graph.Edge>> demo::Link
                {
                  <<graph::graph.from>> a: demo::Actor[1];
                  <<graph::graph.to>> m: demo::Movie[1];
                  <<graph::graph.type>> type() { demo::RelationshipType.DIRECTED }: demo::RelationshipType[1];
                }
                Class demo::Credited extends demo::Link { credit: String[0..1]; }
                """);
        GraphEdges edges = GraphEdges.of(ctx);
        assertEquals(2, edges.edges().size());
        GraphEdges.Edge sub = edges.edgeAt("demo::Credited");
        assertEquals("DIRECTED", java.util.Objects.requireNonNull(sub).type());
        assertEquals("a", sub.from().property());
    }

    @Test
    void anEndWithoutTheMarkerIsRefused() {
        String message = refused(PROFILE + NODES + """
                Class demo::Plain { <<graph::graph.from>> actor: demo::Actor[1]; }
                """);
        assertTrue(message.contains("demo::Plain carries <<graph::graph.from>> on actor but not"), message);
    }

    @Test
    void aMarkerWithoutBothEndsIsRefused() {
        String message = refused(PROFILE + NODES + """
                Class <<graph::graph.Edge>> demo::Half { <<graph::graph.from>> actor: demo::Actor[1]; }
                """);
        assertTrue(message.contains("demo::Half has no property carrying <<graph::graph.to>>"), message);
    }

    @Test
    void anOptionalEndIsRefused() {
        String message = refused(PROFILE + NODES + """
                Class <<graph::graph.Edge>> demo::Loose
                {
                  <<graph::graph.from>> actor: demo::Actor[0..1];
                  <<graph::graph.to>> movie: demo::Movie[1];
                }
                """);
        assertTrue(message.contains("actor, which is not [1]"), message);
    }

    @Test
    void aTypeThatIsNotOneEnumerationValueIsRefused() {
        String message = refused(PROFILE + NODES + """
                Class <<graph::graph.Edge>> demo::Acting
                {
                  <<graph::graph.from>> actor: demo::Actor[1];
                  <<graph::graph.to>> movie: demo::Movie[1];
                  <<graph::graph.type>> type() { 'ACTED_IN' }: String[1];
                }
                """);
        assertTrue(message.contains("whose body is not one spelled enumeration value"), message);
    }

    @Test
    void aToManyClassPropertyOnAnEdgeIsRefused() {
        String message = refused(PROFILE + NODES + """
                Class <<graph::graph.Edge>> demo::Acting
                {
                  <<graph::graph.from>> actor: demo::Actor[1];
                  <<graph::graph.to>> movie: demo::Movie[1];
                  others: demo::Actor[*];
                }
                """);
        assertTrue(message.contains("holds many demo::Actor in others"), message);
    }

    @Test
    void theProfileMustBeDeclared() {
        String message = refused(NODES + """
                Class <<graph.Edge>> demo::Acting
                {
                  <<graph.from>> actor: demo::Actor[1];
                  <<graph.to>> movie: demo::Movie[1];
                }
                """);
        assertTrue(message.contains("the profile graph::graph is not declared"), message);
    }

    @Test
    void aModelWithoutEdgesReadsNone() {
        assertEquals(List.of(), GraphEdges.of(Compiler.compileModel(NODES)).edges());
    }

    private static String refused(String model) {
        ModelException e = assertThrows(ModelException.class, () -> Compiler.compileModel(model));
        return String.valueOf(e.getMessage());
    }
}
