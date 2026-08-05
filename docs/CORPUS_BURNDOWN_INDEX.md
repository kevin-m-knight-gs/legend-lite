# Burn-down index — every cause group, by family (2026-08-05)

> **New here? Start with [`CORPUS_BURNDOWN_HANDOFF.md`](CORPUS_BURNDOWN_HANDOFF.md).**

Companion to [`CORPUS_STUDY_2026_08_ALL.md`](CORPUS_STUDY_2026_08_ALL.md) (synthesis) and
[`ARCHITECTURE_AUDIT_2026_08.md`](ARCHITECTURE_AUDIT_2026_08.md) (design).

**This is the working index.** One line per cause group: fix site, member tests,
confidence. Scoped by family so you can run one `-Drcorpus.only=` at a time.

**Before touching any row, read §0 of the synthesis** — several families are not
reproducible without `-Drcorpus.includeExcluded`, and several rows are not defects.

Legend: **⚠** silent wrong answer · **✗G** golden is wrong, do not chase ·
**H** harness, not engine · **B** backend artifact · **D** by design

---

## functions/tests (52 rows across 3 batches)

| cause | fix site | tests | conf |
|---|---|---|---|
| Unported platform math library (`distanceHaversineDegrees`, `distanceSphericalLawOfCosinesDegrees` + 5 helpers) — port from engine `core/pure/corefunctions/mathExtension.pure:15-47`; all primitives already registered | `builtin/Pure.java` (or shared-source, cf. `firstNotNull` at `RelationalCorpusRunner.java:69-74`) | 8 × `testDistance*` | high |
| Class-collection `concatenate` lowers to `list_concat` over JSON carriers instead of UNION ALL | `compiler/spec/ConcatenateChecker.java:21-25`; a top-level analogue of `StoreResolver.java:355-368` is missing | `testAll`, `testConcatenateFlatWithOtherProperty` | high |
| Cross-head class `concatenate` in a qualified property refused | `resolver/SyntheticHeads.java:441-448` (to-one leaf gate) + `:710-718` (cross-head refusal) | `testConcatenateInQualifierWithComplexReturnType`, `testQualifierConcatenateTwoSimilarJoins(+Embedded)`, `…DifferentJoinPaths` | high |
| `TDSRow.values->at(k)` indexes rows, not cells | `compiler/spec/Typer.java:2277-2311` (gate is `source instanceof TypedVariable`) | `testDupsFilterProject` | high |
| **H** `collection->map(x\|assert(...))` not an assert form; also `TestBody.java:545-553` discards already-verified asserts on downgrade | `harness/TestBody.java` near `:494-496` | `testComplexOrExistsToManyProperty` | high |
| `FunctionType` annotation unsupported + `containsEffect` full-compiles every callee body to answer "is it effectful?" | `Typer.java:2636-2639`; `StatementExecutor.java:2905-2920` | `testExistsWithEmbeddedWithPostProcessor` | high |
| **✗G** ToFix golden aspirational (passing sibling asserts 21 rows on same shape) | — | `testConcatenateClassJoinMerge` | high |
| `orderedDedup` inlines the receiver twice inside a SQL lambda; DuckDB forbids subqueries there | `lowering/Scalars.java:2428-2436` (rule at `:1358`); widen `ValueCollectionOps.java:63-72` | `testAssociationWithProjectionHandlingDups`, `testCollectionDistinctFunction` | high |
| `registerExistsSubs` covers only 1-hop heads (or filter-level 2-hop) — nested exists, and to-many **data-type** properties, leak to list-space | `resolver/StoreResolver.java:2113-2288`, gate at **`:2121`** | `testNestedExistsWithExistsInAbstractProperty`, `testExistsForDataType` | high |
| Missing `assert(Boolean[1], Function<{->String[1]}>[1])` — **135 corpus call sites** | `builtin/Pure.java:1751-1752` | `testFilterFunctionExpressionWithConditionOnRightTableExistsExpression`, `testFilterAfterFilterWithNestedExists`, +3 in `advanced` | high |
| **✗G** ToFix golden encodes the engine's dropped-filter bug (its own comment says so) | — | `testFilterAfterFilterFunctionExpressionWithAndConditionOnRightTableIsEmptyExpression` | high |
| Stale `chainContext` in `collectOpChain` — in-chain `TypedFrom` updates `context` but not `chainContext`, which feeds dispatch | `resolver/StoreResolver.java:2578`/`:2646-2649`/**`:2713`** | `testSelectChainOfAndOrOperators` | high |
| No `TypedNewInstance` fold arm in object space | `resolver/Substitution.java:1870-1879` | `testFilterUsingClassAttribute` +5 elsewhere | high |
| **H** `foldString` folds only `CString`/`plus`; a helper-call golden yields no comparison. *(The two goldens are mutually exclusive by construction.)* | `harness/TestDataGenForm.java:477-494` | `testBuildFilterWithValueThatCanBeNull{InFlowSql,PlanSql}` | high |
| `meta::pure::mapping::withMapping` unported | catalog | `testFromWithMapping`, `…AndIntermediateFuncCall`, `testMultipleFromWithMapping` | high |
| `contains` variant-wraps only the needle for an `Any` collection | `lowering/Scalars.java:1906-1911` | `testContainsWithOneValue` ×2 | high |
| Connection `timeZone` shift exists only in the plan renderer, never reaches the execution dialect | `Compiler.dialectOf:363-415`; port `EngineStyleH2.java:565-587` | `testInExecutionWithTempTableForDateTimesWithTz`, `…OnTimestampColumnWithTz` | high |
| **✗G** golden unreachable from seed data | — | `testInExecutionWithTempTableAndQueryChainingOnTimestampColumn` | high |
| Declared roadmap wall: strict-read filter hoist | `resolver/Substitution.java:876-891` (task #72) | `testInputNotIsolatedWhenPropertyPathIsToOne` | high |
| `average`/`mean` to-one identity elision loses the `Float[1]` promotion **⚠** | `lowering/Scalars.java:1176-1181` | `testSubAggregationMultiLevel` | high |
| **B** unordered join + `at(0)` pushed to `LIMIT 1` | — | `testSequenceMapWithConfusingSetImplementation` | high |
| Filter fan-out multiplies a pre-aggregated subselect **⚠** | `resolver/StoreResolver.java:2336-2341` × `Lowerer.buildGroupBy:579` | `testUsingSameAggFunctionTwiceWithFilter` | med-high |
| **✗G** ×2 (one self-inconsistent: 3 column names, 4 expected values) | — | `testUsingSameAggFunctionTwiceWithExistsFilter`, `testAggToManyWithAverageAndTimes` | high |
| Reducer lambda cannot carry a per-element `map` | `lowering/Lowerer.java:925-963` (fold into `aggSelectorBody:912`) | `testAggToManyWithAverageAndTimes2` | high |
| DB2 group-by separator `", "` vs `","` | `EngineStyleH2.java:658-661`, needs `EngineStyleDB2` override | `testGroupByWithJoinDB2` | high |
| Missing `execute(f,m,runtime,exeCtx,extensions)` overload; `preserveJoinOrder` unimplemented | `builtin/Pure.java:1397-1405` | `testOrder` | high |
| `generateObjectReferences` inside a collection literal | `harness/ObjectRefs.java:36-44` (map over `PureCollection`) | `testObjectReferneceInWithMilestonedRootClass`, `testObjectReferenceInUnion` | very high |
| Plan text for a relation/table-accessor root | `StatementExecutor.java:608-613` + `:2016-2026` | `test{Limit,Filter}…InSequenceForTableAccessor` | high |
| Nested `zip(a, zip(b,c))` in an assert | `resolver/CorrelatedSubselects.java:513-521` | `testSortByLambdaDeepOptional` | high |
| **⚠** graphFetch appends PK order keys unconditionally, overriding user `sortBy` | `resolver/GraphEmission.java:450` | `testSortByLambdaAndGraphFetchDeep` | very high |
| `loadCsvToDbTable` (4-arg native) absent | `builtin/Pure.java` | `testLoadCsv` | very high |

---

## functions/tests/projection (32 rows)

| cause | fix site | tests | conf |
|---|---|---|---|
| **Agg-demand scan gated on the projection row variable** — aggregate inside an inner `map` binder registers no demand | `resolver/CorrelatedSubselects.java:1976-2001` (recurse into mapper body, rebind `userVar`) | `testSubAggregationWithDeepAndOverlap` | certain |
| `ProjectChecker` normalizes on the raw AST, knows 4 column-arg shapes; zero-arg lambda `let`s are never β-inlined | `ProjectChecker.java:117-172`; `Typer.java:1786-1804` | `…_WithColVar`, `testAllOneSimplePropertyUsingVariables2/3` | certain |
| **No overload backtracking** — selection commits on non-lambda args, lambda typed after, no retry | `Typer.java:1440` + `:1451-1509` | `testVariableReferenceInMapWithNestedFilter`, `testProjectReferenceInFilterWithMultiLevelLhs`, `testVariableReferenceInMapWithNestedFunction` | certain |
| Store query spliced into an assertion lambda body is never resolved *(queries themselves are correct)* | `StoreResolver.java:505-506` | `testVariableReferenceInFilter/MapWithSameNameAsThatInParentProject` | high |
| Nested predicate scopes built with empty registries | `CorrelatedSubselects.java:1613-1622` | `testVariableReferenceInExists`, `testVariableReferenceQualifiedPropertyFollowedByExists` | high |
| Object-space nodes with no substitution arm (`TypedFilter` over primitive to-many; `TypedNewInstance`) | `Substitution.java:1875-1878` | `testFilterOnSimpleTypeProperty`, `testQualifierWithClassAsParameter` | certain |
| Class-typed leaf off an association head (2-hop) | `Substitution.assocLeaf:2017`, throws `:2043-2047` | `testChainedFiltersQuery` | certain |
| `prop[setId]` subtype dispatch collapses last-wins | `MappingNormalizer.java:2072-2074`, `:2295`, `:2749` | `testExistsAsNullWithSubType` | high |
| **H** `assertSameSQL` lacks the `PlanAsserts` pre-check `assertEquals` has | `TestBody.java:1944` (cf. `:1808-1812`) | `testFilterAfterJoinInRelation(+WithExtendedPrimitives)` | certain |
| **D** vanilla H2, not legend-patched H2 | — | `H2Test` | certain |
| **⚠** 2-hop `> (INNER)` chain prefix inside an embedded expression PM dropped → root read | `normalizer/JoinChainEmission.java:70-140` | `testIsolatioWhereNoConstaintsAndInnerJoin` | high |
| **⚠** correlated subselect keyed on the wrong column + second predicate absent | `CorrelatedSubselects` / `SyntheticHeads` (site not pinned) | `testVariableReferenceWithNestedFilterMultiple` | high |
| Scalar subselect on an unproven to-one | `Lowerer.java:2782-2790` (gate on provable uniqueness) | `testIsolationOfFiltersWithoutAlias` | high |
| `.values` stays relation-identity after a positional row selection | `Typer.java:2284` | `testSimpleBoolean` | certain |
| **H** `assertSize` counts rows of a TDS value through an `at(0)` wrapper | `TestBody.java:1908-1913` | `testTwoQualifiersUsingSameJoinWithNoUserParams` | certain |
| `castAsDeclared` pushes a Boolean wire coercion into SQL *(its premise comment is false)* | `MappingNormalizer.java:2489-2492` | `testInWithDynaFunction` | certain |
| **B** asymmetric variant wrap (DuckDB only) | `Scalars.java:1907-1911` | `testContainsWithOneValue` | certain |
| **B/⚠** µs `TIMESTAMP` literal truncates ns | `AnsiSqlRenderer.java:727-729`, `Lowerer.java:2211` | `testAdjustWithMicroseconds` | certain |
| H2 `toSQLString` spelling for `mostRecentDayOfWeek` | not pinned | `testMostRecentDayOfWeek` | high |
| Documented roadmap wall (non-strict derived over `[0..1]`) | `Typer.java:2343-2359` — *comment names this test* | `testQualifierWithInThroughJoin` | certain |
| `groupByWithWindowSubset` absent from catalog | `builtin/Pure.java` | `testGroupByWithWindowSubset` | certain |
| **H** `fail()` not harness vocabulary *(the query works)* | `TestBody.java:495-497` | `testSimpleConcatenate` | certain |
| **✗G** ×2 (one is an `assertEquals('')` placeholder; one satisfies its own `assertSize`) | — | `testProjectReferenceInRhs…UsingAggregation`, `testFilterInQualifierAndMapping` | certain |

---

## tests/query + tests + injection + datatype (31 rows)

| cause | fix site | tests | conf |
|---|---|---|---|
| **Stale `Feature` enum** — missing `LEGACY_SQL_NULL_UNSAFE_EQUALS`. **Verified: both goldens already emitted verbatim** | `builtin/Pure.java:653-658` | `testLegacyFlagProjectionEmitsPlainEquals`, `testLegacyFlagRestoresOptionalParamFreeMarkerSelector` | very high |
| Null-safe `==` + optional-param selector pinned to pre-upstream behaviour | `lowering/Scalars.java:71-113`; optional-param placeholder emitter (site not pinned) | `testDefaultProjectionIsNullSafe`, `testDefaultOptionalParamIsNullSafe` | high |
| Host class-instance read inside a query lambda | `Substitution.java:1871-1877`; `StoreResolver.java:496-503` | `testProjectWithSeparateGroupBy`, `testExistsOpenVariableClass`, `testFilterUsingFunctionWithClassAttribute` | high |
| Object-space filter over a multi-hop nav | `Substitution.java:1871-1877` | `testProjectThroughAssociation(+AutoMap)` | high |
| Unported vocabulary: `TDSColumn.type`, `Mapping.includes`, datatype subclasses, 2-arg `dayOfWeekNumber`, `isAlphaNumeric`, `meta::json::*`, `toJSON`, `toJSONStringStream`, `translateCoreTypeToDbSpecificType` | `builtin/Pure.java:479`, `:400`, `:345`, `:1047` | `testResultToJsonStream`, `testTranslateDbType`, `testExtractDBsWithSubstituition`, `testDayOfWeekNumberFunction`, `testFilterUsingIsAlphaNumericFunction`, `testPushDownProjectWithParameter`, `testSimpleTypeMappingProjectNulls` | very high |
| **H** golden compare never attempted — rebuilt `toSQLString` copies args 3..n | `harness/ExecCallFinder.java:124-138` | `testViewSimpleExists` | very high |
| **H** 1-arg `assert` over golden SQL is unconditionally advisory | `TestBody.java:1777-1781`, `:1023-1032` | `testMostRecentDayOfWeek` | very high |
| **⚠ Aggregate over a chained to-many silently loses its reducer** — handler *and* its own audit-9 guard both test `path.get(0)` | `CorrelatedSubselects.java:2078-2107` | `testFilterTimesWithManyOperands` | very high |
| Correlated scalar subquery instead of an ON-hoisted join chain | `GraphEmission.java:2341-2355` / `:2562` | `testRelationalMapperWithJoin`, `testRelationalMapperTwoDBs` | med-high |
| Pure `asin`/`acos` host domain guard applied on the relational path *(doctrine call — same pattern on `sqrt`)* | `Scalars.java:1578-1590` (and `:1568-1576`) | `testFilterUsingArcSinFunction`, `…ArcCosFunction` | very high |
| `distinct()` over a mapped scalar collection: wrong route + unbindable emission | `Pipelines.java:1390-1396` + `StoreResolver.java:2604-2611`; `Scalars.java:2428-2435` | `testCollectionDistinctFunction` | high |
| `execute()` recognised only at statement root | `StatementExecutor.java:327-336` | `testWithParameterToClassNestedSelect` | very high |
| **No overload backtracking past a deferred lambda** *(also 3 rows in `projection/functionvariables`)* | `Typer.java:1631-1662`, `:1440-1520` | `testSQLNullWithinCaseTypeInference1`, `testJoinStringsTypeInference` | very high |
| NormalizeRequired inlining needs a let-only body (blocker is `mutateAdd`) | `Typer.java:1186-1198` | `testExecuteInDbToTDS` | very high |
| JoinNavigation loses its `[DB]` qualifier → size inference wrong | `RelationalGrammarParser.java:727` (`dbScope` → `db`); or `MetamodelWalk.java:1315-1317` | `testDynaComplexInference2` | high |
| SQL NULL kept as a value in a to-one object read | upstream of `exec/ResultShape.java:23-35`; rule lives only on `Executor.java:117-129` | `testSimpleTypeMappingNulls` | high |
| **✗G** ToFix golden drops the chained filter *and* has stale date rendering | — | `testChainFiltersUsingFirstDayOfThisYearH2` | very high |

---

## tds/tests (48 rows across 2 batches)

| cause | fix site | tests | conf |
|---|---|---|---|
| **H Seed pollution from test ordering** — we discover in declaration order, legend-pure sorts alphabetically (`PureTestBuilder.java:65`) | `RelationalCorpusRunner.java:502` / `Runner.java:508-534` | 12 × `simpleGroup*`, `GroupBy*`, `testTDSGroupByPercentile` | certain |
| Legacy TDS constructs (`agg`, `col`, `desc`) recognised only syntactically with literal args | fold query-lambda lets pre-Typer; else `SortChecker.java:106`, `Typer.java:261`, `ExtendChecker.java:138` | `simpleGroupByAggFuncAsLambda`, `testExtendWithVariables2`, `testTableToTDSWithQuotes`, `testProjectWithVariables2` | high |
| Literal-only `TDSRow` getter → unresolvable ref at lowering | `Typer.java:287-291`, `literalColName:881`; surfaces `Lowerer.java:1155-1159` | `testExtendWithVariables1`, `testProjectWithVariables1` | high |
| `rows.values->at(k)` slices rows | `Typer.java:2277-2311` (+ `:2266-2276`, `Lowerer.java:283-303`) | `testFilterOnEnum` | certain |
| Depth-1 aggregate scalar-wrapper unwrap (`max()->toOne()->adjust(...)`) | `Lowerer.java:895-905` | `testTDSGroupByWithEnumArgumentFunctionCall` | high |
| n-ary `concatenate` not lowered | `Lowerer.java:534-538` or `ConcatenateChecker.java:17` | `testMultiConcatenate` | certain |
| Object-space `TypedGroupBy` in a constraint | `Substitution.java:1871-1878` | `testValidateTdsGroupByWithIsNotEmpty` | high |
| `toSQLString` driver recognised only at statement root | `StatementExecutor.java:198-214` | `testParseDate` | high |
| Multi-column `olapGroupBy` partition → `ColSpec[2..2]` | `Typer.java:636-642` (emit `ColSpecArray`) | `testExecutionPlanGeneration` | high |
| `enumValues` unported | `builtin/Pure.java` | `testSortQuotes` | certain |
| **Engine-core platform library not registered** (`TestClass`, `assertSchemaRoundTripEquality`, `SimpleDateTimeFormat`, `buildPureModelContextTextFromMappingAndQuery`) | `Corpus.java` / `RelationalCorpusRunner.java:79-104` | `testJoinUsing`, `testJoinFunc`, `resolveSchemaTest`, `columnValueDifferenceTest`, `rowValueDifferenceTest`, `testExtractingDbFromTableReference` | very high |
| `.columns` returns String names, not `TDSColumn` objects; `StaticFold` map-unroll leaks the binder | `Typer.java:2182`/`:2314`; `StaticFold.java:124-141` | `testJoinWithExtendWithDigestOnColumnsOnBothQueries`, `testExtendDigest_InMemory/_Relational` | high |
| Legacy `join(tds1,tds2,JoinType,cols)` with a non-literal column list | `JoinChecker.tdsLegacyToModern:160-199` | `iqrClassifyTest`, `zScoreTest` | high |
| `!=` col-vs-literal spelling — **113 corpus goldens expect `is distinct from`; 0 expect ours** | `lowering/NullSemantics.java:72-95` | `testUnionWithPreOperation`, `testUnionWithPostOperation` | high |
| `toSQLString` alias convention (`_d#N` vs `_0`) | `Lowerer.nextAlias:274` — parity decision | `testRestrictDistinct_NoOptimization_WindowColumns` | high |
| `Typer.namedType` has no `FunctionType` arm | `Typer.java:2621-2638` | `testRestrictWithPostProcessor` | very high |
| Quote-bearing store column identity not normalized at lowering *(`Lowerer.stripQuotes` is dead code)* | `Fold.claims`/`Fold.sourceColumn` | `testProjectAllColumnsWithSpaces_Single` | high |
| `viewToTDS` has no native → corpus Pure body wins | `builtin/Pure.java` (cf. `TABLE_TO_TDS__RELATION_1:1868`) | `testView` | very high |
| Unresolved type variable at the lowering boundary | `PureSql.java:92-93` | `testFirstNotNull` | high |
| Resolver shape gap: `getAll` under `groupBy → extend → sort → join` | `StoreResolver.java:228` (class, not line) | `columnValueDifferenceWithoutPrevalTest` | med-high |
| **✗G** ×3 (Oliver has 6 letters; Boolean rendered as `1`; golden SQL copied from a different test) | — | `testFilterAfterGroupByWithSameColForGroupByAggAndFilterOnRootClass`, `…WithFilterOnAllProjectColumns`, `testProjectFunctionOnEnumColumn`, `testFunctionOnEnumColumn` | very high |

---

## tests/mapping (30 rows) — misc, dates, join, tree, sqlFunction, selfJoin, filter, include, multigrain

| cause | fix site | tests | conf |
|---|---|---|---|
| **⚠** ns datetime literal → µs `TIMESTAMP` *(standalone DuckDB repro)* | `Lowerer.java:2211` | `dates::datetime::testGet`, `testQuery`, `testQueryExactEquals`, `retrieveDateWithTimeZone` | certain |
| **⚠** undemanded to-many join cancelled with no cardinality guard | `Pipelines.java:404-408` | `testMultipleJoinsInPropertyMappingWithDatesInClass` | certain |
| `[0..1]` property splat keeps NULLs | `StoreResolver.java:963-981` (+ caller `:663`) | `testSameTableNameDifferentSchema1` | high |
| **H One DuckDB connection for all `Database` elements** — cross-file table clobber | `Runner.openSession()`, `ddlConflictsWithSession:1727-1737` | `testChainedJoinsWithUnionsAndIsolationWithProjectionQueryTableFilter` | certain |
| `castAsDeclared` changes the TDS cell kind | `MappingNormalizer.java:2487-2491` | `testJoinIsolationDeeper_LeftOuterLeftOuterThenInner`, `…TwoIsolations…` | high |
| Repeated filtered navigations share a slot prefix | `Pipelines.java:409` (key by head, not alias) | `testJoinIsolationDeeperTwoIsolations…` | med-high |
| Heterogeneous multi-column `sort()` | `ValueCollectionOps.java:33`, `Lowerer.java:515` | `dates::strictdate::testProject` | certain |
| No DATE⇄STRING comparison overload in join conditions *(also 3 `union::biTemporal` rows)* | overload/coercion table | `testMultipleJoinsInPropertyMappingWithDateInJoin` | certain |
| Slot leaf under a value-position filter | `Substitution.java:2712-2716` | `testChainedInnerJoinsWithQualifierInGroupBy` | certain |
| `subType(@T)` dispatch emitted only for scalar leaves *(also `union::partial`)* | `UnionSynthesis.java:305` region | `testForcedSubTypeProjectDirect` | high |
| **H** harness assert vocabulary unreachable from inside platform lambdas | `Typer.java:1362` / harness | `subType::testObjectQuery` | certain |
| Views as relations (join target; nested owner has no `sourceTable`) | `MappingNormalizer.java:3245-3253`, `:3273-3276` | `testSourceViewRootQueryWithInnerJoinClassMappingViewFilter`, `…PropertyQuery…`, `TestClassMappingsWithInnerFilterJoinedWithMilestoningDepthTwo…` | certain |
| **H** plain `sqlQueryPostProcessors` slot ignored | `SqlPostProcessors.java:51-59` | `testReplaceTablesPostProcessorJoinIsolation` | certain |
| `zip` assumes list-shaped args | `Scalars.java:1149-1174` (mirror `numList:1176`) | `stringToFloat::testProject` | high |
| Legacy-H2 `parseDateTime` `mmm` semantics *(partly unfixable)* | `DateFormats.java:101` | `testToSQLStringconvertToDateTimeinH2` | medium |
| `TypedMap` over a host collection whose lambda holds a class query | `StoreResolver.java:508-512` | `testAdjustDateTranslationInMappingAndQuery` | certain |
| **B** DuckDB row order *(H2: `filter` 9/9, `selfJoin` 2/3)* | — | `testSelfJoinPropertyMappingOverlap`, `…WithDynaFunction`, `testFilterMappingWithProjectionOverlapp` | certain |
| Multi-hop through an embedded head | `Substitution.java:1339-1348` | `testToManyWithQualifierWithFilterOnJoin` | certain |
| **H** `assertIs/2` + `resolveStore` unported (two stacked gaps) | `TestBody.java:878`; catalog | `testStoreSubstitution` | certain |
| Sort below a class-flatten hop | `FlattenOps.java:126-141` | `testGetterWithTargetFilter` | certain |
| **✗G** ×2 (golden = unfiltered sibling's; golden wants routing the mapping doesn't declare) | — | `testSubTypeProjectSharedNonDirectlyRoutedWithFilter`, `testProjectQualifiedPropertyFromUnmappedSuperClass` | certain / high |

---

## tests/mapping/embedded + enumeration (28 rows)

| cause | fix site | tests | conf |
|---|---|---|---|
| Emptiness/exists over an embedded same-row head — `predLeavesIn` requires `path.size()==1`; no arm for 1-arg `isEmpty`/`isNotEmpty` | `Substitution.java:2362-2367`; new arm beside `:801-812` | `testExists`, `testOptionalPropertyEmbedded` | high |
| Object-space `filter` vocabulary *(+ stop downgrading at `TestBody.java:545-553`)* | `Substitution.java:1871-1877` | `testRoutingQualifiedPropertySameVariableNamesAsFunctionParam` | high |
| Class-typed join sub-PM inside an embedded body is not a navigation route | `StoreResolver.java:854-864` | `testGetterTwoJoinTraversal` | med-high |
| **Otherwise dispatch is space-blind** — engine is per-leaf in TDS space, whole-property via the fallback set in object/graph space | `StoreResolver.java:1346-1355` + twins `Substitution.java:1952-1959`, `GraphEmission.java:2023-2038` | `otherwiseTestGetter` | high |
| An Otherwise partial's own class-typed sub-PM never demands its nav slot | `StoreResolver.java:1351-1352` | `testProjectionOtherwiseNonPrimitive` | high |
| **`Otherwise([setId]:…)` fallbackSetId is dead data — zero consumers repo-wide** *(neighbour `materializeInlineEmbedded` honours `ie.setId()`)* | `MappingNormalizer.java:2766-2776` + `JoinChainEmission.emitOtherwiseEmbeddedHop:196-230` | `otherwiseTestComplexExpressionWithEnumMapping` | very high |
| Nested embedded graph child keeps the parent's owner class *(one line; `childClass` computed at `:2008` and discarded)* | `GraphEmission.java:2107` | `testInlineInEmbeddedGraphFetch`, `testMilestonedEmbeddedInlineGraphFetch` | very high |
| `Inline[setId]` does not walk mapping `include`s | `MappingNormalizer.java:2785-2792` | `testProjectionMappingIncludes` | very high |
| Non-strict derived over a `[0..1]` receiver | `Typer.java:2343-2359` | `testDenormMappingWithQualifierWithIfAndEquals` | high |
| **H** `fail()` unlowered/unintercepted | `Scalars.java:2384` + `TestBody.java:1766-1953` | `testProjectionWithMultipleRootMappings` | very high |
| **H** empty-TDS CSV renderer drops the engine's blank line *(control: `testIsEmptyType` expects our wrong rendering and passes)* | `TestBody.java:3121-3122`, `:3087-3097` | `testIsEmpty` | very high |
| Follow-up read loses the producing execute's mapping scope *(harness amplifies)* | `StoreResolver.java:159-163` / `:299-307`; `Runner.java:1471-1485` | `testMapping` | med-high |
| Unported: mapping-element enum arm + `enumerationMappings`/`toDomainValue`; `TDS.csv` + cast-to-TDS; `enumerationMappingByName`; `transpose` *(absent upstream too)* | `Typer.java:2498-2513`, `:2425`, `:1361`; `RelOpTranslator` | `testEnumTheSame`, `testEnumInRelation`, `testEnumMappings(+WithInclude)`, `testMappingWithTranspose(+Filter,+Project)` | high |
| **B DuckDB row order — proven: `-Drcorpus.backend=h2` 18/30 → 22/30** | — | `testProjectionWithEnumThroughAssociation`, `testProjectWithIfWhereOneSideIsEnumLiteral(2)`, `…BothSidesUseTheSameEnumMapping` | very high |
| **✗G** ×2 (mutually unsatisfiable asserts; seed lacks `holder2` and golden's SQL selects the wrong column) | — | `testTimeStampPrimaryKeyDateInFilter`, `testSubTypeOnPropertyMappedToNonRootInlineSetImpl` | very high |

---

## tests/mapping/union + relation (25 rows)

| cause | fix site | tests | conf |
|---|---|---|---|
| Embedded block with per-target-set routed joins of the same property name refused *(top-level route has a uniquifier it doesn't use)* | `JoinChainEmission.java:126-142` (cf. `:506`, `:545`, `:581`) | `testAdvancedEmbeddedInMappingQuery` ×2 | high |
| Hoisted-op non-hop source paths dropped, then source materialized with zero slot demand | `FlattenOps.java:64-86` + `StoreResolver.java:726-728` | `testUnionToUnionJoinSequenceWithMultipleChildrenInUnionSourceTree` | high |
| Union arms project all shared scalars; `SubselectPrune` never prunes set-op branches | `UnionSynthesis.java:1031-1047`; `SubselectPrune.java:277-288` | `testProjectAndFilterSamePropertySameJoinInUnion` | high |
| No `execute/5` with `ExecutionContext` in position 4; `RelationalExecutionContext` unmodelled | `builtin/Pure.java:1397-1398` (+ router twins `:1404-1405`); `validation/DriverPkOption.java` | `testPksWithImportDataFlow` ×2 | high |
| Plan `resultColumns` cannot resolve through a UNION-inner subselect | `plan/PlanText.java:711-737` (add a `SqlUnion` arm) | `testEnumFilterWithUnionMappingPlanGeneration` | high |
| No DATE↔STRING comparison overload | `builtin/Pure.java:1171-1182`; throw at `InferenceKernel.java:810-821` | 3 × `biTemporal::*` | high |
| `subType(@Sub).<class-typed prop>.<leaf>` — mints `stc_…___<prop>__<leaf>`, reader asks for `stc_…___<prop>` | `Substitution.java:1883-1978` (mirror `:1323-1332`) | `testPartialUnionMappingOfSubTypePrimitiveProperties_EmbeddedMapping` | high |
| **`#TDS` empty cell → SQL NULL; DB holds `''`; we compare structurally where upstream compares rendered text** | `TestBody.java:2734-2748` or `Scalars.java:2915-2926` | `…_ManyColumnProject`, `…GeneratesSingleUnion` | high |
| **ModelJoin/XStore condition rewrite ignores expression-bodied `Col`** *(machinery exists: `Col.expr()`, `Col.bindSrc`)* | `RelationReads.java:90-116`; latent second wall `XStorePureEnds.java:75-90` | 10 × `advanced::testUnion*` | high |
| **✗G** stub goldens (`assertSameSQL('')`, `assertEquals([])`) | — | `testUnionWithPartialForeignKeyUsage1/2` | high |

---

## tests/mapping/modelJoin + relation (24 rows)

| cause | fix site | tests | conf |
|---|---|---|---|
| `(INNER)` `~filter` builds table scope from un-canonicalized names *(must fix `collectTablesIn`/`determineTargetTable` together)* | `JoinChainEmission.java:807`; `RelOpTranslator.java:85-101`; `MappingNormalizer.java:3256-3277` | `testFilterWithInnerJoinOnTarget` | high |
| `ModelJoinNesting.compose` composes exactly ONE nesting level | `ModelJoinNesting.java:119-123` | `testNestedModelJoinCompoundInnerCondition` | high |
| Association nav hops inside a lifted to-many predicate never registered | `CorrelatedSubselects.java:1588-1593`; call site `AssociationJoins.java:1129-1131` | `testSubFilter`, `testQualifiedPropertyInQuery`, `testRelationalSubFilter` | high |
| Derived properties not inlined before the ModelJoin column-binding lookup | `RelationReads.java:90-116` | `testDerivedPropertyInCondition` | high |
| 2-arg `executionPlan(fnWithFrom, extensions)` unsupported | `StatementExecutor.java:1938-1947`; `TestBody.java:1415-1419` | `testPersonToFirmUsingProject`, `…UsingFromProject`, `…UsingFromGraphFetch` | high |
| `!=` → `nullSafeNotEqual`/`is distinct from` **(30 corpus goldens)** | `NullSemantics.java:66-101` + an `IS_DISTINCT` case in `EngineStyleH2.java:1085-1108` | `testJoinWithConstant{Double,String,Date}` | high |
| ORDER BY null placement (no `NULLS` clause; engine sorts in memory) | `Fold.java:344` / `H2.java:275-281`; **prefer** making a materialised-TDS `sort()` use the engine comparator | `testChainedTwoHops` | high |
| **H** TDS-text header quoting (`'First Name'`) | `TestBody.java:3213` | `testNullableSalaryMapping` | high |
| Nested-hop widening not carried through union arms *(engine also fails)* | `AssociationJoins.java:1847-1880` | `testUnionPropertyInCondition` | high |
| Native `Runtime` missing `preprocessFunction` | `builtin/Pure.java:225` | `testModelJoinForNonRelationalConcepts` | high |
| **⚠** query filter folds to `WHERE` inside a window-carrying mapped relation *(doctrine written at `Fold.java:246-256`, unimplemented — no window-awareness anywhere in the resolver)* | resolver-side isolation of windowed `~func` pipelines | `testMappingWithWindowColumn` | high |
| **H** `assertTdsEquivalent/3,4` not in the assert vocabulary *(queries execute fine)*; also needs `[m]`-suffix stripping in `TdsChecker.java:135-155` | `TestBody.java:1994-1996` | `testDateTimeInclusiveRangeQuery`, `testDateTimeRetrieveWithTimeZone` | high |
| `#>{db.View}#` — views absent from table resolution | `StoreCompiler.java:34-70` | `testRelationStoreAccessorOnView` | high |
| **✗G** ×4 — two are **unmarked** (no ToFix): golden reproduced by applying the filter to the outer person (self-association alias collision) | — | `testSimpleMappingQueryWithFilterInProject`, `testMixedMappingWithFilterInProject`, `testMilestonedIntermediates`, `testPersonToFirmGraphUsingFetch`/`…FromGraphFetch` | high |

---

## milestoning/tests + modelToModelToRelational (47 rows across 2 batches)

| cause | fix site | tests | conf |
|---|---|---|---|
| **`getAllForEachDate` unported** — needs a new cross-join temporal form, not just a catalog entry | `builtin/Pure.java`, `CoreFn.java:108-110`, `Typer.java:1072`, new resolver extent form | **11 tests** (9 × `testProcessingTemporalQuery*`, `testProcessingTemporalModelQueryOnRoot`, `…OnPropertyWithPropogatedDate`) | certain |
| Relational metamodel absent from the native catalog (`SQLQuery`, `operation::Operation`, `datatype::Date`, `SemiStructured*`, `ClusteredValueSpecification`) | `builtin/Pure.java` | `testMilestoningFilterApplicationOnSemiStructured…`, `testMileStoningWithNewTDSFilterAndPostProcessor`, `testViewChainsWithBusinessDate` | certain |
| **`LATEST_DATE` at the lowering boundary on the `toSQLString` path** *(execute path folds it correctly)* — **6 tests family-wide** | surfaces `PureSql.java:65-66`; real fix is the missing fold | `testLatestIgnoredForNonMilestonedMappedClassesAllQuery`, `…BiTemporalClasses…`, `testQueryOfMilestonedTypeUsingLatestWithFilterInMapping`, `testLatestMilestoneDatePropogation…QpInFilter`, `…MappedTableDateDoesNotOverride…` | certain |
| **Join emission follows PM declaration order; engine's is phase-based** *(corrects `LEG1_INNERJOIN_FAMILY.md:448-467`)* | order set at `MappingNormalizer.java:2164-2170`; cleanest intervention `StoreResolver.java:1763-1766` | `testMilestoningQueryWithMilestoneFilterAndDifferentDatesOnTypeWithLatestDateOnProperty`, `testExecutionPlanForQueryWithVariableRundateWithinLambda` | high / med |
| `toSQLString` surface re-aliased where the engine skips it **— recommend NOT fixing** (cosmetic; one row shows our SQL is semantically ahead) | `EngineStyleH2.planSource:326-347` | `testDateFunctionInMilestonedProperty`, `testAllVersionsQueryWithMilestonedProperty` | certain |
| **⚠** embedded milestoned hop drops the explicit date argument | not pinned (chain-key attribution across a table-less embedded hop) | `testDateFunctionInMilestonedPropertyWithMilestonedEntity` | high |
| `pathOf` cannot express the identity path (returns null for the cursor itself) | `Substitution.java:773-775` + `TemporalFrame.java:2041-2052` | `testBusinessDateInjectionFromVarReferenceInProjectUsingExternalFunction` | certain |
| No `TypedMap` arm in anchored position | `StoreResolver.java:507-513` | `testBiTemporalPropertyUsageAfterExecute` | certain |
| `cast/2` rejects `@FunctionDefinition<Any>` | `Typer.java` deferred-shape prefilter | `testBusinessDatePropagationInColFunction_asQueryParam` | med |
| `relationalExtensions():Any[*]` vs `Extension[*]` *(sweep blast radius before changing)* | `builtin/Pure.java:1435` | `testLatestTemporalMilestoningPostProcessor` | high / med |
| Object-space `TypedFilter` over an all-versions navigation | `Substitution.java` — arm beside `:1826-1832` | `testLinkageBetweenUnionWithIsolatedMultiJoinSelectLHS` | high |
| **H effectful-let guard kills the union `BeforePackage`** *(latent behind it: `Runner.moduleDdl` dedups tables by bare lowercased name)* | `StatementExecutor.java:134-149`; `Runner.java:1636`/`:1653` | 4 × `testIsolationOfSubselect…`, `testSubSelectsWithDifferentColumnsMerge`, `testRootUnionQueryWithRelationalJoins/PropertyJoin` | high |
| No flattened multi-join PM subselect / no per-use join isolation | `StoreResolver`/`AssociationJoins` | `testIsolationOfMilestoningFiltersUsedOnIntermediateJoinInOR` | med |
| **⚠** `->map` re-root joins LEFT instead of INNER | `StoreResolver.java:2730` (`rowPreserving` decided by the final terminal alone) | `testMilestoningQueryOnATypeWithManyRelationalPropertyMappingChildrenFollowedByMap`, `testMilestoneDatePropogationFromTypeQueryToNoArg…`, `…WhereSubsequentProjectOverrides` | high |
| **⚠** explicit QP milestoning date dropped after a `->map` re-root | ordering: `StoreResolver.java:2724-2731` vs `:2811-2813` | `…WhereSubsequentProjectOverrides` | med |
| Runtime-constructed `Mapping` at `from()` (grammar compile + `^$m(includes=…)`) | `FromChecker.java:74-76`; `TestBody.clgArm:794-836` | 5 × `_ViaNoArgMapping` variants | high |
| `$this.businessDate` in an `allVersionsInRange` root *(golden is `''` — do not chase)* | temporal-context resolution | `testBusinessTemporalRangeQueryOnRoot…WithThisBusinessDateParameter` | high |
| **✗G** ×4 (one is `assert(false)`; one is arithmetically disproven with a `not not not exists` tell) | — | `testConstraintUsageOfVarReferenceWithThisMilestoningContext`, `testPopulationOfMilestonedBusinessDateInProject`, `testMilestoningQueryWithMultipleChildren…`, `testAllVersionsQuery` | very high |

---

## executionPlan/tests (60 rows across 3 batches)

| cause | fix site | tests | conf |
|---|---|---|---|
| **`nullSafeEqual` unimplemented — only the legacy FreeMarker selector exists.** `is not distinct from` (H2) / `(a=b or (a is null and b is null))` (DB2/Composite). **13 of 20 rows in one batch** | `EngineStyleH2.java:931-970`; `EngineStyleDB2` needs the default spelling; + a `Feature` flag | 13 × `testFilterEqualsWith(Two)OptionalParameter*` | very high |
| Engine-H2 text dialect **mixes H2 1.4.200 and 2.1.214 spellings per-construct** — defeats `assertEqualsH2Compatible`'s either-golden escape hatch | `EngineStyleH2.java:866-870`, `:552-588`, `:500-508`, `:1010-1019` — derive from one version-scoped table | 5 rows (co-cause) | high |
| `StrictDate`/`Date` collapse to one plan-param kind, reconstructed by a dotted-name heuristic | `Fold.java:32-36`; `EngineStyleH2.java:903-905` | `testGroupByWithTwoOpenVariablesInAggAndFilter` | high |
| Large-IN / temp-table plan vocabulary absent (`inFilterClause_*`, `FreeMarkerConditionalExecutionNode`, `CreateAndPopulateTempTable`, `RelationalBlockExecutionNode`); also `IN`→`=` collapse tests arity not literal-ness | `plan/PlanText.java`; `EngineStyleH2.java:1084-1088`; engine spec `processInOperation.pure:34-130` | `testFilterInWithResultSorcedFromAnExpression`, `testExecutionPlanGenerationForMultipleInWithTwoCollectionInputs`, `…ForInWithVarAndConstantInputs`, `testMultiExpressionWithPlatformAndFromFunction` | high / med |
| **Parameterized-lambda `let`s β-folded at type time** — gate `!lam.parameters().isEmpty()` exactly separates failing from passing | `Typer.java:1749-1758`; + a `PureExp` value form in `StatementExecutor.allocationNode:897-966` | `testMultiExpressionWithPlatformAndFromFunction`, `testFilterInWithResultSorcedFromAnExpression` | high |
| Missing `EXECUTION_PLAN__2_P2` (2-arg form, 2-param lambda) | `builtin/Pure.java:1474-1475` | `testSupportStreamFlagFromSimple` | very high |
| `graphFetch` tree arg must be a syntactic `#{…}#` | `GraphFetchChecker.java:82-87` | `testSupportStreamFlagWithGraphFetchAndFrom` | high |
| Bare `TypedGraphFetch` (no `->serialize`) has no `StoreResolver` arm | `StoreResolver.java:405-411` | `planGraphFetchWithDerivedProperty`, `planGraphFetchWithNestedDerivedProperty` | high |
| **`deferredShapesMatch` rejects `FunctionDefinition<Any>`** — latent for every such helper; masked by `Runner.java:662-676` | `Typer.java:1611-1615` | `executeProjectWithNestedDerivedProperty` | high |
| Plan-handle metamodel too thin (`SQLExecutionNode` has only `sqlQuery`; `StoreMappingGlobalGraphFetchExecutionNode` is `{}`; `PlanNode` has 4 fields) | `builtin/Pure.java:501-511`; `plan/PlanNode.java:20-21`; `StatementExecutor.planModel:1938-2014` | `testSQLCommentsInPlan`, `testPlanWithLocalH2ConnectionWithSQL`, `testGraphFetchH2TempTableStrategy(+WithQuoteIdentifiers)`, `testDatabaseConnectionSQLPopulation(+Legacy)` | high |
| Plan-text node vocabulary: no `PureExp`/`StoreMappingGlobalGraphFetch`/`RelationalGraphFetch`/`SQL`/`ExternalFormat_ExternalizeTDS`; no bare `Allocation` for a single trailing `let` | `plan/PlanText.java` (6 forms only); `StatementExecutor.java:604-606` | `testQuoteIdentifiersFlagWithGraphFetch`, `testExecutionPLanGenerationForFromInAllocation` | high |
| `rootGetAllClass` is BFS → wrong branch's class in nested cross-DB joins; and requires a `TypedGetAll` at all | `StatementExecutor.java:2016-2026` (+ callers `:608`, `:751`); `PlanText.java:681` | `tdsTwoJoinThreeDB`, `relationalTDSTypeForColumnsAndQuoting` | high |
| M2M composition is **eager over all ctor properties**, not demand-driven | `ClassSources.java:897`/`:899-906` | `testModelConnectionDeepFunction`, `testModelConnectionAgg`, `testModelConnectionMultipleAgg` | high |
| M2M association target resolved without the chain-mapping context | `AssociationJoins.java:132` (2-arg `ClassSources.get`; also `:92`, `:190`, `:199`) | `testModelConnectionJoin` | high |
| Legacy shared-key TDS join desugars via a **visible** `__jk_` rename | `JoinChecker.java:202-287` | `testTwoMappingsOneRuntime(+WithoutExternalMapping)` | high |
| `PlanEnumForm` strips the enum-decode CASE unconditionally *(engine keeps it on the `->from()` route)* — **guard condition unknown** | `StatementExecutor.java:463-469` / `PlanEnumForm.java:41-46` | `testExecutionPlanGenerationForLambdaFromWithEnumMapping` | med-high |
| Host object-valued open variable not const-folded | `Substitution.java:1871-1878` | `testMapWithOpenVariableOutsideBlock` | high |
| Property path through an Allocation-bound plan variable folds only one hop | `Lowerer.java:2290-2299`; `EngineStyleH2.java:975-983` | `testPlanGenerationForMultipleExpressionsWithPropertyPath` | high |
| Plan-text root-impl handles only single Relational class mappings (not Operation/union) | `ScanRelations.java:577-593`; `PlanText.java:100-104` | `inheritance` | high |
| **✗OA Engine bug baked into goldens** — `${tdsVar}` splice columns hardcoded to `Integer` (`pureToSQLQuery.pure:581-585`); golden contradicts its own `type = TDS[…]` line. **Policy call: matching means reproducing an engine bug** | `StatementExecutor.java:748-750`; `PlanText.java:557-572`, `:681` | `tdsJoinTwoDBWithColumnMappedViaJoins`, `tdsJoinTwoDBExtend`, `testCrossDbPlanGenerationWithFromWithoutExternalMapping` | very high |
| **D** Service DSL / external-format / protocol surfaces outside the registered roots | `Corpus.java:46-60` | `testPureExecutionStrategy*` ×2, `testRelationalProjectionWithExternalFormat`, `testEnumPushDownWithExternalFormat`, `testRoutingContextBuilderFunctions`, `testPreprocessFunctionOnRuntime` | high |

---

## graphFetch (17 rows)

| cause | fix site | tests | conf |
|---|---|---|---|
| XStore key that is not a plain projected column (join-terminal `+prop`; or dropped from a union projection) | `RelationReads.java:90-116`; `UnionSynthesis.java:1036-1047` | `testCrossMappingWithRelOpWithJoinKeys`, `testRootUnionWithOnePropertySet_CrossStore` | high |
| **`from()` rejects a subtype of `Runtime`** — exact FQN equality where a subtype test is needed | `FromChecker.java:53-56` | `testCrossStoreWithCSVDataSource`, `testSpecialUnion_m2m2r` | high |
| `embeddedChild` calls `derivedLeaf` with a null `SubqueryEnv` *(the only such call site)* | `GraphEmission.java:2073` (+ widen `:2932-2942`) | `testEmbeddedMappingQualifiedPropertyAccess2`, `…WithArgs` | high |
| `callKey` cannot render `%latest` as a JSON tree key | `GraphEmission.java:3006-3040` | `testMilestonedPropertyWithLatest` | high |
| `union` operation sets emitted as plain UNION ALL with no PK identity merge *(only `special_union` semantics built)* | `UnionSynthesis` root synthesis + `GraphEmission` root keys | `testMilestoningWithUnionMapping` | med-high |
| **H** `assertEquals` does no JSON canonicalisation *(content byte-identical)* | `TestBody.java:1798-1861` | `testSortOrderAtTopPreserved` | high |
| **⚠ H** connection `postProcessors` / `MapperPostProcessor` channel unimplemented — **silently** | new reader beside `SqlPostProcessors.java`/`RelationalMapperRenames.java`; wire at `StatementExecutor.java:470`, `:1990` | `testGraphFetchWithTableMapperPostProcessor` | high |
| Checked envelope: root-class-only constraint walk + hardcoded `"path": []`; no "unevaluatable constraint" concept | `GraphEmission.java:522-561`; `CheckedEnvelope.java:52-54` | `testCheckedWithCircularConstraints` | high |
| **`include mapping <path>` mis-parsed** — `isFqnSegmentToken` admits keyword tokens | `MappingGrammarParser.java:166-167` | `testCrossStoreGraphFetchWithRelationalDatePropagationForMilestonedPropertyConstraint` | high |
| M2M `prop*` zip explosion — declared roadmap, correctly loud | `MappingNormalizer.java:1332-1337` | `testCrossMappingJsonToDBWithExplosion` | high |
| **H** corpus assembly does not pull `core/pure/graphFetch/domain/**` | `Corpus.java:57` / `RelationalCorpusRunner.java:76-80` | `graphFetch/domain::testGraphFetch` | high |
| **D** plan-text golden encodes the engine's multi-node temp-table architecture *(the data assert passes)* | — | `testMilestonedProperty` | high |
| Union root row order | `GraphEmission`/`UnionSynthesis` root `ORDER BY` | `test6` | undetermined |
| **✗G** golden expects rows unreachable through the mapping under test | — | `testNestedUnion_CrossStore` | high |

---

## lineage + testDataGeneration + advanced (55 rows across 3 batches)

| cause | fix site | tests | conf |
|---|---|---|---|
| **H ⚠ The advisory skip reads the expected answer** — `a[1].contains("joinleft_")`. 7 rows never attempted; **20 of 40 "passes" are half-verified** | `LineageRelationsForm.java:109-119` (split the guard; drop `!tdsRooted` as a blanket skip) + `ScanRelations.java:496` (tds-join children built with `joinName == null`) | `testUnionWithJoinToOneTable`, `testUnionToSameTableWithDiffKeys`, `testSameRelationsAtSameLevel`, `testTableToTdsWithJoin`, `…ToSameTable`, `…WithOLAPGroupBy`, `testTdsJoinConcatenateAndJoin` | high / med |
| **H** `NotImplementedException` laundered into advisory | `LineageRelationsForm.java:131-140`; real walls `ScanRelations.java:323-328` (cross-join condition), `:385-401`/`:116-117` (concatenate on a join side) | `testTableToTdsWithCrossJoin`, `testTableToTdsWithJoinAndUnion` | high / med |
| **No relation node per VIEW** — `expandIfView` replaces the view; `planNode` never expands; view-on-view collapsed. **`perWebChildren` is a compensating overfit — retire it in the same commit** | `TestDataGenerator.java:574-603`, `:1172-1207`, `:1211`; `ScanRelations.java:676-707`, `:816-861` | `testUnionViewOnView`, `testViewEmbeddedInChainedJoin`, `testAlloyTestDatGenWithQuotedColumnsForViews` | high |
| tdg plan builder throws where the engine emits an `Error` node; no `PlanText.error(...)`; probe is `top 20`/`root` not `top 5`/`<table>_0` | `TestDataGenerator.java:1230-1233`, `:1255`, `:1277` | `alloy::testErrorDueToNoSeedForRoot` | high |
| Class-typed subtype props never distributed into a union row *(include-walk fix alone is insufficient)* | **primary** `UnionSynthesis.java:797-828`; secondary `ClassSources.java:713` | `testInheritanceMultipleLevel` | high |
| Set-id-routed union fan-outs nested inside embedded never register | `UnionSynthesis.classifyUnionRoutes:219-225`; fix the misdiagnosing text at `JoinChainEmission.java:131-137` | `testUnionToUnion` | high |
| tds-join side parser: no fork per concatenate arm | `ScanRelations.java:282-303` | `testTableToTdsWithJoinAndUnion` | high |
| Association SubNav trees are depth-1 by construction *(stated at `AssociationJoins.java:143-146`)* | `AssociationJoins.java:191-203`, `:1132-1141`; `Substitution.java:339-341` | `isolationTest`, `testMultipleIsolationWithDifferentProp` | high |
| Object-space `TypedFilter`/`TypedLimit` default arm | `Substitution.java:1871-1877` (arms at `:1826-1832`, `:1843-1844` need class-space counterparts) | `testQualifierChain`, `relationalResultSourcingOfDateList` | high |
| `filteredNavLeafRead` accepts a class-typed leaf *(no data-type guard; `rewriteHeadProp` has one at `:1366-1371`)* | `Substitution.java:2539`, projects at `:2739-2743` | `testQualifierWithIsolation`, `testQualifierWithIsolationXX` | very high |
| **B** DuckDB row order — **proven by running the engine's own golden SQL on DuckDB** | — | `testFilterMappingWithProjectionOverlappForcedCorrelated`, `…ForcedOnClause` | very high |
| `UNNEST` has no engine-style plan-text spelling | `AnsiSqlRenderer.java:607-609`; `EngineStyleH2` override | `relationalResultSourcingOfListExecutionPlan` | high |
| **D** `scanColumns` is plan-derived; class-typed whole-value projection cannot lower | `LineageForm.java:101-103`, `:115-118`; wall `Substitution.java:1366-1371` | `testNonDataTypeProperty` | high |
| **H** SHAPE with no wall — semantically identical SQL, no row assert exists | normalise golden SQL, or extend `H2Verify.java:214` | `testLiteralConditionsForcedIsolation`, `testForcedIsolationFilterOnTop` | high |
| **✗G** ToFix golden arithmetically unsatisfiable | — | `testWithForkedQualifier` | high |
| **D** whole Pure protocol subsystem absent | widen roots, or port | `testClassesAssociationsAndMappingFromDatabase` | certain |

---

## router/tests + postprocessor/tests (21 rows)

| cause | fix site | tests | conf |
|---|---|---|---|
| **D No Pure router** — `routeFunction`, `StoreMappingRoutedValueSpecification`, `ClusteredValueSpecification`, `router::printer::asString`. Proven absent 4 ways | out of scope by design | `testRoutingOfSimpleQualifiedProperty`, `testPlatformExpressionDependencyOnAFromExpression(2)` | very high |
| `isFunctionTyped` rejects `FunctionDefinition<Any>` *(7 passing siblings differ only in `<Any>` vs `<{->…}>`)* | `Typer.java:1611-1615` | `testCompositionInMultiStatementPureExpressions` | very high |
| Cross-statement object lets — `SubQueryLift` gates on a syntactic `toOne()`; no `TypedNewInstance` fold | `Substitution.java`; `SubQueryLift.java:70-92` | `testRoutingTwoFindAllExpressionsWithVariable`, `…WithNewClassInstance`, `…WithClassPropertyInFilter` | very high / med |
| `$result.activities` folded to the empty collection — no activities envelope | `StatementExecutor.java:2580-2588` + `ExecFrame:2041` | `testRoutingFindAllExpressionReturnsMany` | very high |
| 3-hop `subType(..).<assoc>.<leaf>` | `Substitution.java:1322-1348` | `testRoutingWithSubtypePropagation` | high |
| **D** `assertRoundTrip` + preeval AST surface + unregistered model | structural | `testPrerouting41/42/_Store` | very high |
| **H Connection rename map lost on a `$result.values` re-read** *(three-execution SQL dump proves it)* | add the map to `ExecFrame`; thread in `spliceValuesRead:2686-2716` | `testReplaceTablesPostProcessor` | very high |
| **Alias groups derived at render time from the post-rename table name** — engine freezes aliases in `replaceAliasName` *before* connection post-processors | `EngineStyleH2.java:328-346` — re-alias pass over the IR, or a stable `aliasGroup` on `SqlSource.Table` | `testReplaceTablePostProcessorWithExists` | very high |
| Views not materialised as subselects; `(INNER)` property join flattened *(**not** a post-processor bug — proven by a no-post-processor sibling)* | `Lowerer`/`StoreResolver` view handling | `testReplaceTablePostProcessorWithView` | very high |
| **D** no `SQLQuery` base class; no `sqlQueryToString`/`toSQL`/`SQLResult` natives | `builtin/Pure.java` | `testPushFiltersDownToJoinsPostProcessorToSQL`, `testDb2ColumnRename`, `testPostProcessTransformJoinOp` | very high |
| `nonExecutable` registered twice (native + corpus) → structural scoring → `Any[*]` vs `Extension[*]` | add to `PlatformTypes.isPlatformOwnedFunction:218-230` | `testReplaceTablePostProcessorWithSubQueries` | high |
| **H** `through` allowlist rejects a `->replace()` tail; and `toSqlString` reads the rename from a **ThreadLocal ambient**, not its own runtime arg | `ExecCallFinder.java:114-118`; `StatementExecutor.java:390-394`, `PostProcessBoundary.java:26-33` | `testToSqlStringReplaceTablesPostProcessor` | high / med |
| **✗G** ToFix golden copy-pasted from a Firm test onto a Person query | — | `testRoutingUseVariableBeforeAndAfterRelationalExecution` | very high |

---

## pureToSQLQuery + sqlQueryToString + helperFunctions + misc (25 rows)

| cause | fix site | tests | conf |
|---|---|---|---|
| **D Pure-level relational/M3 metamodel not modelled** (`SQLQuery`, `CoreDataType`, `ValueSpecification`, `join::Join`) — **proven by design from our own comments** (`PlatformTypes.java:151-155`, `RelationalDataType.java:5-10`) | none warranted | `simpleFunctionExpressionTranslationNow/Adjust`, `addDriverTablePkForProject`, `testImportDataFlow`, `testProcessIdentifierWithQuoteChar` | very high |
| Multiplicity constants (`ZeroMany`, `PureOne`, …) absent | `builtin/Pure.java` + `Typer.classReference:2060-2145` | `testFindFunctionSequenceMultiplicity` | high |
| **Generic superclass type-argument substitution** — `X extends Pair<String,TableAlias>` yields a raw `TypeVar` *(the logic exists at `Typer.java:2422-2440`, never reached)* | `Typer.accessProperty` `ClassType` arm; `PureModelContext.findProperty:196-200` | `testMergeOldAliasToNewAlias` | high |
| **⚠ H Prelude fallback binds a missing class to a same-named class in another package** | `NameResolver.java:505` (+ `PRELUDE_TYPES:247-250`) — refuse a package-disjoint candidate | `testReAliasMergedJoinOperations` | high |
| `TableAlias.relation()` derived property + partial `View`/`Table` metamodel | `builtin/Pure.java:340`, `:271`, `:411` | `testFindAliasMappingBySchemaName` | high |
| **`setUpDataSQLs` bound to `CsvSeed` instead of `Ddl.*StatementText`** — the correct generators **already emit the golden text character for character**; also needs a `List<String>[*]` overload and a quote-aware CSV split | `StatementExecutor.java:3133-3145`; `Ddl.java:79-110`; `Pure.java:1448-1449`; `CsvSeed.java:88` | `testSetupDataSqlGeneration`, `…WithDataAsString`, `…WithColumnValueHasDelimiterAndQuotes` | high |
| `ReflectAsserts` reads a qualified property's **declared** multiplicity; engine inlines its body. Also no `if(...)` arm | `harness/ReflectAsserts.java:167-190` | `tesIsToOneDataTypeFunctionExpressionSequenceWithIfExpressions` | high / low |
| `eval` rejects a function-typed **call result**; `datatype::Integer` not declared; `createTempTable`/`dropTempTable` absent | `EvalChecker.java:136`; `builtin/Pure.java:345` | `testCreateTempTableStatement`, `dropAndCreateTempTable` | high |
| Cardinality op folded above a to-many join **⚠** *(`FlattenOps.spliceBelow` already does it right; `rowPreserving` exists but isn't consulted)* | `StoreResolver.java:2919-2922` | `dynaJoin::testGet` | high |
| `rootClassMappingByClass` ignores the include closure; `size` absent from `planWalk`'s vocabulary; `Mapping.classMappings()` not on the metaclass | `MetamodelWalk.java:791-801`; `StatementExecutor.java:1394-1415`; `builtin/Pure.java:400` | `testRootMappingForDifferentIncludeOrder`, `testCountClassMappingsForRedundantInclude`, `testRootClassMappingForRedundantInclude` | med-high / high |
| Agg over an expression (not a path) crossing a to-many | `CorrelatedSubselects.java:2080`, `tailMapperOf:2150-2194` | `testSubAggregationMapArithmeticOnRelationMapping` | high |
| **✗G** ×4 — incl. one where the golden is the sibling gsn-test's, and two where `assertSize(project(),2)` is impossible in the engine | — | `testGroupByMappingProjectAggregateWithGroupByInJoin`, `testJoinWithAggregateFunctionQualifier(+WithAssociation)`, `testToManyJoinTreeNodesForInvalidUsage…DoMergeGivingWrongResults` | very high |

---

## transform/fromPure (13 rows)

| cause | fix site | tests | conf |
|---|---|---|---|
| **Alias plan applied unconditionally on the `toSQLString` surface** — engine passes `[]` post-processors there. **Census: every golden naming a join alias fails; all 44 without one pass.** Note: reproducing `_d#` names may be infeasible — consider alias-insensitive comparison | `EngineStyleH2.java:220-229`, `:285-380`; thread a surface flag from `ExecCallFinder.java:118-134` | 11 of 13 rows | very high |
| `STRING_AGG` has no engine-text arm (`listagg`); determinism `ORDER BY rowid` leaks | `EngineStyleH2.java:1132`; `Lowerer.java:1092-1101` | `testToSQLStringJoinStrings` | high |
| `count(DISTINCT x)` vs `count(distinct(x))` | `EngineStyleH2.java:1132` | `testIsDistinctSQLGeneration` | high |
| **Null-safe equality expanded at lowering instead of carried as a node** — `FILTER_POS` is the wrong predicate (engine keys on multiplicity, no position test); `not(OR)` renders **without parens**, which is *semantically wrong SQL* | `NullSemantics.java:66-142`; `Lowerer.java:1193`; `Scalars.java:164-168`; **`EngineStyleH2.java:1021-1032` ignores `parentPrec`** | 6 rows `testNullSafeEquality*`, `testEqualityInFilter*`, `testNotEquality*` | very high |
| Class-`map` root join tree (engine materialises the full tree; we are demand-driven) | resolver emission rule | `testSqlGenerationDivide_AllDBs` | med-high |
| `TypedNewInstance` unresolvable when a field holds a class-query lambda | `StoreResolver.java:509-512` (arm beside `:506`); `Anchors.java:42-58` | `testToSQLStringWithAggregation`, `testToSQLStringWithAbs` | very high |
| `^Duration(…)` unknown class *(exception swallowed — see `ExecCallFinder.java:136-141`)* | catalog | `testToSQLStringWithCodeBlock` | very high |
| `toNonExecutableSQLString` unimplemented **and** absent from `ExecCallFinder`'s stop set | `builtin/Pure.java`; `ExecCallFinder.java:118` | `testNonExecutableSQLString` | very high |

---

## §Z — Reading order for a burn-down session

1. **`CORPUS_STUDY_2026_08_ALL.md` §0** — reproduction (you need `-Drcorpus.includeExcluded`).
2. **§7 masked-wall census** — run it for your family before anything else.
3. **§2 Tier 1** — eight near-free fixes, two verified.
4. **§8 sequencing** — three constraints that will bite.
5. **§9 corrections** — one wrong diagnosis and three false comments already in the tree.
6. This index, for your family.
7. `ARCHITECTURE_AUDIT_2026_08.md` §5 — only when you move to structural work.

**Two standing rules, earned the hard way:**

- **Before touching any ToFix row, find the passing sibling that differs in one dimension.**
  That technique found 31 wrong goldens. Two of them carry **no** ToFix marker.
- **Do not group by error message.** Rows sharing a message template were checked and
  found to have unrelated causes.
