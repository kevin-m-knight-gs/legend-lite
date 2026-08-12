package com.legend.equivalence;

import org.finos.legend.engine.language.pure.grammar.from.PureGrammarParser;
import org.junit.jupiter.api.Test;

/** PROBE: oracle verdicts over small grammar variants. Diagnostic. */
class ZVariantTrialProbe {

    private static void trial(PureGrammarParser oracle, String name,
            String text) {
        try {
            oracle.parseModel(text);
            System.out.println("@@ ACCEPT " + name);
        } catch (Throwable t) {
            Throwable root = t;
            while (root.getCause() != null && root.getCause() != root) {
                root = root.getCause();
            }
            System.out.println("@@ REFUSE " + name + " :: "
                    + String.valueOf(root.getMessage())
                            .replaceAll("\\s+", " "));
        }
    }

    @Test
    void trials() {
        PureGrammarParser oracle = PureGrammarParser.newInstance();
        trial(oracle, "let-no-final-semi",
                "function f::q(): Integer[1] { let x = 1; $x }");
        trial(oracle, "let-final-semi",
                "function f::q(): Integer[1] { let x = 1; $x; }");
        trial(oracle, "no-let-no-semi",
                "function f::q(): Integer[1] { 1 + 1 }");
        trial(oracle, "no-let-semi",
                "function f::q(): Integer[1] { 1 + 1; }");
        trial(oracle, "two-exprs-final-semi",
                "function f::q(): Integer[1] { let x = 1; let y = 2; $x + $y; }");
        trial(oracle, "specific-import",
                "import a::b::C;\nClass x::Y { p: a::b::C[1]; }");
        trial(oracle, "star-import",
                "import a::b::*;\nClass x::Y { p: String[1]; }");
        trial(oracle, "midfile-import-group",
                "Class a::A { p: String[1]; }\nimport a::*;\nClass b::B { q: String[1]; }");
        trial(oracle, "dup-star-imports",
                "import a::b::*;\nimport a::b::*;\nClass x::Y { p: String[1]; }");
        trial(oracle, "trailing-comma-propmap",
                "###Relational\nDatabase d::DB ( Table T (X INTEGER) )\n"
                        + "###Mapping\nMapping m::M ( x::Y: Relational { ~mainTable [d::DB] T y: T.X, } )");
        trial(oracle, "diagram-width",
                "###Diagram\nDiagram my::D(width=1.5, height=2.0) {}");
        trial(oracle, "diagram-plain",
                "###Diagram\nDiagram my::D {}");
        trial(oracle, "runtime-no-final-semi",
                "###Runtime\nRuntime r::R { mappings: [m::M]; connections: [d::DB: [c: c::C]] }");
        trial(oracle, "runtime-final-semi",
                "###Runtime\nRuntime r::R { mappings: [m::M]; connections: [d::DB: [c: c::C]]; }");
        trial(oracle, "xstore-dup-props",
                "###Mapping\nMapping m::M ( a::A: XStore { e[f, p]: $this.a == $that.b, e[f, p]: $this.a == $that.b } )");
        String bindingModel = "Class m::P { manager: String[1]; }\n"
                + "###Relational\nDatabase db::D (Table T (X INTEGER, F VARCHAR(10)))\n"
                + "###Mapping\nMapping m::M ( m::P: Relational { ~mainTable [db::D] T "
                + "manager: Binding m::B: [db::D] T.F } )";
        trial(oracle, "binding-transformer-oracle", bindingModel);
        try {
            com.legend.parser.ElementParser.parseLegendPlatform(bindingModel);
            System.out.println("@@ LITE-ACCEPT binding-transformer");
        } catch (Throwable t) {
            System.out.println("@@ LITE-REFUSE binding-transformer :: "
                    + String.valueOf(t.getMessage()).replaceAll("\\s+", " "));
        }
        trial(oracle, "enum-mapping-trailing-comma",
                "###Mapping\nMapping m::M ( e::E: EnumerationMapping Mid { A: 'a', B: 'b', } )");
    }
}
