# V7 — corpus asserts → SQL verdicts: phase-0 census (2026-08-28)

The measured opening for the V7 charter (PROGRAM_MAP longer-arc item
3, first slice; standing ruling 2026-08-24: one leg, no incremental
drift, only after PCT is 100% — PCT delivered 2026-08-28). The two
fears this census quantifies: CORRECTNESS (moving a trusted referee)
and PERFORMANCE (round trips vs Java host compare).

## 1. Performance — the numbers say the fear is misplaced

Full-sweep timing ledger (G4, 2026-08-28, 2,575 tests):

```
jvm.wall     132.1s
seed.replay   87.3s   n=2,434   <- dominates
engine.exec   36.9s   n=2,355   <- the ENGINE baseline (not ours)
query.exec     6.5s   n=24,529  <- 0.26 ms PER QUERY (in-mem DuckDB)
test.wall    131.8s   n=2,575
```

- Our query execution is 5% of the sweep. At 0.26 ms/query, adding
  one verdict query per data assert (~1,880 sites, §2) costs ~0.5 s.
- The likely shape is BETTER than that: today both assert sides
  execute as SQL and the FULL RESULT SETS cross JDBC for the Java
  compare; a verdict query fuses the sides and returns ONE row. Wire
  bytes shrink; round trips do not multiply.
- The real perf unknown is VERDICT TEXT SIZE (golden rows inlined as
  VALUES). Charter measurement: the golden result-size distribution;
  a per-size parse-cost probe. (V12's one-round-trip UNION ALL shape
  is available if any family needs it early.)

## 2. Scope — the assert-form census (core_relational tests + graphFetch)

| form | sites | note |
|---|---|---|
| assertEquals (DATA) | 1,067 | the main body |
| assertSameElements | 407 | ORDER-INSENSITIVE — verdict needs sorted-both-sides form |
| assertSize | 185 | trivial verdicts (COUNT) |
| assertJsonStringsEqual | 167 | graph lane — JsonAssertCanon territory |
| assert() | 32 | boolean verdicts (the PCT K-arm shape, exists) |
| tail (assertEq/Empty/NotEmpty/Tds/Tolerance/Is/InstanceOf) | ~25 | per-form adjudication |
| **assertEquals over sqlRemoveFormatting** | **341** | **PLAN-TEXT compare — HOST BY DESIGN, out of scope** |
| **assertSameSQL** | **229** | **same — out of scope** |

**V7 surface = ~1,880 data-assert sites; 570 sql-text sites (23%)
stay host-side by design** — they compare the PLAN, not data, and
must be partitioned out in the charter so the leg's claim is honest.

## 3. What moves (the third implementation, named)

Today (`harness/EngineTestExecutor`): both assert sides execute
through the pipeline; JAVA compares wire values — the compare lattice
is `goldenEqualScalar` + the golden temporal-decode arms (the file's
own comment: "these arms delete wholesale with the R2 render
cutover"), `JsonAssertCanon`, `GridCompare`/grid conventions (TDSNull
sentinel, engine text-compare), `H2Verify`'s replay checks. V7 = this
lattice re-expressed as verdict-query construction; the render
conventions move INTO the verdict SQL with an explicit row_number
order key (PROGRAM_MAP item 3's recorded acceptance).

## 4. The correctness safety design (the head start is already wired)

The corpus runner ALREADY prints `sql-verdict agree/disagree/declined`
counters beside its live canon channel (currently all zero — the
plumbing exists, unexercised). V7's shape is therefore the proven PCT
playbook: DUAL-REFEREE inside the one leg — host verdict stays the
verdict of record, the SQL verdict runs beside it, disagreements are
a loud census burned to zero, THEN the host arms delete (cut over
hard; the dual phase is a referee, never a mode). "No half-migrated
referee" constrains the CUTOVER to one leg, not the measurement.

## 5. Remaining charter homework (before any code)

1. Golden result-size distribution → verdict VALUES-literal cost.
2. Softness re-expression: the 937 soft-pass flags (text-rescued 614,
   advisory 304, sqldiff 258) are properties of the COMPARISON — each
   flag family needs its verdict-side expression or an explicit
   host-side residue ruling.
3. Per-form verdict shapes: assertSameElements' order-insensitivity;
   assertJsonStringsEqual vs the byte-canon channel; tolerance forms.
4. The golden channel's SOURCE: expected sides come from ENGINE
   baseline execution (engine.exec) — how golden rows enter the
   verdict (inline VALUES vs temp table) is a design decision with a
   measurement attached.
5. Sequencing confirmed 2026-08-28: V7 first (byte-identical queries,
   referee-only change — perfect attribution), THEN V13 (let IS WITH;
   fusion perturbs golden text and reuses V7's verdict semantics
   wholesale — only the thin per-assert orchestration is superseded).
   The indexOf/substring seam waits behind V13
   (INDEXOF_SUBSTRING_LANE_CENSUS.md §7).
