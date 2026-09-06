# Harness audit — "if I rebuilt the test harness from scratch, what is the minimum?" (2026-09-06)

User ask (after batch 112): audit everything the harness contains through the lens of
deleting EVERYTHING a test harness should not have; think of it as rebuilding the harness
from scratch with the minimum pieces, because that may be what we should actually do.

Numbers that frame it (last full DuckDB sweep, main e8745ec7c):

| measure | value |
|---|---|
| runnable corpus tests | 2575 |
| PASS on the scoreboard | 2464 (2430 clean + 34 soft; 29 of the soft are assert-free tests) |
| scored by the platform's own verdicts ("flipped") | 2451 |
| fallbacks scored by the legacy walk | 122 |
| tests that PASS only through the walk | **13** (one assert-free twin + 12 the walk's own text interpretation passes) |
| fallbacks failing in BOTH channels | 109 |
| harness code in test roots | 16,653 lines (harness/ 11,900 + rcorpus/ 6,300, incl. tests of the harness) |
| platform code in main that exists for the harness | ≈ 6,000 lines (census/instruments ≈ 2,450; verdict arms ≈ 3,700 — see §3) |
| platform (core/src/main) | 190,973 lines |

## 1. The minimum harness, written as a spec

A test in the engine corpus is a Pure function `<<test.Test>> f(): Boolean[1]` whose body is
statements: lets, `execute(|query, mapping, runtime, ext)`, `executeInDb(sql, conn)`,
`assert*(…)`. Setup is `<<test.BeforePackage>>` functions. Nothing else is needed to run one.
The minimum harness is therefore FIVE pieces, each with one owner:

1. **FIND** — read the corpus `.pure` files, parse them with the platform's parser, list
   the `<<test.Test>>` functions and their package's `<<test.BeforePackage>>` setups; honour
   the engine's own exclusion stereotypes (`ToFix`, `Ignore`, `ExcludeAlloy`). Today:
   `rcorpus/Corpus.java` (file access + model assembly, 234) and `Runner.discoverTests` /
   `testKindOf` / `excludeReasonOf` (~120 lines of Runner). **~350 lines.**
2. **ASSEMBLE** — compile the corpus as ONE model through `Compiler.compileModel` (the
   platform; `compile-once-corpus` landed §8). Today: `Runner.globalModule/globalContext`
   (~90 lines). **~100 lines of harness; the rest is platform.**
3. **SEED + SESSION** — one database session per package (catalog-per-session workspaces
   over one DuckDB), run the package's setup functions THROUGH THE PLATFORM (`executeInDb`
   is a platform native), record every raw SQL the body executes (the platform's
   RawSqlBoundary ledger, needed by the referee). Today: `DuckWorkspaces` (139),
   `Runner.beginFamilySession/endFamilySession/callSetup/replaySeeds` (~250). **~400 lines.**
4. **RUN + JUDGE** — for each test, execute its statement list through the ONE production
   entry (`Compiler.executeResolved` with an `AssertListener`); every `assert*` is a platform
   verdict (`AssertVerdicts` / `PureAsserts` — platform semantics of the assert functions,
   not harness); a golden-text assert (SQL string, plan text) is a platform verdict too: the
   platform's sql-text arm asks the **REFEREE** (the `SqlReplayOracle` SPI) for the golden's
   ROWS and compares rows. Harness side: the loop, the exception → failure bucket, nothing
   else. **~150 lines.**
5. **REFEREE** — the harness's implementation of the oracle SPI: an H2 session seeded by
   replaying the recorded raw-SQL ledger (the engine's own dialect is H2, so the recorded
   DDL/DML replays verbatim), execute the golden SQL or replay the golden PLAN program
   (allocations kept on the oracle as tables, holes filled from bindings), return rows. Row
   COMPARISON is the platform's policy (`TdsCompare`/`JsonCompare`), not the referee's.
   Today: `ReplayOracle` (918), `PlanReplay` (406), `H2ExtensionFunctions` (69), the seed-
   ledger cursor. **~900 lines.**
6. **SCORE** — pass/fail per test, one scoreboard file, ONE shrink-only pin: the named
   roster of failing tests (a new name in it fails the build). **~150 lines.**

**Minimum total ≈ 2,000–2,500 lines** against 16,650 today (and ≈ 2,450 lines of
instruments in main that exist only to be read by the harness). Everything not in this list
is either (a) the second implementation, (b) machinery that exists only because there are
two implementations, (c) a migration instrument, or (d) a compensation for corpus fixtures
that the platform should own or that should be a named failure.

## 2. Inventory — test roots, file by file

Verdicts: **KEEP** (in the minimum), **KEEP/SHRINK** (in the minimum after removing policy that
belongs to the platform), **DELETE-WITH-WALK** (exists only because the walk exists),
**DELETE-INSTRUMENT** (measurement, not running), **MOVE** (belongs to the platform).

| file (lines) | what it is | verdict |
|---|---|---|
| harness/EngineTestExecutor (4102) | THE WALK: a second executor of test bodies — statement walk, `checkAssert` (2425–2860: its own assert semantics), sql-text verify arms (`sqlTextVerify`, `tdgSqlTextVerify`, `tdgChainedVerify`, `h2Upgrade`), temp-table seeds, TDG let arms, driver-pair loops, enum-driver loops, `eval`/`evalScalar` host-side evaluation, the v7 dual-channel probe | **DELETE-WITH-WALK** (the whole file; `run` becomes `Compiler.executeResolved` in the runner) |
| harness/WholeTestFlip (521) | the scoring flip: try the platform, else the walk, count the reason | **DELETE-WITH-WALK** (with no walk there is nothing to flip; the bucket text it records comes from the platform's exception/verdict and moves to the runner's failure record — ~40 lines) |
| harness/FlipProbe (185), WholeTestCensus (92) | dual-run agreement instruments, flag-gated | **DELETE-INSTRUMENT** |
| harness/AssertLedger (317) | per-assert ledger rows + bucket taxonomy for non-clean tests, rendered into docs | **KEEP/SHRINK**: the per-assert verdicts are useful reporting, but they come from the `AssertListener` events already; the bucket taxonomy (`sql-text-assert`, `referee-cannot-replay`, `revisit:`, `decision:`, `wall:`) is documentation vocabulary, ~80 lines |
| harness/TestDataGenForm (309), LineageForm (249), LineageRelationsForm (279), JsonAssertCanon (233), ElqSplice (232), PlanAsserts (201), ExecCallFinder (122), AssertLoopForm (106), RuntimeIfForm (77), TdsEquivalence (53), SqlTextShapes (22) | the walk's RECOGNIZED FORMS: each one is a harness-side reading of a Pure idiom (a TDG test shape, a lineage test shape, a JSON sort canon, `executeLegendQuery` splice, plan-text asserts, `->map(f\|assert(...))` loops, `if(cond, \|assert, \|true)` guards, `assertTdsEquivalent` cell policy). The platform compiles every one of these idioms today (the flipped 2451 prove it). | **DELETE-WITH-WALK**. Verified: `assertTdsEquivalent` is already adjudicated by the platform (`AssertVerdicts`:198–203, the numeric/temporal delta of tdsEquivalent.pure), so `TdsEquivalence` is a pure duplicate. |
| harness/H2Verify (1326) | the referee's COMPARISON POLICY (`compareFrame`, `goldenRowsCompare`, `goldenGraphCompare`, ordered verdicts, enum decode, temporal coercion) + decline census (`decline`, `bucketOf`, `verdict`) + carrier-list flattening | **KEEP/SHRINK hard**: row comparison of oracle rows against ours is ONE policy and it belongs to the platform (`TdsCompare`/`JsonCompare`, main); the referee returns rows. The decline census is an instrument. Estimated residue ~150 lines (H2 detection, raw-row fetch). |
| harness/ReplayOracle (918) | the oracle SESSION (family mirror, seed-ledger cursor + poison, fresh-replay fallback, transactional attempt commit/rollback), the SPI implementation (`verify`, `verifyPlan`, `verifyFetchChain`, `verifyFetchTexts`, `rows`, TDG temp replays) | **KEEP** (the referee). Shrink: `verifyFetch*`/`tdgSqlReplay`/`tdgChainedReplay` are arms shaped for walk-side callers — re-derive from what the platform's sql-text arm actually asks for once the walk is gone (expect ~500 lines). |
| harness/PlanReplay (406) | golden PLAN program replay (allocations, holes, the engine's template helper functions by their published bodies) | **KEEP** (part of the referee). Follow-up recorded in batch 112: the pre-existing scalar-list binding still re-spells fetched cells as text; move it onto the oracle-table form. |
| harness/H2ExtensionFunctions (69) | JDK mirrors of the engine's H2 extension SQL functions | **KEEP** (the oracle must run the engine's SQL) |
| harness/EngineTestExecutorTest (535), SubstitutionParityTest (70) | tests OF the walk and of a harness fold hook | **DELETE-WITH-WALK** |
| rcorpus/RelationalCorpusRunner (3826) | ONE JUnit method `scoreboard()` of ~3,400 lines: the family loop + 41 pinned assertions (fallback/flipped counts, lane counts, M1 floors, canon disagree, decline taxonomy, text-verdict lanes) with 2,634 comment lines of ratchet history + 40 `[rcorpus]` census prints + the scoreboard writers (`docs/RELATIONAL_CORPUS.md`, `_ALL.md`) + baseline readers + engine-suite ordering + class/db name indexing | **REWRITE**: the loop and ONE pin (the failing roster, shrink-only) — ~150 lines. The ratchet history is already in GATES.md and the handoffs. |
| rcorpus/Runner (1983) | discovery (keep), model assembly (keep), session/seed lifecycle (keep), `run0` (the per-test flow: keep ~100 lines), `expandHelperCalls` (β-expands helper calls SO THE WALK CAN SEE ASSERTS — its own comment says the platform reason is obsolete), `noteFixtureSkew`/`parseCreateColumns`/`fixtureKind` (the required-over-nullable and fixture-skew CENSUSES — 782 + 631 witness lines printed per run), `capabilityWall`, `score`, `writeScoreboard` (~130), `textVerdictSnapshot`, timing attribution | **KEEP/SHRINK** to ~600: delete `expandHelperCalls` with the walk; delete the fixture-skew and nullable-slack censuses (instruments — if a fixture skew is real it is a named failure or a platform rule, never a census); keep discovery, assembly, sessions, the loop, one scoreboard writer. |
| rcorpus/Corpus (234), DuckWorkspaces (139) | corpus files + model assembly; catalog-per-session workspaces | **KEEP** |
| rcorpus/LibraryPlatformNamespaceGuardTest (47) | a guard test | keep (it is a test, not harness) |

Net: from 16,653 to roughly 2,500 lines, and the walk's ~7,000 lines (executor + forms + flip +
probes + its tests) go entirely.

## 3. Inventory — main-side code that exists for the harness

The user's second question: "no functions in Compiler.java specialized for the harness".

| item (lines) | what it is | verdict |
|---|---|---|
| `Compiler.executeResolved` ×3 overloads | the one back-half entry (keep) + the listener overload ("the runner's scoring seam") + the registration overload (listener + oracle; "production never calls this arity") | **SHRINK to one**: the entry takes an `ExecEnv`-shaped options record; the listener and the oracle are legitimate production SPIs (a server host also wants to observe assert verdicts and may supply a referee), so they stay as PARAMETERS, not as harness-named overloads. |
| `StatementExecutor.ExecEnv` fields `assertListener`, `replayOracle`, `planRows`, `protocolBody` | the execution environment carries the two SPIs plus `planRows` (rows of plan text for the plan-text verdicts) and the protocol body | keep the SPIs. Verified: `planRows` is the platform's own plan-node row scope (`PlanRows.scopeId`, read at StatementExecutor:582–587 by the plan-text machinery) and `protocolBody` is the pre-resolution body the printer's TDS-vs-Class shape reads — both platform, neither harness plumbing. |
| `AssertVerdicts` (2258), `PureAsserts` (512), `SqlTextVerdicts` (1439) | the platform's semantics of the Pure `assert*` family and of asserts whose subject is engine SQL text (rows are the verdict) | **KEEP** — this is production behaviour of assert functions, and the design the user ratified (asserts are verdicts ALWAYS). Audit note: `AssertVerdicts` at 2,258 lines carries the "splice"/envelope-collapse arms (`ResultEnvelopeSplice` neighbours) that grew per golden; a from-scratch platform assert family would be smaller. Not a harness deletion; a platform simplification leg. |
| `TdsCompare` (577), `JsonCompare` (118) | row/grid and JSON structural comparison policies (main) | **KEEP** as THE one comparison policy; H2Verify's duplicate policy folds into these. |
| `SqlReplayOracle` (161), `AssertListener` (18) | the two SPIs | **KEEP** |
| `CanonicalDivergence` (728, 11 static sinks), `CanonRider` (137), `CanonDeclines` (60), `CanonicalForm` (120) | the v7 "canon"/dual-channel divergence instrument and decline taxonomy register (`[canon]`, `[v7]` lanes, the `lane guard` pins) | **DELETE-INSTRUMENT** (with the walk there is no second channel to diverge from; the taxonomy of declines survives as the referee's decline REASON on the failing roster) |
| `SqlTypeCensus` (1031, 31 static sinks) | the label-lie census of SQL types (typed-IR slice 1 instrument) | **DELETE-INSTRUMENT** (the typed IR landed; if a label lie is possible today it is a typing wall, not a counter) |
| `TimingLedger` (77, 3 sinks), `StampCensus` (203, 1 sink), `SqlTextEmission` (89, 2 sinks; `probeSuspended`), `GridProbe` (82), `PctProbe` (56), `ExecutionTrace` (35), `TestResources` (41) | timing accounting, multiplicity-stamp invariant counter, text-emission census + a probe switch, a LIMIT-0 metadata probe, a PCT probe, the engine's execution-trace stamp, classpath test inputs | `TimingLedger`/`StampCensus`/`SqlTextEmission`: **DELETE-INSTRUMENT** (an invariant is an assertion, not a counter). `GridProbe`: examine — a LIMIT-0 schema read is an execution ingredient (dynamic pivot), likely KEEP. `ExecutionTrace`: KEEP if the engine's result carries it (batch 83 says it does). `TestResources`: **KEEP** — verified a resolver SPI (classpath path → text for the engine's `loadCsvToDbTable` native; the harness registers it exactly as it registers the oracle; unregistered = loud wall). |
| `RawSqlBoundary` (305, 2 sinks), `PostProcessBoundary` (78, 4 sinks), `EngineTextBoundary` (38), `TextGoldens` (34) | thread-scoped recording of the raw SQL a body executes (the referee's seed ledger), the last execute's post-processors, and the engine-text rendering markers | the raw-SQL ledger is REQUIRED by the referee — but it is thread-local STATE in main, the exact "instrument state ride the artifact, never a static sink" violation the memory rule names. **MOVE** the ledger onto the `ExecEnv`/result (the connection session already knows what it executed); the text-channel markers go with the text lanes. |
| 26 `System.getenv(...)` sites in main (TemporalFrame 3, Typer 3, StatementExecutor 3, NavMaterializer 2, ScanRelations 2, TdsCompare 2, Executor 2, …) | env-gated debug prints (`LL_TMP_DEBUG`, `LEGEND_LITE_DUMP_SQL`, `LEGEND_LITE_NAVDATE_TRACE`, `LL_ORD_COUNT`, `LL_TOL_COUNT`, `LL_STAMP_COUNT`, …) | **DELETE** all but one structured trace behind one flag (`ObservabilityGuardrailTest` pins the list shrink-only — collapse it). |
| `PlanText` (≈900), `PlanAllocations` (471), `PlanRows`, `PurePrint`, `PlanNode` | `planToString` — the engine's plan-text spelling | **KEEP as a product feature** (`planToString` is engine API), with the honest note that its shape is golden-driven text spelling; the referee brings it to rows, so it must be executable-faithful, not byte-faithful. |
| native catalog: `JAVA_ROUTINE` rows (`toCSV`, `generateTestData`, `planToString`, `toSQLString` family, `executeInDb`, `assert*`, …) | engine functions the corpus calls, implemented as platform routines | **KEEP** — they are the engine's own functions; a test harness must not implement them, the platform must. |

## 4. Cross-cutting findings

1. **Two implementations of assert semantics.** The walk's `checkAssert` (+ the forms) and the
   platform's `AssertVerdicts`/`PureAsserts`. The 12 walk-only text passes are the walk's
   opinion. Delete the walk; the platform's verdict stands, and a test the platform cannot
   judge is a named failure.
2. **Two row-comparison policies.** `H2Verify.compareFrame/goldenRowsCompare/orderedVerdict`
   (harness) and `TdsCompare`/`JsonCompare` (platform). The referee should return rows; the
   platform compares. One policy.
3. **Instruments outnumber the runner.** 40 `[rcorpus]` census prints, `[canon]`, `[v7]`,
   fixture-skew witnesses (782 lines), required-over-nullable witnesses (631), nullable-slack
   classes (160), the timing ledger, the stamp census, the type census, the emission census.
   None of them runs a test. Each was a migration measurement; the migrations they measured
   have landed or been superseded. With the walk gone, the ratchet is one number.
4. **Static sinks in main.** 14 files carry static mutable state that exists to be read by the
   harness (the memory rule `instrument-state-matches-fact-lifetime`). The only one with a
   production purpose is the raw-SQL ledger (the referee's seed replay), and it should ride
   the session/result, not a `ThreadLocal`.
5. **Harness-shaped compensations in the runner.** `expandHelperCalls` (its own comment: the
   platform reason is obsolete; it survives only so the walk can see asserts inside helpers);
   fixture-skew and nullable-slack handling (`noteFixtureSkew`, `parseCreateColumns`,
   `fixtureKind`): a corpus fixture that declares a column the DDL never created is either
   the platform's rule (a store column absent from the physical table) or a named failure —
   a census of 782 witnesses is neither.
6. **The scoreboard writes documentation.** Two generated files in `docs/` (2,397 lines).
   One scoreboard file with the failing roster is the deliverable.
7. **Cost of deletion, measured.** PASS 2464 → 2451; fallbacks 122 → 122 plain failures
   (plus the assert-free twin becomes "no verdict"). No verdict of the platform changes.

## 5. Recommendation: rebuild, literally, beside the old one

STATUS 2026-09-06 (batch 113): step 1 LANDED — the minimum harness exists beside the old one
(MinimalCorpus, ~600 lines), its roster equals the old runner's platform-scored roster plus the
three walk-only trivial passes, and the helper expansion the old runner did by heuristics is now
the platform's StatementInline. Step 2 (the deletions) is next; it must also converge the
executor's call-frame route into the splice (two reopened tests ride on that).

Because the minimum is ~2,500 lines and the surviving pieces are already separable
(`Corpus`, `DuckWorkspaces`, `ReplayOracle`+`PlanReplay`+`H2ExtensionFunctions`, the
platform SPIs), the cheapest honest path is to WRITE THE MINIMUM NEW rather than carve it out:

1. `rcorpus2/` (name to taste): FIND + ASSEMBLE + SEED + RUN + SCORE from the spec in §1,
   ~700 lines of new code, reusing `Corpus`, `DuckWorkspaces`, `ReplayOracle`, `PlanReplay`.
   Every test runs `Compiler.executeResolved(statements, ctx, runtime, conn, listener,
   ReplayOracle.INSTANCE)` once; a throw is a failure with the exception's first line as its
   reason; assert verdicts come from the listener.
2. Run both harnesses over the corpus; the new one's PASS roster must equal the old
   platform-flipped roster (2451) — the set difference IS the acceptance test of the rebuild
   (expected: exactly the 13 walk-only passes and the assert-free twin differ).
3. Delete: EngineTestExecutor, WholeTestFlip, FlipProbe, WholeTestCensus, the eleven forms,
   the two walk tests, RelationalCorpusRunner (replaced), Runner's walk-side arms, the
   `[rcorpus]`/`[canon]`/`[v7]` censuses; then in main: CanonicalDivergence, CanonRider,
   CanonDeclines, CanonicalForm, SqlTypeCensus, TimingLedger, StampCensus, SqlTextEmission,
   the env prints, the harness-named `executeResolved` overloads; move the raw-SQL ledger off
   its ThreadLocal; fold H2Verify's comparison policy into TdsCompare.
4. Replace the 41 pins with one shrink-only failing roster and delete the lane/canon/M1
   guards from the guardrail tests (`CanonDeclineTaxonomyTest`, `VerdictChannelRegisterTest`,
   `SkipCensusTest`, `TenetRatchetTest`'s harness rows — examine each).
5. Only then the NavPath cleanup (memory `string-hacking-audit-navigation-paths`): the
   resolver's fixes are then judged by one implementation.

Order of magnitude: the rebuild ~1 session; the deletions and main-side moves ~1–2 sessions
(each deletion is gated by the chain; the guardrail tests that pin the old shapes are the
part that costs).

## 6. What is NOT harness and must not be deleted under this lens

- `AssertVerdicts`/`PureAsserts`/`SqlTextVerdicts` (assert functions are platform semantics).
- The two SPIs (`AssertListener`, `SqlReplayOracle`).
- The referee (`ReplayOracle`, `PlanReplay`, `H2ExtensionFunctions`): golden text → rows is
  the ratified verdict for every engine golden, and the oracle is a reference database, which
  is a test-time dependency by nature.
- `planToString`/`toSQLString` and the other `JAVA_ROUTINE` engine functions: the corpus
  calls them because the ENGINE has them.
- The 122 named failures and their buckets (IMPL/TEXT/ENGINE/OTHER/NAMED/parked/revisit) —
  they are the burn list, unchanged by any of this; only their bookkeeping shrinks.
