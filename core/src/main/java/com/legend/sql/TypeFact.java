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
    /** {@code tolerated} — the ENGINE-COMPAT carry-through provenance
     * (charter §4bZ): this value was read across a DECLARED
     * property/column kind mismatch at the mapping seam (the one site
     * that knows the pairing), where the engine's contract is raw
     * carry-through (no conversion, no check — engine source
     * receipts). Set ONLY by the mapping seam's doors; transported by
     * identity-preserving rules; consumed by label reconciliation —
     * a label/wire mismatch is tolerated ONLY when the value carries
     * this tag, so an untagged mismatch (a compiler accident) goes
     * loud instead of being blanket-forgiven. */
    record Typed(SqlType type, boolean tolerated) implements TypeFact {
        public Typed(SqlType type) {
            this(type, false);
        }
    }

    record Bottom() implements TypeFact {
    }

    record Unknown() implements TypeFact {
    }
}
