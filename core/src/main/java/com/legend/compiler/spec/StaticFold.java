// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend.compiler.spec;

import com.legend.compiler.element.type.Type;
import com.legend.model.spec.AppliedFunction;
import com.legend.model.spec.AppliedProperty;
import com.legend.model.spec.CBoolean;
import com.legend.model.spec.CInteger;
import com.legend.model.spec.CString;
import com.legend.model.spec.ColSpec;
import com.legend.model.spec.ColSpecArray;
import com.legend.model.spec.LambdaFunction;
import com.legend.model.spec.PackageableElementPtr;
import com.legend.model.spec.PureCollection;
import com.legend.model.spec.ValueSpecification;
import com.legend.model.spec.Variable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * COMPILE-TIME evaluation of SCHEMA computations inside a
 * {@code <<functionType.NormalizeRequiredFunction>>} body (engine doctrine:
 * these functions are normalized away before routing — their bodies COMPUTE
 * the plan, they are not part of it). After β-substitution the schema
 * positions are expressions over literals and column metadata
 * ({@code $cols->map(vc|pair($vc,$vc+'_1'))},
 * {@code $tds.columns->filter(...)->map(col|col({r|...}, $col.name+'_x'))});
 * this folder evaluates that vocabulary to LITERAL arguments so the
 * ordinary checkers (rename pairs, restrict names, extend colspecs) see
 * static facts.
 *
 * <p>Two mutually recursive halves:
 * <ul>
 *   <li>{@link #eval} — the static interpreter. Values: {@code String},
 *       {@code Long}, {@code Double}, {@code Boolean}, {@code List<Object>}
 *       (pure collections FLATTEN), {@link Col} (a TDSColumn metadata fact,
 *       from typing a relation receiver's {@code .columns}), {@link Pair}
 *       and {@link TypeToken}. {@code null} = not static.</li>
 *   <li>{@link #fold} — the AST rebuild. Static subtrees reify to literal
 *       nodes; a {@code map} over a static collection whose lambda body is
 *       NOT static (it reads the runtime row) UNROLLS — one folded body per
 *       element with the parameter bound in scope; {@code if} over a static
 *       condition folds to the taken branch. Everything else rebuilds
 *       children and stays for the ordinary Typer walk.</li>
 * </ul>
 *
 * <p>Unfoldable schema positions stay as-written and the downstream checker
 * walls stay LOUD — the folder never guesses.
 */
final class StaticFold {

    private final Typer typer;
    private final Env env;

    StaticFold(Typer typer, Env env) {
        this.typer = Objects.requireNonNull(typer, "typer");
        this.env = env;
    }

    /** TDS column metadata: name + SIMPLE pure type name (String, Integer…). */
    record Col(String name, String type) {
    }

    record Pair(Object first, Object second) {
    }

    /** A bare type reference in expression position ({@code $col.type == Integer}). */
    record TypeToken(String simpleName) {
    }

    // =====================================================================
    // fold — AST rebuild
    // =====================================================================

    ValueSpecification fold(ValueSpecification v) {
        return fold(v, Map.of());
    }

    /** The expression as a fully static LITERAL, or null — the Typer's
     * TDSColumn-metadata fold ({@code X.columns->map(c|$c.name...)} asserts)
     * only rewrites when the whole computation is schema facts. */
    ValueSpecification foldToLiteral(ValueSpecification v) {
        return reify(eval(v, Map.of()));
    }

    private ValueSpecification fold(ValueSpecification v, Map<String, Object> scope) {
        Object ev = eval(v, scope);
        ValueSpecification lit = reify(ev);
        if (lit != null) {
            return lit;
        }
        return switch (v) {
            case AppliedFunction af -> foldCall(af, scope);
            case AppliedProperty ap -> new AppliedProperty(
                    fold(ap.receiver(), scope), ap.property());
            case LambdaFunction lf -> {
                Map<String, Object> inner = shadow(scope, lf.parameters());
                yield new LambdaFunction(lf.parameters(),
                        lf.body().stream().map(b -> fold(b, inner)).toList());
            }
            case PureCollection pc -> new PureCollection(
                    pc.values().stream().map(x -> fold(x, scope)).toList());
            case ColSpec cs -> new ColSpec(cs.name(),
                    cs.function1() == null ? null
                            : (LambdaFunction) fold(cs.function1(), scope),
                    cs.function2() == null ? null
                            : (LambdaFunction) fold(cs.function2(), scope),
                    cs.alias(),
                    cs.args() == null ? null
                            : cs.args().stream().map(a -> fold(a, scope)).toList());
            case ColSpecArray ca -> new ColSpecArray(ca.colSpecs().stream()
                    .map(c -> (ColSpec) fold(c, scope)).toList());
            default -> v;
        };
    }

    private ValueSpecification foldCall(AppliedFunction af, Map<String, Object> scope) {
        List<ValueSpecification> ps = af.parameters();
        // map over a STATIC collection with a runtime lambda body: UNROLL —
        // one folded body per element, the parameter bound as a scope fact
        // (a Col element's .name/.type reads fold to literals inside).
        if (af.function().equals("map") && ps.size() == 2
                && ps.get(1) instanceof LambdaFunction lam
                && lam.parameters().size() == 1) {
            List<Object> coll = evalList(ps.get(0), scope);
            if (coll != null) {
                List<ValueSpecification> parts = new ArrayList<>(coll.size());
                for (Object e : coll) {
                    Map<String, Object> inner = new LinkedHashMap<>(scope);
                    inner.put(lam.parameters().get(0).name(), e);
                    parts.add(fold(single(lam), inner));
                }
                return parts.size() == 1 ? parts.get(0) : new PureCollection(parts);
            }
        }
        // if over a static condition: fold the taken branch's body
        if (af.function().equals("if") && ps.size() == 3
                && eval(ps.get(0), scope) instanceof Boolean cond
                && ps.get(1) instanceof LambdaFunction thenL
                && ps.get(2) instanceof LambdaFunction elseL) {
            return fold(single(cond ? thenL : elseL), scope);
        }
        return new AppliedFunction(af.function(),
                ps.stream().map(p -> fold(p, scope)).toList(),
                af.candidateFqns());
    }

    // =====================================================================
    // eval — the static interpreter (null = not static)
    // =====================================================================

    private Object eval(ValueSpecification v, Map<String, Object> scope) {
        return switch (v) {
            case CString s -> s.value();
            case CInteger i -> i.value().longValue();
            case com.legend.model.spec.CFloat f -> f.value();
            case CBoolean b -> b.value();
            case Variable var -> scope.get(var.name());
            case PureCollection pc -> {
                List<Object> out = new ArrayList<>(pc.values().size());
                for (ValueSpecification x : pc.values()) {
                    Object e = eval(x, scope);
                    if (e == null) {
                        yield null;
                    }
                    flattenInto(out, e);
                }
                yield out;
            }
            case PackageableElementPtr p -> new TypeToken(simple(p.fullPath()));
            case AppliedProperty ap -> evalProperty(ap, scope);
            case AppliedFunction af -> evalCall(af, scope);
            default -> null;
        };
    }

    private Object evalProperty(AppliedProperty ap, Map<String, Object> scope) {
        if (ap.property().equals("columns")) {
            Object recv = eval(ap.receiver(), scope);
            if (recv == null) {
                return relationColumns(ap.receiver());
            }
            return null;
        }
        Object recv = eval(ap.receiver(), scope);
        return switch (recv) {
            case Col c -> switch (ap.property()) {
                case "name" -> c.name();
                case "type" -> new TypeToken(c.type());
                default -> null;
            };
            case Pair p -> switch (ap.property()) {
                case "first" -> p.first();
                case "second" -> p.second();
                default -> null;
            };
            // collection property nav auto-maps ($diffCols.name)
            case List<?> l -> {
                List<Object> out = new ArrayList<>(l.size());
                for (Object e : l) {
                    Object r = switch (e) {
                        case Col c when ap.property().equals("name") -> c.name();
                        case Col c when ap.property().equals("type") -> new TypeToken(c.type());
                        default -> null;
                    };
                    if (r == null) {
                        yield null;
                    }
                    out.add(r);
                }
                yield out;
            }
            case null, default -> null;
        };
    }

    /** The TDSColumn facts of a relation-typed receiver — TYPED speculatively
     * (the receiver re-types when the folded body synths; the Typer is
     * effect-free on failure). Null when it does not type to a relation. */
    private List<Object> relationColumns(ValueSpecification receiver) {
        try {
            var typed = typer.synth(receiver, env);
            if (typed.info().type() instanceof Type.RelationType rt) {
                List<Object> cols = new ArrayList<>(rt.columns().size());
                for (Type.RelationType.Column c : rt.columns()) {
                    cols.add(new Col(c.name(), simple(c.type().typeName())));
                }
                return cols;
            }
        } catch (RuntimeException ignored) {
            // not typable here (free row vars, unresolved store) — not static
        }
        return null;
    }

    private Object evalCall(AppliedFunction af, Map<String, Object> scope) {
        List<ValueSpecification> ps = af.parameters();
        switch (af.function()) {
            case "plus" -> {
                List<Object> args = evalAll(ps, scope);
                if (args == null) {
                    return null;
                }
                if (args.stream().allMatch(a -> a instanceof String)) {
                    StringBuilder sb = new StringBuilder();
                    args.forEach(a -> sb.append((String) a));
                    return sb.toString();
                }
                if (args.stream().allMatch(a -> a instanceof Long)) {
                    return args.stream().mapToLong(a -> (Long) a).sum();
                }
                return null;
            }
            case "minus" -> {
                List<Object> args = evalAll(ps, scope);
                return args != null && args.size() == 1 && args.get(0) instanceof Long l
                        ? -l : null;
            }
            case "pair" -> {
                List<Object> args = evalAll(ps, scope);
                return args != null && args.size() == 2
                        ? new Pair(args.get(0), args.get(1)) : null;
            }
            case "equal" -> {
                List<Object> args = evalAll(ps, scope);
                return args != null && args.size() == 2
                        ? staticEquals(args.get(0), args.get(1)) : null;
            }
            case "not" -> {
                Object a = ps.size() == 1 ? eval(ps.get(0), scope) : null;
                return a instanceof Boolean b ? !b : null;
            }
            case "in" -> {
                if (ps.size() != 2) {
                    return null;
                }
                Object x = eval(ps.get(0), scope);
                List<Object> coll = evalList(ps.get(1), scope);
                return x == null || coll == null ? null : coll.contains(x);
            }
            case "concatenate" -> {
                List<Object> args = evalAll(ps, scope);
                return args;   // evalAll already flattens collections
            }
            case "removeDuplicates" -> {
                List<Object> coll = ps.size() == 1 ? evalList(ps.get(0), scope) : null;
                return coll == null ? null : new ArrayList<>(new LinkedHashSet<>(coll));
            }
            case "removeAll" -> {
                if (ps.size() != 2) {
                    return null;
                }
                List<Object> a = evalList(ps.get(0), scope);
                List<Object> b = evalList(ps.get(1), scope);
                if (a == null || b == null) {
                    return null;
                }
                List<Object> out = new ArrayList<>(a);
                out.removeAll(b);
                return out;
            }
            case "indexOf" -> {
                if (ps.size() != 2) {
                    return null;
                }
                List<Object> coll = evalList(ps.get(0), scope);
                Object x = eval(ps.get(1), scope);
                return coll == null || x == null ? null : (long) coll.indexOf(x);
            }
            case "map" -> {
                if (ps.size() != 2 || !(ps.get(1) instanceof LambdaFunction lam)
                        || lam.parameters().size() != 1) {
                    return null;
                }
                List<Object> coll = evalList(ps.get(0), scope);
                if (coll == null) {
                    return null;
                }
                List<Object> out = new ArrayList<>(coll.size());
                for (Object e : coll) {
                    Object r = evalWith(lam, e, scope);
                    if (r == null) {
                        return null;
                    }
                    flattenInto(out, r);
                }
                return out;
            }
            case "filter" -> {
                if (ps.size() != 2 || !(ps.get(1) instanceof LambdaFunction lam)
                        || lam.parameters().size() != 1) {
                    return null;
                }
                List<Object> coll = evalList(ps.get(0), scope);
                if (coll == null) {
                    return null;
                }
                List<Object> out = new ArrayList<>();
                for (Object e : coll) {
                    Object keep = evalWith(lam, e, scope);
                    if (!(keep instanceof Boolean b)) {
                        return null;
                    }
                    if (b) {
                        out.add(e);
                    }
                }
                return out;
            }
            case "sortBy" -> {
                if (ps.size() != 2 || !(ps.get(1) instanceof LambdaFunction lam)
                        || lam.parameters().size() != 1) {
                    return null;
                }
                List<Object> coll = evalList(ps.get(0), scope);
                if (coll == null) {
                    return null;
                }
                List<Map.Entry<Object, Object>> keyed = new ArrayList<>(coll.size());
                for (Object e : coll) {
                    Object k = evalWith(lam, e, scope);
                    if (!(k instanceof Comparable)) {
                        return null;
                    }
                    keyed.add(Map.entry(e, k));
                }
                @SuppressWarnings({"unchecked", "rawtypes"})
                Comparator<Map.Entry<Object, Object>> cmp =
                        Comparator.comparing(en -> (Comparable) en.getValue());
                keyed.sort(cmp);
                return keyed.stream().map(Map.Entry::getKey).toList();
            }
            case "if" -> {
                if (ps.size() == 3 && eval(ps.get(0), scope) instanceof Boolean c
                        && ps.get(1) instanceof LambdaFunction thenL
                        && ps.get(2) instanceof LambdaFunction elseL) {
                    LambdaFunction taken = c ? thenL : elseL;
                    return taken.body().size() == 1
                            ? eval(taken.body().get(0), scope) : null;
                }
                return null;
            }
            case "and", "or" -> {
                List<Object> args = evalAll(ps, scope);
                if (args == null || !args.stream().allMatch(a -> a instanceof Boolean)) {
                    return null;
                }
                boolean and = af.function().equals("and");
                return args.stream().map(a -> (Boolean) a)
                        .reduce(and, (x, y) -> and ? x && y : x || y);
            }
            case "makeString", "joinStrings" -> {
                if (ps.isEmpty() || ps.size() > 2) {
                    return null;
                }
                List<Object> coll = evalList(ps.get(0), scope);
                Object sep = ps.size() == 2 ? eval(ps.get(1), scope) : "";
                if (coll == null || !(sep instanceof String s)) {
                    return null;
                }
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < coll.size(); i++) {
                    String piece = stringify(coll.get(i));
                    if (piece == null) {
                        return null;
                    }
                    sb.append(i > 0 ? s : "").append(piece);
                }
                return sb.toString();
            }
            case "elementToPath" -> {
                Object a = ps.size() == 1 ? eval(ps.get(0), scope) : null;
                // primitives' path IS the simple name (Integer, String…)
                return a instanceof TypeToken t ? t.simpleName() : null;
            }
            case "toOne", "at" -> {
                if (af.function().equals("toOne") && ps.size() == 1) {
                    Object a = eval(ps.get(0), scope);
                    return a instanceof List<?> l && l.size() == 1 ? l.get(0) : a;
                }
                if (ps.size() == 2 && eval(ps.get(1), scope) instanceof Long ix) {
                    List<Object> coll = evalList(ps.get(0), scope);
                    return coll != null && ix >= 0 && ix < coll.size()
                            ? coll.get((int) (long) ix) : null;
                }
                return null;
            }
            default -> {
                return null;
            }
        }
    }

    /** A single-expression lambda's body; multi-statement bodies fold via
     * {@link SourceSubst#inlineLets} first, loud when that fails. */
    private static ValueSpecification single(LambdaFunction lam) {
        if (lam.body().size() == 1) {
            return lam.body().get(0);
        }
        LambdaFunction folded = SourceSubst.inlineLets(lam);
        if (folded == null) {
            throw new TypeInferenceException(
                    "static fold: multi-statement lambda with non-let statements");
        }
        return folded.body().get(0);
    }

    private Object evalWith(LambdaFunction lam, Object arg, Map<String, Object> scope) {
        if (lam.body().size() != 1) {
            return null;
        }
        Map<String, Object> inner = new LinkedHashMap<>(scope);
        inner.put(lam.parameters().get(0).name(), arg);
        return eval(lam.body().get(0), inner);
    }

    private List<Object> evalAll(List<ValueSpecification> ps, Map<String, Object> scope) {
        List<Object> out = new ArrayList<>(ps.size());
        for (ValueSpecification p : ps) {
            Object e = eval(p, scope);
            if (e == null) {
                return null;
            }
            flattenInto(out, e);
        }
        return out;
    }

    private List<Object> evalList(ValueSpecification v, Map<String, Object> scope) {
        Object e = eval(v, scope);
        return switch (e) {
            case List<?> l -> new ArrayList<>(l);
            case null -> null;
            default -> {
                List<Object> one = new ArrayList<>(1);
                one.add(e);
                yield one;
            }
        };
    }

    private static String stringify(Object v) {
        return switch (v) {
            case String s -> s;
            case Long l -> String.valueOf(l);
            case Double d -> String.valueOf(d);
            case Boolean b -> String.valueOf(b);
            case TypeToken t -> t.simpleName();
            case null, default -> null;
        };
    }

    private static Boolean staticEquals(Object a, Object b) {
        if (a instanceof TypeToken || b instanceof TypeToken) {
            String an = a instanceof TypeToken t ? t.simpleName() : String.valueOf(a);
            String bn = b instanceof TypeToken t ? t.simpleName() : String.valueOf(b);
            return an.equals(bn);
        }
        return a.equals(b);
    }

    private static void flattenInto(List<Object> out, Object e) {
        if (e instanceof List<?> l) {
            out.addAll(l);
        } else {
            out.add(e);
        }
    }

    // =====================================================================
    // reify — static value back to a literal AST (null = keep the AST)
    // =====================================================================

    private static ValueSpecification reify(Object v) {
        return switch (v) {
            case String s -> new CString(s);
            case Long l -> new CInteger(l);
            case Double d -> new com.legend.model.spec.CFloat(d);
            case Boolean b -> new CBoolean(b);
            case Pair p -> {
                ValueSpecification f = reify(p.first());
                ValueSpecification s = reify(p.second());
                yield f == null || s == null ? null
                        : new AppliedFunction("pair", List.of(f, s));
            }
            case List<?> l -> {
                List<ValueSpecification> vs = new ArrayList<>(l.size());
                for (Object e : l) {
                    ValueSpecification r = reify(e);
                    if (r == null) {
                        yield null;
                    }
                    vs.add(r);
                }
                yield new PureCollection(vs);
            }
            case null, default -> null;
        };
    }

    private static Map<String, Object> shadow(Map<String, Object> scope, List<Variable> params) {
        if (scope.isEmpty()) {
            return scope;
        }
        Map<String, Object> inner = new LinkedHashMap<>(scope);
        params.forEach(p -> inner.remove(p.name()));
        return inner;
    }

    private static String simple(String qn) {
        int cut = qn.lastIndexOf("::");
        return cut < 0 ? qn : qn.substring(cut + 2);
    }
}
