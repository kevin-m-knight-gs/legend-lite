# Relational Feature & Architecture Map — legend-engine vs legend-lite

**Status: COMPLETE (all 3 waves merged, 2026-07-23).** This document is the full-depth survey of
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

## 2a. The GENERAL mapping→SQL path (plain class mappings, end to end)

The verdict the rest of the map rests on: for a plain class mapping — column PMs,
dynafunction PMs, enum PMs, ~filter/~distinct/~groupBy/~primaryKey, association
navigation, project/filter/sort/take — how does our architecture compare to the engine's,
end to end?

### 2a.1 The two architectures are genuinely different — ours by design

The engine INTERPRETS the mapping metamodel at query time: processGetAll →
processRelationalMappingSpecification builds the root, resolves each PM on demand
(findPropertyMapping → processPropertyMapping per hop), and mutates a SelectWithCursor
whose tree-node identity survives rewrites only via re-search (findNode/validate + five
live pointers). We COMPILE the mapping once: MappingNormalizer synthesizes typed pure
bodies (tableReference → ~filter → map(^Class(...))) that G type-checks like any user
code (conform-by-emission), ClassSources strips the terminal into a binding table, and
the resolver β-substitutes bindings into user lambdas over an immutable tree.

**Verdict: our general path is SOUND and structurally superior on four axes** — each an
engine trap class made unrepresentable:
1. Mutable-tree identity (engine traps: re-search, __iso/csq semantic prefixes, alias
   re-pointing, both-direction alias pairs) — impossible: immutable rewrite, NavPath
   identity, fresh trees.
2. inFilter leaf dichotomy (~80 engine sites route output by a state flag) — impossible:
   typed nodes make position structural.
3. PM-resolution drift (engine resolves per hop with router-supplied vs incoming PM
   precedence) — resolved ONCE at extraction into bindings; dynafunction PMs inline by
   β-transitivity instead of a special "rebuild the function over resolved params" arm.
4. Type/string leakage — strings die at the parser; SqlType + dialect wall (the engine's
   emission is entangled through the 11k file; our I-layer is a real seam, and §9
   confirmed our two-registry dialect model matches the engine's own split).

Corpus evidence: 1403 passing including the join-torture and whole-chain-one-SELECT
families; the residue clusters in the four feature calculi (§15), NOT in the plain path.

### 2a.2 What the general path still owes (all already captured in legs, listed here as
the general-path view)

- **Identity/distinct discipline** (the one genuinely general gap): engine rules —
  pk_N injected only when addPk && no groupBy && !distinct; ~distinct forces
  all-properties materialization; and **navigation INTO a distinct/groupBy class
  re-materializes the target as a subselect** (never joins the raw table). That
  re-materialization rule is the same rule as views-as-frames (§5) and modelJoin target
  materialization (§10) — one shared resolver rule, three consumers (leg 15.0/leg 4).
- **The filter/isolation calculus** (leg 1) is a GENERAL-path mechanism, not a feature:
  plain queries hit it whenever a filter crosses a to-many hop or an INNER mapping
  ~filter composes with navigation. The classMappingFilterWithInnerJoin family (11 err)
  sits here.
- **Per-op inventory completeness** (pivot, window frames, paginated, variant/CTE) —
  feature coverage on top of a sound spine, burned as checklists.
- **Normalizer risk to keep pinned**: rules the engine applies lazily per query
  (set-qualified PM selection, extends override elimination, otherwise per-leaf
  dispatch) must survive INTO binding tables rather than being baked flat at synthesis.
  Our ObjectValue per-leaf dispatch and per-member ClassSources are the mechanisms;
  §10's PM-selection rules are their spec.

Net: general mapping/lowering needs no redesign leg of its own. It is the foundation the
legs build on — which is precisely why the legs are worth doing as calculi rather than
patches: the spine can carry them.

## 2b. Multi-hop navigation: the chain algebra

The recurring pain point, given its own verdict. Multi-hop = the composition of hops
(`$x.a.b.c`, filtered hops, to-many mid-chain, aggregates over chains, class-flatten
hops, qualifier hops) — historically our densest source of bespoke blocks.

### 2b.1 The engine's coherence — a 4-mechanism algebra (model coherent, machinery fragile)

1. **One hop function + cursor invariant**: every hop folds through
   processProperty → processPropertyMapping → doJoinToClass; EVERY feature (embedded,
   otherwise, union route, milestoned, view, distinct target) is an arm inside that
   per-hop dispatch, and every arm restores the same invariant — the cursor points at
   the hop's target frame. Chains compose across features because arms compose under
   one invariant.
2. **One sharing mechanism**: merge-by-join-name over per-column threads (prefix sharing).
3. **One cardinality mechanism**: filter channels + isolation chooser (to-many-in-filter
   → EXISTS; aggregate-over-chain → PK injection + grouped rejoin; below-hop filters →
   saved then placed by strategy).
4. **One target rule**: distinct/groupBy/complex targets re-materialize as subselects.

The engine's fragility is in HOW the invariant is maintained (live-pointer re-search,
`__iso` prefixes, qualifier re-entry through recorded state) — and its own test.ToFix
wrong-goldens cluster exactly at multi-hop × qualifier × isolation (P:188, ADV:237).
Even the reference strains at that intersection.

### 2b.2 Our state: right primitives, scattered decision

NavPath whole-chain registry beats the engine's sharing (keep). But "a hop" is served by
context-dependent mechanisms — AssociationJoins, NavMaterializer ([0..1] correlated),
CorrelatedSubselects, FlattenOps (below-op splice), SyntheticHeads, tail mappers,
parent-copy grouped subselect — and the EMISSION decision (flat join | correlated
subquery | EXISTS | grouped rejoin | splice) is made at multiple sites with local
heuristics. Every bespoke wall (#70 chained filtered nav, #63 auto-map class-hops,
size-over-aggregate, navLeafSubquery) landed in the seam BETWEEN two mechanisms, where
neither owned the decision. The positional rule table exists in the H2/H3 plan; it was
never promoted to the single authority as features accreted.

### 2b.3 The coherent target (this is Leg 1 + 15.0 seen from the chain's point of view)

Not a new mechanism — a re-organization making the existing ones arms of one decision:
1. **`Hop` algebra**: one resolver function `hop(frame, pmKind) → frame` with per-kind
   arms (column read / NavPath join / embedded parent-read / otherwise per-leaf /
   union OR-route / re-materialized target), each restoring one frame invariant.
   Existing satellites become the arms' implementations, not competing owners.
2. **One positional emission table**, consulted at ONE site at materialization
   (fed by Leg 1's DeferredFilter channel):
   - projection / sort-key / groupBy-key path → LEFT JOIN via NavPath registry
   - filter-position to-many (and class isEmpty) → correlated EXISTS
   - scalar isEmpty → IS NULL
   - aggregate over a chain → PK-grouped subquery rejoin (parent-copy shape)
   - ops below a class-flatten hop → splice (FlattenOps arm)
   - qualifier hop → inline body, filters ACCUMULATE to caller's isolation (never
     partial application, never LIMIT)
3. **One target-materialization rule** shared with views/modelJoin/distinct (15.0).
Pin the seams explicitly: filtered-hop × aggregate, flatten × limit, union-route ×
chain, qualifier × isolation — one fixture each, because that intersection is where
both we AND the engine historically break.

Verdict: **PATCHWORK at the seams, sound at the primitives** — and the seams are not a
fifth leg; they are the chain-side statement of Leg 1 + the shared foundation. Treat
2b.3's emission table as Leg 1's acceptance criteria.

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

### 7.1 Engine model (relationalGraphFetch.pure "GF", 1087 lines)

**NOT one big join.** The engine compiles a graphFetch tree into a TREE OF EXECUTION
NODES — one independent `SELECT DISTINCT` per class-level tree node, stitched by the
EXECUTOR, with the parent's PK set as the driver:
- Root node: SQL projecting root `pk_*` + to-one primitive properties (only to-one plain
  Properties inline — qualified properties NEVER inline, they always become child nodes).
- Each complex child: separate query whose FROM is the parent-PK temp table
  (`${temp_table_node_N}` placeholder; real temp table only on H2/Snowflake, VALUES
  inlining elsewhere) INNER JOIN main table LEFT JOIN property tables — projecting
  `parent_key_gen_*` (stitch keys) + own `pk_*` + primitives, with AND-of-`pk_* is not
  null` to suppress phantom children (GF:496–499).
- Executor groups child rows under parents by parent_key_gen; multiplicity from the tree
  decides object vs array. Temp table carries ONLY pk columns (GF:387).
- Cross-store children = root-like query on the target store driven by
  `parent_cross_key_*`; strict XStore constraints (single-property traversal,
  primitive-only keys, Varchar(4000) key type).

**Invariants:** PK definition REQUIRED (GF:663); store ~groupBy/~distinct FORBIDDEN in
graphFetch (GF:664–665) yet every generated query is `distinct=true` (identity dedup in
SQL); unions unsupported in this path (GF:950; union graphFetch is separate/newer);
aggregating qualifier children get GROUP BY over non-result columns, non-agg get
isNotNull; inner-join mapping ~filter switches the driver to a subquery (`_gftm`).

**Serialization contracts** (the part ANY implementation must honor):
- to-one absent → `null`; to-many absent → `[]`.
- JSON keys are semantically loaded: qualifier signature (`"name()"`,
  `"nameWithTitle('Mr')"`), milestone date (`"product(2015-10-16)"`), tree alias
  overrides (`'fn':name` → `"fn"`).
- primitives: date `"2003-07-19"`, timestamp with nanos, booleans, nulls.
- `graphFetchChecked` → `{"defects":[],"value":{...}}` envelope; defect records carry
  id/externalId/message/enforcementLevel/ruleType/ruleDefinerPath/path; checked and
  enableConstraints change NO SQL — executor-side.
- subtype subtrees: rows not of the subtype simply omit those properties; alloy config
  adds `@type` + base64 object references encoding PKs.
- batchSize only from `graphFetch(tree, size)` overloads.

### 7.2 legend-lite side & verdict

**MISSING (H4) — and the survey licenses a deliberate architectural divergence.** The
engine's per-node temp-table pipeline exists for cross-DB portability and streaming
batching. Our tenet (Java orchestrates, DATABASE executes) + DuckDB's JSON support make
the planned single-query snapshot envelope (json_group_array(json_object(...)) with
correlated subqueries per child) the right shape for us — the engine's own
GraphFetchLowering confirms graphFetch is source-preserving at the SQL layer with the
envelope in serialize.

What we must PORT from the engine model regardless of emission strategy:
1. **Identity semantics**: per-node DISTINCT-by-PK — a to-many child reached through a
   fanning join must dedup by child PK inside its array; PK required, loud without one.
2. **Phantom suppression**: AND-of-pk-not-null before a child object materializes.
3. **The serialization key contract** (qualifier signatures, milestone dates, aliases,
   null vs []).
4. **Qualified properties as child computations** (never inlined into the parent row).
5. Store ~groupBy/~distinct forbidden in graph flow; checked envelope executor-side.
6. Embedded/otherwise per-leaf dispatch inside trees (§6).
Out of scope for now: cross-store children, temp-table strategies, batch streaming,
alloy object references.

## 8. Qualified/derived properties + auto-map

### 8.1 Engine contracts (test-derived; P/Q/ADV/MAP/ATM/TREE = engine test files, IMPL = pureToSQLQuery.pure)

**The governing invariant — TRANSPARENCY:** a qualified property is indistinguishable from
manually inlining its body (pinned by consistency tests modulo alias numbering). The body
is position-INDEPENDENT; the surrounding SHAPE is position-DEPENDENT:
- project/filter, body touches owning table only → verbatim inline, no join, no subquery.
- through association → the join emits once; body binds to the joined alias.
- parameterized: scalar/enum args → SQL literals (enum via EnumerationMapping source
  value, NOT the enum name); root-correlated args ($f.legalName, $this.date) → correlated
  column refs folded into the join ON, operand order preserved (never normalized).
- **aggregate anywhere in a QP/map body** → `addPkForAggregation` grouped-subquery-rejoin:
  `left outer join (select PK, agg(...) as aggCol ... group by PK) on root.PK` —
  post-aggregate arithmetic stays OUTER referencing aggCol; hard-fails without a PK
  (IMPL:3855). The isAggregationFunction whitelist surprisingly includes plus/stringPlus/
  joinStrings.
- isEmpty/isNotEmpty on to-1 complex → `select distinct PK` existence subquery + CASE on
  null-ness (branch order flips with the predicate).
- chaining a QP off an already-joined table isolates the type-filter into a subselect
  (can't share the ON); single-hop keeps it in the ON-clause. Forced-isolation debug
  goldens (FQ) pin all three strategies on one query.
- mapping ~filter ANDs into the QP-generated join ON.
- null-safe equality `a=b or (a is null and b is null)` fires by column NULLABILITY, not
  syntax.
- toOne()/first()/at() NEVER emit LIMIT — multiplicity assertions that only steer
  isolation. (Our cycle-86 navLeafSubquery LIMIT-1 wrong-value bug violated exactly this.)
- Single-expression rule: multi-statement QP bodies rejected (IMPL:1060).

**Auto-map:** implicit `.prop` over to-many ≡ explicit ->map (pinned identical SQL).
Project position → LEFT JOIN row explosion, no dedup; filter position → distinct-FK
semijoin + is-not-null; aggregation → grouped subquery keyed on the PK of the MAP SOURCE
set (per-employee vs per-firm vs global distinguished only by what encloses the map);
duplicate navigations reuse one alias.

**Paths (#/Class/prop!alias#):** path ≡ lambda for the same navigation; segments fold
left-to-right, QP segments (with args) allowed mid-path, navigation continues past a QP
result; `!alias` names the TDS column; aliasing suppressed in filter position.

**Trap:** engine `test.ToFix` goldens assert current-but-WRONG behavior (P:136, P:188,
ADV:237) — never port those as contracts.

### 8.2 legend-lite side & verdict

**Mostly SOUND, gaps named.** Qualifier inlining via Substitution = the transparency
invariant by construction. The parent-copy grouped subselect (task #77) = the
addPkForAggregation shape; tail mappers cover deep sub-agg. Gaps:
1. **Derived-leaf-inline in nav position** (cycle-86 revert) — the engine rule is now
   explicit: inline the body at the hop, let ISOLATION (not LIMIT-1 subqueries) handle
   to-many; requires §2's DeferredFilter/strategy chooser first.
2. **Root-correlated QP arguments folded into join ON** — check our Substitution scope
   handling against P:240/243 (arg referencing the OUTER row inside the join condition).
3. **Null-safe equality by nullability** — we have pure!=null semantics work (#62);
   verify the complex-type nullable-column arm matches ADV:104 vs ADV:111.
4. **isEmpty existence-subquery CASE shape** for to-1 complex (P:117/125).
5. Paths: parser-level sugar; verify our normalizer's path handling covers QP-segments
   with args + `!alias` (TREE family).

## 9. Dyna-functions & dialect architecture

### 9.1 Engine model (REL = relationalExtension.pure; DBX = dbExtension.pure; DEF = extensionDefaults.pure; H2/DB2 = dialect files)

**TWO independent registries keyed by the same dyna names** (never conflate):
1. **Type-inference registry** (REL:192–1887): dyna name → ordered list of
   (matcher-guard, type-producer) pairs; first matching guard wins; numeric promotion via
   the safe-type lattice (TinyInt→…→Decimal, Varchar size=max, precision capped at 31);
   uninferrable string size defaults 1024.
2. **Rendering registry** (DEF:180–295 ANSI + per-DB): dyna name → `ToSql{format,
   transform, contextAwareTransform, parametersWithinWhenClause}`. Per-DB composition is
   `default->groupBy(name)->putAll(dbSpecific->groupBy(name))` — **whole-template
   replacement per name, no partial override**. Dispatch filters by GenerationState
   (Select/Where × withinWhenClause) and asserts exactly one survivor.
   The legal-name universe is the `DynaFunctionRegistry` enum (DBX:1080–1308, 300+ names).

**DbExtension** = a record of function slots (literalProcessor, dynaFuncDispatch,
selectSQLQueryProcessor, joinProcessor, identifierProcessor, dataTypeToSqlText, limits…)
with `default::` fallbacks per slot. Literals: ANSI quotes booleans ('true'); H2 unquoted
TRUE + `CAST(%s AS FLOAT)` float literals + DATE'…'/TIMESTAMP'…'; date literals default
GMT. Identifier quoting: global flag OR reserved word OR embedded space; FreeMarker
`${…}` never quoted. LIMIT dialects diverge structurally (limit N / offset-fetch /
fetch-first / top), with limit arithmetic pre-computed. H2 emulates FULL OUTER as
UNION ALL of LEFT+RIGHT with a `_lhsJoinValid_` sentinel.

**Load-bearing per-function semantics** (the agent archive has the full ~300-name
catalog; these are the correctness-critical ones):
- 1-BASED string indexing everywhere (substring, indexOf→LOCATE with swapped args).
- `mod` is SIGN-NORMALIZED `mod(mod(a,b)+b,b)`; `rem` is raw mod. Distinct functions.
- division/average force float: `((1.0*a)/b)`, `avg(1.0*x)`.
- `round` defaults 2nd arg 0; projection wraps `cast(round(x) as bigint)`;
  ceiling/floor project through `cast(cast(... as numeric) as bigint)`.
- null-safe equality ONLY in filter position on two nullable columns (DBX:926–948);
  `not(equal)` / `not(in)` add OR-is-null arms depending on which side is a literal.
- `if`/`case` args render within-when-clause; `coalesce` n-ary; `concat` null semantics
  and rendering differ per DB (DB2 infix `concat`).
- Filter contract: the projected expression string is REPEATED VERBATIM in WHERE — same
  rendering in both positions.
- adjust/dateDiff unit maps (WEEKS→7·DAYS on DB2); firstDayOf* families are date
  arithmetic, not calendar-table lookups.

### 9.2 legend-lite side & verdict

**SOUND architecture, catalog-completeness gap.** Our builtin/Pure catalog +
Scalars/SqlFn + AnsiSqlRenderer/DuckDb mirrors the two-registry split (signatures typed
in Pure.java = inference; Scalars/dialect = rendering), and the wall between them is
enforced. Verified-equivalent semantics already: 1-based substring (verbatim passthrough
+ H2 clamp), float division, null-safe filter equality (#62). Actions:
1. **Audit our catalog against the DynaFunctionRegistry enum** — the `functions` family
   (75 err/35 shape) is mostly missing names; burn it as a checklist against the agent's
   catalog table, family by family, not test by test.
2. mod-vs-rem sign semantics; round/ceiling/floor cast-wrapping in projection; adjust
   unit map — verify each against goldens when touched.
3. The H2 advisory backend (#67): the engine's H2 file IS the spec for it (member
   renderings, offset-fetch limits, FULL OUTER emulation, UDF-based JSON) — plus
   `assertEqualsH2Compatible` explains dual goldens in the corpus.
4. Multi-dialect seam: keep per-name whole-template override (our dialect override map
   should mirror putAll semantics — no partial merges).

## 10. Mapping metamodel: inheritance/extends, includes, merge, multigrain, modelJoins, cross-store, associations

### 10.1 Engine model (P2S = pureToSQLQuery.pure; MJ = modelJoins.pure; tests under $E/tests/mapping/)

- **Inheritance/subtypes** — two mechanisms: (a) Operation set-impl with the
  `inheritance_...` operation fanning an abstract-class query to concrete subtype
  mappings (mixed-type results); (b) `subType(@T)` navigation cast mid-query. Unmatched
  subtype columns project TDSNull, rows never dropped. Embedded mappings can live inside
  per-subtype mappings on ONE table. `root==true` (`*` in grammar) marks the primary
  mapping among several for one class. Known engine gap (ToFix): qualified property from
  an unmapped superclass.
- **Extends** — `X[child] extends [parent]` inherits mainTable, PK, ~filter/~groupBy/
  ~distinct, and property mappings; overrides by property name; transitive chains; store
  substitution composes. **The PK-resolution matrix** (testExtendsForPrimaryKey:238–258)
  is the authoritative 4×4 truth table for child×parent {groupBy, distinct, userPK,
  nothing} — counterintuitive corners (child userPK loses to parent distinct → [id,aName]).
- **Includes** — transitive; `include M[src->dst]` store substitution is per-include-edge;
  `resolveStore` on a non-substituting mapping silently maps foreign→local (not an
  error); class-mapping ids must be unique across the include closure.
- **Merge** — union of set-impls + multi-hop join expressions inside one PM
  (case/and/or over `@J1 > @J2 > @J3` chains). Missing union branch column ⇒
  `Literal('__SQLNULL__')` (never dropped); same table via two join paths ⇒ two aliases,
  NEVER deduped by table name.
- **Multigrain** — MultiGrainFilter (discriminator ~filter per grain on one table) with
  THE suppression rule (P2S:1758–1771): root grain filter → WHERE; joined grain via
  non-PK column → discriminator pushed into ON; joined grain via a **single-column PK
  equi-join** (`isSimpleJoinToPk` P2S:1792) → filter SUPPRESSED (grain already pinned).
  distinct/groupBy short-circuit filter handling entirely, before the multigrain check.
- **ModelJoins** — association bound by a Pure lambda over the two ends instead of a DB
  @Join. Operator whitelist {equal,not,and,or,comparisons}; only end-var navigation +
  literals. TWO implementations: static localization (MJ:39–143, → synthetic
  RelationalAssociationImplementation + Join in a synthetic DB) and the newer
  union/milestoning-aware per-branch runtime compilation (P2S:2208–2304: placeholder
  `ON 1=1` join, `_mj_src`/`_mj_tgt` scope binding, conditional target
  materialization-as-subselect when the target has filter/groupBy/distinct or nested
  navigation). Goldens: condition compiles into the ON (incl. lower(), concat, arithmetic,
  IN — the IN predicate is pushed BOTH into the target subselect WHERE and the ON).
- **Cross-store** — CrossSetImplementation → synthesized root set over a placeholder
  table (`Gen_Cross_DB`), primitive cross columns as propertyPlaceHolder columns filled
  at execution.
- **Associations** — `AssociationMapping(end1[srcId,tgtId]: @J, end2[tgtId,srcId]: @J)`;
  default ids only legal when each end class has exactly ONE mapping; self-association =
  two class-mapping ids for the same class; multi-join chains `@J1 > @J2` are
  direction-specific (reverse spells the reverse chain); intermediate tables need PKs.

Provenance note: the core set-impl metamodel classes live UPSTREAM in legend-pure (this
matches [[verify-signatures-against-real-legend-pure]] — legend-pure checkout is part of
the spec base).

### 10.2 legend-lite side & verdict

**Mostly SOUND — these families are largely passing** (inheritance 9 err mostly db-pull;
extends 2 fail; modelJoin 8 err; multigrain 1 err). Named gaps to close from the read:
1. **Extends PK matrix** — our extraction inherits parent pipelines; verify the 4×4
   table's corners (esp. child-PK-loses-to-parent-distinct) with pins.
2. **Multigrain suppression rule** — check we re-apply grain filters on non-PK joined
   grains into the ON and suppress on single-column-PK joins; our TemporalFrame-adjacent
   filter placement should reuse §2's DeferredFilter channel.
3. **ModelJoin target materialization condition** — ours must subselect the target when
   it has filter/groupBy/distinct or deep navigation (same rule as §5 views / §2
   distinct-target re-materialization — one shared rule, three consumers).
4. **`__SQLNULL__` merge-branch behavior + never-dedup-aliases-by-table-name** — pins.
5. Cross-store: DEFERRED (placeholder-table model documented for when it matters).

## 11. AggregationAware + calendar aggregation

### 11.1 Engine model — aggregationAware (AA = core/store/aggregationAware/aggregationAware.pure — NOTE: the engine lives in legend-engine-core, NOT under core_relational; $E has only tests)

A pre-aggregation REWRITE that runs before store planning:
- Mapping = `~mainMapping` (fallback) + ordered `Views`, each `~aggregateMapping`
  (relational set over the agg table) + `~modelOperation{~canAggregate,
  ~groupByFunctions, ~aggregateValues(~mapFn/~aggregateFn over the magic $mapped var)}`.
- **Only a top-level object `groupBy` triggers table switching** (AA:109); getAll/
  filter/project reprocess but bind to the main table. The collection under the groupBy
  must be exactly getAll(+filter) — anything else falls to main.
- Matching: query dimensions/measures compiled to order-insensitive **ProjectPaths**
  (qualified properties INLINED first; numeric aggregates canonicalised
  Integer/Float→Number so sum(Float) matches sum(Number)); `groupByMatch` = every query
  dim rewritable against view dims AND (canAggregate OR query grain == view grain);
  `aggValueMatch` = each measure matches a view spec (mapFn AND aggregateFn separately —
  max never matches a sum-only view) or is reachable as a dimension. **First matching
  view wins (mapping order significant); no match → main table, never an error.**
- `canAggregate=false` NARROWS a view to exact-grain queries; doesn't disable it.
- Observability: AggregationAwareActivity carries the rewritten query string (the goldens
  assert on it).

### 11.2 Engine model — calendar aggregation (CF/CP/CS = calendarFunctions/PureToSQL/Store)

34 grammar-stub functions `f(inputDate, calendarType, endDate, inputValue)` resolved ONLY
by the relational store:
- **A precomputed calendar DIMENSION table** per region (`NY_Calendar`, 24 columns:
  fiscal ordinals, offsets, prior-week dates, previous-period columns…). All window
  logic = table lookup on TWO LEFT OUTER self-joins: `calendar_0` keyed on the row's
  inputDate, `calendar_1` keyed on the endDate expression. Emission =
  `sum(case when <conditions over the two calendar rows> then value[/normFactor] else null end)`.
- Families: current (cw/reportEndDay), to-date (wtd/mtd/qtd/ytd — `<=` on a fiscal
  ordinal), previous-to-date (+prev-year rollover via precomputed columns, NOT
  arithmetic), previous-whole-period, rolling nX (date-range, adjusted-date aware),
  means (divide inside the case by 4/12/52 or a calendar ratio; pywa null outside
  first-5-weeks). p12mtd is the ONLY function using SQL date arithmetic.
- Fiscal week starts Saturday; weekend rollback differs per family (cw −1/0, pw −2/−1,
  _fm variants ignore weekends).
- Distinct endDates ⇒ distinct calendar_end aliases shared across sibling aggregates;
  union-mapped classes take a different join-splice path (unionBase form).
- The 27 date-range goldens ([min,max] of included dates per function @ 2022-11-16
  anchor) are the perfect behavioral spec — they pin windows without pinning SQL.

### 11.3 legend-lite side & verdict

**Calendar: SOUND** (G1 burn landed the natives; corpus 0 err / 4 shape). Verify against
the date-range window table: weekend variants (Sat/Sun endDates), start-of-year
rollovers, different-endDates alias sharing, and the union splice path.
**AggregationAware: PARTIAL.** We pass the current 11-shape family via targeted fixes
(demand-aware milestoned-slot gate) but don't have the matching algorithm as a unit. The
engine's is small and well-specified (≈250 lines of logic): ProjectPath + canRewrite +
first-match fold + canAggregate gates. Port it as ONE resolver-side pre-pass (rewrite
TypedGroupBy's source class → agg-view ClassSource when matched) with the
positive/negative goldens as spec. Low urgency (6 err), high spec clarity — good
bounded leg.

## 12. Execution plans, TDS & result contract

### 12.1 Engine model (EP = executionPlan.pure; RME = relationalMappingExecution.pure; SC = storeContract.pure)

**Plan node taxonomy**: `SQLExecutionNode` (sql string + `resultColumns` = (label,
dataType-or-"") pairs + connection + RelationMetadata) wrapped by one of FOUR
instantiation nodes (Tds/Class/RelationData/DataType — constraint-enforced result
types); `CreateAndPopulateTempTableExecutionNode` for big in-lists (threshold
DB-specific: H2=50, DB2=32767, plus Stream inputs — chosen at RUNTIME by a
FreeMarkerConditionalExecutionNode); `RelationalBlockExecutionNode` (transactional,
whole-subtree scan for temp-table nodes upgrades the wrapper); plan variables via
Allocation/Constant/FunctionParametersValidation nodes.

**TDS result contract** (the part that matters to any implementation):
- TDSColumn = name (user's project alias) + Pure type (Primitive|Enumeration|
  Variant/Map/List ONLY — else fail) + **offset** (positional, load-bearing) +
  **sourceDataType** (DB-side type; TWO conflicting pureTypeToDataType overloads:
  result-inference uses String→Varchar(8192)/Integer→BigInt; the generic map uses
  Varchar(1024)/Integer) + enumMappingId/enumMapping.
- Computed/derived columns legitimately have NO dataType (printed "") — tests assert
  this; do not invent types.
- **Enum decode is either result-side (enumMapping populated) OR SQL-side
  (PUSH_DOWN_ENUM_TRANSFORM clears it) — never both.** Enum source values quoted iff
  String-typed (numeric enum sources unquoted).
- Nulls: TDS rows carry TDSNull instances (outer-join padding); real CSV null = EMPTY
  field ('null' string is a test-harness display token only). Booleans: Bit sourceType;
  boolean-valued SQL literals render 'true'/'false' strings.
- Date/time: DateTime CONSTANTS fold the connection timezone into the literal at plan
  time; VARIABLES defer to the GMTtoTZ FreeMarker function. StrictDate→Date,
  Date/DateTime→Timestamp.
- FreeMarker support-function library (renderCollection, optionalVarPlaceHolder
  selector, per-enum enumMap_<path> functions; empty enum param ⇒ `0 = 1` predicate).

**Routing**: StoreMappingRoutedValueSpecification wrappers carry .sets/.mapping/
.propertyMapping; traversal through user functions is NOT routed; multi-expression
lambdas produce multiple SQLs.

### 12.2 legend-lite side & verdict

**Plan MODEL: OUT OF SCOPE by design** — we execute directly (Compiler → SQL → DuckDB);
no plan-node serialization. The executionPlan family's 100 SHAPE tests are mostly
plan-string printing — runner-vocabulary work (#43), not architecture.
**Result CONTRACT: RELEVANT and mostly aligned** — our Executor/TestBody honor row
equality against engine expectations. Verify against the engine rules: (1) enum
decode-once discipline (we push into SQL — matches PUSH_DOWN mode; ensure we never also
decode result-side); (2) TDS column offset ordering; (3) null = empty CSV field vs
TDSNull padding in outer joins (our TDSNull work already landed); (4) boolean literal
'true'/'false' rendering in projection position; (5) timezone folding is a DEFERRED
corner (no tz connections in corpus). In-list temp-table threshold: not needed for
DuckDB (large IN-lists fine) — documented divergence.

## 13. Postprocessors

### 13.1 Engine model (PP = postProcessor.pure; DPP = defaultPostProcessor.pure; FPD = pushFiltersDownToJoin.pure; RA = reAliasQuery.pure)

**Two pipeline phases** (plan-gen `postProcessSQLQuery` and execution `postProcessQuery` —
different processor sets), built on two transform engines: `transform` (cached memo,
identity-preserving — CTE dedup, mapper rewrites) vs `transformNonCached` (rebuilds join
aliases/target wiring — re-alias, push-down). Empty-data select short-circuits everything.

**Plan pipeline** = 5 ordered macro-processors: processInOperation (large IN → temp table,
DB threshold) → processObjectReferenceIn → defaultProcessors [inner 5-pass: **CTE hoist →
pushFiltersDownToJoins → removeUnionOrJoins → replaceAliasName → prependSQLComments**,
then connection sqlQueryPostProcessors] → connectionAware → dbSpecific (aliasLimit trim +
pre/finally SQL).

**pushFiltersDownToJoins** (the semantically rich pass): strictly **ADDITIVE** — outer
WHERE always preserved, predicates COPIED onto join ONs (rewritten across join-key
equality, both directions) and into pushable subselects. Pushable = binary comparisons/
startsWith/endsWith/contains/in with constant/parameter value side + null-tests; bails
subtree-wide on ANY right/full outer join; subselect push blocked by window columns,
LIMIT rows, >1 filtering/having op, or unknown node types (whitelist walk); group-by
column → inner WHERE, non-group under groupBy → HAVING; aggregate columns never pushed.
Duplicated predicates in one ON are legitimate golden behavior.

**replaceAliasName**: case-insensitive basename grouping → deterministic `<name>_<i>`;
reserved aliases root/unionBase/subselect never renamed; hard-asserts every alias mapped.
**CTE hoist**: lift all nested commonTableExpressions to root, dedup by name.
**prependSQLComments**: `-- "executionTraceID" : "${execID}"` tag only.
**trimColumnName**: aliasLimit truncation to `<prefix>_<i>` (DB2 128).

**Extension model**: connection-level `sqlQueryPostProcessors` (+ConnectionAware variant)
+ parameterized `PostProcessorWithParameter` triad — built-ins: Mapper (table/schema
qualifier rewrites, temp tables exempt, last-match-wins), RelationalMapper (db-level),
ExtractSubQueriesAsCTEs (level-indexed `subquery_cte_<level>_<idx>`, row-identical
contract). Context-based hooks (FunctionActivator: timestamp casts, FreeMarker→
table-function param rewrites). removeUnionOrJoins gated Snowflake-or-flag.

### 13.2 legend-lite side & verdict

**Layer: OUT OF SCOPE as architecture** — deliberately. Our leanness is at construction
(NavPath dedup, Fold flatness, deterministic t0..tN aliases), which subsumes what
replaceAliasName and CTE-hoist repair after the fact; pushFiltersDownToJoins is a
row-equality-neutral optimization (DuckDB's optimizer does this), relevant only to golden
SQL advisory. Named engine ideas worth keeping on file: reserved-alias discipline, the
ADD-not-move contract if we ever emit an equivalent, temp-table-exempt mapper rule.
**The corpus family** (13 err / 22 shape) tests the EXTENSION surface (table/schema
remapping, connection PPs, CTE extraction row-identity). Serving those needs a minimal
connection-postprocessor hook (a replaceTables-style rewrite applied to our SQL IR before
rendering) — a small bounded feature, not a pipeline port. Classify as checklist-burn
tier.

## 14. Tooling features: lineage, testDataGeneration, constraints, mutation, autogeneration, contract

### 14.1 Engine feature models (files under $E/lineage, /testDataGeneration, /validation, /mutation, /autogeneration, /mft, /contract, /extensions)

- **Lineage** — static analyses over the property-path tree: `scanColumns` → flat
  `ColumnWithContext[*]` (column + enclosing-SQL-node-class context; view columns
  expanded via columnMappings); `scanRelations` → a `RelationTree` of tables/views +
  joins (static form off the mapping; a runtime form reads relations off generated SQL
  clusters — that variant needs the whole pipeline). scanRelations is a PREREQUISITE for
  testDataGeneration.
- **TestDataGeneration** (the XL feature) — from query + mapping + seed row identifiers:
  scanRelations tree → per-relation column set (PKs ∪ non-nullable ∪ temporal dates ∪
  referenced) → root SELECT top-20 with OR-of-AND identifier filter (+milestoning
  filter) against a LIVE DB → per child join, materialize parent rows into
  `testDataGen_Temp_*` temp tables and join-pull child rows → dedup → CSV bundle
  (`schema\nname\ncsv` blocks). No identifiers and no default-PK flag → LOUD fail with
  copy-pasteable example seed code. Views/unions/milestoning/plan-generation are staged
  extensions.
- **Constraint validation** — THIN GLUE: each class constraint becomes
  `filter(x|not(body))->project(CONSTRAINT_ID, ENFORCEMENT_LEVEL, MESSAGE [+extras])`,
  concatenated, run through the ORDINARY relational path (constraint bodies with exists/
  aggregates/isDistinct compile like any query). Driver-table PK added to violations.
  ~150 lines of AST synthesis; no new SQL machinery.
- **Mutation** — plan-only: save/upsert (`RelationalSaveNode` + UpsertSQLQuery; only
  single-root, direct-column mappings; graph tree must contain ALL PKs; processing-
  temporal in/out sentinel injection) and relation write (INSERT…SELECT same-connection;
  temp-table staging cross-connection).
- **Autogeneration** — DB → Pure model reverse-generation (table→class, join→association
  with PK-based multiplicities, column→property with nullability→multiplicity,
  Bit→Boolean case-mapping). Self-contained graph transform; no execution deps.
- **MFT** — harness wiring; one datum: `rowExplosion` is the declared unsupported
  mapping feature.
- **StoreContract/extensions** — the SPI checklist (planExecution/supports/
  shouldStopRouting/resolveStore/executeStoreQuery/connectionEquality/…) + the
  RelationalExtension plug-points (buildUniqueName, milestoning filter handlers,
  post-processors, dialect node converters). For us: the interface inventory documents
  what a full store integration means; our QueryService/Compiler seam is the equivalent.

### 14.2 legend-lite verdicts

All **DEFERRED with feature models now archived** — matching existing feature-track
tasks: lineage (#44, 55 shape — start with scanColumns + static scanRelations, no
pipeline dep), constraints (#45, 31 shape — cheap: pure AST synthesis over our existing
resolver; good early win), testDataGen (#46, 68 shape — needs live-DB pulls + temp
tables; LAST), autogen (self-contained; unscheduled), mutation (plan-only; unscheduled).
Engine port order recommendation adopted: autogen/lineage-static → validation →
relation-write → testDataGen.

---

## 15. Synthesis: the coherent-architecture plan

**The core diagnosis, confirmed across every area:** our failures cluster around FOUR
missing engine calculi, not dozens of missing features. Each stopped burn-down leg
(cycles 77–86) failed because we implemented at a fix site what the engine implements as
a small closed system. The redesign program is those four systems plus one shared
foundation, then feature-checklist burns.

### 15.0 Shared foundation: one spec-processing path (from §5/§6/§10)

The engine answers mainTable/filter/distinct/groupBy/primaryKey/propertyMappings through
ONE polymorphic resolver over RelationalMappingSpecification + InstanceSetImplementation —
views, class roots, embedded (via setMappingOwner), unions (member fold), extends
(super-set walk) all included. Our equivalent: consolidate these answers into
ClassSources/Pipelines as shared resolvers with the engine's rules (extends PK matrix,
view no-synthetic-pks, embedded owner-chase). Several legs below assume this.

### Leg 1 — Filter channels + isolation strategies (§2; unlocks legs 2, 4, and the
qualifier tail)

Introduce the resolver-level `DeferredFilter` channel (predicate + NavPath scope — our
typed savedFilteringOperation) and ONE strategy chooser at materialization:
fold-into-ON | correlated-subselect | hoist-on-top, with the engine's two rules:
(a) projection threads prefer fold-into-ON else correlated; (b) hoist-on-top upgrades to
correlated whenever an INNER join is present. Qualifier bodies accumulate filters to the
CALLER's isolation decision (never partially applied — the cycle-86 lesson; and never
LIMIT-1). Kills: chained filtered nav (#70), union filter hoist, size-over-aggregate
routing, derived-leaf inline.

### Leg 2 — Milestoning calculus port (§3; subsumes #40, #32)

`TemporalCtx` value object (strategy, dates as SQL-able exprs, 8-state hop classifier,
range bounds) + `deriveAtHop` (one propagation function) + `predicateFor` (builder table:
exclusive/inclusive, range pair, snapshot equality, %latest infinity, union
coalesce/isNull, bitemporal AND) + 5 injection sites + alias-cloning for two-dates.
resolveMilestoningDateParams port makes the whole non-literal-date tail one resolver.
Port testMilestoningContextPropagation as spec first. Replaces
TemporalContext/TemporalFrame accretion.

### Leg 3 — Union arm-factory discipline (§4; subsumes #41, finishes U4/#27)

Make UnionSynthesis/Pipelines a faithful arm factory: PK-slot cross-product synthesis,
raw/dedup ordinal discipline, NULL-fill, value-vocabulary alignment, and
**re-exposure-on-wrap** (the U4 raw-key bug). Resolver gets the merge-vs-push predicate
(ordered-subset of alias chains) with push-into-arm emission; aggregation-over-union
re-injects real per-member PKs. Port V1–V5 goldens as spec. Fixes t18, N4 chains,
the 36-err union family.

### Leg 4 — Views as identity-carrying frames (§5)

Delete the plain-view flatten arm; every view reached as a relation becomes a named
frame processed by the SAME path as a class source (leg 15.0). Column substitution only
as identity resolution. Honor: no synthetic pks + loud PK requirement, INNER ~filter
hoist, milestoning-inside-body, per-instance aliases.

### Leg 5 — H4 graph output (§7; biggest corpus block: 100 err graphFetch)

Keep the single-query JSON-envelope design (documented divergence); port the engine's
identity + serialization contracts: DISTINCT-by-PK per node, pk-not-null phantom
suppression, key naming (qualifier signatures, milestone dates, aliases), null vs [],
qualified-properties-as-child-computations, store distinct/groupBy forbidden,
embedded/otherwise per-leaf dispatch in trees.

### Checklist burns (feature inventory, not architecture)

- functions family (75 err): burn against §9's catalog table family-by-family
  (per [[batch-slices-target-big-buckets]]).
- aggregationAware matching engine (§11): one bounded resolver pre-pass, ~250 lines.
- tds family (32 err): §2 per-op inventory (pivot, window frames, variant, CTE,
  paginated).
- postprocessor family (§13): minimal connection-postprocessor hook (replaceTables-style
  IR rewrite) — small bounded feature; the engine pipeline itself stays out of scope.
- result-contract checks (§12): enum decode-once, boolean literal rendering, TDSNull
  padding, offset ordering — verify-with-pins, not new machinery.
- Engine-trap pins to add opportunistically: extends PK matrix corners, multigrain
  suppression rule, modelJoin materialization condition, null-safe equality by
  nullability, calendar weekend/rollover windows.

### Deferred (feature models archived in §14, §7, §10)

Cross-store, temp-table strategies/batching, alloy object references,
removeUnionOrJoins, mutation, testDataGen (last; needs live-DB pulls), lineage
(scanColumns first when scheduled), constraints (cheap early win when scheduled),
autogeneration.

### Ordering rationale

Leg 1 first — it's the enabling mechanism for legs 2–4's emission decisions and directly
unblocks the most stopped legs. Then 2 and 3 (largest divergent families, both now fully
specified), 4 (small once 15.0 lands), 5 (biggest missing block, independent of 1–4).
Checklist burns interleave as gate-cycle filler. The corpus ledger remains the
VERIFIER; this map is the DRIVER.
