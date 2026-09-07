# Batch 120 handoff — execution-frame facts ride the from (2026-09-07)

**LANDED on main 2026-09-07 (GATES batch 120): chain GREEN, rosters exact DuckDB 2454 / H2 1866; referee
1591 MATCH / 6 DIVERGED / 21 DECLINED (the paginate sort-tie decline flips run to run — pre-existing).**
The "remaining loss" section below is kept as the record of how it was measured and fixed: the read of
`tryArmExecRead`'s last leg is SPLICED before the zone is derived (the frame's envelope chain is a from).
The executor's record-site read is reduced to the table-rename union; `frameZone` has no env fallback.

## The rule (state it once, then everything follows)

**The from is the only carrier of an execution frame's facts.** A frame's facts are the connection's SQL
post-processors (`ExecutionContext.PostProcessors`: table renames, CTE extraction, the nonExecutable pass),
the connection's time zone, and the execute options (driver-PK today; per call, owed). They are read ONCE by
the one reader (`ContextReading`, now also the home of the post-processor walk moved out of
`SqlPostProcessors`) and bound onto the `TypedFrom` the special forms build (`FromChecker`,
`ExecuteChainAssembly.chain`, `RoutingContext`).

- The executor's frame = the OUTERMOST from of the body it executes (`StatementExecutor.executeTyped`:
  `ExecutionContext.froms(root).get(0)` → `env.withFrame`). `ExecEnv.frame` is a cache of that from, never
  a source. `ExecEnv.postProcessors()` / `timeZone()` read it; the lowering site and `PlanAllocations` use
  them. The table-rename UNION channel (`ExecEnv.tableReplace`, `ExecFrame.tableReplace`, the statement
  union at ~L440) stays as it was.
- Verdict arms build their reads with the producer's bound context: `VerdictQueries.fromWrapped(query,
  mapping, base)`; `FrameFacts.context` (from `StatementExecutor.boundContext(rt, letPrefix, specs)`),
  `legContext(producer, env)` in `SqlTextVerdicts` (adds the nonExecutable pass for a
  toNonExecutableSQLString producer — `underProducerPasses` deleted), `planCtx` in the plan-text arm.
- The referee seeds its in-list temp tables in the READ's from zone (`frameZone(rowsRead, env)` — the
  `env` fallback should go: a read without a from has no connection zone).
- Every reader call under a statement binds the statement's let chase (`reader().bind(v ->
  ExecuteChainAssembly.letBound(v, letPrefix))`): `Compiler.programFacts` (per statement, preceding lets),
  `RoutingContext`, the two toSQLString reads, `boundContext` (letPrefix overload). The chase never
  reaches a variable bound by an enclosing lambda parameter (`ContextReading.scope` marks lambda-bound
  occurrences by identity; `chase` refuses them) — without this the per-statement bind looped on a hook
  lambda's `query` parameter when a let shared the name (StackOverflow in `collectChain`).
- `PostProcessBoundary` (4 thread-locals) DELETED. `PctRenderOption` (thread-local; PCT adapter) still
  standing — part C below.

## The one remaining loss — MEASURED (instrumented scoped run, 2026-09-07)

`meta::relational::tests::query::filter::in::tempTable::testInExecutionWithTempTableForDateTimesWithTz`
(`let result = execute(|Trade.all()->filter(t|$t.settlementDateTime->in([...DateTimes...]))->project(...),
simpleRelationalMapping, testRuntime('US/Arizona'), ext); assertSize(...5); assertSameElements(...);
assertEquals('select ... in (select "temptableforin_4_0"...)', $result->sqlRemoveFormatting())`).

Facts, each from an instrumented run (prints removed again):
- The frame executes with tz=US/Arizona (`executeTyped` root from → `env.frame`); our lowering spells the
  DateTime literals in that zone; `assertSize` and `assertSameElements` PASS (they failed at batch 120's
  first cut, when only the executor's record-site read carried the zone and the assert sides re-planned
  from the statement env).
- The failing verdict is the assertEquals sql-text ROW verdict: golden replay on H2 gives 0 rows, ours 5.
- The referee's temp-table seeding (`inListTemps(..., frameZone(rowsRead, env))`) got zone=null because
  the read has NO from: stack `frameZone:1064 ← rowsLegAndVerdict:1302 ← tryArmExecRead:557 ← tryArm:65`,
  `rowsRead=TypedPropertyAccess froms=0 envtz=null`. That is the LAST return of `tryArmExecRead`
  (SqlTextVerdicts ~L557): `rowsLegAndVerdict(name, golden, ours, textEqual, oracle,
  VerdictQueries.valuesRead(resultArg), null, fm.mapping(), fm.cls(), fm.extentSubset(), letPrefix, specs,
  env, hook, fm.query(), null, Map.of(), pop == null ? List.of() : pop.temps())` — the read is
  `$result.values` over the let-bound Result VARIABLE, not over the frame's chain, so no from is inside it.
  `fm` (FrameFacts) DOES hold the frame's bound context here (`fm.context()`, read off the execute call's
  runtime through `boundContext(rt, letPrefix, specs)`), and `execFrames` holds the ExecFrame whose
  `chain` is the assembled TypedFrom.

The fix must keep the rule (the from is the carrier), so prefer, in order:
1. The read CONTAINS its from: build this leg's read over the frame's chain (the ExecFrame of `$result`,
   spliced the way `hook` splices activities), not over the variable — then `frameZone(rowsRead)` finds it
   and nothing is passed alongside. Check what `hook.apply(VerdictQueries.activitiesRead(resultArg), …)`
   yields here (frameMappingAndClass uses exactly that to find the execute call).
2. Only if (1) is blocked: wrap with `fromWrapped(valuesRead(resultArg), fm.mappingRef(), fm.context())`
   — but `fm.mappingRef()` may be null on this path (the code deliberately does not wrap) and a from with a
   mapping changes routing of the values read; measure with the roster before accepting.
Never: passing the zone as a side parameter from `env` (that is the thread-local again by another name —
the `env` fallback in `frameZone` is to be deleted).

## Reader call-site inventory (measured 2026-09-07; every read must bind the statement's let chase)

| Site | Binds? | Note |
|---|---|---|
| ExecuteChainAssembly.chain ~L490 | yes (`letBound(v, letPrefix)`) | binds the TypedFrom of every execute chain |
| FromChecker ~L83 | no bind; fnBody/canon/dbOfCopy | compile-time `from()`: args are refs or synthesized values |
| RoutingContext ~L79 | yes (batch 120: `.bind(bind)`) | was unbound |
| Compiler.programFacts ~L792 | yes (batch 120: preceding lets per statement) | was unbound; this is what looped without lambda scoping |
| StatementExecutor toSQLString reads ~L533/542 | yes (batch 120) | were unbound |
| StatementExecutor.boundContext(rt, specs) ~L1244 | NO | callers L862/L1298 (executionPlan calls) — bind them or switch to the letPrefix overload |
| StatementExecutor.boundContext(rt, letPrefix, specs) | yes | new; used by FrameFacts and the plan-text arm |
| StatementExecutor record site ~L1535 | yes | REDUNDANT with executeTyped except the `tr` union — reduce to the union |
| SqlTextVerdicts.tryArm ~L103 (frameCtx) | yes (batch 120) | was unbound → threw on `$pair1` hooks |
| SqlPostProcessors.reachableRenames | yes (`letBound` param) | now reads through the reader |
| PlanAllocations ~L139 | yes | unchanged |

## Baselines and parked files (on this branch)

- `docs/parked/duckdb-fail-roster-batch119.txt` — the 121-name DuckDB fail roster of main (batch 119) for
  set-difference checks (`comm -13 roster.txt now.txt` = lost). Regenerable in 60s from main.
- Referee outcomes on main: verify 1592 MATCH / 6 DIVERGED / 20 DECLINED; fetch-chain 49; fetch-texts 23;
  plan 28 / 4 DECLINED (printed per lane as `[corpus2] referee-outcome …`).
- `docs/parked/InDbVerdict.java` — item 4's transfer/verdict (unwired; register with JdbcSurfaceCensusTest
  when wired). `docs/parked/batch120_partA.py`, `batch120_partA2.py` — the two patch scripts that produced
  this branch (for reading, not re-running).
- Chain lane outputs are now kept after GREEN runs too: `$TMPDIR/gates-neema.g4.out` / `.g5.out`.

## Still to do in this batch

1. Fix the loss per the rule (the from the arm builds must carry the producer's frame).
2. Delete the executor's record-site read (StatementExecutor ~L1535: keep only the `tr` union derivation
   through the reader; `env.withFrame` there is redundant with `executeTyped`) — the patch was drafted
   ($CLAUDE_JOB_DIR/tmp partA scripts, job 664ac178) but NOT applied.
3. Drop the `env` fallback in `frameZone`.
4. H2 lane (floor 1866), guardrails (JavaEvalLedgerTest registries — `PostProcessBoundary.java` entry
   already removed; ArchitectureTest; HarnessDisciplineTest sort pins; PlatformNamesGuardrailTest ratchet
   72 — the MapperPostProcessor FQN constants MOVED into ContextReading, count must not rise), chain,
   records (GATES batch 120, breakdown, handoff §0, memory), commit named files, push.
5. Part B (finding 3): per-call driver-PK — `ExecEnv.addDriverTablePk` (program-wide,
   `driverTablePkRequested`) → the from's `context().driverTablePk()`; DriverPkAppend's consumers
   (StatementExecutor L418/474/1519/1538/2126, SqlTextVerdicts L128, `evalValue`) read the frame.
6. Part C: `PctRenderOption` → an execute OPTION on the entry (`Compiler.execute` → `StatementExecutor.
   execute`; `QueryService.execute` in core/server; PCT adapter `pct/.../PctExecuteNative.java:159`) and a
   result variant for the rendered TDS text (`ExecutionResult` sealed: Scalar/Collection/Tabular/Graph —
   no exhaustive switch in main; the adapter switches). `JavaEvalLedgerTest:875` names it.
7. Then item 4 (referee in the database; `InDbVerdict.java` parked at ~/.claude/jobs/664ac178/tmp),
   then item 5 (censuses — sized in the batch-119 handoff paragraph).

## Lessons (this batch)

- Sequencing by SIZE instead of importance is wrong (user: "why did we defer the important parts").
- A multi-file patch script must be written to a file, idempotent (git checkout the touched files first),
  and must not write any file before every assertion has passed — one aborted midway and left a moved
  block cut from its source file.
- zsh: an unquoted `$R` holding two `-D` flags is ONE argument → the corpus test assumes no checkout and
  SKIPS (Skipped 1, 0.5s). Pass flags literally.
- Five fixes found by roster diff = probing. State the rule first; the roster then confirms.
