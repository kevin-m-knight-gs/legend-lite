package com.legend.protocol;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Guards the contract {@code TypeExpression} shares with the value-spec records:
 * <b>source position is excluded from equality.</b>
 *
 * <p>Type expressions are compared structurally throughout the compiler and tests
 * ({@code TypeExpressionFixtures} builds position-free instances); the parser threads real
 * spans so the emitter can produce byte-identical {@code genericType} source information.
 * If {@code equals} ever includes the position, every structural comparison between a parsed
 * and a synthesized type breaks. If you are here because this failed, the override was
 * removed — restore it rather than changing these expectations.
 */
class TypeExpressionEqualityTest {

    private static final SourceInfo A = new SourceInfo("f.pure", 1, 1, 1, 5);
    private static final SourceInfo B = new SourceInfo("f.pure", 9, 9, 9, 9);

    @Test
    void nameRefsAreEqualRegardlessOfPosition() {
        assertEquals(new TypeExpression.NameRef("String"), new TypeExpression.NameRef("String", A));
        assertEquals(new TypeExpression.NameRef("String", A), new TypeExpression.NameRef("String", B));
        assertEquals(new TypeExpression.NameRef("String", A).hashCode(),
                new TypeExpression.NameRef("String", B).hashCode());
    }

    @Test
    void genericsAreEqualRegardlessOfPositionRecursively() {
        TypeExpression.Generic withPos = new TypeExpression.Generic("a::D",
                List.of(new TypeExpression.NameRef("String", A)), List.of(), B);
        TypeExpression.Generic without = new TypeExpression.Generic("a::D",
                List.of(new TypeExpression.NameRef("String")));
        assertEquals(without, withPos);
        assertEquals(without.hashCode(), withPos.hashCode());
    }
}
