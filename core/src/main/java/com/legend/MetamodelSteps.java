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
    static @com.legend.Nullable Object metamodelStep(String simple,
            Object recv, com.legend.compiler.spec.typed.TypedNativeCall c,
            com.legend.compiler.spec.SpecCompiler specs, StatementExecutor.ExecEnv env) {
            switch (simple) {
                case "allNodes" -> {
                    if (recv instanceof com.legend.plan.PlanNode pn) {
                        return new java.util.ArrayList<Object>(pn.allNodes());
                    }
                }
                case "filter" -> {
                    if (recv instanceof java.util.List<?> l
                            && c.args().get(1)
                                    instanceof com.legend.compiler.spec.typed
                                            .TypedLambda lam2) {
                        return StatementExecutor.walkFilter(l, lam2);
                    }
                }
                case "cast", "toOne", "toOneMany" -> {
                    return recv;
                }
                case "at" -> {
                    if (recv instanceof java.util.List<?> l
                            && c.args().get(1)
                                    instanceof com.legend.compiler.spec.typed
                                            .TypedCInteger ix) {
                        return l.get((int) (long) ix.value());
                    }
                }
                case "first" -> {
                    if (recv instanceof java.util.List<?> l) {
                        return l.isEmpty() ? null : l.get(0);
                    }
                }
                case "schema" -> {
                    if (c.args().size() == 2 && c.args().get(1)
                            instanceof com.legend.compiler.spec.typed
                                    .TypedCString sn9) {
                        return com.legend.exec.MetamodelWalk.schema(recv,
                                sn9.value());
                    }
                }
                case "table" -> {
                    if (c.args().size() == 2 && c.args().get(1)
                            instanceof com.legend.compiler.spec.typed
                                    .TypedCString tn9) {
                        return com.legend.exec.MetamodelWalk.table(recv,
                                tn9.value());
                    }
                }
                case "convertElement" -> {
                    return com.legend.exec.MetamodelWalk
                            .convertElement(recv);
                }
                case "convertSelectSqlQuery" -> {
                    Object body = com.legend.exec.MetamodelWalk
                            .convertElement(recv);
                    return body == null ? null
                            : com.legend.exec.MetamodelWalk.nodeOf("Query",
                                    new java.util.TreeMap<>(java.util.Map
                                            .of("queryBody", body)));
                }
                case "view" -> {
                    if (c.args().size() == 2 && c.args().get(1)
                            instanceof com.legend.compiler.spec.typed
                                    .TypedCString vn) {
                        return com.legend.exec.MetamodelWalk.view(recv,
                                vn.value());
                    }
                }
                case "map" -> {
                    if (recv instanceof java.util.List<?> l
                            && c.args().get(1) instanceof
                                    com.legend.compiler.spec.typed
                                            .TypedLambda ml) {
                        return walkMapOver(l, ml, specs, env);
                    }
                }
                case "_classMappingByClass" -> {
                    if (c.args().size() == 2 && c.args().get(1) instanceof
                            com.legend.compiler.spec.typed
                                    .TypedPackageableRef cref2) {
                        return com.legend.exec.MetamodelWalk
                                .classMappingsByClass(recv, cref2.fullPath());
                    }
                }
                case "rootClassMappingByClass" -> {
                    if (c.args().size() == 2 && c.args().get(1) instanceof
                            com.legend.compiler.spec.typed
                                    .TypedPackageableRef cref) {
                        return com.legend.exec.MetamodelWalk
                                .rootClassMappingByClass(recv,
                                        cref.fullPath());
                    }
                }
                case "classMappingById", "superMapping",
                        "allSuperSetImplementations", "mainTable",
                        "resolvePrimaryKey" -> {
                    return mappingNav(simple, recv, c, specs, env);
                }
                case "propertyMappingsByPropertyName" -> {
                    if (c.args().size() == 2 && c.args().get(1) instanceof
                            com.legend.compiler.spec.typed
                                    .TypedCString pn) {
                        return com.legend.exec.MetamodelWalk
                                .propertyMappingsByName(recv, pn.value());
                    }
                }
                case "inferRelationalType" -> {
                    return com.legend.exec.MetamodelWalk.infer(recv);
                }
                case "dataTypeToSqlText" -> {
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
        String mfn = mb.callee().qualifiedName();
        String msimple = mfn.substring(mfn.lastIndexOf(':') + 1);
        return metamodelStep(msimple, e, mb, specs, env);
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
    private static @com.legend.Nullable Object mappingNav(String simple, Object recv,
            com.legend.compiler.spec.typed.TypedNativeCall c,
            com.legend.compiler.spec.SpecCompiler specs, StatementExecutor.ExecEnv env) {
        return switch (simple) {
            case "classMappingById" -> c.args().size() == 2
                    && c.args().get(1) instanceof
                            com.legend.compiler.spec.typed.TypedCString mid
                    ? com.legend.exec.MetamodelWalk.classMappingById(recv,
                            mid.value())
                    : null;
            case "superMapping" ->
                    com.legend.exec.MetamodelWalk.superMapping(recv);
            case "allSuperSetImplementations" -> c.args().size() == 2
                    ? com.legend.exec.MetamodelWalk
                            .allSuperSetImplementations(recv,
                                    StatementExecutor.planWalk(c.args().get(1), specs, env))
                    : null;
            case "mainTable" ->
                    com.legend.exec.MetamodelWalk.mainTable(recv);
            case "resolvePrimaryKey" ->
                    com.legend.exec.MetamodelWalk.resolvePrimaryKey(recv);
            default -> null;
        };
    }

}
