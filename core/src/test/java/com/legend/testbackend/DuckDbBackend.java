// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend.testbackend;

import com.legend.model.ConnectionDefinition.DatabaseType;

/** DuckDB's sessions: a private in-memory instance each, the platform's own engine ({@code Databases.PLATFORM}). */
final class DuckDbBackend extends InProcessBackend {

    static final DuckDbBackend INSTANCE = new DuckDbBackend();

    private DuckDbBackend() {
    }

    @Override
    public DatabaseType type() {
        return DatabaseType.DuckDB;
    }

    @Override
    public String name() {
        return "duckdb";
    }
}
