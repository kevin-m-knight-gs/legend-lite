package com.legend.protocol;

import com.legend.protocol.Protocol.Element;
import com.legend.protocol.Protocol.PClass;
import com.legend.protocol.Protocol.PGenericType;
import com.legend.protocol.Protocol.PMultiplicity;
import com.legend.protocol.Protocol.PPackageableType;
import com.legend.protocol.Protocol.PProperty;
import com.legend.protocol.Protocol.PSection;
import com.legend.protocol.Protocol.PSectionIndex;
import com.legend.protocol.Protocol.PureModelContextData;
import com.legend.protocol.SourceInfo;

import java.util.List;

/**
 * The ONLY upstream-shaped code in legend-lite.
 *
 * <p>Emits {@link Protocol} records as the exact bytes legend-engine's
 * {@code ObjectMapperFactory.getNewStandardObjectMapperWithPureProtocolExtensionSupports()}
 * produces. Every rule below was verified against that mapper's real output, not inferred:
 *
 * <ul>
 *   <li>{@code _type} first, then fields <b>alphabetically</b>;</li>
 *   <li>nulls <b>omitted</b> ({@code NON_NULL} is global upstream);</li>
 *   <li>empty collections emitted as {@code []} — they are non-null, so {@code NON_NULL} keeps them;</li>
 *   <li>arrays in <b>source order</b>; the {@code SectionIndex} appended <b>last</b>;</li>
 *   <li>{@code genericType} and {@code multiplicity} carry <b>no</b> {@code _type}.</li>
 * </ul>
 *
 * <p><b>Why hand-rolled rather than Jackson:</b> matching another Jackson would mean reproducing
 * {@code SORT_PROPERTIES_ALPHABETICALLY}, 2.10's creator-properties-first ordering quirk,
 * {@code NON_NULL}, four {@code NON_EMPTY} overrides and ~20 bespoke serializers — and staying
 * pinned to 2.10 forever. Writing the bytes directly is both simpler and exactly auditable.
 *
 * <p><b>The dispatch is a switch expression with no {@code default} arm.</b> Adding a
 * {@link Element} variant without an emit rule is a compile error.
 */
public final class ProtocolEmitter {

    private ProtocolEmitter() {
    }

    public static String emit(PureModelContextData pmcd) {
        StringBuilder b = new StringBuilder(1024);
        b.append("{\"_type\":\"data\",\"elements\":[");
        List<Element> els = pmcd.elements();
        for (int i = 0; i < els.size(); i++) {
            if (i > 0) {
                b.append(',');
            }
            element(b, els.get(i));
        }
        return b.append("]}").toString();
    }

    /**
     * Emit ONE element's JSON — the granularity the equivalence harness compares at, so a file
     * containing constructs we cannot yet emit still yields a verdict for the ones we can.
     */
    public static String emitElement(Element e) {
        StringBuilder b = new StringBuilder(512);
        element(b, e);
        return b.toString();
    }

    /** Exhaustive over {@link Element}. No {@code default} arm — a new variant must land here. */
    private static void element(StringBuilder b, Element e) {
        switch (e) {
            case PClass c -> pclass(b, c);
            case Protocol.PAssociation a -> association(b, a);
            case Protocol.PFunction fn -> function(b, fn);
            case Protocol.PEnumeration en -> enumeration(b, en);
            case Protocol.PProfile pr -> profile(b, pr);
            case PSectionIndex s -> sectionIndex(b, s);
        }
    }

    /**
     * {@code _type:"function"} — the wire name is SIGNATURE-MANGLED
     * ({@link Protocol.PFunction#mangledName()}); parameters are typed vars; the body is the
     * bare statement list. Type/multiplicity parameters and constraint blocks wall until
     * their wire shapes are probed.
     */
    private static void function(StringBuilder b, Protocol.PFunction f) {
        require(f.typeParams().isEmpty() && f.multParams().isEmpty(),
                "function type/multiplicity parameters", f.qualifiedName());
        require(f.preConstraints().isEmpty(), "function constraints", f.qualifiedName());
        require(!f.hasTests(), "function test suites (wire shape unprobed)", f.qualifiedName());
        b.append("{\"_type\":\"function\",\"body\":[");
        for (int i = 0; i < f.body().size(); i++) {
            if (i > 0) {
                b.append(',');
            }
            valueSpec(b, f.body().get(i));
        }
        b.append("],\"name\":");
        str(b, f.mangledName());
        b.append(",\"package\":");
        str(b, f.pkg());
        b.append(",\"parameters\":[");
        for (int i = 0; i < f.parameters().size(); i++) {
            if (i > 0) {
                b.append(',');
            }
            com.legend.protocol.ParameterDefinition p = f.parameters().get(i);
            b.append("{\"_type\":\"var\",\"genericType\":");
            genericType(b, p.type());
            b.append(",\"multiplicity\":");
            multiplicity(b, p.multiplicity());
            b.append(",\"name\":");
            str(b, p.name());
            b.append(",\"sourceInformation\":");
            srcInfo(b, requirePos(p.pos(), "function parameter " + p.name()));
            b.append('}');
        }
        b.append("],\"postConstraints\":[],\"preConstraints\":[],\"returnGenericType\":");
        genericType(b, f.returnType());
        b.append(",\"returnMultiplicity\":");
        multiplicity(b, f.returnMultiplicity());
        b.append(",\"sourceInformation\":");
        srcInfo(b, f.sourceInformation());
        b.append(",\"stereotypes\":");
        stereotypes(b, f.stereotypes());
        b.append(",\"taggedValues\":");
        taggedValues(b, f.taggedValues());
        b.append(",\"tests\":[]}");
    }

    /** {@code _type:"association"} — ends emit as ordinary wire properties; qualified
     *  properties exactly as on classes (ProbeWireShapes "association"). */
    private static void association(StringBuilder b, Protocol.PAssociation a) {
        b.append("{\"_type\":\"association\",\"name\":");
        str(b, a.name());
        b.append(",\"originalMilestonedProperties\":[],\"package\":");
        str(b, a.pkg());
        b.append(",\"properties\":[");
        for (int i = 0; i < a.properties().size(); i++) {
            if (i > 0) {
                b.append(',');
            }
            property(b, a.properties().get(i));
        }
        b.append("],\"qualifiedProperties\":[");
        for (int i = 0; i < a.derivedProperties().size(); i++) {
            if (i > 0) {
                b.append(',');
            }
            qualifiedProperty(b, a.derivedProperties().get(i));
        }
        b.append("],\"sourceInformation\":");
        srcInfo(b, a.sourceInformation());
        b.append(",\"stereotypes\":");
        stereotypes(b, a.stereotypes());
        b.append(",\"taggedValues\":");
        taggedValues(b, a.taggedValues());
        b.append('}');
    }

    /** {@code _type:"profile"} — declared stereotypes/tags as bare {@code {sourceInformation,
     *  value}} entries spanning the name token (ProbeWireShapes "profile"). */
    private static void profile(StringBuilder b, Protocol.PProfile p) {
        b.append("{\"_type\":\"profile\",\"name\":");
        str(b, p.name());
        b.append(",\"package\":");
        str(b, p.pkg());
        b.append(",\"sourceInformation\":");
        srcInfo(b, p.sourceInformation());
        b.append(",\"stereotypes\":");
        profileEntries(b, p.stereotypes());
        b.append(",\"tags\":");
        profileEntries(b, p.tags());
        b.append('}');
    }

    private static void profileEntries(StringBuilder b, List<Protocol.PProfileEntry> es) {
        b.append('[');
        for (int i = 0; i < es.size(); i++) {
            if (i > 0) {
                b.append(',');
            }
            b.append("{\"sourceInformation\":");
            srcInfo(b, es.get(i).sourceInformation());
            b.append(",\"value\":");
            str(b, es.get(i).value());
            b.append('}');
        }
        b.append(']');
    }

    /**
     * {@code _type:"Enumeration"} — CAPITALIZED, an engine quirk (class/profile/association
     * are lowercase; verified via ProbeWireShapes). Fields alphabetical; each value entry
     * carries its own annotations and a span covering annotations..value name.
     */
    private static void enumeration(StringBuilder b, Protocol.PEnumeration e) {
        b.append("{\"_type\":\"Enumeration\",\"name\":");
        str(b, e.name());
        b.append(",\"package\":");
        str(b, e.pkg());
        b.append(",\"sourceInformation\":");
        srcInfo(b, e.sourceInformation());
        b.append(",\"stereotypes\":");
        stereotypes(b, e.stereotypes());
        b.append(",\"taggedValues\":");
        taggedValues(b, e.taggedValues());
        b.append(",\"values\":[");
        for (int i = 0; i < e.values().size(); i++) {
            if (i > 0) {
                b.append(',');
            }
            Protocol.PEnumValue v = e.values().get(i);
            b.append("{\"sourceInformation\":");
            srcInfo(b, v.sourceInformation());
            b.append(",\"stereotypes\":");
            stereotypes(b, v.stereotypes());
            b.append(",\"taggedValues\":");
            taggedValues(b, v.taggedValues());
            b.append(",\"value\":");
            str(b, v.value());
            b.append('}');
        }
        b.append("]}");
    }

    private static void pclass(StringBuilder b, PClass c) {
        // Not yet emitted. Loud rather than silently dropped — AGENTS.md invariant 4.
        require(c.typeParams().isEmpty(), "class type parameters", c.qualifiedName());
        b.append("{\"_type\":\"class\",\"constraints\":[");
        for (int i = 0; i < c.constraints().size(); i++) {
            if (i > 0) {
                b.append(',');
            }
            constraint(b, c.constraints().get(i));
        }
        b.append("],\"name\":");
        str(b, c.name());
        b.append(",\"originalMilestonedProperties\":[],\"package\":");
        str(b, c.pkg());
        b.append(",\"properties\":[");
        List<PProperty> ps = c.properties();
        for (int i = 0; i < ps.size(); i++) {
            if (i > 0) {
                b.append(',');
            }
            property(b, ps.get(i));
        }
        b.append("],\"qualifiedProperties\":[");
        for (int i = 0; i < c.derivedProperties().size(); i++) {
            if (i > 0) {
                b.append(',');
            }
            qualifiedProperty(b, c.derivedProperties().get(i));
        }
        b.append("],\"sourceInformation\":");
        srcInfo(b, c.sourceInformation());
        b.append(",\"stereotypes\":");
        stereotypes(b, c.stereotypes());
        b.append(",\"superTypes\":[");
        List<Protocol.PSuperType> sts = c.superTypes();
        for (int i = 0; i < sts.size(); i++) {
            if (i > 0) {
                b.append(',');
            }
            superType(b, sts.get(i));
        }
        b.append("],\"taggedValues\":");
        taggedValues(b, c.taggedValues());
        b.append('}');
    }

    /**
     * {@code {"path":…,"sourceInformation":…,"type":"CLASS"}} — fields alphabetical, no {@code _type}.
     *
     * <p>Verified via {@code ProbeWireShapes}: for a GENERIC supertype
     * ({@code extends c::D<String>}) the engine emits only the base path — the type
     * arguments are dropped from the wire — while the span still covers the whole
     * expression. Deliberate parity, not a shortcut.
     */
    private static void superType(StringBuilder b, Protocol.PSuperType st) {
        String path = switch (st.type()) {
            case com.legend.protocol.TypeExpression.NameRef n -> n.name();
            case com.legend.protocol.TypeExpression.Generic g -> g.name();
            default -> throw new UnsupportedOperationException(
                    "ProtocolEmitter has no rule for a supertype of shape "
                            + st.type().getClass().getSimpleName() + " — add the emit rule.");
        };
        b.append("{\"path\":");
        str(b, path);
        b.append(",\"sourceInformation\":");
        srcInfo(b, st.sourceInformation());
        b.append(",\"type\":\"CLASS\"}");
    }

    private static void property(StringBuilder b, PProperty p) {
        b.append('{');
        if (p.defaultValue() != null) {
            // Alphabetically first among the property's fields. Outer span covers the whole
            // default expression; the value node carries its own (identical for literals).
            b.append("\"defaultValue\":{\"sourceInformation\":");
            srcInfo(b, p.defaultValue().sourceInformation());
            b.append(",\"value\":");
            if (p.defaultValue().value() == null) {
                throw new UnsupportedOperationException(
                        "ProtocolEmitter has no rule for this defaultValue expression (at "
                                + p.name() + ") — the parser accepted it but built no value-spec;"
                                + " extend SpecParser coverage, do not drop it.");
            }
            valueSpec(b, p.defaultValue().value());
            b.append("},");
        }
        b.append("\"genericType\":");
        genericType(b, p.type());
        b.append(",\"multiplicity\":");
        multiplicity(b, p.multiplicity());
        b.append(",\"name\":");
        str(b, p.name());
        b.append(",\"sourceInformation\":");
        srcInfo(b, p.sourceInformation());
        b.append(",\"stereotypes\":");
        stereotypes(b, p.stereotypes());
        b.append(",\"taggedValues\":");
        taggedValues(b, p.taggedValues());
        b.append('}');
    }

    /** {@code [{"profile":…,"profileSourceInformation":…,"sourceInformation":…,"value":…}]} */
    private static void stereotypes(StringBuilder b, List<Protocol.PStereotype> ss) {
        b.append('[');
        for (int i = 0; i < ss.size(); i++) {
            if (i > 0) {
                b.append(',');
            }
            Protocol.PStereotype st = ss.get(i);
            b.append("{\"profile\":");
            str(b, st.profile());
            b.append(",\"profileSourceInformation\":");
            srcInfo(b, st.profileSourceInformation());
            b.append(",\"sourceInformation\":");
            srcInfo(b, st.sourceInformation());
            b.append(",\"value\":");
            str(b, st.value());
            b.append('}');
        }
        b.append(']');
    }

    /** {@code [{"sourceInformation":…,"tag":{…},"value":…}]} */
    private static void taggedValues(StringBuilder b, List<Protocol.PTaggedValue> ts) {
        b.append('[');
        for (int i = 0; i < ts.size(); i++) {
            if (i > 0) {
                b.append(',');
            }
            Protocol.PTaggedValue tv = ts.get(i);
            b.append("{\"sourceInformation\":");
            srcInfo(b, tv.sourceInformation());
            b.append(",\"tag\":{\"profile\":");
            str(b, tv.tag().profile());
            b.append(",\"profileSourceInformation\":");
            srcInfo(b, tv.tag().profileSourceInformation());
            b.append(",\"sourceInformation\":");
            srcInfo(b, tv.tag().sourceInformation());
            b.append(",\"value\":");
            str(b, tv.tag().value());
            b.append("},\"value\":");
            str(b, tv.value());
            b.append('}');
        }
        b.append(']');
    }

    /**
     * The wire's {@code genericType}. Named types and generic applications are expressible;
     * spans come from the type NODE itself ({@code parseType} threads them), so nesting is
     * uniform — an argument is just another {@code genericType}, recursively.
     *
     * <p>Verified via {@code ProbeWireShapes}: the {@code rawType} span of a generic covers
     * the WHOLE application including the closing {@code >}; each argument carries its own.
     */
    private static void genericType(StringBuilder b, com.legend.protocol.TypeExpression t) {
        switch (t) {
            case com.legend.protocol.TypeExpression.NameRef n ->
                    genericTypeOf(b, n.name(), java.util.List.of(), java.util.List.of(), n.pos());
            case com.legend.protocol.TypeExpression.Generic g ->
                    genericTypeOf(b, g.name(), g.arguments(), g.multiplicityArguments(), g.pos());
            default -> throw new UnsupportedOperationException(
                    "ProtocolEmitter has no rule for type expression "
                            + t.getClass().getSimpleName() + " — add the emit rule, do not drop it.");
        }
    }

    private static void genericTypeOf(StringBuilder b, String path,
                                      List<com.legend.protocol.TypeExpression> args,
                                      List<String> multArgs,
                                      com.legend.protocol.@com.legend.Nullable SourceInfo pos) {
        if (pos == null) {
            throw new UnsupportedOperationException(
                    "ProtocolEmitter needs a source position for type " + path
                            + " and the parser did not thread one — fix the parse site, do not default it.");
        }
        b.append("{\"multiplicityArguments\":[");
        for (int i = 0; i < multArgs.size(); i++) {
            if (i > 0) {
                b.append(',');
            }
            // Res<T|1>: the argument arrives as raw text; concrete spellings emit as
            // multiplicities, parameter NAMES wall (ProbeWireShapes "burn zoo" R).
            multiplicity(b, parseMultArg(multArgs.get(i), path));
        }
        b.append("],\"rawType\":{\"_type\":\"packageableType\",\"fullPath\":");
        str(b, path);
        b.append(",\"sourceInformation\":");
        srcInfo(b, pos);
        b.append("},\"typeArguments\":[");
        for (int i = 0; i < args.size(); i++) {
            if (i > 0) {
                b.append(',');
            }
            genericType(b, args.get(i));
        }
        b.append("],\"typeVariableValues\":[]}");
    }

    /**
     * One class constraint:
     * {@code {"functionDefinition":…,("messageFunction":…,)?"name":…,"sourceInformation":…}}.
     *
     * <p>Verified via {@code ProbeWireShapes}: the engine wraps the predicate in a lambda whose
     * synthesised {@code $this} parameter carries multiplicity {@code [1..1]} and <b>no</b>
     * span; the {@code ~message} expression gets the same wrapping under {@code messageFunction};
     * an absent {@code ~enforcementLevel} simply vanishes ({@code NON_NULL}); the constraint's
     * span covers the whole entry ({@code name: expr} or {@code name ( … )}).
     */
    private static void constraint(StringBuilder b, com.legend.protocol.ConstraintDefinition c) {
        if (!(c.realization() instanceof com.legend.protocol.Realization.Inline inl)) {
            throw new UnsupportedOperationException(
                    "ProtocolEmitter has no rule for a function-ref constraint (at " + c.name()
                            + ") — add the emit rule, do not drop it.");
        }
        if (c.pos() == null) {
            throw new UnsupportedOperationException(
                    "ProtocolEmitter needs a source position for constraint " + c.name()
                            + " and the parser did not thread one — fix the parse site.");
        }
        b.append('{');
        if (c.enforcementLevel() != null) {
            // Alphabetically FIRST among the constraint's fields (ProbeWireShapes cLevel).
            b.append("\"enforcementLevel\":");
            str(b, c.enforcementLevel());
            b.append(',');
        }
        if (c.externalId() != null) {
            b.append("\"externalId\":");
            str(b, c.externalId());
            b.append(',');
        }
        b.append("\"functionDefinition\":");
        thisLambda(b, inl.body());
        if (c.message() != null) {
            b.append(",\"messageFunction\":");
            thisLambda(b, List.of(c.message()));
        }
        b.append(",\"name\":");
        str(b, c.name());
        if (c.owner() != null) {
            // Alphabetically between name and sourceInformation (ProbeWireShapes "owner");
            // a single identifier — engine rejects a bracketed list outright.
            b.append(",\"owner\":");
            str(b, c.owner());
        }
        b.append(",\"sourceInformation\":");
        srcInfo(b, c.pos());
        b.append('}');
    }

    /**
     * One qualified (derived) property:
     * {@code {"body":[…],"name":…,"parameters":[…],"returnGenericType":…,
     * "returnMultiplicity":…,"sourceInformation":…,"stereotypes":[],"taggedValues":[]}}.
     *
     * <p>Verified via {@code ProbeWireShapes} "qualified property": the body is the bare
     * statement list — NO lambda wrapper and NO synthesised {@code $this} parameter (unlike
     * constraints); parameters are the declared ones only, in the typed-var shape; the span
     * covers the whole declaration. Engine consumes-and-drops annotations on qualified
     * properties, so empty {@code stereotypes}/{@code taggedValues} are engine-parity.
     */
    private static void qualifiedProperty(StringBuilder b,
                                          com.legend.protocol.DerivedPropertyDefinition d) {
        if (!(d.realization() instanceof com.legend.protocol.Realization.Inline inl)) {
            throw new UnsupportedOperationException(
                    "ProtocolEmitter has no rule for a function-ref qualified property (at "
                            + d.name() + ") — add the emit rule, do not drop it.");
        }
        b.append("{\"body\":[");
        for (int i = 0; i < inl.body().size(); i++) {
            if (i > 0) {
                b.append(',');
            }
            valueSpec(b, inl.body().get(i));
        }
        b.append("],\"name\":");
        str(b, d.name());
        b.append(",\"parameters\":[");
        for (int i = 0; i < d.parameters().size(); i++) {
            if (i > 0) {
                b.append(',');
            }
            com.legend.protocol.ParameterDefinition p = d.parameters().get(i);
            b.append("{\"_type\":\"var\",\"genericType\":");
            genericType(b, p.type());
            b.append(",\"multiplicity\":");
            multiplicity(b, p.multiplicity());
            b.append(",\"name\":");
            str(b, p.name());
            b.append(",\"sourceInformation\":");
            srcInfo(b, requirePos(p.pos(), "qualified-property parameter " + p.name()));
            b.append('}');
        }
        b.append("],\"returnGenericType\":");
        genericType(b, d.type());
        b.append(",\"returnMultiplicity\":");
        multiplicity(b, d.multiplicity());
        b.append(",\"sourceInformation\":");
        srcInfo(b, requirePos(d.pos(), "qualified property " + d.name()));
        b.append(",\"stereotypes\":");
        stereotypes(b, d.stereotypes());
        b.append(",\"taggedValues\":");
        taggedValues(b, d.taggedValues());
        b.append('}');
    }

    /** The engine's constraint lambda: body statements plus the synthesised {@code $this}
     *  parameter — multiplicity {@code [1..1]}, no span. The lambda node itself carries no
     *  span either. */
    private static void thisLambda(StringBuilder b,
                                   List<com.legend.protocol.spec.ValueSpecification> body) {
        b.append("{\"_type\":\"lambda\",\"body\":[");
        for (int i = 0; i < body.size(); i++) {
            if (i > 0) {
                b.append(',');
            }
            valueSpec(b, body.get(i));
        }
        b.append("],\"parameters\":[{\"_type\":\"var\",\"multiplicity\":{\"lowerBound\":1,"
                + "\"upperBound\":1},\"name\":\"this\"}]}");
    }

    /**
     * The wire's value-specification encoding — the seed of the full emitter
     * (PARSER_DROP_IN_STATUS.md §4.1 item 2). Literals only so far; every other node
     * walls by name. The {@code default} arm THROWS — it exists because coverage is
     * deliberately partial, never to pass silently.
     *
     * <p>Shapes verified via {@code ProbeWireShapes}: {@code _type} first, then
     * {@code sourceInformation}, then {@code value}; a string literal's span includes
     * its quotes.
     */
    private static void valueSpec(StringBuilder b, com.legend.protocol.spec.ValueSpecification v) {
        switch (v) {
            case com.legend.protocol.spec.CBoolean c ->
                    literal(b, "boolean", String.valueOf(c.value()), c.pos());
            case com.legend.protocol.spec.CInteger c ->
                    literal(b, "integer", c.value().toString(), c.pos());
            case com.legend.protocol.spec.CString c -> {
                StringBuilder quoted = new StringBuilder();
                str(quoted, c.value());
                literal(b, "string", quoted.toString(), c.pos());
            }
            case com.legend.protocol.spec.Variable var -> {
                require(var.type() == null && var.multiplicity() == null,
                        "typed variable reference", var.name());
                b.append("{\"_type\":\"var\",\"name\":");
                str(b, var.name());
                b.append(",\"sourceInformation\":");
                srcInfo(b, requirePos(var.pos(), "var " + var.name()));
                b.append('}');
            }
            case com.legend.protocol.spec.AppliedProperty p -> {
                b.append("{\"_type\":\"property\",\"parameters\":[");
                valueSpec(b, p.receiver());
                b.append("],\"property\":");
                str(b, p.property());
                b.append(",\"sourceInformation\":");
                srcInfo(b, requirePos(p.pos(), "property " + p.property()));
                b.append('}');
            }
            case com.legend.protocol.spec.PureCollection c -> collection(b, c.values(),
                    requirePos(c.pos(), "collection literal"));
            case com.legend.protocol.spec.AppliedFunction f -> appliedFunction(b, f, null);
            case com.legend.protocol.spec.CFloat c ->
                    literal(b, "float", String.valueOf(c.value()), c.pos());
            case com.legend.protocol.spec.PackageableElementPtr ptr -> {
                b.append("{\"_type\":\"packageableElementPtr\",\"fullPath\":");
                str(b, ptr.fullPath());
                b.append(",\"sourceInformation\":");
                srcInfo(b, requirePos(ptr.pos(), "packageableElementPtr " + ptr.fullPath()));
                b.append('}');
            }
            case com.legend.protocol.spec.EnumValue e -> {
                // On the wire an enum-value access is a plain PROPERTY on a
                // packageableElementPtr — there is no enumValue node (ProbeWireShapes cEnum).
                b.append("{\"_type\":\"property\",\"parameters\":["
                        + "{\"_type\":\"packageableElementPtr\",\"fullPath\":");
                str(b, e.fullPath());
                b.append(",\"sourceInformation\":");
                srcInfo(b, requirePos(e.enumerationPos(), "enum ptr " + e.fullPath()));
                b.append("}],\"property\":");
                str(b, e.value());
                b.append(",\"sourceInformation\":");
                srcInfo(b, requirePos(e.pos(), "enum value " + e.value()));
                b.append('}');
            }
            case com.legend.protocol.spec.CLatestDate l -> {
                b.append("{\"_type\":\"latestDate\",\"sourceInformation\":");
                srcInfo(b, requirePos(l.pos(), "%latest"));
                b.append('}');
            }
            case com.legend.protocol.spec.CDecimal dec -> {
                if (dec.written() == null) {
                    throw new UnsupportedOperationException(
                            "ProtocolEmitter needs the verbatim spelling of a decimal literal.");
                }
                // {"_type":"decimal","value":3.14} — the value is a bare JSON number in the
                // source's own digits (suffix stripped by the parser).
                literal(b, "decimal", dec.written(), dec.pos());
            }
            case com.legend.protocol.spec.TypeAnnotation.Named named -> {
                // @Type on the wire: {"_type":"genericTypeInstance","genericType":…,
                // "sourceInformation":span-of-@..type} (ProbeWireShapes "burn zoo" casts).
                b.append("{\"_type\":\"genericTypeInstance\",\"genericType\":");
                genericType(b, named.type());
                b.append(",\"sourceInformation\":");
                srcInfo(b, requirePos(named.pos(), "@-type annotation"));
                b.append('}');
            }
            case com.legend.protocol.spec.NewInstance ni -> newInstance(b, ni, null);
            case com.legend.protocol.spec.ColSpec cs -> colSpec(b, cs);
            case com.legend.protocol.spec.ColSpecArray ca -> colSpecArray(b, ca);
            case com.legend.protocol.spec.PathLiteral pl -> pathLiteral(b, pl);
            case com.legend.protocol.spec.LambdaFunction lam -> lambda(b, lam);
            case com.legend.protocol.spec.CDate d -> {
                // The value is the SOURCE SPELLING, verbatim. DAY precision emits strictDate;
                // every other precision emits dateTime — and MONTH keeps its leading '%'
                // (an engine walker quirk, verified via ProbeWireShapes "burn zoo" dates:
                // %2020 -> "2020" but %2020-01 -> "%2020-01"). Reproduced, not questioned.
                if (d.written() == null) {
                    throw new UnsupportedOperationException(
                            "ProtocolEmitter needs the verbatim source spelling of a date"
                                    + " literal and the parser did not thread it.");
                }
                boolean day = d.value().precision() == com.legend.values.PureDateLiteral.Precision.DAY;
                boolean month = d.value().precision() == com.legend.values.PureDateLiteral.Precision.MONTH;
                b.append(day ? "{\"_type\":\"strictDate\",\"sourceInformation\":"
                        : "{\"_type\":\"dateTime\",\"sourceInformation\":");
                srcInfo(b, requirePos(d.pos(), "date literal"));
                b.append(",\"value\":");
                str(b, month ? "%" + d.written() : d.written());
                b.append('}');
            }
            default -> throw new UnsupportedOperationException(
                    "ProtocolEmitter has no rule for value specification "
                            + v.getClass().getSimpleName() + " — add the emit rule, do not drop it.");
        }
    }

    /**
     * An inline lambda literal. The lambda node itself carries no span. Parameters
     * (ProbeWireShapes cLambda/cLambda2): an UNTYPED parameter is the bare
     * {@code {"_type":"var","name":…}} — no span, no multiplicity; a TYPED one carries
     * {@code genericType} + {@code multiplicity} + the span of its whole declaration.
     */
    private static void lambda(StringBuilder b, com.legend.protocol.spec.LambdaFunction lam) {
        b.append("{\"_type\":\"lambda\",\"body\":[");
        for (int i = 0; i < lam.body().size(); i++) {
            if (i > 0) {
                b.append(',');
            }
            valueSpec(b, lam.body().get(i));
        }
        b.append("],\"parameters\":[");
        for (int i = 0; i < lam.parameters().size(); i++) {
            if (i > 0) {
                b.append(',');
            }
            com.legend.protocol.spec.Variable p = lam.parameters().get(i);
            if (p.type() == null) {
                require(p.multiplicity() == null, "untyped lambda parameter with multiplicity",
                        p.name());
                b.append("{\"_type\":\"var\",\"name\":");
                str(b, p.name());
                b.append('}');
            } else {
                b.append("{\"_type\":\"var\",\"genericType\":");
                genericType(b, p.type());
                b.append(",\"multiplicity\":");
                multiplicity(b, java.util.Objects.requireNonNull(p.multiplicity(),
                        "typed lambda parameter without multiplicity: " + p.name()));
                b.append(",\"name\":");
                str(b, p.name());
                b.append(",\"sourceInformation\":");
                srcInfo(b, requirePos(p.pos(), "typed lambda parameter " + p.name()));
                b.append('}');
            }
        }
        b.append("],\"sourceInformation\":");
        srcInfo(b, requirePos(lam.pos(), "inline lambda"));
        b.append('}');
    }

    /**
     * Emit a node with its top-level span REPLACED — the let-value rule. Nested nodes keep
     * their own spans; only the top node's is overridden (ProbeWireShapes "let zoo").
     */
    private static void valueSpecWithSpan(StringBuilder b,
                                          com.legend.protocol.spec.ValueSpecification v,
                                          SourceInfo span) {
        switch (v) {
            case com.legend.protocol.spec.CBoolean c ->
                    valueSpec(b, new com.legend.protocol.spec.CBoolean(c.value(), span));
            case com.legend.protocol.spec.CInteger c ->
                    valueSpec(b, new com.legend.protocol.spec.CInteger(c.value(), span));
            case com.legend.protocol.spec.CString c ->
                    valueSpec(b, new com.legend.protocol.spec.CString(c.value(), span));
            case com.legend.protocol.spec.CFloat c ->
                    valueSpec(b, new com.legend.protocol.spec.CFloat(c.value(), span));
            case com.legend.protocol.spec.CDate c ->
                    valueSpec(b, new com.legend.protocol.spec.CDate(c.value(), c.written(), span));
            case com.legend.protocol.spec.Variable var ->
                    valueSpec(b, new com.legend.protocol.spec.Variable(
                            var.name(), var.type(), var.multiplicity(), span));
            case com.legend.protocol.spec.PureCollection c ->
                    valueSpec(b, new com.legend.protocol.spec.PureCollection(c.values(), span));
            case com.legend.protocol.spec.AppliedProperty pr ->
                    valueSpec(b, new com.legend.protocol.spec.AppliedProperty(
                            pr.receiver(), pr.property(), span));
            case com.legend.protocol.spec.EnumValue e ->
                    valueSpec(b, new com.legend.protocol.spec.EnumValue(
                            e.fullPath(), e.value(), e.enumerationPos(), span));
            case com.legend.protocol.spec.PackageableElementPtr ptr ->
                    valueSpec(b, new com.legend.protocol.spec.PackageableElementPtr(
                            ptr.fullPath(), span));
            case com.legend.protocol.spec.CLatestDate l ->
                    valueSpec(b, new com.legend.protocol.spec.CLatestDate(span));
            case com.legend.protocol.spec.ColSpecArray ca ->
                    valueSpec(b, new com.legend.protocol.spec.ColSpecArray(ca.colSpecs(), span));
            case com.legend.protocol.spec.ColSpec cs ->
                    valueSpec(b, new com.legend.protocol.spec.ColSpec(cs.name(), cs.function1(),
                            cs.function2(), cs.alias(), cs.args(), cs.qualified(), span));
            case com.legend.protocol.spec.NewInstance ni ->
                    valueSpec(b, ni);   // ^X(...) carries no span on the wire at all
            case com.legend.protocol.spec.TypeAnnotation.Named named ->
                    valueSpec(b, new com.legend.protocol.spec.TypeAnnotation.Named(
                            named.type(), span));
            // Pass the override ALONGSIDE the node: rebuilding pos would corrupt the
            // n-ary chain-span derivation, which must read the original climb spans
            // (caught by the harness on QueryWithLet).
            case com.legend.protocol.spec.AppliedFunction af -> appliedFunction(b, af, span);
            case com.legend.protocol.spec.LambdaFunction lam ->
                    valueSpec(b, new com.legend.protocol.spec.LambdaFunction(
                            lam.parameters(), lam.body(), span));
            default -> throw new UnsupportedOperationException(
                    "ProtocolEmitter has no let-value span rule for "
                            + v.getClass().getSimpleName() + " — probe, do not guess.");
        }
    }

    /** Arithmetic natives the engine spells N-ARY: one collection parameter holding the
     *  flattened operand chain. {@code divide} and the comparisons stay binary. */
    private static final java.util.Set<String> NARY_ARITHMETIC =
            java.util.Set.of("plus", "minus", "times");

    /** The operators our grammar can only produce OVER an arithmetic LHS via explicit
     *  parentheses — a tree shape whose engine bytes are not yet probed (see below). */
    private static final java.util.Set<String> EQUAL_AND_OR =
            java.util.Set.of("equal", "and", "or");

    private static final java.util.Set<String> BINARY_ARITHMETIC = java.util.Set.of("divide");

    /** Every infix-built family — the key-expression first-atom rule strips them all. */
    private static final java.util.Set<String> INFIX_FAMILIES = java.util.Set.of(
            "plus", "minus", "times", "divide", "lessThan", "lessThanEqual",
            "greaterThan", "greaterThanEqual", "equal", "and", "or");

    private static void appliedFunction(StringBuilder b,
                                        com.legend.protocol.spec.AppliedFunction f,
                                        @com.legend.Nullable SourceInfo topSpanOverride) {
        if (f.propertyCall()) {
            // The wire emits `receiver.name(args)` as a PROPERTY node with the arguments
            // appended after the receiver, spanning the NAME token only (ProbeWireShapes
            // cPcall) — EXCEPT milestoned accesses (date/%latest arguments), which the
            // engine's milestoning walker emits with no span at all (harness DIFF on
            // testBiTemporalDateMilestoning).
            boolean milestoned = false;
            for (int i = 1; i < f.parameters().size(); i++) {
                if (f.parameters().get(i) instanceof com.legend.protocol.spec.CLatestDate) {
                    milestoned = true;
                }
            }
            b.append("{\"_type\":\"property\",\"parameters\":[");
            for (int i = 0; i < f.parameters().size(); i++) {
                if (i > 0) {
                    b.append(',');
                }
                if (milestoned && f.parameters().get(i)
                        instanceof com.legend.protocol.spec.CLatestDate) {
                    // the %latest argument of a milestoned access is span-less too
                    b.append("{\"_type\":\"latestDate\"}");
                } else {
                    valueSpec(b, f.parameters().get(i));
                }
            }
            b.append("],\"property\":");
            str(b, f.function());
            b.append(",\"sourceInformation\":");
            srcInfo(b, topSpanOverride != null ? topSpanOverride
                    : requirePos(f.pos(), "property call " + f.function()));
            b.append('}');
            return;
        }
        if ("letFunction".equals(f.function())) {
            // `let name = value` — the name-string parameter carries NO span on the wire
            // (ProbeWireShapes "function"); the func spans the whole let statement.
            require(f.parameters().size() == 2
                            && f.parameters().get(0) instanceof com.legend.protocol.spec.CString,
                    "malformed letFunction", String.valueOf(f.parameters().size()));
            SourceInfo letSpan = requirePos(f.pos(), "letFunction");
            b.append("{\"_type\":\"func\",\"function\":\"letFunction\",\"parameters\":["
                    + "{\"_type\":\"string\",\"value\":");
            str(b, ((com.legend.protocol.spec.CString) f.parameters().get(0)).value());
            b.append("},");
            // Engine's let rule (ProbeWireShapes "let zoo", ALL value kinds verified): the
            // value's TOP node takes the letFunction's own span; nested nodes keep theirs.
            valueSpecWithSpan(b, f.parameters().get(1), letSpan);
            b.append("],\"sourceInformation\":");
            srcInfo(b, letSpan);
            b.append('}');
            return;
        }
        if (NARY_ARITHMETIC.contains(f.function())) {
            naryArithmetic(b, f, topSpanOverride);
            return;
        }
        // (equal/and/or over a parenthesised arithmetic chain probed harmless: the inner
        // func keeps its operator-run span, nothing special — ProbeWireShapes parenEq.)
        if ("new".equals(f.function()) && !f.parameters().isEmpty()
                && f.parameters().get(f.parameters().size() - 1)
                        instanceof com.legend.protocol.spec.NewInstance ni) {
            // The parser wraps ^X(...) as AppliedFunction("new", [receiver, NewInstance]);
            // the wire's whole envelope comes from the NewInstance node alone and carries
            // no spans anywhere STANDALONE — but the let rule still applies: a let-valued
            // new takes the letFunction's span (harness DIFF on testFromJson2).
            newInstance(b, ni, topSpanOverride);
            return;
        }
        if ("pathWithAlias".equals(f.function())
                && !f.parameters().isEmpty()
                && f.parameters().get(0) instanceof com.legend.protocol.spec.PathLiteral pathLit) {
            // the alias carrier is legend-lite-internal; the wire's whole shape (including
            // the alias as the path's "name") comes from the PathLiteral itself
            pathLiteral(b, pathLit);
            return;
        }
        if ("tableReference".equals(f.function())) {
            // #>{db.tbl}# on the wire: classInstance of type ">" whose value is
            // {path:[db, table], sourceInformation} — outer and inner spans identical,
            // covering the whole literal (ProbeWireShapes "burn zoo 2" tref).
            require(f.parameters().size() == 2
                            && f.parameters().get(0) instanceof com.legend.protocol.spec.PackageableElementPtr
                            && f.parameters().get(1) instanceof com.legend.protocol.spec.CString,
                    "table reference shape", String.valueOf(f.parameters().size()));
            com.legend.protocol.spec.PackageableElementPtr db =
                    (com.legend.protocol.spec.PackageableElementPtr) f.parameters().get(0);
            com.legend.protocol.spec.CString tbl =
                    (com.legend.protocol.spec.CString) f.parameters().get(1);
            SourceInfo span = topSpanOverride != null ? topSpanOverride
                    : requirePos(f.pos(), "table reference");
            b.append("{\"_type\":\"classInstance\",\"sourceInformation\":");
            srcInfo(b, span);
            b.append(",\"type\":\">\",\"value\":{\"path\":[");
            str(b, db.fullPath());
            // schema-qualified names split into separate path entries:
            // #>{db.schema.TBL}# -> ["db","schema","TBL"] (ProbeWireShapes c)
            for (String part : tbl.value().split("\\.")) {
                b.append(',');
                str(b, part);
            }
            b.append("],\"sourceInformation\":");
            srcInfo(b, span);
            b.append("}}");
            return;
        }
        b.append("{\"_type\":\"func\",\"function\":");
        str(b, f.function());
        b.append(",\"parameters\":[");
        for (int i = 0; i < f.parameters().size(); i++) {
            if (i > 0) {
                b.append(',');
            }
            valueSpec(b, f.parameters().get(i));
        }
        b.append("],\"sourceInformation\":");
        srcInfo(b, topSpanOverride != null ? topSpanOverride
                : requirePos(f.pos(), "func " + f.function()));
        b.append('}');
    }

    /**
     * {@code a + b + c} on the wire is ONE {@code plus} whose single parameter is a collection
     * of all operands (probe: multiplicity {@code [n..n]}, span from the FIRST operator token
     * to the end of the chain — which is the innermost climb node's span start and the
     * outermost's end). Our precedence climb builds a left-nested tree of the same operator;
     * flatten its left spine.
     */
    private static void naryArithmetic(StringBuilder b,
                                       com.legend.protocol.spec.AppliedFunction f,
                                       @com.legend.Nullable SourceInfo topSpanOverride) {
        if (f.parameters().size() == 1) {
            // UNARY form (ProbeWireShapes cNeg): one direct parameter, NO collection,
            // span = the operator token only.
            b.append("{\"_type\":\"func\",\"function\":");
            str(b, f.function());
            b.append(",\"parameters\":[");
            valueSpec(b, f.parameters().get(0));
            b.append("],\"sourceInformation\":");
            srcInfo(b, topSpanOverride != null ? topSpanOverride
                    : requirePos(f.pos(), "unary " + f.function()));
            b.append('}');
            return;
        }
        java.util.ArrayDeque<com.legend.protocol.spec.ValueSpecification> operands =
                new java.util.ArrayDeque<>();
        com.legend.protocol.spec.AppliedFunction node = f;
        while (true) {
            require(node.parameters().size() == 2, "non-infix " + node.function(), "arity "
                    + node.parameters().size());
            operands.addFirst(node.parameters().get(1));
            if (node.parameters().get(0) instanceof com.legend.protocol.spec.AppliedFunction inner
                    && inner.function().equals(f.function())
                    && !inner.grouped()) {
                node = inner;
            } else {
                operands.addFirst(node.parameters().get(0));
                break;
            }
        }
        // The func's span is its own operator-run context, carried on the node by the
        // climb. The COLLECTION keeps that same span — UNLESS the run's last operand was
        // claimed by a tighter operator (its context lies entirely after the run's), in
        // which case the engine's walker leaves the collection stamped with the CLAIMING
        // context (ProbeWireShapes "precedence zoo": 2 + 2 * 4 -> plus coll spans the
        // times segment). In let context only the FUNC span is overridden.
        SourceInfo ctx = requirePos(f.pos(), "func " + f.function());
        SourceInfo coll = ctx;
        if (operands.peekLast() instanceof com.legend.protocol.spec.AppliedFunction lastOp
                && lastOp.pos() != null
                && startsAfter(lastOp.pos(), ctx)) {
            coll = lastOp.pos();
        }
        b.append("{\"_type\":\"func\",\"function\":");
        str(b, f.function());
        b.append(",\"parameters\":[");
        collection(b, java.util.List.copyOf(operands), coll);
        b.append("],\"sourceInformation\":");
        srcInfo(b, topSpanOverride != null ? topSpanOverride : ctx);
        b.append('}');
    }

    /**
     * {@code ^X(k=v,…)} on the wire: {@code func "new"} with NO span, parameters
     * [span-less {@code genericTypeInstance} of {@code Class<X>}, span-less empty string,
     * span-less collection of {@code keyExpression}s whose keys are span-less strings and
     * whose values keep their own spans] (ProbeWireShapes "burn zoo" newInst).
     */
    private static void newInstance(StringBuilder b, com.legend.protocol.spec.NewInstance ni,
                                     @com.legend.Nullable SourceInfo span) {
        require(!ni.className().isEmpty(), "new-instance on a variable receiver", "^$x(...)");
        // ENGINE SPECIAL-CASES two classes, matching by SIMPLE name (ProbeWireShapes
        // "caret specials"): ^Pair(first=,second=) emits the pair() FUNCTION and
        // ^BasicColumnSpecification(func=,name=) emits col() — canonical key order,
        // no envelope span, values keeping their own spans.
        String simple = ni.className().contains("::")
                ? ni.className().substring(ni.className().lastIndexOf("::") + 2)
                : ni.className();
        if ("Pair".equals(simple)) {
            caretSpecial(b, ni, "meta::pure::functions::collection::pair",
                    new String[]{"first", "second"}, span);
            return;
        }
        if ("BasicColumnSpecification".equals(simple)) {
            caretSpecial(b, ni, "meta::pure::tds::col",
                    new String[]{"func", "name"}, span);
            return;
        }
        b.append("{\"_type\":\"func\",\"function\":\"new\",\"parameters\":["
                + "{\"_type\":\"genericTypeInstance\",\"genericType\":{"
                + "\"multiplicityArguments\":[],\"rawType\":{\"_type\":\"packageableType\","
                + "\"fullPath\":\"meta::pure::metamodel::type::Class\"},\"typeArguments\":["
                + "{\"multiplicityArguments\":[");
        for (int i = 0; i < ni.typeMultiplicityArguments().size(); i++) {
            if (i > 0) {
                b.append(',');
            }
            // ^Result<Any|*>(...): the multiplicity arguments ride on the constructed
            // type's own genericType (harness DIFF on executionPlan_execution).
            multiplicity(b, parseMultArg(ni.typeMultiplicityArguments().get(i), ni.className()));
        }
        b.append("],\"rawType\":{\"_type\":\"packageableType\","
                + "\"fullPath\":");
        str(b, ni.className());
        b.append("},\"typeArguments\":[");
        for (int i = 0; i < ni.typeArguments().size(); i++) {
            if (i > 0) {
                b.append(',');
            }
            // ^X<T>(...): the type arguments ride INSIDE Class<X>'s inner genericType,
            // keeping their real source spans (ProbeWireShapes "typed new and gft").
            genericType(b, ni.typeArguments().get(i));
        }
        b.append("],\"typeVariableValues\":[]}],"
                + "\"typeVariableValues\":[]}},{\"_type\":\"string\",\"value\":\"\"},"
                + "{\"_type\":\"collection\",\"multiplicity\":{\"lowerBound\":")
                .append(ni.properties().size()).append(",\"upperBound\":")
                .append(ni.properties().size()).append("},\"values\":[");
        boolean first = true;
        for (java.util.Map.Entry<String, com.legend.protocol.spec.KeyExpression> e
                : ni.properties().entrySet()) {
            if (!first) {
                b.append(',');
            }
            first = false;
            require(!e.getValue().isLocal(), "local key expression", e.getKey());
            if (e.getValue().value() instanceof com.legend.protocol.spec.PackageableElementPtr root
                    && "::".equals(root.fullPath())) {
                // ENGINE QUIRK (harness DIFF on storeContract): `package=::` maps the root-
                // package reference to null, and NON_NULL drops the expression field whole.
                b.append("{\"_type\":\"keyExpression\",\"add\":")
                        .append(e.getValue().isAdd()).append(",\"key\":{\"_type\":\"string\",\"value\":");
                str(b, e.getKey());
                b.append("}}");
                continue;
            }
            b.append("{\"_type\":\"keyExpression\",\"add\":")
                    .append(e.getValue().isAdd()).append(",\"expression\":");
            // ENGINE DATA-LOSS BUG, reproduced for byte parity (ProbeWireShapes "burn
            // zoo 2" keyChain; harness DIFF on ruleBasedTransformation for the boolean
            // flavor): a key expression keeps only the FIRST ATOM of an unparenthesised
            // infix chain — s='a'+'b'+$v emits just 'a', h=$a||$b emits just $a.
            com.legend.protocol.spec.ValueSpecification kv = e.getValue().value();
            while (kv instanceof com.legend.protocol.spec.AppliedFunction chain
                    && INFIX_FAMILIES.contains(chain.function())
                    && !chain.grouped()
                    && chain.parameters().size() == 2) {
                kv = chain.parameters().get(0);
            }
            valueSpec(b, kv);
            b.append(",\"key\":{\"_type\":\"string\",\"value\":");
            str(b, e.getKey());
            b.append("}}");
        }
        b.append("]}]");
        if (span != null) {
            b.append(",\"sourceInformation\":");
            srcInfo(b, span);
        }
        b.append('}');
    }

    /**
     * {@code #/Root/prop#} on the wire: a {@code classInstance} of type {@code path} whose
     * spans are SHIFTED RIGHT by the literal's length — an engine island-reparse artifact
     * reproduced faithfully (ProbeWireShapes "path offsets", two-sample regression): for a
     * literal at column {@code s}, length {@code len}: outer = {@code [s+len, s+2*len+2]},
     * segment chars {@code [a,b]} (0-based inclusive) = {@code [s+len+a-2, s+len+b-1]}.
     */
    private static void pathLiteral(StringBuilder b, com.legend.protocol.spec.PathLiteral pl) {
        SourceInfo lit = requirePos(pl.pos(), "path literal");
        require(lit.startLine() == lit.endLine(), "multi-line path literal", pl.startType());
        int s = lit.startColumn();
        int len = pl.literalLength();
        int line = lit.startLine();
        SourceInfo outer = new SourceInfo(lit.sourceId(), line, s + len, line, s + 2 * len + 2);
        b.append("{\"_type\":\"classInstance\",\"sourceInformation\":");
        srcInfo(b, outer);
        b.append(",\"type\":\"path\",\"value\":{");
        if (pl.alias() != null) {
            // the !alias becomes the path's NAME, alphabetically first in the value
            b.append("\"name\":");
            str(b, pl.alias());
            b.append(',');
        }
        b.append("\"path\":[");
        for (int i = 0; i < pl.segments().size(); i++) {
            if (i > 0) {
                b.append(',');
            }
            com.legend.protocol.spec.PathLiteral.Segment seg = pl.segments().get(i);
            require(!seg.unsupportedArg(),
                    "dated path segment with a non-%latest argument", seg.name());
            b.append("{\"_type\":\"propertyPath\",\"parameters\":[");
            for (int a = 0; a < seg.latestArgRanges().size(); a++) {
                if (a > 0) {
                    b.append(',');
                }
                // the dated ARGUMENT shifts by one LESS than the property chunk —
                // a-1 rather than a-2 (ProbeWireShapes "alias dated tref2 gft2" b)
                com.legend.protocol.spec.PathLiteral.ArgRange r = seg.latestArgRanges().get(a);
                b.append("{\"_type\":\"latestDate\",\"sourceInformation\":");
                srcInfo(b, new SourceInfo(lit.sourceId(),
                        line, s + len + r.start() - 1,
                        line, s + len + r.end() - 1));
                b.append('}');
            }
            b.append("],\"property\":");
            str(b, seg.name());
            b.append(",\"sourceInformation\":");
            srcInfo(b, new SourceInfo(lit.sourceId(),
                    line, s + len + seg.innerStart() - 2,
                    line, s + len + seg.innerEnd() - 1));
            b.append('}');
        }
        b.append("],\"sourceInformation\":");
        srcInfo(b, outer);
        b.append(",\"startType\":");
        str(b, pl.startType());
        b.append("}}");
    }

    /** The engine's hardcoded caret-to-function desugars — see {@code newInstance}. */
    private static void caretSpecial(StringBuilder b, com.legend.protocol.spec.NewInstance ni,
                                     String function, String[] keys,
                                     @com.legend.Nullable SourceInfo span) {
        b.append("{\"_type\":\"func\",\"function\":");
        str(b, function);
        b.append(",\"parameters\":[");
        for (int i = 0; i < keys.length; i++) {
            com.legend.protocol.spec.KeyExpression ke = ni.properties().get(keys[i]);
            if (ke == null) {
                throw new UnsupportedOperationException(
                        "ProtocolEmitter has no rule for a caret special missing key '"
                                + keys[i] + "' (at " + ni.className() + ").");
            }
            if (i > 0) {
                b.append(',');
            }
            valueSpec(b, ke.value());
        }
        b.append(']');
        if (span != null) {
            b.append(",\"sourceInformation\":");
            srcInfo(b, span);
        }
        b.append('}');
    }

    /** {@code ~name} on the wire: a {@code classInstance} of type {@code colSpec}. Bare
     *  specs span the NAME token (tilde excluded); function-bearing ones span tilde..end;
     *  outer and value spans are identical (ProbeWireShapes "path and cols" + "colspec
     *  fn spans"). */
    private static void colSpec(StringBuilder b, com.legend.protocol.spec.ColSpec cs) {
        require(cs.alias() == null && cs.args().isEmpty() && !cs.qualified(),
                "colSpec with alias/args", cs.name());
        SourceInfo pos = requirePos(cs.pos(), "colSpec " + cs.name());
        b.append("{\"_type\":\"classInstance\",\"sourceInformation\":");
        srcInfo(b, pos);
        b.append(",\"type\":\"colSpec\",\"value\":");
        colSpecValue(b, cs, pos);
        b.append('}');
    }

    private static void colSpecValue(StringBuilder b, com.legend.protocol.spec.ColSpec cs,
                                     SourceInfo pos) {
        b.append('{');
        if (cs.function1() != null) {
            b.append("\"function1\":");
            lambda(b, cs.function1());
            b.append(',');
        }
        if (cs.function2() != null) {
            b.append("\"function2\":");
            lambda(b, cs.function2());
            b.append(',');
        }
        b.append("\"name\":");
        str(b, cs.name());
        b.append(",\"sourceInformation\":");
        srcInfo(b, pos);
        b.append('}');
    }

    /** {@code ~[a, b]}: a {@code classInstance} of type {@code colSpecArray} spanning the
     *  brackets, entries spanning their name tokens. */
    private static void colSpecArray(StringBuilder b, com.legend.protocol.spec.ColSpecArray ca) {
        b.append("{\"_type\":\"classInstance\",\"sourceInformation\":");
        srcInfo(b, requirePos(ca.pos(), "colSpecArray"));
        b.append(",\"type\":\"colSpecArray\",\"value\":{\"colSpecs\":[");
        for (int i = 0; i < ca.colSpecs().size(); i++) {
            if (i > 0) {
                b.append(',');
            }
            com.legend.protocol.spec.ColSpec cs = ca.colSpecs().get(i);
            colSpecValue(b, cs, requirePos(cs.pos(), "colSpec " + cs.name()));
        }
        b.append("]}}");
    }

    private static com.legend.protocol.Multiplicity parseMultArg(String text, String where) {
        if (text.equals("*")) {
            return new com.legend.protocol.Multiplicity.Concrete(0, null);
        }
        int dots = text.indexOf("..");
        try {
            if (dots < 0) {
                int n = Integer.parseInt(text);
                return new com.legend.protocol.Multiplicity.Concrete(n, n);
            }
            int lo = Integer.parseInt(text.substring(0, dots));
            String hi = text.substring(dots + 2);
            return new com.legend.protocol.Multiplicity.Concrete(lo,
                    hi.equals("*") ? null : Integer.valueOf(hi));
        } catch (NumberFormatException named) {
            throw new UnsupportedOperationException(
                    "ProtocolEmitter has no rule for a multiplicity PARAMETER '" + text
                            + "' in generic " + where + " — add the emit rule.");
        }
    }

    /** True when {@code s} begins strictly after {@code ctx} ends. */
    private static boolean startsAfter(SourceInfo s, SourceInfo ctx) {
        return s.startLine() > ctx.endLine()
                || (s.startLine() == ctx.endLine() && s.startColumn() > ctx.endColumn());
    }

    /** {@code {"_type":"collection","multiplicity":{n,n},"sourceInformation":…,"values":[…]}} */
    private static void collection(StringBuilder b,
                                   List<com.legend.protocol.spec.ValueSpecification> values,
                                   SourceInfo pos) {
        b.append("{\"_type\":\"collection\",\"multiplicity\":{\"lowerBound\":")
                .append(values.size()).append(",\"upperBound\":").append(values.size())
                .append("},\"sourceInformation\":");
        srcInfo(b, pos);
        b.append(",\"values\":[");
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) {
                b.append(',');
            }
            valueSpec(b, values.get(i));
        }
        b.append("]}");
    }

    private static SourceInfo requirePos(@com.legend.Nullable SourceInfo pos, String what) {
        if (pos == null) {
            throw new UnsupportedOperationException(
                    "ProtocolEmitter needs a source position for " + what
                            + " and the parser did not thread one — fix the parse site.");
        }
        return pos;
    }

    /** {@code {"_type":…,"sourceInformation":…,"value":…}} — {@code rendered} is emitted verbatim. */
    private static void literal(StringBuilder b, String type, String rendered,
                                com.legend.protocol.@com.legend.Nullable SourceInfo pos) {
        if (pos == null) {
            throw new UnsupportedOperationException(
                    "ProtocolEmitter needs a source position for a " + type
                            + " literal and the parser did not thread one — fix the parse site.");
        }
        b.append("{\"_type\":\"").append(type).append("\",\"sourceInformation\":");
        srcInfo(b, pos);
        b.append(",\"value\":").append(rendered).append('}');
    }

    private static void multiplicity(StringBuilder b, com.legend.protocol.Multiplicity m) {
        if (!(m instanceof com.legend.protocol.Multiplicity.Concrete c)) {
            throw new UnsupportedOperationException(
                    "ProtocolEmitter has no rule for a multiplicity PARAMETER — add the emit rule.");
        }
        b.append("{\"lowerBound\":").append(c.lowerBound());
        if (c.upperBound() != null) {          // null upper bound is [n..*]; NON_NULL omits it
            b.append(",\"upperBound\":").append(c.upperBound().intValue());
        }
        b.append('}');
    }

    private static void sectionIndex(StringBuilder b, PSectionIndex s) {
        b.append("{\"_type\":\"sectionIndex\",\"name\":");
        str(b, s.name());
        b.append(",\"package\":");
        str(b, s.pkg());
        b.append(",\"sections\":[");
        List<PSection> ss = s.sections();
        for (int i = 0; i < ss.size(); i++) {
            if (i > 0) {
                b.append(',');
            }
            section(b, ss.get(i));
        }
        b.append("]}");
    }

    private static void section(StringBuilder b, PSection s) {
        b.append("{\"_type\":\"importAware\",\"elements\":[");
        for (int i = 0; i < s.elements().size(); i++) {
            if (i > 0) {
                b.append(',');
            }
            str(b, s.elements().get(i));
        }
        b.append("],\"imports\":[");
        for (int i = 0; i < s.imports().size(); i++) {
            if (i > 0) {
                b.append(',');
            }
            str(b, s.imports().get(i));
        }
        b.append("],\"parserName\":");
        str(b, s.parserName());
        b.append(",\"sourceInformation\":");
        srcInfo(b, s.sourceInformation());
        b.append('}');
    }

    private static void srcInfo(StringBuilder b, SourceInfo s) {
        b.append("{\"endColumn\":").append(s.endColumn())
                .append(",\"endLine\":").append(s.endLine())
                .append(",\"sourceId\":");
        str(b, s.sourceId());
        b.append(",\"startColumn\":").append(s.startColumn())
                .append(",\"startLine\":").append(s.startLine()).append('}');
    }

    /**
     * A construct the emitter cannot yet put on the wire must stop the build, never vanish from it.
     * Silent omission is how a byte-identity claim becomes a lie that every structural comparison
     * still passes.
     */
    private static void require(boolean emitted, String what, String where) {
        if (!emitted) {
            throw new UnsupportedOperationException(
                    "ProtocolEmitter has no rule for " + what + " (at " + where
                            + "). Add the emit rule — do not drop it.");
        }
    }

    /** RFC-8259 string escaping, matching Jackson's default output. */
    private static void str(StringBuilder b, String v) {
        b.append('"');
        for (int i = 0; i < v.length(); i++) {
            char c = v.charAt(i);
            switch (c) {
                case '"' -> b.append("\\\"");
                case '\\' -> b.append("\\\\");
                case '\n' -> b.append("\\n");
                case '\r' -> b.append("\\r");
                case '\t' -> b.append("\\t");
                case '\b' -> b.append("\\b");
                case '\f' -> b.append("\\f");
                default -> {
                    if (c < 0x20) {
                        b.append(String.format("\\u%04x", (int) c));
                    } else {
                        b.append(c);
                    }
                }
            }
        }
        b.append('"');
    }
}
