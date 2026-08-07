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
    void registeredSectionsAreNeverUnclaimed() {
        ParsedModel m = ElementParser.parse(
                "Class my::A { a: String[1]; }");
        assertTrue(m.unclaimedSections().isEmpty());
    }
}
