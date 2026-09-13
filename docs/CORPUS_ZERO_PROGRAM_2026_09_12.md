# CORPUS TO ZERO — the program (2026-09-12)

USER: "setup a program that does homework/research one by one for every failure and burns down to zero — at each one we should really be expert, creative, thoughtful whether it makes sense for our product/platform to fix or hijack; skip/wall+explain should only be the last resort when it does not make sense."

## The ladder (every row climbs it, in this order)

1. **FIX** — a semantic gap a user could hit: implement it the engine's way, rows as the verdict.
2. **HIJACK** — the engine's function or vocabulary with OUR result inside: the plan-text channel, metamodel-API natives over facts we already hold, our own parser/compiler/emitters under the engine's names, the engine's text spellings where rows cannot judge.
3. **ACCEPT** — the engine's answer contradicts Pure (measured in the real interpreter): an accepted-divergence row with the witness, decided by the user.
4. **WALL** — last resort, with the explanation of WHY neither fix nor hijack makes sense for the product (a stub that types and is never consumed is not an implementation).

## The homework per row (never sampled, never guessed)

- the test body at the pinned checkout: what it ASSERTS (rows / SQL text / plan text / a structure);
- what the engine does: the lowering registration, the flag, the plan node, the printer path — read, not inferred;
- what we do: the SQL dump or the refusal, and WHY;
- when semantics are in doubt: the real Pure interpreter (recipe in memory), the H2 referee on the golden;
- the decision on the ladder, written in the ledger below and in GATES.md when it lands.

## Discipline

- one gated batch per row or per cluster: `GATES_PARALLEL=1 tools/allgates.sh` once, in the background; pins move only with measured values and written reasons; named files staged; push; CI checked.
- the ledger is the state; the review (docs/CORPUS_FAIL_REVIEW_2026_09_12.md) is the evidence; GATES.md is the record.
- H2 lane rows follow the DuckDB lane: a DuckDB burn is checked on H2 in the same batch.

## The ledger — 118 DuckDB fail rows, by cluster (order = the burn order)

| # | cluster | rows |
|---|---|---|
| 1 | A. checked-fetch constraint scope | 1 |
| 2 | B. plan text — near misses | 6 |
| 3 | C. plan text — node kinds | 12 |
| 4 | D. metamodel-API natives | 15 |
| 5 | E. our own compiler as the engine's | 10 |
| 6 | F. typer gaps | 3 |
| 7 | G. text idioms | 12 |
| 8 | H. other-dialect text goldens | 7 |
| 9 | I. lanes (design first) | 21 |
| 10 | J. router and printer internals | 31 |

### A. checked-fetch constraint scope — 1 rows — ladder: FIX

constraints see the fetched tree; unfetched reference → 'data not available' marker (NULL) → CheckedEnvelope's catch-branch defect

| test | state |
|---|---|
| `meta::relational::graphFetch::tests::simple::testCheckedWithCircularConstraints` | ACCEPTED (DuckDB) — engine-golden defect (upstream toFix); the by-tree `isDistinct` its nested Firm constraint reaches LANDED 2026-09-12 (GATES: isDistinct, three things under one name), so nested constraints can re-land (branch nested-constraints-wip) without this row regressing |
| (own witness) NESTED-OBJECT CONSTRAINTS | BURNED (FIXED) 2026-09-12 — re-landed over its two prerequisites (by-tree isDistinct 9d5d3ad33, reducers over navigations f69fca42e); GATES: "Nested-object constraints, re-landed". DuckDB 0 LOST / 0 GAINED (the circular row keeps its accepted witness and now evaluates its nested Firm constraint); H2 444→447 by USER ruling (skip H2: checked trees with class-typed children wall on the list-lambda capability — a lane gap, named). OWED (audit): hoist order across several checked children (engine sorts subtrees — needs an engine golden); embedded children's own constraints not evaluated; broken to-one mapping duplicates the parent via LEFT LATERAL instead of raising; store-vs-fetched-tree evaluation (engine canEvaluateForTree) is a product decision carried by default; STRUCTURAL: the derived-leaf inliner (GraphEmission.inlineThis) is a second expression compiler beside the projection path's demand machinery — unification is a design leg, not a batch. Homework kept: the engine's constraint property tree is a scanProperties lineage (typeInfo.pure:379-390) whose result on the one golden contradicts a naive reading, so NO evaluability filter. CLUSTER A CLOSED. |

### B. plan text — near misses — 6 rows — ladder: HIJACK

one or two spellings in the plan-text channel

| test | state |
|---|---|
| `executionPlan::tests::testGroupByWithOpenVariableInAgg` | BURNED 2026-09-12 (FIX) — fixture on demand seeds its store, but the fixture has no calendar row for 2005-10-10 (empty Allocation: rows cannot judge; text is the verdict). The join order was PHASE order (a nav-date chain registers first and sinks deepest), found by a stack probe in the TypedJoin constructor; `resolver/SlotOrder` now re-sequences the root's step joins into first-read order at the root materialization (GATES: Join order by first read); passes by text, both lanes |
| `executionPlan::tests::testGroupByWithTwoOpenVariablesInAggAndFilter` | BURNED 2026-09-12 (FIX) — the same reorder, plus `PlanParam.Kind.STRICT_DATE`: a StrictDate let spells `DATE'${startDate}'` where pure Date spells TIMESTAMP; passes by text, both lanes |
| `executionPlan::tests::testTemporalDateVariableInFunctionExpressionWithPropagation` | BURNED 2026-09-12 (FIX, referee) — fixture on demand seeds the store its mapping reads; judged by ROWS, both lanes (GATES: Fixture on demand) |
| `executionPlan::tests::testTwoMappingsOneRuntime` | BURNED 2026-09-12 (FIX + referee) — the legacy shared-key TDS join types as its own thing (merged schema by the engine's tds.pure rule, no synthetic `__jk_` rename) and lowers to ONE select over the two sides (lean product SQL, USER ruling); the plan arm accepts the mapping-less `executionPlan(lambda, extensions)`; passes by ROWS, both lanes (GATES: Lean shared-key join) |
| `executionPlan::tests::testTwoMappingsOneRuntimeWithoutExternalMapping` | BURNED 2026-09-12 — same as the row above; passes by ROWS, both lanes |
| `query::filter::isempty::testIsEmptyOnCollection` | HIJACKED 2026-09-12 — the text channel spells an optional collection parameter's emptiness with the engine's `(${collectionSize(name![])})` template (both lanes) |

### C. plan text — node kinds — 12 rows — ladder: HIJACK

a plan node kind the channel does not print yet (PureExp, StoreMappingGlobalGraphFetch, union root, scalar projection, view root, cross-db argument shape)

| test | state |
|---|---|
| `executionPlan::m2m2r::tests::planGraphFetchWithDerivedProperty` | TODO |
| `executionPlan::m2m2r::tests::planGraphFetchWithNestedDerivedProperty` | TODO |
| `executionPlan::tests::testCrossDbPlanGenerationWithRelationFromWithOnlyRuntimes` | TODO |
| `executionPlan::tests::testGraphFetchH2TempTableStrategy` | TODO |
| `executionPlan::tests::testGraphFetchH2TempTableStrategyWithQuoteIdentifiers` | TODO |
| `executionPlan::tests::testQuoteIdentifiersFlagWithGraphFetch` | HOMEWORK 2026-09-12 — rows leg derivable now; the golden fails on the REFEREE (`Schema "productSchema" not found`): its seed replay never received the schema DDL → NOT a seed gap (homework 2026-09-12): our session ran `Create schema productSchema;` unquoted and H2 (DATABASE_TO_UPPER default; the engine's H2Manager sets it only from a user property) stores PRODUCTSCHEMA, while the quoted golden reads `"productSchema"` — the golden cannot execute on the engine's own fixture either; rows can never judge it, the referee's named gap is right. This row's verdict is text; its burn is the plan-text node-kinds hijack of cluster C, unchanged |
| `executionPlan::tests::testViewToTDS` | TODO |
| `executionPlan::tests::withPlatform` | TODO |
| `meta::relational::graphFetch::tests::milestoning::testMilestonedProperty` | TODO |
| `advanced::resultSourcing::relationalResultSourcingOfListExecutionPlan` | TODO |
| `m2m2r::testProp3` | TODO |
| `mapping::union::testEnumFilterWithUnionMappingPlanGeneration` | TODO |

### D. metamodel-API natives — 15 rows — ladder: HIJACK

engine functions over facts we hold (metamodel-as-relations / typed context): return the engine's answer from our data

HOMEWORK 2026-09-13 (read at the PINNED root $HOME/legend/legend-engine 4.145.0 — the neemsandv checkout is 4.137 and lacks the feature-flag suite): four families. (1) FEATURE FLAGS, 5 rows: `useDbNativeImplicitNullOrdering(conn, ctx)` = `isOptionSet('…')` (engine JAVA native; the platform has no runtime options → false) OR `conn.queryGenerationConfigs` has a GenerationFeaturesConfig with the name enabled OR `contextHasFlag(ctx, Feature)` (core `executionPlanFeature.pure`, over `getContexts` in the plan-generation file); all inputs are INSTANCE LITERALS. Open mechanism question: the two helpers live in engine-core files that are the plan generator (never admitted whole as LIBRARY_FILES); `withFeatureFlags` beside them is a lowering native. (2) CONNECTION EQUALITY, 5 rows: `runRelationalRouterExtensionConnectionEquality` matches over `relationalExtensions().routerExtensions().connectionEquality` arms — the extension registry is SUBSUMED (Subsumed.RELATIONAL_EXTENSIONS: 'no arm reads an Extension value' — these five do); the relational arm compares type/timeZone/quoteIdentifiers/datasourceSpecification (classes carry <<equality.Key>> → InstanceEquality's keyed half)/authenticationStrategy via `compareObjectsWithPossiblyNoProperties` (hierarchicalProperties()->size() reflection)/postProcessors. Hijack = the platform folds the subsumed registry's arm to ITS OWN arm (the platform is its own extensions). (3) STORE SUBSTITUTION, 2 rows: `resolveStore` (legend-pure functions_Mapping.pure: fold over includes' substitutions) and `extractDBs` (includes recursion + RootRelational mainTableAlias.database, dedupe) — facts we hold (MappingInclude.substitutions, class-mapping main tables); natives over our compiled mapping model. (4) SINGLES refiled: testPlanForExecutionOption → C (Allocation plan text + extension option variables); testPreprocessFunctionOnRuntime → J (hand-built SimpleFunctionExpression = code-as-data); testRoutingContextBuilderFunctions → J (store-contract routeFunctionExpressions = router internals).

| test | state |
|---|---|
| `executionPlan::tests::testPlanForExecutionOption` | REFILED → C (2026-09-13): plan-text golden with Allocation/let nodes + `extractVariablesFromExecutionOption`; `isExecutionOptionPresent` is trivial, the golden is not |
| `executionPlan::tests::testPreprocessFunctionOnRuntime` | REFILED → J (2026-09-13): builds a SimpleFunctionExpression by hand (`functionReturnType`, `expressionSequence`) = code-as-data, PARKED |
| `executionPlan::tests::testRoutingContextBuilderFunctions` | REFILED → J (2026-09-13): rewrites the store contract's routeFunctionExpressions (router internals); the plan golden itself is plain |
| `meta::relational::functions::sqlQueryToString::tests::testUseDbNativeImplicitNullOrderingResolutionConnectionDisabledIsInert` | HOMEWORK DONE (family 1, feature flags): mechanism decision owed — where core helper functions (contextHasFlag/getContexts) live; isOptionSet = platform constant false |
| `meta::relational::functions::sqlQueryToString::tests::testUseDbNativeImplicitNullOrderingResolutionConnectionEnabled` | HOMEWORK DONE (family 1, feature flags): mechanism decision owed — where core helper functions (contextHasFlag/getContexts) live; isOptionSet = platform constant false |
| `meta::relational::functions::sqlQueryToString::tests::testUseDbNativeImplicitNullOrderingResolutionExeCtxFlag` | HOMEWORK DONE (family 1, feature flags): mechanism decision owed — where core helper functions (contextHasFlag/getContexts) live; isOptionSet = platform constant false |
| `meta::relational::functions::sqlQueryToString::tests::testUseDbNativeImplicitNullOrderingResolutionOffByDefault` | HOMEWORK DONE (family 1, feature flags): mechanism decision owed — where core helper functions (contextHasFlag/getContexts) live; isOptionSet = platform constant false |
| `meta::relational::functions::sqlQueryToString::tests::testUseDbNativeImplicitNullOrderingResolutionOrPrecedence` | HOMEWORK DONE (family 1, feature flags): mechanism decision owed — where core helper functions (contextHasFlag/getContexts) live; isOptionSet = platform constant false |
| `meta::relational::metamodel::execute::tests::testConnectionEqualityAllButOnePropertySame` | HOMEWORK DONE (family 2, connection equality): fold the subsumed registry's arm to the platform's own; keyed instance equality exists; reflection helper (hierarchicalProperties size) to check |
| `meta::relational::metamodel::execute::tests::testConnectionEqualityAllSameStatic` | HOMEWORK DONE (family 2, connection equality): fold the subsumed registry's arm to the platform's own; keyed instance equality exists; reflection helper (hierarchicalProperties size) to check |
| `meta::relational::metamodel::execute::tests::testConnectionEqualityTypeDiff` | HOMEWORK DONE (family 2, connection equality): fold the subsumed registry's arm to the platform's own; keyed instance equality exists; reflection helper (hierarchicalProperties size) to check |
| `meta::relational::metamodel::execute::tests::testConnectionEqualityTypeSameSpecDiff` | HOMEWORK DONE (family 2, connection equality): fold the subsumed registry's arm to the platform's own; keyed instance equality exists; reflection helper (hierarchicalProperties size) to check |
| `meta::relational::metamodel::execute::tests::testConnectionEqualityTypeSpecSameAuthDiff` | HOMEWORK DONE (family 2, connection equality): fold the subsumed registry's arm to the platform's own; keyed instance equality exists; reflection helper (hierarchicalProperties size) to check |
| `mapping::include::testStoreSubstitution` | HOMEWORK DONE (family 3): `resolveStore(mapping, store)` over MappingInclude.substitutions (legend-pure functions_Mapping.pure body) — native over our mapping model |
| `runtime::extractDBs::testExtractDBsWithSubstituition` | HOMEWORK DONE (family 3): `extractDBs(mapping)` = includes recursion + each RootRelational class mapping's main-table database, deduped (engine runtime.pure:75-86); the test's 'substitution' is a DATABASE include (DB1 includes DB1_Inc; `[DB1]testTable1` → DB1) |

### E. our own compiler as the engine's — 10 rows — ladder: HIJACK

compileLegendGrammar / protocol transforms / DDL statements: the platform already parses, compiles and emits these — expose them under the engine's names

| test | state |
|---|---|
| `executionPlan::tests::datetime::testPlanWithLocalH2ConnectionWithSQL` | TODO |
| `graphFetch::tests::XStore::milestoning::testCrossStoreGraphFetchWithRelationalDatePropagationForMilestonedPropertyConstraint` | TODO |
| `graphFetch::tests::XStore::milestoning::testCrossStoreGraphFetchWithRelationalDatePropagationForMilestonedPropertyZeroToOne` | TODO |
| `graphFetch::tests::m2m2r::milestoning::milestonedSourceToNonMilestonedTargetProperty::testFlatten_ViaNoArgMapping` | TODO |
| `graphFetch::tests::m2m2r::milestoning::milestonedSourceToNonMilestonedTargetProperty::testFlatten_ViaNoArgMapping_ViaAssociation` | TODO |
| `meta::relational::functions::sqlQueryToString::tests::testTempTableSqlStatementsForH2` | TODO |
| `ddl::dropAndCreateTempTable` | TODO |
| `ddl::testCreateTempTableStatement` | TODO |
| `typeInference::testTranslateDbType` | TODO |
| `meta::relational::transform::autogen::tests::testClassesAssociationsAndMappingFromDatabase` | TODO |

### F. typer gaps — 3 rows — ladder: FIX

a typing refusal on a real query shape — HOMEWORK DONE 2026-09-13: two rows were misfiled (the typer refusal was only the first wall; → D/I and J), one is a real one-line typer bug with a 1-row lowering leg behind it (parked). Cluster F burns 0 rows on its own.

| test | state |
|---|---|
| `executionPlan::tests::testSupportStreamFlagWithGraphFetchAndFrom` | HOMEWORK 2026-09-13 — NOT a typer gap. The assert is a PLAN-STRUCTURE fact: `FunctionParametersValidationNode.functionParameters.supportsStream` for `firmName` (engine executionPlan_generation.pure: `findParamsSupportedForStreamInput` on the routed function). Behind the deferred-let refusal: `calculateSourceTree` (uncatalogued), a chained model-connection runtime (`getRuntimeWithModelConnection` over `$sourceFirms`), and the plan-node API (`allNodes`, node class casts). RECLASSIFIED → D (plan/metamodel API) + I (chained M2M runtime). The only engine test asserting supportsStream. |
| `router::preeval::tests::testPrerouting42` | HOMEWORK 2026-09-13 — NOT a typer gap. `assertRoundTrip` runs the engine's `preval` router pass on the input lambda and compares `transformFunctionBody->toJSON(50000)` with the expected lambda: router internals over code-as-data (PARKED). Our refusal comes from the EXPECTED lambda's `^BasicColumnSpecification(name=, func=)` literals inside `project([...])` (ProjectChecker admits `col(fn,'n')` and paths, not instances). RECLASSIFIED → J. Side note: `^BasicColumnSpecification` in project appears in 18 engine files; the only other fail-roster row from them is `lineage::scanColumns::test::testNonDataTypeProperty` (a lineage test, not this shape). |
| `tds::window::routing::testExecutionPlanGeneration` | HOMEWORK 2026-09-13 — the typer gap is REAL and one line: legacy `olapGroupBy(['a','b'], …)` desugared its partition columns as a COLLECTION of ColSpecs, so `over` had no overload (the modern spelling is ONE ColSpecArray `~[a,b]`, restrict's precedent); fixed in Typer.olapGroupByDesugar (witness owed; rides the next row-moving batch). Behind it, measured with the fix: `plan: star-top TDS column 'ageSum' resolves through no FROM-tree table` — our lowering wraps the grouped select and windows over it; the golden (H2 text, no fixture → rows cannot judge) is ONE grouped select with `count(sum(age)) over (…)` in the same select. The fold (window over a grouped select into one select) is the lean shape but a lowering leg whose only corpus row is THIS one (no other engine test chains groupBy → olapGroupBy). PARKED: 1 row, text-exact bar, behind two walls. |

### G. text idioms — 12 rows — ladder: HIJACK

the engine's SQL text spelling where rows cannot judge

| test | state |
|---|---|
| `alloy::connections::tests::relationalMapper::testRelationalMapperTwoDBs` | TODO |
| `alloy::connections::tests::relationalMapper::testRelationalMapperWithJoin` | TODO |
| `functions::sqlstring::testEqualityInFilterOnOptionalPropertiesLegacy` | TODO |
| `functions::sqlstring::testNotEqualityInFilterOnOptionalPropertiesLegacy` | TODO |
| `mapping::union::biTemporal::testBiTemporalUnionAsJoinTarget_correlatedSubqueryQuoting` | TODO |
| `mapping::union::biTemporal::testBiTemporalUnionJoin_milestoningColumnInOnClause` | TODO |
| `mapping::union::testChainedUnions` | TODO |
| `mapping::union::testProjectThroughAsso` | TODO |
| `mapping::union::testProjectThroughAssoWithJoinInMapping` | TODO |
| `mapping::union::testUnionWithSinglePropertyMapping` | TODO |
| `projection::view::testUnionOnViewsMapping` | TODO |
| `query::legacyNullUnsafeEquals::testLegacyFlagRestoresOptionalParamFreeMarkerSelector` | TODO |

### H. other-dialect text goldens — 7 rows — ladder: HIJACK?

DB2 / SQL Server spellings in the text renderer — product value only if those targets matter

| test | state |
|---|---|
| `functions::sqlstring::testIsDistinctSQLGeneration` | TODO |
| `functions::sqlstring::testSqlGenerationDivide_AllDBs` | TODO |
| `groupBy::testGroupByWithJoinDB2` | TODO |
| `projection::filter::testFilterAfterJoinInRelationWithExtendedPrimitives` | TODO |
| `query::take::testFilterLimitInSequenceForTableAccessor` | TODO |
| `query::take::testLimitFilterInSequenceForTableAccessor` | TODO |
| `tds::sort::testSortQuotes` | TODO |

### UNION FAMILY — homework 2026-09-13 (8 rows, USER: "let's do the homework for union")

Read at the pinned root, each row run scoped with the SQL dump; the graph-fetch row probed for its full JSON (temporary message, reverted).

- **5 rows fail ONLY on the engine's `removeUnionOrJoins` post-processor half** (testProjectThroughAsso, testProjectThroughAssoWithJoinInMapping, testChainedUnions, testUnionWithSinglePropertyMapping, testUnionOnViewsMapping): their rows halves PASS today (the row asserts and even the exact-text `assertSameSQL`/`assertEquals(sql)` of the unflagged form pass through our engine-style writer); the second half runs the runtime with the connection feature `REMOVE_UNION_OR_JOINS` enabled and asserts `sql()->contains('union_gen_source_pk_0')` + csv equality with the unflagged result. We have no such pass. WHAT IT IS (engine pureToSQLQuery_union.pure:870-1399, ~530 lines): for a join whose source and/or target is a UnionAll of PK-carrying sets, build a BRIDGE union-all — one leg per (source set, target set) pair, each an INNER join carrying only the primary keys as `"union_gen_source_pk_i"` / `"union_gen_target_pk_i"` — and join both sides to the bridge on PKs, replacing the OR-join over the union (`x.FirmID_0 = root.ID or x.FirmID_1 = root.ID`) with equi-joins; on by default for Snowflake, else by the connection's GenerationFeaturesConfig (the connection-level flag carrier the platform does not read yet). A real optimizer capability (a compiler pass by ruling; docs/... post-processors are IR passes), 5 rows + their H2 mirrors; sized as a DESIGN LEG (2-3 sessions): IR pass + connection flag carrier + the bridge's PK naming — SPEC WRITTEN: docs/UNION_OR_JOIN_REMOVAL_DESIGN_2026_09_13.md.
- **test6 — FIXED 2026-09-13 (GATES: Graph-root order rule; DuckDB 111→110, H2 447→446).** Was: CONTENT IDENTICAL, ROOT ORDER ONLY — ours X, A, B (set order), golden B, X, A; every firm's employee list matches element for element. Pure specifies no order for `Firm.all()->graphFetch()`; the golden's order is H2's execution order of the engine's union. REFEREE RULE OWED: the unordered-chain register (rows as a multiset) extended to a graph-fetch ROOT ARRAY when the query carries no sort — 1 row (+H2). Not a product defect.
- **2 bitemporal rows**: pure TEXT asserts (`sql()->contains('"unionalias_1"."lake_thru_0"')` etc.) pinning the engine's nested-union alias convention and a quoting fix for H2 case sensitivity; our SQL spells the milestoning columns as its own union aliases (`lake_thru_1` …) and executes. No rows asserted. WALL candidates (text contract on an engine alias convention), unless the union-removal leg happens to align the spelling.

Verdict: the union family holds ZERO wrong-result rows. 5 = one optimizer feature (design leg), 1 = referee order rule, 2 = text contracts.

### I. lanes (design first) — 21 rows — ladder: FIX (lane)

a design session opens the lane; then rows burn

| test | state |
|---|---|
| `executionPlan::tests::inheritance` | TODO |
| `executionPlan::tests::testEnumPushDownWithExternalFormat` | TODO |
| `executionPlan::tests::testModelConnectionDeepFunction` | TODO |
| `executionPlan::tests::testModelConnectionJoin` | TODO |
| `executionPlan::tests::testRelationalProjectionWithExternalFormat` | TODO |
| `graphFetch::tests::XStore::inMemoryAndRelational::testCrossMappingJsonToDBWithExplosion` | TODO |
| `graphFetch::tests::XStore::inMemoryAndRelational::testCrossStoreWithCSVDataSource` | TODO |
| `lineage::scanColumns::test::testNonDataTypeProperty` | TODO |
| `tds::toRelation::testJoinFunc` | TODO |
| `tds::toRelation::testJoinUsing` | TODO |
| `meta::relational::graphFetch::tests::union::propertyLevel::test6` | TODO |
| `meta::relational::tds::schema::tests::resolveSchemaTest` | TODO |
| `meta::relational::testDataGeneration::tests::alloy::testAlloyTestDatGenWithQuotedColumnsForViews` | TODO |
| `advanced::forced::structure::testQualifierWithOperation` | TODO |
| `advanced::forced::structure::testTwoQualifiersWithOperation` | TODO |
| `functions::sqlstring::testToSQLStringWithAbs` | TODO |
| `functions::sqlstring::testToSQLStringWithAggregation` | TODO |
| `json::testResultToJsonStream` | TODO |
| `mapping::modelJoin::advanced::testNestedModelJoinCompoundInnerCondition` | TODO |
| `mapping::relation::testMixedMappingWithFilterInProject` | TODO |
| `mapping::relation::testRelationStoreAccessorOnView` | TODO |

### J. router and printer internals — 31 rows — ladder: WALL? (last resort)

asserts on the engine's router output structures or on its SQL printer over hand-built SQL-metamodel instances — each row still gets its homework before a wall

| test | state |
|---|---|
| `executionPlan::tests::execution::testPureExecutionStrategyForCreateAndPopulateTempTableExecutionNode` | TODO |
| `executionPlan::tests::execution::testPureExecutionStrategyForRelationalInstantiationExecutionNode` | TODO |
| `meta::relational::functions::sqlQueryToString::default::testProcessIdentifierWithQuoteChar` | TODO |
| `meta::relational::functions::sqlQueryToString::tests::testCaseNullOrderingSupportFlagOnSkipsCaseShim` | TODO |
| `meta::relational::functions::sqlQueryToString::tests::testCaseNullOrderingSupportUsesUnderlyingExpression` | TODO |
| `meta::relational::functions::sqlQueryToString::tests::testNativeOnlySupportIgnoresExplicitNullOrder` | TODO |
| `meta::relational::functions::sqlQueryToString::tests::testNullsHighOrderingSupport` | TODO |
| `meta::relational::functions::sqlQueryToString::tests::testNullsHighOrderingSupportFlagOn` | TODO |
| `meta::relational::functions::sqlQueryToString::tests::testNullsLowOrderingSupport` | TODO |
| `meta::relational::functions::sqlQueryToString::tests::testNullsLowOrderingSupportFlagOn` | TODO |
| `meta::relational::functions::sqlQueryToString::tests::testUniformNullsLastOrderingSupport` | TODO |
| `meta::relational::functions::sqlQueryToString::tests::testUniformNullsLastOrderingSupportFlagOn` | TODO |
| `meta::relational::functions::toPostgresModel::tests::testConvertJoinTreeNode` | TODO |
| `meta::relational::functions::toPostgresModel::tests::testConvertSelectSQLQuery` | TODO |
| `meta::relational::graphFetch::domain::tests::testGraphFetch` | TODO |
| `functions::pureToSqlQuery::addDriverTablePkForProject` | TODO |
| `functions::pureToSqlQuery::simpleFunctionExpressionTranslationAdjust` | TODO |
| `functions::pureToSqlQuery::simpleFunctionExpressionTranslationNow` | TODO |
| `functions::pureToSqlQuery::testFindAliasMappingBySchemaName` | TODO |
| `functions::pureToSqlQuery::testFindFunctionSequenceMultiplicity` | TODO |
| `functions::pureToSqlQuery::testImportDataFlow` | TODO |
| `functions::pureToSqlQuery::testMergeOldAliasToNewAlias` | TODO |
| `functions::pureToSqlQuery::testReAliasMergedJoinOperations` | TODO |
| `milestoning::applyMilestoningFilters::testMilestoningFilterApplicationOnSemiStructuredRelationalOperationElements` | TODO |
| `postProcessor::filterPushDown::testPushFiltersDownToJoinsPostProcessorToSQL` | TODO |
| `postProcessor::testDb2ColumnRename` | TODO |
| `postProcessor::testPostProcessTransformJoinOp` | TODO |
| `query::routing::multipleexpressions::testPlatformExpressionDependencyOnAFromExpression` | TODO |
| `query::routing::multipleexpressions::testPlatformExpressionDependencyOnAFromExpression2` | TODO |
| `query::routing::testCompositionInMultiStatementPureExpressions` | TODO |
| `query::routing::testRoutingOfSimpleQualifiedProperty` | TODO |
