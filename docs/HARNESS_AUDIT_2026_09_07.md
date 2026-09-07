# Test-harness audit — soup to nuts (2026-09-07)

> **Baseline.** `f073b394` = `origin/main` @ `570acfbc` + one docs commit. Gates run and
> reproduced: DuckDB 2454/121 of 2575 (55s), H2 1866/709 (37s).
>
> **Method.** Seven adversarial reviewers, each briefed to be harsh but correct and to
> label anything unverified. `docs/END_TO_END_PLAN_2026_09_08.md` was treated as a set of
> CLAIMS to test, never as evidence. Every headline number below was re-verified by the
> orchestrator against source or a run; figures that failed verification were dropped and
> are listed in §9. One reviewer executed the gates twice per lane; one reproduced the lane
> with its own driver and instrumented per-test referee traffic.

---

## 1. Verdict

**The rewrite is a real and large success on structure, and it silently weakened
verification.** Both halves are load-bearing.

The old harness is gone — all eight classes, **−12,800 net test lines** — and production
absorbed almost none of it (**+230 net**). Four execution-option ThreadLocals were deleted
by making options ride values, which is the correct fix, and `ExecuteOptions`' javadoc
states the doctrine better than any design doc: *"they ride the request and the result,
never a static slot."* Denominator integrity is now protected by construction: a corpus
parse wall throws from the constructor, so a parse regression detonates instead of
shrinking the score.

Against that: **batch 115 deleted 39 assertion sites and replaced them with one
monotone-upward count**, and in the same commit it deleted the writers of four fields
whose readers it left behind. The consequence is §2 — row order stopped being verified,
in the direction that manufactures passes, and nothing in the build can see it.

---

## 2. The regression: row order stopped being a contract

`EngineTestExecutor.java:1656-1657` set the referee's ordered-compare flags per test.
Batch 115 (`ff359bae`) deleted that file (4,102 lines) and left the readers in `H2Verify`.

| field | reads | writes | effect |
|---|---:|---:|---|
| `ORDERED_QUERY` | 6 | **0** | defaults `FALSE` — every golden judged as a multiset |
| `SORT_KEYS` | 1 | **0** | `sortKeyIndexes` unreachable |
| `FORCED_MECHANISM` | 0 | 0 | wholly dead |
| `CanonicalDivergence.CONTEXT_SOURCE` | 4 | **0** | every witness prints `<unattributed>` |

**Measured blast radius: 95 passing tests would fail an order-strict compare; 117 depend on
some form of order leniency.** Eleven of the 95 are tests where the referee is the *only*
judge. An ordered comparison is strictly stronger than a multiset one, so this can only
move tests from fail to pass — and the floor was re-pinned at the higher number.

Two aggravating facts. `H2Verify.CURRENT_TEST` lost the *same* writer and **was** rewired
by hand in batch 124 — because unattributed lines appeared in the console. Nothing prints
when an ordered compare becomes unordered. And `HarnessDisciplineTest.java:96` still
certifies two sort sites by citing `EngineTestExecutor.sortKeyCols → SORT_KEYS`: a guard
vouching for a mechanism deleted in the same commit range.

**The missing guard is ~40 lines**: for every static `ThreadLocal`/`Atomic*`/`LongAdder`/
`volatile` field across both source roots, assert reads > 0 ⟺ writes > 0, pinned at zero.
It would have gone red in `ff359bae` naming all four fields.

---

## 3. What a pass actually proves

Measured per-test over the 2,454 DuckDB passes by instrumenting `ReplayOracle.OUTCOMES`
and `H2Verify.UNVERIFIABLE_CENSUS`.

| Strength | Mechanism | Tests | What a pass proves |
|---|---|---:|---|
| **Strongest** | our rows vs engine golden SQL on a real seeded H2, **plus** a literal assert | **1,198** | the answer is right two independent ways |
| Strong | golden-row differential only (text assert upgraded to rows) | **313** | matches the engine's answer; text unpinned |
| Moderate | our rows vs literals typed in the corpus | **825** | equals a human-written expectation; no cross-engine witness |
| Weak | cardinality/emptiness/boolean only | **25** | a row count, not a value |
| Spelling only | SQL/plan text byte-compare; row leg never derivable | **39** | our emitter spells like the engine's — nothing about answers |
| **None** | zero adjudicated asserts — passes because the body did not throw | **32** | nothing |

**1,511 of 2,454 (61.6%) are backed by a real differential check.** That oracle has teeth:
6 tests fail today on row divergence alone, text notwithstanding.

Of the 32 zero-assertion passes, **27 are `mayExecuteAlloyTest` shells** whose
assert-bearing lambda never runs (the `| true` fallback does, `ProgramFacts.verdicts()` is
false, the test scores green); 5 have their asserts commented out in the corpus; **2 are
vacuous placeholders** — `testXStore:3323` and `testAggregationAware:3331`, each one
comment and no statements, un-disabled by an "F2.6" pass citing corpus scores rather than
writing the test. All are inside the 2454 floor, whose own derivation comment says so.

A further **19 tests passed after the referee explicitly DECLINED** and the verdict fell
back to byte-equal SQL text. And **28 tests are absorbed by numeric tolerance**.

---

## 4. Verified defects

Ordered by consequence.

1. **Ordered compare is dead** (§2). 95 tests affected, 11 referee-only.
2. **The gate has one assertion.** `MinimalCorpusTest.java:113`,
   `assertTrue(pass.size() >= floor)`. It is a **count, not a roster**, so a red flip is
   masked by a green one; it is **monotone upward**, so anything that manufactures passes
   is invisible; and it is **skipped entirely** under `-Drcorpus.test` (`if (only.isEmpty())`
   at `:106`). Everything else — the verdict roster, decline buckets, unverifiable census,
   referee outcomes — is `System.out.println`.
3. **`equal(1, 1.0)` has five implementations giving three answers**, and two of them are
   *both* compile-time World-2 folders that disagree: `resolver/LiteralFolds.java:80-86`
   (BigDecimal → **TRUE**) vs `compiler/spec/StaticFold.java:660-667` (`a.equals(b)` →
   **FALSE**). The World-1/World-2 split is deliberate and fenced
   (`EqualityWorldsConformanceTest.java:94` pins it with a stated reason); the
   World-2-vs-World-2 split is fenced by nothing. **No corpus test exercises the shape** —
   `grep` for an int-vs-float equality in the corpus returns zero hits.
4. **21 of 121 fail-roster entries carry no diagnostic** (11 empty, 10 bare `"Assert
   failed"`). Cause: `MinimalCorpusTest.java:73` does
   `String.valueOf(e.getMessage()).split("\n")[0]` and the expected/actual lives on lines
   2-3. This directly contradicts §6f item 5. One-line fix.
5. **127 H2 failures are one bug in the core renderer.** We emit derived-column aliases
   **quoted** and column references **unquoted**; DuckDB's case-insensitive resolution hides
   it completely, H2 (and Oracle, DB2, Postgres, Snowflake) do not. Invisible across the
   entire DuckDB lane.
6. **The referee's timestamp normalizer is broken.** `H2Verify.java:1251-1253` runs
   `s.replaceAll("\\.?0+$","")` on timestamp-shaped strings:
   `'…00:00:00'` → `'…00:00:'` and `'…00:00:10'` → `'…00:00:1'`. The two forms it exists to
   equate now normalize differently. It creates false divergences.
7. **A computed divergence is re-classified into a pass.** `H2Verify.java:419-429` converts
   an already-computed row divergence to a DECLINE when the golden matches a
   `limit|offset|fetch` regex — it fires only when `d != null`, i.e. only after the compare
   failed — and `SqlTextVerdicts.java:1296` turns a decline into a PASS on byte-equal text.
8. **Any referee bug becomes a text verdict.** Three `catch (RuntimeException e) → declined`
   sites (`ReplayOracle.java:209, 871, 907`).
9. **Setup failures are preserved as passes and then erased.** `MinimalCorpus.java:452-457`
   keeps `r.pass()` and appends `" [setup: …]"`; `MinimalCorpusTest.java:75` writes the FQN
   alone for passes. Zero occurrences today — adopt the fix while it is free.
10. **The mirror's seed cursor can desync.** A bare index into a list whose length changes
    between tests (`MinimalCorpus.java:438-467` × `ReplayOracle.java:332`); every recorded
    query-kind statement shifts it and skips one seed. 83 corpus files use `executeInDb`.
11. **Two vacuous tests inside the floor** (§3).
12. **The new guard checks retired names, not the concept.**
    `PlatformNamesGuardrailTest.runtimeShapesAreReadByTheOneReaderOnly` greps for eleven
    retired *method names*; `StatementExecutor.java:1181,1189,1194` reads
    `ni.properties().get("testDataSetupCsv")` raw — in the same block that correctly calls
    `ExecutionContext.Reader.databaseType(ni)`.
13. **Two small sharp bugs** found in the blank-message entries: md5 digest encoding
    (2 tests, one root cause) and Date-vs-DateTime column rendering.

---

## 5. Ambient state and oracle residue

**Twelve ThreadLocals survive in `core/src/main`**, so the plan's §0 claim
*"Execution-option thread-locals in main | ZERO"* is false — and §1a, twelve lines later,
lists four of them under *"Modes that change what the compiler emits."* Two demonstrably do:

- `NullSemantics.java:141` picks `NULL_SAFE_EQUAL` vs `EQUAL` — **which rows a join matches**.
- `CastPolicy.java:50` **deletes a cast from the MIR**, and `StatementExecutor.java:619-621`
  enters that boundary around **`lw.lower(body)`** — MIR construction, not rendering.

`NullSemantics.FILTER_POS` is **provably write-only** (five occurrences: declaration, the
save inside its own setter, set, restore, and a comment claiming it "still gates OTHER
arms"). Plan Appendix A — headed "measured" — lists a reader that does not exist.

**Oracle in the production jar: 9,826 → 9,421 raw lines, −4.1%.** The reduction came
entirely from deleting seven small peripherals (438 lines); **the ten largest adjudicators
net +33 lines**, and `AssertVerdicts`, `SqlTypeCensus`, `CanonicalDivergence`,
`PureAsserts`, `TdsCompare`, `JsonCompare`, `CanonicalForm` are byte-identical to their
pre-rewrite state. Not one line of §6f item 2's deletion list has been touched.

Batch 123's stated reason for keeping the two censuses does not survive inspection: PCT
reads **counters, not behaviour**, in the **same JVM**, through 11 members that are all
print-or-assert. The refactor is two observer interfaces (~25 LOC in main) on an injection
seam (`AssertListener` on `ExecEnv`) that already exists and is proven.

**~500 lines inside `CanonicalDivergence`/`SqlTypeCensus` are provably dead** — the whole
V7 block (`:401-680`) has zero production and zero PCT callers, and its only exerciser
(`V7DualChannelCensusTest`) feeds the API its own inputs and asserts the counters equal
what it just fed in.

---

## 6. Guards

Every one of the prior audit's seven blind spots is **still open**, and the suite missed §2
because no guard checks that class.

| Pin | Pinned | Measured | Slack |
|---|---:|---:|---:|
| `EVICT_SIZE["StatementExecutor.java"]` | 2699 | 2047 | **652** |
| `EVICT_SIZE` total | — | — | **751 lines** |
| `BROAD_CATCH_COUNTS["EngineTestExecutor.java"]` | 5 | file deleted | **bearer bond** |
| `SqlTextRatchetTest` coverage floor | 250 | 604 | **354 files** |
| `defaultLiteralFallbacksOnlyShrink` | 3 arms | 479 exist | **99.4% unpinned** |

Cause of the first: `EVICT_NAMES` fails on shrink *and* growth; `EVICT_SIZE` only on growth,
so every deletion banks headroom.

**Twelve javadoc "THE ONE OWNER" claims are contradicted by code**, and only three have any
guard — all shrink-only registers whose existence disproves the wording. Notable:
`AsorRef` vs a second full decoder as a hardcoded regex (`AsorReaders.java:99`);
`Multiplicity` "ONE owner" vs `Typer.java:2655-2666` summing bounds inline with its own
private `[1..1]` fallback; `PureAsserts.equal()` "ONE owner" vs `TdsCompare.java:344-373`, a
complete second wire-value comparator that never calls `equalScalar`.

**Good, and worth protecting:** every core guard runs in CI (gate 1 has no `-Dtest` filter);
the `surefire.excludedGroups` override is clean; and the exact-set ledgers
(`RawSqlLedgerTest`, `SqlTextRatchetTest`, `JdbcSurfaceCensusTest`, `HarnessDisciplineTest`,
`SkipCensusTest`, `CarrierPurityRatchetTest`) fail in **both** directions and are all
currently exact. That design is why the rewrite's register cleanup was *forced*.

---

## 7. The floor arithmetic does not close

Measured categorization of all 121 DuckDB failures:

| Category | Count |
|---|---:|
| REAL-DEFECT | **37** |
| ENGINE-MACHINERY | **36** |
| TEXT-ONLY | **29** (9 of them unadjudicated, not proven-equivalent) |
| OTHER-STORE | **10** (plan says 8) |
| CODE-AS-DATA | **7** |
| UNKNOWN | 2 |
| DATA-NONDETERMINISM | **0** |

Solving §7's end state against this forces **X = 16, Y = 0 exactly**: every one of the 37
real defects must burn with zero unburnable residue. There is no slack. And **"step 5
families ≈ 45" exceeds the entire measured pool** of engine-machinery + code-as-data (43),
while §7 simultaneously keeps "engine machinery outside code-as-data" in the residue. The
121 → ~105 step is credible; ~55 as written is not.

Three practical corrections: Appendix B's set-difference command produces a **false
regression** today (it compares name+message and one message drifted — compare names);
`mvn`/`java` are not on the non-interactive PATH; and §6c's named order-dependence example
(`relationalResultSourcingOfListExecutionPlan`) **does not reproduce** — it fails identically
scoped and full.

---

## 8. The H2 lane

**1866/709, and 590 fail only on H2.** Three families cover 82.5%:

| Family | Count |
|---|---:|
| declared `DialectCapability` gaps | 280 |
| H2 missing functions | 80 |
| **`Column "tN.X" not found`** | **127** |
| row/value asserts | 66 |
| other | 37 |

So ~61% is "gaps we chose not to close" and ~39% is not. **The 127 are one renderer bug**
(§4.5) and 11 more are one `union needs at least two branches` bug.

**Recommendation:** do not retire the lane first. Close those two legs (~138 tests), then
what remains genuinely *is* the emulation decision and "advisory with a floor" becomes
honest. Retiring first deletes the only detector of a real cross-dialect bug in the core
renderer. Note also that on the H2 lane `verifyAuto` routes to `verifyOnSession`
(`ReplayOracle.java:432`) — the golden runs on the *same connection* as our query, so that
lane is **not** an independent oracle and must not be read as one.

---

## 9. Claims investigated and dropped

| Claim | Outcome |
|---|---|
| "3 vacuous enabled tests" | **2** — `functionBodyParsesNestedLambdaBraces` is fully implemented; my 3-line window hit its comment block |
| "Oracle in main down 16%" | **−4.1%** on a like-for-like file set; my figure conflated deleted files with shrunk ones |
| "21 roster entries with no reason" (first two attempts) | **confirmed at 21** on the third filter — 11 empty + 10 bare `"Assert failed"` |
| "`QueryService.execute` has zero callers" | it has test callers; the correct statement is that no *production* path reaches it |
| "Harness is ~1.1k lines" | **3,815** — driver 1,123 + `harness/` 2,692. Still a 4× reduction |
| "Denominator may be silently shrinking" | **No.** 2,721 declared − 146 excluded = 2,575 exactly; parse walls are fatal |

---

## 10. What DONE means for core_relational

The authors' §6f gets four things right — scoped-equals-full, every failure named, push
judgment into the database, zero ambient state — and omits everything about **strength**.
Under §6f as written, a run in which the oracle declined every time and 1,511 tests
degraded to spelling checks would be DONE. These are the criteria I would gate on. Each is
phrased so a machine decides it.

1. **The denominator is re-derived, not remembered.** The harness prints
   `discovered/excluded/declared` and a test asserts the triple against a scan of the corpus
   tree. *Today: 2575 correct, gated by nothing.*
2. **The roster is pinned as a SET, per lane** — `assertEquals(expectedSet, actualSet)`
   against a committed roster, replacing `pass.size() >= floor`. A red flip must not be
   maskable by a green one. Add a **ceiling** so a pass-count jump must be explained.
3. **A verification-strength budget is gated.** Per lane, per test, emit the strength class
   of §3 and assert `differential >= 1511 && textOnly <= 39 && zeroAssert <= 32 &&
   weakOnly <= 25`, moving monotonically in the right direction.
4. **Zero silent zero-assertion passes.** `Compiler.callsVerdict` descends into user-function
   bodies; a test adjudicating zero verdicts is classified `SKIPPED (no assertion reachable)`
   and **excluded from the pass count**. *Today: 32 counted green, including 2 vacuous.*
5. **Order independence is a fact, not an accident.** Either wire `ORDERED_QUERY`/`SORT_KEYS`
   from `AssertVerdicts.orderView` (the live derivation exists) or delete the dead machinery
   and declare unordered the contract in one place. Gate: every `ThreadLocal` declared in
   `com.legend.harness` has ≥1 `.set(` site; and `[ord]` firings are 0 or every one is in a
   committed register. *Today: 136 firings across 117 tests, registered nowhere.*
6. **Every leniency has a counter and a committed ceiling** — the 2-ULP grant, the CSV cell
   tolerance, the referee's `MathContext(10)` rounding and microsecond flooring, the fanout
   collapse, the stitch-key drop. The last two currently have no counter at all.
7. **No uncounted declines.** Every path returning `textEqual ? ok() : fail(...)` records a
   named decline first. *Today `SqlTextVerdicts.java:1258-1273` and `:151-160` do not, which
   is why the decline count is 24 against a text-decided population of 58.*
8. **Referee faults fail, not decline.** Split DECLINED into a modeled gap and a FAULT
   (SQLException on our own seeding, `RuntimeException` in the compare, missing extension
   function). FAULT never falls back to text. Gate: zero `catch (RuntimeException)` in the
   referee.
9. **A semantic parity suite in the platform, not the corpus** — one assertion per row of
   the fidelity table: `equal(1,1.0)` decided once across `LiteralFolds`, `StaticFold`,
   `PureAsserts` and SQL pushdown; runtime `[] == []`; `indexOf`/`substring` base pinned per
   lane; `sort()` over mixed kinds pinned to one total order. The corpus cannot test what it
   does not cover.
10. **Scoped equals full**, mechanized over a ≥50-test sample.
11. **Setup failure is fatal**, and `INERT_SETUP` elisions are counted. *Free to adopt today
    — zero occurrences.*
12. **The H2 lane's fate is decided in code**: either a portability lane with its own pinned
    roster that prints `oracle=same-session`, or retired — after the 127-test renderer bug is
    fixed, not before.
13. **The dangling-state guard exists** (§2), and no guard comment names a symbol absent
    from the tree.
14. **Every `EVICT_SIZE` row is tightened to measured and fails on shrink**, as `EVICT_NAMES`
    does. *Starting slack: 751 lines.*
15. **"Zero unnamed failures" is the definition of zero** — every failing test carries a
    reason in the granular ledger, and the roster prints ≥3 lines of the message.

**What DONE is not:** a number. 2,575 green on this lane would still buy only
single-threaded, small-data, H2-shaped, happy-path relational semantics — the corpus tests
no dialect we execute on (`DuckDB` appears zero times in it), 4 `assertError` sites in
total, 3 files mentioning transactions, and nothing about concurrency, volume, plan reuse
or connection lifecycle.
