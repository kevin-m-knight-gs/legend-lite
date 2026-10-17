// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend.compiler.spec.typed;

import com.legend.builtin.NativeFn;
import com.legend.compiler.element.type.ExprType;

import java.util.List;

/**
 * A catalog grid of the session's own database: a {@code fetchDb*MetaData} call whose patterns are literals, typed
 * as the relation JDBC's {@code DatabaseMetaData} fixes (a declared schema, never late-bound). It says which grid and
 * which patterns, never how a database lists them: that is the dialect's, from the {@code SqlSource.Catalog} the
 * Lowerer makes of it (the dialect program's Phase 3, 2026-10-16: until then the Typer carried the compiler's
 * {@code information_schema} text in a {@link TypedRawSqlRelation}).
 *
 * @param grid          the grid
 * @param schemaPattern the schemas' {@code LIKE} pattern; null matches every schema
 * @param tablePattern  the tables' pattern; null matches every table, and a schema grid takes none
 * @param columnPattern the columns' pattern; null matches every column, and only the column grid takes one (the
 *                      IR source, {@code SqlSource.Catalog}, refuses any other)
 * @param info          the grid's declared relation
 */
public record TypedCatalogGrid(NativeFn.Carrier.FetchDbGrid grid, @com.legend.base.Nullable String schemaPattern,
        @com.legend.base.Nullable String tablePattern, @com.legend.base.Nullable String columnPattern,
        ExprType info) implements TypedSpec {

    @Override
    public List<TypedSpec> children() {
        return List.of();
    }

    @Override
    public TypedSpec withChildren(List<TypedSpec> kids) {
        TypedSpec.expectChildren(kids, 0, "TypedCatalogGrid");
        return this;
    }

    @Override
    public TypedSpec withInfo(ExprType info) {
        return new TypedCatalogGrid(grid, schemaPattern, tablePattern, columnPattern, info);
    }
}
