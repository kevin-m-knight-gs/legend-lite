// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend.harness;

import com.legend.compiler.NameResolver;
import com.legend.compiler.element.ModelContext;
import com.legend.error.NotImplementedException;
import com.legend.model.ImportScope;
import com.legend.model.spec.AppliedFunction;
import com.legend.model.spec.AppliedProperty;
import com.legend.model.spec.CBoolean;
import com.legend.model.spec.CFloat;
import com.legend.model.spec.CInteger;
import com.legend.model.spec.CString;
import com.legend.model.spec.LambdaFunction;
import com.legend.model.spec.PackageableElementPtr;
import com.legend.model.spec.PureCollection;
import com.legend.model.spec.ValueSpecification;
import com.legend.model.spec.Variable;
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
    static boolean hasGenerate(ValueSpecification rhs) {
        return findCall(rhs, "generateTestData") != null;
    }

    /** planTestDataGeneration: a PLAN-TEXT contract (MultiResultSequence
     * printout with engine-H2 SQL per fetch) — pending the tdg plan
     * printer. */
    static boolean hasPlanGenerate(ValueSpecification rhs) {
        return findCall(rhs, "planTestDataGeneration") != null;
    }

    /** Whether the rhs carries a generateSeedDataString call. */
    static boolean hasSeedDataString(ValueSpecification rhs) {
        return findCall(rhs, "generateSeedDataString") != null;
    }

    /** Run generateSeedDataString — the createRowIdentifier SOURCE-CODE
     * rendering of the demanded rows. */
    static TestDataGenerator.Result runSeedDataString(
            ValueSpecification rhs, ModelContext ctx, ImportScope imports,
            java.sql.Connection conn) throws java.sql.SQLException {
        AppliedFunction call = findCall(rhs, "generateSeedDataString");
        List<ValueSpecification> ps = call.parameters();
        if (ps.size() < 2 || !(ps.get(0) instanceof LambdaFunction query)
                || !(ps.get(1) instanceof PackageableElementPtr mp)) {
            throw new NotImplementedException(
                    "testDataGen: unrecognized seed-data call shape");
        }
        String mappingFqn = qualify(mp.fullPath(), ctx, imports);
        LambdaFunction resolved = (LambdaFunction) NameResolver
                .resolveQuery(query, imports, ctx.elementFqns());
        return new TestDataGenerator.Result(List.of(),
                TestDataGenerator.seedDataString(ctx, resolved, mappingFqn,
                        conn));
    }

    /** Whether the rhs carries a getRelationalCSVDataFromQuery call. */
    static boolean hasCsvCensus(ValueSpecification rhs) {
        return findCall(rhs, "getRelationalCSVDataFromQuery") != null;
    }

    /** Run the NECESSARY-column census (engine
     * getRelationalCSVDataFromQuery — no execution). */
    static TestDataGenerator.Result runCsvCensus(ValueSpecification rhs,
            ModelContext ctx, ImportScope imports) {
        AppliedFunction call =
                findCall(rhs, "getRelationalCSVDataFromQuery");
        List<ValueSpecification> ps = call.parameters();
        if (ps.size() < 2 || !(ps.get(0) instanceof LambdaFunction query)
                || !(ps.get(1) instanceof PackageableElementPtr mp)) {
            throw new NotImplementedException(
                    "testDataGen: unrecognized csv-census call shape");
        }
        String mappingFqn = qualify(mp.fullPath(), ctx, imports);
        LambdaFunction resolved = (LambdaFunction) NameResolver
                .resolveQuery(query, imports, ctx.elementFqns());
        return new TestDataGenerator.Result(List.of(), null,
                TestDataGenerator.necessaryColumns(ctx, resolved,
                        mappingFqn));
    }

    /** The planTestDataGeneration PLAN TEXT for a substituted assert
     * argument, or null when the argument carries no such call. Walls
     * throw {@link NotImplementedException}. */
    static String planText(ValueSpecification subArg, ModelContext ctx,
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
            classifyArg(ps.get(i), rowIds, dates);
        }
        String mappingFqn = qualify(mp.fullPath(), ctx, imports);
        LambdaFunction resolved = (LambdaFunction) NameResolver
                .resolveQuery(query, imports, ctx.elementFqns());
        return TestDataGenerator.planText(ctx, resolved, mappingFqn,
                rowIds, dates[0]);
    }

    /** Parse + run. Walls throw {@link NotImplementedException}. */
    static TestDataGenerator.Result run(ValueSpecification rhs,
            ModelContext ctx, ImportScope imports, Connection conn)
            throws SQLException {
        AppliedFunction call = findCall(rhs, "generateTestData");
        List<ValueSpecification> ps = call.parameters();
        if (ps.size() < 4) {
            throw new NotImplementedException("testDataGen: "
                    + ps.size() + "-arg generateTestData pending");
        }
        if (!(ps.get(0) instanceof LambdaFunction query)) {
            throw new NotImplementedException(
                    "testDataGen: non-lambda query argument");
        }
        if (!(ps.get(1) instanceof PackageableElementPtr mp)) {
            throw new NotImplementedException(
                    "testDataGen: non-pointer mapping argument");
        }
        List<TestDataGenerator.TableRowIds> rowIds = new ArrayList<>();
        TestDataGenerator.MilestoningDates[] dates =
                new TestDataGenerator.MilestoningDates[1];
        for (int i = 3; i < ps.size(); i++) {
            classifyArg(ps.get(i), rowIds, dates);
        }
        String mappingFqn = qualify(mp.fullPath(), ctx, imports);
        LambdaFunction resolved = (LambdaFunction) NameResolver
                .resolveQuery(query, imports, ctx.elementFqns());
        return TestDataGenerator.generate(ctx, resolved, mappingFqn,
                rowIds, dates[0], conn);
    }

    /** Recognized trailing args: TableRowIdentifiers (single or
     * collection), hashStrings=false, extensions/runtime calls, empty
     * collections. Anything else is a LOUD wall — never silently
     * ignored. */
    private static void classifyArg(ValueSpecification arg,
            List<TestDataGenerator.TableRowIds> rowIds,
            TestDataGenerator.MilestoningDates[] dates) {
        if (arg instanceof CBoolean b) {
            if (b.value()) {
                throw new NotImplementedException(
                        "testDataGen: hashStrings pending");
            }
            return;
        }
        if (arg instanceof PureCollection pc) {
            for (ValueSpecification e : pc.values()) {
                classifyArg(e, rowIds, dates);
            }
            return;
        }
        if (arg instanceof AppliedFunction af) {
            String simple = simple(af.function());
            switch (simple) {
                case "createTableRowIdentifiers" -> {
                    rowIds.add(parseTableRowIds(af));
                    return;
                }
                case "new" -> {
                    // ^ExecutionContext()-style defaults: droppable only
                    // when NO property assignments ride along
                    for (ValueSpecification p : af.parameters()) {
                        if (p instanceof PureCollection pc
                                && !pc.values().isEmpty()) {
                            throw new NotImplementedException("testDataGen:"
                                    + " instance argument with properties"
                                    + " pending");
                        }
                    }
                    return;
                }
                case "createTemporalMilestoningDates" -> {
                    // (businessDate, processingDate, snapshotDate) — each
                    // a date literal or the empty collection
                    String[] d = new String[3];
                    for (int i = 0; i < af.parameters().size() && i < 3;
                            i++) {
                        ValueSpecification pv = af.parameters().get(i);
                        if (pv instanceof com.legend.model.spec.CDate cd) {
                            d[i] = cd.value().toEngineString();
                        } else if (!(pv instanceof PureCollection pc2)
                                || !pc2.values().isEmpty()) {
                            throw new NotImplementedException("testDataGen:"
                                    + " non-literal milestoning date");
                        }
                    }
                    dates[0] = new TestDataGenerator.MilestoningDates(
                            d[0], d[1], d[2]);
                    return;
                }
                case "relationalExtensions", "testRuntime",
                        "executionContext", "extension" -> {
                    return;
                }
                default -> {
                    if (simple.toLowerCase(java.util.Locale.ROOT)
                            .contains("runtime")) {
                        return;   // runtime factory helpers
                    }
                    throw new NotImplementedException("testDataGen:"
                            + " unrecognized argument call '" + simple
                            + "'");
                }
            }
        }
        if (arg instanceof com.legend.model.spec.NewInstance ni) {
            // ^ExecutionContext()-style defaults ride along; anything
            // carrying properties is semantics we must not drop
            if (ni.properties().isEmpty()) {
                return;
            }
            throw new NotImplementedException("testDataGen: instance"
                    + " argument with properties pending");
        }
        throw new NotImplementedException("testDataGen: unrecognized"
                + " argument " + arg.getClass().getSimpleName());
    }

    /** {@code createTableRowIdentifiers(db, 'schema', 'table', [ids])} or
     * {@code createTableRowIdentifiers(getTable(db,'s','t'), [ids])}. */
    private static TestDataGenerator.TableRowIds parseTableRowIds(
            AppliedFunction af) {
        List<ValueSpecification> ps = af.parameters();
        String schema;
        String table;
        ValueSpecification ids;
        if (ps.size() == 4 && ps.get(1) instanceof CString s
                && ps.get(2) instanceof CString t) {
            schema = s.value();
            table = t.value();
            ids = ps.get(3);
        } else if (ps.size() == 2
                && ps.get(0) instanceof AppliedFunction gt
                && simple(gt.function()).equals("getTable")
                && gt.parameters().size() == 3
                && gt.parameters().get(1) instanceof CString s
                && gt.parameters().get(2) instanceof CString t) {
            schema = s.value();
            table = t.value();
            ids = ps.get(1);
        } else {
            throw new NotImplementedException("testDataGen:"
                    + " createTableRowIdentifiers shape pending");
        }
        List<TestDataGenerator.RowId> out = new ArrayList<>();
        collectRowIds(ids, out);
        return new TestDataGenerator.TableRowIds(schema, table, out);
    }

    private static void collectRowIds(ValueSpecification v,
            List<TestDataGenerator.RowId> out) {
        if (v instanceof PureCollection pc) {
            for (ValueSpecification e : pc.values()) {
                collectRowIds(e, out);
            }
            return;
        }
        if (v instanceof AppliedFunction af
                && simple(af.function()).equals("createRowIdentifier")
                && af.parameters().size() == 2) {
            List<String> cols = new ArrayList<>();
            for (ValueSpecification c : flat(af.parameters().get(0))) {
                if (c instanceof CString cs) {
                    cols.add(cs.value());
                } else {
                    throw new NotImplementedException("testDataGen:"
                            + " non-literal row identifier column");
                }
            }
            List<Object> vals = new ArrayList<>();
            for (ValueSpecification c : flat(af.parameters().get(1))) {
                vals.add(literal(c));
            }
            if (cols.size() != vals.size()) {
                throw new NotImplementedException("testDataGen: row"
                        + " identifier column/value arity mismatch");
            }
            out.add(new TestDataGenerator.RowId(cols, vals));
            return;
        }
        throw new NotImplementedException(
                "testDataGen: row identifier shape pending");
    }

    private static Object literal(ValueSpecification v) {
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
        if (v instanceof com.legend.model.spec.CDecimal d) {
            return d.value();
        }
        if (v instanceof AppliedFunction af
                && simple(af.function()).equals("parseDate")
                && af.parameters().size() == 1
                && af.parameters().get(0) instanceof CString s) {
            return s.value();   // dates ride as strings; SQL types them
        }
        if (v instanceof com.legend.model.spec.CDate d) {
            return d.value().toEngineString();
        }
        throw new NotImplementedException("testDataGen: non-literal row"
                + " identifier value "
                + v.getClass().getSimpleName());
    }

    // ===== assert-side reads =====

    /** A read over a bound testData let: {@code $td.sqls},
     * {@code $td.dataCsvString}, through {@code ->toOne()/->at(i)/
     * ->size()}. */
    record Read(String var, String kind) {
    }

    static Read read(ValueSpecification v) {
        String kind = null;
        while (true) {
            if (v instanceof Variable var) {
                return kind == null ? null : new Read(var.name(), kind);
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
                v = af.parameters().get(0);
                continue;
            }
            return null;
        }
    }

    /** Replace {@code $td.dataCsvString} / {@code $td.sqls} reads with
     * their LITERAL values so downstream statements (the corpus's
     * loadAndTestExecution tail) run through the platform unchanged. */
    static ValueSpecification inlineReads(ValueSpecification v,
            java.util.Map<String, TestDataGenerator.Result> tdg) {
        if (tdg.isEmpty()) {
            return v;
        }
        if (v instanceof AppliedProperty ap) {
            Read r = read(ap);
            TestDataGenerator.Result bound =
                    r == null ? null : tdg.get(r.var());
            if (bound != null) {
                if ("dataCsvString".equals(ap.property())) {
                    return new CString(bound.dataCsvString());
                }
                if ("sqls".equals(ap.property())) {
                    List<ValueSpecification> ss = new ArrayList<>();
                    for (String s : bound.sqls()) {
                        ss.add(new CString(s));
                    }
                    return new PureCollection(ss);
                }
            }
            ValueSpecification rec = inlineReads(ap.receiver(), tdg);
            return rec == ap.receiver() ? ap
                    : new AppliedProperty(rec, ap.property());
        }
        if (v instanceof AppliedFunction af) {
            List<ValueSpecification> ps = new ArrayList<>();
            boolean changed = false;
            for (ValueSpecification p : af.parameters()) {
                ValueSpecification p2 = inlineReads(p, tdg);
                changed |= p2 != p;
                ps.add(p2);
            }
            return changed ? new AppliedFunction(af.function(), ps) : af;
        }
        if (v instanceof PureCollection pc) {
            List<ValueSpecification> es = new ArrayList<>();
            boolean changed = false;
            for (ValueSpecification e : pc.values()) {
                ValueSpecification e2 = inlineReads(e, tdg);
                changed |= e2 != e;
                es.add(e2);
            }
            return changed ? new PureCollection(es) : pc;
        }
        if (v instanceof LambdaFunction lf) {
            List<ValueSpecification> bs = new ArrayList<>();
            boolean changed = false;
            for (ValueSpecification b : lf.body()) {
                ValueSpecification b2 = inlineReads(b, tdg);
                changed |= b2 != b;
                bs.add(b2);
            }
            return changed
                    ? new LambdaFunction(lf.parameters(), bs) : lf;
        }
        return v;
    }

    static String qualify(String name, ModelContext ctx,
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
    static String foldString(ValueSpecification v) {
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

    private static AppliedFunction findCall(ValueSpecification n,
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
