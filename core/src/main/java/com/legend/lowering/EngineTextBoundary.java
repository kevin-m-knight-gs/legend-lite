// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend.lowering;

/**
 * Thread-scoped marker for ENGINE-TEXT lowering (the {@code RawSqlBoundary}
 * pattern): the toSQLString/planToString funnels rebuild SQL the engine's
 * goldens compare LITERALLY, and the engine never spells the mapping's
 * WIRE coercions (a String-declared property over a numeric column reads
 * bare — the engine runtime converts on the wire). Execution lowering
 * keeps those casts (DuckDB does not wire-convert). The
 * {@code castAsDeclared} scalar rule reads this flag; the funnels set it
 * around their lowering, try/finally.
 */
public final class EngineTextBoundary {

    private EngineTextBoundary() {
    }

    private static final ThreadLocal<Boolean> ACTIVE =
            ThreadLocal.withInitial(() -> Boolean.FALSE);

    public static boolean active() {
        return ACTIVE.get();
    }

    /** Non-throwing close so funnels use plain try-with-resources. */
    public interface Scope extends AutoCloseable {
        @Override
        void close();
    }

    public static Scope enter() {
        ACTIVE.set(Boolean.TRUE);
        return () -> ACTIVE.set(Boolean.FALSE);
    }
}
