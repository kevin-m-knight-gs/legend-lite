# Block compiler homework — one artifact per test body (2026-09-21)

**The question (user, 2026-09-21):** now that setup and seeding are known to be a package
thing, how do we make sure a test function is compiled as ONE unit and run as ONE unit, no
exceptions — and is it tractable, do we lose anything, what does it mean for seeding.

**Decisions taken in the discussion (user-ratified):**

1. The SQL-text referee is the test lane's judge, never the product's: OUT OF SCOPE for the
   one-unit rule. The register counts product-owned statements only.
2. The rule is ONE ARTIFACT per body, not one statement: a pure body compiles to one verdict
   statement; an effect body compiles to one SCRIPT (effects in order, verdict segments where
   the body asserts between effects). Forcing effects into a SELECT would be the hack.
3. Statement-by-statement execution is REMOVED, not kept beside the compiler. A REPL is a
   sequence of blocks over a session environment (earlier lets as frames); a cell whose last
   expression is a value is the value-position case we already have. Two paths = two
   implementations of Pure semantics (the harness lesson; "one router, one evaluator").
4. The ruler stays the corpus: the artifact must reproduce the engine's verdicts (stop at the
   first failing assert, errors named), which segmenting gives by construction.

Everything below is MEASURED on the DuckDB and H2 corpus lanes in database judge mode
(2,613 tests each), with the instruments landed today: the body-shape census
(`ProgramFacts.shape`, `target/corpus2-body-shapes.tsv`), fallback reasons
(`VerdictBatch.FALLBACK_REASONS`, `target/corpus2-fallbacks.tsv`), the raw-vs-generated seed
split (`StatementOrigin.SEED_GENERATED`), and the prepare/execute trace (`LEGEND_LITE_PREP_TRACE`).

## 1. What a body looks like

One letter per statement: `F` a let bound to an execute (a frame), `L` another let, `A` a verdict
call, `X` an assertError, `E` an effect (raw statement, DDL, a test-data generator), `O` other.

| fact | value |
|---|---|
| bodies | 2,613 |
| pure (no E, no X) | 2,472 (95%) |
| effectful | 141 |
| effects AFTER an assert (interleaved) | 33 — all test-data-generation and plan-execution tests |
| most common shapes | `FAA` 412 · `FAAA` 372 · `FA` 237 · `LA` 140 · `LLLLA` 111 · `LAA` 84 · `LLA` 81 · `FLAA` 76 |
| statements per body | median 4, max 476 (a test-data-generation body: `LLLLLEEE…OALAL…`) |
| asserts per body | max 24 |
| frames per body | max 12 (`FLAA` × 12: `mapping::extend::propertyMapping::testPropertyMappingsForC`) |
| assertError bodies | the root-call detection found 0; 14 corpus files use `assertError` (a user call, not a native root) — the `X` letter is OWED |

**Reading.** 95% of bodies are pure expression blocks: the compiler model applies in full. The
141 effect bodies are scripts by nature; 33 of them need verdict SEGMENTS (an assert, then an
effect, then an assert again — "all effects, then the verdict" would be wrong for them).

## 2. How many statements a body sends TODAY (DuckDB, database judge)

| fused statements per body | bodies |
|---|---|
| 0 | 505 — 357 judged by the referee only (text asserts), 144 failing before a verdict, 14 skipped |
| 1 | 1,887 |
| 2 | 138 |
| 3–12 | 83 |

**Finding: 160 of the 221 multi-statement bodies are PURE.** They are split only because the
batch FLUSHES at every non-assert statement that follows an assert (`StatementExecutor`, the
host-channel rule "what came before is judged first") — a rule written for effects that fires
on a plain `let` too. `FLAAFLAA…` becomes 12 statements with no effect anywhere. A block compiler
orders by DEPENDENCY, not by position: those 160 become one statement with no other change.
The remaining 61 multi-statement bodies are the effect scripts (§5).

## 3. Size and planning cost of the fused statement (DuckDB, 2,577 executed)

| | median | p99 | max |
|---|---|---|---|
| characters | 3,427 | 112,862 | 1,133,071 |
| prepare (plan) | 1.3 ms | 68 ms | 574 ms |
| execute | 0.47 ms | 7.2 ms | 834 ms |

Total text 28.0 MB. The tail is literal-heavy bodies, not a property of fusion: the three
`meta::pure::tds::tests::extensions::*ValueDifference*` bodies inline ≈1.1 MB each of literal
TDS data; the `toPostgresModel` text asserts ≈400k each (large SQL literals). Everything else
is under 113k. Fusion does not create a size problem; literal relations spelled as text do — a
separate question (literal data as VALUES in the text vs bound parameters), unchanged by the
compiler.

## 4. Why a fused statement falls back today

DuckDB: 1 body (`Catalog Error: Table with name personView does not exist` — a product row on
the roster surfacing at the flush). H2: 61 bodies, 207 reasons across their groups:

| reason | count |
|---|---|
| variant navigation reached a dialect without JSON support | 68 |
| LIST_MIN reached a dialect without a list encoding | 46 |
| a struct extraction reached a dialect without struct support | 33 |
| UNNEST reached a dialect without an unnest placement | 26 |
| LIST_FILTER / LIST_TRANSFORM / LIST_GET without a list encoding | 13 |
| nested checked defects without list lambdas | 5 |
| STRING_AGG collection reduction without a list encoding | 3 |
| DataError (product H2 rows: missing columns, FLATTEN / MD5 / TO_BASE64, PERSONVIEW, `Nov1995`) | 13 |

**Reading.** The H2 fallbacks are the VERDICT VOCABULARY (lists, structs, JSON, unnest) with no
H2 spelling, not the fusion. The fallback path IS the interpreter path the compiler deletes, so
before deleting it H2 needs either those spellings or a documented segmented form for the shapes
that have none. DuckDB has no vocabulary gap.

## 5. Effects and the script artifact

141 bodies carry effects; the batch already splits at every effect today (that is where the
61 non-pure multi-statement bodies come from). The block artifact keeps exactly that structure
as ONE script: effect statements in order, a verdict segment wherever asserts precede the next
effect. Sequential inside the script, planned entirely up front — our compiler binds against the
MODEL (the tables an effect body creates are declared in its store), so no statement waits on the
live catalog, which is the one reason query engines cannot do this. Whether DuckDB's driver
accepts a multi-statement script in one round trip is a probe still owed; H2 does.

## 6. Seeding: what the compiler can and cannot do

| seed statements (per lane) | count |
|---|---|
| RAW text (a fixture's `executeInDb` blobs, a runtime's declared setups) | 110,646 (97%) |
| GENERATED from data or the model (CSV loads, dropAndCreate DDL) | 3,612 (3%) |

**Reading.** The compiler cannot reshape raw text, so multi-row inserts help 3% of seeding. The
lever is the HARNESS: 307 of 313 packages re-run the same ≈220-statement shared fixture into a
fresh database. Probe (DuckDB 1.4.4.0, this box): `COPY FROM DATABASE tpl TO ws` copies 1,000 rows
and a view in one statement, under 1 ms. Design: seed the shared fixture ONCE per lane into a
template database; each package starts as a one-statement clone and runs only its own setups.
≈67,000 statements → 307. H2 has `SCRIPT` / `RUNSCRIPT` (not probed). Independent of the compiler.

## 7. What we lose, and the mitigation each needs (design owed)

| loss | mitigation |
|---|---|
| stop-at-first-failure (the engine runs sequentially) | the verdict rows are ordinal; a script's segments honor order by construction |
| error locality (a database error names an alias, kills the whole body) | ERROR ATTRIBUTION: the artifact carries a fragment map (let / assert → alias prefix / branch ordinal) so a raised error names its statement — required before the fallback path is deleted |
| intermediate visibility (watching a let's rows) | a value-position cell in the REPL; a partial-artifact dump for debugging |
| all-or-nothing (no partial progress, one snapshot) | accepted for tests; scripts for effect bodies |
| host-only natives mid-body (clock, file, HTTP, no SQL rule) | hoisted to parameters bound before the statement — a CENSUS of such natives is owed |

## 8. The plan the numbers give

1. **The register** (small, first): every test whose body sends a product-owned statement outside
   its artifact, by name and origin, exact per lane, shrink-only. Referee excluded by decision 1.
2. **The pure-body compiler**: the body → one plan (lets as CTEs by dependency, asserts as rows)
   BEFORE anything executes; the executor only runs. 2,472 bodies; the 160 split bodies become
   one statement for free. This is the AssertVerdicts split (task #27) + the 3.5 deletions.
3. **Scripts** for the 141 effect bodies (33 segmented).
4. **Seeding template copy** in the harness (§6) — independent, largest statement cut.
5. **H2 verdict vocabulary** (§4) or the documented segmented form, then delete the fallback path.
6. Owed censuses before step 2 lands: the `X` shape letter, host-only natives, the DuckDB
   multi-statement probe, and the error-attribution design.

## 9. Rung 1 LANDED (2026-09-21): the batch flushes only before an EFFECT

**The rule.** `StatementExecutor.executeStatements`: the body's pending verdicts are sent before
a statement if and only if that statement has effects (`containsEffect` or a test-data
generator — a compile-time fact); a pure let or a pure statement rides to the next effect or the
body's end. Step 1 had flushed before EVERY let and before every non-assert statement.

**Measured.** Fused statements sent (DuckDB database lane) 2,578 → 2,167. Outside-body register:
DuckDB 394 → 248 (146 STALE, 0 NEW), H2 418 → 291 (128 STALE, 1 NEW — `groupBy::
testAggToManyWithFilter`, already failing on the JSON vocabulary gap; merged into one statement
its failure now passes through the fallback path once). Fail rosters exact on both lanes; the
DuckDB per-assert differential agree 5,848 · disagree 0 · unregistered 0; the ladder pins
byte-identical.

**The predicted loss, with a name.** A body whose first assert fails and which then has a let
used to raise at the flush before the let; now its later asserts evaluate before the body's end
raises the same first failure. `sqlstring::testSqlGenerationDivide_AllDBs`: one more foreign-
dialect (Composite) text-decided verdict on both lanes (ceilings 7 → 8, reasons at the pins);
`tds::sort::testSortQuotes`: one more referee pair. Same verdicts, same reasons, extra work on
failing bodies only.

**Two of the 160 stay registered, for the compiler.** `toPostgresModel::tests::testConvertAlias`
(`LLALLLALLA`): its asserts are wrapped in a helper (`assertConversion`), and each helper call is
inlined and judged through its own nested batch — one statement per call. Inlining at COMPILE
time (the block compiler's job) makes it one. `alloy::testAlloyTestDatGenWithQuotedColumnsForViews`
fails before its verdicts (a view-slice wall) and keeps two.

## 10. Rung 2a LANDED (2026-09-21): text asserts are verdict rows; the referee is their APPEAL

**The user's question that opened it:** "shouldn't these asserts go to the database fully with
the equal being string equality of the SQL text printed, and then the referee?" Yes — the 357
text-assert bodies were routed around the batch by an assert-shape recognizer (exact FQNs, but
still a second judge chosen by shape), and our plan's execution was filed under the referee.

**What landed.** `VerdictSql.textEquals(golden, ours)`: the text assert's own verdict row — the
golden text against our rendered text, string equality, both constants of the compiled body.
`VerdictBatch.Pending` carries an optional `Appeal`; at the flush a FAILED row with an appeal
asks it (returns on a pass by rows, raises its own failure), a held row needs none. Every text
arm converges on one function (`SqlTextVerdicts.rowsLegAndVerdict`): under a batch it defers
the row with the referee's rows leg attached as the appeal; outside a batch (the host judge)
it applies THE SAME RULE (below) and runs the rows leg only on a differing text.

**The ruling (user, 2026-09-21, option 1).** A text byte-equal to the golden IS the assert's
verdict — the engine's own — in BOTH judges; rows are the appeal on a failed text only. The
DuckDB-vs-H2 function divergences a held text used to expose (hash, week start — engine
golden defects on the accepted roster) belong to tests that assert VALUES.

**Measured (DuckDB database lane).** Bodies sending their fused statement 2,167 → 2,515 (+348:
the text bodies); referee runs of our plan 1,894 → 1,263 and golden replays 1,883 → 1,269 (a
held text needs no appeal); mirror seeds 95,481 → 75,023. Fail rosters: one NEW row on both
lanes (below); DuckDB 109 / H2 413 exact. Per-assert differential: agree 5,848 · disagree 0 ·
unregistered 0 — the two judges agree completely. Outside-body registers: DuckDB 248 → 232
(16 stale, 0 new after the flush rule), H2 291 → 329 (15 stale, 53 new — below).

**Rows and pins that moved, each with its reason at the pin.**

1. `executionPlan::tests::testTemporalDateVariableInFunctionExpressionWithPropagation` → the
   FAIL rosters (both lanes). Its plan-text rows leg reads the milestoning store, which its
   package never seeds and no single fixture is indexed for; text is the contract and ours
   differs. It PASSED before only because an earlier test's referee provided
   `objectReferenceIn::setUp` on demand, which also seeds the milestoning tables — a fixture
   inherited as another test's side effect. With the referee an appeal on a failed text, a
   passing earlier test provides nothing. Alone, it failed on every tree.
2. Accepted rosters −3 (DuckDB: `sqlstring::testHashFunctions`, `testToSQLStringForTDSStringJoin`,
   `testToSqlGenerationFirstDayOfWeek`; H2: the string-join one): text held → PASS.
3. Ord registers (8 → 4 / 7 → 4), unordered registers (1,381 → 920 / 1,314 → 875), DuckDB
   engine-order register (1,007 → 992): tags the referee's rows leg used to emit for tests
   whose text now holds. Strength floors: DuckDB differential 1,543 → 1,020, H2 1,387 → 953
   (passes now decided by the text row: LITERAL); spelling ceilings 60 / 67 → 22.
4. Outside-body: 14 take/limit/slice bodies stale on each lane (their text holds — no referee,
   no statement outside); the register's split rule is now FLUSHES, not statements (a body that
   asserts over the metamodel AND the session sends two statements in one flush and is not a
   cut); H2 +53 `fallback=1` rows: bodies already failing on the JSON/list vocabulary gap whose
   fused statement fails at render — the text row now runs alone through the fallback path
   (rung 2c owns the gap).

**Findings for the compiler.** A fixture provided on demand is a side effect of the REFEREE, not
a fact of the test (item 1): the runner should provide stores from the body's compile-time
facts, never from a judge. `toPostgresModel::testConvertAlias` sends two statements in ONE flush:
its helper-wrapped asserts carry two connection objects for the same session — the compiler's
inlining will make it one.

**Still outside the artifact after 2a:** the referee's golden replay (by decision), the 183
`side` bodies (a comparison decided in Java over two database-computed sides — the lineage, TDG,
identity and metadata-fetch arms: task #14's canon list), the 141 effect bodies (scripts), and
the assert-level pin "asserts decided outside a row, in database mode" (next, so the host-
compared remainder is one number that must reach zero).

## 11. The HOST-COMPARED register (2026-09-21): the assert-level number that must reach zero

**The user's question:** "are there still things that fall back to the host? how do we know?"
Two different things had been answered under one word. The verdict path has no host RESCUE
(a shape the canon cannot claim is unjudged and fails). But an assert whose arm compares two
database-computed sides in Java — the lineage tree, the test-data-generation fetch text,
identity, the metadata fetches — is a host judgment of the COMPARISON, and it was only visible
as a `side` row of the outside-body register (test-level, 183 tests).

**What landed.** `VerdictBatch.hostDecidedCount()`: an assert root the batch flushes WITHOUT a
verdict row was decided outside the database. The corpus lanes attribute it per test and pin
`rcorpus/<lane>-database-host-compared-register.txt` exact, shrink-only (`pinArtifactRegister`,
kind `host-compared`).

| lane | tests with a host-decided assert | asserts decided outside a row |
|---|---|---|
| DuckDB | 102 | 162 |
| H2 | 100 | 140 |

By family (DuckDB): lineage `scanRelations` 49 · test-data generation 19 · functions (metadata
fetch, sqlstring arms) 14 · mapping 8 · execution plans 6 · query 4 · tds 1 · groupBy 1. This is
task #14's canon list with names and a count; every canon written removes rows, and a new arm
that compares in Java fails the lane.

## 12. The owed items, measured (2026-09-21)

1. **The `X` shape letter (assertError).** Measured over the engine's relational sources: exactly
   ONE test calls `assertError` at statement level (`sqlstring::databricks::testCreateViewForDatabricks`)
   and it is not in this corpus. The letter stays for the shape's completeness; no body in the
   2,613 needs the raise-in-a-branch treatment today.
2. **Host-only natives.** The catalog's JAVA_ROUTINE rows are five: `planToString`,
   `planToStringWithoutFormatting`, `toSQLString`, `toSQLStringPretty`, `toNonExecutableSQLString`.
   They are STAGED at compile time into string constants of the body (`NativeDispatch.stage` —
   the compiler's own renderer producing the text) — constants, never a runtime host call. The
   other host seam is the metamodel navigation the executor evaluates at the seam
   (`StoreNav.owns`: store-navigation natives and `^Class(...)` constructions as values) — the
   35 `statement` rows of the census. Both fit the compiler model: constants and values of the
   compiled body, computed before the artifact runs.
3. **DuckDB multi-statement scripts.** Probed on 1.4.4.0 (this box): `Statement.execute("CREATE …;
   INSERT …; SELECT …")` runs the whole script in one round trip and returns the last statement's
   result; a PREPARED script works too. H2 accepts scripts. The 141 effect bodies can be one
   script each, sent once.
4. **Error attribution (design).** The artifact carries a FRAGMENT MAP: every CTE definition and
   every verdict branch records the let / assert it came from (name, statement ordinal, alias
   prefix or `__ix`). A database error names an alias or a branch; the map turns that into the
   statement, and the failure message names the let or assert — what the interpreter gave for
   free by position. DuckDB's binder errors name the alias (`frame_result__t0`, `t3.bookId`) and
   H2's name the column with its alias; both resolve through the map. Precondition for deleting
   the fallback path (rung 2c).

**Conclusion.** Nothing measured blocks the compiler rung. Its first step: the pure-body compiler
returns the artifact (frames as CTEs by dependency, verdict rows, the fragment map) BEFORE the
executor runs anything; judged on byte-identical ladder pins and the four lanes exact.

## 13. The compiler rung — design (2026-09-21)

**What exists.** The executor (`StatementExecutor.executeStatements`, 3,321 lines in the file)
walks the body statement by statement: at a let it plans a frame (`buildFrame` → `planBare`,
the frame's CTE is `defineFrame`d on the batch); at an assert `AssertVerdicts.tryAdjudicate`
plans the sides (`planValue`) and `defer`s a verdict row (`VerdictBatch.Pending`, an APPEAL for
a text row); at an effect the batch flushes (`VerdictBatch.flush` → `VerdictSql.batch` fuses
the pending rows over the frames — one statement per connection); at the body's end it
flushes. Every planning function is already pure of execution under a batch; what interleaves
them with execution is the LOOP, plus three things that read execution state: the value-
position run (`resultNeeded`), the metamodel navigation evaluated at the seam (`hostChannel`),
and the raw natives (`executeInDb` and the DDL natives).

**The artifact.**

```
BodyArtifact
  frames:    ordered (name → SqlQuery)            — CTE definitions, by dependency
  segments:  list of Segment                      — one per effect boundary, in body order
    Segment.Verdicts(rows: list of VerdictRow)    — fused into ONE statement per connection
      VerdictRow(ix, assertName, wantEqual, query, connection, appeal?)
    Segment.Effect(statement)                     — a raw / DDL native, sent as written
    Segment.Value(plan)                           — the value-position result (the body's value)
  fragments: (alias prefix | branch __ix) → (statement ordinal, let name | assert name)
```

A PURE body is one `Verdicts` segment; an effect body alternates `Effect` and `Verdicts`
segments and is sent as ONE SCRIPT (DuckDB and H2 both take a script in one round trip, §12.3);
the value-position result, when the body has one, is the script's last statement.

**The compiler.** `BodyCompiler.compile(resolvedBody, specs, env)` → `BodyArtifact`. It walks the
body ONCE, in order, and only PLANS: lets become frames (the existing `planBare`; a class frame's
extent rows per rung 12; helper calls inlined FIRST by the existing `UserCallInliner`, so a
helper-wrapped assert is an ordinary row of the same segment — the `testConvertAlias` residual);
asserts become verdict rows (the existing arms in `AssertVerdicts`, which today call
`batch.defer` — they will return the row instead); an effect statement closes the current
`Verdicts` segment and opens an `Effect` one; the seam-evaluated metamodel navigations become
constants (they are today, at the seam — computed before the artifact, like the JAVA_ROUTINE
texts). The fragment map is filled as each piece is planned.

**The executor.** `BodyRunner.run(artifact, env)`: for each segment in order — `Verdicts`: fuse
(`VerdictSql.batch`) per connection, execute, judge rows in order, appeals on failed rows, first
failure raises; `Effect`: send; `Value`: send and return. Nothing planned, nothing decided
here. A database error is mapped through the fragment map before it is reported.

**Staging, each judged by the ladder pins (byte-identical), the four lanes exact, and the
registers.**

1. `BodyCompiler` + `BodyRunner` for PURE bodies, behind the existing loop's outputs: same
   frames, same rows, same fused statement text — the extraction moves the seam, not the
   answer. The 2,472 pure bodies.
2. Dependency order and compile-time inlining: `testConvertAlias` leaves the register; the
   fragment map lands (errors name their let / assert).
3. Effect bodies as scripts: the 141; the register's `raw` rows leave.
4. Delete the statement-by-statement loop and the fallback re-execution (H2 vocabulary first,
   rung 2c) — the interpreter is gone.

**What the REPL gets for free:** a block of one or more statements compiled against a session
environment whose earlier lets are frames; a cell whose last expression is a value compiles to a
`Value` segment.
