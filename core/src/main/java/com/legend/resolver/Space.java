package com.legend.resolver;

/**
 * A node's resolution space (remediation T3.1) &mdash; decided ONCE per node
 * (memoized in {@link Anchors}) from the checked types already on the tree,
 * consumed by {@code resolveNode}'s dispatch instead of per-guard subtree
 * re-scans.
 *
 * <ul>
 *   <li>{@link #OBJECT} &mdash; the node IS an object-space value: a class
 *       extent, a class-typed hop chain, or a space-transparent op
 *       (filter/limit/drop/slice/sortBy/class-map/first-like natives) over
 *       one. Resolves as (part of) a chain.</li>
 *   <li>{@link #ANCHORED} &mdash; not itself object-space, but an unresolved
 *       store anchor sits beneath: chain TERMINALS (project, groupBy,
 *       scalar reads &mdash; they exit object space) and relation-space
 *       wrappers above a class chain.</li>
 *   <li>{@link #INERT} &mdash; no anchor anywhere beneath: a pure relation
 *       query; resolution is the identity.</li>
 * </ul>
 */
enum Space {
    OBJECT, ANCHORED, INERT
}
