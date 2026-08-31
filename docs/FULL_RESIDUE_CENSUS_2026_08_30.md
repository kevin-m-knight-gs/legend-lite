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

## 9. PROVISIONING MEASUREMENT (engine-rule residue, 2026-08-30)

QUESTION (user): the engine provisions its shared DB purely via
package-chain BeforePackage setups (self-sufficient by authorship —
fromMapping::setUp calls query::setUp, receipts in testFrom.pure).
If that rule alone provisioned our corpus, the residue should be
ZERO — anything else would mean hidden compensation somewhere.

METHOD: env-guarded experiment patch (reverted after): disable the
two SCAN-driven provisioning layers (module-declared DDL +
cross-family setup pulls), keep shared zero-arg setups + the test's
own package-chain BeforePackage functions. One full sweep.

RESULT: corpus 2,358 → 2,348 — **residue = 10 tests**. FIRST-PASS
attribution below was then REPLACED by a test-by-test verification
(user: no guessing, no sampling) — see the corrected table after it.

- **9 are OUR extra verification, not engine compensation**:
  calendarAggregations ×4 (testDifferentCalendar/EndDates,
  testDynaEndDate/Input — calendar tables) + query-family ×5
  (testConcatenateWithPost/PreFilteredGroupBy,
  testFilterBeforeAndAfterGroupBy/Project, testLimitFilterInSequence)
  all fail as `sql-text` diffs — i.e. their ADVISORY row-check could
  not run (tables missing) and the golden-SQL diff became the
  verdict per the scoring rule. In the ENGINE these are text-only
  compares that never touch a table — the engine rule never needed
  to provision them. The demand is created by OUR advisory
  row-verification lane (a verification the engine does not do).
- **1 execution dependency to adjudicate**: testSpecialUnion_m2m2r
  (graphFetch/union) ERRORs on a missing catalog/table — either an
  engine order-freeloader (shared world provided it by suite order)
  or a setup-semantics difference on our side; single-test read
  owed at the leg.

### §9a TEST-BY-TEST VERIFICATION (all 10, receipts read)

The first-pass story ("9 = missing calendar/query tables") was
WRONG in its mechanism — the per-test reading found the truth is
narrower: TWO mechanisms, both schema-existence-only, no foreign
data anywhere, no engine freeloading anywhere.

| tests | verified mechanism | need |
|---|---|---|
| calendarAggregations ×4 (testDifferentCalendar, testDifferentEndDates, testDynaEndDate, testDynaInput) | their own setUp DOES create LegendCalendarSchema + both calendar tables on the DuckDB side (testCalendarFunctions.pure:102, dropAndCreateSchemaInDb + dropAndCreateTableInDb) — execution was fine. What died was the H2 ADVISORY MIRROR: `seed replay: Schema "LEGENDCALENDARSCHEMA" not found` — at baseline the module-DDL layer's recorded creates gave the mirror its schemas. With the advisory row-check dead, these 4 tests' STANDING dialect text divergences (our `NULL` casing + `group by "root".hireType` vs golden `group by "hireType"` — present at baseline too, advisory-tolerated) became the verdict | H2-mirror SCHEMA existence only |
| testConcatenateWithPostFilteredGroupBy, testConcatenateWithPreFilteredGroupBy, testFilterBeforeAndAfterGroupBy, testFilterBeforeAndAfterProject, testLimitFilterInSequence | identical mechanism: mirror `seed replay: Schema "CONCATENATE" not found`; same standing-divergence-surfaces-as-verdict scoring | H2-mirror SCHEMA existence only |
| testSpecialUnion_m2m2r | inline `testDataSetupCsv` (TEST_SCHEMA.PEOPLE/PEOPLE2/FIRMS, testUnionRootLevel_relational.pure:697) — the harness's inline-CSV lane DELETE+INSERTs over tables it expects to exist; baseline existence came from module DDL. The ENGINE'S OWN inline-CSV lane creates model-derived tables on a fresh test database — so providing them is engine-PARITY, not compensation | model-derived schema+tables for the inline-CSV lane |

CORRECTED DESIGN CONSEQUENCE: the lazy hook shrinks to two
model-derived DDL provisions at known seams — (1) the H2 mirror's
replay creates schemas/tables for the stores in scope (referee
lane, our extra verification); (2) the inline-CSV lane creates its
model-derived tables (engine-parity). No demand-walker, no foreign
data, no dependency management, no setup inference. SIDE FINDING
for the dialect ledger: the 8 sql-text tests carry standing
NULL-casing / group-by-alias divergences that only the advisory
lane's tolerance hides.

VERDICT: the engine does NOT do extra compensation — its rule is
genuinely self-sufficient for its own verification model. Our
richer verification (advisory row-checks over golden SQL) is what
demands extra tables. ARCHITECTURE CONFIRMED WITH RECEIPTS:
provisioning = the engine rule (package-chain setups) + a LAZY
store-triggered hook at the one execution-setup seam (the
metamodel-store seed precedent, generalized) covering the referee
lane's demands and the 1 residual — replacing both scan-driven
layers. No demand-walker needed: the resolver knows the stores
before SQL runs.

## 9b. ROOT-CAUSE DRILL COMPLETE (user-driven, "no guessing"):
## residue 10 → 0 — THREE REAL BUGS, ZERO new provisioning machinery

The user's challenge ("engine H2 syntax failing on our H2 means
something is wrong on our side") was CORRECT three times over. All
three defects share one invariant violation — the advisory mirror
is a REPLAY, so the recording must be a faithful transcript of what
the session executed — and all three were masked by the module-DDL
guessing layer:

1. **Suppressed schema creates** (StatementExecutor
   dropAndCreateSchemaInDb K-arm): executed `Create schema if not
   exists` on the session but recorded it METADATA-ONLY, editing a
   corpus-authored statement out of the replay ledger. One
   suppressed create per family POISONED the family mirror; the
   poison replayed to every later advisory-dependent test (the
   calendar 4 + filter-combo 2 healed on this fix alone). FIXED:
   record on both channels.
2. **Unwired inline-CSV creation half** (seedInlineCsv →
   CsvSeed.sqls(csv, null, ctx)): the model-derived DROP+CREATE
   branch EXISTED in CsvSeed but the call site passed dbFqn=null,
   degrading every block to bare DELETE. FIXED: the CSV pairs with
   its ConnectionStore's element ref (exact-candidate import
   resolution, never suffix), CsvSeed emits CREATE SCHEMA IF NOT
   EXISTS for qualified tables, and the statements are RECORDED
   (transcript fidelity). Heals testSpecialUnion_m2m2r; serves all
   84 inline-CSV usages uniformly; engine-parity (their lane
   creates model-derived tables on a fresh DB).
3. **Mirror cursor aliasing** (H2Verify.applyPendingSeeds +
   the tempTableForIn extras): per-verify synthesized statements
   were appended to a LOCAL copy of the ledger, so mirror.applied
   counted entries the shared ledger does not contain — every later
   verify in the family replayed MISALIGNED (a synthesized INSERT
   without its CREATE). FIXED: extras thread separately
   (verifyAuto/verify extraSeeds), execute AFTER the cursor-applied
   ledger on every verify, drop-first for re-runnability; a failing
   extra declines its own verify instead of poisoning the mirror.

MEASURED END STATE: baseline byte-stable (all pins, scoreboard
identical, BASE_EXIT=0); package-chain-only provisioning now passes
**2,358/2,575 — EXACT pass parity with baseline, residue ZERO**.
The surviving declaration-driven seam is ONE: the inline-CSV lane
creating its declared tables. NAMED RESIDUE for the deletion leg:
under the experiment flag, exec-passing is 1,495 vs 1,497 — two
assert rows' verification strength still depends on a
guessing-layer statement; drill them when the deletion lands (the
flag is an experiment instrument, re-derivable from this doc, and
was REVERTED, not shipped).

## 10. SLICE-1 DELETION INVENTORY + THE FULL HARNESS-REMOVAL ROADMAP
## (homework, measured line ranges — the design doc for the deletion leg)

§9b's zero-residue proof (now zero at the ASSERT level too — the
2-row delta was the numbered tempTableForIn synthesizer path
missing drop-first; fixed, PKG sweep passes ALL pins) de-risks the
following deletions in Runner.java (2,725 lines today):

### 10a. Delete outright (proven by the experiment)
| what | lines | notes |
|---|---|---|
| `moduleDdl` + `DdlUnit` | 1755–1810 (56) | the model-derived DDL guessing layer |
| `ddlScopeDbs` + `currentDdlDbs` | 1688–1732 (45) + field | consumers: moduleDdl (dies with it) and `moduleColumnKinds` :1952 (the fixture-skew census's scope — re-scope that instrument to EXECUTED DDL or its own walk; never delete the instrument silently) |
| crossRefs layer in `replaySeeds0` | ~35 of 148 | + `familyCrossDone`, `preflightResolvable` 2339–2389 (51) |
| `executeMappingRefs` | 862–1020 (159) | the name-scan — WITH the two replacement jobs in 10b |
| `tryRunNoExecute` + demand-pull retry + the SHAPE gate | 1126–1204 (79) + run0 branches | tests just run; walls carry the compiler's reason |
| `collectCalledFqns`, `qualify` (scan-only uses), the withMapping discovery arm, seed-trace debug | ~40 | |

Total ≈ **450–500 lines** plus run0 simplification (178-line method
loses its guess branches). Everything KEPT: authored-setup
execution (package-chain, outermost-first), the inline-CSV seam,
family sessions + seed ledger, the mirror (now transcript-faithful),
all census instruments.

### 10b. The two replacement jobs (the real design content of slice 1)
1. **Overlay mappings** (`rtMappings` — the runtime overlay that
   drives class-query dispatch) is the scan's last real output.
   Replacement: read the mapping refs off the TYPED body against
   the BASE global context (proven overlay-independent — the
   overlay is consumed only by the resolver), at one seam. This is
   discovery-from-typed-surfaces scoped to API data only — NOT
   provisioning. (The deeper engine-shape alternative — per-call
   contexts built from call-site args — is Phase C material.)
2. **Session-conflict routing**: `ddlConflictsWithSession`
   (2098–2115) iterates moduleDdl and must be re-based. Census
   first: count today's conflict-routed tests; then either re-base
   on EXECUTED live shapes (familyLiveShapes) or adopt the engine's
   grouping semantics outright (package-grouped tests + drop/create
   setups make clobber safe by construction; inline-CSV keeps its
   private-session rule).

### 10c. The full harness-removal program (updated for today's facts)
- **Phase A** — this slice (10a+10b).
- **Phase B** — burn the remaining lanes to zero with the dual
  channel refereeing throughout (the ratified anti-drift order):
  plan-text lane (~160 plan-bearing declines + the 23 plan rows in
  text-only), TDG §S5 (+28), emission-anatomy (+7),
  host-unsupported capability gaps (24), h2-vocabulary (10+65),
  metamodel quarantine 142 (user-gated). Each lane completion
  DELETES its harness classification/adjudication arm — burn = 
  deletion fuel, never bucket movement.
- **Phase C** — single-channel cutover AT ZERO: EngineTestExecutor's
  host evaluation dies wholesale (measured biggest members: compare
  151, Eval 76, purifiedSetup 56, goldenEqualScalar 50, clgArm 45,
  etaExpandWrapper 44, enumDriverLoop 42, driverPairLoop 33, the
  subst/splice family) — every assert is a platform verdict, the v7
  dual-channel census retires because one channel cannot disagree
  with itself.
- **Phase D** — referee endgame: H2Verify remains the ONE declared
  external referee (or converges per the h2-session leg);
  ObjectRefs dies with the host lattice; Runner reduces to the four
  honest verbs — discover (stereotype walk), provision (authored
  setups + CSV seam), run (platform), score/report (+ census
  instruments). Harness rows in the Java-eval ledger: ZERO.

### 10d. JOB-1 HOMEWORK VERDICT (measured, 2026-08-30)

The call-site mapping ALREADY flows engine-style: ExecuteChainAssembly
validates execute()'s mapping argument and SYNTHESIZES it onto the
query as a TypedFrom node (chain(), :231); ClassSources.dispatch is
explicit-mapping-FIRST — the ambient overlay's mapping list is
consumed ONLY in the no-explicit-mapping fallback
(findRuntime(runtimeFqn).mappings(), exactly-one-binder).

FALLBACK CENSUS (one instrumented sweep, reverted): **90 firings,
19 distinct tests, all via rcorpus::Rt** — extend-family
propertyMappings tests (model::A..K), inheritance subTypeFilter
tests (RoadVehicle/Vehicle), scanColumns::testSubType. Slice-1 job 1
is therefore: read those 19 (why does their class query reach
dispatch without explicit context — note the ENGINE answers subtype
dispatch from the ONE explicit mapping via its routing strategy, so
runtime-fallback here may be a compensation), convert them to
explicit context, then DELETE the fallback branch + the overlay's
mapping list + the scan that feeds it. Bounded, attributed, no
unknowns.

### 10e. WHY THE ENGINE HAS NO JOB 2 (receipts in §9/§10 reads)

Job 2 guards a CONTRACT the engine deliberately does not have. In
the engine, a test's world is DEFINED as "what my own package's
setup just built": setups are destructive (drop+create), wired
immediately before their package's tests, so same-named
different-shape tables never coexist — order IS the semantics, and
a sequentially-programmed suite needs no conflict detection. The
price they pay: no per-package isolation/reproducibility on the
shared DB (their one isolation grant is the inline-CSV fresh
database). We kept per-test reproducibility (scoped runs must
mirror the sweep) and a replay referee (bounded family ledgers) —
contracts need a guard, hence our router. Post-deletion the router
re-bases per the §10b census (live shapes vs full ordering
semantics; we already re-run package setups within family sessions,
so the gap to the engine model is small).

### 10f. JOB-2 ROUTER CENSUS (measured, 2026-08-30) — the answer to
### "do same-family tests carry same-named tables with different shapes"

One instrumented sweep (reverted): the conflict router fired for
**2,380 distinct tests — ~92% of the runnable corpus — routing them
ALL to private sessions**, and 2,379 of the 2,380 events are ONE
table name: `persontable` (1,836 LIVE-CLOBBER + 543 MODULE-SHAPE;
1 automobiletable; 0 reached the inline-CSV check — the conflict
short-circuits first). So: YES, same-named different-shape tables
are real within family scope — the corpus deliberately defines many
personTable variants — but the "conflict" is measured against
MODULE-DECLARED shapes, i.e. the guessing layer being deleted
manufactures almost all of it. CONSEQUENCES: (1) the family-session
sharing lever (#112) is effectively DISABLED for ~92% of tests
today — per-test seeding is why seed.replay dominates G4 (~88s);
the deletion leg is a correctness AND speed win; (2) the engine
faces the same persontable multiplicity and never notices — each
package's setup rebuilds it just-in-time (ordering semantics we
already run inside family sessions). JOB-2 VERDICT: adopt the
engine model — delete module-shape conflict checking with the
module-DDL layer, keep the inline-CSV private rule, read the single
automobiletable live-clobber witness at implementation before
deciding whether any live-shape guard survives.

### 10g. SHARING-SAFETY DIFFERENTIAL (measured, 2026-08-31) —
### per-package workspaces are SAFE; the four-layer proof

Question: today every mutating/conflicting test gets a private fresh
workspace; per-package workspaces (§10f design) share one DuckDB per
test package. Does any test OBSERVE different data under sharing?

Method (all temp instruments, reverted after the measure):
1. **Mutation census** (`LL_MUT_CENSUS`, body-level walk over each
   test fn for executeInDb/dropAndCreate*/createTablesAndFillDb/
   loadCsv/loadValues calls): **59 mutating tests in 7 packages**.
   53 are `testDataGeneration` (createTablesAndFillDb — self-seeding
   by design); the rest: ddl natives (3), loadCsv (2, own tables),
   graphFetch isolation (2), filter::in temp-table (1), map (1).
   The engine runs ALL of these in its ONE suite-wide shared H2 —
   per-package sharing is strictly tighter than the spec's topology.
2. **Flag + full-sweep verdict parity** (`LL_PKG_WS`: session restart
   at package boundary, unconditional sharing except inline-CSV,
   package-chain provisioning only — no moduleDdl, no crossRefs):
   scoreboard IDENTICAL to baseline on every lane pin
   (agree=3383 disagree=9 exec-passing=1497 text-only=44 unable=50
   csv=0 declined=181 quarantined=142) and the decline-witness SET is
   byte-identical. Only deltas: side-row histogram (side-channel
   queries shift with provisioning) and fixture-skew census 473→459
   columns (it keys off the deleted moduleDdl bookkeeping — §10a
   already requires re-scoping it, never blinding it silently).
3. **Result-digest differential** (`LL_RESULT_DIGEST`: per-test
   rolling digest of every SELECT/WITH result, row-multiset-hashed).
   2351 vs 2346 digest rows; 264 differ, decomposed:
   - **257 rows: read-COUNT shift only** — demand-pull/try-run probe
     SELECTs that private routing issues and sharing never does.
     Provisioning accounting, not observed data.
   - **5 tests read-silent under sharing** — decline/plan-lane tests
     (e.g. testSupportStreamFlag* TypedMap wall) whose only baseline
     reads were probe queries. Verdicts identical.
   - **7 rows same-count, different digest** — the only candidates:
     * 3 fetchDbMetaData tests: they SELECT information_schema —
       topology-observing by definition; engine's shared suite DB
       observes MORE; asserts pass under both topologies.
     * 4 union aggregation tests: same SQL hash returns the same two
       digest VALUES in swapped call order.
4. **Witness attribution — flap test**: the same baseline command run
   twice flaps exactly those union tests to exactly the "divergent"
   values (unordered string-concat aggregation; row-order
   nondeterminism inside list_aggregate). NOT a sharing effect.

VERDICT: zero sharing-caused observation changes. Per-package
workspaces land with slice 1 as the only topology (flag removed);
the fetchDbMetaData family is the one topology-observing package to
keep an eye on when the topology changes again.

### 10h. SLICE-1 LANDING RECORD (2026-08-31) — job 1 threading fixes,
### per-package cutover, guessing-layer deletions

**Job-1 fixes (main tree, all conform-by-threading — the call site's
own mapping reaches every consumption path):**
1. `StoreResolver.spineContext` — the synthetic-heads canonicalizer
   and the `genericType().rawType` reflection arm captured the ENTRY
   context; both now fold in-chain `from()` contexts down the source
   spine (the stale capture was the extend/inheritance fallback
   cluster, 21 of the 29 firings).
2. `Compiler.lowerResolved` gained an explicit-mapping overload;
   `LineageForm` threads the scanColumns call's own mapping (bare
   names qualify through the import scope) — 7 firings.
3. `StoreResolver.routedContext` — the generic walk resolves
   `execute()`/`executionPlan()` arguments under the CALL'S routing
   context (mapping arg + runtime arg's chain mappings, helper calls
   inlined once).
4. `JsonSourceFrame.fromContext` — an instance-runtime `from()` (no
   mapping ref, no runtime ref) DROPPED `fr.chainMappings()`;
   `ClassSources.dispatch` gained the null-explicit chain arm
   (query-side withChainedMappings dispatch) — the last 2 firings
   (m2m2r testProp2/4).
5. `ClassSources.dispatch`: the ambient runtime-candidates block is
   DELETED — a class query with no mapping context is a loud wall.
   Fallback census: 90 firings/19 tests before, ZERO after, all
   pins intact.

**Runner deletions (−704 lines net):** executeMappingRefs name-scan,
TryRun/tryRunNoExecute/unknownTypePull/demand-pull retry,
SHAPE gate (tests just run; walls carry the compiler's reason),
dominantNamespace, moduleDdl+DdlUnit, ddlScopeDbs+currentDdlDbs,
ddlConflictsWithSession router, familyDdlShapes, crossRefs layer +
familyCrossDone, preflightResolvable + the separate setup-universe
module (setups run in THE global context), seed-trace debug, the
rtMappings overlay scan (`globalContext()` = ONE memoized overlay,
empty mapping list). Per-package workspaces are the ONLY topology
(flag removed). Fixture-skew census RE-SCOPED to every database in
the global model (measurement only, never blinded).

**Two reconciliations the referee caught (both landed):**
- 15 filter::in tests went exec-passing → unable: the now-running
  H2Test's raw SELECT probes rode the family seed ledger and failed
  H2 replay for siblings. Fix: the inherited history is STATE only
  (the #67 contract's own words — "seeds and mutations"); reads are
  filtered at the ledger handoff. exec-passing=1497 restored.
- metamodel-quarantined 142 → 107+20 walls: the toPostgresModel
  family (20 tests, 35 witness rows) now fails at the TEST level
  (same texts, thrown before per-assert adjudication).
  `CanonicalDivergence.noteWall` counts them through the SAME
  vocabulary; the partition's test set is unchanged. Pins moved
  107 (witness rows) + 20 (wall tests).

Sweep wall-clock: ~62s (was ~156s) — the §10f prediction (~6× fewer
seeding runs) realized.

### 10h-addendum: gate-chain reconciliation receipts (2026-08-31)

- **G1 (947 errors, fixed)**: the dispatch wall was over-broad — a
  MODEL-DECLARED runtime's mapping list (unit fixtures'
  test::TestRuntime) is real engine API and dispatch by it is
  restored verbatim; what stays deleted is the HARNESS feed
  (rcorpus::Rt's overlay list is EMPTY now, so a corpus query with
  no threaded mapping walls loudly on zero candidates).
- **Guardrails (4, all fixed as designed)**: temp trace env flags
  deleted; spineContext/routedContext/contextKey extracted to
  resolver/RoutingContext.java (StoreResolver back under the
  3,500-line ceiling); QUARANTINED_WALL_TESTS registered
  (measurement-only static).
- **G4 fixture-skew ceiling 473 → 782**: the census re-scope's wider
  honest denominator (whole-model declared side), same instrument.
- **G5 h2 floor 1347 → 1329, walls 983 → 993**: the h2 lane's 20
  toPostgresModel passes were HOST-adjudicated via the deleted
  try-run lane (never platform verification) — 10 now wall on
  registered renderer gaps, 10 error at lowering. Worktree receipt:
  HEAD full h2 sweep = 1349 vs this tree 1329; the delta is EXACTLY
  the quarantine family. (The stale h2-base.log tdg=62 reference was
  Aug-29 code; HEAD itself measures tdg=29 — no tdg movement in this
  slice.)
