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
                        ExprType info) implements TypedSpec {

    public TypedFrom(TypedSpec source, Optional<TypedPackageableRef> mapping,
                     Optional<TypedPackageableRef> runtime, ExprType info) {
        this(source, mapping, runtime, List.of(), java.util.Map.of(), info);
    }

    public TypedFrom(TypedSpec source, Optional<TypedPackageableRef> mapping,
                     Optional<TypedPackageableRef> runtime,
                     List<String> chainMappings, ExprType info) {
        this(source, mapping, runtime, chainMappings, java.util.Map.of(), info);
    }

    /** class FQN -> data: URL payload for every
     * {@code ^JsonModelConnection(class=..., url='data:application/json,...')}
     * in a runtime-valued expression — the JSON SOURCE FRAME feed (XStore
     * leg §1). Non-literal shapes contribute nothing. */
    public static java.util.Map<String, String> jsonSourcesIn(TypedSpec n) {
        java.util.Map<String, String> out = new java.util.LinkedHashMap<>();
        collectJson(n, out);
        return java.util.Map.copyOf(out);
    }

    private static void collectJson(TypedSpec n,
            java.util.Map<String, String> out) {
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
            collectJson(c, out);
        }
    }

    /** A '+'-folded string literal, null when any part is non-literal. */
    private static String foldLiteral(TypedSpec n) {
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

    @Override
    public List<TypedSpec> children() {
        List<TypedSpec> out = new java.util.ArrayList<>();
        out.add(source);
        mapping.ifPresent(out::add);
        runtime.ifPresent(out::add);
        return out;
    }
}
