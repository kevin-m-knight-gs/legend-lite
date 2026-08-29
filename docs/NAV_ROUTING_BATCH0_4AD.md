# §4AD SLICE 1 — BATCH 0 HOMEWORK: ENGINE ROUTING MAP + GOLDEN SHAPE SURVEY (2026-08-29)

Status: **EXECUTED (read-only). Verdict: §3's two-form design is
CONFIRMED — no third form — with two data-driven refinements
(reducer test tightened to argument shape; placement bit now a
measured table). Awaiting user sign-off before any implementation
batch.**

Parent: NAV_ROUTING_DESIGN_4AD_SLICE1.md (Batch 0 = 0a routing map,
0b golden survey, 0c pure verification). Standing user ruling
incorporated (2026-08-29, mid-batch): **the engine/pure source is the
SEMANTIC spec, not the implementation spec — build the right/best
implementation, better than pure but informed by pure so rows don't
break.** Accordingly this map records the engine's ROW GUARANTEES as
the conformance floor and its mechanisms as non-binding; §3 is judged
on row-equivalence, never shape-imitation.

Spec checkouts read (reference only, never runtime):
- Engine router: `/Users/neemsandv/legend/legend-engine/…/core_relational/relational/pureToSQLQuery/pureToSQLQuery.pure`
  (10,385 lines; all line numbers below cite this file unless named).
- Pure natives: `/Users/neemsandv/legend/legend-pure/…/platform/pure/grammar/functions/math/operation/plus.pure`;
  engine standard lib `core_functions_standard/math/aggregator/sum.pure`.

---

## 0a. ENGINE ROUTING MAP (all VERIFIED at cited lines)

### Entry points (qualified property / navigation)

- `processQualifiedPropertyFunctionExpression` (L965) — the qualifier
  call entry. L993: `manageAgg =
  $fe.func->containsAggregationFunctionInFunction($state.supportedFunctions)`
  — the SINGLE aggregation trigger for qualifier bodies. If true,
  `addPkForAggregation` (L995 → def L3808) resolves the root set's
  PRIMARY KEY and pushes it as a `ColumnGroup` into `aggFromMap`
  (L1011) — the future GROUP BY key. No PK ⇒ hard fail with a named
  message (L3816): the grouped form is keyed by construction.
- `processQualifiedProperty` (L1061) — compiles the qualifier's BODY
  as an ordinary expression (L1072-1074, one expressionSequence
  supported), then milestoned qualifiers inside projection threads
  get isolation (L1087-1088).
- `processMap` (L3938) — map over a navigation; same trigger at
  L3957 (`containsAggregationFunctionInFunction` on the mapper) →
  `addPkForAggregation` (L3958).

### Reducer classification (what counts as aggregation)

- `isAggregationFunction` (L3900-3908): a function is an aggregation
  iff its registered PROCESSOR is one of `processAggregation`,
  `processStringPlus`, `processPlus`, `processJoinStrings`. Processor
  identity, not name matching.
- Registry (`getSupportedFunctions`, L9939+): `count` → 
  processAggregation (L9991); `average`/`max`/`min`/`maxBy`/`minBy` →
  processAggregation (L10138-10174); `plus(Integer|Float|Decimal|Number[*])`
  → processPlus (L10188-10191); `plus(String[*])` → processStringPlus
  (L10107); `joinStrings` → processJoinStrings (L10108-10109);
  `minus` → processVariableArity (L10181-10184; NOT an aggregation —
  minus never reduces).
- **`sum` has NO registry row.** It reaches the router as its pure
  body (`$numbers->plus()`), so sum IS collection-plus by the time
  routing happens — see 0c.

### THE DISPATCH (the fact §3 hangs on)

`processPlus` (L3232-3239), verbatim logic:

```
if ($f.parametersValues->size() == 1
    && !$f.parametersValues->at(0)->instanceOf(InstanceValue),
   | processAggregation(...)      // reducer: ONE non-literal collection arg
   | processVariableArity(...))   // scalar n-ary: infix chain / literal list
```

The engine's own choice between scalar-plus and reduce-plus is a
**pure syntax-shape test on the argument list** — no head lookup, no
topology, no mapping. Infix `a + b` arrives as `plus(InstanceValue
[a,b])` → scalar; `coll->plus()` / a collection-typed expression →
aggregation. The joinStrings analog: `buildJoinStrings` L8289
(`strings->size() == 1 && !aggFromMap->isEmpty()` → aggregation).
`processVariableArity` (L8234) additionally routes to
`processAggVariableArity` when `$state.inAgg` (explicit agg lambda
context).

### The grouped form (aggregation-over-navigation)

`processAggregation` (L4024-4056) → `manageAggregation`
(L4070-4100): the reducer DynaFunction becomes the column,
`groupBy = aggFromMap.values->last().columns` (the PKs, L4078), and —
decisive — when the aggregation reads THROUGH a join (currentTreeNode
has children, L4081-4093): `addSelfJoin` (L4116+) +
`buildCorrelatedSubQuery(shouldIsolateGroupBy=true)` (L4092). The
previously-ASSERTED claim is now VERIFIED twice over:

- CODE: `buildCorrelatedSubQuery` (L1181-1358) builds a NESTED SELECT
  rejoined as a TableAlias; with `isolateGroupBy` the GROUP BY and
  aggregate live inside the subselect (L1250, L1289-1304), joined
  back on key. It never emits a SQL scalar subquery.
- GOLDEN: `meta::relational::tests::map::testSubAggregationMultiLevel`
  — `… left outer join (select "firmtable_1".ID as ID, avg(1.0 *
  "persontable_0".AGE) as aggCol … group by …) …` — the named engine
  golden the design doc demanded for the subAggregation shape.

### The isolation chooser (three strategies = one placement bit)

`isolateSubJoins` (L7534-7612); enum L7485-7490. Decision procedure
(L7565-7581): forced override first; else in a PROJECTION THREAD —
`MoveFilterInOnClause` if the filtered node is a suitable direct
child, else `BuildCorrelatedSubQuery` (L7569-7572); outside
projection threads — `MoveFilterInOnClause` for suitable leaf nodes
under nested-filter/if contexts, else `MoveFilterOnTop`
(L7573-7577); and `MoveFilterOnTop` over a tree containing an INNER
join demotes to `BuildCorrelatedSubQuery` (L7581). Executors:
`moveFiltersOnTop` (L1149 — pred to top WHERE), `moveFiltersInOnClause`
(L1110 — pred ANDed into the join's ON, join renamed so it is not
shared), `buildCorrelatedSubQuery` (L1181 — filtered-subselect join).
Engine's own trade-off comments at L7584-7604 ("MoveFilterOnTop …
Cons: when n threads are projected some rows may be canceled if a
data point is missing").

**All three strategies are row-wise JOINS.** In row semantics they
collapse to predicate PLACEMENT: ON/subselect-WHERE = row-preserving,
top-WHERE = row-dropping. The chooser's heuristics are non-binding
mechanism (user ruling); the placement outcomes per consumption
position are the semantic spec, and 0b measures them.

### The exists / isEmpty family (semi-join, row-count-preserving)

Registry: `exists` → processExists (L9984); `isEmpty(Any[*])` →
processNotExists (L9990); `isEmpty(Any[0..1])` → processIsEmpty
(L10042); `isNotEmpty` → processIsNotEmpty (L10041). Class-typed
isEmpty routes into processExists (L4207). Dispatch (L5363-5368):
`buildExistsPredicate` when the target is a subselect/union
(`shouldBuildExistsPredicate` L5378-5386) — a true `exists (select…)`
SQL predicate; otherwise `buildExistsAsJoinWithNullCheck`
(L5394-5505): DISTINCT-key subselect (`distinct=true, columns=join
keys`, L5452-5456) rejoined under a `<child>_ecq` alias with
`isNotNull(key)` (negate → `isNull`) as the predicate (L5461-5463) —
and a fallback to the EXISTS predicate when the inner condition
reads root columns (L5438-5440, L5503). Both forms preserve root
cardinality by construction. This is the semantics that STAYS
semi-join under §8 (`isEmpty`/`isNotEmpty`/`exists` calls); plain
predicate-reads of navigations do not own it.

## 0c. PURE-SEMANTICS VERIFICATION

- `plus` (plus.pure L17-30): `plus(Integer[*]):Integer[1]` (+ Float/
  Decimal/Number) — native, stereotyped `functionType.ReducerFunction`,
  `PCT.grammarCharacters='+'`. The PCT tests use `1 + 2` and
  `[15,13,2,1,1]->plus()` against the SAME function: **pure has no
  binary plus; infix and collection plus are one function**, and the
  scalar/reducer split exists only at the call-shape level (see the
  processPlus dispatch above).
- `sum` (sum.pure L17-30): `sum(Number[*]) = $numbers->plus()`
  (all three overloads). **`->sum()` IS 1-arg collection plus,
  definitionally** — the design's "including pure's 1-arg collection
  ->plus() which IS sum" is verified in the strongest form: sum is
  DEFINED as it.
- `isEmpty`/`exists` compile to the semi-join family (0a above) —
  the "row-count-preserving semantics by definition" claim in §8 is
  verified against the emitting code.

## 0b. GOLDEN SHAPE SURVEY (script, not sampling)

Method: `tools/golden_shape_survey.py` (committed; re-runnable) over
the census universe — every distinct test in `nav-arm-census-4AD.txt`
(1,017) plus the design doc's named witnesses. For each test: locate
the engine source function, extract every `sqlRemoveFormatting`
golden string, classify shapes (regex over normalized SQL) and the
pure consumption position; FORCED = `::forced::`/`Forced` in the FQN
or `forcedIsolation` in the body. Witness dump:
`docs/golden-shape-survey-4AD.tsv` (1,332 test×golden rows; 1,014
tests located; the 3 stragglers are `mapping::in::testJoinWith*In
Clause*` — multi-line headers in
`tests/mapping/inClause/testInClauseForJoinsAndFilters.pure`,
IN-literal join-condition tests, located by hand, not
navigation-routing shapes).

**Headline: ZERO correlated scalar subqueries in the entire golden
universe.** Every `(select` in all 1,085 SQL goldens is an inline
view (`from (select`/`join (select`), a set-op member, or an
`exists`/`in` predicate — none in a SELECT list or comparison.
(Honesty note: the first classifier pass reported ~205
"scalar-subquery" rows; ALL were `from`/`join` inline-view false
positives — the corrected detector excludes `join|exists|in|from|
union|,` prefixes. Recorded so the correction is visible.) Combined
with the L1181 code reading: "zero correlated scalar subqueries for
navigations" is engine CONFORMANCE, measured AND code-verified. Our
`filteredNavLeafRead` remains the only producer of that shape.

Shape counts (test×golden rows; a row can carry several tags):

| shape tag | rows (default) | rows (forced) |
|---|---|---|
| top-where predicate | 689 | 20 |
| filtered-subselect join | 358 | 12 |
| on-clause literal predicate | 195 | 4 |
| plain join (keys only) | 203 | 0 |
| single table | 70 | 0 |
| grouped-subselect join | 28 | 0 |
| exists predicate | 25 | 0 |
| no SQL golden (result-only test) | 246 | 1 |
| correlated scalar subquery | **0** | **0** |

(1,309 default + 23 forced test×golden rows; a row can carry several
tags. Counts are POST-AUDIT: the extractor stitches concatenated
golden literals and accepts `with`-prefixed CTE goldens — see the
audit log below.)

The `_ecq` distinct-join exists form appears in ZERO goldens (grepped
across the whole engine tests tree) — committed exists goldens pin
either the `exists (select…)` predicate form or shapes upstream of
it; the `_ecq` form is code-verified only. `in (select` occurs 130
times (union-optimized / tdsContains families — IN-subquery
predicate, also row-count-preserving).

ON-clause cell provenance (default mode): dominated by milestoning
(~120 rows — temporal conds in ON, the known seam) and union/model
join constants; the qualifier-owned remainder is the ROW-PRESERVING
position below.

### The placement-bit table (measured, named witnesses per cell)

| consumption position | parked-predicate placement | row effect | witness (default-mode golden) |
|---|---|---|---|
| projection/TDS column thread (root must survive) | ON clause of the per-occurrence join (or subselect WHERE — row-equivalent) | row-preserving; missing nav ⇒ NULL cell | `projection::qualifier::testDerivedWithFiltering` + `…TwoProperties` (functions/tests/projection/testQualifier.pure): `on ("synonymtable_0".PRODID = "root".ID and "synonymtable_0".TYPE = 'CUSIP')`; golden expects `TDSNull` |
| map VALUE position (class nav read into a computation) | top WHERE | row-dropping; non-matching roots vanish (NULL fails WHERE) | `advanced::structure::testQualifierWithOperation` (testQueryStructure.pure:85-94) |
| map VALUE position, MULTI-occurrence | per-occurrence join copies; ALL predicates ANDed in ONE top WHERE | row survives only if EVERY occurrence matches | `advanced::structure::testTwoQualifiersWithOperation` (testQueryStructure.pure:96-105) — **settles the §5-addendum question by witness; the code-derived fallback is not needed** |
| FILTER position (slice 2 / §8) | top WHERE; per-occurrence join copies; each occurrence's qualifier-pred AND consumption-pred grouped, groups combined by the filter's own operator (OR in the witness) | fan-out kept at SQL level, NO DISTINCT; golden selects `pk_0` — the OBJECT layer dedups by PK (assertSize 1 object; raw SQL rows fan to 7) | `advanced::structure::testQualifierQueryWithOr` (testQueryStructure.pure:60-70) — the DEFAULT sibling of §8's forced witness; **flat joins, no subselect pair — simpler than the forced shape §8 was drafted from** |
| explicit reducer over nav | grouped subselect joined back on PK | one row per root; COUNT-zero via join-back | `map::testSubAggregationMultiLevel` |
| chained/mid-hop filtered nav | mid-hops BUNDLED inside each occurrence's self-contained filtered subselect | no cross-fan between occurrences | `mapping::tree::testProjectMerge`; `mapping::join::testChainedInnerJoinsWithQualifierInGroupBy` |

Canary note (`testChainedInnerJoinsWithQualifierInGroupBy`, full
golden read): the ENGINE compiles it as a fanned filtered-subselect
join (bridge ⋈ person ⋈ mid ⋈ extension inside the subselect,
qualifier pred in the subselect WHERE and echoed in the outer ON)
with a TOP-LEVEL `count(...)` from the explicit `groupBy([],…count…)`
— NOT per-root sub-aggregation, NOT correlated. The batch-5 target
shape for the canary is therefore the FANNED form + ordinary groupBy
machinery; batch 2 still attributes which of OUR arms carries it
today (that question is about our code, not the engine's).

## VERDICT ON §3

**CONFIRMED — two forms, joins only, zero correlated scalar
subqueries — with two refinements and no third form:**

1. **Reducer test tightened to the engine's own condition.** "The
   consumption IS an explicit reducer call" means: a registered
   reducer FQN applied to ONE non-literal collection-typed argument
   (`processPlus` L3234). N-ary infix / literal-list `plus` → FANNED;
   single-collection `->plus()` / `->sum()` / `->count()` /
   `->joinStrings()` → GROUPED. This is §3's intent stated as the
   measurable syntax predicate — and it is literally the dispatch the
   engine already runs, so conformance and syntax-direction coincide.
2. **The placement bit is a measured table, not a rule-of-thumb.**
   Row-preserving positions (projection/TDS column threads) park the
   predicate in ON/subselect-WHERE; row-dropping positions (map value,
   filter) park it in the top WHERE; multi-occurrence combines
   per-occurrence predicate groups in the top WHERE under the
   consumption's own operator. Every cell above carries a
   default-mode witness — nothing is code-derived-only.
3. Per the user ruling, the engine's three-strategy chooser is
   mechanism we deliberately do NOT copy: one owner (the lift
   pre-pass), two forms, one placement bit — better than the spec's
   implementation, row-equal to its semantics. Deliberate row
   divergences remain charter-adjudication-only.

Consequences for the batch plan (no reordering needed): batch 2's
arm-attribution census is unchanged (it measures OUR routing; 0b
measured the engine's); batch 5 inherits the placement table above
verbatim (the multi-occurrence rule is now witness-pinned); batch 7's
default mechanism shape is the FLAT per-occurrence join + shared top
WHERE (not the forced two-subselect form) with `pk_0`-style object
dedup left to the object layer exactly as today's charter decision 2
ratified; §8's "exists stays semi-join" boundary is now
code-verified at the emitting lines.

## PROVENANCE / LIMITS (kept honest)

- Shape classification is regex over normalized golden SQL; tags are
  per-golden, coarse (e.g. `top-where-pred` fires on any literal
  comparison in a WHERE). The TSV carries per-row SQL prefixes for
  spot-checking; the placement table's cells were each verified by
  reading the FULL golden, not the tag.
- 248 census tests have no SQL golden (result-only tests) — they
  constrain rows, not shapes; the oracle lane referees them.
- `assertSameSQL` and `assertEquals(…sqlRemoveFormatting)` are both
  captured (the literal-extraction regex keys on the SQL string, not
  the assert spelling).
- The engine checkout is the pinned oracle version (W10 re-pin,
  4.138.2 lineage) — line numbers cite that tree.

## AUDIT LOG (same day, user-ordered "audit the homework" — every
## receipt re-tested adversarially; all corrections applied above)

1. **Truncation hole FOUND AND FIXED.** The first extractor stopped a
   golden at its first string literal — 63 goldens across 48 tests
   (testDataGeneration, modelJoin::advanced, sqlstring families) were
   concatenations whose TAILS were never scanned. Extractor now
   stitches `'…' + '…' + $var + '…'` chains (non-literal chunks
   become a `¤` placeholder); the shape table above is the re-run.
   Movement was shape-refinement only (truncated single-table/plain
   rows revealed their joins and subselects); the zero-correlated
   headline held.
2. **Whole-tree raw-text sweep** (2,009 .pure files — independent of
   extraction, census universe, and concatenation): every
   `(select`-after-paren in the core_relational test trees resolves
   to a split-literal `join (select` inline view (4), a CTE (2), or
   ONE genuinely correlated scalar subquery that is COMMENTED OUT
   (functions/tests/projection/testFilter.pure:31 — dead code, not a
   golden). The headline is now verified against the whole tests
   tree, not just the census universe. (The 374 raw survivors outside
   it are dialect-module metamodel literals `^select(…)` and
   renderer source — not goldens.)
3. **Six extraction misses adjudicated**: 2 contains-assert quoting
   tests — including `testBiTemporalUnionAsJoinTarget_
   correlatedSubqueryQuoting`, whose asserts pin the
   buildCorrelatedSubQuery WRAPPER aliases (`as "unionalias_0"`),
   CORROBORATING the join-not-scalar reading; 2 routing tests whose
   goldens start with `with` (CTE — extractor now accepts them; they
   classify as join fan-out + CTE-level count); 2 relation-paradigm
   filter tests (no full golden).
4. **ON-cell mechanism verified at the model source** (the audit's
   sharpest check — was the ON predicate a qualifier's or the
   mapping join's?): `cusip(){$this.synonymByType(CUSIP).name}` and
   `synonymByType(type){$this.synonyms->filter(s|$s.type ==
   $type)->toOne()}` (simpleTestModel.pure:408,454) — a genuine
   filtered-navigation qualifier over the synonyms association. The
   ON placement in that golden IS the projection-thread chooser
   branch (L7570-7571) lifting a QUALIFIER predicate; the cell
   stands, now with its mechanism attributed.
5. **`sum` absence re-verified against BOTH registries**: no row in
   `getSupportedFunctions` AND none in
   `getContextBasedSupportedFunctions` (L9543+, entries are
   at/match/typeName/…): sum reaches routing only as its body.
6. **`buildExistsPredicate` upgraded from asserted to READ**
   (L5507-5573): emits `exists(<subselect>)` as a DynaFunction over
   a subselect projecting `1` — with a group-by/having relocation
   arm (L5543-5551) that moves outer GROUP BY into the EXISTS body
   when its columns aren't visible outside.
7. **Stale pointer corrected**: the design doc's "exists chooser
   ~L5607" lands inside `processIn`; the actual exists dispatch is
   L5363-5368 (`shouldBuildExistsPredicate` at L5378).
8. Known remaining coarseness (disclosed, not load-bearing): shape
   tags are regex-level; `top-where-pred` fires on any WHERE
   comparison; the "engine 7 raw rows" figure for
   testQualifierQueryWithOr is the charter's measurement, not
   re-measured here; the 3 multi-line-header census stragglers
   remain out of the TSV (located, IN-clause join tests, not
   navigation shapes).
