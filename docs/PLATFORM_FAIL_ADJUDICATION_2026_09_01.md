# Platform-fail adjudication — the foundation probe (2026-09-01)

Handoff step 1+2 (docs/SESSION_HANDOFF_2026_09_01.md): before racking up
mechanical flips, probe whether the platform ever produced a
plausible-looking WRONG answer in a way that could silently corrupt the
1,697 row-verified flips. Method: one `LL_TMP_DEBUG=1 LEGEND_LITE_DUMP_SQL=1`
sweep, every `[flip-fail-debug]` block read with its lowered SQL, every
divergence adjudicated against the ENGINE SOURCE (the corpus's own
mapping, seed rows, sibling tests, the PCT contract, legend-pure's TDS
literal parser) — never against my expectation.

## Verdict on the foundation

No divergence is a filter/null-out corruption. Every one of the 9 "real"
rows from the earlier audit is either the engine's own defect pinned by
its golden, a carrier/representation convention, or a narrow mapping-DSL
binding with a corpus-wide blast radius of ONE test. The sweep DID surface
one genuine lowering defect the earlier audit had not listed
(testMappingWithWindowColumn, the mapping-seam window rule) — fixed in
this batch — plus three "ignored connection feature" rows that are silent
in production but loud in the corpus. Flipped tests cannot be silently
wrong: every flip is judged against the engine's golden rows, and every
divergence below FAILS loudly (that is how they were found).

## The null-vs-value group (3) — ENGINE DEFECT, ours is the Pure answer

`testMixedMappingWithFilterInProject`, `testSimpleMappingQueryWithFilterInProject`
(+ their shared bucket). Query:
`Person.all()->project(~[name1:x|$x.firstName, name2:x|$x.firm.employees->filter(e|$e.age < 35).firstName])`
over the Relation-function mapping (Person set = `personFunction` =
`personTable->filter(AGE > 25)->limit(5)`: John 30/Firm X, Fabrice 45/Firm C,
Oliver 26/Firm C, David 52/Firm D).

Hand-computed Pure answer: David→null, **Fabrice→Oliver** (Firm C's only
under-35 employee), John→John, Oliver→Oliver. That is exactly our SQL
(`t3.AGE < 35` sits in the JOINED employee subquery `t4`) and our rows.

The golden (`Fabrice,null`; `Oliver,Fabrice` + `Oliver,Oliver`) is the
filter bound to the ROOT person's age: Oliver (26 < 35) gets every Firm C
employee including 45-year-old Fabrice, whom no reading of `$e.age < 35`
admits. The engine corpus admits the defect itself:
`aggregation/testRelationFunctionAggregation.pure` carries a
`<<test.ToFix>>` sibling with the comment "filter inside collection
navigation — AGE column fails to resolve against inner relation". Same
column-resolution defect; in the self-join case it resolves to the outer
alias, producing precisely the golden. Upstream commits (#4941/#4971/#5001)
own these files — not local edits.

**Decision:** ADJUDICATED NOT OURS. Stays a counted platform-fail row
(never conform-by-weakening our correct binding). Same class as the
firstDayOfWeek record (charter §8 3a).

## Duplicate rows dropped (2) — two different things

1. `testIsolatioWhereNoConstaintsAndInnerJoin` — NOT a dedup. The mapping
   `chainedJoinsInner` spells
   `@Firm_FirmPersonBridge > (INNER) @Person_FirmPersonBridge | firmTable.ADDRESSID`:
   the join chain ends at personTable but the declared column table is
   firmTable. The engine binds the column to the CHAIN TERMINAL (its SQL
   reads the person's ADDRESSID inside the bridge subquery and fans out per
   employee: Firm X ×4); we honor the declared table, read the firm's own
   ADDRESSID and never emit the chain (Firm X ×1). Engine = semantic spec
   → the chain-terminal binding is the rule. **Blast radius census** (all
   `.pure` under the engine checkout, 165 join-chain column refs): exactly
   ONE reference declares a table outside its terminal join — this
   mapping. The five other hits of the length≥2 heuristic are ping-pong
   self-chains (`@Firm_Person > @Firm_Person | personTable.X`) whose
   declared table IS the terminal, plus one grammar-serialization
   fixture. Named residue: **join-chain terminal binding** (1 witness,
   filter-predicate-isolation family — the fix also needs the isolated
   INNER-chain subquery shape, not just the binding).
2. `testMultipleJoinsInPropertyMappingWithDatesInClass` — a to-one join
   (TypeTableB, milestoned rows old+new per ID, no date filter in this
   mapping) fans out: the engine builds 6 objects for 3 pks (no pk dedup
   on this path); we also return 6 instances (assertSize passes), but
   `$result.values.tableProperty` re-derives the class query with only the
   root table (join pruning) → 3 values. Our carrier is self-inconsistent
   under fan-out through a multiplicity-violating join; Pure-strict pk
   identity would say 3/3. Named residue: **instance-carrier cardinality
   under fan-out joins** (the value/tabular duplication class, charter
   §6.1). Loud, never silent: any count assert diverges.

## Empty-vs-null encoding (2) — OUR carrier convention, both sides ours

`testSQLQueryMergingForInnerJoins`(+2): `assertEquals(['8', ^TDSNull(), '8', ^TDSNull()], $result.values.rows.get('a_p1'))`.
The canon compare is ORDER-BLIND (both sides `ORDER BY` the canon key), so
the apparent order difference is nothing. The real difference: the
expected `^TDSNull()` instance literal lowers to JSON `null` (renders `[]`)
while our row NULL lowers to the string `'TDSNull'`. Two carriers, two
encodings of one value. Named residue: **TDSNull encoding across
carriers** ([[tdsnull-is-a-value-slice]] carrier rule). Loud.

## Union column padding (2) — engine csv-carrier artifact, ours is the DB truth

`testUnionTwoRelationMappings_ManyColumnProject`(+GeneratesSingleUnion):
both union members map `firstName: $src.firstName_s1->toOne()`; the SEED
rows (`testUnion.pure:426-428`) insert `''` for firstName_s1. Our `''` is
what the database holds. The engine's `#TDS` literal parser treats `""`
AND `"null"` as null (legend-pure `TDSExtension.makePureCsvSpecs`
`.nullValueLiterals("", "null")`; engine `TestTDS` adds `"NULL"`), and the
engine's Relation results round-trip through the same csv carrier, so
`''` and NULL conflate on BOTH sides there — the golden's `null` is that
conflation. Our expected side (empty cell → NULL) is faithful to the
literal parser; our actual side keeps `''` distinct from NULL.
**Decision:** convention, not ours; not conform-by-weakening the DB value.

## NOT in the earlier "9" — found by reading every block

- **`testMappingWithWindowColumn` — REAL LOWERING DEFECT, FIXED HERE.**
  Mapping `WindowColumnMapping` (~func `personFunctionWithJoinAndWindowColumn`
  = join + `extend(over(~GROUPID, ~SALARY->ascending()), ~[RANK: rank])`),
  query `ExtendedPerson.all()->filter(x|$x.age > 25)->project(...)`. We
  fused the class filter INTO the window select (`... RANK() OVER (...)
  FROM personTable JOIN groupMembershipTable WHERE t0.AGE > 25`), so John
  ranked 1st (Peter, 23, filtered before ranking); golden: John 2nd.
  Engine contract, two specs: in plain relation composition the engine
  folds an ordinary predicate to WHERE under a window (PCT
  `testExtendFilterOutNull`: partition 0 sums to 20 not 50 — the window
  sees FILTERED rows; `Fold.filterSlot` keeps that); at the MAPPING seam
  the mapped relation is a non-mergeable view. `Fold.containsWindow`'s doc
  named this "the RESOLVER's decision (windowed ~func pipelines)" — nothing
  implemented it. Landed: `TypedExtendWindow/TypedExtendAgg.extentBoundary`
  stamped by `ClassSources.sealExtentWindows` at the extent-extraction
  seam (every window in a class extent's pipeline), honored by
  `Lowerer.extentBoundary` (isolates the window select; the query's
  filter lands in the outer WHERE). Unit test
  `RelationMappingWindowSeamTest` pins both specs + the SQL shape.
- `testInExecutionWithTempTableForDateTimesWithTz` — `testRuntime('US/Arizona')`:
  the engine binds tz-less DateTime literals in the CONNECTION time zone;
  our execution ignores the runtime tz (only the engine-text renderer
  knows it, `EngineStyleH2.timeZone`) → 0 of 5 rows. Named residue:
  **connection timeZone at execution** (2 corpus tests, both testIn.pure).
- `testGraphFetchWithTableMapperPostProcessor` — an inline
  `^MapperPostProcessor(mappers=^TableNameMapper(personTable → differentPersonTable))`
  on the connection is ignored (only the `relationalMapperPostProcessor`
  helper channel is read, `RelationalMapperRenames`) → 4 employees
  instead of 0. Named residue: **inline MapperPostProcessor** (1 corpus
  test). Silent in production sense, loud here.
- `testCheckedWithCircularConstraints` (checked graph-fetch constraint
  defects 1 vs 0) — constraint-evaluation feature gap, loud.
- `testJoinIsolationDeeperTwoIsolations_LeftOuterLeftOuterThenInner`,
  `isolationTest` (forcedselfjoin) — join-isolation family shape
  divergences (bu ancestor lookup joins the TEAM id; org-tree fan-out
  line counts). Named family (filter-predicate isolation 25).
- `testQueryOfMilestonedTypeWithFilterInMapping` — `$products->map(p|$p.id)`
  carried the second pk column (`name`) into the value list
  (`[2, 'ProductName2']` vs `2`): the identity carrier leaking into a
  scalar map over a multi-column-pk milestoned class. Named residue:
  **identity-as-data leak on multi-pk map** (F13 class).
- `testProjectionWithEnumThroughAssociation`, `testProjectWithIf*` —
  `rows->at(N)` on an unordered LEFT-JOIN result: H2 heap order vs
  DuckDB hash-join order. The incidental-order class (charter §7), no
  value divergence (multiset-equal).
- `columnValueDifferenceWithoutPrevalTest` — DATE vs DATETIME print of
  `tradeDate` (`2014-12-01` vs `2014-12-01T00:00:00.000000000+0000`); the
  §6 TIMESTAMP carrier row.

## STEP 3 census — the "mechanical" buckets are NOT mechanical

Named by overload from the same debug sweep (the census file masks
them as `'_'`):

| bucket | count | what it really is |
|---|---|---|
| `assertEquals/2` + `/4` + `assertEqWithinTolerance/3` reaching the SCALAR lowerer | 42 | asserts in expression position (inside `->map(p\|…)` loops over driver pairs, or over instance-carrier navigations like `removeDuplicates().firstName->sort()->makeString`) — a VERDICT-PLACEMENT leg (the arm sees only statement-root asserts), not a native port |
| `rootClassMappingByClass/2`, `classMappingById/2`, `_classMappingByClass/2`, `view/2`, `inferRelationalType/1`, `toPostgresModel::newState/0` | 43 | mapping-METAMODEL queries — the quarantined metamodel-as-relations family ([[metamodel-in-database-ruling]]) |
| `enumValues/1` | 1 | one real native |
| unknown function `getInteger` (+ siblings) | 25 | NOT an unported native: `TDS_ROW_GETTERS` exist; the receiver is a row parameter of the legacy TDS `join(tds, tds, JoinType, {a,b\|…})` predicate, which does not type as a TDS row — ONE typed-form leg (tdsJoin family) |
| `meta::legend::executeLegendQuery` / `compileLegendValueSpecification` / `compileLegendGrammar` | 42 | harness vocabulary (metaprogramming bodies) — walk-routed by design |
| `generateObjectReferences` 7, `routeFunction` 4, `repeat` 2, `toDomainValue` 2, `column` 1, `resolveStore` 1, misc 3 | 20 | small named legs |

So the handoff's "scalar lowerings 76 + unported natives ~70 =
predictable volume" dissolves: the honest bounded items are the TDS
join-predicate row typing (25, one shape) and the assert-in-expression
placement (42, one arm change); the rest is the metamodel family and
harness vocabulary. Sequenced accordingly.

## The clock — how the ratchet became time-of-day dependent, and the fix

Two same-tree sweeps of the untouched HEAD read **877/1696** against the
pinned 876/1697, both in the 21:00–24:00 local window. Attribution: the
engine runs every test JVM under `-Duser.timezone=GMT` (its root pom's
`surefire.vm.params`); ours pinned nothing, so the H2 replay oracle read
`now()` in the machine's LOCAL zone while DuckDB read UTC, and the five
sqlstring `dateDiff(settlementDateTime, now(), UNIT)` goldens diverged by
the zone offset — DAYS only after 21:00 local (the wobble). Root pom now
pins `-Duser.timezone=GMT` (one clock, the engine's). Under GMT the five
row-verified — and then still dropped ONE different member in two of five
sweeps (HOURS once, MINUTES once): the oracle replays the golden later
than our pipeline executed, two instants, so a projected distance-to-now
races at every unit boundary. No clock pin removes that. The comparison
seam (`H2Verify.compareFrame`) now DECLINES BY NAME `datediff-to-now
golden` = a `datediff(...)` whose arguments spell the instant, in the
SELECT list (paren-depth-0 scan up to the top-level FROM); §3.7 makes
TEXT the contract, their spelling is byte-identical, so they flip
DETERMINISTICALLY (emission census 396/812/102 → 392/812/110: eight
dateDiff goldens moved from text-matched to the named text-verdict
lane). Two over-broad cuts were measured and rejected: "any now()
anywhere" cost four predicate-use flips (WHERE/ON `x < now()` selects the
same rows seconds apart); "any projected now()" cost
testProjectEnumFromOpenVariable's M1 verify (`now()->adjust(1, DAYS) >
now()` is instant-invariant).

## What moves in this batch

- Fix: mapping-seam window rule (above). Corpus: testMappingWithWindowColumn
  FAIL → PASS (2349 → 2350). +1 flip.
- Fix: one test clock (GMT) + the named datediff-to-now decline. +5 flips
  (byte-identical spelling under a counted decline), the ROW-verdict
  diverged bucket 10 → 5. Ratchet **876/1697 → 871/1702**, paired sweeps
  byte-identical on all three rosters, sql-verdict disagree EXACT 0, canon
  disagree EXACT 21, M1 verified 83 / rescued 204 unchanged, exec-passing
  345 unchanged.
- Handoff pivot: the foundation is sound; STEP 3 re-sized by the census
  above — the honest next legs are the TDS join let-bound JoinType (23),
  the TDG carrier frame (29 + 3), the dialect-loop unroll (13).
