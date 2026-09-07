# Phase 5 — code and metamodel as data: the sizing (2026-09-08)

Status: SIZING. Written after batch 146 from the DuckDB lane's 108 fail
messages (`lane-duckdb.out`, batch 146), the code-as-data homework
(`docs/CODE_AS_DATA_HOMEWORK_2026_09_05.md`, never re-derived), the two-legs
research (`docs/TWO_DESIGN_LEGS_2026_09_07.md` §3) and the metamodel-as-relations
homework (`docs/METAMODEL_AS_RELATIONS_HOMEWORK_2026_09_02.md`). USER 2026-09-08:
Phase 5 directly after 146; the two remaining code legs deferred.

## 1. The 108 by owner, named (DuckDB lane, batch 146)

Read off the failure messages; each row is one test unless a count is given.

**TEXT contracts — 33 (Phase 4, the referee leg; none is a Phase 5 test).**
DB2 text (6): sqlstring `testEqualityInFilterOnOptionalPropertiesLegacy`,
`testNotEqualityInFilterOnOptionalPropertiesLegacy`, `testIsDistinctSQLGeneration`,
`testSqlGenerationDivide_AllDBs`, groupBy `testGroupByWithJoinDB2`, tds::sort `testSortQuotes`.
Unformatted plans (5): `testQuoteIdentifiersFlagWithGraphFetch`,
`testFilterAfterJoinInRelationWithExtendedPrimitives`, `testIsEmptyOnCollection`,
`testFilterLimitInSequenceForTableAccessor`, `testLimitFilterInSequenceForTableAccessor`.
Rows underivable — plan goldens over unseeded stores (4): `testGroupByWithOpenVariableInAgg`,
`testGroupByWithTwoOpenVariablesInAggAndFilter`,
`testTemporalDateVariableInFunctionExpressionWithPropagation`, m2m2r `testProp3`.
Plan / PureExp texts (3): `testTwoMappingsOneRuntime`, `…WithoutExternalMapping`,
graphFetch milestoning `testMilestonedProperty`. Connection-mapper SQL text (2):
`testRelationalMapperTwoDBs`, `testRelationalMapperWithJoin`. H2-advisory row divergence
(3): `testQualifierWithOperation`, `testTwoQualifiersWithOperation`,
`testBusinessDateInjectionFromVarReferenceInProjectUsingExternalFunction`.
Substring asserts on engine-internal aliases, `Assert failed` (10):
`testBiTemporalUnionAsJoinTarget_correlatedSubqueryQuoting`,
`testBiTemporalUnionJoin_milestoningColumnInOnClause`, `testChainedUnions`,
`testProjectThroughAsso`, `testProjectThroughAssoWithJoinInMapping`,
`testUnionWithSinglePropertyMapping`, `testUnionOnViewsMapping`,
`testLegacyFlagProjectionEmitsPlainEquals`,
`testLegacyFlagRestoresOptionalParamFreeMarkerSelector`,
`testExecutionPlanGenerationForLambdaFromWithEnumMapping`.

**ENGINE-MACHINERY — 44 (the engine's own router / planner / renderer /
protocol under test: WORLD_MAP kind A, never ported; named, never passes
unless Phase 5 D5 is ever chosen — it is not).**
Router: `routeFunction` ×5 (`addDriverTablePkForProject`,
`testPlatformExpressionDependencyOnAFromExpression`, `…2`,
`testCompositionInMultiStatementPureExpressions`, `testRoutingOfSimpleQualifiedProperty`),
`testRoutingContextBuilderFunctions` (extends the router's `routeFunctionExpressions`),
`testPrerouting42`. Planner: `defaultState` ×2 (`simpleFunctionExpressionTranslationAdjust`,
`…Now`), `testFindFunctionSequenceMultiplicity` (asserts the engine compiler's auto-`map`
insertion in the expression tree), executionPlan `inheritance`, `testModelConnectionDeepFunction`,
`testModelConnectionJoin`, `testCrossDbPlanGenerationWithRelationFromWithOnlyRuntimes`,
`testSupportStreamFlagWithGraphFetchAndFrom`, `testEnumFilterWithUnionMappingPlanGeneration`,
`relationalResultSourcingOfListExecutionPlan`, `isExecutionOptionPresent` (`testPlanForExecutionOption`),
`newMultiValueMap` (`pureToSqlQuery::testImportDataFlow`). Renderer / SQL AST as values:
`testProcessIdentifierWithQuoteChar`, `testTempTableSqlStatementsForH2`, `testCreateTempTableStatement`,
`dropAndCreateTempTable`, `testFindAliasMappingBySchemaName`, `testMergeOldAliasToNewAlias`,
`testReAliasMergedJoinOperations`, `testConvertJoinTreeNode`, `testConvertSelectSQLQuery`,
post-processors `testPushFiltersDownToJoinsPostProcessorToSQL`, `testDb2ColumnRename`,
`testPostProcessTransformJoinOp`, `testTranslateDbType`,
`testMilestoningFilterApplicationOnSemiStructuredRelationalOperationElements`, `testViewToTDS`.
Protocol / grammar / other engine surfaces: `transformPlan` (`testPlanWithLocalH2ConnectionWithSQL`),
`PureModelContextData` (`testClassesAssociationsAndMappingFromDatabase`), `compileLegendGrammar` ×2
(XStore milestoning), `exampleExternalFormatExtension` ×2, `resolveStore` ×2
(`testStoreSubstitution`, `testExtractDBsWithSubstituition`), `resolveSchemaTest`,
`toJSONStringStream` (`testResultToJsonStream`), service test runner `evaluate` ×2
(`testPureExecutionStrategyFor…` ×2), `testFlatten_ViaNoArgMapping` ×2 (`from($var)` — a
M2M2R plan surface), `testCrossMappingJsonToDBWithExplosion` (M2M chain).

**CODE-AS-DATA — 16 (Phase 5, ours).** See §3 for the slices.
Extension registry (5): `testConnectionEqualityAllButOnePropertySame`, `…AllSameStatic`,
`…TypeDiff`, `…TypeSameSpecDiff`, `…TypeSpecSameAuthDiff`. The program as m3 data (2):
graphFetch::domain `testGraphFetch` (reads `.func`, `.parametersValues`, the tree literal; builds
lambdas from tree pieces; plans them), `testPreprocessFunctionOnRuntime` (hand-builds a
`^SimpleFunctionExpression(func = from_…, parametersValues = $lambda.expressionSequence ++
^InstanceValue(values = $runtime))` and plans `^FunctionDefinition(expressionSequence = …)` —
the reader direction). Protocol types as data (2): `testJoinFunc`, `testJoinUsing` (the toRelation
`test(...)` helper types protocol `LambdaFunction` values; the prelude excludes `vX_X_X`).
Plan rows discriminated (2): `testGraphFetchH2TempTableStrategy`, `…WithQuoteIdentifiers`
(`instanceOf(StoreMappingGlobalGraphFetchExecutionNode)` over an ExecutionNode row — a plan node
kind our planner does not produce; D3 AND planner parity). Class instances in value position, our
resolver's `class query under Typed<X> is not resolvable yet` wall (5): `planGraphFetchWithDerivedProperty`,
`planGraphFetchWithNestedDerivedProperty`, `testCrossStoreWithCSVDataSource`, `testToSQLStringWithAbs`,
`testToSQLStringWithAggregation` (the whole-value class column, H4 — `testNonDataTypeProperty` was
PARKED under the same name, batch 104).

**Other, decided or deferred — 15.** Deferred code legs (2): `testMixedMappingWithFilterInProject`,
`testAlloyTestDatGenWithQuotedColumnsForViews`. Parked (2): `testNestedModelJoinCompoundInnerCondition`
(nested ModelJoin, three walls), `testNonDataTypeProperty` (H4). REVISIT receipts kept as FAIL (2):
`test6`, `testCheckedWithCircularConstraints`. Real divergences not yet traced (3):
`testDateTimeInclusiveRangeQuery` (2 rows vs 1), `testRelationStoreAccessorOnView` (a Relation store
accessor over a VIEW — `personView` never created: a store leg), `withPlatform` (`STRING_AGG` reached a
dialect without a list encoding — the PureExp tail). Typing (1): tds::window
`testExecutionPlanGeneration` (`over` typing; verdict is plan text). Engine-golden-defect candidates
counted above. The three untraced divergences are Phase 4 material (trace, then decide) — not Phase 5.

Totals: 33 + 44 + 16 + 15 = 108.

## 2. The five demands against what is already landed

| # | demand (TWO_DESIGN §3.1) | landed | receipt |
|---|---|---|---|
| D1 | metamodel as relations | YES | `SystemMetamodel` (classes, properties, mappings, joins, connections, datasources), `MetamodelSeeds`, read-only per graph |
| D2 | closures as relations | YES | `mapping_includes_closure`, `set_ancestry`, `plan_node_closure`; `class_ancestry` exists on the parked branch only |
| D3 | the plan as relations | PARTLY | `PlanRows` (nodes, closure, template functions, connections); `PLAN_NODE_KINDS` is a fixed list — the graph-fetch temp-table node is not in it because the planner never emits it |
| D4 | the user program as relations | STARTED (2026-09-03, groups A/H) | `FunctionBodyRows`: a lambda's `expressionSequence` is `functions` / `value_specifications(id, function_id, ordinal, kind, parent_id, depth, mult_lower, mult_upper, var_name)` rows riding the query; `VS_KINDS` = FunctionExpression / InstanceValue / VariableExpression class mappings over `kind`; `parametersValues` = children rows; `multiplicity` = the real m3 Multiplicity → MultiplicityValue shape; `inferredPrimaryKeyColumns` (PkInference). MISSING on the row: `func` (the callee — a reference to the function/property row), `functionName`, `values` of an InstanceValue, `genericType`, the QUOTED-code reader (a hand-built m3 tree → executable) |
| D5 | re-hosting the engine's compiler | NO, by decision | the 44 ENGINE-MACHINERY rows above; named out of scope once, here |

Correction to the earlier census (ledger §15 "CODE-AS-DATA ~19 / ENGINE-MACHINERY ~38"):
by message the split is 16 / 44. The difference is the SQL-AST-as-values group (alias merging,
temp-table statements, toPostgresModel) — those tests exercise the engine's renderer over its own
SQL metamodel objects and belong to kind A. TWO_DESIGN's "phase 1 = 8 tests" is our 5 + 2 + 2 minus
the plan-kind pair, within one of each other.

## 3. The slices, smallest witnessed first

**Slice 1 — the extension registry read by need (5 tests, designed).** The homework §4: records
stand (R1), a field read forces the field it names (R2), lets are classified by use (R3), the query
boundary forces (R4); the match over extension-contributed arms becomes static dispatch on the
spelled arms; `hierarchicalProperties` is a navigation over `class_ancestry` rows (D2, from the
branch); `routerExtensions` a Pure function over the record. ~80 lines in `UserCallInliner` +
`LiteralUnroll`, plus the branch's D2 rows and the template protocol package in the prelude
generator. The one semantic deviation (§4.3, strict vs by-need on an UNREAD failing field) is a
user decision — say yes or no before the batch. Guards: string-dispatch pin, catch-returns-value
pin, CodeShape caps, NativeCatalogGovernance INTERNAL_DESUGAR.

**Slice 2 — the program as m3 data, both directions (2 tests + the D4 columns).** (a) the row gains
`func_fqn` / `function_name`, and `FunctionExpression.func` navigates to the function or property
row (the metamodel already holds both); `InstanceValue.values` for literal payloads; (b) the READER:
a hand-built `^SimpleFunctionExpression(func = …, parametersValues = …)` / `^FunctionDefinition(
expressionSequence = …)` assembled from literal pieces types and executes (`testPreprocessFunctionOnRuntime`);
(c) `testGraphFetch`: `.func ==` on the row, the tree literal read back through `InstanceValue.values`,
lambdas built from tree pieces then planned — the fold route (LiteralUnroll) since every piece is a
literal. Recursion over the tree (`findFunctionSequenceMultiplicity`-style walks) is a compile-time
BOUNDED unroll — the lambda is a literal, its depth is known — never a recursive CTE (TWO_DESIGN §3.2–3.3).
Size: the homework's ~300 + ~300 lines; two batches.

**Slice 3 — protocol types as data (2 tests).** Admit the template package
`meta::protocols::pure::vX_X_X::metamodel::m3::` into the prelude generator (the branch's 39 lines);
`testJoinFunc` / `testJoinUsing` then meet their next wall honestly (the toRelation helper's body).
Possibly folds into slice 1's prelude change.

**Slice 4 — class instances in value position (5 tests, H4).** A class-typed value under
graphFetch / map / new / toSQLString positions — the whole-value class column. The parked H4 item;
size after slices 1–3 (it is a resolver leg, not a data leg).

**Not a slice — the plan-kind pair (2).** Needs the graph-fetch temp-table planner strategy itself;
planner parity, not data. Stays ENGINE-adjacent until a planner leg exists.

## 4. Recommendation

Start with slice 1: five witnesses, a written design, the branch already holds the D2 rows and the
prelude change, and it is the leg the user parked on 2026-09-05 to return to. Batch 147 = slice 1
whole (one batch, three fix cycles then a wall). Then slice 2a+2b (batch 148), 2c (149), slice 3
riding whichever prelude change lands first. Slice 4 after.

Pending user decisions before batch 147: (i) the strict-vs-by-need deviation (§3 slice 1);
(ii) confirm D5 is named out of scope for the whole phase (the 44 rows stay FAIL under
`engine-machinery`, no new status).
