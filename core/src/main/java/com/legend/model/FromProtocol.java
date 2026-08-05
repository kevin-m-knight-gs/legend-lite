package com.legend.model;

import com.legend.model.ClassDefinition;
import com.legend.protocol.Protocol;
import com.legend.protocol.Protocol.PClass;
import com.legend.protocol.Protocol.PProperty;

import java.util.ArrayList;
import java.util.List;

/**
 * Protocol &rarr; {@code com.legend.model}: the boundary that keeps upstream's wire shape out of
 * legend-lite's compiler.
 *
 * <p>The parser has exactly one output — {@link Protocol} records. Everything legend-lite compiles
 * comes through here, and the compiler below is unchanged. This is deliberately the mirror of
 * {@link ProtocolEmitter}: one converts our records to their bytes, the other converts our records
 * to our compiler's model, and <b>no third party learns the wire shape</b>.
 *
 * <p>It is also stage 2's input adapter. When legend-lite's compiler replaces legend-engine's it
 * must consume {@code PureModelContextData} anyway, so this boundary is being built now and
 * exercised by legend-lite's entire test suite on every build.
 *
 * <p><b>Positions are dropped here, on purpose.</b> {@code com.legend.model} records are value
 * types whose structural equality the compiler and 111 hand-built test assertions rely on; a
 * position component would change {@code equals}. Positions live on the protocol side, which is
 * where the wire needs them.
 */
public final class FromProtocol {

    private FromProtocol() {
    }

    /** Protocol stereotypes to the model's, dropping positions the compiler does not want. */
    public static List<com.legend.model.StereotypeApplication> stereotypes(
            List<Protocol.PStereotype> ss) {
        List<com.legend.model.StereotypeApplication> out = new ArrayList<>(ss.size());
        for (Protocol.PStereotype s : ss) {
            out.add(new com.legend.model.StereotypeApplication(s.profile(), s.value()));
        }
        return out;
    }

    /** Protocol tagged values to the model's. */
    public static List<com.legend.model.TaggedValue> taggedValues(List<Protocol.PTaggedValue> ts) {
        List<com.legend.model.TaggedValue> out = new ArrayList<>(ts.size());
        for (Protocol.PTaggedValue t : ts) {
            out.add(new com.legend.model.TaggedValue(t.tag().profile(), t.tag().value(), t.value()));
        }
        return out;
    }

    /** Protocol profile to the model's — bare names; spans stay protocol-side. */
    public static ProfileDefinition toProfileDefinition(Protocol.PProfile p) {
        java.util.List<String> ss = new ArrayList<>(p.stereotypes().size());
        for (Protocol.PProfileEntry e : p.stereotypes()) {
            ss.add(e.value());
        }
        java.util.List<String> ts = new ArrayList<>(p.tags().size());
        for (Protocol.PProfileEntry e : p.tags()) {
            ts.add(e.value());
        }
        return new ProfileDefinition(p.qualifiedName(), ss, ts);
    }

    /** Protocol enumeration to the model's — value NAMES only; the compiler does not
     *  consume enum annotations. */
    public static EnumDefinition toEnumDefinition(Protocol.PEnumeration e) {
        java.util.List<String> names = new ArrayList<>(e.values().size());
        for (Protocol.PEnumValue v : e.values()) {
            names.add(v.value());
        }
        return new EnumDefinition(e.qualifiedName(), names);
    }

    public static ClassDefinition toClassDefinition(PClass c) {
        List<ClassDefinition.PropertyDefinition> props = new ArrayList<>(c.properties().size());
        for (PProperty p : c.properties()) {
            props.add(new ClassDefinition.PropertyDefinition(
                    p.name(), p.type(), p.multiplicity(),
                    stereotypes(p.stereotypes()), taggedValues(p.taggedValues())));
        }
        List<com.legend.protocol.TypeExpression> supers = new ArrayList<>(c.superTypes().size());
        for (Protocol.PSuperType st : c.superTypes()) {
            supers.add(st.type());
        }
        return new ClassDefinition(c.qualifiedName(), c.typeParams(), supers, props,
                c.derivedProperties(), c.constraints(),
                stereotypes(c.stereotypes()), taggedValues(c.taggedValues()),
                c.isNative());
    }

}
