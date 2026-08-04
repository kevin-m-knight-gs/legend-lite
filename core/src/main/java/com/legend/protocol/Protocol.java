package com.legend.protocol;

import java.util.List;

/**
 * The Legend protocol shape — <em>our</em> types, <em>their</em> bytes.
 *
 * <p>These records mirror the shape legend-engine serialises as
 * {@code PureModelContextData}, so {@link ProtocolEmitter} can reproduce its JSON
 * byte-for-byte. They are a <strong>clean-room reimplementation</strong>: legend-lite takes no
 * dependency on {@code legend-engine-protocol-pure}, and nothing here imports
 * {@code org.finos.legend.engine}.
 *
 * <p><b>The protocol is a serialization contract, not a design constraint.</b> Upstream's protocol
 * is mutable public-field POJOs dispatched by Jackson type-ids. Ours is:
 * <ul>
 *   <li><b>sealed</b> hierarchies with explicit {@code permits} — so {@link ProtocolEmitter}'s
 *       switch is exhaustive and adding a variant without an emit rule is a <em>compile error</em>,
 *       the same discipline {@code AGENTS.md} invariant 3 imposes on MIR &rarr; dialect;</li>
 *   <li><b>100% immutable</b> records;</li>
 *   <li><b>loud</b> — no defaulting, no silent absence (invariant 4).</li>
 * </ul>
 *
 * <p>Positions are captured at construction because that is the only point where token offsets are
 * in hand. {@link com.legend.protocol.SourceInfo} follows the engine's convention exactly: 1-based lines, 1-based start
 * column, and an <em>inclusive</em> end column.
 */
public final class Protocol {

    private Protocol() {
    }

    /** Root: {@code {"_type":"data","elements":[...]}}. Null {@code serializer}/{@code origin} are omitted. */
    public record PureModelContextData(List<Element> elements) {
        public PureModelContextData {
            elements = List.copyOf(elements);
        }
    }

    /** A packageable element. Sealed so the emitter's switch is exhaustive. */
    public sealed interface Element permits PClass, PSectionIndex {
    }

    /**
     * {@code _type:"class"} — <b>the parser's output for a {@code Class} declaration</b>.
     *
     * <p>Carries everything the parse produced, which is a superset of what goes on the wire.
     * {@code typeParams} and {@code isNative} have no protocol equivalent and
     * {@link ProtocolEmitter} simply does not emit them: <b>these are our records; the wire shape
     * is the emitter's decision, not the record's.</b> That is what lets the parser have exactly
     * one output while still round-tripping losslessly (via {@code com.legend.model.FromProtocol})
     * into the model for our own compiler.
     */
    public record PClass(String pkg, String name,
                         List<String> typeParams,
                         List<PSuperType> superTypes,
                         List<PProperty> properties,
                         List<com.legend.protocol.DerivedPropertyDefinition> derivedProperties,
                         List<com.legend.protocol.ConstraintDefinition> constraints,
                         List<PStereotype> stereotypes,
                         List<PTaggedValue> taggedValues,
                         boolean isNative,
                         com.legend.protocol.SourceInfo sourceInformation) implements Element {
        public PClass {
            typeParams = List.copyOf(typeParams);
            superTypes = List.copyOf(superTypes);
            properties = List.copyOf(properties);
            derivedProperties = List.copyOf(derivedProperties);
            constraints = List.copyOf(constraints);
            stereotypes = List.copyOf(stereotypes);
            taggedValues = List.copyOf(taggedValues);
        }

        /** The wire's {@code package} + {@code name} recombined — legend-lite keys by FQN, always. */
        public String qualifiedName() {
            return pkg.isEmpty() ? name : pkg + "::" + name;
        }
    }

    /** {@code _type:"sectionIndex"} — synthesised, and always emitted last. */
    public record PSectionIndex(String pkg, String name, List<PSection> sections) implements Element {
        public PSectionIndex {
            sections = List.copyOf(sections);
        }
    }

    /** {@code _type:"importAware"} — the only section kind emitted for {@code ###Pure}. */
    public record PSection(String parserName, List<String> elements, List<String> imports,
                           com.legend.protocol.SourceInfo sourceInformation) {
        public PSection {
            elements = List.copyOf(elements);
            imports = List.copyOf(imports);
        }
    }

    /**
     * A simple (non-derived) property, carrying the <b>parse product</b> — the type expression and
     * multiplicity exactly as parsed — plus the property's own span. The type's span lives on the
     * type node itself ({@code TypeExpression.NameRef#pos()} / {@code Generic#pos()}), threaded by
     * {@code parseType}, so nested type arguments carry their own spans uniformly.
     *
     * <p>It deliberately does <em>not</em> pre-flatten the type into the wire's
     * {@code genericType}/{@code rawType} shape. Doing that at parse time forced the parser to
     * reject type expressions it can parse perfectly well, and to invent a multiplicity when the
     * declaration used a parameter. <b>The parser stays total; the emitter owns what the wire can
     * express</b> and walls loudly on the rest.
     */
    public record PProperty(String name,
                            com.legend.protocol.TypeExpression type,
                            com.legend.protocol.Multiplicity multiplicity,
                            List<PStereotype> stereotypes,
                            List<PTaggedValue> taggedValues,
                            com.legend.protocol.SourceInfo sourceInformation,
                            @com.legend.Nullable PDefaultValue defaultValue) {
        public PProperty {
            stereotypes = List.copyOf(stereotypes);
            taggedValues = List.copyOf(taggedValues);
        }
    }

    /**
     * A property's default value: {@code {"sourceInformation":…,"value":…}} on the wire, no
     * {@code _type}. The outer span covers the whole default expression.
     *
     * <p>{@code value} is {@code null} when the parser accepted the default expression but
     * could not build a value-spec tree for it — the parser stays total; {@link ProtocolEmitter}
     * walls loudly on the null rather than dropping the property or the build silently.
     */
    public record PDefaultValue(@com.legend.Nullable com.legend.protocol.spec.ValueSpecification value,
                                com.legend.protocol.SourceInfo sourceInformation) {
    }

    /** Note: carries no {@code _type} on the wire. */
    public record PGenericType(PPackageableType rawType) {
    }

    /**
     * The wire's {@code StereotypePtr}:
     * {@code {"profile":…,"profileSourceInformation":…,"sourceInformation":…,"value":…}}.
     *
     * <p>Two spans: {@code profileSourceInformation} covers just the profile FQN,
     * {@code sourceInformation} covers the whole {@code a::P.s1}.
     */
    public record PStereotype(String profile, String value,
                              com.legend.protocol.SourceInfo profileSourceInformation,
                              com.legend.protocol.SourceInfo sourceInformation) {
    }

    /**
     * The wire's {@code TagPtr}. Same four fields as {@link PStereotype} — but note the
     * <b>asymmetry</b>: a tag's {@code sourceInformation} covers only the tag NAME, where a
     * stereotype's covers the whole {@code profile.name}. Verified against legend-engine, not
     * assumed.
     */
    public record PTag(String profile, String value,
                       com.legend.protocol.SourceInfo profileSourceInformation,
                       com.legend.protocol.SourceInfo sourceInformation) {
    }

    /** {@code {"sourceInformation":…,"tag":{…},"value":…}}. */
    public record PTaggedValue(PTag tag, String value, com.legend.protocol.SourceInfo sourceInformation) {
    }

    /**
     * One entry of the wire's {@code superTypes}: {@code {"path":…,"sourceInformation":…,"type":"CLASS"}}.
     *
     * <p>Carries the parsed {@code TypeExpression} rather than a pre-flattened path, for the same
     * reason {@link PProperty} does — the parser stays total and the emitter owns what the wire can
     * express.
     */
    public record PSuperType(com.legend.protocol.TypeExpression type, com.legend.protocol.SourceInfo sourceInformation) {
    }

    /** {@code _type:"packageableType"}. */
    public record PPackageableType(String fullPath, com.legend.protocol.SourceInfo sourceInformation) {
    }

    /** Carries no {@code _type} and no source information. */
    public record PMultiplicity(int lowerBound, @com.legend.Nullable Integer upperBound) {
    }


    /**
     * Splits an FQN into the wire's {@code package} / {@code name} pair.
     *
     * <p><b>This is the only place in legend-lite that splits an FQN</b>, and it exists solely to
     * satisfy the wire. {@code PackageableElement} deliberately has no {@code simpleName()} /
     * {@code packagePath()} because they invite {@code findClass(element.simpleName())} — the
     * simple-name collision documented in {@code docs/NAME_RESOLUTION_BUG.md}. Splitting on the way
     * OUT is safe; nothing reads these back as a lookup key.
     */
    public static String[] splitFqn(String qualifiedName) {
        int i = qualifiedName.lastIndexOf("::");
        return i < 0 ? new String[]{"", qualifiedName}
                     : new String[]{qualifiedName.substring(0, i), qualifiedName.substring(i + 2)};
    }
}
