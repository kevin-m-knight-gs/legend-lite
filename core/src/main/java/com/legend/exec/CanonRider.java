// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend.exec;

import com.legend.compiler.element.type.Type;

import java.util.ArrayList;
import java.util.List;

/**
 * V11 — the SINGLE-QUERY canon carrier (user-ratified 2026-08-22):
 * canonical renders ride the side query itself as appended VARCHAR
 * projections ({@code SELECT value, canon(value) ...}), the m2m
 * in-query JSON precedent — ONE execution produces both the values
 * (host referee, kind gates, declared policies) and the canon texts
 * (the byte verdict of record). The double-execution machinery
 * (prepCanon/runCanon) and its soundness obligation are DELETED.
 *
 * <p>Lifecycle: the K-arm creates one rider per assert side; the
 * driver ({@code StatementExecutor.executeTyped}) asks the canon owner
 * ({@code CanonicalRenderSql.wrapWithCanon}) to wrap the lowered plan;
 * the {@code Executor} harvests the canon columns row-aligned with the
 * value decode. A side the canon cannot ride declines with a named
 * reason — census fuel, never a silent rescue.
 *
 * <p>UNREFINED NUMBER stamps project ONE CANDIDATE COLUMN PER FINE
 * KIND (our OutputCol types are stamp-derived, so the plan cannot name
 * the member — the V6-round-2 circularity): the DB computes every
 * candidate render, and the verdict layer SELECTS by the runtime kind
 * it already derives from the fetched values for the equality gate —
 * selection, never evaluation.
 */
public final class CanonRider {

    /** The wrap outcome frame: candidate canon kinds in
     * projected-column order, and whether the side is a collection
     * (drives the renderSide list framing at the verdict layer). */
    public record Wrap(List<Type> kinds, boolean many) {
    }

    private final boolean canonicalOrder;
    private final List<String[]> rows = new ArrayList<>();
    private @com.legend.Nullable Wrap wrap;
    private @com.legend.Nullable String declined = "non-sql-arm";

    public CanonRider(boolean canonicalOrder) {
        this.canonicalOrder = canonicalOrder;
    }

    public boolean canonicalOrder() {
        return canonicalOrder;
    }

    /** Candidate canon kinds, in projected-column order (one per
     * appended VARCHAR column). Empty until the wrap succeeds. */
    public List<Type> kinds() {
        return wrap == null ? List.of() : wrap.kinds();
    }

    /** Harvested canon cells, one array per data row, aligned with the
     * value decode (the Executor's SCALAR/COLLECTION arms). */
    public List<String[]> rows() {
        return rows;
    }

    public @com.legend.Nullable String declined() {
        return declined;
    }

    public boolean wrapped() {
        return wrap != null;
    }

    /** Whether the side is a collection (drives the renderSide list
     * framing at the verdict layer). */
    public boolean many() {
        return wrap != null && wrap.many();
    }

    /** The canon owner records a successful wrap. */
    public void wrap(List<Type> candidateKinds, boolean isMany) {
        this.wrap = new Wrap(List.copyOf(candidateKinds), isMany);
        this.declined = null;
    }

    /** The canon owner (or a non-SQL driver arm) records a decline. */
    public void decline(String reason) {
        this.wrap = null;
        this.declined = reason;
    }
}
