# Granular fallback ledger — 2026-09-06 (after batch 105)

[batch 113, 2026-09-06: 124/2449 — two IMPL rows REOPENED under the statement splice (m2m2r::executeProjectWithNestedDerivedProperty, paginate::testPaginated); see BURN_BREAKDOWN status.]

130 fallbacks of 2575 runnable tests (2443 flipped). [batch 106, same day: isolationTest burned → 129/2444; this snapshot is otherwise unchanged.] One entry per test: the run's bucket (the first failing assert's reason; the runner normalizes names to `_` and numbers to `N`), the breakdown's classification and row, and every assert row from the assert ledger. Built from `core/target/wholetest-flip-buckets.txt` (DuckDB lane), `docs/RELATIONAL_CORPUS.md` (assert ledger) and `docs/BURN_BREAKDOWN_2026_09_05.md`.


## 1. IMPL (14)

- **inheritance** — `pure::executionPlan::tests` [L1 Resolver: navigation shapes (15 tests)]
    - run bucket: `wall-exec: plan: no class mapping for '_' under 'meta::relational::tests::ma`
    - breakdown: | inheritance::multiJoins::testForcedSubTypeProjectDirect | FLIPPED batch 95 — the cast canon reads the union's plain lifted `person` slot; the read through the witness f
    - assert #1 assertEquals → `wall:exec`: wall-exec: plan: no class mapping for '_' under 'meta::relational::tests::ma :: plan: no class mapping for 'meta::relational::tests::model::
- **testInheritanceMultipleLevel** — `relational::testDataGeneration::tests` [L1 Resolver: navigation shapes (15 tests)]
    - run bucket: `wall-exec: multi-hop navigation vehicles#fN.stc_meta__relational__tests__model__inheritance__Bicycle___person.name through an embed`
    - breakdown: | testDataGeneration::testInheritanceMultipleLevel | multi-hop vehicles#f.subType.person.name | TDG rows |
    - assert #7 - → `wall:resolver`: wall-exec: multi-hop navigation vehicles#fN.stc_meta__relational__tests__model__inheritance__Bicycle___person.name through an embed :: multi
    - asserts passing: #1, #2, #3, #4, #5, #6
- **isolationTest** — `tests::advanced::forcedselfjoin` [L1 Resolver: navigation shapes (15 tests)]
    - run bucket: `wall-exec: correlated filter predicate on hop '_' at depth N of the navigation employees.group.children.name has no applicat`
    - breakdown: | advanced::forcedselfjoin::isolationTest | correlated filter predicate at depth ≥ 2 (batch 69b wall) | rows |
    - assert #1 assertEquals → `wall:resolver`: wall-exec: correlated filter predicate on hop '_' at depth N of the navigation employees.group.children.name has no applicat :: correlated f
- **testToManyWithQualifierWithFilterOnJoin** — `tests::mapping::multigrain` [L1 Resolver: navigation shapes (15 tests)]
    - run bucket: `wall-exec: multi-hop navigation account.incomeFunctionSplits#fN.incomeFunction.Classification.name through an embedded/slot head is`
    - breakdown: | multigrain::testToManyWithQualifierWithFilterOnJoin | multi-hop through an embedded/slot head | rows [500] + text behind |
    - assert #1 assertSameElements → `wall:resolver`: wall-exec: multi-hop navigation account.incomeFunctionSplits#fN.incomeFunction.Classification.name through an embedded/slot head is :: multi
- **testExistsAsNullWithSubType** — `tests::projection::exists` [L1 Resolver: navigation shapes (15 tests)] — **LANDED batch 140 (§12)**
    - PROBED 2026-09-07 (unattended; stacks): the wall is `Substitution.assocLeaf` (leafBinding == null under a
      NESTED target) reached from `rewriteExists` → `rewriteLambda` → `rewritePath`: the head `fnScope` IS registered
      in the exists scope (an AssocSub from `CorrelatedSubselects.nestedAssocMaterials` → `AssociationJoins.aggJoinMaterial`
      → `sources.get(mapping, FunctionScope)`), but its target bindings have no `stc_<Public>___id`. Why: `ClassSources`
      synthesizes stc pseudo-bindings only for subclasses mapped over the SAME root table as the parent source
      (`sameRootTable`); here FunctionScope has no mapping of its own, Private[map2] is on privateFn, Public[map3] on
      publicFn, and the property is routed PER TARGET SET (`fnScope[map2]: @privateFnJoin`, `fnScope[map3]: @publicFnJoin`).
      The engine's golden lowers `$f.fnScope->subType(@Public).id->isNotEmpty()` inside the exists subselect as ONE
      left join of the map3 route (publicFn via publicFnJoin) with `"publicfn_0".id is not null` — the cast selects the
      route; no union. THE LEG: a class-typed property with per-target-set Join PMs navigated through `subType(@X)`
      resolves to the PM route whose target set is X's set (join that set's table, read the leaf), at the top level and
      in nested (exists) scopes — the nested AssocSub must carry per-route targets. Design leg (routing a subtype cast to
      a PM route), not a fix: NOT attempted unattended (hard-stop rule).
    - run bucket: `wall-exec: nested navigation '_' inside an exists/isEmpty predic`
    - breakdown: | projection::exists::testExistsAsNullWithSubType | nested navigation inside exists/isEmpty | rows + assertSameSQL (text behind) |
    - assert #1 assertSize → `wall:resolver`: wall-exec: nested navigation '_' inside an exists/isEmpty predic :: nested navigation 'fnScope.stc_meta__relational__tests__projection__exis
- **testRoutingWithSubtypePropagation** — `tests::projection::simple` [L1 Resolver: navigation shapes (15 tests)]
    - run bucket: `wall-exec: multi-hop navigation employees.stc_meta__relational__tests__model__simple__PersonExtension___manager.stc_meta__relationa`
    - breakdown: | projection::simple::testRoutingWithSubtypePropagation | multi-hop (subType chain) through an embedded head | assertEquals on SQL text ONLY → TEXT behind the wall |
    - assert #1 assertEquals → `wall:resolver`: wall-exec: multi-hop navigation employees.stc_meta__relational__tests__model__simple__PersonExtension___manager.stc_meta__relationa :: multi
- **planGraphFetchWithNestedDerivedProperty** — `executionPlan::m2m2r::tests` [L13 Model chain over relational (m2m2r) and derived properties (5)]
    - run bucket: `wall-exec: class query under TypedGraphFetch is not resolvable yet (HN vocabulary)`
    - breakdown: | m2m2r::planGraphFetchWithNestedDerivedProperty | same |
    - assert #1 assertEquals → `wall:resolver`: wall-exec: class query under TypedGraphFetch is not resolvable yet (HN vocabulary) :: class query under TypedGraphFetch is not resolvable ye
- **testUnionTwoRelationMappings_ManyColumnProject** — `mapping::union::relation` [L3 Relation-mapping family (4)]
    - run bucket: `platform-fail: expected: '#TDS\n   cN,cN,cN,cN,cN,cN,cN,cN,cN,cN,cN,cN\n   Anand,null,Anand,null,Anand,null,Anand,null,Anand,null,Ana`
    - breakdown: | union::relation::testUnionTwoRelationMappings_ManyColumnProject | DIVERGENCE: 12-column distinct over a union of two relation mappings — TRACED 2026-09-06 (revisit): ou
    - assert #1 assertEquals → `divergence`: platform-fail: expected: '#TDS\n cN,cN,cN,cN,cN,cN,cN,cN,cN,cN,cN,cN\n Anand,null,Anand,null,Anand,null,Anand,null,Anand,null,Ana :: expecte
- **testUnionTwoRelationMappings_ManyColumnProjectGeneratesSingleUnion** — `mapping::union::relation` [L3 Relation-mapping family (4)]
    - run bucket: `platform-fail: expected: '#TDS\n   cN,cN,cN,cN,cN,cN,cN,cN,cN,cN,cN,cN\n   Anand,null,Anand,null,Anand,null,Anand,null,Anand,null,Ana`
    - breakdown: | union::relation::testUnionTwoRelationMappings_ManyColumnProjectGeneratesSingleUnion | same |
    - assert #1 assertEquals → `divergence`: platform-fail: expected: '#TDS\n cN,cN,cN,cN,cN,cN,cN,cN,cN,cN,cN,cN\n Anand,null,Anand,null,Anand,null,Anand,null,Anand,null,Ana :: expecte
- **testPersonToFirmUsingFromProject** — `relational::modelJoins::test` [L5 Cross-store model joins as relational joins (4)]
    - run bucket: `wall-exec: MappingResolutionException: association '_' is not mapped in mapping 'meta::ext`
    - breakdown: | modelJoins::testPersonToFirmUsingFromProject | association not mapped — asserts the XStore plan's SQL EQUALS the single-store plan's SQL (a semantic equality, not a spe
    - assert #1 assertEquals → `wall:resolver`: wall-exec: MappingResolutionException: association '_' is not mapped in mapping 'meta::ext :: association 'meta::external::store::relational
- **testPersonToFirmUsingProject** — `relational::modelJoins::test` [L5 Cross-store model joins as relational joins (4)]
    - run bucket: `assert-free-inert`
    - breakdown: | modelJoins::testPersonToFirmUsingProject | assert-free (zero-assert) — runs the same XStore shape |
    - assert #0 - → `zero-assert`: assert-free-inert :: meta::external::store::relational::modelJoins::test::testPersonToFirmUsingProject
- **testCrossMappingWithRelOpWithJoinKeys** — `graphFetch::tests::crossDatabase` [L5 Cross-store model joins as relational joins (4)]
    - run bucket: `wall-exec: MappingResolutionException: association '_' is not mapped in mapping 'meta::`
    - breakdown: | graphFetch::crossDatabase::testCrossMappingWithRelOpWithJoinKeys | association not mapped — XStore association with join keys across two databases |
    - assert #1 assertJsonStringsEqual → `wall:resolver`: wall-exec: MappingResolutionException: association '_' is not mapped in mapping 'meta:: :: association 'meta::relational::graphFetch::tests:
- **testNestedModelJoinCompoundInnerCondition** — `mapping::modelJoin::advanced` [L5 Cross-store model joins as relational joins (4)]
    - run bucket: `wall-exec: MappingResolutionException: association '_' is not mapped in mapping 'meta::relationa`
    - breakdown: | modelJoin::advanced::testNestedModelJoinCompoundInnerCondition | association not mapped — compound inner condition in a model join |
    - assert #1 assertEquals → `wall:resolver`: wall-exec: MappingResolutionException: association '_' is not mapped in mapping 'meta::relationa :: association 'meta::relational::tests::ma
- **testPostProcessTransformJoinOp** — `relational::tests::postProcessor` [L7 Post-processors as compiler passes (4)]
    - run bucket: `wall-exec: TypeInferenceException: in function '_': unknown function '_' — n`
    - breakdown: | postProcessor::testPostProcessTransformJoinOp | a connection `sqlQueryPostProcessors` lambda over the SQL AST — the ONE post-processor test that is a user-supplied pass
    - assert #1 assertEquals → `wall:lowering`: wall-exec: TypeInferenceException: in function '_': unknown function '_' — n :: in function 'meta::relational::functions::sqlDialectTranslat

## IMPL (parked) (2)

- **testPksWithImportDataFlow** — `tests::mapping::union` [L4 Union / isolation / join fan-out (6)]
    - run bucket: `wall-type: multiplicity [*] is not compatible with [N]`
    - breakdown: | union::testPksWithImportDataFlow | PARKED 2026-09-06 (batch 105 note, handoff): seams 1-2 mechanical (the 5-arg execute overload with exeCtx; flags on ExecEnv), seam 3 
    - assert #0 - → `wall:typer`: wall-type: multiplicity [*] is not compatible with [N] :: meta::relational::tests::mapping::union::testPksWithImportDataFlow :: multiplicity
- **testNonDataTypeProperty** — `lineage::scanColumns::test` [L9 Lineage row verdicts (2)]
    - run bucket: `wall-exec: class query under TypedMap is not resolvable yet (HN vocabulary)`
    - breakdown: | lineage::scanColumns::testNonDataTypeProperty | PARKED 2026-09-06 (batch 104 note): a CLASS-typed project column (`p|$p.address`) has no SQL value form yet — the whole-
    - assert #1 assertEquals → `wall:resolver`: wall-exec: class query under TypedMap is not resolvable yet (HN vocabulary) :: class query under TypedMap is not resolvable yet (H2 vocabula

## REVISIT (5)

- **testBusinessDateInjectionFromVarReferenceInProjectUsingExternalFunction** — `tests::milestoning::businessdate` [L1 Resolver: navigation shapes (15 tests)]
    - run bucket: `platform-fail: assertSameSQL (sql-text ROW verdict — golden rows vs ours diverged, whatever the text said): hN-advisory divergence: gol`
    - breakdown: | businessdate::testBusinessDateInjectionFromVarReferenceInProjectUsingExternalFunction | REVISIT (batch 93 receipt `revisit:instance-filter-ungated` — traced, NOT resolv
    - assert #1 assertSameSQL → `revisit:instance-filter-ungated`: platform-fail: assertSameSQL (sql-text ROW verdict — golden rows vs ours diverged, whatever the text said): hN-advisory divergence: gol :: a
- **testMixedMappingWithFilterInProject** — `tests::mapping::relation` [L3 Relation-mapping family (4)]
    - run bucket: `wall-exec: a navigation join over this union demands key column '_', which NO union member carries`
    - breakdown: | relation::testMixedMappingWithFilterInProject | REVISIT (batch 96 receipt `revisit:relation-mapping-filter-alias-root` — traced, NOT resolved; user 2026-09-06) — the sa
    - assert #1 assertEquals → `wall:resolver`: wall-exec: a navigation join over this union demands key column '_', which NO union member carries :: a navigation join over this union dema
- **testSimpleMappingQueryWithFilterInProject** — `tests::mapping::relation` [L3 Relation-mapping family (4)]
    - run bucket: `platform-fail: expected: '_'`
    - breakdown: | relation::testSimpleMappingQueryWithFilterInProject | REVISIT (batch 96 receipt `revisit:relation-mapping-filter-alias-root` — traced, NOT resolved; user 2026-09-06) — 
    - assert #1 assertEquals → `revisit:relation-mapping-filter-alias-root`: platform-fail: expected: '_' :: expected: '#TDS\n name1,name2\n David,null\n Fabrice,null\n John,John\n Oliver,Fabrice\n Oliver,Oliver\n#' a
- **testCheckedWithCircularConstraints** — `graphFetch::tests::simple` [L6 Graph fetch (4)]
    - run bucket: `platform-fail: assertJsonStringsEqual: FIRST DIFF at $[N].defects expected N element(s), got N`
    - breakdown: | graphFetch::simple::testCheckedWithCircularConstraints | REVISIT (batch 105 receipt `revisit:engine-isDistinct-checked-defect` — traced, NOT resolved) — the engine's ow
    - assert #1 assertJsonStringsEqual → `revisit`: revisit:engine-isDistinct-checked-defect: platform-fail: assertJsonStringsEqual: FIRST DIFF at $[N].defects expected N element(s), got N :: 
- **test6** — `tests::union::propertyLevel` [L6 Graph fetch (4)]
    - run bucket: `platform-fail: assertJsonStringsEqual: FIRST DIFF at $[N].legalName expected Firm B, got Firm X`
    - breakdown: | graphFetch::union::propertyLevel::test6 | REVISIT (batch 105 receipt `revisit:h2-distinct-root-order` — traced, NOT resolved) — same row set; the engine's graph-fetch r
    - assert #1 assertJsonStringsEqual → `revisit`: revisit:h2-distinct-root-order: platform-fail: assertJsonStringsEqual: FIRST DIFF at $[N].legalName expected Firm B, got Firm X :: assertJso

## 2. TEXT (43)

- **testExecutionPlanGenerationForLambdaFromWithEnumMapping** — `pure::executionPlan::tests` [T1 `assert($sql->contains('<engine alias>'))` over OUR text (11)]
    - run bucket: `platform-fail: Assert failed`
    - breakdown: | executionPlan::testExecutionPlanGenerationForLambdaFromWithEnumMapping | the exact CASE WHEN spelling of enum push-down |
    - assert #1 assert → `divergence`: platform-fail: Assert failed :: Assert failed
- **testBiTemporalUnionAsJoinTarget_correlatedSubqueryQuoting** — `mapping::union::biTemporal` [T1 `assert($sql->contains('<engine alias>'))` over OUR text (11)]
    - run bucket: `platform-fail: Assert failed`
    - breakdown: | biTemporal::testBiTemporalUnionAsJoinTarget_correlatedSubqueryQuoting | `unionalias_1`/`unionalias_0` quoting |
    - assert #1 assert → `divergence`: platform-fail: Assert failed :: Assert failed
- **testBiTemporalUnionJoin_milestoningColumnInOnClause** — `mapping::union::biTemporal` [T1 `assert($sql->contains('<engine alias>'))` over OUR text (11)]
    - run bucket: `platform-fail: Assert failed`
    - breakdown: | biTemporal::testBiTemporalUnionJoin_milestoningColumnInOnClause | same family |
    - assert #1 assert → `divergence`: platform-fail: Assert failed :: Assert failed
- **testChainedUnions** — `tests::mapping::union` [T1 `assert($sql->contains('<engine alias>'))` over OUR text (11)]
    - run bucket: `platform-fail: Assert failed`
    - breakdown: | union::testChainedUnions | `union_gen_source_pk_0` (removeUnionOrJoins spelling) |
    - assert #3 assert → `sql-text-assert`: platform-fail: Assert failed :: Assert failed
    - asserts passing: #1, #2
- **testProjectThroughAsso** — `tests::mapping::union` [T1 `assert($sql->contains('<engine alias>'))` over OUR text (11)]
    - run bucket: `platform-fail: Assert failed`
    - breakdown: | union::extend::testProjectThroughAsso | same |
    - assert #3 assert → `sql-text-assert`: platform-fail: Assert failed :: Assert failed
    - asserts passing: #1, #2
- **testProjectThroughAssoWithJoinInMapping** — `tests::mapping::union` [T1 `assert($sql->contains('<engine alias>'))` over OUR text (11)]
    - run bucket: `platform-fail: Assert failed`
    - breakdown: | union::extend::testProjectThroughAssoWithJoinInMapping | same |
    - assert #3 assert → `sql-text-assert`: platform-fail: Assert failed :: Assert failed
    - asserts passing: #1, #2
- **testUnionWithSinglePropertyMapping** — `tests::mapping::union` [T1 `assert($sql->contains('<engine alias>'))` over OUR text (11)]
    - run bucket: `platform-fail: Assert failed`
    - breakdown: | union::testUnionWithSinglePropertyMapping | same (+ assertSameSQL) |
    - assert #2 assert → `sql-text-assert`: platform-fail: Assert failed :: Assert failed
    - asserts passing: #1
- **testUnionOnViewsMapping** — `tests::projection::view` [T1 `assert($sql->contains('<engine alias>'))` over OUR text (11)]
    - run bucket: `platform-fail: Assert failed`
    - breakdown: | projection::view::testUnionOnViewsMapping | same |
    - assert #3 assert → `sql-text-assert`: platform-fail: Assert failed :: Assert failed
    - asserts passing: #1, #2
- **testLegacyFlagProjectionEmitsPlainEquals** — `tests::query::legacyNullUnsafeEquals` [T1 `assert($sql->contains('<engine alias>'))` over OUR text (11)]
    - run bucket: `platform-fail: Assert failed`
    - breakdown: | legacyNullUnsafeEquals::testLegacyFlagProjectionEmitsPlainEquals | plan text `"root".AGE = "persontable_1".AGE` under a feature flag |
    - assert #1 assert → `divergence`: platform-fail: Assert failed :: Assert failed
- **testLegacyFlagRestoresOptionalParamFreeMarkerSelector** — `tests::query::legacyNullUnsafeEquals` [T1 `assert($sql->contains('<engine alias>'))` over OUR text (11)]
    - run bucket: `platform-fail: Assert failed`
    - breakdown: | legacyNullUnsafeEquals::testLegacyFlagRestoresOptionalParamFreeMarkerSelector | FreeMarker selector text under the flag |
    - assert #1 assert → `divergence`: platform-fail: Assert failed :: Assert failed
- **testRestrictOnGroupByEleminatesUnnecessaryAggsWithDistinct** — `tests::tds::tdsRestrict` [T1 `assert($sql->contains('<engine alias>'))` over OUR text (11)]
    - run bucket: `platform-fail: Assert failed`
    - breakdown: | tdsRestrict::testRestrictOnGroupByEleminatesUnnecessaryAggsWithDistinct | `!contains('max')` — the engine prunes an unused aggregate; rows PASS. An OPTIMIZATION we coul
    - assert #4 assertFalse → `sql-text-assert`: platform-fail: Assert failed :: Assert failed
    - asserts passing: #1, #2, #3
- **tdsTwoJoinThreeDB** — `pure::executionPlan::tests` [T2 Plan-text goldens (executionPlan printed as a string) (13)]
    - run bucket: `wall-exec: plan: star-top TDS column '_' resolves through no FROM-tree table`
    - breakdown: | executionPlan::tdsTwoJoinThreeDB | plan text (+ star-top column wall first) |
    - assert #1 assertEquals → `wall:exec`: wall-exec: plan: star-top TDS column '_' resolves through no FROM-tree table :: plan: star-top TDS column 'firstName' resolves through no FR
- **testCrossDbPlanGenerationWithRelationFromWithOnlyRuntimes** — `pure::executionPlan::tests` [T2 Plan-text goldens (executionPlan printed as a string) (13)]
    - run bucket: `wall-exec: executionPlan mapping argument must be a reference (or the query must carry ->from), got TypedNativeCall`
    - breakdown: | executionPlan::testCrossDbPlanGenerationWithRelationFromWithOnlyRuntimes | plan text (+ mapping-argument wall first) |
    - assert #1 assertEquals → `wall:exec`: wall-exec: executionPlan mapping argument must be a reference (or the query must carry ->from), got TypedNativeCall :: executionPlan mapping
- **testGroupByWithOpenVariableInAgg** — `pure::executionPlan::tests` [T2 Plan-text goldens (executionPlan printed as a string) (13)]
    - run bucket: `platform-fail: assertEqualsHNCompatible (sql-text, rows underivable): expected Sequence`
    - breakdown: | executionPlan::testGroupByWithOpenVariableInAgg | Sequence plan with OPEN variables — rows underivable |
    - assert #1 assertEqualsH2Compatible → `referee-cannot-replay`: platform-fail: assertEqualsHNCompatible (sql-text, rows underivable): expected Sequence :: assertEqualsH2Compatible (sql-text, rows underiva
- **testGroupByWithTwoOpenVariablesInAggAndFilter** — `pure::executionPlan::tests` [T2 Plan-text goldens (executionPlan printed as a string) (13)]
    - run bucket: `platform-fail: assertEqualsHNCompatible (sql-text, rows underivable): expected Sequence`
    - breakdown: | executionPlan::testGroupByWithTwoOpenVariablesInAggAndFilter | same |
    - assert #1 assertEqualsH2Compatible → `referee-cannot-replay`: platform-fail: assertEqualsHNCompatible (sql-text, rows underivable): expected Sequence :: assertEqualsH2Compatible (sql-text, rows underiva
- **testTwoMappingsOneRuntime** — `pure::executionPlan::tests` [T2 Plan-text goldens (executionPlan printed as a string) (13)]
    - run bucket: `platform-fail: expected: 'Relational\n(\n  type = TDS[(legalName, String, VARCHAR(N), ""), (legalNameSimple, String, VARCHAR(N), ""`
    - breakdown: | executionPlan::testTwoMappingsOneRuntime | Relational plan text |
    - assert #1 assertEquals → `divergence`: platform-fail: expected: 'Relational\n(\n type = TDS[(legalName, String, VARCHAR(N), ""), (legalNameSimple, String, VARCHAR(N), "" :: expect
- **testTwoMappingsOneRuntimeWithoutExternalMapping** — `pure::executionPlan::tests` [T2 Plan-text goldens (executionPlan printed as a string) (13)]
    - run bucket: `platform-fail: expected: 'Relational\n(\n  type = TDS[(legalName, String, VARCHAR(N), ""), (legalNameSimple, String, VARCHAR(N), ""`
    - breakdown: | executionPlan::testTwoMappingsOneRuntimeWithoutExternalMapping | same |
    - assert #1 assertEquals → `divergence`: platform-fail: expected: 'Relational\n(\n type = TDS[(legalName, String, VARCHAR(N), ""), (legalNameSimple, String, VARCHAR(N), "" :: expect
- **testViewToTDS** — `pure::executionPlan::tests` [T2 Plan-text goldens (executionPlan printed as a string) (13)]
    - run bucket: `wall-exec: TypeInferenceException: in function '_': unknown function 'meta::relational::metamodel::datatype::dataTypeToCompatibleP`
    - breakdown: | executionPlan::testViewToTDS | plan text (+ unknown dataTypeToCompatiblePureType) |
    - assert #1 assertEquals → `wall:typer`: wall-exec: TypeInferenceException: in function '_': unknown function 'meta::relational::metamodel::datatype::dataTypeToCompatibleP :: in fun
- **withPlatform** — `pure::executionPlan::tests` [T2 Plan-text goldens (executionPlan printed as a string) (13)]
    - run bucket: `wall-exec: DialectCapability: collection reduction '_' reached a dialect without a list encoding`
    - breakdown: | executionPlan::withPlatform | PureExp/makeString platform node (+ our DialectCapability wall first) |
    - assert #1 assertEquals → `wall:lowering`: wall-exec: DialectCapability: collection reduction '_' reached a dialect without a list encoding :: collection reduction 'STRING_AGG' reache
- **testMilestonedProperty** — `graphFetch::tests::milestoning` [T2 Plan-text goldens (executionPlan printed as a string) (13)]
    - run bucket: `platform-fail: expected: 'PureExp\n(\n  type = String\n  expression =  -> serialize(#{meta::relational::tests::milestoning::Order {id,`
    - breakdown: | graphFetch::milestoning::testMilestonedProperty | assert #2 = PureExp plan text (assert #1 rows passes) |
    - assert #2 assertEquals → `divergence`: platform-fail: expected: 'PureExp\n(\n type = String\n expression = -> serialize(#{meta::relational::tests::milestoning::Order {id, :: expec
    - asserts passing: #1
- **testFilterAfterJoinInRelationWithExtendedPrimitives** — `tests::projection::filter` [T2 Plan-text goldens (executionPlan printed as a string) (13)]
    - run bucket: `platform-fail: assertSameSQL (sql-text, oracle declined: golden execution: Syntax error in SQL statement "select""root"".LEGALNAMEas""n`
    - breakdown: | projection::filter::testFilterAfterJoinInRelationWithExtendedPrimitives | `planToStringWithoutFormatting` — the golden has no spaces (`select""root""`); unreplayable by
    - assert #1 assertSameSQL → `referee-cannot-replay`: platform-fail: assertSameSQL (sql-text, oracle declined: golden execution: Syntax error in SQL statement "select""root"".LEGALNAMEas""n :: a
- **testIsEmptyOnCollection** — `query::filter::isempty` [T2 Plan-text goldens (executionPlan printed as a string) (13)]
    - run bucket: `platform-fail: assertEquals (sql-text, oracle declined: plan-text unformatted (planToStringWithoutFormatting) — its SQL is not a statem`
    - breakdown: | query::filter::isempty::testIsEmptyOnCollection | plan-text unformatted — not a statement |
    - assert #1 assertEquals → `referee-cannot-replay`: platform-fail: assertEquals (sql-text, oracle declined: plan-text unformatted (planToStringWithoutFormatting) — its SQL is not a statem :: a
- **testExecutionPlanGeneration** — `tds::window::routing` [T2 Plan-text goldens (executionPlan printed as a string) (13)]
    - run bucket: `wall-type: no overload of '_' structurally matches the argument types (ExprType[type=GenericTyp`
    - breakdown: | tds::window::routing::testExecutionPlanGeneration | Sequence plan text (+ `over` overload wall) |
    - assert #0 - → `wall:typer`: wall-type: no overload of '_' structurally matches the argument types (ExprType[type=GenericTyp :: meta::relational::tests::tds::window::rou
- **testEqualityInFilterOnOptionalPropertiesLegacy** — `tests::functions::sqlstring` [T3 Foreign-dialect SQL text (no engine to execute it) (11)]
    - run bucket: `platform-fail: assertEquals (sql-text, DBN — text is the contract): expected select "root".FIRSTNAME as "name" from personTable as "roo`
    - breakdown: | sqlstring::testEqualityInFilterOnOptionalPropertiesLegacy | DBN legacy |
    - assert #1 assertEquals → `divergence`: platform-fail: assertEquals (sql-text, DBN — text is the contract): expected select "root".FIRSTNAME as "name" from personTable as "roo :: a
- **testIsDistinctSQLGeneration** — `tests::functions::sqlstring` [T3 Foreign-dialect SQL text (no engine to execute it) (11)]
    - run bucket: `platform-fail: assertSameSQL (sql-text, DBN — text is the contract): expected select "root".LEGALNAME as "LegalName", case when (count(`
    - breakdown: | sqlstring::testIsDistinctSQLGeneration | per-DB text (assertSameSQL, DBN) |
    - assert #2 assertSameSQL → `divergence`: platform-fail: assertSameSQL (sql-text, DBN — text is the contract): expected select "root".LEGALNAME as "LegalName", case when (count( :: a
    - asserts passing: #1
- **testNotEqualityInFilterOnOptionalPropertiesLegacy** — `tests::functions::sqlstring` [T3 Foreign-dialect SQL text (no engine to execute it) (11)]
    - run bucket: `platform-fail: assertEquals (sql-text, DBN — text is the contract): expected select "root".FIRSTNAME as "name" from personTable as "roo`
    - breakdown: | sqlstring::testNotEqualityInFilterOnOptionalPropertiesLegacy | DBN legacy |
    - assert #1 assertEquals → `divergence`: platform-fail: assertEquals (sql-text, DBN — text is the contract): expected select "root".FIRSTNAME as "name" from personTable as "roo :: a
- **testSqlGenerationDivide_AllDBs** — `tests::functions::sqlstring` [T3 Foreign-dialect SQL text (no engine to execute it) (11)]
    - run bucket: `platform-fail: assertSameSQL (sql-text, DBN — text is the contract): expected select ((N.N * "root".quantity) / N) from tradeTabl`
    - breakdown: | sqlstring::testSqlGenerationDivide_AllDBs | all DBs |
    - assert #1 assertSameSQL → `divergence`: platform-fail: assertSameSQL (sql-text, DBN — text is the contract): expected select ((N.N * "root".quantity) / N) from tradeTabl :: assertS
- **testToSQLStringWithAbs** — `tests::functions::sqlstring` [T3 Foreign-dialect SQL text (no engine to execute it) (11)]
    - run bucket: `wall-exec: class query under TypedNewInstance is not resolvable yet (HN vocabulary)`
    - breakdown: | sqlstring::testToSQLStringWithAbs | `runTestCaseById` — the engine's per-DB expected-SQL table (+ class query under TypedNewInstance wall) |
    - assert #2 assert → `wall:resolver`: wall-exec: class query under TypedNewInstance is not resolvable yet (HN vocabulary) :: class query under TypedNewInstance is not resolvable 
    - asserts passing: #1
- **testToSQLStringWithAggregation** — `tests::functions::sqlstring` [T3 Foreign-dialect SQL text (no engine to execute it) (11)]
    - run bucket: `wall-exec: class query under TypedNewInstance is not resolvable yet (HN vocabulary)`
    - breakdown: | sqlstring::testToSQLStringWithAggregation | same registry |
    - assert #1 assert → `wall:resolver`: wall-exec: class query under TypedNewInstance is not resolvable yet (HN vocabulary) :: class query under TypedNewInstance is not resolvable 
- **testGroupByWithJoinDB2** — `relational::tests::groupBy` [T3 Foreign-dialect SQL text (no engine to execute it) (11)]
    - run bucket: `platform-fail: assertEquals (sql-text, DBN — text is the contract): expected select "root".LEGALNAME as "legalName", "personTable_d#N_d`
    - breakdown: | groupBy::testGroupByWithJoinDB2 | DB2 |
    - assert #1 assertEquals → `divergence`: platform-fail: assertEquals (sql-text, DBN — text is the contract): expected select "root".LEGALNAME as "legalName", "personTable_d#N_d :: a
- **testDb2ColumnRename** — `relational::tests::postProcessor` [T3 Foreign-dialect SQL text (no engine to execute it) (11)]
    - run bucket: `wall-exec: TypeInferenceException: in function '_': class meta::pure::metamodel::type::Any h`
    - breakdown: | postProcessor::testDb2ColumnRename | DB2 128-char alias truncation text |
    - assert #1 assertEquals → `wall:typer`: wall-exec: TypeInferenceException: in function '_': class meta::pure::metamodel::type::Any h :: in function 'meta::relational::postProcessor
- **testFilterLimitInSequenceForTableAccessor** — `tests::query::take` [T3 Foreign-dialect SQL text (no engine to execute it) (11)]
    - run bucket: `platform-fail: assertEquals (sql-text, oracle declined: golden execution: Table "SELECTTOPN" not found; SQL statement:`
    - breakdown: | query::take::testFilterLimitInSequenceForTableAccessor | golden `select top N` (SQL Server) — H2 cannot replay |
    - assert #1 assertEquals → `referee-cannot-replay`: platform-fail: assertEquals (sql-text, oracle declined: golden execution: Table "SELECTTOPN" not found; SQL statement: :: assertEquals (sql-
- **testLimitFilterInSequenceForTableAccessor** — `tests::query::take` [T3 Foreign-dialect SQL text (no engine to execute it) (11)]
    - run bucket: `platform-fail: assertEquals (sql-text, oracle declined: golden execution: Table "SELECTTOPN" not found; SQL statement:`
    - breakdown: | query::take::testLimitFilterInSequenceForTableAccessor | same |
    - assert #1 assertEquals → `referee-cannot-replay`: platform-fail: assertEquals (sql-text, oracle declined: golden execution: Table "SELECTTOPN" not found; SQL statement: :: assertEquals (sql-
- **testSortQuotes** — `tests::tds::sort` [T3 Foreign-dialect SQL text (no engine to execute it) (11)]
    - run bucket: `wall-exec: IllegalStateException: no scalar lowering registered for resolved overload '_' with N parameter(s)`
    - breakdown: | tds::postgres::testSortQuotes | Postgres (+ no scalar lowering wall) |
    - assert #1 assertEquals → `wall:lowering`: wall-exec: IllegalStateException: no scalar lowering registered for resolved overload '_' with N parameter(s) :: no scalar lowering register
- **testTemporalDateVariableInFunctionExpressionWithPropagation** — `pure::executionPlan::tests` [T4 Plan text with parameters / temporal propagation (2)]
    - run bucket: `platform-fail: assertEqualsHNCompatible (sql-text, rows underivable): expected select "productexchangetable_N".name as "exchangeName" f`
    - breakdown: | executionPlan::testTemporalDateVariableInFunctionExpressionWithPropagation | rows underivable (parameterized) |
    - assert #1 assertEqualsH2Compatible → `referee-cannot-replay`: platform-fail: assertEqualsHNCompatible (sql-text, rows underivable): expected select "productexchangetable_N".name as "exchangeName" f :: a
- **testProp3** — `relational::tests::m2m2r` [T4 Plan text with parameters / temporal propagation (2)]
    - run bucket: `platform-fail: assertEqualsHNCompatible (sql-text, rows underivable): expected Relational`
    - breakdown: | m2m2r::testProp3 | referee-cannot-replay:no-fixture (receipt) — listed here as its assert is text |
    - assert #1 assertEqualsH2Compatible → `referee-cannot-replay`: platform-fail: assertEqualsHNCompatible (sql-text, rows underivable): expected Relational :: assertEqualsH2Compatible (sql-text, rows underi
- **testPureExecutionStrategyForCreateAndPopulateTempTableExecutionNode** — `executionPlan::tests::execution` [T5 Plan-as-data (7)]
    - run bucket: `wall-type: unknown function '_' — no function of this name in the native or user catalog (unported platform function, or a m`
    - breakdown: | executionPlan::execution::testPureExecutionStrategyForCreateAndPopulateTempTableExecutionNode | executes a plan NODE by hand (`evaluate`) |
    - assert #0 - → `wall:typer`: wall-type: unknown function '_' — no function of this name in the native or user catalog (unported platform function, or a m :: meta::pure::
- **testPureExecutionStrategyForRelationalInstantiationExecutionNode** — `executionPlan::tests::execution` [T5 Plan-as-data (7)]
    - run bucket: `wall-type: unknown function '_' — no function of this name in the native or user catalog (unported platform function, or a m`
    - breakdown: | executionPlan::execution::testPureExecutionStrategyForRelationalInstantiationExecutionNode | same |
    - assert #0 - → `wall:typer`: wall-type: unknown function '_' — no function of this name in the native or user catalog (unported platform function, or a m :: meta::pure::
- **testGraphFetchH2TempTableStrategy** — `pure::executionPlan::tests` [T5 Plan-as-data (7)]
    - run bucket: `wall-exec: instanceOf(meta::pure::graphFetch::executionPlan::StoreMappingGlobalGraphFetchExecutionNode) over a row of meta::pure::e`
    - breakdown: | executionPlan::testGraphFetchH2TempTableStrategy | `instanceOf(StoreMappingGlobalGraphFetchExecutionNode)` over the plan — the plan as a Pure value |
    - assert #1 assertEquals → `wall:exec`: wall-exec: instanceOf(meta::pure::graphFetch::executionPlan::StoreMappingGlobalGraphFetchExecutionNode) over a row of meta::pure::e :: insta
- **testGraphFetchH2TempTableStrategyWithQuoteIdentifiers** — `pure::executionPlan::tests` [T5 Plan-as-data (7)]
    - run bucket: `wall-exec: instanceOf(meta::pure::graphFetch::executionPlan::StoreMappingGlobalGraphFetchExecutionNode) over a row of meta::pure::e`
    - breakdown: | executionPlan::testGraphFetchH2TempTableStrategyWithQuoteIdentifiers | same |
    - assert #1 assertEquals → `wall:exec`: wall-exec: instanceOf(meta::pure::graphFetch::executionPlan::StoreMappingGlobalGraphFetchExecutionNode) over a row of meta::pure::e :: insta
- **testPlanForExecutionOption** — `pure::executionPlan::tests` [T5 Plan-as-data (7)]
    - run bucket: `wall-type: unknown function '_' — no function of this name in the native or user catalog (unported platform`
    - breakdown: | executionPlan::testPlanForExecutionOption | a dummy Extension with `extractVariablesFromExecutionOption` — the extension record again (parked family) |
    - assert #0 - → `wall:typer`: wall-type: unknown function '_' — no function of this name in the native or user catalog (unported platform :: meta::pure::executionPlan::te
- **testPreprocessFunctionOnRuntime** — `pure::executionPlan::tests` [T5 Plan-as-data (7)]
    - run bucket: `wall-type: unknown function '_' — no function of this name in the native or user catalog (unported platform functi`
    - breakdown: | executionPlan::testPreprocessFunctionOnRuntime | `functionReturnType` reflection over a runtime's preprocess function |
    - assert #0 - → `wall:typer`: wall-type: unknown function '_' — no function of this name in the native or user catalog (unported platform functi :: meta::pure::executionP
- **testSupportStreamFlagWithGraphFetchAndFrom** — `pure::executionPlan::tests` [T5 Plan-as-data (7)]
    - run bucket: `wall-type: deferred let binding '_' has no type outside a consuming call position (tree/colspec bindings resolve at thei`
    - breakdown: | executionPlan::testSupportStreamFlagWithGraphFetchAndFrom | reads plan nodes' flags (+ deferred let wall); M2M dest classes |
    - assert #0 - → `wall:typer`: wall-type: deferred let binding '_' has no type outside a consuming call position (tree/colspec bindings resolve at thei :: meta::pure::exec

## TEXT (reclassified) (10)

- **testAlloyTestDatGenWithQuotedColumnsForViews** — `testDataGeneration::tests::alloy` [L11 Views (2)]
    - run bucket: `wall-exec: testDataGen: view-backed relation '_' — view slice pending`
    - breakdown: | testDataGeneration::alloy::testAlloyTestDatGenWithQuotedColumnsForViews | RECLASSIFIED → TEXT (T2) 2026-09-06: the test says so itself — `// Purposefully asserting on p
    - assert #1 assertEquals → `wall:exec`: wall-exec: testDataGen: view-backed relation '_' — view slice pending :: testDataGen: view-backed relation 'AltID_View' — view slice pending
- **testRelationStoreAccessorOnView** — `tests::mapping::relation` [L11 Views (2)]
    - run bucket: `platform-fail: Catalog Error: Table with name personView does not exist!`
    - breakdown: | relation::testRelationStoreAccessorOnView | RECLASSIFIED → TEXT (T1) 2026-09-06: its first assert is `assert($result->contains('"sql":"select \\"personview_0\\".ID as \
    - assert #1 assert → `divergence`: platform-fail: Catalog Error: Table with name personView does not exist! :: Catalog Error: Table with name personView does not exist! Did yo
- **planGraphFetchWithDerivedProperty** — `executionPlan::m2m2r::tests` [L13 Model chain over relational (m2m2r) and derived properties (5)]
    - run bucket: `wall-exec: class query under TypedGraphFetch is not resolvable yet (HN vocabulary)`
    - breakdown: | m2m2r::planGraphFetchWithDerivedProperty | RECLASSIFIED batch 105 → TEXT/T2 — its only assert is `planToString` TEXT (the breakdown's own rule: a plan-text-only test ca
    - assert #1 assertEquals → `wall:resolver`: wall-exec: class query under TypedGraphFetch is not resolvable yet (HN vocabulary) :: class query under TypedGraphFetch is not resolvable ye
- **testModelConnectionDeepFunction** — `pure::executionPlan::tests` [L13 Model chain over relational (m2m2r) and derived properties (5)]
    - run bucket: `wall-exec: plan: no class mapping for '_' under 'meta::pure::mapping::m`
    - breakdown: | executionPlan::testModelConnectionDeepFunction | RECLASSIFIED → TEXT (T2) 2026-09-06: same assert form, deep chain |
    - assert #1 assertEquals → `wall:exec`: wall-exec: plan: no class mapping for '_' under 'meta::pure::mapping::m :: plan: no class mapping for 'meta::pure::mapping::modelToModel::te
- **testModelConnectionJoin** — `pure::executionPlan::tests` [L13 Model chain over relational (m2m2r) and derived properties (5)]
    - run bucket: `wall-exec: MappingResolutionException: class '_' is not mapped in mapping 'meta::pure::mapping::mod`
    - breakdown: | executionPlan::testModelConnectionJoin | RECLASSIFIED → TEXT (T2) 2026-09-06: `assertEquals($expected, $res->planToString(…))` over a ModelChainConnection plan (executi
    - assert #1 assertEquals → `wall:resolver`: wall-exec: MappingResolutionException: class '_' is not mapped in mapping 'meta::pure::mapping::mod :: class 'meta::pure::mapping::modelToMo
- **relationalResultSourcingOfListExecutionPlan** — `tests::advanced::resultSourcing` [L14 Raw SQL to TDS and CSV load (3)]
    - run bucket: `wall-exec: IllegalStateException: reading an executeInDb result binding ('_') is not supported`
    - breakdown: | advanced::resultSourcing::relationalResultSourcingOfListExecutionPlan | RECLASSIFIED → TEXT (T2) 2026-09-06: `assertEquals($expectedPlan, $result->planToStringWithoutFo
    - assert #1 assertEquals → `wall:exec`: wall-exec: IllegalStateException: reading an executeInDb result binding ('_') is not supported :: reading an executeInDb result binding ('re
- **testQuoteIdentifiersFlagWithGraphFetch** — `pure::executionPlan::tests` [L15 Referee legs (goldens a referee CAN bring to rows) (1)]
    - run bucket: `platform-fail: assertEquals (sql-text, oracle declined: golden execution: Schema "productSchema" not found; SQL statement:`
    - breakdown: | executionPlan::testQuoteIdentifiersFlagWithGraphFetch | RECLASSIFIED batch 96 → TEXT (T2) — the assert is `assertEquals('PureExp(type=String expression=->serialize(...)
    - assert #1 assertEquals → `referee-cannot-replay`: platform-fail: assertEquals (sql-text, oracle declined: golden execution: Schema "productSchema" not found; SQL statement: :: assertEquals (
- **testEnumFilterWithUnionMappingPlanGeneration** — `tests::mapping::union` [L4 Union / isolation / join fan-out (6)]
    - run bucket: `wall-exec: plan: alias '_' not resolvable to a table (Subselect)`
    - breakdown: | union::testEnumFilterWithUnionMappingPlanGeneration | RECLASSIFIED → TEXT (T2) 2026-09-06: its one assert is `assertEquals($expected, $plan->planToStringWithoutFormatti
    - assert #1 assertEquals → `wall:resolver`: wall-exec: plan: alias '_' not resolvable to a table (Subselect) :: plan: alias 't2' not resolvable to a table (Subselect)
- **testRelationalMapperTwoDBs** — `connections::tests::relationalMapper` [L7 Post-processors as compiler passes (4)]
    - run bucket: `platform-fail: expected: 'select "root".NAME as "name", "synonymtable_N".NAME as "cusip" from snDB.productSchemaNewDBINC.productTableNe`
    - breakdown: | alloy::connections::relationalMapper::testRelationalMapperTwoDBs | RECLASSIFIED → TEXT (T3) 2026-09-06: same helper, `snDB.productSchemaNewDBINC.productTableNewINC` (ca
    - assert #1 assertEquals → `divergence`: platform-fail: expected: 'select "root".NAME as "name", "synonymtable_N".NAME as "cusip" from snDB.productSchemaNewDBINC.productTableNe :: e
- **testRelationalMapperWithJoin** — `connections::tests::relationalMapper` [L7 Post-processors as compiler passes (4)]
    - run bucket: `platform-fail: expected: 'select "addresstable_N".NAME as "address" from snDBDefault.default.firmTableNew as "root" left outer join snD`
    - breakdown: | alloy::connections::relationalMapper::testRelationalMapperWithJoin | RECLASSIFIED → TEXT (T3) 2026-09-06: `assertEquals('select … from snDBDefault.default.firmTableNew 
    - assert #1 assertEquals → `divergence`: platform-fail: expected: 'select "addresstable_N".NAME as "address" from snDBDefault.default.firmTableNew as "root" left outer join snD :: e

## 3. ENGINE (31)

- **testPlanWithLocalH2ConnectionWithSQL** — `executionPlan::tests::datetime` [T5 Plan-as-data (7)]
    - run bucket: `wall-type: unknown function '_' — no funct`
    - breakdown: | protocol transforms (2) | transform::autogen::testClassesAssociationsAndMappingFromDatabase, executionPlan::datetime::testPlanWithLocalH2ConnectionWithSQL | `PureModelC
    - assert #0 - → `decision:protocol-transform`: wall-type: unknown function '_' — no funct :: meta::pure::executionPlan::tests::datetime::testPlanWithLocalH2ConnectionWithSQL :: unknown fu
- **testRoutingContextBuilderFunctions** — `pure::executionPlan::tests` [T5 Plan-as-data (7)]
    - run bucket: `wall-type: class meta::pure::metamodel::valuespecification::FunctionExpression has no property '_'`
    - breakdown: | router (6) | routing::testCompositionInMultiStatementPureExpressions, multipleexpressions::testPlatformExpressionDependencyOnAFromExpression, …2, testRoutingOfSimpleQua
    - assert #0 - → `wall:typer`: wall-type: class meta::pure::metamodel::valuespecification::FunctionExpression has no property '_' :: meta::pure::executionPlan::tests::test
- **testPrerouting42** — `router::preeval::tests` [T5 Plan-as-data (7)]
    - run bucket: `wall-type: '_' is not a known class, mapping, runtime, connection, or database`
    - breakdown: | router (6) | routing::testCompositionInMultiStatementPureExpressions, multipleexpressions::testPlatformExpressionDependencyOnAFromExpression, …2, testRoutingOfSimpleQua
    - assert #0 - → `wall:typer`: wall-type: '_' is not a known class, mapping, runtime, connection, or database :: meta::pure::router::preeval::tests::testPrerouting42 :: 'm
- **testJoinFunc** — `pure::tds::toRelation` [T5 Plan-as-data (7)]
    - run bucket: `wall-type: '_' is not a known class, mapping, runtime, connection, or database — user elements in a query need a fully qual`
    - breakdown: | tdsToRelation transform (2) | tds::toRelation::testJoinFunc, testJoinUsing | the engine's TDS→Relation protocol transform harness (`test(...)`); also its own `TestClass
    - assert #0 - → `wall:typer`: wall-type: '_' is not a known class, mapping, runtime, connection, or database — user elements in a query need a fully qual :: meta::pure::t
- **testJoinUsing** — `pure::tds::toRelation` [T5 Plan-as-data (7)]
    - run bucket: `wall-type: '_' is not a known class, mapping, runtime, connection, or database — user elements in a query need a fully qual`
    - breakdown: | tdsToRelation transform (2) | tds::toRelation::testJoinFunc, testJoinUsing | the engine's TDS→Relation protocol transform harness (`test(...)`); also its own `TestClass
    - assert #0 - → `wall:typer`: wall-type: '_' is not a known class, mapping, runtime, connection, or database — user elements in a query need a fully qual :: meta::pure::t
- **testProcessIdentifierWithQuoteChar** — `functions::sqlQueryToString::default` [T5 Plan-as-data (7)]
    - run bucket: `wall-exec: TypeInferenceException: in function '_': in call to 'meta::rel`
    - breakdown: | SQL renderer / DDL (5) | typeInference::testTranslateDbType, sqlQueryToString::testTempTableSqlStatementsForH2, sqlQueryToString::default::testProcessIdentifierWithQuot
    - assert #1 assertEquals → `wall:typer`: wall-exec: TypeInferenceException: in function '_': in call to 'meta::rel :: in function 'meta::relational::functions::sqlQueryToString::def
- **testTempTableSqlStatementsForH2** — `functions::sqlQueryToString::tests` [T5 Plan-as-data (7)]
    - run bucket: `wall-exec: TypeInferenceException: in function '_': in call to 'meta::relatio`
    - breakdown: | SQL renderer / DDL (5) | typeInference::testTranslateDbType, sqlQueryToString::testTempTableSqlStatementsForH2, sqlQueryToString::default::testProcessIdentifierWithQuot
    - assert #1 - → `wall:typer`: wall-exec: TypeInferenceException: in function '_': in call to 'meta::relatio :: in function 'meta::relational::functions::sqlQueryToString:
- **testConvertJoinTreeNode** — `functions::toPostgresModel::tests` [T5 Plan-as-data (7)]
    - run bucket: `wall-exec: TypeInferenceException: in function '_': expected meta::external::`
    - breakdown: | recursion over data (2) | toPostgresModel::testConvertJoinTreeNode, testConvertSelectSQLQuery | decision:recursion (task #4 if ever) |
    - assert #1 - → `decision:recursion`: wall-exec: TypeInferenceException: in function '_': expected meta::external:: :: in function 'meta::relational::functions::toPostgresModel::
- **testConvertSelectSQLQuery** — `functions::toPostgresModel::tests` [T5 Plan-as-data (7)]
    - run bucket: `wall-exec: TypeInferenceException: in function '_': expected meta::external::`
    - breakdown: | recursion over data (2) | toPostgresModel::testConvertJoinTreeNode, testConvertSelectSQLQuery | decision:recursion (task #4 if ever) |
    - assert #1 - → `decision:recursion`: wall-exec: TypeInferenceException: in function '_': expected meta::external:: :: in function 'meta::relational::functions::toPostgresModel::
- **testGraphFetch** — `graphFetch::domain::tests` [T5 Plan-as-data (7)]
    - run bucket: `wall-exec: TypeInferenceException: in function '_': class meta::pure::metamodel::valuespec`
    - breakdown: | graph-fetch domain extraction (1) | graphFetch::domain::testGraphFetch | `extractDomainTypeClassFromFunction` reads `FunctionExpression.func` (code-as-data) |
    - assert #1 assertEquals → `wall:typer`: wall-exec: TypeInferenceException: in function '_': class meta::pure::metamodel::valuespec :: in function 'meta::pure::graphFetch::domain::e
- **resolveSchemaTest** — `tds::schema::tests` [T5 Plan-as-data (7)]
    - run bucket: `wall-type: '_' is not a known class, mapping, runtime, connection, or database — user elements in a query need a fully qualif`
    - breakdown: | schema resolution program (1) | tds::schema::resolveSchemaTest | `resolveSchema` — a Pure program over the QUERY TREE (needs code-as-data) |
    - assert #0 - → `wall:typer`: wall-type: '_' is not a known class, mapping, runtime, connection, or database — user elements in a query need a fully qualif :: meta::relat
- **dropAndCreateTempTable** — `relational::tests::ddl` [T5 Plan-as-data (7)]
    - run bucket: `wall-type: unknown function '_' — no function of this name in the native or user catalog (unported platform function,`
    - breakdown: | SQL renderer / DDL (5) | typeInference::testTranslateDbType, sqlQueryToString::testTempTableSqlStatementsForH2, sqlQueryToString::default::testProcessIdentifierWithQuot
    - assert #0 - → `wall:typer`: wall-type: unknown function '_' — no function of this name in the native or user catalog (unported platform function, :: meta::relational::t
- **testCreateTempTableStatement** — `relational::tests::ddl` [T5 Plan-as-data (7)]
    - run bucket: `wall-exec: IllegalStateException: no SQL type for Pure class meta::relational::metamodel::TableAlias at the lowering boundary (class values do not reach S`
    - breakdown: | SQL renderer / DDL (5) | typeInference::testTranslateDbType, sqlQueryToString::testTempTableSqlStatementsForH2, sqlQueryToString::default::testProcessIdentifierWithQuot
    - assert #1 assertEquals → `wall:lowering`: wall-exec: IllegalStateException: no SQL type for Pure class meta::relational::metamodel::TableAlias at the lowering boundary (class values 
- **addDriverTablePkForProject** — `tests::functions::pureToSqlQuery` [T5 Plan-as-data (7)]
    - run bucket: `wall-type: unknown function '_' — no function of this name in the native or user catalog (unported platform function, o`
    - breakdown: | pureToSqlQuery internals (8) | testMergeOldAliasToNewAlias, testReAliasMergedJoinOperations, testFindAliasMappingBySchemaName, testFindFunctionSequenceMultiplicity, tes
    - assert #0 - → `decision`: decision:routeFunction: wall-type: unknown function '_' — no function of this name in the native or user catalog (unported platform function
- **simpleFunctionExpressionTranslationAdjust** — `tests::functions::pureToSqlQuery` [T5 Plan-as-data (7)]
    - run bucket: `wall-exec: TypeInferenceException: in function '_': unknown function 'meta::pure::executionPlan::fe`
    - breakdown: | pureToSqlQuery internals (8) | testMergeOldAliasToNewAlias, testReAliasMergedJoinOperations, testFindAliasMappingBySchemaName, testFindFunctionSequenceMultiplicity, tes
    - assert #1 assertEquals → `wall:typer`: wall-exec: TypeInferenceException: in function '_': unknown function 'meta::pure::executionPlan::fe :: in function 'meta::relational::functi
- **simpleFunctionExpressionTranslationNow** — `tests::functions::pureToSqlQuery` [T5 Plan-as-data (7)]
    - run bucket: `wall-exec: TypeInferenceException: in function '_': unknown function 'meta::pure::executionPlan::fe`
    - breakdown: | pureToSqlQuery internals (8) | testMergeOldAliasToNewAlias, testReAliasMergedJoinOperations, testFindAliasMappingBySchemaName, testFindFunctionSequenceMultiplicity, tes
    - assert #1 assertEquals → `wall:typer`: wall-exec: TypeInferenceException: in function '_': unknown function 'meta::pure::executionPlan::fe :: in function 'meta::relational::functi
- **testFindAliasMappingBySchemaName** — `tests::functions::pureToSqlQuery` [T5 Plan-as-data (7)]
    - run bucket: `wall-type: in call to '_', argument N: expected meta::relational::metamodel::TableAlias, got V`
    - breakdown: | pureToSqlQuery internals (8) | testMergeOldAliasToNewAlias, testReAliasMergedJoinOperations, testFindAliasMappingBySchemaName, testFindFunctionSequenceMultiplicity, tes
    - assert #0 - → `wall:typer`: wall-type: in call to '_', argument N: expected meta::relational::metamodel::TableAlias, got V :: meta::relational::tests::functions::pureTo
- **testFindFunctionSequenceMultiplicity** — `tests::functions::pureToSqlQuery` [T5 Plan-as-data (7)]
    - run bucket: `wall-type: class meta::pure::metamodel::valuespecification::FunctionExpression has no property '_'`
    - breakdown: | pureToSqlQuery internals (8) | testMergeOldAliasToNewAlias, testReAliasMergedJoinOperations, testFindAliasMappingBySchemaName, testFindFunctionSequenceMultiplicity, tes
    - assert #0 - → `wall:typer`: wall-type: class meta::pure::metamodel::valuespecification::FunctionExpression has no property '_' :: meta::relational::tests::functions::pu
- **testImportDataFlow** — `tests::functions::pureToSqlQuery` [T5 Plan-as-data (7)]
    - run bucket: `wall-type: unknown function '_' — no function of this name in the native or user catalog (unported platform function`
    - breakdown: | pureToSqlQuery internals (8) | testMergeOldAliasToNewAlias, testReAliasMergedJoinOperations, testFindAliasMappingBySchemaName, testFindFunctionSequenceMultiplicity, tes
    - assert #0 - → `wall:typer`: wall-type: unknown function '_' — no function of this name in the native or user catalog (unported platform function :: meta::relational::te
- **testMergeOldAliasToNewAlias** — `tests::functions::pureToSqlQuery` [T5 Plan-as-data (7)]
    - run bucket: `wall-exec: TypeInferenceException: in function '_': cannot access '_' on V [inlined v`
    - breakdown: | pureToSqlQuery internals (8) | testMergeOldAliasToNewAlias, testReAliasMergedJoinOperations, testFindAliasMappingBySchemaName, testFindFunctionSequenceMultiplicity, tes
    - assert #1 assertEquals → `wall:typer`: wall-exec: TypeInferenceException: in function '_': cannot access '_' on V [inlined v :: in function 'meta::relational::functions::pureToSql
- **testReAliasMergedJoinOperations** — `tests::functions::pureToSqlQuery` [T5 Plan-as-data (7)]
    - run bucket: `wall-exec: store resolution left user call '_' uninlined —`
    - breakdown: | pureToSqlQuery internals (8) | testMergeOldAliasToNewAlias, testReAliasMergedJoinOperations, testFindAliasMappingBySchemaName, testFindFunctionSequenceMultiplicity, tes
    - assert #1 assertEquals → `wall:resolver`: wall-exec: store resolution left user call '_' uninlined — :: store resolution left user call 'meta::relational::functions::pureToSqlQuery::
- **testResultToJsonStream** — `relational::tests::json` [T5 Plan-as-data (7)]
    - run bucket: `wall-type: '_' is not a known class, mapping, runtime, connection, or database — user elements in a query need a`
    - breakdown: | JSON result stream (1) | json::testResultToJsonStream | a hand-built `Result<TabularDataSet>` streamed to JSON — the engine's result serializer |
    - assert #0 - → `wall:typer`: wall-type: '_' is not a known class, mapping, runtime, connection, or database — user elements in a query need a :: meta::relational::tests:
- **testStoreSubstitution** — `tests::mapping::include` [T5 Plan-as-data (7)]
    - run bucket: `wall-type: unknown function '_' — no function of this name in the native or user catalog (unported platform function, or`
    - breakdown: | runtime helpers (2) | runtime::extractDBs::testExtractDBsWithSubstituition, include::testStoreSubstitution | `extractDBs`/`resolveStore` — the engine's mapping-include 
    - assert #0 - → `wall:typer`: wall-type: unknown function '_' — no function of this name in the native or user catalog (unported platform function, or :: meta::relational
- **testMilestoningFilterApplicationOnSemiStructuredRelationalOperationElements** — `tests::milestoning::applyMilestoningFilters` [T5 Plan-as-data (7)]
    - run bucket: `wall-exec: TypeInferenceException: in function '_': ambiguous overload of 'meta::relational::milestoni`
    - breakdown: | post-processors over the engine's SQL AST (2) | filterPushDown::testPushFiltersDownToJoinsPostProcessorToSQL, applyMilestoningFilters::testMilestoningFilterApplicationO
    - assert #1 - → `wall:typer`: wall-exec: TypeInferenceException: in function '_': ambiguous overload of 'meta::relational::milestoni :: in function 'meta::relational::mil
- **testPushFiltersDownToJoinsPostProcessorToSQL** — `tests::postProcessor::filterPushDown` [T5 Plan-as-data (7)]
    - run bucket: `wall-exec: TypeInferenceException: in function '_': unknown function '_' — n`
    - breakdown: | post-processors over the engine's SQL AST (2) | filterPushDown::testPushFiltersDownToJoinsPostProcessorToSQL, applyMilestoningFilters::testMilestoningFilterApplicationO
    - assert #1 assertEquals → `wall:lowering`: wall-exec: TypeInferenceException: in function '_': unknown function '_' — n :: in function 'meta::relational::functions::sqlDialectTranslat
- **testPlatformExpressionDependencyOnAFromExpression** — `query::routing::multipleexpressions` [T5 Plan-as-data (7)]
    - run bucket: `wall-type: unknown function '_' — no function of this name in the native or user catalog (unported platform function, o`
    - breakdown: | router (6) | routing::testCompositionInMultiStatementPureExpressions, multipleexpressions::testPlatformExpressionDependencyOnAFromExpression, …2, testRoutingOfSimpleQua
    - assert #0 - → `decision`: decision:routeFunction: wall-type: unknown function '_' — no function of this name in the native or user catalog (unported platform function
- **testCompositionInMultiStatementPureExpressions** — `tests::query::routing` [T5 Plan-as-data (7)]
    - run bucket: `wall-exec: TypeInferenceException: in function '_': unknown function '_' — no function of`
    - breakdown: | router (6) | routing::testCompositionInMultiStatementPureExpressions, multipleexpressions::testPlatformExpressionDependencyOnAFromExpression, …2, testRoutingOfSimpleQua
    - assert #1 assertEquals → `decision`: decision:routeFunction: wall-exec: TypeInferenceException: in function '_': unknown function '_' — no function of :: in function 'meta::rela
- **testRoutingOfSimpleQualifiedProperty** — `tests::query::routing` [T5 Plan-as-data (7)]
    - run bucket: `wall-type: unknown function '_' — no function of this name in the native or user catalog (unported platform function, o`
    - breakdown: | router (6) | routing::testCompositionInMultiStatementPureExpressions, multipleexpressions::testPlatformExpressionDependencyOnAFromExpression, …2, testRoutingOfSimpleQua
    - assert #0 - → `decision`: decision:routeFunction: wall-type: unknown function '_' — no function of this name in the native or user catalog (unported platform function
- **testExtractDBsWithSubstituition** — `tests::runtime::extractDBs` [T5 Plan-as-data (7)]
    - run bucket: `wall-exec: TypeInferenceException: in function '_': unknown function '_' — no function of this name in the n`
    - breakdown: | runtime helpers (2) | runtime::extractDBs::testExtractDBsWithSubstituition, include::testStoreSubstitution | `extractDBs`/`resolveStore` — the engine's mapping-include 
    - assert #1 assertSize → `wall:typer`: wall-exec: TypeInferenceException: in function '_': unknown function '_' — no function of this name in the n :: in function 'meta::relationa
- **testTranslateDbType** — `relational::tests::typeInference` [T5 Plan-as-data (7)]
    - run bucket: `wall-exec: TypeInferenceException: in function '_': class meta::pure::metamodel::type::Any h`
    - breakdown: | SQL renderer / DDL (5) | typeInference::testTranslateDbType, sqlQueryToString::testTempTableSqlStatementsForH2, sqlQueryToString::default::testProcessIdentifierWithQuot
    - assert #1 assertEquals → `wall:typer`: wall-exec: TypeInferenceException: in function '_': class meta::pure::metamodel::type::Any h :: in function 'meta::relational::translation::
- **testClassesAssociationsAndMappingFromDatabase** — `transform::autogen::tests` [T5 Plan-as-data (7)]
    - run bucket: `wall-type: unknown class '_' in ^meta::protocols::pure::vX_X_X::metamo`
    - breakdown: | protocol transforms (2) | transform::autogen::testClassesAssociationsAndMappingFromDatabase, executionPlan::datetime::testPlanWithLocalH2ConnectionWithSQL | `PureModelC
    - assert #0 - → `decision:protocol-transform`: wall-type: unknown class '_' in ^meta::protocols::pure::vX_X_X::metamo :: meta::relational::transform::autogen::tests::testClassesAssociatio

## 4. OTHER STORES (8)

- **testEnumPushDownWithExternalFormat** — `pure::executionPlan::tests` [T5 Plan-as-data (7)]
    - run bucket: `wall-type: unknown function '_' — no function o`
    - breakdown: | executionPlan::testEnumPushDownWithExternalFormat + testRelationalProjectionWithExternalFormat (2, counted as one row here: 2 tests) | external-format store (`externali
    - assert #0 - → `wall:typer`: wall-type: unknown function '_' — no function o :: meta::pure::executionPlan::tests::testEnumPushDownWithExternalFormat :: unknown function 
- **testRelationalProjectionWithExternalFormat** — `pure::executionPlan::tests` [T5 Plan-as-data (7)]
    - run bucket: `wall-type: unknown function '_' — no function o`
    - breakdown: | executionPlan::testEnumPushDownWithExternalFormat + testRelationalProjectionWithExternalFormat (2, counted as one row here: 2 tests) | external-format store (`externali
    - assert #0 - → `wall:typer`: wall-type: unknown function '_' — no function o :: meta::pure::executionPlan::tests::testRelationalProjectionWithExternalFormat :: unknown f
- **testCrossMappingJsonToDBWithExplosion** — `tests::XStore::inMemoryAndRelational` [T5 Plan-as-data (7)]
    - run bucket: `wall-exec: MappingResolutionException: class '_' is not mapped in mapping 'meta::pure::grap`
    - breakdown: | XStore::inMemoryAndRelational::testCrossMappingJsonToDBWithExplosion | in-memory JSON store crossed with relational (M2M explosion) |
    - assert #1 assertJsonStringsEqual → `wall:resolver`: wall-exec: MappingResolutionException: class '_' is not mapped in mapping 'meta::pure::grap :: class 'meta::pure::graphFetch::tests::XStore:
- **testCrossStoreWithCSVDataSource** — `tests::XStore::inMemoryAndRelational` [T5 Plan-as-data (7)]
    - run bucket: `wall-exec: class query under TypedMap is not resolvable yet (HN vocabulary)`
    - breakdown: | XStore::inMemoryAndRelational::testCrossStoreWithCSVDataSource | CSV data source store crossed with relational |
    - assert #1 assertEquals → `wall:resolver`: wall-exec: class query under TypedMap is not resolvable yet (HN vocabulary) :: class query under TypedMap is not resolvable yet (H2 vocabula
- **testCrossStoreGraphFetchWithRelationalDatePropagationForMilestonedPropertyConstraint** — `tests::XStore::milestoning` [T5 Plan-as-data (7)]
    - run bucket: `wall-type: unknown function '_' — no function of this name in the native or user catalog (unported`
    - breakdown: | XStore::milestoning::testCrossStoreGraphFetchWithRelationalDatePropagationForMilestonedPropertyConstraint | `compileLegendGrammar` at run time (decision:dynamic-compila
    - assert #0 - → `decision:dynamic-compilation`: wall-type: unknown function '_' — no function of this name in the native or user catalog (unported :: meta::pure::graphFetch::tests::XStore:
- **testCrossStoreGraphFetchWithRelationalDatePropagationForMilestonedPropertyZeroToOne** — `tests::XStore::milestoning` [T5 Plan-as-data (7)]
    - run bucket: `wall-type: unknown function '_' — no function of this name in the native or user catalog (unported`
    - breakdown: | XStore::milestoning::testCrossStoreGraphFetchWithRelationalDatePropagationForMilestonedPropertyZeroToOne | same |
    - assert #0 - → `decision:dynamic-compilation`: wall-type: unknown function '_' — no function of this name in the native or user catalog (unported :: meta::pure::graphFetch::tests::XStore:
- **testFlatten_ViaNoArgMapping** — `m2m2r::milestoning::milestonedSourceToNonMilestonedTargetProperty` [T5 Plan-as-data (7)]
    - run bucket: `wall-type: from() argument N must be a mapping or runtime reference, got TypedVariable`
    - breakdown: | m2m2r::milestoning::testFlatten_ViaNoArgMapping | `getNoArgFlattenMapping()` builds the mapping at run time (decision:dynamic-compilation) |
    - assert #0 - → `wall:typer`: wall-type: from() argument N must be a mapping or runtime reference, got TypedVariable :: meta::pure::graphFetch::tests::m2m2r::milestoning:
- **testFlatten_ViaNoArgMapping_ViaAssociation** — `m2m2r::milestoning::milestonedSourceToNonMilestonedTargetProperty` [T5 Plan-as-data (7)]
    - run bucket: `wall-type: from() argument N must be a mapping or runtime reference, got TypedVariable`
    - breakdown: | m2m2r::milestoning::testFlatten_ViaNoArgMapping_ViaAssociation | same |
    - assert #0 - → `wall:typer`: wall-type: from() argument N must be a mapping or runtime reference, got TypedVariable :: meta::pure::graphFetch::tests::m2m2r::milestoning:

## 5. NAMED (11)

- **columnValueDifferenceWithoutPrevalTest** — `tds::tests::extensions` [T5 Plan-as-data (7)]
    - run bucket: `platform-fail: assertEquals (rendered CSVJOIN:;): line N: expected <N-N-NTN:N:N.N+N|true|N.N|TDSNull|N.N|N|TDSNu`
    - breakdown: | columnValueDifferenceWithoutPrevalTest | engine-golden-defect:alloy-adjust-widening |
    - assert #4 assertEquals → `engine-golden-defect:alloy-adjust-widening`: platform-fail: assertEquals (rendered CSVJOIN:;): line N: expected <N-N-NTN:N:N.N+N|true|N.N|TDSNull|N.N|N|TDSNu :: assertEquals (rendered C
    - asserts passing: #1, #2, #3
- **testExtendDigest_Relational** — `tds::tests::extensions` [T5 Plan-as-data (7)]
    - run bucket: `platform-fail: expected: ['_', '_']`
    - breakdown: | testHashFunctions, testToSQLStringForTDSStringJoin, testExtendDigest_Relational, tdsJoin::alloy::testJoinWithExtendWithDigestOnColumnsOnBothQueries | engine-golden-defe
    - assert #1 assertEquals → `engine-golden-defect`: engine-golden-defect:joinStrings-rendering: platform-fail: expected: ['_', '_'] :: expected: ['9e103ea06a6999b4c5a86cf25d68b083', 'b7bbee4d9
- **testMilestonedRootAndMilestonedProperty** — `tests::embedded::otherwise` [T5 Plan-as-data (7)]
    - run bucket: `wall-exec: IllegalStateException: golden JSON does not parse: trailing JSON at N`
    - breakdown: | embedded::otherwise::testMilestonedRootAndMilestonedProperty, milestoning::testMilestonedRootAndMilestonedProperty | engine-golden-defect:malformed-json-golden |
    - assert #1 assertJsonStringsEqual → `engine-golden-defect:malformed-json-golden`: wall-exec: IllegalStateException: golden JSON does not parse: trailing JSON at N :: golden JSON does not parse: trailing JSON at 191
    - assert #1 assertJsonStringsEqual → `engine-golden-defect:malformed-json-golden`: wall-exec: IllegalStateException: golden JSON does not parse: trailing JSON at N :: golden JSON does not parse: trailing JSON at 191
- **testMilestonedRootAndMilestonedProperty** — `graphFetch::tests::milestoning` [T5 Plan-as-data (7)]
    - run bucket: `wall-exec: IllegalStateException: golden JSON does not parse: trailing JSON at N`
    - breakdown: | embedded::otherwise::testMilestonedRootAndMilestonedProperty, milestoning::testMilestonedRootAndMilestonedProperty | engine-golden-defect:malformed-json-golden |
    - assert #1 assertJsonStringsEqual → `engine-golden-defect:malformed-json-golden`: wall-exec: IllegalStateException: golden JSON does not parse: trailing JSON at N :: golden JSON does not parse: trailing JSON at 191
    - assert #1 assertJsonStringsEqual → `engine-golden-defect:malformed-json-golden`: wall-exec: IllegalStateException: golden JSON does not parse: trailing JSON at N :: golden JSON does not parse: trailing JSON at 191
- **testQualifierWithOperation** — `advanced::forced::structure` [T5 Plan-as-data (7)]
    - run bucket: `platform-fail: assertEquals (sql-text ROW verdict — golden rows vs ours diverged, whatever the text said): hN-advisory divergence: gold`
    - breakdown: | forced::structure::testQualifierWithOperation, testTwoQualifiersWithOperation | decision:empty-toOne-forced-isolation |
    - assert #3 assertEquals → `decision`: decision:empty-toOne-forced-isolation: platform-fail: assertEquals (sql-text ROW verdict — golden rows vs ours diverged, whatever the text s
    - asserts passing: #1, #2
- **testTwoQualifiersWithOperation** — `advanced::forced::structure` [T5 Plan-as-data (7)]
    - run bucket: `platform-fail: assertEquals (sql-text ROW verdict — golden rows vs ours diverged, whatever the text said): hN-advisory divergence: gold`
    - breakdown: | forced::structure::testQualifierWithOperation, testTwoQualifiersWithOperation | decision:empty-toOne-forced-isolation |
    - assert #3 assertEquals → `decision`: decision:empty-toOne-forced-isolation: platform-fail: assertEquals (sql-text ROW verdict — golden rows vs ours diverged, whatever the text s
    - asserts passing: #1, #2
- **testHashFunctions** — `tests::functions::sqlstring` [T5 Plan-as-data (7)]
    - run bucket: `platform-fail: assertEquals (sql-text ROW verdict — golden rows vs ours diverged, whatever the text said): hN-advisory divergence: gold`
    - breakdown: | testHashFunctions, testToSQLStringForTDSStringJoin, testExtendDigest_Relational, tdsJoin::alloy::testJoinWithExtendWithDigestOnColumnsOnBothQueries | engine-golden-defe
    - assert #1 assertEquals → `engine-golden-defect`: engine-golden-defect:joinStrings-rendering: platform-fail: assertEquals (sql-text ROW verdict — golden rows vs ours diverged, whatever the t
- **testToSQLStringForTDSStringJoin** — `tests::functions::sqlstring` [T5 Plan-as-data (7)]
    - run bucket: `platform-fail: assertEquals (sql-text ROW verdict — golden rows vs ours diverged, whatever the text said): hN-advisory divergence: gold`
    - breakdown: | testHashFunctions, testToSQLStringForTDSStringJoin, testExtendDigest_Relational, tdsJoin::alloy::testJoinWithExtendWithDigestOnColumnsOnBothQueries | engine-golden-defe
    - assert #1 assertEquals → `engine-golden-defect`: engine-golden-defect:joinStrings-rendering: platform-fail: assertEquals (sql-text ROW verdict — golden rows vs ours diverged, whatever the t
- **testToSqlGenerationFirstDayOfWeek** — `tests::functions::sqlstring` [T5 Plan-as-data (7)]
    - run bucket: `platform-fail: assertEquals (sql-text ROW verdict — golden rows vs ours diverged, whatever the text said): hN-advisory divergence: gold`
    - breakdown: | testToSqlGenerationFirstDayOfWeek | engine-golden-defect:h2-week-start |
    - assert #1 equal → `engine-golden-defect`: engine-golden-defect:h2-week-start: platform-fail: assertEquals (sql-text ROW verdict — golden rows vs ours diverged, whatever the text said
- **testDateTimeInclusiveRangeQuery** — `tests::mapping::relation` [T5 Plan-as-data (7)]
    - run bucket: `platform-fail: [settlementDateTime] (N rows)`
    - breakdown: | relation::testDateTimeInclusiveRangeQuery | RECEIPT: 9-digit sub-second literal vs `.123` fixture — golden-vs-H2 skew |
    - assert #1 assertTdsEquivalent → `divergence`: platform-fail: [settlementDateTime] (N rows) :: [settlementDateTime] (2 rows) is not equivalent to: [settlementDateTime] (1 rows)
- **testJoinWithExtendWithDigestOnColumnsOnBothQueries** — `tds::tdsJoin::alloy` [T5 Plan-as-data (7)]
    - run bucket: `platform-fail: expected: ['_', 'N,John,Johnson`
    - breakdown: | testHashFunctions, testToSQLStringForTDSStringJoin, testExtendDigest_Relational, tdsJoin::alloy::testJoinWithExtendWithDigestOnColumnsOnBothQueries | engine-golden-defe
    - assert #2 assertSameElements → `engine-golden-defect`: engine-golden-defect:joinStrings-rendering: platform-fail: expected: ['_', 'N,John,Johnson :: expected: ['1,Peter,Smith,1,ee0af362d8c1e4fa8c
    - asserts passing: #1

## (not in the breakdown) (6) — CLASSIFIED 2026-09-07 (unattended session)

The five `testConnectionEquality*` tests are the connection-equality family PARKED for the
code-as-data leg (docs/CODE_AS_DATA_HOMEWORK_2026_09_05.md; memory `code-as-data-leg-parked`): the
scalar `match` over extension-contributed arms is Pure evaluating over metamodel instances. Bucket:
NAMED / code-as-data. `testPlatformExpressionDependencyOnAFromExpression2` is the sibling of
`…OnAFromExpression` in the ENGINE bucket (decision:routeFunction — the engine's router under test).
Net: IMPL stays at 7 (5 real legs + the parked nested ModelJoin + the post-processor transform lambda,
which is code-as-data); everything else is TEXT / ENGINE / OTHER / NAMED / REVISIT.


- **testConnectionEqualityAllButOnePropertySame** — `metamodel::execute::tests` [?]
    - run bucket: `wall-exec: scalar match: the arm collection has a non-literal prefix (extension-contributed arms) that did not fold to [] — the low`
    - assert #1 assert → `wall:lowering`: wall-exec: scalar match: the arm collection has a non-literal prefix (extension-contributed arms) that did not fold to [] — the low :: scala
- **testConnectionEqualityAllSameStatic** — `metamodel::execute::tests` [?]
    - run bucket: `wall-exec: scalar match: the arm collection has a non-literal prefix (extension-contributed arms) that did not fold to [] — the low`
    - assert #1 assert → `wall:lowering`: wall-exec: scalar match: the arm collection has a non-literal prefix (extension-contributed arms) that did not fold to [] — the low :: scala
- **testConnectionEqualityTypeDiff** — `metamodel::execute::tests` [?]
    - run bucket: `wall-exec: scalar match: the arm collection has a non-literal prefix (extension-contributed arms) that did not fold to [] — the low`
    - assert #1 assert → `wall:lowering`: wall-exec: scalar match: the arm collection has a non-literal prefix (extension-contributed arms) that did not fold to [] — the low :: scala
- **testConnectionEqualityTypeSameSpecDiff** — `metamodel::execute::tests` [?]
    - run bucket: `wall-exec: scalar match: the arm collection has a non-literal prefix (extension-contributed arms) that did not fold to [] — the low`
    - assert #1 assert → `wall:lowering`: wall-exec: scalar match: the arm collection has a non-literal prefix (extension-contributed arms) that did not fold to [] — the low :: scala
- **testConnectionEqualityTypeSpecSameAuthDiff** — `metamodel::execute::tests` [?]
    - run bucket: `wall-exec: scalar match: the arm collection has a non-literal prefix (extension-contributed arms) that did not fold to [] — the low`
    - assert #1 assert → `wall:lowering`: wall-exec: scalar match: the arm collection has a non-literal prefix (extension-contributed arms) that did not fold to [] — the low :: scala
- **testPlatformExpressionDependencyOnAFromExpression2** — `query::routing::multipleexpressions` [?]
    - run bucket: `wall-type: unknown function '_' — no function of this name in the native or user catalog (unported platform function, o`
    - assert #0 - → `decision`: decision:routeFunction: wall-type: unknown function '_' — no function of this name in the native or user catalog (unported platform function

## 6. VERDICT-GAP (18) — batch 128 (Phase 0.3), 2026-09-08 — RESOLVED batch 141 (§13): the helper is a platform native; the shells are SKIPPED, no assertion reachable

Bucket `verdict-gap:guard-assert-in-expression-helper`: alloy test-data-generation shells (`mayExecuteAlloyTest(serverThunk, {| true})`) whose SETUP half calls `meta::relational::testDataGeneration::createTableRowIdentifiers($db, …)`; that helper's body carries a guard `assert($table.columns->cast(@Column).name->contains($cv.first), …)` inside a `map` (engine testDataGeneration.pure L81). The engine executes the guard; our platform types it (an expression statement of the helper's lambda) but never adjudicates it — the listener sees statement-root asserts only, and a value-position helper's inner statements are not judged. `ProgramFacts.verdicts` is TRUE (the descent reaches the assert), the run reports 0 verdicts → FAIL `no verdict: the body calls an assert the platform did not adjudicate`. Instrumented once on the descent: the reaching callee was this helper for all 18. Before batch 128 these were counted as passes ("ran, no asserts"). The fix is a platform leg (Phase 3): adjudicate asserts reached inside expression-position user bodies (or lower the guard as a boolean the statement channel judges); never a harness arm. Both lanes.

- meta::relational::testDataGeneration::tests::alloy::testAlloyTestDatGenForNestedViews
- meta::relational::testDataGeneration::tests::alloy::testInheritanceMultipleLevel_Alloy
- meta::relational::testDataGeneration::tests::alloy::testInheritanceMultipleTableJoin_Alloy
- meta::relational::testDataGeneration::tests::alloy::testQualifier_Alloy
- meta::relational::testDataGeneration::tests::alloy::testSelfJoin_Alloy
- meta::relational::testDataGeneration::tests::alloy::testSimpleSingleTableWithNoDataToInsert_Alloy
- meta::relational::testDataGeneration::tests::alloy::testSimpleSingleTable_Alloy
- meta::relational::testDataGeneration::tests::alloy::testSimpleTableToViewJoin_Alloy
- meta::relational::testDataGeneration::tests::alloy::testSimpleTwoTableMultipleStartRows_Alloy
- meta::relational::testDataGeneration::tests::alloy::testSimpleTwoTable_Alloy
- meta::relational::testDataGeneration::tests::alloy::testSimpleViewRootToJoin_Alloy
- meta::relational::testDataGeneration::tests::alloy::testTableToTDSMultipleJoins
- meta::relational::testDataGeneration::tests::alloy::testTableToTdsWithConcatenate
- meta::relational::testDataGeneration::tests::alloy::testTableToTdsWithJoinAndUnion
- meta::relational::testDataGeneration::tests::alloy::testUnionToUnionMultipleLevelsWithStringHashing_Alloy
- meta::relational::testDataGeneration::tests::alloy::testUnionToUnionMultipleLevels_Alloy
- meta::relational::testDataGeneration::tests::alloy::testViewChild_Alloy
- meta::relational::testDataGeneration::tests::alloy::testViewEmbeddedInChainedJoin_Alloy

## 7. PAGINATED-GOLDEN (9) — batch 130 (Phase 0.5), 2026-09-08 — FIXED in batch 131 (0.5b): the page-membership verdict; all 9 pass again on both lanes (45 / 44 page-membership verdicts). Kept as the record of the class.

Bucket `paginated-golden:text-differs`: the golden SQL pages (`offset/fetch/limit`) over an unsorted chain or a sort that need not be total; the referee now DECLINES a paginated golden before comparing (until batch 129 a page's row MATCH was two databases agreeing on arrival order, and a divergence was re-classified as a decline after the fact); the sql-text assert then falls back to text, which differs. Each test's own literal asserts on the page still pass. FIX (batch 0.5b, user ruling 2026-09-08 'land, then fix'): the page-membership verdict — our UNPAGED population from the typed chain minus its tail page node; golden page ⊆ population, row count equal, key sequence sorted when ordered. Both lanes.

- meta::relational::tests::projection::drop::testSimpleNestedDrop
- meta::relational::tests::projection::drop::testSimpleNestedDropAfterConcatenate
- meta::relational::tests::projection::drop::testSimpleNestedSlice
- meta::relational::tests::projection::drop::testSimpleNestedSliceAfterConcatenate
- meta::relational::tests::query::drop::testSimpleDrop
- meta::relational::tests::tds::tdsProject::testDropAfterLimit
- meta::relational::tests::tds::tdsProject::testLimitAfterDrop
- meta::relational::tests::tds::tdsProject::testLimitAfterSlice
- meta::relational::tests::tds::tdsProject::testSliceAfterLimit

## 8. REAL-DEFECT found by the referee — batch 132 (Phase 0.6), 2026-09-08

- **testToSQLStringSplitPart** — `relational::tests::functions::sqlstring` — bucket `real-defect:splitPart-missing-part`. Surfaced when the referee gained `legend_h2_extension_split_part` (until batch 132 the golden failed to execute and the test passed on byte-equal text): golden rows on H2 give NULL for a part past the end (the engine's extension: commons split — adjacent separators collapse — and `parts.length > part-1 ? … : null`; Pure's `splitPart(str, token, part):String[0..1]` returns nothing), our DuckDB `split_part` gives '' (5 of 7 rows, both columns). The lowering of `splitPart` must return NULL for a missing part and collapse adjacent separators like Pure's `split`. Phase 3. Both lanes fail it (H2 lane: same-session golden through our Java-in-H2 function).

## 9. GAPS named by batch 132 (declines the referee cannot judge; text stays the contract, counted)

- `quoted-identifier golden over an unquoted schema` (6): `executionPlan::tests::testQuoteIdentifiersFlag`, `…InGroupBy`, `…InOrderByClause`, `…WithGraphFetch`, `testTypedTDSWithEnum`, `testTypedTDSWithEnumFilter` — `quoteIdentifiers=true` plan texts spell `"productSchema"."productTable"`; the corpus creates the schema UNQUOTED (relationalSetUp.pure `createProductSchemaTablesAndFillDb`), H2 uppercases it; the engine never executes these SQLs.
- `unformatted golden` (4): `projection::filter::testFilterAfterJoinInRelation`, `…WithExtendedPrimitives`, `query::take::testFilterLimitInSequenceForTableAccessor`, `testLimitFilterInSequenceForTableAccessor` — whitespace-stripped SQL text nobody can execute.
- `plan binding spelling` (1): `executionPlan::tests::testFilterEqualsWithOptionalParameter_H2` — H2 rejects VARCHAR(5) vs BOOLEAN; the golden's `varPlaceHolderToString(optionalActive![] "'" "'" …)` quotes the Boolean hole. Phase 4 (referee bindings by declared type, or an engine-template decision).

## 10. H2-lane gaps named by batch 135 (Phase 1)

- **testGenerateNecessaryTableColumnsForSingleTable** (H2 lane) — bucket `h2-gap:lambda-param-without-carrier`: on DuckDB the value is `list_transform([struct…], t -> concat(t.schema, …))` over a struct literal; H2 has no list carriers, and the lambda parameter reaches the renderer as a bare `"t"."schema"` reference with no source (the declared list-carrier `DialectCapability` gap family, surfacing as `Column "t.schema" not found` instead of the named refusal). Closes with the list-carrier leg or stays a named H2 limit.


## 11. Phase 3 homework on the audit's "three small sharp bugs" (batch 139, 2026-09-08)

- **testToSQLStringSplitPart** (§8 row) — CONFIRMED OURS, the first Phase 3 leg. Spec = the engine's `core_functions_unclassified` `splitPart(str:String[0..1], token, part):String[0..1]` with its PCT tests (`'Hello World'->splitPart(' ', 0) == ['Hello']`, `[]->splitPart(…) == []`, empty token → the whole string) and Pure's `split` (drops empty tokens: `split` PCT). DuckDB's `split_part` keeps empty parts and returns '' past the end. Lowering: the semantic `SqlFn.PURE_SPLIT_PART(str, token, part + 1)`; DuckDb spells it over its list encoding `list_extract(list_filter(string_split(s, t), x -> x <> ''), p)` (a list index past the end is NULL); H2 and the engine-text renderer spell it `legend_h2_extension_split_part(s, t, p)` — the engine's own function, the golden's spelling. The first cut (list calls straight from the lowering) failed the engine-text render: the engine-style renderer has no list encoding — the tell that the meaning is a semantic node, not one dialect's idiom.
- **testExtendDigest_Relational**, **tdsJoin::alloy::testJoinWithExtendWithDigestOnColumnsOnBothQueries** — RECLASSIFIED REAL-DEFECT → `revisit:engine-golden-defect:digest-joinStrings`. TRACE: the engine's Pure spec for `extendWithDigestOnColumns` (relational/tds/tdsExtension.pure L214–225) is `columns->map(toStringForColAccessor)->joinStrings('|')->hash(MD5)`; the goldens decode as **md5 of the STRING columns only, concatenated with NO separator and a trailing `|`**: expected `9e103ea06a…` = md5('2320.0|'), `b7bbee4d9b…` = md5('125.0|') (rows 2,1 of Trade — also reversed), `ee0af362d8…` = md5('PeterSmith|'), `f8758ee5b7…` = md5('JohnJohnson|'), the firm side `d41d8cd98f…` = md5(''). Ours: md5('1|25.0') = `6923b8e81f…`, md5('2|320.0') = `5e922469e9…` — exactly the spec. The engine's relational lowering of joinStrings-over-column-strings disagrees with its own Pure spec. USER DECISION (REVISIT rule: traced golden disagreements are decided, never resolved by code): engine-defect (we keep the spec) / accepted divergence.
- **columnValueDifferenceWithoutPrevalTest** — RECLASSIFIED REAL-DEFECT → `revisit:adjust-strictdate-render`. TRACE: `Trade.date : StrictDate[1]`; the groupBy key `$x.date->adjust(0, DurationUnit.DAYS)`; ours renders `2014-12-01` (StrictDate stays StrictDate — Pure's `adjust` on a StrictDate by a day-or-coarser unit; `DateShifts` casts the widened TIMESTAMP back to DATE), the golden renders `2014-12-01T00:00:00.000000000+0000` (the relational lane's DATEADD TIMESTAMP printed as a DateTime). The engine's DuckDB and H2 relational PCT adapters list NO expected failure for `adjust`, so the lane-seam ruling (2026-09-04) gives no green light to follow the relational rendering. USER DECISION: interpreter semantics (ours) / relational rendering.

## 12. Phase 3 leg 2 — exists-with-subtype (batch 140, 2026-09-08)

- **testExistsAsNullWithSubType** (§1 row, "PROBED 2026-09-07") — LANDED batch 140, both lanes. The 09-07 probe's reading ("ClassSources synthesizes stc pseudo-bindings only for subclasses over the SAME root table") was one layer too deep: the head's TARGET was already wrong. Instrument (one): the nested wall message now prints the target class and its binding keys — `target=Private; bindings=[id]`. Cause: `fnScope[map2]` + `fnScope[map3]` route to Private / Public sets while the declared class FunctionScope has no set; without a union/inheritance op on FunctionScope, `classifyUnionRoutes` saw two root-or-sole sets → un-routed → `emitJoinChain` minted one navigate slot and the FIRST PM won. Fix: the implicit `Inheritance` op the normalizer appends for an unmapped association end (MilestonedInheritanceMapping) now also covers the declared class of a routed class-typed property (`ImplicitInheritance.implicitOpsForRoutedTargets`). Downstream unchanged: member ordinals, routed union navigation (`ON fnId_0 = id OR fnId_1 = id`), inheritance synthesis with `stc_<Sub>___id` threads and `$member` witnesses, the cast leaf as an ordinary subtype-dispatch read with the member restriction. Rows = the engine's single publicFn join (referee MATCH); SQL shape differs (union arm) — rows are the verdict. Not a design leg after all: a missing arm of an existing rule.
- **Leg-3 homework (read-only, batch 140):** (1) `testToSQLStringForTDSStringJoin` and `testHashFunctions` (`tds_joinstrings_md5` / `tds_digest` columns only) — the engine renders `joinStrings([a, ' ', b], '[', ',', ']')` as `concat(a, ' ', b, '[', ',', ']')` (golden rows `Anthony Allen[,]`); ours is Pure's `[Anthony, ,Allen]`. Same family as §11's digests → `revisit:engine-golden-defect:joinStrings-literal-collection` (4 tests, one USER decision). (2) `testToSqlGenerationFirstDayOfWeek` — Pure spec `firstDayOfWeek = mostRecentDayOfWeek(Monday)` (dateExtension.pure L202); ours 2014-12-01 (Monday); the golden's H2 `date_trunc('week')` executes to 2014-11-30 (Sunday) → `revisit:engine-golden-defect:firstDayOfWeek-h2-week-start` (USER decision). (3) The 5 "simple-name" failures are NOT one cause: `testJoinFunc` / `testJoinUsing` (TestClass), `resolveSchemaTest` (Address), `testPrerouting42` (preeval Person) reference classes declared in ENGINE-CORE test-model files (`core/pure/tds/relation/testTdsToRelation.pure`, `core/pure/corefunctions/tests/testModel.pure`, `core/pure/router/preeval/tests.pure`) that the corpus model does not load — a corpus-SCOPE decision (USER), not resolution; `testResultToJsonStream` (GeographicEntityType, declared in core_relational) is a bare ENUM reference in value position (`^TDSColumn(type=GeographicEntityType)`) the name resolver leaves unqualified → one real fix, one test. (4) The 18 guard-assert-gap shells (§6) remain the largest code family: a value-position helper's `map(… assert(…))` statements need hoisting into the verdict channel (a compiler pass) — sized as one design batch.

## 13. VERDICT-GAP (18) RESOLVED — batch 141 (Phase 3 leg 3), 2026-09-08 — SUPERSEDED by §14 (batch 142: the shells PASS)

§6's bucket `verdict-gap:guard-assert-in-expression-helper` was misnamed: the platform never executes the helper's Pure body. `createTableRowIdentifiers` (/4, /2) and `createRowIdentifier` are registered NATIVES (`Pure.CREATE_TABLE_ROW_IDENTIFIERS__4/__2`, `CREATE_ROW_IDENTIFIER`) because the TDG carrier `generateTestData` reads these calls as SYNTAX (`TestDataGenerationNatives.classifyArg` → `parseTableRowIds` / `collectRowIds`). Two passes had two owners: `StatementInline` refused to open the call (native registered → the platform's), `FunctionCompiler.functionsAt` merged the native with the corpus's Pure overloads (the FQN was not platform-owned) and the typer chose the user body, so `Compiler.callsVerdict` reached the guard `assert` and the runner failed the shell for an assert nobody ran. Instrument (one, temporary — removed before the chain; `noNewDebugEnvFlags` is shrink-only): a trace on `StatementInline.rewrite` printed the expanded statement list and every skipped program-candidate call with its definition/native/overload facts — `skip …createTableRowIdentifiers/4 candidates=[] def=none natives=2 defs=[4, 2]`.

Fix: `PlatformTypes.isPlatformOwnedFunction` names the three TDG argument spellings; the user definitions suppress loudly at load; the verdict scan sees a native call and does not descend. The 18 shells are SKIPPED `no assertion reachable (the program calls no verdict function)` on both lanes — truthful: the real verdict (`assertTestData` inside the alloy thunk) sits behind `mayExecuteAlloyTest`'s server gate, which never fires; the guard was a precondition on the test's own inputs. Both lanes moved exactly the 18 (fail → skipped), zero other movement; the 50 non-alloy TDG tests unchanged.

OWED LEG (plan Phase 5, value carriers): the TDG carrier consumes VALUES (constructed `TableRowIdentifiers` / `RowIdentifier` instances, let-bound or inline) instead of call syntax; the three helpers return to being PROGRAMS the statement inliner opens; `createRowIdentifier`'s size guard and the column guard become statement-root verdicts — the column guard's nested `identifiers->map(i | pairs->map(cv | assert(…)))` needs `AssertVerdicts.unrolled` to accept a quantified ROOT (recursive adjudication) and a computed message; the `"shape pending"` walls in `classifyArg` die with the syntax reading. Then these 18 can become passes only when the alloy gate runs — i.e. never in this harness; they stay SKIPPED by nature (the alloy lane's own verdict is the deferred plan-text arm).

**Correction to §12(3):** `testResultToJsonStream` sits in a `###Pure` section of relationalSetUp.pure that imports `meta::json::tests::*`; its `GeographicEntityType` is `meta::json::tests::GeographicEntityType`, declared in engine-core's json tests — not the relational model's enum and not a resolver gap. All five "simple-name" failures (`testJoinFunc`, `testJoinUsing`, `resolveSchemaTest`, `testPrerouting42`, `testResultToJsonStream`) are the same corpus-SCOPE decision: engine-core test-model files the corpus does not load.

## 14. VERDICT-GAP (18) FIXED — batch 142 (Phase 3 leg 3, the real fix), 2026-09-08

§13's classification is superseded: the 18 alloy shells PASS on both lanes with the guard as a real verdict. USER: "fix the tests! That is what this phase is about" (memory `phase3-fix-not-reclassify`).

- **Ownership:** the four TDG natives (`createTableRowIdentifiers` ×2, `createRowIdentifier`, `createTemporalMilestoningDates`) are deleted; the engine's Pure bodies are the definitions — programs `StatementInline` opens (hoisted `_sN_hoisted` lets, the guards at statement root). The TDG carrier (`generateTestData` / `planTestDataGeneration`) reads the constructed VALUES: `SourceSubst.instanceOf` unwraps the parser's `new(<class>, NewInstance)` once; let-bound collections of hoisted lets deep-adopt; `TestDataGenerationNatives.classifyArg` reads `^TableRowIdentifiers(table = getTable(db,'S','T'), rowIdentifiers = [^RowIdentifier(columnValuePairs = zip(cols, vals))])` and `^TemporalMilestoningDates(...)`; the call-shape arms and the `"shape pending"` walls are gone.
- **Verdict channel:** `AssertVerdicts.unrolled` accepts a NESTED quantified root (`ids->map(i | pairs->map(cv | assert(…)))`), routes a COMPUTED message through the per-element unroll (the vector arm needs a literal), and reads a property over a let-bound instance literal as its field; `VerdictQueries.unrollElements` chases let-bound collection elements and takes a [1] instance literal as one element.
- **The guard executes in the database:** `$table.columns->cast(@Column).name->contains($cv.first)` → `schema()` / `table()` were typing natives with NO lowering; they are now the system metamodel's Pure accessors over rows (spec platform_store_relational/functions.pure:227/249; the engine's includes-concatenation arm of `schema()` is NOT ported — the system store maps no `Database.includes`; the identity route below walks includes in Java for the Table key). `db->schema('S')->table('T')` over an element reference is a STORE-ELEMENT IDENTITY (D2), owned once in `compiler.spec.typed.StoreElementIdentity`: `UserCallInliner` keeps it closed; `ElementReferences.storeTableKey` roots the chain at the Table row `tbl:<declaring db>|schema|table`; `Anchors` anchors it only under a PROPERTY navigation (as a bare argument it is the value `loadCsvToDbTable`, `replaceTables`, the relational mappers and `StoreNav` read — six consumers rewired to the owner); `StoreEscapees` knows it; `StoreResolver.collectGetAllClasses` counts it as a Table fetch. SQL: `list_contains(list_filter(LIST(t3.name …) FROM relational_elements t1 LEFT JOIN … WHERE t1.kind='Table' AND t1.id='tbl:…'), x -> x IS NOT NULL), 'ID')` on DuckDB.
- **H2:** the portable membership rewrite (`CarrierStrategies.membershipRule`) sees through the many-read's `LIST_FILTER(…, x -> x IS NOT NULL)` and `CompactList` carriers to the collecting subselect → `EXISTS`; the guard runs on H2 and NINE `query::filter::exists` tests (testContains, testContainsNegated, testIn, testInNegated, testInExistsCombined, testNotExists, testNestedExistsOne, testNestedNotExists, testExistsToManyPropertyWithAndFilterAndLiteralConditionsDeep) pass on H2 as a side effect.
- **Measured:** DuckDB 120 fail / 14 SKIPPED / 2441 pass (LITERAL 838 → 856 — the 18 guards); H2 572 / 14 / 1989; zero LOST on either lane; the 50 non-alloy TDG tests unchanged. Pins: AssertVerdicts 1750 → 1765 (verdict orchestration, reason written), StoreNav 188 → 187, H2 cardinality ceiling 19 → 22 (assertSize tests), native-catalog −6 rows; `NameResolutionContractTest` re-exampled on `joinStrings` (its example native `schema` became a Pure accessor).
- **Fix cycles (11, each named by one probe):** native ownership → nested quantification → computed message → let-bound elements → instance fields → schema/table lowering → identity vs inlining → anchoring → the `new` wrapper → includes → H2 carriers. Guard trips along the way: `ObservabilityGuardrailTest` (no new getenv flags; string-dispatch count — `CoreFn.NEW` typed dispatch), `CodeShapeGuardrailTest` (dead `ContextReading.stringOf` deleted), `JavaEvalLedgerTest` (pins above).

## 15. Phase 3 close-out census — batch 143, 2026-09-08

The DuckDB fail roster's 120, each traced to its owner (messages from the lane run + engine sources). Phase 3 landed 21 passes (batch 139 splitPart, 140 exists-with-subtype, 142 the 18 alloy shells) + 9 H2 side-gains (the EXISTS membership rewrite).

**Code legs left (3, single tests):**
- `testPksWithImportDataFlow` — the engine's `RelationalExecutionContext(importDataFlow=true, importDataFlowAddFks=true)`: the union query gains per-member pk columns `<col>_<i>` (pureToSQLQuery_union.pure:144–150; a non-member thread carries the type's DEFAULT literal, 0 for Integer, when `importDataFlowImplementationCount` is empty). Ours: `type: relation has no column 'ID_0'` at typing. Size: one batch — `ContextReading` reads the two flags into `ExecutionContext`; the execute result TYPE gains the columns (the typer must know the union members' pk: mapping `~primaryKey` or table PK); a resolver append pass (DriverPkAppend's sibling) projects the union row's key threads with the default-literal coalesce; `UnionSynthesis` must DEMAND every member's pk thread (today `<col>_<ord>` threads exist only where a route demands them — L2887). Union text goldens are the regression risk.
- `testMixedMappingWithFilterInProject` — `a navigation join over this union demands key column 'firm_ID', which NO union member carries` (a relation-function set beside a relational set under one union; the to-many nav `firm.employees->filter(...)` inside project). Design leg.
- `testAlloyTestDatGenWithQuotedColumnsForViews` — `testDataGen: view-backed relation 'AltID_View' — view slice pending` (the TDG generator over a view-backed relation).

**Reclassified this batch (no code):**
- `testSortQuotes` — the forAll now unrolls (mechanism landed); its assertEquals is a POSTGRES SQL-text golden → `foreign-dialect` TEXT contract.
- `tds::window::routing::testExecutionPlanGeneration` — a plan-text golden (`Sequence(… FunctionParametersValidationNode …)`) → TEXT; the `over` typing gap behind it is real but the verdict is text.
- The 5 `oracle declined`: `testQuoteIdentifiersFlagWithGraphFetch`, `testFilterAfterJoinInRelationWithExtendedPrimitives`, `testIsEmptyOnCollection`, `testFilterLimitInSequenceForTableAccessor`, `testLimitFilterInSequenceForTableAccessor` — the TESTS' goldens are unformatted plan strings (`'Relational(type=TDS[…]resultColumns=[…]sql=select"root"…'`); the SQL inside has no spaces (`selecttop1"persontable_1".ID…`) and cannot execute; re-spacing it would be text normalization → TEXT contracts (`text-contract:unformatted-plan`).
- The 4 `rows underivable`: the decline now names its cause — `testGroupByWithOpenVariableInAgg` = `Table SALES_GCS does not exist` (a plan-generation-only test; no lane seeds its tables); the other three are the same class (plan goldens over unseeded stores / parameterised plans) → TEXT contracts.
- `testMilestonedRootAndMilestonedProperty` ×2 (graphFetch milestoning, embedded otherwise) — `golden JSON does not parse: trailing JSON at 191`: the engine's golden literally ends `…[]}]"` — a stray quote in the test source (the tests are AlloyOnly; the engine never parses them) → `revisit:engine-golden-defect:json-stray-quote`.
- `testUnionTwoRelationMappings_ManyColumnProject` ×2 — golden `Anand,null,…`, ours `Anand,,…`: the seeds insert `''` (testUnion.pure:426–433), our rows are `''` (data-faithful; the TDS compare render spells NULL as `null`, so these cells are truly empty strings); the engine's golden spells them `null` → `revisit:engine-golden-defect:empty-string-as-null`.
- `testSimpleMappingQueryWithFilterInProject` — the mapping's relation function is `personTable->filter(AGE > 25)->limit(5)` with NO sort (relationMappingSetup.pure:1007–1011), used twice in one query (persons and their firm's employees); the expected pairing (`Fabrice,null` / `Oliver,Fabrice`) encodes one engine's physical row order; our H2 lane fails identically → `revisit:data-nondeterminism:limit-without-order` (the audit's "DATA-NONDETERMINISM 0" was wrong by one). **CORRECTED in §16 (batch 144): the seed has four people over 25, so `limit(5)` is inert and the rows are deterministic; the golden is the engine binding the filter lambda to the outer row — `engine-golden-defect:filter-lambda-binds-outer-row`.**

**The 120 by owner:** REVISIT 10 + nondeterminism 1 (USER); SCOPE 5 (USER: load engine-core test-model files?); TEXT contracts ~27 (foreign dialects 6, unformatted plans 5, plan/TDS texts 6, substring asserts on engine-internal aliases 10); ENGINE-MACHINERY ~38 (Phase 5/6); CODE-AS-DATA / OTHER-STORE ~19 (Phase 5); code legs 3 (above).

## 16. REVISIT decided — the ACCEPTED register (batch 144, 2026-09-08)

USER reviewed the eleven traces one by one and agreed to all. Each is now a row of `rcorpus/<lane>-accepted-roster.txt` (`fqn ||| bucket ||| witness`) and runs as status ACCEPTED: the test executes, its failure must carry the witness (the golden's own wrong value), the census counts it by bucket; a changed divergence is an ordinary FAIL ("witness missing — re-decide"), a vanished one is GAINED.

| bucket | tests | the engine's golden | ours |
|---|---|---|---|
| `engine-golden-defect:digest-joinStrings` | testExtendDigest_Relational, tdsJoin::alloy::testJoinWithExtendWithDigestOnColumnsOnBothQueries | md5 of the STRING columns concatenated without separator + trailing `|` (md5('PeterSmith|'), md5('2320.0|'), md5('')) | `columns->map(toString)->joinStrings('|')->hash(MD5)` — the engine's own Pure spec (tdsExtension.pure L214–225) |
| `engine-golden-defect:joinStrings-literal-collection` | testToSQLStringForTDSStringJoin, testHashFunctions (tds_joinstrings_md5 / tds_digest columns) | `joinStrings([a,' ',b], '[', ',', ']')` rendered `concat(a,' ',b,'[',',',']')` → `Anthony Allen[,]` | Pure's `[Anthony, ,Allen]` |
| `engine-golden-defect:adjust-strictdate-render` | columnValueDifferenceWithoutPrevalTest | `adjust(0, DAYS)` on a StrictDate printed `2014-12-01T00:00:00.000000000+0000` (the relational DATEADD TIMESTAMP) | `2014-12-01` (StrictDate stays StrictDate) |
| `engine-golden-defect:firstDayOfWeek-h2-week-start` | testToSqlGenerationFirstDayOfWeek | H2 `date_trunc('week', d)` executes to 2014-11-30 (Sunday) | `mostRecentDayOfWeek(Monday)` = 2014-12-01 (dateExtension.pure L202) |
| `engine-golden-defect:json-stray-quote` | graphFetch::tests::embedded::otherwise::testMilestonedRootAndMilestonedProperty, graphFetch::tests::milestoning::testMilestonedRootAndMilestonedProperty | the golden string ends `…[]}]"` — a stray quote in the test source (AlloyOnly; the engine never parses it) | the JSON before the stray quote |
| `engine-golden-defect:empty-string-as-null` | testUnionTwoRelationMappings_ManyColumnProject, …GeneratesSingleUnion | `Anand,null,…` | the seeds insert `''` (testUnion.pure:426–433); our cells are `''` |
| `engine-golden-defect:filter-lambda-binds-outer-row` | testSimpleMappingQueryWithFilterInProject | `Oliver,Fabrice` + `Fabrice,null`: Fabrice is 45, Oliver 26 — the golden is what `$e.age < 35` bound to the OUTER person `$x` produces | `Fabrice,Oliver` / `Oliver,Oliver`: the filter over the employees. CORRECTION of §15: not nondeterminism — the seed has four people over 25, `limit(5)` is inert, the rows are deterministic on both engines. The mixed-mapping sibling's golden carries the same bug. |

H2: `testHashFunctions` and `testToSqlGenerationFirstDayOfWeek` PASS on H2 (not registered); the two digest tests and the adjust test fail on H2 for H2-lane reasons (`Function "MD5" not found`, `variant navigation` — Phase 7) and stay in the H2 fail roster. DuckDB 109 fail / 14 SKIPPED / 11 ACCEPTED / 2441 pass; H2 566 / 14 / 6 / 1989.

## 17. SCOPE decided — engine-core fixtures as named library sources (batch 145, 2026-09-08)

USER: load them. Four engine-core files are NAMED in `Corpus.LIBRARY_FILES` (declarations in, their functions library elements the discovery never counts; census unchanged). The stdlib-namespace guard now refuses FUNCTIONS under `meta::pure::functions::` only. Why not discover from imports: a Pure `import` is a name shorthand, not a dependency — nothing ties a package to a file or module; the engine's runner loads every module into one graph, ours loads the relational module by decision.

The five tests reach their true walls (all remain in the fail roster under their new owners):
- `meta::pure::tds::toRelation::testJoinFunc`, `testJoinUsing` → `Unknown type: 'meta::protocols::pure::vX_X_X::metamodel::m3::function::LambdaFunction'` — the toRelation `test(...)` helper reads protocol types as data → CODE-AS-DATA (Phase 5).
- `meta::relational::tds::schema::tests::resolveSchemaTest` → `no overload of 'meta::pure::tds::schema::tests::assertSchemaRoundTripEquality'` — a helper in another engine-core file whose body is the engine's schema resolver → ENGINE-MACHINERY.
- `meta::relational::tests::json::testResultToJsonStream` → `unknown function 'toJSONStringStream'` — an unported platform native → ENGINE-MACHINERY / natives.
- `meta::pure::router::preeval::tests::testPrerouting42` → `a name-less project column must be a property navigation` — the router preeval suite → ENGINE-MACHINERY (the typer rule it trips is real but the suite's verdicts are the engine's router).

## 18. importDataFlow — the attempt reverted, the design written (2026-09-08)

The first leg for `testPksWithImportDataFlow` ran SIX fix cycles (native ownership → typing → normalized-binding lookup → architecture cycle → file cap → thread kinds) — past the three-cycle rule — and the user called it: "revert and clean sheet the right design". The tree is back at 8ad0cd6fb. What the cycles established is the DESIGN, not the code: `docs/IMPORT_DATA_FLOW_DESIGN_2026_09_08.md` — the union row projects every member's key thread unconditionally (engine `pk_<p>_<i>`; both lanes showed zero movement from it), the key threads are ONE fact recorded where UnionSynthesis produces them (`(name, pureKind)` on the ModelBuilder, surfaced through ModelContext like the mixed-union members) and stamped as the union binding's `primaryKeyColumns`; the typer widens the execute result type from that fact (never from the legacy definition, never through the normalizer — the architecture guard forbids the cycle); a resolver append pass descends to the union-row projection and coalesces non-member threads to the engine's default literals. NEXT batch implements §3 of the design in order; witness flips on both lanes.

## 19. importDataFlow LANDED as designed (batch 146, 2026-09-08)

`testPksWithImportDataFlow` PASSES on both lanes, implemented from `docs/IMPORT_DATA_FLOW_DESIGN_2026_09_08.md` §3 in order, one fix cycle (the typer arm the design already named — the assert reads `$r.getInteger('ID_0')` THROUGH the execute call's `Result<TDS>` type, so the widened type is load-bearing, not cosmetic). Rows: `Anand, 2, 0 / Roberts, 3, 0 / Scott, 1, 0 / Taylor, 0, 1 / Wright, 0, 2`.

The facts and their carriers, as built:

| fact | producer | carrier |
|---|---|---|
| a union's key threads `(name, pureKind)` | `UnionSynthesis.recordKeyThreads` — every member's primary key (declared `~primaryKey` refs on the main table, else the table's PRIMARY KEY; engine `resolvePrimaryKey`) joins the per-ordinal key map the routed navigations already project from, as `<col>_<ordinal>`; a SHARED table key (`<col>__pk_<table>`) is not doubled | `com.legend.model.KeyThread`; `ModelBuilder.unionKeyThreads` → `NormalizedModel.unionKeyThreads` (7th component, the same route as `mixedUnions` through `ModelNormalizer` and the `Compiler` layer union) → `PureModelContext` → `ModelContext.unionKeyThreads(mapping, class)` |
| the option on the call | `ContextReading.contextFlag(option, …)` — the driver-PK reader generalised (literal flag on a RelationalExecutionContext instance, let-bound or literal; computed = loud) and `ImportDataFlow.requested` for the typer's raw view (`Env.resolveAlias` → `SourceSubst.instanceOf`) | `ExecutionContext.importDataFlowColumns` (typed `Type.Column` list, empty = off; `withImportDataFlowColumns`, `importDataFlowRequested`) |
| the result columns | `ImportDataFlow.columns(mapping, chain, ctx)` — the chain's class root (`getAll`) → the recorded threads → `Type.Column(name, kind, [1])`; loud when the class is not a union under the mapping or a kind is undeclared | derived at `ExecuteChainAssembly.chain` onto the bound context AND by `Typer.refineImportDataFlow` (the execute exeCtx overload's output: every relation type inside `Result<…>` widened — a refinement of the signature's output from the call's own facts, like the Decimal carrier) |
| the appended projection columns | `ImportDataFlowAppend` (resolver, `DriverPkAppend`'s sibling; hooked in `StatementExecutor` beside it) — descends to the projection whose source row carries ALL the threads (an assert side re-plans the chain under its own map), appends `coalesce(row.<thread>, <default>)` per thread; a row carrying SOME threads is loud | the resolved `TypedProject` (columns + info widened); defaults = engine `getDefaultLiteralValue`: Integer 0, Float 0.0, Decimal 0, String '', Boolean false, StrictDate %9999-01-01, Date/DateTime %9999-01-01T00:00:00 |

Deviation from the design, decided from a consumer census: the union binding's `ClassBinding.primaryKeyColumns` is NOT set to the thread names (§3 step 3). Eleven consumers read that field as PHYSICAL key columns of a relational set (ClassSources, ForeignKeyIdentity, ObjectReferenceDecode, RelationalRootForm, CastReRoot, ElementReferences, MetamodelSeeds, NameResolver) — changing the union's value would re-route them; the option needs only the recorded facts. If a later leg wants the union's key as a binding fact it is a NEW field, not a reuse.

Guard receipts: `MappingNormalizer` untouched (3510 cap); no compiler→normalizer edge (`ImportDataFlow` reads `ModelContext` only); the union projection is UNCONDITIONAL (engine parity) and both lanes moved by exactly the witness.

## 20. Phase 5 batch 147 — the STRICT RUN of the extension-registry chain: the program ledger (2026-09-08)

USER method (docs/PHASE5_SIZING_2026_09_08.md §4): strict first, every engine program the chain meets is a row; D5 open. Witness: the five `testConnectionEquality*` tests. Landed as MECHANISM + LEDGER with the hand-off SWITCHED OFF (`UserCallInliner.HAND_OFF_ON = false`): with it on, every test that passes a field of the extension record through platform Pure depends on the whole record compiling, and the chain stops at row 18. The five witnesses therefore still fail at their pre-batch lowering wall; both rosters byte-identical. Batch 148 = the row-18 design (function references as values), then the switch.


| # | program met | first wall | kind | action |
|---|---|---|---|---|
| 1 | `relationalExtension()` → field `executionPlan_execution_processNode` (lambda body typed) | `Type has no property 'classifierGenericType'` | B vocabulary (m3 `Any.classifierGenericType: GenericType[0..1]`) | declared on `Pure.ANY` |
| 2 | same lambda body | `unknown function 'buildVariableCollectionSizeString'` (core/pure/executionPlan/executionPlan_execution.pure) | B vocabulary (file not admitted) | `Corpus.LIBRARY_FILES` += executionPlan_execution.pure |
| 3 | same lambda body | `unknown function 'classMappings'` — `Mapping.classMappings()`, a QUALIFIED PROPERTY of the metamodel (core mapping.pure), not a function we hold | C metamodel-as-relations (D1: a qualified property over the mapping rows) | STOP — census instead of cycle 4 |

CENSUS (static, first ring only — the names the eight chain files' bodies call that neither the relational module, the admitted files, the native catalog nor the platform functions define): 131.
- 69 from `core_external_store_relational_sql_dialect_translation` — the engine's SQL RENDERER (node processors `*_default`, function processors, cast/identifier generators); referenced by `h2SqlDialect()` (67 functions, 1077 lines) as FUNCTION REFERENCES in the dialect record's maps. Kind A. The module is 3 files / 47 functions / 633 lines; we load only utils.pure.
- 38 from engine `core` — the M2M interpreter (`executeInMemory`, `executeChain`), the plan executor (`planExecutionPure`, `planInMemoryGraphFetchExecution`, …), printers (`printValueSpecification`, `printNew`, `nodesToString`), plus a few genuine platform natives we lack (`remove`, `equalIgnoreCase`, `isDigit`, `hierarchicalProperties`, `multiplicitySubsumes`, `propertyByName`, `isWithinPackage`). Kind A mostly; the natives kind B.
- 23 defined nowhere as functions — qualified properties / m3 (`classMappings`, `_subTypeOf`, `class`, `resolveStore`, `reactivate`, `formatValue`, `literalProcessor`, `createTempTable`, `separator`; `cview_*/gview_*/pview_*` are local names, false positives). Kind C (D1 material).
Transitive closure NOT counted: each of the 107 kind-A functions has a body.

UNCOMMITTED mechanism on the tree (all strict-correct, none test-shaped): Corpus.LIBRARY_FILES += functions.pure, executionPlan_execution.pure, m2m + aggregationAware storeContract.pure, h2SqlDialect.pure; PreludeGeneratorTest admits `meta::protocols::pure::vX_X_X::metamodel::m3::` (Prelude regenerated, +38 lines); UserCallInliner.spelledProgramOr (typing-surface native → the model's program, at property-access and auto-map sources) + lambda-valued record fields stand until applied; Pure.ROUTER_EXTENSIONS native deleted (catalog −1) → SystemMetamodel Pure function `routerExtensions(_this)` verbatim; `Pure.ANY` declares `classifierGenericType: GenericType[0..1]` (m3).

PATH A (USER): the first ring admitted as vocabulary — 18 engine-core files + 4 dialect-translation files (`Corpus.LIBRARY_FILES`); the five stdlib-namespace files (`corefunctions/*Extension.pure`, `testExtension.pure`) REFUSED by the 2026-08-28 ruling (their nine names — remove, equalIgnoreCase, isDigit, validateDateTimeFormat, hierarchicalProperties, multiplicitySubsumes, propertyByName, isWithinPackage, testedBy — are platform rows, owed). The ring parses clean (no new library skip). Then, one wall per run:

| # | program met | first wall | kind | action |
|---|---|---|---|---|
| 4 | `relationalExtension()` hook body | `EnumerationMapping declares 0 type parameter(s) but the receiver EnumerationMapping<Any> supplies 1` | B (spec: `EnumerationMapping<T>`, mapping.pure:40) | `Pure.java` declaration made generic |
| 5 | same | `unknown function meta::relational::metamodel::execute::createTempTable` (an engine Java native) | B | two signature-only natives, spec spellings |
| 6 | same | `no overload of createDbConfig accepts 3 argument(s)`, then `Any has no property 'dbExtension'` | B (our typing-only surfaces returned Any) | 3-arg overload registered; all three return the spec's `DbConfig` (prelude shape generated) |
| 7 | same | `'ZeroMany' is not a known class, mapping, …` — m3's PackageableMultiplicity instances (m3.pure:1411) | C: m3 instance constants have no element kind | `PlatformConstants`: PureOne/PureZero/ZeroOne/ZeroMany/OneMany as spelled `^Multiplicity(lowerBound=^MultiplicityValue(value=n), upperBound=…)` values, a Typer arm before the function-reference fallback |
| 8 | `tdsSchema_resolveSchemaImpl` hook body: `$tdsSchema1.join($tdsSchema2)` | `no overload of meta::pure::tds::join accepts 2 argument(s)` — the `join` special form claimed a call whose receiver's CLASS declares `join(otherSchema)` | C typer: real pure routes the receiver's qualified property first (FunctionExpressionProcessor ordering) | Typer: a bound-variable receiver whose class declares a same-name derived property of matching arity routes to it before the special form; the parameterized-qualified-property gate widened from "no function" to "no function of this arity" |
| 9 | same (the property was invisible) | `SchemaState` in the model had only `columns` — the GENERATED prelude shape (a copy made when the declaring file was not loaded) shadowed the admitted `tdsSchema.pure` class, dropping its ten qualified properties | C: `TypeClassifier.classDef` was native-first for classes (enums were already user-first) | the source declaration wins over its generated copy (`Prelude.classFqns()` marks the copies); hand-declared metaclasses unchanged |
| 10 | the chain's remaining names | `_propertyMappingsByPropertyName` (spec body, platform Pure over the property-mapping rows); `reactivate(vs, vars)`, `_subTypeOf(sub, super)` (PCT platformOnly natives); `resolveStore(_this, store)` (a spec Pure function over `MappingInclude.storeSubstitutions` — a fact the rows do not carry yet: signature-only, loud at evaluation) | B / C | registered; catalog golden regenerated (+6 rows, −1 routerExtensions) |
| 11 | same hook body: `$tdsSchema1.extend(...)` | `NormalizeRequired function 'SchemaState$prop$extend' has non-let intermediate statements — cannot inline` | **C, STANDING**: the engine's schema resolver computes over `TDSColumn` VALUES; our typer treats a `TDSColumn`-typed helper as NormalizeRequired (β-inlined AT TYPING) and its guard asserts (`assertEmpty`, `assertNotEmpty`) cannot inline. The engine type-checks the closure; it never inlines it. | decision owed (see report) |

MEASURED (first full DuckDB lane with rows 1–11): 2128 pass / 424 fail — LOST 316. Bisected to TWO causes, neither the class flip (row 9):
| 12 | row 1 as declared | 141 `Cannot deduce template type` + 32 `collection-shaped result … NULL cells dropped` — a PROPERTY on `Any` changes the struct/variant carrier for every Any-typed value | C: Any's property-free shape is load-bearing | REVERTED the declaration; `classifierGenericType` is SERVED by the Typer exactly like `elementOverride` (typed `GenericType[0..1]`, folded empty) — the existing precedent |
| 13 | row 6's DbConfig shape | 47 `unknown function parseCSV` in `setUpDataSQLsV2` — the engine's program (toDDL.pure:198, `dbConfig:DbConfig[1]`) became TYPEABLE once DbConfig existed and out-ranked the platform's same-FQN native by specificity, so its body (the engine's CSV seeder) was typed | C: overload choice lacked the native-ownership rule StatementInline has | `InferenceKernel.resolveOverload`: a structurally matching native under a FQN removes same-FQN module candidates before scoring |
| 14 | row 9's breadth | 68 classes are declared both as generated shapes and in loaded files | scoped: the source wins ONLY when it declares qualified properties (SchemaState, DbConfig, LiteralProcessor, Format, DynaFunctionToSql, SQLResult, RelationTree) | |
| 11 | STANDING | 7 plan tests (`testSupportStreamFlag*`, `testSQLCommentsInPlan`) are collateral: platform Pure reads a field of the extension argument, the hand-off compiles the record, the hook body cannot type | decision owed |

| 15 | `SchemaState.extend` in the schema-resolution hook | resolved: STORED LAMBDAS ARE TYPED BY SIGNATURE — monomorphization needs an application; an executed call has its arguments, a lambda literal in a record field has none (the engine types a closure body by signatures and pastes nothing). `Typer.storedLambdaDepth` gates the NormalizeRequired route; evaluation stays strict; a closure applied later is pasted by the inliner (a column-typed-TDS helper inside such a closure would need a typed→raw printer — no corpus witness; slice 2 builds that printer anyway) | C, DECIDED by USER | the seven collateral plan tests come back |
| 16 | the hook bodies | `reactivate/1` (spec Pure function over the 2-arg native), `testedBy` (the refused testExtension.pure), `FunctionExpression.func` (m3: `Function<Any>[1]` — slice 2a's row carrier is owed) | B | signatures registered; `func` declared on the m3 class |
| 17 | `relationalExtension()` fields | `ExecutionPlanFeatureFlagExtension()` — `pure/executionPlan/executionPlanFeature.pure` admitted; `concatenate` of `Pair<Function<Any>, {->SchemaState}>` with `Pair<{Table->TableTDS}, …>` — the kernel joins same-raw parameterized classes ARG-WISE and a lambda's structural type with the nominal Function carrier (real pure covariance); `Any` is the top of every join | B / C | kernel arms added |
| 18 | `relationalStoreContract()` → `let defaultState = defaultState(…)` → `getSupportedFunctions()` — the engine's Pure→SQL registry, 427 function REFERENCES by engine id | (a) `sortBy_T_m__Function_$0_1$__T_m_` did not resolve: the mangled-id DECODER (a regex grammar) never learned multiplicity PARAMETERS — USER: "why do we never fix our thing to support it?" → REPLACED by a GENERATOR: `SignatureMangle.mangle(def)` spells the engine id exactly as FunctionDescriptor.java:196–232 does, and a reference resolves by spelling each declaration under a prefix and keeping the exact match (four decoder sites → one call; both regexes deleted). (b) STANDING: ~10 references to checker-owned TDS special forms (no catalog row by design) and ~20 stdlib functions we lack (paginated, union, whenSubType ×3, olapGroupBy ×8, projectWithColumnSubset ×2, tdsRows, save, now, today, firstDayOfThis*, currentUserId, convertTimeZone, isAlphaNumeric, toJson) | B (a fixed) / B+C (b standing) | batch 148: special-form references → opaque function values keyed on the CoreFn registry; the stdlib signatures from the spec |
| — | REGEX census (USER question): no rule bans regex; 21 production files use it (SqlTextVerdicts, LegendHttpServer, Scalars, TdsCompare, MappingNormalizer, AnsiSqlRenderer, H2, RawSqlBoundary, JsonSourceFrame, Pipelines, …) — text processing and grammar-guessing mixed; a ban needs the census first, then a shrink-only pin | — | owed |
| 15b | row 15 as first written | 8 TDS-extension tests LOST: the rule caught LET-BOUND lambda literals the same body applies (`columnValueDifference`), which rely on the eager paste | measured | scoped to RECORD-FIELD lambdas (`Typer.synthRecordField`, called by `NewChecker`); the seven plan tests are row-18 collateral, restored by switching the hand-off off |
| 18b | the row-18 CHASE (reverted, USER: "are we starting to hack?") | opaque references for checker-owned special forms; α-freshening of a referenced generic function's type parameters (broke schema algebra next — a renaming walker was the next patch) — six cycles, no design | reverted | KEPT from the chase, catalog truth regardless: 17 registry functions registered from the spec (paginated, union, whenSubType ×3, convertTimeZone, isAlphaNumeric, string::plus, variant toJson, mutation save, core::runtime::currentUserId …); `groupByWithWindowSubset`'s native signature spelled bare `Any`/`String`/`TabularDataSet` (a latent break the resolver had never reached) — every native signature census-checked, none bare now |
| 18c | DESIGN OWED (batch 148) | a reference to a function is a VALUE node carrying the function, typed by its classifier, expanded only when applied — the same "expand at the application" rule as row 15; the eta-expansion (cluster 26) leaks a generic function's type variables into whatever holds the value (`pair<U,V>(groupByWithWindowSubset<K,V,U> ref, …)` → "unbound type variable U"). Cost: a new typed node kind — 70 kinds, 39 walkers (a leaf: most pass through; typer, inliner, eval, lowering need arms) | design | after it lands, `HAND_OFF_ON`; then rows 19–23 per CODE_AS_DATA_HOMEWORK §2 (buildClassMappingsById over `^Mapping()`, the H2 dialect record, the dot rule with defaults, spelled arms + static dispatch, the comparison tail over class_ancestry rows + the `remove` native) |
| 19 | the vocabulary ring vs the platform's own Pure functions | 7 plan tests LOST with the hand-off OFF: `executionPlan_execution.pure` (admitted for row 2) redefines `meta::pure::executionPlan::allNodes` — the engine's RECURSIVE walk — beside the platform's `allNodes` over the plan-node closure rows; `SystemMetamodel.withoutSystemShadows` drops a graph copy of a system function only by IDENTICAL signature, and ours spelled `extensions: Any[*]` where the spec says `Extension[*]`, so both survived and overload choice picked the engine's | C: a platform Pure function owns its name by its SPEC signature | the platform signature made spec-exact (executionPlan_execution.pure:67); the engine copy is a recognised shadow again; `allSuperSetImplementations` (mappingExtension.pure:163) already matched. RULE: a platform Pure function is spelled with the spec's exact signature — that is what makes the engine's own copy a shadow |
| 9b | row 9 REVERTED (chain gate 9) | `testPairCollectionToString` lost: legend-pure declares `Pair.toString()` as a QUALIFIED PROPERTY; with the source class winning over its generated copy, `pair->toString()` routed to the spec's Pure body instead of the platform's rendering (the second component, `Any`-typed, came back quoted). Ten generated shapes have spec qualified properties the platform implements natively (Pair/List/SQLNull `toString`, `Runtime.connectionByElement`, `Row.value`, `TableAlias.relation`, `PostProcessor*` ids, GraphFetchTree navigations) | C: a class-level flip is unsound; the rule is PER PROPERTY — a spec qualified property is admitted only where the platform holds no native of that name | reverted (its only witness, `SchemaState.join`, is behind the switched-off hand-off); batch 148 designs the per-property rule beside the function-reference leg |
| 20 | landing (USER: commit only with zero regressions) | with the "native ownership" rule gone (gate 9), the engine's `from` / `withMapping` twins in the admitted `mappingExtension.pure` out-ranked the platform natives by specificity and 12 corpus tests LOST (`fromMapping::*`, `testCrossDbPlanGeneration…`, the `*WithVariables` executeLegendQuery tests) | the vocabulary RING has no witness while the hand-off is off, and 12 of its files carry module twins of platform natives (`from` ×3, `withMapping`, `withChainedMappings`, `execute`, `withFeatureFlags`, `planToString` ×2, `graphFetch`/`serialize`/`alloyConfig`, `save`) | the ring is OUT of this batch (`Corpus.LIBRARY_FILES` back to main); it returns with the hand-off in 148, each twin handled by row 19's rule (spec-exact native signatures → same-shape tie-break). The prelude keeps the protocol template package and the shapes the new native signatures name (+63 lines) |

## 21. Batch 148 — the system-prelude census and the kernel rule it found first (2026-09-08)

Design: `docs/SYSTEM_PRELUDE_DESIGN_2026_09_08.md` (WORLD_MAP §8). Census: `SpecBodyCensusTest` over legend-pure's nine platform packages; report in `docs/SPEC_BODY_CENSUS_2026_09_08.md`.

| # | what the census met | first wall | kind | action |
|---|---|---|---|---|
| 1 | 605 of the first run's 643 failures | `test<Z\|y>(f:Function<{Function<{->Z[y]}>[1]->Z[y]}>)` bodies calling `$f->eval(\|1)` then `$f->eval(\|'a')` — the kernel held the enclosing function's type/multiplicity parameters RIGID inside its body | kernel | a variable bound to a FUNCTION TYPE meeting another function type unifies structurally (`InferenceKernel.bindOrCheckTypeVar`); a mismatched shape still fails inside `unify` |
| 2 | the rule above, first cut | `V := Z`, `Z := Integer` — resolution returned the first hop | kernel | `resolve`/`resolveMult` follow variable chains |
| 3 | rule 2, first cut | StackOverflow ×2 on `T := G<W>`, `W := G<T>` (the census, not channel B) | kernel | a cycle guard (the set of variables being resolved) spanning the whole resolution; a cyclic variable stays as-is |
| — | MEASURED | census 481/643 → 950/174; kernel-class 470 → 1; channel B unchanged (314/13, 355, 137, 95, 204); DuckDB 2442/108/14/11 EXACT; H2 1990/565/14/6 EXACT | | landed |
| 4 | gate 1 | `ParserBoundaryArchTest` (the census parses at LEGEND_PLATFORM) and `SkipCensusTest` (it skips without the pure checkout) | pins | both registered with their reasons |
| 5 | the remaining 174 | unknown-function 74, overload 35, unknown-property 30, unknown-type 6, other 30 | vocabulary + 2 typer bugs | the ordered work list, SPEC_BODY_CENSUS §6–7; next: the 17 missing metamodel properties, then the five overload spellings |

## 22. Batch 149 — the census work list, 174 → 36 (2026-09-08)

Method: SpecBodyCensusTest after every change; channel B + both lanes before landing; every shape/native from the spec line it cites (SPEC_BODY_CENSUS §8.1). Rows: §8.2 there is the remaining list by owner; §8.3 the two lane-caught regressions and the fix rule for each.

| # | bucket | before → after | rule that closed it |
|---|---|---|---|
| 1 | unknown-property | 30 → 5 | 15 hand shapes made spec-exact (ColSpec family, Mapping, PropertyMapping, Package, Function, Class, CFD, ModelElement→AnnotatedElement) |
| 2 | `@T` casts in generic bodies | 11 → 0 | the enclosing function's type parameters are a frame; rigid in the kernel |
| 3 | unknown-function | 74 → 6 | 22 spec natives registered spec-exact (reflection/effects: lowering walls); spec-exact `isEmpty`, collection arithmetic |
| 4 | overload | 35 → 2 | PCT suppression stays by NAME (its three dropped spellings are natives); parameterized actuals vs class formals; linearization tie-break; supertype instantiation for property values |
| 5 | other | 30 → 16 | Nil-bottom join; lambda classifier reads; unknown-schema row pick; extractEnumValue signature typing; relation-type argument literals; metaclass-instance properties; profiles as values |
| 6 | lanes | 2442 → 2431 → 2442 | function-carrier properties have no layout slot; SystemMetamodel return type reverted |
| 7 | lanes, second pass | DuckDB HUNG (liveArms exponential) / chB-std 204 → 187 | runtime-match arm withdrawn (7 rows stay, design row); PCT suppression back to by-name |
| 8 | the hang (USER: "wall the post-processors until a design session") | DuckDB 110 s → 60 s | `ENGINE_MACHINERY_WALLS` (5 exact FQNs) + `UNROLL_BUDGET` 20,000 + the declared-ancestor index |

## 23. Batch 150 — the census work list, 36 → 3; units and the PCT harness in (2026-09-08)

USER: burn to zero, nothing decided away. Method as §22. SPEC_BODY_CENSUS §9 has the rows and the rules; §9.3 the lane-caught hijack.

| # | bucket | before → after | rule |
|---|---|---|---|
| 1 | vocabulary | 9 → 0 | 7 spec natives + 3 harness shapes generated (the generator's `meta::pure::test::` admission); Database/SetImplementation spec-exact |
| 2 | units | 4 → 0 | measures in the model; Measure/Unit shapes; unit literal = `newUnit(M~u, n)`; `M~u` resolved through the measure |
| 3 | match no-branch | 7 → 0 | the raise typed at the LUB (real pure's Match failure) |
| 4 | one-offs | 6 → 0 | Nil wildcard argument; raw-vs-parameterized; eval run-time multiplicity; tie-break ranks; SetImplementation.id[1] |
| 5 | discarded statements / assert family / dotted copy keys / packages | 8 → 0 | see §9.1 |
| 6 | special-form collisions | 2 → 0 | the receiver's own `_this` function wins over the bare operator family (`ReceiverOwnedFunctions`) |
| 7 | lanes | 2442 → 2376 → 2442 | the routing hijacked tableToTDS and cast; narrowed to `_this` functions with natives that never take the class |
| 8 | guards | — | unify's generic arm split; `NumberLiterals` out of SpecParser; class count 84; Measure/Unit/Database surfaces; catalog +8 |
| — | REMAINING 3 | | derived-property bodies — the generator's printer leg (SYSTEM_PRELUDE_DESIGN §9.3) |

## 24. Batch 151 — the prelude is a MODULE, phase 1 (2026-09-08)

Homework first (`docs/PRELUDE_MODULE_HOMEWORK_2026_09_08.md`, every §9 check decided before code), then the mechanism with
today's demand: `prelude.pure` (verbatim, parser-delimited slices, per-file import scopes), `Prelude` reader, boot-layer
merge, `bootFqns`, `withoutPreludeShadows` (61 T4 receipts), resolver universe = catalog ∪ module. Chain green, pass counts
unchanged on every gate; census 1128/3 → 1226/22.

| # | what the lanes/gates named | decided | where |
|---|---|---|---|
| 1 | bare `Relation`/`JoinType` flipped to the sql-protocol copies (13 G1 failures) — the fallback's collision winner was HashMap luck over 48 colliding simple names | RULE: catalog (declaration order), then module order (legend-pure first), FIRST claimant wins | homework §9.12; `NameResolver.platformTypeFqns` |
| 2 | 8 DuckDB / 3 H2 tests: `TDSRow$prop$get` cannot inline, then `TDSRow$prop$getString` unknown — the spec's accessor bodies read the engine's row; ours are row natives | `PlatformTypes.isPlatformOwnedDerivedProperty`, one owner in `ClassCompiler` (three cycles: shadow route, ordinary route, class compile) | homework §9.14 |
| 3 | channel B essential 313 < 314: `testPairCollectionToString` `<a, "b">` — `Pair.toString` as a body hits the Any-to-text rendering gap | TRANSITIONAL on the same list; NEXT LEG fixes the rendering and deletes Scalars' two arms | homework §9.15 |
| 4 | `CompilerModuleTest.eagerCompileAllBodies`: the boot layer's 152 bodies in a user module's walls | `compileAllBodies` = the module's own pass; boot bodies are the census's | homework §9.13 |
| 5 | G7 222 errors: `ModelPacker` read generic prelude classes as user classes | platform filter = catalog ∪ module | `pct/.../ModelPacker` |
| 6 | `PureModelContextTest` ×2: a fixture bypassing the boot layer asked for `Month` | catalog enum as the witness (`DateTimeFormat`) | test |
| 7 | `JavaEvalLedgerTest`: ModelPacker 267 > 266 code lines | one line | pin unchanged |

## 25. Batch 152 — Pair/List toString as bodies: monomorphization completed at the inlining seam (2026-09-08)

The §24 row-3 leg. The `<a, "b">` was not the Any arm (it strips JSON quoting) but a type-variable stamp reaching the
lowering: `UserCallInliner` β-reduced the module's generic `Pair<U,V>.toString()` keeping every node's generic stamp
(the old rule re-stamped the root only). Chain green, pass counts unchanged; census pinned.

| # | what the gates named | decided | where |
|---|---|---|---|
| 1 | `<a, "b">`: `$this.second : V` reached `toString`'s fall-through cast | the application binds the callee's type parameters (unify formals against argument types) and every stamp resolves (`TypedSpec.withInfo` on all 75 node records; `UserCallInliner.instantiate`) | homework §9.15 |
| 2 | `testPairToString` under the PCT harness printed the raw struct — the harness spells `->meta::pure::functions::string::toString()` | the derived shadow keys on the SIMPLE name | `Typer.derivedShadow` |
| 3 | nested `<dog, {'first': cat…}>`: the inner toString was bound to the native while `V` was a variable | RE-DISPATCH after instantiation: an Any-first native whose receiver is now a class with its own same-named derived property becomes that body, inlined | `UserCallInliner.redispatch` |
| 4 | `testFormatPair`/`testFormatList` once the arms went: printf showed the struct | `format`'s class-typed slots type as `$arg->toString()` (`CallShapes.formatSlotsByToString`, `PlatformTypes.printsByOwnToString`) | typer |
| 5 | `ErrorShapeGuardrailTest`: a catch returning a value | pre-check `hasFreeTypeVars(t, bindings)` instead of catching the kernel's unbound-variable exception | inliner |
| 6 | `CodeShapeGuardrailTest`: Typer 3538 > 3500 lines | the format rewrite lives in `CallShapes` | — |

## 26. Batch 153 — bare names fail like pure and the engine (2026-09-08)

The ratified §6a item 2, its own batch so phase 3's lane movement is demand alone. The resolver's prelude fallback
tier is gone; the core import group gained the engine's three additions. One lite test moved; every gate unchanged.

| # | what the gates named | decided | where |
|---|---|---|---|
| 1 | `testLegacyTdsJoinWithLetBoundJoinType`: `unknown enumeration 'JoinType'` — a sectionless query, bare enum | qualified in the test (the engine would refuse it too) | test |
| 2 | our core group was m3.pure's exactly; the engine's has `metamodel::relation`, `metamodel::variant`, `precisePrimitives` too | added with the receipt (`CompileContext.META_IMPORTS`) — the corpus spells `Relation<(…)>` bare on their strength | `NameResolver.CORE_IMPORTS` |
