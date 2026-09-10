// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend.builtin;

import com.legend.model.NativeFunctionDefinition;

import java.util.Collections;
import java.util.Map;
import java.util.Optional;

/**
 * THE CALENDAR FAMILY as a closed type (upstream boundary batch 3, the CoreFn
 * pattern): the 32 calendar natives ({@code ytd}, {@code pma}, …) that
 * {@link CalendarAgg} lowers, one constant per overload, each carrying its
 * {@link Pure} signature. The switches in {@link CalendarAgg} are switch
 * EXPRESSIONS over this enum with no default — add a constant and they stop
 * compiling until it is handled; the registry ({@code com.legend.builtin.Claims})
 * reads {@link #values()}. The enum is the set AND the dispatch key: nothing
 * beside it to keep in sync.
 */
public enum CalendarFn {
    CY_MINUS2(Pure.CAL_C_Y_MINUS2),
    CY_MINUS3(Pure.CAL_C_Y_MINUS3),
    ANNUALIZED(Pure.CAL_ANNUALIZED),
    CME(Pure.CAL_CME),
    CW(Pure.CAL_CW),
    CW_FM(Pure.CAL_CW_FM),
    MTD(Pure.CAL_MTD),
    P12MTD(Pure.CAL_P12MTD),
    P12WA(Pure.CAL_P12WA),
    P12WTD(Pure.CAL_P12WTD),
    P4WA(Pure.CAL_P4WA),
    P4WTD(Pure.CAL_P4WTD),
    P52WA(Pure.CAL_P52WA),
    P52WTD(Pure.CAL_P52WTD),
    PMA(Pure.CAL_PMA),
    PMTD(Pure.CAL_PMTD),
    PQTD(Pure.CAL_PQTD),
    PRIOR_DAY(Pure.CAL_PRIOR_DAY),
    PRIOR_YEAR(Pure.CAL_PRIOR_YEAR),
    PW(Pure.CAL_PW),
    PW_FM(Pure.CAL_PW_FM),
    PWA(Pure.CAL_PWA),
    PWTD(Pure.CAL_PWTD),
    PYMTD(Pure.CAL_PYMTD),
    PYQTD(Pure.CAL_PYQTD),
    PYTD(Pure.CAL_PYTD),
    PYWA(Pure.CAL_PYWA),
    PYWTD(Pure.CAL_PYWTD),
    QTD(Pure.CAL_QTD),
    REPORT_END_DAY(Pure.CAL_REPORT_END_DAY),
    WTD(Pure.CAL_WTD),
    YTD(Pure.CAL_YTD);

    private final NativeFunctionDefinition overload;

    CalendarFn(NativeFunctionDefinition overload) {
        this.overload = overload;
    }

    /** The catalog signature this constant implements. */
    public NativeFunctionDefinition overload() {
        return overload;
    }

    /** FQN -> constant; immutable (ArchitectureTest invariant 3). */
    private static final Map<String, CalendarFn> BY_FQN = index();

    private static Map<String, CalendarFn> index() {
        Map<String, CalendarFn> m = new java.util.HashMap<>();
        for (CalendarFn x : values()) {
            m.put(x.overload.qualifiedName(), x);
        }
        return Map.copyOf(m);
    }

    /** The calendar function a call resolves to — empty when the callee is
     *  not in this family (a normal fall-through, never an error). */
    public static Optional<CalendarFn> of(String calleeFqn) {
        return Optional.ofNullable(BY_FQN.get(calleeFqn));
    }

    /** Every calendar FQN, for the family precheck in {@link CalendarAgg}. */
    public static Map<String, CalendarFn> byFqn() {
        return Collections.unmodifiableMap(BY_FQN);
    }
}
