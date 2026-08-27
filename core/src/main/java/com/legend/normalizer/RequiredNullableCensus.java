// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend.normalizer;

import com.legend.compiler.ModelBuilder;
import com.legend.model.ClassDefinition;
import com.legend.model.DatabaseDefinition;
import com.legend.model.PropertyMapping;
import com.legend.model.RelationalOperation;
import com.legend.protocol.Multiplicity;

import java.util.HashSet;

/**
 * The [1]-OVER-NULLABLE-COLUMN census (typed-IR queue item 2; the
 * three-lane nullability map's unchecked implication): every
 * class-mapped property whose declared multiplicity REQUIRES a value
 * (lower &gt;= 1) bound to a column the store declaration leaves
 * NULLABLE (neither NOT NULL nor PRIMARY KEY). The engine trusts
 * store-read data and nothing checks "[1]-property &rArr; NOT NULL
 * column"; the relation paradigm derives multiplicities FROM the DDL,
 * the class-mapped lane never looks. Each such pairing is a spot where
 * the typed tree asserts always-present and the database may hand back
 * NULL — the static, compile-time-knowable slice of the wire-breach
 * census (E2E audit's 925).
 *
 * <p>Deliberately a CENSUS, not a warning: on the engine's own fixture
 * models it fires wholesale (fixture debt — wrong audience); the
 * user-facing diagnostic waits for the dialect-levels split, and this
 * instrument is its firing list. Hooked at the {@link DeclaredCoercions}
 * pairing seam (BEFORE the kind checks' early returns, so every pairing
 * is seen).
 *
 * <p>Rows accumulate on the COMPILE'S OWN {@link ModelBuilder}
 * ({@code requiredNullableRows()}), never in static state — the fact is
 * a per-model compile-time fact, and a global sink would bleed
 * unrelated models' rows into each other in long-lived processes.
 * Readers go through {@code ModelContext.requiredNullableCensus()};
 * the corpus harness AGGREGATES across its models and pins.
 *
 * <p>Buckets: {@code direct} (plain column PMs) and {@code join-terminal}
 * (join-chain terminal reads), plus HONESTY buckets for pairings the
 * adjudication cannot resolve ({@code unresolved-property}: the owner
 * chain doesn't declare the name — association-end injections;
 * {@code unresolved-column}: scope-block reads with no [db], columns
 * the store walk misses) — a silent skip would read as covered.
 */
final class RequiredNullableCensus {

    private RequiredNullableCensus() {
    }

    static void noteDirect(PropertyMapping.Column col,
            @com.legend.Nullable String ownerClassFqn, ModelBuilder model) {
        pair(col.propertyName(), ownerClassFqn, col.database(),
                col.table(), col.column(), "direct", model);
    }

    static void noteJoinTerminal(PropertyMapping.JoinTerminalColumn jtc,
            @com.legend.Nullable String ownerClassFqn, ModelBuilder model) {
        if (!(jtc.terminalColumn()
                instanceof RelationalOperation.ColumnRef cr)) {
            return;
        }
        pair(jtc.propertyName(), ownerClassFqn,
                cr.databaseName() != null ? cr.databaseName()
                        : jtc.database(),
                cr.table(), cr.column(), "join-terminal", model);
    }

    private static void pair(String propName,
            @com.legend.Nullable String ownerClassFqn,
            @com.legend.Nullable String db, String table, String column,
            String bucket, ModelBuilder model) {
        if (ownerClassFqn == null) {
            return;
        }
        ClassDefinition owner = MissProbe.knownMiss(
                model.findClass(ownerClassFqn));
        ClassDefinition.PropertyDefinition prop = owner == null ? null
                : MappingNormalizer.findPropertyDefDeep(owner, propName,
                        model, new HashSet<>());
        if (prop == null) {
            note(model, "unresolved-property",
                    ownerClassFqn + "." + propName);
            return;
        }
        if (!(prop.multiplicity() instanceof Multiplicity.Concrete c)
                || c.lowerBound() < 1) {
            return;
        }
        DatabaseDefinition.ColumnDefinition cd = db == null ? null
                : MappingNormalizer.findPhysicalColumn(db, table, column,
                        model);
        if (cd == null) {
            note(model, "unresolved-column", (db == null ? "<no-db>" : db)
                    + "." + table + "." + column);
            return;
        }
        if (cd.primaryKey() || cd.notNull()) {
            return;
        }
        note(model, bucket, ownerClassFqn + "." + propName + multText(c)
                + " over " + table + "." + column);
    }

    private static String multText(Multiplicity.Concrete c) {
        if (c.upperBound() == null) {
            return "[" + c.lowerBound() + "..*]";
        }
        if (c.upperBound() == c.lowerBound()) {
            return "[" + c.lowerBound() + "]";
        }
        return "[" + c.lowerBound() + ".." + c.upperBound() + "]";
    }

    private static void note(ModelBuilder model, String bucket,
            String witness) {
        model.requiredNullableRows().computeIfAbsent(bucket,
                k -> new java.util.TreeSet<>()).add(witness);
    }
}
