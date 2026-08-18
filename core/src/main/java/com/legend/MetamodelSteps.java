// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend;

import com.legend.compiler.spec.typed.TypedSpec;

/** The recv-dispatched metamodel-walk vocabulary (extracted from
 * StatementExecutor at the file guardrail): ONE switch shared by
 * planWalk and the map-lambda body (ledger cluster 56), plus the
 * map-over driver and the extends-chain mapping natives. */
final class MetamodelSteps {

    private MetamodelSteps() {
    }

    /** Walk-failure sentinel: a map-lambda body step the metamodel
     * vocabulary does not recognize must FAIL the walk (fall through to
     * the loud pipeline walls), never degrade to a silently empty chain
     * (ledger cluster 56). Distinct from null = Pure-empty. */
    static final Object WALK_UNRECOGNIZED = new Object();

    /** ONE recv-dispatched metamodel vocabulary, shared by planWalk and
     * the map-lambda body (ledger cluster 56 — walkMapBody was a
     * hand-maintained 3-entry copy that silently dropped everything
     * else). Null = recognized head but unserved shape / Pure-empty. */
    static @com.legend.Nullable Object metamodelStep(String fqn,
            Object recv, com.legend.compiler.spec.typed.TypedNativeCall c,
            com.legend.compiler.spec.SpecCompiler specs, StatementExecutor.ExecEnv env) {
            // F7.7: EXACT FQN dispatch (never suffix matching) — the FQN
            // set was censused live over the full sweep and verified
            // against the registered signatures / real legend-pure
            // sources; a user function sharing a simple name can no
            // longer shadow the metamodel vocabulary.
            switch (fqn) {
                case "meta::pure::executionPlan::allNodes" -> {
                    if (recv instanceof com.legend.plan.PlanNode pn) {
                        return new java.util.ArrayList<Object>(pn.allNodes());
                    }
                }
                case "meta::pure::functions::collection::filter" -> {
                    if (recv instanceof java.util.List<?> l
                            && c.args().get(1)
                                    instanceof com.legend.compiler.spec.typed
                                            .TypedLambda lam2) {
                        return StatementExecutor.walkFilter(l, lam2);
                    }
                }
                case "meta::pure::functions::lang::cast",
                        "meta::pure::functions::multiplicity::toOne",
                        "meta::pure::functions::multiplicity::toOneMany" -> {
                    return recv;
                }
                case "meta::pure::functions::collection::at" -> {
                    if (recv instanceof java.util.List<?> l
                            && c.args().get(1)
                                    instanceof com.legend.compiler.spec.typed
                                            .TypedCInteger ix) {
                        return l.get((int) (long) ix.value());
                    }
                }
                case "meta::pure::functions::collection::first" -> {
                    if (recv instanceof java.util.List<?> l) {
                        return l.isEmpty() ? null : l.get(0);
                    }
                }
                case com.legend.compiler.element.type.PlatformTypes
                        .STORE_SCHEMA_NAV -> {
                    if (c.args().size() == 2 && c.args().get(1)
                            instanceof com.legend.compiler.spec.typed
                                    .TypedCString sn9) {
                        return com.legend.exec.MetamodelWalk.schema(recv,
                                sn9.value());
                    }
                }
                case com.legend.compiler.element.type.PlatformTypes
                        .STORE_TABLE_NAV -> {
                    if (c.args().size() == 2 && c.args().get(1)
                            instanceof com.legend.compiler.spec.typed
                                    .TypedCString tn9) {
                        return com.legend.exec.MetamodelWalk.table(recv,
                                tn9.value());
                    }
                }
                case "meta::relational::functions::toPostgresModel::convertElement" -> {
                    return com.legend.exec.MetamodelWalk
                            .convertElement(recv);
                }
                case "meta::relational::functions::toPostgresModel"
                        + "::convertSelectSqlQuery" -> {
                    Object body = com.legend.exec.MetamodelWalk
                            .convertElement(recv);
                    return body == null ? null
                            : com.legend.exec.MetamodelWalk.nodeOf("Query",
                                    new java.util.TreeMap<>(java.util.Map
                                            .of("queryBody", body)));
                }
                case "meta::relational::metamodel::view" -> {
                    if (c.args().size() == 2 && c.args().get(1)
                            instanceof com.legend.compiler.spec.typed
                                    .TypedCString vn) {
                        return com.legend.exec.MetamodelWalk.view(recv,
                                vn.value());
                    }
                }
                case "meta::pure::functions::collection::map" -> {
                    if (recv instanceof java.util.List<?> l
                            && c.args().get(1) instanceof
                                    com.legend.compiler.spec.typed
                                            .TypedLambda ml) {
                        return walkMapOver(l, ml, specs, env);
                    }
                }
                case "meta::pure::mapping::_classMappingByClass" -> {
                    if (c.args().size() == 2 && c.args().get(1) instanceof
                            com.legend.compiler.spec.typed
                                    .TypedPackageableRef cref2) {
                        return com.legend.exec.MetamodelWalk
                                .classMappingsByClass(recv, cref2.fullPath());
                    }
                }
                case "meta::pure::mapping::rootClassMappingByClass" -> {
                    if (c.args().size() == 2 && c.args().get(1) instanceof
                            com.legend.compiler.spec.typed
                                    .TypedPackageableRef cref) {
                        return com.legend.exec.MetamodelWalk
                                .rootClassMappingByClass(recv,
                                        cref.fullPath());
                    }
                }
                case "meta::pure::mapping::classMappingById",
                        "meta::pure::mapping::superMapping",
                        "meta::pure::mapping::allSuperSetImplementations",
                        "meta::relational::metamodel::mainTable",
                        "meta::relational::mapping::resolvePrimaryKey" -> {
                    return mappingNav(fqn, recv, c, specs, env);
                }
                case "meta::pure::mapping::propertyMappingsByPropertyName" -> {
                    if (c.args().size() == 2 && c.args().get(1) instanceof
                            com.legend.compiler.spec.typed
                                    .TypedCString pn) {
                        return com.legend.exec.MetamodelWalk
                                .propertyMappingsByName(recv, pn.value());
                    }
                }
                case "meta::relational::functions::typeInference::inferRelationalType" -> {
                    return com.legend.exec.MetamodelWalk.infer(recv);
                }
                case "meta::relational::metamodel::datatype::dataTypeToSqlText" -> {
                    return com.legend.exec.MetamodelWalk.sqlText(recv);
                }
                default -> {
                    return WALK_UNRECOGNIZED;
                }
            }
        return null;
    }

    /** Map-lambda body: one native call over the parameter
     * ({@code x|$x->propertyMappingsByPropertyName('...')}) — dispatched
     * through the SAME metamodel vocabulary planWalk uses;
     * {@link #WALK_UNRECOGNIZED} on any other shape. */
    static @com.legend.Nullable Object walkMapBody(Object e,
            com.legend.compiler.spec.typed.TypedLambda ml,
            com.legend.compiler.spec.SpecCompiler specs, StatementExecutor.ExecEnv env) {
        if (ml.body().size() != 1 || ml.parameters().isEmpty()
                || !(ml.body().get(0) instanceof
                        com.legend.compiler.spec.typed.TypedNativeCall mb)
                || mb.args().isEmpty()
                || !(mb.args().get(0) instanceof
                        com.legend.compiler.spec.typed.TypedVariable mv)
                || !mv.name().equals(ml.parameters().get(0))) {
            return WALK_UNRECOGNIZED;
        }
        return metamodelStep(mb.callee().qualifiedName(), e, mb, specs,
                env);
    }

    /** {@code ->map(x|...)} over walked handles; a single IS a [1]
     * collection (pure semantics), so classMappingById's [0..1] result
     * maps like the metamodel families' lists. */
    static @com.legend.Nullable Object walkMapOver(@com.legend.Nullable Object recvM,
            com.legend.compiler.spec.typed.TypedLambda tml,
            com.legend.compiler.spec.SpecCompiler specs, StatementExecutor.ExecEnv env) {
        if (recvM != null && !(recvM instanceof java.util.List)) {
            recvM = java.util.List.of(recvM);
        }
        if (recvM instanceof java.util.List<?> lm) {
            java.util.List<Object> outM = new java.util.ArrayList<>();
            for (Object e : lm) {
                Object v = walkMapBody(e, tml, specs, env);
                if (v == WALK_UNRECOGNIZED) {
                    return null;   // honest walk failure, never empty
                }
                if (v instanceof java.util.List<?> vl) {
                    outM.addAll(vl);   // Pure map auto-flattens
                } else if (v != null) {
                    outM.add(v);
                }
            }
            return outM;
        }
        return null;
    }

    /** The extends-chain mapping-metamodel natives (classMappingById /
     * superMapping / allSuperSetImplementations / mainTable /
     * resolvePrimaryKey) — recv-dispatched to MetamodelWalk. */
    private static @com.legend.Nullable Object mappingNav(String fqn, Object recv,
            com.legend.compiler.spec.typed.TypedNativeCall c,
            com.legend.compiler.spec.SpecCompiler specs, StatementExecutor.ExecEnv env) {
        return switch (fqn) {
            case "meta::pure::mapping::classMappingById" -> c.args().size() == 2
                    && c.args().get(1) instanceof
                            com.legend.compiler.spec.typed.TypedCString mid
                    ? com.legend.exec.MetamodelWalk.classMappingById(recv,
                            mid.value())
                    : null;
            case "meta::pure::mapping::superMapping" ->
                    com.legend.exec.MetamodelWalk.superMapping(recv);
            case "meta::pure::mapping::allSuperSetImplementations" -> c.args().size() == 2
                    ? com.legend.exec.MetamodelWalk
                            .allSuperSetImplementations(recv,
                                    StatementExecutor.planWalk(c.args().get(1), specs, env))
                    : null;
            case "meta::relational::metamodel::mainTable" ->
                    com.legend.exec.MetamodelWalk.mainTable(recv);
            case "meta::relational::mapping::resolvePrimaryKey" ->
                    com.legend.exec.MetamodelWalk.resolvePrimaryKey(recv);
            default -> null;
        };
    }

}
