# V7 — corpus asserts become SQL verdicts (charter, 2026-08-28)

**Mission.** Retire the corpus harness's private assert-comparison
lattice — the THIRD implementation of pure equality semantics — by
routing corpus assert statements through the production verdict path
(`StatementExecutor` → `AssertVerdicts`), i.e. the PCT lane's
RATIFIED dual-verdict architecture EXACTLY AS IT EXISTS
(`AssertVerdicts:125-140`): each side executes with the canonical
render RIDING THE SIDE QUERY ITSELF (`wrapWithCanon` — the DATABASE
computes the canonical bytes; sameElements arrives ORDER BY canon
text in the same execution); the verdict of record is byte equality
of DB-computed canonical renders (Java's remaining role is a
semantics-free `String.equals`); the host lattice stays as the
PERMANENT parallel referee with the disagreement alarm; boolean
`assert`/`assertFalse` K-arm conditions adjudicate fully in-DB.
What dies is the corpus harness's SEMANTIC compare code
(`goldenEqualScalar`, temporal decode, numeric-by-value) — the
semantics move into the one canon owner's SQL. TRUE single-query
fusion (both sides in one round trip, one verdict row out) is V12,
deliberately NOT this leg. Standing ruling 2026-08-24 (PROGRAM_MAP
longer-arc §3): one leg, no incremental drift, no half-migrated
referee; unblocked by PCT completion 2026-08-28. Phase-0 census:
[V7_ASSERT_VERDICT_CENSUS.md](V7_ASSERT_VERDICT_CENSUS.md).

---

## 1. The facts this charter stands on (all verified 2026-08-28)

- **The seam is ONE dispatch arm.** `EngineTestExecutor` already runs
  non-assert statements through `Compiler.executeResolved` (the
  production path); ONLY statements matching
  `simpleName(...).startsWith("assert")` divert to `checkAssert`
  (`EngineTestExecutor:753-769`) — the host lattice
  (`goldenEqualScalar` + golden temporal-decode arms + the grid/CSV
  conventions). The temporal-decode arms' own comment already says
  "these arms delete wholesale" with a render cutover.
- **The production adjudicator covers 11/12 forms.** `AssertVerdicts`
  (PCT-battle-tested: K-arm verdicts, canon riders, dual-verdict
  alarm) recognizes assertEquals/NotEquals, assertSameElements,
  assertSize, assertEq, assertEqWithinTolerance, assert/assertFalse,
  assertInstanceOf, assertIs, assertEmpty/NotEmpty,
  assertTdsEquivalent — vs the corpus census (~1,880 data sites) this
  is total coverage except **assertJsonStringsEqual (167 sites)**,
  the one NEW verdict form (the graph lane; production `JsonCompare`
  + the byte-canon channel are its design anchors — Channel B's
  graph verdicts are the precedent).
- **Performance is a non-issue, measured**: 24,529 queries / 6.5 s
  full-sweep (0.26 ms each). Per-assert round trips are UNCHANGED by
  this leg (both sides already execute today; the canon rides those
  same executions as appended columns — wire grows by the canon
  column, shrinks by nothing yet). The round-trip HALVING (fused
  sides, one verdict row) is V12's payoff, measured then.
- **The dual-referee plumbing already exists**: the corpus lane
  prints `sql-verdict agree/disagree/declined` counters (all zero,
  unexercised).
- **The softness flags mostly do not migrate**: text-rescued (614),
  sqldiff (258), adv-pass (304) annotate the GOLDEN-SQL/H2-replay
  advisory channel — plan text and cross-engine rows, host/oracle by
  design. Only two obligations cross: 0-assert passes (27) must stay
  visibly zero-assert (a vacuous verdict must not hide them), and
  per-test softness attribution survives the re-route.

## 2. Scope partition (the honest claim)

MIGRATES (~1,880 data-assert sites): the §1 form table.
STAYS HOST BY DESIGN (named, not debt):
- `assertSameSQL` (229) + `assertEquals(...sqlRemoveFormatting())`
  (341) — PLAN-TEXT comparisons.
- The TDG arms (`generateSeedDataString` CSV compares, plan-text
  compares — `EngineTestExecutor:1842-1896`) — host artifacts.
- The golden-SQL advisory / H2-replay oracle channel, wholesale.

## 3. Design decisions

**D1 — one owner, no fourth implementation.** Verdict queries are
constructed by the production `AssertVerdicts` ONLY. The harness's
contribution shrinks to what it already owns: statement sequencing,
the execute-handle SPLICE (assert args referencing `$result` reads
wrap exactly like the non-assert statements it already routes:
`LambdaFunction(execStmts + spliced)`), and outcome accounting. Any
corpus-only comparison rule found during the burn moves INTO
`AssertVerdicts` (or its canon layer) with a witness — never into a
harness arm.

**D2 — the dual phase is a referee, never a mode** (no-adapter-hedges
doctrine). Host verdict stays the verdict OF RECORD while the SQL
verdict runs beside it; the existing `sql-verdict` counters carry the
per-test disagreement census; DECLINED is a named per-form census,
never a silent skip. The cutover deletes `checkAssert`'s comparison
lattice in the same slice that flips the verdict of record.

**D3 — order keys are explicit.** `assertSameElements` verdicts sort
both sides canonically; `assertEquals` over rows carries the
row_number order key (PROGRAM_MAP §3's recorded acceptance). The
grid render conventions (TDSNull sentinel, engine text-compare) move
into verdict-query construction.

**D4 — assertJsonStringsEqual lands INSIDE the leg** (a data assert
cannot stay host-side past cutover without violating the no-half-
migration ruling). Design anchor: the graph lane's byte-canon; if the
form proves un-verdictable for a subshape, that subshape gets a
NAMED, ceiling-pinned residue adjudicated with the user BEFORE
cutover, not after.

## 4. Sequencing (~3 gated batches inside the one leg)

1. **Wire the dual channel** (no behavior change): the assert
   dispatch arm (`EngineTestExecutor:753`, gated by
   `harnessVocabName`) additionally routes each assert through the
   production path; host verdict remains of record; counters
   populate. **The wiring shortcut — do NOT hand-plumb**: the harness
   ALREADY calls `Compiler.executeResolved(...)` for setup statements
   (`:793-809`, with the `LambdaFunction(execStmts + spliced)` wrap
   and `NameResolver.resolveQuery(wrapped, imports,
   ctx.elementFqns())`) — and `executeResolved` →
   `StatementExecutor.executeStatements` ALREADY dispatches
   statement-root assert-family calls to `AssertVerdicts
   .tryAdjudicate`. Batch 1 = the same call for assert statements
   with the outcome captured instead of thrown away; never construct
   `ExecEnv`/call `AssertVerdicts` directly from the harness. The
   counters' owner is `com.legend.exec.CanonicalDivergence`
   (`probeSqlVerdict`/`sqlDisagreeCount`/`sqlDeclinedCount` — the
   [canon]/sql-verdict lines the runner already prints). Instrument:
   per-form agree/disagree/declined + a rows-fetched-per-assert
   histogram (the golden-size fact §5-1 of the census). Scoreboard
   byte-identical BY CONSTRUCTION — full chain green.
2. **Burn the census to zero**: fix verdict-construction gaps
   per-form (order keys, TDSNull, temporal spellings, the JSON form);
   every fix is a production-side change with a witness. DECLINED
   shrinks to the named §2 partition. Scoreboard still untouched.
3. **The cutover** (one slice): SQL verdict becomes the verdict of
   record; `checkAssert`'s comparison lattice + `goldenEqualScalar` +
   the golden temporal-decode arms DELETE (shrink pins move with
   dated justifications); 0-assert accounting and softness
   attribution re-anchored; the dual-verdict alarm stays armed
   permanently (PCT precedent). Acceptance: scoreboard IDENTICAL
   (2,334 + the family table), disagreement 0, declines = the §2
   partition only, full chain green, push.

## 4L. Batch-1 LANDING RECORD (2026-08-28)

**EXECUTED — the dual channel is live; scoreboard byte-identical.**

- **Call-site census**: `checkAssert` has exactly TWO direct call
  sites — the main dispatch arm (`EngineTestExecutor` statement loop)
  and `runPerDriverLoop`. `AssertLoopForm` and `RuntimeIfForm` re-enter
  by pushing statements onto the `work` deque, so their asserts land at
  the main arm — both direct sites carry the dual channel
  (`v7DualChannel`), so coverage is total.
- **The wiring is the §4.1 shortcut verbatim**: `v7DualChannel` calls
  `evalSpliced(subst(spelledAssert, lets), execStmts, …)` — the
  existing setup-statement pattern; `AssertVerdicts` is never touched
  from the harness. Two enabling facts discovered en route:
  1. Real Pure AUTO-IMPORTS `meta::pure::functions::asserts`
     (m3.pure:202's system imports) — that is why corpus tests call
     the family bare. Our implicit tier is the native registry, which
     owns only assert/fail/assertEqWithinTolerance/assertError/
     assertTdsEquivalent. So (a) the corpus global model now loads the
     REAL legend-pure assert sources (12 files, `Corpus.PURE_ASSERTS`)
     as library sources — parsed native twins drop at the global
     compile's library-scoped prune (the ChannelB idiom; registry is
     the definition) — and (b) the splice FQN-spells bare assert names
     the registry does not own (`v7Spell`). Qualified and
     registry-owned spellings pass through untouched.
  2. **Probe isolation**: the duplicate executions must not feed the
     primary lane's pinned compiler censuses —
     `SqlTypeCensus.probeSuspend` brackets the probe (first sweep
     tripped four ceiling pins purely by double-counting).
- `testExtension.pure` (`assertJsonStringsEqual` — same asserts
  package) deliberately does NOT load in batch 1: its unported
  `meta::pure::functions::test` siblings add wall rows to the
  scoreboard doc. It loads with D4 in batch 2.
- **Census (full DuckDB sweep 2026-08-28, the batch-2 work list)**:
  `dual-channel agree=141 disagree=0 declined=5100`. ZERO
  disagreements — every pair both adjudicators judged, they agreed
  (per-form agrees: assertSize 79, assertEquals 44, assertSameElements
  10, assert 4, assertEq 2, assertFalse 1, assertNotEmpty 1). Inner
  referee on those pairs: `sql-verdict agree=41 disagree=0
  declined=15`; [canon] pin 27 held exactly. Declines by class
  (console `[v7]` lines are the authority):
  | class | ~sites |
  |---|---|
  | lowering gap (TypedPropertyAccess/TypedVariable under verdict sides) | 1,644 |
  | exec-envelope reads (`$result.values`/`.activities` — "no row scope") | 1,407 |
  | resolver: class query under wrapper (TypedUserCall/TypedMap/if) | 703 |
  | host partition: sql/plan-text forms (§2, BY DESIGN) | 375 |
  | unknown function (assertJsonStringsEqual et al — D4) | 180 |
  | resolver: getAll shape unresolved | 174 |
  | no scalar lowering for overload (assert/2 under assertContains etc.) | 89 |
  | unbound variable (TDG lets) + host-unsupported + grid sides + tail | ~200 |
- **§5-1 answered**: side-rows histogram `0:24 1:2069 2-3:666 4-7:455
  8-15:87 16-31:21 32-63:3` — 92% of sides are ≤3 elements, max
  bucket 32–63. VALUES-literal cost for V12 is a non-issue.
- Guardrail registers moved with justification: ErrorShape
  EngineTestExecutor 3→4 (the decline tunnel), HarnessDiscipline
  CanonicalDivergence 4→6 (report display sort), JavaEvalLedger
  AssertVerdicts 834→840 (histogram hook), ArchitectureTest statics
  (V7_FORMS/V7_DECLINES/V7_SAMPLES). Witness:
  `V7DualChannelCensusTest`.

**Batch-2 reading of the census**: with disagreement already ZERO, the
burn is the DECLINE table — chiefly the exec-envelope read lane
(`$result.values` splice into the verdict side path) and the
verdict-side lowering/resolver gaps; the host-partition rows (375) are
the named §2 residue and stay.

## 4M. Batch-2 slice 1 LANDING RECORD (2026-08-28)

**The envelope splice reaches the verdict lane; two byte-channel gaps
the alarm caught are fixed. Census: agree 141→2,023, declined
5,100→3,128, disagree 0→90 (named, the next slices' work list).**

- **The splice**: `evalValue` built its `UserCallInliner` WITHOUT the
  statement loop's `spliceHook` — verdict sides compiled
  `$result.values` as raw variable reads and walled. The hook now
  threads `executeStatements` → `tryAdjudicate` → every side
  evaluation (`SpliceHook`); pin: `AssertVerdictSpliceTest` (adjudicate
  + polarity + condition/size lanes). `.activities` reads hit F6.1's
  loud wall — honest declines.
- **Alarm catch #1 (enum-under-Any)**: the literal channel spells an
  Any-carried enum as a quoted string while the enum canon spells the
  bare name — the byte verdict FAILED an assert the engine holds.
  Fixed as a NAMED decline (`enum kind has no literal channel`);
  witness `AssertVerdictsTest.enumUnderAnyDeclinesToHost`.
- **Alarm catch #2 (TDSNull sentinel)**: expected `'TDSNull'` vs an
  actual NULL cell holds in the lattice by the DECLARED sentinel
  policy (PureAsserts, expected-direction only) but byte-differs by
  construction. New declared-policy arm on the byte channel
  (`sqlTdsNullPolicy`, the 2-ULP shape) — hold BY POLICY, counted.
- **Alarm witnesses got a RESERVED buffer** (`sqlDisagreeSamples`,
  printed as `[canon] ALARM`) after the one alarm row was crowded out
  of the 200-cap shared sample buffer.
- Full sweep GREEN: inner `sql-verdict disagree=0` (1,464 agree, 478
  declines), scoreboard byte-identical, soft ceilings exact.
- **Remaining outer census (next slices)**: disagree 90 = the
  grid-text render family (CSV floats/TDSNull-in-joined-strings/`~`
  joins — D3's `GridCompare.renderedText` arm), arrival-order rows
  (D3 order key), decimal/temporal golden spellings, 3 forAll-contains
  shapes. Declined 3,128 = resolver class-query-under 924 (largely
  sql-text family), Tabular sides 654 + Graph sides 214 (the D3 grid /
  D4 graph verdict arms), §2 partition 375, unknown-function 180 (D4),
  getAll shapes 177, assertContains-overload 89, TDG unbound 68, tail.

## 4N. Batch-2 slice 2 LANDING RECORD (2026-08-28)

**D3 executed — the grid/order conventions live in verdict
construction. Census: disagree 90 → 8, agree 2,023 → 2,105.**

- **ORDER VIEW** (`AssertVerdicts.orderView`): SORTED (ends in sort
  through the audited order-preserving tails, moved verbatim from the
  harness) / INCIDENTAL (bottoms at a store source or an execution
  frame — SQL arrival order; engine goldens encode H2's) / DEFINED
  (pure values). An INCIDENTAL assertEquals fetches CANONICAL-order
  riders on both sides and judges order-insensitively (exactly the
  assertSameElements shape); SORTED/DEFINED stay strict. Witness:
  `AssertVerdictSpliceTest.incidentalOrderPolicy` (reversed golden
  holds unsorted, FAILS under sort()).
- **RENDERED-TEXT arm**: toCSV/toString-over-relation/
  toCSV→replace('\n',sep)/sep-join-over-incidental spellings route to
  `GridCompare.renderedText` — the one policy owner, R1b-probed;
  assertSameElements gets the token-multiset view. Burned the
  calendar CSVJOIN family and the joined-string rows.
- **GRID-PAIR arm**: both sides statically relation-stamped →
  `GridCompare.grids`. Witness `gridPairVerdict` (#TDS golden vs
  project). Non-tabular execution under a relation stamp is a LOUD
  wall.
- **forAll-contains subset fold** moved from the harness's fc arm:
  both sides DB-computed, membership judged by the lattice.
- **DriverPk parity**: the verdict side lane now applies
  `DriverPkAppend` exactly like the generic statement path — the
  option is EXECUTION ENV (the validation family's 14 disagreements
  were a missing ID column; all burned).
- **R1 probe isolation** (`r1Suspend`): the dual channel's duplicate
  executions no longer double-feed the [canon] disagree≤27 pin.
- **The remaining 8 disagreement rows are WIRE-FIDELITY findings,
  not verdict-construction gaps** — the census doing its job:
  1. Decimal SCALE drift (×4, testDataTypeMapping): literal `1.234d`
     vs the column-scaled wire cell — X2's own doctrine ("fixed at
     emission, never re-blurred"); an emission-scale work item.
  2. Temporal nine-digit convention (×2, graphFetch dates): the wire
     decode must carry the engine's fromSQLTimestamp nine-digit
     subseconds for the lattice's exact compare.
  3. Sort-TIE order (×1, testTDSConcatenate): golden encodes one tie
     order; a re-plan legally produces another (the charter's phantom
     class).
  4. Milestoning TDSNull row-strings (×1): null-cell string-concat
     spelling across lanes.
  Cutover (batch 3) requires these adjudicated: emission fixes or
  NAMED ceiling-pinned residue agreed with the user (D4's mechanism).

## 4O. Batch-2 slice 3a LANDING RECORD (2026-08-28)

**Census: agree 2,105 → 2,635, declined 3,128 → 2,598, disagree
steady at the 8 named §4N rows.**

- **§2 partition BY FORM at the splice**: assertSameSQL/
  assertSameSQLs/assertEqualsH2Compatible/assertSqlEquals classify as
  `host-partition-sqltext` WITHOUT routing — they compare the PLAN by
  design; routing them only produced noise walls.
- **assertSize learns the result kinds** (cluster 34 moved to the
  owner): grid ROWS, graph array length, values otherwise; the
  ONE-CARRIER envelope rule ($r.values of a relation execute = one
  TDS) reads from the MODEL (`ExecutionResult.envelopeCarriers`),
  keyed by the same read shape as the harness arm
  (`envelopeValuesRead`).
- **assertContains arm**: both sides DB-computed, lattice membership.
- Witnesses: `AssertVerdictSpliceTest.sizeEnvelopeAndContains`.
- Remaining declines (~2,598): §2 partition ~690, class-query-under
  ~610, assertEquals tabular/flat-cells ~340, D4 JSON 175 + graph
  sides, getAll shapes ~175, TDG unbound ~68, tail. Remaining
  slices: flat-cells/tabular assertEquals conventions, D4
  (testExtension + JSON verdict arm), resolver gaps adjudication.

**Process note (recorded twice now)**: no repo writes while a chain
runs — two chains were stopped mid-run after edits started; the
certification chain must launch AFTER the slice's last write.

## 4P. Batch-2 slice 3b LANDING RECORD (2026-08-28)

**D4 partially landed; the §2 partition is fully NAMED. Census:
agree 2,637, declined 2,596 (of which the honest partition:
sqltext 961 + TDG 123), disagree 8 (§4N).**

- `testExtension.pure` loads (the 7 unported-sibling wall rows join
  the scoreboard doc — this slice's adjudicated change);
  `assertJsonStringsEqual` now RESOLVES and the verdict ARM exists
  (JsonCompare, the V3 tree owner; the [x]≡x root bridge moved).
- Graph sides DECODE (the DB-built JSON array's elements — the
  harness Eval convention moved to the owner).
- TDG/plan-text asserts classify `host-partition-tdg` at the splice
  (§2's TDG arms), joining the sqltext partition.
- **D4 remainder (typing/registry legs, the arm is ready)**: the JSON
  form's sites stay declined on (a) `Result<T|m>` — our
  `execute().values` types `[*]` where the engine refines to the
  inner query's `String[1]` (multiplicity failure BEFORE any arm),
  and (b) the `equalJsonStrings`/`parseJSON` natives the loaded
  bodies reference. Both are engine-parity platform work, not
  verdict-construction.
- **Remaining non-partition declines (~1,512)**: resolver
  class-query-under (~610) + getAll shapes (~175) — resolver legs;
  assertEquals tabular/flat-cells conventions (~340); D4 typing
  (above); host-unsupported 30; tail.

## 4Q. TENET CORRECTION (2026-08-28, user catch) — the pure-source
## dependency is EVICTED

**The violation:** slices 1–3b made corpus assert resolution work by
LOADING real legend-pure assert sources (and engine's
testExtension.pure) into the corpus model as library files. That made
the reference implementation a RUNTIME COMPONENT of our platform —
against the project's core premise (legend-lite REPLACES pure/engine;
checkouts are spec and test input only). The error conflated
oracle-as-test-input (corpus/PCT sources — legitimate) with
oracle-as-platform-machinery (the stdlib our model resolves against —
never).

**The correction (this slice):** the assert family is now 47
PLATFORM-OWNED registry natives in Pure.java — every real overload of
assert/assertFalse/assertEquals/assertNotEquals/assertSameElements/
assertSize/assertEq/assertEmpty/assertNotEmpty/assertInstanceOf/
assertIs/assertContains/assertEqWithinTolerance +
assertJsonStringsEqual, signatures copied VERBATIM from the real
`.pure` files and verified (spec by verification, never by loading);
`PlatformTypes.ASSERT_FAMILY_OWNED` suppresses parsed twins loudly
(the existing platform-owned mechanism — the twin-shadowing fear that
justified the library route was already solved by the house design).
DELETED: Corpus.PURE_ROOT/PURE_ASSERTS/TEST_EXTENSION + their loads,
the library-scoped native prune, v7Spell. GUARD:
`Runner.registerLibrarySource` refuses `meta::pure::functions::`
elements outright (`LibraryPlatformNamespaceGuardTest`); tenet
recorded as an INVARIANT in AGENTS.md.

**Verification:** full sweep GREEN with the census IMPROVED (agree
2,637 → 2,650, declined 2,583, disagree 8 unchanged); the scoreboard
change is exactly the 7 testExtension wall rows REVERTING; soft
ceilings exact; inner alarm 0.

## 4S. LEG 0 + LEG 1 LANDING RECORD (2026-08-28)

**LEG 0 EXECUTED** (90be6b6c): lane-classification guard — sqltext 961
/ tdg 123 pin EXACTLY in the corpus runner; h2-exec rescued ≥ 632
floor + unverifiable ≤ 145 shrink-only ceiling (the leg-7 ratchet).

**LEG 1 EXECUTED — the grid canon, the ratified end-state-shaped
design (NOT a host-only arm). Census: agree 2,650 → 3,002 (+352),
declined 2,583 → 2,230 (the FULL 353-row flat-cells wall), disagree
8 → 9 (the +1 adjudicated below). Inner sql-verdict alarm 0,
ulp-policy 7. Scoreboard byte-identical; witnesses 10/10; full chain
GREEN.**

- **The mechanism**: `wrapGridCanon` (CanonicalRenderSql) appends one
  per-ROW canonical text to any TABULAR plan — per-cell PURE-LITERAL
  spellings (LiteralSpelling.literal, the ONE grammar both the grid
  cells and the value peer's literal channel spell), GRID_CELL_SEP
  () joined, NULL cells spelling bare `TDSNull` (disjoint from
  a quoted string by the grammar itself); a string cell carrying the
  reserved separator POISONS its row canon to NULL (counted decline,
  never a mis-split). The executor routes by the DECLARED result
  shape (static, pre-execution); the tabular decode strips + harvests
  the canon column row-aligned. The peer's row canons FRAME from its
  literal-channel element canons chunked by the grid's width —
  framing writes separators only. Judgment: ordered list equality,
  or sorted-list equality under the INCIDENTAL view (cross-row
  shuffles fail — audit 9); host cell lattice (rowTupleMultiset /
  loose pool for sameElements) = the parallel referee; message,
  verdict, and probe from ONE decision point (the 28-row phantom is
  structurally impossible).
- **Fetch rule discovered**: a grid pair fetches BOTH sides in
  DEFINITION order (canonicalOrder=false) — the canonical-order rider
  re-sorts a literal side's VALUES and destroys the row chunking (the
  first witness run caught it; grid-ness is static, so the rule is
  compile-time).
- **Alarm catches, all fixed same-slice** (19 inner disagreements →
  0): (1) ENUM cells decline the byte channel at wrap ("grid-canon:
  enum cell has no literal channel" — the §4M scalar precedent);
  (2) the abstract DATE stamp missed the pure-literal % prefix in
  LiteralSpelling.literal — a real gap in the shared grammar, fixed
  (leaf already claimed DATE); (3) the declared 2-ULP
  dialect-arithmetic policy gets its grid arm (positional cell gate,
  counted sqlUlpPolicy — 7 rows).
- **The +1 outer disagreement is ADJUDICATED as a grid-form member of
  the NAMED temporal subsecond wire-decode class** (§4N row 2;
  witness `[%2014-12-05T21:00:00.000000, 5]` — the same value row the
  flat-cells attempt census carried). Burns with leg 5's decode fix.
- Registers moved with justification: JavaEvalLedger AssertVerdicts
  1240→1525 + StatementExecutor 2472→2482; TenetRatchet 13→14 (the
  grid harvest's ONE getString — text carriage); CodeShape allowlist
  CanonRider.gridWidth.
- **Traps recorded**: the battery shorthand "CodeShape" names NO test
  class — the guard is `CodeShapeGuardrailTest`, and a -Dtest name
  that matches nothing passes SILENTLY (two chain iterations lost);
  measure the ledger AFTER the slice's last edit (a post-measure
  3-line edit tripped a chain); allgates REQUIRES the
  LEGEND_ENGINE_ROOT/LEGEND_PURE_ROOT env (bare launch = stale-root
  phantom failures across 5 gates).

## 4T. LEGS 2+3 LANDING RECORD (2026-08-28) — Result<T|m> ENGINE-
## VERBATIM + the JSON natives; a misdiagnosis caught by the user

**Census: agree 3,002 → 3,161 (+159), declined 2,230 → 2,071 (the
JSON family adjudicates), disagree 9 unchanged, inner alarm 0,
h2-exec counters IDENTICAL (320/632/0/145). Full chain GREEN.**

- **Leg 2 — the engine's `Result<T|m>` (result.pure:17), spelled
  VERBATIM and solved by the GENERAL machinery**: `execute<T|m>
  (f:Function<{->T[m]}>…):Result<T|m>[1]` (router_entry.pure's own
  shape). New capability, general: class-level MULTIPLICITY
  parameters — GenericType carries multiplicity arguments
  (TypeClassifier captures the `|m`/`|1` spellings the parser always
  preserved), the kernel resolves them through the same bindings the
  if/let machinery fills, and parameterized-receiver property access
  instantiates `values:T[m]` positionally (mults-omitted receivers
  keep the pre-leg [*]; over-supply throws). A serialize execute's
  `.values` types `String[1]` and the strict JSON signature accepts.
- **Leg 3**: `parseJSON`/`equalJsonStrings`/`JSONElement` registered
  verbatim from core_functions_json/json.pure (§4Q pattern); the
  JSON-assert side reader accepts a GRAPH result (the DB-built
  envelope IS the String[1] document). JSON tail now 4 rows (2
  strict-parse, 2 toPrettyJSONString) — leg 6.
- **THE PROCESS RECORD (user catch — "we need to support the correct
  signatures")**: the first signature attempt was retreated to a
  call-site fix-up on a MISDIAGNOSIS: the sweep's per-test FAIL lines
  were read as regressions without checking the COMMITTED scoreboard
  — all 7 "regressed plan goldens" were pre-existing FAIL rows that
  print on every sweep. The real attempt-1 damage was the then-
  incomplete instantiation arm THROWING on 1-arg Result receivers.
  With the machinery completed, the engine spelling passes every
  gate with ZERO h2/plan movement; the fix-up seam is DELETED.
  **RULE: before attributing a sweep FAIL row to the change under
  test, grep the committed scoreboard for it.**

## 4U. LEG 4 CENSUS SPLIT LANDING RECORD (2026-08-28)

**The 513-row "class query under TypedUserCall" bucket was ENTIRELY
sql-text family.** Two diagnostics: the resolver wall names its
wrapper CALLEE, and v7DualChannel classifies by CONTENT (an assert
whose args pull the sqlQueryToString-family vocabulary — the
harness's own containsSqlText — is a plan-text compare whatever its
form name; §2 by definition). Result: sqltext partition 961 → 1,529
(+568), agree/disagree/declined-total EXACTLY unchanged (3,161/9/
2,071 — the movement was entirely within the declined column; a
split, not a burn). Lane-guard pin moved with this table. Leg 4's
REAL remainder: getAll shapes ~175, TypedMap wrappers 63, pkOfFunc
FunctionDefinition-vs-Function typing 43, metamodel-fn lowering gaps
~50, tail.

## 8. PLAN OF ATTACK — the batch-2 remainder → cutover (handoff,
## 2026-08-28)

**State on entry**: batches 1 + 2.1–2.3b + the §4Q eviction are
EXECUTED and pushed (..ec9f6fe8). Census: agree 2,650 / disagree 8 /
declined 2,583 (partition NAMED: sqltext 961 + tdg 123). Scoreboard
byte-identical throughout. The census console is the work list:

```
mvn -pl core test -Dtest=RelationalCorpusRunner \
  -Dlegend.engine.root=/Users/neemsandv/legend/legend-engine \
  -Dlegend.pure.root=/Users/neemsandv/legend/legend-pure
# read the [v7] lines; scoped probes: -Drcorpus.only=<family>
# (scoped runs never write the scoreboard)
```

## 8.0 SCOPE TABLE — the ratified denominator (2026-08-28, user
## sign-off; measured from the baseline full sweep at 9958c040)

Plain reading: an "assert execution" is one assert statement judged
once during a full corpus sweep. Of **5,241** total:

| bucket | count | plan |
|---|---|---|
| sql/plan-text compares (assertSameSQL family + CONTENT-classified sqlQueryToString-family args, §4U census split) | 1,529 | **OUT of the migration by design, permanently.** Already end-state: text match → H2 row-check (320, 0 diverged); text differs → engine's golden SQL executes on H2, rows vs our rows (632 verified, 0 diverged); unverifiable → advisory, counted by reason (145 — LEG 8 burns these) |
| TDG/test-data-gen text compares | 123 | OUT by design, permanently (host artifacts) |
| host-unsupported forms | 28 | name-by-name adjudication (leg 6) |
| **adjudicating through the production verdict path today** | **2,658** | 2,650 agree + 8 named wire-fidelity disagreements (leg 5) |
| resolver: class query under wrapper | 63 (was 614 — §4U: 513 were sql-text content, 33+30 TypedMap remain) | leg 4 |
| resolver: getAll/call shapes | ~175 | leg 4 |
| flat-cells / tabular sides | 353 | leg 1 (grid canon — fusion-spike F2 proved the SQL) |
| JSON family typing | 163 | legs 2–3 (arm exists; Result<T\|m> typing + 2 natives) |
| lowering gaps + tail | 166 | leg 6 |

Cutover acceptance re-stated against this denominator: disagree 0 and
declines == exactly the two BY-DESIGN rows (961 + 123) + adjudicated
residue. RATIFIED DESIGN for every remaining leg (fusion spike,
[V12_FUSION_SPIKE_2026_08_28.md](V12_FUSION_SPIKE_2026_08_28.md), user
sign-off across four rounds): comparison policy chosen at COMPILE TIME
from static types; host-executed today, emittable tomorrow as ONE
statement per test body (lets = `WITH ... AS MATERIALIZED`, asserts =
plain verdict columns, evidence side-tagged in the same result set;
first-failure = diagnostics not semantics — split rung is the
error-diagnosis fallback only; JSON rides the byte channel via
canonical sorted-key EMISSION; literals always inline — no
unspellable class, stringLit splices chr(0)).

**Leg order (each leg: witness → implement → full sweep → guardrail
battery → allgates → push):**

0. **Lane-classification guard (with the scope table, this slice).**
   The sqltext/tdg partition counts pin EXACTLY in the corpus runner
   (shrink-or-justify), and the h2-exec `diverged` counter pins 0 —
   an assert can never silently change lanes, and a replay divergence
   can never pass silently.

1. **Flat-cells compare (353 declines) — DESIGN SUPERSEDED
   2026-08-28 (user ratification after the fusion spike): lands as
   the GRID-CANON EXTENSION of `wrapWithCanon`, NOT a host-only arm.**
   `wrapWithCanon`'s 1-column decline is the whole blocker (spike F2);
   the extension: per-cell leaf spelling (LiteralSpelling) joined by a
   separator no cell can contain (chr(31)) into a per-ROW canon text;
   the row-canon multiset (sorted-list equality) is the byte verdict;
   NULL cells spell the golden's sentinel convention at the canon
   (COALESCE → 'TDSNull', direction preserved at compile time — spike
   R2-3); an explicit `->sort()` side orders by PURE'S TOTAL ORDER
   (kind-rank + typed value), never canon text (spike R2-1). The host
   cell lattice (`GridCompare.rowTupleMultiset`, engine semantics:
   column names OUT, cells row-wise, cross-row shuffles FAIL — audit
   9) stays as the PARALLEL REFEREE, exactly the scalar channel's
   dual-verdict shape. The failure message derives from the SAME
   judgment that failed — the reverted attempt printed the
   byte-verdict text for judgments the byte channel never made (its
   28-row phantom: judgment and message from different lattices with
   the probe unfired; the committed tail couples message⇔probe, the
   arm must too).
   Historical findings (the reverted attempt, still true):
   **ATTEMPTED AND REVERTED 2026-08-28 — findings for the retry
   (an attempt was measured then rolled back at ec9f6fe8; nothing
   half-understood was pushed):**
   - The TYPER COLLAPSES the trailing `TDSRow.values`: the typed side
     for `$r.values.rows.values` is a `rows` PropertyAccess over the
     values read — detect the flat-cells shape as a `rows` read at the
     side root (both property-access and call spellings), NOT as
     values-over-rows.
   - The CANON-RIDER execution changes the RESULT KIND (a rider-free
     probe returned Tabular; the ridered fetch did not) — the arm must
     fetch rider-free and skip the byte channel entirely (decoded
     cells have no canon; count a named decline).
   - A working shape: dedicated arm before the order-view path —
     rider-free evalValue both sides, flatten Tabular to cells (the
     harness Eval convention), ordered exact then rowTupleMultiset
     under INCIDENTAL view; witness pinned ordered/row-swap/cross-row-
     shuffle (shuffle must FAIL; its failure message is PureAsserts'
     "expected:" text, not the word "assert").
   - Measured outcome: declines 2,583 → 2,230 (−353), agree → 2,959,
     sweep GREEN — BUT disagreements 8 → 52. The +44: ~15 TDSNull
     null-cell spelling rows, ~9 order/cohesion variants, and a
     28-row class of "byte-verdict: canonical renders differ (host
     lattice agreed)" whose accounting was NOT understood — full
     witness list preserved in V7_FLATCELLS_ATTEMPT_CENSUS.md (the
     sql-verdict disagree counter stayed 0 while the message claims a
     divergence — reconcile the arm's `equal`-vs-message-lattice flow
     before trusting any of it). DIAGNOSE THE 28 FIRST; do not push
     the leg with an unexplained class.
   Watch: PCT G7/G9 ledger movement and the chB canon residue counts.
2. **Result typing (~175, unlocks the JSON family).** The engine's
   `Result<T|m>`: `execute(q,...).values` types as q's element type
   and multiplicity (a serialize query → `String[1]`); ours stamps
   `[*]`, so strict signatures (assertJsonStringsEqual) reject BEFORE
   any arm. Fix in the TYPER where the execute call's return/values
   read is stamped — engine-parity typing, not verdict work.
3. **Two JSON natives (small; pairs with #2).** `parseJSON` +
   `equalJsonStrings` registry natives (real signatures verbatim —
   engine-core corefunctions; the eviction's §4Q pattern). The
   assertJsonStringsEqual verdict ARM already exists (JsonCompare).
4. **Resolver legs (census-first; the deep one).** class-query-under
   ~610 + getAll shapes ~175. FIRST: group the sweep's decline
   details by wrapper node and split out the sql-text-family rows
   hiding in the 610 (they belong to the §2 partition). THEN
   per-shape StoreResolver arms, biggest bucket first. Large enough
   to charter its own slices.
5. **Wire-fidelity fixes = the 8 disagreements (§4N).** Decimal SCALE
   at emission (X2's doctrine — never re-blur the judge); temporal
   NINE-DIGIT decode (the engine fromSQLTimestamp convention, at the
   wire temporal seam); then the two phantoms (sort-tie order policy,
   TDSNull row-string spelling) — adjudicate with the user if a
   mechanical fix doesn't fall out.
6. **host-unsupported 28 + tail**: name-by-name adjudication (§2 rows
   or feature rows).
7. **H2-replay unverifiable burndown (145)** — user-ratified as its
   own leg 2026-08-28 ("so we actually do that"). The sql-text
   family's row-verification oracle currently declines 145 asserts
   (census by reason: non-tabular result frames 452-class dominant,
   no-root-exec-variable, array-literal dialect gaps, non-lambda
   toSQLString shapes, enum-decoded columns). Each fix converts an
   advisory pass into a ROW-VERIFIED pass; target: unverifiable → 0
   or a named, user-adjudicated residue. Independent of the cutover
   (advisory channel) — schedulable any time.
   **D3 PIN (arch-audit 2026-08-28, user-ratified): the cutover's
   DELETION LIST is an acceptance criterion, not an intention.** The
   dual-referee period's deliberate harness mirror dies with batch 3,
   enumerated: `checkAssert`'s comparison lattice, `goldenEqualScalar`
   + the golden temporal-decode arms, the harness `compare()`/`Eval`
   leniencies, the harness `endsInSort`/`orderView` duplicate, the
   harness rendered-form recognition (its `renderForm` twin), and
   `isFlatCellsRead` — each with its shrink pins moved (dated
   justifications). A batch-3 slice that flips the verdict of record
   WITHOUT this list deleted does not merge.

8. **BATCH 3 — the cutover (one slice, only at disagree 0 and
   declines == §2 partition):** SQL verdict becomes the verdict of
   record; DELETE `checkAssert`'s comparison lattice,
   `goldenEqualScalar`, the golden temporal-decode arms, `compare()`/
   `Eval`'s leniencies (shrink pins move with dated justifications);
   re-anchor 0-assert accounting (27) + softness attribution; the
   dual-verdict alarm stays armed permanently. Acceptance: scoreboard
   IDENTICAL, full chain green, push. Then V12 (fusion) and V13 per
   PROGRAM_MAP; indexOf/substring stays parked behind V13.

**Standing traps for the next session** (all bitten this session):
run the GUARDRAIL BATTERY (JavaEvalLedger, JdbcSurfaceCensus,
CodeShape, ErrorShape, HarnessDiscipline, ArchitectureTest,
NativeFunctionTest's golden catalog) BEFORE launching a chain — six
register/golden bumps tripped chains; ZERO repo writes while a chain
runs (launch only after the slice's last write); roots are -D
properties at the /Users/neemsandv checkouts; the reference-checkout
INVARIANT (AGENTS.md): checkouts feed the thing UNDER test, never the
thing judging — `registerLibrarySource` enforces it.

## 5. Witnesses (before behavior, where possible)

1. Per-form verdict unit witnesses beside AssertVerdictsTest for each
   corpus form it newly exercises (sameElements order key, TDS
   sentinel rows, tolerance, JSON canonical).
2. The dual-channel census itself is the leg's primary witness: batch
   1's disagreement list IS the spec of batch 2.
3. Regression: the 27 zero-assert tests still report 0-asserts; a
   deliberately-broken assert still fails (polarity witness — the
   verdict lane must never rescue a truly failing test).
4. `ExtendCheckerTest`-style pins for the splice: an assert whose
   args read an execute handle adjudicates identically pre/post.

## 6. Traps (recorded now)

- `checkAssert` has MORE THAN ONE call site (`runPerDriverLoop`,
  `AssertLoopForm`, `RuntimeIfForm` re-entries) — batch 1 starts with
  a call-site census; the dual channel must cover every one.
- The G8 fixture scanner adjudicates test-tree string literals —
  no Pure-shaped assert messages in new tests (metamodel-leg trap).
- Full `tools/allgates.sh` per batch, caffeinated, tree FROZEN;
  12-min P0 ceiling; `mvn -o -pl core install` before hand-run pct
  lanes; corpus doc regeneration rides G4 — commit it with the batch.
- The pct/corpus ROOTS are `-D` system properties (env vars IGNORED)
  on every hand-run.
- H2 advisory channel consumes our DuckDB rows — verify the re-route
  leaves its feed intact (it reads results, not assert outcomes).
- **Dual-phase double execution**: batch 1 runs each assert's sides
  TWICE (host path + verdict path; ~+1 s at 0.26 ms/query — fine).
  Consequence: any stateful or nondeterministic assert side (sequence
  reads, unordered limits) surfaces as a PHANTOM disagreement — that
  is the census working, not a verdict-construction bug; adjudicate
  such rows as nondeterminism (order-key or setup fix), don't chase
  the verdict SQL.
- Batches 1-2 must not touch production files' behavior for
  NON-corpus lanes: AssertVerdicts changes ride behind witnesses and
  the PCT suites (G6/G7) pin the existing dual-verdict behavior —
  any [canon]/sql-verdict census movement on the PCT lanes is a
  regression, not progress.

## 7. Out of scope (name them if tempted)

V12 round-trip fusion and V13 whole-function/let-IS-WITH fusion
(sequenced AFTER this leg; V13 reuses this leg's verdict semantics
wholesale). The sql-text/TDG host partition (§2). The
indexOf/substring seam (parked behind V13,
INDEXOF_SUBSTRING_LANE_CENSUS.md §7). Prepared statements (LAST,
standing ruling).
