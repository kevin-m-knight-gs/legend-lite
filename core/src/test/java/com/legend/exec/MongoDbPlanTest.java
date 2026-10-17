// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend.exec;

import com.legend.Compiler;
import com.legend.TypedQuery;
import com.legend.executionplan.ExecutionPlan;
import com.legend.testing.EmbeddedMongoDb;
import com.mongodb.client.MongoClient;
import org.bson.Document;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.StringWriter;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * v0 end to end (docs/MONGODB_PLAN_2026_10_16.md §11, 2026-10-17): a model with a {@code ###MongoDB} store and a
 * {@code MongoDBConnection}, planned by the one compiler into an aggregate command and run by the plan runner through
 * the MongoDB connector on a real MongoDB 8.0 ({@code EmbeddedMongoDb}). The documents are the test's own, written
 * through the driver; the platform reads them through the store's declared schema alone.
 */
class MongoDbPlanTest {

    private static final String PASSWORD_PROPERTY = "legend.mongodb.pct.password";

    private static String model;

    @BeforeAll
    static void documents() {
        EmbeddedMongoDb db = EmbeddedMongoDb.shared();
        System.setProperty(PASSWORD_PROPERTY, EmbeddedMongoDb.PASSWORD);
        try (MongoClient client = db.client()) {
            var people = client.getDatabase(EmbeddedMongoDb.DATABASE).getCollection("people");
            people.drop();
            people.insertMany(List.of(
                    new Document("name", "Ann").append("age", 34L).append("city", "Oslo"),
                    new Document("name", "Bob").append("age", 28L).append("city", "Oslo"),
                    new Document("name", "Cy").append("age", 41L).append("city", "Rome"),
                    new Document("name", "Di").append("city", "Rome")));
        }
        model = String.format(java.util.Locale.ROOT, """
                ###MongoDB
                Database test::Store
                (
                  Collection people
                  (
                    jsonSchema: {
                      "bsonType": "object",
                      "properties": {
                        "name": { "bsonType": "string" },
                        "age": { "bsonType": "long" },
                        "city": { "bsonType": "string" }
                      },
                      "required": ["name"]
                    };
                  )
                )
                ###Connection
                MongoDBConnection test::Conn
                {
                  database: %s;
                  store: test::Store;
                  serverURLs: [127.0.0.1:%d];
                  authentication: # UserPassword {
                    username: '%s';
                    password: SystemPropertiesSecret
                    {
                      systemPropertyName: '%s';
                    };
                  }#;
                }
                ###Runtime
                Runtime test::RT
                {
                  mappings: [];
                  connections: [ test::Store: [ session: test::Conn ] ];
                }
                """, EmbeddedMongoDb.DATABASE, db.port(), EmbeddedMongoDb.USER, PASSWORD_PROPERTY);
    }

    private static String run(String query, TypedQuery.Output output, Map<String, ?> values) throws Exception {
        ExecutionPlan plan = Compiler.query(Compiler.compileModel(model), query).executionPlan("test::RT", output);
        StringWriter out = new StringWriter();
        PlanRunner.run(plan, values, PlanSessions.shared(), out);
        return out.toString();
    }

    @Test
    void aFilteredSortedSelectStreamsItsRows() throws Exception {
        assertEquals("[{\"name\":\"Ann\",\"age\":34},{\"name\":\"Cy\",\"age\":41}]",
                run("#>{test::Store.people}#->filter(x | $x.age > 30)->select(~[name, age])->sort(~name->ascending())",
                        TypedQuery.Output.STREAMED_JSON, Map.of()));
    }

    @Test
    void anAbsentFieldReadsAsNull() throws Exception {
        assertEquals("[{\"name\":\"Di\",\"age\":null}]",
                run("#>{test::Store.people}#->filter(x | $x.age->isEmpty())->select(~[name, age])",
                        TypedQuery.Output.STREAMED_JSON, Map.of()));
    }

    @Test
    void aParameterIsBoundInPlace() throws Exception {
        assertEquals("[{\"name\":\"Cy\"}]",
                run("{n: Integer[1] | #>{test::Store.people}#->filter(x | $x.age > $n)->select(~[name])}",
                        TypedQuery.Output.STREAMED_JSON, Map.of("n", 40L)));
    }

    @Test
    void aGroupCountsAndSorts() throws Exception {
        assertEquals("[{\"city\":\"Oslo\",\"n\":2},{\"city\":\"Rome\",\"n\":2}]",
                run("#>{test::Store.people}#->groupBy(~[city], ~[n: x | $x.name : y | $y->count()])"
                        + "->sort(~city->ascending())", TypedQuery.Output.STREAMED_JSON, Map.of()));
    }

    @Test
    void theWholeResultAsJsonAndAsCsv() throws Exception {
        String json = run("#>{test::Store.people}#->filter(x | $x.city == 'Oslo')->select(~[name, age])"
                + "->sort(~age->descending())", TypedQuery.Output.JSON, Map.of());
        assertTrue(json.contains("\"name\":\"Ann\"") && json.contains("\"name\":\"Bob\""), json);
        String csv = run("#>{test::Store.people}#->filter(x | $x.city == 'Oslo')->select(~[name, age])"
                + "->sort(~age->descending())", TypedQuery.Output.CSV, Map.of());
        assertTrue(csv.startsWith("name,age") && csv.contains("Ann,34") && csv.contains("Bob,28"), csv);
    }

    @Test
    void aStorelessExpressionRunsOnTheDatabase() throws Exception {
        assertTrue(run("1 + 2", TypedQuery.Output.JSON, Map.of()).contains("3"));
    }
}
