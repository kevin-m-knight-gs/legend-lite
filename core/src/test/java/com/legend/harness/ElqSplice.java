// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend.harness;

import com.legend.protocol.TypeExpression;
import com.legend.protocol.spec.AppliedFunction;
import com.legend.protocol.spec.CDate;
import com.legend.protocol.spec.CString;
import com.legend.protocol.spec.LambdaFunction;
import com.legend.protocol.spec.PureCollection;
import com.legend.protocol.spec.ValueSpecification;
import com.legend.protocol.spec.Variable;
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

    /** QUERY-PARAMETER let names of the current test (populated at
     * splice, cleared per test): the engine keeps \$name spellings in
     * serialize keys for RUNTIME-bound parameters; ordinary test-body
     * lets are compile-time values and spell their VALUE. */
    static final ThreadLocal<java.util.Set<String>> ELQ_PARAMS =
            ThreadLocal.withInitial(java.util.HashSet::new);

    static java.util.@com.legend.Nullable List<ValueSpecification> splice(
            CString name, ValueSpecification rhs,
            Map<String, ValueSpecification> lets) {
        if (!(rhs instanceof AppliedFunction elq)
                || !EngineTestExecutor.harnessVocabName(elq.function())
                || !EngineTestExecutor.simpleName(elq.function())
                        .equals("executeLegendQuery")
                || elq.parameters().isEmpty()
                || !(EngineTestExecutor.substitute(elq.parameters().get(0), lets)
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
                    EngineTestExecutor.substitute(elq.parameters().get(1), lets), lets);
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
                ELQ_PARAMS.get().add(v.name());
                out.add(new AppliedFunction("letFunction", List.of(
                        new CString(v.name()), coerce(v, val))));
            }
        }
        List<ValueSpecification> qb = qlf.body();
        // F3.2d: serialize-key aliases stamp HERE (pre-substitution, the
        // last point that sees the variable spellings) — substitution is
        // no longer in the alias business
        for (ValueSpecification st : qb.subList(0, qb.size() - 1)) {
            out.add(stampKeys(st));
        }
        ValueSpecification fin = stampKeys(qb.get(qb.size() - 1));
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

    /** F3.2d: pre-substitution alias stamping over the spliced query
     * body — every ColSpec whose args read an ELQ param gets its
     * synthetic serialize key ({@code customer($processingDate)}) set
     * while the variable spelling still exists. Rebuild is lossless. */
    private static ValueSpecification stampKeys(ValueSpecification v) {
        // POST-ORDER: children first — nested tree nodes
        // (customer(...){ address($businessDate){...} }) carry their own
        // ColSpecs inside the outer spec's lambdas
        ValueSpecification walked = v.mapChildren(ElqSplice::stampKeys);
        if (walked instanceof com.legend.protocol.spec.ColSpec cs) {
            String a = keyAlias(cs);
            return java.util.Objects.equals(a, cs.alias()) ? cs
                    : new com.legend.protocol.spec.ColSpec(cs.name(),
                            cs.function1(), cs.function2(), a, cs.args(),
                            cs.qualified(), cs.pos(), cs.colType(),
                            cs.colTypeMult(), cs.stereotypes(),
                            cs.taggedValues());
        }
        return walked;
    }

    /** Synthetic serialize-key alias for a qualifier tree node: an arg
     * spelled as an ELQ-PARAM variable keeps its source form in the
     * engine key even though the value inlines for execution.
     * Null-alias pass-through otherwise. */
    static @com.legend.Nullable String keyAlias(
            com.legend.protocol.spec.ColSpec cs) {
        if (cs.alias() != null || cs.args().isEmpty()
                || cs.args().stream().noneMatch(a -> a instanceof Variable v
                        && ELQ_PARAMS.get().contains(v.name()))) {
            return cs.alias();
        }
        StringBuilder k = new StringBuilder(cs.name()).append('(');
        for (int i = 0; i < cs.args().size(); i++) {
            if (i > 0) {
                k.append(", ");
            }
            switch (cs.args().get(i)) {
                case Variable v -> k.append('$').append(v.name());
                case CString s -> k.append('\'').append(s.value()).append('\'');
                case com.legend.protocol.spec.CInteger ci -> k.append(ci.value());
                case com.legend.protocol.spec.CBoolean cb -> k.append(cb.value());
                case CDate cd -> {
                    String d = cd.value().toEngineString();
                    k.append(d).append(d.indexOf('T') >= 0 ? "+0000" : "");
                }
                default -> {
                    return cs.alias();
                }
            }
        }
        return k.append(')').toString();
    }

    /** Whether the arrow-chain HEAD path of {@code vs} contains a call
     * named {@code fn} ({@code x->serialize(...)->from(...)} does). */
    private static boolean headChainContains(ValueSpecification vs,
            String fn) {
        ValueSpecification cur = vs;
        while (cur instanceof AppliedFunction af
                && !af.parameters().isEmpty()) {
            if (EngineTestExecutor.simpleName(af.function()).equals(fn)) {
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
                    || !EngineTestExecutor.simpleName(pair.function()).equals("pair")
                    || pair.parameters().size() != 2
                    || !(EngineTestExecutor.substitute(pair.parameters().get(0), lets)
                            instanceof CString key)) {
                return null;
            }
            out.put(key.value(),
                    EngineTestExecutor.substitute(pair.parameters().get(1), lets));
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
            default -> new com.legend.protocol.spec.EnumValue(
                    ref.name(), cs.value());
        };
    }
}
