// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0
package com.legend.resolver;

import com.legend.compiler.element.type.Type;
import com.legend.compiler.spec.typed.TypedConcatenate;
import com.legend.compiler.spec.typed.TypedFrom;
import com.legend.compiler.spec.typed.TypedFuncCol;
import com.legend.compiler.spec.typed.TypedMap;
import com.legend.compiler.spec.typed.TypedNativeCall;
import com.legend.compiler.spec.typed.TypedSerializeGraph;
import com.legend.compiler.spec.typed.TypedSpec;
import java.util.ArrayList;
import java.util.List;
import java.util.function.UnaryOperator;

/**
 * Class-collection CONCATENATES ({@code Product.all()->concatenate(
 * Product.all())}) at the anchored-node seam — the engine's one
 * unionalias instance stream (concatenate testAll: 8 instances, u_type
 * per thread).
 */
final class ClassConcatenates {
    private ClassConcatenates() {
    }

    /** A scalar map over an EXECUTED concatenate (the execute() frame
     * wraps the concatenate: {@code $result.values.name}): each side
     * keeps the frame, the reads UNION ALL. */
    static TypedSpec mapOverExecuted(TypedMap m, TypedFrom fr0, TypedNativeCall c,
            UnaryOperator<TypedSpec> resolve) {
        List<TypedSpec> lk = new ArrayList<>(fr0.children());
        lk.set(0, c.args().get(0));
        List<TypedSpec> rk = new ArrayList<>(fr0.children());
        rk.set(0, c.args().get(1));
        return new TypedConcatenate(
                resolve.apply(new TypedMap(fr0.withChildren(lk), m.mapper(), m.info())),
                resolve.apply(new TypedMap(fr0.withChildren(rk), m.mapper(), m.info())),
                m.info());
    }

    /** The whole-instance terminal: each side resolved as its own
     * object-space chain; two IMPLICIT-SERIALIZE graph terminals of ONE
     * class layout fuse into ONE graph over the UNION of their row
     * sources (a union of two graph nodes has no relation lowering);
     * anything else UNION ALLs the resolved sides. */
    static TypedSpec terminal(TypedNativeCall c, TypedSpec lhs, TypedSpec rhs) {
        if (lhs instanceof TypedSerializeGraph gl && rhs instanceof TypedSerializeGraph gr
                && java.util.Objects.equals(gl.classFqn(), gr.classFqn())
                && gl.leaves().stream().map(TypedFuncCol::name).toList()
                        .equals(gr.leaves().stream().map(TypedFuncCol::name).toList())
                && gl.nested().size() == gr.nested().size()
                && Type.requireRelationSchema(gl.source().info().type())
                        .equals(Type.requireRelationSchema(gr.source().info().type()))) {
            return new TypedSerializeGraph(
                    new TypedConcatenate(gl.source(), gr.source(), gl.source().info()),
                    gl.rowVar(), gl.leaves(), gl.nested(), gl.arrayWrap(),
                    gl.bareValue(), gl.classFqn(), gl.info(), gl.inlineChild(),
                    gl.subTypePatches(), gl.orderKeys(), gl.typeKeyName(),
                    gl.fqTypePath(), gl.checkedConstraints(), gl.removeNullKeys(),
                    gl.removeEmptySets(), gl.objectRefPrefix());
        }
        return new TypedConcatenate(lhs, rhs, c.info());
    }
}
