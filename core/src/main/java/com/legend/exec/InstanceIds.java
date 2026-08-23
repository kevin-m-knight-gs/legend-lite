// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend.exec;

/**
 * F13 — SYNTHETIC INSTANCE IDENTITY as DATA: each keyless-class
 * construction SITE (a {@code TypedNewInstance}/{@code TypedCopyInstance}
 * node) mints ONE deterministic id, carried in the instance struct's
 * {@code __id} field. Identity is a property of the BINDING SITE, never
 * of an evaluation ({@code uuid()} per evaluation would make a let-bound
 * instance unequal to itself); the inliner's verbatim let splicing and
 * "untouched subtrees keep identity" contract make node identity the
 * site key — the same let-bound constructor reaches both assert sides
 * as the SAME node, two textually distinct constructors are two nodes.
 *
 * <p>Scoped to ONE {@link com.legend.StatementExecutor.ExecEnv} (both
 * sides of every verdict in a test share the env, so ids agree across
 * side lowerings by construction). Single-threaded like the env itself.
 */
public final class InstanceIds {

    private final java.util.IdentityHashMap<Object, String> ids =
            new java.util.IdentityHashMap<>();

    /** The site id of {@code node}, minted on first sight — the map's
     * pre-insert size numbers the sites (entries are never removed, so
     * ids stay unique and stable; no mutable counter field). */
    public String idOf(Object node) {
        return ids.computeIfAbsent(node, k -> "i" + (ids.size() + 1));
    }
}
