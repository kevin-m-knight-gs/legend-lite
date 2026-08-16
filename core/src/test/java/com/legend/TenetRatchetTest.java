// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * F1.10 — the DIRECT tenet metric (docs/TENET_CHARTER.md, enforcement
 * map): a shrink-only count of JDBC value-accessor CALL SITES in
 * production. The §0.2 metrics are proxies (funnel coverage, duplicate
 * counts); this is the number "Java orchestrates, the DATABASE
 * executes" says must go DOWN — every site is a place Java takes a
 * value off the wire, and Charter C1.2 licenses only CARRIAGE at such
 * a site, never computation. Phases 4-7 (DB-side rendering, the typed
 * bridge, compensation removal, ingress) each delete some of these;
 * this ratchet records the descent and refuses regrowth.
 *
 * <p>Counting rule: {@code .getXxx(} accessor spellings inside
 * src/main files that import {@code java.sql} (the funnel makes that
 * the complete universe). Seeded 2026-08-16 at the F0.1 baseline:
 * TestDataGenerator 6, Executor 6, DynamicPivot 1, DbMetaData 1.
 */
class TenetRatchetTest {

    private static final int RESULT_SET_ACCESSOR_SITES = 14;

    private static final Pattern ACCESSOR = Pattern.compile(
            "\\.get(String|Object|Int|Long|Double|Boolean|BigDecimal"
            + "|Date|Timestamp|Time|Bytes|Array|Float|Short|Byte)\\(");

    @Test
    void resultSetConsumptionOnlyShrinks() throws IOException {
        List<String> sites = new ArrayList<>();
        Path root = Path.of("src/main/java");
        try (Stream<Path> files = Files.walk(root)) {
            for (Path f : files.filter(p -> p.toString().endsWith(".java"))
                    .toList()) {
                String src = Files.readString(f);
                if (!src.contains("import java.sql")
                        && !src.contains("java.sql.")) {
                    continue;
                }
                String code = src.replaceAll("//.*", "")
                        .replaceAll("(?s)/\\*.*?\\*/", "");
                Matcher m = ACCESSOR.matcher(code);
                while (m.find()) {
                    sites.add(f.getFileName().toString());
                }
            }
        }
        assertTrue(sites.size() <= RESULT_SET_ACCESSOR_SITES,
                "JDBC value-accessor sites grew to " + sites.size()
                + " (pinned at " + RESULT_SET_ACCESSOR_SITES + "): "
                + sites + " — the tenet's number goes DOWN (Charter"
                + " C1.2: carriage only; computation belongs to the"
                + " database)");
    }
}
