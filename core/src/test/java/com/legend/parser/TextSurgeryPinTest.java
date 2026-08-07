package com.legend.parser;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Pins for the text-surgery audit §1.1 wrong-answer fixes — each test IS the
 * audit's counterexample, asserted on the fixed behavior.
 */
class TextSurgeryPinTest {

    @Test
    void witnessPrefixInvertsExactlyForClassNamesContainingDoubleUnderscore() {
        // §1.1 #1: my::A__B and my::A::B mint the same prefix; the string
        // demangle returned my::A::B (simple name "B") for a class actually
        // named A__B. The inverse is a MODEL lookup by re-mangling.
        String pfx = com.legend.model.ClassMapping.subTypeColumnPrefix("my::A__B");
        assertEquals("my::A__B", com.legend.model.ClassMapping
                .classOfWitnessPrefix(pfx, List.of("other::C", "my::A__B")));
        assertEquals(null, com.legend.model.ClassMapping
                .classOfWitnessPrefix(pfx, List.of("other::C")));
    }

    @Test
    void dataCellColonIsNotAPathSeparator() {
        // §1.1 #2: indexOf(':') found a colon anywhere — a cell containing
        // 10:30 garbled path, columns and rows. A path colon must head the
        // block as one dotted name.
        assertEquals(-1, ElementParser.pathColonOf("a,b\n1,10:30"));
        assertEquals(-1, ElementParser.pathColonOf("time\n10:30"));
        assertEquals(-1, ElementParser.pathColonOf("\"x:y\",b\nv,w"));
        assertEquals("default.T".length() + 2,
                ElementParser.pathColonOf("  default.T:\n a,b\n 1,2"));
        assertEquals(-1, ElementParser.pathColonOf("a,b\n1,2"));
    }
}
