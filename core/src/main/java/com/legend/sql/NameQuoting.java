// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend.sql;

/**
 * Which parts of a stored table's name its store declared QUOTED ({@code Table "Orders"}, {@code Schema "Sales"}):
 * data, never quote characters inside the name. A name in the IR is its text, the identity every lookup reads (a plan's
 * text, the column lineage, a table-name mapping); the dialect writes a declared-quoted part delimited by its own quote
 * character, its case kept, and a bare part as its queries reference a bare name (decision D8: Postgres folds it). The
 * dialect program's audit of PR neema2/legend-lite#39 (2026-10-14) found the quote characters D8 had first put inside
 * the names breaking every consumer that read them.
 *
 * @param schema the schema's name was declared quoted
 * @param table  the table's name was declared quoted
 */
public record NameQuoting(boolean schema, boolean table) {

    /** Neither part declared quoted: every name the platform makes itself (a CTE, a frame, a verdict table), and every
     *  stored name a store declares bare. */
    public static final NameQuoting BARE = new NameQuoting(false, false);
}
