package com.legend.equivalence.harvest;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.HashSet;
import java.util.Set;

/** The sink behind the shadowed engine test bases: every fixture the
 *  engine's own grammar/compiler tests assemble at runtime lands here as
 *  one JSONL row — {@code {source, expectedError, origin}} — deduped by
 *  text. The dump becomes corpus tier C6. */
public final class FixtureRecorder {

    private FixtureRecorder() {
    }

    private static final Set<String> SEEN = new HashSet<>();
    private static final Path OUT = Path.of(System.getProperty(
            "fixture.dump", "target/engine-fixtures.jsonl"));
    private static final com.fasterxml.jackson.databind.ObjectMapper JSON =
            new com.fasterxml.jackson.databind.ObjectMapper();

    public static synchronized void record(String source,
            String expectedError, String kind) {
        if (source == null || source.isBlank() || !SEEN.add(source)) {
            return;
        }
        try {
            Files.createDirectories(OUT.getParent());
            var node = JSON.createObjectNode();
            node.put("source", source);
            if (expectedError != null) {
                node.put("expectedError", expectedError);
            }
            node.put("kind", kind);
            node.put("origin", origin());
            Files.writeString(OUT, JSON.writeValueAsString(node) + "\n",
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** First engine test frame on the stack — the fixture's provenance. */
    private static String origin() {
        for (StackTraceElement st : Thread.currentThread().getStackTrace()) {
            String c = st.getClassName();
            if (c.startsWith("org.finos.legend.engine.")
                    && !c.contains("TestGrammarRoundtrip")
                    && !c.contains("TestGrammarParser")
                    && !c.contains("TestCompilationFromGrammar")) {
                return c + "#" + st.getMethodName();
            }
        }
        return "?";
    }
}
