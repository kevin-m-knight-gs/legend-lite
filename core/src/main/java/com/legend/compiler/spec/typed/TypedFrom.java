package com.legend.compiler.spec.typed;

import com.legend.compiler.element.type.ExprType;

import java.util.List;
import java.util.Optional;

/**
 * An execution-context binding {@code ->from(runtime)} / {@code ->from(mapping,
 * runtime)} (engine {@code TypedFrom}) &mdash; a type passthrough
 * ({@code Relation<T>[1]} / {@code T[*]}) that slots the referenced mapping and
 * runtime onto the node for the back-end.
 *
 * @param source  the value being bound to an execution context
 * @param mapping the mapping reference (the M2M three-argument form), if present
 * @param runtime the runtime reference, if present
 * @param chainMappings mapping FQNs carried by a ModelChainConnection inside
 *                an INSTANCE-runtime argument (the XStore chain: an M2M
 *                mapping's ~src classes resolve THROUGH these) — empty for
 *                reference runtimes
 * @param info    the source type unchanged
 */
public record TypedFrom(TypedSpec source, Optional<TypedPackageableRef> mapping,
                        Optional<TypedPackageableRef> runtime,
                        List<String> chainMappings,
                        java.util.Map<String, String> jsonSources,
                        List<String> sqlSetups,
                        @com.legend.Nullable String connectionName,
                        ExprType info) implements TypedSpec {

    public TypedFrom(TypedSpec source, Optional<TypedPackageableRef> mapping,
                     Optional<TypedPackageableRef> runtime, ExprType info) {
        this(source, mapping, runtime, List.of(), java.util.Map.of(),
                List.of(), null, info);
    }

    public TypedFrom(TypedSpec source, Optional<TypedPackageableRef> mapping,
                     Optional<TypedPackageableRef> runtime,
                     List<String> chainMappings, ExprType info) {
        this(source, mapping, runtime, chainMappings, java.util.Map.of(),
                List.of(), null, info);
    }

    public TypedFrom(TypedSpec source, Optional<TypedPackageableRef> mapping,
                     Optional<TypedPackageableRef> runtime,
                     List<String> chainMappings,
                     java.util.Map<String, String> jsonSources,
                     ExprType info) {
        this(source, mapping, runtime, chainMappings, jsonSources, List.of(),
                null, info);
    }

    public TypedFrom(TypedSpec source, Optional<TypedPackageableRef> mapping,
                     Optional<TypedPackageableRef> runtime,
                     List<String> chainMappings,
                     java.util.Map<String, String> jsonSources,
                     List<String> sqlSetups,
                     ExprType info) {
        this(source, mapping, runtime, chainMappings, jsonSources, sqlSetups,
                null, info);
    }

    /** The plan-text CONNECTION SPELLING of the first connection instance
     * under an INSTANCE-runtime expression ({@code
     * RelationalDatabaseConnection(type = "H2")}) — null when no instance
     * connection appears (ref runtimes; the plan surface falls back to
     * TestDatabaseConnection). Exact-FQN dispatch. */
    public static @com.legend.Nullable String connectionNameIn(TypedSpec n) {
        if (n instanceof TypedNewInstance ni) {
            String simple = switch (ni.classFqn()) {
                case "meta::external::store::relational::runtime"
                        + "::DatabaseConnection" -> "DatabaseConnection";
                case "meta::external::store::relational::runtime"
                        + "::RelationalDatabaseConnection" ->
                        "RelationalDatabaseConnection";
                case "meta::external::store::relational::runtime"
                        + "::TestDatabaseConnection" ->
                        "TestDatabaseConnection";
                default -> null;
            };
            if (simple != null) {
                String db = ni.properties().get("type") instanceof
                        TypedEnumValue ev ? String.valueOf(ev.value()) : "H2";
                return simple + "(type = \"" + db + "\")";
            }
        }
        // HELPER-CONSTRUCTED runtimes (from(testRuntimeXY())): the
        // instance lives in the callee's RAW body — chase it at parse
        // level (bare + FQN spellings, the RelationalDebugContext-gate
        // convention)
        if (n instanceof TypedUserCall uc
                && uc.callee().body().isPresent()) {
            for (com.legend.model.spec.ValueSpecification b
                    : uc.callee().body().get()) {
                String r = rawConnectionNameIn(b);
                if (r != null) {
                    return r;
                }
            }
        }
        for (TypedSpec c : n.children()) {
            String r = connectionNameIn(c);
            if (r != null) {
                return r;
            }
        }
        return null;
    }

    private static @com.legend.Nullable String rawConnectionNameIn(
            com.legend.model.spec.ValueSpecification n) {
        if (n instanceof com.legend.model.spec.NewInstance ni) {
            String cn = ni.className();
            String simple = switch (cn) {
                case "DatabaseConnection",
                        "meta::external::store::relational::runtime"
                        + "::DatabaseConnection" -> "DatabaseConnection";
                case "RelationalDatabaseConnection",
                        "meta::external::store::relational::runtime"
                        + "::RelationalDatabaseConnection" ->
                        "RelationalDatabaseConnection";
                case "TestDatabaseConnection",
                        "meta::external::store::relational::runtime"
                        + "::TestDatabaseConnection" ->
                        "TestDatabaseConnection";
                default -> null;
            };
            if (simple != null) {
                com.legend.model.spec.KeyExpression ke =
                        ni.properties().get("type");
                String db = ke != null && ke.value()
                        instanceof com.legend.model.spec.EnumValue ev
                        ? ev.value() : "H2";
                return simple + "(type = \"" + db + "\")";
            }
        }
        java.util.List<com.legend.model.spec.ValueSpecification> kids =
                switch (n) {
                    case com.legend.model.spec.AppliedFunction af ->
                            af.parameters();
                    case com.legend.model.spec.NewInstance ni2 ->
                            ni2.properties().values().stream()
                                    .map(com.legend.model.spec
                                            .KeyExpression::value)
                                    .toList();
                    case com.legend.model.spec.PureCollection pc ->
                            pc.values();
                    case com.legend.model.spec.LambdaFunction lf ->
                            lf.body();
                    default -> java.util.List.of();
                };
        for (com.legend.model.spec.ValueSpecification c : kids) {
            String r = rawConnectionNameIn(c);
            if (r != null) {
                return r;
            }
        }
        return null;
    }

    /** class FQN -> data: URL payload for every
     * {@code ^JsonModelConnection(class=..., url='data:application/json,...')}
     * in a runtime-valued expression — the JSON SOURCE FRAME feed (XStore
     * leg §1). Non-literal shapes contribute nothing. */
    public static java.util.Map<String, String> jsonSourcesIn(TypedSpec n) {
        return jsonSourcesIn(n, java.util.function.UnaryOperator.identity());
    }

    /** {@code canon} resolves a class name to its FQN (helper bodies are
     * UNCHECKED source — their refs may be import-simple). */
    public static java.util.Map<String, String> jsonSourcesIn(TypedSpec n,
            java.util.function.UnaryOperator<String> canon) {
        java.util.Map<String, String> out = new java.util.LinkedHashMap<>();
        collectJson(n, out, canon);
        return java.util.Map.copyOf(out);
    }

    private static void collectJson(TypedSpec n,
            java.util.Map<String, String> out,
            java.util.function.UnaryOperator<String> canon) {
        // helper-CONSTRUCTED runtimes (from(m, runtime())): the JSON
        // frames live in the helper's UNCHECKED body — walk it
        // (TradeLinkage cross-store golden)
        if (n instanceof com.legend.compiler.spec.typed.TypedUserCall uc
                && uc.callee().body().isPresent()) {
            for (com.legend.model.spec.ValueSpecification b
                    : uc.callee().body().get()) {
                collectJsonRaw(b, out, canon);
            }
            return;
        }
        if (n instanceof TypedNewInstance ni
                && "meta::external::store::model::JsonModelConnection"
                        .equals(ni.classFqn())) {
            TypedSpec cls = ni.properties().get("class");
            String url = foldLiteral(ni.properties().get("url"));
            if (cls instanceof TypedPackageableRef pr && url != null) {
                out.put(pr.fullPath(), url);
            }
            return;
        }
        for (TypedSpec c : n.children()) {
            collectJson(c, out, canon);
        }
    }

    /** The UNCHECKED-source mirror of {@link #collectJson} for helper
     * bodies (class refs canonicalized through {@code canon}). */
    private static void collectJsonRaw(
            com.legend.model.spec.ValueSpecification v,
            java.util.Map<String, String> out,
            java.util.function.UnaryOperator<String> canon) {
        switch (v) {
            case com.legend.model.spec.NewInstance ni -> {
                if (ni.className().endsWith("JsonModelConnection")) {
                    var cls = ni.properties().get("class");
                    var url = ni.properties().get("url");
                    if (cls != null && cls.value() instanceof
                            com.legend.model.spec.PackageableElementPtr pr
                            && url != null && url.value() instanceof
                                    com.legend.model.spec.CString us) {
                        out.put(canon.apply(pr.fullPath()), us.value());
                    }
                    return;
                }
                for (var ke : ni.properties().values()) {
                    collectJsonRaw(ke.value(), out, canon);
                }
            }
            case com.legend.model.spec.AppliedFunction af -> {
                for (var p2 : af.parameters()) {
                    collectJsonRaw(p2, out, canon);
                }
            }
            case com.legend.model.spec.LambdaFunction lf -> {
                for (var b2 : lf.body()) {
                    collectJsonRaw(b2, out, canon);
                }
            }
            case com.legend.model.spec.PureCollection pc -> {
                for (var e2 : pc.values()) {
                    collectJsonRaw(e2, out, canon);
                }
            }
            default -> { }
        }
    }

    /** A '+'-folded string literal, null when any part is non-literal. */
    private static @com.legend.Nullable String foldLiteral(@com.legend.Nullable TypedSpec n) {
        if (n instanceof TypedCString cs) {
            return cs.value();
        }
        if (n instanceof TypedNativeCall c
                && c.callee().qualifiedName().endsWith("::plus")) {
            StringBuilder sb = new StringBuilder();
            for (TypedSpec a : c.args()) {
                String part = foldLiteral(a);
                if (part == null) {
                    return null;
                }
                sb.append(part);
            }
            return sb.toString();
        }
        if (n instanceof TypedCollection tc) {
            StringBuilder sb = new StringBuilder();
            for (TypedSpec a : tc.elements()) {
                String part = foldLiteral(a);
                if (part == null) {
                    return null;
                }
                sb.append(part);
            }
            return sb.toString();
        }
        return null;
    }

    /** Mapping FQNs under any {@code ^ModelChainConnection(mappings=[...])}
     * in a runtime-valued expression — the ONE literal walk both consumers
     * share (FromChecker for in-query from(); buildFrame for the execute()
     * runtime argument). Non-literal shapes contribute nothing; their
     * reads wall downstream. */
    public static List<String> chainMappingsIn(TypedSpec n) {
        List<String> out = new java.util.ArrayList<>();
        collectChain(n, out);
        return List.copyOf(out);
    }

    private static void collectChain(TypedSpec n, List<String> out) {
        if (n instanceof TypedNewInstance ni
                && "meta::external::store::model::ModelChainConnection"
                        .equals(ni.classFqn())) {
            TypedSpec ms = ni.properties().get("mappings");
            List<TypedSpec> els = switch (ms) {
                case TypedCollection tc -> tc.elements();
                case null -> List.of();
                default -> List.of(ms);
            };
            for (TypedSpec e : els) {
                if (e instanceof TypedPackageableRef pr) {
                    out.add(pr.fullPath());
                }
            }
            return;
        }
        for (TypedSpec c : n.children()) {
            collectChain(c, out);
        }
    }

    /** Every literal {@code testDataSetupSqls} blob under a runtime-valued
     * expression ({@code ^LocalH2DatasourceSpecification(testDataSetupSqls
     * =[...])}) — the engine executes these when it ESTABLISHES the LocalH2
     * connection; the executor runs them at query execution on the ambient
     * session (same semantics). Helper-built runtimes walk the callee's
     * unchecked body with let-binding resolution ({@code let csvData =
     * '...' + ...; ... testDataSetupSqls=[$csvData]}). */
    public static List<String> sqlSetupsIn(TypedSpec n) {
        List<String> out = new java.util.ArrayList<>();
        collectSqlSetups(n, out);
        return List.copyOf(out);
    }

    private static void collectSqlSetups(TypedSpec n, List<String> out) {
        if (n instanceof TypedUserCall uc && uc.callee().body().isPresent()) {
            java.util.Map<String, com.legend.model.spec.ValueSpecification>
                    lets = new java.util.HashMap<>();
            for (com.legend.model.spec.ValueSpecification b
                    : uc.callee().body().get()) {
                collectSqlSetupsRaw(b, lets, out);
            }
            return;
        }
        if (n instanceof TypedNewInstance ni
                && ("meta::pure::alloy::connections::alloy::specification"
                        + "::LocalH2DatasourceSpecification")
                        .equals(ni.classFqn())) {
            String s = foldLiteral(ni.properties().get("testDataSetupSqls"));
            if (s != null) {
                out.add(s);
            }
            return;
        }
        for (TypedSpec c : n.children()) {
            collectSqlSetups(c, out);
        }
    }

    /** The unchecked-source mirror of {@link #collectSqlSetups}: helper
     * bodies carry the blobs behind lets (bare + FQN class spellings, the
     * collectJsonRaw convention). */
    private static void collectSqlSetupsRaw(
            com.legend.model.spec.ValueSpecification v,
            java.util.Map<String, com.legend.model.spec.ValueSpecification> lets,
            List<String> out) {
        switch (v) {
            case com.legend.model.spec.AppliedFunction af -> {
                if ("letFunction".equals(af.function())
                        && af.parameters().size() == 2
                        && af.parameters().get(0)
                                instanceof com.legend.model.spec.CString nm) {
                    lets.put(nm.value(), af.parameters().get(1));
                }
                for (var p : af.parameters()) {
                    collectSqlSetupsRaw(p, lets, out);
                }
            }
            case com.legend.model.spec.NewInstance ni -> {
                if (ni.className().endsWith("LocalH2DatasourceSpecification")) {
                    var ke = ni.properties().get("testDataSetupSqls");
                    String s = ke == null ? null
                            : foldRawLiteral(ke.value(), lets);
                    if (s != null) {
                        out.add(s);
                    }
                    return;
                }
                for (var ke : ni.properties().values()) {
                    collectSqlSetupsRaw(ke.value(), lets, out);
                }
            }
            case com.legend.model.spec.LambdaFunction lf -> {
                for (var b : lf.body()) {
                    collectSqlSetupsRaw(b, lets, out);
                }
            }
            case com.legend.model.spec.PureCollection pc -> {
                for (var e : pc.values()) {
                    collectSqlSetupsRaw(e, lets, out);
                }
            }
            default -> { }
        }
    }

    /** A raw-spec string literal folded through '+' chains, collections,
     * and let-bound variables; null when any part is non-literal. */
    private static @com.legend.Nullable String foldRawLiteral(
            com.legend.model.spec.ValueSpecification v,
            java.util.Map<String, com.legend.model.spec.ValueSpecification> lets) {
        return switch (v) {
            case com.legend.model.spec.CString cs -> cs.value();
            case com.legend.model.spec.Variable vr -> {
                var bound = lets.get(vr.name());
                yield bound == null ? null : foldRawLiteral(bound, lets);
            }
            case com.legend.model.spec.AppliedFunction af
                    when "plus".equals(af.function()) -> {
                StringBuilder sb = new StringBuilder();
                for (var p : af.parameters()) {
                    String part = foldRawLiteral(p, lets);
                    if (part == null) {
                        yield null;
                    }
                    sb.append(part);
                }
                yield sb.toString();
            }
            case com.legend.model.spec.PureCollection pc -> {
                StringBuilder sb = new StringBuilder();
                for (var e : pc.values()) {
                    String part = foldRawLiteral(e, lets);
                    if (part == null) {
                        yield null;
                    }
                    if (sb.length() > 0) {
                        sb.append('\n');
                    }
                    sb.append(part);
                }
                yield sb.isEmpty() ? null : sb.toString();
            }
            default -> null;
        };
    }

    @Override
    public List<TypedSpec> children() {
        List<TypedSpec> out = new java.util.ArrayList<>();
        out.add(source);
        mapping.ifPresent(out::add);
        runtime.ifPresent(out::add);
        return out;
    }

    @Override
    public TypedSpec withChildren(java.util.List<TypedSpec> kids) {
        int n = 1 + (mapping.isPresent() ? 1 : 0) + (runtime.isPresent() ? 1 : 0);
        TypedSpec.expectChildren(kids, n, "TypedFrom");
        int i = 1;
        java.util.Optional<TypedPackageableRef> m = mapping.isPresent()
                ? java.util.Optional.of((TypedPackageableRef) kids.get(i++))
                : java.util.Optional.empty();
        java.util.Optional<TypedPackageableRef> r = runtime.isPresent()
                ? java.util.Optional.of((TypedPackageableRef) kids.get(i))
                : java.util.Optional.empty();
        return new TypedFrom(kids.get(0), m, r, chainMappings, jsonSources,
                sqlSetups, connectionName, info);
    }
}
