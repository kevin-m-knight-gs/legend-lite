// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend;

import com.legend.database.Databases;
import com.legend.executionplan.ExecutionPlan;
import com.legend.lowering.WireRender;
import com.legend.model.ConnectionDefinition.DatabaseType;
import com.legend.testcases.PlanCases;
import com.legend.testcases.PlanCases.OptionalCase;
import com.legend.testcases.PlanCases.Parameterised;
import com.legend.testbackend.TestBackend;
import com.legend.testbackend.TestBackends;
import org.junit.jupiter.api.Test;

import java.io.StringWriter;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The planner's plans (docs/EXECUTION_PLAN_BOUNDARY_2026_10_05.md §9, step 2's landing 2): a plan answers exactly as
 * today's wire and streaming paths answer the same query. Each query runs twice, on two fresh databases: through
 * {@link Execution} (its connection's declared test data established first, as the server establishes it), and through
 * its plan — the target's session statements, its setup steps, its statement — run here as each step describes itself
 * (the runner is step 3; the cases and the run are {@link PlanCases}'). The texts must be equal, byte for byte.
 * Each in-process database answers; Postgres's case is {@code PostgresArmTest}'s (it needs the embedded Postgres). What
 * one database's plan says (its versions, its session, its bindings) is that database's test, {@code PlanMaker<Db>Test}
 * (the dialect program's Phase 6, 2026-10-16).
 */
class PlanMakerTest {

    /** The register of each database's known differences from today's paths, shrink-only: a row is its backend, the
     *  output, the query (today's form: its values as lets) and the plan's own answer, exactly, or its refusal
     *  ({@code refused: } and its message), escaped to one line; lines starting {@code #} are its reasons (the dialect
     *  program's Phase 6, 2026-10-16, when the cases first ran on SQLite; exact since the audit of the branch). */
    private static final String DIFFERENCES_FILE = "plan-maker/differences.tsv";

    private static final java.util.Map<String, String> DIFFERENCES = differences();

    /** The register's rows the cases reached, so that a row no case reaches fails ({@link #everyDifferenceIsACase}). */
    private static final java.util.Set<String> CONSULTED = java.util.concurrent.ConcurrentHashMap.newKeySet();

    private static java.util.Map<String, String> differences() {
        java.util.Map<String, String> rows = new java.util.LinkedHashMap<>();
        try (var in = java.util.Objects.requireNonNull(PlanMakerTest.class.getClassLoader()
                .getResourceAsStream(DIFFERENCES_FILE), DIFFERENCES_FILE)) {
            for (String line : new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).split("\n")) {
                if (line.isBlank() || line.startsWith("#")) {
                    continue;
                }
                String[] cells = line.split("\t");
                if (cells.length != 4) {
                    throw new IllegalStateException(DIFFERENCES_FILE + ": a row is backend, output, query, answer: "
                            + line);
                }
                rows.put(cells[0] + "\t" + cells[1] + "\t" + cells[2], cells[3]);
            }
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
        return rows;
    }

    /** The register's rows, at most: 31 measured 2026-10-16, when the cases first ran on SQLite (the dialect
     *  program's Phase 6). Lowered with the rows; raised only with a dated reason. */
    private static final int MAX_DIFFERENCES = 31;

    /** The test methods run in this class, so that unreached rows are judged on a whole run only. */
    private static final java.util.concurrent.atomic.AtomicInteger RAN = new java.util.concurrent.atomic.AtomicInteger();

    @org.junit.jupiter.api.AfterEach
    void ran() {
        RAN.incrementAndGet();
    }

    @org.junit.jupiter.api.AfterAll
    static void everyDifferenceIsACase() {
        assertTrue(DIFFERENCES.size() <= MAX_DIFFERENCES, DIFFERENCES_FILE + " has " + DIFFERENCES.size()
                + " rows, above its ceiling " + MAX_DIFFERENCES + ": a row is added only with a dated reason");
        long declared = java.util.Arrays.stream(PlanMakerTest.class.getDeclaredMethods())
                .filter(m -> m.isAnnotationPresent(Test.class)).count();
        if (RAN.get() < declared) {
            return;   // a run of some of the methods reaches some of the rows
        }
        java.util.Set<String> unreached = new java.util.TreeSet<>(DIFFERENCES.keySet());
        unreached.removeAll(CONSULTED);
        assertEquals(java.util.Set.of(), unreached, "rows of " + DIFFERENCES_FILE + " no case reaches: remove them");
    }

    /** The databases a plan is run on here: every in-process one. */
    static final List<DatabaseType> IN_PROCESS = TestBackends.inProcess().stream().map(TestBackend::type).toList();

    /** A table of three rows, declared as the connection's test data; {@code type} is the connection's database. */
    static String model(String type) {
        return """
                Class s::Item { id: Integer[1]; name: String[0..1]; price: Decimal[0..1]; }
                ###Relational
                Database s::DB ( Table T ( ID INTEGER PRIMARY KEY, NAME VARCHAR(20), PRICE DECIMAL(10,2) ) )
                ###Mapping
                Mapping s::M ( *s::Item: Relational { ~mainTable [s::DB] T
                    id: [s::DB] T.ID, name: [s::DB] T.NAME, price: [s::DB] T.PRICE } )
                ###Connection
                RelationalDatabaseConnection s::Conn
                {
                    store: s::DB;
                    type: %s;
                    specification: LocalH2 { testDataSetupCSV: 'default\\nT\\nID,NAME,PRICE\\n1,a,1.50\\n2,O\\'Brien,---null---\\n3,---null---,3.25\\n'; };
                    auth: DefaultH2;
                }
                ###Runtime
                Runtime s::RT { mappings: [s::M]; connections: [ s::DB: [ c1: s::Conn ] ]; }
                """.formatted(type);
    }

    private static final String RELATION = "|#>{s::DB.T}#->select(~[ID, NAME, PRICE])->sort(~ID->ascending())";
    private static final String NONE = "|#>{s::DB.T}#->filter(r|$r.ID > 99)->select(~[ID, NAME])";
    private static final String PROJECT = "|s::Item.all()->project(~[name: i|$i.name, price: i|$i.price])"
            + "->sort(~name->ascending())";
    private static final String GRAPH = "|s::Item.all()->graphFetch(#{s::Item{id, name, price}}#)"
            + "->serialize(#{s::Item{id, name, price}}#)";

    @Test
    void aRelationsPlanAnswersAsTodaysPaths_everyOutput() throws Exception {
        for (DatabaseType type : IN_PROCESS) {
            for (String query : List.of(RELATION, NONE, PROJECT)) {
                for (TypedQuery.Output output : TypedQuery.Output.values()) {
                    assertAnswersAsToday(type, query, output);
                }
            }
        }
    }

    @Test
    void aGraphFetchsPlanAnswersAsTodaysPaths_inJson() throws Exception {
        for (DatabaseType type : IN_PROCESS) {
            assertAnswersAsToday(type, GRAPH, TypedQuery.Output.JSON);
            assertAnswersAsToday(type, GRAPH, TypedQuery.Output.STREAMED_JSON);
            var refused = assertThrows(com.legend.error.NotImplementedException.class,
                    () -> plan(type, GRAPH, TypedQuery.Output.CSV));
            assertTrue(refused.getMessage().contains("graph results have no CSV wire"), refused.getMessage());
        }
    }

    /** The target names its connection on every database; its versions, session and rows are each database's own
     *  ({@code PlanMaker<Db>Test}). */
    @Test
    void theTargetNamesItsConnection() {
        for (DatabaseType type : IN_PROCESS) {
            ExecutionPlan.Target t = target(plan(type, RELATION, TypedQuery.Output.JSON));
            assertEquals("s::Conn", ((ExecutionPlan.Database.Declared) t.database()).connection().qualifiedName(),
                    type.name());
        }
    }

    @Test
    void aRuntimeOfModelDataRunsOnThePlatformsEngine() throws Exception {
        String model = """
                Class m::Raw { name: String[1]; }
                Class m::P { name: String[1]; }
                ###Mapping
                Mapping m::MM ( *m::P: Pure { ~src m::Raw name: $src.name } )
                ###Runtime
                Runtime m::RT { mappings: [m::MM]; connections: [ ModelStore: [ json: #{ JsonModelConnection {
                    class: m::Raw; url: 'data:application/json,[{"name":"b"},{"name":"a"}]'; } }# ] ]; }
                """;
        String query = "|m::P.all()->project(~[name: p|$p.name])->sort(~name->ascending())";
        ExecutionPlan plan = Compiler.query(Compiler.compileModel(model), query)
                .executionPlan("m::RT", TypedQuery.Output.JSON);
        ExecutionPlan.Target t = target(plan);
        assertEquals(new ExecutionPlan.Database.Platform(Databases.PLATFORM), t.database());
        assertEquals(List.of(), t.setup());
        try (Connection today = TestBackends.platform().open();
             Connection planned = TestBackends.platform().open()) {
            StringWriter out = new StringWriter();
            Execution.executeWire(model, query, "m::RT", today, WireRender.Format.JSON, out);
            assertEquals(out.toString(), PlanCases.run(plan, planned));
        }
    }

    @Test
    void aRuntimeOfTwoDifferentConnectionsIsRefusedWhenThePlanIsMade() {
        // a second store with a connection of its own, another database of the same type: the query reads only the
        // first store, but the runtime binds both, and a query runs on one session (the server refuses it as it opens)
        String model = model("H2").replace("""
                ###Runtime
                Runtime s::RT { mappings: [s::M]; connections: [ s::DB: [ c1: s::Conn ] ]; }
                """, """
                ###Connection
                RelationalDatabaseConnection s::Other { store: s::DB2; type: H2; specification: LocalH2 { }; auth: DefaultH2; }
                ###Relational
                Database s::DB2 ( Table U ( ID INTEGER PRIMARY KEY ) )
                ###Runtime
                Runtime s::RT { mappings: [s::M]; connections: [ s::DB: [ c1: s::Conn ], s::DB2: [ c2: s::Other ] ]; }
                """);
        assertTrue(model.contains("c2: s::Other"), model);
        var refused = assertThrows(com.legend.error.NotImplementedException.class,
                () -> Compiler.query(Compiler.compileModel(model), RELATION).executionPlan("s::RT", TypedQuery.Output.JSON));
        assertTrue(refused.getMessage().contains("different connections: a query runs on one session"),
                refused.getMessage());
    }

    private static final List<Parameterised> SCALARS = PlanCases.scalars("T");

    @Test
    void anOptionalParametersPlanAnswersAsTheQuery_withItsValueAndWithNone() throws Exception {
        for (DatabaseType type : IN_PROCESS) {
            for (OptionalCase q : PlanCases.optionals("T")) {
                for (TypedQuery.Output output : TypedQuery.Output.values()) {
                    assertAnswersAsToday(type, q.withParameter(), q.withValue(), java.util.Map.of("x", q.value()),
                            output);
                    assertAnswersAsToday(type, q.withParameter(), q.withNone(),
                            java.util.Collections.singletonMap("x", null), output);
                }
            }
        }
    }

    /** {@link PlanCases#enumModel} with its rows as an in-memory connection's declared test data. */
    private static String enumModel(DatabaseType type) {
        return PlanCases.enumModel("type: " + type.name() + "; specification: LocalH2 { testDataSetupCSV: '"
                + PlanCases.enumRows("A").replace("\n", "\\n") + "'; }; auth: DefaultH2;", "A");
    }

    @Test
    void anEnumerationParametersPlanAnswersAsTheQueryWithItsValue() throws Exception {
        for (DatabaseType type : IN_PROCESS) {
            for (Parameterised q : PlanCases.enumerations()) {
                for (TypedQuery.Output output : TypedQuery.Output.values()) {
                    assertAnswersAsToday(enumModel(type), type, q.withParameters(), q.withLets(),
                            q.values(), output);
                }
            }
        }
    }

    @Test
    void aListParametersPlanAnswersAsTheQueryWithItsValues_everyOutput() throws Exception {
        for (DatabaseType type : IN_PROCESS) {
            for (Parameterised q : PlanCases.lists("T")) {
                for (TypedQuery.Output output : TypedQuery.Output.values()) {
                    assertAnswersAsToday(type, q.withParameters(), q.withLets(), q.values(), output);
                }
            }
            for (TypedQuery.Output output : TypedQuery.Output.values()) {
                assertAnswersAsToday(enumModel(type), type, "{sts: s::Status[*]|s::Acct.all()->filter(a|$a.status->in($sts))"
                        + "->project(~[id: a|$a.id])->sort(~id->ascending())}", "|let sts = [s::Status.ACTIVE];"
                        + "s::Acct.all()->filter(a|$a.status->in($sts))->project(~[id: a|$a.id])->sort(~id->ascending());",
                        java.util.Map.of("sts", List.of("ACTIVE")), output);
            }
        }
    }

    /** A list of decimals is refused by name (a list's one array is each database's: {@code PlanMaker<Db>Test}). */
    @Test
    void aListOfDecimalsIsRefusedByName() {
        var refused = assertThrows(com.legend.error.NotImplementedException.class, () -> plan(Databases.PLATFORM,
                "{ps: Decimal[*]|#>{s::DB.T}#->filter(r|$r.ID->in($ps))}", TypedQuery.Output.JSON));
        assertTrue(refused.getMessage().contains("PARK-20"), refused.getMessage());
    }

    /** Compared with a mapped column, an enumeration's name is translated by a value table at that place, so the
     *  comparison reads the stored column and keeps its index (§9, measured: probes/enum-index-results.txt). */
    @Test
    void anEnumerationParameterIsComparedThroughAValueTable() {
        ExecutionPlan plan = Compiler.query(Compiler.compileModel(enumModel(Databases.PLATFORM)),
                PlanCases.enumerations().get(0).withParameters()).executionPlan("s::RT", TypedQuery.Output.JSON);
        String statement = ((ExecutionPlan.TextResult) plan.root()).sql().statement();
        assertTrue(statement.contains("IN (SELECT") && statement.contains("VALUES ('A', 'ACTIVE'), ('X', 'ACTIVE'),"
                + " ('C', 'CLOSED')"), statement);
        assertTrue(!statement.contains("THEN 'ACTIVE'"), "the column is read stored, never decoded: " + statement);
    }

    @Test
    void anOptionalParametersEqualityIsNullSafe() {
        ExecutionPlan.Sql sql = ((ExecutionPlan.TextResult) plan(Databases.PLATFORM,
                PlanCases.optionals("T").get(0).withParameter(), TypedQuery.Output.JSON).root()).sql();
        assertTrue(sql.statement().contains("IS NOT DISTINCT FROM ?"), sql.statement());
    }

    @Test
    void aScalarParametersPlanAnswersAsTheQueryWithItsValue_everyOutput() throws Exception {
        for (DatabaseType type : IN_PROCESS) {
            for (Parameterised q : SCALARS) {
                for (TypedQuery.Output output : TypedQuery.Output.values()) {
                    assertAnswersAsToday(type, q.withParameters(), q.withLets(), q.values(), output);
                }
            }
        }
    }

    /** A DateTime parameter is cast to the type of its value's literal on every database (each one's spelling is its
     *  own test's, {@code PlanMaker<Db>Test}): a type hole, absent a date-time, a null a TIMESTAMP. */
    @Test
    void aDateTimeParameterIsCastToATypeHoleOnEveryDatabase() {
        for (DatabaseType type : IN_PROCESS) {
            ExecutionPlan.Binding.One one = dateTimeBinding(type);
            ExecutionPlan.TypeHole hole = java.util.Objects.requireNonNull(one.hole(), type + ": a type hole");
            assertEquals(java.util.Set.of(ExecutionPlan.ValueKind.DATE_TIME, ExecutionPlan.ValueKind.DATE_TIME_NANOS),
                    hole.types().keySet(), type.name());
            assertEquals(ExecutionPlan.ValueKind.DATE_TIME, hole.absent(), type.name());
            assertEquals("TIMESTAMP", one.nullType(), type.name());
        }
    }

    /** The binding of a DateTime parameter, written as a column's value, on {@code type}. */
    static ExecutionPlan.Binding.One dateTimeBinding(DatabaseType type) {
        ExecutionPlan.Sql sql = ((ExecutionPlan.TextResult) plan(type, "{t: DateTime[1]|#>{s::DB.T}#->extend(~t: r|$t)"
                + "->select(~[ID, t])}", TypedQuery.Output.JSON).root()).sql();
        return (ExecutionPlan.Binding.One) sql.slots().get(0).binding();
    }

    /** What a plan does not bind yet is refused when the plan is made, by name (PARK-21): a class instance, an
     *  optional enumeration (its absence's answer unmeasured against legend-engine). */
    @Test
    void anUnboundParameterKindIsRefusedByName() {
        var aClass = assertThrows(com.legend.error.NotImplementedException.class, () -> plan(Databases.PLATFORM,
                "{i: s::Item[1]|#>{s::DB.T}#->filter(r|$r.ID == $i.id)}", TypedQuery.Output.JSON));
        assertTrue(aClass.getMessage().contains("a class instance is not bound") && aClass.getMessage().contains("PARK-21"),
                aClass.getMessage());
        var optionalEnum = assertThrows(com.legend.error.NotImplementedException.class, () -> Compiler.query(
                Compiler.compileModel(enumModel(Databases.PLATFORM)), "{st: s::Status[0..1]|s::Acct.all()"
                        + "->filter(a|$a.status == $st)->project(~[id: a|$a.id])}").executionPlan("s::RT", TypedQuery.Output.JSON));
        assertTrue(optionalEnum.getMessage().contains("an optional enumeration's absence")
                && optionalEnum.getMessage().contains("PARK-21"), optionalEnum.getMessage());
    }

    /** A list parameter is bound only as a whole list (in, contains); written where one value goes, it is refused by
     *  name, never bound as something it is not. */
    @Test
    void aListParameterWrittenWhereOneValueGoesIsRefusedByName() {
        var refused = assertThrows(com.legend.sql.dialect.DialectCapability.class, () -> plan(Databases.PLATFORM,
                "{ns: Integer[*]|#>{s::DB.T}#->extend(~n: r|$ns->size())->select(~[ID, n])}", TypedQuery.Output.JSON));
        assertTrue(refused.getMessage().contains("is bound only as a whole list"), refused.getMessage());
    }

    /** A value's text — a scalar's, a collection's — is the one-column relation {@code value}, whole in every output
     *  (no row to stream), as today's paths write it. */
    @Test
    void aValuesPlanAnswersAsTodaysPaths_everyOutput() throws Exception {
        for (DatabaseType type : IN_PROCESS) {
            for (String query : List.of("|#>{s::DB.T}#->select(~[ID])->size()",
                    "|s::Item.all()->map(i|$i.id)->sort()")) {
                for (TypedQuery.Output output : TypedQuery.Output.values()) {
                    assertAnswersAsToday(type, query, output);
                }
            }
        }
    }

    // ---------------------------------------------------------------------------------------------------------------

    static ExecutionPlan plan(DatabaseType type, String query, TypedQuery.Output output) {
        return Compiler.query(Compiler.compileModel(model(type.name())), query).executionPlan("s::RT", output);
    }

    static ExecutionPlan.Target target(ExecutionPlan plan) {
        return ((ExecutionPlan.TextResult) plan.root()).sql().target();
    }

    private static void assertAnswersAsToday(DatabaseType type, String query, TypedQuery.Output output)
            throws Exception {
        assertAnswersAsToday(type, query, query, java.util.Map.of(), output);
    }

    /** {@code withParameters}'s plan, run with {@code values}, answers as today's path answers {@code query}. */
    private static void assertAnswersAsToday(DatabaseType type, String withParameters, String query,
            java.util.Map<String, Object> values, TypedQuery.Output output) throws Exception {
        assertAnswersAsToday(model(type.name()), type, withParameters, query, values, output);
    }

    /** {@link #assertAnswersAsToday(DatabaseType, String, String, java.util.Map, TypedQuery.Output)} over
     *  {@code model}, whose runtime is {@code s::RT}. */
    private static void assertAnswersAsToday(String model, DatabaseType type, String withParameters, String query,
            java.util.Map<String, Object> values, TypedQuery.Output output) throws Exception {
        String key = TestBackends.of(type).name() + "\t" + output + "\t" + query;
        String known = DIFFERENCES.get(key);
        CONSULTED.add(key);
        String what = type + " " + output + " " + withParameters;
        String[] answers;
        try {
            answers = answers(model, type, withParameters, query, values, output);
        } catch (RuntimeException refused) {
            if (known == null) {
                throw refused;
            }
            assertEquals(known, escaped("refused: " + refused.getMessage()), key + ": the plan's refusal moved");
            return;
        }
        if (known == null) {
            assertEquals(answers[0], answers[1], what);
            return;
        }
        assertNotEquals(answers[0], answers[1], key + ": answers as today now; remove its row from " + DIFFERENCES_FILE);
        assertEquals(known, escaped(answers[1]), key + ": the plan's answer moved");
    }

    /** {@code s} on one register line: its backslashes, tabs, returns and new lines escaped. */
    private static String escaped(String s) {
        return s.replace("\\", "\\\\").replace("\t", "\\t").replace("\r", "\\r").replace("\n", "\\n");
    }

    /** Today's path's answer to {@code query} and the plan's to {@code withParameters} with {@code values}, each on a
     *  fresh database of {@code type}; a plan refused when it is made throws. */
    private static String[] answers(String model, DatabaseType type, String withParameters, String query,
            java.util.Map<String, Object> values, TypedQuery.Output output) throws Exception {
        ExecutionPlan plan = Compiler.query(Compiler.compileModel(model), withParameters).executionPlan("s::RT", output);
        try (Connection today = fresh(type); Connection planned = fresh(type)) {
            var ctx = Compiler.compileModel(model);
            var dialect = Databases.dialect(type);
            com.legend.exec.SetupRunner.run(com.legend.setup.CsvSeed.declaredSteps("s::RT", ctx, dialect), today,
                    dialect, null);
            StringWriter out = new StringWriter();
            switch (output) {
                case CSV -> Execution.executeWire(model, query, "s::RT", today, WireRender.Format.CSV, out);
                case JSON -> Execution.executeWire(model, query, "s::RT", today, WireRender.Format.JSON, out);
                case STREAMED_JSON -> Execution.executeStreaming(model, query, "s::RT", today, out);
            }
            return new String[] {out.toString(), PlanCases.run(plan, planned, values)};
        }
    }

    private static Connection fresh(DatabaseType type) throws SQLException {
        return TestBackends.of(type).open();
    }
}
