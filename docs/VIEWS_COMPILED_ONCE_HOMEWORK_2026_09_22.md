# Views compiled once — homework (2026-09-22)

**Question.** A Legend `View` is a store element whose meaning is a query over tables. Today
that meaning is computed downstream in four vocabularies. The proposed design computes it
ONCE, in the compiler layer, and every consumer derives what it needs from that one form.
This document is the measured homework the user ordered before any code moves: the reading,
the wall table, two census runs over the corpus, the engine's own rule, the layering check,
and a per-stage ratchet. Nothing in the product tree changed for this document.

**Method.** 4,139 lines read in full (`normalizer/ViewRelation` 523, `normalizer/RelOpTranslator`
748, `lineage/ScanRelations` 2,796 — its view section lines 880–1420 — and `resolver/ViewFrames`
72). The census ran through the platform: a throwaway probe (`ViewCensusProbe.java`, kept
outside the tree at the job's tmp dir) parsed the whole corpus with `MinimalCorpus`, resolved
names, built the model index, and for every view ran the existing expander
(`ViewRelation.viewRelationExpr`) recording the exception if any. Corpus: 2,613 tests, 0 resolve
walls. Engine read at the pin (4.145.0): `pureToSQLQuery.pure` 5140–5192 (view processing +
milestoning), the accessor-on-view golden, `HelperRelationalBuilder`.

---

## 1. The four vocabularies today (the reading)

| Reader | Package | What it computes from the raw `ViewDefinition` | Mapping-dependent? |
|---|---|---|---|
| `ViewRelation.viewRelationExpr` | normalizer | the view as a Pure relation expression: `tableReference(root) -> [filter] -> (groupBy \| project) -> [distinct]`, join-navigating columns hoisted as `JOIN_SLOT` steps through `JoinChainEmission.emitJoinChain` on a `Pipeline.forView` | `md` for messages only on the view path (`classTypedTerminus=false`, `ownerClassFqn=null`); the class-typed arms of `emitJoinChain` never fire for a view |
| `StoreCompiler.viewSchema` (via `findTable` fall-through) | compiler.element | the view's column TYPE — **only when every column is a plain `ColumnRef`**; otherwise `Optional.empty()` (a computed-column view has no type today) | no |
| `ScanRelations.expandView` / `viewExpansion` | lineage | the view's internal tree (root table + join webs + filter columns), `mainTable`, `colToBase` (declared column → base column, plain columns only), `viewChain` (view-on-view, outer-first); own private include-aware `findView` ×3 | no |
| `ViewFrames.frameNameOf` | resolver | name only: "is this class's main source a view" → the frame alias is the view's name | reads the mapping's `RelationalSource` stamp |

Plus the mapping-side rewriters in `ViewRelation` that operate on the MAPPING's relational
operations against a view (not on the view): `frameRewrite` / `frameRewriteIfView` /
`throughFrame` / `declaredSpelling` / `inlineViewRefs` / `inferViewMainTable`. These read the
view's column list (name → expression) as a schema; they do not translate the view.

**The extraction surface of `viewRelationExpr` (what a store-level view compiler must own):**

| Piece | Lines | Today | Notes |
|---|---|---|---|
| `RelOpTranslator` (whole) | 748 | normalizer | RelationalOperation → Pure. Imports `builtin.DynaFn`, `builtin.Pure`, `model.*`, `protocol.spec.*`. **Zero `md` uses.** |
| `ViewRelation.viewRelationExpr` + `inferViewMainTable` + `joinOnlyViewRoot` | ~200 | normalizer | md = messages |
| `Pipeline.forView` + `PipelineView` | ~60 | normalizer | the view mode carries no mapping ledger (`ledger()` throws) — already a store-only pipeline |
| `JoinChainEmission.emitJoinChain`, non-navigate branch | ~120 of 1,063 | normalizer | with `classTypedTerminus=false`: slot by path key, `findJoin`, `declaredSpelling`, `hopTarget`, condition translate, `JOIN_SLOT` emission, `targetColumnNames`, `collectJoinNavigations` (md-null tolerant), `slotFor`, `uniqueSlotName` |
| `MappingNormalizer.canonicalTable`, `seedAliasScope`, `resolveViewRefsInJoin`, `determineTargetTable` | ~120 | normalizer | md = messages |
| `GroupBySynthesis.isGroupReducer`, `groupByOpsMatch` | ~40 | normalizer | pure predicates over `RelationalOperation` |
| internal natives `Pure.Lite.JOIN_SLOT`, `GROUP_BY_COMPUTED_KEYS` | — | builtin | exist |

≈ 1,300 lines move or split; the class-typed arms of `emitJoinChain` (union routes, navigate
slots, inline-embedded splices, `md.classMappings()`) stay in the normalizer and call the store
path. `JoinChainEmission` today mixes the store path and the mapping path in one method; the
split is at the `emitNavigate` fork (line ~400).

## 2. The wall table (21 throws in the two translators)

| # | Site | Shape refused | Fires in the corpus? |
|---|---|---|---|
| 1 | ViewRelation: cyclic view-on-view | a view whose root chain returns to itself | no |
| 2 | ViewRelation: join-mediated `~filter` on a view used as a relation | `FilterMapping.JoinMediated` on a view | no (0 join-mediated view filters in the corpus) |
| 3 | ViewRelation: `~filter` not found | dangling filter pointer | no |
| 4 | ViewRelation.frameRewrite: column read under a view that carries no such column | mapping-side | no |
| 5 | ViewRelation.inferViewMainTable: no non-join column | join-only view whose root cannot be inferred | no (join-only views resolve via `joinOnlyViewRoot`) |
| 6 | ViewRelation.inferViewMainTable: multiple root tables | columns spanning two tables without joins | no |
| 7–8 | `MissProbe.neverFired` ViewRelation#6/#7 | database / join not found | no |
| 9 | RelOpTranslator.collectTablesIn: JoinNavigation inside an expression | nested join nav in a table-collection walk | no |
| 10 | RelOpTranslator.columnRead: no row variable for table | scope miss | no |
| 11 | RelOpTranslator: ColumnRef table not in scope | scope miss | no |
| 12 | RelOpTranslator: TargetColumnRef outside a join condition | `{target}.col` misuse | no |
| 13 | RelOpTranslator: dayOfWeekNumber week start not Sunday/Monday | engine assert mirrored | no |
| 14 | RelOpTranslator.pureTypeFor: SEMISTRUCTURED / `[]` types | semistructured extraction | no |
| 15 | RelOpTranslator.ambiguousTableRef | a table reached by two join paths | no |
| 16 | RelOpTranslator.joinNavigation: nested JoinNavigation without a pipeline | join nav inside association predicates / join conditions | no |
| 17 | RelOpTranslator.literalToValueSpec: unsupported literal type | — | no |
| 18 | RelOpTranslator.dynaFnName: TRANSLATED without an arm | registry inconsistency | no |
| 19 | RelOpTranslator.dynaFnName: UNSUPPORTED dynafunction | an engine dynafunction we have not ported | no |
| 20 | RelOpTranslator dispatch: unexpected op | sealed-hierarchy coverage | no |
| 21 | PipelineView.NONE.slotFor | join slot requested outside a pipeline | no |

**Measured: 48 of 48 relational-corpus views expand with no wall — and that denominator was
WRONG for eager lifting.** The stress corpus (gate 10) carries 4 more views in its own store
files (`NOTIONAL_BY_BOOK`, `CURVE_SUMMARY`, `SURFACE_VIEW`, `dense_Rollup`); stage 1's first
chain went red on `dense_Rollup`: a view rooted at `ACCRUAL_ENTRY` whose `~filter` read
`ACCOUNT.ACCOUNT_ID`, a table the view never reads and no join reaches (wall #11). The engine
compiles such a view (its compiler resolves the filter by name, never reachability) and fails
at SQL generation if used. **USER RULING 2026-09-22: STRICT.** An untranslatable view fails the
build under the strict entry, exactly as an unnormalizable mapping does (`buildModel` has no
wall sink; `buildModule` records the wall under the lifted FQN and keeps building). The stress
fixture was OURS and WRONG: it now filters over its own root (`dense_AccrualNotNull`). Real
denominator: 52 views, 52 lift.

## 3. Census 1 — every view in the corpus, by shape (48 views, 20 databases)

| shape | count |
|---|---|
| plain columns only | 18 |
| computed columns (dynafunction / arithmetic), of which 7 also `~groupBy` | 8 |
| join-navigating columns (`@Join \| T.COL`) | 11 |
| `~filter` (all `Direct`; 0 join-mediated) | 4 |
| `~groupBy` | 8 |
| `~distinct` | 8 |
| view-on-view (root is a view; deepest chain 3: `ViewOnViewOnViewSchool`) | 7 |
| schema-qualified (`E.AltID_View`, `ViewSchema.AltID_View`, `Entity.LegalEntity_View`) | 4 |
| root table MILESTONED (business/processing) | 10 |

Full per-view table (db, columns plain/computed/joinNav, filter, groupBy, distinct, root, root
milestoned, expander verdict) is in the probe's output; every row's verdict is `ok`.

Two views the type path cannot type today (computed columns → `viewSchema` returns empty):
`interactionViewMaxTime`, `tradeEventViewMaxTradeEventDate`, `personViewWithGroupBy`,
`accountOrderPnlView`, `accountView`, `FIRM_TO_PERSON_VIEW`, and the join-navigating eleven —
the accessor route would fail on all of them; the mapping route never asks the type path.

## 4. Census 2 — who touches views, by route (2,613 corpus tests)

| route | count | notes |
|---|---|---|
| relation accessor `#>{db.view}#` (`tableReference` on a view) | **1** | `testRelationStoreAccessorOnView` (roster: DuckDB + H2) |
| `viewReference(db, schema, view)` call | **1** | `meta::pure::executionPlan::tests::testViewToTDS` (roster: DuckDB + H2; asserts on a plan STRING, H2-format golden) |
| mapping route: names a mapping whose include-closure maps a class on a view | **1,117** | 33 class mappings on views across 26 mappings; 47 joins name a view |
| — of which test data generation | 33 | `testAlloyTestDatGenWithQuotedColumnsForViews` on both rosters |
| — of which lineage (`scanRelations`) | 13 | |

Roster rows naming a view today: DuckDB 4 (`testViewToTDS`, `testAlloyTestDatGenWithQuotedColumnsForViews`,
`testRelationStoreAccessorOnView`, `testUnionOnViewsMapping`); H2 those 4 plus
`testAssnToViewWithGroupBy`, `testViewWithGroupBy`. The 1,117 mapping-route tests are the
regression guard for every stage; they pass today at DuckDB 108 / H2 363 exact.

## 5. The engine's rule (read, not recalled)

- **A view is a mapping specification.** `processRelation` on a `View` calls
  `processRelationalMappingSpecification($v, …)` — the same function that plans a class
  mapping (main table, column mappings as property mappings, `~filter`, `~groupBy`,
  `~distinct`) — and wraps the result as `ViewSelectSQLQuery(view=$v, name=$v.name, …)`.
- **SQL shape: an inline derived table aliased by the view's name.** Golden for the accessor
  test: `select "personview_0".ID as "ID", … from (select "root".ID as ID, "root".AGE as age,
  "root"."FIRST NAME" as name from personTable as "root") as "personview_0"`. Not a CTE, not a
  database view.
- **Milestoning applies INSIDE the view's select** from the outer query's milestoning context:
  `applyMilestoningTypeFilters($s, $milestoningContext, …, $viewSpecification->instanceOf(View), …)`
  at the tail of `processRelationalMappingSpecification`. The engine never creates a database
  view; a Legend view is virtual by contract (the store describes a database the product does
  not own; business-date parameters cannot pass into a physical view).
- **Ours already matches on the mapping route.** The normalizer expands the view into Pure
  before typing; the resolver's `TemporalFrame` finds the milestoned `tableReference` inside the
  expansion (`ctx.findTableMilestoning(store, table)` at four sites) and attaches the temporal
  conditions — the 10 milestoned-root views (milestoningmap*, UnionOnViewWithMilestoning,
  multipleChainedJoinsDB) pass today through exactly this path. A compiled Pure form changes
  nothing about milestoning.

## 6. Layering check

- (Superseded by §7: the lift stays in the normalizer; kept for the record.) Home considered for a view compiler: **`com.legend.compiler.element`**, beside `StoreCompiler` (which
  already owns table types and the plain-column `viewSchema`). It already imports
  `builtin.Pure`, `model.*`, `compiler.element.type.*`; the translator's imports (`builtin.DynaFn`,
  `builtin.Pure`, `model.*`, `protocol.spec.*`) are the same surface. `lowering` may depend on
  `compiler.element` (Invariant 5 allowlist), so the home must not pull `normalizer` — it will not.
- `com.legend.model` is NOT a legal home: Invariant 6j pins the model package to
  protocol/values/error, and the translator needs `builtin`.
- Callers: the normalizer already imports `compiler.ModelBuilder` (allowed); `compiler.spec`
  (the checker) already reads `compiler.element` (`Type`, `ModelContext`); `lineage` reads
  `ModelContext` (compiler.element). No rule blocks any of the three consumers reading a
  compiler.element class. Invariant 7 (typed nodes minted only by compiler layers) is
  untouched: the compiled form is PROTOCOL Pure, typed by the checker like any expression.
- Where the compiled form rides: the model index (`ModelBuilder`, which already holds
  `viewsByDb`) — the immutable artifact every layer reads; T4.1 invariant 5 (no writes into
  the index after construction) holds because it is computed at `ModelBuilder.from`, or lazily
  once as a pure function of `(db, view)` over the immutable index (both satisfy
  "correct algorithm before memoizing"; pick at stage 1, prefer eager + walls).
- Checker entry: `Typer.synth(ValueSpecification, Env)` types a synthesized expression;
  `TableReferenceChecker.check(Typer, AppliedFunction)` is the one site to change.

## 7. The design (corrected twice: by the homework, then by the user)

**A view is a function.** The user's tenet — every Pure expression is a function — is the right
mental model, and it replaces the "carrier" wrapper the first draft proposed (a wrapper native
smuggling a name to lowering is a sidecar in spirit). The corpus and the codebase already hold
the two precedents:

- The normalizer LIFTS synthesized `FunctionDefinition`s into the model today: derived
  properties (E.2, via `compiler.DerivedProps.lift`), constraints (E.3), service queries (E.4).
- A class mapped with `~func` (162 relation-function mappings in the corpus) takes a
  ZERO-ARG SINGLE-EXPRESSION relation function's body as its source relation
  (`MappingNormalizer.relationFunctionPipeline` returns `fn.body().get(0)`).

So: **E.5 — lift views.** Every `View` in every `Database` becomes a synthesized zero-arg
function `<dbFqn>::<viewName>(): Relation<…>[1]` (schema-qualified views:
`<dbFqn>::<schema>::<viewName>`) whose single body expression is what `viewRelationExpr`
produces today. Lifted in `ModelNormalizer.normalize` beside E.2–E.4, by the translator that
already lives in the normalizer. Nothing moves out of the normalizer. The layering question of
§6 disappears: no consumer calls the normalizer; each resolves a function by FQN in the model.

Consumers, each through machinery that already exists:

| consumer | how | dies |
|---|---|---|
| relation accessor `#>{db.view}#` / `viewReference` | `TableReferenceChecker`: the name is a view → type a zero-arg call to the lifted function; `UserCallInliner` inlines it like every user call; lowering names the derived table by the function (the engine's `<view>_n`) | the physical-table lowering for views; `findTable`'s fall-through to views |
| type | the lifted function's return type = its body's relation type; computed-column and join-navigating views get a type for the first time | `StoreCompiler.viewSchema` (plain-columns-only) |
| mapping route | a class on a view IS a class on a relation function: `relationFunctionPipeline` semantics; join hops onto a view reference the lifted function's body the same way | `ViewRelation.viewRelationExpr` call sites (17) collapse to one lookup; `Pipeline.forView` |
| resolver `ViewFrames` | the function's name | the name-only scan of `db.views()` |
| lineage | walks the lifted function's body: innermost `tableReference` = main table; `project` ColSpecs = column map; nested lifted calls = view chain | `ScanRelations.findView` ×3, `expandView`, `viewExpansion` raw walk |

The raw `ViewDefinition` stays in the model as the view's schema record (names, column
expressions, filter pointer), as `TableDefinition` stays for tables; the mapping-side rewriters
(`frameRewrite`, `throughFrame`, `declaredSpelling`, `inlineViewRefs`) keep reading it as
schema. What dies is every second TRANSLATION.

## 8. Stages and ratchets (named before the leg)

**Stage 1 — E.5 lift + the accessor.** `ModelNormalizer.liftViews` synthesizes one function per
view (eager; a view whose translation walls becomes a walled element under the module's
POISON-DON'T-DROP contract). `TableReferenceChecker` types a view name as a call to the lifted
function. The `~func` and user-call-inline paths carry it from there.
- Ratchet: `testRelationStoreAccessorOnView` green on all four lanes (DuckDB 108→107, H2
  363→362); DuckDB split-rung firings 1→0; `testViewToTDS` is a CANDIDATE (H2-format plan-string
  golden; not promised). Zero movement elsewhere: the 1,117 mapping-route tests exact.
- Deletes: `StoreCompiler.viewSchema` and `findTable`'s view fall-through.

**Stage 2 — the mapping route reads the lifted function.** The 17 `viewRelationExpr` /
`relationRef` sites become one lookup of the lifted function's body (the `~func` path);
`Pipeline.forView` and the view branch of `emitJoinChain` fold into the lift.
ORDERING FACT (found writing stage 1): E.5 runs AFTER `MappingNormalizer.normalize` and the
lifted functions never enter the model index (built before normalization; T4.1 invariant 5
forbids the normalizer writing into it), so stage 2 cannot look them up the way `~func` does.
Lift views FIRST and hand the lifted bodies to the mapping normalizer as an input of the same
phase — no index write. Stage 2 consumes the form stage 4 settles (see below), not the spliced one.
- Ratchet: zero test movement; DuckDB / H2 exact; ledger pins shrink.
- Deletes: `ViewRelation.viewRelationExpr` + `inferViewMainTable` + `joinOnlyViewRoot` as
  separate entry points (the lift owns them).

**Stage 3 — lineage walks the lifted function.** `ScanRelations`' view section reads the
lifted body. HOMEWORK OWED before it starts: the reading of lineage's ~500 view lines against
the body — node labelling (view vs table), the filter's join web and group-by columns folded
onto tree nodes, the test-data generator's use of `mainTable` / `colToBase` / `viewChain`.
"Derivable from the expression" was asserted here, not read.
- Ratchet: 13 scanRelations + 33 TDG mapping-route tests exact; candidate
  `testAlloyTestDatGenWithQuotedColumnsForViews` (both rosters).
- Deletes: `ScanRelations.findView` ×3, `expandView`, `viewExpansion`'s raw walk.

**Stage 4 — the engine's SQL for the accessor (added by the user, 2026-09-22).** Stage 1
proved the view by the test's ROWS assert; its first assert expects the engine's SQL text
inside the `executeLegendQuery` JSON. The platform renders that activities SQL only for
mapping-backed chains (`firstMappingFqn != null`); the accessor has no mapping. Three parts:
(1) render engine-style SQL for a mapping-free relation chain; (2) judge a `contains('"sql":…')`
golden by ROWS under the SQL-text charter; (3) the view's derived table named by the view
(the engine's `personview_0`) — the inlined body loses the view's identity at typing, so the
call must survive to lowering as a NAMED relation (the frame/CTE-reader precedent), not be
spliced. Part 3 feeds back into stage 1's checker and is why stage 2 waits for stage 4.
Ratchet: `testRelationStoreAccessorOnView` fully green on four lanes (DuckDB 108→107, H2
363→362). Order after stage 1 lands: 4 → 2 → 3.

Each stage: fast pair → four lanes → sequential chain → registers regenerated → record →
commit → ci-watch. No stage starts on a red predecessor.

## 8b. Stages 2, 3, 4 — the homework READ while stage 1's chain ran (2026-09-22)

**Stage 4 (first).** (1) The activities SQL is skipped by a null-mapping guard at the call site
only; `StoreResolver.resolve(body, null)` and `RelationalRootForm.apply(body, ctx, null)` both
accept no mapping — dropping the guard for relation-rooted chains is the change. (2) The charter's
arms (`tryArmSameSql`, `tryArmH2Compat`, `tryArmTdgRoot`) are keyed by assert FQN, evaluate golden
and ours, replay the golden through the oracle and let rows decide (`rowsLegAndVerdict`); the
accessor test's shape is `assert(contains(<executeLegendQuery JSON>, '"sql":"…"'))` — one new arm
that recognizes it, takes the SQL out of the JSON fragment, and hands it to the same rows path
(~80 lines, no new mechanism). (3) THE DESIGN POINT: the IR already has named frames —
`SqlSource.Subselect(inner, alias, frameName)`; `EngineStyleH2.planSource` groups a named frame by
its own model identity (`orderpnlview_0`); the mapping route sets it through `TypedJoinSlot` /
`TypedJoin.frameName` for a join hop onto a view (`Lowerer.asRightSide`). Missing: a ROOT-position
named frame from a typed node. Today the checker SPLICES the view's body, so lowering flattens it
into one select over the table. Two candidates: keep the call to the lifted function and lower a
user call whose callee is a VIEW-hat function as a named subselect (the typed inliner leaves those
calls alone), or the planned-frame slot on `TypedTableReference` (a named CTE reader — not the
engine's inline shape). The first is right; it replaces stage 1's splice.

**Stage 2.** `MappingNormalizer.normalize(parsed, model, wallSink)` builds its own `lifted` list and
runs BEFORE E.5; the lifted functions never enter the index. So: lift views first, pass a
`Map<viewKey, FunctionDefinition>` in, the 17 sites become one lookup each. View-on-view: today the
expander recurses with a cycle guard; under the function model the outer body CALLS the inner
view's function — the recursion and the guard die, and the spliced mapping bodies then carry user
calls the typed inliner inlines — which is why stage 2 waits for stage 4's inliner decision.

**Stage 3 — bigger than §8 said.** Lineage's view expansion has ONE real consumer: the test-data
generator, at six sites: `isView` (labels + `expandIfView`), `viewExpansion` (tree, `colToBase`,
`viewChain`, `mainTable`), and `viewDef` for `viewFetchSql` — a HAND-BUILT SQL renderer that writes
the view's fetch query as a string from the raw column mappings, filter, group-by and distinct: a
second SQL generator for views, inside the TDG program. Stage 3 is therefore: the TDG view fetch
comes from the lifted body through the real lowerer, and lineage's tree derives from the same body;
`viewFetchSql`, `expandView`, `viewExpansion`'s raw walk and the private `findView` ×3 die. It
belongs to the TDG program's leg (its 33 tests + 13 lineage tests as the guard).

## 9. Open decisions for the user

1. ~~Eager versus lazy~~ DECIDED 2026-09-22: eager, like E.2–E.4 (all three lifts are eager; walls
   recorded when a sink exists, thrown under the strict entry). STRICT for untranslatable views
   (user ruling; see §2).
2. **`testViewToTDS`** asserts on an H2-format plan string through `viewReference`; stage 1
   makes the call resolvable but the plan-text golden is a separate concern. Not claimed.
3. **Stage 2 scope**: the mapping-side rewriters keep reading the view's column list as schema.
   If the user wants those to derive from the compiled form too, that is a fourth stage with its
   own reading; not sized here.
