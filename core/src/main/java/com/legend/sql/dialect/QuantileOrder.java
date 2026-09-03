package com.legend.sql.dialect;

import com.legend.sql.SqlAgg;
import com.legend.sql.SqlExpr;
import com.legend.sql.SqlFn;
import com.legend.sql.SqlRewriter;
import com.legend.sql.SqlSelect;
import com.legend.sql.SqlType;
import java.util.List;

/**
 * DuckDB's quantile family takes NO within-group order, so the
 * percentile reducer's order key (the semantic {@code PERCENTILE_x(p)
 * WITHIN GROUP (ORDER BY v [DESC])}) is spelled here, as a named MIR
 * pass: an ASCENDING key drops ({@code quantile_cont(v, p)}); a
 * DESCENDING continuous percentile interpolates over the NEGATED values
 * and negates back (the same interpolation direction as the standard's
 * DESC form — engine golden 1.4 over [1,1.5,2]); a DESCENDING discrete
 * percentile is the ceil(p*N)-th element of the DESC-sorted collected
 * list (PERCENTILE_DISC DESC picks the first value whose cume_dist
 * >= p). A window-positioned percentile windows every reducer of its
 * encoding with the same window spec.
 */
final class QuantileOrder extends SqlRewriter {
    @Override
    protected SqlExpr expr(SqlExpr e) {
        if (e instanceof SqlAgg.Reducer r && ordered(r)) {
            return encode(r);
        }
        if (e instanceof SqlExpr.WindowCall w
                && w.fn() instanceof SqlAgg.Reducer r && ordered(r)) {
            return windowize(encode(r), w);
        }
        return e;
    }

    private static boolean ordered(SqlAgg.Reducer r) {
        return (r.fn() == SqlAgg.Fn.QUANTILE_CONT
                    || r.fn() == SqlAgg.Fn.QUANTILE_DISC)
                && r.orderBy().size() == 1 && r.args().size() == 2;
    }

    private static SqlExpr encode(SqlAgg.Reducer r) {
        SqlSelect.SortKey key = r.orderBy().get(0);
        SqlExpr value = r.args().get(0);
        SqlExpr p = r.args().get(1);
        if (key.ascending()) {
            return new SqlAgg.Reducer(r.fn(), r.args(), r.distinct(), List.of());
        }
        if (r.fn() == SqlAgg.Fn.QUANTILE_CONT) {
            return SqlExpr.Call.of(SqlFn.NEGATE,
                    new SqlAgg.Reducer(SqlAgg.Fn.QUANTILE_CONT,
                            List.of(SqlExpr.Call.of(SqlFn.NEGATE, value), p),
                            r.distinct(), List.of()));
        }
        return SqlExpr.Call.of(SqlFn.LIST_GET,
                SqlExpr.Call.of(SqlFn.LIST_SORT_DESC,
                        new SqlAgg.Reducer(SqlAgg.Fn.LIST, List.of(value),
                                r.distinct(), List.of())),
                new SqlExpr.Cast(
                        SqlExpr.Call.of(SqlFn.CEILING,
                                SqlExpr.Call.of(SqlFn.TIMES, p,
                                        new SqlAgg.Reducer(SqlAgg.Fn.COUNT,
                                                List.of(value), r.distinct(),
                                                List.of()))),
                        SqlType.Scalar.BIGINT));
    }

    /** Every reducer inside the encoding takes the window's spec (the
     * encoding's own shapes: reducers under calls and casts). */
    private static SqlExpr windowize(SqlExpr e, SqlExpr.WindowCall w) {
        return switch (e) {
            case SqlAgg.Reducer r -> new SqlExpr.WindowCall(r,
                    w.partitionBy(), w.orderBy(), w.frame());
            case SqlExpr.Call c -> new SqlExpr.Call(c.fn(), c.args().stream()
                    .map(x -> windowize(x, w)).toList());
            case SqlExpr.Cast c -> new SqlExpr.Cast(windowize(c.value(), w),
                    c.target(), c.conform());
            default -> e;
        };
    }
}
