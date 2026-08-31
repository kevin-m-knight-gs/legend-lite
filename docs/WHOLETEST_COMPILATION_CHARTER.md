# WHOLE-TEST COMPILATION — item 1 of the harness-deletion program

Source blueprint: `EMBEDDED_UNION_NAV_HANDOFF_2026_08_31.md` §7 (user
directive: the harness does ONLY discover/provision/run/score — every
other line dies). Item 1 is THE SPINE: compile a whole multi-statement
test function as ONE unit on the platform; the statement walk it
retires is `EngineTestExecutor.run()` (~1,800 test-side lines with its
satellites: ElqSplice, RuntimeIfForm, AssertLoopForm, ExecCallFinder,
wrapper/eta-expansion arms, per-driver loop, let/substitution
machinery).

## The design (flip per test, counted fallback)

1. Per test: attempt the WHOLE post-preamble statement list as one
   zero-arg lambda body through the platform (resolve → type → execute;
   asserts are verdicts ALWAYS — the platform's assert natives judge,
   the harness only scores).
2. On any wall: fall back to the legacy walk and record a CENSUS row
   (reason-bucketed, shrink-only pin). The fallback census is the
   program's burn-down surface; the walk deletes when it hits zero.
3. Containment at every flip: corpus byte-stability except attributed
   movement + disagree pinned EXACT ZERO + the census pin.
4. Migration instrument (dual-run agreement) rides a -D flag like the
   H2 lane; it dies with the cutover (§7 item 7).

## Baseline census (2026-08-31, `-Dll.wholetest.census`, probe in
## `WholeTestCensus.java`; full histogram `target/wholetest-census.txt`)

**2,224 / 2,573 bodies (86%) already TYPE as one unit** (name-resolve +
typeQueryBody). The 349-body fallback tail, bucketed:

| ~count | bucket | note |
|---|---|---|
| 121 | `~col: mapped/aggregate column specifications need an enclosing call` | colspec/lambda destructured through lets — the BIND-ONCE leg: typing must carry let-bound colspecs to their consuming call |
| 65 | unknown function | harness vocabulary: `meta::legend::executeLegendQuery` / `compileLegendGrammar` / `compileLegendValueSpecification` (ElqSplice/clgArm arms today), `generateObjectReferences`, `repeat`, external-format/protocol helpers — each becomes a platform native or a DECLARED fallback family |
| 33 | `generateTestData needs its query lambda and mapping reference INLINE` | relax the TDG special-form checker to see through let bindings (bind-once again) |
| 31 | bare lambda outside call position / non-let intermediates | let-bound lambda literals typed standalone — same bind-once family |
| 8 | `from() argument must be a mapping or runtime reference, got TypedVariable` | let-bound runtime refs |
| ~90 | long tail (Any-typed args through lets, overload misses, …) | mostly downstream of the same let-opacity |

Reading: the tail is dominated by ONE platform gap — statements typed
in isolation lose what the let bound (colspecs, lambdas, mapping and
runtime references). "Bind-once" (let values visible to the typer
across statement boundaries) collapses an estimated 250+ of 349.

## Slice order

1. **LANDED (this commit): probe + charter.** Measurement only,
   flag-gated, zero verdict movement.
2. **Execution flip, simplest cohort**: bodies that type whole AND
   carry no execute-vars/TDG/wrappers — flip to platform execution
   behind the dual-run agreement instrument; score flips when
   agreement is total. Establishes the census pin.
3. **Bind-once typing**: let-bound colspecs/lambdas/mapping-and-runtime
   refs visible across statements (the 250+ collapse). Platform
   (typer) work, gated by the ordinary chain.
4. **Harness-vocabulary FQNs**: executeLegendQuery family as platform
   natives (one router, one evaluator — no bespoke per-FQN entry
   points; they route like any native) or declared fallback families.
5. **TDG inline-args relaxation** rides bind-once.
6. Walk arms delete as their consuming cohorts flip; the census pin
   shrinks monotonically; at zero, `run()`'s walk and its satellites
   delete (§7 item 7 cutover).

## Notes

- 2,573 probed vs 2,575 runnable: 2 bodies return before the probe
  point (preamble-owned lineage arms) — they join the flip at slice 2.
- The probe swallows everything; measurement must never move a verdict
  (sweep stayed exit-0 with the flag on).

## Slice 2 LANDED (2026-08-31): the verdict seam + the dual-run instrument

- **Platform seam** (permanent): `AssertListener` (exec funnel register,
  tenet argument in JavaEvalLedgerTest) — `AssertVerdicts.tryAdjudicate`
  reports each owned verdict to an observer the runner supplies via
  `Compiler.executeResolved(..., listener)`; judgment untouched, rides
  ExecEnv (run-scoped, never a static sink).
- **Instrument** (scaffolding, dies at cutover): `FlipProbe`
  (`-Dll.wholetest.flip`) re-runs each test's whole raw statement list
  through the platform AFTER the legacy walk and compares. Every fact
  from compiled state: re-run safety = `Compiler.hasStatementEffects`
  (the platform's transitive effect scan; effectful bodies excluded —
  they join only at single-execution cutover). Census isolation: the
  probe's duplicate executions are muted from ALL pinned counters
  (SqlTypeCensus.probeSuspend + CanonicalDivergence.muteAll — the
  §4.1 idiom, extended). Unflagged sweep byte-identical.

### Execution-reality census (`target/wholetest-flip-census.txt`)

| count | bucket | reading |
|---|---|---|
(EXACT totals, corrected 2026-08-31 second measure — the first table's
"~110 DISAGREE" summed only the visible top rows; probed=2,438 of the
2,575 runnable this sweep)

| count | bucket | reading |
|---|---|---|
| **687** | agree-pass | the day-one flippable cohort — full per-assert agreement, count-skew ZERO (every multi-assert test's platform event count matched the walk's verified count exactly) |
| 38 | agree-fail | both paths fail — 725 total agreement |
| 65 | cohort-excluded: effectful | re-run unsafe; flip at cutover only |
| 33 | out-of-scope | legacy Unsupported |
| **362** | DISAGREE (legacy pass / platform fail) | 334 golden-TEXT asserts (engine SQL / plan "Sequence" / rewritten-query text) + 13 grid-canon + 5 plain + 10 misc — **~95% POLICY LOCATION, not wrong verdicts**: the walk soft-passes text goldens via its advisory/text-rescue policy while the platform judges strictly. Flip design: the rescue policy moves ABOVE the platform verdict, into the scorer consuming listener events — platform stays spec-exact. Fidelity sub-item: binder freshening (`x` → `_i0`) in query-to-text renders |
| 825 | wall-exec (no scalar lowering 327, uninlined user call 217, TypedMap 120, …) | execution gaps typing never showed — these REORDER the burn |
| 428 | wall-type (the baseline census tail) | bind-once ~250 no longer automatically first |

### Slice 3 (next): score-from-platform for the agree-pass cohort

Flip scoring for the 687 behind a per-test census pin (shrink-only on
the fallback count), with the walk's soft-pass policies (advisory
golden-SQL, text-rescue) re-homed into the listener-event scorer.
Then burn lanes strictly by measured bucket size.
