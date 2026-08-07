package com.legend.compiler.spec;

import com.legend.compiler.element.type.Type;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Text-surgery audit §1.1 #7 pins — inference must match what the lowering
 *  can actually parse. */
class TdsInferencePinTest {

    @Test
    void dotlessScientificWidensTheColumnToFloat() {
        // an Integer-headed column with a later 1e5 cell stayed Integer and
        // Long.parseLong("1e5") crashed at lowering
        assertEquals(Type.Primitive.FLOAT, TdsChecker.inferredType(
                List.of(List.of("1"), List.of("1e5")), 0));
    }

    @Test
    void dotlessScientificFirstCellIsFloat() {
        assertEquals(Type.Primitive.FLOAT, TdsChecker.inferredType(
                List.of(List.of("1e5")), 0));
    }

    @Test
    void colonOffsetTimestampStaysStringUntilTheLoweringPreservesOffsets() {
        // +05:30 is legal ISO-8601; accepting it before audit §1.1 #3 lands
        // would mint mis-lowered instants — pinned String DELIBERATELY
        assertEquals(Type.Primitive.STRING, TdsChecker.inferredType(
                List.of(List.of("2024-01-29T00:32:34.000+05:30")), 0));
    }
}
