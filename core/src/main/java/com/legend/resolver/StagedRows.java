// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend.resolver;

import com.legend.compiler.element.type.ExprType;
import com.legend.compiler.element.type.Type;
import com.legend.compiler.spec.typed.TypedLet;
import com.legend.compiler.spec.typed.TypedTableReference;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * The let of a program's staged rows (lite's {@code stage}, docs/WRITES_DESIGN_2026_10_18.md §8, leg 6): what the
 * statements after {@code let s = rows->stage()} read for {@code $s}, once execution has computed the rows into a
 * temporary table of the session -- that table, a relation of the staged rows' columns. A physical reference,
 * minted here with the resolver's other table reads; execution names the table and the store.
 */
public final class StagedRows {

    private StagedRows() {
    }

    /** {@code let name} bound to the session's temporary table {@code table}, a relation of the staged rows' columns
     *  ({@code rows}), each named as its projection named it (delimited, so its case holds on a database that folds
     *  an undelimited name); {@code store} is the store the table is read under, {@code letInfo} the let's type. */
    public static TypedLet boundTo(String name, String table, ExprType rows, String store, ExprType letInfo) {
        Set<String> columns = new LinkedHashSet<>();
        Objects.requireNonNull(Type.schemaView(rows.type()), "staged rows are a relation").columns()
                .forEach(c -> columns.add(c.name()));
        return new TypedLet(name, new TypedTableReference(store, table, rows, true, null, columns, false, Map.of(),
                false, false), letInfo);
    }
}
