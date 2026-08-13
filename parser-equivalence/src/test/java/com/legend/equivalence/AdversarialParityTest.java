// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend.equivalence;

import org.finos.legend.engine.language.pure.grammar.from.PureGrammarParser;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * THE GENERATIVE DIFFERENTIAL GATE (2026-08-12 adversarial audit): inputs
 * the harvested corpus structurally CANNOT contain — engine/pure repos only
 * spell what their own tests spell — verdict-adjudicated against the live
 * 4.138.2 oracle in this JVM. Every family below found a real divergence on
 * its first run; each fixed row stays here as a regression pin, and the
 * whole suite runs in ~5s.
 *
 * <p>Three verdict classes are asserted: ACCEPT-parity (both accept or both
 * refuse), no INTERNAL exceptions on lite's side (a refusal must be a
 * {@code ParseException}, never a raw JDK exception or an
 * {@code UnsupportedOperationException} invariant trip on engine-legal
 * input), and a DIVERGENCE RATCHET for the named still-open rows (shrink
 * only; a new divergence in a green family is a hard failure).
 *
 * <p>This gate deliberately does NOT compare bytes — {@code CorpusSweepTest}
 * owns byte parity. Verdict asymmetry is the blind spot this class exists
 * to close (deep-audit H1: "byte parity is blind where the oracle refuses";
 * GATES.md: "a corpus sweep structurally cannot find a disagreement about a
 * form the corpus never contains").
 */
class AdversarialParityTest {

    private enum Verdict { ACCEPTS, REFUSES, LITE_INTERNAL_ERROR }

    private static Verdict engine(String src) {
        try {
            PureGrammarParser.newInstance().parseModel(src);
            return Verdict.ACCEPTS;
        } catch (Throwable t) {
            return Verdict.REFUSES;
        }
    }

    private static Verdict lite(String src) {
        try {
            com.legend.parser.PmcdParser.parseDocument(src);
            return Verdict.ACCEPTS;
        } catch (com.legend.parser.ParseException e) {
            return Verdict.REFUSES;
        } catch (Throwable t) {
            return Verdict.LITE_INTERNAL_ERROR;
        }
    }

    private record Row(String label, String src) {
    }

    private static void runFamily(String family, List<Row> rows,
            int maxDivergent) {
        List<String> divergent = new ArrayList<>();
        List<String> internal = new ArrayList<>();
        for (Row r : rows) {
            Verdict e = engine(r.src());
            Verdict l = lite(r.src());
            if (l == Verdict.LITE_INTERNAL_ERROR) {
                internal.add(r.label());
            } else if (e != l) {
                divergent.add(r.label() + " (engine " + e + ", lite " + l + ")");
            }
        }
        assertEquals(List.of(), internal,
                family + ": lite threw a NON-ParseException — internal "
                        + "errors on user input are always bugs");
        if (divergent.size() > maxDivergent) {
            assertEquals(List.of(), divergent, family
                    + ": verdict divergences grew past the ratchet ("
                    + maxDivergent + ")");
        }
    }

    // ------------------------------------------------------------------
    // Families. Every keyword the lexer fuses or classifies specially is
    // exercised in every identifier position; the section/comment/escape
    // families each reproduce a divergence found (and fixed) 2026-08-12.
    // ------------------------------------------------------------------

    private static final String[] KEYWORDS = {"all", "let", "import",
            "extends", "native", "as", "include", "query", "pattern",
            "owners", "documentation", "execution", "mapping", "runtime",
            "connection", "connections", "mappings", "store", "type",
            "specification", "auth", "filter", "distinct", "groupBy",
            "mainTable", "primaryKey", "src", "and", "or", "true", "false",
            "testSuites", "comparator", "allVersions"};

    @Test
    void keywordAsIdentifierEverywhere() {
        List<Row> rows = new ArrayList<>();
        for (String kw : KEYWORDS) {
            rows.add(new Row("class " + kw,
                    "###Pure\nClass a::" + kw + " { x: String[1]; }\n"));
            rows.add(new Row("prop " + kw,
                    "###Pure\nClass a::A { " + kw + ": String[1]; }\n"));
            rows.add(new Row("var " + kw, "###Pure\nfunction f::f(): Integer[1]\n{\n  let "
                    + kw + " = 1;\n  $" + kw + " + 1;\n}\n"));
            rows.add(new Row("param " + kw, "###Pure\nfunction f::f(" + kw
                    + ": Integer[1]): Integer[1]\n{\n  $" + kw + ";\n}\n"));
            rows.add(new Row("enumval " + kw,
                    "###Pure\nEnum a::E { " + kw + " }\n"));
            rows.add(new Row("colspec " + kw,
                    "###Pure\nfunction f::f(): Any[*]\n{\n  #>{a::db.t}#->select(~"
                            + kw + ")\n}\n"));
        }
        runFamily("keyword-as-identifier", rows, 0);
    }

    @Test
    void sectionBoundaryShapes() {
        runFamily("section-boundaries", List.of(
                new Row("mid-line ###",
                        "###Pure\nClass a::A { x: String[1]; } ###Mapping\nMapping m::M ()\n"),
                new Row("leading-space ###",
                        "###Pure\nClass a::A { x: String[1]; }\n ###Mapping\nMapping m::M ()\n"),
                new Row("### in block comment",
                        "###Pure\nClass a::A { x: String[1]; }\n/* c\n###Mapping\n*/\nClass a::B { y: String[1]; }\n"),
                new Row("### in string",
                        "###Pure\nfunction f::f(): String[1]\n{\n  'a\n###Mapping\nb'\n}\n"),
                new Row("unterminated block comment",
                        "###Pure\nClass a::A { x: String[1]; } /* never closed"),
                new Row("plain two sections",
                        "###Pure\nClass a::A { x: String[1]; }\n###Mapping\nMapping m::M ()\n")),
                0);
    }

    @Test
    void escapesAndLiterals() {
        runFamily("escapes-and-literals", List.of(
                new Row("escaped quote", "###Pure\nfunction f::f(): String[1]\n{\n  'it\\'s'\n}\n"),
                new Row("unicode escape", "###Pure\nfunction f::f(): String[1]\n{\n  'a\\u0041b'\n}\n"),
                new Row("octal escape", "###Pure\nfunction f::f(): String[1]\n{\n  'a\\101b'\n}\n"),
                new Row("unknown escape", "###Pure\nfunction f::f(): String[1]\n{\n  'a\\qb'\n}\n"),
                new Row("backslash at EOF", "###Pure\nfunction f::f(): String[1]\n{\n  'abc\\"),
                new Row("graphfetch escaped quote",
                        "###Pure\nClass a::A { name: String[1]; }\nfunction f::f(): Any[*]\n{\n  a::A.all()->graphFetch(#{a::A{name('it\\'s')}}#)\n}\n"),
                new Row("decimal leading zeros", "###Pure\nfunction f::f(): Any[*]\n{\n  007d;\n}\n"),
                new Row("bare fraction decimal", "###Pure\nfunction f::f(): Any[*]\n{\n  [.5d]\n}\n"),
                new Row("overflowing integer", "###Pure\nfunction f::f(): Any[*]\n{\n  99999999999999999999\n}\n"),
                new Row("overflow multiplicity", "###Pure\nClass a::A { x: String[1..9999999999]; }\n")),
                0);
    }

    @Test
    void duplicateFieldsRefuseOnceOnly() {
        runFamily("duplicate-fields", List.of(
                new Row("dataspace title", "###DataSpace\nDataSpace a::DS\n{\n  executionContexts: [];\n  defaultExecutionContext: '';\n  title: 'one';\n  title: 'two';\n}\n"),
                new Row("dataspace description", "###DataSpace\nDataSpace a::DS\n{\n  executionContexts: [];\n  defaultExecutionContext: '';\n  description: 'a';\n  description: 'b';\n}\n"),
                new Row("connection class", "###Connection\nJsonModelConnection a::c\n{\n  class: a::A;\n  class: a::A;\n  url: 'data:x';\n}\n"),
                new Row("connection type", "###Connection\nRelationalDatabaseConnection a::c\n{\n  store: a::db;\n  type: H2;\n  type: H2;\n  specification: LocalH2 {};\n  auth: DefaultH2;\n}\n"),
                new Row("runtime mappings", "###Runtime\nRuntime a::r\n{\n  mappings: [a::m];\n  mappings: [a::m];\n}\n"),
                new Row("profile tags", "###Pure\nProfile a::p { tags: [a]; tags: [b]; }\n"),
                new Row("missing Email address", "###DataSpace\nDataSpace a::DS\n{\n  executionContexts: [];\n  defaultExecutionContext: '';\n  supportInfo: Email {\n  };\n}\n"),
                new Row("bad port", "###Connection\nRelationalDatabaseConnection a::c\n{\n  store: a::db;\n  type: H2;\n  specification: Static { name: 'n'; host: 'h'; port: xyz; };\n  auth: DefaultH2;\n}\n")),
                0);
    }

    @Test
    void expressionEnvelope() {
        runFamily("expression-envelope", List.of(
                new Row("milestoning non-literal", "###Pure\nClass a::A { name: String[1]; }\nfunction f::f(): Any[*]\n{\n  a::A.all(now())\n}\n"),
                new Row("enum value named all", "###Pure\nEnum a::E { all, none }\nfunction f::i(): a::E[1]\n{\n  a::E.all\n}\n"),
                new Row("bracket index", "###Pure\nfunction f::h(x: String[*]): String[1]\n{\n  $x[0]\n}\n"),
                new Row("not-equal <>", "###Pure\nfunction f::f(a: Integer[1], b: Integer[1]): Boolean[1]\n{\n  $a <> $b;\n}\n"),
                new Row("arith in collection", "###Pure\nfunction f::f(): Any[*]\n{\n  [1 + 2, 3]\n}\n"),
                new Row("empty function body", "###Pure\nfunction f::f(): Integer[1]\n{\n}\n"),
                new Row("projection class", "###Pure\nClass a::P projects a::A { +[name] }\n"),
                new Row("Z-suffix datetime", "###Pure\nfunction f::f(): Any[*]\n{\n  %2024-01-01T10:00:00Z\n}\n"),
                new Row("date feb-30", "###Pure\nfunction f::f(): Any[*]\n{\n  %2024-02-30\n}\n"),
                new Row("exponent decimal", "###Pure\nfunction f::f(): Any[*]\n{\n  1e3d;\n}\n"),
                new Row("multi-stmt missing final semi", "###Pure\nfunction f::f(x: Integer[1]): Any[*]\n{\n  let y = $x;\n  $y + 1\n}\n"),
                new Row("single stmt no semi", "###Pure\nfunction f::f(x: Integer[1]): Any[*]\n{\n  $x + 1\n}\n"),
                new Row("single-line triple-quote tv", "###Pure\nProfile a::p { tags: [doc]; }\nClass {a::p.doc = '''hello'''} a::A { x: String[1]; }\n"),
                new Row("comparator expr", "###Pure\nfunction f::f(): Any[*]\n{\n  [1,2]->contains(1, comparator(a: Integer[1], b: Integer[1]): Boolean[1] { $a == $b })\n}\n"),
                new Row("quoted path segment with ::", "###Pure\nClass a::'b::c' { x: String[1]; }\n")),
                0);
    }
}
