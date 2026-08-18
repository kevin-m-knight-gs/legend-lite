// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend.exec;

/**
 * E1 (JAVA_EVICTION_PLAN): the PCT wire-render EXECUTION OPTION — set
 * by the PCT adapter around one execution, consumed at the one
 * plan-building site. When enabled, a RELATION-rooted query lowers
 * through the Lowerer's PCT-TDS root mode: the PLAN emits the wire
 * text and the result is a Scalar String ({@code markRendered} tells
 * the adapter the string IS the TDS text, not an ordinary scalar).
 * Same option pattern as {@code DriverPkOption}.
 */
public final class PctRenderOption {

    private PctRenderOption() {
    }

    private static final ThreadLocal<boolean[]> STATE = new ThreadLocal<>();

    /** Enable for the current thread; close() disables. */
    public static AutoCloseable enable() {
        STATE.set(new boolean[]{false});
        return STATE::remove;
    }

    public static boolean enabled() {
        return STATE.get() != null;
    }

    /** The platform wrapped this execution's root — the Scalar String
     * result IS the rendered TDS text. */
    public static void markRendered() {
        boolean[] s = STATE.get();
        if (s != null) {
            s[0] = true;
        }
    }

    public static boolean wasRendered() {
        boolean[] s = STATE.get();
        return s != null && s[0];
    }
}
