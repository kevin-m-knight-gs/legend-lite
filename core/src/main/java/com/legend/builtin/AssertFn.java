// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend.builtin;

import com.legend.model.NativeFunctionDefinition;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * THE ASSERT FAMILY as a closed type (upstream boundary batch 3, the CoreFn
 * pattern): the fourteen {@code meta::pure::functions::asserts} members
 * {@code com.legend.AssertVerdicts} adjudicates as row verdicts, one constant per
 * function carrying EVERY catalog overload of it (message / format / lambda
 * variants). {@code AssertVerdicts.adjudicate} is a switch EXPRESSION over
 * this enum with no default — a new member does not compile until it is
 * placed. {@code assertError} is not here: it has its own context-owning
 * arm ({@code AssertErrorNative}, claimed through IMPLEMENTATION_KIND);
 * {@code fail} is not adjudicated. The registry reads {@link #values()}.
 */
public enum AssertFn {
    ASSERT_EQUALS("assertEquals",
            Pure.ASSERT_EQUALS__ANY_MANY__ANY_MANY, Pure.ASSERT_EQUALS__ANY_MANY__ANY_MANY__STRING_1, Pure.ASSERT_EQUALS__ANY_MANY__ANY_MANY__STRING_1__ANY_MANY, Pure.ASSERT_EQUALS__ANY_MANY__ANY_MANY__FN_1),
    ASSERT_NOT_EQUALS("assertNotEquals",
            Pure.ASSERT_NOT_EQUALS__ANY_MANY__ANY_MANY, Pure.ASSERT_NOT_EQUALS__ANY_MANY__ANY_MANY__STRING_1, Pure.ASSERT_NOT_EQUALS__ANY_MANY__ANY_MANY__STRING_1__ANY_MANY, Pure.ASSERT_NOT_EQUALS__ANY_MANY__ANY_MANY__FN_1),
    ASSERT_SAME_ELEMENTS("assertSameElements",
            Pure.ASSERT_SAME_ELEMENTS__ANY_MANY__ANY_MANY, Pure.ASSERT_SAME_ELEMENTS__ANY_MANY__ANY_MANY__STRING_1, Pure.ASSERT_SAME_ELEMENTS__ANY_MANY__ANY_MANY__STRING_1__ANY_MANY, Pure.ASSERT_SAME_ELEMENTS__ANY_MANY__ANY_MANY__FN_1),
    ASSERT_SIZE("assertSize",
            Pure.ASSERT_SIZE__ANY_MANY__INTEGER_1, Pure.ASSERT_SIZE__ANY_MANY__INTEGER_1__STRING_1, Pure.ASSERT_SIZE__ANY_MANY__INTEGER_1__STRING_1__ANY_MANY, Pure.ASSERT_SIZE__ANY_MANY__INTEGER_1__FN_1),
    ASSERT_JSON_STRINGS_EQUAL("assertJsonStringsEqual",
            Pure.ASSERT_JSON_STRINGS_EQUAL__STRING_1__STRING_1),
    ASSERT_CONTAINS("assertContains",
            Pure.ASSERT_CONTAINS__ANY_MANY__ANY_1, Pure.ASSERT_CONTAINS__ANY_MANY__ANY_1__STRING_1, Pure.ASSERT_CONTAINS__ANY_MANY__ANY_1__STRING_1__ANY_MANY, Pure.ASSERT_CONTAINS__ANY_MANY__ANY_1__FN_1),
    ASSERT_EQ("assertEq",
            Pure.ASSERT_EQ__ANY_1__ANY_1, Pure.ASSERT_EQ__ANY_1__ANY_1__STRING_1, Pure.ASSERT_EQ__ANY_1__ANY_1__STRING_1__ANY_MANY, Pure.ASSERT_EQ__ANY_1__ANY_1__FN_1),
    ASSERT_EQ_WITHIN_TOLERANCE("assertEqWithinTolerance",
            Pure.ASSERT_EQ_WITHIN_TOLERANCE__NUMBER_1__NUMBER_1__NUMBER_1, Pure.ASSERT_EQ_WITHIN_TOLERANCE__N_1__N_1__N_1__STRING_1, Pure.ASSERT_EQ_WITHIN_TOLERANCE__N_1__N_1__N_1__STRING_1__ANY_MANY, Pure.ASSERT_EQ_WITHIN_TOLERANCE__N_1__N_1__N_1__FN_1),
    ASSERT("assert",
            Pure.ASSERT__BOOLEAN_1, Pure.ASSERT__BOOLEAN_1__STRING_1, Pure.ASSERT__BOOLEAN_1__FN_1, Pure.ASSERT__BOOLEAN_1__STRING_1__ANY_MANY),
    ASSERT_FALSE("assertFalse",
            Pure.ASSERT_FALSE__BOOLEAN_1, Pure.ASSERT_FALSE__BOOLEAN_1__STRING_1, Pure.ASSERT_FALSE__BOOLEAN_1__STRING_1__ANY_MANY, Pure.ASSERT_FALSE__BOOLEAN_1__FN_1),
    ASSERT_INSTANCE_OF("assertInstanceOf",
            Pure.ASSERT_INSTANCE_OF__ANY_1__TYPE_1, Pure.ASSERT_INSTANCE_OF__ANY_1__TYPE_1__STRING_1, Pure.ASSERT_INSTANCE_OF__ANY_1__TYPE_1__STRING_1__ANY_MANY, Pure.ASSERT_INSTANCE_OF__ANY_1__TYPE_1__FN_1),
    ASSERT_IS("assertIs",
            Pure.ASSERT_IS__ANY_1__ANY_1, Pure.ASSERT_IS__ANY_1__ANY_1__STRING_1, Pure.ASSERT_IS__ANY_1__ANY_1__STRING_1__ANY_MANY, Pure.ASSERT_IS__ANY_1__ANY_1__FN_1),
    ASSERT_EMPTY("assertEmpty",
            Pure.ASSERT_EMPTY__ANY_MANY, Pure.ASSERT_EMPTY__ANY_MANY__STRING_1, Pure.ASSERT_EMPTY__ANY_MANY__STRING_1__ANY_MANY, Pure.ASSERT_EMPTY__ANY_MANY__FN_1),
    ASSERT_NOT_EMPTY("assertNotEmpty",
            Pure.ASSERT_NOT_EMPTY__ANY_MANY, Pure.ASSERT_NOT_EMPTY__ANY_MANY__STRING_1, Pure.ASSERT_NOT_EMPTY__ANY_MANY__STRING_1__ANY_MANY, Pure.ASSERT_NOT_EMPTY__ANY_MANY__FN_1);

    private final String bareName;
    private final List<NativeFunctionDefinition> overloads;

    AssertFn(String bareName, NativeFunctionDefinition... overloads) {
        this.bareName = bareName;
        this.overloads = List.of(overloads);
    }

    /** The bare function name ({@code assertEquals}). */
    public String bareName() {
        return bareName;
    }

    /** Every catalog overload this constant adjudicates. */
    public List<NativeFunctionDefinition> overloads() {
        return overloads;
    }

    /** FQN -> constant; immutable (ArchitectureTest invariant 3). */
    private static final Map<String, AssertFn> BY_FQN = index();

    private static Map<String, AssertFn> index() {
        Map<String, AssertFn> m = new java.util.HashMap<>();
        for (AssertFn f : values()) {
            for (NativeFunctionDefinition o : f.overloads) {
                m.put(o.qualifiedName(), f);
            }
        }
        return Map.copyOf(m);
    }

    /** The assert a callee FQN resolves to — empty when it is not an
     *  adjudicated member (assertError, fail: a normal fall-through). */
    public static Optional<AssertFn> ofFqn(String fqn) {
        return Optional.ofNullable(BY_FQN.get(fqn));
    }
}
