// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend.compiler.spec;

import com.legend.compiler.element.type.ExprType;
import com.legend.compiler.element.type.Type;
import com.legend.compiler.spec.typed.TypedScanRelations;
import com.legend.compiler.spec.typed.TypedSpec;
import com.legend.protocol.spec.AppliedFunction;
import com.legend.protocol.spec.LambdaFunction;
import com.legend.protocol.spec.PackageableElementPtr;
import com.legend.protocol.spec.ValueSpecification;

/**
 * The lineage scan surface (engine {@code scanRelations.pure}) — the
 * platform-owned pair: {@code scanRelations(query, mapping[, runtime],
 * extensions)} captures a CARRIER (compile-time model reflection, no
 * database — the TDG-S1 precedent) and {@code relationTreeAsString(tree
 * [, withJoin])} FOLDS it through the platform's native
 * {@link com.legend.lineage.ScanRelations} to the engine-exact tree
 * text. The engine pure bodies are the SPEC (verified by signature,
 * suppressed as definitions — they metaprogram over FunctionDefinition,
 * the quarantined vocabulary). Walk parity: {@code LineageRelationsForm}
 * verifies the same native against the same goldens.
 */
final class ScanRelationsChecker {

    private ScanRelationsChecker() {
    }

    static final String RELATION_TREE_FQN =
            "meta::pure::lineage::scanRelations::RelationTree";

    static TypedSpec scan(Typer t, AppliedFunction af, Env env) {
        // corpus spellings (LineageRelationsForm parity): 3-arg
        // (query, mapping, extensions) = STATIC scan; 4-arg
        // (query, mapping, runtime, extensions) = RUNTIME scan. The
        // query lambda and mapping ref resolve through the let-alias
        // channel (bind-once) and the lambda CLOSES over in-scope lets.
        int n = af.parameters().size();
        ValueSpecification q = n >= 3
                ? env.resolveAlias(af.parameters().get(0)) : null;
        ValueSpecification m = n >= 3
                ? env.resolveAlias(af.parameters().get(1)) : null;
        if ((n == 3 || n == 4)
                && q instanceof LambdaFunction ql && ql.parameters().isEmpty()
                && m instanceof PackageableElementPtr mp) {
            LambdaFunction closed = env.aliases().isEmpty() ? ql
                    : (LambdaFunction) SourceSubst.substitute(ql, env.aliases());
            return new TypedScanRelations(closed, mp.fullPath(), n == 4,
                    ExprType.one(new Type.ClassType(RELATION_TREE_FQN)));
        }
        throw new TypeInferenceException("scanRelations needs its query"
                + " lambda and mapping reference (inline or let-bound;"
                + " 3-arg static / 4-arg runtime scan)");
    }

    static TypedSpec treeString(Typer t, AppliedFunction af, Env env) {
        ValueSpecification r = unwrap(env.resolveAlias(af.parameters().get(0)), env);
        if (!(r instanceof AppliedFunction sc)
                || CoreFn.of(sc.function()).orElse(null) != CoreFn.SCAN_RELATIONS) {
            throw new TypeInferenceException("relationTreeAsString folds at"
                    + " CHECK time and needs a scanRelations chain as its"
                    + " receiver (inline or let-bound)");
        }
        TypedScanRelations carrier = (TypedScanRelations) scan(t, sc, env);
        // label arg (LineageRelationsForm parity): no-arg = JOIN names,
        // false = NO labels, true = the condition mangle
        boolean showLabels = !(af.parameters().size() >= 2
                && af.parameters().get(1)
                        instanceof com.legend.protocol.spec.CBoolean b
                && !b.value());
        String s;
        try {
            s = com.legend.lineage.ScanRelations.treeString(t.model(),
                    carrier.query(), carrier.mappingFqn(),
                    carrier.runtimeVariant(), showLabels);
        } catch (com.legend.error.NotImplementedException e) {
            // the scanner's own vocabulary walls stay walls — re-thrown
            // in the typing channel so the census counts them by reason
            throw new TypeInferenceException("scanRelations: "
                    + String.valueOf(e.getMessage()).split("\\n")[0]);
        }
        return new com.legend.compiler.spec.typed.TypedCString(s,
                ExprType.one(Type.Primitive.STRING));
    }

    /** Unwrap the tree-position plumbing between the scan call and its
     * consumer: {@code ->toOne()}, {@code ->cast(@...)}, and let-alias
     * hops. */
    private static ValueSpecification unwrap(ValueSpecification v, Env env) {
        while (true) {
            ValueSpecification r = env.resolveAlias(v);
            if (r instanceof AppliedFunction af
                    && !af.parameters().isEmpty()) {
                String n = af.function();
                int idx = n.lastIndexOf("::");
                String bare = idx < 0 ? n : n.substring(idx + 2);
                if (bare.equals("toOne") || bare.equals("cast")) {
                    v = af.parameters().get(0);
                    continue;
                }
            }
            return r;
        }
    }
}
