# Foundations Baseline — captured 2026-08-16 (F0.1)

> Captured at **main `739d5af9`** (docs merge; last code change `7c55f81b`), NOT at the
> audit's `f6a50a7d` — per the F0.1 execution correction, all expected-red declarations in
> `docs/FOUNDATIONS_PLAN.md` measure against THIS file. Every number is dated and carries the
> command that reproduces it. The audit-delta section (§6) covers the 27 commits the audit
> has not seen.

## 1. Corpus referee (G4, DuckDB sweep)

Command: `LEGEND_ENGINE_ROOT=/Users/neemsandv/legend/legend-engine LEGEND_PURE_ROOT=/Users/neemsandv/legend/legend-pure mvn -pl core test -Dtest=RelationalCorpusRunner -Dlegend.engine.root=/Users/neemsandv/legend/legend-engine`
(run 2026-08-16, exclusive; certified by the full `tools/allgates.sh` chain at `7c55f81b`, all gates GREEN).

| | count |
|---|---:|
| total `<<test.Test>>` functions | 2798 |
| runnable | 2575 |
| excluded (`ExcludeAlloy` 96 + `ToFix` 127) | 223 |
| **PASS** | **2347** |
| FAIL | 73 |
| ERROR | 69 |
| SHAPE | 86 |
| sqldiff-pass | 247 |

Per-family baseline: `docs/RELATIONAL_CORPUS.md` **as committed at `7c55f81b`** is the
row-level record — this file does not duplicate it; the §0.4 expected-red policy diffs
against that committed file.

Referee ratchets (live values, with their file:line):
- advisory-SQL ceiling: **299** (`RelationalCorpusRunner.java:531`; 297→298→299 during the
  burn, each with a dated justification — flagged again in §6.5)
- string-dispatch freeze: **111** (`ObservabilityGuardrailTest.java:52`)
- stderr prints: **43** (`ObservabilityGuardrailTest.java:48`)
- endsWith-on-FQN: **23** (`ErrorShapeGuardrailTest.java:91`)
- catch-returns-value: **20** (`ErrorShapeGuardrailTest.java:87`)

## 2. H2 oracle channel (G4 run above + G5)

- h2-exec (our SQL replayed on H2): **307 verified / 0 diverged / 154 unverifiable**
- h2-backend validation family (G5): **23/23 pass**
- Decline census (the counted concealment channel — F2.3 extends this to `ExecCallFinder`):
  - 448× non-tabular result frame
  - 19× no root exec variable in the actual arg
  - 17× golden execution (9× duplicate column name: FIRSTNAME 4, FIRMID 3, CARRIER 1, NAME 1;
    3× `tempTableForIn_4` not found; remainder misc)
  - 7× enum-decoded column (post-transform rows)
  - 3× no foldable golden string
  - 2× column arity differs

## 3. PCT

Command: `mvn -pl pct -am test` (run 2026-08-16 at `739d5af9`).

| suite | run | failures | errors | skipped |
|---|---:|---:|---:|---:|
| Essential | 327 | 0 | 0 | 0 |
| Standard | 204 | 0 | 0 | 0 |
| Grammar | 136 | 0 | 0 | 0 |
| Unclassified | 94 | 0 | 0 | 0 |
| Relation (DuckDB) | 348 | 0 | 0 | 0 |
| **total** | **1109** | **0** | **0** | **0** |

Gate views of the same suites: G6 (full DuckDB) 1,109 run / 0 / 0 / 0; G7 (Relation
h2modern) 348 run / 1 failure / 22 errors (gate bounds: run≥348, fail≤1, err≤22).

Exclusion pins: **36** total — Essential 25, Grammar 10, Relation 1 (grep
`one(` in `pct/src/test/java/org/finos/legend/lite/pct/Test_LegendLite_*_PCT.java`).
Audit note stands: these are 1,109 of 1,203 upstream scopes (Variant 88 + Quant 6 not wired).

**Known softness (audit §4.1, unchanged at this baseline):** 405 TDS results carry the
declared-header overlay; 322 of 405 have multiplicity rewritten. The F5.3 Stage A probe
converts this into the concealment inventory — these totals are the before picture.

## 4. Parser-equivalence ratchets (live values at `739d5af9`)

| constant | value | file |
|---|---:|---|
| MIN_DOCS_MATCHED | 6489 | CorpusSweepTest:78 |
| MIN_SEAM_MATCHED | 6480 | CorpusSweepTest:81 |
| MAX_SEAM_ORACLE_ASYMMETRY | 0 | CorpusSweepTest:85 |
| MAX_ENGINE_JSON_ASYMMETRY | 9 | CorpusSweepTest:89 |
| MAX_STRICT_ORACLE_ASYMMETRY | 2 | CorpusSweepTest:93 |
| MAX_PLATFORM_CATALOG | 1517 | CorpusSweepTest:99 |
| MSG_VERBATIM_FLOOR | 863 | CorpusSweepTest:107 |
| MSG_RICHER_FLOOR | 1277 | CorpusSweepTest:115 |
| MAX_MSG_GENUINE_MISMATCH | 254 | CorpusSweepTest:116 |
| MAX_DIVERGENCES (generative) | 0 | GenerativeDualParseTest:50 |
| MIN_PINS | 424 | RejectionParityTest:199 |
| MIN_COLUMN_EXACT / MIN_LINE_AGREEMENT | 337 / 417 | RejectionParityTest:169,173 |
| MIN_BEHAVIOUR_MATCHED | 2093 | SectionParseSentinelTest:314 |
| MAX_DROP_IN_DEFECTS | 0 | SectionParseSentinelTest:298 |
| MAX_LENIENT | 17 | SectionParseSentinelTest:323 |
| MAX_LENIENCY_KINDS / MAX_OVER_STRICTNESS | 21 / 6 | FixtureAdjudicationTest:100,101 |
| MIN_SECTIONS / MIN_ELEMENTS | 25 / 41 | EngineSectionRosterTest:35, EngineElementRosterTest:37 |
| MAX_SITES (mutation fuzz) | 3 | MutationFuzzTest:33 |

## 5. `@Disabled("GAP:")` census

**17 sites**, all in `core/src/test/java/com/legend/integration/RelationalMappingIntegrationTest.java`
(command: `grep -rn '@Disabled("GAP' core/src/test parser-equivalence/src/test`). The audit
said 20 — the count at this HEAD is 17; F2.6 re-checks each for staleness (the audit already
identified the XStore and AggregationAware ones as stale).

## 6. Audit delta — the 27 commits the audit has not seen

Range: `9d1f2cd0..739d5af9` (merge-base of main and `worktree-e2e-deep-diagnosis` → HEAD).
These are the burn's closing batches. One line each, classified by the audit's own shapes
(S1 = rewrite-then-reimplement in the harness; clean = typed→typed platform work).

### 6.1 New S1-shaped harness surface (extends the audit's §3 S1 inventory)

- `497de6bd` / `4b8dc15a` — **`AssertLoopForm.consume`** (new file): `$src->map(a|assert(...))`
  loops are consumed by evaluating the SOURCE host-side and lifting each value to a literal
  before re-pushing the asserts. Host-side value handling on the assert path; loud on
  non-liftable elements. Also **`envelopeSizeCheck`**: peels `toOne`/`first`/`at(_,0)`
  around `$result.values` before assertSize — a rewrite-before-compare arm (engine-parity
  justified: TDS carrier is 1 through the envelope; 3 lite unit pins were re-spelled to
  `.rows` forms in the same commit).
- `58fe16d8` — **c52 splice arm**: `size(execute(...))` → `TypedCInteger(1)` + eager frame.
  Typed-fact justified (`Result<T|m>[1]`), but it is a harness-side dispatch arm in
  `spliceHook`, i.e. S1 territory.
- `e25ab497` — **c63 identity-hook recognizer** in `SqlPostProcessors.readHook`: exact-FQN,
  recognized-and-applied, loud otherwise — follows the audit's own convergence target
  (`SqlPostProcessors`), so closer to BOUNDARY than VIOLATION, but it is name-shape
  recognition of Pure the platform does not evaluate.

### 6.2 Audit facts that are now STALE

- **"`StaticFold` is frozen — byte-identical across 691 commits" (§1) is no longer true.**
  `9162d8d4` and `1d962f78` added arms: `isEmpty`/`isNotEmpty`, `toOneMany`, 2-arg `toOne`,
  `toString` over static scalars. All literal-gated in the existing style, both call sites
  unchanged — but the freeze claim must not be cited going forward.
- **StatementExecutor's cited line map is invalid.** `e25ab497` extracted the metamodel-walk
  vocabulary to **`MetamodelSteps.java`** (new file) and `4ea1e734` extracted
  **`PlanAllocations.java`**; `StoreEscapees`, `CastPolicy`, `DateShifts` were extracted in
  earlier burn commits. F7.7's planWalk dispatch criticism now applies to `MetamodelSteps`
  (still simple-name dispatch — unchanged in substance, moved in address).
- The §9 note that `walkProp` **drops** null-walking elements is **half-addressed**:
  `walkMapOver` now flattens and fails honestly (`WALK_UNRECOGNIZED` sentinel, `e25ab497`);
  `walkProp` itself still drops.

### 6.3 Clean platform work (typed→typed, no new tenet surface)

- Lowering/dialect: wire-cast seams (`20f1df87`, `4a60b246`), `adjustTemporal` print-channel
  split (`c56938f3`), average/mean to-one → DOUBLE, in()-membership split + `COALESCE`
  (`4b8dc15a`), SubselectPrune union-arm positional pruning + fixpoint (`e25ab497`).
- Compiler/typer: mangled-id hoist, cell-index arm, null-strict whitelist **deletion**
  (`594cc227` — a policy deletion the audit would approve), Runtime-subtype from(),
  singleton-collection sort peel, n-ary TDS concatenate.
- Resolver/normalizer: OTHERWISE kind-aware dispatch + embedded-exists shared descent
  (`4ea1e734`), closed-query resolution under data lambdas (`ec42f41d`), M2M per-binding
  wall deferral (`6df70129`), owner-aware union routing + recursive route classification
  (`7c55f81b`), frame-carried tableReplace (`7c55f81b` — fixes a REAL wrong-rows case the
  audit's A-ledger did not list: the `$result.values` re-plan ran without the frame's
  renames).
- Plan model: `sqlComment` channel, trailing-let Allocation, VarSetPlaceholder typing.

### 6.4 Guardrail movements during the burn (all structural, none bypassed)

- ENGINE_VOCAB_SHIMS 7→11 (four Any-typed ordering shims, `43ecce8a`); native-catalog +5
  lines; string-dispatch stayed ≤111 (one trip, fixed by map-keyed binder idiom);
  endsWith-FQN stayed ≤23 (one trip, fixed by exact FQN); file guardrails held via the
  extractions above.

### 6.5 Advisory ceiling drift

The down-only advisory-SQL ceiling moved **297→299** during the burn (two justified +1s:
c35/c40 membership/date-literal shape, then the c45/c51/c52/c53 batch). Each carries a dated
comment, but F2.1/F2.2 should treat the ceiling's direction of travel as part of the
soft-pass story it makes visible.

## 7. F0.3 — HostEval arm census (2026-08-16, at `f7628758`)

**File:** `exec/HostEval.java`, 894 lines. **Consumers of `eval`/`evalToResult`:** exactly
one production class — `StatementExecutor.hostChannel:540-543` and `:2876-2877` (both gated
on `wantsHostEval`). The harness consumes only `hostEquals` (`EngineTestExecutor:3369`, a
comparison helper, not evaluation). `HostEvalTest` is synthetic.

**Arm inventory (~47, grouped):**

| group | arms | count |
|---|---|---:|
| structural (eval switch on node type) | NativeCall, MatchRuntime, Map, NewInstance, CopyInstance, Cast, Fold, PropertyAccess, Variable, UserCall, Let, CString, CInteger, CBoolean, CFloat, CDecimal, If, Slice, Filter, Collection | 20 |
| native-call (READ_CHAIN vocabulary) | fold, map, concatenate, at, first, size, and, or, not, eq/equal, in, isEmpty, isNotEmpty, slice, toLower, indexOf, instanceOf, toString, toOne | 19 |
| grid property reads | rows, columnNames, values, parent | 4 |
| K-native / store-nav handlers | fetch (fetchDb + the executeInDb READ path — **audit A9 lives here**, DbMetaData shadow replay), schemaNav (+ collectSchema include-closure), table nav | ~4 |

**Admission gates (`wantsHostEval`) — every arm is reachable only through one of four
gates, and every gate exists to serve corpus-harness vocabulary:**

1. chain bottoms at `executeInDb` (engine test setups; grid reads),
2. chain bottoms at a store-nav fn (`schema()`/`table()` — metamodel tests),
3. `TypedNewInstance` of a CURATED 5-class set (`DynaFunction`, `Literal`, `Alias`,
   `FreeMarkerOperationHolder`, `VarPlaceHolder` — the typeInference test vocabulary;
   the set is deliberately narrow: "any native class" once stole 21 constructions from
   the K path and the gate caught it),
4. `containsFetchDb` anywhere (JDBC metadata grids — corpus only).

**Classification:**

- **Production-reachable (static):** all arms — `hostChannel` sits on the production
  `executeStatements` path (server/LSP/QueryService share it with the harness).
- **Production-demanded (actual):** **none found.** No server, LSP, QueryService, or NLQ
  entry point constructs a query that passes any of the four admission gates today. The
  channel is production-RESIDENT but harness-DEMANDED.
- **Dead:** not statically decidable per-arm (the gates admit whole chains, not arms).
  If eviction is pursued, wire an `LL_HOST_ARM_COUNT`-style counter for one full referee
  cycle first — same instrument pattern as `LL_TOL_COUNT`.

**Consequence (backlogged, not pause work):** since the demand is 100% harness, the
eviction path after F1.2 is to move the host-channel DISPATCH behind a seam the harness
installs, leaving production `StatementExecutor` with no host channel at all — at which
point Charter clause 3's invariant becomes vacuously true in production rather than
guarded. That is a design change with a real risk history (the admission predicate has
collapsed the sweep twice), so it rides AFTER the F1.5 pin exists and only with the
counter data. Recorded in `FOUNDATIONS_PLAN.md` §9 Backlog.

## 8. Reproduction quick-reference

```bash
# corpus referee (exclusive; nothing else running)
LEGEND_ENGINE_ROOT=/Users/neemsandv/legend/legend-engine LEGEND_PURE_ROOT=/Users/neemsandv/legend/legend-pure \
  mvn -pl core test -Dtest=RelationalCorpusRunner -Dlegend.engine.root=/Users/neemsandv/legend/legend-engine
# full chain
LEGEND_ENGINE_ROOT=... LEGEND_PURE_ROOT=... caffeinate -i ./tools/allgates.sh
# PCT
mvn -pl pct -am test
# ratchets
grep -rn "static final int [A-Z_]* = [0-9]" parser-equivalence/src/test/java
```

---

## 9. CLOSING NUMBERS — §11 definition of done (2026-08-17)

The pause ends here. Every phase (0–8) is landed or adjudicated in
`docs/FOUNDATIONS_PLAN.md` (each item carries a **LANDED ENDPOINT** or an explicit
adjudication with its evidence); the §9 backlog holds the named residue.

### 9.1 Corpus referee — every family move explained

**PASS 2347 → 2335** (runnable 2575 unchanged). The delta is exactly four §0.4-declared
events, each with a verdict in `docs/BURNDOWN_EXPLANATIONS.md`:

| family | move | cause |
|---|---|---|
| functions/tests | 239 → 237 | F4.3 `testConcatenateWithFilter` (−1, OutputCol/slot contract deviation, §9-filed); F6.1 `testSQLComments` (−1, fabricated UUID trace comment deleted) |
| tests/mapping/embedded | 62 → 63 | F4.3 `testIsEmpty` (+1 — the deleted harness renderer was breaking it) |
| aggregationAware/test/rewrite | 13 → 9 | F6.1 (−4, activity-trail fabrication deleted; blocked-on-feature) |
| aggregationAware/test/rewrite/NOP | 15 → 7 | F6.1 (−8, absence-asserts that passed for the wrong reason) |
| functions/tests/projection | 145 → 146 | F6.6 `H2Test` (+1 — its executeInDb READ errored only in the deleted shadow replay) |

Every other family is byte-identical to the F0.1 baseline through ~20 green full chains.

### 9.2 The five §0.2 metrics

> **Correction (2026-08-18, `ADVERSARIAL_TENET_AUDIT_2026_08_18.md` §5):** two closing
> claims below were FALSE as written when re-audited. "Falsified self-claims → 0" — the
> audit found ≥8 live, most created by the closing sessions themselves (including the
> `preResolve` citations, corrected 2026-08-18). "Duplicates → one owner" — identifier
> quoting had THREE mutually inconsistent rules (`AnsiSqlRenderer.ident` doubles,
> `Ddl.processColumnName` strips — lossy, `GridReads.q` doubles); the GridReads copy
> retires with the fetchDb deletion leg, the Ddl/renderer divergence is an open row. Read
> the table as the state CLAIMED at closing, not the state verified since.

| Metric | At F0.1 | Closing |
|---|---|---|
| **Unexplained rows** | unknown — inexpressible | **0, mechanically gated**: the scoreboard's soft-pass columns (F2.1) make passing-with-unverified-elements expressible; every non-passing row carries a verdict (adjudication ledger + BURNDOWN_EXPLANATIONS for §0.4 deltas); the regression assert refuses any undeclared red; the five pardon ledgers assert staleness and totals (F3.7/F3.7b) |
| **Duplicate implementations** | 5 readers, 4 writers, 2 substitution, 2 multiplicity, 2 ASOR, 3 quoting, 5 renderers | **one owner each**: JSON read `sql/Json` (F3.1d, 2 documented exemptions), JSON write `Escapes.jsonEscape` (F3.1c), substitution `SourceSubst` (F3.2), multiplicity `Typer` (F3.3), ASOR `AsorRef` (F3.4/b), quoting = engine's exact H2 rule (F3.5), rendering `lowering/Render` in SQL (F4.1–F4.3; deep-audit precision 2026-08-17: the CORPUS render is platform-owned — the PCT wire formatter (`ExecuteLegendLiteQuery.formatAsTds/formatValue`) remains a deliberate host-side formatter until the §9 F4.4 Lowerer-root-mode leg) |
| **Unguarded tenet surface** | total | **funnelled + positively constrained**: `java.sql` pinned to the chartered seam (ArchUnit, F1.3/F1.3b probe-proven), reflection ban (bytecode-level, frozen shrink-only), harness reorder/discipline allowlists exact-match |
| **Falsified self-claims** | 9 | **0** — nine headers corrected (F2.5); the last two (`RawSqlBoundary` contract, `Column.java`) closed by F7.4 and this commit |
| **Uncounted concealment channels** | 2 of 3 | **0** — H2Verify declines censused + registry-gated, ExecCallFinder declines counted+gated (F2.3), rescue counter (F2.2), LL_TOL float-tolerance counted (F4.3b) |

### 9.3 Companion surfaces

- h2-exec oracle: **320 text-matched + 632 text-divergent-rescued row-verified / 0 diverged /
  155 unverifiable** (baseline 307/0/154 — the mirror gained the F6.7 extension parity and
  the F7.4 model-spelled DDL stream).
- h2-backend sweep: 2282/2575, the six named `already exists` DDL collisions (F7.1's one
  tolerated, named gap).
- PCT: 1109/1109 (unchanged through the pause; renderer work F4.4 recorded as a §9 leg).
- Seed stream: 979,780 raw jdbc statements — measured corpus-authored text (F7.5); the
  tolerance apparatus is deleted to one named gap.

### 9.4 What resumes

`passed + explained = 2793` remains the burn-down's definition of done. The left term is now
trustworthy: **passed means passed** (no strips, no fabricated activities, no side-agnostic
coercions, no silent seed repair), and **explained is mechanically enforced**. Task #18
(burn-to-zero) resumes against this scoreboard.
