// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend.lowering;

import com.legend.compiler.spec.typed.TypedAggColSpec;
import com.legend.compiler.spec.typed.TypedAggColSpecArray;
import com.legend.compiler.spec.typed.TypedAggregate;
import com.legend.compiler.spec.typed.TypedAsOfJoin;
import com.legend.compiler.spec.typed.TypedCBoolean;
import com.legend.compiler.spec.typed.TypedCDate;
import com.legend.compiler.spec.typed.TypedCDecimal;
import com.legend.compiler.spec.typed.TypedCFloat;
import com.legend.compiler.spec.typed.TypedCInteger;
import com.legend.compiler.spec.typed.TypedCLatestDate;
import com.legend.compiler.spec.typed.TypedCString;
import com.legend.compiler.spec.typed.TypedCTime;
import com.legend.compiler.spec.typed.TypedCast;
import com.legend.compiler.spec.typed.TypedColSpec;
import com.legend.compiler.spec.typed.TypedColSpecArray;
import com.legend.compiler.spec.typed.TypedCollection;
import com.legend.compiler.spec.typed.TypedCollectionRelation;
import com.legend.compiler.spec.typed.TypedConcatenate;
import com.legend.compiler.spec.typed.TypedCopyInstance;
import com.legend.compiler.spec.typed.TypedDistinct;
import com.legend.compiler.spec.typed.TypedDrop;
import com.legend.compiler.spec.typed.TypedEnumValue;
import com.legend.compiler.spec.typed.TypedEval;
import com.legend.compiler.spec.typed.TypedExtend;
import com.legend.compiler.spec.typed.TypedExtendAgg;
import com.legend.compiler.spec.typed.TypedExtendWindow;
import com.legend.compiler.spec.typed.TypedFilter;
import com.legend.compiler.spec.typed.TypedFlatten;
import com.legend.compiler.spec.typed.TypedFold;
import com.legend.compiler.spec.typed.TypedFrom;
import com.legend.compiler.spec.typed.TypedFuncColSpec;
import com.legend.compiler.spec.typed.TypedFuncColSpecArray;
import com.legend.compiler.spec.typed.TypedGetAll;
import com.legend.compiler.spec.typed.TypedGraphFetch;
import com.legend.compiler.spec.typed.TypedGroupBy;
import com.legend.compiler.spec.typed.TypedIf;
import com.legend.compiler.spec.typed.TypedJoin;
import com.legend.compiler.spec.typed.TypedJoinSlot;
import com.legend.compiler.spec.typed.TypedLambda;
import com.legend.compiler.spec.typed.TypedLet;
import com.legend.compiler.spec.typed.TypedLimit;
import com.legend.compiler.spec.typed.TypedMap;
import com.legend.compiler.spec.typed.TypedMatch;
import com.legend.compiler.spec.typed.TypedMatchRuntime;
import com.legend.compiler.spec.typed.TypedMilestonedAccess;
import com.legend.compiler.spec.typed.TypedNativeCall;
import com.legend.compiler.spec.typed.TypedNavigate;
import com.legend.compiler.spec.typed.TypedNewInstance;
import com.legend.compiler.spec.typed.TypedNewInstanceCast;
import com.legend.compiler.spec.typed.TypedOver;
import com.legend.compiler.spec.typed.TypedPackageableRef;
import com.legend.compiler.spec.typed.TypedPivot;
import com.legend.compiler.spec.typed.TypedProject;
import com.legend.compiler.spec.typed.TypedPropertyAccess;
import com.legend.compiler.spec.typed.TypedRawSqlRelation;
import com.legend.compiler.spec.typed.TypedRename;
import com.legend.compiler.spec.typed.TypedSelect;
import com.legend.compiler.spec.typed.TypedSerialize;
import com.legend.compiler.spec.typed.TypedSerializeGraph;
import com.legend.compiler.spec.typed.TypedSlice;
import com.legend.compiler.spec.typed.TypedSort;
import com.legend.compiler.spec.typed.TypedSortBy;
import com.legend.compiler.spec.typed.TypedSortInfo;
import com.legend.compiler.spec.typed.TypedSourceUrl;
import com.legend.compiler.spec.typed.TypedSpec;
import com.legend.compiler.spec.typed.TypedTableReference;
import com.legend.compiler.spec.typed.TypedTds;
import com.legend.compiler.spec.typed.TypedTypeRef;
import com.legend.compiler.spec.typed.TypedUserCall;
import com.legend.compiler.spec.typed.TypedVariable;
import com.legend.compiler.spec.typed.TypedWrite;

/**
 * The TYPED collection-lane decision (COMPILER_SHORTCUT_AUDIT §1a,
 * Blocker 2): {@code toOne}/{@code toOneMany} pick their checked lane
 * from the OPERAND'S TYPED PROVENANCE, never by sniffing the SQL they
 * just emitted.
 *
 * <p>VALUE lane (pure raising semantics — size != bound raises in the
 * database with pure's message): collections whose provenance is
 * expression-space — literals, natives over value collections,
 * if-branches, and lane-preserving transforms. ROW lane (the engine's
 * relational {@code processNoOp} flow, ADJUDICATED: SQL cannot tell a
 * NULL cell from an empty): anything rooted in a store/relation read.
 *
 * <p>THE SWITCH IS EXHAUSTIVE over the sealed hierarchy — the
 * DEEP_AUDIT_2026_08_21 caught the first draft's {@code default ->
 * false} whitelist missing {@code TypedLimit} (a working
 * {@code take(1)->toOne()} became a compile abort), the exact
 * blind-spot class the whitelist replaced. javac is now the referee: a
 * NEW node type refuses to compile until it is classified here.
 */
final class CollectionLanes {

    private CollectionLanes() {
    }

    /** Is this typed collection VALUE-lane (pure raising semantics)? */
    static boolean valueLane(TypedSpec spec) {
        return switch (spec) {
            // ---- literals & literal-ish leaves: VALUE ----
            case TypedCollection c -> c.elements().stream()
                    .allMatch(CollectionLanes::valueLane);
            case TypedCInteger ignored -> true;
            case TypedCFloat ignored -> true;
            case TypedCDecimal ignored -> true;
            case TypedCString ignored -> true;
            case TypedCBoolean ignored -> true;
            case TypedCDate ignored -> true;
            case TypedCTime ignored -> true;
            case TypedCLatestDate ignored -> true;
            case TypedEnumValue ignored -> true;
            case TypedNewInstance ignored -> true;
            case TypedCopyInstance ignored -> true;
            case TypedTypeRef ignored -> true;
            case TypedPackageableRef ignored -> true;
            // compile-time reflection carrier — folds before lowering
            case com.legend.compiler.spec.typed.TypedDeactivate ignored -> true;
            // ---- lane-preserving transforms: the SOURCE decides ----
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
            case TypedSortBy s -> valueLane(s.source());
            // census carrier: folded to instance literals BEFORE lowering
            // (StatementExecutor); its result is a VALUE — defensive true
            case com.legend.compiler.spec.typed.TypedCsvCensus c -> true;
            case com.legend.compiler.spec.typed.TypedTestDataGen g -> true;
            case TypedDistinct d -> valueLane(d.source());
            case TypedDrop d -> valueLane(d.source());
            // take()/limit() — the DEEP_AUDIT catch: the whitelist had
            // no arm and a working query aborted the compile
            case TypedLimit l -> valueLane(l.source());
            case TypedConcatenate c ->
                    valueLane(c.left()) && valueLane(c.right());
            // a native call is value-lane iff every MANY-stamped data
            // argument is (vacuously true for scalar-built collections:
            // range, split) AND every ZERO-PARAM thunk's body is —
            // if() arrives as a NATIVE with thunk lambdas (thunks ARE
            // value sources); parameterized lambdas (filter/map element
            // functions) stay excluded — their collection arg decides.
            case TypedNativeCall nc -> nc.args().stream().allMatch(a ->
                    a instanceof TypedLambda l
                            ? l.parameters().isEmpty()
                                    ? valueLane(l.body()
                                            .get(l.body().size() - 1))
                                    : true
                            : !a.info().multiplicity().isMany()
                                    || valueLane(a));
            // ---- store/relation-rooted reads and relation ops: ROW
            // (the engine's processNoOp flow) ----
            case TypedPropertyAccess ignored -> false;
            case TypedVariable ignored -> false;
            case TypedGetAll ignored -> false;
            case TypedMilestonedAccess ignored -> false;
            case TypedNavigate ignored -> false;
            case TypedTableReference ignored -> false;
            case TypedRawSqlRelation ignored -> false;
            case TypedCollectionRelation ignored -> false;
            case TypedTds ignored -> false;
            case TypedProject ignored -> false;
            case TypedSelect ignored -> false;
            case TypedRename ignored -> false;
            case TypedExtend ignored -> false;
            case TypedExtendAgg ignored -> false;
            case TypedExtendWindow ignored -> false;
            case TypedGroupBy ignored -> false;
            case TypedAggregate ignored -> false;
            case TypedPivot ignored -> false;
            case TypedFlatten ignored -> false;
            case TypedJoin ignored -> false;
            case TypedJoinSlot ignored -> false;
            case TypedAsOfJoin ignored -> false;
            case TypedOver ignored -> false;
            case TypedWrite ignored -> false;
            case TypedGraphFetch ignored -> false;
            case TypedSerialize ignored -> false;
            case TypedSerializeGraph ignored -> false;
            case TypedNewInstanceCast ignored -> false;
            case TypedSourceUrl ignored -> false;
            // ---- opaque evaluation / binder machinery: conservative
            // ROW (the inliner reduces the common forms before the
            // rules run — probed: let/eval raise correctly) ----
            case TypedUserCall ignored -> false;
            case TypedEval ignored -> false;
            case TypedLet ignored -> false;
            case TypedLambda ignored -> false;
            case TypedMatch ignored -> false;
            case TypedMatchRuntime ignored -> false;
            case TypedFold ignored -> false;
            // spec-fragment carriers — never a collection operand
            case TypedColSpec ignored -> false;
            case TypedColSpecArray ignored -> false;
            case TypedSortInfo ignored -> false;
            case TypedFuncColSpec ignored -> false;
            case TypedFuncColSpecArray ignored -> false;
            case TypedAggColSpec ignored -> false;
            case TypedAggColSpecArray ignored -> false;
        };
    }

    /** The §5 rule at a VALUE-LANE consumer: positional/counting reads
     * (size/at/indexOf — the ops SQL does not null-skip) consume the
     * COMPACTED carrier, because pure collections hold no empties and a
     * literal of {@code [0..1]} reads carries NULL slots for the empty
     * ones. Identity on definite lists; engine-TEXT renders the wrapper
     * verbatim (no golden movement). Row-lane operands ride through —
     * their carriers compact at the collect (Blocker 1). */
    static com.legend.sql.SqlExpr compactIfValueLane(TypedSpec typedOp,
            com.legend.sql.SqlExpr arg) {
        return valueLane(typedOp) && !scalarCarriedIf(typedOp)
                ? new com.legend.sql.SqlExpr.CompactList(arg)
                : arg;
    }

    /** A C1-COLLAPSED LITERAL operand ({@code [7]} — a to-one-stamped
     * collection literal lowered as its bare element, DEEP_AUDIT §3):
     * the ONE population that must re-box before a list-consuming
     * emission. A to-one PROPERTY READ must NOT box — the corpus pins
     * its null-guarded scalar arms (testContainsEscapePercentage:
     * {@code comments->contains('%')} over String[0..1] is
     * {@code IS NOT NULL AND strpos(...)}, never list_contains). */
    static boolean c1Literal(TypedSpec t) {
        return t instanceof TypedCollection
                && t.info().multiplicity() instanceof
                        com.legend.compiler.element.type.Multiplicity.Bounded b
                && b.upper() != null && b.upper() <= 1;
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
                            .filter(a -> a instanceof TypedLambda)
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
