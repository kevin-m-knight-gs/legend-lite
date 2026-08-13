package com.legend.equivalence;

import org.finos.legend.engine.language.pure.grammar.from.PureGrammarParser;
import org.junit.jupiter.api.Test;

class ZOracleScopeProbeTest {
    @Test
    void probe() {
        String[][] cases = {
            {"redshift-ds", "###Connection\nRelationalDatabaseConnection x::c\n{\n  store: x::S;\n  type: Redshift;\n  specification: Redshift\n  {\n    name: 'n';\n    host: 'h';\n    port: 5439;\n    clusterID: 'c';\n    region: 'r';\n    endpointURL: 'e';\n  };\n  auth: DefaultH2;\n}\n"},
            {"snowflake-proxy", "###Connection\nRelationalDatabaseConnection x::c\n{\n  store: x::S;\n  type: Snowflake;\n  specification: Snowflake\n  {\n    name: 'n';\n    account: 'a';\n    warehouse: 'w';\n    region: 'r';\n    proxyHost: 'p';\n    proxyPort: '8080';\n  };\n  auth: DefaultH2;\n}\n"},
            {"embedded-h2", "###Connection\nRelationalDatabaseConnection x::c\n{\n  store: x::S;\n  type: H2;\n  specification: EmbeddedH2\n  {\n    name: 'n';\n    directory: '/tmp/d';\n    autoServerMode: true;\n  };\n  auth: DefaultH2;\n}\n"},
        };
        for (String[] c : cases) {
            try {
                PureGrammarParser.newInstance().parseModel(c[1]);
                System.out.println("PROBE " + c[0] + " ORACLE=accepts");
                try {
                    var m = RejectionParityTest.class.getDeclaredMethod(
                            "parseLegendEngine", String.class);
                    m.setAccessible(true);
                    m.invoke(RejectionParityTest.class
                            .getDeclaredConstructor().newInstance(), c[1]);
                    System.out.println("PROBE " + c[0] + " OURS=accepts");
                } catch (Exception oe) {
                    System.out.println("PROBE " + c[0] + " OURS=refuses :: "
                            + String.valueOf(oe.getCause().getMessage())
                                    .replaceAll("\\s+", " "));
                }
            } catch (Throwable e) {
                System.out.println("PROBE " + c[0] + " ORACLE=refuses :: "
                        + String.valueOf(e.getMessage()).replaceAll("\\s+", " "));
            }
        }
    }
}
