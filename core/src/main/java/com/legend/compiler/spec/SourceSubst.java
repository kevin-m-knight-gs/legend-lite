// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend.compiler.spec;

import com.legend.model.spec.AppliedFunction;
import com.legend.model.spec.AppliedProperty;
import com.legend.model.spec.CString;
import com.legend.model.spec.ColSpec;
import com.legend.model.spec.ColSpecArray;
import com.legend.model.spec.KeyExpression;
import com.legend.model.spec.LambdaFunction;
import com.legend.model.spec.NewInstance;
import com.legend.model.spec.NewInstanceCast;
import com.legend.model.spec.PureCollection;
import com.legend.model.spec.ValueSpecification;
import com.legend.model.spec.Variable;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * SOURCE-level β-substitution over {@link ValueSpecification} trees — the
 * compiler-side sibling of the harness's inliner. Pure lets are
 * non-recursive value bindings, so substituting a let's value for its
 * variable preserves semantics exactly; shadowing lambda parameters stop
 * substitution.
 */
final class SourceSubst {

    private SourceSubst() {
    }

    /**
     * Fold a multi-statement lambda {@code [let*, final]} into a
     * single-expression lambda by inlining each let into everything after
     * it. Null when any non-terminal statement is not a let — the caller
     * keeps its loud wall (never a silently dropped statement).
     */
    static @com.legend.Nullable LambdaFunction inlineLets(LambdaFunction lam) {
        Map<String, ValueSpecification> env = new LinkedHashMap<>();
        for (int i = 0; i < lam.body().size() - 1; i++) {
            if (!(lam.body().get(i) instanceof AppliedFunction lf
                    && lf.function().equals("letFunction")
                    && lf.parameters().size() == 2
                    && lf.parameters().get(0) instanceof CString name)) {
                return null;
            }
            env.put(name.value(), substitute(lf.parameters().get(1), env));
        }
        return new LambdaFunction(lam.parameters(),
                List.of(substitute(lam.body().get(lam.body().size() - 1), env)));
    }

    static ValueSpecification substitute(ValueSpecification v,
            Map<String, ValueSpecification> env) {
        if (env.isEmpty()) {
            return v;
        }
        return switch (v) {
            case Variable var -> env.getOrDefault(var.name(), var);
            case AppliedFunction af -> new AppliedFunction(af.function(),
                    af.parameters().stream().map(p -> substitute(p, env))
                            .toList(),
                    af.candidateFqns());
            case AppliedProperty ap -> new AppliedProperty(
                    substitute(ap.receiver(), env), ap.property());
            case LambdaFunction lf -> {
                Map<String, ValueSpecification> inner = new LinkedHashMap<>(env);
                lf.parameters().forEach(p -> inner.remove(p.name()));
                yield inner.isEmpty() ? lf
                        : new LambdaFunction(lf.parameters(), lf.body().stream()
                                .map(b -> substitute(b, inner)).toList());
            }
            case PureCollection pc -> new PureCollection(pc.values().stream()
                    .map(x -> substitute(x, env)).toList());
            case ColSpec cs -> new ColSpec(cs.name(),
                    cs.function1() == null ? null
                            : (LambdaFunction) substitute(cs.function1(), env),
                    cs.function2() == null ? null
                            : (LambdaFunction) substitute(cs.function2(), env),
                    cs.alias(),
                    cs.args().stream().map(a -> substitute(a, env))
                            .toList());
            case ColSpecArray ca -> new ColSpecArray(ca.colSpecs().stream()
                    .map(c -> (ColSpec) substitute(c, env)).toList());
            case NewInstance ni -> {
                Map<String, KeyExpression> props = new LinkedHashMap<>();
                ni.properties().forEach((k, ke) -> props.put(k,
                        new KeyExpression(substitute(ke.value(), env),
                                ke.isAdd(), ke.isLocal())));
                yield new NewInstance(ni.className(), ni.typeArguments(), props);
            }
            case NewInstanceCast nc -> new NewInstanceCast(nc.className(),
                    nc.typeArguments(), substitute(nc.src(), env),
                    nc.targetSetId());
            // leaves pass; any composite not special-cased above recurses
            default -> v.mapChildren(x -> substitute(x, env));
        };
    }
}
