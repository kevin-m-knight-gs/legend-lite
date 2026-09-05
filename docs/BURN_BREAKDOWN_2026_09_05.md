# The honest breakdown of the 168 remaining fallbacks (2026-09-05, after batch 72c)

User order: "a full honest breakdown of what is left that can actually be
implemented for real, and burn those down to zero." Method: every one of the
168 tests read at the source (engine test body) together with its failing
assert row in the ledger (docs/RELATIONAL_CORPUS.md "### assert ledger") and
its wall text (core/target/wholetest-flip-buckets.txt). Source of truth for the
list: the flip-buckets file of the batch-72c run (ratchet 168/2405, census 2575).

Four honest labels:

- **IMPL** — a platform leg exists that would make the test's verdict REAL
  (rows or values). Work we should do.
- **TEXT** — the failing assert compares the ENGINE'S SPELLING of SQL or plan
  text (alias names such as `unionalias_1` / `union_gen_source_pk_0`, plan
  print formats, foreign-dialect SQL nobody can execute). Cannot be passed
  "for real" without copying the engine's text; the charter's rows-are-the-
  verdict rule names these `sql-text-assert` / `plan-text`. Where a REFEREE
  could bring the golden to rows, that is noted as a referee leg (IMPL-ref).
- **ENGINE** — the test exercises the engine's OWN compiler / router / planner
  / SQL renderer / DDL / execution machinery directly (kind A in
  docs/CODE_AS_DATA_HOMEWORK_2026_09_05.md §5). We replace those programs; we
  never port them. A named decision, not work.
- **NAMED** — already a receipt, a registered engine-golden defect, or a user
  decision (parked, recursion, protocol).

Where a test carries two problems (a wall of ours FIRST, then a text-only
assert behind it) the row says so: fixing the wall is real work; the assert
behind it is still TEXT.

---

## 1. IMPL — implementable for real (legs, with their tests)

### L1 Resolver: navigation shapes (15 tests)

Status: **batch 73 / L1a LANDED** — testQualifiedPropertyInQuery and testSubFilter
flipped (166/2407): the synthetic predicate's nested-association reads widen the
target pipe like the association condition already did. Sub-legs found by the
probes, still open: (i) testExistsAsNullWithSubType — inside a nested exists scope
a class-typed slot mapped to TWO subtype sets registers only one set's bindings
(the `stc_<Sub>___id` leaf is missing from the AssocSub); (ii) the three multi-hop
tests are two designs: a filtered to-many hop INSIDE a 4-hop chain with an
embedded+join tail (multigrain), and join slots behind subtype witnesses
(`stc_<Sub>___<joinProp>.<leaf>` — the cast chain `employees->subType(@PersonExtension).manager…`
and the union member `vehicles->subType(@Bicycle).person.name`).

| test | wall | note |
|---|---|---|
| projection::exists::testExistsAsNullWithSubType | nested navigation inside exists/isEmpty | rows + assertSameSQL (text behind) |
| modelJoin::advanced::testQualifiedPropertyInQuery | nested navigation inside exists/isEmpty | rows |
| modelJoin::advanced::testSubFilter | nested navigation inside exists/isEmpty | rows |
| multigrain::testToManyWithQualifierWithFilterOnJoin | multi-hop through an embedded/slot head | rows [500] + text behind |
| projection::simple::testRoutingWithSubtypePropagation | multi-hop (subType chain) through an embedded head | assertEquals on SQL text ONLY → TEXT behind the wall |
| testDataGeneration::testInheritanceMultipleLevel | multi-hop vehicles#f.subType.person.name | TDG rows |
| businessdate::testBusinessDateInjectionFromVarReferenceInProjectUsingExternalFunction | milestoned property access on a nested navigation | assertSameSQL → referee rows |
| advanced::forcedselfjoin::isolationTest | correlated filter predicate at depth ≥ 2 (batch 69b wall) | rows |
| injection::testProjectThroughAssociation | filtered-navigation read reached substitution unlifted | rows |
| inheritance::multiJoins::testForcedSubTypeProjectDirect | same lift wall (subType in project) | rows |
| injection::testProjectThroughAssociationAutoMap | object-space TypedFilter not substitutable | rows |
| query::function::testFilterTimesWithManyOperands | aggregate over a navigation whose to-many hop sits behind a to-one | assertSameSQL → referee rows |
| concatenate::testQualifierConcatenateTwoSimilarJoinsEmbedded | class-typed property of an association target as a whole value | rows [1,'OE 1',2,'OE 2'] |
| concatenate::testConcatenateInQualifierWithComplexReturnType | class-typed property used as a whole value (graph output) | rows |
| enumeration::testEnumInRelation | class query under TypedPropertyAccess — a `~[...]` relation project whose columns read enum-mapped and class-typed properties | rows (csv) |

### L2 Resolver: project/extend column resolution and aggregation (3)

| test | wall |
|---|---|
| concatenate::testQualifierConcatenateTwoSimilarJoins | extend/project columns [Trade ID, OE] unresolvable after isolation |
| aggregation::testSubAggregationWithDeepAndOverlap_WithColVar | extend/project columns [a,b,c] unresolvable (cols bound through a let + cast) |
| aggregation::testSubAggregationWithDeepAndOverlap | store resolution left getAll(Firm) unresolved (nested map + count in a col) |

### L3 Relation-mapping family (4)

| test | detail |
|---|---|
| relation::testSimpleMappingQueryWithFilterInProject | DIVERGENCE: rows differ (`Fabrice,Oliver` vs `Fabrice,null`) — filter inside project over a relation mapping |
| relation::testMixedMappingWithFilterInProject | a navigation join over this union demands a key column no member carries |
| union::relation::testUnionTwoRelationMappings_ManyColumnProject | DIVERGENCE: 12-column distinct over a union of two relation mappings |
| union::relation::testUnionTwoRelationMappings_ManyColumnProjectGeneratesSingleUnion | same |

### L4 Union / isolation / join fan-out (6)

| test | detail |
|---|---|
| classMappingFilterWithInnerJoin::testChainedJoinsWithUnionsAndIsolationWithProjectionQueryTableFilter | OUR SQL is invalid: Binder Error "t5" not found (alias scoping bug in a union+isolation chain) |
| tree::testJoinIsolationDeeperTwoIsolations_LeftOuterLeftOuterThenInner | DIVERGENCE: `orgByName('BUSINESS UNIT').name` yields [] where the golden has 'OrgName2' (qualifier with a filter through a self-join tree) |
| join::testMultipleJoinsInPropertyMappingWithDatesInClass | DIVERGENCE: 3 rows vs 6 (a property mapping through multiple joins with date columns in the class — the join must not collapse the two versions) |
| projection::filter::testChainedFiltersQuery | property 'locations' of the filter chain is not mapped (chained filters employees→locations) |
| union::testEnumFilterWithUnionMappingPlanGeneration | plan: alias not resolvable to a table (Subselect) — then the assert is plan TEXT (TEXT behind) |
| union::testPksWithImportDataFlow | typer: multiplicity [*] vs [1] — `RelationalExecutionContext(importDataFlow=true, importDataFlowAddFks=true)` adds the union's pk/fk columns (`ID_0`, `ID_1`) to the projection; rows assert |

### L5 Cross-store model joins as relational joins (4)

| test | detail |
|---|---|
| graphFetch::crossDatabase::testCrossMappingWithRelOpWithJoinKeys | association not mapped — XStore association with join keys across two databases |
| modelJoins::testPersonToFirmUsingFromProject | association not mapped — asserts the XStore plan's SQL EQUALS the single-store plan's SQL (a semantic equality, not a spelling) |
| modelJoins::testPersonToFirmUsingProject | assert-free (zero-assert) — runs the same XStore shape |
| modelJoin::advanced::testNestedModelJoinCompoundInnerCondition | association not mapped — compound inner condition in a model join |

### L6 Graph fetch (4)

| test | detail |
|---|---|
| graphFetch::simple::testCheckedWithCircularConstraints | DIVERGENCE: `graphFetchChecked` defects — constraint evaluation in the checked envelope (expected 1 defect, ours 0) |
| graphFetch::simple::testGraphFetchWithTableMapperPostProcessor | DIVERGENCE: the connection's MapperPostProcessor (table rename) is not applied (4 employees vs 0) — post-processors are compiler passes ([[post-processors-are-compiler-passes]]) |
| graphFetch::union::propertyLevel::test6 | DIVERGENCE: `Firm B` vs `Firm X` — property-level union in graph fetch |
| query::function::concatenate::testAll | lowering not implemented for TypedSerializeGraph — `Product.all()->concatenate(Product.all())` as instances |

### L7 Post-processors as compiler passes (4)

| test | detail |
|---|---|
| alloy::connections::relationalMapper::testRelationalMapperWithJoin | DIVERGENCE: schema/table mapper on the connection (`snDBDefault.default.firmTableNew`) not applied |
| alloy::connections::relationalMapper::testRelationalMapperTwoDBs | same, two databases |
| sqlstring::testNonExecutableSQLString | `toNonExecutableSQLString` — the non-executable rewrite pass (typer: toSQLString 8-arg overload) |
| postProcessor::testPostProcessTransformJoinOp | a connection `sqlQueryPostProcessors` lambda over the SQL AST — the ONE post-processor test that is a user-supplied pass; text assert behind it (TEXT) |

### L8 Natives and small typer legs (12)

| test | detail |
|---|---|
| sqlFunction::stringToFloat::testProject | no scalar lowering for the string→float cast function |
| dataType::testSimpleTypeMappingProjectNulls | no scalar lowering for tinyInt/smallInt column functions |
| mapping::dates::strictdate::testProject | TypedNativeCall in relation position (strict date column) |
| tds::extensions::testFirstNotNull | unresolved type variable T at the lowering boundary |
| tds::extensions::iqrClassifyTest | `col(p\|$p.first,'name')` over a zipped pair list: an in-memory TDS from pairs + `iqrClassify` (engine tdsExtension program) |
| tds::extensions::zScoreTest | same shape, `zScore` |
| tds::extensions::rowValueDifferenceTest | typer: cannot access 'name' on String (a column-name read on a TDSColumn collection) |
| tds::extensions::testExtendDigest_InMemory | TypedNativeCall in relation position — a literal TDS (`project` over pairs) then extendWithDigest |
| projection::testGroupByWithWindowSubset | `groupByWithWindowSubset` unknown — the engine's TDS extension program (admit/inline) |
| sqlstring::testToSQLStringWithCodeBlock | typer: a `#/Trade/date#` path argument typed Any where Date is expected |
| businessdate::testViewChainsWithBusinessDate | typer: `toSQL` 5-arg overload (with connection) missing; then assertSameSQL → referee rows |
| lineage::scanRelations::testTdsJoinConcatenateAndJoin | typer: TDS concatenate of 7 vs N columns (the engine accepts the shape) — lineage rows verdict behind |

### L9 Lineage row verdicts (2)

| test | detail |
|---|---|
| lineage::scanColumns::testNonDataTypeProperty | class query under TypedMap (H2 vocabulary) — scanColumns over a project with a class-typed column |
| lineage::scanRelations::testTableToTdsWithCrossJoin | no SQL type for TableAlias at the lowering boundary — `tableToTDS(tableReference(...))` join (the same store-row leg as batch 55) |

### L10 Time zone and temp tables (1)

| test | detail |
|---|---|
| in::tempTable::testInExecutionWithTempTableForDateTimesWithTz | DIVERGENCE 0 rows vs 5 — the connection time-zone overload for DateTime in-lists (leg started in ConnectionFlags.timeZoneOf) |

### L11 Views (2)

| test | detail |
|---|---|
| relation::testRelationStoreAccessorOnView | Catalog Error: personView does not exist — a relation store accessor over a VIEW must expand the view |
| testDataGeneration::alloy::testAlloyTestDatGenWithQuotedColumnsForViews | TDG over a view-backed relation (view slice) — then a plan-string assert (TEXT behind) |

### L12 Milestoning divergence (1)

| test | detail |
|---|---|
| businessdate::testDateFunctionInMilestonedPropertyWithMilestonedEntity | rows differ under the H2 advisory referee — `classification(constantDate())` on a milestoned entity with an embedded set |

### L13 Model chain over relational (m2m2r) and derived properties (5)

| test | detail |
|---|---|
| m2m2r::planGraphFetchWithDerivedProperty | class query under TypedGraphFetch — M2M mapping chained over the relational mapping (`getM2M2RRuntime`) |
| m2m2r::planGraphFetchWithNestedDerivedProperty | same |
| m2m2r::executeProjectWithNestedDerivedProperty | unknown `meta::json::tdsToJSONKeyValueObjectString` (a JSON native) after the chain |
| executionPlan::testModelConnectionJoin | plan: no class mapping under the model mapping — `ModelChainConnection` (chain) — plan TEXT behind |
| executionPlan::testModelConnectionDeepFunction | same, deep chain — plan TEXT behind |

The m2m2r family already runs in the corpus (core/store/m2m/tests); these five
are the derived-property and chain shapes it does not take yet.

### L14 Raw SQL to TDS and CSV load (3)

| test | detail |
|---|---|
| metamodel::execute::testExecuteInDbToTDS | `executeInDbToTDS('select 1 as "Count"', conn)` — reading an executeInDb result binding (`NormalizeRequired` non-let statements) |
| advanced::resultSourcing::relationalResultSourcingOfListExecutionPlan | reading an executeInDb result binding — then a plan TEXT assert (TEXT behind) |
| loadCsv::testLoadCsv | `loadCsvToDbTable(file, table, conn)` 4-arg overload then `Person.all()` rows — a data-loading native we already have the pieces for (CSV seeds) |

### L15 Referee legs (goldens a referee CAN bring to rows) (1)

| test | detail |
|---|---|
| executionPlan::testQuoteIdentifiersFlagWithGraphFetch | oracle declined: Schema "productSchema" not found — the QUOTED schema name; the referee must create the quoted schema (6 goldens in the census hit this) |

### L16 Plumbing (1)

| test | detail |
|---|---|
| query::simple::testSQLComments | `$result.activities->at(0).comment` matches an executionTraceID comment — activity metadata on our Result |

**IMPL total: 68 tests** (L1 15, L2 3, L3 4, L4 6, L5 4, L6 4, L7 4, L8 12,
L9 2, L10 1, L11 2, L12 1, L13 5, L14 3, L15 1, L16 1). Of these, 9 carry a
TEXT assert behind the wall (marked) — the wall is real work, the assert stays
a text contract.

---

## 2. TEXT — the engine's spelling is the contract (44 tests)

### T1 `assert($sql->contains('<engine alias>'))` over OUR text (11)

The predicate runs honestly over our SQL and is false because it names the
engine's generated aliases or its optimizer's spelling. Rows asserts in the
same tests PASS.

| test | what the text names |
|---|---|
| union::testChainedUnions | `union_gen_source_pk_0` (removeUnionOrJoins spelling) |
| union::extend::testProjectThroughAsso | same |
| union::extend::testProjectThroughAssoWithJoinInMapping | same |
| union::testUnionWithSinglePropertyMapping | same (+ assertSameSQL) |
| projection::view::testUnionOnViewsMapping | same |
| biTemporal::testBiTemporalUnionAsJoinTarget_correlatedSubqueryQuoting | `unionalias_1`/`unionalias_0` quoting |
| biTemporal::testBiTemporalUnionJoin_milestoningColumnInOnClause | same family |
| legacyNullUnsafeEquals::testLegacyFlagProjectionEmitsPlainEquals | plan text `"root".AGE = "persontable_1".AGE` under a feature flag |
| legacyNullUnsafeEquals::testLegacyFlagRestoresOptionalParamFreeMarkerSelector | FreeMarker selector text under the flag |
| executionPlan::testExecutionPlanGenerationForLambdaFromWithEnumMapping | the exact CASE WHEN spelling of enum push-down |
| tdsRestrict::testRestrictOnGroupByEleminatesUnnecessaryAggsWithDistinct | `!contains('max')` — the engine prunes an unused aggregate; rows PASS. An OPTIMIZATION we could add, visible only in text |

### T2 Plan-text goldens (executionPlan printed as a string) (13)

| test | note |
|---|---|
| executionPlan::withPlatform | PureExp/makeString platform node (+ our DialectCapability wall first) |
| executionPlan::testTwoMappingsOneRuntime | Relational plan text |
| executionPlan::testTwoMappingsOneRuntimeWithoutExternalMapping | same |
| projection::filter::testFilterAfterJoinInRelationWithExtendedPrimitives | `planToStringWithoutFormatting` — the golden has no spaces (`select""root""`); unreplayable by construction |
| graphFetch::milestoning::testMilestonedProperty | assert #2 = PureExp plan text (assert #1 rows passes) |
| executionPlan::inheritance | Class plan text (+ our "no class mapping" plan wall first) |
| executionPlan::tdsTwoJoinThreeDB | plan text (+ star-top column wall first) |
| executionPlan::testCrossDbPlanGenerationWithRelationFromWithOnlyRuntimes | plan text (+ mapping-argument wall first) |
| executionPlan::testViewToTDS | plan text (+ unknown dataTypeToCompatiblePureType) |
| tds::window::routing::testExecutionPlanGeneration | Sequence plan text (+ `over` overload wall) |
| query::filter::isempty::testIsEmptyOnCollection | plan-text unformatted — not a statement |
| executionPlan::testGroupByWithOpenVariableInAgg | Sequence plan with OPEN variables — rows underivable |
| executionPlan::testGroupByWithTwoOpenVariablesInAggAndFilter | same |

### T3 Foreign-dialect SQL text (no engine to execute it) (11)

| test | dialect |
|---|---|
| groupBy::testGroupByWithJoinDB2 | DB2 |
| sqlstring::testIsDistinctSQLGeneration | per-DB text (assertSameSQL, DBN) |
| sqlstring::testSqlGenerationDivide_AllDBs | all DBs |
| sqlstring::testEqualityInFilterOnOptionalPropertiesLegacy | DBN legacy |
| sqlstring::testNotEqualityInFilterOnOptionalPropertiesLegacy | DBN legacy |
| tds::postgres::testSortQuotes | Postgres (+ no scalar lowering wall) |
| query::take::testFilterLimitInSequenceForTableAccessor | golden `select top N` (SQL Server) — H2 cannot replay |
| query::take::testLimitFilterInSequenceForTableAccessor | same |
| postProcessor::testDb2ColumnRename | DB2 128-char alias truncation text |
| sqlstring::testToSQLStringWithAbs | `runTestCaseById` — the engine's per-DB expected-SQL table (+ class query under TypedNewInstance wall) |
| sqlstring::testToSQLStringWithAggregation | same registry |

### T4 Plan text with parameters / temporal propagation (2)

| test | note |
|---|---|
| executionPlan::testTemporalDateVariableInFunctionExpressionWithPropagation | rows underivable (parameterized) |
| m2m2r::testProp3 | referee-cannot-replay:no-fixture (receipt) — listed here as its assert is text |

### T5 Plan-as-data (7)

| test | note |
|---|---|
| executionPlan::testGraphFetchH2TempTableStrategy | `instanceOf(StoreMappingGlobalGraphFetchExecutionNode)` over the plan — the plan as a Pure value |
| executionPlan::testGraphFetchH2TempTableStrategyWithQuoteIdentifiers | same |
| executionPlan::testSupportStreamFlagWithGraphFetchAndFrom | reads plan nodes' flags (+ deferred let wall); M2M dest classes |
| executionPlan::testPlanForExecutionOption | a dummy Extension with `extractVariablesFromExecutionOption` — the extension record again (parked family) |
| executionPlan::testPreprocessFunctionOnRuntime | `functionReturnType` reflection over a runtime's preprocess function |
| executionPlan::execution::testPureExecutionStrategyForCreateAndPopulateTempTableExecutionNode | executes a plan NODE by hand (`evaluate`) |
| executionPlan::execution::testPureExecutionStrategyForRelationalInstantiationExecutionNode | same |

**TEXT total: 44.** None of these can pass "for real" under the rows-are-the-
verdict charter; T1's rows asserts already do. T1's last row (aggregate
pruning) is the one optimization that would be worth doing for its own sake.

---

## 3. ENGINE — the engine's own machinery under test (kind A; decisions) (32)

| group | tests | why it is not ours to compile |
|---|---|---|
| pureToSqlQuery internals (8) | testMergeOldAliasToNewAlias, testReAliasMergedJoinOperations, testFindAliasMappingBySchemaName, testFindFunctionSequenceMultiplicity, testImportDataFlow, addDriverTablePkForProject, simpleFunctionExpressionTranslationAdjust, simpleFunctionExpressionTranslationNow | the engine's SQL generator's alias tables, join-tree re-aliasing, data-flow import and per-expression translation — tests OF the planner |
| router (6) | routing::testCompositionInMultiStatementPureExpressions, multipleexpressions::testPlatformExpressionDependencyOnAFromExpression, …2, testRoutingOfSimpleQualifiedProperty, preeval::testPrerouting42, testRoutingContextBuilderFunctions | `routeFunction`, `routeInternal`, pre-evaluation and the routing context — the engine's router (decision:routeFunction) |
| post-processors over the engine's SQL AST (2) | filterPushDown::testPushFiltersDownToJoinsPostProcessorToSQL, applyMilestoningFilters::testMilestoningFilterApplicationOnSemiStructuredRelationalOperationElements | hand-built `TableAliasColumn`/`SemiStructuredPropertyAccess` AST nodes fed to engine passes |
| SQL renderer / DDL (5) | typeInference::testTranslateDbType, sqlQueryToString::testTempTableSqlStatementsForH2, sqlQueryToString::default::testProcessIdentifierWithQuoteChar, ddl::testCreateTempTableStatement, ddl::dropAndCreateTempTable | `translateCoreTypeToDbSpecificType`, `getTempTableSqlStatements`, identifier quoting helpers, temp-table DDL builders |
| runtime helpers (2) | runtime::extractDBs::testExtractDBsWithSubstituition, include::testStoreSubstitution | `extractDBs`/`resolveStore` — the engine's mapping-include store substitution walk (metamodel reflection; would be rows under metamodel-as-relations, but the tests assert engine helpers) |
| tdsToRelation transform (2) | tds::toRelation::testJoinFunc, testJoinUsing | the engine's TDS→Relation protocol transform harness (`test(...)`); also its own `TestClass` model |
| schema resolution program (1) | tds::schema::resolveSchemaTest | `resolveSchema` — a Pure program over the QUERY TREE (needs code-as-data) |
| graph-fetch domain extraction (1) | graphFetch::domain::testGraphFetch | `extractDomainTypeClassFromFunction` reads `FunctionExpression.func` (code-as-data) |
| JSON result stream (1) | json::testResultToJsonStream | a hand-built `Result<TabularDataSet>` streamed to JSON — the engine's result serializer |
| protocol transforms (2) | transform::autogen::testClassesAssociationsAndMappingFromDatabase, executionPlan::datetime::testPlanWithLocalH2ConnectionWithSQL | `PureModelContextData` / `transformPlan` (decision:protocol-transform) |
| recursion over data (2) | toPostgresModel::testConvertJoinTreeNode, testConvertSelectSQLQuery | decision:recursion (task #4 if ever) |

---

## 4. OTHER STORES — a different store's execution (decisions unless a leg opens) (7)

| test | store |
|---|---|
| XStore::inMemoryAndRelational::testCrossMappingJsonToDBWithExplosion | in-memory JSON store crossed with relational (M2M explosion) |
| XStore::inMemoryAndRelational::testCrossStoreWithCSVDataSource | CSV data source store crossed with relational |
| XStore::milestoning::testCrossStoreGraphFetchWithRelationalDatePropagationForMilestonedPropertyConstraint | `compileLegendGrammar` at run time (decision:dynamic-compilation) + cross store |
| XStore::milestoning::testCrossStoreGraphFetchWithRelationalDatePropagationForMilestonedPropertyZeroToOne | same |
| m2m2r::milestoning::testFlatten_ViaNoArgMapping | `getNoArgFlattenMapping()` builds the mapping at run time (decision:dynamic-compilation) |
| m2m2r::milestoning::testFlatten_ViaNoArgMapping_ViaAssociation | same |
| executionPlan::testEnumPushDownWithExternalFormat + testRelationalProjectionWithExternalFormat (2, counted as one row here: 2 tests) | external-format store (`externalize('text/example')`) — no external-format extension in this platform |

(8 tests: the last row is two.)

---

## 5. NAMED — receipts, registered defects, user decisions (16)

| tests | bucket |
|---|---|
| testHashFunctions, testToSQLStringForTDSStringJoin, testExtendDigest_Relational, tdsJoin::alloy::testJoinWithExtendWithDigestOnColumnsOnBothQueries | engine-golden-defect:joinStrings-rendering |
| testToSqlGenerationFirstDayOfWeek | engine-golden-defect:h2-week-start |
| columnValueDifferenceWithoutPrevalTest | engine-golden-defect:alloy-adjust-widening |
| embedded::otherwise::testMilestonedRootAndMilestonedProperty, milestoning::testMilestonedRootAndMilestonedProperty | engine-golden-defect:malformed-json-golden |
| forced::structure::testQualifierWithOperation, testTwoQualifiersWithOperation | decision:empty-toOne-forced-isolation |
| relation::testDateTimeInclusiveRangeQuery | RECEIPT: 9-digit sub-second literal vs `.123` fixture — golden-vs-H2 skew |
| metamodel::execute::testConnectionEquality ×5 (AllButOnePropertySame, AllSameStatic, TypeDiff, TypeSameSpecDiff, TypeSpecSameAuthDiff) | PARKED 2026-09-05 (code-as-data leg) |

---

## 6. Totals

| label | tests | share of 168 |
|---|---|---|
| IMPL (real work) | 68 | 40% |
| TEXT (engine spelling is the contract) | 44 | 26% |
| ENGINE (engine's own machinery) | 32 | 19% |
| OTHER STORES | 8 | 5% |
| NAMED (receipts/defects/decisions) | 16 | 10% |
| **total** | **168** | |

Cross-check: 68 + 44 + 32 + 8 + 16 = 168 (every FQN of the flip-buckets file appears once; checked mechanically).

So "burn to zero" honestly means: **68 tests can become real verdicts**, in
sixteen legs. The other 100 are named for what they are; none of them is a
platform gap that a compiler for Pure-to-SQL should close, and the ledger
buckets already say so test by test.

## 7. Burn order (tests per design, biggest first)

1. **L1 navigation shapes (15)** — one resolver family: nested navigation
   inside exists/isEmpty (3), multi-hop through embedded heads (3), the
   filtered-navigation lift (2), class-typed property as a value (2), the
   depth-2 correlated predicate, the milestoned nested access, the
   behind-to-one aggregate.
2. **L8 natives and small typer legs (12)** — cheap, independent, each a
   half-day at most; iqr/zScore/groupByWithWindowSubset are engine tdsExtension
   PROGRAMS (admit + inline), not Java.
3. **L4 + L3 union/isolation/relation-mapping (10)** — real divergences (wrong
   rows) — the highest-value correctness work on the list.
4. **L13 m2m2r derived properties (5)**, **L5 model joins (4)**, **L6 graph
   fetch (4)**, **L7 post-processor passes (4)**.
5. **L2, L9, L11, L14 (10)**, then **L10, L12, L15, L16 (4)**.

Each leg lands as its own batch (ratchet moves, chain green, pins with
justification), per [[burn-fallbacks-every-batch]].
