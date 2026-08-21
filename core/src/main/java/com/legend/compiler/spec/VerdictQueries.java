// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend.compiler.spec;

import com.legend.compiler.element.type.ExprType;
import com.legend.compiler.element.type.Multiplicity;
import com.legend.compiler.element.type.Type;
import com.legend.compiler.spec.typed.TypedLambda;
import com.legend.compiler.spec.typed.TypedMap;
import com.legend.compiler.spec.typed.TypedSpec;

import java.util.List;

/**
 * The K-arm verdict channel's QUERY SYNTHESIS (Invariant 7: minting
 * typed nodes is compiler work): the assert family's in-database
 * evaluations are BUILT here; {@code AssertVerdicts} fetches the
 * results and keeps the JUDGMENT host-side (Clause 2c — arguments
 * execute in the database, the verdict is World 1's). This class is the
 * seed of the canonical-render verdicts leg: when asserts move to
 * compiler-known canonical serialization with byte-compare, that
 * emission joins this owner.
 */
public final class VerdictQueries {

    private VerdictQueries() {
    }

    /** The predicate VECTOR for a quantified assert
     * ({@code source->map(binder|assert(pred, msg))}): same source, same
     * binder, the assert's CONDITION as the mapper body — one boolean
     * per row, computed in the database. */
    public static TypedSpec predicateVector(TypedMap quantified,
            TypedLambda lam, TypedSpec condition) {
        TypedLambda predLam = new TypedLambda(lam.parameters(),
                List.of(condition), lam.info());
        return new TypedMap(quantified.source(), predLam,
                new ExprType(Type.Primitive.BOOLEAN,
                        Multiplicity.Bounded.ZERO_MANY));
    }
}
