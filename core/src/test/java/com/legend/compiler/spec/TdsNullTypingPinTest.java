// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend.compiler.spec;

import com.legend.compiler.element.PureModelContext;
import com.legend.compiler.element.TypedFunction;
import com.legend.compiler.element.type.Multiplicity;
import com.legend.compiler.element.type.PlatformTypes;
import com.legend.compiler.element.type.Type;
import com.legend.compiler.spec.typed.TypedNativeCall;
import com.legend.compiler.spec.typed.TypedNewInstance;
import com.legend.compiler.spec.typed.TypedSpec;
import com.legend.model.NormalizedModel;
import com.legend.model.ParsedModel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the ^TDSNull() split (TDSNull-is-a-value slice, 2026-08-24 —
 * charter record in F10_CARRIER_DESIGN.md). The slice-1-3 audit found
 * this path otherwise WITNESS-LESS: the corpus harness folds
 * {@code ^TDSNull()} to the string sentinel at SOURCE level
 * (CORPUS_FOLD), so no corpus test ever exercises the compiler's own
 * typing of the constructor.
 *
 * <p>The split: the CONSTRUCTOR {@code ^TDSNull()} is a real INSTANCE
 * — typed through NewChecker as {@code TDSNull[1]} (a VALUE, never an
 * empty; engine tds.pure:127). The BARE REFERENCE ({@code $v !=
 * TDSNull}) stays the {@code sqlNull()} funnel — that position is the
 * presence test (NullSemantics null-literal arms) and {@code Nil[0]}
 * is sqlNull's registered signature.
 */
class TdsNullTypingPinTest {

    private static List<TypedSpec> bodyOf(String model, String fnFqn) {
        ParsedModel p = com.legend.testing.Own.model(model);
        PureModelContext c = PureModelContext.from(
                new NormalizedModel(p.elements(), p.imports()));
        List<TypedFunction> fns = c.findFunction(fnFqn);
        assertEquals(1, fns.size(), fnFqn + " should have exactly one overload");
        return new SpecCompiler(c).compile(fns.get(0)).body();
    }

    @Test
    @DisplayName("^TDSNull() is an INSTANCE — TDSNull[1], via NewChecker")
    void ctorTypesAsInstanceOne() {
        List<TypedSpec> body = bodyOf(
                "function f::t():meta::pure::tds::TDSNull[1] {"
                + " ^meta::pure::tds::TDSNull() }\n", "f::t");
        TypedSpec last = body.get(body.size() - 1);
        TypedNewInstance ni = assertInstanceOf(TypedNewInstance.class, last,
                "^TDSNull() must type as a construction, not the sqlNull funnel");
        assertEquals(PlatformTypes.TDS_NULL_FQN,
                ni.classFqn(), "the TDSNull platform class");
        assertTrue(ni.info().type() instanceof Type.ClassType ct
                        && ct.fqn().equals(PlatformTypes.TDS_NULL_FQN),
                "info type is the TDSNull class");
        assertEquals(new Multiplicity.Bounded(1, 1), ni.info().multiplicity(),
                "an instance is a VALUE — stamped [1], never Nil[0]");
    }

    @Test
    @DisplayName("bare TDSNull reference stays the sqlNull() presence funnel")
    void bareReferenceStaysSqlNullFunnel() {
        List<TypedSpec> body = bodyOf(
                "function f::r(v:meta::pure::metamodel::type::String[0..1])"
                + ":meta::pure::metamodel::type::Boolean[1] {"
                + " $v != meta::pure::tds::TDSNull }\n", "f::r");
        TypedSpec last = body.get(body.size() - 1);
        // the != lowers over a sqlNull() operand — walk the typed tree
        // for the funnel call (position varies with the equality shape)
        assertTrue(mentionsSqlNull(last),
                "bare TDSNull must resolve through the sqlNull() funnel,"
                + " got: " + last);
    }

    private static boolean mentionsSqlNull(TypedSpec n) {
        // exact FQN, never a suffix match (exact-FQN rule, audit 23 A1)
        if (n instanceof TypedNativeCall c && c.callee().qualifiedName()
                .equals("meta::relational::functions::sqlQueryToString::sqlNull")) {
            return true;
        }
        return n.children().stream().anyMatch(TdsNullTypingPinTest::mentionsSqlNull);
    }
}
