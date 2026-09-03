# SQL-text homework — every SQL-text assert, one by one (2026-09-03)

User ask: "1-by-1 deep analysis on all the SQL ones, not guessing or sampling — even if the test only has a SQL assert, can we not check the SQL output?" This document is the receipt. Two populations were measured, not sampled:

1. **The 65 "text-policy" fallbacks.** These tests were never attempted on the platform: the flip gate (`WholeTestFlip`, `SqlTextShapes.allSimple`) pre-declined any body whose SQL asserts were not of a "simple" shape. With the gate switched off (`LL_TEXTPOLICY_ATTEMPT=1`, probe run 2026-09-03) **36 of the 65 flip on genuine rows verdicts and 29 hit a real, named wall; 0 previously-flipped tests are lost** (ratchet 366/2207 → 330/2243; passes 2374 → 2375; dual-channel disagree 0; the canon lattice's 21 float-ULP disagreements unchanged). The gate is deleted in batch 37.
2. **Every SQL-text assert the platform arm judged by TEXT** (the rows leg declined). Measured with a new per-test roster (`target/sqltext-text-verdict-roster.txt`, attributed by counter deltas per test). In the same run the arm judged 1,677 SQL-text asserts: 501 rows-verified with identical text, 1,005 rows-verified with differing text, **170 by text in 154 tests** — each listed below with its reason.

Answer to the question: yes — an SQL-only assert IS checkable by rows (golden SQL replayed on the oracle H2 vs our rows), and that is what the arm does for 1,506 of 1,677 asserts. The 170 text verdicts are the cases where one side cannot produce rows; each has a reason below, and several of those reasons are our bugs.

## Part 1 — the 65 gated tests

Columns: shape (the gate's own census label), outcome when attempted, cause / disposition.

### 1a. Flipped when attempted (36) — rows verdicts, nothing else needed

- `meta::relational::tests::milestoning::businessdate::testConstraintUsageOfThisMilestoningContext1` — shape `assert-formx1`
- `meta::relational::tests::milestoning::businessdate::testConstraintUsageOfThisMilestoningContext1b` — shape `assert-formx1`
- `meta::relational::tests::milestoning::businessdate::testConstraintUsageOfThisMilestoningContext1c` — shape `assert-formx1`
- `meta::relational::tests::milestoning::businessdate::testConstraintUsageOfThisMilestoningContext2` — shape `assert-formx1`
- `meta::relational::tests::milestoning::businessdate::testConstraintUsageOfThisMilestoningContext2WithNonTemporalStore` — shape `assert-formx1`
- `meta::relational::tests::milestoning::businessdate::testConstraintUsageOfThisMilestoningContext3` — shape `assert-formx1`
- `meta::relational::tests::milestoning::businessdate::testMilestonedThisBusinessDateUsedAsParameterToFunctionInMilestoningQualifiedPropertyMappedToView` — shape `assert-formx1`
- `meta::relational::tests::milestoning::businessdate::testProcessedMilestonedExchangeNameConstraint` — shape `assert-formx1`
- `meta::relational::tests::milestoning::contextpropagation::testMilestoneDatePropogationThruExistsIsIndenpendentOfDateManipulationWithinTheFilter` — shape `exec-sql-readx1+h2compat-simplex1+plainx1`
- `meta::relational::tests::milestoning::contextpropagation::testMilestoneDatePropogationThruFilterIsIndenpendentOfDateManipulationWithinTheFilter` — shape `exec-sql-readx1+h2compat-simplex1+plainx1`
- `meta::relational::tests::projection::qualifier::testQualifierFunctionConsistencyWithComplexTypeProperty` — shape `exec-sql-readx1+execsqlread-simplex2+plainx3`
- `meta::relational::tests::projection::qualifier::testQualifierFunctionConsistencyWithDataTypeProperty` — shape `exec-sql-readx1+execsqlread-simplex2+plainx2`
- `meta::relational::tests::query::filter::equal::testBuildFilterWithValueThatCanBeNullPlanSql` — shape `exec-sql-readx1`
- `meta::relational::tests::query::function::testDayOfWeekNumberFunction` — shape `exec-sql-readx1+plainx1`
- `meta::relational::tests::tds::slice::testSimpleSliceZeroSameAsTake` — shape `exec-sql-readx1`
- `meta::relational::tests::tds::tdsRestrict::testLowerProjectColsEliminated` — shape `exec-sql-readx1+execsqlread-simplex1+plainx2`
- `meta::relational::tests::tds::tdsRestrict::testLowerProjectColsNotEliminatedWithDistinct` — shape `exec-sql-readx1+execsqlread-simplex1+plainx2`
- `meta::relational::tests::tds::tdsRestrict::testLowerProjectColsNotEliminatedWithSort` — shape `exec-sql-readx1+execsqlread-simplex1+plainx2`
- `meta::relational::tests::tds::tdsRestrict::testRestrictOnGroupByColumn_DropAllAggColumns` — shape `exec-sql-readx3+execsqlread-simplex1+plainx2`
- `meta::relational::tests::tds::tdsRestrict::testRestrictOnGroupByColumn_SubSetOfGroupByColumns` — shape `exec-sql-readx2+execsqlread-simplex1+plainx3`
- `meta::relational::validation::showcase::standalone::validateMultipleConstraints` — shape `assert-formx1+plainx1`
- `meta::relational::validation::showcase::standalone::validateMultiplesConstraintWithConstraintInformation` — shape `assert-formx1+plainx1`
- `meta::relational::validation::showcase::standalone::validateSingleConstraint` — shape `assert-formx1+plainx1`
- `meta::relational::validation::tests::milestoning::testAggregationOnRootClass` — shape `assert-formx1`
- `meta::relational::validation::tests::milestoning::testValidateQueryOpenVariableInAgg` — shape `assert-formx1`
- `meta::relational::validation::tests::milestoning::testValidateQueryOpenVariableInCol` — shape `assert-formx1`
- `meta::relational::validation::tests::milestoning::testValidateQueryOpenVariableInColAndAgg` — shape `assert-formx1`
- `meta::relational::validation::tests::milestoning::testValidateQueryOpenVariableInColAndExtraProjection` — shape `assert-formx1`
- `meta::relational::validation::tests::milestoning::testValidateQueryOpenVariableInKeyExpression` — shape `assert-formx1`
- `meta::relational::validation::tests::milestoning::testValidateQueryWithMilestoning` — shape `assert-formx1`
- `meta::relational::validation::tests::milestoning::testValidateQueryWithMilestoningAndAggregationAll` — shape `assert-formx1`
- `meta::relational::validation::tests::milestoning::testValidateQueryWithMilestoningAndAggregationSingle` — shape `assert-formx1`
- `meta::relational::validation::tests::milestoning::testValidateQueryWithMilestoningAndAggregationSingleAndNestedDynaFunction` — shape `assert-formx1`
- `meta::relational::validation::tests::milestoning::testValidateQueryWithMilestoningWithMultipleVariables` — shape `assert-formx1`
- `meta::relational::validation::tests::milestoning::testValidateQueryWithMilestoningWithVariable` — shape `assert-formx1`
- `meta::relational::validation::tests::milestoning::testValidateQueryWithUnion` — shape `assert-formx1`

Shapes explained: `assert-form` = assertSameSQL / assertEqualsH2Compatible whose actual is a `validate(...)` or `toSQLString(...)` value (since batch 31 validate() is a frame; the sql read routes to the exec-read rows arm); `exec-sql-read` = `$result->sqlRemoveFormatting()` reads plus text PREDICATES (`assert($sql->contains(...))`, `assertFalse(... contains 'hello')`) — the predicate asserts are the test author's own text contract (an optimisation property such as "the constant column was eliminated"); they pass because our SQL has the property.

### 1b. Walled when attempted (29) — the first wall each, probed

- `meta::relational::testDataGeneration::tests::alloy::testDataGenerationWithBiTemporalMilestoning_WithMilestoningDates_Alloy` — shape `assert-formx1`
  - wall: TypeInferenceException: in function 'meta::relational::testDataGeneration::executionPlan::planTestDataGenerationWithParameterValuePairs': unknown function 'functionReturnType' — no function of this name in the native or user catal
  - cause: unported engine-internal function `functionReturnType` inside planTestDataGenerationWithParameterValuePairs (TDG alloy)
  - disposition: residue: engine-internal (same bucket as the 4 alloy TDG tests)
- `meta::relational::testDataGeneration::tests::alloy::testDataGenerationWithBusinessDateMilestoning_WithMilestoningDates_Alloy` — shape `assert-formx1`
  - wall: TypeInferenceException: in function 'meta::relational::testDataGeneration::executionPlan::planTestDataGenerationWithParameterValuePairs': unknown function 'functionReturnType' — no function of this name in the native or user catal
  - cause: unported engine-internal function `functionReturnType` inside planTestDataGenerationWithParameterValuePairs (TDG alloy)
  - disposition: residue: engine-internal (same bucket as the 4 alloy TDG tests)
- `meta::relational::testDataGeneration::tests::alloy::testDataGenerationWithSnapshotMilestoning_WithMilestoningDates_Alloy` — shape `assert-formx1`
  - wall: TypeInferenceException: in function 'meta::relational::testDataGeneration::executionPlan::planTestDataGenerationWithParameterValuePairs': unknown function 'functionReturnType' — no function of this name in the native or user catal
  - cause: unported engine-internal function `functionReturnType` inside planTestDataGenerationWithParameterValuePairs (TDG alloy)
  - disposition: residue: engine-internal (same bucket as the 4 alloy TDG tests)
- `meta::relational::tests::functions::sqlstring::testIsDistinctSQLGeneration` — shape `assert-formx2`
  - wall: IllegalStateException: no scalar lowering registered for resolved overload 'meta::pure::functions::asserts::assertEquals' with 2 parameter(s)
  - cause: assertSameSQL over a toSQLString STRING for a FOREIGN dialect (DB2/Composite) — `tryArmExecRead` finds no sql() read / plan producer for the String, returns null, the statement falls to the lowerer
  - disposition: mechanism: the String overload over a toSQLString producer must take the foreign-dialect text verdict (the same `foreign-dialect` text contract the flipped DB2 tests take) — a verdict-arm leg
- `meta::relational::tests::functions::sqlstring::testNonExecutableSQLString` — shape `assert-formx1`
  - wall: in function 'meta::relational::functions::sqlstring::toNonExecutableSQLString': no overload of 'meta::relational::functions::sqlstring::toSQLString' matches 8 argument(s) of these shapes — candidates: [meta::relational::functions:
  - cause: engine helper `toNonExecutableSQLString` calls an 8-argument toSQLString overload we do not declare
  - disposition: port: declare the 8-arg toSQLString signature (verify against real sqlstring.pure)
- `meta::relational::tests::functions::sqlstring::testSqlGenerationDivide_AllDBs` — shape `assert-formx2`
  - wall: IllegalStateException: no scalar lowering registered for resolved overload 'meta::pure::functions::asserts::assertEquals' with 2 parameter(s)
  - cause: assertSameSQL over a toSQLString STRING for a FOREIGN dialect (DB2/Composite) — `tryArmExecRead` finds no sql() read / plan producer for the String, returns null, the statement falls to the lowerer
  - disposition: mechanism: the String overload over a toSQLString producer must take the foreign-dialect text verdict (the same `foreign-dialect` text contract the flipped DB2 tests take) — a verdict-arm leg
- `meta::relational::tests::functions::sqlstring::testSqlGenerationForAdjustStrictDateUsageInFiltersForH2` — shape `assert-formx1`
  - wall: IllegalStateException: java.sql.SQLException: Catalog Error: Scalar Function with name h2version does not exist!|Did you mean "version"?||LINE 2: FROM (SELECT H2VERSION()) AS _p|                     ^
  - cause: the engine H2 extension probes the H2 version by `executeInDb('SELECT H2VERSION();', $conn)` (h2Extension.pure:47) to choose the adjust/date spelling; our ambient session is DuckDB, which has no H2VERSION()
  - disposition: decision: an engine-internal H2 probe; either the session answers H2VERSION() for an H2-typed connection (a dialect-fact query, not DuckDB) or residue
- `meta::relational::tests::functions::sqlstring::testSqlGenerationForAdjustStrictDateUsageInProjectionForH2` — shape `assert-formx1`
  - wall: IllegalStateException: java.sql.SQLException: Catalog Error: Scalar Function with name h2version does not exist!|Did you mean "version"?||LINE 2: FROM (SELECT H2VERSION()) AS _p|                     ^
  - cause: the engine H2 extension probes the H2 version by `executeInDb('SELECT H2VERSION();', $conn)` (h2Extension.pure:47) to choose the adjust/date spelling; our ambient session is DuckDB, which has no H2VERSION()
  - disposition: decision: an engine-internal H2 probe; either the session answers H2VERSION() for an H2-typed connection (a dialect-fact query, not DuckDB) or residue
- `meta::relational::tests::functions::sqlstring::testToSQLStringWithAbs` — shape `plainx1+tosqlstring-nonlambdax1+tosqlstring-simplex1`
  - wall: class query under TypedNewInstance <<TypedNewInstance(TypedCString, lambda[](TypedGroupBy(TypedProject(TypedGetAll, lambda[p](.firstName($p))), lambda[e](TypedCInteger), lambda[y](count($y)))), TypedPackageableRef, TypedEnumValue,
  - cause: `runTestCaseById` builds a `^TestCase(query=…, mapping=…, dbType=…)` instance list and evaluates toSQLString over the instance's fields — a constructed-instance read of a LAMBDA-valued property
  - disposition: mechanism: constructed instances with function-valued properties read at the toSQLString site (C2-adjacent: instance rows carrying a lambda)
- `meta::relational::tests::functions::sqlstring::testToSQLStringWithAggregation` — shape `plainx1+tosqlstring-nonlambdax1`
  - wall: class query under TypedNewInstance <<TypedNewInstance(TypedCString, lambda[](TypedGroupBy(TypedProject(TypedGetAll, lambda[p](.firstName($p))), lambda[e](TypedCInteger), lambda[y](count($y)))), TypedPackageableRef, TypedEnumValue,
  - cause: `runTestCaseById` builds a `^TestCase(query=…, mapping=…, dbType=…)` instance list and evaluates toSQLString over the instance's fields — a constructed-instance read of a LAMBDA-valued property
  - disposition: mechanism: constructed instances with function-valued properties read at the toSQLString site (C2-adjacent: instance rows carrying a lambda)
- `meta::relational::tests::functions::sqlstring::testToSQLStringWithCodeBlock` — shape `assert-formx1`
  - wall: unknown class 'Duration' in ^Duration(…)
  - cause: `^Duration(number=1, unit=DurationUnit.MONTHS)` — the Duration class is not in our catalog
  - disposition: port: declare meta::pure::functions::date::Duration (verify against real .pure) + add() over a Duration
- `meta::relational::tests::groupBy::datePeriods::testGroupByWithFilterFunction_noDatePath` — shape `assert-formx1+h2compat-simplex2+plainx1`
  - wall: AssertFailed: assertEqualsH2Compatible (sql-text, oracle declined: column arity differs: golden 10 vs frame 4): expected select "root"."date" as "pk_0", "root"."calendar name" as "pk_1", "root"."date" as "date", "root"."fiscal wee
  - cause: the sql-text assert compares a SUB-QUERY text (the golden is `select distinct … from validPersonTable` / the 10-column calendar frame) while the frame executes the whole query — the oracle cannot align rows for a fragment
  - disposition: referee: sqlRemoveFormatting(N) reads the N-th activity; the rows leg must replay the N-th activity, not the frame (2 tempTable); datePeriods: filtered-navigation lift wall underneath
- `meta::relational::tests::mapping::union::biTemporal::testBiTemporalUnionAsJoinTarget_correlatedSubqueryQuoting` — shape `exec-sql-readx5`
  - wall: AssertFailed: Assert failed
  - cause: five `assert($sql->contains('"unionalias_1"."lake_thru_0"'))` predicates on the engine's alias/quoting spelling of a bi-temporal union self-join — pure text contract on engine alias names
  - disposition: text contract on an engine feature/spelling we do not reproduce — residue unless the feature is built (removeUnionOrJoins) or the spelling adopted
- `meta::relational::tests::mapping::union::biTemporal::testBiTemporalUnionJoin_milestoningColumnInOnClause` — shape `exec-sql-readx1`
  - wall: AssertFailed: Assert failed
  - cause: `assert($sql->contains('"lake_thru_0"'))` — the engine's quoted milestoning column in the ON clause (text contract on quoting)
  - disposition: text contract on an engine feature/spelling we do not reproduce — residue unless the feature is built (removeUnionOrJoins) or the spelling adopted
- `meta::relational::tests::mapping::union::testChainedUnions` — shape `exec-sql-readx1+plainx3`
  - wall: AssertFailed: Assert failed
  - cause: assert(sql contains 'union_gen_source_pk_0') after `testRuntimeWithRemoveUnionOrJoinsFeatureEnabled()` — the engine's removeUnionOrJoins post-processor (an optimisation feature we do not implement); its rows asserts pass
  - disposition: text contract on an engine feature/spelling we do not reproduce — residue unless the feature is built (removeUnionOrJoins) or the spelling adopted
- `meta::relational::tests::mapping::union::testProjectThroughAsso` — shape `exec-sql-readx1+plainx3`
  - wall: AssertFailed: Assert failed
  - cause: same removeUnionOrJoins predicate
  - disposition: text contract on an engine feature/spelling we do not reproduce — residue unless the feature is built (removeUnionOrJoins) or the spelling adopted
- `meta::relational::tests::mapping::union::testProjectThroughAssoWithJoinInMapping` — shape `exec-sql-readx1+plainx3`
  - wall: AssertFailed: Assert failed
  - cause: same removeUnionOrJoins predicate
  - disposition: text contract on an engine feature/spelling we do not reproduce — residue unless the feature is built (removeUnionOrJoins) or the spelling adopted
- `meta::relational::tests::mapping::union::testUnionWithSinglePropertyMapping` — shape `assertsamesql-simplex1+exec-sql-readx1+plainx1`
  - wall: AssertFailed: Assert failed
  - cause: same removeUnionOrJoins predicate (the assertSameSQL rows leg passes)
  - disposition: text contract on an engine feature/spelling we do not reproduce — residue unless the feature is built (removeUnionOrJoins) or the spelling adopted
- `meta::relational::tests::milestoning::businessdate::testDateFunctionInMilestonedProperty` — shape `assert-formx1`
  - wall: IllegalStateException: java.sql.SQLException: Catalog Error: Scalar Function with name h2version does not exist!|Did you mean "version"?||LINE 2: FROM (SELECT H2VERSION()) AS _p|                     ^
  - cause: the engine H2 extension probes the H2 version by `executeInDb('SELECT H2VERSION();', $conn)` (h2Extension.pure:47) to choose the adjust/date spelling; our ambient session is DuckDB, which has no H2VERSION()
  - disposition: decision: an engine-internal H2 probe; either the session answers H2VERSION() for an H2-typed connection (a dialect-fact query, not DuckDB) or residue
- `meta::relational::tests::milestoning::businessdate::testDateFunctionInMilestonedPropertyWithMilestonedEntity` — shape `assert-formx1`
  - wall: IllegalStateException: java.sql.SQLException: Catalog Error: Scalar Function with name h2version does not exist!|Did you mean "version"?||LINE 2: FROM (SELECT H2VERSION()) AS _p|                     ^
  - cause: the engine H2 extension probes the H2 version by `executeInDb('SELECT H2VERSION();', $conn)` (h2Extension.pure:47) to choose the adjust/date spelling; our ambient session is DuckDB, which has no H2VERSION()
  - disposition: decision: an engine-internal H2 probe; either the session answers H2VERSION() for an H2-typed connection (a dialect-fact query, not DuckDB) or residue
- `meta::relational::tests::milestoning::businessdate::testViewChainsWithBusinessDate` — shape `assert-formx1`
  - wall: no overload of 'meta::relational::functions::sqlstring::toSQLString' accepts 5 argument(s)
  - cause: `toSQL(...).toSQLString(type, timeZone, quoteIdentifiers, config, …)` — the 5-argument toSQLString on a SQLResult
  - disposition: port: declare the SQLResult.toSQLString overloads (verify against real .pure)
- `meta::relational::tests::postProcessor::testToSqlStringReplaceTablesPostProcessor` — shape `assert-formx1`
  - wall: IllegalStateException: no scalar lowering registered for resolved overload 'meta::pure::functions::asserts::assertEquals' with 2 parameter(s)
  - cause: assertSameSQL over a toSQLString STRING for a FOREIGN dialect (DB2/Composite) — `tryArmExecRead` finds no sql() read / plan producer for the String, returns null, the statement falls to the lowerer
  - disposition: mechanism: the String overload over a toSQLString producer must take the foreign-dialect text verdict (the same `foreign-dialect` text contract the flipped DB2 tests take) — a verdict-arm leg
- `meta::relational::tests::projection::view::testUnionOnViewsMapping` — shape `exec-sql-readx1+execsqlread-simplex1+plainx2`
  - wall: AssertFailed: Assert failed
  - cause: same removeUnionOrJoins predicate (plus a toCSV equality of the two runs)
  - disposition: text contract on an engine feature/spelling we do not reproduce — residue unless the feature is built (removeUnionOrJoins) or the spelling adopted
- `meta::relational::tests::query::filter::in::tempTable::testInExecutionWithTempTableAndQueryChaining` — shape `exec-sql-readx2+plainx3`
  - wall: AssertFailed: assertEquals (sql-text, oracle declined: column arity differs: golden 1 vs frame 2): expected select distinct "root".FIRSTNAME from validPersonTable as "root", got select "root".FIRSTNAME as "firstName", "root".LASTN
  - cause: the sql-text assert compares a SUB-QUERY text (the golden is `select distinct … from validPersonTable` / the 10-column calendar frame) while the frame executes the whole query — the oracle cannot align rows for a fragment
  - disposition: referee: sqlRemoveFormatting(N) reads the N-th activity; the rows leg must replay the N-th activity, not the frame (2 tempTable); datePeriods: filtered-navigation lift wall underneath
- `meta::relational::tests::query::filter::in::tempTable::testInExecutionWithTempTableAndQueryChainingOnIntegerColumn` — shape `exec-sql-readx2+plainx3`
  - wall: AssertFailed: assertEquals (sql-text, oracle declined: column arity differs: golden 1 vs frame 2): expected select distinct "root".AGE from validPersonTable as "root", got select "root".FIRSTNAME as "firstName", "root".LASTNAME as
  - cause: the sql-text assert compares a SUB-QUERY text (the golden is `select distinct … from validPersonTable` / the 10-column calendar frame) while the frame executes the whole query — the oracle cannot align rows for a fragment
  - disposition: referee: sqlRemoveFormatting(N) reads the N-th activity; the rows leg must replay the N-th activity, not the frame (2 tempTable); datePeriods: filtered-navigation lift wall underneath
- `meta::relational::tests::query::function::testDayOfWeekFunction` — shape `exec-sql-readx1+plainx1`
  - wall: AssertFailed: assertEquals (sql-text, oracle declined: enum-decoded column (post-transform rows)): expected select formatdatetime("root".tradeDate, 'EEEE') as "Day Of Week Name" from tradeTable as "root" where formatdatetime("root
  - cause: the golden selects the raw enum code; our frame decodes in SQL; the oracle had no per-column decode map for this frame
  - disposition: referee leg: derive the enum decode map from the enumeration mapping (the 20-assert enum-decoded class)
- `meta::relational::tests::tds::tdsExtend::testParseDate` — shape `tosqlstring-nogoldenx1`
  - wall: AssertFailed: Assert failed
  - cause: `assert($sql->contains('parsedatetime'))` over toSQLStringPretty with a RUNTIME argument — our engine-style text spells parseDate differently for this shape (EngineStyleH2 has a parsedatetime spelling for the formatted form; check the constant-literal form)
  - disposition: emission gap on OUR side — a spelling/optimisation leg (rows already equal)
- `meta::relational::tests::tds::tdsProject::testProjectWithColumnSubSetSQLTest` — shape `exec-sql-readx1+execsqlread-simplex4+plainx1`
  - wall: AssertFailed: assertEquals (sql-text, oracle declined: enum-decoded column (post-transform rows)): expected select "root".FIRSTNAME as "first_name", "root".LASTNAME as "last_name", "root".AGE as "age", "addresstable_0".NAME as "Em
  - cause: the golden selects the raw enum code; our frame decodes in SQL; the oracle had no per-column decode map for this frame
  - disposition: referee leg: derive the enum decode map from the enumeration mapping (the 20-assert enum-decoded class)
- `meta::relational::tests::tds::tdsRestrict::testRestrictOnGroupByEleminatesUnnecessaryAggsWithDistinct` — shape `exec-sql-readx2+execsqlread-simplex1+plainx2`
  - wall: AssertFailed: Assert failed
  - cause: `assertFalse($sql->toLower()->contains('max'))` — restrict after groupBy+distinct should DROP the unused max() aggregate from the SQL; ours keeps it (an optimisation the engine performs; a real emission difference, rows still equal)
  - disposition: emission gap on OUR side — a spelling/optimisation leg (rows already equal)

Wall groups: assertEquals-lowered 3 (`testIsDistinctSQLGeneration`, `testSqlGenerationDivide_AllDBs`, `testToSqlStringReplaceTablesPostProcessor`) = one verdict-arm leg; H2VERSION 4; enum-decoded 2 + column-arity 3 = referee legs; unported ports 4 (functionReturnType 3 are engine-internal; toSQLString overloads 2, Duration 1); text predicates 9 (removeUnionOrJoins 5, alias/quoting 2, parseDate 1, restrict-drops-agg 1); constructed TestCase instances 2.

## Part 2 — the 170 asserts judged by TEXT inside flipped tests (154 tests)

Reason classes, then every test. "Disposition" says whether the text verdict is the honest contract or a gap we should close.


### plan-hole-not-simple — 37 asserts / 37 tests

The assert compares engine PLAN text whose SQL carries FreeMarker operation templates for optional/collection parameters; the platform renders the same plan text and the text IS the contract (no rows exist for a template).

**Disposition:** honest text contract (plan text); no rows possible

- x1 `meta::pure::executionPlan::tests::datetime::testPlanForDateTimeVariableESTTimeZone`
- x1 `meta::pure::executionPlan::tests::simpleExpressionWithMultipleVariables`
- x1 `meta::pure::executionPlan::tests::simpleExpressionWithVariable`
- x1 `meta::pure::executionPlan::tests::tdsJoinTwoDBExtend`
- x1 `meta::pure::executionPlan::tests::tdsJoinTwoDBWithColumnMappedViaJoins`
- x1 `meta::pure::executionPlan::tests::testClassPropertyOpenVariable`
- x1 `meta::pure::executionPlan::tests::testExecutionPlanGenerationForInWithIntegerCollection`
- x1 `meta::pure::executionPlan::tests::testExecutionPlanGenerationForMultipleInWithTwoCollectionInputs`
- x1 `meta::pure::executionPlan::tests::testFilterEqualsWithOptionalParameterDate`
- x1 `meta::pure::executionPlan::tests::testFilterEqualsWithOptionalParameterDateTimeWithNoTimeZone`
- x1 `meta::pure::executionPlan::tests::testFilterEqualsWithOptionalParameterDateTimeWithTimeZone`
- x1 `meta::pure::executionPlan::tests::testFilterEqualsWithOptionalParameterFloat`
- x1 `meta::pure::executionPlan::tests::testFilterEqualsWithOptionalParameterInteger`
- x1 `meta::pure::executionPlan::tests::testFilterEqualsWithOptionalParameterString`
- x1 `meta::pure::executionPlan::tests::testFilterEqualsWithOptionalParameter_Composite`
- x1 `meta::pure::executionPlan::tests::testFilterEqualsWithOptionalParameter_DB2`
- x1 `meta::pure::executionPlan::tests::testFilterEqualsWithOptionalParameter_H2`
- x1 `meta::pure::executionPlan::tests::testFilterEqualsWithTwoOptionalParameterDateTimeWithTimeZone`
- x1 `meta::pure::executionPlan::tests::testFilterEqualsWithTwoOptionalParametersDate`
- x1 `meta::pure::executionPlan::tests::testFilterEqualsWithTwoOptionalParametersDateTimeWithNoTimeZone`
- x1 `meta::pure::executionPlan::tests::testFilterEqualsWithTwoOptionalParametersFloat`
- x1 `meta::pure::executionPlan::tests::testFilterEqualsWithTwoOptionalParametersInteger`
- x1 `meta::pure::executionPlan::tests::testFilterEqualsWithTwoOptionalParametersString`
- x1 `meta::pure::executionPlan::tests::testFilterInWithResultSorcedFromAnExpression`
- x1 `meta::pure::executionPlan::tests::testFilterWithOpenVariable`
- x1 `meta::pure::executionPlan::tests::testGroupByWithOpenVariableInAgg`
- x1 `meta::pure::executionPlan::tests::testGroupByWithTwoOpenVariablesInAggAndFilter`
- x1 `meta::pure::executionPlan::tests::testMapWithOpenVariable`
- x1 `meta::pure::executionPlan::tests::testPlanGenerationForInWithCollectionParameterHavingTimeZoneForH2`
- x1 `meta::pure::executionPlan::tests::testPlanGenerationForMultipleExpressionsWithPropertyPath`
- x1 `meta::pure::executionPlan::tests::twoExpressionWithConstant`
- x1 `meta::pure::executionPlan::tests::twoRoutedExpressions`
- x1 `meta::relational::tests::m2m2r::testProp4`
- x1 `meta::relational::tests::milestoning::businessdate::testExecutionPlanForQueryWithVariableRundateWithinLambda`
- x1 `meta::relational::tests::query::filter::isempty::testIsEmptyOnCollection`
- x1 `meta::relational::tests::query::sort::testSortByLambda_QueryWithParameters_Plan`
- x1 `meta::relational::tests::tds::tdsExtend::testFunctionOnVariable`

### foreign-dialect DB2 — 28 asserts / 26 tests

toSQLString for DatabaseType.DB2: no DB2 to execute on; the platform spells the DB2 dialect and compares text.

**Disposition:** honest text contract (foreign dialect); rows impossible without a DB2

- x1 `meta::relational::tests::functions::sqlstring::testCbrt`
- x1 `meta::relational::tests::functions::sqlstring::testDayOfYear`
- x1 `meta::relational::tests::functions::sqlstring::testEqualityInFilterOnOptionalPropertiesLegacy`
- x1 `meta::relational::tests::functions::sqlstring::testLeftRight`
- x1 `meta::relational::tests::functions::sqlstring::testNotEqualityInFilterOnOptionalPropertiesLegacy`
- x1 `meta::relational::tests::functions::sqlstring::testPad`
- x1 `meta::relational::tests::functions::sqlstring::testToSQLStringReverse`
- x1 `meta::relational::tests::functions::sqlstring::testToSQLStringWithAggregationDB2`
- x1 `meta::relational::tests::functions::sqlstring::testToSQLStringWithLength`
- x1 `meta::relational::tests::functions::sqlstring::testToSQLStringWithPosition`
- x3 `meta::relational::tests::functions::sqlstring::testToSQLStringWithRelativeDateDB2`
- x1 `meta::relational::tests::functions::sqlstring::testToSQLStringWithRepeatString`
- x1 `meta::relational::tests::functions::sqlstring::testToSQLStringWithReplace`
- x1 `meta::relational::tests::functions::sqlstring::testToSQLStringWithStdDevPopulation`
- x1 `meta::relational::tests::functions::sqlstring::testToSQLStringWithStdDevSample`
- x1 `meta::relational::tests::functions::sqlstring::testToSqlGenerationFirstDayOfMonth`
- x1 `meta::relational::tests::functions::sqlstring::testToSqlGenerationFirstDayOfThisYear`
- x1 `meta::relational::tests::functions::sqlstring::testToSqlGenerationFirstDayOfYear`
- x1 `meta::relational::tests::functions::sqlstring::testTrim`
- x1 `meta::relational::tests::mapping::sqlFunction::parseInteger::testToSQLStringParseIntegerinDB2`
- x1 `meta::relational::tests::mapping::sqlFunction::stringToDate::testToSQLStringconvertToDateTimeinDb2`
- x1 `meta::relational::tests::mapping::sqlFunction::stringToDate::testToSQLStringconvertToDateinDb2`
- x1 `meta::relational::tests::mapping::sqlFunction::toString::testToSQLStringConcatInDB2`
- x1 `meta::relational::tests::mapping::sqlFunction::toString::testToSQLStringToStringInDB2`
- x1 `meta::relational::tests::mapping::sqlFunction::trim::testTriminNotSybaseASE`
- x1 `meta::relational::tests::query::function::testJoinStringFunction`

### oracle-declined: golden execution — 21 asserts / 21 tests

The oracle tried to execute the GOLDEN and failed. Sub-cases: (a) 12 plan-text goldens (`Relational( type = …`) were handed to the SQL oracle — a referee misroute: these are plan-text asserts and belong to the plan-text arm (the text verdict is right, the reason is wrong); (b) 2 `Schema "productSchema" not found` — the oracle H2 session lacks the fixture schema (referee fixture gap); (c) 2 `LEGEND_H2_EXTENSION_LPAD/SPLIT_PART not found` — the engine registers Java UDFs on its H2; the oracle could register equivalents (referee capability); (d) 4 temp-table goldens — the engine creates the temp table in a prior step the oracle does not replay (`TEMPTABLEFORIN_*`, and a mistyped DATE temp table).

**Disposition:** (a) reclassify to plan-text; (b)(c) referee fixture/capability legs; (d) residue unless temp-table setup is replayed

- x1 `meta::pure::executionPlan::tests::datetime::testPlanForDateTimeConstantParameterESTTimeZone` — Syntax error in SQL statement "Relational (   type = Class[impls=(meta::relational::tests::model::simple::Order [*]| simpleRelationalMapping.meta_relational_t
- x1 `meta::pure::executionPlan::tests::datetime::testPlanForDateTimeConstantParameterGMTTimeZone` — Syntax error in SQL statement "Relational (   type = Class[impls=(meta::relational::tests::model::simple::Order [*]| simpleRelationalMapping.meta_relational_t
- x1 `meta::pure::executionPlan::tests::datetime::testPlanForDateTimeConstantParameterNoTimeZone` — Syntax error in SQL statement "Relational (   type = Class[impls=(meta::relational::tests::model::simple::Order [*]| simpleRelationalMapping.meta_relational_t
- x1 `meta::pure::executionPlan::tests::datetime::testPlanForDateTimeVariableNoTimeZone` — Syntax error in SQL statement "Sequence (   type = Class[impls=(meta::relational::tests::model::simple::Order [*]| simpleRelationalMapping.meta_relational_tes
- x1 `meta::pure::executionPlan::tests::simpleExpression` — Syntax error in SQL statement "Relational\000a(\000a  type = Class[impls=(meta::relational::tests::model::simple::Person [*]| simpleRelationalMappingInc.meta_
- x1 `meta::pure::executionPlan::tests::tdsJoinOneDBOneExpression` — Syntax error in SQL statement "[*]Relational\000a(\000a  type = TDS[(firstName, String, VARCHAR(200), """"), (eID, Integer, INT, """"), (fID, Integer, INT, ""
- x1 `meta::pure::executionPlan::tests::tdsReturn` — Syntax error in SQL statement "[*]Relational\000a(\000a  type = TDS[(""firstName"", String, VARCHAR(200), ""doc1""), (lastName, String, VARCHAR(200), """")]\0
- x1 `meta::pure::executionPlan::tests::testMapWithOpenVariableOutsideBlock` — Syntax error in SQL statement "[*]Relational\000a(\000a  type = Integer\000a  resultSizeRange = *\000a  resultColumns = [(""firmtable_1"".aggCol + 10, """")]\
- x1 `meta::pure::executionPlan::tests::testQuoteIdentifiersFlag` — Syntax error in SQL statement "Relational\000a(\000a  type = Class[impls=(meta::relational::tests::model::simple::Product [*]| simpleRelationalMapping.meta_re
- x1 `meta::pure::executionPlan::tests::testQuoteIdentifiersFlagInGroupBy` — Syntax error in SQL statement "[*]Relational\000a(\000a  type = TDS[(prodName, String, VARCHAR(200), """"), (cnt, Integer, INT, """")]\000a  resultColumns = [
- x1 `meta::pure::executionPlan::tests::testQuoteIdentifiersFlagInOrderByClause` — Syntax error in SQL statement "[*]Relational\000a(\000a  type = TDS[(name, String, VARCHAR(200), """")]\000a  resultColumns = [(""name"", VARCHAR(200))]\000a
- x1 `meta::pure::executionPlan::tests::testQuoteIdentifiersFlagWithGraphFetch` — Syntax error in SQL statement "PureExp(type=Stringexpression=->serialize(#{meta::relational::tests::model::simple::Product{name}}#)(StoreMappingGlobalGraphFet
- x1 `meta::pure::executionPlan::tests::testTypedTDSWithEnum` — Schema "productSchema" not found; SQL statement: select "root"."TYPE" as "type", "root"."NAME" as "name" from "productSchema"."synonymTable" as "root" [90079-
- x1 `meta::pure::executionPlan::tests::testTypedTDSWithEnumFilter` — Schema "productSchema" not found; SQL statement: select "root"."TYPE" as "type" from "productSchema"."synonymTable" as "root" where "root"."TYPE" = 'CUSIP' [9
- x1 `meta::relational::tests::functions::sqlstring::testPad` — Function "LEGEND_H2_EXTENSION_LPAD" not found; SQL statement: select legend_h2_extension_lpad("root".FIRSTNAME, 1, ' ') as "lpad", legend_h2_extension_lpad("r
- x1 `meta::relational::tests::functions::sqlstring::testToSQLStringSplitPart` — Function "LEGEND_H2_EXTENSION_SPLIT_PART" not found; SQL statement: select legend_h2_extension_split_part(legend_h2_extension_split_part("root".FIRSTNAME, '|'
- x1 `meta::relational::tests::projection::filter::testFilterAfterJoinInRelation` — Syntax error in SQL statement "[*]Relational(type=TDS[(name,String,VARCHAR(200),""""),(employeeName,String,VARCHAR(200),"""")]resultColumns=[(""name"",VARCHAR
- x1 `meta::relational::tests::projection::filter::testFilterAfterJoinInRelationWithExtendedPrimitives` — Syntax error in SQL statement "[*]Relational(type=TDS[(name,meta::relational::tests::model::simple::ExtendedString,VARCHAR(200),""""),(employeeName,meta::rela
- x1 `meta::relational::tests::query::filter::in::tempTable::testInExecutionWithTempTableForDateTimes` — Table "TEMPTABLEFORIN_4" not found; SQL statement: select "root".ID as "TradeId" from tradeTable as "root" where "root".settlementDateTime in (select "temptab
- x1 `meta::relational::tests::query::filter::in::tempTable::testInExecutionWithTempTableForNumbers` — Table "TEMPTABLEFORIN_8" not found; SQL statement: select concat("root".FIRSTNAME, ' ', "root".LASTNAME) as "fullName" from personTable as "root" where "root"
- x1 `meta::relational::tests::query::filter::in::tempTable::testInExecutionWithTempTableForStrings` — Cannot parse "DATE" constant "Peter"; SQL statement: select "root".LASTNAME as "lastName" from personTable as "root" where "root".FIRSTNAME in (select "tempta

### our-rows-underivable — 21 asserts / 20 tests

OUR side could not produce rows for the frame, and the error text says why: the rows leg runs our SQL in the test's session, and that session does not HAVE the store's tables — these are plan-only tests (`executionPlan(...)`, `toSQLString(...)`) that never run the store's setup function, so `ProductTable` (milestoningdb), `Product` / `Product_Synonym` (the enumeration db), `Person` (the modelToModel relational db), `dataTable`, `employeeTable`, `"S"."sourceEntitlement"` were never created; DuckDB's "did you mean" names a DIFFERENT store's table that another test seeded. Two more are fixture skews between the store declaration and the seed DDL (`"Trades"."Trade"` declared in schema Trades, seeded as main.Trade; `FULLNAME` referenced by the mapping, absent from the seeded table). Two are M2M model-connection walls (expected). Receipt: the golden SQL for the same tests names the same unqualified tables (`from ProductTable as "root"`, `from Product as "root"`), so our emission matches the store; only the session is empty.

**Disposition:** REFEREE LEG: seed the referenced store before the rows leg of a plan-only test (the family seed ledger knows the setup functions; the store is known from the mapping) — 15 asserts; 2 fixture skews to name; 2 M2M residue.

- x1 `meta::external::store::relational::modelJoins::test::testJoinWithConstantDate` — Catalog Error: Table with name Trade does not exist! Did you mean "main.Trade"?  LINE 2: FROM "Trades"."Trade" AS t0              ^
- x1 `meta::external::store::relational::modelJoins::test::testJoinWithConstantDouble` — Catalog Error: Table with name Trade does not exist! Did you mean "main.Trade"?  LINE 2: FROM "Trades"."Trade" AS t0              ^
- x1 `meta::external::store::relational::modelJoins::test::testJoinWithConstantString` — Catalog Error: Table with name Trade does not exist! Did you mean "main.Trade"?  LINE 2: FROM "Trades"."Trade" AS t0              ^
- x1 `meta::external::store::relational::modelJoins::test::testJoinWithInequalities` — Catalog Error: Table with name Trade does not exist! Did you mean "main.Trade"?  LINE 2: FROM "Trades"."Trade" AS t0              ^
- x1 `meta::pure::executionPlan::tests::tdsWithEnumReturn` — Catalog Error: Table with name Product does not exist! Did you mean "productSchema.ProductTable"?  LINE 2: FROM Product AS t0              ^
- x1 `meta::pure::executionPlan::tests::testExecutionPlanGenerationForInWithVarAndConstantInputs` — Catalog Error: Table with name Person does not exist! Did you mean "PersonTable"?  LINE 2: FROM Person AS t0              ^
- x1 `meta::pure::executionPlan::tests::testIfOpFilterEnumValueWithClassPropInProject` — Catalog Error: Table with name employeeTable does not exist! Did you mean "placeOfInterestTable"?  LINE 2: FROM employeeTable AS t0              ^
- x1 `meta::pure::executionPlan::tests::testModelConnectionMultipleAgg` — class 'meta::pure::mapping::modelToModel::test::shared::src::_S_Person' is not mapped in mapping 'meta::pure::mapping::modelToModel::test::simple::simpleModel
- x1 `meta::pure::executionPlan::tests::testModelConnectionSimple` — class 'meta::pure::mapping::modelToModel::test::shared::src::_Firm' is not mapped in mapping 'meta::pure::mapping::modelToModel::test::simple::simpleModelMapp
- x1 `meta::pure::executionPlan::tests::testTemporalDateVariableAtRoot` — Catalog Error: Table with name ProductTable does not exist! Did you mean "productSchema.ProductTable"?  LINE 2: FROM ProductTable AS t0              ^
- x1 `meta::pure::executionPlan::tests::testTemporalDateVariableInFunctionExpression` — Catalog Error: Table with name ProductTable does not exist! Did you mean "productSchema.ProductTable"?  LINE 2: FROM ProductTable AS t0              ^
- x1 `meta::pure::executionPlan::tests::testTemporalDateVariableInFunctionExpressionWithPropagation` — Catalog Error: Table with name ProductTable does not exist! Did you mean "productSchema.ProductTable"?  LINE 2: FROM ProductTable AS t0              ^
- x1 `meta::pure::executionPlan::tests::testTemporalDateVariableInPropertySequence` — Catalog Error: Table with name ProductTable does not exist! Did you mean "productSchema.ProductTable"?  LINE 2: FROM ProductTable AS t0              ^
- x1 `meta::relational::tests::functions::sqlstring::testToSQLStringNonPrestoSchemaNameShouldNotConvertDollarSign` — Catalog Error: Table with name personTable does not exist! Did you mean "simple.personTable"?  LINE 2: FROM "catalog$schema"."personTable" AS t0
- x1 `meta::relational::tests::functions::sqlstring::testToSQLStringWithPosition` — Binder Error: Table "t0" does not have a column named "FULLNAME"  Candidate bindings: : "lastName"  LINE 1: SELECT substr(t0.FULLNAME, 1, strpos(t0.FULLNAME,
- x1 `meta::relational::tests::functions::sqlstring::testToSQLStringWithStdDevPopulation` — Catalog Error: Table with name dataTable does not exist! Did you mean "LocationTable"?  LINE 2: FROM dataTable AS t0              ^
- x1 `meta::relational::tests::functions::sqlstring::testToSQLStringWithStdDevSample` — Catalog Error: Table with name dataTable does not exist! Did you mean "LocationTable"?  LINE 2: FROM dataTable AS t0              ^
- x2 `meta::relational::tests::m2m2r::testProp1` — Catalog Error: Table with name sourceEntitlement does not exist! Did you mean "information_schema.constraint_table_usage"?  LINE 2: FROM "S"."sourceEntitlemen
- x1 `meta::relational::tests::m2m2r::testProp2` — Catalog Error: Table with name sourceEntitlement does not exist! Did you mean "information_schema.constraint_table_usage"?  LINE 2: FROM "S"."sourceEntitlemen
- x1 `meta::relational::tests::m2m2r::testProp3` — Catalog Error: Table with name sourceEntitlement does not exist! Did you mean "information_schema.constraint_table_usage"?  LINE 2: FROM "S"."sourceEntitlemen

### oracle-declined: enum-decoded — 20 asserts / 20 tests

The golden selects the raw enum CODE; our frame decodes the enum in SQL; the oracle had no per-column decode map (c46 derives it for some mappings, not these).

**Disposition:** REFEREE LEG: derive the decode map from the enumeration mapping for every enum-typed projected column; 20 asserts

- x1 `meta::relational::tests::functions::sqlstring::testToSqlGenerationMonth`
- x1 `meta::relational::tests::groupBy::testAggToManyWithAverage`
- x1 `meta::relational::tests::groupBy::testAggToManyWithFilter`
- x1 `meta::relational::tests::groupBy::testAggToManyWithMaxInteger`
- x1 `meta::relational::tests::groupBy::testAggToManyWithMinInteger`
- x1 `meta::relational::tests::groupBy::testJoinStringsTwiceWithAssociation`
- x1 `meta::relational::tests::mapping::embedded::advanced::otherwiseTestComplexExpressionWithEnumMapping`
- x1 `meta::relational::tests::mapping::embedded::testDenormMappingOneToManyProjectWithEnum`
- x1 `meta::relational::tests::mapping::embedded::testDenormMappingOneToManyProjectWithFilterOnEnumLeft`
- x1 `meta::relational::tests::mapping::embedded::testDenormMappingOneToManyProjectWithFilterOnEnumRight`
- x1 `meta::relational::tests::mapping::multigrain::testProjectPersonWithJoinToAddress`
- x1 `meta::relational::tests::milestoning::businessdate::testMilestoningCriteriaAppliedToSimplePropertyJoinFromTemporalClass`
- x1 `meta::relational::tests::milestoning::businessdate::testPopulationOfMilestonedThisBusinessDatesInProject`
- x1 `meta::relational::tests::milestoning::contextpropagation::testLatestMilestoningFiltersPropogatedToDataTypePropertiesFromAllInProject`
- x1 `meta::relational::tests::milestoning::contextpropagation::testMilestoningFiltersPropogatedFromAllThroughFilterToDataTypePropertiesInProject`
- x1 `meta::relational::tests::milestoning::contextpropagation::testMilestoningFiltersPropogatedToDataTypePropertiesFromAllInProject`
- x1 `meta::relational::tests::projection::function::concatenate::testConcatenateClassMerge`
- x1 `meta::relational::tests::query::function::testDayOfWeekFunction`
- x1 `meta::relational::tests::tds::tdsFilter::testFilterOnEnum`
- x1 `meta::relational::tests::tds::tdsProject::testProjectWithColumnSubSetSQLTest`

### tdg-declined — 11 asserts / 11 tests

Test-data-generation fetch texts reference generator temp tables the oracle cannot replay (charter §5 item 3).

**Disposition:** residue by charter

- x1 `meta::relational::testDataGeneration::tests::testInheritanceMultipleTableJoin`
- x1 `meta::relational::testDataGeneration::tests::testQualifier`
- x1 `meta::relational::testDataGeneration::tests::testSelfJoin`
- x1 `meta::relational::testDataGeneration::tests::testSimpleTwoTable`
- x1 `meta::relational::testDataGeneration::tests::testSimpleTwoTableMultipleStartRows`
- x1 `meta::relational::testDataGeneration::tests::testTableToTDSMultipleJoins`
- x1 `meta::relational::testDataGeneration::tests::testTableToTdsWithJoinAndOLAPGroupBy`
- x1 `meta::relational::testDataGeneration::tests::testTableToTdsWithJoinAndUnion`
- x1 `meta::relational::testDataGeneration::tests::testTableToTdsWithJoinToSameTable`
- x1 `meta::relational::testDataGeneration::tests::testUnion`
- x1 `meta::relational::testDataGeneration::tests::testUnionToUnion`

### plan-param-unbindable — 10 asserts / 6 tests

Plan text with ENUM / collection parameters the rows leg cannot bind to scalar values.

**Disposition:** text contract (plan text with unbindable params)

- x2 `meta::pure::executionPlan::tests::testFilterEqualAndInWithEnumParameter`
- x3 `meta::pure::executionPlan::tests::testFilterEqualAndInWithMultipleEnumParameters`
- x2 `meta::pure::executionPlan::tests::testFilterNotEqualAndNotInWithEnumParameter`
- x1 `meta::pure::executionPlan::tests::testIfEnumParameterInProject`
- x1 `meta::pure::executionPlan::tests::testIfEnumParameterWithClassPropInProject`
- x1 `meta::pure::executionPlan::tests::testOptionalEnumParameterEqualsClassProp`

### oracle-declined: datediff — 8 asserts / 8 tests

The golden projects a distance to now(); two executions at two instants — non-reproducible by definition (named decline, foundation probe 2026-09-01).

**Disposition:** residue by design

- x1 `meta::relational::tests::functions::sqlstring::testGenerateDateDiffExpressionForH2ForDifferenceInDays`
- x1 `meta::relational::tests::functions::sqlstring::testGenerateDateDiffExpressionForH2ForDifferenceInHours`
- x1 `meta::relational::tests::functions::sqlstring::testGenerateDateDiffExpressionForH2ForDifferenceInMilliseconds`
- x1 `meta::relational::tests::functions::sqlstring::testGenerateDateDiffExpressionForH2ForDifferenceInMinutes`
- x1 `meta::relational::tests::functions::sqlstring::testGenerateDateDiffExpressionForH2ForDifferenceInMonths`
- x1 `meta::relational::tests::functions::sqlstring::testGenerateDateDiffExpressionForH2ForDifferenceInSeconds`
- x1 `meta::relational::tests::functions::sqlstring::testGenerateDateDiffExpressionForH2ForDifferenceInWeeks`
- x1 `meta::relational::tests::functions::sqlstring::testGenerateDateDiffExpressionForH2ForDifferenceInYears`

### foreign-dialect Composite — 7 asserts / 7 tests

toSQLString for DatabaseType.Composite — same as DB2.

**Disposition:** honest text contract

- x1 `meta::relational::tests::functions::sqlstring::testCbrt`
- x1 `meta::relational::tests::functions::sqlstring::testPad`
- x1 `meta::relational::tests::functions::sqlstring::testToSQLStringComposite`
- x1 `meta::relational::tests::functions::sqlstring::testToSQLStringWithLength`
- x1 `meta::relational::tests::functions::sqlstring::testToSQLStringWithPosition`
- x1 `meta::relational::tests::functions::sqlstring::testTrim`
- x1 `meta::relational::tests::mapping::sqlFunction::parseInteger::testToSQLStringParseIntegerinComposite`

### oracle-declined — 3 asserts / 3 tests

Column arity differs: the assert compares a sub-query text (`sqlRemoveFormatting(N)` / a filtered-navigation calendar frame) while the frame is the whole query.

**Disposition:** referee: replay the N-th activity; datePeriods = the filtered-navigation lift wall

- x1 `meta::relational::tests::groupBy::datePeriods::testGroupByWithFilterFunction_noDatePath` — column arity differs: golden 10 vs frame 4
- x1 `meta::relational::tests::query::filter::in::tempTable::testInExecutionWithTempTableAndQueryChaining` — column arity differs: golden 1 vs frame 2
- x1 `meta::relational::tests::query::filter::in::tempTable::testInExecutionWithTempTableAndQueryChainingOnIntegerColumn` — column arity differs: golden 1 vs frame 2

### oracle-declined: forced-isolation — 2 asserts / 2 tests

The golden is an engine debug-mechanism (forced isolation) rendering over a VALUE frame.

**Disposition:** residue (engine debug pin)

- x1 `meta::relational::tests::advanced::forced::structure::testQualifierWithOperation`
- x1 `meta::relational::tests::advanced::forced::structure::testTwoQualifiersWithOperation`

### oracle-declined: graph keys — 2 asserts / 2 tests

Graph-fetch key aliases differ between the golden and the frame (milestoned type with a mapping filter: the frame carries extra keys).

**Disposition:** referee: align graph keys by the class's pk properties

- x1 `meta::relational::tests::milestoning::businessdate::testQueryOfMilestonedTypeWithFilterInMapping` — mismatch golden aliases: golden [id, name, type] vs frame [classificationType, id, name, stockProductName, type]
- x1 `meta::relational::tests::milestoning::latestDate::testQueryOfMilestonedTypeUsingLatestWithFilterInMapping` — mismatch golden aliases: golden [id, name, type] vs frame [classificationType, id, name, stockProductName, type]

## Part 3 — what to do, in order

1. **Delete the gate** (batch 37): +36 flips, 0 lost. The shape census that served the gate goes with it; the per-test text-verdict roster stays (it is the ledger for Part 2).
2. **Referee: seed the referenced store for plan-only tests** (15 asserts): the rows leg runs our SQL in a session that never ran the store's setup; seed it from the family ledger before replaying. Plus 2 fixture skews (`Trades.Trade`, `FULLNAME`) to name.
3. **Referee: enum decode maps from the enumeration mapping** (20 asserts, + the 2 walled tests in Part 1b).
4. **Verdict arm: assertSameSQL(String) over a foreign-dialect toSQLString** (3 walled tests) takes the same foreign-dialect text contract the flipped DB2 tests already take.
5. **Referee misroute: plan-text goldens handed to the SQL oracle** (12) — reclassify; **oracle fixture schema** (2); **H2 extension UDFs on the oracle** (2); **N-th activity replay** (2).
6. **Ports:** toSQLString 8-arg and SQLResult 5-arg overloads, Duration (3 tests); H2VERSION probe decision (4 tests).
7. **Named residue (honest text contracts):** foreign dialects 35, plan text with FreeMarker holes 37, plan params 10, TDG temp tables 11, datediff-to-now 8, forced isolation 2, engine-feature text predicates (removeUnionOrJoins 5, alias quoting 2), engine-internal functions 3.
8. **Our emission gaps found by text predicates:** restrict drops unused aggregates (1), parseDate constant spelling (1).
