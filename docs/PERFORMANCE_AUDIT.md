# Performance audit — the test loop

> **Questions asked:** (1) the engine tests used to run in ~2 min and PCT-on-DuckDB in ~1.5 min —
> how long do they take now? (2) why does running engine + PCT + corpus in parallel get everything
> **killed**? (3) where are the algorithmic bottlenecks in `core/`?
>
> **Companions:** `BACKEND_PORTABILITY.md`, `AGENTS.md` (the gate protocol this audit is about).

**Evidence standard.** Every timing below was **measured on this machine** (32 GB RAM, 10 cores —
4 performance + 6 efficiency) with `-Xmx` bounded so the measuring run could not itself contribute
to a kill. Memory defaults were read out of the running engines, not from documentation. Where a
number is contended by a concurrent run, it says so. **One hypothesis in an earlier draft of this
audit was wrong and was killed by measurement** — see §5.

---

## 1. Verdict

**The suites are not broadly slow. One is, and it is the one you remembered.**

| Suite | Tests | Time now | Baseline | Verdict |
|---|---:|---:|---:|---|
| `core` | 1,595 | **8.8 s** | — | healthy |
| `engine` unit | 2,721 | **18.6 s** | 51 s pre-carrier | healthy |
| `pct` | 974 | **40.2 s** | 43.9 s pre-carrier | **no regression** |
| **corpus sweep** | 1 `@Test` / 2,019 fns | **297.7 s** | **211.4 s** (Jul 31) | **+41% — this is it** |

- **"Engine tests took 2 min" was the corpus sweep, not the engine unit tests.** The unit tests run
  in 19 s. The sweep is a *single* `@Test` (`RelationalCorpusRunner.scoreboard`) that drives 2,019
  corpus functions, and it has gone from 3.5 min to ~5 min.
- **"PCT took 1.5 min" is still true** — 40 s of test time inside a ~2 min module build. PCT did not
  regress; measured at the pre-carrier commit it was *slower* (43.9 s), not faster.
- **The kills are arithmetic, not a leak.** §3.
- **The compiler's own algorithms are chronically, not recently, slow.** §5.

### 1.1 In plain terms

**Why parallel runs die:** nobody ever set a memory budget. DuckDB decides on startup that it may
use 80% of all RAM — *per instance*, with no knowledge that other suites exist. Java defaults to
8 GB. The build tool has no configuration at all, so neither gets overridden. The tests then create
~2,000 database instances per run, each with its own 10 threads on a 10-core machine. One component
genuinely does grow without limit (the corpus runner's per-test cache), and that is what dies first.
On top of all of it, **two checkouts under two user accounts run gates on this machine at the same
time**. It is not a leak — three programs were each told they may take more memory than the machine
has, and two people are running them at once.

**Why it got slower:** only one suite did. The corpus sweep rebuilds the entire database from
scratch for every one of its 2,019 tests — new database, every table recreated one statement at a
time, all setup code re-run through the full compiler. That is the equivalent of reinstalling the
operating system before each unit test. Everything else is healthy, and the engine unit tests are an
order of magnitude faster than the "2 minutes" they were remembered as.

**The one thing to know about the compiler:** there is a cache built for exactly this problem, whose
own Javadoc calls it *"the one sanctioned cache in core"*, and **nothing in production calls it**
(§5.2). Meanwhile every model compile redoes the full parse-and-check across ~4,200 tests that
mostly compile identical models.

---

## 2. Why parallel runs get killed

Measured defaults, per instance, nothing in the repo overriding them:

| | value | where |
|---|---|---|
| DuckDB `memory_limit` | **25.5 GiB** (80% of RAM) | unset anywhere |
| DuckDB `threads` | **10** (every core) | unset except `Runner.java:52` |
| JVM max heap | **8.0 GB** | no surefire config exists at all |

**Read these as ceilings, not reservations** — DuckDB does not pre-allocate its limit, and a JVM
does not commit `-Xmx`. The point is that **nothing bounds the sum**: one JVM + one DuckDB are
*permitted* 33.5 GB on a 32 GB box, three suites ≈ 100 GB, and no component is told to stay
smaller. DuckDB does allocate aggressively toward its ceiling for hash joins and sorts, and it sizes
that ceiling from *total* RAM with no knowledge that two other suites exist. Nothing has to leak for
the OOM killer to fire; it only takes two suites reaching for the same headroom at once.

The precise mechanism is aggravator 2 below — unbounded JVM-heap retention is what actually dies
first, and the memory ceilings are what remove the margin that would otherwise absorb it.

Three aggravators, all measured:

1. **Instance counts.** engine unit creates **~1,970** DuckDB instances per run; the corpus sweep
   **≥2,019** (up to 4× on retry); `pct` **≥1,109**. Each carries its own 10-thread pool — on 10
   cores.
2. **Unbounded retention — the actual kill mechanism.** `Runner.java:96`'s `moduleCache` holds
   complete compiled `ModelContext`s for the whole sweep, keyed **per test**, and the retry loop at
   `Runner.java:1016-1057` mints extra keys plus up to 4 recompiles and 4 sessions per throwing
   test. That is unbounded growth across 2,019 tests inside an 8 GB heap.
3. **Two checkouts on one machine.** `/Users/neemsandv/legend/legend-lite` and
   `/Users/neema/legend/legend-lite` run gates concurrently. During this audit both were running PCT
   simultaneously; measured cost of that contention on one PCT run was **+43 s (2:06 → 2:49)**.

---

## 3. The regression: the corpus sweep, +41%

> **Superseded in part — see `CORPUS_SWEEP_PERF.md`.** A later JFR profile showed this section's
> diagnosis (per-test database reseeding) is **not** the dominant cost: `openSession` is 0.1% of
> samples and there are no JDBC frames in the top 25. The sweep is dominated by **module
> recompilation** (`moduleContextFor`, 61.7%), of which 66% is redundant. That document also records
> the −21% fix that landed and five other falsified hypotheses. Read it before acting on this
> section.

`RelationalCorpusRunner` reseeds **the entire family database per test**
(`Runner.java:1200-1203`): a fresh DuckDB, then DDL for every table of every database in the module
emitted statement-by-statement through `h2ToDuckDb` + `prepareStatement`, then every setup Pure
function re-executed through the full compile → plan → execute stack. 2,019 times.

**Fix:** seed once per `(family, moduleKey)`; isolate tests by transaction rollback rather than by
rebuilding the world. This is the single largest time lever in the repo.

---

## 4. The harness — measured counts

| Module | classes | tests | DuckDB instances/run | fixture compiles |
|---|---:|---:|---:|---:|
| core | 82 | 1,576 | **25** | 25 |
| engine (unit) | 94 | 2,633 | **~1,970** | 1,967 + ~855 per-query |
| engine (corpus) | **1** | 2,019 fns | **≥2,019** (4× on retry) | cached, unbounded |
| pct | 5 | ~1,109 | **≥1,109** | ≥1,109 |
| nlq | 13 | 102 | 0 | — |

**`core` is the model to copy** — all 25 classes use `private static Connection` + `@BeforeAll`.
Zero `@BeforeEach`. No leaks. No change needed.

**`engine` is the outlier** — 94 `@BeforeEach`, of which **24 open a fresh `jdbc:duckdb:` per test
method** (`DuckDBIntegrationTest.java:51-56` is the shape, covering 1,967 tests). Complete
`@BeforeEach` cost measured at **9.6–22.5 ms** → **20–44 s** of the suite. Hoist to `@BeforeAll`,
memoize the compiled model (the source is a compile-time constant), batch the inserts.

A fresh in-process DuckDB costs **4.85 ms more than reusing one (14×)**, measured over 40
iterations. Bounding `threads=1`/`memory_limit=512MB` does *not* reduce creation cost (5.22 → 4.96
ms) — the thread pool is not the startup cost, but it is the concurrency and memory problem.

---

## 5. `core/` algorithms — chronic, not regressive

**The prime suspect was wrong.** `CarrierStrategies` (`97b696fd`, wired first in every dialect's
`passes()`) adds one MIR walk per render — 6 → 7 on DuckDB, ~+17% MIR traversal — and on DuckDB both
hooks early-return on `caps.nativeLists()`, degenerating to an allocation-free identity walk. Wrong
order of magnitude for minutes. **The PCT bisect confirms it: 43.9 s pre-carrier vs 40.2 s at HEAD.**

The real algorithmic findings predate the window:

1. **`Pipelines.closeOverConditions` (`resolver/Pipelines.java:226-256`)** — a `while (grew)` fixpoint
   wrapping three nested loops (closed-aliases × all-slots × condition statements), each innermost
   step a full recursive `TypedSpec` walk via `referencesAliasOn`. **O(k³·b·|cond|)** in join-slot
   count, with `k²` throwaway `List.copyOf` and `k³·b` throwaway `Set.of()`. The waste is structural:
   `referencesAliasOn` *already takes a set of aliases* and is called once per candidate with a
   singleton. Called from 13 sites. **Fix:** one collecting walk (the codebase already has this shape
   in `collectSlotReads`) plus a worklist instead of re-scanning `closed` → **O(k³) → O(k)**,
   behaviour-preserving.

2. **There is no model-level memo.** `Compiler.compileModel` reruns parse → resolve → normalize →
   eager `ModelIntegrity.check` on every call. `cache/ContentStore` — whose own Javadoc calls it
   *"the one sanctioned cache in core"*, content-addressed so it cannot desync — has **zero
   production call sites**. With ~4,200 tests mostly compiling byte-identical models, this is the
   biggest suite-time lever in `core/`.

3. The same per-alias re-walk anti-pattern at five more sites (`StoreResolver:1432,1759`,
   `GraphEmission:2417`, `CorrelatedSubselects:1221,1253`); `Pipelines.walk` using full-subtree
   predicates as `switch` guards (O(depth×tree)); five normalizer sites rescanning the whole model
   per property binding; memo-free `LayoutTypes.sqlTypeOf` / `ClassLayouts.layoutOf`, which walk a
   class DAG as a tree.

**Verified clean** (so absence of findings is informative): `Pattern.compile` /
`DateTimeFormatter` / `String.format` — zero hot-path hits; all dialect registries `static final`;
no IR `toString()` on hot paths; `ModelBuilder` O(1) throughout; `SqlRewriter.mapList`
identity-preserving; exactly one fixpoint loop in the entire codebase.

---

## 6. Two correctness findings, outside the brief

- **`Corpus.java:31-32` defaults `ENGINE_ROOT` to `/Users/neema/legend/legend-engine`** — another
  user's home, which **exists and is readable from this account**. Any sweep run without
  `-Dlegend.engine.root` silently reads a tree that a different session is actively modifying.
  Make it fail loudly instead of defaulting.

  > **RESOLVED 2026-08-05** (commits `9f9c0240`, `1e4b93d9`). Two changes, both verified:
  > (1) the default now resolves under `user.home`, matching `parser-equivalence`'s
  > `Corpus.engineRoot()` — the no-override invocation is green and byte-reproduces the committed
  > baseline; (2) the sweep no longer rewrites `docs/RELATIONAL_CORPUS.md` before asserting. The
  > regression gate is now computed **before** the write and the scoreboard is written only when
  > clean, so the failure mode described immediately below — a corrupted committed artifact left in
  > the working tree — can no longer occur. Proven both directions: a deliberate wrong-root run fails
  > the build with the scoreboard byte-identical and the tree clean; a correct run writes as before.

  **This is not theoretical — it was demonstrated accidentally during this audit.** The sweep
  **rewrites `docs/RELATIONAL_CORPUS.md` in place** as a side effect. Running it with
  `-Dlegend.engine.root=/Users/neemsandv/legend/legend-engine` (this account's checkout, at
  `a337991e9eb`, 2026-07-17) produced a scoreboard that disagreed with the committed one:

  | row | committed | this account's engine checkout |
  |---|---:|---:|
  | `executionPlan/tests` | 110 tests, 59 pass | **108 tests, 58 pass** |
  | `tests/mapping/modelJoin` | 47 tests, 1 fail | **48 tests, 2 fail** |

  So **the engine root silently changes the denominator**, and the committed scoreboard is only
  meaningful next to the engine revision that produced it. Two consequences worth fixing together:
  record the engine-checkout revision alongside the scoreboard, and be aware that **running the
  sweep dirties the working tree** — the diff above was reverted, not committed, and anyone timing
  the sweep needs to do the same.
- **`main` has 23 engine test failures** (20 failures, 3 errors) — `expected: <Acme> but was:
  <null>`, `expected: <3> but was: <4>`. **Identical counts at the pre-carrier commit `85ff6c8a`**,
  so they are pre-existing, not carrier-arc damage. But `AGENTS.md` makes engine-green a
  precondition for every commit, so the gate is currently red.

---

## 7. Fix order — this order matters

**There are two independent goals here, and they want different work.** Do either half first, but
keep the order *within* a half.

**Half A — "stop killing my machine" (steps 1-3).** Nothing here makes the suite faster; it makes
concurrent runs survivable. This is the half to do if the complaint is that engine + PCT + corpus
can't run together.

**Half B — "make the loop fast" (steps 4-6).** Steps 4 and 5 are pure wall-clock; step 6 is the
largest single lever in `core/` and also the most invasive.

Applying these out of order makes things **worse**, not better: anything that adds forks or
concurrency before memory is bounded multiplies the kill.

1. **Bound DuckDB first.** A single `openTestSession()` factory that issues
   `SET memory_limit='512MB'; SET threads=1` on every test connection. Nothing else is safe until
   this lands.
2. **Add surefire configuration** (there is none): `forkCount=3`, `reuseForks=true`, `-Xmx2g`.
   3 forks × 2 GB heap + bounded DuckDB fits comfortably in 32 GB with the 4 performance cores.
3. **Bound `moduleCache`** (`Runner.java:96`) and stop keying it per test; cap the retry loop.
   This is the kill fix proper.
4. **Seed once per `(family, moduleKey)`** in the corpus runner (§3) — the +41% regression.
5. **Hoist engine's 24 per-test fixtures to `@BeforeAll`** (§4) — 20–44 s.
6. **Wire `ContentStore` into `compileModel`** (§5.2) — the largest `core/` lever.
7. Only then consider **JUnit parallelism**. Enabling it before 1–3 would make the kills strictly
   worse.

**A safe parallel `engine + PCT + corpus` run** needs 1–3 done, and then a per-suite budget: 3 JVMs
× 2 GB heap × bounded DuckDB ≈ 8 GB total, against 32 GB physical — with the second checkout on the
machine still to account for.

---

## 8. What NOT to do

- **Don't enable JUnit parallelism to "make tests faster"** before DuckDB memory is bounded (§7).
- **Don't chase `CarrierStrategies`** — measured and exonerated (§5).
- **Don't trust `engine/target/surefire-reports` aggregate times.** A stale 123 MB
  `RelationalCorpusRunner.xml` from a previous run inflated engine's apparent total to 242 s in an
  earlier draft of this audit; the live figure is 19 s. Clean the directory before timing.
- **Don't optimise the bare connection cost.** 4.85 ms × 2,633 ≈ 13 s — real, but rounding error
  against the corpus sweep's 298 s. Per-query model recompilation (~2 s) is the same.
- **Don't assume O(tests²) I/O in the corpus runner** — the file indexes are already memoized and
  the per-family reads are per-family. That hypothesis was tested and is dead.
