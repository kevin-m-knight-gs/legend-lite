package com.legend.protocol.spec;

import com.legend.protocol.SourceInfo;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * Guards the one property the value-specification design rests on:
 * <b>source position is excluded from equality.</b>
 *
 * <p>Value specifications carry a {@code pos} so the parser can emit protocol
 * {@code sourceInformation} without a side table. But they are also compared structurally — by the
 * compiler, and by 111 hand-built assertions of the form
 * {@code assertEquals(new CInteger(42L), spec)}. If {@code equals} ever includes the position,
 * every one of those breaks, and two structurally identical expressions become unequal for no
 * semantic reason.
 *
 * <p>This test exists because deleting the override and letting the record default return is an
 * easy "cleanup". If you are here because this failed, the override was removed — restore it
 * rather than changing these expectations.
 */
class ValueSpecEqualityTest {

    private static final SourceInfo A = new SourceInfo("f.pure", 1, 1, 1, 2);
    private static final SourceInfo B = new SourceInfo("f.pure", 9, 9, 9, 9);

    @Test
    void integersAreEqualRegardlessOfPosition() {
        assertEquals(new CInteger(42L), new CInteger(42L, A));
        assertEquals(new CInteger(42L, A), new CInteger(42L, B));
        assertEquals(new CInteger(42L, A).hashCode(), new CInteger(42L, B).hashCode());
    }

    @Test
    void stringsAreEqualRegardlessOfPosition() {
        assertEquals(new CString("x"), new CString("x", A));
        assertEquals(new CString("x", A), new CString("x", B));
        assertEquals(new CString("x", A).hashCode(), new CString("x", B).hashCode());
    }

    /** Position insensitivity must not become value insensitivity. */
    @Test
    void differentValuesAreStillUnequal() {
        assertNotEquals(new CInteger(1L, A), new CInteger(2L, A));
        assertNotEquals(new CString("x", A), new CString("y", A));
    }

    /** The position is retained — excluded from equality, not discarded. */
    @Test
    void positionIsRetainedEvenThoughItIsNotCompared() {
        assertEquals(A, new CInteger(42L, A).pos());
        assertEquals(A, new CString("x", A).pos());
    }

    @Test
    void booleansAreEqualRegardlessOfPosition() {
        assertEquals(new CBoolean(true), new CBoolean(true, A));
        assertEquals(new CBoolean(true, A), new CBoolean(true, B));
        assertEquals(new CBoolean(true, A).hashCode(), new CBoolean(true, B).hashCode());
        assertNotEquals(new CBoolean(true, A), new CBoolean(false, A));
        assertEquals(A, new CBoolean(true, A).pos());
    }
}
