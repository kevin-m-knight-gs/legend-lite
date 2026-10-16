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
import static org.junit.jupiter.api.Assertions.assertFalse;
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
            "demo::City", Map.of("name", prop("name", "String", 1, 1)),
            // the edge classes (the study's §5.6): Acting's ends actor and firm, its properties role and since;
            // Directing's ends director and firm (never nodes: the models' classes() leave them out)
            "demo::Acting", Map.of(
                    "actor", prop("actor", "demo::Person", 1, 1),
                    "firm", prop("firm", "demo::Firm", 1, 1),
                    "role", prop("role", "String", 1, 1),
                    "since", prop("since", "Integer", 0, 1)),
            "demo::Directing", Map.of(
                    "director", prop("director", "demo::Person", 1, 1),
                    "firm", prop("firm", "demo::Firm", 1, 1)));

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
            // (an edge class is never a node)
            return CLASSES.keySet().stream().filter(c -> !c.equals("demo::Acting") && !c.equals("demo::Directing"))
                    .sorted().toList();
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

        @Override
        public void ends(String classPath, CypherToPure.PropertySink sink) {
            // firm is the forward end of Person-Firm (employees its inverse); hq a plain property holding a class
            if (classPath.equals("demo::Person")) {
                prop("firm", "demo::Firm", 0, 1).reportTo(sink);
            } else if (classPath.equals("demo::Firm")) {
                prop("hq", "demo::City", 0, 1).reportTo(sink);
            }
        }

        @Override
        public void edges(CypherToPure.EdgeSink sink) {
        }
    };

    /** The model with its edge class (the study's §5.6): Acting, of type ACTED_IN from Person to Firm in the
     *  association form (each end's inverse on the node class holds many edges), its properties role and since;
     *  Directing, the compact form (its ends its own properties, no inverse), of its simple name's type. */
    private static final CypherToPure.Model EDGES = new CypherToPure.Model() {
        @Override
        public List<String> classesOf(String label) {
            return MODEL.classesOf(label);
        }

        @Override
        public void property(String classPath, String name, CypherToPure.PropertySink sink) {
            MODEL.property(classPath, name, sink);
        }

        @Override
        public void inverse(String classPath, String property, CypherToPure.PropertySink sink) {
            MODEL.inverse(classPath, property, sink);
        }

        @Override
        public boolean isSubclass(String classPath, String superPath) {
            return MODEL.isSubclass(classPath, superPath);
        }

        @Override
        public List<String> classes() {
            return MODEL.classes();
        }

        @Override
        public void properties(String classPath, CypherToPure.PropertySink sink) {
            MODEL.properties(classPath, sink);
        }

        @Override
        public String labelOf(String classPath) {
            return MODEL.labelOf(classPath);
        }

        @Override
        public void ends(String classPath, CypherToPure.PropertySink sink) {
            MODEL.ends(classPath, sink);
        }

        @Override
        public void edges(CypherToPure.EdgeSink sink) {
            sink.edge("demo::Acting", "ACTED_IN", "actor", "demo::Person", "actings", true, "firm", "demo::Firm",
                    "cast", true);
            sink.edge("demo::Directing", "Directing", "director", "demo::Person", null, false, "firm", "demo::Firm",
                    null, false);
        }
    };

    private static String edges(String cypher) {
        return PureComposer.valueSpecification(CypherToPure.translate(cypher, EDGES), PureComposer.Style.STANDARD);
    }

    private static SqlTranslationException edgeRefusal(String cypher) {
        return assertThrows(SqlTranslationException.class, () -> edges(cypher));
    }

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
        // refused by name: a typed hop after a label-less one (its arms continue only through an untyped hop, the
        // study's §12.32; a pattern sharing no variable is a product, §12.33), a property declared nowhere
        unsupported("MATCH (n) MATCH (n)-[:firm]->(f:Firm) RETURN f.legalName", "a MATCH after a label-less pattern");
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
        unsupported("RETURN toString(datetime({year: 2017, month: 8, day: 8, hour: 12, timezone: 'Europe/Stockholm'})) AS s", "toString of a value in a named zone");
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
        unsupported("MATCH (p:Person)-[:firm]->(f:Firm)<-[:firm]-(q:Person) RETURN q.name",
                "two relationships of the type firm");
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
        assertEquals("demo::Person.all()->project(~[p:p|fromJson(('{\"@labels\":' + '[\"Person\"]') + ((if(if($p.active->isNotEmpty() && $p.active->toOne(), |true, |if($p.active->isNotEmpty() && !$p.active->toOne(), |false, |[]))->isEmpty(), |'', |',\"active\":' + if($p.active->isNotEmpty() && $p.active->toOne(), |true, |if($p.active->isNotEmpty() && !$p.active->toOne(), |false, |[]))->toOne()->toString()) + if($p.age->isEmpty(), |'', |',\"age\":' + $p.age->toOne()->toString())) + (',\"name\":' + ('\"' + $p.name->replace('\\\\', '\\\\\\\\')->replace('\"', '\\\\\"') + '\"'))) + '}')])",
                pure("MATCH (p:Person) RETURN p"));
    }

    @Test
    void aNodeThroughWithIsCarriedAsItsColumns() {
        // the node's label and properties as columns of the WITH, so ORDER BY p.age sorts a typed column and the
        // RETURN reads p.name as one; the node's own name is a virtual column
        assertEquals("demo::Person.all()->project(~[_cypher_1:p|'[\"Person\"]', _cypher_2:p|if($p.active->isNotEmpty() && $p.active->toOne(), |true, |if($p.active->isNotEmpty() && !$p.active->toOne(), |false, |[])), _cypher_3:p|$p.age, _cypher_4:p|$p.name])->sort([~_cypher_3->ascending(meta::pure::functions::relation::NullOrder.LAST)])->limit(1)->extend(~['p.name':r|$r._cypher_4, p:r|fromJson(('{\"@labels\":' + $r._cypher_1->toOne()) + ((if(if($r._cypher_2->isNotEmpty() && $r._cypher_2->toOne(), |true, |if($r._cypher_2->isNotEmpty() && !$r._cypher_2->toOne(), |false, |[]))->isEmpty(), |'', |',\"active\":' + if($r._cypher_2->isNotEmpty() && $r._cypher_2->toOne(), |true, |if($r._cypher_2->isNotEmpty() && !$r._cypher_2->toOne(), |false, |[]))->toOne()->toString()) + if($r._cypher_3->isEmpty(), |'', |',\"age\":' + $r._cypher_3->toOne()->toString())) + (',\"name\":' + ('\"' + $r._cypher_4->replace('\\\\', '\\\\\\\\')->replace('\"', '\\\\\"') + '\"'))) + '}')])->select(~['p.name', p])",
                pure("MATCH (p:Person) WITH p ORDER BY p.age LIMIT 1 RETURN p.name, p"));
    }

    @Test
    void aPatternThatFansOutReadsOneRowPerPair() {
        // the study's §12.16: no to-one root, so the first node is the root and the to-many hop (employees) fans
        // out; the graph is materialized to rows at the end of the MATCH (every node carried as its columns, the
        // fanning node's labels navigating its path so a node without a property still counts), the WHERE read over
        // those rows, and the RETURN projects them
        assertEquals("demo::Person.all()->filter(p|$p.firm->isNotEmpty() && $p.firm.employees->isNotEmpty())->project(~[_cypher_1:p|'[\"Person\"]', _cypher_2:p|if($p.active->isNotEmpty() && $p.active->toOne(), |true, |if($p.active->isNotEmpty() && !$p.active->toOne(), |false, |[])), _cypher_3:p|$p.age, _cypher_4:p|$p.name, _cypher_5:p|'[\"Firm\"]', _cypher_6:p|$p.firm.legalName, _cypher_7:p|'[\"Person\"]', _cypher_8:p|if($p.firm.employees.active->isNotEmpty() && $p.firm.employees.active->toOne(), |true, |if($p.firm.employees.active->isNotEmpty() && !$p.firm.employees.active->toOne(), |false, |[])), _cypher_9:p|$p.firm.employees.age, _cypher_10:p|$p.firm.employees.name])->filter(r|$r._cypher_9->isNotEmpty() && ($r._cypher_9->toOne() > 30))->extend(~['q.name':r|$r._cypher_10])->select(~['q.name'])",
                pure("MATCH (p:Person)-[:firm]->(f:Firm)-[:employees]->(q:Person) WHERE q.age > 30 RETURN q.name"));
    }

    @Test
    void percentilesArePuresPercentileAggregateAndAMapIsIndexedByARowValue() {
        // the study's §12.30
        assertEquals("demo::Person.all()->project(~[_cypher_1:p|$p.age])->aggregate(~[_cypher_2:x|$x._cypher_1:y|$y"
                + "->percentile(0.5, true, false)])->extend(~[d:r|if($r._cypher_2->isEmpty(), |[]->cast(@Integer),"
                + " |$r._cypher_2->toOne()->cast(@Integer))])->select(~[d])",
                pure("MATCH (p:Person) RETURN percentileDisc(p.age, 0.5) AS d"));
        // a map indexed by a value of the row: the database's choice among its entries, strict over the key
        assertEquals("['a', 'b']->flatten(~k)->extend(~[v:r|if($r.k->isEmpty(), |[], |if($r.k->toOne() == 'a', |1,"
                + " |if($r.k->toOne() == 'b', |2, |[]->cast(@Integer))))])->select(~[v])",
                pure("UNWIND ['a', 'b'] AS k RETURN {a: 1, b: 2}[k] AS v"));
        unsupported("MATCH (p:Person) RETURN percentileCont(p.age, 0.5) AS c", "percentileCont over values that are not"
                + " floats");
        invalid("MATCH (p:Person) RETURN percentileDisc(p.age, 1.5) AS d", "must be a number in the range 0.0 to 1.0");
    }

    @Test
    void aLabelPredicateReadsTheNodesLabelsAndSeveralTypesAreArms() {
        // the study's §12.35: n:Label tests the node's labels text (a constant for a known class: the database
        // folds it), null for an absent node; a relationship of several types is one arm per distinct type
        assertEquals("demo::Person.all()->project(~[a:p|'[\"Person\"]'->contains('\"Person\"'), b:p|'[\"Person\"]'->contains('\"Firm\"')])",
                pure("MATCH (p:Person) RETURN p:Person AS a, p:Firm AS b"));
        assertEquals("demo::Person.all()->project(~[a:p|if(!$p.firm->isEmpty() && '[\"Firm\"]'->contains('\"Firm\"'), |true, |if(!$p.firm->isEmpty() && !'[\"Firm\"]'->contains('\"Firm\"'), |false, |[]))])",
                pure("MATCH (p:Person) OPTIONAL MATCH (p)-[:firm]->(f:Firm) RETURN f:Firm AS a"));
        invalid("RETURN 1:Person AS a", "expected Node or Relationship but was Integer");
        // (a type spelled twice is one type: one arm, read as rows)
        String twice = pure("MATCH (p:Person)-[:firm|:firm]->(f) RETURN f.legalName AS l");
        assertTrue(twice.contains("$p.firm.legalName") && !twice.contains("->concatenate("), twice);
        assertTrue(pure("MATCH (n)-[r:firm|employees]->(x) RETURN type(r) AS t").contains("->concatenate("));
        unsupported("MATCH (a:Person), (b:Person) WHERE a = b RETURN a.name", "Neo4j compares them by identity");
    }

    @Test
    void listsOfKnownShapeOrderElementByElement() {
        // the study's §12.34: the first pair that is not equal decides (null when either is null or the two do
        // not order), the lengths when every pair is equal; a list and a value of another type do not order
        assertEquals("#TDS{\n_cypher_row\n1\n}#->extend(~[a:r|((1 == 3) && (((2 == 4) && false) || ((2 != 4) && (2 < 4)))) || ((1 != 3) && (1 < 3)), b:r|if(false, |true, |if(false, |false, |[])), c:r|((1 == 3) && (((2 == 4) && false) || ((2 != 4) && (2 < 4)))) || ((1 != 3) && (1 < 3)) == false])->select(~[a, b, c])",
                pure("RETURN [1, 2] < [3, 4] AS a, [1, 2] < true AS b, ([1, 2] < [3, 4]) IN [[3, 4], false] AS c"));
        assertEquals("demo::Person.all()->project(~[a:p|if((($p.age->isNotEmpty() && ($p.age->toOne() == 2)) && (((1 == 1) && false) || ((1 != 1) && (1 < 1)))) || (($p.age->isNotEmpty() && ($p.age->toOne() != 2)) && ($p.age->isNotEmpty() && ($p.age->toOne() < 2))), |true, |if((($p.age->isNotEmpty() && ($p.age->toOne() == 2)) && (((1 == 1) && true) || ((1 != 1) && !(1 < 1)))) || (($p.age->isNotEmpty() && ($p.age->toOne() != 2)) && ($p.age->isNotEmpty() && !($p.age->toOne() < 2))), |false, |[]))])",
                pure("MATCH (p:Person) RETURN [p.age, 1] < [2, 1] AS a"));
        unsupported("RETURN [[1]] < [[2]] AS a", "ordering lists holding lists or maps");
        unsupported("RETURN {a: 1} < {a: 2} AS a", "ordering maps");
    }

    @Test
    void aCartesianProductWithALabelLessPatternAndWithStar() {
        // the study's §12.33: WITH * keeps the graph; a pattern sharing no variable with it is a join of the two
        // patterns' rows (§12.28), a label-less one the union of its arms' rows
        assertEquals("demo::Person.all()->project(~[_cypher_1:p|'[\"Person\"]', _cypher_2:p|if($p.active->isNotEmpty() && $p.active->toOne(), |true, |if($p.active->isNotEmpty() && !$p.active->toOne(), |false, |[])), _cypher_3:p|$p.age, _cypher_4:p|$p.name])->join(demo::Firm.all()->project(~[_cypher_5:f|'[\"Firm\"]', _cypher_6:f|$f.legalName]), meta::pure::functions::relation::JoinKind.INNER, {l, r|true})->extend(~[a:r|$r._cypher_4, b:r|$r._cypher_6])->select(~[a, b])",
                pure("MATCH (p:Person) WITH * MATCH (f:Firm) RETURN p.name AS a, f.legalName AS b"));
        assertTrue(pure("MATCH (n), (m) RETURN n.name AS a, m.name AS b").contains("->join("));
        assertTrue(pure("MATCH (a) WITH a MATCH (b) RETURN a, b").contains("->join("));
        // WITH * keeps a path with the nodes and relationships it reads (one named synthetically)
        assertTrue(pure("MATCH q = (p:Person)-[:firm]->(f) WITH * MATCH (c:City) RETURN q, c.name AS n")
                .contains("@path"));
        unsupported("MATCH (n), (m) WITH n, m MATCH (n)-->(x) RETURN x", "a MATCH after a WITH that projected");
        // a list comprehension's WHERE keeps an element when its condition is true: null, like false, drops it
        assertEquals("[1, 2, 3]->flatten(~value)->extend(~[x:r|[1, 2, 3]->filter(x|$r.value->isNotEmpty() && ($x < $r.value->toOne()))->size()])->select(~[x])",
                pure("UNWIND [1, 2, 3] AS value RETURN size([x IN [1, 2, 3] WHERE x < value]) AS x"));
    }

    @Test
    void anUntypedHopFromABoundNodeIsOneArmPerWayTheModelReadsIt() {
        // the study's §12.32: after a WITH that carried the node (the graph kept), or a MATCH before; the arms
        // materialized to rows and concatenated (one way here: Person.firm)
        assertEquals("demo::Person.all()->filter(p|$p.firm->isNotEmpty())->project(~[_cypher_1:p|'[\"Person\"]', _cypher_2:p|if($p.active->isNotEmpty() && $p.active->toOne(), |true, |if($p.active->isNotEmpty() && !$p.active->toOne(), |false, |[])), _cypher_3:p|$p.age, _cypher_4:p|$p.name, _cypher_5:p|'[\"Firm\"]', _cypher_6:p|$p.firm.legalName])->extend(~[n:r|$r._cypher_4, l:r|$r._cypher_6])->select(~[n, l])",
                pure("MATCH (p:Person) WITH p MATCH (p)-->(x) RETURN p.name AS n, x.legalName AS l"));
        // the relationship's type is the way's (hq, the one end leaving Firm)
        assertEquals("demo::Firm.all()->filter(f|$f.hq->isNotEmpty())->project(~[_cypher_1:f|'[\"Firm\"]', _cypher_2:f|$f.legalName, _cypher_3:f|'[\"City\"]', _cypher_4:f|$f.hq.name, _cypher_5:f|'hq'])->extend(~[l:r|$r._cypher_2, t:r|$r._cypher_5])->select(~[l, t])",
                pure("MATCH (f:Firm) MATCH (f)-[r]->(x) RETURN f.legalName AS l, type(r) AS t"));
        unsupported("MATCH (f:Firm) WITH f.legalName AS l, f MATCH (f)-->(x) RETURN l",
                "a MATCH after a WITH that projected its variables");
        unsupported("MATCH (f:Firm)-[:employees]->(p:Person) MATCH (f)-->(p) RETURN p.name",
                "two ends are both bound already");
        // no way of the model leaves Firm to a Person (the study's §12.36): the rows in hand limited to none, x
        // an absent node (its labels column null)
        assertEquals("demo::Firm.all()->project(~[_cypher_1:f|'[\"Firm\"]', _cypher_2:f|$f.legalName])->limit(0)->extend(~[_cypher_3:r|[]->cast(@String)])->extend(~[n:r|[]])->select(~[n])",
                pure("MATCH (f:Firm) MATCH (f)-->(x:Person) RETURN x.name AS n"));
        unsupported("MATCH (f:Firm) MATCH q = (f)-->(x) RETURN q", "a named path over an untyped relationship");
    }

    @Test
    void aHopFromAnAbsentNodeFindsNothing() {
        // the study's §12.36: x, a node the OPTIONAL MATCH never finds (no `nothing` end leaves Person), carried
        // through a WITH as null everywhere: a MATCH from it reads no row (the rows in hand limited to none), b
        // absent; an OPTIONAL MATCH keeps every row, b and r absent (type(r) null)
        String none = pure("MATCH (p:Person) OPTIONAL MATCH (p)-[:nothing]->(x) WITH x, 1 AS one MATCH (x)-->(b)"
                + " RETURN b");
        assertTrue(none.contains("->limit(0)"), none);
        String kept = pure("MATCH (p:Person) OPTIONAL MATCH (p)-[:nothing]->(x) WITH x, 1 AS one OPTIONAL MATCH"
                + " (x)-[r]->(b) RETURN b, r, type(r) AS t");
        assertFalse(kept.contains("->limit(0)"), kept);
        // the graph kept by the WITH (x a bare item): the same
        assertTrue(pure("MATCH (p:Person) OPTIONAL MATCH (p)-[:nothing]->(x) WITH x MATCH (x)-->(b) RETURN b")
                .contains("->limit(0)"));
        // a null the WITH named: the path null, nodes(p) and length(p) of it null
        String path = pure("WITH null AS a OPTIONAL MATCH p = (a)-[r]->() RETURN nodes(p) AS n, length(p) AS l");
        assertTrue(path.contains("n:r|[]->cast(@meta::pure::metamodel::variant::Variant)")
                && path.contains("l:r|[]->cast(@Integer)"), path);
        // an OPTIONAL MATCH as the first clause whose pattern no way reads (no way leaves City): one null row, the
        // root too (Neo4j's OPTIONAL MATCH is the whole pattern's: nothing matched, null everywhere), over the
        // one-row TDS
        String first = pure("OPTIONAL MATCH (c:City)-[r]->(x) RETURN c, r, type(r) AS t");
        assertTrue(first.startsWith("#TDS{") && !first.contains("->limit(0)") && !first.contains("City.all()"),
                first);
        // the WHERE is still read: a variable it names must exist
        invalid("MATCH (p:Person) OPTIONAL MATCH (p)-[:nothing]->(x) WITH x, 1 AS one MATCH (x)-->(b) WHERE q.x = 1"
                + " RETURN b", "not defined");
        unsupported("MATCH (p:Person) OPTIONAL MATCH (p)-[:nothing]->(x) WITH x, 1 AS one MATCH (x)-->(x) RETURN x",
                "binding x again");
    }

    @Test
    void aPatternComprehensionIsANavigationFromTheBoundNode() {
        // the study's §12.31: size counts the hop's matches; a scalar map over one typed way is a collection the
        // database computes; an untyped hop reads every association end, each way's values joined as JSON text
        assertEquals("demo::Firm.all()->project(~[n:f|$f.legalName, c:f|$f.employees->size()])",
                pure("MATCH (f:Firm) RETURN f.legalName AS n, size([(f)-[:employees]->() | 1]) AS c"));
        assertEquals("demo::Firm.all()->project(~[names:f|$f.employees->filter(_cypher_1|$_cypher_1.age->isNotEmpty() && ($_cypher_1.age->toOne() > 30))->map(_cypher_1|$_cypher_1.name)->toVariant()])",
                pure("MATCH (f:Firm) RETURN [(f)-[:employees]->(e) WHERE e.age > 30 | e.name] AS names"));
        // a to-one way (firm) is read through its path under a presence guard, never a map the database folds
        assertEquals("demo::Person.all()->project(~[l:p|fromJson('[' + if($p.firm->isNotEmpty(), |',' + ('\"' + $p.firm.legalName->replace('\\\\', '\\\\\\\\')->replace('\"', '\\\\\"') + '\"'), |'')->substring(2) + ']')])",
                pure("MATCH (p:Person) RETURN [(p)-->(x) | x.legalName] AS l"));
        assertEquals("demo::Firm.all()->project(~[c:f|if($f.hq->isNotEmpty(), |1, |0) + $f.employees->size()])",
                pure("MATCH (f:Firm) RETURN size([(f)--() | 1]) AS c"));
        unsupported("MATCH (f:Firm) WITH f.legalName AS n RETURN [(f)-[:employees]->() | 1] AS l",
                "a pattern comprehension over rows");
        unsupported("MATCH (f:Firm)-[:employees]->(p:Person) RETURN [(f)-[:employees]->(p) | 1] AS l",
                "whose two ends are both bound");
        unsupported("MATCH (f:Firm) RETURN [(f)-[:employees]->()-[:firm]->() | 1] AS l",
                "over more than one hop");
        unsupported("MATCH (f:Firm) RETURN [(f)-[:employees*1..2]->() | 1] AS l", "variable-length");
        // the lowering maps the navigated instances alone: over a relationship holding many, the expression (or
        // the path, whose start is the graph's node) may not read a node outside the pattern
        unsupported("MATCH (f:Firm) RETURN [(f)-[:employees]->(e) | f.legalName + e.name] AS l",
                "reads a node outside its pattern");
        unsupported("MATCH (f:Firm) RETURN [p = (f)-[:employees]->(e) | p] AS l", "reads a node outside its pattern");
    }

    @Test
    void anOptionalMatchsWhereGuardsWhatThePatternBound() {
        // the study's §12.29: the optional node absent where its hop finds none or the WHERE does not hold; its
        // property null there, its value null there
        assertEquals("demo::Person.all()->project(~[n:p|$p.name, l:p|if($p.firm->isEmpty() || !($p.firm.legalName->isNotEmpty() && ($p.firm.legalName->toOne() == 'x')), |[]->cast(@String), |$p.firm.legalName), f:p|if($p.firm->isEmpty() || !($p.firm.legalName->isNotEmpty() && ($p.firm.legalName->toOne() == 'x')), |[]->cast(@meta::pure::metamodel::variant::Variant), |fromJson(('{\"@labels\":' + '[\"Firm\"]') + if(if($p.firm->isEmpty() || !($p.firm.legalName->isNotEmpty() && ($p.firm.legalName->toOne() == 'x')), |[]->cast(@String), |$p.firm.legalName)->isEmpty(), |'', |',\"legalName\":' + ('\"' + if($p.firm->isEmpty() || !($p.firm.legalName->isNotEmpty() && ($p.firm.legalName->toOne() == 'x')), |[]->cast(@String), |$p.firm.legalName)->toOne()->replace('\\\\', '\\\\\\\\')->replace('\"', '\\\\\"') + '\"')) + '}'))])",
                pure("MATCH (p:Person) OPTIONAL MATCH (p)-[:firm]->(f:Firm) WHERE f.legalName = 'x'"
                        + " RETURN p.name AS n, f.legalName AS l, f"));
        unsupported("MATCH (f:Firm) OPTIONAL MATCH (f)-[:employees]->(p:Person) WHERE p.age > 1 RETURN p.name",
                "OPTIONAL MATCH with a WHERE over a hop that fans out");
    }

    @Test
    void aPatternSharingNoVariableIsEveryPairOfRows() {
        // the study's §12.28: each pattern read as rows, the rows joined on a condition that always holds, the WHERE
        // over the pairs
        assertEquals("demo::Person.all()->project(~[_cypher_1:p|'[\"Person\"]', _cypher_2:p|if($p.active->isNotEmpty() && $p.active->toOne(), |true, |if($p.active->isNotEmpty() && !$p.active->toOne(), |false, |[])), _cypher_3:p|$p.age, _cypher_4:p|$p.name])->join(demo::Firm.all()->project(~[_cypher_5:f|'[\"Firm\"]', _cypher_6:f|$f.legalName]), meta::pure::functions::relation::JoinKind.INNER, {l, r|true})->filter(r|$r._cypher_4 == $r._cypher_6)->extend(~[n:r|$r._cypher_4, l:r|$r._cypher_6])->select(~[n, l])",
                pure("MATCH (p:Person), (f:Firm) WHERE p.name = f.legalName RETURN p.name AS n, f.legalName AS l"));
    }

    @Test
    void aNamedPathIsItsNodesAndRelationshipsWithTheirStoredDirections() {
        // the study's §12.27: the path's JSON (nodes and relationships alternating, each relationship's stored
        // direction beside them) built as text by the database; length(p) spelled from the pattern, nodes(p) and
        // relationships(p) lists of the values; a path written against the stored direction prints it backward
        assertEquals("demo::Person.all()->filter(p|$p.firm->isNotEmpty())->project(~[pa:p|fromJson(((('{\"@path\":[' + (('{\"@labels\":' + '[\"Person\"]') + ((if(if($p.active->isNotEmpty() && $p.active->toOne(), |true, |if($p.active->isNotEmpty() && !$p.active->toOne(), |false, |[]))->isEmpty(), |'', |',\"active\":' + if($p.active->isNotEmpty() && $p.active->toOne(), |true, |if($p.active->isNotEmpty() && !$p.active->toOne(), |false, |[]))->toOne()->toString()) + if($p.age->isEmpty(), |'', |',\"age\":' + $p.age->toOne()->toString())) + (',\"name\":' + ('\"' + $p.name->replace('\\\\', '\\\\\\\\')->replace('\"', '\\\\\"') + '\"'))) + '}')) + ',{\"@type\":\"firm\"},') + (('{\"@labels\":' + '[\"Firm\"]') + if($p.firm.legalName->isEmpty(), |'', |',\"legalName\":' + ('\"' + $p.firm.legalName->toOne()->replace('\\\\', '\\\\\\\\')->replace('\"', '\\\\\"') + '\"')) + '}')) + '],\"@dirs\":[true]}')])",
                pure("MATCH pa = (p:Person)-[:firm]->(f:Firm) RETURN pa"));
        // the nodes in the written order (the root is the Person, the pattern starts at the Firm)
        assertEquals("demo::Person.all()->filter(q|$q.firm->isNotEmpty())->project(~[len:q|1, ns:q|fromJson((('[' + fromJson(('{\"@labels\":' + '[\"Firm\"]') + if($q.firm.legalName->isEmpty(), |'', |',\"legalName\":' + ('\"' + $q.firm.legalName->toOne()->replace('\\\\', '\\\\\\\\')->replace('\"', '\\\\\"') + '\"')) + '}')->toString()) + (',' + fromJson(('{\"@labels\":' + '[\"Person\"]') + ((if(if($q.active->isNotEmpty() && $q.active->toOne(), |true, |if($q.active->isNotEmpty() && !$q.active->toOne(), |false, |[]))->isEmpty(), |'', |',\"active\":' + if($q.active->isNotEmpty() && $q.active->toOne(), |true, |if($q.active->isNotEmpty() && !$q.active->toOne(), |false, |[]))->toOne()->toString()) + if($q.age->isEmpty(), |'', |',\"age\":' + $q.age->toOne()->toString())) + (',\"name\":' + ('\"' + $q.name->replace('\\\\', '\\\\\\\\')->replace('\"', '\\\\\"') + '\"'))) + '}')->toString())) + ']'), rs:q|fromJson(('[' + fromJson('{\"@type\":\"firm\"}')->toString()) + ']')])",
                pure("MATCH pa = (f:Firm)<-[:firm]-(q:Person) RETURN length(pa) AS len, nodes(pa) AS ns,"
                        + " relationships(pa) AS rs"));
        assertEquals("demo::Person.all()->project(~[pa:q|fromJson(('{\"@path\":[' + (('{\"@labels\":' + '[\"Person\"]') + ((if(if($q.active->isNotEmpty() && $q.active->toOne(), |true, |if($q.active->isNotEmpty() && !$q.active->toOne(), |false, |[]))->isEmpty(), |'', |',\"active\":' + if($q.active->isNotEmpty() && $q.active->toOne(), |true, |if($q.active->isNotEmpty() && !$q.active->toOne(), |false, |[]))->toOne()->toString()) + if($q.age->isEmpty(), |'', |',\"age\":' + $q.age->toOne()->toString())) + (',\"name\":' + ('\"' + $q.name->replace('\\\\', '\\\\\\\\')->replace('\"', '\\\\\"') + '\"'))) + '}')) + '],\"@dirs\":[]}')])",
                pure("MATCH pa = (q:Person) RETURN pa"));
        invalid("MATCH p = (p:Person)-[:firm]->(f:Firm) RETURN p", "Variable `p` already declared");
    }

    @Test
    void keysOfANodeAreTheNamesOfItsPropertiesThatHoldAValue() {
        // the study's §12.26: the names' JSON array text (each where its value is present), read back as strings;
        // a relationship holds none; RETURN p ORDER BY p.name with a LIMIT reads the pattern's property
        assertEquals("demo::Person.all()->project(~[n:p|fromJson('[' + substring((if(if($p.active->isNotEmpty() && $p.active->toOne(), |true, |if($p.active->isNotEmpty() && !$p.active->toOne(), |false, |[]))->isEmpty(), |'', |',\"active\"') + if($p.age->isEmpty(), |'', |',\"age\"')) + ',\"name\"', 2) + ']')->toMany(@String)->size(), a:p|'age'->in(fromJson('[' + substring((if(if($p.active->isNotEmpty() && $p.active->toOne(), |true, |if($p.active->isNotEmpty() && !$p.active->toOne(), |false, |[]))->isEmpty(), |'', |',\"active\"') + if($p.age->isEmpty(), |'', |',\"age\"')) + ',\"name\"', 2) + ']')->toMany(@String))])",
                pure("MATCH (p:Person) RETURN size(keys(p)) AS n, 'age' IN keys(p) AS a"));
        assertEquals("demo::Person.all()->filter(p|$p.firm->isNotEmpty())->project(~[n:p|fromJson('[]')->toMany(@String)->size()])",
                pure("MATCH (p:Person)-[r:firm]->(f:Firm) RETURN size(keys(r)) AS n"));
        assertEquals("demo::Person.all()->project(~[p:p|fromJson(('{\"@labels\":' + '[\"Person\"]') + ((if(if($p.active->isNotEmpty() && $p.active->toOne(), |true, |if($p.active->isNotEmpty() && !$p.active->toOne(), |false, |[]))->isEmpty(), |'', |',\"active\":' + if($p.active->isNotEmpty() && $p.active->toOne(), |true, |if($p.active->isNotEmpty() && !$p.active->toOne(), |false, |[]))->toOne()->toString()) + if($p.age->isEmpty(), |'', |',\"age\":' + $p.age->toOne()->toString())) + (',\"name\":' + ('\"' + $p.name->replace('\\\\', '\\\\\\\\')->replace('\"', '\\\\\"') + '\"'))) + '}'), _cypher_1:p|$p.name])->sort([~_cypher_1->ascending(meta::pure::functions::relation::NullOrder.LAST)])->select(~[p])->limit(1)",
                pure("MATCH (p:Person) RETURN p ORDER BY p.name LIMIT 1"));
    }

    @Test
    void aShapeOfRowValuesIsItsJsonTextBuiltByTheDatabase() {
        // the study's §12.25: each element spelled by its kind (null for an empty one, a string escaped, a whole
        // float with its .0), a map's entries under their keys, a nested shape within; read as a Variant
        assertEquals("demo::Person.all()->project(~[l:p|fromJson(('[' + if($p.age->isEmpty(), |'null', |$p.age->toOne()->toString())) + ']')])",
                pure("MATCH (p:Person) RETURN [p.age] AS l"));
        assertEquals("demo::Person.all()->project(~[l:p|fromJson((((('[' + if($p.age->isEmpty(), |'null', |$p.age->toOne()->toString())) + (',' + ('\"' + $p.name->replace('\\\\', '\\\\\\\\')->replace('\"', '\\\\\"') + '\"'))) + (',' + if(if($p.age->isNotEmpty() && ($p.age->toOne() > 1), |true, |if($p.age->isNotEmpty() && !($p.age->toOne() > 1), |false, |[]))->isEmpty(), |'null', |if($p.age->isNotEmpty() && ($p.age->toOne() > 1), |true, |if($p.age->isNotEmpty() && !($p.age->toOne() > 1), |false, |[]))->toOne()->toString()))) + (',' + if(toFloat(2.5)->toString()->contains('.') || toFloat(2.5)->toString()->contains('e'), |toFloat(2.5)->toString(), |toFloat(2.5)->toString() + '.0'))) + ']')])",
                pure("MATCH (p:Person) RETURN [p.age, p.name, p.age > 1, 2.5] AS l"));
        assertEquals("demo::Person.all()->project(~[m:p|fromJson((('{' + '\"age\":' + if($p.age->isEmpty(), |'null', |$p.age->toOne()->toString())) + ',\"inner\":' + (('[' + ('\"' + $p.name->replace('\\\\', '\\\\\\\\')->replace('\"', '\\\\\"') + '\"')) + ']')) + '}')])",
                pure("MATCH (p:Person) RETURN {age: p.age, inner: [p.name]} AS m"));
    }

    @Test
    void anExpressionAroundAggregatesIsComputedAfterTheGrouping() {
        // the study's §12.24: each aggregate a hidden column of the grouping, the expression extended over the
        // finished aggregates (a count of nothing is 0), the items alone selected; an ORDER BY aggregate beyond the
        // items a hidden aggregate of the grouping too, read by the sort and dropped after it
        assertEquals("demo::Person.all()->project(~[_cypher_2:p|1, _cypher_4:p|1])->aggregate(~[_cypher_1:x|$x._cypher_2:y|$y->count(), _cypher_3:x|$x._cypher_4:y|$y->count()])->extend(~[c:r|$r._cypher_1 * 10, neg:r|1 - $r._cypher_3])->select(~[c, neg])",
                pure("MATCH (p:Person) RETURN count(*) * 10 AS c, 1 - count(p) AS neg"));
        // a sum of nothing is 0: the raw sum finished first, the expression over the finished one
        assertEquals("demo::Person.all()->project(~[name:p|$p.name, _cypher_2:p|$p.age])->groupBy(~[name], ~[_cypher_3:x|$x._cypher_2:y|$y->sum()])->extend(~[_cypher_1:r|if($r._cypher_3->isEmpty(), |0, |$r._cypher_3->toOne())])->extend(~[twice:r|$r._cypher_1 * 2])->select(~[name, twice])->sort([~name->ascending(meta::pure::functions::relation::NullOrder.LAST)])",
                pure("MATCH (p:Person) RETURN p.name AS name, sum(p.age) * 2 AS twice ORDER BY name"));
        // the sort's aggregate beyond the items: hidden, kept past the grouping's select, dropped after the sort
        assertEquals("demo::Person.all()->project(~[name:p|$p.name, _cypher_1:p|$p.age, _cypher_3:p|$p.age])->groupBy(~[name], ~[min:x|$x._cypher_1:y|$y->min(), _cypher_5:x|$x._cypher_3:y|$y->sum()])->extend(~[_cypher_2:r|if($r._cypher_5->isEmpty(), |0, |$r._cypher_5->toOne())])->select(~[name, min, _cypher_2])->extend(~[_cypher_4:r|$r._cypher_2])->sort([~_cypher_4->ascending(meta::pure::functions::relation::NullOrder.LAST)])->select(~[name, min])->select(~[name])",
                pure("MATCH (p:Person) WITH p.name AS name, min(p.age) AS min ORDER BY sum(p.age) RETURN name"));
        // the sort re-spelling an item's aggregate reads its column
        assertEquals("demo::Person.all()->project(~[_cypher_1:p|$p.age])->aggregate(~[a:x|$x._cypher_1:y|$y->average()])->select(~[a])->extend(~[_cypher_2:r|if($r.a->isEmpty(), |[], |1 + $r.a->toOne())])->sort([~_cypher_2->ascending(meta::pure::functions::relation::NullOrder.LAST)])->select(~[a])",
                pure("MATCH (p:Person) RETURN avg(p.age) AS a ORDER BY 1 + avg(p.age)"));
    }

    @Test
    void aRelationshipIsItsType() {
        // the study's §12.19: a relationship as a value is its type's JSON (the model holds no relationship
        // property); type(r) its type; carried through WITH as its type's column
        assertEquals("demo::Person.all()->filter(p|$p.firm->isNotEmpty())->project(~[_cypher_1:p|'[\"Person\"]', _cypher_2:p|if($p.active->isNotEmpty() && $p.active->toOne(), |true, |if($p.active->isNotEmpty() && !$p.active->toOne(), |false, |[])), _cypher_3:p|$p.age, _cypher_4:p|$p.name, _cypher_5:p|'firm'])->sort([~_cypher_4->ascending(meta::pure::functions::relation::NullOrder.LAST)])->limit(1)->extend(~[r:r|fromJson('{\"@type\":\"' + $r._cypher_5->toOne() + '\"}'), t:r|$r._cypher_5])->select(~[r, t])",
                pure("MATCH (p:Person)-[r:firm]->(f:Firm) WITH p, r ORDER BY p.name LIMIT 1 RETURN r, type(r) AS t"));
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
        unsupported("MATCH (p:Person)-[r:firm]->(f) RETURN r.since", "a property of a relationship");
        unsupported("MATCH (p:Person) RETURN p.firm", "a node as a value is not supported yet");
        unsupported("MATCH (p:Person) RETURN p.tags", "holds many values (a list)");
        unsupported("MATCH (f:Firm)-[:hq]->(c:City)<-[:hq]-(g:Firm) RETURN g.legalName", "no inverse");
        unsupported("MATCH (p:Person)-[:firm]->(f:Firm)<-[:firm]-(q:Person) RETURN q.name",
                "two relationships of the type firm");
        unsupported("MATCH (p:Person)-[:firm*1..2]->(f) RETURN f.legalName", "variable-length");
        unsupported("MATCH (p:Person)-[:firm {since: 1}]->(f) RETURN f.legalName", "properties on a relationship");
        unsupported("MATCH (p:Person) WITH p.name AS n MATCH (f:Firm) RETURN n", "a MATCH after a WITH");
        unsupported("CREATE (p:Person {name: 'x'})", "CREATE is not supported yet");
        unsupported("MATCH (p:Person) RETURN collect(p.name)", "collect(...) is not supported yet");
        unsupported("MATCH (p:Person) RETURN count(DISTINCT p.name)", "count(DISTINCT ...)");
        unsupported("MATCH (p:Person) WHERE p.name = $name RETURN p.name", "a parameter ($name)");
        unsupported("MATCH (p:Person) WHERE p.name =~ 'A.*' RETURN p.name", "regular expression");
        unsupported("MATCH (p:Person) WITH [p.age] AS l RETURN l", "holding a value of the row (l), carried");
        unsupported("MATCH (p:Person) RETURN [localtime('12:00')] AS l", "a list or map holding a LocalTime, as a value");
        invalid("MATCH (p:Person) RETURN all(x IN p.name WHERE x > 1)", "expected List<T> but was String");
        unsupported("USE demo::Space MATCH (p:Person) RETURN p.name", "USE");
        unsupported("MATCH (p:Person) RETURN DISTINCT p", "RETURN DISTINCT over a node (p");
        unsupported("MATCH (p:Person) RETURN p ORDER BY p", "ORDER BY a node (p");
        unsupported("MATCH (p:Person) RETURN p, count(*) AS c", "a node among the grouping keys (p");
        unsupported("MATCH (p:Person:Firm) RETURN p.name", "several labels");
        invalid("MATCH (p:Person) RETURN p.age + count(*)", "implicit grouping expressions (p in p.age + count(*))");
        unsupported("RETURN 1e308 AS x", "a float literal beyond 1e15");
        unsupported("RETURN 1e-20 AS x", "a float literal beyond 1e15");
    }

    // ------------------------------------------------------------------ edge classes (the study's §5.6)

    @Test
    void anEdgeHopNavigatesTheInverseEndThenTheFarEnd() {
        String pure = edges("MATCH (p:Person)-[r:ACTED_IN]->(f:Firm) RETURN r.role AS role, f.legalName AS firm");
        assertTrue(pure.contains("$p.actings.role"), pure);
        assertTrue(pure.contains("$p.actings.firm.legalName"), pure);
        assertTrue(pure.contains("$p.actings.firm->isNotEmpty()"), pure);
    }

    @Test
    void anIncomingEdgeHopNavigatesTheToEndsInverse() {
        String pure = edges("MATCH (f:Firm)<-[r:ACTED_IN]-(p:Person) RETURN r.role AS role, p.name AS name");
        assertTrue(pure.contains("$f.cast.role"), pure);
        assertTrue(pure.contains("$f.cast.actor.name"), pure);
    }

    @Test
    void anEdgeAsAValueCarriesItsTypeAndProperties() {
        String pure = edges("MATCH (p:Person)-[r:ACTED_IN]->(:Firm) RETURN r, type(r) AS t, properties(r) AS ps,"
                + " keys(r) AS ks");
        // (the graph fans out: the relationship is carried as its type's and its properties' columns, and its
        // value is rebuilt from them)
        assertTrue(pure.contains("'{\"@type\":' + ('\"' + $r._cypher_6->toOne()"), pure);
        assertTrue(pure.contains(",\"role\":"), pure);
        assertTrue(pure.contains(",\"since\":"), pure);
        assertTrue(pure.contains("'ACTED_IN'"), pure);
    }

    @Test
    void anEdgesPropertyMapFiltersTheEdgeInstance() {
        String pure = edges("MATCH (p:Person)-[:ACTED_IN {role: 'Neo'}]->(f:Firm) RETURN f.legalName AS firm");
        assertTrue(pure.contains("'Neo'"), pure);
        assertTrue(pure.contains("$p.actings.role"), pure);
    }

    @Test
    void anUntypedHopRefusesAnEdgeEndWithoutAnInverse() {
        // (the review's second finding: an edge the node cannot navigate to is refused wherever it is a way, never
        // skipped; Directing's from end is its own property)
        SqlTranslationException e = edgeRefusal("MATCH (p:Person)-->(x) RETURN labels(x) AS l");
        assertTrue(e.getMessage().contains("holds its from end director with no inverse end"), e.getMessage());
        SqlTranslationException pred = edgeRefusal("MATCH (p:Person) WHERE (p)-->() RETURN p.name");
        assertTrue(pred.getMessage().contains("holds its from end director with no inverse end"), pred.getMessage());
    }

    @Test
    void aPatternPredicateBindsNoNewVariableAndStandsWhereABooleanIsTested() {
        SqlTranslationException fresh = refusal("MATCH (p:Person) WHERE (p)-[r:firm]->(f) RETURN p.name");
        assertEquals(SqlTranslationException.Kind.INVALID, fresh.kind(), fresh.getMessage());
        assertTrue(fresh.getMessage().contains("PatternExpressions are not allowed to introduce new variables: 'r'"),
                fresh.getMessage());
        SqlTranslationException value = refusal("MATCH (p:Person) WHERE toString((p)-[:firm]->()) = 'true' RETURN p.name");
        assertTrue(value.getMessage().contains("A pattern expression should only be used"), value.getMessage());
        // (a quantifier's predicate is a boolean context too: the pattern is allowed there and refused by name only
        // because the quantifier's scope is not the graph's, never as Neo4j's rejection)
        SqlTranslationException quantified = refusal("MATCH (p:Person) RETURN any(x IN [1] WHERE (p)-[:firm]->()) AS a");
        assertEquals(SqlTranslationException.Kind.UNSUPPORTED, quantified.kind(), quantified.getMessage());
        assertTrue(quantified.getMessage().contains("over rows"), quantified.getMessage());
        String negated = pure("MATCH (p:Person) WHERE NOT ((p)-[:firm]->() OR p.age > 1) RETURN p.name");
        assertTrue(negated.contains("$p.firm->isNotEmpty()"), negated);
    }

    @Test
    void aLabelledStartInAPredicateIsTheBoundNodesClassOrNothing() {
        // (the review's third finding: the start node's label is read)
        String nothing = pure("MATCH (p:Person) WHERE (p:Firm)-[:firm]->() RETURN p.name");
        assertTrue(nothing.contains("filter(p|false)"), nothing);
        String same = pure("MATCH (p:Person) WHERE (p:Person)-[:firm]->() RETURN p.name");
        assertTrue(same.contains("$p.firm->isNotEmpty()"), same);
    }

    @Test
    void edgeRefusalsAreNamed() {
        SqlTranslationException label = edgeRefusal("MATCH (a:Acting) RETURN a");
        assertEquals(SqlTranslationException.Kind.INVALID, label.kind(), label.getMessage());
        assertTrue(label.getMessage().contains("names the edge class demo::Acting"), label.getMessage());
        SqlTranslationException compact = edgeRefusal("MATCH (p:Person)-[r:Directing]->(f:Firm) RETURN r");
        assertEquals(SqlTranslationException.Kind.UNSUPPORTED, compact.kind(), compact.getMessage());
        assertTrue(compact.getMessage().contains("holds its from end director with no inverse end on demo::Person"),
                compact.getMessage());
        SqlTranslationException past = edgeRefusal("MATCH (p:Person)-[:ACTED_IN]->(f:Firm)-[:hq]->(c:City) RETURN c");
        assertTrue(past.getMessage().contains("a hop from f, a node reached through an edge class"), past.getMessage());
        SqlTranslationException wrongWay = edgeRefusal("MATCH (f:Firm)-[r:ACTED_IN]->(p:Person) RETURN r");
        assertEquals(SqlTranslationException.Kind.INVALID, wrongWay.kind(), wrongWay.getMessage());
        assertTrue(wrongWay.getMessage().contains("which no pattern from demo::Firm reads outgoing"),
                wrongWay.getMessage());
    }
}
