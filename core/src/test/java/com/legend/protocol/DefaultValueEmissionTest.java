package com.legend.protocol;

import com.legend.lexer.Lexer;
import com.legend.lexer.TokenStream;
import com.legend.lexer.TokenType;
import com.legend.parser.ElementParser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * End-to-end byte pin for property default values: OUR lexer + parser + emitter over source
 * text, compared to legend-engine's verbatim output for the same source (captured 2026-08-04
 * via {@code ProbeWireShapes}, engine 4.133.0, HTTP-endpoint mapper).
 *
 * <p>Conventions pinned: {@code defaultValue} is alphabetically FIRST among the property's
 * fields; its outer span covers the whole default expression and equals the literal's own span;
 * a string literal's span INCLUDES the surrounding quotes; the value node is a full
 * value-specification ({@code _type}/{@code sourceInformation}/{@code value}).
 */
class DefaultValueEmissionTest {

    private static String emitFirstClass(String source) {
        TokenStream ts = Lexer.tokenize(source);
        int idx = ElementParser.topLevelIndexes(ts, TokenType.CLASS).get(0);
        return ProtocolEmitter.emitElement(com.legend.testing.Engine.at(ts, idx).parseClassDefinition(false));
    }

    private static final String EXPECTED_DEFAULTS =
            "{\"_type\":\"class\",\"constraints\":[],\"name\":\"C\",\"originalMilestonedProperties\":[],\"package\":\"d\",\"properties\":[{\"defaultValue\":{\"sourceInformation\":{\"endColumn\":26,\"endLine\":3,\"sourceId\":\"\",\"startColumn\":22,\"startLine\":3},\"value\":{\"_type\":\"boolean\",\"sourceInformation\":{\"endColumn\":26,\"endLine\":3,\"sourceId\":\"\",\"startColumn\":22,\"startLine\":3},\"value\":false}},\"genericType\":{\"multiplicityArguments\":[],\"rawType\":{\"_type\":\"packageableType\",\"fullPath\":\"Boolean\",\"sourceInformation\":{\"endColumn\":15,\"endLine\":3,\"sourceId\":\"\",\"startColumn\":9,\"startLine\":3}},\"typeArguments\":[],\"typeVariableValues\":[]},\"multiplicity\":{\"lowerBound\":1,\"upperBound\":1},\"name\":\"flag\",\"sourceInformation\":{\"endColumn\":27,\"endLine\":3,\"sourceId\":\"\",\"startColumn\":3,\"startLine\":3},\"stereotypes\":[],\"taggedValues\":[]},{\"defaultValue\":{\"sourceInformation\":{\"endColumn\":20,\"endLine\":4,\"sourceId\":\"\",\"startColumn\":19,\"startLine\":4},\"value\":{\"_type\":\"integer\",\"sourceInformation\":{\"endColumn\":20,\"endLine\":4,\"sourceId\":\"\",\"startColumn\":19,\"startLine\":4},\"value\":42}},\"genericType\":{\"multiplicityArguments\":[],\"rawType\":{\"_type\":\"packageableType\",\"fullPath\":\"Integer\",\"sourceInformation\":{\"endColumn\":12,\"endLine\":4,\"sourceId\":\"\",\"startColumn\":6,\"startLine\":4}},\"typeArguments\":[],\"typeVariableValues\":[]},\"multiplicity\":{\"lowerBound\":1,\"upperBound\":1},\"name\":\"n\",\"sourceInformation\":{\"endColumn\":21,\"endLine\":4,\"sourceId\":\"\",\"startColumn\":3,\"startLine\":4},\"stereotypes\":[],\"taggedValues\":[]},{\"defaultValue\":{\"sourceInformation\":{\"endColumn\":20,\"endLine\":5,\"sourceId\":\"\",\"startColumn\":18,\"startLine\":5},\"value\":{\"_type\":\"string\",\"sourceInformation\":{\"endColumn\":20,\"endLine\":5,\"sourceId\":\"\",\"startColumn\":18,\"startLine\":5},\"value\":\"x\"}},\"genericType\":{\"multiplicityArguments\":[],\"rawType\":{\"_type\":\"packageableType\",\"fullPath\":\"String\",\"sourceInformation\":{\"endColumn\":11,\"endLine\":5,\"sourceId\":\"\",\"startColumn\":6,\"startLine\":5}},\"typeArguments\":[],\"typeVariableValues\":[]},\"multiplicity\":{\"lowerBound\":1,\"upperBound\":1},\"name\":\"s\",\"sourceInformation\":{\"endColumn\":21,\"endLine\":5,\"sourceId\":\"\",\"startColumn\":3,\"startLine\":5},\"stereotypes\":[],\"taggedValues\":[]}],\"qualifiedProperties\":[],\"sourceInformation\":{\"endColumn\":1,\"endLine\":6,\"sourceId\":\"\",\"startColumn\":1,\"startLine\":1},\"stereotypes\":[],\"superTypes\":[],\"taggedValues\":[]}";

    @Test
    void literalDefaultValuesAreByteIdentical() {
        assertEquals(EXPECTED_DEFAULTS, emitFirstClass("""
                Class d::C
                {
                  flag: Boolean[1] = false;
                  n: Integer[1] = 42;
                  s: String[1] = 'x';
                }
                """));
    }
}
