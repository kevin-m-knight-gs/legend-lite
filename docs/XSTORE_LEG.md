# XStore leg — cross-store queries as ONE SQL plan

## Scope (the 24-test cluster, census 2026-07-26 at nominal 1600)

Every test walls at the SAME place: the Typer's `unbound variable`
(Typer.java:131) on a let-bound CONNECTION/RUNTIME value — before any
store machinery runs. Three clusters by what the runtime carries:

| Cluster | Tests | Wall | Engine shape |
|---|---|---|---|
| A. Cross-store graphFetch (JSON ModelStore root → relational children) | 15 × `$dbRuntime` — testCrossMappingJsonToDB{,WithNoLocalProperties,WithExplosion}, testSimple/NestedUnion{,OnMultipleSets}CrossStore, testSimpleOrderedCrossStoreGraphFetch× 6, testOrderedCrossStoreGraphFetchWithComplexQualifierExpression{,Reuse} | `let dbRuntime = testRuntime()` then `^$dbRuntime(connectionStores = ...->concatenate(^ConnectionStore(element=^ModelStore(), connection=$jsonConnection)))` | Pure sets (`Trade[trade_set]: Pure { ~src S_Trade, +prodId: $src.s_tradeDetails->split(':')->at(0) ... }`) sourced from a `JsonModelConnection(class=S_Trade, url='data:application/json,{...}')`, XStore assoc (`Trade_Product : XStore { product[trade_set, prod_set]: $this.prodId == $that.productId }`) to Relational sets |
| B. JSON-sourced M2M feeding a relational query | 4 × `$jsonConnection` — testSourcingJsonResultToQuery{,WithFiltersInMapping,WithParametersInUrl,WithPureDateAsParam} | same `JsonModelConnection` let | JSON rows drive the M2M source class of an otherwise-relational execution |
| C. Mapping chain | 5 × `$modelChainConnection` — testRelationalChainExecution{Flat,Nested,WithFilter,WithInScopeVariableFilter} + testInExecutionWithTempTableAndQueryChainingAndChainConnection (functions/tests) | `^ModelChainConnection(mappings=[simpleRelationalMapping])` in a hand-built `^Runtime(connectionStores=...)` | M2M mapping whose `~src` classes resolve THROUGH the chained mapping (Target_Firm: Pure { ~src Firm } where Firm is relational in the chained mapping) |

Adjacent but NOT this leg: testCrossMappingWithRelOpWithJoinKeys /
testCrossMappingWithMoreBooleanAlgebra (already past the runtime wall —
resolver gaps in xstore end synthesis), testCrossStoreWithCSVDataSource
(`^EngineRuntime` vocabulary), CrossStoreGraphFetchWithRelationalMilestoned×4
(`executeLegendQuery` 4-arg — plan-metamodel track), the modelJoin
family's 9 non-pass (resolver long-tail: sub-aggregation heads,
exists-nested navigation, inner-join-on-target — they share machinery
but not the runtime wall).

## Engine architecture (for the record)

- Metamodel: `XStoreAssociationImplementation` — per-end property
  mappings with `crossExpression` lambdas (`$this.key == $that.key`),
  ends named by set ids (core/pure/mapping/XStore.pure).
- Interpreted path: `crossGetterOverride` (XStore.pure:37) — per-OBJECT
  lazy navigation: reads the source object's hidden `MappingInstanceData`
  payload, finds the property mapping, and when source/target stores
  differ fires `performXStoreQuery` = a routed `StoreMappingRouted...`
  execution of `Target.all()->filter($that| <crossExpression with $this
  keys bound>)` against the target store.
- Plan path (what the corpus tests exercise): cross-store graphFetch
  nodes — root store executes its local tree, cross keys
  (`parent_cross_key_*`) collected per batch, each cross child runs a
  root-like query on ITS store keyed by an IN-list; strict constraints
  (single-property traversal, primitive keys — feature map §7).
- `ModelChainConnection(mappings=[M])`: the ModelStore's "connection" is
  ANOTHER MAPPING — the M2M's source classes are fetched by executing
  them through M, i.e. mapping composition at runtime.

## What legend-lite already has

- **XStore association PARSING + synthesis**: MappingGrammarParser
  `parseXStoreAssociationMapping` (`A : XStore { prop[src,tgt]: expr }`),
  `MappingNormalizer.synthesizeXStoreMapping` / `xstoreEndOf`,
  ModelJoinNesting's doctrine "both XStore and ModelJoin are just
  navigate()". The modelJoins family (38/47) exercises this machinery
  end-to-end for relational-to-relational model joins.
- **M2M `~src` chains**: MappingNormalizer resolves Pure-class sets by
  recursive substitution (H5 design: β-composition of binding tables),
  with cycle detection (:762-784). `+prodId`-style mapping-local
  columns are already understood as XStore keys (:911-913).
- **JsonModelConnection**: model class + ElementParser support for
  embedded `JsonModelConnection { class: ...; url: '...'; }` islands.
- **Missing**: (1) the Typer/value vocabulary for let-bound runtime
  values — `^JsonModelConnection(...)`, `^ModelChainConnection(...)`,
  `^Runtime(...)`, `^ConnectionStore(...)`, `^ModelStore()`, and the
  `^$var(...)` COPY-CONSTRUCTOR over a runtime-typed let; (2) a
  RELATION source for a JSON ModelStore class (the data: URL's rows);
  (3) execution-context threading of the extra connections into
  ClassSources (which mapping/store serves which set).

## The legend-lite design (tenet: Java orchestrates, database executes)

The engine brokers cross-store navigation OBJECT-BY-OBJECT (or batch
IN-lists) across two executors. We have ONE executor (DuckDB) and a
resolver that composes relations — so the whole cross-store query
lowers to ONE SQL plan:

1. **JSON ModelStore class = a relation**: a `JsonModelConnection`
   data: URL is STATIC ROWS. `S_Trade` becomes a typed frame —
   `read_json`/VALUES lowered from the parsed payload, columns from
   the class's properties (same discipline as model-driven DDL: the
   class decl is the schema). The M2M set's binding exprs
   (`split(':')->at(0)`, `parseInteger`) are ordinary scalar lowerings
   over that frame — the ClassSource for `Trade[trade_set]` is a
   projection over the S_Trade frame, by the SAME recursive
   substitution that serves `~src` today.
2. **XStore association = an ordinary AssocSub**: both ends are now
   relations; the crossExpression (`$this.prodId == $that.productId`)
   is the join condition — the existing navigate()/graph-emission
   machinery applies unchanged. Engine's per-batch IN-list plan shape
   is a DIVERGENCE WE DOCUMENT (advisory SQL), row equality is the
   contract.
3. **ModelChainConnection = resolver composition**: the M2M's `~src
   Firm` resolves `Firm` through the CHAINED mapping's ClassSource
   (sources.get(chainedMapping, Firm)) and β-composes — zero new
   execution machinery, exactly the H5 M2M sketch with the inner
   mapping taken from the connection instead of the same mapping.
4. **Runtime values in test bodies**: the harness's exec env gains a
   RUNTIME VALUE vocabulary — `testRuntime()` as a value, `^Runtime`,
   `^ConnectionStore`, `^ModelStore`, `^JsonModelConnection`,
   `^ModelChainConnection` construction, `^$var(...)` copy-ctor, and
   `.connectionStores->concatenate(...)`. These evaluate to a CONTEXT
   OBJECT (mapping set + per-store connection facts), consumed by
   `execute(query, mapping, $runtime, ...)` to seed StoreResolver's
   Context — the same role the driver-supplied runtime plays today.
   Loud on any unknown connection kind.

## Slice ladder (each: gate cycle, REVERT ON REGRESSION)

- **Slice 0 — runtime-value vocabulary** (unblocks all 24, converts ~0
  alone): platform classes + Typer acceptance + exec-env evaluation of
  the runtime lets and the `^$var` copy-ctor; `execute(...)` consumes
  the composed runtime. Exit: every cluster test moves PAST `unbound
  variable` to its true store wall (named, precise). Zero regressions.
- **Slice 1 — mapping chain (cluster C, 5 tests)**: ModelChainConnection
  threads the chained mapping into ClassSources for M2M `~src`
  resolution. Target: testRelationalChainExecutionFlat first (flat
  projection through the chain — smallest row-verified conversion).
- **Slice 2 — JSON source frame (cluster B, 4 tests)**: data: URL →
  typed VALUES frame; M2M set over it; parameters-in-url + date-param
  variants after the flat case. Target: testSourcingJsonResultToQuery.
- **Slice 3 — cross-store graphFetch (cluster A core, ~7)**:
  XStore assoc between the JSON-frame set and relational sets through
  the standard AssocSub/graph emission; testCrossMappingJsonToDB
  family + testSimpleOrderedCrossStoreGraphFetch basics.
- **Slice 4 — union + ordered/property-level variants (~8)**:
  cross-store unions ride the union arm factory; ordered multi-level /
  property-level fetch details last.

## Risks / walls to expect

- The JSON payloads in data: URLs are CONCATENATED objects
  (`{...}{...}` — not a JSON array); the frame builder must split them
  (engine's JsonModelConnection semantics: one object per row; some
  tests wrap in `{"s_trades": [...]}` — class-typed roots).
- Ordered cross-store tests assert ORDER — the engine's per-batch
  execution preserves root order; our single-SQL plan must ORDER BY
  the root's natural sequence (row_number over the root frame if
  needed).
- testSimpleOrderedCrossStoreGraphFetchMultiLevelXStoreRequirements
  chains XStore hops — needs slice 3's assoc route to compose.
- Property-level fetch details / complex qualifier expressions in
  XStore conditions may hit the qualifier-inlining long tail.

## Cluster A design note (2026-07-26): Pure-set XStore ends

`synthesizeXStoreMapping` is NORMALIZE-time: it resolves each end to a
relation pipeline + column view (`xstoreEndOf`) and rewrites the cross
condition to COLUMN reads over the two relation rows
(`legacyAssocPredicate(a, b, srcRel, tgtRel, {s,t|cond})`). A PURE set
has no normalize-time relation — its frame (the JSON payload) arrives
with the RUNTIME VALUE at resolve time.

**Chosen direction (route A — resolve-time is where all facts live):**
a Pure-set end emits the association PREDICATE in PROPERTY SPACE
(`$this.prodId == $that.productId`, unrewritten) with the end pinned by
class + set id; the RESOLVER's association route substitutes the reads
through each side's COMPOSED bindings (locals included — they compose
as binding columns since a36c4930), exactly how AssociationMapping
predicates already resolve. Needs: an emission variant that
`AssociationJoins.associationJoin` recognizes as property-space with
set-qualified ends, and the whole-instance `$src` marker rung for the
same-source second-set bindings (`trader[trader_set]: $src`).

Rejected route B (project locals as physical columns at composition so
the column-space emission works): still needs a deferred pipeline for
the frame, and bakes column names into normalize-time output that
resolve-time owns.

## Implementation brief: property-space Pure-set ends (study 2026-07-26)

1. **Consumption**: AssociationJoins:817-845 — predicateFunctionFqn →
   compiled body's last node MUST be the 5-arg legacyAssocPredicate call
   (Pure.java:1139: cond params are RELATION ROWS). The normalizer
   (synthesizeXStoreMapping :1114-1118) rewrites $this.p to COLUMN reads
   via rewriteRelationReads (:1271-1330); a Pure PropertyBinding (no
   .column()) misses the c.column()!=null filter at :1321 → the :1325
   throw. The condition is column-space by normalize time; the resolver
   never sees property space today.
2. **Substitution engine to reuse**: andCorrelatedIntoCondition
   (AssociationJoins:962-1064) — pass 1: own param through
   target.bindings() over tgtRow (RowScope :1022-1026); pass 2: free
   outer vars through parent.bindings() over srcRow. Exactly the
   $this/$that shape; needs two real ClassSources + slotPrefixes/subNavs.
   The base XStore cond must arrive as a property-space TypedLambda —
   the RowScope contract is the seam.
3. **Set-id dispatch**: already complete — get(mapping, class, setId,...)
   → findBinding filters by cb.setId() (ClassSources:710-766, Pure sets
   included). MISSING CALLER: associationJoin resolves targets with NO
   setId (AssociationJoins:728) — the XStore line's set ids must thread
   to that get call.
4. **Whole-instance $src / same-source second set** (trader[trader_set]):
   TWO load-bearing gaps — (a) synthM2M DROPS pb.targetSetId()/
   sourceSetId() (:1352-1410 never reads them); (b) only $src.assocProp
   markers exist (ClassSources:491-500) with ONE consumer
   (GraphEmission.graphChild:565-587 → m2mAssocChild). Need: a whole-$src
   marker tagged with targetSetId + a graphChild consumer dispatching to
   sources.get(mapping, sameClass, targetSetId, ...).
5. **Pins**: 'no Relation or Relational set' is UNPINNED (free to change).
   Must hold: MappingNormalizerTest.associationMapping_singleHop_emits-
   LegacyAssocPredicate (:3857 — Relational-end column-space emission),
   allEmittedNativesResolveInCatalog (:2118), LegacyCleanSheet-
   ConvergenceTest.associationBindingTableConverges (:161). New pure-set
   fixtures should pin the new branch; the runtime shape guard
   (AssociationJoins:837-844) must stay satisfied — a property-space
   emission can still terminate in a 5-arg legacyAssocPredicate whose
   END args are set-pinned class extents, with the cond substituted at
   the resolver.

## Design note: union Pure-set members (banked 2026-07-26)

Four XStoreUnion tests wall at UnionSynthesis.synthUnion's member-kind
gate (:322-329 — members must be Relational/Relation(~func)). The phase
tension is route A's exactly: synthUnion is NORMALIZE-time, a Pure
member's relation is the RESOLVE-time M2M composition (JSON frame +
binding expressions).

Two routes considered:
- (a) property-space MEMBER MARKER threads in the emission, arm factory
  materializes them at resolve — spreads the marker vocabulary into the
  union emission; rejected.
- (b) RESOLVER-SIDE union synthesis for MIXED-kind unions (chosen): when
  any member is a Pure set, the class-level synthesis defers (set-pinned
  class marker, loud if consumed outside the resolver); ClassSources'
  union route builds the arm list itself — per member,
  get(mapping, class, memberSetId) composes the ClassSource (Relational
  members compose exactly as today; Pure members ride the M2M/JSON-frame
  composition landed in route A), each arm projects the SHARED scalar
  properties to property-named columns, arms concatenate with the
  engine's _N member-suffix discipline. One construction site, no new
  emission vocabulary — the leg-3 arm-factory discipline's natural home.

Interactions to hold: member key-column demand (c_PersonID family),
associations INTO union targets (U3 dispatch by member set), milestoned
members (temporal stamps are per-arm), include-closure member resolution.
