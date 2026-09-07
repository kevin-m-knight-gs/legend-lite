# The referee judges in the database — item 4 of the harness endgame (2026-09-07)

User question (2026-09-06): "does H2Verify exist because we are comparing raw database
return values instead of Pure-interpreted values after they come back?" Yes. This
document records the measurement, the design, and what it deletes.

## What the referee does today (measured, batch 118, one DuckDB lane run)

| Entry | MATCH | DIVERGED | DECLINED |
|---|---|---|---|
| `verify` (a golden SQL vs our frame) | 1,591 | 6 | 21 |
| `verifyFetchChain` (TDG chained fetch) | 49 | | |
| `verifyFetchTexts` (TDG fetch text) | 23 | | |
| `verifyPlan` (a golden plan program) | 28 | | 4 |

Every row verdict is produced the same way (`H2Verify.goldenRowsCompare`): the golden
SQL runs on the H2 mirror and its rows come back as JDBC objects; our side arrives as an
`ExecutionResult.Tabular` — rows already pulled out of DuckDB into Java; BOTH sides go
through `H2Verify.norm(Object)` (a Java text normalization of JDBC values), enum cells
through a Java decode map, temporals through `coerceTemporal`, then either a sorted
string-list equality (multiset) or a keyed positional verdict (`sortKeyIndexes`,
`orderedVerdict`, `ordFallback` leniency). `norm` is the SECOND interpretation of every
value — the one the platform's own equality (the database, under Pure's rules) never
sees. 1,256 lines of H2Verify are that policy.

## The design: one database, one equality

1. **The golden's rows become a relation in the session.** The referee still runs the
   golden on the H2 mirror (seeds, extension functions, the oracle's dialect — all as
   today), but instead of normalizing its rows in Java it TRANSFERS them into the DuckDB
   session as a typed temporary table: column types from the H2 `ResultSetMetaData`
   (`java.sql.Types` → DuckDB types: one mapping, in one place), values through
   `DuckDBAppender` by JDBC type (`append(long/double/BigDecimal/LocalDate/
   LocalDateTime/String)`, `appendNull`). Nothing is spelled as text. This is the
   batch-112 precedent (plan allocations materialized as oracle tables) applied to the
   verdict itself.
2. **Our side stays SQL.** The verdict arm already holds our typed read; the executor
   renders it in the SESSION dialect (`StatementExecutor.renderValue`, the evalValue
   pipeline minus execution). Our rows are never pulled into Java for the verdict.
3. **The verdict is one query, in the database:**
   `SELECT count(*) FROM ((SELECT * FROM golden EXCEPT ALL SELECT * FROM (ours)) UNION ALL
   (SELECT * FROM (ours) EXCEPT ALL SELECT * FROM golden))` — zero rows ⇔ the two
   multisets are equal under the database's equality. Column arity mismatch → DECLINED
   (the same bucket as today). Enum-coded golden columns decode by a JOIN to the
   enumeration mapping's pairs (model metadata, a VALUES relation) — not a Java map over
   cells. An ordered golden (ORDER BY) keeps today's explicit leniency: the multiset
   verdict plus a counted `ord-multiset` bucket (the keyed positional rule is a follow-up
   as SQL over `row_number()`).
4. **Numeric tolerance is not silently kept.** Today's 2-ulp Double tolerance
   (`PureAsserts.equalScalar`) is a Java rule; `EXCEPT ALL` is exact. The known class is
   the 21 calendarAggregations float-print rows: they either agree exactly in the
   database or become attributed `revisit:` rows. No hidden leniency.
5. **Graph (JSON) results are out of scope** for this item: `goldenGraphCompare` is a
   tree compare of a serialized document, not a row verdict; it stays until the JSON
   verdicts move to the database's own JSON equality.

## What is deleted

`H2Verify`: `norm`, `coerceTemporal`, `goldenRowsCompare`, `multisetCompare`,
`orderedVerdict`, `sortKeyIndexes`, `keyTuple`, `ordFallback`, `divergence`, `diffRows`,
`firstDiff`, `rawRows`, `transcriptRows`, `nameOrder`, `enumPrecheck`, `carrierList`,
`instantInSelectList` — the comparison policy (~900 lines). The decline/bucket plumbing
stays until the censuses go (item 5). `ReplayOracle` keeps: seeds, the mirror, plan
replay, allocations, extension functions — a translator, never a judge.

## Acceptance

The DuckDB lane's 121-test fail roster unchanged; the referee's outcomes: 1,591 MATCH
stay MATCH except rows attributed to the tolerance class (each named), 6 DIVERGED stay,
21 DECLINED stay or shrink; the H2 lane unchanged (no mirror there). Chain green.

## Grounding

- `DuckDBAppender` in the shipped driver (`org.duckdb.DuckDBAppender`: `append(...)` for
  every JDBC scalar class incl. `BigDecimal`, `LocalDate`, `LocalDateTime`, `String`,
  `byte[]`; `appendNull`; `DuckDBConnection.createAppender(schema, table)`) — verified
  by `javap` on the resolved jar.
- The referee receives the SESSION connection (`SqlReplayOracle.verify(session, …)`).
- The verdict arm holds our typed read (`rowsRead`) and the environment; rendering
  without executing is the executor's existing pipeline.

## Implementation plan (2026-09-07, after the H2Verify census; batches 123–125)

Census facts that shape the plan (measured by reading, not sampling):
- `H2Verify.ORDERED_QUERY` is never set anywhere → `sortKeyIndexes` / `orderedVerdict` / `ordFallback` are
  DEAD: today's referee already judges an ordered golden as a multiset. The database verdict keeps that,
  counted (`ord-multiset`), the positional rule stays a follow-up.
- `bookkeepingAlias` (pk_N, u_type, from_z/thru_z, k_businessDate) and the `EXTENT_SUBSET` pk-collapse
  are used ONLY by `goldenGraphCompare` (graph results) — out of scope, they stay with it.
- `enumPrecheck` (our column is an enum with no mapping decode → decline) needs OUR column Pure types
  without executing — the prepared plan's typed columns (`Executor` derives them from the plan's outputs
  and the root's declared schema; the same derivation, before the run).
- The referee's H2-session fast path (`verifyOnSession`, gate 5's lane): the golden and our query are on
  ONE database — no transfer; the golden SQL is the subquery directly.
- Referee outcome baseline (main, batch 122): verify 1591–1592 MATCH / 6 DIVERGED / 20–21 DECLINED (the
  paginate sort-tie decline flips run to run); fetch-chain 49; fetch-texts 23; plan 28 / 4.

**Batch 123 — the executor hands back our SQL without running it (no behaviour change).**
`StatementExecutor.executeTyped` splits into `prepareTyped(body, env, rider, identityLane)` returning a
sealed `Prepared` = `Planned(SqlQuery plan, TypedSpec root, ExprType declaredInfo, ResultShape shape,
List<Column> columns, ExecEnv env)` | `Answered(ExecutionResult)` (every pre-plan arm: EFFECT natives,
StoreNav host evaluation, DDL strings, orchestration handles, the effectful map, the literal fold), and
`executeTyped` = prepare → Answered ? result : executePlan. `renderValue(rowsRead, letPrefix, specs,
env, hook)` = evalValue's pipeline (inline, stage, resolve) → prepare → `Rendered(sql, columns, shape,
connection)` or null for an Answered read (the verdict declines it by name). Acceptance: rosters exact,
chain green, no new Java evaluation (JavaEvalLedger pins hold).

**Batch 124 — the verdict is one query in the database.**
- `SqlReplayOracle` gains `verifyInDb(session, goldenSql, ourSql, ourColumns, valueFrame, mappingFqn,
  rootClassFqn, extentSubset, ctx, temps)`; the default declines.
- `SqlTextVerdicts.rowsLegAndVerdict`: for a row-shaped read, `renderValue` instead of `evalValue`; the
  oracle gets our SQL. Graph results keep `evalValue` + the graph compare.
- `ReplayOracle.verifyInDb`: seeds the mirror as today (recorded SQL ledger, extra seeds, temp tables);
  enum decode pairs from `H2Verify.decodeOf` per OUR enum-typed column; declines computed without
  values: `instantInSelectList(golden)`, the PAGINATED pattern, `enumPrecheck` over our typed columns,
  the population-statement rule; then `InDbVerdict.judge(session, mirrorStatement, goldenSql, ourSql,
  enumDecode, valueFrame)` (docs/parked/InDbVerdict.java → core/src/test/java/com/legend/harness,
  registered with JdbcSurfaceCensusTest as the ONE transfer surface). H2 session: judge with the golden
  SQL as a subquery (no transfer).
- `InDbVerdict` as parked, plus: the H2-session branch; DuckDB column types from H2 metadata (the one
  mapping, `duckType`); values by JDBC type (`appendTyped`); enum decode by LEFT JOIN to a VALUES
  relation; value frames drop the single all-null row on both sides; verdict = count of two-way EXCEPT
  ALL; samples for the message only.
- Acceptance: outcomes 1591–1592 / 6 / 20–21 with EVERY change named (the Java two-ulp float tolerance is
  gone — rows that matched only under it become `revisit:` rows); roster 2454; H2 lane 1866; chain green.

**Batch 125 — the row-compare policy deleted.** `goldenRowsCompare`, `norm`, `multisetCompare`,
`orderedVerdict`, `sortKeyIndexes`, `keyTuple`, `ordFallback`, `divergence`, `diffRows`, `firstDiff`,
`rawRows`, `transcriptRows`, `nameOrder`, `carrierList`, `coerceTemporal`, `enumPrecheck` (replaced),
`ORDERED_QUERY`, `EXTENT_SUBSET` if graph-only usage allows a parameter (~900 of 1,256 lines). STAYS:
`goldenGraphCompare` + `bookkeepingAlias` (graph leg later), `decodeOf` (model metadata → VALUES),
`instantInSelectList` (a decline rule), the mirror machinery in `ReplayOracle`, the decline funnel and
roster display (item 5).
