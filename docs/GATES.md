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

Budget: the WHOLE chain is ~5-6 minutes (core 8s + engine 21s +
DuckDB corpus 115s + h2 corpus 45s + PCT full 30-80s + PCT h2modern
25s, sequential; measured 2026-08-02). Two failure modes to know:
(1) PCT gates run OFFLINE (`mvn -o`) — the pct module carries 9
legend-engine dependencies and an online run re-checks remote
metadata for them: measured 26s offline vs 10-16 MINUTES online,
identical results. (2) INTERMITTENT stall: after many back-to-back
JVM-heavy runs this machine can block a single PCT test (observed:
timeBucket, 854s wall / ~0 cpu — memory pressure, same constraint
as the no-parallel-gates rule); the identical suite re-runs in
seconds — re-run before diagnosing. Gates 6-7 are required whenever
core is touched; 6–7 whenever a
dialect (H2/H2Modern/shared renderer) or the lowering changes.

Scoped corpus runs (`-Drcorpus.only=…`) never write the scoreboard
and their universe differs from the full sweep — they are probes,
not gates.
