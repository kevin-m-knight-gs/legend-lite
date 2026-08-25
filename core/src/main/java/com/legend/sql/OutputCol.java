package com.legend.sql;

/**
 * One output column of a query node, in the SQL layer's own type vocabulary
 * (LEGEND_SQL_VISION.md). Frontends stamp these at the lowering boundary;
 * result layers that need frontend types (Pure) read them from the FRONTEND's
 * typed root, never from the plan.
 */
public record OutputCol(String name, SqlType type, boolean nullable,
        boolean tolerated) {

    public OutputCol(String name, SqlType type, boolean nullable) {
        this(name, type, nullable, false);
    }

    /** {@code tolerated} — the slot carries an ENGINE-COMPAT
     * carry-through value (charter §4bZ): its declared label and its
     * wire deliberately disagree because the read crossed a declared
     * property/column kind mismatch at the mapping seam. Stamped by
     * label reconciliation from the value's provenance tag
     * ({@link TypeFact.Typed#tolerated()}); read by the wire census
     * (the label/meta divergence is registered, not red). */
}
