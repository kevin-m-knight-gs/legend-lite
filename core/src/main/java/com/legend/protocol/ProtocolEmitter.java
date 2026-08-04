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
import com.legend.protocol.Protocol.SourceInfo;

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
        require(c.superClasses().isEmpty(), "superTypes", c.qualifiedName());
        require(c.derivedProperties().isEmpty(), "qualifiedProperties", c.qualifiedName());
        require(c.constraints().isEmpty(), "constraints", c.qualifiedName());
        require(c.stereotypes().isEmpty(), "stereotypes", c.qualifiedName());
        require(c.taggedValues().isEmpty(), "taggedValues", c.qualifiedName());
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
        b.append(",\"stereotypes\":[],\"superTypes\":[],\"taggedValues\":[]}");
    }

    private static void property(StringBuilder b, PProperty p) {
        require(p.stereotypes().isEmpty(), "property stereotypes", p.name());
        require(p.taggedValues().isEmpty(), "property taggedValues", p.name());
        b.append("{\"genericType\":");
        genericType(b, p.type(), p.typeSourceInformation());
        b.append(",\"multiplicity\":");
        multiplicity(b, p.multiplicity());
        b.append(",\"name\":");
        str(b, p.name());
        b.append(",\"sourceInformation\":");
        srcInfo(b, p.sourceInformation());
        b.append(",\"stereotypes\":[],\"taggedValues\":[]}");
    }

    /** The wire's {@code genericType}. Only a plain named type is expressible so far. */
    private static void genericType(StringBuilder b, com.legend.model.TypeExpression t,
                                    Protocol.SourceInfo pos) {
        if (!(t instanceof com.legend.model.TypeExpression.NameRef n)) {
            throw new UnsupportedOperationException(
                    "ProtocolEmitter has no rule for type expression "
                            + t.getClass().getSimpleName() + " — add the emit rule, do not drop it.");
        }
        b.append("{\"multiplicityArguments\":[],\"rawType\":{\"_type\":\"packageableType\",\"fullPath\":");
        str(b, n.name());
        b.append(",\"sourceInformation\":");
        srcInfo(b, pos);
        b.append("},\"typeArguments\":[],\"typeVariableValues\":[]}");
    }

    private static void multiplicity(StringBuilder b, com.legend.model.Multiplicity m) {
        if (!(m instanceof com.legend.model.Multiplicity.Concrete c)) {
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
