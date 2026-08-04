package com.legend.protocol;

import com.legend.model.ClassDefinition;
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
public final class ProtocolToModel {

    private ProtocolToModel() {
    }

    public static ClassDefinition toClassDefinition(PClass c) {
        List<ClassDefinition.PropertyDefinition> props = new ArrayList<>(c.properties().size());
        for (PProperty p : c.properties()) {
            props.add(new ClassDefinition.PropertyDefinition(
                    p.name(), p.type(), p.multiplicity(), p.stereotypes(), p.taggedValues()));
        }
        return new ClassDefinition(c.qualifiedName(), c.typeParams(), c.superClasses(), props,
                c.derivedProperties(), c.constraints(), c.stereotypes(), c.taggedValues(),
                c.isNative());
    }

}
