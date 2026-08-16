# Bucket 2 — Execution-plan subsystem

71 tests from the ledger; **70 still non-passing** at `9d1f2cd0`. 1 now pass (fixed upstream since the 2026-08-14 sweep) and are marked below.

Each entry was produced by an agent that read the test's `.pure` body, grepped the failure message back to the emitting legend-lite code, read that method's control flow, and checked semantics against real legend-engine / legend-pure sources. Citations were mechanically verified to resolve.

Verdicts: REAL DEFECT 31, MISSING FEATURE 28, GOLDEN TEXT ONLY 6, TESTS ENGINE INTERNALS 3, NEEDS PROBE 1, EXECUTION-TARGET ARTIFACT 1, HARNESS GAP 1

---

## `executeProjectWithNestedDerivedProperty`

| | |
|---|---|
| family | `executionPlan/tests` |
| sweep status | SHAPE |
| **verdict** | **REAL DEFECT** |
| effort | XL |
| confidence | high |

**Root cause**

The typer's function-CARRIER family (`FunctionDefinition<F>` / `LambdaFunction<F>` / `Function<F>`) is recognised in some checks and not others. The harness substitutes `let query = {|...}` into the call, so `generateAndExecutePlan({|…}, ModelToModelMapping, getM2M2RRuntime(), [strings])` reaches `Typer.checkWithDeferred` with arg0 a raw LambdaFunction and the candidate's param0 typed `FunctionDefinition<Any>[1]`. At the brief's sweep the wall was the shape prefilter (`deferredShapesMatch` → `isFunctionTyped` returned false for the carrier). Commit 787c391b fixed exactly that (`isFunctionTyped` now consults `InferenceKernel.FUNCTION_CARRIER_FQNS`, Typer.java:1727-1738) and the wall MOVED — HEAD's recorded wall is `no overload of 'meta::pure::executionPlan::m2m2r::tests::generateAndExecutePlan' matches the argument types` (docs/RELATIONAL_CORPUS.md:1141, rewritten by that same commit). That HEAD message can only be produced by `selectRankedByPresentArgs` (Typer.java:1773-1778) with a NULL arityRejection, which means every candidate was dropped by `lambdaAritiesFit` (Typer.java:1830-1864): for i=0 it calls `extractFunctionType(FunctionDefinition<Any>)`, which throws because the carrier's single type argument is `Any`, not a FunctionType (Typer.java:2027-2036), and the catch does `if (raw.get(i) instanceof LambdaFunction) return false;`. `lambdaArityMismatch` `continue`s in the same catch and returns null (Typer.java:1876-1882), hence the bare message. So the carrier blind spot survives in the arity filter and, one step further, in `typeLambda`.

**Fix**

Two-part, both in core/src/main/java/com/legend/compiler/spec/Typer.java. (1) `lambdaAritiesFit` (:1830): in the `catch (TypeInferenceException e)` block, before rejecting, exempt the carrier family — if `isFunctionTyped(pt)` is true (i.e. `pt` is a GenericType whose rawFqn is in `InferenceKernel.FUNCTION_CARRIER_FQNS`) the lambda's arity is UNCONSTRAINED by the signature, so `continue` instead of `return false`. (2) `bindDeferredAndBuild` (:1567-1590): the `Type.TypeVar` self-typable arm (:1573-1583) must widen to 'TypeVar OR a function carrier with a non-FunctionType argument' — synth the lambda standalone (`typed[i] = synth(raw.get(i), env)`) and unify the carrier param against the synthesised `LambdaFunction<{->T[m]}>` type, instead of calling `typeLambda`, which would re-enter `extractFunctionType` and throw. Guard the standalone path with the existing `selfTypable(lf)` predicate (:1722-1725) so a bare `x|…` against a carrier still fails loudly. NOTE this only unblocks TYPING. The test then needs surfaces that do not exist: `meta::pure::executionPlan::execute(plan, params, extensions)` (executing a generated PLAN HANDLE, not a query) and `meta::json::tdsToJSONKeyValueObjectString` — neither is in builtin/Pure.java. Expect the next wall to be `unknown function 'meta::pure::executionPlan::execute'`. The typer fix is S; the plan-execute surface is the XL part.

**How legend-engine does it** — legend-engine .../core/pure/executionPlan/executionPlan_generation.pure:160 — the engine's own `executionPlan(f:FunctionDefinition<Any>[1], m:Mapping[1], runtime:Runtime[1], …)` is routinely applied to `{|…}` lambdas throughout the corpus, so `LambdaFunction<{->T[1]}>` IS an acceptable `FunctionDefinition<Any>[1]` argument in real Pure.

**Risk** — Widening `lambdaAritiesFit` weakens the P1/P2 overload discrimination the comment at Typer.java:1875-1880 says it exists for (the `executionPlan` Function<{->T}> / Function<{P1->T}> families). Those natives spell their params as real FunctionTypes (`Function<{->Any[*]}>`, builtin/Pure.java:1606,1620,1622), so the carrier exemption should not touch them — but a sweep is mandatory. Tenet-2 trap: do NOT 'fix' this by making Runner.expandHelperCalls β-expand generateAndExecutePlan (adding another idiom beside planChain at Runner.java:688-697). That is harness compensation for a platform typing gap and would also hide the missing plan-execute surface.

**Also unblocks** — Every corpus helper that takes a `FunctionDefinition<Any>[1]`/`Function<Any>[1]` parameter and is called with a let-bound or literal lambda. The burndown index names `testBusinessDatePropagationInColFunction_asQueryParam` (cast/2 against `@FunctionDefinition<Any>`) as the same family, but that one goes through the cast checker, not lambdaAritiesFit — I did not verify it.

**Falsifier** — Run this one test at HEAD. If the wall is NOT `no overload of 'meta::pure::executionPlan::m2m2r::tests::generateAndExecutePlan' matches the argument types`, my identification of the HEAD failure site (lambdaAritiesFit + null lambdaArityMismatch) is wrong — that exact string with no parenthetical is reachable only through the `extractFunctionType` catch. A cheaper check: add a temporary `System.err` in the `catch` at Typer.java:1846 and confirm it fires with pt.typeName()=="FunctionDefinition<Any>".

<details><summary>Evidence read (11 citations)</summary>

- core/src/main/java/com/legend/compiler/spec/Typer.java:1504-1519 — `checkWithDeferred` prefilters candidates by `deferredShapesMatch`; its message is the brief's text (`matches N argument(s) of these shapes — candidates: [...]`)
- core/src/main/java/com/legend/compiler/spec/Typer.java:1727-1738 — `isFunctionTyped` now has the carrier clause `FUNCTION_CARRIER_FQNS.contains(g2.rawFqn())` (added by 787c391b)
- core/src/main/java/com/legend/compiler/spec/Typer.java:1830-1851 — `lambdaAritiesFit`: catch of `extractFunctionType` → `if (raw.get(i) instanceof LambdaFunction) return false;` (no carrier exemption)
- core/src/main/java/com/legend/compiler/spec/Typer.java:2027-2036 — `extractFunctionType` only unwraps a GenericType whose single argument IS a FunctionType; `FunctionDefinition<Any>` throws
- core/src/main/java/com/legend/compiler/spec/Typer.java:1773-1778 — `selectRankedByPresentArgs` throws exactly `no overload of 'X' matches the argument types` with no parenthetical when arityRejection is null
- core/src/main/java/com/legend/compiler/spec/Typer.java:1585-1590 — after selection, `bindDeferredAndBuild` calls `typeLambda(lam, FunctionDefinition<Any>, …)`, which re-enters `extractFunctionType` and would throw again
- core/src/main/java/com/legend/compiler/spec/InferenceKernel.java:1180-1185 — FUNCTION_CARRIER_FQNS = {Function, FunctionDefinition, LambdaFunction, ConcreteFunctionDefinition}
- docs/RELATIONAL_CORPUS.md:1141 — HEAD-current wall for this test is the shorter `matches the argument types` message (line rewritten by 787c391b, verified with `git show 787c391b -- docs/RELATIONAL_CORPUS.md`)
- legend-engine .../core_relational/relational/executionPlan/tests/m2m2rExecutionPlanTests.pure:236-242 — `generateAndExecutePlan(query:FunctionDefinition<Any>[1], mapping:Mapping[1], runtime:Runtime[1], dbSetupSql:String[*])`; body ends in `$plan->meta::pure::executionPlan::execute([], ext).values->cast(@TabularDataSet)->toOne()->meta::json::tdsToJSONKeyValueObjectString()->makeString()`
- core/src/main/java/com/legend/builtin/Pure.java — grep for `executionPlan::execute` and `tdsToJSON` returns NOTHING: neither the plan-handle `execute` nor `tdsToJSONKeyValueObjectString` is in the native catalog
- core/src/test/java/com/legend/rcorpus/Runner.java:647-701 — the let-bound helper only β-expands for the pair/singleExecute/executeTerminal/planChain idioms; `generateAndExecutePlan` matches none, so unlike its `planToString` sibling it stays a real call and must type

</details>

---

## `inheritance`

| | |
|---|---|
| family | `executionPlan/tests` |
| sweep status | SHAPE |
| **verdict** | **REAL DEFECT** |
| effort | L |
| confidence | high |

**Root cause**

`RoadVehicle` is mapped by an Operation (inheritance) set implementation, not a Relational or Pure one. `ScanRelations.rootImplOrNull` scans only `allClassMappings(m)` (Relational sets) and then only `ClassMapping.Pure` sets for the ~src chase; `ClassMapping.Inheritance` — which legend-lite DOES model (ClassMapping.java:352-366) and does consult elsewhere (`hasUnionOperation`, ScanRelations.java:1541-1553) — is not consulted, so `rootImplOrNull` returns null and `rootImpl` throws the observed wall (ScanRelations.java:588-596). Note the SQL lowering already succeeded: `engineSql` runs at StatementExecutor.java:629 BEFORE `PlanText.single` at :633, and `single` calls `rootImpl` on its first line (PlanText.java:57-59). So the failure is purely in the plan-text identity layer. Behind that wall sits a second, structural defect: even given the right sets, `PlanText.typeBlock` can only print ONE impl pair (PlanText.java:120-126), while the engine prints a comma-joined LIST.

**Fix**

Three coordinated changes. (1) `ScanRelations`: add a plural entry point `rootImpls(ctx, mappingFqn, classFqn, chainMappings) -> List<String[]>`. When the class's set in `mappingFqn` (or an include) is a `ClassMapping.Inheritance`, resolve members the way the engine does: walk the class's specializations to the LEAF classes that have a root class mapping in the mapping (the `getMappedLeafTypes` recursion), in specialization-declaration order, and return one `[definingMappingName, setId, dbFqn, mainTable]` per leaf. Keep the existing singular `rootImpl` as `rootImpls(...).get(0)` so no caller breaks. (2) `PlanText.typeBlock` (:113-126): take `List<String[]> impls` plus a parallel list of leaf class FQNs and print `Class[impls=` + entries joined by `,` + `]` then `\n         as <rootClassFqn>` — the leaf class in each pair, the ROOT class on the `as` line, exactly as executionPlan_print.pure:119 does. (3) `PlanText.resultColumns` must type columns through the UNION sub-select: the golden's from-tree is `(select … from Bicycle union all select … from Car) as "unionBase"`, and `resolvePhysical` (PlanText.java:718) currently looks through `SqlSource.Subselect` whose inner is a `SqlSelect` — a union inner needs an arm that resolves the column positionally in the FIRST branch. Do NOT skip step 3: without it the wall just moves.

**How legend-engine does it** — legend-engine .../core/pure/router/operations/router_operations.pure:19-21 (inheritance operation = mapped leaf types) and .../core/pure/executionPlan/executionPlan_print.pure:119 (the multi-impl print format)

**Risk** — `rootImpl` is called from many places (StatementExecutor.java:731,754,889,939; PlanText.java:57). Making it plural must not change the single-set answer for the ~200 passing plan tests — keep the singular wrapper and route only typeBlock through the plural form. Also beware the impls ORDER: it is specialization order, not mapping-declaration order and not alphabetical (Bicycle-before-Car happens to be both here, which is a trap — verify against a case where they differ).

**Also unblocks** — Any plan-text test whose root class is an Operation/union set. `testForcedSubTypeProjectDirect` (tests/mapping/inheritance) fails for a different, resolver-side reason (unmapped property binding) and would NOT be fixed by this.

**Falsifier** — If the union SQL legend-lite generates does not already match the golden's `select "unionBase".u_type … from (… union all …) as "unionBase"` text, then rootImpl is not the only blocker and the fix above will surface a text diff instead of a pass. Cheapest check: run the same query through the toSQLString/execute path (which does not touch rootImpl) and diff the SQL against the golden at executionPlanTest.pure:1030.

<details><summary>Evidence read (11 citations)</summary>

- core/src/main/java/com/legend/lineage/ScanRelations.java:588-596 — `rootImpl` throws `plan: no class mapping for '<class>' under '<mapping>'` when rootImplOrNull is null; this is the exact observed text
- core/src/main/java/com/legend/lineage/ScanRelations.java:598-640 — rootImplOrNull scans `allClassMappings(m)` (Relational) then `ClassMapping.Pure` ~src chase; there is no `ClassMapping.Inheritance`/Operation arm
- core/src/main/java/com/legend/lineage/ScanRelations.java:1541-1553 — `hasUnionOperation` already pattern-matches `ClassMapping.Union || ClassMapping.Inheritance`, proving the model carries the concept
- core/src/main/java/com/legend/model/ClassMapping.java:352-366 — `record Inheritance(className, setId, extendsSetId, root)` with the doc 'the class's extent is the UNION of every mapped set of its subclasses'
- core/src/main/java/com/legend/plan/PlanText.java:120-126 — `"  type = Class[impls=(" + rootClassFqn + " | " + impl[0] + "." + impl[1] + ")]"` — exactly one pair, and the class printed is the ROOT class, not the leaf sets
- core/src/main/java/com/legend/StatementExecutor.java:629-639 — engineSql first, then PlanText.single; so the union SQL was already generated when the wall fired
- legend-engine .../tests/mapping/inheritance/testInheritanceRelational.pure:415-418 — `RoadVehicle : Operation { meta::pure::router::operations::inheritance_OperationSetImplementation_1__SetImplementation_MANY_() }` inside inheritanceMappingDB (which `include`s inheritanceMain at :403)
- legend-engine .../tests/mapping/inheritance/testInheritanceRelational.pure:351,361 — inheritanceMain declares `Car[map1]` then `Bicycle[map2]`, i.e. the golden's impls order (Bicycle,Car) is NOT declaration order
- legend-engine .../core/pure/router/operations/router_operations.pure:19-21,39-42 — `inheritance(o)` = `$o.class->getMappedLeafTypes($o.parent)`, i.e. `$type.specializations` walked to leaves — the SPECIALIZATION order
- legend-engine .../tests/testModel/inheritanceTestModel.pure:40,70 — Bicycle declared before Car, which is why the golden lists (Bicycle | …map2) before (Car | …map1)
- legend-engine .../core/pure/executionPlan/executionPlan_print.pure:119 — `'Class[impls='+$classResultType.setImplementations->map(se|'('+$se.class->elementToPath()+' | '+$se.parent.name+'.'+$se.id+')')->joinStrings(',')+']'` — a LIST, each entry naming the LEAF class

</details>

---

## `planGraphFetchWithDerivedProperty`

| | |
|---|---|
| family | `executionPlan/tests` |
| sweep status | SHAPE |
| **verdict** | **MISSING FEATURE** |
| effort | XL |
| confidence | high |

**Root cause**

Two stacked gaps, both genuine absences. (a) RESOLVER: the query terminal is a BARE `graphFetch(#{...}#)` with no `->serialize(...)`. StoreResolver.resolveNode has an arm for TypedSerialize (StoreResolver.java:408) but none for TypedGraphFetch, so the node falls into the named default at StoreResolver.java:508 and produces exactly the observed text "class query under TypedGraphFetch is not resolvable yet (H2 vocabulary)". collectOpChain likewise only unwraps a TypedGraphFetch when it sits UNDER a TypedSerialize (StoreResolver.java:2581-2586). (b) PLAN PRINTER — the real wall: legend-lite has no graph-fetch execution-node vocabulary at all. com/legend/plan/ contains no reference to graphFetch, and the only occurrence of StoreMappingGlobalGraphFetch in the whole main tree is a native class DECLARATION for reflective asserts (builtin/Pure.java:514). This is corroborated by testQuoteIdentifiersFlagWithGraphFetch (same corpus file, has ->serialize so it clears the resolver): legend-lite prints a plain `Relational(...)` node where the engine prints `PureExp(... StoreMappingGlobalGraphFetch(... RelationalGraphFetch(... SQL(...))))`. This test's golden needs the hardest variant: a MODEL-store StoreMappingGlobalGraphFetch wrapping an InMemoryRootGraphFetch wrapping a second, relational-store StoreMappingGlobalGraphFetch with a RelationalGraphFetch/SQL leaf, plus `PartialClass[impls=..., propertiesWithParameters=[...]]` type spelling, `graphFetchTree = [...]{[/isPeterSmith()]}` text, and nodeIndex / batchSize / checked / localTreeIndices / dependencyIndices lines.

**Fix**

Do not fix now — ledger it. This is an honest wall over an absent surface, and building it is a subsystem, not a patch. If it is picked up, the work is: (1) a small, correct resolver addition — add `case TypedGraphFetch gf when anchored(gf.source()) -> resolveChain(gf, context)` next to StoreResolver.java:408 and extend collectOpChain's terminal detection at StoreResolver.java:2581 with `else if (top instanceof TypedGraphFetch gf) { tree = gf.tree(); checkedEnvelope = gf.checked(); cur = gf.source(); }` so a bare graphFetch is the same graph terminal as serialize's, minus the PureExp/serialize envelope (the golden's root node is StoreMappingGlobalGraphFetch, not PureExp — unlike testQuoteIdentifiersFlagWithGraphFetch); and (2) the large part — a new plan/GraphFetchPlanText emitting StoreMappingGlobalGraphFetch / InMemoryRootGraphFetch / RelationalGraphFetch / SQL nodes with the PartialClass type spelling, graphFetchTree rendering, and localTreeIndices/dependencyIndices computation, driven by the store split of the graph tree. Doing (1) alone would replace this wall with a plan-printer wall, which is a strictly more accurate wall but not a pass; only do (1) alone if the goal is to move the wall to where the work actually is.

**How legend-engine does it** — /Users/neemsandv/legend/legend-engine/legend-engine-core/legend-engine-core-pure/legend-engine-pure-code-compiled-core/src/main/resources/core/pure/mapping/modelToModel.pure:471 — meta::pure::mapping::modelToModel::graphFetch::executionPlan::planModelChainConnectionGraphFetchExecution builds the ModelChainConnection graph-fetch node tree; modelToModel.pure:313-314 is the chainConnectionNodeGeneration hook that produces the nested StoreMappingGlobalGraphFetch this golden pins.

**Risk** — Doing only the resolver arm converts a SHAPE at the resolver into a SHAPE at the printer and risks a silent wrong plan if some path falls back to the single-node Relational printer (exactly what testQuoteIdentifiersFlagWithGraphFetch already does — it prints a Relational node for a graph-fetch plan, which is wrong output rather than a wall). Prefer the loud wall until the node vocabulary exists. Tenet-2 trap: never assemble the expected StoreMappingGlobalGraphFetch text in the harness or in a test-only formatter.

**Also unblocks** — planGraphFetchWithNestedDerivedProperty (identical query and identical golden). The plan-printer half would also be the prerequisite for testQuoteIdentifiersFlagWithGraphFetch, testGraphFetchH2TempTableStrategy and testGraphFetchH2TempTableStrategyWithQuoteIdentifiers.

**Falsifier** — A grep for a graph-fetch plan-node emitter under core/src/main/java/com/legend/plan returning any hit would falsify the 'vocabulary absent' claim (it returns nothing today). Equally, if testQuoteIdentifiersFlagWithGraphFetch's got text ever contains 'StoreMappingGlobalGraphFetch', the printer exists and this is only a resolver gap.

<details><summary>Evidence read (7 citations)</summary>

- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/resolver/StoreResolver.java:508 — `default -> throw new NotImplementedException("class query under " + n.getClass().getSimpleName() + " is not resolvable yet (H2 vocabulary)")`; getSimpleName() of TypedGraphFetch reproduces the sweep message exactly.
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/resolver/StoreResolver.java:408 — the only graph terminal arm is `case TypedSerialize sz when anchored(sz.source()) -> resolveChain(sz, context)`; there is no TypedGraphFetch arm anywhere in the switch (lines 400-511).
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/resolver/StoreResolver.java:2581-2586 — collectOpChain unwraps graphFetch only via `sz.source() instanceof TypedGraphFetch gf ? gf.source() : sz.source()`; a bare graphFetch would fall to the `implicitSerialize` else-branch and then hit the `while (!(cur instanceof TypedGetAll))` walk with a node it cannot consume.
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/builtin/Pure.java:514 — the sole StoreMappingGlobalGraphFetchExecutionNode reference in main: a `nativeClass(...)` declaration, no printer.
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/plan/PlanText.java:1-799 — the plan-text vocabulary is Relational / Sequence / Allocation / Constant / FunctionParametersValidationNode only; no graph-fetch node kind exists.
- /Users/neemsandv/.claude/jobs/5671074c/tmp/burndown/dossiers/executionPlan__tests.md:1719 — testQuoteIdentifiersFlagWithGraphFetch got `Relational(type=String resultSizeRange=1 ...)` where the engine emits the StoreMappingGlobalGraphFetch tree: independent proof that the graph-fetch node vocabulary is absent, not merely unreached.
- /Users/neemsandv/legend/legend-engine/legend-engine-xts-relationalStore/legend-engine-xt-relationalStore-generation/legend-engine-xt-relationalStore-pure/legend-engine-xt-relationalStore-core-pure/src/main/resources/core_relational/relational/executionPlan/tests/m2m2rExecutionPlanTests.pure:293-310 — the model: `PersonPeterSmith : Pure { ~src _Person, details : $src }` over `_Person : Relational` in a ModelChainConnection, i.e. a genuine two-store graph fetch.

</details>

---

## `planGraphFetchWithNestedDerivedProperty`

| | |
|---|---|
| family | `executionPlan/tests` |
| sweep status | SHAPE |
| **verdict** | **MISSING FEATURE** |
| effort | XL |
| confidence | high |

**Root cause**

Byte-identical to planGraphFetchWithDerivedProperty — the corpus declares the same query `PersonPeterSmith.all()->meta::pure::graphFetch::execution::graphFetch(#{PersonPeterSmith{isPeterSmith}}#)` and the same expected plan text in both functions (m2m2rExecutionPlanTests.pure:37 and :167). The bare graphFetch terminal has no arm in StoreResolver.resolveNode and falls to the named default at StoreResolver.java:508; behind that, the StoreMappingGlobalGraphFetch / InMemoryRootGraphFetch / RelationalGraphFetch plan-node vocabulary does not exist in legend-lite.

**Fix**

Do not fix separately — it is the same fix as planGraphFetchWithDerivedProperty (same query, same golden). Ledger both together under one graph-fetch-plan-vocabulary item; whatever makes the sibling pass makes this pass.

**How legend-engine does it** — /Users/neemsandv/legend/legend-engine/legend-engine-core/legend-engine-core-pure/legend-engine-pure-code-compiled-core/src/main/resources/core/pure/mapping/modelToModel.pure:471 (planModelChainConnectionGraphFetchExecution) — the nested model-store-over-relational-store graph-fetch node tree the golden pins.

**Risk** — None beyond the sibling's. Do not be tempted to treat the duplicate as a corpus bug worth special-casing — it is a plain duplicate test in the engine corpus and must be run as data like every other.

**Also unblocks** — planGraphFetchWithDerivedProperty

**Falsifier** — Diff m2m2rExecutionPlanTests.pure lines 37-131 against 167-238: if the two bodies are NOT identical, this test has an independent shape and the shared-fix claim is wrong.

<details><summary>Evidence read (5 citations)</summary>

- /Users/neemsandv/legend/legend-engine/legend-engine-xts-relationalStore/legend-engine-xt-relationalStore-generation/legend-engine-xt-relationalStore-pure/legend-engine-xt-relationalStore-core-pure/src/main/resources/core_relational/relational/executionPlan/tests/m2m2rExecutionPlanTests.pure:167-171 — planGraphFetchWithNestedDerivedProperty's query is character-for-character the same as planGraphFetchWithDerivedProperty's at line 37-41, with the same `expected` block.
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/resolver/StoreResolver.java:508 — the default arm that produced the observed message.
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/resolver/StoreResolver.java:408 — TypedSerialize is the only graph terminal arm.
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/plan/PlanText.java:26-30 — the plan printer's own doc scopes it to "SINGLE-RELATIONAL plans"; no graph-fetch node kinds are defined in the file.
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/builtin/Pure.java:514 — StoreMappingGlobalGraphFetchExecutionNode exists only as a native class declaration.

</details>

---

## `relationalTDSTypeForColumnsAndQuoting`

| | |
|---|---|
| family | `executionPlan/tests` |
| sweep status | SHAPE |
| **verdict** | **REAL DEFECT** |
| effort | L |
| confidence | high |

**Root cause**

The query's root is `tableToTDS(tableReference(db,'default','tableWithQuotedColumns'))` — a direct STORE relation, with `EmptyMapping` as the mapping and no class anywhere. `StatementExecutor.planToString` reaches the single-node arm and calls `rootGetAllClass(lam.body())`, a BFS that returns the first `TypedGetAll`'s class FQN and null when there is none (StatementExecutor.java:2028-2038). There is no getAll, so it returns null and line 612-616 throws `planToString: no getAll root (multi-node plans pending)`. The class root is not incidental: everything downstream is keyed on it — `PlanText.single` calls `ScanRelations.rootImpl(ctx, mappingFqn, rootClassFqn, …)` on its first line purely to obtain `impl[2]`, the database FQN used to type every column. For a tableToTDS root that database is named directly by the `tableReference` argument, and the `type = TDS[…]` block needs no class identity at all (PlanText.typeBlock's RelationType branch never uses impl[0]/impl[1]).

**Fix**

In `StatementExecutor.planToString`, before the `rootClass == null` wall (StatementExecutor.java:611), add a STORE-ROOT arm: walk the typed body for the `tableReference` call (a TypedNativeCall whose callee is CoreFn.TABLE_REFERENCE, arg0 a TypedPackageableRef = the Database FQN) and, when found, take that FQN as the plan's dbFqn. Then add `PlanText.singleTds(ModelContext ctx, String dbFqn, SqlQuery plan, String sql, List<TypedSpec> body, String connName)` — the same body as `single()` (PlanText.java:52-83) minus the `ScanRelations.rootImpl` call, passing `dbFqn` where `impl[2]` was used and emitting `typeBlock`'s TDS branch directly (no impls line, no resultSizeRange). Mirror `ScanRelations.collectTableToTds` (:506-528) for the extraction so the two readers cannot drift. Do not thread a fake class through rootImpl — the honest shape is 'this plan has a store, not a set implementation'.

**How legend-engine does it** — legend-engine .../core/pure/executionPlan/executionPlan_generation.pure:151-158 — the mapping-less entry passes a DUMMY `^Mapping(package=meta::pure::executionPlan, name='dummy')`; the plan's identity comes from the routed clusters' stores, never from a class mapping.

**Risk** — Low blast radius (a new arm reached only when rootGetAllClass is null, which today always throws). The follow-on risk is real though: the golden also exercises quoted-identifier restrict/filter (`->restrict(['"FIRST NAME"'])`), so the test can still fail on column-name quoting after the envelope is built.

**Also unblocks** — Likely `testViewToTDS` and the other tableToTDS-rooted plan goldens in executionPlan/tests, which must hit the identical wall — I did not open their failure rows, so this is inference from the shared root, not a verified claim.

**Falsifier** — If, after supplying dbFqn, the generated SQL is not `select "root"."FIRST NAME" as "FIRST NAME", … from tableWithQuotedColumns as "root" where "root"."FIRST NAME" = 'Peter'`, the remaining gap is identifier quoting for space-bearing and digit-leading column names, not the plan envelope. Cheapest check: run the same query through toSQLString (no plan text) and compare with the golden's `sql =` line.

<details><summary>Evidence read (7 citations)</summary>

- core/src/main/java/com/legend/StatementExecutor.java:611-616 — `String rootClass = rootGetAllClass(lam.body()); if (rootClass == null) throw new NotImplementedException("planToString: no getAll root (multi-node plans pending)")` — the exact observed text
- core/src/main/java/com/legend/StatementExecutor.java:2028-2038 — rootGetAllClass is a BFS returning `ga.classFqn()` for the first TypedGetAll, else null
- core/src/main/java/com/legend/plan/PlanText.java:52-83 — `single()` opens with `ScanRelations.rootImpl(ctx, mappingFqn, rootClassFqn, chainMappings)`; `impl[2]` then feeds `resultColumns` and `tdsTuples`
- core/src/main/java/com/legend/plan/PlanText.java:99-108 — typeBlock's RelationType branch prints `type = TDS[...]` from `tdsTuples(ctx, impl[2], …)` and needs impl[0]/impl[1] not at all
- core/src/main/java/com/legend/lineage/ScanRelations.java:506-528 — `collectTableToTds` already extracts `(db.fullPath(), table)` from `tableToTDS(tableReference(<PackageableElementPtr db>, s, 'T'))` at the PROTOCOL level — the exact datum the plan path needs, but only as ValueSpecification, not TypedSpec
- core/src/main/java/com/legend/builtin/Pure.java:2018-2021 — TABLE_TO_TDS is a registered native over the relation carrier, so the query types and lowers; only the plan-text envelope walls
- legend-engine .../executionPlan/tests/executionPlanTest.pure:2409-2447 — the test: `tableToTDS(tableReference(db,'default','tableWithQuotedColumns'))->project([col(…)])->restrict([…])->filter(…)` against `EmptyMapping`; the golden has a `type = TDS[…]` block, resultColumns, and NO resultSizeRange/impls line

</details>

---

## `tdsJoinTwoDBExtend`

| | |
|---|---|
| family | `executionPlan/tests` |
| sweep status | FAIL |
| **verdict** | **REAL DEFECT** |
| effort | M |
| confidence | high |

**Root cause**

In the engine's cross-store TDS-join split, the left side of the join is replaced by a VarSetPlaceHolder whose synthesized Columns are ALL hard-typed Integer: pureToSQLQuery.pure:581-584 builds `^VarSetPlaceHolder(varName=..., columns=$v.tdsColumns->map(c|^Column(owner=..., name=$c.name, type = ^meta::relational::metamodel::datatype::Integer())), paths = ... relationalType = $c.sourceDataType ...)`. The plan node's resultColumns are typed from the column object (relationalMappingExecution.pure:228 `dataType = $c->getRelationalTypeFromRelationalOperationElement()`, which for a TableAliasColumn returns `$t.column.type` — relationalMappingExecution.pure:261), so every column read out of the placeholder side prints INT; the `type = TDS[...]` line instead rides `paths`/sourceDataType and keeps the REAL type. That is exactly the golden's apparent contradiction: `type = TDS[(firstName, String, VARCHAR(200), "")]` but `resultColumns = [("firstName", INT), ...]`. legend-lite's crossDbTdsPlan explicitly declines to do this: StatementExecutor.java:646-650 documents 'Type and resultColumns for the terminal resolve over the ORIGINAL (pre-splice) plan … only the SQL TEXT renders the placeholder form', and :745-752 passes `fullEs.plan()` (pre-splice) as the plan argument to PlanText.single while passing the spliced `splicedSql` only as text. PlanText.resultColumns therefore takes the star-top branch (PlanText.java:540-562) and resolves firstName BY NAME through the real left subselect down to personTable.FIRSTNAME → VARCHAR(200). eID/managerID/fID/legalName coincidentally agree (they really are INT / INT / INT / VARCHAR(200)), so firstName is the only visible diff.

**Fix**

Make the terminal (and any spliced Allocation) resolve resultColumns over the SPLICED IR, and give the placeholder the engine's INT typing. Three edits: (1) PlanText.java — split spliceLeftVar into `public static @Nullable SqlQuery spliceLeftVarQuery(SqlQuery plan, String var)` returning the swapped SqlSelect (the existing spliceLeftVar becomes `q == null ? null : renderer.render(q)`). (2) PlanText.java — add a `colPlan` parameter to `single(...)` (defaulting to `plan`) used ONLY by the `resultColumns(ctx, impl[2], colPlan, rrt)` call at :76; `typeBlock(...)` keeps taking the ORIGINAL `plan` (that is the engine's paths/sourceDataType channel — the `type = TDS[...]` line must stay VARCHAR(200)). (3) PlanText.java — in the star-top loop (:548-561) and in resolveStarColumn/resolvePhysical, add a `SqlSource.VarSetPlaceholder vp` arm: if any `vp.outputs()` name matches the column (case-insensitive), spell `"INT"` directly instead of resolving a physical column — one-line-comment it as the pureToSQLQuery.pure:583 hard-typed `^Integer()` placeholder column. Cleanest shape: a small `starColumnSpelling(ctx, dbFqn, from, name)` helper returning the type STRING, with the placeholder arm returning "INT", used by both resultColumns and (unchanged, over the original plan) tdsTuples. (4) StatementExecutor.crossDbTdsPlan :745-752 — compute `SqlQuery splicedIr = PlanText.spliceLeftVarQuery(fullEs.plan(), prevVar)` and pass it as `colPlan`; do the same for the per-Allocation `aSql` splice at :726 so a 3-DB chain is consistent.

**How legend-engine does it** — legend-engine .../core_relational/relational/pureToSQLQuery/pureToSQLQuery.pure:581-584 (VarSetPlaceHolder columns hard-typed Integer, real types kept on `paths`); .../relationalMappingExecution.pure:228 and :258-266 (resultColumns dataType comes from the Column object's type)

**Risk** — The INT rule must fire ONLY when a VarSetPlaceholder is actually in the from tree, and ONLY for resultColumns — never for the `type = TDS[...]` line and never for single-store plans (there is no placeholder there, so a from-tree-driven implementation is naturally inert). Tenet-2 trap to avoid: do NOT special-case a column name, and do NOT normalize types in the harness comparison (PlanAsserts.planTextAssert) — the plan channel owns this spelling. Also do not 'fix' the ALLOCATION node's resultColumns for the first (unspliced) allocation: the golden there is VARCHAR(200) and is already right.

**Also unblocks** — testCrossDbPlanGenerationWithFromWithoutExternalMapping (docs/RELATIONAL_CORPUS.md:1153 — byte-identical single diff: ("firstName", INT) vs ("firstName", VARCHAR(200)))

**Falsifier** — Open pureToSQLQuery.pure:583. If the VarSetPlaceHolder columns were NOT constructed with `type = ^Integer()` — i.e. if the placeholder carried real column types — then the INT in the golden would have some other origin and this diagnosis is wrong. Secondary: if applying the rule to test 6 changed any column other than firstName (it must not: eID/managerID/fID/legalName are already INT/INT/INT/VARCHAR(200) in both), the mechanism is mis-scoped.

<details><summary>Evidence read (11 citations)</summary>

- /Users/neemsandv/legend/legend-engine/legend-engine-xts-relationalStore/legend-engine-xt-relationalStore-generation/legend-engine-xt-relationalStore-pure/legend-engine-xt-relationalStore-core-pure/src/main/resources/core_relational/relational/pureToSQLQuery/pureToSQLQuery.pure:583 — `type = ^meta::relational::metamodel::datatype::Integer()` for every VarSetPlaceHolder column
- /Users/neemsandv/legend/legend-engine/.../core_relational/relational/pureToSQLQuery/pureToSQLQuery.pure:584 — the same placeholder carries `paths = … ^PathInformation(type=$c.type, relationalType=$c.sourceDataType)`, i.e. the REAL type survives on a different channel (this is why the type= line stays VARCHAR(200))
- /Users/neemsandv/legend/legend-engine/.../core_relational/relational/relationalMappingExecution.pure:228 — `resultColumns = … ^SQLResultColumn(label=…, dataType = $c->getRelationalTypeFromRelationalOperationElement()…)`
- /Users/neemsandv/legend/legend-engine/.../core_relational/relational/relationalMappingExecution.pure:260-261 — `$r->match([t:TableAliasColumn[1] | $t.column.type, …])`
- /Users/neemsandv/legend/legend-engine/.../core_relational/relational/contract/storeContract.pure:156 — the plan printer prints `resultColumns` from `executionNodes->at(0)->cast(@SQLExecutionNode).resultColumns` via `dataTypeToSqlText()`; no other channel feeds that line
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/StatementExecutor.java:646-650 — comment: 'Type and resultColumns for the terminal resolve over the ORIGINAL (pre-splice) plan … only the SQL TEXT renders the placeholder form'
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/StatementExecutor.java:745-752 — `splicedSql = PlanText.spliceLeftVar(fullEs.plan(), prevVar, …)` then `PlanText.single(ctx, rootClass, mappingFqn, fullEs.plan(), splicedSql, …)` — text spliced, IR not
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/plan/PlanText.java:540-562 — star-top resultColumns resolve each TDS column via resolveStarColumn over the from tree and spell the PHYSICAL store column type
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/plan/PlanText.java:492-513 — spliceLeftVar builds the swapped SqlSource but RETURNS ONLY RENDERED TEXT; the spliced IR is discarded
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/plan/PlanText.java:675-716 — resolveStarColumn has no VarSetPlaceholder arm; it falls to the `default` case and throws
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/docs/RELATIONAL_CORPUS.md:1150 — full untruncated sweep entry: expected vs got differ in EXACTLY one token, `("firstName", INT)` vs `("firstName", VARCHAR(200))`; the terminal SQL text matches character-for-character

</details>

---

## `tdsJoinTwoDBWithColumnMappedViaJoins`

| | |
|---|---|
| family | `executionPlan/tests` |
| sweep status | FAIL |
| **verdict** | **REAL DEFECT** |
| effort | XS |
| confidence | high |
| **adversarial review** | **CONFIRMED** |

**Root cause**

Identical mechanism to tdsJoinTwoDBExtend. The terminal Relational node's resultColumns are computed by PlanText over the PRE-SPLICE IR (StatementExecutor.java:745-752), so firstName resolves through the real left subselect to personTable.FIRSTNAME and spells VARCHAR(200); the engine, having replaced that side with a VarSetPlaceHolder whose Columns are hard-typed `^Integer()` (pureToSQLQuery.pure:583), spells INT. Confirmed by the full sweep entry: the only difference between expected and got across the whole two-node Sequence is `("firstName", INT)` vs `("firstName", VARCHAR(200))` in the terminal node. Note the other columns already agree for INDEPENDENT reasons: managerID is a CASE expression in the left subselect, so resolveStarColumn's projection loop finds no plain Column and descends to `is.from()` (PlanText.java:698-708), landing on personTable.MANAGERID → INT, which coincides with the engine's placeholder INT; and in the FIRST (Allocation) node managerID correctly prints the empty type "" because the engine has no physical column for a computed projection.

**Fix**

Exactly the fix written for tdsJoinTwoDBExtend — one change, both tests. No test-specific work is needed here.

**How legend-engine does it** — legend-engine .../core_relational/relational/pureToSQLQuery/pureToSQLQuery.pure:581-584

**⚠ Correction from adversarial review** — The fix is not stated here (it is by reference to tdsJoinTwoDBExtend), so I could not verify it. The decisive regression guard it must satisfy: the fix MUST be scoped to columns resolving through the SPLICED/placeholder side only — not to PlanText.resolveStarColumn's Subselect arm in general. tdsJoinOneDBOneExpression (executionPlanTest.pure:1791) currently PASSES with the identical star-top-over-joined-projection-subselects shape and expects `("firstName", VARCHAR(200))`; any change that makes star-descent through a subselect yield INT would break it. Concretely the discriminator has to be 'this subselect is the one crossDbTdsPlan replaced with ${tdsVar}', which is knowledge crossDbTdsPlan has (it knows the spine's left leg) but PlanText.single as currently called at StatementExecutor.java:751-753 does not — the terminal is printed from `fullEs.plan()` with no marker for which subtree was spliced. If the fix has to thread that marker into PlanText.single/resultColumns, the effort is S, not XS.

<details><summary>Adversarial review notes (CONFIRMED)</summary>

Every checkable claim holds. I mechanically diffed the expected/got payloads recorded on RELATIONAL_CORPUS.md:1150 and :1151: both are 29 lines and both differ on exactly ONE line (line 23, the terminal node's resultColumns), and the difference is exactly the firstName token — expected INT, got VARCHAR(200). Every other line (both SQL strings, both type= blocks, the whole Allocation child incl. managerID's empty-type "") is byte-identical. So the shared-cause claim (one fix, both tests) is correct: the same edit that turns the terminal's firstName from VARCHAR(200) into INT is the whole delta for BOTH tests, and eID/managerID/fID/legalName are already right and would be unaffected (eID and managerID are already INT, so making placeholder-side columns INT is a no-op for them). The mechanism checks out end to end: crossDbTdsPlan prints the terminal from the PRE-splice `fullEs.plan()` (StatementExecutor.java:742-753 — `String terminal = PlanText.single(..., fullEs.plan(), splicedSql, ...)`, i.e. types from the real IR, SQL text from the spliced string), so PlanText resolves firstName through the real left subselect's `"root".FIRSTNAME as "firstName"` projection to personTable.FIRSTNAME = VARCHAR(200); the engine instead types it off the VarSetPlaceHolder whose every Column is hard-typed `^Integer()`. The managerID side-explanation is also exactly right and I traced it: PlanText.java:698-707 requires `p2.expr() instanceof SqlExpr.Column` to resolve through a named projection, and managerID's projection is a CASE, so the loop falls through to `resolveStarColumn(ctx, dbFqn, is.from(), col)` and lands on personTable.MANAGERID = INT, coinciding with the engine's placeholder INT. What I could NOT check: the fix itself, which is stated only by reference ("exactly the fix written for tdsJoinTwoDBExtend") and is not in this batch — see fixCorrection for the regression guard that fix must satisfy.

</details>

**Citation issues found in review** — Minor, non-material: testTDSJoin.pure:148-170 is cited as the mapping but the mapping declaration actually spans 155-172 (lines 148-152 are the tail of the PREVIOUS mapping's testJoinTDS_Address scope). Also the diagnosis calls it a test — it is a Mapping element, not a test.Test function. Everything else resolves exactly.

**Risk** — Same as tdsJoinTwoDBExtend. In particular the fix must not disturb the `("managerID", "")` empty-type spelling in the FIRST node, which comes from the computed-projection arm at PlanText.java:569-577.

**Also unblocks** — tdsJoinTwoDBExtend, testCrossDbPlanGenerationWithFromWithoutExternalMapping

**Falsifier** — Same as tdsJoinTwoDBExtend. Additionally: if, after the fix, this test still differs, it can only be in a place the sweep entry shows as already matching — so a first-diff dump (LL_TMP_DEBUG=1, PlanAsserts.planGoldenDebug at PlanText/PlanAsserts.java:145-161) pointing anywhere other than the firstName token would falsify the claim that the two tests share one cause.

<details><summary>Evidence read (6 citations)</summary>

- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/docs/RELATIONAL_CORPUS.md:1151 — full untruncated expected/got: the ONLY divergence is `resultColumns = [("firstName", INT), ("eID", INT), ("managerID", INT), ("fID", INT), ("legalName", VARCHAR(200))]` vs `[("firstName", VARCHAR(200)), …]`; both SQL strings and both type= lines match exactly
- /Users/neemsandv/legend/legend-engine/.../core_relational/relational/pureToSQLQuery/pureToSQLQuery.pure:583 — every VarSetPlaceHolder column is `^Column(… type = ^Integer())`
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/StatementExecutor.java:745-752 — the terminal is printed from the pre-splice `fullEs.plan()`
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/plan/PlanText.java:698-708 — resolveStarColumn descends past a non-Column projection into the subselect's own from tree (why managerID lands on personTable.MANAGERID → INT)
- /Users/neemsandv/legend/legend-engine/.../core_relational/relational/tds/tests/testTDSJoin.pure:148-170 — testJoinTDSMappingTwoDatabaseWithColumnsMappedViaJoinsAndDynaFunction: person side scoped to dbInc (managerID via case(equal(MANAGERID,0),0,MANAGERID)), firm side scoped to database2 — the cross-store split this test exercises
- /Users/neemsandv/legend/legend-engine/.../core_relational/relational/tds/tests/testTDSJoin.pure:1146-1152 — `Database database2 ( … Table firmTable(ID INT PRIMARY KEY, LEGALNAME VARCHAR(200), ADDRESSID INT) )`

</details>

---

## `tdsTwoJoinThreeDB`

| | |
|---|---|
| family | `executionPlan/tests` |
| sweep status | ERROR |
| **verdict** | **REAL DEFECT** |
| effort | L |
| confidence | medium |

**Root cause**

`No value present` is a bare `NoSuchElementException` from `Optional.orElseThrow()` with no supplier. In the plan-text path the only unmessaged such calls are the column-type lookups in `PlanText`: :298/:301, :313/:316, :328/:332 (tdsTuples) and :555/:559 (resultColumns star arm). They all resolve a physical `[table, column]` and then do `ctx.findTableDefinition(dbFqn, pc[0]).orElseThrow()` where `dbFqn` is the SINGLE database `impl[2]` of the BFS-chosen root class. This query spans THREE databases (testJoinTDS_Person scope[dbInc], testJoinTDS_Firm scope[database2], testJoinTDS_Address scope[database3]), so at least one projected column's physical table is not in `dbFqn` and the lookup is empty. The :313 site is the likely one: it follows `resolvePhysical`, which looks THROUGH subselects to the physical table and — unlike `resolveStarColumn` (:675-716, which is db-checked and throws a MESSAGED NotImplementedException) — never verifies the table belongs to `dbFqn`. Because the exception is a NoSuchElementException it is not in `PlanAsserts.planTextAssert`'s catch list (NotImplementedException | LegendCompileException | UnsupportedOperationException, PlanAsserts.java:188-190), so it escapes the runner as a raw ERROR instead of a named wall. Two further defects sit behind it: (a) `rootGetAllClass` is a BFS over children (StatementExecutor.java:2028-2038), so for a nested join it returns whichever branch's getAll it reaches first, not the query's leading root; (b) the Allocation var NAMES are assigned innermost-first as tdsVar, tdsVar_0 (StatementExecutor.java:715-716) whereas the engine's golden names them tdsVar_0 (inner) and tdsVar (outer).

**Fix**

(1) Immediate correctness: in `PlanText`, every `findTableDefinition(dbFqn, …).orElseThrow()` and `columns().stream()…findFirst().orElseThrow()` must carry a NotImplementedException supplier the way :202-207 and :582-590 already do, so a cross-store lookup becomes a named wall instead of `No value present`. (2) The actual defect: plan-node column typing must not assume one database. A projection whose source is a spliced TDS var must NOT be looked up physically at all — per pureToSQLQuery.pure:580-585 its resultColumns type is INT (the VarSetPlaceHolder column type) while its `type = TDS[…]` tuple keeps the source column's data type carried through `paths`. So in `PlanText.tdsTuples`/`resultColumns`, detect that `resolvePhysical` landed inside the spliced sub-select (or that the resolved table is not in `dbFqn`) and take the placeholder typing (INT) for resultColumns, keeping the pure/source type for the tuple line. (3) `rootGetAllClass` must take the LEFT-SPINE root (depth-first down `children().get(0)`, or better: the leftmost TypedGetAll of the terminal's left spine) rather than a BFS, so the node's dbFqn is the leading store. (4) Rename the allocation vars to match the engine: the OUTERMOST spine allocation is `tdsVar`, inner ones `tdsVar_0`, `tdsVar_1` … — i.e. invert the naming at StatementExecutor.java:715-716.

**How legend-engine does it** — legend-engine .../core_relational/relational/pureToSQLQuery/pureToSQLQuery.pure:580-585 — VarSetPlaceHolder columns hardcoded to `meta::relational::metamodel::datatype::Integer`, with the real source type preserved separately in `paths`

**Risk** — Change (2) touches resultColumns typing for ALL cross-store plans, i.e. tdsJoinTwoDBExtend / tdsJoinTwoDBWithColumnMappedViaJoins / testCrossDbPlanGenerationWithFromWithoutExternalMapping (currently FAILing on text in other units) — it should move them toward their goldens, but it reproduces an ENGINE BUG (a VARCHAR column printed as INT). That is a policy call the maintainer must make explicitly: matching the corpus here means matching a known engine defect. Change (3) alters rootGetAllClass, used by the sequence and single paths too — verify the ~60 passing plan tests.

**Also unblocks** — tdsJoinTwoDBExtend, tdsJoinTwoDBWithColumnMappedViaJoins, testCrossDbPlanGenerationWithFromWithoutExternalMapping (same splice-column typing); those are FAILs in other units.

**Falsifier** — Run only this test with a stack trace (the runner already prints one under LL_TMP_DEBUG on the tryRunNoExecute path; this test goes through the execute path, so temporarily rethrow with the frame). If the top frame is not one of PlanText:298/313/328/555 (or the sibling column `findFirst().orElseThrow()` at :301/316/332/559), my identification is wrong and the NoSuchElementException comes from somewhere else in lowering — everything else in this diagnosis then still stands as latent, but the ERROR has another cause.

<details><summary>Evidence read (11 citations)</summary>

- core/src/main/java/com/legend/plan/PlanText.java:296-301 — `resolveStarColumn(...)` then `ctx.findTableDefinition(dbFqn, pc[0]).orElseThrow()` (no supplier → 'No value present')
- core/src/main/java/com/legend/plan/PlanText.java:308-316 — the POSITIONAL-projection arm: `resolvePhysical(s.from(), c.table(), …)` then `ctx.findTableDefinition(dbFqn, pc[0]).orElseThrow()` — resolvePhysical does no db check, so a foreign-store table lands here
- core/src/main/java/com/legend/plan/PlanText.java:554-559 — same unmessaged pattern in the star-top resultColumns arm
- core/src/main/java/com/legend/plan/PlanText.java:675-716 — `resolveStarColumn` DOES consult dbFqn and throws a messaged NotImplementedException, which is why the star arm degrades to a SHAPE wall while the positional arm crashes
- core/src/main/java/com/legend/plan/PlanText.java:202-207 — the same lookup with a MESSAGED supplier, showing the inconsistency is accidental
- core/src/main/java/com/legend/harness/PlanAsserts.java:188-190 — the catch list that would have converted this into a plan wall does not include NoSuchElementException/RuntimeException
- core/src/main/java/com/legend/StatementExecutor.java:710-741 — crossDbTdsPlan's allocation loop: `String var = prevVar == null ? "tdsVar" : "tdsVar_" + (allocs.size() - 1)` (innermost gets the unsuffixed name)
- core/src/main/java/com/legend/StatementExecutor.java:2028-2038 — rootGetAllClass BFS: for the nested join it can return the Address branch's class, whose db is database3
- legend-engine .../tds/tests/testTDSJoin.pure:177-209 — testJoinTDSMappingThreeDatabase: Person scope([dbInc]), Firm scope([database2]), Address scope([database3])
- legend-engine .../executionPlan/tests/executionPlanTest.pure:1918-1943 — the golden's first Allocation is named tdsVar_0 and the second tdsVar; the second's resultColumns type EVERY column INT, including firstName
- legend-engine .../pureToSQLQuery/pureToSQLQuery.pure:580-585 — `v:PlanSetPlaceHolder[1] | ^VarSetPlaceHolder(columns=$v.tdsColumns->map(c|^Column(… type = ^meta::relational::metamodel::datatype::Integer())), paths = …sourceDataType…)` — columns arriving through a ${var} splice are typed Integer, while the TDS tuple line keeps the source data type from `paths`

</details>

---

## `testCrossDbPlanGenerationWithFromWithoutExternalMapping`

| | |
|---|---|
| family | `executionPlan/tests` |
| sweep status | FAIL |
| **verdict** | **REAL DEFECT** |
| effort | M |
| confidence | high |

**Root cause**

The cross-store split builds a Sequence(Allocation(tdsVar, Relational(left)), Relational(join)). For the terminal node, StatementExecutor.crossDbTdsPlan splices the plan's leftmost subselect into a `(${tdsVar})` VarSetPlaceholder for the SQL TEXT ONLY (PlanText.spliceLeftVar renders and discards the spliced IR), and then deliberately passes the ORIGINAL, pre-splice plan to PlanText.single for typing — the comment at StatementExecutor.java:648-650 states this as intent ("physical typing needs the real from tree"). PlanText.resultColumns therefore takes the star-top branch and calls resolveStarColumn, which descends THROUGH the (unspliced) left subselect to personTable.FIRSTNAME and spells VARCHAR(200). In real legend-engine the placeholder relation is already in the query when resultColumns is computed, and every VarSetPlaceHolder column is constructed with an arbitrary `Integer` datatype, so every tdsVar-sourced result column prints INT. Hence the single divergence: expected `("firstName", INT)`, got `("firstName", VARCHAR(200))`. The `type = TDS[...]` line is unaffected in both engines because it is built from the TDS column metadata (PathInformation.relationalType / TDSColumn.sourceDataType), not from the SQL operation elements — which is why the golden shows firstName as VARCHAR(200) on the type line and INT on the resultColumns line at the same time.

**Fix**

Make the terminal (and any spliced Allocation) node type its resultColumns over the SPLICED plan, and teach the plan printer that a VarSetPlaceholder column is INT. Concretely: (1) In PlanText, split spliceLeftVar into `public static @Nullable SqlSelect spliceLeftVarQuery(SqlQuery plan, String var)` returning the spliced select (the body currently at PlanText.java:495-512 minus the final render) and keep `spliceLeftVar(plan, var, renderer)` as `q == null ? null : renderer.render(q)`. (2) Add an overload `PlanText.single(ctx, rootClassFqn, mappingFqn, SqlQuery typePlan, SqlQuery colsPlan, String sql, body, connectionName, chainMappings)` where `typeBlock`/`tdsTuples` keep using `typePlan` and only `resultColumns(ctx, impl[2], colsPlan, rrt)` uses `colsPlan`; the existing 3 overloads delegate with `colsPlan = typePlan` so no other caller changes. (3) In PlanText.resolveStarColumn (line 675) and resolvePhysical (line 718) add `case SqlSource.VarSetPlaceholder vp -> { if (vp.outputs().stream().anyMatch(o -> o.name().equalsIgnoreCase(col))) return new String[]{PLACEHOLDER, col}; }` with a private sentinel constant, and in resultColumns (line 528) spell `INT` when the resolution returns that sentinel instead of doing the findTableDefinition lookup — engine parity with pureToSQLQuery.pure:584. (4) In StatementExecutor.crossDbTdsPlan, compute `SqlQuery aColsPlan = prevVar == null ? aEs.plan() : PlanText.spliceLeftVarQuery(aEs.plan(), prevVar)` and pass it as `colsPlan` at line 737-739, and `PlanText.spliceLeftVarQuery(fullEs.plan(), prevVar)` as `colsPlan` at line 751-753. Do NOT change the typeBlock arguments at lines 734-736 and 760-762 — the TDS type line must keep resolving physically (engine keeps it via `paths`).

**How legend-engine does it** — /Users/neemsandv/legend/legend-engine/legend-engine-xts-relationalStore/legend-engine-xt-relationalStore-generation/legend-engine-xt-relationalStore-pure/legend-engine-xt-relationalStore-core-pure/src/main/resources/core_relational/relational/pureToSQLQuery/pureToSQLQuery.pure:581-585 — the PlanSetPlaceHolder arm builds `^VarSetPlaceHolder(varName=..., columns=$v.tdsColumns->map(c|^Column(name=$c.name, type=^...datatype::Integer())), paths=...PathInformation(type=$c.type, relationalType=$c.sourceDataType))`: the COLUMNS are unconditionally Integer, the true relational type survives only in `paths`. relationalMappingExecution.pure:229 computes `resultColumns` via `$c->getRelationalTypeFromRelationalOperationElement()`, and relationalMappingExecution.pure:260-261 resolves a TableAliasColumn to `$t.column.type` — i.e. the placeholder's Integer. relationalMappingExecution.pure:73-95 (TDSSelectQueryToTDSResultType) builds the `type = TDS[...]` line from `$query.paths`, which is why that line keeps VARCHAR(200).

**Risk** — resolveStarColumn/resolvePhysical are shared by tdsTuples (the `type = TDS[...]` line); adding the placeholder arm is harmless there only because tdsTuples is never handed a spliced plan under this fix — if a later caller passes one, the type line would silently become INT. Guard by keeping the sentinel private and only consulting it from resultColumns. Tenet-2 trap: do NOT special-case the column name or the string 'tdsVar' in the harness or in the golden comparison; the INT comes from the placeholder relation's column type, which is platform state.

**Also unblocks** — tdsJoinTwoDBExtend and tdsJoinTwoDBWithColumnMappedViaJoins (byte-identical divergence — placeholder columns typed physically instead of INT). Likely also tdsTwoJoinThreeDB, whose [ERROR] "No value present" is a NoSuchElementException consistent with PlanText's `ctx.findTableDefinition(...).orElseThrow()` / column `.orElseThrow()` failing when a three-DB spine resolves a column into a different database — the placeholder arm removes exactly those cross-database physical lookups; verify separately.

**Falsifier** — The dossier truncates `got` immediately after `sql = select "tdsvar_0`, so a second divergence inside the terminal SQL text cannot be ruled out. Cheapest check: run only `tdsJoinTwoDBExtend` with `LL_TMP_DEBUG=1` and read the `[plan-golden] ... firstDiff@<n> E<...> G<...>` line that PlanAsserts.planGoldenDebug prints (PlanAsserts.java:146-161). If firstDiff lands inside `resultColumns`, this diagnosis holds; if a second firstDiff appears inside the `sql =` text after the fix, the SQL renderer has an independent divergence.

<details><summary>Evidence read (8 citations)</summary>

- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/StatementExecutor.java:648 — javadoc of crossDbTdsPlan: "Type and resultColumns for the terminal resolve over the ORIGINAL (pre-splice) plan — physical typing needs the real from tree; only the SQL TEXT renders the placeholder form."
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/StatementExecutor.java:751 — the terminal node is printed with `PlanText.single(env.ctx(), rootClass, mappingFqn, fullEs.plan(), splicedSql, ...)`: the un-spliced `fullEs.plan()` for typing, the spliced text for `sql =`.
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/plan/PlanText.java:509 — spliceLeftVar builds the spliced SqlSelect and immediately `renderer.render(...)`s it; the spliced IR is never returned to the caller.
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/plan/PlanText.java:540 — `starTop = s.projections().isEmpty() || ...Star`; the cross-db terminal IR top has empty projections (that is precisely the precondition of EngineStyleH2.wrapTdsJoinTop at line 241-247, which is what renders the `select "tdsvar_0".firstName ... from (select * from (${tdsVar}) ...)` shape), so resultColumns takes the star branch.
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/plan/PlanText.java:692 — resolveStarColumn's Subselect arm resolves a named projection THROUGH to its physical column ("the engine types resultColumns by the physical store column"), yielding personTable.FIRSTNAME -> VARCHAR(200).
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/plan/PlanText.java:675 — resolveStarColumn has arms for Table / Join / Subselect only; there is no SqlSource.VarSetPlaceholder arm, so a spliced plan would currently throw "star-top TDS column ... resolves through no FROM-tree table".
- /Users/neemsandv/.claude/jobs/5671074c/tmp/burndown/dossiers/executionPlan__tests.md:627 — the full got text: identical to expected up to `resultColumns = [("firstName", VARCHAR(200)), ("eID", INT), ("fID", INT), ("legalName", VARCHAR(200))]` where expected is `[("firstName", INT), ("eID", INT), ("fID", INT), ("legalName", VARCHAR(200))]`.
- /Users/neemsandv/.claude/jobs/5671074c/tmp/burndown/dossiers/executionPlan__tests.md:466 — tdsJoinTwoDBWithColumnMappedViaJoins' golden proves the rule is 'all placeholder columns are INT', not 'physical type': managerID is a computed CASE typed `""` in the Allocation node but INT in the terminal node.

</details>

---

## `testCrossDbPlanGenerationWithRelationFromWithOnlyRuntimes`

| | |
|---|---|
| family | `executionPlan/tests` |
| sweep status | SHAPE |
| **verdict** | **MISSING FEATURE** |
| effort | XL |
| confidence | high |

**Root cause**

The query is `#>{dbInc.personTable}#->project(~[…])->from($runtime1)->join(#>{dbInc.personTable}#->project(~[…])->from($runtime2), JoinKind.LEFT, {x,y|…})` under the TWO-ARG `executionPlan(f, extensions)` overload. There is NO mapping anywhere: the `->from` calls carry a RUNTIME only, and the roots are relation-store accessors, not class extents. `StatementExecutor.planToString` requires a mapping FQN before it can do anything: args[1] is not a TypedPackageableRef (it is the `relationalExtensions()` TypedNativeCall), so it falls to `firstFromMapping` (null — `fr.mapping()` is empty on a runtime-only from) and then `firstFromChainMappings` (empty), and throws the observed wall at :576-581. This is an honest wall, not a miscomputation: the mapping-less relation-plan route genuinely does not exist. The real engine's 2-arg entry routes with NO mapping and only stuffs a dummy `^Mapping(name='dummy')` and empty `^Runtime()` into the ExecutionPlan record for bookkeeping.

**Fix**

Make the plan path mapping-optional, mirroring the engine's 2-arg entry. In `StatementExecutor.planToString`: when no mapping can be found AND the query's roots are relation-store accessors (`#>{db.T}#` / tableReference), do not throw — carry `mappingFqn = null` and derive each cluster's store from its accessor, and each cluster's CONNECTION from its own `->from($runtimeN)` argument (`connectionNameOf` already reads a runtime instance; here it must be read PER from(), not once for the whole plan — this golden prints `RelationalDatabaseConnection(type = "H2")`, not the default TestDatabaseConnection). Then route through the same cross-store splitter as `crossDbTdsPlan` (StatementExecutor.java:651-765) but keyed on DIFFERENT RUNTIMES rather than different stores — `streamStoreOf` (:2829-2847) compares stores and would report the same store for both sides here, so it needs a runtime-identity comparison. Finally the TDS tuple line must spell precise primitives (`meta::pure::precisePrimitives::Varchar`) for relation-accessor columns, which `PlanText.pureTypeName` does not do today. Realistically this is a new capability, not an edit; until it lands the wall is the correct behaviour and should stay loud.

**How legend-engine does it** — legend-engine .../core/pure/executionPlan/executionPlan_generation.pure:151-158 (mapping-less routing + dummy Mapping/Runtime record)

**Risk** — Tenet-2 trap: it is tempting to make the wall go away by defaulting mappingFqn to some arbitrary mapping in the module, or by having the harness inject one. That would produce a plan text keyed to a mapping the query never named — wrong rows dressed as a pass. Keep the wall until the mapping-less route exists.

**Falsifier** — If `firstFromMapping` in fact returns a mapping for this query (i.e. `TypedFrom.mapping()` is populated from a runtime-only from), the wall would not have named `TypedNativeCall` at args[1]. Cheapest check: dump the typed `TypedFrom` for `->from($runtime1)` and confirm `mapping()` is empty and `chainMappings()` is empty.

<details><summary>Evidence read (7 citations)</summary>

- core/src/main/java/com/legend/StatementExecutor.java:543-582 — the mapping-resolution ladder: TypedPackageableRef at args[1] → firstFromMapping → firstFromChainMappings → the wall `executionPlan mapping argument must be a reference (or the query must carry ->from), got TypedNativeCall` (the exact observed text, and TypedNativeCall is what `relationalExtensions()` is)
- core/src/main/java/com/legend/StatementExecutor.java:805-818 — `firstFromMapping` only returns when `fr.mapping().isPresent()`; a runtime-only `->from($runtime1)` yields null
- core/src/main/java/com/legend/StatementExecutor.java:788-801 — `firstFromChainMappings` returns empty for a from with no chainMappings
- core/src/main/java/com/legend/plan/PlanText.java:52-59 — even past the wall, `single()` needs a mappingFqn for `ScanRelations.rootImpl`, so the mapping is load-bearing all the way down
- legend-engine .../core/pure/executionPlan/executionPlan_generation.pure:25-28 and :151-158 — the 2-arg `executionPlan(f, extensions)` delegates to the context form, which calls `routeFunction($contextWithEnumPushDown, $extensions, …)` with NO mapping and then passes `^Mapping(package=meta::pure::executionPlan, name='dummy')` and `^meta::core::runtime::Runtime()` purely to fill the ExecutionPlan record
- legend-engine .../executionPlan/tests/executionPlanTest.pure:2025-2075 — the golden: `Sequence(Allocation(name=tdsVar, value=(Relational(… connection = RelationalDatabaseConnection(type = "H2")))) Relational(…))` with TDS columns typed `meta::pure::precisePrimitives::Varchar`/`Int`
- core/src/main/java/com/legend/compiler/spec/CoreFn.java:38-40 — `#>{db.TABLE}#` (TABLE_REFERENCE / tableToTDS) is a supported accessor, so the query itself is expressible; only the PLAN route is missing

</details>

---

## `testDatabaseConnectionSQLPopulation`

| | |
|---|---|
| family | `executionPlan/tests` |
| sweep status | ERROR |
| **verdict** | **MISSING FEATURE** |
| effort | M |
| confidence | high |

**Root cause**

Two-layer gap, both real. (1) TYPING: legend-lite declares the plan node as `native Class meta::relational::mapping::SQLExecutionNode extends ExecutionNode { sqlQuery: String[1]; sqlComment: String[0..1]; }` (Pure.java:516). The real engine class also declares `connection: DatabaseConnection[1]` (executionPlan.pure:69). So `->cast(@SQLExecutionNode).connection` reaches Typer's ClassType property arm, `ctx.findProperty` misses, and it throws the observed `class ... has no property 'connection'` (Typer.java:2565). Because assertSize is not a plan-text assert, the exception is not caught by PlanAsserts' plan-wall try/catch and surfaces as [ERROR]. (2) VALUE: even with the property declared, nothing can answer it. `com.legend.plan.PlanNode` is a 4-field record (kind, children, sqlQuery, functionParameters) with no connection slot (PlanNode.java:20-21); `planModel` builds the SQLExecutionNode with only `es.sql()` (StatementExecutor.java:2012-2014); and `walkProp`'s PlanNode arm only serves rootExecutionNode/executionNodes/sqlQuery/functionParameters (StatementExecutor.java:1834-1842). Finally the ASSERTED VALUE (58) is the engine's `processRuntimeTestConnections` store-contract hook (storeContract.pure:110-146): a RelationalDatabaseConnection whose datasourceSpecification is a LocalH2DatasourceSpecification gets `testDataSetupSqls = testDataSetupCsv->setUpDataSQLs(connectionStore.element)`. With csv='' that is schemaAndTableSetup only (toDDL.pure:155-195): drop+create per schema of `db->allSchemas()` (RECURSIVE through includes, merged by name — databaseHelperFunctions.pure:96-108) plus drop+create per table. For meta::relational::tests::db that is 2 schemas (default, productSchema) x2 = 4 plus 27 tables (15 db default + 10 dbInc default + productTable + synonymTable) x2 = 54, i.e. exactly 58. legend-lite has the text generator (`Ddl.setUpDataSqlsText`, Ddl.java:112-135) but it walks only `db.schemas()`/`db.tables()` and never `db.includes()`, so it would produce 36, not 58.

**Fix**

Four coordinated edits. (1) core/src/main/java/com/legend/builtin/Pure.java:516 — extend SQL_EXECUTION_NODE to the engine's member set, minimally adding `connection: meta::external::store::relational::runtime::DatabaseConnection[1];` (engine also has resultColumns/metadata/onConnectionClose*/isResultColumnsDynamic/isMutationSQL — add only `connection` unless another corpus test demands more). (2) core/src/main/java/com/legend/plan/PlanNode.java — add a nullable `Object connection` component (or a dedicated `ConnH` record: kind simple-name, DatabaseType string, datasourceSpecification handle, testDataSetupSqls list) and thread it through the canonical constructor. (3) core/src/main/java/com/legend/StatementExecutor.java: in `planModel` (line ~1990-2014) reuse the existing `connectionInstanceOf(rtArg2)` (line 1114) to get the connection TypedNewInstance, additionally locate the enclosing `^ConnectionStore` and read its `element` TypedPackageableRef to get the store FQN, then emulate storeContract's `processRuntimeTestConnections`: for a TestDatabaseConnection with a non-empty `testDataSetupCsv` and empty `testDataSetupSqls`, set testDataSetupSqls = Ddl.setUpDataSqlsText(csv, ctx.findDatabase(storeFqn)); for a RelationalDatabaseConnection whose datasourceSpecification is a LocalH2DatasourceSpecification, do the same on the spec and CONCATENATE onto its declared testDataSetupSqls. Attach the resulting handle to the SQLExecutionNode PlanNode. Add walkProp arms: PlanNode `case "connection" -> pn.connection()`, and on the connection handle `datasourceSpecification`, `testDataSetupSqls`, `testDataSetupCsv`, `type`. (4) core/src/main/java/com/legend/exec/Ddl.java:112 — make the schema/table enumeration include-closure aware, mirroring the engine's allSchemas: recursively gather this db and every db in `includes()`, GROUP SCHEMAS BY NAME (so `default` and `productSchema` each emit exactly one drop/create pair) and union their tables de-duplicated by name. Do this by passing a ModelContext (or a pre-resolved List<DatabaseDefinition>) rather than a single DatabaseDefinition.

**How legend-engine does it** — /Users/neemsandv/legend/legend-engine/legend-engine-xts-relationalStore/legend-engine-xt-relationalStore-generation/legend-engine-xt-relationalStore-pure/legend-engine-xt-relationalStore-core-pure/src/main/resources/core_relational/relational/executionPlan/executionPlan.pure:69 — `connection: DatabaseConnection[1];` on SQLExecutionNode; population at .../relational/contract/storeContract.pure:112-136 (processRuntimeTestConnections, the LocalH2DatasourceSpecification arm); statement generation at .../relational/sqlQueryToString/DDL/toDDL.pure:155-195 (schemaAndTableSetup + setUpDataSQLs); recursive schema merge at .../relational/helperFunctions/databaseHelperFunctions.pure:96-108 (allSchemas / allSchemasRecursive).

**Risk** — Making Ddl.setUpDataSqlsText include-aware changes every caller: SeedSqlForms.assertForm (SeedSqlForms.java:46,58) and TestDataGenerator's assertTestData path. The DDL golden family (engine testDDL.pure:39,84,126) uses `meta::relational::tests::dbInc`, which has NO includes, so those goldens are unaffected; testDataGeneration's assertTestData compares two setUpDataSQLs outputs to each other, so it is symmetric. Tenet-2 trap: do NOT special-case the number 58 or hard-code the connection text in the harness — the plan model must actually carry the connection object, otherwise the next test that reads `.type` or `.datasourceSpecification` re-breaks.

**Also unblocks** — meta::pure::executionPlan::tests::datetime::testPlanWithLocalH2ConnectionWithSQL (executionPlanTest.pure:2630) reads the same SQLExecutionNode.connection -> LocalH2DatasourceSpecification.testDataSetupSqls chain (asserting the pass-through ['a','b'], i.e. no CSV expansion); testCrossStoreGraphFetch.pure:1039 reads the same .connection hop through a graph-fetch node.

**Falsifier** — Compute Ddl.setUpDataSqlsText("", db) over the include closure of meta::relational::tests::db and count: if it is not exactly 58 statements (4 schema + 54 table), the counting model (2 merged schemas, 27 tables, views excluded) is wrong and the fix will not satisfy assertSize.

<details><summary>Evidence read (8 citations)</summary>

- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/builtin/Pure.java:516 — SQL_EXECUTION_NODE declares only sqlQuery + sqlComment; no `connection`.
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/compiler/spec/Typer.java:2565 — `throw new TypeInferenceException("class " + ct.fqn() + " has no property '" + ap.property() + "'")`, the exact observed message.
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/plan/PlanNode.java:20 — `record PlanNode(String kind, List<PlanNode> children, String sqlQuery, List<Param> functionParameters)`; no connection slot.
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/StatementExecutor.java:1834 — walkProp's PlanNode switch has arms only for rootExecutionNode/executionNodes/sqlQuery/functionParameters, `default -> null`.
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/StatementExecutor.java:2012 — planModel constructs `new PlanNode("SQLExecutionNode", List.of(), es.sql(), List.of())`.
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/exec/Ddl.java:113 — setUpDataSqlsText loops `for (var sc : db.schemas())` and `for (var t : db.tables())`; `db.includes()` is never consulted.
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/builtin/Pure.java:264 — LOCAL_H2_DATASOURCE_SPECIFICATION already declares testDataSetupCsv + testDataSetupSqls, so only the SQLExecutionNode hop is missing for this test.
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/compiler/ModelBuilder.java:907 — legend-lite resolves includes by explicit recursion per lookup (findFilter/findJoin/findView); DatabaseDefinition is NOT include-flattened.

</details>

---

## `testDatabaseConnectionSQLPopulationLegacy`

| | |
|---|---|
| family | `executionPlan/tests` |
| sweep status | ERROR |
| **verdict** | **MISSING FEATURE** |
| effort | S |
| confidence | high |

**Root cause**

Same first wall as the non-legacy twin — `SQLExecutionNode` has no `connection` property in legend-lite's native catalog (Pure.java:516) so Typer.java:2565 throws before anything runs. The legacy variant then needs ONE more declaration the twin does not: it casts to `TestDatabaseConnection` and reads `.testDataSetupSqls`, but legend-lite's TEST_DATABASE_CONNECTION declares only `testDataSetupCsv` (Pure.java:258), while real pure declares both `testDataSetupSqls : String[*]` and `testDataSchemas : Schema[*]` on that class (legend-pure relationalRuntime.pure:117-121). So after fixing `connection`, this test would hit a SECOND identical TypeInferenceException on `testDataSetupSqls`. The value side is the same engine hook: storeContract's processRuntimeTestConnections TestDatabaseConnection arm sets testDataSetupSqls = testDataSetupCsv('')->setUpDataSQLs(connectionStore.element = meta::relational::tests::db), i.e. the 58 schema/table DDL statements.

**Fix**

Do the four edits listed for testDatabaseConnectionSQLPopulation, plus: core/src/main/java/com/legend/builtin/Pure.java:258 — extend TEST_DATABASE_CONNECTION to `{ testDataSetupCsv: String[0..1]; testDataSetupSqls: String[*]; }` (add `testDataSchemas: meta::relational::metamodel::Schema[*];` only if a corpus test demands it). In the planModel connection-handle builder, the TestDatabaseConnection arm must mirror storeContract's `^$t(testDataSetupSqls = if($t.testDataSetupCsv->isEmpty(), |[], | if($t.testDataSetupSqls->isEmpty(), | setUpDataSQLs(csv, element), | $t.testDataSetupSqls)))` — note `''` is NOT empty in Pure (a one-element String collection), so the empty-string CSV in this test DOES take the setUpDataSQLs branch and yields all 58 DDL statements.

**How legend-engine does it** — /Users/neemsandv/legend/legend-engine/legend-engine-xts-relationalStore/legend-engine-xt-relationalStore-generation/legend-engine-xt-relationalStore-pure/legend-engine-xt-relationalStore-core-pure/src/main/resources/core_relational/relational/contract/storeContract.pure:115-120 — the TestDatabaseConnection arm of processRuntimeTestConnections; class declaration at /Users/neemsandv/legend/legend-pure/legend-pure-store/legend-pure-store-relational/legend-pure-m2-store-relational-pure/src/main/resources/platform_store_relational/relationalRuntime.pure:101-122.

**Risk** — Adding testDataSetupSqls to TestDatabaseConnection widens the ^TestDatabaseConnection(...) construction surface — the corpus's own testDatabaseConnection() helper and connection grammar already round-trip that key (ConnectionSectionGrammar.java:837), so no parser change is needed. Same tenet-2 trap as the twin: the value must come from the plan model, not from a harness-side special case.

**Also unblocks** — Shares its entire fix with testDatabaseConnectionSQLPopulation; also unblocks testPlanWithLocalH2ConnectionWithSQL (executionPlanTest.pure:2630).

**Falsifier** — Declare `connection` on SQLExecutionNode only and re-run: if this test then fails with `class meta::external::store::relational::runtime::TestDatabaseConnection has no property 'testDataSetupSqls'`, the two-declaration analysis is confirmed; if it fails elsewhere, the walk-side model is the binding constraint instead.

<details><summary>Evidence read (5 citations)</summary>

- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/builtin/Pure.java:258 — `TEST_DATABASE_CONNECTION = ... { testDataSetupCsv: String[0..1]; }` — no testDataSetupSqls.
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/builtin/Pure.java:516 — SQL_EXECUTION_NODE without `connection`, the reported failure.
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/compiler/spec/Typer.java:2565 — the throw site for the observed message.
- /Users/neemsandv/legend/legend-pure/legend-pure-store/legend-pure-store-relational/legend-pure-m2-store-relational-pure/src/main/resources/platform_store_relational/relationalRuntime.pure:117 — `testDataSetupSqls : String[*];` declared on TestDatabaseConnection (with the 'never populated manually, used exclusively in Legend Engine' doc tag).
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/StatementExecutor.java:1114 — connectionInstanceOf already dispatches on the exact TestDatabaseConnection/RelationalDatabaseConnection/DatabaseConnection FQNs, so the runtime-arg reader needed by the fix already exists.

</details>

---

## `testEnumPushDownWithExternalFormat`

| | |
|---|---|
| family | `executionPlan/tests` |
| sweep status | SHAPE |
| **verdict** | **MISSING FEATURE** |
| effort | XL |
| confidence | high |

**Root cause**

Identical mechanism to testRelationalProjectionWithExternalFormat and identical first wall: the `$extensions` let calls meta::external::format::shared::transformation::tests::exampleExternalFormatExtension(), which is defined only in legend-engine-core (core/pure/binding/transformation/tests/externalFormatContract.pure:112), outside the two roots Corpus.java loads; Typer.checkGeneric finds zero candidates and throws (Typer.java:1448), and PlanAsserts.planTextAssert converts it to the 'plan wall' SHAPE stamp (PlanAsserts.java:198). Beyond that shared wall this test adds a SECOND, independent requirement: its golden asserts the enum push-down CASE expression `case when "root".TYPE = 'CUSIP' then 'CUSIP' ... else null end as "name"` inside the externalized relational node — i.e. the PUSH_DOWN_ENUM_TRANSFORM behaviour, not just the externalize envelope. So even a complete external-format port would not make this pass unless enum push-down is right (the sibling test testExecutionPlanGenerationForLambdaFromWithEnumMapping, executionPlanTest.pure:2856, fails on exactly that CASE text with no external format involved, which is evidence the push-down path is separately broken).

**Fix**

Same as testRelationalProjectionWithExternalFormat — keep the wall; the external-format subsystem is genuinely absent. If it is ever scheduled, sequence it AFTER the enum push-down defect (tracked by testExecutionPlanGenerationForLambdaFromWithEnumMapping), because this test's golden embeds the pushed-down CASE expression and would fail a second time otherwise. Do not fix in isolation.

**How legend-engine does it** — /Users/neemsandv/legend/legend-engine/legend-engine-xts-relationalStore/legend-engine-xt-relationalStore-generation/legend-engine-xt-relationalStore-pure/legend-engine-xt-relationalStore-core-pure/src/main/resources/core_relational/relational/relationalMappingExecution.pure:86-88 — TDSSelectQueryToTDSResultType drops enumMappingId/enumMapping when contextHasFlag(Feature.PUSH_DOWN_ENUM_TRANSFORM), the switch that makes the CASE appear in SQL instead of a post-hoc enum map.

**Risk** — Ledgering this is correct; the trap would be to declare it 'fixed' after only porting the extension function, since the enum push-down assertion is orthogonal and would then fail with a confusing diff.

**Also unblocks** — Shares the external-format prerequisite with testRelationalProjectionWithExternalFormat (executionPlanTest.pure:2647).

**Falsifier** — Add core/pure/binding as a Corpus root and re-run: if the failure changes to a text diff on the CASE expression rather than passing, the two-layer reading is confirmed.

<details><summary>Evidence read (5 citations)</summary>

- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/test/java/com/legend/rcorpus/Corpus.java:49 and :57 — the only two corpus source roots; core/pure/binding is not among them.
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/compiler/spec/Typer.java:1448 — the zero-candidate throw producing the observed message.
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/harness/PlanAsserts.java:198 — plan-wall stamping for planToString goldens.
- /Users/neemsandv/legend/legend-engine/legend-engine-core/legend-engine-core-pure/legend-engine-pure-code-compiled-core/src/main/resources/core/pure/binding/transformation/tests/externalFormatContract.pure:112 — the missing function's definition site.
- dossier /Users/neemsandv/.claude/jobs/5671074c/tmp/burndown/dossiers/executionPlan__tests.md (testExecutionPlanGenerationForLambdaFromWithEnumMapping, source executionPlanTest.pure:2856) — 'assert did not hold (false)' on the same enum push-down CASE text with no external format in play, so the push-down half is independently unsatisfied.

</details>

---

## `testExecutionPLanGenerationForFromInAllocation`

| | |
|---|---|
| family | `executionPlan/tests` |
| sweep status | FAIL |
| **verdict** | **REAL DEFECT** |
| effort | S |
| confidence | high |
| **adversarial review** | **CONFIRMED** |

**Root cause**

Two envelope bugs in `StatementExecutor.sequencePlan`, both visible in the HEAD-recorded actual output. The function body is ONE expression, `let var = _Firm.all()->from(relationalMapping, ^Runtime(...))`. Commit 787c391b correctly routed a lone LET to `sequencePlan` (StatementExecutor.java:603-607), but sequencePlan then (a) never turns it into an Allocation — its allocation loop runs `for i < lam.body().size() - 1`, which is empty for a single statement, so the let falls through to the TERMINAL arm and is printed as a bare `Relational` child (:865-888); and (b) wraps whatever children it produced in `PlanText.sequence(...)` unconditionally (:891-897, PlanText.java:138-150), even for a single child. The engine does neither: `executionPlan` emits a Sequence ONLY when `$clusters->size() != 1` (executionPlan_generation.pure:180-200), and a `let` cluster is processed by `letFunctionProcessor` into an `AllocationExecutionNode` whose child is the value's node (platform/executionPlan_generation.pure:121-142). Result at HEAD: `Sequence(Relational(...))` where the golden is `Allocation(name=var, value=(Relational(...)))`. The inner Relational's type block, resultColumns and SQL are already byte-identical to the golden — the whole diff is the envelope.

**Fix**

In `StatementExecutor.sequencePlan` (:825-898): (1) treat a TRAILING TypedLet as an Allocation — change the loop bound to cover every statement and, when the last statement is a `TypedLet`, append `allocationNode(let, mappingFqn, specs, env, params, quote, timeZone, dbType)` instead of the `PlanText.single(...)` terminal; the envelope's type block then comes from that node's own result type (engine: `resultType = $allNodes->last().resultType`, executionPlan_generation.pure:195-197). (2) Drop the Sequence envelope when there is exactly one child: `if (children.size() == 1) return new ExecutionResult.Scalar(children.get(0), Type.Primitive.STRING);` before the `PlanText.sequence(...)` call. This is faithful because a parameterized lambda always contributes a FunctionParametersValidationNode as a second child, so it still gets its Sequence — exactly the engine's `addFunctionParametersValidationNode` rule. No PlanText change is needed; `allocation()` and the class-envelope `typeBlock()` already emit the golden's exact bytes.

**How legend-engine does it** — legend-engine .../core/pure/executionPlan/executionPlan_generation.pure:180 (single cluster ⇒ no Sequence) and .../core/pure/platform/executionPlan/executionPlan_generation.pure:136 (letFunctionProcessor ⇒ AllocationExecutionNode)

**⚠ Correction from adversarial review** — Substance is right; the wording of step (1) would break a passing test if applied literally. Do NOT just "change the loop bound to cover every statement": the loop body at :866-870 throws NotImplementedException("plan: non-let intermediate statement") for any non-let, and testExecutionPLanGenerationForFromWithMultiClusters (currently passing) has a non-let LAST statement — extending the bound unguarded makes it throw. Correct shape: keep the loop at size()-1, then branch on the terminal — `if (term instanceof TypedLet tl) { children.add(allocationNode(tl, mappingFqn, specs, env, params, quote, timeZone, dbType)); } else { …existing rootClass/engineSql/PlanText.single terminal… }`. Second gap: the Sequence envelope's type block at :889-895 is built from `rootClass`/`es`/`List.of(term)`, none of which exist on the let branch, so the code will not compile as described unless you either (a) return early `if (children.size() == 1) return new ExecutionResult.Scalar(children.get(0), Type.Primitive.STRING);` immediately after appending the allocation and before reaching :889 — which is safe here because the only way to reach sequencePlan with a trailing let and >1 child is params + lone let, a combination no corpus test exercises — or (b) explicitly compute the envelope block from let.value() for that case. Option (a) is the smaller change and is what I would plan for. Effort S stands.

<details><summary>Adversarial review notes (CONFIRMED)</summary>

Mechanism fully confirmed; every cited line resolves and says what is claimed. Diffing the corpus payload (RELATIONAL_CORPUS.md:1158) shows the entire delta is the envelope: golden is a bare top-level `Allocation` with `name = var` / `value =`, HEAD emits `Sequence` with the Relational as its lone child; the inner Relational's type block, resultColumns, sql and connection are identical modulo the 2-space indent shift. Both claimed bugs are real. (a) StatementExecutor.java:865 is literally `for (int i = 0; i < lam.body().size() - 1; i++)`, so a single-statement body never enters the allocation loop and the lone let falls through to the terminal arm at :877-888 which unconditionally emits `PlanText.single(...)` — a Relational. (b) :891-897 always wraps in `PlanText.sequence(...)`, and PlanText.java:144-152 always emits `Sequence\n(\n<typeBlock>  (\n…)\n)\n`. The engine really does neither: executionPlan_generation.pure:179-182 is `let node = if ($clusters->size() == 1, | $clusters->at(0)->plan(...), | … ^SequenceExecutionNode(...))`, and platform/executionPlan_generation.pure:121-142 letFunctionProcessor emits `^AllocationExecutionNode(varName=…, resultType=$subPlan.resultType, resultSizeRange=$subPlan.resultSizeRange, executionNodes=$subPlan)`. The claim that a parameterized lambda still gets a Sequence is confirmed at executionPlan_generation.pure:249-257: the FunctionParametersValidationNode is prepended and a non-Sequence node is wrapped `^SequenceExecutionNode(executionNodes=[$fpvn, $e], …)`, and only when `$planVarPlaceHolders->isNotEmpty()`. I also verified the machinery the fix reuses actually produces the golden bytes: TypedLet.info() IS value.info() (TypedLet.java javadoc + record), so for `let var = _Firm.all()->from(...)` the type is ClassType with multiplicity *, which routes allocationNode to the CLASS arm at :941-951; that arm's typeBlock (PlanText.java:125-128) emits `type = Class[impls=(… | relationalMapping.…)]` / `as …` / `resultSizeRange = *`, and its inner `PlanText.single` uses the 6-arg overload whose default connection (PlanText.java:31-36) is exactly `TestDatabaseConnection(type = "H2")` — the golden's connection line. So the fix does land on the golden. Effort S is right: one function, no new IR node, no golden realignment.

</details>

**Citation issues found in review** — One imprecision: the diagnosis cites executionPlan_generation.pure:195-197 (`resultType = $allNodes->last().resultType`) as the source of "the envelope's type block" for the Allocation. That line is inside the MULTI-cluster ^SequenceExecutionNode branch and has nothing to do with a single-cluster Allocation; the Allocation's resultType actually comes from `$subPlan.resultType` in letFunctionProcessor (platform/executionPlan_generation.pure:136). The conclusion is unaffected — legend-lite's allocationNode already derives its type block from the let's value — but the cited line does not say what it is used to support.

**Risk** — The single-child unwrap must not fire for the FunctionParametersValidationNode case (it cannot: that is always 2+ children) nor for cross-store plans (those go through crossDbTdsPlan, a different function, which builds its own Sequence with ≥2 children). Re-sweep the ~62 passing executionPlan tests: any test whose lambda is a single non-let statement never enters sequencePlan at all, so the exposure is limited to single-let bodies.

**Also unblocks** — Any plan golden whose whole body is one `let` (the engine prints a bare Allocation for all of them). The burndown pairs this with testQuoteIdentifiersFlagWithGraphFetch, but that one needs graph-fetch node vocabulary and would NOT be fixed by this change.

**Falsifier** — If, after the change, the printed Allocation's type block differs from the golden's (`type = Class[impls=…]` / `as …` / `resultSizeRange = *`), then `allocationNode`'s class-valued arm is not producing what the multi-cluster test's nested Allocation produces — but docs/RELATIONAL_CORPUS.md:1158 shows the inner node is already byte-identical, and testExecutionPLanGenerationForFromWithMultiClusters passes with the same Allocation shape, so that is unlikely.

<details><summary>Evidence read (12 citations)</summary>

- core/src/main/java/com/legend/StatementExecutor.java:603-610 — the lone-LET gate added by 787c391b routes to sequencePlan
- core/src/main/java/com/legend/StatementExecutor.java:865-876 — `for (int i = 0; i < lam.body().size() - 1; i++) { … children.add(allocationNode(let, …)) }` — a single-statement body never enters this loop
- core/src/main/java/com/legend/StatementExecutor.java:877-897 — the terminal is always printed via `PlanText.single(...)` (a Relational node) and the children are always wrapped by `PlanText.sequence(...)`
- core/src/main/java/com/legend/plan/PlanText.java:138-150 — `sequence()` always emits `Sequence\n(\n<typeBlock>  (\n …children… )\n)\n`
- core/src/main/java/com/legend/StatementExecutor.java:905-975 — `allocationNode` already produces exactly the golden's Allocation shape for a CLASS-typed let (:941-951: class-envelope type block + inner `PlanText.single`), so the machinery exists and is unused for the trailing statement
- core/src/main/java/com/legend/plan/PlanText.java:157-170 — `allocation()` prints `Allocation\n(\n<typeAndSize>  name = …\n  value = \n    (\n<inner>    )\n)\n`, matching the golden line for line
- docs/RELATIONAL_CORPUS.md:1158 — HEAD-current actual, verbatim: `got Sequence\n(\n  type = Class[impls=(…_Firm | relationalMapping.…)]\n         as …_Firm\n  resultSizeRange = *\n  (\n    Relational\n(…identical inner node…)\n  )\n)`; the inner resultColumns/sql/connection match the golden exactly
- legend-engine .../core/pure/executionPlan/executionPlan_generation.pure:180-200 — `let node = if ($clusters->size() == 1, | $clusters->at(0)->plan(...), | … ^SequenceExecutionNode(...))`
- legend-engine .../core/pure/platform/executionPlan/executionPlan_generation.pure:121-142 — `letFunctionProcessor` → `^AllocationExecutionNode(varName=…, resultType=$subPlan.resultType, executionNodes=$subPlan)`
- legend-engine .../core/pure/executionPlan/executionPlan_generation.pure:250-256 — the FunctionParametersValidationNode is what wraps a non-Sequence node INTO a Sequence, and only when the function has parameters
- legend-engine .../executionPlan/tests/executionPlanTest.pure:2266-2290 — the golden: a bare top-level `Allocation` with `name = var`
- legend-engine .../executionPlan/tests/executionPlanTest.pure:2292-2340 — the sibling two-statement test testExecutionPLanGenerationForFromWithMultiClusters expects Sequence(Allocation, Relational) and is NOT in the failing corpus, proving the Allocation rendering and SQL are already right

</details>

---

## `testExecutionPlanGenerationForInWithVarAndConstantInputs`

| | |
|---|---|
| family | `executionPlan/tests` |
| sweep status | FAIL |
| **verdict** | **MISSING FEATURE** |
| effort | L |
| confidence | high |

**Root cause**

legend-lite implements NO equivalent of the engine's first default plan post-processor, `meta::relational::postProcessor::processInOperation`. In the engine, plan generation runs postProcessorList() (defaultPostProcessor.pure:36-44) with processInOperation first; for a TestDatabaseConnection the run is a 'test run' so the collection threshold is 50 (processInOperation.pure:34-38). This query's in-list is [$name] + 'John','Peter','1'..'50' = 53 literals > 50, so transformNotRequiringConditionNode rewrites the DynaFunction 'in' second parameter into a select over temp table `tempTableForIn_<uniqueId>` (processInOperation.pure:68-79), and generatePostProcessorResult emits an AllocationExecutionNode named `tempVarForIn_4` holding a ConstantExecutionNode of the 52 non-VarPlaceHolder values plus a CreateAndPopulateTempTableExecutionNode with inputVarNames=[name, tempVarForIn_4] (processInOperation.pure:126-148). Because a CreateAndPopulateTempTable node exists, generateExecutionNodeForPostProcessedResult wraps the whole thing in a RelationalBlockExecutionNode instead of a SequenceExecutionNode (relationalMappingExecution.pure:64-68); and since RelationalBlockExecutionNode EXTENDS SequenceExecutionNode, addFunctionParametersValidationNode PREPENDS the FPVN INTO that block rather than creating an outer Sequence (executionPlan_generation.pure:253-255) — which is exactly the golden's shape. legend-lite's sequencePlan (StatementExecutor.java:825-895) unconditionally emits PlanText.sequence(FPVN, lets-as-Allocations, terminal Relational) and has no post-processing hook at all, so the in-list is simply rendered inline by the dialect's IN arm (EngineStyleH2.java:1127-1170). Grep confirms zero occurrences of collectionThreshold / 32767 / tempTableForIn / inFilterClause / CreateAndPopulateTempTable / FreeMarkerConditional anywhere in core/src/main/java.

**Fix**

Implement the in-collection post-processor as a PLAN-CHANNEL pass, not a harness patch. (1) New class com.legend.plan.InOperationPostProcessor: given the lowered SqlSelect, the connection descriptor (type + isTestRun) and the plan params, walk the IR for IN nodes whose right side is a literal list or a PlanParam. isTestRun = connection is TestDatabaseConnection, or RelationalDatabaseConnection with a LocalH2DatasourceSpecification; threshold = 50 when isTestRun, else the dialect's collectionThresholdLimit (DB2 = 32767). (2) Case A (this test): literal-list size > threshold — replace the IN right side with a temp-table subselect `select "<lowercased-temptable>_0".ColumnForStoringInCollection as ColumnForStoringInCollection from tempTableForIn_<id> as "<lowercased>_0"`, and emit two nodes ahead of the Relational: Allocation(name=tempVarForIn_<id>, value=Constant(values=[the non-parameter literals])) and CreateAndPopulateTempTable(inputVarNames=[<param names>, tempVarForIn_<id>], tempTableName=tempTableForIn_<id>, tempTableColumns=[(ColumnForStoringInCollection, VARCHAR(1024))], connection=<connName>). (3) Because a CreateAndPopulateTempTable exists, sequencePlan must emit `RelationalBlockExecutionNode` instead of `Sequence`, with the FPVN as its FIRST child — add PlanText.relationalBlock(...) plus printers for CreateAndPopulateTempTable and (for the sibling case) FreeMarkerConditionalExecutionNode. (4) The `<id>` suffix is the engine's transform-memo size at the visit (postProcessor.pure:122-135 passes `$transformed->getMapStats().size`); to match `tempTableForIn_4` you must reproduce that counter — number the IR nodes in the engine's transform traversal order and use the count of already-memoized distinct RelationalOperationElements at the IN node. Treat that as the hard part; if it cannot be reproduced faithfully, WALL the case (throw NotImplementedException from the plan channel) rather than emit a differently-numbered temp table.

**How legend-engine does it** — /Users/neemsandv/legend/legend-engine/legend-engine-xts-relationalStore/legend-engine-xt-relationalStore-generation/legend-engine-xt-relationalStore-pure/legend-engine-xt-relationalStore-core-pure/src/main/resources/core_relational/relational/postprocessor/defaultPostProcessor/processInOperation.pure:34-38 (threshold 50 for test runs), :68-79 (the >threshold literal-list rewrite), :126-160 (Allocation + CreateAndPopulateTempTable emission); wrapping at .../relational/relationalMappingExecution.pure:64-68; FPVN prepended into the block at /Users/neemsandv/legend/legend-engine/legend-engine-core/legend-engine-core-pure/legend-engine-pure-code-compiled-core/src/main/resources/core/pure/executionPlan/executionPlan_generation.pure:253-255.

**Risk** — Every existing plan-text golden that contains a large IN list would change shape once the threshold rule is live — sweep the plan goldens before landing. Tenet-2 trap: do NOT normalize the temp-table id (or strip it) in the harness comparison; the id is part of the engine's output and a mismatched id means the traversal model is wrong, which the golden should say out loud. Also note this is plan-text only — legend-lite's DuckDB execution path inlines the values and is unaffected.

**Also unblocks** — testFilterInWithResultSorcedFromAnExpression (executionPlanTest.pure:108) and meta::relational::tests::advanced testRelationalResultSourcing (tests/advanced/testRelationalResultSourcing.pure:62) both assert the same Allocation(inFilterClause_*) + FreeMarkerConditional + CreateAndPopulateTempTable shape.

**Falsifier** — Instrument (or reason through) the engine's transform memo for this query: if the IN DynaFunction is not the 5th distinct RelationalOperationElement visited (memo size 4), the uniqueId model is wrong and `tempTableForIn_4` cannot be reproduced by counting traversal order — in which case the correct move is a wall, not a guess.

<details><summary>Evidence read (5 citations)</summary>

- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/StatementExecutor.java:825 — sequencePlan; children are FPVN + per-let Allocation + terminal Relational only (lines 836-886), then PlanText.sequence at :892. No post-processor step exists.
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/plan/PlanText.java:143 — `sequence(...)` always writes the literal 'Sequence\n(\n'; the file has no RelationalBlockExecutionNode / CreateAndPopulateTempTable / FreeMarkerConditional printer (grep of PlanText.java shows only Allocation:161, Constant:222).
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/sql/dialect/EngineStyleH2.java:1155-1170 — the IN arm that renders the literal list inline, producing the observed `in ('${name?replace(...)}','John',...)`.
- grep for `collectionThreshold|32767|tempTableForIn|inFilterClause|CreateAndPopulateTempTable|FreeMarkerConditional` over /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java returns nothing — the surface is wholly absent.
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/StatementExecutor.java:1114 — connectionInstanceOf, the existing reader that would supply the connection type / test-run flag the threshold rule needs.

</details>

---

## `testExecutionPlanGenerationForLambdaFromWithEnumMapping`

| | |
|---|---|
| family | `executionPlan/tests` |
| sweep status | FAIL |
| **verdict** | **REAL DEFECT** |
| effort | M |
| confidence | medium |

**Root cause**

legend-lite applies the plan-text "keep enum columns RAW" reduction unconditionally. `StatementExecutor.engineSql` calls `PlanEnumForm.apply(sel, rt)` on EVERY plan whose terminal is a relation (StatementExecutor.java:463-468), and `PlanEnumForm.apply` (plan/PlanEnumForm.java:32-57) rewrites any enum-typed projection whose expression is a decode CASE back to its single raw store column. So `project([e|$e.type],['type'])` prints `"root".type`, and the assert `planString->contains('case when "root".type in (...) then 'CONTRACT' ...')` is false. That reduction is only correct for the executionPlan overloads that take a mapping/runtime argument. This test uses the 2-arg `executionPlan(func, extensions)` overload with `->from(mapping, runtime)` inside the query, and THAT overload in the engine unconditionally turns the enum push-down feature ON. There is a second, independent text gap: even with the reduction suppressed, legend-lite's decode chain spells a multi-source-value branch as an OR of equalities (`MappingNormalizer.translateEnumeratedSource` builds `disj = or(equal(src,'FTC'), equal(src,'FTO'))`, MappingNormalizer.java:3053-3068), so it would render `case when "root".type = 'FTC' or "root".type = 'FTO' then 'CONTRACT' ...` — not the golden's `in ('FTC', 'FTO')`. Nothing in the codebase folds an OR-of-equalities over one LHS back into SQL IN (`SqlFn.IN` is constructed only at lowering/Scalars.java:2286 and lowering/CalendarAgg.java:271/352).

**Fix**

Two changes. (1) Thread a `pushDownEnum` boolean through the plan path in core/src/main/java/com/legend/StatementExecutor.java: set it true in `planToString` exactly on the branch at :546-556 where `ep.args().get(1)` is NOT a TypedPackageableRef (the mapping-less / ->from-carrying overload), pass it down through `engineSql(...)`, and at :463-468 skip the `PlanEnumForm.apply` call when it is true. Do NOT skip `PlanEnumForm.rewritePredicate` behaviour for filter-position enum parameters — the engine's push-down is explicitly gated on `!$state.inFilter`, so the filter-side selector rewrite must stay; that means splitting PlanEnumForm.apply into its projection arm (suppressed under push-down) and its where arm (always applied). (2) In core/src/main/java/com/legend/normalizer/MappingNormalizer.java:3053-3068, when `ev.sourceValues().size() > 1` emit `new AppliedFunction("in", List.of(sourceRead, new PureCollection(litList)))` instead of the OR chain (single source value keeps `equal`). That change requires companion edits: com/legend/sql/DecodeShapes.java `conditionSource` (:68-85) must accept a `SqlFn.IN` condition and return its first argument, and com/legend/lowering/EnumSourceValues.decodeInvert must keep working over the new branch condition (it copies `w.condition()` verbatim, so it should be unaffected). EngineStyleH2 already renders `COALESCE(IN(a,b,c), false)` as the bare `a in (b, c)` (sql/dialect/EngineStyleH2.java:1107-1170), so the rendered text lands on the golden.

**How legend-engine does it** — /Users/neemsandv/legend/legend-engine/legend-engine-core/legend-engine-core-pure/legend-engine-pure-code-compiled-core/src/main/resources/core/pure/executionPlan/executionPlan_generation.pure:150-154 — the mapping-less overload `executionPlan(f:FunctionDefinition<Any>[1], context:ExecutionContext[1], extensions, debugContext)` does `let contextWithEnumPushDown = $context->addFlagToContext(Feature.PUSH_DOWN_ENUM_TRANSFORM);` and routes with it; the mapping/runtime overloads at :159-163 do not. The push-down itself is /Users/neemsandv/legend/legend-engine/legend-engine-xts-relationalStore/legend-engine-xt-relationalStore-generation/legend-engine-xt-relationalStore-pure/legend-engine-xt-relationalStore-core-pure/src/main/resources/core_relational/relational/pureToSQLQuery/pureToSQLQuery.pure:4892-4900 `buildPossibleEnumMappingPushDown` — `if($state.pushDownEnumTransformations && $mapping.transformer->instanceOf(EnumerationMapping) && !$state.inFilter, |...^DynaFunction(name='in', parameters=[$relationalElement, ^LiteralList(...)])...^DynaFunction(name='case', parameters=$caseParams->concatenate(^DynaFunction(name='sqlNull')))` — note the `in` DynaFunction for size>1 source values and the sqlNull else, and pureToSQLQuery.pure:337 where the flag is read off the exeCtx.

**Risk** — Change (2) alters the SQL text of EVERY multi-source-value enum decode, in execution as well as plan text — any currently-passing golden that pins `= 'FTC' or = 'FTO'` would flip. Grepping the corpus shows the engine goldens want `in (...)` (testEnumerationMapping.pure:386, 405, 443), so this should be net-positive, but it is a wide blast radius. Change (1) must not be implemented in the harness (com/legend/harness/) — the overload-to-feature-flag mapping is platform semantics owned by StatementExecutor's plan surface, and patching the assert would be textbook harness compensation.

**Also unblocks** — Change (2) is a prerequisite for the enum goldens at tests/mapping/enumeration/testEnumerationMapping.pure:386, :405 and :443, which all pin `in ('FTC', 'FTO', ...)`.

**Falsifier** — Print the plan string legend-lite produces for this test. If it already contains a `case when` over `"root".type` (i.e. PlanEnumForm did not fire — say because the projection expression is not a `SqlExpr.Case`, or `rt.columns().get(i).type()` is not an `EnumType` at that index) then part (1) is wrong and the failure is only the OR-vs-IN spelling. Conversely, if the plan SQL shows a bare `"root".type as "type"`, part (1) is confirmed.

<details><summary>Evidence read (6 citations)</summary>

- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/StatementExecutor.java:461-468 — "engine plans keep enum columns RAW (host-side decode)" then `plan = com.legend.plan.PlanEnumForm.apply(sel, rt);` with no overload/flag guard
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/plan/PlanEnumForm.java:42-45 — `if (isEnum && p.expr() instanceof SqlExpr.Case && (raw = singleColumnIn(p.expr())) != null) { ps.add(new SqlSelect.Projection(raw, p.outputName())); }` — the enum projection is replaced by the bare column
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/StatementExecutor.java:546-556 — when `ep.args().get(1)` is not a TypedPackageableRef the mapping is taken from `firstFromMapping(...)`; this is exactly the ->from-carrying 2-arg overload the test uses, and the branch is already distinguishable at that point
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/normalizer/MappingNormalizer.java:3065-3068 — `ValueSpecification eq = new AppliedFunction("equal", List.of(sourceRead, srcLit)); disj = disj == null ? eq : new AppliedFunction("or", List.of(disj, eq));` — multi-source-value branches become OR chains, never IN
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/sql/DecodeShapes.java:68-85 — `conditionSource` only understands EQUAL and OR-of-EQUAL, confirming the decode chain's condition shape is an OR tree
- /Users/neemsandv/legend/legend-engine/legend-engine-xts-relationalStore/legend-engine-xt-relationalStore-generation/legend-engine-xt-relationalStore-pure/legend-engine-xt-relationalStore-core-pure/src/main/resources/core_relational/relational/tests/mapping/enumeration/testEnumerationMappingDomain.pure:222-226 — `EmployeeType: EnumerationMapping Foo { CONTRACT: ['FTC','FTO'], FULL_TIME: 'FTE' }` is the mapping the golden CASE decodes

</details>

---

## `testExecutionPlanGenerationForMultipleInWithTwoCollectionInputs`

| | |
|---|---|
| family | `executionPlan/tests` |
| sweep status | FAIL |
| **verdict** | **MISSING FEATURE** |
| effort | L |
| confidence | high |

**Root cause**

Same missing subsystem as its sibling, but this test exercises the OTHER branch of processInOperation. Here both in-lists are single ZeroMany VarPlaceHolders ($name, $name1), so transformRequiringConditionNode fires (processInOperation.pure:81-90), replacing each IN right side with a VarPlaceHolder named `inFilterClause_<var>`; because the collection size (1 placeholder) is NOT > the threshold, generatePostProcessorResult takes the conditional branch (processInOperation.pure:150-176) and emits, per variable, Allocation(inFilterClause_<var>, value=FreeMarkerConditionalExecutionNode(condition='${(instanceOf(<var>,"Stream") || ((collectionSize(<var>![])?number) > 32767))?c}', trueBlock=Sequence(CreateAndPopulateTempTable(...), Constant(select-from-temp-table text)), falseBlock=Constant(the renderCollection text))). The threshold is 32767 because the connection here is a plain DatabaseConnection(DB2) — not a test run — so getCollectionThresholdLimitForDatabaseType returns the DB2 dbExtension limit, and the temp table name carries the DB2 SESSION. prefix. legend-lite has none of this: sequencePlan (StatementExecutor.java:825-895) emits Sequence(FPVN, Relational) and the dialect renders each IN as the inline `${renderCollection(...)}` template (EngineStyleH2.java:1155-1163, the exact text the engine relegates to the falseBlock Constant). SECOND, INDEPENDENT DIVERGENCE in the same golden: the observed SQL is `where (A and B)` while the engine's is `where A and B` — legend-lite's EngineStyleDB2 deliberately wraps a top-level conjunction in parens (EngineStyleDB2.java:61-66), but the engine's default 'and' ToSql is format='%s' with a plain ' and ' join and has no DB2 override, so a top-level AND is NEVER parenthesized; parens in engine goldens come from the individual rewrites (null-safe equality, DB2 date guards), not from the where clause.

**Fix**

Implement the same InOperationPostProcessor described for testExecutionPlanGenerationForInWithVarAndConstantInputs, and add its CONDITIONAL branch: for each ZeroMany plan parameter used under IN, rewrite the IN right side to a `${inFilterClause_<var>}` template placeholder and emit, in parameter order, Allocation(name=inFilterClause_<var>, value=FreeMarkerConditionalExecutionNode(condition, trueBlock=Sequence(CreateAndPopulateTempTable(inputVarNames=[<var>], tempTableName=<procesTempTableName prefix>tempTableForIn_<var>, tempTableColumns=[(ColumnForStoringInCollection, VARCHAR(1024))], connection=<connName>), Constant(select-from-temp-table SQL text)), falseBlock=Constant(<the renderCollection text currently inlined by EngineStyleH2:1155>))). Threshold source: 50 when the connection is a TestDatabaseConnection or a RelationalDatabaseConnection with LocalH2DatasourceSpecification, else the dialect limit (DB2 32767); the DB2 temp-table name gains the `SESSION.` prefix (engine procesTempTableName). Condition text also gains `instanceOf(<var>,"StreamingResult")` for test-run connections (processInOperation.pure:40-45) — that is why the H2 goldens show three disjuncts and this DB2 golden shows two. Outer node becomes RelationalBlockExecutionNode with the FPVN as its first child. SEPARATELY: revisit EngineStyleDB2.whereSql — the blanket top-level-AND paren is not the engine's rule; move the parens to the specific rewrites that own them (null-safe equality already emits its own parens at EngineStyleDB2.java:50-56) and re-run the DB2 goldens that motivated the blanket rule (e.g. transform/fromPure/tests/testToSQLString.pure:113, whose parens come from the DB2 date-guard rewrite, not from the where clause).

**How legend-engine does it** — /Users/neemsandv/legend/legend-engine/legend-engine-xts-relationalStore/legend-engine-xt-relationalStore-generation/legend-engine-xt-relationalStore-pure/legend-engine-xt-relationalStore-core-pure/src/main/resources/core_relational/relational/postprocessor/defaultPostProcessor/processInOperation.pure:81-90 (the ZeroMany-VarPlaceHolder rewrite to inFilterClause_*) and :150-176 (the FreeMarkerConditionalExecutionNode + Allocation emission); threshold selection at :34-45; block wrapping at .../relational/relationalMappingExecution.pure:64-68.

**Risk** — Changing EngineStyleDB2.whereSql will move other DB2 SQL-text goldens — do that as its own change with its own sweep, not folded into the post-processor work. The post-processor itself changes the shape of every plan golden containing a collection-typed parameter under IN. Tenet-2 trap: the falseBlock Constant must be the SAME text the dialect renders today (renderCollection); do not hand-write it in the plan printer, share the dialect's renderer so the two can never drift.

**Also unblocks** — testFilterInWithResultSorcedFromAnExpression (executionPlanTest.pure:108) — same conditional branch with an H2 test connection (threshold 50, three-disjunct condition); tests/advanced/testRelationalResultSourcing.pure:62 asserts the identical shape.

**Falsifier** — Render this query's SQL with the DB2 dialect and inspect only the where clause: if the engine golden's `where A and B` cannot be produced without also breaking transform/fromPure/tests/testToSQLString.pure:113 (`where (X and Y)`), then the paren rule is load-bearing for a reason I have not identified and the DB2 half of this diagnosis is wrong.

<details><summary>Evidence read (5 citations)</summary>

- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/StatementExecutor.java:886-895 — sequencePlan appends the terminal Relational and returns PlanText.sequence(...); no per-parameter Allocation for in-filters is ever produced.
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/sql/dialect/EngineStyleH2.java:1152-1156 — `return expr(...) + " in (${renderCollection(" + cp.name() + "![] \",\" " + holderArgs(cp.kind()) + " \"null\")})"`, i.e. the exact text observed inline in the SQL.
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/sql/dialect/EngineStyleDB2.java:61-66 — `whereSql` wraps a top-level SqlFn.AND in an extra paren pair; comment at EngineStyleH2.java:756 calls it 'DB2-family dialects wrap a top-level conjunction in one extra paren pair'.
- /Users/neemsandv/legend/legend-engine/.../core_relational/relational/sqlQueryToString/extensionDefaults.pure:189 — `dynaFnToSql('and', $allStates, ^ToSql(format='%s', transform={p|$p->makeString(' and ')}))`; a repo-wide grep for `dynaFnToSql('and'` finds only this default and a Spanner copy — no DB2 override — so the engine never parenthesizes a top-level and.
- grep for `inFilterClause|FreeMarkerConditional|32767` over /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java returns nothing.

</details>

---

## `testFilterInWithResultSorcedFromAnExpression`

| | |
|---|---|
| family | `executionPlan/tests` |
| sweep status | FAIL |
| **verdict** | **REAL DEFECT** |
| effort | XL |
| confidence | high |

**Root cause**

Three stacked causes, in order of what the observed output actually shows. (a) The query lambda `{y:String[1]| let z = $y->split(','); Firm.all()->filter(e|$e.legalName->in($z))->project(...)}` is PARAMETERIZED and multi-statement, so `Typer.typeLambda` (compiler/spec/Typer.java:1908-1919) β-inlines the let via `SourceSubst.inlineLets` and hands a SINGLE-expression lambda to the plan printer. That is why the got plan has only `FunctionParametersValidationNode` + `Relational` and no `Allocation(name=z)` at all — `StatementExecutor.sequencePlan`'s let loop (:865-876) has nothing to iterate. (b) With `$z` inlined, `$y->split(',')` lowers into the SQL as `string_split('${y...}', ',')`, and the `in` rule (lowering/Scalars.java:2273-2287) builds `Call(SqlFn.IN, [needle, splitCall])` — a 2-element IN. `EngineStyleH2` then hits the guard at sql/dialect/EngineStyleH2.java:1156-1161 ("the engine collapses a SINGLETON literal in-list to equality") which fires on ARITY ALONE, so a collection-valued second operand becomes `=`. Result: `where "root".LEGALNAME = string_split(...)` — a predicate that can never be true. That is a wrong-rows defect independent of plan text. (c) Even with (a) and (b) fixed, the golden's `RelationalBlockExecutionNode` / `Allocation(inFilterClause_z)` / `FreeMarkerConditionalExecutionNode` / `CreateAndPopulateTempTable` / `PureExp` node vocabulary is genuinely absent: `grep -rn inFilterClause core/src/main/java` returns nothing, `RelationalBlockExecutionNode` returns nothing, and the only reference to PureExp in the whole tree is a comment in builtin/Pure.java:1616 calling it "a named wall at the K-arm until built".

**Fix**

Three separate changes; only the first two are small. (1) core/src/main/java/com/legend/compiler/spec/Typer.java:1908-1919: stop inlining lets for parameterized lambdas whose consumer is the plan surface. The cleanest form is to keep the folded lambda for the TYPING pass but preserve the original multi-statement `TypedLambda` on the produced spec (an extra field, or type the statements individually as `TypedLet`s the way the zero-parameter path already does), so `StatementExecutor.planToString` (:603-610) still takes the `sequencePlan` branch with `lam.body().size() > 1`. (2) core/src/main/java/com/legend/sql/dialect/EngineStyleH2.java:1156-1161: gate the singleton collapse on the operand actually being a scalar — replace `if (bc.args().size() == 2)` with a test that arg 1 is a literal/column/scalar param and NOT a collection-typed expression. The durable form is to stop losing the distinction at lowering: have lowering/Scalars.java:2273-2287 emit a distinct node (e.g. reuse `SqlExpr.Membership`, which already exists at sql/SqlExpr.java:249-256 for exactly this meaning) when arg 1 is a collection-valued expression rather than an ArrayLit, and let the renderer spell `x in (<expr>)`. (3) Build the missing node vocabulary: add `PureExp`, `RelationalBlockExecutionNode`, `FreeMarkerConditionalExecutionNode`, `CreateAndPopulateTempTable` printers to com/legend/plan/PlanText.java; extend `StatementExecutor.allocationNode` (:905-975) with a fourth form — a let whose value has no getAll root and no literal form becomes `PureExp(type, resultSizeRange, requires=[<free vars with types>], expression=<pure source text>)`; and add a post-lowering pass mirroring processInOperation.pure that, for every `in` whose collection operand is a collection-multiplicity plan variable, replaces the operand with `${inFilterClause_<var>}`, wraps the terminal in a RelationalBlockExecutionNode, and prepends the Allocation/FreeMarkerConditional/CreateAndPopulateTempTable trio. Threshold is 50 for TestDatabaseConnection/H2 and 32767 for DB2 per the goldens.

**How legend-engine does it** — /Users/neemsandv/legend/legend-engine/legend-engine-xts-relationalStore/legend-engine-xt-relationalStore-generation/legend-engine-xt-relationalStore-pure/legend-engine-xt-relationalStore-core-pure/src/main/resources/core_relational/relational/postprocessor/defaultPostProcessor/processInOperation.pure:48-50 defines `prefixForWrapperAllocationNodeName() = 'inFilterClause_'`; :86-90 rewrites an `in` DynaFunction whose LiteralList holds a ZeroMany VarPlaceHolder into `^VarPlaceHolder(name = 'inFilterClause_' + $uniqueSuffix)`; :150-158 builds the `CreateAndPopulateTempTableExecutionNode` (tempTableName from `processedTempTableNameForIn` = 'tempTableForIn_' + name, column `ColumnForStoringInCollection`); :160-190 builds the `FreeMarkerConditionalExecutionNode` with `'${' + $conditionWithStreamHandled + '?c}'`, trueBlock = Sequence(allocation, createTempTable, Constant(select from temp table)), falseBlock = Constant(renderCollection template), wrapped in `^AllocationExecutionNode(varName = $outerAllocationNodeName, ...)`.

**Risk** — Change (1) is the risky one: `SourceSubst.inlineLets` for parameterized lambdas is load-bearing for the rest of the pipeline (Typer:1281, 1374 use it for NormalizeRequired inlining; StaticFold:445 and IfChecker:145 depend on the folded form). Preserving the multi-statement shape must not change what the LOWERER sees — the terminal still needs the let-bound variable available, now as a plan parameter rather than an inlined expression. Change (2) can flip currently-passing goldens that legitimately spell `x = v` for a singleton in-list; the arity test must be replaced by a shape test, not simply deleted. Do NOT implement (3) by special-casing the expected string in the harness — plan node vocabulary is platform surface. If (3) is not built, the honest outcome is a LOUD wall ("plan: IN over a collection plan variable needs the temp-table post-processor") rather than today's silent wrong SQL.

**Also unblocks** — testExecutionPlanGenerationForMultipleInWithTwoCollectionInputs (executionPlanTest.pure:2350) fails on the identical missing inFilterClause machinery — its got output is `in (${renderCollection(name![]...)})` where the golden wants the RelationalBlockExecutionNode + Allocation(inFilterClause_name) wrapper. testMultiExpressionWithPlatformAndFromFunction in this same unit shares causes (a) and (c).

**Falsifier** — Add a temporary trace print of `lam.parameters().size()` and `lam.body().size()` at the top of `StatementExecutor.planToString` (:537). If body.size() is 2 (the let survived) then cause (a) is wrong and the missing Allocation comes from somewhere else in sequencePlan. Independently: if the got SQL had shown `in (string_split(...))` rather than `= string_split(...)`, cause (b) would be wrong.

<details><summary>Evidence read (7 citations)</summary>

- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/compiler/spec/Typer.java:1908-1919 — `if (lam.body().size() != 1 && !lam.parameters().isEmpty()) { LambdaFunction folded = SourceSubst.inlineLets(lam); ... lam = folded; }`; the zero-parameter case is deliberately excluded, which is exactly why testMapWithOpenVariable keeps its Allocation and this test does not
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/compiler/spec/SourceSubst.java:41-54 — `inlineLets` folds `[let*, final]` into `List.of(substitute(last, env))`
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/StatementExecutor.java:865-876 — the Allocation loop is `for (int i = 0; i < lam.body().size() - 1; i++)`; a folded body of size 1 emits zero Allocations
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/sql/dialect/EngineStyleH2.java:1156-1161 — `// the engine collapses a SINGLETON literal in-list to equality` then `if (bc.args().size() == 2) { return expr(bc.args().get(0), 4) + " = " + expr(bc.args().get(1), 4); }` — arity-only test, no check that arg 1 is a scalar literal
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/lowering/Scalars.java:2273-2287 — when arg1 is not an ArrayLit, `flat.add(args.get(1))` produces exactly the 2-element IN that the renderer then miscollapses
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/plan/PlanText.java:144-229 — the entire node vocabulary the printer owns: sequence / functionParametersNode / allocation / constant / scalarRelational / single. No block node, no conditional node, no temp-table node, no PureExp.
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/StatementExecutor.java:930-934 — `allocationNode` requires `rootGetAllClass(let.value())`; a platform let value like `$y->split(',')` would throw "plan: Allocation value without a getAll root", so even un-inlined this let has no printable form today

</details>

---

## `testGraphFetchH2TempTableStrategy`

| | |
|---|---|
| family | `executionPlan/tests` |
| sweep status | SHAPE |
| **verdict** | **MISSING FEATURE** |
| effort | L |
| confidence | high |

**Root cause**

legend-lite has no relational graph-fetch execution-plan node family, in either of the two layers this test touches. Typed surface: Pure.java:513-514 register GlobalGraphFetchExecutionNode and StoreMappingGlobalGraphFetchExecutionNode as EMPTY native classes (`{}`), so the typer walls at `.localGraphFetchExecutionNode` (Typer.java:2564-2566) — the real class declares it on the abstract parent (graphFetchExecutionPlan.pure:37). Value/generator surface: even with the property declared there is nothing to return — StatementExecutor.planModel (:1941-2026) only ever builds SequenceExecutionNode / FunctionParametersValidationNode / RelationalInstantiationExecutionNode / SQLExecutionNode, and a grep for 'GraphFetch' across com/legend/plan and StatementExecutor returns nothing. The test then needs the whole temp-table strategy: RelationalRootQueryTempTableGraphFetchExecutionNode with processedTempTableName ('temp_table_node_' + index, relationalGraphFetch.pure:201-204) and a LoadFromTempFileTempTableStrategy (chosen for H2 at :211-216, built at :307-315) whose three child SequenceExecutionNodes carry the CREATE/INSERT-CSVREAD/DROP DDL (:230-256 via sqlsForTempTableCreation :171-175, sqlsForTempTableLoadFromTempFile :182-188, sqlsForTempTableDeletion :177-180). None of that exists. The wall is honest and is emitted at the earliest possible point.

**Fix**

Do not fix piecemeal — ledger it as 'relational graph-fetch execution planning (temp-table strategy) not implemented'. If/when it is built, the shape is: (1) Pure.java:513-514 — give the graph-fetch node classes their real members (at minimum `localGraphFetchExecutionNode`, `children`, `graphFetchTree`, `localTreeIndices`, `dependencyIndices` on the Global node), and add native entries for RelationalTempTableGraphFetchExecutionNode / RelationalClassQueryTempTableGraphFetchExecutionNode / RelationalRootQueryTempTableGraphFetchExecutionNode (tempTableName, columns, processedTempTableName, tempTableStrategy, batchSize, enableConstraints, checked) and TempTableStrategy (createTempTableNode/loadTempTableNode/dropTempTableNode) — mirroring graphFetchExecutionPlan.pure:33-73 and relationalGraphFetch.pure:46-105. (2) PlanNode.java — the node model needs kinds beyond the four it has, plus named child SLOTS (localGraphFetchExecutionNode, createTempTableNode, …) rather than a single positional `children` list. (3) StatementExecutor.planModel — a graph-fetch arm that, for a `graphFetch(tree)->serialize(tree)` terminal on a single relational store, emits Global→StoreMapping→RelationalRootQueryTempTable with processedTempTableName = tempTableName(0) put through the connection's identifier processor (quoted under quoteIdentifiers — that is the whole delta of the WithQuoteIdentifiers sibling), and a LoadFromTempFileTempTableStrategy whose three SequenceExecutionNodes wrap the CREATE/INSERT/DROP DDL rendered for the root class's PK columns (pk_0 INT for Person under simpleRelationalMapping).

**How legend-engine does it** — legend-engine .../core_relational/relational/graphFetch/relationalGraphFetch.pure:211-216 (H2 selects LoadFromTempFileTempTableStrategy for the root node) and :307-315 (the strategy is built with the three child nodes); legend-engine-core/.../core/pure/graphFetch/graphFetchExecutionPlan.pure:37 (localGraphFetchExecutionNode)

**Risk** — Adding properties to the two currently-empty native graph-fetch classes without a value channel converts a clear typer wall into a silent empty read (the `got []` shape seen on testSQLCommentsInPlan) — strictly worse diagnostics. Declare the properties only together with the generator. Tenet-2 trap: do not have the harness recognize this test's four assert strings; the plan model owns them.

**Also unblocks** — testGraphFetchH2TempTableStrategyWithQuoteIdentifiers

**Falsifier** — Declare `localGraphFetchExecutionNode` on the native GlobalGraphFetchExecutionNode and re-run. If the test then produced anything other than an empty/failed walk, planModel would have to be emitting graph-fetch nodes — a grep of core/src/main/java for 'GraphFetch' inside com/legend/plan and StatementExecutor shows it does not, so the expected result is `[]`/a new wall. Any other outcome falsifies the 'no generator' half of this diagnosis.

<details><summary>Evidence read (12 citations)</summary>

- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/builtin/Pure.java:513-514 — both graph-fetch plan node classes are declared with EMPTY bodies `{}`
- /Users/neemsandv/legend/legend-engine/legend-engine-core/legend-engine-core-pure/legend-engine-pure-code-compiled-core/src/main/resources/core/pure/graphFetch/graphFetchExecutionPlan.pure:33-43 — GlobalGraphFetchExecutionNode declares graphFetchTree, children, `localGraphFetchExecutionNode : LocalGraphFetchExecutionNode[1]`, parentIndex, enableConstraints, checked, localTreeIndices, dependencyIndices
- /Users/neemsandv/legend/legend-engine/.../core/pure/graphFetch/graphFetchExecutionPlan.pure:45-50 — StoreMappingGlobalGraphFetchExecutionNode extends it (store, xStorePropertyMapping, xStorePropertyFetchDetails)
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/compiler/spec/Typer.java:2564-2566 — the exact wall text
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/StatementExecutor.java:2012-2026 — planModel's ENTIRE node vocabulary: SQLExecutionNode → RelationalInstantiationExecutionNode → (optionally) FunctionParametersValidationNode + SequenceExecutionNode
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/resolver/StoreResolver.java:507-510 — the generic 'class query under <node> is not resolvable yet (H2 vocabulary)' wall that graph-fetch plan queries hit downstream (docs/RELATIONAL_CORPUS.md:1142-1143 shows planGraphFetchWithDerivedProperty hitting it)
- /Users/neemsandv/legend/legend-engine/.../core_relational/relational/graphFetch/relationalGraphFetch.pure:51-57 — RelationalTempTableGraphFetchExecutionNode declares tempTableName, columns, processedTempTableName, tempTableStrategy
- /Users/neemsandv/legend/legend-engine/.../core_relational/relational/graphFetch/relationalGraphFetch.pure:82-87 — TempTableStrategy declares createTempTableNode / loadTempTableNode / dropTempTableNode
- /Users/neemsandv/legend/legend-engine/.../core_relational/relational/graphFetch/relationalGraphFetch.pure:201-204 — `tempTableName(index) = 'temp_table_node_' + toString($index)`
- /Users/neemsandv/legend/legend-engine/.../core_relational/relational/graphFetch/relationalGraphFetch.pure:171-188 — sqlsForTempTableCreation (CreateTableSQL isTempTable=true) and sqlsForTempTableLoadFromTempFile (LoadTableSQL with the 'csv_file_location' VarPlaceHolder)
- /Users/neemsandv/legend/legend-engine/.../core_relational/relational/graphFetch/relationalGraphFetch.pure:230-256 — getCreateTempTableNode / getLoadTempTableNodeForRoot wrap the DDL strings in SQLExecutionNodes inside SequenceExecutionNodes
- /Users/neemsandv/legend/legend-engine/.../core_relational/relational/sqlQueryToString/extensionDefaults.pure:608 — the DROP spelling `'Drop table if exists ' + … + ';'` the test pins

</details>

---

## `testGraphFetchH2TempTableStrategyWithQuoteIdentifiers`

| | |
|---|---|
| family | `executionPlan/tests` |
| sweep status | SHAPE |
| **verdict** | **MISSING FEATURE** |
| effort | XS |
| confidence | high |

**Root cause**

Identical to testGraphFetchH2TempTableStrategy — same wall, same line, same absent surface. The only difference in the test is `testRuntime(true)` (quoteIdentifiers), which in the engine changes nothing structural: the temp table name and its columns are put through the connection's identifier processor, so 'temp_table_node_0' becomes '"temp_table_node_0"' and 'pk_0' becomes '"pk_0"' (the DDL is rendered via tableToString/identifierProcessor — see the DROP spelling at sqlQueryToString/extensionDefaults.pure:608, which applies dbConfig.identifierProcessor to the table name). legend-lite already carries the quoteIdentifiers flag through the plan path (StatementExecutor.quoteIdentifiersOf, :1000-1026, including the testRuntime(true) native overload arm at :1017-1024), so once the graph-fetch plan surface exists this sibling costs nothing extra.

**Fix**

Same as testGraphFetchH2TempTableStrategy — one feature, both tests. When the graph-fetch plan surface is built, route the temp-table name and its column names through the connection's identifier processor (the existing `quote` flag already reaches the plan dialect at StatementExecutor.java:1971-1976), which is the entire delta between the two tests. Ledger together with its sibling.

**How legend-engine does it** — legend-engine .../core_relational/relational/sqlQueryToString/extensionDefaults.pure:608 (identifierProcessor applied to DDL names); relationalGraphFetch.pure:201-204 (the unquoted base temp-table name)

**Risk** — Same as the sibling: do not declare the node properties without a generator.

**Also unblocks** — testGraphFetchH2TempTableStrategy

**Falsifier** — Same as the sibling. If this test's wall text ever diverges from the sibling's, the two do not share a cause — today docs/RELATIONAL_CORPUS.md:1163 and :1164 are byte-identical apart from the test name.

<details><summary>Evidence read (6 citations)</summary>

- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/docs/RELATIONAL_CORPUS.md:1164 — same wall as its sibling: `plan wall: class …StoreMappingGlobalGraphFetchExecutionNode has no property 'localGraphFetchExecutionNode'`
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/builtin/Pure.java:513-514 — the empty native graph-fetch node classes
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/StatementExecutor.java:1000-1026 — quoteIdentifiersOf, including the `testRuntime(true)` platform-native overload arm, already feeds the plan dialect
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/StatementExecutor.java:1971-1976 — planModel already reads `quote = quoteIdentifiersOf(rtArg2)` and passes it into the EngineStyleH2 plan dialect
- /Users/neemsandv/legend/legend-engine/.../core_relational/relational/sqlQueryToString/extensionDefaults.pure:608 — DDL names go through `$dbConfig.identifierProcessor(...)`, which is what makes the quoted variant's expected strings differ only by quoting
- /Users/neemsandv/legend/legend-engine/.../core_relational/relational/graphFetch/relationalGraphFetch.pure:201-204 — the unquoted base name 'temp_table_node_' + index that the identifier processor then quotes

</details>

---

## `testGroupByWithOpenVariableInAgg`

| | |
|---|---|
| family | `executionPlan/tests` |
| sweep status | FAIL |
| **verdict** | **REAL DEFECT** |
| effort | M |
| confidence | medium |

**Root cause**

The plan text legend-lite emits matches the UPGRADED (H2 2.1.214) golden arm byte-for-byte across every visible character (all 1661 chars before the sweep's 4000-char truncation), so the divergence is inside the terminal Relational node's remaining ~670 chars. The two golden arms of this test differ in EXACTLY TWO places and nowhere else (verified by extracting and diffing both arms programmatically): (a) the Allocation SQL's date literal `'2005-10-10'` vs `DATE'2005-10-10'`, and (b) the terminal SQL's `else 0.0 end` vs `else cast(0.0 as float) end`. legend-lite emits (a) in the 2.1.214 form — that is exactly what the sweep's got text shows — but `EngineStyleH2.expr` returns a BARE float literal (`String.valueOf(f.value())` on a `double`, i.e. `0.0`), which is the 1.4.200 form. So the output is a hybrid: it cannot equal arm 1 (wrong date literal) and cannot equal arm 2 (wrong float literal). This is not an accident of one method — the dialect has no version axis at all; each construct's spelling was chosen ad hoc against whichever golden was being chased.

**Fix**

Structural fix (the only one that holds): give `EngineStyleH2` a single declared H2 version and derive every literal/placeholder spelling from one type-keyed table, mirroring the engine. Concretely, in `core/src/main/java/com/legend/sql/dialect/EngineStyleH2.java` add a private `record LiteralFormat(String prefix, String suffix)` table keyed by the Pure type (STRING, INTEGER, FLOAT, BOOLEAN, STRICT_DATE, DATE, DATETIME, ENUM) populated from h2Extension2_1_214.pure:148-155 — FLOAT -> ("cast(", " as float)"), STRICT_DATE -> ("DATE'", "'"), DATE/DATETIME -> ("TIMESTAMP'", "'"), everything else -> ("", "") / ("'", "'") — and route BOTH `expr(SqlExpr.FloatLit)` (currently :968-970) AND the `SqlExpr.PlanParam` arm (currently :989-1014) through it, exactly as the engine routes literals and placeholders through the same `literalProcessor` (dbExtension.pure:620-643). For this test the visible change is one line: :968-970 becomes `return "cast(" + f.value() + " as float)";`. Use LOWERCASE `cast(... as float)` — the corpus goldens are the contract and they contain 26 lowercase occurrences and ZERO uppercase, despite h2Extension2_1_214.pure:152 declaring `CAST(%s AS FLOAT)`. What must change with it: `meta::relational::tests::m2m2r::testProp3`/`testProp4` (modelToModelToRelational/m2m2rShowcase.pure:78-96) currently PASS on the LEGACY arm (they are absent from the failing list); flipping the float spelling forces them onto the UPGRADED arm, which also requires the upgraded arithmetic parenthesization `((1.0 * a) / (b)) * c` instead of `(((1.0 * a) / b) * c)`. Do the float spelling and the arithmetic-parenthesization alignment in the same change, or both tests regress.

**How legend-engine does it** — legend-engine .../core_relational/relational/sqlQueryToString/dbSpecific/h2/h2Extension2_1_214.pure:148-155 (the version-scoped literal-processor table) and .../sqlQueryToString/dbSpecific/h2/h2Extension.pure:29-41 (`assertEqualsH2Compatible` selects one arm by the running H2 version)

**Risk** — High blast radius: legend-lite currently satisfies an unknown number of goldens through the LEGACY arm (every plain date placeholder spells `'${bd}'`, every float literal spells `0.0`). Committing the dialect to 2.1.214 moves all of those onto the upgraded arm and they will only pass if EVERY construct in their SQL is also upgraded-form. Measure first (see falsifier) before landing. Tenet-2 trap: do NOT make the harness render or accept two texts — `PlanAsserts.planTextAssert` already accepts either golden, which is more lenient than the engine; the platform owes exactly one text, so the fix belongs in the dialect, never in PlanAsserts.

**Also unblocks** — testGroupByWithTwoOpenVariablesInAggAndFilter (same cluster), plus the '5 rows (co-cause)' H2-version-mixing cluster recorded in docs/CORPUS_BURNDOWN_INDEX.md; any golden that combines a date literal/placeholder with a float literal

**Falsifier** — Run this one test with `LL_TMP_DEBUG=1`; `PlanAsserts.planGoldenDebug("plan-2golden", ...)` (PlanAsserts.java:183) prints the first-diff offset and a 180-char window against the SECOND golden. If that window shows `else 0.0` vs `else cast(0.0 as float)` and nothing after it, the diagnosis is complete. If it shows a diff BEFORE the `else`, the float spelling is only one of several divergences in the truncated tail and the fix is incomplete.

<details><summary>Evidence read (8 citations)</summary>

- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/sql/dialect/EngineStyleH2.java:968-970 — `if (e instanceof SqlExpr.FloatLit f) { return String.valueOf(f.value()); }`, with the comment at :963-967 justifying it from testProp3's LEGACY golden while the surrounding dialect targets 2.1.214
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/sql/SqlExpr.java:279 — `record FloatLit(double value)`, so `String.valueOf` yields `0.0`
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/sql/dialect/EngineStyleH2.java:955-957 — `groupBySeparator()` returns ", ", which is the 2.1.214 arm's `group by "a", "b"`; the dialect is otherwise aiming at the upgraded arm
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/sql/dialect/EngineStyleH2.java:1085-1093 — AND renders flat with no parens, i.e. the 2.1.214 relaxed-bracket form, again the upgraded arm
- legend-engine .../core_relational/relational/executionPlan/tests/executionPlanTest.pure:1171-1249 — the test body; I extracted both `assertEqualsH2Compatible` arms and diffed them: the ONLY differing lines are the Allocation `sql = ... where "root"."date" = '2005-10-10'` vs `DATE'2005-10-10'` and the terminal `... else 0.0 end` vs `... else cast(0.0 as float) end`
- legend-engine .../sqlQueryToString/dbSpecific/h2/h2Extension2_1_214.pure:148-155 — `getLiteralProcessorsForH2` maps Float -> `CAST(%s AS FLOAT)`, StrictDate -> `DATE'%s'`, DateTime/Date -> `TIMESTAMP'%s'`; the 1.4.200 path inherits extensionDefaults.pure:134-140 where Float is `%s` and all date types are `'%s'`
- legend-engine .../sqlQueryToString/dbSpecific/h2/h2Extension.pure:29-41 — `assertEqualsH2Compatible` asserts against exactly ONE arm chosen by `SELECT H2VERSION()`; the two arms are two renderings of the same query by two versions of one table, not two acceptable answers
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/harness/PlanAsserts.java:163-200 — `planTextAssert` compares the rendered plan text literally against args[0], then args[1] when there are 3 args, and reports `assertEquals: expected <arg0>, got <actual>`

</details>

---

## `testGroupByWithTwoOpenVariablesInAggAndFilter`

| | |
|---|---|
| family | `executionPlan/tests` |
| sweep status | FAIL |
| **verdict** | **REAL DEFECT** |
| effort | M |
| confidence | medium |

**Root cause**

Two independent instances of the same 'no version axis' defect, both in the terminal Relational node's SQL. (i) The float literal, exactly as in testGroupByWithOpenVariableInAgg: `else 0.0` where the upgraded arm needs `else cast(0.0 as float)`. (ii) The StrictDate open variable `$startDate` (`let startDate = %2015-02-25`, whose own Allocation node prints `type = StrictDate`). `Fold.planKindOf` collapses `Type.Primitive.DATE` and `Type.Primitive.STRICT_DATE` into one `PlanParam.Kind.DATE`, destroying the distinction the engine needs. `EngineStyleH2` then tries to recover it with a NAME heuristic — `p.name().indexOf('.') >= 0 ? "TIMESTAMP'${...}'" : "'${...}'"` — so a plain-named param spells the 1.4.200 bare-quoted form and a dotted one spells the 2.1.214 TIMESTAMP form. The real engine rule is purely by Pure type: `processPlaceHolder` takes prefix/suffix from `getPrefixAndSuffixForType($vType, $literalProcessor)`, i.e. the SAME per-type table used for literals. The corpus confirms the type rule exactly: across the whole relational corpus, `${bd}`/`${dt}`/`${productBusDate}`/`${reportEndDate.date}` (all Date/DateTime) appear 16+2+2+2 times as `TIMESTAMP'...'` in upgraded arms, and `${date}`/`${startDate}` (StrictDate) appear 11+1 times as `DATE'...'` — never crossed. Here `startDate:StrictDate` needs `DATE'${startDate}'` and `reportEndDate.date` (declared `date:Date[1]` on FiscalCalendarDate) needs `TIMESTAMP'${reportEndDate.date}'`; the dotted heuristic gets the second right and the first wrong, so again the text matches neither arm.

**Fix**

Same one change as testGroupByWithOpenVariableInAgg, plus: (1) in `core/src/main/java/com/legend/lowering/Fold.java:32-36`, split the kinds — return a new `SqlExpr.PlanParam.Kind.STRICT_DATE` for `Type.Primitive.STRICT_DATE` and keep `Kind.DATE` for `Type.Primitive.DATE` (add the enum constant in `core/src/main/java/com/legend/sql/SqlExpr.java`'s `PlanParam.Kind`). (2) In `EngineStyleH2.expr`, replace the dotted-name heuristic at :1003-1005 with the type-driven table: `STRICT_DATE -> "DATE'${" + p.name() + "}'"`, `DATE, DATETIME -> "TIMESTAMP'${" + p.name() + "}'"` (keeping the existing `timeZone != null` GMTtoTZ wrap for DATETIME), and DELETE the `indexOf('.')` branch entirely — the dot is a property path, not a type signal. (3) Audit every other `Kind.DATE` consumer (`EngineStyleDB2`, `EngineStyleComposite`, `PlanEnumForm.java:155-190`) for the new constant so the switch stays exhaustive.

**How legend-engine does it** — legend-engine .../core_relational/relational/sqlQueryToString/dbExtension.pure:633-643 (`convertPlaceHolderToSQLString` derives prefix/suffix from the type's LiteralProcessor) and .../sqlQueryToString/dbSpecific/h2/h2Extension2_1_214.pure:153-155 (StrictDate -> DATE'%s', Date/DateTime -> TIMESTAMP'%s')

**Risk** — Splitting the kind moves EVERY plain date placeholder in the corpus onto the upgraded spelling: 16 `${bd}` sites become `TIMESTAMP'${bd}'` and 11 `${date}` sites become `DATE'${date}'`. Milestoning/filter tests that currently pass on the legacy arm via `'${bd}'` will only keep passing if their whole SQL is upgraded-form. Land this together with the float change and measure the net corpus delta; if it is negative, the honest answer is to ledger the whole dialect-versioning item rather than half-migrate. Tenet-2 trap: resist widening `PlanAsserts` to normalise date keywords — the platform owes one text under one declared H2 version.

**Also unblocks** — testGroupByWithOpenVariableInAgg; the docs/CORPUS_BURNDOWN_INDEX.md row 'StrictDate/Date collapse to one plan-param kind, reconstructed by a dotted-name heuristic'

**Falsifier** — `LL_TMP_DEBUG=1` on this test: `planGoldenDebug("plan-2golden", ...)` prints the first-diff window against the upgraded golden. Expect the first diff at `else 0.0` (before the WHERE). Patch the float spelling alone and re-run: the next first-diff must land at `'${startDate}'` vs `DATE'${startDate}'`. If either predicted diff does not appear, some third divergence exists in the truncated tail.

<details><summary>Evidence read (8 citations)</summary>

- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/lowering/Fold.java:32-36 — `if (t == Type.Primitive.DATE || t == Type.Primitive.STRICT_DATE) return PlanParam.Kind.DATE;` — the two types collapse to one kind
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/sql/dialect/EngineStyleH2.java:1003-1005 — `case DATE -> p.name().indexOf('.') >= 0 ? "TIMESTAMP'${" + p.name() + "}'" : "'${" + p.name() + "}'"` — the dotted-name heuristic standing in for the lost type
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/lowering/Lowerer.java:2285-2295 — the dotted PlanParam is minted here with `Fold.planKindOf(p.info().type())`, so the FIELD's type is available at mint time and is simply discarded by the DATE/STRICT_DATE collapse
- legend-engine .../sqlQueryToString/dbExtension.pure:633-643 + :645-670 — `convertPlaceHolderToSQLString` computes `$prefixSuffix = getPrefixAndSuffixForType($vType, $literalProcessor)` and `processPlaceHolder` emits `$prefix + '${' + name + '}' + $suffix`; the placeholder prefix IS the literal format for that type
- legend-engine .../core_relational/relational/tests/query/datePeriods.pure:635-651 — `Class FiscalCalendarDate { date:Date[1]; ... day:Integer[1]; ... }`, so `$reportEndDate.date` is Date (-> TIMESTAMP) and `$reportEndDate.day` is Integer (-> bare `${reportEndDate.day}`, which legend-lite already gets right via Kind.OTHER)
- legend-engine .../core_relational/relational/executionPlan/tests/executionPlanTest.pure:1254-1345 — the test body; the upgraded arm's terminal SQL is `... else cast(0.0 as float) end ... where "calendar_0"."date" > DATE'${startDate}' and "calendar_0"."date" <= TIMESTAMP'${reportEndDate.date}' group by "Sales Division"`
- corpus-wide placeholder census over .../core_relational/relational: 16x `TIMESTAMP'${bd}'` / 16x `'${bd}'`, 11x `DATE'${date}'` / 11x `'${date}'`, 1x `DATE'${startDate}'` / 1x `'${startDate}'` — every upgraded-arm spelling tracks the Pure type, none tracks the name shape
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/sql/dialect/EngineStyleH2.java:1085-1093 — AND renders flat with no parens, so the WHERE clause is already the upgraded arm's shape and is NOT a third divergence here

</details>

---

## `testMapWithOpenVariable`

| | |
|---|---|
| family | `executionPlan/tests` |
| sweep status | FAIL |
| **verdict** | **GOLDEN TEXT ONLY** |
| effort | L |
| confidence | medium |

**Root cause**

legend-lite deliberately emits a different SQL shape for a projection-position aggregate over a to-many navigation. In `CorrelatedSubselects.corrAggSubSource` (resolver/CorrelatedSubselects.java:205-214), when there is no correlated predicate AND the demand is not filter-position AND the association has plain equi keys, it returns `aj.targetPipeline()` grouped by the TARGET's keys — producing `(select "root".FIRMID as "FIRMID", count(*) as "agg_0" from personTable as "root" group by "root".FIRMID) as "persontable_0"`. The engine ALWAYS uses the parent-copy isolation shape: it re-roots the aggregate subselect on the PARENT extent left-outer-joined to the target, groups by the parent key, and names the aggregate column `aggCol` under a parent-derived alias — `(select "firmtable_1".ID as ID, count(*) as aggCol from firmTable as "firmtable_1" left outer join personTable as "persontable_0" ... group by "firmtable_1".ID) as "firmtable_1"`. legend-lite's own comment at resolver/StoreResolver.java:1984-1987 states the divergence explicitly. Secondary text delta: legend-lite wraps count-family aggregate reads in `case when <col> is not null then <col> else 0 end` (`Substitution.rewrite`, resolver/Substitution.java:1658-1676, driven by `AggRead.zeroWhenEmpty` set at StoreResolver.java:2092-2093). The engine emits no such guard. Rows are NOT asserted by this test, and legend-lite's rows are arguably the pure-correct ones: over the engine's parent-copy shape a firm with zero employees yields `count(*) = 1` (the left-outer-join row still counts), while legend-lite yields 0.

**Fix**

In core/src/main/java/com/legend/resolver/CorrelatedSubselects.java:205-214, delete the `!filterPosition` escape so the parent-copy shape is taken for projection-position aggregate demands too (i.e. change the condition to `if (tKeys != null && false)` / simply remove the early return and always fall through to the parentCopy path at :219-266). That alone does not reproduce the golden text; three companions are needed: (a) name the aggregate column `aggCol` rather than `agg_<n>` when the group carries exactly one aggregate demand — StoreResolver.java:2046-2052; (b) derive the outer join alias from the PARENT table (`firmtable_1`) rather than the target (`persontable_0`) — `AssociationJoins.prefixFor(head + "_agg", cs)` at StoreResolver.java:2069-2071 is the site; (c) drop the `zeroWhenEmpty` count guard on this path (StoreResolver.java:2092-2093 / Substitution.java:1663-1674), because the engine's parent-copy subselect always yields a row per parent and therefore never coalesces. Honest alternative: ledger this. The test asserts only plan text, legend-lite's rows are pure-correct, and (c) trades a semantic improvement for text parity.

**How legend-engine does it** — /Users/neemsandv/legend/legend-engine/legend-engine-xts-relationalStore/legend-engine-xt-relationalStore-generation/legend-engine-xt-relationalStore-pure/legend-engine-xt-relationalStore-core-pure/src/main/resources/core_relational/relational/pureToSQLQuery/pureToSQLQuery.pure:1181-1183 `buildCorrelatedSubQuery(... shouldIsolateGroupBy:Boolean[1] ...)` with `let isolateGroupBy = $shouldIsolateGroupBy && !$selectSql.groupBy->isEmpty();`, and :1250-1255 `let colForGroupBy = if($isolateGroupBy, |let aggFunc = ...; let aggFuncModified = $aggFunc->match([a:Alias[1]|$a, a:Any[1]|^Alias(name = 'aggCol', relationalElement = $aggFunc)]); ...)` — this is where the golden's literal `aggCol` name and the parent-rooted nested select come from. That the shape is universal (not filter-position-only) is pinned by projection-position goldens: tests/query/testWithFunction.pure:437 (`... left outer join (select "firmtable_2".ID as ID, sum("persontable_1".AGE) as aggCol from firmTable as "firmtable_2" left outer join personTable as "persontable_1" ... group by "firmtable_2".ID) as "firmtable_1" ...`) and tests/mapping/modelJoin/testModelJoinAdvanced.pure:1492-1493.

**Risk** — High-blast-radius. Removing the projection-position shortcut changes the SQL of every to-many aggregate in a project/map, so every currently-passing execution and toSQLString test with an aggregate over a navigation moves. Companion (c) is a genuine semantic REGRESSION for row-asserting tests: `$f.employees->count()` for a firm with zero employees becomes 1 under the engine's `count(*)` over a left-outer join, where pure says 0 and legend-lite currently says 0. Do not paper over the divergence in the harness by normalizing the SQL text before comparison — plan text is a literal-comparison contract owned by the platform.

**Also unblocks** — testMapWithOpenVariableOutsideBlock (same unit, identical shape). Plausibly also tests/query/testWithFunction.pure:437 and :440, tests/mapping/modelJoin/testModelJoinAdvanced.pure:1492, and the aggCol goldens in tests/mapping/union/testUnion.pure, tests/mapping/union/testUnionWithExtends.pure, tests/mapping/modelJoin/testModelJoinUnion.pure and the two milestoning tests — I have not verified those are currently failing, only that they pin the same aggCol shape.

**Falsifier** — Run `Firm.all()->map(f|$f.employees->count())` against seeded data containing a firm with zero employees, on both engines. If legend-lite returns 0 and the engine returns 1 for that firm, the fix is a rows-regression and the honest verdict is 'ledger, do not fix'. Separately, if some currently-PASSING legend-lite golden pins the target-grouped shape (`group by "root".FIRMID` with an `agg_0` alias), then the divergence is not uniformly wrong and the fix must be position-discriminated after all.

<details><summary>Evidence read (6 citations)</summary>

- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/resolver/CorrelatedSubselects.java:205-214 — `if (corrAgg == null) { List<String> tKeys = targetEquiKeysOrNull(aj.condition()); // FILTER position takes the PARENT-COPY shape below even for a simple equi condition ... if (tKeys != null && !filterPosition) { return new CorrAggSub(aj.targetPipeline(), tKeys, aj.targetRow(), null, null, null, null); } }`
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/resolver/StoreResolver.java:1983-1987 — `// FILTER-position demands emit their OWN parent-copy subselect; a projection demand on the same head keeps the target-grouped shape (the engine's isolation differs by position — the root tree is copied only into filter isolations)`
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/resolver/StoreResolver.java:2046-2052 — `String alias = "agg_" + ord++;` is where the golden's `aggCol` name is instead spelled `agg_0`
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/resolver/Substitution.java:1658-1674 — the AggRead arm returns `new TypedIf(neCallee(read), read, Optional.of(TypedCInteger(0L)), ...)` when `aggRead.zeroWhenEmpty()`, which renders as the got output's `case when "persontable_0"."agg_0" is not null then ... else 0 end`
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/resolver/StoreResolver.java:2090-2093 — `aggReads.put(d.node(), new Substitution.AggRead(prefix + "agg_" + ord++, CorrelatedSubselects.isCountFamily(d.node())));`
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/plan/PlanText.java:564-577 — a computed (non-Column) projection prints `("<outputName>", "")`; for a scalar terminal StatementExecutor takes the PlanText.single :70-77 path that copies the raw select item text, which is why resultColumns mirrors the SQL divergence exactly

</details>

---

## `testMapWithOpenVariableOutsideBlock`

| | |
|---|---|
| family | `executionPlan/tests` |
| sweep status | FAIL |
| **verdict** | **GOLDEN TEXT ONLY** |
| effort | XS |
| confidence | medium |

**Root cause**

Identical to testMapWithOpenVariable — the projection-position correlated-aggregate subselect keeps legend-lite's target-grouped shape instead of the engine's parent-copy `aggCol` isolation, plus the count-family `case when ... else 0` guard. Everything else in this test is already right: the outer `let a = ^Struct(val = 10)` lives OUTSIDE the executionPlan lambda, so `$a.val` constant-folds to `10` and legend-lite's got text shows `+ 10` exactly as the golden does; and because the query lambda `{|Firm.all()->map(...)}` is zero-parameter and single-statement, `planToString` takes the single-node `Relational` branch (StatementExecutor.java:603-610 is false, so it falls through to :611-639) and prints a bare Relational node with no Sequence envelope — again matching the golden. The ONLY delta is the aggregate SQL.

**Fix**

No separate fix. Apply the CorrelatedSubselects.java:205-214 change plus the aggCol naming / parent alias / zeroWhenEmpty companions described for testMapWithOpenVariable; this test passes or fails with that one change. Do not attempt a second, narrower fix here — the two tests differ only in whether the open variable is a plan `${a}` template or a folded literal `10`, and that part already matches.

**How legend-engine does it** — Same as testMapWithOpenVariable: /Users/neemsandv/legend/legend-engine/legend-engine-xts-relationalStore/legend-engine-xt-relationalStore-generation/legend-engine-xt-relationalStore-pure/legend-engine-xt-relationalStore-core-pure/src/main/resources/core_relational/relational/pureToSQLQuery/pureToSQLQuery.pure:1181-1183 and :1250-1255 (`^Alias(name = 'aggCol', ...)` under `isolateGroupBy`).

**Risk** — Same risk profile as testMapWithOpenVariable — this test adds no independent exposure.

**Also unblocks** — testMapWithOpenVariable (same unit).

**Falsifier** — Same as testMapWithOpenVariable. If, after the parent-copy change, this test still differs while testMapWithOpenVariable passes, the residue is in the `${a}`-vs-literal channel, not in the aggregate shape.

<details><summary>Evidence read (4 citations)</summary>

- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/resolver/CorrelatedSubselects.java:205-214 — the same `tKeys != null && !filterPosition` early return that pins the target-grouped subselect
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/resolver/Substitution.java:1663-1674 — the `zeroWhenEmpty` TypedIf that renders the got output's `case when "persontable_0"."agg_0" is not null then ... else 0 end`
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/StatementExecutor.java:603-610 — `if (!lam.parameters().isEmpty() || lam.body().size() > 1 || (lam.body().size() == 1 && body.get(0) instanceof TypedLet))` gates the Sequence envelope; this lambda satisfies none of the three, hence the bare `Relational` node the golden also wants
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/plan/PlanText.java:70-77 — the scalar-terminal branch spells resultColumns straight from the emitted select item, so the resultColumns delta is a pure consequence of the SQL delta

</details>

---

## `testModelConnectionAgg`

| | |
|---|---|
| family | `executionPlan/tests` |
| sweep status | SHAPE |
| **verdict** | **REAL DEFECT** |
| effort | M |
| confidence | high |

**Root cause**

Identical mechanism to testModelConnectionDeepFunction: ClassSources.composeModelToModel eagerly substitutes all four bindings of simpleModelMapping's Person class mapping (ClassSources.java:897-907) and dies on `type : $src._type` (ClassSources.java:951) because relationalMapping maps only `fullName` on _S_Person. The query is `Person.all()->groupBy([x|$x.firstName], agg(x|$x.lastName, y|$y->count()), [...])` — it demands only firstName and lastName, so the failing binding is never needed.

**Fix**

Same change as testModelConnectionDeepFunction (deferred per-binding walls on ClassSource + read-site accessor + loud whole-map iteration). Beyond the wall this test additionally needs the M2M groupBy shape to render as the golden does: `group by "FirstName"` (grouping by the projection ALIAS, not by the repeated expression), `count(<lastName expression>)` as the aggregate, and the M2M type spellings `type = TDS[(FirstName, String, VARCHAR(8192), "")]` with `resultColumns = [("FirstName", ""), ("PersonCount", "")]` — the empty-type spelling for computed projections already exists at PlanText.java:569-577 and the M2M VARCHAR(8192) spelling at PlanText.java:302-307, so no new plan-printer vocabulary should be required.

**How legend-engine does it** — /Users/neemsandv/legend/legend-engine/legend-engine-core/legend-engine-core-pure/legend-engine-pure-code-compiled-core/src/main/resources/core/store/m2m/chain.pure:189-193 — property-by-property, query-driven inlining of the PurePropertyMapping transform.

**Risk** — Same as testModelConnectionDeepFunction. Additional exposure once the wall lifts: the group-by-alias spelling. If legend-lite emits `group by substring(...)` instead of `group by "FirstName"`, that is a separate SQL-render divergence, not a regression of this fix.

**Also unblocks** — testModelConnectionDeepFunction, testModelConnectionMultipleAgg

**Falsifier** — Same falsifier as testModelConnectionDeepFunction. Additionally: if after the fix the wall is replaced by a groupBy/agg wall or a `group by <expr>` vs `group by "FirstName"` text diff, the eager-substitution diagnosis is still correct but insufficient for a pass.

<details><summary>Evidence read (5 citations)</summary>

- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/resolver/ClassSources.java:897 — the unconditional per-binding substitution loop.
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/resolver/ClassSources.java:951 — the emitted wall message, matching the sweep detail verbatim.
- /Users/neemsandv/legend/legend-engine/legend-engine-core/legend-engine-core-pure/legend-engine-pure-code-compiled-core/src/main/resources/core/store/m2m/tests/simple.pure:241-246 — `~src _S_Person` with `type : $src._type`.
- /Users/neemsandv/legend/legend-engine/legend-engine-xts-relationalStore/legend-engine-xt-relationalStore-generation/legend-engine-xt-relationalStore-pure/legend-engine-xt-relationalStore-core-pure/src/main/resources/core_relational/relational/tests/shared.pure:72 — `_S_Person : Relational { fullName : [relationalDB]SPerson.fullname }`.
- /Users/neemsandv/legend/legend-engine/legend-engine-xts-relationalStore/legend-engine-xt-relationalStore-generation/legend-engine-xt-relationalStore-pure/legend-engine-xt-relationalStore-core-pure/src/main/resources/core_relational/relational/executionPlan/tests/executionPlanTest.pure:2198-2216 — the test's expected SQL reads only `"root".fullname` from SPerson, confirming the engine never materialized `type`.

</details>

---

## `testModelConnectionDeepFunction`

| | |
|---|---|
| family | `executionPlan/tests` |
| sweep status | SHAPE |
| **verdict** | **REAL DEFECT** |
| effort | M |
| confidence | high |

**Root cause**

ClassSources.composeModelToModel substitutes EVERY key of the M2M constructor eagerly: the loop at ClassSources.java:897-907 iterates `ctor.properties()` and calls substituteSourceReads on each. simpleModelMapping's Person class mapping declares four bindings over `~src _S_Person` — firstName, lastName, `type : $src._type` and `alternateName : $src.alternateName->toOne()`. The upstream relational mapping maps only `fullName` on _S_Person, so `inner.bindings().get("_type")` is null and `_type` is not an association, and substituteSourceReads throws the H5b wall at ClassSources.java:951. The query only projects firstName and lastName; the failing bindings are never demanded. This is eager Work — the exact thing TENETS.md's decision table files under 'Lazy'. The mapping is entered because the ModelChainConnection lists [simpleModelMapping, relationalMapping] beneath simpleModelMappingO.

**Fix**

Defer the wall from composition time to read time. In ClassSources.composeModelToModel, replace the eager loop at ClassSources.java:897-907 with: for each ctor property, try substituteSourceReads; on NotImplementedException, do NOT put a binding — record `prop -> e.getMessage()` in a new `Map<String,String> deferredWalls`. Add `deferredWalls` as a new component of the ClassSource record (/Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/resolver/ClassSource.java:38, defaulting to Map.of() in the existing convenience constructor so no other construction site changes), and add `public TypedSpec binding(String prop)` which throws `new NotImplementedException(deferredWalls.get(prop))` when the property is deferred and returns `bindings.get(prop)` otherwise. Route the scalar READ sites through it — the `bindings().get(...)` calls at ClassSources.java:932, InnerDemand.java:81 and :230, StoreResolver.java:697, :852, :1296, :1336, :1646, :2150, GraphEmission.java:251 — so a query that DOES read `type` still fails with the identical message. Every WHOLE-MAP iteration site that builds an output row (CastNav.java:98, StoreResolver.java:808, GraphEmission.java:125) must raise if `!cs.deferredWalls().isEmpty()`, because dropping a column there would be wrong rows rather than a loud wall. Also propagate `deferredWalls` through the ClassSource rebuilds that copy `sub.bindings()` (StoreResolver.java:765, :787, :1526, :2501, :3430) so the deferral is not lost.

**How legend-engine does it** — /Users/neemsandv/legend/legend-engine/legend-engine-core/legend-engine-core-pure/legend-engine-pure-code-compiled-core/src/main/resources/core/store/m2m/chain.pure:189-193 — meta::pure::mapping::modelToModel::chain::reprocess walks the QUERY expression and, only when it meets a Property application, looks up that one property's mapping (`$setImpl->_propertyMappingsByPropertyName($fe.func.name->toOne())->cast(@PurePropertyMapping)->toOne()`) and inlines its transform. The engine never materializes bindings the query does not navigate — which is why the golden SQL for this test touches only `fullname`.

**Risk** — The dangerous variant of this fix is silently omitting the unresolvable binding: any consumer that iterates bindings to build a projection would then emit fewer columns — wrong rows instead of a loud wall. That is why the iteration sites must stay loud. Tenet-2 trap: do not 'fix' this by pruning `type`/`alternateName` in the harness or by special-casing property names starting with '_'. Second-order risk: lifting the wall exposes whatever comes next in this chain (3-level M2M2R: simpleModelMappingO over simpleModelMapping over relationalMapping, needing `+` -> concat, substring, indexOf -> locate, length -> char_length and the M2M VARCHAR(8192) type spelling) — the test may fail further downstream even after the wall is correct.

**Also unblocks** — testModelConnectionAgg and testModelConnectionMultipleAgg — byte-identical wall from the same composition of the same mapping.

**Falsifier** — If, after making the composition demand-driven, the same `$src._type` wall still fires for a query that only reads firstName/lastName, then some other site force-walks all bindings. Cheapest observation without a full run: grep the resolution path actually taken for a TypedProject over an M2M-composed ClassSource and check whether it reaches `bindings().entrySet()` (CastNav.java:98 / StoreResolver.java:808 / GraphEmission.java:125) rather than `bindings().get(prop)`; if it does, the lazy-read fix alone is insufficient and the composition itself must be property-demand-scoped (the engine's chain.pure:189 shape).

<details><summary>Evidence read (6 citations)</summary>

- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/resolver/ClassSources.java:897 — `for (Map.Entry<String, TypedSpec> e : ctor.properties().entrySet()) { composed.put(e.getKey(), substituteSourceReads(e.getValue(), srcVar, inner, classFqn, mappingFqn)); }` — every binding, unconditionally.
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/resolver/ClassSources.java:951 — the exact wall text: "model-to-model binding of '" + classFqn + "' in '" + mappingFqn + "' navigates '$" + srcVar + "." + pa.property() + "' — an unmapped non-association property of source class ...", reached only when `inner.bindings().get(pa.property())` is null (line 932) and the property is not an association (line 942).
- /Users/neemsandv/legend/legend-engine/legend-engine-core/legend-engine-core-pure/legend-engine-pure-code-compiled-core/src/main/resources/core/store/m2m/tests/simple.pure:237-252 — Mapping simpleModelMapping, class Person, `~src _S_Person`, bindings firstName, lastName, `type : $src._type`, `alternateName : $src.alternateName->toOne()`.
- /Users/neemsandv/legend/legend-engine/legend-engine-xts-relationalStore/legend-engine-xt-relationalStore-generation/legend-engine-xt-relationalStore-pure/legend-engine-xt-relationalStore-core-pure/src/main/resources/core_relational/relational/tests/shared.pure:72-75 — `_S_Person : Relational { fullName : [relationalDB]SPerson.fullname }` — `_type` and `alternateName` are genuinely unmapped upstream.
- /Users/neemsandv/legend/legend-engine/legend-engine-core/legend-engine-core-pure/legend-engine-pure-code-compiled-core/src/main/resources/core/store/m2m/tests/shared.pure:246-250 — class _S_Person declares fullName, alternateName, `_type : String[0..1]`.
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/docs/TENETS.md:54 — the decision table files "mapping transforms" body type-check under Work / Lazy: "compiled when something needs it".

</details>

---

## `testModelConnectionJoin`

| | |
|---|---|
| family | `executionPlan/tests` |
| sweep status | SHAPE |
| **verdict** | **REAL DEFECT** |
| effort | L |
| confidence | medium |

**Root cause**

The ModelChainConnection dispatch is threaded into the ROOT getAll class-source resolution but dropped by every downstream navigation-target resolution. `StoreResolver` resolves the root with the full closure — `sources.get(dispatch(fctx, g.classFqn()), g.classFqn(), (t9, ex9) -> sources.dispatch(fctx.explicitMapping(), fctx.runtimeFqn(), fctx.chainMappings(), t9, ex9), contextKey(fctx))` (StoreResolver.java:2717-2720). Every subsequent target resolution instead calls the 2-argument overload `sources.get(cs.mappingFqn(), targetClass)` (AssociationJoins.java:987, StoreResolver.java:680, :942, :2465, :2519, CorrelatedSubselects.java:456/465/1707, CastNav.java:49, ...), and that overload hard-codes `upstreamMapping = null, contextKey = ""` (ClassSources.java:114-116). Inside `ClassSources.composeModelToModel` the null upstream short-circuits the chain: `String srcMapping = !selfSourced && (binds(mappingFqn, srcType.fqn()) || upstreamMapping == null) ? mappingFqn : ...` (ClassSources.java:868-874) — with `upstreamMapping == null` this always evaluates to the M2M mapping itself. `simpleModelMappingWithAssociation` maps only `dest::Person` and `dest::Firm`; the src classes live in the ModelChainConnection's `relationalMapping`. So the request lands on `build(simpleModelMappingWithAssociation, src::_Person, ...)`, finds no binding, and throws the observed message at ClassSources.java:622-625. There is a second layer behind it: the M2M binding `employees : $src.employees` composes to a SOURCE-NAV MARKER typed with the SOURCE class (`ClassSources.substituteSourceReads` :941-950 re-points the access at the composed row var but keeps `pa.info()`, i.e. `src::_Person[*]`), so the downstream `.lastName` navigation is reasoning about `src::_Person` rather than the routed `dest::Person` set. Threading the chain alone would move the wall, not remove it.

**Fix**

Two parts. (1) Make the chain dispatch ambient on ClassSources, mirroring the existing `setJsonSources` seam (core/src/main/java/com/legend/resolver/ClassSources.java:70-74): add fields for the upstream-dispatch BiFunction and the context key, set them once per from()-scope by StoreResolver where it builds `fctx` (the closure is already constructed at StoreResolver.java:2718-2720), and change the 2-arg `get` at :114-116 to pass those instead of `null, ""`. That makes AssociationJoins.java:987, StoreResolver.java:680/:942/:2465/:2519 and the CorrelatedSubselects/CastNav sites all dispatch through the ModelChainConnection without touching each call site. Note the memo key at :144-145 already includes contextKey, so this does not poison the cache. (2) Retarget the M2M source-nav marker: at ClassSources.java:941-950 the marker keeps `pa.info()` (the SOURCE class type). For a binding of a DEST class property whose declared type is a dest class (`dest::Firm.employees : dest::Person[*]`), the marker must carry enough to let the downstream nav resolve the DEST set (`dest::Person` in the M2M mapping) while deriving the JOIN from the src association (`src::_Firm_Person`) resolved in the chain mapping. Concretely: keep the src-typed marker for the join derivation, but stamp the declared dest property type on the node used for downstream property resolution — the engine composes exactly this way (dest set over the chain-resolved src row).

**How legend-engine does it** — /Users/neemsandv/legend/legend-engine/legend-engine-core/legend-engine-core-pure/legend-engine-pure-code-compiled-core/src/main/resources/core/store/m2m/tests/simple.pure:347-363 — `Mapping simpleModelMappingWithAssociation ( Person : Pure { ~src _Person firstName : ..., lastName : ... } Firm : Pure { ~src _Firm legalName : $src.name, employees : $src.employees } )`. The src side is /Users/neemsandv/legend/legend-engine/legend-engine-core/legend-engine-core-pure/legend-engine-pure-code-compiled-core/src/main/resources/core/store/m2m/tests/shared.pure:228-232 — `Association src::_Firm_Person { employees : _Person[*]; firm : _Firm[1]; }`; note dest::Firm.employees (shared.pure:24-30) is a plain class PROPERTY, not an association, so the join must be derived from the SRC association resolved through the chain mapping.

**Risk** — Making the upstream dispatcher ambient changes resolution for every M2M composition that currently reaches the 2-arg get. Today those either bind in the M2M mapping (unchanged) or throw; after the change they will dispatch through chainMappings/runtime candidates. No currently-passing test can depend on the throw, but a mapping that binds a class in BOTH the explicit mapping and a chain mapping would now be resolved by `dispatch`'s exactly-one-binder rule rather than by the explicit mapping — check `dispatch`'s guard order at ClassSources.java:1267-1286 preserves 'explicit wins when it binds'. Part (2) touches M2M binding composition and can move graph-fetch results; run the m2m/graphFetch families. Do not shortcut this by teaching the harness to skip the plan wall.

**Also unblocks** — Unclear. The sibling failures testModelConnectionAgg (executionPlanTest.pure:2198), testModelConnectionDeepFunction (:2153) and testModelConnectionMultipleAgg (:2221) wall on a DIFFERENT message ('navigates $src._type — an unmapped non-association property of source class'), which is ClassSources.java:951-955, not this defect. They are not in this unit and I would not claim them.

**Falsifier** — Put a breakpoint / trace on ClassSources.build's throw at :622 and print the caller frame. If the requester is `composeModelToModel` reached from `sources.get(simpleModelMappingWithAssociation, dest::Person)` then part (1) alone (threading upstreamMapping) suffices and part (2) is unnecessary. If the requester is a navigation asking for `src::_Person` directly (because the marker's declared type is the src class) then part (2) is required and part (1) alone only relocates the wall.

<details><summary>Evidence read (7 citations)</summary>

- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/resolver/ClassSources.java:622-625 — `throw new MappingResolutionException("class '" + classFqn + "' is not mapped in mapping '" + mappingFqn + "'" ...)` — the exact message text of the failure
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/resolver/ClassSources.java:114-116 — `public ClassSource get(String mappingFqn, String classFqn) { return get(mappingFqn, classFqn, null, ""); }` — the upstream dispatcher is null on every 2-arg call
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/resolver/ClassSources.java:868-876 — the srcMapping ternary; with `upstreamMapping == null` and `binds(mappingFqn, _Person) == false` it still yields `mappingFqn`, then `inner = get(srcMapping, srcType.fqn(), upstreamMapping, contextKey);`
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/resolver/ClassSources.java:1256-1295 — `dispatch(explicitMapping, runtimeFqn, chainMappings, classFqn, exclude)` is the code that WOULD have found `relationalMapping`: `if (!chainMappings.isEmpty() && (!binds(explicitMapping, classFqn) ...)) { chainBinders ... if (chainBinders.size() == 1) return chainBinders.get(0); }`
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/resolver/StoreResolver.java:2717-2720 — the only site that passes the closure and contextKey
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/resolver/AssociationJoins.java:984-987 — `ClassSource target = pm.targetSetId() != null ? sources.get(cs.mappingFqn(), targetClass, pm.targetSetId(), null, "") : sources.get(cs.mappingFqn(), targetClass);` — both arms pass a null upstream
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/resolver/ClassSources.java:941-950 — the source-nav marker: `return new TypedPropertyAccess(new TypedVariable(inner.rowVar(), ExprType.one(new Type.ClassType(inner.classFqn()))), pa.property(), pa.info());` — `pa.info()` is the SOURCE-side type `src::_Person[*]`

</details>

---

## `testModelConnectionMultipleAgg`

| | |
|---|---|
| family | `executionPlan/tests` |
| sweep status | SHAPE |
| **verdict** | **REAL DEFECT** |
| effort | M |
| confidence | high |

**Root cause**

Identical to testModelConnectionAgg — the same simpleModelMapping composition dies eagerly on `type : $src._type` at ClassSources.java:951, reached from the unconditional binding loop at ClassSources.java:897-907, while the query demands only firstName and lastName. The only difference from testModelConnectionAgg is a second agg (`->size()` rather than `->count()`), which the golden renders as `count(...)` too.

**Fix**

Same change as testModelConnectionDeepFunction / testModelConnectionAgg — one fix covers all three. Note for this test specifically that `y->size()` must lower to `count(...)`, matching the golden's third column.

**How legend-engine does it** — /Users/neemsandv/legend/legend-engine/legend-engine-core/legend-engine-core-pure/legend-engine-pure-code-compiled-core/src/main/resources/core/store/m2m/chain.pure:189-193

**Risk** — Same as the other two. If `size()` over an agg lowers to something other than `count(...)`, that is an independent divergence surfaced, not caused, by lifting the wall.

**Also unblocks** — testModelConnectionAgg, testModelConnectionDeepFunction

**Falsifier** — Same as testModelConnectionAgg; additionally, if the third column renders as anything other than `count(substring("root".fullname, 0, locate(' ', "root".fullname)))`, the agg lowering for `size()` is a separate defect.

<details><summary>Evidence read (4 citations)</summary>

- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/resolver/ClassSources.java:897 — the eager loop over ctor.properties().
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/resolver/ClassSources.java:932-955 — null upstream binding + not an association => the H5b wall.
- /Users/neemsandv/legend/legend-engine/legend-engine-xts-relationalStore/legend-engine-xt-relationalStore-generation/legend-engine-xt-relationalStore-pure/legend-engine-xt-relationalStore-core-pure/src/main/resources/core_relational/relational/executionPlan/tests/executionPlanTest.pure:2221-2244 — the test body and its golden: `sql = select substring(...) as "FirstName", count(substring(...)) as "PersonCount", count(substring(...)) as "PersonCountViaSize" from SPerson as "root" group by "FirstName"` — again only `fullname` is read.
- /Users/neemsandv/legend/legend-engine/legend-engine-core/legend-engine-core-pure/legend-engine-pure-code-compiled-core/src/main/resources/core/store/m2m/tests/simple.pure:241-246 — the four bindings of the class mapping.

</details>

---

## `testMultiExpressionWithPlatformAndFromFunction`

| | |
|---|---|
| family | `executionPlan/tests` |
| sweep status | FAIL |
| **verdict** | **REAL DEFECT** |
| effort | XL |
| confidence | medium |

**Root cause**

Same mechanism as testFilterInWithResultSorcedFromAnExpression. The lambda `{names:String[*]| let upperNames = $names->map(n|$n->toUpper()); Person.all()->filter(e|$e.firm.legalName->in($upperNames))->from(simpleRelationalMapping, testRuntime())}` is parameterized and multi-statement, so `Typer.typeLambda` (compiler/spec/Typer.java:1908-1919) β-inlines the let, and the plan printer's Allocation loop (StatementExecutor.java:865-876) has nothing to emit. The golden requires the let value to stay on the PLATFORM as `Allocation(name=upperNames, value=PureExp(requires=[names(String[*])], expression=$names -> map([Routed Func:n:String[1] | $n -> toUpper();])))` — legend-lite has no PureExp node at all (the only occurrence of the token in core/src/main/java is a comment at builtin/Pure.java:1616 calling it 'a named wall at the K-arm until built'), and `StatementExecutor.allocationNode` (:905-975) can only print a Constant (literal), a Class-envelope Relational, or a scalar Relational — a platform-only let value hits `throw new NotImplementedException("plan: Allocation value without a getAll root")` at :930-934. The golden's inner `RelationalBlockExecutionNode` + `Allocation(inFilterClause_upperNames)` + `FreeMarkerConditionalExecutionNode` + `CreateAndPopulateTempTable` are likewise entirely absent (grep for each returns nothing in core/src/main/java). Independently, the inlined `in($names->map(toUpper))` lowers to a 2-element `Call(SqlFn.IN, ...)` and `EngineStyleH2`'s arity-only singleton collapse (sql/dialect/EngineStyleH2.java:1156-1161) rewrites it to `=`, which is a wrong-rows defect on its own.

**Fix**

Identical fix set to testFilterInWithResultSorcedFromAnExpression — do not fix them separately. (1) compiler/spec/Typer.java:1908-1919: preserve the multi-statement body of parameterized lambdas for the plan surface. (2) sql/dialect/EngineStyleH2.java:1156-1161 + lowering/Scalars.java:2273-2287: stop collapsing a 2-arity IN whose second operand is a collection-valued expression to `=`; distinguish scalar-singleton from collection at lowering (SqlExpr.Membership already carries that meaning, sql/SqlExpr.java:249-256). (3) Add the missing plan-node vocabulary to plan/PlanText.java and a fourth Allocation value form in StatementExecutor.allocationNode: a let whose value is platform-only prints `PureExp(type, resultSizeRange, requires=[<free var>(<type>[<mult>])], expression=<pure source text>)` — note the golden's `[Routed Func: ...]` spelling of the inner lambda, which requires a routed-lambda source printer legend-lite does not have either. (4) Add the processInOperation-equivalent post-pass described for testFilterInWithResultSorcedFromAnExpression. If (3)/(4) are out of budget, make StatementExecutor.planToString throw a NAMED wall for a parameterized multi-statement lambda whose let value has no getAll root, rather than silently printing a folded single-node plan.

**How legend-engine does it** — The PureExp allocation comes from the engine's platform-cluster plan generation; the inner IN machinery is /Users/neemsandv/legend/legend-engine/legend-engine-xts-relationalStore/legend-engine-xt-relationalStore-generation/legend-engine-xt-relationalStore-pure/legend-engine-xt-relationalStore-core-pure/src/main/resources/core_relational/relational/postprocessor/defaultPostProcessor/processInOperation.pure:48-50 (`prefixForWrapperAllocationNodeName() = 'inFilterClause_'`), :86-90 (the VarPlaceHolder swap keyed on a ZeroMany placeholder in the in-list), and :150-190 (CreateAndPopulateTempTableExecutionNode + FreeMarkerConditionalExecutionNode + the wrapping AllocationExecutionNode). The `${(instanceOf(x,"Stream") || ... > 50)?c}` condition text is built at :167-172 from `$conditionWithStreamHandled`.

**Risk** — Same as testFilterInWithResultSorcedFromAnExpression, with one extra: this test also needs `requires = [names(String[*])]` free-variable analysis and the engine's `[Routed Func: ...]` lambda source spelling, which is a printer surface nothing else in the corpus exercises — building it for one test is poor value. Ledgering it behind a loud wall is defensible; silently folding the let is not.

**Also unblocks** — testFilterInWithResultSorcedFromAnExpression (same unit) and testExecutionPlanGenerationForMultipleInWithTwoCollectionInputs (executionPlanTest.pure:2350) share causes (1)-(2) and (4).

**Falsifier** — The sweep detail for this test was truncated before the 'got' text, so I have not seen legend-lite's actual output. Re-run it and capture the full got string. If the got plan already contains an `Allocation(name = upperNames)` then cause (1) is wrong here (the lambda would have kept its body) and the failure is purely the missing PureExp/inFilterClause node text. If the got plan is a bare `Sequence(FunctionParametersValidationNode, Relational)` — the same shape as testFilterInWithResultSorcedFromAnExpression — cause (1) is confirmed.

<details><summary>Evidence read (6 citations)</summary>

- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/compiler/spec/Typer.java:1908-1919 — the parameterized-lambda let inlining that destroys the multi-statement body
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/StatementExecutor.java:865-876 — `for (int i = 0; i < lam.body().size() - 1; i++) { ... children.add(allocationNode(...)); }` — no iterations on a folded body
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/StatementExecutor.java:910-934 — allocationNode's three value forms (literal Constant, Class Relational, scalar Relational) then `throw new NotImplementedException("plan: Allocation value without a getAll root")`; a platform-only let value has no printable form
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/builtin/Pure.java:1615-1617 — `// pure-only plan shapes (no store): 2/3-arg spellings type; their plan text is a PureExp node — a named wall at the K-arm until built`, the project's own admission that PureExp is unimplemented
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/plan/PlanText.java:144-229 — the complete printer vocabulary; no PureExp, no RelationalBlockExecutionNode, no FreeMarkerConditionalExecutionNode, no CreateAndPopulateTempTable
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/sql/dialect/EngineStyleH2.java:1156-1161 — the arity-only IN→`=` collapse that also afflicts this query's `in($upperNames)`

</details>

---

## `testPlanForExecutionOption`

| | |
|---|---|
| family | `executionPlan/tests` |
| sweep status | ERROR |
| **verdict** | **MISSING FEATURE** |
| effort | L |
| confidence | high |

**Root cause**

The corpus file itself declares `Class meta::pure::executionPlan::tests::DummyExecutionOption extends ExecutionOption { paramsRequired(){ ^PlanVarPlaceHolder(...) }:PlanVarPlaceHolder[1]; }` (executionPlanTest.pure:1448-1451). Compiling that class's qualified-property RETURN type routes to TypeClassifier.classify -> findType, which resolves a bare NameRef only through `model.findClass(fqn) || Pure.findNativeClass(fqn)` (TypeClassifier.java:52-54). `meta::pure::executionPlan::PlanVarPlaceHolder` is a real platform class (engine executionPlan_generation.pure:121, `extends FunctionParameter`) but is absent from legend-lite's native catalog — Pure.java registers ExecutionOption (:242), ExecutionOptionContext (:243), FunctionParameter (:515) and NOT PlanVarPlaceHolder (grep over core/src/main returns zero hits). So classification throws at line TypeClassifier.java:91-92 with the observed text. That is only the first hop: even with the class registered, the test needs the whole ExecutionOption->plan-parameter feature (a user-built ^Extension carrying a FeatureExtension whose `extractVariablesFromExecutionOption` LAMBDA the plan generator must eval, plus `isExecutionOptionPresent` / `validateAndReturnExecutionOptionOfType`), none of which exists — the golden's `functionParameters = [dummyOptionParam:String[1]]` comes from the OPTION, not from the (zero-arg) query lambda.

**Fix**

Two layers, do them in order. (1) XS, unblocks the type wall: add to core/src/main/java/com/legend/builtin/Pure.java next to EXECUTION_OPTION_CONTEXT (line 243) — `public static final ClassDefinition PLAN_VAR_PLACE_HOLDER = nativeClass("native Class meta::pure::executionPlan::PlanVarPlaceHolder extends meta::pure::executionPlan::FunctionParameter { genericType: meta::pure::metamodel::type::generics::GenericType[0..1]; multiplicity: meta::pure::metamodel::type::Any[0..1]; }");` (name/supportsStream are inherited from FUNCTION_PARAMETER at :515). (2) L, what the assert actually needs: teach the plan generator about ExecutionOptionContext. Register natives for `meta::pure::executionPlan::isExecutionOptionPresent(ExecutionOptionContext[1], Class<Any>[1]):Boolean[1]` and `validateAndReturnExecutionOptionOfType(...):ExecutionOption[1]` with host implementations that filter `$ctx.executionOptions` by instanceOf; then, in StatementExecutor.sequencePlan/planToString, when the 5-arg executionPlan overload carries an ExecutionOptionContext argument, evaluate each extension's `extractVariablesFromExecutionOption` lambda over the context's options (the values are ^PlanVarPlaceHolder instances constructible by the existing NewChecker path once (1) lands) and prepend the resulting `name:Type[mult]` entries to the FunctionParametersValidationNode spelling built at StatementExecutor.java:836-863. Do NOT special-case this test in the harness.

**How legend-engine does it** — /Users/neemsandv/legend/legend-engine/legend-engine-core/legend-engine-core-pure/legend-engine-pure-code-compiled-core/src/main/resources/core/pure/executionPlan/executionPlan_generation.pure:121 declares `Class meta::pure::executionPlan::PlanVarPlaceHolder extends meta::pure::executionPlan::FunctionParameter`; :333-338 `stubExecutionOptionParameters` maps each extension's `extractVariablesFromExecutionOption` over the context's options; :203 and :207 splice those PlanVarPlaceHolders into `addFunctionParametersValidationNode`; :319/:326 are isExecutionOptionPresent / validateAndReturnExecutionOptionOfType.

**Risk** — Registering PlanVarPlaceHolder makes the two corpus ExecutionOption classes compile, which may move other tests in executionPlanTest.pure from a type wall to a deeper wall (louder, not wronger). Step (2) touches the FunctionParametersValidationNode spelling shared by every parameterized plan golden — it must only add entries when an ExecutionOptionContext argument is actually present.

**Falsifier** — Grep the built native catalog for `PlanVarPlaceHolder`: if it IS registered somewhere I did not find (e.g. a second catalog outside builtin/Pure.java), then the unknown-type throw comes from a different unregistered name and this diagnosis is wrong.

<details><summary>Evidence read (5 citations)</summary>

- /Users/neemsandv/legend/legend-engine/.../core_relational/relational/executionPlan/tests/executionPlanTest.pure:1448-1451 — `Class ...tests::DummyExecutionOption extends ExecutionOption { paramsRequired(){ ^PlanVarPlaceHolder(name='dummyOptionParam', ...) }:PlanVarPlaceHolder[1]; }`
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/compiler/element/TypeClassifier.java:91 — `findType(nr.name()).orElseThrow(... "Unknown type: '" + nr.name() + "' is not a known primitive, class, or enum")`
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/compiler/element/TypeClassifier.java:52-54 — `isClassFqn` = model.findClass || Pure.findNativeClass; nothing else can rescue an unknown name
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/builtin/Pure.java:242-243 — ExecutionOption and ExecutionOptionContext are registered native classes; a grep for PlanVarPlaceHolder over core/src/main/java returns nothing
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/builtin/Pure.java:515 — FUNCTION_PARAMETER native class (name, supportsStream), the declared supertype of PlanVarPlaceHolder in the engine

</details>

---

## `testPlanGenerationForMultipleExpressionsWithPropertyPath`

| | |
|---|---|
| family | `executionPlan/tests` |
| sweep status | SHAPE |
| **verdict** | **REAL DEFECT** |
| effort | M |
| confidence | medium |

**Root cause**

The plan's dotted template parameter (`${endDateCalendar.fiscalYear.value}`) is minted by exactly ONE Lowerer arm, and that arm folds only a SINGLE hop off a bare variable: `case TypedPropertyAccess p when p.source() instanceof TypedVariable v && letBindings.containsKey(v.name())` (Lowerer.java:2285-2295). The query here is `$endDateCalendar->toOne().fiscalYear.value` — two hops, with a multiplicity-erasing `toOne()` call between the variable and the first hop — so the arm never fires for the inner access; the accesses fall through to the generic class-value arm `new SqlExpr.StructGet(scalar(p.source(), columns), p.property())` (Lowerer.java:2371-2373). The renderer then walks the nested StructGets down to their base and, when that base is not a `SqlExpr.PlanParam`, throws the observed wall (EngineStyleH2.java:1038-1055). Even on the path where the base IS a PlanParam, the StructGet fallback hard-codes a QUOTED spelling `'${...}'` (EngineStyleH2.java:1049-1050) while the golden wants a bare `${endDateCalendar.fiscalYear.value}` — correct only because the leaf `FiscalYear.value` is Integer (Fold.planKindOf -> OTHER -> `${name}`, Fold.java:27-55). So the placeholder KIND must come from the LEAF property type, which only the PlanParam-minting arm can supply.

**Fix**

In core/src/main/java/com/legend/lowering/Lowerer.java, replace the one-hop arm at 2285-2295 with a PATH-collecting arm placed before the generic struct arm: walk a TypedPropertyAccess spine upward collecting property names, peeling multiplicity-erasing wrappers on the way down (TypedNativeCall whose callee key is in Pure.nativeKeysAt("toOne")/"toOneMany"/"first", and TypedCast); if the spine bottoms at a TypedVariable whose letBindings value is a SqlExpr.PlanParam, yield `new SqlExpr.PlanParam(pp.name() + "." + String.join(".", path), Fold.planKindOf(p.info().type()))` using the OUTERMOST access's type as the leaf kind; otherwise fall through to the existing StructGet behaviour. With that in place the EngineStyleH2 StructGet arm (1038-1055) is no longer reached for plan variables — leave its wall standing as the honest wall for genuine struct extraction, and do NOT relax its quoting (quoting must be decided by PlanParam.Kind, which the new arm now supplies correctly).

**How legend-engine does it** — The golden itself is the engine contract: executionPlanTest.pure:677-693 expects `sql = select ... where "root".year = ${endDateCalendar.fiscalYear.value}` — a bare dotted freemarker path over the Allocation-bound variable, with no relational navigation for fiscalYear.

**Risk** — The same arm serves ordinary (non-plan) let-bound instance values (`let person = ^Person(...); $person.firstName`) — the new peeling must keep yielding StructGet when the binding is a StructLit, not a PlanParam, or instance-literal field reads break. Peeling `first()` is only sound for a to-one narrowing; restrict the peel set to toOne/toOneMany/cast to stay conservative.

**Also unblocks** — Any other plan golden whose terminal reads a two-or-more-hop path off an Allocation-bound class variable (the datePeriods/reportEndDate family in executionPlan tests).

**Falsifier** — Run this test with LL_TMP_DEBUG and dump the SqlExpr handed to EngineStyleH2.expr: if the StructGet's base is ALREADY a SqlExpr.PlanParam (i.e. the wall is never reached and the only failure is quoting/text), then the defect is purely the renderer's unconditional `'${...}'` at EngineStyleH2.java:1049 and the Lowerer arm needs no path walk — only the kind.

<details><summary>Evidence read (6 citations)</summary>

- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/lowering/Lowerer.java:2285-2295 — the only dotted-PlanParam mint; guard requires `p.source() instanceof TypedVariable`, one hop only, kind = Fold.planKindOf(field type)
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/lowering/Lowerer.java:2371-2373 — generic arm: `case TypedPropertyAccess p when classLayout.apply(p.source().info().type()).isPresent() -> new SqlExpr.StructGet(scalar(p.source(), columns), p.property())`
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/sql/dialect/EngineStyleH2.java:1038-1055 — walks nested StructGets; PlanParam base -> `'${name.path}'` (always quoted); otherwise `throw new UnsupportedOperationException("plan: struct extraction has no engine-H2 spelling")`
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/StatementExecutor.java:865-885 — sequencePlan turns each lambda-body let into an Allocation and registers `params.put(let.name(), new SqlExpr.PlanParam(let.name(), kindOf(let type)))` before lowering the terminal
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/lowering/Fold.java:27-55 — planKindOf: Integer falls to OTHER, which EngineStyleH2:1013 spells bare `${name}` (matching the golden)
- /Users/neemsandv/legend/legend-engine/.../core_relational/relational/tests/query/datePeriods.pure:741-745 — `fiscalYear ( value: "fiscal year" )` is an EMBEDDED property mapping; the golden Allocation SQL selects no fiscalYear column, confirming the engine resolves the path purely as a freemarker placeholder, never as a join

</details>

---

## `testPlanWithLocalH2ConnectionWithSQL`

| | |
|---|---|
| family | `executionPlan/tests` |
| sweep status | SHAPE |
| **verdict** | **MISSING FEATURE** |
| effort | L |
| confidence | high |

**Root cause**

Two independent gaps, and the second one is a genuinely absent surface. (i) First assert: `…->cast(@SQLExecutionNode).connection` walls in the typer (Typer.java:2564-2566, `class <fqn> has no property '<p>'`) because the native catalog entry Pure.java:516 declares SQLExecutionNode with only sqlQuery + sqlComment — no `connection` — even though the real class has it (executionPlan.pure:69 `connection: DatabaseConnection[1]`). Even with the property declared there is no VALUE channel: PlanNode (PlanNode.java:20-21) carries no connection, planModel (StatementExecutor.java:2012) never threads one in, and walkProp (:1834-1845) has no arm for it nor for reading properties off a constructed instance (HostEval.HostInstance, HostEval.java:207-213). (ii) Second assert calls `meta::protocols::pure::vX_X_X::transformation::fromPureGraph::executionPlan::transformPlan($plan, extensions)` and then navigates the vX_X_X PROTOCOL metamodel. That function is a 370-line Pure transformation living OUTSIDE the loaded corpus root (legend-engine-core/…/core/pure/protocol/vX_X_X/transfers/executionPlan.pure:24), it dispatches through per-extension serializer contributions, and legend-lite has no protocol-transformation surface at all (grep for transformPlan across core/src/main/java returns nothing). So even a perfect fix for (i) leaves the test walled at the second assert. The wall is honest.

**Fix**

Recommendation: LEDGER this test as a named missing surface (protocol plan transformation). Do not chase it. If you nevertheless want the first assert to pass and the wall to move honestly to the second one, the bounded part is: (1) Pure.java:516 — add `connection: meta::external::store::relational::runtime::DatabaseConnection[1]` to the SQLExecutionNode catalog entry, citing executionPlan.pure:69 per the catalog's own citation contract. (2) PlanNode.java — add a `@Nullable Object connection` component (holds a HostEval.HostInstance). (3) StatementExecutor.planModel (:1941-2026) — after computing rtArg2, do `var connNi = connectionInstanceOf(rtArg2)` (:1118) and evaluate it to a HostInstance through HostEval (HostEval.java:548-556 already handles TypedNewInstance), threading it into the SQLExecutionNode PlanNode at :2012. (4) walkProp (:1817-1853) — add `case "connection" -> pn.connection();` to the PlanNode arm, and a new arm `if (recv instanceof com.legend.exec.HostEval.HostInstance hi) { return hi.properties().get(prop); }` so `.datasourceSpecification` and `.testDataSetupSqls` read through. The second assert stays a wall until a vX_X_X protocol plan transform exists — that is a separate, large piece of work (a protocol metamodel + a transformPlan/transformNode walk), and nothing in this test justifies starting it.

**How legend-engine does it** — legend-engine .../core_relational/relational/executionPlan/executionPlan.pure:69 (`connection: DatabaseConnection[1]`); relationalMappingExecution.pure:231 (`connection = $connection->updateConnection($extensions)`); legend-engine-core/.../core/pure/protocol/vX_X_X/transfers/executionPlan.pure:24 (transformPlan)

**Risk** — Adding `connection` to the native class changes typing GLOBALLY: two tests currently ERRORing on exactly that property (testDatabaseConnectionSQLPopulation, testDatabaseConnectionSQLPopulationLegacy — docs/RELATIONAL_CORPUS.md:1156-1157) will move to a new state. They assert `assertSize($resultConnection.testDataSetupSqls, 58)`, i.e. they need the engine's DDL-population of the connection, which is a further feature — expect them to become FAIL, not PASS. Tenet-2 trap: do not let the harness synthesize a connection object or short-circuit the second assert; a loud wall on transformPlan is the correct outcome.

**Also unblocks** — testDatabaseConnectionSQLPopulation and testDatabaseConnectionSQLPopulationLegacy stop ERRORing on the missing `connection` property (they will not pass — they need testDataSetupSqls populated from the store's DDL)

**Falsifier** — Grep the LOADED model for the protocol transform: `grep -rn 'transformation::fromPureGraph::executionPlan::transformPlan' <corpus root core_relational/relational>` returns only the CALL site in executionPlanTest.pure — no definition. If a definition were reachable in legend-lite's model (or legend-lite had a Java transformPlan), the second assert would not be a missing surface and this verdict would drop to REAL_DEFECT with a much smaller fix.

<details><summary>Evidence read (10 citations)</summary>

- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/builtin/Pure.java:516 — `native Class meta::relational::mapping::SQLExecutionNode extends …ExecutionNode { sqlQuery: String[1]; sqlComment: String[0..1]; }` — no connection
- /Users/neemsandv/legend/legend-engine/.../core_relational/relational/executionPlan/executionPlan.pure:62-73 — the real SQLExecutionNode declares sqlComment, sqlQuery, resultColumns, `connection: DatabaseConnection[1]`, metadata, …
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/compiler/spec/Typer.java:2564-2566 — `throw new TypeInferenceException("class " + ct.fqn() + " has no property '" + ap.property() + "'")`, the exact wall text in the brief
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/harness/PlanAsserts.java:188-199 — the plan assert catches NotImplemented/LegendCompile/UnsupportedOperation and reports `plan wall: <msg>` as SHAPE
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/StatementExecutor.java:1118-1139 — `connectionInstanceOf(runtimeArg)` already finds the runtime's ^DatabaseConnection/^TestDatabaseConnection/^RelationalDatabaseConnection TypedNewInstance (used today only for the plan's connection NAME spelling)
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/builtin/Pure.java:264,268 — LOCAL_H2_DATASOURCE_SPECIFICATION (with testDataSetupSqls: String[*]) and RELATIONAL_DATABASE_CONNECTION (with datasourceSpecification) already exist, so the navigation after `.connection` would type-check
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/exec/HostEval.java:207-213 — HostInstance(classFqn, properties) is the generic ^Class(...) value; StatementExecutor.walkProp has no arm for it
- /Users/neemsandv/legend/legend-engine/legend-engine-core/legend-engine-core-pure/legend-engine-pure-code-compiled-core/src/main/resources/core/pure/protocol/vX_X_X/transfers/executionPlan.pure:24-34 — transformPlan builds the whole protocol ExecutionPlan and recurses via transformNode with per-extension dispatch (370-line file, outside core_relational)
- /Users/neemsandv/legend/legend-engine/.../core_relational/relational/relationalMappingExecution.pure:241-246 — `updateConnection` is the identity for a plain DatabaseConnection, so the plan's connection IS the runtime connection instance verbatim (why the golden expects ['a','b'] unchanged)
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/docs/RELATIONAL_CORPUS.md:1144 — current sweep entry still SHAPE on `no property 'connection'`

</details>

---

## `testPreprocessFunctionOnRuntime`

| | |
|---|---|
| family | `executionPlan/tests` |
| sweep status | SHAPE |
| **verdict** | **TESTS ENGINE INTERNALS** |
| effort | XL |
| confidence | high |

**Root cause**

The test does M3 surgery on legend-engine's own function metamodel: it reads `$lambda.expressionSequence`, builds `^SimpleFunctionExpression(func=from_T_m__PackageableRuntime_1__T_m_, parametersValues=...)`, and constructs `^FunctionDefinition<{->Any[1]}>(expressionSequence = $withFrom)` before handing that hand-built FunctionDefinition to executionPlan. legend-lite registers `meta::pure::metamodel::function::FunctionDefinition<F>` as an OPAQUE native carrier with no declared properties (Pure.java:526), so the constructor key `expressionSequence` fails property lookup in NewChecker and throws `class 'meta::pure::metamodel::function::FunctionDefinition' has no property 'expressionSequence'` (NewChecker.java:94 — note the quoted class name in the message, which is NewChecker's format, not the Typer's unquoted one at Typer.java:2565). Underneath that, the feature the test is really about — `EngineRuntime.preprocessFunction` being evaluated during plan generation (the corpus's addLimit rewriting a select SFE into limit(…,10)) — does not exist in legend-lite at all: grep for `preprocessFunction`/`addLimit` over core/src/main/java returns nothing.

**Fix**

Do not fix; ledger it. Making this pass requires an M3 ValueSpecification metamodel in legend-lite (ValueSpecification / SimpleFunctionExpression / InstanceValue / FunctionDefinition.expressionSequence / GenericType, with construction, match-dispatch and property reads) AND the ability to re-enter the compiler on a REFLECTIVELY CONSTRUCTED function body — the exact 'corpus code walks the plan/M3 metamodel' surface that Pure.java:522-528 and :1567 already declare out of scope. If the hook itself is ever wanted independently of M3, the honest slice is: register `preprocessFunction: Function<Any>[0..1]` on the EngineRuntime native class and, in StatementExecutor's plan/exec paths, apply a RECOGNIZED preprocess function structurally (e.g. an addLimit shape) to the typed body before lowering — but that would not make THIS test pass, because the test's own input is a hand-built FunctionDefinition. Keep the loud wall.

**How legend-engine does it** — /Users/neemsandv/legend/legend-engine/legend-engine-core/legend-engine-core-pure/legend-engine-pure-code-compiled-core/src/main/resources/core/pure/executionPlan/executionPlan_generation.pure:131 `preprocessFunction(f:FunctionDefinition<Any>[1], context, extensions)` and :147 `if($runtime.preprocessFunction->isEmpty(), |$f, |$runtime.preprocessFunction->at(0)->toOne()->eval($f, $runtime->at(0)))` — the hook is applied to the M3 FunctionDefinition before routing (:155).

**Risk** — Adding `expressionSequence` to the FunctionDefinition native class to silence the message would be the worst outcome: it would let ^FunctionDefinition(...) type and then produce garbage or a wrong plan downstream. The property-free carrier is load-bearing.

**Also unblocks** — The other M3-surgery tests in this corpus (concatenateTemporalTdsQueries-style bodies, testRoutingContextBuilderFunctions) share the same wall class.

**Falsifier** — If NewChecker is not the thrower — e.g. the harness reports the same text for the `$lambda.expressionSequence` READ — then the failing hop is Typer.accessProperty instead; the verdict is unchanged but the cited line moves. Confirm by checking whether the message keeps the quotes around the class FQN (NewChecker) or not (Typer).

<details><summary>Evidence read (5 citations)</summary>

- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/builtin/Pure.java:526 — `native Class meta::pure::metamodel::function::FunctionDefinition<F> extends ...Function<F> {}` — an empty body, deliberately property-free
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/compiler/spec/NewChecker.java:94 — `throw new TypeInferenceException("class '" + ni.className() + "' has no property '" + name + "'")` — matches the observed message verbatim (quoted class)
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/compiler/spec/Typer.java:2565 — the property-READ variant spells `class <fqn> has no property` WITHOUT quotes, so the failure is the constructor, not the `$lambda.expressionSequence` read
- grep over /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/ for `preprocessFunction|addLimit` — zero hits: the runtime preprocess hook is unimplemented
- /Users/neemsandv/legend/legend-engine/.../executionPlan/tests/executionPlanTest.pure:1695-1717 — the corpus's own addLimit/rewriteFunction bodies match over SimpleFunctionExpression and rebuild `^$l(expressionSequence = ...)`: the test is a metamodel-rewrite test, not a query test

</details>

---

## `testPureExecutionStrategyForCreateAndPopulateTempTableExecutionNode`

| | |
|---|---|
| family | `executionPlan/tests` |
| sweep status | SHAPE |
| **verdict** | **MISSING FEATURE** |
| effort | XL |
| confidence | high |

**Root cause**

Identical first wall to testPureExecutionStrategyForRelationalInstantiationExecutionNode — `NewChecker.check` rejects `^Service(...)` because `meta::legend::service::metamodel` is not in the assembled model (`Corpus` registers only the relational tree and the M2M test model). Beyond that shared wall this test is STRICTLY harder: it asserts `executeServiceTests(6)` for two services whose filters are large IN lists (a 53-element String `in` and a 51-element Integer `in`), so the expected plan has SIX nodes — the engine's large-IN strategy splits into `CreateAndPopulateTempTableExecutionNode` + `SQLExecutionNode` + sequence/free-marker-conditional nodes. legend-lite has no temp-table plan-node vocabulary at all: `PlanNode` is a flat record with an ad-hoc `kind` string and the plan builder never mints create/populate temp-table nodes, so even with the Service classes loaded the node count can never reach 6.

**Fix**

Same Step 1 as the sibling test (add the core_service source root to `Corpus.java` beside `M2M_TESTS`), then the additional platform work this test alone needs: implement the engine's large-IN temp-table strategy in the plan builder — when an `in` operand exceeds the threshold, emit `CreateAndPopulateTempTableExecutionNode` (+ its populate/free-marker-conditional siblings) and rewrite the filter to a join against the temp table, so `allNodes()` yields the engine's 6. The engine spec for the split is `processInOperation.pure` (the large-IN branch) as recorded in docs/CORPUS_BURNDOWN_INDEX.md's 'Large-IN / temp-table plan vocabulary absent' row. Honest recommendation: LEDGER this one. It is the deepest test in the unit — Service DSL + plan-handle execute + temp-table node vocabulary + reflective assert evaluation — and none of that surface is partially present. A loud `unknown class 'Service'` is a better outcome than a half-built temp-table plan.

**How legend-engine does it** — legend-engine .../core_relational/relational/executionPlan/tests/executionPlanExecutionTest.pure:67-142 (the two large-IN services) and :145-153 (`executeServiceTests`, the node-count assert)

**Risk** — Same source-root blast radius as the sibling. Tenet-2 trap: do NOT relax the node-count assert or teach the harness to skip `assertEquals/2` inside `executeServiceTests` — the node count IS the test's contract; the temp-table split is a platform shape.

**Also unblocks** — testPureExecutionStrategyForRelationalInstantiationExecutionNode shares the Service-root wall (but not the temp-table gap).

**Falsifier** — Add the core_service source root and re-run. If the detail changes to a node-count mismatch (`assertEquals: expected 6, got <n>`) rather than a class-resolution wall, the Service-root diagnosis is confirmed and the temp-table gap is the remaining cause. If it changes to a wall on `->in` with a 50+ element collection, the threshold logic is the next stop instead.

<details><summary>Evidence read (6 citations)</summary>

- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/compiler/spec/NewChecker.java:67-70 — the `unknown class '…' in ^…(…)` throw that produced the sweep message
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/test/java/com/legend/rcorpus/Corpus.java:46-60 — the two registered source roots; no core_service
- legend-engine .../core_relational/relational/executionPlan/tests/executionPlanExecutionTest.pure:67-142 — the test body: two `^Service` values whose funcs filter on `$p.firstName->in([53 strings])` and `$p.age->in([51 integers])`, each asserted with `executeServiceTests(6)`
- legend-engine .../core_relational/relational/executionPlan/tests/executionPlanExecutionTest.pure:145-149 — `assertEquals($expectedExecutionNodesCount, $plan.rootExecutionNode->meta::pure::executionPlan::allNodes(...)->size())` is the node-count assert
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/plan/PlanNode.java:15-21 — the doc comment enumerates the only kinds the model knows (SequenceExecutionNode, FunctionParametersValidationNode, RelationalInstantiationExecutionNode, SQLExecutionNode); no CreateAndPopulateTempTable / RelationalBlock / FreeMarkerConditional
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/StatementExecutor.java:1299-1302 — the `allNodes` walk exists and simply flattens `PlanNode.allNodes()`, so the count is whatever the builder produced

</details>

---

## `testPureExecutionStrategyForRelationalInstantiationExecutionNode`

| | |
|---|---|
| family | `executionPlan/tests` |
| sweep status | SHAPE |
| **verdict** | **MISSING FEATURE** |
| effort | XL |
| confidence | high |

**Root cause**

The test constructs `^Service(pattern=..., execution=^PureSingleExecution(...), test=^SingleExecutionTest(...))` from `meta::legend::service::metamodel` and calls `->executeServiceTests(2)`. `NewChecker.check` throws `unknown class 'Service' in ^Service(…)` because `t.model().findClass("Service")` is empty: the Legend Service DSL metamodel lives at legend-engine-xts-service/legend-engine-language-pure-dsl-service-pure/src/main/resources/core_service/service/metamodel.pure, and `Corpus` only assembles two source roots — the relational corpus tree (`RELATIONAL`) and the platform M2M test model (`M2M_TESTS`). The wall is honest and it is the FIRST of several: even with the Service classes loaded, `executeServiceTests` needs (a) `executionPlan` over the service's func, (b) `$plan.rootExecutionNode->allNodes(...)->size() == 2`, i.e. a plan tree that actually contains a `RelationalInstantiationExecutionNode` wrapping a `SQLExecutionNode` (legend-lite's `PlanNode` has only kind/children/sqlQuery/functionParameters and its kind vocabulary is produced ad hoc by PlanText), (c) `setUpData` -> `meta::alloy::service::execution::setUpDataSQLs(csv, database)` seeding the test DB (a `setUpDataSQLs` surface does exist in `SeedSqlForms`), and (d) `$plan->execute($a.parametersValues, extensions)` — executing a plan HANDLE with parameters — followed by reflective `$a.assert->evaluate(...)`. None of (b) or (d) exist.

**Fix**

Do not spot-patch; this is a stack, and the cheap half is a trap. Step 1 (legitimate, model assembly not harness compensation): add a `SERVICE_DSL` source root to `core/src/test/java/com/legend/rcorpus/Corpus.java` beside `M2M_TESTS` — `ENGINE_ROOT.resolve("legend-engine-xts-service/legend-engine-language-pure-dsl-service-pure/src/main/resources/core_service/service")` — and include it in the assembled model exactly as M2M_TESTS is. That converts the wall from 'unknown class Service' into the real one. Step 2 (the actual platform work): teach `StatementExecutor`'s plan builder to emit a `RelationalInstantiationExecutionNode` wrapper above the `SQLExecutionNode` for a class-typed (non-TDS) relational root so `allNodes()->size()` is 2, and add a `$plan->execute(parameterValues, extensions)` surface that runs the built plan's SQL with the supplied parameter bindings and returns a `Result` whose `.values` is the TDS. Step 3: wire `setUpData` through the existing `SeedSqlForms`/`setUpDataSQLs` reader plus `executeInDb`. If steps 2-3 are not being funded now, the right answer is to LEDGER this test and leave the wall loud — a Service-DSL root added without steps 2-3 just moves the wall one line further and costs a build.

**How legend-engine does it** — legend-engine .../core_relational/relational/executionPlan/tests/executionPlanExecutionTest.pure:145-153 (`executeServiceTests`) and :155-165 (`setUpData` -> `meta::alloy::service::execution::setUpDataSQLs`); the Service metamodel itself at legend-engine-xts-service/legend-engine-language-pure-dsl-service-pure/src/main/resources/core_service/service/metamodel.pure

**Risk** — Adding a new source root widens the assembled model for EVERY corpus test — name collisions or parse walls in core_service would regress unrelated families. Gate it by running the full sweep, not this test alone. Tenet-2 trap: do NOT special-case `^Service(...)` in `EngineTestExecutor`/`TestBody` to synthesise a fake Service value so the plan assert can proceed — the class model is platform-owned Knowledge, and a fabricated Service would make the node-count assert pass against a plan legend-lite never really built.

**Also unblocks** — testPureExecutionStrategyForCreateAndPopulateTempTableExecutionNode (same file, same stack). The docs/CORPUS_BURNDOWN_INDEX.md row groups these with testRelationalProjectionWithExternalFormat / testEnumPushDownWithExternalFormat / testRoutingContextBuilderFunctions / testPreprocessFunctionOnRuntime as 'surfaces outside the registered roots', but those need DIFFERENT roots (external-format / protocol), not core_service — the Service root alone unblocks only these two.

**Falsifier** — Add the core_service source root only and re-run this single test. If the failure detail changes from `unknown class 'Service' in ^Service(…)` to a wall naming `allNodes`, `execute` on a plan handle, or `setUpDataSQLs`, the diagnosis is confirmed and the remaining stack is exactly as described. If it still says `unknown class 'Service'`, the metamodel.pure path or the assembly step is wrong, not the source root.

<details><summary>Evidence read (6 citations)</summary>

- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/compiler/spec/NewChecker.java:67-70 — `if (t.model().findClass(ni.className()).isEmpty()) throw new TypeInferenceException("unknown class '" + ni.className() + "' in ^" + ni.className() + "(…)");` — verbatim the message in the sweep detail
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/test/java/com/legend/rcorpus/Corpus.java:46-60 — only `RELATIONAL` (core_relational/relational) and `M2M_TESTS` (core/store/m2m/tests) are registered as source roots; no core_service root
- legend-engine .../core_relational/relational/executionPlan/tests/executionPlanExecutionTest.pure:28-65 — the test body: `^Service(... execution = ^PureSingleExecution(...), test = ^SingleExecutionTest(... asserts = [^meta::legend::service::metamodel::TestContainer(...)]))` then `$simpleService->executeServiceTests(2)`
- legend-engine .../core_relational/relational/executionPlan/tests/executionPlanExecutionTest.pure:145-153 — `executeServiceTests` = executionPlan + `assertEquals($expectedExecutionNodesCount, $plan.rootExecutionNode->allNodes(ext)->size())` + `$t->setUpData(...)` + `$plan->execute($a.parametersValues, ext)` + `$a.assert->evaluate(...)`
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/plan/PlanNode.java:20-43 — the whole plan-handle model is `record PlanNode(String kind, List<PlanNode> children, String sqlQuery, List<Param> functionParameters)` with an `allNodes()` walk; no node-kind vocabulary beyond what PlanText mints
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/harness/EngineTestExecutor.java:878-895 — `scoreAssert` stamps `assert form '<fn>/<arity>' is not supported yet` and prefixes the PLATFORM cause when one was recorded, which is why the row reads 'assert form assertEquals/2 … — plan wall: unknown class Service'

</details>

---

## `testQuoteIdentifiersFlagWithGraphFetch`

| | |
|---|---|
| family | `executionPlan/tests` |
| sweep status | FAIL |
| **verdict** | **MISSING FEATURE** |
| effort | L |
| confidence | high |

**Root cause**

The quoteIdentifiers flag itself WORKS: the SQL legend-lite produced is character-identical to the SQL inside the engine's expected node (`select "root"."ID" as "pk_0", "root"."NAME" as "name" from "productSchema"."productTable" as "root"`). What is missing is the plan NODE VOCABULARY for a graph-fetch query. `StatementExecutor.planToString` funnels every single-node plan into `PlanText.single(...)` (StatementExecutor.java:629-639), and PlanText only knows the relational forms — `single` (a `Relational` block), `typeBlock`, `sequence`, `functionParametersNode`, `allocation`, `scalarTypeBlock`, `scalarRelational`, `constant` (PlanText.java:31-233). It has no `PureExp`, no `StoreMappingGlobalGraphFetch`, no `RelationalGraphFetch`, no bare `SQL` node, and no notion of `localTreeIndices`/`dependencyIndices`/`nodeIndex`/`PartialClass[...propertiesWithParameters=[...]]` type blocks. So a `Product.all()->graphFetch($g)->serialize($g)` query renders as if it were an ordinary relational read: `Relational(type=String resultSizeRange=1 resultColumns=[...] sql=...)` — which is exactly the observed 'got'.

**Fix**

Add a graph-fetch plan-text branch, in plan/PlanText.java plus a dispatch in StatementExecutor.planToString. (a) In planToString, before the `PlanText.single` call at :629-639, detect a terminal whose typed node is TypedSerialize (optionally over TypedGraphFetch) and route to a new `PlanText.graphFetch(...)`. (b) In PlanText add three forms mirroring the engine printers: `pureExp(typeBlock, expressionText, childBlock)` -> `PureExp\n(\n  type = ...\n  expression = ->serialize(#{...}#)\n  ( <child> )\n)`; `storeMappingGlobalGraphFetch(typeBlock, storeFqn, localNodeBlock, children, localTreeIndices, dependencyIndices)`; and `relationalGraphFetch(typeBlock, nodeIndex, sqlNodeBlock, children)` whose inner node is a bare `SQL(type = meta::pure::metamodel::type::Any, resultColumns = [...], sql = ..., connection = ...)` rather than the class-envelope `Relational` block. (c) The type block needs a PartialClass spelling — `PartialClass[impls=[(<class>|<mapping>.<setImplId>)],propertiesWithParameters=[<tree leaf props>]]` — derivable from the existing ScanRelations.rootImpl output plus the TypedGraphTree property names. The serialize expression text is the source graph-tree spelling, which TypedSerialize already carries.

**How legend-engine does it** — /Users/legend-engine printers: core/pure/executionPlan/executionPlan_print.pure:47 spells `PureExp\n(...expression = <asString of the serialize expression>...)` for PureExpressionPlatformExecutionNode; /Users/neemsandv/legend/legend-engine/.../core_relational/relational/graphFetch/relationalGraphFetch.pure:1079-1084 spells `RelationalGraphFetch\n( header + nodeIndex = N + relationalNode = <planNodeToString of the SQL node> + children = [...] )`.

**Risk** — planToStringWithoutFormatting strips whitespace, so exact indentation is forgiving here, but the same forms will be compared WITH formatting by other graphFetch plan goldens — build the blocks with the shared indent() helper (PlanText.java:133) so both spellings stay consistent. Routing on TypedSerialize must not capture non-graph serialize uses (e.g. TDS serialization) — key on the presence of a graph tree.

**Also unblocks** — Every other graphFetch plan-text golden in the executionPlan and graphFetch families that currently renders as a bare Relational node.

**Falsifier** — If a graphFetch plan golden elsewhere in the corpus already passes, then some graph-fetch plan-text path exists that I did not find, and the gap is narrower than 'no vocabulary at all'. Grep for `StoreMappingGlobalGraphFetch` in core/src/main — I found zero hits, which is the basis for this verdict.

<details><summary>Evidence read (4 citations)</summary>

- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/plan/PlanText.java:31-233 — the complete public form list; no graph-fetch node spellings exist anywhere in the file (799 lines)
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/StatementExecutor.java:629-639 — the single-node plan path calls PlanText.single unconditionally once a getAll root is found, regardless of a TypedSerialize/TypedGraphFetch terminal
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/StatementExecutor.java:592 and :1000-1012 — quoteIdentifiersOf reads the runtime's connection flag and threads it into the EngineStyleH2 renderer, which is why the SQL text already matches the golden
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/docs/burndown-2026-08-14/master-classification.csv:89 and docs/BURNDOWN_EXPLANATIONS.md (testQuoteIdentifiersFlagWithGraphFetch row) — an earlier pass reached the same fix site (PlanText has 6 forms only)

</details>

---

## `testRelationalProjectionWithExternalFormat`

| | |
|---|---|
| family | `executionPlan/tests` |
| sweep status | SHAPE |
| **verdict** | **MISSING FEATURE** |
| effort | XL |
| confidence | high |

**Root cause**

The test's first statement builds `$extensions` by calling `meta::external::format::shared::transformation::tests::exampleExternalFormatExtension()`. That function is defined in legend-engine-core at core/pure/binding/transformation/tests/externalFormatContract.pure:112 — a path the corpus runner never loads: Corpus.java exposes exactly two source roots, `core_relational/relational` (RELATIONAL) and `core/store/m2m/tests` (M2M_TESTS). With no user-catalog entry and no native shim, Typer.checkGeneric finds zero candidates and throws the observed 'unknown function ... unported platform function' (Typer.java:1448-1451). Because the assert is a planToString golden, PlanAsserts.planTextAssert catches the LegendCompileException and stamps it 'plan wall: ...' (PlanAsserts.java:198), which scoreAssert re-wraps as the SHAPE line in the sweep. The wall is honest: even with that one function resolvable, the test needs the whole external-format surface — `meta::external::format::shared::functions::externalize(TabularDataSet[1], String[1])` (declared in engine-core functions.pure:146-150, also unloaded), the ExternalFormatContract/Binding metamodel, and the `ExternalFormat_ExternalizeTDS` plan node and its printer. A grep of legend-lite's whole main source finds zero occurrences of `externalize`, `ExternalizeTDS` or `external::format` as a Legend concept.

**Fix**

Do not chase this now — it is a genuine subsystem, not a bug. If it is ever scheduled: (a) add core/pure/binding as a third Corpus source root (Corpus.java, next to M2M_TESTS) so ExternalFormatContract/Binding/exampleExternalFormatExtension compile as user code; (b) port `meta::external::format::shared::functions::externalize(TabularDataSet[1], String[1]):String[1]` as a plan-time-only function (engine marks the graph-fetch variants NotImplementedFunction — 'Implemented by execution plans'); (c) teach the plan channel to wrap the relational node in an `ExternalFormat_ExternalizeTDS` node carrying `contentType`, and add its printer form to com.legend.plan.PlanText. Until (a)-(c) exist, KEEP the wall — it is correctly loud and correctly attributed.

**How legend-engine does it** — /Users/neemsandv/legend/legend-engine/legend-engine-core/legend-engine-core-pure/legend-engine-pure-code-compiled-core/src/main/resources/core/pure/binding/transformation/tests/externalFormatContract.pure:112 (the extension the test builds) and .../core/pure/binding/functions/functions.pure:146 (externalizeTDSFunctions).

**Risk** — Adding core/pure/binding as a corpus root pulls a large new element set into every test run and could surface new compile walls in unrelated families; it should be gated and swept separately. Tenet-2 trap: do NOT stub exampleExternalFormatExtension in the harness to get past the extensions let — the plan would then be generated with a fake extension list and the golden would be wrong for a second reason.

**Also unblocks** — testEnumPushDownWithExternalFormat (executionPlanTest.pure:2672) — identical wall, identical prerequisite.

**Falsifier** — Add core/pure/binding to the Corpus source roots and re-run: if the failure moves from 'unknown function exampleExternalFormatExtension' to a wall on `externalize` or on the ExternalFormat_ExternalizeTDS node, the layered-missing-feature reading is confirmed; if it passes outright, legend-lite already had the externalize plan surface and my grep was wrong.

<details><summary>Evidence read (6 citations)</summary>

- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/test/java/com/legend/rcorpus/Corpus.java:49 — RELATIONAL = .../core_relational/relational; Corpus.java:57 — M2M_TESTS = .../core/store/m2m/tests. Those are the only roots.
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/compiler/spec/Typer.java:1448 — `throw new TypeInferenceException("unknown function '" + af.function() + "' — no function of this name in the native or user catalog ...")`, exactly the observed text.
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/harness/PlanAsserts.java:198 — `return EngineTestExecutor.unsupported("plan wall: " + pw.getMessage());` in planTextAssert's catch of LegendCompileException.
- /Users/neemsandv/legend/legend-engine/legend-engine-core/legend-engine-core-pure/legend-engine-pure-code-compiled-core/src/main/resources/core/pure/binding/transformation/tests/externalFormatContract.pure:112 — `function meta::external::format::shared::transformation::tests::exampleExternalFormatExtension(): Extension[1]`, outside both loaded roots.
- /Users/neemsandv/legend/legend-engine/legend-engine-core/legend-engine-core-pure/legend-engine-pure-code-compiled-core/src/main/resources/core/pure/binding/functions/functions.pure:146 — externalizeTDSFunctions() registering `externalize_TabularDataSet_1__String_1__String_1_`, the function this test's query calls.
- grep over /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java for `externalize|ExternalizeTDS|external::format` returns only unrelated hits (ModelNormalizer 'externalizes body sites', SynthFqn docs) — no external-format subsystem exists.

</details>

---

## `testSQLCommentsInPlan`

| | |
|---|---|
| family | `executionPlan/tests` |
| sweep status | SHAPE |
| **verdict** | **REAL DEFECT** |
| effort | S |
| confidence | high |
| **adversarial review** | **CONFIRMED** |

**Root cause**

The brief's message is STALE. Commit 787c391b added `sqlComment: String[0..1]` to the native SQLExecutionNode (Pure.java:516), so the typer no longer walls; the CURRENT sweep result recorded in docs/RELATIONAL_CORPUS.md:1147 is `FAIL … expected -- "executionTraceID" : "${execID}", got []`. The remaining defect is that the plan VALUE model has no sqlComment channel: com.legend.plan.PlanNode (PlanNode.java:20-21) is a record of (kind, children, sqlQuery, functionParameters) and StatementExecutor.walkProp's PlanNode arm (:1834-1845) answers only rootExecutionNode / executionNodes / sqlQuery / functionParameters, with `default -> null`. Because the receiver at `.sqlComment` is a LIST (cast over filter), walkProp's list arm (:1822-1833) drops the nulls and returns an EMPTY list, which walkResult (:1919-1927) turns into an empty Collection — hence `got []` rather than a wall. In the engine the value is a CONSTANT: the default post-processor chain stamps every SelectSQLQuery with `comment = '-- "executionTraceID" : "${execID}"'` (defaultPostProcessor.pure:71, registered in sqlQueryDefaultPostProcessors at :57-65), and generateSQLExecutionNode copies it with `sqlComment = $query.comment` (relationalMappingExecution.pure:227).

**Fix**

Three small edits. (1) core/src/main/java/com/legend/plan/PlanNode.java — add a `@Nullable String sqlComment` component (place it after sqlQuery; the compact constructor needs no change), and add a javadoc line citing defaultPostProcessor.pure:71 for why it is a constant. (2) core/src/main/java/com/legend/StatementExecutor.java:2012 — construct the SQL node with the engine's constant: `new PlanNode("SQLExecutionNode", List.of(), es.sql(), "-- \"executionTraceID\" : \"${execID}\"", List.of())`. Prefer hoisting the literal to a named constant on PlanNode (e.g. `PlanNode.EXEC_TRACE_COMMENT`) so the one spelling is shared with any future JSON serializer. (3) StatementExecutor.walkProp:1835-1844 — add `case "sqlComment" -> pn.sqlComment();` to the PlanNode switch. Do NOT touch PlanText: the printer must keep omitting the comment (storeContract.pure:153/156).

**How legend-engine does it** — legend-engine .../core_relational/relational/postprocessor/defaultPostProcessor/defaultPostProcessor.pure:71 (prependSQLComments) with relationalMappingExecution.pure:227 (sqlComment = $query.comment)

**⚠ Correction from adversarial review** — Substantively right, mechanically under-specified in one place: adding a 5th record component to PlanNode breaks ALL FOUR construction sites, not just :2012. grep shows `new com.legend.plan.PlanNode(` at StatementExecutor.java:2012 (SQLExecutionNode), :2015 (RelationalInstantiationExecutionNode), :2021 (FunctionParametersValidationNode) and :2024 (SequenceExecutionNode). The other three need a `null` sqlComment argument or the file will not compile. That is three extra mechanical edits, so effort S (not XS) is right, but the fix description implying a single StatementExecutor edit is incomplete. Everything else — the PlanNode component, the constant at the SQL node, and `case "sqlComment" -> pn.sqlComment();` in the walkProp switch — is correct as written, and leaving PlanText alone is verified safe against the plan-text goldens.

<details><summary>Adversarial review notes (CONFIRMED)</summary>

Every claim checked and held. The brief-is-stale claim is right: Pure.java:516 currently declares `SQLExecutionNode extends ExecutionNode { sqlQuery: String[1]; sqlComment: String[0..1]; }`, so the typer no longer walls, and the corpus records the current status as `FAIL … expected -- "executionTraceID" : "${execID}", got []`. The mechanism is exactly as described and I traced it against the real test: executionPlanTest.pure:2518-2523 is `let sqlComment = $plan.rootExecutionNode->allNodes(…)->filter(node|$node->instanceOf(SQLExecutionNode))->cast(@SQLExecutionNode).sqlComment;` — the receiver at `.sqlComment` is a LIST, so walkProp's list arm (StatementExecutor.java:1821-1833) maps over it, walkProp's PlanNode arm (:1834-1845) hits `default -> null`, the null is dropped by `else if (v != null)`, and the result is an empty list which walkResult (:1920-1928) turns into an ExecutionResult.Collection — printing `[]`, not a wall. That is precisely the observed output. PlanNode.java:20-21 is verified to be the 4-component record with no sqlComment. The engine side is a genuine constant: prependSQLComments (defaultPostProcessor.pure) returns `^Result<SelectSQLQuery|1>(values= ^$selectSQLQuery(comment= '-- "executionTraceID" : "${execID}"'))` with no dependence on connection or dialect, and it is the last member of sqlQueryDefaultPostProcessors(), which relationalMappingExecution.pure folds over EVERY plan-generated select before generateSQLExecutionNode copies `sqlComment = $query.comment`. I also ran the falsifier's own check: grepping the engine for 'executionTraceID' in .pure sources returns this one literal plus Snowflake's separate ALTER SESSION SET QUERY_TAG mechanism (executionPlanTestSnowflake.pure:56), so a hard-coded constant is faithful. And storeContract.pure's printPlanNodeToString for SQLExecutionNode / RelationalInstantiationExecutionNode emits only header/resultColumns/sql/connection — no sqlComment — so adding the field cannot perturb any plan-TEXT golden, confirming the 'do not touch PlanText' instruction.

</details>

**Risk** — The constant is unconditional in the engine for the pure-execution plan path; do not gate it on a flag. Tenet-2 trap: do not answer sqlComment inside the harness's assert layer (PlanAsserts) — the plan model owns the value. Verify no plan-text golden regresses: the printer at storeContract.pure:153/156 does not print sqlComment, and PlanText.single (PlanText.java:78-83) emits type/resultColumns/sql/connection only, so leaving PlanText untouched keeps every existing golden byte-identical.

**Falsifier** — If walkProp already answered sqlComment, the sweep would print the string rather than `[]` — docs/RELATIONAL_CORPUS.md:1147 shows `got []`, which is exactly what a null property read through the list arm produces. Conversely, if the constant were connection- or dialect-dependent, some other corpus golden would show a different comment text: grep the corpus for 'executionTraceID' shows only the one literal (plus Snowflake's separate QUERY_TAG mechanism), so a constant is right.

<details><summary>Evidence read (9 citations)</summary>

- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/docs/RELATIONAL_CORPUS.md:1147 — current status: `FAIL testSQLCommentsInPlan …: assertEquals: expected -- "executionTraceID" : "${execID}", got []`
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/builtin/Pure.java:516 — SQL_EXECUTION_NODE already declares `sqlComment: …String[0..1]` (added by 787c391b; `git show 787c391b -- core/src/main/java/com/legend/builtin/Pure.java` shows the exact line change)
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/plan/PlanNode.java:20-21 — `record PlanNode(String kind, List<PlanNode> children, @Nullable String sqlQuery, List<Param> functionParameters)` — no sqlComment component
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/StatementExecutor.java:1834-1845 — the PlanNode walkProp switch: rootExecutionNode / executionNodes / sqlQuery / functionParameters / `default -> null`
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/StatementExecutor.java:2012-2014 — planModel constructs `new PlanNode("SQLExecutionNode", List.of(), es.sql(), List.of())`, the only place the SQL node is built
- /Users/neemsandv/legend/legend-engine/.../core_relational/relational/postprocessor/defaultPostProcessor/defaultPostProcessor.pure:71 — `^Result<SelectSQLQuery|1>(values= ^$selectSQLQuery(comment= '-- "executionTraceID" : "${execID}"'))`
- /Users/neemsandv/legend/legend-engine/.../core_relational/relational/postprocessor/defaultPostProcessor/defaultPostProcessor.pure:57-65 — prependSQLComments is a member of sqlQueryDefaultPostProcessors, i.e. it runs for EVERY plan-generated select
- /Users/neemsandv/legend/legend-engine/.../core_relational/relational/relationalMappingExecution.pure:227 — `sqlComment = $query.comment` inside generateSQLExecutionNode
- /Users/neemsandv/legend/legend-engine/.../core_relational/relational/contract/storeContract.pure:153,156 — the planToString printer emits header/resultColumns/sql/connection and NOT sqlComment, so adding the field cannot perturb any plan-text golden

</details>

---

## `testSupportStreamFlagFromSimple` — ✅ NOW PASSES (fixed upstream; diagnosis retained for the record)

| | |
|---|---|
| family | `executionPlan/tests` |
| sweep status | ERROR |
| **verdict** | **NEEDS PROBE** |
| effort | M |
| confidence | low |

**Root cause**

The plan-handle walk reaches `StatementExecutor.planModel`, whose FIRST guard requires `ep.args().get(0) instanceof TypedLambda` and otherwise throws the observed message (StatementExecutor.java:1944-1948). The signature that types this call — `EXECUTION_PLAN__2_P2` (2-arg overload, 2-param lambda) — exists (Pure.java:1626, added in commit 56d5449d), which is why the old failure 'no overload of executionPlan matches the argument types' (docs/OUTSTANDING.md:231) turned into this one. I traced every typing path a lambda literal can take (Typer.java:193-238 bare literal, :1902-2010 typeLambda, :1567-1621 bindDeferredAndBuild) and every rewrite the argument passes through (UserCallInliner.rewrite :303-457, .lambda :470-503, HarnessSubstitution :59-120) — all of them preserve a TypedLambda — so I cannot statically account for the guard failing. The decisive datum I do have: the OTHER test that hits this exact message, modelJoins testPersonToFirmUsingFromProject (testModelJoinsToRelationalJoins.pure:127), uses a ZERO-parameter lambda with the same 2-arg overload. Identical failure across both lambda arities points at the 2-arg call shape, not at the lambda. Note also that planModel duplicates a NARROWER version of the argument reading planToString already does — planToString unwraps `preval(lambda, ext)` (StatementExecutor.java:531-540) and has the 2-arg 'context lives in the query's ->from' rule (:541-582); planModel has neither the preval unwrap nor any letBound() indirection (contrast buildFrame at :2077-2094, which does both).

**Fix**

Probe first, then fix in ONE place. Probe: add a temporary one-line print (or reuse the LL_TMP_DEBUG channel already present at StatementExecutor.java:321-325) at StatementExecutor.java:1944 dumping `ep.args().size()` and `ep.args().get(0).getClass().getSimpleName()`, and run only testSupportStreamFlagFromSimple and modelJoins testPersonToFirmUsingFromProject. That names the actual node in one shot. Fix, regardless of what the probe says: stop duplicating argument-reading in planModel. Extract the argument reader planToString already has (StatementExecutor.java:531-582 — preval/withFeatureFlags peel, letBound resolution of a variable-bound query, mapping = arg1 packageable ref ELSE firstFromMapping(terminal) ELSE the chain-mapping rule) into one private helper returning (lambda, mappingFqn, runtimeArgOrNull), and call it from BOTH planToString and planModel (StatementExecutor.java:1941-1968). planModel's supportsStream/FunctionParametersValidationNode construction (:1974-2025) then works unchanged for the 2-arg overload, since it only needs the lambda's parameters and the mapping FQN. Do not add a harness-side special case for the 2-arg form.

**Risk** — planToString and planModel currently diverge on the runtime argument (planModel reads args[2] for quote/timezone; with the 2-arg overload there is none) — the shared helper must return an absent runtime and let both callers default to the in-query ->from connection, exactly as planToString does at :594-602.

**Also unblocks** — modelJoins testPersonToFirmUsingFromProject (same message, same 2-arg overload).

**Falsifier** — The probe itself: if `ep.args().get(0)` prints as TypedLambda, then the throw is NOT the instanceof guard and there is a second source of this message (or a stale sweep), and the whole diagnosis must be redone from the actual stack trace.

<details><summary>Evidence read (6 citations)</summary>

- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/StatementExecutor.java:1944-1948 — the only site of the message; fires exactly when arg 0 is not a TypedLambda
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/builtin/Pure.java:1624-1626 — EXECUTION_PLAN__2 / __2_P1 / __2_P2 are all registered, with the author's own note above them that the 2-arg spellings 'TYPE here; the plan-text K-arm walls the multi-node envelope'
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/StatementExecutor.java:531-540 and :541-582 — planToString's richer argument reading (preval unwrap; 2-arg overload defers to the query's own ->from for the mapping), which planModel lacks
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/StatementExecutor.java:2077-2094 — buildFrame resolves its query argument through letBound() and peels preval/withFeatureFlags; planModel does neither
- /Users/neemsandv/legend/legend-engine/.../modelJoins/testModelJoinsToRelationalJoins.pure:127 — testPersonToFirmUsingFromProject: 2-arg executionPlan with a ZERO-param lambda, same message per docs/RELATIONAL_CORPUS_ALL.md:1333
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/harness/PlanAsserts.java:33-42 and :62-69 — this assert's LAST argument is the literal `false`, so containsPlanWalk/containsPlanToString are false and the plan-assert channel (which would downgrade the throw to a SHAPE 'plan wall:') never engages; the NotImplementedException escapes as an ERROR, matching the sweep

</details>

---

## `testSupportStreamFlagWithGraphFetchAndFrom`

| | |
|---|---|
| family | `executionPlan/tests` |
| sweep status | ERROR |
| **verdict** | **MISSING FEATURE** |
| effort | XL |
| confidence | high |

**Root cause**

legend-lite has no first-class graph-tree VALUE: a `#{Class{...}}#` tree is only ever a SYNTACTIC argument. GraphFetchChecker.checkTree requires the second parameter, after unwrapping the literal carrier / cast / quoted-tree forms, to be a `ColSpecArray` and otherwise throws `graphFetch expects (classCollection, #{Class{…}}#)` (GraphFetchChecker.java:82-87, unwrapCompiledTree at :233-252). This test passes `->graphFetch($sourceGraph)` where `$sourceGraph = meta::pure::graphFetch::calculateSourceTree($targetGraph, simpleModelMapping, relationalExtensions())` — a COMPUTED tree. Two things are therefore absent: (a) `calculateSourceTree` is not implemented anywhere in legend-lite (grep over core/src/main returns zero hits), and (b) even a stub could not be consumed, because the checker's contract is syntactic. The wall is honest and fires at the first hop.

**Fix**

Do not fix as a point patch; ledger it behind a real feature. The prerequisite is a graph-tree VALUE type in the compiler: reify TypedGraphTree/TypedGraphFetch's tree as a value a variable can hold (a new TypedSpec carrier plus an ExprType for RootGraphFetchTree<C>), teach GraphFetchChecker.checkTree to accept, in addition to a syntactic ColSpecArray, an expression that evaluates to such a carrier (constant-folded at G, since the tree must be known statically to drive lowering), and only then implement `meta::pure::graphFetch::calculateSourceTree` as tree algebra over the M2M mapping (root ~src class, per-property source-tree enrichment, subtree merge + sort — mirroring graphExtension.pure:396-410). This test additionally needs cross-store M2M chaining through getRuntimeWithModelConnection and the 2-arg executionPlan plan walk (see testSupportStreamFlagFromSimple), so it should not be used as the driving case for the tree-value work.

**How legend-engine does it** — /Users/neemsandv/legend/legend-engine/legend-engine-core/legend-engine-core-pure/legend-engine-pure-code-compiled-core/src/main/resources/core/pure/graphFetch/graphExtension.pure:396-410 — `calculateSourceTree(tree:RootGraphFetchTree<Any>[1], mapping, extensions)` replaces qualified properties with required ones, finds the root set implementation, and for a PureInstanceSetImplementation builds `^RootGraphFetchTree<Any>(class=$pisi.srcClass)` then enrichSourceTreeNode/mergeSubTrees/sortTree; the OperationSetImplementation arm merges per-branch trees via SubTypeGraphFetchTree.

**Risk** — Loosening GraphFetchChecker to accept non-syntactic trees without a constant-folding guarantee would let a tree that is not statically known reach lowering, where the serialize key and the fetch plan both assume a known tree — that would trade a loud wall for wrong output. Any acceptance must be gated on successful compile-time folding.

**Also unblocks** — functions/tests/testObjectReferenceIn.pure:379 uses the same calculateSourceTree idiom.

**Falsifier** — If some other corpus test already passes while calling graphFetch with a let-bound tree that is NOT a syntactic literal, then a tree-value path exists that I missed and the checker's contract is looser than GraphFetchChecker.java:85 suggests.

<details><summary>Evidence read (5 citations)</summary>

- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/compiler/spec/GraphFetchChecker.java:82-87 — `if (!(second instanceof ColSpecArray tree)) throw new TypeInferenceException(fn + " expects (classCollection, #{Class{…}}#)")`
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/compiler/spec/GraphFetchChecker.java:233-252 — unwrapCompiledTree accepts only GraphFetchLiteral, cast-of-ColSpecArray, and QuotedTreeCall; a function CALL is returned unchanged and then fails the instanceof
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/compiler/spec/GraphFetchChecker.java:100-178 — validate() walks the ColSpec tree against the class model directly, confirming trees are compile-time structures with no runtime value representation
- grep for `calculateSourceTree` over /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java — zero hits
- /Users/neemsandv/legend/legend-engine/.../executionPlan/tests/executionPlanTest.pure:2546-2560 — the test also needs getRuntimeWithModelConnection(_Firm, $sourceFirms) (a runtime built from another query's results) and the 2-arg executionPlan walk, i.e. it stacks on the U13 test-5 wall as well

</details>

---

## `testTemporalDateVariableInFunctionExpression`

| | |
|---|---|
| family | `executionPlan/tests` |
| sweep status | FAIL |
| **verdict** | **REAL DEFECT** |
| effort | S |
| confidence | high |
| **adversarial review** | **CONFIRMED** |

**Root cause**

legend-lite's engine-style H2 renderer emits a CHIMERA of the engine's two H2 dialects for this expression, so it matches neither of the two goldens `assertEqualsH2Compatible` supplies.

The query is `Product.all($bd)->project(t|$t.classification($bd->adjust(1, DAYS)).type, ...)`. Two independent spellings compose in the milestoning join-ON predicate:

(a) The DATE plan-template parameter. `EngineStyleH2.expr` (EngineStyleH2.java:1001-1005) deliberately renders a non-dotted DATE `PlanParam` in the LEGACY freemarker form `'${bd}'` (its own comment names this "the milestoning business-date channel"). That is the h2Extension2_1_214 spelling — golden #1's spelling. The h2-new golden (#2) spells it `TIMESTAMP'${bd}'`.

(b) The `adjust()` shift. `Scalars`' adjust rule (Scalars.java:497-502) unconditionally builds `SqlExpr.Call(SqlFn.ADD_INTERVAL, [StringLit(intervalFn(unit)), amount, date])`, and `EngineStyleH2.call`'s ADD_INTERVAL arm (EngineStyleH2.java:1427-1429) spells the unit through `dbUnitOf(...)`, which returns LOWERCASE names (EngineStyleH2.java:1356-1370: "to_days" -> "day"). That is the h2SqlDialect (new) spelling — golden #2's spelling. Golden #1 wants `dateadd(DAY, ...)`.

So legend-lite produces `... from_z <= dateadd(day, 1, '${bd}') ...` = legacy param + new unit. Golden #1 is `dateadd(DAY, 1, '${bd}')`, golden #2 is `dateadd(day, 1, TIMESTAMP'${bd}')`. Neither matches, and the plan channel compares STRICTLY: `EngineTestExecutor` line 1821-1824 short-circuits `assertEqualsH2Compatible` into `PlanAsserts.planTextAssert` whenever the operand is a plan walk (this test walks `rootExecutionNode...sqlQuery`), and `planTextAssert` (PlanAsserts.java:170-187) does a literal string compare against golden[0] then golden[1] with no row-verification fallback — unlike the `sqlTextVerify` path, which does fall back to rows.

The seam to fix this was already carved and left unwired: `SqlFn.ADD_INTERVAL_TEMPORAL` exists precisely for the uppercase spelling (SqlFn.java:62-65) and EngineStyleH2.java:1432-1435 renders it uppercase, but the ONLY producer of that opcode anywhere in main is `DateShifts.dayOfWeekShift` (DateShifts.java:58). No milestoning/plan-param path ever emits it. `TemporalFrame.java:2594-2596` even documents the target text ("engine golden: dateadd(DAY, 1, '<ctx-date>')") while letting the computation ride to SQL through the ordinary lowercase `adjust` rule.

The sibling `testTemporalDateVariableInPropertySequence` (executionPlanTest.pure:2093, goldens at :2100-2101) is byte-identical to this test except for the `adjust` wrapper, and it is in NO unit's brief — i.e. it passes the strict plan-text compare against golden #1. That pins `'${bd}'`, the milestoning join filters and the aliasing as already correct, and isolates the delta to the single unit token.

**Fix**

Scope the unit-token case to the LEGACY plan-template channel inside the renderer that owns SQL text.

In `core/src/main/java/com/legend/sql/dialect/EngineStyleH2.java`, merge the two arms at :1427-1435 into one that picks the case from the operand's dialect, not from the opcode:

```java
case ADD_INTERVAL, ADD_INTERVAL_TEMPORAL -> {
    String u = dbUnitOf(((SqlExpr.StringLit) a.get(0)).value());
    // The legacy freemarker channel (h2Extension2_1_214) spells the
    // unit UPPERCASE (extensionDefaults mapToDBUnitType); the h2-new
    // channel (h2SqlDialect) spells it lowercase. A ${...} plan
    // parameter only exists in the legacy plan-template channel and
    // already renders in its bare-quoted form, so the unit must follow
    // it or the text is a chimera of two engine dialects.
    if (c.fn() == com.legend.sql.SqlFn.ADD_INTERVAL_TEMPORAL
            || containsPlanParam(a.get(2))) {
        u = u.toUpperCase(java.util.Locale.ROOT);
    }
    yield "dateadd(" + u + ", " + expr(a.get(1), 0) + ", "
            + expr(a.get(2), 0) + ")";
}
```

Add the private helper next to `dbUnitOf`:

```java
/** Does this subtree contain a plan-template ${...} parameter? Such a
 *  node lives only in the legacy freemarker plan channel. */
private static boolean containsPlanParam(SqlExpr e) {
    if (e instanceof SqlExpr.PlanParam) { return true; }
    if (e instanceof SqlExpr.Call c2) {
        for (SqlExpr x : c2.args()) { if (containsPlanParam(x)) { return true; } }
    }
    if (e instanceof SqlExpr.Cast cs) { return containsPlanParam(cs.expr()); }
    if (e instanceof SqlExpr.Group g) { return containsPlanParam(g.inner()); }
    return false;
}
```
(walk whatever other composite SqlExpr cases can wrap a date operand; a `Call`/`Cast`/`Group` walk covers the corpus shapes.)

Do NOT flip `dbUnitOf` globally to uppercase and do NOT route the milestoning `adjust` to `ADD_INTERVAL_TEMPORAL` in `Scalars`/`TemporalFrame` — see risk.

Nothing in `Scalars` changes; nothing in the harness changes.

**How legend-engine does it** — /Users/neemsandv/legend/legend-engine/legend-engine-xts-relationalStore/legend-engine-xt-relationalStore-generation/legend-engine-xt-relationalStore-pure/legend-engine-xt-relationalStore-core-pure/src/main/resources/core_relational/relational/sqlQueryToString/extensionDefaults.pure:647-660 (mapToDBUnitType -> 'DAY', the legacy/plan dialect) vs /Users/neemsandv/legend/legend-engine/legend-engine-xts-relationalStore/legend-engine-xt-relationalStore-generation/legend-engine-xt-relationalStore-pure/legend-engine-xt-relationalStore-sqlDialectTranslation-pure/src/main/resources/core_external_store_relational_sql_dialect_translation/defaults/sqlDialectExtensionDefaults.pure:362-375 (mapToDBUnitType -> 'day', the h2-new dialect); consumed at h2Extension2_1_214.pure:201 and h2SqlDialect.pure:555 respectively.

**⚠ Correction from adversarial review** — Two non-structural corrections. (1) The helper will not compile as written: SqlExpr.Cast's component is `value()`, not `expr()` (SqlExpr.java:468 `record Cast(SqlExpr value, SqlType target)`); Group.inner() and Call.args() are correct. The Cast/Group walk is defensive only — in the failing shape a.get(2) IS the PlanParam directly (Scalars.dateArg at Scalars.java:2992 passes the lowered expr straight through for a non-literal date). (2) The comment's justification is factually wrong about the engine and should not be pasted in as-is: the legacy h2Extension2_1_214 dialect uppercases the unit ALWAYS via extensionDefaults.mapToDBUnitType, not only for plan parameters — testBusinessDateMilestoning.pure:223 and :1343 are legacy goldens with `dateadd(DAY, -1, '2015-10-16')` on a plain date literal. containsPlanParam is therefore a corpus-safe PROXY for 'this is the legacy plan-template channel', not the engine's rule; say so, and also correct the now-false dbUnitOf javadoc at EngineStyleH2.java:1349-1355 ('the uppercase dateadd(DAY,...) corpus spellings are firstDayOf* FORMAT literals, never adjust units') or the next reader will revert this.

<details><summary>Adversarial review notes (CONFIRMED)</summary>

Mechanism holds. Every citation resolves and says what is claimed, and the corpus goldens are exactly as quoted: executionPlanTest.pure:2113 legacy golden is `dateadd(DAY, 1, '${bd}')` and :2114 h2-new golden is `dateadd(day, 1, TIMESTAMP'${bd}')`, so the two goldens differ ONLY in (unit case + TIMESTAMP keyword). legend-lite renders the bare-quoted legacy param (EngineStyleH2:1003) and the lowercase unit (EngineStyleH2:1427 -> dbUnitOf:1356), i.e. a chimera. Independent corroboration I obtained: (a) the sibling testTemporalDateVariableInPropertySequence is absent from rows.json (the failing-test list), so the join/where/alias/param spellings are already byte-right and the adjust wrapper is the only delta; (b) the ONLY difference between the two failing tests in U14 (#1 and #2) is the same wrapper. The strict-compare route is real: EngineTestExecutor:1821 short-circuits into PlanAsserts.planTextAssert BEFORE the 3-arg sqlTextVerify branch, and planTextAssert:170-187 literal-compares golden[0] then golden[1] with no row fallback, emitting exactly the observed 'assertEquals: expected ...' prefix. ADD_INTERVAL_TEMPORAL's only construction site in main is DateShifts.java:58 (repo grep: SqlFn:65 decl, DateShifts:58 producer, EngineStyleH2:1432 / H2:78 / AnsiSqlRenderer:524 consumers). What I could NOT check: the brief truncates the failure detail before the 'got' side, so I never saw legend-lite's actual text; the mechanism is inferred (very strongly) rather than observed. Blast radius of the fix is small: across the whole engine corpus only 4 golden lines contain both `dateadd` and `${` and all 4 are the two already-failing tests (executionPlanTest.pure:2113/2114/2126/2127); the only other corpus file with `${`+dateadd is the javaPlatformBinding executionPlanTest, outside this corpus root. No legend-lite test resource mentions dateadd.

</details>

**Risk** — A GLOBAL uppercase flip is the trap. legend-lite renders date LITERALS in the h2-new form (`EngineStyleH2.dateLit` at :629-633 returns `DATE'...'`), so with a literal date it already matches golden #2 byte-for-byte (`dateadd(day, 1, DATE'2015-10-16')` — testBusinessDateMilestoning.pure:212, functions/tests/projection/testDateFilters.pure:43, transform/fromPure/tests/testToSQLString.pure:503). Uppercasing everywhere would break every one of those, plus the single-golden lowercase toSQLString goldens (tds/tests/testGroupBy.pure:539/567/599, functions/tests/testModelGroupBy.pure:1291, tests/mapping/sqlFunction/testSqlFunctionsInMapping.pure:643, milestoning/tests/testBusinessDateMilestoning.pure:821, tds/tests/testTDSProject.pure:331/357, pureToSQLQuery/tests/testPureToSql.pure:268). Those go through `sqlTextVerify` (EngineTestExecutor.java:986-1013), which falls back to row verification on a text miss, so most would still PASS — but they would silently lose their M1_VERIFIED byte-parity credit, which is a real regression hidden behind a green bar.

Routing the milestoning `adjust` to ADD_INTERVAL_TEMPORAL in the resolver/lowerer is also wrong: it would uppercase the LITERAL-date milestoning goldens too (testBusinessDateMilestoning.pure:211-212), turning a current golden-#2 byte match into a match of neither golden.

Tenet-2 trap: do not "fix" this by relaxing `EngineTestExecutor.compare` or by making `planTextAssert` case-insensitive. The plan text is the product surface; a case-insensitive plan compare would hide every future dialect-token divergence.

**Also unblocks** — testTemporalDateVariableInFunctionExpressionWithPropagation (the other test in this unit). No other brief in U01-U62 contains a `${...}`-parameterised plan golden with an adjust wrapper.

**Falsifier** — Run this one test with `LL_TMP_DEBUG=1`; `PlanAsserts.planGoldenDebug` prints `[plan-golden] ... firstDiff@N E<...> G<...>`. If the first divergence is NOT the `d` of `dateadd(day` vs `dateadd(DAY` (i.e. the two strings differ earlier, e.g. in an alias, a join order, or the where-clause date form), this diagnosis is wrong and the unit case is at most a second-order delta.

<details><summary>Evidence read (14 citations)</summary>

- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/lowering/Scalars.java:497-502 — the `adjust` lowering rule: `new SqlExpr.Call(SqlFn.ADD_INTERVAL, List.of(new SqlExpr.StringLit(DateShifts.intervalFn(enumName(n.args().get(2)))), args.get(1), dateArg(...)))`; unconditional, no temporal/plan-param variant.
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/sql/dialect/EngineStyleH2.java:1427-1429 — `case ADD_INTERVAL -> "dateadd(" + dbUnitOf(((SqlExpr.StringLit) a.get(0)).value()) + ", " + expr(a.get(1),0) + ", " + expr(a.get(2),0) + ")"` (no case change).
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/sql/dialect/EngineStyleH2.java:1356-1370 — `dbUnitOf`: `case "to_days" -> "day";` i.e. lowercase unit tokens.
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/sql/dialect/EngineStyleH2.java:1432-1435 — `case ADD_INTERVAL_TEMPORAL -> "dateadd(" + dbUnitOf(...).toUpperCase(Locale.ROOT) + ...` — the uppercase spelling exists and is unreachable from the milestoning channel.
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/lowering/DateShifts.java:58 — `return new SqlExpr.Call(SqlFn.ADD_INTERVAL_TEMPORAL, ...)` inside `dayOfWeekShift`; a repo-wide grep for ADD_INTERVAL_TEMPORAL shows this is the only construction site in main.
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/sql/dialect/EngineStyleH2.java:1001-1005 — `case DATE -> p.name().indexOf('.') >= 0 ? "TIMESTAMP'${"+p.name()+"}'" : "'${"+p.name()+"}'"`, with the comment naming this "a plain function param ('${bd}' — the milestoning business-date channel) spells BARE-QUOTED".
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/harness/EngineTestExecutor.java:1821-1824 — `if (PlanAsserts.wantsPlanText(args, lets)) { return PlanAsserts.planTextAssert(...); }` placed BEFORE the 3-arg `assertEqualsH2Compatible` -> `sqlTextVerify` branch at :1828-1834, so plan-walk operands never get the row-verification fallback.
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/harness/PlanAsserts.java:170-187 — literal compare of golden[0], then golden[1] when args.size()==3, then `return "assertEquals: expected " + pe.render() + ", got " + pa.render();` — exactly the observed failure prefix.
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/StatementExecutor.java:2001-2011 — `planModel` calls `engineSql(List.of(term), pmFqn, specs, env, new EngineStyleH2(quote, tz), params, mapperRenames)`, confirming the plan-walk SQL for this test is rendered by EngineStyleH2 with the `${bd}` PlanParam bound.
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/resolver/TemporalFrame.java:2594-2596 — comment: "COMPUTED date ($this.businessDate->adjust(1, DAYS)): the context read normalizes INSIDE the computation and the computation itself rides to SQL (engine golden: dateadd(DAY, 1, '<ctx-date>'))" — the uppercase target is documented but never produced.
- /Users/neemsandv/legend/legend-engine/.../core_relational/relational/sqlQueryToString/dbSpecific/h2/h2Extension2_1_214.pure:201 — `dynaFnToSql('adjust', $allStates, ^ToSql(format='dateadd(%s)', transform={p:String[3] | $p->at(2)->mapToDBUnitType() + ', ' + $p->at(1) + ', ' + $p->at(0)}))` (legacy H2, the plan-generation dialect).
- /Users/neemsandv/legend/legend-engine/.../core_relational/relational/sqlQueryToString/extensionDefaults.pure:647-660 — `mapToDBUnitType` maps `DurationUnit.DAYS->toString()` to the UPPERCASE literal `'DAY'`.
- /Users/neemsandv/legend/legend-engine/.../core_external_store_relational_sql_dialect_translation/defaults/sqlDialectExtensionDefaults.pure:362-375 — the NEW dialect's `mapToDBUnitType` maps `TemporalUnit.DAY` to the LOWERCASE `'day'`; used by h2SqlDialect.pure:555's `generateFunctionCallWithArgs($sqlDialect, 'dateadd', [...mapToDBUnitType($sqlDialect), ...])`.
- /Users/neemsandv/legend/legend-engine/.../core_relational/relational/executionPlan/tests/executionPlanTest.pure:2093-2103 — `testTemporalDateVariableInPropertySequence`, the same query minus `->adjust(1, DAYS)`; absent from all 62 unit briefs, therefore passing the strict plan-text compare against golden #1.

</details>

---

## `testTemporalDateVariableInFunctionExpressionWithPropagation`

| | |
|---|---|
| family | `executionPlan/tests` |
| sweep status | FAIL |
| **verdict** | **REAL DEFECT** |
| effort | XS |
| confidence | medium |

**Root cause**

Identical mechanism to `testTemporalDateVariableInFunctionExpression` — see that entry. This test extends the query with `.exchange.name`, which propagates the adjusted business date into a subselect frame, so the shifted date appears FOUR times in golden #1: twice in the ProductClassificationTable join-ON and twice in the `(select "productexchangetable_1".name ... where from_z <= dateadd(DAY, 1, '${bd}') and thru_z > dateadd(DAY, 1, '${bd}'))` propagation subselect. Every one of them is emitted by the same `Scalars` adjust rule (Scalars.java:497-502) through the same lowercase `EngineStyleH2` ADD_INTERVAL arm (EngineStyleH2.java:1427-1429), against the same legacy bare-quoted `'${bd}'` PlanParam (EngineStyleH2.java:1001-1005).

The propagation FRAME shape itself is separately corroborated: `meta::relational::tests::milestoning::businessdate::testMilestonedThisBusinessDateUsedAsParameterToFunctionParametersOfMilestonedQualifiedProperty` (testBusinessDateMilestoning.pure:205-215) is the same query with a literal `%2015-10-16` in place of `$bd`, and it appears in no unit brief. Caveat: that test reaches `sqlTextVerify` (EngineTestExecutor.java:986-1013), which falls back to ROW verification on a text miss, so its passing proves the join/subselect SEMANTICS are right but does not prove its text is byte-exact. That is why this entry carries lower confidence than its sibling.

**Fix**

No separate change. The single `EngineStyleH2.call` ADD_INTERVAL/ADD_INTERVAL_TEMPORAL merge described for `testTemporalDateVariableInFunctionExpression` fixes all four occurrences in this golden, since every one of them wraps the same `${bd}` PlanParam.

If, after that change, this test still fails while its sibling passes, the residual is a FRAME-shape delta in the propagation subselect (column list, alias numbering `productexchangetable_0`/`_1`, or the placement of the milestoning predicate inside the subselect WHERE rather than the outer ON) — diagnose it from the `[plan-golden] firstDiff@` offset, not by further dialect tweaking.

**How legend-engine does it** — /Users/neemsandv/legend/legend-engine/legend-engine-xts-relationalStore/legend-engine-xt-relationalStore-generation/legend-engine-xt-relationalStore-pure/legend-engine-xt-relationalStore-core-pure/src/main/resources/core_relational/relational/sqlQueryToString/dbSpecific/h2/h2Extension2_1_214.pure:201 — the legacy H2 `adjust` dynaFn whose `mapToDBUnitType` yields the uppercase unit golden #1 pins.

**Risk** — Same as the sibling: do not flip `dbUnitOf` globally and do not route the milestoning adjust to ADD_INTERVAL_TEMPORAL in Scalars/TemporalFrame (that would uppercase the literal-date milestoning goldens and break their golden-#2 byte match). Additional risk specific to this test: because the propagation frame text has never been strictly verified, the fix may reveal a second, independent text delta. That would be a NEW finding, not a reason to widen the dialect change.

**Also unblocks** — testTemporalDateVariableInFunctionExpression (the sibling in this unit).

**Falsifier** — Run with `LL_TMP_DEBUG=1` and read the `[plan-golden] firstDiff@N` offset. If the first divergence lands anywhere other than the unit token of the first `dateadd(` — in particular if it lands in the `(select "productexchangetable_1"...)` subselect's column list or alias numbering — then the propagation frame is also wrong and the unit case is not the whole story.

<details><summary>Evidence read (7 citations)</summary>

- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/lowering/Scalars.java:497-502 — the single, unconditional `adjust` -> `SqlFn.ADD_INTERVAL` rule that every occurrence of the shifted date in this query goes through.
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/sql/dialect/EngineStyleH2.java:1427-1429 and :1356-1370 — ADD_INTERVAL renders `dateadd(<lowercase>, n, d)`.
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/sql/dialect/EngineStyleH2.java:1001-1005 — the non-dotted DATE PlanParam renders `'${bd}'` (legacy), fixing the whole string to golden #1's dialect.
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/harness/PlanAsserts.java:170-187 — strict two-golden literal compare, no row fallback, producing the observed `assertEquals: expected <golden#1>` message.
- /Users/neemsandv/legend/legend-engine/.../core_relational/relational/executionPlan/tests/executionPlanTest.pure:2126-2127 — golden #1 spells `dateadd(DAY, 1, '${bd}')` in all four positions; golden #2 spells `dateadd(day, 1, TIMESTAMP'${bd}')`.
- /Users/neemsandv/legend/legend-engine/.../core_relational/relational/milestoning/tests/testBusinessDateMilestoning.pure:205-215 — the literal-date twin of this exact propagation shape (same ProductExchangeTable subselect), absent from every unit brief.
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/harness/EngineTestExecutor.java:986-1013 — `sqlTextVerify` falls back to `h2Upgrade` row verification when the golden text does not match, which is why the literal-date twin's pass does not by itself certify its text.

</details>

---

## `testTwoMappingsOneRuntime`

| | |
|---|---|
| family | `executionPlan/tests` |
| sweep status | FAIL |
| **verdict** | **GOLDEN TEXT ONLY** |
| effort | M |
| confidence | high |

**Root cause**

The rows are identical to the engine's; only the SQL shaping differs, and it differs for one reason. `JoinChecker.sharedKeyLegacyJoin` desugars the legacy shared-key TDS join `join(tds2, JoinType.INNER, ['legalName'])` into three typed nodes: `rename(right, legalName -> __jk_legalName)`, then the modern `join` with condition `$a.legalName == $b.__jk_legalName`, then a `TypedSelect` dropping the synthetic column. That desugar is semantically correct but produces the wrong TEXT twice over. (i) `Lowerer.rename` materialises the rename as its own derived table, so the got SQL carries an extra wrapper `select "persontable_3"."legalName" as "__jk_legalName", … from (RIGHT) as "persontable_3"` and the synthetic name leaks into the ON clause. (ii) The trailing `TypedSelect` fuses into the join's own SELECT, giving the top-level select non-empty projections — which disqualifies `EngineStyleH2.wrapTdsJoinTop`, whose guard requires `s.projections().isEmpty()`. So the engine's outer isolation wrapper `select "persontable_0".<3 cols> from (<join>) as "persontable_0"` is never emitted. The engine does neither: `processTdsJoinOnColumns` joins the two sides UNRENAMED with `left.legalName = right.legalName` and simply omits the right's duplicate-named columns from `newColumns` (`$aliases2->filter(a|!$a.name->in($aliases1.name))`), then always wraps the join in a `TdsSelectSqlQuery` under a fresh `tdsJoined_<nodeId>` alias and projects from it. The alias numbering corroborates the mechanism exactly: legend-lite's own `EngineStyleH2` alias planner numbers pre-order per lowercased table group with each leftmost 'root' still consuming an index, so the got is left-frame=0, left-root=1, rename-wrapper=2, right-frame=3; removing the rename wrapper and adding the outer wrapper renumbers to wrapper=0, left=1, left-root=2, right=3 — precisely the golden's persontable_0/_1/_3.

**Fix**

Make the legacy shared-key join a first-class node instead of a rename desugar. (1) In `core/src/main/java/com/legend/compiler/spec/JoinChecker.java`, replace the rename/select desugar in `sharedKeyLegacyJoin` (:250-292) with a directly-constructed `TypedJoin` whose condition is `$a.k == $b.k` over the ORIGINAL names and whose `info` is the deduped schema (`left.columns ++ right.columns minus sharedKeys`, or the mirror for RIGHT_OUTER — the same `kept` list the current code computes at :286-288). Carry the shared keys on the node: add a `List<String> sharedKeys` component to `core/src/main/java/com/legend/compiler/spec/typed/TypedJoin.java` (empty for the modern overload) rather than reusing `frameName`, and update the two rebuild sites (`withChildren`, and `Lowerer`'s synthetic construction at Lowerer.java:1692). Do NOT route it through `check(t, modern, env)` — the T+V algebra rightly rejects the name collision; the whole point is that the legacy overload resolves it by projection. (2) In `Lowerer.join` (Lowerer.java:1708-1761), when `sharedKeys` is non-empty, build `SqlSource.Join` from the two sides as-is, resolve the ON against each side's alias with the ORIGINAL column name, then emit an explicit projection list (left's columns from the left alias, right's columns minus the shared keys from the right alias) instead of the star form, and wrap the whole thing in `new SqlSource.Subselect(join, nextAlias(), "tdsJoined")` projecting the output columns — this is the engine's `tdsJoined_<nodeId>` isolation and it makes step (3) unnecessary. (3) If you instead keep the wrapper in the text channel, relax `EngineStyleH2.wrapTdsJoinTop` (:240-247) to fire when the top select's projections are plain column refs exactly covering `s.outputs()` — but gate it on the new `"tdsJoined"` frameName so modern-overload joins that currently pass through the bare-star path are untouched.

**How legend-engine does it** — legend-engine .../core_relational/relational/pureToSQLQuery/pureToSQLQuery_deprecated.pure:540-618 (`processTdsJoinOnColumns` — unrenamed ON, duplicate right columns dropped from `newColumns`, join wrapped under a `tdsJoined_<nodeId>` alias)

**Risk** — `sharedKeyLegacyJoin` also serves the 5-argument explicit-pair spelling with identical column lists and the duplicate-key case (`['tradeDate','tradeDate']`), and the RIGHT_OUTER 'which side's keys survive' rule at JoinChecker.java:242-244 — all of that must be preserved by the new node, and there are tds/tests goldens for RIGHT_OUTER key provenance that will catch a regression. Relaxing `wrapTdsJoinTop` unconditionally would add a wrapper to every currently-passing modern TDS-join golden — hence the frameName gate. Tenet-2 trap: do NOT normalise `__jk_` out of the text in `PlanAsserts`/`TdsEquivalence`; the shape is owned by the checker and the lowerer.

**Also unblocks** — testTwoMappingsOneRuntimeWithoutExternalMapping (byte-identical got and golden). docs/CORPUS_BURNDOWN_INDEX.md names exactly these two for this cause; any other corpus test using the legacy `join(tds, JoinType, [cols])` overload with a plan-text or golden-SQL assert would also move.

**Falsifier** — After the change, `LL_TMP_DEBUG=1` on this test must show `planGoldenDebug("plan-golden", …)` reporting equal lengths and no first-diff. Cheaper pre-check without touching code: grep the got SQL for `__jk_` — it is there today and must be absent after; and confirm the outer alias becomes `persontable_0` with the left at `_1` and the right at `_3`. If after removing the rename the aliases come out 0/1/2 instead of 0/1/3, my reading of the leftmost-'root'-consumes-an-index rule (EngineStyleH2.java:283-287) is wrong and the numbering needs separate work.

<details><summary>Evidence read (7 citations)</summary>

- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/compiler/spec/JoinChecker.java:215-292 — `sharedKeyLegacyJoin`: `jkPrefix = "__jk_"` (:251), `right = new AppliedFunction("rename", List.of(right, new ColSpec(k), new ColSpec(s)))` (:271-273), condition `equal($a.k, $b.s)` (:274-275), then `new TypedSelect(joined, kept…)` dropping the synthetics (:288-291)
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/lowering/Lowerer.java:1537-1577 — `rename(TypedRename)` isolates the source into its own select and emits a full explicit projection, which is the extra `as "persontable_3"` derived table in the got SQL
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/sql/dialect/EngineStyleH2.java:232-279 — `wrapTdsJoinTop`, documented as 'the engine ISOLATES a user TDS join's result (isolateTdsSelect)', is gated on `s.projections().isEmpty() && … s.from() instanceof SqlSource.Join`; the fused drop-synthetic select makes `projections()` non-empty so the wrapper is skipped
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/sql/dialect/EngineStyleH2.java:281-347 — the alias plan: groups keyed by lowercased bare table name, numbered pre-order, leftmost source renders 'root' but still consumes its index; this is what makes the predicted renumbering to 0/1/3 exact
- legend-engine .../core_relational/relational/pureToSQLQuery/pureToSQLQuery_deprecated.pure:540-618 — `processTdsJoinOnColumns`: `joinColAliasPairs` built from both sides' ORIGINAL aliases with no rename, `newColumns = $aliases1->concatenate($aliases2->filter(a|!$a.name->in($aliases1.name)))`, `newAlias = ^TableAlias(name='tdsJoined_'+$nodeId, relationalElement=^TdsSelectSqlQuery(data=$root, columns=$newColumns))`, and the returned select projects from `$newAlias`
- legend-engine .../core_relational/relational/tds/tds.pure:29-45 — the in-memory contract the desugar must preserve: `requiredRightCols = rightTds.columns->filter(c|!$c.name->in($commonJoinCols))` for non-RIGHT_OUTER, i.e. drop the right's shared keys, keep the left's — which legend-lite's `rightKeeps = kind.value().equals("RIGHT_OUTER")` already mirrors
- legend-engine .../core_relational/relational/executionPlan/tests/executionPlanTest.pure:1959-1974 — the test body: `executionPlan({| … ->join(…, JoinType.INNER, ['legalName'])}, ^Mapping(name=''), ^Runtime(), ext)` and a single `assertEquals(golden, $plan->planToString(ext))`; there is NO row assert, so text is the whole contract

</details>

---

## `testTwoMappingsOneRuntimeWithoutExternalMapping`

| | |
|---|---|
| family | `executionPlan/tests` |
| sweep status | FAIL |
| **verdict** | **GOLDEN TEXT ONLY** |
| effort | XS |
| confidence | high |

**Root cause**

Same defect as testTwoMappingsOneRuntime, and the sweep's got string is byte-identical between the two tests — the only difference in the test source is the `executionPlan` arity (2-arg overload here, dummy `^Mapping(name='')` + `^Runtime()` there), which `StatementExecutor.planToString` resolves identically via `firstFromMapping` on the terminal's `->from(alternateSimpleMapping, …)`. The shared cause: `JoinChecker.sharedKeyLegacyJoin` desugars the legacy shared-key TDS join through a `__jk_` rename plus a drop-synthetic `TypedSelect`; the rename becomes an extra derived table on the right with the synthetic name in the ON clause, and the fused drop-select gives the top select non-empty projections, which disqualifies `EngineStyleH2.wrapTdsJoinTop` (guard: `s.projections().isEmpty()`) so the engine's outer isolation wrapper is never emitted. Rows are identical; nothing but plan text is asserted.

**Fix**

Identical to testTwoMappingsOneRuntime — one change fixes both. Replace the rename desugar in `JoinChecker.sharedKeyLegacyJoin` with a `TypedJoin` carrying `sharedKeys`, lower it in `Lowerer.join` to a single join over unrenamed sides with an explicit left-columns ++ right-minus-shared-keys projection, and isolate the result under a `"tdsJoined"`-framed `SqlSource.Subselect` (or, if the wrapper stays text-channel, gate a relaxed `EngineStyleH2.wrapTdsJoinTop` on that frameName). No separate work is needed for the 2-arg overload — mapping resolution is already correct here, as the matching got text proves.

**How legend-engine does it** — legend-engine .../core_relational/relational/pureToSQLQuery/pureToSQLQuery_deprecated.pure:540-618 (`processTdsJoinOnColumns`)

**Risk** — None beyond the sibling's — this test adds no new surface. Tenet-2 trap: the same one; do not normalise `__jk_` in the assert channel.

**Also unblocks** — testTwoMappingsOneRuntime

**Falsifier** — After fixing testTwoMappingsOneRuntime, this test must go green in the same run with no additional change. If it does not, the two-mapping resolution in the 2-arg overload differs after all and `StatementExecutor.firstFromMapping` needs its own look.

<details><summary>Evidence read (5 citations)</summary>

- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/compiler/spec/JoinChecker.java:250-292 — the `__jk_` prefix, the `rename` of the right side's key, and the trailing `TypedSelect` that drops the synthetics
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/sql/dialect/EngineStyleH2.java:240-247 — `wrapTdsJoinTop`'s `s.projections().isEmpty()` guard, which the fused drop-select violates
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/StatementExecutor.java:543-582 — the 2-arg `executionPlan` overload path: when arg1 is not a `TypedPackageableRef`, `mappingFqn = firstFromMapping(<terminal>)`, i.e. the query's own `->from` names the plan — the same resolution the dummy-`^Mapping` sibling takes, which is why both tests produce identical text
- legend-engine .../core_relational/relational/executionPlan/tests/executionPlanTest.pure:1976-1988 — the test body: the 2-arg `executionPlan({| … ->from(alternateSimpleMapping, testRuntime()) ->join(… ->from(alternateSimpleMapping2, testRuntime()), JoinType.INNER, ['legalName'])}, ext)` with a single plan-text `assertEquals`
- legend-engine .../core_relational/relational/pureToSQLQuery/pureToSQLQuery_deprecated.pure:588-618 — `newColumns = $aliases1->concatenate($aliases2->filter(a|!$a.name->in($aliases1.name)))` and the `tdsJoined_<nodeId>` alias wrapper: the engine drops the duplicate key column rather than renaming it, and always isolates the join

</details>

---

## `testViewToTDS`

| | |
|---|---|
| family | `executionPlan/tests` |
| sweep status | SHAPE |
| **verdict** | **MISSING FEATURE** |
| effort | L |
| confidence | high |

**Root cause**

legend-lite has no `viewReference` / `viewToTDS` native surface, so the call falls through to the CORPUS's own Pure definition of `meta::pure::tds::viewToTDS`, whose body constructs `^meta::relational::mapping::TableTDS(store = $view.schema.database, table = $view, columns = ...)`. `store` is declared not on `TableTDS` but on its supertype `meta::pure::mapping::TabularDataSetImplementation`, which lives in legend-engine's core platform model (`core/pure/mapping/mappingExtension.pure:52-55`) and is NOT in legend-lite's native class catalog — a repo-wide grep for `TabularDataSetImplementation` over `core/src/main` returns nothing. With the supertype invisible, `NewChecker.check` cannot find the property and throws the observed `class 'meta::relational::mapping::TableTDS' has no property 'store'` (NewChecker.java:83-94), which `PlanAsserts.planTextAssert` catches as a plan wall (PlanAsserts.java:188-198).

The TABLE half of this surface is fully built and this is exactly what the VIEW half is missing:
- `CoreFn.TABLE_REFERENCE("tableReference")` / `CoreFn.TABLE_TO_TDS("tableToTDS")` (CoreFn.java:38-41) — no VIEW_REFERENCE / VIEW_TO_TDS.
- `Pure.TABLE_REFERENCE__STRING_1__STRING_1__STRING_1` and `Pure.TABLE_TO_TDS__RELATION_1` (Pure.java:2016-2023) — no viewReference / viewToTDS native at all.
- `Typer` line 1179 dispatches `case TABLE_TO_TDS -> TableReferenceChecker.checkTableToTds(...)`; `TableReferenceChecker.check` (TableReferenceChecker.java:33-79) resolves the physical name via `t.model().findTable(db, name)`, and `ModelContext.findTable`'s own contract (ModelContext.java:150-157) says it "searches the database's top-level tables, then its schemas' tables" — views are not in scope.
- `ScanRelations.collectTableToTds` (ScanRelations.java:506-527) matches only `tableToTDS` over a `tableReference`, and resolves columns via `ctx.findTableDefinition`.

So the wall is honest in outcome but misleading in message: it is a leaked type error from compiling a platform-owned corpus body, not a named "viewToTDS is not modelled" wall.

The underlying VIEW machinery already exists — `DatabaseDefinition.ViewDefinition`, `ScanRelations.findView` (ScanRelations.java:1141-1156), `viewDef` (:662), and the view-inlining column walk (:696-710, :1007-1035). Only the query-source entry point is missing.

**Fix**

Build the VIEW half of the table-TDS surface, mirroring the table half exactly. Five edits:

1. `core/src/main/java/com/legend/builtin/Pure.java` (beside :2016-2023) — add
   `VIEW_REFERENCE__STRING_1__STRING_1__STRING_1 = signature("native function meta::relational::functions::database::viewReference(db:...String[1], schema:...String[1], name:...String[1]):...Relation<Any>[1];")` and
   `VIEW_TO_TDS__RELATION_1 = signature("native function meta::pure::tds::viewToTDS(view:...Relation<Any>[1]):...Relation<Any>[1];")`,
   with the same "REAL engine form" comment style (engine: `viewReference_Database_1__String_1__String_1__View_1_`, `viewToTDS_View_1__TableTDS_1_`).

2. `core/src/main/java/com/legend/compiler/spec/CoreFn.java` — add `VIEW_REFERENCE("viewReference")` and `VIEW_TO_TDS("viewToTDS")`.

3. `core/src/main/java/com/legend/compiler/spec/Typer.java:1179` — add `case VIEW_TO_TDS -> TableReferenceChecker.checkTableToTds(this, af, env);` and route `VIEW_REFERENCE` to `TableReferenceChecker.check`.

4. `core/src/main/java/com/legend/compiler/spec/TableReferenceChecker.java` — parameterise `check` on kind. For the VIEW kind resolve the relation type from `ScanRelations.viewDef(...)`'s columns (or add `ModelContext.findView(dbFqn, name):Optional<Type.RelationType>` beside `findTable` at ModelContext.java:157 and implement it over `DatabaseDefinition.ViewDefinition`), validate against `CoreFn.VIEW_REFERENCE`, and return a `TypedTableReference` carrying a `view=true` flag (or a new `TypedViewReference`). Keep `checkTableToTds`'s "must be a reference, not a derived relation" guard for the view arm.

5. `core/src/main/java/com/legend/lineage/ScanRelations.java:506-527` — accept `"viewToTDS".equals(simple)` over `endsWith("viewReference")`, resolving columns via the existing `findView`/`viewDef` path instead of `findTableDefinition`.

Lowering then needs the view reference to expand to its inlined SELECT so the plan text reads `from (select distinct "root".FIRSTNAME as firstName, "addresstable_0".NAME as address from personTable as "root" left outer join addressTable ...) as "root"`. Verify whether the existing mapped-view inlining (ScanRelations.java:696-710 / :1007-1035) is reachable from a bare relation source; if it is not, that is the remaining sub-task and the honest interim is a NAMED wall in `TableReferenceChecker` ("viewToTDS source: view inlining pending") rather than the current leaked NewChecker message.

If the view-inlining reach turns out to be deep, LEDGER this test — it is a single corpus test for a rarely used store-contract entry point, and the current outcome is already SHAPE (unsupported), not a wrong answer.

**How legend-engine does it** — /Users/neemsandv/legend/legend-engine/legend-engine-xts-relationalStore/legend-engine-xt-relationalStore-generation/legend-engine-xt-relationalStore-pure/legend-engine-xt-relationalStore-core-pure/src/main/resources/core_relational/relational/contract/storeContract.pure:64-71 and :85 — `shouldStopRouting` lists `tableToTDS_Table_1__TableTDS_1_` and `viewToTDS_View_1__TableTDS_1_` side by side, and the TDS routing assert accepts `tableReference_Database_1__String_1__String_1__Table_1_` or `viewReference_Database_1__String_1__String_1__View_1_` — the engine treats the two as one symmetric surface, which is exactly the symmetry legend-lite is missing.

**Risk** — Registering `viewToTDS`/`viewReference` as natives SHADOWS the corpus's own Pure definitions of the same FQNs (tableToTDS.pure:32). That is the established pattern here (tableToTDS is already shadowed the same way, Pure.java:2018-2021), but confirm the shadow is total — a partial shadow would leave some call sites still compiling the `^TableTDS(store=...)` body and hitting the same NewChecker error with a now-confusing message.

Second risk: `viewReference` with schema `'default'` must take the same strict top-level-only path `TableReferenceChecker.check` applies at :48-62 (`name = "default." + name; strictDefault = true`), or a `default`-schema view will silently resolve against a view in some other schema — the exact audit-22b failure the table path already guards.

Tenet-2 trap: do not make `PlanAsserts` special-case `viewToTDS` or pre-substitute the expected SQL. The view source is a platform surface.

**Falsifier** — Grep `core/src/main/java` for `viewToTDS` and `viewReference`. If either turns up (e.g. registered under a different spelling or aliased onto the table natives), then the surface is present and the `store` error has a different cause — most likely that `TabularDataSetImplementation` needs adding to `Pure.allNativeClasses()` instead.

<details><summary>Evidence read (11 citations)</summary>

- /Users/neemsandv/legend/legend-engine/legend-engine-xts-relationalStore/legend-engine-xt-relationalStore-generation/legend-engine-xt-relationalStore-pure/legend-engine-xt-relationalStore-core-pure/src/main/resources/core_relational/relational/functions/tableToTDS.pure:32-39 — `function meta::pure::tds::viewToTDS(view:View[1]):TableTDS[1] { ^TableTDS(store = $view.schema.database, table = $view, columns = ...) }` — the exact body legend-lite is compiling.
- /Users/neemsandv/legend/legend-engine/legend-engine-xts-relationalStore/.../core_relational/relational/functions/tableToTDS.pure:17-20 — `Class meta::relational::mapping::TableTDS extends TabularDataSetImplementation { table : NamedRelation[1]; }` — `store` is inherited, not declared here.
- /Users/neemsandv/legend/legend-engine/legend-engine-core/legend-engine-core-pure/legend-engine-pure-code-compiled-core/src/main/resources/core/pure/mapping/mappingExtension.pure:52-55 — `Class meta::pure::mapping::TabularDataSetImplementation extends TabularDataSet { store : meta::pure::store::Store[1]; }` — the missing supertype that owns `store`.
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/compiler/spec/NewChecker.java:83-94 — `t.model().findProperty(ni.className(), name) ... .orElseThrow(() -> new TypeInferenceException("class '" + ni.className() + "' has no property '" + name + "'"))` — the literal that produced the observed message.
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/compiler/spec/TableReferenceChecker.java:33-79 — `check`: accepts only (db,'TABLE') / (db,'SCHEMA','TABLE'), validates against `CoreFn.TABLE_REFERENCE`, and resolves through `t.model().findTable(dbRef.fullPath(), resolvedName)`.
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/compiler/spec/TableReferenceChecker.java:88-115 — `checkTableToTds`: requires the argument to be a `TypedTableReference`, validates against `CoreFn.TABLE_TO_TDS`, returns the relation as IDENTITY.
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/compiler/spec/CoreFn.java:38-41 — only `TABLE_REFERENCE("tableReference")` and `TABLE_TO_TDS("tableToTDS")`; no view counterparts.
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/builtin/Pure.java:2016-2023 — `TABLE_TO_TDS__RELATION_1` and `TABLE_REFERENCE__STRING_1__STRING_1__STRING_1` are declared; grep for `viewToTDS`/`viewReference` across `core/src/main/java` returns nothing.
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/compiler/element/ModelContext.java:150-157 — `findTable`'s doc: "Searches the database's top-level tables, then its schemas' tables" — no view lookup.
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/lineage/ScanRelations.java:506-527 — `collectTableToTds` matches `"tableToTDS".equals(simple)` over `tr.function().endsWith("tableReference")` and resolves columns via `ctx.findTableDefinition`.
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/lineage/ScanRelations.java:1141-1156 — `findView(ctx, dbName, schema, name)` exists; the VIEW model and its column walk are already built.

</details>

---

## `withPlatform`

| | |
|---|---|
| family | `executionPlan/tests` |
| sweep status | ERROR |
| **verdict** | **MISSING FEATURE** |
| effort | L |
| confidence | high |

**Root cause**

legend-lite's `planToString` channel has NO platform/store split: it lowers the entire query into ONE Relational node. The engine splits it into a `PureExpressionPlatformExecutionNode` (printed `PureExp`) whose child is the Relational node.

The query is `Person.all()->filter(...).lastName->makeString(', ')`. `makeString` is not a store-clusterable function, so the engine's `defaultFunctionProcessor` wraps it in a platform node (executionPlan_generation.pure:47-64) and `executionPlan_print.pure:47` prints it as `'PureExp\n' + ... 'expression = ' + $p.expression->asString(...)`, giving the golden's `expression = [Node Index:0] -> makeString(', ')` over a nested `Relational(... sql = select "root".LASTNAME from personTable ...)`.

In legend-lite, `StatementExecutor`'s planToString path (StatementExecutor.java:603-640) has exactly three shapes: the Sequence path (parameters present, or >1 body statement, or a lone let), a named wall when there is no getAll root, and otherwise `engineSql(lam.body(), ...)` -> `PlanText.single(...)`. `withPlatform` has no parameters and one body statement, so it takes the single-Relational path and lowers the WHOLE expression including `makeString`. `PlanText`'s own class doc (PlanText.java:18-25) states the vocabulary is single-relational and "Anything beyond the single-node vocabulary ... is a named wall" — but no wall exists for a platform terminal, so the expression is silently pushed into SQL.

`Scalars`' makeString rule (Scalars.java:791-810) then lowers it to `COALESCE(ReduceCollection(STRING_AGG, LIST_TRANSFORM(...), [sep]), '')`. `EngineStyleH2` inherits `AnsiSqlRenderer.reduceCollection` (AnsiSqlRenderer.java:360-363), which throws `new DialectCapability("collection reduction '" + rc.reducer() + "' reached a dialect without a list encoding")` — the observed message, verbatim.

Two secondary consequences worth naming:
- The failure is classified ERROR rather than SHAPE only because `DialectCapability extends IllegalStateException` (DialectCapability.java:14), while `PlanAsserts.planTextAssert` catches `NotImplementedException | LegendCompileException | UnsupportedOperationException` (PlanAsserts.java:188-190). That is a classification artefact of the real gap, not the gap itself.
- The dialect wall is HONEST in its own terms: the engine-style H2 renderer genuinely has no list encoding. Pushing makeString into SQL is legitimate for the DuckDB EXECUTION channel (which does have list encoding); it is only wrong for the PLAN channel, where the engine puts it on the platform side.

**Fix**

Two-stage, both platform-side.

Stage 1 (S) — turn the misleading dialect error into a named plan wall, so the outcome is SHAPE and the message says what is actually missing. In `StatementExecutor`'s planToString path (StatementExecutor.java:603-640), before calling `engineSql(lam.body(), ...)`, test whether the body's terminal is store-clusterable. Concretely: if the terminal `TypedSpec` is a `TypedNativeCall` whose callee is not in the relational-clusterable set (the same set `Scalars`/`Lowerer` can push down as a relation-producing or column-producing operator) applied to a relational sub-expression, throw
`new NotImplementedException("planToString: platform (PureExp) node pending — '" + <callee simple name> + "' is not store-clusterable")`.
Do NOT instead add `DialectCapability` to `PlanAsserts`' catch list — see risk.

Stage 2 (L) — build the PureExp envelope:
(a) A store-clusterability predicate over `TypedSpec`, mirroring `meta::relational::contract::supports` (storeContract.pure:206-212). Seed it from the set of natives `Scalars`/`Aggregates`/`Lowerer` already lower into relation operators; everything else is platform.
(b) Split the terminal: find the maximal store-clusterable sub-expression (here `Person.all()->filter(...).lastName`), lower and render it as today via `PlanText.single(...)`, and keep the platform remainder (`->makeString(', ')`).
(c) Add `PlanText.pureExp(typeBlock, resultSizeRange, expressionText, childText)` emitting
`"PureExp\n(\n" + typeBlock + "  resultSizeRange = " + n + "\n  expression = " + expr + "\n" + indent(child) + ")\n"`,
matching executionPlan_print.pure:47's layout (2-space indent, children indented a further 2).
(d) A minimal Pure-expression printer for the platform node's `expression` field that spells the clustered child as `[Node Index:0]` and the applied function as `-> makeString(', ')` — the engine's `asString(^Pref(useClusterIndex=true, ...))` with cluster indices.
(e) The child Relational node's `resultColumns` must print `("root".LASTNAME, VARCHAR(200))` with the store column type, whereas `PlanText.single`'s current scalar-projection arm (PlanText.java:63-76) emits `(<select item>, "")`. That arm needs the typed variant for the platform-child case.

If Stage 2 is out of budget, do Stage 1 and LEDGER the test — it is a single golden and the platform-node vocabulary is a genuine unbuilt surface, not a wrong answer.

**How legend-engine does it** — /Users/neemsandv/legend/legend-engine/legend-engine-core/legend-engine-core-pure/legend-engine-pure-code-compiled-core/src/main/resources/core/pure/platform/executionPlan/executionPlan_generation.pure:47-64 (defaultFunctionProcessor -> PureExpressionPlatformExecutionNode with the store-clustered children) and /Users/neemsandv/legend/legend-engine/legend-engine-core/legend-engine-core-pure/legend-engine-pure-code-compiled-core/src/main/resources/core/pure/executionPlan/executionPlan_print.pure:47 (the 'PureExp' text form).

**Risk** — The tenet-2 trap here is precise and tempting: adding `DialectCapability` (or `IllegalStateException`) to the catch list in `PlanAsserts.planTextAssert` (PlanAsserts.java:188-190) would flip this test from ERROR to SHAPE in one line and change nothing real. That is harness compensation for a platform gap — the harness would be absorbing a shape the plan generator owns. Reclassification must come from the platform raising a named plan wall (Stage 1), not from the harness widening its catch.

Stage 1 risk: the clusterability predicate must not be over-eager. If it walls on constructs the single-Relational path currently handles fine (e.g. terminal aggregates that DO lower into SQL), it will regress currently-passing plan goldens. Start from a deny-list of known platform-only functions (makeString/joinStrings-over-rows and friends) rather than an allow-list.

Stage 2 risk: do not change `Scalars`' makeString rule. Pushing makeString into SQL is CORRECT for the DuckDB execution channel (which has a list encoding); the split belongs in the plan channel only.

**Also unblocks** — Any other executionPlan golden whose root node is PureExp — Pure.java:1615-1617 notes the "pure-only plan shapes (no store)" 2/3-arg executionPlan spellings are walled for the same reason. Those live outside this unit; the Stage-2 envelope would be the shared prerequisite.

**Falsifier** — Grep `core/src/main/java` for `PureExp` and for any platform/store split in the plan path. The only hit is a comment at Pure.java:1617. If instead a PureExp emitter turns up, then the envelope exists and the real cause is that the splitter failed to classify `makeString` as platform — a much smaller fix confined to the clusterability predicate.

<details><summary>Evidence read (10 citations)</summary>

- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/sql/dialect/AnsiSqlRenderer.java:360-363 — `protected String reduceCollection(SqlExpr.ReduceCollection rc) { throw new DialectCapability("collection reduction '" + rc.reducer() + "' reached a dialect without a list encoding"); }` — the exact observed message.
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/lowering/Scalars.java:791-810 — the `makeString` rule builds `COALESCE(new SqlExpr.ReduceCollection(SqlAgg.Fn.STRING_AGG, strs, List.of(sep)), StringLit(""))`, i.e. it always pushes makeString into SQL.
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/StatementExecutor.java:603-640 — the planToString path: Sequence when params/multi-statement/lone-let; `NotImplementedException("planToString: no getAll root (multi-node plans pending)")` when no root; otherwise `engineSql(lam.body(), ...)` + `PlanText.single(...)`. No platform-terminal branch and no platform wall.
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/plan/PlanText.java:18-25 — class doc: single-relational plans only; "Anything beyond the single-node vocabulary (unions, computed projections, multi-node sequences) is a named wall." A grep for `PureExp` across `core/src/main/java` hits only a comment in Pure.java:1617.
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/sql/dialect/DialectCapability.java:14 — `public final class DialectCapability extends IllegalStateException`, so PlanAsserts.java:188-190's catch (`NotImplementedException | LegendCompileException | UnsupportedOperationException`) does not catch it and the test reports ERROR rather than SHAPE.
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/builtin/Pure.java:1615-1617 — "pure-only plan shapes (no store): 2/3-arg spellings type; their plan text is a PureExp node — a named wall at the K-arm until built" — the PureExp gap is already acknowledged in the catalog.
- /Users/neemsandv/legend/legend-engine/legend-engine-core/legend-engine-core-pure/legend-engine-pure-code-compiled-core/src/main/resources/core/pure/platform/executionPlan/executionPlan_generation.pure:47-64 — `defaultFunctionProcessor` builds `^PureExpressionPlatformExecutionNode(expression = $fe, resultType = ..., resultSizeRange = $fe.multiplicity, executionNodes = $children, ...)` — the platform node with the store-clustered child.
- /Users/neemsandv/legend/legend-engine/legend-engine-core/legend-engine-core-pure/legend-engine-pure-code-compiled-core/src/main/resources/core/pure/executionPlan/executionPlan_print.pure:47 — `p:PureExpressionPlatformExecutionNode[1]|'PureExp\n'+$space+'('+header(...)+'\n'+$space+'  expression = '+$p.expression->asString(^Pref(useClusterIndex=true, clusterIndex=extractClusters($p.executionNodes, $extensions)))+...` — the `PureExp` / `expression = [Node Index:0] -> ...` text the golden pins.
- /Users/neemsandv/legend/legend-engine/legend-engine-xts-relationalStore/.../core_relational/relational/contract/storeContract.pure:206-212 — `meta::relational::contract::supports` = `isFunctionSupportedForCluster($f, $state) || $f.func->in(clusteringEscapeFunctions())`; the escape list at :216-225 is sum/olap-func/write only. `makeString` appears nowhere in pureToSQLQuery's PureFunctionToRelationalFunctionPair table, so it is not clusterable and lands on the platform side.
- /Users/neemsandv/legend/legend-engine/.../core_relational/relational/executionPlan/tests/executionPlanTest.pure:1673-1692 — the golden: `PureExp(... expression = [Node Index:0] -> makeString(', ') ( Relational( type = String, resultSizeRange = *, resultColumns = [("root".LASTNAME, VARCHAR(200))], sql = select "root".LASTNAME from personTable as "root" where ...) ))`.

</details>

---

## `testFilterLimitInSequenceForTableAccessor`

| | |
|---|---|
| family | `functions/tests` |
| sweep status | SHAPE |
| **verdict** | **MISSING FEATURE** |
| effort | L |
| confidence | high |

**Root cause**

Identical mechanism to testLimitFilterInSequenceForTableAccessor: `StatementExecutor.planToString` requires a `Class.all()` root. `rootGetAllClass` (StatementExecutor.java:2028-2038) only matches `TypedGetAll`; this query's root is a `TypedTableReference` (`#>{dbInc.personTable}#`), so `rootClass` is null and StatementExecutor.java:612-616 throws the 'planToString: no getAll root' wall. The only difference between the two tests is operator order (filter→limit vs limit→filter), which changes only where the `top 1` and the WHERE land inside the subselect — the wall is hit before any SQL is generated.

**Fix**

Exactly the fix written for testLimitFilterInSequenceForTableAccessor — one change unblocks both. One extra golden detail specific to this pair: both expected SQLs carry `"…".AGE is not null and "…".AGE > 25`, the engine's optional-operand comparison guard (same shape as the class-rooted plan golden at legend-engine .../core_relational/relational/executionPlan/tests/executionPlanTest.pure:1085, `"root".AGE is not null and "root".AGE > ${age}`). Because a relation column is typed `[0..1]`, legend-lite's lowering must emit that guard for relation-column comparisons too, or the plan text will differ even after the root wall is removed. I did NOT confirm where legend-lite decides this (the NullSemantics/filter path in Lowerer.java:1200-1246) — treat it as a second work item to verify once the wall is gone.

**How legend-engine does it** — Same as the sibling test — pureToSQLQuery.pure:601-612 (processRelationStoreAccessor), pureToSQLQuery.pure:3408 (isolateTdsSelect with the literal 'subselect'), postprocessor/defaultPostProcessor/reAliasQuery.pure:38 (identity map for root/unionBase/subselect, everything else renumbered to `<table>_i`).

**Risk** — Same as the sibling. Additionally, the `is not null` guard is a semantic emission rule, not a text rule — if it is added only in the engine-text channel it becomes a text hack; it must come from the IR (the operand's multiplicity), so that executed rows and plan text agree.

**Also unblocks** — testLimitFilterInSequenceForTableAccessor

**Falsifier** — Same as the sibling: if the two tests wall for any reason other than the TypedTableReference root, the diagnosis is wrong. The thrown literal at StatementExecutor.java:613 is unique to that one branch, so this is already close to proven.

<details><summary>Evidence read (4 citations)</summary>

- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/StatementExecutor.java:611-616 — the wall literal, reached whenever rootGetAllClass returns null.
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/StatementExecutor.java:2028-2038 — rootGetAllClass has a TypedGetAll arm only.
- /Users/neemsandv/legend/legend-engine/legend-engine-xts-relationalStore/legend-engine-xt-relationalStore-generation/legend-engine-xt-relationalStore-pure/legend-engine-xt-relationalStore-core-pure/src/main/resources/core_relational/relational/functions/tests/testSliceTakeLimitDrop.pure:119 — the test body: `let fun = {|#>{…personTable}#->filter(x | $x.AGE > 25)->limit(1)->select(~[FIRSTNAME,LASTNAME])}` then `executionPlan(...)->planToStringWithoutFormatting(...)`; the assert is pure plan text.
- /Users/neemsandv/legend/legend-engine/legend-engine-xts-relationalStore/legend-engine-xt-relationalStore-generation/legend-engine-xt-relationalStore-pure/legend-engine-xt-relationalStore-core-pure/src/main/resources/core_relational/relational/pureToSQLQuery/pureToSQLQuery.pure:601-612 — processRelationStoreAccessor: the accessor alias is 'dbAccessor'+nodeId and the select projects every column of the relation (hence the golden's 7-column inner select).

</details>

---

## `testIsEmptyOnCollection`

| | |
|---|---|
| family | `functions/tests` |
| sweep status | FAIL |
| **verdict** | **REAL DEFECT** |
| effort | M |
| confidence | high |

**Root cause**

`$input->isEmpty()` where `input` is a to-many PLAN PARAMETER must become the engine's freemarker template `(${collectionSize(input![])}) = 0`. legend-lite has no such emission at all. The isEmpty lowering rule (Scalars.java:292-300) dispatches purely on multiplicity — `listValued(arg)` is `arg.info().multiplicity().isMany()` (Scalars.java:2419-2421) — and for a many-valued argument it emits `EQUAL(COALESCE(LIST_LENGTH(arg), 0), 0)`, i.e. it treats the plan parameter as a SQL list value. In the engine-text channel that argument is a `SqlExpr.PlanParam` of kind STRING, which EngineStyleH2 renders as `'${input?replace("'", "''")}'` (EngineStyleH2.java:990-993), and LIST_LENGTH spells `len` (Spellings.java:83, EngineStyleH2 is constructed with `Spellings.DUCKDB` at EngineStyleH2.java:204). So the plan SQL reads roughly `where coalesce(len('${input?replace("'", "''")}'), 0) = 0` instead of `where (${collectionSize(input![])}) = 0` — not merely different text, but a template that computes the string length of the rendered parameter. The string 'collectionSize' appears exactly once in the whole main source tree, as the SUPPORT-FUNCTION constant text (PlanSupportFunctions.java:36-39); it is never emitted into SQL. The plan envelope around it is right (the failure's 'got' prefix matches the expected Sequence/FunctionParametersValidationNode/type/resultColumns byte for byte up to `sql=select"root".LEGA`), so the WHERE clause is the diff.

**Fix**

Add the plan-parameter arm, mirroring the existing renderCollection arm. Preferred (IR-level, so execution and text agree): in Scalars.java:292-308, when the isEmpty/isNotEmpty argument lowers to a `SqlExpr.PlanParam` whose multiplicity is many, emit a dedicated IR node — add `SqlFn.VAR_COLLECTION_SIZE` (one arg) and produce `EQUAL(VAR_COLLECTION_SIZE(param), 0)` / `GREATER(VAR_COLLECTION_SIZE(param), 0)` instead of the COALESCE(LIST_LENGTH(...)) shape. Then in EngineStyleH2.expr, next to the IN/renderCollection arm at EngineStyleH2.java:1136-1155, add a VAR_COLLECTION_SIZE arm rendering `(${collectionSize(" + p.name() + "![])})`; the enclosing EQUAL renders ` = 0` through the existing comparison path, giving exactly `(${collectionSize(input![])}) = 0`. The execution dialect (DuckDb) keeps the list_length spelling for VAR_COLLECTION_SIZE since at execution time the parameter is bound to a real collection. Minimal alternative if adding an SqlFn is undesirable: pattern-match `EQUAL(COALESCE(LIST_LENGTH(PlanParam p), 0), IntLit 0)` inside EngineStyleH2.expr and rewrite it to the template — but that is text-channel-only and leaves the wrong shape in the IR, so prefer the first.

**How legend-engine does it** — legend-engine dispatches this explicitly. .../core_relational/relational/pureToSQLQuery/pureToSQLQuery.pure:4272-4284 `processSubEmpty` computes `isParameterPlanVarOfTypeList = isParamPlanVar($parameters, $vars, $state.inScopeVars) && $parameters.multiplicity == ZeroMany` and, when true, calls `processIsEmptyOnPlanVar`. That function (pureToSQLQuery.pure:4240-4252) builds `^DynaFunction(name = 'equal', parameters = [^FreeMarkerOperationHolder(name = 'variableCollectionSize', parameters = [$columns]), ^Literal(value = 0)])`. The freemarker op renders via .../sqlQueryToString/dbExtension.pure:722 `pair('variableCollectionSize', ^ToSql(format='collectionSize(%s)'))`, wrapped as `'(${' + format + '})'` by dbExtension.pure:715-717 — hence `(${collectionSize(input![])}) = 0`. The `collectionSize` freemarker function itself is .../relationalMappingExecution.pure:340-342.

**Risk** — The COALESCE(LIST_LENGTH(...)) shape is also produced for genuinely list-valued SQL expressions (carrier lists), and CarrierStrategies.java:501-507 already pattern-matches LIST_LENGTH; scoping the new arm strictly to `SqlExpr.PlanParam` arguments avoids touching those. Tenet-2 trap: do not normalise the golden or relax the plan-text compare in PlanAsserts — plan text compares literally by design (the toSQLString doctrine).

**Also unblocks** — Any other corpus test whose plan asserts isEmpty/isNotEmpty over a to-many plan parameter; none confirmed in this brief.

**Falsifier** — Print the plan string this test produces and look at the WHERE clause. If it already reads `(${collectionSize(input![])}) = 0`, my root cause is wrong and the diff lies elsewhere in the SQL (the sweep truncated the 'got' at `sql=select"root".LEGA`, so I inferred the diff position from the matching prefix rather than observing it).

<details><summary>Evidence read (8 citations)</summary>

- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/lowering/Scalars.java:292-300 — the isEmpty rule: `listValued(n.args().get(0)) ? EQUAL(COALESCE(LIST_LENGTH(args.get(0)), 0), 0) : IS_NULL(args)`.
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/lowering/Scalars.java:2419-2421 — `listValued(TypedSpec arg) { return arg.info().multiplicity().isMany(); }` — `input:String[*]` is many, so the list arm is taken.
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/sql/dialect/EngineStyleH2.java:982-993 — a non-optional STRING PlanParam renders `'${name?replace("'", "''")}'`.
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/sql/dialect/Spellings.java:83 — `m.put(SqlFn.LIST_LENGTH, "len")`.
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/plan/PlanSupportFunctions.java:36-39 — COLLECTION_SIZE is only the `<#function collectionSize …>` support-function TEXT; grep over core/src/main/java shows no other 'collectionSize' occurrence, i.e. no emission site.
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/sql/dialect/EngineStyleH2.java:1136-1155 — the ALREADY-IMPLEMENTED sibling: an IN over a collection PlanParam renders `… in (${renderCollection(name![] "," … "null")})`. This is the exact pattern the collectionSize arm is missing.
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/StatementExecutor.java:826-895 — sequencePlan: builds the FunctionParametersValidationNode and binds each lambda parameter as a `SqlExpr.PlanParam` with `PlanParams.kindOf(p.type())`; this is the path this test takes (the lambda has one parameter).
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/StatementExecutor.java:262-300 — `$plan.processingTemplateFunctions` IS implemented, so the test's second assert (assertSameElements(templateFunctionsList(), …)) should pass once the first one does.

</details>

---

## `testLimitFilterInSequenceForTableAccessor`

| | |
|---|---|
| family | `functions/tests` |
| sweep status | SHAPE |
| **verdict** | **MISSING FEATURE** |
| effort | L |
| confidence | high |

**Root cause**

`StatementExecutor.planToString` derives EVERY downstream plan fact from a Pure CLASS root. At StatementExecutor.java:611 it calls `rootGetAllClass(lam.body())`, which (StatementExecutor.java:2028-2038) walks the typed tree and returns a class FQN only for a `TypedGetAll` node. This query's root is `#>{meta::relational::tests::dbInc.personTable}#`, which the G-phase compiles to `TypedTableReference(store, table, RelationType)` (TypedTableReference.java:21) — never a TypedGetAll. So `rootClass` is null and line 612-616 throws NotImplementedException('planToString: no getAll root (multi-node plans pending)'). The message is misleading (this is not a multi-node plan) but the wall is honest: there is genuinely no relation-rooted plan path. Everything the printer would call next is class-keyed too — `PlanText.single` (PlanText.java:52-58) immediately calls `ScanRelations.rootImpl(ctx, mappingFqn, rootClassFqn, chainMappings)` to get `[definingMapping, setId, dbFqn, mainTable]`, and that method throws 'plan: no class mapping for ...' when the class is absent (ScanRelations.java:587-598). A table accessor has no class and no mapping — its db/table identity is carried directly on the TypedTableReference. Execution of such a query already works (Lowerer.java:325-326 lowers TypedTableReference to `SqlSelect.starOf(SqlSource.Table(...))`); only the plan-TEXT surface is absent.

**Fix**

Two parts, both platform-side.
(1) Relation-rooted plan printing. In StatementExecutor.java add `private static @Nullable TypedTableReference rootTableRef(List<TypedSpec> body)` (same BFS as rootGetAllClass, matching TypedTableReference). At StatementExecutor.java:611, when `rootGetAllClass` returns null, fall back to that: if a TypedTableReference root exists, take a relation-root branch that skips `ScanRelations.rootImpl` entirely and passes the store identity directly — `impl = new String[]{"", "", tref.store(), tref.table()}`. Add a `PlanText.singleRelationRoot(ctx, String[] impl, SqlQuery plan, String sql, List<TypedSpec> body, String connectionName)` overload that reuses the existing body of `single` (PlanText.java:52-84) but takes the prebuilt `impl` instead of calling rootImpl; the `typeBlock` Class-envelope branch (PlanText.java:125-128) is unreachable for a relation root because the terminal's type is always a RelationType, so only the TDS branch (PlanText.java:106-107) runs. Do the same at the sequencePlan twin wall (StatementExecutor.java:877-881, 'plan: sequence terminal without a getAll root').
(2) TDS tuple types for a relation root. The golden's type block is `TDS[(FIRSTNAME,meta::pure::precisePrimitives::Varchar,VARCHAR(1024),"")]` while resultColumns is `("FIRSTNAME",VARCHAR(200))`. That means the type block for a relation root spells the PURE column type (precisePrimitives) and the DEFAULT relational type for that Pure type (String→Varchar(1024), per legend-engine .../core_relational_duckdb/relational/typeConversion.pure:31 and the H2 equivalent), NOT the physical store column, whereas resultColumns keeps reading the physical column off the SQL IR. `PlanText.tdsTuples` must take that distinction as a flag (it already has an `impl.length > 4` m2m flag at PlanText.java:107 doing an analogous thing).
(3) Engine alias conventions (needed because plan text compares LITERALLY through PlanAsserts.planTextAssert, unlike golden-SQL asserts which are advisory): in EngineStyleH2.planSource (EngineStyleH2.java:330-347) the leftmost Table of a root scope must render "root" ONLY when the query root is a class extent. For a table-accessor root the leftmost table must take `nextInGroup(group)` like any other member, and the enclosing TDS isolation frame must render the literal `subselect` (reAliasQuery.pure:38 identity mapping) while still consuming its group index. Model this by giving `SqlSource.Subselect` a frameName of "subselect" at the isolation site (Lowerer.isolate, Lowerer.java:3466-3468) and special-casing that name in planSource: consume `nextInGroup(firstInnerTable(...))` but rename to the literal "subselect".
Do NOT fix any of this in the harness — the assert path (EngineTestExecutor.java:1820-1824 → PlanAsserts.planTextAssert) is correct as written.

**How legend-engine does it** — legend-engine names the table-accessor alias itself and lets the re-alias post-processor renumber it: legend-engine-xts-relationalStore/.../core_relational/relational/pureToSQLQuery/pureToSQLQuery.pure:601-612 `processRelationStoreAccessor` builds `^TableAlias(name = 'dbAccessor' + buildNodeId($nodeId, '_i' + $index), relationalElement = $relationalElement)` and projects every one of the relation's columns. The TDS isolation frame is literally named 'subselect' (pureToSQLQuery.pure:3408 `$select->isolateTdsSelect($query, 'subselect', $extensions)`), and postprocessor/defaultPostProcessor/reAliasQuery.pure:38 identity-maps it — `newMap($keyValuePairs)->putAll([pair('root','root'),pair('unionBase','unionBase'),pair('subselect','subselect')])` — while 'dbAccessor…' falls through to the group renaming and becomes `persontable_1` (the 'subselect' pair consumed index _0 of the persontable group). That is exactly the golden's `from (select top 1 … from personTable as "persontable_1") as "subselect"`. Relation ops reuse the TDS processors (pureToSQLQuery.pure:10304/10319/10325 map relation::filter/limit/select to processTdsFilter/processTake/processTdsRestrict), so nothing else about the SQL shape is special.

**Risk** — Part (3) changes alias numbering for every engine-text query that goes through `isolate()`, including class-rooted TDS goldens (e.g. testLimitFilterInSequence, whose golden also spells `as "subselect"`). Those asserts are currently ADVISORY (row-replayed on H2, EngineTestExecutor.java:1836-1840 region) so they will not regress on text, but any plan-TEXT golden containing an isolation frame will change. Tenet-2 trap: it is tempting to make the harness compare plan text loosely, or to special-case the two table-accessor tests in the runner — both are harness compensation. The wall as it stands is honest; leaving it is better than a fake pass.

**Also unblocks** — testFilterLimitInSequenceForTableAccessor (same wall, same fix). Any other corpus test that calls executionPlan/planToString over a `#>{db.table}#` relation root would be unblocked by part (1)+(2).

**Falsifier** — Add a temporary `System.err.println` (or run the two tests with the wall replaced by a stub that prints the terminal spec class) — if `rootGetAllClass` is null for some reason OTHER than a TypedTableReference root (e.g. the `$fun` let never resolves to a TypedLambda and the failure is really the earlier 'executionPlan whose query argument is not a lambda' path), this diagnosis is wrong. The failure literal at StatementExecutor.java:613 already rules that out, since it is thrown strictly after the lambda and mapping checks.

<details><summary>Evidence read (8 citations)</summary>

- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/StatementExecutor.java:611 — `String rootClass = rootGetAllClass(lam.body());` followed at :612-616 by `throw new NotImplementedException("planToString: no getAll root (multi-node plans pending)")` — the exact literal in the failure detail.
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/StatementExecutor.java:2028-2038 — `rootGetAllClass` BFS returns `ga.classFqn()` only for `instanceof TypedGetAll`, else null. No TypedTableReference arm.
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/compiler/spec/typed/TypedTableReference.java:21 — `record TypedTableReference(String store, String table, ExprType info)`, javadoc: 'the #>{db.TABLE}# source', info is a bare RelationType.
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/lowering/Lowerer.java:325-326 — `case TypedTableReference t -> SqlSelect.starOf(new SqlSource.Table(t.table(), nextAlias(), outputsOf(t.info())))`; execution of the shape is implemented.
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/plan/PlanText.java:52-58 — `single(...)` opens with `String[] impl = ScanRelations.rootImpl(ctx, mappingFqn, rootClassFqn, chainMappings);` — the printer's db identity is class+mapping derived.
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/lineage/ScanRelations.java:587-598 — `rootImpl` throws NotImplementedException("plan: no class mapping for '"+classFqn+"' under '"+mappingFqn+"'") when the class is unmapped.
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/sql/dialect/EngineStyleH2.java:330-347 — planSource renames the leftmost Table of a root scope unconditionally to "root"; there is no table-accessor (`dbAccessor`) convention.
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/sql/dialect/EngineStyleH2.java:349-377,394-397 — a Subselect frame is renamed `nextInGroup(firstInnerTable(...))` i.e. `persontable_0`; the literal alias `subselect` is never produced (grep for '"subselect"' over core/src/main/java returns exactly one hit, EngineStyleH2.java:432, a group-NAME fallback, not an alias).

</details>

---

## `testSortByLambdaAndGraphFetchDeep`

| | |
|---|---|
| family | `functions/tests` |
| sweep status | FAIL |
| **verdict** | **EXECUTION-TARGET ARTIFACT** |
| effort | S |
| confidence | medium |

**Root cause**

Top-level ORDER BY null placement. `Fold.sortNulls(boolean ascending)` returns null unconditionally (Fold.java:344-346), so every top-level sort key lowers with `nullOrder == null` (its only four call sites, Lowerer.java:1596/1617/1622/1633, are the sort/sortBy lowering) and `AnsiSqlRenderer.sortKey` (AnsiSqlRenderer.java:198-205) emits no NULLS clause. DuckDB's default for ASC is NULLS LAST; H2's is NULLS FIRST (null smallest). The corpus golden was produced on H2, so the address-less person sorts FIRST there and LAST here — exactly the observed `$[0].address expected null, got {name=Hoboken}`. This is a half-reverted design, not an oversight: EngineStyleH2.sortKey still carries the matching half (EngineStyleH2.java:1229-1241) — it computes `h2Default = ascending ? NULLS_FIRST : NULLS_LAST` and suppresses a nullOrder that merely restates it, with the comment 'C1.2 puts it in the IR so DuckDB, whose default differs, can render it explicitly'. With sortNulls returning null that suppression is dead code and DuckDB never gets the explicit clause. The H2 second target compounds it: H2.java:275-281 appends ' NULLS LAST' whenever nullOrder is null, i.e. it forces H2 to imitate DuckDB rather than the other way round.

**Fix**

Restore the C1.2 IR pin that EngineStyleH2 is already written to absorb: change `Fold.sortNulls` (Fold.java:344-346) to `return ascending ? SqlSelect.SortKey.NullOrder.NULLS_FIRST : SqlSelect.SortKey.NullOrder.NULLS_LAST;` and update its javadoc. Consequences, all intended: DuckDb (via AnsiSqlRenderer.sortKey) then emits `… ASC NULLS FIRST`, matching H2; EngineStyleH2.sortKey suppresses it (EngineStyleH2.java:1237 `k.nullOrder() != h2Default`) so no golden plan/SQL text changes; H2.java:275-281's `nullOrder == null` branch stops firing, so the H2 replay target emits the same explicit clause instead of its current forced NULLS LAST — the two targets stay consistent. Leave aggregate-internal ORDER BY alone: AnsiSqlRenderer.reducer (AnsiSqlRenderer.java:695-706) inlines its own order rendering without sortKey, and H2.java:362-375 keeps the ASC→NULLS LAST aggregate convention that the joinStrings pin needs. Leave window keys alone: they already carry explicit orders (Lowerer.java:1996-2001 ASC→NULLS_LAST, Comparators.java:83).

**How legend-engine does it** — legend-engine emits no NULLS clause at all for a top-level order by (the goldens across the corpus, e.g. .../core_relational/relational/functions/tests/testSort.pure's `order by concat(…) asc`), so the engine's row order IS the connected database's default — and the reference database for every corpus expectation is H2 (`TestDatabaseConnection(type="H2")`). Matching the corpus therefore means matching H2's null-smallest ordering.

**Risk** — EngineStyleDB2 extends EngineStyleH2 (EngineStyleDB2.java:20) and EngineStyleComposite extends EngineStyleDB2 (EngineStyleComposite.java:15), so all three inherit the H2-keyed `h2Default` suppression. If DB2's own default null placement differs from H2's, a DB2 plan/SQL golden with an ASC sort would suddenly gain a ' nulls first' token — that is the most likely identity of the 'five engine sort/groupBy pins' the earlier attempt broke. Before landing, make the suppression key off a per-dialect `defaultNullOrder(boolean ascending)` hook rather than a hard-coded h2Default. Second risk: row order changes for every query with a nullable ORDER BY key; all such goldens come from H2, so the movement should be toward truth, but the DIFF-count must be measured, not assumed. Tenet-2 trap: do NOT fix this by adding `SET default_null_order='nulls_first'` in the corpus runner's DuckDB session (core/src/test/java/com/legend/rcorpus/DuckWorkspaces.java:80-101) — that is harness compensation for a dialect-owned decision.

**Also unblocks** — Plausibly other row-order failures whose ORDER BY key is nullable; none confirmed from this brief. Note testSortByLambdaDeepOptional (same file) fails for an unrelated reason ('zip over inputs that are not two scalar projections of the SAME class chain') and would NOT be fixed by this.

**Falsifier** — Two cheap checks. (a) Confirm the 'got' array has 12 elements and ends with `{"address":null}` — if it has 11, the null-address person is being dropped (an inner-join / toOne() defect) and the diagnosis is wrong. (b) Run `select name from (values ('b'),(null),('a')) t(name) order by name asc` on the project's DuckDB build: if null comes first, DuckDB is not the source of the divergence and this diagnosis collapses.

<details><summary>Evidence read (8 citations)</summary>

- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/lowering/Fold.java:334-346 — `sortNulls` returns null, with the documented revert note: 'the earlier global ASC->FIRST pin forced H2's null-smallest onto DuckDB … A dialect that needs explicit placement for cross-target row parity says so in ITS sortKey (H2 does).'
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/lowering/Lowerer.java:1596,1617,1622,1633 — the only four `Fold.sortNulls(...)` call sites, all top-level sort/sortBy key construction.
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/sql/dialect/AnsiSqlRenderer.java:198-205 — `sortKey` appends NULLS FIRST/LAST only when `k.nullOrder() != null`; DuckDb extends AnsiSqlRenderer (DuckDb.java:21) and does NOT override sortKey (grep for 'sortKey' in DuckDb.java: no hits).
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/sql/dialect/EngineStyleH2.java:1229-1241 — the surviving other half: 'H2 sorts null SMALLEST by default (ASC first / DESC last) and the engine never spells a NULLS clause — suppress a placement that just restates that default (C1.2 puts it in the IR so DuckDB, whose default differs, can render it explicitly)'.
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/sql/dialect/H2.java:275-281 — the H2 replay target appends ' NULLS LAST' when nullOrder is null, i.e. it currently pins H2 to DuckDB's convention.
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/sql/dialect/H2.java:360-361 — 'witnessed: sorted joinStrings led with TDSNull under H2's NULLS-FIRST default' — independent in-repo confirmation that H2's default really is NULLS FIRST for ASC.
- /Users/neemsandv/legend/legend-engine/legend-engine-xts-relationalStore/legend-engine-xt-relationalStore-generation/legend-engine-xt-relationalStore-pure/legend-engine-xt-relationalStore-core-pure/src/main/resources/core_relational/relational/functions/tests/testSort.pure:89 — the test body: `sortBy(p | $p.address->toOne().name)->graphFetch(...)->serialize(...)`, expected JSON leads with `{"address":null}`; the sibling testSortByLambdaDeepOptional (same file, ~line 80) pins `order by concat("addresstable_0".NAME, …) asc` with a LEFT OUTER join, so the sort key really is NULL for the address-less person.
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/resolver/GraphEmission.java:451-455 — `if (!containsExplicitSort(pipeline)) { orderKeys.addAll(pkOrderKeys(...)); }` — the PK-order-keys-override-sortBy hypothesis recorded in docs/CORPUS_BURNDOWN_INDEX.md:52 ('graphFetch appends PK order keys unconditionally', confidence 'very high') is STALE: the guard is already there. Do not act on that doc entry.

</details>

---

## `testFilterAfterJoinInRelationWithExtendedPrimitives`

| | |
|---|---|
| family | `functions/tests/projection` |
| sweep status | FAIL |
| **verdict** | **REAL DEFECT** |
| effort | L |
| confidence | high |

**Root cause**

legend-lite ERASES extended primitive types (`Primitive X extends String`) to their base primitive at kind-classification time, so the declared type name is unrecoverable downstream. `ModelBuilder` stores each `PrimitiveExtensionDefinition` only as `fqn -> baseTypeName` and `findPrimitiveExtension` chases the chain down to a `Type.Primitive` constant; `TypeClassifier.findType` then returns that base primitive for the extension FQN. `Type` is a sealed interface with no variant that can carry an extension's own FQN. Consequently `Firm_ExtendedPrimitives.legalName : ExtendedString[1]` types as `Type.Primitive.STRING`, the projected TDS `Type.Column` carries STRING, and `PlanText.tdsTuples` spells `pureName(cols.get(i).type())` = `"String"`. The golden requires `meta::relational::tests::model::simple::ExtendedString`. The sibling test `testFilterAfterJoinInRelation` — same query shape over the non-extended `Firm` — is not in the failing corpus, and the sweep's `got` string is byte-identical to `expected` except for the two type names, which pins the defect to exactly this erasure.

**Fix**

Stop erasing, and teach the plan printer the new spelling. (1) Add a variant to core/src/main/java/com/legend/compiler/element/type/Type.java: `record PreciseType(String fqn, Primitive base) implements Type { public String typeName() { return fqn.substring(fqn.lastIndexOf("::") + 2); } }` and add it to the `permits` clause. (2) In core/src/main/java/com/legend/compiler/element/TypeClassifier.java:39-42, return `new Type.PreciseType(fqn, ext.get())` instead of the bare base. (3) In core/src/main/java/com/legend/plan/PlanText.java:452, add a first arm `if (t instanceof Type.PreciseType pt) { return pt.fqn(); }` — mirroring the existing `EnumType` arm at line 482-484 which already returns an FQN. (4) The load-bearing part: every site that compares a `Type` by identity against a `Type.Primitive` constant (`t == Type.Primitive.STRING`, `t instanceof Type.Primitive`, family()/isNumeric()/isTemporal() dispatch, the SQL dialect's type mapping, coercion in InferenceKernel, subtyping in ModelContext) must transparently see through `PreciseType` to its `base`. The mechanical way to do this without auditing hundreds of call sites is to introduce one normalizing accessor (e.g. `static Type erased(Type t)` returning `t instanceof PreciseType p ? p.base() : t`) and route the compare-by-kind paths through it, keeping the precise form ONLY on `Type.Column`, property types and plan spelling. Anything you miss fails loud (unhandled switch / NotImplementedException), not silently.

**How legend-engine does it** — /Users/neemsandv/legend/legend-engine/.../core_relational/relational/contract/storeContract.pure:167 — the TDS type line is printed as `'TDS['+$tdsResultType.tdsColumns->map(c|'('+$c.name+', '+$c.type->toOne()->elementToPath()+ ', '+ ...)`. `elementToPath()` on the built-in `String` primitive yields `String` (primitives live at the root package), while an extended primitive declared in `meta::relational::tests::model::simple` yields its full path — which is exactly the golden's two spellings. The engine never erases the extension; the column's `type` IS the `ExtendedString` PrimitiveType.

**Risk** — Adding a variant to a sealed `Type` breaks every exhaustive switch over it — that is a compile-time wall, which is the safe failure mode, but it is a wide diff. The real hazard is the identity comparisons (`t == Type.Primitive.STRING`) which are NOT compile errors and would start silently taking the wrong branch for extended primitives: SQL type mapping, literal coercion, and comparator selection. Every one must be routed through the erasing accessor. Narrower alternative if the blast radius is unacceptable: keep the erasure everywhere and carry the declared type name only on `Type.Column` for plan spelling — but that is a second, parallel truth about types and will drift; I do not recommend it. In THIS test the divergence is text-only (rows are not asserted and the SQL is byte-identical), so the change can be scheduled rather than rushed.

**Falsifier** — If `Firm_ExtendedPrimitives.legalName` is NOT reaching PlanText as `Type.Primitive.STRING` — e.g. if the property type is already lost earlier, at parse time, so that `PrimitiveExtensionDefinition` is never even created for `ExtendedString` — the fix location moves to the parser. Cheapest check: confirm `ElementParser` line ~1240-1255 produced a `PrimitiveExtensionDefinition(meta::relational::tests::model::simple::ExtendedString, String)` for simpleTestModel.pure:18, e.g. by asserting `ModelBuilder.primitiveExtensions` contains that key in a scratch unit test.

<details><summary>Evidence read (7 citations)</summary>

- core/src/main/java/com/legend/compiler/element/TypeClassifier.java:37-42 — "// a PRECISE PRIMITIVE is its base primitive in the query path"; `findType` returns `model.findPrimitiveExtension(fqn)`'s base `Type.Primitive`
- core/src/main/java/com/legend/compiler/ModelBuilder.java:245-246 — `case PrimitiveExtensionDefinition pe -> mb.primitiveExtensions.put(pe.qualifiedName(), pe.baseTypeName());` — only the base name is retained
- core/src/main/java/com/legend/compiler/ModelBuilder.java:357-374 — `findPrimitiveExtension` chases the chain and returns a `Type.Primitive`, discarding the extension identity
- core/src/main/java/com/legend/compiler/element/type/Type.java:46-50 — `sealed interface Type permits Type.Primitive, Type.PrecisionDecimal, Type.ClassType, Type.EnumType, Type.TypeVar, Type.GenericType, Type.FunctionType, Type.RelationType, Type.SchemaAlgebra` — no precise-primitive variant
- core/src/main/java/com/legend/plan/PlanText.java:341-344 — the TDS tuple spells `pureName(cols.get(i).type())`; core/src/main/java/com/legend/plan/PlanText.java:452-485 — `pureName` maps `Type.Primitive.STRING` to `"String"`, has an `EnumType` arm that returns the FQN, and throws for anything else
- /Users/neemsandv/.claude/jobs/5671074c/tmp/burndown/failing.json — the untruncated record: expected and got differ ONLY in `(name,meta::relational::tests::model::simple::ExtendedString,...)` vs `(name,String,...)` (and the same for employeeName); SQL, resultColumns and connection are identical
- /Users/neemsandv/legend/legend-engine/.../core_relational/relational/tests/testModel/simpleTestModel.pure:18,93-95 — `Primitive meta::relational::tests::model::simple::ExtendedString extends String` and `Firm_ExtendedPrimitives.legalName : ExtendedString[1]`

</details>

---

## `testGraphFetch`

| | |
|---|---|
| family | `graphFetch/domain` |
| sweep status | SHAPE |
| **verdict** | **TESTS ENGINE INTERNALS** |
| effort | XL |
| confidence | high |

**Root cause**

The test calls `meta::pure::graphFetch::domain::extractDomainTypeClassFromFunction`, a legend-engine function written IN PURE that reflects over the M3 abstract syntax of the caller's own lambda and REWRITES it: its first statement is `let main = $func.expressionSequence->evaluateAndDeactivate();`, and it then does `$main->toOne()->instanceOf(FunctionExpression)`, compares `.func` against the `graphFetch_T_MANY__RootGraphFetchTree_1__T_MANY_` function handle, reaches into `.parametersValues->at(1)->cast(@InstanceValue)`, and synthesizes new `^LambdaFunction<{->Any[*]}>(expressionSequence = ...)` values via `newLambdaFunction`/`reprocessVariables`. legend-lite walls on the very first hop: `Pure.FUNCTION_DEFINITION` is declared as `native Class meta::pure::metamodel::function::FunctionDefinition<F> extends Function<F> {}` with an EMPTY property list, so `Typer`'s property-access arm for a `Type.ClassType` receiver finds no property, finds no generated milestoning member, is not `elementOverride`, and throws the observed message. Declaring the property would move the wall one step, not fix the test: legend-lite has no `ValueSpecification`/`FunctionExpression`/`InstanceValue` M3 value model, no `evaluateAndDeactivate`/`reactivate`, and no `newLambdaFunction` — i.e. no self-reflective compiler surface at all.

**Fix**

Do not fix — ledger it. The honest action is to record this as an unported surface ("M3 function reflection: FunctionDefinition.expressionSequence and the ValueSpecification value model") and leave the wall exactly as it is. Specifically: do NOT add `expressionSequence: ValueSpecification[1..*]` to `Pure.FUNCTION_DEFINITION` (core/src/main/java/com/legend/builtin/Pure.java:526) as a cosmetic unblock — the property would type but nothing could evaluate it, and the wall would simply move to `evaluateAndDeactivate` / `instanceOf(FunctionExpression)` / `newLambdaFunction` with a less informative message. If this family is ever prioritised, the scope is: an M3 `ValueSpecification` value model (FunctionExpression / InstanceValue / VariableExpression) reified from the already-parsed `com.legend.protocol.spec` AST, plus `evaluateAndDeactivate`/`reactivate`/`newLambdaFunction`/`reprocessVariables` as natives — an entire subsystem, not a patch.

**How legend-engine does it** — legend-pure m3.pure:2155-2172 is the authoritative declaration of `FunctionDefinition.expressionSequence : ValueSpecification[1..*]`; legend-engine's domainManagement.pure:9-27 is the Pure-implemented compiler transformation the test drives directly.

**Risk** — The tempting one-line change (declaring `expressionSequence` on FUNCTION_DEFINITION) would flip this row's message without changing behaviour, and would additionally let `testPreprocessFunctionOnRuntime` past its `^FunctionDefinition(expressionSequence=...)` construction check into a deeper, more confusing failure. That is message laundering, not a fix. Tenet-2 trap: do not make the harness special-case `extractDomainTypeClassFromFunction`.

**Also unblocks** — testPreprocessFunctionOnRuntime (executionPlan/tests) hits the SAME missing M3 property from the construction side — its detail is "class 'meta::pure::metamodel::function::FunctionDefinition' has no property 'expressionSequence'" (the quoted NewChecker form, core/src/main/java/com/legend/compiler/spec/NewChecker.java:44). It would be unblocked at the TYPING level by the same declaration, but — same caveat — not made to pass by it.

**Falsifier** — If legend-lite already has an M3 value-specification reflection layer that I did not find, this verdict is wrong and the fix is just the missing property declaration. Cheapest check: `grep -rn "evaluateAndDeactivate\|FunctionExpression\|newLambdaFunction" core/src/main/java` — if those surfaces exist as natives with real implementations, re-open this as MISSING_FEATURE/S.

<details><summary>Evidence read (6 citations)</summary>

- core/src/main/java/com/legend/builtin/Pure.java:526 — `FUNCTION_DEFINITION = nativeClass("native Class meta::pure::metamodel::function::FunctionDefinition<F> extends meta::pure::metamodel::function::Function<F> {}")` — empty property block
- core/src/main/java/com/legend/compiler/spec/Typer.java:2542-2567 — the `Type.ClassType` arm of property access: `ctx.findProperty(...)` returns null, `Temporal.generatedMember` returns null, the property is not `elementOverride`, so `throw new TypeInferenceException("class " + ct.fqn() + " has no property '" + ap.property() + "'")` — byte-for-byte the observed detail
- /Users/neemsandv/legend/legend-engine/legend-engine-core/legend-engine-core-pure/legend-engine-pure-code-compiled-core/src/main/resources/core/pure/graphFetch/domain/domainManagement.pure:9-27 — `extractDomainTypeClassFromFunction`'s body: `$func.expressionSequence->evaluateAndDeactivate()`, `instanceOf(FunctionExpression)`, `.func == graphFetch_T_MANY__RootGraphFetchTree_1__T_MANY_`, `.parametersValues->at(1)->cast(@InstanceValue)`
- /Users/neemsandv/legend/legend-engine/legend-engine-core/.../domainManagement.pure:48-113 — the rewrite half: `newLambdaFunction(^FunctionType(...))`, `^LambdaFunction<{->Any[*]}>(expressionSequence = ...->slice(0,...)->map(x|$x->reprocessVariables(...))->concatenate(generateFunctionExpressionByOperationType(...)))`
- /Users/neemsandv/legend/legend-engine/.../core_relational/relational/graphFetch/domain/domainManagementTests.pure:53-84 — the test body: it calls the extractor, then maps each extracted `$r.second` through `executionPlan(...)` and asserts the plan strings
- /Users/neemsandv/legend/legend-pure/legend-pure-core/legend-pure-m3-core/src/main/resources/platform/pure/grammar/m3.pure:2155-2172 — real M3 declares `FunctionDefinition`'s property `expressionSequence` with generic type `ValueSpecification` and multiplicity `OneMany`

</details>

---

## `testCheckedWithCircularConstraints`

| | |
|---|---|
| family | `graphFetch/tests` |
| sweep status | FAIL |
| **verdict** | **REAL DEFECT** |
| effort | L |
| confidence | high |

**Root cause**

legend-lite's `graphFetchChecked` envelope evaluates class constraints ONLY on the ROOT graph node and always emits an EMPTY defect path, so a constraint defect belonging to a NESTED class in the tree can never be produced. Two concrete code facts: (1) `StoreResolver` sets `checkedEnvelope` from the terminal serialize (StoreResolver.java:2584) and passes it exactly once, to the ROOT `buildGraphNode` call (StoreResolver.java:2958); the 10-arg overload calls `withChecked` only for that node (GraphEmission.java:461), while every child is built through `graphChild` → the 9-arg overload (GraphEmission.java:1392-1394), which delegates with `checked = false` (GraphEmission.java:219-220). So `TypedSerializeGraph.checkedConstraints()` is non-null only at the root. (2) Even at the root, `CheckedEnvelope.wrap` hard-codes the defect's `path` to an empty array (`SqlFn.TO_VARIANT, new SqlExpr.ArrayLit(List.of())`, CheckedEnvelope.java:51-53), so the engine's `path=[{"propertyName":"firm","index":null}]` shape is unrepresentable. For this test the root class Person's three constraints all evaluate true off the mapping bindings (matching the engine), so rows 0..3 come out with `defects: []` — which is why the first diff is at $[2].defects (expected 1 defect on Firm X's rows, got 0). The engine's expected output additionally pins a DOCUMENTED ENGINE BUG: the test's own `// toFix: after fixing isDistinct related bug` comment says the correct answer is all-empty defects; the asserted answer is the defect the engine emits when `Firm.duplicateEmployee` (`$this.type == CORPORATION || $this.employees->isDistinct(#{Person{firstName,lastName}}#)`) throws while evaluating `employees` — the generated Java catches the exception and emits 'Unable to evaluate constraint [duplicateEmployee]: data not available - check your mappings'. Firm Y rows carry no defect because `type == CORPORATION` short-circuits before `employees` is touched.

**Fix**

Two separable changes; do the first, and only do the second deliberately.
(a) NESTED CHECKED CONSTRAINTS (the real gap). Thread `checked` through `GraphEmission.graphChild` into the child `buildGraphNode` call at GraphEmission.java:1392, and give `TypedSerializeGraph.CheckedConstraint` a `path` field (list of {propertyName, index}) built from the tree position — root = [], a child of property p = parent path + {propertyName:p, index: null for to-one, the array index for to-many}. `CheckedEnvelope.wrap` (CheckedEnvelope.java:51-53) then renders `path` from that field instead of the hard-coded empty ArrayLit, and the child node's defects must be hoisted into the ROOT object's `defects` array (the engine collects one flat defect list per root object; the child value itself is not wrapped). Filter the constraint set per node the way the engine does: only constraints whose required property tree is satisfiable — port `canEvaluateForTree` (graphExtension.pure:377) against the node's own tree + the mapping's bindings, so a constraint that reads unmapped/unfetched data is EXCLUDED rather than silently true.
(b) UNEVALUABLE-CONSTRAINT DEFECT. To match this particular golden you must also emit a defect (id, level, ruleDefinerPath, message 'Unable to evaluate constraint [<id>]: data not available - check your mappings') when the constraint's predicate cannot be lowered — for `duplicateEmployee` legend-lite would hit the nested-join wall at GraphEmission.java:397-401 ('mapped through the class's own join slots'). That converts a resolver wall into a data defect, which is the engine's Java-binding semantics but is dangerous as a blanket rule.
Recommendation: implement (a); treat (b) as scoped strictly to CHECKED-envelope constraint predicates and nothing else, or ledger this test and note it pins a bug the engine itself has a toFix comment for.

**How legend-engine does it** — /Users/neemsandv/legend/legend-engine/legend-engine-xts-java/legend-engine-xt-javaPlatformBinding-pure/src/main/resources/core_java_platform_binding/legendJavaPlatformBinding/shared/constraints.pure:246-300 — `createConstraintCheckingForTree_recurse` walks EVERY complex sub-tree of the graph fetch tree, generates the child's constraint calls, and wraps each returned defect with `prefixDefectPath($param, $node)` where `$node` is `newRelativePathNode(j_string($prop.name), [index])` — that is exactly the `path=[{"propertyName":"firm","index":null}]` in the golden. Line 249 filters constraints by `canEvaluateForTree` (definition at /Users/neemsandv/legend/legend-engine/legend-engine-core/.../core/pure/graphFetch/graphExtension.pure:377-392). Lines 171-179 are the catch arms that produce 'Unable to evaluate constraint [id]: data not available - check your mappings' when the constraint body throws (NPE = property not populated). Tree augmentation with constraint-required MAPPED properties happens in /Users/neemsandv/legend/legend-engine/legend-engine-core/.../core/pure/graphFetch/graphFetch_routing.pure:380-398 (`ensureConstraintsRequirementsForMappedProperties` / `requiredPropertiesAreMapped`).

**Risk** — (b) is the trap: a blanket 'constraint failed to lower ⇒ emit a defect' rule would mask genuine resolver walls as data-quality defects across the whole checked family — exactly the 'wrong rows instead of a loud wall' failure the tenets forbid. Scope it to the checked-constraint predicate lowering only, and keep the wall text inside the defect message so the cause stays visible. (a) changes the defects array of every nested-class checked test, so re-run the whole graphFetchChecked family; tests that currently pass with `defects: []` because nested constraints were invisible may start emitting defects (correctly).

**Also unblocks** — Any other checked graph-fetch test whose expected defects sit on a nested class or carry a non-empty `path` (the M2M dataQuality/constraints goldens in core/store/m2m/tests/legend/constraints.pure use the same path-prefixed defect shape).

**Falsifier** — Add a nested-class constraint that is UNAMBIGUOUSLY violated by fetched, mapped data (e.g. a Firm constraint over `legalName`, which IS in the tree) and run graphFetchChecked. If a defect with `path=[{propertyName:firm,...}]` appears, then nested constraints are being evaluated somewhere I did not find and the 'root-only' claim is wrong.

<details><summary>Evidence read (8 citations)</summary>

- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/resolver/StoreResolver.java:2584 — `checkedEnvelope = sz.source() instanceof TypedGraphFetch g2 && g2.checked();`
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/resolver/StoreResolver.java:2958 — the ONLY call passing `checkedEnvelope`, on the root node build
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/resolver/GraphEmission.java:215-221 — the 9-arg overload forwards `checked=false`
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/resolver/GraphEmission.java:461 — `return checked ? withChecked(node, ...) : node;` (the only withChecked call site in the file)
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/resolver/GraphEmission.java:1392-1394 — `em.buildGraphNode(target, childRel, slotPrefixes, childStripped, childVar, node.children(), context, toMany, childInfo)` — the 9-arg (unchecked) form for every class child
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/lowering/CheckedEnvelope.java:51-53 — `new SqlExpr.StringLit("path"), SqlExpr.Call.of(SqlFn.TO_VARIANT, new SqlExpr.ArrayLit(List.of()))` — path is unconditionally []
- /Users/neemsandv/legend/legend-engine/.../core_relational/relational/tests/modelWithConstraints.pure:24-30 — Firm's two constraints, incl. `duplicateEmployee: ($this.type->isNotEmpty() && ($this.type == FirmType.CORPORATION)) || $this.employees->isDistinct(#{Person{firstName,lastName}}#)`
- /Users/neemsandv/legend/legend-engine/.../core_relational/relational/graphFetch/tests/testSimpleRelationalGraphFetch.pure:1337-1343 — the `// toFix: after fixing isDistinct related bug` comment above the assertion, i.e. the golden pins current engine behaviour, not intended behaviour

</details>

---

## `testCrossStoreGraphFetchWithRelationalDatePropagationForMilestonedPropertyConstraint`

| | |
|---|---|
| family | `graphFetch/tests` |
| sweep status | SHAPE |
| **verdict** | **MISSING FEATURE** |
| effort | XL |
| confidence | high |

**Root cause**

The test body is: `let grammar = '<big concatenated Legend grammar>'; let elements = meta::legend::compileLegendGrammar($grammar); let function = $elements->filter(e|$e->instanceOf(FunctionDefinition))->at(1)->cast(@ConcreteFunctionDefinition<{String[1]->String[1]}>); assertEquals($expected, meta::legend::executeLegendQuery($function, ^Pair<String,String>(first='var_1',second='{"firmId": "1"}'), ...))`. In legend-lite the let-binding path calls `EngineTestExecutor.clgArm` (EngineTestExecutor.java:390). clgArm's unwrap loop only peels `cast`/`toOne`/`at` (lines 800-812); it never sees a `filter` step, so for the `elements` let the wrapper chain is empty, `idx` stays at its default 0, and clgArm returns `new LambdaFunction(List.of(), fns.get(0).body())` (line 836) — i.e. the body of the FIRST function definition in the grammar, `test::function::getFirmXstoreFunc(collection: FirmOutput[*])`, wrapped as a ZERO-ARG lambda that discards the `collection` parameter. When the assert later evaluates `$function` (which substitutes to that lambda), Typer's zero-arg-lambda arm types the body `$collection->graphFetch(...)` and `Typer.java:165` throws `unbound variable '$collection'`. That is the observed wall. Behind that wall the feature is genuinely absent in three further layers: (a) clgArm registers NO element from the grammar into the ModelContext (it returns only a body), so the grammar's classes/mappings/runtimes/bindings do not exist for resolution; (b) even with the right index (`finalQuery(var_1: String[1])`) the zero-arg wrap drops the parameter and `ElqSplice.varPairs` (ElqSplice.java:162-186) only accepts `pair(...)` applied-function lists, not the `^Pair<String,String>(...)` NewInstance this test passes, so `var_1` could not bind either; (c) the query itself needs `getRuntimeWithModelQueryConnection` + `mergeRuntimes` + `ModelChainConnection`-driven XStore resolution with milestoned date propagation into an M2M property (`firmTarget: $src.firm(%2023-10-12)`) — `grep -rn 'mergeRuntimes|getRuntimeWithModelQueryConnection' core/src` returns nothing at all in the whole tree.

**Fix**

Do not chase this test as written; it is an XL feature stack. The honest staged fix, in order:
1. (XS, do it anyway) Make clgArm's failure LOUD instead of silently binding the wrong function. In `EngineTestExecutor.clgArm` (EngineTestExecutor.java:796-838), when the unwrap loop stops on a call it does not recognise, return `rhs` as today, but when it DOES reach a compileLegendGrammar call, refuse to wrap a FunctionDefinition that declares parameters: if `fns.get(idx).signature().parameters()` is non-empty, return an `unsupported("compileLegendGrammar: selected function declares parameters; the grammar payload surface binds only zero-arg bodies")` marker rather than a zero-arg LambdaFunction. That turns 'unbound variable $collection' into a message that names the real gap.
2. (S) Teach the unwrap loop the corpus idiom: accept a `filter(<src>, lambda)` step whose lambda body is `instanceOf($e, FunctionDefinition)` and treat it as identity over the FunctionDefinition list (the list clgArm already builds is exactly that filtered list), so the `->at(1)` index reaches `idx`.
3. (L) Make compileLegendGrammar real: parse the payload with `com.legend.parser.ElementParser.parse(src, Dialect.LEGEND_ENGINE)` (already done at line 826) and REGISTER all resulting elements into an overlay ModelContext that the rest of the statement stream resolves through, instead of throwing everything but one body away. Keep the selected element as a first-class function handle (name + parameters + body), not a lambda, so `executeLegendQuery` can bind its declared parameters.
4. (M) Extend `ElqSplice.varPairs` to accept a `^Pair(first=..., second=...)` NewInstance (and a bare single pair) as well as `pair(...)`, and bind the selected function's DECLARED parameters via the existing `coerce` path.
5. (XL) Only then does the query itself become reachable, and it needs surfaces that do not exist anywhere in core/src: `meta::core::runtime::getRuntimeWithModelQueryConnection(Class, Binding, String)`, `meta::core::runtime::mergeRuntimes`, external-format JSON Binding internalize as a ModelStore source, and milestoned-date propagation through an M2M property mapping (`firmTarget: $src.firm(%2023-10-12)`) into a business-temporal relational class with a class constraint. Ledger this test behind that stack.

**How legend-engine does it** — /Users/neemsandv/legend/legend-engine/legend-engine-core/legend-engine-core-pure/legend-engine-pure-code-compiled-core/src/main/resources/core/legend/tools/devUtils.pure:18-23 — `meta::legend::compileLegendGrammar(grammar:String[1]): PackageableElement[*]` reflectively invokes `meta::legend::compile_String_1__PackageableElement_MANY_`, i.e. it COMPILES the whole grammar into the model and returns every element; devUtils.pure:34-39 — `executeLegendQuery(f: FunctionDefinition<Any>[1], vars: Pair<String,Any>[*], exeCtx, extensions)` executes a real FunctionDefinition with its declared parameters bound from the pairs. The runtime the grammar's finalQuery builds is engine-defined at /Users/neemsandv/legend/legend-engine/legend-engine-core/legend-engine-core-pure/legend-engine-pure-code-compiled-core/src/main/resources/core/pure/runtime/runtimeExtension.pure:123-127 (getRuntimeWithModelQueryConnection with a Binding → ModelQueryConnection whose instancesProvider internalizes the JSON) and :145-150 (mergeRuntimes concatenates connectionStores).

**Risk** — Step 1 changes a SHAPE detail string only. Step 3 is the real risk: an overlay ModelContext built from an embedded grammar can shadow same-named elements from the surrounding module (the payload uses generic names like test::model::Firm), so it must be a scoped overlay for the statements that read the binding, not a global merge. Tenet-2 trap: it is tempting to special-case this test in the harness by hand-binding $collection or by hard-coding the index — do not; compileLegendGrammar is a platform surface (the engine implements it in devUtils.pure), so the parse+register belongs on the platform side of the harness seam, and anything it cannot do must wall by name.

**Also unblocks** — testCrossStoreGraphFetchWithRelationalDatePropagationForMilestonedPropertyZeroToOne (identical shape). Steps 2-4 also apply to every other corpus test built on the compileLegendGrammar payload idiom, e.g. modelToModelToRelational/milestoned/milestonedSourceToMilestonedTargetProperty.pure and milestonedSourceToNonMilestonedTargetProperty.pure, which use the same `->filter(instanceOf(FunctionDefinition))->at(n)` selector.

**Falsifier** — Run the test with LL_TMP_DEBUG=1 and print the value clgArm binds for the `elements` let. If it is NOT a zero-arg LambdaFunction whose body is `$collection->graphFetch(...)` — e.g. if clgArm returned rhs untouched because foldString failed on the concatenated grammar literal — then the wall comes from somewhere else (most likely the grammar string never folding) and this diagnosis is wrong.

<details><summary>Evidence read (6 citations)</summary>

- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/harness/EngineTestExecutor.java:791-838 — clgArm: unwrap loop handles only cast/toOne/at (`n.equals("cast")`, `n.equals("at")`), everything else `break`s; on success returns `new LambdaFunction(List.of(), new ArrayList<>(fns.get((int) idx).body()))` with idx defaulting to 0
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/harness/EngineTestExecutor.java:389-391 — the let arm: `ValueSpecification rhs = clgArm(foldLiteralIf(subst(af.parameters().get(1), lets)), lets);` so every `let x = compileLegendGrammar(...)` goes through it
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/compiler/spec/Typer.java:165 — `case Variable v -> ... orElseThrow(() -> new TypeInferenceException("unbound variable '$" + v.name() + "'"))`, the exact message text in the sweep
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/harness/ElqSplice.java:162-186 — varPairs requires `AppliedFunction pair(...)` entries; a `^Pair(first=,second=)` NewInstance returns null, so the parameterized splice cannot bind var_1
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/test/java/com/legend/rcorpus/Runner.java:1307-1315 — the SHAPE stamp: `"no execute(|...) call [calls " + ns + "] — wall: " + attempted.wall()`, matching the brief's detail verbatim
- /Users/neemsandv/legend/legend-engine/.../core_relational/relational/graphFetch/tests/testCrossStoreGraphFetchMilestoning.pure:345-352 — the grammar's FIRST function is `test::function::getFirmXstoreFunc(collection: test::model::FirmOutput[*])` whose body is `$collection->graphFetch(...)`; the test wants index 1 (`test::function::finalQuery(var_1: String[1])`)

</details>

---

## `testCrossStoreGraphFetchWithRelationalDatePropagationForMilestonedPropertyZeroToOne`

| | |
|---|---|
| family | `graphFetch/tests` |
| sweep status | SHAPE |
| **verdict** | **MISSING FEATURE** |
| effort | XL |
| confidence | high |

**Root cause**

Byte-for-byte the same mechanism as the Constraint variant above: the body is `let elements = meta::legend::compileLegendGrammar($grammar); let function = $elements->filter(e|$e->instanceOf(FunctionDefinition))->at(1)->cast(@ConcreteFunctionDefinition<{String[1]->String[1]}>); assertEquals($expected, meta::legend::executeLegendQuery($function, ^Pair<String,String>(...), ...))`, and the embedded grammar again declares `test::function::getFirmXstoreFunc(collection: test::model::FirmOutput[*])` FIRST. clgArm ignores the `filter` step, keeps idx=0, and returns that helper's body as a zero-arg lambda, so `$collection` has no binder and Typer.java:165 throws `unbound variable '$collection'`. The only difference from the sibling test is the payload model (a `[0..1]` milestoned property instead of a class constraint), which sits entirely behind the same wall.

**Fix**

Identical to the Constraint variant — steps 1-5 there. There is nothing extra to do for this test: it is unblocked or blocked in lockstep with its sibling. When step 5 is scheduled, note that this payload additionally needs a `[0..1]` milestoned property target (`firmTarget: test::model::FirmTarget[0..1]` fed by `$src.firm(%2023-10-12)`), i.e. the zero-or-one arm of the same date-propagation path.

**How legend-engine does it** — /Users/neemsandv/legend/legend-engine/legend-engine-core/legend-engine-core-pure/legend-engine-pure-code-compiled-core/src/main/resources/core/legend/tools/devUtils.pure:18-23 and :34-39 — compileLegendGrammar returns the full PackageableElement[*] and executeLegendQuery runs a parameterized FunctionDefinition; /Users/neemsandv/legend/legend-engine/legend-engine-core/legend-engine-core-pure/legend-engine-pure-code-compiled-core/src/main/resources/core/pure/runtime/runtimeExtension.pure:123-150 — getRuntimeWithModelQueryConnection(Binding) and mergeRuntimes, neither of which exists anywhere in legend-lite's core/src.

**Risk** — Same as the sibling. Do not 'fix' the two of them by teaching the harness to skip the filter for these two files specifically — the selector idiom is corpus-wide.

**Also unblocks** — testCrossStoreGraphFetchWithRelationalDatePropagationForMilestonedPropertyConstraint

**Falsifier** — Same probe as the sibling: dump the value clgArm binds to `elements`. If it is anything other than the zero-arg lambda holding `$collection->graphFetch(...)`, this diagnosis is wrong.

<details><summary>Evidence read (4 citations)</summary>

- /Users/neemsandv/legend/legend-engine/.../core_relational/relational/graphFetch/tests/testCrossStoreGraphFetchMilestoning.pure:577,591,605 — inside the payload of the test starting at line 495 the function order is getFirmXstoreFunc, finalQuery, getFirmXstoreFunc_serialized (same as the sibling test)
- /Users/neemsandv/legend/legend-engine/.../core_relational/relational/graphFetch/tests/testCrossStoreGraphFetchMilestoning.pure:721-724 — the selector `$elements->filter(e|$e->instanceOf(FunctionDefinition))->at(1)->cast(...)` and `executeLegendQuery($function, ^Pair<String,String>(first='var_1',second='{"firmId": "1"}'), ...)`
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/harness/EngineTestExecutor.java:796-838 — clgArm: `while (cur instanceof AppliedFunction af ...)` handles cast/toOne/at only; `long idx = 0` default; returns `new LambdaFunction(List.of(), fns.get((int) idx).body())`
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/compiler/spec/Typer.java:165 — the `unbound variable '$…'` throw site

</details>

---

## `testCrossStoreWithCSVDataSource`

| | |
|---|---|
| family | `graphFetch/tests` |
| sweep status | SHAPE |
| **verdict** | **TESTS ENGINE INTERNALS** |
| effort | XL |
| confidence | high |

**Root cause**

Two independent blockers, and the reported wall is the shallower of the two.
(1) The observed message 'from() argument 2 must be a mapping or runtime reference, got TypedCopyInstance' comes from FromChecker.java:74-76. The query is `->from($mapping, $runtime)` where `$runtime = ^$dbRuntimeFromCSv(connectionStores = ...)` and `$dbRuntimeFromCSv = ^EngineRuntime(...)`. `NewChecker.checkCopy` types a `^$var(...)` copy with the RECEIVER's class type (NewChecker.java:32-37,63), i.e. `meta::core::runtime::EngineRuntime`. FromChecker's instance-runtime arm admits an argument only when `ct.fqn().equals("meta::core::runtime::Runtime")` — an EXACT FQN compare (FromChecker.java:53-58). legend-lite itself declares `EngineRuntime extends Runtime` (Pure.java:234), so this is a plain subtype bug: any `^EngineRuntime(...)` or copy thereof is rejected. That is a REAL_DEFECT and it is one line.
(2) Behind it, the test's FIRST assert is `assertEquals(7, $plan.rootExecutionNode.executionNodes->at(0)->cast(@StoreMappingGlobalGraphFetchExecutionNode).children->at(0).localGraphFetchExecutionNode.executionNodes->at(0)->cast(@SQLExecutionNode).connection->cast(@RelationalDatabaseConnection).datasourceSpecification->cast(@LocalH2DatasourceSpecification).testDataSetupSqls->size())`. That is a white-box read of the engine's execution-plan object graph and of the statement granularity of its DDL generator. legend-lite's plan-handle walk understands exactly four properties — `rootExecutionNode`, `executionNodes`, `sqlQuery`, `functionParameters` (StatementExecutor.java:1836-1843) — and has no plan node carrying a connection, a datasourceSpecification, or generated setup SQLs. Separately, `TypedFrom.collectSqlSetups` only harvests LITERAL `testDataSetupSqls` blobs off a `^LocalH2DatasourceSpecification` (TypedFrom.java:350-358); it never converts `testDataSetupCsv`, even though legend-lite already owns a faithful port of the engine's generator in `Ddl.setUpDataSqlsText` (Ddl.java:106-165). So the count 7 is reachable in principle (db2 has 2 default-schema tables: drop+create schema = 2, drop+create per table = 4, one CSV insert = 7 — matching Ddl.setUpDataSqlsText's own statement order) but there is no plan object to hang it on.

**Fix**

Split the work.
(a) FIX NOW (XS, independent of this test): in `FromChecker.check`, replace the exact compare
    `ct.fqn().equals("meta::core::runtime::Runtime")`
with
    `t.model().isSubtype(ct.fqn(), "meta::core::runtime::Runtime")`
(FromChecker.java:55-58; `Typer.model()` is at Typer.java:102, `ModelContext.isSubtype` at ModelContext.java:223). Nothing else in the arm needs to change — chainMappings/jsonSources/sqlSetups/connectionName collection already walks the instance generically.
(b) OPTIONAL, and worth doing on its own merits: give `TypedFrom.collectSqlSetups` a `testDataSetupCsv` arm that resolves the store Database from the enclosing `^ConnectionStore(element = <db ref>)` and calls the existing `com.legend.exec.Ddl.setUpDataSqlsText(csv, db)`, so a CSV-seeded inline runtime actually seeds. Without this the test's SECOND assert (the row JSON) cannot pass either.
(c) DO NOT build for this test: the `assertEquals(7, $plan...testDataSetupSqls->size())` assert requires materialising the engine's ExecutionPlan protocol as a property-navigable object graph (StoreMappingGlobalGraphFetchExecutionNode / localGraphFetchExecutionNode / connection / datasourceSpecification) purely so a DDL statement COUNT can be read off it. That is a white-box assertion on legend-engine's Pure-implemented plan generator; ledger it.

**How legend-engine does it** — /Users/neemsandv/legend/legend-engine/legend-engine-core/legend-engine-core-pure/legend-engine-pure-code-compiled-core/src/main/resources/core/pure/runtime/runtimeExtension.pure:20 — `Class meta::core::runtime::EngineRuntime extends Runtime`, so the engine's from() accepts it by subsumption. The 7 setup SQLs come from /Users/neemsandv/legend/legend-engine/.../core_relational/relational/sqlQueryToString/DDL/toDDL.pure:155-195: `schemaAndTableSetup` emits dropSchemaStatement+createSchemaStatement per schema and dropTableStatement+createTableStatement per table, then `setUpDataSQLs` concatenates `loadCsvDataToDbTable`; the plan stamps the result onto the connection at /Users/neemsandv/legend/legend-engine/.../core_relational/relational/contract/storeContract.pure:116-131 (`^$t(testDataSetupSqls = if($t.testDataSetupCsv->isEmpty(), ..., | $t.testDataSetupCsv->toOne()->setUpDataSQLs(...)))`).

**Risk** — (a) widens from()'s accepted argument set: any Runtime subtype instance now takes the 'harness-owned connection content' path, which silently EMPTIES the runtime slot (FromChecker.java:38-47 comment). A runtime-only `from(src, ^EngineRuntime(...))` therefore walls later with 'class query requires an execution context' rather than at from() — a different, still-loud wall, but re-run the sweep to confirm nothing that previously walled early now produces rows from the wrong ambient connection. (b) must not seed when the store element cannot be resolved to a Database — silently seeding the wrong table would be worse than not seeding.

**Also unblocks** — meta::relational::graphFetch::tests::union::rootLevel::testSpecialUnion_m2m2r (testUnionRootLevel_relational.pure:687-731) passes a bare `^EngineRuntime(...)` straight into `->from($mapping, $runtime)` and hits the identical FromChecker wall (as a TypedNewInstance); it also needs the testDataSetupCsv arm (b). meta::external::store::relational::modelJoins::test::getNoStoreRuntime (testModelJoinsToRelationalJoins.pure:63) builds ^EngineRuntime too.

**Falsifier** — Apply only change (a) and re-run. If the test's wall does NOT move off FromChecker (i.e. it still reports 'from() argument 2 …'), then the copy's static type is not a Runtime subtype in this module (e.g. EngineRuntime is not being resolved to meta::core::runtime::EngineRuntime under the test's imports) and the subtype diagnosis is wrong.

<details><summary>Evidence read (9 citations)</summary>

- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/compiler/spec/FromChecker.java:53-76 — `if (a.args().get(i).info().type() instanceof Type.ClassType ct && ct.fqn().equals("meta::core::runtime::Runtime")) {...} throw new TypeInferenceException("from() argument " + i + " must be a mapping or runtime reference, got " + a.args().get(i).getClass().getSimpleName());` — the exact sweep message
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/compiler/spec/NewChecker.java:29-37,63 — `^$var(...)` takes the receiver's ClassType and yields a TypedCopyInstance with that same type
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/builtin/Pure.java:234 — `native Class meta::core::runtime::EngineRuntime extends meta::core::runtime::Runtime { mappings: ...[*]; }` — legend-lite already models the subtype relation it then refuses to use
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/compiler/element/ModelContext.java:216-241 — `default boolean isSubtype(String childFqn, String parentFqn)` walks superClassFqns; the ready-made replacement for the exact compare
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/StatementExecutor.java:1834-1844 — the PlanNode property vocabulary: rootExecutionNode / executionNodes / sqlQuery / functionParameters, `default -> null`
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/compiler/spec/typed/TypedFrom.java:348-358 — collectSqlSetups reads only `testDataSetupSqls` off LocalH2DatasourceSpecification; no testDataSetupCsv arm
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/exec/Ddl.java:106-165 — `setUpDataSqlsText(String data, DatabaseDefinition db)` already emits drop/create schema, drop/create per table, then one insert per CSV row
- /Users/neemsandv/legend/legend-engine/.../core_relational/relational/graphFetch/tests/testCrossStoreGraphFetch.pure:1013-1037 — `^EngineRuntime(...)` with testDataSetupCsv, then `let runtime = ^$dbRuntimeFromCSv(connectionStores = ...)` and `->from($mapping,$runtime)`
- /Users/neemsandv/legend/legend-engine/.../core_relational/relational/graphFetch/tests/testCrossStoreGraphFetch.pure:116-122 — db2 declares exactly two default-schema tables (productTable, synonymTable), which is what makes the engine's count 7

</details>

---

## `testMilestonedProperty`

| | |
|---|---|
| family | `graphFetch/tests` |
| sweep status | FAIL |
| **verdict** | **GOLDEN TEXT ONLY** |
| effort | XL |
| confidence | medium |

**Root cause**

The test has two asserts. The first, `assertJsonStringsEqual('[{"id":1,"product(2015-10-16)":[]},{"id":2,...}]', $result)`, PASSED — the harness returns on the first failing assert (EngineTestExecutor.java:895-905 scoreAssert → Outcome.Ran with the failure, and Runner.score maps the first failure to FAIL), and the reported failure text is the PLAN assert, so the rows and the milestoned qualifier serialize key are already correct. The failure is the second assert: `assertEquals('PureExp\n(\n  type = String\n  expression =  -> serialize(#{...}#)\n  (\n    StoreMappingGlobalGraphFetch ...', $plan)` where `$plan = executionPlan(...)->planToString(...)`. legend-lite's planToString K-arm has NO graph-fetch vocabulary at all: `StatementExecutor.planToString` unwraps the lambda, finds a getAll root, and falls straight through to `PlanText.single(...)` (StatementExecutor.java:626-638), which emits a single `Relational\n(\n  type = ...\n  resultColumns = [...]\n  sql = ...` block (PlanText.java:78-81). Nothing in com/legend/plan or StatementExecutor mentions graphFetch, serialize, PureExp, StoreMappingGlobalGraphFetch, RelationalGraphFetch, localTreeIndices or temp_table_node — `grep -rn 'graphFetch|SerializeGraph|PureExp' core/src/main/java/com/legend/StatementExecutor.java core/src/main/java/com/legend/plan/` returns nothing, and Pure.java:1616-1617 states outright that 'plan text is a PureExp node — a named wall at the K-arm until built'. The defect is that the wall is NOT taken: a serialize/graphFetch-rooted query silently renders as a single Relational node instead of walling, turning an honest SHAPE into a FAIL on text that was never going to match.

**Fix**

Do not build the graph-fetch plan-text renderer for this test; do make the wall honest.
(a) NOW (XS): in `StatementExecutor.planToString`, before the `PlanText.single` return (StatementExecutor.java:626-638), detect a GRAPH terminal — the same test StoreResolver uses at StoreResolver.java:2581-2585, i.e. the terminal typed node is a serialize whose source is a TypedGraphFetch (or, pre-resolution, the body's terminal chain contains a `serialize`/`graphFetch` native call) — and `throw new NotImplementedException("planToString: graph-fetch plans (PureExp/StoreMappingGlobalGraphFetch/RelationalGraphFetch nodes) are not rendered yet")`. PlanAsserts.planTextAssert (PlanAsserts.java:186-189) already catches NotImplementedException and scores SHAPE, so the test becomes an honest 'not built' instead of a FAIL on a plan shape legend-lite never claimed to emit. This also stops the same misleading Relational text leaking into every other graph-fetch plan golden.
(b) LATER (XL, only if the plan-text surface is ever prioritised): the renderer needs the whole engine node vocabulary — PureExp envelope with the serialize tree printed in engine spelling, StoreMappingGlobalGraphFetch with `PartialClass[impls=[...], propertiesWithParameters=[...]]`, a RelationalGraphFetch child per tree node with nodeIndex numbering, per-node SQL with `${temp_table_node_N}` parent splices and parent_key_gen columns, plus localTreeIndices/dependencyIndices. That is a subsystem, not a fix.

**How legend-engine does it** — /Users/neemsandv/legend/legend-engine/.../core_relational/relational/graphFetch/tests/testGraphFetchMilestoning.pure:92-160 — the engine's own rendering of this plan (the golden): a PureExp envelope around StoreMappingGlobalGraphFetch whose localGraphFetchExecutionNode is a RelationalGraphFetch tree with per-node SQL, `${temp_table_node_N}` parent splices, `k_businessDate` propagation columns, nodeIndex, localTreeIndices and dependencyIndices. legend-lite has no counterpart for any of it.

**Risk** — (a) reclassifies this test from FAIL to SHAPE — it does NOT make it pass, and anyone reading the scoreboard must understand that. Check first whether any currently-PASSING plan golden is a graph/serialize query that happens to match the single Relational text (unlikely, since the engine always wraps graph plans in PureExp, but the detection predicate must be exact: only wall when the terminal really is a graph serialize, not when a plain projection merely mentions serialize). Tenet-2 trap: do not make the harness skip this assert — the missing renderer is platform-side.

**Also unblocks** — Every other corpus test that compares a graph-fetch planToString golden gets the same honest wall from (a) — e.g. the neighbouring milestoning plan goldens in testGraphFetchMilestoning.pure and the plan asserts in testCrossStoreGraphFetch.pure.

**Falsifier** — Print what legend-lite's planToString returns for this query. If it starts with 'PureExp' (i.e. some graph-aware renderer exists that I did not find) then the 'renders a single Relational node' claim is wrong and the diff is a detail-level text divergence instead. Confidence is medium only on that point — the direction (plan text differs, rows are right) is certain from the assert ordering.

<details><summary>Evidence read (6 citations)</summary>

- /Users/neemsandv/legend/legend-engine/.../core_relational/relational/graphFetch/tests/testGraphFetchMilestoning.pure:70-163 — the test body: assertJsonStringsEqual on rows FIRST, then the full planToString golden (PureExp → StoreMappingGlobalGraphFetch → RelationalGraphFetch with nodeIndex/temp_table_node splices/localTreeIndices)
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/StatementExecutor.java:606-638 — planToString: `String rootClass = rootGetAllClass(lam.body()); if (rootClass == null) throw ...; ... return new ExecutionResult.Scalar(com.legend.plan.PlanText.single(...))` — no graph terminal check anywhere on the path
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/plan/PlanText.java:16-24,78-81 — the class doc ('SINGLE-RELATIONAL plans … Anything beyond the single-node vocabulary … is a named wall') and the actual emitter `return "Relational\n(\n" + typeBlock(...) + "  resultColumns = [" + cols + "]\n"`
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/builtin/Pure.java:1616-1617 — 'pure-only plan shapes (no store) … their plan text is a PureExp node — a named wall at the K-arm until built'
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/harness/PlanAsserts.java:163-190 — planTextAssert's failure spelling `"assertEquals: expected " + pe.render() + ", got " + pa.render()`, matching the brief's detail, and its catch arms that would have made this SHAPE had the renderer walled
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/test/java/com/legend/rcorpus/Runner.java:1450-1453 — score(): the first non-empty failure becomes the FAIL detail, so the earlier row assert must have passed

</details>

---

## `testBusinessDatePropagationInColFunction_asQueryParam`

| | |
|---|---|
| family | `milestoning/tests` |
| sweep status | SHAPE |
| **verdict** | **REAL DEFECT** |
| effort | M |
| confidence | high |

**Root cause**

The test opens with `{productBusDate:Date[1] | Order.all()->project(...)}->cast(@FunctionDefinition<Any>)`. `cast` is declared in legend-lite as `cast<T|m>(source:meta::pure::metamodel::type::Any[m], type:T[1]):T[m]` (Pure.java:1153), i.e. parameter 0 has type `Type.ClassType(meta::pure::metamodel::type::Any)`. The receiver is a `LambdaFunction`, which `Typer.deferredArg` classifies as a DEFERRED argument (Typer.java:1652-1653), so `checkGeneric` routes to `checkWithDeferred` (Typer.java:1434-1436). `checkWithDeferred` prefilters candidates with `deferredShapesMatch`, whose `LambdaFunction` arm accepts ONLY a function-typed parameter or a bare `Type.TypeVar` (Typer.java:1691-1693). `Any` is neither, so the single `cast/2` candidate is filtered out and the `arity.isEmpty()` branch throws the exact observed message (Typer.java:1511-1518). The gate is wrong: in real Pure a lambda IS an `Any`, so `cast`'s `Any[m]` source legitimately accepts a LambdaFunction. Two sibling guards encode the same wrong rule and would also reject the candidate even if the first gate were opened: `lambdaAritiesFit` returns false for a lambda against a non-function non-TypeVar parameter (Typer.java:1843-1848), and `bindDeferredAndBuild` only takes the standalone-synthesis path for `Type.TypeVar` params, otherwise calling `typeLambda(lam, Any, ...)` which requires a function type (Typer.java:1573-1590, 1902-1907).

**Fix**

Three coordinated edits in core/src/main/java/com/legend/compiler/spec/Typer.java, all expressing one rule: "a SELF-TYPABLE lambda also matches a parameter typed as the top type `Any`, and types by standalone synthesis there". (1) Add a private helper `private static boolean isTopAny(Type t) { return t instanceof Type.ClassType ct && com.legend.builtin.Pure.ANY.qualifiedName().equals(ct.fqn()); }` (use whatever constant Pure exposes for meta::pure::metamodel::type::Any; do not hard-code the string twice). (2) In `deferredShapesMatch` (line 1691) change the LambdaFunction arm to `isFunctionTyped(t) || ((t instanceof Type.TypeVar || isTopAny(t)) && selfTypable(lf))`. (3) In `lambdaAritiesFit` (line 1837) extend the skip to `if (pt instanceof Type.TypeVar || isTopAny(pt)) { continue; }` — an Any param imposes no arity constraint. (4) In `bindDeferredAndBuild` (line 1573) change the standalone-synthesis guard from `chosen.parameters().get(i).type() instanceof Type.TypeVar` to `... instanceof Type.TypeVar || isTopAny(...)`, so the lambda is `synth`'d standalone and unified against `Any` (which trivially succeeds) instead of being pushed through `typeLambda`. Do NOT widen the gate to every non-function type — only the top type; a lambda against, say, `String[1]` must stay loud.

**How legend-engine does it** — legend-pure declares the signature that makes a lambda a legal `cast` source: /Users/neemsandv/legend/legend-pure/legend-pure-core/legend-pure-m3-core/src/main/resources/platform/pure/essential/lang/cast/cast.pure:19 — `native function <<PCT.function>> meta::pure::functions::lang::cast<T|m>(source:Any[m], object:T[1]):T[m];`. Any value, including a LambdaFunction, inhabits `Any`.

**Risk** — Widening the prefilter admits lambdas to every `Any`-typed parameter in the catalog (assertEquals, toString, print, …), which enlarges candidate sets and can change which overload wins for calls that previously had exactly one survivor. Mitigate by keeping the `selfTypable(lf)` conjunct — a lambda with unannotated parameters still cannot type standalone and stays rejected. Second, and more important for scoring honesty: opening this gate only gets past the FIRST wall. The test then feeds the cast result into `executionPlan(...)` and expects a `FunctionParametersValidationNode` plus `'${productBusDate}'` substituted into the milestoning join predicate produced from inside a `col([o|$o.product($productBusDate).name], 'prodName')` lambda. `StatementExecutor.sequencePlan` (line 825-888) already builds parameterized plans with `PlanText.functionParametersNode`, so the machinery exists, but the plan path must also see THROUGH the `TypedCast` wrapper to the underlying lambda. Expect a second, different wall; do not treat the gate fix as a full pass. Tenet-2 trap: do not make the harness pre-strip the `->cast(@FunctionDefinition<Any>)` — the cast is the platform's to type.

**Falsifier** — If `Type.ClassType` is not what `cast`'s parameter 0 classifies to (e.g. if `Any` is special-cased into a TypeVar-like or a GenericType somewhere in TypeClassifier/InferenceKernel), the whole diagnosis is wrong. Cheapest check: in a debugger or a one-line temporary print, dump `Pure.CAST__ANY_m__T_1`'s compiled parameter-0 `Type` and confirm it is `Type.ClassType("meta::pure::metamodel::type::Any")` and that `isFunctionTyped` returns false for it.

<details><summary>Evidence read (8 citations)</summary>

- core/src/main/java/com/legend/builtin/Pure.java:1153 — `CAST__ANY_m__T_1 = signature("native function meta::pure::functions::lang::cast<T|m>(source:meta::pure::metamodel::type::Any[m], type:T[1]):T[m];")` — param 0 is Any, not a function type
- core/src/main/java/com/legend/compiler/spec/Typer.java:1652-1653 — `deferredArg` returns true for `p instanceof LambdaFunction`
- core/src/main/java/com/legend/compiler/spec/Typer.java:1691-1693 — `case LambdaFunction lf -> isFunctionTyped(t) || (t instanceof Type.TypeVar && selfTypable(lf));` — no arm for the top type Any
- core/src/main/java/com/legend/compiler/spec/Typer.java:1511-1518 — the `arity.isEmpty()` throw producing exactly "no overload of 'cast' matches 2 argument(s) of these shapes — candidates: [...cast/2]"
- core/src/main/java/com/legend/compiler/spec/Typer.java:1843-1848 — `lambdaAritiesFit`: "a deferred LAMBDA against a non-function, non-variable param can never type" → return false
- core/src/main/java/com/legend/compiler/spec/Typer.java:1573-1590 — `bindDeferredAndBuild` only synthesizes standalone when the param is a `Type.TypeVar`, otherwise calls `typeLambda(lam, paramType, ...)`
- core/src/main/java/com/legend/compiler/spec/Typer.java:1902-1907 — `typeLambda` starts with `extractFunctionType(functionParamType)`, which cannot yield a function type for `Any`
- core/src/main/java/com/legend/compiler/spec/CastChecker.java:23-26 — `CastChecker.check` delegates to `t.checkGeneric(af, env)`, so the wall is reached through the ordinary generic path

</details>

---

## `testExecutionPlanForQueryWithVariableRundateWithinLambda`

| | |
|---|---|
| family | `milestoning/tests` |
| sweep status | FAIL |
| **verdict** | **GOLDEN TEXT ONLY** |
| effort | M |
| confidence | medium |

**Root cause**

Every select-list column, the type block, the resultColumns list, the Allocation/Constant nodes and the `'${date}'` parameterization are byte-identical to the golden. The ONLY divergence is join ORDER in the from-tree, which changes the alias numbers: expected `classificationType` reads `productclassificationtable_0` and the filter's `classification($date)` navigation reads `productclassificationtable_1`; legend-lite emits them swapped, and its first join in the from-tree is `ProductClassificationTable as "productclassificationtable_0"` (the filter's) whereas the engine's first join is `StockProductTable as "stockproducttable_0"` (a select-column join). Aliases are not chosen at build time — `EngineStyleH2.planSource` assigns them by walking the from-tree left-to-right and taking the next index within the lowercased-table-name group (EngineStyleH2.java:281-347), so the alias diff is a pure consequence of join ORDER. That order comes from the ClassSource pipeline's node order: `Pipelines.materialize` walks the pipeline and preserves it, cancelling only undemanded slots (Pipelines.java:341-378, 404-408); `materializeRoot` materializes navigate steps and join slots off that one pipeline (StoreResolver.java:1755-1788). In milestoningmap, Product declares `classification : [db]@Product_Classification` (a navigation, position 4) BEFORE `stockProductName` (position 9) and `classificationType` (position 10), so declaration-order pipeline construction puts the filter's navigation join ahead of the two column-producing slot joins. legend-engine orders by PROCESSING, not declaration: the set implementation's column-producing property mappings are turned into select columns (and their joins) first, and the query's `->filter` is processed afterwards, appending its navigation joins behind them.

**Fix**

Do NOT patch this test in isolation. The change is a single ordering rule in the resolver: when building the ClassSource pipeline for a class ROOT whose result is the class itself, emit the join slots that produce SELECT columns (value/leaf property mappings such as `stockProductName`, `classificationType`) ahead of the navigate steps that only the query's filter/sort demanded. Concretely: in `StoreResolver.materializeRoot` (core/src/main/java/com/legend/resolver/StoreResolver.java:1755-1788), the pipeline handed to `Pipelines.materialize` must be re-sequenced so demanded `TypedJoinSlot` nodes reached from `cs.bindings()` values precede demanded `TypedNavigate` steps that are reached only from `filterPaths`; equivalently, split the demand at StoreResolver.java:2811-2812 into projection-first order (`paths = projectionPaths ∪ filterPaths`) and make pipeline construction honour that order rather than mapping-declaration order. Before touching it, run the probe named in the falsifier and diff a full sweep — alias numbering is asserted by a large fraction of the relational goldens, so any reordering must be validated corpus-wide, not on this one test.

**How legend-engine does it** — The engine's ordering is a consequence of its processing pipeline: `meta::relational::functions::pureToSqlQuery::processFilter` is a SEPARATE registered processor applied to the already-built set-implementation select — /Users/neemsandv/legend/legend-engine/.../core_relational/relational/pureToSQLQuery/pureToSQLQuery.pure:5054 (declaration) and :9982 (the `PureFunctionToRelationalFunctionPair` binding `filter` to it). The set implementation's columns, and their joins, exist before `processFilter` runs.

**Risk** — HIGH blast radius: alias numbering appears in essentially every SQL golden in the corpus. A reordering that fixes this test can silently break dozens that currently pass. It must be landed behind a full-sweep diff. Rows are unaffected either way (identical joins, identical predicates), so there is no correctness urgency. Tenet-2 trap: do not normalise alias numbers in the harness comparison — the engine's alias plan is platform-owned and `EngineStyleH2` already models it deliberately.

**Falsifier** — The `got` string in the sweep is truncated at 1427 characters, so I could only see the FIRST divergent join. I cannot distinguish "navigate steps sort before join slots" from "pipeline follows mapping declaration order" — both predict the observed prefix. Cheapest discriminating probe: re-run this one test with the full (untruncated) actual SQL captured, and look at whether `ProductExchangeTable` (the filter's second navigation, declared at mapping position 6) appears BEFORE `StockProductTable` (declared at position 9). If it does, the rule is declaration order; if `ProductExchangeTable` comes last, the rule is navs-before-slots. Also confirm the got SQL contains all five joins — if a join is missing entirely, this is not an ordering bug and the whole diagnosis is wrong.

<details><summary>Evidence read (6 citations)</summary>

- /Users/neemsandv/.claude/jobs/5671074c/tmp/burndown/failing.json — the full untruncated record for this test: expected and got differ on exactly one line (the `sql =` line); every other line of the 33-line plan matches
- core/src/main/java/com/legend/sql/dialect/EngineStyleH2.java:281-347 — "The engine groups aliases BY LOWERCASED TABLE NAME and numbers 0..n-1 within each group in encounter order"; `planSource` walks `Join` left then right (line 378-381)
- core/src/main/java/com/legend/resolver/Pipelines.java:341-378 — `materialize` walks the pipeline in place; core/src/main/java/com/legend/resolver/Pipelines.java:404-408 — an undemanded slot is dropped, a demanded one keeps its pipeline position
- core/src/main/java/com/legend/resolver/StoreResolver.java:1755-1788 — `materializeRoot` builds the whole root pipe from the single `cs.pipeline()`, so nav steps and join slots share one ordering
- core/src/main/java/com/legend/resolver/StoreResolver.java:2811-2812 — `Set<List<String>> paths = new LinkedHashSet<>(filterPaths); paths.addAll(projectionPaths);` — filter demand is registered before projection demand
- /Users/neemsandv/legend/legend-engine/.../core_relational/relational/milestoning/tests/businessDateMilestoningSetUp.pure:545-556 — Product's property mappings in declaration order: id, name, type, classification(@Product_Classification), referenceSystem, exchange, synonyms, orders, stockProductName(@Product_StockProduct > @StockProduct_Description), classificationType(@Product_Classification)

</details>

---

## `testModelJoinForNonRelationalConcepts`

| | |
|---|---|
| family | `modelJoins` |
| sweep status | SHAPE |
| **verdict** | **REAL DEFECT** |
| effort | M |
| confidence | high |

**Root cause**

Two stacked defects, both in legend-lite, both hit while type-checking `getNoStoreRuntime()`.

(1) legend-lite's native mirror of legend-pure's `meta::core::runtime::Runtime` is incomplete. Real pure declares three members on Runtime — `preprocessFunction`, `connectionStores`, `connectionByElement(...)` — but Pure.java:230 declares only `connectionStores`, and ENGINE_RUNTIME (Pure.java:234) merely adds `mappings`. `^EngineRuntime(connectionStores=…, preprocessFunction = …)` therefore reaches NewChecker.check, whose `t.model().findProperty(ni.className(), name).orElseThrow(...)` (NewChecker.java:83-94) raises the observed message. Note the property lookup runs BEFORE `t.synth(key.value(), env)` (NewChecker.java:95), so the VALUE has never been typed yet — which hides defect (2).

(2) The value `preprocessQueryDummy_FunctionDefinition_1__Runtime_1__FunctionDefinition_1_` is a BARE function pointer carrying the engine's signature mangle. `NameResolver.resolveNameMulti` (NameResolver.java:532-592) has wildcard, own-package and prelude tiers, and every tier tests membership in `knownFqns`, which holds only UNMANGLED element FQNs (NameResolver.java:284-292 — `known.add(el.qualifiedName())`). There is no demangling tier, so the name survives resolution bare and mangled. `Typer.classReference` then calls `functionCandidates(ref.fullPath())` (Typer.java:2248), which demangles to the bare base `preprocessQueryDummy` and calls `ctx.findFunction("preprocessQueryDummy")` (Typer.java:2148-2160); `FunctionCompiler.functionsAt` only extends the bare-name courtesy to `meta::pure::functions::*` / `meta::pure::tds` packages (FunctionCompiler.java:34-47), so a corpus user function never matches. The result is `ResolutionException("'preprocessQueryDummy_…_' is not a known class, mapping, runtime, connection, or database")` at Typer.java:2294 — the NEXT wall once (1) is fixed.

Everything downstream is already correct: this test's expected SQL is byte-identical to testJoinWithConstantDouble/String/Date/Inequalities, which all PASS today (they are absent from every unit brief and from the modelJoins SHAPE rows of docs/RELATIONAL_CORPUS.md:1260-1262); the only delta is `getNoStoreRuntime()` vs `getXStoreRuntime()`, and `planModel` reads nothing from the runtime argument except quoteIdentifiers/timeZone/mapper renames (StatementExecutor.java:1961-1975), so a store-less runtime is inert.

**Fix**

Two edits.

A. core/src/main/java/com/legend/builtin/Pure.java:230 — extend the native Runtime declaration to match legend-pure runtime.pure:17-22:
`public static final ClassDefinition RUNTIME = nativeClass("native Class meta::core::runtime::Runtime { preprocessFunction: meta::pure::metamodel::function::Function<{meta::pure::metamodel::function::FunctionDefinition<meta::pure::metamodel::type::Any>[1], meta::core::runtime::Runtime[1] -> meta::pure::metamodel::function::FunctionDefinition<meta::pure::metamodel::type::Any>[1]}>[0..1]; connectionStores: meta::core::runtime::ConnectionStore[*]; }");`
(Function-typed properties with a spelled signature already parse in this file — see DATABASE_CONNECTION's `sqlQueryPostProcessors` and POST_PROCESSOR_WITH_PARAMETER's `ConcreteFunctionDefinition<{->…}>` at Pure.java:466-467, and FUNCTION_DEFINITION exists at Pure.java:526. If the exact signature proves fussy, the already-proven weaker form `meta::pure::metamodel::function::Function<meta::pure::metamodel::type::Any>[0..1]` also unifies via InferenceKernel.java:140-145 — but prefer the faithful one.)
Do NOT add it to ENGINE_RUNTIME: real pure puts it on Runtime, and routing.pure:113 reads `$runtime.preprocessFunction` off a plain Runtime.

B. core/src/main/java/com/legend/compiler/NameResolver.java, in `resolveNameMulti` — add a LAST tier (after the prelude tier, before `return List.of(name)`): if `SignatureMangle.stripTail(name)` is non-null, re-run the wildcard and own-package membership tests against `pkg + "::" + base`, and on a hit return `pkg + "::" + name` (KEEP the mangled tail — Typer.functionCandidates/classReference already demangle an FQN and use the tail's arity + return-type name to pick the overload, Typer.java:2148-2160 and 2253-2259). Returning the tail-stripped FQN instead would throw away the overload disambiguator and is wrong.

No behaviour change is needed in planModel or the plan channel: the expected SQL is already produced for the sibling XStoreTradesMapping_withConstantDouble tests.

**How legend-engine does it** — /Users/neemsandv/legend/legend-pure/legend-pure-dsl/legend-pure-dsl-store/legend-pure-m2-dsl-store-pure/src/main/resources/platform_dsl_store/grammar/runtime.pure:19 declares preprocessFunction on Runtime; /Users/neemsandv/legend/legend-engine/legend-engine-core/legend-engine-core-pure/legend-engine-pure-code-compiled-core/src/main/resources/core/pure/executionPlan/executionPlan_generation.pure:147 applies it (`if($runtime.preprocessFunction->isEmpty(), | $f, | $runtime.preprocessFunction->at(0)->toOne()->eval($f, $runtime->at(0)))`), and core/pure/router/store/routing.pure:113 uses `$runtime.preprocessFunction->isNotEmpty()` to accept a runtime with ZERO connections — which is exactly what this test's getNoStoreRuntime() exercises.

**Risk** — Edit B changes global name resolution: any bare identifier that happens to end in a signature-mangle-shaped tail could now resolve to a same-named unmangled element. SignatureMangle's grammar (SignatureMangle.java:28-29) demands the trailing underscore precisely to keep ordinary snake_case names out, and the tier is a LAST resort after all existing tiers miss, so the blast radius is names that today resolve to nothing (i.e. today's hard errors). Edit A adds an optional property to a widely-used native class — it can only widen what type-checks. Tenet-2 trap to avoid: do NOT special-case `preprocessFunction` in the harness or teach EngineTestExecutor to skip getNoStoreRuntime(); the metamodel is platform-owned.

**Also unblocks** — Edit B should also unblock any corpus body holding a bare mangled user-function pointer — e.g. `columnFilterPredicate_Column_1__Boolean_1_` in autogeneration/relationalToPure.pure:25,30,159 and the `annualized_Date_$0_1$__…_` AggHandler table in calendarAggregation/calendarFunctions.pure:512-526 (those families have other walls in front, so treat this as secondary).

**Falsifier** — Add only edit A, re-run this single test. If it now fails with `'preprocessQueryDummy_FunctionDefinition_1__Runtime_1__FunctionDefinition_1_' is not a known class, mapping, runtime, connection, or database`, defect (2) is confirmed and edit B is required. If it instead passes, defect (2) does not exist (some resolution path I did not find qualifies mangled bare names) and edit B should be dropped.

<details><summary>Evidence read (12 citations)</summary>

- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/builtin/Pure.java:230 — `RUNTIME = nativeClass("native Class meta::core::runtime::Runtime { connectionStores: meta::core::runtime::ConnectionStore[*]; }")`; no preprocessFunction member
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/builtin/Pure.java:234 — `ENGINE_RUNTIME … extends meta::core::runtime::Runtime { mappings: … }`, so it inherits the incomplete Runtime
- /Users/neemsandv/legend/legend-pure/legend-pure-dsl/legend-pure-dsl-store/legend-pure-m2-dsl-store-pure/src/main/resources/platform_dsl_store/grammar/runtime.pure:19 — real pure: `preprocessFunction : Function<{FunctionDefinition<Any>[1], Runtime[1] -> FunctionDefinition<Any>[1]}>[0..1];` declared on Runtime (not on EngineRuntime)
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/compiler/spec/NewChecker.java:93-94 — `.orElseThrow(() -> new TypeInferenceException("class '" + ni.className() + "' has no property '" + name + "'"))`, the exact observed text
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/compiler/spec/NewChecker.java:83-95 — findProperty is evaluated BEFORE `t.synth(key.value(), env)`, so the mangled value is not yet a factor
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/compiler/NameResolver.java:532-592 — resolveNameMulti: wildcard / own-package / prelude tiers only, each gated on `scope.knownFqns().contains(pkg + "::" + name)`; no signature-mangle tier
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/compiler/NameResolver.java:284-292 — knownFqns = element `qualifiedName()`s + native class/enum FQNs; mangled spellings never appear
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/compiler/element/FunctionCompiler.java:43-47 — bare-name lookup is scoped to CORE_FUNCTION_PACKAGES (`meta::pure::functions::*`, `meta::pure::tds*`) only
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/compiler/spec/Typer.java:2143-2163 — functionCandidates: miss → SignatureMangle.stripTail → `ctx.findFunction(base)` (base stays bare if the resolver never qualified it)
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/compiler/spec/Typer.java:2273-2297 — single-candidate function refs eta-expand to a TypedLambda; zero candidates throw the 'is not a known class, mapping, runtime…' ResolutionException
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/compiler/spec/InferenceKernel.java:63-72,177-204 — unify normalizes `Function<{…}>` on BOTH sides then unifies bare FunctionTypes, so the eta-expanded lambda will conform to the pure-faithful property type
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/StatementExecutor.java:1961-1975 — planModel reads only quoteIdentifiers/timeZone/relationalMapperPostProcessor off the runtime arg; a store-less runtime cannot change the SQL

</details>

---

## `testPersonToFirmUsingFromProject`

| | |
|---|---|
| family | `modelJoins` |
| sweep status | SHAPE |
| **verdict** | **REAL DEFECT** |
| effort | M |
| confidence | medium |

**Root cause**

FIRST: the brief's message for this test is stale (see sharedRootCause). The current wall, per the new-stamp sweep row docs/RELATIONAL_CORPUS.md:1261, is `plan wall: association 'meta::…::Trade_LegalEntity' is not mapped in mapping 'meta::…::XStoreTradesMapping' (association 'meta::…::Trade_LegalEntity': $this.entityIdFk …`.

Mechanism: `XStoreTradesMapping`'s `Trade[trade]` set declares its two mapping-local properties with EXPRESSION bodies — `+entityIdFk: String[1]: case(isNull(toString(toString([XStoreTradesDatabase]Trades.Trade.ENTITY_ID_FK))), …)` and `+entityNameFk: String[1]: case(isNull([LocalTradesDatabase]Trades.Trade.ENTITY_NAME_FK), 'Unknown', …)` — and the XStore predicate reads them (`$this.entityIdFk == $that.entityId && $this.entityNameFk == $that.name`).

`XStorePureEnds.xstoreEndOf`'s relational-end arm builds the end's column view from the class mapping's property mappings; for a `PropertyMapping.LocalProperty` it emits a `RelationFunction.Col` ONLY when the body is a plain `PropertyMapping.Column` (XStorePureEnds.java:103-107). An expression body contributes nothing to `cols` — it only lands in the `locals` name set (XStorePureEnds.java:108-111).

Both ends here are relational, so `MappingNormalizer.synthesizeXStoreMapping` takes the COLUMN-SPACE route (MappingNormalizer.java:1116-1122 — the property-space route is reserved for a Pure-set end) and hands the predicate to `RelationReads.xstore`. That walker looks for a Col with `column()` (RelationReads.java:94-111), then for a Col with `expr()` (RelationReads.java:117-122); finding neither for `entityIdFk` it throws `NotImplementedException("association '…': $this.entityIdFk has no column binding on the Relation mapping of '…Trade' (mapping=…XStoreTradesMapping)")` at RelationReads.java:123-127. `MappingNormalizer` catches it per-association and records a poison (MappingNormalizer.java:420-436), and at query time `AssociationJoins.predicateMaterial` raises it as the observed `association … is not mapped in mapping … (<poison>)` (AssociationJoins.java:1165-1177).

This is why the four sibling tests pass: XStoreTradesMapping_withConstantDouble/String/Date/Inequalities all include `TradesMapping`, whose `+entityIdFk`/`+entityNameFk` are PLAIN columns.

The `expr` channel already exists end-to-end (`RelationFunction.Col.expr` + `Col.bindSrc`, ClassMapping.java:419-444; consumed at RelationReads.java:117-122; produced today only from the protocol at MappingFromProtocol.java:392-394). The single missing producer is the Relational→column-view conversion in XStorePureEnds.

**Fix**

core/src/main/java/com/legend/normalizer/XStorePureEnds.java, the Relational-end arm at lines 97-113: give expression-bodied bindings a `Col` carrying a `$src`-rooted ValueSpecification instead of dropping them.

- For `pm instanceof PropertyMapping.LocalProperty lp` whose `lp.body() instanceof PropertyMapping.Expression lex`, and symmetrically for a top-level `pm instanceof PropertyMapping.Expression ex` (a non-local class property with a computed body), emit:
  `cols.add(new ClassMapping.RelationFunction.Col(<propName>, null, <isLocal>, null, List.of(), null, RelOpTranslator.translate(<op>, Map.of(MappingNormalizer.canonicalTable(<mainTable>.table()), new Variable("src")), null, null, RelOpTranslator.PipelineView.NONE)));`
  where `<mainTable>` is the set's `~mainTable` (use `MappingNormalizer.inferMainTableQuiet(rcm)` when it is not spelled). The `$src` root is required because `RelationReads` inlines through `Col.bindSrc(c.expr(), row)` (RelationReads.java:119-120), which rewrites `$src` to the row variable.
- Keep `locals.add(lp.propertyName())` unchanged.
- Keep a LOUD wall (not a silent drop) for expressions the flat translation cannot express: if `JoinChainEmission.collectJoinNavigations(op, navs)` yields anything, or `RelOpTranslator.translate` throws, let the exception propagate to the existing per-association poison at MappingNormalizer.java:431 so the message still names the property.

Note on this corpus's quirk: `+entityNameFk` references `[LocalTradesDatabase]Trades.Trade.ENTITY_NAME_FK` while `~mainTable` is `[XStoreTradesDatabase]Trades.Trade`. `RelOpTranslator`'s scope is keyed by canonical TABLE name only (RelOpTranslator.java:192-202), so the cross-database spelling resolves to the same row — which is what the test needs and what the engine effectively produces. Do not add a database-identity check here as part of this fix.

**How legend-engine does it** — /Users/neemsandv/legend/legend-engine/legend-engine-core/legend-engine-core-pure/legend-engine-pure-code-compiled-core/src/main/resources/core/pure/router/store/routing.pure:95-160 — `maybeConvertXStoreToModelJoin` / `convertXStoreToModelJoin` / `convertXStorePropertyMapping` rewrite the XStore association into a ModelJoin whose `joinCondition` stays in PROPERTY space over the two end classes. /Users/neemsandv/legend/legend-engine/legend-engine-xts-relationalStore/legend-engine-xt-relationalStore-generation/legend-engine-xt-relationalStore-pure/legend-engine-xt-relationalStore-core-pure/src/main/resources/core_relational/relational/pureToSQLQuery/relationalModelJoins.pure:63-100 — `compileModelJoinForBranch` then compiles that condition with the ORDINARY `processValueSpecification` against the source/target SelectWithCursors. So the engine resolves `$this.entityIdFk` through whatever the class mapping binds it to — a column OR an arbitrary relational expression; it never requires a plain column. legend-lite's plain-column-only column view is the divergence.

**Risk** — XStorePureEnds.xstoreEndOf feeds both the XStore route (MappingNormalizer.java:1114) and the ModelJoin route (MappingNormalizer.java:1215), so the new Cols become visible to every model-join family too — previously-poisoned associations will start resolving, which can turn SHAPE rows into FAIL rows with real SQL diffs. That is a truthful move, not a regression, but expect churn in modelJoin/mft-xStore families. Tenet-2 trap: do NOT make the test pass by teaching the harness to accept the poisoned mapping, and do NOT silently drop the offending predicate conjunct — the honest wall must stay for shapes the translator cannot express. Residual: even after this fix the test asserts XStore SQL == Local SQL, so the localized join must render the same `case(isNull(toString(toString(...))), ...) = … and case(isNull(...),'Unknown',...) = …` ON clause as the declared `Entity_Trade` join; a text divergence there would surface as a FAIL, which is progress, not this fix failing.

**Also unblocks** — testPersonToFirmUsingProject (same mapping, same predicate) — it is a precondition for that test actually generating a plan. Likely also the XStore/model-join families that use expression-bodied `+props` (tests/mft/xStore, tests/mapping/modelJoin); not verified here.

**Falsifier** — Run this one test and read the wall. If it reports the association poison quoting `$this.entityIdFk has no column binding on the Relation mapping of '…Trade'`, the diagnosis holds. If it instead reports `plan walk: executionPlan argument shapes pending`, then RELATIONAL_CORPUS_ALL.md is the current sweep, my stamp-format inference is wrong, and the real cause is upstream in planModel's arg-0 shape — in which case dump `ep.args().get(0).getClass()` at StatementExecutor.java:1944 before changing anything.

<details><summary>Evidence read (14 citations)</summary>

- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/docs/RELATIONAL_CORPUS.md:1261 — new-stamp sweep row: `SHAPE testPersonToFirmUsingFromProject [modelJoins]: plan wall: association '…Trade_LegalEntity' is not mapped in mapping '…XStoreTradesMapping' (association '…Trade_LegalEntity': $this.entityIdFk`
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/harness/EngineTestExecutor.java:886-895 — scoreAssert now emits `why + " [surfaced via assert form '…']"` and the comment states the em-dash form was "the old stamp"; the brief's text is therefore from a pre-change sweep
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/normalizer/XStorePureEnds.java:99-113 — `if (pm instanceof PropertyMapping.Column c) cols.add(...)` / `else if (pm instanceof PropertyMapping.LocalProperty lp) { if (lp.body() instanceof PropertyMapping.Column lc) cols.add(...); locals.add(...); }` — an Expression body adds NO Col
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/normalizer/RelationReads.java:117-127 — the `c.expr() != null` inlining loop, then the throw `"association '" + assocName + "': $" + var.name() + "." + ap.property() + " has no column binding on the Relation mapping of '" + rf.className() + "'"`
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/normalizer/MappingNormalizer.java:1114-1122 — ends are resolved via XStorePureEnds; the property-space route is taken only `if (endA.pure() || endB.pure())`, otherwise the column-space route runs
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/normalizer/MappingNormalizer.java:1157-1162 — the column-space route calls `RelationReads.xstore(cand.expression(), thisRow, thisRf, thatRow, thatRf, …)`
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/normalizer/MappingNormalizer.java:418-436 — per-association catch records `model.mappingPoisons.putIfAbsent(md.qualifiedName() + "::" + <assoc fqn>, e.getMessage())`
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/resolver/AssociationJoins.java:1165-1177 — `associationBindingInClosure(...).orElseThrow(... "association '" + assoc + "' is not mapped in mapping '" + cs.mappingFqn() + "'" + ctx.mappingPoison(...).map(r -> " (" + r + ")"))` — exactly the observed string with the poison in parentheses
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/model/ClassMapping.java:419-444 — `Col(property, column, local, enumMappingId, embedded, inlineSetId, expr)` plus `Col.bindSrc(v, row)` which substitutes `$src` with the row variable
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/model/MappingFromProtocol.java:392-394 — the only current producer of `Col.expr` (from the protocol's relation-function property mappings)
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/normalizer/RelOpTranslator.java:183-203 — `translate(RelationalOperation, tableScope, targetVar, rowBind, PipelineView)`; a ColumnRef becomes `new AppliedProperty(tableScope.get(canonicalTable(ref.table())), ref.column())`
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/normalizer/RelOpTranslator.java:58,69 — `interface PipelineView` with a ready-made `PipelineView.NONE`
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/StatementExecutor.java:1944-1959 — planModel's arg-0 lambda wall AND the 2-arg branch `ep.args().size() > 1 && ep.args().get(1) instanceof TypedPackageableRef ? … : firstFromMapping(...)`, i.e. the 2-arg spelling the stale message blamed is supported now
- /Users/neemsandv/legend/legend-engine/legend-engine-xts-relationalStore/legend-engine-xt-relationalStore-generation/legend-engine-xt-relationalStore-pure/legend-engine-xt-relationalStore-core-pure/src/main/resources/core_relational/relational/modelJoins/testModelJoinsToRelationalJoins.pure:129 — the test's xstore side: `executionPlan({|Trade.all()->from(XStoreTradesMapping, getXStoreRuntime())->project([x|$x.value,x|$x.client.name],['Value','Client/Name']);}, relationalExtensions())`

</details>

---

## `testPersonToFirmUsingProject`

| | |
|---|---|
| family | `modelJoins` |
| sweep status | SHAPE |
| **verdict** | **HARNESS GAP** |
| effort | S |
| confidence | high |

**Root cause**

The test body has NO live assertion — every `assertEquals` is commented out in the corpus source and the function ends in `true;`. Its only substance is that `let xstoreResult = executionPlan($query, relationalExtensions());` must generate a plan without throwing (engine parity: an assert-free body that runs to completion is a pass).

The runner already encodes that policy: `Runner.score` yields PASS when `r.verified() == 0 && r.executed() > 0` (Runner.java:1466-1472) and falls to `SHAPE "no verifying assertions"` only when BOTH are zero (Runner.java:1473-1475). So the failure means `executed` never incremented.

It never increments because the `executionPlan` binding is treated as a LAZY let. In `EngineTestExecutor.tdgLetArm` the executionPlan arm is gated on `ep.parameters().size() >= 3` (EngineTestExecutor.java:1430-1433); this test uses the 2-arg spelling, so the arm is skipped and the method returns `new TdgLet(null, rhs, false)` (EngineTestExecutor.java:1480) with consumed=false. Back in the let loop, the eager-forward branch requires `containsExecute(rhs) || referencesAny(rhs, execVars)` (EngineTestExecutor.java:444), and `containsExecute` only matches a call named `execute` with >=2 params (EngineTestExecutor.java:90-104, isExecuteCall at 3393-3401) — `executionPlan` is not one. The binding therefore just lands in `lets` and is never evaluated, because nothing downstream reads it.

Note the >=3-arg arm would not help either: it also returns consumed=false (EngineTestExecutor.java:1440-1441), so no executionPlan let is counted as executed today; it simply doesn't matter for tests that go on to assert over the plan.

Second-order: even once the let is evaluated eagerly, this query drives `XStoreTradesMapping` — the mapping poisoned by the defect diagnosed for testPersonToFirmUsingFromProject — so it will wall until that fix lands. That is the honest outcome (a named platform wall instead of a vague harness stamp), and it becomes a PASS afterwards.

**Fix**

core/src/main/java/com/legend/harness/EngineTestExecutor.java, the let arm around line 444: extend the eager-forward rule to plan bindings. Concretely, when the (substituted) let RHS is an `executionPlan(...)` call (any arity — reuse the `simpleName(af.function()).equals("executionPlan")` + `harnessVocabName` test already used at EngineTestExecutor.java:1430-1432), evaluate it once through `evalSpliced`/`eval` and `executed++`, then still record it in `lets` so a later plan assert substitutes and reads it exactly as today. A wall from that evaluation must propagate as the test's outcome (SHAPE/ERROR with the platform's own message) — never be swallowed.

This is a scoring/eagerness rule the harness legitimately owns (it is the same engine-parity rule already applied to execute() bindings three lines above), not compensation for a platform shape: the plan generation itself still runs entirely through StatementExecutor.planModel.

Sequencing: land the XStorePureEnds fix (testPersonToFirmUsingFromProject) FIRST. Applying this change alone converts the row from SHAPE "no verifying assertions" to SHAPE/ERROR naming the XStoreTradesMapping association poison — more honest, still not a pass.

**Risk** — Making every executionPlan let eager means plan-generation walls now surface at tests that previously scored on other asserts and merely happened to bind a plan they never read — expect some PASS→SHAPE/ERROR movement across executionPlan/tests and testDataGeneration families. That is the intended direction (a loud wall beats a silent skip), but it should be landed as its own change so the delta is attributable. Tenet-2 trap: do not instead relax Runner.score to treat "0 asserts, 0 executed" as a PASS — that would manufacture a hollow pass for a test whose entire contract is that plan generation succeeds.

**Also unblocks** — Any other assert-free corpus test whose only substance is a 2-arg executionPlan binding; testPersonToFirmGraphUsingFetch / testPersonToFirmUsingFromGraphFetch in the same file have the same assert-free shape but are <<test.ToFix>>-excluded from this scoreboard.

**Falsifier** — Instrument (or temporarily log) `verified`/`executed` for this test. If `executed > 0` is already reported and the SHAPE row still says "no verifying assertions", my read of Runner.score is wrong and the gap is elsewhere. Equally: if adding the eager plan-let evaluation makes the row report the XStoreTradesMapping association poison, both this diagnosis and the testPersonToFirmUsingFromProject diagnosis are confirmed together.

<details><summary>Evidence read (7 citations)</summary>

- /Users/neemsandv/legend/legend-engine/legend-engine-xts-relationalStore/legend-engine-xt-relationalStore-generation/legend-engine-xt-relationalStore-pure/legend-engine-xt-relationalStore-core-pure/src/main/resources/core_relational/relational/modelJoins/testModelJoinsToRelationalJoins.pure:74-88 — body: `let query = {|Trade.all()->filter(...)->project(...)->from(XStoreTradesMapping, getXStoreRuntime());}; let xstoreResult = executionPlan($query, relationalExtensions()); // assertEquals(...) commented out; true;`
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/test/java/com/legend/rcorpus/Runner.java:1466-1475 — `if (r.verified()==0 && r.executed()>0) yield PASS("0 asserts — N statement(s) executed"); if (r.verified()==0) yield SHAPE("no verifying assertions")`, the exact observed text
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/harness/EngineTestExecutor.java:1430-1433 — the executionPlan let arm is gated on `ep.parameters().size() >= 3`
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/harness/EngineTestExecutor.java:1480 — the fall-through `return new TdgLet(null, rhs, false);` (not consumed, so `executed` is not bumped at EngineTestExecutor.java:398-400)
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/harness/EngineTestExecutor.java:444-450 — the eager-forward + `executed++` branch fires only for `containsExecute(rhs) || referencesAny(rhs, execVars)`
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/harness/EngineTestExecutor.java:3393-3401 — `isExecuteCall` matches only `execute` / `…::execute` with >=2 params, so executionPlan never qualifies
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/harness/EngineTestExecutor.java:411-418 — the doctrine already stated at this very site: "Forwarding is EAGER (audit 16 F1, engine parity): the statement executor runs the query AT the let, so a broken pipeline surfaces even when no assert ever reads the binding"

</details>

---

## `testExecutionPlanGeneration`

| | |
|---|---|
| family | `tds/tests` |
| sweep status | SHAPE |
| **verdict** | **REAL DEFECT** |
| effort | L (revised up from S by adversarial review) |
| confidence | high |
| **adversarial review** | **PARTIALLY_WRONG** |

**Root cause**

Typer.olapGroupByDesugar rewrites the legacy TDS `olapGroupBy` into the modern windowed `extend(src, over(...), ~col:...)`. For the partition columns it collects one `ColSpec` node per name (Typer.java:651 / 654) and then wraps them in a PLAIN COLLECTION LITERAL: `overArgs.add(new PureCollection(partSpecs))` (Typer.java:674). Typer.collection (Typer.java:2301-2324) types a collection literal as `elementType = commonSupertype(...)` with `multiplicity = Bounded(n,n)`, so two partition columns produce an argument of type `ColSpec<(firstName:?)>` with multiplicity [2]. Every registered `over` overload declares its column parameter as `ColSpec<T>[1]` or `ColSpecArray<T>[1]` (Pure.java:1837-1845), so no candidate scores >= 0 and InferenceKernel.resolveOverload throws the observed 'no overload of meta::pure::functions::relation::over structurally matches the argument types (ExprType[type=GenericType[rawFqn=...ColSpec, arguments=[RelationType[columns=[Column[name=firstName...' (InferenceKernel.java:808-822). The multi-column partition list must be a `ColSpecArray` node (`~[a,b]`), which is exactly what the sibling `restrict` desugar already builds (Typer.java:405-411). A SINGLE-column olapGroupBy accidentally works (collection of 1 -> multiplicity [1] -> matches the ColSpec[1] overload), which is why only the two-column corpus test walls.

**Fix**

In `core/src/main/java/com/legend/compiler/spec/Typer.java`, change `olapGroupByDesugar` so the partition list is a `ColSpecArray`, not a `PureCollection`. Concretely: declare `List<ColSpec> partSpecs = new ArrayList<>();` (instead of `List<ValueSpecification>`), keep lines 651/654 as-is, and at line 674 write `overArgs.add(new com.legend.protocol.spec.ColSpecArray(partSpecs));`. Leave line 677 (`sortKeys` as a PureCollection) unchanged — `SortInfo<T>[*]` accepts a many-valued collection. Apply the identical change at Typer.java:833 in `windowColsProjectDesugar` (`new AppliedFunction("over", List.of(new PureCollection(partSpecs)))` -> `List.of(new ColSpecArray(partSpecs))`), which has the same latent bug for a `col(window(p1,p2), ...)` project column with 2+ partition lambdas. No change is needed in OverChecker or Pure's registrations. NOTE: this only removes the WALL. The test then compares the full plan text against an H2 golden that fuses group-by and window in one select (`count(sum("root".age)) over (partition by ... order by char_length("root".id) asc nulls last)`), inlines three user functions, and carries a `${limit}` plan parameter; expect a golden-text diff next, not a pass.

**How legend-engine does it** — legend-engine .../core/pure/tds/tds.pure:756 — `function meta::pure::tds::olapGroupBy<T>(tds:TabularDataSet[1], columns:String[*], sortBy:SortInformation[0..1], operation:OlapOperation<T>[1], columnName:String[1])`: the partition list is declared `String[*]`, i.e. a genuine multi-column partition is the engine's own signature, so the desugar must produce the modern multi-column spelling (`~[a,b]` = ColSpecArray).

**⚠ Correction from adversarial review** — The code change itself is right (declare List<ColSpec> partSpecs, wrap in ColSpecArray at :674 and :832) — partSpecs has no other use in either method, so the type change is contained. What needs correcting is the EFFORT/verdict label: this is a wall-removal of XS-S, but the work item 'make testExecutionPlanGeneration pass' is L. Also note the change flips the currently-PASSING single-column olapGroupBy tests (testTDSWindowColumn) from the ColSpec[1] overload to the ColSpecArray[1] overload; OverChecker.collect handles both so I expect no regression, but that sibling behaviour must be re-run, not assumed.

<details><summary>Adversarial review notes (PARTIALLY_WRONG)</summary>

The MECHANISM is confirmed in full, and I could not find a simpler explanation. Every citation resolves exactly: Typer.java:651/654 build ColSpec per name, :674 is literally `overArgs.add(new PureCollection(partSpecs))`, :832 has the same PureCollection wrapping in windowColsProjectDesugar, :405-411 is the ColSpecArray pattern in the restrict desugar, collection() at :2301-2324 sets `Bounded(elements.size(), elements.size())`, Pure.java:1837-1845 registers only ColSpec<T>[1] / ColSpecArray<T>[1] / SortInfo<T>[*], and InferenceKernel.java:808-822 emits the exact message. I also verified the two things the diagnosis merely asserts: (a) the rejection really is on MULTIPLICITY — paramMultScore (InferenceKernel.java:1054-1076) returns -1 for formal [1] vs actual Bounded(2,2) because Multiplicity.isMany() (Multiplicity.java:34) is `upper==null || upper>1` and relationRow() (:1253) is null for a ColSpec GenericType; (b) the corpus source really is 2-column — testCanRouteWrappedFunctions.pure:70 `olapGroupBy(['firstName','lastName'], asc('id'), ...)`. Strong corroboration: every single-column olapGroupBy test (testTDSWindowColumn.pure:31/43/56/73/93) is ABSENT from rows.json, i.e. passing — exactly the 1-vs-2 split predicted. I also attacked a risk the diagnosis did not mention: switching to ColSpecArray makes this the first call that binds T from BOTH cols and sortInfo; InferenceKernel.bindOrCheckTypeVar (:393+) explicitly merges two unsolved schema fragments and names `over<T>(ColSpec<T>, SortInfo<T>[*])` as the case, so that is not a blocker. WHY PARTIALLY_WRONG: the effort/verdict framing, not the mechanism. The header says 'REAL_DEFECT, effort S' against a TEST, while the body admits the change only converts the wall into a golden-text diff. To green this test you additionally need group-by + window fused in one select, three user functions inlined across the routing boundary, a ${limit} plan parameter, and the resultColumns/type=TDS lines — none of which I can size without running. Budgeting S per test here is the expensive error; the ColSpecArray edit alone is XS.

</details>

**Risk** — Low for the olapGroupBy site — a single-column partition changes from `ColSpec[1]` to `ColSpecArray<(c:?)>[1]`, which resolves to `OVER__COL_SPEC_ARRAY_1`/`OVER__COL_SPEC_ARRAY_1__SORT_INFO_MANY` and still lands on `TypedColSpecArray` in OverChecker.collect, so the single-column olapGroupBy tests (testTDSWindowColumn.pure:31/43/56/73/93, testTDSFilter.pure:218) must be re-checked for identical emitted SQL. The tenet-2 trap to avoid: do NOT teach the harness or PlanAsserts to tolerate this wall — it is a platform typer bug and belongs in Typer.

**Also unblocks** — No other test in the relational corpus uses a multi-column olapGroupBy (grep over the corpus finds only testCanRouteWrappedFunctions.pure:70). The companion fix at Typer.java:833 pre-empts the same failure for any window-col project with 2+ partition lambdas.

**Falsifier** — Compile just `meta::relational::tests::tds::window::routing::function3`'s body (or any `olapGroupBy(['a','b'], ...)`) and print the desugared AppliedFunction: if the `over` argument is already a ColSpecArray (i.e. the partition wrapping is not a PureCollection), this diagnosis is wrong. Equivalently, if a SINGLE-column `olapGroupBy(['x'], ...)` also fails with the same message, the cause is not multiplicity and this is wrong.

<details><summary>Evidence read (10 citations)</summary>

- core/src/main/java/com/legend/compiler/spec/Typer.java:674 — `overArgs.add(new PureCollection(partSpecs))`: the partition ColSpecs are wrapped in a plain collection literal, not a ColSpecArray
- core/src/main/java/com/legend/compiler/spec/Typer.java:651 and :654 — each partition name becomes `new ColSpec(name)`; the multi-name arm iterates a PureCollection of CStrings
- core/src/main/java/com/legend/compiler/spec/Typer.java:2301-2324 — `collection(...)`: element type is the common supertype, `Multiplicity mult = new Multiplicity.Bounded(elements.size(), elements.size())` — two ColSpecs give multiplicity [2]
- core/src/main/java/com/legend/compiler/spec/Typer.java:405-411 — the `restrict` desugar builds `new com.legend.protocol.spec.ColSpecArray(specs)` for exactly the same 'list of bare column names' shape (the correct pattern)
- core/src/main/java/com/legend/compiler/spec/Typer.java:2871-2895 — `typedColSpecArray` gives a `ColSpecArray<(a:?,b:?)>[1]` value; TypedColSpecArray is already handled by OverChecker
- core/src/main/java/com/legend/builtin/Pure.java:1837-1845 — all 10 `over` registrations take `ColSpec<T>[1]`, `ColSpecArray<T>[1]` or `SortInfo<T>[*]`; there is no `ColSpec[2]` overload. `OVER__COL_SPEC_ARRAY_1__SORT_INFO_MANY` (line 1842) is the one this call needs
- core/src/main/java/com/legend/compiler/spec/InferenceKernel.java:808-822 — `if (winners.isEmpty()) throw new TypeInferenceException("no overload of '" + name + "' structurally matches the argument types (" + detail + ")" + cands)` — the exact emitted message
- core/src/main/java/com/legend/compiler/spec/OverChecker.java:56-66 — `collect(...)` already accepts `TypedColSpecArray` (`case TypedColSpecArray arr -> partitions.addAll(arr.names())`), so the downstream construct node needs no change
- core/src/main/java/com/legend/compiler/spec/SortChecker.java:106-113 — `asc('id')` is normalized to `asc(~id)` and checked to a TypedSortInfo, so the second argument is NOT the problem
- core/src/main/java/com/legend/harness/PlanAsserts.java:163-199 — `planTextAssert` catches LegendCompileException and reports `unsupported("plan wall: " + msg)`, which is how the typer wall surfaces as the SHAPE row in the brief

</details>

---

## `testAlloyTestDatGenWithQuotedColumnsForViews`

| | |
|---|---|
| family | `testDataGeneration/tests` |
| sweep status | SHAPE |
| **verdict** | **MISSING FEATURE** |
| effort | L |
| confidence | high |

**Root cause**

The test data generator's relation locator has no VIEW branch at all. `TestDataGenerator.locate` searches each database's schemas and top-level table lists for a matching TABLE; if it instead finds a VIEW of that name it throws `NotImplementedException("testDataGen: view-backed relation '" + table + "' — view slice pending")` — twice, once for schema-owned views and once for top-level views. The query in this test projects `Party.identifier.identifier` through `MappingWithJoinToSchemaInAnotherView`, so `ScanRelations` yields `AltID_View` as a relation in the tree, and the first `locate` call on it walls. The wall is honest: there is no code anywhere in TestDataGenerator that emits the engine's two-node view slice.

**Fix**

Implement the view slice in core/src/main/java/com/legend/testdatagen/TestDataGenerator.java, mirroring the engine's `planTestDataGenerationForNestedViewTree`. (a) Change `locate` (line 916-966) to return the view definition instead of throwing — give `Located` a nullable `ViewDefinition` alongside its `TableDefinition`. (b) In `planNode` (line 1431), when the located relation is a view, emit TWO allocations instead of one: first a node named `<res>_v` for the view's main table (the engine's `mainTable($view)` — the table the view's own from-tree roots at), planned exactly like a normal table node (`select top 20` of the mapped columns, seeded from the parent via the `${parent}` placeholder or from rowIdentifiers when it is the root); then a node named `<res>` whose `type = Relation[name=<view>, type=VIEW, schema=<view schema>, database=<db fqn>, columns=[...]]` and whose sql is the view's own column expressions re-rooted onto `(select * from (${<res>_v}) as "root") as "root"` with `select top 20`. (c) `relationType` (line 1468-1477) currently hard-codes `type=TABLE` — it must spell `type=VIEW` for the view node. (d) The view's column aliases are QUOTED in the golden (`"root"."alternativeID" as "altID"`), which is the point of the test's comment, so the re-rooted column references must carry quotes. Recurse for the view's children off the VIEW node's varName, as the engine does at testDataGeneration.pure:869/955.

**How legend-engine does it** — /Users/neemsandv/legend/legend-engine/.../core_relational/relational/testDataGeneration/testDataGeneration.pure:1023 declares `planTestDataGenerationForNestedViewTree(...)`; line 1026 is `let currentIndices = $indices->add('v');` — this is exactly where the golden's `_v` suffix comes from. Lines 1024-1135 build the view's MAIN-TABLE allocation (`meta::relational::metamodel::mainTable($view)`), then lines 1145-1200 rewrite the view's own select with `fixRelations($tableOldToNew)` so its main-table reference becomes `${res_c0_c0_v}`, wrap it as a `RelationDataSelectSqlQuery` with `toRow = ^Literal(value=20)`, and emit the second AllocationExecutionNode named `res_c0_c0`. The dispatch sites that call it are testDataGeneration.pure:865 (root view) and :951 (child view), both guarded by `$tree.isView()`.

**Risk** — The two-node emission changes the varName sequence (`_v` interleaves into the `res_cN` naming), so any other testDataGeneration golden that already passes and happens to contain a view would shift. Grep the corpus for testDataGeneration tests whose goldens contain `type=VIEW` before landing. Tenet-2 trap: the harness's TestDataGenForm must not be taught to skip or rename view nodes — the node naming is the platform's.

**Falsifier** — If `ScanRelations` does not actually put `AltID_View` in the relation tree as a relation (e.g. if it already inlines views into their main tables elsewhere in the pipeline), then `locate` would never be called with a view name and the wall would be coming from somewhere else. Cheapest check: read `com.legend.lineage.ScanRelations` and confirm it emits a `Rel` whose `table()` is a view name for this mapping — or add a temporary print at TestDataGenerator.java:943 to confirm the thrown name is `AltID_View` and its caller is `planNode`.

<details><summary>Evidence read (4 citations)</summary>

- core/src/main/java/com/legend/testdatagen/TestDataGenerator.java:942-948 — schema-owned view branch: `throw new NotImplementedException("testDataGen: view-backed relation '" + table + "' — view slice pending")`
- core/src/main/java/com/legend/testdatagen/TestDataGenerator.java:955-961 — the identical throw for top-level views
- core/src/main/java/com/legend/testdatagen/TestDataGenerator.java:1431-1466 — `planNode` only ever produces a single `Relational` allocation per relation via `planRootSql`/`planChildSql`; there is no view-pair emission
- /Users/neemsandv/legend/legend-engine/.../core_relational/relational/testDataGeneration/tests/testDataGeneration.pure:3155-3186 — the golden: an Allocation `res_c0_c0_v` for the view's MAIN TABLE `AltIDToEntityMapping`, then an Allocation `res_c0_c0` typed `Relation[name=AltID_View, type=VIEW, schema=ViewSchema, ...]` whose sql is `select top 20 "root"."alternativeID" as "altID", "root"."entityID" as "entityID" from (select * from (${res_c0_c0_v}) as "root") as "root"`

</details>

---

## `testErrorDueToNoSeedForRoot`

| | |
|---|---|
| family | `testDataGeneration/tests` |
| sweep status | SHAPE |
| **verdict** | **MISSING FEATURE** |
| effort | M |
| confidence | high |

**Root cause**

The engine's contract for "the caller supplied no row identifiers for a root table" is NOT to fail — it is to emit an `ErrorExecutionNode` INTO the plan, wrapping a probe query over the table's primary key. legend-lite's `planRootSql` has no such branch: it scans `rowIds` for a matching table+schema and, finding none, throws `NotImplementedException("testDataGen plan: no row identifiers for root '" + rel.table() + "'")`. The test passes `let tableRowIdentifiers = [];` deliberately and asserts the resulting Error-node plan text, so legend-lite walls exactly where the engine produces its expected output. The absent surface is precise and small: an `Error` plan node in PlanText plus a distinct "no-seed root" SQL form.

**Fix**

Two edits. (1) Add an `error` node builder to core/src/main/java/com/legend/plan/PlanText.java beside `allocation` (line 165): `public static String error(String message, String inner) { return "Error\n(\n  type = meta::pure::metamodel::type::Any\n  message = " + message + "\n  (\n" + indent(inner, "    ") + "  )\n)\n"; }` — the message's trailing newline must be emitted ESCAPED as the two characters backslash-n (the golden's `[ID:INT]\\n` in Pure source), matching how the engine's planToString escapes it. (2) In core/src/main/java/com/legend/testdatagen/TestDataGenerator.java replace the throw at line 1489-1492 with the engine's no-seed branch: build the probe SQL as `select top 5 "<lowercased table>_0".<pk> as "<pk>" from <qualified table> as "<lowercased table>_0"` over the table's PRIMARY KEY columns only (top 20 when the root is a view), with no where clause beyond the milestoning filter that `planMilestone` already computes; set the node's `type = Relation[...]` and `resultColumns` to the PK columns only (not the full mapped column list `planNode` currently uses at line 1441/1448); and wrap the whole `Relational` in the new `Error` node with message `Row Identifers should be provided for the root table: <db fqn>.<qualified table name> [<pk name>:<SQL type>,...]` + newline. Note the alias in the golden is `person_0`, not `root` — the no-seed probe uses the engine's `main` alias which reAliasQuery renames into the table's group, so this SQL must NOT go through the `"root"` spelling that `planRootSql` uses. `planNode` must be restructured so the no-seed case returns the Error-wrapped node in place of the Allocation and does not recurse into children.

**How legend-engine does it** — /Users/neemsandv/legend/legend-engine/.../core_relational/relational/testDataGeneration/testDataGeneration.pure:1203-1254 — `planRowIdentifierExtractionForTable`: when `$tablePk` is empty it builds a `RelationDataSelectSqlQuery` with `columnSubset = $table.primaryKey`, `toRow = if($isViewRoot,|^Literal(value=20),|^Literal(value=5))` (line 1215), `mainAlias = ^TableAlias(name='main', ...)` (line 1209), NO filtering operation other than the optional milestoning filter, and wraps the resulting node in `^ErrorExecutionNode(message = 'Row Identifers should be provided for the root table' + if($isViewRoot,...) + ': ' + $table.schema.database->elementToPath()+'.'+$table->getQualifiedTableName()+' ['+$table.primaryKey->map(c|$c.name+':'+dataTypeToSqlText($c.type))->joinStrings(',')+']\n', resultType = ^ResultType(genericType=^GenericType(rawType=Any)), executionNodes = $finalNode)` (lines 1245-1252).

**Risk** — `planNode` currently always wraps its Relational in an Allocation (TestDataGenerator.java:1458-1460); the no-seed case must NOT allocate (the golden shows the Error node directly under MultiResultSequence) and must NOT recurse into `rel.children()`. Getting that control flow wrong would emit a plan with a dangling `${res_c0}` reference. Tenet-2 trap: do not let the harness catch the NotImplementedException and synthesize the Error text — the Error node is a plan node the platform owns.

**Falsifier** — If the observed message's `rel.table()` is not `Person` from the inheritance DB — i.e. if the wall is being reached for a different relation than the intended root — the fix location is wrong. Cheapest check: the sweep detail already reads "no row identifiers for root 'Person'" and the test's mapping is `meta::relational::tests::mapping::inheritance::relational::inheritanceMappingDB` over `myDB` whose golden names `myDB.Person [ID:INT]` — these agree, so the falsifier here is instead about the SQL FORM: print what `planMilestone` returns for `myDB.Person` and confirm it is null (the golden has no where clause at all).

<details><summary>Evidence read (4 citations)</summary>

- core/src/main/java/com/legend/testdatagen/TestDataGenerator.java:1482-1492 — `TableRowIds ids = null; for (...) {...} if (ids == null) { throw new NotImplementedException("testDataGen plan: no row identifiers for root '" + rel.table() + "'"); }`
- core/src/main/java/com/legend/testdatagen/TestDataGenerator.java:1514-1519 — the only root SQL form legend-lite can emit: `select top 20 <all mapped cols> from <table> as "root" where <pk predicate>` — always top 20, always aliased "root", always with a where clause
- core/src/main/java/com/legend/plan/PlanText.java:144-181 — the plan-text vocabulary has `sequence`, `functionParametersNode`, `allocation`, `scalarTypeBlock`, `constant`; there is NO `error(...)` node builder
- /Users/neemsandv/legend/legend-engine/.../core_relational/relational/testDataGeneration/tests/testDataGeneration.pure:2338-2371 — the golden expects `MultiResultSequence > Error(type = meta::pure::metamodel::type::Any, message = Row Identifers should be provided for the root table: ...myDB.Person [ID:INT]\n, ( Relational( type = Relation[...columns=[("ID",INT)]], resultColumns = [("ID", INT)], sql = select top 5 "person_0".ID as "ID" from Person as "person_0" ) ) )`

</details>

---

## `testResultToJsonStream`

| | |
|---|---|
| family | `tests` |
| sweep status | SHAPE |
| **verdict** | **MISSING FEATURE** |
| effort | XL |
| confidence | high |

**Root cause**

Two independent gaps, of which only the first is visible. (a) legend-lite's builtin `meta::pure::tds::TDSColumn` declares ONLY `offset` and `name` (Pure.java:485). The test constructs `^TDSColumn(name='id', type=String)`, so NewChecker.check looks up the property `type`, `t.model().findProperty(...)` returns empty, and it throws `class 'meta::pure::tds::TDSColumn' has no property 'type'` (NewChecker.java:94) — the exact reported wall. Real Pure's TDSColumn declares seven properties. (b) Even with the class completed, the test's whole point is `$result->toJSONStringStream([],true)->makeString()`, and `toJSONStringStream` does not exist anywhere in legend-lite (no hit in core/src/main/java). The Runner classifies the test SHAPE via the no-execute path (Runner.java:1311-1314), which attaches the first wall it hit; the class-property gap is merely the first thing the try-run touched.

**Fix**

Two parts, both in platform code. (1) In `core/src/main/java/com/legend/builtin/Pure.java:485`, complete the native class to match tds.pure:25-40: `native Class meta::pure::tds::TDSColumn extends meta::pure::metamodel::type::Any { offset: Integer[0..1]; name: String[1]; type: meta::pure::metamodel::type::Type[0..1]; enumMappingId: String[0..1]; enumMapping: meta::pure::mapping::EnumerationMapping<meta::pure::metamodel::type::Any>[0..1]; documentation: String[0..1]; sourceDataType: meta::pure::metamodel::type::Any[0..1]; }` (fully qualifying each type as the neighbouring registrations do). This alone requires that a bare element reference such as `String` or `GeographicEntityType` synthesizes to a value conforming to `meta::pure::metamodel::type::Type` — check Typer.packageableRef (Typer.java:2240-2296), which today resolves classes/mappings/runtimes/connections/databases and otherwise throws ResolutionException; a PrimitiveType/Enumeration-as-value arm is part of this fix. (2) Implement `meta::pure::mapping::toJSONStringStream(Result, ..., Boolean)` producing the engine's exact envelope: `{"values": [ {"columns":[{"name":..,"type":..,"metaType":"PrimitiveType"|"Enumeration"|""},...],"rows":[{"values":[...]}], "__TYPE": "meta::pure::tds::TabularDataSet"}], "activities": [...], "__TYPE": "meta::pure::mapping::Result"}` with nulls emitted for unset optional fields. That is a new serializer subsystem, not a patch. HONEST RECOMMENDATION: fix (1) because the truncated builtin class is a real correctness defect that will mis-wall other tests, and LEDGER (2) — this single test is not worth a JSON-serialization subsystem, and the wall it prints today is honest.

**How legend-engine does it** — legend-engine legend-engine-core/legend-engine-core-pure/legend-engine-pure-code-compiled-core/src/main/resources/core/pure/tds/tds.pure:25-40 — `Class meta::pure::tds::TDSColumn { offset: Integer[0..1]; name: String[1]; type: Type[0..1]; enumMappingId: String[0..1]; enumMapping: meta::pure::mapping::EnumerationMapping<Any>[0..1]; documentation: String[0..1]; sourceDataType: Any[0..1]; }` — five properties legend-lite is missing, including the `type: Type[0..1]` this test sets.

**Risk** — Adding `type: Type[0..1]` to TDSColumn changes what `^TDSColumn(...)` and any `.type` navigation type to; anywhere legend-lite synthesizes TDSColumn instances internally must keep conforming. The tenet-2 trap: do not special-case `toJSONStringStream` in EngineTestExecutor to return a canned string — that is exactly the harness compensation the tenets forbid; a loud 'toJSONStringStream is not implemented' wall is the correct state until the serializer exists.

**Also unblocks** — Any corpus test that reads or sets TDSColumn.documentation / .enumMappingId / .sourceDataType (the TDS metadata family) is blocked by the same truncated class.

**Falsifier** — Add nothing and instead grep the built model for a `type` property on TDSColumn: if `ModelContext.findProperty("meta::pure::tds::TDSColumn", "type")` is non-empty at runtime (i.e. something else registers a richer TDSColumn than Pure.java:485), part (a) of this diagnosis is wrong. For part (b): if any `toJSONStringStream` implementation exists under a name my grep missed, the MISSING_FEATURE verdict is wrong and this becomes a golden-text diff.

<details><summary>Evidence read (6 citations)</summary>

- core/src/main/java/com/legend/builtin/Pure.java:485 — `nativeClass("native Class meta::pure::tds::TDSColumn extends ...Any { offset: Integer[0..1]; name: String[1]; }")` — only two properties
- core/src/main/java/com/legend/compiler/spec/NewChecker.java:88-95 — `t.model().findProperty(ni.className(), name) ... .orElseThrow(() -> new TypeInferenceException("class '" + ni.className() + "' has no property '" + name + "'"))` — the exact message text
- core/src/test/java/com/legend/rcorpus/Runner.java:1303-1314 — the no-execute path runs `tryRunNoExecute` and, on failure, emits `"no execute(|...) call" ... " — wall: " + attempted.wall()`, matching the brief's message shape
- grep for `toJSONStringStream` / `toJSONString` over core/src/main/java returns ZERO hits — the serializer surface is entirely absent
- core/src/main/java/com/legend/builtin/Pure.java:1534 — `meta::pure::mapping::Result<T> { values: T[*]; activities: Activity[*]; }` exists, and :496 `meta::relational::mapping::RelationalActivity` exists with sql/comment/timings/dataSource, so the surrounding model the test builds IS available — only the serializer and the TDSColumn fields are missing
- corpus tests/relationalSetUp.pure:1557-1608 — the test body: `^TDSColumn(name='id', type=String)`, `^TDSColumn(name='Location', type=GeographicEntityType)`, then asserts one of two exact JSON strings from `toJSONStringStream([],true)->makeString()`

</details>

---

## `relationalResultSourcingOfListExecutionPlan`

| | |
|---|---|
| family | `tests/advanced` |
| sweep status | ERROR |
| **verdict** | **MISSING FEATURE** |
| effort | XL |
| confidence | medium |

**Root cause**

The lambda is `let nameList = Firm.all()->filter(..).legalName->distinct(); Person.all()->filter(y|$y.firm.legalName->in($nameList))->project(..)->sort(..)`. StatementExecutor.sequencePlan turns the let into an Allocation via allocationNode (StatementExecutor.java:900-972), which lowers the let value through engineSql and renders it with `planDialect(...)` = the engine-style H2 renderer. The let value's type is `String[*]` — a VALUE COLLECTION, not a RelationType — so Lowerer.lower falls through its RelationType arm and its root-TypedMap arm and lands in `scalarRoot` (Lowerer.java:262-281), which builds a FROM-less select and, because the value is many-valued, wraps the expression in `SqlExpr.Call.of(SqlFn.UNNEST, e)` (Lowerer.java:273). legend-lite's value-collection strategy is DuckDB LIST-space (Scalars.java:1348-1360 folds `removeDuplicates` to `orderedDedup(list)`, and its own comment names 'the root UNNEST'). `unnestProjection` is overridden ONLY by DuckDb (DuckDb.java:268); the engine-style H2 renderer inherits the base, which throws `DialectCapability("UNNEST reached a dialect without an unnest placement")` (AnsiSqlRenderer.java:614-616, reached from the `case UNNEST ->` dispatch at :553). Two consequences: (i) the plan channel cannot render a many-valued scalar allocation at all, and (ii) the failure is reported as [ERROR] rather than [SHAPE] because `DialectCapability extends IllegalStateException` (DialectCapability.java:14) while PlanAsserts only catches NotImplementedException / LegendCompileException / UnsupportedOperationException (PlanAsserts.java:188-190). Underneath both, the test can never pass anyway: its golden requires `RelationalBlockExecutionNode`, `FreeMarkerConditionalExecutionNode`, `CreateAndPopulateTempTable` and the `inFilterClause_<var>` templating — none of these strings occur anywhere in legend-lite's source.

**Fix**

Do NOT fix by giving EngineStyleH2 an UNNEST spelling — H2 has none and the engine's plan contains none. Two separable changes: (A) WALL CLASSIFICATION (XS, do this): in `core/src/main/java/com/legend/sql/dialect/EngineStyleH2.java`, override `unnestProjection` (and, for consistency, `listCall`/`listExists`/`listForAll`/`foldCall`) to `throw new UnsupportedOperationException("plan: <construct> has no engine-H2 spelling")`, exactly matching the convention this class already sets at line 1054. That reclassifies the failure from [ERROR] to [SHAPE] with an honest message and stops a dialect-capability exception escaping the plan channel. Do not instead change DialectCapability's supertype — that would silently reclassify every dialect gap everywhere. (B) THE ACTUAL FEATURE (XL, ledger it): a relation-space lowering for many-valued scalar roots (extend ValueCollectionOps.relationSpaceRewrite — today it only fires when the argument is ALREADY a RelationType, ValueCollectionOps.java:63-68 — to also accept `removeDuplicates(TypedMap(<relation>, λ))` and rewrite it to `TypedDistinct(TypedProject(rel, [FuncCol(name, λ)]))`), PLUS the entire Allocation-block plan vocabulary (RelationalBlockExecutionNode, FreeMarkerConditionalExecutionNode, CreateAndPopulateTempTable, inFilterClause templating). Recommendation: apply (A), ledger (B) — the wall is honest and this golden is deep engine plan-machinery.

**How legend-engine does it** — The golden itself is the engine reference: the expected plan text in tests/advanced/testRelationalResultSourcing.pure:64 shows the engine keeps the `nameList` allocation in RELATION space (`select distinct "root".LEGALNAME from firmTable as "root" where ...`, no unnest) and materializes the IN-list through a RelationalBlockExecutionNode / FreeMarkerConditionalExecutionNode / CreateAndPopulateTempTable trio driven by `collectionSize(nameList) > 50`.

**Risk** — Change (A) is contained to the engine-style renderer and only affects how plan-channel walls are classified. Change (B)'s relation-space rewrite is the risky one: `removeDuplicates` in real Pure preserves FIRST-OCCURRENCE order, which `orderedDedup` honours in list space but `SELECT DISTINCT` does not — rewriting to relation space could silently change row order for currently-passing DuckDB tests that assert an ordered CSV of a deduped collection. The tenet-2 trap: do not add DialectCapability to PlanAsserts' catch list — that puts a platform classification decision in the harness; fix it at the dialect, per EngineStyleH2:1054's own precedent.

**Also unblocks** — Change (A) converts any other plan-channel test that trips a dialect capability gap from ERROR to SHAPE. `relationalResultSourcingOfDateList` (same file, line 44) is the same list-allocation shape and, if it has a plan variant, is blocked identically.

**Falsifier** — Run the plan generation with LL_TMP_DEBUG set and read the `[plan-wall]` stack (PlanAsserts.java:196). If the UNNEST originates anywhere other than Lowerer.java:273 in the `nameList` allocation — e.g. from the terminal query's `in($nameList)` rendering — the scalarRoot half of this diagnosis is wrong (the missing plan-node vocabulary half stands regardless). Cheapest single observation: whether removing the `->distinct()` from the let makes the UNNEST disappear.

<details><summary>Evidence read (11 citations)</summary>

- core/src/main/java/com/legend/sql/dialect/AnsiSqlRenderer.java:614-616 — `protected String unnestProjection(List<SqlExpr> args) { throw new DialectCapability("UNNEST reached a dialect without an unnest placement"); }` — the exact emitted message
- core/src/main/java/com/legend/sql/dialect/AnsiSqlRenderer.java:553 — `case UNNEST -> unnestProjection(a);` is the only dispatch into it
- grep for `unnestProjection` over core/src/main/java: three hits only — the base (AnsiSqlRenderer:614), the dispatch (:553) and DuckDb.java:268. EngineStyleH2 does NOT override it
- core/src/main/java/com/legend/lowering/Lowerer.java:262-281 — `scalarRoot`: `if (isMany(spec)) { e = SqlExpr.Call.of(SqlFn.UNNEST, e); }` over a `SqlSource.Dual()` from — the many-valued scalar root encoding
- core/src/main/java/com/legend/lowering/Scalars.java:1343-1360 — the removeDuplicates rule folds to `orderedDedup(args.get(0))` in LIST space and its comment explicitly names 'the root UNNEST' as the consumer
- core/src/main/java/com/legend/StatementExecutor.java:900-972 — `allocationNode`: for a non-class, non-literal let it calls `engineSql(..., planDialect(dbType, quote, timeZone), ...)` and then `renderer.render(bareSel)`, i.e. the allocation value is rendered by the engine-style H2 dialect
- core/src/main/java/com/legend/sql/dialect/DialectCapability.java:14 — `public final class DialectCapability extends IllegalStateException` — NOT UnsupportedOperationException
- core/src/main/java/com/legend/harness/PlanAsserts.java:188-198 — the plan-assert catch list is `NotImplementedException | LegendCompileException | UnsupportedOperationException`, with a comment naming UnsupportedOperationException as 'the sql layer's standalone wall type'
- core/src/main/java/com/legend/sql/dialect/EngineStyleH2.java:1052-1055 — the engine-H2 layer's OWN convention for an unspellable construct: `// engine-H2 text has no struct vocabulary — a named wall (SHAPE in the plan branch), not a dialect bug` followed by `throw new UnsupportedOperationException("plan: struct extraction has no engine-H2 spelling")`
- grep for `inFilterClause` / `CreateAndPopulateTempTable` / `FreeMarkerConditionalExecutionNode` / `RelationalBlockExecutionNode` over core/src/main/java: zero hits — the plan-node vocabulary the golden demands does not exist
- corpus tests/advanced/testRelationalResultSourcing.pure:56-67 — the golden requires exactly those nodes plus the `${inFilterClause_nameList}` splice in the terminal SQL

</details>

---

## `testEnumFilterWithUnionMappingPlanGeneration`

| | |
|---|---|
| family | `tests/mapping/union` |
| sweep status | SHAPE |
| **verdict** | **REAL DEFECT** |
| effort | L (revised up from S by adversarial review) |
| confidence | high |
| **adversarial review** | **PARTIALLY_WRONG** |

**Root cause**

`Address` is union-mapped (AddressSet1 + AddressSet2), so the H-phase produces a TypedConcatenate, which Lowerer lowers to `SqlSelect.starOf(new SqlSource.Subselect(union(c), nextAlias(), null))` (Lowerer.java:461-462) — a Subselect whose INNER is a `SqlUnion`, aliased from `nextAlias()` = "t" + counter (Lowerer.java:283-285), hence "t2". PlanText then has to spell the plan's `type = TDS[...]` and `resultColumns = [...]` lines by resolving each top-level projection's `alias.column` back to a physical store column via `resolvePhysical` (PlanText.java:718-759). Its `case SqlSource.Subselect sub` arm is guarded by `sub.alias().equals(alias) && sub.inner() instanceof SqlSelect is` (PlanText.java:733-735). For a union subselect the alias matches but `sub.inner()` is a `SqlUnion` (SqlQuery permits exactly SqlSelect and SqlUnion — SqlQuery.java:11), so the guard fails, control falls out of the switch, and line 756-758 throws `plan: alias 't2' not resolvable to a table (Subselect)` — the exact reported message, including the `(Subselect)` simple-name suffix. The identical gap exists in `resolveStarColumn` (PlanText.java:692-709), whose Subselect arm is also `SqlSelect`-only. This is a genuine missing arm, not an honest wall: the branches of a union produced by a union mapping all have the same output shape, so the column IS resolvable.

**Fix**

In `core/src/main/java/com/legend/plan/PlanText.java`, add a UNION arm to both physical-column resolvers. In `resolvePhysical` (line 733), after the existing `sub.inner() instanceof SqlSelect` branch, add: when `sub.alias().equals(alias) && sub.inner() instanceof com.legend.sql.SqlUnion u && !u.branches().isEmpty()`, resolve the column through the FIRST branch — i.e. if `u.branches().get(0) instanceof SqlSelect bs`, find the projection whose `outputName()` strips to `col` and whose expr is a `SqlExpr.Column c2`, then recurse `resolvePhysical(bs.from(), c2.table(), strip(c2.name()))`; a non-column projection (the `'city'` literal discriminator) stays loud since it has no physical column. Make the same addition to `resolveStarColumn`'s Subselect arm (line 692). First-branch resolution is sound because every branch of a mapping-generated union carries the same output names and store types. BE HONEST ABOUT WHAT THIS BUYS: it converts the test from a SHAPE wall into a plan-text golden DIFF, it does not make it pass. Passing additionally needs (i) the engine's `unionBase` alias for a union frame instead of `unionalias_N`/`tN` (EngineStyleH2.java:349-375 rename rules), (ii) engine union column naming `<setId><prop>_<setId><prop>` and `pk_0_N` split keys, and (iii) the constant-enum property mapping rendered as a literal discriminator column. Those are separate, larger pieces of union parity.

**How legend-engine does it** — The golden at corpus tests/mapping/union/testUnion.pure:395 is the engine's own output: the union subselect is aliased `"unionBase"`, the enum-constant property mappings become literal discriminator projections named by concatenating the members' values (`"city_REGION"`), the shared property column is named `<set1><prop>_<set2><prop>` (`AddressSet1name_AddressSet2name`), and the enum filter renders through `optionalVarPlaceHolderOperationSelector` / `equalEnumOperationSelector` — templates legend-lite already emits (EngineStyleH2.java:441-479, PlanSupportFunctions.java:41-63).

**⚠ Correction from adversarial review** — The code sketch is workable (SqlUnion.branches() exists and returns List<SqlQuery>), but two things to nail down before it is written: (1) first-branch resolution is only sound if legend-lite's union branches actually project the outer name as a plain SqlExpr.Column — if legend-lite names the branch projections differently from the outer reference, the loop finds nothing and you must still throw, so keep the loud fallthrough rather than returning a guessed physical column; (2) re-label the effort — the PlanText arm is S, the work item 'make testEnumFilterWithUnionMappingPlanGeneration pass' is L/XL (unionBase alias, engine union column naming, pk_0_N split keys, constant-enum discriminator, and the enumMap/optionalVarPlaceHolder freemarker where-clause). Sequence it as a deliberate SHAPE->FAIL conversion, and expect the burndown's SHAPE count to drop while the FAIL count rises.

<details><summary>Adversarial review notes (PARTIALLY_WRONG)</summary>

Mechanism confirmed as far as static reading can go. All eight legend-lite citations resolve exactly: PlanText.java:757 throws `plan: alias '...' not resolvable to a table (<SimpleName>)` — matching the brief's 'plan: alias 't2' not resolvable to a table (Subselect)' verbatim; :733-734 is the `sub.alias().equals(alias) && sub.inner() instanceof SqlSelect is` guard; resolveStarColumn's Subselect arm at :692-708 has the same SqlSelect-only shape; SqlQuery.java:11 permits exactly SqlSelect and SqlUnion; Lowerer.java:461-462 is `case TypedConcatenate c -> SqlSelect.starOf(new SqlSource.Subselect(union(c), nextAlias(), null))`; :283-285 is `"t" + aliasCounter++`; :528-535 walls on a top-level union; :579-596 is the resolvePhysical call site. I attacked the mechanism two ways. (1) Alternative reading of the message: the same text is also produced when the alias simply does not match that Subselect — the message alone cannot distinguish. But a SqlSelect inner WITH a matching alias throws a different message ('column ... not a plain projection of subselect'), and a Join src would print '(Join)', so a matching-alias union inner is the only reading that fits a well-formed from-tree. (2) Could a later pass flatten the Subselect? I read lowering/SubselectPrune.java:20-90: it only prunes columns, never flattens, and explicitly skips set-operation branches. So the Subselect(SqlUnion) survives. I did NOT run the falsifier, so 'the inner is a SqlUnion' remains inferred. WHY PARTIALLY_WRONG: the label. 'REAL_DEFECT, effort S' sits on a test that the diagnosis itself says will still fail after the fix — it converts SHAPE into a plan-text DIFF. I confirmed the size of the residual against the golden at testUnion.pure:394: it needs the "unionBase" frame alias, engine union column naming (AddressSet1name_AddressSet2name), pk_0_0/pk_0_1 split keys, the 'city'/'REGION' constant-enum discriminator column city_REGION, AND the ${optionalVarPlaceHolderOperationSelector(...)}/enumMap_... freemarker where-clause — a whole union+enum-param parity workstream, not S.

</details>

**Citation issues found in review** — Cosmetic only: the prose calls the union members 'AddressSet1 + AddressSet2' — those are the TABLE names; the set ids in the mapping are set1/set2 (testUnion.pure:1917/1922). Nothing load-bearing depends on it.

**Risk** — The resolver change is additive — it only fires where the code currently throws, so nothing that resolves today changes. Slight semantic choice: taking branch 0's physical type when branches disagree on width (e.g. VARCHAR(200) vs VARCHAR(100)); the engine appears to do the same, but that assumption is worth pinning with a comment. Tenet-2 trap: do not make PlanAsserts swallow this wall or compare plan text loosely — the plan-text channel compares literally by doctrine (PlanText.java:16-25), and softening the compare in the harness would hide the union-parity gap.

**Also unblocks** — Every plan-text (executionPlan/planToString) test over a union-mapped or inheritance-mapped class hits this same missing arm — e.g. any executionPlan golden in tests/mapping/union. It does NOT unblock the execute()-based union tests (testUnionWithSinglePropertyMapping and friends), whose SQL-text mismatches are scored advisory.

**Falsifier** — Print the lowered SqlQuery for `Address.all()->filter(a|$a.type==$type)->project([a|$a.name],['name'])` under `unionMappingWithEnumerationMapping`. If the top select's `from` is NOT a `SqlSource.Subselect` whose `inner()` is a `SqlUnion` (for example if SubselectPrune has already flattened it, or the from is a Join), this diagnosis is wrong and the 't2' alias comes from somewhere else.

<details><summary>Evidence read (11 citations)</summary>

- core/src/main/java/com/legend/plan/PlanText.java:756-758 — `throw new NotImplementedException("plan: alias '" + alias + "' not resolvable to a table" + " (" + src.getClass().getSimpleName() + ")")` — the exact message including the class simple name
- core/src/main/java/com/legend/plan/PlanText.java:733-735 — `case SqlSource.Subselect sub -> { if (sub.alias().equals(alias) && sub.inner() instanceof SqlSelect is) {` — the SqlSelect-only guard that a union inner fails
- core/src/main/java/com/legend/plan/PlanText.java:692-709 — `resolveStarColumn`'s Subselect arm is likewise `if (ss.inner() instanceof SqlSelect is)` with no union branch
- core/src/main/java/com/legend/sql/SqlQuery.java:11 — `public sealed interface SqlQuery permits SqlSelect, SqlUnion` — a Subselect's inner is one of exactly these two, so the union case is a real, reachable hole
- core/src/main/java/com/legend/lowering/Lowerer.java:461-462 — `case TypedConcatenate c -> SqlSelect.starOf(new SqlSource.Subselect(union(c), nextAlias(), null));` — the union-mapped extent becomes exactly Subselect(SqlUnion, tN)
- core/src/main/java/com/legend/lowering/Lowerer.java:283-285 — `private String nextAlias() { return "t" + aliasCounter++; }` — the source of the alias 't2' in the message
- core/src/main/java/com/legend/plan/PlanText.java:528-535 — `resultColumns` walls separately on a union at the TOP (`plan: non-select top query (union) pending`), confirming union-in-subselect was simply never covered
- core/src/main/java/com/legend/plan/PlanText.java:579-596 — resultColumns calls `resolvePhysical(s.from(), c.table(), strip(c.name()))` for every plain-column projection: the call site that throws
- corpus tests/mapping/union/testUnion.pure:392-397 — the test body and the golden, which spells the union subselect `"unionBase"`, projects `'city' as "city_REGION"` / `'REGION' as "city_REGION"` discriminators and `pk_0_0`/`pk_0_1`, and names the shared column `AddressSet1name_AddressSet2name`
- corpus tests/mapping/union/testUnion.pure:1909-1931 — `unionMappingWithEnumerationMapping`: each member maps `type` to a CONSTANT enum value ('city' / 'REGION') through `EnumerationMapping GeographicEntityType`
- grep for `unionBase` over core/src/main/java: no hit as an emitted alias (only comments at ScanRelations.java:1464/1529 and StoreResolver.java:2029) — legend-lite renames union frames to `unionalias_N`/`<table>_N` via EngineStyleH2's rename map (EngineStyleH2.java:349-375, :588)

</details>

---

## `testDefaultProjectionIsNullSafe`

| | |
|---|---|
| family | `tests/query` |
| sweep status | FAIL |
| **verdict** | **REAL DEFECT** |
| effort | M |
| confidence | high |

**Root cause**

`NullSemantics.equalNullArms` is the only place legend-lite can emit `SqlFn.NULL_SAFE_EQUAL` for an `==`, and it fires ONLY when `FILTER_POS` is true (NullSemantics.java:117-124). `FILTER_POS` is set exclusively around a filter predicate (`Lowerer.filter`, Lowerer.java:1208). The test's `==` sits in a PROJECTION column (`col(p|$p.age == $p.manager.age, 'match')`), so `FILTER_POS` is false and the rule falls through to `new SqlExpr.Call(SqlFn.EQUAL, ops)` at NullSemantics.java:125. The plan text therefore reads `"root".AGE = "persontable_1".AGE as "match"` and `$planString->contains('... is not distinct from ...')` is false. legend-lite modelled only the engine's render-layer `callingFromFilter` mechanism (dbExtension.pure:927-930) and never modelled the router-layer `nullSafeEqualsOperation` (pureToSQLQuery.pure:7928-7945), which is position-independent and fires here because BOTH operands have multiplicity lower bound 0 (`Person.age : Integer[0..1]`, `Person.manager : Person[0..1]` — simpleTestModel.pure:167-168, so `$p.manager.age` is also [0..1]). The rendering side is already correct and present: `EngineStyleH2.nullSafeSpelling` (EngineStyleH2.java:947-951) spells `NULL_SAFE_EQUAL` as ` is not distinct from `; nothing but the lowering decision is missing.

**Fix**

In `core/src/main/java/com/legend/lowering/NullSemantics.java`, extend `equalNullArms(TypedNativeCall n, List<SqlExpr> ops)` to add the engine's router-layer arm AFTER the existing FILTER_POS arm (leave that arm exactly as-is — it is the faithful model of dbExtension.pure:927-930 and is what keeps execution-path filter rows correct):

```java
static SqlExpr equalNullArms(TypedNativeCall n, List<SqlExpr> ops) {
    if (FILTER_POS.get() && ops.size() == 2
            && ops.get(0) instanceof SqlExpr.Column
            && ops.get(1) instanceof SqlExpr.Column
            && isOptional(n.args().get(0).info().multiplicity())
            && isOptional(n.args().get(1).info().multiplicity())) {
        return new SqlExpr.Call(SqlFn.NULL_SAFE_EQUAL, ops);
    }
    // pureToSQLQuery.pure:7928-7945 nullSafeEqualsOperation — position
    // INDEPENDENT. Engine-text surfaces only: relationalMappingExecution
    // .pure:385 forces legacyNullUnsafeEquals=true for in-flow execute.
    if (ops.size() == 2 && EngineTextBoundary.active()
            && !QueryFeatureFlags.legacyNullUnsafeEquals()
            && (isOptionalPlanParam(ops.get(0)) || isOptionalPlanParam(ops.get(1))
                || (isOptional(n.args().get(0).info().multiplicity())
                    && isOptional(n.args().get(1).info().multiplicity())))) {
        return new SqlExpr.Call(SqlFn.NULL_SAFE_EQUAL, ops);
    }
    return new SqlExpr.Call(SqlFn.EQUAL, ops);
}
private static boolean isOptionalPlanParam(SqlExpr e) {
    return e instanceof SqlExpr.PlanParam p && p.optional();
}
```
(`isOptional` already exists at NullSemantics.java:57.) `QueryFeatureFlags` is the new thread-scoped holder introduced by diagnosis 2 — it must exist before this lands, otherwise `testLegacyFlagProjectionEmitsPlainEquals` regresses. Deliberately do NOT port the three `leftIsEmptyLiteral`/`rightIsEmptyLiteral` arms of `nullSafeEqualsOperation` in this change: they concern `x == []`, are handled elsewhere in legend-lite, and widening scope here risks unrelated goldens. Gate strictly on `EngineTextBoundary.active()` so that DuckDB execution SQL keeps plain `=` and row results are unchanged.

**How legend-engine does it** — /Users/neemsandv/legend/legend-engine/legend-engine-xts-relationalStore/legend-engine-xt-relationalStore-generation/legend-engine-xt-relationalStore-pure/legend-engine-xt-relationalStore-core-pure/src/main/resources/core_relational/relational/pureToSQLQuery/pureToSQLQuery.pure:7928 (`nullSafeEqualsOperation`), called unconditionally at :7898 for every non-enum `==`

**Risk** — Tenet-2 trap: do NOT reach into the harness/PlanText to rewrite `=` into `is not distinct from` for the projection alias — the operator choice belongs to lowering. Second trap: do NOT drop the `EngineTextBoundary.active()` gate. Without it, DuckDB execution SQL would start emitting `IS NOT DISTINCT FROM` for every nullable-vs-nullable projection and filter, changing ROWS for the whole corpus, whereas real legend-engine's in-flow execute is legacy plain-equals (relationalMappingExecution.pure:385). Third: `optionalOperandGuards` is a different mechanism (inlined [0..1] comparison-overload bodies) and must not be conflated with this arm.

**Also unblocks** — testNullSafeEqualityForOptionalProperties [transform/fromPure/tests] — same projection, via toSQLString; this fix removes its `=` vs `is not distinct from` diff. It will NOT make that test pass on its own: it has a second, independent join-alias defect (golden `"personTable_d#5_d#2_m1_d_m2"` vs legend-lite `"persontable_1"`, per docs/RELATIONAL_CORPUS.md).

**Falsifier** — Run `testDefaultProjectionIsNullSafe` with `LL_TMP_DEBUG=1` and read the printed plan text. If the actual plan already contains `is not distinct from` but the assert still fails, the defect is elsewhere (plan-text framing / alias) and this diagnosis is wrong. Equally: if `n.args().get(1).info().multiplicity()` for `$p.manager.age` is NOT `Bounded(0,1)` (e.g. it types as [*]), the multiplicity arm will not fire and the fix as written is inert — check by printing the two multiplicities at the equal rule.

<details><summary>Evidence read (14 citations)</summary>

- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/lowering/NullSemantics.java:117 — `if (FILTER_POS.get() && ops.size() == 2 && ops.get(0) instanceof SqlExpr.Column && ...)` guards the only NULL_SAFE_EQUAL emission for `equal`
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/lowering/NullSemantics.java:125 — fall-through is `return new SqlExpr.Call(SqlFn.EQUAL, ops);`
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/lowering/NullSemantics.java:100 — `private static final ThreadLocal<Boolean> FILTER_POS = ThreadLocal.withInitial(() -> Boolean.FALSE);`
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/lowering/Lowerer.java:1208 — `try (var ignored = NullSemantics.enterFilter()) {` appears only inside `filter(TypedFilter f)`; no projection path enters it
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/lowering/Scalars.java:114 — the catalog `equal`/`eq` rule ends `return NullSemantics.equalNullArms(n, args);`, so projections lower through this rule
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/sql/dialect/EngineStyleH2.java:948 — `bc.fn() == SqlFn.NULL_SAFE_EQUAL ? " is not distinct from " : " is distinct from "` (rendering already correct)
- /Users/neemsandv/legend/legend-engine/legend-engine-xts-relationalStore/legend-engine-xt-relationalStore-generation/legend-engine-xt-relationalStore-pure/legend-engine-xt-relationalStore-core-pure/src/main/resources/core_relational/relational/pureToSQLQuery/pureToSQLQuery.pure:7942 — `pair(|$leftParam.multiplicity->getLowerBound() == 0 && $rightParam.multiplicity->getLowerBound() == 0, |^DynaFunction(name = 'nullSafeEqual', ...))`
- /Users/neemsandv/legend/legend-engine/legend-engine-xts-relationalStore/legend-engine-xt-relationalStore-generation/legend-engine-xt-relationalStore-pure/legend-engine-xt-relationalStore-core-pure/src/main/resources/core_relational/relational/pureToSQLQuery/pureToSQLQuery.pure:7898 — the non-enum arm is `nullSafeEqualsOperation($leftParam, $rightParam, $leftVal, $rightVal)` regardless of `$state.inFilter`
- /Users/neemsandv/legend/legend-engine/legend-engine-xts-relationalStore/legend-engine-xt-relationalStore-generation/legend-engine-xt-relationalStore-pure/legend-engine-xt-relationalStore-core-pure/src/main/resources/core_relational/relational/tests/testModel/simpleTestModel.pure:167 — `manager : Person[0..1];` and :168 `age : Integer[0..1];`
- /Users/neemsandv/legend/legend-engine/legend-engine-xts-relationalStore/legend-engine-xt-relationalStore-generation/legend-engine-xt-relationalStore-pure/legend-engine-xt-relationalStore-core-pure/src/main/resources/core_relational/relational/transform/fromPure/tests/testToSQLString.pure:780 — the toSQLString golden for the SAME projection expects `"root".AGE is not distinct from ... as "match"`, confirming projection-position null-safety is engine-wide, not plan-only
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/docs/RELATIONAL_CORPUS.md:1397 — `FAIL testDefaultProjectionIsNullSafe [tests/query]: assert did not hold (false)`; the sibling `testLegacyFlagProjectionEmitsPlainEquals` (which pins the exact alias spelling `"root".AGE = "persontable_1".AGE as "match"`) is absent from the failure list, so only the operator differs
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/lowering/EngineTextBoundary.java:25 — `public static boolean active()`; set only by the plan/toSQLString funnel
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/StatementExecutor.java:457 — `try (var ignored = com.legend.lowering.EngineTextBoundary.enter(); ...) { plan = lw.lower(body); }` inside `engineSql` (the shared plan/toSQLString funnel)
- /Users/neemsandv/legend/legend-engine/legend-engine-xts-relationalStore/legend-engine-xt-relationalStore-generation/legend-engine-xt-relationalStore-pure/legend-engine-xt-relationalStore-core-pure/src/main/resources/core_relational/relational/relationalMappingExecution.pure:385 — in-flow execution hardcodes `^$sqlGenerationState(legacyNullUnsafeEquals = true)`, so the EXECUTION path must keep plain `=`

</details>

---

## `testLegacyFlagRestoresOptionalParamFreeMarkerSelector`

| | |
|---|---|
| family | `tests/query` |
| sweep status | FAIL |
| **verdict** | **REAL DEFECT** |
| effort | M |
| confidence | high |

**Root cause**

`meta::pure::executionPlan::featureFlag::withFeatureFlags` is treated as a pure IDENTITY in legend-lite and its SECOND argument (the flag enum) is thrown away at both places it is peeled: `StoreResolver.resolveNode` (StoreResolver.java:291-295) returns `resolveNode(wf.args().get(0), context)` and `StatementExecutor.buildFrame` (StatementExecutor.java:2091-2093) does `q = letBound(pv.args().get(0), letPrefix)`. Real pure's `withFeatureFlags` body is indeed `$object` (executionPlanFeature.pure:28-31), but the flag is NOT inert: the routing extension `ExecutionPlanFeatureFlagExtension` reads `$fe.parametersValues->at(1)`, reifies it into a `FeatureFlagOption` on the ExecutionContext, and `defaultState` then sets `State.legacyNullUnsafeEquals` from `contextHasFlag(... LEGACY_SQL_NULL_UNSAFE_EQUALS)` (pureToSQLQuery.pure:338). Because legend-lite never captures the flag, nothing downstream can behave legacily. Concretely, for `{age:Integer[0..1]| ... filter(p|$p.age == $age) ...}` the optional plan parameter reaches `EngineStyleH2.optionalParamEquality`, whose single-optional branch takes the legacy FreeMarker selector ONLY when `opt.kind() == Kind.DATE` (EngineStyleH2.java:555); Integer maps to `Kind.OTHER` (Fold.java:54), so control falls to EngineStyleH2.java:566-568 and returns `nullSafeEq(...)` = ` is not distinct from `. `optionalVarPlaceHolderOperationSelector` never appears, and the assert is false. The correct discriminator is the feature flag, not the parameter's SQL kind — legend-lite already has the exact selector spelling it needs (EngineStyleH2.java:536-543 for two optionals, :561-563 for one), it is just wired to the wrong condition.

**Fix**

Three coordinated edits.

1) New `core/src/main/java/com/legend/lowering/QueryFeatureFlags.java`, modelled exactly on `EngineTextBoundary` (same thread-scoped Scope/enter idiom, so the precedent is one, not two):
```java
public final class QueryFeatureFlags {
    private static final ThreadLocal<java.util.Set<String>> FLAGS =
            ThreadLocal.withInitial(java.util.Set::of);
    public interface Scope extends AutoCloseable { @Override void close(); }
    public static Scope enter(java.util.Set<String> flags) {
        var prev = FLAGS.get(); FLAGS.set(flags); return () -> FLAGS.set(prev);
    }
    public static boolean legacyNullUnsafeEquals() {
        return FLAGS.get().contains("LEGACY_SQL_NULL_UNSAFE_EQUALS");
    }
}
```

2) Capture the flags where they are currently discarded. Add a static scan (a `featureFlagsIn(List<TypedSpec>)` helper next to `StatementExecutor.firstFromMapping`) that walks the pre-resolution body for a `TypedNativeCall` whose `callee().qualifiedName()` is `meta::pure::executionPlan::featureFlag::withFeatureFlags` and collects the value names of arg 1 when it is a `TypedEnumValue` (or a `TypedCollection` of them) of `meta::pure::executionPlan::features::Feature`. Wrap `StatementExecutor.engineSql`'s existing try-with-resources at StatementExecutor.java:457 with `QueryFeatureFlags.enter(featureFlagsIn(body))`, alongside `EngineTextBoundary.enter()`. Leave `StoreResolver.resolveNode`'s peel (StoreResolver.java:291-295) and `buildFrame`'s peel (StatementExecutor.java:2091-2093) exactly as they are — they stay identity for value semantics; only the capture is added.

3) In `EngineStyleH2`, replace the KIND gates with the FLAG gate. Add a `legacyNullUnsafeEquals` field set from a new 4th constructor parameter, threaded through `StatementExecutor.planDialect` (StatementExecutor.java:1157-1169) — both `EngineStyleH2` and `EngineStyleDB2` constructors. Then in `optionalParamEquality`:
  - two-optional branch (EngineStyleH2.java:534): change `if (lp2.kind() == Kind.DATE || lp2.kind() == Kind.DATETIME)` to `if (legacyNullUnsafeEquals)` — the nested-selector spelling at :536-543 is already the exact engine form from pureToSQLQuery.pure:7949-7950.
  - single-optional branch (EngineStyleH2.java:555): change `if (opt.kind() == Kind.DATE)` to `if (legacyNullUnsafeEquals)` — the spelling at :561-563 already matches pureToSQLQuery.pure:7951-7955.
Keep `holderEscaped`'s DATE-style DATETIME spelling (EngineStyleH2.java:504-515) unchanged; it is only reachable from the legacy branches now.

This must land in the same change as diagnosis 1, otherwise `testLegacyFlagProjectionEmitsPlainEquals` (currently passing only because legend-lite always emits `=`) regresses.

**How legend-engine does it** — /Users/neemsandv/legend/legend-engine/legend-engine-xts-relationalStore/legend-engine-xt-relationalStore-generation/legend-engine-xt-relationalStore-pure/legend-engine-xt-relationalStore-core-pure/src/main/resources/core_relational/relational/pureToSQLQuery/pureToSQLQuery.pure:7947 (`legacyNullUnsafeEqualsOperation`), selected at :7896 by `$state.legacyNullUnsafeEquals`, which is set at :338 from the feature flag

**Risk** — Flipping the two-optional DATE/DATETIME/FLOAT/STRING and single-optional DATE spellings from the legacy selector to `is not distinct from` by default changes plan text for meta::pure::executionPlan::tests::testFilterEqualsWithOptionalParameterDate / testFilterEqualsWithTwoOptionalParameters{Float,Date,String,DateTime}. Those all assert via 3-arg `assertEqualsH2Compatible`, and PlanAsserts.planTextAssert (PlanAsserts.java:176-181) accepts EITHER golden — the new-H2 goldens (executionPlanTest.pure:308, :473) are exactly the null-safe form, so they should stay green. Verify no OTHER corpus test asserts an optional-parameter equality with a SINGLE golden pinning the selector; grep the corpus for `optionalVarPlaceHolderOperationSelector` gives only executionPlanTest.pure, testUnion.pure (enum selector — a different code path, `enumSelector`, untouched) and testLegacyNullUnsafeEquals.pure. Tenet-2 trap: do NOT teach the harness to special-case `withFeatureFlags` when building the plan-text assert — the flag is query knowledge owned by the compiler pipeline.

**Also unblocks** — None additional, but it is a hard PREREQUISITE for diagnosis 1: without it, fixing diagnosis 1 regresses the currently-green testLegacyFlagProjectionEmitsPlainEquals.

**Falsifier** — Print the plan text for this test. If it already contains `optionalVarPlaceHolderOperationSelector` but the assert still fails, the diagnosis is wrong. Cheaper still: confirm the sibling `testDefaultOptionalParamIsNullSafe` (same query WITHOUT the flag, asserting the selector is ABSENT and `is not distinct from` is PRESENT) is not in docs/RELATIONAL_CORPUS.md's failure list — it is not, which proves legend-lite emits the null-safe form for this exact parameter and therefore that only the flag branch is missing.

<details><summary>Evidence read (14 citations)</summary>

- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/resolver/StoreResolver.java:290 — comment `// withFeatureFlags = IDENTITY (executionPlanFeature.pure:27)` then :294 `return resolveNode(wf.args().get(0), context);` — arg 1 (the flags) is dropped
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/StatementExecutor.java:2091 — `|| "meta::pure::executionPlan::featureFlag::withFeatureFlags".equals(pv.callee().qualifiedName())) { q = letBound(pv.args().get(0), letPrefix); }`
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/builtin/Pure.java:1574 — `// withFeatureFlags (REAL executionPlanFeature.pure:27): IDENTITY` and :1576 the signature-only native declaration
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/builtin/Pure.java:658 — `// withFeatureFlags is IDENTITY in real pure (:27 — the flag rides the plan context; our enum source-value translation IS the pushdown).` — the flag is documented as discarded
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/sql/dialect/EngineStyleH2.java:555 — `if (opt.kind() == SqlExpr.PlanParam.Kind.DATE) {` gates the legacy selector on parameter KIND
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/sql/dialect/EngineStyleH2.java:561 — the selector spelling `"(${optionalVarPlaceHolderOperationSelector(" + opt.name() + "![], '" + present + "', '" + otherEsc + " is null')})"` already exists and matches the engine's one-sided legacy form
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/sql/dialect/EngineStyleH2.java:566 — the non-DATE fall-through `return opt == l ? nullSafeEq(holder(opt), otherTx) : nullSafeEq(otherTx, holder(opt));`
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/lowering/Fold.java:54 — `return com.legend.sql.SqlExpr.PlanParam.Kind.OTHER;` (Integer falls here)
- /Users/neemsandv/legend/legend-engine/legend-engine-core/legend-engine-core-pure/legend-engine-pure-code-compiled-core/src/main/resources/core/pure/executionPlan/executionPlanFeature.pure:28 — `function meta::pure::executionPlan::featureFlag::withFeatureFlags<T>(object:T[*],e:Enum[*]):T[*] { $object; }` — identity for VALUES only
- /Users/neemsandv/legend/legend-engine/legend-engine-core/legend-engine-core-pure/legend-engine-pure-code-compiled-core/src/main/resources/core/pure/executionPlan/executionPlanFeature.pure:62 — `ExecutionPlanFeatureFlagExtension` routes the expression, reads `$fe.parametersValues->at(1)` and calls `addFlagToContext`
- /Users/neemsandv/legend/legend-engine/legend-engine-xts-relationalStore/legend-engine-xt-relationalStore-generation/legend-engine-xt-relationalStore-pure/legend-engine-xt-relationalStore-core-pure/src/main/resources/core_relational/relational/pureToSQLQuery/pureToSQLQuery.pure:338 — `legacyNullUnsafeEquals = $exeCtx->toOne()->contextHasFlag(Feature.LEGACY_SQL_NULL_UNSAFE_EQUALS)`
- /Users/neemsandv/legend/legend-engine/legend-engine-xts-relationalStore/legend-engine-xt-relationalStore-generation/legend-engine-xt-relationalStore-pure/legend-engine-xt-relationalStore-core-pure/src/main/resources/core_relational/relational/pureToSQLQuery/pureToSQLQuery.pure:7951 — `legacyNullUnsafeEqualsOperation`'s one-sided arm `^FreeMarkerOperationHolder(name = 'optionalVarPlaceHolderOpSelector', parameters = [$rightVal, equal($leftVal,$rightVal), isNull($leftVal)])`
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/StatementExecutor.java:1157 — `private static com.legend.sql.dialect.EngineStyleH2 planDialect(String dbType, boolean quote, String tz)` — the single construction point for the plan dialect, where a legacy flag can be threaded
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/harness/PlanAsserts.java:177 — for a 3-arg `assertEqualsH2Compatible`, EITHER golden (`args.get(0)` legacy-H2 or `args.get(1)` new-H2) satisfies the compare, so flipping the DATE/DATETIME optional-param spellings to null-safe does not break executionPlanTest's optional-parameter goldens

</details>

---

## `testPushDownProjectWithParameter`

| | |
|---|---|
| family | `tests/query` |
| sweep status | ERROR |
| **verdict** | **MISSING FEATURE** |
| effort | L |
| confidence | medium |

**Root cause**

The whole `meta::json` platform library is unported in legend-lite. The test's final statement is `$resultJson->meta::json::parseJSON()->cast(@JSONObject).keyValuePairs->filter(kv|$kv.key.value == 'result').value->toOne()->toCompactJSONString()`. `JsonAssertCanon.isPlumbing` only recognises chains built solely from `parseJSON`/`toPrettyJSONString`/`toOne`/`cast` bottoming at a Variable (JsonAssertCanon.java:95-114); this chain contains an `AppliedProperty` (`keyValuePairs`) and a `filter`, so it is not plumbing, `ConnEquality.letFold` (ConnEquality.java:70-76) falls through to eager evaluation, and the typer reaches `Typer.checkGeneric`, finds zero catalog candidates for `meta::json::parseJSON`, and throws the reported message from Typer.java:1448-1452. Grepping the whole legend-lite main source for `meta::json::` returns nothing: there is no `parseJSON` native, no `JSONElement`/`JSONObject`/`JSONKeyValue`/`JSONString` class hierarchy, and no `toCompactJSONString`. The wall is honest and precisely names the absent surface. A SECOND, independent gap sits behind it: even with `meta::json` ported, `$resultJson` would not be JSON. `ElqSplice.splice` binds the executeLegendQuery result as `toString(<final expression>)` and only wraps a JSON envelope when the query chain contains `serialize` (ElqSplice.java:90-103). This query's terminal is `project(...)->from(mapping, runtime)`, so no envelope is produced — whereas real `meta::legend::executeLegendQuery` (devUtils.pure:35-40) returns the full result JSON with a `result` key, whose shape the corpus pins elsewhere as `"result" : {"columns" : [...], "rows" : [{"values": [...]}]}` (tests/mapping/relation/tests.pure:1350).

**Fix**

Two pieces, in this order.

(A) Port the `meta::json` surface into the platform, not the harness. Add the JSON metamodel classes to the builtin model alongside the other native declarations in `core/src/main/java/com/legend/builtin/Pure.java` (same `nativeEnum`-style source-text idiom, using the class form): `meta::json::JSONElement`, `JSONBoolean{value:Boolean[1]}`, `JSONString{value:String[1]}`, `JSONNumber{value:Number[1]}`, `JSONNull{value:Nil[0]}`, `JSONArray{values:JSONElement[*]}`, `JSONObject{keyValuePairs:JSONKeyValue[*]}`, `JSONKeyValue{key:JSONString[1], value:JSONElement[1]}` — copy the declarations verbatim from core_functions_json/json.pure:32-70. Declare `native function meta::json::parseJSON(string:String[1]):meta::json::JSONElement[1];` and implement it HOST-side (there is already a JSON parser at `core/src/main/java/com/legend/sql/Json.java`, whose javadoc at :44 documents real-pure `parseJSON` leading-value semantics) so it materialises the class instances rather than raw Maps. Add `meta::json::toCompactJSONString(JSONElement[1]):String[1]` as a host native mirroring core/external/format/json/json.pure:62-75. This gives `cast(@JSONObject)`, `.keyValuePairs`, `.key.value`, `filter`, `.value`, `toOne()` for free through the ordinary object pipeline — no new harness recognisers.

(B) Make `ElqSplice` bind the engine's real envelope. In `ElqSplice.splice` (ElqSplice.java:87-105), replace the serialize-only special case with a result-shape switch: when the query's terminal type is a relation/TDS, bind `{"builder":{"_type":"tdsBuilder"},"activities":[],"result":{"columns":[<names>],"rows":[{"values":[...]},...]}}` built from the executed TDS; keep the existing `{"builder":{"_type":"json"},"values":...}` form for serialize; keep bare `toString` for scalars (testPlatformOperationsOnRelational asserts `'true'`/`'false'`, so the scalar form must not change). The `result` sub-object must serialise exactly as `{"columns":["syntype","name"],"rows":[{"values":["CUSIP","CUSIP1"]},…]}` since the test round-trips it through toCompactJSONString. This envelope belongs in the platform result-serialisation layer, not in ElqSplice's string concatenation — prefer adding it next to the existing TDS JSON writer at `core/src/main/java/com/legend/protocol/ProtocolEmitter.java:531` / `TailEmitter.java:163` and have ElqSplice call it.

If (A)+(B) is judged too large for this cycle, the honest alternative is to LEDGER it and leave the wall: it is loud, correctly attributed, and names the exact unported surface. Do NOT close it by adding a `parseJSON`-chain recogniser to JsonAssertCanon — that is harness compensation for a platform library.

**How legend-engine does it** — /Users/neemsandv/legend/legend-engine/legend-engine-core/legend-engine-core-pure/legend-engine-pure-code-functions-json/legend-engine-pure-functions-json-pure/src/main/resources/core_functions_json/json.pure:19 and :32-70 (parseJSON native + JSONElement hierarchy); /Users/neemsandv/legend/legend-engine/legend-engine-core/legend-engine-core-pure/legend-engine-pure-code-compiled-core/src/main/resources/core/external/format/json/json.pure:62 (toCompactJSONString)

**Risk** — Introducing `meta::json::*` classes into the builtin model widens the eager-Knowledge manifest; keep them declaration-only (Tier-1) so nothing forces a transitive load. Changing ElqSplice's binding for relation-typed terminals will alter every other executeLegendQuery test's `$result` string — testPlatformOperationsOnRelational (scalar Boolean terminals) and the modelToModelToRelational/milestoned family must be re-checked, since several of them assert on the raw `$result` string. Tenet-2 trap: the envelope is the platform's result-serialisation contract; do not synthesise it as a literal string inside the harness assert path.

**Also unblocks** — Any other corpus test whose assert navigates the executeLegendQuery envelope through meta::json — the same two pieces unblock them. Within the relational corpus the executeLegendQuery users are tests/mapping/relation/tests.pure:1346, tests/platformOperations/testPlatformOperationsOnRelational.pure and the modelToModelToRelational/milestoned family; only piece (B) touches those, and I have not verified their current pass/fail state, so treat this as unconfirmed.

**Falsifier** — Add only `meta::json::parseJSON` + the JSON classes and re-run. If the test then fails with a JSON-parse error or `no key 'result'` rather than passing, piece (B) — the missing executeLegendQuery TDS envelope — is confirmed as the second gap and the effort estimate holds. If instead it passes, the envelope is already produced somewhere I did not find and effort drops to M. Independently: check whether the sibling `testPushDownProject` (same enum push-down, no parameter, no JSON) passes — it is absent from docs/RELATIONAL_CORPUS.md's failure list, which is evidence that the enum source-value push-down itself is sound and the failure is purely the JSON/envelope surface.

<details><summary>Evidence read (12 citations)</summary>

- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/compiler/spec/Typer.java:1448 — `throw new TypeInferenceException("unknown function '" + af.function() + "' — no function of this name in the native or user catalog (unported platform function, or a misspelling)");` reached when `functionCandidates(af).isEmpty()` at :1443
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/harness/JsonAssertCanon.java:95 — `static boolean isPlumbing(ValueSpecification v)` accepts only parseJSON/toPrettyJSONString/toOne (1-arg) and cast (2-arg) wrappers, returning false on any other node (:107 `return false;`)
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/harness/ConnEquality.java:70 — `if (JsonAssertCanon.isPlumbing(rhs)) { return rhs; }` then :72 `Boolean hf = tryEval(substituted, ctx, imports);` — the non-plumbing let is evaluated (and therefore typed)
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/harness/ElqSplice.java:90 — `ValueSpecification bound = new AppliedFunction("toString", List.of(fin));` and :96 `if (headChainContains(fin, "serialize")) { ... }` — the JSON envelope is built ONLY for serialize queries
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/harness/ElqSplice.java:99 — the only envelope literal in legend-lite is `{"builder":{"_type":"json"},"values":` … `}`; there is no tdsBuilder/`result` envelope anywhere (grep for `\"result\"` over core/src/main/java finds only PureLspServer.java:259 and unrelated SQL column aliases)
- /Users/neemsandv/legend/legend-engine/legend-engine-core/legend-engine-core-pure/legend-engine-pure-code-functions-json/legend-engine-pure-functions-json-pure/src/main/resources/core_functions_json/json.pure:19 — `native function meta::json::parseJSON(string:String[1]):meta::json::JSONElement[1];`
- /Users/neemsandv/legend/legend-engine/legend-engine-core/legend-engine-core-pure/legend-engine-pure-code-functions-json/legend-engine-pure-functions-json-pure/src/main/resources/core_functions_json/json.pure:61 — `Class meta::json::JSONObject extends JSONElement { <<equality.Key>> keyValuePairs : JSONKeyValue[*]; }` and :66 `Class meta::json::JSONKeyValue { key : JSONString[1]; value : JSONElement[1]; }`
- /Users/neemsandv/legend/legend-engine/legend-engine-core/legend-engine-core-pure/legend-engine-pure-code-compiled-core/src/main/resources/core/external/format/json/json.pure:62 — `function meta::json::toCompactJSONString(json:JSONElement[1]):String[1]` (a plain Pure match over the JSONElement hierarchy)
- /Users/neemsandv/legend/legend-engine/legend-engine-core/legend-engine-core-pure/legend-engine-pure-code-compiled-core/src/main/resources/core/legend/tools/devUtils.pure:35 — `function meta::legend::executeLegendQuery(f, vars, exeCtx, extensions): String[1]` delegating to `meta::legend::execute_...`, whose contract is the serialized result JSON
- /Users/neemsandv/legend/legend-engine/legend-engine-xts-relationalStore/legend-engine-xt-relationalStore-generation/legend-engine-xt-relationalStore-pure/legend-engine-xt-relationalStore-core-pure/src/main/resources/core_relational/relational/tests/mapping/relation/tests.pure:1350 — another corpus test pins the envelope: `$result->contains('"result" : {"columns" : ["ID","age","name"], "rows" : [{"values": [1,23,"Peter"]}, ...')`
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/docs/RELATIONAL_CORPUS.md:1391 — `ERROR testPushDownProjectWithParameter [tests/query]: unknown function 'meta::json::parseJSON' …`
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/test/java/com/legend/rcorpus/Runner.java:470 — `case "ToFix", "Ignore", "ExcludeAlloy" -> excluded = true;` — `test.AlloyOnly` is NOT excluded, so this test genuinely runs

</details>

---
