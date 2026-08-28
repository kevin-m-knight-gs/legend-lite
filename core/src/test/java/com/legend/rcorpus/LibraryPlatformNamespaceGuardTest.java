// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend.rcorpus;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * THE PLATFORM-NAMESPACE GUARD (V7 tenet correction 2026-08-28, user
 * catch): the whole point of legend-lite is to REPLACE pure/engine
 * with our own implementation — reference checkouts are SPEC and TEST
 * INPUT, never runtime components. A library source that defines
 * {@code meta::pure::functions::} elements would compile the reference
 * implementation's stdlib into our model; the loader refuses LOUDLY.
 * Test-fixture models (corpus test classes, engine test domains) load
 * as before — they are the thing under test, not the thing judging.
 */
class LibraryPlatformNamespaceGuardTest {

    @Test
    void refusesPlatformStdlibLibrarySources() {
        Runner r = new Runner(List.of(), List.of());
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> r.registerLibrarySource(
                        "function meta::pure::functions::asserts::"
                        + "assertEquals(expected:Any[*], actual:Any[*])"
                        + ":Boolean[1] { true; }"));
        assertTrue(e.getMessage().contains("platform-namespace"),
                e.getMessage());
        assertTrue(e.getMessage().contains(
                "meta::pure::functions::asserts::assertEquals"),
                e.getMessage());
    }

    @Test
    void acceptsTestFixtureLibrarySources() {
        Runner r = new Runner(List.of(), List.of());
        assertDoesNotThrow(() -> r.registerLibrarySource(
                "Class my::fixtures::Widget { name: String[1]; }"));
    }
}
