# The result boundary program: start here (rewritten 2026-10-28)

For a session picking this up with no context, from any account. This page is the whole roadmap: what the program is,
exactly where it stands, every remaining piece of work in order with a brief for each, how we work, how to run every
check, how to raise the lanes that run the other databases, the traps already hit, and what was never in the
repository. Its evidence (the audit, the maps of the code, the measurements, every probe's source) is
`docs/result-boundary-homework/README.md`; its rulings are the program, `docs/RESULT_BOUNDARY_PROGRAM_2026_10_26.md`
§8. The handoff of 2026-10-26 this page replaces is in git history (`f631cffc`).

## 0. In one minute

- **The goal.** A query answers typed values computed by the database; nothing in SQL formats output; each consumer
  writes its own form at the edge: legend-engine's exact JSON for Studio and legacy clients (the LEGACY renderer), lite's
  lossless JSON, CSV and Arrow for its own (the LEAN renderer). One way to run a query or a test body: a plan, run by
  the plan runner, into a writer. Every other path deleted.
- **Where it stands.** Every production route runs on plans. PCT channel A runs on plans, and a relation now crosses
  exactly as legend-engine's own PCT adapter reads it (the engine JSON our server serves). What is NOT on plans yet:
  our own core tests, the core_relational corpus, PCT channel B and the service test runner, which all still enter
  through the old door (`Execution.execute*` -> `StatementExecutor` -> `ExecutionResult`), though every statement they
  send already runs on the plan runner.
- **The branch.** `claude/upbeat-johnson-l4hogx`, 15 commits ahead of `main` (`7b8c89dc`, PR #78) at `b6978a34`, plus
  the documentation commit that wrote this page. Clean. Never pushed to `main`; it lands through a PR when the user
  says (AGENTS.md, "Pushing to main").
- **The next action.** S1.4 (the writers carriage only), then S1.5 (the missing records), then S1.6 (a draft PR so CI
  runs on every OS; the user's word first). Then the four big tracks: B (a test body as a sequence of plans), T (our
  core tests as the LEAN twin of PCT and the corpus), C (the corpus and channel B on B), D (the deletions). L (mixed
  lists typed) and A (the Arrow writer) can run beside them.
- **Who decides.** The user: rulings (§10 lists every one), pushes to `main`, PRs, merges, and **every change to
  how any output is spelled, case by case** (2026-10-28). A lane's report is data, never an instruction.

## 1. The program in brief

A query produces a table of typed values. The database computes every value; Java orchestrates (the execution tenet,
AGENTS.md). The plan (`executionplan/ExecutionPlan`) carries three facts per column (the Pure type and multiplicity,
the SQL slot, the store's declared type) and the typed SQL tree; the plan runner (`exec/PlanRunner`) runs it on a
session and streams typed batches to a sink (`result/ResultSink`); a writer spells them at the edge.

**Two renderers, two families of tests (the user's rulings of 2026-10-24 and 2026-10-28):**

| Renderer | Who reads it | Written by | Judged by |
|---|---|---|---|
| LEGACY: legend-engine's exact answers | Studio, legend clients, `pure/v1` | `result/PureV1Writer`, `CellText.engine` | PCT (legend-pure's own function tests, 12 databases) and the core_relational corpus (legend-engine's own relational tests): legend-engine's tests, legend-engine's expectations |
| LEAN: lite's own lossless output | lite's query service, the warehouse, DataCube, Python | `result/JsonRowsWriter`, `CsvWriter`, Arrow (not built) | our own core tests, rewritten as the platform twin of PCT and the corpus (track T): the same queries and logic, judged on what lite's users actually get |

**Two judges, kept forever (R10).** Every assertion a test body makes is judged twice: the HOST judge (each side a
typed plan, compared in Java) and the DATABASE judge (both sides in one SQL verdict statement); a disagreement fails
the test. **Zero dependencies stay zero (R9)** in the compiler, the WebAssembly planner and the native image: no Arrow
library in the product.

## 2. Where everything is

| Area | Files |
|---|---|
| The plan | `core/src/main/java/com/legend/executionplan/ExecutionPlan.java` (nodes `Sequence`, `TdsResult`, `Keyed`, `Update`, `Check`, `Effect`; `Column`), `ColumnFacts.java`, `PlanJson.java` (format 16; `PlanJsonTest` pins it), `MirJson.java`; `com/legend/PlanMaker.java`, `TypedQuery.java` (`executionPlan(...)`), `plan/QueryPlan.java`, `plan/UpstreamRelationType.java` (legend-engine's spellings of a column) |
| Running a plan | `exec/PlanRunner.java` (`stream`, `rows`, `update`, `effect`), `exec/JdbcStatements.java` (the JDBC boundary; stamps the trace comment), `exec/JdbcDecode.java` (a JDBC cell by the plan's facts; `Driver` holds the drivers' quirks as facts), `exec/PlanSessions.java`, `exec/NonFiniteJson.java`, `core/src/main/mongodb/.../MongoDbDecode.java` |
| The result and the writers | `result/`: `ResultSink`, `Batch`, `Batcher`, `Collector` (collect into memory), `RowDecode` (a cell from a JSON row), `HostRows` (rows a host ran), `CellText` (every cell spelling: Pure's print forms, legend-engine's tokens, lite's), `PureV1Writer` (LEGACY), `JsonRowsWriter` and `CsvWriter` (LEAN) |
| The production entry | `Execution.run(...)` (plan, run, stream to the sink a caller makes from the plan); `server/PureV1Api.java` (`engineWriter`, `jsonPlan`, `arrowPlan`, `execute`), `server/QueryService.java` (`executeUpstream`, and the streaming `execute(..., OutputStream, OutputFormat)`) |
| The OLD door (goes in track D) | `Execution.execute*` and `executeResolved` -> `StatementExecutor.java` (3,270 lines: the body driver) -> `BodyCompiler.java` -> `exec/ExecutionResult.java` (sealed, 152 lines); `QueryService`'s three materialized `execute` methods; `test/PureTestRunner.java`, `test/ServiceTestRunner.java` |
| The judges | `HostJudge`, `DatabaseJudge`, `AgreementArm`, `AssertVerdicts`, `VerdictArm`, `JudgePlans` (each side a plan), `SqlTextVerdicts`, `exec/VerdictBatch`, `exec/TdsCompare`, `lowering/CanonicalRenderSql` (the database judge's canon; stays) |
| The PCT harness | `pct/src/test/java/org/finos/legend/lite/pct/extension/`: `PctExecuteNative` (the native the reference calls), `PctPlanRoute` (plan, run; a relation through `PureV1Api.engineWriter`), `ValueBridge` (`engineTDS` for a relation, `values` for a value), `PctBackend` (the lane's sessions, `LEGENDLITE_PCT_BACKEND`); `pct/src/main/resources/core_legend_lite_pct/pct_adapter.pure` (the Pure adapter; `engineTDS` = legend-engine's `getTDSResultFromProtocol`), `pct_types.pure`; `pct/.../channelb/ChannelB.java` (channel B); the ledgers `Test_LegendLite_<Lane>_<Suite>_PCT.java` |
| The corpus | `spec/src/test/java/com/legend/rcorpus/` (`MinimalCorpus`, `JudgeLedger`), its rosters `spec/src/test/resources/rcorpus/`, the referee `spec/src/test/java/com/legend/harness/ReplayReferee.java`, `RefereeVerify.java` |
| What the twin (track T) changes | `core/src/test/java/com/legend/integration/AbstractDatabaseTest.java` (28 subclasses), and the other helpers on the old door (`CheckerResults.collect`, `SqlParity`, `CypherParity`, `IqParity`, `PlanCases`, `testing/Rows.java`); the census `docs/result-boundary-homework/test-census-2026-10-28.tsv` |
| Mixed values (track L) | `sql/SqlExpr.java` and `SqlType.java` (`Tagged`), `lowering/MixedEncoding.java` (`rankedElems`), `lowering/Scalars.java`, `lowering/Lowerer.java`, `lowering/Fold.java` (`conformValueEgress`), `lowering/LiteralSpelling.java`, `LiteralText`, the `LITERAL` slot (94 sites), `exec/TaggedSelectionTest` |
| The Arrow framer (track A) | `warehouse/src/main/java/com/legend/warehouse/server/duck/ArrowStreams.java` and `FlatBuilder` (written by hand, no library), its judge `warehouse/src/test/python/arrow_matches_json.py` |
| legend-engine's answers, recorded | `core/src/test/resources/upstream-api/` (e1, e5, e8, e9 answers; the Variant probe's `e8-variant-*.json` and `variant-probe.pure`), judged by `server/PureV1ApiTest` |
| The engine itself, runnable | `tools/engine-runner` (legend-engine 4.145.0's jars, `@maven_runner`); how to ask it anything: the homework README §7 |
| The guards that pin this line | `JavaEvalLedgerTest` (exact line pins, the exec register), `ArchitectureTest`, `CodeShapeGuardrailTest`, `TenetRatchetTest`, `DialectLeakRatchetTest` with `DatabaseOwners` (no database named outside its owners), `ParkedWorkLedgerTest`, `NoSaltedIterationOrderTest` |
| Documents | this page; the homework folder; the program (§8 rulings, §9 status); its design `docs/RESULTS_AND_RENDERERS_2026_10_22.md`; `docs/PLAN_AND_RESULT_MODEL_2026_10_18.md`; `docs/PLAN_PATH_PROGRAM_2026_10_17.md` (§5 lets, §6 judges, §7 steps 8 to 11 are tracks B, C, D here); `docs/SEMANTICS_REGISTER.md`; `docs/GATES.md`; `docs/PARKED_WORK_LEDGER.md`; `docs/CLOUD_BACKENDS_PCT_2026_10_11.md`; `docs/CLOUD_SESSIONS.md` |

## 3. Where it stands (2026-10-28)

**The program's steps, the phases that did them, and the commits** (the phases are the user's 2026-10-27 execution
order; the steps are the program's §5):

| Program step | Phase / stage item | Commits | State |
|---|---|---|---|
| 0. Measure | (the clouds' M5 to M8, 2026-10-26) | GATES 2026-10-26 | half: M1 to M4 never recorded; the lanes' per-test baselines of S1.1 replaced them as the gate |
| 1. Typed results, the batch stream | phase 1 | `51688f36` | done |
| 2. The shared Arrow framer | -- | -- | not started (track A) |
| 3. The legacy writer in Java; the SQL-written wires deleted | phases 2, 3 | `12b89c7d`, `1b0305f7`, `53cf5b6d` | done |
| 4. PCT from typed rows; the TDS text deleted | phase 4; S1.3 | `b3311cd6`; `ac4bb7d3`, `6811256b`, `4eaf1b07`, `d8ab8434` | done: a relation crosses as legend-engine's adapter reads it |
| 5. The lean writers | phase 2 (JSON, CSV) | `12b89c7d` | JSON and CSV done; Arrow not (track A); `conformValueEgress` not deleted (track L) |
| 6. Mixed lists typed | -- | -- | not started (track L) |
| 7. The old executor goes | phase 5; S1.2 | `0fe63b42`, `f883d42b` | half: `exec.Executor` deleted; the old door, `StatementExecutor` and `ExecutionResult` remain (tracks B, T, C, D) |
| 8. Optional deletions | -- | -- | parked for the user (the engine-style SQL-text dialects) |

**The audit's three stages** (2026-10-27, accepted with "keep it, and keep going"): stage 1 S1.1 to S1.3 done, S1.4 to
S1.6 next (§4); stage 2 is tracks B, C and D; stage 3 is tracks L and A and the final audit (Z). The audit itself and
what it found: the homework README §1.

**The last measurements.**
- `f883d42b` (phase 5 green), six lane sessions against `7b8c89dc`, per test: channel A exact on Postgres, SQLite,
  MariaDB, MySQL, ClickHouse, Trino, H2, BigQuery, Databricks and Snowflake; channel B identical (one MySQL gain); one
  pin open (Snowflake's `testAdjustByMinutesBigNumber`, a driver limit).
- `e8c341d0`, the engine crossing (channel A, every suite, every lane): exact everywhere; each lane's log proves the
  route (460 or 459 relation crossings, 64 standard). Switched at `d8ab8434`.
- Locally at `d8ab8434`: PCT relation and standard on DuckDB, SQLite and MongoDB, `//pct:pct_discipline`,
  `//core:census`, `//core:guardrails`; at `4eaf1b07`/`0b06545e`: `//core:core_tests_server`, `//core:core_tests_root`,
  `//datacube:python_execute_test`. **Not run since phase 5: the local gate, CI on any OS.**

**Who still enters through the old door** (measured 2026-10-28; production: nobody):

| Caller | Where |
|---|---|
| the core_relational corpus | `MinimalCorpus` -> `PureTestRunner` -> `Execution.executeResolved` (bodies and setups) |
| PCT channel B | `ChannelB.java` -> `Execution.executeResolved(..., JudgeMode.BOTH)` |
| the service test runner | `ServiceTestRunner.runOne` -> `Execution.executeResolved`; `serialize(ExecutionResult, ...)` |
| the corpus referee | `ReplayReferee`, `RefereeVerify` read `ExecutionResult` |
| core's tests | 140 files, 2,304 test methods (1,450 assert values), 609 direct call sites; most through `AbstractDatabaseTest` |
| main code naming `ExecutionResult` | 25 files: the judges, `StatementExecutor`, `BodyCompiler`, `PlanEnvelope`, `CsvLoad`, `SeedSqlForms`, `LiteralFold`, `AssertErrorNative`, `TestDataGenerationNatives.planTextResult`, `exec/VerdictBatch`, `exec/SqlReplayReferee`, `exec/TdsCompare`, `plan/ResultShape`, `server/QueryService` |

## 4. The roadmap, in order

Dependencies: S1.4 -> S1.5 -> S1.6, first. Then B -> C -> D (D needs T as well). T can start right after S1.6: of its
1,450 value tests only 20 use a `let` (up to 145 hold a `;`, mostly inside data strings: check those per file), so
nearly all are single queries that need nothing from B. L, A and F can run beside the others. Z last.
Every item follows §5's rules: announce in `docs/IN_FLIGHT.md`, measure first, run both, switch, delete in the same
step, records with the code, every lane per test.

### Stage 1, finish

**S1.4 The writers carry, never decide.** Why: rule R3; the audit found the writers comparing values and choosing
spellings by rules, unguarded. Build:
1. One typed fact on the plan column for a temporal value's written precision, replacing the slot-name strings
   (`"TEMPORAL_TEXT"`, `"LITERAL"`) matched in `exec/JdbcDecode`, `result/RowDecode`, `result/CellText` and
   `MongoDbDecode`: a field of `ExecutionPlan.Column` (a plan format bump; `PlanJsonTest`, `MirJson`), set by
   `ColumnFacts`, read by the decoders and the writers.
2. `-0.0`: **ruled and built 2026-10-28** (the user's "okay"; register S114). Lite's own outputs (LEAN JSON and
   CSV) write a negative zero as `-0.0`, the shortest text that reads back as the same double (the Float rule of
   2026-10-26), as the LEGACY writer and Arrow carry it; Pure's `toString` of a Float stays `0.0` for either sign, as
   legend-pure's own and lite's SQL (`FloatCanonText.ofText`) write it. `CellText.pureFloat` has no zero branch now.
   **Open, for the user:** the same function writes a double Java prints in exponent form below `1e-3` with the
   exponent's trailing zero (`1.0E-4` as `0.00010`, `1.0E-5` as `0.000010`: `new BigDecimal("1.0E-4")` keeps scale 5);
   legend-pure prints `0.0001`, and the Float rule's shortest digits are `0.0001`. Found 2026-10-28, measured in a
   scratch program, never fixed: a spelling change, so it goes to the user before any code.
3. The two guards the program names (R3), **approved by the user 2026-10-28** ("yes add this to the plan", after the
   explanation below), built right after item 1 so the precision fact removes the slot-word matches they would flag:
   `ArchitectureTest.writersAreCarriageOnly`, a dependency rule (the `result` package reads the plan's column facts
   and the typed batch, and no `compiler`, `lowering`, `sql` or `exec` class: true today, the rule keeps it true), and
   `WriterCarriageGuardTest`, a source scan of `result/` for arithmetic and comparison on numbers and dates, zero
   allowed, with representation operations (a scale set without rounding, a decimal's plain digits) listed by name.
   What the scan finds today, each to be fixed by a typed fact or put to the user: `dateOnly`'s `midnight(d)` (an
   abstract Date at midnight printed as its day: the writer guessing the written precision a typed fact should carry,
   item 1); `engineDay`'s and `dateOnly`'s `atLeast(HOUR)` and slot
   words compared as strings; `stripTrailingZeros` on a Float held as a decimal. Also the `result` package as a closed
   register in `JavaEvalLedgerTest`, like `exec`. What they are for, as put to the user: R3 says a writer only spells
   the typed value it was handed, so swapping one writer for another changes no value; a writer that compares or
   computes a value is deciding output the plan never typed, and nothing would show it but a judge that happens to
   cover the case (the midnight rule `6811256b` fixed was caught by PCT only by luck of a fixture). Limits: the scan
   catches drift, not a determined workaround, like every guard here. An allowlist entry moves only with a dated
   justification (AGENTS.md, "Guard files are load-bearing").
4. The phase 3 audit's nits (homework §9); any that changes an output's spelling goes to the user first.
Gate: `//core:core_tests`, the guards, PCT on DuckDB, SQLite, MongoDB, `//datacube:python_execute_test`; the lanes
channel A. Trap: a guard that rejects the change is usually right.

**S1.5 The missing records.** GATES entries for phases 1 to 4 (each commit's built, deleted, measured; the lanes' S1.1
numbers are in GATES 2026-10-27's phase 5 entry, item 3); the 51 writer differences
(`docs/result-boundary-homework/wire-differences-2026-10-27.tsv`): classify each a correction or a spelling change, and
put every spelling change to the user case by case (the ruling of 2026-10-28), with its expression, the old output and
the new; the user's answer decides each (keep it with a register row, or revert it in code); register rows for
the PCT message changes (`testBigFloatAbs`'s digits, the NaN refusals of `testArcCosineError`/`testArcSineError`); the
program's §3 table brought up to date.

**S1.6 A draft PR, with the user's word.** So CI runs on every OS (the local gate covers neither Windows, arm, macOS,
the corpus lanes nor the product build: the 2026-10-26 incident). Template `.github/pull_request_template.md`. First run
`bazel test --lockfile_mode=error //gates:local` (Postgres targets unprivileged, §6). Drive CI to green; the user merges.

### Track B. A test body is a sequence of plans

Why: the corpus, channel B, the service tests and the 20 `let` tests need one entry for a whole body; it is what lets
`StatementExecutor` stop sending anything itself and `ExecutionResult` leave main. It is the plan path program's step 8
(`docs/PLAN_PATH_PROGRAM_2026_10_17.md` §7) and the end state's item 3 (homework README §6).

What exists: the plan nodes `Sequence`, `Update`, `Effect`, `Check`, `TdsResult`, `Keyed`; `PlanRunner.update`/`effect`
(phase 5: every effect of a body is already a plan node run on the body's session); `JudgePlans` (each judge side and
each verdict statement already a plan); the `AssertListener` verdict channel.

Build:
1. The body driver sends nothing itself: the three schema probes become plan steps through the session seam; a guard
   forbids `StatementExecutor`/`BodyCompiler` from `java.sql` (`ArchitectureTest`).
2. One entry for a program: `Execution.runProgram(...)` (name it) plans a body (lets per the plan path program §5: in
   the database or nowhere), runs it, reports every verdict to the listener, returns nothing materialized.
3. The judges take typed sides (collected typed rows plus a shape) instead of `ExecutionResult`; the host judge's
   `Equality.ordered` unchanged.
4. Put to the user before building: what the body driver keeps in Java (call frames, `assertError`, `$plan` navigation,
   `print`, the test-data generator; homework README §6 lists every kind with its size).
Gate: the corpus rosters unchanged (`//spec:judge_lanes`), channel B identical per test on every lane, PCT exact.

### Track T. Our core tests become the LEAN twin of PCT and the corpus

The user's ruling, 2026-10-28: "i would prefer not to delete the overlapping tests but use the exact same logic but
with platform output that we think users would actually run/use instead of backwards compat mode - so its almost the
platform twin of PCT/core-relational". Nothing is deleted; the same queries and logic are judged on lite's own output.

Build:
1. A test helper `lean(model, query)` (and its runtime overload) that plans with `Compiler.query`, runs with
   `Execution.run`, writes lite's JSON rows (`result/JsonRowsWriter`, what the query service serves) and reads them
   back as a client does: columns (name, Pure type, multiplicity) and rows of JSON values. It may keep the accessor
   names tests use (`rows().get(0).get(0)`, `columns()`, `rowCount()`): it is a reader of the product's output, not a
   copy of `ExecutionResult`. Never in main.
2. Switch `AbstractDatabaseTest` (`queryService.execute`, `executeRelation`, `executePureQuery`) and the other helpers
   (§2) to it. Every expectation that moves is decided one by one and listed (the old decoder's Java carrier, an
   `Integer` for a `Long`, a Decimal's scale, a date's precision: a correction recorded, or a bug in the new path
   fixed), as phase 5 did for its 93.
3. Run per lane like PCT: `AbstractDatabaseTest.backend()` is `TestBackends.platform()` (DuckDB) today; parameterize
   by `TestBackends` and give each lane a Bazel target beside PCT's (DuckDB, SQLite, H2, MongoDB here; the servers and
   the clouds through the lanes).
4. The ~850 tests that only reach the old door to compile or plan (homework README §5) call `Compiler.query` or the
   plan instead.
5. The LEAN judge is lite's JSON rows first; Arrow joins it when track A lands, with a gate that the two agree.
Gate: the census's 1,450 value tests pass on DuckDB with every moved expectation listed; then each lane at a recorded
floor. Census: `python3 -I docs/result-boundary-homework/probes/test_census.py.txt`.

### Track C. The corpus, channel B and the service tests on track B

Switch `PureTestRunner` (bodies and setups), `ChannelB`, `ServiceTestRunner` and the referee (`SqlReplayReferee`,
`ReplayReferee`, `RefereeVerify`) to track B's entry and typed sides. The plan path program's step 10 (the 2,798 bodies
through plan sequences). Gate: the rosters (`spec/src/test/resources/rcorpus`) unchanged, both judges agreeing,
`//spec:judge_lanes`, `//gates:corpus_duckdb`, `//gates:corpus_h2`, channel B per test on every lane.

### Track D. The deletions

After T and C: `Execution.execute*` and `executeResolved`, `exec/ExecutionResult`, `QueryService`'s materialized
`execute` methods, `TestDataGenerationNatives.planTextResult`'s result type, the old helpers in `AbstractDatabaseTest`,
`StatementExecutor`'s JDBC; `StatementExecutor` renamed to what it is (the body driver) if it stays. The census before
and after: `python3 -I docs/result-boundary-homework/probes/oldpath_census.py.txt` must read zero for the step-7
symbols. Guards shrink with dated notes: `JavaEvalLedgerTest`'s exec register and pins, `TenetRatchetTest`'s JDBC
accessor pin, `ArchitectureTest`'s pins. The plan path program's step 11.

### Track L. Mixed lists as typed values (the program's step 6)

A list of tagged values (one tag, one typed slot per kind), as the ratified selection design
(`docs/RESULTS_AND_RENDERERS_2026_10_22.md` §5a) extended to lists: `MixedEncoding` (`rankedElems`' text ranks go),
`Scalars` (mixed `sort`, the Any-kind carrier), `Lowerer` (a mixed collection at a root as parallel typed arrays),
`RowDecode`/`JdbcDecode` (the tag then the slot per element), `CanonicalRenderSql` (the canon per slot),
`ColumnFacts`/`MirJson`/`PlanJson` (a format bump); then delete the `LITERAL` slot, `LiteralText.parse`, the dialects'
literal-text arms and `Fold.conformValueEgress`. Gate: a `TaggedListTest` beside `TaggedSelectionTest`; the PCT suites;
every lane's channels A and B; the `LITERAL` census zero. Traps: arithmetic over a tagged value is not designed; H2 has
no list encoding (its lane runs the relation suite alone); SQLite reads a BIT as the text "false".

### Track A. The Arrow writer (the program's steps 2 and 5)

Ruled 2026-10-28: "yes keep arrow writer at the boundary in this program (but no arrow dependencies in compiler!)".
Move the warehouse's hand-written Arrow IPC framer (`ArrowStreams`, `FlatBuilder`) into a dependency-free module both
the warehouse and core's writers use, with its pyarrow judge; write `ArrowWriter` from typed batches; consume it in the
Python route (`ARROW_IPC`) and the tab. Guards: no `arrow` repository in the deps of `//core:plan_side`, the
WebAssembly planner or the native image (R9). Gate: the warehouse's Arrow tests unchanged; a round-trip gate that
Arrow and JSON carry the same values for every kind; DataCube's Python tests.

### Track F. The open findings

Each with its evidence in the homework README §9: S113 (`toVariant()` of an empty value on DuckDB); the engine-parity
H2 dialect's `fromJson` (a JSON string, not parsed: a silent wrong answer); a `BIT` store column's type (a ruling);
Snowflake's far-BC `TIMESTAMP_NTZ` (its dialect returns the database's own text; a lane measurement);
`RootNumericTypes`' guessed unbounded Decimal; the tab's Arrow nested values in `HostRows`; an absent to-many property
written as `null`; the PCT JVMs at their 4 GB heap ceiling.

### Track P. The backlog from the plan path program

Not part of this program, recorded so it is not lost (the plan path program's §9 status): PCT parity batch 3 (a
Variant's key order on MySQL and Postgres, a carrier change; a named zone where the database has no zone rules, a
question for the user; SQLite's big integers); the ClickHouse and Postgres pins that legend-engine passes; the lane
pins that legend-engine also fails (MySQL and MariaDB windows, H2 lists, Trino rows, MongoDB clusters); the deep SQL
optimization passes the user asked for after parity; the TeaVM bump to 0.16.0 that closes PARK-40 (delete
`tools/teavm`'s `WasmDataOffsets`); the program's step 0 local measurements M1 to M4.

### Z. The final audit and the landing

An audit against the tenets and the program (an independent agent, the code not the recollection); the documents
updated for every renamed and deleted piece; every lane measured; the PR handed to the user to merge.

## 5. How we work (the user's rules; each learned the hard way)

**Before code**
- Measure first. A design argument without a number is an opinion.
- Plan each step with the user and get agreement before writing code. Plain words, short sentences, one settled
  answer, a recommendation rather than a survey.
- Find the root cause. No hacks, no fallbacks, no defaults (AGENTS.md invariant 4). A guard that rejects a change is
  usually right. A pin, ceiling or allowlist entry moves only with a dated justification naming the task.
- Never put a value computation in Java (R3). Formatting at the edge; anything that decides a value goes back to the
  database or to the plan's facts.
- **Never change how an output is spelled without the user's word, case by case** (2026-10-28): a measured
  difference in any writer's output (LEGACY or LEAN) is put to the user alone, with its expression, before and after.
- **Probe legend-engine, do not guess** any spelling of its (the homework README §7 has the recipe; PARK-34's lesson).
- Never commit a probe as code. Probes live in a scratch directory; their source is archived as text under
  `docs/result-boundary-homework/probes/` and their numbers quoted in GATES with the date.

**While coding**
- Develop and push only on the designated branch (`git push -u origin <branch>`). **No session pushes to `main`
  without the user's explicit word in that session** (the 2026-10-26 incident: 15 CI lanes red). A landing of more than
  one change goes through a PR.
- Commit messages: plain, dated where a fact is cited, honest about what was NOT run, no model names; end with the
  attribution lines the session's instructions give.
- `docs/IN_FLIGHT.md`: announce each step before touching its files; land with its GATES entry.
- **Records land with the code** (the audit's first lesson): GATES, IN_FLIGHT, the program's §9 line, a register row
  for every deliberate difference. None of these is a later step.
- **Every switch is preceded by running old and new side by side on every lane**, and each lane proves which route ran
  (a marker per crossing, counted); every deletion is in the same step as its switch (R7). Deleting first and
  measuring later is how phases 3 and 4 lost passes (homework README §1).
- At most two heavy Bazel jobs at once; Bazel runs one command at a time per output base.

**Landing a step:** its own checks, the guards, the local gate; the lanes per test (§7); the GATES entry with the
numbers; the deletion; IN_FLIGHT and §9.

## 6. How to run each check (exact)

| Check | Command | Pass means |
|---|---|---|
| A core package | `bazel test //core:core_tests_<package>` (architecture, builtin, cache, compiler, exec, ide, integration, ladder, lexer, lineage, lowering, model, normalizer, parser, platform, protocol, resolver, server, setup, sql, test, testdatagen, testing, values; `//core:core_tests_root` the rest) | all pass |
| The guards | `bazel test //core:guardrails //core:census //pct:pct_discipline` | all pass (`JavaEvalLedgerTest` is in `census`) |
| PCT channel A, one lane | `bazel test //pct:pct_<lane>_{essential,grammar,relation,standard,unclassified} --local_test_jobs=2` (lanes: duckdb, sqlite, mongodb, h2 (relation only: `//pct:pct_h2`), postgres, mariadb, mysql, clickhouse, trino, snowflake, bigquery, databricks); 4 GB heap each | each suite exact against its ledger (§7 reads the failures) |
| PCT channel B | `bazel test //pct:pct_channel_b_<suite>` (DuckDB) or `//pct:pct_channel_b_<lane>_<suite>`; `//gates:pct_channel_b` | per test identical to the previous commit |
| The corpus | `bazel test //gates:corpus_duckdb //gates:corpus_h2` and `//spec:judge_lanes` (both judges) | the rosters unchanged |
| The served routes and legend-engine's answers | `bazel test //core:core_tests_server` (`PureV1ApiTest`) | all pass |
| DataCube and Python on the same Java | `bazel test //datacube:python_execute_test` (builds the native library and the WebAssembly planner; about 12 minutes cold) | all pass |
| Postgres targets in a cloud container (root) | `cp tools/postgres/as_unprivileged.sh $SCRATCH/; bazel test <target> --run_under="$SCRATCH/as_unprivileged.sh ubuntu" --strategy=TestRunner=standalone` (`docs/CLOUD_SESSIONS.md`) | all pass |
| The local gate | `bazel test --lockfile_mode=error //gates:local` | all pass |
| A cloud container's first build | `tools/cloud-distdir.sh` once per container | Bazel resolves |
| Asking legend-engine | the homework README §7 | the answer recorded under `upstream-api/` |
| The old-door census | `python3 -I docs/result-boundary-homework/probes/oldpath_census.py.txt` | zero where a step says |

## 7. The lanes: running the other databases

**What runs where.** A cloud container runs DuckDB, SQLite, H2 and MongoDB, and the embedded servers (Postgres
unprivileged, MariaDB, MySQL, ClickHouse, Trino: Linux x86_64 only), but its disk allowance is about 3 GB free once
Bazel's caches are warm, so a session usually runs the local four and hands the rest to lane sessions. Snowflake,
BigQuery and Databricks need credentials, which only an environment's setup script provides (never the repository,
never a message).

**This account's warm lane sessions (2026-10-28; `get_session` says whether one is still alive):**

| Lane session | Databases | Id |
|---|---|---|
| step 9 lane | Postgres (unprivileged), SQLite, MongoDB | `session_01UmbETZRDU9ugZ8X2d1hGyb` |
| step 9 lane | MariaDB, MySQL, ClickHouse | `session_018ATdMqvopmrLibQhwN4GQ4` |
| step 9 lane | Trino, H2 | `session_01Hztqdk3qySkTkH2uUDuUdL` |
| step 9 lane | Databricks | `session_01757FUtRcj12NGhvSxL6A1b` |
| PCT lane | BigQuery | `session_019tku7nRKBygjSBz9QQBGfM` |
| PCT lane | Snowflake | `session_01WDoDkeNvgCsaPVzNx6wGDA` |
| the core writer (this program's session) | DuckDB, SQLite, MongoDB, H2 locally | `session_01Vz1L3X6p56AZdTCQXUXYfE` |

**From another account** none of these sessions, their environments or their credentials exist. Raise your own:
1. An environment per credential set, whose setup script writes the properties file `LEGENDLITE_PCT_CLOUD_PROPERTIES`
   names (`docs/CLOUD_BACKENDS_PCT_2026_10_11.md` §4 the file's keys, §6 a cloud session's setup). The script lives in
   the environment's configuration, not in the repository; nobody pastes a key into a session.
2. One session per lane group (the table above is a good split: the embedded servers fit two or three per container),
   created on this branch (`create_session` with `source_url` the repository and `source_revision` the branch).
3. Each lane bootstraps Bazel (`tools/cloud-distdir.sh`), then measures the base commit once and keeps its logs: a
   lane's value is its history.

**The brief** (every message to a lane, plain text, under 64 KiB; the Snowflake lane refuses encoded blobs):
> MEASURE ONLY: no commits, no pushes, no credential reads, no tracked file edited. `git fetch origin <branch>`;
> `git checkout --detach <commit>`; a clean tree. Run <channel A / channel B>, <suites>, on <databases> (Postgres
> unprivileged), <with any --test_env flag>. Compare each suite with your <previous commit> logs (no need to re-run
> them). I expect <exact / identical per test>. Report: one table per lane; every lost pass, every moved message and
> every channel B difference in full (test name, expected and actual text); the route markers counted
> (`grep -c '\[LegendLite PCT\] engine JSON:'`); leave the tree clean.

**Reading a report.** `docs/result-boundary-homework/probes/compare_lanes.py.txt` is the comparison every lane ran:
channel A failures by their wording (STALE, MOVED, LOST), channel B per test, the markers. A report without marker
counts after a route switch is not yet evidence (Databricks and Snowflake each had to be asked on 2026-10-28).

## 8. Traps already hit (add to this list)

- **The disk allowance** (about 3 GB free in a warm container): clear `$(bazel info output_base)/sandbox/*` when idle;
  the engine runner's jars are about 600 MB; the repository-contents cache holds an 11 GB toolchain, do not delete it.
- **A local `git stash` in the original container** (`stash@{0}`, a Snowflake per-key column fix of 2026-10-21) is
  obsolete: its change landed as `deaceee4`. The auto-mode classifier refused dropping it; it dies with that container.
- **The census bans naming a database** in a test that is not its owner (identifiers, `jdbc:` URLs; a database's name
  inside a Pure model string is fine): `DatabaseOwners`, `DialectLeakRatchetTest`.
- **`JavaEvalLedgerTest`'s line pins are exact both ways**: a shrink must re-pin, with a dated reason.
- **Closing a parked item deletes its anchor** in `ParkedWorkLedgerTest` in the same commit.
- **The engine-parity H2 dialect is not H2Modern**: H2 2.1 and 2.2 (the engine's) keep this dialect's walls; `fromJson`
  there answers a JSON string (track F).
- **`toVariant()` of an empty value is SQL's NULL on DuckDB** (S113): a test over nullable data sees an empty Variant.
- **CI runs only on pushes to `main` and on PRs**, and ignores `**/*.md`: a branch push runs nothing.
- **A lane can be idle and say nothing**: `get_session` gives its task summary; `list_events` its last messages.
- **The old traps of this line, still true:** the clouds' channel A on a system-mode connection (2.4x slower: use the
  suite's held session); a tagged column's plan slot needs its `Cast(..., SqlType.Tagged)` identity; every exhaustive
  switch over `SqlExpr`/`SqlType` needs the new arm (`SqlRewriter`, `FoldToListReduce`, `UnqualifyPivotArgs`,
  `SubselectPrune`, `Windows`, `AnsiSqlRenderer`, `MongoDb`, Snowflake's `raisedAs`, BigQuery's `bqType`,
  `ColumnFacts.slot`, `MirJson`); `PlanJsonTest` pins the format version; Snowflake's Float text caps at 16 significant
  digits (`docs/RESULTS_AND_RENDERERS_2026_10_22.md` §10.5); a probe's VALUES rows are run-time values on Snowflake;
  `//gates:local` as root needs the unprivileged Postgres run; TeaVM's 1 MiB data-segment cliff (PARK-40); `bazel
  info` blocks while another build runs.

## 9. What was never in the repository, and where it went

| What | Where it lived | Where it is now |
|---|---|---|
| the audit of phases 1 to 5, the lost-pass analysis, how the PCT works, the three-stage plan | a session's conversation | the homework README §1 to §3; this page §3 and §4 |
| the maps of the code (corpus path, judges' sides, `StatementExecutor`'s kinds, the decided end state) | a session's scratch log | the homework README §6 |
| the 51 writer differences | a session's scratch file | `docs/result-boundary-homework/wire-differences-2026-10-27.tsv` |
| the census of core's tests | a session's analysis | `docs/result-boundary-homework/test-census-2026-10-28.tsv` and its script |
| every probe's source; the census and comparison scripts | scratch directories of this session and the lanes | `docs/result-boundary-homework/probes/` |
| what legend-engine answered | a probe's output | `core/src/test/resources/upstream-api/` and the homework README §7 |
| the user's rulings | the conversation | §10 below and the program's §8 |
| the lanes and their credentials | other sessions and their environments | §7 (how to raise your own); credentials never copied |
| other branches | `origin/claude/step9-*` (the step 9 lanes' branches, merged on 2026-10-19: history); `origin/claude/deephaven-3-browser` (the Deephaven line, `docs/DEEPHAVEN_START_HERE.md` there); `origin/studio-program` (the Studio line) | not this program's |

## 10. The user's rulings and words, in order

| Date | Words | What it decided |
|---|---|---|
| 2026-10-24 | (D6, the renderers redesign) | two first-class renderers, LEAN and LEGACY |
| 2026-10-26 | the eight rulings, "arrow look good", "sounds great" (the program's §8) | the batch is our own representation; no Arrow library anywhere; the Float rule; the database judge stays (R10); zero dependencies (R9) |
| 2026-10-26 | "use the sessions that connect to bq, snow, dbx in their own parallel sessions to iterate fast only if that helps" | lanes measure in parallel; core has one writer |
| 2026-10-26 | after the push to `main` without a PR: "reset" | AGENTS.md "Pushing to main": no session pushes `main` without the user's word; landings through a PR |
| 2026-10-27 | "Skip the guards - let's actually do the real work of making the plan real and deleting everything else including the string spellings and old execution - keep going in phases, do expert work, audit after each phase" | phases 1 to 5 |
| 2026-10-27 | "we need to pause and do an audit - what did we actually do? did we do it right? did we stick to our core tenets?" | the audit (homework README §1) |
| 2026-10-27 | "keep it, and keep going" (answering the audit's first question: keep phase 5's commit) | the three-stage plan, stage 1 first (the program's §8, ruling 9) |
| 2026-10-28 | "any output spelling changes need to come to me on case by case basis" | no session accepts or registers a spelling change of any output: each goes to the user alone, before and after, and the answer is recorded (S1.4, S1.5, every track) |
| 2026-10-28 | "yes keep arrow writer at the boundary in this program (but no arrow dependencies in compiler!)" | track A stays in this program, at the boundary only; R9's guard |
| 2026-10-28 | "writer guards are for what exactly" | answered (S1.4 item 3 keeps the explanation) |
| 2026-10-28 | "yes add this to the plan" | the two writer guards are built in S1.4, right after the precision fact |
| 2026-10-28 | "what should we do about the negative zero? what is the right move", then "okay" to the answer | LEAN JSON and CSV write `-0.0`; Pure's `toString` stays `0.0` (S1.4 item 2, register S114) |
| 2026-10-28 | "Do the probe" | PARK-34 closed by asking legend-engine |
| 2026-10-28 | "Do it" | the engine crossing measured on every lane, then switched; `PureTdsText` deleted |
| 2026-10-28 | "i would prefer not to delete the overlapping tests but use the exact same logic but with platform output that we think users would actually run/use instead of backwards compat mode - so its almost the platform twin of PCT/core-relational" | track T |
| 2026-10-28 | "write this all up ... make sure there is nothing in this session ephemeral that we lose" | this page and the homework folder |

## 11. Reading order

1. This page.
2. `docs/result-boundary-homework/README.md`, all of it.
3. `docs/RESULT_BOUNDARY_PROGRAM_2026_10_26.md`: §0 (the plan in plain words), §1 (the rules R1 to R10), §8 (the
   rulings), §9 (the status lines).
4. `docs/RESULTS_AND_RENDERERS_2026_10_22.md` §0 to §5a and §10.5; `docs/PLAN_AND_RESULT_MODEL_2026_10_18.md` §2 to §6.
5. `docs/PLAN_PATH_PROGRAM_2026_10_17.md` §5 (lets), §6 (both judges on plans), §7 (steps 8 to 11).
6. `docs/TENET_CHARTER.md` Clauses 1 to 4; `docs/SEMANTICS_REGISTER.md` S104, S112, S113.
7. `docs/GATES.md`'s entries from 2026-10-26 on (the merge, phase 5, the crossing, PARK-34, the switch).
8. Before the first message to a lane: `docs/CLOUD_BACKENDS_PCT_2026_10_11.md` §4 to §6 and `docs/CLOUD_SESSIONS.md`.
9. The code: `ExecutionPlan.java`'s javadoc, `exec/PlanRunner.java`, `result/CellText.java`, `PctPlanRoute.java`,
   `AbstractDatabaseTest.java`, `StatementExecutor.execute`.
