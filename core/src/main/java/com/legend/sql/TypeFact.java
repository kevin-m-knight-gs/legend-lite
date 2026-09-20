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
    /** {@code nullable} — THE NULLABILITY DIMENSION (charter §E3,
     * M-N1): may this expression's value be SQL NULL? {@code false} is
     * a PROOF CLAIM (the wire never NULLs here — at the flip a NULL
     * under it is a compiler bug, loud); {@code true} is the safe
     * side, never a lie. Uncertainty therefore defaults TRUE — the
     * engine's own DDL doctrine (RelationalCompilerExtension:940,
     * unknown lowers to [0..1]). Computed at CONSTRUCTION through the
     * {@link SqlTyping} rule funnel (default scalar composition =
     * any-operand-nullable; per-fn exceptions probed on the 1.5.0
     * reference jar AS EMISSIONS, receipts on the arms); leaves enter
     * through the {@link SqlExpr.Column} doors (M-N1: the frame's
     * echo-derived {@link OutputCol#nullable()}; DDL/join-pad
     * authority is M-N2).
     *
     * <p>(The engine-compat {@code tolerated} tag lived here until the
     * wire-slot leg, docs/WIRE_SLOT_HOMEWORK_2026_09_19.md: an output slot
     * IS the wire now, so a declared/physical mismatch is a census fact —
     * declared kind from the schema against the slot — not a tag.) */
    record Typed(SqlType type, boolean nullable) implements TypeFact {
        public Typed(SqlType type) {
            this(type, false);
        }
    }

    record Bottom() implements TypeFact {
    }

    /** A RAISING expression ({@code error()}) — it never yields a
     * value, so it conforms to EVERY slot (type theory's divergence;
     * §4bZ-U leg 3): admissible in any branch family without
     * constraining it, never a projection root's type debt. Replaces
     * {@code uniform()}'s structural ERROR special-case with a fact
     * the tree carries. */
    record Raises() implements TypeFact {
    }

    record Unknown() implements TypeFact {
    }
}
