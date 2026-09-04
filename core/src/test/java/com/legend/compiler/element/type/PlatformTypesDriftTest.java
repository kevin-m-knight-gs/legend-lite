package com.legend.compiler.element.type;

import com.legend.builtin.Pure;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link PlatformTypes}' FQN constants mirror the {@code builtin/Pure}
 * prelude declarations — this pin makes it impossible for either side to
 * move alone (the constants exist so low-level packages need no prelude
 * dependency; the pin is what makes that duplication safe).
 */
class PlatformTypesDriftTest {

    @Test
    void constantsMatchThePreludeDeclarations() {
        assertEquals(Pure.ANY.qualifiedName(), PlatformTypes.ANY);
        assertEquals(Pure.NIL.qualifiedName(), PlatformTypes.NIL);
        assertEquals(Pure.VARIANT.qualifiedName(), PlatformTypes.VARIANT);
        assertTrue(com.legend.builtin.Prelude.classFqns().contains(PlatformTypes.LIST));
        assertTrue(com.legend.builtin.Prelude.classFqns().contains(PlatformTypes.PAIR));
        assertEquals(Pure.FUNCTION.qualifiedName(), PlatformTypes.FUNCTION);
    }
}
