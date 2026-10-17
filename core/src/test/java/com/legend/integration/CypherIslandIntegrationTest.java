// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend.integration;

import com.legend.error.LegendCompileException;
import com.legend.exec.ExecutionResult;
import com.legend.server.QueryService;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code #Cypher{...}#} end to end on DuckDB (docs/CYPHER_STUDY_2026_10_15.md §5, docs/CYPHER_PLAN_2026_10_15.md
 * PR1): a Cypher query over a class model with an association and a class-typed property returns the rows Neo4j
 * returns for the same graph (the rows here are derived by hand from Cypher's semantics; the Neo4j-judged harness is
 * the plan's next landing), the island composes with Pure, and a refusal is the compile error its kind says, placed
 * in the source.
 */
class CypherIslandIntegrationTest {

    private static final String MODEL = """
            ###Pure
            Class test::Person { name: String[1]; age: Integer[0..1]; active: Boolean[0..1]; }
            Class test::Firm { legalName: String[1]; founded: Integer[0..1]; hq: test::City[0..1]; }
            Class test::City { name: String[1]; }
            Association test::PersonFirm { firm: test::Firm[0..1]; employees: test::Person[*]; }
            ###Relational
            Database store::DB
            (
                Table T_PERSON (ID INTEGER PRIMARY KEY, NAME VARCHAR(100), AGE INTEGER, ACTIVE BIT, FIRM_ID INTEGER)
                Table T_FIRM (ID INTEGER PRIMARY KEY, LEGAL_NAME VARCHAR(100), FOUNDED INTEGER, CITY_ID INTEGER)
                Table T_CITY (ID INTEGER PRIMARY KEY, NAME VARCHAR(100))
                Join PersonFirm(T_PERSON.FIRM_ID = T_FIRM.ID)
                Join FirmCity(T_FIRM.CITY_ID = T_CITY.ID)
            )
            ###Mapping
            Mapping test::M
            (
                test::Person: Relational
                {
                    ~mainTable [store::DB] T_PERSON
                    name: [store::DB] T_PERSON.NAME,
                    age: [store::DB] T_PERSON.AGE,
                    active: [store::DB] T_PERSON.ACTIVE
                }
                test::Firm: Relational
                {
                    ~mainTable [store::DB] T_FIRM
                    legalName: [store::DB] T_FIRM.LEGAL_NAME,
                    founded: [store::DB] T_FIRM.FOUNDED,
                    hq: [store::DB] @FirmCity
                }
                test::City: Relational
                {
                    ~mainTable [store::DB] T_CITY
                    name: [store::DB] T_CITY.NAME
                }
                test::PersonFirm: Relational { AssociationMapping (
                    employees: [store::DB]@PersonFirm,
                    firm: [store::DB]@PersonFirm
                ) }
            )
            ###Connection
            RelationalDatabaseConnection store::Conn { type: DuckDB; specification: DuckDB { }; auth: Test; }
            ###Runtime
            Runtime test::RT { mappings: [ test::M ]; connections: [ store::DB: [ environment: store::Conn ] ]; }
            """;

    private static Connection duckdb;

    @BeforeAll
    static void seed() throws SQLException {
        duckdb = DriverManager.getConnection("jdbc:duckdb:");
        try (Statement s = duckdb.createStatement()) {
            s.execute("CREATE TABLE T_PERSON (ID INTEGER PRIMARY KEY, NAME VARCHAR(100), AGE INTEGER, ACTIVE BOOLEAN,"
                    + " FIRM_ID INTEGER)");
            s.execute("CREATE TABLE T_FIRM (ID INTEGER PRIMARY KEY, LEGAL_NAME VARCHAR(100), FOUNDED INTEGER,"
                    + " CITY_ID INTEGER)");
            s.execute("CREATE TABLE T_CITY (ID INTEGER PRIMARY KEY, NAME VARCHAR(100))");
            s.execute("INSERT INTO T_CITY VALUES (1, 'NYC'), (2, 'LA')");
            s.execute("INSERT INTO T_FIRM VALUES (1, 'Acme', 1990, 1), (2, 'Globex', NULL, NULL)");
            s.execute("INSERT INTO T_PERSON VALUES (1, 'Alice', 30, true, 1), (2, 'Bob', 40, NULL, 1),"
                    + " (3, 'Charlie', 25, false, 2), (4, 'Diana', NULL, true, NULL)");
        }
    }

    @AfterAll
    static void close() throws SQLException {
        duckdb.close();
    }

    private static ExecutionResult run(String cypher) throws SQLException {
        return new QueryService().execute(MODEL, "#Cypher{" + cypher + "}#", "test::RT", duckdb);
    }

    /** The model with a graph (the study's §12.45): a DataSpace of Person and Firm, open. */
    private static final String GRAPH_MODEL = MODEL + """
            ###Pure
            Profile graph::graph
            {
               stereotypes: [Edge, Open, from, to, type];
            }
            ###DataSpace
            DataSpace <<graph::graph.Open>> test::G
            {
              executionContexts:
              [
                {
                  name: 'default';
                  mapping: test::M;
                  defaultRuntime: test::RT;
                }
              ];
              defaultExecutionContext: 'default';
              elements: [ test::Person, test::Firm ];
            }
            """;

    private static ExecutionResult runOver(String model, String cypher) throws SQLException {
        return new QueryService().execute(model, "#Cypher{" + cypher + "}#", "test::RT", duckdb);
    }

    @Test
    void theGraphAUseNamesIsADataSpaceOpenByItsStereotype() throws SQLException {
        // the study's §12.45: USE names the graph, a DataSpace; its elements are the labels (City is outside), its
        // <<graph::graph.Open>> the open reading (a label no class has: no rows; a property no class declares: null)
        assertEquals(List.of(), rows(runOver(GRAPH_MODEL, "USE test::G MATCH (p:Nobody) RETURN p.name AS n")));
        List<String> people = rows(runOver(GRAPH_MODEL, "USE test::G MATCH (p:Person) RETURN p.nam AS x"));
        assertEquals(rows(run("MATCH (p:Person) RETURN p.name AS n")).size(), people.size());
        assertTrue(people.stream().allMatch("null"::equals), people.toString());
        long persons = Long.parseLong(rows(run("MATCH (p:Person) RETURN count(*) AS c")).get(0));
        long firms = Long.parseLong(rows(run("MATCH (f:Firm) RETURN count(*) AS c")).get(0));
        assertEquals(List.of(String.valueOf(persons + firms)),
                rows(runOver(GRAPH_MODEL, "USE test::G MATCH (n) RETURN count(*) AS c")));
        // without a USE the whole model is the graph, closed: C4 stays (aQueryNeo4jRejectsIsATypeError)
        LegendCompileException closed = assertThrows(LegendCompileException.class,
                () -> runOver(GRAPH_MODEL, "MATCH (p:Nobody) RETURN p.name"));
        assertTrue(closed.getMessage().contains("the label Nobody names no class"), closed.getMessage());
        // a USE naming no DataSpace, and a USE that is not the first clause, are errors at their place
        LegendCompileException none = assertThrows(LegendCompileException.class,
                () -> runOver(GRAPH_MODEL, "USE test::Nope MATCH (p:Person) RETURN p.name"));
        assertTrue(none.getMessage().contains("USE test::Nope: no DataSpace of the model"), none.getMessage());
        LegendCompileException late = assertThrows(LegendCompileException.class,
                () -> runOver(GRAPH_MODEL, "MATCH (p:Person) USE test::G RETURN p.name"));
        assertTrue(late.getMessage().contains("USE clause must be"), late.getMessage());
    }

    private static List<String> columns(ExecutionResult result) {
        return result.columns().stream().map(c -> c.name()).toList();
    }

    private static List<String> rows(ExecutionResult result) {
        List<String> rows = new ArrayList<>();
        for (var row : result.rows()) {
            List<String> cells = new ArrayList<>();
            for (Object value : row.values()) {
                cells.add(value == null ? "null" : value instanceof Number n && !(value instanceof Double)
                        ? String.valueOf(n.longValue()) : String.valueOf(value));
            }
            rows.add(String.join("|", cells));
        }
        return rows;
    }

    private static List<String> expect(String... rows) {
        return Arrays.asList(rows);
    }

    @Test
    void aMatchReturnsEachNodesProperty() throws SQLException {
        ExecutionResult r = run("MATCH (p:Person) RETURN p.name ORDER BY p.name");
        assertEquals(List.of("p.name"), columns(r));
        assertEquals(expect("Alice", "Bob", "Charlie", "Diana"), rows(r));
    }

    @Test
    void whereKeepsTheRowsWhereTheConditionIsTrueNullIsNotTrue() throws SQLException {
        assertEquals(expect("Alice", "Bob"), rows(run("MATCH (p:Person) WHERE p.age > 26 RETURN p.name AS name"
                + " ORDER BY name")));
        // NOT null is null: Bob (age known, active unknown) is not kept by NOT p.active
        assertEquals(expect("Charlie", "Diana"), rows(run("MATCH (p:Person) WHERE NOT p.active OR p.age IS NULL"
                + " RETURN p.name ORDER BY p.name")));
        assertEquals(expect("Alice", "Diana"), rows(run("MATCH (p:Person) WHERE p.active RETURN p.name"
                + " ORDER BY p.name")));
    }

    @Test
    void aRelationshipIsAnAssociationEndInEitherDirection() throws SQLException {
        ExecutionResult r = run("MATCH (p:Person)-[:firm]->(f:Firm) RETURN p.name, f.legalName ORDER BY p.name");
        assertEquals(List.of("p.name", "f.legalName"), columns(r));
        assertEquals(expect("Alice|Acme", "Bob|Acme", "Charlie|Globex"), rows(r));
        assertEquals(expect("Alice", "Bob"), rows(run("MATCH (f:Firm)-[:employees]->(p:Person)"
                + " WHERE f.legalName = 'Acme' RETURN p.name ORDER BY p.name")));
        assertEquals(expect("Acme", "Acme", "Globex"), rows(run("MATCH (f:Firm)<-[:firm]-(p:Person)"
                + " RETURN f.legalName ORDER BY f.legalName")));
    }

    @Test
    void aChainOfHopsNavigatesAClassTypedProperty() throws SQLException {
        assertEquals(expect("Alice|NYC", "Bob|NYC"), rows(run("MATCH (p:Person)-[:firm]->(f:Firm)-[:hq]->(c:City)"
                + " RETURN p.name, c.name ORDER BY p.name")));
        assertEquals(expect("Acme"), rows(run("MATCH (p:Person {name: 'Bob'})-[:firm]->(f) RETURN f.legalName")));
    }

    @Test
    void aggregatesGroupImplicitlyAndASumOfNothingIsZero() throws SQLException {
        ExecutionResult r = run("MATCH (f:Firm)<-[:firm]-(p:Person) RETURN f.legalName, count(*) AS n ORDER BY n DESC");
        assertEquals(List.of("f.legalName", "n"), columns(r));
        assertEquals(expect("Acme|2", "Globex|1"), rows(r));
        ExecutionResult all = run("MATCH (p:Person) RETURN count(*), sum(p.age), avg(p.age), min(p.age), max(p.age)");
        assertEquals(List.of("count(*)", "sum(p.age)", "avg(p.age)", "min(p.age)", "max(p.age)"), columns(all));
        assertEquals(expect("4|95|31.666666666666668|25|40"), rows(all));
        assertEquals(expect("0|0|null"), rows(run("MATCH (p:Person) WHERE p.age > 100 RETURN count(p), sum(p.age),"
                + " avg(p.age)")));
    }

    @Test
    void withProjectsAndFiltersAndReturnReadsItsColumns() throws SQLException {
        assertEquals(expect("Bob|80", "Alice|60"), rows(run("MATCH (p:Person) WITH p.name AS name, p.age AS age"
                + " WHERE age IS NOT NULL RETURN name, age * 2 AS twice ORDER BY twice DESC LIMIT 2")));
        assertEquals(expect("Alice", "Bob"), rows(run("MATCH (p:Person) WITH p WHERE p.age > 26 RETURN p.name"
                + " ORDER BY p.name")));
    }

    @Test
    void caseCoalesceDistinctAndSlicing() throws SQLException {
        assertEquals(expect("Alice|senior", "Bob|senior", "Charlie|junior", "Diana|unknown"),
                rows(run("MATCH (p:Person) RETURN p.name, CASE WHEN p.age >= 30 THEN 'senior' WHEN p.age < 30"
                        + " THEN 'junior' ELSE 'unknown' END AS band ORDER BY p.name")));
        assertEquals(expect("0", "25", "30", "40"), rows(run("MATCH (p:Person) RETURN coalesce(p.age, 0) AS age"
                + " ORDER BY age")));
        assertEquals(expect("Bob", "Charlie"), rows(run("MATCH (p:Person) RETURN p.name ORDER BY p.name SKIP 1"
                + " LIMIT 2")));
        assertEquals(expect("Acme", "Globex"), rows(run("MATCH (p:Person)-[:firm]->(f:Firm) RETURN DISTINCT"
                + " f.legalName AS firm ORDER BY firm")));
    }

    @Test
    void nullSortsLastAscendingAndFirstDescending() throws SQLException {
        assertEquals(expect("Charlie|25", "Alice|30", "Bob|40", "Diana|null"),
                rows(run("MATCH (p:Person) RETURN p.name AS n, p.age AS a ORDER BY p.age, p.name")));
        assertEquals(expect("Diana|null", "Bob|40", "Alice|30", "Charlie|25"),
                rows(run("MATCH (p:Person) RETURN p.name AS n, p.age AS a ORDER BY a DESC")));
    }

    @Test
    void arithmeticAndStringPredicates() throws SQLException {
        ExecutionResult r = run("RETURN 7 / 2 AS q, -7 / 2 AS nq, 7 % 3 AS m, 2 ^ 3 AS p, 7 / 2.0 AS f,"
                + " 'a' + 'b' AS s");
        assertEquals(List.of("q", "nq", "m", "p", "f", "s"), columns(r));
        assertEquals(expect("3|-3|1|8.0|3.5|ab"), rows(r));
        assertEquals(expect("Alice", "Charlie"), rows(run("MATCH (p:Person) WHERE p.name STARTS WITH 'A'"
                + " OR p.name ENDS WITH 'e' RETURN p.name ORDER BY p.name")));
        assertEquals(expect("Bob", "Charlie"), rows(run("MATCH (p:Person) WHERE p.age IN [25, 40] RETURN p.name"
                + " ORDER BY p.name")));
    }

    @Test
    void theIslandComposesWithPure() throws SQLException {
        ExecutionResult r = new QueryService().execute(MODEL, "#Cypher{MATCH (p:Person) RETURN p.name AS name,"
                + " p.age AS age}#->filter(x|$x.age > 26)->select(~[name])->sort(~name->ascending())", "test::RT",
                duckdb);
        assertEquals(expect("Alice", "Bob"), rows(r));
    }

    @Test
    void aSyntaxErrorIsAParseErrorAtItsPlaceInTheSource() {
        LegendCompileException e = assertThrows(LegendCompileException.class,
                () -> run("MATCH (p:Person RETURN p.name"));
        assertEquals(LegendCompileException.Phase.PARSE, e.phase());
        assertEquals("#Cypher{...}# at line 1, column 25: expected ')' but found 'RETURN'", e.getMessage());
    }

    @Test
    void aQueryNeo4jRejectsIsATypeError() {
        LegendCompileException e = assertThrows(LegendCompileException.class, () -> run("MATCH (p:Person)"
                + " RETURN p.nam"));
        assertEquals(LegendCompileException.Phase.TYPE, e.phase());
        assertTrue(e.getMessage().contains("the class test::Person has no property nam"), e.getMessage());
        LegendCompileException label = assertThrows(LegendCompileException.class, () -> run("MATCH (p:Nobody)"
                + " RETURN p.name"));
        assertTrue(label.getMessage().contains("the label Nobody names no class"), label.getMessage());
    }

    @Test
    void whatIsNotReadYetIsRefusedByName() {
        com.legend.error.NotImplementedException e = assertThrows(com.legend.error.NotImplementedException.class,
                () -> run("MATCH (p:Person)-[r:firm]->(f:Firm) RETURN r.since"));
        assertEquals("#Cypher{...}# at line 1, column 53: a property of a relationship over a property or association"
                + " (no edge class, the study's §5.6) is not supported yet", e.getMessage());
        com.legend.error.NotImplementedException create = assertThrows(com.legend.error.NotImplementedException.class,
                () -> run("CREATE (p:Person {name: 'x'})"));
        assertTrue(create.getMessage().contains("CREATE is not supported yet"), create.getMessage());
    }
}
