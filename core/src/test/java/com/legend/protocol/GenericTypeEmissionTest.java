package com.legend.protocol;

import com.legend.lexer.Lexer;
import com.legend.lexer.TokenStream;
import com.legend.lexer.TokenType;
import com.legend.parser.ElementParser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * End-to-end byte pins for generic type emission: OUR lexer + parser + emitter over source
 * text, compared to legend-engine's verbatim output for the same source.
 *
 * <p>Each {@code EXPECTED_*} constant was captured 2026-08-04 via {@code ProbeWireShapes}
 * (parser-equivalence) from {@code legend-engine-language-pure-grammar:4.133.0} through
 * {@code getNewStandardObjectMapperWithPureProtocolExtensionSupports()} — the HTTP endpoint's
 * mapper. The corpus-scale harness re-derives these from the live upstream parser and fails on
 * drift; the pins here keep the fast, dependency-free gate honest.
 *
 * <p>The convention these pin (verified, not assumed): a generic's {@code rawType} span covers
 * the WHOLE application including the closing {@code >}; each type argument is a full nested
 * {@code genericType} carrying its own span; a GENERIC SUPERTYPE emits only the base path —
 * its type arguments are dropped from the wire — while the span still covers the whole
 * expression.
 */
class GenericTypeEmissionTest {

    private static String emitFirstClass(String source) {
        TokenStream ts = Lexer.tokenize(source);
        int idx = ElementParser.topLevelIndexes(ts, TokenType.CLASS).get(0);
        return ProtocolEmitter.emitElement(com.legend.testing.Engine.at(ts, idx).parseClassDefinition(false));
    }

    private static final String EXPECTED_GENERIC_PROPERTY =
            "{\"_type\":\"class\",\"constraints\":[],\"name\":\"C\",\"originalMilestonedProperties\":[],"
            + "\"package\":\"a\",\"properties\":[{\"genericType\":{\"multiplicityArguments\":[],"
            + "\"rawType\":{\"_type\":\"packageableType\",\"fullPath\":\"a::D\",\"sourceInformation\":"
            + "{\"endColumn\":17,\"endLine\":3,\"sourceId\":\"\",\"startColumn\":6,\"startLine\":3}},"
            + "\"typeArguments\":[{\"multiplicityArguments\":[],\"rawType\":{\"_type\":\"packageableType\","
            + "\"fullPath\":\"String\",\"sourceInformation\":{\"endColumn\":16,\"endLine\":3,\"sourceId\":\"\","
            + "\"startColumn\":11,\"startLine\":3}},\"typeArguments\":[],\"typeVariableValues\":[]}],"
            + "\"typeVariableValues\":[]},\"multiplicity\":{\"lowerBound\":1,\"upperBound\":1},\"name\":\"p\","
            + "\"sourceInformation\":{\"endColumn\":21,\"endLine\":3,\"sourceId\":\"\",\"startColumn\":3,"
            + "\"startLine\":3},\"stereotypes\":[],\"taggedValues\":[]}],\"qualifiedProperties\":[],"
            + "\"sourceInformation\":{\"endColumn\":1,\"endLine\":4,\"sourceId\":\"\",\"startColumn\":1,"
            + "\"startLine\":1},\"stereotypes\":[],\"superTypes\":[],\"taggedValues\":[]}";

    @Test
    void genericPropertyTypeIsByteIdentical() {
        assertEquals(EXPECTED_GENERIC_PROPERTY, emitFirstClass("""
                Class a::C
                {
                  p: a::D<String>[1];
                }
                """));
    }

    private static final String EXPECTED_NESTED_GENERIC =
            "{\"_type\":\"class\",\"constraints\":[],\"name\":\"C\",\"originalMilestonedProperties\":[],"
            + "\"package\":\"b\",\"properties\":[{\"genericType\":{\"multiplicityArguments\":[],"
            + "\"rawType\":{\"_type\":\"packageableType\",\"fullPath\":\"b::E\",\"sourceInformation\":"
            + "{\"endColumn\":32,\"endLine\":3,\"sourceId\":\"\",\"startColumn\":6,\"startLine\":3}},"
            + "\"typeArguments\":[{\"multiplicityArguments\":[],\"rawType\":{\"_type\":\"packageableType\","
            + "\"fullPath\":\"String\",\"sourceInformation\":{\"endColumn\":16,\"endLine\":3,\"sourceId\":\"\","
            + "\"startColumn\":11,\"startLine\":3}},\"typeArguments\":[],\"typeVariableValues\":[]},"
            + "{\"multiplicityArguments\":[],\"rawType\":{\"_type\":\"packageableType\",\"fullPath\":\"b::D\","
            + "\"sourceInformation\":{\"endColumn\":31,\"endLine\":3,\"sourceId\":\"\",\"startColumn\":19,"
            + "\"startLine\":3}},\"typeArguments\":[{\"multiplicityArguments\":[],\"rawType\":"
            + "{\"_type\":\"packageableType\",\"fullPath\":\"Integer\",\"sourceInformation\":{\"endColumn\":30,"
            + "\"endLine\":3,\"sourceId\":\"\",\"startColumn\":24,\"startLine\":3}},\"typeArguments\":[],"
            + "\"typeVariableValues\":[]}],\"typeVariableValues\":[]}],\"typeVariableValues\":[]},"
            + "\"multiplicity\":{\"lowerBound\":0,\"upperBound\":1},\"name\":\"p\",\"sourceInformation\":"
            + "{\"endColumn\":39,\"endLine\":3,\"sourceId\":\"\",\"startColumn\":3,\"startLine\":3},"
            + "\"stereotypes\":[],\"taggedValues\":[]}],\"qualifiedProperties\":[],\"sourceInformation\":"
            + "{\"endColumn\":1,\"endLine\":4,\"sourceId\":\"\",\"startColumn\":1,\"startLine\":1},"
            + "\"stereotypes\":[],\"superTypes\":[],\"taggedValues\":[]}";

    @Test
    void nestedGenericWithMultipleArgumentsIsByteIdentical() {
        assertEquals(EXPECTED_NESTED_GENERIC, emitFirstClass("""
                Class b::C
                {
                  p: b::E<String, b::D<Integer>>[0..1];
                }
                """));
    }

    private static final String EXPECTED_GENERIC_SUPERTYPE =
            "{\"_type\":\"class\",\"constraints\":[],\"name\":\"C\",\"originalMilestonedProperties\":[],"
            + "\"package\":\"c\",\"properties\":[],\"qualifiedProperties\":[],\"sourceInformation\":"
            + "{\"endColumn\":1,\"endLine\":3,\"sourceId\":\"\",\"startColumn\":1,\"startLine\":1},"
            + "\"stereotypes\":[],\"superTypes\":[{\"path\":\"c::D\",\"sourceInformation\":{\"endColumn\":31,"
            + "\"endLine\":1,\"sourceId\":\"\",\"startColumn\":20,\"startLine\":1},\"type\":\"CLASS\"}],"
            + "\"taggedValues\":[]}";

    @Test
    void genericSupertypeEmitsBasePathOnly() {
        assertEquals(EXPECTED_GENERIC_SUPERTYPE, emitFirstClass("""
                Class c::C extends c::D<String>
                {
                }
                """));
    }
}
