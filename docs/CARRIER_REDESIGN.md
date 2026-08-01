# Collection-carrier redesign (H2_BACKEND.md §4.1) — the single-compiler leg

Status: R0 LANDED (2026-08-01). Census pins (raw occurrences,
pre-dialect layers): ArrayLit 34, OrderedListAgg 1, SqlFn.LIST_ 147,
SqlFn.UNNEST 13 — `CarrierPurityRatchetTest` enforces shrink-only.
`CarrierStrategies` (identity) wired FIRST in every dialect's passes().
Known runner flapper (noted, unowned): testResultToJsonStream's SHAPE
wall text alternates between two diagnoses run-to-run (counts stable). Measured motivation: the H2 portability sweep's
capability budget (c45, exact): **1,250 of 2,538 corpus tests wall on
collection carriers** — UNNEST 792, array literal 226, LIST_AGG 133,
LIST_CONTAINS 30, LIST_GET 20, TYPEOF 17, LIST_BOOL_AND 5, LIST_CONCAT 3,
tail 9. legend-engine passes these same tests on H2 — the walls are OUR
lowering's DuckDB-native carrier choices, not H2 limits. The engine's SQL
for each construct is the existence proof and the port source.

## 0. Tenets (ordered; #1 is HARD and user-set)

1. **ONE COMPILER — total migration, no dual paths.** The Lowerer and
   resolver emit SEMANTIC nodes only and lose the right to spell any
   backend idiom directly. DuckDB's native carriers (`list()`, `UNNEST`,
   array literals) survive ONLY as rewrite rules of the same semantic
   node in the dialect strategy pass — DuckDB migrates onto each node in
   the SAME commit that introduces it, its rule reproducing today's
   emission (golden-pinned; the 2180 baseline never moves). A construct
   never exists in both worlds across a commit boundary: each rung
   DELETES the old direct-emission site as it lands the node.
2. Rows are the contract (golden text advisory); the differential oracle
   (both strategies executed on DuckDB, row equality asserted) rides
   every rung, plus the engine-golden A/B and the h2 sweep scoreboard.
3. Probe-verified spellings only: every H2 rule's SQL shape executes on
   the real 2.1.214 jar before it is written down; every portable rule
   cites the engine emission it ports (pureToSQLQuery/…​.pure line).
4. Java orchestrates, the database executes — a portable rule may change
   the SQL SHAPE, never move value computation host-side.
5. Where NO portable shape exists (the engine itself goes host-side or
   uses its forked-H2 machinery), the typed `DialectCapability` wall
   REMAINS, budget-counted — an honest wall beats replicated temp-table
   orchestration in the first cut.

## 1. Architecture

The T3.2 rails already exist: SqlPlan → MIR rewrite passes → one
SqlWriter, Dialect = data. This leg adds ONE pass and a node family.

- **Semantic nodes** (`com.legend.sql`, sealed, grammar-first): the
  Lowerer's new output vocabulary for collection constructs. Draft
  inventory (final names at R0):
  - `CollectionSource(elements | subquery)` — a collection used as a
    row source (today: `UNNEST(...)` table function / array literal
    scans).
  - `SubAggregate(value, agg, orderKeys, filter)` — per-group reduction
    of a to-many path (today: `list(...)`, `LIST_AGG`, ordered forms).
  - `Membership(needle, collection)` — `in()` over a collection value
    (today: `LIST_CONTAINS`, array literals in `IN`).
  - `CollectionValue` carriers for list-typed intermediates (today:
    array literals, `LIST_GET`, `LIST_CONCAT`, `TYPEOF`-dispatched
    reads) — the tail; some become rules, some stay walls (R5 census
    decides per construct, budget-counted).
- **The strategy pass** (`com.legend.sql.dialect`, runs before the
  writer): per dialect, rewrites each semantic node to its emission.
  Dispatch is capability-driven: the dialect declares which strategies
  it supports (data, like Spellings/Lexicon rows); the pass selects;
  a node with no rule on this dialect throws the typed
  `DialectCapability` (same wall, now AFTER strategy selection).
  - DuckDB rules = today's emissions, verbatim (golden-pinned).
  - H2/portable rules = the engine's shapes: grouped subselect joined
    by group keys (SubAggregate — the engine's own form, already
    proven here by the parent-copy grouped subselect leg, task #77),
    literal `IN` lists / join-form membership (Membership), `VALUES`
    row constructors or UNION-of-literals (CollectionSource; probed:
    H2 supports both), STRING_AGG forms already landed (c44 RowOrder).
- **The purity guardrail** (core test, R0): walks the pre-strategy MIR
  and FAILS on any dialect-idiom node upstream of the pass. Starts as a
  ratchet pinned to the R0 census of direct-emission sites, burns to
  zero with the rungs, then freezes at zero. This is the mechanical
  form of tenet #1 — the same freeze discipline as string-dispatch/110
  and System.err/40.

## 2. Precompiled services with parameters — the bind-time face of the
same nodes (IMPORTANT, user-set scope)

legend-lite compiles per-execution today (literals baked at compile
time). The service pattern — compile ONCE, execute many times with
parameter values — must work WITHOUT a templating layer and WITHOUT a
second compiler. The design:

- **The precompiled artifact is a TYPED PLAN, not a text template.**
  `SqlExpr.PlanParam` (already in the IR) carries the declared Pure
  type, multiplicity, and enum-ness of each parameter position. Per
  dialect, the plan renders ONCE into SQL with real JDBC bind
  placeholders; execution binds values via `PreparedStatement`. No
  string splicing anywhere — which buys injection safety, database-side
  plan caching, and no re-parse per execution (all three are what the
  engine's Freemarker `${...}` text-templating gives up). For
  multi-backend deployment the artifact is the PRE-DIALECT MIR: one
  compilation, N rendered statement texts sharing one bind map — the
  standalone-SQL-library story (LEGEND_SQL_VISION.md).
- **Collection parameters are the SAME semantic nodes** (`Membership`,
  `CollectionSource`) with the value arriving at bind time — so the
  strategy pass picks the BIND FORM per dialect exactly as it picks the
  literal form:
  1. **array bind** where the backend takes array-typed parameters
     (DuckDB yes; H2 documents `IN(UNNEST(?))` with an array parameter
     — PROBE before trusting, standing rule);
  2. **arity-bucketed expansion** otherwise — `IN (?,?,?)` rendered per
     collection size class, one cached PreparedStatement per bucket;
  3. **temp-table join above a size threshold** — the engine's
     tempTableForIn, reframed from registered wall to the deliberate
     large-N strategy: the EXECUTOR creates/loads/drops a local temp
     table, the precompiled query joins it. Values still evaluate in
     SQL (tenet #4); only the loading is orchestration.
- **The engine's other template jobs map to typed equivalents**:
  `collectionSize` validation guards → the executor checks bound values
  against the PlanParam's declared multiplicity BEFORE binding; enum
  parameters bind their SOURCE code (`Status.ACTIVE` → `'A'`) via the
  enumeration mapping at bind time — the c46 `enumDecodeFor` machinery
  run in the inverse direction; milestoning date params are scalar
  binds.
- **Rung impact**: R2 (`Membership`) designs its node with the
  parameter case IN SCOPE from day one — the node must not assume its
  collection operand is compile-time-known. R3 (`CollectionSource`)
  likewise for parameter-sourced collections.

## 3. Rungs (each: fix → core 1573 → DuckDB sweep 2180 byte-stable →
PCT 1109 → h2 sweep (scoreboard must rise) → differential oracle green →
commit+push)

- **R0 — census + rails (no behavior change).** Site-level census of
  every direct carrier emission in Lowerer/Scalars/Aggregates (the
  budget's 1,250 is test-level; R0 gives the code-level worklist). Land
  the empty strategy pass + the purity ratchet pinned at the census
  number. Engine-reference probe list drafted per rung head.
- **R1 — `SubAggregate`** (LIST_AGG 133 + the list() sub-agg share of
  UNNEST tests). Smallest semantic surface, biggest prior art: the
  engine's grouped-subselect form is already implemented for chained-nav
  aggregates (#77) — this rung generalizes it to a strategy rule.
  **CRUX: ordering determinism.** The list()/OrderedListAgg carriers
  encode Pure's insertion-order semantics (ORDER BY the RowOrder
  pseudo-column — the joinStrings goldens); every strategy rule must
  state and reproduce the SAME element order contract, not discover it
  through red differential gates.
- **R2 — `Membership`** (LIST_CONTAINS 30 + IN-over-collection share;
  the node's collection operand may be literal OR PlanParam — §2's bind
  strategies are this node's rules; tempTableForIn graduates from
  registered wall to the large-N bind strategy when §2 lands).
  **CRUX: NULL semantics.** SQL `IN` and LIST_CONTAINS differ around
  NULL needles/elements, and Pure-vs-SQL null semantics is a known bug
  family here (the `!=`-drops-NULL-rows class). Every Membership rule
  pins its NULL truth table against Pure semantics explicitly; the
  differential oracle includes NULL-edge fixtures, not just happy rows.
- **R3 — `CollectionSource`** (UNNEST 792 — the giant; probed H2 forms:
  `VALUES`, UNION-of-literals; the engine's per-construct shapes read at
  rung head). Expect this rung to split into sub-rungs by source kind
  (literal collections, getAll-for-each-date, variant/JSON explodes —
  the last may remain walls).
- **R4 — array-literal value carriers** (226; many fall out of R1-R3
  since the literals feed those constructs; the residue is genuine
  list-VALUES — census decides rule vs wall per site).
- **R5 — tail classification** (LIST_GET/TYPEOF/LIST_BOOL_*/…​ — rule
  or budget-counted wall, decided per construct with the engine as the
  bar; zero silent drops).

## 4. Deferred decisions (named so they stay decisions, not drift)

- **"Dialect idiom" definition for the purity ratchet (R0 settles):**
  `SqlFn` entries are SEMANTIC vocabulary (Spellings maps them) — legal
  upstream of the strategy pass. The violation class is nodes that
  presuppose a backend's DATA MODEL: list/array values and their
  functions, `json_group_array`/`to_json(list(...))` composites,
  physical pseudo-columns spelled as plain Columns. R0's census applies
  this definition and pins the ratchet number.
- **Performance stance:** correctness first. DuckDB cannot regress BY
  CONSTRUCTION (its rules reproduce today's emission, golden-pinned).
  Flipping any dialect to a different strategy later requires a
  MEASURED reason; no perf harness exists yet — deferred deliberately,
  not forgotten.
- **Graph envelope at scale:** the JSON envelope materializes the whole
  graph as one SQL value. The engine's level-wise batched fetching
  (flat selects per tree level, IN over parent keys, host assembly) is
  BOTH the streaming story for huge graphs AND the natural fallback
  strategy for a backend with no JSON constructors. Deferred until a
  witness (memory blow-up or a JSON-less target); the envelope stays
  the one strategy until then.
- **Plan-format interop:** §2 decides we PRODUCE typed plans (our
  format). Engine-plan interop is DECOUPLED from this leg entirely —
  plan consumption is POST-render (parse plan JSON, fill its template,
  execute, shape rows); the carrier redesign is PRE-render. Cost model,
  layered: (1) the seam signature `execute(enginePlanJson, params,
  conn) → ExecutionResult` is free and non-speculative (the format is
  externally fixed) — write it the day it's needed; (2) a stub — plan
  deserializer for the relational node subset + typed walls — is
  days-scale and reuses what we own (the plan node model behind the
  plan-handle walk tests, PlanText's template-function vocabulary, the
  session/codec/shaping machinery); (3) real implementation — a
  Freemarker-SUBSET evaluator + node-interpreter loop — is its own
  multi-rung leg, spec'd by porting the engine's plan-execution tests.
  TRIGGER: the first real engine-plan artifact we want to run; no stub
  before then (a seam without a consumer test is speculative API).
  ASYMMETRY: the PRODUCE direction (emitting engine plan JSON for our
  parameterized services) would force Freemarker templates back into
  the artifact §2 designed them out of — if interop ever matters,
  consume is the direction that fits; resist produce.
- **Our own dual enum-decode paths:** most frames decode enum codes
  in-SQL (CASE); a residue decodes post-SQL (the c46 witnesses). The
  M1 oracle reconciles the comparison, but the single-compiler spirit
  wants ONE decode layer — converge (decode-in-SQL everywhere, or one
  declared post-SQL transform) as its own small rung after R1.
- **Variant/JSON navigation on H2:** permanent stock-H2 wall (no field
  navigation in any version through 2.4.240; the engine uses a banned
  Java UDF). Revisit trigger: if the variant family ever matters on
  H2, re-open the version pin (2.2+ buys array INDEXING only —
  H2_BACKEND.md verification addendum).
- **Known singles parked with owners:** executionPlan 4x
  ArrayIndexOutOfBounds (real bug — diagnose under #102);
  STRING_AGG ORDER BY qualified `_ROWID_` failure (probe the aliased
  form); the H2 `extract()` unit map is blanket-uppercase and WILL fail
  loudly on non-trivial units (`isodow` → needs a probed unit table);
  step-11 BOOLEAN/DECIMAL codec rows stay witness-driven.
- **XStore / in-memory stores:** host-side stitching the engine does in
  Java — out of carrier scope, tracked as its own feature leg under
  #102 (never an exclusion).

## 5. H2-era code: what retires, what graduated (the cleanup ledger)

Nothing from the H2 arc was left as an undocumented hack, but the
classification must be explicit so "temporary" never silently becomes
"forever":

**Graduated — permanent machinery, NOT cleanup targets:**
- `H2Verify` (M1 oracle, enum-decode replay, unverifiable census,
  registry) — this IS the differential-oracle infrastructure this leg
  runs on.
- `Runner.openSession()` + `dialectOf(ctx, rt, connection)` — the
  session/dialect binding is the architecture (H5.4).
- `RawSqlBoundary.h2ToDuckDb` — inherent to DuckDB being the reference
  target for H2-flavored corpus seeds; honestly gated by
  `rawH2IsNative()`. Deletable ONLY if the reference target changes.

**Policy wearing a hack's clothes — re-document, keep:**
- `case H2 -> distinct.add("DuckDB")` (2-arg `dialectOf`): declared-H2
  models render DuckDB on DuckDB sessions. Post-H5.4 the session always
  wins, so this is the REFERENCE-TARGET POLICY, not a danger — its
  comment should say so (done for the corpus; keep the spelling).

**Real debt this leg retires, rung by rung:**
- **`AnsiSqlRenderer` is DuckDB wearing an ANSI nameplate**: the base
  defaults are DuckDB idioms (`//`, `d + to_days(n)`,
  `json_group_array`/`to_json(list(...))`, `rowid`) and H2 OVERRIDES
  them — inverted layering. As each rung moves a carrier's emission
  into the strategy pass, the corresponding base-renderer idiom moves
  INTO the DuckDB rules; the end-state base is honest ANSI and the
  current H2 `call()` overrides (INT_DIVIDE, ADD_INTERVAL, EXTRACT,
  STARTS_WITH, jsonObject/jsonArrayAgg, rowOrderColumn, reducer) SHRINK
  as the idioms they counteract stop being defaults. Exit check: the
  H2 dialect class is mostly Spellings/Lexicon DATA + strategy rules,
  not call-override code.
- Small duplications to consolidate when touched: `H2.dateUnit` vs
  `EngineStyleH2.dbUnitOf` (deliberate quarantine copy — merge only if
  a THIRD consumer appears), `Ddl.H2_RESERVED` vs `Lexicon.H2`.

**Retirement conditions (recorded, trigger-gated):**
- `EngineStyleH2` + the golden-TEXT advisory channel retire TOGETHER
  if/when row-verification coverage makes text compares pure noise —
  a deliberate future decision, not this leg's.
- H5.5 (Ddl quoting) is FIXED (full-name column quoting); the
  `tableWithQuotedColumns` census entries are a seed-recording decline
  (family's raw seeds not reaching the replay), a census bucket to
  burn, not a DDL bug.

## 6. Exit criteria

- Purity guardrail at ZERO and frozen (tenet #1 mechanically closed).
- h2-backend sweep ≥ 80% of the DuckDB baseline's passing set, with the
  remaining gap 100% budget-classified (walls + registry rows).
- DuckDB baseline 2180+ throughout (byte-stable per rung; improvements
  only by deliberate strategy flips with the differential oracle green).
- The strategy pass + node family documented as the standalone-SQL
  library's collection chapter (LEGEND_SQL_VISION.md cross-link).
