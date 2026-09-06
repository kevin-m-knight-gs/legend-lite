// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The two ratchets of docs/EXECUTION_CONTEXT_DESIGN_2026_09_06.md (user
 * ruling 2026-09-06: "are we building a general purpose pure runner or a
 * super hard-coded test runner?").
 *
 * <ol>
 *   <li>A Pure function or class NAME is spelled in ONE place — the catalog
 *       ({@link com.legend.compiler.element.type.PlatformTypes}). Every
 *       other file asks the catalog. The literal {@code equals("meta::…")}
 *       checks outside it are counted and may only SHRINK.</li>
 *   <li>The execution context is a VALUE read once: no file outside
 *       {@link com.legend.compiler.spec.typed.ExecutionContext} walks a
 *       runtime expression for its shape. The retired walkers are named
 *       so they cannot return under their old spelling.</li>
 * </ol>
 */
class PlatformNamesGuardrailTest {

    private static final Path MAIN = Path.of("src/main/java/com/legend");
    private static final Pattern LITERAL_NAME_CHECK =
            Pattern.compile("equals\\(\"meta::");
    /** The retired runtime-shape walkers: their names may not reappear as
     * methods anywhere outside the one reader. */
    private static final List<String> RETIRED_WALKERS = List.of(
            "chainMappingsIn(", "jsonSourcesIn(", "sqlSetupsIn(", "setupsIn(",
            "connectionNameIn(", "quoteIdentifiersOf(", "timeZoneOf(",
            "connectionInstanceOf(", "databaseTypeOf(", "connectionStoreElementOf(",
            "runRuntimeSetups(");

    @Test
    void pureNamesAreSpelledInTheCatalogOnly() throws IOException {
        int count = 0;
        StringBuilder where = new StringBuilder();
        try (Stream<Path> files = Files.walk(MAIN)) {
            for (Path f : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                if (f.getFileName().toString().equals("PlatformTypes.java")) {
                    continue;
                }
                Matcher m = LITERAL_NAME_CHECK.matcher(Files.readString(f));
                int n = 0;
                while (m.find()) {
                    n++;
                }
                if (n > 0) {
                    count += n;
                    where.append(MAIN.relativize(f)).append('=').append(n).append(' ');
                }
            }
        }
        // 73 at batch 114 (2026-09-06) — SHRINK-ONLY: every burn moves a
        // spelling into PlatformTypes; a new literal check anywhere else
        // fails here
        assertTrue(count <= 73, "literal Pure-name checks outside PlatformTypes grew: "
                + count + " > 73 — " + where);
    }

    @Test
    void runtimeShapesAreReadByTheOneReaderOnly() throws IOException {
        StringBuilder found = new StringBuilder();
        try (Stream<Path> files = Files.walk(MAIN)) {
            for (Path f : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                if (f.endsWith("ExecutionContext.java")) {
                    continue;
                }
                String text = Files.readString(f);
                for (String w : RETIRED_WALKERS) {
                    if (text.contains(" " + w) || text.contains("." + w)) {
                        found.append(MAIN.relativize(f)).append(':').append(w).append(' ');
                    }
                }
            }
        }
        assertEquals("", found.toString(),
                "a runtime-shape walker reappeared outside ExecutionContext.Reader: ");
        assertTrue(!Files.exists(MAIN.resolve("ConnectionFlags.java")),
                "ConnectionFlags is retired: its readers live in ExecutionContext.Reader");
    }
}
