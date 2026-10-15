// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend.sql;

import java.util.List;

/**
 * Row-writing statements as SQL IR (2026-09-23): the dialect renders them as
 * it renders a query and a {@link SqlDdl} &mdash; its identifier rule, its
 * literal spelling. A table is named as DDL names it ({@code schema} null or
 * {@code default}: bare): its names' text, and which its store declared quoted ({@link NameQuoting}), spelled once,
 * by the dialect. So is a column: {@code columns} holds their text, {@code quotedColumns} the ones the store declared
 * quoted ({@code "Amt"}), and the dialect spells each as its queries reference it ({@code SqlDialect.storedName}).
 */
public sealed interface SqlDml permits SqlDml.InsertValues, SqlDml.InsertFromTable, SqlDml.DeleteAll {

    /** {@code INSERT INTO [schema.]table [(columns)] VALUES (row), ...};
     *  {@code columns} empty: every column in declared order. */
    record InsertValues(@com.legend.base.Nullable String schema, String table, NameQuoting quoting, List<String> columns,
                        java.util.Set<String> quotedColumns, List<List<SqlExpr>> rows) implements SqlDml {
        public InsertValues {
            columns = List.copyOf(columns);
            quotedColumns = java.util.Collections.unmodifiableSet(new java.util.LinkedHashSet<>(quotedColumns));
            rows = List.copyOf(rows);
        }
    }

    /** {@code INSERT INTO [schema.]table [(columns)] SELECT * FROM source}: a
     *  staged table's rows, cast by the database into the target's columns. */
    record InsertFromTable(@com.legend.base.Nullable String schema, String table, NameQuoting quoting,
                           List<String> columns, java.util.Set<String> quotedColumns, String source) implements SqlDml {
        public InsertFromTable {
            columns = List.copyOf(columns);
            quotedColumns = java.util.Collections.unmodifiableSet(new java.util.LinkedHashSet<>(quotedColumns));
        }
    }

    /** {@code DELETE FROM [schema.]table}: every row. */
    record DeleteAll(@com.legend.base.Nullable String schema, String table, NameQuoting quoting) implements SqlDml {
    }
}
