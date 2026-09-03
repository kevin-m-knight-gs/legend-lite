// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend.normalizer;

import com.legend.compiler.ModelBuilder;
import com.legend.model.DatabaseDefinition;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The physical TABLE definition behind a mapping's table name &mdash;
 * schema-aware, include-walking (the table half of
 * {@code MappingNormalizer.findPhysicalColumn}), for whole-table facts such
 * as the declared PRIMARY KEY.
 */
final class PhysicalTables {

    private PhysicalTables() {
    }

    static DatabaseDefinition.@com.legend.Nullable TableDefinition find(
            @com.legend.Nullable String dbFqn, @com.legend.Nullable String table,
            ModelBuilder model) {
        if (dbFqn == null || table == null) {
            return null;
        }
        String t = MappingNormalizer.canonicalTable(table);
        String schema = null;
        int dot = t.indexOf('.');
        if (dot > 0) {
            schema = t.substring(0, dot);
            t = t.substring(dot + 1);
        }
        return find(dbFqn, schema, t, model, new HashSet<>());
    }

    private static DatabaseDefinition.@com.legend.Nullable TableDefinition find(
            String dbFqn, @com.legend.Nullable String schema, String table,
            ModelBuilder model, Set<String> seen) {
        if (!seen.add(dbFqn)) {
            return null;
        }
        DatabaseDefinition db = model.findDatabase(dbFqn).orElse(null);
        if (db == null) {
            return null;
        }
        List<DatabaseDefinition.TableDefinition> tables = new ArrayList<>(db.tables());
        for (DatabaseDefinition.SchemaDefinition s : db.schemas()) {
            if (schema == null || s.name().equals(schema)) {
                tables.addAll(s.tables());
            }
        }
        for (DatabaseDefinition.TableDefinition td : tables) {
            if (td.name().equalsIgnoreCase(table)) {
                return td;
            }
        }
        for (String inc : db.includes()) {
            DatabaseDefinition.TableDefinition hit = find(inc, schema, table, model, seen);
            if (hit != null) {
                return hit;
            }
        }
        return null;
    }
}
