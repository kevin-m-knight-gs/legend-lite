# Standing gates — every change cycle runs ALL of these, sequentially

Established 2026-08-02 after the engine-suite audit: 23 tests had been
failing for months because only the corpus runner was gated. The FULL
engine suite is the acceptance scoreboard for core — a runner-only
cycle is not a gate.

Sequential (never parallel — concurrent heavy JVMs get killed on this
machine). Core must be INSTALLED before any engine-module run (the
engine builds against core's installed jar; a stale jar silently
A/Bs old code).

| # | Gate | Command (from repo root) | Expectation |
|---|------|--------------------------|-------------|
| 1 | Core suite | `mvn -pl core test` | 1595, 0 failures (NullAway, ArchUnit, code-shape included) |
| 2 | Core install | `mvn -pl core install -DskipTests` | — |
| 3 | Engine suite (corpus excluded — gate 4 owns it) | `mvn -pl engine test '-Dtest=!RelationalCorpusRunner'` | 0 failures (~21s) |
| 4 | DuckDB corpus sweep | `mvn -pl engine test -Dtest=RelationalCorpusRunner` | 2180 EXACT + M1 h2-exec 296 floor / 0 divergences (~115s) |
| 5 | h2 corpus sweep | `mvn -pl engine test -Dtest=RelationalCorpusRunner -Drcorpus.backend=h2` | ≥ 2148/2538 (portability sweep; scoreboard not written) (~45s) |
| 6 | PCT full (DuckDB) | `cd pct && mvn -o test` | green (1 ledgered Relation expected-failure) (~30-80s) |
| 7 | PCT h2modern Relation guard | `cd pct && LEGENDLITE_PCT_BACKEND=h2 mvn -o test -Dtest=Test_LegendLite_RelationFunctions_PCT -Dh2.version=2.4.240` | 325/348 (~25s) |
| 8 | Parser equivalence (all three dimensions) | `mvn -pl parser-equivalence test -Dtest='CorpusEquivalenceTest,RejectionParityTest,SpiSeamProofTest' -Dsurefire.failIfNoSpecifiedTests=false -Dlegend.engine.root=<engine checkout> -Dlegend.pure.root=<legend-pure checkout>` | 19,260/19,260 byte-equal, 39/39 rejection pins, SPI seam 4,002 matched / 0 DIFF / 0 SPI-REJECTS (~90s) |

`tools/allgates.sh` runs the whole chain (env: `LEGEND_ENGINE_ROOT`,
`LEGEND_PURE_ROOT`, optional `MVN_SETTINGS`; log at `$GATES_LOG`,
default `/tmp/gates.log`). Gate 8 needs the two upstream checkouts on
disk — without them the harness tests skip via `Assumptions`, which is
NOT a pass of gate 8.

Budget: the WHOLE chain measured END-TO-END at 284s (2026-08-03,
machine held awake): build+install 4s, core 8s, engine 22s, DuckDB
corpus 110s (seed 47s + h2-mirror 21s), h2 corpus 43s, PCT full 73s,
PCT h2modern 25s. THE one failure mode that matters: any gate
showing ~900s wall with near-zero CPU means THE MACHINE SLEPT
mid-run (pmset log: 900-946s Maintenance Sleep cycles with 45s
DarkWakes; this box sleeps after 1 idle minute). Run long chains
under `caffeinate` (plain `-i` is NOT enough if the machine is
already in its sleep cycle) or `sudo pmset -a sleep 0` for the
session — and re-run before diagnosing any ~900s outlier. `mvn -o`
on pct stays as hygiene (skips remote metadata checks) but was NOT
the cause of the historic 10-16 min runs; those were sleep. Gates
6-7 are required whenever core is touched; 6–7 whenever a
dialect (H2/H2Modern/shared renderer) or the lowering changes.

Scoped corpus runs (`-Drcorpus.only=…`) never write the scoreboard
and their universe differs from the full sweep — they are probes,
not gates.
