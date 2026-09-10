// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend.builtin;

import com.legend.model.NativeFunctionDefinition;

import java.util.Map;
import java.util.Optional;

/**
 * THE TDS ROW GETTERS as a closed type (upstream boundary batch 3, the CoreFn
 * pattern): real tds.pure's {@code getString / getInteger / …} over a TDSRow,
 * one constant per catalog overload, read by {@link RowGetters} and by the
 * claim registry ({@code com.legend.builtin.Claims}). {@code getEnum}, which
 * the old string set named, has no catalog signature and so was never
 * reachable as a native call; it is not here.
 */
public enum RowGetter {
    GET_STRING(Pure.GET_STRING__TDS_ROW_1__STRING_1),
    GET_INTEGER(Pure.GET_INTEGER__TDS_ROW_1__STRING_1),
    GET_FLOAT(Pure.GET_FLOAT__TDS_ROW_1__STRING_1),
    GET_DECIMAL(Pure.GET_DECIMAL__TDS_ROW_1__STRING_1),
    GET_NUMBER(Pure.GET_NUMBER__TDS_ROW_1__STRING_1),
    GET_BOOLEAN(Pure.GET_BOOLEAN__TDS_ROW_1__STRING_1),
    GET_DATE(Pure.GET_DATE__TDS_ROW_1__STRING_1),
    GET_DATE_TIME(Pure.GET_DATE_TIME__TDS_ROW_1__STRING_1),
    GET_STRICT_DATE(Pure.GET_STRICT_DATE__TDS_ROW_1__STRING_1);

    private final NativeFunctionDefinition overload;

    RowGetter(NativeFunctionDefinition overload) {
        this.overload = overload;
    }

    /** The catalog signature this constant implements. */
    public NativeFunctionDefinition overload() {
        return overload;
    }

    /** FQN -> constant; immutable (ArchitectureTest invariant 3). */
    private static final Map<String, RowGetter> BY_FQN = index();

    private static Map<String, RowGetter> index() {
        Map<String, RowGetter> m = new java.util.HashMap<>();
        for (RowGetter x : values()) {
            m.put(x.overload.qualifiedName(), x);
        }
        return Map.copyOf(m);
    }

    /** The getter a call resolves to — empty when the callee is not one. */
    public static Optional<RowGetter> of(String calleeFqn) {
        return Optional.ofNullable(BY_FQN.get(calleeFqn));
    }
}
