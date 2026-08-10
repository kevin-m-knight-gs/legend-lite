package com.legend.protocol;

import com.legend.protocol.Protocol.PClass;
import com.legend.protocol.Protocol.PProperty;
import com.legend.protocol.Protocol.PSection;
import com.legend.protocol.Protocol.PSectionIndex;
import com.legend.protocol.Protocol.PureModelContextData;
import com.legend.protocol.SourceInfo;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Byte-identity pins for {@link ProtocolEmitter}.
 *
 * <p>Each {@code EXPECTED_*} constant is the <b>verbatim output of legend-engine's own parser</b>
 * serialised through
 * {@code ObjectMapperFactory.getNewStandardObjectMapperWithPureProtocolExtensionSupports()} — the
 * mapper its HTTP endpoint uses. Captured 2026-08-04 against {@code legend-engine d0b4c3a2f68}.
 *
 * <p><b>These pins deliberately live in {@code core}, which has no legend-engine dependency.</b>
 * The {@code parser-equivalence} harness re-derives the same bytes from the <em>live</em> upstream
 * parser and fails if they drift, so the pin here is fast and dependency-free while the harness
 * keeps it honest. A pin that drifts without the harness noticing is exactly the failure mode that
 * rotted upstream's own 26 golden fixtures for 16 months.
 */
class ProtocolEmitterTest {

    /**
     * <pre>
     * Class model::Person
     * {
     *   name: String[1];
     * }
     * </pre>
     */
    private static final String EXPECTED_SIMPLE_CLASS =
            "{\"_type\":\"data\",\"elements\":[{\"_type\":\"class\",\"constraints\":[],\"name\":\"Person\","
            + "\"originalMilestonedProperties\":[],\"package\":\"model\",\"properties\":[{\"genericType\":"
            + "{\"multiplicityArguments\":[],\"rawType\":{\"_type\":\"packageableType\",\"fullPath\":\"String\","
            + "\"sourceInformation\":{\"endColumn\":14,\"endLine\":3,\"sourceId\":\"\",\"startColumn\":9,"
            + "\"startLine\":3}},\"typeArguments\":[],\"typeVariableValues\":[]},\"multiplicity\":"
            + "{\"lowerBound\":1,\"upperBound\":1},\"name\":\"name\",\"sourceInformation\":{\"endColumn\":18,"
            + "\"endLine\":3,\"sourceId\":\"\",\"startColumn\":3,\"startLine\":3},\"stereotypes\":[],"
            + "\"taggedValues\":[]}],\"qualifiedProperties\":[],\"sourceInformation\":{\"endColumn\":1,"
            + "\"endLine\":4,\"sourceId\":\"\",\"startColumn\":1,\"startLine\":1},\"stereotypes\":[],"
            + "\"superTypes\":[],\"taggedValues\":[]},{\"_type\":\"sectionIndex\",\"name\":\"SectionIndex\","
            + "\"package\":\"__internal__\",\"sections\":[{\"_type\":\"importAware\",\"elements\":"
            + "[\"model::Person\"],\"imports\":[],\"parserName\":\"Pure\",\"sourceInformation\":"
            + "{\"endColumn\":2,\"endLine\":6,\"sourceId\":\"\",\"startColumn\":1,\"startLine\":1}}]}]}";

    @Test
    void simpleClassIsByteIdenticalToLegendEngine() {
        PProperty name = new PProperty("name",
                new com.legend.protocol.TypeExpression.NameRef("String", new SourceInfo("", 3, 9, 3, 14)),
                new com.legend.protocol.Multiplicity.Concrete(1, 1),
                List.of(), List.of(),
                new SourceInfo("", 3, 3, 3, 18), null);
        PClass person = new PClass("model", "Person", List.of(), List.of(), List.of(name),
                List.of(), List.of(), List.of(), List.of(), false, new SourceInfo("", 1, 1, 4, 1));
        PSectionIndex sections = new PSectionIndex("__internal__", "SectionIndex",
                List.of(new PSection(true, "Pure", List.of("model::Person"),
                        List.of(), new SourceInfo("", 1, 1, 6, 2))));

        assertEquals(EXPECTED_SIMPLE_CLASS,
                ProtocolEmitter.emit(new PureModelContextData(List.of(person, sections))));
    }

    /**
     * {@code superTypes} on the wire — captured from legend-engine for
     * {@code Class a::B extends a::C\n{\n}\n}. Note the entry has no {@code _type}, its fields are
     * alphabetical, and {@code a::C} spans columns 20-23 inclusive.
     */
    @Test
    void superTypesMatchTheWire() {
        PClass c = new PClass("a", "B", List.of(),
                List.of(new Protocol.PSuperType(
                        new com.legend.protocol.TypeExpression.NameRef("a::C"),
                        new SourceInfo("", 1, 20, 1, 23))),
                List.of(), List.of(), List.of(), List.of(), List.of(), false,
                new SourceInfo("", 1, 1, 3, 1));
        String json = ProtocolEmitter.emitElement(c);
        assertEquals("[{\"path\":\"a::C\",\"sourceInformation\":{\"endColumn\":23,\"endLine\":1,"
                        + "\"sourceId\":\"\",\"startColumn\":20,\"startLine\":1},\"type\":\"CLASS\"}]",
                json.replaceAll(".*\"superTypes\":(\\[.*?\\]),\"taggedValues.*", "$1"));
    }

    /** {@code NON_NULL}: a {@code [1..*]} upper bound is null upstream and vanishes from the wire. */
    @Test
    void nullUpperBoundIsOmittedNotEmittedAsNull() {
        PClass c = new PClass("m", "C", List.of(), List.of(),
                List.of(new PProperty("xs",
                        new com.legend.protocol.TypeExpression.NameRef("String", new SourceInfo("", 1, 1, 1, 1)),
                        new com.legend.protocol.Multiplicity.Concrete(1, null),
                        List.of(), List.of(),
                        new SourceInfo("", 1, 1, 1, 1), null)),
                List.of(), List.of(), List.of(), List.of(), false, new SourceInfo("", 1, 1, 1, 1));
        String json = ProtocolEmitter.emit(new PureModelContextData(List.of(c)));

        org.junit.jupiter.api.Assertions.assertTrue(json.contains("\"multiplicity\":{\"lowerBound\":1}"),
                "an absent upper bound must be omitted entirely, not rendered as null: " + json);
        org.junit.jupiter.api.Assertions.assertFalse(json.contains("upperBound"), json);
    }

    /** Strings are escaped the way Jackson escapes them, or the bytes diverge on any quoted name. */
    @Test
    void stringEscapingMatchesJackson() {
        PClass c = new PClass("m", "A\"B\\C\nD", List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of(), List.of(), false, new SourceInfo("", 1, 1, 1, 1));
        assertEquals("\"A\\\"B\\\\C\\nD\"",
                ProtocolEmitter.emit(new PureModelContextData(List.of(c)))
                        .replaceAll(".*\"name\":(\".*?[^\\\\]\"),\"original.*", "$1"));
    }
}
