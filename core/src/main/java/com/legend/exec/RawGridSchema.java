// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend.exec;

import com.legend.compiler.element.type.ExprType;
import com.legend.compiler.element.type.Multiplicity;
import com.legend.compiler.element.type.PlatformTypes;
import com.legend.compiler.element.type.Type;
import com.legend.compiler.spec.typed.TypedRawSqlRelation;
import com.legend.compiler.spec.typed.TypedSpec;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * The EXECUTION-BOUNDARY schema stamp (One-Platform Plan Phase 1c —
 * the dynamic-pivot rule): a raw-SQL grid's columns first exist where a
 * session exists, so the compiler types them LATE-BOUND
 * ({@link Type.RelationType#lateBound()}) and this pass resolves them
 * here — one LIMIT-0 metadata read per distinct grid (schema, never
 * values; the E1 probe discipline), stamped onto the typed node before
 * lowering. Exactly where pivot resolves its data-derived names
 * ({@code Executor}'s template matching); the compiler never probes.
 *
 * <p>Stamped columns are {@code Any[0..1]} — the SQL layer needs the
 * NAMES (its outputs invariant: every source stamps its schema); cell
 * TYPES stay the database's own, decoded at egress by the ordinary
 * carrier rules.
 */
public final class RawGridSchema {

    private RawGridSchema() {
    }

    /** The tree with every late-bound raw grid's schema stamped. */
    public static List<TypedSpec> stamp(List<TypedSpec> body,
            Connection conn, com.legend.sql.dialect.SqlDialect dialect)
            throws SQLException {
        List<TypedSpec> out = new ArrayList<>(body.size());
        boolean changed = false;
        for (TypedSpec n : body) {
            TypedSpec s = stampNode(n, conn, dialect);
            changed |= s != n;
            out.add(s);
        }
        return changed ? out : body;
    }

    private static TypedSpec stampNode(TypedSpec n, Connection conn,
            com.legend.sql.dialect.SqlDialect dialect) throws SQLException {
        if (n instanceof TypedRawSqlRelation raw
                && raw.info().type() instanceof Type.RelationType rt
                && rt.isLateBound()) {
            List<Type.Column> cols = new ArrayList<>();
            Type any = new Type.ClassType(PlatformTypes.ANY);
            for (String nm : ResultNav.probeNames(raw.sql(), conn, dialect)) {
                cols.add(new Type.Column(nm, any,
                        Multiplicity.Bounded.ZERO_ONE));
            }
            return new TypedRawSqlRelation(raw.sql(),
                    new ExprType(new Type.RelationType(cols, List.of()),
                            raw.info().multiplicity()));
        }
        List<TypedSpec> kids = n.children();
        if (kids.isEmpty()) {
            return n;
        }
        List<TypedSpec> out = new ArrayList<>(kids.size());
        boolean changed = false;
        for (TypedSpec k : kids) {
            TypedSpec s = stampNode(k, conn, dialect);
            changed |= s != k;
            out.add(s);
        }
        return changed ? n.withChildren(out) : n;
    }
}
