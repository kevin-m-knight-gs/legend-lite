# Relational Feature & Architecture Map — legend-engine vs legend-lite

**Status: IN PROGRESS (skeleton + wave 1).** This document is the full-depth survey of
every feature area in legend-engine's `core_relational` — the engine's semantic model per
feature (with source refs), the behavioral contracts its tests pin, the legend-lite
architecture piece that serves it, its corpus status, and a verdict:

- **SOUND** — our architecture models the feature the way the engine does; remaining work is incremental.
- **PATCHWORK** — we pass tests but the architecture grew by special cases; needs a redesign against the engine model.
- **MISSING** — no architecture piece exists; needs a designed leg.
- **DEFERRED** — feature model documented, implementation intentionally out of scope for now.

Purpose (user directive 2026-07-04): stop patching/special-casing/shooting in the dark;
make this map — not the test ledger — the driver of future work.

Engine source base (local checkout, all refs relative unless noted):
`legend-engine/legend-engine-xts-relationalStore/legend-engine-xt-relationalStore-generation/legend-engine-xt-relationalStore-pure/legend-engine-xt-relationalStore-core-pure/src/main/resources/core_relational/relational/`

---

## 0. The two architectures at a glance

### 0.1 Engine (legend-engine core_relational)

One 11k-line translator (`pureToSQLQuery/pureToSQLQuery.pure`) plus satellite files.
The semantic heart is ~18k lines of ~82k total:

| File | Lines | Owns |
|---|---|---|
| pureToSQLQuery/pureToSQLQuery.pure | 11,097 | the whole query translator: per-op processing, join trees, isolation, qualifiers, PMs |
| relationalExtension.pure | 2,022 | dyna-function → SQL registry (the scalar function catalog) |
| pureToSQLQuery_union.pure | 1,384 | union (Operation set) processing |
| testDataGeneration.pure | 1,366 | test data generation from mappings |
| dbExtension.pure + per-DB files | 1,308+ | dialect abstraction (DDL/DML/type mapping per DB) |
| relationalGraphFetch.pure | ~1,000 | graphFetch → SQL batching/temp-table model |
| milestoning/milestoning.pure | 938 | temporal context calculus |
| executionPlan/executionPlan.pure | ~900 | plan-node generation (allocation, TDS result, activities) |

Key architectural facts (established §A/§B of the H2/H3 plan, reconfirmed this survey):
- Per-column **select threads** carrying join trees, merged by **join-name identity** (`mergeSQLQueryData`); leanness comes from merge-by-identity, not from a pruning pass.
- **Isolation strategies** (MoveFilterInOnClause / BuildCorrelatedSubQuery / MoveFilterOnTop) decide nesting prophylactically; there is no post-hoc flattener.
- **No FK-shortcut, no join cancellation, no distinct-removal** — leanness is join dedup + prophylactic flatness only.
- Post-processor pipeline: CTE hoisting, pushFiltersDownToJoins, removeUnionOrJoins (Snowflake), replaceAliasName, prependSQLComments.
- One **milestoning context per query cursor**, rebuilt at explicit-date hops, propagated through same-strategy temporal parents, cleared at non-temporal hops.

### 0.2 legend-lite

Pipeline: `parser → normalizer → compiler/spec (G: Typer/InferenceKernel/checkers) →
resolver (H: StoreResolver + satellites) → lowering (I: Lowerer/Scalars/Fold) →
sql + sql/dialect (AnsiSqlRenderer/DuckDb) → exec (Executor) → harness`.

| Layer | Files (core/src/main/java/com/legend/…) | Role |
|---|---|---|
| normalizer | MappingNormalizer, RelOpTranslator, ViewRelation, JoinChainEmission, UnionSynthesis, AssociationSynthesis, Pipeline | mapping grammar → synthesized pure function bodies (conform-by-emission) |
| compiler/spec | Typer, InferenceKernel, JoinChecker, CastChecker, GraphFetchChecker | G: type-check everything incl. synthesized bodies |
| resolver | StoreResolver (~3.5k), ClassSources, Substitution, AssociationJoins, CorrelatedSubselects, NavMaterializer, Pipelines, TemporalFrame/TemporalContext, GraphEmission, SyntheticHeads, FlattenOps, InnerDemand, ScalarStats | H: class queries → relation-space TypedSpec via β-substitution over binding tables |
| lowering | Lowerer (~3.5k), Scalars (~3.5k), ListShapes, Fold | I: TypedSpec → SQL IR; fold policy = flat-by-default |
| sql, sql/dialect | SqlExpr/SqlFn/SqlAgg; AnsiSqlRenderer, DuckDb | renderer wall; SqlType enforced |
| exec, harness | Executor, TestBody, StatementExecutor | DuckDB execution; corpus harness runs the WHOLE pipeline |

Contract: row equality against engine-blessed expectations; golden SQL advisory.
Tenet: Java orchestrates, database executes — value evaluation always lowers to SQL.

### 0.3 Corpus status per family (full sweep @ HEAD 15cead69: 1403 pass / 62 fail / 434 err / 639 shape)

| Family | ERR | FAIL | SHAPE | Feature areas implicated |
|---|---|---|---|---|
| graphFetch | 100 | 12 | 3 | H4 graph output (biggest missing block) |
| functions | 75 | 14 | 35 | dyna-functions, projection functions |
| tests/mapping/union | 36 | 1 | 11 | union architecture (U4 leg) |
| tds | 32 | 2 | 35 | TDS ops long tail |
| milestoning | 31 | 2 | 42 | temporal context model |
| tests/mapping/association | 16 | 0 | 0 | navigation/joins (many are db-pull) |
| tests/query | 16 | 1 | 1 | views, qualifiers |
| transform | 16 | 4 | 15 | M2M relational |
| postprocessor | 13 | 0 | 22 | postprocessor features |
| classMappingFilterWithInnerJoin | 11 | 0 | 0 | mapping ~filter INNER |
| executionPlan | 10 | 0 | 100 | plan features (mostly out of scope) |
| inheritance | 9 | 0 | 0 | subtype mappings |
| sqlFunction | 9 | 3 | 0 | dyna registry |
| embedded | 8 | 1 | 3 | embedded/otherwise |
| modelJoin | 8 | 1 | 0 | model joins |
| aggregationAware | 6 | 0 | 11 | agg-aware rewrite |
| (others) | ≤7 each | | | see docs/RELATIONAL_CORPUS.md |

---

## 1. Feature taxonomy (engine directory structure = the feature list)

Survey waves; every area gets FULL depth:

- **Wave 1 (known-divergent):** query-translator core + isolation; milestoning; unions; views + embedded/inline/otherwise.
- **Wave 2:** graphFetch (H4); qualifiers/derived properties + auto-map; dyna-functions + dialect architecture; mapping metamodel (incl. inheritance/extends, modelJoins, multigrain, merge, include).
- **Wave 3:** aggregationAware + calendarAggregation; execution plans/TDS/result contract; postprocessors; tooling (lineage, testDataGeneration, mutation, autogeneration, constraints, functionActivation).

Sections 2+ below are filled per wave.

---

## 2. Query-translator core (joins, isolation, per-op processing)

### 2.1 Engine model (pureToSQLQuery.pure, 11,097 lines; all L-refs to that file)

**The machine.** Entry `toSQLQuery` (L215): `processQuery → applyPostProcessingForTempTableAsDriver
→ manageDeepMapClassColumns → .select → pushSavedFilteringOperation → pushExtraFilteringOperation
→ orderImmediateChildNodeByJoinAliasDependencies`. Three SELECT shapes: `SelectSQLQuery`
(base; columns, data = RootJoinTreeNode, filteringOperation, **savedFilteringOperation**
(node-keyed deferred filters), **extraFilteringOperation** (merge-later, deduped),
leftSideOfFilter, groupBy/orderBy/having/qualify/pivot/distinct/fromRow/toRow/CTEs),
`TdsSelectSqlQuery` (adds `paths`: TDS col → PathInformation(type, relationalType, PM,
doc)), `ViewSelectSQLQuery` (View + expanded select, L5642).

**Join tree.** `RootJoinTreeNode` (driver) + `JoinTreeNode` (alias, join, joinName,
joinType, lateral). Joins store BOTH directional alias pairs `[(src,tgt),(tgt,src)]`
(L2381, L9883) — target resolution (`otherTable`/`findTarget`) depends on it. **Node
identity across rewrites is by re-search, not reference**: `findNode`/`findNodeByChild`
(L5979–6014) re-locate "the same" node by join-name match → child index → `__iso` prefix
heuristics; `replaceTreeNodeAndUpdatePointersInSwc` (L1675) re-points all five live
pointers atomically; `validate` (L479) asserts pointer liveness after nearly every rewrite.

**Carriers.** `SelectWithCursor` (L67): parent (for exists/in scope resolution), select,
currentTreeNode ("where we are"; navigation joins hang off it),
positionBeforeLastApplyJoinTreeNode (exists/in cut point), savedRoot, milestoningContext.
`State` (L82): immutable, threaded; ~30 flags — the load-bearing ones: **`inFilter`** (the
master switch — every leaf writes to filteringOperation vs columns on it, ~80 sites),
`inProject`/`inProjectFunctions`/`processingProjectionThread` (steer isolation strategy),
`shouldIsolate`/`shouldIsolateNestedFilter`/`filterChainDepth`, `qualifierBase`/
`functionReferenceScope` (qualifier machinery), `functionExpressionStack` (detects
"last operation", restrict-distinct adjacency, paginated position). `FunctionParamScope`
(L128): lambda/let var → the SWC recorded at lambda entry.

**Per-op processing** (each handler `(fe, PM[*], SWC, vars, State, JoinType, nodeId, aggFromMap, …) → element`):
- getAll `processGetAll` (L5440): per-set dispatch (Root → processRelationalMappingSpecification
  L5556; RelationFunction; Cross → placeholder; Operation(union) → buildUnion). Builds root
  node, resolves all datatype columns, injects `pk_<n>` **only when addPk && no groupBy &&
  not distinct** (L5580–5596), applies mapping ~filter/groupBy, milestoning at L5631.
  `inProject` suppresses fetch-all-properties (L5451).
- filter `processFilter` (L5824): processes left (filterChainDepth++), rootSelect carries
  only applicable saved filters, binds lambda param, predicate with inFilter=true, merge,
  collapse WHERE to single AND, then channel decision (L5897–5914) and conditional
  `manageIsolation` (L5943).
- project `processProject` (L7975): asserts DataType returns; source with inProject=true;
  `removeColumnsAndJoins`; `addPkForAggregation`; each projection fn = own thread with
  `processingProjectionThread=true`, isolated via manageIsolation (L8018), then merged;
  emits TdsSelectSqlQuery with paths.
- groupBy `processGroupBy` (L6710): noSubSelect decision (L6721 — no existing
  groupBy/pivot/distinct/orderBy-outside-groupcols); flat or wrapped-subselect emission;
  always through `isolateNonTerminalGroupByQueryWithEmptyGroupingColumns` (L851 — empty
  grouping cols + non-terminal ⇒ wrap, keyed on functionExpressionStack size > 1).
- sortBy (L3349): lambda arm computes a `generated_order_key` column, merges, moves the
  expression to orderBy and drops the temp column.
- take/slice/drop/paginated (L3517/L3423/L4245/L3480): set fromRow/toRow then
  `isolateSubSelectIfNotLastOperation` (L3465 — wrap iff a following TDS op exists on the
  functionExpressionStack). paginated asserts sorted-first and last-position.
- restrict `processTdsRestrict` (L7374): restrict-then-distinct over a project-of-basic-cols
  triggers `processTdsRestrictOptimized` — rewrites into a narrower project, CUTTING joins
  for unprojected columns; else subselect only if un-projected groupBy/sortBy cols or
  distinct (L7464).
- TDS/relation join (L7511): both sides materialized as aliases, dup-column assert, ON
  lambda → Operation (boolean literal coerced to 1=1/1=0), one JoinTreeNode, re-alias all
  columns to the joined alias.
- pivot (L6795): always subselect-wrapped; dynamic pivot with >1 agg = UnionAll-of-groupBys;
  forces `select *` (result columns data-dependent).
- window: three arrival paths (legacy TDS WindowColumn; relation `_Window`; first/last/
  offset/nth inside map, L3980) → `Window(partition, sortBy, frame)`; distinct isolates
  first (L3701); isolate-if-not-last unless in extend (L3744).
- exists/isEmpty (L6016/L4432): isEmpty branches on raw type — DataType → null/size check,
  Class → exists. Two emission forms: `buildExistsPredicate` (correlated EXISTS, when alias
  wraps Select/Union with single child, L6149) else `buildExistsAsJoinWithNullCheck`
  (LEFT JOIN + IS [NOT] NULL, L6165).
- in/contains (L6351): literal collection → in/equal with enum source-value mapping;
  property-collection → EXISTS subquery (L6531).
- concatenate (L2776): flattens nested; no-data ⇒ top-level Union; else per-arm subselects
  grafted as a Union node; `alignJoinAndPkColumnsForUnion` (L2978) NULL-pads to a shared
  column list.
- CTE: leading `let`s become CommonTableExpressions (L2646–2732).

**Join & navigation.** Every projection fn / filter operand / property is its OWN select
thread (columns + the join path it needs); threads combine via `mergeSQLQueryData` (L9424)
/ `merge` (L9301): match children **by join.name equality** → absorb + remap aliases both
directions; no match → reprocess (unique re-alias) + append. `applyJoinInTree` (L9543)
grafts join chains; explode-in-ON routes to lateral-flatten emission (L9643); joinName
collision at siblings ⇒ suffix with `buildUniqueName` of the operation.
`orderImmediateChildNodeByJoinAliasDependencies` (L365) topologically sorts sibling joins
by alias cross-reference. Navigation default LEFT_OUTER (L1510/1518); INNER only from
INNER mapping-filter subselect wrap (L1765) or user TDS join kind. Distinct/groupBy target
classes re-materialize as subselects (L1785). `buildUniqueName` (L8527) is the canonical
structural fingerprint for join naming AND filter/column dedup (MD5-truncated >256 chars —
correctness, not cosmetics).

**Isolation model** (the part we never modeled). Driven entirely by
savedFilteringOperation + INNER joins. `manageIsolation` (L8186) fires iff
`shouldIsolate && (tree-has-children && containsInnerJoin || savedFilters nonempty)`.
`isolateSubJoins` (L8346): `findBestNodeToIsolate` (L8449 — deepest node common to all
root→leaf threads referencing the filter's aliases; backs off from INNER leaves;
reconciles with leftSideOfFilter), then strategy (L8377):
- projection thread: immediate-child found → **MoveFilterInOnClause**, else
  **BuildCorrelatedSubQuery**;
- filter context: leaf + shouldIsolateNestedFilter (or inIfTrueFalseStmt) →
  MoveFilterInOnClause; else **MoveFilterOnTop**;
- OVERRIDE: MoveFilterOnTop upgrades to BuildCorrelatedSubQuery whenever the root
  containsInnerJoin (L8393).
MoveFilterOnTop (L1138): filters → top-level extraFilteringOperation + join rename
(un-shares the join). MoveFilterInOnClause (L1098): fold each saved filter into the ON of
the node carrying its alias. BuildCorrelatedSubQuery (L1170): wrap found node in nested
SELECT, partition filters inside/outside, pull needed columns up with unique renaming,
re-point join at the `csq` subselect. Five OTHER nesting triggers: isolate-if-not-last
(take/limit/slice/drop/window), pivot always, empty-groupcol groupBy non-terminal,
distinct-source project/window/rowCount (`moveSelectQueryToSubSelect` L8600), filter over
aggregated/window TDS (WHERE vs HAVING vs QUALIFY routing, L3782–3799).

**Qualifier/derived-property model.** `processQualifiedPropertyFunctionExpression` (L952):
process left side, detect aggregation in body (pre-add PKs, L982), then
`processQualifiedProperty` (L1048): bind qualifier params into FunctionParamScope, process
the single body expression with **shouldIsolate=false** (filters accumulate as saved; the
CALLER isolates), set parent/positionBefore so enclosing exists/in cuts correctly. `$this`
= qualifierBase with filter cleared (L435). Param values re-process against the state
recorded at qualifier entry (L463) — how `$f.employeesByAddress($f.address)` binds.
Auto-map `processMap` (L3978): isolate source, PKs if body aggregates, dispatch body
(property/qualifier/lambda/path).

**Key invariants** (full list in agent archive; the load-bearing ones):
- inFilter dichotomy at every leaf; `or`/`not` move extraFilters into filter first (L5334, L3267).
- Enum translation is POSITION-dependent: filter/comparison → source values (in-list)
  (L8800–8850); projection (+flag) → inlined `case` (L5679); `if`-predicate arg disables
  enum pushdown (L5320); enum-returning `if` requires one transformer across branches (L4567).
- `==` requires both sides [0..1] — else "use exists" failure (L8675).
- pk_ names are matched downstream BY NAME PATTERN (self-join L4364, temp-table L8133).
- `__iso_`/`csq`/`_ecq`/`gen_` alias prefixes are semantic (merge/find/self-join key off them).
- Column-name comparisons must be quote-insensitive (L7359–7369).
- `extractColumnTypeDefaultToInt` (L1376): inference failure defaults the column type to
  Integer (legacy-compat, load-bearing).
- Dispatch: `getSupportedFunctions` (L10650–11097) + context-guarded
  `getContextBasedSupportedFunctions` (L10348, checked first) — the full inventory: TDS ops,
  ~40 string fns, ~50 math, ~40 date/time incl. calendar aggs, hash/regexp, ~40 variant
  fns, window family with frames, special modes (import-data-flow FK materialization,
  temp-table-as-driver, CTEs, model joins, cross-store placeholders).

### 2.2 legend-lite side

Our equivalent is split across three layers by design (the engine's one 11k-line file is
the anti-model):

- **Navigation → joins:** resolver `AssociationJoins` + `NavMaterializer` + `Substitution`.
  Join identity = NavPath (full path prefix from chain root), whole-op-chain registry,
  first-demand order — the engine's merge-by-join-name-identity outcome without a merge
  pass. Demand-gated: un-navigated association ends emit nothing (an advance beyond the
  engine, which always materializes navigation joins it builds but never builds undemanded
  ones either — net equivalent leanness).
- **Isolation/nesting:** lowering `Fold` policy — flat-by-default, per-op fold guards,
  `starOf(Subselect)` isolation. The engine decides nesting at construction (isolation
  strategies); we decide at fold time. Equivalent outcomes for the common shapes; our
  known gaps are exactly the engine's isolation strategies we never modeled:
  filter-over-to-many hoisting (MoveFilterInOnClause / correlated-subquery choices) is
  where several stopped legs (size-over-aggregate, derived-leaf inline) died.
- **Per-op processing:** compiler/spec typed nodes (TypedFilter/Project/GroupBy/…) +
  Lowerer arms. To-many nav in filter = correlated EXISTS (single form; engine's
  LEFT-JOIN-to-DISTINCT second form deliberately rejected — DuckDB decorrelates).

**Corpus status:** core shapes green (1403 pass incl. join torture); the residue clusters
in `functions` (75 err) and `tds` (32 err) long tails, plus isolation-shape gaps above.

### 2.3 Verdict & gaps

**SOUND core, with one structural hole: we never modeled the engine's filter-channel +
isolation calculus.** What's confirmed sound:

- Join dedup: our NavPath whole-chain registry ≡ engine merge-by-join-name outcome,
  without the engine's mutable-tree re-search machinery (findNode/validate/alias
  re-pointing) — our immutable rewrite makes that entire trap class (engine traps 2, 7, 8)
  structurally impossible. Deliberate, superior divergence; keep.
- Flat-by-default Fold ≡ prophylactic flatness; isolate-if-not-last, WHERE/HAVING/QUALIFY
  routing, distinct-source isolation all have equivalents.
- Position-dependent enum translation, LEFT-navigation default, INNER-mapping-filter wrap,
  correlated-EXISTS single form: adopted per plan §A.

The hole — the engine carries THREE filter channels (`filteringOperation` /
`savedFilteringOperation` node-keyed deferred / `extraFilteringOperation`) and a
strategy chooser (MoveFilterInOnClause | BuildCorrelatedSubQuery | MoveFilterOnTop with
the INNER-join upgrade rule). We have one channel (fold-into-WHERE) plus per-site
special cases. Every stopped leg maps to a missing strategy:
- filter-hoist-below-nav (union t18 filter, chained filtered nav #70) = savedFiltering +
  MoveFilterInOnClause;
- size-over-aggregate routing regression = projection-thread BuildCorrelatedSubQuery;
- derived-leaf inline wrong-value = qualifier shouldIsolate=false accumulation (filters
  must accumulate to the CALLER's isolation decision, never partially applied in place).

**Sane architecture:** introduce a resolver-level `DeferredFilter` channel (predicate +
the pipeline node it scopes to — our typed equivalent of savedFilteringOperation keyed by
NavPath instead of tree-node identity) and ONE strategy chooser at materialization:
fold-into-ON | correlated-subselect | hoist-on-top with the inner-join upgrade rule.
This replaces the per-site special cases in CorrelatedSubselects/InnerDemand and is the
prerequisite for the union push-into-arm leg (§4) and qualifier aggregation (§8).
Secondary gaps (feature inventory, not architecture): pivot, window frames beyond current
coverage, variant/semi-structured family, CTE-from-lets, paginated — enumerate against
the dispatch-table inventory when burning the `functions`/`tds` families.

## 3. Milestoning (temporal)

### 3.1 Engine model (milestoning.pure "M", PSQL = pureToSQLQuery.pure)

**The context object** — `TemporalMilestoningContext` (M L51–91), ONE per query cursor
(`SelectWithCursor.milestoningContext`):
- `processingDate` / `businessDate` : **RelationalOperationElement** [0..1] — resolved
  dates held as SQL elements (Literal, TableAliasColumn, or DynaFunction), NOT Pure dates.
  Dedup/equality by rendered form (`buildUniqueName`), never object identity.
- `currentMilestoningStrategy`: BusinessTemporal | ProcessingTemporal | BiTemporal
  (BiTemporal expands to [Business, Processing], M L820).
- `currentProcessingState`: an **8-value hop classifier** (M L34–43):
  MILESTONED_ALL_FUNC, MILESTONED_CLASS_PROPERTY, MILESTONED_CLASS_PROPERTY_NO_ARG,
  NON_MILESTONED_CLASS_PROPERTY, DATATYPE_PROPERTY, ALL_VERSIONS, ALL_VERSIONS_IN_RANGE,
  ALL_FOR_EACH_DATE. The pivotal predicate `currentProcessingStateIsMilestonedClassProperty`
  (M L86) = {MILESTONED_CLASS_PROPERTY, _NO_ARG, ALL_VERSIONS_IN_RANGE} — note
  ALL_VERSIONS is NOT in it. This gates intermediate-join filters.
- `startDate`/`endDate` for allVersionsInRange only.

**Context lifecycle.**
- **Seeded at root** per all() arity (M L830–844): 0 dates → allVersions; 1 →
  MILESTONED_ALL_FUNC; 2 → range (single-strategy) or bitemporal both-slots;
  getAllForEachDate special-cased.
- **Rebuilt at explicit-date hops**: `getMilestoningContextForQualifiedProperty`
  (M L846–868) — a generated-milestoned QP with date args builds a NEW context from its
  return-type class's stereotype; a no-arg milestoned QP INHERITS the incoming date
  (fails loudly outside a milestoned context, M L862); a NON-milestoned QP passes the
  incoming context through unchanged (M L855).
- **Explicit beats propagated**: an explicit `classification(%2015-10-17)` keeps its date
  while the root keeps its own — no override in either direction (goldens :282/:316).
  Two different dates on one chain = **two cloned table aliases** (`…_0`, `…_1`) each with
  its own filter — never two contexts (:290/:334).
- **Cleared at non-temporal hops** — but only for non-generated-milestoning properties
  (PSQL:2036–2041): temporal→non-temporal→temporal means the second temporal class must
  re-supply a date or use an edge-point property (`AllVersions`), which keep flowing.
- After each QP hop, `currentProcessingState` is reset to [] on the surviving context
  (M L552–556); final choice (PSQL:1002–1004): generated-milestoned QP → propagate
  realized columns; temporal return type → keep child context; else clear.

**Table-level model.** Milestoning blocks on tables: `business(BUS_FROM=from_z,
BUS_THRU=thru_z [,THRU_IS_INCLUSIVE][,INFINITY_DATE])`, `processing(in_z/out_z/
OUT_IS_INCLUSIVE/INFINITY_DATE)`, snapshot variants (`BUS_SNAPSHOT_DATE=col`).
Bitemporal = both blocks. Class-stereotype ↔ table-block matcher
`milestoningCanSupportTemporalStrategy` (M L743): business-temporal class may map to
BusinessMilestoning OR BusinessSnapshotMilestoning tables; Union supports a strategy via
member-table fold.

**Predicate builders** (each a distinct convention — do not conflate):
- default (exclusive thru): `from <= d AND thru > d`; inclusive flag flips to
  `from < d AND thru >= d` (M L403–410); flag consistency asserted across union members.
- range (allVersionsInRange): `from <= END AND thru > START` — a DIFFERENT pair (M L412).
- snapshot: EQUALITY `snapshotDate = d`, with datePart coercion for DATE col vs timestamp
  (M L387–401); `%latest` illegal for snapshot and for ranges.
- `%latest`: `thru = INFINITY_DATE`; requires declared INFINITY_DATE (hard fail M L608),
  identical across union members (M L612).
- allVersions: NO filter at all; `k_businessDate` = the inclusive boundary column.
- union: per-member **coalesce across member slots OR isNull(coalesce(all))** admitting
  non-milestoned members (M L349–385) — matches §4's union NULL discipline.
- bitemporal: 4-predicate AND (in_z/out_z + from_z/thru_z), k_processingDate then
  k_businessDate.

**Injection sites** (the processing algorithm):
1. Root scan: `applyMilestoningTypeFilters` (PSQL:5652) — guarded by `!allVersions &&
   !forEachDate && relationalElementIsMilestoned`; walks the tree
   (`applyMilestoningFilters` M L106–184: WHERE on SelectSQLQuery, **ON-clause AND-fold on
   JoinTreeNode**); appends synthetic `k_*Date` columns (only when class objects are
   returned, not TDS); resets state.
2. Navigation joins: filter lands in the LEFT JOIN ON of the milestoned target.
3. **Intermediate pass-through joins**: `getAppliedJoinMilestoningFilters` (M L542–550) —
   filtered when union-chained, or milestoned/non-milestoned-class-property state WITH
   children, or datatype-property terminus. Inside `applyJoinInTreeDeep` (PSQL:9567):
   isolation into a subselect when no inner join present, else pushed into the inner
   subselect's WHERE (goldens :175/:186/:195).
4. exists/in: predicate appears BOTH inside the correlated subquery and on the outer root.
5. Milestoned `==` equality is its OWN code path (PSQL:8684, M L230–261), incl. bitemporal
   top-level-`and` suppression — not "navigate then compare".

**Non-literal dates** — `resolveMilestoningDateParams` (M L638–681) dispatches:
variables (inScopeVars → Literal or plan VarPlaceHolder); **row-read dates**
(`$o.orderDate` → re-processed into a TableAliasColumn: `from_z <= "root".orderDate`);
function calls (reactivate if constant-foldable, else DynaFunction:
`from_z <= dateadd(day,-1, t.settlementDate)`); `$this.*Date` (re-entrant
TemporalMilestoningThisContext rewriting, M L45–66/683–704); getAllForEachDate calendar
column (`from_z <= calendar.calendar_date`). Router wrappers must be bypassed before
inspection.

**Full trap list, invariants, and 15 golden contracts:** archived in the agent report;
the load-bearing ones are reproduced above.

### 3.2 legend-lite side

Grew by accretion (the user's step-back critique names this area first): root
`TemporalContext` + chain-keyed spec map + per-audit propagation arms + `nestedFrame`
bolted on in cycle 71; `TemporalFrame` in the resolver applies from_z/thru_z / in_z/out_z
predicates at materialization sites. Passing 104/109 milestoning-family tests but there is
**no single context object flowing with the cursor** — each fix re-derived propagation
locally. Known-unserved: non-literal dates (variables, row-reads), two-dates-per-chain,
bitemporal-hybrid, generated-date reads in some positions.

### 3.3 Verdict & gaps

**PATCHWORK — confirmed.** The engine's design is a small, closed calculus: ONE context
value-object per cursor + an 8-state hop classifier + ~6 predicate builders + 5 injection
sites. Ours re-derives fragments of that calculus at each fix site (root TemporalContext,
chain-keyed spec map, per-audit propagation arms, nestedFrame). The specific mismatches:

1. **No processing-state classifier.** The engine's 8-value enum is what makes
   intermediate-join filtering, allVersions non-filtering, and clear-at-non-temporal-hop
   DECIDABLE locally. Our propagation arms re-answer "should this hop filter?" ad hoc —
   that's why each new shape (nested cursors, audit arms) needed a new patch.
2. **Dates as SQL elements.** Engine context holds Literal|Column|DynaFunction. Our tail
   (non-literal dates: variables, row-reads `$o.orderDate`, function-call dates, `$this.*Date`)
   is unserved precisely because our context carries literal dates only. Adopting the
   engine representation makes the whole non-literal tail ONE resolver
   (resolveMilestoningDateParams port), not four features.
3. **Two-dates-per-chain = alias cloning**, not context stacking. The engine never has two
   contexts; it clones the target alias per distinct rendered date. Our two-dates failures
   come from trying to thread both dates through one frame.
4. **Predicate-builder table**: exclusive/inclusive, range (different pair!), snapshot
   equality + datePart coercion, %latest infinity, union coalesce/isNull, bitemporal AND.
   We have the common two; the rest are the 42-shape/31-err tail.
5. **Milestoned equality as its own path** — we treat it as navigate+compare.

**Sane architecture (the milestoning redesign leg):** replace TemporalContext/
TemporalFrame accretion with a faithful port: `TemporalCtx` record (strategy,
processingDate/businessDate as TypedSpec-level SQL-able exprs, state enum, range bounds)
carried on the resolver cursor; `deriveAtHop(ctx, hop)` = the one propagation function
(port of getMilestoningContextForQualifiedProperty + state transitions);
`predicateFor(table, ctx)` = the builder table; injection at our 5 equivalent sites
(root scan, nav target, intermediate join, exists, equality). Port
testMilestoningContextPropagation as the behavioral spec first
([[port-engine-tests-as-spec]]). This SUBSUMES task #40 and the milestoning long-tail
(#32).

## 4. Unions (Operation set implementations)

### 4.1 Engine model (pureToSQLQuery_union.pure "U", union branches of pureToSQLQuery.pure "P")

**The model.** A union class mapping = `OperationSetImplementation` over N member sets;
member ordinal `i` = `resolveOperation` order and is load-bearing everywhere. At query
time a union is a **UNION ALL subselect** with a strict column discipline:

- **Driver getAll** (P L5463–5476): union → `buildUnion`, wrapped in a root alias literally
  named **`unionBase`**; outer select re-projects `u_type` + arm-0's value columns.
  Single surviving member ⇒ NO union node at all (degeneration, P L5464).
- **buildUnion** (U L174–274), the arm factory. Per arm: (1) member getAll (Root
  relational or `~func` relation-function member); (2) **PK slot synthesis**
  `managePrimaryKeys` (U L136): every arm carries the full cross-product of member PK
  slots `pk_<k>_<i>` — own member's real keys, siblings NULL-filled; (3) milestone
  columns suffixed `from_z_<i>`/`thru_z_<i>`, NULL-filled in siblings; (4) FK columns
  `fk_<i>` when navigating; (5) **value columns aligned to `allColumnsName`** — the
  union's common vocabulary; missing ⇒ `SQLNull` alias; (6) `u_type` discriminator
  prepended (driver context only), using the **deduplicated** member index while column
  suffixes use the RAW ordinal (self-union trap).
- **Value columns are merged un-suffixed** under `getUnionPropertyName` (P L1807 —
  concatenated unique names, e.g. `PersonSet1lastName_s1_PersonSet2lastName_s2`);
  **key/FK/milestone columns are per-member suffixed** — suffixing is what keeps split
  keys from cross-matching.

**Navigation.** Master routine `buildSQLQueryOutManySetImplementations` (U L408–514):
- FROM a union: per-member FK columns injected into each arm
  (`addMissingColumnToUnion` U L41–108, own FK populated, siblings NULL);
- INTO a union: target members normalized; >1 ⇒ target `buildUnion` aliased
  `unionAlias`/`unionalias_N`; operation-set as a nav target is REJECTED (U L483);
- join-back **OR-combined across member routes**
  (`simplyMergeJoinTreeNodeUsingOrOperation` U L584–631, emitting a tagged `UnionOrJoin`
  dyna): `on (uB.FirmID_0 = ua.ID_0 or uB.FirmID_1 = ua.ID_1)`. Union-to-union
  OR-combines the cartesian of legitimate routes; set-qualified PMs `prop[srcSet,tgtSet]`
  pin which routes are legitimate; extends-member override rule drops a PM whose source
  set is a super-set of another candidate's (P L1839–1847).
- **Merge-vs-push decision** (U L828–847): member join CHAINS that are not
  ordered-subsets of each other cannot be OR-merged — they are **pushed inside the union
  arms** (`pushChainedJoinsIntoSourceUnion`/`...TargetUnion` U L692–788): each arm applies
  its own residual joins internally, intermediate FK columns (`fk1_1`, `fk1_2`)
  materialize per-arm NULL-filled in siblings, and missing columns are hoisted up
  (U L790–826). The V1–V5 goldens exhaustively pin every source×target cardinality combo
  of this decision.
- Members with NO property mapping for the navigated property get a synthesized
  **null-join PM** (P L2011); its presence flips `avoidModeledProperties`, making FK
  columns RAW table column names (naming-consistency rule, U L388).

**Key invariants.**
- NULL discipline: only one slot per side non-null per row makes the OR-join safe; any
  coalesced fold MUST add the `... is null` disjunct (milestone guard:
  `(coalesce(from_z_0,from_z_1) <= d and coalesce(thru_z_0,thru_z_1) > d) or coalesce(all) is null`);
  the removeUnionOrJoins rewrite uses explicit `nullSafeEquals`.
- Member `~filter` stays per-arm WHERE inside the UNION ALL leg; never hoisted.
- **Aggregation over a union re-injects REAL per-member PK columns**
  (`addPkForAggregationInUnion` U L633, from P L3899) — grouping on the NULL-filled
  slots would collapse foreign-member rows.
- `-1` sentinel = single-set side, do NOT suffix (U L318/326).
- Nested materialization must re-expose every suffixed FK/PK/milestone column a parent
  join still needs — buildUnion's "project the entire raw column list" branch (U L222–231)
  exists solely for this (== our U4 raw-key-loss bug, named exactly).
- removeUnionOrJoins postprocessor (U L889–1385) = optional Snowflake-gated bridge-table
  rewrite, byte-identical results contract; the feature ships without it.

### 4.1a Behavioral contracts to port (goldens)
V1–V5 chained-join family (2/3/4-set unions, milestoned, push-vs-merge in every
combination); threeway union with overlapping FK/PK names; union self-join (manager);
chained-join + filter isolation (`unionalias_3` wrap); chained union aggregation
(group by pk_0_0, pk_0_1 + 4-way OR); `~filter` member golden; single-property/self-union;
union graphFetch root-level + milestoned (wave-2 crossover).

### 4.2 legend-lite side

`UnionSynthesis` (normalizer) emits per-member pipelines merged by lift; resolver
`Pipelines.widenConcatenateForKeys` + split-key OR join-back (ID_0/ID_1 each NULL
off-member); per-member ClassSources. The U4 leg stalled twice (cycles 77-78): raw keys
are lost through re-materialization because the merged lift in UnionSynthesis doesn't
preserve lifted keys — the fix belongs at synthesis, not at the join sites we probed.
Union family: 36 err / 11 shape; graphFetch-over-union untouched.

### 4.3 Verdict & gaps

**PATCHWORK — confirmed, and the engine read names our bug exactly.** Our split-key
OR join-back matches the engine's per-member suffixed model in spirit, but we are missing
four structural pieces of the engine's discipline:

1. **Raw-column re-exposure through nested materialization** (engine U L222–231 "project
   the entire raw column list" branch + chained-join column hoist U L790–826). This IS the
   U4 raw-key-loss bug that stalled cycles 77-78: our UnionSynthesis merged lift doesn't
   re-expose suffixed keys when arms get wrapped, and the join sites downstream can't see
   them. The engine solves it at the ARM FACTORY, not at join sites — confirming our probe
   conclusion that the fix belongs in UnionSynthesis, and now giving the exact contract:
   when a union is wrapped/materialized, every suffixed pk/fk/milestone column a parent
   may need is projected through, not just value columns.
2. **Merge-vs-push decision** (U L828–847 ordered-subset test): we have no equivalent —
   chained member joins that aren't OR-mergeable must push INSIDE the arms with per-arm
   NULL-filled intermediate FKs. Our nested-union lowering bug (t18) and N4 agg chains sit
   exactly here.
3. **Aggregation PK re-injection** (`addPkForAggregationInUnion`): grouping over a union
   must group on real per-member `pk_<k>_<i>` columns, never the NULL-filled slots.
4. **Ordinal discipline**: raw ordinal for suffixes vs deduplicated index for `u_type`;
   `-1` no-suffix sentinel for single-set sides; null-join PMs flipping FK naming to raw
   columns.

**Sane architecture (U4 redesign):** make our union emission a faithful arm factory —
one place (UnionSynthesis/Pipelines) that owns PK-slot synthesis, suffix ordinals,
NULL-fill, value-column vocabulary alignment, and re-exposure-on-wrap; give the resolver
the merge-vs-push decision as a named predicate (ordered-subset of alias chains); port
the V1–V5 goldens as the behavioral spec (per [[port-engine-tests-as-spec]]).
removeUnionOrJoins: out of scope (optional Snowflake optimization; ship without it).

## 5. Views

### 5.1 Engine model (REL = legend-pure relational.pure grammar; PSQL = pureToSQLQuery.pure)

**Dual identity.** `View` (REL L114–119) extends BOTH `NamedRelation` (queryable, has
name/columns/schema) AND `RelationalMappingSpecification` (columnMappings + ~filter/
~distinct/~groupBy + mainTableAlias) — and `RootRelationalInstanceSetImplementation`
extends the SAME RelationalMappingSpecification, so **a view and a class root mapping are
processed by the same code path** (`processRelationalMappingSpecification` PSQL:5556);
the getters getGroupBy/getFilter/getDistinct (PSQL:10242–10270) are polymorphic over both.

**The two outcomes, chosen by HOW the view is reached:**
1. Reached AS A RELATION (class mainTable, join endpoint, mainRelation) →
   `processRelation` (PSQL:5642): the view's spec is fully processed into an inner select
   and wrapped in **`ViewSelectSQLQuery` — which extends TABLE, not Select** (REL:270):
   a named, identity-carrying subselect with name+schema so all join/alias/PK machinery
   treats it like a physical table. **A view NEVER flattens into the outer query.**
2. A single view COLUMN referenced in an identity-sensitive expression (join condition,
   PK matching, self-join) → `findTableForColumnInAlias` (PSQL:10091–10108) recurses
   through columnMappings to the base physical table+column. Substitution exists ONLY for
   identity resolution, never for emission.

**Rules.**
- `~distinct` sets select.distinct + forces ALL declared columns materialized;
  `~groupBy` → select.groupBy; either SUPPRESSES synthetic pk_N columns (PSQL:5580).
  Views emit NO synthetic pks in any case (`viewSpecificationPrimaryKey` → [] PSQL:5703);
  view identity for self-join/graphFetch comes from the declared `View.primaryKey`,
  failing LOUDLY if absent (PSQL:4354).
- `~filter` with an INNER join tree is HOISTED into the view body pre-isolation
  (getRelationalElementWithInnerJoin PSQL:5535); LEFT_OUTER filter joins applied normally.
- View-on-view = natural recursion → nested ViewSelectSQLQuery; mainTable/findMainTable
  recurse to the base physical table.
- Two views over the same physical table = two separate ViewSelectSQLQuery instances with
  distinct aliases (alias = viewname+nodeId, PSQL:9853), disambiguated by schema when
  names collide — join correctly with no collapse (testViewToViewToUnion golden).
- Outer property predicates get DUPLICATED — pushed inside the view subselect AND
  repeated on the outer view column (testViewSimpleFilter golden) — a product of
  saved/extra filter isolation, deliberate.
- Inner view column aliases are UNQUOTED, outer class columns quoted
  (shouldQuoteColumnAliases PSQL:5707: View→false, Root→true).
- Milestoning applies inside the view body (isFromView flag, PSQL:5631/5658).
- View identity in tree matching is by object reference ($v.view == elem, PSQL:1550).

### 5.2 legend-lite side

`ViewRelation` (normalizer) emits view bodies as relation pipelines; plain views currently
substitute column expressions (flatten to physical table), non-plain (filter/distinct/
groupBy) become row-defining subselects. Cycle-84 probe: making plain views
identity-carrying converted 2 but regressed 5 view-on-view tests — same-root view-to-view
joins collapse wrongly. Conclusion already reached: views must be **identity-carrying join
frames** (named subselects when same-root collapse occurs); the discriminating rule needs
the engine's model.

### 5.3 Verdict & gaps

**PATCHWORK — and the engine read settles the cycle-84 question with a cleaner rule than
either of our probes.** The engine has NO plain-view/non-plain-view distinction:

1. **Every view reached as a relation materializes as a named identity-carrying subselect
   frame** (ViewSelectSQLQuery-as-Table). Our "plain views substitute column expressions"
   design is wrong at the root — that's why view-on-view and same-root view joins
   collapse. Column substitution exists in the engine ONLY as an identity resolver
   (resolving a view column down to its physical column for join/PK matching), never as
   query emission.
2. The engine gets this cheaply because **views and class root mappings share one
   processing path** — a view is just a RelationalMappingSpecification with
   quoting/pk differences. Our ViewRelation already emits view bodies as relation
   pipelines; the redesign is to make the resolver treat a view target exactly like a
   class-source pipeline target (named frame, own alias), deleting the
   flatten-to-physical-table arm entirely.
3. Details to honor: no synthetic pks for views + LOUD fail on self-join/graphFetch
   without declared `View.primaryKey`; INNER ~filter hoisted into the body; ~distinct/
   ~groupBy suppress pks and force full column materialization; milestoning applies
   inside the body; per-instance aliases for same-table views.

**Sane architecture:** view = class-source-shaped pipeline, one shared spec-processing
path (mirroring the engine's polymorphism), always framed. Expected corpus effect:
the view-on-view family, testViewSimpleFilter shape, and the union-view crossings
(testViewToViewToUnion) all sit on this one rule.

## 6a. Views/embedded shared machinery note

The engine builds mainTable/mainRelation/getFilter/getDistinct/getGroupBy/resolvePrimaryKey
ONCE over the RelationalMappingSpecification + InstanceSetImplementation abstractions —
views, root mappings, embedded (via setMappingOwner), and unions (member fold) all answer
through the same resolvers. Our equivalent seams are ClassSources/Pipelines; the survey
verdicts in §3–§6 should land as shared-resolver work, not per-feature arms.

## 6. Embedded / inline / otherwise mappings

### 6.1 Engine model (MAP = platform_dsl_mapping grammar; RMAP = relationalMapping.pure; PMI = functions_PropertyMappingsImplementation.pure; HELP = helperFunctions.pure)

**The type structure.** An embedded set-impl is SIMULTANEOUSLY a PropertyMapping and an
InstanceSetImplementation (MAP:80) — it maps the parent's property AND carries its own
propertyMappings. `setMappingOwner` always points to the owning ROOT set-impl (RMAP:52) —
PK/mainTable/mainRelation for ANY embedded (however nested) resolve through the root's
physical table (HELP:459–488). Three kinds:
- **Embedded** = same-row sub-instance, NO join, arbitrarily deep; navigation returns the
  source operation untouched (PSQL:1649), leaves become parent-row columns.
- **Inline** (`( ) Inline[targetId]`) = embedded delegating to another class mapping's
  property mappings — COPIED with owner/sourceSetImplementationId rebound
  (mappingExtension.pure:266; sharing by reference corrupts join reprocessing). Same-row;
  a join appears only if a BORROWED mapping itself joins. Supports subtype and non-root
  target ids; asserts exactly one root target (PMI:50).
- **Otherwise** (`( leaf:col ) Otherwise([id]:@join)`) = partial embedded + fallback PM
  (typically a join). **Per-leaf dispatch is name-driven**:
  `propertyMappingsByPropertyName(Otherwise) = body ∪ otherwisePropertyMapping` (PMI:84) —
  leaf in body → same-row column; absent → fallback join. Both coexist in ONE select.
  Deep traversal past an otherwise leaf forces the fallback join then chains into the
  target mapping's own joins; temporal targets re-enter with `milestoningUseOtherwise=true`
  (PSQL:713–718). GraphFetch flow sets `inGetterFlow`/`milestoningUseOtherwise` before
  descending (relationalGraphFetch.pure:775/805) — without these flags otherwise-dispatch
  silently doesn't happen in graphFetch.

Match order matters: Otherwise before Inline before plain Embedded (PSQL:1641–1650).
Enum pushdown works through embedded leaves; otherwise+union supported (owner chase
PSQL:1987).

### 6.2 legend-lite side

Embedded = same-row sub-binding tables in ClassSources (no join, parent-alias reads);
otherwise = per-leaf dispatch (embedded leaf → column read; fallback → association join),
canonicalized at extraction. Largely working (8 err / 1 fail / 3 shape, several being
cross-family db-pull). Inline-embedded delegation is the least-tested corner.

### 6.3 Verdict & gaps

**SOUND, with three named corners.** Our per-leaf dispatch (embedded sub-bindings vs
fallback join) matches the engine's name-driven model, and our extraction-time
canonicalization is a legitimate simplification of the engine's flow-flag machinery.
Corners to close: (1) **inline delegation** — we have no Inline arm; port as
copy-with-rebind at ClassSources extraction (never share binding objects across set-ids);
(2) **otherwise deep traversal + temporal re-entry** — verify our fallback join composes
with the milestoning context (§3 redesign should carry the context through the fallback
hop); (3) **graphFetch otherwise dispatch** — when H4 lands, the fetch-tree walker must
use the same per-leaf dispatch (engine needed explicit flags; ours should fall out of the
shared ObjectValue dispatch if we route graph emission through it — pin it).

## 7. GraphFetch (graph output)

*(wave 2)*

## 8. Qualified/derived properties + auto-map

*(wave 2)*

## 9. Dyna-functions & dialect architecture

*(wave 2)*

## 10. Mapping metamodel: inheritance/extends, modelJoins, include, merge, multigrain

*(wave 2)*

## 11. AggregationAware + calendar aggregation

*(wave 3)*

## 12. Execution plans, TDS & result contract

*(wave 3)*

## 13. Postprocessors

*(wave 3)*

## 14. Tooling features: lineage, testDataGeneration, mutation, autogeneration, constraints

*(wave 3)*

---

## 15. Synthesis: the coherent-architecture plan

*(written last: ranked redesign legs with designs, replacing ledger-chasing)*
