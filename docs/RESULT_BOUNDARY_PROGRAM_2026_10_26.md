# The result boundary program: a typed plan, writers at the edge, the text path deleted

**Status: RULED IN PART, revision 3, 2026-10-26.** The user ruled on 2026-10-26: the design (D1 to D5), the
formatting-at-the-edge clause with its guards, lean JSON's numbers, collecting into memory as a test helper only,
and the order are accepted as written in revision 2 ("everything else is okay"); **the database judge is never
deleted** ("a crown jewel"); the Float rule and the Arrow question were sent back for the expert version, which
revision 3 gives (§8, items 3 and 4) and which waits on the user's yes. Revision 1 was written the same day at the
user's ask ("this is now becoming a huge blocker to our streaming/deephaven/real-time ambitions; how can we move this
forward sanely AND delete all our historical cruft"); revision 2 folded in the user's three questions that followed.
It is the execution plan for `docs/RESULTS_AND_RENDERERS_2026_10_22.md` (D1 to D6) and for the plan path program's
steps 7 to 11 (`docs/PLAN_PATH_PROGRAM_2026_10_17.md` §7), made one program. Nothing is built; the handoff for a
session with no context is `docs/RESULT_BOUNDARY_START_HERE.md`.

## 0. The plan in plain words

**The goal.** A query produces a table of typed values. The database computes every value. Our SQL never formats
anything for output. Whoever consumes the result formats it at the edge, their way: Studio and the other legend
clients get legend-engine's exact JSON, written in Java; the PCT and corpus harnesses get Pure values, no text; lite's
own clients get lossless typed data (Arrow, JSON, CSV); the browser formats its own grid. Every old path that did it
differently is deleted, not kept beside the new one. The product keeps zero third-party dependencies in the compiler,
the WebAssembly planner and the native image: no Arrow library enters the product (§2).

**What we have today.**

- The compiler makes a plan: the SQL plus, for each result column, its Pure type and multiplicity, the SQL type the
  database answers with, and the store column's declared type. This is right and stays.
- Two executors. The old one runs SQL and builds an in-memory result with its own decoding. The plan runner runs the
  plan and reads each cell by the plan's column types. Two decoders; one must go.
- On most routes the SQL itself is made to write the final text: Pure's TDS text for PCT, legend-engine's JSON rows
  for the served API, CSV. That is what turns one addition into a 48 KB statement on Snowflake for PCT and 6 MB for
  the served API, makes every new database re-derive Pure's spellings before one test passes, and is the wrong shape
  for anything that streams or ticks. This goes.
- Values SQL has no type for. A mixed `max` now comes back as a tag plus one typed column per kind (landed
  2026-10-23). Mixed lists still come back as text and must be typed the same way.
- A Float turned into a String inside a query (`toString`, `format`): the database writes it, as the shortest decimal
  that reads back as the same double. On Snowflake and BigQuery that costs a 5 KB expression because their own
  formatters are lossy (measured 2026-10-21 and 2026-10-24). This stays, because it is part of the query's meaning
  and nothing cheaper is lossless there; §8 item 3 says how to make it cheap where it can be.
- The warehouse server already writes Arrow IPC streams with no Arrow library: its own framer
  (`warehouse/.../duck/ArrowStreams.java`, `FlatBuilder`) over the buffers DuckDB builds, inside the native image,
  checked against pyarrow in its tests. The lean Arrow writer is that framer over our own column batches.

**What we will do, in order.** Measure first. Make the plan answer typed rows and nothing else. Define the result
as a stream of batches of typed rows, in our own column arrays. Write legend-engine's JSON in Java and prove it byte
for byte, then delete the SQL that wrote it. Feed PCT and the corpus from typed rows, lane by lane, then delete the
SQL that wrote TDS text. Add the lossless writers, the Arrow one over the warehouse's framer. Type the mixed lists and
delete the text carrier. Delete the old executor and make "collect into memory" a test helper rather than a
production method. The database judge and the host judge both stay, reading typed rows.

**Is it correct?** Yes. The decision that matters, typed results with formatting at the edge, is what every engine
and every driver does, and the measurements of 2026-10-18 and 2026-10-22 show the alternative's cost on our own
lanes. The risks are known and each has a gate that finds it (§6).

**Is it tractable?** Yes. Seven to nine weeks of one session's work, the cloud lanes' turnaround included. Every
step lands on its own with its own gate and its own deletion; no step needs the ones after it; the Deephaven line
needs only steps 1 and 6. The risk is concentrated in step 4 (the lanes) and step 7 (test expectations that move
when the old decoder's quirks are corrected), and both are made visible by running old and new side by side before
switching.

## 1. The rules, each with the test that keeps it

AGENTS.md: guard files are the rules' mechanical form. A rule without a guard is a wish.

- **R1. The database computes every value; our code derives none.** Unchanged (the execution tenet; the charter's
  Clauses 1 to 6). Guard: `JavaEvalLedgerTest`, `ArchitectureTest.theInterpreterPerformsNoJdbc`.
- **R2. The plan describes types; it never spells values and never names an output format.** Every result column
  carries its three facts. A fact that only Pure knows (a Decimal's scale, a DateTime's written precision, a tagged
  winner's kinds) travels in the plan or in the data, never in a text. Guard: `PlanJsonTest` (format pin),
  `PlanMakerTest.aColumnCarriesItsThreeTypeFacts`, `testcases/PlanRoundTrip`; new: the planner makes no `TextResult`.
- **R3. Formatting at the edge is serialization, not computation** (ruled 2026-10-26). A writer spells the typed
  value it received, losslessly, in its consumer's layout. It does no arithmetic, no comparison, no conversion
  between kinds, no default for a null, no zone shift, and chooses no spelling by a Pure rule. The test: swap the
  writer and every value is unchanged. Guard, new: `ArchitectureTest.writersAreCarriageOnly` (the writer package
  depends on the plan's column facts and the typed batch, on no `compiler`, `lowering`, `sql` or `exec` class) and
  `WriterCarriageGuardTest` (a source scan for arithmetic and comparison on numbers and dates in that package, zero
  allowed; representation operations such as setting a scale without rounding are allowed and listed). A scan
  catches drift, not an adversary; that limit is the same the existing guards carry. The charter clause is D3's.
- **R4. One statement, one typed result, several writers.** The legacy writer makes legend-engine's bytes and is
  judged by bytes (`OutputMatrixTest`); the PCT and corpus harnesses take Pure values through `ValueBridge`; the lean
  writers are judged by the value round trip. Execution is never forked for a format (D6).
- **R5. A String the query itself builds is computed in the query, lossless, with one spelling.** The shortest
  round-trip decimal in Pure's layout: the database's own text where its cast writes shortest digits, the exact
  expression where it does not (Snowflake, BigQuery), a temporary function of the database's where that is measured
  exact and cheaper (§8 item 3). This is D7. It is the only text the database writes for a result. Guard: the PCT
  suites; a dated register row for any deliberate difference.
- **R6. A value SQL has no type for rides as a typed encoding with a plan fact, never as a spelling.** The tagged
  winner is landed; mixed lists follow (step 6) as a list of tagged values, which is Arrow's list of a union, so the
  plan's vocabulary, the tagged carrier and the lean wire are one type system. Guard: `TaggedSelectionTest` and its
  successor for lists; a shrink-only census of `LITERAL` slots in the test corpus's plans, zero at step 6.
- **R7. Measure, run both, switch, delete, in that order, per lane.** Nothing is deleted until its replacement has
  produced the same bytes (legacy) or the same values (lean, PCT) on every lane and the gate pins it. No parallel
  path survives its switch. Guard: a GATES entry per switch; a shrink-only census of database-written result text,
  zero at step 4.
- **R8. A result is a stream of batches of typed rows** (ruled 2026-10-26). A batch is up to N rows, flushed when
  full, when the source has no next row ready, or at the end; a batch of one row is a batch. Memory is bounded by one
  batch. Collecting a whole result into memory is a writer like any other, used by tests and the judges, and never a
  production method. Guard: `TenetRatchetTest` (JDBC accessor sites at 21); the exec package's closed register; new:
  no production method in `Execution` returns a materialized result.
- **R9. Zero third-party dependencies stay zero in the compiler, the WebAssembly planner and the native image**
  (the user, 2026-10-26: "I don't want to compromise on that"). The Arrow format is written by our own framer; the
  Arrow and pyarrow libraries are test-only readers. Guard, new: a Bazel query test in `tools/guards` that
  `deps(//core:plan_side)`, `deps(//wasm:...)` and the native image's deps contain no `arrow` repository; the
  existing C2a pin (`ArchitectureTest.rootJavaSqlSurfaceIsPinned`: the planner is `java.sql`-free).
- **R10. Both judges stay** (the user, 2026-10-26; plan path R2). The host judge compares typed values in Java; the
  database judge composes both sides into one verdict statement and compares inside the database, over the
  platform's own canonical spellings (`FLOAT_CANON`, the tagged canon per slot), which are query-side constructs
  like `toString` and are never deleted. The two must agree. Guard: `VerdictChannelRegisterTest`, channel B's floors.

## 2. The shape, once

```
Pure query ──▶ PLAN: the statement + one column record per result column (type, multiplicity, slot, store)
                 │        no output format, no spelling, no writer named in it
                 ▼
            EXECUTE: the database computes everything that has meaning
                 │
                 ▼
            TYPED BATCHES: the session reads cells by the plan's facts into our own column arrays, up to N rows
                 │        (a row driver fills small batches; DuckDB's native path hands over its own buffers)
     ┌───────────┼──────────────────────────┬──────────────────────────┬────────────────────────┐
     ▼           ▼                          ▼                          ▼                        ▼
 lean writers   legacy writer            ValueBridge              a collector              the tab's own reader
 Arrow IPC      legend-engine pure/v1    (PCT, corpus):           (tests, the judges;      DataCube on DuckDB in the
 (our framer),  JSON, byte for byte      Pure values, no text     never a production API)  browser, and on Deephaven
 JSON, CSV
```

The right-hand consumer is why the plan is the only contract that matters for real time: on the Deephaven route no
Java of ours runs between the plan and the grid. What it needs from this program is R2 and R6, nothing else.

**Row databases and batches (the user's worry, 2026-10-26).** Batches are not a plan concern and not two execution
modes; the batch size is a session setting. A row-at-a-time driver (Postgres, MySQL, H2, SQLite) produces small
batches and flushes on idle, so the first row is not delayed and a tick arrives as a batch of one. Moving a row's
cells into columns is a copy per cell, cheaper than today's typed read plus formatting per cell; the driver's own
wire cost is paid either way, and most row drivers fetch in chunks underneath. A one-value result pays microseconds
of fixed cost against a round trip of milliseconds. Choosing rows as the unit instead would de-vectorize every
columnar source and re-vectorize it at the Arrow writer: slowest exactly where the databases are fastest.

**Where the Arrow format lives, and where no library does (the user's question, 2026-10-26).** The compiler
(`//core:planner`, exported by `//core:plan_side`, the WebAssembly planner's only core input) has no execution
dependency and no Arrow; that is C2a, pinned today, and R9 adds the Bazel query guard. The execution side
(`//core:exec`, the JDBC drivers in `//core:drivers`, the writers) gains no Arrow library either: the batch is our
own typed column arrays, and the Arrow IPC writer is the warehouse server's existing dependency-free framer
(`ArrowStreams` and `FlatBuilder`, the Flatbuffers messages and buffer layout written by hand, about 300 lines),
moved to a shared dependency-free module so core's lean writer and the warehouse's SQL API use one. On DuckDB the
warehouse's native path already hands over DuckDB's own Arrow buffers; on JDBC sessions our arrays become buffers by
our code. The Arrow Java library and pyarrow stay what they are today: readers in tests (`arrow_matches_json`),
which read back what our writer wrote. The native image (the warehouse server) therefore carries exactly what it
carries today.

## 3. Where things stand (honest)

| Piece | State today | Step |
|---|---|---|
| Three facts per column, typed tree in the plan JSON (format 10) | landed (2026-10-17/18; tagged slots 2026-10-23) | kept |
| The typed seam: `ExecutionPlan.TdsResult`, `PlanRunner.rows`, `JdbcDecode`/`RowDecode` by the plan's facts | landed; used by the typed callers and `TaggedSelectionTest` | step 1 makes it the only result |
| `ExecutionPlan.TextResult` with `Format {CSV, JSON, JSON_PER_ROW, TDS_TEXT}`; `TypedQuery.Output` as a planning input | live: PCT (`TDS_TEXT`), the served routes (`JSON_PER_ROW`, `JSON`, `CSV`) | steps 1 to 4 retire; deleted at step 4 |
| `WireRender` (177 lines), `Render`'s text wires (in 1,360), `TdsTextWrap` (268), `FloatPrintText`'s engine layout (in 269), `LiteralSpelling.floatPrintJava` and `dateTimeTextEngine` (in 1,052), each dialect's `FLOAT_PRINT_ENGINE` arm, MariaDB's and MySQL's `floatLayout` | live | deleted at steps 3 and 4 |
| `PureV1Api` builds the envelope in Java and takes the rows as the database wrote them (`Execution.executeWire`, a `List<String>` of lines) | live | step 3 |
| `ValueBridge` builds Pure values, lists and class instances from typed values; a relation arrives as TDS text | live | step 4 adds the relation from typed rows |
| `Executor` (1,195 lines, the old executor) and `ExecutionResult` (an in-memory result: columns and rows), produced by it and returned by `Execution.execute` | live; 101 test files and 28 main files use the type; one real route (`server/QueryService`), the channel B judges, loaders, and type references | step 7 |
| `Fold.conformValueEgress` (re-types a value root's egress) | live | step 5 deletes with the text wires |
| `LITERAL` slot, `LiteralText.parse`, the Any-kind carrier, mixed `sort` | live for lists | step 6 |
| The Arrow IPC framer | live in the warehouse server, dependency-free, native-image-proven | step 5 shares it |
| `CanonicalRenderSql` (1,085 lines) and each dialect's `FLOAT_CANON`: the database judge's canonical spellings | live (channel B) | stays (R10); simplifies per slot as carriers are typed |
| A Float's String inside a query on Snowflake and BigQuery | the exact-digits expression (correct, ~5 KB per Float printed) | stays (R5); §8 item 3 measures the cheaper form |

## 4. Step 0: measure first (one week, nothing built)

Every number is produced before step 1 and recorded in `docs/GATES.md` under one entry, "The result boundary
program, step 0". Each is the baseline a later step is judged against.

- **M1. The census of database-written text.** For every plan the test corpus makes (the integration suite, PCT's
  five suites on the platform's engine, the corpus, `OutputMatrixTest`): which result node, and which consumer asked.
  Its `TextResult` count is R7's shrink-only pin and must read zero at step 4. Local. Half a day.
- **M2. The value round-trip gate, built and run.** Every Pure kind of RESULTS_AND_RENDERERS §5 through every
  carrier: each local database's driver read, then Arrow and JSON once they exist. Compared by value, never text. Its
  failures are the real work list of steps 1 to 3 (time zones, BigQuery's BIGNUMERIC scale, nested values per
  driver, a Decimal whose scale varies by value, an Integer past 64 bits). Local first, the clouds through the lanes.
  Two days.
- **M3. Host writing speed.** Ten million rows on DuckDB: typed batches plus a Java JSON writer and a CSV writer,
  against today's database-written wires (2026-10-18: typed 10 to 12 s, the JSON wire 50 to 61 s). Never measured;
  step 3 does not switch without it. One day.
- **M4. The collector over the plan path, once.** `ExecutionResult` filled from the plan runner's typed rows on H2
  and DuckDB, the 101 test files run against it: how many expectations move, and why each moves (the old decoder's
  quirk or the new one's bug). Sizes step 7. Half a day.
- **M5. Statement size and lane time per suite.** Per lane and suite: the largest statement, the count over 5 KB,
  wall time; the lanes' histories collected into one table with the local lanes. Half a day.
- **M6. How often a Float becomes a String inside a query** in PCT standard and essential, the corpus and the
  Deephaven line's 74 trading queries, per lane. Decides whether R5's cheaper form is worth building. Half a day.
- **M7. What legend-engine itself prints for a Float's `toString` on Snowflake and BigQuery**, and which JDK's
  `Double.toString` the `OutputMatrixTest` bytes came from (before JDK 19 it was not always shortest: 8.41E21 printed
  as `8.409999999999999E21`). Decides what legacy byte parity means on a lossy engine and whether the writers ship
  their own shortest-digits routine or pin the JDK. Half a day.
- **M8. The cheaper lossless Float text on Snowflake and BigQuery: a temporary JavaScript function** (§8 item 3).
  Over the same 1,946 doubles: exactness against Java's shortest digits byte for byte (JavaScript's own
  `Number.toString` is the shortest round-trip form by its specification; the layout is rewritten to Pure's in the
  same function), speed per 10,000 rows against the exact-digits expression, and the privileges a temporary function
  needs on each cloud. One lane probe each. One day.

Step 0 ends with §5 re-sized from the numbers.

## 5. The steps

Each step has the same five beats (R7): measure (its M items), build, run both (old and new, every lane, differences
counted), switch, delete (in the same step, never later). A step lands as one GATES entry and one line in §9.

### Step 1. One result shape, and the batch stream (three days)

- Build: the planner makes a `TdsResult` for every relation and value root; `TypedQuery.Output` leaves the planning
  input (`TypedQuery.plan`, `PlanMaker`, `ExecuteOptions`, `Diagnostics` take no format); `PlanJson` format 11 writes
  no `TextResult`. The result API: `Execution.batches(...)` answers a stream of typed batches (R8's flush rule) to a
  writer; `executeStreaming` becomes a writer; `executeWire`'s `List<String>` is marked for step 3's deletion. The
  batch type: our own typed column arrays under `exec/`, one array per column in the plan's slot vocabulary, with the
  representation hidden behind the writer interface.
- Delete: nothing yet (the `TextResult` node stays until its last consumer switches at step 4).
- Gate: `PlanJsonTest` 11, `PlanRoundTrip`, the core lanes, M1 showing every planner-made result typed; R9's query
  guard added now, so it watches every later step.

### Step 2. The shared Arrow framer (two days)

- Build: `ArrowStreams` and `FlatBuilder` move from the warehouse server to a dependency-free module both the
  warehouse's SQL API and core's writers use; the warehouse's tests (`arrow_matches_json`, pyarrow reading what the
  framer wrote) move with them and stay its judge. No Arrow library anywhere in the product (R9).
- Gate: the warehouse's Arrow tests unchanged; R9's query guard green.

### Step 3. The legacy writer: legend-engine's rows in Java (one week)

- Build: one writer package (`com.legend.sink`, name the implementer's) under R3's guards. `PureV1Writer`: a row's
  cells as legend-engine spells them from the plan's facts and the typed cell: a Float by the shortest-digits routine
  (M7 decides: the JDK's `Double.toString` pinned, or our own), a DateTime by the nine-digit `+0000` form at the
  plan's precision, a Decimal at its own scale (S97), a tagged winner by its slot's kind, an Integer past 64 bits as
  digits; the envelope `PureV1Api` already writes. CSV and the JSON array the same way, batch by batch.
- Run both: every `OutputMatrixTest` cell (192 on DuckDB and H2) and every served-route integration test, old route
  and new, zero byte differences or a dated register row each (as S97). M3's speed beside it.
- Switch: `pure/v1` execute, the streamed JSON and CSV routes read the batch stream through the writer. `jsonPlan`'s
  `run` (the tab running the server's wire SQL) becomes the base statement plus the plan's columns: the tab types
  its own rows, as it already does for Arrow and DuckDB in the browser.
- Delete: `WireRender`; `Render.jsonWire`, `csvWire`, `jsonWireRows`; `LiteralSpelling.floatPrintJava` and
  `dateTimeTextEngine` with `SqlFn.FLOAT_PRINT_ENGINE` and every dialect's arm; MariaDB's and MySQL's `floatLayout`;
  `Execution.executeWire`; `Format.JSON`, `JSON_PER_ROW`, `CSV`. Snowflake's served statement goes from 5.9 KB to 44
  characters; MySQL's and MariaDB's from megabytes to the base query.
- Gate: `OutputMatrixTest` unchanged byte for byte; the served routes' tests; `writersAreCarriageOnly`; the local gate.

### Step 4. PCT and the corpus from typed rows (one week, the lanes' turnaround included)

- Build: `ValueBridge` gains a relation built from typed rows (column facts to Pure's TDS column types, each cell by
  its kind, a tagged winner by its slot, a list by the identity channel until step 6); `PctBackend` runs the batch
  stream and hands rows to it; `PctPlanRoute`'s text path and `PctShadowPlan` go (plan path R8's referee overlap ends).
  The database judge reads both sides from typed plans as it does today; its canonical spellings stay in the query
  (R10).
- Run both: PCT both ways on the platform's engine and MongoDB locally, then each cloud lane (Snowflake, BigQuery,
  Databricks) and the server lanes through channel B's floors. The pass set must be identical test by test; a test
  that moves has its cause named before the switch. A difference here is a bug, never a register row.
- Switch: lane by lane; each lane's counts re-baselined in `docs/GATES.md` (the merge rule's floors move only here).
- Delete: `TdsTextWrap`, `Render.tdsText` and its per-row form, `Format.TDS_TEXT`, `ExecutionPlan.TextResult` and
  `Format`, `ResultType` where only text used it, `KeyedPivot`'s text outputs (its probe stays). M1's census reads
  zero.
- Gate: the five PCT suites exact on the platform's engine and MongoDB; every lane at or above its previous count;
  M5 re-measured: no PCT statement carries output text; Snowflake's standard suite at or below the merge-base's 269 s;
  channel B's floors unchanged on every lane.

### Step 5. The lean writers: Arrow IPC, JSON, CSV, lossless (one week)

- Build: `ArrowWriter` over the shared framer (one Arrow schema from the plan's columns; an Integer past 64 bits and
  a Decimal whose scale varies by value carried as M2 decided; a mixed value as a union), `JsonWriter` (numbers
  written as exact JSON numbers with the column types beside the rows, ruled 2026-10-26; NaN and the infinities as
  the strings `NaN`, `Infinity`, `-Infinity`, since JSON has no token for them), `CsvWriter`. The Python route
  (`arrowPlan`) and the tab's readers consume them.
- Gate: the value round-trip gate over every writer and every kind (pyarrow and the Arrow Java library as test-only
  readers). No bytes to compare.
- Delete: `Fold.conformValueEgress`.

### Step 6. Mixed lists as typed values (two weeks; runs beside steps 5 and 7)

- Build: a mixed list (`sort` over mixed kinds, the Any-kind carrier, a mixed collection at a root) as a list of
  tagged values, one tag and one typed slot per element, Arrow's list of a union; the decoders read the tag then the
  slot as the tagged winner's do; the database judge's canon per slot. Partial dates (year, year-month) as their own
  kinds with the precision in the slot's kind.
- Delete: the `LITERAL` slot, `LiteralText.parse`, the spelling carrier's remaining machinery in `MixedEncoding`
  (`rankedElems`' text ranks) and the dialects' literal-text arms. R6's census reads zero.
- Gate: `TaggedSelectionTest`'s successor for lists; the PCT suites; every lane's channels A and B.

### Step 7. The old executor goes; collecting becomes a test helper (one to two weeks)

- Build: `Execution.execute(...)` as a production method returning a materialized result is deleted.
  `server/QueryService`, the one real route on it, writes to a writer. The collector (`ExecutionResult`'s shape,
  filled from the batch stream) moves to test code as a helper, and the channel B judges, harness code in main, keep
  it as their named writer. The 135 test call sites call the helper; their assertions do not change.
- The real work, sized by M4: every expectation that moves because the old decoder read a value differently (a Long
  where it read an Integer, a Decimal keeping its scale, a date's precision) is either a bug in the new decoder,
  fixed, or a correction of what the old one silently did, recorded. This step is the test of the new decoder.
- Delete: `Executor` (1,195 lines), `StatementExecutor`'s legacy order; `JavaEvalLedgerTest`'s exec register shrinks
  by those rows; `TenetRatchetTest`'s JDBC accessor pin drops with `Executor`'s reads.
- Gate: the integration suite on the plan path; the corpus; the local gate; both judges green and agreeing.

### Step 8. One question parked for the user

The engine-style SQL-text dialects (`EngineStyleH2`, `EngineStyleDB2`, `EngineStyleComposite`): matching
legend-engine's generated SQL byte for byte is a test oracle, not a product contract, once results are judged by
value. Not this program's to delete; named here because it is cruft of the same kind. (Revision 2 also listed the
database judge here; the user ruled it stays, R10, and it is withdrawn.)

### What stays, by name, and why

A String the query builds (R5); the database judge and the host judge with their canonical spellings in the query
(R10); kind B's encodings until step 6 types them; the exact-digits Float text on Snowflake and BigQuery until M8
measures a cheaper exact form (R5); the engine-style dialects until step 8 is ruled.

## 6. Gates for the program

1. **The value round-trip gate** (M2): the judge of every lean writer and of the typed seam on every lane.
2. **`OutputMatrixTest` byte for byte**: the judge of the legacy writer; a deliberate difference is a register row.
3. **PCT and the corpus, test by test, every lane, no lane below its previous count**; the floors move only at step
   4's re-baseline, in the GATES entry that names it. **Both judges agree** on every lane (R10).
4. **Two shrink-only censuses:** database-written result text (M1) and `LITERAL` slots (R6), both zero by step 6.
5. **`writersAreCarriageOnly` and `WriterCarriageGuardTest`** from step 3 on; **R9's dependency query guard** from
   step 1 on.
6. **The size and time table** (M5) re-measured at steps 4 and 7: Snowflake's standard suite at or below 269 s; no
   PCT statement over 5 KB from output text; Snowflake's served statement for `|1.5 + 2.25` at 44 characters.
7. **No production method returns a materialized result** (R8), from step 7 on.

## 7. Order, dependencies, size

```
step 0 (1 wk) ─▶ 1 (3 d) ─▶ 2 (2 d) ─▶ 3 (1 wk) ─▶ 4 (1 wk, lanes) ─▶ 5 (1 wk) ─▶ 7 (1-2 wk) ─▶ 8 (ruling)
                                                          └─▶ 6 (2 wk, beside 5 and 7; may start after 1 if Deephaven is the priority)
```

Before step 0: land the current branch on `main` (every lane at or above the merge-base but `testFilterInstance`,
which main loses too; Snowflake standard +21% same-hour, the TDS text wrap this program removes). The program
starts from `main`, on its own branch, with nothing else on it: the last merge of `main` into this branch cost 39
conflicts, and the two text pipelines this program deletes are exactly where other lines' edits land.

Step 3 before step 4 because the legacy writer's cell spellings are the same cell reads `ValueBridge` needs, proven
once against bytes. Step 6 is independent after step 1 and the longest. The Deephaven line needs steps 1 and 6 and
nothing else from here.

Seven to nine weeks of one session's work, the lanes' turnaround included. The deletion is about 3,500 lines of
Java plus one render arm per dialect, the four kinds of output text out of every statement on every lane, and one
of the two decoders.

## 8. The rulings

Ruled 2026-10-26 ("everything else is okay"): **1** D1 to D5 as written in RESULTS_AND_RENDERERS §2; **2** D3's
charter clause (R3) with its two guards, the writers registered carriage sites in `JavaEvalLedgerTest`; **5** lean
JSON writes exact JSON numbers with the column types beside the rows, Arrow for any reader that needs exactness in
JavaScript; **6** collecting into memory is a test helper and the judges' writer, never a production method, and the
old executor goes at step 7; **7** the database judge is never deleted (R10; the engine-style dialects stay parked);
**8** the order of §7, mixed lists in parallel after step 1 if Deephaven is the priority.

Sent back for the expert version, given here, waiting on the user's yes:

**3. A Float, the version that is best on every side and makes the lean experience good.** Three places, three
answers.
- *In a result.* A Float is its 64 bits, never a text: the DOUBLE slot, the driver's exact read, Arrow's float64.
  Nothing is lost, nothing is spelled, no database is involved. Where a lean writer must spell it (JSON, CSV), it
  writes the shortest decimal that reads back as the same double: 0.1 is `0.1`, a third is `0.3333333333333333`,
  never `0.10000000000000001` and never a lost digit. That routine is pinned (M7): the JDK's `Double.toString` from
  JDK 19 on, or our own shortest-digits routine (about 300 lines, no dependency) if the pinned JDK is older. Decimals
  print at their own scale; integers exactly; NaN and the infinities as named strings in JSON and natively in Arrow.
- *Inside a query* (`toString`, `format`, a String built from a Float). One spelling everywhere, so the same program
  gives the same String on every database: the shortest round-trip digits in Pure's layout. Nine engines write it
  with one cast and a layout fix (DuckDB, Postgres, H2, ClickHouse, Trino, MariaDB, MySQL, Databricks, MongoDB);
  Deephaven is Java and writes it natively. Snowflake and BigQuery cannot (measured: sixteen digits and a
  mis-rounded `%.17g`), so they compute the exact digits in SQL today, about 5 KB per Float printed, correct.
- *Making those two cheap without giving anything up.* A temporary JavaScript function in the session:
  JavaScript's `Number.toString` is the shortest round-trip form by its specification, so one line gives the digits
  exactly, and the same function lays them out as Pure does. The statement then spells a Float as one call. M8
  measures it before it is adopted: byte-exact against Java's shortest over the 1,946 doubles, the speed per 10,000
  rows against the expression, and the privileges a temporary function needs (the session already creates temporary
  tables for a `let`, plan path R11, so temporary objects are an accepted pattern). Adopted only where it is exact
  and faster; the expression stays where it is not. Nothing in Java, one spelling, same SQL for both writers.
- *Rejected and why:* a fifteen- or sixteen-digit standard (lossy by design on the lossless engines, and Snowflake's
  own fifteen is double-rounded); Snowflake's sixteen digits as a per-database exception (one database would answer
  differently; reconsidered only if M6 shows the case is common and M8 fails); fixed seventeen digits (shows binary
  noise on ordinary values, which is why every modern runtime moved to shortest).

**4. Arrow: where the dependency would sit, and the answer that it sits nowhere.** The compiler never sees it: the
planner has no execution dependency (C2a, pinned), and the WebAssembly planner takes only `//core:plan_side`. The
execution side does not take it either: the batch is our own typed column arrays, and the Arrow IPC writer is the
warehouse server's existing framer, written by hand, already inside the native image, already checked by pyarrow in
its tests. Step 2 moves that framer to a shared dependency-free module; step 5's `ArrowWriter` uses it over our
batches; on DuckDB the warehouse's native path hands over DuckDB's own buffers as it does now. The Arrow Java library
and pyarrow remain test-only readers. R9 pins it with a Bazel query guard over the planner, the WebAssembly planner
and the native image. What this gives up against an Arrow-library-inside design: zero-copy from JDBC sessions that
could hand out Arrow (BigQuery's storage read, the Snowflake and Databricks drivers), which would need the library;
if a measured gap ever justifies it, it is an optional module beside the drivers, never in the compiler or the
native image, and never the default.

## 9. Status

- **2026-10-26, revision 3, ruled in part.** The user's rulings recorded (§8): items 1, 2, 5, 6, 8 as written; the
  database judge stays (R10); the Float rule and the Arrow placement answered with the expert version (R5 with M8,
  R9 with the warehouse's framer), waiting on the user's yes. Next: the yes, then landing the branch on `main`, then
  step 0.
- **2026-10-26, the handoff written:** `docs/RESULT_BOUNDARY_START_HERE.md`.
- **2026-10-26, revision 2.** After the user's three questions: batches as the unit; collecting into memory a test
  helper, never a production method; M4 and M7 added to step 0.
- **2026-10-26, revision 1, proposed.** Written after the Snowflake Float-text measurement (RESULTS_AND_RENDERERS
  §10.5) closed the last open fact under D7.
