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
| `meta::relational::graphFetch::tests::simple::testCheckedWithCircularConstraints` | ACCEPTED 2026-09-12 — the golden encodes an engine bug the test's own `toFix` comment names (isDistinct inside a checked constraint); upstream's intended output is no defects for all four persons, which is ours. Accepted-roster bucket `engine-golden-defect:isDistinct-in-checked-constraint(upstream-toFix)` — DuckDB lane. On H2 the checked envelope fails earlier (`LIST_FILTER` has no H2 encoding): an H2-lane capability gap, stays a FAIL there. |
| (own witness) NESTED-OBJECT CONSTRAINTS: the engine evaluates a nested class's constraints and hoists their defects to the root with a path (`[{propertyName: firm}]`); we evaluate the root class's only (SQL dumped: 3 predicates, Firm's 2 absent) | FIX — next batch, with our own checked-fetch test as the witness (no corpus row exercises it correctly) |

### B. plan text — near misses — 6 rows — ladder: HIJACK

one or two spellings in the plan-text channel

| test | state |
|---|---|
| `executionPlan::tests::testGroupByWithOpenVariableInAgg` | BURNED 2026-09-12 (FIX) — fixture on demand seeds its store, but the fixture has no calendar row for 2005-10-10 (empty Allocation: rows cannot judge; text is the verdict). The join order was PHASE order (a nav-date chain registers first and sinks deepest), found by a stack probe in the TypedJoin constructor; `resolver/SlotOrder` now re-sequences the root's step joins into first-read order at the root materialization (GATES: Join order by first read); passes by text, both lanes |
| `executionPlan::tests::testGroupByWithTwoOpenVariablesInAggAndFilter` | BURNED 2026-09-12 (FIX) — the same reorder, plus `PlanParam.Kind.STRICT_DATE`: a StrictDate let spells `DATE'${startDate}'` where pure Date spells TIMESTAMP; passes by text, both lanes |
| `executionPlan::tests::testTemporalDateVariableInFunctionExpressionWithPropagation` | BURNED 2026-09-12 (FIX, referee) — fixture on demand seeds the store its mapping reads; judged by ROWS, both lanes (GATES: Fixture on demand) |
| `executionPlan::tests::testTwoMappingsOneRuntime` | TODO |
| `executionPlan::tests::testTwoMappingsOneRuntimeWithoutExternalMapping` | TODO |
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

| test | state |
|---|---|
| `executionPlan::tests::testPlanForExecutionOption` | TODO |
| `executionPlan::tests::testPreprocessFunctionOnRuntime` | TODO |
| `executionPlan::tests::testRoutingContextBuilderFunctions` | TODO |
| `meta::relational::functions::sqlQueryToString::tests::testUseDbNativeImplicitNullOrderingResolutionConnectionDisabledIsInert` | TODO |
| `meta::relational::functions::sqlQueryToString::tests::testUseDbNativeImplicitNullOrderingResolutionConnectionEnabled` | TODO |
| `meta::relational::functions::sqlQueryToString::tests::testUseDbNativeImplicitNullOrderingResolutionExeCtxFlag` | TODO |
| `meta::relational::functions::sqlQueryToString::tests::testUseDbNativeImplicitNullOrderingResolutionOffByDefault` | TODO |
| `meta::relational::functions::sqlQueryToString::tests::testUseDbNativeImplicitNullOrderingResolutionOrPrecedence` | TODO |
| `meta::relational::metamodel::execute::tests::testConnectionEqualityAllButOnePropertySame` | TODO |
| `meta::relational::metamodel::execute::tests::testConnectionEqualityAllSameStatic` | TODO |
| `meta::relational::metamodel::execute::tests::testConnectionEqualityTypeDiff` | TODO |
| `meta::relational::metamodel::execute::tests::testConnectionEqualityTypeSameSpecDiff` | TODO |
| `meta::relational::metamodel::execute::tests::testConnectionEqualityTypeSpecSameAuthDiff` | TODO |
| `mapping::include::testStoreSubstitution` | TODO |
| `runtime::extractDBs::testExtractDBsWithSubstituition` | TODO |

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

a typing refusal on a real query shape

| test | state |
|---|---|
| `executionPlan::tests::testSupportStreamFlagWithGraphFetchAndFrom` | TODO |
| `router::preeval::tests::testPrerouting42` | TODO |
| `tds::window::routing::testExecutionPlanGeneration` | TODO |

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
