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
