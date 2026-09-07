// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend.compiler.spec;

import com.legend.compiler.element.type.ExprType;
import com.legend.compiler.element.type.Multiplicity;
import com.legend.compiler.element.type.Type;
import com.legend.compiler.spec.typed.TypedCollection;
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
    /** The FIRST-statement form of an indexed SQL read ({@code
     * sqlRemoveFormatting($res, n)} → {@code sqlRemoveFormatting($res)}):
     * the verdict arm reads our one statement where the engine's plan
     * names its n-th (batch 67). Compiler-layer minting (Invariant 7). */
    public static com.legend.compiler.spec.typed.TypedUserCall firstStatementRead(
            com.legend.compiler.spec.typed.TypedUserCall read) {
        return new com.legend.compiler.spec.typed.TypedUserCall(
                read.callee(), List.of(read.args().get(0)), read.info());
    }

    /** {@code assertEquals(expected, actual)} as a typed call — the meaning
     * of a dual-golden assert once its golden is chosen (the verdict arm
     * adjudicates it as the plain verdict). Null when the catalog has no
     * two-argument assertEquals (never, in a platform build). */
    public static @com.legend.Nullable TypedSpec assertEqualsOf(TypedSpec expected,
            TypedSpec actual, SpecCompiler specs) {
        return specs.ctx().findFunction(
                        com.legend.compiler.element.type.PlatformTypes.ASSERT_EQUALS)
                .stream().filter(f -> f.parameters().size() == 2).findFirst()
                .map(f -> (TypedSpec) new com.legend.compiler.spec.typed.TypedNativeCall(f,
                        java.util.List.of(expected, actual),
                        new com.legend.compiler.element.type.ExprType(
                                com.legend.compiler.element.type.Type.Primitive.BOOLEAN,
                                com.legend.compiler.element.type.Multiplicity.Bounded.ONE)))
                .orElse(null);
    }

    public static TypedSpec fromWrapped(TypedSpec query,
            com.legend.compiler.spec.typed.TypedPackageableRef mapping) {
        return fromWrapped(query, mapping,
                com.legend.compiler.spec.typed.ExecutionContext.NONE);
    }

    /** The verdict's read wrapped in the FRAME's own bound context (the
     * producer's post-processors, time zone, options) under the mapping. */
    public static TypedSpec fromWrapped(TypedSpec query,
            com.legend.compiler.spec.typed.TypedPackageableRef mapping,
            com.legend.compiler.spec.typed.ExecutionContext base) {
        return new com.legend.compiler.spec.typed.TypedFrom(query,
                base.withMapping(java.util.Optional.of(mapping)), query.info());
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

    /** {@code forAll(coll, x | <assert>)} — the engine's per-element
     * assert idiom (stringToFloat testProject: {@code [123.456, 100.001]
     * ->zip($tds.rows.values)->forAll(pair | assertEqWithinTolerance(
     * ...))}) IS the quantified assert: every element's assert holds (an
     * assert never yields false — it raises), so it unrolls exactly as
     * the map form does. Null = not that shape. */
    public static @com.legend.Nullable TypedMap forAllAsQuantified(TypedSpec bare) {
        if (bare instanceof TypedNativeCall fa
                && fa.callee().qualifiedName().equals(
                        "meta::pure::functions::collection::forAll")
                && fa.args().size() == 2
                && fa.args().get(1) instanceof TypedLambda flam
                && flam.parameters().size() == 1
                && !flam.body().isEmpty()) {
            return new TypedMap(fa.args().get(0), flam, fa.info());
        }
        return null;
    }

    /** The elements a quantified assert unrolls over: a LITERAL
     * collection's elements, or — {@code zip(A, B)} — pairs of the two
     * arms' elements, each arm a literal collection or a side the
     * database evaluates ({@code fetch}); its values become literal
     * specs (the unroll COMPARES, never computes — the pairing is
     * orchestration, every arithmetic stays in the assert's own side
     * evaluation). Null = not an unrollable shape (a runtime
     * collection, a value with no literal spelling). */
    public static @com.legend.Nullable List<TypedSpec> unrollElements(
            TypedSpec source, List<TypedSpec> letPrefix,
            com.legend.compiler.element.ModelContext ctx,
            java.util.function.Function<TypedSpec, List<Object>> fetch) {
        if (source instanceof TypedCollection coll) {
            // elements that are let-bound values ([$_s1_hoisted, $_s2_hoisted]
            // — hoisted constructor programs) read through the caller's lets
            List<TypedSpec> out = new java.util.ArrayList<>(coll.elements().size());
            for (TypedSpec e : coll.elements()) {
                out.add(ExecuteChainAssembly.letBound(e, letPrefix));
            }
            return out;
        }
        // a [1] instance literal (a let-bound constructor value) is the
        // one-element collection pure's [x] == x law makes it
        if (source instanceof com.legend.compiler.spec.typed.TypedNewInstance ni) {
            return List.of(ni);
        }
        if (source instanceof TypedNativeCall z
                && z.callee().qualifiedName().equals(
                        "meta::pure::functions::collection::zip")
                && z.args().size() == 2) {
            List<TypedSpec> left = armElements(z.args().get(0), letPrefix, fetch);
            List<TypedSpec> right = armElements(z.args().get(1), letPrefix, fetch);
            if (left == null || right == null) {
                return null;
            }
            var pairFns = ctx.findFunction("meta::pure::functions::collection::pair")
                    .stream().filter(f -> f.parameters().size() == 2).toList();
            if (pairFns.size() != 1) {
                throw new IllegalStateException(
                        "verdict synthesis bug: expected one 2-arg collection::pair");
            }
            // zip pairs by position and stops at the shorter arm (zip.pure)
            int n = Math.min(left.size(), right.size());
            List<TypedSpec> out = new java.util.ArrayList<>(n);
            for (int i = 0; i < n; i++) {
                TypedSpec l = left.get(i);
                TypedSpec r = right.get(i);
                out.add(new TypedNativeCall(pairFns.get(0), List.of(l, r),
                        new ExprType(new Type.GenericType(
                                "meta::pure::functions::collection::Pair",
                                List.of(l.info().type(), r.info().type()), List.of()),
                                Multiplicity.Bounded.ONE)));
            }
            return out;
        }
        return null;
    }

    private static @com.legend.Nullable List<TypedSpec> armElements(TypedSpec arm0,
            List<TypedSpec> letPrefix,
            java.util.function.Function<TypedSpec, List<Object>> fetch) {
        TypedSpec arm = ExecuteChainAssembly.letBound(arm0, letPrefix);
        if (arm instanceof TypedCollection c) {
            return c.elements();
        }
        List<TypedSpec> out = new java.util.ArrayList<>();
        for (Object v : fetch.apply(arm)) {
            TypedSpec lit = literalSpec(v);
            if (lit == null) {
                return null;
            }
            out.add(lit);
        }
        return out;
    }

    /** A database value as the literal spec that spells it; null when
     * the value has no literal spelling (dates, structures). */
    public static @com.legend.Nullable TypedSpec literalSpec(@com.legend.Nullable Object v) {
        return switch (v) {
            case Long l -> new com.legend.compiler.spec.typed.TypedCInteger(l,
                    ExprType.one(Type.Primitive.INTEGER));
            case Integer i -> new com.legend.compiler.spec.typed.TypedCInteger((long) i,
                    ExprType.one(Type.Primitive.INTEGER));
            case Double d -> new com.legend.compiler.spec.typed.TypedCFloat(d, null,
                    ExprType.one(Type.Primitive.FLOAT));
            case Float f -> new com.legend.compiler.spec.typed.TypedCFloat(f, null,
                    ExprType.one(Type.Primitive.FLOAT));
            case java.math.BigDecimal bd -> new com.legend.compiler.spec.typed.TypedCDecimal(bd,
                    ExprType.one(Type.Primitive.DECIMAL));
            case String str -> new com.legend.compiler.spec.typed.TypedCString(str,
                    ExprType.one(Type.Primitive.STRING));
            case Boolean b -> new com.legend.compiler.spec.typed.TypedCBoolean(b,
                    ExprType.one(Type.Primitive.BOOLEAN));
            case null, default -> null;
        };
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
        if (bare instanceof TypedNativeCall an
                && an.callee().qualifiedName().startsWith("meta::pure::functions::asserts::")) {
            // the MESSAGE arguments drop; the value arity is the assert's
            // own (assertEqWithinTolerance carries its delta as a third
            // VALUE — assertEqWithinTolerance.pure:22)
            int keep = "meta::pure::functions::asserts::assertEqWithinTolerance"
                    .equals(an.callee().qualifiedName()) ? 3 : 2;
            if (an.args().size() > keep) {
                bare = new TypedNativeCall(an.callee(), an.args().subList(0, keep), an.info(), an.pos());
            }
        }
        reduced.set(last, bare);
        return reduced;
    }
}
