// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend.sql;

/**
 * A date or time part as the IR names one: the unit a date is shifted, bucketed, truncated or differenced by
 * ({@link SqlFn#ADD_INTERVAL}, {@link SqlFn#ADD_INTERVAL_TEMPORAL}, {@link SqlFn#TIME_BUCKET}, {@link SqlFn#DATE_TRUNC},
 * {@link SqlFn#DATE_DIFF}) and the field {@link SqlFn#EXTRACT} reads. It rides as the call's first argument, a
 * {@link SqlExpr.DatePartLit}. Until 2026-10-15 each rode as DuckDB's own spelling in a string -- a part name
 * ({@code 'isodow'}) or an interval FUNCTION's name ({@code to_days}) -- and every other dialect decoded DuckDB's
 * names (the dialect program's Phase 2, decision D3: the IR says what, never how). Each dialect spells a part itself.
 */
public enum DatePart {
    YEAR, QUARTER, MONTH, WEEK, DAY, HOUR, MINUTE, SECOND, MILLISECOND, MICROSECOND,
    /** The day of the year, 1 to 366 ({@code EXTRACT} only). */
    DAY_OF_YEAR,
    /** The ISO day of the week, Monday 1 to Sunday 7 ({@code EXTRACT} only). */
    ISO_DAY_OF_WEEK
}
