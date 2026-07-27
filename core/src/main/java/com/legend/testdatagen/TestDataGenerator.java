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

    public record Result(List<String> sqls, String dataCsvString) {
    }

    public static Result generate(ModelContext ctx,
            LambdaFunction resolvedQuery, String mappingFqn,
            List<TableRowIds> rowIds, Connection conn) throws SQLException {
        List<ScanRelations.Rel> roots =
                ScanRelations.relTree(ctx, resolvedQuery, mappingFqn);
        // engine generateRelationColumnMap: column demand merges PER
        // TABLE across ALL tree nodes (a self-join's two fetches of one
        // table share one column set)
        Map<String, List<String>> colMap = new LinkedHashMap<>();
        for (ScanRelations.Rel r : roots) {
            collectColMap(ctx, r, colMap);
        }
        List<String> sqls = new ArrayList<>();
        // tableKey (schema\ntable) -> fetch temps, in first-fetch order
        Map<String, Fetched> fetched = new LinkedHashMap<>();
        // per-call temp-name counter (temps drop in finally, so names
        // never collide across sequential invocations)
        List<String> temps = new ArrayList<>();
        try (Statement st = conn.createStatement()) {
            for (ScanRelations.Rel r : roots) {
                fetchRoot(ctx, r, rowIds, st, sqls, fetched, temps, colMap);
            }
            String csv = renderCsv(st, fetched);
            return new Result(List.copyOf(sqls), csv);
        } finally {
            dropTemps(conn, temps);
        }
    }

    private static void collectColMap(ModelContext ctx,
            ScanRelations.Rel rel, Map<String, List<String>> colMap) {
        Located loc = locate(ctx, rel.db(), rel.table());
        String key = loc.schema() + "\n" + rel.table();
        // engine sortBy(name) is effectively case-insensitive (goldens:
        // B_PERSONID < ID, FROM_Z < ID < IN_Z)
        TreeSet<String> merged =
                new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        merged.addAll(colMap.getOrDefault(key, List.of()));
        merged.addAll(fetchCols(ctx, loc, rel));
        colMap.put(key, List.copyOf(merged));
        for (ScanRelations.Rel child : rel.children()) {
            collectColMap(ctx, child, colMap);
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
            Map<String, List<String>> colMap) throws SQLException {
        Located loc = locate(ctx, rel.db(), rel.table());
        List<String> cols = colMap.get(loc.schema() + "\n" + rel.table());
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
        String sql = "select " + String.join(", ",
                cols.stream().map(c -> "\"root\"." + q(c)).toList())
                + " from " + qualify(loc.schema(), rel.table())
                + " as \"root\" where " + where + " limit 20";
        String temp = materialize(st, sql, rel.table(), temps);
        sqls.add(sql);
        record(fetched, loc.schema(), rel.table(), cols, temp);
        for (ScanRelations.Rel child : rel.children()) {
            fetchChild(ctx, rel, temp, child, st, sqls, fetched, temps,
                    colMap);
        }
    }

    private static void fetchChild(ModelContext ctx, ScanRelations.Rel parent,
            String parentTemp, ScanRelations.Rel child, Statement st,
            List<String> sqls, Map<String, Fetched> fetched,
            List<String> temps, Map<String, List<String>> colMap)
            throws SQLException {
        Located loc = locate(ctx, child.db(), child.table());
        List<String> cols = colMap.get(loc.schema() + "\n" + child.table());
        RelationalOperation op = child.cond() != null ? child.cond()
                : findJoin(ctx, child.joinName(), child.db(),
                        parent.db()).operation();
        String alias = child.table().equals(parent.table())
                ? "t_" + child.table() : child.table();
        String cond = renderCondition(op, parent.table(),
                child.table(), alias, String.valueOf(child.joinName()));
        String sql = "select " + String.join(", ",
                cols.stream().map(c -> q(alias) + "." + q(c)).toList())
                + " from " + parentTemp + " as main inner join "
                + qualify(loc.schema(), child.table()) + " as " + q(alias)
                + " on " + cond + " limit 20";
        String temp = materialize(st, sql, child.table(), temps);
        sqls.add(sql);
        record(fetched, loc.schema(), child.table(), cols, temp);
        for (ScanRelations.Rel sub : child.children()) {
            fetchChild(ctx, child, temp, sub, st, sqls, fetched, temps,
                    colMap);
        }
    }

    private static String materialize(Statement st, String sql,
            String table, List<String> temps) throws SQLException {
        String temp = "tdg_" + temps.size() + "_"
                + table.replaceAll("[^A-Za-z0-9_]", "_");
        st.execute("CREATE TEMPORARY TABLE " + temp + " AS " + sql);
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
            java.util.Set<String> known, String col) {
        if (col != null && known.contains(col)) {
            out.add(col);
        }
    }

    private static void collectTableCols(RelationalOperation op,
            String table, TreeSet<String> out, java.util.Set<String> known) {
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
            String parentTable, String childTable, String childAlias,
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

    // ===== lookups =====

    private static Located locate(ModelContext ctx, String dbFqn,
            String table) {
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
            ModelContext ctx, String name, String... dbFqns) {
        ArrayDeque<String> work = new ArrayDeque<>(List.of(dbFqns));
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
            Map<String, Fetched> fetched) throws SQLException {
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
            // identifiers print uppercase, exotic names ride as-is
            out.append(f.schema()).append('\n').append(f.table())
                    .append('\n').append(String.join(",", f.cols().stream()
                            .map(TestDataGenerator::headerCase).toList()))
                    .append('\n');
            try (ResultSet rs = st.executeQuery(
                    "select * from (" + union + ") order by all")) {
                int n = rs.getMetaData().getColumnCount();
                while (rs.next()) {
                    StringBuilder row = new StringBuilder();
                    for (int i = 1; i <= n; i++) {
                        if (i > 1) {
                            row.append(',');
                        }
                        String v = rs.getString(i);
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

    // ===== assertTestData (engine: setUpDataSQLs + assertSameElements) =====

    /**
     * Compare two testDataGen CSV strings as TYPED ROW SETS: both sides
     * load into temp tables typed from the store model and diff with
     * {@code EXCEPT} both ways — the database normalizes types, dates and
     * numeric spellings, exactly like the engine's route through
     * setUpDataSQLs. Returns null when equal, else a failure message.
     */
    public static String compareCsv(ModelContext ctx, String dbFqn,
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
                String[][] e = exp.get(key);
                String[][] a = act.get(key);
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
            default -> "VARCHAR";
        };
    }

    private static String lit(Object v,
            DatabaseDefinition.ColumnDefinition col) {
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
