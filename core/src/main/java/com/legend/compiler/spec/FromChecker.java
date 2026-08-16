package com.legend.compiler.spec;

import com.legend.compiler.spec.typed.TypedFrom;
import com.legend.compiler.spec.typed.TypedPackageableRef;
import com.legend.compiler.spec.typed.TypedSpec;
import com.legend.protocol.spec.AppliedFunction;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * {@code from} (engine {@code FromChecker}) &mdash; binds a value to an execution
 * context. Fully generic type-wise (a passthrough: {@code from<T>(Relation<T>[1]
 * [, runtime:Any[1]])} and the M2M {@code from<T>(T[*], mapping, runtime)});
 * every non-source argument must be a packageable-element reference (mapping /
 * runtime), slotted onto the node for the back-end:
 * {@code from(src)} &rarr; (&mdash;, &mdash;); {@code from(src, runtime)} &rarr;
 * (&mdash;, runtime); {@code from(src, mapping, runtime)} &rarr; (mapping, runtime).
 */
final class FromChecker {

    private FromChecker() {
    }

    static TypedSpec check(Typer t, AppliedFunction af, Env env) {
        Application a = t.checkGeneric(af, env);
        List<TypedPackageableRef> refs = new ArrayList<>(a.args().size() - 1);
        List<String> chainMappings = new ArrayList<>();
        String connectionName = null;
        java.util.Map<String, String> jsonSources =
                new java.util.LinkedHashMap<>();
        List<String> sqlSetups = new java.util.ArrayList<>();
        for (int i = 1; i < a.args().size(); i++) {
            if (a.args().get(i) instanceof TypedPackageableRef ref) {
                refs.add(ref);
                continue;
            }
            // helper-CONSTRUCTED runtimes — from(src, mapping,
            // testRuntimeUS()) where the helper builds ^Runtime(
            // connectionStores=...): the instance's CONNECTION content is
            // harness-owned (the module runtime supplies connections for
            // every module Database; connection timezone divergences
            // surface as visible row FAILs), so the execution context
            // reduces to the mapping ref and the runtime SLOT stays
            // EMPTY. A runtime-only from() then walls loudly downstream
            // ("class query requires an execution context"). Anything not
            // statically Runtime-typed stays loud here.
            // EXCEPTION (XStore leg slice 1): a ModelChainConnection inside
            // the instance carries MAPPING FQNs that change RESOLUTION —
            // an M2M mapping's ~src classes resolve THROUGH them. Collect
            // them onto the node; everything else stays harness-owned.
            if (a.args().get(i).info().type()
                    instanceof com.legend.compiler.element.type.Type
                            .ClassType ct
                    && (ct.fqn().equals("meta::core::runtime::Runtime")
                            || t.model().isSubtype(ct.fqn(),
                                    "meta::core::runtime::Runtime"))) {
                chainMappings.addAll(TypedFrom.chainMappingsIn(
                        a.args().get(i)));
                jsonSources.putAll(TypedFrom.jsonSourcesIn(a.args().get(i),
                        t::classFqnOf));
                sqlSetups.addAll(TypedFrom.sqlSetupsIn(a.args().get(i),
                        fq -> t.model().findFunction(fq).stream()
                                .map(com.legend.compiler.element
                                        .TypedFunction::body)
                                .filter(java.util.Optional::isPresent)
                                .map(java.util.Optional::get)
                                .findFirst()));
                if (connectionName == null) {
                    connectionName = TypedFrom.connectionNameIn(
                            a.args().get(i));
                }
                continue;
            }
            throw new TypeInferenceException("from() argument " + i
                    + " must be a mapping or runtime reference, got "
                    + a.args().get(i).getClass().getSimpleName());
        }
        // slotting by KIND when instance-runtimes dropped a ref: a sole
        // surviving ref that is a MAPPING slots as the mapping (the
        // 3-arg from(src, mapping, <instance runtime>) shape)
        boolean soleIsMapping = refs.size() == 1
                && a.args().size() >= 3;
        Optional<TypedPackageableRef> mapping =
                refs.size() >= 2 || soleIsMapping
                        ? Optional.of(refs.get(0)) : Optional.empty();
        Optional<TypedPackageableRef> runtime = switch (refs.size()) {
            case 0 -> Optional.empty();
            case 1 -> soleIsMapping ? Optional.empty()
                    : Optional.of(refs.get(0));
            default -> Optional.of(refs.get(1));
        };
        // QUERY-SIDE chain channel (engine withChainedMappings_T_m__
        // Mapping_MANY__T_m_): source->withChainedMappings([...])->from(rt)
        // — identity on the stream; its mapping refs join chainMappings
        // and the node strips (same channel as ModelChainConnection)
        TypedSpec src = a.args().get(0);
        if (src instanceof com.legend.compiler.spec.typed.TypedNativeCall wc
                && "meta::pure::mapping::withChainedMappings"
                        .equals(wc.callee().qualifiedName())
                && wc.args().size() == 2) {
            collectMappingRefs(wc.args().get(1), chainMappings);
            src = wc.args().get(0);
        }
        return new TypedFrom(src, mapping, runtime,
                List.copyOf(chainMappings),
                java.util.Map.copyOf(jsonSources), List.copyOf(sqlSetups),
                connectionName, a.out());
    }

    private static void collectMappingRefs(TypedSpec n,
            List<String> out) {
        if (n instanceof TypedPackageableRef r) {
            out.add(r.fullPath());
            return;
        }
        for (TypedSpec c : n.children()) {
            collectMappingRefs(c, out);
        }
    }
}
