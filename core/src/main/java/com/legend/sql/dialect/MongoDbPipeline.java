// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend.sql.dialect;

import com.legend.sql.OutputCol;
import com.legend.sql.SqlAgg;
import com.legend.sql.SqlExpr;
import com.legend.sql.SqlQuery;
import com.legend.sql.SqlSelect;
import com.legend.sql.SqlSource;
import com.legend.sql.SqlUnion;
import com.legend.sql.SqlWith;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A query as MongoDB's aggregation pipeline (docs/MONGODB_PLAN_2026_10_16.md §5.1, 2026-10-16): the stages
 * {@link MongoDb} writes for a select, a union and their sources. The row conventions every stage keeps:
 * <ul>
 *   <li>a select's RESULT rows are flat documents, one field per output column, named as the select names them;</li>
 *   <li>inside a select, the current row holds each source under its alias ({@code {t0: {...}, t1: {...}}}), so a
 *       column reference {@code t0.c} is the path {@code $t0.c}; a scan projects every declared column with
 *       {@code $ifNull}, so an absent field reads as null (D7) and no later stage meets a missing field;</li>
 *   <li>a join is a {@code $lookup} whose pipeline sees the left row's aliases as {@code let} variables and the right
 *       source's rows FLAT (the right alias is the lookup's {@code as}), then {@code $unwind}; a left join's missed
 *       row is the null row of the right source's columns;</li>
 *   <li>a group is one {@code $group} whose accumulators are read back through markers the projection carries; an
 *       aggregate over no rows answers SQL's one row through a union with the empty row;</li>
 *   <li>a sort key is computed into a carried field with its null flag, so NULLS FIRST and LAST are the SQL
 *       engine's (BSON's own order puts nulls first ascending);</li>
 *   <li>a literal relation is {@code $documents}: PCT's storeless expressions load nothing.</li>
 * </ul>
 * What the pipeline has no shape for yet is refused by name: a right, full or lateral join, a pivot, a CTE, raw text,
 * a catalog grid, a window.
 */
final class MongoDbPipeline {

    private final MongoDb dialect;

    MongoDbPipeline(MongoDb dialect) {
        this.dialect = dialect;
    }

    /** The stages written so far at one level: a comma before each but the first. */
    static final class Stages {
        private int written;

        SqlWriter begin(SqlWriter w) {
            if (written++ > 0) {
                w.append(", ");
            }
            return w;
        }
    }

    /** How a source's rows are shaped: under the source's alias (the current row of a select), or flat (the right
     *  side of a lookup, whose alias is the lookup's {@code as}). */
    private enum Shape { WRAPPED, FLAT }

    // ==================================================================
    // The command
    // ==================================================================

    /** The whole {@code aggregate} command for {@code q}: over its base collection, or the database when the query
     *  reads only literal relations. */
    SqlWriter command(SqlWriter w, SqlQuery q) {
        String base = baseCollection(q);
        w.append("{\"aggregate\": ").append(base == null ? "1" : MongoJson.string(base)).append(", \"pipeline\": [");
        query(w, q, new Stages());
        return w.append("], \"cursor\": {}}");
    }

    /** The collection a query's pipeline starts from: its FROM's base table, read through its subselects and down the
     *  left side of its joins; null for a query over literal rows alone. */
    private static @com.legend.base.Nullable String baseCollection(SqlQuery q) {
        return switch (q) {
            case SqlSelect s -> baseOf(s.from());
            case SqlUnion u -> baseCollection(u.branches().get(0));
            case SqlWith with -> throw new DialectCapability("a WITH (common table expressions) reached MongoDb,"
                    + " which has no shape for one yet");
        };
    }

    private static @com.legend.base.Nullable String baseOf(SqlSource src) {
        return switch (src) {
            case SqlSource.Table t -> collectionName(t);
            case SqlSource.Subselect sub -> baseCollection(sub.inner());
            case SqlSource.Join j -> baseOf(j.left());
            case SqlSource.Values v -> null;
            case SqlSource.Dual d -> null;
            case SqlSource.Cte c -> throw new DialectCapability("a CTE reference reached MongoDb: no shape yet");
            case SqlSource.VarSetPlaceholder v -> throw new IllegalStateException("a plan variable (" + v.varName()
                    + ") reached an executable dialect: plan-text vocabulary only");
            case SqlSource.RawSql r -> throw new DialectCapability("a raw statement as a relation reached MongoDb:"
                    + " a user's own pipeline as a source is not run yet");
            case SqlSource.Catalog c -> throw new DialectCapability("a catalog grid reached MongoDb: its catalog"
                    + " (listCollections) is not read as a grid yet");
            case SqlSource.SourceUrl u -> throw new DialectCapability("sourceUrl reached MongoDb: no external"
                    + " source encoding");
            case SqlSource.Pivot p -> throw new DialectCapability("a pivot reached MongoDb: no shape yet (a static"
                    + " pivot's grouped form is a later landing)");
        };
    }

    /** A collection's name: a table's bare name; a schema-qualified or tabular-function table is refused. */
    private static String collectionName(SqlSource.Table t) {
        if (t.call()) {
            throw new DialectCapability("a tabular function (" + t.name() + ") reached MongoDb, which has none");
        }
        if (t.name().indexOf('.') >= 0) {
            throw new DialectCapability("a schema-qualified table (" + t.name() + ") reached MongoDb: a collection"
                    + " has no schema (its database is the connection's)");
        }
        return t.name();
    }

    // ==================================================================
    // Queries
    // ==================================================================

    private void query(SqlWriter w, SqlQuery q, Stages st) {
        switch (q) {
            case SqlSelect s -> select(w, s, st);
            case SqlUnion u -> union(w, u, st);
            case SqlWith with -> throw new DialectCapability("a WITH (common table expressions) reached MongoDb,"
                    + " which has no shape for one yet");
        }
    }

    /** A union: the first branch's stages, then each other branch as a {@code $unionWith} over its own base; a
     *  deduplicating union groups the rows afterwards. */
    private void union(SqlWriter w, SqlUnion u, Stages st) {
        query(w, u.branches().get(0), st);
        for (int i = 1; i < u.branches().size(); i++) {
            SqlQuery branch = u.branches().get(i);
            String base = baseCollection(branch);
            st.begin(w).append("{\"$unionWith\": {");
            if (base != null) {
                w.append("\"coll\": ").append(MongoJson.string(base)).append(", ");
            }
            w.append("\"pipeline\": [");
            query(w, branch, new Stages());
            w.append("]}}");
        }
        if (!u.all()) {
            distinct(w, st);
        }
    }

    /** The rows made distinct: grouped by the whole row, then each group's row. */
    private static void distinct(SqlWriter w, Stages st) {
        st.begin(w).append("{\"$group\": {\"_id\": \"$$ROOT\"}}");
        st.begin(w).append("{\"$replaceWith\": \"$_id\"}");
    }

    /** One select, clause by clause, in SQL's order of evaluation. */
    private void select(SqlWriter w, SqlSelect s, Stages st) {
        if (s.qualify() != null) {
            throw new DialectCapability("QUALIFY reached MongoDb: windows have no shape yet");
        }
        source(w, s.from(), st, Shape.WRAPPED);
        if (s.where() != null) {
            match(w, st, s.where());
        }
        Grouping g = group(w, s, st);
        if (g.having != null) {
            match(w, st, g.having);
        }
        List<SortCarry> sorts = sortCarries(w, s, st, g);
        projection(w, s, st, g, sorts);
        if (s.distinct()) {
            if (!sorts.isEmpty()) {
                throw new DialectCapability("DISTINCT with ORDER BY reached MongoDb: a sort key carried beside a"
                        + " distinct select's rows is not shaped yet");
            }
            distinct(w, st);
        }
        if (!sorts.isEmpty()) {
            st.begin(w).append("{\"$sort\": {");
            for (int i = 0; i < sorts.size(); i++) {
                SortCarry c = sorts.get(i);
                if (i > 0) {
                    w.append(", ");
                }
                w.append(MongoJson.string(c.nullField)).append(": ").append(c.nullsFirst ? -1 : 1).append(", ")
                        .append(MongoJson.string(c.field)).append(": ").append(c.ascending ? 1 : -1);
            }
            w.append("}}");
            st.begin(w).append("{\"$unset\": [");
            for (int i = 0; i < sorts.size(); i++) {
                if (i > 0) {
                    w.append(", ");
                }
                w.append(MongoJson.string(sorts.get(i).field)).append(", ")
                        .append(MongoJson.string(sorts.get(i).nullField));
            }
            w.append("]}");
        }
        if (s.offset() != null) {
            st.begin(w).append("{\"$skip\": ").append(s.offset()).append("}");
        }
        if (s.limit() != null) {
            st.begin(w).append("{\"$limit\": ").append(s.limit()).append("}");
        }
    }

    private static void match(SqlWriter w, Stages st, SqlExpr condition) {
        st.begin(w).append("{\"$match\": {\"$expr\": ").expr(condition, 0).append("}}");
    }

    // ==================================================================
    // Sources
    // ==================================================================

    private void source(SqlWriter w, SqlSource src, Stages st, Shape shape) {
        switch (src) {
            case SqlSource.Table t -> scan(w, t, st, shape);
            case SqlSource.Values v -> values(w, v, st, shape);
            case SqlSource.Dual d -> st.begin(w).append("{\"$documents\": [{}]}");
            case SqlSource.Subselect sub -> {
                query(w, sub.inner(), st);
                if (shape == Shape.WRAPPED) {
                    st.begin(w).append("{\"$replaceWith\": {").append(MongoJson.key(sub.alias()))
                            .append(": \"$$ROOT\"}}");
                }
            }
            case SqlSource.Join j -> {
                if (shape == Shape.FLAT) {
                    throw new DialectCapability("a join on the right side of a join reached MongoDb: a lookup"
                            + " over a joined source has no shape yet");
                }
                join(w, j, st);
            }
            case SqlSource.Cte c -> throw new DialectCapability("a CTE reference reached MongoDb: no shape yet");
            case SqlSource.VarSetPlaceholder v -> throw new IllegalStateException("a plan variable (" + v.varName()
                    + ") reached an executable dialect: plan-text vocabulary only");
            case SqlSource.RawSql r -> throw new DialectCapability("a raw statement as a relation reached MongoDb:"
                    + " a user's own pipeline as a source is not run yet");
            case SqlSource.Catalog c -> throw new DialectCapability("a catalog grid reached MongoDb: its catalog"
                    + " (listCollections) is not read as a grid yet");
            case SqlSource.SourceUrl u -> throw new DialectCapability("sourceUrl reached MongoDb: no external"
                    + " source encoding");
            case SqlSource.Pivot p -> throw new DialectCapability("a pivot reached MongoDb: no shape yet (a static"
                    + " pivot's grouped form is a later landing)");
        }
    }

    /** A collection's scan: each declared column read with {@code $ifNull}, so an absent field is null (D7), under
     *  the alias or flat; the document's own {@code _id} dropped unless the store declares it. */
    private static void scan(SqlWriter w, SqlSource.Table t, Stages st, Shape shape) {
        collectionName(t);
        boolean declaresId = t.outputs().stream().anyMatch(c -> c.name().equals("_id"));
        st.begin(w).append("{\"$project\": {");
        if (!declaresId) {
            w.append("\"_id\": 0").append(t.outputs().isEmpty() ? "" : ", ");
        }
        if (shape == Shape.WRAPPED) {
            w.append(MongoJson.key(t.alias())).append(": {");
        }
        for (int i = 0; i < t.outputs().size(); i++) {
            OutputCol c = t.outputs().get(i);
            if (i > 0) {
                w.append(", ");
            }
            w.append(MongoJson.key(c.name())).append(": {\"$ifNull\": [").append(MongoJson.path(c.name()))
                    .append(", null]}");
        }
        if (shape == Shape.WRAPPED) {
            w.append("}");
        }
        w.append("}}");
    }

    /** Literal rows as {@code $documents}, each row's cells under the relation's column names. */
    private static void values(SqlWriter w, SqlSource.Values v, Stages st, Shape shape) {
        st.begin(w).append("{\"$documents\": [");
        for (int r = 0; r < v.rows().size(); r++) {
            if (r > 0) {
                w.append(", ");
            }
            if (shape == Shape.WRAPPED) {
                w.append("{").append(MongoJson.key(v.alias())).append(": ");
            }
            w.append("{");
            List<SqlExpr> row = v.rows().get(r);
            for (int c = 0; c < row.size(); c++) {
                if (c > 0) {
                    w.append(", ");
                }
                w.append(MongoJson.key(v.columns().get(c))).append(": ").expr(row.get(c), 0);
            }
            w.append("}");
            if (shape == Shape.WRAPPED) {
                w.append("}");
            }
        }
        w.append("]}");
    }

    /** The aliases a source brings to the row, in order. */
    private static List<String> aliases(SqlSource src) {
        List<String> out = new ArrayList<>();
        switch (src) {
            case SqlSource.Join j -> {
                out.addAll(aliases(j.left()));
                out.addAll(aliases(j.right()));
            }
            case SqlSource.Dual d -> {
            }
            default -> out.add(src.alias());
        }
        return out;
    }

    /** An inner, left or cross join as a {@code $lookup} and an {@code $unwind}. */
    private void join(SqlWriter w, SqlSource.Join j, Stages st) {
        switch (j.kind()) {
            case INNER, LEFT, CROSS -> {
            }
            case RIGHT, FULL, CROSS_LATERAL, LEFT_LATERAL, ASOF_LEFT -> throw new DialectCapability("a "
                    + j.kind() + " join reached MongoDb: only inner, left and cross joins have a shape yet");
        }
        source(w, j.left(), st, Shape.WRAPPED);
        if (j.right() instanceof SqlSource.Join) {
            throw new DialectCapability("a join on the right side of a join reached MongoDb: a lookup over a"
                    + " joined source has no shape yet");
        }
        String right = j.right().alias();
        List<String> left = aliases(j.left());
        String base = baseOf(j.right());
        st.begin(w).append("{\"$lookup\": {");
        if (base != null) {
            w.append("\"from\": ").append(MongoJson.string(base)).append(", ");
        }
        w.append("\"let\": {");
        for (int i = 0; i < left.size(); i++) {
            if (i > 0) {
                w.append(", ");
            }
            w.append(MongoJson.string(MongoJson.variable(left.get(i)))).append(": ")
                    .append(MongoJson.path(left.get(i)));
        }
        w.append("}, \"pipeline\": [");
        dialect.pushLookupScope(left, right);
        try {
            Stages inner = new Stages();
            source(w, j.right(), inner, Shape.FLAT);
            if (j.on() != null) {
                match(w, inner, j.on());
            }
        } finally {
            dialect.popScope();
        }
        w.append("], \"as\": ").append(MongoJson.key(right)).append("}}");
        st.begin(w).append("{\"$unwind\": {\"path\": ").append(MongoJson.path(right))
                .append(", \"preserveNullAndEmptyArrays\": ").append(j.kind() == SqlSource.Join.Kind.LEFT ? "true" : "false")
                .append("}}");
        if (j.kind() == SqlSource.Join.Kind.LEFT) {
            // a missed row: the right source's columns, each null, so every later read finds the field
            st.begin(w).append("{\"$addFields\": {").append(MongoJson.key(right)).append(": {\"$ifNull\": [")
                    .append(MongoJson.path(right)).append(", {");
            List<OutputCol> cols = j.right().outputs();
            for (int i = 0; i < cols.size(); i++) {
                if (i > 0) {
                    w.append(", ");
                }
                w.append(MongoJson.key(cols.get(i).name())).append(": null");
            }
            w.append("}]}}}");
        }
    }

    // ==================================================================
    // Grouping
    // ==================================================================

    /** A grouped select's rewritten clauses: each aggregate node a marker the group stage computed, each group key a
     *  read of the group's {@code _id}. An ungrouped select keeps its clauses as they are. */
    private record Grouping(List<SqlSelect.Projection> projections, @com.legend.base.Nullable SqlExpr having,
            List<SqlSelect.SortKey> orderBy) {
    }

    /** The rewrite: aggregates to markers, keys to {@code _id} reads. */
    private static final class Rewrite {
        final List<SqlExpr> keys;
        final List<SqlExpr> aggregates = new ArrayList<>();

        Rewrite(List<SqlExpr> keys) {
            this.keys = keys;
        }

        SqlExpr apply(SqlExpr e) {
            if (e instanceof SqlAgg.Reducer || e instanceof SqlExpr.OrderedListAgg
                    || e instanceof SqlExpr.JsonArrayAgg) {
                int at = aggregates.indexOf(e);
                if (at < 0) {
                    at = aggregates.size();
                    aggregates.add(e);
                }
                return SqlExpr.Column.derived(null, MongoDb.AGGREGATE_MARKER + at);
            }
            int k = keys.indexOf(e);
            if (k >= 0) {
                return SqlExpr.Column.derived(null, "_id.k" + k);
            }
            return e.mapChildren(this::apply);
        }
    }

    /** The {@code $group} stage when the select groups or aggregates; the clauses rewritten over its outputs. */
    private Grouping group(SqlWriter w, SqlSelect s, Stages st) {
        boolean aggregates = s.projections().stream().anyMatch(p -> SqlAgg.aggregatesSelect(p.expr()))
                || s.having() != null && SqlAgg.aggregatesSelect(s.having())
                || s.orderBy().stream().anyMatch(k -> SqlAgg.aggregatesSelect(k.expr()));
        if (s.groupBy().isEmpty() && !aggregates) {
            return new Grouping(s.projections(), s.having(), s.orderBy());
        }
        Rewrite rewrite = new Rewrite(s.groupBy());
        List<SqlSelect.Projection> ps = new ArrayList<>();
        for (SqlSelect.Projection p : s.projections()) {
            // the output keeps its name: a key projected bare reads the group's _id, under the column's own name
            ps.add(new SqlSelect.Projection(rewrite.apply(p.expr()), p.alias() != null ? p.alias() : p.outputName(),
                    p.out()));
        }
        SqlExpr having = s.having() == null ? null : rewrite.apply(s.having());
        List<SqlSelect.SortKey> keys = new ArrayList<>();
        for (SqlSelect.SortKey k : s.orderBy()) {
            keys.add(new SqlSelect.SortKey(rewrite.apply(k.expr()), k.ascending(), k.nullOrder(), k.outputName()));
        }
        st.begin(w).append("{\"$group\": {\"_id\": ");
        if (s.groupBy().isEmpty()) {
            w.append("null");
        } else {
            w.append("{");
            for (int i = 0; i < s.groupBy().size(); i++) {
                if (i > 0) {
                    w.append(", ");
                }
                w.append("\"k").append(i).append("\": ").expr(s.groupBy().get(i), 0);
            }
            w.append("}");
        }
        Map<String, SqlWriter.Piece> reads = new LinkedHashMap<>();
        for (int i = 0; i < rewrite.aggregates.size(); i++) {
            String field = MongoDb.AGGREGATE_MARKER + i;
            w.append(", ").append(MongoJson.string(field)).append(": ");
            reads.put(field, dialect.accumulator(w, rewrite.aggregates.get(i), field));
        }
        w.append("}}");
        if (s.groupBy().isEmpty()) {
            // SQL's one row over no input: the group answers none, so the empty row is unioned in and the first of
            // the two (the real one when there is one) kept
            st.begin(w).append("{\"$unionWith\": {\"pipeline\": [{\"$documents\": [{\"_id\": null");
            for (int i = 0; i < rewrite.aggregates.size(); i++) {
                w.append(", ").append(MongoJson.string(MongoDb.AGGREGATE_MARKER + i)).append(": ")
                        .append(MongoDb.emptyInput(rewrite.aggregates.get(i)));
            }
            w.append(", \"__none\": 1}]}]}}");
            st.begin(w).append("{\"$sort\": {\"__none\": 1}}");
            st.begin(w).append("{\"$limit\": 1}");
            st.begin(w).append("{\"$unset\": \"__none\"}");
        }
        dialect.aggregateReads(reads);
        return new Grouping(ps, having, keys);
    }

    // ==================================================================
    // Sorting and projecting
    // ==================================================================

    /** A sort key computed into a carried field, with its null flag beside it. */
    private record SortCarry(String field, String nullField, boolean ascending, boolean nullsFirst) {
    }

    /** Each sort key computed before the projection ({@code $addFields}), with its null flag, so the sort reads
     *  fields and the SQL engine's null placement holds (ascending puts nulls last, descending first, unless the key
     *  says otherwise). */
    private static List<SortCarry> sortCarries(SqlWriter w, SqlSelect s, Stages st, Grouping g) {
        if (g.orderBy.isEmpty()) {
            return List.of();
        }
        List<SortCarry> out = new ArrayList<>();
        st.begin(w).append("{\"$addFields\": {");
        for (int i = 0; i < g.orderBy.size(); i++) {
            SqlSelect.SortKey k = g.orderBy.get(i);
            String field = "__sort" + i;
            String nullField = "__null" + i;
            boolean nullsFirst = k.nullOrder() == null ? !k.ascending()
                    : k.nullOrder() == SqlSelect.SortKey.NullOrder.NULLS_FIRST;
            if (i > 0) {
                w.append(", ");
            }
            w.append(MongoJson.string(field)).append(": ").expr(k.expr(), 0).append(", ")
                    .append(MongoJson.string(nullField)).append(": {\"$eq\": [").expr(k.expr(), 0)
                    .append(", null]}");
            out.add(new SortCarry(field, nullField, k.ascending(), nullsFirst));
        }
        w.append("}}");
        return out;
    }

    /** The projection as one {@code $replaceWith}: the outputs by name, a star as the merged source documents, and
     *  the sort carries beside them. */
    private static void projection(SqlWriter w, SqlSelect s, Stages st, Grouping g, List<SortCarry> sorts) {
        st.begin(w).append("{\"$replaceWith\": ");
        List<SqlWriter.Piece> merged = new ArrayList<>();
        List<String> names = new ArrayList<>();
        List<SqlExpr> exprs = new ArrayList<>();
        if (g.projections.isEmpty()) {
            for (String alias : aliases(s.from())) {
                merged.add(x -> x.append(MongoJson.path(alias)));
            }
        }
        for (int i = 0; i < g.projections.size(); i++) {
            SqlSelect.Projection p = g.projections.get(i);
            if (p.expr() instanceof SqlExpr.Star star) {
                if (star.table() != null) {
                    String starred = java.util.Objects.requireNonNull(star.table());
                    merged.add(x -> x.append(MongoJson.path(starred)));
                } else {
                    for (String alias : aliases(s.from())) {
                        merged.add(x -> x.append(MongoJson.path(alias)));
                    }
                }
            } else if (p.expr() instanceof SqlExpr.StarExcept) {
                throw new DialectCapability("a star with excluded columns reached MongoDb: no shape yet");
            } else {
                String name = p.alias() != null ? p.alias() : p.outputName() != null ? p.outputName() : "_c" + i;
                names.add(name);
                exprs.add(p.expr());
            }
        }
        SqlWriter.Piece fields = x -> {
            x.append("{");
            for (int i = 0; i < names.size(); i++) {
                if (i > 0) {
                    x.append(", ");
                }
                x.append(MongoJson.key(names.get(i))).append(": ").expr(exprs.get(i), 0);
            }
            for (SortCarry c : sorts) {
                if (!names.isEmpty() || sorts.indexOf(c) > 0) {
                    x.append(", ");
                }
                x.append(MongoJson.string(c.field)).append(": ").append(MongoJson.path(c.field)).append(", ")
                        .append(MongoJson.string(c.nullField)).append(": ").append(MongoJson.path(c.nullField));
            }
            x.append("}");
        };
        if (merged.isEmpty()) {
            w.piece(fields);
        } else {
            merged.add(fields);
            MongoJson.opOf(w, "$mergeObjects", merged);
        }
        w.append("}");
    }
}
