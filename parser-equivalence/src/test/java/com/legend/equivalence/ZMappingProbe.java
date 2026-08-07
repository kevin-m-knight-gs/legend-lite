package com.legend.equivalence;

import org.finos.legend.engine.language.pure.grammar.from.PureGrammarParser;
import org.junit.jupiter.api.Test;

/** Wire-shape probe for ###Mapping (section-parity leg 4). */
class ZMappingProbe {

    private void probe(String label, String src) throws Exception {
        var mapper = org.finos.legend.engine.shared.core.ObjectMapperFactory
                .getNewStandardObjectMapperWithPureProtocolExtensionSupports();
        try {
            var pmcd = PureGrammarParser.newInstance().parseModel(src);
            for (var e : pmcd.getElements()) {
                if (e.getPath().contains("SectionIndex")) {
                    continue;
                }
                if (!mapper.writeValueAsString(e).contains("\"mapping\"")) {
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
    void batch12Shapes() throws Exception {
        probe("modeljoin", """
                ###Pure
                Class my::F8 { id: Integer[1]; legalName: String[1]; }
                Class my::P8 { firmId: Integer[1]; lastName: String[1]; }
                Class my::SF8 { _id: Integer[1]; _legalName: String[1]; }
                Class my::SP8 { _firmId: Integer[1]; _lastName: String[1]; }
                Association my::FP8 { firm8: my::F8[1]; employees8: my::P8[*]; }

                ###Mapping
                Mapping my::M50
                (
                  my::F8[f1]: Pure { ~src my::SF8 id: $src._id, legalName: $src._legalName }
                  my::P8[e]: Pure { ~src my::SP8 firmId: $src._firmId, lastName: $src._lastName }
                  my::FP8: ModelJoin { {firm8:my::F8[1], employees8:my::P8[1]|$firm8.id == $employees8.firmId} }
                )
                """);
        probe("relation-fn", """
                ###Relational
                Database my::DB9
                (
                  Table t9 ( name VARCHAR(20), id INT )
                )

                ###Pure
                Class my::P9 { name: String[1]; id: Integer[1]; }
                function my::rel9(): meta::pure::metamodel::relation::Relation<Any>[1]
                {
                  #>{my::DB9.t9}#->select()
                }

                ###Mapping
                Mapping my::M51
                (
                  *my::P9[p9]: Relation
                  {
                    ~func my::rel9():Relation<Any>[1]
                    name: name,
                    id: id
                  }
                )
                """);
    }

    @Test
    void batch11Shapes() throws Exception {
        String db = """
                ###Relational
                Database my::DB
                (
                  Table t ( name VARCHAR(20), fid INT )
                )
                """;
        probe("pure-enum-transform", """
                ###Pure
                Class my::S5 { v: String[1]; }
                Enum my::E5 { A }

                ###Mapping
                Mapping my::M40
                (
                  my::S5: Pure
                  {
                    ~src my::S5
                    v: EnumerationMapping em5: $src.v
                  }
                  my::E5: EnumerationMapping em5
                  {
                    A: ['a']
                  }
                )
                """);
        probe("enum-no-id", """
                ###Pure
                Enum my::E6 { BUY, SELL }

                ###Mapping
                Mapping my::M41
                (
                  my::E6: EnumerationMapping
                  {
                    BUY: ['B'],
                    SELL: ['S']
                  }
                )
                """);
        probe("custom-op-fn", """
                ###Pure
                Class my::P6 { n: String[1]; }
                function my::a(): meta::pure::mapping::SetImplementation[*] { [] }

                ###Mapping
                Mapping my::M42
                (
                  *my::P6[one]: Operation
                  {
                    a__SetImplementation_MANY_()
                  }
                )
                """);
        probe("db-dynafunc", db + """
                ###Mapping
                Mapping my::M43
                (
                  my::S: Relational
                  {
                    v: [my::DB] add(t.fid, add(t.fid, 3)),
                    scope([my::DB]t)
                    (
                      w: [my::DB] name
                    )
                  }
                )
                """);
        probe("numeric-id", """
                ###Pure
                Class my::S7 { v: String[1]; }

                ###Mapping
                Mapping my::M44
                (
                  *my::S7[1]: Pure
                  {
                    ~src my::S7
                    v: $src.v
                  }
                )
                """);
    }

    @Test
    void batch10Shapes() throws Exception {
        String db = """
                ###Relational
                Database my::DB
                (
                  Table t ( name VARCHAR(20), fid INT )
                  Table f ( id INT )
                  Join J ( t.fid = f.id )
                  Join K ( t.fid = f.id )
                  Filter FLT ( t.fid > 0 )
                )
                """;
        probe("filter-jointype", db + """
                ###Mapping
                Mapping my::M30
                (
                  my::S: Relational
                  {
                    ~filter [my::DB] (INNER)@J | [my::DB] FLT
                    v: [my::DB]t.name
                  }
                )
                """);
        probe("inline-embedded", db + """
                ###Mapping
                Mapping my::M31
                (
                  my::S[s1]: Relational
                  {
                    v: [my::DB]t.name,
                    emb() Inline[other]
                  }
                  my::T2[other]: Relational { u: [my::DB]f.id }
                )
                """);
        probe("otherwise-embedded", db + """
                ###Mapping
                Mapping my::M32
                (
                  my::S[s1]: Relational
                  {
                    v: [my::DB]t.name,
                    emb
                    (
                      ~primaryKey ([my::DB]t.fid)
                      w: [my::DB]t.fid
                    ) Otherwise ( [other]:[my::DB]@J )
                  }
                  my::T2[other]: Relational { u: [my::DB]f.id }
                )
                """);
        probe("xstore-ids", """
                ###Pure
                Class my::A3 { id: Integer[1]; }
                Class my::B3 { aId: Integer[1]; }
                Association my::AB { a3: my::A3[1]; b3: my::B3[*]; }

                ###Mapping
                Mapping my::M33
                (
                  my::A3[a_set]: Pure { ~src my::A3 id: $src.id }
                  my::B3[b_set]: Pure { ~src my::B3 aId: $src.aId }
                  my::AB: XStore
                  {
                    a3[b_set, a_set]: $this.aId == $that.id,
                    b3[a_set, b_set]: $this.id == $that.aId
                  }
                )
                """);
        probe("pure-decorations", """
                ###Pure
                Class my::S4 { v: String[1]; w: String[*]; }
                Class my::T4 { u: String[1]; }

                ###Mapping
                Mapping my::M34
                (
                  my::S4[s]: Pure
                  {
                    ~src my::S4
                    v: $src.v,
                    +extra: String[0..1]: $src.v,
                    w*: $src.w,
                    u[t_set]: $src.v
                  }
                )
                """);
    }

    @Test
    void batch8Shapes() throws Exception {
        String db = """
                ###Relational
                Database my::DB
                (
                  Table t ( name VARCHAR(20), fid INT, from_z DATE, thru_z DATE )
                  Table f ( id INT )
                  Join J ( t.fid = f.id )
                  Filter FLT ( t.fid > 0 )
                )
                """;
        probe("assoc-set-ids", db + """
                ###Mapping
                Mapping my::M20
                (
                  my::A2: Relational
                  {
                    AssociationMapping
                    (
                      a[e1, a1] : [my::DB]@J,
                      e[a1, e1] : [my::DB]@J
                    )
                  }
                )
                """);
        probe("rel-filter", db + """
                ###Mapping
                Mapping my::M21
                (
                  my::S: Relational
                  {
                    ~filter [my::DB] FLT
                    v: [my::DB]t.name
                  }
                )
                """);
        probe("rel-filter-joined", db + """
                ###Mapping
                Mapping my::M22
                (
                  my::S: Relational
                  {
                    ~filter [my::DB]@J | [my::DB]FLT
                    v: [my::DB]t.name
                  }
                )
                """);
        probe("pure-filter", """
                ###Pure
                Class my::S2 { v: String[1]; }

                ###Mapping
                Mapping my::M23
                (
                  my::S2: Pure
                  {
                    ~src my::S2
                    ~filter $src.v == 'x'
                    v: $src.v
                  }
                )
                """);
        probe("embedded-plain", db + """
                ###Mapping
                Mapping my::M24
                (
                  my::S: Relational
                  {
                    v: [my::DB]t.name,
                    emb
                    (
                      w: [my::DB]t.fid
                    )
                  }
                )
                """);
        probe("embedded-id-and-milestoning", db + """
                ###Mapping
                Mapping my::M25
                (
                  my::S[s1] extends [s0]: Relational
                  {
                    milestoning[s1_m]
                    (
                      from: [my::DB]t.from_z,
                      thru: [my::DB]t.thru_z
                    ),
                    emb[k]
                    (
                      w: [my::DB]t.fid
                    )
                  }
                )
                """);
        probe("local-prop", db + """
                ###Mapping
                Mapping my::M26
                (
                  my::S: Relational
                  {
                    +localName: String[0..1]: [my::DB]t.name
                  }
                )
                """);
        probe("test-suites", """
                ###Pure
                Class my::P2 { n: String[1]; }

                ###Mapping
                Mapping my::M27
                (
                  my::P2: Pure { ~src my::P2 n: $src.n }

                  testSuites:
                  [
                    suite1:
                    {
                      function: |my::P2.all()->graphFetch(#{my::P2{n}}#)->serialize(#{my::P2{n}}#);
                      tests:
                      [
                        test1:
                        {
                          data:
                          [
                            ModelStore: ModelStore
                              #{
                                my::P2:
                                  ExternalFormat
                                  #{
                                    contentType: 'application/json';
                                    data: '{"n": "x"}';
                                  }#
                              }#
                          ];
                          asserts:
                          [
                            expected:
                              EqualToJson
                              #{
                                expected:
                                  ExternalFormat
                                  #{
                                    contentType: 'application/json';
                                    data: '{"n": "x"}';
                                  }#;
                              }#
                          ];
                        }
                      ];
                    }
                  ]
                )
                """);
    }

    @Test
    void batch7Shapes() throws Exception {
        String db = """
                ###Relational
                Database my::DB
                (
                  Table t ( name VARCHAR(20), fid INT )
                  Table f ( id INT )
                  Join J ( t.fid = f.id )
                  Join K ( t.fid = f.id )
                )
                """;
        probe("prop-set-ids", db + """
                ###Mapping
                Mapping my::M10
                (
                  my::S[s1]: Relational { v: [my::DB]t.name, w[s1,s2]: [my::DB]@J }
                  my::T2[s2]: Relational { u[s2]: [my::DB]@J }
                )
                """);
        probe("scope-forms", db + """
                ###Mapping
                Mapping my::M11
                (
                  my::S: Relational
                  {
                    scope([my::DB])
                    (
                      v: t.name
                    ),
                    scope([my::DB]f)
                    (
                      w: id
                    ),
                    scope([my::DB]default.t)
                    (
                      u: name
                    )
                  }
                )
                """);
        probe("extends-id", db + """
                ###Mapping
                Mapping my::M12
                (
                  my::S[a]: Relational { v: [my::DB]t.name }
                  my::T2[b] extends [a]: Relational { w: [my::DB]t.name }
                )
                """);
        probe("include-substitution", db + """
                ###Relational
                Database my::DB2 ( include my::DB )

                ###Mapping
                Mapping my::Base ()

                Mapping my::M13
                (
                  include my::Base[my::DB->my::DB2]
                )
                """);
        probe("inline-enum-transform", db + """
                ###Mapping
                Mapping my::M14
                (
                  my::S: Relational
                  {
                    v: EnumerationMapping em: [my::DB]t.name
                  }
                  E_1: EnumerationMapping em
                  {
                    A: ['a']
                  }
                )
                """);
        probe("nav-chain-db-each-step", db + """
                ###Mapping
                Mapping my::M15
                (
                  my::S: Relational
                  {
                    v: [my::DB] @J > (INNER) [my::DB] @K | t.name
                  }
                )
                """);
        probe("merge-op", """
                ###Pure
                Class my::P { n: String[1]; }

                ###Mapping
                Mapping my::M16
                (
                  *my::P : Operation
                  {
                    merge_OperationSetImplementation_1__SetImplementation_MANY_(p1,p2)
                  }
                )
                """);
    }

    @Test
    void bareTableShapes() throws Exception {
        probe("bare-no-db", """
                ###Relational
                Database my::DB
                (
                  Table t ( name VARCHAR(20), fid INT )
                  Table f ( id INT )
                  Join J ( t.fid = f.id )
                )

                ###Mapping
                Mapping my::M9
                (
                  my::S: Relational
                  {
                    v: t.name,
                    w: [my::DB]@J | f.id
                  }
                )
                """);
    }

    @Test
    void srcIdShapes() throws Exception {
        probe("src-set-id", """
                ###Pure
                Class my::S { v: String[1]; }
                Class my::T2 { w: String[1]; }

                ###Mapping
                Mapping my::MA
                (
                  my::S[srcSet]: Pure { ~src my::S v: 'x' }
                  my::T2: Pure
                  {
                    ~src srcSet
                    w: $src.v
                  }
                )
                """);
    }

    @Test
    void includeShapes() throws Exception {
        probe("include-plain", """
                ###Mapping
                Mapping my::Base ()

                Mapping my::M8
                (
                  include my::Base
                )
                """);
        probe("include-mapping-kw", """
                ###Mapping
                Mapping my::Base2 ()

                Mapping my::M9
                (
                  include mapping my::Base2
                )
                """);
    }

    @Test
    void moreShapes() throws Exception {
        probe("pure-m2m", """
                ###Pure
                Class my::S { fullName: String[1]; }
                Class my::T { first: String[1]; }

                ###Mapping
                Mapping my::M4
                (
                  my::T: Pure
                  {
                    ~src my::S
                    first: $src.fullName->substring(0, 1)
                  }
                )
                """);
        probe("operation", """
                ###Pure
                Class my::P { n: String[1]; }

                ###Mapping
                Mapping my::M5
                (
                  *my::P[u]: Operation
                  {
                    meta::pure::router::operations::union_OperationSetImplementation_1__SetImplementation_MANY_(a, b)
                  }
                  my::P[a]: Pure { ~src my::P n: 'x' }
                  my::P[b]: Pure { ~src my::P n: 'y' }
                )
                """);
        probe("include-and-assoc", """
                ###Pure
                Class my::A { id: String[1]; }
                Class my::B { id: String[1]; }
                Association my::AB { a: my::A[1]; b: my::B[1]; }

                ###Relational
                Database my::DB2
                (
                  Table TA ( id VARCHAR(10) PRIMARY KEY )
                  Table TB ( id VARCHAR(10) PRIMARY KEY )
                  Join AB_J (TA.id = TB.id)
                )

                ###Mapping
                Mapping my::M6
                (
                  my::A: Relational { ~mainTable [my::DB2]TA id: [my::DB2]TA.id }
                  my::B: Relational { ~mainTable [my::DB2]TB id: [my::DB2]TB.id }
                  my::AB: Relational
                  {
                    AssociationMapping
                    (
                      a: [my::DB2]@AB_J,
                      b: [my::DB2]@AB_J
                    )
                  }
                )
                """);
        probe("xstore", """
                ###Pure
                Class my::X { id: String[1]; }
                Class my::Y { id: String[1]; }
                Association my::XY { x: my::X[1]; y: my::Y[1]; }

                ###Mapping
                Mapping my::M7
                (
                  my::X: Pure { ~src my::X id: $src.id }
                  my::Y: Pure { ~src my::Y id: $src.id }
                  my::XY: XStore
                  {
                    x: $this.id == $that.id,
                    y: $this.id == $that.id
                  }
                )
                """);
    }

    @Test
    void shapes() throws Exception {
        probe("empty", """
                ###Mapping
                Mapping my::M ()
                """);
        probe("enum-mapping", """
                ###Pure
                Enum my::E { A, B }

                ###Mapping
                Mapping my::M2
                (
                  my::E: EnumerationMapping em
                  {
                    A: ['a1', 'a2'],
                    B: ['b']
                  }
                )
                """);
        probe("relational-class-mapping", """
                ###Pure
                Class my::Person { name: String[1]; age: Integer[1]; }

                ###Relational
                Database my::DB ( Table T ( id INTEGER PRIMARY KEY, NAME VARCHAR(20), AGE INTEGER ) )

                ###Mapping
                Mapping my::M3
                (
                  *my::Person: Relational
                  {
                    ~primaryKey ( [my::DB]T.id )
                    ~mainTable [my::DB]T
                    name: [my::DB]T.NAME,
                    age: [my::DB]T.AGE
                  }
                )
                """);
    }
}
