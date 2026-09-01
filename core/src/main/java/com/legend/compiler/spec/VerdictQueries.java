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

    /** The SQL-TEXT arm's OUR-ROWS query (SQLTEXT charter §3.5c —
     * Appendix A): the producer's own query lambda body wrapped in
     * {@code from(<the producer's mapping>)}, runtime left to the
     * executing env. SqlTextVerdicts fetches and judges; the mint is
     * compiler emission (Invariant 7). */
    public static TypedSpec fromWrapped(TypedSpec query,
            com.legend.compiler.spec.typed.TypedPackageableRef mapping) {
        return new com.legend.compiler.spec.typed.TypedFrom(query,
                java.util.Optional.of(mapping), java.util.Optional.empty(),
                query.info());
    }

    /** assertSameSQL's OUR-TEXT read (charter §8.3b): the
     * {@code sqlRemoveFormatting($result)} call over the assert's own
     * Result argument — the envelope splice folds it to the frame's
     * EXECUTED SQL text (ResultEnvelopeSplice.sqlProducerCall). Null
     * when the model does not know the Result overload (the arm then
     * leaves the shape on its current path). */
    public static @com.legend.Nullable TypedSpec sqlStripRead(
            TypedSpec resultArg,
            com.legend.compiler.element.ModelContext ctx) {
        for (var f : ctx.findFunction(
                ResultEnvelopeSplice.SQL_REMOVE_FORMATTING_FQN)) {
            if (f.parameters().size() == 1
                    && f.parameters().get(0).type()
                            != Type.Primitive.STRING) {
                return new com.legend.compiler.spec.typed.TypedUserCall(f,
                        List.of(resultArg),
                        new ExprType(Type.Primitive.STRING,
                                Multiplicity.Bounded.ONE));
            }
        }
        return null;
    }

    /** assertSameSQL's OUR-ROWS read (§8.3b): {@code $result.values} —
     * the envelope splice swaps it for the frame's typed chain
     * (ResultEnvelopeSplice.valuesRead), which carries the REAL
     * result type; the minted info is a pre-splice placeholder. */
    public static TypedSpec valuesRead(TypedSpec resultArg) {
        return new com.legend.compiler.spec.typed.TypedPropertyAccess(
                resultArg, "values", resultArg.info());
    }

    /** SQLTEXT charter §5 (the plan replayer, slice 4) — REFEREE
     * PARAMETER BINDINGS for a plan lambda: each scalar parameter
     * binds a fixed referee value as a minted {@code TypedLet} (the
     * verdict layer appends them to the let prefix — parameters
     * resolve exactly like test-body lets, no substitution walk).
     * {@code spellings} pairs each name with the SQL literal TEXT the
     * golden's <code>${'$'}{name}</code> hole fills with (the golden
     * supplies its own quoting). Null when any parameter is not a
     * bindable scalar (enum/class/collection — the arm declines
     * COUNTED; the charter's measure-first residue). */
    public record PlanBindings(List<TypedSpec> lets,
            java.util.Map<String, String> spellings) {
    }

    public static @com.legend.Nullable PlanBindings refereeBindings(
            com.legend.compiler.spec.typed.TypedLambda lam) {
        Type.FunctionType ft = com.legend.compiler.element.type
                .PlatformTypes.functionTypeOf(lam.info().type());
        if (ft == null || ft.params().size() != lam.parameters().size()) {
            return null;
        }
        List<TypedSpec> lets = new java.util.ArrayList<>();
        java.util.Map<String, String> spellings =
                new java.util.LinkedHashMap<>();
        for (int i = 0; i < lam.parameters().size(); i++) {
            String name = lam.parameters().get(i);
            Type pt = ft.params().get(i).type();
            TypedSpec value;
            String spelling;
            if (pt == Type.Primitive.STRING) {
                value = new com.legend.compiler.spec.typed.TypedCString(
                        "A", scalar(Type.Primitive.STRING));
                spelling = "A";
            } else if (pt == Type.Primitive.INTEGER) {
                value = new com.legend.compiler.spec.typed.TypedCInteger(
                        22L, scalar(Type.Primitive.INTEGER));
                spelling = "22";
            } else if (pt == Type.Primitive.FLOAT
                    || pt == Type.Primitive.NUMBER) {
                value = new com.legend.compiler.spec.typed.TypedCFloat(
                        1.0, new java.math.BigDecimal("1.0"),
                        scalar(Type.Primitive.FLOAT));
                spelling = "1.0";
            } else if (pt == Type.Primitive.BOOLEAN) {
                value = new com.legend.compiler.spec.typed.TypedCBoolean(
                        true, scalar(Type.Primitive.BOOLEAN));
                spelling = "true";
            } else if (pt == Type.Primitive.DATE
                    || pt == Type.Primitive.STRICT_DATE) {
                value = new com.legend.compiler.spec.typed.TypedCDate(
                        com.legend.values.PureDateLiteral.parse(
                                "2015-10-16"), scalar(pt));
                spelling = "2015-10-16";
            } else if (pt == Type.Primitive.DATE_TIME) {
                value = new com.legend.compiler.spec.typed.TypedCDate(
                        com.legend.values.PureDateLiteral.parse(
                                "2015-10-16T00:00:00"), scalar(pt));
                spelling = "2015-10-16 00:00:00";
            } else {
                return null;
            }
            lets.add(new com.legend.compiler.spec.typed.TypedLet(
                    name, value, value.info()));
            spellings.put(name, spelling);
        }
        return new PlanBindings(lets, spellings);
    }

    private static ExprType scalar(Type t) {
        return new ExprType(t, Multiplicity.Bounded.ONE);
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
