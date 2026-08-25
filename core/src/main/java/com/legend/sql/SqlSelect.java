package com.legend.sql;

import com.legend.Nullable;

import java.util.List;

/**
 * THE query node (PHASE_HIJ_LOWERING.md): one record with every clause slot,
 * mirroring real legend's {@code SelectSQLQuery}. The fold policy extends a
 * single {@code SqlSelect} through a run of compatible relational ops via the
 * {@code with*} copiers; a fresh nesting level exists only as an explicit
 * {@link SqlSource.Subselect}. Empty {@link #projections} means {@code SELECT *}.
 */
public record SqlSelect(List<Projection> projections, boolean distinct,
                        SqlSource from,
                        @Nullable SqlExpr where, List<SqlExpr> groupBy,
                        @Nullable SqlExpr having, @Nullable SqlExpr qualify,
                        List<SortKey> orderBy, @Nullable Long limit,
                        @Nullable Long offset, List<OutputCol> outputs)
        implements SqlQuery {

    public SqlSelect {
        java.util.Objects.requireNonNull(from,
                "a FROM-less select spells SqlSource.Dual, never null");
        // THE LABEL FLIP (TYPED_SQL_IR.md, 2026-08-24): output labels
        // reconcile with the projections' STORED types at construction
        // — equal or ADMITTED keeps the pure-contract erasure; a label
        // lie ADOPTS the wire. Compact-ctor idiom: computed once,
        // idempotent, structurally unable to drift. (Conform-by-
        // emission lives at the stamp-guarded lowering seam, not here
        // — the referee's castErasure verdict, charter T4 leg 1.)
        outputs = SqlTyping.reconcileLabels(projections, outputs);
    }

    /** {@code SELECT * FROM source} with every other clause empty. */
    public static SqlSelect starOf(SqlSource from) {
        return new SqlSelect(List.of(), false, from, null, List.of(), null, null,
                List.of(), null, null, from.outputs());
    }

    /** SYNTHETIC scalar-map column-name marker (resolver
     * scalarMapAsProject): the engine spells a bare map scalar select
     * UNALIASED — the engine-TEXT channel drops such aliases entirely;
     * execution keeps them (downstream references use the row type). */
    public static final String SYNTH_MAP_COL = "u_map__";

    public record Projection(SqlExpr expr, @Nullable String alias) {

        /**
         * The projected OUTPUT name: the alias, else the bare column's own
         * name, else null (a computed expression with no alias has no
         * addressable name). THE one implementation of the rule (an audit
         * found it duplicated across Fold and the Lowerer).
         */
        public @Nullable String outputName() {
            return alias != null ? alias
                    : expr instanceof SqlExpr.Column c ? c.name() : null;
        }

    }

    /** One ORDER BY key; {@code nullOrder} null = dialect default.
     * {@code outputName} — the projected TDS column a COLUMN-NAME-keyed
     * sort addresses; engine text spells it ({@code order by "name"
     * asc}), execution dialects render {@code expr}. Null otherwise. */
    public record SortKey(SqlExpr expr, boolean ascending,
            @Nullable NullOrder nullOrder, @Nullable String outputName) {
        public enum NullOrder { NULLS_FIRST, NULLS_LAST }

        // NO short overload: a defaulted outputName silently de-addressed a
        // column-name-keyed sort at rebuild sites (remediation T2.2); every
        // construction names every field.

        public static SortKey asc(SqlExpr e) {
            return new SortKey(e, true, null, null);
        }

        /** Test-DSL convenience (no production callers; hand-built IR only). */
        public static SortKey desc(SqlExpr e) {
            return new SortKey(e, false, null, null);
        }
    }

    // ----- clause copiers: the fold policy's fingers -----

    public SqlSelect withFrom(SqlSource f) {
        return new SqlSelect(projections, distinct, f, where, groupBy, having,
                qualify, orderBy, limit, offset, outputs);
    }

    public SqlSelect withProjections(List<Projection> p, List<OutputCol> out) {
        return new SqlSelect(p, distinct, from, where, groupBy, having, qualify, orderBy, limit, offset, out);
    }

    public SqlSelect withDistinct() {
        return new SqlSelect(projections, true, from, where, groupBy, having, qualify, orderBy, limit, offset, outputs);
    }

    public SqlSelect withWhere(@Nullable SqlExpr w) {
        return new SqlSelect(projections, distinct, from, w, groupBy, having, qualify, orderBy, limit, offset, outputs);
    }

    public SqlSelect withGroupBy(List<SqlExpr> keys) {
        return new SqlSelect(projections, distinct, from, where, keys, having, qualify, orderBy, limit, offset, outputs);
    }

    public SqlSelect withHaving(@Nullable SqlExpr h) {
        return new SqlSelect(projections, distinct, from, where, groupBy, h, qualify, orderBy, limit, offset, outputs);
    }

    public SqlSelect withQualify(@Nullable SqlExpr q) {
        return new SqlSelect(projections, distinct, from, where, groupBy, having, q, orderBy, limit, offset, outputs);
    }

    public SqlSelect withOrderBy(List<SortKey> keys) {
        return new SqlSelect(projections, distinct, from, where, groupBy, having, qualify, keys, limit, offset, outputs);
    }

    public SqlSelect withLimit(@Nullable Long n) {
        return new SqlSelect(projections, distinct, from, where, groupBy, having, qualify, orderBy, n, offset, outputs);
    }

    public SqlSelect withOffset(@Nullable Long n) {
        return new SqlSelect(projections, distinct, from, where, groupBy, having, qualify, orderBy, limit, n, outputs);
    }
}
