# Standing gates — every change cycle runs ALL of these, sequentially

Established 2026-08-02 after the engine-suite audit: 23 tests had been
failing for months because only the corpus runner was gated. The FULL
suite is the acceptance scoreboard — a runner-only cycle is not a gate.

**2026-08-11: the engine module is DELETED.** Its behavioral suite lives in
core (`com.legend.integration`), the corpus runner in `com.legend.rcorpus`,
the server shell in `com.legend.server`. Gate 3 (engine suite) folded into
gate 1; gates 4/5 run `-pl core`. Gate numbers stay stable in
`tools/allgates.sh` so logs remain comparable.

**Numbers below are refreshed 2026-08-06.** Prefer regenerating a report to
quoting one; the ratchet constants in the test sources are the authority, and
they move.

**2026-08-22: GATE 9 added — the ChannelB dual-verdict suites** (all five:
Standard/Essential/Grammar/Unclassified/Relation; discovery pins 287/137,
sql-verdict disagree=0, decline ceilings). Added because the X-slice pushed
with a ChannelB pin unvalidated: the suites were in NO gate, and their
discovery pins depend on `-Dlegend.pure.root`/`-Dlegend.engine.root` SYSTEM
properties (env-only hand-runs silently referee the stale `$HOME` checkout
and fake a 280!=287 "regression" — same trap class as the corpus root).

---

## The root flag is a SYSTEM PROPERTY, and the fallback is silent

`rcorpus/Corpus.java:47` reads `-Dlegend.engine.root`, defaulting to
`$HOME/legend/legend-engine`. It does **not** read the `LEGEND_ENGINE_ROOT`
environment variable — that name exists only for `tools/allgates.sh`, which
converts it into the `-D` flag for you (`allgates.sh:17-20`). Export the env
var and run `mvn` BY HAND and you get the default checkout with no warning.

On this machine that default is a stale July tag with 2,759 test functions
against the real checkout's 2,798, so a hand-run sweep reports a plausible
seven-family "regression" that does not exist. It cost an hour and a false
"main is red" report on 2026-08-08.

**The tells, in the order they appear:** `census: 2759` instead of `2798`;
`h2-exec 0 verified` (the goldens do not match, so nothing verifies); and a
~320s runtime instead of ~90s. Any one of them means the wrong checkout —
check the flag before reading the scoreboard. Prefer `tools/allgates.sh`,
which cannot make this mistake.

## Read this before trusting a green

Three ways this chain reports success without having checked anything:

1. **CI enforces gates 1, 2 and 4 only — and gate 4 skips.**
   `.github/workflows/gate.yml` runs `core clean test`, `core install`, and
   `engine test -Dtest=RelationalCorpusRunner`. It does **not** check out
   legend-engine, so `Corpus.available()` is false,
   `RelationalCorpusRunner.java:55` skips via `Assumptions`, and JUnit reports
   success. **Gates 3, 5, 6, 7 and 8 never run in CI at all.** A green CI badge
   means the core suite passed.
2. **`tools/allgates.sh` has no `set -e` and always exits 0.** It echoes
   `G<n>_EXIT=` lines into `$GATES_LOG` (default `/tmp/gates.log`). Pass/fail
   must be read by eye — the script's own exit code tells you nothing.
3. **Missing upstream checkouts skip rather than fail.** Gates 4, 5 and 8 all
   need `~/legend/legend-engine` (and gate 8 also `~/legend/legend-pure`).
   Without them the tests `Assumptions`-skip, which is **not** a pass. The
   corpus baseline reader is worse: `readBaseline` prints "gate SKIPPED" and
   goes green if `docs/RELATIONAL_CORPUS.md` is unreadable.

**Core must be INSTALLED before any downstream run.** `mvn -pl <module> test`
resolves `legend-lite-core` from `~/.m2`, **not** the reactor — so after
touching core it silently A/Bs the previously installed jar. Use `-am`, or run
gate 2 first. This has already produced a phantom regression report
(2026-08-06: four DIFFs and a collapsed column count that did not exist).

Sequential, never parallel — concurrent heavy JVMs get killed on this machine.
And do not BUILD while a chain runs: a `mvn install` underneath a running gate
swaps the jar it loaded and produces a fake failure (2026-08-08: G8 reported
MATCH 25,142 mid-chain; re-run clean it was 25,472, the baseline exactly).

## Budget decision, 2026-08-10 — gate 8 grew by ~100s

**Four `parser-equivalence` test classes were in no gate and no workflow**, including the
two that pin the programme's flagship claims. All four now run in gate 8.

| class | time | pins |
|---|---:|---|
| `ViewFilterParityTest` | 0.8s | view-filter shapes |
| `CorpusSweepTest` | ~40s | THE consolidated sweep (2026-08-12): whole-document parity + SPI seam + dialect quarantine + leniency classification — absorbs the deleted `PmcdEquivalenceTest`/`StrictDialectParityTest`/`LeniencyCatalogTest` |

> **A measurement warning, learned the hard way.** My first timing put
> `StrictDialectParityTest` at **722s** and I nearly recorded it as unaffordable. It was a
> slept/preempted run — precisely the failure mode this file documents below. Re-measured
> under `caffeinate -dims` it is **34s, 21× faster**. **Never time a gate on this machine
> without `caffeinate`, and treat any outlier as suspect before treating it as data.**

The chain moves **324s → ~424s (7.1 min)**, over the 330s ceiling. Per this file's own
rule that is recorded, not absorbed. Three ways to settle it, all explicit human decisions:

1. **Raise the ceiling to ~430s.** These four gate claims that were previously enforced by
   nothing automated — `DEEP_AUDIT_HANDOFF.md` calls `PmcdEquivalenceTest` "the audit's
   strongest regression net", and it ran in no gate at all.
2. **Take the cut this file already nominates** — gate 5 (41s, the same sweep as gate 4
   against a second backend, scoreboard not written) → ~383s.
3. **Split the chain**: the fast seven on every push, the four heavy parity tests
   pre-push/nightly. Riskier — a gate that runs less often is a gate that catches less.

## Budget decision, 2026-08-12 — the sweep collapse: gate 8 143s -> 50s, chain 5m22s

The user's challenge ("time should have gone DOWN — did the parser regress?")
forced the full decomposition:

- **Lite's parser did NOT regress**: `parseDocument` covers the ENTIRE corpus in
  ~0.5s, and an A/B against the pre-flip commit measured the strict flip
  marginally FASTER (477ms vs 514ms avg).
- The growth was (a) four tests ADDED by the simplification plan (+~35s,
  RefusalSymmetryTest dominating) and (b) the `OracleParses` evict-after-2
  policy silently re-running the whole engine oracle on sweeps 3 and 5
  (~24s each) once five tests consumed it.
- The REAL fix was the plan's own end state, previously skipped: ONE sweep
  (`CorpusSweepTest`, ~39s) replacing six classes and the cache entirely —
  one oracle parse per source, every claim a column, all assertions
  collected. Two slack ratchets surfaced immediately and tightened
  (strict census 258 -> 187, JSON-asymmetry 10 -> 9).

Measured 2026-08-12, full chain GREEN: G1 29s, G2 8s, G4 92s, G5 43s,
G6 76s, G7 24s, G8 50s — **5m22s total, back under the 5.5m ceiling**.
Standing rule reaffirmed: time a full chain after every harness-shape
change; a budget breach is an entry here, never an absorbed drift.

## Budget decision, 2026-08-14 — gate 8 +13s for three new standing gates

An in-chain reading of 380s (6m20s) triggered an audit; most of the
delta was same-day cache/thermal contention (three chains back to
back). Isolated re-measure: G8 63s (was 50) — +6.5s is the actual
test time of THREE new members (`FixtureCorpusParityTest`, 266
vendored sibling sources; `MutationFuzzTest`, 950 live differential
mutants; protocol-check inside the sweep) and ~6s is compiling the
larger core; the sweep itself is unchanged at ~39s. G4 97s / G5 ~50s
(+5-7s each — the Phase-1/2 validation walks now run inside corpus
parsing). Honest chain estimate ≈ **5m45s**. Decision: the ceiling
moves to 6m — 950 mutants + the fixture ratchet + engine-side
protocol validation are the cheapest coverage per second in the whole
chain, and the alternative (sampling them) reintroduces the silent
blind spots they exist to close.

2026-08-14: `GrammarCoverageCensusTest` (the bulletproof-and-total
program's completeness instrument — corpus coverage of the engine's
own grammars, ratcheted; see GRAMMAR_COVERAGE_CENSUS.md) is
TRIGGERED, NOT SCHEDULED: its inputs are both pinned (corpus manifest
SHA + oracle jar version), so its output is a constant between pin
changes and re-measuring a constant every chain is pure cost (~40s).
Run it — ratchets enforced — on exactly three triggers: corpus
manifest change, oracle-pin bump (it is a step of the bump procedure),
or edits to the census itself:
  mvn -pl parser-equivalence -am test -Dtest=GrammarCoverageCensusTest \
      -Dlegend.engine.root=... -Dlegend.pure.root=...
The chain ceiling stays 6m.

2026-08-15 re-pin (post literal-fold, 0e527998): measured chain
5m03-5m06s — G1 28-29, G2 8-9, G4 72-73, G5 35-37, G6 76, G7 24,
G8 59 — back under the 2026-08-08 5m22s best. The fold took G4
89->72 and G5 44->35; ceiling stays 6m as headroom against this
machine's +/-20-30% wobble. Per-mutant oracle instances were
already hoisted (FixtureCorpusParityTest 2.4s -> 0.5s); the next real
lever, if the budget ever binds, is sharing one surefire JVM across
gates 4/5 (the family-sharding speed leg), not thinning coverage.

## Budget BREACH, 2026-09-02 — group F landed at 12m54s; the fix is batch 8

The group F burn (eaf025c9) landed GREEN at **776s = 12m54s**: G1 114s,
G4 173s, G5 196s, G6 158s (parser-only G8/G9 flat). Two causes, both
per-execution or per-compile re-derivation of facts that are constant:

1. **Normalizing the injected system metamodel per model compile** —
   2.3ms -> 28.2ms per compile; ~3,000 compiles in G1. Profiled: 40% was
   `UnionSynthesis.mergedScan` PRINTING syntax trees to compare them
   (quadratic in the 21-member if-chain), 45% an unindexed subclass
   search over the whole class universe per inheritance op. Both FIXED
   in batch 8 (record equality; a direct-subclass index per model +
   native catalog): 28.2ms -> 8.0ms. The residual 5.7ms is normalizer
   re-derivation the boot-layer leg removes.
2. **Seeding ~20 metamodel tables of a corpus-sized graph on EVERY
   store-reading execution** (the four op-tree tables each re-walked the
   whole graph). Batch 8: THE SYSTEM DATABASE (user ruling) — one
   in-memory database per graph per engine, separate from every user
   connection, written ONCE (exec/SystemDatabase, ModelContext.derived);
   the executor ROUTES store-reading bodies to it. DuckDB lane 173s ->
   66s; H2 lane 196s -> 159s.

**Named residue (batch 9):** the H2 lane's remaining 110s is TEN
typeInference tests (9–18s each — the per-test `slowest` ledger names
them): their queries join the `RelationalOperationElement` extent, a
UNION ALL over five store tables (tables/columns/views/table_aliases/
relational_ops), which H2 cannot index (rescan per outer row; DuckDB
hash-joins it in ~1ms). Fix = the store's own idiom: ONE table for the
hierarchy (`kind` column, `id` PK — as data_types/relational_ops already
are) so the extent is an indexed filtered scan, plus the plain-`id` key
read for merged members. Then the boot layer (system elements compiled
once per process). The 5.5-minute ceiling is re-armed when both land.

**Batch 9 + 10 (same day): landed.** Single table (dea642c4) + union
lowering: H2 lane 137s → 41s (standalone; the ten tests <1s each),
DuckDB 61s, G1 ~55s. Remaining over the 5.5-minute line: G6 (PCT)
~105s vs ~90s pre-group-F and G1 ~55s vs 33s — both the per-compile
normalizer residual (5.7ms) the boot layer + per-mapping index legs
remove. Ceiling re-arm stays pending on those two legs.

**Gate-shape decision, 2026-09-02 — Channel B runs ONCE (G9).** Homework,
not script-reading: G6's exact command (`cd pct && mvn clean test` with
both root properties) executed the five Channel B suites alongside the
five PCT suites (1115 tests), and G9 executed the same five classes on
the same two properties (5 tests). The discovery / disagree-zero /
decline-ceiling assertions live INSIDE those classes, so the two gates
asserted the same facts on the same inputs — ~13s of duplicate test
time per chain (ChannelBRelation 4.8s, Essential 3.4s, Standard 2.3s,
Unclassified 1.2s, Grammar 1.2s). Cut: G6 excludes `ChannelB*`
(`-Dtest='!ChannelB*'`) and is purely the PCT suites; G9 stays the one
Channel B run with the roots pinned at the gate and its own log line
(user choice: the dedicated gate is the cleaner home). Measured: G6 86s
→ 82s (1115 → 1110 tests), G9 18s; chain 5m49s — the module's build and
JVM startup dominate G6, so the wall saving is ~4s of the 13s of test
time; the cut is kept for its shape (no fact asserted twice).

**Batch 52 (post-processors as compiler passes: the nonExecutable IR pass; the text verdict arm takes the toSQLString runtime overload with the runtime's table replacements on the rows leg, 2026-09-03): chain GREEN
(gates 1,2,4,5,6,7,8,9)** — ratchet 279/2294 → 277/2296 (+2, 0 lost); lane move text-only 25 → 24 (disagree 0); SqlTextVerdicts ledger 669 → 690 (justified). G1 40s, G2 9s, G4 58s, G5 39s, G6 82s, G7 25s, G9 18s, G8 71s.

**Batch 53 (THE COMPILER COMPARES, THE DATABASE COMPUTES — tier-1 unroll with residuals, debugPrint 9 with zero Java value computation; the world map: docs/WORLD_MAP.md + TENET_CHARTER Clause 6, 2026-09-03): chain GREEN
(gates 1,2,4,5,6,7,8,9)** — ratchet 277/2296 → 267/2306 (+10, 0 lost; disagree 0); lane move exec-passing 59 → 58 (disagree 0); JavaEvalLedger AssertVerdicts 1511 → 1529 (justified: per-class nested key projection; SQL-canon follow-up named); LiteralUnrollLedgerTest pins the compare-only fold set; StoreNav's host construction set DELETED. G1 40s, G2 9s, G4 55s, G5 38s, G6 83s, G7 25s, G9 19s, G8 71s.

**Batch 55b (toPostgresModel slice B, the compiler side — a system-store row dispatches over the relation's kinds; list-shape folds, 2026-09-04): chain GREEN
(gates 1,2,4,5,6,7,8,9)** — ratchet 252/2321 → 251/2322 (+1 testConvertJoinStrings, 0 lost; disagree 0); exec-passing 58 unchanged; sqltypes untyped=0; G1 45s (4,396), G2 8s, G4 63s, G5 47s, G6 82s, G7 26s, G9 18s, G8 74s; channel B unchanged (316/137/204/355/95, disagree 0). The previous session's nine uncommitted files were AUDITED against the real Pure sources: kept the declaration-only arm scan (`UserCallInliner.declaredSubtype`) and the lexicographic recursion measure (literal size, then a store argument of a class no enclosing activation holds); reverted the legend-pure `functions.pure` library admission (12 effect natives; the platform already owns that file's views in `SystemMetamodel`), the native-span text blanking, the native duplicate key, `orElse`, `string::plus(String[*])` + the Typer catch + the NameResolver multi-candidate, the widening-cast type, and the static first-arm dispatch (never fired) — each of their receipts was a wall inside an arm that must be DEAD for a Table input (reached through the already-admitted pureToSQLQuery library). New: `children()`/`childByJoinName()` as SystemMetamodel views (functions.pure:288-296); a runtime match over a SYSTEM-STORE row (a navigation rooted at an element reference) keeps only the arms some class bound in the system mapping beneath the declared class reaches (Table's rows are Table/View, never ViewSelectSQLQuery); a primitive input keeps only its lattice's arms; folds: spelled scalar `cast` to its primitive, `cast` over the empty spelled collection, native `concatenate` (and its empty-side identity), `zip`, `init` (LiteralUnrollLedger +concatenate/zip/init); `SqlTypeCensus` locators name a struct's blind field and a call's blind argument. Family 12/21 → 13/21; six of the eight left sit at the store-row leg ("class query under TypedNewInstance"), two are the §7 row-backed-recursion residue. Hang root cause: the library file's natives entering the model (never thread-dumped; removed by the audit).

**Batch 55a (the Java port of toPostgresModel and the host metamodel walk are DELETED, 2026-09-04): chain GREEN
(gates 1,2,4,5,6,7,8,9)** — ratchet 255/2318 → 252/2321 (+3, 0 lost; disagree 0); exec-passing 58 unchanged; G1 42s (4,396), G2 9s, G4 56s, G5 43s, G6 79s, G7 25s, G9 19s, G8 72s; channel B unchanged (316/137/204/355/95). Deleted: `exec/MetamodelWalk.java` (905 lines), `MetamodelSteps.java` (156), the executor's planWalk/constructNode/constructOp/nodeValue/walkProp/walkFilter/walkResult arms (583 lines; StatementExecutor 3,494 → 2,911), the harness's `instanceOfAssert` NodeH string-match arm; JavaEvalLedger register rows for both files removed, executor EVICT pin 40 → 5, AssertVerdicts 1568 → 1576 (justified: assertInstanceOf reads the wire's `__type` up the model's subtype relation). The three tests the walk still scored (measured by the nowalk probe: ratchet unmoved, family scoreboard −3) now ride the platform: SQLExecutionNode.connection and its LocalH2 datasource specification are plan ROWS (`plan_connections` / `plan_connection_sqls`, PlanRows.connectionRows, mapped as the engine's connection classes under inheritance operations; the cast raise beside a to-many leaf is stamped per joined row), a property-less class constructor is the identity struct (`ClassLayouts.syntheticOnlyLayout`), `assertInstanceOf` over a conforming literal folds (LiteralUnrollLedger +assertInstanceOf). Prelude +1 generated class (LocalH2DatasourceSpecification, demanded by the system store).

**Batch 54 (OPTION S — the prelude's library shapes are GENERATED from the spec; toPostgresModel slice A, 2026-09-04): chain GREEN
(gates 1,2,4,5,6,7,8,9)** — ratchet 267/2306 → 255/2318 (+12, 0 lost; disagree 0); G1 44s (4,396), G2 8s, G4 61s, G5 44s, G6 78s, G7 26s, G9 18s, G8 72s; channel B essential 316 / grammar 137 / standard 204 / relation 355 / unclassified 95, disagree 0 everywhere. Chain catch on the way: the core-import tier resolves a bare `equality`/`temporal`/`PCT` profile to its m3 FQN, and three consumers matched the BARE spelling — identity layouts silently replaced equality keys (channel B head/first/contains/in/equal over `<<equality.Key>>` classes); `PlatformTypes.isProfile` (exact FQN, or the bare spelling of a model that does not declare the profile) is now the one rule (ClassCompiler, FunctionCompiler, MilestoningStrategy). Also: `tail` over a spelled list and a `cast` over a spelled collection fold (the untyped FoldCall root in toPostgresModel's binary-expression chain is gone; sqltypes untyped=0); exec-passing 58 unchanged; NativeFunctionTest hand-class pin 255 → 76 (217 hand copies of spec shapes deleted — the generated `Prelude.java` (PreludeGeneratorTest, `-Dprelude.generate=1`, verify mode in the chain) carries 230 classes / 10 enums with their equality keys and defaults; hand = m3 bootstrap (tools/m3shape.py receipts), primitives, carriers, 13 Java-referenced definitions and 6 SYSTEM-STORE-COUPLED shapes); hand-enum pin 19 → 6; LiteralUnrollLedger fold set + size/contains/keyValues/get/defaultIfEmpty/assert/enumValues/dynamicNew/isTrue/greaterThan/lessThan/greaterThanEqual/lessThanEqual/pair (all compare-only); native catalog +6 signatures (eval/3, elementToPath(Type), collection groupBy/2, keyValues, defaultIfEmpty, dynamicNew ×2, isTrue). Receipts: docs/DECLARATIONS_HOMEWORK_2026_09_04.md; NameResolver.CORE_IMPORTS (real pure's implicit import group).

**Batch 51 (an Any-typed struct field decodes as its value at the wire, 2026-09-03): chain GREEN
(gates 1,2,4,5,6,7,8,9)** — ratchet 280/2293 → 279/2294 (+1, 0 lost); lanes unchanged (exec-passing 59, M1 rescued 54, disagree 0). G1 42s, G2 8s, G4 59s, G5 39s, G6 84s, G7 26s, G9 19s, G8 75s.

**Batch 50 (the engine-style H2 referee spells the MMMyyyy month-abbreviation parse, 2026-09-03): chain GREEN
(gates 1,2,4,5,6,7,8,9)** — ratchet 281/2292 → 280/2293 (+1, 0 lost); lane move exec-passing 60 → 59 (disagree 0). G1 40s, G2 8s, G4 58s, G5 41s, G6 84s, G7 27s, G9 19s, G8 74s.

**Batch 49 (a let-bound legacy aggregate value defers to the groupBy that consumes it, 2026-09-03): chain GREEN
(gates 1,2,4,5,6,7,8,9)** — ratchet 282/2291 → 281/2292 (+1, 0 lost); lanes unchanged (exec-passing 60, M1 rescued 54, disagree 0). G1 42s, G2 8s, G4 57s, G5 38s, G6 81s, G7 26s, G9 18s, G8 72s.

**Batch 48 (enumeration mappings as system-store rows; enumerationMappingByName and toDomainValue as Pure bodies over them, 2026-09-03): chain GREEN
(gates 1,2,4,5,6,7,8,9)** — ratchet 284/2289 → 282/2291 (+2, 0 lost); lanes unchanged (exec-passing 60, M1 rescued 54, disagree 0); native class pin 255 → 256. G1 40s, G2 8s, G4 57s, G5 38s, G6 79s, G7 25s, G9 18s, G8 72s.

**Batch 47 (parseDate is a semantic SQL node the dialects spell; the engine-style H2 text carries the engine's parsedatetime idiom, 2026-09-03): chain GREEN
(gates 1,2,4,5,6,7,8,9)** — ratchet 285/2288 → 284/2289 (+1, 0 lost); lanes unchanged (exec-passing 60, M1 rescued 54, disagree 0). G1 40s, G2 8s, G4 55s, G5 35s, G6 82s, G7 25s, G9 18s, G8 71s.

**Batch 46 (relation-rooted plan text: a table accessor / tableToTDS single node with precisePrimitives accessor columns; a map over a scalar read composes the mapper over the read, 2026-09-03): chain GREEN
(gates 1,2,4,5,6,7,8,9)** — ratchet 287/2286 → 285/2288 (+2, 0 lost); lane moves exec-passing 61 → 60, M1 rescued 55 → 54 (disagree 0). G1 40s, G2 9s, G4 54s, G5 38s, G6 77s, G7 26s, G9 19s, G8 73s.

**Batch 45 (if() over a class query decides on literal emptiness; a TDSNull-typed collection root egresses as the TDSNull value, 2026-09-03): chain GREEN
(gates 1,2,4,5,6,7,8,9)** — ratchet 291/2282 → 287/2286 (+4, 0 lost); lane moves exec-passing 63 → 61, M1 rescued 57 → 55 (disagree 0). G1 40s, G2 9s, G4 55s, G5 38s, G6 77s, G7 26s, G9 18s, G8 73s.

**Batch 44 (no-decision singles: zip is the positional list_zip pairing, the envelope splice erases cast/rows after splicing their source, meta::pure::tds::extend dispatches to the extend checker, 2026-09-03): chain GREEN
(gates 1,2,4,5,6,7,8,9)** — ratchet 297/2276 → 291/2282 (+6, 0 lost); lane moves exec-passing 68 → 63, M1 rescued 62 → 57 (disagree 0). G1 39s, G2 9s, G4 55s, G5 39s, G6 82s, G7 26s, G9 18s, G8 72s.

**Batch 43 (the referee render runs the H2 carrier strategies: a whole relation collected as a list then exploded becomes rows, 2026-09-03): chain GREEN
(gates 1,2,4,5,6,7,8,9)** — ratchet 303/2270 → 297/2276 (+6, 0 lost); lane moves exec-passing 75 → 68, M1 rescued 62 → 62
(passes 2379, disagree 0). G1 40s, G2 8s, G4 62s, G5 42s, G6 80s, G7 26s, G9 19s, G8 72s.

**Batch 42 (the static extent-subset fact from the typed chain arms the oracle's pk-collapse in the verdict-arm lane, 2026-09-03): chain GREEN
(gates 1,2,4,5,6,7,8,9)** — ratchet 304/2269 → 303/2270 (+1, 0 lost); lane moves exec-passing 76 → 75, M1 rescued 63 → 62
(passes 2379, disagree 0).

**Batch 41 (let-bound column arguments bind at project; the TDG no-seed Error plan, 2026-09-03): chain GREEN
(gates 1,2,4,5,6,7,8,9)** — ratchet 308/2265 → 304/2269 (+4, 0 lost); lane moves M1 verified 4 → 1, exec-passing 79 → 76,
text-only 26 → 25 (passes 2379, disagree 0).

**Batch 40 (the TDG plan as a platform value: plan-flavored TypedTestDataGen + planToString printer, 2026-09-03): chain GREEN
(gates 1,2,4,5,6,7,8,9)** — ratchet 310/2263 → 308/2265 (+2, 0 lost); lane move text-only 27 → 26 (passes 2378, disagree 0).

**Batch 39 (lateral explode → decorrelated UNION on the H2 family; engine-style render runs its passes; plan-text goldens replay their sql node, 2026-09-03): chain GREEN
(gates 1,2,4,5,6,7,8,9)** — ratchet 314/2259 → 310/2263 (+4, 0 lost); lane move exec-passing 82 → 79; text-verdict asserts 156 → 147
(passes 2378, disagree 0).

**Batch 38 (no-decision burn from the sqltext homework: frame mapping to the oracle's enum decode (includes, identity), let-bound join lambdas + declared TDSRow, TDSRow getters, assertSameSQL(String) general arm, paginated-golden rule, 2026-09-03): chain GREEN
(gates 1,2,4,5,6,7,8,9)** — ratchet 330/2243 → 314/2259 (+16, 0 lost); lane moves M1 verified 9 → 4, M1 rescued 75 → 63,
exec-passing 99 → 82, unable-to-exec 14 → 13; text-verdict asserts 170 → 156 (passes 2377, disagree 0).

**Batch 37 (the "text-policy" pre-decline gate DELETED; every sql-assert shape attempted; per-test text-verdict roster, 2026-09-03): chain GREEN
(gates 1,2,4,5,6,7,8,9)** — ratchet 366/2207 → 330/2243 (+36, 0 lost); lane moves M1 verified 12 → 9, M1 rescued 108 → 75,
exec-passing 135 → 99, unable-to-exec 20 → 14 (passes 2374 → 2375, disagree 0). Dossier: docs/SQLTEXT_HOMEWORK_2026_09_03.md.

**Batch 36 (percentile = one semantic reducer with a within-group order; DuckDB encodings as the QuantileOrder MIR pass, 2026-09-03): chain GREEN
(gates 1,2,4,5,6,7,8,9)** — ratchet 369/2204 → 366/2207 (+3, 0 lost); lane moves exec-passing 140 → 135,
M1 rescued 109 → 108 (passes 2374 stable, disagree 0).

**Batch 35 (referee render: literal-collection reductions, firstNotNull, round in the engine-style H2 dialect, 2026-09-03): chain GREEN
(gates 1,2,4,5,6,7,8,9)** — ratchet 379/2194 → 369/2204 (+10, 0 lost); lane move exec-passing 149 → 140
(passes 2374 stable, disagree 0).

**Batch 34 (assertSameSQL(String, String) takes the exec-read rows verdict, 2026-09-03): chain GREEN
(gates 1,2,4,5,6,7,8,9)** — ratchet 394/2179 → 379/2194 (+15, 0 lost); lane moves M1 verified 20 → 12,
M1 rescued 119 → 109, exec-passing 167 → 149 (passes 2374 stable, disagree 0).

**Batch 33 (runtime connections THROUGH lets — JSON source / chain mappings, 2026-09-03): chain GREEN
(gates 1,2,4,5,6,7,8,9; per-gate timings not captured this run)** — ratchet 416/2157 → 394/2179 (+22, 0 lost);
M1 rescued floor 127 → 119 (lane move: passes 2367 → 2374, disagree 0); other pins unchanged.

**Batch 32 (plan-execute FRAMES — the let-chase, rows/cast erase, TDS roots, 2026-09-03): chain 6m00s** —
G1 40s, G2 8s, G4 62s, G5 47s, G6 85s, G7 26s, G9 18s, G8 74s. Ratchet
430/2143 → 416/2157 (+14, 0 lost); exec-passing declines 170 → 167; other pins
unchanged.

**Batch 31 (the query FRONT DOOR — validate desugar in the platform path, 2026-09-03): chain 6m00s** —
G1 40s, G2 8s, G4 62s, G5 47s, G6 85s, G7 26s, G9 18s, G8 74s. Ratchet
446/2127 → 430/2143 (+16, 0 lost); exec-passing declines 171 → 170; other pins
unchanged.

**Batch 30 (effectful helper VALUES + generic multiplicity arguments, 2026-09-03): chain 6m00s** —
G1 40s, G2 8s, G4 62s, G5 47s, G6 85s, G7 26s, G9 18s, G8 74s. Ratchet
451/2122 → 446/2127 (+5, 0 lost); metamodel quarantine rows 22 → 5 (the multiplicity
arguments type reflection chains that walled); exec-passing declines 180 → 171; ledger
StatementExecutor 2692 → 2696 (justified); other pins unchanged.

**Batch 29 (SQL post-processors — CTE extraction, let-bound replaceTables, 2026-09-03): chain 6m00s** —
G1 40s, G2 8s, G4 62s, G5 47s, G6 85s, G7 26s, G9 18s, G8 74s. Ratchet
463/2110 → 451/2122 (+12, 0 lost); M1 verified floor 22 → 20, rescued 128 → 127
(lane moves); other pins unchanged.

**Batch 28 (INLINE handles on demand + the unrolled quantified verdict, 2026-09-03): chain 6m00s** —
G1 40s, G2 8s, G4 62s, G5 47s, G6 85s, G7 26s, G9 18s, G8 74s. Ratchet
487/2086 → 463/2110 (+24, 0 lost); AssertVerdicts ledger pin 1459 → 1511 (a verdict
shape, justified in the ledger); other pins unchanged.

**Batch 27 (referee render COVERAGE — chain mapping, H2 in-lists, 2026-09-03): chain 6m00s** —
G1 40s, G2 8s, G4 62s, G5 47s, G6 85s, G7 26s, G9 18s, G8 74s. Ratchet
505/2068 → 487/2086 (+18, 0 lost); exec-passing declines 198 → 180 (lane move);
other pins unchanged. G6 is creeping (78 → 85s): the first slice to shard if the
chain nears the budget.

**Batch 26 (the referee's render is the FRAME's chain — milestoning leg, 2026-09-03): chain 5m53s** —
G1 41s, G2 8s, G4 62s, G5 43s, G6 80s, G7 26s, G9 19s, G8 74s. Ratchet
581/1992 → 505/2068 (+76, 0 lost); lane pins moved as lane moves (M1 verified
54 → 22, M1 rescued 164 → 128, exec-passing declines 275 → 198); other pins
unchanged.

**Batch 25 (aggregation-aware ROUTING done right, 2026-09-03): chain 5m50s** —
G1 41s, G2 8s, G4 64s, G5 39s, G6 83s, G7 26s, G9 18s, G8 71s. Ratchet
unchanged 581/1992 (0 lost; the five nonGroupBy rewrittenQuery reads now flip
through rows, the Java fold is deleted); all pins unchanged. One failed run on
the way (the error-shape guardrail: the routing walk's unmatched kinds must
throw, not yield a placeholder path).

**Batch 24 (execution ACTIVITIES as rows, 2026-09-03): chain 5m54s** —
G1 40s, G2 9s, G4 65s, G5 44s, G6 78s, G7 26s, G9 18s, G8 74s. Ratchet
653/1920 → 581/1992 (+72, 0 lost); lane pins moved as lane moves: M1 verified
floor 82 → 54, M1 rescued floor 204 → 164, exec-passing declines 344 → 275
(receipt: corpus passes 2355 → 2367, clean 2151 → 2201, text-rescued 165 → 127,
oracle disagreements 0); other pins unchanged. Two failed chain runs on the way
(the rescued floor, then a real NOP-family regression when the rewrittenQuery
fold was deleted — restored).

**Batch 23 (consolidation — handle class from the native signature, shape-free
let registration, one resolver factory, 2026-09-03): chain 5m54s** —
G1 40s, G2 8s, G4 65s, G5 42s, G6 82s, G7 26s, G9 18s, G8 73s. Ratchet
unchanged 653/1920 (0 lost, 0 gained); all pins unchanged.

**Batch 22 (group H — the expression TREE as rows, 2026-09-03): chain 5m49s** —
G1 41s, G2 8s, G4 64s, G5 41s, G6 78s, G7 24s, G9 19s, G8 74s. Ratchet
656/1917 → 653/1920 (+3, 0 lost); native classes 249 → 255 (Multiplicity,
MultiplicityValue, InstanceValue, VariableExpression, FunctionExpression,
SimpleFunctionExpression); metamodel quarantine rows 34 → 22 (the m3 classes
type reflection chains that walled as unknown types); Java arm ReflectAsserts
deleted; other pins unchanged.

**Batch 21 (group I — column lineage AS ROWS, 2026-09-03): chain 5m56s** —
G1 40s, G2 9s, G4 65s, G5 42s, G6 82s, G7 26s, G9 19s, G8 73s. Ratchet
661/1912 → 656/1917 (+5, 0 lost); native classes 245 → 249 (PropertyPathNode,
Res, PropertyPathTree, ColumnWithContext); other pins unchanged.

**Batch 20 (group E — lineage trees AS ROWS, 2026-09-03): chain 5m42s** —
G1 38s, G2 9s, G4 61s, G5 40s, G6 78s, G7 25s, G9 18s, G8 73s. Ratchet
686/1887 → 661/1912 (+25, 0 lost); native classes 244 → 245 (RelationTree);
other pins unchanged.

**Batch 19 (group A — function bodies AS ROWS, 2026-09-03): chain 5m49s** —
G1 40s, G2 8s, G4 62s, G5 42s, G6 80s, G7 26s, G9 19s, G8 72s. Ratchet
729/1844 → 686/1887 (+43, 0 lost); metamodel quarantine rows 77 → 34
(walls 9); proven-empty int-or-null ceiling 67 → 87 (the temporal-TDS
concatenation tests' expressionSequence reads now type and their attempts
execute — same three witnesses, more probes); other pins unchanged.

**Batch 18 (group Q — plan nodes AS ROWS, 2026-09-03): chain 5m48s** —
G1 38s, G2 9s, G4 62s, G5 41s, G6 81s, G7 26s, G9 19s, G8 72s. Ratchet
778/1795 → 729/1844 (+49, 0 lost); walk text-only asserts 35 → 27 (the
plan-text asserts joined the flip cohort); metamodel quarantine rows 125
→ 77 (the plan-read refusals are dead; walls 9 unchanged); required-over-
nullable ceiling 533 → 534 (SQLExecutionNode.sqlQuery over the single-
table plan_nodes); exec-passing 344.

**Batch 17 (group Q opener — executionPlan signature verbatim,
2026-09-03): chain 5m56s** — G1 38s, G2 9s, G4 63s, G5 44s, G6 84s, G7
26s, G9 19s, G8 73s. Ratchet 780/1793 → 778/1795 (+2); other pins unchanged.

**Batch 16 (group D remainder — let-bound runtimes and CSV seeds,
2026-09-03): chain 5m56s** — G1 39s, G2 9s, G4 63s, G5 43s, G6 84s, G7
26s, G9 19s, G8 73s. Ratchet 782/1791 → 780/1793 (+2); other pins unchanged.

**Batch 15 (group D leg 2 — the meta::json tree on the variant lane,
2026-09-03): chain 5m56s** — G1 38s, G2 9s, G4 64s, G5 43s, G6 83s, G7
26s, G9 20s, G8 73s. Ratchet 791/1782 → 782/1791 (+9); exec-passing 344,
h2-exec 82, quarantine 125/9 unchanged; walk text-only asserts 40 → 35
(the paginate helpers' SQL-text asserts joined the flip cohort).

**Batch 14 (group D leg 1 — the router's string entry, 2026-09-03):
chain 5m49s** — G1 39s, G2 9s, G4 62s, G5 42s, G6 80s, G7 25s, G9 19s,
G8 73s. Ratchet 820/1753 → 791/1782 (+29); exec-passing 344, h2-exec 82,
quarantine 125/9 unchanged.

**Batches 12–13 (refs by id 5m44s; inline relations 5m57s with a 55s G5
outlier — two standalone H2 reruns measured 42s/42s, ledgers identical to
batch 12's; watch the next chain).** Channel B once (G9): 5m49s.

**Batch 11 (boot layer, same day): chain 5m51s** — G1 38s (clean build;
29s warm), G2 9s, G4 62s, G5 40s, G6 86s, G7 25s, G9 19s, G8 72s. A model
compile is 0.5ms (8.0ms at the breach, 2.3ms before group F). The 21s
over the 5.5-minute line is G4 at the top of its old range and G6 —
the per-mapping normalizer index leg is the named next slice; the
ceiling re-arms when the chain measures under 330s.

## The time budget: ~6m40s measured 2026-08-11 — re-pin pending

The 5.5-minute lock (measured 2026-08-08) was already exceeded BEFORE the
engine-module deletion (6m32s with the module still present), and the
deletion itself was time-neutral (6m41s after — gate 3's removal offset the
suite growth in gate 1). Suspected growth since the 08-08 pin: gate 8's
strengthening (whole-document parity + the four previously ungated tests)
and the clean NullAway compile absorbing the server shell. `allgates.sh`
now stamps per-gate wall time into the log (`GN_EXIT=0 (took Ns)`) — re-pin
this table from the next run's stamps instead of guessing.

Measured per-gate 2026-08-11 (the runner now stamps these into the log):

| # | gate | 08-08 | 08-11 |
|---|------|-------|-------|
| 1 | core suite (clean; 4,046 tests — engine's suite folded in) | 13s | 29s |
| 2 | core install | 1s | 8s |
| 3 | (folded into gate 1 — engine module deleted) | 21s | — |
| 4 | DuckDB corpus sweep | 92s | 93s |
| 5 | h2 corpus sweep | 41s | 43s |
| 6 | PCT full | 73s | 78s |
| 7 | PCT h2modern guard | 24s | 24s |
| 8 | parser parity | ~65s | **123s** → 103s after the oracle-parse dedupe |
| | **total** | **~330s** | **398s (6m38)** → ~6m15 |

The minute went to GATE 8: it roughly doubled when the whole-document PMCD
parity test (5,259 sources) joined the element-level sweep (26,168 verdicts)
— both layers re-parse largely the same source text, and the recorded
"harness dedupe" follow-up (PMCD-parity notes) is the lever to claw much of
it back: parse each distinct source once, feed both verdict layers from the
same parse. Everything else moved by seconds.

Previous table (2026-08-08 measurements) for reference:

| # | gate | time |
|---|------|------|
| 1 | core suite (clean, ~4,000 tests — engine's behavioral suite folded in) | ~35s |
| 2 | core install | 1s |
| 3 | (folded into gate 1 — engine module deleted) | — |
| 4 | **DuckDB corpus sweep** | **92s** |
| 5 | h2 corpus sweep | 41s |
| 6 | **PCT full (1,109)** | **73s** |
| 7 | PCT h2modern guard | 24s |
| 8 | **parser equivalence** | **59s** |
| | **total** | **324s — 5.4 min** |

**The whole chain must stay at or under 5.5 minutes (330s).** Adding work that
breaks that ceiling is an explicit decision to be argued and recorded HERE, not
absorbed silently — a chain that creeps toward ten minutes stops being run, and
a gate nobody runs is not a gate.

Two things this table settles. G1 is 13 seconds, not the minute-plus it is
usually assumed to be, so `clean` costs almost nothing and stays. And the
33-grammar oracle added to G8 on 2026-08-08 cost about 20s (it was ~40s with
three jars) — that is most of the current headroom, spent deliberately: three
jars was what let 2,270 corpus files leave the denominator unnoticed.

The cheapest cut available, if the ceiling is ever breached, is gate 5: it is
the SAME sweep as gate 4 against a second backend, it does not write the
scoreboard, and it is portability coverage rather than correctness. It is kept
on every run by explicit decision (2026-08-08), not by inertia.

---

| # | Gate | Command (from repo root) | Expectation |
|---|------|--------------------------|-------------|
| 1 | Core suite | `mvn -pl core **clean** test` | 0 failures. **`clean` is load-bearing** — NullAway runs only on `default-compile`, so a warm `target/` silently no-ops the null gate. |
| 2 | Core install | `mvn -pl core install -DskipTests` | — (required before 3–8) |
| 3 | Engine suite (corpus excluded — gate 4 owns it) | `mvn -pl engine test '-Dtest=!RelationalCorpusRunner'` | 0 failures (~21s). Note `engine/pom.xml` excludes the `heavy` group, so this is the default suite, not everything. |
| 4 | DuckDB corpus sweep | `mvn -pl engine test -Dtest=RelationalCorpusRunner -Dlegend.engine.root=<engine checkout>` | scoreboard vs `docs/RELATIONAL_CORPUS.md`; `M1_VERIFIED` floor (~115s) |
| 5 | h2 corpus sweep | `mvn -pl engine test -Dtest=RelationalCorpusRunner -Drcorpus.backend=h2 -Dlegend.engine.root=<engine checkout>` | portability sweep; scoreboard not written (~45s) |
| 6 | PCT full (DuckDB) | `cd pct && mvn -o test` | 1,109 run, 0 failures, 36 ledgered expected failures, nothing skipped (~30–80s) |
| 7 | PCT h2modern Relation guard | `cd pct && LEGENDLITE_PCT_BACKEND=h2 mvn -o test -Dtest=Test_LegendLite_RelationFunctions_PCT -Dh2.version=2.4.240` | see the warning below (~25s) |
| 8 | Parser equivalence | `mvn -pl parser-equivalence **-am** clean test -Dtest='CorpusSweepTest,RejectionParityTest,SectionParseSentinelTest,FixtureAdjudicationTest,EngineSectionRosterTest,EngineElementRosterTest,ViewFilterParityTest,ComparatorSelfTest,QuotedImportParityTest,CorpusManifestTest,OffsetCompositionParityTest' -Dsurefire.failIfNoSpecifiedTests=false -Dlegend.engine.root=<engine checkout> -Dlegend.pure.root=<legend-pure checkout>` — the authority is `tools/allgates.sh` (this row is a mirror) | the ratchets below (~60s) |

> **Gate 7 is one-directional and goes RED on improvement.** `allgates.sh:53`
> judges it with `grep -qE "Tests run: 348, Failures: 1, Errors: 22"` — a
> literal string. **Fixing any one of those 22 errors turns the gate red.**
> Fix the script before fixing the tests.

### Live ratchet constants (the authority is the SOURCE — this table is regenerated, not trusted)

Regenerated 2026-08-12 (the previous table was 100% dead: every row cited a
class deleted in the 08-12 sweep consolidation — deep-audit §6).

| Constant | Value | Source |
|---|---:|---|
| `MIN_PINS` | 424 | `RejectionParityTest.java` |
| `MIN_LINE_AGREEMENT` | 417 of 423 | `RejectionParityTest.java` |
| `MIN_COLUMN_EXACT` | 337 | `RejectionParityTest.java` |
| `MIN_DOCS_MATCHED` | 6489 (100%) | `CorpusSweepTest.java` |
| `MAX_SEAM_LENIENT_ACCEPTS` | 22 | `CorpusSweepTest.java` |
| `MAX_ENGINE_JSON_ASYMMETRY` | 9 | `CorpusSweepTest.java` |
| `MAX_PARSER_LENIENT_ACCEPTS` | 181 | `CorpusSweepTest.java` |
| `MIN_BEHAVIOUR_MATCHED` | 2093 | `SectionParseSentinelTest.java` |
| `MAX_DROP_IN_DEFECTS` | 0 | `SectionParseSentinelTest.java` |
| `MAX_LENIENT` | 17 | `SectionParseSentinelTest.java` |
| `MAX_UNJUSTIFIED_LENIENCY` | 0 | `SectionParseSentinelTest.java` |

> This table is re-checked against source whenever a floor moves (deep audit
> #2 found it wrong in 6 of 12 rows — the SOURCE constants are authority,
> this table is a courtesy). `SurfaceCensusTest` and `MessageParityTest` are
> gate-8 members since 2026-08-14; `AdversarialParityTest`'s class filter in
> `tools/allgates.sh` is the authoritative list, not the one quoted above.
| `MAX_LENIENCY_KINDS` | 21 | `FixtureAdjudicationTest.java` (distinct kinds, not fixtures) |
| `MAX_OVER_STRICTNESS` | 6 | `FixtureAdjudicationTest.java` |
| `MIN_SECTIONS` | 25 | `EngineSectionRosterTest.java` — DENOMINATOR: sections engine can parse |
| `MIN_ELEMENTS` | 41 | `EngineElementRosterTest.java` — DENOMINATOR: element types engine can produce |

Deleted classes previously cited here (`CorpusEquivalenceTest`,
`SpiSeamProofTest`, `PmcdEquivalenceTest`, `StrictDialectParityTest`,
`LeniencyCatalogTest`, `MappingEquivalenceTest`) are consolidated into
`CorpusSweepTest`; when a row and its source disagree, fix THIS table.

> **`FixtureAdjudicationTest` is the only tier pointed at OUR OWN fixtures.**
> Every other tier reads legend-engine's and legend-pure's files, and a
> corpus sweep structurally cannot find a disagreement about a form the
> corpus never contains — which is how three leniencies survived for months
> pinned by our own tests. It costs ~1s. Its two ratchets are debt ceilings,
> not targets, and its Javadoc clusters the 268 by the reference parser's own
> message so the list is actionable rather than a number.

Corpus ledger (`docs/RELATIONAL_CORPUS.md`, regenerated by gate 4):
**2,575 run / 2,318 pass**, of 2,798 total `<<test.Test>>` functions.
`docs/RELATIONAL_CORPUS_ALL.md` is the same sweep in 100% mode
(`-Drcorpus.includeExcluded`): 2,798 / 2,398.

> **`MAX_LENIENT_ACCEPTS` bounds the SPI bridge, not the parser.** The bridge
> is a site scanner that ignores tokens it does not recognise, so this number
> can be lowered by adding a scan guard rather than fixing a defect — which is
> how 182 → 170 happened. `MAX_PARSER_LENIENT_ACCEPTS` (742) is the honest
> parser-side figure. Lower that one.

---

`tools/allgates.sh` runs the whole chain (env: `LEGEND_ENGINE_ROOT`,
`LEGEND_PURE_ROOT`, optional `MVN_SETTINGS`; log at `$GATES_LOG`, default
`/tmp/gates.log`). It omits `clean` on gate 1 and `-am` on gate 8 — both
worth fixing.

**`tools/diagnostics.sh` — the measurement battery, OUT of the chain**
(user ruling 2026-08-26, reviving the 08-14 "triggered, not scheduled"
cadence): the parse-speed benchmark + six census/sizing classes (five
assertless printers; GrammarCoverage's ratchets bind PINNED inputs — a
constant between pin changes). Run it on its three triggers — corpus
manifest change, oracle-pin bump, parser/protocol/census-code change —
never per chain. It carries its own rename-goes-red roster, so the
"every class in some roster" discipline holds across both scripts.

**When each gate is required:** 1–5 whenever core is touched; 6–7 additionally
whenever a dialect (H2/H2Modern/shared renderer) or the lowering changes; 8
whenever the lexer, parser, protocol or emitter changes, and after any upstream
checkout pull.

Budget: the WHOLE chain measured END-TO-END at 284s (2026-08-03, machine held
awake): build+install 4s, core 8s, engine 22s, DuckDB corpus 110s (seed 47s +
h2-mirror 21s), h2 corpus 43s, PCT full 73s, PCT h2modern 25s.

**THE one failure mode that matters:** any gate showing ~900s wall with
near-zero CPU means THE MACHINE SLEPT mid-run (pmset log: 900–946s Maintenance
Sleep cycles with 45s DarkWakes; this box sleeps after 1 idle minute). Run long
chains under `caffeinate` (plain `-i` is NOT enough if the machine is already
in its sleep cycle) or `sudo pmset -a sleep 0` for the session — and re-run
before diagnosing any ~900s outlier. `mvn -o` on pct stays as hygiene (skips
remote metadata checks) but was NOT the cause of the historic 10–16 min runs;
those were sleep.

Scoped corpus runs (`-Drcorpus.only=…`) never write the scoreboard and their
universe differs from the full sweep — they are probes, not gates.

**After ANY upstream checkout pull, run gate 8's `SectionParseSentinelTest`
FIRST** (~1s). It parses every corpus file containing
`###Mapping`/`###Relational`/`###Connection`/`###Runtime` sections through the
real pipeline entry and fails if the parsing count drops — the named-failure
version of the 2026-08-04 `~src` pull that silently collapsed gate 4 to
2/2567. A new message bucket in `target/section-sentinel-report.txt` IS the
drift.
