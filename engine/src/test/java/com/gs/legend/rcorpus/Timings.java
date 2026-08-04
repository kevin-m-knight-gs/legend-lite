package com.gs.legend.rcorpus;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Wall-clock phase accounting for the corpus sweep.
 *
 * <p><b>Why this exists.</b> Every performance number in this project that was
 * <em>derived</em> (from a CPU sample share, or from a micro-benchmark of an
 * unrepresentative input) turned out wrong, some by 4&times;. Every number
 * measured directly with {@link System#nanoTime()} around a real phase of a
 * real run held up. This class makes the second kind cheap and permanent so
 * the add-measure-revert cycle stops.
 *
 * <p><b>Cost when off: none.</b> {@link #ON} is a {@code static final boolean}
 * read from a system property at class init, so the JIT constant-folds every
 * guarded branch away, and {@link #phase} returns a shared no-op that allocates
 * nothing. Cost when on: one {@code nanoTime} pair per probe (~20-25ns);
 * ~40k probes over a 250s sweep is around a millisecond.
 *
 * <p><b>Enable:</b> {@code -Dlegend.timings} (optionally
 * {@code -Dlegend.timings.out=/path}, default {@code /tmp/timings.txt}).
 *
 * <p><b>The load-bearing feature is UNACCOUNTED time.</b> Every node reports
 * {@code self - sum(children)}. A phase tree that quietly covers 40% of the
 * wall clock looks identical to one that covers 100% unless you print the
 * remainder — and that omission is exactly how several wrong conclusions
 * survived. {@code OVER} marks a node whose children exceed it, which means
 * double counting (a nested probe being counted twice).
 *
 * <p>Accumulation is per-thread-safe and path-keyed, so it survives the sweep
 * becoming parallel without a redesign.
 */
public final class Timings {

    /**
     * Constant-folded when absent: the off path costs literally nothing.
     *
     * <p>Presence-based on purpose. {@code Boolean.getBoolean} would require
     * the literal value {@code "true"}, so the natural {@code -Dlegend.timings}
     * would silently do nothing — which it did, on the first run of this class.
     * Any value enables; {@code -Dlegend.timings=false} disables.
     */
    private static final boolean ON =
            System.getProperty("legend.timings") != null
            && !"false".equalsIgnoreCase(System.getProperty("legend.timings"));

    private static final Map<String, AtomicLong> NANOS = new ConcurrentHashMap<>();
    private static final Map<String, AtomicLong> COUNTS = new ConcurrentHashMap<>();

    private static final ThreadLocal<Deque<String>> STACK =
            ThreadLocal.withInitial(ArrayDeque::new);

    /** Allocation-free sentinel used whenever timing is off. */
    private static final Scope NOOP = new Scope(null, 0L);

    private Timings() {
    }

    public static boolean enabled() {
        return ON;
    }

    /**
     * Open a phase. Nest with try-with-resources; the path is the
     * slash-joined stack, so the same label under two parents stays distinct.
     */
    public static Scope phase(String name) {
        if (!ON) {
            return NOOP;
        }
        Deque<String> st = STACK.get();
        String parent = st.peek();
        String path = parent == null ? name : parent + "/" + name;
        st.push(path);
        return new Scope(path, System.nanoTime());
    }

    /** A phase's elapsed wall time, accumulated across every entry. */
    public static final class Scope implements AutoCloseable {
        private final String path;
        private final long start;

        private Scope(String path, long start) {
            this.path = path;
            this.start = start;
        }

        @Override
        public void close() {
            if (path == null) {
                return;
            }
            long d = System.nanoTime() - start;
            NANOS.computeIfAbsent(path, k -> new AtomicLong()).addAndGet(d);
            COUNTS.computeIfAbsent(path, k -> new AtomicLong()).incrementAndGet();
            STACK.get().pop();
        }
    }

    /** Render the tree. {@code wallNanos} is the true outer wall clock. */
    public static String report(long wallNanos) {
        if (!ON) {
            return "(timings off; enable with -Dlegend.timings)";
        }
        List<String> paths = new ArrayList<>(NANOS.keySet());
        paths.sort(Comparator.naturalOrder());
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("WALL %.1fs%n", wallNanos / 1e9));
        double wall = wallNanos / 1e9;

        for (String p : paths) {
            int depth = (int) p.chars().filter(c -> c == '/').count();
            double self = NANOS.get(p).get() / 1e9;
            long n = COUNTS.get(p).get();

            double kids = 0;
            for (String q : paths) {
                // direct children only
                if (q.startsWith(p + "/")
                        && q.indexOf('/', p.length() + 1) < 0) {
                    kids += NANOS.get(q).get() / 1e9;
                }
            }
            String label = p.substring(p.lastIndexOf('/') + 1);
            sb.append(String.format("%s%-22s %8.1fs  %5.1f%% wall  n=%-7d",
                    "  ".repeat(depth + 1), label, self, 100 * self / wall, n));
            if (kids > 0) {
                double un = self - kids;
                sb.append(String.format("  unaccounted %6.1fs (%.0f%%)%s",
                        un, 100 * un / self, un < -0.05 ? "  <== OVER: double counted" : ""));
            }
            sb.append('\n');
        }

        double top = 0;
        for (String p : paths) {
            if (p.indexOf('/') < 0) {
                top += NANOS.get(p).get() / 1e9;
            }
        }
        sb.append(String.format("%nTOP-LEVEL COVERED %.1fs of %.1fs wall (%.0f%%) "
                + "-- UNACCOUNTED %.1fs%n", top, wall, 100 * top / wall, wall - top));
        return sb.toString();
    }

    /** Write {@link #report} to {@code -Dlegend.timings.out} (default /tmp/timings.txt). */
    public static void dump(long wallNanos) {
        if (!ON) {
            return;
        }
        String out = System.getProperty("legend.timings.out", "/tmp/timings.txt");
        try {
            java.nio.file.Files.writeString(java.nio.file.Path.of(out), report(wallNanos));
        } catch (Exception e) {
            System.err.println("[timings] could not write " + out + ": " + e.getMessage());
        }
    }
}
