package com.legend;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * THE PLANNER RUNS ON java.base ALONE: parse, type, resolve, lower and render a
 * query in a JVM whose only module is {@code java.base} ({@code java.sql}
 * absent). That is what a WASM or slim-runtime planner needs, and it is
 * invisible otherwise — every build and test passes while it is broken. It was
 * broken by ONE line: a {@code catch (java.sql.SQLException)} in
 * {@code Compiler}, which the verifier resolves when the class links, taking the
 * plan surface with it (now {@code exec.JdbcMetadata}). Checked by RUNNING the
 * planner, not by scanning source for JDBC names — a scan misses a catch clause
 * or static initializer in a class the plan path starts loading. Its build-graph
 * form is a planner target compiled with --limit-modules java.base, once the
 * planner packages separate from exec (the package untangle).
 */
class PlannerRunsOnJavaBaseTest {

    @Test
    @DisplayName("Compiler.plan runs with java.base as the only module")
    void planOnJavaBaseAlone() throws Exception {
        Process p = new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "--limit-modules", "java.base",
                "-cp", System.getProperty("java.class.path"),
                PlanOnJavaBase.class.getName())
                .redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(0, p.waitFor(), "the planner needs more than java.base:\n" + out);
        assertTrue(out.contains("java.sql visible: false"), "java.sql was visible:\n" + out);
        assertTrue(out.contains("SELECT") && out.contains("GROUP BY"), "no SQL planned:\n" + out);
    }
}
