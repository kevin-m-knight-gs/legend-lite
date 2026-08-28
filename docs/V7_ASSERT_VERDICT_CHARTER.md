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
