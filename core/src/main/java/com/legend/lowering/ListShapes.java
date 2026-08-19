package com.legend.lowering;

import com.legend.compiler.spec.typed.TypedLambda;

import com.legend.compiler.element.type.Multiplicity;
import com.legend.compiler.spec.typed.TypedSpec;
import com.legend.sql.SqlAgg;
import com.legend.sql.SqlExpr;
import com.legend.sql.SqlFn;
import java.util.List;
import java.util.Set;

/**
 * List-position ENCODING policy (one owner): pure multiplicity stamps are
 * unreliable after substitution (many-stamped reads stay scalar; values
 * readers return lists from to-one-stamped subqueries), so list-position
 * consumers decide by the SQL VALUE SHAPE — and scalar encodings wrap as
 * singletons, with SQL NULL carrying pure's EMPTY ({@code []}, never
 * {@code [NULL]}).
 */
final class ListShapes {

    private ListShapes() {
    }

    /** SqlFns whose RESULT is a list value (never the LIST_AGG family —
     * list_aggregate reduces to a scalar). */
    static final Set<SqlFn> LIST_PRODUCERS = Set.of(
            SqlFn.LIST_CONCAT, SqlFn.LIST_TRANSFORM, SqlFn.LIST_SORT,
            SqlFn.LIST_FILTER, SqlFn.LIST_TAIL, SqlFn.LIST_INIT,
            SqlFn.LIST_SLICE, SqlFn.LIST_APPEND, SqlFn.LIST_FLATTEN,
            SqlFn.LIST_DISTINCT, SqlFn.LIST_REVERSE, SqlFn.LIST_ZIP,
            // map readers produce LISTS (values()->sort() wrapped a
            // nested list without them — Phase 4 channel B testValues)
            SqlFn.MAP_KEYS, SqlFn.MAP_VALUES,
            // the regexp sweep produces a LIST (chunk()->sort() wrapped
            // a nested list without it — same precedent)
            SqlFn.REGEXP_EXTRACT_ALL);

    /** A scalar subquery whose single projection is a LIST-building
     * aggregate (the values-collection reader) — its VALUE is a list. */
    static boolean listValuedSubquery(SqlExpr.ScalarSubquery sq) {
        return sq.subquery() instanceof com.legend.sql.SqlSelect ss
                && ss.projections().size() == 1
                && (ss.projections().get(0).expr()
                                instanceof SqlAgg.Reducer r
                            && r.fn() == SqlAgg.Fn.LIST
                        || ss.projections().get(0).expr()
                                instanceof SqlExpr.OrderedListAgg);
    }

    /** The value is DEFINITELY list-shaped at SQL level. */
    static boolean listShaped(SqlExpr e) {
        return e instanceof SqlExpr.ArrayLit || e instanceof SqlExpr.NullLit
                || (e instanceof SqlExpr.ScalarSubquery sq
                        && listValuedSubquery(sq))
                || (e instanceof SqlExpr.Cast ct
                        && ct.target() instanceof com.legend.sql.SqlType.Array)
                || (e instanceof SqlExpr.Call c
                        && LIST_PRODUCERS.contains(c.fn()));
    }

    /** LIST position: singleton-wrap a TO-ONE unless already a list (or
     * NULL = empty, which DuckDB list fns treat as []) — asList policy. */
    static SqlExpr listArg(TypedSpec pureArg, SqlExpr e) {
        return !isToOne(pureArg) || e instanceof SqlExpr.ArrayLit
                || e instanceof SqlExpr.NullLit
                ? e : new SqlExpr.ArrayLit(List.of(e));
    }

    /** A concatenate SIDE: scalar encodings (TO-ONE stamps, many-stamped
     * CASE optionals) wrap null-guarded — SQL NULL is pure's EMPTY, so
     * the side contributes [], never [NULL]. Lists pass. */
    static SqlExpr concatSide(TypedSpec pureArg, SqlExpr e) {
        if (e instanceof SqlExpr.ArrayLit || e instanceof SqlExpr.NullLit
                || !(isToOne(pureArg) || e instanceof SqlExpr.Case)) {
            return e;
        }
        return new SqlExpr.Case(
                List.of(new SqlExpr.Case.When(
                        SqlExpr.Call.of(SqlFn.IS_NULL, e),
                        new SqlExpr.ArrayLit(List.of()))),
                new SqlExpr.ArrayLit(List.of(e)));
    }

    private static boolean isToOne(TypedSpec arg) {
        return arg.info().multiplicity() instanceof Multiplicity.Bounded b
                && b.isToOne();
    }

    /** A value in LIST position: singleton-wrap unless it is already a
     * list (or NULL = empty). */
    static SqlExpr asList(SqlExpr e, boolean many) {
        return many || e instanceof SqlExpr.ArrayLit
                || e instanceof SqlExpr.NullLit
                ? e : new SqlExpr.ArrayLit(java.util.List.of(e));
    }
    /** An if-branch is a 0-param SINGLE-expression thunk; its body is the value. */
    static TypedSpec thunkBody(TypedSpec branch) {
        if (branch instanceof TypedLambda l) {
            if (l.body().size() != 1) {
                throw new IllegalStateException("if-branch thunk has "
                        + l.body().size() + " statements; a last-statement pick"
                        + " would silently drop the rest");
            }
            return l.body().get(0);
        }
        return branch;
    }

}
