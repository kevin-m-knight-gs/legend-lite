// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend.sql;

/**
 * An expression's TYPE FACT (TYPED_SQL_IR.md §2), stored on every
 * {@link SqlExpr} node at construction: {@link Typed} — the expression
 * produces exactly that {@link SqlType}; {@link Bottom} — the NULL
 * value, admissible in any nullable slot (type theory's &perp;;
 * DuckDB's own internal SQLNULL, resolved by context — deliberately
 * NOT pure's {@code Nil}: pure's bottom types empty COLLECTIONS and
 * pure has no null value); {@link Unknown} — no typing rule yet,
 * counted by the census, never guessed.
 *
 * <p>(Renamed from {@code TypeFact} when the transitional
 * judge deleted — the fact was never a judgment; it is a property of
 * the tree.)
 */
public sealed interface TypeFact {
    record Typed(SqlType type) implements TypeFact {
    }

    record Bottom() implements TypeFact {
    }

    record Unknown() implements TypeFact {
    }
}
