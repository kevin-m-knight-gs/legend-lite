package com.legend.lowering;

import com.legend.compiler.element.type.Type;
import com.legend.sql.OutputCol;
import com.legend.sql.SqlExpr;
import com.legend.sql.SqlFn;
import com.legend.sql.SqlSelect;
import com.legend.sql.SqlSource;
import java.util.List;
/**
 * THE fold authority (PHASE_HIJ_LOWERING.md): the single owner of the
 * fold-vs-isolate decision. SQL's SELECT evaluates its slots in one fixed
 * order &mdash; {@code FROM → WHERE → GROUP BY → HAVING → window → QUALIFY →
 * SELECT list → DISTINCT → ORDER BY → LIMIT/OFFSET} &mdash; so whether a
 * pipeline op can extend the current select is a property of that order (does
 * the op commute past every already-occupied later slot?), not of the op.
 * Master plangen re-derived this per operator and drifted; this class is the
 * one place the knowledge lives.
 *
 * <p>Each method answers for one op kind. {@code true} = extend the current
 * select; {@code false} = the caller must isolate it as a subselect first.
 */
final class Fold {

    /** The plan-template placeholder KIND for a field type (h2New spells
     * date placeholders with the type keyword). */
    static com.legend.sql.SqlExpr.PlanParam.Kind planKindOf(
            com.legend.compiler.element.type.Type t) {
        if (t == com.legend.compiler.element.type.Type.Primitive.STRING) {
            return com.legend.sql.SqlExpr.PlanParam.Kind.STRING;
        }
        if (t == com.legend.compiler.element.type.Type.Primitive.DATE
                || t == com.legend.compiler.element.type.Type.Primitive
                        .STRICT_DATE) {
            return com.legend.sql.SqlExpr.PlanParam.Kind.DATE;
        }
        if (t == com.legend.compiler.element.type.Type.Primitive
                .DATE_TIME) {
            return com.legend.sql.SqlExpr.PlanParam.Kind.DATETIME;
        }
        if (t == com.legend.compiler.element.type.Type.Primitive.FLOAT) {
            return com.legend.sql.SqlExpr.PlanParam.Kind.FLOAT;
        }
        if (t == com.legend.compiler.element.type.Type.Primitive.BOOLEAN) {
            // spells like OTHER on H2; DB2-family dialects QUOTE boolean
            // placeholders (the case-expr compares 'true'/'false' strings)
            return com.legend.sql.SqlExpr.PlanParam.Kind.BOOLEAN;
        }
        if (t instanceof com.legend.compiler.element.type.Type.EnumType) {
            // enum values travel as NAME strings — the engine spells the
            // placeholder quoted ('\${yesOrNo}' = 'NO')
            return com.legend.sql.SqlExpr.PlanParam.Kind.ENUM;
        }
        return com.legend.sql.SqlExpr.PlanParam.Kind.OTHER;
    }

    /** An ORDER-SENSITIVE aggregate over a UNION-ALL-backed source with
     * no explicit key carries pure's CONCATENATE order obligation — the
     * union's branches are an ORDERED list. Stamp a branch ordinal into
     * the union and order the aggregate by it
     * ({@code string_agg(x, sep ORDER BY u_ord)}). H2 satisfies the
     * obligation by insertion order; a parallel backend must carry it
     * explicitly. Null when not applicable. */
    record OrderedAgg(com.legend.sql.SqlSelect base,
            com.legend.sql.SqlAgg.Reducer reducer) {
    }

    static OrderedAgg orderUnionAggregate(com.legend.sql.SqlSelect base,
            com.legend.sql.SqlAgg.Reducer red) {
        if (!"STRING_AGG".equals(red.fn()) || !red.orderBy().isEmpty()) {
            return null;
        }
        com.legend.sql.SqlSource.Subselect sub = findUnionSub(base.from());
        if (sub == null || !readsAlias(red.args(), sub.alias())) {
            return null;
        }
        var u = (com.legend.sql.SqlUnion) sub.inner();
        java.util.List<com.legend.sql.SqlQuery> bs = new java.util.ArrayList<>();
        int i = 0;
        for (com.legend.sql.SqlQuery b : u.branches()) {
            if (!(b instanceof com.legend.sql.SqlSelect s)
                    || s.projections().stream().anyMatch(p ->
                            "u_ord".equals(p.outputName()))) {
                return null;
            }
            var ps = new java.util.ArrayList<>(s.projections());
            if (ps.isEmpty()) {
                // a star branch keeps its star AND gains the ordinal
                ps.add(new com.legend.sql.SqlSelect.Projection(
                        new com.legend.sql.SqlExpr.Star(null), null));
            }
            ps.add(new com.legend.sql.SqlSelect.Projection(
                    new com.legend.sql.SqlExpr.IntLit(i++), "u_ord"));
            bs.add(s.withProjections(ps, widen(s.outputs())));
        }
        var nu = new com.legend.sql.SqlUnion(bs, true, widen(u.outputs()));
        var repl = new com.legend.sql.SqlSource.Subselect(nu, sub.alias(),
                sub.frameName());
        var red2 = new com.legend.sql.SqlAgg.Reducer(red.fn(), red.args(),
                red.distinct(), java.util.List.of(
                        new com.legend.sql.SqlSelect.SortKey(
                                new com.legend.sql.SqlExpr.Column(
                                        sub.alias(), "u_ord"), true, null, null)));
        return new OrderedAgg(
                base.withFrom(replaceSub(base.from(), sub, repl)), red2);
    }

    private static java.util.List<com.legend.sql.OutputCol> widen(
            java.util.List<com.legend.sql.OutputCol> outs) {
        if (outs == null || outs.isEmpty()) {
            return outs;
        }
        var w = new java.util.ArrayList<>(outs);
        w.add(new com.legend.sql.OutputCol("u_ord",
                com.legend.sql.SqlType.Scalar.INTEGER, false));
        return w;
    }

    /** The aggregate's VALUE must actually read the union it is ordered
     * by — anchor on the alias, never "the first union found". */
    private static boolean readsAlias(
            java.util.List<com.legend.sql.SqlExpr> args, String alias) {
        var found = new boolean[1];
        for (com.legend.sql.SqlExpr a : args) {
            walkColumns(a, c -> {
                if (alias.equals(c.table())) {
                    found[0] = true;
                }
            });
        }
        return found[0];
    }

    private static void walkColumns(com.legend.sql.SqlExpr e,
            java.util.function.Consumer<com.legend.sql.SqlExpr.Column> f) {
        switch (e) {
            case com.legend.sql.SqlExpr.Column c -> f.accept(c);
            case com.legend.sql.SqlExpr.Call c ->
                    c.args().forEach(x -> walkColumns(x, f));
            case com.legend.sql.SqlExpr.Cast c -> walkColumns(c.value(), f);
            case com.legend.sql.SqlExpr.Group g -> walkColumns(g.inner(), f);
            case com.legend.sql.SqlExpr.Case cs -> {
                cs.whens().forEach(w -> {
                    walkColumns(w.condition(), f);
                    walkColumns(w.then(), f);
                });
                if (cs.otherwise() != null) {
                    walkColumns(cs.otherwise(), f);
                }
            }
            default -> { }
        }
    }

    private static com.legend.sql.SqlSource.Subselect findUnionSub(
            com.legend.sql.SqlSource src) {
        return switch (src) {
            case com.legend.sql.SqlSource.Subselect s
                    when s.inner() instanceof com.legend.sql.SqlUnion u
                            && u.all() -> s;
            case com.legend.sql.SqlSource.Join j -> {
                var l = findUnionSub(j.left());
                yield l != null ? l : findUnionSub(j.right());
            }
            case null, default -> null;
        };
    }

    private static com.legend.sql.SqlSource replaceSub(
            com.legend.sql.SqlSource src,
            com.legend.sql.SqlSource.Subselect target,
            com.legend.sql.SqlSource.Subselect repl) {
        if (src == target) {
            return repl;
        }
        return src instanceof com.legend.sql.SqlSource.Join j
                ? new com.legend.sql.SqlSource.Join(
                        replaceSub(j.left(), target, repl),
                        replaceSub(j.right(), target, repl),
                        j.kind(), j.on())
                : src;
    }

    /** Whether the source reads (possibly through joins) a UNION
     * subselect — the count-of-rows aggregate spelling seam. */
    static boolean unionBacked(com.legend.sql.SqlSource src) {
        return switch (src) {
            case com.legend.sql.SqlSource.Subselect s ->
                    s.inner() instanceof com.legend.sql.SqlUnion;
            case com.legend.sql.SqlSource.Join j ->
                    unionBacked(j.left()) || unionBacked(j.right());
            case null, default -> false;
        };
    }

    /** Combine conjuncts into one AND, FLATTENING same-operator nesting
     * and Group-of-AND operands (the engine's andFilters: same-operator
     * chains never keep their group — a guard group survives only
     * standalone or under or/not). */
    static com.legend.sql.SqlExpr mergeAnd(com.legend.sql.SqlExpr... parts) {
        java.util.List<com.legend.sql.SqlExpr> flat =
                new java.util.ArrayList<>();
        for (com.legend.sql.SqlExpr p2 : parts) {
            flattenInto(p2, flat);
        }
        return flat.size() == 1 ? flat.get(0)
                : new com.legend.sql.SqlExpr.Call(
                        com.legend.sql.SqlFn.AND, flat);
    }

    private static void flattenInto(com.legend.sql.SqlExpr e,
            java.util.List<com.legend.sql.SqlExpr> out) {
        if (e instanceof com.legend.sql.SqlExpr.Group g
                && g.inner() instanceof com.legend.sql.SqlExpr.Call c
                && c.fn() == com.legend.sql.SqlFn.AND) {
            c.args().forEach(x -> flattenInto(x, out));
        } else if (e instanceof com.legend.sql.SqlExpr.Call c2
                && c2.fn() == com.legend.sql.SqlFn.AND) {
            c2.args().forEach(x -> flattenInto(x, out));
        } else {
            out.add(e);
        }
    }

    private Fold() {
    }

    /**
     * A filter always has a slot in an UNTRUNCATED select: WHERE normally,
     * HAVING over GROUP BY, QUALIFY over window columns (filtering commutes
     * with sorting, so ORDER BY does not force isolation &mdash; but LIMIT/OFFSET
     * truncate, and filtering does not commute with truncation). DISTINCT is
     * also a boundary: filtering after dedup can only be expressed after the
     * dedup happened.
     */
    static FilterSlot filterSlot(SqlSelect s, boolean referencesWindowColumn) {
        if (s.limit() != null || s.offset() != null || s.distinct()) {
            return FilterSlot.ISOLATE;
        }
        if (referencesWindowColumn) {
            return FilterSlot.QUALIFY;
        }
        if (!s.groupBy().isEmpty()) {
            return FilterSlot.HAVING;
        }
        return FilterSlot.WHERE;
    }

    enum FilterSlot { WHERE, HAVING, QUALIFY, ISOLATE }

    /**
     * Column selection/rename/projection narrows or re-labels the SELECT list.
     * It folds unless the select already DEDUPed (narrowing after DISTINCT
     * changes semantics) or truncated (projection commutes with LIMIT, but a
     * narrowed list may drop a column ORDER BY needs only under DISTINCT
     * &mdash; plain ORDER BY may reference source columns in the same select).
     */
    static boolean projectionFolds(SqlSelect s) {
        return !s.distinct() && s.limit() == null && s.offset() == null;
    }

    /**
     * Narrowing WITH dedup ({@code distinct(~cols)}): DISTINCT requires every
     * ORDER BY key to survive into the projected set (SQL rejects ordering a
     * deduped result by a dropped column). Full-row dedup trivially satisfies
     * this — which is why {@code sort→distinct()} stays flat: whole-row dedup
     * commutes with reordering.
     */
    static boolean distinctNarrowFolds(SqlSelect s, List<String> keptColumns) {
        for (SqlSelect.SortKey k : s.orderBy()) {
            if (!(k.expr() instanceof SqlExpr.Column c) || !keptColumns.contains(c.name())) {
                return false;
            }
        }
        return true;
    }

    /**
     * GROUP BY replaces the select's row space; it folds only onto a select
     * with nothing group-sensitive accumulated (no prior grouping/dedup/
     * truncation/order — grouping does not preserve order). QUALIFY and
     * window projections also block: their windows compute over the
     * UNGROUPED rows, which no longer exist once GROUP BY lands.
     */
    /**
     * Whether any projection is ROW-MULTIPLYING (a select-list UNNEST —
     * flatten's emission). COUNT(*)-replacement and other whole-row
     * summaries must isolate first: swapping projections out would count
     * the PRE-explosion rows.
     */
    static boolean unnestInProjections(SqlSelect s) {
        return s.projections().stream().anyMatch(p -> containsUnnest(p.expr()));
    }

    private static boolean containsUnnest(SqlExpr e) {
        return e instanceof SqlExpr.Call c
                && (c.fn() == SqlFn.UNNEST
                        || c.args().stream().anyMatch(Fold::containsUnnest));
    }

    static boolean groupByFolds(SqlSelect s) {
        return s.groupBy().isEmpty() && !s.distinct() && s.orderBy().isEmpty()
                && s.limit() == null && s.offset() == null && s.qualify() == null
                && s.projections().stream()
                        .noneMatch(p -> p.expr() instanceof SqlExpr.WindowCall);
    }

    /**
     * extend APPENDS a computed column: row count is untouched, so it commutes
     * with truncation and ordering; only DISTINCT is a boundary (extending a
     * deduped row set would dedup WITH the new column).
     */
    static boolean extendFolds(SqlSelect s) {
        return !s.distinct();
    }

    /**
     * A window column computes over the CURRENT select's row set: WHERE is fine
     * (windows evaluate after it), ORDER BY is fine (independent orderings) —
     * but truncation, dedup, and grouping all change the row set the window
     * must see, so each forces isolation.
     */
    static boolean windowFolds(SqlSelect s) {
        return !s.distinct() && s.limit() == null && s.offset() == null && s.groupBy().isEmpty();
    }

    /** Sort folds iff ORDER BY is free (a second sort re-orders; last wins only via isolation). */
    static boolean sortFolds(SqlSelect s) {
        // LIMIT/OFFSET guard: within ONE select ORDER BY applies BEFORE
        // LIMIT, so folding a sort into an already-limited select would
        // sort-then-limit where the query asked limit-then-sort (audit).
        return s.orderBy().isEmpty() && s.limit() == null && s.offset() == null;
    }

    /** {@code limit n} folds iff LIMIT is free (limit-of-limit must nest to keep the smaller window). */
    static boolean limitFolds(SqlSelect s) {
        return s.limit() == null;
    }

    /** {@code drop n} folds iff OFFSET AND LIMIT are free (offset after limit shrinks the window). */
    static boolean offsetFolds(SqlSelect s) {
        return s.offset() == null && s.limit() == null;
    }

    /**
     * DISTINCT dedups the projected row; it folds only while nothing
     * order-or-truncation-sensitive is pending (master's rule: groupBy,
     * orderBy, limit all force isolation).
     */
    static boolean distinctFolds(SqlSelect s) {
        return s.groupBy().isEmpty() && s.orderBy().isEmpty()
                && s.limit() == null && s.offset() == null;
    }

    /**
     * Can a predicate/projection reference {@code column} directly in this
     * select, and as WHAT expression? Star select: the column IS a source
     * column. Projected select: the reference substitutes to the projection's
     * expression &mdash; folding stays legal when that expression is a plain
     * column (real legend's restrict/rename flatten); a COMPUTED projection
     * returns null and the caller isolates (recomputing scalars in WHERE is
     * legal but deferred until the corpus pins it).
     */
    static SqlExpr resolveInto(SqlSelect s, String column) {
        if (column == null) {
            // a COLUMN-LESS read reaching fold resolution was an NPE
            // (dishonest wall) — the producing shape failed to name its
            // column; surface it loudly (audit 24 follow-on).
            throw new IllegalStateException("resolver bug: a column-less"
                    + " read reached fold resolution — the producing shape"
                    + " did not name its column");
        }
        SqlExpr r = resolveIntoExact(s, column);
        if (r == null) {
            // A pivot dynamic column's PURE identity carries quotes
            // ('2011__|__newCol'); its SQL name is the bare inner text —
            // retry unquoted.
            String bare = pivotIdentity(column);
            if (!bare.equals(column)) {
                r = resolveIntoExact(s, bare);
            }
        }
        return r;
    }

    private static String pivotIdentity(String column) {
        return column.length() >= 2 && column.startsWith("'") && column.endsWith("'")
                && column.contains(Type.RelationType.PIVOT_SEPARATOR)
                ? column.substring(1, column.length() - 1)
                : column;
    }

    private static SqlExpr resolveIntoExact(SqlSelect s, String column) {
        if (s.projections().isEmpty()) {
            return sourceColumn(s.from(), column);
        }
        boolean star = false;
        for (SqlSelect.Projection p : s.projections()) {
            if (p.expr() instanceof SqlExpr.Star) {
                star = true;
                continue;
            }
            String name = p.outputName();
            if (column.equals(name)) {
                return p.expr() instanceof SqlExpr.Column c ? c : null;
            }
        }
        // A star projection (extend's `t0.*, expr AS x`) keeps every source
        // column visible; names not claimed by an explicit projection resolve
        // straight to the source.
        return star ? sourceColumn(s.from(), column) : null;
    }

    /**
     * The qualified column reference for {@code column} within a FROM source.
     * Single-alias sources resolve schema-blind (the alias qualifies any name
     * — Phase G already validated existence). A JOIN resolves by SIDE: the
     * side whose output schema claims the name qualifies it; join outputs are
     * disjoint by Phase-G typing (duplicate columns are a type error; prefix
     * joins rename). Null when no side claims the column.
     */
    static SqlExpr.Column sourceColumn(SqlSource src, String column) {
        // A quote-bearing pivot IDENTITY ('2011__|__newCol') strips to its
        // bare SQL name ONLY when the source does not claim the exact name —
        // a genuine column carrying that spelling (its own extend) wins.
        if (src.outputs().isEmpty() || !claims(src.outputs(), column)) {
            column = pivotIdentity(column);
        }
        return switch (src) {
            case SqlSource.Table t -> claims(t.outputs(), column)
                    ? new SqlExpr.Column(t.alias(), column) : null;
            case SqlSource.Subselect sub -> claims(sub.outputs(), column)
                    ? new SqlExpr.Column(sub.alias(), column) : null;
            case SqlSource.Values v -> claims(v.outputs(), column)
                    ? new SqlExpr.Column(v.alias(), column) : null;
            case SqlSource.SourceUrl u -> claims(u.outputs(), column)
                    ? new SqlExpr.Column(u.alias(), column) : null;
            // Pivot outputs are DYNAMIC (one column per pivoted value) — the
            // static schema cannot enumerate them, so a pivot claims any name.
            case SqlSource.Pivot p -> new SqlExpr.Column(p.alias(), column);
            case SqlSource.Join j -> {
                SqlExpr.Column left = sourceColumn(j.left(), column);
                yield left != null ? left : sourceColumn(j.right(), column);
            }
        };
    }

    /**
     * Schema-AWARE claim check: a source claims only columns its stamped
     * outputs actually contain — load-bearing for CORRELATED scopes, where an
     * unclaimed name must fall through to the enclosing lambda instead of
     * being blindly alias-qualified. The real pipeline stamps outputs on
     * every source; an UNSTAMPED source is a construction bug and fails
     * loudly rather than silently claiming everything.
     */
    private static boolean claims(List<OutputCol> outputs, String column) {
        if (outputs.isEmpty()) {
            throw new IllegalStateException(
                    "source has no stamped output schema — cannot resolve column '"
                            + column + "' (stamp outputs at construction)");
        }
        return outputs.stream().anyMatch(c -> c.name().equals(column));
    }

    /** One-line structural sketch of a join side for the unknown-column
     * wall — table/alias per leaf, projection names for subselects. */
    static String describeSource(SqlSource s) {
        return switch (s) {
            case SqlSource.Join j -> "join(" + describeSource(j.left()) + ", "
                    + describeSource(j.right()) + ")";
            case SqlSource.Subselect sub -> "subselect:" + sub.alias() + "("
                    + (sub.inner() instanceof SqlSelect ss
                            ? ss.projections().stream()
                                    .map(SqlSelect.Projection::alias)
                                    .collect(java.util.stream.Collectors
                                            .joining(","))
                            : "union") + ")";
            default -> s.getClass().getSimpleName() + ":" + s.alias() + "("
                    + s.outputs().stream().map(o -> o.name())
                            .collect(java.util.stream.Collectors.joining(","))
                    + ")";
        };
    }

    /** A DATETIME-family serialize leaf renders the engine's ISO wire
     * form ({@code 2015-08-26T00:00:00.000000000} — 'T' separator +
     * 9-digit nanos; DuckDB's raw json timestamp text is
     * space-separated). Abstract Date formats by the PHYSICAL value's
     * precision, the engine's JDBC value-class dispatch (java.sql.Date
     * -> bare day, Timestamp -> full instant): setup DDL can diverge
     * from the store declaration, so the dispatch must be runtime
     * ({@code typeof}). NULL propagates (CONCAT would swallow it). */
    static SqlExpr jsonDateWrap(SqlExpr e,
            com.legend.compiler.element.type.Type t) {
        if (t != com.legend.compiler.element.type.Type.Primitive.DATE_TIME
                && t != com.legend.compiler.element.type.Type.Primitive.DATE) {
            return e;
        }
        SqlExpr iso = SqlExpr.Call.of(SqlFn.CONCAT,
                SqlExpr.Call.of(SqlFn.STRFTIME, e,
                        new SqlExpr.StringLit("%Y-%m-%dT%H:%M:%S.%f")),
                new SqlExpr.StringLit("000"));
        List<SqlExpr.Case.When> arms = new java.util.ArrayList<>();
        arms.add(new SqlExpr.Case.When(
                SqlExpr.Call.of(SqlFn.IS_NULL, e), new SqlExpr.NullLit()));
        if (t == com.legend.compiler.element.type.Type.Primitive.DATE) {
            arms.add(new SqlExpr.Case.When(
                    SqlExpr.Call.of(SqlFn.EQUAL,
                            SqlExpr.Call.of(SqlFn.TYPEOF, e),
                            new SqlExpr.StringLit("DATE")),
                    SqlExpr.Call.of(SqlFn.STRFTIME, e,
                            new SqlExpr.StringLit("%Y-%m-%d"))));
        }
        return new SqlExpr.Case(arms, iso);
    }


    /** The serialize leaf's DECLARED result type (the lambda's
     * FunctionType result — the resolver stamps the MODEL property type
     * there; the body's own info is column-typed). */
    static com.legend.compiler.element.type.Type leafResultType(
            com.legend.compiler.spec.typed.TypedFuncCol leaf) {
        return leaf.fn().info().type()
                instanceof Type.FunctionType ft
                ? ft.result().type() : leaf.fn().body().get(leaf.fn().body().size() - 1)
                        .info().type();
    }


}
