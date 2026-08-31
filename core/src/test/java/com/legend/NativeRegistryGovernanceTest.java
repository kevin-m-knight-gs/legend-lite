// SPDX-License-Identifier: Apache-2.0

package com.legend;

import com.legend.compiler.element.type.PlatformTypes;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Registry == catalog (§10m, user push: "or just moving the ifs into a
 * table?"): every catalogued EFFECT/JAVA_ROUTINE row has a registered
 * implementation and every registered implementation has a row —
 * neither list can drift without failing here.
 */
class NativeRegistryGovernanceTest {

    private static Set<String> rows(PlatformTypes.NativeImpl kind) {
        return PlatformTypes.IMPLEMENTATION_KIND.entrySet().stream()
                .filter(e -> e.getValue() == kind)
                .map(Map.Entry::getKey)
                .collect(Collectors.toSet());
    }

    @Test
    @DisplayName("EFFECT rows == registered effect arms")
    void effectRegistryCoversCatalog() {
        assertEquals(rows(PlatformTypes.NativeImpl.EFFECT),
                StatementExecutor.registeredEffectKeys(),
                "EFFECT rows and registered arms drifted");
    }

    @Test
    @DisplayName("JAVA_ROUTINE rows == registered staged routines")
    void routineRegistryCoversCatalog() {
        assertEquals(rows(PlatformTypes.NativeImpl.JAVA_ROUTINE),
                StatementExecutor.registeredRoutineKeys(),
                "JAVA_ROUTINE rows and staged routines drifted");
    }
}
