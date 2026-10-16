// SPDX-License-Identifier: Apache-2.0

package com.legend.compiler.spec;

import com.legend.compiler.element.type.ExprType;
import com.legend.compiler.spec.typed.TypedCatalogGrid;
import com.legend.compiler.spec.typed.TypedNativeCall;

import java.util.ArrayList;
import java.util.List;

/**
 * The {@code fetchDb*MetaData} catalog grids (E4.b, JAVA_EVICTION_PLAN; moved from {@code exec.DbMetaData} in
 * Phase 1c): a call with LITERAL patterns types as its grid, a {@link TypedCatalogGrid} of the declared relation
 * JDBC's {@code DatabaseMetaData} fixes. Every VALUE is database-produced, read from the AMBIENT session's catalog
 * (the database the raw writes actually seeded, the F6.6 rule). Where that catalog is and how it is read is the
 * dialect's ({@code SqlSource.Catalog}; the dialect program's Phase 3, 2026-10-16: until then this class wrote
 * {@code information_schema} text the Typer carried as raw SQL).
 */
public final class CatalogGrids {

    private CatalogGrids() {
    }

    /** The catalog grid of a {@code fetchDb*} call with LITERAL patterns, the Typer's retype gate (Phase 1c: the
     * call types as its relation). Null = not compile-time recognizable (a variable pattern): the call keeps its
     * declared type and WALLS loudly at the pipeline. All four grids read the LIVE catalog (batch 71: primary keys
     * too). */
    public static @com.legend.base.Nullable TypedCatalogGrid grid(TypedNativeCall nc) {
        var kind = java.util.Objects.requireNonNull(com.legend.builtin.NativeFn.Carrier.fetchDbGrid(nc.callee().id()));
        String a1 = literalPattern(nc, 1);
        String a2 = nc.args().size() > 2 ? literalPattern(nc, 2) : null;
        String a3 = nc.args().size() > 3 ? literalPattern(nc, 3) : null;
        if (bad(a1) || bad(a2) || bad(a3)) {
            return null;
        }
        // §4bZ-U leg 4: the JDBC spec fixes the metadata result shape, a DECLARED table-function schema, never
        // late-bound (no LIMIT-0 probe, reads stamp statically)
        ExprType info = ExprType.one(com.legend.compiler.element.type.Type.relation(gridSchema(kind)));
        return switch (kind) {
            case SCHEMAS -> new TypedCatalogGrid(kind, a1, null, null, info);
            case TABLES, PRIMARY_KEYS -> new TypedCatalogGrid(kind, a1, a2, null, info);
            case COLUMNS -> new TypedCatalogGrid(kind, a1, a2, a3, info);
        };
    }

    /** Sentinel-based literal pattern: null = empty ([]), the string =
     * a literal, {@link #BAD} = not compile-time recognizable. */
    private static final String BAD = " bad";

    private static boolean bad(@com.legend.base.Nullable String s) {
        return BAD.equals(s);
    }

    private static @com.legend.base.Nullable String literalPattern(TypedNativeCall nc, int i) {
        var a = nc.args().get(i);
        if (a instanceof com.legend.compiler.spec.typed.TypedCString cs) {
            return cs.value();
        }
        if (a instanceof com.legend.compiler.spec.typed.TypedCollection col
                && col.elements().isEmpty()) {
            return null;
        }
        return BAD;
    }

    /** THE DECLARED METADATA SCHEMA (§4bZ-U leg 4): the JDBC
     * {@code DatabaseMetaData} spec FIXES each catalog grid's result
     * shape, and every dialect's catalog projection writes it — so the
     * columns are typed compilation facts (a declared table-function
     * schema), never late-bound. Text columns are {@code String[0..1]};
     * {@code DATA_TYPE} ({@code java.sql.Types} int) and
     * {@code KEY_SEQ} (short) are {@code Integer[0..1]}. */
    public static com.legend.compiler.element.type.Type.RelationType
            gridSchema(
            com.legend.builtin.NativeFn.Carrier.FetchDbGrid k) {
        List<com.legend.compiler.element.type.Type.Column> cols =
                new ArrayList<>();
        for (String name : gridColumns(k)) {
            com.legend.compiler.element.type.Type t =
                    name.equals("DATA_TYPE") || name.equals("KEY_SEQ")
                            ? com.legend.compiler.element.type.Type
                                    .Primitive.INTEGER
                            : com.legend.compiler.element.type.Type
                                    .Primitive.STRING;
            cols.add(new com.legend.compiler.element.type.Type.Column(
                    name, t, com.legend.compiler.element.type.Multiplicity
                            .Bounded.ZERO_ONE));
        }
        return new com.legend.compiler.element.type.Type.RelationType(cols);
    }

    /** The grid's column names per kind, in JDBC's order — compilation
     * facts (the E4.e chain compiler's columnNames / positional-index
     * arithmetic); a dialect's catalog projection names its columns by
     * them (the source's outputs). */
    public static List<String> gridColumns(
            com.legend.builtin.NativeFn.Carrier.FetchDbGrid k) {
        return switch (k) {
            case SCHEMAS -> List.of("TABLE_SCHEM", "TABLE_CATALOG");
            case TABLES -> List.of("TABLE_CAT", "TABLE_SCHEM",
                    "TABLE_NAME", "TABLE_TYPE", "REMARKS");
            case COLUMNS -> List.of("TABLE_CAT", "TABLE_SCHEM",
                    "TABLE_NAME", "COLUMN_NAME", "DATA_TYPE", "TYPE_NAME",
                    "SQL_TYPE_NAME");
            case PRIMARY_KEYS -> List.of("TABLE_CAT", "TABLE_SCHEM",
                    "TABLE_NAME", "COLUMN_NAME", "KEY_SEQ", "PK_NAME");
        };
    }
}
