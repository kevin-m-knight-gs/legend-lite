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
        if (fqn == null || !fqn.startsWith(PKG)) {
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
                            out.add(el);
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

    private static ExecutionResult ok() {
        return new ExecutionResult.Scalar(Boolean.TRUE,
                com.legend.compiler.element.type.Type.Primitive.BOOLEAN);
    }

    private static ExecutionResult fail(String message)
            throws java.sql.SQLException {
        throw new java.sql.SQLException(message);
    }
}
