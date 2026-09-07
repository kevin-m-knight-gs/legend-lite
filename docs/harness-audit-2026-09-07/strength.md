# What a pass proves — verification strength

The lane was reproduced with an independent driver over `MinimalCorpus` (no maven), and every
test's referee traffic instrumented by snapshotting `ReplayOracle.OUTCOMES` and
`H2Verify.UNVERIFIABLE_CENSUS` per test. Counts below are **measured** unless labelled.

**Headline: a pass in this corpus proves one of five very different things, and the run does
not distinguish them.**

---

## 1. The strength ladder

| # | Mechanism | Strength | Tests (of 2,454) | What a pass proves |
|---|---|---|---:|---|
| 1 | Our rows vs **engine golden SQL on a real seeded H2**, **plus** a literal assert | strongest | **1,198** | the answer is right two independent ways |
| 2 | Golden-row differential **only** (a golden-text assert upgraded to rows) | strong, single-witness | **296** | matches the engine's answer; nothing pins the text or an independent literal |
| 2b | Golden-row differential via a corpus helper (no assert callsite in the body) | strong | **17** | same as 2 |
| 3 | Our DB rows vs **literals typed in the corpus** | moderate | **825** (574 touch a database; 251 compile/plan-only) | equals a human-written expectation; no cross-engine witness |
| 4 | Only **cardinality/emptiness/boolean** asserts | weak | **25** | a row count, not a value |
| 5 | **SQL/plan TEXT byte-compare only** | spelling only | **39** | our emitter spells like the engine's — nothing about answers |
| 6 | **Zero adjudicated asserts** — passes because the body did not throw | none | **32** | nothing |
| — | of which **referee DECLINED and the pass fell back to text** (overlaps 1–5) | — | **19 tests, 24 events** | §3 |
| — | **vacuous** (`body == [true]`) short-circuit, `MinimalCorpus:417-419` | none | **2** (subset of 6) | nothing |

Totals: 1198 + 296 + 17 + 825 + 25 + 39 + 32 = 2,432, plus row 4's 25 disjoint → **2,454**.

**1,511 of 2,454 (61.6%) are backed by at least one real differential check.** That oracle has
teeth: **6 tests FAIL today on row divergence alone**, text notwithstanding.

Event-level: **5,437 assert verdicts** fired across the passing tests; **1,688 (31%)** were
backed by a golden-row comparison. Whole-sweep referee outcomes: `verify8 MATCH=1592,
DIVERGED=6, DECLINED=20; verifyFetchChain MATCH=49; verifyFetchTexts MATCH=23; verifyPlan
MATCH=28, DECLINED=4`.

**Denominator integrity is clean**: 2,721 `<<test.Test>>` functions under
`core_relational/relational` with comments stripped, 146 carrying `ToFix`/`ExcludeAlloy`,
2,721 − 146 = **2,575 exactly**. Nothing is silently dropped at discovery.

---

## 2. Five traced journeys

### J1 — `mapping::modelJoin::milestoning::testMilestoningSameScheme` (row assert + golden rows)

`testModelJoinMilestoning.pure:266`. Measured: `2 verdicts, {verify8 MATCH: 1}`.

| hop | code | what happens |
|---|---|---|
| discover | `MinimalCorpus:240-268` | `<<test.Test, test.AlloyOnly>>` — `AlloyOnly` is not an exclusion, so it runs |
| assemble | `:102-140` | all 543 corpus `.pure` files → one module; every `Database` bound to `rcorpus::Conn` |
| session | `beginSession:316-341` | inline CSV (`facts.seedsInlineCsv()`) → **private** workspace; mirror suspended so the referee replays fresh |
| seed | `runSetups:373-397` | package `BeforePackage` + shared fixture, through `Compiler.executeResolved` |
| execute | `judge:481-497` | `Compiler.executeResolved(resolved, ctx, RUNTIME, conn, listener, ReplayOracle.INSTANCE)` |
| assert | `AssertVerdicts` → `SqlTextVerdicts.tryArm:52` | one plain value compare; one carrying a `toSQLString` producer routes to the row leg |
| referee | `ReplayOracle.verify1:648` → `H2Verify.compareFrame` | golden H2 SQL on a fresh H2 seeded from the recorded ledger; rows compared |
| verdict | `SqlTextVerdicts:1286-1288` | `MATCH` → pass, "text is a census number" |
| score | `MinimalCorpusTest:75,113` | into `pass`; only `pass.size()` gated |

**Proves:** our milestoned join produced the same row *multiset* as the engine's own SQL over
identical seed data, **and** a literal expectation held. Strong. **Does not prove:** row order
(§4), nor that our SQL is the engine's SQL (text is demoted to a census number by design).

### J2 — `executionPlan::tests::tdsReturn` (golden TEXT assert, upgraded to rows)

`executionPlanTest.pure:1648`. Only assert is `assertEquals('<plan text with sql=…>',
$result->planToString(...))`. Measured: `1 verdict, {verify8 MATCH: 1}`.

The arm detects a plan-text producer by exact callee FQN (`SqlTextVerdicts.findProducer:1305-1330`),
then **discards the text comparison** and derives OUR ROWS by re-running the producer's own
query lambda through `evalValue`, GOLDEN ROWS from the golden's embedded SQL. `MATCH` → pass.

**Proves:** the plan we build answers the same rows as the engine's published plan. **Does not
prove** the plan text matches — a structurally different plan producing the same rows passes.
This is the design intent and it is the right call, but it means **296 passing tests whose only
assertion is a golden text assert are judged purely on rows, with no text pin and no independent
literal.** If the oracle ever declines for those, the test silently degrades to a spelling check.

### J3 — `graphFetch::tests::XStore::inMemoryAndRelational::testCrossMappingJsonToDB`

`testCrossStoreGraphFetch.pure:796`. Measured: `1 verdict, {}` — **zero referee events.**

`assertJsonStringsEqual` is adjudicated entirely in-process at `AssertVerdicts:473-513`: both
sides parsed by `com.legend.sql.Json.parse`, compared by `JsonCompare.document:45-49` — objects
by key set, arrays *ordered*, numbers by kind (`documentLeaf:104-111`). One documented leniency:
a one-element array on our side unwraps to match a bare-object golden (`:503-507`).

**Proves:** our cross-store graph fetch serialized to exactly the JSON the corpus author typed —
a strong, order-sensitive structural assertion, but a **literal-expectation** check, not a
differential one. **No graph-fetch test in the pass roster has any referee event.** The graph
verdict leg the plan discusses is not in effect on this lane.

### J4 — `functions::sqlstring::testGenerateDateDiffExpressionForH2ForDifferenceInDays` (DECLINED → text)

`testToSQLString.pure:315`. Measured: `1 verdict, {verify8 DECLINED: 1}`, bucket
`verdict-arm: datediff-to-now golden: oracle replay and our execution are at different instants`.
The verdict falls through `SqlTextVerdicts:1291-1298`:

```java
case DECLINED -> yield textEqual ? ok() : fail(...);
```

**Proves:** our emitter spells the SQL byte-for-byte as the engine does. **Nothing about the
answer.** Seven sibling `dateDiff` tests are in the same state (8 decline events in that bucket).

### J5 — `mapping::dates::strictdate::testProject` (rescued by order leniency)

Body: `assertSize($result.values.rows, 15)` and
`assertEquals([1,…,15, %2014-12-01, …], $result.values.rows.values->sort())`. Measured:
`verdicts>0, {}` — no referee; and it fired `[ord] row-tuple order-leniency-dependent pass`.

The `bare no-key sort() over flat cells` shape is routed at `AssertVerdicts:275-292` to
`tdsRowValuesSameElements:1057-1066`, judging by **cell multiset** via
`TdsCompare.rowTupleMultiset(e, a, 1)`. The `[ord]` instrument (`TdsCompare:100-108`) fires only
when the positional compare would have *failed* — so this test's `sort()` produced a different
total order than the corpus expects, and the multiset rescue passed it.

**Proves:** we returned the right 30 cells. **Does not prove** our `sort()` over a mixed
Integer/StrictDate pool orders like the engine's. Defensible (Pure has no cross-kind total
order) but a real hole in the one place a sort test should be strongest.

---

## 3. Where a pass is weaker than it looks

**1. Text-only: 39 tests**, concentrated in `executionPlan/tests/executionPlanTest.pure`. Named:
`testModelConnectionSimple`, `testExecutionPLanGenerationForFrom`, `tdsWithEnumReturn`
(`:1662`). Mechanism: `SqlTextVerdicts:1258-1273` — when the rows leg throws or is null the arm
returns `textEqual ? ok() : fail(...)` **without ever calling the oracle**, so these do not
appear in the decline census at all. **That is the invisible half of the weak bucket.**

**2. Zero-assertion: 32 tests.** `MinimalCorpus:513-520`:

```java
if (failure == null && verdicts.isEmpty() && facts.verdicts()) { failure = "no verdict: …"; }
…
return new Result(t.fqn(), true, verdicts.size(), verdicts.isEmpty() ? "ran, no asserts" : …);
```

The guard only fires when `ProgramFacts.verdicts()` is true, and `Compiler.callsVerdict` walks
only the statement's **own** typed tree — it does not descend into user-function bodies.
**27 of the 32 are `mayExecuteAlloyTest({…asserts…}, | true)`**: the assert-bearing lambda never
runs, the `| true` fallback does, `facts.verdicts()` is false, the test scores green. Named:
`testDataGeneration::tests::alloy::testSimpleSingleTable_Alloy`,
`tds::tdsJoin::alloy::testSimpleJoin` — the latter contains a full `assertSize`+`assertSameElements`
pair that **never executes**. The other 5 have asserts commented out in the corpus and end in
`true;`. These are the engine's behaviour too, but they are **32 units of the 2,454 denominator
that assert nothing**.

**3. Tolerance absorption: 28 tests measured** (`LL_TOL_COUNT` run, attributed per test).
- **7** by the 2-ULP double leniency (`PureAsserts:321-330`): `acos::testProject`,
  `asin::testProject`, `atan2::testProject`, `log::testFilter`, `log::testProject`,
  `tan::testFilter`, `tan::testProject`.
- **21** by the CSV cell float tolerance (`TdsCompare:560-573`, `|Δ| ≤ |e|·1e-11`) — e.g.
  `[tol] csv 6.84 vs 6.840000000000002`.
- Two further absorbers **could not be attributed per test because they have no counter**:
  `MathContext(10)` rounding of all non-integral numerics (`H2Verify:1199-1205`) and microsecond
  flooring of timestamps plus `toInstantFloor()` for precision ≥ HOUR (`H2Verify:1216-1245`).
  The integral arm is correctly exact; the float arm means **a genuine sub-1e-10 relative error
  in any golden-row comparison is invisible.** Labelled **INFERRED** as to count.

**4. Canonicalization, measured.**
- **Unconditional multiset sorting** of both sides (`H2Verify:725-728`, `:867-868`):
  **95 passing tests** would have failed an order-strict compare.
- **Golden-side duplicate collapse** under `EXTENT_SUBSET` (`:684-717`): fired **1×**. Narrow,
  one-directional — acceptable.
- **Golden-only alias dropping** (`:596-606`): fired **11×** (`golden-stitch-keys-dropped`).
  One-directional — a frame key the golden never selects is still a loud `Unverifiable`.
- **Column-arity/shape mismatch → decline, not fail** (`:601`) — combined with ladder row 5, the
  failure mode is *"unverifiable therefore green."*

**5. Setup-failure passes: 0 today, mechanism live.** `MinimalCorpus:452-457` appends the setup
failure to the reason and **keeps `r.pass()`**. Measured zero `[setup:` occurrences across all
2,575 DuckDB results, so this class is empty right now — **adopt the fix while it is free.**
Related latent hole: a setup whose body `Compiler.hasStatementEffects` misjudges is replaced by
`INERT_SETUP` and **silently never runs** (`:369-372, 385-388`) — no counter, no report.

---

## 4. Order-insensitivity — the structural finding

`H2Verify` declares the machinery:

```java
public static final ThreadLocal<Boolean> ORDERED_QUERY = ThreadLocal.withInitial(() -> Boolean.FALSE);  // :179
public static final ThreadLocal<List<String>> SORT_KEYS = new ThreadLocal<>();                          // :192
```

Both are **read** at `:611, 710, 722, 773, 858, 862, 897` and **set nowhere in the repository**.
The comments cite `EngineTestExecutor.sortKeyCols` as the producer — deleted with the old
harness. Consequences:

- `orderedVerdict` (`:947-971`) and `sortKeyIndexes` (`:895-918`) are unreachable dead code.
- `ordFallback()` (`:920-927`) — the "counted residue" instrument — **never fires**, so that
  census is silently zero by construction.
- **Every** golden-row verdict is an unordered multiset compare, sorted and unsorted alike.
- `FORCED_MECHANISM` (`:156`) is likewise declared and never read or set — the documented
  "forced isolation strategy declines" policy is not in effect.

**Measured blast radius:** of the 95 order-leniency-dependent passes, **11 are tests where the
referee is the *only* judge** (ladder row 2) — `groupBy::testReprocessGroupByAlias`,
`mapping::union::testThreewayUnionJoinWithOverlappingFKPKAliasNames`,
`milestoning::contextpropagation::testMilestoningContextWithLatestDateNotPropogatedThroughNonTemporalPropertiesFromAll`,
and 8 others. **None of those 11 has an explicit `sort` in its body**, so no live order bug is
demonstrably masked — but **the guarantee is gone, not weakened**, and nothing in the build
would notice if one appeared. Separately, 28 tests depend on the platform's row-tuple multiset
rescue and 4 on text-line multiset; **117 distinct passing tests depend on some form of order
leniency.**

**The inversion worth naming:** the platform's own assert path derives order honestly —
`AssertVerdicts.orderView` walks the typed chain and returns `SORTED` for real sorts. So **the
platform checks order and the referee does not** — the exact inverse of what `H2Verify`'s
comments claim.

---

## 5. What core_relational does not cover

Measured over the reference checkout.

- **Dialects.** `DatabaseType` literals in the whole corpus: H2 208, DB2 43, DebugPrint 29,
  Composite 11, Snowflake 8, SybaseIQ 7, Sybase 3, MemSQL 1. **DuckDB appears zero times** — the
  lane we execute on is not a dialect the corpus tests. Non-H2 goldens can never be row-verified
  by construction (`SqlTextVerdicts:151-160`). The engine's own per-dialect trees are entirely
  outside the denominator: **491 `<<test.Test>>` functions** in
  `legend-engine-xt-relationalStore-dbExtension/` (sybaseiq 135, snowflake 75, presto 72,
  databricks 55, memsql 50, postgres 41, sqlserver 15, **duckdb 10**, h2 4, others 1–2).
- **Error paths.** **4 `assertError` call sites in the entire corpus.** Wrong-error,
  wrong-message and error-at-the-right-stage are essentially untested.
- **Transactions.** 3 files mention commit/rollback. No multi-statement transaction semantics,
  no isolation, no partial-failure rollback.
- **Concurrency.** Nothing. The harness is single-threaded and every referee thread-local
  assumes one thread.
- **Volume/performance.** Tens of rows per table. No large-result, streaming, memory-bounded
  fetch, or query-timeout behaviour.
- **Plan cache / repeated execution.** No test executes the same query twice.
- **Connection lifecycle.** Auth, pooling, reconnection, credentials: untested
  (`AuthenticationSpec.NoAuth` hard-wired at `MinimalCorpus:139`).
- **Mutation.** Two files — insert/update/delete coverage is thin relative to the query surface.
- **Anything gated on a live server.** The 27 `mayExecuteAlloyTest` tests are an entire assertion
  set present in the count and absent from the verification.

**So "2,575 green" would buy: single-threaded, small-data, H2-shaped, happy-path relational query
semantics.** It would not buy dialect portability, error behaviour, transactional correctness, or
anything at scale.

---

## 6. Semantic fidelity spot-checks

| semantic | right? | would a corpus test catch it being wrong? | citation |
|---|---|---|---|
| `equal(1, 1.0)` cross-kind | **No — three answers live simultaneously.** Static fold TRUE (`literalEquals` via `BigDecimal.compareTo`); assert family FALSE (kind-strict, correctly citing engine `EqualityUtilities`); SQL pushdown TRUE | **No.** `grep -rnoE "assertEquals\([0-9]+, ?[0-9]+\.[0-9]+\)"` and `if(<int> == <float>)` over the whole corpus return **zero hits**. The divergence is invisible at any score | `LiteralFolds:76-85` vs `PureAsserts:273-289`; fold site `StoreResolver:296`; SQL `Scalars:84-110` |
| Pure empty vs SQL NULL | Deliberately engine-verbatim, **not Pure-faithful**: `[] == []` folds TRUE, `[] == x` → `IS NULL`, but two *runtime* empties compare as SQL NULL where Pure says true. The code says so | **No, structurally.** The corpus pins the engine and the engine has the same divergence — no corpus test can separate "matches Pure" from "matches the engine" | `Scalars:80-110` |
| `indexOf` base (Pure = 0-based) | **1-based** in the relational lane, matching the engine's `locate()`; the code names the divergence | Yes for *regressions* (`testSqlFunctionsInMapping` row-verifies). **No** for the Pure-vs-relational divergence itself — the corpus encodes it | `Scalars:1463-1470` |
| Sort order / null placement | Referee: **no** (§4). Platform asserts: yes, when `orderView` resolves to `SORTED` | **Partly.** 296 passes are judged by the referee alone with zero order enforcement. At-risk example: `groupBy::testReprocessGroupByAlias` — pass required discarding row order and no other assert exists | `H2Verify:179,192` (never set), `:725-728`, `:867-868`; `AssertVerdicts.orderView` |
| `sort()` over mixed kinds | Unverified — routed to a **cell-multiset** verdict | **No.** 28 tests where the order-strict compare fails and the multiset rescue passes. Named: `mapping::dates::strictdate::testProject` | `AssertVerdicts:275-292` → `:1057-1066`; `TdsCompare:100-108` |
| `Integer / Integer` division | **Right.** `DIVIDE` renders `((1.0 * a) / b)` on every dialect, preserving Pure's Float result — the one place the code refuses SQL-native behaviour (H2 would truncate) | Yes — the `sqlFunction` family is row-verified against H2 goldens, so a regression to integer division diverges loudly | `AnsiSqlRenderer:645`; `DecimalKindRules:40-58` |
| Date precision / timezone | Lenient at the oracle seam: any `PureDateLiteral` ≥ HOUR compared as an **instant**; timestamps floored to **µs** | Sub-microsecond and precision-class divergences are absorbed silently. The code argues both sides are DB reads so this is engine semantics — plausible, but an **untested assumption with no counter** | `H2Verify:1216-1245` |
| Milestoning propagation through non-temporal intermediates | Covered and row-verified | Yes — but `testMilestoningContextWithLatestDateNotPropogated…` is one of the 11 referee-only, order-leniency-dependent passes | `milestoning/tests/testMilestoningContextPropagation.pure` |

**The prior-audit claim is confirmed live and uncaught:** `LiteralFolds.literalEquals` and
`PureAsserts.equalScalar` disagree on `equal(1, 1.0)` today, and **no corpus test exercises the
shape**.

---

## 7. Critique of the authors' §6f

**What it gets right.** Item 4 ("every test passes scoped exactly as in the full run") is the
best criterion in the list — it directly attacks cross-test contamination through the shared
package session and the seed ledger, and it is mechanically checkable. Item 5 (every failing
test named, floor restated from the ledger not memory) is the right instinct about denominator
honesty. Item 2's *direction* — push judgment into the database, delete the Java comparators —
is exactly the change that would retire the leniency inventory above wholesale. Item 3 would,
if extended to the harness, have caught the dead `ORDERED_QUERY`.

**What it omits — everything about strength.**
- No criterion says how much of the roster must be differentially verified. **Under §6f, a run
  in which the oracle declined all 1,692 times and 1,511 tests degraded to spelling checks is
  DONE.**
- No cap on declines, tolerance firings, or order-leniency firings; none required to be
  zero-or-registered.
- **No roster pin.** Item 5 restates a floor *number*; the floor is `pass.size() >= 2454` and
  nothing reads `corpus2-pass.txt`. Compensating flips are invisible.
- Nothing about order independence, despite the mechanism being dead in the code the plan was
  written against.
- Nothing about the semantic spot-checks: the `equal(1, 1.0)` fork lives in `main/`, is
  unreachable from any corpus test, and no §6f item would surface it.
- **Nothing about the H2 lane.** Gate 5 runs at floor 1866 with a fundamentally weaker oracle —
  `verifyAuto` → `verifyOnSession` (`ReplayOracle:432`) runs the golden on the *same connection*,
  so it is not an independent engine. §6f does not say whether that lane is kept, retired, or
  expected to converge.
- Nothing about the 27 `mayExecuteAlloyTest` shells or the 5 commented-out bodies staying in the
  denominator.

**What is unmeasurable as written.**
- Item 1: "does four things **only**" and "interprets no Pure" are not machine-checkable. And
  the vacuous check it forbids is still there (`MinimalCorpus:417-419`).
- Item 2: "DELETED, not bypassed" — deletion is checkable, "not bypassed" is not. And it names
  `H2Verify`'s compare policy for deletion while the *dead* parts of that policy are the ones
  currently costing verification strength.
- Item 3: "zero thread-locals **in main**" leaves `H2Verify`'s test-side thread-locals —
  precisely the dead ones — untouched.
- Item 5: "the floor number is restated from the ledger" has no artifact and no check.
- Item 7: a document, not a gate.

---

## 8. The definition of DONE this dimension adds

Beyond the summary's §10, phrased so a machine decides each.

1. **Verification-strength budget, gated.** The harness emits a per-test strength class and the
   totals; a gate asserts `differential >= 1511 && textOnly <= 39 && zeroAssert <= 32 &&
   weakOnly <= 25`, computed from `ReplayOracle.OUTCOMES` deltas per test. These must move
   monotonically in the right direction; today they move unobserved.
2. **Zero silent zero-assertion passes.** `Compiler.callsVerdict` descends through user-function
   bodies; any test adjudicating zero verdicts is classified `SKIPPED (no assertion reachable)`
   and **excluded from the pass count**. Check: `grep -c 'ran, no asserts' target/corpus2-pass.txt`
   == 0; the 32 move to a named `skipped` roster with the 27 shells listed by reason.
3. **Order independence is a fact.** Either wire `ORDERED_QUERY`/`SORT_KEYS` from
   `AssertVerdicts.orderView` (the live derivation exists) or delete the dead machinery and
   declare unordered the contract in one place.
   *Check (a):* every `ThreadLocal` declared in `com.legend.harness` has ≥1 `.set(` site — this
   alone catches all three today.
   *Check (b):* `LL_ORD_COUNT=1 <lane> 2>&1 | grep -c '^\[ord\]'` is 0, or every firing is
   attributed to a named entry in a committed order-leniency register. **Today: 136 firings
   across 117 tests, registered nowhere.**
4. **Tolerance and canonicalization budget.** Every leniency has a counter and a committed
   ceiling: the 2-ULP grant, the CSV cell tolerance, the referee's `MathContext(10)` rounding,
   the µs/instant flooring, the fanout collapse, the stitch-key drop.
   *Check:* `LL_TOL_COUNT=1 <lane> 2>&1 | grep -c '^\[tol\]'` ≤ 45, and the referee's
   rounding/flooring paths gain the counters they lack — **the one budget that could not be
   measured today.**
5. **No uncounted declines.** Every path returning `textEqual ? ok() : fail(...)` records a named
   decline first. Today `SqlTextVerdicts:1258-1273` (rows underivable) and `:151-160` (foreign
   dialect) return without touching the census — **which is why the decline count is 24 while the
   text-decided population is 58.**
6. **A semantic spot-check suite in the platform, not the corpus** — one assertion per row of §6:
   `equal(1,1.0)` decided once across `LiteralFolds`, `StaticFold`, `PureAsserts` and SQL
   pushdown; runtime `[] == []`; `indexOf`/`substring` base pinned per lane; `sort()` over mixed
   kinds pinned to one total order. **The corpus cannot test what it does not cover.**
7. **The H2 lane's fate decided in code** — either a portability lane with its own pinned roster
   that prints `oracle=same-session`, or retired. "Floor 1866" with no stated meaning is not a
   criterion.
8. **Setup failure is fatal**, and `INERT_SETUP` elisions are counted and reported.
   *Free to adopt today — zero occurrences.*
