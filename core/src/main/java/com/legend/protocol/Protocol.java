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
            PProfile, PSectionIndex, PMeasure, PRuntime, PConnection {
    }

    /** {@code _type:"connection"} — a ###Connection element: envelope
     *  {name, package, connectionValue, sourceInformation}; element and
     *  value share ONE span (ZConnectionProbe). */
    public record PConnection(String pkg, String name, PConnectionValue value,
                              com.legend.protocol.SourceInfo sourceInformation)
            implements Element {
        public String qualifiedName() {
            return pkg.isEmpty() ? name : pkg + "::" + name;
        }
    }

    /**
     * {@code _type:"runtime"} — {@code Runtime pkg::R { mappings: [...];
     * connections: [...] }} and {@code SingleConnectionRuntime} (whose
     * runtimeValue discriminates {@code localEngineRuntime}). Probed
     * byte-shapes: ZRuntimeProbe — fields alphabetical, element and
     * runtimeValue share ONE span, pointer types spelled {@code MAPPING} /
     * {@code STORE}, connections keep their IDs and source ORDER.
     */
    public record PRuntime(String pkg, String name, boolean single,
                           List<PPointer> mappings,
                           List<PStoreConnections> connections,
                           List<PConnectionStores> connectionStores,
                           com.legend.protocol.SourceInfo sourceInformation)
            implements Element {
        public String qualifiedName() {
            return pkg.isEmpty() ? name : pkg + "::" + name;
        }
    }

    /** A typed packageable-element pointer ({@code path}/{@code type}/span). */
    public record PPointer(String type, String path,
                           com.legend.protocol.SourceInfo sourceInformation) {
    }

    /** One {@code store: [ id: conn, ... ]} group — order preserved. */
    public record PStoreConnections(PPointer store,
                                    List<PIdentifiedConnection> storeConnections,
                                    com.legend.protocol.SourceInfo sourceInformation) {
    }

    /** {@code id: <connection>} — pointer or embedded connection value. */
    public record PIdentifiedConnection(String id, PConnectionValue connection,
                                        com.legend.protocol.SourceInfo sourceInformation) {
    }

    /** {@code connectionStores: [conn: [stores...]]} group (also
     *  SingleConnectionRuntime's {@code connection:}, storePointers empty).
     *  Store pointers serialize path+span WITHOUT a type field (probe). */
    public record PConnectionStores(PConnectionValue connectionPointer,
                                    List<PStorePointer> storePointers,
                                    com.legend.protocol.SourceInfo sourceInformation) {
    }

    /** A typeless store pointer inside connectionStores. */
    public record PStorePointer(String path,
                                com.legend.protocol.SourceInfo sourceInformation) {
    }

    /** A connection VALUE: a pointer, or a concrete connection (standalone
     *  ###Connection element or embedded runtime island). */
    public sealed interface PConnectionValue
            permits PConnectionPointer, PJsonModelConnection,
            PXmlModelConnection, PModelChainConnection,
            PRelationalDatabaseConnection {
    }

    /** {@code _type:"connectionPointer"}. */
    public record PConnectionPointer(String connection,
                                     com.legend.protocol.SourceInfo sourceInformation)
            implements PConnectionValue {
    }

    /** {@code _type:"JsonModelConnection"} — class + classSourceInformation
     *  + url; {@code element} is {@code "ModelStore"} for STANDALONE
     *  connection elements and null when embedded in a runtime (probes
     *  embedded-json vs json). */
    public record PJsonModelConnection(String className,
                                       com.legend.protocol.SourceInfo classSourceInformation,
                                       @com.legend.Nullable String element,
                                       String url,
                                       com.legend.protocol.SourceInfo sourceInformation)
            implements PConnectionValue {
    }

    /** {@code _type:"XmlModelConnection"} — same shape as Json (probe xml). */
    public record PXmlModelConnection(String className,
                                      com.legend.protocol.SourceInfo classSourceInformation,
                                      @com.legend.Nullable String element,
                                      String url,
                                      com.legend.protocol.SourceInfo sourceInformation)
            implements PConnectionValue {
    }

    /** {@code _type:"ModelChainConnection"} — mappings as STRINGS + one
     *  mappingsSourceInformation (probe model-chain). */
    public record PModelChainConnection(@com.legend.Nullable String element,
                                        List<String> mappings,
                                        com.legend.protocol.SourceInfo mappingsSourceInformation,
                                        com.legend.protocol.SourceInfo sourceInformation)
            implements PConnectionValue {
    }

    /** {@code _type:"RelationalDatabaseConnection"} — databaseType AND type
     *  both spelled; spec/auth spans run keyword..close (probes
     *  relational-*). Only corpus-censused spec/auth shapes exist; the
     *  parser WALLS the rest loudly. */
    public record PRelationalDatabaseConnection(
            PAuthStrategy authenticationStrategy,
            String databaseType,
            PDatasourceSpec datasourceSpecification,
            String element,
            com.legend.protocol.SourceInfo elementSourceInformation,
            com.legend.protocol.SourceInfo sourceInformation)
            implements PConnectionValue {
    }

    /** Datasource specifications the corpus actually uses. */
    public sealed interface PDatasourceSpec permits PH2Local, PStaticSpec {
    }

    /** {@code _type:"h2Local"}; testDataSetupSqls omitted when absent. */
    public record PH2Local(@com.legend.Nullable List<String> testDataSetupSqls,
                           com.legend.protocol.SourceInfo sourceInformation)
            implements PDatasourceSpec {
    }

    /** {@code _type:"static"} — name: spells databaseName on the wire. */
    public record PStaticSpec(String databaseName, String host, long port,
                              com.legend.protocol.SourceInfo sourceInformation)
            implements PDatasourceSpec {
    }

    /** Authentication strategies the corpus actually uses. */
    public sealed interface PAuthStrategy
            permits PH2Default, PTestAuth, PDelegatedKerberos {
    }

    /** {@code _type:"h2Default"}. */
    public record PH2Default(com.legend.protocol.SourceInfo sourceInformation)
            implements PAuthStrategy {
    }

    /** {@code _type:"test"}. */
    public record PTestAuth(com.legend.protocol.SourceInfo sourceInformation)
            implements PAuthStrategy {
    }

    /** {@code _type:"delegatedKerberos"}; serverPrincipal optional. */
    public record PDelegatedKerberos(@com.legend.Nullable String serverPrincipal,
                                     com.legend.protocol.SourceInfo sourceInformation)
            implements PAuthStrategy {
    }

    /**
     * {@code _type:"measure"} — {@code Measure pkg::M { *Canon: x -> $x; Other: x -> ... }}.
     * The {@code *}-marked unit is canonical; each unit's conversion is an arrow-form
     * lambda whose wrapper carries NO span (probe: vanilla engine Measure JSON).
     */
    public record PMeasure(String pkg, String name,
                           @com.legend.Nullable PUnit canonicalUnit,
                           List<PUnit> nonCanonicalUnits,
                           com.legend.protocol.SourceInfo sourceInformation)
            implements Element {
        public PMeasure {
            nonCanonicalUnits = List.copyOf(nonCanonicalUnits);
        }

        /** The UNmangled FQN — legend-lite's key. */
        public String qualifiedName() {
            return pkg.isEmpty() ? name : pkg + "::" + name;
        }
    }

    /** One measure unit: name, owning measure FQN, optional conversion (param + body —
     *  {@code *Unit;} has none), span = name..the terminating ';' ({@code *} excluded). */
    public record PUnit(String name, String measureFqn,
                        @com.legend.Nullable String paramName,
                        @com.legend.Nullable com.legend.protocol.spec.ValueSpecification body,
                        com.legend.protocol.SourceInfo sourceInformation) {
    }

    /**
     * One {@code functionTestSuite} (legend-testable trailing block). A {@code null} id is
     * the UNNAMED brace form — the wire spells it {@code "default"} and its span covers
     * the whole block; a named suite ({@code name ( ... )}) spans name..close-paren
     * (ProbeWireShapes "fn tests wire", "fn tests named suite").
     */
    public record PTestSuite(@com.legend.Nullable String id,
                             com.legend.protocol.SourceInfo sourceInformation,
                             List<PTestData> testData,
                             List<PFunctionTest> tests) {
        public PTestSuite {
            testData = List.copyOf(testData);
            tests = List.copyOf(tests);
        }

        /** No-data convenience constructor. */
        public PTestSuite(@com.legend.Nullable String id,
                          com.legend.protocol.SourceInfo sourceInformation,
                          List<PFunctionTest> tests) {
            this(id, sourceInformation, List.of(), tests);
        }
    }

    /** One {@code store: <payload>;} entry in a test block (probe "pf test store data"
     *  and friends). */
    public record PTestData(String storePath,
                            com.legend.protocol.SourceInfo storeSpan,
                            PTestPayload data,
                            com.legend.protocol.SourceInfo sourceInformation) {
    }

    /** A test-data payload / format-prefixed assertion payload. */
    public sealed interface PTestPayload {
        /** {@code (JSON) '...'} — an externalFormat blob. */
        record ExternalFormat(String contentType, String data,
                              com.legend.protocol.SourceInfo sourceInformation)
                implements PTestPayload {
        }

        /** {@code some::DataElement} — a reference, {@code type} fixed to {@code DATA}. */
        record Reference(String path, com.legend.protocol.SourceInfo sourceInformation)
                implements PTestPayload {
        }

        /** {@code Relation #{ path: cols rows }#} — a relationAccessor. */
        record RelationElements(List<RelationElement> elements,
                                com.legend.protocol.SourceInfo sourceInformation)
                implements PTestPayload {
            public RelationElements {
                elements = List.copyOf(elements);
            }
        }

        /** One relation block: dotted path, column names, string-valued rows. */
        record RelationElement(List<String> columns, List<String> paths,
                               List<List<String>> rows,
                               com.legend.protocol.SourceInfo sourceInformation) {
            public RelationElement {
                columns = List.copyOf(columns);
                paths = List.copyOf(paths);
                rows = rows.stream().map(List::copyOf).toList();
            }
        }

        /** {@code ModelStore #{ FQN: ExternalFormat #{...}# }#} — modelStore data. */
        record ModelStoreData(List<ModelEmbedded> modelData,
                              com.legend.protocol.SourceInfo sourceInformation)
                implements PTestPayload {
            public ModelStoreData {
                modelData = List.copyOf(modelData);
            }
        }

        /** One {@code FQN: ExternalFormat #{ contentType: '...'; data: '...'; }#}. */
        record ModelEmbedded(String model, ExternalFormat data,
                             com.legend.protocol.SourceInfo sourceInformation) {
        }

        /** {@code Relational #{ schema.table: 'csv'; }#} — relationalCSVData. */
        record RelationalCsv(List<CsvTable> tables,
                             com.legend.protocol.SourceInfo sourceInformation)
                implements PTestPayload {
            public RelationalCsv {
                tables = List.copyOf(tables);
            }
        }

        /** One CSV table: schema, table, concatenated values; span =
         *  {@code schema.table:}..values end (the ';' excluded). */
        record CsvTable(String schema, String table, String values,
                        com.legend.protocol.SourceInfo sourceInformation) {
        }
    }

    /** One {@code functionTest}: {@code id | call(args) => expected;} — span includes the
     *  semicolon. */
    public record PFunctionTest(String id,
                                com.legend.protocol.SourceInfo sourceInformation,
                                List<PTestParam> parameters,
                                PAssertion assertion) {
        public PFunctionTest {
            parameters = List.copyOf(parameters);
        }
    }

    /** The test's single assertion, id always {@code "default"} on the wire. */
    public sealed interface PAssertion {
        /** {@code => expr} — equalTo spanning the expected value. */
        record EqualTo(com.legend.protocol.spec.ValueSpecification expected,
                       com.legend.protocol.SourceInfo span) implements PAssertion {
        }

        /** {@code => (JSON) '...'} — equalToJson with an externalFormat expected. */
        record EqualToJson(PTestPayload.ExternalFormat expected,
                           com.legend.protocol.SourceInfo span) implements PAssertion {
        }

        /** {@code => Relation #{...}#} — equalToRelation; the expected object is the bare
         *  columns/paths/rows shape (no _type), spanning the island CONTENT; the
         *  assertion spans {@code Relation}..{@code }#}. */
        record EqualToRelation(PTestPayload.RelationElement expected,
                               com.legend.protocol.SourceInfo span) implements PAssertion {
        }
    }

    /** One test-call argument, keyed by the SIGNATURE parameter name at its position —
     *  {@code null} when the call passes more arguments than the signature declares
     *  (probe "pf extra test arg": the name key is simply absent). */
    public record PTestParam(@com.legend.Nullable String name,
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
                            @com.legend.Nullable PDefaultValue defaultValue,
                            @com.legend.Nullable String aggregation) {
        public PProperty {
            stereotypes = List.copyOf(stereotypes);
            taggedValues = List.copyOf(taggedValues);
        }

        /** The common no-aggregation-kind form. */
        public PProperty(String name, com.legend.protocol.TypeExpression type,
                         com.legend.protocol.Multiplicity multiplicity,
                         List<PStereotype> stereotypes, List<PTaggedValue> taggedValues,
                         com.legend.protocol.SourceInfo sourceInformation,
                         @com.legend.Nullable PDefaultValue defaultValue) {
            this(name, type, multiplicity, stereotypes, taggedValues,
                    sourceInformation, defaultValue, null);
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
        String pkg = i < 0 ? "" : qualifiedName.substring(0, i);
        String name = i < 0 ? qualifiedName : qualifiedName.substring(i + 2);
        return new String[]{unquoteSegments(pkg), unquoteSegments(name)};
    }

    /** Public form for REFERENCE positions that also unquote (stereotype/tag
     *  profiles — corpus DIFF: {@code <<'a profile'.st>>} emits {@code a profile}). */
    public static String unquotePath(String path) {
        return unquoteSegments(path);
    }

    /** DECLARATION names unquote their quoted segments on the wire
     *  ({@code test::'p a c k'::A} &rarr; package {@code test::p a c k}); REFERENCES
     *  keep the quotes — the parser hands the raw spelling to both. */
    private static String unquoteSegments(String path) {
        if (path.indexOf('\'') < 0) {
            return path;
        }
        StringBuilder out = new StringBuilder();
        int start = 0;
        while (start <= path.length()) {
            int sep = path.indexOf("::", start);
            int end = sep < 0 ? path.length() : sep;
            if (start > 0) {
                out.append("::");
            }
            String s = path.substring(start, end);
            out.append(s.length() >= 2 && s.startsWith("'") && s.endsWith("'")
                    ? s.substring(1, s.length() - 1) : s);
            if (sep < 0) {
                break;
            }
            start = sep + 2;
        }
        return out.toString();
    }
}
