// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend.testdatagen;

import com.legend.compiler.element.ModelContext;
import com.legend.error.NotImplementedException;
import com.legend.lineage.ScanRelations;
import com.legend.model.DatabaseDefinition;
import com.legend.model.RelationalDataType;
import com.legend.model.RelationalOperation;
import com.legend.protocol.spec.LambdaFunction;
import com.legend.sql.NameQuoting;
import com.legend.sql.OutputCol;
import com.legend.sql.SqlDdl;
import com.legend.sql.SqlDml;
import com.legend.sql.SqlExpr;
import com.legend.sql.SqlFn;
import com.legend.sql.SqlQuery;
import com.legend.sql.SqlSelect;
import com.legend.sql.SqlSource;
import com.legend.sql.SqlTyping;
import com.legend.sql.dialect.SqlDialect;

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
 * rows land in a session TEMP table ({@code CREATE TEMP TABLE ... AS
 * SELECT}, the engine's own temp-table discipline), child fetches JOIN
 * the parent's temp table in the database, and per-table dedup is a
 * DB-side {@code UNION}. Row values only cross into Java as display
 * strings for the CSV text. (The CSV compare, never called since
 * assertTestData folds through setUpDataSQLs, was deleted 2026-10-16.)
 *
 * <p>Walls are LOUD ({@link NotImplementedException} naming the
 * pending shape) — but the previously-listed examples (view-backed
 * relations, hashStrings, temporal milestoning dates) are IMPLEMENTED
 * now; hashStrings and the CSV scrub are spelled IN SQL (A5/A6 — the
 * Java SHA-256 over rs.getString was the tenet's oldest open breach).
 * Only genuinely unhandled shapes throw today.
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
    public record MilestoningDates(@com.legend.base.Nullable String business,
            @com.legend.base.Nullable String processing,
            @com.legend.base.Nullable String snapshot) {
    }

    public record Result(List<String> sqls, @com.legend.base.Nullable String dataCsvString,
            @com.legend.base.Nullable List<String[]> tables,
            @com.legend.base.Nullable List<Fetch> fetches) {
        public Result(List<String> sqls, String dataCsvString) {
            this(sqls, dataCsvString, null, null);
        }

        public Result(List<String> sqls, @com.legend.base.Nullable String dataCsvString,
                @com.legend.base.Nullable List<String[]> tables) {
            this(sqls, dataCsvString, tables, null);
        }
    }

    /** Per-fetch transcript entry, aligned 1:1 with {@link Result#sqls}
     * — the engine's own return shape (generateTestData pairs every SQL
     * with its ResultSet). {@code rows} are the fetch's LIVE-SESSION
     * results, read from the materialized temp before it drops; {@code
     * parentIndex} is the sqls index of the fetch whose rows seeded this
     * fetch's parent temp (-1 for roots/id-fetches/view fetches);
     * {@code table} is the fetched relation's name — the engine spells
     * a chained hop's parent temp {@code 'testDataGen_Temp_' +
     * table(parentIndex)}. */
    public record Fetch(int parentIndex, String table, List<String> columns,
            List<List<Object>> rows) {
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


    /** The VIEW's fetch SQL, rendered by the driver's COMPILER (the engine's
     *  own shape, testDataGeneration.pure 377–412: the view planned by the SQL
     *  generator, the fetched base tables substituted by their temps
     *  ({@code fixTables}), printed): {@code tableToTemp} maps each fetched
     *  table's name (bare and {@code SCHEMA.NAME}) to its temp. The
     *  generator never builds view SQL itself. */
    public interface ViewSql {
        String render(String dbFqn, String viewName, Map<String, String> tableToTemp);
    }

    /** The generator's run on the session {@code conn}, whose database {@code dialect} writes every statement: the
     *  fetches into its temp tables, their read-backs and the CSV envelope (the dialect program's Phase 3,
     *  2026-10-16: until then this class wrote one SQL text it hoped both H2 and DuckDB would read). */
    public static Result generate(ModelContext ctx,
            LambdaFunction resolvedQuery, String mappingFqn,
            List<TableRowIds> rowIds, @com.legend.base.Nullable MilestoningDates dates,
            boolean hashStrings, Connection conn, SqlDialect dialect, ViewSql viewSql) throws SQLException {
        List<ScanRelations.Rel> roots =
                ScanRelations.relTree(ctx, resolvedQuery, mappingFqn);
        // engine generateRelationColumnMap: column demand merges PER
        // TABLE across ALL tree nodes (a self-join's two fetches of one
        // table share one column set). fetchRoot expands views itself
        // (it must KEEP the view identity to emit the view fetch).
        Map<String, List<String>> colMap = new LinkedHashMap<>();
        for (ScanRelations.Rel r : roots) {
            collectColMap(ctx, r, colMap, null);
        }
        List<String> sqls = new ArrayList<>();
        // the per-fetch transcript, 1:1 with sqls (engine parity: the
        // engine returns every fetch's ResultSet beside its SQL)
        List<Fetch> fetches = new ArrayList<>();
        // tableKey (schema\ntable) -> fetch temps, in first-fetch order
        Map<String, Fetched> fetched = new LinkedHashMap<>();
        // per-call temp-name counter (temps drop in finally, so names
        // never collide across sequential invocations)
        List<String> temps = new ArrayList<>();
        try (Statement statement = conn.createStatement()) {
            Session st = new Session(statement, dialect);
            for (ScanRelations.Rel r : roots) {
                fetchRoot(ctx, r, rowIds, st, sqls, fetches, fetched,
                        temps, colMap, dates, viewSql);
            }
            String csv = csvEnvelope(st, fetched, hashStrings);
            return new Result(List.copyOf(sqls), csv, null,
                    List.copyOf(fetches));
        } finally {
            dropTemps(conn, dialect, temps);
        }
    }

    private static void collectColMap(ModelContext ctx,
            ScanRelations.Rel rel, Map<String, List<String>> colMap,
            @com.legend.base.Nullable String parentDb) {
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

    private record Fetched(String schema, String table, DatabaseDefinition.TableDefinition def, List<String> cols,
            List<String> temps) {
    }

    private record Located(String schema,
            DatabaseDefinition.TableDefinition def) {
    }

    // ===== tree walk =====

    private static void fetchRoot(ModelContext ctx, ScanRelations.Rel rel,
            List<TableRowIds> rowIds, Session st, List<String> sqls,
            List<Fetch> fetches,
            Map<String, Fetched> fetched, List<String> temps,
            Map<String, List<String>> colMap, @com.legend.base.Nullable MilestoningDates dates,
            ViewSql viewSql)
            throws SQLException {
        // a VIEW-backed root generates for its UNDERLYING tree (engine
        // generateTestDataForNestedViewTree): the view's seed table is
        // the row-identifier target, its join web fetches as children,
        // and the group closes with the VIEW's OWN fetch over the temps
        // (one per view layer, inner-first — §8.2)
        String viewDb = rel.db();
        String viewName = ScanRelations.isView(ctx, rel.db(), rel.table())
                ? rel.table() : null;
        rel = expandIfView(ctx, rel, null);
        Located loc = locate(ctx, rel.db(), rel.table());
        String tbl = java.util.Objects.requireNonNull(rel.table(), "rel.table()");
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
        SqlSelect fetch = idFetch(ids, loc, tbl, cols, dates);
        String temp = materialize(st, fetch, tbl, temps);
        int idx = sqls.size();
        sqls.add(st.dialect().renderInline(fetch));
        fetches.add(new Fetch(-1, tbl, cols, tempRows(st, temp)));
        record(fetched, loc, tbl, cols, temp);
        for (ScanRelations.Rel child : rel.children()) {
            fetchChild(ctx, rel, temp, idx, child, st, sqls, fetches,
                    fetched, temps, colMap, rowIds, dates, viewSql);
        }
        if (viewName != null) {
            emitViewFetches(ctx, viewDb, viewName, st, sqls, fetches,
                    fetched, viewSql);
        }
    }

    /** The fetch of a table's rows by their identifiers: its demanded columns, under {@code "root"}, the rows whose
     *  identifiers match ({@code (a and b) or (…)}) and are live on the milestoning dates, at most 20. */
    private static SqlSelect idFetch(TableRowIds ids, Located loc, String table, List<String> cols,
            @com.legend.base.Nullable MilestoningDates dates) {
        SqlExpr where = null;
        for (RowId id : ids.ids()) {
            SqlExpr one = null;
            for (int i = 0; i < id.cols().size(); i++) {
                one = and(one, SqlExpr.Call.of(SqlFn.EQUAL, stored(ROOT, loc.def(), id.cols().get(i)),
                        literal(id.values().get(i), column(loc.def(), id.cols().get(i)))));
            }
            SqlExpr group = new SqlExpr.Group(java.util.Objects.requireNonNull(one, "a row identifier with no column"));
            where = where == null ? group : SqlExpr.Call.of(SqlFn.OR, where, group);
        }
        SqlExpr mf = milestoningFilter(loc.def(), ROOT, dates);
        SqlExpr rows = java.util.Objects.requireNonNull(where, "no row identifiers");
        return fetch(cols, loc.def(), ROOT, storeTable(loc, table, ROOT), mf == null ? rows
                : SqlExpr.Call.of(SqlFn.AND, new SqlExpr.Group(rows), mf));
    }

    /** {@code select <alias>.c, ... from <from> [where <where>] limit 20}: the demanded columns of the table
     *  {@code alias} reads, unaliased, so the database names its temp's columns as it names a read column (H2 upper
     *  case, DuckDB as stored), as when this was text. */
    private static SqlSelect fetch(List<String> cols, DatabaseDefinition.TableDefinition def, String alias,
            SqlSource from, @com.legend.base.Nullable SqlExpr where) {
        List<SqlSelect.Projection> ps = new ArrayList<>();
        for (String c : cols) {
            ps.add(new SqlSelect.Projection(stored(alias, def, c), null, null));
        }
        return new SqlSelect(ps, false, from, where, List.of(), null, null, List.of(), 20L, null, List.of());
    }

    private static void fetchChild(ModelContext ctx, ScanRelations.Rel parent,
            String parentTemp, int parentIdx, ScanRelations.Rel child,
            Session st,
            List<String> sqls, List<Fetch> fetches,
            Map<String, Fetched> fetched,
            List<String> temps, Map<String, List<String>> colMap,
            List<TableRowIds> rowIds, @com.legend.base.Nullable MilestoningDates dates,
            ViewSql viewSql)
            throws SQLException {
        String viewDb = child.db();
        String viewName = ScanRelations.isView(ctx, child.db(), child.table())
                ? child.table() : null;
        child = expandIfView(ctx, child, parent.db());
        Located loc = locate(ctx, child.db(), child.table());
        String ct = java.util.Objects.requireNonNull(child.table(), "child.table()");
        List<String> cols = java.util.Objects.requireNonNull(
                colMap.get(loc.schema() + "\n" + ct),
                "no column map for " + ct);
        // a VIEW child's SEED with EXPLICIT row identifiers fetches BY
        // ITS IDS, not the parent join (engine
        // generateTestDataStartingFromNode's isView arm: tablePk
        // non-empty short-circuits the parent-derived ids —
        // testViewEmbeddedInChainedJoin pins personTable 1,2,3,5 while
        // the join reaches only 1,2). PLAIN table children stay
        // join-derived (a self-join's manager fetch must follow the
        // join even when the table's ROOT ids are configured).
        for (TableRowIds t : viewName == null ? List.<TableRowIds>of()
                : rowIds) {
            if (t.table().equals(ct) && t.schema().equals(loc.schema())) {
                SqlSelect idFetch = idFetch(t, loc, ct, cols, dates);
                String idTemp = materialize(st, idFetch, ct, temps);
                int idIdx = sqls.size();
                sqls.add(st.dialect().renderInline(idFetch));
                fetches.add(new Fetch(-1, ct, cols, tempRows(st, idTemp)));
                record(fetched, loc, ct, cols, idTemp);
                for (ScanRelations.Rel sub : child.children()) {
                    fetchChild(ctx, child, idTemp, idIdx, sub, st, sqls,
                            fetches, fetched, temps, colMap, rowIds, dates, viewSql);
                }
                if (viewName != null) {
                    emitViewFetches(ctx, viewDb, viewName, st, sqls,
                            fetches, fetched, viewSql);
                }
                return;
            }
        }
        RelationalOperation op = child.cond() != null ? child.cond()
                : findJoin(ctx, child.joinName(), child.db(),
                        parent.db()).operation();
        String alias = ct.equals(parent.table()) ? "t_" + ct : ct;
        SqlExpr cond = condition(op, parent.table(), locate(ctx, parent.db(), parent.table()).def(),
                child.table(), alias, loc.def(), String.valueOf(child.joinName()));
        SqlSelect fetch = fetch(cols, loc.def(), alias, new SqlSource.Join(tempTable(parentTemp, PARENT),
                storeTable(loc, ct, alias), SqlSource.Join.Kind.INNER, cond),
                milestoningFilter(loc.def(), alias, dates));
        String temp = materialize(st, fetch, child.table(), temps);
        int idx = sqls.size();
        sqls.add(st.dialect().renderInline(fetch));
        fetches.add(new Fetch(parentIdx, ct, cols, tempRows(st, temp)));
        record(fetched, loc, child.table(), cols, temp);
        for (ScanRelations.Rel sub : child.children()) {
            fetchChild(ctx, child, temp, idx, sub, st, sqls, fetches,
                    fetched, temps, colMap, rowIds, dates, viewSql);
        }
        if (viewName != null) {
            emitViewFetches(ctx, viewDb, viewName, st, sqls, fetches,
                    fetched, viewSql);
        }
    }

    // ===== the view fetch (engine generateTestDataForNestedViewTree) =====

    /** After a view group's base fetches, the engine emits the VIEW's own
     * query re-pointed at the fetched temp tables (fixTables oldToNew)
     * and executes it — one per view layer, INNER-FIRST for view-on-view
     * stacks. The view result pairs with a View (not a Table), so it
     * adds no CSV block; the SQL and its execution are the contract. */
    private static void emitViewFetches(ModelContext ctx, String db,
            String viewName, Session st, List<String> sqls,
            List<Fetch> fetches,
            Map<String, Fetched> fetched, ViewSql viewSql) throws SQLException {
        List<String> chain = ScanRelations
                .viewExpansion(ctx, db, viewName).viewChain();
        // the fetched tables by every spelling the lowering may use for a
        // source name (bare, SCHEMA.NAME) -> the LAST temp of each
        Map<String, String> tableToTemp = new LinkedHashMap<>();
        for (Fetched f : fetched.values()) {
            if (f.temps().isEmpty()) {
                continue;
            }
            String temp = f.temps().get(f.temps().size() - 1);
            tableToTemp.put(f.table(), temp);
            tableToTemp.put(f.schema() + "." + f.table(), temp);
        }
        for (int i = chain.size() - 1; i >= 0; i--) {
            String sql = viewSql.render(db, chain.get(i), tableToTemp);
            sqls.add(sql);
            List<String> viewCols = new ArrayList<>();
            List<List<Object>> viewRows = captureRows(st, sql, viewCols);
            fetches.add(new Fetch(-1, chain.get(i), viewCols, viewRows));
        }
    }

    /** The ONE whole-temp read (csvEnvelope's union arms and the transcript capture share it — single owner). */
    private static SqlSelect selectAll(String temp) {
        return SqlSelect.starOf(tempTable(temp, temp));
    }

    /** The materialized temp's LIVE contents — the fetch's transcript
     * rows (engine parity: generateTestData pairs each SQL with its
     * ResultSet). */
    private static List<List<Object>> tempRows(Session st, String temp)
            throws SQLException {
        return captureRows(st, st.render(selectAll(temp)), null);
    }

    private static List<List<Object>> captureRows(Session st, String sql,
            @com.legend.base.Nullable List<String> colsOut) throws SQLException {
        List<List<Object>> rows = new ArrayList<>();
        com.legend.exec.StatementOrigin.count(com.legend.exec.StatementOrigin.TDG);
        try (java.sql.ResultSet rs = st.statement().executeQuery(sql)) {
            int n = rs.getMetaData().getColumnCount();
            if (colsOut != null) {
                for (int i = 1; i <= n; i++) {
                    colsOut.add(rs.getMetaData().getColumnLabel(i));
                }
            }
            while (rs.next()) {
                List<Object> row = new ArrayList<>(n);
                for (int i = 1; i <= n; i++) {
                    row.add(rs.getObject(i));
                }
                rows.add(row);
            }
        }
        return rows;
    }

    /** The engine's getMilestoningFilter: forced temporal dates filter a
     * MILESTONED table's fetch (business/processing from-thru ranges,
     * snapshot equality), the table read through {@code alias}. A
     * milestoned table without its date is the engine's own assert — a
     * loud wall. */
    private static @com.legend.base.Nullable SqlExpr milestoningFilter(
            DatabaseDefinition.TableDefinition def, String alias,
            @com.legend.base.Nullable MilestoningDates d) {
        var ms = def.milestoning();
        if (ms == null || d == null) {
            return null;
        }
        SqlExpr parts = null;
        if (ms.business() != null) {
            var b = ms.business();
            if (b.snapshotDate() != null) {
                parts = and(parts, onDate(alias, def, b.snapshotDate(), SqlFn.EQUAL,
                        requireDate(d.snapshot(), def, "snapshotDate")));
            } else {
                String bd = requireDate(d.business(), def, "businessDate");
                parts = and(parts, onDate(alias, def, java.util.Objects.requireNonNull(b.from(),
                        "milestoning business block without BUS_FROM"), SqlFn.LESS_EQUAL, bd));
                parts = and(parts, onDate(alias, def, java.util.Objects.requireNonNull(b.thru(),
                        "milestoning business block without BUS_THRU"),
                        b.thruIsInclusive() ? SqlFn.GREATER_EQUAL : SqlFn.GREATER, bd));
            }
        }
        if (ms.processing() != null) {
            var pr = ms.processing();
            if (pr.snapshotDate() != null) {
                parts = and(parts, onDate(alias, def, pr.snapshotDate(), SqlFn.EQUAL,
                        requireDate(d.processing(), def, "processingDate")));
            } else {
                String pd = requireDate(d.processing(), def, "processingDate");
                parts = and(parts, onDate(alias, def, java.util.Objects.requireNonNull(pr.in(),
                        "milestoning processing block without PROCESSING_IN"), SqlFn.LESS_EQUAL, pd));
                parts = and(parts, onDate(alias, def, java.util.Objects.requireNonNull(pr.out(),
                        "milestoning processing block without PROCESSING_OUT"),
                        pr.outIsInclusive() ? SqlFn.GREATER_EQUAL : SqlFn.GREATER, pd));
            }
        }
        return parts;
    }

    /** {@code alias.column <op> DATE 'date'}. */
    private static SqlExpr onDate(String alias, DatabaseDefinition.TableDefinition def, String column, SqlFn op,
            String date) {
        return SqlExpr.Call.of(op, stored(alias, def, column), new SqlExpr.DateLit(date));
    }

    private static String requireDate(@com.legend.base.Nullable String v,
            DatabaseDefinition.TableDefinition def, String name) {
        if (v == null) {
            throw new NotImplementedException("testDataGen: table '"
                    + def.name() + "' is milestoned but '" + name
                    + "' was not passed in TemporalMilestoningDates");
        }
        return v;
    }

    /** The fetch's rows into a temp table of the session's own, named {@code tdg_<n>_<table>} (the referee knows a
     *  chained fetch by that prefix). */
    private static String materialize(Session st, SqlSelect fetch,
            String table, List<String> temps) throws SQLException {
        String temp = "tdg_" + temps.size() + "_"
                + table.replaceAll("[^A-Za-z0-9_]", "_");
        com.legend.exec.StatementOrigin.count(com.legend.exec.StatementOrigin.TDG);
        st.statement().execute(st.dialect().render(new SqlDml.CreateTempTableAs(temp, fetch)));
        temps.add(temp);
        return temp;
    }

    private static void record(Map<String, Fetched> fetched, Located loc,
            String table, List<String> cols, String temp) {
        Fetched f = fetched.computeIfAbsent(loc.schema() + "\n" + table,
                k -> new Fetched(loc.schema(), table, loc.def(), cols, new ArrayList<>()));
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
            java.util.Set<String> known, @com.legend.base.Nullable String col) {
        if (col != null && known.contains(col)) {
            out.add(col);
        }
    }

    private static void collectTableCols(RelationalOperation op,
            @com.legend.base.Nullable String table, TreeSet<String> out, java.util.Set<String> known) {
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

    // ===== the join condition =====

    /** A child edge's join condition over the parent's temp table ({@code main}) and the child table
     *  ({@code childAlias}): a self-join's plain spelling is the PARENT side, its {@code {target}} spelling the
     *  child (engine reprocessAliases). */
    private static SqlExpr condition(RelationalOperation op,
            @com.legend.base.Nullable String parentTable, DatabaseDefinition.TableDefinition parentDef,
            String childTable, String childAlias,
            DatabaseDefinition.TableDefinition childDef, String joinName) {
        return switch (op) {
            case RelationalOperation.ColumnRef cr -> {
                String t = bare(cr.table());
                if (t.equals(parentTable)) {
                    yield SqlExpr.Column.derived(PARENT, tempColumn(parentDef, cr.column()));
                }
                if (t.equals(childTable)) {
                    yield stored(childAlias, childDef, cr.column());
                }
                throw new NotImplementedException("testDataGen: join '"
                        + joinName + "' references table '" + t
                        + "' outside the parent/child pair — multi-table"
                        + " join conditions pending");
            }
            case RelationalOperation.TargetColumnRef tr -> stored(childAlias, childDef, tr.column());
            case RelationalOperation.Literal l -> literal(l.value(), null);
            case RelationalOperation.Comparison c -> SqlExpr.Call.of(switch (c.op()) {
                case EQ -> SqlFn.EQUAL;
                case NEQ -> SqlFn.NOT_EQUAL;
                case LT -> SqlFn.LESS;
                case LTE -> SqlFn.LESS_EQUAL;
                case GT -> SqlFn.GREATER;
                case GTE -> SqlFn.GREATER_EQUAL;
            }, condition(c.left(), parentTable, parentDef, childTable, childAlias, childDef, joinName),
                    condition(c.right(), parentTable, parentDef, childTable, childAlias, childDef, joinName));
            case RelationalOperation.BooleanOp b -> SqlExpr.Call.of(
                    b.op() == com.legend.model.LogicalOp.AND ? SqlFn.AND : SqlFn.OR,
                    condition(b.left(), parentTable, parentDef, childTable, childAlias, childDef, joinName),
                    condition(b.right(), parentTable, parentDef, childTable, childAlias, childDef, joinName));
            case RelationalOperation.Group g -> new SqlExpr.Group(
                    condition(g.inner(), parentTable, parentDef, childTable, childAlias, childDef, joinName));
            case RelationalOperation.IsNull n -> SqlExpr.Call.of(SqlFn.IS_NULL,
                    condition(n.operand(), parentTable, parentDef, childTable, childAlias, childDef, joinName));
            case RelationalOperation.IsNotNull n -> SqlExpr.Call.of(SqlFn.IS_NOT_NULL,
                    condition(n.operand(), parentTable, parentDef, childTable, childAlias, childDef, joinName));
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
            ScanRelations.Rel r, @com.legend.base.Nullable String parentDb) {
        if (!ScanRelations.isView(ctx, r.db(), r.table())) {
            return r;
        }
        String vt = java.util.Objects.requireNonNull(r.table(), "r.table()");
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
            default -> op.mapChildren(x -> substituteViewRefs(x, viewName,
                    mainTable, colToBase));
        };
    }

    // ===== lookups =====

    private static Located locate(ModelContext ctx, String dbFqn,
            @com.legend.base.Nullable String table0) {
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
            ModelContext ctx, @com.legend.base.Nullable String name0,
            @com.legend.base.Nullable String... dbFqns) {
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

    // ===== CSV =====

    private static String csvEnvelope(Session st,
            Map<String, Fetched> fetched, boolean hashStrings)
            throws SQLException {
        StringBuilder out = new StringBuilder();
        for (Fetched f : fetched.values()) {
            if (out.length() > 0) {
                out.append("-----\n");
            }
            // dedup ACROSS fetches of one table = DB-side UNION
            SqlQuery union = f.temps().size() == 1 ? selectAll(f.temps().get(0)).withDistinct()
                    : new com.legend.sql.SqlUnion(f.temps().stream().<SqlQuery>map(TestDataGenerator::selectAll)
                            .toList(), false, List.of());
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
            // A5/A6: hashing AND the CSV scrub are SQL — Java only
            // displays. Text columns learn from the union's SCHEMA (a
            // LIMIT-0 metadata read, not value sniffing).
            java.util.Set<String> textCols = new java.util.HashSet<>();
            com.legend.exec.StatementOrigin.count(com.legend.exec.StatementOrigin.TDG);
            try (ResultSet meta = st.statement().executeQuery(st.render(
                    SqlSelect.starOf(new SqlSource.Subselect(union, "_u", null)).withLimit(0L)))) {
                var mmd = meta.getMetaData();
                for (int i = 1; i <= mmd.getColumnCount(); i++) {
                    if (isTextType(mmd.getColumnType(i))) {
                        // without case: H2 names a temp's column upper case, DuckDB as stored
                        textCols.add(mmd.getColumnName(i).toUpperCase(java.util.Locale.ROOT));
                    }
                }
            }
            List<SqlSelect.Projection> projs = new ArrayList<>(cs.size());
            for (String c : cs) {
                String tc = tempColumn(f.def(), c);
                SqlExpr cell = SqlExpr.Column.derived(null, tc);
                if (!textCols.contains(tc.toUpperCase(java.util.Locale.ROOT))) {
                    // non-string kinds pass through (engine
                    // hashStrings(): s:String -> hash, Any unchanged);
                    // their display forms never carry scrub characters
                    projs.add(new SqlSelect.Projection(cell, null, null));
                } else if (hashStrings) {
                    projs.add(new SqlSelect.Projection(hashString(cell), tc, null));
                } else {
                    // the CSV scrub (quote/comma/newline) in SQL — the
                    // old Java replace chain was A6's lossy scrub at
                    // the wrong layer
                    projs.add(new SqlSelect.Projection(replace(replace(replace(cell,
                            SqlExpr.Call.of(SqlFn.CHR, new SqlExpr.IntLit(39)), " "), new SqlExpr.StringLit(","), ";"),
                            SqlExpr.Call.of(SqlFn.CHR, new SqlExpr.IntLit(10)), " "), tc, null));
                }
            }
            // E5: the ROW TEXT is SQL — cells cast to their display
            // text, the '---null---' token and the comma joins all in
            // the projection; Java appends DB-produced lines only. The
            // OUTER query orders by the projected display columns
            // (left-to-right — the old ordinal semantics; the sort keys
            // need not be projected).
            List<SqlExpr> parts = new ArrayList<>();
            for (String c : cs) {
                if (!parts.isEmpty()) {
                    parts.add(new SqlExpr.StringLit(","));
                }
                parts.add(SqlExpr.Call.of(SqlFn.COALESCE, new SqlExpr.Cast(
                        SqlExpr.Column.derived(CSV_ROW, tempColumn(f.def(), c)), com.legend.sql.SqlType.Scalar.VARCHAR),
                        new SqlExpr.StringLit("---null---")));
            }
            SqlSelect cells = new SqlSelect(projs, false, new SqlSource.Subselect(union, "_u", null), null, List.of(),
                    null, null, List.of(), null, null, List.of());
            SqlSelect lines = new SqlSelect(List.of(new SqlSelect.Projection(parts.size() == 1 ? parts.get(0)
                    : new SqlExpr.Call(SqlFn.CONCAT, parts, SqlTyping.UNKNOWN), "_csv_line", null)), false,
                    new SqlSource.Subselect(cells, CSV_ROW, null), null, List.of(), null, null,
                    cs.stream().map(c -> SqlSelect.SortKey.asc(SqlExpr.Column.derived(CSV_ROW, tempColumn(f.def(), c))))
                            .toList(), null, null, List.of());
            com.legend.exec.StatementOrigin.count(com.legend.exec.StatementOrigin.TDG);
            com.legend.exec.StatementOrigin.count(com.legend.exec.StatementOrigin.TDG);
            try (ResultSet rs = st.statement().executeQuery(st.render(lines))) {
                while (rs.next()) {
                    out.append(rs.getString(1)).append('\n');
                }
            }
        }
        if (out.length() > 0) {
            out.append("-----\n");
        }
        return out.toString();
    }

    /** The engine's hashString (testDataGeneration.pure:656) IN SQL: the first 5 hex characters of the string's
     *  SHA-256, tiled to its length (whole repeats, then the LAST {@code length % 5} characters). Hex needs no
     *  scrub. A NULL string stays NULL. */
    private static SqlExpr hashString(SqlExpr s) {
        SqlExpr h = SqlExpr.Call.of(SqlFn.SUBSTRING, SqlExpr.Call.of(SqlFn.SHA256, s), new SqlExpr.IntLit(1),
                new SqlExpr.IntLit(5));
        SqlExpr length = SqlExpr.Call.of(SqlFn.LENGTH, s);
        SqlExpr tiled = SqlExpr.Call.of(SqlFn.CONCAT,
                SqlExpr.Call.of(SqlFn.REPEAT_STR, h, SqlExpr.Call.of(SqlFn.INT_DIVIDE, length, new SqlExpr.IntLit(5))),
                SqlExpr.Call.of(SqlFn.RIGHT, h, SqlExpr.Call.of(SqlFn.MOD, length, new SqlExpr.IntLit(5))));
        return new SqlExpr.Case(List.of(new SqlExpr.Case.When(SqlExpr.Call.of(SqlFn.IS_NULL, s), new SqlExpr.NullLit())),
                tiled);
    }

    private static SqlExpr replace(SqlExpr s, SqlExpr from, String to) {
        return SqlExpr.Call.of(SqlFn.REPLACE, s, from, new SqlExpr.StringLit(to));
    }

    private static boolean isTextType(int sqlType) {
        return sqlType == java.sql.Types.VARCHAR
                || sqlType == java.sql.Types.CHAR
                || sqlType == java.sql.Types.LONGVARCHAR;
    }

    // ===== rendering primitives =====

    /** A row identifier's or a join's value as a literal: a string of a DATE or TIMESTAMP column that type's
     *  literal, a whole number an integer, any other number its exact value. */
    private static SqlExpr literal(@com.legend.base.Nullable Object v,
            @com.legend.base.Nullable DatabaseDefinition.ColumnDefinition col) {
        if (v == null) {
            return new SqlExpr.NullLit();
        }
        if (v instanceof String s) {
            if (col != null) {
                RelationalDataType t = col.dataType();
                if (t instanceof RelationalDataType.Date_) {
                    return new SqlExpr.DateLit(s);
                }
                if (t instanceof RelationalDataType.Timestamp) {
                    return new SqlExpr.TimestampLit(s);
                }
            }
            return new SqlExpr.StringLit(s);
        }
        if (v instanceof Boolean b) {
            return new SqlExpr.BoolLit(b);
        }
        if (v instanceof Long || v instanceof Integer || v instanceof Short || v instanceof Byte) {
            return new SqlExpr.IntLit(((Number) v).longValue());
        }
        if (v instanceof Number n) {
            return new SqlExpr.DecimalLit(new java.math.BigDecimal(n.toString()));
        }
        throw new NotImplementedException(
                "testDataGen: row identifier value " + v.getClass());
    }

    /** The engine's {@code toRepresentation()} (platform
     * toRepresentation.pure): row-identifier cells are PURE SOURCE, so
     * strings take BACKSLASH escapes, dates the {@code %} form,
     * decimals the {@code D} suffix. (Audit 2026-08-18 finding E: the
     * old inline speller had NO escaping and JDBC-default spellings;
     * the fix is this spec port, not the SQL {@code lit()} speller —
     * SQL doubles quotes, Pure backslash-escapes them.)
     * Package-private for the Tier-1 regression pin (PureReprTest). */
    static String pureRepr(@com.legend.base.Nullable Object v) {
        // Phase 2a: toRepresentation has ONE owner (PureAsserts.repr —
        // this port generalized there); only the row-identifier NULL
        // contract stays local (a pk cell must have produced a value)
        if (v == null) {
            throw new NotImplementedException(
                    "testDataGen: NULL row-identifier cell — a primary"
                    + " key produced no value");
        }
        // this generator reads its OWN ResultSet (registered JDBC seam)
        // — driver temporals convert to THE wire carrier here, at the
        // seam, before any shared formatter sees them (D-arc rule)
        v = switch (v) {
            case java.sql.Date d -> com.legend.values.PureDateLiteral
                    .fromLocalDate(d.toLocalDate());
            case java.sql.Timestamp ts -> com.legend.values.PureDateLiteral
                    .fromLocalDateTime(ts.toLocalDateTime());
            case java.time.LocalDate ld ->
                    com.legend.values.PureDateLiteral.fromLocalDate(ld);
            case java.time.LocalDateTime ldt ->
                    com.legend.values.PureDateLiteral.fromLocalDateTime(ldt);
            default -> v;
        };
        return com.legend.exec.PureAsserts.repr(v);
    }

    /** The engine's generateSeedDataString: execute the demanded
     * columns (pks first) of each tree table and format every row as
     * createRowIdentifier SOURCE CODE — column names spelled by their
     * QUOTE-BEARING identity. */
    public static String seedDataString(ModelContext ctx,
            LambdaFunction resolvedQuery, String mappingFqn,
            Connection conn, SqlDialect dialect) throws SQLException {
        List<ScanRelations.Rel> roots =
                ScanRelations.relTree(ctx, resolvedQuery, mappingFqn);
        roots = roots.stream().map(r -> expandIfView(ctx, r, null))
                .toList();
        StringBuilder out = new StringBuilder();
        try (Statement statement = conn.createStatement()) {
            Session st = new Session(statement, dialect);
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
                List<SqlSelect.Projection> ps = new ArrayList<>();
                for (String c : cols) {
                    ps.add(new SqlSelect.Projection(stored(ROOT, loc.def(), c), null, null));
                }
                String sql = st.render(new SqlSelect(ps, false, storeTable(loc,
                        java.util.Objects.requireNonNull(r.table(), "r.table()"), ROOT), null, List.of(), null, null,
                        List.of(), null, null, List.of()));
                out.append('\n')
                        .append("meta::relational::testDataGeneration::"
                                + "createTableRowIdentifiers(")
                        .append(r.db()).append(", '").append(loc.schema())
                        .append("', '").append(r.table()).append("', [\n");
                List<String> rows = new ArrayList<>();
                for (List<Object> raw : captureRows(st, sql, null)) {
                    List<String> vals = new ArrayList<>();
                    for (Object v : raw) {
                        vals.add(pureRepr(v));
                    }
                    rows.add("       meta::relational::"
                            + "testDataGeneration::createRowIdentifier(["
                            + spelled.stream().map(c2 -> "'" + c2 + "'")
                                    .collect(java.util.stream.Collectors
                                            .joining(","))
                            + "], ["
                            + String.join(",", vals) + "])");
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
            ScanRelations.@com.legend.base.Nullable Rel parent, String res,
            List<TableRowIds> rowIds,
            @com.legend.base.Nullable MilestoningDates dates, Map<String, List<String>> colMap,
            StringBuilder out) {
        Located loc = locate(ctx, rel.db(), rel.table());
        String tbl = java.util.Objects.requireNonNull(rel.table(), "rel.table()");
        List<String> cols = java.util.Objects.requireNonNull(
                colMap.get(loc.schema() + "\n" + tbl),
                "no column map for " + tbl);
        String relType = relationType(rel, loc, cols);
        if (parent == null && rootIds(loc, rel, rowIds) == null) {
            // the engine's plan for a ROOT without row identifiers is an
            // Error node carrying the top-5 sample query
            // (testDataGeneration.pure:511, planTestDataGeneration's
            // getRowIdentifiersForRoot assert): message + the sampled
            // Relational, no Allocation
            // the sample selects the PRIMARY KEY columns only
            List<String> pkCols = loc.def().columns().stream()
                    .filter(c -> c.primaryKey()).map(c -> c.name()).toList();
            out.append(planRootErrorNode(loc, rel, tbl, pkCols,
                    relationType(rel, loc, pkCols)));
            return;
        }
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

    private static @com.legend.base.Nullable TableRowIds rootIds(Located loc,
            ScanRelations.Rel rel, List<TableRowIds> rowIds) {
        TableRowIds ids = null;
        for (TableRowIds t : rowIds) {
            if (t.table().equals(rel.table())
                    && t.schema().equals(loc.schema())) {
                ids = t;
            }
        }
        return ids;
    }

    /** The engine's Error node for a root without row identifiers:
     * the assert message (primary key columns as name:TYPE) over the
     * sampled {@code select top 5} of the root table. */
    private static String planRootErrorNode(Located loc,
            ScanRelations.Rel rel, String tbl, List<String> cols,
            String relType) {
        String alias = tbl.toLowerCase(java.util.Locale.ROOT) + "_0";
        String pk = loc.def().columns().stream()
                .filter(c -> c.primaryKey())
                .map(c -> c.name() + ":" + com.legend.plan.PlanText.spell(
                        c.dataType()))
                .collect(java.util.stream.Collectors.joining(","));
        String qualified = "default".equals(loc.schema()) ? tbl
                : loc.schema() + "." + tbl;
        String sql = sampleSelect(5, cols, alias, loc, tbl) ;
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
        return "Error\n(\n"
                + "  type = meta::pure::metamodel::type::Any\n"
                + "  message = Row Identifers should be provided for the root"
                + " table: " + rel.db() + "." + qualified + " [" + pk
                + "]\\n\n"
                + "  (\n" + com.legend.plan.PlanText.indent(inner, "    ")
                + "  )\n)\n";
    }

    private static String planRootSql(Located loc, ScanRelations.Rel rel,
            List<String> cols, List<TableRowIds> rowIds,
            @com.legend.base.Nullable MilestoningDates dates) {
        TableRowIds ids = rootIds(loc, rel, rowIds);
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
        return sampleSelect(20, cols, "root", loc,
                java.util.Objects.requireNonNull(rel.table(), "rel.table()"))
                + " where " + where;
    }

    /** The engine's sampled root select: {@code select top N "alias".c as
     * "c", ... from <table> as "alias"} (the plan's root fetch and the
     * no-seed error node's sample). */
    private static String sampleSelect(int top, List<String> cols,
            String alias, Located loc, String tbl) {
        return "select top " + top + " " + cols.stream()
                .map(c -> "\"" + alias + "\"." + c + " as \"" + c + "\"")
                .collect(java.util.stream.Collectors.joining(", "))
                + " from " + qualify(loc.schema(), tbl) + " as \"" + alias + "\"";
    }

    private static String planChildSql(ModelContext ctx, Located loc,
            ScanRelations.Rel parent, ScanRelations.Rel child,
            List<String> cols, String res, @com.legend.base.Nullable MilestoningDates dates) {
        String parentRes = res.substring(0, res.lastIndexOf("_c"));
        // the engine's per-query alias-group index: each child fetch SQL
        // joins exactly ONE table by construction, so its group index is
        // always 0 (audit 25 — a multi-join child would need the full
        // group counter)
        String ct = java.util.Objects.requireNonNull(child.table(), "child.table()");
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
            @com.legend.base.Nullable String parentTable, String childTable,
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
    private static @com.legend.base.Nullable String planMilestone(
            DatabaseDefinition.TableDefinition def, String alias,
            @com.legend.base.Nullable MilestoningDates d) {
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

    /** The alias a fetch reads its seed table through, and the one a child fetch reads its parent's temp
     *  through. */
    private static final String ROOT = "root";

    private static final String PARENT = "main";

    /** The alias the CSV envelope's row text reads its cells through. */
    private static final String CSV_ROW = "_r";

    /** The statement every query of a run goes through, and the session's dialect, which writes them. */
    private record Session(Statement statement, SqlDialect dialect) {
        String render(SqlQuery q) {
            return dialect.render(q);
        }
    }

    /** A temp's column, as a query over the temp names it: a column its store declared quoted by its name, any
     *  other upper case. The database named it as it names a read column: H2 upper case (unquoted), DuckDB as stored,
     *  and DuckDB compares names without case, so both read it so (the dialect program's Phase 3, 2026-10-16: a
     *  declared-quoted column was upper-cased too while this was text, which H2 cannot find). */
    private static String tempColumn(DatabaseDefinition.TableDefinition def, String c) {
        DatabaseDefinition.ColumnDefinition cd = column(def, c);
        return cd.quoted() ? cd.name() : cd.name().toUpperCase(java.util.Locale.ROOT);
    }

    /** A column of the store table {@code def}, read through {@code alias}, spelled as its DDL declared it (bare, or
     *  quoted with its case kept). */
    private static SqlExpr stored(@com.legend.base.Nullable String alias, DatabaseDefinition.TableDefinition def,
            String c) {
        DatabaseDefinition.ColumnDefinition cd = column(def, c);
        return SqlExpr.Column.physical(alias, cd.name(), cd.quoted());
    }

    /** The store table a fetch reads, by its schema's name and its own, quoted as its store declared them. */
    private static SqlSource.Table storeTable(Located loc, String table, String alias) {
        String name = loc.schema() == null || loc.schema().isEmpty() || "default".equals(loc.schema()) ? table
                : loc.schema() + "." + table;
        return new SqlSource.Table(name, alias, List.of(), false, Map.of(),
                new NameQuoting(loc.def().schemaQuoted(), loc.def().quoted()));
    }

    /** A temp table of the run's own. */
    private static SqlSource.Table tempTable(String temp, String alias) {
        return new SqlSource.Table(temp, alias, List.of(), false, Map.of(), NameQuoting.BARE);
    }

    private static SqlExpr and(@com.legend.base.Nullable SqlExpr left, SqlExpr right) {
        return left == null ? right : SqlExpr.Call.of(SqlFn.AND, left, right);
    }

    /** The plan text's {@code schema.table} (the engine's own spelling). */
    private static String qualify(String schema, String table) {
        return schema == null || schema.isEmpty()
                || "default".equals(schema) ? table : schema + "." + table;
    }

    private static String bare(String table) {
        int dot = table.lastIndexOf('.');
        return dot < 0 ? table : table.substring(dot + 1);
    }

    private static void dropTemps(Connection conn, SqlDialect dialect, List<String> temps) {
        try (Statement st = conn.createStatement()) {
            for (String t : temps) {
                com.legend.exec.StatementOrigin.count(com.legend.exec.StatementOrigin.TDG);
                st.execute(dialect.render(new SqlDdl.DropTable(null, t, NameQuoting.BARE)));
            }
        } catch (SQLException ignored) {
            // cleanup only — temp tables die with the connection anyway
        }
    }
}
