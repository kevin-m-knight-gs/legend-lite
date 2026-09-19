// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend.values;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The host half of the pure-literal grammar: one case per form (a
 * spelling added on either side must land on both). */
class LiteralTextTest {

    @Test
    void everyFormParsesToItsOwnKind() {
        assertEquals("it's", LiteralText.parse("'it\\'s'"));
        assertEquals(Boolean.TRUE, LiteralText.parse("true"));
        assertEquals(7L, LiteralText.parse("7"));
        assertEquals(1.5, LiteralText.parse("1.5"));
        assertEquals(new java.math.BigDecimal("3.00"), LiteralText.parse("3.00D"));
        // the enum form decodes to the NAME the host holds an enum as —
        // before the Decimal arm (a name may end in D)
        assertEquals("CITY", LiteralText.parse("meta::pure::GeographicEntityType.CITY"));
        assertEquals("SOLD", LiteralText.parse("my::pkg::Status.SOLD"));
    }
}
