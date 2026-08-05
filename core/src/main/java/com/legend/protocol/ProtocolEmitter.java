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
                    genericTypeOf(b, n.name(), java.util.List.of(), n.pos());
            case com.legend.protocol.TypeExpression.Generic g -> {
                require(g.multiplicityArguments().isEmpty(),
                        "generic multiplicity arguments", g.name());
                genericTypeOf(b, g.name(), g.arguments(), g.pos());
            }
            default -> throw new UnsupportedOperationException(
                    "ProtocolEmitter has no rule for type expression "
                            + t.getClass().getSimpleName() + " — add the emit rule, do not drop it.");
        }
    }

    private static void genericTypeOf(StringBuilder b, String path,
                                      List<com.legend.protocol.TypeExpression> args,
                                      com.legend.protocol.@com.legend.Nullable SourceInfo pos) {
        if (pos == null) {
            throw new UnsupportedOperationException(
                    "ProtocolEmitter needs a source position for type " + path
                            + " and the parser did not thread one — fix the parse site, do not default it.");
        }
        b.append("{\"multiplicityArguments\":[],\"rawType\":{\"_type\":\"packageableType\",\"fullPath\":");
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
            case com.legend.protocol.spec.LambdaFunction lam -> lambda(b, lam);
            case com.legend.protocol.spec.CDate d -> {
                // Only day-precision dates are probed: {"_type":"strictDate","value":"2020-01-01"},
                // span covering the whole %-literal. Coarser/finer precisions wall until probed.
                require(d.value().precision() == com.legend.values.PureDateLiteral.Precision.DAY,
                        "date literal precision " + d.value().precision(), "CDate");
                b.append("{\"_type\":\"strictDate\",\"sourceInformation\":");
                srcInfo(b, requirePos(d.pos(), "strictDate literal"));
                b.append(",\"value\":");
                str(b, d.value().toEngineString());
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
                    valueSpec(b, new com.legend.protocol.spec.CDate(c.value(), span));
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
            // Pass the override ALONGSIDE the node: rebuilding pos would corrupt the
            // n-ary chain-span derivation, which must read the original climb spans
            // (caught by the harness on QueryWithLet).
            case com.legend.protocol.spec.AppliedFunction af -> appliedFunction(b, af, span);
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

    private static void appliedFunction(StringBuilder b,
                                        com.legend.protocol.spec.AppliedFunction f,
                                        @com.legend.Nullable SourceInfo topSpanOverride) {
        if (f.propertyCall()) {
            // The wire emits `receiver.name(args)` as a PROPERTY node with the arguments
            // appended after the receiver, spanning the NAME token only (ProbeWireShapes
            // cPcall, confirming the harness DIFF on AccountWithConstraints).
            b.append("{\"_type\":\"property\",\"parameters\":[");
            for (int i = 0; i < f.parameters().size(); i++) {
                if (i > 0) {
                    b.append(',');
                }
                valueSpec(b, f.parameters().get(i));
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
        // equal/and/or over an arithmetic-chain LHS: reachable in our grammar only through
        // explicit parentheses (unparenthesised, `==` binds into the preceding operand —
        // matching engine's flat grammar, byte-pinned in ConstraintEmissionTest). The
        // parenthesised form's engine bytes are unprobed, so it walls rather than guessing.
        if (EQUAL_AND_OR.contains(f.function())
                && !f.parameters().isEmpty()
                && f.parameters().get(0) instanceof com.legend.protocol.spec.AppliedFunction lhs
                && (NARY_ARITHMETIC.contains(lhs.function())
                        || BINARY_ARITHMETIC.contains(lhs.function()))) {
            throw new UnsupportedOperationException(
                    "ProtocolEmitter has no rule for " + f.function()
                            + " over an arithmetic chain (engine flat-grammar associativity)"
                            + " — probe the wire shape before adding one.");
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
        SourceInfo end = requirePos(f.pos(), "func " + f.function());
        SourceInfo start = end;
        while (true) {
            require(node.parameters().size() == 2, "non-infix " + node.function(), "arity "
                    + node.parameters().size());
            operands.addFirst(node.parameters().get(1));
            start = requirePos(node.pos(), "func " + node.function());
            if (node.parameters().get(0) instanceof com.legend.protocol.spec.AppliedFunction inner
                    && inner.function().equals(f.function())) {
                node = inner;
            } else {
                operands.addFirst(node.parameters().get(0));
                break;
            }
        }
        SourceInfo chain = new SourceInfo(start.sourceId(),
                start.startLine(), start.startColumn(), end.endLine(), end.endColumn());
        b.append("{\"_type\":\"func\",\"function\":");
        str(b, f.function());
        b.append(",\"parameters\":[");
        // The COLLECTION always keeps the chain span — only the func's own span is
        // overridden in let context (ProbeWireShapes "let zoo", `let arith = $a + 1`).
        collection(b, java.util.List.copyOf(operands), chain);
        b.append("],\"sourceInformation\":");
        srcInfo(b, topSpanOverride != null ? topSpanOverride : chain);
        b.append('}');
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
