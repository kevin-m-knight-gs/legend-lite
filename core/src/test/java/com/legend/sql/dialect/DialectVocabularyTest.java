// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend.sql.dialect;

import com.legend.sql.SqlExpr;
import com.legend.testing.SourceFiles;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * EVERY DIALECT DECIDES EVERY POINT OF ITS VOCABULARY (the user's decision P1b, 2026-10-13;
 * docs/DIALECT_LEAK_CENSUS_AND_PLAN_2026_10_12.md Phase 1b). Each concrete dialect class, a family member's included,
 * declares its own {@code call} and {@code reducer} and spells each {@code SqlFn} and {@code SqlAgg.Fn} in a switch
 * EXPRESSION with no {@code default} arm, so javac makes it decide every function and aggregate: a new one breaks the
 * build in every dialect until each spells it, refuses it by name, or names it among those it takes from its parent.
 * It declares its own {@code formatText} too: which date-format vocabulary it writes is its choice, never a default;
 * and its own {@code scalarTypeName}, every scalar type's name or a refusal, in a switch with no {@code default}. A
 * {@code default} would let a dialect inherit a spelling no one chose for it, which is how a DuckDB spelling reached
 * databases that never ran it (decision D1).
 */
@Tag("guardrail")
class DialectVocabularyTest {

    private static final String DIALECTS = "core/src/main/java/com/legend/sql/dialect";

    /** A vocabulary: the method each dialect declares, its parameters, and the switch that decides it (none where a
     *  dialect may take another's whole table, as a date format's parts are). */
    private record Vocabulary(String method, Class<?>[] params, @com.legend.base.Nullable String sw) {
    }

    private static final List<Vocabulary> VOCABULARIES = List.of(
            new Vocabulary("call", new Class<?>[] {SqlWriter.class, SqlExpr.Call.class, int.class}, "switch (c.fn())"),
            new Vocabulary("reducer", new Class<?>[] {SqlWriter.class, com.legend.sql.SqlAgg.Reducer.class},
                    "switch (r.fn())"),
            new Vocabulary("formatText", new Class<?>[] {SqlExpr.FormatLit.class}, null),
            new Vocabulary("scalarTypeName", new Class<?>[] {com.legend.sql.SqlType.Scalar.class}, "switch (s)"));

    @Test
    void everyConcreteDialectDecidesEveryFunctionAndAggregateItself() throws IOException, ClassNotFoundException {
        List<String> wrong = new ArrayList<>();
        int dialects = 0;
        for (Path f : SourceFiles.in(DIALECTS)) {
            String file = f.getFileName().toString();
            if (!file.endsWith(".java") || file.equals("package-info.java")) {
                continue;
            }
            String name = file.substring(0, file.length() - ".java".length());
            Class<?> cls = Class.forName("com.legend.sql.dialect." + name, false,
                    DialectVocabularyTest.class.getClassLoader());
            if (cls == AnsiSqlRenderer.class || !AnsiSqlRenderer.class.isAssignableFrom(cls)
                    || Modifier.isAbstract(cls.getModifiers())) {
                continue;
            }
            dialects++;
            String src = Files.readString(f);
            for (Vocabulary v : VOCABULARIES) {
                boolean declared = java.util.Arrays.stream(cls.getDeclaredMethods())
                        .anyMatch(m -> m.getName().equals(v.method())
                                && java.util.Arrays.equals(m.getParameterTypes(), v.params()));
                if (!declared) {
                    wrong.add(name + ": declares no " + v.method() + "() of its own (it takes the whole vocabulary from"
                            + " its parent unasked)");
                    continue;
                }
                if (v.sw() == null) {
                    continue;
                }
                // the switch that decides: in the method itself, or in the one it hands every point to
                // (SQLite.calls, ClickHouse.reducerIn); a helper's own switch over a subset is not it
                String first = "\\s*\\(\\s*[\\w.]*" + v.params()[0].getSimpleName() + "\\b";
                String decider = body(src, "\\b" + v.method() + first);
                if (decider != null && !decider.contains(v.sw())) {
                    java.util.regex.Matcher to = java.util.regex.Pattern
                            .compile("return\\s+(\\w+)\\(writer,\\s*[cr]\\b").matcher(decider);
                    decider = to.find() ? body(src, "\\b" + to.group(1) + first) : null;
                }
                if (decider == null) {
                    wrong.add(name + ": its " + v.method() + "() was not found in its source");
                    continue;
                }
                int switches = 0;
                for (int at = decider.indexOf(v.sw()); at >= 0; at = decider.indexOf(v.sw(), at + 1)) {
                    switches++;
                    String before = decider.substring(Math.max(0, at - 12), at).strip();
                    if (!(before.endsWith("return") || before.endsWith("yield") || before.endsWith("->")
                            || before.endsWith("="))) {
                        wrong.add(name + ": its " + v.method() + "()'s " + v.sw() + " is a statement, which javac does"
                                + " not check");
                    }
                    if (hasDefault(decider, decider.indexOf('{', at))) {
                        wrong.add(name + ": its " + v.method() + "()'s " + v.sw() + " has a default arm");
                    }
                }
                if (switches == 0) {
                    wrong.add(name + ": its " + v.method() + "() decides in no " + v.sw());
                }
            }
        }
        assertTrue(dialects >= 15, "the dialect package's concrete dialects were not found: " + dialects);
        assertTrue(wrong.isEmpty(), "a dialect that does not decide its whole vocabulary itself: " + wrong);
    }

    /** The body of the method whose declaration {@code declaration} (a regex) finds, its braces matched past
     *  comments and literals; null when there is none. */
    private static @com.legend.base.Nullable String body(String src, String declaration) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile(declaration).matcher(src);
        while (m.find()) {
            int open = src.indexOf('{', m.end());
            int semi = src.indexOf(';', m.end());
            if (open < 0 || (semi >= 0 && semi < open)) {
                continue;   // a call, not the declaration
            }
            int depth = 0;
            for (int i = open; i < src.length(); i++) {
                int skipped = skip(src, i);
                if (skipped != i) {
                    i = skipped - 1;
                    continue;
                }
                if (src.charAt(i) == '{') {
                    depth++;
                } else if (src.charAt(i) == '}' && --depth == 0) {
                    return src.substring(open, i + 1);
                }
            }
        }
        return null;
    }

    /** Whether the switch body opening at {@code open} has a {@code default} arm of its own (not a nested switch's). */
    private static boolean hasDefault(String src, int open) {
        int depth = 0;
        for (int i = open; i < src.length(); i++) {
            int skipped = skip(src, i);
            if (skipped != i) {
                i = skipped - 1;
                continue;
            }
            char c = src.charAt(i);
            if (c == '{' || c == '(') {
                depth++;
            } else if (c == '}' || c == ')') {
                depth--;
                if (depth == 0) {
                    return false;
                }
            } else if (depth == 1 && src.startsWith("default", i) && !Character.isJavaIdentifierPart(src.charAt(i - 1))
                    && src.substring(i + "default".length()).stripLeading().startsWith("->")) {
                return true;
            }
        }
        return false;
    }

    /** Past a comment or a string or char literal starting at {@code i}; {@code i} itself when none starts there. */
    private static int skip(String src, int i) {
        if (src.startsWith("//", i)) {
            int end = src.indexOf('\n', i);
            return end < 0 ? src.length() : end;
        }
        if (src.startsWith("/*", i)) {
            return src.indexOf("*/", i + 2) + 2;
        }
        if (src.startsWith("\"\"\"", i)) {
            return src.indexOf("\"\"\"", i + 3) + 3;
        }
        char c = src.charAt(i);
        if (c == '"' || c == '\'') {
            int j = i + 1;
            while (src.charAt(j) != c) {
                j += src.charAt(j) == '\\' ? 2 : 1;
            }
            return j + 1;
        }
        return i;
    }

}
