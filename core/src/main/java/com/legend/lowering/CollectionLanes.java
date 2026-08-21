// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend.lowering;

import com.legend.compiler.spec.typed.TypedCBoolean;
import com.legend.compiler.spec.typed.TypedCDate;
import com.legend.compiler.spec.typed.TypedCDecimal;
import com.legend.compiler.spec.typed.TypedCFloat;
import com.legend.compiler.spec.typed.TypedCInteger;
import com.legend.compiler.spec.typed.TypedCString;
import com.legend.compiler.spec.typed.TypedCast;
import com.legend.compiler.spec.typed.TypedCollection;
import com.legend.compiler.spec.typed.TypedConcatenate;
import com.legend.compiler.spec.typed.TypedDistinct;
import com.legend.compiler.spec.typed.TypedDrop;
import com.legend.compiler.spec.typed.TypedEnumValue;
import com.legend.compiler.spec.typed.TypedFilter;
import com.legend.compiler.spec.typed.TypedFrom;
import com.legend.compiler.spec.typed.TypedIf;
import com.legend.compiler.spec.typed.TypedMap;
import com.legend.compiler.spec.typed.TypedNativeCall;
import com.legend.compiler.spec.typed.TypedSlice;
import com.legend.compiler.spec.typed.TypedSort;
import com.legend.compiler.spec.typed.TypedSpec;

/**
 * The TYPED collection-lane decision (COMPILER_SHORTCUT_AUDIT §1a,
 * Blocker 2): {@code toOne}/{@code toOneMany} pick their checked lane
 * from the OPERAND'S TYPED PROVENANCE, never by sniffing the SQL they
 * just emitted. The old evidence procedure ({@code instanceof ArrayLit
 * || Call.producesList()}) shared its blind spots with the stamp
 * census's — {@code slice}/{@code if}/{@code range}/{@code zip} results
 * lowered to shapes neither recognized, and a two-element list flowed
 * back as {@code Integer[1]} with the invariant silent.
 *
 * <p>VALUE lane (pure raising semantics — size != bound raises in the
 * database with pure's message): collections whose provenance is
 * expression-space — literals, natives over value collections,
 * if-branches. ROW lane (the engine's relational {@code processNoOp}
 * flow, ADJUDICATED: SQL cannot tell a NULL cell from an empty):
 * anything rooted in a store/relation read — property navigations,
 * class extents, relation-derived reads, unknown binders. The default
 * is ROW — conservative flow, the engine's own lane.
 */
final class CollectionLanes {

    private CollectionLanes() {
    }

    /** Is this typed collection VALUE-lane (pure raising semantics)? */
    static boolean valueLane(TypedSpec spec) {
        return switch (spec) {
            // a collection LITERAL carries its ELEMENTS' lane: all-value
            // elements are the pure value collection (raising toOne);
            // an element that is a row-correlated read puts the whole
            // literal in the engine's relational lane — the engine
            // compiles `[$p.a, $p.b]->toOne()` as processNoOp flow, and
            // the corpus pins that (witness: the 11-slot milestoned-if
            // collection in testIsolationOfMilestoningFilters...IfStmt;
            // §4 per-lane ruling — engine-relational is the target).
            case TypedCollection c -> c.elements().stream()
                    .allMatch(CollectionLanes::valueLane);
            case TypedCInteger ignored -> true;
            case TypedCFloat ignored -> true;
            case TypedCDecimal ignored -> true;
            case TypedCString ignored -> true;
            case TypedCBoolean ignored -> true;
            case TypedCDate ignored -> true;
            case TypedEnumValue ignored -> true;
            // transforms carry the lane of their collection source
            case TypedIf i -> valueLane(PureSql.thunkBody(i.thenBranch()))
                    && i.elseBranch()
                            .map(e -> valueLane(PureSql.thunkBody(e)))
                            .orElse(true);
            case TypedFrom f -> valueLane(f.source());
            case TypedFilter f -> valueLane(f.source());
            case TypedMap m -> valueLane(m.source());
            case TypedCast c -> valueLane(c.source());
            case TypedSlice s -> valueLane(s.source());
            case TypedSort s -> valueLane(s.source());
            case TypedDistinct d -> valueLane(d.source());
            case TypedDrop d -> valueLane(d.source());
            case TypedConcatenate c ->
                    valueLane(c.left()) && valueLane(c.right());
            // a native call is value-lane iff every MANY-stamped data
            // argument is (vacuously true for scalar-built collections:
            // range(1,5), split) AND every ZERO-PARAM thunk's body is —
            // if() arrives as a NATIVE with thunk lambdas, and the
            // thunks ARE the value sources (corpus witness: the
            // milestoned qualified property's if(..., |'empty',
            // |$this.product($bd).name)->toOne() must FLOW).
            // Parameterized lambdas (filter/map element functions) stay
            // excluded — their collection argument decides the lane.
            case TypedNativeCall nc -> nc.args().stream().allMatch(a ->
                    a instanceof com.legend.compiler.spec.typed.TypedLambda l
                            ? l.parameters().isEmpty()
                                    ? valueLane(l.body()
                                            .get(l.body().size() - 1))
                                    : true
                            : !a.info().multiplicity().isMany()
                                    || valueLane(a));
            // property reads, variables, class extents, relation reads,
            // windows — the ROW lane
            default -> false;
        };
    }

    /** An if whose branch thunks are ALL to-one-stamped lowers on the
     * SCALAR carrier (MixedEncoding.lubCase — a bare CASE), a loose
     * {@code [*]} outer stamp notwithstanding: there is no list to
     * count, and the engine compiles exactly this {@code toOne} as the
     * unguarded CASE (corpus witness: the milestoned qualified property
     * {@code if(...->isEmpty(), |'empty', |...)->toOne()} in
     * testIsolationOfMilestoningFiltersReferencedInAllPartsOfIfStmt).
     * The guard rules FLOW these — identity over a scalar value. Typed
     * facts only; never the emitted SQL. */
    static boolean scalarCarriedIf(TypedSpec spec) {
        java.util.List<TypedSpec> branches = switch (spec) {
            case TypedIf i -> i.elseBranch()
                    .map(e -> java.util.List.of(
                            PureSql.thunkBody(i.thenBranch()),
                            PureSql.thunkBody(e)))
                    .orElseGet(() -> java.util.List.of(
                            PureSql.thunkBody(i.thenBranch())));
            case TypedNativeCall nc when nc.callee().qualifiedName()
                    .equals("meta::pure::functions::lang::if") ->
                    nc.args().stream()
                            .filter(a -> a instanceof
                                    com.legend.compiler.spec.typed.TypedLambda)
                            .map(PureSql::thunkBody)
                            .toList();
            default -> null;
        };
        return branches != null && !branches.isEmpty()
                && branches.stream().allMatch(b ->
                        b.info().multiplicity() instanceof
                                com.legend.compiler.element.type
                                        .Multiplicity.Bounded bb
                        && bb.upper() != null && bb.upper() <= 1);
    }
}
