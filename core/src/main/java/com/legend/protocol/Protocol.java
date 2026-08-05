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
    public sealed interface Element permits PClass, PAssociation, PEnumeration, PFunction,
            PProfile, PSectionIndex {
    }

    /**
     * One {@code functionTestSuite} (legend-testable trailing block). A {@code null} id is
     * the UNNAMED brace form — the wire spells it {@code "default"} and its span covers
     * the whole block; a named suite ({@code name ( ... )}) spans name..close-paren
     * (ProbeWireShapes "fn tests wire", "fn tests named suite").
     */
    public record PTestSuite(@com.legend.Nullable String id,
                             com.legend.protocol.SourceInfo sourceInformation,
                             List<PFunctionTest> tests) {
        public PTestSuite {
            tests = List.copyOf(tests);
        }
    }

    /** One {@code functionTest}: {@code id | call(args) => expected;} — span includes the
     *  semicolon; the single assertion is an {@code equalTo} with id {@code "default"}
     *  spanning the expected value. */
    public record PFunctionTest(String id,
                                com.legend.protocol.SourceInfo sourceInformation,
                                List<PTestParam> parameters,
                                com.legend.protocol.spec.ValueSpecification expected,
                                com.legend.protocol.SourceInfo expectedSpan) {
        public PFunctionTest {
            parameters = List.copyOf(parameters);
        }
    }

    /** One test-call argument, keyed by the SIGNATURE parameter name at its position;
     *  both the parameter and its value span the argument text. */
    public record PTestParam(String name,
                             com.legend.protocol.spec.ValueSpecification value,
                             com.legend.protocol.SourceInfo sourceInformation) {
    }

    /**
     * {@code _type:"function"} — the wire NAME is SIGNATURE-MANGLED
     * ({@code f_Integer_1__String_MANY__Integer_1_}, verified via ProbeWireShapes
     * "function mangling"): simple type names (packages stripped, generic arguments
     * dropped), multiplicities as {@code 1}/{@code MANY}/{@code $0_1$}/{@code $1_MANY$},
     * parameters joined by {@code __}, return appended with a trailing underscore.
     */
    public record PFunction(String pkg, String name,
                            List<String> typeParams, List<String> multParams,
                            List<com.legend.protocol.ParameterDefinition> parameters,
                            com.legend.protocol.TypeExpression returnType,
                            com.legend.protocol.Multiplicity returnMultiplicity,
                            List<com.legend.protocol.spec.ValueSpecification> body,
                            List<com.legend.protocol.ConstraintDefinition> preConstraints,
                            List<PTestSuite> testSuites,
                            List<PStereotype> stereotypes,
                            List<PTaggedValue> taggedValues,
                            com.legend.protocol.SourceInfo sourceInformation) implements Element {
        public PFunction {
            typeParams = List.copyOf(typeParams);
            multParams = List.copyOf(multParams);
            parameters = List.copyOf(parameters);
            body = List.copyOf(body);
            preConstraints = List.copyOf(preConstraints);
            testSuites = List.copyOf(testSuites);
            stereotypes = List.copyOf(stereotypes);
            taggedValues = List.copyOf(taggedValues);
        }

        /** The UNmangled FQN — legend-lite's key. */
        public String qualifiedName() {
            return pkg.isEmpty() ? name : pkg + "::" + name;
        }

        /** The wire name. Throws (loudly) on shapes the mangle rules do not cover yet.
         *  The return segment joins with {@code __} after parameters, {@code _} when there
         *  are none: {@code f_Integer_1__String_MANY__Integer_1_}, {@code h__Boolean_1_}. */
        public String mangledName() {
            StringBuilder m = new StringBuilder(name).append('_');
            for (int i = 0; i < parameters.size(); i++) {
                if (i > 0) {
                    m.append("__");
                }
                com.legend.protocol.ParameterDefinition pd = parameters.get(i);
                m.append(mangleType(pd.type())).append('_').append(mangleMult(pd.multiplicity()));
            }
            m.append(parameters.isEmpty() ? "_" : "__")
                    .append(mangleType(returnType)).append('_')
                    .append(mangleMult(returnMultiplicity)).append('_');
            return m.toString();
        }

        private static String mangleType(com.legend.protocol.TypeExpression t) {
            String full = switch (t) {
                case com.legend.protocol.TypeExpression.NameRef n -> n.name();
                case com.legend.protocol.TypeExpression.Generic g -> g.name();
                default -> throw new UnsupportedOperationException(
                        "no mangle rule for parameter type " + t.getClass().getSimpleName());
            };
            int i = full.lastIndexOf("::");
            return i < 0 ? full : full.substring(i + 2);
        }

        private static String mangleMult(com.legend.protocol.Multiplicity mult) {
            if (!(mult instanceof com.legend.protocol.Multiplicity.Concrete c)) {
                throw new UnsupportedOperationException("no mangle rule for a multiplicity parameter");
            }
            String lo = String.valueOf(c.lowerBound());
            if (c.upperBound() == null) {
                return c.lowerBound() == 0 ? "MANY" : "$" + lo + "_MANY$";
            }
            if (c.upperBound().intValue() == c.lowerBound()) {
                return lo;
            }
            return "$" + lo + "_" + c.upperBound() + "$";
        }
    }

    /** {@code _type:"association"} — ends are ordinary wire properties; qualified properties
     *  ride along exactly as on classes. */
    public record PAssociation(String pkg, String name,
                               List<PProperty> properties,
                               List<com.legend.protocol.DerivedPropertyDefinition> derivedProperties,
                               List<PStereotype> stereotypes,
                               List<PTaggedValue> taggedValues,
                               com.legend.protocol.SourceInfo sourceInformation) implements Element {
        public PAssociation {
            properties = List.copyOf(properties);
            derivedProperties = List.copyOf(derivedProperties);
            stereotypes = List.copyOf(stereotypes);
            taggedValues = List.copyOf(taggedValues);
        }

        public String qualifiedName() {
            return pkg.isEmpty() ? name : pkg + "::" + name;
        }
    }

    /** {@code _type:"profile"} — stereotype/tag declarations as bare name+span entries. */
    public record PProfile(String pkg, String name,
                           List<PProfileEntry> stereotypes,
                           List<PProfileEntry> tags,
                           com.legend.protocol.SourceInfo sourceInformation) implements Element {
        public PProfile {
            stereotypes = List.copyOf(stereotypes);
            tags = List.copyOf(tags);
        }

        public String qualifiedName() {
            return pkg.isEmpty() ? name : pkg + "::" + name;
        }
    }

    /** One declared stereotype or tag: {@code {"sourceInformation":…,"value":…}} — the span
     *  covers the name token only. */
    public record PProfileEntry(String value, com.legend.protocol.SourceInfo sourceInformation) {
    }

    /**
     * {@code _type:"Enumeration"} — CAPITALIZED on the wire, unlike class/profile/association
     * (verified via ProbeWireShapes; an engine quirk, reproduced not questioned).
     */
    public record PEnumeration(String pkg, String name,
                               List<PEnumValue> values,
                               List<PStereotype> stereotypes,
                               List<PTaggedValue> taggedValues,
                               com.legend.protocol.SourceInfo sourceInformation) implements Element {
        public PEnumeration {
            values = List.copyOf(values);
            stereotypes = List.copyOf(stereotypes);
            taggedValues = List.copyOf(taggedValues);
        }

        public String qualifiedName() {
            return pkg.isEmpty() ? name : pkg + "::" + name;
        }
    }

    /** One enum value: annotations plus the entry's span (annotations..name, comma excluded). */
    public record PEnumValue(String value,
                             List<PStereotype> stereotypes,
                             List<PTaggedValue> taggedValues,
                             com.legend.protocol.SourceInfo sourceInformation) {
        public PEnumValue {
            stereotypes = List.copyOf(stereotypes);
            taggedValues = List.copyOf(taggedValues);
        }
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
