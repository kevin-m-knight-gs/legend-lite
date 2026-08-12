// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend.platform;

import com.legend.Compiler;
import com.legend.compiler.spec.SpecCompiler;
import com.legend.compiler.spec.UserCallInliner;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * THE QUARANTINED PLATFORM-REGIME TESTS (inlining): behavior only the
 * m2 corpus exercises — {@code .allVersionsInRange} is platform
 * dialect, so a user can never write it, but corpus function bodies
 * flow through the same inliner. Package name = the regime; parses via
 * {@link com.legend.testing.Platform}.
 */
class PlatformInliningTest {

    @Test
    @DisplayName("versionSweep survives a dated rebuild (remediation T1.1)")
    void versionSweepSurvivesInlining() {
        // the rebuild only fires when milestoning is NON-empty: an
        // allVersionsInRange whose dates reference callee parameters is
        // exactly the shape that silently became a POINT fetch
        String model = """
                Class <<temporal.businesstemporal>> t::T { id: Integer[1]; }
                function t::sweep(s: Date[1], e: Date[1]): t::T[*]
                { t::T.allVersionsInRange($s, $e) }
                """;
        var ctx = Compiler.buildModel(com.legend.testing.Platform.model(model));
        var specs = new SpecCompiler(ctx);
        var body = specs.typeQueryBody(
                com.legend.compiler.NameResolver.resolveQuery(
                        com.legend.testing.Platform.spec(
                                "|t::sweep(%2020-01-01, %2021-01-01)")));
        body = new UserCallInliner(specs).inlineBody(body);
        var last = body.get(body.size() - 1);
        var g = org.junit.jupiter.api.Assertions.assertInstanceOf(
                com.legend.compiler.spec.typed.TypedGetAll.class, last);
        assertTrue(g.versionSweep(),
                "inlining a dated version sweep must stay a SWEEP, not a point fetch");
        assertEquals(2, g.milestoning().size(), "range dates ride along");
    }

}
