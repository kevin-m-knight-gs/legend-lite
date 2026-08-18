// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend.exec;

import com.legend.model.DatabaseDefinition;
import com.legend.model.RelationalDataType;

/**
 * DDL rendering from the COMPILED store model — the K-native
 * {@code dropAndCreateTableInDb} boundary (the real engine's
 * {@code toDDL.pure} walks the Database metamodel; legend-lite renders
 * from {@link DatabaseDefinition}). Lives in the EXEC (K-phase) package —
 * the SQL layer ({@code com.legend.sql}) stays standalone and never sees
 * the store model. Model-derived DDL is spelled correctly for its
 * TARGET the first time (F7.4, audit S4): the type switch carries both
 * flavors, and the {@link RawSqlBoundary} translator serves
 * HAND-WRITTEN corpus text only — text whose origin really is another
 * dialect.
 *
 * <p>No PRIMARY KEY / NOT NULL constraints are emitted — a DELIBERATE
 * DIVERGENCE from the engine (its dropAndCreateTableInDb defaults
 * applyConstraints=true): milestoned test tables seed several versions
 * of one id, and DuckDB would reject the re-seeds the engine's H2 setup
 * tolerates. Parity here is with legend-lite's own legacy replay, not
 * the engine.
 */
public final class Ddl {

    private Ddl() {
    }

    /** The render TARGETS of the ONE generator (ratified E4 design:
     * engine-exact text is a FLAVOR of the single speller, never a
     * second one). {@code H2_EXEC}/{@code DUCK_EXEC} are the EXECUTION
     * forms — full-name quoting, no constraints (the deliberate DuckDB
     * re-seed divergence in this file's header); {@code ENGINE_TEXT} is
     * the engine's {@code translateCreateTableStatementDefault}
     * (extensionDefaults.pure:609-620) — reserved-word column quoting,
     * engine type spellings (INT), NULL / NOT NULL nullability,
     * trailing {@code , PRIMARY KEY(...)} with RAW pk names. */
    public enum Flavor { H2_EXEC, DUCK_EXEC, ENGINE_TEXT }

    /** {@code Drop table if exists s.T;} — the engine's
     * dropTableStatement spelling, identical across every flavor. */
    public static String dropTable(String schema, String table) {
        return "Drop table if exists " + qualify(schema, table) + ";";
    }

    public static String createTable(DatabaseDefinition.TableDefinition def,
            @com.legend.Nullable String schema) {
        return createTable(def, schema, Flavor.H2_EXEC);
    }

    public static String createTable(DatabaseDefinition.TableDefinition def,
            @com.legend.Nullable String schema, boolean duckTarget) {
        return createTable(def, schema,
                duckTarget ? Flavor.DUCK_EXEC : Flavor.H2_EXEC);
    }

    /** THE create-table generator, flavor-dispatched ({@link Flavor}). */
    public static String createTable(DatabaseDefinition.TableDefinition def,
            @com.legend.Nullable String schema, Flavor f) {
        StringBuilder sb = new StringBuilder("Create Table ")
                .append(qualify(schema, def.name())).append("(");
        boolean first = true;
        for (DatabaseDefinition.ColumnDefinition col : def.columns()) {
            if (!first) {
                sb.append(f == Flavor.ENGINE_TEXT ? "," : ", ");
            }
            first = false;
            // EXEC flavors FULL-quote (corpus columns carry spaces and
            // reserved words — the dialect's quoteCreateColumns passes
            // quoted heads through); ENGINE_TEXT quotes by the engine's
            // processColumnName rule
            sb.append(f == Flavor.ENGINE_TEXT
                            ? processColumnName(col.name())
                            : '"' + col.name() + '"')
                    .append(' ').append(spell(col.dataType(), f));
            if (f == Flavor.ENGINE_TEXT) {
                sb.append(col.primaryKey() || col.notNull()
                        ? " NOT NULL" : " NULL");
            }
        }
        if (f == Flavor.ENGINE_TEXT) {
            java.util.List<String> pks = def.columns().stream()
                    .filter(DatabaseDefinition.ColumnDefinition::primaryKey)
                    .map(DatabaseDefinition.ColumnDefinition::name).toList();
            if (!pks.isEmpty()) {
                // the engine joins the pk NAMES RAW (translateCreateTable-
                // StatementDefault: '$t.primaryKey->map(c|$c.name)', no
                // processColumnName) — text parity keeps that spelling
                sb.append(", PRIMARY KEY(").append(String.join(",", pks))
                        .append(')');
            }
        }
        return sb.append(");").toString();
    }

    /** The ENGINE's column-name rule for H2 DDL TEXT — processColumnName
     * = columnNameToIdentifier THEN processIdentifierWithQuoteChar
     * (dbExtension.pure:611-614, extensionDefaults.pure:557-563). H2
     * leaves columnNameToIdentifier UNSET, and the DbConfig accessor
     * defaults it to IDENTITY (dbExtension.pure:155-158) — the
     * kerberos/date/first uppercase trio belongs to the dialects that
     * opt in (redshift, sqlserver, ...), NOT H2; testDDL.pure's goldens
     * pin bare {@code date}. F3.5 (audit A16): the old reserved-word-
     * ONLY rule missed the engine's OTHER two quote triggers — pre-
     * quoted and SPACE-BEARING names — so the corpus's 'Previous Fiscal
     * Week Year' emitted bare here and the DuckDB boundary's head-quoter
     * mangled it downstream. Reserved words come from the dialect
     * lexicon — the ONE H2 list. */
    private static String processColumnName(String name) {
        // processIdentifierWithQuoteChar: pre-quoted, reserved, or
        // space-bearing identifiers quote (embedded quotes stripped).
        // Audit §11 "three inconsistent quoting rules", adjudicated
        // (documented-debts 2026-08-18): the strip is the ENGINE'S OWN
        // rule verbatim (extensionDefaults.pure:559 —
        // identifier->replace('"','') inside the quote chars), and this
        // method serves ONLY the ENGINE_TEXT byte-parity flavor.
        // AnsiSqlRenderer.ident DOUBLES because execution-correct SQL
        // must; two contracts, not one behavior with two owners. The
        // third copy (GridReads.q) dies with the fetchDb leg.
        if (name.startsWith("\"")
                || com.legend.sql.dialect.Lexicon.H2_ENGINE_TEXT
                        .reservedWords()
                        .contains(name.toLowerCase(java.util.Locale.ROOT))
                || name.contains(" ")) {
            return '"' + name.replace("\"", "") + '"';
        }
        return name;
    }

    /** The ENGINE's setUpDataSQLs TEXT (toDDL.pure:186-195 +
     * loadCsvDataToDbTable): schema drop/create pairs, every table's
     * drop/create text, then one {@code insert} per CSV row. Faithful
     * quirks: cells are NOT trimmed; a cell whose FIRST char is a quote
     * unquotes, leading-space-then-quote keeps the cell verbatim; block
     * separators are lines of dashes (the CsvSeed corpus form). */
    public static java.util.List<String> setUpDataSqlsText(String data,
            DatabaseDefinition db) {
        return setUpDataSqlsText(data, db, f -> java.util.Optional.empty());
    }

    /** Include-closure form (cluster 60 — the engine's {@code
     * allSchemas()} recurses {@code includes} FIRST, groups schemas by
     * name and de-duplicates tables; setUpDataSQLs walks that closure,
     * so a db of includes yields every included table's DDL). The
     * lookup resolves an include FQN to its definition; an unresolvable
     * include contributes nothing (parity with the engine's cast walk
     * over a compiled model, where it cannot happen). */
    public static java.util.List<String> setUpDataSqlsText(String data,
            DatabaseDefinition db,
            java.util.function.Function<String,
                    java.util.Optional<DatabaseDefinition>> lookup) {
        // include-first, group-by-name, table-dedup-by-name (first wins)
        java.util.LinkedHashMap<String,
                java.util.LinkedHashMap<String,
                        DatabaseDefinition.TableDefinition>> named =
                new java.util.LinkedHashMap<>();
        java.util.LinkedHashMap<String,
                DatabaseDefinition.TableDefinition> defaults =
                new java.util.LinkedHashMap<>();
        collectClosure(db, lookup, new java.util.LinkedHashSet<>(), named,
                defaults);
        java.util.List<String> out = new java.util.ArrayList<>();
        for (var sc : named.entrySet()) {
            out.add("Drop schema if exists " + sc.getKey() + " cascade;");
            out.add("Create Schema if not exists " + sc.getKey() + ";");
        }
        out.add("Drop schema if exists default cascade;");
        out.add("Create Schema if not exists default;");
        for (var sc : named.entrySet()) {
            for (var t : sc.getValue().values()) {
                out.add(dropTable(sc.getKey(), t.name()));
                out.add(createTable(t, sc.getKey(), Flavor.ENGINE_TEXT));
            }
        }
        // the parser FLATTENS named-schema tables into the top-level list
        // too — the DDL surface lists each table once, under its schema
        java.util.Set<String> inSchemas = new java.util.HashSet<>();
        for (var sc : named.values()) {
            for (var t : sc.values()) {
                inSchemas.add(t.name());
            }
        }
        for (var t : defaults.values()) {
            if (!inSchemas.contains(t.name())) {
                out.add(dropTable("default", t.name()));
                out.add(createTable(t, "default", Flavor.ENGINE_TEXT));
            }
        }
        String[] lines = data.split("\n", -1);
        int i = 0;
        while (i < lines.length) {
            while (i < lines.length && (lines[i].isBlank()
                    || lines[i].strip().matches("-+"))) {
                i++;
            }
            if (i + 2 >= lines.length) {
                break;
            }
            String schema = lines[i].strip();
            String table = lines[i + 1].strip();
            java.util.List<String> header = csvCells(lines[i + 2]);
            DatabaseDefinition.TableDefinition def =
                    findTable(db, schema, table);
            i += 3;
            while (i < lines.length && !lines[i].isBlank()
                    && !lines[i].strip().matches("-+")) {
                out.add(insertText(schema, table, def, header,
                        csvCells(lines[i])));
                i++;
            }
        }
        return out;
    }

    /** The RECORDS form (engine setUpDataSQLs(records:List<String>[*])):
     *  pre-split cells, no CSV re-parsing — schema/table statements from
     *  the string form with EMPTY data, inserts appended per record
     *  block (blank single-cell records separate blocks). */
    public static java.util.List<String> setUpDataSqlsTextFromRecords(
            java.util.List<java.util.List<String>> records,
            DatabaseDefinition db) {
        return setUpDataSqlsTextFromRecords(records, db,
                f -> java.util.Optional.empty());
    }

    public static java.util.List<String> setUpDataSqlsTextFromRecords(
            java.util.List<java.util.List<String>> records,
            DatabaseDefinition db,
            java.util.function.Function<String,
                    java.util.Optional<DatabaseDefinition>> lookup) {
        java.util.List<String> out = setUpDataSqlsText("", db, lookup);
        int i = 0;
        while (i < records.size()) {
            while (i < records.size() && blankRecord(records.get(i))) {
                i++;
            }
            if (i + 2 >= records.size()) {
                break;
            }
            String schema = records.get(i).get(0).strip();
            String table = records.get(i + 1).get(0).strip();
            java.util.List<String> header = records.get(i + 2);
            DatabaseDefinition.TableDefinition def =
                    findTable(db, schema, table);
            i += 3;
            while (i < records.size() && !blankRecord(records.get(i))) {
                out.add(insertText(schema, table, def, header,
                        records.get(i)));
                i++;
            }
        }
        return out;
    }

    private static boolean blankRecord(java.util.List<String> r) {
        return r.isEmpty() || (r.size() == 1 && r.get(0).isBlank());
    }

    private static DatabaseDefinition.@com.legend.Nullable TableDefinition
            findTable(DatabaseDefinition db, String schema, String table) {
        if (!"default".equals(schema)) {
            for (var sc : db.schemas()) {
                if (sc.name().equals(schema)) {
                    for (var t : sc.tables()) {
                        if (t.name().equalsIgnoreCase(table)) {
                            return t;
                        }
                    }
                }
            }
        }
        for (var t : db.tables()) {
            if (t.name().equalsIgnoreCase(table)) {
                return t;
            }
        }
        return null;
    }

    /** Quote-aware split; a cell whose FIRST character is the quote
     *  unquotes (CSV semantics), leading whitespace before the quote
     *  keeps the cell verbatim, quotes and all (engine parseCSV parity). */
    private static java.util.List<String> csvCells(String line) {
        java.util.List<String> cells = new java.util.ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean inQuotes = false;
        for (int k = 0; k < line.length(); k++) {
            char ch = line.charAt(k);
            if (ch == '"') {
                inQuotes = !inQuotes;
                cur.append(ch);
            } else if (ch == ',' && !inQuotes) {
                cells.add(cur.toString());
                cur.setLength(0);
            } else {
                cur.append(ch);
            }
        }
        cells.add(cur.toString());
        for (int k = 0; k < cells.size(); k++) {
            String c = cells.get(k);
            if (c.length() >= 2 && c.charAt(0) == '"' && c.endsWith("\"")) {
                cells.set(k, c.substring(1, c.length() - 1));
            }
        }
        return cells;
    }

    private static String insertText(String schema, String table,
            DatabaseDefinition.@com.legend.Nullable TableDefinition def,
            java.util.List<String> header, java.util.List<String> cells) {
        java.util.List<String> colNames = new java.util.ArrayList<>();
        java.util.List<String> values = new java.util.ArrayList<>();
        for (int c = 0; c < header.size() && c < cells.size(); c++) {
            String h = header.get(c).strip();
            DatabaseDefinition.ColumnDefinition col = null;
            if (def != null) {
                for (var cd : def.columns()) {
                    if (cd.name().equalsIgnoreCase(h)) {
                        col = cd;
                        break;
                    }
                }
            }
            colNames.add(col != null ? col.name() : h);
            String cell = cells.get(c);
            boolean numeric = col != null && isNumericType(col.dataType());
            values.add(numeric ? cell.strip()
                    : "'" + cell.replace("'", "''") + "'");
        }
        String qualified = "default".equals(schema) ? table
                : schema + "." + table;
        return "insert into " + qualified + " ("
                + String.join(",", colNames) + ") values ("
                + String.join(",", values) + ");";
    }

    private static boolean isNumericType(RelationalDataType t) {
        return t instanceof RelationalDataType.Integer_
                || t instanceof RelationalDataType.BigInt
                || t instanceof RelationalDataType.SmallInt
                || t instanceof RelationalDataType.TinyInt
                || t instanceof RelationalDataType.Float_
                || t instanceof RelationalDataType.Double_
                || t instanceof RelationalDataType.Real
                || t instanceof RelationalDataType.Decimal
                || t instanceof RelationalDataType.Numeric;
    }

    private static void collectClosure(DatabaseDefinition db,
            java.util.function.Function<String,
                    java.util.Optional<DatabaseDefinition>> lookup,
            java.util.Set<String> seen,
            java.util.Map<String, java.util.LinkedHashMap<String,
                    DatabaseDefinition.TableDefinition>> named,
            java.util.Map<String,
                    DatabaseDefinition.TableDefinition> defaults) {
        if (!seen.add(db.qualifiedName())) {
            return;
        }
        for (String inc : db.includes()) {
            lookup.apply(inc).ifPresent(d ->
                    collectClosure(d, lookup, seen, named, defaults));
        }
        for (var sc : db.schemas()) {
            var tables = named.computeIfAbsent(sc.name(),
                    k -> new java.util.LinkedHashMap<>());
            for (var t : sc.tables()) {
                tables.putIfAbsent(t.name(), t);
            }
        }
        for (var t : db.tables()) {
            defaults.putIfAbsent(t.name(), t);
        }
    }

    private static String qualify(@com.legend.Nullable String schema, String table) {
        return schema == null || schema.isEmpty() || "default".equals(schema)
                ? table : schema + "." + table;
    }

    /** The FLAVORED type spelling: the deltas from the H2 base are the
     * ONLY per-target lines — DuckDB where H2's type SEMANTICS differ
     * from its name (FLOAT is an 8-byte double, BIT a boolean — spelled
     * from the TYPE, never recovered from text, F7.4); engine TEXT is
     * {@link #dataTypeToSqlText}, the ONE engine spelling. */
    private static String spell(RelationalDataType t, Flavor f) {
        if (f == Flavor.DUCK_EXEC
                && t instanceof RelationalDataType.Float_) {
            return "DOUBLE";
        }
        if (f == Flavor.DUCK_EXEC && t instanceof RelationalDataType.Bit) {
            return "BOOLEAN";
        }
        if (f == Flavor.ENGINE_TEXT) {
            return dataTypeToSqlText(t);
        }
        return spell(t);
    }

    /** THE engine {@code dataTypeToSqlText} spelling
     * (platform_store_relational/functions.pure:68-96), spelled ONCE —
     * the ENGINE_TEXT DDL flavor and the metamodel walk's
     * dataTypeToSqlText native both read here (ratified E4 design: no
     * type text is spelled twice). Deltas from the EXECUTION base:
     * Integer spells INT; Other spells OTHER (execution walls — a
     * column of type Other cannot be created). */
    public static String dataTypeToSqlText(RelationalDataType t) {
        if (t instanceof RelationalDataType.Integer_) {
            return "INT";
        }
        if (t instanceof RelationalDataType.Other) {
            return "OTHER";
        }
        return spell(t);
    }

    /** The H2-flavored spelling of a store column type. */
    private static String spell(RelationalDataType t) {
        return switch (t) {
            case RelationalDataType.BigInt ignored -> "BIGINT";
            case RelationalDataType.SmallInt ignored -> "SMALLINT";
            case RelationalDataType.TinyInt ignored -> "TINYINT";
            case RelationalDataType.Integer_ ignored -> "INTEGER";
            // H2's FLOAT is an 8-byte double (duckSpell owns the
            // DuckDB flavor — F7.4 ended the render-then-regex loop)
            case RelationalDataType.Float_ ignored -> "FLOAT";
            case RelationalDataType.Double_ ignored -> "DOUBLE";
            case RelationalDataType.Real ignored -> "REAL";
            case RelationalDataType.Bit ignored -> "BIT";
            case RelationalDataType.Timestamp ignored -> "TIMESTAMP";
            case RelationalDataType.Date_ ignored -> "DATE";
            case RelationalDataType.Varchar v -> "VARCHAR(" + v.size() + ")";
            case RelationalDataType.Char_ c -> "CHAR(" + c.size() + ")";
            case RelationalDataType.Binary b -> "BINARY(" + b.size() + ")";
            case RelationalDataType.Varbinary v -> "VARBINARY(" + v.size() + ")";
            case RelationalDataType.Decimal d ->
                    "DECIMAL(" + d.precision() + ", " + d.scale() + ")";
            case RelationalDataType.Numeric n ->
                    "NUMERIC(" + n.precision() + ", " + n.scale() + ")";
            case RelationalDataType.SemiStructured ignored -> "JSON";
            // No DDL spelling by design — EXPLICIT so a new variant is a
            // compile error here, not a runtime surprise (T3.1).
            case RelationalDataType.Distinct ignored -> throw new IllegalStateException(
                    "no DDL spelling for store column type " + t);
            case RelationalDataType.Other ignored -> throw new IllegalStateException(
                    "no DDL spelling for store column type " + t);
            case RelationalDataType.Array ignored -> throw new IllegalStateException(
                    "no DDL spelling for store column type " + t);
            case RelationalDataType.Object_ ignored -> throw new IllegalStateException(
                    "no DDL spelling for store column type " + t);
        };
    }
}
