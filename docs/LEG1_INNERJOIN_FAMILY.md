# classMappingFilterWithInnerJoin family — routing census (2026-07-26)

The feature map's leg-1 (DeferredFilter) assumption for this family is
CORRECTED by census: the 11 errors split into six mechanisms, and the
dominant one is LEG 4 (views as identity-carrying frames), not filter
isolation. The INNER-mapping-filter machinery itself (~filter declared
INNER wrapping the class source) already serves the family's 21 passing
tests.

## Routing

1. VIEWS AS JOIN TARGETS — 5 tests, LEG 4:
   testSourceViewRootQueryWithInnerJoinClassMappingViewFilter,
   testSourceViewPropertyQueryWithInnerJoinClassMappingViewFilter
   (Join 'myFirmView_myPersonView' targets view 'myFirmView'),
   TestClassMappingsWithInnerFilterJoinedWithMilestoningDepthTwoNested
   (ProductViewTrade_Join targets a view),
   testSourceViewPropertyQueryWithInnerJoinClassMappingTableFilter
   (Binder: VALUES t3 lacks 'firmID' — a view frame losing join-key
   columns), and likely testTargetViewPropertyQuery... (supertype
   symptom over a view read). A join whose TARGET is a view must join
   the view's FRAME (subselect) — the leg-4 identity-carrying frame
   discipline; the join-emission currently only targets tables.
2. CROSS-DB JOIN LOOKUP — 2 tests (Join 'PersonSet1AddressSet1' /
   'PersonBicycle' not found in db X): DEEPER than a lookup bug —
   ModelBuilder.findJoin IS include-closure aware and the join IS
   declared in the included db (union::myDB, testUnion.pure:835). The
   included db is a CROSS-FAMILY element (the union family's source
   file); the likely failure is corpus module ASSEMBLY not carrying the
   dependency source into this family's model (the include resolves to
   an unknown db). Route: the harness module-dependency story (#43),
   verify with a findDatabase probe before any code.
3. MERGE CLASS MAPPINGS — 2 tests (employees2/3): the Merge operation
   feature (own track, like union/inheritance ops).
4. TYPE LUB Column-vs-bare — 'no common supertype for
   (address:String[0..1]) and String' (2 symptoms; one may be leg-4
   masked): a NAMED-COLUMN type meeting a bare primitive in a LUB
   position — small typing bug, candidate slice 1 sibling.
5. TypedVariable lowering — 1 test, needs its own diagnosis.

## Slice ladder

S1: Column-vs-bare LUB (diagnose first); the join-lookup pair moved
    to the #43 assembly track (see above).
S2: leg-4 views-as-join-targets (5) — belongs to the leg-4 design
    (frame-as-join-side), not built here.
S3: Merge ops (2) — feature track.

## Filter-position pierced-toOne reads: fold the comparison into EXISTS (2026-07-31)

`filterFunctionExpressionWithOrConditionOnRightTable` (ERROR, DuckDB
"more than one row returned by a subquery"): `Firm.all()->filter(f|
$f.employees->filter(e|$e.lastName=='Lopez' || $e.age>20)->toOne()
.lastName == 'Smith')`. Substitution.filteredNavLeafRead rewrites the
pierced read to a correlated single-column relation rendered as a scalar
subquery — strict pure toOne (raises on >1 match). The engine instead
LEFT JOINs with the inner filter in the ON clause and compares in the
outer WHERE (golden in testFilterWithQualifiedProperties.pure:225, note
the null-guarded OR: `AGE is not null and AGE > 20`); join row
multiplication collapses via PK dedup at the class reader, so the
OBSERVABLE engine semantics in filter position is
`EXISTS(target: assoc-cond AND inner-filter AND leaf-compare)`.

Fix (this leg's hop emission table): when the consumer of a
filteredNavLeafRead is a comparison INSIDE a filter predicate, fold the
comparison into the correlated relation and emit the exists family
instead of a scalar-subquery compare. Projection position keeps the
scalar subquery (engine multiplies rows there; our scalar read is the
row-stable equivalent). Needs predicate-context awareness at the
Substitution comparison site — do NOT bolt onto the lowerer.

## Qualifier -> class-hop -> qualifier chains (testQualifierWithIsolation pair, 2026-07-31)

`testQualifierWithIsolation`/`testQualifierWithIsolationXX` (ERROR
"extend/project columns [firm] reference names unresolvable even after
isolation [col='firm' ref='firm']", Lowerer.computedColumns:1144 via
scalarRelationalArms — a nested relation in scalar position): the
projection arithmetic crosses `$f.employeeByLastName('Smith2').firm
->toOne().employeeByLastName('Smith3').age->toOne()` — a FILTERED
qualifier, then a CLASS hop (firm), then ANOTHER filtered qualifier.
filteredNavLeafRead's hop peeling handles class hops between leaf and
filtered head, but a second qualifier AFTER the hop leaves a TypedProject
binding whose 'firm' column self-references (never materialized as a
join). Engine golden (testQueryStructure.pure:237): four chained LEFT
JOINs to filtered subselects — persontable_0(Smith), persontable_2
(Smith2), firmtable_1 (the hop), persontable_4 (Smith3) — with the CASE
arithmetic reading across them. Fix shape: the hop chain must register
each qualifier segment as its own filtered-subselect join descriptor and
re-key the continuation on the joined alias (this leg's hop emission
table), not re-enter filteredNavLeafRead per segment. Same machinery as
#70 chained/deep filtered navigation.

## Milestoning long-tail witnesses (2026-07-31, cycle-12 triage — Leg 2 owned)

- `testMilestoningContextPropagatedThruPropertyToViewWithNonMilestonedRoot`
  WRONG ANSWER (priority): expected `[1,Joe Martinez, 1,Joe Martinez,
  2,TDSNull]`, got `2,John Martinez` — the business-date context must
  propagate through the property INTO the view frame (engine filters the
  out-of-date version to NULL; we return the raw view row). Leg 2
  milestone: temporal context threading across ViewFrames (the ON-clause
  seam memory: temporal conds ride the resolver join-condition channel).
- `testMultiLevelIsolatedToSubSelectHasCorrectExtraColumns` ERROR: typing
  wall in mapping body — Boolean property
  `isBrexitClassificationTypeExchange` bound to an if-expression typed
  String (normalizer/typer if-branch unification in mapping bindings,
  milestoningmap2). Typer bucket, not temporal calculus.

## Leg-2 view-propagation trace state (2026-07-31, cycle 12 — three ruled-out sites)

`testMilestoningContextPropagatedThruPropertyToViewWithNonMilestonedRoot`
(WRONG ANSWER, `Trade.all($bd)->project($o.tradePnl.supportContactName)`,
milestoningmap3): our SQL joins the view (SELECT DISTINCT over
tradePnlTableNoMilestoning JOIN tradeTable JOIN salesPersonTable) with NO
temporal condition on milestoned salesPersonTable; engine stamps
from_z/thru_z inside the view (per-TABLE-ALIAS filtering).

RULED OUT (patched, no effect, reverted): (1) TemporalFrame.stampForClass
strat==null interior-scan arm — stampForClass/stampForClassOrDefer are
NEVER CALLED for this hop (LL_VIEW_DEBUG print silent); (2)
NavMaterializer:322 physCtx fallback (slotCtx->inherited) around
filterMilestonedJoinTargets — also no effect (either `inherited` empty
there or hasMilestonedSlotTarget false for the view pipe, or the hop
materializes via a different route entirely). ALSO: contextAt returns
TemporalContext.NONE for non-milestoned target classes (targetStrat null
skips every arm) — by design for CLASS stamping, but physical-table
stamping needs the inherited date regardless.

NEXT TRACE: instrument the resolver entry for THIS hop (which path
materializes property 'tradePnl' of milestoningmap3 — AssociationJoins
route? CorrelatedSubselects? the ViewFrame distinct materialization in
Pipelines?) with env-gated prints, THEN thread the root context (root
TemporalContext must be reachable — TemporalFrame.root exists) into the
view pipe's milestoned scans via replaceScan/tableHasBlock (mechanism
proven at TemporalFrame:1371).

## Milestoning/tests residue classification (2026-07-31, cycle 15 — 17 items, family 207/224)

- PENDING-USER sql-only advisory (8): testDateFunctionInMilestonedProperty(+WithMilestonedEntity),
  testQueryOfMilestonedTypeUsingLatestWithFilterInMapping, testLatestIgnoredForNonMilestonedMapped
  (BiTemporal|)ClassesAllQuery, testBusinessDatePropagationInColFunction_asQueryParam,
  testLatestMilestoneDateMappedTableDate..., testLatestMilestoneDatePropogationFromTypeQuery... —
  all "sql-only: advisory golden-SQL, no row verification"; the advisory-upgrade policy question
  decides them.
- PLAN-SURFACE leg (L1): testProcessingTemporalPropertyQuery + PropagationInQuery (pre-existing
  FAIL; diff inventory vs golden: bare '2015-10-16' constant vs our DATE'...' in the k_ projection;
  k_processingDate output naming vs our processingDate; select 1 spelling; ALSO the engine golden
  keeps correlated exists here while ExistsJoinForm converts — the engine chooser distinguishes
  cases we don't yet; revisit the chooser when this pair's other spellings are fixed);
  testExecutionPlanForQueryWithVariableRundateWithinLambda (plan text);
  testViewChainsWithBusinessDate (toSQL(...).toSQLString(connType, timeZone, quoteIdentifiers,
  ^Format(newLine, indent)) member-call surface — runner/exec vocabulary).
- Leg #80/#70 (isolation/OR): testIsolationOfMilestoningFiltersUsedOnIntermediateJoinInOR.
- Leg #30/#32 (nested-nav temporal): testBusinessDateInjectionFromVarReferenceInProjectUsing
  ExternalFunction ("milestoned property access on a NESTED navigation").
- Leg #71 (subType): testMilestoningContextIsPropogatedThroughSubType (multi-hop through stc_
  embedded/slot head).
- #32 two-dates family: testMilestoningQueryWithMilestoneFilterAndDifferentDatesOnTypeWith
  LatestDateOnProperty.
- Semi-structured feature: testMilestoningFilterApplicationOnSemiStructuredRelationalOperationElements.

## executionPlan/tests residue classification (2026-07-31, cycle 17 — 57 items, family 53/110)

- M2M2R LEG (~13 here + 6 testModelConnection* plan walls + modelToModelToRelational/milestoned
  0/7 = ~26 corpus-wide, THE biggest coherent remaining feature): planProjectWithDerivedProperty(1)/
  planProjectWithNested(Derived)Property/planGraphFetchWith(Nested)DerivedProperty/
  executeProjectWithNestedDerivedProperty (7 SHAPEs — planToString($query, $mapping, $runtime)
  3-arg spelling over ModelToModelMapping chained onto relational; the golden COLLAPSES the chain:
  engine composes m2m bindings into one SQL, e.g. fullName = concat(firstName,' ',lastName));
  + 4 'plan wall: class meta::pure::mapping::modelToModel...' assert-form rows. Design: H-plan H5
  recursive substitution — resolve the inner getAll, substitute, binding tables compose by
  β-transitivity.
- Plan-surface vocabulary/spelling (~15): 2 PureExecutionStrategy nodes (CreateAndPopulateTempTable/
  RelationalInstantiation), 2 'no overload of executionPlan', 2 SQLExecutionNode.connection
  property, ~10 plan-text diffs (Sequence/Relational/RelationalBlockExecutionNode spellings; known
  sub-rules: DATE literal in k_ projections, k_ naming, select 1, quoted-schema sql-text).
- Cross-db relation-from pair (feature); ExecutionOptionContext unknown-class (vocabulary);
  4 null-reason rows (re-itemize on next sweep); 2 expected-true/false asserts (investigate).

## M2M2R cycle-19 triage (2026-07-31; executionPlan 56/110 after chain threading)

- NEXT SLICE (nested m2m nav, 3 tests: planProjectWithNestedProperty,
  planProjectWithNestedDerivedProperty, executeProjectWithNestedDerivedProperty):
  `$x.details.firstName` walls at Substitution:~1627 — head binding is a
  WHOLE-SOURCE TypedNewInstanceCast (`details : $src` in PersonPeterSmith),
  not the slot-read cast CastNav serves. Design: at Substitution
  CONSTRUCTION (StoreResolver.substitution, which holds `sources` + `cs`),
  pre-register whole-source cast heads (binding = TypedNewInstanceCast
  whose source unwraps to the bare src var) into the assocs registry as a
  SAME-ROW AssocSub: prefix = identity/no-join, targetRowVar = the cast
  target's composed ClassSource rowVar (ClassSources.get(mapping, castFqn)
  — the frame-identity guard in CastNav.leafSource applies: composed
  target must share the row var), targetBindings = the composed binding
  table. Then the existing H5c arm (`target.assocs().containsKey(head)`
  -> assocLeaf) dispatches. Review assocLeaf's prefix handling for the
  no-join case first.
- graphFetch-over-m2m pair (planGraphFetchWith(Nested)DerivedProperty):
  'class query under TypedGraphFetch not resolvable' — leg #84 (H4/H5c
  graph channel).
- executeProjectWithNestedDerivedProperty additionally needs the
  generateAndExecutePlan helper vocabulary (execute-with-setup spelling).
- modelToModelToRelational 2/5 + milestoned 0/7: not yet itemized —
  next probe.

## modelToModelToRelational itemization (2026-07-31, cycle 21; family 2/5 + milestoned 0/7)

- testProp3 FAIL (nearest): TWO engine-text spelling sub-rules — (1) float
  literal in COMPARISON context spells bare `0.0` (our EngineStyleH2
  spells cast(0.0 as float) unconditionally; the cast spelling was pinned
  from other goldens — the rule is CONTEXT-DEPENDENT, investigate before
  changing); (2) the whole then/else arithmetic wraps in one extra paren
  group `(((a*b)/c)*d)`. Plan-surface spelling bucket.
- testProp2: unknown function meta::pure::mapping::withChainedMappings
  (library vocabulary — register/parse the helper).
- testProp4: executionPlan overload signature gap.
- milestoned 0/7: meta::legend::executeLegendQuery overloads (legend
  query API surface) + graphFetch-over-m2m milestoned (legs #84/#81) +
  TargetProductMilestoned class walls. Feature-track heavy; classify
  with graphFetch tracks.
- planProjectWithDerivedProperty1 already PASSES (not in failure lists).
- graphFetch-over-m2m pair (planGraphFetchWith(Nested)DerivedProperty):
  leg #84 ('class query under TypedGraphFetch not resolvable', H2 vocab).

## withChainedMappings state (2026-07-31, cycle 23 — native + FromChecker landed, uncommitted w/ next slice)

DONE (uncommitted): Pure.WITH_CHAINED_MAPPINGS native (engine
Handlers:2223 signature, T[*] simplification per the from() precedent);
FromChecker absorbs source->withChainedMappings([maps])->from(rt) into
TypedFrom.chainMappings (collectMappingRefs walk) and strips the node.
testProp2/3 moved past the unknown-function wall to: "executionPlan
mapping argument must be a reference (or the query must carry ->from)" —
the query's from() carries ONLY runtimeWithoutChain(); the MAPPING must
dispatch through the query-side chainMappings. NEXT SLICE: in
StatementExecutor.planToString's 2-arg/dummy-mapping arm
(firstFromMapping null path), also look for the first TypedFrom's
chainMappings + root getAll class and pick the chain binder (the
ClassSources.binds dispatch rule) as mappingFqn; thread the same chain
into engineSql/PlanText (c18 plumbing). ALSO pending:
assertEqualsH2Compatible/3 harness arm (either-golden matches;
TestBody:1753/1773 has /2 partial) — testProp3 needs it after the
mapping dispatch lands. testProp4 = executionPlan overload (unread).

## Chain-binder dispatch state (2026-07-31, cycle 24 — uncommitted, plans generate)

DONE (uncommitted): planToString's null-mapping path dispatches the root
class through the query-side chainMappings binder (ClassSources.binds,
exactly-one rule) via new firstFromChainMappings helper; queryChain
unions into the engineSql/PlanText chain threading. testProp2+3 now
GENERATE plans (FAIL on text, no walls). Remaining testProp2 diffs:
(1) connection = RelationalDatabaseConnection(type = "H2") — the query's
from(runtimeWithoutChain()) INSTANCE runtime carries the connection
class name, but FromChecker drops the instance (only chainMappings/
jsonSources extracted); fix = extend the same extraction with a
connectionName hint on TypedFrom (schema change: T2.2 all-fields rule,
~5 construction sites: FromChecker, SyntheticHeads:475/996,
StatementExecutor executes) and have planToString prefer it over the
TestDatabaseConnection default; (2) one trailing empty-line diff (check
PlanText.single terminal newline). testProp3 additionally needs the
assertEqualsH2Compatible/3 harness arm. testProp4 = executionPlan
overload (unread).

## L6 itemization (2026-07-31, cycle 27)

- CONSTRAINTS/VALIDATION biggest bucket (5): "aggregate sum over a
  to-many navigation in FILTER position" — validateComplexValidation4/7 +
  testValidateQueryWithMilestoningAndAggregation{All,Single,SingleAnd
  NestedDynaFunction}. Shape: filter(x| $x.<toMany>.<col>->sum() > N).
  Engine: correlated scalar subquery (select sum(col) from target where
  assoc-corr) compared in WHERE — aggregates return exactly one row, so
  no multi-row hazard (unlike the pierced-toOne case). Design: route
  filter-position to-many aggregates through the SAME correlated-
  aggregate machinery the projection position uses (#77 parent-copy /
  CorrelatedSubselects AggRead registry) — the wall text says "the
  aggregate demand scan did not recognize this shape", i.e. the demand
  scan skips filter lambdas for agg reads. Adjacent singles:
  isDistinct-reducer variants (validateComplexValidation9/10).
- graphFetch: executeLegendQuery 4-arg overloads (4, legend query API
  surface); parseJSON/alloyConfig unknown fns (4, vocabulary);
  assertJsonStringsEqual/2 (harness assert form); no-execute (3).
- lineage: 9 of 12 = sql-only advisory (PENDING-USER policy!); 3 real
  (scanColumns joinTree diff, class-typed whole-value read, Vehicle
  inheritance mapping).
- testDataGeneration: scattered singles (tableToTDS join side,
  stc multi-hop, assertSize sqls 5v4, assert forms).
- m2m2r/milestoned 0/7 (c21): executeLegendQuery overloads +
  graphFetch-over-m2m milestoned (legs #84/#81) + TargetProductMilestoned
  walls — leg-owned.

## Validation family closed-out state (2026-07-31, cycles 28-29 — 19/23)

Cycles 28-29 landed the L6 top bucket: filter-position to-many
aggregates route through the agg demand registry (aggScanFilters feeds
aggDemands ONLY — bare paths from filter bodies are DISCARDED, they
belong to memberScan's implicit-EXISTS route), and filter-position
demands emit the PARENT-COPY grouped subselect without a where
(engine BuildCorrelatedSubQuery copies the root tree into the
isolation subquery: firmTable's duplicate ID=1 rows double the
aggregated collection — validateComplexValidation10's constraint8
golden (1,1) pins the difference; the target-grouped shape saw 4
distinct concat values and returned no violations). Position split at
CorrelatedSubselects.splitAggGroups; projection demands on the same
head keep the target-grouped shape.

Remaining 4 (all ERROR walls, leg-owned):
- validateComplexValidation2 (LegalEntity c2): `exists(...) ||
  filter(e| $e.addresses.location.street == $this.address.location
  .street)->isNotEmpty()` — filter-position TypedFilter whose predicate
  reads an OUTER nav chain ($this.address.location.street). Needs the
  correlated-EXISTS emission with outer-nav parent-copy inside the
  exists subquery (Leg 1 isolation-chooser rung; the ExistsJoinForm/
  exists machinery has no outer-nav channel yet).
- validateComplexValidation3 (Firm c1): DOUBLY-nested filters over two
  to-many navs (filter(a| ...employees.addresses->filter(b| $a.… ==
  $b.…)->isNotEmpty())->isEmpty()) — nested correlated EXISTS where the
  inner predicate correlates BOTH lambda vars. Same Leg-1 rung, one
  level deeper (exists-in-exists with cross-correlation).
- validateComplexValidation5 (Firm c3): full TDS pipeline INSIDE a
  constraint (employees->project(...)->groupBy(...)->filter(...)->
  tdsRows()->isEmpty()) — object-space TypedProject/TypedGroupBy over a
  to-many nav in value position. A per-instance correlated TDS subquery
  family of its own (engine builds a nested plan); not a spelling gap.
- validateComplexValidation6 (Firm c4, mapping2): employeesAddresses =
  @Firm_Person > (INNER) @Address_Person multi-hop join PM; the
  value-position filter's leaf 'locationStreet' reads the target's own
  join slot (Address ~filter INNER @Address_Location + locationStreet
  via slot) — slot-demanding leaves under value-position filters
  (chained-nav Leg #70 rung).

## Cycle-30 bucket triage (2026-07-31)

- aggregationAware/test/rewrite/NOP (5, one cause): all read
  `$result.activities->filter(instanceOf AggregationAwareActivity)
  .rewrittenQuery` — the Result frame's ROUTER-ACTIVITY surface. Our
  frame has no activities => the collection lowers NULL =>
  struct_extract(NULL) binder error. Needs: activities on the Result
  surface + AggregationAwareActivity with the engine query-printer
  rewrittenQuery text (' | [SCT_Main Class Wholesales].all();').
  Router-metaprogramming family — leg-owned, not a spelling gap.
- graphFetch alloyConfig (2): vocabulary is trivial (pure-source
  overloads over ^AlloySerializationConfig) but BOTH corpus usages pass
  includeObjectReference=true — the opaque store-reference token
  envelope (H4/#84 machinery). Vocabulary alone gains 0; leg-owned.
- graphFetch executeLegendQuery 4-arg (4): the queries are
  PARAMETERIZED lambdas ({processingDate, businessDate | ...}) —
  elqSplice only handles zero-arg. Extending the splice = bind pairs as
  date lets; but the queries behind it are XStore graphFetch milestoned
  (m2m2r/milestoned legs #84/#81) — they move to the next wall, gain 0
  today. Splice extension worth doing WITH that leg.
- tests/mapping/enumeration (4 FAILs): D
  (testTdsProjectWithEnumToStringEqualityComparison) is a REAL value
  bug: project->project(col(if(getEnum('Type') == 'FTE'...))) — the
  first project ISOLATES (computed CASE decode), so the outer equal
  sees a subselect column and EnumSourceValues.decodeInvert (which
  already implements the engine's raw-compare rule, C1.4) cannot see
  the chain. Engine folds both projects into ONE select (golden:
  `case when "root".type = 'FTE' ...` — no subselect). Fix seam: fold
  policy (resolveInto for computed cols consumed once?) OR typed-level
  pre-lowering inversion. A/B/C (ProjectionWithEnumThroughAssociation,
  IfWhereOneSideIsEnumLiteral, IfWhereBothSidesUseTheSameEnumMapping)
  look like ROW-ORDER permutations of correct multisets (H2-vs-DuckDB
  scan order) AND are H2-replay-blocked by the enum-decoded-column
  Unverifiable arm — verify multiset equality per test, then either
  in-SQL-decode unblocks replay or document as scan-order.

## Cycle-32 closeout (2026-07-31)

- Enumeration trio DOCUMENTED not-implementable (H2 scan order,
  replay-proven — see NOT_IMPLEMENTABLE.md); family fully triaged at
  18/26 (3 scan-order, 2 vocabulary walls genericType/relation::TDS,
  2 SHAPE no-execute, 1 runtime-dispatch ambiguity).
- tests/mapping/inheritance normalize bucket (4): NOT a coherent rung —
  genericType/_classMappingByClass = M3-reflection vocabulary;
  testSubTypeFilter = H4 whole-value graph output (#84);
  testEmbeddMappingInSubTypes + testMilestonedSubTyping pair +
  testForcedSubTypeProjectDirect = association-into-subtype-mapping
  resolution (leg #71 subType dispatch).
- Leg #70 residue recount: the 24 TypedFilter walls are down to 7 —
  2 slot-demanding leaves (testChainedInnerJoinsWithQualifierInGroupBy,
  validateComplexValidation6), 2 correlated/nested constraint filters
  (validateComplexValidation2/3), testGroupByWithFilterFunction_noDatePath
  (open-variable Allocation-plan machinery, executionPlan leg),
  testQualifiedPropertyUsingColumnProtocol, + validateComplexValidation5
  (TDS pipeline in constraint). Each is a DIFFERENT rung; no shared
  slice remains under #70's original framing.

## Cycle-33 design state: processing-temporal pair (2026-07-31, in flight)

testProcessingTemporalPropertyQuery + PropagationInQuery [milestoning]
are sql-text FAILs sharing THREE engine divergences (class frame =>
no H2 row rescue; byte diff is the verdict):
1. EXISTS FORM: engine emits correlated `exists(select 1 ... and
   "root".kerberos = ct.kerberos and ct.in_z <= DATE'..' and ct.out_z >
   DATE'..')` for the MILESTONED exists target; our ExistsJoinForm
   (SQL-level, plainShape gate) fires and emits the join-distinct form
   (temporal conds land as ordinary local conjuncts — indistinguishable
   at SQL level). GROUND FIRST: read engine buildExistsPredicate vs
   buildExistsAsJoinWithNullCheck gates (pureToSQLQuery L5607-5749) —
   is the choice temporal-driven or localness-driven? Then thread a
   provenance marker (temporal-stamped target => skip ExistsJoinForm);
   candidate seams: a frame marker on the exists subselect (mirror
   EXISTS_KEYS_FRAME) stamped where the exists arg's pipeline carries
   TemporalFrame stamps, checked in ExistsJoinForm's plainShape gate.
   Tightening is SAFE for passing tests: engine never join-distincts
   milestoned targets, so no byte-match test can depend on it.
2. CARRIER NAME: engine projects the temporal date constant as
   "k_processingDate" (k_ prefix); ours spells "processingDate".
   Find our carrier-column naming site (likely TemporalFrame /
   GraphEmission pk emission) and adopt the engine spelling — grep
   engine for 'k_processingDate' / 'k_businessDate' to ground.
3. LITERAL SPELLING: engine spells the PROJECTED date constant as bare
   string '2015-10-16' (comparisons stay DATE'2015-10-16') — same
   projection-vs-comparison split as the c22 bare-float rule; land in
   EngineStyleH2 next to FloatLit.
Order: (2)+(3) are dialect/naming spellings, low risk, land first and
re-diff; (1) is the meaty gate change. Family expectation: +2 direct;
watch the whole milestoning + advanced families for exists-form blast.
