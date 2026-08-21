// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend.lowering;

import com.legend.compiler.element.type.Multiplicity;
import com.legend.compiler.spec.typed.TypedSpec;

/**
 * THE stamp-reading authority for the lowering layer (D4 burn-down,
 * STAMP_DISCIPLINE_PROGRAM): the singleton-identity principle —
 * <em>auto-collection means an operation over at most one value IS the
 * value</em> — was restated as ~30 hand-rolled ternaries across the
 * scalar rules, each re-deriving "is this stamp at most one?" with
 * slightly different spellings (isToOne = upper==1 missed [0..0]; the
 * sort/reverse arms grew their own bounded checks). One principle, one
 * reader: rules ask THESE predicates and never touch Multiplicity
 * internals again.
 */
final class Stamps {

    private Stamps() {
    }

    /** upper == 1 exactly ([0..1], [1..1]) — the historical isToOne
     * reading, preserved VERBATIM for the reduction rules: their
     * identity arms are correct for one value but WRONG for the empty
     * (and([]) is true, joinStrings([]) is '' — the empty-identity
     * lives in the list arm's COALESCE). DISCOVERED FORK: a [0..1]
     * operand that is empty AT RUNTIME takes the identity arm and
     * yields NULL where pure defines an empty identity — WRONG ANSWERS
     * user-visible (and()/or()/joinStrings''/makeString leaks the
     * TDSNull sentinel). OWNED: slice 4 of the multiplicity-audit
     * program (docs/MULTIPLICITY_AUDIT_2026_08_20.md §4; the earlier
     * "PCT lane" attribution had no ledger row and was fictional). */
    static boolean toOne(TypedSpec spec) {
        return spec.info().multiplicity() instanceof Multiplicity.Bounded b
                && Integer.valueOf(1).equals(b.upper());
    }

    /** The stamp admits AT MOST ONE value ([0..0], [0..1], [1..1]) —
     * the auto-collection singleton frame: correct for COLLECTION ops
     * (sort/reverse/distinct: op([]) is [] and NULL carries it) and
     * for the frame dispatches; NOT for reduction identity arms (see
     * {@link #toOne}). */
    static boolean atMostOne(TypedSpec spec) {
        return spec.info().multiplicity() instanceof Multiplicity.Bounded b
                && b.upper() != null && b.upper() <= 1;
    }

    /** The stamp admits MORE than one value — the collection frame:
     * the SQL carrier is a list (the invariant's contract). */
    static boolean many(TypedSpec spec) {
        return !(spec.info().multiplicity() instanceof Multiplicity.Bounded b
                && b.upper() != null && b.upper() <= 1);
    }

    /** Exactly [1..1] — a value that is always present. */
    static boolean exactlyOne(TypedSpec spec) {
        return spec.info().multiplicity() instanceof Multiplicity.Bounded b
                && b.lower() == 1 && Integer.valueOf(1).equals(b.upper());
    }
}
