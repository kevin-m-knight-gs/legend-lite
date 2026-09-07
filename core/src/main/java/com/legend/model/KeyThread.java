// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend.model;

import java.util.Objects;

/**
 * One primary-key THREAD of an Operation union's row: a member set's key
 * column projected as {@code <column>_<memberOrdinal>} — the union's row
 * identity across its members (a set-1 row carries NULL in set-2's key
 * thread). The engine's importDataFlow columns
 * ({@code pureToSQLQuery_union.pure:140–150}); {@code pureKind} is the
 * column's Pure primitive kind ({@code Integer}, {@code String}, …) read
 * off the store at synthesis, null when the store does not declare it
 * (a consumer that needs the kind is loud).
 */
public record KeyThread(String name, @com.legend.Nullable String pureKind) {
    public KeyThread {
        Objects.requireNonNull(name, "name");
    }
}
