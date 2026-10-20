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
        Set<String> columns = new LinkedHashSet<>(columns(rows));
        return new TypedLet(name, new TypedTableReference(store, table, rows, true, null, columns, false, Map.of(),
                false, false), letInfo);
    }

    /** The staged rows' columns ({@code rows}, a relation), in order, as their projection names them: the session's
     *  temporary table's, which the statements after read by these names (writes leg 4, 2026-10-20: a dialect whose
     *  database folds a stored column's name names the table's columns so). */
    public static java.util.List<String> columns(ExprType rows) {
        return Objects.requireNonNull(Type.schemaView(rows.type()), "staged rows are a relation").columns().stream()
                .map(Type.Column::name).toList();
    }
}
