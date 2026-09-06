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

Status: **batch 111 / T1 restrict over a distinct groupBy LANDED (2026-09-06)** — testRestrictOnGroupByEleminatesUnnecessaryAggsWithDistinct flipped (123/2450): the engine's unused-aggregate drop under a whole-row distinct. TEXT 43 → 42.

Status: **batch 110 / L5 XStore over lossy table-backed ends LANDED (2026-09-06)** — testPersonToFirmUsingFromProject + testCrossMappingWithRelOpWithJoinKeys flipped (124/2449); route A (property space) for lossy column views, authored operand order, binding-aware condition demand. IMPL 11, REVISIT 7. Open on route A: typed local reads (ordering comparisons over +props), the exists-context target substitution.

Status: **batch 109 / L1 embedded ctor as a SubNav node LANDED (2026-09-06)** — testToManyWithQualifierWithFilterOnJoin flipped (126/2447); the embedded-head trio is CLOSED. IMPL 13, REVISIT 7.

Status: **batch 108 / L1 subtype-only class-typed joins lift under their stc key LANDED (2026-09-06)** — testInheritanceMultipleLevel flipped (127/2446); UnionSynthesis lifts a subtype-only Join PM as a navigate slot keyed stc_<Sub>___<prop>. IMPL 14, REVISIT 7.

Status: **batch 107 / L1 subtype cast in auto-map source position LANDED (2026-09-06)** — testRoutingWithSubtypePropagation flipped (128/2445); the demand scan composes a cast-sourced auto-map through the one funnel. IMPL 15, REVISIT 7.

Status: **batch 106 / L1 isolation (element-scoped tail predicate) LANDED (2026-09-06)** — isolationTest flipped (129/2444), both asserts; IMPL 16, REVISIT 7. USER RULING (same day): the resolver's string-keyed path model is a debt — a NavPath/Hop leg runs AFTER Phase 1 is burned out and BEFORE Phase 2 (user re-ruling, same day; memory string-hacking-audit-navigation-paths).

Status: **batch 105 / L13 tdsToJSONKeyValueObjectString LANDED (2026-09-06)** — executeProjectWithNestedDerivedProperty flipped (130/2443); planGraphFetchWithDerivedProperty reclassified TEXT; two REVISIT receipts (test6, testCheckedWithCircularConstraints); testPksWithImportDataFlow PARKED. IMPL 17, REVISIT 7.

Status: **batch 104 / L2 sub-aggregation in a fan-out mapper LANDED (2026-09-06)** — both testSubAggregationWithDeepAndOverlap tests flipped (131/2442); L2 closed (3/3). testNonDataTypeProperty PARKED (H4 whole-value class column). IMPL 19, REVISIT 5.

Status: **batch 103 / L8 rowValueDifference LANDED (2026-09-06)** — rowValueDifferenceTest flipped (133/2440); L8 closed (12/12). IMPL 21, REVISIT 5.

Status: **batch 102 / L8 groupByWithWindowSubset LANDED (2026-09-06)** — testGroupByWithWindowSubset flipped (134/2439): a store-handled function desugared by the store's rule. IMPL 22, REVISIT 5.

Status: **batch 101 / subtype-cast slot = property ownership LANDED (2026-09-06)** — no ratchet move (135/2438); the batch-100 slot probe replaced by the model fact (a property declared on the navigated class reads the plain slot; subtype-only reads the suffixed one). IMPL 23, REVISIT 5.

Status: **batch 100 / CLEANUP LANDED (2026-09-06)** — no ratchet move (135/2438); the audit of batches 87–99 acted on (GATES batch 100). USER RULE 2026-09-06: a traced golden disagreement is a **REVISIT** row (`revisit:<name>` in AssertLedger), never a resolved one — the five REVISIT items (batch 93 instance-filter-ungated; batch 96 relation-mapping-filter-alias-root ×2; the union relation pair's CSV round trip) are decided at the end of the burn. IMPL stays 23.

Status: **batch 99 / L1 chained filters in filter position LANDED (2026-09-06)** — testChainedFiltersQuery flipped (135/2438): `filter(f | $f.employees->filter(..).locations->filter(..).place != 'New York')` — the filtered sub-navigation already rode the employees join's SubNav; the negation-isolation null-guard picked the 2-segment class node as its crossing read. **batch 95 / L1 subtype cast over a member union + lifted slot LANDED (2026-09-06)** — testForcedSubTypeProjectDirect flipped (139/2434): `$r->subType(@Bicycle).person.name` — the union lifts each member's class-typed Join PM as ONE plain navigate slot (`person`, per-member routes, member-suffixed keys), the cast canon reads it by its plain name, and the 2-hop read through the witness filter canonicalizes to a guarded read in the lift pass. **batch 94 / L1 chained aggregate behind a to-one head LANDED (2026-09-06)** — testFilterTimesWithManyOperands flipped (140/2433): `sum($p.firm.employees.age)` registers under the dotted chain key `firm.employees` with a depth-2 tail mapper (the STUDY #12 class the wall named); `times([$a, 2, $b, 100])` renders as the `*` chain. A first cut over-reached (it caught `joinStrings` over an embedded to-one head and regressed two aggregationAware goldens) — narrowed to the eliding reducers. **batch 93 / L1 instance-filter canon LANDED (2026-09-06)** — no flip: `$x->filter(o | pred)` over the [1] instance canonicalizes in the lift pass (SyntheticHeads.descend: the parameter is the instance — its milestoned qualifier's temporal spec and slot demand register), which takes testBusinessDateInjectionFromVarReferenceInProjectUsingExternalFunction to its sql-text ROW verdict; the rows differ by the engine's ungated join-back projection → registered engine-golden-defect:instance-filter-ungated (NAMED, receipt in AssertLedger and the charter). **batch 92 / L1 map fusion LANDED (2026-09-06)** — testProjectThroughAssociationAutoMap flipped (141/2432): `map(xs, t | f).leaf` / `map(map(xs, t | f), u | $u.leaf)` fuse to `map(xs, t | f.leaf)` in the lift canon (SyntheticHeads.fuseLeafOverClassMap) — the substitution's map composition would otherwise splice the whole receiver chain for the element and the correlated predicate would read `$b.trades.d`. **batch 91 / L1 TDS.csv LANDED (2026-09-06)** — testEnumInRelation flipped (142/2431): the TDS class's `csv` property over an executed relation cast to `TDS<Any>` renders in the database. **batch 90 / L1 mapper-scoped filtered navigation LANDED (2026-09-06)** — testProjectThroughAssociation flipped (143/2430): `$b.trades->map(t | $t.products->filter(p | $p.date == $t.d)->toOne().name)` — the filtered-navigation lift now runs inside a mapper over a class collection (SyntheticHeads.descend/TypedMap), a head whose predicate reads only the mapper's own element is PARENT-SCOPED, and the sub-hop join inside the slot materialization ANDs that predicate into its ON clause (Pipelines.TargetResolver.conditionFor ← NavMaterializer.subHopResolver ← AssociationJoins.andCorrelatedIntoCondition) — the engine's nested join with the filter in the join condition. Still open in this family: testProjectThroughAssociationAutoMap (the typer inlines the derived property over the to-many receiver `$b.trades` as `$this`, so the predicate reads `$b.trades.d` — an auto-map typing shape, not a resolver one), testForcedSubTypeProjectDirect (`$r->subType(@Bicycle).person.name`, a class-typed hop past a subtype cast), isolationTest (depth-2 pred whose outer read hops a parent nav). **batch 87 / L1-L2 union heads LANDED (2026-09-06)** — testQualifierConcatenateTwoSimilarJoinsEmbedded and testConcatenateInQualifierWithComplexReturnType flipped (147/2426, with L2's testQualifierConcatenateTwoSimilarJoins): the "class-typed property as a whole value" wall in front of these two was really the engine's processConcatenate shape — a concatenate of navigation chains through different heads joins ONE union subselect (see L2). **batch 73 / L1a LANDED** — testQualifiedPropertyInQuery and testSubFilter
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
| modelJoin::advanced::testQualifiedPropertyInQuery | **FLIPPED batch 73** (row marked at the batch 89 census) | rows |
| modelJoin::advanced::testSubFilter | **FLIPPED batch 73** (row marked at the batch 89 census) | rows |
| multigrain::testToManyWithQualifierWithFilterOnJoin | FLIPPED batch 109 — the nested materializer drills the embedded ctor to its navigate slot and registers an embedded SubNav node (NavMaterializer.drillEmbedded / putUnderEmbedded) | rows [500] + sql-text row verdict |
| projection::simple::testRoutingWithSubtypePropagation | FLIPPED batch 107 — the cast-sourced auto-map composes its leaf demand (composeAutoMapPaths inlines the element; pathOf's cast arm qualifies the leaf); the same-source stc navigate transplant materializes as a SubNav | sql-text row verdict |
| testDataGeneration::testInheritanceMultipleLevel | FLIPPED batch 108 — the Vehicle union lifts Bicycle's subtype-only `person` join as the navigate slot `stc_Bicycle___person` (UnionSynthesis.scanJoinPms) | TDG rows |
| businessdate::testBusinessDateInjectionFromVarReferenceInProjectUsingExternalFunction | **REVISIT (batch 93 receipt `revisit:instance-filter-ungated` — traced, NOT resolved; user 2026-09-06: the relational lane and Pure disagree, the lane choice is revisited at the end)** — the instance-filter idiom canonicalizes in the lift pass (the wall is gone; the assert reaches its sql-text ROW verdict); the golden projects `"root".id` unconditionally (testBusinessDateMilestoning.pure:591 — its filter subselect never gates the value) while Pure's filter->map and the engine's own sibling golden testConcatenateWithFilter ('Firm A,', testConcatenate.pure:88) yield the empty cell; rows [1, 2] vs ours [TDSNull, 2] | assertSameSQL → referee rows |
| advanced::forcedselfjoin::isolationTest | FLIPPED batch 106 — the predicate re-bases onto the fan-out element at the lift (ElementScope); the head's target materialization joins the chain as the exploding parent-copy subselect with the target as parent (§8.0) | rows |
| injection::testProjectThroughAssociation | **FLIPPED batch 90** — the lift runs inside a class-collection mapper; the parent-scoped correlated predicate composes into the sub-hop join's ON clause (NavMaterializer.conditionFor) | rows |
| inheritance::multiJoins::testForcedSubTypeProjectDirect | **FLIPPED batch 95** — the cast canon reads the union's plain lifted `person` slot; the read through the witness filter is the guarded `if(witness, \| $r.person.name, \| [])` (SyntheticHeads.instanceFilterNavRead) | rows |
| injection::testProjectThroughAssociationAutoMap | **FLIPPED batch 92** — map fusion in the lift canon: the leaf read over the auto-mapped derived property (`map($b.trades, _am0 \| toOne(filter(...))).name`) fuses into the mapper, then batch 90's parent-scoped route serves it | rows |
| query::function::testFilterTimesWithManyOperands | **FLIPPED batch 94** — the eliding reducer behind a to-one head registers under the dotted chain key (CorrelatedSubselects.chainTailAggArm: grouped subselect keyed on the firm, joined back through the firm hop); a literal operand list under plus/times renders as the engine's NULL-propagating binary chain (Numerics.scalarChain) | assertSameSQL → referee rows |
| concatenate::testQualifierConcatenateTwoSimilarJoinsEmbedded | **FLIPPED batch 87** — union head (#uN): the branch chains UNION ALL-ed with name-aligned null-padded keys, LEFT-joined on the OR of the branch conditions (engine processConcatenate); the embedded `oe` ctor drills inside the member | rows [1,'OE 1',2,'OE 2'] |
| concatenate::testConcatenateInQualifierWithComplexReturnType | **FLIPPED batch 87** — union head over `address` (navigate-slot route) and `firm.address` (association hop + slot); keys align BY NAME so both branches share `ID` exactly as the golden joins `unionalias_0.ID = root.FIRMID or … = root.ADDRESSID`; the assert's `sort(tds, $tds.columns.name)` folds to the legacy string-keyed sort | rows |
| enumeration::testEnumInRelation | **FLIPPED batch 91** — the `~[...]` project resolved already; the wall was the assert's `->cast(@TDS<Any>).csv` read: TDS.csv over an executed relation is a render (header ', ', rows ',', TDSNull), the cast a type-level no-op (Anchors.tdsCsvRead / Render.lowerTdsCsvProperty) | rows (csv) |

### L2 Resolver: project/extend column resolution and aggregation (3)

Status: **batch 87 / L1-L2 union heads LANDED (2026-09-06)** — testQualifierConcatenateTwoSimilarJoins flipped (147/2426) with the two L1 concatenate tests: a concatenate of navigation chains through DIFFERENT head properties lifts into ONE synthetic union head (`SyntheticHeads.liftUnionHead` → `#uN`; `UnionHeads.material` builds the engine's unionalias join: member = branch chain, keys aligned by name and null-padded, condition = OR of the branch conditions). The two aggregation tests stay open.

| test | wall |
|---|---|
| concatenate::testQualifierConcatenateTwoSimilarJoins | **FLIPPED batch 87** — the same union head; the `oe` navigate slot of each branch target rides the member's SubNav |
| aggregation::testSubAggregationWithDeepAndOverlap_WithColVar | **LANDED batch 104** — with its twin (the let-bound cast column list already resolved; the aggregate was the wall) |
| aggregation::testSubAggregationWithDeepAndOverlap | **LANDED batch 104** — a mapper-scoped aggregate is a chain aggregate keyed on the element, joined onto the fan-out row already on the pipe (mapperAggs + foldChainMid + withAggReads); println statements inert for the resolver |

### L3 Relation-mapping family (4)

| test | detail |
|---|---|
| relation::testSimpleMappingQueryWithFilterInProject | **REVISIT (batch 96 receipt `revisit:relation-mapping-filter-alias-root` — traced, NOT resolved; user 2026-09-06)** — fixture ages David 52 / Fabrice 45 / John 30 / Oliver 26, Firm C = {Fabrice, Oliver}; the golden's `Fabrice → TDSNull, Oliver → [Fabrice, Oliver]` is `root.AGE < 35` on the OUTER row (relationalModelJoins.pure:342-349 reconciles the inner condition's alias onto 'root'); Pure's per-employee filter (ours) gives `Fabrice → Oliver, Oliver → Oliver` |
| relation::testMixedMappingWithFilterInProject | **REVISIT (batch 96 receipt `revisit:relation-mapping-filter-alias-root` — traced, NOT resolved; user 2026-09-06)** — the same golden (tests.pure:179) behind a union-key wall (`firm_ID`): even with the wall gone the rows cannot match Pure |
| union::relation::testUnionTwoRelationMappings_ManyColumnProject | DIVERGENCE: 12-column distinct over a union of two relation mappings — TRACED 2026-09-06 (revisit): our rows differ only by `''` vs `null` cells; the engine's Pure-side `execute` prints a Relation-typed result to CSV and re-parses it with stringToTDS (execution_relational_execute.pure getTDSResultFromProtocol; TestTDS.makePureCsvSpecs nullValueLiterals "", "null", "NULL"), so the engine's own `''` cells (H2 LEGACY keeps them) become null. A boundary pass was prototyped and REVERTED (user: skip, revisit at the end) |
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
| projection::filter::testChainedFiltersQuery | **FLIPPED batch 99** — the negation null-guard tested the class-typed mid node (`$f.employees#f0.locations#f1`) instead of the leaf past the sub-navigation; the crossing read is now the outermost read (Substitution.collectToManyCrossings) |
| union::testEnumFilterWithUnionMappingPlanGeneration | **RECLASSIFIED → TEXT (T2) 2026-09-06**: its one assert is `assertEquals($expected, $plan->planToStringWithoutFormatting(…))` (tests/mapping/union/testUnion.pure) — unformatted plan text, unreplayable by construction (the T2 precedent); the Subselect-alias plan wall is real but cannot flip it |
| union::testPksWithImportDataFlow | PARKED 2026-09-06 (batch 105 note, handoff): seams 1-2 mechanical (the 5-arg execute overload with exeCtx; flags on ExecEnv), seam 3 is option-driven union key projection — the union body is synthesized once per mapping on demand. Earlier note: typer: multiplicity [*] vs [1] — `RelationalExecutionContext(importDataFlow=true, importDataFlowAddFks=true)` adds the union's pk/fk columns (`ID_0`, `ID_1`) to the projection; rows assert |

### L5 Cross-store model joins as relational joins (4)

| test | detail |
|---|---|
| graphFetch::crossDatabase::testCrossMappingWithRelOpWithJoinKeys | FLIPPED batch 110 — join-chain +prop demands its slot through the binding-aware condition scan | graph-fetch rows |
| modelJoins::testPersonToFirmUsingFromProject | FLIPPED batch 110 — lossy column view → property-space route, authored operand order (the two plans are byte-equal) | text over two of our plans |
| modelJoins::testPersonToFirmUsingProject | assert-free (zero-assert bucket by design — the runner counts it, it cannot flip; runs the batch-110 shape) | none |
| modelJoin::advanced::testNestedModelJoinCompoundInnerCondition | PARKED 2026-09-06 (three walls; handoff §0 item 3): recursive ModelJoinNesting.compose (probed, correct) + the sibling-slot demand for a JoinSlot condition inside a composed pipe (the `profile` join stripped while `profile_RANK` is still read) | rows |

### L6 Graph fetch (4)

Status: **batch 97 / L6b LANDED (2026-09-06)** — concatenate::testAll flipped (137/2436): a class concatenate executed as instances is ONE graph over the UNION of the two row sources (the engine's unionalias instance stream). **batch 80 / L6a LANDED (2026-09-06)** — testGraphFetchWithTableMapperPostProcessor flipped (156/2417).

| test | detail |
|---|---|
| graphFetch::simple::testCheckedWithCircularConstraints | **REVISIT (batch 105 receipt `revisit:engine-isDistinct-checked-defect` — traced, NOT resolved)** — the engine's own test source says `toFix: after fixing isDistinct related bug this test should expect:` the all-empty-defects document, which is exactly our output |
| graphFetch::simple::testGraphFetchWithTableMapperPostProcessor | **FLIPPED batch 80** — the connection's MapperPostProcessor rides the tableReplace channel (SqlPostProcessors.hooks: exact-FQN TableNameMapper/SchemaNameMapper; other kinds loud); renames now reach aggregate arguments (the graph envelope's child subquery) and every execute a statement reaches through ordinary lets |
| graphFetch::union::propertyLevel::test6 | **REVISIT (batch 105 receipt `revisit:h2-distinct-root-order` — traced, NOT resolved)** — same row set; the engine's graph-fetch root query is `select distinct` (relationalGraphFetch.pure:791) with no ORDER BY, the golden's order is H2's hash-distinct order |
| query::function::concatenate::testAll | **FLIPPED batch 97** — two implicit-serialize graph terminals of one class layout fuse into ONE graph over the union of their sources (ClassConcatenates.terminal); `$result.values.name` distributes per side over the executed frame (ClassConcatenates.mapOverExecuted); a value-typed union takes its branches' outputs |

### L7 Post-processors as compiler passes (4)

Status: **batch 81 / L7a LANDED (2026-09-06)** — testNonExecutableSQLString flipped (155/2418; text-only lane 13 → 12). The relationalMapper pair asserts a PLAN NODE's sqlQuery text over foreign schema names (`snDBDefault.default.*`, no such schema in any session) — text unless a referee creates the schema (L15's idea); testPostProcessTransformJoinOp is TEXT behind its wall.

| test | detail |
|---|---|
| alloy::connections::relationalMapper::testRelationalMapperWithJoin | **RECLASSIFIED → TEXT (T3) 2026-09-06**: `assertEquals('select … from snDBDefault.default.firmTableNew as "root" …', $resultSQL)` where `$resultSQL` is a plan node's sqlQuery (testRelationalMapper.pure:66-78 relationalMapperSqlQuery) — CATALOG-qualified 3-part names; H2 has no user catalogs, no session can execute the golden, text is the contract |
| alloy::connections::relationalMapper::testRelationalMapperTwoDBs | **RECLASSIFIED → TEXT (T3) 2026-09-06**: same helper, `snDB.productSchemaNewDBINC.productTableNewINC` (catalog.schema.table) |
| sqlstring::testNonExecutableSQLString | **FLIPPED batch 81** — a fourth toSQLString-family native on the one K-routine; the nonExecutable IR pass renders and the rows leg runs under it |
| postProcessor::testPostProcessTransformJoinOp | a connection `sqlQueryPostProcessors` lambda over the SQL AST — the ONE post-processor test that is a user-supplied pass; text assert behind it (TEXT) |

### L8 Natives and small typer legs (12)

Status: **batch 89 / L8 LANDED (2026-09-06)** — testSimpleTypeMappingProjectNulls flipped (144/2429): toJSON(tds) as the engine's TDS JSON document, database-emitted. **batch 88 / L8 LANDED (2026-09-06)** — stringToFloat::testProject and strictdate::testProject flipped (145/2428): both were assert-side shapes (a forAll over an assert body; a bare sort() over flat cells). **batch 74 / L8a LANDED** — testToSQLStringWithCodeBlock (the engine's
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
| dataType::testSimpleTypeMappingProjectNulls | **FLIPPED batch 89** — `toJSON(tds)` is the engine's TDS JSON document `{"columns":[{name,type,metaType}],"rows":[{"values":[..]}]}` emitted by the database (TdsJsonChecker → TypedJsonResult.Kind.TDS_JSON → JsonEmission); the TINYINT/SMALLINT columns already read as Integer |
| mapping::dates::strictdate::testProject | **FLIPPED batch 88** — the assert's `rows.values->sort()` over a mixed Integer/StrictDate cell pool is the cell-multiset judgment (AssertVerdicts.bareSortOverCells → tdsRowValuesSameElements), never a SQL column sort |
| tds::extensions::testFirstNotNull | **FLIPPED batch 74** (row marked at the batch 89 census — generic instantiation at the inlining seam) |
| tds::extensions::iqrClassifyTest | **FLIPPED batch 76** — collection value in relation position + StaticFold zip |
| tds::extensions::zScoreTest | **FLIPPED batch 76** — collection value in relation position + StaticFold zip |
| tds::extensions::rowValueDifferenceTest | **LANDED batch 103** — the normalize-required program's call to a plain Pure function with static arguments inlines and folds (StaticFold.inlineUserCall); the whole extension lowers to one statement |
| tds::extensions::testExtendDigest_InMemory | **FLIPPED batch 76** — collection value in relation position + StaticFold zip |
| projection::testGroupByWithWindowSubset | **LANDED batch 102** — a STORE-handled function (pureToSQLQuery processObjectGroupByWithWindowSubSet), desugared by the store's rule to the legacy groupBy (GroupByChecker.checkWindowSubset) |
| sqlstring::testToSQLStringWithCodeBlock | **FLIPPED batch 74** (row marked at the batch 89 census — the add(Date, Duration) programs admitted verbatim) |
| businessdate::testViewChainsWithBusinessDate | **FLIPPED batch 75** — toSQL handle + SQLResult.toSQLString function form; rows verdict |
| lineage::scanRelations::testTdsJoinConcatenateAndJoin | **FLIPPED batch 84** (row marked at the batch 96 census — the ConcatenateChecker's positional alignment; in the flipped set since) |

### L9 Lineage row verdicts (2)

Status: **batch 98 / L9b LANDED (2026-09-06)** — testTableToTdsWithCrossJoin flipped (136/2437): ScanRelations.attachTdsJoin's cross-join arm. testNonDataTypeProperty stays (scanColumns over a class-typed project column — Phase H4 whole-value read).

| test | detail |
|---|---|
| lineage::scanColumns::testNonDataTypeProperty | PARKED 2026-09-06 (batch 104 note): a CLASS-typed project column (`p|$p.address`) has no SQL value form yet — the whole-value class column design (Phase H4); the engine's scanColumns is metamodel-level (property tree over the mapping: join keys only, no value column), ours scans the lowered SQL, and no engine SQL golden projects such a column. Needs the H4 design first |
| lineage::scanRelations::testTableToTdsWithCrossJoin | **FLIPPED batch 98** — the lineage scanner's tableToTDS join chain accepts a constant-true condition (a cross join: the right table under the spine's root, bare `tdsJoin` label, no keys); the TableAlias lowering wall was the generic path after the lineage arm refused the shape |

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

Status: **batch 96 / L12 LANDED (2026-09-06)** — testDateFunctionInMilestonedPropertyWithMilestonedEntity flipped (138/2435): our SQL was NOT byte-identical to the golden after all — the golden dates ProductClassificationSystemTable by `constantDate()` (2015-01-01) and ours by the root business date; the temporal stamping's mid-slot rule keyed the slot by the sub-chain `classification.system` (no spec) and fell back to the root. A dated EMBEDDED head now governs its block's joinslots and its sub-hops' mid slots.

| test | detail |
|---|---|
| businessdate::testDateFunctionInMilestonedPropertyWithMilestonedEntity | **FLIPPED batch 96** — a real wrong-row bug of ours, not referee skew: the embedded block's milestoned joinslot took the ROOT date; the dated embedded head's spec (`constantDate()` = 2015-01-01) now stamps it (TemporalFrame.datedEmbeddedMidSlots) |

### L13 Model chain over relational (m2m2r) and derived properties (5)

| test | detail |
|---|---|
| m2m2r::planGraphFetchWithDerivedProperty | **RECLASSIFIED batch 105 → TEXT/T2** — its only assert is `planToString` TEXT (the breakdown's own rule: a plan-text-only test can never leave IMPL by flipping) |
| m2m2r::planGraphFetchWithNestedDerivedProperty | same |
| m2m2r::executeProjectWithNestedDerivedProperty | **LANDED batch 105** — the TDS-as-row-objects JSON document emitted by the database (TDS_JSON_KV); the envelope collapse sees through the TDS cast over plan-execute values |
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
| executionPlan::testQuoteIdentifiersFlagWithGraphFetch | **RECLASSIFIED batch 96 → TEXT (T2)** — the assert is `assertEquals('PureExp(type=String expression=->serialize(...)(StoreMappingGlobalGraphFetch(...)))', $result->planToStringWithoutFormatting(...))` (executionPlanTest.pure:2619): engine PLAN TEXT with the quoted SQL embedded — the same class as the seven batch-86 receipts; the referee's quoted-schema decline was the sql-text arm attempting the embedded SQL |

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
| tdsRestrict::testRestrictOnGroupByEleminatesUnnecessaryAggsWithDistinct | FLIPPED batch 111 — restrict over a whole-row distinct of a groupBy lowers as the distinct over the restricted columns (Fold.restrictOverWholeRowDistinct); the max is dropped like the engine | rows + 3 sql-text row verdicts |

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

## 5. NAMED — receipts, registered defects, user decisions (19)

| tests | bucket |
|---|---|
| testHashFunctions, testToSQLStringForTDSStringJoin, testExtendDigest_Relational, tdsJoin::alloy::testJoinWithExtendWithDigestOnColumnsOnBothQueries | engine-golden-defect:joinStrings-rendering |
| testToSqlGenerationFirstDayOfWeek | engine-golden-defect:h2-week-start |
| columnValueDifferenceWithoutPrevalTest | engine-golden-defect:alloy-adjust-widening |
| embedded::otherwise::testMilestonedRootAndMilestonedProperty, milestoning::testMilestonedRootAndMilestonedProperty | engine-golden-defect:malformed-json-golden |
| forced::structure::testQualifierWithOperation, testTwoQualifiersWithOperation | decision:empty-toOne-forced-isolation |
| businessdate::testBusinessDateInjectionFromVarReferenceInProjectUsingExternalFunction (batch 93) | revisit:instance-filter-ungated (NOT resolved, user 2026-09-06) — the golden's join-back subselect never gates `"root".id`; the engine's sibling golden for the same idiom (testConcatenateWithFilter) gates it, as Pure does |
| relation::testSimpleMappingQueryWithFilterInProject, relation::testMixedMappingWithFilterInProject (batch 96) | revisit:relation-mapping-filter-alias-root (NOT resolved, user 2026-09-06) — the inner filter's reads reconciled onto the outer 'root' alias (relationalModelJoins.pure:342-349); fixture-proven against the ages |
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
lines): 68 → batch 73 −2 (66) → batch 74 −2 (64) → batch 75 −1 (63) → batch 76 −3 (60) → batch 77 −1 (59) → batch 78 −1 (58) → batch 79 −1 (57) → batch 80 −1 (56) → batch 81 −1 (55) → testRelationStoreAccessorOnView reclassified TEXT −1 (54) → batch 82 −1 (53) → batch 83 −1 (52) → batch 84 −1 (51) → batch 85 −1 (50) → seven plan-text / catalog-name reclassifications (T2: testEnumFilterWithUnionMappingPlanGeneration, relationalResultSourcingOfListExecutionPlan, testModelConnectionJoin, testModelConnectionDeepFunction, testAlloyTestDatGenWithQuotedColumnsForViews; T3: testRelationalMapperWithJoin, testRelationalMapperTwoDBs) −7 (43) → batch 86 −1 (42) → batch 87 −3 (39) → batch 88 −2 (37) → batch 89 −1 (36) → batch 90 −1 (35) → batch 91 −1 (34) → batch 92 −1 (33) → testBusinessDateInjectionFromVarReferenceInProjectUsingExternalFunction reclassified NAMED engine-golden-defect −1 (32) → batch 94 −1 (31) → batch 95 −1 (30) → batch 96 −1 (29) → relation-mapping pair reclassified NAMED engine-golden-defect −2 (27) → testQuoteIdentifiersFlagWithGraphFetch reclassified TEXT (T2) −1 (26) → batch 97 −1 (25) → batch 98 −1 (24) → batch 99 −1 → **23** → batch 102 −1 → **22** → batch 103 −1 → **21** → batch 104 −2 → **19** → batch 105 −1 flip −1 TEXT reclass → **17**.

Rule applied for the reclassifications (2026-09-06): a test whose ONLY assert compares engine PLAN TEXT (`planToString` / `planToStringWithoutFormatting`) or SQL text no session can execute (catalog-qualified names) can never leave IMPL by flipping, whatever wall stands in front of it — the wall is real work the flip cannot pay for; each row names the assert and its file. A test whose text assert reads a REPLAYABLE producer (execute()/toSQL/toSQLString) stays IMPL: the sql-text arm brings the golden to rows (batches 75, 81).

So "burn to zero" honestly means: **68 tests can become real verdicts**, in
sixteen legs. The other 100 are named for what they are; none of them is a
platform gap that a compiler for Pure-to-SQL should close, and the ledger
buckets already say so test by test.

## 6b. Foreign-dialect referee backends (note, user ask 2026-09-06)

The T3 "text is the contract" tests (DB2, Postgres, SQL Server `select top N`,
Oracle-style quoting, the per-DB expected-SQL table) become ROW verdicts on the
dialect itself once a real backend replays the golden: Postgres, Trino/Presto,
SQL Server Developer, Db2 Community and Oracle XE run in Docker; Snowflake,
BigQuery, Redshift and Sybase IQ have no local edition. Leg: a referee session
per available dialect (the H2 second-target machinery generalized), the store
DDL seeded per dialect, goldens replayed there. Twelve tests today; the same
harness validates every dialect rewrite rule we emit.

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

### 8.0 L1 — isolationTest: the tail-hop correlated predicate (sized 2026-09-06, after batch 105; LANDED batch 106 the same day)

LANDED (batch 106): mechanisms (1)+(2)+(3) below built as designed with one change of placement — the RE-BASE happens at the LIFT (SyntheticHeads.rebaseToElement, so the outer read never registers parent demand at the root — otherwise the employees slot would have joined `product` and duplicated Peter's row), and the nested reroute lives in NavMaterializer (elementDivertedTails + foldElementReroutes) reusing CorrelatedSubselects.explodingSubselect with the target as the parent — the "corrNavHeads factoring" risk named below did not materialize because the fold is the same three calls the root makes. The prefix tails of a rerouted tail are diverted with it (else the plain slot joins beside the reroute). Emitted shape = the golden's (Person copy ⋈ product ⋈ group→children→coveredProduct WHERE pred, keyed by ID, LEFT-joined on the employees row). Original sizing note kept for the record:

Query: `Firm.all()->project([... col(x | $x.employees.group.children->filter(c |
$c.coveredProduct.name == $x.employees.product.name).name->toOne(), 'testCol')])`.
The predicate sits on hop 3 (`children#f0`) of `employees.group.children#f0.name`
and reads the OUTER var through the SAME fan-out head (`$x.employees.product.name`);
engine isolation semantics: that read is the fan-out ELEMENT's own navigation.
Golden SQL (testForcedSelfJoin.pure): `... left join personTable persontable_0 on
root.ID = persontable_0.FIRMID left join (select persontable_2.ID as ID,
organizationtable_1.name as name from personTable persontable_2 left join
organizationTable organizationtable_0 on … left join organizationTable
organizationtable_1 on organizationtable_0.orgId = organizationtable_1.parentId
left join productTable producttable_0 on producttable_0.orgId =
organizationtable_1.orgId left join productTable producttable_1 on persontable_2.ID
= producttable_1.ownerId where producttable_0.name is not distinct from
producttable_1.name) as persontable_1 on persontable_0.ID = persontable_1.ID` —
i.e. the exploding parent-copy subselect with the EMPLOYEE as the parent: a copy of
Person carrying its `product` nav and the `group.children#f0` chain, the predicate in
its WHERE, keyed by Person's PK, LEFT-joined back onto the employees fan-out row.
Where we wall: StoreResolver registerNavigations, the `explodingReroutePred(path,
mid)` branch → `synthetics.unappliedCorrelatedWall(path, mid + 1)` (batch 69b) —
the head-level reroute (corrNavHeads → parentCopyFor(cs=Firm, …)) serves mid==1
only. Mechanism to build: (1) RE-BASE: a tail pred whose outer reads ALL pass
through path[0] (`$x.employees.…`) rewrites to reads on the head's target element
(`$e.product.name`); (2) NESTED REROUTE: resolve the tail chain
`[group, children#f0, name]` against the head's TARGET ClassSource (Person) with
the re-based pred — it is then a hop-0 exploding reroute FROM Person's point of view
(corrPredDemandsParentNav over `product`), parent copy = Person; (3) attach the
resulting AssocJoin INSIDE the employees target pipeline and register its composed
prefix as a SubNav under the employees AssocSub so Substitution's chain-key read
(`chainKeySubNavRead`) serves `employees.group.children#f0.name`. Risk: the sub-target
materialization path (NavMaterializer.navTargetMaterialized) does not run the
head-level reroute today; it needs the corrNavHeads build factored so a target can
reuse it. Estimate: one focused session.

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
- PARKED 2026-09-06 (batch 96 probes, third new wall): the root cause is UPSTREAM of the
  nested scope. Dumped at NavExistsMaterial.register: the exists target ClassFunction[map1]
  carries ONE navigate step `fnScope -> Private` (its bindings [name, fnScope]) — the
  normalizer emits the routed pair `fnScope[map2]/[map3]` as a single navigate to the
  FIRST route's set. UnionSynthesis classifies routed Join PMs against "the target
  class's union" (unionRoutes: ONE navigate, OR over the entries); Private[map2] and
  Public[map3] are sets of SUBCLASSES of the abstract FunctionScope (a rootless
  subclass multi-set), which that classification does not cover. Design: the routed
  navigate must target the FunctionScope subclass union (the same rootless multi-set
  source the cast canon resolves — `sources.get(m, FunctionScope)` with the member
  witnesses) with the OR of the per-route conditions; then the nested scope's SubNav
  carries the `stc_<Public>___id` leaf and the exists predicate resolves. Owner:
  normalizer/UnionSynthesis.collectNavLifts + the routed classification (:180-200).

### 8.2 L1 — the three multi-hop-through-embedded tests (two designs)

Probe diagnostics (LEGEND_LITE_STACKS=1, `[multi-hop wall] path=… targetBindingKeys=…`):

- testToManyWithQualifierWithFilterOnJoin: LANDED batch 109 (an embedded ctor is a SubNav NODE
  sharing the parent's row — see the GATES record). Original note: path
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
- testRoutingWithSubtypePropagation: LANDED batch 107 — the wall was the DEMAND SCAN, not the
  materializer: the derived leaf over the [0..1] cast is an auto-map with a bare cast as its
  source, which pathOf could not spell; composeAutoMapPaths now inlines the element. Original note:
  path `[employees, stc_…PersonExtension___manager,
  stc_…PersonExtension___firstName]`; targetBindingKeys already carry every
  `stc_…PersonExtension___<prop>` INCLUDING manager. Query: `Firm.all()->project(col(x|
  $x.employees->subType(@PersonExtension).manager->subType(@PersonExtension).firstName…))`
  (REL/router/tests/testRouting.pure). Model: `PersonExtension extends Person`
  (simpleTestModel.pure:230); mapping `PersonExtension: Relational { scope([dbInc])
  (firstName, age), lastName, firm: @Firm_Person, address: @Address_Person, locations:
  @Person_Location, manager: @Person_Manager }` (relationalSetUp.pure:1139). Shape: a
  subtype-cast leaf that is a JOIN slot (`manager`), then a further cast + leaf. The
  assert is SQL-text only (TEXT behind).
- testInheritanceMultipleLevel (TDG): LANDED batch 108 — the union lift, not the resolver (see the
  GATES record). Original note: path `[vehicles#f1, stc_…Bicycle___person, name]`;
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


- 2026-09-06 (batch 96 probes, current walls — all three end in Substitution
  .rewritePath's last-resort "multi-hop navigation … through an embedded/slot head"):
  testToManyWithQualifierWithFilterOnJoin = `account.incomeFunctionSplits#f0
  .incomeFunction.Classification.name` (assocs=[account]; the head's subNavs carry the
  lifted filtered sub-slot `incomeFunctionSplits#f0`; inside it `incomeFunction` is an
  EMBEDDED ctor whose `Classification` is a navigate slot — a SubNav CHILD two levels
  down, through an embedded body); testInheritanceMultipleLevel = `vehicles#f1
  .stc_<Bicycle>___person.name` (head binding ABSENT — the lifted filtered union head;
  the Vehicle union of inheritanceMappingDB does NOT lift Bicycle's `person` slot as a
  plain slot, unlike multiJoins' RoadVehicle union in batch 95 — dump the union's
  bindings to see whether the LiftChain dropped it (poison ledger) and why);
  testRoutingWithSubtypePropagation = `employees.stc_<PersonExtension>___manager
  .stc_<PersonExtension>___firstName` (a subtype's class-typed slot then a subtype
  leaf of ITS target — two stc-qualified hops; TEXT-only assert, rows via the
  sql-text referee). One design for the first: SubNav children through embedded
  bodies inside lifted sub-slots (NavMaterializer.composeSubNavPrefixes depth);
  the other two are union-lift coverage (UnionSynthesis) + stc-qualified slot reads.
### 8.3 L1 — the rest, walls and owners

- isolationTest: LANDED batch 106 (§8.0).
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
