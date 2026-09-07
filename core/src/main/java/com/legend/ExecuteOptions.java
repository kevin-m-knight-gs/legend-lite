// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend;

/**
 * The execute OPTIONS a caller passes with one execution — they ride the
 * request and the result, never a static slot (the PctRenderOption
 * thread-local died here, batch 122).
 *
 * @param pctRender the PCT adapter's wire render: a relation-rooted query
 *                  lowers through the PCT-TDS root mode and the result is an
 *                  {@link com.legend.exec.ExecutionResult.TdsText}
 */
public record ExecuteOptions(boolean pctRender) {
    public static final ExecuteOptions NONE = new ExecuteOptions(false);
    public static final ExecuteOptions PCT_RENDER = new ExecuteOptions(true);
}
