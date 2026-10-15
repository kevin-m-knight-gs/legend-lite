// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend.protocol.cypher;

import com.legend.protocol.PureComposer;
import com.legend.protocol.spec.ValueSpecification;
import com.legend.protocol.sql.SqlTranslationException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The translator ({@link CypherToPure}) on its own: the Pure each query becomes, printed as a person would write it,
 * Cypher's null rules, and each refusal's kind. The model is a stand-in for the compiled one, so nothing is compiled
 * here; {@code CypherIslandIntegrationTest} runs the same pipeline end to end.
 */
class CypherToPureTest {

    /** A property of the stand-in model: name, type, bounds; a type written as a path is a class. */
    private record Prop(String name, String type, int lower, int upper) {
        void reportTo(CypherToPure.PropertySink sink) {
            sink.property(name, type, type.contains("::"), false, lower, upper);
        }
    }

    private static Prop prop(String name, String type, int lower, int upper) {
        return new Prop(name, type, lower, upper);
    }

    /** demo::Person {name String[1], age Integer[0..1], active Boolean[0..1], firm Firm[0..1]} and demo::Firm
     *  {legalName String[1], hq City[0..1], employees Person[*]}; demo::City {name String[1]}; firm and employees
     *  are one association's ends. */
    private static final Map<String, Map<String, Prop>> CLASSES = Map.of(
            "demo::Person", Map.of(
                    "name", prop("name", "String", 1, 1),
                    "age", prop("age", "Integer", 0, 1),
                    "active", prop("active", "Boolean", 0, 1),
                    "firm", prop("firm", "demo::Firm", 0, 1),
                    "tags", prop("tags", "String", 0, -1)),
            "demo::Firm", Map.of(
                    "legalName", prop("legalName", "String", 1, 1),
                    "hq", prop("hq", "demo::City", 0, 1),
                    "employees", prop("employees", "demo::Person", 0, -1)),
            "demo::City", Map.of("name", prop("name", "String", 1, 1)));

    private static final CypherToPure.Model MODEL = new CypherToPure.Model() {
        @Override
        public List<String> classesOf(String label) {
            if (label.contains("::")) {
                return CLASSES.containsKey(label) ? List.of(label) : List.of();
            }
            return CLASSES.keySet().stream().filter(c -> c.endsWith("::" + label)).sorted().toList();
        }

        @Override
        public void property(String classPath, String name, CypherToPure.PropertySink sink) {
            Map<String, Prop> props = CLASSES.get(classPath);
            Prop p = props == null ? null : props.get(name);
            if (p != null) {
                p.reportTo(sink);
            }
        }

        @Override
        public void inverse(String classPath, String property, CypherToPure.PropertySink sink) {
            if (classPath.equals("demo::Person") && property.equals("firm")) {
                prop("employees", "demo::Person", 0, -1).reportTo(sink);
            } else if (classPath.equals("demo::Firm") && property.equals("employees")) {
                prop("firm", "demo::Firm", 0, 1).reportTo(sink);
            }
        }

        @Override
        public boolean isSubclass(String classPath, String superPath) {
            return classPath.equals(superPath);
        }
    };

    private static String pure(String cypher) {
        ValueSpecification vs = CypherToPure.translate(cypher, MODEL);
        return PureComposer.valueSpecification(vs, PureComposer.Style.STANDARD);
    }

    private static SqlTranslationException refusal(String cypher) {
        return assertThrows(SqlTranslationException.class, () -> pure(cypher));
    }

    private static void unsupported(String cypher, String message) {
        SqlTranslationException e = refusal(cypher);
        assertEquals(SqlTranslationException.Kind.UNSUPPORTED, e.kind(), e.getMessage());
        assertTrue(e.getMessage().contains(message), e.getMessage());
    }

    private static void invalid(String cypher, String message) {
        SqlTranslationException e = refusal(cypher);
        assertEquals(SqlTranslationException.Kind.INVALID, e.kind(), e.getMessage());
        assertTrue(e.getMessage().contains(message), e.getMessage());
    }

    // ------------------------------------------------------------------ shapes

    @Test
    void aMatchReturnIsGetAllFilterProject() {
        assertEquals("demo::Person.all()->filter(p|$p.name == 'x')->project(~['p.age':p|$p.age])",
                pure("MATCH (p:Person) WHERE p.name = 'x' RETURN p.age"));
    }

    @Test
    void anAliasNamesTheColumnAndSortSliceDistinctFollow() {
        assertEquals("demo::Person.all()->project(~[who:p|$p.name, age:p|$p.age])->distinct()"
                        + "->sort([~age->descending(meta::pure::functions::relation::NullOrder.FIRST),"
                        + " ~who->ascending(meta::pure::functions::relation::NullOrder.LAST)])->drop(1)->limit(2)",
                pure("MATCH (p:Person) RETURN DISTINCT p.name AS who, p.age AS age ORDER BY age DESC, who SKIP 1"
                        + " LIMIT 2"));
    }

    @Test
    void aSortByAnExpressionNotProjectedIsAHiddenColumn() {
        assertEquals("demo::Person.all()->project(~[who:p|$p.name, _cypher_1:p|$p.age])"
                        + "->sort([~_cypher_1->ascending(meta::pure::functions::relation::NullOrder.LAST)])"
                        + "->select(~[who])",
                pure("MATCH (p:Person) RETURN p.name AS who ORDER BY p.age"));
    }

    @Test
    void aToOneHopIsANavigationAndMustFindANode() {
        assertEquals("demo::Person.all()->filter(p|$p.firm->isNotEmpty())"
                        + "->project(~['p.name':p|$p.name, 'f.legalName':p|$p.firm.legalName])",
                pure("MATCH (p:Person)-[:firm]->(f:Firm) RETURN p.name, f.legalName"));
    }

    @Test
    void aTwoHopChainNavigatesTwice() {
        assertEquals("demo::Person.all()->filter(p|$p.firm->isNotEmpty() && $p.firm.hq->isNotEmpty())"
                        + "->project(~['c.name':p|$p.firm.hq.name])",
                pure("MATCH (p:Person)-[:firm]->(:Firm)-[:hq]->(c:City) RETURN c.name"));
    }

    @Test
    void anIncomingHopOrAToManyOneIsReadFromTheNodeThatHoldsTheToOneEnd() {
        String expected = "demo::Person.all()->filter(p|$p.firm->isNotEmpty())"
                + "->project(~['f.legalName':p|$p.firm.legalName, 'p.name':p|$p.name])";
        assertEquals(expected, pure("MATCH (f:Firm)<-[:firm]-(p:Person) RETURN f.legalName, p.name"));
        assertEquals(expected, pure("MATCH (f:Firm)-[:employees]->(p:Person) RETURN f.legalName, p.name"));
        assertEquals(expected, pure("MATCH (p:Person)<-[:employees]-(f:Firm) RETURN f.legalName, p.name"));
    }

    @Test
    void aLabelLessNodeTakesThePropertysType() {
        assertEquals("demo::Person.all()->filter(p|$p.firm->isNotEmpty())"
                        + "->project(~['f.legalName':p|$p.firm.legalName])",
                pure("MATCH (p:Person)-[:firm]->(f) RETURN f.legalName"));
    }

    @Test
    void aPropertyMapIsAnEqualityFilter() {
        assertEquals("demo::Person.all()->filter(p|$p.name == 'Bob')->project(~['p.age':p|$p.age])",
                pure("MATCH (p:Person {name: 'Bob'}) RETURN p.age"));
    }

    @Test
    void aSecondPatternExtendsTheFirstFromABoundNode() {
        assertEquals("demo::Person.all()->filter(p|$p.firm->isNotEmpty() && $p.firm.hq->isNotEmpty())"
                        + "->project(~['c.name':p|$p.firm.hq.name])",
                pure("MATCH (p:Person)-[:firm]->(f:Firm) MATCH (f)-[:hq]->(c:City) RETURN c.name"));
    }

    @Test
    void aWithOfPatternVariablesKeepsTheGraph() {
        assertEquals("demo::Person.all()->filter(p|$p.age->isNotEmpty() && ($p.age->toOne() > 1))"
                        + "->project(~['q.name':p|$p.name])",
                pure("MATCH (p:Person) WITH p AS q WHERE q.age > 1 RETURN q.name"));
    }

    @Test
    void aWithOfValuesProjectsAndTheRestReadsItsColumns() {
        assertEquals("demo::Person.all()->project(~[name:p|$p.name, age:p|$p.age])"
                        + "->filter(r|$r.age->isNotEmpty() && ($r.age->toOne() > 1))"
                        + "->extend(~[twice:r|if($r.age->isEmpty(), |[], |$r.age->toOne() * 2)])"
                        + "->select(~[name, twice])",
                pure("MATCH (p:Person) WITH p.name AS name, p.age AS age WHERE age > 1 RETURN name, age * 2 AS twice"));
    }

    @Test
    void aColumnRenamedOntoATakenNameGoesThroughAFreshOne() {
        assertEquals("demo::Person.all()->project(~[age:p|$p.age])"
                        + "->extend(~[_cypher_1:r|if($r.age->isEmpty(), |[], |$r.age->toOne() + 1)])"
                        + "->select(~[_cypher_1])"
                        + "->rename(~_cypher_1, ~age)",
                pure("MATCH (p:Person) WITH p.age AS age RETURN age + 1 AS age"));
    }

    @Test
    void aQueryWithoutAPatternIsOneRow() {
        assertEquals("#TDS{\n_cypher_row\n1\n}#->extend(~[three:r|1 + 2])->select(~[three])",
                pure("RETURN 1 + 2 AS three"));
    }

    // ------------------------------------------------------------------ aggregation

    @Test
    void anAggregateGroupsByTheOtherItemsImplicitly() {
        assertEquals("demo::Person.all()->filter(p|$p.firm->isNotEmpty())"
                        + "->project(~['f.legalName':p|$p.firm.legalName, _cypher_1:p|1])"
                        + "->groupBy(~['f.legalName'], ~[n:x|$x._cypher_1:y|$y->count()])->select(~['f.legalName', n])"
                        + "->sort([~n->descending(meta::pure::functions::relation::NullOrder.FIRST)])",
                pure("MATCH (f:Firm)<-[:firm]-(p:Person) RETURN f.legalName, count(*) AS n ORDER BY n DESC"));
    }

    @Test
    void anAggregateAloneIsOneRowAndASumOfNothingIsZero() {
        assertEquals("demo::Person.all()->project(~[_cypher_1:p|1, _cypher_2:p|$p.age, _cypher_3:p|$p.age,"
                        + " _cypher_4:p|$p.age])"
                        + "->aggregate(~['count(*)':x|$x._cypher_1:y|$y->count(), _cypher_5:x|$x._cypher_2:y|$y->sum(),"
                        + " 'avg(p.age)':x|$x._cypher_3:y|$y->average(), 'max(p.age)':x|$x._cypher_4:y|$y->max()])"
                        + "->extend(~['sum(p.age)':r|if($r._cypher_5->isEmpty(), |0, |$r._cypher_5->toOne())])"
                        + "->select(~['count(*)', 'sum(p.age)', 'avg(p.age)', 'max(p.age)'])",
                pure("MATCH (p:Person) RETURN count(*), sum(p.age), avg(p.age), max(p.age)"));
    }

    @Test
    void countOfANodeVariableCountsRowsAndCountOfAPropertyCountsItsValues() {
        assertEquals("demo::Person.all()->project(~[_cypher_1:p|1])"
                        + "->aggregate(~['count(p)':x|$x._cypher_1:y|$y->count()])->select(~['count(p)'])",
                pure("MATCH (p:Person) RETURN count(p)"));
        assertEquals("demo::Person.all()->project(~[_cypher_1:p|$p.age])"
                        + "->aggregate(~['count(p.age)':x|$x._cypher_1:y|$y->count()])->select(~['count(p.age)'])",
                pure("MATCH (p:Person) RETURN count(p.age)"));
    }

    @Test
    void aSortInAGroupedProjectionMustNameAnItem() {
        invalid("MATCH (p:Person) RETURN p.name, count(*) ORDER BY p.age", "not possible to access variables");
        invalid("MATCH (p:Person) RETURN DISTINCT p.name ORDER BY p.age", "not possible to access variables");
    }

    // ------------------------------------------------------------------ null rules

    @Test
    void comparisonsAreNullWhenASideIsAndWhereKeepsOnlyTrue() {
        assertEquals("demo::Person.all()->filter(p|$p.age->isNotEmpty() && ($p.age->toOne() > 30))"
                        + "->project(~['p.name':p|$p.name])",
                pure("MATCH (p:Person) WHERE p.age > 30 RETURN p.name"));
        // NOT null is null: the FALSE case of the comparison, not the negation of its TRUE case
        assertEquals("demo::Person.all()->filter(p|$p.age->isNotEmpty() && !($p.age->toOne() > 30))"
                        + "->project(~['p.name':p|$p.name])",
                pure("MATCH (p:Person) WHERE NOT p.age > 30 RETURN p.name"));
        assertEquals("demo::Person.all()->filter(p|false)->project(~['p.name':p|$p.name])",
                pure("MATCH (p:Person) WHERE p.age = null RETURN p.name"));
    }

    @Test
    void aNullableBooleanPropertyIsAConditionOnlyWhereItIsKnown() {
        assertEquals("demo::Person.all()->filter(p|$p.active->isNotEmpty() && $p.active->toOne())"
                        + "->project(~['p.name':p|$p.name])",
                pure("MATCH (p:Person) WHERE p.active RETURN p.name"));
        assertEquals("demo::Person.all()->filter(p|$p.active->isNotEmpty() && !$p.active->toOne())"
                        + "->project(~['p.name':p|$p.name])",
                pure("MATCH (p:Person) WHERE NOT p.active RETURN p.name"));
    }

    @Test
    void isNullIsTwoValued() {
        assertEquals("demo::Person.all()->filter(p|!$p.age->isNotEmpty())->project(~['p.name':p|$p.name])",
                pure("MATCH (p:Person) WHERE p.age IS NULL RETURN p.name"));
        assertEquals("demo::Person.all()->filter(p|true)->project(~['p.name':p|$p.name])",
                pure("MATCH (p:Person) WHERE p.name IS NOT NULL RETURN p.name"));
    }

    @Test
    void andOrXorKeepThreeValuedLogic() {
        // a AND b is FALSE when either is FALSE, even if the other is null
        assertEquals("demo::Person.all()->filter(p|($p.age->isNotEmpty() && ($p.age->toOne() > 30))"
                        + " && ($p.name == 'x'))->project(~['p.name':p|$p.name])",
                pure("MATCH (p:Person) WHERE p.age > 30 AND p.name = 'x' RETURN p.name"));
        assertEquals("demo::Person.all()->filter(p|($p.age->isNotEmpty() && !($p.age->toOne() > 30))"
                        + " || ($p.name != 'x'))->project(~['p.name':p|$p.name])",
                pure("MATCH (p:Person) WHERE NOT (p.age > 30 AND p.name = 'x') RETURN p.name"));
    }

    @Test
    void aComparisonAsAValueIsTrueFalseOrNothing() {
        assertEquals("demo::Person.all()->project(~[old:p|if($p.age->isNotEmpty() && ($p.age->toOne() > 30), |true,"
                        + " |if($p.age->isNotEmpty() && !($p.age->toOne() > 30), |false, |[]))])",
                pure("MATCH (p:Person) RETURN p.age > 30 AS old"));
    }

    @Test
    void caseAndCoalesce() {
        assertEquals("demo::Person.all()->project(~[band:p|if($p.age->isNotEmpty() && ($p.age->toOne() >= 30),"
                        + " |'senior', |if($p.age->isNotEmpty() && ($p.age->toOne() < 30), |'junior', |'unknown'))])",
                pure("MATCH (p:Person) RETURN CASE WHEN p.age >= 30 THEN 'senior' WHEN p.age < 30 THEN 'junior'"
                        + " ELSE 'unknown' END AS band"));
        assertEquals("demo::Person.all()->project(~[age:p|if($p.age->isEmpty(), |0, |$p.age)])",
                pure("MATCH (p:Person) RETURN coalesce(p.age, 0) AS age"));
        assertEquals("demo::Person.all()->project(~[one:p|if($p.name == 'a', |1, |[]->cast(@Integer))])",
                pure("MATCH (p:Person) RETURN CASE p.name WHEN 'a' THEN 1 END AS one"));
    }

    @Test
    void arithmeticIsCyphers() {
        // integer division truncates toward zero: Postgres's spelling (SqlToPure.integerQuotient)
        String quotient = pure("RETURN 7 / 2 AS q");
        assertTrue(quotient.startsWith("#TDS{\n_cypher_row\n1\n}#->extend(~[q:r|if(((7 - 7->rem(2)) / 2)"
                + " * 0.9999999999999991 >= 0, |floor(((7 - 7->rem(2)) / 2) * 0.9999999999999991), |ceiling("),
                quotient);
        assertTrue(quotient.endsWith("])->select(~[q])"), quotient);
        assertEquals("#TDS{\n_cypher_row\n1\n}#->extend(~[r:r|7->rem(3), p:r|toFloat(2)->pow(3), f:r|7 / 2.0,"
                        + " s:r|'a' + 'b'])->select(~[r, p, f, s])",
                pure("RETURN 7 % 3 AS r, 2 ^ 3 AS p, 7 / 2.0 AS f, 'a' + 'b' AS s"));
    }

    @Test
    void stringPredicatesAndIn() {
        assertEquals("demo::Person.all()->filter(p|$p.name->startsWith('A') || $p.name->endsWith('e'))"
                        + "->project(~['p.name':p|$p.name])",
                pure("MATCH (p:Person) WHERE p.name STARTS WITH 'A' OR p.name ENDS WITH 'e' RETURN p.name"));
        assertEquals("demo::Person.all()->filter(p|$p.age->isNotEmpty() && $p.age->toOne()->in([25, 40]))"
                        + "->project(~['p.name':p|$p.name])",
                pure("MATCH (p:Person) WHERE p.age IN [25, 40] RETURN p.name"));
    }

    // ------------------------------------------------------------------ the model is the schema

    @Test
    void anUnknownLabelPropertyOrRelationshipIsAnError() {
        invalid("MATCH (p:Nobody) RETURN p.name", "the label Nobody names no class");
        invalid("MATCH (p:Person) RETURN p.nam", "the class demo::Person has no property nam");
        invalid("MATCH (p:Person)-[:boss]->(q) RETURN q.name", "names no property or association end");
        invalid("MATCH (p:Person) RETURN q.name", "Variable `q` not defined");
        invalid("MATCH (p:Person) RETURN p.name, p.name", "Multiple result columns with the same name");
        invalid("MATCH (p:Person)", "Query cannot conclude with MATCH");
        invalid("MATCH (p:Person) RETURN p.name RETURN p.age", "RETURN can only be used at the end");
    }

    @Test
    void aLabelMayBeAFullPath() {
        assertEquals("demo::Person.all()->project(~['p.name':p|$p.name])",
                pure("MATCH (p:`demo::Person`) RETURN p.name"));
    }

    @Test
    void typeMismatchesAreNeo4jsErrors() {
        invalid("MATCH (p:Person) WHERE p.name RETURN p.name", "Type mismatch: expected Boolean but was String");
        invalid("MATCH (p:Person) WHERE p.age AND true RETURN p.name",
                "Type mismatch: expected Boolean but was Integer");
        invalid("MATCH (p:Person) RETURN p.name - 2", "Type mismatch: expected Float or Integer but was String");
        unsupported("MATCH (p:Person) RETURN p.name + 1", "+ of a string and a Integer");
        invalid("MATCH (p:Person) RETURN sum(p.name)",
                "Type mismatch: expected Float, Integer or Duration but was String");
        invalid("MATCH (p:Person) RETURN count(count(*))", "aggregate functions inside of aggregate functions");
        unsupported("MATCH (p:Person) RETURN p.name SKIP -1", "SKIP with an expression other than an integer literal");
    }

    // ------------------------------------------------------------------ refusals by name

    @Test
    void whatPr1DoesNotReadIsRefusedByName() {
        unsupported("MATCH (p:Person) RETURN p", "a node as a value (p) is not supported yet");
        unsupported("MATCH (p:Person)-[r:firm]->(f) RETURN r", "a relationship as a value (r)");
        unsupported("MATCH (p:Person) RETURN p.firm", "a node as a value is not supported yet");
        unsupported("MATCH (p:Person) RETURN p.tags", "holds many values (a list)");
        unsupported("MATCH (p:Person) RETURN *", "RETURN *");
        unsupported("MATCH (p:Person)-[:firm]->(f:Firm)-[:employees]->(q:Person) RETURN q.name", "fans out");
        unsupported("MATCH (f:Firm)-[:hq]->(c:City)<-[:hq]-(g:Firm) RETURN g.legalName", "fans out");
        unsupported("MATCH (p:Person), (f:Firm) RETURN p.name", "cartesian product");
        unsupported("MATCH (p:Person)-[:firm]->(f:Firm)<-[:firm]-(q:Person) RETURN q.name", "fans out");
        unsupported("MATCH (p:Person)-[:firm*1..2]->(f) RETURN f.legalName", "variable-length");
        unsupported("MATCH (p:Person)-[:firm {since: 1}]->(f) RETURN f.legalName", "properties on a relationship");
        unsupported("OPTIONAL MATCH (p:Person) RETURN p.name", "OPTIONAL MATCH");
        unsupported("MATCH (p:Person) WITH p.name AS n MATCH (f:Firm) RETURN n", "a MATCH after a WITH");
        unsupported("UNWIND [1, 2] AS x RETURN x", "UNWIND is not supported yet");
        unsupported("CREATE (p:Person {name: 'x'})", "CREATE is not supported yet");
        unsupported("MATCH (p:Person) RETURN toUpper(p.name)", "the function toUpper(...) is not supported yet");
        unsupported("MATCH (p:Person) RETURN collect(p.name)", "collect(...) is not supported yet");
        unsupported("MATCH (p:Person) RETURN count(DISTINCT p.name)", "count(DISTINCT ...)");
        unsupported("MATCH (p:Person) WHERE p.name = $name RETURN p.name", "a parameter ($name)");
        unsupported("MATCH (p:Person) WHERE p.name =~ 'A.*' RETURN p.name", "regular expression");
        unsupported("MATCH (p:Person) WHERE p.age IN [1, null] RETURN p.name", "IN over a list holding null");
        unsupported("MATCH (p:Person) RETURN p.name = 1", "comparing a String with a Integer");
        unsupported("USE demo::Space MATCH (p:Person) RETURN p.name", "USE");
        unsupported("MATCH (p:Person) WITH p ORDER BY p.age LIMIT 1 RETURN p.name", "a node as a value (p)");
        unsupported("MATCH (p:Person:Firm) RETURN p.name", "several labels");
        unsupported("MATCH (p:Person) RETURN p.age + count(*)", "an expression around an aggregate");
    }
}
