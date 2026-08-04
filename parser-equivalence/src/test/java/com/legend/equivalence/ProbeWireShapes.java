package com.legend.equivalence;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.finos.legend.engine.language.pure.grammar.from.PureGrammarParser;
import org.finos.legend.engine.protocol.pure.m3.PackageableElement;
import org.finos.legend.engine.protocol.pure.v1.model.context.PureModelContextData;
import org.finos.legend.engine.shared.core.ObjectMapperFactory;
import org.junit.jupiter.api.Test;

/**
 * NOT a gate — an instrument. Prints the reference parser's exact bytes for constructs the
 * emitter does not cover yet, so emit rules are written from observation, never inference
 * (the same discipline as ProtocolEmitterTest's pinned constants).
 *
 * Run on demand: mvn test -pl parser-equivalence -Dtest=ProbeWireShapes
 */
class ProbeWireShapes {

    private final ObjectMapper mapper =
            ObjectMapperFactory.getNewStandardObjectMapperWithPureProtocolExtensionSupports();
    private final PureGrammarParser parser = PureGrammarParser.newInstance();

    private void dump(String label, String code) throws Exception {
        System.out.println("======== " + label + " ========");
        System.out.println(code);
        System.out.println("--------");
        PureModelContextData pmcd = parser.parseModel(code);
        for (PackageableElement e : pmcd.getElements()) {
            if (!"SectionIndex".equals(e.name)) {
                System.out.println(mapper.writeValueAsString(e));
            }
        }
        System.out.println();
    }

    @Test
    void probe() throws Exception {
        dump("generic property type", """
                Class a::C
                {
                  p: a::D<String>[1];
                }
                """);
        dump("nested generic + multiple args", """
                Class b::C
                {
                  p: b::E<String, b::D<Integer>>[0..1];
                }
                """);
        dump("generic supertype", """
                Class c::C extends c::D<String>
                {
                }
                """);
        dump("default value", """
                Class d::C
                {
                  flag: Boolean[1] = false;
                  n: Integer[1] = 42;
                  s: String[1] = 'x';
                }
                """);
        dump("qualified property", """
                Class e::C
                {
                  first: String[1];
                  greet() {'hi ' + $this.first}: String[1];
                  greetN(n: Integer[1]) {$this.first + $n->toString()}: String[1];
                }
                """);
        dump("operator zoo", """
                Class g::C
                [
                  cMinus: $this.n - 1 > 0,
                  cTimes: $this.n * 2 > 0,
                  cDiv: $this.n / 2 > 0,
                  cEq: $this.n == 1,
                  cNeq: $this.n != 1,
                  cAnd: ($this.n > 1) && ($this.n < 9),
                  cOr: ($this.n > 1) || ($this.n < 9),
                  cNot: !($this.n > 1),
                  cChain: $this.s + 'a' + 'b' == 'x',
                  cCall: $this.s->startsWith('a'),
                  cColl: $this.n->in([1, 2, 3])
                ]
                {
                  n: Integer[1];
                  s: String[1];
                }
                """);
        dump("operator zoo emittable", """
                Class h::C
                [
                  cMinus: $this.n - 1 > 0,
                  cTimes: $this.n * 2 > 0,
                  cDiv: $this.n / 2 > 0,
                  cEq: $this.n == 1,
                  cNeq: $this.n != 1,
                  cAnd: ($this.n > 1) && ($this.n < 9),
                  cOr: ($this.n > 1) || ($this.n < 9),
                  cNot: !($this.n > 1),
                  cChain: $this.s + 'a' + 'b',
                  cCall: $this.s->startsWith('a'),
                  cColl: $this.n->in([1, 2, 3])
                ]
                {
                  n: Integer[1];
                  s: String[1];
                }
                """);
        dump("constraint", """
                Class f::C
                [
                  c1: $this.n > 1,
                  c2
                  (
                    ~function: $this.n < 10
                    ~message: 'n is ' + $this.n->toString()
                  )
                ]
                {
                  n: Integer[1];
                }
                """);
    }
}
