package com.legend.equivalence;

import org.finos.legend.engine.language.pure.grammar.from.PureGrammarParser;
import org.junit.jupiter.api.Test;

/** Wire-shape probe for ###Relational Database elements (leg 3). */
class ZRelationalProbe {

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
        probe("minimal", """
                ###Relational
                Database my::DB
                (
                  Table T ( id INTEGER PRIMARY KEY, name VARCHAR(200) )
                )
                """);
        probe("schema-join-filter-view", """
                ###Relational
                Database my::DB2
                (
                  Schema S
                  (
                    Table T1 ( id INTEGER PRIMARY KEY, oid INTEGER )
                    Table T2 ( id INTEGER PRIMARY KEY, amount DOUBLE )
                    View V1
                    (
                      vid: T1.id,
                      total: T2.amount
                    )
                  )
                  Join J1 (S.T1.oid = S.T2.id)
                  Filter F1 (S.T1.id > 10)
                )
                """);
        probe("milestoning", """
                ###Relational
                Database my::DB4
                (
                  Table T
                  (
                    milestoning
                    (
                      business(BUS_FROM = from_z, BUS_THRU = thru_z, THRU_IS_INCLUSIVE = true),
                      processing(PROCESSING_IN = in_z, PROCESSING_OUT = out_z, OUT_IS_INCLUSIVE = false)
                    )
                    id INTEGER PRIMARY KEY,
                    from_z DATE, thru_z DATE, in_z TIMESTAMP, out_z TIMESTAMP
                  )
                )
                """);
        probe("milestoning-infinity", """
                ###Relational
                Database my::DB5
                (
                  Table T
                  (
                    milestoning
                    (
                      processing(PROCESSING_IN = in_z, PROCESSING_OUT = out_z, INFINITY_DATE = %9999-12-30T19:00:00.0000)
                    )
                    id INTEGER PRIMARY KEY, in_z TIMESTAMP, out_z TIMESTAMP
                  )
                )
                """);
        probe("join-ops", """
                ###Relational
                Database my::DB6
                (
                  Table A ( id INTEGER PRIMARY KEY, x VARCHAR(10), n INTEGER )
                  Table B ( id INTEGER PRIMARY KEY, y VARCHAR(10) )
                  Join J1 (A.id = B.id and A.x = 'lit')
                  Join J2 (A.id = B.id or isNull(A.x))
                  Join Self (A.id = {target}.n)
                  Filter FComplex (A.n > 5 and A.x != 'z')
                )
                """);
        probe("view-features", """
                ###Relational
                Database my::DB7
                (
                  Table T ( id INTEGER PRIMARY KEY, grp VARCHAR(10), amt DOUBLE )
                  View V1
                  (
                    ~filter F1
                    ~groupBy ( T.grp )
                    ~distinct
                    key: T.grp PRIMARY KEY,
                    total: sum(T.amt)
                  )
                  Filter F1 (T.id > 0)
                )
                """);
        probe("column-stereotypes", """
                ###Pure
                Profile test::P { stereotypes: [imp, dep]; }

                ###Relational
                Database a::A
                (
                  Table tb
                  (
                    id <<test::P.imp, test::P.dep>> INTEGER
                  )
                )
                """);
        probe("decorated-schema-table", """
                ###Pure
                Profile test::P { stereotypes: [s1]; tags: [doc]; }

                ###Relational
                Database app::db
                (
                  Schema <<test::P.s1>> {test::P.doc = 'S doc'} mySchema
                  (
                    Table <<test::P.s1>> {test::P.doc = 'T doc'} T1 ( id INTEGER PRIMARY KEY )
                    View <<test::P.s1>> V1 ( vid: T1.id )
                  )
                )
                """);
        probe("nary-and-schema-qual", """
                ###Relational
                Database my::DB9
                (
                  Schema S ( Table T1 ( id INTEGER PRIMARY KEY, a INTEGER, b INTEGER ) )
                  Table T2 ( id INTEGER PRIMARY KEY )
                  Join J (S.T1.id = T2.id and S.T1.a = 1 and S.T1.b = 2)
                  Filter F (S.T1.a > 1 and S.T1.b < 5)
                )
                """);
        probe("milestoning-samline", """
                ###Relational
                Database my::DBA
                (
                  Table T
                  (
                    milestoning( business(BUS_FROM = f, BUS_THRU = t) )
                    id INTEGER PRIMARY KEY, f DATE, t DATE
                  )
                )
                """);
        probe("include", """
                ###Relational
                Database my::DB3
                (
                  include my::DB
                  Table X ( a BIT, b DATE, c TIMESTAMP, d DECIMAL(10,2), e CHAR(1), f SEMISTRUCTURED )
                )
                """);
    }
}
