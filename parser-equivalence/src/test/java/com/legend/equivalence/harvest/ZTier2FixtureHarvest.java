package com.legend.equivalence.harvest;

import com.legend.testing.WalkedPath;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

/** HARVEST BY EXECUTION, tier 2: the checkout's EXTENSION grammar test
 *  sources, compiled per-file tolerantly against the shim classpath
 *  (unpublished tests-jars — relationalStore/service/persistence), then
 *  run reflectively like tier 1. {@code -Dtier2.classes=<dir>} points at
 *  the compiled classes; the shims (parent classloader) win, so every
 *  {@code test(...)} call records instead of asserting. Diagnostic. */
class ZTier2FixtureHarvest {

    @Test
    void harvest() throws Exception {
        String dir = System.getProperty("tier2.classes");
        if (dir == null) {
            System.out.println("@@ no -Dtier2.classes — skipping");
            return;
        }
        Path root = Path.of(dir);
        try (URLClassLoader loader = new URLClassLoader(
                new java.net.URL[]{root.toUri().toURL()},
                getClass().getClassLoader())) {
            int classes = 0;
            int methods = 0;
            int invoked = 0;
            Map<String, Integer> failures = new TreeMap<>();
            try (Stream<Path> s = Files.walk(root)) {
                for (Path p : s.filter(f -> f.toString().endsWith(".class"))
                        .filter(f -> !f.toString().contains("$"))
                        .sorted().toList()) {
                    String cls = WalkedPath.spell(root.relativize(p), ".")
                            .replace(".class", "");
                    if (!cls.substring(cls.lastIndexOf('.') + 1)
                            .startsWith("Test")) {
                        continue;
                    }
                    Class<?> c;
                    try {
                        c = Class.forName(cls, false, loader);
                    } catch (Throwable t) {
                        failures.merge("load", 1, Integer::sum);
                        continue;
                    }
                    if (c.isInterface()
                            || Modifier.isAbstract(c.getModifiers())) {
                        continue;
                    }
                    Object instance;
                    try {
                        instance = c.getDeclaredConstructor().newInstance();
                    } catch (Throwable t) {
                        failures.merge("instantiate", 1, Integer::sum);
                        continue;
                    }
                    classes++;
                    for (Method m : c.getMethods()) {
                        if (m.getAnnotation(org.junit.Test.class) == null
                                || m.getParameterCount() != 0) {
                            continue;
                        }
                        methods++;
                        try {
                            m.invoke(instance);
                            invoked++;
                        } catch (Throwable t) {
                            failures.merge("invoke-threw", 1, Integer::sum);
                        }
                    }
                }
            }
            System.out.println("@@ tier2 classes: " + classes + "; methods: "
                    + methods + "; completed: " + invoked + "; failures: "
                    + failures);
        }
        Path dump = Path.of(System.getProperty("fixture.dump",
                "target/engine-fixtures.jsonl"));
        System.out.println("@@ fixtures dumped: "
                + (Files.exists(dump) ? Files.lines(dump).count() : 0));
    }
}
