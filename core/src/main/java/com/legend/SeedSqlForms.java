// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend;

import com.legend.compiler.spec.typed.TypedSpec;
import com.legend.exec.ExecutionResult;

/**
 * The TWO faces of {@code setUpDataSQLs} (engine toDDL.pure): the
 * ASSERT surface carries the engine's H2 statement TEXT verbatim
 * ({@link com.legend.exec.Ddl#setUpDataSqlsText}); the EXECUTION surface
 * keeps the DuckDB-safe {@link com.legend.exec.CsvSeed} forms — the
 * engine runs its text on EPHEMERAL per-connection H2 dbs, while our
 * DuckDB catalog is SHARED across a family's seeds, where the text's
 * schema cascades would destroy sibling seeds.
 */
final class SeedSqlForms {

    private SeedSqlForms() {
    }

    private static boolean isSetUpDataSqls(
            com.legend.compiler.spec.typed.TypedNativeCall c) {
        return com.legend.compiler.element.type.PlatformTypes.SET_UP_DATA_SQLS_V2
                        .equals(c.callee().qualifiedName())
                || com.legend.compiler.element.type.PlatformTypes.SET_UP_DATA_SQLS
                        .equals(c.callee().qualifiedName());
    }

    /** The ENGINE-TEXT statement list (golden-asserted). Unresolvable
     *  db keeps the CsvSeed execution form (nothing asserts its text). */
    static ExecutionResult assertForm(java.util.List<TypedSpec> body,
            com.legend.compiler.spec.typed.TypedNativeCall gen,
            StatementExecutor.ExecEnv env) throws java.sql.SQLException {
        String csv = StatementExecutor.evalStringArg(body, gen.args().get(0), env);
        String dbFqn = gen.args().get(1)
                instanceof com.legend.compiler.spec.typed.TypedPackageableRef pr
                ? pr.fullPath() : null;
        var dbDef = dbFqn == null ? null
                : env.ctx().findDatabase(dbFqn).orElse(null);
        return new ExecutionResult.Collection(new java.util.ArrayList<>(
                dbDef != null ? com.legend.exec.Ddl.setUpDataSqlsText(csv, dbDef)
                        : com.legend.exec.CsvSeed.sqls(csv, dbFqn, env.ctx())),
                com.legend.compiler.element.type.Type.Primitive.STRING);
    }

    /** The EXECUTION form for a mapped {@code ->map(executeInDb)} source;
     *  null when the source is not a setUpDataSQLs call. */
    static ExecutionResult.@com.legend.Nullable Collection mappedExecutionForm(
            java.util.List<TypedSpec> body,
            com.legend.compiler.spec.typed.TypedMap tm,
            StatementExecutor.ExecEnv env) throws java.sql.SQLException {
        if (!(tm.source() instanceof com.legend.compiler.spec.typed.TypedNativeCall sg)
                || !isSetUpDataSqls(sg)) {
            return null;
        }
        String seedCsv = StatementExecutor.evalStringArg(body, sg.args().get(0), env);
        String seedDb = sg.args().get(1)
                instanceof com.legend.compiler.spec.typed.TypedPackageableRef spr
                ? spr.fullPath() : null;
        return new ExecutionResult.Collection(new java.util.ArrayList<>(
                com.legend.exec.CsvSeed.sqls(seedCsv, seedDb, env.ctx())),
                com.legend.compiler.element.type.Type.Primitive.STRING);
    }
}
