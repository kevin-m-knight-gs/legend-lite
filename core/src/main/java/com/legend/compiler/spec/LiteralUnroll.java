// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend.compiler.spec;

import com.legend.builtin.Pure;
import com.legend.compiler.element.ModelContext;
import com.legend.compiler.element.type.ExprType;
import com.legend.compiler.element.type.PlatformTypes;
import com.legend.compiler.element.type.Type;
import com.legend.compiler.spec.typed.TypedCBoolean;
import com.legend.compiler.spec.typed.TypedCFloat;
import com.legend.compiler.spec.typed.TypedCInteger;
import com.legend.compiler.spec.typed.TypedCString;
import com.legend.compiler.spec.typed.TypedCast;
import com.legend.compiler.spec.typed.TypedCollection;
import com.legend.compiler.spec.typed.TypedCopyInstance;
import com.legend.compiler.spec.typed.TypedEnumValue;
import com.legend.compiler.spec.typed.TypedMatchRuntime;
import com.legend.compiler.spec.typed.TypedNativeCall;
import com.legend.compiler.spec.typed.TypedNewInstance;
import com.legend.compiler.spec.typed.TypedPackageableRef;
import com.legend.compiler.spec.typed.TypedPropertyAccess;
import com.legend.compiler.spec.typed.TypedSlice;
import com.legend.compiler.spec.typed.TypedLimit;
import com.legend.compiler.spec.typed.TypedDrop;
import com.legend.compiler.spec.typed.TypedConcatenate;
import com.legend.compiler.spec.typed.TypedSpec;
import com.legend.compiler.spec.typed.TypedTypeRef;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * TIER 1 RECURSION (user green light 2026-09-03): a recursive Pure function
 * applied to a LITERAL instance tree unrolls at compile time — M2M
 * composition over a literal source. The inliner re-enters the recursive
 * call while its literal argument DESCENDS (strictly smaller each level,
 * so the unroll is well-founded with no depth constant), and these folds
 * decide, over literal structure only, what the body's operations mean: a
 * {@code match} picks its arm by the literal's class, a property read is
 * the literal's field, a cast over a literal of the target class is the
 * literal, and {@code map}/{@code filter}/{@code at}/{@code slice}/
 * {@code in}/… over a literal collection are their literal results. Every
 * fold is EXACT or ABSENT: {@link #fold} returns the node itself when the
 * unroll does not own it. Nothing here reads data — a literal is a value
 * the program spelled.
 *
 * <p>THE COMPILER COMPARES, THE DATABASE COMPUTES (docs/WORLD_MAP.md §4,
 * TENET_CHARTER Clause 6.2): every fold here decides something visible in
 * the program text — an arm by a literal's class, a spelled field, list
 * shape over a spelled list, identity of two spelled scalars of ONE kind,
 * a short-circuit on a spelled boolean. No fold produces a NEW value: a
 * {@code toLower}, a {@code +}, an arithmetic result is the database's and
 * stays in the tree as a residual the lowering spells as SQL. The fold set
 * is pinned by LiteralUnrollLedgerTest.
 */
final class LiteralUnroll {

    private LiteralUnroll() {
    }

    /** A value the compiler holds in full: an instance literal (its class)
     * or a scalar literal (its primitive/enum and value). */
    sealed interface Literal permits Instance, Scalar {
        String cls();
    }

    record Instance(String cls, TypedSpec node) implements Literal {
    }

    record Scalar(String cls, Object value) implements Literal {
    }

    static Optional<Literal> literal(TypedSpec s) {
        return switch (s) {
            case TypedNewInstance ni -> Optional.of(new Instance(ni.classFqn(), ni));
            case TypedCopyInstance cp -> literalStructure(cp.source())
                    ? Optional.of(new Instance(cp.classFqn(), cp)) : Optional.empty();
            case TypedCString c -> Optional.of(new Scalar(Type.Primitive.STRING.qualifiedName(), c.value()));
            case TypedCInteger i -> Optional.of(new Scalar(Type.Primitive.INTEGER.qualifiedName(),
                    i.value().longValue()));
            case TypedCBoolean b -> Optional.of(new Scalar(Type.Primitive.BOOLEAN.qualifiedName(), b.value()));
            case TypedCFloat f -> Optional.of(new Scalar(Type.Primitive.FLOAT.qualifiedName(), f.value()));
            case TypedEnumValue ev -> Optional.of(new Scalar(ev.enumFqn(), ev.enumFqn() + "." + ev.value()));
            default -> Optional.empty();
        };
    }

    /** A value whose STRUCTURE the compiler holds in full (a literal or a
     * collection of literal structures). */
    static boolean literalStructure(TypedSpec s) {
        return s instanceof TypedCollection tc
                ? tc.elements().stream().allMatch(LiteralUnroll::literalStructure)
                : literal(s).isPresent();
    }

    /** Node count of a literal structure — the unroll's descent measure. */
    static int size(TypedSpec s) {
        return switch (s) {
            case TypedNewInstance ni -> 1 + ni.properties().values().stream().mapToInt(LiteralUnroll::size).sum();
            case TypedCopyInstance cp -> 1 + size(cp.source())
                    + cp.overrides().values().stream().mapToInt(LiteralUnroll::size).sum();
            case TypedCollection tc -> tc.elements().stream().mapToInt(LiteralUnroll::size).sum();
            default -> literal(s).isPresent() ? 1 : 0;
        };
    }

    private static boolean accepts(ModelContext ctx, String cls, String armType) {
        if (armType.equals(PlatformTypes.ANY) || armType.equals(cls)) {
            return true;
        }
        String simpleArm = armType.substring(armType.lastIndexOf(':') + 1);
        String simpleCls = cls.substring(cls.lastIndexOf(':') + 1);
        if (!armType.contains("::") && simpleArm.equals(simpleCls)) {
            return true;
        }
        return ctx.isSubtype(cls, armType);
    }

    /** The literal field of an instance literal: the SPELLED value (or the
     * copy's override). A property the literal does not spell is not
     * folded — it may be derived, defaulted, or association-backed, and
     * the resolver owns those reads. */
    static Optional<TypedSpec> field(TypedSpec inst, String prop) {
        if (inst instanceof TypedNewInstance ni) {
            return Optional.ofNullable(ni.properties().get(prop));
        }
        if (inst instanceof TypedCopyInstance cp && literalStructure(cp.source())) {
            TypedSpec v = cp.overrides().get(prop);
            return v != null ? Optional.of(v) : field(cp.source(), prop);
        }
        return Optional.empty();
    }

    /** The FLAT element list of a literal structure — pure collections
     * are flat: a nested or empty literal collection compacts away
     * ({@code [[]->first(), 'a']} has one element). */
    static List<TypedSpec> elements(TypedSpec s) {
        if (!(s instanceof TypedCollection tc)) {
            return List.of(s);
        }
        List<TypedSpec> out = new ArrayList<>();
        for (TypedSpec e : tc.elements()) {
            out.addAll(elements(e));
        }
        return out;
    }

    private static Optional<Object> scalar(TypedSpec s) {
        return literal(s).filter(l -> l instanceof Scalar).map(l -> ((Scalar) l).value());
    }

    private static TypedCollection sub(TypedCollection coll, int lo, int hi, ExprType info) {
        List<TypedSpec> el = elements(coll);
        int from = Math.max(0, lo);
        int to = Math.min(el.size(), hi);
        return new TypedCollection(from < to ? el.subList(from, to) : List.of(), info);
    }

    private static TypedCBoolean bool(boolean v) {
        return new TypedCBoolean(v, ExprType.one(Type.Primitive.BOOLEAN));
    }

    /**
     * One fold step over an already-rewritten node: the literal result when
     * the node is a literal-structure operation the unroll owns, else the
     * node ITSELF. Constructs that bind a parameter (match arms, map/filter
     * lambdas, if branches) are unrolled by the inliner BEFORE their bodies
     * are rewritten — see UserCallInliner.rewriteSwitch.
     */
    static TypedSpec fold(TypedSpec n, ModelContext ctx) {
        return switch (n) {
            case TypedPropertyAccess pa -> field(pa.source(), pa.property()).orElse(n);
            case TypedCast tc -> literal(tc.source())
                    .filter(lit -> tc.target() instanceof Type.ClassType target
                            && accepts(ctx, lit.cls(), target.fqn()))
                    .<TypedSpec>map(lit -> tc.source()).orElse(n);
            case TypedNativeCall c -> nativeFold(c, ctx);
            // a copy of a literal IS a literal: the source's fields with the
            // overrides applied (one instance shape downstream)
            case TypedCopyInstance cp when cp.source() instanceof TypedNewInstance src -> {
                java.util.Map<String, TypedSpec> props = new java.util.LinkedHashMap<>(src.properties());
                props.putAll(cp.overrides());
                yield new TypedNewInstance(cp.classFqn(), props, cp.info());
            }
            // a WELL-FORMED spelled slice only: an inverted range is the
            // engine's error, raised by the lowering (PCT testSliceError)
            case TypedSlice sl when sl.source() instanceof TypedCollection coll && literalStructure(coll)
                    && sl.start() instanceof TypedCInteger lo && sl.stop() instanceof TypedCInteger hi
                    && lo.value().intValue() >= 0 && lo.value().intValue() <= hi.value().intValue() ->
                    sub(coll, lo.value().intValue(), hi.value().intValue(), sl.info());
            case TypedLimit lm when lm.source() instanceof TypedCollection coll && literalStructure(coll)
                    && lm.count() instanceof TypedCInteger k ->
                    sub(coll, 0, k.value().intValue(), lm.info());
            case TypedDrop dr when dr.source() instanceof TypedCollection coll && literalStructure(coll)
                    && dr.count() instanceof TypedCInteger k ->
                    sub(coll, k.value().intValue(), Integer.MAX_VALUE, dr.info());
            case TypedConcatenate cc when literalStructure(cc.left()) && literalStructure(cc.right()) -> {
                List<TypedSpec> out = new ArrayList<>(elements(cc.left()));
                out.addAll(elements(cc.right()));
                yield new TypedCollection(out, cc.info());
            }
            default -> n;
        };
    }

    /** The arm a literal input dispatches to (the inliner applies it
     * before the arms are rewritten); empty for a non-literal input. */
    static Optional<TypedMatchRuntime.Arm> arm(TypedMatchRuntime mr, TypedSpec input, ModelContext ctx) {
        return literal(input).flatMap(lit -> mr.arms().stream()
                .filter(a -> accepts(ctx, lit.cls(), a.typeFqn())).findFirst());
    }

    private static boolean is(TypedNativeCall c, String name) {
        return Pure.nativeNamed(name, c.callee().signatureKey());
    }

    private static TypedSpec nativeFold(TypedNativeCall c, ModelContext ctx) {
        List<TypedSpec> a = c.args();
        if (is(c, "instanceOf") && a.size() == 2) {
            return literal(a.get(0)).flatMap(lit -> typeTargetFqn(a.get(1))
                    .<TypedSpec>map(fqn -> bool(accepts(ctx, lit.cls(), fqn)))).orElse(c);
        }
        // scalar equality of the SAME kind only: a cross-kind compare
        // (1 == 1.0) is the database's verdict (SQL numeric coercion —
        // EqualityWorldsConformanceTest's declared divergence)
        if ((is(c, "equal") || is(c, "eq")) && a.size() == 2) {
            return literal(a.get(0)).flatMap(l -> literal(a.get(1))
                    .filter(r -> l instanceof Scalar && r instanceof Scalar && l.cls().equals(r.cls()))
                    .<TypedSpec>map(r -> bool(((Scalar) l).value().equals(((Scalar) r).value()))))
                    .orElse(c);
        }
        if (is(c, "not") && a.size() == 1 && a.get(0) instanceof TypedCBoolean b) {
            return bool(!b.value());
        }
        // and/or: both literal, or the short-circuit side literal (pure
        // semantics: the right operand is not evaluated)
        if ((is(c, "and") || is(c, "or")) && a.size() == 2) {
            boolean isAnd = is(c, "and");
            if (a.get(0) instanceof TypedCBoolean l) {
                if (l.value() != isAnd) {
                    return bool(l.value());
                }
                return a.get(1);
            }
            if (a.get(1) instanceof TypedCBoolean r && r.value() == isAnd) {
                return a.get(0);
            }
            return c;
        }
        if (is(c, "in") && a.size() == 2 && a.get(1) instanceof TypedCollection coll
                && coll.elements().stream().allMatch(e -> scalar(e).isPresent())) {
            return scalar(a.get(0)).<TypedSpec>map(needle -> bool(coll.elements().stream()
                    .anyMatch(e -> scalar(e).filter(needle::equals).isPresent()))).orElse(c);
        }
        if ((is(c, "isEmpty") || is(c, "isNotEmpty")) && a.size() == 1
                && literalStructure(a.get(0))) {
            boolean empty = elements(a.get(0)).isEmpty();
            return bool(is(c, "isEmpty") == empty);
        }
        if (is(c, "at") && a.size() == 2 && literalStructure(a.get(0))
                && a.get(1) instanceof TypedCInteger k
                && k.value().intValue() >= 0 && k.value().intValue() < elements(a.get(0)).size()) {
            return elements(a.get(0)).get(k.value().intValue());
        }
        if ((is(c, "toOne") || is(c, "toOneMany") || is(c, "first") || is(c, "last"))
                && a.size() >= 1 && literalStructure(a.get(0)) && !elements(a.get(0)).isEmpty()) {
            List<TypedSpec> el = elements(a.get(0));
            if (is(c, "toOneMany")) {
                return a.get(0);
            }
            if (el.size() == 1 || is(c, "first")) {
                return el.get(0);
            }
            if (is(c, "last")) {
                return el.get(el.size() - 1);
            }
            return c;
        }
        return c;
    }

    private static Optional<String> typeTargetFqn(TypedSpec typeArg) {
        return switch (typeArg) {
            case TypedTypeRef tr -> tr.target() instanceof Type.ClassType c
                    ? Optional.of(c.fqn()) : Optional.empty();
            case TypedPackageableRef pr -> Optional.of(pr.fullPath());
            default -> Optional.empty();
        };
    }

}
