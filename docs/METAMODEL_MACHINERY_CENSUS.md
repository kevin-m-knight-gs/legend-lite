# METAMODEL / HOST-EVALUATION MACHINERY CENSUS — 2026-08-30

Step-0 homework (user directive): the FULL lay of the land of every
place the platform (or its harness) evaluates, navigates, or spells
metamodel/pure semantics host-side — before any burn touches the
quarantined metamodel declines. Seeded from the JavaEvalLedgerTest
registry (EVICT_SIZE + EVICT_NAMES + the funnel class registers) and
verified against the tree by grep; sizes are the ledger's pinned
COMMENT-STRIPPED counts unless marked raw. Companion:
docs/FULL_RESIDUE_CENSUS_2026_08_30.md (the row-by-row residue
census) and PROGRAM_MAP § "DEFERRED PROGRAM — METAMODEL AS DATA".

## 1. THE ENTRY-POINT CENSUS (the "side doors")

The user's standing ruling ([[one-router-one-evaluator]] memory): ONE
entry point for all pure code. Today the statement loop
(`StatementExecutor.executeStatements`) dispatches through THESE
doors, in order (each a place pure-ish evaluation can happen outside
a single orchestration):

| # | door | what it claims |
|---|---|---|
| 1 | `TdgNatives.foldCensus` (per-statement pre-fold) | test-data-gen carriers → instance literals (model-space census + generator execution) |
| 2 | let handling (alias frames, eager execute frames, effect walls) | binding orchestration |
| 3 | effectful user calls / `TdgNatives.needsBodyRoute` | body inlining route |
| 4 | `AssertVerdicts.tryAdjudicate` | statement-root asserts → verdict-in-DB (sides lower; comparison layer judges) |
| 5 | `hostChannel` → `StoreNav.owns` → `hostEvalAtSeam` | store-nav shapes evaluated at the seam |
| 6 | `processingTemplateFunctions` arm | plan support functions (host list) |
| 7 | `planWalk` | plan-node model + METAMODEL walks (via MetamodelSteps) |
| 8 | `AssertErrorNative.run` | assertError orchestration |
| 9 | execute()-in-result-position | frame build |
| 10 | fall-through | resolve (Phase H) → lower → DATABASE (tenet #1) |

Doors 1, 4, 5, 6, 7 each carry evaluation or per-FQN dispatch. The
harness adds an eleventh: `EngineTestExecutor`'s own host verdict
path (v7 dual channel's "host" side) with its subst/splice/compare
machinery. This table IS the migration work-list whenever the
one-router program un-defers.

## 2. COMPONENT INVENTORY

### 2a. Metamodel value-space components (main tree, ledger-pinned)

| component | size (stripped pin) | role | provenance discipline |
|---|---|---|---|
| exec/MetamodelWalk | 1307 | HOST-side metamodel navigation + DERIVATION. Handle records: Db/Sch/Vw/Vcm/Tbl/ColH/AliasH (store), Mm/Cm/Pm (mapping), Rop/Dt (relational ops/types), NodeH + QnH/CnH/TacH/DynH/JtnH/RoewjH (SQL-protocol nodes, structural equality). Per-FQN methods, each javadoc-cited to the REAL .pure line: store nav (schema/table/view/column); mapping reflection (classMappingById, _classMappingByClass, rootClassMappingByClass, superMapping, allSuperSetImplementations, mainTable, resolvePrimaryKey, propertyMappingsByName); typeInference (infer/inferOp/dynaNode/columnType — the dyna return-type rules hand-ported); dataTypeToSqlText spelling; toPostgresModel conversion (convertElement/convertSelectSql/convertOp/convertJoinTree/convertWindowColumn) | a HAND-PORTED SPEC — a per-function third implementation of engine pure |
| MetamodelSteps | 196 | the recv-dispatched per-FQN vocabulary SWITCH shared by planWalk and the map-lambda walk (exact FQNs; classMappingById/view/inferRelationalType/convertElement/toOne/cast/map/filter/first/at arms) | dispatch only; delegates to MetamodelWalk |
| StatementExecutor walk family | 40 pinned name-occurrences (planWalk/walkProp/walkFilter/walkResult/planModel/constructNode/constructOp/nodeValue…) | the walk DRIVER: PackageableRef→Db/Mm handles; property access; cast/filter/map; TypedNewInstance→protocol-node/relational-op construction; plan-node model | driver only; vocabulary lives in MetamodelSteps |
| builtin/SystemMetamodel | (not size-pinned; builtin) | THE METAMODEL STORE: metamodel IN the database. v1 = ONE table (metamodel.classes: fqn PK/name/package) + Class metaclass mapping (name property only). `Class.all()` is a real SELECT through the ordinary store lane; extent seeded from ModelContext.classifierInstances at the one execution-setup owner; grow BY WITNESS ONLY | the RATIFIED opposite answer — model-as-data, zero dispatch special cases |
| builtin/Pure metaclass fragment | 212 `nativeClass` declarations total (the metamodel share includes SetImplementation family, Mapping, Database/Schema/Table/View/Column, RelationalOperationElement family, the SQL-protocol node classes, plan classes) | the TYPE surface for all of the above — signatures verified against real .pure | declaration only |
| normalizer/MappingNormalizer | (compiler; not on this ledger) | compile-time AUTHORED-fact normalization: setIdOf defaulting, extends links, view-backed mainTable resolution. Deliberately does NOT precompute inheritance-walk facts ("the parent's ~mainTable is not auto-copied", :761) | compiler-owned construction |
| compiler/spec/NormalizeFolds | 100 raw | the ONE sanctioned constant-fold owner (typing + inlining seams): literal-if prune (inlined provenance only — engine parity keeps user ifs in SQL), size-from-multiplicity, int eq/equal, bool and/or | spec-sound folds, engine-parity gated |
| resolver/Pipelines.instanceLiteralProp | (one rule inside Pipelines) | property access over an instance LITERAL folds to its value — THE literal-prop rule (TDG lane consumer) | single rule, single owner |
| testdatagen/TdgNatives + TestDataGenerator | funnel-registered (373 raw + generator) | orchestration-time FOLD of test-data-gen carriers; census + generator execution; splices compiler-minted literals | the named instance of the bespoke-entry-point pattern; RENAME to spelled-out vocabulary owed at migration (user ruling) |
| plan/PlanText | 750 | engine-parity plan TEXT composition (envelope, temp-table emitters, PureExp let-allocation) | text spelling, single owner |
| AggAwareActivities | 227 | aggregation-aware activity replication | recognition |
| exec/StoreNav | 199 | store-nav chain recognition (owns() gate for hostChannel) + chainBottom walker | recognition/gate |
| exec/DynamicPivot | 118 / exec/GridProbe 52 | late-bound grid schema staticize + LIMIT-0 probe | schema-only |

### 2b. Verdict / comparison layer (permanent-allowed class — consumes
two produced sides, never produces a result)

AssertVerdicts 1405 (verdict dispatch; K-arm), PureAsserts 311,
TdsCompare 431, JsonCompare 70, RaisedErrors, CanonicalForm +
CanonicalDivergence (census/referee; the step-0 decline-witness
instrument lands here), CanonRider, CanonDeclines, InstanceIds,
SqlTypeCensus. StatementExecutor itself pinned 2520 (the
K-orchestrator; absorbs by design, watched).

### 2c. Harness side (test scope — NOT ledger-pinned; the v7 "host"
channel)

| component | raw lines | role |
|---|---|---|
| harness/EngineTestExecutor | 3717 | native test-body ORCHESTRATION (header: "No interpreter" — statements route through the compile-to-SQL pipeline); the v7 dual-channel classifier + probe; subst/splice; Outcome polarity |
| harness/H2Verify | 1197 | the H2 golden-replay referee (M1 verify, tdgSqlReplay, mirror seeding) |
| harness/ObjectRefs | 326 | harness value refs |
| rcorpus/Runner | 2725 | corpus driver: census, per-test context (CONTEXT_SOURCE), family sessions, scoreboard |

### 2d. PCT adapter (registered residue)

PctExecuteNative 131, ModelPacker 266, ValueBridge 355 — the
E1 adapter contract (ingress splicing, scalar bridge, H4 remap).

## 3. THE DUPLICATION MAP (the census payoff)

1. **Model-as-data exists TWICE, in different value spaces.**
   SystemMetamodel: model facts as DATABASE ROWS (Class.all lane,
   trivial fragment). MetamodelWalk: model facts as JAVA HANDLES
   (rich fragment). A query answerable by either would answer in two
   different worlds. Today they do not overlap (the store knows only
   classes.fqn/name/package) — growth on either side without a
   ruling WIDENS the fork.
2. **Engine spec'd-pure functions exist twice**: the real .pure
   bodies (reference checkouts, spec) and MetamodelWalk's hand-ports
   (mainTable inheritance walk, resolvePrimaryKey precedence table,
   dyna type-inference rules, toPostgresModel conversion). Each
   javadoc cites its spec line — honest, but a per-function third
   implementation by construction.
3. **Read-time derivation vs compile-time fact**: MappingNormalizer
   deliberately leaves inheritance-walk facts UNDERIVED; MetamodelWalk
   re-derives them per read. Under the deferred metamodel-store frame
   this is THE load-bearing fork (seed-time precompute vs SQL
   recursion vs status quo) — recorded as open homework item 1 in
   PROGRAM_MAP.
4. **Acknowledged small copies**: MetamodelWalk.setIdOf mirrors
   MappingNormalizer.setIdOf (comment-cited, one line).
5. **Entry points**: §1's table — five evaluation-bearing doors plus
   the harness channel, vs the ruled ONE.

## 4. WHAT THIS CENSUS CHANGES (recorded implications)

- The deferred metamodel-as-data program's open question 1 is now
  ANSWERED for the walk side: the facts are AUTHORED + normalized at
  compile time; the DERIVATIONS (inheritance, PK precedence, type
  inference) run at read time in MetamodelWalk. Any store-frame
  design must place those derivations explicitly.
- The one-router migration work-list is §1's table, in order of
  evaluation weight: MetamodelWalk (1307) > harness channel >
  TdgNatives > hostChannel/StoreNav > planWalk driver.
- The ledger discipline (shrink-only, justification bumps) already
  points every component here at eviction; this census adds the
  DIRECTION each should evict TOWARD when its program un-defers.
