// SPDX-License-Identifier: Apache-2.0

package com.legend.exec;

import com.legend.compiler.element.ModelContext;
import com.legend.compiler.element.type.Type;

import java.util.ArrayList;
import java.util.List;

/**
 * The engine's {@code setUpDataSQLsV2} semantics: CSV seed blocks
 * ({@code schema\ntable\nheader\nrows...}, blocks joined by {@code \n-\n})
 * become DDL + INSERT statements derived from the PARSED store's column
 * types ({@link ModelContext#findTable}). Returns SQL text — execution
 * stays with the caller's executeInDb loop (the corpus's own
 * {@code setupTestData} body maps the strings through executeInDb;
 * audit 19d B5 moved this synthesis out of the harness so that body runs
 * through the platform).
 */
public final class CsvSeed {

    private CsvSeed() {
    }

    public static List<String> sqls(String csvBlocks, @com.legend.Nullable String dbFqn,
            ModelContext ctx) {
        List<String> out = new ArrayList<>();
        // block separators: a line of dashes — '-' (the Alloy '\n-\n'
        // form) or '-----' (the testDataGeneration CSV form)
        StringBuilder block = new StringBuilder();
        for (String line : csvBlocks.split("\n", -1)) {
            if (line.strip().matches("-+")) {
                blockSqls(block.toString(), dbFqn, ctx, out);
                block.setLength(0);
            } else {
                if (block.length() > 0) {
                    block.append('\n');
                }
                block.append(line);
            }
        }
        blockSqls(block.toString(), dbFqn, ctx, out);
        return out;
    }

    private static void blockSqls(String csv, @com.legend.Nullable String dbFqn, ModelContext ctx,
            List<String> out) {
        String[] lines = csv.split("\n");
        while (lines.length > 0 && lines[0].isBlank()) {
            lines = java.util.Arrays.copyOfRange(lines, 1, lines.length);
        }
        if (lines.length < 3) {
            return;
        }
        String schema = lines[0].strip();
        String table = lines[1].strip();
        String qualified = "default".equals(schema) ? table
                : schema + "." + table;
        String[] cols = lines[2].split(",");
        var tableType = dbFqn == null
                ? java.util.Optional.<Type.RelationType>empty()
                : ctx.findTable(dbFqn, table);
        if (tableType.isPresent()) {
            // DROP-then-CREATE, never CREATE OR REPLACE: H2 (2.1.214, the
            // engine's own target) has no OR REPLACE for tables — this was
            // the recorded root cause of ~39 'Table already exists' H2
            // replay declines (H2_BACKEND.md §12 step 2); DuckDB accepts
            // the two-statement form identically
            out.add("DROP TABLE IF EXISTS " + qualified);
            StringBuilder ddl = new StringBuilder("CREATE TABLE ")
                    .append(qualified).append(" (");
            var tcols = tableType.get().columns();
            for (int c = 0; c < tcols.size(); c++) {
                if (c > 0) {
                    ddl.append(", ");
                }
                ddl.append(tcols.get(c).name()).append(' ')
                        .append(ddlType(tcols.get(c).type()));
            }
            out.add(ddl.append(")").toString());
        } else {
            out.add("DELETE FROM " + qualified);
        }
        for (int i = 3; i < lines.length; i++) {
            if (lines[i].isBlank()) {
                continue;
            }
            String[] vals = lines[i].split(",", -1);
            StringBuilder sql = new StringBuilder("INSERT INTO ")
                    .append(qualified).append(" (")
                    .append(String.join(", ", cols)).append(") VALUES (");
            for (int c = 0; c < cols.length; c++) {
                String tok = c < vals.length ? vals[c].strip() : "";
                if (c > 0) {
                    sql.append(", ");
                }
                if (tok.isEmpty() || tok.equals("---null---")) {
                    // ---null--- is the testDataGeneration CSV null token
                    sql.append("NULL");
                } else if (tok.matches("[+-]?\\d+(\\.\\d+)?")) {
                    sql.append(tok);
                } else {
                    sql.append("'").append(tok.replace("'", "''"))
                            .append("'");
                }
            }
            out.add(sql.append(")").toString());
        }
    }

    private static String ddlType(Type t) {
        if (t == Type.Primitive.INTEGER) {
            return "BIGINT";
        }
        if (t == Type.Primitive.FLOAT || t == Type.Primitive.NUMBER) {
            return "DOUBLE";
        }
        if (t == Type.Primitive.BOOLEAN) {
            return "BOOLEAN";
        }
        if (t == Type.Primitive.STRICT_DATE) {
            return "DATE";
        }
        if (t == Type.Primitive.DATE_TIME || t == Type.Primitive.DATE) {
            return "TIMESTAMP";
        }
        if (t == Type.Primitive.DECIMAL
                || t instanceof Type.PrecisionDecimal) {
            return "DECIMAL(38, 9)";
        }
        if (t == Type.Primitive.STRING || t instanceof Type.EnumType) {
            return "VARCHAR";
        }
        throw new com.legend.error.NotImplementedException(
                "csv seed DDL type for " + t + " is not mapped");
    }
}
