package com.legend.equivalence;

import org.finos.legend.engine.language.pure.grammar.from.PureGrammarParser;
import org.junit.jupiter.api.Test;

/** Wire-shape probe for the burn-to-zero tail: include dataspace, #SQL
 *  islands, ServiceStore embedded data, (dataspace) function-test refs.
 *  Diagnostic only. */
class ZTailProbe {

    private void probe(String label, String src) throws Exception {
        var mapper = org.finos.legend.engine.shared.core.ObjectMapperFactory
                .getNewStandardObjectMapperWithPureProtocolExtensionSupports();
        try {
            var pmcd = PureGrammarParser.newInstance().parseModel(src);
            for (var e : pmcd.getElements()) {
                if (e.getPath().contains("SectionIndex")) {
                    continue;
                }
                System.out.println("== " + label + " :: " + e.getPath());
                System.out.println(mapper.writeValueAsString(e));
            }
        } catch (Throwable t) {
            System.out.println("== " + label + " REJECTED: "
                    + String.valueOf(t.getMessage()).replaceAll("\\s+", " "));
        }
    }

    @Test
    void tdsShape() throws Exception {
        probe("tds-accessor", """
                function my::f(): String[1]
                {
                  let x = #TDS{
                      val, more
                      1, a
                      2, b
                  }#;
                  'ok';
                }
                """);
    }

    @Test
    void foreignMappingShape() throws Exception {
        probe("servicestore-mapping", """
                Class my::Person { name: String[1]; }

                ###ServiceStore
                ServiceStore my::Store
                (
                  ServiceGroup G
                  (
                    path : '/g';
                    Service S
                    (
                      path : '/s';
                      method : GET;
                      response : [my::Person <- my::B];
                      security : [];
                    )
                  )
                )

                ###Mapping
                Mapping my::M
                (
                  *my::Person[p_set]: ServiceStore
                  {
                    ~service [my::Store] G.S
                  }
                )
                """);
    }

    @Test
    void foreignMappingRichShape() throws Exception {
        probe("servicestore-mapping-rich", """
                Class my::Person { name: String[1]; }

                ###ServiceStore
                ServiceStore my::Store
                (
                  ServiceGroup G
                  (
                    path : '/g';
                    Service S
                    (
                      path : '/s';
                      method : GET;
                      parameters :
                      (
                        q : String (location = query)
                      );
                      response : [my::Person <- my::B];
                      security : [];
                    )
                  )
                )

                ###Mapping
                Mapping my::M
                (
                  *my::Person[p_set]: ServiceStore
                  {
                    ~service [my::Store] G.S
                    (
                      ~request
                      (
                        parameters
                        (
                          q = $this.name
                        )
                      )
                    )
                  }
                )
                """);
    }

    @Test
    void foreignMappingBodyShape() throws Exception {
        probe("servicestore-mapping-body", """
                Class my::Person { name: String[1]; }
                Class my::Syn { name: String[1]; }

                ###ServiceStore
                ServiceStore my::Store
                (
                  ServiceGroup G
                  (
                    path : '/g';
                    Service S
                    (
                      path : '/s';
                      method : POST;
                      requestBody : [my::Syn <- my::B];
                      response : [my::Person <- my::B];
                      security : [];
                    )
                  )
                )

                ###Mapping
                Mapping my::M
                (
                  *my::Person[p_set]: ServiceStore
                  {
                    ~service [my::Store] G.S
                    (
                      ~request
                      (
                        body = ^my::Syn(name = 'x')
                      )
                    )
                  }
                )
                """);
    }

    @Test
    void mongoMappingShape() throws Exception {
        probe("mongodb-mapping", """
                Class my::SomeClass { name: String[1]; }

                ###MongoDB
                Database my::db
                (
                )

                ###Mapping
                Mapping my::M
                (
                  *my::SomeClass[id1]: MongoDB
                  {
                    ~mainCollection [my::db] PersonRecord
                  }
                )
                """);
    }

    @Test
    void foreignConnShapes() throws Exception {
        probe("servicestore-conn", """
                ###ServiceStore
                ServiceStore my::Store
                (
                )

                ###Connection
                ServiceStoreConnection my::C
                {
                  store: my::Store;
                  baseUrl: 'https://prodUrl.com';
                }
                """);
        probe("deephaven-conn", """
                ###Deephaven
                Deephaven my::DStore
                (
                    Table t
                    (
                        C1: INT
                    )
                )

                ###Connection
                DeephavenConnection my::DC
                {
                    store: my::DStore;
                    serverUrl: 'http://localhost:10000'
                    authentication: # PSK {
                        psk: 'myStaticPSK';
                    }#;
                }
                """);
        probe("mongodb-conn", """
                ###MongoDB
                Database my::mdb
                (
                )

                ###Connection
                MongoDBConnection my::MC
                {
                  database: legend_db;
                  store: my::mdb;
                  serverURLs: [localhost:27071];
                  authentication: # UserPassword {
                    username: 'lgnd_usr';
                    password: SystemPropertiesSecret
                    {
                      systemPropertyName: 'sys.prop.name';
                    };
                  }#;
                }
                """);
    }

    @Test
    void richServiceStoreMappingShapes() throws Exception {
        probe("servicestore-mapping-rich2", """
                Class my::Person { name: String[1]; }

                ###ServiceStore
                ServiceStore my::Store
                (
                  ServiceGroup G
                  (
                    path : '/g';
                    Service S
                    (
                      path : '/s';
                      method : GET;
                      parameters :
                      (
                        "q param" : String (location = query)
                      );
                      response : [my::Person <- my::B];
                      security : [];
                    )
                  )
                )

                ###Mapping
                Mapping my::M
                (
                  *my::Person[p_set]: ServiceStore
                  {
                    +localName: String[1];

                    ~service [my::Store] G.S
                    (
                      ~path $service.response.name
                      ~request
                      (
                        parameters
                        (
                          "q param" = $this.name
                        )
                      )
                    )
                  }
                )
                """);
    }

    @Test
    void activatorShapes() throws Exception {
        probe("snowflake-app", """
                function my::f(): String[1] { 'x' }

                ###Snowflake
                SnowflakeApp my::App
                {
                  applicationName: 'revenue';
                  description: 'd';
                  ownership: Deployment { identifier: 'dep1' };
                  function: my::f():String[1];
                  usageRole: 'PUBLIC';
                  deploymentSchema: 'LEGEND';
                }
                """);
        probe("snowflake-udf", """
                function my::f(): String[1] { 'x' }

                ###Snowflake
                SnowflakeM2MUdf my::Udf
                {
                  udfName: 'u';
                  function: my::f():String[1];
                  ownership: Deployment { identifier: 'dep1' };
                  deploymentSchema: 'S';
                  deploymentStage: 'St';
                }
                """);
        probe("memsql-fn", """
                function my::f(): String[1] { 'x' }

                ###MemSql
                MemSqlFunction my::MF
                {
                  functionName: 'mf';
                  function: my::f():String[1];
                  ownership: Deployment { identifier: 'dep1' };
                }
                """);
        probe("hosted-service", """
                function my::f(): String[1] { 'x' }

                ###HostedService
                HostedService my::HS
                {
                  pattern: '/p';
                  ownership: Deployment { identifier: 'dep1' };
                  function: my::f():String[1];
                  documentation: 'd';
                  autoActivateUpdates: true;
                }
                """);
    }

    @Test
    void activatorShapes2() throws Exception {
        probe("bigquery-fn", """
                function my::f(): String[1] { 'x' }

                ###BigQuery
                BigQueryFunction my::BF
                {
                  functionName: 'bf';
                  function: my::f():String[1];
                  ownership: Deployment { identifier: 'dep1' };
                }
                """);
        probe("function-jar", """
                function my::f(): String[1] { 'x' }

                ###FunctionJar
                FunctionJar my::FJ
                {
                  ownership: Deployment { identifier: 'dep1' };
                  function: my::f():String[1];
                  documentation: 'd';
                }
                """);
        probe("snowflake-app-actcfg", """
                function my::f(): String[1] { 'x' }

                ###Connection
                RelationalDatabaseConnection my::conn
                {
                  store: my::db;
                  type: Snowflake;
                  specification: Snowflake { name: 'n'; account: 'a'; warehouse: 'w'; region: 'r'; };
                  auth: DefaultH2;
                }

                ###Snowflake
                SnowflakeApp my::App2
                {
                  applicationName: 'a2';
                  function: my::f():String[1];
                  ownership: Deployment { identifier: 'dep1' };
                  activationConfiguration: my::conn;
                }
                """);
    }

    @Test
    void activatorShapes3() throws Exception {
        probe("hosted-userlist", """
                function my::f(): String[1] { 'x' }

                ###HostedService
                HostedService my::HS
                {
                  pattern: '/a/b';
                  ownership: UserList { users: [
                   'user1',
                   'user2'
                   ] };
                  function: my::f():String[1];
                  documentation: 'd';
                  autoActivateUpdates: true;
                }
                """);
        probe("snowflake-permscheme", """
                function my::f(): String[1] { 'x' }

                ###Snowflake
                SnowflakeApp my::App
                {
                  applicationName: 'a';
                  function: my::f():String[1];
                  ownership: Deployment { identifier: 'dep1' };
                  usageRole: 'PRIVATE';
                  permissionScheme: SEQUESTERED;
                }
                """);
    }

    @Test
    void tailShapes() throws Exception {
        probe("text", """
                ###Text
                Text meta::pure::myText
                {
                  type: STRING;
                  content: 'this is just for context';
                }

                Text meta::pure::noType
                {
                  content: 'x';
                }
                """);
        probe("genspec", """
                ###GenerationSpecification
                GenerationSpecification test::x
                {
                  generationNodes: [
                    {
                      generationElement: model::serializableSpec;
                    },
                    {
                      id: 'secondGeneration';
                      generationElement: model::modelSpec;
                    }
                  ];
                  fileGenerations: [
                    model::myFileGeneration,
                    model::other
                  ];
                }
                """);
        probe("filegen", """
                ###FileGeneration
                Avro model::AvroConfig
                {
                  scopeElements: [model::MyClass, model];
                  generationOutputPath: 'myAvroRoot';
                  includeNamespace: true;
                  propertyProfile: ['model::myProfile', 'model::nextProfile'];
                  test: 2;
                  namespaceOverride: {
                    key1: 'mapValue1';
                    key2: 'mapValue2';
                  };
                }

                JsonSchema model::JSONSchemaConfig
                {
                }
                """);
        probe("deephaven-store", """
                ###Deephaven
                Deephaven test::Store::foo
                (
                    Table xyz
                    (
                      prop1: STRING,
                      prop2: INT,
                      prop3: BOOLEAN,
                      prop4: DATETIME,
                      prop8: DECIMAL(10, 2)
                    )
                )
                """);
        probe("mongo-db", """
                ###MongoDB
                Database test::db
                (
                  Collection Person
                  (
                    validationLevel: strict;
                    validationAction: error;
                    jsonSchema: {
                      "bsonType": "object",
                      "properties": {
                        "name": {
                          "bsonType": "string"
                        }
                      },
                      "additionalProperties": false
                    };
                  )
                )
                """);
        probe("elastic-cluster", """
                ###Elasticsearch
                Elasticsearch7Cluster abc::abc::Store
                {
                    indices: [
                        index1: {
                            properties: [
                                prop1: Keyword
                            ];
                        }
                    ];
                }
                """);
    }

    @Test
    void tailShapes2() throws Exception {
        probe("dq-validation", """
                Class my::Person
                {
                  name: String[1];
                  age: Integer[1];
                }

                ###DataQualityValidation
                DataQualityValidation my::PersonValidation
                {
                   context: fromMappingAndRuntime(my::M, my::RT);
                   validationTree: $[
                      Person{
                        name,
                        age
                      }
                    ]$;
                   filter: p: Person[1]|$p.age >= 18;
                }
                """);
    }

    @Test
    void tailShapes3() throws Exception {
        probe("deephaven-app", """
                function test::myFunc():Any[*] { 1 }

                ###Deephaven
                DeephavenApp test::MyApp
                {
                    applicationName: 'MyTestApp';
                    function: test::myFunc():Any[*];
                    description: 'A test app';
                    ownership: Deployment { identifier: 'owner123' };
                }
                """);
        probe("dq-relation-validation", """
                Class demo::Person
                {
                  id: Integer[1];
                }

                ###DataQualityValidation
                DataQualityRelationValidation demo::simpleValidation
                {
                   query: |demo::Person.all()->project(~[id: x|$x.id]);
                   validations: [
                   {
                     name: 'idNotNegative';
                     description: 'no negative ids';
                     assertion: rel|$rel->filter(row|$row.id > 0);
                    }
                   ];
                }
                """);
        probe("dq-relation-comparison", """
                Class demo::Person
                {
                  id: Integer[1];
                }

                ###DataQualityValidation
                DataQualityRelationComparison meta::dataquality::TestRelationComparison
                {
                  source: |demo::Person.all()->project(~[id: x|$x.id]);
                  target: |demo::Person.all()->project(~[id: x|$x.id]);
                  keys: [id, fullName];
                  strategy: MD5Hash;
                }
                """);
        probe("filegen-quoted", """
                ###FileGeneration
                Avro model::AvroConfig
                {
                  scopeElements: [model::MyClass, model];
                  'include Namespace': true;
                }
                """);
        probe("dq-dataspace-ctx", """
                ###DataQualityValidation
                DataQualityValidation my::V
                {
                   context: fromDataSpace(my::DS, 'Local_Context');
                   validationTree: $[
                      Person<ageMustBePositive, 'nameMust NotBeBlank'>{
                        name
                      }
                    ]$;
                }
                """);
    }

    @Test
    void tailShapes4() throws Exception {
        probe("deephaven-cols", """
                ###Deephaven
                Deephaven test::S
                (
                    Table t
                    (
                      p5: FLOAT,
                      p6: DOUBLE,
                      p7: TIMESTAMP
                    )
                )
                """);
        probe("mongo-rich", """
                ###MongoDB
                Database test::db2
                (
                  Collection Person
                  (
                    validationLevel: strict;
                    validationAction: error;
                    jsonSchema: {
                      "bsonType": "object",
                      "title": "Record of Firm",
                      "properties": {
                        "name": {
                          "bsonType": "string",
                          "description": "name of the firm",
                          "minLength": 2
                        },
                        "age": {
                          "bsonType": "long"
                        }
                      },
                      "additionalProperties": true,
                      "required": ["name"]
                    };
                  )
                )
                """);
        probe("mongo-empty", """
                ###MongoDB
                Database test::empty
                (
                )
                """);
    }

    @Test
    void shapes() throws Exception {
        probe("include-dataspace", """
                ###Mapping
                Mapping my::M
                (
                  include dataspace my::DS
                )
                """);
        probe("sql-island", """
                function my::f(): Any[*]
                {
                  #SQL{select * from t}#
                }
                """);
        probe("servicestore-data", """
                ###Data
                Data my::D
                {
                  ServiceStore
                  #{
                    [
                      {
                        request:
                        {
                          method: GET;
                          url: '/x';
                        };
                        response:
                        {
                          body:
                            ExternalFormat
                            #{
                              contentType: 'application/json';
                              data: '[]';
                            }#;
                        };
                      }
                    ]
                  }#
                }
                """);
        probe("dataspace-testref", """
                function my::f(): Any[*]
                {
                  1
                }
                {
                  testSuite_1
                  (
                    (dataspace) my::DS:
                        DataspaceTestData
                        #{
                          my::Ref
                        }#;
                    test_1 | f() => '1';
                  )
                }
                """);
    }
}
