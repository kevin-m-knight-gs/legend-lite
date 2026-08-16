# Bucket 11 — Unclassified one-offs

11 tests from the ledger; **9 still non-passing** at `9d1f2cd0`. 2 now pass (fixed upstream since the 2026-08-14 sweep) and are marked below.

Each entry was produced by an agent that read the test's `.pure` body, grepped the failure message back to the emitting legend-lite code, read that method's control flow, and checked semantics against real legend-engine / legend-pure sources. Citations were mechanically verified to resolve.

Verdicts: MISSING FEATURE 5, REAL DEFECT 4, NEEDS PROBE 2

---

## `testExistsWithEmbeddedWithPostProcessor`

| | |
|---|---|
| family | `functions/tests` |
| sweep status | ERROR |
| **verdict** | **REAL DEFECT** |
| effort | M |
| confidence | high |

**Root cause**

The corpus builds the connection hook as `^$conn(sqlQueryPostProcessors=[{query|$query->meta::relational::postProcessor::postprocess({rel|$rel})}])`. That copy-instance is the value of a `let`, and `StatementExecutor.executeStatements` runs an EFFECT SCAN over every non-execute let value before binding it. `containsEffect` is not a syntactic scan: for every `TypedUserCall` it reaches it calls `specs.compile(uc.callee())` and then recurses over the compiled body — i.e. it whole-graph type-checks the callee closure. It therefore compiles the corpus's `postprocess` (fine), then its callee `transformNonCached`, whose match arms name relational-metamodel classes. `Typer.namedType` resolves `@ViewSelectSQLQuery` through `ctx.findType` and legend-lite's hand-authored native prelude has no such class, so G throws and `SpecCompiler.compile` decorates it with the `in function 'meta::relational::postProcessor::transformNonCached'` prefix (exactly one prefix = transformNonCached was the outermost compile, which is what the callee-recursion in containsEffect produces). The post-processor hook is CONFIG that legend-lite deliberately never evaluates as Pure (it recognizes hook shapes and rewrites its own SQL IR); compiling its body at all is the defect. Two further leaks sit behind it: (a) `SqlPostProcessors.collectConnections` only inspects `TypedNewInstance.properties()`, so a hook attached via `^$conn(...)` (a `TypedCopyInstance`) is silently invisible — the very false-green the D2-4 note in that file swore off; (b) `readHook` recognizes only `replaceTables`, so the identity `postprocess` shape has no arm.

**Fix**

Three edits, all platform-side. (1) Introduce one predicate for the connection's post-processor CONFIG property names — {"sqlQueryPostProcessors", "sqlQueryPostProcessorsConnectionAware", "queryPostProcessorsWithParameter"} (put it next to the existing configMode logic, e.g. a static `isPostProcessorConfig(String)` on `SqlPostProcessors` or a small `PostProcessorConfig` helper). (2) `UserCallInliner.rewrite` (core/src/main/java/com/legend/compiler/spec/UserCallInliner.java:340-361): change the guard from `ni.properties().containsKey("queryPostProcessorsWithParameter")` to "any property whose name isPostProcessorConfig", enter configMode for each such property, and add the mirror arm for `TypedCopyInstance` over `overrides()` — hook bodies are opaque metamodel programs, never query code. (3) `StatementExecutor.containsEffect` (:2911): before the generic `for (TypedSpec c : node.children())` recursion, when `node` is a `TypedNewInstance`/`TypedCopyInstance`, skip the values of post-processor config properties — a post-processor hook is a plan-time SQL rewrite and can carry no DDL/executeInDb effect; scanning it is what compiles `transformNonCached`. That alone clears the reported error. To make the test actually pass and to close the silent-drop hole: (4) `SqlPostProcessors.collectConnections` must also read `TypedCopyInstance.overrides()` (same two property names), and `readHook` must gain one recognized shape besides replaceTables — a `TypedUserCall` to `meta::relational::postProcessor::postprocess` whose second argument is the IDENTITY lambda (one parameter, body is exactly a `TypedVariable` of that parameter) contributes nothing to the rename map, matching the engine's identity transform; any other node function keeps the existing loud NotImplementedException. Do NOT 'fix' this by adding ViewSelectSQLQuery to Pure.java: the next arms of the same match need Operation/UnaryOperation/BinaryOperation/VariableArityOperation/SemiStructuredPropertyAccess/SemiStructuredArrayElementAccess, and after all of them the inliner would be β-reducing a Pure metamodel traversal over a `$query` value legend-lite never materializes — it would end at the loud readHook wall anyway.

**How legend-engine does it** — legend-pure/legend-pure-store/legend-pure-store-relational/legend-pure-m2-store-relational-pure/src/main/resources/platform_store_relational/grammar/relational.pure:270 — `Class meta::relational::metamodel::relation::ViewSelectSQLQuery extends Table`; legend-engine/…/core_relational/relational/postprocessor/postProcessor.pure:313 is the `v:ViewSelectSQLQuery[1]` match arm inside `transformNonCached`, and :86-89 is `postprocess(s,f) = ^Result<SelectSQLQuery|1>(values=$s->transformNonCached($f)->cast(@SelectSQLQuery))` — with the identity node function `{rel|$rel}` the transform rebuilds a structurally equal tree, i.e. a semantic no-op, which is why the test's expected SQL is byte-identical to testExistsWithEmbedded's.

**Risk** — Edit (4) turns hooks attached via `^$conn(...)` from invisible into visible; any corpus test that currently 'passes' because its copy-instance hook was silently dropped will now hit the loud recognizer wall. That is the correct direction per tenets (a loud wall beats a false green) but it can flip currently-green tests to ERROR, so land (4) with a sweep. Edit (2) makes user calls STAND inside sqlQueryPostProcessors, so a hook whose replaceTables pairs come from a user helper would no longer be pre-inlined for the recognizer — check the cteExtraction/replaceTables corpus shapes before landing.

**Also unblocks** — testRestrictWithPostProcessor (tds/tests/testTDSRestrict.pure:268) fails with the identical `in function 'meta::relational::postProcessor::transformNonCached': unknown type 'ViewSelectSQLQuery'` message and is the same mechanism.

**Falsifier** — If the sweep, after edit (3) alone, reports a DIFFERENT error for this test (e.g. 'unknown type Operation' or the readHook NotImplementedException) then the containsEffect path is confirmed and only the remaining edits are missing; if it still reports `in function 'transformNonCached'`, then the compile is being driven by some other caller of specs.compile (UserCallInliner:184 is the only other candidate) and the guard must move there instead.

<details><summary>Evidence read (7 citations)</summary>

- core/src/main/java/com/legend/StatementExecutor.java:2911-2943 — `containsEffect` calls `specs.compile(uc.callee())` at :2924 and recurses over the compiled body; it is invoked on every non-execute let value at :134
- core/src/main/java/com/legend/compiler/spec/SpecCompiler.java:57-74 — `compile` wraps a TypeInferenceException as "in function '<fqn>': …"; a nested compile would produce two prefixes, the observed message has one
- core/src/main/java/com/legend/compiler/spec/Typer.java:2823-2834 — `namedType` ends in `ctx.findType(name).…orElseThrow(new TypeInferenceException("unknown type '"+name+"' in @"+name))`
- core/src/main/java/com/legend/builtin/Pure.java:415-461 — the whole hand-written relational-metamodel prelude; a grep of every `native Class meta::relational::metamodel::*` in this file lists SelectSQLQuery/TdsSelectSqlQuery/Union/UnionAll/CommonTableExpression/… and NO ViewSelectSQLQuery, Operation, UnaryOperation, BinaryOperation, VariableArityOperation, SemiStructuredPropertyAccess, SemiStructuredArrayElementAccess
- core/src/main/java/com/legend/lowering/SqlPostProcessors.java:47-77 — `collectConnections` reads `ni.properties()` of a `TypedNewInstance` only; :86-100 `readHook` throws unless the lambda's last statement is a `replaceTables` native call
- core/src/main/java/com/legend/compiler/spec/typed/TypedCopyInstance.java:12-30 — `^$x(p=v)` is its own node with `overrides()`, invisible to the TypedNewInstance-only scan
- core/src/main/java/com/legend/compiler/spec/UserCallInliner.java:76-78 and :340-361 — the existing precedent: inside `queryPostProcessorsWithParameter` the inliner enters `configMode` so user calls STAND; `sqlQueryPostProcessors` / `sqlQueryPostProcessorsConnectionAware` are not in that guard

</details>

---

## `testQualifierConcatenateTwoSimilarJoinsEmbedded`

| | |
|---|---|
| family | `functions/tests` |
| sweep status | ERROR |
| **verdict** | **MISSING FEATURE** |
| effort | L |
| confidence | high |

**Root cause**

The qualifier is `accountOrganizationalEntity() { $this.subAccount.oe->concatenate($this.otherAccount.oe)->toOne() }` — a concatenate of two navigations with DIFFERENT head properties (`subAccount` vs `otherAccount`) that happen to land on the same class. legend-lite models class-concatenate only as 'one head property, several parked branch predicates' (#cN synthetic heads): `SyntheticHeads.liftConcatStreams` requires every branch to be the SAME whole navigation node and explicitly returns null otherwise, with the comment that cross-head unions 'are their own rung'. So the lift refuses, the two branches fall through to ordinary per-hop resolution, and branch 1 resolves as the 2-hop association leaf `subAccount.oe`. Under `testEmbeddedMapping` the SubAccount set maps `oe` as an EMBEDDED ctor (`oe(name:[myDb]CONCATENATE.SUBACCOUNT.OE_NAME)`), so the association target's binding for leaf `oe` is a `TypedNewInstance`, and `Substitution.assocBindingRead` has no arm for a class-valued embedded leaf — it throws the reported message. The embedded-ctor walk that DOES exist (`rewriteMultiHop`'s head-join + embedded-tail arm) only fires for a full scalar path such as `subAccount.oe.name`; here the navigation legitimately terminates at a CLASS value because concatenate consumes it. So there are two stacked gaps: the missing cross-head concatenate union (primary), and the missing class-valued-embedded-leaf materialization (only reachable once branches are resolved independently).

**Fix**

Ledger it as a rung, do not spot-fix. The honest scope: (1) `SyntheticHeads.liftConcatStreams` (core/src/main/java/com/legend/resolver/SyntheticHeads.java:672) must stop requiring `nav.equals(headNode)`; instead of parking branch PREDICATES on one real head it must mint a UNION head that carries the ordered list of whole branch NAVIGATIONS, and `applyToPipe` (:171-191) must, for such a head, resolve each branch navigation to its own target pipeline and UNION-ALL them with column alignment — output column named by joining each branch's unique column name with '_' (engine processConcatenate :2790), the union joined back to the driver on the OR of the branches' own join conditions. This also unblocks the FK/null-padding columns the expected SQL shows (`null as EA_ID`, `null as ID`). (2) `Substitution.assocBindingRead` (Substitution.java:2104) needs an EMBEDDED arm: when `leafInner instanceof TypedNewInstance` and the read is a branch materialization, the ctor's mapped property expressions become that branch's projected columns (renamed onto the read row exactly as the scalar arm does) instead of throwing. Do (1) first — with the union rung in place the embedded branch is just another branch pipeline, and (2) is the small piece that makes the embedded mapping project SUBACCOUNT.OE_NAME rather than a join to CONCATENATE.OE.

**How legend-engine does it** — legend-engine/…/core_relational/relational/pureToSQLQuery/pureToSQLQuery.pure:2709 `meta::relational::functions::pureToSqlQuery::processConcatenate` — flattens the concatenate, processes EACH branch as an independent routed value specification (`$elements->map(e|$e->processValueSpecificationReturnPropertyMapping(...))`), cuts each branch's node under the driver, unions the branch selects, and at :2790 names the merged output column `$processed->map(t|…columns->map(c|$c->buildUniqueName(false,$extensions))->makeString('_'))->removeDuplicates()->makeString('_')` — which is literally the expected `SUBACCOUNTOE_NAME_EXTERNALACCOUNTEA_OE_NAME` alias, and the union is joined back on the OR of the two branch conditions. Nothing in the engine requires the branches to share a head.

**Risk** — Relaxing the liftConcatStreams identity check is exactly the merge-by-identity trap the surrounding comments describe (audit 16: 'branch 2's $p.parent hop silently vanished into branch 1's head — wrong rows'; the Merge golden's 7-vs-13 rows). A cross-head union that reuses the single-head parking machinery WILL produce wrong rows; it needs its own head kind, not a loosened equality. Keep the current refusal as the fallback for any branch shape the new rung does not handle.

**Also unblocks** — testQualifierConcatenateTwoSimilarJoins (same file, line 166 region, currently failing with "extend/project columns [Trade ID, OE] reference names unresolvable even after isolation [col='OE' ref='subAccount_oe']") — the same missing rung, one step further along because the non-embedded `oe` binding is a join slot rather than a ctor.

**Falsifier** — If, after implementing only (2) (the embedded arm) the test's SQL still lacks the union subselect and the OR-ed join-back, the cross-head union is confirmed as the primary gap and (2) alone is insufficient. Conversely, if the non-embedded sibling starts passing from (1) alone, (2) is confirmed as the only remaining embedded-specific piece.

<details><summary>Evidence read (5 citations)</summary>

- core/src/main/java/com/legend/resolver/SyntheticHeads.java:672-725 — `liftConcatStreams`; at :712-720 `else if (!prop.equals(p) || !nav.equals(headNode)) { … return null; }` with the comment 'Cross-head/cross-date unions are their own rung; the refusal keeps the loud not-substitutable wall'
- core/src/main/java/com/legend/resolver/Substitution.java:2104-2111 — `assocBindingRead`: `if (leafInner instanceof TypedNewInstance) throw new NotImplementedException("class-typed property '"+leaf+"' of association target '"+a.targetClassFqn()+"' (embedded) is not supported yet")` — the exact message
- core/src/main/java/com/legend/resolver/Substitution.java:1293-1320 — the head-join + embedded-tail arm walks `ha.targetBindings()` down a ctor chain but is guarded by `curT != null && !(curT instanceof TypedNewInstance)`, i.e. it deliberately declines when the walk STOPS at a ctor (a class-valued leaf)
- core/src/main/java/com/legend/resolver/Substitution.java:2030-2046 — `assocLeaf` is the caller: leaf `oe` is looked up in `a.targetBindings()` and handed straight to assocBindingRead
- legend-engine …/core_relational/relational/functions/tests/testConcatenate.pure:369-404 — `testEmbeddedMapping` maps SubAccount.oe and OtherAccount.oe as embedded partials over SUBACCOUNT.OE_NAME / EXTERNALACCOUNT.EA_OE_NAME; :246-265 is the NewTrade qualifier

</details>

---

## `testSelectChainOfAndOrOperators` — ✅ NOW PASSES (fixed upstream; diagnosis retained for the record)

| | |
|---|---|
| family | `functions/tests` |
| sweep status | ERROR |
| **verdict** | **NEEDS PROBE** |
| effort | M |
| confidence | medium |

**Root cause**

The message can only be produced when the `Context` reaching a class fetch has `explicitMapping == null`: `ClassSources.dispatch` returns the explicit mapping unconditionally when it is non-null (the only escape is the self-sourced-M2M `exclude` case, which does not apply here), and only falls through to the runtime-candidate list when it is null. So somewhere in this test a `TypedGetAll` for Person is resolved without the enclosing `TypedFrom` that carries the execute()'s mapping argument. What makes this test unique in its family is that it is the only one calling execute() with TWO different mappings (`simpleRelationalMapping` for results 1-3, `simpleRelationalMappingWithBooleanExpression` for results 4-5), and the corpus harness synthesises `rcorpus::Rt` from the union of the mappings the test references (Runner.java:1528-1542). With one referenced mapping the runtime fallback silently picks the right answer; with two it detonates. That means the runtime-mapping list in the synthetic runtime is MASKING a context-threading leak — this is a real platform defect, not a harness artifact, but the harness's mapping list is what has kept it invisible. I could not pin the exact leaking node by reading: I verified that the execute path wraps the chain in TypedFrom (StatementExecutor:2234-2259), that the resolver re-scopes at TypedFrom both at the top (:297-308) and mid-chain (:2646-2650, whose comment already credits '#18 2-binder root cause'), that the frame splice hands back the wrapped chain verbatim (spliceValuesRead → `f.chain()`), and that every ClassSources.get inside the resolver uses `cs.mappingFqn()` rather than re-dispatching. The one pre-loop dispatch that still uses the OUTER context (collectOpChain's canonicalizer at StoreResolver:2568-2572) only fires on subType shapes, which this test has none of. So the leaking site is real but unidentified.

**Fix**

PROBE FIRST, then thread. Probe: run this one test with a breakpoint (or a temporary `Thread.dumpStack()` under the existing `System.getenv("LEGEND_LITE_STACKS")` guard, which StoreResolver already uses at :1335-1339) at the throw site core/src/main/java/com/legend/resolver/ClassSources.java:1324; the resolveNode ancestry in that stack names the node that lost the from-scope. The fix is then mechanical: at that site, either re-scope the Context from the enclosing TypedFrom (the `fromContext` call, JsonSourceFrame:149) or carry the already-known mapping the way `flattenSource`/`substitution` do (they thread `fctx` explicitly). Two candidate sites to check first because they resolve BEFORE the mid-chain from() re-scope at StoreResolver:2646: (i) `collectOpChain`'s canonicalizer wiring at StoreResolver:2568-2572 (`final Context canonCtx = context;` is the OUTER context and `liftFilteredHeads(top)` runs before the loop) — the canonicalizer only dispatches on subType shapes today, but any future arm there inherits the bug; (ii) any statement whose class chain reaches the resolver without the frame's TypedFrom. SECOND, and independently: per TENETS the synthetic runtime's mapping list is a harness compensation that hides exactly this class of defect. Once the leak is closed, `rcorpus::Rt` should carry mappings ONLY for the tests that genuinely need runtime-side dispatch (`->from(runtime)` with no mapping); populating it from the test's whole mapping-ref grab-bag makes runtime dispatch answer questions the explicit mapping should have answered. Do not touch that until the leak is fixed — tightening it first would turn a masked defect into dozens of loud '0 binders' failures.

**Risk** — If the leak turns out to be in a shared path (e.g. the frame-splice), the fix could change which mapping a currently-passing test resolves against — every test that references exactly one Person-binding mapping is currently getting the right answer by accident, so a wrong re-threading is silent. Any change here needs a full sweep, not a single-test check.

**Also unblocks** — testMapping (tests/mapping/enumeration/testEnumerationMapping.pure:105) fails with the identical message for class Employee and has the identical shape — two execute() calls with different mappings (employeeTestMapping and employeeTestMappingWithFunction) in one test function.

**Falsifier** — Add a second, Person-binding mapping reference to any currently-PASSING test in this family (e.g. mention simpleRelationalMappingWithBooleanExpression in a dead let). If that test also starts failing with 'has 2 mappings binding class Person', the leak is in a path every class query takes and the masking explanation is confirmed. If it keeps passing, the leak is specific to a shape only testSelectChainOfAndOrOperators has (the `$p.locations.place` to-many lift, or the 5-execute/2-mapping statement sequence), and the probe should target that shape.

<details><summary>Evidence read (7 citations)</summary>

- core/src/main/java/com/legend/resolver/ClassSources.java:1284-1286 — `if (!explicitMapping.equals(exclude)) return explicitMapping;` — the runtime branch below is unreachable with a non-null explicit mapping
- core/src/main/java/com/legend/resolver/ClassSources.java:1296-1331 — the runtime branch: `rt.mappings().stream().distinct().filter(binds)` then `if (binders.size() != 1) throw … "class-query dispatch needs exactly one"` (:1324-1328)
- core/src/test/java/com/legend/rcorpus/Runner.java:1526-1542 — `rcorpus::Rt` is built per test from `mappingRefs`: every referenced mapping present in the model is added to the runtime's mappings list
- core/src/main/java/com/legend/StatementExecutor.java:2234-2259 — the execute() chain is wrapped `new TypedFrom(chain, Optional.of(mref), …)` whenever `!containsTypedFrom(chain)`; :2366-2376 shows containsTypedFrom is a whole-subtree scan
- core/src/main/java/com/legend/resolver/StoreResolver.java:159-163 and :297-308 — Context is `ofRuntime(driverRuntimeFqn)` when no explicit mapping is passed, and is re-scoped only when a `TypedFrom` node is walked (JsonSourceFrame.java:149-170 does the re-scope)
- core/src/main/java/com/legend/resolver/StoreResolver.java:2643-2650 — the in-chain from() arm of collectOpChain, commented '(#18 2-binder root cause)' — a previous fix for the same class of leak
- core/src/main/java/com/legend/StatementExecutor.java:335-337 and :2269-2271 — both resolver entry points pass only `env.runtimeFqn()`; the mapping must arrive via a TypedFrom inside the body

</details>

---

## `testExistsAsNullWithSubType`

| | |
|---|---|
| family | `functions/tests/projection` |
| sweep status | ERROR |
| **verdict** | **MISSING FEATURE** |
| effort | L |
| confidence | medium |

**Root cause**

`mappingForMultipleSubTypes` maps the property `fnScope` TWICE with subtype-set routes: `fnScope[map2] : [db]@privateFnJoin` (target set Private) and `fnScope[map3] : [db]@publicFnJoin` (target set Public). legend-lite decides whether a class-typed Join PM becomes a class NAVIGATE purely from the property's DECLARED type: JoinChainEmission.classTypedTargetIfMapped(owner, propName, model) reads findPropertyTypeDeep -> FunctionScope and asks model.isMappedClass("FunctionScope"). FunctionScope is an abstract supertype mapped NOWHERE in the corpus (only its subclasses Private/Public are), so it returns null; no legacyNavigate is emitted and MappingNormalizer's ctor field for the Join PM falls to the physical-slot arm `$row.<joinSlot>` — a table sub-row. NewChecker then unifies that row value against the declared FunctionScope[1] property and throws. The PM's targetSetId (map2/map3) is never consulted, and because the ctor field map is keyed by property name the second route would in any case overwrite the first.

**Fix**

Two coordinated changes, both in the normalizer.
(1) core/src/main/java/com/legend/normalizer/JoinChainEmission.java: change classTypedTargetIfMapped to take the PropertyMapping (or an extra `@Nullable String targetSetId`) and, when targetSetId != null, resolve the target class from the named set implementation in the enclosing LegacyMappingDefinition (the ClassMapping whose setIdOf(...) equals targetSetId) instead of the declared property type; assert the set's className is a subtype of the declared type and fall back to the declared type when targetSetId is null. Update the three call sites (JoinChainEmission.java:173, :272 and MappingNormalizer.java:2609) to pass the PM. This makes `fnScope[map2]` navigate to Private and `fnScope[map3]` to Public, so emitNavigate fires and the ctor value is a class instance that unifies with FunctionScope by subtyping.
(2) The nav slot is currently minted per PROPERTY NAME (JoinChainEmission.mintNavSlotAlias / pipeline.navSlotByProp keyed by propName, dedup at :291-299). Key it by (propertyName, targetSetId) so the two routes get distinct slots, and in MappingNormalizer's CtorField Join arm (:2608-2617) emit ONE field per route rather than one per name — the resolver then needs a set-routed read so that `$f.fnScope->subType(@Public)` picks the map3 slot (mirror ClassSources' existing stc_/subTypeColumn contract, i.e. bind each route as stc_<Private|Public>___fnScope in addition to the plain name).
If (2) is out of scope for now, ledger it: doing only (1) makes the mapping COMPILE but silently binds whichever route survives the name-keyed map — that is wrong rows, so (1) must not ship without (2) or an explicit loud wall on duplicate (name,targetSetId) Join PMs.

**How legend-engine does it** — /Users/neemsandv/legend/legend-pure/legend-pure-dsl/legend-pure-dsl-mapping/legend-pure-m2-dsl-mapping-pure/src/main/resources/platform_dsl_mapping/functions_PropertyMappingsImplementation.pure:122-125 — meta::pure::mapping::propertyMappingByPropertyNameAndTargetId filters allPropertyMappings by `$pm.property.name == $s && $pm.targetSetImplementationId == $targetId`; :127-129 states the rule verbatim: "In the case of ->subType() routing will identify the root class mapping for the target type, in this instance the property mapping needs to be resolved by the [target] SetImplementationId". So the engine keeps the two fnScope PMs DISTINCT and picks by target set at the subType cast.

**Risk** — classTypedTargetIfMapped is called from three places including the Inline-embedded collision check (JoinChainEmission.java:173); changing its meaning for set-routed PMs could re-route existing union tests that already rely on p.unionRoutes (JoinChainEmission.java:381-397) keyed by property name. Tenet-2 trap: do NOT make the value conform by weakening NewChecker's unify — that would let genuinely unmapped class-typed PMs through as rows.

**Also unblocks** — Likely the other prop[subTypeSetId] corpus shapes (tests/mapping/subType family) if any are currently failing on the same message.

**Falsifier** — Add a temporary loud wall in MappingNormalizer's Join CtorField arm when targetIfMapped == null but the PM has a non-null targetSetId; if the enumeration of failing tests does not include this one, the ctor-field arm is not the throw site and the failure is in a different NewChecker call (checkCopy, line 56).

<details><summary>Evidence read (5 citations)</summary>

- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/normalizer/JoinChainEmission.java:636-645 — classTypedTargetIfMapped: `TypeExpression propType = findPropertyTypeDeep(owner, propName, model); ... return model.isMappedClass(tgt) ? tgt : null;` — declared type only, targetSetId absent from the signature
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/normalizer/JoinChainEmission.java:270-283 — `targetClassFqn = classTypedTargetIfMapped(...); boolean emitNavigate = isLastHop && targetClassFqn != null;` so a null target degrades the terminal hop to a physical join slot
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/normalizer/MappingNormalizer.java:2608-2617 — `case PropertyMapping.Join j -> { String targetIfMapped = classTypedTargetIfMapped(...); String slot = targetIfMapped != null ? pipeline.navSlotByProp... : JoinChainEmission.slotFor(pipeline, j.joins()); yield new CtorField(j.propertyName(), new AppliedProperty(rowBind, slot), false); }` — j.targetSetId() is never read here
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/compiler/spec/NewChecker.java:95-104 — the unify failure is wrapped as `"property '" + name + "' of '" + ni.className() + "': " + e.getMessage()`, matching the observed text exactly
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/compiler/ModelBuilder.java:453-459 — isMappedClass is fed only from declared classBindings, so FunctionScope (never a class-mapping target anywhere) is genuinely unmapped

</details>

---

## `testSpecialUnion_m2m2r`

| | |
|---|---|
| family | `graphFetch/tests/union` |
| sweep status | ERROR |
| **verdict** | **REAL DEFECT** |
| effort | S (revised up from XS by adversarial review) |
| confidence | high |
| **adversarial review** | **CONFIRMED** |

**Root cause**

The test builds its execution context as `let runtime = ^EngineRuntime(mappings = ..., connectionStores = [...])` and calls `->from($mapping, $runtime)`. FromChecker's instance-runtime arm recognises a non-ref argument as a runtime only by EXACT FQN equality against `meta::core::runtime::Runtime`. `^EngineRuntime(...)` types as ClassType("meta::core::runtime::EngineRuntime") — a declared SUBCLASS of Runtime in legend-lite's own prelude — so the equality fails, the argument falls through to the loud throw, and the ModelChainConnection mappings / testDataSetupCsv it carries are never harvested. It is a subtype-check bug, not an absent surface: every mechanism this test needs downstream (chainMappings harvesting, special_union operation synthesis, inline CSV seeding) already exists.

**Fix**

core/src/main/java/com/legend/compiler/spec/FromChecker.java line 53-56: replace the exact-FQN test with a subtype-aware one —
    if (a.args().get(i).info().type() instanceof Type.ClassType ct
            && (ct.fqn().equals("meta::core::runtime::Runtime")
                || t.model().isSubtype(ct.fqn(), "meta::core::runtime::Runtime"))) { ... }
(t.model() is the ModelContext already reachable from Typer, same accessor GraphFetchChecker.java:117 uses.) Apply the identical widening at the two mirror sites in StatementExecutor.java:3054 and :3080 (the orchestration-handle arms), which have the same exact-equality test for ^Runtime/Runtime-typed values and would otherwise force an ^EngineRuntime value through the SQL pipeline. Nothing else changes: refs/slotting, chainMappings, jsonSources and sqlSetups harvesting all already run inside that arm.

**How legend-engine does it** — /Users/neemsandv/legend/legend-engine/legend-engine-core/legend-engine-core-pure/legend-engine-pure-code-compiled-core/src/main/resources/core/pure/runtime/runtimeExtension.pure:20 — `Class meta::core::runtime::EngineRuntime extends Runtime`, so from(..., ^EngineRuntime(...)) is a well-typed Runtime argument in the engine.

**⚠ Correction from adversarial review** — The fix as written is correct but is three edit sites, not one, and the StatementExecutor widening must keep the existing containsEffectfulNode guards (:3057-3063 and :3085-3089) — widening the ctor arm to any Runtime subtype without them would silently drop effects in constructor arguments. Also drop the claim that this alone unblocks the test: nothing here establishes that special_union synthesis or the M2M-over-union chain works.

<details><summary>Adversarial review notes (CONFIRMED)</summary>

The diagnosed wall is real and the citation set is accurate. Observed failure in the sweep brief is `from() argument 2 must be a mapping or runtime reference, got TypedNewInstance` (U61.md:38-44) — TypedNewInstance, i.e. the inline `^EngineRuntime(...)` at testUnionRootLevel_relational.pure:675-681, exactly as claimed. FromChecker.java:53-58 is an exact-FQN test against meta::core::runtime::Runtime, and the throw at :74-76 is the observed message. Pure.java:234 declares EngineRuntime extends Runtime, and ModelContext.isSubtype:222-242 walks superClassFqns via findClass, so the widening resolves it; NewChecker.check returns ClassType(ni.className()) for the default (non-Pair/List) case, so the arg really is a ClassType. TypedFrom.collectChain:290-310 recurses through children(), so a TypedNewInstance runtime tree does yield ModelChainConnection.mappings once the arm is entered. The two mirror sites in StatementExecutor are real: :3054-3056 (ctor arm, exact FQN Runtime/ConnectionStore, with the effect guard at :3057-3063) and :3080-3084 (type-driven arm, exact FQN plus env.ctx().isSubtype for Connection) — the same accessor the fix wants is already in scope there. What I will NOT confirm is the sentence 'every mechanism this test needs downstream already exists'. I did not verify special_union operation synthesis or the M2M-over-union chain; the only hits for special_union are a protocol-name case in MappingProtocolParser:1568 and a comment in UnionSynthesis:320, which is thin evidence for 'already exists'. The row's own risk field concedes this ('may not alone make it green') — V10.md dropped that hedge. Treat this as 'removes the typing wall', not 'greens the test'.

</details>

**Citation issues found in review** — The fix text places the mirror sites in 'StatementExecutor.java:3054 and :3080' — correct lines, but the file is core/src/main/java/com/legend/StatementExecutor.java, not .../com/legend/harness/StatementExecutor.java as other evidence lines in this batch imply. EngineTestExecutor.java:458-469 I did not open.

**Risk** — Widening StatementExecutor's handle arms to subtypes makes more values 'opaque handles' that return Scalar(null); the effect guard at :3057-3063 must stay so a ctor argument carrying an executeInDb effect is still loud. Note this test is <<test.AlloyOnly>> and additionally needs the special_union OperationSetImplementation + M2M-over-union chain to work; the from() fix unblocks it but may not alone make it green.

**Also unblocks** — Other corpus tests building ^EngineRuntime(...) inline: graphFetch/tests/testCrossStoreGraphFetch.pure, modelJoins/testModelJoinsToRelationalJoins.pure, executionPlan/tests/executionPlanTest.pure.

**Falsifier** — After the widening, if the failure moves to a special_union / ModelChainConnection dispatch message (UnionSynthesis or ClassSources chain dispatch), the from() check was the only typing blocker and the rest is a separate feature gap; if it still says "must be a mapping or runtime reference", the runtime argument is not typing as a ClassType at all (e.g. it stayed a TypedUserCall) and the arm needs an inline step first.

<details><summary>Evidence read (5 citations)</summary>

- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/compiler/spec/FromChecker.java:53-76 — `if (a.args().get(i).info().type() instanceof Type.ClassType ct && ct.fqn().equals("meta::core::runtime::Runtime")) { ...harvest... } throw new TypeInferenceException("from() argument " + i + " must be a mapping or runtime reference, got " + a.args().get(i).getClass().getSimpleName());` — exact-equality, then the observed message
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/builtin/Pure.java:234 — ENGINE_RUNTIME = native Class meta::core::runtime::EngineRuntime extends meta::core::runtime::Runtime { mappings: Mapping[*]; } — the class IS declared, and as a strict subtype
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/compiler/spec/GraphFetchChecker.java:117 — `if (!t.model().isSubtype(subFqn, classFqn))` — the Typer-side subtype accessor the fix should use (ModelContext.isSubtype, ModelContext.java:223-239)
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/compiler/spec/typed/TypedFrom.java:284-310 — chainMappingsIn/collectChain already harvests ModelChainConnection.mappings, which is exactly what this test's second connectionStore carries
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/harness/EngineTestExecutor.java:458-469 — the plain-let arm already seeds inline testDataSetupCsv for exactly the executeLegendQuery/from() shape this test uses

</details>

---

## `testRestrictWithPostProcessor`

| | |
|---|---|
| family | `tds/tests` |
| sweep status | ERROR |
| **verdict** | **MISSING FEATURE** |
| effort | M |
| confidence | high |

**Root cause**

The test's runtime copy installs `sqlQueryPostProcessors = [{query|$query->meta::relational::postProcessor::postprocess({rel|$rel})}]`. `postprocess` is a CORPUS Pure function (postProcessor.pure:87-89) whose body calls `transformNonCached`, so SpecCompiler.compileReachable pulls `meta::relational::postProcessor::transformNonCached` into the G-phase. Its body is a `match` over the relational metamodel, and its THIRD arm is `v:ViewSelectSQLQuery[1] | ...`. legend-lite's builtin relational-metamodel prelude never declares `meta::relational::metamodel::relation::ViewSelectSQLQuery`, so Typer.namedType's `ctx.findType(name)` misses and throws. This is not a query-execution defect at all: it is an absent metamodel CLASS in the prelude, hit while type-checking a corpus function the test references.

**Fix**

In core/src/main/java/com/legend/builtin/Pure.java, next to TDS_SELECT_SQL_QUERY (line 451), add the metamodel classes transformNonCached's match arms name, following the file's own single-inheritance-collapse + references-as-Any convention:
  VIEW_SELECT_SQL_QUERY = nativeClass("native Class meta::relational::metamodel::relation::ViewSelectSQLQuery extends meta::relational::metamodel::relation::Table { selectSQLQuery: meta::relational::metamodel::relation::SelectSQLQuery[1]; view: meta::relational::metamodel::relation::View[1]; }")
That clears the observed wall. The SAME body then walls on the next absent arms, so add them in the same edit (all verified absent by grep over Pure.java): meta::relational::metamodel::operation::Function (extends RelationalOperationElement), ::operation::Operation (extends operation::Function), ::operation::BinaryOperation { left: RelationalOperationElement[1]; right: RelationalOperationElement[1]; }, ::operation::UnaryOperation { nested: RelationalOperationElement[1]; }, ::operation::VariableArityOperation { args: RelationalOperationElement[*]; }, ::operation::SemiStructuredObjectNavigation { operand: RelationalOperationElement[1]; contentType: String[0..1]; returnType: Any[0..1]; avoidCastIfPrimitive: Boolean[0..1]; }, ::operation::SemiStructuredPropertyAccess (extends SemiStructuredObjectNavigation) { property: RelationalOperationElement[1]; index: RelationalOperationElement[0..1]; }, ::operation::SemiStructuredArrayElementAccess (extends SemiStructuredObjectNavigation) { index: RelationalOperationElement[1]; }. Also extend SELECT_SQL_QUERY (Pure.java:426) with leftSideOfFilter: meta::relational::metamodel::join::RelationalTreeNode[0..1] and pivot/savedFilteringOperation/extraFilteringOperation/preIsolationCurrentTreeNode (Any-typed per convention), and register a native signature for meta::relational::functions::pureToSqlQuery::findOneNode(node:RelationalTreeNode[1], oldRoot:RootJoinTreeNode[1], newRoot:RootJoinTreeNode[1]):RelationalTreeNode[1] — transformNonCached's SelectSQLQuery arm calls it. No resolver/lowering change: the post-processor value is an orchestration handle (StatementExecutor.java:3053-3090) and the installed processor is the IDENTITY {rel|$rel}, so the SQL golden is the un-post-processed one.

**How legend-engine does it** — /Users/neemsandv/legend/legend-pure/legend-pure-store/legend-pure-store-relational/legend-pure-m2-store-relational-pure/src/main/resources/platform_store_relational/grammar/relational.pure:270-273 (Class meta::relational::metamodel::relation::ViewSelectSQLQuery extends Table { selectSQLQuery : SelectSQLQuery[1]; view : View[1]; }); the consuming body is /Users/neemsandv/legend/legend-engine/legend-engine-xts-relationalStore/legend-engine-xt-relationalStore-generation/legend-engine-xt-relationalStore-pure/legend-engine-xt-relationalStore-core-pure/src/main/resources/core_relational/relational/postprocessor/postProcessor.pure:295-340 (transformNonCached) and :87-89 (postprocess)

**Risk** — Adding classes to the prelude is additive, but SELECT_SQL_QUERY gaining properties changes its value layout and could perturb any code that enumerates its properties (ProtocolEmitter / plan printing). Keep the new SelectSQLQuery props Any-typed to avoid re-introducing the CTE<->SelectSQLQuery layout cycle the existing comment at Pure.java:440 warns about. Tenet-2 trap: do NOT special-case `postprocess` in the harness to skip compiling its callee — the platform owns the metamodel surface.

**Also unblocks** — Any other corpus test whose body reaches meta::relational::postProcessor::postprocess / transformNonCached (postProcessor family, trimColumnName/reAlias/pushFiltersDownToJoin post-processor tests).

**Falsifier** — Add ONLY ViewSelectSQLQuery and re-run: if the error moves to "unknown type 'UnaryOperation'"/"'Operation'" the staged-wall reading is right; if it instead reaches execution and the SQL golden mismatches, the root cause is the restrict-after-sort rendering, not the prelude.

<details><summary>Evidence read (5 citations)</summary>

- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/compiler/spec/Typer.java:2823-2834 — namedType: ctx.findType(name) ... .orElseThrow(() -> new TypeInferenceException("unknown type '" + name + "' in @" + name)); this is the only emitter of that literal
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/compiler/spec/SpecCompiler.java:65-71 — the "in function '<fqn>': " prefix is added by compile(TypedFunction); compileReachable (:88-104) is what drags transformNonCached in
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/builtin/Pure.java:415-461 — the relational-metamodel prelude block: Relation, NamedRelation, Table, SelectSQLQuery, CommonTableExpression, Union, UnionAll, TdsSelectSqlQuery, TabularFunction are all present; ViewSelectSQLQuery is absent from the whole file (grep over core/src/main/java finds only a comment at normalizer/MappingNormalizer.java:1865)
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/builtin/Pure.java:425 — the prelude itself records the gap: "pivot/leftSideOfFilter/saved*/preIsolation* omitted until demanded"; transformNonCached DEMANDS leftSideOfFilter
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/builtin/Pure.java:146-166 — nativeClass(...) auto-registers into ALL_CLASSES, so adding a field is the whole registration

</details>

---

## `testTableToTDSWithQuotes`

| | |
|---|---|
| family | `tds/tests` |
| sweep status | ERROR |
| **verdict** | **REAL DEFECT** |
| effort | S (revised up from XS by adversarial review) |
| confidence | high |
| **adversarial review** | **CONFIRMED** |

**Root cause**

The test spells the legacy TDS sort key as `->sort([desc(['FIRST NAME'])])` — the column name is written as a ONE-ELEMENT COLLECTION, which in Pure collapses to String[1] and therefore binds the real `meta::pure::tds::desc(column:String[1])`. legend-lite registers only a ColSpec overload of desc (Pure.java:1208) and compensates for the string form by a SYNTACTIC desugar in SortChecker.sortInfo that fires only when the single parameter is literally a CString node. A PureCollection wrapping one CString is not a CString, so the desugar is skipped, the call is checked against the ColSpec signature, and InferenceKernel:1318 reports "expected ColSpec<T>, got String". The defect is that legend-lite encodes a MULTIPLICITY rule (a 1-element collection IS the element) as an AST-shape rule.

**Fix**

core/src/main/java/com/legend/compiler/spec/SortChecker.java. (1) In sortInfo (line 106): before the CString test, peel a singleton collection —
    ValueSpecification a0 = af.parameters().size() == 1 ? af.parameters().get(0) : null;
    if (a0 instanceof PureCollection pc && pc.values().size() == 1) { a0 = pc.values().get(0); }
    if (a0 instanceof CString c) { af = af.withParameters(List.of(new ColSpec(c.value()))); }
    else if (a0 instanceof ColSpec cs0) { af = af.withParameters(List.of(cs0)); }
(2) In carriesColSpec (line 120): give the AppliedFunction arm the same peel, i.e. accept a single-element PureCollection whose sole element is a ColSpec or CString, so sort([desc(['X'])]) is classified as a relation sort and rides checkGeneric+TypedSort rather than applyGeneric. Add `import com.legend.protocol.spec.PureCollection` is already present (line 12). No lowering change: after the peel the node is the ordinary TypedSortInfo(colName, ascending=false) the renderer already emits as `order by "FIRST NAME" desc`.

**How legend-engine does it** — /Users/neemsandv/legend/legend-engine/legend-engine-core/legend-engine-core-pure/legend-engine-pure-code-compiled-core/src/main/resources/core/pure/tds/tds.pure:695-707 — meta::pure::tds::desc(column:String[1]):SortInformation[1] and asc(column:String[1]); the parameter is String[1], so a [ 'X' ] literal binds it directly by Pure's collection/multiplicity rule.

**⚠ Correction from adversarial review** — The fix works, but it deepens the very AST-shape encoding the diagnosis calls the defect. The faithful alternative is to register the engine's real String overloads (tds.pure:697 desc(column:String[1]), and asc likewise) and let overload resolution bind a one-element collection as String[1] — then sortInfo needs no peel at all, only carriesColSpec needs the shape widened for the relation-sort classification. If the syntactic peel is kept, it must be strictly size()==1 and must not be applied in legacyStringSortToModern (:69-76), where ['A','B'] is legitimately multiple ascending keys.

<details><summary>Adversarial review notes (CONFIRMED)</summary>

Every citation is line-accurate and the observed message matches exactly: U61.md:22-28 records `in call to 'meta::pure::tds::desc', argument 1: expected ColSpec<T>, got String`, whose tail is InferenceKernel.fail (:1317-1318). desc/asc are registered ONLY in the ColSpec form (Pure.java:1121, :1208 — grep for tds::desc/tds::asc returns exactly those two). Typer.java:1187-1188 routes ASC/DESC to SortChecker.sortInfo, whose desugar at :108 fires only on a bare CString, so `desc(['FIRST NAME'])` is passed through to checkGeneric and unify hits the GenericType arm and throws the observed text. The fact that the message says 'got String' (not 'got ColSpec') proves the argument is a one-element collection typing as String[1], i.e. the AST-shape claim is right rather than the parser having already collapsed it. carriesColSpec:120-131 is as quoted, so the sort does fall to applyGeneric — fix (2) is genuinely needed. I also attacked the fix on the sibling lambda in the same test body, `->sort([desc(['"FIRST NAME"'])])` at testSort.pure:213: once fix (2) routes this through checkGeneric, the ⊆ constraint would compare the quoted key against the bare projected column names — but InferenceKernel.unifyConstraint:246-250 matches via sameColumn, and sameColumn/stripColQ:376-383 strips surrounding quotes, so that lambda still types. No new wall. The upstream of the sort also types: ProjectChecker:137-150 desugars legacy col(fn,'name') and GET_STRING__TDS_ROW_1__STRING_1 is registered (Pure.java:1911), which is consistent with the wall landing on desc rather than earlier. Caveat: as the falsifier itself allows, the peel is unlikely to green the test — the asserts pin exact SQL with quoted identifiers (`select "root"."FIRST NAME" ... order by "FIRST NAME" desc`), and I did not verify the renderer emits that.

</details>

**Risk** — The peel must stay at size()==1 — collapsing a 2+-element collection would silently drop sort keys. Do not peel in the SORT arm's legacyStringSortToModern (lines 69-76): that path already handles ['A','B'] as multiple ASC keys and its semantics differ.

**Also unblocks** — Any corpus test spelling asc/desc with a bracketed single column name.

**Falsifier** — If after the peel the error becomes a tableToTDS/quoted-identifier failure rather than a passing golden, the desc overload was only the first of several walls in this test (it also exercises tableReference(db,'default','tableWithQuotedColumns') and getString on quoted column names).

<details><summary>Evidence read (4 citations)</summary>

- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/compiler/spec/SortChecker.java:106-113 — sortInfo(): `if (af.parameters().size() == 1 && af.parameters().get(0) instanceof CString c) { af = af.withParameters(List.of(new ColSpec(c.value()))); }` then checkGeneric — no PureCollection unwrap
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/compiler/spec/SortChecker.java:120-131 — carriesColSpec(): the AppliedFunction arm accepts only `f.parameters().get(0) instanceof ColSpec || instanceof CString`, so sort([desc([...])]) is not even recognised as a relation-sort shape and falls to t.applyGeneric
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/builtin/Pure.java:1208 — DESC__COL_SPEC_1 is the ONLY meta::pure::tds::desc registration (same for asc at :1121)
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/compiler/spec/InferenceKernel.java:1318 — `new TypeInferenceException("expected " + formal.typeName() + ", got " + actual.typeName())` is the exact message tail

</details>

---

## `testMapping` — ✅ NOW PASSES (fixed upstream; diagnosis retained for the record)

| | |
|---|---|
| family | `tests/mapping/enumeration` |
| sweep status | ERROR |
| **verdict** | **NEEDS PROBE** |
| effort | M |
| confidence | low |

**Root cause**

Established: ClassSources.dispatch is reached with explicitMapping == null and runtimeFqn == "rcorpus::Rt", whose mapping list is exactly the two mappings this test names (employeeTestMapping, employeeTestMappingWithFunction) — both bind Employee, so the exactly-one rule fires. What I could NOT pin down is WHICH call site loses the mapping: every path I read threads it correctly (StatementExecutor.buildFrame attaches TypedFrom(mref) at :2257 when the query has no from(); StoreResolver.resolveNode re-scopes at :297-308 via JsonSourceFrame.fromContext:158-163; collectOpChain re-scopes an in-chain from() at :2647-2650; SubQueryLift rebuilds the TypedFrom with the context's mapping at :156-168 and :200-212). The one pre-loop stale-context site I found is StoreResolver.java:2568-2572, where `canonCtx = context` (the OUTER, runtime-only context when the from() sits INSIDE the chain — the shape produced by splicing `$result.values`) is captured for the subType-cast canonicalizer BEFORE the loop peels the from(); the adjacent comment at :2646 ("#18 2-binder root cause") shows this exact class of bug was fixed once for the loop but not for the pre-loop pass. That site cannot be the throw for THIS test though (the body has no subType). Corroborating shape: the only other corpus test with this message, testSelectChainOfAndOrOperators (docs/RELATIONAL_CORPUS_ALL.md:1221), is likewise an OBJECT (non-project) query in a test that names two mappings binding one class — so the trigger is 'a test naming >=2 mappings for a class, plus object-space reads over a spliced .values frame', and it is latent-but-masked whenever only one candidate exists.

**Fix**

PROBE FIRST, do not guess-patch. Cheapest probe: in ClassSources.dispatch (ClassSources.java:1324), append the current stack to the MappingResolutionException message (or set a breakpoint / -D flag that prints Thread.currentThread().getStackTrace() when binders.size() != 1), then run only tests/mapping/enumeration::testMapping and functions/tests::testSelectChainOfAndOrOperators. That names the losing call site in one run. The expected fix shape once named is a Context-threading repair in the resolver (carry the from()'s mapping to whatever pre-loop / nested pass currently sees only the runtime), NOT a change to the exactly-one rule and NOT a harness change to make rcorpus::Rt list one mapping — the runtime legitimately holds both, and narrowing it in the Runner would be textbook harness compensation. If the probe shows the loss happens in StatementExecutor.buildFrame's letPrefix re-inline (:2229-2232 lacking the splice hook), the fix is to pass spliceHook(...) into that inliner too so previously-bound `$result.values` reads keep their own from() context instead of being re-resolved under the ambient runtime.

**Also unblocks** — testSelectChainOfAndOrOperators [functions/tests] — same message, same multi-mapping object-query shape.

**Falsifier** — If the stack shows the throw coming from ClassSources.get's resolveVia fallback with a non-null explicitMapping and a non-null `exclude` equal to it, then this is the self-sourced-M2M arm (ClassSources.java:1284-1295), not a context-threading loss, and the whole diagnosis above is wrong.

<details><summary>Evidence read (6 citations)</summary>

- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/resolver/ClassSources.java:1296-1331 — the only emitter; reached only when explicitMapping is null (or equals `exclude`), and it consults rt.mappings()
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/test/java/com/legend/rcorpus/Runner.java:1526-1542 — rcorpus::Rt is built per test from the mapping FQNs the test REFERENCES, which is why the count is exactly 2 here
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/StatementExecutor.java:2234-2259 — buildFrame attaches TypedFrom(mref, runtime) only `if (!containsTypedFrom(chain))`, and passes the raw letPrefix into an inliner WITHOUT the splice hook at :2229-2232 (the outer statement inliner at :188-191 DOES have it) — an asymmetry worth checking
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/resolver/StoreResolver.java:159-163 — the root Context is runtime-only when resolve(body, runtimeFqn) is called (StatementExecutor.java:337 and :2269 both use that 2-arg form)
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/resolver/StoreResolver.java:2568-2572 vs :2646-2650 — the canonicalizer captures the pre-peel context while the loop re-scopes; documented 2-binder history in the comment
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/docs/RELATIONAL_CORPUS_ALL.md:1221,1465 — the two corpus tests carrying this message, both multi-mapping object queries

</details>

---

## `testForcedSubTypeProjectDirect`

| | |
|---|---|
| family | `tests/mapping/inheritance` |
| sweep status | ERROR |
| **verdict** | **MISSING FEATURE** |
| effort | L |
| confidence | high |

**Root cause**

`RoadVehicle : Operation { inheritance_... }` makes RoadVehicle an inheritance union of Car[map1] (table Car) and Bicycle[map2] (table Bicycle). The query forces `r->subType(@Bicycle).person.name`, which the resolver rewrites into a read of the synthetic subtype-dispatch column `stc_<Bicycle>___person`. UnionSynthesis only mints stc columns for SCALAR properties (or for flattenable EMBEDDED ctor leaves): subTypeDispatchProps computes `boolean scalar = t instanceof NameRef nr && model.findClass(nr.name()).isEmpty()` and only emits when `scalar && visibleOnTarget`; the else-branch handles a ctor-valued field. `person` on Bicycle[map2] is a CLASS-typed Join PM (`[myDB]@PersonBicycle > @PersonPersonMid`), i.e. a nav SLOT, not a scalar and not a ctor — so no stc column is emitted for it in any thread. The Substitution then finds no binding named stc_...Bicycle___person on the RoadVehicle class source and walls. In short: subtype-cast dispatch over an inheritance union supports scalar and embedded properties but not JOIN-NAVIGATED (class-typed) properties.

**Fix**

core/src/main/java/com/legend/normalizer/UnionSynthesis.java. Extend the cast-target column set to CLASS-typed Join props by transplanting the owning member's navigation rather than trying to project a scalar: in subTypeDispatchProps (:798-830), add a third branch for `visibleOnTarget && !scalar && the member's field value is a nav-slot read` that records the prop as a NAV cast target; then in addSubTypeDispatchCols (:877-929) — or a new addStcNavTransplant sibling of addStcEmbeddedLeaf (:934-964) — emit, for the OWNING thread only, the member's join step re-anchored under the alias ClassMapping.subTypeColumn(target, prop), and for every other thread the join KEY columns projected as typed NULL so the transplanted left join yields no row there. Then make ClassSources expose it: generalise the stcNavTransplants block (ClassSources.java:711-798) so it also picks up union-member nav transplants (today it is gated on sameRootTable at :726-730, which is correct for the extends family but excludes different-table union members) — the pseudo-binding must be a slot read so Substitution's ordinary nav/demand machinery resolves `.name` on it. The MEMBER_WITNESS machinery (:833-845) already exists and should be reused so Car rows read TDSNull, matching the expected '1, Peugeot, 4, TDSNull'. If this is too large to land now, ledger it as 'class-typed subtype-cast dispatch over an inheritance union' — but keep the loud wall; a scalar-only fallback would return wrong rows.

**How legend-engine does it** — /Users/neemsandv/legend/legend-engine/legend-engine-core/legend-engine-core-pure/legend-engine-pure-code-compiled-core/src/main/resources/core/pure/router/operations/router_operations.pure:19-22 — meta::pure::router::operations::inheritance resolves the operation to `$o.class->getMappedLeafTypes($o.parent)`; the router then routes the ->subType(@Bicycle) leg to the Bicycle SetImplementation and resolves `person` through THAT set's own property mapping (join @PersonBicycle > @PersonPersonMid), so Car rows contribute NULL. Property selection by set id: /Users/neemsandv/legend/legend-pure/legend-pure-dsl/legend-pure-dsl-mapping/legend-pure-m2-dsl-mapping-pure/src/main/resources/platform_dsl_mapping/functions_PropertyMappingsImplementation.pure:122-129.

**Risk** — Emitting extra join steps into union member threads changes the shape of every inheritance/union query, and the threads must stay column-aligned (addSubTypeDispatchCols's contract at :876 is 'same order in every thread'). A NULL-key transplant in the non-owning thread must not turn the outer join into a row multiplier. Tenet-2 trap: do not satisfy this by making the cast read fall back to the ROOT's shared `person` (from the Driver association) — that would return David Scott for Cars instead of TDSNull.

**Also unblocks** — Likely other forced-subType-over-inheritance corpus tests (tests/mapping/inheritance family) that read a join-navigated property through subType(@Sub).

**Falsifier** — Run the sibling testSubTypeProjectDirect (same file, line 39) which reads $r.person.name WITHOUT the cast: if that one also fails, the gap is plain class-typed property support on an inheritance union, not the subtype-cast leg, and the fix belongs one level lower (in the shared-property union columns, not the stc set).

<details><summary>Evidence read (5 citations)</summary>

- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/normalizer/UnionSynthesis.java:798-830 — subTypeDispatchProps: scalar test at :801-802, `if (scalar && visibleOnTarget)` at :806, and the `else if (visibleOnTarget)` branch at :809-829 that only distributes ctor leaves (`NewInstance ector = ctorOf(fv.value()); if (ector != null) ...`) — a nav-slot AppliedProperty yields ector == null and nothing is emitted
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/normalizer/UnionSynthesis.java:877-929 — addSubTypeDispatchCols only ever emits ColSpecs for the props subTypeDispatchProps collected, so an omitted prop has no column in ANY thread
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/model/ClassMapping.java:52-59 — subTypeColumn/subTypeColumnPrefix produce exactly "stc_" + fqn.replace("::","__") + "___" + prop, matching the failing name stc_meta__relational__tests__model__inheritance__Bicycle___person
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/resolver/Substitution.java:1422-1427 — the exact wall text ("has no binding in mapping ... unmapped, or routed to a non-root mapping set — multi-set union dispatch is a roadmap feature")
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/resolver/ClassSources.java:702-798 — the ONLY existing class-typed subtype transplant (stcNavTransplants) is gated on sameRootTable/sameRootTableUnderSubstitution (:726-730), which Car(table Car) vs Bicycle(table Bicycle) fail, so it cannot serve this mapping

</details>

---

## `testPartialUnionMappingOfSubTypePrimitiveProperties_EmbeddedMapping`

| | |
|---|---|
| family | `tests/mapping/union` |
| sweep status | ERROR |
| **verdict** | **REAL DEFECT** |
| effort | S |
| confidence | high |
| **adversarial review** | **CONFIRMED** |

**Root cause**

The subType-cast canonicalizer mints the SCALAR spelling of a subtype-dispatch column for a property that union synthesis only ever publishes in its FLATTENED, per-leaf spelling.

Exact chain, node by node:

1. The mapping maps `ext1Address` on `PersonExt1[set1]` as an EMBEDDED property mapping whose single leaf is a join-terminal column (`name: [myDB] @PersonSet1AddressSet1_ADDRESSID | AddressSet1.name`). `MappingFromProtocol.bodyOf` turns `@Join | Table.col` into a `PropertyMapping.JoinTerminalColumn` (MappingFromProtocol.java:659), and `MappingNormalizer.translatePmToField`'s JoinTerminalColumn arm builds the ctor field value as the TWO-HOP `$row.<slotAlias>.name` (MappingNormalizer.java:2617-2626).

2. `UnionSynthesis.subTypeDispatchProps` classifies `ext1Address` as NOT scalar (its type resolves to a class), so it takes the embedded branch and registers one FLAT pseudo-property per ctor leaf, `ext1Address__name` — gated on `isThreadProjectable`, which explicitly accepts the two-hop `$row.slot.col` shape (UnionSynthesis.java:812-828, :1237-1252). `addStcEmbeddedLeaf` then emits the column under `ClassMapping.subTypeColumn(PersonExt1, "ext1Address__name")` = `stc_…PersonExt1___ext1Address__name` (UnionSynthesis.java:932-963). There is NO `stc_…PersonExt1___ext1Address` column, by construction.

3. `ClassSources` exposes every `stc_` column of the union row as a binding keyed by its own column name (ClassSources.java:688-700). So `bindings()` has `…ext1Address__name` and not `…ext1Address`.

4. On the read side, `SyntheticHeads.liftFilteredHeads` applies the node-local canonicalizer TOP-DOWN (SyntheticHeads.java:296-299) and `StoreResolver.collectOpChain` wires it to `CorrelatedSubselects.subTypeNavCastCanon` (StoreResolver.java:2569-2571). For `$p->subType(@PersonExt1).ext1Address`, that method's tail (CorrelatedSubselects.java:1834-1846) sees a witness binding (`stc_…PersonExt1___$member`, emitted because PersonExt2 does not conform — UnionSynthesis.java:833-845) and returns `TypedPropertyAccess(TypedFilter($p, witnessPred), ClassMapping.subTypeColumn(PersonExt1, "ext1Address"))`. It uses `pa.property()` verbatim — it never asks whether the property is embedded, and it never looks at the trailing `.name` hop, which stays outside as a second property access.

5. Substitution then walks the canonicalized tree. The outer `.name` node has a `TypedPropertyAccess` source, so it lands on `case TypedPropertyAccess pa -> rebuildWithInstanceFold(pa)` (Substitution.java:1767), which rewrites children; the child `filter($p,wp).stc_…___ext1Address` matches `case TypedPropertyAccess pa when pa.source() instanceof TypedFilter f && f.source() instanceof TypedVariable fv && fv.name().equals(target.userVar())` (Substitution.java:1739-1745) → `filteredInstanceRead`, which calls `rewrite(new TypedPropertyAccess(f.source(), pa.property(), pa.info()))` (Substitution.java:1906-1907) — i.e. `$p.stc_…___ext1Address`, a 1-element path → `rewriteHeadProp` → binding null → the exact observed message at Substitution.java:1421-1427.

Why the two sibling tests in the same file pass: `_MappingToColumn` / `_MappingToRelationalOperation` cast to SCALAR properties (`ext1LastName`, `ext1LastNameUpperCase`), for which `subTypeDispatchProps` DOES emit the plain `stc_…___<prop>` column, so the canonicalizer's name matches. Only the embedded property diverges. (Note the witness filter is not a row filter in this position: `filteredInstanceRead` turns it into `TypedIf(witness, leaf)` — a CASE — which is why those two tests still return all 6 rows.)

**Fix**

Fix in ONE place: `CorrelatedSubselects.subTypeNavCastCanon` (core/src/main/java/com/legend/resolver/CorrelatedSubselects.java). Because `liftFilteredHeads` applies the canonicalizer top-down, the two-level node `(subType($v,@Sub).prop).leaf` is visited before its child, so the arm can fold the trailing hop into the column name.

Insert a new arm at the TOP of `subTypeNavCastCanon` (before the existing exists-over-cast arm at :1775 and before the single-level guard at :1808):

```java
// SUBTYPE-EMBEDDED leaf: when the cast's property is mapped as an
// EMBEDDED ctor, the union frame carries NO stc_<Sub>___<prop>
// column — only the FLAT per-leaf stc_<Sub>___<prop>__<leaf>
// (UnionSynthesis.addStcEmbeddedLeaf). Fold the trailing hop into
// the column name so the read is an ordinary union-column read.
if (n instanceof TypedPropertyAccess outer
        && outer.source() instanceof TypedPropertyAccess mid
        && mid.source() instanceof TypedNativeCall sc2
        && sc2.callee().qualifiedName()
                .equals("meta::pure::functions::lang::subType")
        && !sc2.args().isEmpty()
        && sc2.info().type() instanceof Type.ClassType sct2
        && sc2.args().get(0).info().type() instanceof Type.ClassType navCt2) {
    ClassSource t2 = castTarget(mappingOf, navCt2);
    String plain = com.legend.model.ClassMapping.subTypeColumn(
            sct2.fqn(), mid.property());
    String flat = com.legend.model.ClassMapping.subTypeColumn(
            sct2.fqn(), mid.property() + "__" + outer.property());
    if (t2 != null && !t2.bindings().containsKey(plain)
            && t2.bindings().containsKey(flat)) {
        String wKey2 = com.legend.model.ClassMapping.subTypeColumn(
                sct2.fqn(), com.legend.model.ClassMapping.memberWitness());
        TypedSpec nav2 = sc2.args().get(0);
        TypedSpec src = t2.bindings().containsKey(wKey2)
                ? new TypedFilter(nav2,
                        witnessPred(navCt2, wKey2, isNotEmpty), nav2.info())
                : nav2;
        return new TypedPropertyAccess(src, flat, outer.info());
    }
}
```

The `!containsKey(plain)` guard keeps the existing route for class-typed properties the union really does publish plainly (e.g. a cast onto a class-typed JOIN property), so the arm cannot hijack them. The `containsKey(flat)` guard makes it fire only when union synthesis actually flattened the property. The witness branch is kept so partial-membership casts still get the CASE-shaped read `filteredInstanceRead` produces; the no-witness branch handles total-membership unions, where the canonicalizer used to return `n` unchanged and the 2-hop path then died in `rewritePath`→`assocLeaf` instead.

Nothing else must change: because the canonicalizer runs before `collectOpDemand`, the demand scan sees a single-element path (`stc_…___ext1Address__name`), `registerNavigations` treats it as an ordinary binding, and `rewriteHeadProp` resolves it off the union row.

Optional belt-and-braces mirror (not needed for this test, cheap and symmetric): in `Substitution.rewritePath` (Substitution.java:1931), just before the final `return assocLeaf(head, leaf);`, add
```java
if (com.legend.model.ClassMapping.isSubTypeColumn(head)) {
    TypedSpec flatB = target.bindings().get(head + "__" + leaf);
    if (flatB != null) { return renameRowVar(flatB); }
}
```
so a 2-hop stc path that reaches the resolver by any other route resolves instead of hitting the `resolver bug: undemanded navigation` IllegalStateException. Do NOT put the flattening in `Substitution.pathOf`: pathOf is static and has no binding table, so an unconditional collapse there would corrupt genuine class-typed stc navigations (the `stc_…Bicycle___person` family).

**How legend-engine does it** — The engine has no such naming seam: `meta::pure::router::routing` resolves a cast to the conforming member SetImplementations and each member's property mapping is then processed in its own thread, so `ext1Address.name` is just an ordinary embedded property-mapping walk inside the set1 arm — which is exactly what the test's own golden SQL pins (testUnionPartial.pure:126: each UNION ALL arm carries `left outer join AddressSet1 as "addressset1_N" on ("root".ADDRESSID = "addressset1_N".ID)` and projects `"addressset1_N".name` under a per-thread alias). legend-lite's flat `stc_` column is its own (legitimate) encoding of that thread-local projection; the bug is purely that the two ends of `com.legend.model.ClassMapping`'s stated naming contract (ClassMapping.java:44-56) disagree for embedded properties.

<details><summary>Adversarial review notes (CONFIRMED)</summary>

I re-walked the whole chain in source and every load-bearing link holds.

(1) Observed error is the one cited: burndown/failing.tsv carries "property 'stc_meta__relational__tests__mapping__union__partial__PersonExt1___ext1Address' of class '...PersonBase' has no binding in mapping '...partialUnionMappingOfSubTypePrimitiveProperties' (unmapped, o...". Of the four "has no binding in mapping" throw sites (Substitution:1424, :2328, :2750, CorrelatedSubselects:909) only Substitution:1424 carries the "(unmapped, or routed to a non-root...)" suffix, so the throw site is pinned, and the offending name is the SCALAR stc spelling with no __name.

(2) The canonicalizer really mints that name from pa.property() alone (CorrelatedSubselects.java:1834-1846) and it really fires here: cast head is the project-lambda var over PersonBase, castTarget resolves the union ClassSource, and the witness stc_...PersonExt1___$member exists because PersonExt2 does not conform (UnionSynthesis.java:833-845). The sibling scalar tests passing proves the same route is live and working when the plain column exists.

(3) The union publishes only the FLAT spelling. subTypeDispatchProps' else-branch (UnionSynthesis.java:809-828) never adds the plain prop for a class-typed property; it adds prop+"__"+leaf per ctor leaf, and the emission loop at :901-908 routes prop names containing "__" that are not real properties into addStcEmbeddedLeaf (:934-963), which emits ClassMapping.subTypeColumn(target, "ext1Address__name"). ClassSources.java:694-702 exposes every stc_ row column as a binding keyed by its own name, so bindings() has ...ext1Address__name and not ...ext1Address — self-consistent with the observed "no binding".

(4) The gates for the flat leaf being registered all hold statically: ext1Address types to Address (class) so scalar=false and visibleOnTarget=true; PropertyMapping.Embedded -> materializeEmbedded -> buildNewInstanceToOne (MappingNormalizer:2645-2648, :2694-2713, :3361) yields a toOne/NewInstance that ctorOf (UnionSynthesis:1221-1231) unwraps; the `name` leaf comes from the JoinTerminalColumn arm (MappingNormalizer:2618-2641) whose RelOpTranslator.ColumnRef case (RelOpTranslator:189-203) resolves AddressSet1 to the hoisted slot row, producing AppliedProperty(AppliedProperty($row, alias), "name") — exactly the two-hop shape isThreadProjectable accepts (UnionSynthesis:1237-1257). declaredAssertion (MappingNormalizer:2397-2421) returns the read unchanged for a matching String kind, and even if it wrapped, the AppliedFunction arm of isThreadProjectable recurses fine.

(5) Independent corroboration that this machinery is real and not speculative: commit 7b1b0ffc added flat stc embedded columns and moved testEmbeddMappingInSubTypes ERROR->FAIL-with-rows (so flat columns do materialize and read), and commit 41ba5d47 added the two-hop acceptance and turned testSimpleProjectionFromWithEmbeddedInMapping green (so a two-hop join-slot read does project inside a union member thread). Those two together are the strongest evidence I can get without building.

(6) Top-down claim for the fix holds: liftFilteredHeads applies canon.apply(n) at the top of every invocation (SyntheticHeads:296-299) and only then recurses (TypedProject -> column lambdas at :457-464, TypedPropertyAccess -> source at :493-498). valuesLambdas (:1189, only populated at :658 with lift-synthesized mappers) does NOT disable canon inside project columns, so the outer `.name` node is genuinely visited before its subType child and a two-level arm can fire there.

(7) Attacked the fix: the proposed arm compiles against real members — castTarget(Function,Type.ClassType) and witnessPred(Type.ClassType,String,TypedFunction) (CorrelatedSubselects:1866-1867) exist with those signatures, ClassSource.bindings() exists. Guards are tight: !containsKey(plain) cannot hijack the scalar route (plain is exactly what those tests bind) and containsKey(flat) only fires where union synthesis flattened. The returned shape PA(TypedFilter($p,witness), flat) is byte-for-byte the shape the scalar tests already take through Substitution:1738-1745 -> filteredInstanceRead (:1904-1912) -> 1-element path -> resolved binding, so it inherits their working route and their 6-row CASE semantics, which is what the expected CSV wants (Scott/Anand New York in thread 1, Roberts NULL, blanks in thread 2).

Residual I could not close (no builds allowed): I did not execute anything, so the actual presence of stc_...PersonExt1___ext1Address__name in the resolved row type is inferred from a complete static read of every gate, not observed. The diagnosis's own falsifier is the right one and I endorse it as the first thing to print.

</details>

**Citation issues found in review** — No substantive miscitation; three cosmetic offsets. (a) 'Substitution.rewritePath (Substitution.java:1931), just before the final return assocLeaf(head, leaf)' — rewritePath starts at ~1929 but the final `return assocLeaf(head, leaf);` is at 2026, not 1931. (b) StoreResolver setCanonicalizer/liftFilteredHeads are at 2566-2572, cited as 2569-2571. (c) Step 1 says MappingNormalizer.translatePmToField's JoinTerminalColumn arm 'builds the ctor field value' for ext1Address; strictly ext1Address is a PropertyMapping.Embedded whose materializeEmbedded calls translatePmToField on the inner `name` sub-PM, which is the JoinTerminalColumn. Net value shape is exactly as claimed.

**Risk** — The new arm changes behavior only for `subType(@Sub).<prop>.<leaf>` where the cast target binds `stc_<Sub>___<prop>__<leaf>` and does NOT bind `stc_<Sub>___<prop>` — a set that today is 100% failure. The one thing to watch: a subtype property that is embedded with MORE than one leaf, or a nested ctor (`emb.inner.leaf`), produces a 3-hop read the new 2-level arm does not cover; that must keep falling through to the existing loud path, not be silently half-folded. Tenet-2 trap to avoid: do not 'fix' this by teaching the corpus runner to skip the golden-SQL assert — the row assert (`toCSV()`) is a real row check and must pass on its own. Also resist the tempting shortcut of making `UnionSynthesis` ALSO emit a plain `stc_…___ext1Address` column: the union frame is flat/scalar by design and a class-typed union column would break the `isSubTypeColumn` binding exposure in ClassSources.java:688-700.

**Also unblocks** — Not in this unit, but the same family and worth checking after the fix: `testForcedSubTypeProjectDirect` (tests/mapping/inheritance) fails with the identical message shape for `stc_…Bicycle___person`; `testRoutingWithSubtypePropagation` (router/tests) and `testInheritanceMultipleLevel` (testDataGeneration/tests) fail on the multi-hop wall with an stc segment mid-path. Those are class-typed JOIN properties rather than embedded ctors, so they likely need a sibling arm, not this one — do not assume this fix clears them.

**Falsifier** — Print `sources.get("…partialUnionMappingOfSubTypePrimitiveProperties", "…PersonBase").bindings().keySet()` (or the union pipeline's rowType columns) once while resolving this query. If `stc_meta__relational__tests__mapping__union__partial__PersonExt1___ext1Address__name` is ABSENT, this diagnosis is wrong: the defect would then be upstream in `UnionSynthesis.subTypeDispatchProps` / `isThreadProjectable` (the join-terminal leaf not being thread-projectable), and the fix would have to go there instead of in the canonicalizer.

<details><summary>Evidence read (16 citations)</summary>

- /Users/neemsandv/legend/legend-engine/.../core_relational/relational/tests/mapping/union/testUnionPartial.pure:120-129 — the failing test projects `p|$p->subType(@PersonExt1).ext1Address.name`; the mapping at :341-359 maps `ext1Address ( name: [myDB] @PersonSet1AddressSet1_ADDRESSID | AddressSet1.name )` as an EMBEDDED block on PersonExt1[set1]
- core/src/main/java/com/legend/resolver/CorrelatedSubselects.java:1834-1846 — `subTypeNavCastCanon` tail: `return new TypedPropertyAccess(new TypedFilter(nav, witnessPred(...)), ClassMapping.subTypeColumn(sct.fqn(), pa.property()), pa.info());` — mints the column name from `pa.property()` alone, never consulting whether the target binds it
- core/src/main/java/com/legend/normalizer/UnionSynthesis.java:812-828 — the non-scalar branch of `subTypeDispatchProps` registers `prop + "__" + pe.key()` per ctor leaf, gated on `isThreadProjectable`
- core/src/main/java/com/legend/normalizer/UnionSynthesis.java:932-963 — `addStcEmbeddedLeaf` emits the column as `ClassMapping.subTypeColumn(target, flatProp)` where flatProp is `<prop>__<leaf>`
- core/src/main/java/com/legend/normalizer/UnionSynthesis.java:1237-1252 — `isThreadProjectable` accepts one-hop `$row.col` AND two-hop `$row.slot.col` ("an embedded sub bound THROUGH a join reads its emitted pipeline slot")
- core/src/main/java/com/legend/normalizer/MappingNormalizer.java:2617-2626 — the `JoinTerminalColumn` arm builds `AppliedProperty(AppliedProperty(rowBind, alias), terminalCol)` — the two-hop shape isThreadProjectable accepts
- core/src/main/java/com/legend/model/MappingFromProtocol.java:651-660 — `@Join | Table.col` becomes `PropertyMapping.JoinTerminalColumn`
- core/src/main/java/com/legend/model/ClassMapping.java:53-64 — the naming contract: `subTypeColumn(classFqn, prop) = "stc_" + mangled + "___" + prop`; both ends are required to call it and neither may re-derive by pattern
- core/src/main/java/com/legend/resolver/ClassSources.java:688-700 — every `stc_` column of the row type is exposed as a binding keyed by its own column name
- core/src/main/java/com/legend/resolver/SyntheticHeads.java:285-299 — `canon` is applied at each node BEFORE the lift arms and before recursing, i.e. top-down: the outer `.name` node is visited before its `subType(...).ext1Address` child
- core/src/main/java/com/legend/resolver/StoreResolver.java:2569-2571 — `synthetics.setCanonicalizer(nn -> corrSubs.subTypeNavCastCanon(nn, ...)); top = synthetics.liftFilteredHeads(top);` runs BEFORE `collectOpDemand`
- core/src/main/java/com/legend/resolver/Substitution.java:1739-1745 — `filter($userVar, pred).prop` routes to `filteredInstanceRead`
- core/src/main/java/com/legend/resolver/Substitution.java:1903-1911 — `filteredInstanceRead` re-issues `rewrite($p.<prop>)`, producing a 1-element path
- core/src/main/java/com/legend/resolver/Substitution.java:1421-1427 — the throw site: `"property '" + prop + "' of class '" + target.classFqn() + "' has no binding in mapping '" + target.mappingFqn() + "' (unmapped, or routed to a non-root mapping set — multi-set union dispatch is a roadmap feature)"` — matches the observed message verbatim, including the raw (non-displayName) stc spelling
- core/src/main/java/com/legend/resolver/Substitution.java:1322-1332 — the flattening arm ALREADY exists, but only for the assoc-head position (`path.size() >= 3 && isSubTypeColumn(path.get(1)) && assocs.containsKey(path.get(0))`), i.e. `$x.<assoc>-><cast>.<emb>.<leaf>`; nothing covers the depth-1/root position
- core/src/main/java/com/legend/resolver/Substitution.java:743-772 — `pathOf`'s subType arm mints `subTypeColumn(sct.fqn(), pa0.property())` for the cast hop and lets the trailing leaf become a separate path element — the same unflattened assumption as the canonicalizer

</details>

---

## `testFilterTimesWithManyOperands`

| | |
|---|---|
| family | `tests/query` |
| sweep status | ERROR |
| **verdict** | **MISSING FEATURE** |
| effort | M |
| confidence | medium |

**Root cause**

`CorrelatedSubselects.aggScan` dispatches aggregated navigations by the SHAPE of the property path, and it has no arm for `[to-one hop, to-many hop, leaf]`. The qualified property `sumEmployeesAge(){$this.employees.age->sum()}` (simpleTestModel.pure:41) inlines into `$p.firm->toOne().employees.age->sum()`, so `Substitution.pathOf` yields `[firm, employees, age]` (the `->toOne()` is peeled by pathOf's wrapper handling).

Walking aggScan's arms for that path:
- `path.size() == 2 && toManyHead(path.get(0))` (:2034) — no, size is 3.
- the sortBy / computed-mapper arms (:1977, :1996) — no, the arg is a bare property chain.
- BARE-HEAD COUNT (:2043) — no, size is 3 and sum is not count-family.
- CHAIN bare count over a to-one hop (:2069-2103) — this is the arm whose SHAPE matches (to-one head, to-many hop behind it, dotted `mid.final` demand key), but it is gated on `isCountFamily(nc)` and on `path.size() == 2`.
- DEEP leaf / audit-9 loud fallthrough (:2104, :2123) — both gated on `toManyHead.test(cs, path.get(0))`, and `firm` is to-one, so both are skipped.
- STUDY #12 wall (:2141-2153) — `path.size() >= 2 && !toManyHead(path.get(0)) && isElidingReducer(sum) && arg multiplicity is not [1]` — all true, so it throws the observed message.

So the wall is honest (it prevents a real wrong-rows class: a bare demand for `firm.employees.age` would explode the join and `Scalars`' to-one identity elision would silently eat the `sum`). The surface is genuinely absent: only the count-family sibling of this exact shape is implemented.

Crucially, the machinery the fix needs already exists and is already exercised: `buildAggMaterials` splits a dotted key into mid + final, materializes the mid as an ordinary nav join and anchors the final's aggregate material at the mid's target ClassSource (CorrelatedSubselects.java:100-121); `foldChainMid` emits the mid LEFT join with a chain-private prefix and re-points the final association condition onto it (:141-190); `StoreResolver.foldAssociationJoins` calls both (:2002-2007). `testQualifiedPropertyUsingColumnProtocol` (testAssociationToMany.pure:49-54, `$p.firm->toOne().employeesByAge($firmAge)->count()`) rides exactly this path and is NOT in the failing set, so the chain route is proven end-to-end.

**Fix**

Generalize the CHAIN arm in `CorrelatedSubselects.aggScan` (core/src/main/java/com/legend/resolver/CorrelatedSubselects.java:2069-2103) from 'count over a 2-element to-one-headed path' to 'any aggregate over a path whose to-many hop is at index 1 behind a to-one head', and narrow the STUDY #12 wall (:2141-2153) so it only covers what remains unhandled.

Step 1 — a helper that finds the depth at which the chain becomes to-many, from the EXPRESSION nodes (the `toManyHead` BiPredicate cannot be used past index 0 because aggScan has no ClassSource for the mid hop's target):

```java
/** The prefix node of {@code arg} whose path has exactly {@code len}
 *  elements (toOne/property wrappers peeled), or null. */
private static @Nullable TypedSpec prefixNodeAt(TypedSpec arg, String userVar, int len) {
    TypedSpec cur = arg;
    while (cur != null) {
        List<String> p = Substitution.pathOf(cur, userVar);
        if (p == null) { return null; }
        if (p.size() == len) { return cur; }
        if (p.size() < len) { return null; }
        cur = cur instanceof TypedPropertyAccess pa ? pa.source()
                : cur instanceof TypedNativeCall c && c.args().size() == 1
                    && c.callee().qualifiedName().equals(
                        "meta::pure::functions::multiplicity::toOne")
                  ? c.args().get(0) : null;
    }
    return null;
}

private static boolean isToMany(@Nullable TypedSpec n) {
    return n != null && !(n.info().multiplicity()
            instanceof Multiplicity.Bounded b && Integer.valueOf(1).equals(b.upper()));
}
```

Step 2 — replace the `path.size() == 2 && isCountFamily(nc)` gate of the chain arm with a shape gate that admits both the existing count case and the new leaf case. Concretely, after the existing count arm, add:

```java
// CHAIN LEAF over a TO-ONE hop's to-many navigation
// ($p.firm.employees.age->sum(), the qualifier-inlined
// sumEmployeesAge spelling): same dotted-key route as the count
// sibling — buildAggMaterials anchors the material at the mid
// hop's target class and foldChainMid emits the mid LEFT join
// plus the re-pointed join-back condition.
if (path != null && path.size() == 3
        && !toManyHead.test(cs, path.get(0))
        && bareHead.test(cs, path.get(0))
        && !isToMany(prefixNodeAt(nc.args().get(0), userVar, 1))
        && isToMany(prefixNodeAt(nc.args().get(0), userVar, 2))) {
    aggOut.computeIfAbsent(path.get(0) + "." + path.get(1),
                    k -> new ArrayList<>())
            .add(new StoreResolver.AggDemand(nc, path.get(2)));
    for (int i = 1; i < nc.args().size(); i++) {
        aggScan(nc.args().get(i), userVar, cs, aggOut, bareOut,
                toManyHead, bareHead);
    }
    return;   // the path is agg-consumed, not bare
}
```

The `!isToMany(prefix@1) && isToMany(prefix@2)` pair is load-bearing: it proves the to-many is at hop 1 (so the dotted `mid.final` key is expressible) and not at hop 2 (`firm.address.<many>` would need a 2-hop mid, which `buildAggMaterials`' single `indexOf('.')` split cannot express).

Step 3 — the STUDY #12 wall at :2141 stays exactly as written. It now simply stops firing for this shape because the new arm `return`s first. Deeper shapes (to-many at index >= 2, path length > 3, non-peelable spellings) still hit it, which is correct — do not weaken it.

No change is needed downstream: `buildAggMaterials` already routes dotted keys (:100-121), `foldChainMid` already emits the mid join and re-points the final condition (:141-190), and `aggColFor`'s leaf branch already resolves `d.leaf()` against `aj.target().bindings()` (:902-916) — the target here being the Firm-anchored `employees` material, i.e. Person, whose `age` binding is `personTable.AGE`.

**How legend-engine does it** — /Users/neemsandv/legend/legend-engine/legend-engine-xts-relationalStore/legend-engine-xt-relationalStore-generation/legend-engine-xt-relationalStore-pure/legend-engine-xt-relationalStore-core-pure/src/main/resources/core_relational/relational/pureToSQLQuery/pureToSQLQuery.pure:4070-4093 — `meta::relational::functions::pureToSqlQuery::manageAggregation`: when `$operation.currentTreeNode` has children, it `addSelfJoin`s the node to cut and calls `buildCorrelatedSubQuery(..., shouldIsolateGroupBy = true, ...)`. The routing is on the JOIN-TREE NODE the navigation reached, with no notion of how many hops from the root it is — which is why the engine treats `$f.employees.age->sum()` and `$p.firm.employees.age->sum()` identically and legend-lite's path-length dispatch in `aggScan` is the structural divergence. `buildCorrelatedSubQuery` itself is at pureToSQLQuery.pure:1181-1290 (the `aggCol` alias is minted at :1253).

**Risk** — Two things to watch.

(1) SQL SHAPE vs ENGINE. The engine's golden puts the aggregate in a PARENT-COPY subselect (`select firmtable_2.ID, sum(persontable_1.AGE) from firmTable as firmtable_2 left join personTable as persontable_1 ... group by firmtable_2.ID`), because `manageAggregation` isolates the current tree node with `addSelfJoin` first. legend-lite's `corrAggSubSource` (CorrelatedSubselects.java:200-215) takes the plain TARGET-GROUPED shape whenever `targetEquiKeysOrNull(aj.condition())` is non-null and the position is not a filter — so it will likely emit `select FIRMID, sum(AGE) from personTable group by FIRMID` instead. That is a text divergence, NOT a row divergence, and the harness row-verifies goldens rather than text-comparing them (EngineTestExecutor.java:1986-1997 → h2Upgrade). Do not chase the text.

(2) The second half of the test uses `times([$p.age->toOne(), 2, $p.firm->toOne().sumEmployeesAge(), 100])`, which `Scalars` lowers to `LIST_PRODUCT` over an array literal rather than an n-ary `*` (Scalars.java:196-208, the `args.size() == 1` branch). That path is already exercised and passing by `testDivideFunctionPrecision` (testWithFunction.pure, `[$t.quantity, 32147678342]->times()->divide(1000)` with an exact `assertEq` on the value), so it should be fine — but if this test still fails after the agg fix with a numeric-type mismatch on the second assert, the honest follow-up is in the `times` rule (fold a literal collection arg into an n-ary TIMES), not in the harness comparator.

Tenet-2 trap: do NOT relax `isElidingReducer` or delete the STUDY #12 wall to make this pass — the wall exists because a bare demand for `firm.employees.age` produces silently wrong rows.

**Also unblocks** — None known — `study #12` appears exactly once in the sweep output (docs/RELATIONAL_CORPUS_ALL.md:1531), so this is the only test currently behind that wall.

**Falsifier** — Run the test after only Step 2 and inspect the emitted SQL. If the mid hop `left outer join firmTable as "..." on (... = "root".FIRMID)` is NOT emitted, or the grouped subselect joins back on something other than the mid hop's key, then `foldChainMid`'s re-pointing does not survive a leaf-form (non-mapper) demand and the diagnosis that the chain machinery is demand-form-agnostic is wrong. Cheaper still: confirm `testQualifiedPropertyUsingColumnProtocol` (testAssociationToMany.pure:49) really passes today — if it does not, the chain route is not proven and this fix needs the mapper form plus its own emission work.

<details><summary>Evidence read (15 citations)</summary>

- /Users/neemsandv/legend/legend-engine/.../core_relational/relational/tests/query/testWithFunction.pure:434-441 — the test: `p|$p.age->toOne() * 2 * $p.firm->toOne().sumEmployeesAge()` and the `times([...])` many-operand spelling
- /Users/neemsandv/legend/legend-engine/.../core_relational/relational/tests/testModel/simpleTestModel.pure:41 — `sumEmployeesAge(){$this.employees.age->sum()}:Integer[1];`
- core/src/main/java/com/legend/resolver/CorrelatedSubselects.java:2141-2153 — the STUDY #12 guard and its throw; the message text matches the observed failure verbatim
- core/src/main/java/com/legend/resolver/CorrelatedSubselects.java:2069-2103 — the CHAIN arm: gated on `isCountFamily(nc)` and `path.size() == 2`, registers under the dotted key `path.get(0) + "." + path.get(1)` with a const mapper
- core/src/main/java/com/legend/resolver/CorrelatedSubselects.java:2034-2041 — the depth-1 leaf arm: `new StoreResolver.AggDemand(nc, path.get(1))` — the leaf-form demand the chain arm should reuse for a 3-element path
- core/src/main/java/com/legend/resolver/CorrelatedSubselects.java:2123-2133 and :2104-2122 — both the DEEP-leaf arm and the audit-9 loud fallthrough are gated on `toManyHead.test(cs, path.get(0))`, which is false for a to-one head
- core/src/main/java/com/legend/resolver/CorrelatedSubselects.java:2225-2231 — `isElidingReducer` = sum/average/mean only; count/joinStrings deliberately stay allowed
- core/src/main/java/com/legend/resolver/CorrelatedSubselects.java:100-121 — `buildAggMaterials` dotted-key branch: mid via `aggJoinMaterial(temporal, cs, mid, ...)`, final via `aggJoinMaterial(temporal, midAj.target(), fin, context, leaves, mapperPaths)`
- core/src/main/java/com/legend/resolver/CorrelatedSubselects.java:141-190 — `foldChainMid`: emits the mid LEFT join under a chain-private prefix and re-points the final condition's parent-side reads onto it (walls only for filter position / outer-correlated predicates)
- core/src/main/java/com/legend/resolver/CorrelatedSubselects.java:854-932 — `aggColFor` uses `head` only for messages; the leaf form resolves through `aj.target().bindings().get(d.leaf())`, so the dotted key is transparent to it
- core/src/main/java/com/legend/resolver/StoreResolver.java:1993-2007 — the fold looks up `aggMaterials.get(head)` and, when `chainMids.get(head)` is non-null, runs `foldChainMid` before building the grouped subselect
- core/src/main/java/com/legend/resolver/StoreResolver.java:3073-3085 — `AggDemand(node, leaf)` and `AggDemand(node, leaf, mapper)` convenience constructors
- core/src/main/java/com/legend/resolver/StoreResolver.java:3183-3201 — `isToManyAssocHead(cs, head)` is the `toManyHead` predicate: it needs a ClassSource, which aggScan does not have for the MID hop's target — hence the multiplicity must be read off the expression nodes instead
- /Users/neemsandv/legend/legend-engine/.../core_relational/relational/functions/tests/projection/testAssociationToMany.pure:49-54 — `testQualifiedPropertyUsingColumnProtocol`, the count-family sibling of this exact chain shape; it is not in the failing set, so the chain machinery works
- core/src/main/java/com/legend/harness/EngineTestExecutor.java:1986-1997 and :1027-1090 — `assertSameSQL` does NOT do a literal text compare: `h2Upgrade` replays the recorded seeds on H2, runs the GOLDEN there and compares its rows against our DuckDB rows (or returns the advisory marker). So the fix must produce correct ROWS, not the engine's exact SQL text.

</details>

---
