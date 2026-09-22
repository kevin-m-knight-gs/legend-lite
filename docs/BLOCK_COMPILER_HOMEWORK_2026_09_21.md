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

## 14. Stage 1 LANDED (2026-09-21): the pure body compiles to its artifact before it runs

**Measured first.** Assert-family statement roots the arms did NOT claim (opened, every arm
null, fallen through to host evaluation): **0 on both lanes** — every assert root yields a
verdict, defers a row, or raises. The compiler has no host arm to inherit.

**What landed.** `BodyCompiler` (core, `com.legend`, registered in the root-class register):
`accepts` names a PURE body (every statement a let without effects or an assert-family root
without effects, the last an assert; no test-data generator; no assertError); `compile` walks
it ONCE and only plans — the census fold, the contexts established (provisioning precedes
planning: a frame's reported wire types are read from the seeded tables), frames planned,
handles registered, asserts adjudicated into deferred rows with their appeals — using the
executor's own arms in the executor's own order; `run` sends the artifact (one fused statement
per connection, appeals on failed rows, first failure raises) and returns the body's value.
`StatementExecutor.executeStatements` dispatches pure bodies to it under the database judge;
every other body (effects, helper-wrapped asserts, value-position lets) walks the loop until
stages 2–3.

**Judged.** The twelve ladder pins byte-identical; DuckDB database lost 0 / gained 0,
outside-body 232 exact, host-compared 102 exact; H2 database registers exact (329 / 100);
chain green. One lesson during the rung: three test-data-generation bodies lost until the
census fold joined the compiler's walk — the loop's per-statement steps are the walk, all of
them, in order.

**What stage 1 does not yet do:** order by dependency (stage 2, with compile-time inlining of
helper-wrapped asserts and the fragment map), scripts for effect bodies (stage 3), delete the
loop and the fallback re-execution (stage 4, after H2's vocabulary — rung 2c).

## 15. Two standing rules (user questions, 2026-09-21)

**Phases, not walkers.** A body is walked by resolve, by type, by the program-facts pass, and by
the executor loop — the last two ask the same questions twice, and the loop decides while
running. `BodyCompiler` (stage 1) is a SCAFFOLD: it duplicates the loop's arms so the pins could
prove byte-identical output, and while it stands the database judge has two walkers for one
body. The end state has ONE plan pass that also knows the shape and the effects, a render pass,
and a runner that does not walk at all (it sends the artifact and maps errors through the
fragment map). Therefore: **every stage from here removes a walk or merges two, never adds
one.** Stage 2 merges helper inlining into the plan pass; stage 3 makes effect bodies the same
pass with segments; stage 4 deletes the loop and the program-facts walk into the plan pass. A
stage that proposes a new pass is wrong by construction.

**"No fallback" is not "no host compare."** No fallback holds: under the database judge the
host never rescues a verdict (unclaimed assert roots measured 0). Host compares remain in three
places, each counted: (1) the host judge mode itself, the verdict of record, by definition;
(2) under the database judge, the host-compared register — 162 asserts in 102 tests (DuckDB),
140 in 100 (H2), two database-computed sides compared in Java (lineage, TDG, identity,
metadata) — shrink-only, task #14's list; (3) the referee's row comparison on a failed text row,
the test lane's judge, Java until rung 2b puts the canon in SQL on both sides (option A).

## 16. Task #14, leg 1 (2026-09-21): the lineage tree is judged as lines — the golden canon at compile time, no SQL of the arm's own

**The user's ruling:** a comparison in Java over two database-computed sides IS a fallback,
whatever the sides are; "no fallback" holds only when the host-compared register is empty.
Task #14 is therefore the next legs, before compiler stage 2, largest arm first.

**The first cut, rejected.** The arm's tree-print → rows query (`TREE_ROWS`) was raw DuckDB
SQL (`string_split`, `generate_series`, `struct_pack`, `regexp_extract`) run through a
`RawSql` source — outside the two chartered raw-SQL seams, and DuckDB vocabulary on the H2
lane (the 49 lineage tests sat on the H2 fail roster with `Function "STRING_SPLIT" not
found`). User: "Why is something in h2 mode using duckdb anything?" Nothing on the H2 lane
may use DuckDB-specific SQL; the arm was rebuilt before it was committed.

**What landed instead — a golden canon, not a verdict arm.** The engine's tree print is
`buildUniqueName(alias = true)`: its join labels spell the engine's decorated SQL aliases
(`_d#N`, `_dy<i>`, `_m<N>`, `_l`, `_r`, `_md`, duplicate counters), an artifact of its SQL
generation the row charter retired; the tree's content is the `alias = false` form, the
relational element's name. So the statement-root `assertEquals(<print>,
$tree->relationTreeAsString(…))` is REWRITTEN at compile time (`compiler/spec/LineageTreeLines`,
the compiler layer — typed nodes are minted nowhere else) into the ordinary collection assert
`assertEquals([<line>, …], $tree->relationTreeLines(…))`:

- the golden's lines parsed by the print grammar in Java (indent, kind, name, join label,
  columns), every decorated alias in a label resolved to the node name the tree itself declares
  (longest name first), re-rendered as the line the prelude prints;
- ours the prelude's own Pure body `meta::lite::lineage::relationTreeLines(t, withJoin)` — one
  text per node in preorder over the handle's `LineageRows` (`relationTreeAsString` is now
  `relationTreeLines(…)->joinStrings('', '\n', '\n')`, one rule);
- the ordinary verdict decides: a verdict row of the body's statement (a literal grid against
  the planned relation) under the database judge, the host judge in host mode. Same rows in
  both modes, no Java compare anywhere, no SQL of the arm's own on either lane.

Deleted: `LineageTreeVerdicts` (the root-package arm, its raw query, its side statement),
`VerdictSql.rawTextPair`, `CanonicalDivergence.lineageRows`, the ArchUnit exemption that let
the arm reach the host-verdict classes, the SQL-text ratchet's lineage site.

**The seam pinned at zero.** `StatementOrigin.hostSeam` (moved out of `CanonicalDivergence`, a
host-verdict class the verdict seam alone may reach) counts values the executor evaluates in
Java at the store-navigation seam with no statement sent. Measured 0 on both lanes under the
database judge; `MinimalCorpusTest` now FAILS on any seam value in database mode. The seam
itself (`hostChannel` / `StoreNav.owns` / `hostEvalAtSeam`) is deleted when its other users
are measured gone (compiler stage 4).

**Measured.** Four lanes GREEN. The 49 lineage tests PASS on H2 under both judges (H2 fail roster 413 → 364 — the first lineage rows off that roster; DuckDB 109 exact). Host-compared register DuckDB 102 → 53, H2 100 → 51; outside-body DuckDB 232 → 183, H2 −49 → 280 (the arm's `side` statements gone: DuckDB side 414 → 207); host-seam 0 tests / 0 values on both lanes, pinned. Statement origins (database judge): DuckDB body 2,563 · side 207 · fallback 1; H2 body 2,349 · side 205 · fallback 146 (the H2 verdict vocabulary, rung 2c). Differential agree 5,848 · disagree 0. Strength LITERAL DuckDB 1,406 / H2 1,243 (+49 each: the lineage lines are literal verdict rows now).

**Java on the database path — the full inventory (user question).**

| what Java does | where counted | status |
|---|---|---|
| compares two database-computed sides | host-compared register: 53 tests DuckDB / 51 H2 | shrinking, TDG fetch text next (19), then instances (22), plans (10) |
| compares our rows with the golden's on a failed text row | the referee (test lane) | out of scope by decision; SQL canon on both sides in rung 2b (option A) |
| brings a golden literal to its canon rows at compile time (JSON, rendered text, lineage lines) | the compiler layer (`VerdictQueries`, `LineageTreeLines`) | the canon rule on the spec cell, by design — never on ours |
| renders plan text and SQL text (five natives) | staged as constants at compile time | the compiler's own renderer, by design |
| evaluates a value at the seam with no statement sent | `StatementOrigin.hostSeam` — **0 on both lanes, pinned** | none |
| reads a verdict row's boolean, maps an error | the runner | not a judgment |

The host judge mode is Java throughout, by definition — the verdict of record.


## 17. Task #14, leg 2 (2026-09-21): the host-compared register reaches ZERO — every assert is a verdict row

**What was left after leg 1 (53 tests DuckDB / 51 H2), attributed per assert** by a one-run
trace at the batch's flush (a root flushed without a verdict row, named):

| shape | tests | how it was decided |
|---|---|---|
| TDG fetch text (`assertSqlEquals(golden, $testData.sqls->at(n))`, the H2Compatible spelling) | 19 | both texts folded, the REFEREE replayed the fetch on the oracle and compared rows in Java — before any text verdict |
| foreign-dialect `toSQLString` text (DB2, Postgres, Sybase, SybaseIQ, "unresolved" driver pairs) | 22 | `textEqual ? ok() : fail(…)` in Java (a counted decline: no oracle for the dialect) |
| plan text with unbindable parameters (enum-parameter plans, `planToString`) | 6 | `textEqual ? ok() : fail(…)` in Java (decline `plan-params-unbindable`) |
| `assertEq` (a metadata cell, a Float precision value, a count) | 4 | the router had NO database-mode branch for `eq`: `PureAsserts.assertEq` in Java |
| quantified `coll->map(x \| assert(pred))` over a database-computed collection | 2 | the predicate VECTOR computed in the database, the all-true fold in Java |

**What landed — one rule, no new walker.** Each shape defers what it already had in hand:

- **TDG fetch text** (`SqlTextVerdicts.tryArmTdgSql`): under a batch the text equality is a
  verdict row of the body's statement; the referee's replay (`tdgRowsNow`) is the row's APPEAL,
  run at the flush only when the row failed. Host mode: option 1 (byte-equal text is the
  verdict; the referee on a failed text only).
- **Text-decided declines** (`textVerdict`): the foreign-dialect and plan-params-unbindable
  declines defer the same text row; the arm's own message is raised when the row fails. The
  decline stays counted (`declined(...)`) — the referee still cannot appeal these.
- **`assertEq`**: routed to the database verdict for primitive pairs (eq on primitives IS
  equals); a class-instance pair keeps the host's LOUD identity wall.
- **Quantified assert**: the predicate vector is PLANNED (`planSide`) and
  `VerdictSql.allOf(vector, wantTrue)` returns the one verdict row — no row said otherwise.

**Measured.** Four lanes GREEN; fail rosters unchanged on both lanes (DuckDB 109 exact, H2 364 host / 356 database); differential agree 5,848 · disagree 0. HOST-COMPARED REGISTER: DuckDB 53 → 0, H2 51 → 0 — EMPTY and exact on both lanes. Outside-body DuckDB 183 → 179, H2 280 → 277 (the `assertEq` and quantified sides no longer sent apart). Statement origins (DuckDB): body 2,563 → 2,578, side 207 → 193. Host-seam 0/0. Text-decided ceiling foreign-dialect:Composite 8 → 9 on both lanes: tds::sort::testSortQuotes loops over drivers — its DB2 text assert used to raise in Java at the assert; as a row it is judged at the flush, so the later Composite assert is reached and counted (the DB2 row fails at the flush as before).

**What "zero" means (user question, 2026-09-21).** Under the database judge no assert of the
corpus is decided by a comparison in Java over database-computed sides: every assert root
flushes with a verdict row; the register is empty on both lanes and exact (any new row fails
the lane). What remains in Java on the database path is exactly the inventory of §16: the
referee as an APPEAL on a failed text row (test lane), the golden canons at compile time, the
compiler's own renderers staged as constants, the seam at zero (pinned), and the runner
reading the row. Compiler stage 2 (dependency order, compile-time inlining, the fragment map)
starts on that footing.

## 18. Compiler stage 2 (2026-09-21): compile-time inlining, the value segment, the fragment map — every effect-free body is one artifact

**Measured first (the ratchet named before the leg).** A refusal census on the stage-1 tree:
`BodyCompiler.accepts` now records WHY a body is not compiled as one artifact
(`[corpus2] body-compiler accepted=… refused={…}`, attributed per test in
`target/corpus2-body-compiler.txt`). Beside the effect bodies (stage 3), **91 tests** were refused:
helper-call roots 27 (`test(…)`, `runLegendTest(…)` wrappers), `println` / `print` statements 23,
a trailing `true;` 17, a trailing `$a == $b` value 12, quantified `->map(x | assert(…))` roots 7,
`->forAll(…)` 2, an `if` over asserts 2, one `createTempTable` root.

**What landed — the loop's own phases, shared, not a new walker.**

- **`StatementExecutor.prepareValue` / `runValue`.** The loop's value-statement tail (helper
  inlining, native staging, the inlined root's re-classification, store resolution, the
  cross-store wall — then execution) is split into its compile phases and its run phase, ONE
  implementation: the loop calls the pair at once; the compiler prepares at compile and runs at
  the artifact's run. The phases keep the loop's exact order (computing the statement's widened
  environment BEFORE store resolution changed one lowering — an order dependence in the state
  the inliner touches, measured and owed, not fixed here).
- **The compiler accepts every effect-free statement**: lets (a trailing let is the body's
  value), assert-family roots (a verdict call, a quantified map / forAll, an if over asserts —
  whatever `AssertVerdicts.tryAdjudicate` claims, the same call), helper calls (inlined at
  compile time by the shared preparation; an inlined assert root is adjudicated there), value
  statements (prepared at compile, run in body order before the fused send). Refused: a
  test-data generator, an effect (stage 3), a context owner (assertError runs its body under an
  arm's catch), a frame forced at value position, and an UNPORTED native at a statement root.
- **The unported-native rule (found by the register, not guessed).** `ddl::dropAndCreateTempTable`
  (on the fail roster on both lanes) compiled and failed one statement LATER: `createTempTable` is
  a prelude-typed native in no family (no body here, loud at evaluation), and the raw-grid read
  after it (`executeInDb('select * from tt', …)`, a late-bound relation) PROBES the table's schema
  at plan time — a state the unported statement would have created. The compiler must not plan
  past a statement it cannot run: a root outside the implemented surface (the claim registry's
  own question — a family member, a scalar rule / reducer / window function by signature key, a
  core function by bare name; a walled native is refused by decision) is refused, and the loop
  keeps it. One body in the corpus.
- **The fragment map.** `Artifact.fragments`: every frame (`frame_<let>`) and every verdict branch
  (`__ix=<n>`) → the let / assert and its statement ordinal; carried by the batch, and a database
  error on the fused statement names the frame it mentions (`VerdictBatch.attribute`) in the
  fallback census row. Data now; load-bearing when stage 4 deletes the split rung.
- **A census correction.** The referee's PAGE-POPULATION read (the unpaged rows for a paged
  chain's page-membership verdict) is the referee's, marked `REFEREE_OURS` like its rows read —
  22 tests left the DuckDB outside-body register on that alone (their only `side` statement).

**Measured (DuckDB database).** Refused non-effect tests 91 → 1 (`unported-native:
createTempTable`); accepted bodies 2,434 per run (top-level test bodies plus the fixture bodies
that pass through the same entry); outside-body register 179 → 157 (exact); side statements
193 → 168; fail roster 109 exact, lost 0 / gained 0; host-compared 0; differential agree 5,848 ·
disagree 0; the twelve ladder pins byte-identical. H2 database: outside-body 277 → 255 (the same 22 referee population reads), fail roster 356 exact, host-compared 0, refused non-effect tests 1; body statements 2,364, fallback 146 (the H2 verdict vocabulary, rung 2c, unchanged).

**What stage 2 does not do.** Effect bodies (754 refusals per run, fixtures included) stay on the
loop until stage 3 (scripts). The value statements are lowered inside `executeTyped` at the
artifact's run — the run phase still lowers; stage 3 makes them planned statements of the
script. The fragment map is inert until the split rung goes (stage 4).


## 19. Stage 3 homework (2026-09-21): effect bodies as scripts — probes, the baseline, the decisions

**Decision (user, 2026-09-21): one send per segment.** An effect body compiles to an ordered list
of segments; the runner sends each EFFECT segment as one script (its raw / DDL statements, as
written, adapted per dialect) and each VERDICTS segment as one fused statement, reading its rows
at once. A verdict segment is planned after the effects before it have run, because two facts are
still read from the live session at plan time — a frame's wire types after seeding and a raw
read's schema. So the 108 non-interleaved effect bodies become TWO sends (effects, then verdicts),
the 33 interleaved ones two per alternation. Parking the verdict rows in a scratch table for one
send per body waits for static wire types (a later leg); the raw read's schema stays inherent.

**Probed (DuckDB 1.4.4.0, H2 2.4.240; `$CLAUDE_JOB_DIR/tmp/probe/ScriptFail.java`, `ScriptTx.java`).**

| question | DuckDB | H2 |
|---|---|---|
| a script stops at the first failing statement, earlier ones stay applied | yes (rows before the failure = sequential) | yes |
| the last statement's result set comes back from `execute(script)` | yes (ONLY the last; earlier result sets are dropped) | NO — a script returns no result set through `execute` |
| the error names the failing statement | no (the message names values / columns, not a position) | yes (`SQL statement: INSERT INTO t VALUES ('x')`; a later failure quotes the script from the failing statement on) |
| a failed segment rolls back as a unit under `BEGIN … COMMIT` | yes, DDL included (the table is gone after `ROLLBACK`) | DML only (DDL commits: the table stays, the rows roll back) |

**What the probes decide.**

- Verdict statements are their own sends on both lanes (H2 cannot return a script's result;
  DuckDB returns only the last) — the option-1 shape, no per-engine branch.
- **The raw-statement ledger** (`Recorder.recordExecuted(sql, query)`: the referee's mirror replays
  its non-query entries; "a failed statement is never recorded") keeps its invariant per engine,
  decided INSIDE the dialect: on DuckDB an effect segment runs as one transaction — all applied
  and all recorded, or rolled back and none recorded; on H2 the failing statement is read from the
  error and the statements before it are recorded (DDL cannot roll back there). The query kind is
  decided statically by the boundary's own first-keyword rule (`RawSql.QUERY_KEYWORDS`, the rule
  the Typer already uses to type an `executeInDb` literal as a relation).
- **Error attribution for an effect segment**: the fragment map names the SEGMENT (its statement
  ordinals) on DuckDB, the statement on H2. A test failing inside an effect segment fails as it
  does today; only the message's precision differs per engine.
- **All-or-nothing on DuckDB changes the session state a FAILING test leaves behind** (today its
  statements before the failure stay applied). Measured before the leg: the tests whose failure is
  inside an effect statement, and whether any later test in the same package session depends on
  the partial state. Measured (DuckDB database lane, 109 failures): NO failure is a write statement's own — the three `Catalog Error` rows are a raw READ of a missing view (`testRelationStoreAccessorOnView`, the lane's one fallback), a referee rows leg, and the unported `createTempTable` lowering. All-or-nothing on a failed segment changes no session state in the corpus today.

**The baseline (the ratchet named before the leg).** Both lanes, database judge, per-test statement origins over the 141 effect bodies (33 interleaved):

| | DuckDB | H2 |
|---|---|---|
| product-owned sends inside the 141 today (raw + verdict + side + statement + probe) | 13,863 (98.3 per body) | 13,838 (98.1 per body) |
| of which raw statements sent one by one | 13,484 | 13,464 |
| sends after stage 3 (one per segment: an effect run = one script, a verdict run = one statement) | 311 (2.2 per body) | 311 |
| outside-body register rows whose reason includes `raw` | 139 | 139 |

The four heaviest bodies seed 318–455 raw statements each (the milestoning and test-data-generation
tests); they become 2–3 sends. The test-data generators' own statements (`tdg` 1,220) run at the
census fold and are not segments. Fixture bodies (the 754 effect refusals per run include them) go
through the same entry and become scripts too, but the seeding census (`seed` 110k) is the harness
template copy's number, not this leg's.

**What stage 3 changes and what it does not.** The cut rule is unchanged (`containsEffect`, the
catalog, transitively). The artifact gains `Effect` segments (statement lists) beside the verdict
batch and the prepared value statements; `BodyCompiler.refusal` stops refusing effects (a
test-data generator, a context owner, a frame forced at value position and an unported native
still refuse). The value statements still lower at the artifact's run. The split rung stays for a
failed fused verdict statement (stage 4). Fixture bodies pass through the same entry and become
scripts too — the seeding template copy (COPY FROM DATABASE) is a separate harness leg.

## 20. Compiler stage 3 LANDED (2026-09-21): effect bodies are scripts — one send per segment

**What landed.** `BodyCompiler.execute` is the SEGMENT WALK, one pass over the body: lets, asserts
and value statements accumulate into the open VERDICTS segment (frames and rows on its batch, values
prepared); an effect statement closes it (its values run in order, its batch flushes as one fused
statement) and is COLLECTED into the open EFFECT segment; the next non-effect statement sends that
segment as ONE script first; a test-data generator's fold sends the pending script before it folds
(it reads the state). The effect natives keep their compile work (raw text split and adapted, DDL
rendered from the model, CSV spelled as inserts) and lose their send: `StatementExecutor.sendEffect`
is THE ONE send — collected into an `EffectSink` when one rides the environment (`ExecEnv.effectSink`),
executed at once otherwise (the loop's behavior, unchanged). `sendScript` sends a segment as one
script (`Executor.executeScript`, one round trip) and records its statements for the referee's
ledger: all on success; on a failure the ones the engine names as applied (H2 quotes the failing
statement; `SqlDialect.failingStatement`), none when the segment rolled back as a unit.

**Two things the lanes taught, both recorded here because they cost a red run each.**

1. **The harness owns a transaction.** The referee's ATTEMPT protocol turns autocommit off on the
   session connection for an effect body (committed on a pass, rolled back on a failure). The
   dialect's script bracket (`BEGIN TRANSACTION … COMMIT` on DuckDB, transactional DDL) nested inside
   it: "cannot start a transaction within a transaction", 137 tests. The bracket applies only when the
   send OWNS the transaction (`Executor.ownsTransaction`: autocommit on); inside a caller's the
   statements run bare and the caller unwinds — the harness's rollback IS the all-or-nothing.
2. **A failed bracket must be closed.** The probe rolled back by hand; the executor did not, and every
   later script on the connection was refused. `SqlDialect.scriptAbort()` (DuckDB: `ROLLBACK`) runs
   when a bracketed script fails.

**Refusals.** Effects and generators are no longer refusals. Remaining: a context owner
(assertError), a frame forced at value position, an unported native at a statement root (1 body).

**Measured (database judge, both lanes).**

| | DuckDB | H2 |
|---|---|---|
| raw statements sent one by one (`raw` origin) | 13,484 → 0 | 13,464 → 0 |
| effect statements that rode inside scripts (fixture bodies included) | 126,922 | 126,902 |
| the body's own sends (`body`: fused verdicts + scripts) | 2,578 → 6,555 | 2,364 → 6,339 |
| outside-body register | 157 → 103 (side 93 · tdg 34 · statement 33 · probe 5 · fallback 1) | 255 → 202 |
| referee mirror seeds replayed (the ledger intact) | 75,023 (unchanged) | 185,812 (unchanged) |
| fail roster / lost / gained | 109 exact / 0 / 0 | 356 exact / 0 / 0 |
| H2 verdict-vocabulary fallbacks | — | 146 (unchanged, rung 2c) |

The `seed` census fell 110k → 412: the fixture bodies' seeding did not disappear, it rides in scripts
(the same statements, counted in-script); the 412 are the runtime-declared setups the session
provisions on its own. Round trips for the whole DuckDB database lane (every statement the executor sent: setups, sides, frames, referee replays): 134,691 → 11,746 — the lane's wall time is now the seeding's parse work and the referee, not the sends.

**What remains on the register (103 / 202).** `side` (the value sides the referee's appeal and the
zip / identity arms still evaluate apart), `tdg` (the generators' own statements at the census fold),
`statement` (value statements: prepared at compile, still their own sends — stage 3's value segment
as a planned script statement is owed), `probe` (raw-grid schema reads), and the one fallback.

**Owed next.** Static wire types for modeled tables (one send per non-raw effect body); rung 2c (H2
vocabulary); stage 4 (delete the loop, the seam, the split rung; the fragment map load-bearing);
the value statements as planned statements; rung 2b (referee canon in SQL); the seeding template
copy; the order dependence of §18.


## 21. Cleanup leg 1 (2026-09-21): the verdict seam split — router, host judge, database judge

**The audit that ordered it** (user, 2026-09-21: "a serious deep audit"; measured from 13b6a7ceb,
the program's first judging commit, to a033ff6fa): product +8,037 / −1,218 lines with ONE file
deleted; `AssertVerdicts` 2,543 → 3,313 lines holding both judges with 16 mode forks and 34 Java
compare sites; two walkers both live (the loop for host mode and refused bodies, the compiler
for the rest); 19 static census counters across seven classes; 27 register files (4,714 rows,
7 empty); let-binding lookup reimplemented seven times; `ExecEnv` a 16-component bag; string
decisions added (error-message parsing in two places, a package-prefix match in
`isVerdictFunction`); pins moved rather than code (one ceiling, four ledger bumps in a day).
Real outcomes (no Java compare under the database judge, one artifact per body, round trips
134,691 → 11,746) — banked only when the old paths go. The user's standing decision: the host
judge is NOT deleted; it becomes one arm of one system and gains the compiler's work.

**Move 1 (this section): the split, verbatim.** `AssertVerdicts` (the router: classification,
the shared readers, the dispatch) → 2,188 lines; `DatabaseJudge` (both sides planned, one
verdict statement, the row read) 550 lines; `HostJudge` (both sides executed, Java compares the
rows — the verdict of record in host mode) 635 lines. Every member moved with its comment
block by exact brace-matched ranges; members both judges use stayed in the router
(package-private); the `SideRows` record went with the database arm, the `SideFetch` /
`Framed` / `SqlVerdict` records with the host arm. No behavior change: the ladder pins
byte-identical, the four lanes exact, the differential unchanged (agree 5,848 · disagree 0).
Guardrails: both classes in the root-class register with per-file ledger pins (host 423,
database 439; the router 2,459 → 1,627), the V3 reachability rule names both arms, the host arm is a registered judge caller; the root
`java.sql` pin and the JDBC census name the database arm TEMPORARILY — it makes no JDBC call, it
carries the Connection as a routing key in three signatures it took verbatim (the side's
connection, a constant bound on its partner's database, the verdict run's target). User
question: "why does it need java.sql" — it does not; move 2 routes by the side's environment
and both rows go.

**Move 2 (next): one dispatch.** The 16 forks (`if (databaseMode(env)) yield DatabaseJudge.x(…)`
then the host body inline) become one arm chosen once per adjudication: each host body a
`HostJudge` method with the database method's signature, the switch yielding `arm.x(…)`.
Then the host arm can consume the compiler's artifact (host mode through `BodyCompiler`), which
is what makes the loop deletable in both modes.

**Then, in order:** one census owner (the 19 counters behind one class, one snapshot for the
lanes; the seven empty registers collapsed into one must-be-zero list); one let-binding lookup
and one callee-name helper in the compiler layer; stage 4 (delete the loop, the seam, the split
rung — not the host judge); port `createTempTable` / `dropTempTable` and delete the
unported-native gate.

**Move 2a LANDED (2026-09-21): the database judge off `java.sql`.** `SideRows.on(env)`,
`runVerdict(…, runOn env)`, `constantSide(…, partner env)`: the judge routes by the side's
environment and the batch reads the connection from it. No `java.sql` in the class; the two
temporary register rows of move 1 are gone. Owed from the same audit: the router's four `java.sql`
value arms (JDBC Array / Timestamp / Date decoding in `decodeSideValues`) belong in the exec funnel.

**Move 2b LANDED (2026-09-21): one dispatch.** `VerdictArm` — one method per assert family —
implemented by `HostJudge.ARM` and `DatabaseJudge.ARM`; the router names the arm once
(`arm(env)`), classifies, hands the sides over. The switch's thirteen host bodies moved verbatim
into `HostJudge`, its database blocks into `DatabaseJudge`; mode forks 16 → 1. Router 3,313 →
1,722 lines across moves 1–2b. No judgment changed: four lanes exact, ladder pins byte-identical.
Next: move 2c, host mode through the compiler (the host arm consumes the artifact), then the
census owner and stage 4.

**Move 2c LANDED (2026-09-21): host mode through the compiler.** The compiler dispatch no longer
tests the judge mode: every accepted body walks the segment walk in both modes; under the host judge
the walk has no batch (the host arm judges at the assert, values run in walk order — the loop's
order, kept exactly) and effects become scripts. The host judge is one arm of one system — the
promise of the program's first day ("could the host judge integrate?") held: DuckDB host-lane round
trips 141,422 → 18,477, raw sends 13,484 → 0, host rosters exact. The loop now serves only the three
refused shapes; stage 4 deletes it.


**Move 3 LANDED (2026-09-22): one census owner.** `Census` (exec): one `Key` per count, keyed
families for runtime names (statement origins, refusal reasons), one snapshot. The 28 counter
storages the audit found across eight classes are deleted; each site increments a key where the
fact happens. One exception by rule, not by convenience: the scan-order pass counts inside the
standalone SQL layer (it may not reach `exec`) and the census reads through to it. Every census
line the lanes print is identical to move 2c's run; four lanes exact; no verdict reads a count.
Next: one let-binding lookup and one callee-name helper in the compiler layer, then stage 4.

**Move 4 LANDED (2026-09-22): one let-binding lookup, one callee-name helper.** `Lets` (typed
package): `binding` / `binds` / `bound` / `bare` / `byName` — the let in scope is the last one that
binds the name; the chase is lexical. Eleven hand-rolled prefix walks, six trailing-let idioms and
`ExecuteChainAssembly.letBound` are gone (its callers name `Lets.bound`). `Calls`: callee FQN and
arguments of either call kind, once; the copies in `StoreElementIdentity`, `ContextReading`,
`BodyCompiler` and the router deleted. Every census line identical; four lanes exact. The cleanup
list from §21 is now: stage 4 (loop, seam, split rung), then the temp-table natives.

**Stage 4 LANDED (2026-09-22): the loop and the seam are deleted.** `executeStatements` =
`BodyCompiler.execute`; the loop (130 lines), `hostChannel` / `hostEvalAtSeam` / `StoreNav` (seam
measured 0 / 0 in all four lanes) and the compiler's `accepts` / `refusal` gate are gone. One wall
stays by the agreed order: an unported native at a statement root refuses the body BEFORE planning
(the first cut without it let the walk plan past `createTempTable`, and the raw read's schema probe
became a statement outside the artifact — one register row, rosters exact; the wall put the
loop's order back by construction). Rosters exact, census identical. The split rung stays until
rung 2c (H2: 146 firings, all vocabulary; DuckDB: 1, a product FAIL row). Next: port the temp-table
natives (1 row × 4 lanes) and delete the wall; the router's java.sql value arms; rung 2c.

**Temp-table port LANDED (2026-09-22): the last wall is gone.** `createTempTable` / `dropTempTable`
= EFFECT natives spelling the dialect's own `CreateTable(temporary)` / `DropTable` from the
`^Column` literals' types; the engine's string-builder argument is never called (DDL is SQL, the
dialect renders). `BodyCompiler.wallUnported` + `implemented()` deleted: the walk has no gate left.
Exposed and fixed by construction: a let binding a raw read is schema-stamped AT the let (engine
runs `executeInDb` there), before later effects. H2 lanes PASS the row (364 → 363); DuckDB: `col`
vs the golden's H2-folded `'COL'` — a ruling owed to the user (accept as an engine-golden H2-ism,
precedent h2-literal-coercion). Next: that ruling; the router's java.sql value arms; rung 2c.

**JDBC carrier arm LANDED (2026-09-22).** The list wire arriving as one JDBC array cell is decoded to a `Collection` at the executor's scalar read; the router reads values and holds no `java.sql` (F1.3b pin {Compiler, StatementExecutor}). Next: rung 2c (H2 vocabulary) and the split rung; the DuckDB temp-table ruling.

**Self-audit corrections LANDED (2026-09-22, user "Go").** Datatype classes by exact FQN (`PlatformTypes.DATATYPE_*`); the DDL-builder argument rule MEASURED over the engine's five callers (all the spec's builders; stated at the arm, not assumed); the array cell through the one `unwrap`; and the contested row was a FIX: `DuckDb.ddlIdentifier` folds an unquoted identifier to the SQL-standard uppercase identity (a declared-quoted name keeps its case) — `dropAndCreateTempTable` passes on all four lanes (DuckDB 109 → 108). Open with the user: the outside-body register row (`probe=1`) for that body.

**Literal folding LANDED (2026-09-22).** Census first: 167 of the 168 side statements were literal-only (85 seed CSV texts, 80 SQL goldens, 2 integers), 1 read a table. `Literals` (typed package) folds exactly those shapes; `evalValue` folds after the inliner and `evalStringArg` folds its argument; a side a canon rider rides is never folded (the canon text is the database's). Side statements 168 → 1 on both database lanes; registers regenerated (DuckDB 104 → 45, H2 203 → 144); rosters and every judging line identical. Next: the zip-over-frame arm (`testProject`, the last side), then rung 2c homework.

**forAll / zip relational LANDED (2026-09-22).** An assert inside a quantified lambda is the predicate it means (`VerdictQueries.assertAsPredicate`); `zip` at row position is a join on the row number (`CollectionRelations.zipRows`, the list form kept for list-valued arms); `forAll` plans through the existing quantified vector. THE VECTOR CONTRACT, read off the 47 bodies the first cut broke (all `createTableRowIdentifiers`): a row source, a row-local predicate, a literal message at the verdict function's own position — anything else keeps the unroll. Side statements 0 on both database lanes; registers DuckDB 44 / H2 143; rosters and differential unchanged. Next: the view-accessor expansion (one owner: `ViewRelation`, applied to `tableReference` calls naming a view); the console meaning of println; rung 2c homework.
