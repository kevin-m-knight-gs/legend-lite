// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend.compiler.spec;

import com.legend.compiler.NameResolver;
import com.legend.compiler.element.type.Type;
import com.legend.compiler.spec.typed.TypedSpec;
import com.legend.protocol.SourceInfo;
import com.legend.protocol.spec.SqlIsland;
import com.legend.protocol.spec.ValueSpecification;
import com.legend.protocol.sql.InputDialect;
import com.legend.protocol.sql.PostgresDialect;
import com.legend.protocol.sql.SqlToPure;
import com.legend.protocol.sql.SqlTranslationException;
import com.legend.protocol.sql.iq.IqDialect;
import com.legend.protocol.sql.iq.IqProfile;

import java.util.function.Function;

/**
 * THE ONE PLACE a SQL island becomes a Relation expression (docs/LEGEND_SQL_STUDY_2026_10_11.md §3,
 * docs/IQ_SQL_STUDY_2026_10_13.md §5): {@code #SQL{...}#} read as Postgres ({@link PostgresDialect}), {@code #IQ{...}#}
 * as SAP IQ under its profile ({@link IqDialect}). The protocol layer's translator ({@link SqlToPure}) reads the SQL
 * through the island's dialect and builds the Pure a person would write; this seam answers its one question, what a
 * FROM source's columns are (each column's declared store type too, when the source is a table), by typing the source;
 * it resolves the result's names as the resolver resolves any query, and the Typer then types it once, like any query.
 * A refusal becomes the compile error its kind says, placed in the enclosing source.
 */
final class SqlIslands {

    private SqlIslands() {
    }

    /** The relation expression {@code island} means, names resolved; {@code typeSource} types a resolved source. */
    static ValueSpecification desugar(SqlIsland island, Function<ValueSpecification, TypedSpec> typeSource) {
        try {
            ValueSpecification pure = SqlToPure.translate(island.sql(),
                    (source, column) -> columns(typeSource.apply(NameResolver.resolveQuery(source)), island, column),
                    dialect(island));
            return NameResolver.resolveQuery(pure);
        } catch (SqlTranslationException e) {
            String message = island.opening() + "...}# at " + position(island, e.line(), e.column()) + ": "
                    + e.getMessage();
            throw switch (e.kind()) {
                case SYNTAX -> new com.legend.error.IslandSyntaxException(message);
                case UNSUPPORTED -> new com.legend.error.NotImplementedException(message);
                case INVALID -> new TypeInferenceException(message);
            };
        }
    }

    /** The island's dialect: Postgres for {@code #SQL{}}, IQ under the island's profile for {@code #IQ{}}. */
    private static InputDialect dialect(SqlIsland island) {
        if (island.language().equals(SqlIsland.SQL) && island.profile() == null) {
            return PostgresDialect.INSTANCE;
        }
        if (!island.language().equals(SqlIsland.IQ)) {
            throw new TypeInferenceException("an island of language " + island.language() + (island.profile() == null
                    ? "" : " and profile " + island.profile()) + " has no SQL dialect");
        }
        String named = island.profile();
        IqProfile profile = named == null ? IqProfile.DEFAULT : IqProfile.named(named);
        if (profile == null) {
            throw new TypeInferenceException(IqProfile.unknown(java.util.Objects.requireNonNull(named)));
        }
        return IqDialect.of(profile);
    }

    /** A FROM source's columns, as the translator reads them: each column's name, its type's name, whether it may hold
     *  no value, and, when the source is a table, its declared store type, spelled as the store spells it. */
    private static void columns(TypedSpec source, SqlIsland island, SqlToPure.Column column) {
        Type.RelationType schema = Type.relationSchema(source.info().type());
        if (schema == null || !schema.dynamicColumns().isEmpty()) {
            throw new TypeInferenceException(island.opening() + "...}# at " + position(island, 1, 1) + ": a FROM"
                    + " source must be a relation with known columns, not " + source.info().type().typeName());
        }
        java.util.Map<String, com.legend.sql.SqlDdl.ColumnType> stored =
                source instanceof com.legend.compiler.spec.typed.TypedTableReference t ? t.storedTypes()
                        : java.util.Map.of();
        for (Type.Column c : schema.columns()) {
            boolean required = c.multiplicity() instanceof com.legend.compiler.element.type.Multiplicity.Bounded b
                    && b.lower() >= 1;
            com.legend.sql.SqlDdl.ColumnType storeType = stored.get(c.name());
            column.column(c.name(), c.type().typeName(), !required, storeType == null ? null : spelled(storeType));
        }
    }

    /** A declared store type as the store spells it: {@code SMALLINT}, {@code CHAR(5)}, {@code NUMERIC(10,2)}. */
    private static String spelled(com.legend.sql.SqlDdl.ColumnType t) {
        return switch (t) {
            case com.legend.sql.SqlDdl.ColumnType.Plain p -> p.kind().name();
            case com.legend.sql.SqlDdl.ColumnType.Sized s -> s.kind() + "(" + s.size() + ")";
            case com.legend.sql.SqlDdl.ColumnType.Scaled s -> s.kind() + "(" + s.precision() + "," + s.scale() + ")";
        };
    }

    /** A position in the SQL text as a position in the enclosing source (the island's own when it has one). */
    private static String position(SqlIsland island, int line, int column) {
        SourceInfo pos = island.pos();
        if (pos == null) {
            return "line " + line + ", column " + column + " of the SQL";
        }
        return line == 1 ? "line " + pos.startLine() + ", column " + (pos.startColumn() + island.opening().length()
                + column - 1)
                : "line " + (pos.startLine() + line - 1) + ", column " + column;
    }
}
