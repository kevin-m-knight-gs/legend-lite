// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend.lowering;

import com.legend.compiler.spec.typed.TypedFuncCol;
import com.legend.compiler.spec.typed.TypedSerializeGraph;
import com.legend.sql.SqlExpr;
import com.legend.sql.SqlFn;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * The CHECKED serialize envelope ({@code graphFetchChecked}): each object
 * wraps as {@code {defects: [...], value: obj}} where the defects array
 * collects one engine-shaped defect object per FAILING class constraint
 * (a predicate that is not true; SQL NULL = no defect). The engine's
 * defect shape is the dataQuality {@code Defect} serialization —
 * id/externalId/message/enforcementLevel/ruleType/ruleDefinerPath/path.
 */
final class CheckedEnvelope {

    private CheckedEnvelope() {
    }

    /** {@code render} lowers one constraint predicate/message lambda into
     * the envelope base select's terms (the Lowerer's own scalar path). */
    static SqlExpr wrap(TypedSerializeGraph g, SqlExpr obj,
            Function<TypedFuncCol, SqlExpr> render) {
        List<SqlExpr> cases = new ArrayList<>();
        for (var cc : g.checkedConstraints()) {
            SqlExpr pred = render.apply(cc.predicate());
            SqlExpr msg = render.apply(cc.message());
            cases.add(new SqlExpr.Case(List.of(new SqlExpr.Case.When(
                    SqlExpr.Call.of(SqlFn.NOT, pred),
                    new SqlExpr.JsonObject(List.of(
                            new SqlExpr.StringLit("id"),
                            new SqlExpr.StringLit(cc.id()),
                            new SqlExpr.StringLit("externalId"),
                            new SqlExpr.NullLit(),
                            new SqlExpr.StringLit("message"), msg,
                            new SqlExpr.StringLit("enforcementLevel"),
                            new SqlExpr.StringLit(cc.level()),
                            new SqlExpr.StringLit("ruleType"),
                            new SqlExpr.StringLit("ClassConstraint"),
                            new SqlExpr.StringLit("ruleDefinerPath"),
                            new SqlExpr.StringLit(cc.definerFqn()),
                            new SqlExpr.StringLit("path"),
                            SqlExpr.Call.of(SqlFn.TO_VARIANT,
                                    new SqlExpr.ArrayLit(List.of())))))),
                    new SqlExpr.NullLit()));
        }
        SqlExpr defects = cases.isEmpty()
                ? SqlExpr.Call.of(SqlFn.TO_VARIANT,
                        new SqlExpr.ArrayLit(List.of()))
                : SqlExpr.Call.of(SqlFn.TO_VARIANT,
                        SqlExpr.Call.of(SqlFn.LIST_FILTER,
                                new SqlExpr.ArrayLit(cases),
                                new SqlExpr.Lambda(List.of("x"),
                                        SqlExpr.Call.of(SqlFn.IS_NOT_NULL,
                                                new SqlExpr.Column(null,
                                                        "x")))));
        return new SqlExpr.JsonObject(List.of(
                new SqlExpr.StringLit("defects"), defects,
                new SqlExpr.StringLit("value"), obj));
    }
}
