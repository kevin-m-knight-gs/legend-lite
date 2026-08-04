// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend.sql.dialect;

/**
 * Thread-scoped marker for the ENGINE-TEXT rendering channel — the SQL
 * layer's mirror of the driver's EngineTextBoundary (the layer wall
 * forbids reading it directly). The toSQLString/planToString funnels
 * enter BOTH; execution rendering never sets it.
 */
public final class TextGoldens {

    private TextGoldens() {
    }

    private static final ThreadLocal<Boolean> ACTIVE =
            ThreadLocal.withInitial(() -> Boolean.FALSE);

    public static boolean active() {
        return ACTIVE.get();
    }

    /** Non-throwing close for try-with-resources. */
    public interface Scope extends AutoCloseable {
        @Override
        void close();
    }

    public static Scope enter() {
        ACTIVE.set(Boolean.TRUE);
        return () -> ACTIVE.set(Boolean.FALSE);
    }
}
