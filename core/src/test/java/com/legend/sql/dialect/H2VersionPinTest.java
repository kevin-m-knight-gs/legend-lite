// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0
package com.legend.sql.dialect;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The raw-SQL boundary's H2 dialect level IS the referee's H2 jar. */
class H2VersionPinTest {

    @Test
    @DisplayName("H2VERSION() answers the H2 jar the harness referee runs")
    void h2VersionIsTheRefereeJar() {
        assertEquals(org.h2.engine.Constants.VERSION, RawSqlBoundary.H2_DIALECT_VERSION,
                "the H2 jar moved: re-pin RawSqlBoundary.H2_DIALECT_VERSION with the engine's matching extension");
    }
}
