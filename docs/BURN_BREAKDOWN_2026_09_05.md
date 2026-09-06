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

Status: **batch 87 / L1-L2 union heads LANDED (2026-09-06)** — testQualifierConcatenateTwoSimilarJoinsEmbedded and testConcatenateInQualifierWithComplexReturnType flipped (147/2426, with L2's testQualifierConcatenateTwoSimilarJoins): the "class-typed property as a whole value" wall in front of these two was really the engine's processConcatenate shape — a concatenate of navigation chains through different heads joins ONE union subselect (see L2). **batch 73 / L1a LANDED** — testQualifiedPropertyInQuery and testSubFilter
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
| concatenate::testQualifierConcatenateTwoSimilarJoinsEmbedded | **FLIPPED batch 87** — union head (#uN): the branch chains UNION ALL-ed with name-aligned null-padded keys, LEFT-joined on the OR of the branch conditions (engine processConcatenate); the embedded `oe` ctor drills inside the member | rows [1,'OE 1',2,'OE 2'] |
| concatenate::testConcatenateInQualifierWithComplexReturnType | **FLIPPED batch 87** — union head over `address` (navigate-slot route) and `firm.address` (association hop + slot); keys align BY NAME so both branches share `ID` exactly as the golden joins `unionalias_0.ID = root.FIRMID or … = root.ADDRESSID`; the assert's `sort(tds, $tds.columns.name)` folds to the legacy string-keyed sort | rows |
| enumeration::testEnumInRelation | class query under TypedPropertyAccess — a `~[...]` relation project whose columns read enum-mapped and class-typed properties | rows (csv) |

### L2 Resolver: project/extend column resolution and aggregation (3)

Status: **batch 87 / L1-L2 union heads LANDED (2026-09-06)** — testQualifierConcatenateTwoSimilarJoins flipped (147/2426) with the two L1 concatenate tests: a concatenate of navigation chains through DIFFERENT head properties lifts into ONE synthetic union head (`SyntheticHeads.liftUnionHead` → `#uN`; `UnionHeads.material` builds the engine's unionalias join: member = branch chain, keys aligned by name and null-padded, condition = OR of the branch conditions). The two aggregation tests stay open.

| test | wall |
|---|---|
| concatenate::testQualifierConcatenateTwoSimilarJoins | **FLIPPED batch 87** — the same union head; the `oe` navigate slot of each branch target rides the member's SubNav |
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

Status: **batch 79 / L4c LANDED (2026-09-06)** — testJoinIsolationDeeperTwoIsolations flipped (157/2416): an extra sub-slot identity materializes its own composite chain. **batch 78 / L4b LANDED (2026-09-06)** — testMultipleJoinsInPropertyMappingWithDatesInClass flipped (158/2415): reads over an executed instance frame range over the extent's rows. **batch 77 / L4a LANDED (2026-09-06)** — the Binder-error test flipped
(159/2414) by DEMAND: a navigation hop through a union projects its join keys
only (SubselectPrune star narrowing + the positional union prune). Probed the
same day (LL_TMP_DEBUG stacks, all nine L3/L4 items): the remaining walls are in
§8.5 with their stacks.

| test | detail |
|---|---|
| classMappingFilterWithInnerJoin::testChainedJoinsWithUnionsAndIsolationWithProjectionQueryTableFilter | **FLIPPED batch 77** — not alias scoping: the intermediate Firm hop projected `legalName` off a `FirmSet1` the session had seeded from ANOTHER package's DDL (merge/testMerge.pure: `FirmSet1(ID, LegalName)` vs union/testUnion.pure: `FirmSet1(id, name, NICKNAME)`); DuckDB spells the missing column as "Referenced table t5 not found". The engine projects a hop's join keys only; SubselectPrune now narrows a qualified star to the outer's reads, and the positional union prune follows |
| tree::testJoinIsolationDeeperTwoIsolations_LeftOuterLeftOuterThenInner | **FLIPPED batch 79** — the second filtered identity (`orgs#f1`) joined the first identity's FILTERED tree slot row; it now gets its own composite chain (org ⋈ tree on the oriented condition), the engine's per-qualifier subselect |
| join::testMultipleJoinsInPropertyMappingWithDatesInClass | **FLIPPED batch 78** — the six instances WERE there (assertSize passed); `$result.values.tableProperty` re-resolved the chain with the read's demand only (root table, 3 rows). A read over an executed instance frame now ranges over the extent's rows (TypedFrom.executedExtent → the implicit scalar tree's paths join the demand) |
| projection::filter::testChainedFiltersQuery | property 'locations' of the filter chain is not mapped (chained filters employees→locations) |
| union::testEnumFilterWithUnionMappingPlanGeneration | **RECLASSIFIED → TEXT (T2) 2026-09-06**: its one assert is `assertEquals($expected, $plan->planToStringWithoutFormatting(…))` (tests/mapping/union/testUnion.pure) — unformatted plan text, unreplayable by construction (the T2 precedent); the Subselect-alias plan wall is real but cannot flip it |
| union::testPksWithImportDataFlow | typer: multiplicity [*] vs [1] — `RelationalExecutionContext(importDataFlow=true, importDataFlowAddFks=true)` adds the union's pk/fk columns (`ID_0`, `ID_1`) to the projection; rows assert |

### L5 Cross-store model joins as relational joins (4)

| test | detail |
|---|---|
| graphFetch::crossDatabase::testCrossMappingWithRelOpWithJoinKeys | association not mapped — XStore association with join keys across two databases |
| modelJoins::testPersonToFirmUsingFromProject | association not mapped — asserts the XStore plan's SQL EQUALS the single-store plan's SQL (a semantic equality, not a spelling) |
| modelJoins::testPersonToFirmUsingProject | assert-free (zero-assert) — runs the same XStore shape |
| modelJoin::advanced::testNestedModelJoinCompoundInnerCondition | association not mapped — compound inner condition in a model join |

### L6 Graph fetch (4)

Status: **batch 80 / L6a LANDED (2026-09-06)** — testGraphFetchWithTableMapperPostProcessor flipped (156/2417).

| test | detail |
|---|---|
| graphFetch::simple::testCheckedWithCircularConstraints | DIVERGENCE: `graphFetchChecked` defects — constraint evaluation in the checked envelope (expected 1 defect, ours 0) |
| graphFetch::simple::testGraphFetchWithTableMapperPostProcessor | **FLIPPED batch 80** — the connection's MapperPostProcessor rides the tableReplace channel (SqlPostProcessors.hooks: exact-FQN TableNameMapper/SchemaNameMapper; other kinds loud); renames now reach aggregate arguments (the graph envelope's child subquery) and every execute a statement reaches through ordinary lets |
| graphFetch::union::propertyLevel::test6 | DIVERGENCE: `Firm B` vs `Firm X` — property-level union in graph fetch |
| query::function::concatenate::testAll | lowering not implemented for TypedSerializeGraph — `Product.all()->concatenate(Product.all())` as instances |

### L7 Post-processors as compiler passes (4)

Status: **batch 81 / L7a LANDED (2026-09-06)** — testNonExecutableSQLString flipped (155/2418; text-only lane 13 → 12). The relationalMapper pair asserts a PLAN NODE's sqlQuery text over foreign schema names (`snDBDefault.default.*`, no such schema in any session) — text unless a referee creates the schema (L15's idea); testPostProcessTransformJoinOp is TEXT behind its wall.

| test | detail |
|---|---|
| alloy::connections::relationalMapper::testRelationalMapperWithJoin | **RECLASSIFIED → TEXT (T3) 2026-09-06**: `assertEquals('select … from snDBDefault.default.firmTableNew as "root" …', $resultSQL)` where `$resultSQL` is a plan node's sqlQuery (testRelationalMapper.pure:66-78 relationalMapperSqlQuery) — CATALOG-qualified 3-part names; H2 has no user catalogs, no session can execute the golden, text is the contract |
| alloy::connections::relationalMapper::testRelationalMapperTwoDBs | **RECLASSIFIED → TEXT (T3) 2026-09-06**: same helper, `snDB.productSchemaNewDBINC.productTableNewINC` (catalog.schema.table) |
| sqlstring::testNonExecutableSQLString | **FLIPPED batch 81** — a fourth toSQLString-family native on the one K-routine; the nonExecutable IR pass renders and the rows leg runs under it |
| postProcessor::testPostProcessTransformJoinOp | a connection `sqlQueryPostProcessors` lambda over the SQL AST — the ONE post-processor test that is a user-supplied pass; text assert behind it (TEXT) |

### L8 Natives and small typer legs (12)

Status: **batch 88 / L8 LANDED (2026-09-06)** — stringToFloat::testProject and strictdate::testProject flipped (145/2428): both were assert-side shapes (a forAll over an assert body; a bare sort() over flat cells). **batch 74 / L8a LANDED** — testToSQLStringWithCodeBlock (the engine's
`add(Date, Duration)` programs admitted verbatim) and testFirstNotNull (generic
instantiation at the inlining seam; bare TDSNull as a list element = the null-cell
value; element-reference / null-carrier equality folds) flipped: 164/2409.
**batch 75 / L8b LANDED (2026-09-06)** — testViewChainsWithBusinessDate flipped
(163/2410; text-only lane 14 → 13): `toSQL(f, mapping, runtime, ext)` is the
SQLResult HANDLE of the toSQLString doctrine (the plan handle's twin) and the
qualified property `SQLResult.toSQLString(dbType, tz, quote, format)` is the
5-argument function form of toSQLString (real pure desugars `$r.toSQLString(…)`
so); one K-routine renders both (SqlTextInputs reads the lambda / mapping /
runtime off the handle; the dialect = the connection's `type`, read through the
lets and inlined user calls). The Prelude generator admitted SQLResult and
Format on platform demand. The assert is a rows verdict (text diverged,
golden replayed on H2, rows agreed).
**batch 76 / L8c LANDED (2026-09-06)** — iqrClassifyTest, zScoreTest,
testExtendDigest_InMemory flipped (160/2413): the in-memory TDS from collection
natives. (1) A CLASS-typed collection VALUE in relation position
(`range(n)->map(i|…)->zip($scores)` — a Pair list the DATABASE computes:
list_zip over list_transform over range; `range(n)` is NOT a compile-time fold,
the unroll compares and never computes) is the relation of its elements'
LAYOUT fields, UNNEST in list order (CollectionRelations.explode; the flatten
arm moved there at the Lowerer's file guardrail), so `project([col(p|$p.first,
'name'), …])` reads the columns like a store row. (2) StaticFold's schema
vocabulary gains `zip` — the engine's iqrClassify/zScore programs spell
`$cols->zip($outputCols)->map(colPair|…col(…, $colPair.second))`, and the
computed column names must fold before typing. The digest golden is pure's own
md5('student_0|1') (the in-memory engine's joinStrings is correct; only its
relational renderer is the registered defect).
Re-sized after reading the walls: iqrClassify / zScore / extendDigest_InMemory
are NOT small — a VALUES relation from `range()->map()->zip()` (collection
natives in relation position), one leg for the three; rowValueDifference needs
`.columns` typed as TDSColumn instances (today `.columns.name` folds only as a
direct read); testViewChainsWithBusinessDate needs the `toSQL(...)` →
`SQLResult.toSQLString(dbType, tz, quote, format)` typing surface routed onto the
toSQLString doctrine (StatementExecutor.toSqlString reads lambda/mapping/dbType);
stringToFloat::testProject's wall is `assertEqWithinTolerance` inside a
`forAll` over a zip of the expected literal list and the result rows (a verdict
form to add); testSortQuotes is Postgres text (TEXT, moved conceptually to T3);
testSimpleTypeMappingProjectNulls #2 is `toJSON` of a TDS (the engine's column/
row JSON envelope — a golden-to-rows referee arm).

| test | detail |
|---|---|
| sqlFunction::stringToFloat::testProject | **FLIPPED batch 88** — the mapping's `parseFloat(col)` already lowered (`CAST(.. AS DOUBLE)`); the wall was the assert: `zip(literals, rows.values)->forAll(pair \| assertEqWithinTolerance(...))` now unrolls like the quantified map form (VerdictQueries.forAllAsQuantified / unrollElements) |
| dataType::testSimpleTypeMappingProjectNulls | no scalar lowering for tinyInt/smallInt column functions |
| mapping::dates::strictdate::testProject | **FLIPPED batch 88** — the assert's `rows.values->sort()` over a mixed Integer/StrictDate cell pool is the cell-multiset judgment (AssertVerdicts.bareSortOverCells → tdsRowValuesSameElements), never a SQL column sort |
| tds::extensions::testFirstNotNull | unresolved type variable T at the lowering boundary |
| tds::extensions::iqrClassifyTest | **FLIPPED batch 76** — collection value in relation position + StaticFold zip |
| tds::extensions::zScoreTest | **FLIPPED batch 76** — collection value in relation position + StaticFold zip |
| tds::extensions::rowValueDifferenceTest | typer: cannot access 'name' on String (a column-name read on a TDSColumn collection) |
| tds::extensions::testExtendDigest_InMemory | **FLIPPED batch 76** — collection value in relation position + StaticFold zip |
| projection::testGroupByWithWindowSubset | `groupByWithWindowSubset` unknown — the engine's TDS extension program (admit/inline) |
| sqlstring::testToSQLStringWithCodeBlock | typer: a `#/Trade/date#` path argument typed Any where Date is expected |
| businessdate::testViewChainsWithBusinessDate | **FLIPPED batch 75** — toSQL handle + SQLResult.toSQLString function form; rows verdict |
| lineage::scanRelations::testTdsJoinConcatenateAndJoin | typer: TDS concatenate of 7 vs N columns (the engine accepts the shape) — lineage rows verdict behind |

### L9 Lineage row verdicts (2)

| test | detail |
|---|---|
| lineage::scanColumns::testNonDataTypeProperty | class query under TypedMap (H2 vocabulary) — scanColumns over a project with a class-typed column |
| lineage::scanRelations::testTableToTdsWithCrossJoin | no SQL type for TableAlias at the lowering boundary — `tableToTDS(tableReference(...))` join (the same store-row leg as batch 55) |

### L10 Time zone and temp tables (1)

Status: **batch 86 / L10 LANDED (2026-09-06)** — testInExecutionWithTempTableForDateTimesWithTz flipped (150/2423).

| test | detail |
|---|---|
| in::tempTable::testInExecutionWithTempTableForDateTimesWithTz | **FLIPPED batch 86** — the connection time zone spells time-bearing DateTime literals at the same instant in that zone (Lowerer.withDbTimeZone / MatchFold.dateLit; the referee's in-list temp table too) |

### L11 Views (2)

| test | detail |
|---|---|
| relation::testRelationStoreAccessorOnView | **RECLASSIFIED → TEXT (T1) 2026-09-06**: its first assert is `assert($result->contains('"sql":"select \\"personview_0\\".ID as \\"ID\\", … from (select \\"root\\".ID as ID … from personTable as \\"root\\") as \\"personview_0\\""'))` (tests/mapping/relation/tests.pure — `contains` over the engine's `personview_0` alias spelling of OUR SQL); the view-expansion wall on the typed accessor path is real work that cannot flip the test — the second assert (rows) would then pass. Counted with T1 |
| testDataGeneration::alloy::testAlloyTestDatGenWithQuotedColumnsForViews | **RECLASSIFIED → TEXT (T2) 2026-09-06**: the test says so itself — `// Purposefully asserting on plan string to assert we add quotes in join columns` then `assertEquals('MultiResultSequence\n(\n  type = …', …)` (testDataGeneration/tests/testDataGeneration.pure); the view-slice TDG wall is real but cannot flip it |

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
| executionPlan::testModelConnectionJoin | **RECLASSIFIED → TEXT (T2) 2026-09-06**: `assertEquals($expected, $res->planToString(…))` over a ModelChainConnection plan (executionPlan/tests/executionPlanTest.pure) — Class/M2M nodes, not a statement the oracle replays; the chain wall is real but cannot flip it |
| executionPlan::testModelConnectionDeepFunction | **RECLASSIFIED → TEXT (T2) 2026-09-06**: same assert form, deep chain |

The m2m2r family already runs in the corpus (core/store/m2m/tests); these five
are the derived-property and chain shapes it does not take yet.

### L14 Raw SQL to TDS and CSV load (3)

Status: **batch 85 / L14b LANDED (2026-09-06)** — testLoadCsv flipped (151/2422); **batch 82 / L14a LANDED (2026-09-06)** — testExecuteInDbToTDS flipped (154/2419). relationalResultSourcingOfListExecutionPlan: its assert is plan TEXT behind the executeInDb-binding wall.

| test | detail |
|---|---|
| metamodel::execute::testExecuteInDbToTDS | **FLIPPED batch 82** — the raw grid typed TDS (Typer.rawGridOrSelf); a late-bound inner's toCSV defers to the boundary (DeferredTdsString.Form.CSV) |
| advanced::resultSourcing::relationalResultSourcingOfListExecutionPlan | **RECLASSIFIED → TEXT (T2) 2026-09-06**: `assertEquals($expectedPlan, $result->planToStringWithoutFormatting(…))` (tests/advanced/testRelationalResultSourcing.pure) — unformatted plan text; the executeInDb-binding read is real work that cannot flip it |
| loadCsv::testLoadCsv | **FLIPPED batch 85** — an EFFECT native: the CSV is test input the harness resolves (exec.TestResources), header dropped, positional INSERTs (CsvLoad) |

### L15 Referee legs (goldens a referee CAN bring to rows) (1)

| test | detail |
|---|---|
| executionPlan::testQuoteIdentifiersFlagWithGraphFetch | oracle declined: Schema "productSchema" not found — the QUOTED schema name; the referee must create the quoted schema (6 goldens in the census hit this) |

### L16 Plumbing (1)

Status: **batch 83 / L16 LANDED (2026-09-06)** — testSQLComments flipped (153/2420).

| test | detail |
|---|---|
| query::simple::testSQLComments | **FLIPPED batch 83** — the executed statement carries the engine's `-- "executionTraceID" : "<uuid>"` comment (exec.ExecutionTrace at the JDBC boundary) and the activity records the comment of its own run |

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

**Running IMPL count** (flips and reclassification receipts, from the Status
lines): 68 → batch 73 −2 (66) → batch 74 −2 (64) → batch 75 −1 (63) → batch 76 −3 (60) → batch 77 −1 (59) → batch 78 −1 (58) → batch 79 −1 (57) → batch 80 −1 (56) → batch 81 −1 (55) → testRelationStoreAccessorOnView reclassified TEXT −1 (54) → batch 82 −1 (53) → batch 83 −1 (52) → batch 84 −1 (51) → batch 85 −1 (50) → seven plan-text / catalog-name reclassifications (T2: testEnumFilterWithUnionMappingPlanGeneration, relationalResultSourcingOfListExecutionPlan, testModelConnectionJoin, testModelConnectionDeepFunction, testAlloyTestDatGenWithQuotedColumnsForViews; T3: testRelationalMapperWithJoin, testRelationalMapperTwoDBs) −7 (43) → batch 86 −1 (42) → batch 87 −3 (39) → batch 88 −2 → **37**.

Rule applied for the reclassifications (2026-09-06): a test whose ONLY assert compares engine PLAN TEXT (`planToString` / `planToStringWithoutFormatting`) or SQL text no session can execute (catalog-qualified names) can never leave IMPL by flipping, whatever wall stands in front of it — the wall is real work the flip cannot pay for; each row names the assert and its file. A test whose text assert reads a REPLAYABLE producer (execute()/toSQL/toSQLString) stays IMPL: the sql-text arm brings the golden to rows (batches 75, 81).

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

## 8. Homework notes per open item (2026-09-05/06 — what the probes and source reads established; never re-derive)

Conventions: "wall @" names the throw site; "owner" names the code that must
change; engine paths are under core_relational/relational (REL) unless said.

### 8.1 L1 — nested exists scope, class-typed slot mapped to TWO subtype sets (testExistsAsNullWithSubType)

- Test: `MyClass.all()->project([p|$p.hasPrivateFunction, p|$p.hasPublicFunction])`;
  `hasPrivateFunction(){$this.functions->exists(f|$f.fnScope->subType(@Public).id->isNotEmpty())}`
  (REL/functions/tests/projection/testExists.pure:23-31). Mapping
  `mappingForMultipleSubTypes` (:257): `MyClass.functions[map1]: @classFnJoin`;
  `ClassFunction[map1].fnScope[map2]: @privateFnJoin`, `fnScope[map3]: @publicFnJoin`;
  `Private[map2].id: privateFn.id`, `Public[map3].id: publicFn.id`. Tables
  main/fn/privateFn/publicFn; joins classFnJoin(main.id=fn.classId),
  privateFnJoin(privateFn.fnId=fn.id), publicFnJoin(publicFn.fnId=fn.id).
- Engine expected SQL (:122): `select "fn_0".classId is not null as "hasPrivateFn",
  "fn_2".classId is not null as "hasPublicFn" from main as "root" left outer join
  (select distinct "fn_1".classId from fn as "fn_1" left outer join publicFn as
  "publicfn_0" on ("publicfn_0".fnId = "fn_1".id) where "publicfn_0".id is not null)
  as "fn_0" on ("root".id = "fn_0".classId) left outer join (…privateFn…) as "fn_2"
  on (…)` — exists = a DISTINCT semi-join subselect per derived property; the
  subtype pick = the member set's join with an `is not null` witness.
- Wall @ Substitution.assocLeaf (the SECOND nested throw: `leafBinding == null`),
  reached from rewritePath's navigate-slot arm (`target.assocs().containsKey(head)`
  is TRUE → the head `fnScope` IS registered in the nested scope, but the leaf
  `stc_meta__relational__tests__projection__exists__Public___id` is not in its
  `targetBindings()`): the nested registration served ONE set's bindings for the
  class-typed slot, not the union of the subtype member sets with their
  `stc_<Sub>___<prop>` witnesses. Stack: rewriteExists → predSub.rewriteLambda →
  rewritePath:~2390 → assocLeaf.
- Owner: StoreResolver.nestedScope/scopeMaterials → registerExistsSubs /
  CorrelatedSubselects.nestedAssocMaterials for the exists TARGET (ClassFunction[map1]);
  the root scope's multi-set slot machinery (SyntheticHeads `#cN` CONCAT heads: a
  property mapped to several sets branches and UNION-ALLs — SyntheticHeads.applyToPipe)
  is what the nested scope lacks for `fnScope[map2]/[map3]`. Design: inside a nested
  scope a class-typed slot with N set mappings registers the UNION of the member
  sets' bindings under `stc_<Sub>___` keys (as the root does), joined per member.

### 8.2 L1 — the three multi-hop-through-embedded tests (two designs)

Probe diagnostics (LEGEND_LITE_STACKS=1, `[multi-hop wall] path=… targetBindingKeys=…`):

- testToManyWithQualifierWithFilterOnJoin: path
  `[account, incomeFunctionSplits#f0, incomeFunction, Classification, name]`,
  head `account` registered (targetBindingKeys=[number, incomeFunctionSplits]).
  Query: `Position.all()->filter(p | $p.account.incomeFunctionSplits->filter(i |
  $i.type == 'P')->toOne().incomeFunction.Classification.name == 'IfName1')->project(quantity)`
  (REL/tests/mapping/multigrain/testMultiGrainTableMappings.pure:78). Mapping
  `testMappingFirmAccount` (:347): `Position.account: @posAccount`;
  `FirmAccount ~filter accountGrain, incomeFunctionSplits: @account_accountIFSplit`;
  `AccountIncomeFunctionSplit ~filter accountIFGrain, type: IF_TYPE, incomeFunction (
  code: IF_NUM, Classification: @ifClass )` — an EMBEDDED property mapping carrying a
  JOIN-mapped property; `Classification.name: IF_OTHER_INFO.IF_NAME`. Store
  myDBAccount: POSITION, FIRM_ACCT_IF_MULTIGRAIN (multi-grain rows filtered per set),
  IF_OTHER_INFO; joins posAccount, account_accountIFSplit, ifSplit_if, ifClass.
  Shape: a FILTERED to-many hop (`#f0`, `->toOne()`) in the middle of a 4-hop chain,
  then an embedded ctor whose tail property is a join. `rewriteMultiHop` (Substitution
  ~1219-1350) has arms for: navigate-slot SubNav trees (`a3.subNavs()`), embedded-ctor
  tails under a SubNav (`ctorTailLeaf`), chain keys (`target.assocs().containsKey(chainKey)`),
  nested embedded ctors walked from bindings, "HEAD-JOIN + EMBEDDED TAIL", and the
  "SUBTYPE-EMBEDDED tail" flat column — none composes a filtered hop's SubNav with an
  embedded+join tail. Owner: AssociationJoins.associationJoin's `navTails`/`tailSubNavs`
  (NavMaterializer.navTargetMaterialized composes deeper prefixes) + the synthetic
  `#f` head's SubNav registration.
- testRoutingWithSubtypePropagation: path `[employees, stc_…PersonExtension___manager,
  stc_…PersonExtension___firstName]`; targetBindingKeys already carry every
  `stc_…PersonExtension___<prop>` INCLUDING manager. Query: `Firm.all()->project(col(x|
  $x.employees->subType(@PersonExtension).manager->subType(@PersonExtension).firstName…))`
  (REL/router/tests/testRouting.pure). Model: `PersonExtension extends Person`
  (simpleTestModel.pure:230); mapping `PersonExtension: Relational { scope([dbInc])
  (firstName, age), lastName, firm: @Firm_Person, address: @Address_Person, locations:
  @Person_Location, manager: @Person_Manager }` (relationalSetUp.pure:1139). Shape: a
  subtype-cast leaf that is a JOIN slot (`manager`), then a further cast + leaf. The
  assert is SQL-text only (TEXT behind).
- testInheritanceMultipleLevel (TDG): path `[vehicles#f1, stc_…Bicycle___person, name]`;
  targetBindingKeys hold the union's flat columns (`stc_…Bicycle___id`, `…___owner__name`
  — an INLINE embedded owner(name:'Unknown') distributes as a flat column) but no
  `person` slot: mapping `inheritanceMain` (REL/tests/mapping/inheritance/
  testInheritanceRelational.pure): `Person.vehicles[map1]: @PersonCar, vehicles[map2]:
  @PersonBicycle`; `Bicycle[map2].person: @PersonBicycle`, `Car[map1].person: @PersonCar`.
  Shape: a join slot on a UNION MEMBER behind the subtype witness.
- Design (shared by the last two): "join slots behind subtype witnesses" — a member
  set's navigate step materializes and its columns distribute through the union under
  the `stc_<Sub>___<joinProp>_` prefix, with a SubNav registered under the `stc_` key so
  rewriteMultiHop's SubNav walk continues; the cast chain re-enters the same rule.

### 8.3 L1 — the rest, walls and owners

- isolationTest: `correlated filter predicate on hop '_' at depth 2 of employees.group.children.name has no application site` — StoreResolver.unappliedCorrelatedWall (batch 69b); leg = apply the parked predicate at depth ≥ 2 and reroute an already-claimed alias (memory harness-burndown-program).
- testProjectThroughAssociation / testForcedSubTypeProjectDirect: `filtered-navigation read reached substitution unlifted — the router owns this shape (batches 69+)`; SubQueryLift/SyntheticHeads.liftValueRead pre-pass misses the injection mapping's `trades->map(t|$t.productAtTimeOfTrade.name)` and the `->subType(@Bicycle).person.name` project column.
- testProjectThroughAssociationAutoMap: `object-space expression node TypedFilter is not substitutable yet` — `$b.trades.productAtTimeOfTrade.name` auto-map with a filter in object space (Substitution).
- testFilterTimesWithManyOperands: `aggregate over the navigation firm.employees.age whose to-many hop sits BEHIND a to-one` — `$p.firm->toOne().sumEmployeesAge()` (qualifier aggregating a to-many under a to-one hop); owner CorrelatedSubselects aggregated subselect through a to-one hop.
- testQualifierConcatenateTwoSimilarJoinsEmbedded / testConcatenateInQualifierWithComplexReturnType: `class-typed property … used as a whole value is graph output (Phase H)` — `$t.accountOrganizationalEntity.name` where the qualifier concatenates two class-typed navigations (`->concatenate` of instances) then reads `.name`; owner: the concatenate-of-instances union (engine `unionalias_0`) + leaf read (Substitution rewritePath/assocLeaf).
- testBusinessDateInjectionFromVarReferenceInProjectUsingExternalFunction: `milestoned property access on a NESTED navigation is not supported yet` — `filterOrders($o)` external function over `Order` with milestoned nested navigation; owner TemporalFrame nested navigation dates.
- testEnumInRelation: `class query under TypedPropertyAccess is not resolvable yet` — `~[name: x|$x.name, …, firm: x|$x.firm, role: x|$x.role]` relation project whose columns read enum-mapped properties (`employeeTestMapping` EnumerationMappings) — the wall is the TypedPropertyAccess over the class row inside the `~[...]` project (StoreResolver.resolveObject vocabulary).

### 8.4 L8 — sizes, with the reads behind them

- iqrClassifyTest / zScoreTest / testExtendDigest_InMemory: `let data = range($scores->size())->map(i|'student_'+toString($i))->zip($scores); let tds = $data->project([col(p|$p.first,'name'), col(p|$p.second,'score')]);` (REL/tds/tests/testTdsExtension.pure). Typer wall "no overload of 'col' matches 2 argument(s) (no candidates at all)": `meta::pure::tds` IS in NameResolver.CORE_IMPORTS; `col` has no checker arm for a project whose SOURCE is a collection of Pairs (not a class/TDS). `zip`/`range` in relation position: `lowering not yet implemented for TypedNativeCall ('zip' in relation position)`. Leg = a VALUES relation from collection natives (range/map/zip over literals is computation, so it must be SQL: `range(n)` + list ops or UNNEST), then `project(col…)` over it, then the engine's tdsExtension programs iqrClassify/zScore (define in REL/tds — admit as programs; extendWithDigestOnColumns is an engine program too).
- rowValueDifferenceTest: wall `cannot access 'name' on String` at `$rawTradeDate.columns->map(c|$c.name + ':' + $c.type->toOne()->elementToPath())` — Typer.tdsColumnsMetaRead folds ONLY the direct forms `.columns.name` / `.columns.type` / `.columns.documentation` (Typer ~2782); a bare `.columns` types as names (String[*]). Leg = `.columns` as a spelled collection of `^TDSColumn(name=…, type=…)` literals so `map` unrolls and `$c.name`/`$c.type` fold; `elementToPath` over a type reference then needs a fold. The assert itself calls the engine's `rowValueDifference` tdsExtension program — unverified whether it lowers.
- testViewChainsWithBusinessDate: `toSQL(|query, ViewChainMapping, testRuntime(), extensions).toSQLString($connection.type, $connection.timeZone, $connection.quoteIdentifiers, ^Format(newLine='', indent=''))` then assertSameSQL (REL/milestoning/tests/testBusinessDateMilestoning.pure:243-246). Engine: `toSQL(f, mapping, runtime, ext): SQLResult` (sqlstring.pure:46); `SQLResult.toSQLString(dbType, tz, quote, format)` qualified property (:151) maps each SQLQuery through sqlQueryToString. Ours: no `toSQL` native; toSQLString natives are `TO_SQL_STRING__FN_1__ANY_1__ANY_1__ANY_MANY` (Pure.java:1605); the harness routes sql-text asserts by `SQL_PRODUCER_FQNS` (EngineTestExecutor:3145: sql, sqlRemoveFormatting, toSQLString, toSQLStringPretty, TDG sqls) and renders ours in StatementExecutor.toSqlString (:491 — arg0 lambda literal, arg1 mapping reference, arg2 DatabaseType enum or a connection → EngineStyleH2/DB2/Composite renderer). Leg = a typing surface `toSQL(...)` → an SQLResult carrier + `toSQLString` over it, routed onto the same doctrine (dbType from the connection's `type`; tz/quote/format honored or defaulted), and `toSQL` added to SQL_PRODUCER_FQNS.
- stringToFloat::testProject: wall `no scalar lowering registered for resolved overload 'assertEqWithinTolerance' with 3 parameter(s)` — the assert sits INSIDE `[123.456, 100.001]->zip($result…rows.values)->forAll(pair | assertEqWithinTolerance($pair.first->cast(@Float), $pair.second->cast(@Float), 0.001))`; the harness has the direct form (EngineTestExecutor:2671) but not forAll-over-zip. Leg = a verdict form: unroll the zip against the literal expected list into per-row tolerance asserts (AssertVerdicts).
- testSimpleTypeMappingProjectNulls #2: `meta::json::toJSON` over the TDS — the engine's envelope `{"columns":[{"name":"ti","type":"Integer","metaType":"PrimitiveType"},…],"rows":[{"values":[…]}]}`; assert #1 (TDSNull row values) passes. Leg = a referee arm: parse the golden envelope to rows, compare rows (golden-to-rows rule), never byte-compare JSON text.
- testSortQuotes: Postgres `toSQLString` text → TEXT (T3), not L8.
- testTdsJoinConcatenateAndJoin (lineage): typer `concatenate: 7 column(s) [First_1, Age_1, First_2, Age_2, First_3, Age_3, Restated] cannot unite with N column(s)` — the lineage query joins three projections then concatenates a differently-shaped TDS; batch 72a made concatenate POSITIONAL on names of the left — the counts differ here; read the test (REL/lineage/scanRelations/scanRelationsTests.pure) before deciding whether the engine accepts mismatched widths (it is a scanRelations lineage test, rows verdict = the lineage tree, batch 59).
- testGroupByWithWindowSubset: `groupByWithWindowSubset` unknown — an engine PROGRAM (core/pure/tds/tds.pure:867: filters/sorts the agg and function lists by ids, then calls `meta::pure::tds::groupBy`) — admit verbatim like the others; its body uses `indexOf`, `contains`, `sort` with compare lambdas over spelled lists (folds needed: indexOf/contains over spelled lists exist? check LiteralUnroll before promising).
- testSimpleTypeMappingProjectNulls / strictdate::testProject `TypedNativeCall ('sort' in relation position)`: `assertEquals([...], $result.values.rows.values->sort())`? — read the exact assert before sizing.

### 8.5 L4/L3 — the wrong-row divergences (read, not yet probed)

- testChainedJoinsWithUnionsAndIsolationWithProjectionQueryTableFilter: our SQL fails to BIND (`Referenced table "t5" not found! Candidate tables: "t4" LINE 16: SELECT t5.name AS legalName, t5.ID AS ID_0, NULL AS ID_1 …`) — an alias scoping bug when a union member's isolated subselect references an outer alias; mapping `chainedJoinsWithUnionsAndIsolation`, query `Person.all()->project([p|$p.firm.employees->filter(p|$p.lastName->startsWith('Sc')).lastName])`; expected rows ['Scott','Scott','null'].
- testJoinIsolationDeeperTwoIsolations_LeftOuterLeftOuterThenInner: rows [11,'Alex','OrgName3',[]] vs golden [11,'Alex','OrgName3','OrgName2'] — the 4th column `$a.trades.trader.orgByName('BUSINESS UNIT').name` (a qualifier with a filter through the org self-join tree, mapping `orgTestMapping`, REL/tests/mapping/tree) comes back empty; the first three columns agree.
- testMultipleJoinsInPropertyMappingWithDatesInClass: 3 rows vs 6 — `TypeBuiltOutOfMultipleJoinsWithDates.all()` over `advancedRelationalMapping3` (a property mapped through MULTIPLE joins with date columns in the class: both "old" and current versions must survive); ours collapses to one version per row.
- testSimpleMappingQueryWithFilterInProject: `#TDS name1,name2 / Fabrice,Oliver` vs golden `Fabrice,null` — `~[name1:x|$x.firstName, name2:x|$x.firm.employees->filter(e|$e.age < 35).firstName]` over the RELATION mapping `SimpleMapping` (`~func` relation functions, REL/tests/mapping/relation): the filtered to-many navigation inside a relation project pairs rows wrongly (Fabrice gets Oliver) — compare with the L1a fix (the same shape over a class mapping passes since batch 73).
- testMixedMappingWithFilterInProject: `a navigation join over this union demands key column '_' which NO union member carries` — same query over `MixedMapping` (relation + relational sets).
- testUnionTwoRelationMappings_ManyColumnProject(+GeneratesSingleUnion): 12-column `distinct` project over `unionOfTwoRelationMappingsFirstAndLast` — rows `Anand,null,Anand,null,…` vs ours (read the ledger row for the exact actual); the golden repeats lastName/firstName alternately.
- testChainedFiltersQuery: `property 'locations' of class … is not mapped` — `Firm.all()->filter(f|$f.employees->filter(e|$e.lastName=='Smith').locations->filter(o|$o.place=='Hoboken').place != 'New York')` — a filter chain through two to-many hops with filters on each (the chained-filter navigation), simpleRelationalMapping.
- testPksWithImportDataFlow: typer `multiplicity [*] is not compatible with [1]` — `^RelationalExecutionContext(importDataFlow=true, importDataFlowAddFks=true)` passed to execute (5-arg execute overload with a context); the engine adds `ID_0`/`ID_1` pk columns to the union projection; ours types the context argument wrong before anything else.

### 8.6 L5/L6/L7/L13 — what was read

- Model joins (L5): mappings spell `Person_Address: ModelJoin { {person, address | …} }` inside the mapping (REL/tests/mapping/modelJoin/modelJoinAdvancedSetup.pure:39-75, 529-565) over `~func` relation-function sets; the three failing tests are XStore/cross-database shapes (`association not mapped` in AssociationJoins.associationJoin's `findAssociationOf`/binding closure) — the ModelJoin's association mapping lives in a mapping the closure does not see (cross mapping / two databases).
- Graph fetch (L6): testCheckedWithCircularConstraints expects `defects` on the Checked envelope for a constraint over `meta::pure::executionPlan::constraints::tests::Person` (`graphFetchChecked`); testGraphFetchWithTableMapperPostProcessor's runtime carries `postProcessors = ^MapperPostProcessor(mappers = ^TableNameMapper(schema = ^SchemaNameMapper(from='default', to='default'), from='personTable', to=…))` — a connection-level table rename the compiler must apply as an IR pass (memory: post-processors are compiler passes); test6 = property-level union mapping `Mapping6` (REL/graphFetch/tests/union).
- Post-processors (L7): relationalMapper tests use `snDBDefault.default.firmTableNew`-style renames on the connection; testNonExecutableSQLString = `toNonExecutableSQLString` (typer: toSQLString 8-arg overload with `sqlQueryPostProcessors` — sqlstring.pure:88-93); testPostProcessTransformJoinOp sets `sqlQueryPostProcessors` on a TestDatabaseConnection (a user lambda over the SQL AST — the ONE that is kind-A-shaped; its assert is text).
- m2m2r (L13): `getM2M2RRuntime()` chains `ModelToModelMapping` over the relational mapping; the plan tests print `StoreMappingGlobalGraphFetch` plan text (TEXT behind the wall); executeProjectWithNestedDerivedProperty needs `meta::json::tdsToJSONKeyValueObjectString` (a JSON native) after the chain.

### 8.7 Cross-cutting lessons (for whoever burns next)

- Probe with `LL_TMP_DEBUG=1` (walls with stacks as `[flip-wall-debug]`/`[flip-fail-debug]`), `LEGEND_LITE_STACKS=1` (the multi-hop diagnostics), `LL_DUMP_RESOLVED=1` (the resolved typed body per statement — how the TDSNull carrier was found to be `sqlNull()`); the corpus runner's `-Drcorpus.test=<name>` scopes to one test (~1 min), the full corpus is ~65 s.
- Size pins: CodeShapeGuardrail 250 lines/method and 3500 lines/file (Typer, StoreResolver, Substitution, Scalars at the limit; AssociationJoins.associationJoin and LiteralUnroll.nativeFold now split); LiteralUnrollLedgerTest pins the fold NAME set (`is(c, "<name>")` occurrences); the runner pins fallbacks/flipped and the lanes (`assert-sql-text-only` etc.) — every move with a comment.
- Verify a patch applied (assert in the script, then `git diff --stat`) BEFORE launching a chain; a chain is 6–9 minutes.
