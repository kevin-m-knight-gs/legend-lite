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
 * substitution").
 *
 * <p>FLIPPED TO THE INVARIANT (2026-08-20, census zero on the full
 * corpus AND all five PCT suites): a provable stamp/shape lie now
 * THROWS at the funnel — always on, build-breaking. {@code
 * LL_STAMP_COUNT=1} downgrades to the original print-and-continue
 * census mode for measurement sweeps. Conservative by construction:
 * only PROVABLE shapes fire (a definite list value under a to-one
 * stamp; a definite scalar literal under a many stamp) and the
 * DESIGNED (stamp, carrier) table below names every adjudicated
 * convention. Unknowable shapes (column reads, opaque calls) never
 * fire — absence is not proof of health, but firing IS proof of a lie.
 */
public final class StampCensus {

    private StampCensus() {
    }

    /** Census mode: print-and-continue (measurement sweeps). Without
     * it the invariant THROWS — the permanent build-breaking check. */
    private static final boolean COUNT_MODE =
            System.getenv("LL_STAMP_COUNT") != null;

    /** The test/query currently compiling — set by harness runners so a
     * census line names its witness (the H2Verify.CURRENT_TEST pattern;
     * main-scope holder because the lowering cannot see test classes). */
    public static final ThreadLocal<String> CONTEXT =
            ThreadLocal.withInitial(() -> "<unattributed>");


    /** The scalar-funnel hook: spec's stamp vs the lowered expression's
     * provable shape. */
    static void check(TypedSpec spec, SqlExpr e) {
        Multiplicity m = spec.info().multiplicity();
        if (!(m instanceof Multiplicity.Bounded b)) {
            // a VARIABLE stamp surviving to lowering is its own finding —
            // lowering should only ever see concrete bounds
            fire("VAR-STAMP-AT-LOWERING " + digest(spec)
                    + " test=" + CONTEXT.get());
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
        // DESIGNED (stamp, carrier) pairs — the FRAME-AWARE table (the
        // invariant flip's contract; each row is an ADJUDICATED carrier
        // convention, never a lie):
        // 1. A RELATION-typed node's scalar stamp describes the relation
        //    VALUE (one relation); the LIST/collect SQL is its designed
        //    row-collection carrier (TDS distinct/sort heads).
        // 2. An INSTANCE ctor's scalar stamp describes one instance; the
        //    ArrayLit is the struct/canonical-layout carrier
        //    (STRUCT_VALUES design).
        // 3. A platform List<T> object is ONE value carried as the SQL
        //    array (list() — engine List semantics).
        // 4. A MANY-stamped property read with scalar SQL is the PER-ROW
        //    frame of the same read (C5 adjudication: the collection
        //    stamp describes the pure value, the scalar Cast the row
        //    frame — both true in their own frame).
        if (spec.info().type()
                instanceof com.legend.compiler.element.type.Type.RelationType) {
            return;
        }
        if (spec instanceof com.legend.compiler.spec.typed.TypedNewInstance
                && e instanceof SqlExpr.ArrayLit) {
            return;
        }
        if (com.legend.compiler.element.type.PlatformTypes.isListCarrier(spec.info().type())) {
            return;
        }
        if (!scalarStamp && spec instanceof TypedPropertyAccess) {
            return;
        }
        if (scalarStamp && ListShapes.listShaped(e)) {
            fire("ONE-STAMP/LIST-SHAPE mult=[" + b.lower() + ".."
                    + b.upper() + "] sql=" + e.getClass().getSimpleName()
                    + " " + digest(spec) + " test=" + CONTEXT.get());
            return;
        }
        if (!scalarStamp && definitelyScalar(e)) {
            fire("MANY-STAMP/SCALAR-SHAPE mult=[" + b.lower() + ".."
                    + (b.upper() == null ? "*" : b.upper()) + "] sql="
                    + e.getClass().getSimpleName() + " " + digest(spec)
                    + " test=" + CONTEXT.get());
        }
    }

    /** Count mode prints; invariant mode THROWS — a provable stamp lie
     * is a compiler bug, never a value to compute with. */
    private static void fire(String line) {
        if (COUNT_MODE) {
            System.err.println("[stamp] " + line);
            return;
        }
        throw new IllegalStateException(
                "MULTIPLICITY-STAMP INVARIANT VIOLATED (stamp program,"
                + " docs/STAMP_DISCIPLINE_PROGRAM.md): " + line);
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
            if (!c.args().isEmpty()) {
                TypedSpec a0 = c.args().get(0);
                sb.append(" arg0=").append(a0.getClass().getSimpleName());
                if (a0 instanceof TypedNativeCall ic) {
                    sb.append('(').append(ic.callee().qualifiedName()
                            .substring(ic.callee().qualifiedName()
                                    .lastIndexOf(':') + 1)).append(')');
                } else if (a0 instanceof TypedPropertyAccess ip) {
                    sb.append('(').append(ip.property()).append(')');
                }
            }
        } else if (spec instanceof TypedPropertyAccess p) {
            sb.append(" prop=").append(p.property());
        } else if (spec instanceof TypedVariable v) {
            sb.append(" name=").append(v.name());
        }
        return sb.toString();
    }
}
