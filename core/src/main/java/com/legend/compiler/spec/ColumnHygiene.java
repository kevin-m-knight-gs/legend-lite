// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0
package com.legend.compiler.spec;

import com.legend.compiler.element.type.Type;
import com.legend.platform.CoreFn;
import com.legend.protocol.spec.AppliedFunction;
import com.legend.protocol.spec.AppliedProperty;
import com.legend.protocol.spec.ColSpec;
import com.legend.protocol.spec.ColSpecArray;
import com.legend.protocol.spec.LambdaFunction;
import com.legend.protocol.spec.ValueSpecification;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The column half of an expanded body's hygiene (AlphaRename is the binder half): a function generic over relations
 * (docs/SEMANTICS_REGISTER.md S112) is pasted into each call, so the columns its body makes for its own use share the
 * call's namespace -- a caller's table with a column of such a name (a {@code ts_m}) was refused, naming a column the
 * caller never wrote (docs/PURE_LIBRARY_SPIKE_2026_10_27.md GAP 2; the user's choice, 2026-10-27: rename them).
 *
 * <p>Each expansion renames, before the call's arguments are substituted in, every column name the body MAKES -- a
 * column spec of {@code extend} after its source, {@code rename}'s new name, {@code groupBy}'s aggregates,
 * {@code aggregate}'s and {@code project}'s columns -- that its declared return does not name literally, at every
 * place the body writes it (its column specs and its row reads, {@code $r.name}), to a name of its own that no column
 * of the call's tables and no column the call names has. A name the body reads but never makes is the caller's, and
 * stays. Only a declared table return is hygienic: a function declared {@code Relation<Any>} says nothing of what
 * leaves its body, so nothing in it is renamed. One counter per typer, so expansions within expansions never meet.
 */
final class ColumnHygiene {

    private int fresh;

    /** An expansion's renamed body and the names it renamed (fresh to written), for its messages. */
    record Renamed(ValueSpecification body, Map<String, String> written) {

        /** {@code message} with each fresh name put back as the body wrote it. */
        String restore(String message) {
            String out = message;
            for (var e : written.entrySet()) {
                out = out.replaceAll("\\b" + Pattern.quote(e.getKey()) + "\\b", Matcher.quoteReplacement(e.getValue()));
            }
            return out;
        }
    }

    /**
     * The body with its working columns renamed, or as it is where the declaration names no table.
     *
     * @param declared the function's declared return, as written (its type variables open)
     * @param taken    every column name the call's tables have and its arguments name
     */
    Renamed apply(ValueSpecification body, Type declared, Set<String> taken) {
        Set<String> keep = declaredColumns(declared);
        if (keep == null) {
            return new Renamed(body, Map.of());
        }
        Set<String> made = new LinkedHashSet<>();
        collectMade(body, made);
        made.removeAll(keep);
        if (made.isEmpty()) {
            return new Renamed(body, Map.of());
        }
        Map<String, String> to = new LinkedHashMap<>();
        Map<String, String> written = new LinkedHashMap<>();
        for (String name : made) {
            String candidate;
            do {
                candidate = name + "_" + fresh++;
            } while (taken.contains(candidate) || made.contains(candidate));
            to.put(name, candidate);
            written.put(candidate, name);
        }
        return new Renamed(rename(body, to), written);
    }

    /** The column names a declared table return writes literally ({@code (total:Float)} in {@code Relation<T+(total:Float)>});
     *  empty for one of type variables alone; null where the declaration is no table at all. */
    private static @com.legend.base.Nullable Set<String> declaredColumns(Type declared) {
        if (!(declared instanceof Type.GenericType g)
                || !com.legend.compiler.element.type.PlatformTypes.RELATION_CARRIERS.contains(g.rawFqn())
                || g.arguments().size() != 1) {
            return null;
        }
        Set<String> out = new LinkedHashSet<>();
        return literalColumns(g.arguments().get(0), out) ? out : null;
    }

    /** Adds the literal columns of a schema; false where it is not a schema of type variables and literals. */
    private static boolean literalColumns(Type schema, Set<String> out) {
        return switch (schema) {
            case Type.TypeVar v -> true;
            case Type.RelationType r -> {
                r.columns().forEach(c -> out.add(c.name()));
                yield true;
            }
            case Type.SchemaAlgebra sa -> literalColumns(sa.left(), out) && literalColumns(sa.right(), out);
            default -> false;
        };
    }

    /** The names the body's making positions write. */
    private static void collectMade(ValueSpecification v, Set<String> made) {
        if (v instanceof AppliedFunction af) {
            var form = CoreFn.of(af.function());
            List<ValueSpecification> ps = af.parameters();
            if (form.isPresent()) {
                switch (form.get()) {
                    case EXTEND, AGGREGATE, PROJECT -> ps.stream().skip(1).forEach(p -> names(p, made));
                    case RENAME -> {
                        if (ps.size() == 3) {
                            names(ps.get(2), made);
                        }
                    }
                    case GROUP_BY -> {
                        if (ps.size() == 3) {
                            names(ps.get(2), made);
                        }
                    }
                    default -> {
                    }
                }
            }
        }
        for (ValueSpecification c : v.children()) {
            collectMade(c, made);
        }
    }

    /** The names a column spec or an array of them writes. */
    private static void names(ValueSpecification v, Set<String> into) {
        switch (v) {
            case ColSpec cs -> into.add(cs.name());
            case ColSpecArray ca -> ca.colSpecs().forEach(c -> into.add(c.name()));
            default -> {
            }
        }
    }

    /** Every column spec and row read of a renamed name, renamed. */
    private static ValueSpecification rename(ValueSpecification v, Map<String, String> to) {
        return switch (v) {
            case ColSpec cs -> new ColSpec(to.getOrDefault(cs.name(), cs.name()),
                    cs.function1() == null ? null : (LambdaFunction) rename(cs.function1(), to),
                    cs.function2() == null ? null : (LambdaFunction) rename(cs.function2(), to),
                    cs.alias(),
                    cs.args().stream().map(a -> rename(a, to)).toList());
            case ColSpecArray ca -> new ColSpecArray(ca.colSpecs().stream()
                    .map(c -> (ColSpec) rename(c, to)).toList());
            case AppliedProperty ap -> new AppliedProperty(rename(ap.receiver(), to),
                    to.getOrDefault(ap.property(), ap.property()));
            default -> v.mapChildren(c -> rename(c, to));
        };
    }
}
