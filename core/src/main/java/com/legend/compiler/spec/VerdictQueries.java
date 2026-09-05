// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend.compiler.spec;

import com.legend.compiler.element.type.ExprType;
import com.legend.compiler.element.type.Multiplicity;
import com.legend.compiler.element.type.Type;
import com.legend.compiler.spec.typed.TypedLambda;
import com.legend.compiler.spec.typed.TypedMap;
import com.legend.compiler.spec.typed.TypedNativeCall;
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
            java.util.Map<String, String> spellings,
            java.util.Map<String, List<String>> lists) {
        public PlanBindings(List<TypedSpec> lets,
                java.util.Map<String, String> spellings) {
            this(lets, spellings, singletons(spellings));
        }

        private static java.util.Map<String, List<String>> singletons(
                java.util.Map<String, String> spellings) {
            java.util.Map<String, List<String>> out = new java.util.LinkedHashMap<>();
            spellings.forEach((k, v) -> out.put(k, List.of(v)));
            return out;
        }
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
        java.util.Map<String, List<String>> lists =
                new java.util.LinkedHashMap<>();
        for (int i = 0; i < lam.parameters().size(); i++) {
            String name = lam.parameters().get(i);
            Type pt = ft.params().get(i).type();
            TypedSpec value;
            String spelling;
            if (ft.params().get(i).multiplicity().isMany()
                    && (pt == Type.Primitive.STRING
                            || pt == Type.Primitive.INTEGER)) {
                // a COLLECTION parameter (batch 66): two fixed referee
                // elements — the plan's template operations
                // (collectionSize, renderCollection) evaluate over them
                // at the oracle; our side runs the same two
                boolean str = pt == Type.Primitive.STRING;
                List<TypedSpec> elems = str
                        ? List.of(new com.legend.compiler.spec.typed.TypedCString(
                                        "A", scalar(pt)),
                                new com.legend.compiler.spec.typed.TypedCString(
                                        "B", scalar(pt)))
                        : List.of(new com.legend.compiler.spec.typed.TypedCInteger(
                                        22L, scalar(pt)),
                                new com.legend.compiler.spec.typed.TypedCInteger(
                                        23L, scalar(pt)));
                ExprType many = new ExprType(pt, Multiplicity.Bounded.ZERO_MANY);
                lets.add(new com.legend.compiler.spec.typed.TypedLet(name,
                        new com.legend.compiler.spec.typed.TypedCollection(
                                elems, many), many));
                lists.put(name, str ? List.of("A", "B") : List.of("22", "23"));
                continue;
            }
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
            lists.put(name, List.of(spelling));
        }
        return new PlanBindings(lets, spellings, lists);
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

    /** {@code equal(distinct(<map>), [true])} → the map; else the node
     * (the toSQLString dialect-table idiom's outer wrapper). */
    public static TypedSpec distinctTrueWrapper(TypedSpec bare) {
        if (bare instanceof TypedNativeCall eq
                && (eq.callee().qualifiedName().equals("meta::pure::functions::boolean::equal")
                        || eq.callee().qualifiedName().equals("meta::pure::functions::boolean::eq"))
                && eq.args().size() == 2
                && eq.args().get(1) instanceof com.legend.compiler.spec.typed.TypedCollection tc
                && tc.elements().size() == 1
                && tc.elements().get(0) instanceof com.legend.compiler.spec.typed.TypedCBoolean b
                && b.value()
                && eq.args().get(0) instanceof TypedNativeCall d
                && d.callee().qualifiedName().equals("meta::pure::functions::collection::distinct")
                && d.args().size() == 1) {
            return d.args().get(0);
        }
        return bare;
    }

    /** One element of an UNROLLED quantified assert: the caller's lets,
     * the lambda's parameter bound to {@code element} as a let, then the
     * lambda's own statements — reduced by the inliner (the one
     * substitution engine). The last statement is the element's assert;
     * a message-carrying assert ({@code assertEquals(e, a, fmt, args)})
     * normalizes to its two-argument form (the message is failure text,
     * never part of the verdict). */
    /** The frame variable's {@code .activities} read — the node the
     * splice hook resolves to the frame's own execute() call (the
     * verdict arms recover a frame's mapping through it). */
    /** Whether a typed chain is a SUB-COLLECTION of a class extent:
     * getAll through filter/sort/limit/slice/drop/from/first/last/toOne
     * (the walk lane's extentSubset, on the typed tree). */
    public static boolean extentSubset(TypedSpec n) {
        return switch (n) {
            case com.legend.compiler.spec.typed.TypedGetAll g -> true;
            case com.legend.compiler.spec.typed.TypedFilter f ->
                    extentSubset(f.source());
            case com.legend.compiler.spec.typed.TypedSort s ->
                    extentSubset(s.source());
            case com.legend.compiler.spec.typed.TypedSortBy s ->
                    extentSubset(s.source());
            case com.legend.compiler.spec.typed.TypedLimit l ->
                    extentSubset(l.source());
            case com.legend.compiler.spec.typed.TypedSlice l ->
                    extentSubset(l.source());
            case com.legend.compiler.spec.typed.TypedDrop d ->
                    extentSubset(d.source());
            case com.legend.compiler.spec.typed.TypedFrom f ->
                    extentSubset(f.source());
            case com.legend.compiler.spec.typed.TypedNativeCall c when !c.args().isEmpty() -> {
                String q = c.callee().qualifiedName();
                String simple = q.substring(q.lastIndexOf(':') + 1);
                yield switch (simple) {
                    case "first", "last", "toOne", "take", "limit", "drop",
                            "slice" -> extentSubset(c.args().get(0));
                    default -> false;
                };
            }
            default -> false;
        };
    }

    public static TypedSpec activitiesRead(TypedSpec frameVar) {
        return new com.legend.compiler.spec.typed.TypedPropertyAccess(
                frameVar, "activities", frameVar.info());
    }

    public static List<TypedSpec> unrolledElement(SpecCompiler specs,
            List<TypedSpec> letPrefix, TypedLambda lam, TypedSpec element,
            java.util.function.@com.legend.Nullable BiFunction<TypedSpec,
                    java.util.Set<String>, TypedSpec> hook) {
        List<TypedSpec> seq = new java.util.ArrayList<>(letPrefix);
        seq.add(new com.legend.compiler.spec.typed.TypedLet(lam.parameters().get(0),
                element, element.info()));
        seq.addAll(lam.body());
        var inliner = hook == null ? new UserCallInliner(specs)
                : new UserCallInliner(specs, hook);
        List<TypedSpec> reduced = new java.util.ArrayList<>(inliner.inlineBody(seq));
        int last = reduced.size() - 1;
        TypedSpec stmt = reduced.get(last);
        TypedSpec bare = stmt instanceof com.legend.compiler.spec.typed.TypedLet tl
                ? tl.value() : stmt;
        if (bare instanceof TypedNativeCall an && an.args().size() > 2
                && an.callee().qualifiedName().startsWith("meta::pure::functions::asserts::")) {
            bare = new TypedNativeCall(an.callee(), an.args().subList(0, 2), an.info(), an.pos());
        }
        reduced.set(last, bare);
        return reduced;
    }
}
