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
            case Protocol.PMeasure m -> measure(b, m);
            case Protocol.PAssociation a -> association(b, a);
            case Protocol.PFunction fn -> function(b, fn);
            case Protocol.PEnumeration en -> enumeration(b, en);
            case Protocol.PProfile pr -> profile(b, pr);
            case PSectionIndex s -> sectionIndex(b, s);
            case Protocol.PRuntime r -> runtime(b, r);
            case Protocol.PConnection c -> connection(b, c);
            case Protocol.PDatabase d -> database(b, d);
            case Protocol.PMapping m -> mapping(b, m);
        }
    }

    /** {@code _type:"runtime"} — element and runtimeValue share ONE span;
     *  runtimeValue discriminates engineRuntime/localEngineRuntime
     *  (ZRuntimeProbe). Fields alphabetical throughout. */
    private static void runtime(StringBuilder b, Protocol.PRuntime r) {
        b.append("{\"_type\":\"runtime\",\"name\":");
        str(b, r.name());
        b.append(",\"package\":");
        str(b, r.pkg());
        b.append(",\"runtimeValue\":{\"_type\":");
        str(b, r.single() ? "localEngineRuntime" : "engineRuntime");
        b.append(",\"connectionStores\":[");
        for (int i = 0; i < r.connectionStores().size(); i++) {
            if (i > 0) {
                b.append(',');
            }
            Protocol.PConnectionStores cs = r.connectionStores().get(i);
            b.append("{\"connectionPointer\":");
            connectionValue(b, cs.connectionPointer());
            b.append(",\"sourceInformation\":");
            srcInfo(b, cs.sourceInformation());
            b.append(",\"storePointers\":[");
            for (int j = 0; j < cs.storePointers().size(); j++) {
                if (j > 0) {
                    b.append(',');
                }
                b.append("{\"path\":");
                str(b, cs.storePointers().get(j).path());
                b.append(",\"sourceInformation\":");
                srcInfo(b, cs.storePointers().get(j).sourceInformation());
                b.append('}');
            }
            b.append("]}");
        }
        b.append("],\"connections\":[");
        for (int i = 0; i < r.connections().size(); i++) {
            if (i > 0) {
                b.append(',');
            }
            storeConnections(b, r.connections().get(i));
        }
        b.append("],\"mappings\":[");
        for (int i = 0; i < r.mappings().size(); i++) {
            if (i > 0) {
                b.append(',');
            }
            pointer(b, r.mappings().get(i));
        }
        b.append("],\"sourceInformation\":");
        srcInfo(b, r.sourceInformation());
        b.append("},\"sourceInformation\":");
        srcInfo(b, r.sourceInformation());
        b.append('}');
    }

    private static void storeConnections(StringBuilder b,
            Protocol.PStoreConnections sc) {
        b.append("{\"sourceInformation\":");
        srcInfo(b, sc.sourceInformation());
        b.append(",\"store\":");
        pointer(b, sc.store());
        b.append(",\"storeConnections\":[");
        for (int i = 0; i < sc.storeConnections().size(); i++) {
            if (i > 0) {
                b.append(',');
            }
            Protocol.PIdentifiedConnection ic = sc.storeConnections().get(i);
            b.append("{\"connection\":");
            connectionValue(b, ic.connection());
            b.append(",\"id\":");
            str(b, ic.id());
            b.append(",\"sourceInformation\":");
            srcInfo(b, ic.sourceInformation());
            b.append('}');
        }
        b.append("]}");
    }

    private static void pointer(StringBuilder b, Protocol.PPointer p) {
        b.append("{\"path\":");
        str(b, p.path());
        b.append(",\"sourceInformation\":");
        srcInfo(b, p.sourceInformation());
        b.append(",\"type\":");
        str(b, p.type());
        b.append('}');
    }

    /** {@code _type:"mapping"} envelope (ZMappingProbe). */
    private static void mapping(StringBuilder b, Protocol.PMapping m) {
        b.append("{\"_type\":\"mapping\",\"associationMappings\":[");
        for (int i = 0; i < m.associationMappings().size(); i++) {
            if (i > 0) {
                b.append(',');
            }
            switch (m.associationMappings().get(i)) {
                case Protocol.PRelAssociationMapping ra -> {
                    b.append("{\"_type\":\"relational\",\"association\":");
                    pointer(b, ra.association());
                    if (ra.id() != null) {
                        b.append(",\"id\":");
                        str(b, ra.id());
                    }
                    b.append(",\"propertyMappings\":[");
                    for (int j = 0; j < ra.propertyMappings().size(); j++) {
                        if (j > 0) {
                            b.append(',');
                        }
                        Protocol.PRelAssocPropertyMapping pm =
                                ra.propertyMappings().get(j);
                        b.append("{\"_type\":\"relationalPropertyMapping\","
                                + "\"property\":{\"property\":");
                        str(b, pm.property());
                        b.append(",\"sourceInformation\":");
                        srcInfo(b, pm.propertySourceInformation());
                        b.append("},\"relationalOperation\":");
                        relOp(b, pm.relationalOperation());
                        if (pm.source() != null) {
                            b.append(",\"source\":");
                            str(b, pm.source());
                        }
                        b.append(",\"sourceInformation\":");
                        srcInfo(b, pm.sourceInformation());
                        if (pm.target() != null) {
                            b.append(",\"target\":");
                            str(b, pm.target());
                        }
                        b.append('}');
                    }
                    b.append("],\"sourceInformation\":");
                    srcInfo(b, ra.sourceInformation());
                    b.append(",\"stores\":[");
                    for (int j = 0; j < ra.stores().size(); j++) {
                        if (j > 0) {
                            b.append(',');
                        }
                        str(b, ra.stores().get(j));
                    }
                    b.append("]}");
                }
                case Protocol.PXStoreAssociationMapping xa -> {
                    b.append("{\"_type\":\"xStore\",\"association\":");
                    pointer(b, xa.association());
                    b.append(",\"propertyMappings\":[");
                    for (int j = 0; j < xa.propertyMappings().size(); j++) {
                        if (j > 0) {
                            b.append(',');
                        }
                        Protocol.PXStorePropertyMapping pm =
                                xa.propertyMappings().get(j);
                        b.append("{\"_type\":\"xStorePropertyMapping\","
                                + "\"crossExpression\":{\"_type\":\"lambda\","
                                + "\"body\":[");
                        for (int k = 0; k < pm.crossExpression().size(); k++) {
                            if (k > 0) {
                                b.append(',');
                            }
                            valueSpec(b, pm.crossExpression().get(k));
                        }
                        b.append("],\"parameters\":[]},\"property\":{\"class\":");
                        str(b, pm.ownerClass());
                        b.append(",\"property\":");
                        str(b, pm.property());
                        b.append(",\"sourceInformation\":");
                        srcInfo(b, pm.propertySourceInformation());
                        b.append("},\"source\":");
                        str(b, pm.source());
                        b.append(",\"sourceInformation\":");
                        srcInfo(b, pm.sourceInformation());
                        b.append(",\"target\":");
                        str(b, pm.target());
                        b.append('}');
                    }
                    b.append("],\"sourceInformation\":");
                    srcInfo(b, xa.sourceInformation());
                    b.append(",\"stores\":[]}");
                }
                case Protocol.PModelJoinAssociationMapping mj -> {
                    b.append("{\"_type\":\"modelJoin\",\"association\":");
                    pointer(b, mj.association());
                    b.append(",\"joinCondition\":");
                    valueSpec(b, mj.joinCondition());
                    b.append(",\"sourceInformation\":");
                    srcInfo(b, mj.sourceInformation());
                    b.append(",\"stores\":[]}");
                }
            }
        }
        b.append("],\"classMappings\":[");
        for (int i = 0; i < m.classMappings().size(); i++) {
            if (i > 0) {
                b.append(',');
            }
            switch (m.classMappings().get(i)) {
                case Protocol.PClassMappingRel r -> relClassMapping(b, r);
                case Protocol.PClassMappingPure pu -> pureClassMapping(b, pu);
                case Protocol.PClassMappingRelation rf -> {
                    b.append("{\"_type\":\"relation\",\"class\":");
                    str(b, rf.className());
                    if (rf.id() != null) {
                        b.append(",\"id\":");
                        str(b, rf.id());
                    }
                    b.append(",\"primaryKey\":[],\"propertyMappings\":[");
                    for (int j = 0; j < rf.propertyMappings().size(); j++) {
                        if (j > 0) {
                            b.append(',');
                        }
                        Protocol.PRelationFnPropertyMapping pm =
                                rf.propertyMappings().get(j);
                        b.append("{\"_type\":"
                                + "\"relationFunctionPropertyMapping\","
                                + "\"column\":");
                        str(b, pm.column());
                        b.append(",\"property\":{\"class\":");
                        str(b, pm.ownerClass());
                        b.append(",\"property\":");
                        str(b, pm.property());
                        b.append(",\"sourceInformation\":");
                        srcInfo(b, pm.propertySourceInformation());
                        b.append('}');
                        if (pm.source() != null) {
                            b.append(",\"source\":");
                            str(b, pm.source());
                        }
                        b.append(",\"sourceInformation\":");
                        srcInfo(b, pm.sourceInformation());
                        b.append('}');
                    }
                    b.append("],\"relationFunction\":{\"path\":");
                    str(b, rf.relationFunction());
                    b.append(",\"sourceInformation\":");
                    srcInfo(b, rf.relationFunctionSourceInformation());
                    b.append(",\"type\":\"FUNCTION\"},\"root\":")
                            .append(rf.root());
                    b.append(",\"sourceInformation\":");
                    srcInfo(b, rf.sourceInformation());
                    b.append('}');
                }
                case Protocol.PClassMappingOperation om -> {
                    b.append("{\"_type\":\"operation\",\"class\":");
                    str(b, om.className());
                    b.append(",\"classSourceInformation\":");
                    srcInfo(b, om.classSourceInformation());
                    if (om.id() != null) {
                        b.append(",\"id\":");
                        str(b, om.id());
                    }
                    if (om.operation() != null) {
                        // MERGE emits NO discriminator (probe merge-op)
                        b.append(",\"operation\":");
                        str(b, om.operation());
                    }
                    b.append(",\"parameters\":[");
                    for (int j = 0; j < om.parameters().size(); j++) {
                        if (j > 0) {
                            b.append(',');
                        }
                        str(b, om.parameters().get(j));
                    }
                    b.append("],\"root\":").append(om.root());
                    b.append(",\"sourceInformation\":");
                    srcInfo(b, om.sourceInformation());
                    b.append('}');
                }
            }
        }
        b.append("],\"enumerationMappings\":[");
        for (int i = 0; i < m.enumerationMappings().size(); i++) {
            if (i > 0) {
                b.append(',');
            }
            Protocol.PEnumerationMapping em = m.enumerationMappings().get(i);
            b.append("{\"enumValueMappings\":[");
            for (int j = 0; j < em.enumValueMappings().size(); j++) {
                if (j > 0) {
                    b.append(',');
                }
                Protocol.PEnumValueMapping vm = em.enumValueMappings().get(j);
                b.append("{\"enumValue\":");
                str(b, vm.enumValue());
                b.append(",\"sourceValues\":[");
                for (int k = 0; k < vm.sourceValues().size(); k++) {
                    if (k > 0) {
                        b.append(',');
                    }
                    Object v = vm.sourceValues().get(k).value();
                    if (v instanceof String sv) {
                        b.append("{\"_type\":\"stringSourceValue\",\"value\":");
                        str(b, sv);
                    } else {
                        b.append("{\"_type\":\"integerSourceValue\",\"value\":")
                                .append(v);
                    }
                    b.append('}');
                }
                b.append("]}");
            }
            b.append("],\"enumeration\":");
            pointer(b, em.enumeration());
            if (em.id() != null) {
                b.append(",\"id\":");
                str(b, em.id());
            }
            b.append(",\"sourceInformation\":");
            srcInfo(b, em.sourceInformation());
            b.append('}');
        }
        b.append("],\"includedMappings\":[");
        for (int i = 0; i < m.includedMappings().size(); i++) {
            if (i > 0) {
                b.append(',');
            }
            Protocol.PMappingInclude inc = m.includedMappings().get(i);
            b.append("{\"_type\":\"mappingIncludeMapping\","
                    + "\"includedMapping\":");
            str(b, inc.includedMapping());
            if (inc.sourceDatabasePath() != null) {
                b.append(",\"sourceDatabasePath\":");
                str(b, inc.sourceDatabasePath());
            }
            b.append(",\"sourceInformation\":");
            srcInfo(b, inc.sourceInformation());
            if (inc.targetDatabasePath() != null) {
                b.append(",\"targetDatabasePath\":");
                str(b, inc.targetDatabasePath());
            }
            b.append('}');
        }
        b.append("],\"name\":");
        str(b, m.name());
        b.append(",\"package\":");
        str(b, m.pkg());
        b.append(",\"sourceInformation\":");
        srcInfo(b, m.sourceInformation());
        b.append(",\"tests\":[]}");
    }

    private static void pureClassMapping(StringBuilder b,
            Protocol.PClassMappingPure cm) {
        b.append("{\"_type\":\"pureInstance\",\"class\":");
        str(b, cm.className());
        b.append(",\"classSourceInformation\":");
        srcInfo(b, cm.classSourceInformation());
        if (cm.filter() != null) {
            // ~filter lambda rides between the class span and id
            // (probe pure-filter)
            b.append(",\"filter\":{\"_type\":\"lambda\",\"body\":[");
            List<com.legend.protocol.spec.ValueSpecification> fb = cm.filter();
            for (int i = 0; i < fb.size(); i++) {
                if (i > 0) {
                    b.append(',');
                }
                valueSpec(b, fb.get(i));
            }
            b.append("],\"parameters\":[]}");
        }
        if (cm.id() != null) {
            b.append(",\"id\":");
            str(b, cm.id());
        }
        b.append(",\"propertyMappings\":[");
        for (int i = 0; i < cm.propertyMappings().size(); i++) {
            if (i > 0) {
                b.append(',');
            }
            Protocol.PPurePropertyMapping pm = cm.propertyMappings().get(i);
            b.append("{\"_type\":\"purePropertyMapping\",");
            if (pm.enumMappingId() != null) {
                b.append("\"enumMappingId\":");
                str(b, pm.enumMappingId());
                b.append(',');
            }
            b.append("\"explodeProperty\":").append(pm.explodeProperty());
            if (pm.localMappingProperty() != null) {
                Protocol.PLocalProp lp = pm.localMappingProperty();
                b.append(",\"localMappingProperty\":{\"multiplicity\":"
                        + "{\"lowerBound\":");
                b.append(lp.lowerBound());
                if (lp.upperBound() != null) {
                    b.append(",\"upperBound\":").append(lp.upperBound());
                }
                b.append("},\"sourceInformation\":");
                srcInfo(b, lp.sourceInformation());
                b.append(",\"type\":");
                str(b, lp.type());
                b.append('}');
            }
            b.append(",\"property\":{");
            if (pm.ownerClass() != null) {
                b.append("\"class\":");
                str(b, pm.ownerClass());
                b.append(',');
            }
            b.append("\"property\":");
            str(b, pm.property());
            b.append(",\"sourceInformation\":");
            srcInfo(b, pm.propertySourceInformation());
            b.append('}');
            if (pm.source() != null) {
                b.append(",\"source\":");
                str(b, pm.source());
            }
            b.append(",\"sourceInformation\":");
            srcInfo(b, pm.sourceInformation());
            if (pm.target() != null) {
                b.append(",\"target\":");
                str(b, pm.target());
            }
            b.append(",\"transform\":{\"_type\":\"lambda\",\"body\":[");
            for (int j = 0; j < pm.transform().size(); j++) {
                if (j > 0) {
                    b.append(',');
                }
                valueSpec(b, pm.transform().get(j));
            }
            b.append("],\"parameters\":[]}}");
        }
        b.append("],\"root\":").append(cm.root());
        if (cm.srcClass() != null && cm.sourceClassSourceInformation() != null) {
            b.append(",\"sourceClassSourceInformation\":");
            srcInfo(b, cm.sourceClassSourceInformation());
        }
        b.append(",\"sourceInformation\":");
        srcInfo(b, cm.sourceInformation());
        if (cm.srcClass() != null) {
            b.append(",\"srcClass\":");
            str(b, cm.srcClass());
        }
        b.append('}');
    }

    private static void relClassMapping(StringBuilder b,
            Protocol.PClassMappingRel cm) {
        b.append("{\"_type\":\"relational\",\"class\":");
        str(b, cm.className());
        b.append(",\"classSourceInformation\":");
        srcInfo(b, cm.classSourceInformation());
        b.append(",\"distinct\":").append(cm.distinct());
        if (cm.extendsClassMappingId() != null) {
            b.append(",\"extendsClassMappingId\":");
            str(b, cm.extendsClassMappingId());
        }
        if (cm.filter() != null) {
            b.append(",\"filter\":");
            filterMapping(b, cm.filter());
        }
        b.append(",\"groupBy\":[");
        for (int i = 0; i < cm.groupBy().size(); i++) {
            if (i > 0) {
                b.append(',');
            }
            relOp(b, cm.groupBy().get(i));
        }
        b.append(']');
        if (cm.id() != null) {
            b.append(",\"id\":");
            str(b, cm.id());
        }
        if (cm.mainTable() != null) {
            b.append(",\"mainTable\":");
            tablePtr(b, cm.mainTable());
        }
        b.append(",\"primaryKey\":[");
        for (int i = 0; i < cm.primaryKey().size(); i++) {
            if (i > 0) {
                b.append(',');
            }
            relOp(b, cm.primaryKey().get(i));
        }
        b.append("],\"propertyMappings\":[");
        for (int i = 0; i < cm.propertyMappings().size(); i++) {
            if (i > 0) {
                b.append(',');
            }
            relPropertyMapping(b, cm.propertyMappings().get(i));
        }
        b.append("],\"root\":").append(cm.root());
        b.append(",\"sourceInformation\":");
        srcInfo(b, cm.sourceInformation());
        b.append('}');
    }

    private static void relPropertyMapping(StringBuilder b,
            Protocol.PPropertyMapping pmi) {
        switch (pmi) {
            case Protocol.PRelPropertyMapping pm -> {
                b.append("{\"_type\":\"relationalPropertyMapping\",");
                if (pm.enumMappingId() != null) {
                    b.append("\"enumMappingId\":");
                    str(b, pm.enumMappingId());
                    b.append(',');
                }
                if (pm.localMappingProperty() != null) {
                    Protocol.PLocalProp lp = pm.localMappingProperty();
                    b.append("\"localMappingProperty\":{\"multiplicity\":"
                            + "{\"lowerBound\":");
                    b.append(lp.lowerBound());
                    if (lp.upperBound() != null) {
                        b.append(",\"upperBound\":").append(lp.upperBound());
                    }
                    b.append("},\"sourceInformation\":");
                    srcInfo(b, lp.sourceInformation());
                    b.append(",\"type\":");
                    str(b, lp.type());
                    b.append("},");
                }
                b.append("\"property\":{");
                if (pm.ownerClass() != null) {
                    b.append("\"class\":");
                    str(b, pm.ownerClass());
                    b.append(',');
                }
                b.append("\"property\":");
                str(b, pm.property());
                b.append(",\"sourceInformation\":");
                srcInfo(b, pm.propertySourceInformation());
                b.append("},\"relationalOperation\":");
                relOp(b, pm.relationalOperation());
                if (pm.source() != null) {
                    b.append(",\"source\":");
                    str(b, pm.source());
                }
                b.append(",\"sourceInformation\":");
                srcInfo(b, pm.sourceInformation());
                if (pm.target() != null) {
                    b.append(",\"target\":");
                    str(b, pm.target());
                }
                b.append('}');
            }
            case Protocol.PEmbeddedPropertyMapping em -> {
                b.append("{\"_type\":\"embeddedPropertyMapping\","
                        + "\"classMapping\":{\"_type\":\"embedded\"");
                if (em.id() != null) {
                    b.append(",\"id\":");
                    str(b, em.id());
                }
                b.append(",\"primaryKey\":[");
                for (int i = 0; i < em.primaryKey().size(); i++) {
                    if (i > 0) {
                        b.append(',');
                    }
                    relOp(b, em.primaryKey().get(i));
                }
                b.append("],\"propertyMappings\":[");
                for (int i = 0; i < em.propertyMappings().size(); i++) {
                    if (i > 0) {
                        b.append(',');
                    }
                    relPropertyMapping(b, em.propertyMappings().get(i));
                }
                b.append("],\"root\":false,\"sourceInformation\":");
                srcInfo(b, em.sourceInformation());
                b.append('}');
                if (em.id() != null) {
                    b.append(",\"id\":");
                    str(b, em.id());
                }
                b.append(",\"property\":{");
                if (em.ownerClass() != null) {
                    b.append("\"class\":");
                    str(b, em.ownerClass());
                    b.append(',');
                }
                b.append("\"property\":");
                str(b, em.property());
                b.append(",\"sourceInformation\":");
                srcInfo(b, em.propertySourceInformation());
                b.append("},\"sourceInformation\":");
                srcInfo(b, em.sourceInformation());
                if (em.id() != null) {
                    b.append(",\"target\":");
                    str(b, em.id());
                }
                b.append('}');
            }
            case Protocol.PInlineEmbeddedPropertyMapping ie -> {
                b.append("{\"_type\":\"inlineEmbeddedPropertyMapping\"");
                if (ie.id() != null) {
                    b.append(",\"id\":");
                    str(b, ie.id());
                }
                b.append(",\"property\":{");
                if (ie.ownerClass() != null) {
                    b.append("\"class\":");
                    str(b, ie.ownerClass());
                    b.append(',');
                }
                b.append("\"property\":");
                str(b, ie.property());
                b.append(",\"sourceInformation\":");
                srcInfo(b, ie.propertySourceInformation());
                b.append("},\"setImplementationId\":");
                str(b, ie.setImplementationId());
                b.append(",\"sourceInformation\":");
                srcInfo(b, ie.sourceInformation());
                b.append('}');
            }
            case Protocol.POtherwiseEmbeddedPropertyMapping oe -> {
                b.append("{\"_type\":\"otherwiseEmbeddedPropertyMapping\","
                        + "\"classMapping\":{\"_type\":\"embedded\"");
                if (oe.id() != null) {
                    b.append(",\"id\":");
                    str(b, oe.id());
                }
                b.append(",\"primaryKey\":[");
                for (int i = 0; i < oe.primaryKey().size(); i++) {
                    if (i > 0) {
                        b.append(',');
                    }
                    relOp(b, oe.primaryKey().get(i));
                }
                b.append("],\"propertyMappings\":[");
                for (int i = 0; i < oe.propertyMappings().size(); i++) {
                    if (i > 0) {
                        b.append(',');
                    }
                    relPropertyMapping(b, oe.propertyMappings().get(i));
                }
                b.append("],\"root\":false,\"sourceInformation\":");
                srcInfo(b, oe.classMappingSourceInformation());
                b.append("},\"otherwisePropertyMapping\":{"
                        + "\"_type\":\"relationalPropertyMapping\","
                        + "\"property\":{");
                if (oe.ownerClass() != null) {
                    b.append("\"class\":");
                    str(b, oe.ownerClass());
                    b.append(',');
                }
                b.append("\"property\":");
                str(b, oe.property());
                b.append(",\"sourceInformation\":");
                srcInfo(b, oe.propertySourceInformation());
                b.append("},\"relationalOperation\":");
                relOp(b, oe.otherwiseOp());
                b.append(",\"sourceInformation\":");
                srcInfo(b, oe.otherwiseOp().sourceInformation());
                b.append(",\"target\":");
                str(b, oe.otherwiseTarget());
                b.append("},\"property\":{");
                if (oe.ownerClass() != null) {
                    b.append("\"class\":");
                    str(b, oe.ownerClass());
                    b.append(',');
                }
                b.append("\"property\":");
                str(b, oe.property());
                b.append(",\"sourceInformation\":");
                srcInfo(b, oe.propertySourceInformation());
                b.append("},\"sourceInformation\":");
                srcInfo(b, oe.sourceInformation());
                b.append('}');
            }
        }
    }

    private static void joinPtr(StringBuilder b, Protocol.PJoinPtr jp) {
        b.append('{');
        String jdb = jp.db();
        if (jdb != null) {
            // bare @Join with NO db anywhere omits the key
            // (probe bare-no-db, island TestSimpleGrammar#218)
            b.append("\"db\":");
            str(b, jdb);
            b.append(',');
        }
        if (jp.joinType() != null) {
            b.append("\"joinType\":");
            str(b, jp.joinType());
            b.append(',');
        }
        b.append("\"name\":");
        str(b, jp.name());
        b.append(",\"sourceInformation\":");
        srcInfo(b, jp.sourceInformation());
        b.append('}');
    }

    private static void filterMapping(StringBuilder b,
            Protocol.PFilterMapping f) {
        b.append("{\"filter\":{\"db\":");
        str(b, f.db());
        b.append(",\"name\":");
        str(b, f.name());
        b.append("},\"joins\":[");
        for (int i = 0; i < f.joins().size(); i++) {
            if (i > 0) {
                b.append(',');
            }
            joinPtr(b, f.joins().get(i));
        }
        b.append("],\"sourceInformation\":");
        srcInfo(b, f.sourceInformation());
        b.append('}');
    }

    private static void tablePtr(StringBuilder b, Protocol.PTablePtr t) {
        b.append("{\"_type\":\"Table\"");
        String db = t.database();
        String mainDb = t.mainTableDb();
        if (db != null && mainDb != null) {
            // a table ref with NO db anywhere (bare mapping-embedded op)
            // OMITS both db keys (probe bare-no-db)
            b.append(",\"database\":");
            str(b, db);
            b.append(",\"mainTableDb\":");
            str(b, mainDb);
        }
        b.append(",\"schema\":");
        str(b, t.schema());
        b.append(",\"sourceInformation\":");
        srcInfo(b, t.sourceInformation());
        b.append(",\"table\":");
        str(b, t.table());
        b.append('}');
    }

    /** {@code _type:"relational"} — Database element (ZRelationalProbe). */
    private static void database(StringBuilder b, Protocol.PDatabase d) {
        b.append("{\"_type\":\"relational\",\"filters\":[");
        for (int i = 0; i < d.filters().size(); i++) {
            if (i > 0) {
                b.append(',');
            }
            Protocol.PDbFilter f = d.filters().get(i);
            b.append("{\"_type\":\"").append(f.filterType())
                    .append("\",\"name\":");
            str(b, f.name());
            b.append(",\"operation\":");
            relOp(b, f.operation());
            b.append(",\"sourceInformation\":");
            srcInfo(b, f.sourceInformation());
            b.append('}');
        }
        b.append("],\"includedStores\":[");
        for (int i = 0; i < d.includedStores().size(); i++) {
            if (i > 0) {
                b.append(',');
            }
            pointer(b, d.includedStores().get(i));
        }
        b.append("],\"joins\":[");
        for (int i = 0; i < d.joins().size(); i++) {
            if (i > 0) {
                b.append(',');
            }
            Protocol.PDbJoin j = d.joins().get(i);
            b.append("{\"name\":");
            str(b, j.name());
            b.append(",\"operation\":");
            relOp(b, j.operation());
            b.append(",\"sourceInformation\":");
            srcInfo(b, j.sourceInformation());
            b.append('}');
        }
        b.append("],\"name\":");
        str(b, d.name());
        b.append(",\"package\":");
        str(b, d.pkg());
        b.append(",\"schemas\":[");
        for (int i = 0; i < d.schemas().size(); i++) {
            if (i > 0) {
                b.append(',');
            }
            dbSchema(b, d.schemas().get(i));
        }
        b.append("],\"sourceInformation\":");
        srcInfo(b, d.sourceInformation());
        b.append(",\"stereotypes\":");
        stereotypes(b, d.stereotypes());
        b.append('}');
    }

    private static void dbSchema(StringBuilder b, Protocol.PDbSchema sc) {
        b.append("{\"name\":");
        str(b, sc.name());
        b.append(",\"sourceInformation\":");
        srcInfo(b, sc.sourceInformation());
        if (!sc.stereotypes().isEmpty()) {
            b.append(",\"stereotypes\":");
            stereotypes(b, sc.stereotypes());
        }
        b.append(",\"tables\":[");
        for (int i = 0; i < sc.tables().size(); i++) {
            if (i > 0) {
                b.append(',');
            }
            dbTable(b, sc.tables().get(i));
        }
        b.append("],\"tabularFunctions\":[");
        for (int i = 0; i < sc.tabularFunctions().size(); i++) {
            if (i > 0) {
                b.append(',');
            }
            Protocol.PDbTable tf = sc.tabularFunctions().get(i);
            // slim wire: columns + name + span only (probe)
            b.append('{');
            dbColumns(b, tf.columns());
            b.append(",\"name\":");
            str(b, tf.name());
            b.append(",\"sourceInformation\":");
            srcInfo(b, tf.sourceInformation());
            b.append('}');
        }
        b.append(']');
        if (!sc.taggedValues().isEmpty()) {
            b.append(",\"taggedValues\":");
            taggedValues(b, sc.taggedValues());
        }
        b.append(",\"views\":[");
        for (int i = 0; i < sc.views().size(); i++) {
            if (i > 0) {
                b.append(',');
            }
            dbView(b, sc.views().get(i));
        }
        b.append("]}");
    }

    private static void dbTable(StringBuilder b, Protocol.PDbTable t) {
        b.append('{');
        dbColumns(b, t.columns());
        b.append(",\"milestoning\":[");
        emitTableTail(b, t);
    }

    private static void dbColumns(StringBuilder b, List<Protocol.PDbColumn> cols) {
        b.append("\"columns\":[");
        for (int i = 0; i < cols.size(); i++) {
            if (i > 0) {
                b.append(',');
            }
            Protocol.PDbColumn c = cols.get(i);
            b.append("{\"name\":");
            str(b, c.name());
            b.append(",\"nullable\":").append(c.nullable());
            b.append(",\"sourceInformation\":");
            srcInfo(b, c.sourceInformation());
            if (!c.stereotypes().isEmpty()) {
                b.append(",\"stereotypes\":");
                stereotypes(b, c.stereotypes());
            }
            if (!c.taggedValues().isEmpty()) {
                b.append(",\"taggedValues\":");
                taggedValues(b, c.taggedValues());
            }
            b.append(",\"type\":{\"_type\":\"").append(c.type().kind())
                    .append('\"');
            if (c.type().precision() != null) {
                b.append(",\"precision\":").append(c.type().precision());
            }
            if (c.type().scale() != null) {
                b.append(",\"scale\":").append(c.type().scale());
            }
            if (c.type().size() != null) {
                b.append(",\"size\":").append(c.type().size());
            }
            b.append("}}");
        }
        b.append(']');
    }

    private static void emitTableTail(StringBuilder b, Protocol.PDbTable t) {
        for (int i = 0; i < t.milestoning().size(); i++) {
            if (i > 0) {
                b.append(',');
            }
            milestoning(b, t.milestoning().get(i));
        }
        b.append("],\"name\":");
        str(b, t.name());
        b.append(",\"primaryKey\":[");
        for (int i = 0; i < t.primaryKey().size(); i++) {
            if (i > 0) {
                b.append(',');
            }
            str(b, t.primaryKey().get(i));
        }
        b.append("],\"sourceInformation\":");
        srcInfo(b, t.sourceInformation());
        if (!t.stereotypes().isEmpty()) {
            b.append(",\"stereotypes\":");
            stereotypes(b, t.stereotypes());
        }
        if (!t.taggedValues().isEmpty()) {
            b.append(",\"taggedValues\":");
            taggedValues(b, t.taggedValues());
        }
        b.append('}');
    }

    private static void milestoning(StringBuilder b, Protocol.PMilestoning m) {
        switch (m) {
            case Protocol.PBusinessMilestoning bm -> {
                b.append("{\"_type\":\"businessMilestoning\",\"from\":");
                str(b, bm.from());
                if (bm.infinityDate() != null) {
                    b.append(",\"infinityDate\":");
                    dateTimeLit(b, bm.infinityDate());
                }
                b.append(",\"sourceInformation\":");
                srcInfo(b, bm.sourceInformation());
                b.append(",\"thru\":");
                str(b, bm.thru());
                b.append(",\"thruIsInclusive\":").append(bm.thruIsInclusive());
                b.append('}');
            }
            case Protocol.PProcessingSnapshotMilestoning psm -> {
                b.append("{\"_type\":\"processingSnapshotMilestoning\","
                        + "\"snapshotDate\":");
                str(b, psm.snapshotDate());
                b.append(",\"sourceInformation\":");
                srcInfo(b, psm.sourceInformation());
                b.append('}');
            }
            case Protocol.PBusinessSnapshotMilestoning sm -> {
                b.append("{\"_type\":\"businessSnapshotMilestoning\","
                        + "\"snapshotDate\":");
                str(b, sm.snapshotDate());
                b.append(",\"sourceInformation\":");
                srcInfo(b, sm.sourceInformation());
                b.append('}');
            }
            case Protocol.PProcessingMilestoning pm -> {
                b.append("{\"_type\":\"processingMilestoning\",\"in\":");
                str(b, pm.in());
                if (pm.infinityDate() != null) {
                    b.append(",\"infinityDate\":");
                    dateTimeLit(b, pm.infinityDate());
                }
                b.append(",\"out\":");
                str(b, pm.out());
                b.append(",\"outIsInclusive\":").append(pm.outIsInclusive());
                b.append(",\"sourceInformation\":");
                srcInfo(b, pm.sourceInformation());
                b.append('}');
            }
        }
    }

    private static void dateTimeLit(StringBuilder b, Protocol.PDateTimeLit d) {
        b.append("{\"_type\":\"").append(d.wireType())
                .append("\",\"sourceInformation\":");
        srcInfo(b, d.sourceInformation());
        b.append(",\"value\":");
        str(b, d.value());
        b.append('}');
    }

    private static void dbView(StringBuilder b, Protocol.PDbView v) {
        b.append("{\"columnMappings\":[");
        for (int i = 0; i < v.columnMappings().size(); i++) {
            if (i > 0) {
                b.append(',');
            }
            Protocol.PViewColumnMapping cm = v.columnMappings().get(i);
            b.append("{\"name\":");
            str(b, cm.name());
            b.append(",\"operation\":");
            relOp(b, cm.operation());
            b.append(",\"sourceInformation\":");
            srcInfo(b, cm.sourceInformation());
            b.append('}');
        }
        b.append("],\"distinct\":").append(v.distinct());
        if (v.filter() != null) {
            b.append(",\"filter\":{\"filter\":{\"name\":");
            str(b, v.filter().name());
            b.append("},\"joins\":[],\"sourceInformation\":");
            srcInfo(b, v.filter().sourceInformation());
            b.append('}');
        }
        b.append(",\"groupBy\":[");
        if (v.groupBy() != null) {
            for (int i = 0; i < v.groupBy().size(); i++) {
                if (i > 0) {
                    b.append(',');
                }
                relOp(b, v.groupBy().get(i));
            }
        }
        b.append(']');
        b.append(",\"name\":");
        str(b, v.name());
        b.append(",\"primaryKey\":[");
        for (int i = 0; i < v.primaryKey().size(); i++) {
            if (i > 0) {
                b.append(',');
            }
            str(b, v.primaryKey().get(i));
        }
        b.append("],\"sourceInformation\":");
        srcInfo(b, v.sourceInformation());
        if (!v.stereotypes().isEmpty()) {
            b.append(",\"stereotypes\":");
            stereotypes(b, v.stereotypes());
        }
        if (!v.taggedValues().isEmpty()) {
            b.append(",\"taggedValues\":");
            taggedValues(b, v.taggedValues());
        }
        b.append('}');
    }

    private static void relOp(StringBuilder b, Protocol.PRelOp op) {
        switch (op) {
            case Protocol.PDynaFunc f -> {
                b.append("{\"_type\":\"dynaFunc\",\"funcName\":");
                str(b, f.funcName());
                b.append(",\"parameters\":[");
                for (int i = 0; i < f.parameters().size(); i++) {
                    if (i > 0) {
                        b.append(',');
                    }
                    relOp(b, f.parameters().get(i));
                }
                b.append("],\"sourceInformation\":");
                srcInfo(b, f.sourceInformation());
                b.append('}');
            }
            case Protocol.PColumnRef c -> {
                b.append("{\"_type\":\"column\",\"column\":");
                str(b, c.column());
                b.append(",\"sourceInformation\":");
                srcInfo(b, c.sourceInformation());
                b.append(",\"table\":");
                tablePtr(b, c.table());
                b.append(",\"tableAlias\":");
                str(b, c.tableAlias());
                b.append('}');
            }
            case Protocol.PElemtWithJoins ej -> {
                b.append("{\"_type\":\"elemtWithJoins\",\"joins\":[");
                for (int i = 0; i < ej.joins().size(); i++) {
                    if (i > 0) {
                        b.append(',');
                    }
                    joinPtr(b, ej.joins().get(i));
                }
                b.append(']');
                if (ej.relationalElement() != null) {
                    b.append(",\"relationalElement\":");
                    relOp(b, ej.relationalElement());
                }
                b.append(",\"sourceInformation\":");
                srcInfo(b, ej.sourceInformation());
                b.append('}');
            }
            case Protocol.PRelLiteralList ll -> {
                b.append("{\"_type\":\"literalList\",\"sourceInformation\":");
                srcInfo(b, ll.sourceInformation());
                b.append(",\"values\":[");
                for (int i = 0; i < ll.values().size(); i++) {
                    if (i > 0) {
                        b.append(',');
                    }
                    Protocol.PRelLiteral it = ll.values().get(i);
                    b.append("{\"_type\":\"literal\",\"value\":"
                            + "{\"sourceInformation\":");
                    srcInfo(b, it.sourceInformation());
                    b.append(",\"value\":");
                    if (it.value() instanceof String sv) {
                        str(b, sv);
                    } else {
                        b.append(it.value());
                    }
                    b.append("}}");
                }
                b.append("]}");
            }
            case Protocol.PRelLiteral l -> {
                b.append("{\"_type\":\"literal\",\"sourceInformation\":");
                srcInfo(b, l.sourceInformation());
                b.append(",\"value\":");
                if (l.value() instanceof String sv) {
                    str(b, sv);
                } else {
                    b.append(l.value());
                }
                b.append('}');
            }
        }
    }

    /** {@code _type:"connection"} envelope — element and value share one
     *  span (ZConnectionProbe). */
    private static void connection(StringBuilder b, Protocol.PConnection c) {
        b.append("{\"_type\":\"connection\",\"connectionValue\":");
        connectionValue(b, c.value());
        b.append(",\"name\":");
        str(b, c.name());
        b.append(",\"package\":");
        str(b, c.pkg());
        b.append(",\"sourceInformation\":");
        srcInfo(b, c.sourceInformation());
        b.append('}');
    }

    private static void connectionValue(StringBuilder b,
            Protocol.PConnectionValue v) {
        switch (v) {
            case Protocol.PConnectionPointer cp -> {
                b.append("{\"_type\":\"connectionPointer\",\"connection\":");
                str(b, cp.connection());
                b.append(",\"sourceInformation\":");
                srcInfo(b, cp.sourceInformation());
                b.append('}');
            }
            case Protocol.PJsonModelConnection jc -> modelConnection(b,
                    "JsonModelConnection", jc.className(),
                    jc.classSourceInformation(), jc.element(), jc.url(),
                    jc.sourceInformation());
            case Protocol.PXmlModelConnection xc -> modelConnection(b,
                    "XmlModelConnection", xc.className(),
                    xc.classSourceInformation(), xc.element(), xc.url(),
                    xc.sourceInformation());
            case Protocol.PModelChainConnection mc -> {
                b.append("{\"_type\":\"ModelChainConnection\"");
                if (mc.element() != null) {
                    b.append(",\"element\":");
                    str(b, mc.element());
                }
                b.append(",\"mappings\":[");
                for (int i = 0; i < mc.mappings().size(); i++) {
                    if (i > 0) {
                        b.append(',');
                    }
                    str(b, mc.mappings().get(i));
                }
                b.append("],\"mappingsSourceInformation\":");
                srcInfo(b, mc.mappingsSourceInformation());
                b.append(",\"sourceInformation\":");
                srcInfo(b, mc.sourceInformation());
                b.append('}');
            }
            case Protocol.PRelationalDatabaseConnection rc -> {
                b.append("{\"_type\":\"RelationalDatabaseConnection\","
                        + "\"authenticationStrategy\":");
                authStrategy(b, rc.authenticationStrategy());
                b.append(",\"databaseType\":");
                str(b, rc.databaseType());
                b.append(",\"datasourceSpecification\":");
                datasourceSpec(b, rc.datasourceSpecification());
                if (rc.element() != null && rc.elementSourceInformation() != null) {
                    b.append(",\"element\":");
                    str(b, rc.element());
                    b.append(",\"elementSourceInformation\":");
                    srcInfo(b, rc.elementSourceInformation());
                }
                b.append(",\"postProcessorWithParameter\":[]");
                if (!rc.postProcessors().isEmpty()) {
                    b.append(",\"postProcessors\":[");
                    for (int i = 0; i < rc.postProcessors().size(); i++) {
                        if (i > 0) {
                            b.append(',');
                        }
                        b.append("{\"_type\":\"mapper\",\"mappers\":[");
                        List<Protocol.PMapper> ms =
                                rc.postProcessors().get(i).mappers();
                        for (int j = 0; j < ms.size(); j++) {
                            if (j > 0) {
                                b.append(',');
                            }
                            mapper(b, ms.get(j));
                        }
                        b.append("]}");
                    }
                    b.append(']');
                }
                b.append(",\"sourceInformation\":");
                srcInfo(b, rc.sourceInformation());
                if (rc.timeZone() != null) {
                    b.append(",\"timeZone\":");
                    str(b, rc.timeZone());
                }
                b.append(",\"type\":");
                str(b, rc.databaseType());
                b.append('}');
            }
        }
    }

    private static void modelConnection(StringBuilder b, String type,
            String className, SourceInfo classSpan,
            @com.legend.Nullable String element, String url, SourceInfo span) {
        b.append("{\"_type\":\"").append(type).append("\",\"class\":");
        str(b, className);
        b.append(",\"classSourceInformation\":");
        srcInfo(b, classSpan);
        if (element != null) {
            b.append(",\"element\":");
            str(b, element);
        }
        b.append(",\"sourceInformation\":");
        srcInfo(b, span);
        b.append(",\"url\":");
        str(b, url);
        b.append('}');
    }

    private static void authStrategy(StringBuilder b, Protocol.PAuthStrategy a) {
        switch (a) {
            case Protocol.PH2Default h -> {
                b.append("{\"_type\":\"h2Default\",\"sourceInformation\":");
                srcInfo(b, h.sourceInformation());
                b.append('}');
            }
            case Protocol.PTestAuth t -> {
                b.append("{\"_type\":\"test\",\"sourceInformation\":");
                srcInfo(b, t.sourceInformation());
                b.append('}');
            }
            case Protocol.PUserNamePassword u -> {
                b.append("{\"_type\":\"userNamePassword\"");
                if (u.baseVaultReference() != null) {
                    b.append(",\"baseVaultReference\":");
                    str(b, u.baseVaultReference());
                }
                b.append(",\"passwordVaultReference\":");
                str(b, u.passwordVaultReference());
                b.append(",\"sourceInformation\":");
                srcInfo(b, u.sourceInformation());
                b.append(",\"userNameVaultReference\":");
                str(b, u.userNameVaultReference());
                b.append('}');
            }
            case Protocol.PDelegatedKerberos k -> {
                b.append("{\"_type\":\"delegatedKerberos\"");
                if (k.serverPrincipal() != null) {
                    b.append(",\"serverPrincipal\":");
                    str(b, k.serverPrincipal());
                }
                b.append(",\"sourceInformation\":");
                srcInfo(b, k.sourceInformation());
                b.append('}');
            }
        }
    }

    private static void mapper(StringBuilder b, Protocol.PMapper m) {
        switch (m) {
            case Protocol.PTableMapper t -> {
                b.append("{\"_type\":\"table\",\"from\":");
                str(b, t.from());
                b.append(",\"schema\":{\"_type\":\"schema\",\"from\":");
                str(b, t.schemaFrom());
                b.append(",\"to\":");
                str(b, t.schemaTo());
                b.append("},\"to\":");
                str(b, t.to());
                b.append('}');
            }
            case Protocol.PSchemaMapper sm -> {
                b.append("{\"_type\":\"schema\",\"from\":");
                str(b, sm.from());
                b.append(",\"to\":");
                str(b, sm.to());
                b.append('}');
            }
        }
    }

    private static void datasourceSpec(StringBuilder b, Protocol.PDatasourceSpec d) {
        switch (d) {
            case Protocol.PH2Local h -> {
                b.append("{\"_type\":\"h2Local\",\"sourceInformation\":");
                srcInfo(b, h.sourceInformation());
                if (h.testDataSetupCsv() != null) {
                    b.append(",\"testDataSetupCsv\":");
                    str(b, h.testDataSetupCsv());
                }
                if (h.testDataSetupSqls() != null) {
                    b.append(",\"testDataSetupSqls\":[");
                    for (int i = 0; i < h.testDataSetupSqls().size(); i++) {
                        if (i > 0) {
                            b.append(',');
                        }
                        str(b, h.testDataSetupSqls().get(i));
                    }
                    b.append(']');
                }
                b.append('}');
            }
            case Protocol.PStaticSpec st -> {
                b.append("{\"_type\":\"static\",\"databaseName\":");
                str(b, st.databaseName());
                b.append(",\"host\":");
                str(b, st.host());
                b.append(",\"port\":").append(st.port());
                b.append(",\"sourceInformation\":");
                srcInfo(b, st.sourceInformation());
                b.append('}');
            }
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
        // FUNCTION constraint blocks are parsed upstream but NEVER serialized — the
        // engine emits empty pre/postConstraints regardless (verified on the
        // AbstractTestConstraints corpus). We keep the parse product for our own
        // compiler and match the drop on the wire.

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
        b.append(",\"tests\":[");
        for (int i = 0; i < f.testSuites().size(); i++) {
            if (i > 0) {
                b.append(',');
            }
            testSuite(b, f.testSuites().get(i));
        }
        b.append("]}");
    }

    /**
     * One {@code functionTestSuite} (probes "fn tests wire", "fn tests named suite"):
     * unnamed blocks serialize as id {@code "default"}; each test's single assertion is an
     * {@code equalTo} with id {@code "default"} spanning the expected value; the
     * {@code parameters} key appears only when the test call passes arguments.
     */
    private static void testSuite(StringBuilder b, Protocol.PTestSuite s) {
        b.append("{\"_type\":\"functionTestSuite\",\"id\":");
        str(b, s.id() != null ? s.id() : "default");
        b.append(",\"sourceInformation\":");
        srcInfo(b, s.sourceInformation());
        b.append(",\"testData\":[");
        for (int i = 0; i < s.testData().size(); i++) {
            if (i > 0) {
                b.append(',');
            }
            Protocol.PTestData d = s.testData().get(i);
            b.append("{\"data\":");
            testPayload(b, d.data());
            b.append(",\"packageableElementPointer\":{\"path\":");
            str(b, d.storePath());
            b.append(",\"sourceInformation\":");
            srcInfo(b, d.storeSpan());
            b.append("},\"sourceInformation\":");
            srcInfo(b, d.sourceInformation());
            b.append('}');
        }
        b.append("],\"tests\":[");
        for (int i = 0; i < s.tests().size(); i++) {
            if (i > 0) {
                b.append(',');
            }
            Protocol.PFunctionTest t = s.tests().get(i);
            b.append("{\"_type\":\"functionTest\",\"assertions\":[");
            assertion(b, t.assertion());
            b.append("],\"id\":");
            str(b, t.id());
            if (!t.parameters().isEmpty()) {
                b.append(",\"parameters\":[");
                for (int k = 0; k < t.parameters().size(); k++) {
                    if (k > 0) {
                        b.append(',');
                    }
                    Protocol.PTestParam pa = t.parameters().get(k);
                    b.append('{');
                    // an argument BEYOND the signature has no name key at all
                    // (probe "pf extra test arg")
                    if (pa.name() != null) {
                        b.append("\"name\":");
                        str(b, pa.name());
                        b.append(',');
                    }
                    b.append("\"sourceInformation\":");
                    srcInfo(b, pa.sourceInformation());
                    b.append(",\"value\":");
                    valueSpec(b, pa.value());
                    b.append('}');
                }
                b.append(']');
            }
            b.append(",\"sourceInformation\":");
            srcInfo(b, t.sourceInformation());
            b.append('}');
        }
        b.append("]}");
    }

    /** The three assertion spellings (probes "fn tests wire", "pf fmt expected and
     *  data", "pf relation expected"); assertion id is always {@code "default"}. */
    private static void assertion(StringBuilder b, Protocol.PAssertion a) {
        switch (a) {
            case Protocol.PAssertion.EqualTo eq -> {
                b.append("{\"_type\":\"equalTo\",\"expected\":");
                valueSpec(b, eq.expected());
                b.append(",\"id\":\"default\",\"sourceInformation\":");
                srcInfo(b, eq.span());
                b.append('}');
            }
            case Protocol.PAssertion.EqualToJson ej -> {
                b.append("{\"_type\":\"equalToJson\",\"expected\":");
                testPayload(b, ej.expected());
                b.append(",\"id\":\"default\",\"sourceInformation\":");
                srcInfo(b, ej.span());
                b.append('}');
            }
            case Protocol.PAssertion.EqualToRelation er -> {
                b.append("{\"_type\":\"equalToRelation\",\"expected\":");
                relationElement(b, er.expected());
                b.append(",\"id\":\"default\",\"sourceInformation\":");
                srcInfo(b, er.span());
                b.append('}');
            }
        }
    }

    private static void testPayload(StringBuilder b, Protocol.PTestPayload p) {
        switch (p) {
            case Protocol.PTestPayload.ExternalFormat ef -> {
                b.append("{\"_type\":\"externalFormat\",\"contentType\":");
                str(b, ef.contentType());
                b.append(",\"data\":");
                str(b, ef.data());
                b.append(",\"sourceInformation\":");
                srcInfo(b, ef.sourceInformation());
                b.append('}');
            }
            case Protocol.PTestPayload.Reference r -> {
                b.append("{\"_type\":\"reference\",\"dataElement\":{\"path\":");
                str(b, r.path());
                b.append(",\"sourceInformation\":");
                srcInfo(b, r.sourceInformation());
                b.append(",\"type\":\"DATA\"},\"sourceInformation\":");
                srcInfo(b, r.sourceInformation());
                b.append('}');
            }
            case Protocol.PTestPayload.RelationElements re -> {
                b.append("{\"_type\":\"relationAccessor\",\"relationElements\":[");
                for (int i = 0; i < re.elements().size(); i++) {
                    if (i > 0) {
                        b.append(',');
                    }
                    relationElement(b, re.elements().get(i));
                }
                b.append("],\"sourceInformation\":");
                srcInfo(b, re.sourceInformation());
                b.append('}');
            }
            case Protocol.PTestPayload.ModelStoreData ms -> {
                // probe "pf modelstore island"
                b.append("{\"_type\":\"modelStore\",\"modelData\":[");
                for (int i = 0; i < ms.modelData().size(); i++) {
                    if (i > 0) {
                        b.append(',');
                    }
                    Protocol.PTestPayload.ModelEmbedded me = ms.modelData().get(i);
                    b.append("{\"_type\":\"modelEmbeddedData\",\"data\":");
                    testPayload(b, me.data());
                    b.append(",\"model\":");
                    str(b, me.model());
                    b.append(",\"sourceInformation\":");
                    srcInfo(b, me.sourceInformation());
                    b.append('}');
                }
                b.append("],\"sourceInformation\":");
                srcInfo(b, ms.sourceInformation());
                b.append('}');
            }
            case Protocol.PTestPayload.RelationalCsv rc -> {
                // probe "pf relational island"
                b.append("{\"_type\":\"relationalCSVData\",\"sourceInformation\":");
                srcInfo(b, rc.sourceInformation());
                b.append(",\"tables\":[");
                for (int i = 0; i < rc.tables().size(); i++) {
                    if (i > 0) {
                        b.append(',');
                    }
                    Protocol.PTestPayload.CsvTable t = rc.tables().get(i);
                    b.append("{\"schema\":");
                    str(b, t.schema());
                    b.append(",\"sourceInformation\":");
                    srcInfo(b, t.sourceInformation());
                    b.append(",\"table\":");
                    str(b, t.table());
                    b.append(",\"values\":");
                    str(b, t.values());
                    b.append('}');
                }
                b.append("]}");
            }
        }
    }

    /** The bare columns/paths/rows shape — every cell a STRING. */
    private static void relationElement(StringBuilder b,
            Protocol.PTestPayload.RelationElement el) {
        b.append("{\"columns\":[");
        for (int i = 0; i < el.columns().size(); i++) {
            if (i > 0) {
                b.append(',');
            }
            str(b, el.columns().get(i));
        }
        b.append("],\"paths\":[");
        for (int i = 0; i < el.paths().size(); i++) {
            if (i > 0) {
                b.append(',');
            }
            str(b, el.paths().get(i));
        }
        b.append("],\"rows\":[");
        for (int i = 0; i < el.rows().size(); i++) {
            if (i > 0) {
                b.append(',');
            }
            b.append("{\"values\":[");
            List<String> row = el.rows().get(i);
            for (int k = 0; k < row.size(); k++) {
                if (k > 0) {
                    b.append(',');
                }
                str(b, row.get(k));
            }
            b.append("]}");
        }
        b.append("],\"sourceInformation\":");
        srcInfo(b, el.sourceInformation());
        b.append('}');
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
        if (p.aggregation() != null) {
            // (shared)/(composite)/(none) — alphabetically FIRST
            // (ProbeWireShapes "agg kind and varchar")
            b.append("\"aggregation\":");
            str(b, p.aggregation());
            b.append(',');
        }
        if (p.defaultValue() != null) {
            // Alphabetically next among the property's fields. Outer span covers the whole
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
                    genericTypeOf(b, n.name(), java.util.List.of(), java.util.List.of(),
                            java.util.List.of(), n.pos());
            case com.legend.protocol.TypeExpression.Generic g ->
                    genericTypeOf(b, g.name(), g.arguments(), g.multiplicityArguments(),
                            g.typeVariableValues(), g.pos());
            // (col:Type, ...): relationType rawType; neither the wrapper genericType nor
            // the relationType carries a span; undeclared column multiplicity is 0..1 ON
            // THE WIRE; column span = name (quotes included) .. type end (ProbeWireShapes
            // "relation type sigs")
            case com.legend.protocol.TypeExpression.RelationType rt -> {
                b.append("{\"multiplicityArguments\":[],\"rawType\":{\"_type\":\"relationType\",\"columns\":[");
                for (int i = 0; i < rt.columns().size(); i++) {
                    if (i > 0) {
                        b.append(',');
                    }
                    com.legend.protocol.TypeExpression.Column col = rt.columns().get(i);
                    b.append("{\"genericType\":");
                    genericType(b, col.type());
                    b.append(",\"multiplicity\":");
                    if (col.multiplicityDeclared()) {
                        // declared -> emitted as declared, column span extends through
                        // the ']' (probe "declared col mult and relation shape")
                        multiplicity(b, col.multiplicity());
                    } else {
                        b.append("{\"lowerBound\":0,\"upperBound\":1}");
                    }
                    b.append(",\"name\":");
                    str(b, col.name());
                    b.append(",\"sourceInformation\":");
                    srcInfo(b, requirePos(col.pos(), "relation column " + col.name()));
                    b.append('}');
                }
                b.append("]},\"typeArguments\":[],\"typeVariableValues\":[]}");
            }
            default -> throw new UnsupportedOperationException(
                    "ProtocolEmitter has no rule for type expression "
                            + t.getClass().getSimpleName() + " — add the emit rule, do not drop it.");
        }
    }

    private static void genericTypeOf(StringBuilder b, String path,
                                      List<com.legend.protocol.TypeExpression> args,
                                      List<String> multArgs,
                                      List<com.legend.protocol.spec.ValueSpecification> typeVarValues,
                                      com.legend.protocol.@com.legend.Nullable SourceInfo pos) {
        if (pos == null) {
            throw new UnsupportedOperationException(
                    "ProtocolEmitter needs a source position for type " + path
                            + " and the parser did not thread one — fix the parse site, do not default it.");
        }
        // engine backward-compat (DomainParseTreeWalker.processType; probe "bare result
        // type"): a bare 'Result' defaults to <meta::pure::metamodel::type::Any|1> — the
        // synthesized Any carries NO span, the multiplicity no upper bound
        if (path.equals("Result") && args.isEmpty() && multArgs.isEmpty()) {
            b.append("{\"multiplicityArguments\":[{\"lowerBound\":1}],"
                    + "\"rawType\":{\"_type\":\"packageableType\",\"fullPath\":\"Result\","
                    + "\"sourceInformation\":");
            srcInfo(b, pos);
            b.append("},\"typeArguments\":[{\"multiplicityArguments\":[],"
                    + "\"rawType\":{\"_type\":\"packageableType\","
                    + "\"fullPath\":\"meta::pure::metamodel::type::Any\"},"
                    + "\"typeArguments\":[],\"typeVariableValues\":[]}],"
                    + "\"typeVariableValues\":[]}");
            return;
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
        b.append("],\"typeVariableValues\":[");
        for (int i = 0; i < typeVarValues.size(); i++) {
            if (i > 0) {
                b.append(',');
            }
            valueSpec(b, typeVarValues.get(i));
        }
        b.append("]}");
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
        List<com.legend.protocol.spec.ValueSpecification> cbody = realizationBody(
                c.realization(), "constraint " + c.name());
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
        thisLambda(b, cbody);
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
        // a bare-reference body ({ok}) classifies as Ref for the MODEL layer; the wire
        // serializes it as the plain expression (inline-snippet corpus)
        List<com.legend.protocol.spec.ValueSpecification> qbody = realizationBody(
                d.realization(), "qualified property " + d.name());
        b.append("{\"body\":[");
        for (int i = 0; i < qbody.size(); i++) {
            if (i > 0) {
                b.append(',');
            }
            valueSpec(b, qbody.get(i));
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
                // the ROOT PACKAGE spelled '::' (fold(..., ::)) reaches the engine's
                // serializer as a Java null — the wire carries LITERAL null (probe
                // "unit sig and root package" b). Reproduced, not questioned.
                if (ptr.fullPath().equals("::")) {
                    b.append("null");
                    break;
                }
                // a TILDE name (Mass~Kilogram) is a UNIT reference — same shape,
                // _type "unitType" (probe "unit type refs"); '~' is only legal there
                b.append(ptr.fullPath().indexOf('~') >= 0
                        ? "{\"_type\":\"unitType\",\"fullPath\":"
                        : "{\"_type\":\"packageableElementPtr\",\"fullPath\":");
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
                // a UNIT type here (cast(@Mass~Pound)) loses its rawType span — engine
                // wart, unlike signature position (inline corpus AbstractTestMeasure)
                if (named.type() instanceof com.legend.protocol.TypeExpression.NameRef un
                        && un.name().indexOf('~') >= 0) {
                    b.append("{\"multiplicityArguments\":[],\"rawType\":{\"_type\":\"packageableType\",\"fullPath\":");
                    str(b, un.name());
                    // the OUTER span also excludes the '@' here — it is the NAME span
                    b.append("},\"typeArguments\":[],\"typeVariableValues\":[]}");
                    b.append(",\"sourceInformation\":");
                    srcInfo(b, requirePos(un.pos(), "unit type annotation " + un.name()));
                } else {
                    genericType(b, named.type());
                    b.append(",\"sourceInformation\":");
                    srcInfo(b, requirePos(named.pos(), "@-type annotation"));
                }
                b.append('}');
            }
            // simple-name @Relation<(...)>: genericTypeInstance whose rawType is the
            // literal "Relation" with span over the whole application; columns as in
            // signature position (probe "simple relation cast")
            case com.legend.protocol.spec.TypeAnnotation.RelationShape rs -> {
                b.append("{\"_type\":\"genericTypeInstance\",\"genericType\":{\"multiplicityArguments\":[],"
                        + "\"rawType\":{\"_type\":\"packageableType\",\"fullPath\":");
                // fullPath is the name AS SPELLED — simple or FQN (inline-snippet corpus)
                str(b, java.util.Objects.requireNonNull(rs.spelledName(),
                        "@Relation<(...)> spelled name"));
                b.append(",\"sourceInformation\":");
                srcInfo(b, requirePos(rs.typeSpan(), "@Relation<(...)> type"));
                b.append("},\"typeArguments\":[{\"multiplicityArguments\":[],\"rawType\":{\"_type\":\"relationType\",\"columns\":[");
                for (int i = 0; i < rs.columns().size(); i++) {
                    if (i > 0) {
                        b.append(',');
                    }
                    com.legend.protocol.spec.TypeAnnotation.RelationShape.Column col =
                            rs.columns().get(i);
                    if (!(col.type() instanceof com.legend.protocol.spec.TypeAnnotation.Named nt)
                            || col.name() == null) {
                        throw new UnsupportedOperationException(
                                "ProtocolEmitter has no rule for wildcard @Relation columns"
                                        + " (wire shape unprobed) — probe, do not guess.");
                    }
                    b.append("{\"genericType\":");
                    genericType(b, nt.type());
                    b.append(",\"multiplicity\":");
                    if (col.multiplicity() != null) {
                        multiplicity(b, col.multiplicity());
                    } else {
                        b.append("{\"lowerBound\":0,\"upperBound\":1}");
                    }
                    b.append(",\"name\":");
                    str(b, col.name());
                    b.append(",\"sourceInformation\":");
                    srcInfo(b, requirePos(col.pos(), "@Relation column " + col.name()));
                    b.append('}');
                }
                b.append("]},\"typeArguments\":[],\"typeVariableValues\":[]}],\"typeVariableValues\":[]},\"sourceInformation\":");
                srcInfo(b, requirePos(rs.pos(), "@Relation annotation"));
                b.append('}');
            }
            case com.legend.protocol.spec.NewInstance ni -> newInstance(b, ni, null);
            case com.legend.protocol.spec.ColSpec cs -> colSpec(b, cs);
            case com.legend.protocol.spec.ColSpecArray ca -> colSpecArray(b, ca);
            case com.legend.protocol.spec.PathLiteral pl -> pathLiteral(b, pl);
            // %10:10:10 -> strictTime, value VERBATIM without the '%' (probe "time literal")
            case com.legend.protocol.spec.CTime t -> literal(b, "strictTime",
                    quotedWritten(t.written(), "time literal"), t.pos());
            case com.legend.protocol.spec.GraphFetchLiteral gf -> graphFetch(b, gf);
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
            case com.legend.protocol.spec.GraphFetchLiteral gf -> graphFetch(b, gf, span);
            case com.legend.protocol.spec.PathLiteral pl -> pathLiteral(b, pl, span);
            case com.legend.protocol.spec.LambdaFunction lam ->
                    valueSpec(b, new com.legend.protocol.spec.LambdaFunction(
                            lam.parameters(), lam.body(), span));
            default -> throw new UnsupportedOperationException(
                    "ProtocolEmitter has no let-value span rule for "
                            + v.getClass().getSimpleName() + " — probe, do not guess.");
        }
    }

    /** {@code _type:"measure"} (probe: vanilla engine Measure JSON): units carry a
     *  span-less arrow-lambda conversion; the {@code *} canonical unit rides its own
     *  key; unit spans run name..the terminating ';'. */
    private static void measure(StringBuilder b, Protocol.PMeasure m) {
        b.append("{\"_type\":\"measure\"");
        if (m.canonicalUnit() != null) {
            b.append(",\"canonicalUnit\":");
            unit(b, m.canonicalUnit());
        }
        b.append(",\"name\":");
        str(b, m.name());
        b.append(",\"nonCanonicalUnits\":[");
        for (int i = 0; i < m.nonCanonicalUnits().size(); i++) {
            if (i > 0) {
                b.append(',');
            }
            unit(b, m.nonCanonicalUnits().get(i));
        }
        b.append("],\"package\":");
        str(b, m.pkg());
        b.append(",\"sourceInformation\":");
        srcInfo(b, m.sourceInformation());
        b.append('}');
    }

    private static void unit(StringBuilder b, Protocol.PUnit u) {
        b.append('{');
        if (u.body() != null) {
            b.append("\"conversionFunction\":{\"_type\":\"lambda\",\"body\":[");
            valueSpec(b, u.body());
            b.append("],\"parameters\":[{\"_type\":\"var\",\"name\":");
            str(b, java.util.Objects.requireNonNull(u.paramName(), "unit param"));
            b.append("}]},");
        }
        b.append("\"measure\":");
        str(b, u.measureFqn());
        b.append(",\"name\":");
        str(b, u.name());
        b.append(",\"sourceInformation\":");
        srcInfo(b, u.sourceInformation());
        b.append('}');
    }

    /** A realization's WIRE body: inline statements, or the kept reference NODE for the
     *  bare-name form ({@code Ref.source}); a Ref without a node cannot emit. */
    private static List<com.legend.protocol.spec.ValueSpecification> realizationBody(
            com.legend.protocol.Realization r, String what) {
        return switch (r) {
            case com.legend.protocol.Realization.Inline inl -> inl.body();
            case com.legend.protocol.Realization.Ref ref -> {
                if (ref.source() == null) {
                    throw new UnsupportedOperationException(
                            "ProtocolEmitter needs the parsed reference node for " + what
                                    + " and the parser did not keep one — fix the parse site.");
                }
                yield List.of(ref.source());
            }
        };
    }

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
        // NO SHAPE PATCHES HERE: the parser carries the engine's tree — n-ary
        // plus[Collection[...]], booleanPart accumulator rotation, run-context
        // spans — at PARSE time (SpecParser's combined loop; implementation
        // audit §1.1/§1.3). Operator-built nodes emit structurally like any
        // other func; an arrow-spelled (10)->times(2) stays a plain
        // two-parameter call because the parser built it that way.
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
        if ("tableReference".equals(f.function())
                && f.parameters().size() == 1
                && f.parameters().get(0) instanceof com.legend.protocol.spec.PackageableElementPtr store) {
            // STORE-ONLY island (#>{my::Store}#): ONE path element (probe "pf named
            // new and store tref" b); spans as in the two-part form
            SourceInfo span = topSpanOverride != null ? topSpanOverride
                    : requirePos(f.pos(), "table reference");
            b.append("{\"_type\":\"classInstance\",\"sourceInformation\":");
            srcInfo(b, span);
            b.append(",\"type\":\">\",\"value\":{\"path\":[");
            str(b, store.fullPath());
            b.append("],\"sourceInformation\":");
            srcInfo(b, span);
            b.append("}}");
            return;
        }
        if ("tableReference".equals(f.function())
                && f.parameters().size() == 2
                && f.parameters().get(0) instanceof com.legend.protocol.spec.PackageableElementPtr db
                && f.parameters().get(1) instanceof com.legend.protocol.spec.CString tbl
                && tbl.pos() == null) {
            // The ISLAND form #>{db.schema.TBL}#: classInstance of type ">" with
            // {path:[db, schema, TBL], sourceInformation} — outer and inner spans identical,
            // covering the whole literal (ProbeWireShapes tref/tref2). Discriminated from
            // the ORDINARY tableReference(db,'s','t') function call — which emits as a
            // plain func below — by the island's synthesised, pos-less table-name string.
            SourceInfo span = topSpanOverride != null ? topSpanOverride
                    : requirePos(f.pos(), "table reference");
            b.append("{\"_type\":\"classInstance\",\"sourceInformation\":");
            srcInfo(b, span);
            b.append(",\"type\":\">\",\"value\":{\"path\":[");
            str(b, db.fullPath());
            String t = tbl.value();
            int start = 0;
            while (start <= t.length()) {
                int dot = t.indexOf('.', start);
                b.append(',');
                str(b, t.substring(start, dot < 0 ? t.length() : dot));
                if (dot < 0) {
                    break;
                }
                start = dot + 1;
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
     * {@code ^X(k=v,…)} on the wire: {@code func "new"} with NO span, parameters
     * [span-less {@code genericTypeInstance} of {@code Class<X>}, span-less empty string,
     * span-less collection of {@code keyExpression}s whose keys are span-less strings and
     * whose values keep their own spans] (ProbeWireShapes "burn zoo" newInst).
     */
    private static void newInstance(StringBuilder b, com.legend.protocol.spec.NewInstance ni,
                                     @com.legend.Nullable SourceInfo span) {
        require(!ni.className().isEmpty(), "new-instance on a variable receiver", "^$x(...)");
        // ENGINE SPECIAL-CASES three classes, matching the spelled name EXACTLY against
        // the simple or canonical-FQN spelling (DomainParseTreeWalker; ProbeWireShapes
        // "caret specials"): ^Pair -> pair(), ^BasicColumnSpecification -> col() (with
        // documentation as an optional third key, engine's select-nonNull), and
        // ^TdsOlapRank -> meta::pure::tds::func() — canonical key order, no envelope
        // span, values keeping their own spans.
        String spelled = ni.className();
        if ("Pair".equals(spelled)
                || "meta::pure::functions::collection::Pair".equals(spelled)) {
            caretSpecial(b, ni, "meta::pure::functions::collection::pair",
                    new String[]{"first", "second"}, false, span);
            return;
        }
        if ("BasicColumnSpecification".equals(spelled)
                || "meta::pure::tds::BasicColumnSpecification".equals(spelled)) {
            caretSpecial(b, ni, "meta::pure::tds::col",
                    new String[]{"func", "name", "documentation"}, true, span);
            return;
        }
        if ("TdsOlapRank".equals(spelled)
                || "meta::pure::tds::TdsOlapRank".equals(spelled)) {
            caretSpecial(b, ni, "meta::pure::tds::func",
                    new String[]{"func"}, false, span);
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
            // Two spellings of an infix chain: pairwise params (divide, comparisons,
            // equal/and/or) strip to the first parameter; the n-ary collection carrier
            // (plus/minus/times — parser-shaped like the engine) strips to the first
            // collection element.
            while (kv instanceof com.legend.protocol.spec.AppliedFunction chain
                    && INFIX_FAMILIES.contains(chain.function())
                    && !chain.grouped()) {
                if (chain.parameters().size() == 2) {
                    kv = chain.parameters().get(0);
                } else if (chain.parameters().size() == 1
                        && chain.parameters().get(0)
                                instanceof com.legend.protocol.spec.PureCollection run
                        && run.values().size() >= 2) {
                    kv = run.values().get(0);
                } else {
                    break;
                }
            }
            valueSpec(b, kv);
            b.append(",\"key\":{\"_type\":\"string\",\"value\":");
            // the KEY keeps only its first atom too — propToA.prop = ... emits "propToA"
            // (same engine truncation family; inline-snippet corpus TestNewInstance)
            int dot = e.getKey().indexOf('.');
            str(b, dot < 0 ? e.getKey() : e.getKey().substring(0, dot));
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
        pathLiteral(b, pl, null);
    }

    /** Let-value form: the OUTER classInstance takes the letFunction span; the value keeps
     *  its SHIFTED spans unchanged (probe "path in let" — same rule as graph fetch). */
    private static void pathLiteral(StringBuilder b, com.legend.protocol.spec.PathLiteral pl,
            @com.legend.Nullable SourceInfo outerOverride) {
        SourceInfo lit = requirePos(pl.pos(), "path literal");
        require(lit.startLine() == lit.endLine(), "multi-line path literal", pl.startType());
        int s = lit.startColumn();
        int len = pl.literalLength();
        int line = lit.startLine();
        SourceInfo outer = new SourceInfo(lit.sourceId(), line, s + len, line, s + 2 * len + 2);
        b.append("{\"_type\":\"classInstance\",\"sourceInformation\":");
        srcInfo(b, outerOverride != null ? outerOverride : outer);
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
            pathArgs(b, seg.args(), lit, s, len, line);
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


    /** Dated-segment arguments under the shifted-span rules; collections recurse. */
    private static void pathArgs(StringBuilder b,
            List<com.legend.protocol.spec.PathLiteral.PathArg> args,
            SourceInfo lit, int s, int len, int line) {
        for (int a = 0; a < args.size(); a++) {
            if (a > 0) {
                b.append(',');
            }
            switch (args.get(a)) {
                case com.legend.protocol.spec.PathLiteral.PathArg.Latest r -> {
                    b.append("{\"_type\":\"latestDate\",\"sourceInformation\":");
                    srcInfo(b, new SourceInfo(lit.sourceId(),
                            line, s + len + r.start() - 1,
                            line, s + len + r.end() - 1));
                    b.append('}');
                }
                case com.legend.protocol.spec.PathLiteral.PathArg.DateArg r -> {
                    b.append("{\"_type\":\"dateTime\",\"sourceInformation\":");
                    srcInfo(b, new SourceInfo(lit.sourceId(),
                            line, s + len + r.start() - 1,
                            line, s + len + r.end() - 1));
                    b.append(",\"value\":");
                    str(b, r.value());
                    b.append('}');
                }
                case com.legend.protocol.spec.PathLiteral.PathArg.EnumArg e -> {
                    b.append("{\"_type\":\"enumValue\",\"fullPath\":");
                    str(b, e.fullPath());
                    b.append(",\"value\":");
                    str(b, e.value());
                    b.append('}');
                }
                case com.legend.protocol.spec.PathLiteral.PathArg.IntArg n -> {
                    b.append("{\"_type\":\"integer\",\"sourceInformation\":");
                    srcInfo(b, new SourceInfo(lit.sourceId(),
                            line, s + len + n.start() - 1,
                            line, s + len + n.end() - 1));
                    b.append(",\"value\":").append(n.value()).append('}');
                }
                case com.legend.protocol.spec.PathLiteral.PathArg.StrArg st -> {
                    b.append("{\"_type\":\"string\",\"sourceInformation\":");
                    srcInfo(b, new SourceInfo(lit.sourceId(),
                            line, s + len + st.start() - 1,
                            line, s + len + st.end() - 1));
                    b.append(",\"value\":");
                    str(b, st.value());
                    b.append('}');
                }
                case com.legend.protocol.spec.PathLiteral.PathArg.CollectionArg col -> {
                    // span-less collection wrapper, elements carry their own shifted
                    // spans (probe "pf path exotic")
                    b.append("{\"_type\":\"collection\",\"multiplicity\":{\"lowerBound\":")
                            .append(col.elements().size()).append(",\"upperBound\":")
                            .append(col.elements().size()).append("},\"values\":[");
                    pathArgs(b, col.elements(), lit, s, len, line);
                    b.append("]}");
                }
            }
        }
    }

    /**
     * {@code #{Root {a, k {b}}}#}: a {@code classInstance} of type {@code rootGraphFetchTree}
     * whose outer and value spans are BOTH the class-name token span (absolute — graph-fetch
     * spans are not island-shifted); each property node spans its name token
     * (ProbeWireShapes "typed new and gft", "alias dated tref2 gft2" d).
     */
    private static void graphFetch(StringBuilder b, com.legend.protocol.spec.GraphFetchLiteral gf) {
        graphFetch(b, gf, null);
    }

    /** Let-value form: the OUTER classInstance takes the letFunction span; the inner value
     *  keeps the class-name span (probe "gft as let value"). */
    private static void graphFetch(StringBuilder b, com.legend.protocol.spec.GraphFetchLiteral gf,
            @com.legend.Nullable SourceInfo outerSpan) {
        require(!gf.unsupported(),
                "graph-fetch with aliases/parameters/subType (wire shape unprobed)",
                gf.className());
        SourceInfo pos = requirePos(gf.pos(), "graph fetch " + gf.className());
        b.append("{\"_type\":\"classInstance\",\"sourceInformation\":");
        srcInfo(b, outerSpan != null ? outerSpan : pos);
        // the engine DOUBLES the _type key here (Jackson subtype + explicit property — probe "gft in let arg")
        b.append(",\"type\":\"rootGraphFetchTree\",\"value\":{\"_type\":\"rootGraphFetchTree\",\"_type\":\"rootGraphFetchTree\",\"class\":");
        str(b, gf.className());
        b.append(",\"sourceInformation\":");
        srcInfo(b, pos);
        b.append(",\"subTrees\":[");
        graphNodes(b, gf.subTrees());
        b.append("],\"subTypeTrees\":[");
        graphSubTypes(b, gf.subTypeTrees());
        b.append("]}}");
    }

    /** {@code ->subType(@X) { ... }} entries: {@code subTypeGraphFetchTree} nodes (the
     *  {@code _type} key doubled like every graph node); span = the class name WITHOUT
     *  the {@code @} (probe "gft root subtype"). */
    private static void graphSubTypes(StringBuilder b,
            List<com.legend.protocol.spec.GraphFetchLiteral.SubTypeNode> sts) {
        for (int i = 0; i < sts.size(); i++) {
            if (i > 0) {
                b.append(',');
            }
            com.legend.protocol.spec.GraphFetchLiteral.SubTypeNode st = sts.get(i);
            b.append("{\"_type\":\"subTypeGraphFetchTree\",\"_type\":\"subTypeGraphFetchTree\",\"sourceInformation\":");
            srcInfo(b, requirePos(st.pos(), "graph-fetch subType " + st.subTypeClass()));
            b.append(",\"subTrees\":[");
            graphNodes(b, st.subTrees());
            b.append("],\"subTypeClass\":");
            str(b, st.subTypeClass());
            b.append(",\"subTypeTrees\":[]}");
        }
    }

    private static void graphNodes(StringBuilder b,
            List<com.legend.protocol.spec.GraphFetchLiteral.Node> nodes) {
        for (int i = 0; i < nodes.size(); i++) {
            if (i > 0) {
                b.append(',');
            }
            com.legend.protocol.spec.GraphFetchLiteral.Node n = nodes.get(i);
            b.append("{\"_type\":\"propertyGraphFetchTree\",\"_type\":\"propertyGraphFetchTree\",");
            if (n.alias() != null) {
                b.append("\"alias\":");
                str(b, n.alias());
                b.append(',');
            }
            b.append("\"parameters\":[");
            for (int k = 0; k < n.parameters().size(); k++) {
                if (k > 0) {
                    b.append(',');
                }
                gftParam(b, n.parameters().get(k));
            }
            b.append("],\"property\":");
            str(b, n.property());
            b.append(",\"sourceInformation\":");
            srcInfo(b, requirePos(n.pos(), "graph-fetch property " + n.property()));
            b.append(",\"subTrees\":[");
            graphNodes(b, n.subTrees());
            b.append(']');
            if (n.subType() != null) {
                b.append(",\"subType\":");
                str(b, n.subType());
            }
            b.append(",\"subTypeTrees\":[");
            graphSubTypes(b, n.subTypeTrees());
            b.append("]}");
        }
    }

    /**
     * A graph-fetch property argument. String/integer/var reuse the ordinary literal
     * shapes; dates diverge from expression position — ALWAYS {@code dateTime} and the
     * value keeps the leading {@code %} (probe "gft pct date param") — and a dotted enum
     * is a REAL {@code enumValue} node spanning the whole dotted path (probe "gft enum
     * param"), unlike the property-on-ptr spelling expression position uses.
     */
    private static void gftParam(StringBuilder b, com.legend.protocol.spec.ValueSpecification p) {
        switch (p) {
            case com.legend.protocol.spec.CDate d -> {
                b.append("{\"_type\":\"dateTime\",\"sourceInformation\":");
                srcInfo(b, requirePos(d.pos(), "graph-fetch date argument"));
                b.append(",\"value\":");
                str(b, java.util.Objects.requireNonNull(d.written(),
                        "graph-fetch date argument written form"));
                b.append('}');
            }
            case com.legend.protocol.spec.EnumValue e -> {
                b.append("{\"_type\":\"enumValue\",\"fullPath\":");
                str(b, e.fullPath());
                b.append(",\"sourceInformation\":");
                srcInfo(b, requirePos(e.pos(), "graph-fetch enum argument"));
                b.append(",\"value\":");
                str(b, e.value());
                b.append('}');
            }
            case com.legend.protocol.spec.PureCollection col -> {
                // graph-fetch collection args carry NO sourceInformation
                // (probe "gft collection args"), unlike expression position
                b.append("{\"_type\":\"collection\",\"multiplicity\":{\"lowerBound\":")
                        .append(col.values().size()).append(",\"upperBound\":")
                        .append(col.values().size()).append("},\"values\":[");
                for (int k = 0; k < col.values().size(); k++) {
                    if (k > 0) {
                        b.append(',');
                    }
                    gftParam(b, col.values().get(k));
                }
                b.append("]}");
            }
            case com.legend.protocol.spec.CString s -> valueSpec(b, s);
            case com.legend.protocol.spec.CInteger c -> valueSpec(b, c);
            case com.legend.protocol.spec.CBoolean bo -> valueSpec(b, bo);
            case com.legend.protocol.spec.Variable v -> valueSpec(b, v);
            default -> throw new UnsupportedOperationException(
                    "ProtocolEmitter has no rule for graph-fetch argument "
                            + p.getClass().getSimpleName() + " — probe, do not guess.");
        }
    }

    /** Quote a literal's REQUIRED written form for the wire. */
    private static String quotedWritten(@com.legend.Nullable String written, String what) {
        if (written == null) {
            throw new UnsupportedOperationException(
                    "ProtocolEmitter requires the source-written form for " + what
                            + " — synthesized nodes are not emittable.");
        }
        StringBuilder q = new StringBuilder();
        str(q, written);
        return q.toString();
    }

    /** The engine's hardcoded caret-to-function desugars — see {@code newInstance}. */
    private static void caretSpecial(StringBuilder b, com.legend.protocol.spec.NewInstance ni,
                                     String function, String[] keys, boolean dropMissing,
                                     @com.legend.Nullable SourceInfo span) {
        b.append("{\"_type\":\"func\",\"function\":");
        str(b, function);
        b.append(",\"parameters\":[");
        int emitted = 0;
        for (int i = 0; i < keys.length; i++) {
            com.legend.protocol.spec.KeyExpression ke = ni.properties().get(keys[i]);
            if (ke == null && dropMissing) {
                continue;              // engine's select(nonNull) — col's documentation
            }
            if (ke == null) {
                throw new UnsupportedOperationException(
                        "ProtocolEmitter has no rule for a caret special missing key '"
                                + keys[i] + "' (at " + ni.className() + ").");
            }
            if (emitted++ > 0) {
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
        // ~name:Type[m] — the TYPED column spec (probe "typed colspec stmt"): genericType
        // (+ multiplicity when declared) in place of the lambdas
        if (cs.colType() != null) {
            b.append("\"genericType\":");
            genericType(b, cs.colType());
            b.append(',');
            if (cs.colTypeMult() != null) {
                b.append("\"multiplicity\":");
                multiplicity(b, cs.colTypeMult());
                b.append(',');
            }
        }
        b.append("\"name\":");
        str(b, cs.name());
        b.append(",\"sourceInformation\":");
        srcInfo(b, pos);
        // ~<<...>> {...} name:Type — annotations ride the value, keys only when present
        // (probe "pf colspec annotations")
        if (!cs.stereotypes().isEmpty()) {
            b.append(",\"stereotypes\":");
            stereotypes(b, cs.stereotypes());
        }
        if (!cs.taggedValues().isEmpty()) {
            b.append(",\"taggedValues\":");
            taggedValues(b, cs.taggedValues());
        }
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
                        // Jackson writes control escapes with UPPERCASE hex ()
                        b.append(String.format("\\u%04X", (int) c));
                    } else {
                        b.append(c);
                    }
                }
            }
        }
        b.append('"');
    }
}
