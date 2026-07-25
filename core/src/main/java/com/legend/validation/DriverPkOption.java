// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend.validation;

/**
 * The {@code addDriverTablePkForProject} execution option (#45). Engine
 * parity note: the engine carries this on the
 * {@code RelationalExecutionContext} argument of {@code execute()}; that
 * metamodel class is unloadable in PARTIAL corpus modules (its defining
 * file drags the relational metamodel), so the option travels run-scoped
 * here — {@code TestBody.run} sets it FRESH (true or false) for every
 * body, the executor reads it into the {@code ExecEnv}. Replace with the
 * typed exeCtx argument once the metamodel family loads.
 */
public final class DriverPkOption {

    private DriverPkOption() {
    }

    private static final ThreadLocal<Boolean> ACTIVE =
            ThreadLocal.withInitial(() -> false);

    public static void set(boolean on) {
        ACTIVE.set(on);
    }

    public static boolean get() {
        return ACTIVE.get();
    }
}
