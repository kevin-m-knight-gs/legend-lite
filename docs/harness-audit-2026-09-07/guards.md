# The guards — what they pin, what slips past

**Verdict: the guards guard *structure* well and *semantics* poorly.** The exact-set ledgers
are genuinely excellent and all currently exact. Every one of the prior audit's seven blind
spots is **still open**. And the suite missed the ordered-compare regression because no guard
checks the class that broke.

*Method note: two reviewers had no JDK on their sandbox. Every "measured actual" below is that
guard's own regex/algorithm re-implemented in Python over the identical file set the guard
walks (same `Files.walk` root, same filters, same comment-stripping), run at `f073b394`.
Anything inferred is labelled.*

---

## 1. Per-guard: mechanism, and the slip-past

| Guard | Mechanism | Pins | Slips past |
|---|---|---|---|
| `CodeShapeGuardrailTest.noStaticMutableState` | per-line regex `static (?!final )…` | "static mutable state, no allowlist" | §2 — **everything** |
| `…mutableInstanceStateIsExplicit` | per-line regex, char class `[\w.<>\[\], ?]` | non-final instance fields | no `@` in the class → any `@Nullable` field; multi-line declarations |
| `…noMethodBeyondTheLimit` / `noFileBeyondTheLimit` | brace-walk / line count | 250-line methods, 3500-line files | allowlists near-empty — **well built** |
| `…deadPrivateMethodsOnlyShrink` | span-masked reference count, pin 0 | dead private **methods**, `src/main` only | dead **fields**; anything in `src/test` — §3 |
| `ArchitectureTest` (35 methods) | ArchUnit bytecode | dependency direction | no `resolver → lowering` rule — §4 |
| `…staticCollectionStateIsImmutableOrRegistered` | reflection, type filtered to `Map`/`Collection` | static collection state | ThreadLocals, `LongAdder`, `AtomicX`, arrays, the one non-final static |
| `ObservabilityGuardrailTest.stringDispatchOnlyShrinks` | regex `(function\(\)\|simpleName\(\|\.name\(\))\.equals\("` | 87 sites | §5 — ~51 more |
| `…noNewDebugEnvFlags` | regex `System\.getenv\("…"\)` | the flag vocabulary | `System.getProperty` — two already exist |
| `ErrorShapeGuardrailTest.defaultLiteralFallbacksOnlyShrink` | regex `default -> "` | **3** sites | §6 — 476 of 479 arms |
| `…broadCatchCountsPinnedPerFile` | regex keyed by **filename** | per-file counts | a bearer bond for a deleted filename — §7 |
| `LiteralUnrollLedgerTest` | regex `is\(c, "(\w+)"\)` | the compare-only fold set | §8 — the receiver must be named `c` |
| `TenetRatchetTest` | accessor regex gated on driver package names | 13 JDBC accessor sites | `org.postgresql`/`org.mariadb` not in the gate (0 such files today — latent) |
| `PlatformNamesGuardrailTest` | §9 | 73→0 literal `meta::` checks; 11 retired names | §9 |
| `builtin/NativeFunctionTest.catalogMatchesTheGoldenFile` | `assertEquals(golden, Pure.all())` | 816 golden lines | §10 — tautological by construction |
| `V7DualChannelCensusTest` | feeds its own inputs to the counter | the counter's arithmetic | §11 — pins nothing about the codebase |
| `MinimalCorpusTest` | `assertTrue(pass.size() >= floor)` | a **count** | §12 — the whole corpus contract |
| `RawSqlLedgerTest`, `SqlTextRatchetTest`, `JdbcSurfaceCensusTest`, `HarnessDisciplineTest`, `SkipCensusTest`, `CarrierPurityRatchetTest` | exact set/map equality + coverage floor | see §14 | scope limits only |

---

## 2. Static mutable state — the guard sees none of it

The three regexes, run against real declarations from the tree:

```java
private static final ThreadLocal<String> STATE = new ThreadLocal<>();   // INVISIBLE
private static final AtomicLong COUNTER = new AtomicLong();             // INVISIBLE
private static final Map<String, Rule> RULES = new HashMap<>();         // INVISIBLE
private static final int[] CACHE = new int[16];                         // INVISIBLE
private @Nullable ImportScope memo;                                     // INVISIBLE (no @ in char class)
private static Map<String, String> CACHE
        = new HashMap<>();                                              // INVISIBLE (multi-line)
private static int counter;                                             // caught
```

`static final` + a mutable object defeats the `(?!final )` lookahead entirely. **Measured**
over `core/src/main/java` (628 files): **12 `static final ThreadLocal`** *(correcting an
earlier figure of 13 — one was a comment line)*, **38 `static final LongAdder`/`AtomicLong`**,
**15 `static final` mutable collections**. Plus the live non-final one at
`CanonicalDivergence.java:434-435`, which slips both guards because its declaration spans two
lines and the regex is per-line.

This matters right now: the plan opens with a thread-local sweep and the guard suite gave
**zero signal** that twelve accumulated.

---

## 3. Read-with-no-writer — the regression no guard caught

A repo-wide sweep over `src/main` + `src/test`, counting `.get(` reads against `.set(`/
assignment writes for every static `ThreadLocal`/`volatile`:

| field | declared | reads | writes | status |
|---|---|---:|---:|---|
| `ORDERED_QUERY` | `H2Verify:179` | **6** (`:611,710,722,773,858,862`) | **0** | **READ, NEVER WRITTEN** |
| `SORT_KEYS` | `H2Verify:192` | **1** (`:897`) | **0** | **READ, NEVER WRITTEN** |
| `FORCED_MECHANISM` | `H2Verify:156` | 0 | 0 | **completely DEAD** |
| `LAST_DECLINE` | `H2Verify` | 0 | 1 | write-only |
| `CONTEXT_SOURCE` | `CanonicalDivergence:434` | 4 | 0 | **injection point with no injector** |

`ORDERED_QUERY` is `withInitial(() -> Boolean.FALSE)`, so all six reads take the unordered
branch permanently: **row order stopped being a contract**, silently, in the direction that
manufactures passes.

**The diagnostic contrast.** `H2Verify.CURRENT_TEST` had the *same* writer deleted and batch
124 (`06e968c5`) rewired it by hand at `MinimalCorpus:405,409` — because someone noticed
unattributed decline lines in the console. The other four were not noticed, because **nothing
prints when an ordered compare quietly becomes a multiset compare.** That is the argument for
a mechanical rule rather than vigilance.

**Which guard should have caught it — none could, as built:**

- `deadPrivateMethodsOnlyShrink` is the right *shape* (reference counting with span masking)
  but is scoped to `private` **methods** in `src/main`. These are `public` **fields** in
  `src/test`. Two independent scope misses.
- `staticCollectionStateIsImmutableOrRegistered` never sees `ThreadLocal` (§2) and imports
  production classes only (`DO_NOT_INCLUDE_TESTS`, `ArchitectureTest:40`).
- `JavaEvalLedgerTest`'s stale-row rule is the closest precedent — it fails when a *pinned
  file* disappears. The same idea one level down (a pinned *symbol* losing its counterpart) is
  exactly what was needed.

**The rule to write** — `DanglingStateCensusTest`, ~40 lines:

> For every static field whose type is `ThreadLocal`, `Atomic*`, `LongAdder`, or is declared
> `volatile`, count reads (`.get(`, `.getAndX(`, bare reference) and writes (`.set(`, `.add(`,
> `.increment(`, `X =`) across **both** source roots. Assert **reads > 0 ⟺ writes > 0**, and
> total occurrences > 1. Pin violations at **zero** — a correctness property, not a burn-down.

It would have gone red in `ff359bae` naming all four fields. The property has now been found
**by hand twice** — `CodeShapeGuardrailTest`'s allowlist comment records deleting
`Lowerer.relationDepth` because "the audit found the counter write-only" — so it should be a
test.

**Second, narrower rule:** a guard's justification comment must not name a deleted symbol.
`HarnessDisciplineTest:96` cites `EngineTestExecutor.sortKeyCols`; `HarnessDisciplineTest:~60`
and `ErrorShapeGuardrailTest:116` also still name `EngineTestExecutor`. A regex over guard-file
comments for `\b[A-Z]\w+\.(java|\w+\()` resolved against the tree is cheap.

---

## 4. `resolver → lowering` — still open, and violated

`loweringDependencySurfaceIsPinned` (`ArchitectureTest:475`) forbids `lowering → resolver`.
There is **no converse rule**, and `packageDependenciesAreAcyclic` cannot see a one-way
backward edge. Production violates pipeline order today:

- `resolver/GraphEmission.java:2329` → `com.legend.lowering.Aggregates.isReducer(...)`
- `resolver/CorrelatedSubselects.java:1799,1805` → `…Aggregates.isDemandReducer(...)`

Phase H reaching into phase I's catalog. One ArchUnit method fixes the guard.

---

## 5. String-name dispatch

Pin 87, **measured 87** — the ratchet itself is tight. Invisible to it, same file set:

| dodge | measured | witness |
|---|---:|---|
| flipped operand `"lit".equals(x.function())` | 41 | `protocol/ProtocolEmitter:2416` |
| `.equalsIgnoreCase("…")` | 7 | `normalizer/JoinChainEmission:1066` |
| `switch` over a name | 3 | `StaticFold:352`, `FoldChecker:163`, `Typer:829` |
| `startsWith`/`endsWith`/`contains` on a name | 16 | — |

≈51 further sites. Slip-past: `if ("myNewFn".equals(call.function())) { … }` and the counter
stays 87.

---

## 6. `default ->`

**Measured** over `src/main`: **479** arms.

| shape | measured | pinned? |
|---|---:|---|
| `default -> throw …` | 205 | n/a (correct) |
| `default -> "<literal>"` | **3** | **yes, pin 5** |
| `default -> null` | 61 | explicitly out of scope |
| `default -> false/true` | 38 | explicitly out of scope |
| `default -> { … }` block | 61 | invisible — a `yield` inside fabricates freely |
| other value expressions | 107 | **unpinned, unmeasured by anything** |

The guard covers **0.6%** of the construct it names. Several of the 107 are the exact defect
its own javadoc describes: `lexer/Lexer:769` `default -> TokenType.INVALID;`,
`ide/ModelIndexer:259` `default -> TokenType.BRACE_OPEN;`, `AssertVerdicts:762` `default -> name;`.

---

## 7. The filename bearer bond

`BROAD_CATCH_COUNTS` is keyed by *basename*. `Map.entry("EngineTestExecutor.java", 5)`
(`ErrorShapeGuardrailTest:116`) survives for a file deleted in batch 115. **Anyone creating a
file with that name inherits five free broad catches.** `QuotedSpecParser.java` (`:117`) is
pinned 1, measured 0. `CodeShapeGuardrailTest`'s mutable-field allowlist carries the same
tombstones (`EngineTestExecutor.i`, `PureDateLiteral.pos`).

Contrast `JavaEvalLedgerTest:1156`, which goes **red** when a pinned file vanishes — the
correct design, present in the same suite.

---

## 8. `LiteralUnrollLedgerTest`'s receiver anchor

The guard scrapes `is\(c, "(\w+)"\)`. **Measured**: the file already contains
`is(nm, "newMap")` (`LiteralUnroll:513,517`) and `is(pc, "pair")` (`:274`), both invisible;
`newMap` is not in `COMPARE_ONLY` and the test still passes.

```java
// slips past entirely — a WORLD_MAP §4 / Charter C6.2 violation:
if (is(call, "toLower") && a.size() == 1 && a.get(0) instanceof TypedCString s) {
    return new TypedCString(s.value().toLowerCase());   // the unroll now COMPUTES
}
```

Defeated by renaming a local. Fix: `is\(\w+, "…"\)`.

---

## 9. The new guards (batch 114)

**`pureNamesAreSpelledInTheCatalogOnly`** — pin 73, **measured 72** (slack 1). Real and tight.
The regex is `equals("meta::` only. **Measured** outside `PlatformTypes.java`: **29**
flipped-operand `"meta::…".equals(`, **10** `case "meta::`, **5** `startsWith("meta::` — **44
further** literal Pure-name checks it cannot see, against **520** total `"meta::` literals. It
is also **the only source-walking guard in the suite with no `GuardCoverage.assertFloor`** —
the exact defect `GuardCoverage` was written for, in a guard added a month after it.

**`runtimeShapesAreReadByTheOneReaderOnly`** — the javadoc claims *"no file outside
`ExecutionContext` walks a runtime expression for its shape."* The test greps for **eleven
literal method-name strings** and checks `ConnectionFlags.java` is absent. Rename
`chainMappingsIn` → `chainMappingsFor` and reintroduce the walker: green.

And the claim is **already false**. `StatementExecutor.java:1180-1194`:

```java
String type = ExecutionContext.Reader.databaseType(ni);            // correct
String csv  = ni.properties().get("testDataSetupCsv") ...          // raw
if (ni.properties().get("datasourceSpecification") ...)            // raw
String specCsv = ds.properties().get("testDataSetupCsv") ...       // raw
```

A name blocklist cannot see a raw field-name read. **This is a correctly-built tombstone list
mis-described as an invariant.**

**`LibraryPlatformNamespaceGuardTest`** — two real assertions on
`MinimalCorpus.refusePlatformNamespace`, positive and negative. Sound as a unit test of the
predicate; it does **not** guard that every load path calls it (two call sites exist; a third
loader would be unguarded). Separately **AGENTS.md:49-51 is stale** — it names
`Runner.registerLibrarySource`, and no such symbol exists.

---

## 10. The native catalog golden

`NativeFunctionTest:52` reads `native-catalog.txt` (816 lines) and compares it to `Pure.all()`
rendered by `renderCanonical` — **the same method the update instruction says to regenerate the
file with**. It detects *unreviewed change*, which is real value, but cannot detect a wrong
signature.

**Measured**: of 482 distinct golden FQNs, **209 (43%)** appear upstream as a `native
function`; 29 are declared lite inventions (censused by `NativeCatalogGovernanceTest`); **244
do not appear as upstream natives** — many legitimately, since upstream declares them as
ordinary Pure functions, which the World Map licenses reimplementing in Java. **No test
distinguishes the cases.** *(The prior audit's "~28% exactly match" could not be reproduced and
is not asserted here.)*

A divergence the golden cannot see: golden
`meta::pure::functions::math::atan2(y:Number[1], x:Number[1])` vs upstream
`atan2(number1:Number[1], number2:Number[1])` (`legend-pure/…/math/trigonometry/atan2.pure:17`).

`PreludeGeneratorTest` does this **properly** — regenerates from the real checkouts and asserts
the committed file matches. The mechanism exists; it was applied to classes/enums and never to
native functions.

---

## 11. The tautology class

`V7DualChannelCensusTest.perFormAccountingAndSummary` calls `CanonicalDivergence.v7Verdict(...)`
six times with hand-written arguments, then asserts `v7DisagreeCount() == 2` — the count of the
two calls it just made. It is a correct **unit test of the accumulator's arithmetic**, honestly
labelled ("Measurement-instrument pins only; no verdict flows through here"). It is **not** a
census pin and says nothing about the codebase.

This corrects a mid-audit claim: when checking whether dual-channel disagreement is still
asserted anywhere at HEAD, this test was the only core hit. **It is not evidence that
disagreement is still guarded.** The real pins live only in `pct` (gate 9):
`ChannelB{Standard,Relation,Grammar,Unclassified,Essential}Test` each assert
`SqlTypeCensus.wireDivergeCount() <= 75`.

Others sharing the shape: the native golden (§10, artifact vs itself); `LiteralUnrollLedgerTest`
partially (hand-list vs a regex scrape of the same file — inherent to a ledger, honestly
framed); six of seven `EVICT_NAMES` rows pinned at 0 (tombstones asserting a dead name stays
dead — not tautological, not load-bearing). **No test was found whose assertion can only pass.**

---

## 12. The corpus gate as a guard

```java
// MinimalCorpusTest.java:112-115 — its own comment calls this "the ONE pin"
int floor = MinimalCorpus.H2_BACKEND ? H2_FLOOR : 2454;
assertTrue(pass.size() >= floor, "corpus pass roster shrank: " + pass.size() + " < " + floor);
```

Three defects compound:

1. **A count, not a roster.** A regression is masked by any test that starts passing. The old
   runner's commits claim "rosters exact on both lanes"; that property is gone.
2. **Monotone upward**, so anything that *manufactures* passes is invisible — and §3 is exactly
   that. The floor cannot distinguish "we fixed the compiler" from "we weakened the referee".
   Its own derivation at `:109` counts "the assert-free twin and **the two vacuous
   placeholders** it walked".
3. **Skipped entirely** under `-Drcorpus.test` (`if (only.isEmpty())`) and under
   `Assumptions.assumeTrue(Corpus.available())` at `:35`.

Everything else — the verdict roster, decline buckets, unverifiable census, outcome roster
(`:89-101`) — is `System.out.println` with the comment "DISPLAYED, no verdict flows through it."

**What it should assert**, in priority order: (a) `assertEquals` on the committed pass
**roster**, or a shrink-only set difference naming any departure — trivial, just commit the
2454/1866 name sets; (b) a **ceiling** as well as a floor, so a pass-count jump must be
explained rather than banked; (c) `assertEquals(0, …)` or an explicit shrink-only ceiling per
referee decline/unverifiable bucket; (d) an assertion that the ordered-compare path was
actually exercised (a counter in the `ORDERED_QUERY.get()` branch, asserted non-zero) — the
cheap, specific tripwire for §3.

---

## 13. Ratchet slack

Slack = how far the codebase can regress before the guard fires.

| pin | file:line | pinned | measured | slack |
|---|---|---:|---:|---:|
| `EVICT_SIZE["StatementExecutor.java"]` | `JavaEvalLedgerTest:561` | 2699 | 2047 | **652** |
| `EVICT_SIZE["SqlTextVerdicts.java"]` | `:701` | 1071 | 1044 | 27 |
| `EVICT_SIZE["PctExecuteNative.java"]` | — | 131 | 107 | 24 |
| `EVICT_SIZE["AggAwareActivities.java"]` | — | 227 | 211 | 16 |
| `EVICT_SIZE["DynamicPivot.java"]` | — | 118 | 106 | 12 |
| `EVICT_SIZE["StoreNav.java"]` | — | 199 | 188 | 11 |
| `EVICT_SIZE["JsonCompare.java"]` | — | 70 | 64 | 6 |
| `EVICT_SIZE["AssertVerdicts.java"]` | `:449` | 1652 | 1649 | 3 |
| **`EVICT_SIZE` total** | | | | **751 lines** |
| `BROAD_CATCH_COUNTS["EngineTestExecutor.java"]` | `ErrorShape:116` | 5 | file deleted | 5 |
| `ENDS_WITH_FQN` | `:144` | 18 | 12 | 6 |
| `DEFAULT_LITERAL_FALLBACKS` | `:150` | 5 | 3 | 2 |
| `CATCH_RETURNS_VALUE` | `:140` | 15 | 15 | 0 |
| `STDERR_PRINTS` | `Observability:59` | 34 | 29 | 5 |
| `STRING_DISPATCH_SITES` | `:63` | 87 | 87 | 0 |
| literal `meta::` checks | `PlatformNames:71` | 73 | 72 | 1 |
| `RESULT_SET_ACCESSOR_SITES` | `TenetRatchet:60` | 13 | 13 | 0 |
| `ArrayLit`/`SqlFn.LIST_`/`UNNEST`/`Reducer("LIST"` | `CarrierPurity` | 40/142/13/0 | 40/142/13/0 | **0** |
| raw `new SqlExpr.Column(` | `CodeShape:473` | 7 | 7 | 0 |
| `FILE_ALLOWLIST["MappingNormalizer.java"]` | `:39` | 3510 | 3509 | 1 |
| `METHOD_ALLOWLIST` | `:37` | *empty* | — | **0 — exemplary** |
| coverage floor, `core/src/main` | `GuardCoverage`, 4 guards | 498 | 628 | **130 files** |
| `FILE_FLOOR`, JdbcSurfaceCensus | `:~74` | 778 | 967 | **189 files** |
| coverage floor, SqlTextRatchet | `:142` | 250 | 604 | **354 files** |
| coverage floor, CarrierPurity | `:231` | 74 | 130 | 56 files |
| coverage floor, SkipCensus | `:156` | 270 | 311 | 41 files |
| coverage floor, HarnessDiscipline | `:214` | 22 | 37 | 15 files |

**Two systemic notes.** `EVICT_NAMES` fails on `n != pinned` — shrink *and* growth.
`EVICT_SIZE` fails only on `lines > pin`, so **every deletion silently banks slack**; that is
how `StatementExecutor` reached 652. And the `GuardCoverage` floors have rotted into
decoration: `GuardCoverage:22` states the doctrine ("growth is free"), so a floor tracks a
historical low. A walk-filter bug dropping 20% of `src/main` still clears 498/628;
`SqlTextRatchetTest` could lose **58%** of its tree and pass.

---

## 14. Pin movements in the harness rewrite (`4606852b..f073b394`)

Most movement was *forced tightening* — the exact-set design paying off. The raises:

| change | commit | judgment |
|---|---|---|
| `EVICT_SIZE["AssertVerdicts"]` 1646 → **1652** | `ff359bae` B115 | justified in prose; **batch named, no calendar date**. Over-shot: 3 of 6 lines needed |
| `EVICT_SIZE["SqlTextVerdicts"]` 1061 → 1063 → **1071** | `bb088a72` B114, `8576fd2b` B120 | argued line-by-line, credible, mechanism visible in the diff. **No date.** B123 then deleted 27 lines and the pin was **not followed down**, against its own "shrink-only from here" |
| `ROOT_CLASSES` += `ProgramFacts`, `ExecuteOptions` | B118, B122 | legitimate new funnel surface. **No date** |
| `HarnessDisciplineTest` += `MinimalCorpusTest = 1` | `ff359bae` | claim is true (`:102-105` sorts a display-only map). **No date, no task/incident — non-compliant** |
| `ParserBoundaryArchTest.DIALECT_CLASSES` += 2 | `4606852b`, `ff359bae` | added to a list its own javadoc calls **"Shrink-only"**. **No date.** Worse: unlike its siblings this register is *not* exact-match, so nothing forced the stale `EngineTestExecutor`/`Runner` rows out and nothing flagged 15→16 |
| `native-catalog.txt` += 5 signatures | B117, B118 | justification in the commit subject only; **no in-tree note** |
| `core/pom.xml` `excludedGroups` → `${surefire.excludedGroups}` | B113 | **No date.** The only change making test *selection* externally overridable; nothing guards its value (the value itself is clean — §15) |
| `allgates.sh` gate 4/5 → `MinimalCorpusTest` | `4606852b`, `ff359bae` | **No date in the script** |
| DuckDB floor 2452 → **2454**; new `H2_FLOOR = 1866` | `bb088a72`, `ff359bae` | both dated 2026-09-06 with derivations. **Compliant.** The 1976 → 1866 H2 drop is dated and coherent but **not verifiable from the diff** — the flip file it cites is an uncommitted build artifact |
| `PlatformNamesGuardrailTest` pin 73 + `RETIRED_WALKERS` | `bb088a72` | dated. **Compliant** |

**Raises that shipped, then were erased by deleting their file** (all in
`RelationalCorpusRunner.java`): `toleratedTransportedCount` 46 → **60**;
`wireIntOrNullEmptyCount` 87 → **153** (+76%); `CanonicalDivergence.disagreeCount` exact
21 → **42**. All dated. The last is troubling: batch 114 admits the doubling was a
**measurement artifact** — "gate 4 runs both harnesses in one JVM and this census is a static
sink counting both." A pin moved to accommodate double-counting, never re-tightened, then
deleted with its file.

**Batch 115 retired ~20 ratchets and replaced them with one count.** `RelationalCorpusRunner`
(3,873 lines, **39 assertion sites**) and `Runner` deleted. Verified gone and not
re-established: nine `assertEquals(0, …)` alarms ("dual-channel disagreement appeared (pinned
ZERO)", "corpus wire divergence reappeared", "a label lie escaped reconciliation", "untyped
projection roots reappeared", "wire NULL under a never-null label", "metamodel quarantine moved
off 0", …), the lane guards (`assert-sql-text-only 11`, `-unable-to-exec 8`,
`assert-test-data-csv 69`), `maxAdvisorySqlDiffs = 76`, `softDiff ≤ 264`, `softZero ≤ 30`, and
seven `SqlTypeCensus` ceilings. `GATES.md` records the deletion at the **batch** level;
AGENTS.md requires it at the **pin** level, and a bulk retirement of pinned-zero alarms is
precisely the case that rule exists for.

**Judgment: this is the one commit in the range that should not have passed review as written.**
It removed 39 assertions, left five fields dangling (§3), left a guard comment certifying a
deleted symbol, and substituted a monotone-upward count that the dangling fields inflate.

---

## 15. Do the guards run?

**Yes — and better than `GATES.md` claims.** Gate 1 (`allgates.sh:97`, `mvn -o -pl core clean
test`) carries **no `-Dtest` filter**, so every core guard runs; CI (`gate.yml:26`) runs gate 1,
so **every core guard runs in CI**.

**The `surefire.excludedGroups` override is clean.** `core/pom.xml:20` defaults it to `heavy`.
The complete `@Tag` census across all test sources is three sites: `MinimalCorpusTest:23`
(`heavy`, re-run explicitly by gates 4/5), `StressTestChaotic:36`, `ProfileBuildCost:13`.
**No guard carries any tag**; none is `@Disabled`; none contains an `Assumptions` skip. Two
anti-evasion checks also clean: no `import static …Assumptions.*` anywhere (a bare `assumeTrue`
would evade `SkipCensusTest:123`'s literal scan), and no bare `@Disabled` without a reason.

**Five holes:**

1. **Gate 1 has no rename-goes-red roster.** Gate 8 verifies each of its 20 named classes
   appeared (`allgates.sh:257-272`); gate 1, carrying ~23 core guards, verifies nothing. Delete
   `TenetRatchetTest.java` and the chain stays green. `SkipCensusTest`'s floor of 270 over 311
   cannot see a one-file deletion.
2. **Gate 1 does not run `skipped()`.** The detector (`:73-89`) is applied only to gates 4, 5
   and 8, contradicting its own header. Live consequence:
   `integration/CorpusDifferentialTest.java:43` `assumeTrue(Files.isDirectory(expected))` —
   whose javadoc says it *"verifies nothing until the generator is wired into allgates.sh"* —
   sits inside gate 1 reporting green forever.
3. **`ParserBoundaryArchTest.roots()` (`:132-144`) silently drops missing sibling roots and has
   no coverage floor.** One of its four, `../server/src`, **does not exist** (the server moved
   into core on 2026-08-11) and is silently skipped.
4. **Seven asserting `parser-equivalence` classes are in no gate**, notably
   `GrammarCoverageCensusTest` (18 assert/fail sites), moved to `tools/diagnostics.sh` on a
   human-memory trigger.
5. `SurfaceCensusTest:123` `assumeTrue(engineRoot != null)` skips 1 of 2 tests, so `skipped()`
   (which compares run-count to skip-count) is blind to it.

Two `catch (Throwable) { continue/return; }` sites erode coverage per-class with no count:
`ArchitectureTest:932`, `NoEagerTypeReferencesTest:93`.

**`GATES.md:48-66` is stale on all three of its own "read this before trusting a green"
warnings**, all pessimistic: CI does not run a skipping corpus step (`RelationalCorpusRunner`
no longer exists), `allgates.sh` does `exit 1` on failure (`:286,303`), and no `readBaseline` /
"gate SKIPPED" symbol exists anywhere.

**Unverified:** `PreludeGeneratorTest.generate()` (`:101-103`) hardcodes
`/Users/neemsandv/legend/legend-engine` as its default and calls `Files.walk` with no existence
guard and no `Assumptions`. On a machine without the checkout that should throw and fail gate 1
and CI. Inferred from source; could not be run.

---

## 16. What is not guarded at all

### A. The `[CONVENTION]` invariants

The marking is honest and rare — but the list includes the load-bearing rules.

| rule | AGENTS.md | the guard that should exist | cost |
|---|---|---|---|
| 1. Frontend does all AST walking and typing | :168 | reflection census: every `TypedSpec` node carries a non-null type stamp after phase G | one afternoon |
| 2. Lowerer does no type inference | :186 | ArchUnit: `lowering` must not call `ModelContext.find*`/`isSubtype` | trivial |
| 3. Dialect owns all SQL rendering | :203 | mostly covered; uncovered half is `FunctionCall("name", …)` in lowering — a regex ratchet | trivial |
| 4. **NO FALLBACKS. NO DEFAULTING** *(the most-cited invariant — 15 javadoc sites)* | :270 | extend §6 to all 479 arms with a per-file register; separately flag `default ->` in a switch over a `sealed` type | one afternoon |
| 6. F must not trigger G | :301 | the `NoEagerUserClassLoadsTest` proxy pattern applied to phase F | one afternoon — the pattern exists |
| `resolver → lowering` | pipeline table | one ArchUnit rule; **already violated at 3 sites** | trivial |

Plus §3's property — **no static cell may be read with no writer** — stated nowhere, enforced
nowhere, and the one that actually broke.

### B. Two AGENTS.md markings are stale in the *pessimistic* direction

Invariant 5 says *"`NoEagerTypeReferencesTest` and `NoEagerUserClassLoadsTest` died with the
engine module. Core has **no lazy-loading enforcement at all**."* **Both files exist and are
real**, rebuilt for core (139 and 114 lines; the latter hands `SpecCompiler` a fail-fast
`ModelContext` proxy over four cold FQNs). Invariant 8 says *"`ArchitectureTest` is 23
dependency-direction rules and nothing else"* — it has **35** test methods including
reflection, static-state and JDBC-funnel rules. The doc rotted; the guards did not.

### C. Twelve verified "THE ONE OWNER" contradictions

~30 files under `src/main` assert "THE ONE OWNER" / "the only place" / "single source of truth".
**None is mechanically checked.** Twelve are false.

| # | claim | claimed at | contradicted by |
|---|---|---|---|
| 1 | "the **only place in legend-lite that splits an FQN**" | `protocol/Protocol.java` | **55** other sites: `MetamodelSeeds:115,126,191,619`, `AggAwareActivities:81`, `DiagramService:194,199`, `ConnectionResolver:184`, +47 |
| 2 | `sql/dialect` sole-renderer claim | `sql/dialect/package-info.java` | partially guarded by `SqlTextRatchetTest`'s register — whose existence disproves the wording |
| 3 | `OverlayElementSink` sole-sink | `OverlayElementSink` | a second sink |
| 4 | "single source of truth for JSON string escaping" | `server/Json.java` | `protocol/Escapes:36-42`, which `ProtocolEmitter:3345` in turn calls "the ONE table". **Two files each claim to be the one escaper** |
| 5 | `ModelBuilder.findLegacyMapping` choke point | `ModelBuilder` | guarded by `LegacyReachbackCensusTest`'s register — again disproving the wording |
| 6 | "single source of truth" for the provenance tag and `$`-sigil | `model/SynthHat.java:8` | `SpecCompiler:156` (`.contains("$class$")`), `GraphEmission:2614` (`"$prop$"`). Build side clean; **read side spells the literals twice** |
| 7 | "THE ASOR protocol — ONE owner" | `resolver/AsorRef.java:7` | `lowering/AsorReaders:99` — a **second full decoder as a hardcoded regex** `"^001:010:\\d{10}:([^:]*):…"` plus its own marker skip at `:90`, sharing no code. Weaker: `SnapshotEnvelope:129-141` re-implements the framing in SQL |
| 8 | "SPEC-SOUND constant folds — ONE owner" | `compiler/spec/NormalizeFolds.java:17` | `LiteralUnroll:435` folds `size(x)` — same fold as `NormalizeFolds:82`; `LiteralUnroll:378` duplicates `NormalizeFolds:95`. **A third evaluator** at `StaticFold` |
| 9 | "the ONE owner of the `'#'`-suffix convention" | `resolver/SyntheticHeads.java:43` | mint: `ScanRelations:1889,1919`. Decode: raw `indexOf('#')` at `TemporalFrame:498-499,637-638`, `NavMaterializer:960-961`, `OccurrenceBundling:190-192` |
| 10 | "the only code that knows the runtime value's shape" | `ContextReading.java:20`, `ExecutionContext:190-204` | `StatementExecutor:1181,1189,1194` — raw `ni.properties().get(...)` **in the same block that correctly calls the Reader**. This is the claim `PlatformNamesGuardrailTest` exists to hold |
| 11 | "THE multiplicity algebra — ONE owner" | `compiler/element/type/Multiplicity.java:63` | `Typer:2655-2666` sums bounds inline with its **own private non-`Bounded` fallback of `[1..1]`** — precisely the "four sites, four fallbacks" pattern the javadoc says was consolidated |
| 12 | "`equal()` — pure's equality over wire values (ONE owner)" | `exec/PureAsserts.java:238` | `exec/TdsCompare:344` (`tdsEquivalent`, cell loop `:353-373`) — a complete second comparator: `Number` by `doubleValue()` within a delta, temporals by `epochSeconds`, else raw `Objects.equals` at `:370`, **never calling `equalScalar`**; second site `:541` |

Verified as genuinely holding: `sql/SqlTyping:9`, `exec/RaisedErrors:25`,
`lowering/InstanceEquality:23`, `lowering/Fold:1061` + `parser/PmcdParser:67`.
`lowering/LiteralSpelling`'s claim remains **UNVERIFIED**.

**Only three of the twelve have any guard** (#2, #5, partially #3), and all three are
shrink-only registers whose own existence disproves the "ONE owner" wording.

**#12 is the important one for guard design.** It was initially scored as *holding*, on the
strength of `VerdictChannelRegisterTest`'s caller register — which pins *who may call*
`PureAsserts.equal*`, **not that a registered caller doesn't also carry a private comparator**.
`TdsCompare` *does* delegate on the grid path (`:103,276,331`), which is why the register looks
clean. **Two claims flipped from "holds" to "contradicted" on a second look (#9 and #12), both
because the check looked for the narrow spelling the javadoc names rather than the concept it
claims to own.** That is the failure mode a "ONE owner" javadoc invites, and it argues the right
guard is not review but an **ownership census**: a machine-readable register mapping each claim
to a regex for the concept's spelling, ratcheting the out-of-owner site count. One afternoon; it
would have caught eleven of the twelve.

### D. Native signatures vs upstream

`PreludeGeneratorTest` proves the mechanism for classes/enums. Extending it to `Pure.java`'s 482
native FQNs is the single highest-value missing guard. One afternoon reusing the generator; needs
a policy decision for the ~244 FQNs upstream declares non-natively.

---

## 17. Vacuous tests

**Verified count: 2** — by reading every candidate, not by scanning. Both in
`integration/RelationalMappingIntegrationTest.java`:

- `:3321 testXStore` — body is one comment; `// F2.6: un-disabled — the audit flagged this GAP stale (XStore IS in the grammar)`
- `:3329 testAggregationAware` — same shape

Both were un-disabled by an earlier pass **without ever being implemented**. The repo already
knows: `MinimalCorpusTest:109` cites "the assert-free twin and **the two vacuous placeholders**
it walked" — *as part of the derivation of the 2454 floor*.

An exhaustive whitespace-only-body scan across **4,426** test methods (core 4,267 · nlq 98 ·
parser-equivalence 52 · pct 6 · experiments 3) returns exactly these two enabled, plus 15
`@Disabled` ones — all carrying a `GAP: ` reason enforced by `SkipCensusTest:74`. No
class-level `@Disabled`.

Read as *assertion-free* rather than *empty*, the number is **35 enabled tests that can never
fail on a wrong answer**, plus 3 that only skip: 18 print-only (incl.
`DuckDBIntegrationTest:3587 testPureSyntaxIsLeap`, which never touches Pure syntax; and
`ProbeWireShapes:41`, an 850-line self-declared instrument), 7 implicit does-not-throw (incl.
`SpecCompilerTest:143`, whose `check(` is `Expected.check` — a **production record factory**,
not an assertion — and `DuckDbValidityTest:73,103,128`, whose class javadoc claims *"the RESULT
is asserted too"*), 3 assume-only, 3 in `experiments/`, and 2 deliberate JUnit-4 harvest shims.

For the record, so nobody re-flags them: all 33 `ArchitectureTest` tests assert via ArchUnit
`.check(...)`; all 16 `AdversarialParityTest` via `runFamily` → three `assertEquals`;
`PipelineStageFailureTest` via `failsWith` → `assertThrows`; `MinimalCorpusTest.corpus` via
`assertTrue` at `:113`.

---

## 18. What is genuinely good

- **The exact-set ledgers.** `RawSqlLedgerTest`, `SqlTextRatchetTest`, `JdbcSurfaceCensusTest`,
  `HarnessDisciplineTest`, `SkipCensusTest`, `LiteralUnrollLedgerTest` use `assertEquals` on a
  set/map, so they fail on *shrink* as well as growth. Re-derived: `SqlTextRatchetTest`'s
  register (604 files) and `JdbcSurfaceCensusTest`'s two registers (15 main / 136 test over 967
  files) — **all currently exact**. This design is why the rewrite's register cleanup was
  *forced* rather than discretionary. The strongest structural property in the suite.
- **`SkipCensusTest`** — the stale-row rule ("a stale row is a register lying"), the `GAP: `
  reason requirement, and the exactly-pinned assumption-skip **file set** across three modules.
  The guard that would catch a new way for the suite to go quiet.
- **`CarrierPurityRatchetTest`: all four pins at zero slack.** What a maintained ratchet looks like.
- **`TenetRatchetTest`: 13 pinned, 13 measured**, coverage floor, and an accessor list widened
  after an audit found `org.sqlite.core` reads slipping the old `java.sql`-only precondition.
- **`METHOD_ALLOWLIST = Map.of()`** — the burn-down actually completed. `FILE_ALLOWLIST` is one
  row at 3510 against 3509. `DEAD_PRIVATE_METHODS = 0` with span masking so recursion is
  invisible to the use count — a sound answer to a real adversarial probe, and the right *shape*
  for the guard §3 needs.
- **The deleted rationalizations.** `Lowerer.relationDepth` removed with the note *"the audit
  found the counter write-only; the allowlist justification was a rationalization, exactly what
  this guardrail is for."* `SpecParser.java`'s file-size row retired by taking the named seam
  instead of growing the pin.
- **Honest failure documentation.** `JdbcSurfaceCensusTest`'s javadoc states its own KNOWN LIMIT
  *and names which guard covers the gap*. `JavaEvalLedgerTest`'s comment-stripped counting is an
  explicit answer to a probe showing raw counts let deleted comments fund new evaluation code.
- **`[ENFORCED]` vs `[CONVENTION]` marking** — rare, valuable, and where stale it *understates*
  the suite.
- **`GuardCoverage` as a concept** — the right invention for the right failure (a
  `src/main → src/test` move that walked 7,564 lines out of seven guards). Needs re-pinned
  numbers, not redesign.
- **`PreludeGeneratorTest`** — real spec verification against the real checkouts; the
  anti-tautology and the template for fixing the native golden.
- **`JavaEvalLedgerTest`'s stale-row rule** — a missing pinned file goes red rather than
  skipping. The correct design, in the same suite as the `EngineTestExecutor.java` bearer bond.

---

## 19. What DONE means for the guard suite

In value order.

1. **Fix §3 and guard the class.** Rewire or delete `ORDERED_QUERY`, `SORT_KEYS`,
   `FORCED_MECHANISM`, `LAST_DECLINE`, `CONTEXT_SOURCE`; add `DanglingStateCensusTest`. **Re-run
   the lane and expect the pass count to DROP** — if it does not, the ordered branch was not
   exercising either.
2. **The corpus gate asserts a roster** — `assertEquals` on the committed 2454/1866 name sets,
   plus a ceiling, plus a non-zero assertion on the ordered-compare counter.
3. **Restore or consciously retire, pin by pin with a dated justification**, the nine
   `assertEquals(0, …)` alarms deleted in `ff359bae`.
4. **Tighten every `EVICT_SIZE` row to measured and make it fail on shrink**, as `EVICT_NAMES`
   does. Starting slack 751 lines.
5. **Amend `staticCollectionStateIsImmutableOrRegistered`** (mutable-cell branch, non-final
   check, count the `catch (Throwable)` skips). Expect ~51 new register rows.
6. **Fix `noStaticMutableState`** — drop the `(?!final )` lookahead for a type blocklist, add
   `@` to the field char classes, handle multi-line declarations. `CONTEXT_SOURCE` is live proof
   all three are needed.
7. **Add the `resolver → lowering` ArchUnit rule** and fix the three violations.
8. **Widen the two name-dispatch ratchets to their real populations** — 87 → ~138, 73 → ~116 —
   and re-pin. The point of these is the descent, and they measure the wrong denominator.
9. **Extend `defaultLiteralFallbacksOnlyShrink` to all 479 arms**, plus a rule flagging
   `default ->` over a `sealed` type.
10. **Anchor `LiteralUnrollLedgerTest` on `is\(\w+, "…"\)`.**
11. **Delete every register tombstone** and give every register `JavaEvalLedgerTest`'s stale-row
    rule so tombstones fail rather than sit.
12. **Add a guard-comment symbol check** — no justification may name an absent class or method.
13. **Re-pin every `GuardCoverage` floor to (measured − 5)** and change the doctrine from
    "growth is free" to "re-pin on growth". Give `PlatformNamesGuardrailTest` and
    `ParserBoundaryArchTest` floors; delete the nonexistent `../server/src` root.
14. **Add a rename-goes-red roster to gate 1** and call `skipped()` for gate 1. Then wire
    `CorpusDifferentialTest`'s generator into the chain or delete the test.
15. **Verify native signatures against upstream** by extending `PreludeGeneratorTest`'s
    generator. Until then, say plainly in `NativeFunctionTest`'s javadoc that it is a
    change-detector.
16. **Build the ownership census** over the ~30 exclusivity claims and fix or retract the twelve.
17. **Make `runtimeShapesAreReadByTheOneReaderOnly`'s javadoc match its mechanism**, or
    implement the shape check — and fix `StatementExecutor:1181,1189,1194` either way.
18. **Delete or implement `testXStore` and `testAggregationAware`**, re-derive the 2454 floor
    without them, and consider a `noAssertionFreeEnabledTest` guard with a register.
19. **Fix the stale docs the guards are the mechanical form of**: AGENTS.md:49-51, invariant 5's
    "no lazy-loading enforcement at all", invariant 8's "23 rules", and `GATES.md:48-66`.
