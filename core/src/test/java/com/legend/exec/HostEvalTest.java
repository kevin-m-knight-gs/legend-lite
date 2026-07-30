// SPDX-License-Identifier: Apache-2.0

package com.legend.exec;

import com.legend.compiler.element.type.ExprType;
import com.legend.compiler.element.type.Type;
import com.legend.compiler.spec.typed.TypedCString;
import com.legend.compiler.spec.typed.TypedCollection;
import com.legend.compiler.spec.typed.TypedMatchRuntime;
import com.legend.compiler.spec.typed.TypedNewInstance;
import com.legend.compiler.spec.typed.TypedCopyInstance;
import com.legend.compiler.spec.typed.TypedSpec;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins for the INTERPRETER CORE primitives (reflection leg): instance
 * construction, copy-with-override, runtime-dispatched match, and
 * structural instance equality — the shared substrate of the metamodel
 * host domains.
 */
class HostEvalTest {

    private static final String DYNA = "test::Dyna";
    private static final String LIT = "test::Lit";

    private static ExprType one(String fqn) {
        return ExprType.one(new Type.ClassType(fqn));
    }

    private static TypedCString str(String v) {
        return new TypedCString(v, ExprType.one(Type.Primitive.STRING));
    }

    private static TypedNewInstance lit(String v) {
        LinkedHashMap<String, TypedSpec> ps = new LinkedHashMap<>();
        ps.put("value", str(v));
        return new TypedNewInstance(LIT, ps, one(LIT));
    }

    @Test
    void constructionBuildsTaggedInstances() throws Exception {
        Object v = HostEval.evalToResult(lit("Y")) instanceof
                ExecutionResult.Scalar s ? s.value() : null;
        assertTrue(v instanceof HostEval.HostInstance hi
                && LIT.equals(hi.classFqn())
                && "Y".equals(hi.properties().get("value")));
    }

    @Test
    void copyOverridesOnlyNamedProperties() throws Exception {
        LinkedHashMap<String, TypedSpec> ps = new LinkedHashMap<>();
        ps.put("name", str("and"));
        ps.put("tag", str("keep"));
        TypedNewInstance base = new TypedNewInstance(DYNA, ps, one(DYNA));
        Map<String, TypedSpec> ov = new LinkedHashMap<>();
        ov.put("name", str("or"));
        TypedCopyInstance copy =
                new TypedCopyInstance(base, DYNA, ov, one(DYNA));
        Object v = ((ExecutionResult.Scalar) HostEval.evalToResult(copy))
                .value();
        HostEval.HostInstance hi = (HostEval.HostInstance) v;
        assertEquals("or", hi.properties().get("name"));
        assertEquals("keep", hi.properties().get("tag"));
    }

    @Test
    void matchDispatchesOnRuntimeTag() throws Exception {
        // input statically Any-ish; arms narrow — LIT arm must win for a
        // LIT instance even though a wider arm exists later
        TypedMatchRuntime m = new TypedMatchRuntime(
                lit("x"),
                List.of(
                        new TypedMatchRuntime.Arm(DYNA, "d", str("dyna")),
                        new TypedMatchRuntime.Arm(LIT, "l", str("lit")),
                        new TypedMatchRuntime.Arm(
                                "meta::pure::metamodel::type::Any", "e",
                                str("other"))),
                Optional.empty(), Optional.empty(),
                ExprType.one(Type.Primitive.STRING));
        Object v = ((ExecutionResult.Scalar) HostEval.evalToResult(m)).value();
        assertEquals("lit", v);
    }

    @Test
    void structuralEqualityIsDeep() throws Exception {
        TypedCollection params1 = new TypedCollection(
                List.of(lit("Y"), lit("N")),
                new ExprType(new Type.ClassType(LIT),
                        new com.legend.compiler.element.type.Multiplicity
                                .Bounded(2, 2)));
        LinkedHashMap<String, TypedSpec> ps = new LinkedHashMap<>();
        ps.put("name", str("equal"));
        ps.put("parameters", params1);
        TypedNewInstance a = new TypedNewInstance(DYNA, ps, one(DYNA));
        Object va = ((ExecutionResult.Scalar) HostEval.evalToResult(a)).value();
        Object vb = ((ExecutionResult.Scalar) HostEval.evalToResult(a)).value();
        assertTrue(HostEval.hostEquals(va, vb));
        // flip a nested leaf
        LinkedHashMap<String, TypedSpec> ps2 = new LinkedHashMap<>(ps);
        ps2.put("parameters", new TypedCollection(
                List.of(lit("Y"), lit("X")),
                new ExprType(new Type.ClassType(LIT),
                        new com.legend.compiler.element.type.Multiplicity
                                .Bounded(2, 2))));
        TypedNewInstance b = new TypedNewInstance(DYNA, ps2, one(DYNA));
        Object vc = ((ExecutionResult.Scalar) HostEval.evalToResult(b)).value();
        assertFalse(HostEval.hostEquals(va, vc));
    }
}
