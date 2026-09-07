// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend.compiler.spec.typed;

import com.legend.compiler.element.type.PlatformTypes;
import com.legend.protocol.spec.ValueSpecification;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.UnaryOperator;

/**
 * The body of {@link ExecutionContext.Reader#read}: the ONE place that knows
 * the shape of the engine's runtime classes ({@code connectionStores},
 * {@code connection}, {@code mappings}, {@code testDataSetupSqls},
 * {@code testDataSetupCsv}, {@code quoteIdentifiers}, {@code timeZone},
 * {@code type}, {@code element}, {@code url}) — over a typed value, a
 * let-bound variable (chased through {@code bind}) or a helper call's raw
 * body (through {@code fnBody}; nested helpers depth-capped). Package-
 * private: readers are minted through {@link ExecutionContext#reader()}.
 */
final class ContextReading {
    private final Function<String, Optional<List<ValueSpecification>>> fnBody;
    private final UnaryOperator<TypedSpec> bind;
    private final UnaryOperator<String> canon;
    private final Function<TypedCopyInstance, @com.legend.Nullable String> dbOfCopy;

    ContextReading(Function<String, Optional<List<ValueSpecification>>> fnBody,
            UnaryOperator<TypedSpec> bind, UnaryOperator<String> canon,
            Function<TypedCopyInstance, @com.legend.Nullable String> dbOfCopy) {
        this.fnBody = fnBody;
        this.bind = bind;
        this.canon = canon;
        this.dbOfCopy = dbOfCopy;
    }

    ExecutionContext read(Optional<TypedPackageableRef> mapping,
            @com.legend.Nullable TypedSpec runtimeArg) {
        if (runtimeArg == null) {
            return ExecutionContext.of(mapping, Optional.empty());
        }
        if (runtimeArg instanceof TypedPackageableRef ref) {
            return ExecutionContext.of(mapping, Optional.of(ref));
        }
        List<String> chain = new ArrayList<>();
        collectChain(runtimeArg, chain);
        Map<String, String> json = new LinkedHashMap<>();
        collectJson(runtimeArg, json);
        List<String> sql = new ArrayList<>();
        List<ExecutionContext.CsvSetup> csv = new ArrayList<>();
        collectSetups(runtimeArg, sql, csv, null);
        TypedNewInstance conn = connectionInstance(runtimeArg);
        return new ExecutionContext(mapping, Optional.empty(), chain, json, sql, csv,
                connectionName(runtimeArg), quoteIdentifiers(runtimeArg),
                timeZone(runtimeArg),
                conn == null ? null : databaseType(conn), conn,
                storeFqn(runtimeArg), false);
    }

    /** {@code addDriverTablePkForProject} off an execute call's ExecutionContext
     * argument — a RelationalExecutionContext instance (let-bound or literal)
     * whose flag is a literal true; anything else is the default (false). */
    static boolean driverTablePkOf(@com.legend.Nullable TypedSpec contextArg,
            UnaryOperator<TypedSpec> bind) {
        if (contextArg == null) {
            return false;
        }
        TypedSpec v = bind.apply(contextArg);
        return v instanceof TypedNewInstance ni
                && PlatformTypes.RELATIONAL_EXECUTION_CONTEXT.equals(ni.classFqn())
                && ni.properties().get("addDriverTablePkForProject") instanceof TypedCBoolean b
                && b.value();
    }

    private TypedSpec chase(TypedSpec v) {
        TypedSpec b = bind.apply(v);
        return b == null ? v : b;
    }

    // ---- chain mappings -------------------------------------------

    private void collectChain(TypedSpec n, List<String> out) {
        if (n instanceof TypedVariable) {
            TypedSpec b = chase(n);
            if (b != n) {
                collectChain(b, out);
            }
            return;
        }
        if (n instanceof TypedUserCall uc && uc.callee().body().isPresent()) {
            // a helper-built runtime: its ModelChainConnection lives in
            // the callee's raw body (nested helpers through fnBody)
            for (ValueSpecification b : uc.callee().body().get()) {
                collectChainRaw(b, out, 0);
            }
            return;
        }
        if (n instanceof TypedNewInstance ni
                && PlatformTypes.MODEL_CHAIN_CONNECTION.equals(ni.classFqn())) {
            TypedSpec ms = ni.properties().get("mappings");
            List<TypedSpec> els = switch (ms) {
                case TypedCollection tc -> tc.elements();
                case null -> List.of();
                default -> List.of(ms);
            };
            for (TypedSpec e : els) {
                if (e instanceof TypedPackageableRef pr && !out.contains(pr.fullPath())) {
                    out.add(pr.fullPath());
                }
            }
            return;
        }
        for (TypedSpec c : n.children()) {
            collectChain(c, out);
        }
    }

    private void collectChainRaw(ValueSpecification v, List<String> out, int depth) {
        switch (v) {
            case com.legend.protocol.spec.NewInstance ni -> {
                if (PlatformTypes.isModelChainConnection(ni.className())) {
                    var ms = ni.first("mappings");
                    List<ValueSpecification> els = ms == null ? List.of()
                            : ms.value() instanceof com.legend.protocol.spec.PureCollection pc
                                    ? pc.values() : List.of(ms.value());
                    for (ValueSpecification e : els) {
                        if (e instanceof com.legend.protocol.spec.PackageableElementPtr pr
                                && !out.contains(canon.apply(pr.fullPath()))) {
                            out.add(canon.apply(pr.fullPath()));
                        }
                    }
                    return;
                }
                for (var ke : ni.properties().stream()
                        .map(com.legend.protocol.spec.NewInstance.KeyBinding::expression)
                        .toList()) {
                    collectChainRaw(ke.value(), out, depth);
                }
            }
            case com.legend.protocol.spec.AppliedFunction af -> {
                for (var p : af.parameters()) {
                    collectChainRaw(p, out, depth);
                }
                if (depth < 3 && !"letFunction".equals(af.function())) {
                    var body = fnBody.apply(af.function());
                    if (body.isPresent()) {
                        for (var b : body.get()) {
                            collectChainRaw(b, out, depth + 1);
                        }
                    }
                }
            }
            case com.legend.protocol.spec.LambdaFunction lf -> {
                for (var b : lf.body()) {
                    collectChainRaw(b, out, depth);
                }
            }
            case com.legend.protocol.spec.PureCollection pc -> {
                for (var e : pc.values()) {
                    collectChainRaw(e, out, depth);
                }
            }
            default -> { }
        }
    }

    // ---- JSON sources ---------------------------------------------

    private void collectJson(TypedSpec n, Map<String, String> out) {
        if (n instanceof TypedVariable) {
            TypedSpec b = chase(n);
            if (b != n) {
                collectJson(b, out);
            }
            return;
        }
        if (n instanceof TypedUserCall uc && uc.callee().body().isPresent()) {
            for (ValueSpecification b : uc.callee().body().get()) {
                collectJsonRaw(b, out);
            }
            return;
        }
        if (n instanceof TypedNewInstance ni
                && PlatformTypes.JSON_MODEL_CONNECTION.equals(ni.classFqn())) {
            TypedSpec cls = ni.properties().get("class");
            String url = foldLiteral(ni.properties().get("url"));
            if (cls instanceof TypedPackageableRef pr && url != null) {
                out.put(pr.fullPath(), url);
            }
            return;
        }
        for (TypedSpec c : n.children()) {
            collectJson(c, out);
        }
    }

    private void collectJsonRaw(ValueSpecification v, Map<String, String> out) {
        switch (v) {
            case com.legend.protocol.spec.NewInstance ni -> {
                if (PlatformTypes.isJsonModelConnection(ni.className())) {
                    var cls = ni.first("class");
                    var url = ni.first("url");
                    if (cls != null && cls.value()
                            instanceof com.legend.protocol.spec.PackageableElementPtr pr
                            && url != null && url.value()
                                    instanceof com.legend.protocol.spec.CString us) {
                        out.put(canon.apply(pr.fullPath()), us.value());
                    }
                    return;
                }
                for (var ke : ni.properties().stream()
                        .map(com.legend.protocol.spec.NewInstance.KeyBinding::expression)
                        .toList()) {
                    collectJsonRaw(ke.value(), out);
                }
            }
            case com.legend.protocol.spec.AppliedFunction af -> {
                for (var p2 : af.parameters()) {
                    collectJsonRaw(p2, out);
                }
            }
            case com.legend.protocol.spec.LambdaFunction lf -> {
                for (var b2 : lf.body()) {
                    collectJsonRaw(b2, out);
                }
            }
            case com.legend.protocol.spec.PureCollection pc -> {
                for (var e2 : pc.values()) {
                    collectJsonRaw(e2, out);
                }
            }
            default -> { }
        }
    }

    // ---- setup SQL / CSV -------------------------------------------

    private void collectSetups(TypedSpec n, List<String> out, List<ExecutionContext.CsvSetup> csv,
            @com.legend.Nullable String dbRef) {
        if (n instanceof TypedVariable) {
            TypedSpec b = chase(n);
            if (b != n) {
                collectSetups(b, out, csv, dbRef);
            }
            return;
        }
        if (n instanceof TypedUserCall uc && uc.callee().body().isPresent()) {
            Map<String, ValueSpecification> lets = new java.util.HashMap<>();
            for (ValueSpecification b : uc.callee().body().get()) {
                collectSetupsRaw(b, lets, out, 0, csv, dbRef);
            }
            return;
        }
        if (n instanceof TypedNewInstance ni) {
            String db = ni.properties().get("element")
                    instanceof TypedPackageableRef el ? el.fullPath() : dbRef;
            if (PlatformTypes.LOCAL_H2_DATASOURCE_SPECIFICATION.equals(ni.classFqn())) {
                String s = foldLiteral(ni.properties().get("testDataSetupSqls"));
                if (s != null) {
                    out.add(s);
                }
            }
            String csvText = foldLiteral(ni.properties().get("testDataSetupCsv"));
            if (csvText != null) {
                csv.add(new ExecutionContext.CsvSetup(csvText, db));
            }
            for (TypedSpec c : n.children()) {
                collectSetups(c, out, csv, db);
            }
            return;
        }
        if (n instanceof TypedCopyInstance cp
                && foldLiteral(cp.overrides().get("testDataSetupCsv")) instanceof String c2) {
            csv.add(new ExecutionContext.CsvSetup(c2, dbOfCopy.apply(cp)));
        }
        for (TypedSpec c : n.children()) {
            collectSetups(c, out, csv, dbRef);
        }
    }

    /** The unchecked-source mirror of {@link #collectSetups}: helper
     * bodies carry the blobs behind lets; a nested helper call expands
     * its body in a fresh let scope (depth-capped). */
    private void collectSetupsRaw(ValueSpecification v,
            Map<String, ValueSpecification> lets, List<String> out, int depth,
            List<ExecutionContext.CsvSetup> csv, @com.legend.Nullable String dbRef) {
        switch (v) {
            case com.legend.protocol.spec.AppliedFunction af -> {
                if ("letFunction".equals(af.function())
                        && af.parameters().size() == 2
                        && af.parameters().get(0)
                                instanceof com.legend.protocol.spec.CString nm) {
                    lets.put(nm.value(), af.parameters().get(1));
                }
                for (var p : af.parameters()) {
                    collectSetupsRaw(p, lets, out, depth, csv, dbRef);
                }
                if (depth < 3 && !"letFunction".equals(af.function())) {
                    var body = fnBody.apply(af.function());
                    if (body.isPresent()) {
                        Map<String, ValueSpecification> inner = new java.util.HashMap<>();
                        for (var b : body.get()) {
                            collectSetupsRaw(b, inner, out, depth + 1, csv, dbRef);
                        }
                    }
                }
            }
            case com.legend.protocol.spec.NewInstance ni -> {
                var el = ni.first("element");
                String db = el != null && el.value()
                        instanceof com.legend.protocol.spec.PackageableElementPtr ptr
                        ? ptr.fullPath() : dbRef;
                if (PlatformTypes.isLocalH2DatasourceSpecification(ni.className())) {
                    var ke = ni.first("testDataSetupSqls");
                    String s = ke == null ? null : foldRawLiteral(ke.value(), lets);
                    if (s != null) {
                        out.add(s);
                    }
                }
                var kc = ni.first("testDataSetupCsv");
                String c = kc == null ? null : foldRawLiteral(kc.value(), lets);
                if (c != null) {
                    csv.add(new ExecutionContext.CsvSetup(c, db));
                }
                for (var ke : ni.properties().stream()
                        .map(com.legend.protocol.spec.NewInstance.KeyBinding::expression)
                        .toList()) {
                    collectSetupsRaw(ke.value(), lets, out, depth, csv, db);
                }
            }
            case com.legend.protocol.spec.LambdaFunction lf -> {
                for (var b : lf.body()) {
                    collectSetupsRaw(b, lets, out, depth, csv, dbRef);
                }
            }
            case com.legend.protocol.spec.PureCollection pc -> {
                for (var e : pc.values()) {
                    collectSetupsRaw(e, lets, out, depth, csv, dbRef);
                }
            }
            default -> { }
        }
    }

    // ---- connection flags -----------------------------------------

    /** The FIRST connection instance under the value, or null. */
    private @com.legend.Nullable TypedNewInstance connectionInstance(TypedSpec runtimeArg) {
        ArrayDeque<TypedSpec> work = new ArrayDeque<>();
        work.add(runtimeArg);
        while (!work.isEmpty()) {
            TypedSpec t = work.poll();
            if (t instanceof TypedVariable) {
                TypedSpec b = chase(t);
                if (b != t) {
                    work.add(b);
                }
                continue;
            }
            if (t instanceof TypedNewInstance ni
                    && PlatformTypes.isRelationalConnectionClass(ni.classFqn())) {
                return ni;
            }
            work.addAll(t.children());
        }
        return null;
    }

    /** The first ConnectionStore's {@code element} store reference, or null. */
    private @com.legend.Nullable String storeFqn(TypedSpec runtimeArg) {
        ArrayDeque<TypedSpec> work = new ArrayDeque<>();
        work.add(runtimeArg);
        while (!work.isEmpty()) {
            TypedSpec t = work.poll();
            if (t instanceof TypedVariable) {
                TypedSpec b = chase(t);
                if (b != t) {
                    work.add(b);
                }
                continue;
            }
            if (t instanceof TypedNewInstance ni
                    && PlatformTypes.CONNECTION_STORE.equals(ni.classFqn())
                    && ni.properties().get("element") instanceof TypedPackageableRef pr) {
                return pr.fullPath();
            }
            work.addAll(t.children());
        }
        return null;
    }

    /** The connection's DatabaseType name ("H2" when unspelled). */
    static String databaseType(TypedNewInstance conn) {
        return conn.properties().get("type") instanceof TypedEnumValue ev
                ? String.valueOf(ev.value()) : "H2";
    }

    /** The connection's plan-text spelling ({@code DatabaseConnection(type =
     * "DB2")}): the instance's class simple name with its DatabaseType;
     * a helper-constructed runtime's instance lives in the callee's raw
     * body. Null when no connection instance appears. */
    private @com.legend.Nullable String connectionName(TypedSpec n) {
        if (n instanceof TypedVariable) {
            TypedSpec b = chase(n);
            return b == n ? null : connectionName(b);
        }
        if (n instanceof TypedNewInstance ni) {
            String simple = PlatformTypes.relationalConnectionSimpleName(ni.classFqn());
            if (simple != null) {
                return simple + "(type = \"" + databaseType(ni) + "\")";
            }
        }
        if (n instanceof TypedUserCall uc && uc.callee().body().isPresent()) {
            for (ValueSpecification b : uc.callee().body().get()) {
                String r = rawConnectionName(b);
                if (r != null) {
                    return r;
                }
            }
        }
        for (TypedSpec c : n.children()) {
            String r = connectionName(c);
            if (r != null) {
                return r;
            }
        }
        return null;
    }

    private @com.legend.Nullable String rawConnectionName(ValueSpecification n) {
        if (n instanceof com.legend.protocol.spec.NewInstance ni) {
            String simple = PlatformTypes.relationalConnectionSimpleName(ni.className());
            if (simple != null) {
                com.legend.protocol.spec.KeyExpression ke = ni.first("type");
                String db = ke != null && ke.value()
                        instanceof com.legend.protocol.spec.EnumValue ev
                        ? ev.value() : "H2";
                return simple + "(type = \"" + db + "\")";
            }
        }
        List<ValueSpecification> kids = switch (n) {
            case com.legend.protocol.spec.AppliedFunction af -> af.parameters();
            case com.legend.protocol.spec.NewInstance ni2 -> ni2.properties().stream()
                    .map(b -> b.expression().value()).toList();
            case com.legend.protocol.spec.PureCollection pc -> pc.values();
            case com.legend.protocol.spec.LambdaFunction lf -> lf.body();
            default -> List.of();
        };
        for (ValueSpecification c : kids) {
            String r = rawConnectionName(c);
            if (r != null) {
                return r;
            }
        }
        return null;
    }

    /** {@code quoteIdentifiers} off a connection instance; the platform-
     * native {@code testRuntime(quoteIdentifiers)} overload carries the
     * flag as its argument (the corpus contract, relationalSetUp.pure). */
    private boolean quoteIdentifiers(TypedSpec runtimeArg) {
        ArrayDeque<TypedSpec> work = new ArrayDeque<>();
        work.add(runtimeArg);
        while (!work.isEmpty()) {
            TypedSpec t = work.poll();
            if (t instanceof TypedVariable) {
                TypedSpec b = chase(t);
                if (b != t) {
                    work.add(b);
                }
                continue;
            }
            if (t instanceof TypedNewInstance ni
                    && ni.properties().get("quoteIdentifiers") instanceof TypedSpec qv) {
                Boolean b2 = staticBool(qv);
                if (b2 != null) {
                    return b2;
                }
            }
            if (t instanceof TypedNativeCall nc
                    && PlatformTypes.TEST_RUNTIME.equals(nc.callee().qualifiedName())
                    && nc.args().size() == 1
                    && nc.args().get(0) instanceof TypedCBoolean fb) {
                return fb.value();
            }
            work.addAll(t.children());
        }
        return false;
    }

    /** The connection's {@code timeZone}: the property may be a helper's
     * parameter bound through lets — chased through {@link #bind}. */
    private @com.legend.Nullable String timeZone(TypedSpec runtimeArg) {
        ArrayDeque<TypedSpec> work = new ArrayDeque<>();
        work.add(runtimeArg);
        java.util.Set<TypedSpec> seen = java.util.Collections.newSetFromMap(
                new java.util.IdentityHashMap<>());
        while (!work.isEmpty()) {
            TypedSpec t = work.poll();
            if (!seen.add(t)) {
                continue;
            }
            if (t instanceof TypedVariable) {
                TypedSpec b = chase(t);
                if (b != t) {
                    work.add(b);
                }
                continue;
            }
            if (t instanceof TypedNewInstance ni
                    && ni.properties().get("timeZone") != null) {
                TypedSpec tzv = chase(ni.properties().get("timeZone"));
                if (tzv instanceof TypedCString tzs) {
                    return tzs.value();
                }
            }
            work.addAll(t.children());
        }
        return null;
    }

    /** Bounded constant-fold of the corpus connection-builder idiom
     * ({@code if($q->isEmpty(), |false, |$q->toOne())} over an inlined
     * literal). Null = not statically known; never guesses. */
    private static @com.legend.Nullable Boolean staticBool(TypedSpec t) {
        return switch (t) {
            case TypedCBoolean b -> b.value();
            case TypedNativeCall nc
                    when com.legend.builtin.Pure.isToOneCall(nc.callee().qualifiedName())
                    && nc.args().size() >= 1 -> staticBool(nc.args().get(0));
            case TypedIf i -> {
                Boolean empt = staticIsEmpty(i.condition());
                if (empt == null) {
                    yield null;
                }
                TypedSpec branch = empt ? i.thenBranch() : i.elseBranch().orElse(null);
                if (branch instanceof TypedLambda l && !l.body().isEmpty()) {
                    branch = l.body().get(l.body().size() - 1);
                }
                yield branch == null ? null : staticBool(branch);
            }
            default -> null;
        };
    }

    private static @com.legend.Nullable Boolean staticIsEmpty(TypedSpec cond) {
        if (!(cond instanceof TypedNativeCall nc
                && PlatformTypes.IS_EMPTY.equals(nc.callee().qualifiedName())
                && nc.args().size() == 1)) {
            return null;
        }
        TypedSpec x = nc.args().get(0);
        if (x instanceof TypedCollection c) {
            return c.elements().isEmpty();
        }
        if (x instanceof TypedCBoolean || x instanceof TypedCString
                || x instanceof TypedCInteger) {
            return false;
        }
        return null;
    }

    // ---- literal folding -------------------------------------------

    /** A '+'-folded string literal, null when any part is non-literal. */
    private static @com.legend.Nullable String foldLiteral(@com.legend.Nullable TypedSpec n) {
        if (n instanceof TypedCString cs) {
            return cs.value();
        }
        if (n instanceof TypedNativeCall c
                && PlatformTypes.PLUS.equals(c.callee().qualifiedName())) {
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

    /** A raw-spec string literal folded through '+' chains, collections
     * and let-bound variables; null when any part is non-literal. */
    private static @com.legend.Nullable String foldRawLiteral(ValueSpecification v,
            Map<String, ValueSpecification> lets) {
        return switch (v) {
            case com.legend.protocol.spec.CString cs -> cs.value();
            case com.legend.protocol.spec.Variable vr -> {
                var bound = lets.get(vr.name());
                yield bound == null ? null : foldRawLiteral(bound, lets);
            }
            case com.legend.protocol.spec.AppliedFunction af
                    when PlatformTypes.isPlus(af.function()) -> {
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
            case com.legend.protocol.spec.PureCollection pc -> {
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
}
