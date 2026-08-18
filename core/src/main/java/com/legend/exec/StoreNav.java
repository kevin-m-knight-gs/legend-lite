// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend.exec;

import com.legend.compiler.element.ModelContext;
import com.legend.compiler.element.type.PlatformTypes;
import com.legend.compiler.spec.typed.TypedCString;
import com.legend.compiler.spec.typed.TypedNativeCall;
import com.legend.compiler.spec.typed.TypedPackageableRef;
import com.legend.compiler.spec.typed.TypedSpec;
import com.legend.compiler.spec.typed.TypedVariable;
import com.legend.model.DatabaseDefinition;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * STORE NAVIGATION over the COMPILED MODEL (oracle-not-runtime
 * principle, user-ratified): {@code db->schema('s')->toOne()->table('t')}
 * chains are MODEL-FACT lookups — our own Java walking our own compiled
 * store definitions (the engine's include-closure rule,
 * functions.pure:227-235), never an interpretation of engine source.
 * Presence semantics: a missing schema/table is the EMPTY value (the
 * corpus consumers are emptiness asserts). Unrecognized shapes return
 * null — the seam walls loudly.
 */
public final class StoreNav {

    private StoreNav() {
    }

    /** A found schema, carrying its include-closure-merged tables. */
    public record SchemaHandle(String name,
            List<DatabaseDefinition.TableDefinition> tables) {
    }

    public static @com.legend.Nullable ExecutionResult tryEval(
            TypedSpec root, Map<String, TypedSpec> lets, ModelContext ctx) {
        List<Object> v = nav(resolve(root, lets), lets, ctx);
        return v == null ? null
                : new ExecutionResult.Collection(new ArrayList<>(v),
                        root.info().type());
    }

    private static @com.legend.Nullable List<Object> nav(TypedSpec n,
            Map<String, TypedSpec> lets, ModelContext ctx) {
        n = resolve(n, lets);
        if (n instanceof TypedNativeCall nc && nc.args().size() == 1
                && "meta::pure::functions::multiplicity::toOne"
                        .equals(nc.callee().qualifiedName())) {
            return nav(nc.args().get(0), lets, ctx);
        }
        if (!(n instanceof TypedNativeCall nc)
                || !PlatformTypes.isStoreNavFn(nc.callee().qualifiedName())
                || nc.args().size() != 2
                || !(resolve(nc.args().get(1), lets)
                        instanceof TypedCString name)) {
            return null;
        }
        if (PlatformTypes.STORE_SCHEMA_NAV
                .equals(nc.callee().qualifiedName())) {
            if (!(resolve(nc.args().get(0), lets)
                    instanceof TypedPackageableRef db)) {
                return null;
            }
            List<DatabaseDefinition.TableDefinition> tables =
                    new ArrayList<>();
            boolean[] found = {false};
            collectSchema(ctx, db.fullPath(), name.value(), tables, found,
                    new java.util.LinkedHashSet<>());
            return found[0]
                    ? List.of(new SchemaHandle(name.value(), tables))
                    : List.of();
        }
        // table(schemaChain, 'name')
        List<Object> schema = nav(nc.args().get(0), lets, ctx);
        if (schema == null) {
            return null;
        }
        if (schema.isEmpty()
                || !(schema.get(0) instanceof SchemaHandle sh)) {
            return List.of();
        }
        for (DatabaseDefinition.TableDefinition t : sh.tables()) {
            if (t.name().equals(name.value())) {
                return List.of(t);
            }
        }
        return List.of();
    }

    /** The engine's include-closure schema lookup with MERGED tables
     * (functions.pure:227-235); top-level tables are 'default'. */
    private static void collectSchema(ModelContext ctx, String dbFqn,
            String schemaName,
            List<DatabaseDefinition.TableDefinition> out, boolean[] found,
            java.util.Set<String> seen) {
        if (!seen.add(dbFqn)) {
            return;
        }
        var dbo = ctx.findDatabase(dbFqn);
        if (dbo.isEmpty()) {
            return;
        }
        DatabaseDefinition db = dbo.get();
        for (String inc : db.includes()) {
            collectSchema(ctx, inc, schemaName, out, found, seen);
        }
        if ("default".equals(schemaName)) {
            found[0] = true;
            out.addAll(db.tables());
            return;
        }
        for (DatabaseDefinition.SchemaDefinition sd : db.schemas()) {
            if (sd.name().equals(schemaName)) {
                found[0] = true;
                out.addAll(sd.tables());
            }
        }
    }

    private static TypedSpec resolve(TypedSpec n,
            Map<String, TypedSpec> lets) {
        int guard = 0;
        while (n instanceof TypedVariable v && guard++ < 32) {
            TypedSpec bound = lets.get(v.name());
            if (bound == null) {
                return n;
            }
            n = bound;
        }
        return n;
    }
}
