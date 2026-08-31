// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend.harness;

import com.legend.compiler.NameResolver;
import com.legend.compiler.element.ModelContext;
import com.legend.error.NotImplementedException;
import com.legend.model.ImportScope;
import com.legend.protocol.spec.AppliedFunction;
import com.legend.protocol.spec.AppliedProperty;
import com.legend.protocol.spec.CBoolean;
import com.legend.protocol.spec.CFloat;
import com.legend.protocol.spec.CInteger;
import com.legend.protocol.spec.CString;
import com.legend.protocol.spec.LambdaFunction;
import com.legend.protocol.spec.PackageableElementPtr;
import com.legend.protocol.spec.PureCollection;
import com.legend.protocol.spec.ValueSpecification;
import com.legend.protocol.spec.Variable;
import com.legend.testdatagen.TestDataGenerator;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * The engine's testDataGeneration TEST FORM (#46): {@code let testData =
 * generateTestData($query, $mapping, $runtime, $rowIdentifiers, ...)
 * ->toOne()} with {@code assertTestData(csv, $testData.dataCsvString,
 * $db)} (the ROW contract, verified via
 * {@link TestDataGenerator#compareCsv}), {@code assertSize($testData
 * .sqls, N)} (verified fetch count) and {@code assertSqlEquals(golden,
 * $testData.sqls->at(i))} (engine H2 SQL text — advisory, the golden-SQL
 * doctrine). Parsing lives here; ALL data work runs in the database
 * ({@link TestDataGenerator}).
 */
final class TestDataGenForm {

    private TestDataGenForm() {
    }

    /** Does this let RHS carry a generateTestData call? */
    /** S4 classifier sentinel: the binding IS a generateTestData let —
     * no harness-side execution rides it (the platform carrier owns the
     * one real run). */
    static final TestDataGenerator.Result NAME_ONLY =
            new TestDataGenerator.Result(java.util.List.of(), null, null);

    static boolean hasGenerate(@com.legend.Nullable ValueSpecification rhs) {
        return findCall(rhs, "generateTestData") != null;
    }

    /** planTestDataGeneration: a PLAN-TEXT contract (MultiResultSequence
     * printout with engine-H2 SQL per fetch) — pending the tdg plan
     * printer. */
    static boolean hasPlanGenerate(@com.legend.Nullable ValueSpecification rhs) {
        return findCall(rhs, "planTestDataGeneration") != null;
    }

    /** The planTestDataGeneration PLAN TEXT for a substituted assert
     * argument, or null when the argument carries no such call. Walls
     * throw {@link NotImplementedException}. */
    static @com.legend.Nullable String planText(ValueSpecification subArg, ModelContext ctx,
            ImportScope imports) {
        AppliedFunction call = findCall(subArg, "planTestDataGeneration");
        if (call == null) {
            return null;
        }
        List<ValueSpecification> ps = call.parameters();
        if (ps.size() < 4 || !(ps.get(0) instanceof LambdaFunction query)
                || !(ps.get(1) instanceof PackageableElementPtr mp)) {
            throw new NotImplementedException(
                    "testDataGen plan: unrecognized call shape");
        }
        List<TestDataGenerator.TableRowIds> rowIds = new ArrayList<>();
        TestDataGenerator.MilestoningDates[] dates =
                new TestDataGenerator.MilestoningDates[1];
        for (int i = 3; i < ps.size(); i++) {
            com.legend.testdatagen.TestDataGenerationNatives.classifyArg(
                    ps.get(i), rowIds, dates, new boolean[1]);
        }
        String mappingFqn = java.util.Objects.requireNonNull(
                qualify(mp.fullPath(), ctx, imports),
                "unresolvable mapping reference");
        LambdaFunction resolved = (LambdaFunction) NameResolver
                .resolveQuery(query, imports, ctx.elementFqns());
        return TestDataGenerator.planText(ctx, resolved, mappingFqn,
                rowIds, dates[0]);
    }

    /** Parse + run. Walls throw {@link NotImplementedException}. */
    /** {@code createTableRowIdentifiers(db, 'schema', 'table', [ids])} or
     * {@code createTableRowIdentifiers(getTable(db,'s','t'), [ids])}. */
    private static @com.legend.Nullable Object literal(ValueSpecification v) {
        if (v instanceof CString s) {
            return s.value();
        }
        if (v instanceof CInteger i) {
            return i.value();
        }
        if (v instanceof CFloat f) {
            return f.value();
        }
        if (v instanceof CBoolean b) {
            return b.value();
        }
        if (v instanceof com.legend.protocol.spec.CDecimal d) {
            return d.value();
        }
        if (v instanceof AppliedFunction af
                && simple(af.function()).equals("parseDate")
                && af.parameters().size() == 1
                && af.parameters().get(0) instanceof CString s) {
            return s.value();   // dates ride as strings; SQL types them
        }
        if (v instanceof com.legend.protocol.spec.CDate d) {
            return d.value().toEngineString();
        }
        throw new NotImplementedException("testDataGen: non-literal row"
                + " identifier value "
                + v.getClass().getSimpleName());
    }

    // ===== assert-side reads =====

    /** A read over a bound testData let: {@code $td.sqls},
     * {@code $td.dataCsvString}, through {@code ->toOne()/->at(i)/
     * ->size()}. {@code index} is the {@code ->at(i)} literal when the
     * read carries one (-1 otherwise) — the same index the test's own
     * assert uses to address the fetch transcript. */
    record Read(String var, String kind, int index) {
        Read(String var, String kind) {
            this(var, kind, -1);
        }
    }

    static @com.legend.Nullable Read read(ValueSpecification v) {
        String kind = null;
        int index = -1;
        while (true) {
            if (v instanceof Variable var) {
                return kind == null ? null
                        : new Read(var.name(), kind, index);
            }
            if (v instanceof AppliedProperty ap) {
                if (ap.property().equals("sqls")
                        || ap.property().equals("dataCsvString")) {
                    kind = ap.property();
                }
                v = ap.receiver();
                continue;
            }
            if (v instanceof AppliedFunction af
                    && !af.parameters().isEmpty()
                    && List.of("toOne", "at", "size", "makeString",
                            "sqlRemoveFormatting")
                            .contains(simple(af.function()))) {
                if (simple(af.function()).equals("at")
                        && af.parameters().size() == 2
                        && af.parameters().get(1) instanceof CInteger ci) {
                    index = ci.value().intValue();
                }
                v = af.parameters().get(0);
                continue;
            }
            return null;
        }
    }

    /** The REFEREE-time transcript run (chained-fetch refereeing): the
     * substituted assert argument carries the test's own
     * generateTestData call — classify its arguments through the ONE
     * platform classifier and run the ONE platform generator, returning
     * the {@link TestDataGenerator.Result} WITH its per-fetch
     * live-session rows. Per-statement re-evaluation is the platform
     * carrier's own chartered execution model (deterministic reads over
     * static test seeds — TestDataGenerationNatives.foldCensus), so
     * this run's transcript is the same transcript the carrier's run
     * produced; the caller pins that receipt by comparing sqls texts
     * byte-exactly. Returns null when no generateTestData call is
     * present. */
    static TestDataGenerator.@com.legend.Nullable Result transcript(
            ValueSpecification subArg, ModelContext ctx,
            ImportScope imports, java.sql.Connection conn)
            throws SQLException {
        AppliedFunction call = findCall(subArg, "generateTestData");
        if (call == null) {
            return null;
        }
        List<ValueSpecification> ps = call.parameters();
        if (ps.size() < 4 || !(ps.get(0) instanceof LambdaFunction query)
                || !(ps.get(1) instanceof PackageableElementPtr mp)) {
            throw new NotImplementedException(
                    "testDataGen transcript: unrecognized call shape");
        }
        List<TestDataGenerator.TableRowIds> rowIds = new ArrayList<>();
        TestDataGenerator.MilestoningDates[] dates =
                new TestDataGenerator.MilestoningDates[1];
        boolean[] hash = new boolean[1];
        for (int i = 3; i < ps.size(); i++) {
            com.legend.testdatagen.TestDataGenerationNatives.classifyArg(
                    ps.get(i), rowIds, dates, hash);
        }
        String mappingFqn = java.util.Objects.requireNonNull(
                qualify(mp.fullPath(), ctx, imports),
                "unresolvable mapping reference");
        LambdaFunction resolved = (LambdaFunction) NameResolver
                .resolveQuery(query, imports, ctx.elementFqns());
        return TestDataGenerator.generate(ctx, resolved, mappingFqn,
                rowIds, dates[0], hash[0], conn);
    }

    /** Replace {@code $td.dataCsvString} / {@code $td.sqls} reads with
     * their LITERAL values so downstream statements (the corpus's
     * loadAndTestExecution tail) run through the platform unchanged. */
    static @com.legend.Nullable String qualify(String name, ModelContext ctx,
            ImportScope imports) {
        if (ctx.findMapping(name).isPresent()
                || ctx.findDatabase(name).isPresent()) {
            return name;
        }
        for (String imp : imports.wildcards()) {
            String q = imp + "::" + name;
            if (ctx.findMapping(q).isPresent()
                    || ctx.findDatabase(q).isPresent()) {
                return q;
            }
        }
        return name;
    }

    /** Fold {@code 'a' + 'b' + ...} chains to one literal. */
    static @com.legend.Nullable String foldString(ValueSpecification v) {
        if (v instanceof CString cs) {
            return cs.value();
        }
        if (v instanceof AppliedFunction af
                && simple(af.function()).equals("plus")) {
            StringBuilder sb = new StringBuilder();
            for (ValueSpecification p : flat(af.parameters())) {
                String s = foldString(p);
                if (s == null) {
                    return null;
                }
                sb.append(s);
            }
            return sb.toString();
        }
        return null;
    }

    private static List<ValueSpecification> flat(
            List<ValueSpecification> vs) {
        List<ValueSpecification> out = new ArrayList<>();
        for (ValueSpecification v : vs) {
            out.addAll(flat(v));
        }
        return out;
    }

    private static List<ValueSpecification> flat(ValueSpecification v) {
        if (v instanceof PureCollection pc) {
            List<ValueSpecification> out = new ArrayList<>();
            for (ValueSpecification e : pc.values()) {
                out.addAll(flat(e));
            }
            return out;
        }
        return List.of(v);
    }

    private static @com.legend.Nullable AppliedFunction findCall(@com.legend.Nullable ValueSpecification n,
            String name) {
        if (n instanceof AppliedFunction af) {
            if (name.equals(simple(af.function()))) {
                return af;
            }
            for (ValueSpecification p : af.parameters()) {
                AppliedFunction r = findCall(p, name);
                if (r != null) {
                    return r;
                }
            }
        } else if (n instanceof AppliedProperty ap) {
            return findCall(ap.receiver(), name);
        } else if (n instanceof LambdaFunction lf) {
            for (ValueSpecification b : lf.body()) {
                AppliedFunction r = findCall(b, name);
                if (r != null) {
                    return r;
                }
            }
        } else if (n instanceof PureCollection pc) {
            for (ValueSpecification e : pc.values()) {
                AppliedFunction r = findCall(e, name);
                if (r != null) {
                    return r;
                }
            }
        }
        return null;
    }

    private static String simple(String f) {
        return f.substring(f.lastIndexOf(':') + 1);
    }
}
