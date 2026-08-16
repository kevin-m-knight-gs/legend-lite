# Every non-passing `core_relational` test, root-caused — 2026-08-15

> Companion to `docs/E2E_BURNDOWN_2026_08_14.md`. That document **classified** 276
> failures into 11 buckets and root-caused a handful in depth. This one **diagnoses
> every single one**: for each test, the mechanism that produces the failure, the
> file:line that owns it, how real legend-engine does it instead, the exact fix, the
> risk, and the cheapest observation that would prove the diagnosis wrong.
>
> **Evidence base: `docs/e2e-diagnosis-2026-08-15/`**
> — `bucket-*.md` (12 dossiers, one entry per test), `clusters.md` (209 clusters —
> tests grouped by *the change that fixes them*), `diagnoses.csv` / `.json`
> (machine-readable), `clusters.json`.
>
> Produced at legend-lite `9d1f2cd0` against legend-engine and legend-pure sources.
> Nothing here was written from memory of Legend semantics; every semantic claim was
> checked against the engine's own `.pure`/Java implementation.

---

## RECONCILIATION at `a491a194` (2026-08-16, goal #18 batch 26)

This document was produced at `9d1f2cd0`. Six goal-#18 batches landed since
(`c56938f3` adjustTemporal print-channel, `4a60b246` String-target wire-cast
unwrap, `20f1df87` comparison-position unwrap, `5d7dc0ec` exploding-sub PK
re-keying, `91b60e84` tail-hop parked corr preds, `a491a194` tail-seg reroute).
Diffing `diagnoses.csv` against the `a491a194` scoreboard:

- **4 rows RETIRED** (all REAL_DEFECT, all fixed by the parallel batches, and
  all four fixes landed on the exact mechanisms this document names —
  independent convergence): `testInWithDynaFunction`,
  `testJoinIsolationDeeper_LeftOuterLeftOuterThenInner`,
  `testTemporalDateVariableInFunctionExpression`,
  `testVariableReferenceWithNestedFilterMultiple`.
- **0 rows appeared.** The live denominator is 267 distinct non-passing tests
  (270 rows, 3 double-family), split: 103 REAL_DEFECT, 92 MISSING_FEATURE,
  27 TESTS_ENGINE_INTERNALS, 21 GOLDEN_TEXT_ONLY, 14 EXECUTION_TARGET_ARTIFACT,
  8 HARNESS_GAP, 2 NEEDS_PROBE.
- Two of the still-live diagnoses have MOVED walls since `9d1f2cd0` (both
  forward): `testVariableReferenceInMapWithNestedFilter` (multi-hop wall →
  assert-splice wall; its main query now lowers and executes) and
  `isolationTest` (multi-hop wall → honest row-diff FAIL).

**Standing role**: `diagnoses.csv` in the evidence dir is the per-row
adjudication ledger for goal #18 — every non-passing `core_relational` row
carries an evidence-backed verdict, effort, confidence, and falsifier there.
Retirements are recorded here (shrink-only); verdict changes require the row's
own falsifier to fire.

---

## 0. Why this document exists

The 2026-08-14 burn-down is a good census. It is not a diagnosis. Measured against
its own evidence base:

| | |
|---|---:|
| failing tests **named anywhere** in that document | **34 of 276** |
| `file:line` citations in the whole document | 38 (28 distinct files) |
| distinct failure signatures in its `master-classification.csv` | **204** |

Per bucket, what it actually offered:

| bucket | n | what the old doc gives you |
|---|---:|---|
| 2 execution-plan | 71 | best covered — 23 clusters, file:line for ~8; ~24 rows uncovered |
| 6 wrong rows | 37 | six themes, **zero** code-level causes |
| 7 resolver | 36 | "22 of 92 ERRORs live in `resolver/`", by grep; no per-test cause |
| 4 SQL-text golden | 35 | no per-test cause at all |
| 9 typer | 26 | two owning files named, no causes |
| 1 engine internals | 23 | ledgered out-of-scope (a defensible call) |
| 10 harness SHAPE | 15 | 3 identified, 12 not |
| 11 unclassified | 11 | explicitly left unclassified |

Its own §8 concedes this: §4.4's cluster table and all of §6 are "relayed from
subagent research, not re-verified". With 204 distinct signatures behind 276 rows,
bucketing cannot substitute for per-test work. So this document does the per-test work.

---

## 1. The ledger had drifted — correct these numbers first

The burn-down describes commit `2c0632d6`. A parallel workstream has since landed
**27 commits** (`Goal #18`, working that document's own §5 plan). At `9d1f2cd0`:

| | 2026-08-14 doc | **actual, `9d1f2cd0`** |
|---|---:|---:|
| runnable | 2575 | 2575 |
| passing | 2299 | **2301** |
| non-passing | 276 | **274** (87 FAIL / 94 ERROR / 93 SHAPE) |

- **9 rows from the old ledger now pass.** `testGet`, `testQuery`, `testSimpleBoolean`,
  `testDupsFilterProject`, `testSimpleTypeMappingNulls`, `testSameTableNameDifferentSchema1`,
  `testSupportStreamFlagFromSimple`, `testMapping`, `testSelectChainOfAndOrOperators`.
  Their diagnoses are retained in the dossiers, marked ✅, because several document
  mechanisms that still matter elsewhere.
- **7 failures appeared that were never in the ledger** — the whole `postprocessor`
  family. These are **not a regression**: commit `6ddae338` deliberately deleted a
  `catch (NotImplementedException) { /* leave unapplied */ }` in `SqlPostProcessors`
  that had been silently skipping unrecognised post-processor hooks. All 7 supply a
  **CTE-extraction** hook and had been "passing" with the exact feature under test
  skipped. Each test's own last line asserts the with-CTE and without-CTE results are
  equal, so the row channel could never observe the difference — a textbook false
  green. The commit moves the total by exactly −7. **This is the ledger getting more
  honest, not the product getting worse.**
- One row is double-counted: `testAdvancedEmbeddedInMappingQuery` appears twice in
  `master-classification.csv` (same file:line, two mappings). **275 distinct tests in
  276 rows**; 271 distinct non-passing today.

---

## 2. What the 274 actually are

Every non-passing test received an individual verdict. This is the single most
important table in the document, because it says how much of the gap is *correctness*
and how much is *coverage*:

| verdict | n | meaning |
|---|---:|---|
| **REAL DEFECT** | **110** | legend-lite computes something wrong |
| MISSING FEATURE | 92 | unimplemented surface; the wall is honest |
| TESTS ENGINE INTERNALS | 27 | white-box tests of legend-engine's Pure-written compiler |
| GOLDEN TEXT ONLY | 21 | rows are right or unasserted; only SQL/plan text differs |
| EXECUTION-TARGET ARTIFACT | 14 | DuckDB/H2 difference, not a defect |
| HARNESS GAP | 8 | the runner genuinely cannot express this assert |
| NEEDS PROBE | 2 | unresolvable without running it |

Confidence: **198 high, 72 medium, 4 low**.

**The structural finding.** Effort distributes *opposite* to verdict:

| verdict | XS | S | M | L | XL |
|---|---:|---:|---:|---:|---:|
| REAL DEFECT | 9 | 18 | 56 | 23 | 4 |
| MISSING FEATURE | 4 | 15 | 19 | 38 | 16 |

**83 of 110 real defects are XS–M. 54 of 92 missing features are L–XL.**
(Effort here is *post*-adversarial-review, which revised 15 of 31 estimates upward —
see §4. The pre-review numbers were more flattering and should not be quoted.) The
correctness work is mostly cheap; the coverage work is mostly expensive. The old
doc's §7 ordering leads with breadth (`jsonEquivalent`, Presto renderer, 425 dialect
goldens) and defers the resolver grind — that is backwards if the goal is end-to-end
execution correctness, and its own closing paragraph says as much without acting on it.

Where the defects live:

| bucket | live | real defects | dominant other verdict |
|---|---:|---:|---|
| 2 execution-plan | 70 | 31 | 28 missing feature |
| 7 resolver (H-phase) | 36 | **22** | 14 missing feature |
| 4 SQL-text golden | 35 | 13 | 14 golden-text-only |
| 6 wrong rows | 33 | 14 | 10 execution-target artifact |
| 9 typer (G-phase) | 26 | 13 | 10 missing feature |
| 1 engine self-metamodel | 23 | 1 | 16 tests-engine-internals |
| 10 harness SHAPE | 15 | 3 | 7 missing feature |
| 5 invalid SQL | 10 | 8 | 2 artifact |
| 11 unclassified | 9 | 4 | 5 missing feature |
| 8 lowering (I-phase) | 7 | 0 | 7 missing feature |
| R newly-honest walls | 7 | 0 | 7 missing feature |
| 3 metamodel surface | 3 | 1 | 2 missing feature |

Two readings the old doc could not make:
- **Bucket 7 is the densest defect concentration** — 22 real defects in 36 tests. The
  old doc attributed it to two files by grepping message literals and stopped.
- **Bucket 8 (lowering) contains zero defects.** All 7 are absent surfaces. It should
  never have been ranked as a defect bucket.

---

## 3. The plan — 209 clusters

`clusters.md` groups every test by **the change that fixes it** (not by symptom,
family, or theme). The shape of that grouping is itself the finding:

| cluster size | 1 | 2 | 3 | 4 | 5 | 6 | 9 |
|---|---:|---:|---:|---:|---:|---:|---:|
| count | 160 | 38 | 6 | 2 | 1 | 1 | 1 |

**160 of 209 clusters are singletons.** The old doc's "long diffuse tail" intuition
was right; what is new is that every tail item now has a named cause and a written fix.
There is no large lever hiding in this corpus. Anyone promising a big jump from one
change is mistaken.

| tier | clusters | tests | of which contain a real defect |
|---|---:|---:|---:|
| **XS/S** | 50 | 61 | 27 |
| M | 67 | 87 | 42 |
| L/XL | 64 | 84 | 24 |
| **ledger, do not fix** | 28 | 50 | — |

### 3.1 Start here — highest tests-per-unit-effort, correctness first

Ranked by `tests / effort`, restricted to clusters containing a real defect:

| cluster | tests | effort | owning code |
|---|---:|---|---|
| `StaticFold` cannot fold `isEmpty`, so a dead `if` branch is type-checked | 2 | XS | `compiler/spec/StaticFold.java` |
| Join-navigation database scope lost in metamodel type inference | 1 | XS | `exec/MetamodelWalk.java:1315-1317` |
| `average`/`mean` over a to-one argument lowers to identity (Integer, not Float) | 1 | XS | `lowering/Scalars.java` |
| `ScanRelations.walk` throws instead of silently skipping an unmapped property | 1 | XS | `lineage/ScanRelations.java` |
| `viewSchema` compares a bare column name against a quote-bearing reference | 1 | XS | protocol→model unquote asymmetry |
| `generateObjectReferences` host-fold recognizer rejects a collection RHS | 1 | XS | `harness/ObjectRefs.java` |
| `%latest` generated-date column emitted for non-milestoned target tables | 2 | S | `resolver/GraphEmission.java` |
| H2 dialect chimera: `adjust()` unit case vs legacy plan-param spelling | 2 | S | `sql/dialect/EngineStyleH2.java` |
| `UserCallInliner` drops lambda-binder shadowing when the env is non-empty | 2 | S | `compiler/spec/UserCallInliner.java` |
| Relational comparisons forced through Pure's overload table | 4 | M | `compiler/spec/` |
| `VarSetPlaceholder` result-column typing on cross-store TDS joins | 3 | M | `plan/PlanText.java`, `StatementExecutor.java` |
| M2M eager binding substitution — defer the unmapped-property wall to read time | 3 | M | `resolver/ClassSources.java:897-951` |
| `StaticFold` cannot carry a `TDSColumn` or a lambda across a static fold | 3 | M | `compiler/spec/StaticFold.java` |

Full ordering, with mechanism and consolidated fix for each, is in `clusters.md`.

### 3.2 Take these off the denominator — 28 clusters, 50 tests

The diagnosis concluded these should be **ledgered, not fixed**. Largest:

- **M3 `ValueSpecification` reflection + the engine's Pure SQL compiler** (5 tests) and
  **the engine's Pure router + extension registry** (4 tests). These call
  `routeFunction`, `toSQLQuery`, `mergeOldAliasToNewAlias` and assert on the *shape of
  legend-engine's own intermediate Pure objects*. Passing them means interpreting the
  engine's compiler as data — tenet 1, and no product value.
- **DuckDB row-order artifacts** (multiple clusters, ~14 tests): positional asserts
  over queries with no `ORDER BY`. The old doc found 6–9 of these; the per-test pass
  found **14**, and identified the mechanism more precisely than "row order" — DuckDB's
  LEFT-JOIN build/probe side swap, and `build_side_probe_side` on index-addressed
  self-joins.
- **legend-lite-only `acos`/`asin`/`sqrt` domain guards** — the test rows are
  unreachable on DuckDB at all.

Ledgering these moves the honest target from 274 to **224**.

---

## 4. How much to trust this

The failure mode this pass was built to avoid is the one the previous document
self-reported: confident claims with citations nobody checked. Three independent
checks were run.

**1. Mechanical citation resolution.** Every `file:line` in every diagnosis was
resolved against the real trees:

| | |
|---|---:|
| citations parsed | **2,162** |
| resolve to a real file with that line | **2,162 (100%)** |
| content confirmed by keyword match within ±8 lines | 1,989 (92.3%) |

The 7.7% residue is dominated by citations that *paraphrase* rather than quote.

**2. Adversarial refutation** of the 31 cheap, high-confidence real defects — the list
anyone would act on first. Each verifier was told to attack the mechanism, the fix, the
effort, and to re-open every cited line hunting for citations that resolve but say
something else:

| | |
|---|---:|
| CONFIRMED (mechanism **and** fix hold) | 23 |
| PARTIALLY_WRONG (mechanism holds, fix or effort wrong) | 8 |
| **REFUTED** (mechanism wrong) | **0** |
| effort revised **upward** | **15 of 31** |
| effort revised downward | 2 |

**No mechanism was refuted, but effort was systematically optimistic.** The recurring
error is a distinction the first pass blurred: *removing the wall* ≠ *making the test
pass*. Several entries were relabelled "wall-advance only" — e.g. `testToSQLStringWithAbs`
(the typer edit is right; the real work is elsewhere and it is L, not S) and
`testExecutionPlanGeneration` (the `ColSpecArray` change is genuinely XS–S; the item
"make this test pass" is L). **Treat every unverified XS/S in this document as
provisional.** Only the 31 marked with an adversarial verdict have been pressure-tested.

The review also caught a regression trap worth generalising: for
`tdsJoinTwoDBWithColumnMappedViaJoins`, the sibling `tdsJoinOneDBOneExpression`
currently **passes** with an identical query shape and the opposite expected type, so
the fix must be scoped to the spliced placeholder side only. Fixes in this corpus are
routinely one careless generalisation away from breaking a passing test.

**3. Completeness reconciliation.** Every fan-out output was checked test-by-test
against the input list. This caught a silent failure: the bucket-7 clustering agent
returned a well-formed result covering **8 of its 36 tests** — no error, just quiet
truncation. It was re-run split. Final coverage: **283 diagnoses; 274 live rows,
matching the scoreboard exactly; 271 distinct live tests, 100% in a cluster.**

### Independent calibration

Two diagnoses were written blind against code whose fix had *already landed upstream*,
giving an unplanned control:

- `testSelectChainOfAndOrOperators` — the agent concluded a `getAll` was resolving
  without the enclosing `from()` that carries the mapping, pointed at the in-chain
  `from()` arm (`StoreResolver.java:2643-2650`), predicted it "worked by accident
  whenever the runtime had exactly one binder", and paired it with `testMapping`.
  Commit `377fbf4b`, which it never saw: *"the in-chain TypedFrom arm re-scoped only
  the METHOD PARAMETER 'context' while class-query dispatch reads 'chainContext' …
  That worked by accident whenever the runtime had exactly one binder"* — and its
  scoreboard delta is exactly those two tests. The agent marked itself `NEEDS_PROBE`
  rather than claiming the variable, which was the correct call.
- `testSimpleBoolean` — derived "`values->at(k)` is a cell index, not a row slice";
  commit `9d1f2cd0` is titled *"TDSRow cell reads by index — `values->at(k)` is a
  COLUMN, not a row slice"*.

### Known gaps

- **No test was executed.** Every diagnosis is static analysis against real sources.
  Maven was barred — the machine was under load and 60+ concurrent builds would have
  corrupted parallel work. 2 tests are explicitly `NEEDS_PROBE` with the probe named.
- The failure *text* in the briefs came from the 2026-08-14 sweep; the *code* read was
  current. 61 of 276 rows have drifted in detail since (mostly plan-text whitespace).
  12 diagnoses flagged their own brief as stale — the agents caught this themselves.
- Effort estimates outside the verified 31 are unreviewed, and the verified sample says
  they skew optimistic.
- §6 of the old doc (the wider 5,390-test estate) is **out of scope here** — this
  document covers `core_relational` only. One spot-check: its §6.2 headline claim
  survives — `jsonEquivalent` occurs **exactly 179** times and is genuinely not a
  recognised assert form, though `assertJsonStringsEqual` is now at
  `EngineTestExecutor.java:1998`, not `:1964`.

---

## 5. How to use this

1. **Correct the numbers** (§1) before quoting anything. 2301/274, not 2299/276.
2. **Pick from `clusters.md`**, not from the bucket table. Buckets group by symptom;
   clusters group by the change that fixes them, which is what you schedule.
3. **Read the test's dossier entry** in `bucket-*.md` before writing code — it carries
   the evidence, the risk, the ⚠ correction where adversarial review found the fix
   wrong, and the falsifier.
4. **Run the falsifier first** where one is cheap. It is the fastest way to discover
   the diagnosis is wrong before you have spent a day on it.
5. **Re-derive if the corpus has moved.** This is a snapshot. `diagnoses.csv` keys on
   `family` + `test`; diff it against a fresh scoreboard to find drift.

Standing traps, unchanged and worth restating: fix in the platform, never the harness;
a wrong-rows PASS is worse than an honest ERROR; and — new from this pass — a fix that
generalises one line too far will silently break a passing sibling.
