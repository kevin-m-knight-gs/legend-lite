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
