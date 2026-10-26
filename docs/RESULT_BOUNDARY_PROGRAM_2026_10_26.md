# The result boundary program: a typed plan, host renderers, the text path deleted

**Status: PROPOSED, 2026-10-26, for the user's ruling.** Written at the user's ask ("this is now becoming a huge
blocker to our streaming/deephaven/real-time ambitions; how can we move this forward sanely AND delete all our
historical cruft"). It is the execution plan for `docs/RESULTS_AND_RENDERERS_2026_10_22.md` (D1 to D6, ratified in
part: D6 and §5a on 2026-10-23/24) and the plan path program's steps 7 to 11 (`docs/PLAN_PATH_PROGRAM_2026_10_17.md`
§7), made one program with one order, one measurement first, and one deletion list. Nothing here is built. What it
asks the user to rule is in §8.

## 0. Why this is the blocker, in numbers

A result today is, on most routes, **text the database writes**: Pure's TDS text for PCT, legend-engine's JSON rows
for the served API, CSV, a JSON array. The statement carries the formatting:

| `|1.5 + 2.25`, characters of SQL (2026-10-22, `eee1e560`) | DuckDB | BigQuery | Snowflake |
|---|---|---|---|
| the typed base query | 42 | 71 | 44 |
| PCT's TDS text | 1,687 | 11,029 | 48,288 |
| the served `pure/v1` JSON | 78,959 | 320,970 | 5,973,345 |

Ten million rows on DuckDB (2026-10-18): typed columns 10 to 12 s, the JSON rows wire 50 to 61 s. Snowflake's
standard suite ran 325 s against a same-hour merge-base of 269 s, the whole difference the text wrap. Every new
engine (Snowflake and BigQuery in October; Deephaven now) has to re-derive Pure's bytes in its own SQL before a single
test passes, and the two lossy engines needed a 5 KB expression per Float to do it.

For a streaming consumer the shape is wrong, not just slow. The Deephaven line (`docs/DEEPHAVEN_START_HERE.md` on
`claude/deephaven-3-browser`; its tenet DH27, "a change may take time; a tick may not") sends the plan to the user's
own Deephaven server and follows typed columns as they tick, through Deephaven's own client. A result that is "a string
the plan returns" cannot tick: there is no row to update, no column to type, and nothing in legend-lite between the
server and the browser to run the text through. The tick path is typed rows or nothing.

So the plan must stop returning text. That is one decision with four consequences, and they are this program.

## 1. Tenets

Each tenet is one sentence, then the mechanical guard that keeps it (AGENTS.md: guard files are the rules' form).

- **T1. The database computes every value; the host derives none.** Unchanged (the execution tenet; the charter's
  Clauses 1 to 6). Guard: `JavaEvalLedgerTest`, `ArchitectureTest.theInterpreterPerformsNoJdbc`.
- **T2. The plan is typed, never spelled.** Every result column carries three facts (the Pure type and multiplicity,
  the SQL slot, the store's declared type or its absence), and the plan carries no output format and no spelling. A
  Pure-only fact (a Decimal's scale, a DateTime's written precision, a tagged winner's slots) travels in the plan or
  in the data, never in a text. Guard: `PlanJsonTest` (format pin), `PlanMakerTest.aColumnCarriesItsThreeTypeFacts`,
  the plan round-trip gate (`testcases/PlanRoundTrip`); new: the planner makes no `TextResult` (§5, P1).
- **T3. Serialization is carriage.** A renderer (sink) spells the typed value it received, losslessly, in its
  consumer's layout; it never evaluates: no arithmetic, no comparison, no conversion between kinds, no default for a
  null, no zone shift, no Pure spelling rule chosen on the host. The deletion test: swap the renderer and every value
  is unchanged. Guard, new: `ArchitectureTest.sinksAreCarriageOnly` (the sink package depends on the plan's column
  facts and the typed cell, on no `compiler`, `lowering`, `sql` or `exec` class) and `SinkCarriageGuardTest` (a
  source scan of the sink package for arithmetic and comparison on numeric and temporal types, zero allowed; the
  shape of `NoSaltedIterationOrderTest`). The clause is D3's, to be written into `docs/TENET_CHARTER.md`.
- **T4. Two renderers, one result, one statement.** LEAN writes lossless typed output (Arrow IPC, JSON, CSV) with no
  promise of spelling; LEGACY writes legend-engine's bytes (`pure/v1` JSON) and feeds PCT and the corpus through
  `ValueBridge` with no text at all. The same plan, the same SQL, the same typed rows (D6). Guard: `OutputMatrixTest`
  byte for byte for LEGACY; the value round-trip gate for LEAN (§6); `PctBackend` runs no text route.
- **T5. Text that is a query's answer stays in the query, lossless by rule.** Kind A (`toString`, `format`,
  `joinStrings`, a String built from a Float): the database writes it, as the shortest round-trip decimal in Pure's
  layout, with the database's own text where its cast writes shortest digits and the exact-digits expression where it
  does not (Snowflake, BigQuery; measured 2026-10-21 and 2026-10-24). This is D7 (§8). It is the only text the
  database writes for a result. Guard: the PCT suites; a dated register row for any deliberate difference.
- **T6. A value SQL has no type for rides as a typed encoding with a plan fact, never as a spelling.** A mixed
  selection's winner is a tag plus typed slots (§5a, landed); a mixed list is parallel typed arrays (step B, P5);
  a partial date is its own kind with its precision in the slot's kind. The `LITERAL` slot and `LiteralText.parse`
  go when the last spelling carrier goes. Guard: `TaggedSelectionTest` and its successors; a shrink-only census of
  `LITERAL` slots in the test corpus's plans, pinned at today's count, zero at P5.
- **T7. Measure, shadow, switch, delete, in that order, per lane.** Nothing is deleted until its replacement has
  produced the same bytes (LEGACY) or the same values (LEAN, PCT) on every lane, the gate pins it, and the old path
  has been off for one full lane run. No parallel path survives its switch "just in case" (plan path R8). Guard:
  `docs/GATES.md` entries per switch; the census of database-written text (§4, M1) shrink-only to zero.
- **T8. A result is a stream of typed rows with sinks.** The runner holds one row; a materialized list is a sink
  like any other (S1 to S5, ratified 2026-10-18). A tick is a row. Guard: `TenetRatchetTest` (JDBC accessor sites at
  21), the exec package's closed register in `JavaEvalLedgerTest`; new: no `List<String>` result API in `Execution`.

## 2. The architecture, once

```
Pure query ──▶ PLAN: the base statement + one column record per result column (type, multiplicity, slot, store)
                 │        no output format, no spelling, no renderer in it (D1)
                 ▼
            EXECUTE: the database computes everything with meaning (kinds A, B, C of RESULTS_AND_RENDERERS §0.2)
                 │
                 ▼
            TYPED ROW STREAM: the session reads each cell by the plan's facts (slot first), one row held (S2, S4)
                 │
     ┌───────────┼──────────────────────────┬──────────────────────────┐
     ▼           ▼                          ▼                          ▼
 LEAN sinks   LEGACY sink               ValueBridge (PCT, corpus)   the tab's own reader
 Arrow IPC    legend-engine pure/v1     legend-pure's values,       DataCube on DuckDB-in-the-browser,
 JSON, CSV    JSON, byte for byte       no text                     and on Deephaven (its client types the rows)
```

The right-hand consumer is why the plan is the only contract that matters for real time: on the Deephaven route no
Java of ours runs between the plan and the grid. What it needs from this program is exactly T2 and T6, the plan's
facts and the typed encodings; its grid is a LEAN sink in TypeScript under T3's rule.

## 3. Where things stand (honest)

| Piece | State today | Program step |
|---|---|---|
| Three facts per column, typed tree in the plan JSON (format 10) | landed (2026-10-17/18; tagged slots 2026-10-23) | kept |
| The typed seam: `ExecutionPlan.TdsResult`, `PlanRunner.rows`, `JdbcDecode`/`RowDecode` by the plan's facts | landed; used by the typed callers and `TaggedSelectionTest` | P1 makes it the only result |
| `ExecutionPlan.TextResult` with `Format {CSV, JSON, JSON_PER_ROW, TDS_TEXT}`; `TypedQuery.Output` as a planning input | live: PCT channel A (`TDS_TEXT`), the served routes (`JSON_PER_ROW`, `JSON`, `CSV`) | P1 to P3 retire; deleted at P4 |
| `WireRender` (177), `Render`'s text wires (in 1,360), `TdsTextWrap` (268), `FloatPrintText`'s engine layout (in 269), `LiteralSpelling.floatPrintJava` and `dateTimeTextEngine` (in 1,052), each dialect's `FLOAT_PRINT_ENGINE` arm, MariaDB's and MySQL's `floatLayout` | live | deleted at P2/P3 |
| `PureV1Api` builds the envelope in Java and takes the rows as the database wrote them (`executeWire`: a `List<String>` of lines) | live | P2 |
| `ValueBridge` builds legend-pure's values, lists and class instances from typed values; a relation arrives as TDS text | live (the plan route since 2026-10-18/19) | P3 adds the relation from typed rows |
| `Executor` (1,195, the legacy executor) and `ExecutionResult` (materialized) | live behind `Execution.execute` | P6 deletes (plan path step 11, S1) |
| `Fold.conformValueEgress` (re-types a value root's egress; skips tagged columns since 2026-10-23) | live | P4 deletes with the text wires |
| `LITERAL` slot, `LiteralText.parse`, the Any-kind carrier, mixed `sort` | live for lists | P5 |
| Arrow | not in the build (2026-10-18: only on a measured gap) | P4, on the ruling in §8 |
| Kind A Float text on Snowflake and BigQuery | the exact-digits expression (correct; ~5 KB per Float printed) | stays (T5, D7) |

## 4. Measurement first (M0): one week, nothing built

Every number below is produced before P1 starts and recorded in `docs/GATES.md` under one entry, "The result
boundary program, M0". Each is the baseline a later phase is judged against.

- **M1. The census of database-written text.** For every plan the test corpus makes (the integration suite, PCT's
  five suites on the platform's engine, the corpus, `OutputMatrixTest`), which result node it is (`TdsResult` or
  `TextResult` by format) and which consumer asked. One table; the `TextResult` count becomes the shrink-only pin of
  T7 and must read zero at P4. Local. Half a day.
- **M2. The round-trip gate, built and run (RESULTS_AND_RENDERERS §6.1).** Every Pure kind of §5's table through
  every carrier: each local database's driver read, then Arrow and JSON once they exist. Compared by value, never by
  text. Its failures are the real list of P1 and P2 work (time zones, BigQuery's BIGNUMERIC scale, nested values per
  driver). Local first, the clouds through the lanes. Two days.
- **M3. Host rendering speed.** 10 million rows on DuckDB: typed rows plus a Java JSON writer, plus a CSV writer,
  against today's database-written wires (the 2026-10-18 numbers: typed 10 to 12 s, JSON wire 50 to 61 s). The
  2026-10-18 design has no measurement of this; P2 must not switch without it. One day.
- **M4. Statement size and lane time per suite, the baseline.** Per lane and suite: the largest statement, the count
  over 5 KB, wall time. Already in the lanes' histories for Snowflake, BigQuery, Databricks; collected once into one
  table with the local lanes. Half a day, mostly the lanes.
- **M5. Kind A's frequency.** How many statements in PCT standard and essential, the corpus and the Deephaven line's
  74 trading queries convert a Float to text inside the query, per lane. Decides whether D7's Snowflake clause ever
  needs a cheaper form. Local plus one question to the Snowflake lane. Half a day.
- **M6. What legend-engine prints for a Float's `toString` on Snowflake and BigQuery** (its own SQL for `toString` on
  those stores, from the reference checkout). Decides what LEGACY's byte parity means on a lossy engine: Pure's text
  or the engine's. A read, half a day.

M0 ends with §5's table re-sized from the numbers, not from estimates.

## 5. The phases

Each phase has the same five beats (T7): **measure** (its M-items), **build**, **shadow** (both paths run, every lane,
the difference counted), **switch**, **delete** (in the same phase, never "later"). A phase lands as one GATES entry
and one status line in this file's §9.

### P1. One plan, one result node (D1)

- Build: the planner makes a `TdsResult` for every relation and value root; `TypedQuery.Output` leaves the planning
  input (`TypedQuery.plan`, `PlanMaker`, `ExecuteOptions`, `Diagnostics` take no format); `PlanJson` format 11 writes
  no `TextResult`; the plan round-trip gate reads it back. The stream API: `Execution.rows(...)` answers the typed
  stream with sinks, and `executeStreaming` is a sink; `executeWire`'s `List<String>` is marked for P2's deletion.
- Shadow: none needed (the typed node exists and is gated); the text callers keep their path until P2/P3.
- Delete: nothing yet (the `TextResult` node stays until its last consumer switches, P3).
- Gate: `PlanJsonTest` 11, `PlanRoundTrip`, the core lanes, M1's census showing every planner-made result typed.
- Size: small (two days).

### P2. The LEGACY sink: legend-engine's rows in Java

- Build: `com.legend.sink.PureV1Sink` (the package name is the implementer's; one package, under T3's guard): a row's
  cells as legend-engine spells them, from the plan's facts and the typed cell: a Float by `Double.toString`, a
  DateTime by the nine-digit `+0000` form at the plan's precision, a Decimal at its own scale (S97), a tagged winner
  by its slot's kind, an Integer past 64 bits as digits; the envelope `PureV1Api` already writes. CSV and the JSON
  array the same way, per row, streamed (S2). `OutputMatrixTest` is the fixture: 192 cells on DuckDB and H2, the
  bytes of today's route.
- Shadow: every `OutputMatrixTest` cell and every served-route integration test run both ways; zero byte differences
  or a dated register row each (as S97). M3's speed number beside it.
- Switch: `pure/v1` execute, the streamed JSON and CSV routes read the typed stream through the sink. `jsonPlan`'s
  `run` (the tab running the server's wire SQL) becomes the base statement plus the plan's columns: the tab is a LEAN
  sink and types its own rows (it already does for Arrow and DuckDB-in-the-browser).
- Delete: `WireRender`; `Render.jsonWire`, `csvWire`, `jsonWireRows`; `LiteralSpelling.floatPrintJava` and
  `dateTimeTextEngine` with `SqlFn.FLOAT_PRINT_ENGINE` and every dialect's arm for it; MariaDB's and MySQL's
  `floatLayout`; `Execution.executeWire`; `Format.JSON`, `JSON_PER_ROW`, `CSV`. Snowflake's served statement goes from
  5.9 KB to 44 characters; MySQL's and MariaDB's from megabytes to the base query.
- Gate: `OutputMatrixTest` unchanged byte for byte; the served routes' tests; `sinksAreCarriageOnly`; the local gate.
- Size: medium (a week), M3 included.

### P3. PCT and the corpus through `ValueBridge`, from typed rows

- Build: `ValueBridge` gains a relation built from typed rows (its column facts to legend-pure's TDS column types;
  each cell by its kind; a tagged winner by its slot; a list by its typed array once P5 lands, by the identity channel
  until then); `PctBackend` runs the typed stream and hands rows to it; `PctPlanRoute`'s text path and
  `PctShadowPlan` go (plan path R8's referee overlap ends here).
- Shadow: channel A both ways on the platform's engine and MongoDB locally, then each lane (Snowflake, BigQuery,
  Databricks; the server lanes through channel B's floors): the pass set must be identical test by test, and any test
  that moves has its cause named before the switch (a difference here is a bug, never a register row: RESULTS_AND_
  RENDERERS §4).
- Switch: lane by lane; re-baseline each lane's counts in `docs/GATES.md` (the merge rule's floors move only here,
  with the entry naming this program).
- Delete: `TdsTextWrap`, `Render.tdsText` and its per-row form, `Format.TDS_TEXT`, `ExecutionPlan.TextResult` and
  `Format` themselves, `ResultType` where only text used it, the TDS-text output of `KeyedPivot` (its probe stays).
  The `TextResult` census reads zero (T7).
- Gate: PCT five suites exact on the platform's engine and MongoDB; every lane at or above its previous count; the
  statement-size table (M4) re-measured: no PCT statement carries output text; Snowflake standard back to the merge-
  base's time or better.
- Size: medium (a week; the lanes' turnaround dominates).

### P4. The LEAN sinks: Arrow IPC, JSON, CSV, lossless

- Build: `ArrowSink` (one Arrow schema from the plan's columns: Integer past 64 bits and a Decimal beyond Arrow's
  types carried as the plan says, decided in M2), `JsonSink` (64-bit integers and Decimals exact: as JSON numbers
  where the consumer's parser keeps them, as strings where it does not; the user's ruling, §8), `CsvSink`. The
  Python route (`arrowPlan`) and the tab's readers consume them. Arrow is a session capability on DuckDB (the typed
  method over Arrow batches) if M3 shows the gap; a sink everywhere else.
- Shadow and gate: the value round-trip gate (M2) over every sink and every kind; no bytes to compare.
- Delete: `Fold.conformValueEgress` (the typed root needs no egress re-typing once no text wire reads it).
- Size: medium (a week).

### P5. Kind B's lists onto typed arrays (step B of §5a)

- Build: a mixed list (`sort` over mixed kinds, the Any-kind carrier, a mixed collection at a root) as parallel typed
  arrays with a tag per element; the decoders read the tag then the slot, as the tagged winner's do; the judges' canon
  per slot. Partial dates (year, year-month) as their own kinds with the precision in the slot's kind.
- Delete: the `LITERAL` slot, `LiteralText.parse`, the spelling carrier's remaining machinery in `MixedEncoding`
  (`rankedElems`' text ranks) and the dialects' literal-text arms. T6's census reads zero.
- Gate: `TaggedSelectionTest`'s successor for lists; the PCT suites; every lane's channels A and B.
- Size: large (two weeks; the design is the ratified §5a's, extended).

### P6. The legacy executor and the materialized result

- Build: `Execution.execute(...)` answers through the plan and the stream (the one back-half sequence G½ to K is the
  plan's); the server's `execute` (still on the legacy path, the 2026-10-17 audit) switches; `ExecutionResult` is a
  collector sink for the tests that still want a list (S5), then goes where they switch to assertions on the stream.
- Delete: `Executor` (1,195 lines), `StatementExecutor`'s legacy order, `ExecutionResult`; `JavaEvalLedgerTest`'s exec
  register shrinks by those rows; `TenetRatchetTest`'s JDBC accessor pin drops with `Executor`'s reads.
- Gate: the integration suite (about 4,000 tests) on the plan path; the corpus; the local gate.
- Size: medium to large (one to two weeks; mostly test migration).

### What stays, by name, and why

Kind A's text rules in the lowerings and dialects (`toString` inside a query is the query's answer: T5); kind C, the
judges' canon (`CanonicalRenderSql`, `FLOAT_CANON`, `VerdictSql`: channel B compares inside the database, R2); kind B's
encodings until P5 types them; the `EngineStyle*` dialects (legend-engine's SQL text parity, a different contract from
results; its own question, not this program's); the exact-digits Float text on Snowflake and BigQuery (T5).

## 6. Gates for the program

1. **The value round-trip gate** (M2), the judge of every LEAN sink and of the typed seam on every lane.
2. **`OutputMatrixTest` byte for byte**, the judge of the LEGACY sink; a deliberate difference is a register row.
3. **PCT and the corpus, test by test, every lane, no lane below its previous count**; the floors move only at P3's
   re-baseline, in the GATES entry that names it.
4. **The two shrink-only censuses:** database-written result text (M1) and `LITERAL` slots (T6), both zero by P5.
5. **`sinksAreCarriageOnly` and `SinkCarriageGuardTest`** from P2 on: the sink package can hold no evaluation.
6. **The size and time table** (M4) re-measured at P3 and P6: Snowflake's standard suite at or below the merge-base's
   269 s; no PCT statement over 5 KB from output text; Snowflake's served statement for `|1.5 + 2.25` at 44 characters.

## 7. Order, dependencies, size

```
M0 (1 week) ──▶ P1 (2 days) ──▶ P2 (1 week) ──▶ P3 (1 week, lanes) ──▶ P4 (1 week) ──▶ P6 (1-2 weeks)
                                                       └──▶ P5 (2 weeks, in parallel with P4 and P6)
```

P2 before P3 because the LEGACY sink's cell spellings (Float, DateTime, Decimal, tagged) are the same cell reads
`ValueBridge` needs, proven once against bytes. P5 is independent after P3 and is the longest; it runs beside P4 and
P6. The Deephaven line needs P1 and P5 (the plan's facts and typed encodings) and nothing else from here; it does not
wait for P2 to P4 and P6, which are the server's and the harnesses' concern.

Seven to eight weeks of one session's work, the lanes' turnaround included; the deletion is about 3,500 lines of Java
plus one render arm per dialect, and the four kinds of output text out of every statement on every lane.

## 8. What the user rules now

1. **D1 to D5 as written** in RESULTS_AND_RENDERERS §2 (D6 and §5a are ratified).
2. **D3's charter clause**, T3's sentence, with its two guards named in §1. The renderers are registered carriage
   sites in `JavaEvalLedgerTest` (one row per sink, shrink-only on their count), not residue.
3. **D7, kind A's Float text** as T5 states it: the shortest round-trip decimal in Pure's layout; the database's own
   text where its cast writes it, the exact-digits expression where it does not; one SQL for both renderers; no 15- or
   16-digit standard. The measured record is RESULTS_AND_RENDERERS §10.5.
4. **Arrow enters the build** (a Java Arrow dependency, `ArrowSink`), on this program's ground rather than a measured
   gap: the streaming consumers are the reason. Or: Arrow stays out until a consumer other than Python's route asks.
5. **LEAN JSON's integers and Decimals**: exact as JSON numbers (readers that keep 64 bits and decimals do; JavaScript
   does not) or as strings. The tab is the first reader; its answer decides.
6. **The materialized `ExecutionResult` goes in P6**, with `Executor`; the ~4,000 integration tests assert on the
   stream's collector. (Plan path R8 already rules deletion; this names the step.)
7. **The order of §7**, or a different one.

## 9. Status

- **2026-10-26, proposed.** Written after the Snowflake Float-text measurement (RESULTS_AND_RENDERERS §10.5) closed
  the last open fact under D7. Next: the user's rulings on §8; then M0.
