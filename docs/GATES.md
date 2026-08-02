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
| 3 | FULL engine suite | `mvn -pl engine test` | 2721, 0 failures (includes RelationalCorpusRunner: DuckDB corpus 2180 EXACT + M1 h2-exec 296 floor / 0 divergences) |
| 4 | h2 corpus sweep | `mvn -pl engine test -Dtest=RelationalCorpusRunner -Drcorpus.backend=h2` | ≥ 2148/2538 (portability sweep; scoreboard not written) |
| 5 | PCT full (DuckDB) | `cd pct && mvn -o test` | green (1 ledgered Relation expected-failure) |
| 6 | PCT h2 Relation guard | `cd pct && LEGENDLITE_PCT_BACKEND=h2 mvn -o test -Dtest=Test_LegendLite_RelationFunctions_PCT` | 313/348 |
| 7 | PCT h2modern guard | as #6 plus `-Dh2.version=2.4.240` | 325/348 |

PCT gates run OFFLINE (`mvn -o`): the pct module carries 9
legend-engine dependencies and an online run re-checks remote
metadata for them — measured 26s offline vs 10–16 MINUTES online
(2026-08-02; identical results). Gates 5–7 are required whenever
core is touched; 6–7 whenever a
dialect (H2/H2Modern/shared renderer) or the lowering changes.

Scoped corpus runs (`-Drcorpus.only=…`) never write the scoreboard
and their universe differs from the full sweep — they are probes,
not gates.
