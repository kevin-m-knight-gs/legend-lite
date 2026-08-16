# Bucket 7 — Resolver gap (H-phase)

36 tests from the ledger; **36 still non-passing** at `9d1f2cd0`.

Each entry was produced by an agent that read the test's `.pure` body, grepped the failure message back to the emitting legend-lite code, read that method's control flow, and checked semantics against real legend-engine / legend-pure sources. Citations were mechanically verified to resolve.

Verdicts: REAL DEFECT 22, MISSING FEATURE 14

---

## `testConcatenateInQualifierWithComplexReturnType`

| | |
|---|---|
| family | `functions/tests` |
| sweep status | ERROR |
| **verdict** | **MISSING FEATURE** |
| effort | L |
| confidence | high |

**Root cause**

The query is `Person.all()->project([p|$p.name, p|$p.addresses.name])`. `Person.addresses()` is the derived property `$this.address->concatenate($this.firm.address)` (simpleTestModel.pure:171), which the Typer inlines in query position, so the resolver sees `PropertyAccess(name, concatenate($p.address, $p.firm.address))`. The concat-stream lift arm DOES fire (SyntheticHeads.java:441-453: source is a bare `concatenate` native call, class-typed, and `.name` is [*] so the to-one guard at 446-448 passes), and `liftConcatStreams` walks both branches successfully as liftable navs — but then rejects them at SyntheticHeads.java:712 (`!prop.equals(p) || !nav.equals(headNode)`): both branches share the property name `address`, yet branch 1's nav node is `PropertyAccess($p,'address')` and branch 2's is `PropertyAccess(PropertyAccess($p,'firm'),'address')`, so record equality fails and the lift returns null. With no synthetic union head, the ordinary rewriter then descends into the concatenate's arms; `$p.address` is a depth-1 read whose mapping binding is the join `address : [dbInc]@Address_Person` (relationalSetUp.pure:610), so `rewriteHeadProp` hits the class-typed-binding guard and throws. The wall message blames 'graph output' but the real absent surface is the cross-receiver concatenate union.

**Fix**

Generalise `SyntheticHeads.liftConcatStreams` (SyntheticHeads.java:672-757) from a ONE-HEAD union to a MULTI-CHAIN union, matching `processConcatenate`.

1. Replace the single `prop`/`headNode` accumulator and the refusal at line 712 with a per-branch record `(navPath, pred)`. Keep requiring every branch to be a liftable nav bottoming at the SAME lambda variable (`bottomVarOf`) and every branch's leaf property to be the same (`leafRead.property()`), but ALLOW different head properties and different receiver chains.
2. Mint one `#cN` identity for the whole stream (`mintConcatName` on the FIRST branch's head property is fine — the name is internal), and park the branch DESCRIPTORS, not just predicates: `branchPreds` currently maps synth -> List<TypedLambda>; it needs a sibling map synth -> List<navPath> so the materializer knows each branch is a different chain.
3. In `StoreResolver.registerAssociationJoins` (StoreResolver.java:2441 and the extra-head loop at 2500), when the head key carries a CONCAT identity with heterogeneous branch paths, build ONE AssocJoin whose target pipeline is the UNION ALL of the per-branch materialised chains and whose condition is the OR of the per-branch root conditions. The two machineries already exist and must be reused rather than re-invented: `SyntheticHeads.applyToPipe` (SyntheticHeads.java:171-191) already UNION-ALLs branch pipes into a `TypedConcatenate`, and `Substitution.mergedConcatExists` / the emptiness arm (Substitution.java:830-861) already computes 'the OR of ALL branches' key conditions'. Generalise `applyToPipe` so a branch descriptor supplies its OWN base pipe (today all branches share `pipe` and differ only by predicate).
4. Column alignment: each branch select must project the union of ALL branches' key columns, NULL-padded — this is `alignJoinAndPkColumnsForUnion` (pureToSQLQuery.pure:2912). legend-lite has `Pipelines.widenConcatenateForKeys` (called at StoreResolver.java:2196) which widens a concatenate for key columns; extend it to null-pad missing key columns per branch rather than only widening.
5. Once the lift succeeds, `rewriteHeadProp`'s class-typed wall is no longer reached for this shape — the leaf becomes `assocLeaf("address#cN", "name")`.

**How legend-engine does it** — legend-engine pureToSQLQuery.pure:2709 `processConcatenate` — flattens the concatenate, processes EACH element as its own independent navigation (`$elements->map(e|$e->processValueSpecificationReturnPropertyMapping(...))`), then at :2828 aligns columns across arms via `alignJoinAndPkColumnsForUnion` (:2912, null-pads), at :2845 wraps them in one `union<nodeId>` TableAlias, and at :2854 ORs the per-branch join operations into a single ON clause. There is no same-head restriction anywhere in that function.

**Risk** — The OR'd ON clause plus NULL-padded key columns changes row cardinality for any query already going through the single-head concat lift; regression-check the passing concatenate goldens in functions/tests/testConcatenate.pure (testConcatenateClass, testConcatenateClassMerge, testConcatenateWithFilter, testConcatenateFlat). Tenet-2 trap: do NOT special-case `Person.addresses` in the harness or pre-expand the derived property there — the shape is owned by the resolver's navigation-union surface.

**Also unblocks** — testQualifierConcatenateTwoSimilarJoins (same unit). Very likely also testConcatenateClassJoinMerge and testQualifierConcatenateDifferentJoinPaths in the same file (both are <<test.ToFix>> in the corpus, so they may be excluded), and testQualifierConcatenateTwoSimilarJoinsEmbedded if that one is currently failing elsewhere.

**Falsifier** — Instrument (or reason over) `SyntheticHeads.liftConcatStreams` for this query: if it returns NON-null — i.e. the two branches somehow compare equal, or the arm at line 441 never fires because the Typer does not inline `addresses()` as a bare `concatenate` — then the H4 wall is reached by a different route and this diagnosis is wrong. Cheapest concrete check: confirm the Typer inlines a parameterless qualified property into the call site (Substitution.java:820-823 asserts it does for exactly this `Person.addresses` example, so a counter-observation there falsifies).

<details><summary>Evidence read (9 citations)</summary>

- /Users/neemsandv/legend/legend-engine/.../core_relational/relational/tests/testModel/simpleTestModel.pure:171 — `addresses(){ $this.address->concatenate($this.firm.address) }:Address[*];`
- /Users/neemsandv/legend/legend-engine/.../core_relational/relational/functions/tests/testConcatenate.pure:148 — the test projects `p|$p.addresses.name` and asserts a UNION ALL subselect joined `on ("unionalias_0".ID = "root".FIRMID or "unionalias_0".ID = "root".ADDRESSID)`
- /Users/neemsandv/legend/legend-engine/.../core_relational/relational/tests/relationalSetUp.pure:610 — Person mapping: `address : [dbInc]@Address_Person` (a join, i.e. a class-typed binding)
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/resolver/SyntheticHeads.java:441-453 — the concat-lift arm: fires only when `pa2.source()` is literally a `concatenate` native call, class-typed, and the leaf read is NOT to-one
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/resolver/SyntheticHeads.java:709-720 — `if (prop == null) { prop = p; headNode = nav; } else if (!prop.equals(p) || !nav.equals(headNode)) { return null; }` with the comment 'Cross-head/cross-date unions are their own rung'
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/resolver/Substitution.java:1367-1373 — `if (inner instanceof TypedNewInstance || inner.info().type() instanceof Type.ClassType) throw new NotImplementedException("class-typed property '$"+userVar+"."+prop+"' used as a whole value is graph output (Phase H4)")` — the observed message
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/resolver/Substitution.java:1700-1713 — the rewrite dispatch: pathOf returns null for a concatenate node, so the arms fall through to per-branch descent and `rewriteHeadProp`
- /Users/neemsandv/legend/legend-engine/.../core_relational/relational/pureToSQLQuery/pureToSQLQuery.pure:2912 — `alignJoinAndPkColumnsForUnion`: every branch is padded with `^Alias(name=$a, relationalElement=^Literal(value=^SQLNull()))` for columns it lacks
- /Users/neemsandv/legend/legend-engine/.../core_relational/relational/pureToSQLQuery/pureToSQLQuery.pure:2854 — `let joinY = ^$firstJoin(operation = $newJoins->removeDuplicatesBy(x|$x.name).operation->orFilters($extensions)...)` — the OR'd ON clause

</details>

---

## `testInputNotIsolatedWhenPropertyPathIsToOne`

| | |
|---|---|
| family | `functions/tests` |
| sweep status | ERROR |
| **verdict** | **MISSING FEATURE** |
| effort | M |
| confidence | high |

**Root cause**

A deliberate, correctly-reasoned wall, not an accident. The test is `Person.all()->filter(p| $p.firm->toOne().address->toOne().name->isEmpty())` under `simpleRelationalMappingWithFilter`, whose Firm set carries `~filter [dbInc] FirmXFilter` (relationalSetUp.pure:1046-1051). legend-engine decides isEmpty-input ISOLATION in `processSubEmpty`: `if($state.inFilter && !expressionSequenceReturnsAtLeastToOneDataType($parameters))` it re-processes the argument under `forcedIsolation = IsolationStrategy.MoveFilterInOnClause`, otherwise it processes normally. `expressionSequenceReturnsAtLeastToOneDataType` (pureToSQLQuery.pure:4227-4238) returns TRUE when the expression sequence passes through `toOne`/`toOneMany` or has lower bound >= 1. So the sibling test (`$p.firm.address.name->isEmpty()`) IS isolated — the mapping filter lands in the JOIN ON clause and 6 rows come back — while THIS test, because of the two explicit `->toOne()` calls, is NOT isolated: `"firmtable_0".LEGALNAME = 'Firm X'` stays in the OUTER WHERE, conjoined with `"addresstable_0".NAME is null`, yielding 0 rows. legend-lite has only the isolated emission (mapping ~filter carried inside the join target pipeline), detects the dangerous case, and walls: `Substitution.rewriteCallArms` checks `isEmptinessFamily(call) && piercesToOne(call.args().get(0))` and, if the head's AssocSub was built from a filter-bearing pipeline (`filteredTarget`, set at StoreResolver.java:1531 from `containsFilter(target.pipeline())`), throws the observed message. The surface genuinely absent is 'emit a non-isolated emptiness input: hoist the mapping ~filter out of the join target into the outer WHERE'.

**Fix**

Implement the non-isolated emptiness emission (the 'strict-read filter hoist'), mirroring `processSubEmpty`.

1. Model the engine's decision explicitly rather than as a wall. legend-lite already has `piercesToOne` (Substitution.java:689) — that is the `in(['toOne','toOneMany'])` half of `expressionSequenceReturnsAtLeastToOneDataType`. Add the second half: an argument whose multiplicity lower bound is >= 1 at any step of the sequence. Name the predicate after the engine's (`returnsAtLeastToOneDataType`) so the parity is auditable.
2. When it is TRUE, do NOT isolate: build the emptiness input as an ordinary navigation chain (the same joins the non-emptiness read would produce), and HOIST any mapping ~filter carried in the join target's pipeline out of the target and into the outer WHERE, conjoined with the null check. Concretely: at the AssocSub construction site that computes `filteredTarget` (StoreResolver.java:1525-1531), when the head is consumed by a toOne-piercing emptiness call, strip the `TypedFilter` node(s) that `containsFilter` detects from `target.pipeline()` before materialising the AssocJoin, and re-emit the stripped predicate as a root-level filter over the joined row (the mechanism `foldAssociationJoins` already uses to fold assoc joins into the root pipe, StoreResolver.java:2893).
3. Delete the wall at Substitution.java:884-894 only once (2) is in place — never before.
4. `filteredTarget` currently has exactly one producer (StoreResolver.java:1531) and one consumer (Substitution.java:888); if the hoist is implemented, the flag becomes the hoist's trigger rather than the wall's.

**How legend-engine does it** — legend-engine pureToSQLQuery.pure:4272 `processSubEmpty`, decision at :4286-4293; the isolation predicate at :4227 `expressionSequenceReturnsAtLeastToOneDataType`; the mapping ~filter parked as savedFilteringOperation at :4970. Isolation strategy enum value `IsolationStrategy.MoveFilterInOnClause` at :4288 names precisely what legend-lite does unconditionally today.

**Risk** — Hoisting a mapping ~filter into the outer WHERE changes outer-join semantics from 'unmatched rows survive as NULL' to 'unmatched rows are dropped' — that is the intended difference (6 rows vs 0), but it will silently change results for any other query that reaches the same code path. Gate the hoist strictly on the engine's predicate (inFilter AND returnsAtLeastToOneDataType), not on 'filteredTarget is true'. Tenet-2 trap: do not make the harness accept either row count — the 0-vs-6 difference IS the assertion under test.

**Also unblocks** — Any corpus test combining an explicit ->toOne() inside an isEmpty/isNotEmpty input with a ~filter-mapped set. The rest of the testIsEmpty1.pure family (testIsolationOfTheSameInputInABooleanExprWhereOneSideIsEmpty in particular) exercises the isolated side and should be re-checked after the change.

**Falsifier** — If the sibling test `testIsolationOfInputToIsEmptyWithForcedFiltersOnInput` is ALSO failing in this sweep, then legend-lite's isolated emission is not actually correct either and the split described here is not the whole story — check that test's status first, it is the single cheapest observation. Second falsifier: if the wall fires for a query with NO mapping ~filter, then `filteredTarget` is over-broad and the wall, not the feature, is the bug.

<details><summary>Evidence read (9 citations)</summary>

- /Users/neemsandv/legend/legend-engine/.../core_relational/relational/functions/tests/testIsEmpty1.pure:47-54 — the test and its golden: `... left outer join firmTable as "firmtable_0" on ("firmtable_0".ID = "root".FIRMID) left outer join addressTable ... where "firmtable_0".LEGALNAME = 'Firm X' and "addresstable_0".NAME is null`, `assertEquals(0, $result.values->size())`
- /Users/neemsandv/legend/legend-engine/.../core_relational/relational/functions/tests/testIsEmpty1.pure:36-44 — the CONTRASTING sibling `testIsolationOfInputToIsEmptyWithForcedFiltersOnInput`: same mapping, no toOne, filter moves into the ON clause, 6 rows
- /Users/neemsandv/legend/legend-engine/.../core_relational/relational/tests/relationalSetUp.pure:1046-1051 — `Firm : Relational { ~filter [dbInc] FirmXFilter  legalName : ... address : [dbInc]@Address_Firm }` in simpleRelationalMappingWithFilter
- /Users/neemsandv/legend/legend-engine/.../core_relational/relational/pureToSQLQuery/pureToSQLQuery.pure:4286-4293 — `let mergedSQL = if($state.inFilter && !expressionSequenceReturnsAtLeastToOneDataType($parameters), | let forcedIsolationContext = ^RelationalDebugContext(forcedIsolation=IsolationStrategy.MoveFilterInOnClause, ...) ... , | ...normal processing...)`
- /Users/neemsandv/legend/legend-engine/.../core_relational/relational/pureToSQLQuery/pureToSQLQuery.pure:4227-4238 — `expressionSequenceReturnsAtLeastToOneDataType`: `$a.first->in(['toOne','toOneMany']) || (getLowerBound($fm.second) >= 1)` — the explicit toOne is exactly what flips isolation off
- /Users/neemsandv/legend/legend-engine/.../core_relational/relational/pureToSQLQuery/pureToSQLQuery.pure:4970 — `savedFilteringOperation += pair($lastNode, $filterMapping.filter.operation->toOne()->reprocessAliases(...))` — the mapping ~filter is parked as a saved filtering operation, which is what isolation later moves into the ON clause vs. leaves in the WHERE
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/resolver/Substitution.java:884-894 — the wall: `if (isEmptinessFamily(call) && headPath != null && !headPath.isEmpty() && piercesToOne(call.args().get(0))) { AssocSub fh = target.assocs().get(headPath.get(0)); if (fh != null && fh.filteredTarget()) throw new NotImplementedException("emptiness check over a toOne()-pierced navigation through the ~filter-mapped set of '" + headPath.get(0) + "' needs the strict-read filter hoist — not supported yet"); }`
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/resolver/Substitution.java:685-707 — `piercesToOne`: walks the chain looking for an explicit user `toOne` wrapper
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/resolver/StoreResolver.java:1531 and :3383-3392 — `containsFilter(target.pipeline())` supplies `filteredTarget`; `containsFilter` is a recursive `pipeline instanceof TypedFilter` scan

</details>

---

## `testNestedExistsWithExistsInAbstractProperty`

| | |
|---|---|
| family | `functions/tests` |
| sweep status | ERROR |
| **verdict** | **REAL DEFECT** |
| effort | M |
| confidence | medium |

**Root cause**

Distinct from the concatenate cluster. Query: `Firm.all()->filter(f | exists($f.employees, e | $e.firm->toOne().hasEmployeeBelowAge(15)))`, where `hasEmployeeBelowAge(age){$this.employees->exists(e|$e.age->toOne() < $age)}` (simpleTestModel.pure:68). After inlining, the INNER predicate is `$e.firm->toOne().employees->exists(e2|$e2.age->toOne() < 15)` — an emptiness/exists call whose head is the DOTTED path ['firm','employees'] relative to the inner binder `e`. legend-lite has the machinery for exactly this: `registerDottedExistsSubs` (StoreResolver.java:1548) registers an ExistsSub under the dotted key, and `Substitution.rewriteCallArms` consumes it at Substitution.java:866-872. But `registerDottedExistsSubs` has exactly ONE call site — StoreResolver.java:2889, in the OUTER chain resolution. The nested scope for the outer exists' target is built by `nestedScope` -> `scopeMaterials` (StoreResolver.java:3336-3372), which calls only `registerExistsSubs(t, innerPaths, Set.of(), innerOps, context, Map.of())` at line 3354 with HEADS-ONLY paths (`heads.forEach(h -> innerPaths.add(List.of(h)))`, line 3287) and never registers dotted keys. The outer scope cannot cover for it either: `InnerDemand.collectEmptinessChainPaths` is invoked with the OUTER param `f` (StoreResolver.java:1571-1573) and `Substitution.pathOf($e.firm.employees, "f")` returns null, so the inner dotted path is invisible there. With no ExistsSub under 'firm.employees', the exists arm at Substitution.java:866 does not match, the call falls through every arm, and the generic rewriter descends into the exists' first argument: `$e.firm.employees` is a depth-2 path -> `rewritePath('firm','employees')` -> `assocLeaf('firm','employees')` -> `assocBindingRead` prefixes the class-typed `employees : @Firm_Person` join binding into the bare column read `firm_employees`. The Lowerer then fails to fold that reference inside the exists subselect.

**Fix**

Wire the dotted-exists registration into nested scopes.

In `StoreResolver.scopeMaterials` (StoreResolver.java:3336), after `nestedAssocs`/`pipe` are built (line 3350) and before the NestedScope is returned (line 3366), call:

    registerDottedExistsSubs(t, innerOps, /*top*/ innerOps.isEmpty() ? null : innerOps.get(0), /*tree*/ null, context, nestedAssocs, nested);

The signature already fits: `innerOps` are exactly `new TypedFilter(targetPipe, lam, ...)` nodes (StoreResolver.java:3291), which is the shape `registerDottedExistsSubs` scans at lines 1567-1574 (`if (op instanceof TypedFilter f) ... collectEmptinessChainPaths(b, f.predicate().parameters().get(0), ...)`), so the paths get collected relative to the INNER binder. Pass `tree = null` only if you also want the terminal-lambda scan; for a nested predicate scope the ops scan is sufficient, so pass a `top` that yields no terminal lambdas (or add a boolean to skip the `tree == null` block at lines 1583-1590 — cleaner, since that block is about graph terminals, which a nested scope has none of).

For this test the path is ['firm','employees'] with `firm` to-ONE, so `midToMany` at StoreResolver.java:1617-1623 is false and the ordinary branch applies: `chain = nestedAssocs.get("firm")` (non-null — the nested assoc materials already join firmTable inside the subselect) and the leaf material registers the correlated EXISTS on `employees`. That produces exactly the golden's shape: the `firm` hop as a plain LEFT JOIN inside the outer subselect plus a LEFT JOIN to `select distinct FIRMID ... where AGE < 15` with an `is not null` check.

Also apply the loudness guard from test 4's fix (a) to `Substitution.assocBindingRead`, so that if the registration still misses, the failure is a named resolver wall instead of a dangling `firm_employees` column three stages downstream.

**How legend-engine does it** — legend-engine pureToSQLQuery.pure:4198-4211 `processEmpty` routes a Class-typed emptiness argument to `processExists` regardless of how deeply the call is nested — the engine's exists processing is scope-recursive by construction (`processValueSpecification` on the predicate re-enters with the exists' own `rootSelect` as `operation`, pureToSQLQuery.pure:5079-5101), so a dotted head inside a nested predicate is handled by the same code as one at the top level. legend-lite's split between an outer-only `registerDottedExistsSubs` and a heads-only nested registration has no counterpart in the engine.

**Risk** — Registering dotted exists subs in nested scopes adds joins inside exists subselects; if a path is registered but the enclosing scope also materialises it as an assoc chain, you can get a duplicate join and inflated rows. Guard with the existing `if (existsSubs.containsKey(dotted)) continue;` (StoreResolver.java:1601) and verify the temporal frame handling — `scopeMaterials` swaps `temporal` to the nested frame (lines 3344-3348) and `registerDottedExistsSubs` stamps temporal context, so the new call MUST sit inside that try/finally. Tenet-2 trap: do not relax the harness' SQL comparison to accept a missing inner join.

**Also unblocks** — Any corpus test whose exists/isEmpty head is a dotted (>= 2 hop) path spelled INSIDE another exists/filter predicate — e.g. nested-exists variants in functions/tests/testExists.pure and the isEmpty family. Should be checked against testExistsWithOrCondition and testAssociationWithProjectionHandlingDups, which are the depth-1 analogues that presumably already pass.

**Falsifier** — Log the nested registry contents for the outer exists on `employees`: if `existsSubs` already contains the key "firm.employees" and the failure is instead that the registered ExistsSub's correlated condition does not resolve against the subselect row, then the registration is present and the defect is in ChainedExists/emission, not in scopeMaterials — this diagnosis would be wrong. Equivalent cheap static check: confirm by grep that `registerDottedExistsSubs` really has only the one call site at StoreResolver.java:2889 (it does today) and that no other code path inserts dotted keys into a nested `existsSubs` map.

<details><summary>Evidence read (10 citations)</summary>

- /Users/neemsandv/legend/legend-engine/.../core_relational/relational/functions/tests/testExists.pure:391 — `Firm.all()->filter(f | exists($f.employees,e| $e.firm->toOne().hasEmployeeBelowAge(15)))`; golden = outer distinct-subselect over personTable, containing `left outer join firmTable as "firmtable_1" ... left outer join (select distinct "persontable_3".FIRMID ... where AGE < 15) as "persontable_2" on ("firmtable_1".ID = "persontable_2".FIRMID) where "persontable_2".FIRMID is not null`
- /Users/neemsandv/legend/legend-engine/.../core_relational/relational/tests/testModel/simpleTestModel.pure:68 — `hasEmployeeBelowAge(age:Integer[1]){$this.employees->exists(e|$e.age->toOne() < $age)}:Boolean[1];`
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/resolver/StoreResolver.java:2889 — `registerDottedExistsSubs(cs, ops, top, tree, context, assocs, existsSubs);` — the ONLY call site (verified by grep over the whole resolver package)
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/resolver/StoreResolver.java:3352-3360 — `scopeMaterials`: `registerExistsSubs(t, innerPaths, Set.of(), innerOps, context, Map.of())` then `nestedAssocMaterials(...)`; `innerFullPaths` is passed ONLY to the assoc materials, never to any dotted-exists registration
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/resolver/StoreResolver.java:3284-3292 — `heads.forEach(h -> innerPaths.add(List.of(h)))` — the nested scope's exists paths are depth-1 only
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/resolver/StoreResolver.java:2241 and :2279 — `registerExistsSubs` builds the exists target's registries via `nestedScope(t, ops, head, context, ...)`, closing the loop
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/resolver/InnerDemand.java:306-346 — `collectEmptinessChainPaths(n, userVar, ...)`: only records `Substitution.pathOf(h, userVar)` of size >= 2, so a path rooted at a different (inner) binder is never collected
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/resolver/Substitution.java:866-872 — the dotted-exists consumption arm: `if (headPath.size() >= 2 && isEmptinessFamily(call) && target.existsSubs().containsKey(String.join(".", headPath)))`
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/lowering/Lowerer.java:3440-3451 — `whereLambda` lowers the exists relation then `predicateOrThrow(sub, lambda, "exists/forAll")`
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/lowering/Lowerer.java:1263-1272 — `predicateOrThrow` throws `op + " predicate references column '" + u.column() + "', unresolvable even after isolation [param=" + lambda.parameters().get(0) + "; pred=" + ...]` — the observed message shape, with param=t_n (the freshened target binder from Substitution.java:2152-2170)

</details>

---

## `testQualifierConcatenateTwoSimilarJoins`

| | |
|---|---|
| family | `functions/tests` |
| sweep status | ERROR |
| **verdict** | **REAL DEFECT** |
| effort | L |
| confidence | medium |

**Root cause**

Same absent surface as test 1 (cross-receiver concatenate union), but here the lift arm never even fires, and the fall-through is SILENT rather than loud. The query is `NewTrade.all()->project([t|$t.id, t|$t.accountOrganizationalEntity.name], ['Trade ID','OE'])` with `accountOrganizationalEntity() { $this.subAccount.oe->concatenate($this.otherAccount.oe)->toOne() }:OrganizationalEntity[0..1]` (testConcatenate.pure:255-258). After inlining, the resolver sees `PropertyAccess(name, toOne(concatenate($t.subAccount.oe, $t.otherAccount.oe)))`. The concat-lift arm at SyntheticHeads.java:441-453 is gated on `pa2.source() instanceof TypedNativeCall cc && cc.callee().qualifiedName().equals(...concatenate)` — the source here is the `toOne` call, not the concatenate, so the gate fails (the per-branch toOne look-through at SyntheticHeads.java:685-690 handles inner wrappers only, never the OUTER one). The second gate at 446-448 also excludes it: `.name` off a [0..1] receiver is upper-bound 1, and the arm requires NOT-to-one. With no lift, the generic rewriter descends into the concatenate arms; `$t.subAccount.oe` is a depth-2 path so `rewrite` takes `rewritePath("subAccount","oe")` (Substitution.java:1701-1703), the head has a class-typed join binding registered as an assoc, so it dispatches to `assocLeaf("subAccount","oe")` (Substitution.java:1963-1967). `oe` IS present in the SubAccount target's bindings (`oe : [myDb]@subAccountOE`), so the not-mapped guard at Substitution.java:2091 does not fire, and `assocBindingRead` (Substitution.java:2104-2137) prefixes it into a plain column read `subAccount_oe`. `assocBindingRead` has NO guard for a class-typed (association) leaf binding — it only rejects `TypedNewInstance` embedded ctors at line 2107 — so a join-valued leaf silently becomes a column reference that no lowered relation can produce. The Lowerer then reports it as an unresolvable projection ref.

**Fix**

Two changes, both required.

(a) THE LOUDNESS FIX (do this first, it is small and it is the tenet): in `Substitution.assocBindingRead` (Substitution.java:2104), after the `TypedNewInstance` check at line 2107, add a class-typed guard mirroring the one `rewriteHeadProp` already applies at Substitution.java:1367-1373 — if `leafInner.info().type() instanceof Type.ClassType`, throw `NotImplementedException("class-typed property '" + leaf + "' of association target '" + a.targetClassFqn() + "' used as a whole value is not supported yet")`. Today a join-valued leaf is silently turned into a dangling column name and the failure surfaces three stages later in the Lowerer naming an internal column; that is exactly the 'wrong/dangling instead of a loud wall' failure mode the tenets forbid.

(b) THE FEATURE FIX: same generalisation as test 1. In addition to the multi-chain lift described there, widen the arm's gates at SyntheticHeads.java:441-453: (i) unwrap an OUTER `toOne(...)` (and `first`/`head`) around the concatenate before the `instanceof concatenate` test — reuse the same loop already written at lines 685-690, or `Pipelines.unwrapToOne`; (ii) drop the to-one multiplicity exclusion at 446-448, or restrict it to the case the comment actually intends — a [0..1] qualifier return over a to-many union is still a union join in the engine (the golden here proves it: `accountOrganizationalEntity` is [0..1] and still emits `unionalias_0`). Branch descriptors are `["subAccount","oe"]` and `["otherAccount","oe"]`; each materialises its own 2-hop chain, the union pads to the merged key column set, and the AssocJoin condition is the OR of the two root conditions.

**How legend-engine does it** — legend-engine pureToSQLQuery.pure:2709 `processConcatenate`, non-empty-`operation.select.data` branch (:2790-2882): each element is cut at `nodeToCut->children()` and rebuilt by `buildConcatenateSubSelect` (:2889), the arms are aligned by `alignJoinAndPkColumnsForUnion` (:2828, defined :2912), and the per-arm joins are OR'd into one ON clause at :2854. Note the engine imposes no relationship between the arms' navigation paths — `$this.subAccount.oe` and `$this.otherAccount.oe` are simply two independently-processed value specifications.

**Risk** — Dropping the to-one guard at SyntheticHeads.java:446-448 will pull additional shapes into the lift; that guard was presumably added because a [0..1] leaf read has a scalar (correlated-subselect) emission elsewhere (`filteredNavLeafRead`, Substitution.java:1746). Route the to-one case through the union lift only when the source is a genuine multi-branch concatenate, so the existing scalar arm keeps its shapes. Tenet-2 trap: do NOT make the harness tolerate the missing column or rewrite the qualifier — the projection/union shape is platform-owned.

**Also unblocks** — testConcatenateInQualifierWithComplexReturnType (same unit); plausibly testQualifierConcatenateTwoSimilarJoinsEmbedded and testConcatenateClassJoinMerge.

**Falsifier** — The gate analysis is high-confidence; the exact provenance of the string `subAccount_oe` is medium. Cheapest falsifier: run the sweep with `LEGEND_LITE_STACKS=1` set (Substitution.java:1335 already dumps a stack on the multi-hop wall) — or add a temporary print in `assocBindingRead` — and confirm the `subAccount_oe` reference is minted there with head='subAccount', leaf='oe'. If instead it is minted by a materialised nav-slot prefix (i.e. `subAccount_oe` is a legitimate slot column that the pipeline simply failed to project), the loudness fix (a) is wrong and the defect is in slot materialisation, not in assocBindingRead.

<details><summary>Evidence read (8 citations)</summary>

- /Users/neemsandv/legend/legend-engine/.../core_relational/relational/functions/tests/testConcatenate.pure:255-258 — `accountOrganizationalEntity(){ $this.subAccount.oe->concatenate($this.otherAccount.oe)->toOne() }:OrganizationalEntity[0..1];`
- /Users/neemsandv/legend/legend-engine/.../core_relational/relational/functions/tests/testConcatenate.pure:157 — the test, and its golden `left outer join (... union all ...) as "unionalias_0" on ("root".EXTERNALACCOUNT_ID = "unionalias_0".EA_ID or "root".SUBACCOUNT_ID = "unionalias_0".ID)` with `null as EA_ID` / `null as ID` padding
- /Users/neemsandv/legend/legend-engine/.../core_relational/relational/functions/tests/testConcatenate.pure:~345-370 — testMapping: `subAccount: [myDb]@tradeSubAccount`, `otherAccount: [myDb]@tradeOtherAccount`, `SubAccount { oe: [myDb]@subAccountOE }`, `OtherAccount { oe: [myDb]@otherAccountOE }` — both `oe` leaves are JOINS, not columns
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/resolver/SyntheticHeads.java:441-448 — the two gates that exclude a `toOne(concatenate(...))` source and a to-one leaf multiplicity
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/resolver/SyntheticHeads.java:685-690 — the toOne look-through exists only INSIDE the per-branch loop
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/resolver/Substitution.java:1963-1967 — `if (target.assocs().containsKey(head) && inner instanceof TypedPropertyAccess pa && pa.source() instanceof TypedVariable) return assocLeaf(head, leaf);`
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/resolver/Substitution.java:2104-2137 — `assocBindingRead`: rejects only `TypedNewInstance` leaves (line 2107); any other leaf binding is prefixed into a column read via `Pipelines.prefixColumns(leafBinding, a.targetRowVar(), a.prefix(), ...)`
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/lowering/Lowerer.java:1162-1175 — `computedColumns` throws `"extend/project columns " + names + " reference names unresolvable even after isolation [col='..' ref='..']"` — the observed message, with ref = the unfoldable column name

</details>

---

## `testChainedFiltersQuery`

| | |
|---|---|
| family | `functions/tests/projection` |
| sweep status | ERROR |
| **verdict** | **REAL DEFECT** |
| effort | M |
| confidence | medium |

**Root cause**

`locations` IS mapped — the error text is a misattribution. The query is `Firm.all()->filter(f|$f.employees->filter(e|$e.lastName=='Smith').locations->filter(o|$o.place=='Hoboken').place != 'New York')`. A to-many crossing in FILTER position deliberately takes the association-JOIN route, not the exists route (StoreResolver.java:2337-2341: "EVERY to-many crossing joins with ROW EXPLOSION — filter position included ... AUDIT 9: filter-only EXISTS was cardinality-wrong"), so `registerAssociationJoins` walks the consumed path hop by hop. Hop 0 is `employees`, a real Association end (Employment), which has no ClassSource binding and resolves through `associationJoin`. Hop 1 is the filter-lifted synthetic head over `locations` on the Person ClassSource — and `locations` is NOT an association end: it is a plain class property declared on `EntityWithLocations` and mapped by a Join PM (`locations : [dbInc]@Person_Location`), which the normalizer emits as a `legacyNavigate` slot plus a ctor binding. The hop loop calls `assocMaterial.associationJoin(temporal, parent, path.get(hop), ...)` unconditionally for every hop; only the HOP-0 head is gated on `cs.bindings().containsKey(...)`. Inside `associationJoin` the very first thing after the synthetic-pred collection is `ctx.findAssociationOf(cs.classFqn(), real).orElseThrow(...)`, which throws a MappingResolutionException worded as 'not mapped in mapping'. So: a nav-slot (non-association) class-typed property, when it appears as a MID hop of a filter-position path, is routed into an association-only code path. `AssociationJoins.isAssocOrNavHead` (line 232-254) already declares nav-slot heads and association heads to be the same family and returns true for both — `associationJoin` simply does not honour that contract. Sibling tests testChainedFiltersGet / testChainedFiltersProject use the same `.locations` chain but as the MAIN chain, where `collectOpChain` re-roots per hop and never enters this walk — which is why the same navigation works there.

**Fix**

core/src/main/java/com/legend/resolver/AssociationJoins.java, method `associationJoin(TemporalFrame, ClassSource, String, Context, boolean, Set<String>, String, Set<List<String>>)` (line 944). Insert a NAV-SLOT branch immediately before line 962, mirroring StoreResolver.java:1646-1685 verbatim in structure:

    TypedSpec headBinding = cs.bindings().get(real);
    if (headBinding != null) {
        TypedSpec inner = Pipelines.unwrapToOne(headBinding);
        var pNavSteps = Pipelines.navSteps(cs.pipeline());
        String alias = InnerDemand.navSlotAlias(inner, cs.rowVar(), pNavSteps.keySet());
        var nav = alias == null ? null : pNavSteps.get(alias);
        if (nav != null && nav.target() instanceof TypedGetAll tg
                && sources.binds(cs.mappingFqn(), tg.classFqn())) {
            // build the AssocJoin from the navigate step instead of the
            // association end: target = sources.get(cs.mappingFqn(), tg.classFqn()),
            // condition = nav.predicate(), target pipeline = Pipelines.materialize(
            //   target.pipeline(), demandedLeaves + navTails, target.classFqn()),
            // then park synthPreds with CorrelatedSubselects.predFilteredPipe(...)
            // exactly as the association arm does below, and reuse the same
            // temporal.temporalTargetPipe / applyJoinTemporalFilters calls.
            return <AssocJoin built from the nav step>;
        }
    }

The rest of `associationJoin` after the condition/target are known is end-agnostic (materialisation, slot demand, prefixes, temporal stamping, parked-pred application), so factor those lines into a private helper taking (target ClassSource, condition TypedLambda) and call it from both branches — do not fork the method. Two things must change with it: (a) the `orElseThrow` message at line 963-966 is wrong even for the genuinely-unmapped case reached from a MID hop — it should say the property's route is unsupported / the class is unmapped, never 'property X is not mapped' when a Join PM maps it; (b) `AssociationJoins.hopTargetClass` (line 925-937) and `chainNavTails` already resolve nav-slot hops through `ctx.findProperty` first, so the tails/demand plumbing feeding the new branch is already correct.

Do NOT patch this at StoreResolver.java:2388 alone by skipping nav-slot hops — skipping would silently drop the join and produce wrong rows.

**How legend-engine does it** — legend-engine draws no distinction between an association end and a join-mapped class property when building the join tree: both are processed by the same property-mapping walk in meta::relational::functions::pureToSqlQuery (the QualifiedProperty/Property dispatch at legend-engine-xts-relationalStore/.../core_relational/relational/pureToSQLQuery/pureToSQLQuery.pure:630, which routes on the property mapping, not on whether an Association element declares the end). The golden SQL in the test (testFilter.pure:52) is a flat chain of LEFT JOINs — firmTable -> personTable -> locationTable — with no association-specific shape.

**Risk** — `associationJoin` is called from ~10 sites (NavMaterializer.java:563, GraphEmission.java:1065/1269/1703/2089/2498, StoreResolver.java:861/1697/2267/2389, AssociationJoins.java:1435/1784). Making it accept nav-slot heads changes behaviour at every one of them: sites that today `continue` past a nav-slot head because they pre-check the binding (StoreResolver.java:1646, NavMaterializer.java:133-142) are unaffected, but any site that relied on the throw as a filter would now proceed. Audit each call site's guard before landing. Also: the parked-predicate application for the nav-slot branch must go through `CorrelatedSubselects.predFilteredPipe` on the TARGET pipeline (the closed `o|$o.place=='Hoboken'` pred), or the golden's `and "locationtable_0".PLACE = 'Hoboken'` inside the ON clause will not appear.

**Also unblocks** — Any filter-position path that crosses a join-mapped (non-association) class property as a non-leading hop. Likely siblings: other chained-navigation tests over `.locations` and `.placeOfInterest` in functions/tests/projection and functions/tests/filter.

**Falsifier** — The whole diagnosis rests on `locations` having a ClassSource binding (nav slot) on Person while `employees` has none (association end). One cheap observation settles it: dump `sources.get("meta::relational::tests::simpleRelationalMapping", "...simple::Person").bindings().keySet()` and the same for Firm. If `locations` is absent from Person's bindings, then `associationJoin` is the only available route and the defect is upstream in the normalizer (the Join PM never became a legacyNavigate slot) rather than in the hop dispatch. Secondarily, if the throw actually comes from Substitution.java:2091 (`assocLeaf`, whose message is textually identical because it also prints `SyntheticHeads.realHead(leaf)` and the assoc TARGET class), the fix moves to making synthetic filter-lifted second-hop heads resolvable in `AssocSub.targetBindings()` — a stack trace on the MappingResolutionException distinguishes the two in one run.

<details><summary>Evidence read (9 citations)</summary>

- core/src/main/java/com/legend/resolver/AssociationJoins.java:962-966 — `var assoc = ctx.findAssociationOf(cs.classFqn(), real).orElseThrow(() -> new MappingResolutionException("property '" + SyntheticHeads.displayName(real) + "' of class '" + cs.classFqn() + "' is not mapped in mapping '" + cs.mappingFqn() + "'", ...))` — the exact message text, with the class = the HOP PARENT (Person) and mapping = simpleRelationalMapping
- core/src/main/java/com/legend/resolver/StoreResolver.java:2388-2390 — the hop loop: `AssociationJoins.AssocJoin aj = assocMaterial.associationJoin(temporal, parent, path.get(hop), context, false, ...)` with no per-hop nav-slot dispatch
- core/src/main/java/com/legend/resolver/StoreResolver.java:2325-2335 — the ONLY binding gate is on `path.get(0)`: `if (cs.bindings().containsKey(SyntheticHeads.realHead(path.get(0)))) { emb = assocMaterial.embeddedPassThrough(cs, path); if (emb == null) { continue; } }`
- core/src/main/java/com/legend/resolver/AssociationJoins.java:232-254 — `isAssocOrNavHead`: a head with a binding resolving to a `navSteps` alias whose `nav.target() instanceof TypedGetAll` counts as assoc-or-nav; only the binding-less case falls back to `findAssociationOf`
- core/src/main/java/com/legend/resolver/StoreResolver.java:1646-1685 — the exists-leaf path shows the CORRECT two-branch dispatch: `TypedSpec leafBinding = parent.bindings().get(leaf); if (leafBinding != null) { ...navSlotAlias / navSteps.get(alias) / sources.get(mapping, tg.classFqn()); cond = nav.predicate(); ... } else if (ctx.findAssociationOf(parent.classFqn(), leaf).isPresent()) { ...associationJoin... } else { continue; }` — this is the shape the hop loop is missing
- core/src/main/java/com/legend/normalizer/JoinChainEmission.java:255-262 — "The final hop is a legacyNavigate iff classTypedTerminus is true AND the property's declared target class is mapped" — i.e. a class-typed Join PM becomes a navigate slot, not an association
- tests/testModel/simpleTestModel.pure:27-34 — `Class ...EntityWithLocations { locations : Location[*]; ... }` — a plain class property, and the file's `^Association` declarations (lines 350-682) contain no Person/Location association
- tests/relationalSetUp.pure:601-616 — `Person : Relational { ... locations : [dbInc]@Person_Location, ... }` inside simpleRelationalMappingInc, which simpleRelationalMapping includes with a store substitution (tests/relationalSetUp.pure:785-787: `Mapping meta::relational::tests::simpleRelationalMapping ( include simpleRelationalMappingInc[dbInc->db] ...`) — the property is unambiguously mapped
- functions/tests/projection/testFilter.pure:47-54 — the test body and its golden `left outer join locationTable as "locationtable_0" on ("persontable_0".ID = "locationtable_0".PERSONID and "locationtable_0".PLACE = 'Hoboken')`, confirming the engine emits plain chained LEFT JOINs with the parked filter folded into the ON

</details>

---

## `testQualifierWithInThroughJoin`

| | |
|---|---|
| family | `functions/tests/projection` |
| sweep status | ERROR |
| **verdict** | **MISSING FEATURE** |
| effort | S |
| confidence | medium |

**Root cause**

`$i.account.accountCategory`: `account` is `Account[0..1]` (Association Trade_Accounts) and `accountCategory` is a zero-arg derived property whose body is `if($this.name->in(['Account 1','Account 2']), |'A', |'B')`. In Typer.applyProperty's derived-read arm, a receiver that is not exactly [1] and not many takes the [0..1] branch, which β-inlines the body ONLY when it is provably null-strict in `$this`. `derivedBodyStrictInThis` runs `strictScan` over the body and flags any call in `EMPTY_MANUFACTURING_FNS`; `if` is the first entry in that set, so the body scores non-strict and the Typer throws. The gate exists to preserve PURE's in-memory auto-map semantics (an empty [0..1] receiver yields empty, so a body that manufactures a constant would be wrong). But legend-engine's RELATIONAL compiler does not have those semantics: `processQualifiedProperty` processes the qualifier's expressionSequence directly against the current operation with no emptiness test and no presence guard, so the qualifier body is inlined into SQL and emptiness manifests only as SQL NULL propagation. The corpus encodes exactly that: the golden SQL is `case when "accounttable_0".name in ('Account 1','Account 2') then 'A' else 'B' end`, and the expected values `['A', 'Account 2', 'B', ^TDSNull()]` mean the trade WITHOUT an account gets cat='B' (NULL IN (...) is NULL -> ELSE branch), not empty. So the roadmap remedy named in the wall's own text — the 'presence-guarded emission' — would make this test produce TDSNull for `cat` and STILL fail. The gap is not the presence guard; it is that legend-lite applies in-memory [0..1] auto-map semantics on a path where the reference implementation applies SQL null propagation.

**Fix**

core/src/main/java/com/legend/compiler/spec/Typer.java, applyProperty's derived-read arm. Remove the strictness gate for the RELATIONAL path: delete the `if (!derivedBodyStrictInThis(d)) { throw ... }` block at lines 2505-2511 so a [0..1] receiver falls through to the same `applyGeneric(new AppliedFunction(d.bodyFunctionFqn(), List.of(ap.receiver())), env)` inline the exactly-[1] receiver already takes (line 2514). Keep the `isMany()` auto-map arm at lines 2489-2497 untouched — that one is real Pure (map.pure grammarDoc) and the engine agrees. If a corpus shape genuinely needs Pure's empty semantics for a [0..1] derived read, the discriminator is the EXECUTION TARGET, not the body shape: gate on 'this expression will be compiled to SQL' (relational -> inline, matching processQualifiedProperty) instead of on `derivedBodyStrictInThis`, and keep `derivedBodyStrictInThis`/`EMPTY_MANUFACTURING_FNS` only for the host-eval path. Do NOT implement the 'presence-guarded emission' the wall text advertises — it would emit TDSNull for `cat` on the account-less row and this test would still fail.

**How legend-engine does it** — legend-engine .../core_relational/relational/pureToSQLQuery/pureToSQLQuery.pure:1061-1075 (`processQualifiedProperty`) — inlines the qualifier's expression sequence unconditionally, with no receiver-multiplicity check and no presence guard; and the dispatch that reaches it at the same file:630 (`q:QualifiedProperty<Any>[1] | processQualifiedPropertyFunctionExpression(...)`).

**Risk** — `derivedBodyStrictInThis` was added by audit 22a H2 for a real reason — some corpus test presumably asserts empty/TDSNull where a naive inline manufactures a value. Removing the gate outright could convert that test from PASS to FAIL (a wrong-rows regression, which is worse than this ERROR). That is why the execution-target-scoped variant is the safer landing. Also note this fix is XS in code and M in verification: it must be landed with a full-corpus sweep, not a single-test check.

**Also unblocks** — Other tests blocked by the same 'null-strict whitelist' wall — any query reading a parameterless derived property whose body uses if/match/isEmpty/a reducer over a [0..1] receiver.

**Falsifier** — Find one corpus test that asserts an EMPTY/TDSNull result for a zero-arg derived property with a non-strict body read over a [0..1] receiver. If such a test exists and currently passes, the blanket gate removal is wrong and only the execution-target-scoped variant is admissible. Cheap search: grep the corpus for `assertEmpty`/`^TDSNull()` in tests whose queries navigate a [0..1] association end into a parameterless derived property.

<details><summary>Evidence read (7 citations)</summary>

- core/src/main/java/com/legend/compiler/spec/Typer.java:2496-2513 — the [0..1] branch: `if (!derivedBodyStrictInThis(d)) { throw new TypeInferenceException("derived property '" + ap.property() + "' over a [0..1] receiver has a body outside the null-strict whitelist — empty-receiver semantics needs the presence-guarded emission (roadmap)"); }` — the exact failure text, and the comment above it names this very test as the reason the presence-guard attempt was reverted
- core/src/main/java/com/legend/compiler/spec/Typer.java:2715-2732 — `EMPTY_MANUFACTURING_FNS = Set.of("if", "match", "isEmpty", ...)` and `derivedBodyStrictInThis` returning `(flags & 1) != 0 && (flags & 2) == 0`; `accountCategory`'s `if` sets the non-strict bit
- core/src/main/java/com/legend/compiler/spec/Typer.java:2514-2515 — the inline the strict path takes: `return applyGeneric(new AppliedFunction(d.bodyFunctionFqn(), List.of(ap.receiver())), env);`
- tests/testModel/simpleTestModel.pure:459-466 — `Class ...Account { name : String[1]; createDate : StrictDate[1]; accountCategory(){ if ( $this.name->in(['Account 1','Account 2']), | 'A', | 'B') }:String[1]; ... }`
- tests/testModel/simpleTestModel.pure:620-624 — `Association ...Trade_Accounts { account : Account[0..1]; trades : Trade[*]; }` — the [0..1] receiver that trips the gate
- functions/tests/projection/testIn.pure:209-217 — the test: `assertSize($result.values.rows, 2); assertSameElements(['A', 'Account 2', 'B', ^TDSNull()], ...)` plus the golden `case when "accounttable_0".name in ('Account 1','Account 2') then 'A' else 'B' end as "cat"` — the account-less row's cat is 'B', proving the engine does the plain inline
- legend-engine .../core_relational/relational/pureToSQLQuery/pureToSQLQuery.pure:1061-1075 — `processQualifiedProperty`: `let expression = $fe.expressionSequence; assertEquals(1, $expression->size(), ...); let result = processValueSpecificationReturnPropertyMapping($expression->toOne(), $currentPropertyMapping, $operationWithoutFilter, $vars, ^$updatedState(shouldIsolate=false), ...)` — the qualifier body is processed straight against the current operation; there is no receiver-multiplicity branch and no emptiness guard anywhere in the function

</details>

---

## `testVariableReferenceInFilterWithSameNameAsThatInParentProject`

| | |
|---|---|
| family | `functions/tests/projection` |
| sweep status | ERROR |
| **verdict** | **REAL DEFECT** |
| effort | M |
| confidence | high |

**Root cause**

The failing statement is not the query — it is the assert. `EngineTestExecutor.checkAssert`'s `case "assert"` arm evaluates ONLY `args.get(0)`, i.e. `$expected->forAll(e|$results->contains($e))`, via `evalScalar`->`evalSpliced`, which wraps that ONE expression (plus the exec-let prefix) into a lambda and pushes it through `Compiler.executeResolved`. `$results` β-reduces to `$tds.rows->map(r|$r.values->makeString('~'))` and `$tds` splices to the ExecFrame's chain, which is deliberately UNRESOLVED (`StatementExecutor.ExecFrame` javadoc: "the from-wrapped typed query chain (unresolved — downstream reads compose over it and resolve as a whole)"). So the statement handed to StoreResolver is `forAll(<literal collection>, e | contains(TypedMap(TypedFrom(TypedProject(TypedGetAll(Person)))), $e))`. `Anchors.spaceOf` classifies the `forAll` TypedNativeCall as ANCHORED (a getAll is beneath it), so `anchoredNode` takes the generic `case TypedNativeCall nc -> structural(...)` arm, which maps children through `resolveNode`. The child that carries the query is the predicate TypedLambda — and `anchoredNode`'s arm `case TypedLambda l -> l;` returns it VERBATIM ("a BARE lambda VALUE is DATA — its consumer owns resolution"). `forAll`'s structural arm is not a consumer that resolves lambda bodies, so nobody ever resolves the embedded chain, and the post-condition `assertNoStoreOnlyEscapees` fires. The reported ancestry `root > TypedNativeCall > TypedLambda > TypedNativeCall > TypedMap > TypedFrom > TypedProject` is exactly forAll > predicate-lambda > contains > map > from > project > getAll. Note the wall's text is misleading: the query shape IS supported (that same TypedFrom resolves fine when it is the statement root — the eager `let result = execute(...)` run succeeded); the traversal simply never reaches it.

**Fix**

core/src/main/java/com/legend/resolver/StoreResolver.java, method `anchoredNode`, replace the arm at line 504 (`case TypedLambda l -> l;`) with one that still treats parameter-dependent object space as DATA but resolves SELF-CONTAINED sub-queries in place:

    case com.legend.compiler.spec.typed.TypedLambda l ->
            l.mapChildren(b -> resolveClosedQueries(b, new java.util.LinkedHashSet<>(l.parameters()), context));

and add:

    /** A bare lambda is DATA, but a SELF-CONTAINED query beneath it (a
     *  from()-wrapped chain that reads no lambda-bound variable — the
     *  driver's spliced execute() frame) has no other owner. */
    private TypedSpec resolveClosedQueries(TypedSpec n, java.util.Set<String> bound, Context context) {
        if (n instanceof com.legend.compiler.spec.typed.TypedLambda inner) {
            java.util.Set<String> b2 = new java.util.LinkedHashSet<>(bound);
            b2.addAll(inner.parameters());
            return inner.mapChildren(c -> resolveClosedQueries(c, b2, context));
        }
        if (n instanceof TypedFrom && SubQueryLift.uncorrelated(n, java.util.Set.of())
                && !readsAnyOf(n, bound)) {
            return resolveNode(n, context);
        }
        return n.mapChildren(c -> resolveClosedQueries(c, bound, context));
    }

Use the SHADOW-AWARE closedness test that already exists rather than writing a new one: `SubQueryLift.uncorrelated(TypedSpec, Set<String> bound)` (core/src/main/java/com/legend/resolver/SubQueryLift.java:97-120) is exactly this predicate — promote it to package-private/static-visible and pass the accumulated `bound` set (plus `letBindings.keySet()`, as SubQueryLift's own caller does). A `TypedFrom` carries its own mapping+runtime, so `resolveNode(from, context)` already does the right thing (StoreResolver.java:297-307). Keep the arm an identity when nothing matches (mapChildren returns the same node when children are unchanged), so predicates/mappers still pass through verbatim.

One consequential change goes with it: the escapee message at StoreResolver.java:225-228 should not claim the shape is unsupported when the getAll sits inside an unresolved lambda — it cost this diagnosis real time. Have `assertNoStoreOnlyEscapees` name the enclosing lambda when the path contains one.

**How legend-engine does it** — Not a semantics question — legend-engine materialises `Result.values` in memory and evaluates `forAll`/`contains` host-side, so it never needs this traversal. legend-lite's own doctrine (core/src/main/java/com/legend/StatementExecutor.java:2040-2046: "Result is a typing surface plus an orchestration handle, NEVER a host object graph (tenet #1: Java orchestrates, the database executes)") is what makes the spliced chain reach the resolver, and the lowerer already has the vocabulary to finish the job (core/src/main/java/com/legend/lowering/Scalars.java:334 `family(SqlFn.LIST_FOR_ALL, "forAll")`, core/src/main/java/com/legend/lowering/Scalars.java:1919-1922 `contains` -> `SqlExpr.Membership`).

**Risk** — Two things to watch. (1) A `TypedFrom` inside a predicate lambda under an outer from() is normally consumed by `SubQueryLift.lift` (StoreResolver.java:297-307) BEFORE this arm can see it; the closedness guard means anything that lift declined is uncorrelated anyway, so resolving it standalone as a relation is the right reading — but a correlated one must keep falling through to the existing loud walls, hence the shadow-aware `bound` set is load-bearing, not decoration. (2) Do NOT 'fix' this in EngineTestExecutor by materialising `$results` host-side into Java strings and comparing there — that is precisely the tenet-2 harness compensation: the collection-derived-from-a-query surface is the platform's, and the ExecFrame doctrine says so explicitly.

**Also unblocks** — Every corpus test using the `assert($expected->forAll(e|$results->contains($e)), |msg)` idiom over an execute()-derived collection — testVariableReferenceInMapWithSameNameAsThatInParentProject (unit-mate), and in the same file testProjectReferenceInFilterWithMultiLevelLhs, testVariableReferenceWithNestedFilterMultiple, and testVariableReferenceInMapWithNestedFilter once its own earlier wall is cleared.

**Falsifier** — If the resolver reaches the lambda but the LOWERER cannot express `contains(<relation>, <scalar>)` as `x IN (subquery)`, the fix moves the wall rather than removing it. Cheapest check: read `core/src/main/java/com/legend/lowering/Lowerer.java` around 3439-3460 (`relation exists/forAll` lowering) and confirm a relation-valued arg in scalar position lowers to a scalar subquery; if it does not, this test needs that rung too and the effort is L, not M.

<details><summary>Evidence read (8 citations)</summary>

- core/src/main/java/com/legend/resolver/StoreResolver.java:503-504 — `// a BARE lambda VALUE is DATA — its consumer owns resolution` / `case com.legend.compiler.spec.typed.TypedLambda l -> l;` (identity: the lambda body is never resolved)
- core/src/main/java/com/legend/resolver/StoreResolver.java:473-478 — `case TypedNativeCall nc -> structural(Pipelines.classEmptinessRewrite(nc, this::objectSpace), context)`; `structural` is `n.mapChildren(k -> resolveNode(k, context))` (line 508-510), so forAll's lambda child is handed straight to the identity arm above
- core/src/main/java/com/legend/resolver/StoreResolver.java:223-240 — `assertNoStoreOnlyEscapees(TypedSpec n, String path)`: throws on a surviving TypedGetAll with `"[at " + path + "]"`, path built as `path + " > " + n.getClass().getSimpleName()` per level — this is the exact message and path format in the failure detail
- core/src/main/java/com/legend/resolver/Anchors.java:43-61,71-81 — `anchored()` descends `n.children()` unconditionally and `spaceOf` returns ANCHORED for anything with a getAll beneath; TypedLambda.children() IS its body (core/src/main/java/com/legend/compiler/spec/typed/TypedLambda.java:23-26), so the predicate lambda classifies ANCHORED and reaches the identity arm
- core/src/main/java/com/legend/harness/EngineTestExecutor.java:1781-1811 — `case "assert", "assertFalse"` evaluates `args.get(0)` only (`evalScalar(args.get(0), ...)`); the message lambda is never compiled, so the statement resolved is the bare `forAll(...)` call
- core/src/main/java/com/legend/harness/EngineTestExecutor.java:2666-2678 — `evalSpliced`: `stmts.addAll(execStmts); stmts.add(expr); ... Compiler.executeResolved(resolved, ...)` — one expression + the exec-let prefix goes through the whole platform pipeline
- core/src/main/java/com/legend/StatementExecutor.java:2049-2055 — ExecFrame doc: "the from-wrapped typed query chain (unresolved — downstream reads compose over it and resolve as a whole)"; core/src/main/java/com/legend/StatementExecutor.java:2385-2420 (`aliasFrame`) and 2600-2606 (`spliceValuesRead` returning `f.chain()`) show `$tds`/`$result.values` splicing that raw chain into the assert expression
- functions/tests/projection/testFunctionVariables.pure:68-76 — the test body: `assert($expected->forAll(e|$results->contains($e)),| 'expected: '+...)` with `let results = $tds.rows->map(r|$r.values->makeString('~'))`

</details>

---

## `testVariableReferenceInMapWithNestedFilter`

| | |
|---|---|
| family | `functions/tests/projection` |
| sweep status | ERROR |
| **verdict** | **MISSING FEATURE** |
| effort | L |
| confidence | medium |

**Root cause**

Two stacked gaps, and the reported wall is the outer one. The project column is `p|$p.firm->map(f|$f.address->filter(a|$a.name==$p.address.name)).name`. (1) RECOGNITION: `SyntheticHeads.liftFilteredHeads` normalizes `->map` into the filtered-head spelling only in two shapes — a map whose MAPPER BODY is a bare property read on the param (line 303-315), or a map whose SOURCE is already the TypedFilter (line 401-424). Here the filter lives INSIDE the mapper body over a further hop (`f|$f.address->filter(...)`), so no arm fires and no synthetic `#fN` head is parked. (2) SUBSTITUTION: with no lift, the leaf read arrives at `Substitution.rewrite` as `TypedPropertyAccess('.name', source=TypedMap($p.firm, f|$f.address->filter(...)))`. `filteredNavLeafRead(pa)` (line 2587) peels class-typed property hops and toOne/first wrappers but NOT a TypedMap, so `src instanceof TypedFilter` is false and it returns null (line 2629-2634). The dispatch then falls to the generic `case TypedPropertyAccess pa -> rebuildWithInstanceFold(pa)` (line 1767), which rewrites children — and rewriting the TypedMap hits the class-result map arm (line 1830) that β-inlines `$f := $p.firm`, producing `TypedFilter(source=$p.firm.address, pred=a|$a.name==$p.address.name)`. That TypedFilter, now a CLASS-typed-source filter in object space, matches no arm (the only TypedFilter arm at line 1848 requires a RelationType source) and lands in the `default` wall at line 1893-1899 — printing exactly the node in the failure detail, `TypedFilter[source=TypedPropertyAccess[source=TypedPropertyAccess[source=TypedVariable[name=p ... Person]]]]` = `$p.firm.address`. Even if the map were normalized first, the recognizer would still decline: `filteredNavLeafRead` requires the filtered head to sit DIRECTLY on the instance var (line 2639-2655 — here it is two hops, `$p.firm.address`) and the read to be scalar (line 2621-2628 rejects a bare [*] read; `.name` over the filtered Address[*] is String[*]). The shape would then reach `registerAssociationJoins`, where a CORRELATED parked pred on a non-zero hop is an explicitly named wall (StoreResolver.java:2376-2386). So the honest end state is: this is the 'correlated filtered navigation as a chained association hop' roadmap item, reached today through a misleading substitution-default wall because the map normalization never happens.

**Fix**

Two changes, in order, and the second is the real work.

(a) core/src/main/java/com/legend/resolver/SyntheticHeads.java, `liftFilteredHeads` (line 296). Add a NORMALIZATION arm ahead of the existing lift arms: a `TypedMap` in object space whose mapper is 1-param and whose body is a navigation chain rooted at that param (property hops and/or a filter over them — not just a bare property read) β-inlines the param with the map source and re-enters the walk. i.e. `$p.firm->map(f|$f.address->filter(pred))` becomes `$p.firm.address->filter(pred[f := $p.firm])`. Reuse the same `inlineParam` substitution Substitution.java:1830 already uses so both paths agree; the arm at 303-315 becomes the degenerate case of the new one.

(b) core/src/main/java/com/legend/resolver/StoreResolver.java:2376-2386 and the machinery behind it. After (a) the path is [firm, #fN(address), name] with a CORRELATED parked pred at hop 1, which is precisely the wall at 2384. Implementing it means extending the hop-0 parent-copy reroute (CorrelatedSubselects; the emission that produces a correlated sub-select over the ROOT table re-joined on its pk) to chained mid hops: the sub-select must re-root at the query's own class (personTable as "persontable_2"), carry the chain's joins (firmTable, addressTable) and the outer-correlated conjunct in its WHERE, project the pk plus the leaf, and LEFT JOIN back on the pk — exactly the golden at testFunctionVariables.pure:97. Do NOT attempt to reach this shape through `filteredNavLeafRead` by relaxing its head/multiplicity gates (Substitution.java:2621-2655): that route builds a SCALAR correlated subquery, and the leaf here is [*] — relaxing the gates would impose toOne semantics on a collection and either raise or silently drop rows.

**How legend-engine does it** — The expected emission is spelled out by the test's own golden (functions/tests/projection/testFunctionVariables.pure:97): a correlated sub-select over personTable joined back on ID, with the correlated predicate (`"addresstable_0".NAME is not distinct from "addresstable_1".NAME`) inside the sub-select's WHERE — the engine's filtered-join-tree-node emission from meta::relational::functions::pureToSqlQuery. Compare the same file's testVariableReferenceWithNestedFilterMultiple and testProjectReferenceInRhsFilterWithDistinctVarNamesViaNonPropertyFunctionExpression (testFunctionVariables.pure:62-66), whose goldens show the identical parent-copy shape at hop 0 — the shape legend-lite already builds.

**Risk** — (a) is low-risk but touches the canonicalizer every filtered-navigation test runs through — a too-greedy map-inline arm would change the lift decision for shapes that today take the computed-mapper aggregation route (SyntheticHeads.java:401-424), so guard it on the mapper body being a pure navigation chain (property hops / filter), never a computed expression. (b) is the risky half: the parent-copy reroute decides join identity and row multiplicity, so getting the chained variant wrong produces WRONG ROWS silently rather than an error. Land (a) alone first and confirm the failure moves from the Substitution default to the named 'correlated filtered navigation as a chained association hop' wall — that transition is itself the proof (a) is correct.

**Also unblocks** — testProjectReferenceInFilterWithMultiLevelLhs and testVariableReferenceInMapWithNestedFunction in the same file (both marked test.ToFix upstream), and any project column spelling a nested filter inside a class-result ->map. Part (a) alone may also unblock shapes where the post-normalization head lands at hop 0 (uncorrelated preds), which take the already-implemented route.

**Falsifier** — If, after (a), the failure does NOT move to StoreResolver.java:2384 but instead to some other wall, my reading of the post-normalization path is wrong. Cheapest observation without a build: set LL_FNLR_DEBUG (the recognizer already prints '[fnlr] not-direct-filter: <class>' at Substitution.java:2630-2632) and confirm the declining source is a TypedMap — that pins gap (1) exactly.

<details><summary>Evidence read (10 citations)</summary>

- core/src/main/java/com/legend/resolver/Substitution.java:1893-1899 — the `default -> { String shape = String.valueOf(n); throw new NotImplementedException("object-space expression node " + n.getClass().getSimpleName() + " is not substitutable yet (H2 vocabulary): " + ...) }` arm, truncating at 220 chars — the exact message and truncation in the failure detail
- core/src/main/java/com/legend/resolver/Substitution.java:1848-1854 — the only TypedFilter arm: `case TypedFilter f when f.source().info().type() instanceof Type.RelationType` — an object-space (class-typed) filter source has no arm, by design ("OBJECT-SPACE filters (class-typed sources) stay loud below")
- core/src/main/java/com/legend/resolver/Substitution.java:1830-1831 — the class-result map arm `rewrite(inlineParam(m.mapper().body().get(0), m.mapper().parameters().get(0), m.source()))`, which is what turns `$p.firm->map(f|$f.address->filter(..))` into `$p.firm.address->filter(..)` — but only AFTER the leaf dispatch already declined
- core/src/main/java/com/legend/resolver/Substitution.java:1746-1747 and 1767 — `case TypedPropertyAccess pa when filteredNavLeafRead(pa) != null -> ...` followed by the generic `case TypedPropertyAccess pa -> rebuildWithInstanceFold(pa)` fallback
- core/src/main/java/com/legend/resolver/Substitution.java:2621-2634 — the two declines: the bare-[*] gate (`// a BARE [*] read ($p.xs->filter(..).name) is a collection, not a scalar` ... `return null`) and `if (!(src instanceof TypedFilter f)) { ... return null; }`
- core/src/main/java/com/legend/resolver/Substitution.java:2639-2655 — the head gate: the filtered head must be `TypedPropertyAccess head` (or TypedMilestonedAccess) whose source is `TypedVariable hv` with `hv.name().equals(target.userVar())`; `$p.firm.address` is two hops and fails it
- core/src/main/java/com/legend/resolver/SyntheticHeads.java:303-315 — the map normalization arm, restricted to `tm.mapper().body().get(0) instanceof TypedPropertyAccess mb && mb.source() instanceof TypedVariable mv && mv.name().equals(tm.mapper().parameters().get(0))` — a bare property read only
- core/src/main/java/com/legend/resolver/SyntheticHeads.java:401-424 — the other map arm, requiring `tm2.source() instanceof TypedFilter f0` (filter as the map SOURCE, not inside the mapper)
- core/src/main/java/com/legend/resolver/StoreResolver.java:2376-2386 — the named next wall: `if (hop > 0 && synthetics.hasPred(path.get(hop)) && synthetics.correlatedPred(path.get(hop)) != null) { throw new NotImplementedException("correlated filtered navigation as a chained association hop ('" ... "') is not supported yet"); }` with the comment "parent-copy reroute serves hop-0 only"
- functions/tests/projection/testFunctionVariables.pure:89-97 — the test body and its golden, which is exactly the parent-copy reroute emission: `left outer join (select "persontable_2".ID as ID, "addresstable_0".NAME as NAME from personTable as "persontable_2" left outer join firmTable ... left outer join addressTable as "addresstable_1" on ("addresstable_1".ID = "persontable_2".ADDRESSID) where "addresstable_0".NAME is not distinct from "addresstable_1".NAME) as "persontable_1" on ("root".ID = "persontable_1".ID)`

</details>

---

## `testVariableReferenceInMapWithSameNameAsThatInParentProject`

| | |
|---|---|
| family | `functions/tests/projection` |
| sweep status | ERROR |
| **verdict** | **REAL DEFECT** |
| effort | M (revised up from XS by adversarial review) |
| confidence | high |
| **adversarial review** | **PARTIALLY_WRONG** |

**Root cause**

Identical mechanism to testVariableReferenceInFilterWithSameNameAsThatInParentProject, and confirmed by the byte-identical failure detail (same message, same ancestry path). This test's statement list is `let result / let tds / let results / let expected / assert(forAll(...), |msg) / assertEquals(<golden SQL>, ...)`; the `assert` at line 84 fails before the golden-SQL assertEquals is ever reached. `EngineTestExecutor.checkAssert`'s assert arm evaluates only the condition, `evalSpliced` compiles `$expected->forAll(e|$results->contains($e))` with `$results` β-reduced and `$tds` spliced to the unresolved ExecFrame chain, and `StoreResolver.anchoredNode`'s `case TypedLambda l -> l;` returns forAll's predicate lambda verbatim, leaving `TypedGetAll(Person)` alive under `TypedMap > TypedFrom > TypedProject`. The variable-shadowing feature this test actually exercises (`e` bound twice — outer project column and inner `->map`) is NOT what fails: the eager `let result = execute(...)` run resolved and executed that query successfully, otherwise the failure path would have been rooted at TypedFrom, not at a forAll call.

**Fix**

The same single change described for testVariableReferenceInFilterWithSameNameAsThatInParentProject: replace `case com.legend.compiler.spec.typed.TypedLambda l -> l;` at core/src/main/java/com/legend/resolver/StoreResolver.java:504 with an arm that descends and resolves closed (uncorrelated, non-parameter-reading) `TypedFrom` sub-trees via `resolveNode`, reusing `SubQueryLift.uncorrelated` (core/src/main/java/com/legend/resolver/SubQueryLift.java:97-120) as the closedness test. No separate work item.

**How legend-engine does it** — Same as the sibling test — the divergence is legend-lite's deliberate 'Result is an orchestration handle, never a host object graph' choice (core/src/main/java/com/legend/StatementExecutor.java:2040-2046), not a Legend semantics question.

**⚠ Correction from adversarial review** — Step one (descend into ANCHORED lambdas and resolveNode the closed TypedFrom sub-trees) is right and low-regression — the identity arm is only reachable when a getAll survives beneath, so nothing that passes today can lose behaviour. But it will not green this test on its own. Two additions: (1) the escapee is TypedMap > TypedFrom, so the descent must find TypedFrom nodes nested under arbitrary wrappers, not just a direct lambda-body child; (2) plan a second work item for the consumer side — forAll over a LITERAL collection lowers to a DuckDB list lambda (Scalars.java:334) while contains over the relation lowers to a Membership/scalar-subquery, and DuckDB refuses subqueries inside lambda bodies. Either unroll forAll over a literal collection into an AND-chain of predicates, or route the whole assert host-side.

<details><summary>Adversarial review notes (PARTIALLY_WRONG)</summary>

The MECHANISM is fully confirmed, the FIX is likely incomplete and the effort is understated. Confirmed: (a) the recorded sweep detail for this test is byte-identical to the Filter sibling — 'store resolution left getAll(...Person) unresolved ... [at root > TypedNativeCall > TypedLambda > TypedNativeCall > TypedMap > TypedFrom > TypedProject]' (units.json), exactly forAll > predicate-lambda > contains > map > from > project; (b) StoreResolver.java:504 really is `case ...TypedLambda l -> l;` under the comment 'a BARE lambda VALUE is DATA' at :503, inside anchoredNode (declared :349), and TypedNativeCall really routes to structural() at :476-478, whose body is `n.mapChildren(k -> resolveNode(k, context))` (:516-518); (c) EngineTestExecutor.checkAssert's `case "assert", "assertFalse"` arm (:1782-1812) evaluates only args.get(0) via evalScalar; evalSpliced (:2666-2679) wraps execStmts+expr and calls Compiler.executeResolved, which delegates to StatementExecutor.execute (Compiler.java:613) — so the assert really is compiled as a standalone statement; (d) the .pure body at 78-87 is as quoted; (e) Anchors.anchored is 'a TypedGetAll exists beneath' (Anchors.java:43-61), so the identity lambda arm is only ever reached in already-doomed trees — regression surface for changing it is genuinely small, which is the one thing that supports XS. WHY NOT CONFIRMED: the fix as written is only step one and the diagnosis's own falsifier concedes it. After resolving the closed TypedFrom inside the predicate you are left with `forAll(<7-string literal collection>, e | contains(TypedMap(<resolved relation>, r|$r.values->makeString('~')), $e))`. forAll over a LITERAL collection does not take RelationPredicates' relation arm (RelationPredicates.java:130-133 needs a relation-typed arg); it takes the collection family `family(SqlFn.LIST_FOR_ALL, "forAll")` (Scalars.java:334), i.e. a DuckDB list lambda — and the contains rule lowers to SqlExpr.Membership over the relation (Scalars.java:1907-1922 / ValueCollections.columnList), i.e. a SUBQUERY inside a lambda body, which DuckDB's binder rejects. Scalars.java:824-828 already carries a 'a subquery element cannot ride the list literal' guard, showing the codebase knows this class of problem. I also found NO passing precedent: the four corpus uses of this idiom are testFunctionVariables.pure:75/85/96/107, belonging to tests at :68 (same ERROR), :78 (this test), :89 (ERRORs earlier with a Substitution wall) and :101 (test.ToFix, not swept) — nothing exercising forAll/contains-over-a-relation passes today. So 'effort XS, no separate work item, confidence high' is optimistic; budget a second item for the forAll/contains-over-relation lowering.

</details>

**Citation issues found in review** — No substantive citation errors. Minor line drift only: StatementExecutor '106-135' is really 109-133; SubQueryLift 'uncorrelated 97-120' is really 101-123 (comment from :94). One omission worth naming: SubQueryLift.uncorrelated is `private static` and takes an explicit bound-set, so 'reusing' it needs a visibility change plus a caller-supplied seed (letBindings keys), not a bare call.

**Risk** — After the resolver fix this test still carries a golden-SQL assertEquals ('select concat("root".FIRSTNAME, ' ', "root".LASTNAME) as "personName", "firmtable_0".LEGALNAME as "firmName" from personTable as "root" left outer join firmTable as "firmtable_0" on ("firmtable_0".ID = "root".FIRMID)'), so it may convert ERROR -> FAIL on SQL text rather than to PASS. That would be a separate, much smaller finding (GOLDEN_TEXT_ONLY) and must not be used to argue against this fix.

**Also unblocks** — Shares its fix with testVariableReferenceInFilterWithSameNameAsThatInParentProject and the other assert/forAll/contains corpus sites.

**Falsifier** — Same as the sibling: if `contains(<relation>, <scalar>)` has no scalar-subquery lowering, the wall relocates instead of disappearing.

<details><summary>Evidence read (5 citations)</summary>

- core/src/main/java/com/legend/resolver/StoreResolver.java:503-504 — the identity `case ... TypedLambda l -> l;` arm that strands the query
- core/src/main/java/com/legend/resolver/StoreResolver.java:223-240 — `assertNoStoreOnlyEscapees` builds the reported `[at root > ... > TypedProject]` path
- core/src/main/java/com/legend/harness/EngineTestExecutor.java:1781-1811 — the assert arm evaluates `args.get(0)` only
- core/src/main/java/com/legend/StatementExecutor.java:106-135 — `let result = execute(...)` becomes an eagerly-run ExecFrame; `let tds = $result.values->at(0)` becomes a frame alias (aliasFrame, line 2385-2420); remaining lets ride `letPrefix` and β-reduce into the asserted expression
- functions/tests/projection/testFunctionVariables.pure:78-87 — the test body, with the `assert(forAll(...), |msg)` statement preceding the golden-SQL assertEquals

</details>

---

## `testCrossMappingJsonToDBWithExplosion`

| | |
|---|---|
| family | `graphFetch/tests` |
| sweep status | ERROR |
| **verdict** | **MISSING FEATURE** |
| effort | L |
| confidence | high |

**Root cause**

crossMapping5 writes M2M explosion bindings (`tradeId* : $src.s_trades.s_tradeId`, `prodId*`, `quantity*`). MappingNormalizer.synthM2M reaches `if (pb.explode())` and throws a ModelException naming the feature; that exception is recorded on the mapping-poison ledger, so ClassSources.classSource later cannot find a binding for T_Trade and reports 'class ... is not mapped in mapping ...' with the poison text appended. The surface is genuinely absent: legend-lite's M2M terminal is a single `map(src | ^Class(...))`, one target instance per source instance, with no positional fan-out. The `*` marker is parsed and carried (ClassMapping.Pure.PropertyBinding.explode / MappingFromProtocol passes pm.explodeProperty()), so the refusal is at synthesis, not at parse.

**Fix**

Implement positional fan-out in MappingNormalizer.synthM2M (core/src/main/java/com/legend/normalizer/MappingNormalizer.java, around line 1355). Partition pb list into exploded and non-exploded exactly as inMemory.pure does. When the exploded set is non-empty: (a) assert all exploded bindings come from the same source collection root (engine asserts `Explosion on target properties from more than one class is not supported.`); (b) replace the terminal `map(src | ^Class(fields))` with an index-aligned zip — emit `map(src | range(0, <len>)->map(i | ^Class(nonExplodedFields..., e_k = <expr_k>->at($i))))` where `<len>` is the exploded expressions' common size; (c) keep the non-exploded fields evaluated once per src. On the relational/JSON side this lowers to an UNNEST-with-ordinality over the JSON array frame (JsonSourceFrame.classSource is the realization point for the S_TradesWrapper extent). Do NOT special-case the test: the same shape is needed by the mft/testExplosion families. Keep the current throw as the fallback for a multi-root explosion.

**How legend-engine does it** — /Users/neemsandv/legend/legend-engine/legend-engine-core/legend-engine-core-pure/legend-engine-pure-code-compiled-core/src/main/resources/core/store/m2m/inMemory.pure:216-238 — transformWithMapping partitions the property mappings on `$pm.explodeProperty`, evaluates each exploded transform into a List<KeyValue>, and calls `zipHorizontally($inputLists)`; :267-270 then does one `$targetClass->dynamicNew(...)` per zipped element. The compiler side only relaxes the multiplicity check: /Users/neemsandv/legend/legend-engine/legend-engine-core/legend-engine-core-base/legend-engine-core-language-pure/legend-engine-language-pure-compiler/src/main/java/org/finos/legend/engine/language/pure/compiler/toPureGraph/ClassMappingThirdPassBuilder.java:152 — `pm._explodeProperty() != null && pm._explodeProperty() ? getMultiplicity("zeromany") : property._multiplicity()`.

**Risk** — An index-aligned zip that silently truncates or pads when the exploded collections have different lengths would produce wrong rows. Engine `zipHorizontally` is the authority on that semantics — mirror it rather than inventing a length policy. Tenet-2 trap: do NOT make the harness skip or precompute the explosion; the fan-out belongs to the normalizer/lowering.

**Also unblocks** — Any test using `prop*` M2M bindings (the mft / testExplosion families named in the throw's own comment).

**Falsifier** — Grep the model for any other producer of an exploded M2M terminal: if `ClassMapping.Pure.PropertyBinding.explode()` has a consumer other than the throw at MappingNormalizer.java:1355, the surface is partly implemented and this is not a clean MISSING_FEATURE.

<details><summary>Evidence read (5 citations)</summary>

- core/src/main/java/com/legend/normalizer/MappingNormalizer.java:1355-1361 — `if (pb.explode()) throw new ModelException(... "M2M explosion '" + pb.propertyName() + "*' is a roadmap feature (index-aligned zip fan-out — one target instance per source element); mapping=" ...)`
- core/src/main/java/com/legend/normalizer/MappingNormalizer.java:1379-1381 — the non-exploded terminal is `new AppliedFunction("map", List.of(source, new LambdaFunction(List.of(srcBind), List.of(buildNewInstance(...)))))`: exactly one instance per source row
- core/src/main/java/com/legend/resolver/ClassSources.java:621-625 — `throw new MappingResolutionException("class '" + classFqn + "' is not mapped in mapping '" + mappingFqn + "'" + ctx.mappingPoison(mappingFqn, classFqn).map(r -> " (" + r + ")")...)` — the observed message shape
- core/src/main/java/com/legend/model/MappingFromProtocol.java:341 — `pm.explodeProperty()` is carried into the model, so the flag survives parsing
- /Users/neemsandv/legend/legend-engine/.../core_relational/relational/graphFetch/tests/testCrossStoreGraphFetch.pure:301-317 — crossMapping5's T_Trade[trade_set] Pure block writes `tradeId*`, `prodId*`, `quantity*` over `~src S_TradesWrapper`

</details>

---

## `testCrossMappingWithRelOpWithJoinKeys`

| | |
|---|---|
| family | `graphFetch/tests` |
| sweep status | ERROR |
| **verdict** | **REAL DEFECT** |
| effort | M |
| confidence | medium |

**Root cause**

The mapping declares `Employee : Relational { +ceoId : Integer[1] : [EmployeeDB]@employee_ceo | ceo.identifier, ... }` — a mapping-LOCAL property whose body is a PropertyMapping.JoinTerminalColumn (join chain + terminal column), not a plain column on the main table. XStorePureEnds.xstoreEndOf builds the end's column view from a Relational set by adding a Col ONLY when `lp.body() instanceof PropertyMapping.Column`; a JoinTerminalColumn body is dropped from `cols` (it is still added to `locals`, which the column-space route never consults). Both ends are Relational, so MappingNormalizer.synthesizeXStoreMapping takes the COLUMN-space route and calls RelationReads.xstore on `$this.ceoId == $that.ceoId`; `$that.ceoId` finds no Col with a column() and no Col with an expr(), and throws "association ...EmploymentAssociation': $that.ceoId has no column binding on the Relation mapping of 'Employee'" — which is recorded as the per-association poison and resurfaces from AssociationJoins.predicateMaterial as "association ... is not mapped in mapping ...". Note the class-level path already handles this binding correctly: MappingNormalizer.translatePmToField's LocalProperty arm delegates to the JoinTerminalColumn arm, which reads the joined column through the pipeline alias. Only the XStore column-view construction drops it.

**Fix**

In core/src/main/java/com/legend/normalizer/XStorePureEnds.java, xstoreEndOf's Relational branch (lines 92-121): when the set carries at least one LocalProperty whose body is NOT a PropertyMapping.Column (JoinTerminalColumn, Expression, Embedded), return the XEnd with `colsView = null` and the PURE/property-space flag set (add a distinct `columnSpace=false` field rather than overloading `pure()` if that reads better), keeping `localProps` fully populated as it already is. MappingNormalizer.synthesizeXStoreMapping (line 1122) then routes to XStorePureEnds.synthesize, which emits `legacyLocalProperty($row,'ceoId')`; AssociationJoins.propertyCondToColumns (core/src/main/java/com/legend/resolver/AssociationJoins.java:1246-1300) substitutes each side through its set's COMPOSED bindings, where `ceoId` is already bound to the joined-slot read produced by MappingNormalizer.translatePmToField. Verify that XStorePureEnds.synthesize's `new CString(endA.setId())` is non-null for a Relational set (MappingNormalizer.setIdOf(rcm)); if a set id can be absent, pin it before emitting. Do not try to widen ClassMapping.RelationFunction.Col.expr() to carry the join — a join needs FROM-clause participation and cannot be a row expression.

**How legend-engine does it** — The engine never needs a column view for an XStore condition: the cross-store predicate is evaluated over MATERIALIZED objects, and a mapping-local property is produced by the owning set's own relational query (join included). That is why `+ceoId` may ride a join chain. See /Users/neemsandv/legend/legend-engine/.../core_relational/relational/pureToSQLQuery/pureToSQLQuery.pure:1719-1731 (doJoinToClass resolves the property mapping's own join tree against its target set) — the join is part of the set's query, not of the association condition.

**Risk** — The property-space route (route A) is today exercised only when at least one end is a ClassMapping.Pure. Sending two Relational ends through it is new traffic for AssociationJoins.propertyCondToColumns; the target-side substitution must honour slot prefixes for the joined local (targetSlotPrefixes) or the emitted ON clause will reference an unmaterialized alias. Tenet-2 trap: do not make the harness pre-resolve the local key.

**Also unblocks** — Any XStore/ModelJoin whose condition reads a `+local` bound by a join chain or by an expression (`toString([db]col)`) on a Relational end.

**Falsifier** — Set LEGEND_LITE_STACKS and run the test: if the stack under the recorded poison is NOT RelationReads.rewrite line 123 (i.e. the throw comes from somewhere else, e.g. an earlier XStorePureEnds.xstoreEndOf 'resolves to no Relation or Relational set'), this diagnosis is wrong. Cheaper still: edit a scratch copy of the mapping to make `+ceoId` a plain column on employee and re-run — if it then progresses past the not-mapped wall, the join-terminal local is confirmed as the sole blocker.

<details><summary>Evidence read (7 citations)</summary>

- core/src/main/java/com/legend/normalizer/XStorePureEnds.java:103-112 — `} else if (pm instanceof PropertyMapping.LocalProperty lp) { if (lp.body() instanceof PropertyMapping.Column lc) { cols.add(new Col(lp.propertyName(), lc.column(), true)); } locals.add(lp.propertyName()); }` — a non-Column body contributes no Col
- core/src/main/java/com/legend/normalizer/RelationReads.java:117-127 — after the plain-column and expr() loops both miss, `throw new NotImplementedException("association '" + assocName + "': $" + var.name() + "." + ap.property() + " has no column binding on the Relation mapping of '" + rf.className() + "'")` — matches the truncated detail `(association '...EmploymentAssociation': $that.ceo…`
- core/src/main/java/com/legend/normalizer/MappingNormalizer.java:1122-1160 — synthesizeXStoreMapping takes route A only when `endA.pure() || endB.pure()`; otherwise it uses `endA.colsView()` / `endB.colsView()` and calls `RelationReads.xstore(...)`
- core/src/main/java/com/legend/resolver/AssociationJoins.java:1166-1176 — the assoc-binding lookup `.orElseThrow(() -> new MappingResolutionException("association '" + assoc.qualifiedName() + "' is not mapped in mapping '" + cs.mappingFqn() + "'" + ...mappingPoison(...)))` — the observed outer message
- core/src/main/java/com/legend/normalizer/MappingNormalizer.java:2612-2645 — translatePmToField's JoinTerminalColumn arm builds `$row.<alias>.<col>` through the pipeline, and the LocalProperty arm wraps it with `isLocal=true`: the class-level binding for ceoId DOES exist
- core/src/main/java/com/legend/model/PropertyMapping.java:179-201 — `record JoinTerminalColumn(propertyName, database, joins, terminalColumn, ...)` is the model form of `[DB]@J | T.COL`, distinct from Column
- /Users/neemsandv/legend/legend-engine/.../core_relational/relational/graphFetch/tests/testCrossDatabaseGraphFetch.pure:75-92 — `Employee : Relational { +ceoId : Integer[1] : [EmployeeDB]@employee_ceo | ceo.identifier, ... }` and `EmploymentAssociation : XStore { employees : $this.ceoId == $that.ceoId }`

</details>

---

## `testBusinessDateInjectionFromVarReferenceInProjectUsingExternalFunction`

| | |
|---|---|
| family | `milestoning/tests` |
| sweep status | ERROR |
| **verdict** | **REAL DEFECT** |
| effort | L |
| confidence | medium |

**Root cause**

`filterOrders($o)` inlines to `$o->filter(o2|$o2.product($o2.orderDate->toOne()).type=='STOCK')->map(x|$x.id)`. TemporalFrame.collectTemporalNodes composes an INNER cursor only when the filtered/mapped/projected SOURCE is a NAVIGATION path off the instance var (`Substitution.pathOf(tf.source(), userVar)` must be non-null, TemporalFrame.java:2061-2072). Here the filter's source is the instance VARIABLE itself (`$o`), and pathOf returns null for a bare variable (Substitution.java:772: `if (!(n instanceof TypedPropertyAccess pa)) return null;`). So the arm does not fire, the walk descends into the predicate with the OUTER userVar still bound, reaches `$o2.product(...)` whose root is the inner param, `pathOf(ma, "o")` is null, and the guard at TemporalFrame.java:2018-2022 throws 'milestoned property access ... on a NESTED navigation is not supported yet'. The filter over the instance is an IDENTITY cursor move — the code has no arm for a zero-length chain.

**Fix**

In TemporalFrame.collectTemporalNodes, add the IDENTITY-CURSOR arm before the existing pathOf tests (TemporalFrame.java:2061 for TypedFilter, 2096 for TypedMap, 2077 for TypedProject): when the source is `TypedVariable(userVar)`, the cursor does NOT move — β-inline the inner lambda's param with the source and recurse with the SAME userVar and the SAME prefix, e.g. for TypedFilter: `if (tf.source() instanceof TypedVariable v && v.name().equals(userVar) && tf.predicate().parameters().size() == 1) { for (TypedSpec bb : tf.predicate().body()) collectTemporalNodes(Substitution.inlineParam(bb, tf.predicate().parameters().get(0), tf.source()), userVar, out, prefix); return; }`. That registers the spec under chain key 'product' with dates `$o.orderDate->toOne()` — byte-identical to the passing sibling at testBusinessDateMilestoning.pure:561. IMPORTANT, do not stop there: removing the wall does not make the test green. The query then substitutes through Substitution's filteredInstanceRead arm (Substitution.java:1738-1745), which emits CASE WHEN pred THEN id ELSE NULL, whereas the engine golden emits `select "root".id ... left outer join (select id from OrderTable ... where type='STOCK') as "ordertable_1" on ("root".id = "ordertable_1".id)` — i.e. the engine returns root.id for EVERY order while the CASE-WHEN returns NULL for non-matching ones. Making this test pass also requires the project-of-instance-filter emission to become the engine's parent-copy LEFT-JOIN subselect (same shape as the golden at testBusinessDateMilestoning.pure:571-575). If only the collector is fixed, ledger the residual as a row divergence rather than declaring victory.

**How legend-engine does it** — legend-engine/.../pureToSQLQuery/pureToSQLQuery.pure:3963 — `let milestoningContext = $newQuery.milestoningContext->concatenate($operation.milestoningContext)->first();` and :3975 — the FunctionDefinition arm passes that context into the lambda's `sourceOp`: an inner lambda INHERITS the enclosing operation's milestoning context, which is exactly the identity-cursor rule this fix encodes.

**Risk** — β-inlining the predicate param into the collected date expressions duplicates the source node; harmless for a bare variable, but do not generalize the inline to non-variable sources without also composing the prefix. Tenet-2 trap: the test asserts SQL only, so it is tempting to relax the harness's assertSameSQL — do not; the row divergence described above is real.

**Falsifier** — Check testBusinessDateInjectionFromVarReference (testBusinessDateMilestoning.pure:560) in the same sweep. If it also fails with 'on a NESTED navigation', then the identity-cursor story is wrong and the collector is broken for the plain dated-access shape too.

<details><summary>Evidence read (6 citations)</summary>

- core/src/main/java/com/legend/resolver/TemporalFrame.java:2018 — `List<String> maPath = Substitution.pathOf(ma, userVar); if (maPath == null) throw new NotImplementedException("milestoned property access '" + ma.property() + "' on a NESTED navigation is not supported yet")` — the exact message observed
- core/src/main/java/com/legend/resolver/TemporalFrame.java:2061 — the TypedFilter composition arm: `List<String> fp = Substitution.pathOf(tf.source(), userVar); if (fp != null) { ...recurse with prefix + chain + "."... return; }` — no arm when fp == null
- core/src/main/java/com/legend/resolver/Substitution.java:772 — pathOf returns null for anything that is not a TypedPropertyAccess/TypedMilestonedAccess/subType/map chain, so a bare `$o` yields null (not the empty chain)
- milestoning/tests/testBusinessDateMilestoning.pure:678-681 (corpus) — `filterOrders(order:Order[1]) { $order->filter(o|$o.product($o.orderDate->toOne()).type=='STOCK')->map(x|$x.id) }` — the filter source IS the parameter
- milestoning/tests/testBusinessDateMilestoning.pure:560-562 (corpus) — the sibling `Order.all()->filter(o|$o.product($o.orderDate->toOne()).type=='STOCK')` has the dated access on the SAME lambda var, i.e. the shape the collector already handles
- core/src/main/java/com/legend/resolver/Substitution.java:2213 — `static TypedSpec inlineParam(TypedSpec n, String param, TypedSpec source)` is package-visible, so TemporalFrame can β-inline the predicate param

</details>

---

## `testRoutingWithSubtypePropagation`

| | |
|---|---|
| family | `router/tests` |
| sweep status | ERROR |
| **verdict** | **REAL DEFECT** |
| effort | L |
| confidence | low |

**Root cause**

`$x.employees->subType(@PersonExtension).manager->subType(@PersonExtension).name` canonicalizes (Substitution.pathOf's subType arm, Substitution.java:742-771) to the path [employees, stc_<PE>___manager, stc_<PE>___firstName/lastName]. `employees` is a Join PM, so it registers through the NAV-slot route and its AssocSub is built from `navMats.get(alias).subNavs()` (StoreResolver.java:1512-1531). The reported diagnostics say `head subNavs=[]`: the MID hop `stc_<PE>___manager` produced no SubNav, so rewriteMultiHop has no walk to take — the SubNav branch is skipped, the chain key 'employees.stc_<PE>___manager' is not in assocs, the embedded-ctor drill fails (the head binding is a nav read, not a ctor), the HEAD-JOIN+EMBEDDED-TAIL arm fails for the same reason, the flat `stc__` union-column arm finds no such binding, and the wall at Substitution.java:1338-1345 fires. For a SubNav to exist, the Person ClassSource under this mapping must carry `stc_<PE>___manager` BOTH as a pseudo-binding and as a transplanted TypedNavigate (ClassSources.java:735-780, 793-798) — NavMaterializer looks it up by exactly that key (NavMaterializer.java:133-134 / 462) and `SyntheticHeads.realHead` does not strip the stc prefix (SyntheticHeads.java:977-979). Everything else on the path is name-agnostic, so the missing piece is the class-typed (nav-slot) subtype transplant for the cast hop. A sibling failure in another unit corroborates: 'property stc_<Bicycle>___person of class RoadVehicle has no binding' — the same pseudo-binding absent for a class-typed cast leaf.

**Fix**

Make the subtype-cast hop a first-class navigation hop. (1) ClassSources.java:735-780: ensure the class-typed arm of the same-source subtype transplant actually fires for this mapping — the two silent skips to check are the swallowed subclass build (ClassSources.java:719-723) and the root-table gate (ClassSources.java:724-731 / sameRootTableUnderSubstitution at 1122-1149) across `include simpleRelationalMappingInc[dbInc->db]`; a subclass that cannot be built must at minimum record WHY instead of vanishing, so the cast read walls with an honest message instead of an empty-subNavs multi-hop wall. (2) Wherever a hop's target class is resolved by NAME, decode an stc head instead of failing: AssociationJoins.hopTargetClass (AssociationJoins.java:921-936) and toOneClassProp (AssociationJoins.java:906-919) should, when `ClassMapping.isSubTypeColumn(prop)`, split the prefix with `ClassMapping.classOfWitnessPrefix(pfx, candidates)` (ClassMapping.java:66-79 — the model-driven inverse, never string surgery) and look the tail property up on that SUBCLASS; that also unblocks chainNavTails (AssociationJoins.java:884-892) so the assoc route can register the mid hop when the head is not a nav slot. (3) With (1) in place the existing NavMaterializer path needs no change: `t.bindings().get(stc key)` is a nav-slot read, demandSlotSubTail (NavMaterializer.java:444-495) demands the transplanted step and subTree (NavMaterializer.java:305-318) registers the SubNav under 'stc_<PE>___manager', after which Substitution.java:1206-1247 resolves the leaf through the sub-target's bindings.

**Risk** — Transplanting a subclass's class-typed slot onto the parent source adds a join to every query that touches the parent when the demand scan mis-attributes the hop; keep the demand gating (only tails that actually read through the stc head). Tenet-2 trap: do not make the test pass by teaching the harness to rewrite subType(@X).p into p — the cast is a mapping-dispatch decision the resolver owns.

**Also unblocks** — testDataGeneration/tests: 'multi-hop navigation vehicles#f1.stc_...Bicycle___person.name ... head subNavs=[]' (same empty-subNav stc mid hop); possibly tests/mapping/inheritance's 'property stc_...Bicycle___person of class RoadVehicle has no binding', which is the same missing pseudo-binding one step earlier

**Falsifier** — Dump `sources.getForNav("meta::relational::tests::TestMappingOfSubtypeClass", "...simple::Person", "employees").bindings().keySet()` and `Pipelines.navSteps(that.pipeline()).keySet()`. If `stc_meta__relational__tests__model__simple__PersonExtension___manager` is present in BOTH, the transplant is fine and the defect is instead in NavMaterializer's SubNav assembly (subMats/subTree, NavMaterializer.java:236-318) — this diagnosis's step (1) would then be wrong.

<details><summary>Evidence read (9 citations)</summary>

- core/src/main/java/com/legend/resolver/Substitution.java:1338 — the wall: `throw new NotImplementedException("multi-hop navigation " + String.join(".", path) + " through an embedded/slot head ... [assocs=...; head subNavs=...; head binding=...]")`, the exact message observed
- core/src/main/java/com/legend/resolver/Substitution.java:1170-1205 — the SubNav walk (`a3.subNavs().get(path.get(1))`, children walk, ctorTailLeaf) — all skipped when subNavs is empty
- core/src/main/java/com/legend/resolver/Substitution.java:742 — pathOf's subType canonicalization: `comp = ClassMapping.subTypeColumn(sct.fqn(), pa0.property())`, producing the stc_ mid segment
- core/src/main/java/com/legend/resolver/ClassSources.java:735-780 — the same-source subtype transplant, including the class-typed slot arm that mints `stcNavTransplants` and `bindings.putIfAbsent(stcAlias, ...)`, gated on `sameRootTable(...) || sameRootTableUnderSubstitution(mapping, ...)` (ClassSources.java:724-731) and on `get(mappingFqn, cb.classFqn())` not throwing (ClassSources.java:719-723, which SILENTLY swallows a build failure)
- core/src/main/java/com/legend/resolver/NavMaterializer.java:133 — `TypedSpec b = t.bindings().get(SyntheticHeads.realHead(tail.get(0)))`; when null the tail only gets the size-2 association fallback (NavMaterializer.java:135-169), which cannot fire for an stc head because `assocTargetClassOf(Person, "stc_...___manager")` finds no association
- core/src/main/java/com/legend/resolver/SyntheticHeads.java:977 — `realHead` = `JoinIdentity.of(head).prop()`, which strips only #fN/#dN, never the stc_ prefix (displayName at :986 strips it, but is documented as never consulted by dispatch)
- core/src/main/java/com/legend/resolver/AssociationJoins.java:884-892 and :921-936 — hopTargetClass returns null for stc pseudo-hops (findProperty/findAssociationOf on the un-stripped name), and chainNavTails BREAKS there, so no assoc-route registration exists for the mid hop either
- tests/relationalSetUp.pure:1135-1155 (corpus) — TestMappingOfSubtypeClass maps PersonExtension over [dbInc] personTable with `manager : [dbInc]@Person_Manager`, while Person arrives through `include simpleRelationalMappingInc[dbInc->db]` (tests/relationalSetUp.pure:787) — i.e. the transplant must cross the store substitution
- tests/testModel/simpleTestModel.pure:230 — `Class PersonExtension extends Person`, and manager is declared on Person (simpleTestModel.pure:167), so the cast hop re-maps an INHERITED property

</details>

---

## `columnValueDifferenceTest`

| | |
|---|---|
| family | `tds/tests` |
| sweep status | ERROR |
| **verdict** | **REAL DEFECT** |
| effort | S (revised up from XS by adversarial review) |
| confidence | high |
| **adversarial review** | **CONFIRMED** |

**Root cause**

Variable-capture in the statement-executor's result-frame splice, caused by a dead shadowing guard in UserCallInliner. The test binds `let r = execute($q, ...)` (an exec FRAME, keyed by the name "r") and later asserts over `$relationalResult.rows->map(r|$r.values->makeString('|'))->joinStrings(';')` — the map's lambda binder is also named `r`. StatementExecutor.spliceHook has an explicit guard for exactly this corpus shape: it drops shadowed names from the frame map using the `boundVars` set the inliner passes. But UserCallInliner.lambda(l, env) records the lambda's binders in `bound` ONLY in the `env.isEmpty()` branch, and UserCallInliner.inlineBody builds a non-empty `scope` from the query-level lets and passes it as `env` for the final statement. These two tests put `let inMemoryResult = ...` (a non-execute let) into `letPrefix`, so `scope` is non-empty, `bound` stays empty, `boundVars` is empty inside the lambda, and the guard never fires. The hook's bare-frame-variable arm (`if (n instanceof TypedVariable bv && execFrames.containsKey(bv.name())) return execFrames.get(bv.name()).chain();`) then replaces the lambda's OWN `$r` with the frame's `TypedFrom(...)` query chain, and the enclosing `.values` is rebuilt over it. That reproduces the reported ancestry exactly: root(joinStrings TypedNativeCall) > TypedMap > TypedLambda > TypedNativeCall(makeString) > TypedCollection > TypedPropertyAccess(.values) > TypedFrom > TypedSort > TypedConcatenate > ... > TypedGetAll(Trade). The duplicated class query, now sitting inside a row-map lambda, matches no resolver arm, so StoreResolver.assertNoStoreOnlyEscapees walls. The passing siblings (columnValueDifference_DupeJoinKeys_Test, columnValueDifference_ExtraCols_Test) have neither a `map(r|...)` binder nor a plain let in letPrefix.

**Fix**

core/src/main/java/com/legend/compiler/spec/UserCallInliner.java, method `lambda(TypedLambda l, Map<String,TypedSpec> env)` (line 468): hoist the binder bookkeeping out of the `env.isEmpty()` branch so BOTH branches record the lambda's binders for the duration of the body rewrite. In the α-renaming branch record BOTH the source parameter names (`l.parameters()`) and the fresh names returned by `bind(...)` — the hook fires on nodes both before and after env substitution, so both spellings must be shadow-protected. Structure it as a single try/finally around the whole method body: `l.parameters().forEach(p -> bound.merge(p, 1, Integer::sum)); try { ...existing two branches, adding the fresh names to `bound` as they are minted... } finally { ...decrement each... }`. Apply the same treatment to the lambda-local `TypedLet` binder loop in the α-renaming branch (line ~493) so a lambda-local `let r = ...` also shadows an exec frame. Expect these two tests to advance past this wall but not necessarily to green: statement `assertEquals($relationalResult->toCSV(), $inMemoryResult->toCSV())` still requires the IN-MEMORY columnValueDifference (a LEFT/RIGHT-outer TDS join plus concatenate) over two separate execute() results, which is the next thing to verify.

**⚠ Correction from adversarial review** — Fix is right as written. Two implementation notes: the decrement must mirror the existing `bound.compute(p, (k,c) -> c == null || c <= 1 ? null : c - 1)` idiom rather than a plain remove (nested same-named binders), and the try/finally must wrap the α-renaming branch's early `return new TypedLambda(params, body, l.info())` at :503. Recording the fresh `_iN` names is harmless but unnecessary: reserveFreshNames (:135-155) guarantees `_iN` can never collide with a user let/frame name.

<details><summary>Adversarial review notes (CONFIRMED)</summary>

Mechanism and fix both hold up, and the recorded ancestry is near-decisive on its own. The sweep detail is 'store resolution left getAll(...Trade) unresolved ... [at root > TypedNativeCall > TypedMap > TypedLambda > TypedNativeCall > TypedCollection > TypedPropertyAccess > TypedFrom > TypedSort > TypedConcate...]'. assertNoStoreOnlyEscapees walks children in order (StoreResolver.java:236-239), so the map's SOURCE was reported clean and the copy under the MAPPER was not — that is only explicable by the query chain appearing a second time inside the row lambda, i.e. capture of the lambda's own $r. Every link checks out: UserCallInliner.rewrite fires `hook.apply(n, bound.keySet())` before the switch (:304-309); lambda() records binders in `bound` ONLY inside `if (env.isEmpty())` (:471-483) and the α-renaming branch (:484-503) never touches `bound`; inlineBody builds `scope` from the preceding lets and rewrites the final statement with it (:116-131); StatementExecutor builds `single = letPrefix + stmt` and hands it to inlineBody with spliceHook (:186-191), so a non-empty letPrefix means a non-empty env means an inert guard; the guard is exactly `execFrames.keySet().removeAll(boundVars)` gated on !boundVars.isEmpty() (:2434-2444) and the bare-frame-variable arm is `if (n instanceof TypedVariable bv && execFrames.containsKey(bv.name())) return execFrames.get(bv.name()).chain();` (:2608-2611). letPrefix is provably non-empty here: `let r = execute(...)` becomes a frame (:130-132) and `let relationalResult = $r.values` / `let rawPrevTradeDate = $rawPrevTradeDateR.values` become alias frames (aliasFrame :2385-2422), but `let rawTradeDate = execute(...).values` does NOT — aliasFrame peels .values and finds a TypedNativeCall, not a frame variable, and the execute-unwrap at :121-126 only strips TypedFrom — so it falls to `letPrefix.add(let)` at :170, as does `let inMemoryResult`. The sibling contrast also holds: testTdsExtension.pure:260 and :328 assert only `$r.values.columns.name`, with no lambda binder at all, and the harness forwards only execute-touching lets (EngineTestExecutor:444-448), so their letPrefix is empty and the env.isEmpty() branch runs. The fix (hoist the binder bookkeeping so both branches populate `bound`, plus the lambda-local TypedLet binders) makes boundVars contain 'r', the guard drops the 'r' frame for the body walk, and both spliceValuesRead and the bare-variable arm stop firing on the row variable. Regression surface is limited to lambdas whose binder name collides with an exec-frame name — precisely the shape being fixed.

</details>

**Citation issues found in review** — All cited code exists and says what is claimed, but several line numbers drift by 1-15: UserCallInliner '469-482' is 470-483 and '115-128' is 116-131; StatementExecutor '2436-2445' is 2434-2444, '2604-2610' is 2602-2611, '167-176' — the letPrefix.add is at :170; EngineTestExecutor '459-467' is really 444-448 (the cited range is inside an unrelated JSON-assert block). One factual imprecision, not an error in the conclusion: the diagnosis attributes the non-empty letPrefix solely to `let inMemoryResult`, but `let rawTradeDate = execute(...).values` also lands in letPrefix (aliasFrame rejects it because the peeled base is an execute call, not a frame variable) — so the scope would be non-empty even without inMemoryResult.

**Risk** — Recording the ORIGINAL parameter names in `bound` during an inlined (non-empty-env) rewrite could suppress a splice for a free variable of the same name inside a callee body. Callee bodies are closed by construction (UserCallInliner class comment, line ~31), so a free `$r` inside one cannot legitimately mean the caller's exec frame — the risk is theoretical. No tenet-2 trap: the fix is in the compiler's inliner, not in the harness; the harness-side sibling (HarnessSubstitution.java:108-125) already handles shadowing correctly and needs no change.

**Also unblocks** — columnValueDifferenceWithoutPrevalTest (same mechanism). More generally, any corpus test that binds `let <n> = execute(...)`, reuses `<n>` as a lambda parameter, and has at least one plain (non-execute, non-effect) let ahead of the assert.

**Falsifier** — Copy the test with the map binder renamed (`->map(rr|$rr.values->makeString('|'))`) and run it: if the getAll escapee still appears with the same ancestry, the capture is not the mechanism. Equivalently, print `boundVars` inside StatementExecutor.spliceHook while resolving this statement — it must be non-empty for the guard to work, and this diagnosis predicts it is empty.

<details><summary>Evidence read (10 citations)</summary>

- core/src/main/java/com/legend/compiler/spec/UserCallInliner.java:469-482 — `if (env.isEmpty()) { l.parameters().forEach(p -> bound.merge(p, 1, Integer::sum)); try { ... } finally { ...decrement... } }` — binder tracking exists ONLY on the empty-env path; the α-renaming branch below it never touches `bound`
- core/src/main/java/com/legend/compiler/spec/UserCallInliner.java:115-128 — inlineBody: `Map<String, TypedSpec> scope = new LinkedHashMap<>(); for (...) scope.put(let.name(), rewrite(let.value(), scope)); ... rewrite(last, scope)` — the final statement is rewritten with a NON-EMPTY env whenever any let precedes it
- core/src/main/java/com/legend/compiler/spec/UserCallInliner.java:303-308 — `TypedSpec h = hook.apply(n, bound.keySet());` fires before the switch at every node, with `bound` as the only shadowing information the hook receives
- core/src/main/java/com/legend/StatementExecutor.java:2436-2445 — the guard, whose own comment names this corpus shape: "a lambda-bound variable spelled like an exec-let is NOT a frame read (corpus: `let r = execute(...)` + `->map(r|$r.values...)`)" — `execFrames.keySet().removeAll(boundVars)`
- core/src/main/java/com/legend/StatementExecutor.java:2604-2610 — `TypedSpec direct = spliceValuesRead(n, execFrames, ...); ... if (n instanceof TypedVariable bv && execFrames.containsKey(bv.name())) return execFrames.get(bv.name()).chain();` — the arm that injects the whole query chain in place of the shadowed `$r`
- core/src/main/java/com/legend/StatementExecutor.java:109-176 — `let r = execute(...)` becomes an ExecFrame and is NOT added to letPrefix; a non-execute, non-effect let (`inMemoryResult`) IS added via `letPrefix.add(let)`, which is what makes the inlineBody scope non-empty
- core/src/main/java/com/legend/StatementExecutor.java:2255-2259 — `chain = new TypedFrom(chain, Optional.of(mref), runtime, ...)` — the frame chain is TypedFrom-rooted, matching the observed `TypedPropertyAccess > TypedFrom > TypedSort > TypedConcatenate`
- core/src/main/java/com/legend/resolver/StoreResolver.java:222-233 — `throw new NotImplementedException("store resolution left getAll(" + ga.classFqn() + ") unresolved — the query shape around it is not supported by the resolver yet [at " + path + "]")`, path built as `path + " > " + n.getClass().getSimpleName()` over `n.children()`: the reported path is a pure ancestor chain, so the getAll IS under the lambda
- core/src/main/java/com/legend/harness/EngineTestExecutor.java:459-467 — `if (containsExecute(rhs) || referencesAny(rhs, execVars)) { execStmts.add(...); execVars.add(name.value()); ... }` — `r`, `relationalResult` and `inMemoryResult` all land in the forwarded statement list
- /Users/neemsandv/legend/legend-engine/.../core_relational/relational/tds/tests/testTdsExtension.pure:128-149 — `let r = execute($q, ...)` … `$relationalResult.rows->map(r|$r.values->makeString('|'))->joinStrings(';')`; the sibling DupeJoinKeys/ExtraCols tests at :255 and :327 assert only `$r.values.columns.name` and have no such binder

</details>

---

## `columnValueDifferenceWithoutPrevalTest`

| | |
|---|---|
| family | `tds/tests` |
| sweep status | ERROR |
| **verdict** | **REAL DEFECT** |
| effort | S (revised up from XS by adversarial review) |
| confidence | high |
| **adversarial review** | **CONFIRMED** |

**Root cause**

Identical mechanism to columnValueDifferenceTest — the sweep reports byte-identical failure detail. `let r = execute($q, ...)` creates an exec frame keyed "r"; `let inMemoryResult = $rawTradeDate->columnValueDifference($rawPrevTradeDate, ...)` is a non-execute let and so is pushed onto `letPrefix`; the final assert `$relationalResult.rows->map(r|$r.values->makeString('|'))->joinStrings(';')` is rewritten by UserCallInliner.inlineBody with a NON-EMPTY scope, which skips the `env.isEmpty()` branch of UserCallInliner.lambda and therefore never populates `bound`; StatementExecutor.spliceHook receives an empty `boundVars`, its shadow guard is inert, and the bare-frame-variable arm replaces the map lambda's own `$r` with the frame's TypedFrom-rooted query chain. The duplicated class query inside the row-map lambda survives store resolution and StoreResolver.assertNoStoreOnlyEscapees walls with the ancestry root > TypedNativeCall > TypedMap > TypedLambda > TypedNativeCall > TypedCollection > TypedPropertyAccess > TypedFrom > TypedSort > TypedConcatenate.

**Fix**

Same single change as columnValueDifferenceTest: in core/src/main/java/com/legend/compiler/spec/UserCallInliner.java hoist the `bound` binder bookkeeping out of the `env.isEmpty()` branch of `lambda(...)` so it wraps both branches, recording the original parameter names and the α-renamed fresh names, and extend it to the lambda-local TypedLet binders. No separate work for this test. As with its sibling, expect the next wall to be the in-memory columnValueDifference join over two execute() results (`assertEquals($relationalResult->toCSV(), $inMemoryResult->toCSV())`), so do not assume green from this fix alone.

**⚠ Correction from adversarial review** — None beyond the sibling's notes (mirror the existing decrement idiom; wrap the α-renaming branch's early return in the try/finally). The caveat that the earlier `assertEquals($relationalResult->toCSV(), $inMemoryResult->toCSV())` may be the next wall is speculative — that statement precedes the failing one in source order and did not ERROR in the sweep, so it is currently passing or being skipped as unsupported; fixing the capture may therefore green the test or convert a silent skip into an honest FAIL.

<details><summary>Adversarial review notes (CONFIRMED)</summary>

Same mechanism as its sibling and it holds for the same verified reasons. The recorded detail in units.json is byte-identical to columnValueDifferenceTest (getAll(Trade) at root > TypedNativeCall > TypedMap > TypedLambda > TypedNativeCall > TypedCollection > TypedPropertyAccess > TypedFrom > TypedSort > TypedConcate...), so the getAll is again under the mapper while the map's source resolved clean — the duplicate-chain capture. The .pure body at 152-226 has the identical statement shape: `let rawTradeDate = execute(...).values` (falls to letPrefix — aliasFrame at StatementExecutor:2385-2422 rejects it because the peeled base is an execute call, not a frame variable), `let rawPrevTradeDate = $rawPrevTradeDateR.values` (alias frame), `let r = execute($q,...)` (ExecFrame, StatementExecutor:130-132), `let relationalResult = $r.values` (alias frame), `let inMemoryResult = ...` (letPrefix, :170), then the `->map(r|$r.values->makeString('|'))->joinStrings(';')` assert at :225. Non-empty letPrefix → non-empty inlineBody scope (UserCallInliner:116-131) → α-renaming branch of lambda() (:484-503) → `bound` never populated → spliceHook's guard (:2434-2444) is inert → the bare-frame-variable arm (:2608-2611) replaces the mapper's own $r with the frame chain. The single UserCallInliner fix covers this test with no separate work, exactly as claimed.

</details>

**Citation issues found in review** — Same drift as the sibling: UserCallInliner '469-482' is 470-483 and '115-128' is 116-131; StatementExecutor '2436-2445'/'2604-2610' are 2434-2444/2602-2611; '167-176' — the letPrefix.add is the single line :170. Content is correct in every case. Same imprecision as the sibling: the non-empty scope is not caused by `inMemoryResult` alone — `let rawTradeDate = execute(...).values` is already a letPrefix entry.

**Risk** — Same as columnValueDifferenceTest.

**Also unblocks** — columnValueDifferenceTest.

**Falsifier** — Rename the map binder in a scratch copy from `r` to `rr`; if the escapee persists unchanged, the capture is not the mechanism.

<details><summary>Evidence read (5 citations)</summary>

- core/src/main/java/com/legend/compiler/spec/UserCallInliner.java:469-482 — binder tracking confined to the `env.isEmpty()` branch
- core/src/main/java/com/legend/compiler/spec/UserCallInliner.java:115-128 — inlineBody passes the query-level let scope as `env` to the final statement's rewrite
- core/src/main/java/com/legend/StatementExecutor.java:2436-2445 and :2604-2610 — the inert guard and the bare-frame-variable splice arm
- core/src/main/java/com/legend/StatementExecutor.java:167-176 — `letPrefix.add(let)` for a non-execute, non-effect let (`inMemoryResult`), which is what makes the scope non-empty
- /Users/neemsandv/legend/legend-engine/.../core_relational/relational/tds/tests/testTdsExtension.pure:152-226 — the WithoutPreval body: `let r = execute($q,...)`, `let inMemoryResult = ...`, and the same `->map(r|$r.values->makeString('|'))` assert at :225

</details>

---

## `testInheritanceMultipleLevel`

| | |
|---|---|
| family | `testDataGeneration/tests` |
| sweep status | ERROR |
| **verdict** | **MISSING FEATURE** |
| effort | L |
| confidence | medium |

**Root cause**

The query's third projection column is `$f.vehicles->subType(@Bicycle).person.name`. `Person.vehicles` is routed to map1 (Car) / map2 (Bicycle) under a Vehicle inheritance operation, so it resolves through the union-thread synthesis, where a subtype cast reads thread-local `stc_<Sub>___<prop>` columns. UnionSynthesis.subTypeDispatchProps only distributes a member's subtype-only property as an stc column when the property is SCALAR (`t instanceof NameRef nr && model.findClass(nr.name()).isEmpty()`), or when it is class-typed but its ctor field is an EMBEDDED NewInstance (in which case each thread-projectable ctor leaf becomes a flat `<prop>__<leaf>` column via addStcEmbeddedLeaf). `Bicycle[map2].person : [myDB]@PersonBicycle` is class-typed and its ctor field is a pipeline SLOT read of a legacyNavigate hop, not a NewInstance, so `ctorOf(fv.value())` returns null and NOTHING is emitted for `person`. The union relation therefore carries no `stc_..._Bicycle___person` or `..._person__name` column. At resolve time Substitution's multi-hop walk tries, in order, the chained-assoc key, the nested-embedded ctor walk, the head-join+embedded-tail walk and finally the subtype-flat-column lookup (`flat = "stc_..._Bicycle___person__name"`, `hf.targetBindings().get(flat)` == null) and throws the reported wall. `stc_..._Car___engineType` exists (engineType is scalar), which is why column 2 is fine and only column 3 fails — consistent with the reported `assocs=[vehicles#f0, vehicles#f1]`.

**Fix**

Extend the union-thread synthesis in core/src/main/java/com/legend/normalizer/UnionSynthesis.java so a subtype-only CLASS-TYPED property backed by a join contributes flat stc leaves. In subTypeDispatchProps (line 795), add a third arm alongside the scalar and embedded-ctor arms: when `visibleOnTarget` and the ctor field value is a pipeline SLOT read (the legacyNavigate hop minted by JoinChainEmission for a class-typed PropertyMapping.Join), enumerate the scalar leaves of the join TARGET set's mapping and register `prop + "__" + leaf` for each. In addStcEmbeddedLeaf (line 936), add the corresponding emission: when the owning member's field is a slot read rather than a NewInstance, emit `$row.<slot>.<targetCol>` for the owning thread and `nullOfDeclaredType` for the others, keeping the existing String-cast and toOne shaping so the threads type-align. The member thread's pipeline already carries the join hop, so the leaf is thread-projectable without new joins. Cheaper alternative to evaluate first: make the resolver's Substitution subtype arm (core/src/main/java/com/legend/resolver/Substitution.java:1317) fall back to materializing the hop inside the owning member arm on demand rather than requiring a pre-flattened column — that keeps the normalizer's column list demand-free but is a larger resolver change. Note this test additionally asserts four generated testDataGen SQL texts (assertSqlEquals) and a CSV of generated data, so a passing navigation is necessary but not sufficient.

**How legend-engine does it** — /Users/neemsandv/legend/legend-engine/.../core_relational/relational/pureToSQLQuery/pureToSQLQuery.pure:1719-1731 — doJoinToClass: a property mapping's own join tree is applied against `getClassMappingById($relationalPropertyMapping.targetSetImplementationId)`, inside the owning set's thread. The engine has no notion of pre-flattened subtype columns; a subtype-only class-typed property is just another join processed in the member's own branch, which is why `subType(@Bicycle).person.name` needs no special surface there.

**Risk** — Enumerating ALL scalar leaves of the join target widens every union thread's projection and could change the column list (and therefore golden SQL) of other passing inheritance/union tests. Restrict the enumeration to the target set's own mapped scalar properties, and confirm the added stc columns are pruned when un-demanded (SubselectPrune). Tenet-2 trap: do not let the testDataGeneration harness form synthesize the missing hop.

**Also unblocks** — Any `subType(@Sub).<classTypedProp>.<leaf>` navigation over an inheritance/union mapping.

**Falsifier** — Run with LEGEND_LITE_STACKS set: the debug print at Substitution.java:1332 dumps `targetBindingKeys`. If that set already contains a key starting `stc_..._Bicycle___person`, the column IS being emitted and the defect is in the lookup/flattening spelling rather than in subTypeDispatchProps.

<details><summary>Evidence read (7 citations)</summary>

- core/src/main/java/com/legend/normalizer/UnionSynthesis.java:795-830 — `boolean scalar = t instanceof TypeExpression.NameRef nr && model.findClass(nr.name()).isEmpty(); ... if (scalar && visibleOnTarget) {add prop} else if (visibleOnTarget) { NewInstance ector = fv == null ? null : ctorOf(fv.value()); if (ector != null) { ...add prop + "__" + pe.key()... } }` — a class-typed subtype property backed by a JOIN (no ctor) falls through with nothing added
- core/src/main/java/com/legend/normalizer/UnionSynthesis.java:936-965 — addStcEmbeddedLeaf reads the leaf out of `ctorOf(fv.value())` and otherwise projects `nullOfDeclaredType`: it has no notion of a joined slot read
- core/src/main/java/com/legend/resolver/Substitution.java:1317-1329 — the SUBTYPE-EMBEDDED tail arm: `String flat = String.join("__", path.subList(1, path.size())); TypedSpec fb = hf.targetBindings().get(flat); if (fb != null) {...}` — the only route for `subType(@Sub).<prop>.<leaf>`
- core/src/main/java/com/legend/resolver/Substitution.java:1336-1345 — the thrown wall: `"multi-hop navigation " + String.join(".", path) + " through an embedded/slot head is not supported yet [assocs=" + target.assocs().keySet() + "; head subNavs=" + ... + "; head binding=" + ...]` — matches the reported text verbatim, including `head binding=ABSENT`
- core/src/main/java/com/legend/model/ClassMapping.java:52-63 — `subTypeColumn(classFqn, prop) = "stc_" + classFqn.replace("::","__") + "___" + prop`, matching the reported `stc_meta__relational__tests__model__inheritance__Bicycle___person`
- /Users/neemsandv/legend/legend-engine/.../core_relational/relational/tests/mapping/inheritance/testInheritanceRelational.pure:338-368 — inheritanceMain maps `vehicles[map1] : [myDB]@PersonCar`, `vehicles[map2] : [myDB]@PersonBicycle`, and `Bicycle[map2] { ... person : [myDB]@PersonBicycle }` (a class-typed join, not an embedded block)
- /Users/neemsandv/legend/legend-engine/.../core_relational/relational/testDataGeneration/tests/testDataGeneration.pure:216-221 — the query: `project([f|$f.name, f|$f.vehicles->subType(@Car).engineType, f|$f.vehicles->subType(@Bicycle).person.name], ...)`

</details>

---

## `testUnionToUnion`

| | |
|---|---|
| family | `testDataGeneration/tests` |
| sweep status | ERROR |
| **verdict** | **MISSING FEATURE** |
| effort | L |
| confidence | medium |

**Root cause**

unionMappingWithEmbeddedProperty2 puts the routed union joins INSIDE an embedded block: `Firm[firm_set1] : Relational { legalName : ..., bridge ( employees[set1]:[myDB]@PersonSet1FirmSet1, employees[set2]:[myDB]@PersonSet2FirmSet1 ) }` (and symmetrically for firm_set2). legend-lite HAS the machinery for same-named routed joins into union members — UnionSynthesis.classifyUnionRoutes collects them into p.unionRoutes so ONE navigate dispatches per member — but that collector iterates only `rcm.propertyMappings()`, i.e. the TOP-LEVEL property mappings of the Relational class mapping. It never descends into PropertyMapping.Embedded. Consequently the two `employees[...]` sub-PMs reach JoinChainEmission.emitHopsForStructuralPm's Embedded arm as two independent class-typed Joins; the first mints pipeline slot "employees", the second finds `p.aliasToTargetTable.containsKey("employees")` already true with a mapped class-typed target (Bridge.employees : Person[*] via association BridgeAsso1) and throws the 'Embedded sub-PM ... collides with an existing pipeline slot' NotImplementedException. That is recorded as the class poison for Firm, so ClassSources.classSource later reports "class ...Firm is not mapped in mapping ...unionMappingWithEmbeddedProperty2 (...)". The routed-union dispatch at embedded level is genuinely not implemented; the wall is honest.

**Fix**

In core/src/main/java/com/legend/normalizer/UnionSynthesis.java, generalize classifyUnionRoutes (line 197) to walk the full PM tree: for each PropertyMapping.Embedded / InlineEmbedded / OtherwiseEmbedded, recurse into its sub-PMs with the EMBEDDED class as the owner (resolve it via MappingNormalizer.findPropertyTypeDeep on the parent, exactly as JoinChainEmission.java:122-128 already does), and key the collected routes and the dropped-route ledger by an embedded-qualified path (e.g. "bridge.employees") instead of the bare property name. Then teach JoinChainEmission.emitHopsForStructuralPm's Embedded arm (line 121) to consult that ledger before minting a slot — a sub-PM whose property is route-classified must not emit its own hop, mirroring the top-level `case PropertyMapping.Join j when p.droppedRoutedProps.contains(...)` arm at line 76. Finally, MappingNormalizer.materializeEmbedded must build the embedded ctor's `employees` field from the union-route navigate (the per-member OR entry set) rather than from a single slot. Keep the collision throw as the fallback for any embedded sub-PM route that classifyUnionRoutes could not classify — the wall must stay loud where the routes are not union members.

**How legend-engine does it** — /Users/neemsandv/legend/legend-engine/.../core_relational/relational/pureToSQLQuery/pureToSQLQuery.pure:1700-1712 — processProperty dispatches on the NORMALIZED list of property mappings for one property (`r:RelationalPropertyMapping[*] | processRelationalPropertyMapping($r, ...)`), each carrying its own `targetSetImplementationId` (:1726, `getClassMappingById($relationalPropertyMapping.targetSetImplementationId)`). The embedded set is itself an InstanceSetImplementation whose propertyMappings are looked up the same way, so the engine's multi-route dispatch is per-property-mapping-list and completely level-agnostic — which is why an embedded block may hold two same-named routed joins.

**Risk** — Changing the union-route key from a bare property name to a path affects every consumer of p.unionRoutes and p.droppedRoutedProps (JoinChainEmission line 76, MappingNormalizer line 2251); a partial rename would silently stop dropping poisoned top-level routes and produce wrong rows. Do the key change atomically across all three call sites. Note also that this test asserts an exact lineage tree string with per-member set-qualified names — passing normalization is necessary but the ScanRelations output must then match, so treat green as a separate verification step. Tenet-2 trap: do not special-case the mapping in the lineage harness form.

**Also unblocks** — Other unionMappingWithEmbeddedProperty2 consumers (testUnion.pure:209-212 and testUnionWithExtends.pure:186-189 filter-over-bridge.employees tests) and, more generally, any mapping with routed same-named class-typed joins inside an embedded block.

**Falsifier** — Comment out the collision throw at JoinChainEmission.java:130-138 in a scratch build and re-run: if Firm then normalizes but the second `employees` join silently overwrites the first slot (lineage tree missing the PersonSet2 arms), the slot-keying is confirmed as the mechanism. If instead a different poison appears for Firm, the root cause is elsewhere in the union synthesis.

<details><summary>Evidence read (9 citations)</summary>

- core/src/main/java/com/legend/normalizer/UnionSynthesis.java:197-205 — `static void classifyUnionRoutes(...) { Map<String, List<PropertyMapping.Join>> routedByProp = new LinkedHashMap<>(); for (PropertyMapping pm : rcm.propertyMappings()) { if (pm instanceof PropertyMapping.Join j && j.targetSetId() != null) {...} } ... }` — top-level PMs only, no Embedded descent
- core/src/main/java/com/legend/normalizer/JoinChainEmission.java:121-146 — the Embedded arm: `if (sub instanceof PropertyMapping.Join j && p.aliasToTargetTable.containsKey(j.propertyName()) && classTypedTargetIfMapped(nr.name(), j.propertyName(), model) != null) throw new NotImplementedException("Embedded sub-PM '" + j.propertyName() + "' collides with an existing pipeline slot of the same name; distinct same-named class-typed joins across embedded levels are a roadmap feature. Mapping=" + md.qualifiedName());` — the exact reported text
- core/src/main/java/com/legend/normalizer/JoinChainEmission.java:76-79 — the TOP-level Join arm honours `p.droppedRoutedProps`, the ledger classifyUnionRoutes writes; the Embedded arm has no equivalent consultation of p.unionRoutes/droppedRoutedProps
- core/src/main/java/com/legend/normalizer/UnionSynthesis.java:286-296 — `p.droppedRoutedProps.add(prop); model.mappingPoisons.merge(md.qualifiedName() + "::" + rcm.className(), ...)` — how a route classification poisons a class (the same ledger the collision message rides)
- core/src/main/java/com/legend/resolver/ClassSources.java:621-625 — the not-mapped message with the poison appended
- /Users/neemsandv/legend/legend-engine/.../core_relational/relational/tests/mapping/union/testUnion.pure:1373-1393 — the mapping's Firm[firm_set1]/Firm[firm_set2] bridge blocks with the two routed `employees[set1]`/`employees[set2]` joins each
- /Users/neemsandv/legend/legend-engine/.../core_relational/relational/tests/testModel/simpleTestModel.pure:676-690 — `Association BridgeAsso1 { bridge : Bridge[0..1]; employees : Person[*]; }` and `Class Bridge {}` — so employees IS a mapped class-typed property of the embedded class, satisfying the collision predicate
- /Users/neemsandv/legend/legend-engine/.../core_relational/relational/lineage/scanRelations/scanRelationsTests.pure:303-320 — the test: `Firm.all()->project([p|$p.legalName, p|$p.bridge.employees.name], ['Firm','Employee'])` against unionMappingWithEmbeddedProperty2, asserting a relation tree with per-member set-qualified names
- core/src/main/java/com/legend/lineage/ScanRelations.java:43-44,845 — legend-lite already prints the `------> (t) Table(Join) [cols]` form the golden expects, so the lineage renderer is not the blocker

</details>

---

## `isolationTest`

| | |
|---|---|
| family | `tests/advanced` |
| sweep status | ERROR |
| **verdict** | **MISSING FEATURE** |
| effort | XL |
| confidence | high |

**Root cause**

The projection column is `col(x|$x.employees.group.children->filter(c|$c.coveredProduct.name == $x.employees.product.name).name->toOne(),'testCol')`. The filter reads the OUTER variable $x, so SyntheticHeads.parkFiltered puts it in the CORRELATED pool (corrPreds) and mints the head `children#f0` (SyntheticHeads.java:207-219), turning the expression into the plain 4-segment path employees.group.children#f0.name. `employees` is a nav-slot head on Firm (`employees: @Firm_Person`), so its AssocSub — including its SubNav tree — is registered from NavMaterializer's NavMat (StoreResolver.java:1502-1531, `subNavs = navMats.get(alias).subNavs()`). NavMaterializer builds that tree RECURSIVELY (NavMaterializer.java:316-321, composeSubNavPrefixes over the sub-NavMat's own subNavs), so depth is not the limit. The limit is NavMaterializer.demandSlotSubTail:456-461: when the tail's first segment has a correlated parked pred it returns EARLY and leaves the step undemanded, deliberately ('a CORRELATED pred on a filtered sub-hop cannot park in-target — leave the step undemanded (loud read), never an unfiltered join'). At the Organization level the tail is [children#f0, name] and children#f0 IS correlated, so the Organization NavMat has empty subNavs, hence SubNav('group').children() is empty, hence Substitution.rewriteMultiHop's descent (:1182-1186) cannot advance past hop 2 and falls to the wall at :1342-1348 — which prints exactly the observed 'assocs=[employees]; head subNavs=[product, group]; head binding=TypedPropertyAccess'. The missing surface is the parent-copy / correlated-subselect route at SUB-NAV depth: it exists only for depth-0 heads (StoreResolver's corrNavHeads route, :2517-2545, carrying synthetics.correlatedPred(headKey) into the AssocJoin) and is explicitly walled for chained hops (:2377-2387).

**Fix**

Route a correlated sub-hop the way depth-0 correlated heads are already routed, instead of dropping it. In core/src/main/java/com/legend/resolver/NavMaterializer.java, demandSlotSubTail (:454-461): replace the blanket `return` with a corrSub branch — record the sub-alias in a corrSubHeads map (alias -> head) alongside tNavs, and in the materialisation (:308-330, where subMats/subTree are assembled) build that step through CorrelatedSubselects (the parent-copy subselect: re-join the parent extent with the navs the pred demands, apply the pred over the joined row, join back on parent-key equality — exactly what StoreResolver.java:2517-2545 does for a depth-0 head via AssocJoin's corrSubPred), then publish the resulting SubNav (with the composed prefix) into subTree so rewriteMultiHop's descent at Substitution.java:1182-1186 finds 'children#f0'. The outer correlation ($x.employees.product.name) must resolve against the PARENT copy inside the subselect, which is the engine's self-join-on-PK form. If the parent-copy machinery cannot be reused at sub depth in one step, keep the wall but move it: throw at demandSlotSubTail naming 'correlated filtered navigation at sub-nav depth', so the failure message points at the real site instead of surfacing as a downstream multi-hop wall.

**How legend-engine does it** — legend-engine …/core_relational/relational/pureToSQLQuery/pureToSQLQuery.pure:7511-7532 (addSelfJoinOnNode) and :7534-7620 (isolateSubJoins, choosing IsolationStrategy.BuildCorrelatedSubQuery at :7590-7600, implemented at :1181) — the engine isolates the whole employees thread into a subselect that copies the parent extent (persontable_2) with the org/product joins and rejoins it on the parent key, which is the golden at tests/advanced/testForcedSelfJoin.pure:140.

**Risk** — Removing the early return without building the correlated route would emit an UNFILTERED join for the sub-hop — silently wrong rows, precisely what the comment at NavMaterializer.java:456-458 protects against. Any partial implementation must keep that invariant. The test asserts both the CSV rows and the exact SQL (a self-joined subselect), so even a row-correct implementation may not match the golden text.

**Also unblocks** — Possibly testMultipleIsolationWithDifferentProp and testInheritanceMultipleLevel (both fail with the same multi-hop wall text per docs/RELATIONAL_CORPUS.md) — NOT verified; their heads may bottom out on different builders.

**Falsifier** — Run this test with LEGEND_LITE_STACKS=1 (the env-gated dump at Substitution.java:1341-1346). The diagnosis predicts the wall is reached with path=[employees, group, children#f0, name]. Then add a temporary print at NavMaterializer.java:459: it must fire for tail.get(0)=='children#f0' during the Organization-level recursion. If that print does not fire, the empty children come from a different builder (e.g. the depth-1 3-arg SubNav constructor at AssociationJoins.java:1132-1141) and the fix site moves there.

<details><summary>Evidence read (11 citations)</summary>

- core/src/main/java/com/legend/resolver/Substitution.java:1342-1348 — the exact throw, including the [assocs=…; head subNavs=…; head binding=…] diagnostic that matches the observed text
- core/src/main/java/com/legend/resolver/Substitution.java:1177-1190 — the SubNav descent: `while (sub != null && hop+1 < path.size() && sub.children().containsKey(path.get(hop)))`; with empty children it cannot reach the leaf
- core/src/main/java/com/legend/resolver/NavMaterializer.java:454-461 — `if (tail.size() >= 2 || …) { if (synthetics.correlatedPred(tail.get(0)) != null) { return; } … }` — the early return that leaves the correlated sub-hop undemanded
- core/src/main/java/com/legend/resolver/NavMaterializer.java:316-321 — subTree.put(prop, new SubNav(p, rowVar, bindings, composeSubNavPrefixes(p, sm.getValue().subNavs()))) — children ARE built recursively, so the empty children come from the undemanded sub-hop, not from a structural depth-1 limit
- core/src/main/java/com/legend/resolver/NavMaterializer.java:354-370 — subPipeFor recurses into navTargetMaterialized for each demanded sub-alias and stores the sub NavMat in subMats
- core/src/main/java/com/legend/resolver/StoreResolver.java:1502-1531 — the nav-slot head route publishes AssocSub with subNavs = navMats.get(alias).subNavs()
- core/src/main/java/com/legend/resolver/StoreResolver.java:2325-2334 — slot-headed multi-hop paths are skipped by the association join walk ('embedded/slot heads: ctor-drillable paths stay substitution-side'), so the SubNav tree is the ONLY route for this path
- core/src/main/java/com/legend/resolver/StoreResolver.java:2377-2387 — the sibling explicit wall for a correlated filtered nav as a chained hop
- core/src/main/java/com/legend/resolver/SyntheticHeads.java:207-219 — parkFiltered routes a non-closed (outer-reading) predicate into corrPreds and mints the #fN identity
- legend-engine …/core_relational/relational/tests/advanced/testForcedSelfJoin.pure:118-140 — the query, the CSV golden and the SQL golden (an isolated subselect over personTable re-joined on persontable_0.ID = persontable_1.ID)
- legend-engine …/core_relational/relational/tests/advanced/testForcedSelfJoin.pure:139-190 — IsolationTestMapping: Person(product:@Person_product, group:@Org_Person), Organization(children:@Org_org, coveredProduct:@Org_product) — confirms group and children are nav-slot hops, and children is the Org_org self-join

</details>

---

## `relationalResultSourcingOfDateList`

| | |
|---|---|
| family | `tests/advanced` |
| sweep status | ERROR |
| **verdict** | **REAL DEFECT** |
| effort | S |
| confidence | high |
| **adversarial review** | **CONFIRMED** |

**Root cause**

The query-level let is beta-reduced by UserCallInliner.inlineBody (:100-131), so the second statement's predicate becomes `$y.latestEventDate->in( take(distinct(Trade.all()->filter(x|…).latestEventDate), 2) )`. InnerDemand.collectInQuery (:658-680) sees the `in` call and calls the resolver lambda on the collection argument; only if that returns a RelationType-typed node is an InQueryRead registered and Substitution's membership arm (:934-980) turns it into EXISTS over the resolved relation. That lambda (InnerDemand.java:506-551) peels a trailing `distinct` and a trailing `limit`/`take` — but it matches both as TypedNativeCall. `distinct` over a collection genuinely IS a native call, because DistinctChecker explicitly delegates the non-relation overload to Typer.emitCall (DistinctChecker.java:33-37). `take`/`limit` never are: CoreFn.LIMIT/TAKE own both overloads (CoreFn.java:63-65) and SlicingChecker.limit emits a TypedLimit unconditionally, with no relation-vs-collection test (SlicingChecker.java:21-32, dispatched at Typer.java:1193). So the take arm at InnerDemand:526-539 is dead code, the chain falls through to rawResolver.apply(TypedLimit) (:540), which resolves structurally (StoreResolver anchoredNode's `case TypedLimit l when anchored(l.source()) -> structural` at :2431) and returns a node whose info() is still Date[*], not a RelationType — so collectInQuery's `rt` check (:673-676) rejects it, nothing is registered, and the rewrite walks INTO the collection argument, where object-space TypedLimit has no arm and hits Substitution's default throw (:1893-1900). Compounding it, the distinct arm at :515 calls rawResolver (not itself), so the peels do not compose in either order. The sibling relationalResultSourcingOfList (identical idiom WITHOUT ->take(2)) passes — it matches the live distinct arm.

**Fix**

core/src/main/java/com/legend/resolver/InnerDemand.java, inQueryReads' resolver lambda (:506-551): make the peel SELF-RECURSIVE and add a TypedLimit arm. Concretely, replace the lambda with a named recursive Function (or a private static method taking rawResolver) whose body is: (i) if chain is a 1-arg TypedNativeCall named 'distinct' -> rel0 = recurse(arg); return rel0 == null || !(rel0.info().type() instanceof Type.RelationType) ? null : new TypedDistinct(rel0, List.of(), rel0.info()); (ii) NEW: if chain instanceof TypedLimit tl -> rel0 = recurse(tl.source()); return rel0 == null || !(rel0.info().type() instanceof Type.RelationType) ? null : new TypedLimit(rel0, tl.count(), rel0.info()); (iii) otherwise rawResolver.apply(chain). Keep the existing try/catch of NotImplementedException|LegendCompileException around the whole thing. Delete the dead TypedNativeCall limit/take arm at :526-539 (or leave it with a comment noting CoreFn.TAKE/LIMIT always emit TypedLimit) so the next reader is not misled. Recursing (rather than calling rawResolver) is what makes both ->distinct()->take(n) and ->take(n)->distinct() work. Do NOT 'fix' this by making SlicingChecker emit a native call for the collection overload: Anchors.objectSpine (:99) and the Lowerer both switch on TypedLimit.

**How legend-engine does it** — legend-engine …/core_relational/relational/tests/advanced/testRelationalResultSourcing.pure:62-64 — the engine's own plan golden for the sibling shows the semantics: the list sub-query is executed as its own Relational node, allocated to a variable, and fed back as `in (${inFilterClause_nameList})` (temp table above 50 elements, rendered collection otherwise). Our one-SQL-plan doctrine renders the same set as a correlated-free subselect; row-equivalent.

<details><summary>Adversarial review notes (CONFIRMED)</summary>

Mechanism holds end-to-end and I traced every link. (1) Observed sweep detail (units.json U42) is literally `object-space expression node TypedLimit is not substitutable yet (H2 vocabulary): TypedLimit[source=TypedNativeCall[...collection::distinct, parameters=[TypedParameter[name=s, type=TypeVar[name=T]...` — i.e. exactly Substitution's default arm over a TypedLimit wrapping the COLLECTION distinct overload, as claimed. (2) The take arm at InnerDemand:526-539 really is dead: CoreFn.of() intercepts both 'take' and 'limit' (bare and FQN) before any generic/library dispatch (Typer.java:426 is the only CoreFn.of site in the synth path), Typer.java:1193 routes LIMIT,TAKE to SlicingChecker.limit, and SlicingChecker.limit (:21-32) emits TypedLimit unconditionally except for the empty-optional-bound identity. Both take overloads exist in the catalog (Pure.java:2026-2027) but never reach emitCall. The only other TypedLimit construction from a native call is StoreResolver:2616, and that is first()/head(), not take. So deleting that arm is safe. (3) DistinctChecker:34-37 does delegate the non-relation overload to Typer.emitCall, so distinct genuinely stays a TypedNativeCall — the asymmetry is real. (4) I confirmed the exact 'structural returns Date[*]' claim rather than accepting it: Anchors.objectSpine (:102-115) makes the distinct call ANCHORED (not OBJECT) because its arg is a non-class-typed TypedPropertyAccess, so resolveNode reaches anchoredNode's `case TypedLimit l when anchored(l.source()) -> structural` and structural() is `n.mapChildren(resolveNode)`, which preserves info() — the generic `case TypedNativeCall nc -> structural(...)` arm (:476) means the distinct child resolves without throwing, so the returned node really does carry Date[*] and collectInQuery's rt check rejects it. (5) The fix would work: with a self-recursive peel, rawResolver on the inner `Trade.all()->filter(...).latestEventDate` hits `case TypedPropertyAccess pa when objectSpace(pa.source()) && !ClassType -> scalarReadAsProject` (:394-396), yielding a single-column relation; the membership arm (Substitution:933-985) then builds TypedFilter(q.relation(), eq-pred) wrapped in isNotEmpty → EXISTS, and Fold.filterSlot (:226-236) returns ISOLATE when limit or distinct is set, so filter-over-limit-over-distinct isolates into a subselect — semantically correct (the correlation is applied AFTER the truncation, matching in(take(distinct(...),2))). (6) Data claim verified: tradeEventTable has exactly 5 rows for trades 1 and 6, max eventDate 2014-12-03 / 2014-12-04, so exactly two distinct non-empty latestEventDate values and take(2) is a no-op. Residual unknown I could not settle without running: whether EXISTS over an isolated LIMIT-over-DISTINCT subselect lowers cleanly on this dialect path — that is what could push S to M.

</details>

**Citation issues found in review** — StoreResolver.java:2431 does not contain `case TypedLimit l when anchored(l.source()) -> structural` — that arm is at :432 (a digit-transposition). The test-body citation is testRelationalResultSourcing.pure:43-54, not :44-56, and the data citation is relationalSetUp.pure:1491-1497, not :1490-1497. All are cosmetic; every substantive citation resolved and said what was claimed.

**Risk** — The new arm makes any `in(<class chain>->take(n))` lower as EXISTS over a LIMITed subselect. With no total order a LIMIT is non-deterministic — but the engine has the same exposure (it executes the sub-query and takes the first n), and for this fixture only two distinct dates exist, so the rows are pinned. Residual risk unrelated to the fix: latestEventDate is mapped through the grouped view tradeEventViewMaxTradeEventDate (relationalSetUp.pure:141-143, 820), so the sub-query must join that view; if that join is itself unsupported the test will move to a different wall. Tenet-2 trap: do not evaluate the let host-side and splice literal dates into the IN list from the harness/executor — that is exactly the harness compensation the tenets forbid; the fix belongs in InnerDemand.

**Also unblocks** — Any other `in`/`contains`/`tdsContains` collection argument ending in ->take(n)/->limit(n) over a class chain. None other confirmed failing in this corpus.

**Falsifier** — The cheapest observation: the sibling relationalResultSourcingOfList (same idiom, `->distinct()` with NO `->take(2)`) currently PASSES while this one errors — that alone isolates the take peel. To confirm positively, add a temporary print in InnerDemand.collectInQuery where out.put(c, …) happens: for this test it must NOT fire today, and must fire after the TypedLimit arm is added. If it already fires today, the InQueryRead route is not the failing path and this diagnosis is wrong.

<details><summary>Evidence read (14 citations)</summary>

- core/src/main/java/com/legend/resolver/Substitution.java:1865-1866 — `case TypedLimit rl when rl.source().info().type() instanceof Type.RelationType -> n;` — the pass-through requires a RELATION source; our source is the Date[*] distinct call
- core/src/main/java/com/legend/resolver/Substitution.java:1893-1900 — the default arm producing the exact observed message
- core/src/main/java/com/legend/builtin/Pure.java:1932 — `distinct<T>(s:T[*]):T[*]` — the param name 's' and TypeVar T in the observed message identify the node as the COLLECTION distinct overload wrapped in a TypedLimit
- core/src/main/java/com/legend/resolver/InnerDemand.java:506-551 — the resolver lambda: distinct arm (:510-521), the take/limit arm keyed on TypedNativeCall (:526-539), fallback rawResolver (:540); both inner calls use rawResolver, so the peels never compose
- core/src/main/java/com/legend/resolver/InnerDemand.java:658-680 — collectInQuery registers an InQueryRead only when the resolved collection types as a single-column RelationType
- core/src/main/java/com/legend/compiler/spec/SlicingChecker.java:21-32 — limit()/take() build a TypedLimit with no relation-vs-collection branch
- core/src/main/java/com/legend/compiler/spec/Typer.java:1193 — `case LIMIT, TAKE -> SlicingChecker.limit(this, af, env);`
- core/src/main/java/com/legend/compiler/spec/CoreFn.java:63-65 and :155-175 — LIMIT("limit")/TAKE("take") own every spelling, including the FQN form, so a parsed take is ALWAYS a construct node
- core/src/main/java/com/legend/compiler/spec/DistinctChecker.java:33-37 — the asymmetry: a non-relation distinct source falls back to Typer.emitCall, i.e. stays a TypedNativeCall
- core/src/main/java/com/legend/compiler/spec/UserCallInliner.java:100-131 — query-level lets beta-reduce into the query body, which is why the chain appears inside the filter predicate
- core/src/main/java/com/legend/resolver/Substitution.java:934-980 — the InQueryRead membership arm that would have consumed the whole `in` call had one been registered
- core/src/main/java/com/legend/lowering/Fold.java:227-238 — filterSlot returns ISOLATE when limit/offset/distinct is present, so filter-over-limit lowers to a wrapped subselect correctly
- legend-engine …/core_relational/relational/tests/advanced/testRelationalResultSourcing.pure:44-56 — the test body; its only assertion is assertSameElements on the CSV rows (id 1, 6)
- legend-engine …/core_relational/relational/tests/relationalSetUp.pure:1490-1497 — tradeEventTable has 5 rows for trades 1 and 6 only, so exactly TWO distinct non-empty latestEventDate values exist (2014-12-03, 2014-12-04): take(2) is a no-op and the answer is order-independent

</details>

---

## `testQualifierWithIsolation`

| | |
|---|---|
| family | `tests/advanced` |
| sweep status | ERROR |
| **verdict** | **MISSING FEATURE** |
| effort | L |
| confidence | high |

**Root cause**

The ERROR text is identical to testQualifierWithIsolationXX and comes from the same defect (a class-typed `.firm` leaf projected as a column by filteredNavLeafRead — see sharedRootCause), because `$f.employeeByLastName('Smith2').firm` is rewritten first. But fixing that only moves the wall: this test's real blocker is the SECOND qualifier AFTER the class hop — `$f.employeeByLastName('Smith2').firm->toOne().employeeByLastName('Smith3').age->toOne()` inlines to `toOne(filter(<hop>.employees, e|$e.lastName=='Smith3')).age` where `<hop>` is itself a filtered navigation. filteredNavLeafRead requires the filter's source to be a property access DIRECTLY on the user variable (Substitution.java:2639-2648: `f.source() instanceof TypedPropertyAccess head && head.source() instanceof TypedVariable hv && hv.name().equals(target.userVar())`); here the head sits on the HOP TARGET, so it returns null, and no other arm registers a filtered-navigation segment keyed by a composed chain. The registries are all keyed by a single head property: existsSubs by head (StoreResolver.java:2243), assocs by headKey (:1525). There is no per-segment filtered-subselect descriptor, so a qualifier -> class hop -> qualifier chain has no route at all.

**Fix**

Do NOT try to close this with a filteredNavLeafRead patch. Two steps.
(a) Immediately: add the class-typed-leaf guard described for testQualifierWithIsolationXX (Substitution.java:2775) so this test fails with an honest wall ("class-typed property … is graph output" / "filtered navigation head is not on the instance variable") instead of a misleading lowerer column error. That is the correct interim state.
(b) The real rung: give filtered navigation a per-SEGMENT descriptor. In StoreResolver's head registration (the nav-slot route at :1502-1531 and registerExistsSubs at :2113-2245), key ExistsSub/AssocSub by the COMPOSED chain (e.g. `employees#f0`, `employees#f0.firm`, `employees#f0.firm.employees#f1`) instead of by a bare head property, and have Substitution's filtered-nav recogniser accept a head whose receiver is a previously-registered segment (resolve the receiver to its chain key, look up the descriptor, continue). Each segment emits its own filtered-subselect join and the continuation re-keys on the joined alias — the shape the engine golden shows (four chained LEFT JOINs). This is the same machinery testQualifierWithIsolationForced/Forced2 need, and it is what StoreResolver.java:2377-2387 currently walls.

**How legend-engine does it** — legend-engine …/core_relational/relational/pureToSQLQuery/pureToSQLQuery.pure:7534-7620 (isolateSubJoins) with :7581-7600 selecting IsolationStrategy.BuildCorrelatedSubQuery and calling buildCorrelatedSubQuery (defined at :1181) — the engine isolates each qualifier's saved filtering operation into its own subselect node, per segment, which is exactly the per-segment descriptor legend-lite lacks.

**Risk** — Re-keying the exists/assoc registries by composed chain touches every consumer of target.existsSubs()/target.assocs() (rewriteExists, rewriteMultiHop, filteredNavLeafRead, CorrelatedSubselects) — a broad blast radius across the whole qualifier and exists corpus. Tenet-2 trap: the goldens here are pure SQL text with no row assert, so there is a standing temptation to 'pass' by loosening the SQL comparison — do not.

**Also unblocks** — testQualifierWithIsolationForced, testQualifierWithIsolationForced2 (same qualifier->class-hop->qualifier family; both listed at wall depth 5 in docs/WALL_DEPTH.txt:362-363 — I did not verify their current failure text).

**Falsifier** — Apply ONLY the class-typed-leaf guard (fix (a)) and re-run: the error must change from the Lowerer's [col='firm' ref='firm'] to a Substitution wall naming the class-typed property or the un-recognised filtered head. If instead the test resolves and produces SQL, then a route for the second qualifier does exist and this MISSING_FEATURE verdict is wrong.

<details><summary>Evidence read (8 citations)</summary>

- core/src/main/java/com/legend/resolver/Substitution.java:2639-2654 — filteredNavLeafRead's head recogniser accepts only a property access (or milestoned access) whose source IS target.userVar(); otherwise it returns null
- core/src/main/java/com/legend/resolver/Substitution.java:2775-2792 — the un-guarded class-typed leaf projection that produces the currently observed [col='firm' ref='firm'] message
- core/src/main/java/com/legend/resolver/StoreResolver.java:2243 and :1525 — ExistsSub / AssocSub are registered under a single head key; no composed-chain key exists for a filtered segment
- core/src/main/java/com/legend/resolver/StoreResolver.java:2377-2387 — the sibling route already walls explicitly: "correlated filtered navigation as a chained association hop ('…' at '…') is not supported yet", i.e. chained filtered segments are a known-unbuilt rung
- core/src/main/java/com/legend/lowering/Lowerer.java:1173 — the site that emits the observed message
- legend-engine …/core_relational/relational/tests/testModel/simpleTestModel.pure:56 — employeeByLastName's body (the inline shape both qualifiers expand to)
- legend-engine …/core_relational/relational/tests/advanced/testQueryStructure.pure:224-238 — the test body (forcedIsolation = IsolationStrategy.BuildCorrelatedSubQuery) and its golden: FOUR chained LEFT JOINs to filtered subselects (persontable_0 Smith, persontable_2 Smith2, firmtable_1 the hop, persontable_4 Smith3)
- docs/LEG1_INNERJOIN_FAMILY.md:72-92 — the project's own prior note on this exact pair, consistent with what I read in the code

</details>

---

## `testQualifierWithIsolationXX`

| | |
|---|---|
| family | `tests/advanced` |
| sweep status | ERROR |
| **verdict** | **REAL DEFECT** |
| effort | M |
| confidence | high |

**Root cause**

Substitution.filteredNavLeafRead runs its class-hop peel and its toOne/first peel as two SEQUENTIAL loops in the wrong order. For `$f.employeeByLastName('Smith').firm->toOne().legalName` (which inlines to legalName(toOne(firm(toOne(filter($f.employees, e|$e.lastName=='Smith'))))))` the outer `.legalName` sees a TypedNativeCall(toOne) as its source, so the hop loop at :2596 collects nothing, the wrapper loop peels the toOne, src is then `.firm` (a TypedPropertyAccess, not a TypedFilter) and the method returns null at :2629. rewrite() then falls to rebuildWithInstanceFold (:1918) which rewrites the child `.firm` on its own; for THAT node the wrapper peel reaches the filter, headProp='employees' resolves an ExistsSub, and the method treats the CLASS-typed `.firm` as the leaf. leafType = pa.info().type() is ClassType(Firm) (:2775) and it builds TypedProject(rel,[TypedFuncCol("firm", λrow -> $row.firm)]) (:2789-2792). There is no data-type guard here (rewriteHeadProp has one at :1367-1372, assocBindingRead has one at :2107-2110), and the two slot guards at :2755-2768 only test JoinSlot aliases (Pipelines.slotAliases, :215) — a class-typed `@Join` binding is a NAVIGATE step (Pipelines.navSteps, :146), a disjoint set, so it slips through. The lowerer then cannot resolve the name 'firm' against the base select and throws. The demand scan has the SAME ordering bug: InnerDemand.collectChains (:264-297) builds the property chain first and peels toOne/first/head only AFTER the chain loop ends, so `[firm, legalName]` is never collected into leafChains, so navStepDemand (:201-203) never demands the `firm` nav step and registerExistsSubs never builds a SubNav for it (StoreResolver.java:2176-2189).

**Fix**

Two edits that must land together (the one-funnel rule stated at Substitution.java:473-480 and InnerDemand.java:239).
(1) core/src/main/java/com/legend/resolver/Substitution.java, filteredNavLeafRead (:2588-2630): replace the two sequential peel loops with ONE interleaved loop — repeat { if src is a class-typed TypedPropertyAccess, hops.add(0, prop) and descend; else if src is a 1-arg toOne/first/head native call, descend, set unwrapped=false and (for first/head) firstRow=true; else break }. Keep the existing post-loop multiplicity check on `unwrapped`. With this, `…firm->toOne().legalName` yields hops=[firm], src=the TypedFilter, and the EXISTING SubNav dispatch at :2721-2740 resolves legalName as the composed-prefix column on the joined row.
(2) core/src/main/java/com/legend/resolver/InnerDemand.java, collectChains (:264-297): mirror the same interleaved peel when building `chain` (peel toOne/first/head between property hops), so leafChains yields [firm, legalName]; navStepDemand (:201-203) then demands the `firm` nav step and registerExistsSubs (StoreResolver.java:2176-2189) publishes SubNav['firm']. Without (2), (1) turns the crash into the loud wall at Substitution.java:2729-2733 ("nav step is not materialized yet").
(3) Belt-and-braces guard at Substitution.java:2775, before building the projection: if `pa.info().type() instanceof Type.ClassType` (equivalently, if InnerDemand.navSlotAlias(leafBinding, ex.targetRowVar(), Pipelines.navSteps(...).keySet()) != null), throw the same NotImplementedException wording used at :1367-1372 instead of projecting a class-typed column. A class-typed value can never be a SQL column; this must be a wall, never a projection.

**How legend-engine does it** — legend-engine …/core_relational/relational/pureToSQLQuery/pureToSQLQuery.pure:7534-7620 (isolateSubJoins) — the engine resolves a qualifier's saved filter by ISOLATION STRATEGY; for a projection thread with a suitable found node it picks IsolationStrategy.MoveFilterInOnClause (:7566-7570), producing the flat two-LEFT-JOIN golden at tests/advanced/testQueryStructure.pure:252, not a scalar subquery.

**Risk** — filteredNavLeafRead is consulted as a `when` guard (Substitution.java:1746) and is evaluated twice per node, so widening what it accepts re-routes shapes that other arms currently handle — the qualifier family (testFilterWithQualifiedProperties, testQualifierInLambdaDeep, the #70 orgByName('X').parent.name goldens) must be re-run. Widening collectChains adds nav-step demand (extra materialised joins) to any filtered-nav chain that has a toOne between hops, which can change emitted SQL for currently-passing tests. IMPORTANT: this test's ONLY assertion is the SQL text, and the engine golden is the FLAT form (`left outer join personTable on (root.ID = FIRMID and LASTNAME='Smith') left outer join firmTable on (…)` — testQueryStructure.pure:252, the engine's MoveFilterInOnClause isolation strategy), whereas filteredNavLeafRead emits a correlated scalar subquery by design (:2504-2513). So the fix takes the test from ERROR to a sql-text FAIL unless the value-position filtered nav is additionally routed through the synthetic-head/join-ON emission. Tenet-2 trap: do not normalise or relax the SQL comparison in the harness to close the gap.

**Also unblocks** — Likely the same class-typed-leaf mis-projection in testQualifierWithIsolation (this unit); possibly testQualifierWithIsolationForced/Forced2 (same qualifier->class-hop shape, not in this unit and not verified).

**Falsifier** — Run this one test with LL_FNLR_DEBUG=1 (the env-gated print at Substitution.java:2589/:2662/:2740). The diagnosis predicts a line `[fnlr] leaf 'firm' hops=[] type=ClassType[…Firm]` (leaf 'firm', empty hops, class type). If filteredNavLeafRead never fires for 'firm', or the printed hops are non-empty, this diagnosis is wrong.

<details><summary>Evidence read (17 citations)</summary>

- core/src/main/java/com/legend/lowering/Lowerer.java:1173 — throw "extend/project columns … reference names unresolvable even after isolation" with the [col=…, ref=…] detail; the exact observed message
- core/src/main/java/com/legend/lowering/Lowerer.java:1184-1195 — tryComputedColumns resolves each column lambda against the base select; Resolution.Unfoldable records u.column() as the missing ref, which is how ref='firm' is produced
- core/src/main/java/com/legend/resolver/Substitution.java:2596-2601 — `while (src instanceof TypedPropertyAccess hp && hp.info().type() instanceof Type.ClassType) { hops.add(0, hp.property()); … }` runs FIRST
- core/src/main/java/com/legend/resolver/Substitution.java:2605-2620 — the toOne/first/head peel loop runs AFTER it; the two never interleave
- core/src/main/java/com/legend/resolver/Substitution.java:2629 — `if (!(src instanceof TypedFilter f)) { … return null; }` — the outer `.legalName` bails here
- core/src/main/java/com/legend/resolver/Substitution.java:2775-2792 — `Type leafType = pa.info().type();` then `new TypedProject(rel, List.of(new TypedFuncCol(pa.property(), leafFn)), …)` with no check that leafType is a data type
- core/src/main/java/com/legend/resolver/Substitution.java:1367-1372 — the 1-hop head read DOES guard: "class-typed property '$…' used as a whole value is graph output (Phase H4)"
- core/src/main/java/com/legend/resolver/Substitution.java:2755-2768 — the only guards on the leaf binding test ex.targetSlotAliases()/targetSlotPrefixes(), i.e. JoinSlot aliases
- core/src/main/java/com/legend/resolver/Pipelines.java:146-151 and :215-219 — navSteps() collects TypedNavigate aliases, slotAliases() collects TypedJoinSlot aliases: two disjoint registries
- core/src/main/java/com/legend/resolver/InnerDemand.java:209-224 — navSlotAlias: a class-typed join binding is exactly `$row.<navAlias>` where navAlias is the property name, i.e. `$row.firm`
- core/src/main/java/com/legend/resolver/InnerDemand.java:264-297 — collectChains: the chain loop stops at the first non-PropertyAccess source, then peels toOne/first/head, then requires `sawFilter && chain.size() >= 2`; a hop UNDER a toOne is invisible to it
- core/src/main/java/com/legend/resolver/InnerDemand.java:201-203 — navStepDemand demands the nav step named by each leafChain's first segment and records it in predNavAliases
- core/src/main/java/com/legend/resolver/StoreResolver.java:2176-2189 — registerExistsSubs builds tSubNavs from predNavAliases; that is the map filteredNavLeafRead's hop dispatch reads (Substitution.java:2721-2733)
- core/src/main/java/com/legend/resolver/Substitution.java:1918-1928 — rebuildWithInstanceFold rewrites the children of `.legalName`, which is how the `.firm` node is reached on its own
- legend-engine …/core_relational/relational/tests/testModel/simpleTestModel.pure:56 — employeeByLastName(lastName){$this.employees->filter(e|$e.lastName==$lastName)->toOne()}:Person[0..1]
- legend-engine …/core_relational/relational/tests/relationalSetUp.pure:613 — Person mapping binds `firm : [dbInc]@Firm_Person`, a class-typed join (nav step), not a column
- legend-engine …/core_relational/relational/tests/advanced/testQueryStructure.pure:240-252 — the test body and its single SQL-text assert

</details>

---

## `testProjectThroughAssociation`

| | |
|---|---|
| family | `tests/injection` |
| sweep status | ERROR |
| **verdict** | **REAL DEFECT** |
| effort | L |
| confidence | medium |

**Root cause**

`productAtTimeOfTrade` inlines to `toOne(filter($t.products, p|$p.date == $t.d)).name` inside the `->map(t|...)` mapper. At canonicalization time the filtered head `$t.products` is DEPTH-1 on the mapper's own param, so SyntheticHeads' deep-head lift arm is deliberately suppressed (`!( scalar && directlyOnVar(f.source()) )`, SyntheticHeads.java:353-366) — the shape is routed to the correlated-scalar arm; on top of that the mapper is walked with lifting disabled entirely (SyntheticHeads.java:504-508). Then Substitution's TypedMap arm β-inlines the mapper param with the map SOURCE (Substitution.java:1815-1831), so by the time `filteredNavLeafRead` sees the node the head has DEEPENED to `$b.trades.products` (two hops). `filteredNavLeafRead` only matches a head whose source is the instance VARIABLE (Substitution.java:2641-2656) and ExistsSubs are only registered for size-1 paths (StoreResolver.java:2121-2124), so it returns null, the raw object-space TypedFilter falls through every arm (the only TypedFilter arm requires a RelationType source, Substitution.java:1848-1850) and hits the default wall at Substitution.java:1893-1899. The deepening created by the map inline is exactly the gap the lift arm's own comment admits: 'a DEEP head has no scalar arm'.

**Fix**

Give the deep (multi-hop) filtered head a scalar arm, mirroring the depth-1 one. (a) Substitution.filteredNavLeafRead (Substitution.java:2641-2657): replace the 'head is a property/dated access directly on the instance var' match with `List<String> hp = pathOf(f.source(), target.userVar()); if (hp == null) return null; String headKey = String.join(".", hp);` and look up `target.existsSubs().get(headKey)` — a 1-element path reproduces today's key exactly, so the depth-1 behaviour is unchanged. (b) StoreResolver: register ExistsSubs under DOTTED keys for value-position filtered navigations. The machinery already exists for the emptiness family at StoreResolver.java:1640-1738 (`chain = assocs.get(prefix)`, nav-slot leaf binding -> target pipeline + condition prefixed by `chain.prefix()`, `existsSubs.put(dotted, ...)`); factor that block into a helper `chainedExistsSub(cs, path, ops, context, assocs)` and call it from a new loop over consumed paths of size >= 2 that are the SOURCE of a filtered-nav value read (use the same recognizer as InnerDemand.collect's PropertyAccess arm, InnerDemand.java:434-458, so the demand scan and the rewrite cannot drift). Keep the emptiness loop's `if (existsSubs.containsKey(dotted)) continue;` guard so the two registrations do not fight. (c) No extra work is needed for the correlated predicate `$p.date == $b.trades.d`: filteredNavLeafRead already re-rewrites the inner predicate body through the enclosing scope (Substitution.java:2712-2714), and `trades` is already an assoc/nav head because [trades, d] is consumed.

**How legend-engine does it** — meta::relational::functions::pureToSqlQuery::processMap, legend-engine/.../core_relational/relational/pureToSQLQuery/pureToSQLQuery.pure:3938 — the map lambda is processed against the SAME source operation (line 3975 builds `sourceOp` from the map's own query), i.e. the engine β-binds the mapper against the navigation and keeps one cursor; there is no separate 'depth' restriction on a qualifier body reached through a map.

**Risk** — The emission becomes a correlated scalar subquery rather than the engine's join-with-pushed-predicate; these two tests assert ROWS only, so that is fine, and assertSameSQL elsewhere upgrades to row equivalence (EngineTestExecutor.java:986-1015), but watch for tests where a dotted ExistsSub now shadows an emptiness-chain registration. Tenet-2 trap: do NOT make this pass by teaching the harness to pre-fold qualified properties or by special-casing `->map` in the test runner — the deepening is a platform-owned rewrite.

**Also unblocks** — functions/tests/projection/testFunctionVariables.pure:89 testVariableReferenceInMapWithNestedFilter (identical wall: TypedFilter over $p.firm.address after the map inline), and probably its neighbour testVariableReferenceInMapWithSameNameAsThatInParentProject

**Falsifier** — Run the two tests with LL_FNLR_DEBUG=1 (the switch already exists at Substitution.java:2588). If the log shows `[fnlr] head not on userVar: TypedPropertyAccess`, the head-depth mismatch is confirmed; if instead it shows `[fnlr] no ExistsSub for 'products'` or nothing at all, this diagnosis is wrong.

<details><summary>Evidence read (8 citations)</summary>

- core/src/main/java/com/legend/resolver/Substitution.java:1815 — `case TypedMap m ... -> rewrite(inlineParam(m.mapper().body().get(0), m.mapper().parameters().get(0), m.source()))`: the map arm substitutes the mapper param with the source, turning a depth-1 filtered head into a depth-2 one
- core/src/main/java/com/legend/resolver/Substitution.java:2641 — filteredNavLeafRead's head match: `f.source() instanceof TypedPropertyAccess head && head.source() instanceof TypedVariable hv && hv.name().equals(target.userVar())`, else `return null`
- core/src/main/java/com/legend/resolver/Substitution.java:1893 — the default arm that emitted the observed message `object-space expression node TypedFilter is not substitutable yet (H2 vocabulary)`
- core/src/main/java/com/legend/resolver/Substitution.java:1848 — the ONLY TypedFilter arm is gated on `f.source().info().type() instanceof Type.RelationType`, so a class-typed (object-space) filter has no arm
- core/src/main/java/com/legend/resolver/StoreResolver.java:2121 — registerExistsSubs: `if ((path.size() != 1 && !filterTwoHop) || existsSubs.containsKey(head)) continue;` — no ExistsSub is ever keyed by a multi-hop chain in value position
- core/src/main/java/com/legend/resolver/SyntheticHeads.java:353 — the deep-head lift arm, suppressed for scalar reads whose head is `directlyOnVar` (SyntheticHeads.java:944-954), i.e. exactly this node before the map inline
- core/src/main/java/com/legend/resolver/SyntheticHeads.java:504 — `case TypedMap m -> new TypedMap(..., (TypedLambda) liftFilteredHeads(m.mapper(), false), ...)`: lifting is off inside map mappers
- tests/injection/testInjection.pure:36-40 (corpus) — `productAtTimeOfTrade(){$this.products->filter(p|$p.date == $this.d)->toOne()}:Product[1]`, and the mapping binds `trades`/`products` as Join PMs (testInjection.pure:123-142), so the head is a 2-hop NAV chain, not an assoc-chain

</details>

---

## `testProjectThroughAssociationAutoMap`

| | |
|---|---|
| family | `tests/injection` |
| sweep status | ERROR |
| **verdict** | **REAL DEFECT** |
| effort | XS |
| confidence | medium |

**Root cause**

Identical to testProjectThroughAssociation — the auto-map spelling `$b.trades.productAtTimeOfTrade.name` is the same TypedMap node after desugaring (Substitution.pathOf itself treats `->map(l|path)` as the auto-map spelling, Substitution.java:720-727). The qualified property body `$this.products->filter(...)->toOne()` sits inside the mapper, so it is never lifted (SyntheticHeads.java:504-508), the map arm inlines the param (Substitution.java:1815-1831) deepening the filtered head to `$b.trades.products`, filteredNavLeafRead's single-hop head match fails (Substitution.java:2641-2656), and the object-space TypedFilter reaches the default wall (Substitution.java:1893-1899). The reported failure detail is byte-identical to test 1, which is itself evidence the two converge on one node shape.

**Fix**

Same change as testProjectThroughAssociation — no additional work: (a) key filteredNavLeafRead's ExistsSub lookup by the dotted `pathOf(f.source(), userVar)` chain (Substitution.java:2641-2657); (b) register value-position filtered-nav ExistsSubs under dotted keys by reusing the chained registration at StoreResolver.java:1640-1738. If instead you choose the alternative route (β-inline map mappers in the PRE-REWRITE canonicalization so the demand scan and SyntheticHeads both see the deep head and the deep-head lift arm at SyntheticHeads.java:353 mints `products#f0`), note it additionally requires composing a CORRELATED predicate into a SUB-nav hop's join condition, which NavMaterializer.demandSlotSubTail explicitly refuses today (NavMaterializer.java:456-461) — that is why the correlated-scalar route above is the cheaper fix.

**How legend-engine does it** — legend-engine/.../pureToSQLQuery/pureToSQLQuery.pure:3938-3975 (processMap) — the auto-map and explicit-map spellings are the same FunctionExpression to the engine; the lambda is processed against the map's own source operation.

**Risk** — Same as test 1. Because both tests are one shape, fixing one without the other would indicate the fix was written against the spelling rather than the node.

**Also unblocks** — same set as testProjectThroughAssociation

**Falsifier** — If testProjectThroughAssociation is fixed and this one still walls with the same message, the two are not one shape and this diagnosis is wrong.

<details><summary>Evidence read (5 citations)</summary>

- core/src/main/java/com/legend/resolver/Substitution.java:720 — pathOf's auto-map arm: `if (n instanceof TypedMap m && ... pathOf(mapper body) != null) return pathOf(inlineParam(...))` — the demand scan flattens auto-maps whose body is a plain path, but NOT bodies containing a filter, so the deepened filtered head is never seen at scan time either
- core/src/main/java/com/legend/resolver/Substitution.java:1815 — the map arm's inlineParam, which performs the deepening at rewrite time
- core/src/main/java/com/legend/resolver/Substitution.java:2641 — filteredNavLeafRead's single-hop head requirement
- core/src/main/java/com/legend/resolver/Substitution.java:1893 — the default wall that produced the message
- /Users/neemsandv/.claude/jobs/5671074c/tmp/burndown/dossiers/tests__injection.md:7,24 — the two tests report the SAME truncated node string (TypedFilter over PA(PA($b))), confirming one shape

</details>

---

## `testDenormMappingWithQualifierWithIfAndEquals`

| | |
|---|---|
| family | `tests/mapping/embedded` |
| sweep status | ERROR |
| **verdict** | **REAL DEFECT** |
| effort | S (revised up from XS by adversarial review) |
| confidence | high |
| **adversarial review** | **CONFIRMED** |

**Root cause**

Typer.synth's zero-arg-derived arm refuses to β-inline a derived body over a [0..1] receiver unless the body passes a hand-written "null-strict" whitelist. `Person.firm` is [0..1] (association Employment), and `Firm.isFirmX(){if($this.legalName->toOne()=='Firm X',|'Yes',|'No')}` starts with `if`, which is a member of EMPTY_MANUFACTURING_FNS, so strictScan sets the non-strict bit and derivedBodyStrictInThis returns false → TypeInferenceException. The whitelist encodes a Pure-semantics belief (an empty receiver must auto-map to empty, so a value-manufacturing body must not inline) that the relational store does NOT implement: legend-engine's processQualifiedProperty simply processes the qualifier's expressionSequence against the current cursor with no presence guard and no regard for receiver multiplicity. The corpus proves engine's behaviour is the manufactured value, not empty — testQualifierWithInThroughJoin asserts cat='B' for a trade whose LEFT-JOINed account row is absent (name comes back as ^TDSNull() on that same row). So the wall is legend-lite policy that contradicts the ground truth, and it is the only thing standing between this test and the correct emission.

**Fix**

In /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/compiler/spec/Typer.java, delete the `if (!exactlyOne) { … }` block at lines 2495-2513 in the zero-arg-derived arm of synth(AppliedProperty). A [0..1] receiver then falls through to the same `return applyGeneric(new AppliedFunction(d.bodyFunctionFqn(), List.of(ap.receiver())), env);` at line 2514 that a [1] receiver already takes — which is exactly processQualifiedProperty's behaviour. Keep the `isMany()` auto-map branch at 2487-2494 untouched (that IS Pure's auto-map and is correct). Then delete the now-dead private helpers `derivedBodyStrictInThis` (2724-2732), `strictScan` (2735-2771) and the constant EMPTY_MANUFACTURING_FNS (2715-2722), and rewrite the comment block at 2476-2481/2706-2714 to record the engine rule: 'the relational store inlines a qualifier body regardless of receiver multiplicity; an absent [0..1] receiver yields the body evaluated over NULL columns, not empty (golden: testQualifierWithInThroughJoin returns cat=B for the account-less trade).' Nothing else in the pipeline needs to change — the resulting `$p.firm.legalName` chain is the same shape testDenormMappingOneToManyProject already resolves.

**How legend-engine does it** — /Users/neemsandv/legend/legend-engine/legend-engine-xts-relationalStore/legend-engine-xt-relationalStore-generation/legend-engine-xt-relationalStore-pure/legend-engine-xt-relationalStore-core-pure/src/main/resources/core_relational/relational/pureToSQLQuery/pureToSQLQuery.pure:1061-1074 — meta::relational::functions::pureToSqlQuery::processQualifiedProperty inlines the qualifier's single expressionSequence against the current SelectWithCursor with no multiplicity check and no null/presence guard.

<details><summary>Adversarial review notes (CONFIRMED)</summary>

Every citation is line-exact and the mechanism is the observed failure. Typer.java:2482 defines exactlyOne (lower==1 && upper==1), :2487 routes only isMany() to auto-map, :2495 opens the `if (!exactlyOne)` block, :2505 throws the message that appears verbatim in the sweep detail ('derived property ... over a [0..1] receiver has a body outside the null-strict whitelist — empty-receiver semantics needs the presence-guarded emission (roadmap)'), :2514 is the shared beta-inline. EMPTY_MANUFACTURING_FNS at :2715-2722 lists "if" first; derivedBodyStrictInThis at :2724-2732 ends `return (flags & 1) != 0 && (flags & 2) == 0;`. Model facts check out: testMappingEmbedded imports meta::relational::tests::model::simple, so Person/Firm ARE the simple model classes; isFirmX is simpleTestModel.pure:48-50 with an if-headed body; Employment declares firm : Firm[0..1] at :350-352. The engine ground truth is stronger than the diagnosis claims: testIn.pure:209-218 (testQualifierWithInThroughJoin) emits `case when "accounttable_0".name in (...) then 'A' else 'B' end` over a LEFT JOIN with no presence guard and asserts 'B' for the account-less row where name is ^TDSNull() — engine really does manufacture the else-branch over an empty [0..1] receiver. And I found independent corroboration the diagnosis did not use: testQualifierWithInThroughJoin ITSELF currently fails in this sweep with the identical wall message, so deleting the block plausibly clears two tests. Collateral risk is low by construction: the deleted block can only THROW today, so no currently-passing test depends on it; derivedBodyStrictInThis/strictScan/EMPTY_MANUFACTURING_FNS have no other references (grep: only :2505/:2724 and :2735/:2742). Two things I could not settle without running, hence the effort bump: (a) whether the resulting if/toOne/== chain lowers to exactly `case when "root".FIRM_LEGALNAME = 'Firm X' then 'Yes' else 'No' end` — if not, the test flips ERROR→SHAPE rather than PASS; (b) the Typer is shared, so if any non-relational evaluation path exists in legend-lite, removing the guard changes [0..1] auto-map semantics there too (the code comment at :2476-2481 cites map.pure grammarDoc for that Pure rule). Note also that the parameterized-qualifier route at Typer.java:461-474 ALREADY inlines unconditionally over a [0..1] receiver, so the fix makes the property spelling agree with the call spelling — a consistency argument the diagnosis missed.

</details>

**Risk** — Strictly a widening: the deleted code only ever threw. No currently-passing test can lose a value because of it. The real (and lower) risk is that some other corpus golden somewhere asserts EMPTY for a [0..1]-received non-strict derived; if such a golden exists, engine parity would then require a per-mapping presence guard, which is a much bigger change. No harness-compensation trap here — this is squarely the Typer's job, and the alternative (teaching the test runner to tolerate the wall) would be the cardinal sin.

**Also unblocks** — testQualifierWithInThroughJoin (unit U40) — identical message, `accountCategory` over Trade.account[0..1]; same one-block deletion unblocks it.

**Falsifier** — Grep the corpus for a golden that projects a derived property through a [0..1] receiver and asserts an EMPTY / ^TDSNull() cell for the absent case. If one exists, unguarded inlining is wrong and the fix must instead be the presence-guarded emission. testQualifierWithInThroughJoin (testIn.pure:215) is the opposite evidence and I found no counter-example, but I did not exhaustively sweep the corpus.

<details><summary>Evidence read (11 citations)</summary>

- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/compiler/spec/Typer.java:2505 — `if (!derivedBodyStrictInThis(d)) { throw new TypeInferenceException("derived property '" + ap.property() + "' over a [0..1] receiver has a body outside the null-strict whitelist …"); }` — the exact message in the failure detail
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/compiler/spec/Typer.java:2716 — EMPTY_MANUFACTURING_FNS = Set.of("if", "match", "isEmpty", …); `if` is the first member
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/compiler/spec/Typer.java:2731 — derivedBodyStrictInThis returns `(flags & 1) != 0 && (flags & 2) == 0`, i.e. any banned fn anywhere in the body fails it
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/compiler/spec/Typer.java:2482 — `exactlyOne` requires lower==1 && upper==1; line 2487 routes only `isMany()` receivers to auto-map, so [0..1] falls into the walled branch
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/compiler/element/type/Multiplicity.java:35 — `isMany()` is `upper()==null || upper()>1`, so [0..1] is NOT many and cannot take the auto-map path
- /Users/neemsandv/legend/legend-engine/.../core_relational/relational/tests/testModel/simpleTestModel.pure:48 — `isFirmX(){ if ($this.legalName->toOne() == 'Firm X', | 'Yes', | 'No') }:String[1];`
- /Users/neemsandv/legend/legend-engine/.../core_relational/relational/tests/testModel/simpleTestModel.pure:352 — Association Employment declares `firm : Firm[0..1];`
- /Users/neemsandv/legend/legend-engine/.../core_relational/relational/tests/mapping/embedded/testEmbeddedMapping.pure:169 — golden SQL is `case when "root".FIRM_LEGALNAME = 'Firm X' then 'Yes' else 'No' end` — no presence guard of any kind
- /Users/neemsandv/legend/legend-engine/.../core_relational/relational/pureToSQLQuery/pureToSQLQuery.pure:1074 — processQualifiedProperty: `let result = processValueSpecificationReturnPropertyMapping($expression->toOne(), …)` — the qualifier body is processed straight against the cursor; nothing consults receiver multiplicity or emits an emptiness guard
- /Users/neemsandv/legend/legend-engine/.../core_relational/relational/functions/tests/projection/testIn.pure:215 — `assertSameElements(['A', 'Account 2', 'B', ^TDSNull()], $result.values.rows.values);` with SQL at :216 `case when "accounttable_0".name in (…) then 'A' else 'B' end` — the absent-account row yields 'B', i.e. engine manufactures the else-branch over an empty [0..1] receiver
- /Users/neemsandv/legend/legend-engine/.../core_relational/relational/tests/testModel/simpleTestModel.pure:463 — `accountCategory(){ if ( $this.name->in([…]), | 'A', | 'B') }` — an `if`-headed body, same whitelist rejection, over Trade.account which simpleTestModel.pure:622 declares `account : Account[0..1]`

</details>

---

## `testExists`

| | |
|---|---|
| family | `tests/mapping/embedded` |
| sweep status | ERROR |
| **verdict** | **REAL DEFECT** |
| effort | S |
| confidence | medium |

**Root cause**

Substitution.rewriteCallArms has the correct arm for `exists` over a same-row embedded head (predicate applied directly over the parent row, no EXISTS subquery — the engine's own rule), but its admission gate predLeavesIn only accepts predicate paths of LENGTH 1. The predicate here is `a | $a.address.name == '200 west'`, whose collected param path is [address, name] (size 2, because `address` is itself a nested ^Address(...) ctor inside the ^Firm(...) partial). predLeavesIn returns false at the `path.size() != 1` test, the arm declines, and the walk falls through to the ordinary rewrite, which descends into the exists call's arg0 `$p.firm`. rewriteHeadProp then sees a binding whose look-through is a TypedNewInstance and throws the graph-output NotImplementedException — the message in the failure detail. Secondary contributing defect: collectParamPaths adds PREFIX paths as well as maximal ones (it does not return after out.add(p)), so even a generalized leaf check would see the spurious [address] entry, whose resolution is a ctor (a class-typed value) and would wrongly fail the gate. substEmbeddedReads has the mirror-image size-1 restriction — a 2-hop read would be rebuilt as a TypedPropertyAccess over a TypedNewInstance, which no downstream layer can lower, so both sides must be generalized together.

**Fix**

Three coordinated edits in /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/resolver/Substitution.java. (1) Add a private static helper `@Nullable TypedSpec partialLeaf(TypedNewInstance partial, List<String> path)` that starts at `partial.properties().get(path.get(0))` and runs the identical descent loop as ctorTailLeaf (lines 3097-3120): toOne look-through, otherwiseOf look-through, `ni.properties().containsKey(path.get(h))` → descend, else null; finally unwrap a trailing toOne and return null if the result is still a TypedNewInstance (a class-typed value is not a leaf and must stay loud). Refactor ctorTailLeaf to delegate to it so there is exactly one descent. (2) In collectParamPaths (2418-2430) add `return;` immediately after `out.add(p);` so only MAXIMAL paths are collected — prefixes are recursion artifacts, and a prefix always resolves to a ctor which the new leaf test would reject. (3) Replace the body of predLeavesIn's loop (2410-2414) with `for (List<String> path : paths) { if (partialLeaf(partial, path) == null) { return false; } }`, and in substEmbeddedReads (2453-2457) replace the `p.size() == 1` special case with `if (p != null) { TypedSpec lf = partialLeaf(partial, p); if (lf != null) { return renameRowVar(lf); } }` — falling through to the existing loud NotImplementedException at 2482-2486 when it does not resolve. Gate and substitution then share one resolver and cannot drift.

**How legend-engine does it** — /Users/neemsandv/legend/legend-engine/legend-engine-xts-relationalStore/legend-engine-xt-relationalStore-generation/legend-engine-xt-relationalStore-pure/legend-engine-xt-relationalStore-core-pure/src/main/resources/core_relational/relational/pureToSQLQuery/pureToSQLQuery.pure:5327 — processExists: `if (!$leftSidePropertyMappings->isEmpty() && $leftSidePropertyMappings->at(0)->instanceOf(EmbeddedRelationalInstanceSetImplementation), | … processFilter($expression, …)` — an exists whose left side is an embedded set implementation is processed AS A FILTER, and processFilter navigates the predicate to arbitrary depth, not just one hop.

**Risk** — Widening only for exists/isEmpty/isNotEmpty over a depth-1 embedded head; a path that does not resolve to a scalar leaf still declines the arm and takes the old route. The one real hazard is (2): making collectParamPaths maximal-only changes an input that predLeavesIn currently relies on — a body that reads `$a.x` bare AND `$a.x.y` would previously have failed on the `$a.x` entry and will now be judged on `$a.x.y` alone; that is the desired behaviour but it is a semantic change, so the `return null if TypedNewInstance` guard in partialLeaf is load-bearing and must not be dropped. No harness compensation: this is entirely resolver-side.

**Also unblocks** — Any other embedded-exists site whose predicate reads a nested embedded member (e.g. exists predicates over a firm/address-shaped denormalized mapping). None confirmed in this unit.

**Falsifier** — If, after generalizing, the emitted predicate is not `"root".FIRM_ADDRESS_NAME = '200 west'` — the cheapest check is to print the resolved binding of `partial.properties().get("address")` for testMappingEmbedded and confirm its own `name` field is a TypedPropertyAccess over the SAME row var the outer partial reads. If nested embedded ctors are bound over a fresh row var instead, renameRowVar alone will not correlate them and the fix is wrong.

<details><summary>Evidence read (10 citations)</summary>

- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/resolver/Substitution.java:2411 — `if (path.size() != 1 || !partial.properties().containsKey(path.get(0))) { return false; }` inside predLeavesIn — the exact gate that rejects [address, name]
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/resolver/Substitution.java:802-812 — the embedded-exists arm: `if (headPath != null && headPath.size() == 1 && isEmptinessFamily(call) … ) { var partial = embeddedPartialOf(target.bindings().get(headPath.get(0))); if (partial != null && predLeavesIn(pl, partial)) { return rewriteEmbeddedExists(pl, partial); } }` — everything matches for this call except predLeavesIn
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/resolver/Substitution.java:1370 — `throw new NotImplementedException("class-typed property '$" + target.userVar() + "." + prop + "' used as a whole value is graph output (Phase H4)")` in rewriteHeadProp, reached because `inner instanceof TypedNewInstance` (line 1367) for the `firm` binding
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/resolver/Substitution.java:2454 — substEmbeddedReads: `if (p != null && p.size() == 1) { return renameRowVar(partial.properties().get(p.get(0))); }` — the mirror-image depth-1 restriction
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/resolver/Substitution.java:2418-2430 — collectParamPaths does `out.add(p)` and then still recurses into children, so `$a.address.name` contributes BOTH [address, name] and [address]
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/resolver/Substitution.java:3093-3122 — ctorTailLeaf: the exact nested-ctor descent (toOne + otherwise look-through, `ni.properties().containsKey(path.get(h))`) that the fix needs, already written, but keyed off a SubNav rather than a TypedNewInstance
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/resolver/Substitution.java:1263-1286 — the projection-side multi-hop embedded-ctor drill that already resolves `$p.firm.address.name` and ends in `renameRowVar(cur)`, proving nested ctor leaves are bound over the OWNER row var
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/normalizer/MappingNormalizer.java:2708 — materializeEmbedded builds every sub-PM field with the SAME `rowBind`, recursing for nested embedded blocks — so ^Firm(address=^Address(name=$row.FIRM_ADDRESS_NAME…)) all reads one row var
- /Users/neemsandv/legend/legend-engine/.../core_relational/relational/tests/mapping/embedded/testEmbeddedMapping.pure:192 — golden `… from PERSON_FIRM_DENORM as "root" where "root".FIRM_ADDRESS_NAME = '200 west'` — no EXISTS, no join: the predicate is applied inline over the parent row, which is precisely what rewriteEmbeddedExists produces
- /Users/neemsandv/legend/legend-engine/.../core_relational/relational/tests/mapping/embedded/testEmbeddedMapping.pure:490-494 — the mapping nests `address ( name: FIRM_ADDRESS_NAME, type: … )` INSIDE the `firm ( … )` embedded block, so the partial's `address` member is itself a ctor

</details>

---

## `testProjectionOtherwiseNonPrimitive`

| | |
|---|---|
| family | `tests/mapping/embedded` |
| sweep status | ERROR |
| **verdict** | **REAL DEFECT** |
| effort | S |
| confidence | medium |

**Root cause**

StoreResolver's OTHERWISE per-leaf dispatch treats membership in the embedded partial as proof that the next hop is a same-row column, and short-circuits the whole navigate-slot demand with `continue`. For path [bondDetails, bondClassification, type] under testMappingEmbeddedOtherwise3, the partial ^BondDetail(description=…, bondClassification=$row.bondClassification) DOES contain `bondClassification` — but as a CLASS-TYPED navigate-slot read (JoinChainEmission mints the slot for the Otherwise partial's structural Join sub-PMs, and materializeEmbedded turns it into a slot-reading ctor field). So no join is ever demanded for it and no AssocSub is registered under the dotted key bondDetails.bondClassification. At rewrite time rewriteMultiHop then fails every arm: a3.subNavs() has only `holder` (registered via the OTHERWISE fallback for the [bondDetails, holder, name] path); the chainKey lookup misses; the embedded-ctor drill at 1263-1286 walks partial→bondClassification and then stops because the slot read is not a TypedNewInstance; the head-join+embedded-tail arm stops for the same reason on bondMapping2's chain binding — and it throws the multi-hop wall with exactly the reported `[assocs=[bondDetails]; head subNavs=[holder]; head binding=TypedNativeCall]`. Note the drill machinery for this shape ALREADY exists two lines below the `continue` (the 'EMBEDDED head: the path walks INTO the ^Inner ctor … the AssocSub registers under the DOTTED key' loop) — the otherwise arm just never reaches it. This is also the exact semantic legend-engine implements: the partial's own property mapping wins over the otherwise target's, which is why the golden joins BondClassificationTable off "root" (PRODUCT_DENORM.PRODUCT_ID) and not off bond_detail_0.

**Fix**

In /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/resolver/StoreResolver.java replace lines 1345-1353 with a KIND-aware dispatch:

  var ow = Substitution.otherwiseOf(headBinding);
  if (ow != null) {
      var partial = (TypedNewInstance) ow.args().get(0);
      TypedSpec pb = partial.properties()
              .get(SyntheticHeads.realHead(path.get(1)));
      if (pb != null) {
          // engine pureToSQLQuery.pure:720 — the partial's OWN property
          // mapping wins over the otherwise target's. A same-row column
          // needs no join; a CLASS-TYPED navigate slot inside the partial
          // drills into the ctor below and registers a DOTTED AssocSub.
          if (InnerDemand.navSlotAlias(pb, cs.rowVar(), navSteps.keySet()) == null) {
              continue;
          }
          navRead = partial;
      } else {
          navRead = ow.args().get(1);
      }
  }

Nothing else changes: with navRead = partial the existing drill loop (1362-1382) sees a TypedNewInstance, descends on path.get(1), lands mid=2 on the slot read, and headKey becomes "bondDetails.bondClassification"; navTails picks up ["type"], the loop at 1502-1532 registers the dotted AssocSub, and Substitution.rewriteMultiHop's chainKey branch (1256-1259) resolves the leaf via assocLeaf. Note the sibling [bondDetails, holder, name] path still takes the `else` branch and keeps its own "bondDetails" AssocSub off the otherwise fallback slot — the two coexist because they claim different slot aliases (navHeadByAlias is keyed by alias).

**How legend-engine does it** — /Users/neemsandv/legend/legend-engine/legend-engine-xts-relationalStore/legend-engine-xt-relationalStore-generation/legend-engine-xt-relationalStore-pure/legend-engine-xt-relationalStore-core-pure/src/main/resources/core_relational/relational/pureToSQLQuery/pureToSQLQuery.pure:720 — `o:OtherwiseEmbeddedRelationalInstanceSetImplementation[1] | let navigateToOtherwiseMapping = $o->propertyMappingsByPropertyName($property.name->toOne())->isEmpty();` then :739-743 keeps the embedded cursor when the partial owns the property, and :732 routes through otherwisePropertyMapping only when it does not. Per-property dispatch, exactly.

**Risk** — Two things to watch. (a) Precedence: after the fix, `bondDetails.bondClassification` exists as an assoc key AND `bondClassification` still exists in the otherwise target's targetBindings as a two-join chain. rewriteMultiHop consults the dotted chainKey (line 1256) before the head-join+embedded-tail arm (line 1293), so the partial wins — which is the engine's rule; if that order were ever reversed the query would silently join through BOND_DETAIL and emit a different (still plausible-looking) plan. (b) The `continue` currently also suppresses join demand for every same-row leaf of every Otherwise mapping; the navSlotAlias guard must be the ONLY thing that changes that, or otherwise mappings will start emitting spurious joins for plain columns. No harness compensation involved — this is demand-scan territory.

**Also unblocks** — Any otherwise-embedded mapping navigating three hops through a join declared inside the embedded block. None else confirmed in this unit; the plain (non-otherwise) embedded equivalent already works via the same drill.

**Falsifier** — Re-run and inspect the wall. If it still throws but now reports `assocs=[bondDetails, bondDetails.bondClassification]`, the demand side is fixed and the defect is downstream in assocLeaf/prefixing, not here. If it throws unchanged (`assocs=[bondDetails]`), then navSlotAlias returned null for the partial's bondClassification field — meaning the slot was never minted as a legacyNavigate nav step, and the root cause is in JoinChainEmission's OtherwiseEmbedded arm rather than in StoreResolver's continue.

<details><summary>Evidence read (13 citations)</summary>

- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/resolver/StoreResolver.java:1349 — `if (partial.properties().containsKey(path.get(1))) { continue;   // embedded leaf: parent-alias read, no join }` — the short-circuit; it asks only about membership, never about the member's KIND
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/resolver/StoreResolver.java:1354-1382 — the very next block is the ctor-drill that composes a DOTTED headKey (`String headKey = String.join(".", path.subList(0, mid));` at 1393) — the machinery the otherwise arm skips
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/resolver/StoreResolver.java:1524 — `assocs.put(headKey9, new Substitution.AssocSub(alias + "_", …))` — dotted head keys are first-class in the AssocSub registry
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/resolver/Substitution.java:1341-1349 — the thrown message, verbatim including `[assocs=` … `; head subNavs=` … `; head binding=`, matching the failure detail
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/resolver/Substitution.java:1256-1259 — `String chainKey = String.join(".", path.subList(0, path.size() - 1)); if (target.assocs().containsKey(chainKey)) { return assocLeaf(chainKey, path.get(path.size() - 1)); }` — the consumer that resolves this once the dotted AssocSub exists
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/resolver/Substitution.java:1276-1282 — the embedded-ctor drill only descends while the next value is a TypedNewInstance, so it dead-ends on a slot read
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/normalizer/JoinChainEmission.java:92-116 — the OtherwiseEmbedded arm: 'the PARTIAL's structural sub-PMs hoist like a plain embedded block (bondClassification: @J inside Otherwise(...)) — without them the partial's ctor field reads a slot that was never minted', then `emitHopsForStructuralPm(p, sub, nr.name(), …)`; the Join case at :81 passes classTypedTerminus=true, i.e. a real legacyNavigate slot
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/normalizer/MappingNormalizer.java:2696-2711 — materializeEmbedded: 'Join sub-PMs read the slot Pass 1 hoisted into the TOP pipeline' → the partial's bondClassification field IS `$row.<alias>`
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/normalizer/MappingNormalizer.java:2732-2736 — materializeOtherwiseEmbedded composes `otherwise(^Inner(<embedded subs>), $row.<prop>)`, so otherwiseOf().args().get(0) is the partial containing bondClassification
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/resolver/InnerDemand.java:209-225 — navSlotAlias returns the alias when the binding is `$rowVar.<alias>` and the alias is a known nav step — the kind test the fix needs
- /Users/neemsandv/legend/legend-engine/.../core_relational/relational/tests/mapping/embedded/testEmbeddedOtherwiseMapping.pure:84 — golden: `… left outer join BondClassificationTable as "bondclassificationtable_0" on ("root".PRODUCT_ID = "bondclassificationtable_0".PRODUCT_ID)` — joined off ROOT, i.e. via the partial's own mapping, while `holder` joins off bond_detail_0
- /Users/neemsandv/legend/legend-engine/.../core_relational/relational/tests/mapping/embedded/testEmbeddedOtherwiseMapping.pure:394-398 — testMappingEmbeddedOtherwise3's embedded block declares `bondClassification:[eDB]@BondDetailBondClassification` INSIDE `bondDetails ( … ) Otherwise([bondMapping2]:@BondDetailJoin)`; :414 shows bondMapping2 also declares its own two-join `bondClassification` — the two must not be confused
- /Users/neemsandv/legend/legend-engine/.../core_relational/relational/tests/mapping/embedded/testInlineEmbeddedMapping.pure:369 — `Join BondDetailBondClassification(PRODUCT_DENORM.PRODUCT_ID = BondClassificationTable.PRODUCT_ID)` — a root-anchored join, consistent with the golden

</details>

---

## `testChainedInnerJoinsWithQualifierInGroupBy`

| | |
|---|---|
| family | `tests/mapping/join` |
| sweep status | ERROR |
| **verdict** | **REAL DEFECT** |
| effort | M |
| confidence | high |

**Root cause**

`testFunction(f)` = `$f.employees->filter(e|$e.lastName == $f.legalName)->toOne().extraInformation`. The head `employees` is depth-1, so filteredNavLeafRead matches and builds the correlated scalar subquery; it then walls because the LEAF binding reads an unmaterialized join slot. That slot is unmaterialized because the exists target's slot demand is computed from `InnerDemand.leaves(ops, head)` (StoreResolver.java:2266-2267), and `leaves` only collects the path heads read by the LAMBDAS attached to the head (InnerDemand.java:162-170 -> lambdas/collect). For the filtered-nav-as-value shape, InnerDemand.collect adds only the filter PREDICATE lambdas (InnerDemand.java:434-458 `navLams`), never the property being read off the filter result — and `leafChains` (InnerDemand.java:244-256, used for nav-step demand) requires `chain.size() >= 2`, so a single-segment leaf like `extraInformation` is dropped there too. In chainedJoinsInner, `extraInformation : @Person_MiddleTable > (OUTER)[db]@MiddleTable_PersonExtension | personExtensionTable.EXTRAINFO` is a JOIN-SLOT binding, so `Pipelines.referencesAliasOn(leafBinding, ex.targetRowVar(), unconvertedT)` is true and Substitution.java:2760-2765 throws.

**Fix**

Demand the filtered-navigation LEAF, not just its predicates. In InnerDemand add a collector that mirrors the existing recognizer so the two cannot drift — e.g. `static Set<String> filteredNavLeaves(List<TypedSpec> ops, String head)` built from the same walk as collect's PropertyAccess arm (InnerDemand.java:434-458) but recording `pna.property()` when the unwrapped source is a filter chain over `head`. Union its result into the `demandedLeaves` argument at StoreResolver.java:2266 (association route) and into `innerLeaves` at StoreResolver.java:2143-2149 (navigate route). AssociationJoins.java:993-1001 then collects the alias reads of `extraInformation`'s binding, materializes the MiddleTable/personExtensionTable slot into the exists target, and `aj.targetSlotPrefixes()` carries the prefix so Substitution.java:2766-2775's CONVERTED branch rewrites the read instead of the wall at 2760 firing.

**Risk** — Adding slot demand to an EXISTS target adds a LEFT join inside the subselect; the code already flags the cardinality hazard for slot-demanded exists material (StoreResolver.java:2270-2276, 'audit 13 B3 ... a separate scalar pipeline regressed real value-leaf reads'), so re-check exists/isEmpty consumers of the same head after the change. The residual risk on this test is the emission shape: legend-lite will produce count(<correlated scalar subquery>) where the engine produces count over a joined subselect — assertSameSQL upgrades to row equivalence (EngineTestExecutor.java:986-1015), so it should still verify against the expected [0].

**Falsifier** — Run with LL_FNLR_DEBUG=1 and read the `[fnlr] leaf 'extraInformation' hops=[] ... slotPrefixes={...}` line (Substitution.java:2735-2741). If slotPrefixes already contains the personExtension alias, the leaf slot IS materialized and this diagnosis is wrong.

<details><summary>Evidence read (8 citations)</summary>

- core/src/main/java/com/legend/resolver/Substitution.java:2760 — `throw new NotImplementedException("filtered-navigation leaf '" + pa.property() + "' reads a join slot of '" + ex.targetClassFqn() + "' — slot-demanding leaves under value-position filters are not supported yet")` — the exact message; the branch right below (Substitution.java:2766-2775) shows the CONVERTED case works once the slot is in `ex.targetSlotPrefixes()`
- core/src/main/java/com/legend/resolver/Substitution.java:2691-2694 — `unconvertedT = ex.targetSlotAliases() minus ex.targetSlotPrefixes().keySet()` — the wall fires exactly when the leaf's slot was never demanded
- core/src/main/java/com/legend/resolver/InnerDemand.java:434-458 — collect's filtered-nav arm: it walks the toOne/first wrappers and the filter chain and then `out.addAll(navLams)` — predicates only; `pna.property()` is never recorded
- core/src/main/java/com/legend/resolver/InnerDemand.java:162-170 — `leaves(ops, head)` = collectParamPathHeads over those lambdas only
- core/src/main/java/com/legend/resolver/InnerDemand.java:244-256 + 279-291 — leafChains requires `sawFilter && chain.size() >= 2`, so a 1-segment leaf never becomes nav-step demand either
- core/src/main/java/com/legend/resolver/StoreResolver.java:2266 — `AssociationJoins.AssocJoin aj = assocMaterial.associationJoin(temporal, cs, head, context, true, InnerDemand.leaves(ops, head));` then StoreResolver.java:2281-2287 stores `aj.targetSlotPrefixes()` into the ExistsSub
- core/src/main/java/com/legend/resolver/AssociationJoins.java:993-1001 — the target's slot demand is derived ONLY from `demandedLeaves`: `for (String leaf : demandedLeaves) { TypedSpec b = target.bindings().get(leaf); ... collectAliasReads(b, target.rowVar(), targetSlots, targetDemand); }`
- tests/mapping/join/advancedRelationalSetUp.pure:291-302 (corpus) — chainedJoinsInner maps `extraInformation` through `@Person_MiddleTable > (OUTER)[db]@MiddleTable_PersonExtension | personExtensionTable.EXTRAINFO`, i.e. a join-slot binding

</details>

---

## `testDerivedPropertyInCondition`

| | |
|---|---|
| family | `tests/mapping/modelJoin` |
| sweep status | ERROR |
| **verdict** | **MISSING FEATURE** |
| effort | S |
| confidence | high |

**Root cause**

The ModelJoin condition is `{employees: Person[1], firm: Firm[1] | $employees.fullName == $firm.code->toOne()}` (modelJoinSimpleSetup.pure:440) and `fullName` is a zero-arg QUALIFIED/derived property `fullName() { $this.firstName + ' ' + $this.lastName }: String[1]` (modelJoinSimpleSetup.pure:109) — it has no column in the Relation(~func) class mapping (which maps only firstName/lastName/age/+firmId, modelJoinSimpleSetup.pure:421-428). MappingNormalizer.synthesizeModelJoinMapping rewrites the condition with RelationReads.rewrite (MappingNormalizer.java:1249-1252). RelationReads.rewrite matches `$employees.fullName` on its plain-property arm (RelationReads.java:90-92), scans rf.columns() for a Col with property=="fullName" and a non-null column (94-96), then for an expression-bodied Col (117-122), finds neither, and throws NotImplementedException "association '<assoc>': $employees.fullName has no column binding on the Relation mapping of 'Person' (mapping=...)" (RelationReads.java:123-127). The rewriter has NO arm for derived/qualified properties: it never consults ClassDefinition.derivedProperties(). MappingNormalizer catches that throw per-association and records it as a poison keyed `<mapping>::<assocFqn>` (MappingNormalizer.java:419-435), dropping the Person_Firm AssociationBinding. When the query later navigates Person.firm, AssociationJoins.predicateMaterial cannot find the binding and throws "association 'Person_Firm' is not mapped in mapping 'DerivedPropertyConditionMapping'" with the recorded poison appended in parentheses (AssociationJoins.java:1166-1177) — exactly the observed text.

**Fix**

In core/src/main/java/com/legend/normalizer/RelationReads.java, add a DERIVED-PROPERTY arm to `rewrite(...)` immediately before the `throw` at line 123. When `model != null`, resolve `rf.className()` via `model.findClass(...)`, walk its `derivedProperties()` (and superclasses', reusing the same super-walk shape as `findPropertyDeclared` at RelationReads.java:150-170) for a `DerivedPropertyDefinition` whose `name().equals(ap.property())` and `parameters().isEmpty()` and whose `realization()` is a `Realization.Inline` with exactly one expression. Beta-inline it: substitute the free variable `this` in that expression with the ORIGINAL receiver `Variable var` (a name-keyed substitution over the protocol ValueSpecification tree — the same traversal already written at RelationReads.java:129-147), then recursively call `rewrite(inlined, rowByVar, rfByVar, assocName, md, nestedCols, model)` so the resulting `$employees.firstName` / `$employees.lastName` take the existing plain-column arm. Guard the recursion with a depth counter (a derived property whose body reads another derived property is legal; a self-referential one must stay loud). Two accompanying changes are required: (1) ModelJoinNesting.java:120-124 calls the 6-arg `RelationReads.rewrite(...)` overload, which passes `model = null` (RelationReads.java:50-57) — thread `model` through so derived properties inside a NESTED ModelJoin condition inline too; (2) keep the existing `[1]`-declared toOne wrapping (RelationReads.java:98-108) applied to the inlined leaf reads, since firstName/lastName are String[1].

**How legend-engine does it** — legend-engine .../core/pure/router/store/builder.pure:363 — `let routed = $m.joinCondition->routeFunction(false, $initialState, ^ExecutionContext(), $initialVars, [], $extensions, $debug);` The ModelJoin condition is routed by the standard router, which resolves/inlines QualifiedProperty calls (same file, lines 305-308 explicitly branch on `func->instanceOf(QualifiedProperty)` and propagate through its expressionSequence); the relational store then compiles the ROUTED lambda (.../pureToSQLQuery/relationalModelJoins.pure:95-98). The engine gets derived properties in ModelJoin conditions for free — see legend-engine/docs/engineering/architecture/model-join.md:93-98.

**Risk** — The expected golden in the test is `concat("persontable_0"."FIRSTNAME", ' ', "persontable_0"."LASTNAME")` — a 3-arg concat. legend-lite's lowering of `a + ' ' + b` may emit nested `concat(concat(a,' '),b)` or `||`, so this test can still fail on the `$result->sql()` assert even once resolution succeeds; that residual is a separate GOLDEN_TEXT question, not part of this fix. TENET-2 TRAP: do NOT make this go away by adding `fullName` as a `+local` column to the harness's copy of the mapping or by relaxing the SQL assert — the corpus mapping is data and the platform owns derived-property inlining. Also do not 'fix' it by special-casing the name `fullName`; the arm must be driven by ClassDefinition.derivedProperties().

**Also unblocks** — None in the modelJoin family — `fullName` appears in exactly one mapping condition (grep of tests/mapping/modelJoin shows only modelJoinSimpleSetup.pure:440). It would unblock any other corpus family that spells a derived property inside a ModelJoin/XStore condition.

**Falsifier** — Re-run the sweep printing the UNTRUNCATED failure message. If it does not end with `has no column binding on the Relation mapping of 'meta::relational::tests::mapping::modelJoin::domain::Person' (mapping=meta::relational::tests::mapping::modelJoin::simple::DerivedPropertyConditionMapping)`, the throw is not RelationReads.java:123 and this diagnosis is wrong.

<details><summary>Evidence read (10 citations)</summary>

- /Users/neemsandv/legend/legend-engine/legend-engine-xts-relationalStore/legend-engine-xt-relationalStore-generation/legend-engine-xt-relationalStore-pure/legend-engine-xt-relationalStore-core-pure/src/main/resources/core_relational/relational/tests/mapping/modelJoin/modelJoinSimpleSetup.pure:109 — `fullName() { $this.firstName + ' ' + $this.lastName }: String[1];` is a qualified property on Person, not a mapped column
- /Users/neemsandv/legend/legend-engine/.../tests/mapping/modelJoin/modelJoinSimpleSetup.pure:440 — the ModelJoin condition is `$employees.fullName == $firm.code->toOne()`
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/normalizer/RelationReads.java:123-127 — `throw new NotImplementedException("association '" + assocName + "': $" + var.name() + "." + ap.property() + " has no column binding on the Relation mapping of '" + rf.className() + "' (mapping=" + md.qualifiedName() + ")")` — the exact message in the failure detail
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/normalizer/RelationReads.java:94-122 — the only two lookups before that throw are rf.columns() by column and by expr; nothing consults derivedProperties()
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/normalizer/MappingNormalizer.java:1245-1252 — synthesizeModelJoinMapping calls ModelJoinNesting.compose then RelationReads.rewrite(mj.lambda() body, rowByVar, rfByVar, assocName, md, nestedCols, model)
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/normalizer/MappingNormalizer.java:419-435 — the per-association catch records `model.mappingPoisons.putIfAbsent(md.qualifiedName() + "::" + <assoc FQN>, e.getMessage())` and drops the binding
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/resolver/AssociationJoins.java:1166-1177 — `orElseThrow(... "association '" + assoc.qualifiedName() + "' is not mapped in mapping '" + cs.mappingFqn() + "'" + ctx.mappingPoison(...).map(r -> " (" + r + ")"))` — the outer half of the observed message
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/protocol/DerivedPropertyDefinition.java:22-46 — derived properties carry `List<ParameterDefinition> parameters` and a `Realization.Inline(List<ValueSpecification> expression)` body, i.e. the protocol-level material needed to beta-inline is already present
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/model/ClassDefinition.java:48-65 — ClassDefinition exposes `List<DerivedPropertyDefinition> derivedProperties`
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/resolver/AssociationJoins.java:1261-1263 — precedent: the PROPERTY-SPACE condition route already does exactly this (`body = inlineDerivedCondCalls(body, 0); // DERIVED-property calls ($srcRow.productId()) β-inline first`), but ModelJoin conditions never take that route

</details>

---

## `testNestedModelJoinCompoundInnerCondition`

| | |
|---|---|
| family | `tests/mapping/modelJoin` |
| sweep status | ERROR |
| **verdict** | **MISSING FEATURE** |
| effort | M |
| confidence | high |

**Root cause**

ModelJoin nesting in legend-lite is exactly ONE level deep. The mapping NestedModelJoinWithPropertyAccess declares Person_Profile, Person_Address (`{person, address | $person.profile.rank == $address.country && $person.id == $address.personId}`, modelJoinAdvancedSetup.pure:616-619) and Person_Firm (`{employees, firm | $employees.address.city == $firm.headquarters.city}`, :626-629). Person_Address SYNTHESISES FINE on its own: ModelJoinNesting.compose collects the hop (person, profile) via collectNestedHops (ModelJoinNesting.java:179-200), finds the Person_Profile association and its ModelJoin, composes the join and returns nestedCols {person:{profile:{rank:RANK}}}, so RelationReads' nested arm (RelationReads.java:67-89) resolves `$person.profile.rank`. Person_Firm then FAILS: compose collects the hop (employees, address), finds Person_Address's ModelJoin as `nmj`, and re-rewrites Person_Address's RAW lambda body at ModelJoinNesting.java:120-124 — passing `Map.of()` as nestedCols and using the 6-arg overload that leaves `model` null. With an empty nestedCols the nested arm cannot fire, so `$person.profile.rank` falls through to the AppliedProperty recursion (RelationReads.java:134-136), whose receiver `$person.profile` hits the plain-column arm (RelationReads.java:90-92) and throws "association 'meta::...::Person_Address': $person.profile has no column binding on the Relation mapping of 'Person'". That throw is caught per-association and recorded as a poison keyed `<mapping>::Person_Firm` (MappingNormalizer.java:419-435), which is why the surfaced error blames Person_Firm while the parenthetical reason names Person_Address — precisely the observed text. compose() is simply not recursive.

**Fix**

Make ModelJoinNesting.compose recursive. In core/src/main/java/com/legend/normalizer/ModelJoinNesting.java, inside the hop loop (currently lines 106-136), before building `nCond`: call `compose(md, model, nmj, nad, endCls, nestedCls, nPair[0], nPair[1], Map.of(nPair[0], rf0, nPair[1], nRf), (var.equals(aVar) ? pipeA : pipeB), nEnd.pipeline())` to obtain a `Composed inner`. Then (a) rewrite the nested condition with `RelationReads.rewrite(nmj.lambda().body().get(last), Map.of(nPair[0], j0, nPair[1], j1), Map.of(nPair[0], rf0, nPair[1], nRf), nmj.associationName(), md, inner.nestedCols(), model)` — note both the nestedCols and the previously-dropped `model`; (b) use `inner.pipeA()` as the end-side pipeline the ColSpec join is applied to (so the `profile` join lands on the person pipeline BEFORE the address join is attached) and `inner.pipeB()` as the ColSpec body in place of the bare `nEnd.pipeline()`. Add a `Set<String> visitingAssocFqns` (or an int depth) parameter threaded through compose and thrown on re-entry, so a cyclic pair of ModelJoins (A's condition navigates B, B's navigates A) stays loud instead of recursing forever. Keep the existing one-level behaviour identical when `inner.nestedCols()` is empty, so no currently-passing ModelJoin golden changes shape. Separately (diagnostics, not correctness): MappingNormalizer.java:431 should key the poison by the association whose synthesis threw AND prefix the recorded message with the outer association's name, so the surfaced error stops blaming Person_Firm for a Person_Address shape.

**How legend-engine does it** — legend-engine .../core/pure/router/store/builder.pure:363 — the ModelJoin condition goes through `routeFunction`, so a condition that navigates an association whose OWN condition navigates a further association is just ordinary routing; the relational lowering then absorbs whatever child JoinTreeNodes the compile produced (relationalModelJoins.pure:122-130 'F. Target JTN — materialize if target has children (from compiling $that.nested.prop)' and :353-364 which merges those children into the materialised subselect). Nesting depth is not a special case in the engine — see legend-engine/docs/engineering/architecture/model-join.md:112-115 ('Capability parity with regular Pure … Nested property access on either side … are all supported').

**Risk** — Threading `inner.pipeA()` back into the outer end pipeline changes the SHAPE of the composed subselect for any mapping that already exercises one-level nesting if the recursion is not made a strict no-op at depth 0. Guard it: when the nested compose returns an empty nestedCols and unchanged pipes, emit byte-identical output to today. Also note `hops` is a `LinkedHashSet<String[]>` (ModelJoinNesting.java:58) whose elements have identity equals — the loop relies on the `nestedCols.containsKey` check at :67-68 for dedup; the recursion must not break that. TENET-2 TRAP: do not make this pass by pre-flattening the corpus mapping or by skipping the Person_Address association in the harness.

**Also unblocks** — None in the modelJoin family — grep of tests/mapping/modelJoin shows `$person.profile.…` appears only in modelJoinAdvancedSetup.pure:618, used only by NestedModelJoinWithPropertyAccess, used only by this test.

**Falsifier** — Confirm that Person_Address alone resolves: run any query against NestedModelJoinWithPropertyAccess that navigates only Person.address (never Person.firm). If THAT also fails with the same '$person.profile has no column binding' message, then Person_Address's own synthesis is failing and the one-level compose is not the boundary — this diagnosis would be wrong and the defect would be in the first-level hop resolution instead.

<details><summary>Evidence read (9 citations)</summary>

- /Users/neemsandv/legend/legend-engine/.../tests/mapping/modelJoin/modelJoinAdvancedSetup.pure:611-619 — Person_Profile ModelJoin, and Person_Address whose condition itself navigates `$person.profile.rank`
- /Users/neemsandv/legend/legend-engine/.../tests/mapping/modelJoin/modelJoinAdvancedSetup.pure:626-629 — Person_Firm's condition `$employees.address.city == $firm.headquarters.city` forces compose() to descend into Person_Address
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/normalizer/ModelJoinNesting.java:120-124 — `RelationReads.rewrite(nmj.lambda().body()..., Map.of(nPair[0], j0, nPair[1], j1), Map.of(nPair[0], rf0, nPair[1], nRf), nmj.associationName(), md, Map.of())` — nestedCols is hard-coded EMPTY and no `model` is passed for the nested condition
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/normalizer/ModelJoinNesting.java:61-145 — the compose loop; the only recursion-shaped call in it is the RelationReads.rewrite above, never compose() itself
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/normalizer/RelationReads.java:67-89 — the nested arm only fires when `nestedCols.get(var).containsKey(mid.property())`
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/normalizer/RelationReads.java:123-127 — the throw whose text ('association X: $person.profile has no c…') matches the failure detail's parenthetical
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/normalizer/MappingNormalizer.java:419-435 — the poison is keyed by the OUTER association being synthesised (Person_Firm) while carrying the INNER association's message, explaining the mismatched names in the failure detail
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/resolver/AssociationJoins.java:1166-1177 — the poison is re-surfaced verbatim in parentheses on the 'association ... is not mapped in mapping ...' throw
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/normalizer/ModelJoinNesting.java:91-98 — the alternative wall ('… navigates association X which has no ModelJoin in this mapping') is NOT the message we see, which rules out the association-lookup arm and pins the failure on the empty-nestedCols rewrite

</details>

---

## `testQualifiedPropertyInQuery`

| | |
|---|---|
| family | `tests/mapping/modelJoin` |
| sweep status | ERROR |
| **verdict** | **MISSING FEATURE** |
| effort | XS |
| confidence | medium |

**Root cause**

Identical mechanism to testSubFilter, reached through a qualified property instead of an inline filter. The query is `Firm.all()->project(~['Legal Name': x|$x.legalName, 'Employee In NYC': x|$x.employeesInCity('NYC').firstName])` (testModelJoinAdvanced.pure:1386) and `employeesInCity` is declared as `employeesInCity(cityParam: String[1]) { $this.employees->filter(emp | $emp.address.city == $cityParam) }: Person[*]` (modelJoinSimpleSetup.pure:100). Once the qualified-property call is inlined, the expression is exactly `$x.employees->filter(emp|$emp.address.city == 'NYC')`, so SyntheticHeads.parkFiltered files the closed predicate in `preds` (SyntheticHeads.java:1206-1212), AssociationJoins.associationJoin applies it through the 5-arg `CorrelatedSubselects.predFilteredPipe` (AssociationJoins.java:1129-1131), the predicate scope is built with `Registries.NONE` and `nested = true` (CorrelatedSubselects.java:1616-1633), and rewriting `$emp.address.city` throws at Substitution.java:2033-2036 with the observed "nested navigation 'address.city' inside an exists/isEmpty predicate is not supported yet". The identical message text (same head, same leaf, no `$` prefix) with QualifiedPropertyMapping — whose Person_Firm condition also reads `$employees.address.city && $employees.address.country` (modelJoinAdvancedSetup.pure:563-566), meaning the Address join is likewise already on the target pipe under prefix 'address_' — confirms the two tests reach the same throw for the same reason. Nothing here is specific to qualified properties: the qualified-property inline itself succeeded (otherwise the error would name `employeesInCity`, not `address.city`).

**Fix**

No separate fix. Apply the testSubFilter fix verbatim (AssociationJoins.java: extend `nestedAssocReads` with `collectNestedAssocReads` over each parked pred body against `targetClass`, keep each widening loop's `AssocJoin aj2` to build `Substitution.SubNav(pfx, aj2.target().rowVar(), aj2.target().bindings())`, and switch AssociationJoins.java:1129-1131 to the 6-arg `CorrelatedSubselects.predFilteredPipe(p, target, tMat.slotPrefixes(), nestedSubNavs, pred, cs.mappingFqn())`). Verify separately that the qualified-property argument `'NYC'` survives the inline as a literal in the parked predicate — the message proves the inline itself already works, but the alpha-canonical dedup in parkFiltered (SyntheticHeads.java:1200-1216) must not merge two calls with DIFFERENT arguments onto one head; if the corpus ever calls employeesInCity twice with different cities, that dedup is the next thing to check.

**How legend-engine does it** — legend-engine .../core/pure/router/store/builder.pure:305-308 — the router explicitly propagates through `func->instanceOf(QualifiedProperty)` expression sequences, so a qualified property whose body filters an association navigation is just ordinary routed Pure; the relational lowering then compiles the resulting navigation with `processValueSpecification` and attaches child JoinTreeNodes under the target (relationalModelJoins.pure:122-130).

**Risk** — Same as testSubFilter. Additionally: this test asserts only rows (no `$result->sql()` golden), so a passing fix must produce the exact multiset Apple/Alice, Apple/Charlie, Google/TDSNull — i.e. the predicate must filter the Person subselect BEFORE the LEFT join, not after, or the Google null row disappears. TENET-2 TRAP: do not implement `employeesInCity` by post-filtering the TDS in the harness.

**Also unblocks** — testSubFilter (same unit, same fix).

**Falsifier** — If the untruncated failure message for this test names `employeesInCity` (or a `#fN` synthetic) rather than `address.city`, then the qualified-property inline — not the nested predicate scope — is the boundary, and this diagnosis is wrong. The brief's text says `address.city`, so the cheapest confirmation is simply re-reading the untruncated message.

<details><summary>Evidence read (7 citations)</summary>

- /Users/neemsandv/legend/legend-engine/.../tests/mapping/modelJoin/testModelJoinAdvanced.pure:1383-1390 — the projection `x|$x.employeesInCity('NYC').firstName` against QualifiedPropertyMapping
- /Users/neemsandv/legend/legend-engine/.../tests/mapping/modelJoin/modelJoinSimpleSetup.pure:100 — `employeesInCity(cityParam: String[1]) { $this.employees->filter(emp | $emp.address.city == $cityParam) }: …Person[*];` — the body reduces to the same filtered-navigation shape as testSubFilter
- /Users/neemsandv/legend/legend-engine/.../tests/mapping/modelJoin/modelJoinAdvancedSetup.pure:558-566 — QualifiedPropertyMapping declares Person_Address as a ModelJoin and its Person_Firm condition already reads `$employees.address.city` / `.country`, so the 'address_'-prefixed join is already materialised on the Person target
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/resolver/Substitution.java:2028-2038 — the throw, in assocLeaf, when the nested scope has no AssocSub for the head
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/resolver/CorrelatedSubselects.java:1599-1636 — the 5-arg predFilteredPipe overload and the `Registries.NONE` fallback that makes the scope incapable of association navigation
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/resolver/AssociationJoins.java:1129-1131 — the association head's call site, the only predFilteredPipe caller that omits subNavs
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/resolver/SyntheticHeads.java:1206-1212 — parkFiltered routes a param-closed pred to `preds`, which applyToPipe (SyntheticHeads.java:171-190) hands to predFilteredPipe

</details>

---

## `testSubFilter`

| | |
|---|---|
| family | `tests/mapping/modelJoin` |
| sweep status | ERROR |
| **verdict** | **MISSING FEATURE** |
| effort | S |
| confidence | medium |

**Root cause**

A filter predicate parked on an association head is rewritten in a scope that carries NO association materials. The query is `Firm.all()->project(~['Legal Name': x|$x.legalName, 'NYC Employee': x|$x.employees->filter(e|$e.address.city == 'NYC').firstName])` (testModelJoinAdvanced.pure:1293) over NestedPropertyChainMapping. The predicate `e|$e.address.city == 'NYC'` is closed over its own param, so SyntheticHeads.parkFiltered files it in `preds` (SyntheticHeads.java:1206-1212) and mints a FILTERED synthetic head `employees#fN`. AssociationJoins.associationJoin materialises the Person target and then applies the parked pred via `synthetics.applyToPipe(head, tPipe, (p, pred) -> CorrelatedSubselects.predFilteredPipe(p, target, tMat.slotPrefixes(), pred, cs.mappingFqn()))` — AssociationJoins.java:1129-1131, which selects the 5-ARG overload (CorrelatedSubselects.java:1599-1604) whose `subNavs` is `Map.of()`. predFilteredPipe therefore builds the predicate's Substitution.Target with `Registries.NONE` and `nested = true` (CorrelatedSubselects.java:1616-1633). `Registries.NONE` is documented as 'Inner substitutions (exists/pred rewrites) carry NO registries: nested navigation stays loud by construction' (Substitution.java:122-125). Rewriting `$e.address.city` then reaches Substitution.assocLeaf with `a == null` and `target.nested()` true, and throws "nested navigation 'address.city' inside an exists/isEmpty predicate is not supported yet" (Substitution.java:2028-2038) — the exact observed message. Note `address` is an association end (ModelJoin Person_Address, modelJoinAdvancedSetup.pure:66-69), never a row binding, so none of the existing routes reach it: `demandedLeaves` gains the bare head 'address' (AssociationJoins.java:951-960) but `target.bindings().get("address")` is null so no slot demand results (AssociationJoins.java:993-1000), and `tailNavAliases` is likewise empty (AssociationJoins.java:1005-1015). Crucially the material ALREADY EXISTS: the mapping's Person_Firm condition reads `$employees.address.city` too, so `scanCondTargetReads` → `collectNestedAssocReads` (AssociationJoins.java:1745-1748, 1813-1830) already joins Address into the target pipe under prefix 'address_' and records it in `nestedPrefixByProp` (AssociationJoins.java:1052-1071). It is simply never handed to the predicate scope.

**Fix**

Two changes in core/src/main/java/com/legend/resolver/AssociationJoins.java, inside `associationJoin` (the method starting at line 944). (1) FEED THE PRED'S NESTED-ASSOC READS INTO THE EXISTING WIDENING: after `synthPreds` is computed (lines 950-960) and after `nestedAssocReads` is populated by `scanCondTargetReads` (line 1026), also run `for (TypedLambda sp : synthPreds) for (TypedSpec b : sp.body()) collectNestedAssocReads(b, sp.parameters().get(0), targetClass, nestedAssocReads);` so a pred that navigates an association the CONDITION does not read still gets that association joined into the target pipe. (2) HAND THE PREFIXED JOINS TO THE PREDICATE SCOPE: in the widening loop at lines 1052-1071, keep the `AssocJoin aj2` and build `nestedSubNavs.put(ne.getKey(), new Substitution.SubNav(pfx, aj2.target().rowVar(), aj2.target().bindings()))`; then change the call at lines 1129-1131 to the 6-arg overload — `CorrelatedSubselects.predFilteredPipe(p, target, tMat.slotPrefixes(), nestedSubNavs, pred, cs.mappingFqn())`. predFilteredPipe already turns each SubNav into an AssocSub keyed by property (CorrelatedSubselects.java:1613-1621), so `assocLeaf("address","city")` then resolves through `assocBindingRead` onto the prefixed column `address_CITY` on the target row. For THIS mapping change (2) alone is sufficient, because the Person_Firm condition already caused Address to be joined with prefix 'address_'; change (1) is what makes the fix general rather than accidental.

**How legend-engine does it** — legend-engine .../pureToSQLQuery/relationalModelJoins.pure:122-130 — 'F. Target JTN — materialize if target has children (from compiling $that.nested.prop)': in the engine a navigation inside any lambda over the target is compiled by the ordinary `processValueSpecification`, which simply attaches child JoinTreeNodes under the target and then materialises it as a subselect (:293-403). There is no separate 'predicate scope' with reduced capability; see also legend-engine/docs/engineering/architecture/model-join.md:112-115.

**Risk** — Passing subNavs to predFilteredPipe changes the pred's rewrite for EVERY association head that has a parked filter and a nested-assoc read, which could alter existing goldens' ON/WHERE text where the pred previously walled (it could not previously have produced rows, so behaviour changes only where the query used to error) — but change (1) also widens the target pipe with an extra LEFT join in cases that previously had none, which CAN change row counts for a to-many nested association. Restrict the pred-driven widening to hops whose association end is to-ONE (there is an existing gate for this, `toOneClassProp`, AssociationJoins.java:908-918); a to-many nested hop inside a pred must stay loud rather than explode target rows. TENET-2 TRAP: do not satisfy this test by evaluating the filter in the harness/TDS layer after execution — the filter must ride the target subselect so the LEFT join still yields the `Google, TDSNull` row.

**Also unblocks** — testQualifiedPropertyInQuery (same unit, same wall). It would very likely also unblock testRelationalSubFilter (testModelJoinAdvanced.pure:1437-1441) — but that one is tagged <<test.ToFix>> in the engine, so it must NOT be treated as a pass target; verify the engine's own expectation before counting it.

**Falsifier** — Print the stack at the throw site (Substitution.java:2035). If the frame beneath it is NavMaterializer.java:380 or :623 (both of which already pass a subNavs map) rather than CorrelatedSubselects.predFilteredPipe ← AssociationJoins.java:1130, then the predicate scope is being built somewhere else and the subNavs must be supplied at that site instead — the rest of the diagnosis (missing association material in the nested scope) would still hold, but the named call site would be wrong.

<details><summary>Evidence read (11 citations)</summary>

- /Users/neemsandv/legend/legend-engine/.../tests/mapping/modelJoin/testModelJoinAdvanced.pure:1290-1295 — the failing projection `x|$x.employees->filter(e|$e.address.city == 'NYC').firstName`
- /Users/neemsandv/legend/legend-engine/.../tests/mapping/modelJoin/modelJoinAdvancedSetup.pure:66-74 — NestedPropertyChainMapping declares Person_Address as a ModelJoin, and Person_Firm's condition itself reads `$employees.address.city` / `$employees.address.country`
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/resolver/Substitution.java:2028-2038 — `if (target.nested()) { throw new NotImplementedException("nested navigation '" + head + "." + leaf + "' inside an exists/isEmpty predicate is not supported yet"); }` inside assocLeaf when `target.assocs().get(head) == null` — the exact message, and the only variant without a leading `$` (the other two are Substitution.java:1411-1414, which prints `'$var.prop'`, and :2086-2088, reached only when an AssocSub exists but the leaf is unbound)
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/resolver/CorrelatedSubselects.java:1606-1636 — predFilteredPipe builds `navAssocs` from `subNavs` and falls back to `Substitution.Registries.NONE` when it is empty, constructing the Target with `filterPosition=true, nested=true`
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/resolver/CorrelatedSubselects.java:1599-1604 — the 5-arg overload that hard-codes `Map.of()` for subNavs
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/resolver/AssociationJoins.java:1129-1131 — the association path calls exactly that 5-arg overload, unlike AssociationJoins.java:212-214 and NavMaterializer.java:380-383 / :623-625 which all pass a subNavs map
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/resolver/AssociationJoins.java:1052-1071 — the nested-association widening loop: `AssocJoin aj2 = aggJoinMaterial(temporal, target, ne.getKey(), context, ne.getValue(), Set.of()); String pfx = ne.getKey() + "_"; … nestedPrefixByProp.put(ne.getKey(), pfx);` — the prefixed Address join is already on the pipe and `aj2.target()` carries the rowVar+bindings a SubNav needs
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/resolver/AssociationJoins.java:1813-1830 — `collectNestedAssocReads(TypedSpec n, String tgtVar, String targetClass, Map<String,Set<String>> out)`, the reusable `$var.<assocProp>.<leaf>` scanner, currently invoked only on the association CONDITION (AssociationJoins.java:1745-1748, 1773)
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/resolver/SyntheticHeads.java:1206-1212 — parkFiltered: a pred closed over its own param goes to `preds` (not `corrPreds`), which is the pool applyToPipe consumes (SyntheticHeads.java:171-190)
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/resolver/Substitution.java:122-125 — `Registries.NONE` doc: 'Inner substitutions (exists/pred rewrites) carry NO registries: nested navigation stays loud by construction'
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/resolver/Substitution.java:337-343 — `record SubNav(String prefix, String rowVar, Map<String,TypedSpec> bindings, Map<String,SubNav> children)`, the exact shape predFilteredPipe converts into AssocSubs

</details>

---

## `testToManyWithQualifierWithFilterOnJoin`

| | |
|---|---|
| family | `tests/mapping/multigrain` |
| sweep status | ERROR |
| **verdict** | **REAL DEFECT** |
| effort | L |
| confidence | high |

**Root cause**

The path is [account, incomeFunctionSplits#f0, incomeFunction, Classification, name]. The SubNav for the filtered head exists (`head subNavs=[incomeFunctionSplits#f0]`), but its children do not contain `incomeFunction`, because on AccountIncomeFunctionSplit `incomeFunction` is an EMBEDDED property mapping (a TypedNewInstance binding), not a nav-slot read — and NavMaterializer only demands a sub-step when `t.bindings().get(tail.get(0))` is itself a nav-slot read (NavMaterializer.java:133-134, then navSlotAlias at NavMaterializer.java:462 / InnerDemand.java:206-224). So the join to IF_OTHER_INFO (the embedded's own `Classification: @ifClass` slot) is never materialized. At rewrite time the walk therefore falls to `ctorTailLeaf` (Substitution.java:1193-1204 -> 3093-3122), which drills `incomeFunction` -> `Classification` and then STOPS: the drill only follows nested TypedNewInstance ctors, and `Classification`'s value is a class-typed slot read, so with `name` still unconsumed it returns null. Every later arm needs a chain key or a flat union column that does not exist, and the wall at Substitution.java:1338-1345 fires. The root-level demand scan HAS this exact embedded-then-slot drill (StoreResolver.java:1359-1378, registering the AssocSub under a DOTTED head key); the SUB-level materializer does not.

**Fix**

Port the root-level embedded-ctor drill into the sub-navigation materializer, and give the rewrite a matching dispatch. (1) NavMaterializer.navTargetMaterialized tail loop (NavMaterializer.java:129-174) and demandSlotSubTail (NavMaterializer.java:444-462): before giving up on `tail.get(0)`, unwrap toOne/otherwise and, while the binding is a TypedNewInstance containing the next tail segment, drill into it (`ni.properties().get(realHead(tail.get(i)))`) exactly as StoreResolver.java:1359-1378 does; when the drill lands on a class-typed slot read, demand THAT nav step and record the SubNav under the composed DOTTED key ('incomeFunction.Classification'), with the remaining segments as its own sub-tail. (2) Substitution.rewriteMultiHop (Substitution.java:1180-1205): when ctorTailLeaf's drill ends on a class-typed slot read with segments left, look the composed dotted key up in `sub.children()` and resolve the remaining leaf through that child SubNav's bindings and prefix — reuse the emission already written for the `hop + 1 == path.size()` case (Substitution.java:1206-1247) rather than adding a new one. Keep the loud wall for a drill that finds neither a ctor property nor a materialized child, so an unmapped tail still fails honestly.

**Risk** — Demanding an extra sub-slot adds a LEFT join inside the head's target subselect; the engine golden for this test has exactly that join (`left outer join IF_OTHER_INFO as "if_other_info_0" on ("firm_acct_if_multigrain_1".IF_NUM = "if_other_info_0".IF_CODE)`), so the shape is confirmed, but check the multigrain ~filter interaction (accountIFGrain/ifGrain) since these targets carry class-mapping filters. Tenet-2 trap: do not resolve `Classification` by name-matching the class in the harness or by flattening the embedded in the test corpus loader.

**Also unblocks** — tests/mapping/embedded: 'multi-hop navigation bondDetails.bondClassification.type through an embedded/slot head ... head subNavs=[holder]; head binding=TypedNativeCall' — same embedded-head-then-slot shape

**Falsifier** — Print `a3.subNavs().get("incomeFunctionSplits#f0").children().keySet()` at the wall. If it already contains `incomeFunction` or `Classification`, the sub-step IS materialized and the defect is in the rewrite walk alone, not in the demand scan.

<details><summary>Evidence read (7 citations)</summary>

- core/src/main/java/com/legend/resolver/Substitution.java:1338 — the wall message, printing `head subNavs=[incomeFunctionSplits#f0]` and `head binding=TypedNativeCall`
- core/src/main/java/com/legend/resolver/Substitution.java:1180-1205 — the SubNav walk: `while (sub != null && hop + 1 < path.size() && sub.children().containsKey(path.get(hop)))` then the ctor-tail branch, which only accepts a result that is a same-row column read of the sub-target
- core/src/main/java/com/legend/resolver/Substitution.java:3093-3122 — ctorTailLeaf: `if (inner instanceof TypedNewInstance ni && ni.properties().containsKey(path.get(h))) { cur = ...; h++; } else { cur = null; }` — a class-typed slot read with segments remaining yields null
- core/src/main/java/com/legend/resolver/NavMaterializer.java:129-174 — the tail loop: `TypedSpec b = t.bindings().get(SyntheticHeads.realHead(tail.get(0)))`, with the only fallback being a size-2 ASSOCIATION tail (NavMaterializer.java:135-169); an embedded ctor binding is neither drilled nor walled
- core/src/main/java/com/legend/resolver/NavMaterializer.java:444-495 — demandSlotSubTail demands a sub-step only via `InnerDemand.navSlotAlias(b, t.rowVar(), tNavSteps.keySet())`, i.e. `b` must be a direct `$row.<alias>` class-typed read
- core/src/main/java/com/legend/resolver/StoreResolver.java:1359-1378 — the ROOT-level embedded drill that this route lacks: it walks `ni.properties().get(realHead(path.get(mid)))` and advances `mid`, then registers under `headKey = String.join(".", path.subList(0, mid))`
- tests/mapping/multigrain/testMultiGrainTableMappings.pure:364-377 (corpus) — AccountIncomeFunctionSplit maps `incomeFunction ( code: ..., Classification: [myDBAccount]@ifClass )`, i.e. an embedded ctor whose inner property is a JOIN slot; Classification is an association end (SDI_IF, testMultiGrainTableMappings.pure:189-193) mapped to IF_OTHER_INFO.IF_NAME (testMultiGrainTableMappings.pure:386-389)

</details>

---

## `testAdvancedEmbeddedInMappingQuery`

| | |
|---|---|
| family | `tests/mapping/union` |
| sweep status | ERROR |
| **verdict** | **REAL DEFECT** |
| effort | M |
| confidence | high |
| **adversarial review** | **PARTIALLY_WRONG** |

**Root cause**

Mapping `union::unionMappingWithEmbeddedProperty2` maps `Firm[firm_set1]` with an EMBEDDED block `bridge ( employees[set1]:[myDB]@PersonSet1FirmSet1, employees[set2]:[myDB]@PersonSet2FirmSet1 )` — two class-typed Join sub-PMs for the SAME property `employees`, routed to the two members of Person's union (testUnion.pure:1340-1392). Two normalizer defects combine. (1) MIS-SCOPED COLLISION GUARD: JoinChainEmission.emitHopsForStructuralPm case `PropertyMapping.Embedded` (JoinChainEmission.java:119-144) throws when `p.aliasToTargetTable.containsKey(j.propertyName())` and the sub-property is class-typed to a mapped class. The FIRST `employees` sub-PM mints nav slot alias 'employees' (mintNavSlotAlias, JoinChainEmission.java:549-571) and registers it at JoinChainEmission.java:415; the SECOND sub-PM then trips the guard on the slot its own SIBLING just created. Same-named routed siblings at TOP level never hit this — they go through the plain `case PropertyMapping.Join` arm and dedup harmlessly at JoinChainEmission.java:294-299 — which is why the structurally identical `unionToUnionMapping` (Firm[firm_set1] { employees[set1], employees[set2] } at top level) passes. (2) MISSING ROUTE CLASSIFICATION: UnionSynthesis.classifyUnionRoutes (UnionSynthesis.java:197-205) only iterates `rcm.propertyMappings()`, never descending into Embedded/Otherwise/Inline bodies, so `p.unionRoutes` has no entry for 'employees' and even with the guard removed the emitted navigate would carry ONE member's join instead of the OR over both. The thrown NotImplementedException is caught by the per-class fault isolation in MappingNormalizer.java:326-340, recorded on `model.mappingPoisons` under `<mapping>::Firm`, and re-surfaces at query time as the observed 'class Firm is not mapped in mapping ... (<poison>)' from ClassSources.java:622-625. Note the same guard also poisons Person in this mapping (Person[set1] carries `bridge(firm[firm_set1], firm[firm_set2])`).

**Fix**

Two coordinated changes in core/src/main/java/com/legend/normalizer.

(A) UnionSynthesis.classifyUnionRoutes (UnionSynthesis.java:197-301): replace the flat scan of `rcm.propertyMappings()` with a recursive walk that threads the OWNER CLASS. Collect pairs (ownerClassFqn, PropertyMapping.Join with targetSetId != null): for `Embedded emb`, recurse into emb.propertyMappings() with owner = MappingNormalizer.findPropertyTypeDeep(owner, emb.propertyName(), model) as NameRef.name(); for `OtherwiseEmbedded oe`, recurse into oe.embedded() the same way; for `InlineEmbedded ie`, recurse into the referenced set's PMs with owner = referenced.className() (mirroring the owner derivations already used in JoinChainEmission.java:119-185); for `LocalProperty lp`, recurse into lp.body(). Then group by property name as today, but resolve `targetClass` via findPropertyTypeDeep on the PAIR's owner class, not rcm.className(). Everything downstream (memberOrdinalOf, routes list, p.unionRoutes.put) is unchanged.

(B) JoinChainEmission: make the collision guard owner-aware instead of name-only. Add to Pipeline a `final Map<String,String> navSlotOwner = new LinkedHashMap<>();` recorded in mintNavSlotAlias (JoinChainEmission.java:549-571) alongside navSlotByProp — key = property name, value = the ownerClassFqn the slot was minted under (pass ownerClassFqn into mintNavSlotAlias; emitJoinChain already has it). Then in all three guards (Embedded at :128-139, OtherwiseEmbedded at :103-113, InlineEmbedded at :171-180) fire ONLY when the recorded owner differs from the current level's owner class, i.e. `String prior = p.navSlotOwner.get(j.propertyName()); if (prior != null && !prior.equals(<this level's owner fqn>)) throw ...`. Same-owner same-name siblings (the routed union members) then fall through to emitJoinChain, whose existing dedup at :294-299 emits ONE routed navigate carrying the OR over all p.unionRoutes entries — the same code path the passing top-level `unionToUnionMapping` uses.

No change is needed in materializeEmbedded (MappingNormalizer.java:2668-2717): both same-named sub-PMs produce the identical ctor field `$row.employees`, and the LinkedHashMap put is idempotent.

**How legend-engine does it** — The engine treats an embedded set implementation's property mappings exactly like a root set's for union routing. `meta::relational::mapping::...` fetches them with an explicit Embedded arm: relationalMappingExecution.pure:804-805 (`e:EmbeddedSetImplementation[1]|$e->meta::pure::mapping::propertyMappingsByPropertyName(...)`) and feeds the resulting PropertyMapping[*] to the SAME `processPropertyMapping` (:806). The union-route dispatch resolves an embedded owner explicitly: pureToSQLQuery.pure:2294-2301, `extractParentMappingId = {rpm | $state->getClassMappingById($rpm.sourceSetImplementationId)->match([ r:RootRelationalInstanceSetImplementation[1] | $r.id, e:EmbeddedRelationalInstanceSetImplementation[1] | $e.setMappingOwner.id, ...])}` — used at :2306/:2316 to pick the per-member routed PMs and null-join the un-routed members. Files: /Users/neemsandv/legend/legend-engine/legend-engine-xts-relationalStore/legend-engine-xt-relationalStore-generation/legend-engine-xt-relationalStore-pure/legend-engine-xt-relationalStore-core-pure/src/main/resources/core_relational/relational/relationalMappingExecution.pure and .../pureToSQLQuery/pureToSQLQuery.pure

**⚠ Correction from adversarial review** — Do not carry 'no separate fix, see (A)+(B)' into a work plan as XS. State the dependency explicitly: this test is blocked by the SAME JoinChainEmission Embedded-arm guard (:127-139) and the same classifyUnionRoutes top-level-only scan (UnionSynthesis:198-201) as the base-mapping testAdvancedEmbeddedInMappingQuery, and it should be scheduled as an acceptance check on that item, not as its own task. Drop the 'verify the owner-class key is the CLASS fqn' step — unionRoutes is keyed by property name (Pipeline.java:52), so there is no owner key to get wrong; and drop the memberOrdinalOf extends-lineage dependency claim: `employees` targets simple::Person, whose union members set1/set2 are matched by memberOrdinalOf's direct indexOf (:161) because the include brings that root in. The correct extends-specific acceptance check is instead: confirm that flattenExtends (MappingNormalizer:736-742) has already merged the inherited embedded PM into my_firm_set1's propertyMappings BEFORE classifyUnionRoutes runs on that rcm — it does today, which is why (B) as scoped to rcm.propertyMappings() will reach it.

<details><summary>Adversarial review notes (PARTIALLY_WRONG)</summary>

The MECHANISM is confirmed, and I verified one link the diagnosis only asserted: MappingNormalizer.flattenExtends (:700-770) merges the parent set's PMs into the child rcm keyed by UnionSynthesis.pmIdentity = (name, targetSetId), so the empty-bodied `Firm[my_firm_set1] extends [firm_set1]` really does carry the inherited `bridge(employees[set1], employees[set2])` embedded block into the extend mapping's own propertyMappings list — which is why the guard fires and why a fix on rcm.propertyMappings() would see it. JoinChainEmission.java:119-144's Embedded arm throws the cited literal, and the sweep details for the two testAdvancedEmbeddedInMappingQuery rows differ only in the FQNs (`union::extend::Firm` / `union::extend::unionMappingWithEmbeddedProperty2` vs `model::simple::Firm` / `union::unionMappingWithEmbeddedProperty2`), exactly as claimed. testUnionWithExtends.pure:588-616 and :184-193 are line-exact, and extend::Firm/Person are subclasses declared at :297-315. extend::testSimpleQueryUnionToUnion is indeed not in the failure set. BUT the FIX is defective as a work-plan item on three counts. (1) It is not self-contained: 'changes (A) and (B) described for the sibling test' are not in V07 at all — the sibling U45 diagnosis lives in another batch — so this entry cannot be executed or effort-estimated on its own, and XS is misleading: the real work ((A) rescoping the JoinChainEmission collision guard, (B) making UnionSynthesis.classifyUnionRoutes descend into Embedded/Otherwise/Inline bodies) is M-sized in two normalizer files. (2) The one concrete instruction it does give is fabricated. It says to 'verify that the owner-class key used in (B) is the CLASS fqn (e.g. union::extend::Bridge / the inherited Bridge), not the set id'. There is no owner-class key: Pipeline.java:52 declares `final Map<String, List<UnionSynthesis.UnionRoute>> unionRoutes` keyed by PROPERTY NAME, per-Pipeline, and UnionSynthesis.java:299 does `p.unionRoutes.put(prop, routes)`. Moreover `union::extend::Bridge` does not exist — the extend package declares only Person, Firm, Application and Address (testUnionWithExtends.pure:297-315); Bridge is meta::relational::tests::model::simple::Bridge (simpleTestModel.pure:688). (3) The supporting claim that this variant 'additionally relies on memberOrdinalOf's extends-lineage walk to map targetSetId set1/set2 onto members mySet1/mySet2' is very likely wrong: the routed property is `employees` on simple::Bridge/simple::Firm, whose target class is simple::Person, and simple::Person's union root (`*Person : Operation union(set1,set2)`) comes in through the include, so memberOrdinalOf's DIRECT `memberIds.indexOf(setId)` at :161 matches and the extends walk at :165-175 is never needed. The extend mapping's `*Person : Operation union(mySet1,mySet2)` is for the distinct class extend::Person and does not shadow it. The extends walk is real code but is not the load-bearing path here, so the falsifier built on it ('if the extend one persists, the provenance key is wrong') would mis-diagnose.

</details>

**Citation issues found in review** — 'union::extend::Bridge' in the fix text does not exist (extend declares only Person/Firm/Application/Address at testUnionWithExtends.pure:297-315; Bridge is simpleTestModel.pure:688). The 'owner-class key used in (B)' does not exist either: Pipeline.java:52 / UnionSynthesis.java:299 key unionRoutes by property NAME, per-Pipeline. UnionSynthesis.java:165 is the loop head; the extendsSetId reads are :168-173 (minor). All other citations resolved exactly.

**Risk** — Do NOT simply delete the guard: a genuine cross-level same-name collision (e.g. a top-level `firm` Join PM plus an embedded `firm` Join PM with a DIFFERENT join) would then silently dedup onto the first slot and produce wrong rows — strictly worse than the wall (TENETS: a loud wall beats wrong rows). The owner-provenance key preserves that wall. Change (A) widens the poison surface slightly: an embedded routed property whose routes classify as unsupported now adds the property to p.droppedRoutedProps, which suppresses its ctor field (MappingNormalizer.java:2251) — that suppression is keyed by property NAME on a flat pipeline, so an embedded drop could also silence a same-named top-level property; gate the drop by the same owner key if the two ever coexist. Tenet-2 trap: this must not be papered over by teaching the harness to skip the poisoned mapping — the mapping is legal Legend and the engine compiles it.

**Also unblocks** — lineage/scanRelations/scanRelationsTests.pure:303 testUnionToUnion — its failure detail in the sweep is byte-identical (same poison on unionMappingWithEmbeddedProperty2). testDataGeneration/tests/testDataGeneration.pure:471 also binds this mapping and is a candidate.

**Falsifier** — Add a temporary println at the Embedded guard (JoinChainEmission.java:132) printing ownerClassFqn, nr.name(), j.propertyName(), j.targetSetId() and p.aliasToTargetTable.keySet(), then synthesize unionMappingWithEmbeddedProperty2. If the pre-existing 'employees' slot was minted under an owner class OTHER than Bridge (i.e. a real cross-level collision) my diagnosis is wrong. Cheaper still: if `p.unionRoutes` already contains an entry for 'employees' at that moment, then classifyUnionRoutes does descend and change (A) is unnecessary.

<details><summary>Evidence read (13 citations)</summary>

- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/normalizer/JoinChainEmission.java:128 — `if (sub instanceof PropertyMapping.Join j && p.aliasToTargetTable.containsKey(j.propertyName()) && classTypedTargetIfMapped(nr.name(), ...) != null)` then throw 'Embedded sub-PM ... collides with an existing pipeline slot of the same name' (message literal at :133-138, exactly the observed text)
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/normalizer/JoinChainEmission.java:415 — `p.aliasToTargetTable.put(slotAlias, targetTable)`: the first sibling's emission is what populates the map the guard then reads
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/normalizer/JoinChainEmission.java:294 — the nav arm's own dedup (`if (p.aliasToTargetTable.containsKey(navAlias)) { ...; continue; }`), i.e. a second same-named routed PM is ALREADY handled correctly at top level
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/normalizer/JoinChainEmission.java:549 — mintNavSlotAlias memoizes by property NAME only (`p.navSlotByProp`), no owner-class provenance
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/normalizer/UnionSynthesis.java:200 — `for (PropertyMapping pm : rcm.propertyMappings()) { if (pm instanceof PropertyMapping.Join j && j.targetSetId() != null) ... }` — top-level only, no descent into Embedded
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/normalizer/UnionSynthesis.java:299 — `p.unionRoutes.put(prop, routes)` is the only writer of the routed-navigation table
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/normalizer/MappingNormalizer.java:2116 — `UnionSynthesis.classifyUnionRoutes(md, rcm, model, p)` runs before the Pass-1 emission loop at :2125-2131, so routes must be complete before emitJoinChain reads them at JoinChainEmission.java:382
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/normalizer/MappingNormalizer.java:338 — `model.mappingPoisons.put(md.qualifiedName() + "::" + cm.className(), e.getMessage())` in the per-class NotImplementedException catch
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/resolver/ClassSources.java:622 — `throw new MappingResolutionException("class '" + classFqn + "' is not mapped in mapping '" + mappingFqn + "'" + ctx.mappingPoison(...).map(r -> " (" + r + ")")...)`: the observed message is poison + wrapper
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/model/MappingFromProtocol.java:573 — embedded sub-PMs recurse through the same `propertyMapping(...)` and reach `bodyOf(..., rel.target())` at :618, so `employees[set1]` inside an embedded block IS a `PropertyMapping.Join` with targetSetId (record ctor at :656)
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/normalizer/MappingNormalizer.java:3474 — findPropertyTypeDeep falls back to `model.findAssociationProperty`, so Bridge.employees (an association end) resolves to Person and classTypedTargetIfMapped returns non-null — the guard's second conjunct is satisfied
- /Users/neemsandv/legend/legend-engine/.../core_relational/relational/tests/mapping/union/testUnion.pure:1340 — the mapping under test; the `bridge(...)` embedded blocks with two routed `employees`/`firm` sub-PMs are at :1375-1391
- /Users/neemsandv/legend/legend-engine/.../core_relational/relational/tests/testModel/simpleTestModel.pure:676 — Association BridgeAsso1 { bridge : Bridge[0..1]; employees : Person[*]; } and BridgeAsso2 at :682, giving Firm.bridge : Bridge and Bridge.employees : Person[*]

</details>

---

## `testAdvancedEmbeddedInMappingQuery`

| | |
|---|---|
| family | `tests/mapping/union` |
| sweep status | ERROR |
| **verdict** | **REAL DEFECT** |
| effort | M (revised up from XS by adversarial review) |
| confidence | high |
| **adversarial review** | **PARTIALLY_WRONG** |

**Root cause**

Identical mechanism to the entry above, one mapping level up. `union::extend::unionMappingWithEmbeddedProperty2` (testUnionWithExtends.pure:588-616) `include`s `union::unionMappingWithEmbeddedProperty2` and re-declares the union over extends-sets: `Firm[my_firm_set1] extends [firm_set1]` with an EMPTY body, `*Firm : Operation union(my_firm_set1, my_firm_set2)`. The inherited `bridge ( employees[set1], employees[set2] )` embedded block is re-emitted under this mapping's synthesis, and the SAME guard at JoinChainEmission.java:128-139 fires on the second `employees` sibling — the observed poison text is byte-identical apart from the class/mapping FQNs (`union::extend::Firm`), which is itself the proof that the inherited PMs reached the guard. Route classification here additionally relies on UnionSynthesis.memberOrdinalOf's extends-lineage walk (UnionSynthesis.java:158-177) to map targetSetId `set1`/`set2` onto members `mySet1`/`mySet2`; that path already exists and is exercised by the passing extend::testSimpleQueryUnionToUnion.

**Fix**

No separate fix. Changes (A) and (B) described for the sibling test resolve this one too, because the failing code path is the same guard on the same inherited embedded block. Verify only that the owner-class key used in (B) is the CLASS fqn (e.g. `union::extend::Bridge` / the inherited Bridge), not the set id, so an inherited block and its parent block agree on provenance rather than colliding.

**How legend-engine does it** — Same as the entry above: pureToSQLQuery.pure:2294-2301 (extractParentMappingId resolves an EmbeddedRelationalInstanceSetImplementation to `$e.setMappingOwner.id`) and its use at :2316 with `allSuperSetImplementationIds` — the engine explicitly follows the EXTENDS lineage when matching routed PMs to union members, which is the extends variant of this same query.

**⚠ Correction from adversarial review** — Do not carry 'no separate fix, see (A)+(B)' into a work plan as XS. State the dependency explicitly: this test is blocked by the SAME JoinChainEmission Embedded-arm guard (:127-139) and the same classifyUnionRoutes top-level-only scan (UnionSynthesis:198-201) as the base-mapping testAdvancedEmbeddedInMappingQuery, and it should be scheduled as an acceptance check on that item, not as its own task. Drop the 'verify the owner-class key is the CLASS fqn' step — unionRoutes is keyed by property name (Pipeline.java:52), so there is no owner key to get wrong; and drop the memberOrdinalOf extends-lineage dependency claim: `employees` targets simple::Person, whose union members set1/set2 are matched by memberOrdinalOf's direct indexOf (:161) because the include brings that root in. The correct extends-specific acceptance check is instead: confirm that flattenExtends (MappingNormalizer:736-742) has already merged the inherited embedded PM into my_firm_set1's propertyMappings BEFORE classifyUnionRoutes runs on that rcm — it does today, which is why (B) as scoped to rcm.propertyMappings() will reach it.

<details><summary>Adversarial review notes (PARTIALLY_WRONG)</summary>

The MECHANISM is confirmed, and I verified one link the diagnosis only asserted: MappingNormalizer.flattenExtends (:700-770) merges the parent set's PMs into the child rcm keyed by UnionSynthesis.pmIdentity = (name, targetSetId), so the empty-bodied `Firm[my_firm_set1] extends [firm_set1]` really does carry the inherited `bridge(employees[set1], employees[set2])` embedded block into the extend mapping's own propertyMappings list — which is why the guard fires and why a fix on rcm.propertyMappings() would see it. JoinChainEmission.java:119-144's Embedded arm throws the cited literal, and the sweep details for the two testAdvancedEmbeddedInMappingQuery rows differ only in the FQNs (`union::extend::Firm` / `union::extend::unionMappingWithEmbeddedProperty2` vs `model::simple::Firm` / `union::unionMappingWithEmbeddedProperty2`), exactly as claimed. testUnionWithExtends.pure:588-616 and :184-193 are line-exact, and extend::Firm/Person are subclasses declared at :297-315. extend::testSimpleQueryUnionToUnion is indeed not in the failure set. BUT the FIX is defective as a work-plan item on three counts. (1) It is not self-contained: 'changes (A) and (B) described for the sibling test' are not in V07 at all — the sibling U45 diagnosis lives in another batch — so this entry cannot be executed or effort-estimated on its own, and XS is misleading: the real work ((A) rescoping the JoinChainEmission collision guard, (B) making UnionSynthesis.classifyUnionRoutes descend into Embedded/Otherwise/Inline bodies) is M-sized in two normalizer files. (2) The one concrete instruction it does give is fabricated. It says to 'verify that the owner-class key used in (B) is the CLASS fqn (e.g. union::extend::Bridge / the inherited Bridge), not the set id'. There is no owner-class key: Pipeline.java:52 declares `final Map<String, List<UnionSynthesis.UnionRoute>> unionRoutes` keyed by PROPERTY NAME, per-Pipeline, and UnionSynthesis.java:299 does `p.unionRoutes.put(prop, routes)`. Moreover `union::extend::Bridge` does not exist — the extend package declares only Person, Firm, Application and Address (testUnionWithExtends.pure:297-315); Bridge is meta::relational::tests::model::simple::Bridge (simpleTestModel.pure:688). (3) The supporting claim that this variant 'additionally relies on memberOrdinalOf's extends-lineage walk to map targetSetId set1/set2 onto members mySet1/mySet2' is very likely wrong: the routed property is `employees` on simple::Bridge/simple::Firm, whose target class is simple::Person, and simple::Person's union root (`*Person : Operation union(set1,set2)`) comes in through the include, so memberOrdinalOf's DIRECT `memberIds.indexOf(setId)` at :161 matches and the extends walk at :165-175 is never needed. The extend mapping's `*Person : Operation union(mySet1,mySet2)` is for the distinct class extend::Person and does not shadow it. The extends walk is real code but is not the load-bearing path here, so the falsifier built on it ('if the extend one persists, the provenance key is wrong') would mis-diagnose.

</details>

**Citation issues found in review** — 'union::extend::Bridge' in the fix text does not exist (extend declares only Person/Firm/Application/Address at testUnionWithExtends.pure:297-315; Bridge is simpleTestModel.pure:688). The 'owner-class key used in (B)' does not exist either: Pipeline.java:52 / UnionSynthesis.java:299 key unionRoutes by property NAME, per-Pipeline. UnionSynthesis.java:165 is the loop head; the extendsSetId reads are :168-173 (minor). All other citations resolved exactly.

**Risk** — Same as the sibling entry. Extra care: the extend mapping re-synthesizes classes from the include closure (MappingNormalizer.java:384-411); if the owner key is derived from a set id rather than a class fqn, the re-synthesis could see two different owners for what is one block and keep the wall.

**Falsifier** — If applying fixes (A)+(B) clears the `union::unionMappingWithEmbeddedProperty2` poison but the `union::extend::` one persists with the SAME message, then the extends re-synthesis threads a different owner identity and the provenance key is wrong — that is the one cheap observation that separates the two.

<details><summary>Evidence read (4 citations)</summary>

- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/normalizer/JoinChainEmission.java:133 — the message literal 'Embedded sub-PM ... collides with an existing pipeline slot of the same name; distinct same-named class-typed joins across embedded levels are a roadmap feature' matches both sweep details verbatim
- /Users/neemsandv/legend/legend-engine/.../tests/mapping/union/testUnionWithExtends.pure:588 — `Mapping meta::relational::tests::mapping::union::extend::unionMappingWithEmbeddedProperty2 ( include meta::relational::tests::mapping::union::unionMappingWithEmbeddedProperty2 ... Firm[my_firm_set1] extends [firm_set1] : Relational { } ...)`
- /Users/neemsandv/legend/legend-engine/.../tests/mapping/union/testUnionWithExtends.pure:184 — the extend-package test body, same query `Firm.all()->filter(f|$f.bridge.employees->exists(e|$e.lastName == 'Wright'))`
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/normalizer/UnionSynthesis.java:165 — memberOrdinalOf walks `r.extendsSetId()` chains, so routes naming ancestor sets (set1/set2) resolve to member ordinals of mySet1/mySet2

</details>

---

## `testUnionToUnionJoinSequenceWithMultipleChildrenInUnionSourceTree`

| | |
|---|---|
| family | `tests/mapping/union` |
| sweep status | ERROR |
| **verdict** | **REAL DEFECT** |
| effort | M (revised up from S by adversarial review) |
| confidence | high |
| **adversarial review** | **PARTIALLY_WRONG** |

**Root cause**

Resolver demand/materialize mismatch in the class-flatten hop. The query is `Person.all()->filter(p|$p.firm.legalName == $p.extraInformation)->map(p|$p.firm)->project(f|$f.legalName,'name')` over `unionToUnionMapping2` (testUnion.pure:1521-1550), where Person[set1] carries BOTH a routed class-typed nav slot `firm` (firm[firm_set1]/firm[firm_set2]) AND a JoinTerminalColumn `extraInformation : [myDB]@PersonSet1PersonAdditional | PersonAdditional.extrainfo`, whose join chain is hoisted as physical join slot 'PersonSet1PersonAdditional'. The chain walk (StoreResolver.java:2664-2675) records flattenHops=[firm] and puts the filter in flatSegs (belowOps); flattenSource routes to flattenNavSlot (StoreResolver.java:856-860). Inside flattenNavSlot, FlattenOps.splitBelowOps computes the filter's consumed paths {[firm,legalName],[extraInformation]}; because [firm,...] passes through the hop head, the WHOLE op is classified `hoisted` (FlattenOps.java:64-85) and its non-hop path [extraInformation] is DISCARDED (spliceFull is populated only for non-colliding ops). flattenNavSlot then materializes the source pipeline with an EMPTY join-slot demand — `Pipelines.materialize(spliced, java.util.Set.of(), java.util.Set.of(alias), ...)` at StoreResolver.java:724-726 — so walkJoinSlot strips 'PersonSet1PersonAdditional' (Pipelines.java:405-406). The hoisted filter is only rewritten AFTERWARDS (StoreResolver.java:796-801) through `substitution(src, mf, ...)`, which puts `m.stripped()` on the Substitution target (StoreResolver.java:3433); renameRowVar (Substitution.java:3155-3159) calls Pipelines.rewriteRowReads, which hits the stripped-slot branch and throws the exact observed message (Pipelines.java:1208-1212). So: the demand scan for the SOURCE side of a nav-slot flatten ignores the hoisted ops' non-hop reads, while the rewrite still performs them.

**Fix**

In core/src/main/java/com/legend/resolver: (1) extend FlattenOps.BelowSplit (FlattenOps.java:33-36) with `java.util.Set<List<String>> hoistedFull` and populate it in the `if (collides)` arm at FlattenOps.java:80 exactly as spliceFull is populated in the else arm. (2) In StoreResolver.flattenNavSlot, before the materialize call at StoreResolver.java:724, compute the source-side slot demand: `Set<String> srcSlots = Pipelines.slotAliases(spliced); Set<String> srcDemand = new LinkedHashSet<>(); for (List<String> pp : bsp.hoistedFull()) { TypedSpec hb = src.bindings().get(SyntheticHeads.realHead(pp.get(0))); if (hb != null) { CorrelatedSubselects.collectAliasReads(hb, src.rowVar(), srcSlots, srcDemand); } } srcDemand = Pipelines.closeOverConditions(spliced, srcDemand);` and pass `srcDemand` in place of `java.util.Set.of()` as the second argument. Pipelines.slotAliases collects only TypedJoinSlot aliases (Pipelines.java:1380-1387), so the nav head 'firm' cannot leak into the join-slot demand. Once demanded, walkJoinSlot converts the slot to a real prefixed join, `m.slotPrefixes()` gains the entry, and rewriteRowReads' two-level arm (Pipelines.java:1192-1199) turns `$row.PersonSet1PersonAdditional.extrainfo` into the prefixed flat column — the shape every other demanded-slot read already uses. COMPANION SITE (same defect class, not proven by this test): StoreResolver.belowScope at :3318-3319 materializes with `Set.of()` while its rewriter `bm` carries that stripped set, so a NON-colliding below-op reading a slot-backed property would throw the same way; it already has `bsp.spliceFull()` in hand at :3315 and should compute its demand the same way.

**How legend-engine does it** — The engine's golden for this very test (testUnion.pure:240) shows the join tree retaining PersonAdditional for the filter even though the final projection reads only the Firm union: `... from PersonSet1 as "root" left outer join (FirmSet1 union all FirmSet2) as "unionalias_0" on (...) left outer join PersonAdditional as "personadditional_0" on ("root".ID = "personadditional_0".ID) left outer join (...) as "unionalias_1" on (...) where "unionalias_0"."FirmSet1name_FirmSet2name" is not distinct from "personadditional_0".extrainfo`. That is the semantics the demand scan must reproduce: a join a surviving filter reads through is never cancelled.

**⚠ Correction from adversarial review** — The two code edits as written are fine and compile-plausible (Pipelines.slotAliases/closeOverConditions and CorrelatedSubselects.collectAliasReads are all package-visible from StoreResolver; the hoistedFull loop cannot leak the nav head 'firm' into join-slot demand because slotAliases returns TypedJoinSlot aliases only). What is missing is the second half: with the hoisted filter dispatching through hopAssocs (StoreResolver.java:783-791, prefix = the hop's own slot prefix), legend-lite emits ONE union subselect where the engine golden emits two (unionalias_0 for the filter's firm.legalName, unionalias_1 for the map hop). Expect ERROR -> FAIL on assertSameSQL, and scope a follow-on for the filter-vs-hop join split before calling this test done.

<details><summary>Adversarial review notes (PARTIALLY_WRONG)</summary>

MECHANISM HOLDS — I could not break it. Every cited line resolves and says what is claimed: Pipelines.java:1208-1212 is the sole emitter of the exact observed text ('resolver bug: undemanded navigation — consumed expression reads STRIPPED join slot ...'), and docs/RELATIONAL_CORPUS.md:1388 shows that text with slot name 'PersonSet1PersonAdditional'. FlattenOps.java:80 is verbatim `if (collides) { hoisted.add(op); } else { spliceOps.add(op); spliceFull.addAll(opPaths); }` — colliding ops' opPaths are indeed discarded, and BelowSplit (33-36) has no hoistedFull. StoreResolver.java:724-726 hard-codes `java.util.Set.of()` as the join-slot demand with only `Set.of(alias)` as nav demand; :796-801 applies the hoisted ops afterwards through `substitution(src, mf, hopAssocs, ...)`; :3433 hands `m.stripped(), m.slotPrefixes()` to the Substitution.Target; Substitution.java:3155-3159 renameRowVar calls Pipelines.rewriteRowReads with those. Pipelines.java:405 is the JOIN-CANCELLED arm; :362 collectFilterDemand only rescues pipeline TypedFilter reads. Chain walk at :2664-2675 does push 'firm' onto flattenHops and the query filter into flatSegs, and :2728-2733 passes flatSegs.get(i) as belowOps. I also verified the routing premise the diagnosis leans on: JoinChainEmission.java:398 emits LEGACY_NAVIGATE with slotAlias = the property name for a class-typed join PM (mintNavSlotAlias :543-550), so `firm` really is a nav-slot alias and flattenSource takes the flattenNavSlot arm at :856-860; InnerDemand.navSlotAlias (:209-225) matches exactly that shape. Two-level `$row.SLOT.col` reads do reach the throw (the outer node recurses via the TypedPropertyAccess arm at Pipelines:1217 into the one-level inner read, which hits the stripped branch). Corpus citations are exact: testUnion.pure:236 is the test, :1528 is `extraInformation : [myDB]@PersonSet1PersonAdditional | PersonAdditional.extrainfo`, :842 is `Join PersonSet1PersonAdditional(PersonSet1.ID = PersonAdditional.ID)`, and the golden at :238 does keep `left outer join PersonAdditional as "personadditional_0"`.

WHY NOT CONFIRMED: the fix removes the throw but almost certainly does not green the test, and the diagnosis does not say so. The golden SQL contains THREE joins — two SEPARATE union subselects (`unionalias_0`, projecting only FirmSet1name_FirmSet2name, serving the filter's `$p.firm.legalName`; and `unionalias_1`, projecting pk_0_0/pk_0_1 as well, serving the `map(p|$p.firm)` hop) plus personadditional_0. But legend-lite's hoisting deliberately REUSES the single hop join: StoreResolver.java:783-791 builds hopAssocs for every hopHead with `new Substitution.AssocSub(prefix, ...)` where `prefix = m.slotPrefixes().get(alias)` is the hop's own prefix, so the hoisted filter's `$p.firm.legalName` resolves onto the hop's union subselect. After the fix the emitted SQL would have two joins (one union subselect + PersonAdditional), not three, and assertSameSQL is a literal text compare (docs show alias-level text diffs reported as FAIL, e.g. testRestrictDistinct_NoOptimization_WindowColumns). So the realistic outcome is ERROR -> FAIL, and closing the test needs the second, un-scoped change of not collapsing the filter's navigation into the hop join. That is a shared-path/golden-alignment change, so effort S is understated. I could not run the test (build commands are forbidden here), so this is inference from the emission code plus the golden text, not execution.

</details>

**Risk** — Demanding more slots emits additional LEFT JOINs, but only where a surviving op actually reads them, so the change is scoped to queries that today crash. Follow-on caveat: this test ALSO asserts golden SQL. legend-lite reuses ONE materialized `firm` hop for both the filter and the mapped projection, while the engine emits TWO Firm-union joins (unionalias_0 and unionalias_1) — after the fix the text will still differ, so the test passes only via the harness's execution-equivalence upgrade (EngineTestExecutor.sqlTextVerify at :1006-1013 runs the golden on H2 and compares rows; if H2 cannot replay, the result is a GOLDEN_TEXT-only failure rather than a pass). Rows should be empty on both sides (extrainfo is 'Not Available', firm names are 'Firm X'/'Firm A'), satisfying the test's first assert. Tenet-2 trap: do not widen the demand by unconditionally demanding every slot at flatten time (that would add joins the engine elides elsewhere), and do not soften Pipelines.java:1208 into a silent fallback — that wall is correct and is what surfaced this bug.

**Also unblocks** — No other test in the sweep carries the 'STRIPPED join slot' message (checked across units.json), so no confirmed co-beneficiaries; the belowScope companion site would harden the splice route symmetrically.

**Falsifier** — Print `m.stripped()` and `bsp.hoisted()` immediately after StoreResolver.java:724 while resolving this query. If stripped does not contain 'PersonSet1PersonAdditional' at that point, the throw originates from a different materialize call site (the other candidates are AssociationJoins.java:185/1032, CorrelatedSubselects.java:452, NavMaterializer.java:293) and this diagnosis is wrong.

<details><summary>Evidence read (11 citations)</summary>

- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/resolver/Pipelines.java:1209 — `throw new IllegalStateException("resolver bug: undemanded navigation —" + " consumed expression reads STRIPPED join slot '" + pa.property() + "' (the demand scan and the rewrite disagreed)")` — the sole emitter of the observed text
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/resolver/StoreResolver.java:724 — `Pipelines.materialize(spliced, java.util.Set.of(), java.util.Set.of(alias), src.classFqn(), ...)`: the source's join-slot demand is hard-coded empty; only the NAV alias is demanded
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/resolver/StoreResolver.java:796 — the hoisted ops are applied after materialization via `substitution(src, mf, hopAssocs, ...)` where `mf` is that same Materialized
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/resolver/StoreResolver.java:3433 — `m.stripped(), m.slotPrefixes()` are handed to the Substitution.Target row scope
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/resolver/Substitution.java:3156 — `Pipelines.rewriteRowReads(n, target.sourceRowVar(), target.slotPrefixes(), target.strippedSlots(), ...)`
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/resolver/FlattenOps.java:80 — `if (collides) { hoisted.add(op); } else { spliceOps.add(op); spliceFull.addAll(opPaths); }` — hoisted ops' opPaths are never retained
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/resolver/Pipelines.java:405 — `if (!demanded.contains(js.alias())) { stripped.add(js.alias()); ... }` (walkJoinSlot's JOIN CANCELLED arm)
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/resolver/Pipelines.java:362 — collectFilterDemand only rescues slots read inside PIPELINE TypedFilters (mapping ~filter); this class mapping has no ~filter, so nothing rescues the query filter's reads
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/resolver/StoreResolver.java:2669 — the chain walk pushes `firm` onto flattenHops and the filter into flatSegs, and :2728-2733 passes flatSegs.get(i) as belowOps
- /Users/neemsandv/legend/legend-engine/.../tests/mapping/union/testUnion.pure:1528 — `extraInformation : [myDB]@PersonSet1PersonAdditional | PersonAdditional.extrainfo` in Person[set1] of unionToUnionMapping2; the join itself is declared at testUnion.pure:842 `Join PersonSet1PersonAdditional(PersonSet1.ID = PersonAdditional.ID)` — hence the slot name in the message
- /Users/neemsandv/legend/legend-engine/.../tests/mapping/union/testUnion.pure:236 — the test body and its golden SQL at :240, which does keep `left outer join PersonAdditional as "personadditional_0"` alongside the two Firm-union joins

</details>

---

## `testWithParameterToClassNestedSelect`

| | |
|---|---|
| family | `tests/query` |
| sweep status | ERROR |
| **verdict** | **MISSING FEATURE** |
| effort | S |
| confidence | high |

**Root cause**

The test's 4th statement is `assertSize(0, execute(|Product.all()->filter(p|$p.synonymByType(ProductSynonymType.CUSIP).name == 'ISIN2'), ...)->size())`. The harness's assertSize arm (EngineTestExecutor.java:1938-1957) evaluates arg1 on its own via evalScalar -> eval -> evalSpliced (EngineTestExecutor.java:2665-2677), which wraps the single expression `size(execute(...))` as a lambda body and hands it to Compiler.executeResolved -> StatementExecutor.executeStatements. There, the statement's preRoot is the `size` TypedNativeCall, not an execute call, so the execute-in-result-position arm (StatementExecutor.java:329-334) does not fire, and the G-1/2 splice hook has NO arm for an INLINE execute under size: spliceHook only handles (a) `$r->size()` where the argument is a TypedVariable already bound in execFrames (StatementExecutor.java:2534-2543, 'ONE TDS value, never the row count' -> constant 1) and (b) an inline `execute(...).values` (spliceValuesRead, StatementExecutor.java:2705-2723). With no arm, the raw `size(execute(lambda,...))` tree reaches StoreResolver.resolve. Anchors.spaceOf classifies both native calls as ANCHORED (a TypedGetAll sits beneath, Anchors.java:43-81), so resolveNode takes the generic `case TypedNativeCall nc -> structural(...)` arm (StoreResolver.java:476-478); structural (StoreResolver.java:516-518) recurses into the execute call's children, and the query lambda hits `case TypedLambda l -> l` — 'a BARE lambda VALUE is DATA — its consumer owns resolution' (StoreResolver.java:504). No consumer owns it, so the getAll survives and assertNoStoreOnlyEscapees (StoreResolver.java:220-240) throws with exactly the reported ancestry `root > TypedNativeCall(size) > TypedNativeCall(execute) > TypedLambda > TypedFilter`. The class query itself is fine — the ISIN twin of this very query passes in testAssociationSpecifiedQualifiedProperty (same file, same golden SQL, not in the failing set). The gap is purely 'inline execute() in a non-.values read position'.

**Fix**

Add ONE arm to StatementExecutor.spliceHook (core/src/main/java/com/legend/StatementExecutor.java), immediately after the existing '$r->size() / $tds->size()' arm at line 2534-2543, covering an INLINE execute under size/count:

    // execute(...)->size(): the Result ENVELOPE is ONE value
    // (result.pure:17 Result<T|m>[1]) — never the row count. Pure is
    // strict, so the query still RUNS: build the frame EAGERLY and
    // discard its rows.
    if (n instanceof TypedNativeCall sz2
            && SIZE_FQNS.contains(sz2.callee().qualifiedName())
            && sz2.args().size() == 1) {
        TypedSpec a0 = sz2.args().get(0);
        while (a0 instanceof TypedFrom af) { a0 = af.source(); }
        if (a0 instanceof TypedNativeCall ec2
                && PlatformTypes.isExecuteFqn(ec2.callee().qualifiedName())) {
            try {
                buildFrame(ec2, letPrefix, true, specs, env);   // eager run
            } catch (java.sql.SQLException e) {
                throw new IllegalStateException(e);
            }
            return new TypedCInteger(1L, sz2.info());
        }
    }

Notes that must ride with it: (1) eager MUST be true — unlike the inline `.values` arm (which splices the chain into the enclosing statement, so eager=false avoids a double execution), nothing downstream consumes this chain, so eager=false would silently never run the query and the test would pass for the wrong reason; (2) do NOT gate on relationRooted() as the frame-variable arm does — this query is CLASS-rooted (Product[*]), so chain.info().type() is not a RelationType (StatementExecutor.java:2261), yet Result is still [1] and the answer is still 1; (3) the checked SQLException must be wrapped exactly like spliceValuesRead does at StatementExecutor.java:2719-2721, because the hook is a BiFunction. Leave every OTHER inline-execute read position (e.g. inline execute under an arbitrary native) as the existing loud resolver wall — no blanket 'unwrap execute anywhere' rewrite. Nothing in the resolver changes.

**How legend-engine does it** — /Users/neemsandv/legend/legend-engine/legend-engine-core/legend-engine-core-pure/legend-engine-pure-code-compiled-core/src/main/resources/core/pure/router/router_entry.pure:20 — `function meta::pure::router::execute<T|y>(f:FunctionDefinition<{->T[y]}>[1], m:Mapping[1], runtime:Runtime[1], extensions:Extension[*]):Result<T|y>[1]`; the Result class itself is /Users/neemsandv/legend/legend-pure/legend-pure-dsl/legend-pure-dsl-mapping/legend-pure-m2-dsl-mapping-pure/src/main/resources/platform_dsl_mapping/result.pure:17-20 `Class meta::pure::mapping::Result<T|m> { values:T[m]; activities:Activity[*]; }`. Result is ONE object, so `execute(...)->size()` is 1 in the engine regardless of how many rows the query returns; the engine still evaluates the execute strictly, which is the only real requirement this statement places on the platform.

**Risk** — Tenet-2 trap: the tempting shortcut is to special-case `execute(...)->size()` in EngineTestExecutor's assertSize arm (fold it to 1, or skip the assert). That is harness compensation — the Result-envelope read rules are explicitly platform-owned and were moved OUT of the harness into StatementExecutor (see the audit 19d B2 banner at StatementExecutor.java:2040-2045, 'The splice rules moved VERBATIM from the harness'). Do not touch EngineTestExecutor. Second risk: buildFrame executes SQL as a side effect from inside the inliner hook; because the hook is top-down and the replacement is a literal, the node is not re-visited, so the query runs exactly once — but if the same statement were ever inlined twice the query would execute twice. Third: the new arm only matches a shape that currently always throws, so its regression surface on passing tests is nil.

**Also unblocks** — None. `execute(...)->size()` occurs exactly once in the whole core_relational corpus (grep over the corpus root: only testQualifier.pure:60). The other 'store resolution left getAll' failures in the sweep (functions/tests/projection testVariableReferenceIn{Filter,Map}WithSameNameAsThatInParentProject, tds/tests columnValueDifference*, tds/tests testParseDate) have different ancestry paths and different causes.

**Falsifier** — The whole insertion-point argument rests on the compiled statement being `meta::pure::functions::collection::size(execute(...))`. Cheapest check: temporarily print `preRoot.getClass().getSimpleName()` + the callee FQN just before the `body = new StoreResolver(...).resolve(...)` call at StatementExecutor.java:~346 and run only meta::relational::tests::query::qualifier::testWithParameterToClassNestedSelect. If it prints anything other than a TypedNativeCall whose callee is `meta::pure::functions::collection::size` (e.g. an assertSize user call, or a corpus-defined size overload outside SIZE_FQNS), the arm as written will not fire and the fix location is wrong.

<details><summary>Evidence read (13 citations)</summary>

- /Users/neemsandv/legend/legend-engine/.../core_relational/relational/tests/query/testQualifier.pure:55-62 — the test; statement 4 is `assertSize(0, execute(|Product.all()->filter(p|$p.synonymByType(ProductSynonymType.CUSIP).name == 'ISIN2'), simpleRelationalMapping, testRuntime(), relationalExtensions())->size())`, i.e. size() applied to the Result, not to .values
- /Users/neemsandv/legend/legend-pure/legend-pure-core/legend-pure-m3-core/src/main/resources/platform/pure/essential/tests/assertSize.pure:17-19 — assertSize(collection:Any[*], size:Integer[1]) delegates to assertEq($size, $collection->size()); with collection=0 and size=Result->size()=1 the assert is vacuously true in real pure, so the substantive requirement is only that the second query RUNS
- core/src/main/java/com/legend/harness/EngineTestExecutor.java:1938-1957 — `case "assertSize"`: `Object n = evalScalar(args.get(1), ...)` then `Eval a = eval(args.get(0), ...)`; the two arguments are evaluated as separate expressions
- core/src/main/java/com/legend/harness/EngineTestExecutor.java:2665-2677 — evalSpliced: `stmts.add(expr); LambdaFunction wrapped = new LambdaFunction(List.of(), stmts); ... Compiler.executeResolved(resolved, ctx, runtimeFqn, conn)` — so the compiled statement is literally `size(execute(...))`
- core/src/main/java/com/legend/StatementExecutor.java:329-334 — the only execute() recognizer at statement level: `if (preRoot instanceof TypedNativeCall xc && PlatformTypes.isExecuteFqn(...)) { result = buildFrame(xc, letPrefix, true, specs, env).result(); }` — preRoot here is the `size` call, so it misses
- core/src/main/java/com/legend/StatementExecutor.java:2534-2543 — the existing envelope arm: `$r->size() / $tds->size(): ONE TDS value, never the row count` returns `new TypedCInteger(1L, sz.info())`, but ONLY when the argument is a TypedVariable present in execFrames and relationRooted()
- core/src/main/java/com/legend/StatementExecutor.java:2705-2723 — spliceValuesRead: the ONLY inline-execute arm, and it requires a `.values` TypedPropertyAccess wrapper (`buildFrame(ec, letPrefix, false, specs, env).chain()`)
- core/src/main/java/com/legend/resolver/StoreResolver.java:476-478 and 504 — generic `case TypedNativeCall nc -> structural(...)` and `case TypedLambda l -> l` (bare lambda is DATA); together they walk past the execute call and leave the lambda untouched
- core/src/main/java/com/legend/resolver/StoreResolver.java:220-240 — assertNoStoreOnlyEscapees builds the path exactly as reported and throws the NotImplementedException literal in the brief
- core/src/main/java/com/legend/resolver/Anchors.java:43-115 — anchored()/spaceOf(): a TypedGetAll anywhere beneath makes both native calls ANCHORED, and objectSpine has no arm for execute, so it is never OBJECT
- core/src/main/java/com/legend/builtin/Pure.java:1545 — legend-lite's own declaration: `native function meta::pure::mapping::execute<T>(...):meta::pure::mapping::Result<T>[1]` — multiplicity [1], so ->size() is 1
- core/src/main/java/com/legend/compiler/spec/UserCallInliner.java:84-96 and 303-308 — the hook is fired 'before the standard rewrite at every node' and the rewrite recurses into the replacement, i.e. top-down, so a hook arm on the size node prevents descent into the execute's lambda
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/exec/HostEval.java:76-97 — wantsHostEval only routes executeInDb/storeNav/fetchDb/host-construction chains, so hostChannel does not intercept `size(execute(...))` before the inliner

</details>

---
