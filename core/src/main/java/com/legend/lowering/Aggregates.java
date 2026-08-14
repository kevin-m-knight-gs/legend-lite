package com.legend.lowering;

import com.legend.builtin.Pure;
import com.legend.sql.SqlAgg;
import com.legend.sql.SqlExpr;
import com.legend.sql.SqlFn;
import com.legend.sql.SqlType;
import com.legend.compiler.element.TypedFunction;
import java.util.HashMap;
import java.util.Map;
/**
 * Aggregate-reducer dispatch: the RESOLVED overload of an agg-col's reduce
 * lambda ({@code y|$y->sum()}) &rarr; the SQL reducer name. Identity-keyed like
 * {@link Scalars}; catalog-driven registration; unregistered = loud error.
 */
public final class Aggregates {

    private static final Map<String, SqlAgg.Fn> REDUCERS = new HashMap<>();

    private Aggregates() {
    }

    private static void family(SqlAgg.Fn sqlName, String pureName) {
        for (String f : Pure.nativeKeysAt(pureName)) {
            REDUCERS.put(f, sqlName);
        }
    }

    static {
        family(SqlAgg.Fn.SUM, "sum");
        // Pure spells numeric reduction via plus: y|$y->plus() == sum.
        family(SqlAgg.Fn.SUM, "plus");
        family(SqlAgg.Fn.COUNT, "count");
        family(SqlAgg.Fn.AVG, "average");
        // the mapping ~groupBy DSL's 'avg' (normalizer emits it verbatim
        // against the meta::legend::lite::avg native) — the catalog HALF
        // was missing: typechecked, then died at lowering (T1.8)
        family(SqlAgg.Fn.AVG, "avg");
        family(SqlAgg.Fn.MIN, "min");
        family(SqlAgg.Fn.MAX, "max");
        // H2-LENIENT per-group witness (view ~groupBy per-row columns —
        // the engine's H2 1.x golden spells the BARE column; our DB-side
        // form is ANY_VALUE): REAL pure first() — order-sensitive
        // first()-over-relation consumers keep their limit-1 route by
        // excluding ANY_VALUE at THEIR arms, never a synthetic native
        family(SqlAgg.Fn.ANY_VALUE, "first");
        family(SqlAgg.Fn.STDDEV_SAMP, "stdDevSample");
        family(SqlAgg.Fn.STDDEV_SAMP, "stdDev");
        family(SqlAgg.Fn.COUNT, "size");
        // joinStrings carries its separator as an EXTRA reduce-call argument
        // (handled in the lowering's aggExpr).
        family(SqlAgg.Fn.STRING_AGG, "joinStrings");
        family(SqlAgg.Fn.STDDEV_POP, "stdDevPopulation");
        family(SqlAgg.Fn.VAR_SAMP, "varianceSample");
        family(SqlAgg.Fn.VAR_POP, "variancePopulation");
        // Pure's bare variance is the SAMPLE variance (PCT semantics).
        family(SqlAgg.Fn.VAR_SAMP, "variance");
        family(SqlAgg.Fn.MEDIAN, "median");
        family(SqlAgg.Fn.AVG, "mean");
        family(SqlAgg.Fn.MODE, "mode");
        // Boolean reductions: y|$y->and() / ->or() over a group — DuckDB
        // BOOL_AND/BOOL_OR (engine simpleGroupByAnd/Or goldens). The
        // 1-arg COLLECTION overloads only: the 2-arg logical and(a,b)
        // must never register as a reducer.
        for (String f : Pure.nativeKeysAt("and", 1)) {
            REDUCERS.put(f, SqlAgg.Fn.BOOL_AND);
        }
        for (String f : Pure.nativeKeysAt("or", 1)) {
            REDUCERS.put(f, SqlAgg.Fn.BOOL_OR);
        }
        // percentile: DuckDB QUANTILE family; the 4-arg overload's
        // ascending/continuous flags are folded in the lowering (aggExpr).
        family(SqlAgg.Fn.QUANTILE_CONT, "percentile");
        // BI-VARIATE reducers — the map body is rowMapper(a, b); aggExpr
        // decomposes it into the two SQL arguments.
        family(SqlAgg.Fn.CORR, "corr");
        family(SqlAgg.Fn.COVAR_SAMP, "covarSample");
        family(SqlAgg.Fn.COVAR_POP, "covarPopulation");
        family(SqlAgg.Fn.ARG_MAX, "maxBy");
        family(SqlAgg.Fn.ARG_MIN, "minBy");
        // wavg has NO single SQL reducer: SUM(v*w)/SUM(w), composed in
        // aggExpr — the marker name never reaches the renderer.
        family(SqlAgg.Fn.WAVG, "wavg");
        // hashCode of a GROUP is HASH(LIST(values)) — composed in aggValue.
        family(SqlAgg.Fn.HASH_LIST, "hashCode");
        // isDistinct of a GROUP is COUNT(DISTINCT x) = COUNT(x) — composed
        // in aggValue (engine testGroupByIsDistinct golden). EXACT overload
        // only (audit 22a M5): the legacy 2-arg isDistinct(l,r) must never
        // reach the marker — its args would be dropped and the group SQL
        // rendered for a constantly-true pure expression.
        for (String f : Pure.nativeKeysAt("isDistinct", 1)) {
            REDUCERS.put(f, SqlAgg.Fn.IS_DISTINCT_MARK);
        }
        // the unique group value or NULL (collectionExtension.pure
        // semantics over a group): composed CASE, no single SQL reducer
        for (String f : Pure.nativeKeysAt("uniqueValueOnly", 1)) {
            REDUCERS.put(f, SqlAgg.Fn.UNIQUE_VALUE_ONLY);
        }
        for (String f : Pure.nativeKeysAt("uniqueValueOnly", 2)) {
            REDUCERS.put(f, SqlAgg.Fn.UNIQUE_VALUE_ONLY);
        }
    }

    /**
     * SQL reducer for the resolved reduce overload; loud error when
     * unregistered. Takes the TYPED callee — the parser node never crosses
     * into the lowering (AUDIT_2026_07 §1c; also retires the redundant
     * second parameter, audit L7).
     */
    /** Nullable variant of {@link #reducerFor} — for is-this-a-reducer probes. */
    static com.legend.sql.SqlAgg.@com.legend.Nullable Fn reducerOrNull(TypedFunction callee) {
        return REDUCERS.get(callee.signatureKey());
    }

    /** The ONE aggregate-membership test (remediation T1.7): resolver
     * walls ask the reducer catalog, never a parallel name list — a
     * catalog addition is a wall addition by construction. */
    public static boolean isReducer(TypedFunction callee) {
        return REDUCERS.containsKey(callee.signatureKey());
    }

    /** DEMAND-scan membership: reducers that make an expression an
     * AGGREGATE-over-navigation. ANY_VALUE (pure first()) is a
     * reduce-lambda-ONLY entry — first() over a projected nav collection
     * keeps its join-row route (engine ParentVarReferenceWithProject
     * golden: plain LEFT JOINs, no grouped subselect). */
    public static boolean isDemandReducer(TypedFunction callee) {
        return isReducer(callee)
                && REDUCERS.get(callee.signatureKey()) != SqlAgg.Fn.ANY_VALUE;
    }

    static boolean isReducerKey(String signatureKey) {
        return REDUCERS.containsKey(signatureKey);
    }

    static com.legend.sql.SqlAgg.Fn reducerFor(TypedFunction callee) {
        com.legend.sql.SqlAgg.Fn name = REDUCERS.get(callee.signatureKey());
        if (name == null) {
            throw new IllegalStateException(
                    "no aggregate lowering registered for resolved overload '"
                            + callee.qualifiedName() + "'");
        }
        return name;
    }

    /** Descending DISCRETE percentile: ceil(p*N)-th element of the
     * DESC-sorted values (PERCENTILE_DISC ORDER BY DESC semantics), cast
     * to DOUBLE — pure percentile renders as float (engine golden 12.0). */
    static SqlExpr qdiscDesc(SqlExpr value, SqlExpr p) {
        return new SqlExpr.Cast(SqlExpr.Call.of(SqlFn.LIST_GET,
                SqlExpr.Call.of(SqlFn.LIST_SORT_DESC,
                        new SqlAgg.Reducer(SqlAgg.Fn.LIST, java.util.List.of(value), false, java.util.List.of())),
                new SqlExpr.Cast(
                        SqlExpr.Call.of(SqlFn.CEILING,
                                SqlExpr.Call.of(SqlFn.TIMES, p,
                                        new SqlAgg.Reducer(SqlAgg.Fn.COUNT,
                                                java.util.List.of(value), false, java.util.List.of()))),
                        SqlType.Scalar.BIGINT)),
                SqlType.Scalar.DOUBLE);
    }
}
