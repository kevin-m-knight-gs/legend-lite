// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend.resolver;

import com.legend.compiler.element.ModelContext;
import com.legend.model.ClassMapping;
import com.legend.model.DatabaseDefinition;

/**
 * Leg 4 — views as identity-carrying frames: a class whose ~mainTable is
 * a VIEW joins as a derived table NAMED BY THE VIEW (the engine's
 * relational model makes a view a {@code Table}; its alias groups by the
 * view's own name — {@code orderpnlview_0}, never the underlying
 * physical table's).
 */
public final class ViewFrames {

    private ViewFrames() {
    }

    /** The VIEW name behind {@code cs}'s main source, or null when the
     * class maps a physical table (or the lookup cannot resolve). Walks
     * the mapping INCLUDE CLOSURE — the class set may live in an
     * included mapping (modelJoins' LegalEntityMapping). */
    public static @com.legend.Nullable String frameNameOf(ModelContext ctx, ClassSource cs) {
        if (ctx == null || cs == null) {
            return null;
        }
        var md = ctx.findLegacyMapping(cs.mappingFqn()).orElse(null);
        if (md == null) {
            return null;
        }
        java.util.List<ClassMapping> cms = new java.util.ArrayList<>();
        java.util.Set<String> seen = new java.util.LinkedHashSet<>();
        java.util.ArrayDeque<com.legend.model.LegacyMappingDefinition> work =
                new java.util.ArrayDeque<>();
        work.add(md);
        while (!work.isEmpty()) {
            var m = work.poll();
            if (!seen.add(m.qualifiedName())) {
                continue;
            }
            cms.addAll(m.classMappings());
            for (var inc : m.includes()) {
                ctx.findLegacyMapping(inc.mappingPath()).ifPresent(work::add);
            }
        }
        for (var cm : cms) {
            if (!(cm instanceof ClassMapping.Relational r)
                    || !r.className().equals(cs.classFqn())) {
                continue;
            }
            String table = null;
            String database = null;
            if (r.mainTable() != null) {
                table = r.mainTable().table();
                database = r.mainTable().database();
            } else {
                // scope(...)-inferred main source: the parser distributes
                // the scope table onto the Column PMs
                for (var pm : r.propertyMappings()) {
                    if (pm instanceof com.legend.model.PropertyMapping.Column c) {
                        table = c.table();
                        database = c.database();
                        break;
                    }
                }
            }
            if (table == null || database == null) {
                continue;
            }
            if (table.startsWith("default.")) {
                table = table.substring("default.".length());
            }
            DatabaseDefinition db =
                    ctx.findDatabase(database).orElse(null);
            if (db == null) {
                return null;
            }
            // SCHEMA-QUALIFIED view refs (Entity.LegalEntity_View): the
            // view registry keys by BARE name within its schema
            String t = table.contains(".")
                    ? table.substring(table.lastIndexOf('.') + 1)
                    : table;
            if (db.views().stream().anyMatch(v -> v.name().equals(t))) {
                return t;
            }
            for (var sch : db.schemas()) {
                if (sch.views().stream().anyMatch(v -> v.name().equals(t))) {
                    return t;
                }
            }
            return null;
        }
        return null;
    }
}
