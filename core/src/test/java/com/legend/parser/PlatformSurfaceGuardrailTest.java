// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend.parser;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * THE PLATFORM-SURFACE QUARANTINE (Dialect levels, user directive
 * 2026-08-12): {@code ElementParser.parsePlatform} — legend-lite's own
 * legend-pure dialect superset — may be referenced ONLY by the
 * whitelisted platform/bootstrap call sites below. User-facing code
 * routes through {@code parseLegendLite} (product surface) or
 * {@code parseStrict}/{@code PmcdParser} (exact-engine surface).
 *
 * <p>The whitelist FREEZES the callers as found at the quarantine's
 * introduction; it may only SHRINK as entries migrate to the product
 * surface. Adding a file here is a reviewed decision that a new
 * component is platform machinery.
 */
class PlatformSurfaceGuardrailTest {

    private static final Set<String> WHITELIST = Set.of(
            // platform/bootstrap loading and internal pipeline
            "com/legend/Compiler.java",
            "com/legend/builtin/Pure.java",
            "com/legend/parser/ElementParser.java",
            "com/legend/parser/package-info.java",   // javadoc mention
            // the test harness IS platform-side (it loads platform sources)
            "com/legend/harness/TestBody.java",
            // FROZEN AT INTRODUCTION — migration candidates to
            // parseLegendLite, each its own reviewed batch:
            "com/legend/server/PureLspServer.java",
            "com/legend/server/DiagramService.java",
            "com/legend/server/ConnectionResolver.java");

    @Test
    void platformSurfaceCallersAreWhitelisted() throws IOException {
        Path root = Path.of("src/main/java");
        List<String> offenders;
        try (Stream<Path> files = Files.walk(root)) {
            offenders = files
                    .filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> {
                        try {
                            return Files.readString(p)
                                    .contains("parsePlatform(");
                        } catch (IOException e) {
                            throw new java.io.UncheckedIOException(e);
                        }
                    })
                    .map(p -> root.relativize(p).toString())
                    .filter(p -> !WHITELIST.contains(p))
                    .toList();
        }
        assertTrue(offenders.isEmpty(),
                () -> "NEW callers of the PLATFORM dialect surface — user"
                        + " code must route through parseLegendLite or"
                        + " parseStrict (Dialect levels):\n  "
                        + String.join("\n  ", offenders));
    }
}
