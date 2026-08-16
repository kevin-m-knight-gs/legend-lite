// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend;

/** The Allocation plan-node factory for let bindings (extracted from
 * StatementExecutor at the file guardrail). */
final class PlanAllocations {

    private PlanAllocations() {
    }

    /** An Allocation child for one plan let: LITERAL values print as
     * Constant nodes, scalar query values as SCALAR-projection
     * Relational nodes (bare-typed, alias-less select), and CLASS query
     * values as full Class-envelope Relational nodes — the engine's
     * three Allocation value forms. */
    static @com.legend.Nullable String node(
            com.legend.compiler.spec.typed.TypedLet let, String mappingFqn,
            com.legend.compiler.spec.SpecCompiler specs, StatementExecutor.ExecEnv env,
            java.util.Map<String, com.legend.sql.SqlExpr.PlanParam> params,
            boolean quote, @com.legend.Nullable String timeZone, @com.legend.Nullable String dbType) {
        String literal = switch (let.value()) {
            case com.legend.compiler.spec.typed.TypedCString cs -> cs.value();
            case com.legend.compiler.spec.typed.TypedCInteger ci ->
                    String.valueOf(ci.value());
            case com.legend.compiler.spec.typed.TypedCFloat cf ->
                    String.valueOf(cf.value());
            case com.legend.compiler.spec.typed.TypedCBoolean cb ->
                    String.valueOf(cb.value());
            case com.legend.compiler.spec.typed.TypedCDate cd ->
                    cd.value().toEngineString();
            default -> null;
        };
        if (literal != null) {
            String typeName = com.legend.plan.PlanText
                    .pureTypeName(let.info().type());
            String size = StatementExecutor.sizeRange(let.info().multiplicity());
            return com.legend.plan.PlanText.allocation(let.name(),
                    com.legend.plan.PlanText.scalarTypeBlock(typeName, size),
                    com.legend.plan.PlanText.constant(typeName, literal));
        }
        String rootClass = StatementExecutor.rootGetAllClass(java.util.List.of(let.value()));
        if (rootClass == null) {
            throw new com.legend.error.NotImplementedException(
                    "plan: Allocation value without a getAll root");
        }
        StatementExecutor.EngineSql es = StatementExecutor.engineSql(java.util.List.of(let.value()),
                mappingFqn, specs, env,
                StatementExecutor.planDialect(dbType, quote, timeZone), params,
                java.util.function.UnaryOperator.identity());
        String[] impl = com.legend.lineage.ScanRelations.rootImpl(
                env.ctx(), mappingFqn, rootClass);
        if (let.info().type()
                instanceof com.legend.compiler.element.type.Type.ClassType) {
            // class-valued allocation: the full Class-envelope node, and
            // the Allocation's own type block is the impls form
            String inner = com.legend.plan.PlanText.single(env.ctx(),
                    rootClass, mappingFqn, es.plan(), es.sql(),
                    java.util.List.of(let.value()));
            return com.legend.plan.PlanText.allocation(let.name(),
                    com.legend.plan.PlanText.typeBlock(env.ctx(), rootClass,
                            impl, es.plan(), java.util.List.of(let.value())),
                    inner);
        }
        String typeName = com.legend.plan.PlanText
                .pureTypeName(let.info().type());
        String size = StatementExecutor.sizeRange(let.info().multiplicity());
        if (!(es.plan() instanceof com.legend.sql.SqlSelect sel)) {
            throw new com.legend.error.NotImplementedException(
                    "plan: Allocation value lowers to a non-select");
        }
        com.legend.sql.SqlSelect bareSel = new com.legend.sql.SqlSelect(
                sel.projections().stream().map(p ->
                        new com.legend.sql.SqlSelect.Projection(
                                p.expr(), null)).toList(),
                sel.distinct(), sel.from(), sel.where(), sel.groupBy(),
                sel.having(), sel.qualify(), sel.orderBy(), sel.limit(),
                sel.offset(), sel.outputs());
        var renderer = StatementExecutor.planDialect(dbType, quote, timeZone);
        String bareSql = renderer.render(bareSel);
        String inner = com.legend.plan.PlanText.scalarRelational(env.ctx(),
                impl[2], sel, typeName, size, bareSql,
                renderer::renderedAlias);
        return com.legend.plan.PlanText.allocation(let.name(),
                com.legend.plan.PlanText.scalarTypeBlock(typeName, size),
                inner);
    }
}
