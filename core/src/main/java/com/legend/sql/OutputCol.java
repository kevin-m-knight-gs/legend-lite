package com.legend.sql;

/**
 * One output column of a query node, in the SQL layer's own type vocabulary
 * (LEGEND_SQL_VISION.md). Frontends stamp these at the lowering boundary;
 * result layers that need frontend types (Pure) read them from the FRONTEND's
 * typed root, never from the plan.
 */
public record OutputCol(String name, SqlType type, boolean nullable,
        boolean tolerated, Origin origin) {

    /** WHERE the column NAME was born — the fact a case-sensitive
     * renderer needs (convergence batch C blocker; SQL-IR
     * backend-agnosticism slice 1): a PHYSICAL name exists in DDL and
     * spells bare-unless-special (folds with the DDL's casing); a
     * DERIVED name is invented by the query (projection label, VALUES
     * column) and quotes unconditionally at definition AND reference —
     * the engine's own convention (as "root", as "legalName").
     * Stamped at construction, never re-derived at consumption. */
    public enum Origin { PHYSICAL, DERIVED }

    /** Derived-frame convenience — PHYSICAL outputs are born ONLY at
     * the store boundary ({@code Lowerer.outputsOf}), which uses the
     * canonical constructor explicitly. */
    public OutputCol(String name, SqlType type, boolean nullable,
            boolean tolerated) {
        this(name, type, nullable, tolerated, Origin.DERIVED);
    }

    public OutputCol(String name, SqlType type, boolean nullable) {
        this(name, type, nullable, false, Origin.DERIVED);
    }

    /** {@code tolerated} — the slot carries an ENGINE-COMPAT
     * carry-through value (charter §4bZ): its declared label and its
     * wire deliberately disagree because the read crossed a declared
     * property/column kind mismatch at the mapping seam. Stamped by
     * label reconciliation from the value's provenance tag
     * ({@link TypeFact.Typed#tolerated()}); read by the wire census
     * (the label/meta divergence is registered, not red). */
}
