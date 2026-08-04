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
            case PSectionIndex s -> sectionIndex(b, s);
        }
    }

    private static void pclass(StringBuilder b, PClass c) {
        // Not yet emitted. Loud rather than silently dropped — AGENTS.md invariant 4.
        require(c.typeParams().isEmpty(), "class type parameters", c.qualifiedName());
        require(c.derivedProperties().isEmpty(), "qualifiedProperties", c.qualifiedName());
        require(c.constraints().isEmpty(), "constraints", c.qualifiedName());
        b.append("{\"_type\":\"class\",\"constraints\":[],\"name\":");
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
        b.append("],\"qualifiedProperties\":[],\"sourceInformation\":");
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
            default -> throw new UnsupportedOperationException(
                    "ProtocolEmitter has no rule for value specification "
                            + v.getClass().getSimpleName() + " — add the emit rule, do not drop it.");
        }
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
