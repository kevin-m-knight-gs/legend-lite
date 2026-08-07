package com.legend.parser;

import com.legend.model.ParsedModel;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Phase M step 1 pins: section routing is REGISTRY-adjudicated and an
 *  unclaimed section is DATA, never lexer silence. */
class SectionGrammarRegistryTest {

    @Test
    void builtInsRegisterThroughTheRegistry() {
        for (String s : new String[]{"Pure", "Mapping", "Relational",
                "Connection", "Runtime"}) {
            assertTrue(SectionGrammarRegistry.lookup(s).isPresent(), s);
        }
        assertTrue(SectionGrammarRegistry.lookup("Diagram").isEmpty());
    }

    @Test
    void unclaimedSectionSurfacesOnTheParsedModel() {
        ParsedModel m = ElementParser.parse("""
                Class my::A { a: String[1]; }

                ###Diagram
                Diagram my::D(width=1.0, height=2.0) {}

                ###Pure
                Class my::B { b: String[1]; }
                """);
        assertEquals(2, m.elements().size(), "both Pure classes parse");
        assertEquals(1, m.unclaimedSections().size());
        assertEquals("Diagram", m.unclaimedSections().get(0).name());
    }

    @Test
    void overlayJarSectionParsesThroughTheServiceLoaderSeam() {
        // the whole Phase M point: a section legend-lite does not know,
        // added WITHOUT forking — ServiceLoader discovery, raw text in,
        // opaque elements out, claimed (not unclaimed)
        ParsedModel m = ElementParser.parse("""
                Class my::A { a: String[1]; }

                ###Toy
                Toy my::toys::T1;
                Toy my::toys::T2;
                """);
        assertTrue(m.unclaimedSections().isEmpty(), "Toy is CLAIMED");
        var opaques = m.elements().stream()
                .filter(e -> e instanceof com.legend.model.OpaqueElementDefinition)
                .map(e -> (com.legend.model.OpaqueElementDefinition) e)
                .toList();
        assertEquals(2, opaques.size());
        assertEquals("my::toys::T1", opaques.get(0).qualifiedName());
        assertEquals("Toy", opaques.get(0).sectionName());
        assertEquals(3, m.elements().size(),
                "opaque elements ride the SAME element list — indexed and"
                        + " routed like any element");
        assertTrue(ToySectionGrammar.lastText.contains("Toy my::toys::T2;"),
                "the grammar received the section's raw text");
    }

    @Test
    void registeredSectionsAreNeverUnclaimed() {
        ParsedModel m = ElementParser.parse(
                "Class my::A { a: String[1]; }");
        assertTrue(m.unclaimedSections().isEmpty());
    }
}
