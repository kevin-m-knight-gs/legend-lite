// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend.equivalence;

import org.finos.legend.engine.language.pure.grammar.from.PureGrammarParser;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * ERROR-MESSAGE-TEXT parity for the refusals lite claims ENGINE-VERBATIM.
 * The old {@code StrictDialectParityTest} enforced this and was deleted in
 * the 2026-08-12 sweep consolidation — the doc claim ("refuses with the
 * engine's own messages") survived with no gate behind it (deep-audit §6 /
 * adversarial audit finding). This is its focused successor: every row
 * both sides refuse where lite's source carries an engine-verbatim string,
 * the FULL message text must be equal (lite's {@code [line:col]} prefix is
 * stripped; the engine carries position in a separate field).
 *
 * <p>Rows where lite's message is deliberately its own (generic mismatch
 * template, drift guards) do NOT belong here — this pins only the curated
 * verbatim set, so it can assert EQUALITY rather than a weaker contains.
 */
class MessageParityTest {

    private record Refusal(String label, String src) {
    }

    private static String engineMsg(String src) {
        try {
            PureGrammarParser.newInstance().parseModel(src);
            return "<ACCEPTS>";
        } catch (Throwable t) {
            Throwable root = t;
            return String.valueOf(root.getMessage()).split("\n")[0];
        }
    }

    private static String liteMsg(String src) {
        try {
            com.legend.parser.PmcdParser.parseDocument(src);
            return "<ACCEPTS>";
        } catch (Throwable t) {
            String m = String.valueOf(t.getMessage());
            // strip lite's "[line:col] " prefix — the engine carries the
            // position in SourceInformation, not the message
            return m.replaceFirst("^\\[\\d+:\\d+\\] ", "").split("\n")[0];
        }
    }

    @Test
    void engineVerbatimRefusalsMatchExactly() {
        List<Refusal> rows = List.of(
                new Refusal("dup field once-only",
                        "###DataSpace\nDataSpace a::DS\n{\n  executionContexts: [];\n  defaultExecutionContext: '';\n  title: 'a';\n  title: 'b';\n}\n"),
                new Refusal("required field",
                        "###DataSpace\nDataSpace a::DS\n{\n  executionContexts: [];\n  defaultExecutionContext: '';\n  supportInfo: Email {\n  };\n}\n"),
                new Refusal("unknown section parser",
                        "###NoSuchSection\nFoo a::b {}\n"),
                new Refusal("unsupported join type",
                        "###Pure\nClass a::A { x: String[1]; }\n"
                                + "###Relational\nDatabase a::db (Table t (id INT) Table u (id INT) Join j (t.id = u.id))\n"
                                + "###Mapping\nMapping a::m\n(\n  a::A: Relational\n  {\n    x: [a::db]@j > (inner)[a::db]@j | [a::db]t.id\n  }\n)\n"),
                new Refusal("bracket operation",
                        "###Pure\nfunction f::f(x: String[*]): String[1]\n{\n  $x[0]\n}\n"),
                new Refusal("profile dup tags",
                        "###Pure\nProfile a::p { tags: [a]; tags: [b]; }\n"),
                new Refusal("function generics refused",
                        "###Pure\nfunction f::f<T>(x: T[1]): T[1]\n{\n  $x\n}\n"));
        List<String> diffs = new ArrayList<>();
        for (Refusal r : rows) {
            String e = engineMsg(r.src());
            String l = liteMsg(r.src());
            if ("<ACCEPTS>".equals(e) || "<ACCEPTS>".equals(l)) {
                diffs.add(r.label() + ": verdict asymmetry (engine=" + e
                        + ", lite=" + l + ") — not a message row");
            } else if (!e.equals(l)) {
                diffs.add(r.label() + ":\n    engine: " + e + "\n    lite:   " + l);
            }
        }
        assertEquals(List.of(), diffs,
                "engine-verbatim refusal messages drifted");
    }
}
