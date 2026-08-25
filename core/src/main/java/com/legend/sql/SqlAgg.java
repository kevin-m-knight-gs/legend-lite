package com.legend.sql;

import java.util.List;

/**
 * Aggregate-position functions, POSITION-TYPED (the current-branch idea worth
 * keeping): {@link Reducer}s are valid both under GROUP BY and inside
 * {@code OVER(...)}; {@link RankingFn}/{@link ValueFn} are window-only.
 * {@link Reducer} implements {@link SqlExpr} so it can sit in a grouped
 * select's projections/HAVING; the window-only kinds deliberately do NOT
 * &mdash; {@code LAG} in a GROUP BY position is a javac error, not a runtime one.
 */
public sealed interface SqlAgg {

    /** THE aggregate-function vocabulary (BACKEND_PORTABILITY.md §5.2 /
     * CARRIER_REDESIGN.md P1): typed and exhaustive like {@link SqlFn} —
     * the raw-String channel rendered names verbatim on every dialect,
     * an undeclared passthrough. Dialects SPELL these (base: the enum
     * name); WAVG/HASH_LIST are Lowerer-internal pseudo-reducers
     * expanded before rendering. */
    enum Fn {
        SUM, COUNT, AVG, MIN, MAX, ANY_VALUE, STDDEV_SAMP, STDDEV_POP,
        VAR_SAMP, VAR_POP, MEDIAN, MODE, STRING_AGG, LIST, QUANTILE_CONT,
        QUANTILE_DISC, CORR, COVAR_SAMP, COVAR_POP, ARG_MAX, ARG_MIN,
        WAVG, HASH_LIST, BOOL_AND, BOOL_OR, IS_DISTINCT_MARK,
        UNIQUE_VALUE_ONLY, QDISC_DESC, VARIANCE, STDDEV,
        ROW_NUMBER, RANK, DENSE_RANK, PERCENT_RANK, CUME_DIST, NTILE,
        LAG, LEAD, FIRST_VALUE, LAST_VALUE, NTH_VALUE;

        /** Lowerer-internal pseudo-reducers (composed before rendering —
         * reaching a renderer is a caller bug). */
        public boolean marker() {
            return this == WAVG || this == HASH_LIST
                    || this == IS_DISTINCT_MARK || this == UNIQUE_VALUE_ONLY
                    || this == QDISC_DESC;
        }
    }

    Fn fn();

    List<SqlExpr> args();

    /** GROUP-BY-valid aggregate (also usable inside a window): SUM, COUNT, MIN, ... */
    record Reducer(Fn fn, List<SqlExpr> args, boolean distinct,
            List<SqlSelect.SortKey> orderBy,
            TypeFact type) implements SqlAgg, SqlExpr {

        // NO short overload of the SEMANTIC fields: a defaulted orderBy
        // silently dropped an ordered aggregate's ORDER BY at rebuild
        // sites (remediation T2.2); every construction names every field.
        // (The type component is canonical-computed, never a caller's.)

        public Reducer {
            type = SqlTyping.reducerType(fn, args.isEmpty()
                    ? SqlTyping.UNKNOWN : args.get(0).type());
        }

        public Reducer(Fn fn, List<SqlExpr> args, boolean distinct,
                List<SqlSelect.SortKey> orderBy) {
            this(fn, args, distinct, orderBy, SqlTyping.UNKNOWN);
        }

        public static Reducer of(Fn fn, SqlExpr... args) {
            return new Reducer(fn, List.of(args), false, List.of());
        }

        /** Same aggregate over transformed arguments (rebuild sites). */
        public Reducer withArgs(List<SqlExpr> newArgs) {
            return new Reducer(fn, newArgs, distinct, orderBy);
        }
    }

    /** Window-only ranking function: ROW_NUMBER, RANK, NTILE, CUME_DIST, ... */
    record RankingFn(Fn fn, List<SqlExpr> args) implements SqlAgg {
    }

    /** Window-only value function: LAG, LEAD, FIRST_VALUE, LAST_VALUE, NTH_VALUE. */
    record ValueFn(Fn fn, List<SqlExpr> args) implements SqlAgg {
    }
}
