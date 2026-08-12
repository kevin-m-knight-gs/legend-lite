// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend.harness;

import com.legend.protocol.spec.AppliedFunction;
import com.legend.protocol.spec.AppliedProperty;
import com.legend.protocol.spec.CString;
import com.legend.protocol.spec.KeyExpression;
import com.legend.protocol.spec.LambdaFunction;
import com.legend.protocol.spec.NewInstance;
import com.legend.protocol.spec.PureCollection;
import com.legend.protocol.spec.ValueSpecification;
import com.legend.protocol.spec.Variable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;

import static java.util.Objects.requireNonNull;

import java.util.List;
import java.util.Map;

/** Let-binding substitution over harness value-spec trees — split from
 *  {@link EngineTestExecutor} (file-size guardrail), same package seam. */
final class HarnessSubstitution {

    private HarnessSubstitution() {
    }

    /** {@link #substitute} with the non-null passthrough asserted. */
    static ValueSpecification subst(ValueSpecification v,
            Map<String, ValueSpecification> lets) {
        return requireNonNull(substitute(v, lets), "substitute(v, lets)");
    }

    static @com.legend.Nullable ValueSpecification substitute(
            @com.legend.Nullable ValueSpecification v,
            Map<String, ValueSpecification> lets) {
        if (v == null) {
            return null;
        }
        // ^TDSNull() in a TEST literal is the engine's null-cell INSTANCE
        // (a real value, not a pure empty — an empty would VANISH from
        // [^TDSNull(), 5.0] and break the comparison): it travels as the
        // wire sentinel, which wireEquals equates with an actual null cell
        if (v instanceof NewInstance tn
                && (tn.className().equals("TDSNull")
                        || tn.className().equals("meta::pure::tds::TDSNull"))) {
            return new CString("TDSNull");
        }
        if (v instanceof AppliedFunction nf && nf.function().equals("new")
                && nf.parameters().size() == 2
                && nf.parameters().get(1) instanceof NewInstance tn2
                && (tn2.className().equals("TDSNull")
                        || tn2.className().equals("meta::pure::tds::TDSNull"))) {
            return new CString("TDSNull");
        }
        return switch (v) {
            // a folded quote/eval carrier is a CLOSED term (built from
            // literals): nothing to substitute, and rebuilding would
            // re-fold its own original
            case com.legend.protocol.spec.QuotedTreeCall q -> q;
            // RECURSIVE: the pulled RHS may itself read lets bound earlier
            // (the per-driver loop's toSQLString($driver) — audit 19d B3
            // exposed the shallow pull when the K-native began TYPING what
            // the old harness arm resolved by hand)
            case Variable var when lets.containsKey(var.name()) ->
                    substitute(lets.get(var.name()), lets);
            case AppliedFunction af -> {
                AppliedFunction sub = new AppliedFunction(af.function(),
                        substituteAll(af.parameters(), lets));
                // let-substitution is the moment a quote/eval argument can
                // BECOME literal (the subType family's let-bound tree
                // strings) — complete the parse-time fold right here, the
                // same EngineSpecParser front door and carrier as SpecParser
                ValueSpecification folded =
                        com.legend.parser.EngineSpecParser.fold(sub);
                yield folded != null ? folded : sub;
            }
            case AppliedProperty ap3 -> {
                ValueSpecification recv = substitute(ap3.receiver(), lets);
                // pair(a, b).first/.second is a CONSTANT fold (real pure
                // anonymousCollections semantics) — the datetime plan
                // helpers return Pair<ExecutionPlan, String>
                if (recv instanceof AppliedFunction pf
                        && EngineTestExecutor.simpleName(pf.function()).equals("pair")
                        && pf.parameters().size() == 2) {
                    if (ap3.property().equals("first")) {
                        yield pf.parameters().get(0);
                    }
                    if (ap3.property().equals("second")) {
                        yield pf.parameters().get(1);
                    }
                }
                yield new AppliedProperty(java.util.Objects.requireNonNull(recv, "recv"),
                        ap3.property());
            }
            case PureCollection pc -> new PureCollection(
                    substituteAll(pc.values(), lets));
            case LambdaFunction lf -> {
                // shadowing params stop LET substitution; a LAMBDA-LOCAL
                // let shadows the outer binding for the statements below
                // it (real pure scoping — the plan-printer's injected
                // Allocation lets rely on it)
                Map<String, ValueSpecification> visible = new LinkedHashMap<>(lets);
                lf.parameters().forEach(p2 -> visible.remove(p2.name()));
                List<ValueSpecification> body = new ArrayList<>(lf.body().size());
                for (ValueSpecification st : lf.body()) {
                    body.add(substitute(st, visible));
                    if (st instanceof AppliedFunction lfn
                            && lfn.function().equals("letFunction")
                            && lfn.parameters().size() == 2
                            && lfn.parameters().get(0) instanceof CString ln) {
                        visible.remove(ln.value());
                    }
                }
                yield new LambdaFunction(lf.parameters(), body);
            }
            // graph-tree NODE ARGS read lets too (milestoned property
            // calls in fetch trees: authors($businessDate) { ... } — the
            // tree rides its own let and the date var must inline like
            // any other read)
            case com.legend.protocol.spec.ColSpecArray csa ->
                    new com.legend.protocol.spec.ColSpecArray(
                            csa.colSpecs().stream().map(cs2 ->
                                    new com.legend.protocol.spec.ColSpec(
                                            cs2.name(),
                                            (LambdaFunction) substitute(
                                                    cs2.function1(), lets),
                                            (LambdaFunction) substitute(
                                                    cs2.function2(), lets),
                                            ElqSplice.keyAlias(cs2, lets),
                                            substituteAll(cs2.args(), lets),
                                            cs2.qualified()))
                                    .toList());
            // ^X(prop=$let, ...) / ^$let(prop=...) — the binding
            // EXPRESSIONS read lets too (the XStore runtime copy-ctors:
            // ^$dbRuntime(connectionStores=$dbRuntime.connectionStores
            // ->concatenate(...)) reached the Typer with free vars)
            case NewInstance ni -> {
                Map<String, KeyExpression> props = new LinkedHashMap<>();
                ni.properties().forEach((k, ke) -> props.put(k,
                        new KeyExpression(subst(ke.value(), lets),
                                ke.isAdd(), ke.isLocal())));
                yield new NewInstance(ni.className(), ni.typeArguments(),
                        props);
            }
            default -> v.mapChildren(x -> subst(x, lets));
        };
    }

    static List<ValueSpecification> substituteAll(List<ValueSpecification> vs,
            Map<String, ValueSpecification> lets) {
        List<ValueSpecification> out = new ArrayList<>(vs.size());
        for (ValueSpecification v : vs) {
            out.add(substitute(v, lets));
        }
        return out;
    }

    /** The chain's OUTER tail carries a sort — its order is a contract. */
}
