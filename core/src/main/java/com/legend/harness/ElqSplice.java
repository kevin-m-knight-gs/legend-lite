// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend.harness;

import com.legend.model.TypeExpression;
import com.legend.model.spec.AppliedFunction;
import com.legend.model.spec.CDate;
import com.legend.model.spec.CString;
import com.legend.model.spec.LambdaFunction;
import com.legend.model.spec.PureCollection;
import com.legend.model.spec.ValueSpecification;
import com.legend.model.spec.Variable;
import com.legend.values.PureDateLiteral;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The {@code meta::legend::executeLegendQuery(q, vars, [exeCtx,]
 * extensions)} SPLICE: the query lambda's body IS the statements, the
 * result binds as the engine's serialized scalar ({@code toString} of
 * the final expression), and — the parameterized form — each lambda
 * parameter binds as a {@code let} from the {@code vars} pair list
 * FIRST, coerced by the parameter's DECLARED type exactly as the
 * engine's own execute entry coerces its JSON-borne variable values
 * (devUtils.pure:30-40 &rarr; {@code meta::legend::execute}): an
 * enum-typed parameter takes the string as the ENUM VALUE name; a
 * date-typed parameter parses the string as a Pure date literal;
 * primitives pass through.
 *
 * <p>Returns null when the statement is not this shape — the caller
 * falls through to ordinary typing, which walls loudly.
 */
final class ElqSplice {

    private ElqSplice() {
    }

    static java.util.@com.legend.Nullable List<ValueSpecification> splice(
            CString name, ValueSpecification rhs,
            Map<String, ValueSpecification> lets) {
        if (!(rhs instanceof AppliedFunction elq)
                || !TestBody.harnessVocabName(elq.function())
                || !TestBody.simpleName(elq.function())
                        .equals("executeLegendQuery")
                || elq.parameters().isEmpty()
                || !(TestBody.substitute(elq.parameters().get(0), lets)
                        instanceof LambdaFunction qlf)
                || qlf.body().isEmpty()) {
            return null;
        }
        List<ValueSpecification> out = new ArrayList<>();
        if (!qlf.parameters().isEmpty()) {
            if (elq.parameters().size() < 2) {
                return null;
            }
            Map<String, ValueSpecification> vars = varPairs(
                    TestBody.substitute(elq.parameters().get(1), lets), lets);
            if (vars == null) {
                return null;
            }
            for (ValueSpecification p : qlf.parameters()) {
                if (!(p instanceof Variable v)) {
                    return null;
                }
                ValueSpecification val = vars.get(v.name());
                if (val == null) {
                    // unbound parameter: not this splice's shape — the
                    // ordinary typing path reports it loudly
                    return null;
                }
                out.add(new AppliedFunction("letFunction", List.of(
                        new CString(v.name()), coerce(v, val))));
            }
        }
        List<ValueSpecification> qb = qlf.body();
        out.addAll(qb.subList(0, qb.size() - 1));
        ValueSpecification fin = qb.get(qb.size() - 1);
        ValueSpecification bound = new AppliedFunction("toString",
                List.of(fin));
        // a GRAPH-SERIALIZE query returns the engine's json-builder
        // envelope — executeLegendQuery's contract is the FULL result
        // JSON, not the bare values (devUtils.pure -> meta::legend::
        // execute; the corpus asserts pin the envelope)
        if (headChainContains(fin, "serialize")) {
            bound = new AppliedFunction("joinStrings", List.of(
                    new PureCollection(List.of(
                            new CString("{\"builder\":{\"_type\":\"json\"},"
                                    + "\"values\":"),
                            bound, new CString("}"))),
                    new CString("")));
        }
        out.add(new AppliedFunction("letFunction", List.of(name, bound)));
        return out;
    }

    /** Whether the arrow-chain HEAD path of {@code vs} contains a call
     * named {@code fn} ({@code x->serialize(...)->from(...)} does). */
    private static boolean headChainContains(ValueSpecification vs,
            String fn) {
        ValueSpecification cur = vs;
        while (cur instanceof AppliedFunction af
                && !af.parameters().isEmpty()) {
            if (TestBody.simpleName(af.function()).equals(fn)) {
                return true;
            }
            cur = af.parameters().get(0);
        }
        return false;
    }

    /** {@code [pair('n', v), ...]} (or a single bare pair, or {@code []})
     * as name &rarr; substituted value; null on any other shape. */
    private static java.util.@com.legend.Nullable Map<String, ValueSpecification> varPairs(
            @com.legend.Nullable ValueSpecification varsArg,
            Map<String, ValueSpecification> lets) {
        List<ValueSpecification> entries;
        if (varsArg instanceof PureCollection coll) {
            entries = coll.values();
        } else if (varsArg != null) {
            entries = List.of(varsArg);
        } else {
            return null;
        }
        Map<String, ValueSpecification> out = new LinkedHashMap<>();
        for (ValueSpecification e : entries) {
            if (!(e instanceof AppliedFunction pair)
                    || !TestBody.simpleName(pair.function()).equals("pair")
                    || pair.parameters().size() != 2
                    || !(TestBody.substitute(pair.parameters().get(0), lets)
                            instanceof CString key)) {
                return null;
            }
            out.put(key.value(),
                    TestBody.substitute(pair.parameters().get(1), lets));
        }
        return out;
    }

    /** Engine variable coercion by DECLARED parameter type: enum name
     * strings become enum values (the resolver qualifies the type name
     * through the test's imports), date strings become Pure date
     * literals; everything else passes through as written. */
    private static ValueSpecification coerce(Variable p,
            ValueSpecification val) {
        if (!(val instanceof CString cs)
                || !(p.type() instanceof TypeExpression.NameRef ref)) {
            return val;
        }
        int cut = ref.name().lastIndexOf("::");
        String simple = cut < 0 ? ref.name() : ref.name().substring(cut + 2);
        return switch (simple) {
            case "Date", "DateTime", "StrictDate" ->
                    new CDate(PureDateLiteral.parse(cs.value()));
            case "String", "Integer", "Float", "Number", "Boolean",
                    "Decimal", "Any" -> val;
            // a non-primitive named type over a string value is the
            // engine's enum-name binding
            default -> new com.legend.model.spec.EnumValue(
                    ref.name(), cs.value());
        };
    }
}
