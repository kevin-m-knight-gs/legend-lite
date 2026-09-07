# The driver — `MinimalCorpus` and friends

`core/src/test/java/com/legend/rcorpus/`: `MinimalCorpus` 581 · `MinimalCorpusTest` 117 ·
`Corpus` 234 · `DuckWorkspaces` 139 · `LibraryPlatformNamespaceGuardTest` 52 = **1,123 LOC**.

**Verdict: the driver does not meet its own §6f item-1 standard, but it is close and the gap is
small and named.** It still interprets Pure in exactly one place — the vacuous-body check — and
setup *discovery* is shape-derived rather than stereotype-derived. **The single worst problem is
not any of that**: the entire run has one assertion, and every other number is decoration.

---

## 1. Does it interpret Pure?

**Yes, in one place, plus two shape rules over declarations.** Everything else comes through
`Compiler.programFacts` / `ProgramFacts`, correctly.

**1. `MinimalCorpus:418-420` — the vacuous check (the violation).**

```java
if (body.size() == 1 && body.get(0) instanceof CBoolean cb && cb.value()) {
    return new Result(t.fqn(), true, 0, "vacuous (engine body = true)");
}
```

An arity test plus an `instanceof` over a parsed protocol node plus a value test, deciding PASS
without resolving, typing or executing. §6f item 1 names it; §6e (`END_TO_END_PLAN:284`) says
*"the harness's vacuous-body check (`body == true`) becomes a `ProgramFacts.vacuous` fact"*. It
has not.

**2. `MinimalCorpus:183-186` — setup discovery by arity, not stereotype.**

```java
if (el instanceof FunctionDefinition f && f.parameters().isEmpty()
        && f.stereotypes().stream().noneMatch(st -> st.stereotypeName().equals("Test"))) {
    sharedSetups.add(f.qualifiedName());
```

Every zero-arg non-`Test` function in the four shared fixture sources becomes a setup candidate.
**Mitigating:** whether it *runs* is decided by the platform (`Compiler.hasStatementEffects`,
`:371`), and `relationalSetUp.pure` has only three zero-arg functions, of which the effect scan
admits one. **But the selection rule is a shape test**, and the stereotype compare here omits the
profile check that `discover()` at `:245` does perform.

**3. `MinimalCorpus:256`** — `if (setup && f.parameters().isEmpty())`. Same arity gate on
`BeforePackage`.

**4. `MinimalCorpus:245-254` — stereotype dispatch by bare string.**

```java
if (!(st.profileName().equals("test")
        || st.profileName().equals("meta::pure::profiles::test"))) {
```

Profile identity by string equality against two spellings rather than resolved through the
model's imports — the plan's own standing rule says "no string matching on identities"
(`END_TO_END_PLAN:17`). A different profile also named `test` would be honoured.

**5. `MinimalCorpus:260-267` — the driver fabricates an import.** It appends the test's own
package to the wildcard list the model declared. A name-resolution policy decision made by the
harness, not read from the source. **It changes how the body resolves.**

**6. `MinimalCorpus:555-569` (`engineSuiteOrder`) — `fqn.split("::")` string surgery.** Ordering
only, no verdict flows through it, but it is the harness re-deriving package structure from text.

**Not found (good):** no regex over Pure source, no keyword matching on bodies, no `instanceof`
over *typed* nodes, no re-derivation of effects/seeds/asserts. `facts.effects()` (`:480`),
`facts.seedsInlineCsv()` (`:435`) and `facts.verdicts()` (`:509`) all come from
`Compiler.programFacts` (`:431`). **`Corpus.java`'s brace-scanning text surgery (`:105-212`) is
dead** — `skipFunction` is private and uncalled; nothing outside calls `skipString`.

---

## 2. Every path to a false pass

| # | Path | Status | Deciding citation |
|---|---|---|---|
| 1 | **Vacuous body** — PASS without executing | **REACHABLE** (2 tests) | `MinimalCorpus:418-420` |
| 2 | **Body executes, asserts nothing** — falls through to PASS "ran, no asserts" | **REACHABLE** (≥1: "the assert-free twin") | `:509-519` — failure only set `if (… verdicts.isEmpty() && facts.verdicts())` |
| 3 | **Partial adjudication** — N asserts, 1 adjudicated, the rest executed but never judged | **REACHABLE IN PRINCIPLE, impact unverified** | `ProgramFacts:16` — `verdicts` is a **boolean, not a count**; `:509` guards only `verdicts.isEmpty()`. Nothing compares verdicts received to verdict calls in the program |
| 4 | **Setup failure leaves the test trivially true** | **REACHABLE** | `:392-394` collects failures; `:452-457` appends them to the *reason* and **preserves `r.pass()`**; `MinimalCorpusTest:75` then writes only `r.fqn()` for passes — **the `[setup: …]` annotation is discarded and never printed** |
| 5 | **Exception swallowed into a pass** | **NOT REACHABLE** | `:500-505` converts any `RuntimeException` into `failure`; `:506-508` fails on any recorded `false` verdict even if the platform swallowed the raise; `MinimalCorpusTest:70-74` converts an escaping `Exception` into a FAIL. **Genuinely fail-closed** |
| 6 | **Referee decline degrades to text-only** — if `org.h2.Driver` is off the test classpath, `H2Verify.ready()` is false, the mirror is never built (`:313`) and every golden verify DECLINES | **REACHABLE (remote), and the floor cannot catch it** | `H2Verify:49-61`; the DECLINED arm at `SqlTextVerdicts:1293-1298` yields `ok()` when the *text* matches. **Rows-diverged-but-text-equal cases flip FAIL→PASS, so `pass.size() >= floor` is satisfied *more* easily.** The driver asserts nothing about `H2Verify.ready()` |
| 7 | **Scoped run always green** — with `-Drcorpus.test` the floor assertion is skipped entirely | **REACHABLE** | `MinimalCorpusTest:106`: `if (only.isEmpty()) { … assertTrue … }`. `allgates.sh` does not pass it, so this is a hand-run hazard |

**Not a false pass, but worth stating:** a DIVERGED referee row verdict **does** fail the test
(`SqlTextVerdicts:1290`), so the driver's comment "no verdict flows through it" (`:401-403`) is
accurate only for `H2Verify.CURRENT_TEST`/`VERDICT_ROSTER`, not for the oracle as a whole.

---

## 3. Discovery and the denominator

**How it works.** All corpus `.pure` files under `Corpus.RELATIONAL` are walked (`:202-209`),
deduped by file *text* against the four shared fixtures (`:106-116`), parsed as ONE model
(`:145-147`), and every `FunctionDefinition` carrying `<<test.Test>>` under profile `test`, not
carrying `ToFix`/`Ignore`/`ExcludeAlloy`, and not owned by a library source, becomes a `TestCase`
(`:232-273`). **Exclusion is read from the parsed model**, not from a list — with two exceptions
below.

**A file that fails to parse is accounted LOUDLY. This is the strongest property of the rewrite:**

```java
// MinimalCorpus:152-158
if (!parseWalls.isEmpty()) { throw new IllegalStateException("corpus parse walls: " + parseWalls); }
if (!parsed.duplicateElements().isEmpty()) { throw new IllegalStateException("corpus duplicate elements: " …); }
```

Thrown from the constructor, which `MinimalCorpusTest:53` calls outside any catch → the JUnit
test errors. **A corpus parse regression cannot shrink the denominator; it detonates.** Library
files that fail to parse are skipped by name and reported (`:140-142`), and because their
elements then vanish, corpus tests referencing them fail at `resolveQuery` (`:426-429`) — so the
loss surfaces as FAILs and breaches the floor. **Both accountings are correct.**

**Independent census.** Against `~/legend/legend-engine`: 543 `.pure` files in the relational
tree, 2,727 lines applying `test.Test`, of which 148 also carry `ToFix`/`ExcludeAlloy` →
**2,579** expected runnable versus the claimed **2,575**. A 4-test gap attributable to multi-line
stereotype blocks and 6 non-`function` matches. **The claimed denominator is consistent with the
source.** No duplicate-content `.pure` files exist today (`md5 | uniq -d` → 0), so the text-dedup
at `:112` currently drops nothing — a latent hazard only.

**Where the denominator is nevertheless soft:**

- **Nothing pins it.** `MinimalCorpusTest:84` prints `"of " + (pass.size() + fail.size())` — the
  number of tests *run*, computed from itself. There is no census constant, no `assertEquals` on
  `corpus.tests().size()`, nowhere in `core/src` or `pct/`. **`GATES.md:40` still tells the
  reader to watch for "`census: 2759` instead of `2798`" as the wrong-checkout tell — that census
  line no longer exists**; it died with the old runner. The documented detection procedure is dead.
- **The floor is one-sided.** `pass.size() >= 2454` catches lost *passing* tests but is blind to
  lost *failing* tests, to a shrinking denominator, and to passes gained by the vacuous/
  assert-free routes.
- **Two hardcoded exclusions.** `ENGINE_IMPLEMENTATION_FILES = Set.of("lineage/scanRelations/scanRelations.pure")`
  (`:79-80`, applied at `:205-206`) — a file dropped by name. Verified to contain **0**
  `test.Test`, so it costs nothing today, but it is exactly the mechanism that could silently
  drop tests tomorrow. `Corpus.LIBRARY_FILES` (`Corpus:69-80`) is a second hardcoded list; its
  first entry lives *inside* the walked corpus tree, so it is added as a corpus source first and
  the library loop's `if (!seen.add(text)) continue` skips it — inert, harmlessly so (0 tests).
- **235 tests are excluded by construction and never counted.** The M2M tests dir (231
  `test.Test`) and the graphFetch domain (4) are loaded as model-only and filtered out via
  `libraryElements` (`:234-236`). Defensible scoping, but **nothing reports the number**.
- **The exclusion set does not match the engine's.** The platform profile is
  `[Test, TestCollection, BeforePackage, AfterPackage, ToFix, ExcludeAlloy, ExcludeLazy,
  ExcludeModular, AlloyOnly, ExcludeAlloyTextMode]` (`legend-pure/…/essential/tests/profile.pure:17`).
  **`"Ignore"` is not a stereotype in that profile** — the `case "ToFix", "Ignore",
  "ExcludeAlloy"` arm (`:251`) contains a **dead name**, evidence the set was written from memory
  rather than from the model. Conversely the engine's own filter is `satisfiesConditions`
  (`PureTestHelperFramework:171-176`) = `!ExcludeAlloy && !shouldExcludeOnClientVersion &&
  shouldExecuteOnClientVersionOnwards`; the corpus tree has **217 `serverVersion.start` tagged
  values**, 28 of them on `test.Test` functions in `testDataGeneration.pure` alone. **The driver
  honours no version condition.** Impact today: **unverified** (the tags are old versions, so the
  engine likely includes them all too), but the driver's set is not the engine's set by
  construction.

**Silent-skip analysis (§8's warning).** Still live *in the driver*:
`Assumptions.assumeTrue(Corpus.available(), …)` (`MinimalCorpusTest:35`) with
`Corpus.available()` = `Files.isDirectory(RELATIONAL)` and `ENGINE_ROOT` defaulting to
`$HOME/legend/legend-engine`. **A mangled or mistyped `-Dlegend.engine.root` still yields a green
0.5s skip.** The mitigation is entirely outside the driver: `tools/allgates.sh` checks
`roots_present()` (`:63-69`) *and* runs a `skipped()` awk detector (`:71-87`). CI no longer
pretends to run gate 4 (`gate.yml` explicitly says the corpus is NOT run — **`GATES.md:47-53`'s
claim that CI runs a vacuously-green gate 4 is stale**). So: not a chain-level hole, **but a real
one for any hand-run `mvn`**, which is precisely how the incident in §8 happened. The driver
should refuse, not assume.

---

## 4. Shared state and order dependence

The risk §6c describes is real and its mechanisms are nameable.

- **`:281-290` — the package session.** `sessionConn`, `mirrorConn`, `sessionPkg`, `setupsDone`,
  `seedLedger`, `setupPrograms` are instance fields shared by every test of a package. Reset only
  at `beginSession` (`:308-323`).
- **`:515` — a passing effectful test COMMITS into the shared session.** `commitAttempt(conn)` on
  the pass path; rollback only on failure (`:523-526`). So test *k*'s writes are visible to tests
  *k+1…n*, **and only when test *k* passed** — order dependence coupled to verdicts, the worst kind.
- **`:438-442, 462-467` — the seed ledger is carried forward by design.** `recording.addAll(seedLedger)`
  prepends every prior non-query statement; the `finally` rebuilds `seedLedger` from this test's
  non-queries. Test *k*'s golden replay depends on tests 1…*k−1*. Deliberate and documented
  (`:286-287`) — **and exactly why a scoped run diverges from a full run.**
- **`DuckWorkspaces:70-72` — `private static DuckDBConnection root`, `static AtomicInteger IDS`,
  `static Set<String> LIVE`.** One long-lived DuckDB instance for the whole JVM, never closed.
  Isolation is per-*catalog* (`ATTACH ':memory:' AS __ws_N` + `USE`, `:91-102`), which is
  **per-session, not per test** — tests in the same package share one workspace. `SET threads=1`
  (`:83`) is instance-global.
- **`:443-450` — `TestResources.register(...)` is called once per test and never cleared.**
  `TestResources.RESOLVER` is a `ThreadLocal`; surefire reuses threads, so **this resolver leaks
  into every later test in the JVM**.
- **`:442` — `RawSqlBoundary.record(recording)`** installs a thread-local recorder, likewise never
  unregistered.
- **`ReplayOracle:83/88` — `MIRROR` is a mutable static**, mutated by `mirrorBegin`/`mirrorEnd`/
  `mirrorSuspend` from the driver (`:313-318, 436, 460`), plus static `OUTCOMES` (`:33`) and
  `ATTEMPT_GOLDENS` (`:127`). `H2Verify.VERDICT_ROSTER`, `UNVERIFIABLE_CENSUS`, `CURRENT_TEST`
  are likewise static/thread-local and read by `MinimalCorpusTest:92-99`.
- **`MinimalCorpusTest:38-47` mutates the global system property `legend.exec.engineScanOrder`** —
  correctly saved and restored, but the corpus test changes JVM-global compiler behaviour while
  it runs.
- **`:290` — `setupPrograms` is never cleared across sessions.** Benign (resolved programs are
  model facts) but an unbounded cross-session cache.

**Concretely: yes, a test can pass only because an earlier one seeded something** — through the
committed shared connection (`:515`) and the replayed seed ledger (`:438-442`). Neither is a bug
in itself; **both are unmeasured.**

---

## 5. Distance to single-shot

Structurally significant. Four blockers:

1. **The body is still N statements, executed by a platform loop.**
   `Compiler.executeResolved(resolved, …)` (`:491`) → `StatementExecutor.execute` →
   `specs.typeQueryBody(resolved)` then `executeStatements(typedBody, …)`
   (`StatementExecutor:68, 80`). The driver hands over a whole body and the platform round-trips
   per statement.
2. **The verdict is a Java callback, not a row.** `judge` collects `List<Boolean> verdicts` from
   an `AssertListener` lambda (`:481, 492-498`); `AssertVerdicts.tryAdjudicate` calls
   `l.verdict(name, true, null)` in Java (`AssertVerdicts:77`). "Read one boolean row" requires
   the verdict to be a SQL expression — plan step 6 item 4, still parked.
3. **Setups are separate executions.** `runSetups` loops `Compiler.executeResolved` once per
   setup (`:376-397`). Single-shot needs them bound into the statement.
4. **The transaction protocol assumes multiple statements.** `beginAttempt`/`commitAttempt`/
   `rollbackAttempt` around the body (`:485, 515, 524`) exists because the body is a
   multi-statement effectful program; one shot would not need a mark-and-rollback world protocol.

**Two things already in place that will survive:** the session/workspace choice is made from
`ProgramFacts` before execution (`:435`), and setups are pre-resolved once per session so nothing
resolves mid-test (`:366-374`).

---

## 6. Code-quality findings

**[HIGH] The run has exactly one assertion; every other number is decoration.**
`MinimalCorpusTest:113`. Fail count, denominator, per-test verdict counts, referee
MATCH/DIVERGED/DECLINED tallies (`:92-97`), decline census (`:98-99`) and library walls (`:54-55`)
are all `System.out.println`. *Why it matters:* the gate is monotone in one direction only; every
mechanism that manufactures passes raises the pinned number. *Fix:* pin the denominator, pin
`fail.size()`, pin `libraryWalls().isEmpty()`, and assert `H2Verify.ready()`.

**[HIGH] Setup failures are recorded into a pass and then discarded** (§2 path 4). *Fix:* either
fail a test whose setups failed, or write the full reason to the pass roster and pin
passes-with-setup-failures at zero.

**[HIGH] `ProgramFacts.verdicts` is a boolean, so partial adjudication cannot be detected.**
`ProgramFacts:16`; guard at `:509`. *Fix:* make it `int verdictCalls` and require
`verdicts.size() == facts.verdictCalls()`; persist `Result.verdicts()` into the pass roster so
assert erosion inside passing tests is diffable.

**[MEDIUM] The vacuous check** — the §6f item-1 violation (§1). *Fix:* `ProgramFacts.vacuous`, as
§6e already specifies; or delete it and let the two placeholders execute.

**[MEDIUM] `-Drcorpus.test` silently disables the only assertion** (`MinimalCorpusTest:106`).
*Fix:* when scoped, assert every selected test passed unless on the known-fail roster; at minimum
fail if the filter matched zero tests.

**[MEDIUM] No guard on a missing/mangled engine root** (§3). *Fix:* fail (not assume) when
`legend.engine.root` was explicitly supplied but does not resolve, and fail when discovery yields
fewer than the pinned denominator.

**[MEDIUM] A setup that fails to resolve poisons its whole package with misleading NPEs.**
`:319-322` sets `sessionPkg = pkg` *before* `deriveSetups(pkg)`; if `resolveQuery` throws inside
`computeIfAbsent` (`:368-372`), the first test fails with the real message, `sessionPkg` is
already set so no later test re-enters `beginSession`, and every remaining test of the package
fails at `Objects.requireNonNull(setupPrograms.get(fqn), "setup derived at session start")`
(`:382-383`). *Fix:* derive setups before publishing `sessionPkg`.

**[MEDIUM] The library loop parses each file twice, discarding the first parse** (`:128-134`).
Pure waste, and it invites the two parses to disagree.

**[MEDIUM] `TestResources.register` is called per test and never cleared** (`:443-450`). The
resolver also does path arithmetic — `Corpus.RELATIONAL.getParent().getParent().resolve(...)` —
two `getParent()` hops encoding the engine's directory layout inline. *Fix:* register once per
corpus run in a try/finally; give `Corpus` a named `RESOURCE_ROOT`.

**[MEDIUM] `Corpus.java` is ~50% dead code, including the text surgery the rewrite was supposed
to retire.** `:105-212` — `skipFunction` (private, uncalled), `skipBraces`, `skipString`;
`:214-230` — **four empty section banners** with nothing under them; `:30` — `DIALECT`, unused.
~110 of the advertised 1,123 lines do nothing, and **the surviving brace scanner is the exact
"text surgery" the plan's standing rules forbid** — it reads as a live capability to the next
reader. *Fix:* delete; the class becomes ~90 lines of paths.

**[LOW] Dead declarations.** `:15` imports `NewInstance`, never used. `:78` — `ASSERTS_PACKAGE`,
never used (the real one is `PlatformTypes.ASSERTS_PACKAGE`). `:251` — the `"Ignore"` case
matches a stereotype that does not exist.

**[LOW] Stacked/misplaced javadoc.** `:341-347` has two consecutive javadoc blocks on
`setupCandidates`, the first describing behaviour that lives in `runSetups`. `:532-539`: a
javadoc paragraph about `testDataSetupCsv` sits immediately above, and is therefore attached to,
`refusePlatformNamespace`. `Corpus:16-26`'s header still describes "MODEL ASSEMBLY — section
markers dropped, a Runtime synthesized" that the class no longer does.

**[LOW] Sentinel-by-identity and asymmetric constants.** `:291` `INERT_SETUP = new CBoolean(true)`
compared with `==` at `:384` — works, but a two-state result type would say it.
`MinimalCorpusTest:31` names `H2_FLOOR = 1866` as a constant while the DuckDB floor `2454` is an
inline literal at `:112`.

**[LOW] `target/` written relative to the CWD** (`MinimalCorpusTest:81-83`).

**God methods:** the constructor (`:103-189`, 86 lines: shared sources, corpus walk, text dedup,
per-library double-parse, namespace guard, wall check, duplicate check, module build, db binding,
execution overlay, discovery, shared-setup scan) and `run0` (`:413-476`, 63 lines: session
choice, vacuous shortcut, resolve, facts, workspace choice, mirror control, ledger install,
resource registration, setups, judge, ledger rebuild, teardown). Both should be split. `judge`
(`:478-528`) with its nested try and `finally`-rollback is dense but each piece is load-bearing.

**Not found:** no `default ->` arm returning a value (the one at `:253` is a correct no-op), no
try/catch as control flow (every catch converts to a named FAIL reason), no mutable statics in
`MinimalCorpus` beyond the id counter, no stringly-typed *dispatch* on test bodies.

---

## 7. What is genuinely good

- **Parse walls and duplicate elements are FATAL** (`:152-158`) — the single highest-value
  property for denominator integrity, and it is right. The independent census (2,579 expected vs
  2,575 claimed) confirms discovery is not quietly losing tests.
- **The facts channel is honoured.** Every behavioural decision that could have been a body scan —
  effects, inline-CSV seeding, presence of asserts — reads `ProgramFacts` (`:431, 435, 480, 509`).
  `hasStatementEffects` even delegates to `programFacts` (`Compiler:785-788`), so there is **one
  implementation, not two.** This is the batch-118 discipline actually held.
- **The verdict path is fail-closed.** Any `RuntimeException` becomes a FAIL with the platform's
  own first line (`:500-505`); any recorded `false` verdict fails even if the raise was swallowed
  (`:506-508`); an assert the platform declines to adjudicate fails the test (`:509-512`); a
  harness-level throw fails (`MinimalCorpusTest:70-74`). **There is no swallow-to-pass.**
- **One executor, one entry.** Setups, bodies and everything else go through
  `Compiler.executeResolved`. No second interpreter, no "recognized test form" table, no shape
  registry. **The single biggest structural win over 13,561 lines.**
- **Setups are derived once per session, before any test executes** (`:366-374`), so nothing
  resolves between a test's resolution and its execution — and the derivation asks the platform
  whether the body has effects rather than guessing.
- **The atomic-attempt protocol is genuinely careful:** rollback truncates the SQL ledger to a
  mark and detaches a mirror that ran ahead (`ReplayOracle:234-243`), and the ordering of
  `judge`'s `finally` before `run0`'s means the seed-ledger rebuild sees the truncated list.
  Someone thought about failure atomicity.
- **`DuckWorkspaces` fails loudly on a leak** (`LEAK_CEILING`, `:68, 85-90`) instead of degrading,
  and ties workspace teardown to `Connection.close()` via the proxy (`:107-126`) so ordinary
  try-with-resources discipline is also the cleanup. Its javadoc gives honest A/B numbers *and*
  corrects an earlier wrong forecast.
- **The platform-namespace guard is applied to every source, not just libraries** (`:151`), with
  its own dedicated test carrying both a positive and a negative case.
- **The engine's suite traversal order is reproduced deliberately** (`:555-569`) rather than left
  to hash order.
- **`MinimalCorpusTest` restores the system property it mutates** (`:38-47`) — hygiene usually
  skipped.
- **Comments are unusually high-value** — several encode incidents (the other-account `$HOME`
  checkout, the doc-block-as-body parse bug, the mirror-extension omission) rather than restating
  code.

---

## 8. What DONE means for the driver

Beyond the authors' four. Each is checkable; none requires new architecture.

1. **The denominator is pinned.** `assertEquals(EXPECTED_TESTS, corpus.tests().size())` with the
   constant justified by a written census. *Today 2,575 exists only in prose.*
2. **The fail count is pinned too, and both floors are two-sided.**
   `pass.size() == floor && fail.size() == expectedFails`, or a set difference against a committed
   roster file. **A pass count that only ratchets up rewards every false-pass mechanism.**
3. **The pass roster carries evidence, not just names** — `fqn :: N verdict(s)` for every pass, so
   a test dropping from 5 judged asserts to 1 is a visible diff. Persist `Result.verdicts()`.
4. **Zero passes with a non-empty reason.** No test may land in the pass roster carrying
   `[setup: …]`, `vacuous`, or `ran, no asserts`. Assert `passesWithSetupFailures == 0`; pin
   `vacuousPasses` and `assertFreePasses` at their exact current values (2 and 1) so a third
   cannot appear unnoticed.
5. **Every verdict call is adjudicated.** `verdicts.size() == facts.verdictCalls()` per test, with
   the boolean in `ProgramFacts` replaced by a count. **Without this, "the platform judged it" is
   unfalsifiable.**
6. **The run refuses to skip.** If `legend.engine.root` was set explicitly, a missing corpus is a
   FAIL, not an `Assumption`. Additionally assert `H2Verify.ready()` and pin the referee's
   DECLINED count with a ceiling — **a referee that silently stops refereeing must break the
   build, not raise the score.**
7. **A scoped run is a real run.** With `-Drcorpus.test`, assert every selected test passed unless
   on the committed fail roster, and fail if the filter selected zero tests.
8. **Exclusion is read from the model, wholly.** Delete the dead `"Ignore"` arm, delete
   `ENGINE_IMPLEMENTATION_FILES`, resolve the `test` profile through imports instead of comparing
   two string spellings, and either implement the engine's `satisfiesConditions` version predicate
   or write down, with the count, why the 217 `serverVersion.start` tags are irrelevant.
9. **Setup discovery is by stereotype.** `<<test.BeforePackage>>` only; no arity rule. If
   `relationalSetUp.pure` really needs `createTablesAndFillDb` without the stereotype, name that
   one function explicitly and say why.
10. **No fabricated imports.** Either the model declares the test's own package as in scope or the
    platform's resolver adds it; the driver must not.
11. **Per-test isolation is demonstrated, not assumed.** The 6c gate as written, **plus a positive
    statement of what is deliberately shared** — the committed session (`:515`) and the replayed
    seed ledger (`:438-442`) are *designed* order dependence and must be named as such, not
    discovered again in six months.
12. **The dead code is gone.** `Corpus.skipFunction`/`skipBraces`/`skipString`, `Corpus.DIALECT`,
    `MinimalCorpus.ASSERTS_PACKAGE`, the `NewInstance` import, the four empty section banners.
    **A driver claiming "it interprets no Pure" cannot ship a brace-balancing Pure scanner, even a
    dead one.**
13. **The honest LOC number is stated.** The driver is 1,123 lines, but the corpus lane also owns
    `core/src/test/java/com/legend/harness/` at 2,692 — **3,815 total.** Still a ~4× reduction from
    16.6k and an excellent result; it just should not be quoted as 1.1k.
14. **`GATES.md` is corrected.** Its "read this before trusting a green" section still names a CI
    step that no longer exists and a `census: 2759` tell that no longer prints. **The skip trap it
    warns about is real; the detection procedure it prescribes is dead.**
