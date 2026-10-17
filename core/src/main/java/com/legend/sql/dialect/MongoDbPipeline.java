// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend.sql.dialect;

import com.legend.base.Nullable;
import com.legend.sql.OutputCol;
import com.legend.sql.SqlAgg;
import com.legend.sql.SqlExpr;
import com.legend.sql.SqlFn;
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

    /** The query's stages; the fields its last projection names, or null when a star carries more than it names. */
    private @Nullable List<String> query(SqlWriter w, SqlQuery q, Stages st) {
        return switch (q) {
            case SqlSelect s -> select(w, s, st);
            case SqlUnion u -> union(w, u, st);
            case SqlWith with -> throw new DialectCapability("a WITH (common table expressions) reached MongoDb,"
                    + " which has no shape for one yet");
        };
    }

    /** A union: the first branch's stages, then each other branch as a {@code $unionWith} over its own base; a
     *  deduplicating union groups the rows afterwards. */
    private @Nullable List<String> union(SqlWriter w, SqlUnion u, Stages st) {
        List<String> projected = query(w, u.branches().get(0), st);
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
        return projected;
    }

    /** The rows made distinct: grouped by the whole row, then each group's row. */
    private static void distinct(SqlWriter w, Stages st) {
        st.begin(w).append("{\"$group\": {\"_id\": \"$$ROOT\"}}");
        st.begin(w).append("{\"$replaceWith\": \"$_id\"}");
    }

    /** One select, clause by clause, in SQL's order of evaluation. */
    private @Nullable List<String> select(SqlWriter w, SqlSelect s, Stages st) {
        if (s.qualify() != null) {
            throw new DialectCapability("QUALIFY reached MongoDb: windows have no shape yet");
        }
        source(w, s.from(), st, Shape.WRAPPED);
        Clauses clauses = subqueries(w, s, st);
        if (clauses.where != null) {
            match(w, st, clauses.where);
        }
        Grouping g = group(w, s.groupBy(), clauses.projections, clauses.having, clauses.orderBy, st);
        if (g.having != null) {
            match(w, st, g.having);
        }
        List<SortCarry> sorts = sortCarries(w, st, g);
        List<String> projected = projection(w, s, st, g, sorts);
        if (s.distinct()) {
            // the sort carries are functions of the row's columns, so equal rows carry equal keys: the distinct
            // groups them with the row and the sort reads them after
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
        return projected;
    }

    // ==================================================================
    // Subqueries
    // ==================================================================

    /** A select's clauses with each subquery read through its lookup's marker. */
    private record Clauses(@com.legend.base.Nullable SqlExpr where, List<SqlSelect.Projection> projections,
            @com.legend.base.Nullable SqlExpr having, List<SqlSelect.SortKey> orderBy) {
    }

    /** The subqueries of a select's expressions, each replaced by its marker: a scalar subquery and an EXISTS by a
     *  marker column; an IN by the needle's membership in the marker's rows. */
    private static final class Subqueries {
        final List<SqlExpr> found = new ArrayList<>();

        SqlExpr apply(SqlExpr e) {
            return switch (e) {
                case SqlExpr.ScalarSubquery sq -> marker(sq);
                case SqlExpr.Exists ex -> marker(ex);
                case SqlExpr.InSubquery in -> new SqlExpr.Membership(apply(in.value()), marker(in));
                default -> e.mapChildren(this::apply);
            };
        }

        private SqlExpr marker(SqlExpr subquery) {
            found.add(subquery);
            return SqlExpr.Column.derived(null, MongoDb.SUBQUERY_MARKER + (found.size() - 1));
        }
    }

    /**
     * Each subquery of the select as a {@code $lookup} into a marker field, after the sources and before the clauses
     * that read it: the row's aliases are the lookup's {@code let} variables, so a correlated subquery reads them as
     * variables (a reference the lookup does not bind fails on the server by name); the subquery's own pipeline
     * runs over its base collection, or over its literal rows. A scalar subquery reads its first row's one column
     * (null over no row, SQL's answer); EXISTS reads whether a row came; IN reads the column of every row.
     */
    private Clauses subqueries(SqlWriter w, SqlSelect s, Stages st) {
        Subqueries sub = new Subqueries();
        SqlExpr where = s.where() == null ? null : sub.apply(s.where());
        List<SqlSelect.Projection> ps = new ArrayList<>();
        for (SqlSelect.Projection p : s.projections()) {
            SqlExpr e = sub.apply(p.expr());
            ps.add(e == p.expr() ? p : new SqlSelect.Projection(e, p.alias() != null ? p.alias() : p.outputName(),
                    p.out()));
        }
        SqlExpr having = s.having() == null ? null : sub.apply(s.having());
        List<SqlSelect.SortKey> keys = new ArrayList<>();
        for (SqlSelect.SortKey k : s.orderBy()) {
            keys.add(new SqlSelect.SortKey(sub.apply(k.expr()), k.ascending(), k.nullOrder(), k.outputName()));
        }
        if (!sub.found.isEmpty()) {
            List<String> outer = aliases(s.from());
            Map<String, SqlWriter.Piece> reads = new LinkedHashMap<>();
            for (int i = 0; i < sub.found.size(); i++) {
                String field = MongoDb.SUBQUERY_MARKER + i;
                SqlQuery inner = switch (sub.found.get(i)) {
                    case SqlExpr.ScalarSubquery sq -> sq.subquery();
                    case SqlExpr.Exists ex -> ex.subquery();
                    case SqlExpr.InSubquery in -> in.subquery();
                    default -> throw new IllegalStateException("not a subquery: " + sub.found.get(i));
                };
                String base = baseCollection(inner);
                st.begin(w).append("{\"$lookup\": {");
                if (base != null) {
                    w.append("\"from\": ").append(MongoJson.string(base)).append(", ");
                }
                w.append("\"let\": {");
                for (int a = 0; a < outer.size(); a++) {
                    w.append(a > 0 ? ", " : "").append(MongoJson.string(MongoJson.variable(outer.get(a))))
                            .append(": ").append(MongoJson.path(outer.get(a)));
                }
                w.append("}, \"pipeline\": [");
                dialect.pushLookupScope(outer, null);
                List<String> projected;
                try {
                    projected = query(w, inner, new Stages());
                } finally {
                    dialect.popScope();
                }
                w.append("], \"as\": ").append(MongoJson.key(field)).append("}}");
                reads.put(field, subqueryRead(sub.found.get(i), field, inner, projected));
            }
            dialect.subqueryReads(reads);
        }
        return new Clauses(where, ps, having, keys);
    }

    private static SqlWriter.Piece subqueryRead(SqlExpr subquery, String field, SqlQuery inner,
            @Nullable List<String> projected) {
        String path = MongoJson.string("$" + field);
        return switch (subquery) {
            case SqlExpr.ScalarSubquery sq -> MongoJson.text("{\"$let\": {\"vars\": {\"r\": {\"$arrayElemAt\": ["
                    + path + ", 0]}}, \"in\": " + MongoJson.string("$$r." + readField(inner, projected)) + "}}");
            case SqlExpr.Exists ex -> MongoJson.text("{\"$gt\": [{\"$size\": " + path + "}, 0]}");
            case SqlExpr.InSubquery in -> MongoJson.text("{\"$map\": {\"input\": " + path + ", \"in\": "
                    + MongoJson.string("$$this." + readField(inner, projected)) + "}}");
            default -> throw new IllegalStateException("not a subquery: " + subquery);
        };
    }

    /** The one field a subquery in expression position reads: the field its pipeline's projection stage named (a
     *  projection without an output column, a bare {@code COUNT(*)}, still has its field); else its one output
     *  column. */
    private static String readField(SqlQuery inner, @Nullable List<String> projected) {
        if (projected != null && projected.size() == 1) {
            return projected.get(0);
        }
        List<OutputCol> outs = inner.outputs();
        if (outs.size() != 1) {
            throw new DialectCapability("a subquery of " + outs.size() + " columns reached MongoDb in expression"
                    + " position: one column is read");
        }
        return outs.get(0).name();
    }

    /** The field the {@code i}th projection becomes: its alias, else its output column's name, else positional. */
    private static String fieldName(SqlSelect.Projection p, int i) {
        return p.alias() != null ? p.alias() : p.outputName() != null ? p.outputName() : "_c" + i;
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
    private Grouping group(SqlWriter w, List<SqlExpr> groupBy, List<SqlSelect.Projection> projections,
            @com.legend.base.Nullable SqlExpr having0, List<SqlSelect.SortKey> orderBy, Stages st) {
        boolean aggregates = projections.stream().anyMatch(p -> SqlAgg.aggregatesSelect(p.expr()))
                || having0 != null && SqlAgg.aggregatesSelect(having0)
                || orderBy.stream().anyMatch(k -> SqlAgg.aggregatesSelect(k.expr()));
        if (groupBy.isEmpty() && !aggregates) {
            return new Grouping(projections, having0, orderBy);
        }
        Rewrite rewrite = new Rewrite(groupBy);
        List<SqlSelect.Projection> ps = new ArrayList<>();
        for (SqlSelect.Projection p : projections) {
            // the output keeps its name: a key projected bare reads the group's _id, under the column's own name
            ps.add(new SqlSelect.Projection(rewrite.apply(p.expr()), p.alias() != null ? p.alias() : p.outputName(),
                    p.out()));
        }
        SqlExpr having = having0 == null ? null : rewrite.apply(having0);
        List<SqlSelect.SortKey> keys = new ArrayList<>();
        for (SqlSelect.SortKey k : orderBy) {
            keys.add(new SqlSelect.SortKey(rewrite.apply(k.expr()), k.ascending(), k.nullOrder(), k.outputName()));
        }
        st.begin(w).append("{\"$group\": {\"_id\": ");
        if (groupBy.isEmpty()) {
            w.append("null");
        } else {
            w.append("{");
            for (int i = 0; i < groupBy.size(); i++) {
                if (i > 0) {
                    w.append(", ");
                }
                w.append("\"k").append(i).append("\": ").expr(groupBy.get(i), 0);
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
        if (groupBy.isEmpty()) {
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
    private static List<SortCarry> sortCarries(SqlWriter w, Stages st, Grouping g) {
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
    private static @Nullable List<String> projection(SqlWriter w, SqlSelect s, Stages st, Grouping g,
            List<SortCarry> sorts) {
        st.begin(w).append("{\"$replaceWith\": ");
        List<SqlWriter.Piece> merged = new ArrayList<>();
        List<String> names = new ArrayList<>();
        List<SqlExpr> exprs = new ArrayList<>();
        List<String> unnested = new ArrayList<>();
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
                String name = fieldName(p, i);
                names.add(name);
                if (p.expr() instanceof SqlExpr.Call call && call.fn() == SqlFn.UNNEST) {
                    // the list is projected whole and exploded after ($unwind): a collection result's rows
                    unnested.add(name);
                    exprs.add(call.args().get(0));
                } else {
                    exprs.add(p.expr());
                }
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
        unwind(w, st, unnested);
        return merged.isEmpty() ? names : null;
    }

    /** The exploded lists of a projection: one list unwound (a null or empty list gives no row, as UNNEST does);
     *  several zipped by position to the longest, null-padded (the select-list UNNEST's positional rule), unwound,
     *  and read back into their columns. */
    private static void unwind(SqlWriter w, Stages st, List<String> unnested) {
        if (unnested.isEmpty()) {
            return;
        }
        if (unnested.size() == 1) {
            st.begin(w).append("{\"$unwind\": ").append(MongoJson.path(unnested.get(0))).append("}");
            return;
        }
        st.begin(w).append("{\"$addFields\": {\"__zip\": {\"$zip\": {\"inputs\": [");
        for (int i = 0; i < unnested.size(); i++) {
            w.append(i > 0 ? ", " : "").append("{\"$ifNull\": [").append(MongoJson.path(unnested.get(i)))
                    .append(", []]}");
        }
        w.append("], \"useLongestLength\": true}}}}");
        st.begin(w).append("{\"$unwind\": \"$__zip\"}");
        st.begin(w).append("{\"$addFields\": {");
        for (int i = 0; i < unnested.size(); i++) {
            w.append(i > 0 ? ", " : "").append(MongoJson.key(unnested.get(i))).append(": {\"$arrayElemAt\": [\"$__zip\", ")
                    .append(i).append("]}");
        }
        w.append("}}");
        st.begin(w).append("{\"$unset\": \"__zip\"}");
    }
}
