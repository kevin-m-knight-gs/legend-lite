# FULL RESIDUE CENSUS — 2026-08-30 (step 0, row-by-row, no sampling)

USER DIRECTIVE: before burning anything, bucket every single
decline/mismatch/sql-exec residue row 1-by-1 — real homework, no
sampling, no guessing — and write down everything learned. This doc
is that census. Companion: docs/METAMODEL_MACHINERY_CENSUS.md (the
machinery lay-of-the-land) and PROGRAM_MAP § "DEFERRED PROGRAM —
METAMODEL AS DATA".

## 0. Method + reproducibility

- Instrument: `CanonicalDivergence.v7Declined` now records a
  PER-ROW witness `test :: form :: reason` for every
  non-exec-passing decline (UNCAPPED — census surfaces carry no
  silent caps; the witness reason cut is 500 chars vs the 200-char
  aggregation key, so walk contexts and callees survive).
  Reproduce: full sweep, `grep '^\[v7\] decline-witness'`.
- Population measured: **417 rows** = declined 323 + text-only 44 +
  unable-to-exec 50, over 305 distinct tests. Scoreboard
  byte-identical to the handoff baseline
  (agree=3381 disagree=9 declined=323 / 1495 / 44 / 50 / csv 0)
  across BOTH instrument sweeps — the instrument observed, never
  perturbed.
- Every bucket below: rows verified by reading the named tests /
  registers, not by trusting reason strings. Corrections to prior
  prose are flagged **CORRECTED**.

## 1. MISMATCH LANE (dual-channel disagree) = 9 — VERIFIED, frozen

Witnesses match docs/VERDICT_DISAGREEMENT_BURN_2026_08_30.md
receipts R1–R8 exactly, no drift:
testSimpleTypeMapping ×3 + testSimpleTypeMappingProject ×3
(Decimal-scale ×4, nine-digit temporal ×2), testDayOfMonth ×1
(TDS-lane temporal), testDeepUnionOperationWithNonTemporalAndNon
UnionRoot ×1 (TDSNull row-string), testConcatenateWithJoin ×1
(sort-tie, the phantom class). EXACT-pinned; movement = user ruling
only.

## 2. SQL-EXEC LANE, unable-to-exec = 50 — VERIFIED per row

| sub-reason | rows | tests (complete) | verified cause |
|---|---|---|---|
| chained fetch (TDG temp tables) | 26 | testDataGeneration::tests: testInheritanceMultipleLevel ×3, testInheritanceMultipleTableJoin ×2, testQualifier ×2, testSelfJoin ×2, testSimpleTwoTable, testSimpleTwoTableMultipleStartRows, testTableToTDSMultipleJoins ×3, testTableToTdsWithJoinAndOLAPGroupBy, testTableToTdsWithJoinAndUnion ×2, testTableToTdsWithJoinToSameTable, testUnion ×4, testUnionToUnion ×4 | engine's own chained-fetch anatomy (temp table per hop); burn = TDG charter §S5 (design complete) |
| projection differs | 2 | testTableToTdsWithConcatenate ×2 | our fetchCols demand (firstName,id) vs engine (id,legalName) — §S5 names generateRelationColumnMap as spec |
| predicate-diverged | 6 | testChainedUnions, testProjectThroughAsso, testProjectThroughAssoWithJoinInMapping, testUnionWithSinglePropertyMapping, testUnionOnViewsMapping, testRestrictOnGroupByEleminatesUnnecessaryAggsWithDistinct | emission-anatomy leg (handoff correction stands: NOT by-design; align emission) |
| both-ours | 5 | testMilestoneDatePropogationThruExists…, …ThruFilter…, testQualifierFunctionConsistencyWithComplexTypeProperty, …WithDataTypeProperty, testSimpleSliceZeroSameAsTake | both sides are OUR text (no engine golden to referee) — adjudicated residue |
| no-generator-noreplay | 3 | testQualifier (tdg), testGroupByWithFilterFunction_noDatePath, testBuildFilterWithValueThatCanBeNullPlanSql | no generator/golden replay available |
| column arity | 2 | testInExecutionWithTempTableAndQueryChaining, …OnIntegerColumn | golden 1 col vs frame 2 — tempTable statement-pairing residue |
| forced-isolation | 2 | testQualifierWithOperation, testTwoQualifiersWithOperation | §4AD named defects (P1 restores) |
| graph-keys | 2 | testQueryOfMilestonedTypeWithFilterInMapping, …UsingLatestWithFilterInMapping | golden assembly aliases vs frame roster (multi-statement stitch shape, declined by design) |
| row-cardinality skew | 1 | testQualifierQueryWithOr | §4AD dedup divergence (distinct rows agree) — slice-2 scope |
| enum-decoded column | 1 | testDayOfWeekFunction | post-transform rows vs golden |

## 3. SQL-EXEC LANE, text-only = 44 — VERIFIED per row

| sub-reason | rows | verified cause |
|---|---|---|
| plan-literal | 17 | parameterized-plan text compares (open variables / optional params / temporal variables: testFilterEqualsWithTwoOptionalParameters* ×4, testTemporalDateVariable* ×4, testGroupByWithOpenVariable* ×2, testProp3 ×2, testClassPropertyOpenVariable, testExecutionPlanForQueryWithVariableRundateWithinLambda, testPlanGenerationForInWithCollectionParameterHavingTimeZoneForH2, testFilterEqualsWithOptionalParameterDate, testBusinessDatePropagationInColFunction_asQueryParam) — plan-TEXT lane |
| BARE (no sub-reason) | 2 | **CORRECTED**: testFilterAfterJoinInRelation(+WithExtendedPrimitives) — `assertSameSQL(<plan-literal>, planToStringWithoutFormatting(...))`; the plan path never sets SQL_TEXT_OUTCOME so the sub-reason is empty. SAME class as plan-literal (→ 19); the missing sub-reason is a small honesty fix |
| plan-let | 6 | alloy plan-let bindings — plan-TEXT lane |
| no-generator-noreplay | 9 | nothing executed, no generator |
| no root exec variable | 7 | actual arg carries no exec handle |
| match-noreplay | 3 | texts match, replay unavailable |

Handoff LANE-4 prose said "17 + 3+3 plan-let, 7+2+2 no-generator,
5+2 no-root-exec, 2 match-noreplay" — **CORRECTED to the measured
19/6/9/7/3** (the prose double-counted sub-splits).

## 4. DECLINED LANE = 323 — the full bucket census

### 4a. Metamodel-only (the QUARANTINE set) — 144 rows

**The census MOVED the quarantine boundary** (three findings the
aggregate table hid):

| class | rows | witnesses + verification |
|---|---|---|
| mapping/store reflection (no-scalar) | 46 | classMappingById 21 (extends: 4 mainTable-handle assertEquals + testSuperSetIdsAreCollected + 16 PK-name string asserts), rootClassMappingByClass 13 + view 6 + inferRelationalType 5 (typeInference tests — all compare strings vs dataTypeToSqlText), _classMappingByClass 1 (testSubtypeMapping:70 assertSize) |
| toPostgresModel conversion family | 36 | newState 18 + **ONE-STAMP 17** + SQLNull-layout 1 — ALL in toPostgresModel::tests::testConvert*. **CORRECTED**: the stamp bucket is NOT an independent bug leg — all 17 witnesses fire on callee=toOne inside these same metamodel-conversion chains; zero witnesses outside the family. (Flag kept: if the stamp program ever sees a NON-quarantine witness, it repromotes to a bug leg) |
| function-body reflection | 55 | pkOfFunc 43 (one private helper: `$func.expressionSequence` + inferPrimaryKeyColumnNames tree-walk; fixture bodies never execute — file says so); **bare-lambda 10 + unknown-type-InstanceValue 2** = tesIsToOneDataTypeFunctionExpressionSequence* ×3 tests — **CORRECTED**: these two buckets are `deactivate()->cast(@InstanceValue)` expression-tree reflection, same class as pkOfFunc, NOT a lambda-statement feature gap |
| expressionSequence walls filed under host-unsupported | 2 | testGraphFetch (graphFetch/domain: extractDomainTypeClassFromFunction), testPreprocessFunctionOnRuntime — **CORRECTED** out of host-unsupported into this quarantine class |
| extension-eval (connection equality) | 5 | routerExtensions 5 = testConnectionEquality* via runRelationalRouterExtensionConnectionEquality: `relationalExtensions().routerExtensions()` + `match` over extension-provided connectionEquality LAMBDAS + eval over CONSTRUCTED RelationalDatabaseConnection instances. Metamodel/extension evaluation, no store data — proposed IN the quarantine (user to confirm) |

### 4b. h2-lane vocabulary (owned elsewhere) — 67 rows

TypedMap wall 65 (37 executionPlan tests, relationalMapper 10,
tdsUnion 6, datetime 6, modelJoins 4, projection::union 1,
execution 1 — every row the same wall string) + TypedGraphFetch
walls 2 (planGraphFetchWith[Nested]DerivedProperty, filed under
host-unsupported) — h2-session convergence leg, not resolver work.

### 4c. Active burn — 112 rows

| bucket | rows | verified mechanism |
|---|---|---|
| getAll unresolved | 76 | **ONE MECHANISM, not ~20 shape families** (**CORRECTED** vs handoff guess): every walk context is `root > TypedNativeCall(×N) > TypedLambda > <pipeline>` — getAll inside a LAMBDA argument of an unresolved native chain, overwhelmingly `executionPlan({params\|Class.all()->…})` with plan PARAMETERS (optional/enum/collection/DateTime params, open variables, tdsJoin multi-DB, m2m2r chains: 49 of 65 tests in executionPlanTest.pure, 4 legacyNullUnsafeEquals, m2m2r 5, singles in sort/isEmpty/tdsExtend/graphFetch-milestoning). The dual-channel splice's resolver does not traverse lambda-under-native-call. ONE design leg |
| host-unsupported (remaining) | 24 | per-row causes pulled from the scoreboard SHAPE rows, every row named: plan walls 7 (star-top TDS ×1, alias-subselect ×1, planToString-no-getAll-root multi-node ×3, over-overload ×1, executionPlan-mapping-arg shape ×1); metamodel PROPERTY registration gaps 6 (StoreMappingGlobalGraphFetchExecutionNode.children/.localGraphFetchExecutionNode ×3, TableTDS.store, Any.type, EngineRuntime.preprocessFunction); unported fn/class 4 (exampleExternalFormatExtension ×2, unknown class Service ×2); mapping walls 4 (m2m not-mapped ×2, inheritance no-class-mapping ×1, modelJoins association not-mapped ×1); XStore graph node ×1 (children property — counted in property gaps); assertIs/2 form 1 (testStoreSubstitution); crossDb plan ×1 |
| plan-text lane misroutes | 8 | relationalExtensions 8 — **CORRECTED attribution**: testExecutionPLanGenerationForFrom* ×3, testCrossDbPlanGenerationWithFromWithoutExternalMapping, testExecutionPlanGenerationForLambdaFromWithEnumMapping, testTwoMappingsOneRuntime ×2, m2m2r::testProp2 (NOT the testUnion/testRelationalResultSourcing sites first guessed). All are `assertEquals(<plan-text literal>, planToStringWithoutFormatting(executionPlan(..., relationalExtensions())))` — the probe dies lowering the extensions arg before the sql-text partition can claim the assert. Burn = lane classification |
| unported platform fn | 2 | toPrettyJSONString (graphFetch subType tests) — small port |
| trailing-JSON | 2 | graphFetch milestoning testMilestonedRootAndMilestonedProperty ×2 — JSON assert arg parse; investigate with the graph lane |
| resolver defects | 2 | testMultiExpressionWithPlatformAndFromFunction (filter predicate '<whole variable>' unresolvable), m2m2r::testProp4 (extend/project name resolution) — real bugs, small |

### 4d. Reconciliation

46+36+55+2+5 (quarantine 144) + 67 (h2-lane) + 112 (active) = 323 ✓
Full 417-row raw table reproducible from any sweep via the witness
instrument; per-test tables above are complete (no "misc", no
"mostly").

## 5. WHAT THE CENSUS CHANGES (deltas to prior plans)

1. Quarantine grows 107 → **144** (pkOfFunc was already agreed in;
   ONE-STAMP 17, tesIsToOne 12, expressionSequence-host 2 join it;
   routerExtensions 5 proposed in).
2. ONE-STAMP is DEMOTED from "first burn leg (bug)" to
   quarantine-co-located (all witnesses inside toPostgresModel
   chains; repromote on any outside witness).
3. getAll-76 is ONE design leg (lambda-under-native splice
   resolution), not a census of ~20 families — it becomes the
   clear top of the active burn by tests-per-design.
4. relationalExtensions-8 burn = sql-text lane classification (as
   §4AE S3/S4 said), with corrected witnesses.
5. Active decline backlog after quarantine + h2-lane: **112 rows**
   (getAll 76, host-unsupported 24, plan-text misroutes 8, small
   ports/bugs 6 minus overlaps as tabulated).
6. text-only prose corrected to measured 19/6/9/7/3; the BARE-2
   sub-reason gap is a named small fix.
7. Machinery lay-of-the-land: docs/METAMODEL_MACHINERY_CENSUS.md
   (the five evaluation-bearing side doors + the duplication map).

## 6. Proposed burn order (pending user sign-off)

1. getAll-76 (one resolver design leg, biggest tests-per-design).
2. host-unsupported registration gaps (property/function ports —
   ~12 rows of mechanical registration).
3. plan-text classification 8 + BARE-2 sub-reason honesty fix.
4. resolver defects 2 + toPrettyJSONString 2 + trailing-JSON 2.
5. Plan walls 7 + mapping walls 4 (feature legs, rank after
   census of their families).
6. sql-exec lane: TDG §S5 (26+2) then emission-anatomy 7 (6
   predicate + 1 skew); both-ours/graph-keys/arity residue
   re-adjudicated after.
7. Quarantine 144 + h2-lane 67: deferred programs, untouched.

## 7. WALL-DEEPENING SLICE (same day, user-directed): unknown-name
## masks LIFTED — the registrations and what they revealed

**The move:** every distinct unknown-FUNCTION name across the corpus
registered as a SIGNATURE (declarations only, verbatim from the
reference .pure sources; zero evaluation added), so first-failure
masks lift and the census shows each test's REAL next wall.
Registered (9): routerExtensions (+ RouterExtension class,
router_extension.pure:22, connectionEquality with its real function
type), router::printer::asString (printer.pure:43), withMapping
(mappingExtension.pure:386), enumerationMappingByName
(functions_Mapping.pure:19, + EnumerationMapping class,
mapping.pure:40), enumValues (legend-pure enumValues.pure:18),
toJSON Any[*] (toJSON.pure:54), toPrettyJSONString,
tds getNumber (tds.pure:83), TableAlias.relation()
(relational.pure:211). PLUS one signature FIX:
relationalExtensions() return tightened Any[*] → Extension[*]
(the spec's own type, extension.pure:62 — the routerExtensions
receiver typing demanded it). Governance: class pin 209→211,
package whitelist +meta::pure::router::extension, property-surface
rows, catalog golden regenerated (diff = exactly the new lines).

**Measured outcome (three sweeps, before/after):** ZERO verdict
flips — corpus 2356/2575 byte-identical per family, every lane pin
unchanged (agree=3381 disagree=9 declined=323 / 1495/44/50/0);
`unknown function` occurrences across the whole sweep: 9 → 0. The
masked rows' REAL walls, now on the record:

| was | now (the honest next wall) |
|---|---|
| routerExtensions ×5-6 | auto-map gap: `routerExtensions` argument 1 multiplicity [*] vs [1] — the helper maps a qualified-property native over Extension[*] (pure auto-map); confirms the quarantine class (extension-lambda eval behind it) |
| toPrettyJSONString ×3 | `unknown class 'JSONArray'` — the meta::json node-class family is the next registration set |
| withMapping ×2 | `lowering not yet implemented for TypedNativeCall ('withMapping' in relation position)` — a REAL routing-marker lowering gap, named |
| enumValues ×2 | `no scalar lowering registered` — honest catalog wall |
| toJSON ×1 | `no scalar lowering registered` — honest catalog wall |
| getNumber ×2 | `no overload of 'col' matches 2 argument(s)` — the tds outlier tests' true blocker is a col() overload gap, NOT getNumber |
| relation ×1 | `argument 1: expected TableAlias, got V` — a typing question at the call site, investigate with its family |
| asString ×1 | `unknown function 'routeFunction'` — the router-test machinery goes deeper (more unported router functions behind it) |
| exampleExternalFormatExtension ×2 | NOT registered — a TEST function from the external-format module; module-scope fact, same class as the mft finding below |

**Firm/Person diagnosis (census §4's flag) — CLOSED, not a
resolver bug:** the 13+5 `unknown class 'Firm'/'Person'` rows are
GLOBAL element walls on mft-package mappings
(tests/mft/*): they bind `meta::pure::mft::tests::collection::Firm`,
defined in engine-CORE's MFT module
(core/pure/test/mft/collectionTests.pure) — OUTSIDE the corpus
walk. Honest dropped-base-element walls. Named future fix:
register the MFT collection model as library sources (the
m2m-test-library precedent in the runner) — its own slice, since it
changes the corpus denominator.

## 8. SLICE Q LANDED; B1 MEASURED AND REVERTED (same day)

**Slice Q (quarantine partition):** `metamodel-quarantined=142`
split out of `declined`, EXACT-pinned, vocabulary = the production
system's own refusal spellings (CanonicalDivergence
METAMODEL_QUARANTINE; the 2 expressionSequence rows that decline
under the bare host-unsupported marker stay in that bucket — their
quarantine ownership is documentary, §4a).

**Slice B1 — BUILT, MEASURED, then REVERTED (user catch: the flat
`plan-producer` sub-reason flattened 141 reason-diverse rows into
ONE coarse label — worse bucketing than the walls it replaced; a
lane's classification lands WITH that lane's fix, never before).
The MEASUREMENT is permanent knowledge:**

- With plan-bearing asserts claimed for the text partition:
  declined 181 → 40; text-only 44 → 185; agree/disagree/
  exec-passing/unable/csv/quarantine BYTE-IDENTICAL.
- The 141 plan-bearing decline rows: getAll walls **76** (ALL of
  them — the §4c "one resolver leg" is measured EMPTY: every
  getAll decline is a plan-bearing assert), relationalExtensions
  **8**, TypedMap plan-bearing **55**, in-plan resolver-defect
  messages **2** (testMultiExpressionWithPlatformAndFromFunction,
  m2m2r testProp4).
- **The TRUE non-plan decline residue = 40**: TypedMap 10
  (h2-lane), host-unsupported 26 (per-row causes §4c), JSONArray 2,
  trailing-JSON 2.
- These numbers live here as MEASUREMENT; the shipped census still
  counts the 141 under their original wall reasons until the
  plan-text lane's fix lands with proper per-shape sub-reasons.

**HONESTY NOTE (user challenge on the record: "are we actually
fixing anything?"):** Q, B1, and the registration slice are
ACCOUNTING — zero rows became verified. The verification-gaining
work list from here: the plan-text lane (~160 plan rows now
honestly bucketed, LANE-4 charter owed), TDG §S5 (26+2), the
emission-anatomy 7, host-unsupported capability gaps, and the
small ports (col() overload, withMapping lowering, JSON node
family). "Burned" = agree/exec-passing went UP.
