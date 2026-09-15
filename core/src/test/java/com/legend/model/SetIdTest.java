// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Audit 2026-09-15 P2-1: the ONE set-id rule. */
class SetIdTest {

    @Test
    @DisplayName("the declared id wins; else the class FQN with :: as _")
    void declaredElseDefault() {
        assertEquals("p1", SetId.of("p1", "model::Person"));
        assertEquals("model_Person", SetId.of(null, "model::Person"));
        assertEquals("model_Person", SetId.of("", "model::Person"),
                "an empty declaration is an absence (the protocol carries \"\" for none)");
        assertEquals("a_b_Person", SetId.defaultFor("a::b::Person"));
    }

    @Test
    @DisplayName("isDefault matches the exact default only — never a short class name")
    void isDefaultIsExact() {
        assertTrue(SetId.isDefault("model_Person", "model::Person"));
        assertFalse(SetId.isDefault("Person", "model::Person"),
                "a short class name is a NAME, not the default (two same-named classes in"
                        + " different packages must not match each other's routes)");
        assertFalse(SetId.isDefault("other_Person", "model::Person"));
    }
}
