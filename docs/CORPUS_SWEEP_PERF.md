# Corpus sweep performance — deep dive, for review

> **Purpose.** This document exists to be **double-checked**. It records a performance investigation
> into `RelationalCorpusRunner` (the 2,019-function corpus sweep), including **every hypothesis that
> was falsified** and the exact commands to reproduce each number. A reviewer should be able to
> re-run everything here and either confirm or break it.
>
> **Status:** one fix landed and verified (`cafea293`, −21%). Two fixes proposed, **not implemented**,
> sized at ~88 s and ~24 s. Those two are what most need review before anyone writes code.
>
> **Companions:** `PERFORMANCE_AUDIT.md` (the broader test-loop audit; this is its §3 expanded),
> `AGENTS.md` (the gate protocol), `RELATIONAL_CORPUS.md` (the scoreboard, which is the oracle).

**Evidence standard.** Every number is tagged `[measured]`, `[derived]`, or `[estimated]`.
`[derived]` means computed from two measured quantities — those are the ones most worth attacking.
Measurements were taken on a 32 GB / 10-core (4P+6E) machine with `-Xmx6g`, `legend.engine.root`
pinned to `/Users/neemsandv/legend/legend-engine` at `a337991e9eb` (2026-07-17).

---

## 0. TL;DR for the reviewer

| Claim | Number | Confidence | Attack it by |
|---|---:|---|---|
| Sweep dominated by module compilation | 61.7% of samples | **high** `[measured]` | re-profile; see §2 |
| Cost per cache miss | ~132 ms | **medium** `[derived]` | measure a real miss directly; see §6.1 |
| 66% of misses are redundant | 663 of 1,010 | **high** `[measured]` | re-run §3 instrumentation |
| Re-keying saves ~88 s | 88 s | **medium** `[derived]` | depends on the 132 ms; see §6.1 |
| Throwaway parse costs ~24 s | 11.1% of samples | **high** `[measured]` | see §4 |
| **Source-set identity by NAME is safe** | — | **LOW — see §6.2** | **this is the weakest link** |

**If you only check one thing, check §6.2.**

---

## 1. Baseline numbers

`[measured]` — wall clock, `/usr/bin/time -p`, surefire report times.

| Suite | Tests | Time | Per test |
|---|---:|---:|---:|
| `core` | 1,595 | 8.8 s | 5.5 ms |
| `engine` unit | 2,721 | 18.6 s | 6.8 ms |
| `pct` | 974–1,109 † | ~40 s | ~36–41 ms |
| **corpus sweep** | 2,019 fns (1 `@Test`) | **216 s** | **107 ms** |

> † **Honest caveat on the `pct` row.** The 40.2 s figure came from a run reporting **974** tests
> (with 1 error, in `Test_LegendLite_GrammarFunctions_PCT`); a later run reported **1,109** tests,
> 0 failures. I did not reconcile why the count moved, and the per-test figure is therefore a range,
> not a measurement. `pct` is not this document's subject — but do not quote that row as fact.

**The question this document answers:** why is a corpus test ~16× more expensive than an engine unit
test?

**Answer (§2):** engine/core tests compile a small hand-written fixture **once per class** — core has
25 `@BeforeAll` blocks covering 1,576 tests. A corpus test may recompile an entire Pure module, and
~40% of them do.

> **Historical note.** A stale 123 MB `TEST-…RelationalCorpusRunner.xml` from Jul 31 (1 test,
> 211.37 s) inflated engine's *apparent* aggregate to 242 s in an early draft. **Clean
> `engine/target/surefire-reports/` before timing anything.** The Jul 31 figure is, however, a
> legitimate pre-carrier-arc baseline: the sweep was 211 s then and 273 s before the fix in §5.

---

## 2. Where the time goes — JFR attribution

`[measured]`. Reproduce:

```bash
mvn -o test -pl engine -Dtest=RelationalCorpusRunner -DfailIfNoSpecifiedTests=false \
  -Dlegend.engine.root=<your-engine-checkout> -Dmaven.test.failure.ignore=true \
  -DargLine="-Xmx6g -XX:StartFlightRecording=duration=320s,filename=/tmp/sweep.jfr,settings=profile"
jfr print --events jdk.ExecutionSample --stack-depth 60 /tmp/sweep.jfr > /tmp/stacks.txt
```

Then count stacks containing each frame (3,735 total samples):

| Frame (inclusive) | Samples | Share |
|---|---:|---:|
| `Runner.moduleContextFor` | 2,305 | **61.7%** |
| `Compiler.buildModule` | 934 | 25.0% |
| `Compiler.parseSources` | 760 | 20.3% |
| `Runner.replaySeeds` | 736 | 19.7% |
| `StatementExecutor.*` | 709 | 19.0% |
| `TestBody.run` | 410 | 11.0% |
| `Runner.openSession` | 2 | **0.1%** |

**These are inclusive (frame appears anywhere in the stack), so they nest and overlap — do not sum
them.** `moduleContextFor` contains both `parseSources` and `buildModule`.

True **self-time** (top frame only) is flat — no single hotspot:

| Self frame | Samples |
|---|---:|
| `HashMap` ops (getNode/putVal/nextNode/probe/resize) | ~777 (21%) |
| string building (ensureCapacity/newString/substring) | ~305 |
| `StringLatin1.lastIndexOf` | 146 |
| `Lexer.*` (scanNormalToken/Identifier/StringLiteral) | ~360 (10%) |
| regex matching | ~224 |

**Interpretation.** Flat self-time + one dominant *inclusive* frame = "one expensive operation done
too many times", not "one bad algorithm". That is what points at caching rather than
micro-optimisation.

**Note `openSession` at 0.1%.** DuckDB connection creation — measured separately at 4.85 ms, 14×
more than reusing one — is **irrelevant to this suite**. A general finding that is true and does not
matter here.

---

## 3. The cache-key finding

`[measured]` via temporary instrumentation in `Runner.moduleContextFor` (added, measured, reverted —
not in the tree).

The cache key is:

```java
cacheKey = currentFamilyKey + "|" + currentFileKey + "|"
         + String.join(",", mappingRefs) + "|" + String.join(",", fileOnlyRefs);
```

Measured over a full sweep:

```
calls=2562  hits=1552  misses=1010  hitRate=60.6%
distinctKeys=1010   distinctFamilyFile=210   distinctSourceSets=347
```

**`distinctSourceSets` was computed by sorting the `parseable` list's `ModelSource.name()` values and
joining them** — see §6.2, this is the weakest step in the whole document.

### What it means

`moduleContextFor` assembles `sources` as: `sharedRaw` + parent family + family + file +
cross-family pulls driven by `mappingRefs` (each ref → `elementSource` → defining file → its whole
family, deduped **by text** via the `present` set).

So the compiled module is determined by **the resulting source set**, while the cache is keyed on
**the raw refs**. Different ref-lists frequently resolve to the same files.

- 1,010 misses, 347 distinct source sets ⇒ **663 misses (66%) recompile an already-compiled module**.
- 347 > 210 ⇒ the refs **do** genuinely change the module sometimes (137 times beyond plain
  `family|file`). **The key is not wrong, it is over-granular.** Anyone "fixing" this by deleting
  `mappingRefs` from the key will produce wrong modules for those 137.

### The proposed fix — two-level lookup

Keep the cheap key to find the refs; resolve refs → source set (map lookups, microseconds); key the
**compiled module** on the source set. The code already does the cheap half and then unconditionally
redoes the expensive half.

`[derived]` **663 × ~132 ms ≈ 88 s** of the 216 s sweep.

---

## 4. The double parse

`[measured]`. `Runner.java` ~1571-1581:

```java
List<ModelSource> parseable = new ArrayList<>(sources.size());
for (ModelSource src : sources) {
    try {
        ElementParser.parse(src.text());   // parse to VALIDATE
        parseable.add(src);                // result discarded
    } catch (RuntimeException e) {
        wallOnce("file " + src.name() + " => " + ...);   // per-file error attribution
    }
}
ParsedModule pre = Compiler.parseSources(parseable);     // parses them ALL AGAIN
```

Counting stacks with `ElementParser.parse` **not** under `Compiler.parseSources`:

| | Samples | Share |
|---|---:|---:|
| `ElementParser.parse` via `parseSources` | 726 | 19.4% |
| `ElementParser.parse` **not** via `parseSources` | **414** | **11.1%** |
| `pullUnresolvedMappingStores` | 1 | 0.0% |

`[derived]` 11.1% × 216 s ≈ **24 s**.

**Do not "fix" this by deleting the validation loop.** It exists to attribute a parse failure to an
individual file via `wallOnce`; `parseSources` on the whole list would lose that. The fix is to
**reuse** the parse result — which needs a `Compiler.parseSources` overload accepting pre-parsed
units (it currently calls `ElementParser.parse` internally per source and merges).

---

## 5. The fix that landed — `cafea293`

`RawSqlBoundary` had hoisted two `Pattern`s to `static final` and then used `String.replaceAll` /
`String.matches` inline in nine more places. Those recompile the regex on **every** call, on a
boundary that runs per seeded statement per test.

`[measured]` A/B, matched load (1-min load average 3.44 vs 3.46), same command:

| | Time |
|---|---:|
| without fix | 273.2 s |
| with fix | 216.5 s |
| **gain** | **56.7 s (−21%)** |

Semantics unchanged by construction: `String.replaceAll(re, r)` *is*
`Pattern.compile(re).matcher(s).replaceAll(r)`.

Verified: core 1595/0 failures; engine 2721 with 20 failures / 3 errors (**identical** to the
pre-change baseline — pre-existing, also present at `85ff6c8a`); pct 1109/1109 green.

> **How it was found matters.** A prior static audit explicitly cleared `Pattern.compile` as having
> "zero hot-path hits". It had checked the compiler packages and missed `exec/`. The profiler found
> it in minutes. **Prefer the profiler over static reasoning for anything in this document.**

---

## 6. Where this analysis is weakest — attack these first

### 6.1 The 132 ms per miss is `[derived]`, not measured

61.7% × 216 s = 133 s, ÷ 1,010 misses = ~132 ms. Both the ~88 s and the ranking depend on it.

**Assumptions baked in:** that sample share ≈ wall-clock share (fair for CPU-bound work, but the
sweep also does JDBC I/O, where the JVM may be parked and undersampled — this could **understate**
non-CPU phases and thereby **overstate** `moduleContextFor`'s share); and that hit-path cost ≈ 0
(a `LinkedHashMap` lookup — safe).

**How to check properly:** wrap the miss branch of `moduleContextFor` in `System.nanoTime()`,
accumulate, and print total miss time + count. That converts the key number from `[derived]` to
`[measured]` in one sweep. **This is the single highest-value verification in this document.**

### 6.2 Source-set identity was computed by FILENAME — the weakest link

`distinctSourceSets = 347` was computed from sorted `ModelSource.name()` values.

**But the runner's own dedup is by TEXT** (`present.add(src.text())`), and shared sources are named
positionally (`"shared-" + i + ".pure"`). So:

- If two different texts ever share a name, my 347 **undercounts** distinct modules, and re-keying
  on names would **merge modules that differ** — silently wrong results, exactly the failure class
  this project cares most about.
- Conversely, if identical text appears under different names, 347 **overcounts** and the real win
  is larger.

**Required before implementing §3:** recompute `distinctSourceSets` using a hash of the **sorted
source texts** (or `name + " " + text`), and confirm it still lands near 347. Any real
implementation must key on **text identity**, not names.

### 6.3 Is the compile a pure function of `sources`?

The two-level cache assumes `buildModule(parseSources(sources))` depends on **nothing else**. If any
ambient state participates — `currentFamilyKey`, import scopes, `elementSource`, registry mutation
during compile — two identical source sets could legitimately need different modules.

**How to check:** compile the same source set twice under different `currentFamilyKey`/`mappingRefs`
and compare the resulting `ModelContext` for equivalence. Or grep `buildModule`'s transitive callees
for reads of mutable static/instance state.

### 6.4 Sampling and run-to-run variance

3,735 samples over ~216 s. Sub-1% attributions are noise. Sweep timings observed across this
investigation: **273.2, 216.5, 215.9, 205.8, 203.6 s** — so **±5% run-to-run variance even at
similar load**. Any claimed improvement smaller than ~15 s needs repeated runs, not one A/B.

Machine contention is real and large: one PCT run measured **2:06 idle vs 2:49** while a second
checkout (`/Users/neema/legend/legend-lite`, a different user) ran its own gates. Always record the
load average.

### 6.5 The oracle is the scoreboard, not the clock

Both proposed fixes change **what gets compiled and when**. Wall-clock proves nothing about
correctness. The gate is `docs/RELATIONAL_CORPUS.md`'s pass/fail counts being **unchanged**.

> **Trap:** the sweep **rewrites `docs/RELATIONAL_CORPUS.md` in place**. Every timing run in this
> investigation dirtied the working tree and was reverted with `git checkout --`. Worse, the counts
> depend on **which legend-engine checkout** you point at — running against this account's
> `a337991e9eb` produced `executionPlan 110/59 → 108/58` and `modelJoin 47(1 fail) → 48(2 fail)`
> versus the committed scoreboard. **Compare scoreboards only against a baseline generated from the
> same engine revision.** See also `Corpus.java:31-32`, which defaults `ENGINE_ROOT` to another
> user's home directory.

---

## 7. Hypotheses that were FALSIFIED — do not re-run these

Recorded so the next session does not repeat them. Each was plausible and wrong.

| # | Hypothesis | How it died |
|---|---|---|
| 1 | The recent carrier arc (`CarrierStrategies` wired first in every dialect's `passes()`) caused the slowdown | **Bisect.** PCT at pre-carrier `85ff6c8a` = 43.9 s; at HEAD = 40.2 s. HEAD is *faster*. `CarrierStrategies` early-returns to an identity walk on `caps.nativeLists()`. |
| 2 | The sweep is database-bound — it reseeds the whole family DB per test | **JFR.** `openSession` 0.1%; **zero JDBC frames in the top 25 self-time**. Seeding (`replaySeeds` 19.7%) is real but is mostly *compilation* inside seed replay, not database work. |
| 3 | Re-parsing the 178 KB shared prefix on every miss is the lever | **Benchmark.** `parseSources` on the 4 shared files = 2.6 ms; × 1,010 = **2.6 s (1%)**. |
| 4 | Therefore module memoization has a ~10.5 s ceiling | **Wrong, and instructively so.** That benchmark compiled *only the shared prefix* (10.4 ms). Real misses compile shared **+ family + file + cross-family pulls** ≈ 132 ms — **13× bigger**. A confident conclusion drawn from an unrepresentative input. |
| 5 | `forkCount=4` will exploit the 10 cores and speed up PCT | **Measured.** PCT 40 s → **139 s**, only 208/974 tests ran, `OutOfMemoryError` in `BinaryModelSourceDeserializer`. Each fork re-loads the whole Pure platform model, so forking multiplies the dominant fixed cost. |
| 6 | (Prior static audit) `Pattern.compile` has zero hot-path hits | **JFR.** 149 samples; 93 under `h2ToDuckDb`, 75 under `quoteCreateColumns`. Became the one landed fix. |

**Pattern across 1-6:** every hypothesis formed by *reading code* was wrong; every one formed by
*profiling* held. Weight accordingly.

---

## 8. Proposed work, with risk

| | Gain | Risk | Notes |
|---|---:|---|---|
| **A. Reuse the validation parse** (§4) | ~24 s | **low-medium** | Needs a `Compiler.parseSources` overload taking pre-parsed units. Must preserve per-file `wallOnce` attribution. Purely mechanical once the overload exists. |
| **B. Re-key on resolved source set** (§3) | ~88 s | **medium-high** | Blocked on §6.2 (text vs name identity) and §6.3 (purity). Wrong implementation = silently merged modules = wrong rows. |

**Recommended order: A then B.** A is smaller and lower-risk, and it exercises the
verification loop (three suites + scoreboard comparison) before B touches the semantics of what gets
compiled.

**Not recommended:** internal parallelism of the sweep. It is the only remaining order-of-magnitude
lever (2,019 mostly-independent tests × 107 ms on 10 cores), and it needs no extra JVMs — so it does
not hit the failure mode that killed hypothesis 5. But it requires making `moduleCache` and result
accumulation concurrent, and doing that **before** A and B would parallelise work that should simply
not exist. Revisit after A and B land.

---

## 9. Reproduction checklist

1. `rm -rf engine/target/surefire-reports` — stale corpus XMLs corrupt aggregate timings (§1).
2. Record the load average before and after; a second checkout on this machine runs gates
   continuously (§6.4).
3. Pin `-Dlegend.engine.root` explicitly — the default is another user's home (§6.5).
4. `git checkout -- docs/RELATIONAL_CORPUS.md` after every run (§6.5).
5. For A/B timing, alternate runs under similar load; do not trust a single pair (§6.4).
6. Correctness gate = scoreboard counts unchanged vs a baseline from the **same engine revision**,
   plus core / engine / pct suites at their known baselines (engine's 20 failures + 3 errors are
   **pre-existing** — they reproduce at `85ff6c8a`).
