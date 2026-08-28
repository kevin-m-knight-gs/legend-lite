# The metamodel store — leg charter (opened 2026-08-28, fresh-session handoff)

**Mission (user-ratified 2026-08-28):** the metamodel lives IN the
database. `Class.all()` becomes an ordinary mapped-class query over a
system `metamodel.classes` table seeded from the compiler's own
registry — a real `SELECT`, executed by DuckDB, through the EXISTING
store lane. The engine's one routing rule ("mapped → store") then
covers metamodel and user classes with ZERO special cases in dispatch.
The v0 alternative (a host-side compile-time fold in GetAllChecker) was
REJECTED by the cut-over-hard doctrine — it would be a hedge deleted at
cutover; build the end-state directly.

**Payoff:** (a) the last non-ledger PCT row on either lane
(`getAll::testBasic`, Grammar 136→137 — the FULL grammar lane); (b)
every downstream operation free through the existing relational
machinery (`Class.all()->filter(c|$c.name==...)->size()` needs no
reflection folds, ever); (c) the future lane for the 12 MODEL-WALLed
reflection files (`Multiplicity` ×6, `PackageableElement`,
`ValueSpecification`, `FunctionType`, `Package`) and the test-corpus
arc's reflection families.

---

## 1. The engine truth this leg transposes (all verified from source)

Read these before changing anything; they are the spec.

- **The pure runtime has NO metaclass list.** Its data model is one
  self-hosting instance graph: every compiled element is a node with a
  classifier pointer, and ONE index groups objects by type —
  `Context.instancesByClassifier`
  (legend-pure `m3/compiler/Context.java:161-193`), populated uniformly
  for every compiled instance by
  `IncrementalCompiler.registerInstanceInContext` (`:252-258`).
  `X.all()` = one lookup (interpreted `GetAll.java:46`,
  `context.getClassifierInstances`). `Class.all()` works because class
  DEFINITIONS are the instances whose classifier is `Class` — not
  because `Class` is special. (One cosmetic arm at `GetAll.java:49-58`
  wraps the result's generic type as `Class<Any>`.)
- **The router's question is "does the mapping map this class" — never
  "is this a metamodel class."** `processClass`
  (engine `router/store/routing.pure:381`): `rootClassMappingByClass`;
  when EMPTY the state passes through UNROUTED (deliberate — comment
  "Possible when the mapping is 'embedded'"), meant for the pure
  runtime to answer. `isGetAllFunction` = `router_routing.pure:769`.
- **The engine's relational lane CANNOT do this** — its plan generator
  blind-casts and dies; the reference DuckDB PCT manifest excludes
  `testBasic` with `"Cast exception: InstanceValue cannot be cast to
  StoreMappingRoutedValueSpecification"`
  (`relational-duckdb/GrammarFunctions_manifest.json`). In the real
  engine the JAVA pure runtime covers it. **We absorbed both halves
  into one DuckDB pipeline (user framing, 2026-08-28), so the
  pure-runtime half of the job is OURS — this row is fixed, never
  ledgered.**

This leg = making the metamodel MAPPED, so the engine's one rule
subsumes the pure-runtime half too.

## 2. The two ratified design decisions

**D1 — the ambient system context.** `Class.all()` appears with no
`->from(mapping, runtime)`. Rule: *a registry-tracked metamodel
classifier's store is intrinsic* — when the execution context is NONE
and the getAll class is one the registry tracks (§3), the resolver
supplies the SYSTEM metamodel mapping+runtime implicitly. Site: the
context decision feeding `StoreResolver.resolveChain` (the wall at
`StoreResolver.java:~1174`, "class query requires an execution
context"). User classes are UNTOUCHED: no context → the same loud wall
(deliberately better UX than the engine's empty-set answer — a
forgotten `->from()` must not return `[]`).

**D2 — one identity for a class value: the FQN.** Identity (joins,
dedup, equality) is the fully-qualified name — the exact-FQN doctrine;
the SIMPLE name is the print form. DO NOT repoint the existing wire
convention: `Lowerer.java:~2814` lowers a bare class reference in value
position as its SIMPLE name (`StringLit(simpleName(...))`) because the
`columnsMeta`/`type()`-fold comparisons byte-match simple names — those
witnesses must stay green. The metamodel ROW carries both (`fqn` the
key, `name` the print form); an extent element flowing into a
type-value comparison position spells by `name`.

## 3. The registry query (the seeder's data source)

Add to `ModelContext` (interface) + `PureModelContext` (impl —
`PureModelContext.model` is the `ModelBuilder`):

```java
/** THE VIRTUAL METAMODEL GRAPH source: the compile context's
 * registered instances of {@code classifierFqn}, as element FQNs —
 * the engine's instancesByClassifier index transposed onto our
 * element registry. No metaclass list rides any DISPATCH: coverage
 * is a fact about what the registry stores, and new element kinds
 * join by joining the registry. {@code null} = the registry does not
 * TRACK this classifier (a user class — the store lane owns it); an
 * empty list is an honest empty extent. */
@Nullable List<String> classifierInstances(String classifierFqn);
```

| classifier FQN | extent |
|---|---|
| `meta::pure::metamodel::type::Class` (= `PlatformTypes.CLASS_METACLASS`) | `model.classes()` FQNs ∪ `Pure.allNativeClasses()` FQNs |
| `meta::pure::metamodel::type::Enumeration` | `model.enums()` ∪ `Pure.allNativeEnums()` |
| `meta::pure::metamodel::relationship::Association` | `model.associations()` |
| `meta::pure::mapping::Mapping` | `model.mappings()` |
| anything else | `null` |

Deterministic order (sort by FQN). v1 only NEEDS `Class`; the siblings
are one map entry each — include them, they are the same mechanism.

## 4. The system store, mapping, and metaclass surface

- **Schema v1:** one table, `metamodel.classes(fqn VARCHAR /*key*/,
  name VARCHAR, package VARCHAR)`. Grow BY WITNESS ONLY (properties,
  generalizations, enum_values are future tables — do not speculate).
- **Store + mapping synthesis:** a synthesized system Database
  (`meta::lite::metamodel::MetamodelStore`) and a system mapping
  binding the metamodel `Class` → `metamodel.classes` (`name`→`name`,
  `package`→`package`; `fqn` is the key). ONE owner, host-side
  synthesis — follow the MappingNormalizer / ClassSources precedent
  for synthesized mappings; the resolver must see it exactly as a
  parsed mapping (no parallel lane).
- **The metaclass gains `name`:** the native
  `meta::pure::metamodel::type::Class` declaration (in `Pure.java`
  nativeClass table) is currently property-EMPTY, and
  `NativeFunctionTest.everyNativeClassIsMarkedNativeAndHasEmptyBody...`
  pins that. Add `name: String[1]` (real M3: inherited from
  ModelElement) with a documented-surface arm + dated justification
  (the ValueSpecification arm from leg 3b is the template), plus the
  size-pin/golden moves if the declaration form changes them.
  `package` as VARCHAR path only if the witness needs it — else omit
  the property and keep only the column.

## 5. Seeding

- **Site:** the ONE owner where store DDL/setup runs at execution
  setup (find it — the pct harness setup lane and the corpus
  seed-replay both go through it; do NOT add a second seeding door).
- **Content:** derives from the ACTIVE model context — the compile-once
  global model PLUS the per-test overlay (§8-global-compile
  architecture). Per-test overlays re-seed (or delta) — measure, expect
  trivial (the corpus replays 336k raw statements in ~1.2s; this is
  a few thousand rows once per context).
- The seed reads `classifierInstances(...)` + per-element registry
  lookups for columns. Rows ordered by fqn.

## 6. Resolution flow after the leg

`GetAllChecker` UNCHANGED (still emits the `TypedGetAll` store
anchor). The resolver's context selection gains D1's ambient rule; the
system mapping resolves like any mapping; lowering/execution are the
ordinary store lane. Nothing is deleted because the v0 fold was never
built — there is no hedge.

## 7. Witnesses (write them FIRST where possible)

1. PCT: `getAll::testBasic` (`Class.all()->isEmpty()` = false) — the
   banked row. Grammar floor `ChannelBGrammarTest` 136 → **137**
   same-commit (the full lane).
2. **Channel A ledger:** `Test_LegendLite_GrammarFunctions_PCT:57`
   pins testBasic's expected failure with OUR wall text. If A now
   passes over the wire, REMOVE the entry (the ledger fails loudly on
   a fix by design); if A still fails for an adapter-side reason,
   update the pinned text with a dated note — probe, don't assume.
3. New core integration witnesses (e.g. beside GetCheckerTest):
   `Class.all()->size() > 0`; a filter by `.name` over a
   compiled-model class; `->map(c|$c.name)` containment. These pin the
   store lane end-to-end, not just the count.
4. Regression: user class with no context still walls with the exact
   existing message (pin it if not already pinned).
5. D2 regression: the `columnsMeta`/type-fold simple-name comparisons
   (existing suites) stay green untouched.

## 8. Traps

- **NativeFunctionTest triple-pin** (empty-properties arm, size pin
  201, catalog golden) — every metaclass-surface change moves them
  with dated justifications, never silently.
- **The simple-name wire convention is load-bearing** (D2) — repoint
  nothing to FQN in PRINT positions.
- **Resolver changes need corpus insurance**: scoped
  `-Drcorpus.only=graphFetch,tests/query,tests/mapping` baseline-diff
  BEFORE the full chain (the eq-nodes lesson: PCT lanes cannot catch
  resolver overreach; diff test NAMES against RELATIONAL_CORPUS.md
  with exact-name grep ` <name> [`).
- Full `tools/allgates.sh` per green batch (~7-min budget, 12-min P0
  ceiling), caffeinated, tree FROZEN during runs; `mvn -o -pl core
  install` before any hand-run pct lane (stale-jar, 4×);
  `LEGEND_ENGINE_ROOT=/Users/neemsandv/legend/legend-engine`,
  `LEGEND_PURE_ROOT=/Users/neemsandv/legend/legend-pure`; push after
  every green batch; no subagents.
- `Lowerer.java` is at 3464/3500 — nothing in this leg should touch
  it; if something must, the seam-split precedes.
- Update `PROGRAM_MAP.md` (the getAll row's owner = this charter) and
  `CHANNELB_BURNDOWN_HANDOFF.md` §0's unowned-rows note same-commit
  with the landing.

## 9. Sequencing (one leg, ~2 gated batches)

1. Registry query (§3) + metaclass `name` surface (§4 pins) — compiles
   standalone, no behavior change.
2. Store/mapping synthesis + seeding (§4, §5) + D1 ambient rule (§2) —
   the behavior batch; witnesses §7; lanes + scoped corpus + full
   chain; floors banked same-commit.

Out of scope (name them if tempted): properties/generalizations
tables, `Package` as elements, reflection over functions, the
MODEL-WALL files themselves. Witnesses drive growth.

---

## 10. LANDING RECORD (EXECUTED 2026-08-28, two gated batches)

**Batch 1** (32dd934a): §3 registry query
(`ModelContext.classifierInstances` + `PureModelContext` impl over the
ModelBuilder registry ∪ native catalogs, three unit witnesses) and the
§4 metaclass surface (`Class` gains `name: String[1]`; the
NativeFunctionTest documented-surface arm moved with a dated
justification; size pin unchanged at 201 — a property, not a class).
Full chain green.

**Batch 2** (this commit): the whole behavior leg —

- **Synthesis by injection:** the system Database + Mapping are fixed
  Pure SOURCE (`builtin/SystemMetamodel`, parsed once at class load,
  the `Pure` bootstrap discipline), appended to every model build in
  the two `Compiler` doors (`buildModel`/`buildModule`). The resolver
  sees ordinary parsed elements — no parallel lane. `name` column is
  `NOT NULL` (the [1] property demands a [1] read — the normalizer's
  toOne wrap covers [0..1]-to-[1] only for store emissions, and a
  total column is the honest schema anyway).
- **D1** at the `resolveChain` wall exactly: context NONE + every
  `getAll` class beneath the chain registry-tracked → the system
  mapping; anything else keeps the byte-exact wall (witness-pinned).
- **Seeding** at the ONE execution-setup owner
  (`StatementExecutor.executeTyped`, beside `runRuntimeSetups`): a
  resolved body referencing the system store gets
  `Ddl.metamodelSeed(...)` — schema + drop/create through the one DDL
  generator + the registry extent as one multi-row insert. Content is
  a pure function of the ACTIVE context (overlay re-seeds witnessed).
- **Witnesses:** `integration/MetamodelStoreTest` (7: extent
  non-empty, isEmpty=false, filter-by-name, map containment, user
  wall byte-exact, per-context re-seed, mixed-chain wall).
- **Floors banked:** Grammar B census 137/137 (the FULL lane), floor
  pin 136→137; channel A ledger row REMOVED (A passes over the wire —
  the ledger failed loudly on the fix by design); A grammar 136/136.
- **Ratchets, all argued:** JavaEvalLedger StatementExecutor
  2423→2456 (setup orchestration, zero evaluation); SqlTextRatchet
  Ddl 3→4 (the seed INSERT joins the one DDL owner);
  ParserBoundaryArch + JdbcSurfaceCensus register rows.
- **Corpus insurance:** scoped
  `-Drcorpus.only=graphFetch,tests/query,tests/mapping` baseline-diff
  BYTE-IDENTICAL (families + FAIL names) across the resolver change.
  Full-sweep PASS steady at 2334/2575.
- **Census taught, pin unmoved:** the [1]-over-nullable census filed
  the system mapping's `Class.name` under its unresolved-property
  blindness bucket (98 > the honesty pin 97) — fixed by teaching
  `RequiredNullableCensus` the native-catalog owner fallback (the
  instrument now ADJUDICATES the pairing; `name` is NOT NULL, so it
  adjudicates clean), never by bumping the honesty pin.

**Traps hit (recorded for the next session):**
- The G8 fixture-adjudication scanner treats test-tree STRING LITERALS
  starting with Pure keywords as fixtures — an assert message
  beginning `"Class declares…"` minted a new leniency KIND.
- The pct ChannelB suites read `-Dlegend.pure.root` (SYSTEM PROPERTY);
  the `LEGEND_PURE_ROOT` env var is IGNORED — a hand-run against the
  default `~/legend/legend-pure` stale tree fakes `discovery 136 !=
  137` and a vanished `testEqualEmpty` (the rcorpus -D trap's pct
  twin; the gate script's own comment names the signature).
- Injection un-walled `routing.pure` + `reactivate.pure` in the B
  model-wall census (19→17, shrink-only ceiling intact) — the
  walls-loop's drop cascade is order-sensitive to appended elements.

Future lanes unchanged (§4 grow-by-witness): Enumeration/Association/
Mapping classifiers are registry rows already; their store tables and
mapping arms arrive with their first witness.
