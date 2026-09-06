// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0
package com.legend.exec;

/**
 * TEST-INPUT resources a corpus program names by classpath path
 * ({@code loadCsvToDbTable('/core_relational/.../employees.csv', …)} —
 * the engine's native reads the file from its classpath). The reference
 * checkout is SPEC, never runtime: the executor never touches it; the
 * harness that owns the corpus registers a resolver (path → text) for
 * the run, exactly as it registers the replay oracle. Unregistered = a
 * loud wall, never an empty load.
 */
public final class TestResources {
    private TestResources() {
    }

    private static final ThreadLocal<java.util.function.Function<String, String>> RESOLVER =
            new ThreadLocal<>();

    /** The harness's resolver for this thread's run (null clears it). */
    public static void register(
            java.util.function.@com.legend.Nullable Function<String, String> resolver) {
        if (resolver == null) {
            RESOLVER.remove();
        } else {
            RESOLVER.set(resolver);
        }
    }

    /** The resource's text; loud when no resolver is registered. */
    public static String read(String path) {
        var r = RESOLVER.get();
        if (r == null) {
            throw new com.legend.error.NotImplementedException(
                    "test resource '" + path + "': no resource resolver is"
                    + " registered for this run");
        }
        return r.apply(path);
    }
}
