// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend.compiler.spec.typed;

import com.legend.compiler.element.type.ExprType;

import java.util.List;

/**
 * An AUTHORED-SQL relation source (One-Platform Plan Phase 1c,
 * user-ratified design): the ordered, materialized rows of one raw
 * statement — {@code executeInDb(sql, ...).rows} and the fetchDb
 * catalog grids. The {@code TypedTds} family's sibling: a relation
 * SOURCE whose columns are LATE-BOUND (the dynamic-pivot precedent —
 * nothing is known statically, so {@link #info()} carries an
 * empty-column {@code RelationType}; by-NAME reads carry their own
 * column names, and schema-shaped reads pin at the probe).
 *
 * <p>The carried {@code sql} is USER/CORPUS-AUTHORED text (the
 * {@code executeInDb} argument, or the registered catalog SQL) — never
 * platform-composed; the RawSql quarantine's contract rides from here
 * to {@code SqlSource.RawSql}. Backend adaptation of the authored text
 * is the DIALECT's own (decision D2: the engine's H2 text translated,
 * any other run as written), never this node's business.
 *
 * @param sql      the authored statement text (single statement, no
 *                 trailing {@code ;})
 * @param authored whether the text is authored for a connection (an
 *                 {@code executeInDb} argument), not the platform's own
 *                 (a catalog grid's, written in the session's language)
 * @param database the database that connection declares (that type's
 *                 name, read at typing); null when it could not be read
 * @param info     an empty-column relation type — columns are late-bound
 */
public record TypedRawSqlRelation(String sql, boolean authored, @com.legend.base.Nullable String database,
        ExprType info) implements TypedSpec {

    @Override
    public List<TypedSpec> children() {
        return List.of();
    }

    @Override
    public TypedSpec withChildren(List<TypedSpec> kids) {
        TypedSpec.expectChildren(kids, 0, "TypedRawSqlRelation");
        return this;
    }

    /** The database the text is written for, as its connection declares it (decision D2; that type's name); null for
     *  the platform's own text. Authored text whose connection could not be read is refused when it runs: the
     *  language of its text is unknown. */
    public @com.legend.base.Nullable String writtenFor() {
        if (authored && database == null) {
            throw ExecutionContext.unknownLanguage("raw SQL");
        }
        return database;
    }

    /** The property read behind a (possibly toOne-wrapped) LATE-BOUND
     * grid cell expression — the trust-name rule's read shape; null
     * otherwise. Consumers treat such a cell as PHYSICAL (the
     * database's own value, never the Any-JSON carrier). */
    public static @com.legend.base.Nullable TypedPropertyAccess lateBoundCellRead(
            TypedSpec n) {
        while (n instanceof TypedNativeCall w && w.args().size() == 1
                && com.legend.builtin.Pure.isToOneCall(w.callee().qualifiedName())) {
            n = w.args().get(0);
        }
        return n instanceof TypedPropertyAccess pa
                && com.legend.compiler.element.type.Type
                        .schemaView(pa.source().info().type()) instanceof
                        com.legend.compiler.element.type.Type.RelationType rt
                && rt.isLateBound() ? pa : null;
    }
    @Override
    public TypedSpec withInfo(ExprType info) {
        return new TypedRawSqlRelation(sql, authored, database, info);
    }
}
