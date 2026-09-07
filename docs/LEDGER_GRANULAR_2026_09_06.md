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
