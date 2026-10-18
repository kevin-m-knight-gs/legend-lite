// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend.protocol.sql;

import com.legend.protocol.spec.SqlIsland;

/**
 * The input languages a SQL island is written in, each by the name its island gives it (the dialect program's D11,
 * 2026-10-18): the registry of {@link InputDialect}s, which names each one, as the database registry names every
 * database. A language read under a profile (IQ's options) is that language's own to read.
 */
public final class InputLanguages {

    private InputLanguages() {
    }

    /** The dialect an island in {@code language} that names no profile is read in, or null when that language has
     *  none without one. */
    public static @com.legend.base.Nullable InputDialect plain(String language) {
        return language.equals(SqlIsland.SQL) ? PostgresDialect.INSTANCE : null;
    }
}
