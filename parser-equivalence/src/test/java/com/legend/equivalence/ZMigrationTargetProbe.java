package com.legend.equivalence;

import org.finos.legend.engine.language.pure.grammar.from.PureGrammarParser;
import org.junit.jupiter.api.Test;

/** PROBE (conform-to-engine migration): does the ORACLE accept each
 *  spelling we plan to migrate our tests TO? Real verification per
 *  target, no sampling. Diagnostic only. */
class ZMigrationTargetProbe {

    private void probe(String label, String src) {
        var mapper = org.finos.legend.engine.shared.core.ObjectMapperFactory
                .getNewStandardObjectMapperWithPureProtocolExtensionSupports();
        try {
            var pmcd = PureGrammarParser.newInstance().parseModel(src);
            System.out.println("@@ " + label + " ORACLE-ACCEPTS");
            for (var e : pmcd.getElements()) {
                if (e.getPath().contains("Conn")) {
                    try {
                        System.out.println("@@ WIRE " + label + " "
                                + mapper.writeValueAsString(e));
                    } catch (Exception ex) {
                        System.out.println("@@ WIRE-FAIL " + label);
                    }
                }
            }
        } catch (Throwable t) {
            System.out.println("@@ " + label + " ORACLE-REJECTS: "
                    + String.valueOf(t.getMessage()).replaceAll("\\s+", " "));
        }
    }

    private static final String DB = """
            ###Relational
            Database store::Db
            (
              Table T (ID INTEGER PRIMARY KEY)
            )
            """;

    @Test
    void migrationTargets() {
        probe("duckdb-empty", DB + """
                ###Connection
                RelationalDatabaseConnection store::Conn
                {
                  store: store::Db;
                  type: DuckDB;
                  specification: DuckDB
                  {
                  };
                  auth: Test;
                }
                """);
        probe("duckdb-path", DB + """
                ###Connection
                RelationalDatabaseConnection store::Conn
                {
                  store: store::Db;
                  type: DuckDB;
                  specification: DuckDB
                  {
                    path: '/tmp/x.duckdb';
                  };
                  auth: Test;
                }
                """);
        probe("h2-test-auth", DB + """
                ###Connection
                RelationalDatabaseConnection store::Conn
                {
                  store: store::Db;
                  type: H2;
                  specification: LocalH2
                  {
                  };
                  auth: Test;
                }
                """);
        probe("static-name", DB + """
                ###Connection
                RelationalDatabaseConnection store::Conn
                {
                  store: store::Db;
                  type: H2;
                  specification: Static
                  {
                    name: 'db';
                    host: 'localhost';
                    port: 1234;
                  };
                  auth: DefaultH2;
                }
                """);
        probe("localh2-sqls", DB + """
                ###Connection
                RelationalDatabaseConnection store::Conn
                {
                  store: store::Db;
                  type: H2;
                  specification: LocalH2
                  {
                    testDataSetupSqls: ['drop table if exists T;'];
                  };
                  auth: DefaultH2;
                }
                """);
        probe("username-password-vault", DB + """
                ###Connection
                RelationalDatabaseConnection store::Conn
                {
                  store: store::Db;
                  type: H2;
                  specification: Static
                  {
                    name: 'db';
                    host: 'localhost';
                    port: 1234;
                  };
                  auth: UserNamePassword
                  {
                    baseVaultReference: 'base';
                    userNameVaultReference: 'user';
                    passwordVaultReference: 'pass';
                  };
                }
                """);
    }
}
