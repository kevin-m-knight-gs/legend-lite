# Roster and floor — the measured runs

**Everything here was executed**, not inferred. Gates run twice per lane at `f073b394`.

*Environment blocker worth recording:* `mvn` and `java` are **not on PATH** in a
non-interactive shell on this box, so Appendix B's commands fail with
`mvn: No such file or directory`. Working preamble:

```bash
export JAVA_HOME=/Users/neemsandv/jdk/jdk-21.0.11+10/Contents/Home
export PATH=$JAVA_HOME/bin:/Users/neemsandv/jdk/apache-maven-3.9.9/bin:$PATH
```

---

## 1. Proof the run was real

The silent-skip signature the plan's §8 warns about was reproduced deliberately and looks
nothing like a real run:

```
# MANGLED roots (-Dlegend.engine.root=/nonexistent/legend-engine):
[WARNING] Tests run: 1, Failures: 0, Errors: 0, Skipped: 1, Time elapsed: 0.020 s
[INFO] Total time:  0.570 s          <- no [corpus2] line at all

# REAL gate 4:
[corpus2] pass=2454 fail=121 of 2575 in 55s
[INFO] Tests run: 1, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 56.58 s
[INFO] BUILD SUCCESS / Total time: 01:09 min
```

`Tests run: 1` is correct and not a skip — `MinimalCorpusTest` has one `@Test corpus()` that
drives all 2,575 internally. `Skipped: 0` plus the `[corpus2] … of 2575` line is the proof.
The skip path is `Assumptions.assumeTrue(Corpus.available())` at `MinimalCorpusTest:35`.

---

## 2. Measured vs claimed — every §0 number confirmed

| Metric | Claimed | Measured | Δ |
|---|---|---|---|
| DuckDB discovered | 2575 | **2575** | 0 |
| DuckDB pass | 2454 | **2454** | 0 |
| DuckDB fail | 121 | **121** | 0 |
| DuckDB errors / skipped | — | **0 / 0** | — |
| DuckDB wall | "60s" | **55s / 59s** corpus; 62–69s maven | ✓ |
| H2 discovered | 2575 | **2575** | 0 |
| H2 pass / fail | 1866 / 709 | **1866 / 709** | 0 |
| H2 wall | — | **35s / 37s** corpus | — |
| `verify8 MATCH` | 1591–1592 | **1591** (both runs) | at low end |
| `verify8 DIVERGED` | 6 | **6** | 0 |
| `verify8 DECLINED` | 20–21 | **21** (both runs) | at high end |
| `verifyFetchChain MATCH` | 49 | **49** | 0 |
| `verifyFetchTexts MATCH` | 23 | **23** | 0 |
| `verifyPlan MATCH / DECLINED` | 28 / 4 | **28 / 4** | 0 |

The claimed ±1 (`testPaginatedByVendor`) landed on the DECLINED side in both runs, so
MATCH=1591 / DECLINED=21 was stable, not oscillating. Internal consistency check that passes:
the 6 `DIVERGED` outcomes correspond 1:1 to the 6 failures whose message contains
`sql-text ROW verdict`.

---

## 3. Roster diff

**Regressions (failing now, not in the roster of record): ZERO.**
**Names in the roster that now pass: ZERO.**
The failing name set is byte-identical to `docs/parked/duckdb-fail-roster-batch119.txt`
(121 = 121, both `comm` directions empty). **"0 lost since batch 119" is verified.**

**But Appendix B's own command reports a false regression today.** It does
`comm -13 roster fail-now.txt` on lines of the form `name :: message`, and one message has
drifted since batch 119:

```
roster:   …testPostProcessTransformJoinOp :: TypeInferenceException: in function
          'meta::relational::functions::sqlDialectTranslation::relOpToString': unknown functi…
measured: …testPostProcessTransformJoinOp :: type: sqlQueryPostProcessorsConnectionAware hook
          shape is not a replaceTables lambda — …
```

Run verbatim, Appendix B prints a non-empty LOST set and looks like a regression. **The set
difference must be taken on the name field** (`cut -d' ' -f1`), with messages compared
separately. Fix the command or the ratchet will cry wolf.

---

## 4. All 121 DuckDB failures, categorized

Every one assigned; evidence = the measured message, plus the engine's Pure source or a scoped
re-run where the message was blank.

| Category | Count | Share |
|---|---:|---:|
| **REAL-DEFECT** | **37** | 30.6% |
| **ENGINE-MACHINERY** | **36** | 29.8% |
| **TEXT-ONLY** | **29** | 24.0% |
| **OTHER-STORE** | **10** | 8.3% |
| **CODE-AS-DATA** | **7** | 5.8% |
| DATA-NONDETERMINISM | **0** | — |
| UNKNOWN | 2 | 1.7% |

**TEXT-ONLY (29)** — 5 DB2/foreign-dialect ("text is the contract"), 4 `rows underivable`,
5 `oracle declined`, and **15 reclassified by hand** because the roster message is blank:
- 5 union tests (`testChainedUnions`, `testProjectThroughAsso`,
  `testProjectThroughAssoWithJoinInMapping`, `testUnionWithSinglePropertyMapping`,
  `testUnionOnViewsMapping`) all fail the same line:
  `assert($result2->sql()->contains('union_gen_source_pk_0'))` — **a substring assertion on the
  engine's internal alias name**.
- 2 biTemporal: `assert($sql->contains('as "unionalias_1"'))`.
- 2 `legacyNullUnsafeEquals` + `testExecutionPlanGenerationForLambdaFromWithEnumMapping`:
  `assert($planString->contains(...))`.
- 2 `relationalMapper`, 2 `testTwoMappingsOneRuntime*`: whole plan/SQL text over a foreign schema.
- `testMilestonedProperty`: golden plan is a `PureExp` serialize node, we emit a `Relational`
  node — a text assert with a genuine strategy divergence behind it.

⚠️ **Caveat on 9 of the 29:** `rows underivable` and `oracle declined` mean the referee *could
not adjudicate*. The plan's TEXT-ONLY definition says "rows would match" — **that is not
established for these**; they are *text-differs-and-unadjudicated*. Calling them TEXT-ONLY is a
decision, not a measurement.

**ENGINE-MACHINERY (36)** — tests executing the engine's own Pure compiler/router/plan stack:
`routeFunction` ×4, `resolveStore` ×2, `pureToSqlQuery::*` ×7, `sqlQueryToString::*`,
`toPostgresModel::*` ×2, `postProcessor::*` ×3, `sqlDialectTranslation`,
`translateCoreTypeToDbSpecificType`, `viewToTDS`, `extractDBs`, `evaluate` ×2, `transformPlan`,
5 `testConnectionEquality*` (a Pure `match` over extension-contributed arms), and 2
`testToSQLStringWith*` whose whole body is `runTestCaseById('…')` — **the engine's own Pure
test-case registry executed as data**.

**OTHER-STORE (10)** — 4 XStore in-memory↔relational, 2 M2M model connection, 2 m2m2r
`planGraphFetch*`, 2 external-format binding. *Plan §7's residue says 8; measured 10.*

**CODE-AS-DATA (7)** — metamodel reflection: `ValueSpecification.func` ×3, `instanceOf` over
plan-node types ×2, `^PureModelContextData`, a `TableAlias` metamodel value reaching the
lowering boundary.

**REAL-DEFECT (37)** — the 6 referee-confirmed row divergences, 8 more row/value divergences dug
out of blank messages, 5 simple-name-resolution failures (`'Address' is not a known class …`),
12 named compiler/resolver walls, 5 typer gaps, 1 DuckDB dialect gap (`STRING_AGG`). Two
previously-unnamed, small, sharp bugs surfaced here:

- **md5 digest mismatch** (2 tests, one root cause): `expected 9e103ea06a…, actual 5e922469e9…`
  — our digest is over a different byte encoding than the engine's.
- **Date-column rendering** (`columnValueDifferenceWithoutPrevalTest`): every cell identical
  except `2014-12-01` where the golden has `2014-12-01T00:00:00.000000000+0000`. A
  DateTime-vs-Date rendering bug, not a row bug.

**DATA-NONDETERMINISM (0)** — no failure is attributable to ties/ordering/float noise. The one
tie case (`testPaginatedByVendor`) is a referee *decline* and the test passes.

---

## 5. Why §7's floor arithmetic does not close

Solving §7's stated end state against the measured categorization forces
**X + Y = 16, i.e. X = 16, Y = 0**, which requires:

1. **All 37 REAL-DEFECTs burn** — every compiler wall, every row divergence, both digest bugs,
   both REVISIT row divergences. **No allowance for a single unburnable one.**
2. **All 7 CODE-AS-DATA + both UNKNOWNs resolve.**
3. **Exactly 20 of the 36 ENGINE-MACHINERY tests fall to step 5.**

Where it is optimistic, with numbers:

- **"Step 5 families ≈ 45" is at or above the measured ceiling.** The *entire* population of
  metamodel/engine-code-shaped failures is **36 + 7 = 43**. Claiming 45 exceeds it. And §7
  *simultaneously* keeps "ENGINE machinery outside code-as-data" in the residue — you cannot
  both take 45 from a pool of 43 and leave some of that pool behind. **§7 is internally
  inconsistent by 2–16 tests.**
- **What "step 5" means here is understated.** It is not a family of small fixes: it is making
  `pureToSqlQuery`, `sqlQueryToString`, `toPostgresModel`, `sqlDialectTranslation`, the
  post-processors and `routeFunction` **executable inside legend-lite** — re-hosting the
  engine's own SQL compiler. That is the single largest bet in the plan, and **45/121 (37%) of
  the floor depends on it**.
- **"REVISIT 5 + parked 2 decided → ≈55" is accounting, not burning.** By this project's own
  rule (batch 100), a traced golden disagreement becomes a NAMED receipt and the test *still
  fails*. Subtracting 7 for "decided" only works if the harness stops counting them. Say which.
- Two smaller drifts: OTHER-STORE measured 10 vs §7's 8; TEXT measured 29 vs ~30 (good).
- **The 121 → ~105 step is the credible one** — 16 fixes against 37 measured real defects
  leaves real headroom.

---

## 6. The H2 lane

**Measured: 1866 / 709 of 2575 in 37s. 590 fail *only* on H2** (119 fail on both lanes; **2
DuckDB failures actually pass on H2**).

| Family | Count | Nature |
|---|---:|---|
| `DialectCapability:` (declared gaps) | **280** | variant/JSON navigation 143, LIST_MIN 46, UNNEST 32, LIST_GET 29, collection membership 9, LIST_FILTER 8, STRING_AGG 8, struct 4, FULL OUTER emulation 1 |
| H2 missing functions | **80** | STRING_SPLIT 49, REGEXP_EXTRACT 26, TO_BASE64 3, JSON_PRETTY 2 |
| **`Column "tN.X" not found`** | **127** | **a real identifier-quoting bug in our renderer** |
| row/value assert failures | 66 | incl. 7 numeric-format (`10.0` vs `1E+1`) |
| text / oracle-declined | 21 | |
| internal exceptions | 12 | incl. `a union needs at least two branches` ×11 |
| other | 4 | |

**~61% (360/590) is "dialect gaps we chose not to close." ~39% (230/590) is not.**

### The 127 are one bug, confirmed on two independent samples

```sql
-- meta::relational::tests::mapping::tree::testProjectionDeeper (H2)
    SELECT "t1".id AS "id", "t2".name AS "parent_name"      -- alias QUOTED lowercase
    ...
  ) AS "t3"
  LEFT OUTER JOIN orgTreeOptimizationTable AS "t4" ON "t4".ancestor = "t3".id   -- ref UNQUOTED
-- H2: Column "t3.ID" not found     (H2 folds the unquoted ref to ID; the alias is "id")
```

Second confirmation, `typeInference::testDynaAndOrInference`: subselect exposes
`AS "mapping_fqn"`, join condition references `"t14".mapping_fqn` →
`Column "t14.MAPPING_FQN" not found`.

**We emit derived-column aliases quoted and column references unquoted.** DuckDB's
case-insensitive unquoted resolution hides this completely; H2 — and Oracle, DB2, Snowflake,
Postgres, and every other real target — does not. **A portability defect in the core renderer,
invisible in the whole DuckDB lane, worth 127 H2 tests from one fix.**

### Recommendation for §6d

Neither pure (A) nor pure (B). The lane's value is concentrated: **230 non-declared-gap
failures, of which 127 are one renderer bug and 11 are one `union needs at least two branches`
bug.** Close those two legs (~138 tests, ~23% of the H2-only set) and the lane drops to ~570
failures that are almost entirely the declared dialect gaps — at which point (A) "advisory with
a floor, stop reading its number as progress" becomes **honest**, because what remains genuinely
*is* the emulation decision.

**Doing (A) first, as written, retires the only detector of a real cross-dialect bug in the core
renderer.**

Cost of keeping it: ~40s wall per batch (~100s for both lanes). The real cost is roster churn on
709 names.

**One more thing the user needs for that decision:** on the H2 lane `verifyAuto` routes to
`verifyOnSession` (`ReplayOracle:432`), running the golden on the **same connection** as our
query. That lane is therefore **not an independent oracle** and its 1866 must not be read as a
differential result.

---

## 7. Reproducibility and nondeterminism

**Roster regeneration in ~60s: confirmed.** Warm tree, no recompile:

```
gate 4 run 2:  wall=62s   [corpus2] pass=2454 fail=121 of 2575 in 59s
gate 5 run 2:  wall=41s   [corpus2] pass=1866 fail=709 of 2575 in 37s
```

Run 1 of gate 4 was 1:09 because it recompiled 628 sources.

**Determinism: clean, both lanes, two runs each.**
- Gate 4 run1 vs run2: `diff` on the 121 failure lines **including messages** — **identical**.
  Referee outcome counts identical (1591/6/21, 49, 23, 28/4).
- Gate 5 run1 vs run2: `diff` on the 709 failure lines including messages — **identical**.
- **No test changed outcome between runs.** The plan's claimed H2 flapper
  (`testFullOuterJoinSimple`) did not flap.

**§6c's named order-dependence example does not reproduce.** §6c says
`resultSourcing::relationalResultSourcingOfListExecutionPlan` "passes in the full run and fails
scoped." Measured: it **fails in both**, same message, and its package scores 6 pass / 1 fail
identically scoped and full. Nine other scoped runs (`testExtendDigest_Relational`,
`testMilestonedProperty`, `testSimpleMappingQueryWithFilterInProject`, `testChainedUnions`,
`testProjectThroughAsso`, `testUnionWithSinglePropertyMapping`, `testUnionOnViewsMapping`,
`testJoinWithExtendWithDigest…`, `columnValueDifference…`) all matched their full-run outcomes.

**§6c's premise needs re-measuring before a batch is spent on it** — but note this sampled 10 of
2575, so it is **not** a refutation of order-independence in general, only of the named example.

---

## 8. Findings, ranked

1. **The roster hides the reason for 21 of its 121 entries** (17%) — 11 completely empty, 10
   saying only `"Assert failed"`. Cause: `MinimalCorpusTest:73` does
   `String.valueOf(e.getMessage()).split("\n")[0]` and these failures put expected/actual on
   lines 2–3. Scoped re-runs recover the detail instantly. Directly contradicts §6f item 5.
   **Fix: keep the first 3 lines, or join on `" | "`.** One line; removes an entire class of
   "we don't know what this is."
2. **Appendix B's set-difference command produces a false regression today** (§3).
3. **127 H2 failures are one renderer bug** (§6) — the strongest argument against retiring the
   H2 lane, and not mentioned anywhere in §6d.
4. **§7's floor requires all 37 real defects to burn with zero residue** (§5). No slack.
5. **"Step 5 ≈ 45" exceeds the measured pool of 43** and double-counts against §7's own residue.
6. **Two small, sharp, previously-unnamed bugs** found in the blank-message entries: md5 digest
   encoding (2 tests, one fix) and Date-vs-DateTime column rendering (1 test, rows otherwise
   byte-identical). Both look cheap; neither appears as a named leg.
7. **9 of the 29 TEXT-ONLY are unadjudicated, not proven-equivalent.** Item 4
   (referee-in-database) is what converts them from a decision into a measurement.
8. **`mvn`/`java` are not on the non-interactive PATH** — Appendix B fails on a fresh shell.
9. **Both lanes are fast and fully deterministic.** Whatever else is true, **the measurement
   apparatus itself is sound** and the plan's §0 numbers are all real.
