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
     * class maps a physical table (or the lookup cannot resolve). */
    public static String frameNameOf(ModelContext ctx, ClassSource cs) {
        if (ctx == null || cs == null) {
            return null;
        }
        var md = ctx.findLegacyMapping(cs.mappingFqn()).orElse(null);
        if (md == null) {
            return null;
        }
        for (var cm : md.classMappings()) {
            if (!(cm instanceof ClassMapping.Relational r)
                    || !r.className().equals(cs.classFqn())
                    || r.mainTable() == null) {
                continue;
            }
            String table = r.mainTable().table();
            if (table.startsWith("default.")) {
                table = table.substring("default.".length());
            }
            DatabaseDefinition db =
                    ctx.findDatabase(r.mainTable().database()).orElse(null);
            if (db == null) {
                return null;
            }
            String t = table;
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
