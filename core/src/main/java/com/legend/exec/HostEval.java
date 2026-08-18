// SPDX-License-Identifier: Apache-2.0

package com.legend.exec;

import com.legend.compiler.element.type.PlatformTypes;
import com.legend.compiler.spec.typed.TypedCInteger;
import com.legend.compiler.spec.typed.TypedCString;
import com.legend.compiler.spec.typed.TypedCollection;
import com.legend.compiler.spec.typed.TypedFold;
import com.legend.compiler.spec.typed.TypedLambda;
import com.legend.compiler.spec.typed.TypedMap;
import com.legend.compiler.spec.typed.TypedMatchRuntime;
import com.legend.compiler.spec.typed.TypedNewInstance;
import com.legend.compiler.spec.typed.TypedCopyInstance;
import com.legend.compiler.spec.typed.TypedCast;
import com.legend.compiler.spec.typed.TypedUserCall;
import com.legend.compiler.spec.typed.TypedLet;
import com.legend.compiler.spec.typed.TypedIf;
import com.legend.compiler.spec.typed.TypedFilter;
import com.legend.compiler.spec.typed.TypedCBoolean;
import com.legend.compiler.spec.typed.TypedCFloat;
import com.legend.compiler.spec.typed.TypedCDecimal;
import com.legend.compiler.spec.typed.TypedSlice;
import com.legend.compiler.spec.typed.TypedNativeCall;
import com.legend.compiler.spec.typed.TypedPropertyAccess;
import com.legend.compiler.spec.typed.TypedSpec;
import com.legend.compiler.spec.typed.TypedVariable;
import com.legend.error.NotImplementedException;
import com.legend.model.DatabaseDefinition;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The HOST-CHANNEL ROUTING PREDICATE — all that remains of the deleted
 * interpreter (oracle-not-runtime principle, user-ratified 2026-08-18:
 * engine and legend-pure code is ORACLE material — models, goldens,
 * parity checks — and NEVER executes in our runtime; the evaluator that
 * interpreted engine compiler source is GONE). {@code wantsHostEval}
 * still classifies expressions whose chains bottom at grid/store-nav/
 * construction vocabulary so the seams can dispatch them: recognized
 * grid-read chains compile into SQL ({@code GridReads}), store
 * navigation resolves against the compiled model ({@code StoreNav}),
 * and everything else walls loudly with the principle's name.
 */
public final class HostEval {

    private HostEval() {
    }

    /** Does this expression READ a fetchDb/executeInDb grid? fetchDb
     * anywhere in the tree routes (its only corpus shapes are grid
     * reads); executeInDb routes ONLY when the expression's PRIMARY
     * SOURCE CHAIN bottoms out at the call — ordinary setups carry
     * executeInDb deep inside spliced trees that the SQL pipeline owns
     * (routing on containment collapsed modelJoin/testDataGeneration:
     * this predicate is the fix's pin). The ROOT-position executeInDb
     * SETUP arm dispatches first in executeTyped. */
    public static boolean wantsHostEval(TypedSpec root) {
        return wantsHostEval(root, Map.of());
    }

    /** Lets-aware dispatch: variables in the chain resolve through the
     * enclosing (typed, unevaluated) let bindings. */
    public static boolean wantsHostEval(TypedSpec root,
            Map<String, TypedSpec> lets) {
        TypedSpec bottom = chainBottom(root, lets);
        if (bottom instanceof TypedNativeCall b
                && (PlatformTypes.EXECUTE_IN_DB
                        .equals(b.callee().qualifiedName())
                        || PlatformTypes.isStoreNavFn(
                                b.callee().qualifiedName()))) {
            return true;
        }
        if (bottom instanceof TypedNewInstance ni
                && hostConstruction(ni.classFqn())) {
            return true;
        }
        return containsFetchDb(root);
    }

    /** The ^Class(...) constructions the HOST channel owns — a CURATED
     * set that grows deliberately per slice ("any native class" stole
     * sqlDialectTranslation's 21 previously-passing constructions from
     * the K path — the gate caught it). */
    static final java.util.Set<String> HOST_CONSTRUCTION_CLASSES =   // F1.11: pinned by HostChannelPredicateTest
            java.util.Set.of(
                    "meta::relational::metamodel::DynaFunction",
                    "meta::relational::metamodel::Literal",
                    "meta::relational::metamodel::Alias",
                    "meta::relational::functions::pureToSqlQuery::metamodel"
                            + "::FreeMarkerOperationHolder",
                    "meta::relational::functions::pureToSqlQuery::metamodel"
                            + "::VarPlaceHolder");

    private static boolean hostConstruction(String classFqn) {
        return HOST_CONSTRUCTION_CLASSES.contains(classFqn);
    }

    private static boolean containsFetchDb(TypedSpec root) {
        if (root instanceof TypedNativeCall nc
                && PlatformTypes.isFetchDbFn(nc.callee().qualifiedName())) {
            return true;
        }
        for (TypedSpec c : root.children()) {
            if (containsFetchDb(c)) {
                return true;
            }
        }
        return false;
    }

    private static final java.util.Set<String> READ_CHAIN_FNS =
            java.util.Set.of(
                    "meta::pure::functions::collection::fold",
                    "meta::pure::functions::collection::map",
                    "meta::pure::functions::collection::concatenate",
                    "meta::pure::functions::collection::at",
                    "meta::pure::functions::collection::first",
                    "meta::pure::functions::collection::size",
                    "meta::pure::functions::collection::indexOf",
                    "meta::pure::functions::multiplicity::toOne",
                    "meta::pure::functions::string::toString");

    /** Walk the primary source chain (property access sources, fold/map
     * sources, READ-shaped collection-native first args, user-call and
     * match receivers, let-bound variables) to the expression's root. */
    private static TypedSpec chainBottom(TypedSpec n) {
        return chainBottom(n, Map.of());
    }

    private static TypedSpec chainBottom(TypedSpec n,
            Map<String, TypedSpec> lets) {
        while (true) {
            switch (n) {
                case TypedPropertyAccess pa -> n = pa.source();
                case TypedFold f -> n = f.source();
                case TypedMap m -> n = m.source();
                case TypedUserCall uc -> {
                    if (uc.args().isEmpty()) {
                        return uc;
                    }
                    n = uc.args().get(0);
                }
                case TypedMatchRuntime mr -> n = mr.input();
                case TypedCast tc -> n = tc.source();
                case TypedLet l -> n = l.value();
                case TypedVariable v -> {
                    TypedSpec bound = lets.get(v.name());
                    if (bound == null) {
                        return v;
                    }
                    n = bound;
                }
                case TypedNativeCall nc -> {
                    String fqn = nc.callee().qualifiedName();
                    if (PlatformTypes.EXECUTE_IN_DB.equals(fqn)
                            || PlatformTypes.isFetchDbFn(fqn)
                            || PlatformTypes.isStoreNavFn(fqn)) {
                        return nc;
                    }
                    // walk ONLY through the READ-shaped natives this
                    // evaluator implements — an arbitrary call's first
                    // argument is not a source chain (println(executeInDb)
                    // is a SETUP statement the print arm owns; stealing
                    // it rerouted the effect off the ambient connection)
                    if (nc.args().isEmpty() || !READ_CHAIN_FNS.contains(fqn)) {
                        return nc;
                    }
                    n = nc.args().get(0);
                }
                default -> {
                    return n;
                }
            }
        }
    }

}
