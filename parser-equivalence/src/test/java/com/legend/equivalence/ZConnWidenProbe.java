package com.legend.equivalence;

import org.finos.legend.engine.language.pure.grammar.from.PureGrammarParser;
import org.junit.jupiter.api.Test;

/** Wire-shape probe for the connection spec/auth WIDENING (worklist item 3).
 *  Diagnostic only — corpus-true spellings in, engine JSON out. */
class ZConnWidenProbe {

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
    void shapes() throws Exception {
        probe("snowflake-full", """
                ###Connection
                RelationalDatabaseConnection my::S
                {
                  store: my::DB;
                  type: Snowflake;
                  specification: Snowflake
                  {
                    name: 'dummyDB';
                    account: 'account';
                    warehouse: 'warehouse';
                    region: 'us-east-1';
                    enableQueryTags: true;
                    accountType: VPS;
                  };
                  auth: SnowflakePublic
                  {
                    publicUserName: 'test';
                    privateKeyVaultReference: 'pk';
                    passPhraseVaultReference: 'pp';
                  };
                }
                """);
        probe("snowflake-min-plus-extras", """
                ###Connection
                RelationalDatabaseConnection my::S2
                {
                  store: my::DB;
                  type: Snowflake;
                  specification: Snowflake
                  {
                    name: 'db';
                    account: 'a';
                    warehouse: 'w';
                    region: 'r';
                    cloudType: 'aws';
                    organization: 'org';
                    role: 'role1';
                  };
                  auth: DefaultH2;
                }
                """);
        probe("spanner", """
                ###Connection
                RelationalDatabaseConnection my::SP
                {
                  store: my::DB;
                  type: Spanner;
                  specification: Spanner
                  {
                    projectId: 'proj';
                    instanceId: 'inst';
                    databaseId: 'db';
                  };
                  auth: GCPApplicationDefaultCredentials;
                }
                """);
        probe("databricks", """
                ###Connection
                RelationalDatabaseConnection my::DBX
                {
                  store: my::DB;
                  type: Databricks;
                  specification: Databricks
                  {
                    hostname: 'h.databricks.com';
                    port: '443';
                    protocol: 'https';
                    httpPath: 'sql/x';
                  };
                  auth: ApiToken
                  {
                    apiToken: 'tok.ref';
                  };
                }
                """);
        probe("bigquery", """
                ###Connection
                RelationalDatabaseConnection my::BQ
                {
                  store: my::DB;
                  type: BigQuery;
                  specification: BigQuery
                  {
                    projectId: 'proj';
                    defaultDataset: 'ds';
                  };
                  auth: GCPApplicationDefaultCredentials;
                }
                """);
        probe("middle-tier", """
                ###Connection
                RelationalDatabaseConnection my::MT
                {
                  store: my::DB;
                  type: H2;
                  specification: LocalH2 {};
                  auth: MiddleTierUserNamePassword
                  {
                    vaultReference: 'vault.ref';
                  };
                }
                """);
        probe("quote-identifiers", """
                ###Connection
                RelationalDatabaseConnection my::Q
                {
                  store: my::DB;
                  type: H2;
                  quoteIdentifiers: true;
                  specification: LocalH2 {};
                  auth: DefaultH2;
                }
                """);
    }
}
