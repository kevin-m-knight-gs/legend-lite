package com.legend.compiler.spec.typed;

import java.util.List;

/**
 * Where a construction ({@code ^C(...)}, {@code ^$x(...)}) is evaluated, which is the identity of every object it
 * builds (docs/OBJECT_IDENTITY_2026_10_20.md §3.2). Pure makes a new object at each evaluation; a let names one.
 *
 * <ul>
 *   <li>{@code origin}: the source node the typer read the construction from. A let in a lambda body is spliced into
 *       its uses before typing, so each use is typed as its own construction: every copy has the one origin.</li>
 *   <li>{@code path}: set by the inliner (G½) where it reaches the construction: the instantiations around it (a
 *       call inlined, a lambda applied, an element of a spelled list unrolled), inside its innermost lambda when it
 *       is in one. {@code null} until reached.</li>
 *   <li>{@code complete}: whether the path is known from the query's root: at once outside every lambda, else where its
 *       lambda is used. The origin and the whole path are then the construction's {@link Site}, the leading part of
 *       every id it gives: two runs of the inliner over the same statements give the same sites.</li>
 *   <li>{@code iterations}: the element parameters of the maps it is evaluated in, outermost first: an object built
 *       inside a map is built once per element, and the lowering reads each element's position.</li>
 * </ul>
 *
 * <p>The origin is compared by identity: two constructions spelled alike are two constructions.
 */
public record Evaluation(Object origin, @com.legend.base.Nullable List<Integer> path, boolean complete,
        List<String> iterations) {

    public Evaluation {
        path = path == null ? null : List.copyOf(path);
        iterations = List.copyOf(iterations);
    }

    /** A construction the inliner has not reached, read from {@code origin}. */
    public static Evaluation of(Object origin) {
        return new Evaluation(origin, null, false, List.of());
    }

    /** A construction with no source node of its own (the compiler built it). */
    public static Evaluation synthesized() {
        return of(new Object());
    }

    /** Reached at {@code p} inside a lambda: completed where the lambda is used. */
    public Evaluation at(List<Integer> p) {
        return new Evaluation(origin, p, false, List.of());
    }

    /** Known whole: its path from the root, and the maps it is evaluated in. */
    public Evaluation complete(List<Integer> p, List<String> its) {
        return new Evaluation(origin, p, true, its);
    }

    /** The construction's site, once complete. */
    public Site site() {
        if (!complete || path == null) {
            throw new IllegalStateException("a construction's site is read before the inliner completed it");
        }
        return new Site(origin, path);
    }

    /** A construction's evaluation outside its maps: its origin (by identity) and its path from the query's root. */
    public record Site(Object origin, List<Integer> path) {
        @Override
        public boolean equals(Object o) {
            return o instanceof Site s && origin == s.origin && path.equals(s.path);
        }

        @Override
        public int hashCode() {
            return System.identityHashCode(origin) * 31 + path.hashCode();
        }
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Evaluation e && origin == e.origin && java.util.Objects.equals(path, e.path)
                && complete == e.complete && iterations.equals(e.iterations);
    }

    @Override
    public int hashCode() {
        return (System.identityHashCode(origin) * 31 + java.util.Objects.hashCode(path)) * 31
                + Boolean.hashCode(complete);
    }
}
