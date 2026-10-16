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

        @Override
        public List<String> classes() {
            return CLASSES.keySet().stream().sorted().toList();
        }

        @Override
        public void properties(String classPath, CypherToPure.PropertySink sink) {
            Map<String, Prop> props = CLASSES.get(classPath);
            if (props != null) {
                props.keySet().stream().sorted().forEach(n -> props.get(n).reportTo(sink));
            }
        }

        @Override
        public String labelOf(String classPath) {
            return classPath.substring(classPath.lastIndexOf("::") + 2);
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

    // ------------------------------------------------------------------ a time of day (the study's §12.7)

    @Test
    void aLocalTimeIsPuresStrictTimeReadThroughTheEpochDay() {
        // a written time is Pure's time literal; one from components is the date-time at it on 1970-01-01, its time
        // taken back (lite's atTime and timeOf: the date is a token the database never sees as a value)
        assertEquals("#TDS{\n_cypher_row\n1\n}#->extend(~[t:r|%12:31:14.645,"
                        + " u:r|1970->date(1, 1, 12, 0, 0)->meta::legend::lite::timeOf(),"
                        + " h:r|%1970-01-01->meta::legend::lite::atTime(%12:31:14.645)->hour()])->select(~[t, u, h])",
                pure("RETURN localtime('12:31:14.645') AS t, localtime({hour: 12}) AS u,"
                        + " localtime('12:31:14.645').hour AS h"));
        // Neo4j's errors, spelled as Neo4j spells them
        invalid("RETURN localtime({minute: 5}) AS t", "hour must be specified");
        invalid("RETURN localtime('12:00').year AS y", "Unsupported field: Year");
        invalid("RETURN localtime.truncate('month', localtime('12:00')) AS t",
                "Unit is too large to be used for truncation");
        invalid("RETURN localtime(date('1984-10-11')) AS t", "Cannot get the time of: 1984-10-11");
        invalid("RETURN localtime('25:00') AS t", "Text cannot be parsed to a LocalTime: \"25:00\"");
        // a zoned time of day is its instant on the epoch day, the offset beside: the wall time at the epoch day,
        // less the offset (the adjust steps the database computes); a field reads the wall time back
        assertEquals("#TDS{\n_cypher_row\n1\n}#->extend(~[t:r|%1970-01-01->meta::legend::lite::atTime(%12:00:00)"
                        + "->adjust(-1, meta::pure::functions::date::DurationUnit.HOURS),"
                        + " h:r|%1970-01-01->meta::legend::lite::atTime(%12:00:00)"
                        + "->adjust(-1, meta::pure::functions::date::DurationUnit.HOURS)"
                        + "->adjust(1, meta::pure::functions::date::DurationUnit.HOURS)->hour()])->select(~[t, h])",
                pure("RETURN time('12:00+01:00') AS t, time('12:00+01:00').hour AS h"));
        invalid("RETURN time('25:00+01:00') AS t", "Text cannot be parsed to a Time: \"25:00+01:00\"");
        invalid("RETURN time('12:00[Europe/Stockholm]') AS t", "Using a named time zone e.g. [UTC] is not valid");
        // refused by name: a named zone for a time (its offset is the clock's), the Variant's text spelling of a time
        unsupported("RETURN time({hour: 12, timezone: 'Europe/Stockholm'}) AS t", "a named zone for a time of day");
        unsupported("RETURN time({timezone: '+01:00'}) AS t", "the current time in a zone");
        unsupported("RETURN [localtime('12:31'), localtime('13:00')] AS l", "a list or map holding a LocalTime");
    }

    // ------------------------------------------------------------------ a zoned date-time (the study's §12.8)

    @Test
    void aLabelLessNodeReadsEveryClassOfTheModelAsConcatenatedArms() {
        // MATCH (n): one arm per class, each projected to the same columns, the arms concatenated; a property a
        // class lacks reads as null where another declares it (the study's §12.13)
        String p = pure("MATCH (n) WHERE n.name IS NOT NULL RETURN n.name AS name ORDER BY name");
        assertTrue(p.contains("demo::City.all()"), p);
        assertTrue(p.contains("demo::Firm.all()"), p);
        assertTrue(p.contains("demo::Person.all()"), p);
        assertTrue(p.contains("->concatenate("), p);
        assertTrue(p.indexOf("->sort(") > p.lastIndexOf("->concatenate("), "the sort reads the whole: " + p);
        String q = pure("MATCH (n) RETURN count(*) AS c");
        assertTrue(q.contains("->concatenate(") && q.indexOf("->aggregate(") > q.lastIndexOf("->concatenate("),
                "the aggregate reads the whole: " + q);
        String r = pure("MATCH (n) RETURN n.age AS age");
        assertTrue(r.contains("[]->cast(@Integer)"), "a class without the property reads null: " + r);
        // refused by name: a node as a value, a second MATCH after a label-less one, a property declared nowhere
        unsupported("MATCH (n) MATCH (p:Person) RETURN p.name", "a MATCH after a label-less pattern");
        invalid("MATCH (n) RETURN n.nope AS x", "the graph is the model");
    }

    @Test
    void aNanosecondValueIsTheLiteralToTheMicrosecondMovedOverTheNanosecondEpoch() {
        // the translator splits the written digits: the literal to the microsecond, then the sub-microsecond
        // digits as the database's nanosecond step; every later operation on the value is spelled over the
        // nanosecond epoch (dateDiff/adjust with NANOSECONDS: DuckDB's epoch_ns and make_timestamp_ns)
        assertEquals("#TDS{\n_cypher_row\n1\n}#->extend(~[d:r|%1984-10-11T12:31:14.645876"
                        + "->adjust(123, meta::pure::functions::date::DurationUnit.NANOSECONDS),"
                        + " n:r|%1984-10-11T12:31:14.645876->adjust(123, meta::pure::functions::date::DurationUnit.NANOSECONDS)"
                        + "->firstMillisecondOfSecond()->dateDiff(%1984-10-11T12:31:14.645876"
                        + "->adjust(123, meta::pure::functions::date::DurationUnit.NANOSECONDS),"
                        + " meta::pure::functions::date::DurationUnit.NANOSECONDS)])->select(~[d, n])",
                pure("RETURN localdatetime('1984-10-11T12:31:14.645876123') AS d,"
                        + " localdatetime('1984-10-11T12:31:14.645876123').nanosecond AS n"));
        // refused by name: a nanosecond component of the row, a nanosecond value outside the database's range
        unsupported("UNWIND [1] AS i RETURN localtime({hour: 12, minute: 0, second: 0, nanosecond: i}) AS t",
                "a nanosecond component that is not a spelled number");
        unsupported("RETURN localdatetime({year: 1, month: 1, day: 1, hour: 1, minute: 1, second: 1, nanosecond: 1}) AS t",
                "a nanosecond value of the year 1");
    }

    @Test
    void aZonedDateTimeIsItsInstantWithTheZoneCarriedBeside() {
        // a local date-time at the default zone (UTC) is the instant itself; a written offset is spelled as the
        // adjust steps the database computes (the instant is the wall time less the offset); the zone rides beside
        // the value, so a field reads the wall time back
        assertEquals("#TDS{\n_cypher_row\n1\n}#->extend(~[d:r|%1984-10-11T12:31:14,"
                        + " z:r|%1984-10-11T12:31:14->adjust(-1, meta::pure::functions::date::DurationUnit.HOURS),"
                        + " h:r|%1984-10-11T12:31:14->adjust(-1, meta::pure::functions::date::DurationUnit.HOURS)"
                        + "->adjust(1, meta::pure::functions::date::DurationUnit.HOURS)->hour()])->select(~[d, z, h])",
                pure("RETURN datetime(localdatetime('1984-10-11T12:31:14')) AS d,"
                        + " datetime('1984-10-11T12:31:14+01:00') AS z, datetime('1984-10-11T12:31:14+01:00').hour AS h"));
        // Neo4j's errors, spelled as Neo4j spells them
        invalid("RETURN datetime(date('1984-10-11')) AS d", "Cannot select datetime from: 1984-10-11");
        invalid("RETURN datetime({year: 1984, month: 10, day: 11, timezone: '+0100'}) AS d",
                "Invalid value for TimeZone: Text '+0100' could not be parsed at index 0");
        invalid("RETURN localdatetime('1984-10-11T12:00').timezone AS z", "Cannot get the offset of: 1984-10-11T12:00:00");
        invalid("RETURN localdatetime({year: 1984, month: 1, day: 1, timezone: '+01:00'}) AS d",
                "Cannot assign time zone if also assigning other fields.");
        invalid("RETURN datetime('1984-10-11T12:31:14+2') AS d", "Invalid value for TimeZone: Text '+2'");
        // refused by name: a signed year, toString, a zoned value of a list read in its zone
        unsupported("RETURN datetime('+999999999-12-31T12:31:14Z') AS d", "a signed year");
        unsupported("RETURN toString(datetime('1984-10-11T12:31:14Z')) AS s", "toString of a DateTime");
        unsupported("UNWIND [datetime('1984-10-11T12:31:14+01:00'), datetime('1984-10-11T12:31:14Z')] AS d RETURN d.hour",
                "whose zone is not carried here");
    }

    // ------------------------------------------------------------------ a duration (the study's §12.9)

    @Test
    void aDurationIsAnInstanceOfTheModelsDurationClass() {
        // the demo model declares no Duration class: refused by name, with the declaration to add
        unsupported("RETURN duration({days: 1}) AS d", "a duration needs one class named Duration in the model");
        unsupported("RETURN duration.between(date('1984-10-11'), date('2015-06-24')) AS d",
                "a duration needs one class named Duration in the model");
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
    void aSortByAnExpressionNotProjectedIsAHiddenColumnUnderASliceAndRefusedWithout() {
        assertEquals("demo::Person.all()->project(~[who:p|$p.name, _cypher_1:p|$p.age])"
                        + "->sort([~_cypher_1->ascending(meta::pure::functions::relation::NullOrder.LAST)])"
                        + "->select(~[who])->limit(5)",
                pure("MATCH (p:Person) RETURN p.name AS who ORDER BY p.age LIMIT 5"));
        // the lowering's execute path renders no sort key that is not an output column (the review's finding 6)
        unsupported("MATCH (p:Person) RETURN p.name AS who ORDER BY p.age",
                "an ORDER BY on an expression that is not a returned item, without a SKIP or LIMIT");
    }

    @Test
    void aSortOverTheProjectedColumnsAfterAGroupingIsComputedAfterIt() {
        // the review's finding 7: Neo4j reads ORDER BY a + 'x' over the items a and c
        assertEquals("demo::Person.all()->project(~[a:p|$p.name, _cypher_1:p|1])"
                        + "->groupBy(~[a], ~[c:x|$x._cypher_1:y|$y->count()])->select(~[a, c])"
                        + "->extend(~[_cypher_2:r|$r.a + 'x'])"
                        + "->sort([~_cypher_2->ascending(meta::pure::functions::relation::NullOrder.LAST)])"
                        + "->select(~[a, c])",
                pure("MATCH (p:Person) RETURN p.name AS a, count(*) AS c ORDER BY a + 'x'"));
    }

    @Test
    void twoRelationshipsOfOneTypeInOneMatchAreRefusedUntilIdentity() {
        // the review's finding 1: Neo4j binds the two slots to different relationships (relationship uniqueness)
        unsupported("MATCH (p:Person)-[:firm]->(f:Firm), (p)-[:firm]->(g:Firm) RETURN f.legalName",
                "two relationships of the type firm in one MATCH");
        unsupported("MATCH (p:Person)-[:firm]->(f:Firm)<-[:firm]-(q:Person) RETURN q.name", "fans out");
        // in two MATCH clauses they may be the same relationship, as in Neo4j
        assertEquals("demo::Person.all()->filter(p|$p.firm->isNotEmpty() && $p.firm->isNotEmpty())"
                        + "->project(~['g.legalName':p|$p.firm.legalName])",
                pure("MATCH (p:Person)-[:firm]->(f:Firm) MATCH (p)-[:firm]->(g:Firm) RETURN g.legalName"));
    }

    @Test
    void aFloatLiteralIsADouble() {
        // the review's finding 3: a bare Float literal lowers to a decimal (S9); Cypher's float is IEEE 754
        assertEquals("#TDS{\n_cypher_row\n1\n}#->extend(~[s:r|toFloat(0.1) + toFloat(0.2),"
                        + " eq:r|toFloat(0.1) + toFloat(0.2) == toFloat(0.3)])->select(~[s, eq])",
                pure("RETURN 0.1 + 0.2 AS s, 0.1 + 0.2 = 0.3 AS eq"));
        // the negation of a float is IEEE's (-0.0 is a value of its own); of an integer, Pure's minus
        assertEquals("#TDS{\n_cypher_row\n1\n}#->extend(~[z:r|toFloat(0.0) * -1.0, n:r|-3])->select(~[z, n])",
                pure("RETURN -0.0 AS z, -(3) AS n"));
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
        // the WITH's WHERE is a hidden column of its projection (it may read the scope before the projection too),
        // filtered on and dropped before the clauses after
        assertEquals("demo::Person.all()->project(~[name:p|$p.name, age:p|$p.age,"
                        + " _cypher_1:p|$p.age->isNotEmpty() && ($p.age->toOne() > 1)])"
                        + "->filter(r|$r._cypher_1)->select(~[name, age])"
                        + "->extend(~[twice:r|if($r.age->isEmpty(), |[], |$r.age->toOne() * 2)])"
                        + "->select(~[name, twice])",
                pure("MATCH (p:Person) WITH p.name AS name, p.age AS age WHERE age > 1 RETURN name, age * 2 AS twice"));
    }

    @Test
    void aNodeReturnedIsItsLabelAndItsProperties() {
        // C8 (the study's §12.14): a node as a value is the JSON the judge reads as a node, built as text by the
        // database -- its label, then each property that holds a value (Neo4j stores no null)
        assertEquals("demo::Person.all()->project(~[p:p|fromJson((((('{\"@labels\":' + '[\"Person\"]')"
                        + " + if(if($p.active->isNotEmpty() && $p.active->toOne(), |true, |if($p.active->isNotEmpty()"
                        + " && !$p.active->toOne(), |false, |[]))->isEmpty(), |'', |',\"active\":'"
                        + " + if($p.active->isNotEmpty() && $p.active->toOne(), |true, |if($p.active->isNotEmpty()"
                        + " && !$p.active->toOne(), |false, |[]))->toOne()->toString()))"
                        + " + if($p.age->isEmpty(), |'', |',\"age\":' + $p.age->toOne()->toString()))"
                        + " + (',\"name\":' + ('\"' + $p.name->replace('\\\\', '\\\\\\\\')"
                        + "->replace('\"', '\\\\\"') + '\"'))) + '}')])",
                pure("MATCH (p:Person) RETURN p"));
    }

    @Test
    void aNodeThroughWithIsCarriedAsItsColumns() {
        // the node's label and properties as columns of the WITH, so ORDER BY p.age sorts a typed column and the
        // RETURN reads p.name as one; the node's own name is a virtual column
        assertEquals("demo::Person.all()->project(~[_cypher_1:p|'[\"Person\"]', _cypher_2:p|if($p.active->isNotEmpty()"
                        + " && $p.active->toOne(), |true, |if($p.active->isNotEmpty() && !$p.active->toOne(), |false,"
                        + " |[])), _cypher_3:p|$p.age, _cypher_4:p|$p.name])"
                        + "->sort([~_cypher_3->ascending(meta::pure::functions::relation::NullOrder.LAST)])->limit(1)"
                        + "->extend(~['p.name':r|$r._cypher_4, p:r|fromJson((((('{\"@labels\":' + $r._cypher_1)"
                        + " + if(if($r._cypher_2->isNotEmpty() && $r._cypher_2->toOne(), |true,"
                        + " |if($r._cypher_2->isNotEmpty() && !$r._cypher_2->toOne(), |false, |[]))->isEmpty(), |'',"
                        + " |',\"active\":' + if($r._cypher_2->isNotEmpty() && $r._cypher_2->toOne(), |true,"
                        + " |if($r._cypher_2->isNotEmpty() && !$r._cypher_2->toOne(), |false, |[]))->toOne()"
                        + "->toString())) + if($r._cypher_3->isEmpty(), |'', |',\"age\":' + $r._cypher_3->toOne()"
                        + "->toString())) + (',\"name\":' + ('\"' + $r._cypher_4->replace('\\\\', '\\\\\\\\')"
                        + "->replace('\"', '\\\\\"') + '\"'))) + '}')])->select(~['p.name', p])",
                pure("MATCH (p:Person) WITH p ORDER BY p.age LIMIT 1 RETURN p.name, p"));
    }

    @Test
    void aWithsWhereReadsTheScopeBeforeTheProjectionToo() {
        // Cypher's `WITH a.x AS x WHERE a.y > 1`: the WHERE sees the pattern's variables as well as the items
        assertEquals("demo::Person.all()->project(~[n:p|$p.name,"
                        + " _cypher_1:p|$p.age->isNotEmpty() && ($p.age->toOne() > 1)])"
                        + "->filter(r|$r._cypher_1)->select(~[n])->select(~[n])",
                pure("MATCH (p:Person) WITH p.name AS n WHERE p.age > 1 RETURN n"));
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
    void aSortInAGroupedProjectionReadsTheItemsOnly() {
        invalid("MATCH (p:Person) RETURN p.name, count(*) ORDER BY p.age", "Variable `p` not defined");
        invalid("MATCH (p:Person) RETURN DISTINCT p.name ORDER BY p.age", "Variable `p` not defined");
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
        assertEquals("demo::Person.all()->project(~[age:p|if($p.age->isEmpty(), |0, |$p.age)->toOne()])",
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
        assertEquals("#TDS{\n_cypher_row\n1\n}#->extend(~[r:r|7->rem(3), p:r|toFloat(2)->pow(3),"
                        + " f:r|7 / toFloat(2.0), s:r|'a' + 'b'])->select(~[r, p, f, s])",
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
        invalid("MATCH (p:Person) RETURN p.name SKIP -1", "not a valid value");
    }

    // ------------------------------------------------------------------ refusals by name

    @Test
    void whatPr1DoesNotReadIsRefusedByName() {
        unsupported("MATCH (p:Person)-[r:firm]->(f) RETURN r", "a relationship as a value (r)");
        unsupported("MATCH (p:Person) RETURN p.firm", "a node as a value is not supported yet");
        unsupported("MATCH (p:Person) RETURN p.tags", "holds many values (a list)");
        unsupported("MATCH (p:Person)-[:firm]->(f:Firm)-[:employees]->(q:Person) RETURN q.name", "fans out");
        unsupported("MATCH (f:Firm)-[:hq]->(c:City)<-[:hq]-(g:Firm) RETURN g.legalName", "fans out");
        unsupported("MATCH (p:Person), (f:Firm) RETURN p.name", "cartesian product");
        unsupported("MATCH (p:Person)-[:firm]->(f:Firm)<-[:firm]-(q:Person) RETURN q.name", "fans out");
        unsupported("MATCH (p:Person)-[:firm*1..2]->(f) RETURN f.legalName", "variable-length");
        unsupported("MATCH (p:Person)-[:firm {since: 1}]->(f) RETURN f.legalName", "properties on a relationship");
        unsupported("OPTIONAL MATCH (p:Person) RETURN p.name", "OPTIONAL MATCH");
        unsupported("MATCH (p:Person) WITH p.name AS n MATCH (f:Firm) RETURN n", "a MATCH after a WITH");
        unsupported("MATCH (p:Person) UNWIND [1, 2] AS x RETURN p.name, x", "UNWIND after a MATCH");
        unsupported("CREATE (p:Person {name: 'x'})", "CREATE is not supported yet");
        unsupported("MATCH (p:Person) RETURN labels(p)", "the function labels(...) is not supported yet");
        unsupported("MATCH (p:Person) RETURN collect(p.name)", "collect(...) is not supported yet");
        unsupported("MATCH (p:Person) RETURN count(DISTINCT p.name)", "count(DISTINCT ...)");
        unsupported("MATCH (p:Person) WHERE p.name = $name RETURN p.name", "a parameter ($name)");
        unsupported("MATCH (p:Person) WHERE p.name =~ 'A.*' RETURN p.name", "regular expression");
        unsupported("MATCH (p:Person) WITH [p.age] AS l RETURN l", "holding a value of the row (l), carried");
        unsupported("MATCH (p:Person) RETURN [p.age] AS l", "a list of one value of the row");
        invalid("MATCH (p:Person) RETURN all(x IN p.name WHERE x > 1)", "expected List<T> but was String");
        unsupported("USE demo::Space MATCH (p:Person) RETURN p.name", "USE");
        unsupported("MATCH (p:Person) RETURN DISTINCT p", "RETURN DISTINCT over a node (p");
        unsupported("MATCH (p:Person) RETURN p ORDER BY p", "ORDER BY a node (p");
        unsupported("MATCH (p:Person) RETURN p, count(*) AS c", "a node among the grouping keys (p");
        unsupported("MATCH (p:Person:Firm) RETURN p.name", "several labels");
        unsupported("MATCH (p:Person) RETURN p.age + count(*)", "an expression around an aggregate");
        unsupported("RETURN 1e308 AS x", "a float literal beyond 1e15");
        unsupported("RETURN 1e-20 AS x", "a float literal beyond 1e15");
    }
}
