// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend;

import com.legend.compiler.spec.SpecCompiler;
import com.legend.compiler.spec.typed.TypedNativeCall;
import com.legend.compiler.spec.typed.TypedSpec;
import com.legend.compiler.spec.typed.TypedUserCall;
import com.legend.exec.ExecutionResult;
import com.legend.exec.PureAsserts;

import java.util.ArrayList;
import java.util.List;

/**
 * THE ASSERT-FAMILY VERDICT ARM (Charter Clause 2c, the Phase-4
 * redesign): a STATEMENT-ROOT call to the assert family is a VERDICT —
 * its result terminates in the runner, never in a data flow. Each
 * ARGUMENT executes through the full pipeline IN THE DATABASE (tenet #1
 * — the expressions are the data computation under test); the JUDGMENT
 * over the two produced sides is World 1's:
 * {@link PureAsserts} — the spec-exact Phase-2 adjudication layer. The
 * assert library's pure bodies are never β-inlined into SQL to produce
 * a verdict (the named Clause-2c violation; the Phase-4 seam arms were
 * its witnessed cost).
 *
 * <p>ASSERTS ARE VERDICTS, ALWAYS (homework 2026-08-19): legend-engine
 * has NO SQL translation for any assert — its relational adapters
 * execute only the inner expression in the store. The corpus's
 * map-wrapped asserts ({@code values->map(f|assert(...))}) are
 * QUANTIFIED verdicts over already-executed results — served by the
 * quantified arm here (predicates vectorize IN THE DATABASE, the
 * boolean vector is judged host-side, first failure raises with the
 * spec message) — the interpreter's per-element behavior, minus the
 * interpreter. Family members WITHOUT a verdict arm decline LOUDLY
 * with their shape — never a silent skip, never SQL-lowered verdicts.
 */
final class AssertVerdicts {

    private AssertVerdicts() {
    }

    private static final String PKG = "meta::pure::functions::asserts::";

    /** Null = not a statement-root assert this arm owns (generic path
     * continues); otherwise the verdict (TRUE, or the spec's failure
     * raised as the runner's failure). */
    static @com.legend.Nullable ExecutionResult tryAdjudicate(TypedSpec bare,
            List<TypedSpec> letPrefix, SpecCompiler specs,
            StatementExecutor.ExecEnv env) throws java.sql.SQLException {
        if (bare instanceof com.legend.compiler.spec.typed.TypedMap qm) {
            return quantified(qm, letPrefix, specs, env);
        }
        String fqn = calleeFqn(bare);
        if (fqn == null) {
            return null;
        }
        // THE GRID VERDICT (Clause 2c — GridCompare's chartered route;
        // witness: the relation suite's 79 assertTdsEquivalent rows):
        // both relations execute IN THE DATABASE, the cell-zip
        // adjudicates host-side (tdsEquivalent.pure's numeric delta +
        // temporal seconds policies, already the one owner).
        if (fqn.equals(
                "meta::pure::functions::relation::assertTdsEquivalent")) {
            List<TypedSpec> targs = ((bare instanceof TypedUserCall u2)
                    ? u2.args() : ((TypedNativeCall) bare).args());
            if (targs.size() < 3 || targs.size() > 4) {
                return null;
            }
            ExecutionResult.Tabular one =
                    tabular(targs.get(0), letPrefix, specs, env);
            ExecutionResult.Tabular two =
                    tabular(targs.get(1), letPrefix, specs, env);
            if (one == null || two == null) {
                return null;   // non-tabular shape — fall through, loud later
            }
            double delta = ((Number) one(side(targs.get(2), letPrefix,
                    specs, env), "assertTdsEquivalent delta")).doubleValue();
            double timeDelta = targs.size() == 4
                    ? ((Number) one(side(targs.get(3), letPrefix, specs,
                            env), "assertTdsEquivalent timeDelta"))
                            .doubleValue()
                    : 0.0;
            List<String> c1 = one.columns().stream()
                    .map(com.legend.exec.Column::name).toList();
            List<String> c2 = two.columns().stream()
                    .map(com.legend.exec.Column::name).toList();
            if (!c1.equals(c2) || one.rows().size() != two.rows().size()) {
                return fail("\n" + summarize(one) + "\n is not"
                        + " equivalent to:\n" + summarize(two));
            }
            String d = com.legend.exec.GridCompare.tdsEquivalent(
                    cells(one), cells(two), delta, timeDelta);
            return d == null ? ok() : fail(d);
        }
        if (!fqn.startsWith(PKG)) {
            return null;
        }
        String name = fqn.substring(PKG.length());
        List<TypedSpec> args = ((bare instanceof TypedUserCall u)
                ? u.args() : ((TypedNativeCall) bare).args());
        switch (name) {
            case "assertEquals", "assertNotEquals" -> {
                if (args.size() < 2) {
                    return null;
                }
                List<Object> e = side(args.get(0), letPrefix, specs, env);
                List<Object> a = side(args.get(1), letPrefix, specs, env);
                boolean equal = PureAsserts.equal(e, a);
                if (name.equals("assertNotEquals")) {
                    return equal
                            ? fail("assertNotEquals: both sides are equal")
                            : ok();
                }
                String d = PureAsserts.assertEquals(e, a);
                return d == null ? ok() : fail(d);
            }
            case "assertSameElements" -> {
                if (args.size() < 2) {
                    return null;
                }
                String d = PureAsserts.assertSameElements(
                        side(args.get(0), letPrefix, specs, env),
                        side(args.get(1), letPrefix, specs, env));
                return d == null ? ok() : fail(d);
            }
            case "assertSize" -> {
                if (args.size() < 2) {
                    return null;
                }
                List<Object> coll = side(args.get(0), letPrefix, specs, env);
                Object n = one(side(args.get(1), letPrefix, specs, env),
                        "assertSize size");
                String d = PureAsserts.assertSize(coll,
                        ((Number) n).longValue());
                return d == null ? ok() : fail(d);
            }
            case "assertEq" -> {
                if (args.size() < 2) {
                    return null;
                }
                String d = PureAsserts.assertEq(
                        one(side(args.get(0), letPrefix, specs, env),
                                "assertEq expected"),
                        one(side(args.get(1), letPrefix, specs, env),
                                "assertEq actual"));
                return d == null ? ok() : fail(d);
            }
            case "assertEqWithinTolerance" -> {
                if (args.size() < 3) {
                    return null;
                }
                String d = PureAsserts.assertEqWithinTolerance(
                        (Number) one(side(args.get(0), letPrefix, specs, env),
                                "tolerance expected"),
                        (Number) one(side(args.get(1), letPrefix, specs, env),
                                "tolerance actual"),
                        (Number) one(side(args.get(2), letPrefix, specs, env),
                                "tolerance delta"));
                return d == null ? ok() : fail(d);
            }
            case "assert", "assertFalse" -> {
                if (args.isEmpty()) {
                    return null;
                }
                Object c = one(side(args.get(0), letPrefix, specs, env),
                        name + " condition");
                boolean held = Boolean.TRUE.equals(c) == name.equals("assert");
                return held ? ok() : fail("Assert failed");
            }
            case "assertInstanceOf" -> {
                if (args.size() < 2) {
                    return null;
                }
                // the /3 message overload has no witness — fall through
                if (args.size() != 2) {
                    return null;
                }
                Object v = one(side(args.get(0), letPrefix, specs, env),
                        "assertInstanceOf instance");
                String type = typeRefName(args.get(1));
                if (type == null) {
                    return null;   // non-literal type arg — fall through
                }
                String d = PureAsserts.assertInstanceOf(v, type);
                return d == null ? ok() : fail(d);
            }
            case "assertIs" -> {
                // is() = IDENTITY (real pure is.pure:23, PCT.platformOnly).
                // World-1 adjudication for statically-identified operands
                // only; message overloads have no witness — fall through.
                if (args.size() != 2) {
                    return null;
                }
                return isVerdict(args.get(0), args.get(1));
            }
            case "assertEmpty", "assertNotEmpty" -> {
                if (args.isEmpty()) {
                    return null;
                }
                boolean empty = side(args.get(0), letPrefix, specs, env)
                        .isEmpty();
                boolean held = empty == name.equals("assertEmpty");
                return held ? ok()
                        : fail(name.equals("assertEmpty")
                                ? "collection is not empty"
                                : "collection is empty");
            }
            // assertError has its OWN K-arm (AssertErrorNative); every
            // other member rides the legacy inline path — the recorded
            // residual, never intercepted-and-broken
            default -> {
                return null;
            }
        }
    }

    /** The IDENTITY verdict ({@code assertIs} → {@code is()}, real pure
     * is.pure:23 "pointer equality"): adjudicable in World 1 ONLY when
     * both operands are STATICALLY identified — a type reference (bare
     * element, {@code type(x)->toOne()}, {@code genericType(x).rawType})
     * or the same let-bound instance by construction provenance. Any
     * other shape returns null: the legacy path then walls loudly on
     * {@code is}'s missing SQL rule — a wire carries values, never
     * reference identity (the eq/equalNonPrimitive irreducible ruling). */
    private static @com.legend.Nullable ExecutionResult isVerdict(
            TypedSpec left, TypedSpec right) throws java.sql.SQLException {
        String lt = typeIdentityOf(left);
        String rt = typeIdentityOf(right);
        if (lt != null && rt != null) {
            return lt.equals(rt) ? ok()
                    : fail("\nexpected: " + lt + "\nactual:   " + rt);
        }
        TypedSpec l = instanceOrigin(left);
        TypedSpec r = instanceOrigin(right);
        if (l instanceof com.legend.compiler.spec.typed.TypedVariable lv
                && r instanceof com.legend.compiler.spec.typed.TypedVariable rv
                && lv.name().equals(rv.name())) {
            // the same let-bound variable in one frame IS the same object
            return ok();
        }
        return null;
    }

    /** The statically-known TYPE a value expression identifies, or null.
     * {@code type()}/{@code genericType().rawType} resolve to the STATIC
     * type of their argument — sound exactly when that type is concrete
     * (a literal or constructed instance), which is what the witnesses
     * pass ({@code type(+1)}, {@code genericType(^LA_Person(...))}). */
    private static @com.legend.Nullable String typeIdentityOf(TypedSpec t) {
        TypedSpec s = peel(t);
        if (s instanceof com.legend.compiler.spec.typed.TypedPackageableRef pr) {
            return canonicalTypeFqn(pr.fullPath());
        }
        if (s instanceof com.legend.compiler.spec.typed.TypedTypeRef tr) {
            return canonicalTypeFqn(tr.target().typeName());
        }
        if (s instanceof com.legend.compiler.spec.typed.TypedNativeCall c
                && c.callee().qualifiedName().equals(
                        "meta::pure::functions::meta::type")
                && !c.args().isEmpty()) {
            return staticTypeName(c.args().get(0));
        }
        if (s instanceof com.legend.compiler.spec.typed.TypedPropertyAccess pa
                && pa.property().equals("rawType")
                && peel(pa.source())
                        instanceof com.legend.compiler.spec.typed
                                .TypedNativeCall gt
                && gt.callee().qualifiedName().equals(
                        "meta::pure::functions::meta::genericType")
                && !gt.args().isEmpty()) {
            return staticTypeName(gt.args().get(0));
        }
        return null;
    }

    private static @com.legend.Nullable String staticTypeName(TypedSpec arg) {
        // concrete static identification only: a literal's primitive or a
        // constructed/class-typed value — never an Any/generic stamp
        var ty = peel(arg).info().type();
        if (ty instanceof com.legend.compiler.element.type.Type.ClassType ct) {
            return ct.fqn();
        }
        String n = ty.typeName();
        return switch (n) {
            case "Integer", "Float", "Decimal", "String", "Boolean", "Date",
                    "StrictDate", "DateTime", "StrictTime" ->
                    canonicalTypeFqn(n);
            default -> null;
        };
    }

    /** ONE spelling for a type identity: PRIMITIVES canonicalize to their
     * M3 FQN so all three resolution arms agree (bare {@code Integer},
     * {@code @Integer}, and {@code type(1)} name the same element).
     * Anything else — including packageless user test classes — keeps
     * its resolved spelling untouched. */
    private static String canonicalTypeFqn(String name) {
        return switch (name) {
            case "Integer", "Float", "Decimal", "String", "Boolean", "Date",
                    "StrictDate", "DateTime", "StrictTime", "Number" ->
                    "meta::pure::metamodel::type::" + name;
            default -> name;
        };
    }

    /** Peel value-preserving wrappers ({@code toOne}) and fold a property
     * read over a constructed instance to its constructor argument — the
     * provenance chain the OneToOne witness rides. */
    private static TypedSpec peel(TypedSpec t) {
        TypedSpec s = t;
        while (true) {
            if (s instanceof com.legend.compiler.spec.typed.TypedNativeCall c
                    && com.legend.builtin.Pure.isToOneCall(c.callee().qualifiedName())
                    && !c.args().isEmpty()) {
                s = c.args().get(0);
                continue;
            }
            return s;
        }
    }

    private static TypedSpec instanceOrigin(TypedSpec t) {
        TypedSpec s = peel(t);
        if (s instanceof com.legend.compiler.spec.typed.TypedPropertyAccess pa
                && peel(pa.source())
                        instanceof com.legend.compiler.spec.typed
                                .TypedNewInstance ni
                && ni.properties().get(pa.property()) != null) {
            return instanceOrigin(ni.properties().get(pa.property()));
        }
        return s;
    }

    /** The QUANTIFIED verdict: {@code xs->map(f|assert(pred[, 'msg']))}
     * at a statement root. The predicate VECTORIZES in the database
     * ({@code xs->map(f|pred)} — pure data computation); the boolean
     * vector is judged here, first failure raising the assert's message
     * — the interpreter's per-element semantics without an interpreter.
     * Null = not a quantified assert (generic path continues); shapes
     * beyond assert/assertFalse with a literal-or-absent message decline
     * LOUDLY. */
    private static @com.legend.Nullable ExecutionResult quantified(
            com.legend.compiler.spec.typed.TypedMap qm,
            List<TypedSpec> letPrefix, SpecCompiler specs,
            StatementExecutor.ExecEnv env) throws java.sql.SQLException {
        var lam = qm.mapper();
        if (lam.body().size() != 1) {
            return null;
        }
        TypedSpec root = lam.body().get(0);
        String fqn = calleeFqn(root);
        if (fqn == null || !fqn.startsWith(PKG)) {
            return null;
        }
        String name = fqn.substring(PKG.length());
        List<TypedSpec> aargs = root instanceof TypedUserCall u ? u.args()
                : ((TypedNativeCall) root).args();
        if (!(name.equals("assert") || name.equals("assertFalse"))
                || aargs.isEmpty()) {
            throw new com.legend.error.NotImplementedException(
                    "quantified assert verdict: only map(f|assert/"
                    + "assertFalse(pred[, message])) is modeled — got '"
                    + name + "'/" + aargs.size());
        }
        String msg = aargs.size() >= 2
                && aargs.get(1) instanceof
                        com.legend.compiler.spec.typed.TypedCString cs
                ? cs.value() : "Assert failed";
        if (aargs.size() >= 2 && !(aargs.get(1) instanceof
                com.legend.compiler.spec.typed.TypedCString)) {
            throw new com.legend.error.NotImplementedException(
                    "quantified assert verdict: non-literal message"
                    + " expressions are not modeled");
        }
        // the predicate vector, computed in the database: same source,
        // same binder, the assert's CONDITION as the mapper body
        var boolOne = com.legend.compiler.element.type.ExprType.one(
                com.legend.compiler.element.type.Type.Primitive.BOOLEAN);
        var predLam = new com.legend.compiler.spec.typed.TypedLambda(
                lam.parameters(), List.of(aargs.get(0)), lam.info());
        TypedSpec predMap = new com.legend.compiler.spec.typed.TypedMap(
                qm.source(), predLam,
                new com.legend.compiler.element.type.ExprType(
                        boolOne.type(),
                        com.legend.compiler.element.type
                                .Multiplicity.Bounded.ZERO_MANY));
        List<Object> verdicts = side(predMap, letPrefix, specs, env);
        boolean wantTrue = name.equals("assert");
        for (Object v : verdicts) {
            if (Boolean.TRUE.equals(v) != wantTrue) {
                return fail(msg);
            }
        }
        return ok();
    }

    private static @com.legend.Nullable String calleeFqn(TypedSpec bare) {
        if (bare instanceof TypedUserCall u) {
            return u.callee().qualifiedName();
        }
        if (bare instanceof TypedNativeCall n
                && !com.legend.compiler.element.type.PlatformTypes.ASSERT_ERROR
                        .equals(n.callee().qualifiedName())) {
            return n.callee().qualifiedName();
        }
        return null;
    }

    /** One assert SIDE: the argument expression executed in the
     * database through the ordinary pipeline, flattened to wire values
     * (a null scalar is the EMPTY collection — pure [0..1] emptiness). */
    private static List<Object> side(TypedSpec arg, List<TypedSpec> letPrefix,
            SpecCompiler specs, StatementExecutor.ExecEnv env)
            throws java.sql.SQLException {
        ExecutionResult r = StatementExecutor.evalValue(arg, letPrefix,
                specs, env);
        return switch (r) {
            case null -> new ArrayList<>();
            case ExecutionResult.Scalar s -> {
                List<Object> out = new ArrayList<>(1);
                if (s.value() instanceof java.sql.Array arr) {
                    // the LIST WIRE arriving as one JDBC array cell —
                    // the collection IS the side, flattened
                    try {
                        for (Object el : (Object[]) arr.getArray()) {
                            // ONE-CARRIER normalization: raw JDBC array
                            // elements arrive as driver temporals — the
                            // java.time carrier is the platform's one
                            // convention (the invisible-diff bug: a
                            // Timestamp reprs identically to the
                            // LocalDateTime it never equals)
                            out.add(el instanceof java.sql.Timestamp ts
                                    ? ts.toLocalDateTime()
                                    : el instanceof java.sql.Date sd
                                            ? sd.toLocalDate()
                                            : el);
                        }
                    } catch (java.sql.SQLException ex) {
                        throw new IllegalStateException(
                                "array side unwrap failed", ex);
                    }
                } else if (s.value() != null) {
                    out.add(s.value());
                }
                yield out;
            }
            case ExecutionResult.Collection c -> c.values();
            default -> throw new com.legend.error.NotImplementedException(
                    "assert verdict over a " + r.getClass().getSimpleName()
                    + " side — grid/graph asserts stay with their own"
                    + " compare owners");
        };
    }

    private static Object one(List<Object> side, String what) {
        if (side.size() != 1) {
            throw new IllegalStateException(what + " must be one value,"
                    + " got " + side.size());
        }
        return side.get(0);
    }

    /** The literal type argument's name: @Type annotation
     * ({@code TypedTypeRef}) or a bare reference in value position
     * ({@code TypedPackageableRef}); null = not literal (fall through,
     * the body inlines and walls on its own terms). */
    private static @com.legend.Nullable String typeRefName(TypedSpec t) {
        return switch (t) {
            case com.legend.compiler.spec.typed.TypedTypeRef tr ->
                    tr.target().typeName();
            case com.legend.compiler.spec.typed.TypedPackageableRef pr ->
                    pr.fullPath();
            default -> null;
        };
    }

    /** The relation arg executed in the database, as its TABULAR frame;
     * null = the value did not execute to a relation (fall through). */
    private static ExecutionResult.@com.legend.Nullable Tabular tabular(
            TypedSpec arg, List<TypedSpec> letPrefix, SpecCompiler specs,
            StatementExecutor.ExecEnv env) throws java.sql.SQLException {
        ExecutionResult r = StatementExecutor.evalValue(arg,
                letPrefix, specs, env);
        return r instanceof ExecutionResult.Tabular t ? t : null;
    }

    /** Row-major cell stream of a tabular frame (the cell-zip input). */
    private static List<Object> cells(ExecutionResult.Tabular t) {
        List<Object> out = new java.util.ArrayList<>();
        for (com.legend.exec.Row r : t.rows()) {
            out.addAll(r.values());
        }
        return out;
    }

    /** Failure-message sketch of a frame (columns + row count — the
     * spec's toString(true) grid rendering is message-position only;
     * no witness pins its spelling). */
    private static String summarize(ExecutionResult.Tabular t) {
        return t.columns().stream().map(com.legend.exec.Column::name)
                .toList() + " (" + t.rows().size() + " rows)";
    }

    private static ExecutionResult ok() {
        return new ExecutionResult.Scalar(Boolean.TRUE,
                com.legend.compiler.element.type.Type.Primitive.BOOLEAN);
    }

    private static ExecutionResult fail(String message)
            throws java.sql.SQLException {
        throw new java.sql.SQLException(message);
    }
}
