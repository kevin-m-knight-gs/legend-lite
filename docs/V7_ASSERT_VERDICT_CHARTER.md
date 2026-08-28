# V7 — corpus asserts become SQL verdicts (charter, 2026-08-28)

**Mission.** Retire the corpus harness's private assert-comparison
lattice — the THIRD implementation of pure equality semantics — by
routing corpus assert statements through the production verdict path
(`StatementExecutor` → `AssertVerdicts`): both sides lower into ONE
verdict query, the DATABASE adjudicates, the harness receives a
verdict row. Standing ruling 2026-08-24 (PROGRAM_MAP longer-arc §3):
one leg, no incremental drift, no half-migrated referee; unblocked by
PCT completion 2026-08-28. Phase-0 census with the measured numbers:
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
  full-sweep (0.26 ms each); verdicts fuse sides and return ONE row
  where today full result sets cross JDBC for the Java compare.
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
   dispatch arm additionally routes each assert through
   `AssertVerdicts` on the same connection; host verdict remains of
   record; counters populate. Instrument: per-form
   agree/disagree/declined + a rows-fetched-per-assert histogram (the
   golden-size fact §5-1 of the census). Scoreboard byte-identical BY
   CONSTRUCTION — full chain green.
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

## 7. Out of scope (name them if tempted)

V12 round-trip fusion and V13 whole-function/let-IS-WITH fusion
(sequenced AFTER this leg; V13 reuses this leg's verdict semantics
wholesale). The sql-text/TDG host partition (§2). The
indexOf/substring seam (parked behind V13,
INDEXOF_SUBSTRING_LANE_CENSUS.md §7). Prepared statements (LAST,
standing ruling).
