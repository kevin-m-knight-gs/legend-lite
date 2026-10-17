// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend.exec;

import com.legend.Compiler;
import com.legend.TypedQuery;
import com.legend.executionplan.ExecutionPlan;
import com.legend.model.ConnectionDefinition.DatabaseType;
import com.legend.testing.EmbeddedMongoDb;
import org.junit.jupiter.api.Test;

import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The MongoDB line's homework 3 (2026-10-17, docs/MONGODB_PLAN_2026_10_16.md §9): a differential in miniature. Each
 * case is a storeless Pure expression shaped like the PCT suites' (literal relations, filters, sorts, groups, joins,
 * unions, string, date, number and list functions), planned to the CSV wire on DuckDB (the platform's reference) and
 * on MongoDB, run on both, and the two texts compared. The verdicts -- EQUAL, DIFFERENT, REFUSED (a named refusal
 * at render), ERROR (the server's) -- are written to the test's outputs ({@code mongodb-differential.tsv}) and
 * printed; the test fails only when the reference does not run. It measures; it pins nothing.
 */
class MongoDbDifferentialTest {

    private static final String PASSWORD_PROPERTY = "legend.mongodb.pct.password";

    private record Case(String name, String expression, TypedQuery.Output output) {
        Case(String name, String expression) {
            this(name, expression, TypedQuery.Output.CSV);
        }
    }

    private static final String PEOPLE = "#TDS\n id, name, age, city\n 1, Ann, 34, Oslo\n 2, Bob, 28, Oslo\n"
            + " 3, Cy, 41, Rome\n#";
    private static final String CITIES = "#TDS\n town, country\n Oslo, NO\n Paris, FR\n#";

    private static final List<Case> CASES = List.of(
            new Case("plus", "1 + 2"),
            new Case("string_concat", "'a' + 'b'"),
            new Case("float_divide", "7 / 2"),
            new Case("float_text", "2.0->toString()"),
            new Case("float_texts", "#TDS\n w\n 1.5\n -2.0\n 0.0\n 100.0\n 0.25\n#->extend(~t: x | $x.w->toString())"
                    + "->select(~t)"),
            new Case("float_big", "1.0e20->toString()"),
            new Case("float_small", "1.5e-8->toString()"),
            new Case("float_neg", "(0.0 - 2.0)->toString()"),
            new Case("float_sum", "(0.1 + 0.2)->toString()"),
            new Case("float_third", "(1.0 / 3.0)->toString()"),
            new Case("float_sixteen", "(1.0e16 + 2.0)->toString()"),
            new Case("float_column", "#TDS\n id, w\n 1, 1.5\n 2, 2.0\n 3, -3.0\n#->select(~w)"),
            new Case("float_avg", PEOPLE + "->groupBy(~city, ~a: x | $x.age : y | $y->average())"
                    + "->sort(~city->ascending())"),
            new Case("list_sum", "[1, 2, 3]->plus()"),
            new Case("list_sort", "[3, 1, 2]->sort()"),
            new Case("list_map", "[1, 2, 3]->map(x | $x * 2)"),
            new Case("list_filter", "[1, 2, 3]->filter(x | $x > 1)"),
            new Case("list_fold", "[1, 2, 3]->fold({x, a | $a + $x}, 0)"),
            new Case("list_join", "['a', 'b']->joinStrings(',')"),
            new Case("string_functions", "'Hello'->toUpper() + 'Hello'->toLower() + 'Hello'->length()->toString()"
                    + " + 'Hello'->substring(1, 3) + 'Hello'->replace('l', 'L') + 'Hello'->reverseString()"),
            new Case("string_predicates", "['Hello'->startsWith('He'), 'Hello'->endsWith('lo'), 'Hello'->contains('ell'),"
                    + " 'Hello'->indexOf('l') == 2]"),
            new Case("string_split", "'a,b,c'->split(',')"),
            new Case("date_parts", "[%2020-03-15->year(), %2020-03-15->monthNumber(), %2020-03-15->dayOfMonth()]"),
            new Case("date_adjust", "%2020-03-15->adjust(20, DurationUnit.DAYS)"),
            new Case("date_time_hour", "%2020-03-15T10:20:30->hour()"),
            new Case("date_compare", "%2020-01-01 < %2020-02-01"),
            new Case("date_diff", "%2020-01-01->dateDiff(%2020-03-01, DurationUnit.DAYS)"),
            new Case("math_int", "[10->abs(), -3->abs(), 2.5->round(), 2.4->floor(), 2.4->ceiling()]"),
            new Case("math_float", "[16->sqrt(), 2.0->pow(10.0), 2.5->abs()]"),
            new Case("math_rem", "[7->rem(3), 7->mod(3), -7->rem(3), -7->mod(3)]"),
            new Case("rem_int", "12->rem(5)"),
            new Case("format_bool", "format('a %s b %s', ['fox', false])"),
            new Case("format_float", "format('%f|%f', [2.0, 1.5e-8])"),
            new Case("fold_list", "[1, 2, 3]->fold({a, b | $b->add($a)}, [])"),
            new Case("fold_one", "[1]->fold({a, b | $a + $b}, 0)"),
            new Case("tds_rem", "12->rem(5)", TypedQuery.Output.TDS_TEXT),
            new Case("tds_format_bool", "format('a %s b %s', ['fox', false])", TypedQuery.Output.TDS_TEXT),
            new Case("tds_fold_list", "[1, 2, 3]->fold({a, b | $b->add($a)}, [])", TypedQuery.Output.TDS_TEXT),
            new Case("tds_strings", "['a', '345', 'true', ' x ', '']", TypedQuery.Output.TDS_TEXT),
            new Case("if_then", "if(1 > 2, |'big', |'small')"),
            new Case("filter_select", PEOPLE + "->filter(x | $x.age > 30)->select(~[name, age])->sort(~name->ascending())"),
            new Case("sort_desc_limit", PEOPLE + "->sort(~age->descending())->limit(2)->select(~name)"),
            new Case("drop_slice", PEOPLE + "->sort(~id->ascending())->drop(1)->slice(0, 1)->select(~name)"),
            new Case("extend_strings", PEOPLE + "->extend(~u: x | $x.name->toUpper())->extend(~l: x | $x.name->length())"
                    + "->select(~[u, l])->sort(~u->ascending())"),
            new Case("extend_if_null", PEOPLE + "->extend(~n: x | if($x.age > 30, |$x.age, |[]))"
                    + "->select(~[name, n])->sort(~n->ascending())"),
            new Case("filter_empty", PEOPLE + "->extend(~n: x | if($x.age > 30, |$x.age, |[]))"
                    + "->filter(x | $x.n->isEmpty())->select(~name)"),
            new Case("in_list", PEOPLE + "->filter(x | $x.city->in(['Oslo', 'Nice']))->select(~name)->sort(~name->ascending())"),
            new Case("group_by", PEOPLE + "->groupBy(~city, ~[n: x | $x.name : y | $y->count(), s: x | $x.age : y | $y->plus(),"
                    + " a: x | $x.age : y | $y->average(), mx: x | $x.age : y | $y->max()])->sort(~city->ascending())"),
            new Case("group_join_strings", PEOPLE + "->groupBy(~city, ~[names: x | $x.name : y | $y->joinStrings(',')])"
                    + "->sort(~city->ascending())"),
            new Case("aggregate_all", PEOPLE + "->aggregate(~[n: x | $x.name : y | $y->count(), s: x | $x.age : y | $y->plus()])"),
            new Case("aggregate_none", PEOPLE + "->filter(x | $x.age > 100)->aggregate(~[n: x | $x.name : y | $y->count()])"),
            new Case("size_none", PEOPLE + "->filter(x | $x.age > 100)->size()"),
            new Case("size_some", PEOPLE + "->filter(x | $x.age > 30)->size()"),
            new Case("sum_all_null", PEOPLE + "->extend(~v: x | if($x.age > 100, |$x.age, |[]))"
                    + "->groupBy(~city, ~s: x | $x.v : y | $y->plus())->sort(~city->ascending())"),
            new Case("json_numbers", PEOPLE + "->extend(~h: x | $x.age / 2)->select(~[name, age, h])"
                    + "->sort(~name->ascending())", TypedQuery.Output.JSON),
            new Case("json_dates", PEOPLE + "->extend(~d: x | %2020-01-15->adjust($x.id, DurationUnit.DAYS))"
                    + "->extend(~t: x | %2020-01-15T10:20:30->adjust($x.id, DurationUnit.HOURS))->select(~[name, d, t])"
                    + "->sort(~name->ascending())", TypedQuery.Output.JSON),
            // Pure's TDS text (TypedQuery.Output.TDS_TEXT, the PCT harness's form on the plan path, 2026-10-17)
            new Case("tds_relation", PEOPLE + "->filter(x | $x.age > 30)->select(~[name, age, city])"
                    + "->sort(~name->ascending())", TypedQuery.Output.TDS_TEXT),
            new Case("tds_group", PEOPLE + "->groupBy(~city, ~[n: x | $x.name : y | $y->count(), a: x | $x.age : y | $y->average()])"
                    + "->sort(~city->ascending())", TypedQuery.Output.TDS_TEXT),
            new Case("tds_scalar_float", "7 / 2", TypedQuery.Output.TDS_TEXT),
            new Case("tds_collection", "['a,b', 'c\\'d', 'e']", TypedQuery.Output.TDS_TEXT),
            new Case("tds_dates", PEOPLE + "->extend(~d: x | %2020-01-15->adjust($x.id, DurationUnit.DAYS))"
                    + "->extend(~t: x | %2020-01-15T10:20:30->adjust($x.id, DurationUnit.HOURS))->select(~[name, d, t])"
                    + "->sort(~name->ascending())", TypedQuery.Output.TDS_TEXT),
            new Case("tds_date_value", "%2020-01-15T10:20:30.123->adjust(1, DurationUnit.MINUTES)", TypedQuery.Output.TDS_TEXT),
            new Case("tds_empty", PEOPLE + "->filter(x | $x.age > 100)->select(~[name])", TypedQuery.Output.TDS_TEXT),
            new Case("tds_null_cells", PEOPLE + "->extend(~n: x | if($x.age > 30, |$x.age, |[]))->select(~[name, n])"
                    + "->sort(~name->ascending())", TypedQuery.Output.TDS_TEXT),
            new Case("distinct", PEOPLE + "->select(~city)->distinct()->sort(~city->ascending())"),
            new Case("inner_join", PEOPLE + "->join(" + CITIES + ", JoinKind.INNER, {a, b | $a.city == $b.town})"
                    + "->select(~[name, country])->sort(~name->ascending())"),
            new Case("left_join", PEOPLE + "->join(" + CITIES + ", JoinKind.LEFT, {a, b | $a.city == $b.town})"
                    + "->select(~[name, country])->sort(~name->ascending())"),
            new Case("concatenate", PEOPLE + "->select(~name)->concatenate(" + PEOPLE + "->select(~name))"
                    + "->sort(~name->ascending())"),
            new Case("rename", PEOPLE + "->rename(~name, ~who)->select(~who)->sort(~who->ascending())"),
            new Case("date_column", PEOPLE + "->extend(~d: x | %2020-01-15->adjust($x.id, DurationUnit.DAYS))"
                    + "->extend(~day: x | $x.d->dayOfMonth())->select(~[id, day])->sort(~id->ascending())"),
            new Case("pivot", PEOPLE + "->pivot(~city, ~[s: x | $x.age : y | $y->plus()])"),
            new Case("window", PEOPLE + "->extend(over(~city), ~r: {p, w, r | $p->rowNumber($r)})->select(~[name, r])"));

    @Test
    void differential() throws Exception {
        EmbeddedMongoDb db = EmbeddedMongoDb.shared();
        System.setProperty(PASSWORD_PROPERTY, EmbeddedMongoDb.PASSWORD);
        String duck = com.legend.test.StorelessRuntime.with("", DatabaseType.DuckDB);
        String mongo = com.legend.test.StorelessRuntime.onMongo("", "127.0.0.1", db.port(), EmbeddedMongoDb.DATABASE,
                EmbeddedMongoDb.USER, PASSWORD_PROPERTY);
        List<String> lines = new ArrayList<>();
        Map<String, Integer> counts = new TreeMap<>();
        List<String> referenceFailures = new ArrayList<>();
        for (Case c : CASES) {
            System.out.println("[mongodb-differential] running " + c.name());
            String reference;
            try {
                reference = run(duck, c.expression(), c.output());
            } catch (RuntimeException e) {
                referenceFailures.add(c.name() + ": " + e.getClass().getSimpleName() + ": " + first(e.getMessage()));
                lines.add(c.name() + "\tREFERENCE_FAILED\t" + first(e.getMessage()) + "\t");
                counts.merge("REFERENCE_FAILED", 1, Integer::sum);
                continue;
            }
            String verdict;
            String detail;
            try {
                String answer = run(mongo, c.expression(), c.output());
                verdict = answer.equals(reference) ? "EQUAL" : "DIFFERENT";
                detail = verdict.equals("EQUAL") ? "" : "duckdb=" + flat(reference) + " mongodb=" + flat(answer);
            } catch (com.legend.sql.dialect.DialectCapability e) {
                verdict = "REFUSED";
                detail = first(e.getMessage());
            } catch (com.legend.error.DataError e) {
                verdict = "ERROR";
                detail = first(e.getMessage());
            } catch (RuntimeException e) {
                verdict = "ERROR";
                detail = e.getClass().getSimpleName() + ": " + first(e.getMessage());
            }
            counts.merge(verdict, 1, Integer::sum);
            lines.add(c.name() + "\t" + verdict + "\t" + detail + "\t" + flat(reference));
        }
        String out = System.getenv("TEST_UNDECLARED_OUTPUTS_DIR");
        if (out != null) {
            Files.write(Path.of(out, "mongodb-differential.tsv"), lines, StandardCharsets.UTF_8);
        }
        System.out.println("[mongodb-differential] " + CASES.size() + " cases: " + counts);
        for (String line : lines) {
            System.out.println("[mongodb-differential] " + line);
        }
        assertTrue(referenceFailures.isEmpty(), "the reference (DuckDB) did not run: " + referenceFailures);
    }

    private static String run(String model, String expression, TypedQuery.Output output) throws Exception {
        ExecutionPlan plan = Compiler.query(Compiler.compileModel(model), expression)
                .executionPlan(com.legend.test.StorelessRuntime.RUNTIME, output);
        StringWriter out = new StringWriter();
        PlanRunner.run(plan, Map.of(), PlanSessions.shared(), out);
        return out.toString();
    }

    private static String flat(String s) {
        return s.replace("\n", "\\n").replace("\t", " ");
    }

    private static String first(@com.legend.base.Nullable String message) {
        String m = String.valueOf(message);
        int nl = m.indexOf('\n');
        return (nl < 0 ? m : m.substring(0, nl)).replace('\t', ' ');
    }
}
