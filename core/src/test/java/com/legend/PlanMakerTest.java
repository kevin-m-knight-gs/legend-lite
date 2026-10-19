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
import org.junit.jupiter.api.Test;

import java.io.StringWriter;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The planner's plans (docs/EXECUTION_PLAN_BOUNDARY_2026_10_05.md §9, step 2's landing 2): a plan answers exactly as
 * today's wire and streaming paths answer the same query. Each query runs twice, on two fresh databases: through
 * {@link Execution} (its connection's declared test data established first, as the server establishes it), and through
 * its plan — the target's session statements, its setup steps, its statement — run here as each step describes itself
 * (the runner is step 3; the cases and the run are {@link PlanCases}'). The texts must be equal, byte for byte.
 * Postgres's case is {@code PostgresArmTest}'s (it needs the embedded Postgres).
 */
class PlanMakerTest {

    private static final AtomicInteger H2_NAMES = new AtomicInteger();

    /** A table of three rows, declared as the connection's test data; {@code type} is the connection's database. */
    private static String model(String type) {
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
        for (DatabaseType type : List.of(DatabaseType.DuckDB, DatabaseType.H2)) {
            for (String query : List.of(RELATION, NONE, PROJECT)) {
                for (TypedQuery.Output output : TypedQuery.Output.values()) {
                    assertAnswersAsToday(type, query, output);
                }
            }
        }
    }

    @Test
    void aGraphFetchsPlanAnswersAsTodaysPaths_inJson() throws Exception {
        for (DatabaseType type : List.of(DatabaseType.DuckDB, DatabaseType.H2)) {
            assertAnswersAsToday(type, GRAPH, TypedQuery.Output.JSON);
            assertAnswersAsToday(type, GRAPH, TypedQuery.Output.STREAMED_JSON);
            var refused = assertThrows(com.legend.error.NotImplementedException.class,
                    () -> plan(type, GRAPH, TypedQuery.Output.CSV));
            assertTrue(refused.getMessage().contains("graph results have no CSV wire"), refused.getMessage());
        }
    }

    /** A double that is no number prints as Pure prints it ({@code NaN}; {@code Infinity} and {@code -Infinity}
     *  the same way), whatever the database writes for it: the channel A shadow run of 2026-10-18 found
     *  {@code acos(2.0)} printed {@code nan.0} on DuckDB (the plan path program's step 7). */
    @Test
    void aDoubleThatIsNoNumberPrintsAsPurePrintsIt() throws Exception {
        for (DatabaseType type : List.of(DatabaseType.DuckDB, DatabaseType.H2)) {
            try (Connection c = fresh(type)) {
                assertEquals("value:Float[1]\nNaN",
                        PlanCases.run(plan(type, "|2.0->acos()", TypedQuery.Output.TDS_TEXT), c), type.name());
            }
        }
    }

    @Test
    void theTargetIsWhole_itsDatabaseVersionsSessionAndSetup() {
        ExecutionPlan.Target duck = target(plan(DatabaseType.DuckDB, RELATION, TypedQuery.Output.JSON));
        assertEquals("s::Conn", ((ExecutionPlan.Database.Declared) duck.database()).connection().qualifiedName());
        assertEquals(new ExecutionPlan.Servers.Every(), duck.servers());
        assertEquals(List.of("SET TimeZone='UTC'"), duck.session());
        // DuckDB's rows go to its bulk loader: the create, the rows, the copy and the drop
        ExecutionPlan.SetupStep.Rows rows = (ExecutionPlan.SetupStep.Rows) duck.setup().get(duck.setup().size() - 1);
        assertEquals(List.of(List.of("1", "a", "1.50"), java.util.Arrays.asList("2", "O'Brien", null),
                java.util.Arrays.asList("3", null, "3.25")), rows.rows());

        ExecutionPlan.Target h2 = target(plan(DatabaseType.H2, RELATION, TypedQuery.Output.JSON));
        assertEquals(new ExecutionPlan.Servers.Versions(List.of("2.1", "2.2")), h2.servers());
        assertEquals(List.of(), h2.session());
        // H2's rows are one INSERT
        assertInstanceOf(ExecutionPlan.SetupStep.Statement.class, h2.setup().get(h2.setup().size() - 1));
        assertTrue(h2.setup().stream().noneMatch(s -> s instanceof ExecutionPlan.SetupStep.Rows), h2.setup().toString());
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
        try (Connection today = DriverManager.getConnection("jdbc:duckdb:");
             Connection planned = DriverManager.getConnection("jdbc:duckdb:")) {
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
        for (DatabaseType type : List.of(DatabaseType.DuckDB, DatabaseType.H2)) {
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
        for (DatabaseType type : List.of(DatabaseType.DuckDB, DatabaseType.H2)) {
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
        for (DatabaseType type : List.of(DatabaseType.DuckDB, DatabaseType.H2)) {
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

    @Test
    void aListIsOneArray_andAListOfDecimalsIsRefusedByName() {
        ExecutionPlan.Sql sql = ((ExecutionPlan.TextResult) plan(DatabaseType.H2, PlanCases.lists("T").get(0).withParameters(),
                TypedQuery.Output.JSON).root()).sql();
        assertTrue(sql.statement().contains("= ANY(?)"), sql.statement());
        assertEquals(List.of(new ExecutionPlan.Slot("ns", new ExecutionPlan.Binding.Array("BIGINT"))), sql.slots());
        var refused = assertThrows(com.legend.error.NotImplementedException.class, () -> plan(DatabaseType.DuckDB,
                "{ps: Decimal[*]|#>{s::DB.T}#->filter(r|$r.ID->in($ps))}", TypedQuery.Output.JSON));
        assertTrue(refused.getMessage().contains("PARK-20"), refused.getMessage());
    }

    /** Compared with a mapped column, an enumeration's name is translated by a value table at that place, so the
     *  comparison reads the stored column and keeps its index (§9, measured: probes/enum-index-results.txt). */
    @Test
    void anEnumerationParameterIsComparedThroughAValueTable() {
        ExecutionPlan plan = Compiler.query(Compiler.compileModel(enumModel(DatabaseType.DuckDB)),
                PlanCases.enumerations().get(0).withParameters()).executionPlan("s::RT", TypedQuery.Output.JSON);
        String statement = ((ExecutionPlan.TextResult) plan.root()).sql().statement();
        assertTrue(statement.contains("IN (SELECT") && statement.contains("VALUES ('A', 'ACTIVE'), ('X', 'ACTIVE'),"
                + " ('C', 'CLOSED')"), statement);
        assertTrue(!statement.contains("THEN 'ACTIVE'"), "the column is read stored, never decoded: " + statement);
    }

    @Test
    void anOptionalParametersEqualityIsNullSafe() {
        ExecutionPlan.Sql sql = ((ExecutionPlan.TextResult) plan(DatabaseType.DuckDB,
                PlanCases.optionals("T").get(0).withParameter(), TypedQuery.Output.JSON).root()).sql();
        assertTrue(sql.statement().contains("IS NOT DISTINCT FROM ?"), sql.statement());
    }

    /** Trees planted by city and year, for a pivot whose columns the database names itself. */
    private static final String TREES = "#TDS\n city, year, treePlanted\n NYC, 2011, 5\n NYC, 2000, 15\n SAN, 2011, 2\n"
            + " SAN, 2000, 1\n SAN, 2000, 2\n#";

    /**
     * A DYNAMIC pivot's text is built by the database alone (the plan path program's step 4,
     * docs/PLAN_PATH_PROGRAM_2026_10_17.md §3, 2026-10-18): the generated columns named from the pivot's keys, the
     * cells read from each row's own JSON by those names, the types the template's -- the same TDS text the legacy
     * route builds with its metadata probe; the CSV and JSON wires built the same way. No probe step, no hole for the
     * runner. Every carrier names a generated column as the engine labels it, with the quotes as part of the name
     * (the pivot homework, docs/PIVOT_UPSTREAM_HOMEWORK_2026_10_18.md: legend-engine's DuckDB SQL spells
     * {@code concat('''', year, '''')}, its interpreter {@code "'" + ... + "'"}, and the PCT's expected text and casts
     * carry them; until 2026-10-18 the two wires printed the bare physical name, the TDS text alone the engine's).
     */
    @Test
    void aDynamicPivotsTextIsBuiltByTheDatabase_asTheLegacyProbeBuildsIt() throws Exception {
        String query = TREES + "->pivot(~[year], ~['newCol' : x | $x.treePlanted : y | $y->plus()])"
                + "->sort(~city->ascending())";
        String model = model(DatabaseType.DuckDB.name());
        assertAnswersAsToday(model, DatabaseType.DuckDB, query, query, java.util.Map.of(), TypedQuery.Output.TDS_TEXT);
        ExecutionPlan plan = Compiler.query(Compiler.compileModel(model), query).executionPlan("s::RT", TypedQuery.Output.TDS_TEXT);
        com.legend.testcases.PlanRoundTrip.assertRoundTrips(plan, query);
        try (Connection c = fresh(DatabaseType.DuckDB)) {
            String text = PlanCases.run(plan, c);
            assertTrue(text.endsWith("\nNYC,15,5\nSAN,3,2"), text);
            assertTrue(text.startsWith("city:String[") && text.contains("'\\'2000__|__newCol\\'':Integer[1],'\\'2011__|__newCol\\'':Integer[1]"), text);
            ExecutionPlan csv = Compiler.query(Compiler.compileModel(model), query).executionPlan("s::RT", TypedQuery.Output.CSV);
            assertEquals("city,'2000__|__newCol','2011__|__newCol'\r\nNYC,15,5\r\nSAN,3,2\r\n", PlanCases.run(csv, c));
            ExecutionPlan json = Compiler.query(Compiler.compileModel(model), query).executionPlan("s::RT", TypedQuery.Output.JSON);
            assertEquals("[{\"city\":\"NYC\",\"'2000__|__newCol'\":15,\"'2011__|__newCol'\":5},"
                    + "{\"city\":\"SAN\",\"'2000__|__newCol'\":3,\"'2011__|__newCol'\":2}]", PlanCases.run(json, c));
            // a STATIC pivot (the engine's form: one column, its values, one aggregate) has its columns from the typer,
            // named bare; both wires present them the same way
            String statics = TREES + "->pivot(~year, [2000, 2011], ~newCol : x | $x.treePlanted : y | $y->plus())"
                    + "->sort(~city->ascending())";
            assertEquals("city,'2000__|__newCol','2011__|__newCol'\r\nNYC,15,5\r\nSAN,3,2\r\n", PlanCases.run(
                    Compiler.query(Compiler.compileModel(model), statics).executionPlan("s::RT", TypedQuery.Output.CSV), c));
            assertEquals("[{\"city\":\"NYC\",\"'2000__|__newCol'\":15,\"'2011__|__newCol'\":5},"
                    + "{\"city\":\"SAN\",\"'2000__|__newCol'\":3,\"'2011__|__newCol'\":2}]", PlanCases.run(
                    Compiler.query(Compiler.compileModel(model), statics).executionPlan("s::RT", TypedQuery.Output.JSON), c));
        }
    }

    /** The PCT's trees by city, country and year, with a coefficient (testPivot_SingleMultiple_Dynamic_Aggregation). */
    private static final String TREES_PCT = "#TDS\n city, country, year, treePlanted, coefficient\n NYC, USA, 2011, 5000, 1\n"
            + " NYC, USA, 2000, 5000, 2\n SAN, USA, 2000, 2000, 1\n SAN, USA, 2011, 100, 2\n LDN, UK, 2011, 3000, 2\n"
            + " SAN, USA, 2011, 2500, 1\n NYC, USA, 2000, 10000, 2\n NYC, USA, 2012, 7600, 1\n NYC, USA, 2012, 7600, 2\n#";

    /** The same trees without the coefficient (the PCT's cast cases): a column no aggregate reads stays a group key. */
    private static final String TREES_4 = "#TDS\n city, country, year, treePlanted\n NYC, USA, 2011, 5000\n NYC, USA, 2000, 5000\n"
            + " SAN, USA, 2000, 2000\n SAN, USA, 2011, 100\n LDN, UK, 2011, 3000\n SAN, USA, 2011, 2500\n"
            + " NYC, USA, 2000, 10000\n NYC, USA, 2012, 7600\n NYC, USA, 2012, 7600\n#";

    private static final String CAST_PIVOT = "->cast(@meta::pure::metamodel::relation::Relation<(city:String[0..1],"
            + " country:String[0..1], '\\'2000__|__newCol\\'':Integer[0..1], '\\'2011__|__newCol\\'':Integer[0..1],"
            + " '\\'2012__|__newCol\\'':Integer[0..1])>)";

    /**
     * Two holes the channel A shadow run found (2026-10-18; the plan path program's step 7): an aggregate over an
     * EXPRESSION removes every column it reads from the pivot's static columns (the engine's expected rows of
     * testPivot_SingleMultiple_Dynamic_Aggregation), and a cast naming a dynamic pivot's generated columns projects
     * them, so a filter, a sort or a groupBy over the cast answers through the plan as an ordinary relation.
     */
    @Test
    void aPivotOverAnExpressionAndACastOverADynamicPivotAnswerAsTheEngine() throws Exception {
        String model = model(DatabaseType.DuckDB.name());
        var ctx = Compiler.compileModel(model);
        try (Connection c = fresh(DatabaseType.DuckDB)) {
            String expression = TREES_PCT + "->pivot(~[year], ~[sum: x | $x.treePlanted->toOne() * $x.coefficient->toOne()"
                    + " : y | $y->plus(), count: x | 1 : y | $y->plus()])->sort(~city->ascending())";
            String text = PlanCases.run(Compiler.query(ctx, expression).executionPlan("s::RT", TypedQuery.Output.TDS_TEXT), c);
            assertTrue(text.startsWith("city:String[1],country:String[1],'\\'2000__|__sum\\'':Integer[1],"
                    + "'\\'2000__|__count\\'':Integer[1],'\\'2011__|__sum\\'':Integer[1],'\\'2011__|__count\\'':Integer[1],"
                    + "'\\'2012__|__sum\\'':Integer[1],'\\'2012__|__count\\'':Integer[1]\n"), text);
            assertTrue(text.endsWith("\nLDN,UK,null,null,6000,1,null,null\nNYC,USA,30000,2,5000,1,22800,2"
                    + "\nSAN,USA,2000,1,2700,2,null,null"), text);

            String pivot = TREES_4 + "->pivot(~[year], ~[newCol: x | $x.treePlanted : y | $y->plus()])" + CAST_PIVOT;
            String filtered = PlanCases.run(Compiler.query(ctx, pivot + "->filter(x | $x.city == 'NYC')")
                    .executionPlan("s::RT", TypedQuery.Output.TDS_TEXT), c);
            assertEquals("city:String[0..1],country:String[0..1],'\\'2000__|__newCol\\'':Integer[0..1],"
                    + "'\\'2011__|__newCol\\'':Integer[0..1],'\\'2012__|__newCol\\'':Integer[0..1]\nNYC,USA,15000,5000,15200",
                    filtered);
            String sorted = PlanCases.run(Compiler.query(ctx, pivot + "->sort(~city->ascending())")
                    .executionPlan("s::RT", TypedQuery.Output.TDS_TEXT), c);
            assertTrue(sorted.endsWith("\nLDN,UK,null,3000,null\nNYC,USA,15000,5000,15200\nSAN,USA,2000,2600,null"), sorted);
            String grouped = PlanCases.run(Compiler.query(ctx, pivot + "->groupBy(~[country], ~['\\'2011__|__newCol\\'': x |"
                    + " $x.'\\'2011__|__newCol\\'' : y | $y->plus()])->sort(~country->ascending())")
                    .executionPlan("s::RT", TypedQuery.Output.TDS_TEXT), c);
            assertEquals("country:String[0..1],'\\'2011__|__newCol\\'':Integer[1]\nUK,3000\nUSA,7600", grouped);
            ExecutionPlan csv = Compiler.query(ctx, pivot + "->filter(x | $x.city == 'NYC')").executionPlan("s::RT", TypedQuery.Output.CSV);
            assertEquals("city,country,'2000__|__newCol','2011__|__newCol','2012__|__newCol'\r\nNYC,USA,15000,5000,15200\r\n",
                    PlanCases.run(csv, c));
        }
    }

    /**
     * A cast over a DYNAMIC pivot on a database without a dynamic PIVOT (the plan path program's step 9, 2026-10-19:
     * the PCT's four composition cases, {@code test_Pivot_Filter} among them, refused on every lane but DuckDB with
     * "pivot reached a dialect without a PIVOT strategy"): the keys the cast names are the pivot's NAMED values
     * ({@code SqlSource.Pivot.named}), each the ON key's typed literal, which the dialect's static emulation pivots
     * on (H2's grouped CASE aggregation) with no row pinned -- the group whose key the cast does not name keeps its
     * row, its named cells null, as DuckDB's dynamic form answers through the same cast. DuckDB itself keeps the
     * dynamic form (engine-exact); the plan round-trips with the named values (format 7).
     */
    @Test
    void aCastOverADynamicPivotAnswersOnADatabaseWithoutADynamicPivot() throws Exception {
        String cast = "->cast(@meta::pure::metamodel::relation::Relation<(city:String[0..1], country:String[0..1],"
                + " '\\'2011__|__newCol\\'':Integer[0..1], '\\'2012__|__newCol\\'':Integer[0..1])>)";
        String query = TREES_4 + "->pivot(~[year], ~[newCol: x | $x.treePlanted : y | $y->plus()])" + cast
                + "->sort(~city->ascending())";
        String expected;
        try (Connection c = fresh(DatabaseType.DuckDB)) {
            ExecutionPlan duck = Compiler.query(Compiler.compileModel(model(DatabaseType.DuckDB.name())), query)
                    .executionPlan("s::RT", TypedQuery.Output.TDS_TEXT);
            assertTrue(((ExecutionPlan.TextResult) duck.root()).sql().statement().contains("PIVOT"), "DuckDB's dynamic form");
            expected = PlanCases.run(duck, c);
        }
        // the 2000 group (SAN's 2000 trees, NYC's 15000) keeps its row with its named cells null
        assertEquals("city:String[0..1],country:String[0..1],'\\'2011__|__newCol\\'':Integer[0..1],"
                + "'\\'2012__|__newCol\\'':Integer[0..1]\nLDN,UK,3000,null\nNYC,USA,5000,15200\nSAN,USA,2600,null", expected);
        ExecutionPlan plan = Compiler.query(Compiler.compileModel(model(DatabaseType.H2.name())), query)
                .executionPlan("s::RT", TypedQuery.Output.TDS_TEXT);
        com.legend.testcases.PlanRoundTrip.assertRoundTrips(plan, query);
        String h2 = ((ExecutionPlan.TextResult) plan.root()).sql().statement();
        assertFalse(h2.contains("PIVOT"), h2);
        assertTrue(h2.contains("CASE WHEN") && h2.contains("= 2011") && h2.contains("= 2012"), h2);
        try (Connection c = fresh(DatabaseType.H2)) {
            assertEquals(expected, PlanCases.run(plan, c));
        }
    }

    @Test
    void aScalarParametersPlanAnswersAsTheQueryWithItsValue_everyOutput() throws Exception {
        for (DatabaseType type : List.of(DatabaseType.DuckDB, DatabaseType.H2)) {
            for (Parameterised q : SCALARS) {
                for (TypedQuery.Output output : TypedQuery.Output.values()) {
                    assertAnswersAsToday(type, q.withParameters(), q.withLets(), q.values(), output);
                }
            }
        }
    }

    /** H2 types a parameter when it prepares the statement, and a Float's, a Decimal's, a Date's or a Number's type is
     *  its value's: H2's plan casts it to a TYPE HOLE the runner fills with the type H2 gives that value's literal
     *  (probes/value-typed-cast-results.txt); DuckDB's binds it bare, as the database types the value. Every scalar case
     *  above answers through it on H2. */
    @Test
    void aParameterTypedByItsValueIsCastToATypeHoleOnH2() {
        ExecutionPlan.Sql h2 = ((ExecutionPlan.TextResult) plan(DatabaseType.H2, "{f: Float[1]|#>{s::DB.T}#"
                + "->extend(~x: r|$r.ID * $f)->select(~[ID, x])}", TypedQuery.Output.JSON).root()).sql();
        assertEquals(1, h2.slots().size());
        ExecutionPlan.Binding.One one = (ExecutionPlan.Binding.One) h2.slots().get(0).binding();
        ExecutionPlan.TypeHole hole = java.util.Objects.requireNonNull(one.hole(), "a type hole");
        assertEquals("CAST(? AS )", h2.statement().substring(hole.at() - "CAST(? AS ".length(), hole.at() + 1),
                h2.statement());
        assertEquals(java.util.Map.of(
                ExecutionPlan.ValueKind.DECIMAL, new ExecutionPlan.TypeSpelling("NUMERIC",
                        ExecutionPlan.Digits.PRECISION_AND_SCALE),
                ExecutionPlan.ValueKind.FLOATING, new ExecutionPlan.TypeSpelling("DECFLOAT",
                        ExecutionPlan.Digits.PRECISION)), hole.types());
        assertEquals(ExecutionPlan.ValueKind.DECIMAL, hole.absent());
        assertEquals("DECIMAL", one.nullType());
        ExecutionPlan.Sql duck = ((ExecutionPlan.TextResult) plan(DatabaseType.DuckDB, "{f: Float[1]|#>{s::DB.T}#"
                + "->extend(~x: r|$r.ID * $f)->select(~[ID, x])}", TypedQuery.Output.JSON).root()).sql();
        assertEquals(List.of(new ExecutionPlan.Slot("f", new ExecutionPlan.Binding.One("DECIMAL", null))),
                duck.slots());
    }

    /** A DateTime parameter is cast to the type of its value's literal on every database, and passed as its text: H2's
     *  keeps nanoseconds (a plain TIMESTAMP keeps six digits and rounds the rest; probes/value-typed-cast-results.txt),
     *  DuckDB's is a TIMESTAMP_NS only when the value has digits finer than a microsecond, Postgres's keeps six and cuts
     *  the rest, as each literal writer does (probes/timestamp-results.txt). */
    @Test
    void aDateTimeParameterIsCastToItsLiteralsTypeOnEveryDatabase() {
        String query = "{t: DateTime[1]|#>{s::DB.T}#->extend(~t: r|$t)->select(~[ID, t])}";
        java.util.Map<DatabaseType, java.util.Map<ExecutionPlan.ValueKind, ExecutionPlan.TypeSpelling>> expected =
                java.util.Map.of(
                        DatabaseType.H2, java.util.Map.of(
                                ExecutionPlan.ValueKind.DATE_TIME, new ExecutionPlan.TypeSpelling("TIMESTAMP(9)",
                                        ExecutionPlan.Digits.NONE),
                                ExecutionPlan.ValueKind.DATE_TIME_NANOS, new ExecutionPlan.TypeSpelling("TIMESTAMP(9)",
                                        ExecutionPlan.Digits.NONE)),
                        DatabaseType.DuckDB, java.util.Map.of(
                                ExecutionPlan.ValueKind.DATE_TIME, new ExecutionPlan.TypeSpelling("TIMESTAMP",
                                        ExecutionPlan.Digits.NONE),
                                ExecutionPlan.ValueKind.DATE_TIME_NANOS, new ExecutionPlan.TypeSpelling("TIMESTAMP_NS",
                                        ExecutionPlan.Digits.NONE)),
                        DatabaseType.Postgres, java.util.Map.of(
                                ExecutionPlan.ValueKind.DATE_TIME, new ExecutionPlan.TypeSpelling("TIMESTAMP",
                                        ExecutionPlan.Digits.NONE),
                                ExecutionPlan.ValueKind.DATE_TIME_NANOS, new ExecutionPlan.TypeSpelling("TIMESTAMP",
                                        ExecutionPlan.Digits.NONE, 6)));
        expected.forEach((type, types) -> {
            ExecutionPlan.Sql sql = ((ExecutionPlan.TextResult) plan(type, query, TypedQuery.Output.JSON).root()).sql();
            ExecutionPlan.Binding.One one = (ExecutionPlan.Binding.One) sql.slots().get(0).binding();
            ExecutionPlan.TypeHole hole = java.util.Objects.requireNonNull(one.hole(), type + ": a type hole");
            assertEquals(types, hole.types(), type.name());
            assertEquals(ExecutionPlan.ValueKind.DATE_TIME, hole.absent(), type.name());
            assertEquals("TIMESTAMP", one.nullType(), type.name());
        });
    }


    @Test
    void aScalarParameterIsDeclared_andBoundWhereItIsWritten() {
        ExecutionPlan plan = plan(DatabaseType.H2, "{n: Integer[1]|#>{s::DB.T}#->filter(r|$r.ID != $n)"
                + "->extend(~plus: r|$r.ID + $n)->select(~[ID, plus])}", TypedQuery.Output.JSON);
        assertEquals(List.of(new ExecutionPlan.Parameter("n", "Integer", new ExecutionPlan.Multiplicity(1, 1),
                List.of())), plan.parameters());
        ExecutionPlan.Sql sql = ((ExecutionPlan.TextResult) plan.root()).sql();
        ExecutionPlan.Slot n = new ExecutionPlan.Slot("n", new ExecutionPlan.Binding.One("BIGINT", null));
        assertEquals(List.of(n, n), sql.slots());
        assertEquals(2, sql.statement().chars().filter(ch -> ch == '?').count(), sql.statement());
    }

    /** What a plan does not bind yet is refused when the plan is made, by name (PARK-21): a class instance, an
     *  optional enumeration (its absence's answer unmeasured against legend-engine). */
    @Test
    void anUnboundParameterKindIsRefusedByName() {
        var aClass = assertThrows(com.legend.error.NotImplementedException.class, () -> plan(DatabaseType.DuckDB,
                "{i: s::Item[1]|#>{s::DB.T}#->filter(r|$r.ID == $i.id)}", TypedQuery.Output.JSON));
        assertTrue(aClass.getMessage().contains("a class instance is not bound") && aClass.getMessage().contains("PARK-21"),
                aClass.getMessage());
        var optionalEnum = assertThrows(com.legend.error.NotImplementedException.class, () -> Compiler.query(
                Compiler.compileModel(enumModel(DatabaseType.DuckDB)), "{st: s::Status[0..1]|s::Acct.all()"
                        + "->filter(a|$a.status == $st)->project(~[id: a|$a.id])}").executionPlan("s::RT", TypedQuery.Output.JSON));
        assertTrue(optionalEnum.getMessage().contains("an optional enumeration's absence")
                && optionalEnum.getMessage().contains("PARK-21"), optionalEnum.getMessage());
    }

    /** A list parameter is bound only as a whole list (in, contains); written where one value goes, it is refused by
     *  name, never bound as something it is not. */
    @Test
    void aListParameterWrittenWhereOneValueGoesIsRefusedByName() {
        var refused = assertThrows(com.legend.sql.dialect.DialectCapability.class, () -> plan(DatabaseType.DuckDB,
                "{ns: Integer[*]|#>{s::DB.T}#->extend(~n: r|$ns->size())->select(~[ID, n])}", TypedQuery.Output.JSON));
        assertTrue(refused.getMessage().contains("is bound only as a whole list"), refused.getMessage());
    }

    /** A value's text — a scalar's, a collection's — is the one-column relation {@code value}, whole in every output
     *  (no row to stream), as today's paths write it. */
    @Test
    void aValuesPlanAnswersAsTodaysPaths_everyOutput() throws Exception {
        for (DatabaseType type : List.of(DatabaseType.DuckDB, DatabaseType.H2)) {
            for (String query : List.of("|#>{s::DB.T}#->select(~[ID])->size()",
                    "|s::Item.all()->map(i|$i.id)->sort()")) {
                for (TypedQuery.Output output : TypedQuery.Output.values()) {
                    assertAnswersAsToday(type, query, output);
                }
            }
        }
    }

    // ---------------------------------------------------------------------------------------------------------------

    /** The plan path program's step 1a (docs/PLAN_PATH_PROGRAM_2026_10_17.md §3): every result column carries its
     *  Pure type and multiplicity, the SQL type of its slot, and its store's declared type by provenance -- or none,
     *  said so: a computed column has no declared type, a mapped property reads its column's. */
    @Test
    void aColumnCarriesItsThreeTypeFacts() {
        List<ExecutionPlan.Column> cols = columns(plan(DatabaseType.DuckDB, RELATION, TypedQuery.Output.TDS_TEXT));
        assertEquals(List.of("ID", "NAME", "PRICE"), cols.stream().map(ExecutionPlan.Column::name).toList(),
                cols.toString());
        assertEquals(List.of("INTEGER", "VARCHAR(20)", "DECIMAL(10,2)"),
                cols.stream().map(ExecutionPlan.Column::store).toList(), cols.toString());
        assertEquals("String", cols.get(1).type(), cols.toString());
        assertEquals("VARCHAR", cols.get(1).slot(), cols.toString());
        assertEquals("DECIMAL(10,2)", cols.get(2).slot(), cols.toString());
        List<ExecutionPlan.Column> computed = columns(plan(DatabaseType.DuckDB,
                "|#>{s::DB.T}#->extend(~NEXT: r|$r.ID + 1)->select(~[NAME, NEXT])", TypedQuery.Output.CSV));
        assertEquals("VARCHAR(20)", computed.get(0).store(), computed.toString());
        assertEquals(null, computed.get(1).store(), computed.toString());
        assertEquals("Integer", computed.get(1).type(), computed.toString());
        List<ExecutionPlan.Column> project = columns(plan(DatabaseType.DuckDB, PROJECT, TypedQuery.Output.CSV));
        assertEquals(List.of("VARCHAR(20)", "DECIMAL(10,2)"),
                project.stream().map(ExecutionPlan.Column::store).toList(), project.toString());
        List<ExecutionPlan.Column> value = columns(plan(DatabaseType.DuckDB, "|1 + 2", TypedQuery.Output.TDS_TEXT));
        assertEquals(1, value.size(), value.toString());
        assertEquals("value", value.get(0).name(), value.toString());
        assertEquals("Integer", value.get(0).type(), value.toString());
        assertEquals(null, value.get(0).store(), value.toString());
        assertEquals(new ExecutionPlan.Multiplicity(1, 1), value.get(0).multiplicity(), value.toString());
    }

    /** The plan path program's step 1b (docs/PLAN_PATH_PROGRAM_2026_10_17.md §2, §3): a plan written to its JSON and
     *  read back is the same plan, its typed SQL tree included, and the tree read back renders the same statement
     *  with the same bindings on the plan's own dialect. */
    @Test
    void aPlansTypedTreeReadsBackAndRendersTheSameStatement() {
        for (DatabaseType type : List.of(DatabaseType.DuckDB, DatabaseType.H2)) {
            for (String query : List.of(RELATION, NONE, PROJECT, "|1 + 2",
                    "|#>{s::DB.T}#->extend(~NEXT: r|$r.ID + 1)->select(~[NAME, NEXT])",
                    "|#>{s::DB.T}#->groupBy(~[NAME], ~[total: r|$r.PRICE : y|$y->sum()])->sort(~NAME->ascending())",
                    "|#>{s::DB.T}#->extend(over(~NAME->ascending()), ~rank: {p, w, r | $p->rank($w, $r)})",
                    "|#>{s::DB.T}#->select(~[ID])->concatenate(#>{s::DB.T}#->select(~[ID]))->sort(~ID->ascending())")) {
                for (TypedQuery.Output output : List.of(TypedQuery.Output.TDS_TEXT, TypedQuery.Output.CSV)) {
                    ExecutionPlan plan = plan(type, query, output);
                    com.legend.testcases.PlanRoundTrip.assertRoundTrips(plan, type + " " + output + " " + query);
                }
            }
            com.legend.testcases.PlanRoundTrip.assertRoundTrips(plan(type, GRAPH, TypedQuery.Output.JSON),
                    type + " JSON " + GRAPH);
        }
    }

    private static List<ExecutionPlan.Column> columns(ExecutionPlan plan) {
        return ((ExecutionPlan.Relation) ((ExecutionPlan.TextResult) plan.root()).type()).columns();
    }

    /** The PCT's {@code getAll::testBasic} shape: the system metamodel store read by a plan. */
    private static final String CLASSES = "|meta::pure::metamodel::type::Class.all()->isEmpty()";

    /**
     * The system metamodel store's tables, in the form the plan's database takes (the plan path program's step 9):
     * on an engine in this process, the target's setup, in their schema (2026-10-18); on a SERVER engine, the
     * session's own TEMPORARY tables (the legacy system database's form, the user's design of 2026-10-11), made by
     * the session's statements and read by the query under their session names, with no setup for the user's
     * database (2026-10-19: every server lane had pinned {@code testBasic} by "the plan has setup for it: never run
     * on it"); a server without temporary tables (Trino) refuses by name.
     */
    @Test
    void theMetamodelStoresTablesAreThePlansSetupInProcessAndTheSessionsOwnTablesOnAServer() throws Exception {
        ExecutionPlan duck = plan(DatabaseType.DuckDB, CLASSES, TypedQuery.Output.TDS_TEXT);
        ExecutionPlan.Target inProcess = target(duck);
        assertTrue(inProcess.setup().stream().anyMatch(s -> s instanceof ExecutionPlan.SetupStep.Statement st
                && st.sql().contains("classes")), inProcess.setup().toString());
        assertEquals(List.of("SET TimeZone='UTC'"), inProcess.session());
        assertFalse(((ExecutionPlan.TextResult) duck.root()).sql().statement().contains("metamodel_classes"));
        try (Connection c = fresh(DatabaseType.DuckDB)) {
            assertEquals("value:Boolean[1]\nfalse", PlanCases.run(duck, c));
        }

        ExecutionPlan pg = plan(DatabaseType.Postgres, CLASSES, TypedQuery.Output.TDS_TEXT);
        com.legend.testcases.PlanRoundTrip.assertRoundTrips(pg, CLASSES);
        ExecutionPlan.Target server = target(pg);
        assertTrue(server.setup().stream().noneMatch(s -> s instanceof ExecutionPlan.SetupStep.Statement st
                && st.sql().contains("classes")), server.setup().toString());
        List<String> session = server.session();
        // the dialect's own session statements first (Postgres: its time zone), then the session's tables
        int create = -1;
        for (int i = 0; i < session.size(); i++) {
            if (session.get(i).contains("metamodel_classes")) {
                create = i;
                break;
            }
        }
        assertTrue(create >= 0 && create + 1 < session.size(), session.toString());
        assertTrue(session.get(create).toLowerCase(java.util.Locale.ROOT).contains("temporary"), session.get(create));
        String insert = session.get(create + 1);
        assertTrue(insert.toUpperCase(java.util.Locale.ROOT).startsWith("INSERT INTO") && insert.contains("metamodel_classes"),
                insert.substring(0, Math.min(120, insert.length())));
        String sql = ((ExecutionPlan.TextResult) pg.root()).sql().statement();
        assertTrue(sql.contains("metamodel_classes") && !sql.contains("metamodel.classes"), sql);

        var refused = assertThrows(RuntimeException.class, () -> plan(DatabaseType.Trino, CLASSES, TypedQuery.Output.TDS_TEXT));
        assertTrue(refused.getMessage().contains("no temporary tables"), refused.getMessage());
    }

    private static ExecutionPlan plan(DatabaseType type, String query, TypedQuery.Output output) {
        return Compiler.query(Compiler.compileModel(model(type.name())), query).executionPlan("s::RT", output);
    }

    private static ExecutionPlan.Target target(ExecutionPlan plan) {
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
        String what = type + " " + output + " " + withParameters;
        ExecutionPlan plan = Compiler.query(Compiler.compileModel(model), withParameters).executionPlan("s::RT", output);
        try (Connection today = fresh(type); Connection planned = fresh(type)) {
            var ctx = Compiler.compileModel(model);
            var dialect = Databases.dialect(type);
            com.legend.exec.SetupRunner.run(com.legend.setup.CsvSeed.declaredSteps("s::RT", ctx, dialect), today,
                    dialect, null);
            if (output == TypedQuery.Output.TDS_TEXT) {
                PlanCases.assertTdsTextAnswersAsToday(model, query, "s::RT", today, plan, planned, values, what);
                return;
            }
            StringWriter out = new StringWriter();
            switch (output) {
                case CSV -> Execution.executeWire(model, query, "s::RT", today, WireRender.Format.CSV, out);
                case JSON -> Execution.executeWire(model, query, "s::RT", today, WireRender.Format.JSON, out);
                case STREAMED_JSON -> Execution.executeStreaming(model, query, "s::RT", today, out);
                case TDS_TEXT -> throw new IllegalStateException("judged above");
            }
            assertEquals(out.toString(), PlanCases.run(plan, planned, values), what);
        }
    }

    private static Connection fresh(DatabaseType type) throws SQLException {
        return switch (type) {
            case DuckDB -> DriverManager.getConnection("jdbc:duckdb:");
            case H2 -> DriverManager.getConnection("jdbc:h2:mem:planmaker" + H2_NAMES.incrementAndGet()
                    + com.legend.exec.H2Settings.SETTINGS);
            default -> throw new IllegalArgumentException(type.name());
        };
    }
}
