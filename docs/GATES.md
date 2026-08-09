# Standing gates — every change cycle runs ALL of these, sequentially

Established 2026-08-02 after the engine-suite audit: 23 tests had been
failing for months because only the corpus runner was gated. The FULL
engine suite is the acceptance scoreboard for core — a runner-only
cycle is not a gate.

**Numbers below are refreshed 2026-08-06.** Prefer regenerating a report to
quoting one; the ratchet constants in the test sources are the authority, and
they move.

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

## The time budget: 5.5 minutes, locked

Measured 2026-08-08, sequentially, nothing concurrent:

| # | gate | time |
|---|------|------|
| 1 | core suite (clean, 1,658 tests) | 13s |
| 2 | core install | 1s |
| 3 | engine suite (2,729 tests) | 21s |
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
| 8 | Parser equivalence | `mvn -pl parser-equivalence **-am** test -Dtest='CorpusEquivalenceTest,RejectionParityTest,SpiSeamProofTest,SectionParseSentinelTest,FixtureAdjudicationTest,EngineSectionRosterTest,EngineElementRosterTest' -Dsurefire.failIfNoSpecifiedTests=false -Dlegend.engine.root=<engine checkout> -Dlegend.pure.root=<legend-pure checkout>` | all six ratchets below (~90s) |

> **Gate 7 is one-directional and goes RED on improvement.** `allgates.sh:53`
> judges it with `grep -qE "Tests run: 348, Failures: 1, Errors: 22"` — a
> literal string. **Fixing any one of those 22 errors turns the gate red.**
> Fix the script before fixing the tests.

### Live ratchet constants (the authority — read them, do not trust this table)

| Constant | Value | Source |
|---|---:|---|
| `MIN_ELEMENTS_COMPARED`, `MIN_MATCHES` | 22,725 | `CorpusEquivalenceTest.java:45-46` |
| `MIN_PINS` / `MIN_LINE_AGREEMENT` / `MIN_COLUMN_EXACT` | 43 / 40 / 28 | `RejectionParityTest.java:180,154,150` |
| `MIN_FILES_MATCHED` | 4,051 | `SpiSeamProofTest.java:165` |
| `MAX_LENIENT_ACCEPTS` | 170 | `SpiSeamProofTest.java:175` |
| `MAX_PARSER_LENIENT_ACCEPTS` | 742 | `SpiSeamProofTest.java:183` |
| `MAX_ENGINE_JSON_ASYMMETRY` | 8 | `SpiSeamProofTest.java:189` |
| `MIN_FILES_PARSED` | 877 | `SectionParseSentinelTest.java:123` |
| `MAX_DROP_IN_DEFECTS` | 184 | `SectionParseSentinelTest.java` (was recorded here as 126 while the source said 266 — the source is the authority) |
| `MAX_LENIENCY_KINDS` | 21 | `FixtureAdjudicationTest.java` (distinct kinds, not fixtures) |
| `MAX_OVER_STRICTNESS` | 6 | `FixtureAdjudicationTest.java:92` |
| `MIN_SECTIONS` | 25 | `EngineSectionRosterTest.java` — DENOMINATOR: 21 extension + 4 core sections engine can parse |
| `MIN_ELEMENTS` | 41 | `EngineElementRosterTest.java` — DENOMINATOR: packageable element types engine can produce |

> **`FixtureAdjudicationTest` is the only tier pointed at OUR OWN fixtures.**
> Every other tier reads legend-engine's and legend-pure's files, and a
> corpus sweep structurally cannot find a disagreement about a form the
> corpus never contains — which is how three leniencies survived for months
> pinned by our own tests. It costs ~1s. Its two ratchets are debt ceilings,
> not targets, and its Javadoc clusters the 268 by the reference parser's own
> message so the list is actionable rather than a number.

Corpus ledger (`docs/RELATIONAL_CORPUS.md`, regenerated by gate 4):
**2,575 run / 2,298 pass**, of 2,798 total `<<test.Test>>` functions.
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
