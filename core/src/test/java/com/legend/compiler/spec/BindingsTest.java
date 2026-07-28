package com.legend.compiler.spec;

import com.legend.compiler.element.type.Multiplicity;
import com.legend.compiler.element.type.Type;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Remediation T2.2 &mdash; {@code Bindings.copy()} must copy ALL FOUR
 * fields. It silently dropped {@code rigid} and {@code contravariantDepth},
 * so a per-column copy (the {@code AggColSpecArray} K/V split) forgot that
 * a variable was bound in parameter position and would covariantly widen
 * where an exact check was required.
 */
class BindingsTest {

    @Test
    @DisplayName("copy() preserves rigidity and contravariant depth")
    void copyPreservesAllFourFields() {
        Bindings b = new Bindings();
        b.bindType("T", Type.Primitive.INTEGER);
        b.bindMult("m", Multiplicity.Bounded.ONE);
        b.markRigid("T");
        b.enterContravariant();

        Bindings c = b.copy();
        assertEquals(b.type("T"), c.type("T"), "solved types copy");
        assertEquals(b.mult("m"), c.mult("m"), "solved mults copy");
        assertTrue(c.isRigid("T"),
                "a rigid (parameter-position) variable must stay rigid in the copy");
        assertTrue(c.contravariant(),
                "the contravariant depth must survive the copy");
    }
}
