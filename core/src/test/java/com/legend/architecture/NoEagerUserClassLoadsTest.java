package com.legend.architecture;

import com.legend.Compiler;
import com.legend.compiler.NameResolver;
import com.legend.compiler.spec.SpecCompiler;
import com.legend.compiler.element.ModelContext;
import com.legend.parser.SpecParser;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Lazy-loading behavioral guard — Shape 2 (rebuilt for core; the engine-era
 * original wrapped PureModelBuilder and died with that module).
 *
 * <p>The structural guard ({@link NoEagerTypeReferencesTest}) catches
 * resolved-reference FIELDS. This guard catches the other leak: compile paths
 * that DYNAMICALLY resolve unrelated user elements via
 * {@code ModelContext.findClass}/{@code findEnum} when the query only needs a
 * small subset — the property cross-project lazy loading depends on
 * (docs/BAZEL_IMPLEMENTATION_PLAN.md §4.7 phase C).
 *
 * <p><b>Design:</b> model COMPILE is legitimately eager (it builds the
 * registry). QUERY type-checking must be lazy: a fail-fast {@link ModelContext}
 * proxy is handed to {@link SpecCompiler}; any {@code findClass}/{@code findEnum}
 * for a designated "cold" FQN throws immediately, producing a stack trace that
 * names the exact offending call site.
 *
 * <p><b>Cold elements</b> are arranged to be structurally REACHABLE from the
 * model graph (a superclass of an unused sibling, a declared enum, an isolated
 * class) so naive walk-everything leaks trip at least one of them — not just
 * orphans the simplest bug wouldn't find.
 */
class NoEagerUserClassLoadsTest {

    private static final Set<String> COLD_FQNS = Set.of(
            "cold::ColdIsolated",
            "cold::ColdSuper",
            "cold::ColdSibling",
            "cold::ColdEnum");

    private static final String MODEL_SOURCE = """
            Class cold::ColdIsolated { placeholder: String[1]; }
            Class cold::ColdSuper { s: String[1]; }
            Class cold::ColdSibling extends cold::ColdSuper { extra: String[1]; }
            Enum cold::ColdEnum { A, B, C }

            Class warm::UnusedSibling extends cold::ColdSuper { u: String[1]; }
            Class warm::User { name: String[1]; age: Integer[1]; }
            """;

    /** Query shapes exercising distinct checker paths (getAll, filter,
     * project) so an eager traversal added in any of them is caught. */
    private static final List<String> QUERIES = List.of(
            "|warm::User.all()",
            "|warm::User.all()->filter(x|$x.age > 30)",
            "|warm::User.all()->project([x|$x.name, x|$x.age], ['n', 'a'])");

    @Test
    void typeCheckingDoesNotLoadColdUserElements() {
        // 1. Model compile: legitimately eager — this is how the cold
        //    elements get into the registry at all.
        ModelContext raw = Compiler.compileModel(MODEL_SOURCE);

        // 2. Fail-fast proxy: from here on, any findClass/findEnum of a cold
        //    FQN throws with the offending compile call site in the trace.
        AtomicBoolean warmSeen = new AtomicBoolean(false);
        ModelContext strict = (ModelContext) Proxy.newProxyInstance(
                ModelContext.class.getClassLoader(),
                new Class<?>[] {ModelContext.class},
                (proxy, method, args) -> {
                    if (("findClass".equals(method.getName())
                            || "findEnum".equals(method.getName()))
                            && args != null && args.length == 1
                            && args[0] instanceof String fqn) {
                        if (fqn.startsWith("warm::")) {
                            warmSeen.set(true);
                        }
                        if (COLD_FQNS.contains(fqn)) {
                            throw new AssertionError("lazy-loading violation: "
                                    + method.getName() + "(\"" + fqn + "\") was"
                                    + " called during QUERY type-checking. This"
                                    + " cold element is referenced by no query —"
                                    + " some compiler path eagerly resolves"
                                    + " unrelated elements. The stack trace names"
                                    + " the call site.");
                        }
                    }
                    try {
                        return method.invoke(raw, args);
                    } catch (InvocationTargetException e) {
                        throw e.getCause();
                    }
                });

        // 3. Every query shape type-checks through the strict context.
        for (String query : QUERIES) {
            new SpecCompiler(strict).typeQueryBody(
                    NameResolver.resolveQuery(SpecParser.parse(query)));
        }

        // 4. Sanity: the pipeline actually looked up the warm class — without
        //    this, a wiring mistake would make the test pass vacuously.
        if (!warmSeen.get()) {
            throw new AssertionError("Test wiring sanity check failed:"
                    + " type-check completed but never looked up warm::User —"
                    + " the guard is vacuously passing and must be fixed.");
        }
    }
}
