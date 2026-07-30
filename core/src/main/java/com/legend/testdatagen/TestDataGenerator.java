// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend.testdatagen;

import com.legend.compiler.element.ModelContext;
import com.legend.error.NotImplementedException;
import com.legend.lineage.ScanRelations;
import com.legend.model.DatabaseDefinition;
import com.legend.model.RelationalDataType;
import com.legend.model.RelationalOperation;
import com.legend.model.spec.LambdaFunction;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * The engine's {@code meta::relational::testDataGeneration::generateTestData}
 * (#46): given a query, a mapping and seed row identifiers for the root
 * table, walk the query's {@link ScanRelations} relation tree and extract
 * the minimal supporting rows for every reached table, plus the fetch SQL
 * trail and a CSV dump ({@code schema\ntable\ncols\nrows...-----\n}).
 *
 * <p>Java ORCHESTRATES, the database EXECUTES (tenet #1): every node's
 * rows land in a DuckDB TEMP table ({@code CREATE TEMP TABLE ... AS
 * SELECT}, the engine's own temp-table discipline), child fetches JOIN
 * the parent's temp table in the database, per-table dedup is a DB-side
 * {@code UNION}, and {@link #compareCsv} loads both CSV strings into
 * typed temp tables and diffs them with {@code EXCEPT} both ways (the
 * engine's assertTestData = setUpDataSQLs + assertSameElements — an
 * order-insensitive, type-normalized row-set contract). Row values only
 * cross into Java as display strings for the CSV text.
 *
 * <p>Walls are LOUD ({@link NotImplementedException} naming the pending
 * shape): view-backed relations, hashStrings, temporal milestoning
 * dates, join conditions outside the two-table vocabulary.
 */
public final class TestDataGenerator {

    private TestDataGenerator() {
    }

    /** One {@code createRowIdentifier([cols],[values])}. */
    public record RowId(List<String> cols, List<Object> values) {
    }

    /** One {@code createTableRowIdentifiers(db, schema, table, ids)}. */
    public record TableRowIds(String schema, String table,
            List<RowId> ids) {
    }

    /** The engine's TemporalMilestoningDates: forced dates that FILTER
     * every milestoned table's fetch. */
    public record MilestoningDates(String business, String processing,
            String snapshot) {
    }

    public record Result(List<String> sqls, String dataCsvString,
            @com.legend.Nullable List<String[]> tables) {
        public Result(List<String> sqls, String dataCsvString) {
            this(sqls, dataCsvString, null);
        }
    }

    /** The engine's getRelationalCSVDataFromQuery: the NECESSARY column
     * census per table — (schema, table, comma-joined demanded columns)
     * triples in first-touch order, no execution. */
    public static List<String[]> necessaryColumns(ModelContext ctx,
            LambdaFunction resolvedQuery, String mappingFqn) {
        List<ScanRelations.Rel> roots =
                ScanRelations.relTree(ctx, resolvedQuery, mappingFqn);
        roots = roots.stream().map(r -> expandIfView(ctx, r, null))
                .toList();
        // ENCOUNTER order (no sort): pks ++ non-nullable ++ tree
        // columns ++ temporal milestoning, deduplicated by name.
        // AUDIT-25 NOTE: generateRelationColumnMap (testDataGeneration
        // .pure:573) spells temporal BEFORE tree, but the census golden
        // (testGenerateNecessaryTableColumnsForMilestoningTable) pins
        // tree-before-temporal — getRelationalCSVDataFromQuery evidently
        // assembles differently; the golden is authoritative here
        Map<String, java.util.LinkedHashSet<String>> cm =
                new LinkedHashMap<>();
        for (ScanRelations.Rel r : roots) {
            censusCols(ctx, r, cm);
        }
        List<String[]> out = new ArrayList<>();
        for (Map.Entry<String, java.util.LinkedHashSet<String>> e
                : cm.entrySet()) {
            String[] st = e.getKey().split("\n", 2);
            out.add(new String[]{st[0], st[1],
                    String.join(",", e.getValue())});
        }
        return out;
    }

    private static void censusCols(ModelContext ctx, ScanRelations.Rel rel,
            Map<String, java.util.LinkedHashSet<String>> cm) {
        rel = expandIfView(ctx, rel, null);
        Located loc = locate(ctx, rel.db(), rel.table());
        java.util.LinkedHashSet<String> cols = cm.computeIfAbsent(
                loc.schema() + "\n" + rel.table(),
                k -> new java.util.LinkedHashSet<>());
        for (DatabaseDefinition.ColumnDefinition c : loc.def().columns()) {
            if (c.primaryKey()) {
                cols.add(c.name());
            }
        }
        for (DatabaseDefinition.ColumnDefinition c : loc.def().columns()) {
            if (c.notNull() && !c.primaryKey()) {
                cols.add(c.name());
            }
        }
        for (String c : rel.cols()) {
            cols.add(loc.def().columns().stream()
                    .map(DatabaseDefinition.ColumnDefinition::name)
                    .filter(n -> n.equalsIgnoreCase(c))
                    .findFirst().orElse(c));
        }
        var ms = loc.def().milestoning();
        if (ms != null) {
            if (ms.business() != null) {
                for (String c : new String[]{ms.business().from(),
                        ms.business().thru(),
                        ms.business().snapshotDate()}) {
                    if (c != null) {
                        cols.add(c);
                    }
                }
            }
            if (ms.processing() != null) {
                for (String c : new String[]{ms.processing().in(),
                        ms.processing().out(),
                        ms.processing().snapshotDate()}) {
                    if (c != null) {
                        cols.add(c);
                    }
                }
            }
        }
        for (ScanRelations.Rel child : rel.children()) {
            censusCols(ctx, child, cm);
        }
    }


    public static Result generate(ModelContext ctx,
            LambdaFunction resolvedQuery, String mappingFqn,
            List<TableRowIds> rowIds, Connection conn) throws SQLException {
        return generate(ctx, resolvedQuery, mappingFqn, rowIds, null, conn);
    }

    public static Result generate(ModelContext ctx,
            LambdaFunction resolvedQuery, String mappingFqn,
            List<TableRowIds> rowIds, @com.legend.Nullable MilestoningDates dates,
            Connection conn) throws SQLException {
        return generate(ctx, resolvedQuery, mappingFqn, rowIds, dates,
                false, conn);
    }

    public static Result generate(ModelContext ctx,
            LambdaFunction resolvedQuery, String mappingFqn,
            List<TableRowIds> rowIds, @com.legend.Nullable MilestoningDates dates,
            boolean hashStrings, Connection conn) throws SQLException {
        List<ScanRelations.Rel> roots =
                ScanRelations.relTree(ctx, resolvedQuery, mappingFqn);
        // a VIEW-backed root generates for its UNDERLYING tree (engine
        // generateTestDataForNestedViewTree): the view's seed table is
        // the row-identifier target and its join web fetches as children
        roots = roots.stream().map(r -> expandIfView(ctx, r, null))
                .toList();
        // engine generateRelationColumnMap: column demand merges PER
        // TABLE across ALL tree nodes (a self-join's two fetches of one
        // table share one column set)
        Map<String, List<String>> colMap = new LinkedHashMap<>();
        for (ScanRelations.Rel r : roots) {
            collectColMap(ctx, r, colMap, null);
        }
        List<String> sqls = new ArrayList<>();
        // tableKey (schema\ntable) -> fetch temps, in first-fetch order
        Map<String, Fetched> fetched = new LinkedHashMap<>();
        // per-call temp-name counter (temps drop in finally, so names
        // never collide across sequential invocations)
        List<String> temps = new ArrayList<>();
        try (Statement st = conn.createStatement()) {
            for (ScanRelations.Rel r : roots) {
                fetchRoot(ctx, r, rowIds, st, sqls, fetched, temps, colMap,
                        dates);
            }
            String csv = renderCsv(st, fetched, hashStrings);
            return new Result(List.copyOf(sqls), csv);
        } finally {
            dropTemps(conn, temps);
        }
    }

    private static void collectColMap(ModelContext ctx,
            ScanRelations.Rel rel, Map<String, List<String>> colMap,
            @com.legend.Nullable String parentDb) {
        rel = expandIfView(ctx, rel, parentDb);
        Located loc = locate(ctx, rel.db(), rel.table());
        String key = loc.schema() + "\n" + rel.table();
        // engine sortBy(name) is the plain pure string sort — ASCII
        // case-sensitive (bitemporal golden: ID < PLACE < from_z; the
        // earlier all-uppercase evidence fit either order)
        TreeSet<String> merged = new TreeSet<>();
        merged.addAll(colMap.getOrDefault(key, List.of()));
        for (String c : fetchCols(ctx, loc, rel)) {
            // demanded names arrive in MAPPING spelling — canonicalize
            // to the DDL case before the (case-sensitive) engine sort
            merged.add(loc.def().columns().stream()
                    .map(DatabaseDefinition.ColumnDefinition::name)
                    .filter(n -> n.equalsIgnoreCase(c))
                    .findFirst().orElse(c));
        }
        colMap.put(key, List.copyOf(merged));
        for (ScanRelations.Rel child : rel.children()) {
            collectColMap(ctx, child, colMap, rel.db());
        }
    }

    private record Fetched(String schema, String table, List<String> cols,
            List<String> temps) {
    }

    private record Located(String schema,
            DatabaseDefinition.TableDefinition def) {
    }

    // ===== tree walk =====

    private static void fetchRoot(ModelContext ctx, ScanRelations.Rel rel,
            List<TableRowIds> rowIds, Statement st, List<String> sqls,
            Map<String, Fetched> fetched, List<String> temps,
            Map<String, List<String>> colMap, @com.legend.Nullable MilestoningDates dates)
            throws SQLException {
        Located loc = locate(ctx, rel.db(), rel.table());
        String tbl = java.util.Objects.requireNonNull(rel.table());
        List<String> cols = java.util.Objects.requireNonNull(
                colMap.get(loc.schema() + "\n" + tbl),
                "no column map for " + tbl);
        TableRowIds ids = null;
        for (TableRowIds t : rowIds) {
            if (t.table().equals(rel.table())
                    && t.schema().equals(loc.schema())) {
                ids = t;
            }
        }
        if (ids == null) {
            throw new NotImplementedException("testDataGen: no row"
                    + " identifiers for root table '" + loc.schema() + "."
                    + rel.table() + "' (generateWithDefaultPKs pending)");
        }
        StringBuilder where = new StringBuilder();
        for (RowId id : ids.ids()) {
            if (where.length() > 0) {
                where.append(" or ");
            }
            StringBuilder one = new StringBuilder();
            for (int i = 0; i < id.cols().size(); i++) {
                if (i > 0) {
                    one.append(" and ");
                }
                one.append("\"root\".").append(q(id.cols().get(i)))
                        .append(" = ").append(lit(id.values().get(i),
                                column(loc.def(), id.cols().get(i))));
            }
            one.insert(0, "(").append(")");
            where.append(one);
        }
        String mf = milestoningFilter(loc.def(), "\"root\"", dates);
        String sql = "select " + String.join(", ",
                cols.stream().map(c -> "\"root\"." + q(c)).toList())
                + " from " + qualify(loc.schema(), tbl)
                + " as \"root\" where " + (mf == null ? where.toString()
                        : "(" + where + ") and " + mf) + " limit 20";
        String temp = materialize(st, sql, tbl, temps);
        sqls.add(sql);
        record(fetched, loc.schema(), tbl, cols, temp);
        for (ScanRelations.Rel child : rel.children()) {
            fetchChild(ctx, rel, temp, child, st, sqls, fetched, temps,
                    colMap, dates);
        }
    }

    private static void fetchChild(ModelContext ctx, ScanRelations.Rel parent,
            String parentTemp, ScanRelations.Rel child, Statement st,
            List<String> sqls, Map<String, Fetched> fetched,
            List<String> temps, Map<String, List<String>> colMap,
            @com.legend.Nullable MilestoningDates dates)
            throws SQLException {
        child = expandIfView(ctx, child, parent.db());
        Located loc = locate(ctx, child.db(), child.table());
        String ct = java.util.Objects.requireNonNull(child.table());
        List<String> cols = java.util.Objects.requireNonNull(
                colMap.get(loc.schema() + "\n" + ct),
                "no column map for " + ct);
        RelationalOperation op = child.cond() != null ? child.cond()
                : findJoin(ctx, child.joinName(), child.db(),
                        parent.db()).operation();
        String alias = ct.equals(parent.table()) ? "t_" + ct : ct;
        String cond = renderCondition(op, parent.table(),
                child.table(), alias, String.valueOf(child.joinName()));
        String mf = milestoningFilter(loc.def(), q(alias), dates);
        String sql = "select " + String.join(", ",
                cols.stream().map(c -> q(alias) + "." + q(c)).toList())
                + " from " + parentTemp + " as main inner join "
                + qualify(loc.schema(), ct) + " as " + q(alias)
                + " on " + cond
                + (mf == null ? "" : " where " + mf) + " limit 20";
        String temp = materialize(st, sql, child.table(), temps);
        sqls.add(sql);
        record(fetched, loc.schema(), child.table(), cols, temp);
        for (ScanRelations.Rel sub : child.children()) {
            fetchChild(ctx, child, temp, sub, st, sqls, fetched, temps,
                    colMap, dates);
        }
    }

    /** The engine's getMilestoningFilter: forced temporal dates filter a
     * MILESTONED table's fetch (business/processing from-thru ranges,
     * snapshot equality). A milestoned table without its date is the
     * engine's own assert — a loud wall. */
    private static @com.legend.Nullable String milestoningFilter(
            DatabaseDefinition.TableDefinition def, String alias,
            @com.legend.Nullable MilestoningDates d) {
        var ms = def.milestoning();
        if (ms == null || d == null) {
            return null;
        }
        List<String> parts = new ArrayList<>();
        if (ms.business() != null) {
            var b = ms.business();
            if (b.snapshotDate() != null) {
                parts.add(alias + "." + q(b.snapshotDate()) + " = DATE '"
                        + requireDate(d.snapshot(), def, "snapshotDate")
                        + "'");
            } else {
                String bd = requireDate(d.business(), def, "businessDate");
                parts.add(alias + "." + q(java.util.Objects.requireNonNull(b.from(),
                        "milestoning business block without BUS_FROM"))
                        + " <= DATE '" + bd
                        + "'");
                parts.add(alias + "." + q(java.util.Objects.requireNonNull(b.thru(),
                        "milestoning business block without BUS_THRU"))
                        + (b.thruIsInclusive() ? " >= DATE '" : " > DATE '")
                        + bd + "'");
            }
        }
        if (ms.processing() != null) {
            var pr = ms.processing();
            if (pr.snapshotDate() != null) {
                parts.add(alias + "." + q(pr.snapshotDate()) + " = DATE '"
                        + requireDate(d.processing(), def, "processingDate")
                        + "'");
            } else {
                String pd = requireDate(d.processing(), def,
                        "processingDate");
                parts.add(alias + "." + q(java.util.Objects.requireNonNull(pr.in(),
                        "milestoning processing block without"
                        + " PROCESSING_IN")) + " <= DATE '" + pd
                        + "'");
                parts.add(alias + "." + q(java.util.Objects.requireNonNull(pr.out(),
                        "milestoning processing block without"
                        + " PROCESSING_OUT"))
                        + (pr.outIsInclusive() ? " >= DATE '" : " > DATE '")
                        + pd + "'");
            }
        }
        return parts.isEmpty() ? null : String.join(" and ", parts);
    }

    private static String requireDate(@com.legend.Nullable String v,
            DatabaseDefinition.TableDefinition def, String name) {
        if (v == null) {
            throw new NotImplementedException("testDataGen: table '"
                    + def.name() + "' is milestoned but '" + name
                    + "' was not passed in TemporalMilestoningDates");
        }
        return v;
    }

    private static String materialize(Statement st, String sql,
            String table, List<String> temps) throws SQLException {
        String temp = "tdg_" + temps.size() + "_"
                + table.replaceAll("[^A-Za-z0-9_]", "_");
        st.execute("CREATE TEMPORARY TABLE " + temp + " AS " + sql);
        if (System.getenv("LL_TMP_DEBUG") != null) {
            try (var rs = st.executeQuery(
                    "SELECT COUNT(*) FROM " + temp)) {
                rs.next();
                System.err.println("[tdg] " + rs.getLong(1) + " rows <- "
                        + sql);
            }
        }
        temps.add(temp);
        return temp;
    }

    private static void record(Map<String, Fetched> fetched, String schema,
            String table, List<String> cols, String temp) {
        Fetched f = fetched.computeIfAbsent(schema + "\n" + table,
                k -> new Fetched(schema, table, cols, new ArrayList<>()));
        if (!f.cols().equals(cols)) {
            throw new IllegalStateException("testDataGen: table '" + table
                    + "' fetched twice with differing column sets "
                    + f.cols() + " vs " + cols);
        }
        f.temps().add(temp);
    }

    // ===== column demand (engine generateRelationColumnMap) =====

    /** PK + non-nullable + milestoning + scanned + join-condition columns,
     * name-sorted (the engine sorts fetch columns by name). */
    private static List<String> fetchCols(ModelContext ctx, Located loc,
            ScanRelations.Rel rel) {
        TreeSet<String> out = new TreeSet<>();
        java.util.Set<String> known = new LinkedHashSet<>();
        for (DatabaseDefinition.ColumnDefinition c : loc.def().columns()) {
            known.add(c.name());
            if (c.primaryKey() || c.notNull()) {
                out.add(c.name());
            }
        }
        var ms = loc.def().milestoning();
        if (ms != null) {
            if (ms.business() != null) {
                addIf(out, known, ms.business().from());
                addIf(out, known, ms.business().thru());
                addIf(out, known, ms.business().snapshotDate());
            }
            if (ms.processing() != null) {
                addIf(out, known, ms.processing().in());
                addIf(out, known, ms.processing().out());
                addIf(out, known, ms.processing().snapshotDate());
            }
        }
        for (String c : rel.cols()) {
            if (known.contains(c)) {
                out.add(c);
            }
        }
        // both sides of every child edge's join ride along (the child
        // fetch joins the parent TEMP table, so its condition columns
        // must have been fetched)
        for (ScanRelations.Rel child : rel.children()) {
            RelationalOperation op = child.cond() != null ? child.cond()
                    : findJoin(ctx, child.joinName(), child.db(),
                            rel.db()).operation();
            collectTableCols(op, rel.table(), out, known);
        }
        if (rel.joinName() != null || rel.cond() != null) {
            // this node's own inbound-join child-side columns
            RelationalOperation op = rel.cond() != null ? rel.cond()
                    : findJoin(ctx, rel.joinName(), rel.db(),
                            rel.db()).operation();
            collectTableCols(op, rel.table(), out, known);
        }
        return List.copyOf(out);
    }

    private static void addIf(TreeSet<String> out,
            java.util.Set<String> known, @com.legend.Nullable String col) {
        if (col != null && known.contains(col)) {
            out.add(col);
        }
    }

    private static void collectTableCols(RelationalOperation op,
            @com.legend.Nullable String table, TreeSet<String> out, java.util.Set<String> known) {
        switch (op) {
            case RelationalOperation.ColumnRef cr -> {
                if (bare(cr.table()).equals(table) && known.contains(cr.column())) {
                    out.add(cr.column());
                }
            }
            case RelationalOperation.TargetColumnRef tr -> {
                if (known.contains(tr.column())) {
                    out.add(tr.column());
                }
            }
            case RelationalOperation.Comparison c -> {
                collectTableCols(c.left(), table, out, known);
                collectTableCols(c.right(), table, out, known);
            }
            case RelationalOperation.BooleanOp b -> {
                collectTableCols(b.left(), table, out, known);
                collectTableCols(b.right(), table, out, known);
            }
            case RelationalOperation.Group g ->
                    collectTableCols(g.inner(), table, out, known);
            case RelationalOperation.IsNull n ->
                    collectTableCols(n.operand(), table, out, known);
            case RelationalOperation.IsNotNull n ->
                    collectTableCols(n.operand(), table, out, known);
            case RelationalOperation.FunctionCall fc -> {
                for (RelationalOperation a : fc.args()) {
                    collectTableCols(a, table, out, known);
                }
            }
            default -> {
            }
        }
    }

    // ===== join condition rendering =====

    private static String renderCondition(RelationalOperation op,
            @com.legend.Nullable String parentTable, String childTable, String childAlias,
            String joinName) {
        return switch (op) {
            case RelationalOperation.ColumnRef cr -> {
                String t = bare(cr.table());
                // self-join: the plain spelling is the PARENT side, the
                // {target} spelling the child (engine reprocessAliases)
                String a;
                if (t.equals(parentTable)) {
                    a = "main";
                } else if (t.equals(childTable)) {
                    a = q(childAlias);
                } else {
                    throw new NotImplementedException("testDataGen: join '"
                            + joinName + "' references table '" + t
                            + "' outside the parent/child pair — multi-table"
                            + " join conditions pending");
                }
                yield a + "." + q(cr.column());
            }
            case RelationalOperation.TargetColumnRef tr ->
                    q(childAlias) + "." + q(tr.column());
            case RelationalOperation.Literal l -> lit(l.value(), null);
            case RelationalOperation.Comparison c ->
                    renderCondition(c.left(), parentTable, childTable,
                            childAlias, joinName) + " " + c.op().symbol() + " "
                    + renderCondition(c.right(), parentTable, childTable,
                            childAlias, joinName);
            case RelationalOperation.BooleanOp b ->
                    renderCondition(b.left(), parentTable, childTable,
                            childAlias, joinName)
                    + (b.op() == com.legend.model.LogicalOp.AND ? " and "
                            : " or ")
                    + renderCondition(b.right(), parentTable, childTable,
                            childAlias, joinName);
            case RelationalOperation.Group g -> "("
                    + renderCondition(g.inner(), parentTable, childTable,
                            childAlias, joinName) + ")";
            case RelationalOperation.IsNull n ->
                    renderCondition(n.operand(), parentTable, childTable,
                            childAlias, joinName) + " is null";
            case RelationalOperation.IsNotNull n ->
                    renderCondition(n.operand(), parentTable, childTable,
                            childAlias, joinName) + " is not null";
            default -> throw new NotImplementedException("testDataGen: join '"
                    + joinName + "' condition node "
                    + op.getClass().getSimpleName() + " pending");
        };
    }

    /** A VIEW-backed relation swaps in its seed-table expansion (engine
     * generateTestDataForNestedViewTree): the seed fetches with the
     * view's internal join web as children; the view node's own join
     * condition and its class-join children translate view-side refs to
     * base columns. {@code parentDb} resolves the connecting join when
     * the view is a CHILD. */
    private static ScanRelations.Rel expandIfView(ModelContext ctx,
            ScanRelations.Rel r, @com.legend.Nullable String parentDb) {
        if (!ScanRelations.isView(ctx, r.db(), r.table())) {
            return r;
        }
        String vt = java.util.Objects.requireNonNull(r.table());
        ScanRelations.ViewExpansion ve =
                ScanRelations.viewExpansion(ctx, r.db(), r.table());
        ScanRelations.Rel base = ve.tree();
        RelationalOperation cond = null;
        if (r.cond() != null || r.joinName() != null) {
            RelationalOperation op = r.cond() != null ? r.cond()
                    : findJoin(ctx, r.joinName(), r.db(), parentDb)
                            .operation();
            cond = substituteViewRefs(op, vt, java.util.Objects.requireNonNull(
                    ve.mainTable()), ve.colToBase());
        }
        List<ScanRelations.Rel> kids = new ArrayList<>(base.children());
        for (ScanRelations.Rel c : r.children()) {
            RelationalOperation op = c.cond() != null ? c.cond()
                    : findJoin(ctx, c.joinName(), c.db(), r.db())
                            .operation();
            kids.add(new ScanRelations.Rel(c.db(), c.table(), c.joinName(),
                    substituteViewRefs(op, vt, java.util.Objects.requireNonNull(
                            ve.mainTable()), ve.colToBase()),
                    c.cols(), c.children()));
        }
        return new ScanRelations.Rel(base.db(), base.table(), r.joinName(),
                cond, base.cols(), kids);
    }

    /** Rewrite {@code op}'s refs to {@code viewName} as refs to the
     * view's SEED table with the column translated through the plain
     * column map (a view-side join condition against the fetch temp). */
    private static RelationalOperation substituteViewRefs(
            RelationalOperation op, String viewName, String mainTable,
            Map<String, String> colToBase) {
        return switch (op) {
            case RelationalOperation.ColumnRef r -> {
                if (!viewName.equals(r.table())) {
                    yield r;
                }
                String base = colToBase.get(r.column());
                if (base == null) {
                    throw new NotImplementedException("testDataGen: view"
                            + " join reads '" + r.column() + "' of '"
                            + viewName + "' which is not a plain"
                            + " column-mapped view column");
                }
                yield new RelationalOperation.ColumnRef(r.databaseName(),
                        mainTable, base);
            }
            case RelationalOperation.Comparison c ->
                    new RelationalOperation.Comparison(
                            substituteViewRefs(c.left(), viewName,
                                    mainTable, colToBase),
                            c.op(),
                            substituteViewRefs(c.right(), viewName,
                                    mainTable, colToBase));
            case RelationalOperation.BooleanOp b ->
                    new RelationalOperation.BooleanOp(
                            substituteViewRefs(b.left(), viewName,
                                    mainTable, colToBase),
                            b.op(),
                            substituteViewRefs(b.right(), viewName,
                                    mainTable, colToBase));
            case RelationalOperation.Group g ->
                    new RelationalOperation.Group(substituteViewRefs(
                            g.inner(), viewName, mainTable, colToBase));
            case RelationalOperation.IsNull n ->
                    new RelationalOperation.IsNull(substituteViewRefs(
                            n.operand(), viewName, mainTable, colToBase));
            case RelationalOperation.IsNotNull n ->
                    new RelationalOperation.IsNotNull(substituteViewRefs(
                            n.operand(), viewName, mainTable, colToBase));
            default -> op;
        };
    }

    // ===== lookups =====

    private static Located locate(ModelContext ctx, String dbFqn,
            @com.legend.Nullable String table0) {
        String table = java.util.Objects.requireNonNull(table0,
                "testDataGen: relation without a physical table");
        ArrayDeque<String> work = new ArrayDeque<>();
        java.util.Set<String> seen = new LinkedHashSet<>();
        work.add(dbFqn);
        while (!work.isEmpty()) {
            String fqn = work.poll();
            if (!seen.add(fqn)) {
                continue;
            }
            var dbo = ctx.findDatabase(fqn);
            if (dbo.isEmpty()) {
                continue;
            }
            DatabaseDefinition db = dbo.get();
            // NAMED schemas first: the top-level table list may flatten
            // schema-owned tables, and the CSV contract spells the
            // OWNING schema (testQualifier's productSchema)
            for (DatabaseDefinition.SchemaDefinition s : db.schemas()) {
                for (DatabaseDefinition.TableDefinition t : s.tables()) {
                    if (t.name().equals(table)) {
                        return new Located(s.name(), t);
                    }
                }
                for (DatabaseDefinition.ViewDefinition v : s.views()) {
                    if (v.name().equals(table)) {
                        throw new NotImplementedException("testDataGen:"
                                + " view-backed relation '" + table
                                + "' — view slice pending");
                    }
                }
            }
            for (DatabaseDefinition.TableDefinition t : db.tables()) {
                if (t.name().equals(table)) {
                    return new Located("default", t);
                }
            }
            for (DatabaseDefinition.ViewDefinition v : db.views()) {
                if (v.name().equals(table)) {
                    throw new NotImplementedException("testDataGen:"
                            + " view-backed relation '" + table
                            + "' — view slice pending");
                }
            }
            work.addAll(db.includes());
        }
        throw new NotImplementedException("testDataGen: table '" + table
                + "' not found in database '" + dbFqn + "'");
    }

    private static DatabaseDefinition.JoinDefinition findJoin(
            ModelContext ctx, @com.legend.Nullable String name0,
            @com.legend.Nullable String... dbFqns) {
        String name = java.util.Objects.requireNonNull(name0,
                "testDataGen: join edge without a join name");
        ArrayDeque<String> work = new ArrayDeque<>();
        for (String db : dbFqns) {
            if (db != null) {
                work.add(db);
            }
        }
        java.util.Set<String> seen = new LinkedHashSet<>();
        while (!work.isEmpty()) {
            String fqn = work.poll();
            if (fqn == null || !seen.add(fqn)) {
                continue;
            }
            var dbo = ctx.findDatabase(fqn);
            if (dbo.isEmpty()) {
                continue;
            }
            for (DatabaseDefinition.JoinDefinition j : dbo.get().joins()) {
                if (j.name().equals(name)) {
                    return j;
                }
            }
            work.addAll(dbo.get().includes());
        }
        throw new NotImplementedException("testDataGen: join '" + name
                + "' not found in " + List.of(dbFqns));
    }

    private static DatabaseDefinition.ColumnDefinition column(
            DatabaseDefinition.TableDefinition def, String name) {
        // quote-bearing demand names strip to the stored declaration
        if (name.length() > 1 && name.startsWith("\"")
                && name.endsWith("\"")) {
            name = name.substring(1, name.length() - 1);
        }
        for (DatabaseDefinition.ColumnDefinition c : def.columns()) {
            if (c.name().equals(name)) {
                return c;
            }
        }
        // unquoted SQL identifiers are case-insensitive (H2 uppercases
        // them in engine goldens)
        for (DatabaseDefinition.ColumnDefinition c : def.columns()) {
            if (c.name().equalsIgnoreCase(name)) {
                return c;
            }
        }
        throw new NotImplementedException("testDataGen: column '" + name
                + "' not on table '" + def.name() + "'");
    }

    private static String headerCase(String col) {
        return col.matches("[A-Za-z_][A-Za-z0-9_]*")
                ? col.toUpperCase(java.util.Locale.ROOT) : col;
    }

    private static List<String> headerKey(String[] cols) {
        return java.util.Arrays.stream(cols)
                .map(c -> c.toUpperCase(java.util.Locale.ROOT)).toList();
    }

    // ===== CSV =====

    private static String renderCsv(Statement st,
            Map<String, Fetched> fetched, boolean hashStrings)
            throws SQLException {
        StringBuilder out = new StringBuilder();
        for (Fetched f : fetched.values()) {
            if (out.length() > 0) {
                out.append("-----\n");
            }
            // dedup ACROSS fetches of one table = DB-side UNION
            String union = String.join(" union ", f.temps().stream()
                    .map(t -> "select * from " + t).toList());
            if (f.temps().size() == 1) {
                union = "select distinct * from " + f.temps().get(0);
            }
            // engine parity: H2 UPPERCASES unquoted result labels; plain
            // identifiers print uppercase, exotic names ride as-is.
            // The CSV column order sorts on the DISPLAY (uppercased)
            // name — the fetch/plan order stays the stored-name sort
            // (bitemporal plan text pins ID < PLACE < from_z while the
            // CSV goldens pin B_PERSONID < ID)
            List<String> cs = new ArrayList<>(f.cols());
            cs.sort(java.util.Comparator.comparing(
                    TestDataGenerator::headerCase));
            out.append(f.schema()).append('\n').append(f.table())
                    .append('\n').append(String.join(",", cs.stream()
                            .map(TestDataGenerator::headerCase).toList()))
                    .append('\n');
            try (ResultSet rs = st.executeQuery("select "
                    + String.join(", ", cs.stream().map(
                            TestDataGenerator::q).toList())
                    + " from (" + union + ") order by all")) {
                var md = rs.getMetaData();
                int n = md.getColumnCount();
                while (rs.next()) {
                    StringBuilder row = new StringBuilder();
                    for (int i = 1; i <= n; i++) {
                        if (i > 1) {
                            row.append(',');
                        }
                        String v = rs.getString(i);
                        // hashStrings applies to STRING-typed values only
                        // (engine hashStrings(): s:String -> hash, Any
                        // passes through) — column type decides
                        if (v != null && hashStrings && isTextType(
                                md.getColumnType(i))) {
                            v = hashString(v);
                        }
                        row.append(v == null ? "---null---"
                                : v.replace('\'', ' ').replace(',', ';')
                                        .replace('\n', ' '));
                    }
                    out.append(row).append('\n');
                }
            }
        }
        if (out.length() > 0) {
            out.append("-----\n");
        }
        return out.toString();
    }

    private static boolean isTextType(int sqlType) {
        return sqlType == java.sql.Types.VARCHAR
                || sqlType == java.sql.Types.CHAR
                || sqlType == java.sql.Types.LONGVARCHAR;
    }

    /** The engine's {@code hashString} (testDataGeneration.pure:656):
     * the first 5 hex chars of SHA-256, TILED to the original string's
     * length (whole repeats + the LAST len%5 chars). */
    static String hashString(String s) {
        java.security.MessageDigest md;
        try {
            md = java.security.MessageDigest.getInstance("SHA-256");
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
        byte[] d = md.digest(s.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        StringBuilder hex = new StringBuilder();
        for (byte b : d) {
            hex.append(String.format("%02x", b));
        }
        String h = hex.substring(0, 5);
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < s.length() / 5; i++) {
            out.append(h);
        }
        return out.append(h.substring(5 - s.length() % 5)).toString();
    }

    // ===== assertTestData (engine: setUpDataSQLs + assertSameElements) =====

    /**
     * Compare two testDataGen CSV strings as TYPED ROW SETS: both sides
     * load into temp tables typed from the store model and diff with
     * {@code EXCEPT} both ways — the database normalizes types, dates and
     * numeric spellings, exactly like the engine's route through
     * setUpDataSQLs. Returns null when equal, else a failure message.
     */
    public static @com.legend.Nullable String compareCsv(ModelContext ctx,
            String dbFqn,
            String expected, String actual, Connection conn)
            throws SQLException {
        Map<String, String[][]> exp = parseBlocks(expected);
        Map<String, String[][]> act = parseBlocks(actual);
        if (!exp.keySet().equals(act.keySet())) {
            return "assertTestData: table sets differ — expected "
                    + exp.keySet() + ", got " + act.keySet();
        }
        List<String> temps = new ArrayList<>();
        try (Statement st = conn.createStatement()) {
            for (String key : exp.keySet()) {
                String[][] e = java.util.Objects.requireNonNull(exp.get(key));
                String[][] a = java.util.Objects.requireNonNull(act.get(key),
                        "assertTestData: actual data lacks " + key);
                String table = key.substring(key.indexOf('\n') + 1);
                if (!headerKey(e[0]).equals(headerKey(a[0]))) {
                    return "assertTestData: columns of '" + table
                            + "' differ — expected "
                            + java.util.Arrays.toString(e[0]) + ", got "
                            + java.util.Arrays.toString(a[0]);
                }
                Located loc = locate(ctx, dbFqn, table);
                String te = loadSide(st, loc, e, temps);
                String ta = loadSide(st, loc, a, temps);
                try (ResultSet rs = st.executeQuery(
                        "select count(*) from ((select * from " + te
                        + " except select * from " + ta
                        + ") union all (select * from " + ta
                        + " except select * from " + te + "))")) {
                    rs.next();
                    if (rs.getLong(1) != 0) {
                        return "assertTestData: rows of '" + table
                                + "' differ (" + rs.getLong(1)
                                + " asymmetric rows)\nexpected:\n"
                                + side(e) + "got:\n" + side(a);
                    }
                }
            }
            return null;
        } finally {
            dropTemps(conn, temps);
        }
    }

    private static String side(String[][] block) {
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i < block.length; i++) {
            sb.append(String.join(",", block[i])).append('\n');
        }
        return sb.toString();
    }

    /** One side's rows into a typed temp table; values ride as QUOTED
     * literals and the DATABASE casts them to the model's column types
     * (uniform policy — no host-side type dispatch). */
    private static String loadSide(Statement st, Located loc,
            String[][] block, List<String> temps) throws SQLException {
        String temp = "tdgcmp_" + temps.size();
        StringBuilder ddl = new StringBuilder("CREATE TEMPORARY TABLE ")
                .append(temp).append(" (");
        for (int c = 0; c < block[0].length; c++) {
            if (c > 0) {
                ddl.append(", ");
            }
            ddl.append(q(block[0][c])).append(' ')
                    .append(duckType(column(loc.def(), block[0][c])
                            .dataType()));
        }
        st.execute(ddl.append(")").toString());
        temps.add(temp);
        for (int i = 1; i < block.length; i++) {
            StringBuilder ins = new StringBuilder("INSERT INTO ")
                    .append(temp).append(" VALUES (");
            for (int c = 0; c < block[0].length; c++) {
                if (c > 0) {
                    ins.append(", ");
                }
                String tok = c < block[i].length ? block[i][c] : "";
                ins.append(tok.isEmpty() || tok.equals("---null---")
                        ? "NULL"
                        : "'" + tok.replace("'", "''") + "'");
            }
            st.execute(ins.append(")").toString());
        }
        return temp;
    }

    /** Parse {@code schema\ntable\ncols\nrows...} blocks separated by
     * {@code -----} lines into key (schema\ntable) -> [header, rows...]. */
    private static Map<String, String[][]> parseBlocks(String csv) {
        Map<String, String[][]> out = new LinkedHashMap<>();
        List<String> cur = new ArrayList<>();
        for (String line : csv.split("\n", -1)) {
            if (line.strip().matches("-{3,}")) {
                flushBlock(cur, out);
                cur.clear();
            } else {
                cur.add(line);
            }
        }
        flushBlock(cur, out);
        return out;
    }

    private static void flushBlock(List<String> lines,
            Map<String, String[][]> out) {
        while (!lines.isEmpty() && lines.get(lines.size() - 1).isBlank()) {
            lines.remove(lines.size() - 1);
        }
        while (!lines.isEmpty() && lines.get(0).isBlank()) {
            lines.remove(0);
        }
        if (lines.size() < 3) {
            if (!lines.isEmpty()) {
                throw new NotImplementedException(
                        "testDataGen: malformed CSV block " + lines);
            }
            return;
        }
        String key = lines.get(0).strip() + "\n" + lines.get(1).strip();
        String[][] block = new String[lines.size() - 2][];
        for (int i = 2; i < lines.size(); i++) {
            block[i - 2] = lines.get(i).split(",", -1);
            for (int c = 0; c < block[i - 2].length; c++) {
                block[i - 2][c] = block[i - 2][c].strip();
            }
        }
        out.put(key, block);
    }

    // ===== rendering primitives =====

    private static String duckType(RelationalDataType t) {
        return switch (t) {
            case RelationalDataType.Bool ignored -> "BOOLEAN";
            case RelationalDataType.Bit ignored -> "BOOLEAN";
            case RelationalDataType.BigInt ignored -> "BIGINT";
            case RelationalDataType.SmallInt ignored -> "BIGINT";
            case RelationalDataType.TinyInt ignored -> "BIGINT";
            case RelationalDataType.Integer_ ignored -> "BIGINT";
            case RelationalDataType.Float_ ignored -> "DOUBLE";
            case RelationalDataType.Double_ ignored -> "DOUBLE";
            case RelationalDataType.Real ignored -> "DOUBLE";
            case RelationalDataType.Timestamp ignored -> "TIMESTAMP";
            case RelationalDataType.Date_ ignored -> "DATE";
            case RelationalDataType.Decimal d ->
                    "DECIMAL(" + d.precision() + ", " + d.scale() + ")";
            case RelationalDataType.Numeric n ->
                    "DECIMAL(" + n.precision() + ", " + n.scale() + ")";
            // Text-shaped seeds — EXPLICIT per variant so a new variant is
            // a compile error, never a silent VARCHAR (T3.1).
            case RelationalDataType.Varchar ignored -> "VARCHAR";
            case RelationalDataType.Char_ ignored -> "VARCHAR";
            case RelationalDataType.Binary ignored -> "VARCHAR";
            case RelationalDataType.Varbinary ignored -> "VARCHAR";
            case RelationalDataType.Distinct ignored -> "VARCHAR";
            case RelationalDataType.Other ignored -> "VARCHAR";
            case RelationalDataType.SemiStructured ignored -> "VARCHAR";
            case RelationalDataType.Array ignored -> "VARCHAR";
            case RelationalDataType.Object_ ignored -> "VARCHAR";
        };
    }

    private static String lit(@com.legend.Nullable Object v,
            @com.legend.Nullable DatabaseDefinition.ColumnDefinition col) {
        if (v == null) {
            return "NULL";
        }
        if (v instanceof String s) {
            String quoted = "'" + s.replace("'", "''") + "'";
            if (col != null) {
                RelationalDataType t = col.dataType();
                if (t instanceof RelationalDataType.Date_) {
                    return "DATE " + quoted;
                }
                if (t instanceof RelationalDataType.Timestamp) {
                    return "TIMESTAMP " + quoted;
                }
            }
            return quoted;
        }
        if (v instanceof Boolean b) {
            return b ? "TRUE" : "FALSE";
        }
        if (v instanceof Number n) {
            return n.toString();
        }
        throw new NotImplementedException(
                "testDataGen: row identifier value " + v.getClass());
    }

    /** The engine's generateSeedDataString: execute the demanded
     * columns (pks first) of each tree table and format every row as
     * createRowIdentifier SOURCE CODE — column names spelled by their
     * QUOTE-BEARING identity. */
    public static String seedDataString(ModelContext ctx,
            LambdaFunction resolvedQuery, String mappingFqn,
            Connection conn) throws SQLException {
        List<ScanRelations.Rel> roots =
                ScanRelations.relTree(ctx, resolvedQuery, mappingFqn);
        roots = roots.stream().map(r -> expandIfView(ctx, r, null))
                .toList();
        StringBuilder out = new StringBuilder();
        try (Statement st = conn.createStatement()) {
            for (ScanRelations.Rel r : roots) {
                Located loc = locate(ctx, r.db(), r.table());
                List<String> cols = new ArrayList<>();
                for (DatabaseDefinition.ColumnDefinition c
                        : loc.def().columns()) {
                    if (c.primaryKey()) {
                        cols.add(c.name());
                    }
                }
                for (String c : r.cols()) {
                    String bare = c.length() > 1 && c.startsWith("\"")
                            && c.endsWith("\"")
                            ? c.substring(1, c.length() - 1) : c;
                    if (!cols.contains(bare)) {
                        cols.add(bare);
                    }
                }
                List<String> spelled = cols.stream()
                        .map(c -> column(loc.def(), c).quoted()
                                ? "\"" + c + "\"" : c)
                        .toList();
                String sql = "select " + String.join(", ",
                        cols.stream().map(TestDataGenerator::q).toList())
                        + " from " + qualify(loc.schema(),
                                java.util.Objects.requireNonNull(r.table()));
                out.append('\n')
                        .append("meta::relational::testDataGeneration::"
                                + "createTableRowIdentifiers(")
                        .append(r.db()).append(", '").append(loc.schema())
                        .append("', '").append(r.table()).append("', [\n");
                List<String> rows = new ArrayList<>();
                try (ResultSet rs = st.executeQuery(sql)) {
                    int n = cols.size();
                    while (rs.next()) {
                        List<String> vals = new ArrayList<>();
                        for (int i = 1; i <= n; i++) {
                            Object v = rs.getObject(i);
                            vals.add(v instanceof String str
                                    ? "'" + str + "'" : String.valueOf(v));
                        }
                        rows.add("       meta::relational::"
                                + "testDataGeneration::createRowIdentifier(["
                                + spelled.stream().map(c2 -> "'" + c2 + "'")
                                        .collect(java.util.stream.Collectors
                                                .joining(","))
                                + "], ["
                                + String.join(",", vals) + "])");
                    }
                }
                out.append(String.join(",\n", rows)).append("\n  ])\n");
            }
        }
        return out.toString();
    }

    // ===== the tdg PLAN PRINTER (planTestDataGeneration text) =====

    /** The engine's planTestDataGeneration plan text: MultiResultSequence
     * of per-fetch Allocations (res_cN names in tree order), each a
     * Relational node whose SQL is the ENGINE-H2 spelling of the fetch —
     * parents referenced as {@code ${res_cN}} placeholders. Pure text,
     * no execution. */
    public static String planText(ModelContext ctx,
            LambdaFunction resolvedQuery, String mappingFqn,
            List<TableRowIds> rowIds, MilestoningDates dates) {
        List<ScanRelations.Rel> roots =
                ScanRelations.relTree(ctx, resolvedQuery, mappingFqn);
        roots = roots.stream().map(r -> expandIfView(ctx, r, null))
                .toList();
        Map<String, List<String>> colMap = new LinkedHashMap<>();
        for (ScanRelations.Rel r : roots) {
            collectColMap(ctx, r, colMap, null);
        }
        StringBuilder kids = new StringBuilder();
        int ri = 0;
        for (ScanRelations.Rel r : roots) {
            planNode(ctx, r, null, "res_c" + ri++, rowIds, dates, colMap,
                    kids);
        }
        return "MultiResultSequence\n(\n"
                + "  type = meta::pure::metamodel::type::Any\n"
                + "  (\n"
                + com.legend.plan.PlanText.indent(kids.toString(), "    ")
                + "  )\n)\n";
    }

    private static void planNode(ModelContext ctx, ScanRelations.Rel rel,
            ScanRelations.@com.legend.Nullable Rel parent, String res,
            List<TableRowIds> rowIds,
            @com.legend.Nullable MilestoningDates dates, Map<String, List<String>> colMap,
            StringBuilder out) {
        Located loc = locate(ctx, rel.db(), rel.table());
        String tbl = java.util.Objects.requireNonNull(rel.table());
        List<String> cols = java.util.Objects.requireNonNull(
                colMap.get(loc.schema() + "\n" + tbl),
                "no column map for " + tbl);
        String relType = relationType(rel, loc, cols);
        String sql = parent == null
                ? planRootSql(loc, rel, cols, rowIds, dates)
                : planChildSql(ctx, loc, parent, rel, cols, res, dates);
        String inner = "Relational\n(\n"
                + "  type = " + relType + "\n"
                + "  resultSizeRange = *\n"
                + "  resultColumns = [" + cols.stream()
                        .map(c -> "(\"" + c + "\", "
                                + com.legend.plan.PlanText.spell(
                                        column(loc.def(), c).dataType())
                                + ")")
                        .collect(java.util.stream.Collectors
                                .joining(", ")) + "]\n"
                + "  sql = " + sql + "\n"
                + "  connection = TestDatabaseConnection(type = \"H2\")\n"
                + ")\n";
        out.append(com.legend.plan.PlanText.allocation(res,
                "  type = " + relType + "\n  resultSizeRange = *\n",
                inner));
        int ci = 0;
        for (ScanRelations.Rel child : rel.children()) {
            planNode(ctx, child, rel, res + "_c" + ci++, rowIds, dates,
                    colMap, out);
        }
    }

    private static String relationType(ScanRelations.Rel rel, Located loc,
            List<String> cols) {
        return "Relation[name=" + rel.table() + ", type=TABLE, schema="
                + loc.schema() + ", database=" + rel.db() + ", columns=["
                + cols.stream().map(c -> "(\"" + c + "\","
                        + com.legend.plan.PlanText.spell(
                                column(loc.def(), c).dataType()) + ")")
                        .collect(java.util.stream.Collectors.joining(", "))
                + "]]";
    }

    private static String planRootSql(Located loc, ScanRelations.Rel rel,
            List<String> cols, List<TableRowIds> rowIds,
            @com.legend.Nullable MilestoningDates dates) {
        TableRowIds ids = null;
        for (TableRowIds t : rowIds) {
            if (t.table().equals(rel.table())
                    && t.schema().equals(loc.schema())) {
                ids = t;
            }
        }
        if (ids == null) {
            throw new NotImplementedException("testDataGen plan: no row"
                    + " identifiers for root '" + rel.table() + "'");
        }
        // engine combineFilters explicitly reverses its operand list
        // ($ns->reverse(), pureToSqlQuery.pure:389 — audit 25 verified);
        // a single-condition row spells bare, a multi-condition row
        // parenthesizes its and-group
        List<String> rows = new ArrayList<>();
        for (int i = ids.ids().size() - 1; i >= 0; i--) {
            RowId id = ids.ids().get(i);
            List<String> conds = new ArrayList<>();
            for (int c = 0; c < id.cols().size(); c++) {
                conds.add("\"root\"." + id.cols().get(c) + " = "
                        + planLit(id.values().get(c)));
            }
            rows.add(conds.size() == 1 ? conds.get(0)
                    : "(" + String.join(" and ", conds) + ")");
        }
        String pk = String.join(" or ", rows);
        if (rows.size() > 1) {
            pk = "(" + pk + ")";
        }
        String mf = planMilestone(loc.def(), "\"root\"", dates);
        String where = mf == null ? pk : mf + " and " + pk;
        return "select top 20 " + cols.stream()
                .map(c -> "\"root\"." + c + " as \"" + c + "\"")
                .collect(java.util.stream.Collectors.joining(", "))
                + " from " + qualify(loc.schema(),
                        java.util.Objects.requireNonNull(rel.table()))
                + " as \"root\" where " + where;
    }

    private static String planChildSql(ModelContext ctx, Located loc,
            ScanRelations.Rel parent, ScanRelations.Rel child,
            List<String> cols, String res, @com.legend.Nullable MilestoningDates dates) {
        String parentRes = res.substring(0, res.lastIndexOf("_c"));
        // the engine's per-query alias-group index: each child fetch SQL
        // joins exactly ONE table by construction, so its group index is
        // always 0 (audit 25 — a multi-join child would need the full
        // group counter)
        String ct = java.util.Objects.requireNonNull(child.table());
        String alias = ct.toLowerCase(java.util.Locale.ROOT) + "_0";
        RelationalOperation op = child.cond() != null ? child.cond()
                : findJoin(ctx, child.joinName(), child.db(), parent.db())
                        .operation();
        String mf = planMilestone(loc.def(), "\"" + alias + "\"", dates);
        return "select top 20 " + cols.stream()
                .map(c -> "\"" + alias + "\"." + c + " as \"" + c + "\"")
                .collect(java.util.stream.Collectors.joining(", "))
                + " from (select * from (${" + parentRes
                + "}) as \"root\") as \"root\" inner join "
                + qualify(loc.schema(), child.table()) + " as \"" + alias
                + "\" on (" + planCond(op, parent.table(), child.table(),
                        alias) + ")"
                + (mf == null ? "" : " where " + mf);
    }

    /** Engine-text join condition: parent refs read the ALLOCATION's
     * aliased result columns ({@code "root"."col"}), child refs the
     * joined table ({@code "alias".col}). */
    private static String planCond(RelationalOperation op,
            @com.legend.Nullable String parentTable, String childTable,
            String childAlias) {
        return switch (op) {
            case RelationalOperation.ColumnRef r ->
                    bareTable(r.table()).equals(parentTable)
                            ? "\"root\".\"" + r.column() + "\""
                            : "\"" + childAlias + "\"." + r.column();
            case RelationalOperation.Comparison c ->
                    planCond(c.left(), parentTable, childTable, childAlias)
                            + " " + c.op().symbol() + " "
                            + planCond(c.right(), parentTable, childTable,
                                    childAlias);
            case RelationalOperation.BooleanOp b ->
                    planCond(b.left(), parentTable, childTable, childAlias)
                    + (b.op() == com.legend.model.LogicalOp.AND ? " and "
                            : " or ")
                    + planCond(b.right(), parentTable, childTable,
                            childAlias);
            case RelationalOperation.Group g -> "(" + planCond(g.inner(),
                    parentTable, childTable, childAlias) + ")";
            case RelationalOperation.IsNull n -> planCond(n.operand(),
                    parentTable, childTable, childAlias) + " is null";
            case RelationalOperation.IsNotNull n -> planCond(n.operand(),
                    parentTable, childTable, childAlias) + " is not null";
            default -> throw new NotImplementedException(
                    "testDataGen plan: condition node "
                    + op.getClass().getSimpleName() + " pending");
        };
    }

    private static String bareTable(String t) {
        int dot = t.lastIndexOf('.');
        return dot < 0 ? t : t.substring(dot + 1);
    }

    /** Engine-text milestoning filter — {@code DATE'...'} spelling. */
    private static @com.legend.Nullable String planMilestone(
            DatabaseDefinition.TableDefinition def, String alias,
            @com.legend.Nullable MilestoningDates d) {
        var ms = def.milestoning();
        if (ms == null || d == null) {
            return null;
        }
        // engine bitemporal order: PROCESSING dimension first
        List<String> parts = new ArrayList<>();
        if (ms.processing() != null) {
            var pr = ms.processing();
            if (pr.snapshotDate() != null) {
                parts.add(alias + "." + pr.snapshotDate() + " = DATE'"
                        + requireDate(d.processing(), def, "processingDate")
                        + "'");
            } else {
                String pd = requireDate(d.processing(), def,
                        "processingDate");
                parts.add(alias + "." + pr.in() + " <= DATE'" + pd + "'");
                parts.add(alias + "." + pr.out()
                        + (pr.outIsInclusive() ? " >= DATE'" : " > DATE'")
                        + pd + "'");
            }
        }
        if (ms.business() != null) {
            var b = ms.business();
            if (b.snapshotDate() != null) {
                parts.add(alias + "." + b.snapshotDate() + " = DATE'"
                        + requireDate(d.snapshot(), def, "snapshotDate")
                        + "'");
            } else {
                String bd = requireDate(d.business(), def, "businessDate");
                parts.add(alias + "." + b.from() + " <= DATE'" + bd + "'");
                parts.add(alias + "." + b.thru()
                        + (b.thruIsInclusive() ? " >= DATE'" : " > DATE'")
                        + bd + "'");
            }
        }
        return parts.isEmpty() ? null : String.join(" and ", parts);
    }

    private static String planLit(Object v) {
        return v instanceof String str ? "'" + str + "'" : String.valueOf(v);
    }

    private static String qualify(String schema, String table) {
        return schema == null || schema.isEmpty()
                || "default".equals(schema) ? table : schema + "." + table;
    }

    private static String q(String name) {
        return "\"" + name + "\"";
    }

    private static String bare(String table) {
        int dot = table.lastIndexOf('.');
        return dot < 0 ? table : table.substring(dot + 1);
    }

    private static void dropTemps(Connection conn, List<String> temps) {
        try (Statement st = conn.createStatement()) {
            for (String t : temps) {
                st.execute("DROP TABLE IF EXISTS " + t);
            }
        } catch (SQLException ignored) {
            // cleanup only — temp tables die with the connection anyway
        }
    }
}
