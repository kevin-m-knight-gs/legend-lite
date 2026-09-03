// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend.exec;

import java.util.Map;

/**
 * Thread-scoped carrier of the LAST execute() call's recognized
 * connection post-processor state (the {@link
 * com.legend.lowering.SqlPostProcessors} table-rename map) — the
 * {@code RawSqlBoundary} pattern. The engine's {@code sqlRemoveFormatting}
 * goldens read the EXECUTED plan's SQL, post-processors applied; the
 * harness's engine-text verification rebuilds that SQL through the
 * toSQLString surface, which has no runtime argument — this boundary
 * hands it the executed call's hooks. Reset per execute (an execute
 * without hooks clears it).
 */
public final class PostProcessBoundary {

    private PostProcessBoundary() {
    }

    private static final ThreadLocal<Map<String, String>> TABLE_REPLACE =
            ThreadLocal.withInitial(Map::of);

    public static void record(Map<String, String> tableReplace) {
        TABLE_REPLACE.set(Map.copyOf(tableReplace));
    }

    public static Map<String, String> tableReplace() {
        return TABLE_REPLACE.get();
    }

    private static final ThreadLocal<Boolean> EXTRACT_CTES =
            ThreadLocal.withInitial(() -> false);

    /** Whether the frame's runtime installs the CTE-extraction processor. */
    public static void recordExtractCtes(boolean on) {
        EXTRACT_CTES.set(on);
    }

    public static boolean extractCtes() {
        return EXTRACT_CTES.get();
    }

}
