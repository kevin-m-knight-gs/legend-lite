// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend.lowering;

import com.legend.compiler.element.type.Multiplicity;
import com.legend.compiler.spec.typed.TypedNativeCall;
import com.legend.compiler.spec.typed.TypedPropertyAccess;
import com.legend.compiler.spec.typed.TypedSpec;
import com.legend.compiler.spec.typed.TypedVariable;
import com.legend.sql.SqlExpr;
import com.legend.sql.SqlType;

/**
 * THE MULTIPLICITY-STAMP INSPECTOR (host-logic audit 2026-08-20, stamp
 * program step 3 built FIRST as a measurement instrument): at the scalar
 * lowering funnel, compare each expression's PURE multiplicity stamp
 * against the PROVABLE shape of the SQL it lowered to, and log every
 * definite mismatch. The census this produces is the worklist for the
 * stamp-discipline fix ({@code ListShapes}' own header documents the
 * defect this measures: "pure multiplicity stamps are unreliable after
 * substitution"); once stamps are enforced-true, this instrument becomes
 * the permanent post-lowering INVARIANT and the shape-sniffing dies.
 *
 * <p>MEASUREMENT ONLY — never changes behavior; env-gated
 * ({@code LL_STAMP_COUNT=1}); conservative by construction: only
 * PROVABLE shapes count (a definite list value under a to-one stamp; a
 * definite scalar literal under a many stamp). Unknowable shapes
 * (column reads, opaque calls) are never reported — absence of a line
 * is not proof of health, presence IS proof of a lie.
 */
final class StampCensus {

    private StampCensus() {
    }

    private static final boolean ON =
            System.getenv("LL_STAMP_COUNT") != null;

    /** The scalar-funnel hook: spec's stamp vs the lowered expression's
     * provable shape. */
    static void check(TypedSpec spec, SqlExpr e) {
        if (!ON) {
            return;
        }
        Multiplicity m = spec.info().multiplicity();
        if (!(m instanceof Multiplicity.Bounded b)) {
            // a VARIABLE stamp surviving to lowering is its own finding —
            // lowering should only ever see concrete bounds
            System.err.println("[stamp] VAR-STAMP-AT-LOWERING "
                    + digest(spec));
            return;
        }
        boolean scalarStamp = b.upper() != null && b.upper() <= 1;
        // NullLit is shape-AMBIGUOUS by the carrier convention (SQL NULL
        // carries pure's EMPTY in scalar AND list positions — ListShapes
        // classifies it list-shaped for list consumers, but under a
        // scalar stamp it is the designed [0..1] empty): never a lie.
        if (e instanceof SqlExpr.NullLit) {
            return;
        }
        if (scalarStamp && ListShapes.listShaped(e)) {
            System.err.println("[stamp] ONE-STAMP/LIST-SHAPE mult=["
                    + b.lower() + ".." + b.upper() + "] sql="
                    + e.getClass().getSimpleName() + " " + digest(spec));
            return;
        }
        if (!scalarStamp && definitelyScalar(e)) {
            System.err.println("[stamp] MANY-STAMP/SCALAR-SHAPE mult=["
                    + b.lower() + ".." + (b.upper() == null ? "*" : b.upper())
                    + "] sql=" + e.getClass().getSimpleName() + " "
                    + digest(spec));
        }
    }

    /** PROVABLY a single scalar value — literals and scalar-typed casts
     * only (a column or call could carry a list; never guessed). */
    private static boolean definitelyScalar(SqlExpr e) {
        return switch (e) {
            case SqlExpr.IntLit ignored -> true;
            case SqlExpr.FloatLit ignored -> true;
            case SqlExpr.DecimalLit ignored -> true;
            case SqlExpr.StringLit ignored -> true;
            case SqlExpr.BoolLit ignored -> true;
            case SqlExpr.DateLit ignored -> true;
            case SqlExpr.TimestampLit ignored -> true;
            case SqlExpr.Cast c -> c.target() instanceof SqlType.Scalar;
            default -> false;
        };
    }

    /** Cause-classification digest: the node kind plus the ONE detail
     * that names its origin (callee FQN, property, variable). */
    private static String digest(TypedSpec spec) {
        StringBuilder sb = new StringBuilder("node=")
                .append(spec.getClass().getSimpleName());
        if (spec instanceof TypedNativeCall c) {
            sb.append(" callee=").append(c.callee().qualifiedName());
        } else if (spec instanceof TypedPropertyAccess p) {
            sb.append(" prop=").append(p.property());
        } else if (spec instanceof TypedVariable v) {
            sb.append(" name=").append(v.name());
        }
        return sb.toString();
    }
}
