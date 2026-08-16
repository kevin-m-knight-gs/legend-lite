# Bucket 6 — Wrong rows / wrong value

37 tests from the ledger; **33 still non-passing** at `9d1f2cd0`. 4 now pass (fixed upstream since the 2026-08-14 sweep) and are marked below.

Each entry was produced by an agent that read the test's `.pure` body, grepped the failure message back to the emitting legend-lite code, read that method's control flow, and checked semantics against real legend-engine / legend-pure sources. Citations were mechanically verified to resolve.

Verdicts: REAL DEFECT 18, EXECUTION-TARGET ARTIFACT 10, TESTS ENGINE INTERNALS 3, HARNESS GAP 2, NEEDS PROBE 2, MISSING FEATURE 1, GOLDEN TEXT ONLY 1

---

## `testConcatenateFlatWithOtherProperty`

| | |
|---|---|
| family | `functions/tests` |
| sweep status | FAIL |
| **verdict** | **REAL DEFECT** |
| effort | L |
| confidence | medium |

**Root cause**

A `[*]`-valued scalar projection column is materialized as a LIST CELL and the row explosion is performed HOST-SIDE at result shaping, instead of being a row explosion in SQL as the engine does. `ProjectChecker.clampTdsCells` (ProjectChecker.java:70-94) correctly clamps the column's schema multiplicity to [0..1] with the comment 'a [*]-valued projection column EXPLODES into one row per value', but the lowering does no such thing: `tryComputedColumns` (Lowerer.java:1178-1198) just emits the scalar expression, which for `$t.id->concatenate($t.id+18)` is `list_concat([ID],[ID+18])` (Scalars.java:1635-1658). The multiplication of rows then happens only in `Executor.shapeRow` (Executor.java:572-619), which turns a List/java.sql.Array cell into N rows. Because the explosion is a property of the RESULT rather than of the RELATION, it vanishes the moment anything re-plans the relation with a different column set. That is exactly what this test does: `$result.values.rows.getInteger('simple')` compiles a fresh query projecting only 'simple' (the getter desugar at Typer.java:317-337 turns it into a column read on the relation), the list-valued 'Concatenated' column is never selected, no cell is a List, shapeRow returns one row per DB row, and 'simple' comes back as [1,2] instead of [1,1,2,2]. The sibling single-column test testConcatenateFlat passes because it reads the whole grid (`rows.values`), which does keep the list column and therefore does explode.

**Fix**

Move the explosion into SQL. In Lowerer.tryComputedColumns (Lowerer.java:1178-1198), detect a projection column whose LAMBDA result multiplicity is many — read it from the column's function type, `((Type.FunctionType) c.fn().info().type()).result().multiplicity().isMany()`, because ProjectChecker.clampTdsCells has already erased it from the relation schema — and, instead of projecting the list expression, attach `LEFT JOIN LATERAL UNNEST(<listExpr>) AS <alias>(<colName>)` to the base select and project `<alias>.<colName>`. LEFT (not inner) so a parent with an empty stream keeps one row with a NULL cell, exactly as Executor.shapeRow does today and as the comment at Lowerer.java:451-457 already documents for the instance-literal path. Reuse that same construct. Keep Executor.shapeRow's host explosion as a defensive fallback but it becomes dead for this shape. Do NOT change ProjectChecker.clampTdsCells — the [0..1] schema clamp is correct once the rows really are exploded. Enforce the existing 'one many-valued column per row' rule at lowering time (a second such column should be the same loud NotImplementedException that Executor.java:590-597 raises today), so the wall moves earlier rather than disappearing.

**How legend-engine does it** — /Users/neemsandv/legend/legend-engine/legend-engine-xts-relationalStore/.../core_relational/relational/functions/tests/testConcatenate.pure:190-196 — the pinned golden shows the engine's union-all subselect joined back on the root key; row count is a property of the SQL relation, not of result decoding

**Risk** — Adding a LATERAL UNNEST changes the emitted SQL for every many-valued projection column, so advisory golden-SQL diffs will move (they are advisory — EngineTestExecutor.java:1834-1848). Row COUNTS change for any query that currently under-counts, which is the point, but tests that were accidentally passing on the flattened list cell (testConcatenateFlat compares `rows.values`, which is order-sensitive row-major) must be re-checked for ORDER: the lateral unnest must emit the parent's elements adjacently and in list order, which DuckDB's UNNEST does. Tenet-2 trap: do NOT make the harness re-explode or re-flatten when it sees a list cell — the row count is the platform's contract.

**Falsifier** — Dump the SQL for `$result.values.rows.getInteger('simple')`. If it already contains an UNNEST/union that multiplies rows (i.e. 4 rows come back and the [1,2] came from somewhere else), this diagnosis is wrong and the defect is in the column-read re-plan, not in projection lowering.

<details><summary>Evidence read (8 citations)</summary>

- core/src/main/java/com/legend/exec/Executor.java:572-619 — shapeRow: 'a many-valued primitive projection column (scalar-stream concatenate) EXPLODES ROWS'; the explosion is driven by the runtime cell being a List/java.sql.Array, i.e. host-side, post-SQL
- core/src/main/java/com/legend/compiler/spec/ProjectChecker.java:70-94 — clampTdsCells clamps a many column to [0..1] and documents the explosion contract, but discards the multiplicity the lowering would need
- core/src/main/java/com/legend/lowering/Lowerer.java:1178-1198 — tryComputedColumns emits each column as a plain scalar projection; there is no arm for a many-valued column
- core/src/main/java/com/legend/lowering/Scalars.java:1635-1658 — collection `concatenate` lowers to `SqlFn.LIST_CONCAT`, i.e. a LIST-valued cell
- core/src/main/java/com/legend/compiler/spec/Typer.java:317-337 — `getInteger('simple')` desugars to a column read `AppliedProperty(<relation>, 'simple')`, which re-plans the relation projecting only that column
- /Users/neemsandv/.claude/jobs/5671074c/tmp/burndown/failing.tsv — testConcatenateFlat (the single-column sibling, testConcatenate.pure:183) is absent from the 276 failures, i.e. it passes; the only difference is that it reads the whole grid
- legend-engine .../core_relational/relational/functions/tests/testConcatenate.pure:190-196 — the golden SQL is `... from CONCATENATE.TRADE as "root" left outer join (select "trade_1".ID as "id_plus_TRADEID_18", "trade_1".ID as ID from CONCATENATE.TRADE as "trade_1" union all select "trade_1".ID + 18 ...) as "unionalias_0" on ("root".ID = "unionalias_0".ID)` — the engine explodes rows IN SQL via a UNION ALL subselect joined back to the root
- core/src/main/java/com/legend/lowering/Lowerer.java:451-457 — the codebase already has the SQL-side pattern for this ('LEFT JOIN LATERAL UNNEST ... LEFT so an empty array NULLs its column instead of killing the row') in projectOverInstances

</details>

---

## `testDupsFilterProject` — ✅ NOW PASSES (fixed upstream; diagnosis retained for the record)

| | |
|---|---|
| family | `functions/tests` |
| sweep status | FAIL |
| **verdict** | **REAL DEFECT** |
| effort | M |
| confidence | medium |

**Root cause**

`<something>.values->at(k)` over a relation is compiled as a ROW index instead of a CELL index. `Typer.accessProperty` erases `.values` on a relation-typed non-variable source to the IDENTITY (`return source;`, Typer.java:2429-2463), so by the time the `at` is lowered nothing distinguishes 'the k-th cell of a row' from 'the k-th row'. `Lowerer.relation()` then rewrites `at(rel, n)` into `TypedSlice(n, n+1)` — a ROW slice (Lowerer.java:292-310). For `$result1.rows->first().values->at(0)` the chain is: `.rows` marker -> `first()` = LIMIT 1 (Lowerer.java:425-430) -> `.values` = identity -> `at(0)` = slice(0,1) over an already-single-row relation = the same relation. The Eval then flattens that 1x2 grid to [Firm X, Yes] (EngineTestExecutor.java:2507-2520) and the assert compares 1 expected value against 2. The guard that is supposed to prevent this — `Typer.tdsRowCellIndexRead` (Typer.java:947-990), which turns `<rowPick>.values->at(k)` into `toOne($pick.<col_k>)` — demonstrably does not take effect for this spelling. The same defect, in its other spelling, produces tds/tests testFilterOnEnum: `$result.values.rows.values->at(1)` expected `CITY` (row0/col1 of the ROW-MAJOR cell stream) and got `[New York, CITY]` — literally row 1. Engine ground truth: `TDSRow.values : Any[*]` is a row's cells in column order (tds.pure:79), and `$tds.rows.values` auto-maps and flattens to one Any[*] cell stream, so `at(k)` there is cell k, never row k.

**Fix**

Keep the cell-vs-row distinction alive past the Typer instead of erasing `.values`, then implement both index semantics on it. Concretely: (1) In Typer.accessProperty (Typer.java:2429-2463), for a relation-typed non-variable source return a marker node `TypedPropertyAccess(source, "values")` — exactly symmetric to the existing `.rows` marker at Typer.java:2418-2427 — instead of `return source;`. Add the defensive erasure arm for it in Lowerer.relation()'s switch next to the ROWS_MARKER arm (Lowerer.java:495-499) so a bare `.values` with no index still behaves as the whole-grid flatten it is today. (2) Rewrite `tdsRowCellIndexRead` (Typer.java:958-990) to key off that marker rather than off `pick instanceof TypedNativeCall`+FQN, and to cover BOTH shapes: when the marker's source is a single-ROW pick (first/last/at/toOne/head, or any relation whose multiplicity is to-one), `at(k)` = `toOne($src.<columns[k].name()>)` and `size()` = column count (already implemented); when the source is a many-row relation ($tds.rows.values), `at(k)` = `toOne(slice($src, k/w, k/w+1).<columns[k%w].name()>)` with w = column count, and `size()` = rows*w. (3) Gate Lowerer.relation()'s `at(rel,n)` -> TypedSlice rewrite (Lowerer.java:298-310) so it applies ONLY when the argument is the ROWS marker / a plain relation, never a `.values` cell-stream marker — otherwise the erasure re-introduces the bug for any spelling the Typer misses. Add `"meta::pure::functions::collection::head"` to ROW_PICK_FQNS while there.

**How legend-engine does it** — /Users/neemsandv/legend/legend-engine/legend-engine-core/legend-engine-core-pure/legend-engine-pure-code-compiled-core/src/main/resources/core/pure/tds/tds.pure:76-79 (TDSRow.values : Any[*] — cells in column order; `$tds.rows.values` is the auto-mapped flattened cell stream)

**Risk** — The Result-ENVELOPE peel `$result.values->at(0)` must keep collapsing to the chain — that `.values` has a ClassType (Result<T>) receiver and is handled in StatementExecutor.aliasFrame/spliceHook (StatementExecutor.java:2385-2422, 2549-2578) before the Lowerer, so a relation-side marker must not be confused with it. The harness's whole-grid vs flat-cells distinction (`isFlatCellsRead`, EngineTestExecutor.java:2645-2649) reads the UNTYPED tree and is unaffected. Tenet-2 trap: do NOT teach the harness to interpret `->at(0)` itself — the harness deliberately compiles assert expressions through the platform, and special-casing the index there would hide the platform's cell/row confusion.

**Also unblocks** — tds/tests testFilterOnEnum (`assertEquals: expected CITY, got [New York, CITY]`) is the many-row spelling of the same defect and needs part (2) of the fix. Any other corpus assert of the form `<tds>.rows(->pick).values->at(k)` / `->size()` is in scope.

**Falsifier** — Instrument `Typer.tdsRowCellIndexRead` (or just compile `$r.rows->first().values->at(0)` and dump the SQL). If it returns non-null for this spelling and the emitted SQL already projects the single 'name' column, then the gate is fine and the defect is downstream in the `toOne(<relation column read>)` emission — a different fix site, same file family.

<details><summary>Evidence read (9 citations)</summary>

- core/src/main/java/com/legend/lowering/Lowerer.java:292-310 — 'POSITIONAL reads over a relation: at(n) IS slice(n, n+1)' — `relation(new TypedSlice(pc.args().get(0), n, n+1, ...))`
- core/src/main/java/com/legend/lowering/Lowerer.java:423-430 — `first()/head() over a RELATION: the first row — LIMIT 1`
- core/src/main/java/com/legend/compiler/spec/Typer.java:2429-2463 — `.values` on a relation VALUE returns `source` unchanged ('On a RELATION value ($tds.rows.values / ->at(0).values): identity'); only a row VARIABLE receiver gets the statically enumerated per-column TypedCollection
- core/src/main/java/com/legend/compiler/spec/Typer.java:947-990 — `tdsRowCellIndexRead`: gate is `af.function() in {at, meta::pure::functions::collection::at}` + `param0 instanceof AppliedProperty('values')` + `synth(receiver) instanceof TypedNativeCall` whose FQN is in ROW_PICK_FQNS {at, first, last, toOne}; on success it emits `toOne(<pick>.<column k>)`
- core/src/main/java/com/legend/compiler/spec/Typer.java:396 — the gate is consulted before the CoreFn dispatch at Typer.java:426, so `at` does reach it
- core/src/main/java/com/legend/harness/EngineTestExecutor.java:2507-2520 — Eval.values() over a Tabular concatenates every row's cells, which is why the actual renders as [Firm X, Yes]
- core/src/main/java/com/legend/harness/EngineTestExecutor.java:2666-2679 — evalSpliced compiles the assert expression through `Compiler.executeResolved`; the harness does NOT interpret `->at(0)` itself, so this is a platform defect, not a harness one
- /Users/neemsandv/.claude/jobs/5671074c/tmp/burndown/failing.tsv — `FAIL tds/tests testFilterOnEnum assertEquals: expected CITY, got [New York, CITY]` — the second, receiver-is-not-a-pick spelling of the same defect ($result.values.rows.values->at(1), testTDSFilter.pure:45)
- legend-engine .../core/pure/tds/tds.pure:76-79 — `Class meta::pure::tds::TDSRow { parent : TabularDataSet[0..1]; values : Any[*]; }` — values is the row's cells, so ->at(k) indexes CELLS

</details>

---

## `testInExecutionWithTempTableForDateTimesWithTz`

| | |
|---|---|
| family | `functions/tests` |
| sweep status | FAIL |
| **verdict** | **REAL DEFECT** |
| effort | M |
| confidence | high |

**Root cause**

The connection's `timeZone` is never applied when a Pure Date/DateTime literal is rendered into SQL. `MatchFold.dateLit` (MatchFold.java:75-96) converts a TypedCDate straight to `SqlExpr.DateLit`/`SqlExpr.TimestampLit` using the literal's own engine string, verbatim; there is no zone parameter anywhere on that path. The only timeZone plumbing in legend-lite is `StatementExecutor.timeZoneOf` (StatementExecutor.java:1080-1097), which walks the runtime argument for an INLINE `^...(timeZone='...')` TypedNewInstance with a literal string — and it feeds only `planDialect(dbType, quote, timeZone)`, i.e. execution-PLAN text, not query execution. This test runs `testRuntime('US/Arizona')`, whose timeZone reaches the connection through two user functions and an `if($timeZone->isEmpty(),|'GMT',|$timeZone)` fold (relationalSetUp.pure:1218-1245), so even that reader would not see a bare CString. Consequence: the in-list literals render as '2014-12-03 04:00:00' / '2014-12-04 04:00:00' / '2014-12-08 21:00:00'. The seeded tradeTable holds 2014-12-02 21:00:00 (trades 1,2,3) and 2014-12-03 21:00:00 (trades 4,5) (relationalSetUp.pure:1295-1299) — none of the three rendered literals matches anything, hence exactly 0 rows. Under the engine, `convertDateToSqlString` renders each literal in the connection zone: 2014-12-03T04:00Z in US/Arizona (UTC-7, no DST) is 2014-12-02 21:00 and 2014-12-04T04:00Z is 2014-12-03 21:00 — the 5 expected rows. The temp-table-for-IN spelling in the golden is irrelevant to this failure; it is a text-only difference.

**Fix**

Thread the connection timeZone (default 'GMT') to date-literal rendering, mirroring the engine's placement of it in the literal processor rather than in the query builder. (1) Make the zone survive user-call inlining: extend `StatementExecutor.timeZoneOf` (StatementExecutor.java:1083-1097) so it also accepts a constant-folded `if(isEmpty(x),|'GMT',|x)` binding and a TypedNewInstance reached through inlined user calls — today it only matches an inline literal, which no corpus test actually writes. (2) Carry the resolved zone on the execution context that reaches Phase I/J, alongside dbType and quoteIdentifiers, and hand it to the dialect at render time — the dialect is the right owner because this is a WIRE concern (it is where the engine puts it: LiteralProcessor.transform). (3) At render, format `SqlExpr.DateLit`/`SqlExpr.TimestampLit` by interpreting the Pure literal as a UTC instant and printing it in that zone, i.e. a Java transcription of convertDateToSqlString: `ZonedDateTime.of(<literal>, ZoneOffset.UTC).withZoneSameInstant(ZoneId.of(tz))` then the same yyyy-MM-dd[ HH:mm:ss[.SSSSSS]] shape the literal's precision demands. Keep 'GMT' as the default so every existing test is byte-identical. (4) The inverse conversion belongs on the READ side (a timestamp cell coming back from a zoned connection is DB-local and must be re-expressed as the UTC instant) — put it in the dialect's `normalize` for TIMESTAMP, not in the harness.

**How legend-engine does it** — /Users/neemsandv/legend/legend-engine/legend-engine-xts-relationalStore/.../core_relational/relational/sqlQueryToString/extensionDefaults.pure:138-157 — the DateTime LiteralProcessor calls convertDateToSqlString(date, dbTimeZone), which formats the instant with an explicit `[zone]` prefix in the format string

**Risk** — Every date/datetime literal in every generated query passes through this code, so a wrong default would move hundreds of tests at once — keep the default exactly 'GMT'/UTC identity and gate the conversion on a non-null, non-GMT zone. The read-side inverse (step 4) is the riskier half: apply it only when a zone is set, or timestamp round-trips on the ~600 default-zone tests will shift. Tenet-2 trap: do NOT let the harness pre-convert the literals before handing the query to the platform; the connection zone is a platform/dialect concern.

**Also unblocks** — tests/mapping/relation testDateTimeRetrieveWithTimeZone (`assertTdsEquivalent: cell 1 expected 2016-02-05, got 2016-02-05 21:00:00.123456789`) is in the same surface and likely needs the read-side half (step 4); I did not open that test, so treat it as a candidate, not a promise.

**Falsifier** — Dump the generated SQL for this test. If the IN list already reads '2014-12-02 21:00:00'/'2014-12-03 21:00:00' (i.e. the conversion IS happening) then the 0 rows come from the temp-table/IN spelling instead and this diagnosis is wrong.

<details><summary>Evidence read (9 citations)</summary>

- core/src/main/java/com/legend/lowering/MatchFold.java:75-96 — `dateLit(PureDateLiteral d)` returns `new SqlExpr.TimestampLit(se.toEngineString())` etc.; no timezone argument exists on this path
- core/src/main/java/com/legend/lowering/Lowerer.java:2208 — `case TypedCDate d -> MatchFold.dateLit(d.value());` is the single scalar-literal entry point
- core/src/main/java/com/legend/StatementExecutor.java:1080-1097 — `timeZoneOf` matches only a `TypedNewInstance` whose `timeZone` property is a literal `TypedCString`
- core/src/main/java/com/legend/StatementExecutor.java:593, 828, 884, 967 — the value it returns is threaded only into `planDialect(...)`, i.e. plan rendering
- core/src/main/java/com/legend/sql/dialect/SqlDialect.java:12-36 — the dialect interface has render + normalize; no zone is carried
- legend-engine .../core_relational/relational/sqlQueryToString/extensionDefaults.pure:138-140 — StrictDate/DateTime/Date literal processors are `{d| $d->convertDateToSqlString($dbTimeZone)}`
- legend-engine .../core_relational/relational/sqlQueryToString/extensionDefaults.pure:144-157 — `convertDateToSqlString`: 'Default to UTC, if timezone is not specified' then `format('%t{[' + $timeZone + ']yyyy-MM-dd HH:mm:ss}', $date)` — the literal TEXT is the UTC instant rendered in the DB zone
- legend-engine .../core_relational/relational/tests/relationalSetUp.pure:1295-1299 — trades 1,2,3 settle at '2014-12-02 21:00:00' and 4,5 at '2014-12-03 21:00:00' (5 rows; nothing at 04:00 or at 2014-12-08 21:00), which is exactly why the un-converted literals return 0
- legend-engine .../core_relational/relational/tests/relationalSetUp.pure:1218-1245 — testRuntime(timeZone:String[1]) -> testDatabaseConnection(db, $timeZone) -> `^TestDatabaseConnection(timeZone = if($timeZone->isEmpty(), |'GMT', |$timeZone))`

</details>

---

## `testSequenceMapWithConfusingSetImplementation`

| | |
|---|---|
| family | `functions/tests` |
| sweep status | FAIL |
| **verdict** | **EXECUTION-TARGET ARTIFACT** |
| effort | S |
| confidence | medium |

**Root cause**

The query has no ORDER BY — the pinned golden is `select "root".name as "name", 'ok' as "p_name", "orgtable_1".name as "p_p_name" from orgTable as "root" left outer join orgTable as "orgtable_1" on (...) where "root".filterVal <= 4` — yet the test asserts rows POSITIONALLY at indices 0..5. The corpus expectation encodes H2's incidental scan order (orgTable insertion order: ROOT, Firm X, Firm A, Securities, Banking, Federation). Our first row is ['Firm X','ok','ROOT'], which is verbatim the engine's row 1; ROOT is the ONE row whose parentId (-1) has no join partner, and DuckDB's hash join flushes NULL-extended unmatched probe rows at the END of the join, so our order is [Firm X, Firm A, Securities, Banking, Federation, ROOT]. Row COUNT (6) matched — the first assert `assertEquals(6, $result.values.rows->size())` passed — and the row content we returned is byte-identical to an expected row, so the rows are a permutation, not wrong values. The harness's documented order-policy leniency (EngineTestExecutor.java:2836-2848: an unsorted actual chain compares as a multiset) cannot apply because a `rows->at(i)` assert names one specific row.

**Fix**

Do not fix; ledger it. There is no platform-side change that is honest here: the query genuinely has no ORDER BY, Pure's relational execute makes no row-order promise, and adding a synthetic ORDER BY to make DuckDB reproduce H2's scan order would be inventing a clause the plan does not contain (and would diverge from the engine's own golden SQL, which this test also pins). The corresponding tenet-2 trap is the tempting harness fix — teaching the harness to sort or multiset-match a `rows->at(i)` assert — which would silently grant order-insensitivity to every positional row assert in the corpus, including the ones where a sort IS in the chain and order is contractual. If a bucket is wanted, file it as 'positional row assert over an unordered query, H2-order-encoded' together with the other tests of that shape, and revisit only if a deterministic-order execution mode (single-threaded, preserve_insertion_order, merge/NL join) is ever adopted wholesale — which would be a large, cross-cutting decision, not a fix for this test.

**Risk** — Classifying a genuine wrong-rows bug as an order artifact would hide it. Mitigate by running the falsifier before ledgering.

**Falsifier** — Print all six rows of our result. If the multiset equals {[ROOT,ok,null],[Firm X,ok,ROOT],[Firm A,ok,ROOT],[Securities,ok,Firm X],[Banking,ok,Firm X],[Federation,ok,Firm X]}, it is a pure permutation and this verdict holds. If any row differs in content — e.g. ROOT's p_p_name is not empty, or a row is duplicated/missing — then the mapping's `~filter` / `@OrgOrgParent` join is genuinely mis-resolved and the verdict must flip to REAL_DEFECT.

<details><summary>Evidence read (5 citations)</summary>

- legend-engine .../core_relational/relational/functions/tests/testMap.pure:319-341 — six positional `assertEquals(['...'], $result.values.rows->at(i).values)` asserts against a query whose own golden SQL has no ORDER BY
- legend-engine .../core_relational/relational/tests/mapping/filter/testFilterMappingTree.pure:131-137 — orgTable is seeded ROOT(id 1, parentId -1), Firm X(2,1), Firm A(3,1), Securities(4,2), Banking(5,2), Federation(6,2), ShouldNotBeDisplayed(7, filterVal 6) — the expected order is exactly the insertion order, and ROOT is the only parent-less row
- /Users/neemsandv/.claude/jobs/5671074c/tmp/briefs/U32.md — 'assertEquals: expected [ROOT, ok, TDSNull], got [Firm X, ok, ROOT]': our row 0 is the engine's row 1, cell-for-cell
- core/src/main/java/com/legend/harness/EngineTestExecutor.java:2836-2848 — the ORDER POLICY: multiset compare only when the actual side has no sort in its chain AND the whole collection is compared; a per-index row read defeats it
- core/src/main/java/com/legend/harness/EngineTestExecutor.java:3409-3432 — `endsInSort`/`sortedChain`: `rows`/`at` propagate sortedness but never create it, so this chain is correctly classified unsorted

</details>

---

## `testSubAggregationMultiLevel`

| | |
|---|---|
| family | `functions/tests` |
| sweep status | FAIL |
| **verdict** | **REAL DEFECT** |
| effort | XS |
| confidence | high |
| **adversarial review** | **CONFIRMED** |

**Root cause**

`average()` / `mean()` applied to a TO-ONE argument is lowered as the IDENTITY, so the result keeps the argument's INTEGER type instead of Pure's Float. In `Scalars.java:1173-1178` the rule is `isToOne(n.args().get(0)) ? args.get(0) : LIST_AVG(...)`. The query `Firm.all().employees->map(e|$e.age->average())` navigates no to-many head (`$e.age` is to-one on a Person row), so `CorrelatedSubselects.aggScan` (CorrelatedSubselects.java:1965-1993, which only routes when `toManyHead.test(cs, path.head)`) never raises an agg demand and the call falls through to the scalar rule. The emitted column is the bare AGE column; `Executor.fetch` (Executor.java:447-461) reads it with `rs.getObject` and the DuckDB dialect's `normalize` is the interface default identity (SqlDialect.java:17-20, no override in the DuckDb dialect), so 7 java.lang.Integers reach the comparator. `wireEquals` (EngineTestExecutor.java:3325-3345) deliberately refuses int-vs-float equality, so [23,22,12,...] never matches the expected [12.0,22.0,...]. The VALUES are right; only the kind is wrong.

**Fix**

In core/src/main/java/com/legend/lowering/Scalars.java:1173-1178, stop returning the identity for the to-one arm; return a FLOAT-producing expression, matching the engine's own spelling: `RULES.put(f, (n, args) -> isToOne(n.args().get(0)) ? SqlExpr.Call.of(SqlFn.TIMES, new SqlExpr.FloatLit(1.0), args.get(0)) : SqlExpr.Call.of(SqlFn.LIST_AVG, numList(args.get(0))));` (SqlFn.TIMES exists — SqlFn.java:16). `new SqlExpr.Cast(args.get(0), SqlType.Scalar.DOUBLE)` is an equally correct alternative; prefer TIMES because it reproduces the engine's `1.0 * x` text. Do NOT touch the `median` rule two lines below (Scalars.java:1180-1185): Pure's median returns Number, so its to-one identity is correct. Nothing else must change — the node's Pure type is already Float, so no typer/outputs edit is needed.

**How legend-engine does it** — /Users/neemsandv/legend/legend-engine/legend-engine-core/legend-engine-core-pure/legend-engine-pure-code-functions-standard/legend-engine-pure-functions-standard-pure/src/main/resources/core_functions_standard/math/aggregator/average.pure:22 (Float[1] return, PCT test asserts 1.0 for average([1])); dialect float coercion at legend-engine-xts-relationalStore/.../core_relational/relational/sqlDialectTranslation/toPostgresModel.pure:431

<details><summary>Adversarial review notes (CONFIRMED)</summary>

Every cited line says what is claimed. Scalars.java:1173-1177 is literally `isToOne(n.args().get(0)) ? args.get(0) : SqlExpr.Call.of(SqlFn.LIST_AVG, numList(args.get(0)))` for both 'mean' and 'average'; isToOne (Scalars.java:3423-3426) is upper-bound<=1 so Person.age (Integer[1]) takes the identity arm; Pure.java:1130 types average as Number[*]->Float[1]; CorrelatedSubselects.aggScan gates every agg lift on `toManyHead.test(...)` (lines 1984 / 2015 / 2035) and `$e.age` off a Person row binder has no to-many head; Executor.fetch is `Object o = rs.getObject(i)` with no kind coercion (Executor.java:450); SqlDialect.normalize is the default identity and only H2.java:475 overrides it. wireEquals (EngineTestExecutor.java:3325-3335) refuses Integer-vs-Double outright. The observed multiset [23,22,12,22,34,32,35] is exactly the 7 raw ages, i.e. the identity arm's output. Two extra checks that strengthen it: (1) the sibling testSubAggregation (testMap.pure:217, the same `Firm.all()->map(f|$f.employees.age->average())` query as result3 of the failing test) is NOT in the failure set, so the to-many arm already returns Doubles and result3/result2 of testSubAggregationMultiLevel should pass once the first assert does; (2) the test's golden-SQL asserts are advisory by harness policy (EngineTestExecutor.java:1834), so the fix not reproducing `group by persontable.ID` cannot fail the test. I also checked the fix's wire consequence: SqlExpr.FloatLit renders as `CAST(1.0 AS DOUBLE)` in AnsiSqlRenderer.java:310, so DuckDB yields DOUBLE (not DECIMAL) and a Double reaches wireEquals; even if it were a BigDecimal, wireEquals treats BigDecimal as FP and compares via BigDecimal.compareTo, so it would still pass. Blast radius is small: grepping the whole relational corpus for `->average()` shows every other occurrence is over a to-many collection (groupBy/window aggregates), so the to-one arm is exercised almost nowhere else.

</details>

**Risk** — Any advisory golden-SQL text that currently renders the bare column for a to-one average will now render `1.0 * col` — those asserts are advisory in this harness (EngineTestExecutor.java:1834-1848) so this is cosmetic, and the engine's own text is `avg(1.0 * ...)` anyway. Tenet-2 trap to avoid: do NOT 'fix' this by loosening `wireEquals` to bridge int-vs-float — that comparator's strictness is exactly what caught the typing bug, and loosening it would hide every future Integer/Float lowering mistake.

**Also unblocks** — No other test in the 276-test sweep fails on average/mean (grep of failing.tsv for average|mean|avg returns nothing), so this is expected to be a single-test fix.

**Falsifier** — Run the query with LEGEND_LITE_DUMP_SQL and read the projected expression. If the SQL already contains an `avg(...)`/float cast for this column (i.e. the values arrive as Double and something later narrows them to Integer), the diagnosis is wrong and the defect is in result decoding, not in Scalars.

<details><summary>Evidence read (9 citations)</summary>

- core/src/main/java/com/legend/lowering/Scalars.java:1173 — `for (String name : List.of("mean", "average")) { ... RULES.put(f, (n, args) -> isToOne(n.args().get(0)) ? args.get(0) : SqlExpr.Call.of(SqlFn.LIST_AVG, numList(args.get(0))));`
- core/src/main/java/com/legend/lowering/Scalars.java:3424 — `isToOne` is upper-bound<=1, so both [1] and [0..1] take the identity arm
- core/src/main/java/com/legend/builtin/Pure.java:1130 — legend-lite already types average as `average(Number[*]):Float[1]`, so the TYPE says Float while the SQL says INTEGER
- core/src/main/java/com/legend/harness/EngineTestExecutor.java:3325-3335 — `if (eInt || aInt) { if (!(eInt && aInt)) return false; ... }` — an Integer actual can never equal a Double expected
- core/src/main/java/com/legend/exec/Executor.java:450 — `Object o = rs.getObject(i);` (the declared SqlType does not coerce the driver's kind)
- core/src/main/java/com/legend/sql/dialect/SqlDialect.java:17-20 — `default Object normalize(...) { return jdbcValue; }` and DuckDb.java does not override it (only H2.java:475 does)
- core/src/main/java/com/legend/resolver/CorrelatedSubselects.java:1980-1993 — aggScan only lifts an aggregate into a grouped subselect when the path head passes `toManyHead`
- legend-engine .../core_functions_standard/math/aggregator/average.pure:22-25 — `average(numbers:Integer[*]):Float[1] { ... $numbers->sum() / $numbers->size() }` and its PCT test `assertEquals(1.0, average([1]))` — a one-element average is 1.0, a Float
- legend-engine .../core_relational/relational/sqlDialectTranslation/toPostgresModel.pure:431 — `pair('average', functionCall('avg', ArithmeticExpression(MULTIPLY, DecimalLiteral(1.0d), p0)))` — the engine forces the float by multiplying by 1.0, which is why every golden reads `avg(1.0 * col)`

</details>

---

## `testIsolatioWhereNoConstaintsAndInnerJoin`

| | |
|---|---|
| family | `functions/tests/projection` |
| sweep status | FAIL |
| **verdict** | **REAL DEFECT** |
| effort | M |
| confidence | high |

**Root cause**

A mapping relational expression of the form `@J1 > @J2 | TABLE.COL` (RelationalOperation.JoinNavigation with a terminal ColumnRef) has its terminal column resolved BY THE SPELLED TABLE NAME against the enclosing table scope, instead of being bound to the join chain's terminal sub-row. RelOpTranslator's JoinNavigation arm builds `innerScope = tableScope + {terminalTable -> subRow}` and then recurses; the ColumnRef arm is a plain name lookup in that map. The chainedJoinsInner mapping spells `address(name: case(or(equal(@Firm_FirmPersonBridge > (INNER) @Person_FirmPersonBridge | firmTable.ADDRESSID, 1), equal(@Address_Firm | addressTable.ID, 1)), 'UK','Europe'))`. The chain's terminal table is personTable, but the spelled name is `firmTable`, which IS in scope — it is the class mapping's own main table (root). So the read binds to `root.ADDRESSID` (values 8,9,10,11 — never 1) instead of `persontable.ADDRESSID` (person 1 has ADDRESSID=1). Two consequences, both observed: (a) the case is always 'Europe'; (b) nothing reads the chain's join slot, so Pipelines.walkJoinSlot cancels the join outright ("JOIN CANCELLED: nothing reads through it"), so firmTable never fans out over the bridge and 7 rows collapse to 4. Observed output `[Firm X, Europe, Firm A, Europe, Firm B, Europe, Firm C, Europe]` is exactly what that produces.

**Fix**

In /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/normalizer/RelOpTranslator.java, JoinNavigation arm (539-562): when `jn.terminal()` is a plain `RelationalOperation.ColumnRef cr`, do NOT go through name-scoped translate — yield `new AppliedProperty(subRow, cr.column())` directly, ignoring cr.table() (engine's reprocessAliases rule). Guard it: if `pipeline.targetTable(alias)`'s column set does not contain cr.column(), throw a loud ModelException naming the chain and column rather than falling back to the outer scope — a silent fallback is exactly the bug. Leave every non-ColumnRef terminal (FunctionCall etc.) on the current path: `innerScope.put(terminalTable, subRow)` then translate, which matches engine's DynaFunction branch. Make the identical change at MappingNormalizer.java:2618-2626 (the PropertyMapping.JoinTerminalColumn arm builds the same scope map the same way) — engine resolves both through the one resolveJoinElement site. Best shape: extract a single helper `joinTerminalRead(RelationalOperation terminal, ValueSpecification subRow, String terminalTable, Map<String,ValueSpecification> outerScope, …)` and call it from both.

**How legend-engine does it** — /Users/neemsandv/legend/legend-engine/legend-engine-xts-relationalStore/legend-engine-xt-relationalStore-generation/legend-engine-xt-relationalStore-pure/legend-engine-xt-relationalStore-core-pure/src/main/resources/core_relational/relational/pureToSQLQuery/pureToSQLQuery.pure:1555 — in resolveJoinElement (:1535), the NON-DynaFunction branch does `$col->reprocessAliases(^OldAliasToNewAlias(first = $col->cast(@TableAliasColumn).alias.relationalElement->cast(@NamedRelation).name, second = $op.alias->toOne()))`: the terminal TableAliasColumn's alias is REWRITTEN, unconditionally, to the alias produced by applying the join tree. `$op.alias` is `currentTreeNode.alias` (pureToSQLQuery.pure:75), i.e. the LAST hop's target. The DynaFunction branch (:1541-1546) deliberately does NOT re-alias — it re-resolves columns by name against the tree after applyJoinInTree.

**Risk** — Blast radius is every `@J | T.COL` read in every mapping, but behavior only CHANGES where the spelled table differs from the chain's terminal table — today those either bind to the wrong row (this bug) or already throw 'not in scope'. Check MappingNormalizer.java:2844-2845, which builds the same terminal scope for a third site, before assuming two edits suffice. Tenet-2 trap: after the fix the emitted joins will be `firmTable LEFT bridge LEFT person` where engine emits `firmTable LEFT (bridge INNER person)` — row-equivalent on this fixture (no orphan bridge rows) but NOT in general, because emitJoinChain drops the per-hop `(INNER)` annotation. Do not paper over any residual row-count difference in the harness; if it appears, the honest fix is JoinType threading in emitJoinChain (the rung NavMaterializer.java:184-188 already names as missing). The golden-SQL assert in this test will still differ (no isolation subselect) — that is advisory text, not rows.

**Also unblocks** — Any mapping in the corpus whose join-terminal column names a table other than the chain's last hop. The sibling in the same file, testIsolationOfFiltersWithoutAliasWithChainedJoins (testFilters.pure:110), and chainedInnerJoinsForPrimitive (advancedRelationalSetUp.pure:323) exercise the same construct; I did not verify their current status, so treat this as a lead, not a claim.

**Falsifier** — Dump the generated SQL for this query (or just the addressName column expression). If the case already reads a bridge/person alias rather than "root".ADDRESSID — i.e. the terminal is already bound to the chain — this diagnosis is wrong and the 4-vs-7 row loss is elsewhere (look next at the slot-demand closure in NavMaterializer/InnerDemand).

<details><summary>Evidence read (7 citations)</summary>

- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/normalizer/RelOpTranslator.java:557 — `String terminalTable = pipeline.targetTable(alias);` then :559 `if (terminalTable != null) innerScope.put(terminalTable, subRow);` — ONLY the chain's own terminal table name is rebound, everything else keeps the outer scope
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/normalizer/RelOpTranslator.java:189-202 — the ColumnRef arm is `tableScope.get(canonicalTable(ref.table()))`, i.e. pure name lookup; it only throws when the name is absent, and `firmTable` is present (the main table)
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/resolver/Pipelines.java:405-407 — `if (!demanded.contains(js.alias())) { stripped.add(js.alias()); return left; } // JOIN CANCELLED: nothing reads through it` — an unread chain slot silently disappears, taking its row fan-out with it
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/normalizer/JoinChainEmission.java:263-410 — emitJoinChain emits the chain into the pipeline for every collected JoinNavigation (Embedded PMs included, :664), and never reads hop.joinType(): the `(INNER)` annotation is silently downgraded to the LEFT stamp Pipelines applies at :429-433
- /Users/neemsandv/legend/legend-engine/.../core_relational/relational/tests/mapping/join/advancedRelationalSetUp.pure:291-311 — Mapping chainedJoinsInner, and :309 the exact `case(or(equal(@Firm_FirmPersonBridge > (INNER) @Person_FirmPersonBridge |firmTable.ADDRESSID, 1), equal(@Address_Firm |addressTable.ID, 1)), 'UK', 'Europe')`
- /Users/neemsandv/legend/legend-engine/.../core_relational/relational/tests/mapping/join/advancedRelationalSetUp.pure:476-482, 496-501, 515-519 — fixture data: personTable ADDRESSID 1..7 (person 1 = 1), firmTable ADDRESSID 8,9,10,11; bridge rows (1,1)(1,2)(1,3)(1,4)(2,5)(3,6) and firm 4 deliberately has no employees → engine's 7 rows with exactly one 'UK'
- /Users/neemsandv/legend/legend-engine/.../core_relational/relational/functions/tests/projection/testFilters.pure:85-92 — the test body and its golden SQL, whose case reads "firmpersonbridgetable_0".ADDRESSID (the isolated bridge⋈person subselect exporting "persontable_0".ADDRESSID), not "root".ADDRESSID

</details>

---

## `testSimpleBoolean` — ✅ NOW PASSES (fixed upstream; diagnosis retained for the record)

| | |
|---|---|
| family | `functions/tests/projection` |
| sweep status | FAIL |
| **verdict** | **REAL DEFECT** |
| effort | XS |
| confidence | high |

**Root cause**

`$result.values.rows->at(0).values->at(1)` is a TDSRow CELL index. The Typer's `.values` arm over a relation-typed source enumerates cells only when the source is a TypedVariable (a row binder inside map/filter); for every other source it returns the source unchanged ('return source;' — the row-major flatten identity). After the `.rows` marker is erased, `rows->at(0)` is a single-row pick that is still relation-typed, so the following `.values` was identity and the outer `->at(1)` lowered as a SECOND row slice (LIMIT 1 OFFSET 1) over a 1-row relation → no rows → the harness's Eval.values() yields List.of() for a null/absent scalar → the reported 'got []'. NOTE: this is already fixed at the current worktree HEAD; the sweep in the brief predates it.

**Fix**

NO CODE CHANGE. The fix has already landed at worktree HEAD as Typer.tdsRowCellIndexRead (Typer.java:940-988, dispatched at :396) plus ROW_PICK_FQNS. Action: re-run this single test at HEAD and drop the row from the failing set; do not re-derive a second fix on top of it. If a re-sweep is what produced this brief, then treat the falsifier below as the next step instead.

**How legend-engine does it** — /Users/neemsandv/legend/legend-engine/.../core_relational/relational/functions/tests/projection/testQualifier.pure:31-37 — engine's TDSRow.values is Any[*] in column order, so ->at(1) is column 1 ('Type A'); Account 'Account 2' is the only row whose name contains '2', hence false then true.

**Risk** — Do not 'fix' this again in the Typer's `.values` arm by making the identity branch enumerate cells — the identity flatten is load-bearing for `$tds.rows.values` (whole-row list compares, e.g. testQualifierInLambdaDeep, the tree.pure family) and the HEAD commit explicitly left it untouched for that reason.

**Also unblocks** — testDupsFilterProject (functions/tests, 'expected Firm X, got [Firm X, Yes]') — the same commit says it flips too, and it is still listed at failing.tsv:65.

**Falsifier** — Run only meta::relational::tests::projection::qualifier::testSimpleBoolean at HEAD (9d1f2cd0). If it still reports 'expected false, got []', tdsRowCellIndexRead is not firing for this shape — check that synth of `$result.values.rows->at(0)` really produces a TypedNativeCall whose callee qualifiedName is in ROW_PICK_FQNS (a folded/erased `at` would slip past the `pick instanceof TypedNativeCall` gate), and only then suspect the projected boolean value itself.

<details><summary>Evidence read (6 citations)</summary>

- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/compiler/spec/Typer.java:2429-2464 — the `.values` arm: cells are enumerated only `if (source instanceof TypedVariable)`, otherwise `return source;` (identity)
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/compiler/spec/Typer.java:940-988 — ROW_PICK_FQNS {at, first, last, toOne} and `tdsRowCellIndexRead`, whose javadoc is literally 'rows->at(i).values->at(k) is CELL k of the picked row … NOT a row slice'; it rewrites to `toOne(<pick>.<colName_k>)` and folds `.values->size()` to the column count
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/compiler/spec/Typer.java:396 — `TypedSpec rowCell = tdsRowCellIndexRead(af, env); if (rowCell != null) return rowCell;` — the arm is wired into the applied-function dispatch
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/harness/EngineTestExecutor.java:2502 — `sc.value() == null ? List.of()` in Eval.values(), and :2114 evalScalar returns the list itself when size != 1 → the literal 'got []' rendering
- git commit 9d1f2cd0 (worktree HEAD, 2026-08-15 20:25) 'Goal #18: TDSRow cell reads by index — values->at(k) is a COLUMN, not a row slice': names this test verbatim ('testSimpleBoolean \'expected false, got []\' — 2 rows present on the connection all along … the same query standalone on DuckDB 1.5 returns the row, so the defect was ours, in the Typer'), reports corpus 2299→2301, FAIL 89→87
- /Users/neemsandv/.claude/jobs/5671074c/tmp/burndown/failing.tsv:65 — 'FAIL functions/tests testDupsFilterProject assertEquals: expected Firm X, got [Firm X, Yes]' — the OTHER test the same commit says it flipped, with the identical quoted detail, is still in the sweep: the sweep baseline is older than HEAD

</details>

---

## `testTwoQualifiersUsingSameJoinWithNoUserParams`

| | |
|---|---|
| family | `functions/tests/projection` |
| sweep status | FAIL |
| **verdict** | **HARNESS GAP** |
| effort | S |
| confidence | high |

**Root cause**

`assertSize($result.values->at(0), 1)` asserts the Result ENVELOPE holds exactly one TabularDataSet carrier — a TDS is one object, not its rows. legend-lite's platform deliberately erases that envelope (the splice hook collapses `$r.values->at(0)` on a relation-rooted frame to the spliced relation itself), so the one-TDS arity can only be answered at the assert boundary. The harness has exactly that rule — carrierSizeCheck, 'a TDS is ONE object (carrier)' — but the assertSize arm gates it on the BARE `$result.values` spelling (args.get(0) instanceof AppliedProperty with property 'values' over an exec-frame Variable). The `->at(0)` spelling is an AppliedFunction, misses the gate, falls through to the generic Eval path, and Eval.size() over a Tabular returns t.rows().size() — 4, the productTable row count. Nothing about the query, the SQL, or the rows is wrong; only the arity adapter is missing this spelling. The near-identical sibling testAssociationToOne.pure:41 uses the same spelling and passes only because its TDS happens to have exactly 1 row.

**Fix**

In EngineTestExecutor's assertSize arm (EngineTestExecutor.java:1944), before the generic Eval path, peel `at(_,0)` / `toOne()` / `first()` wrappers off args.get(0) (the same walk recordExecChain already does at :186-197) and, if what remains is the bare `$<execVar>.values` on an exec-frame variable AND no `.rows` property was traversed, route to carrierSizeCheck passing the ORIGINAL arg (carrierSizeCheck evaluates it itself and classifies the result as a TDS carrier). That is a one-spelling extension of an existing, documented adapter, not a new rule. If you would rather the rule live platform-side, the parallel site is StatementExecutor.java:2536-2546 (fold size over an inline envelope peel to 1) — but note that assertSize never emits a `size()` node, so that arm alone would not reach this test.

**How legend-engine does it** — Engine's Result<TabularDataSet|MANY>.values is TabularDataSet[*]; `->at(0)` picks the single TDS and assertSize over one object is 1. legend-lite has no envelope type by design — /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/compiler/spec/Typer.java:2429-2437 states it: 'The Result-ENVELOPE .values never reaches the Typer: the test driver peels it at substitution.'

**Risk** — Must not swallow `$result.values.rows->at(0)` — that is a REAL row pick, and folding its size to 1 would break every `assertSize($result.values.rows, N)`. Gate strictly on 'no .rows traversed during the peel'. Tenet-2 trap: do NOT make Eval.size() over a Tabular return 1 in general, and do not touch the platform's envelope erasure to make this assert pass. Second-order: fixing assertSize only unblocks the FIRST assert; the test then proceeds to `assertEquals([… ^TDSNull(), ^TDSNull()], $result.values->toOne().rows.values)` — expected to pass (the structurally identical testDerivedWithFilteringTwoProperties and testQualifierWithFilteringAndParameters are both absent from the sweep's failing set), but verify rather than assume.

**Falsifier** — In a scratch copy of the test, change the assert to `assertSize($result.values, 1)` (the bare spelling). If it then reports 1, the missing carrier gate is the whole story; if it still reports 4, carrierSizeCheck is not classifying this result as a TDS carrier (check Tabular.returnType() against PlatformTypes.isTdsType / RelationType at EngineTestExecutor.java:163-168) and the diagnosis needs revising.

<details><summary>Evidence read (8 citations)</summary>

- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/harness/EngineTestExecutor.java:1944-1949 — the assertSize gate: `if (args.get(0) instanceof AppliedProperty vp && vp.property().equals("values") && vp.receiver() instanceof Variable rv && execChains.containsKey(rv.name())) return carrierSizeCheck(...)` — an AppliedFunction `at(...)` never matches
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/harness/EngineTestExecutor.java:155-176 — carrierSizeCheck: 'BARE $result.values = the engine Result envelope's values: a TDS is ONE object (carrier)'; `long carriers = tdsCarrier ? 1L : av.size();`
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/harness/EngineTestExecutor.java:1954-1957 — the generic path: `Eval a = eval(args.get(0), …); long actual = a.size();` and the exact message 'assertSize: expected N, got M'
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/harness/EngineTestExecutor.java:2486-2491 — Eval.size(): `case Tabular t -> t.rows().size();` → 4 for the 4-row productTable projection
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/StatementExecutor.java:2548-2571 — the splice hook's envelope-peel arm: `$r.values->at(k)/->toOne()/->first()` over a relation-rooted chain returns the spliced RELATION (and throws for k>0: 'the values envelope holds one TDS')
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/StatementExecutor.java:2536-2546 — the one-TDS size rule exists platform-side, but only for a LET-bound frame variable: `size(TypedVariable bound to a relationRooted frame)` → constant 1
- /Users/neemsandv/legend/legend-engine/.../core_relational/relational/functions/tests/projection/testAssociationToOne.pure:41 — `assertSize($result.values->at(0), 1)` on a TDS query that returns exactly one row: identical spelling, passes today by coincidence, which is why this gap is nearly invisible
- /Users/neemsandv/.claude/jobs/5671074c/tmp/burndown/failing.tsv — 'assertSize: expected 1, got 4' occurs exactly once in the whole sweep

</details>

---

## `testGraphFetchWithTableMapperPostProcessor`

| | |
|---|---|
| family | `graphFetch/tests` |
| sweep status | FAIL |
| **verdict** | **MISSING FEATURE** |
| effort | M |
| confidence | high |

**Root cause**

legend-lite silently ignores the connection's `postProcessors` when that post-processor is a `^MapperPostProcessor(mappers = ^TableNameMapper(...))` built inline in Pure. The test's runtime renames `default.personTable` -> `differentPersonTable`, and `differentPersonTable` is created but never populated by the corpus setup, so the engine's employees come back empty for every firm. legend-lite has exactly one rename channel, `RelationalMapperRenames`, and it recognises exactly one shape: `walk` only fires on a `TypedNewInstance` carrying the property `queryPostProcessorsWithParameter`, and `readPostProcessor` returns silently unless the entry is a native call to `meta::pure::alloy::connections::relationalMapperPostProcessor` ('other postprocessors ride their own channels'). The test's shape is neither — it is a `postProcessors` property holding a `MapperPostProcessor` instance — so `extract` returns `UnaryOperator.identity()` with no diagnostic. Worse, even the recognised channel is only wired into the PLAN-text path: `StatementExecutor.engineSql(lam, mappingFqn, specs, env, renderer)` — the overload the execution path uses — hardcodes `java.util.function.UnaryOperator.identity()` for tableRenames, while the rename extraction happens only in the plan-generation path. So the executed SQL reads `personTable` and returns the real employees.

**Fix**

Two changes, both in the platform. (1) Extend the rename reader: in `RelationalMapperRenames` (core/src/main/java/com/legend/plan/RelationalMapperRenames.java) make `walk` also fire on a `TypedNewInstance` carrying a `postProcessors` property, and add a `readMapperPostProcessor` sibling to `readPostProcessor` that recognises `^MapperPostProcessor(mappers = [...])` and each `^TableNameMapper(schema = ^SchemaNameMapper(from, to), from, to)` / bare `^SchemaNameMapper(from, to)` entry, feeding the same `Cfg.tableTo` / `Cfg.schemaTo` maps so `rename(...)` and `SqlPostProcessors.apply` are reused unchanged. Resolve the store identity the same way the existing `readDatabaseMapper`/`readSchemaMapper` path does, so same-named schemas in different databases stay distinct. (2) Wire it into execution: `StatementExecutor.engineSql(TypedLambda, String, SpecCompiler, ExecEnv, EngineStyleH2)` at StatementExecutor.java:405-413 must extract renames from the runtime argument (as StatementExecutor.java:2006 already does for plan text) and pass them through instead of `UnaryOperator.identity()`. Until (1) lands, `readPostProcessor` should NOT stay silent on an unrecognised `postProcessors` entry whose type is `MapperPostProcessor` — throw `NotImplementedException`, because silently returning real employees where the test expects none is precisely the wrong-rows-instead-of-a-wall failure the tenets forbid.

**How legend-engine does it** — /Users/neemsandv/legend/legend-engine/…/core_relational/relational/postprocessor/tests/testPostProcessor.pure:39-42 — the engine's own golden for this rename: `assertSameSQL('select "root".ID as "pk_0", … from differentPersonTable as "root"', $result)`, i.e. the mapper rewrites the FROM table before execution, not just in the plan text.

**Risk** — Turning the silent skip into a loud NotImplementedException will convert other currently-'passing' tests that carry an unread `postProcessors` block into visible failures. That is the correct direction under the tenets, but expect the corpus pass count to dip before it rises. Do not implement the rename by rewriting the test harness's expectations or by special-casing `differentPersonTable`.

**Also unblocks** — Any other corpus test whose runtime declares `postProcessors = ^MapperPostProcessor(...)` inline — the postprocessor/tests family already asserts this rename at SQL-text level and would gain execution-level coverage.

**Falsifier** — Set a breakpoint / trace on `RelationalMapperRenames.extract` for this test. If it returns a non-identity operator (i.e. it already sees the MapperPostProcessor), the reader is not the gap and the whole cause is the exec-path wiring at StatementExecutor.java:405-413 alone — effort drops to S.

<details><summary>Evidence read (10 citations)</summary>

- /Users/neemsandv/legend/legend-engine/…/graphFetch/tests/testSimpleRelationalGraphFetch.pure:568-594 — the runtime declares `postProcessors = ^MapperPostProcessor(mappers = ^TableNameMapper(schema = ^SchemaNameMapper(from = 'default', to = 'default'), from = 'personTable' , to = 'differentPersonTable'))` and expects `"employees":[]` for all four firms
- /Users/neemsandv/legend/legend-engine/…/tests/relationalSetUp.pure:21 — `Table differentPersonTable (ID INT PRIMARY KEY, FIRSTNAME …)` exists in the store
- /Users/neemsandv/legend/legend-engine/…/tests/relationalSetUp.pure:1394 — `dropAndCreateTableInDb(db, 'differentPersonTable', $connection)` — created and left EMPTY, which is why the expectation is empty employee lists
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/plan/RelationalMapperRenames.java:136-145 — `walk` fires only on `ni.properties().containsKey("queryPostProcessorsWithParameter")`; `readPostProcessor` returns silently unless the callee is `PP_FQN` (`meta::pure::alloy::connections::relationalMapperPostProcessor`)
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/plan/RelationalMapperRenames.java:55 — `PP_FQN = "meta::pure::alloy::connections::relationalMapperPostProcessor"` — the only recognised entry
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/StatementExecutor.java:405-413 — the execution-path `engineSql` overload passes `java.util.function.UnaryOperator.identity()` as tableRenames
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/StatementExecutor.java:2002-2010 — the ONLY `RelationalMapperRenames.extract` call site, inside the plan-node builder
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/StatementExecutor.java:469-474 — `SqlPostProcessors.apply(p2, tableRenames)` — the application point, correct but fed identity on the exec path
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/protocol/Protocol.java:2195-2210 — `PMapperPostProcessor(List<PMapper>)` / `PRelationalMapperPostProcessor` already exist in the protocol model
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/parser/section/ConnectionSectionGrammar.java:545,708-770 — `postProcessors` with `mapper { mappers: [ table {...}, schema {...} ] }` is already PARSED from connection grammar, so only the Pure-instance reader and the exec wiring are missing

</details>

---

## `test6`

| | |
|---|---|
| family | `graphFetch/tests/union` |
| sweep status | FAIL |
| **verdict** | **EXECUTION-TARGET ARTIFACT** |
| effort | S |
| confidence | low |

**Root cause**

Only the ORDER of the top-level firm array differs; the per-firm contents legend-lite returned for Firm X match the expectation for Firm X element-for-element. `Mapping6` declares `special_union(firm_set1, firm_set2, firm_set3)` where FirmSet1='Firm X'(id 1), FirmSet2='Firm A'(id 2), FirmSet3='Firm B'(id 3). legend-lite deliberately serializes union members in BRANCH DECLARATION order — `UnionSerialOrder` injects a per-branch negated ordinal into the `UNION ALL` for exactly this purpose — so it emits Firm X, Firm A, Firm B. The corpus expects Firm B, Firm X, Firm A. That order is derivable from nothing in the model: not declaration order (1,2,3), not the firm ids (3,1,2), not legalName, and not any ORDER BY over the union pk columns I could construct (`order by pk_0_0, pk_0_1, pk_0_2` gives B,A,X with H2 NULLS-FIRST and X,A,B with NULLS-LAST — neither is B,X,A). The engine's own router returns special_union members in declaration order (`special_union(o) { $o.parameters.setImplementation }`), which confirms the B,X,A ordering is not a routing decision. It is therefore an artifact of the engine's result assembly / H2's incidental row order for a three-leg union, captured verbatim into the golden. Note this file's two-leg tests (test1..test5) expect Firm X, Firm A — declaration order — which legend-lite already matches; only the three-leg case diverges.

**Fix**

Do not change `UnionSerialOrder`: declaration order is the principled contract, it is what the engine's router produces, and it is what this file's own two-leg tests pin. Chasing B,X,A would mean reproducing an H2/assembly accident. Ledger this test as an execution-target ordering artifact. If a green result is required, the honest lever is a harness ORDER-POLICY extension applied ONLY to graphFetch roots over a union with no sort in the query chain — i.e. compare the top-level JSON array as a multiset of objects while keeping every nested array order-sensitive — parallel to the existing documented order policy for unsorted TDS chains in `EngineTestExecutor.compare`. That is a deliberate, documented leniency about an undefined ordering, not a correctness compensation, and it must be scoped narrowly enough that a genuinely wrong-ordered nested `employees` array still fails.

**How legend-engine does it** — /Users/neemsandv/legend/legend-engine/legend-engine-core/legend-engine-core-pure/legend-engine-pure-code-compiled-core/src/main/resources/core/pure/router/operations/router_operations.pure:34-37 and .../router/store/routing.pure:814-840 — the engine resolves and concatenates special_union members in declaration order, so the golden's B,X,A cannot come from routing.

**Risk** — If the harness order policy is widened, it must not extend to nested arrays or to non-union graphFetch roots; a blanket multiset comparison on JSON arrays would hide real graphFetch ordering defects across the whole graphFetch family. This is the tenet-2 boundary for this test — the platform owns member order, the harness may only be lenient where SQL genuinely defines none.

**Falsifier** — Print legend-lite's full actual JSON for test6 and compare it to the expectation as a MULTISET of firm objects. If the three firms are all present with byte-identical employee lists, this is purely ordering and the verdict holds. If Firm B is missing, duplicated, or has a wrong employee list, it is a real defect in three-leg special_union graphFetch and the verdict is REAL_DEFECT. The brief's failure text is truncated at Firm X's second employee, so this cannot be settled from it.

<details><summary>Evidence read (8 citations)</summary>

- /Users/neemsandv/legend/legend-engine/…/graphFetch/tests/union/testUnionPropertyLevel_Relational.pure:212-233 — test6 body and the expectation ordered Firm B, Firm X, Firm A
- /Users/neemsandv/legend/legend-engine/…/graphFetch/tests/union/testUnionPropertyLevel_Relational.pure:544-548 — `Mapping6`: `special_union_…(firm_set1, firm_set2, firm_set3)`
- /Users/neemsandv/legend/legend-engine/…/graphFetch/tests/union/testUnionPropertyLevel_Relational.pure:71-80 — setup: FirmSet1='Firm X'(1), FirmSet2='Firm A'(2), FirmSet3='Firm B'(3)
- /Users/neemsandv/legend/legend-engine/…/graphFetch/tests/union/testUnionPropertyLevel_Relational.pure:113-121,160-166 — test1/test3 (two-leg) expect Firm X then Firm A, i.e. declaration order
- /Users/neemsandv/legend/legend-engine/legend-engine-core/legend-engine-core-pure/legend-engine-pure-code-compiled-core/src/main/resources/core/pure/router/operations/router_operations.pure:34-37 — `special_union(o) { $o.parameters.setImplementation }` — declaration order, no reordering
- /Users/neemsandv/legend/legend-engine/legend-engine-core/legend-engine-core-pure/legend-engine-pure-code-compiled-core/src/main/resources/core/pure/router/store/routing.pure:814-840 — special_union roots are converted to a CONCATENATION over `$resolvedSets` (declaration order)
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/lowering/UnionSerialOrder.java:17-24,54-72 — 'union members serialize in BRANCH DECLARATION order'; injects `u_serial_ord__` per branch
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/harness/EngineTestExecutor.java:1998-2001 — `assertJsonStringsEqual`: 'object keys order-INSENSITIVE, arrays order-SENSITIVE', so the top-level array order is asserted

</details>

---

## `testPrerouting42`

| | |
|---|---|
| family | `router/tests` |
| sweep status | SHAPE |
| **verdict** | **TESTS ENGINE INTERNALS** |
| effort | XS |
| confidence | high |

**Root cause**

`assertRoundTrip` is not an assert about query RESULTS at all — it is a white-box unit test of legend-engine's own Pure-implemented pre-evaluation router. Its 5-arg body runs `$input->preval(...)` to get a rewritten FunctionDefinition, then `transformFunctionBody($extensions)->toJSON(50000)->parseJSON()->toPrettyJSONString()` on BOTH the prevalled result and the expected lambda and compares the two protocol-JSON strings; only on mismatch does it fall back to `toPure()` text. So the contract is: legend-engine's preeval AST rewrite, serialized through legend-engine's vX_X_X protocol serializer, byte-for-byte. legend-lite models `preval` as IDENTITY for row semantics and has no preeval AST pass and no protocol-JSON round trip, so there is nothing to compare. The harness's assert dispatch has no `assertRoundTrip` case, falls to the default, and `scoreAssert` emits the bare-marker Unsupported message. The wall is honest and correctly attributed (`Outcome.Unsupported`, a SHAPE not a FAIL).

**Fix**

DO NOT FIX — ledger it. Adding an `assertRoundTrip` arm to EngineTestExecutor would require legend-lite to (a) reimplement `meta::pure::router::preeval::preval` as a faithful AST→AST rewrite and (b) reimplement `meta::protocols::pure::vX_X_X::transformation::fromPureGraph` + `toJSON` so the serialized text matches legend-engine's byte-for-byte. Both are engine compiler internals with no observable row semantics, and a partial implementation would produce a passing test that proves nothing. The correct action is to record the test in the engine-internals ledger (alongside the other white-box router/protocol tests) so it stops being counted as a fixable SHAPE. If anything changes, it should be the CLASSIFICATION only: `scoreAssert` could recognise a small set of known engine-internals assert names and emit a distinct outcome (e.g. "engine-internals assert") so the burndown does not read this as an unimplemented harness surface.

**How legend-engine does it** — /Users/neemsandv/legend/legend-engine/legend-engine-core/legend-engine-core-pure/legend-engine-pure-code-compiled-core/src/main/resources/core/pure/router/preeval/tests.pure:1681 (the assertRoundTrip contract) and .../core/pure/router/preeval/preeval.pure:22-40 (`meta::pure::router::preeval::State`, the pass legend-lite does not have)

**Also unblocks** — testPrerouting41 and testPrerouting_Store in the same file are <<test.ToFix>> so they are not run; the other assertRoundTrip call sites live in legend-engine-core, outside the relational corpus. No other corpus test is unblocked.

**Falsifier** — Find a `preval` implementation in legend-lite that produces a rewritten AST (not identity) AND a vX_X_X protocol serializer whose output is byte-compatible with legend-engine's `transformFunctionBody(...)->toJSON()`. If both exist, this is a HARNESS_GAP (just wire the assert arm), not engine internals. `grep -rn "transformFunctionBody\|fromPureGraph" core/src/main/java/com/legend/` returning real serializers would flip it.

<details><summary>Evidence read (7 citations)</summary>

- /Users/neemsandv/legend/legend-engine/legend-engine-core/legend-engine-core-pure/legend-engine-pure-code-compiled-core/src/main/resources/core/pure/router/preeval/tests.pure:1681-1706 — the 5-arg `assertRoundTrip` body: `$input->preval(...)`, then `$expected->transformFunctionBody($extensions)`/`$result->transformFunctionBody(...)`, `->toJSON(50000)->parseJSON()->toPrettyJSONString()`, compared as strings
- /Users/neemsandv/legend/legend-engine/legend-engine-core/legend-engine-core-pure/legend-engine-pure-code-compiled-core/src/main/resources/core/pure/router/preeval/tests.pure:1676-1678 — the 3-arg overload the corpus test calls delegates to the 5-arg one with empty stopInlineFunctions/extensions
- /Users/neemsandv/legend/legend-engine/.../core_relational/relational/router/tests/testPreeval.pure:63-123 — `testPrerouting42` builds `$input`/`$expected` lambdas that differ only in `col(lambda,'name')` vs `^BasicColumnSpecification<Person>(name=..., func=...)`, i.e. the AST normalization preeval performs
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/harness/EngineTestExecutor.java:1504-1998 — the assert dispatch switch; every `case "assert..."` arm is present and there is no `assertRoundTrip` arm
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/harness/EngineTestExecutor.java:889-894 — `scoreAssert` emits "assert form '" + af.function() + "/" + arity + "' is not supported yet" when no PLATFORM reason was stashed, which is exactly the observed text
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/harness/EngineTestExecutor.java:603-630 — `zeroArgLambdaArg` documents legend-lite's stance: preval is "the engine's PLAN-TIME pre-evaluation, identity for row semantics: the wrapped query IS the query"
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/builtin/Pure.java:1554-1562 — `preval` exists only as a native SIGNATURE, with a comment pointing at engine preeval.pure; there is no implementation of the pass

</details>

---

## `testFilterOnEnum`

| | |
|---|---|
| family | `tds/tests` |
| sweep status | FAIL |
| **verdict** | **REAL DEFECT** |
| effort | S |
| confidence | high |
| **adversarial review** | **CONFIRMED** |

**Root cause**

`$result.values.rows.values->at(1)` must read CELL 1 in row-major order across the whole TDS (engine: TabularDataSet.rows is TDSRow[*], TDSRow.values is Any[*] in column order, and Pure property access over a collection auto-flattens — so the flat cell list is [Hoboken, CITY, New York, CITY, ...] and at(1) is CITY). legend-lite instead applies at(1) at ROW granularity, returning the whole second row. Mechanism: Typer.applyProperty types `.rows` over a relation as an identity MARKER TypedPropertyAccess, and then types `.values` over that marker as IDENTITY (it only builds a per-column cell TypedCollection when the receiver is a TypedVariable, i.e. a row binder). So the whole `$result.values.rows.values` chain types as the relation itself, and the enclosing `at(rel,1)` reaches Lowerer.relation(), which rewrites positional reads over a relation as `TypedSlice(n, n+1)` = LIMIT 1 OFFSET 1 — one ROW. Eval.values() then flattens that single row to [New York, CITY]. Commit 9d1f2cd0 added exactly the right machinery for the sibling spelling (`rows->at(i).values->at(k)`) but gated it on the receiver being a single-row PICK (`ROW_PICK_FQNS` = at/first/last/toOne as a TypedNativeCall); here the receiver is the whole-relation `.rows` marker, a TypedPropertyAccess, so `tdsRowCellIndexRead` returns null and the old row-slice path stands.

**Fix**

Extend `Typer.tdsRowCellIndexRead` (core/src/main/java/com/legend/compiler/spec/Typer.java:958-990) with a second arm for the WHOLE-RELATION receiver. After `TypedSpec pick = synth(vp.receiver(), env)` yields a `Type.RelationType prt`, if `pick` is NOT a single-row pick but IS the `.rows` marker (a `TypedPropertyAccess` with property "rows" over a relation), then for `at(k)` compute `C = prt.columns().size()`, `r = k / C`, `c = k % C` and return `synth(new AppliedFunction("toOne", List.of(new AppliedProperty(new AppliedFunction("at", List.of(vp.receiver(), new CInteger(r))), prt.columns().get(c).name()))), env)` — i.e. reuse the exact proven route 9d1f2cd0 introduced (row pick, then named-column read, then toOne). Keep the existing out-of-range guard, but bound on rows*cols is not statically known, so only bound `c` and let a bad `r` yield empty. Leave the `size()` arm alone for this receiver (rows*cols is not statically computable) — return null so it stays on its current path rather than lying. Do NOT touch the bare `.values` flatten (no positional consumer): whole-row/whole-grid list compares depend on the identity typing (Typer.java:2463) and `EngineTestExecutor.isFlatCellsRead` (:2645-2649) keys off that spelling.

**How legend-engine does it** — /Users/neemsandv/legend/legend-engine/legend-engine-xts-relationalStore/legend-engine-xt-relationalStore-generation/legend-engine-xt-relationalStore-pure/legend-engine-xt-relationalStore-core-pure/src/main/resources/core_relational/relational/relationalExtension.pure:78 — `^TDSRow(values=$r.values->map(...))`, TDSRow.values is Any[*] in column order; property access over `rows` (TDSRow[*]) flattens row-major, which is what `->at(k)` indexes

<details><summary>Adversarial review notes (CONFIRMED)</summary>

Every citation is exact. Typer.java:2418-2427 returns the identity-typed `.rows` MARKER (a TypedPropertyAccess over the relation); Typer.java:2429-2463 builds per-column cells only `if (source instanceof TypedVariable)` and otherwise `return source;`; tdsRowCellIndexRead (Typer.java:958-990) bails at 968-972 unless `pick instanceof TypedNativeCall` with an fqn in ROW_PICK_FQNS — and for `$result.values.rows.values->at(1)` the receiver of the outer `.values` synths to the TypedPropertyAccess 'rows' marker, so it returns null. I also confirmed the hook is on the path at all (called at Typer.java:396, early in the applied-function arm) and that `at(relation, n)` then reaches Lowerer.relation()'s `at(n) IS slice(n, n+1)` rewrite (Lowerer.java:291-308), whose single row EngineTestExecutor's Tabular arm (2507-2521) flattens to [New York, CITY] — exactly the brief's got value. The engine semantics claim also checks out against the corpus: row 0 is 'Hoboken,CITY' so the flat cell 1 is CITY, which is what the assert expects. The fix reuses the identical route commit 9d1f2cd0 introduced (`toOne(at(<rows>, r).<colName>)`); I verified the named-column read over a relation-typed receiver exists (Typer.relationColumn / the TypedPropertyAccess build at 2607-2609), that bare synthesized function names like 'at'/'toOne' are already used the same way at 987-989, and that the re-entrant synth of `at(<.rows marker>, r)` cannot loop back into tdsRowCellIndexRead (its arg-0 property is 'rows', not 'values'). Value-wise the enum column comes back as the string CITY (PureSql maps EnumType to VARCHAR, and the current got value already shows 'CITY'), so the compare will match. Blast radius checked against the corpus: the only other `rows.values->at(k)` spellings are single-column results (testIn.pure:190, testSqlFunctionsInMapping.pure:409) where cell-0 and row-0 coincide, plus testFetchDbMetaData.pure:176/179 which the cell semantics actually repairs; the `.rows.values->size()` sites are deliberately left alone. Effort S is right, not XS: it edits a shared typer hook, needs the r/c arithmetic plus a bounds decision, and has cross-family exposure.

</details>

**Risk** — Row order: `at(k)` with k >= C now picks row k/C of an UNSORTED relation, whose order is DuckDB-incidental. testFilterOnEnum is safe (k=1, C=2 → row 0, and every row has 'CITY' in column 1), but a future test indexing past the first row could pass or fail on join order. That is the same exposure the existing `rows->at(i)` path already has, not a new one. Tenet-2 trap to avoid: do NOT 'fix' this by special-casing `rows.values` inside EngineTestExecutor.Eval/compare — the cell-vs-row granularity is a TYPER fact (the platform owns TDSRow semantics), and a harness-side index remap would leave the platform still computing the wrong thing for any non-test consumer.

**Also unblocks** — None confirmed. Three other corpus sites use the same spelling (tests/mapping/sqlFunction/testSqlFunctionsInMapping.pure:409, functions/tests/testFetchDbMetaData.pure:176/179, functions/tests/projection/testIn.pure:190) but none of them appear in the 276-test failing set, so they either pass incidentally (single-column relations, where row index == cell index) or are not run.

**Falsifier** — Set LEGEND_LITE_DUMP_SQL and run only tds/tests::testFilterOnEnum. If the emitted SQL for the failing assert is NOT a `LIMIT 1 OFFSET 1` over the addressTable projection (i.e. not a row slice), the granularity diagnosis is wrong. Equivalently: if `Typer.tdsRowCellIndexRead` is reached (add a breakpoint/print) and returns non-null for this expression, my claim that it bails is wrong.

<details><summary>Evidence read (8 citations)</summary>

- /Users/neemsandv/legend/legend-engine/.../core_relational/relational/tds/tests/testTDSFilter.pure:42-48 — `assertEquals(GeographicEntityType.CITY, $result.values.rows.values->at(1));` with the row list showing row 1 = 'New York,CITY' (exactly the observed got value)
- /Users/neemsandv/legend/legend-engine/.../core_relational/relational/relationalExtension.pure:73-84 — `resultSetToTDS`: `^TDSRow(values=$r.values->map(...))` and `^TabularDataSet(columns=..., rows=$rows)` — rows is a collection of TDSRow each carrying `values : Any[*]`
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/compiler/spec/Typer.java:2418-2427 — `.rows` over a RelationType returns `new TypedPropertyAccess(source, "rows", source.info())`, an identity marker
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/compiler/spec/Typer.java:2429-2463 — `.values` over a RelationType builds per-column cells ONLY `if (source instanceof TypedVariable)`; otherwise `return source;` (identity), losing cell granularity
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/compiler/spec/Typer.java:958-990 — `tdsRowCellIndexRead` bails at line 968-972 unless `pick instanceof TypedNativeCall` whose fqn is in ROW_PICK_FQNS; the `.rows` marker is a TypedPropertyAccess, so it returns null
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/lowering/Lowerer.java:291-310 — `relation(TypedSpec)`: "POSITIONAL reads over a relation: at(n) IS slice(n, n+1)" — the row-slice rewrite that produces the wrong granularity
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/harness/EngineTestExecutor.java:2507-2521 — Eval.values() on a Tabular concatenates every row's cells, so the single sliced row renders as [New York, CITY]
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/StatementExecutor.java:2486-2495 — the splice hook erases the `.rows` marker to its source once seen, confirming the marker never reaches lowering as anything but the bare relation

</details>

---

## `testDynaComplexInference2`

| | |
|---|---|
| family | `tests` |
| sweep status | FAIL |
| **verdict** | **REAL DEFECT** |
| effort | XS |
| confidence | high |
| **adversarial review** | **PARTIALLY_WRONG** |

**Root cause**

`MetamodelWalk.inferOp`'s JoinNavigation arm recurses into the nav's TERMINAL with the SAME `Rop` env, discarding the nav's own `[DB]` qualifier. The mapping's `lastName` expression is `substring(concat([myDB]default.personTable.FULLNAME, [myDB]@personExtension|personTableExtension.FULLNAME_PART2), ...)`. For a property-mapping Expression the Rop is built with a NULL database (MetamodelWalk.java:1147-1148), so `columnType` can only resolve a ColumnRef that carries its own `databaseName`. The first concat arg carries `myDB` and resolves to VARCHAR(200). The second arg is a JoinNavigation whose TERMINAL ColumnRef was deliberately stripped of its db by RelOpFromProtocol ('as-written' rule: the terminal is written relative to the nav's own db, so its db is nulled — RelOpFromProtocol.java:70-71 + 111/119-121). With opDb == null and env.db() == null, `columnType` returns null at MetamodelWalk.java:1504-1506, and the concat arm silently adds 0 for an unknown-typed argument (MetamodelWalk.java:1283-1289). Result: VARCHAR(200 + 0) = VARCHAR(200); `substring` passes arg0's size through, so the test sees VARCHAR(200) instead of VARCHAR(400).

**Fix**

In core/src/main/java/com/legend/exec/MetamodelWalk.java, rebind the environment database on the JoinNavigation arm before recursing into the terminal. Replace lines 1315-1317 with:

    case RelationalOperation.JoinNavigation j -> {
        if (j.terminal() == null) { yield null; }
        String jdbName = j.databaseName() != null ? j.databaseName()
                : j.chain().get(0).databaseName();
        DatabaseDefinition jdb = env.db();
        if (jdbName != null && env.ctx() != null) {
            jdb = env.ctx().findDatabase(jdbName).orElse(jdb);
        }
        yield inferOp(new Rop(jdb, env.ctx(), j.terminal()), j.terminal());
    }

(`j.databaseName()` can legitimately be null when the nav is rooted in the enclosing db — RelOpFromProtocol.java:116-118 — hence the fallback to the chain hop's databaseName, which JoinChainElement documents as always populated.) Second, matching the engine's guard, make the `concat`/`group_concat` arm at MetamodelWalk.java:1279-1289 return null (no inferred type) when ANY argument's type is unresolved, instead of contributing 0 — silently shrinking a VARCHAR is exactly the wrong-value failure this test caught. Optionally also thread the owning database into the `Pm` handle so `new Rop(null, p.ctx(), ex.expression())` (line 1148) carries the class mapping's store as the default db; that is the general cure for un-qualified ColumnRefs in mapping expressions, but it is not required for this test.

**How legend-engine does it** — legend-engine sums concat operand sizes and passes substring's arg0 size through: relationalExtension.pure:463-471 (`'concat'` → `Varchar(size = $params->map(p|$p->inferRelationalType())->map(x|$x->getSize())->sum())`) and relationalExtension.pure:1627-1635 (`'substring'` → `Varchar(size = $params->at(0)->inferRelationalType()->match([Char|.size, Varchar|.size]))`). The engine never needs a db lookup because its RelationalOperationElement is post-compilation: relationalExtension.pure:140-141 reads `$t.column.type` off a bound Column and RelationalOperationElementWithJoin simply recurses into its already-bound `relationalOperationElement`. legend-lite resolves columns lazily BY NAME, which is why the lost db qualifier is fatal here. Note also relationalExtension.pure:467 — the engine's concat MATCH guard requires every param to be Char/Varchar, and inferDynaFunctionReturnType (relationalExtension.pure:111-117) asserts 'Type inference not supported yet!' when no candidate matches: the engine is LOUD where legend-lite silently adds 0.

**⚠ Correction from adversarial review** — Apply ONLY the JoinNavigation rebind (MetamodelWalk.java:1315-1317). Drop the concat/group_concat change: the engine sums only the resolvable operand sizes too (unresolved params drop out of the Pure [0..1] map before forAll/getSize), so making the arm return null diverges from ground truth and changes a path shared by views/filters with no test demanding it. Also guard `j.chain()` for emptiness before `get(0)` (an association-side nav can in principle reach here), and note that j.databaseName() is in fact non-null for THIS test (enclosingDb was null, so the nav kept myDB) — the chain fallback is only for the enclosing-db-rooted spelling. Equivalent single-line alternative the diagnosis lists as optional: build the Pm handle as `new Rop(<class mapping's db>, p.ctx(), ex.expression())` at line 1148, which cures every unqualified ColumnRef in mapping expressions at once.

<details><summary>Adversarial review notes (PARTIALLY_WRONG)</summary>

MECHANISM CONFIRMED, and I verified the one step the diagnosis asserted without proof. The chain holds end to end: (a) PropertyfuncMappingWithJoin has NO ~mainTable, and MappingFromProtocol.bodyOf computes `String db = firstNonNull(protocolDb(rawOp), mainDb)` where protocolDb() returns non-null only for a top-level PColumnRef/PElemtWithJoins — lastName's top node is a substring PDynaFunc, so enclosingDb == null for the whole expression. That is why `[myDB]default.personTable.FULLNAME` KEEPS its db through RelOpFromProtocol.columnRef:70-71 (the as-written nulling needs db.equals(enclosingDb)) and resolves to VARCHAR(200), while the nav's terminal is built by joinNavigation with navDb=myDB (line 111/121) and therefore IS nulled. (b) MetamodelWalk.java:1315-1317 recurses with the unchanged env; the Pm handle's Rop is `new Rop(null, p.ctx(), ex.expression())` (line 1148, verified), so columnType (1497-1506) has opDb==null and env.db()==null and returns null; the concat arm (1279-1289) adds 0. (c) substring passes arg0 through (line 1260-1263), so VARCHAR(200) — exactly the observed 'expected VARCHAR(400), got VARCHAR(200)' in RELATIONAL_CORPUS.md:1327. (d) NameResolver.resolveRelOp DOES resolve JoinNavigation.databaseName and the chain hops to FQNs (NameResolver.java:1426-1432), so env.ctx().findDatabase(jdbName) will match the same FQN form the working ColumnRef arm already uses. Sibling testDynaComplexInference1 stays green: firstName's arg0 is a scope-expanded ColumnRef that keeps its db for the same enclosingDb==null reason. WHAT IS WRONG IS THE SECOND HALF OF THE FIX. The diagnosis says 'matching the engine's guard, make the concat/group_concat arm return null when ANY argument's type is unresolved'. The engine's guard (relationalExtension.pure:464-472) is `$params->map(p|$p->inferRelationalType())->forAll(x|$x->instanceOf(Char)||$x->instanceOf(Varchar))` — inferRelationalType returns [0..1], so an UNRESOLVED param vanishes from the mapped collection, passes forAll vacuously, and getSize()->sum() sums only what resolved. The engine therefore does exactly what legend-lite does today (partial sum); the proposed 'return null on any unresolved arg' is a divergence from the engine on a shared inference path (views, filters, every other concat), justified by a misread of the cited guard. Part 1 alone makes this test produce VARCHAR(400).

</details>

**Risk** — `inferOp` is reached only from `MetamodelWalk.infer` (the `inferRelationalType` metamodel surface, dispatched at StatementExecutor.java:1410) and from the view-column recursion inside `columnType`, so the blast radius is confined to typeInference tests. Making the concat arm return null on an unknown arg could turn some currently-'passing-by-luck' inference into an empty result — that is the correct engine behaviour (assert 'Type inference not supported yet!') but should be landed together with the db fix, not before it. TENET-2 TRAP: do not fix this by teaching the corpus runner the expected string, and do not special-case the walk on the test's mapping name — the db-scope loss is a model-walk defect and must be repaired there.

**Also unblocks** — No other test in the briefs is known to depend on this; any corpus `inferRelationalType` assertion over a mapping expression that navigates a join (`@Join|table.COL` inside a dyna function) is unblocked by the same change.

**Falsifier** — Run only this test with a temporary print of the inferred type of each `concat` argument (or set LL_FNLR_DEBUG-style tracing in inferOp). If the join-terminal argument already resolves to VARCHAR(200) and the total is still 200, the db-propagation diagnosis is wrong and the defect is in the concat summation or in which expression the `lastName` property mapping actually carries. Equivalently: if `MetamodelWalk.inferOp` is never reached (the test fails before the walk), the diagnosis is wrong.

<details><summary>Evidence read (9 citations)</summary>

- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/exec/MetamodelWalk.java:1315-1317 — `case RelationalOperation.JoinNavigation j -> j.terminal() == null ? null : inferOp(env, j.terminal());` recurses with the UNCHANGED env; the nav's databaseName/chain db is never bound
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/exec/MetamodelWalk.java:1500-1506 — `DatabaseDefinition db = env.db(); if (opDb != null && env.ctx() != null) db = ...findDatabase(opDb)...; if (db == null) return null;` — with both null the column type is unresolvable
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/exec/MetamodelWalk.java:1144-1148 — a property mapping's `relationalOperationElement` handle is `new Rop(null, p.ctx(), ex.expression())`: env.db() is NULL for every mapping expression
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/exec/MetamodelWalk.java:1279-1289 — the `concat` arm adds v2.size()/c2.size() only when the arg type is Varchar/Char; an unresolved (null) arg contributes 0 SILENTLY
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/model/RelOpFromProtocol.java:70-71 — `String db = t.database() != null && t.database().equals(enclosingDb) ? null : t.database();` — a column written under its own db is recorded with db == null
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/model/RelOpFromProtocol.java:111,119-121 — `String navDb = db != null ? db : enclosingDb;` and the terminal is built with `op(j.relationalElement(), navDb)`, so the terminal's db is nulled against the nav's db
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/model/JoinChainElement.java:16-19 — javadoc: the hop's `databaseName` is 'Always populated' (inherited scope or inline [DB]) — the recoverable db for the fix
- /Users/neemsandv/legend/legend-engine/.../core_relational/relational/tests/mapping/propertyfunc/simplePropertyFunc.pure:189-206 — PropertyfuncMappingWithJoin's `lastName : substring(concat([myDB]default.personTable.FULLNAME, [myDB]@personExtension|personTableExtension.FULLNAME_PART2), add(position(...),2))`; both columns are VARCHAR(200) (declared at simplePropertyFunc.pure:154-155)
- /Users/neemsandv/legend/legend-engine/.../core_relational/relational/tests/testRelationalExtension.pure:294-300 — the test asserts `VARCHAR(400)` for `lastName`; testDynaComplexInference1 (line 285-292) asserts VARCHAR(200) for `firstName`, whose substring reads FULLNAME directly and therefore passes even with the broken concat — the exact pass/fail split observed

</details>

---

## `testRelationalMapperTwoDBs`

| | |
|---|---|
| family | `tests` |
| sweep status | FAIL |
| **verdict** | **REAL DEFECT** |
| effort | L |
| confidence | medium |

**Root cause**

Same mechanism as testRelationalMapperWithJoin, in BOTH query positions. `Product.cusip` is `$this.synonymByType(CUSIP).name`, which inlines to `toOne(filter($p.synonyms, s|$s.type==CUSIP)).name` (simpleTestModel.pure:408 + :454) — a depth-1 scalar filtered-nav read, excluded from the join lift by SyntheticHeads.java:367-371. (a) In PROJECTION position (`p|$p.cusip`) it takes `Substitution.filteredNavLeafRead` and renders as a scalar subselect, so the second select item is `(select ...)` instead of the engine's `"synonymtable_0".NAME as "cusip"` — consistent with the observed 400-char-truncated prefix, which ends exactly where the first (plain) column `"root".NAME as "name"` ends. (b) In FILTER position (`filter(p|$p.cusip == 'CUSIP1')`) the equality takes the explicit EXISTS fold at Substitution.java:1007-1046, emitting `exists(select ... )` where the engine emits `where "synonymtable_0".NAME = 'CUSIP1'` over the SAME join alias used by the projection. Neither position can reproduce the golden, which requires one shared `synonymtable_0` LEFT JOIN carrying `TYPE = 'CUSIP'` and the mapping's ProductSynonymFilter `ID <> 1` in its ON clause. The relational-mapper renaming itself is sound for this two-database case: RelationalMapperRenames.rename disambiguates the two same-named `productSchema` schemas by table membership and yields `snDB.productSchemaNewDBINC.productTableNewINC` / `snDB.productSchemaNewDB.synonymTableNew`.

**Fix**

Apply the shared fix (see testRelationalMapperWithJoin): remove the `directlyOnVar` scalar exclusion in SyntheticHeads.java:371 so the qualified property lifts to a `synonyms#fN` join head with `TYPE = 'CUSIP'` parked on the join, and narrow `Substitution.filteredNavLeafRead` accordingly. ADDITIONALLY, this test needs the FILTER-position arm changed: delete/narrow the EXISTS fold at Substitution.java:1007-1046 so that `$p.cusip == 'CUSIP1'` becomes an ordinary outer-WHERE comparison against the SAME lifted join alias (engine: tests/advanced/testFilterWithQualifiedProperties.pure:126-128), and ensure the projection read and the filter read share one join identity — `parkFiltered` already reuses one identity for equal predicates on the same head, which is what makes `synonymtable_0` appear once. Finally verify the mapping-level ProductSynonymFilter (`ID <> 1`) is emitted into that same ON clause rather than a subselect; the golden requires it.

**How legend-engine does it** — Same as the sibling test: pureToSQLQuery.pure:1110-1129 (`moveFiltersInOnClause` merges the parked filter into `join.operation`) and pureToSQLQuery.pure:5124-5143 (`processFilter` parks it as `savedFilteringOperation` against the last join node). The ON-clause shape this query must produce is independently pinned by functions/tests/projection/testQualifier.pure:213-215 (`on ("synonymtable_0".PRODID = "root".ID and "synonymtable_0".TYPE = 'CUSIP' and "synonymtable_0".ID <> 1)`, mapping filter included) and the filter-position outer-WHERE shape by tests/advanced/testFilterWithQualifiedProperties.pure:126-128.

**Risk** — Removing the EXISTS fold changes semantics for object-space filters over to-many navigations: EXISTS never duplicates parents, whereas the engine's LEFT JOIN + outer WHERE does (dedup happens at the engine's PK reader, not in SQL). Tests that assert row counts of object queries may move, and the not-equal/ordering operators deliberately kept on the strict path (Substitution.java:1005-1010) must be re-derived from the engine's per-operator null compensation rather than left inconsistent. TENET-2 TRAP: this test asserts only SQL text, so it is tempting to 'fix' it by post-processing or by loosening the corpus comparison — do not; the row-level defect is real and is what makes the sibling U30 test error at runtime.

**Also unblocks** — `testRelationalMapperWithJoin` (this unit), `testIsolationOfFiltersWithoutAlias` (U30), and the qualified-property tests in U34/U42.

**Falsifier** — The observed 'got' is truncated at 400 characters, before the divergence. Re-run with the truncation lifted: if the full SQL contains `(select` for the cusip column and/or `exists (` in the WHERE, this diagnosis holds. If instead the SQL is join-shaped with a single `synonymtable_0` alias and differs only in the catalog/schema spelling or in whether `"synonymtable_0".ID <> 1` appears in the ON clause, the root cause is RelationalMapperRenames or mapping-filter placement, not the filtered-nav lowering — that is the single cheapest observation and it should be taken before any code is touched.

<details><summary>Evidence read (7 citations)</summary>

- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/resolver/SyntheticHeads.java:353-371 — the same scalar+directlyOnVar exclusion; `$p.synonyms` is directly on the lambda var and `.name` is [1]
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/resolver/Substitution.java:1000-1046 — the FILTER-position arm: 'FILTER-POSITION pierced-toOne EQUALITY folds into EXISTS ... PROJECTION position keeps the scalar subquery', returning `neCallee()` (isNotEmpty) over a TypedFilter of the filtered-nav relation
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/resolver/Substitution.java:1746-1747 — the rewrite switch dispatches any TypedPropertyAccess matching filteredNavLeafRead onto the correlated-relation arm
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/plan/RelationalMapperRenames.java:317-363 + :281-306 — `rename` resolves catalog/schema/table per DEFINING db (`definingDb` walks includes) and disambiguates by `hasTable`; for dbInc.productSchema.productTable it yields snDB.productSchemaNewDBINC.productTableNewINC and for db.productSchema.synonymTable snDB.productSchemaNewDB.synonymTableNew — i.e. the renaming channel already matches the golden
- /Users/neemsandv/legend/legend-engine/.../core_relational/relational/tests/relationalSetUp.pure:61-64 and :199-202 — dbInc declares productSchema{productTable}, db (which includes dbInc) declares productSchema{synonymTable}: the membership discriminator RelationalMapperRenames relies on is real
- /Users/neemsandv/legend/legend-engine/.../core_relational/relational/tests/testModel/simpleTestModel.pure:408,454 — `cusip(){$this.synonymByType(ProductSynonymType.CUSIP).name}` and `synonymByType(type){$this.synonyms->filter(s|$s.type == $type)->toOne()}`
- /Users/neemsandv/legend/legend-engine/.../core_relational/relational/tests/testRelationalMapper.pure:140-144 — the failing test; the seven sibling mapper tests at lines 80-131 use `Product.all()` with no qualified property and are not in the failing set, isolating the qualified-property path as the difference

</details>

---

## `testRelationalMapperWithJoin`

| | |
|---|---|
| family | `tests` |
| sweep status | FAIL |
| **verdict** | **REAL DEFECT** |
| effort | L |
| confidence | high |

**Root cause**

`$f.employeeByLastName('Smith').address.name` inlines to `toOne(filter($f.employees, e|$e.lastName=='Smith')).address.name`. In `SyntheticHeads.liftFilteredHeads`, the direct lift arm is gated OFF for this shape by the exclusion at SyntheticHeads.java:367-371 (`scalar read (upper==1) AND the filtered head sits directly on the lambda var`), so the filtered navigation is NOT lifted to a synthetic `#fN` join head whose predicate parks in the association join's ON clause. It falls instead to `Substitution.filteredNavLeafRead` (Substitution.java:2587+), which builds a correlated single-column relation — target pipeline filtered by the oriented association condition AND the user predicate, projecting the leaf — stamped [0..1], which the lowerer renders as a SCALAR SUBQUERY. That is exactly the observed output prefix `select (select`, where the engine emits `select "addresstable_0".NAME as "address" from firmTableNew as "root" left outer join personTable as "persontable_0" on ("root".ID = "persontable_0".FIRMID and "persontable_0".LASTNAME = 'Smith') left outer join addressTable ...`. The relational-mapper post-processor is NOT implicated: the seven sibling mapper tests pass and `SqlPostProcessors.apply` renames join/subselect sources totally.

**Fix**

Route scalar-consumed filtered navigations through the join-lift instead of the correlated subselect. (1) In core/src/main/java/com/legend/resolver/SyntheticHeads.java, drop the `&& directlyOnVar(f.source())` exclusion at line 371 (i.e. stop excluding depth-1 scalar reads) so `toOne(filter($f.employees, pred)).address.name` mints a `employees#fN` synthetic head exactly as the bare-collection spelling already does; the closed predicate then parks on the association-join target and `augmentNavPredicates`/`AssociationJoins.andCorrelatedIntoCondition` (StoreResolver.java:557-593) compose it into the ON clause — the machinery that emits the engine's shape already exists and is exercised for the [*] case. (2) Correspondingly narrow `Substitution.filteredNavLeafRead` (Substitution.java:2587-2790) so it no longer claims this shape; it should survive only where a join route genuinely cannot serve. (3) Accept the consequences the engine has: `->toOne()` must NOT impose a one-row constraint (LEFT-JOIN row explosion is the engine's observable behaviour) and no match must deliver NULL through the LEFT JOIN, not an empty subquery. Land (1)-(3) together with the U30 sibling test as the primary acceptance check. If a staged landing is wanted, gate the change on projection position first (the engine's own `state.inProjectFunctions` gate at pureToSQLQuery.pure:5124), which is sufficient for this test.

**How legend-engine does it** — The engine folds a navigation's filter into that navigation's join ON clause and reads the joined alias: `meta::relational::functions::pureToSqlQuery::moveFiltersInOnClause` at pureToSQLQuery.pure:1110-1129 rebuilds the JoinTreeNode with `operation = $f->concatenate($join.operation)->andFilters(...)`; the filter is parked for that node by `processFilter`'s `managedFilteredPosition` / `savedFilteringOperation` at pureToSQLQuery.pure:5124-5143. Goldens that pin the resulting shape (and prove `->toOne()` imposes NO single-row constraint): functions/tests/projection/testFilters.pure:97-101 — `left outer join personTable as "persontable_0" on ("root".ID = "persontable_0".FIRMID and 'Smith' = 'Smith')` returning SEVEN rows for four firms; tests/advanced/testFilterWithQualifiedProperties.pure:126-128 — the same fold in FILTER position with the comparison in the outer WHERE.

**Risk** — This is the single largest semantic switch in the resolver: every currently-passing test whose SQL contains a scalar subselect for a qualified property will change shape, and object-space `filter` results may change row counts (the engine's dedup-at-the-reader is what makes the join fold safe there). GENUINELY correlated predicates (the pred reads the parent row, #69 / CorrelatedSubselects) must keep a route — note that `augmentNavPredicates` already composes correlated preds into the join condition, so the correlated case is not an argument for keeping the scalar arm in projection position. TENET-2 TRAP: do not make this test pass by normalising or regex-rewriting the produced SQL in the corpus harness, and do not special-case the relational-mapper channel — the defect is in H-phase navigation lowering.

**Also unblocks** — `testIsolationOfFiltersWithoutAlias` (U30, functions/tests/projection/testFilters.pure:95 — currently ERRORs with 'More than one row returned by a subquery'), `testRelationalMapperTwoDBs` (this unit), and very likely the qualifier family in U34 (`testTwoQualifiersUsingSameJoinWithNoUserParams`, functions/tests/projection/testQualifier.pure:88, 'assertSize: expected 1, got 4') and U42's testQualifierWithIsolation tests — all read a `->filter(..)->toOne()` qualified property.

**Falsifier** — Re-run this test with the sweep's 400-char failure-detail truncation lifted and print the full 'got' SQL. If the produced SQL is join-shaped (contains `left outer join ... personTable ... on (... and ... LASTNAME = 'Smith')`) and differs only in table spelling or alias naming, this diagnosis is wrong and the defect lives in RelationalMapperRenames/alias naming instead. The observed prefix `select (select` already argues strongly against that.

<details><summary>Evidence read (10 citations)</summary>

- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/resolver/SyntheticHeads.java:353-371 — the lift arm requires `filterBehindToOne(pa.source()) instanceof TypedFilter` and is disabled by `!(pa.info().multiplicity() ... upper()==1 && directlyOnVar(f.source()))`; the comment states scalar depth-1 heads 'stay with the correlated-scalar arm (filteredNavLeafRead — the complementary split)'
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/resolver/SyntheticHeads.java:944-954 — `directlyOnVar` is true exactly when the filtered head is `$var.prop`, which `$f.employees` is
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/resolver/SyntheticHeads.java:493-496 — the walk's `case TypedPropertyAccess pa` recurses into the source, so the intermediate `.address` hop is where the (excluded) arm is evaluated
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/resolver/Substitution.java:2505-2513 — filteredNavLeafRead's own doc: 'Rewrites to a CORRELATED single-column relation ... which the lowerer renders as a scalar subquery in scalar position'
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/resolver/Substitution.java:2723-2745 — the hop peel (`hops`) + SubNav dispatch is what makes the `.address` hop resolvable on this arm, confirming this shape lands here
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/resolver/Substitution.java:1001-1007 — the design note itself: 'the engine LEFT JOINs with the inner filter in the ON clause and compares in the outer WHERE ... PROJECTION position keeps the scalar subquery (row-stable)'
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/lowering/SqlPostProcessors.java:219-232 — the rename walk descends Join.left/right/on and Subselect, so table renaming is not the failing element
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/plan/RelationalMapperRenames.java:317-363 — `rename` composes catalog+schema+table and disambiguates same-named schemas by model membership; it produces exactly the expected `snDBDefault.default.firmTableNew` spelling
- /Users/neemsandv/legend/legend-engine/.../core_relational/relational/tests/testModel/simpleTestModel.pure:56 — `employeeByLastName(lastName){$this.employees->filter(e|$e.lastName == $lastName)->toOne()}:Person[0..1]`
- /Users/neemsandv/.claude/jobs/5671074c/tmp/briefs/U30.md:36-43 — the sibling test `testIsolationOfFiltersWithoutAlias` (functions/tests/projection/testFilters.pure:95, the SAME `$f.employeeByLastName('Smith').address.name` shape) fails at runtime with 'More than one row returned by a subquery used as an expression' — direct proof that this shape is emitted as a scalar subquery and that the subquery is semantically wrong

</details>

---

## `testFilterMappingWithProjectionOverlappForcedCorrelated`

| | |
|---|---|
| family | `tests/advanced` |
| sweep status | FAIL |
| **verdict** | **EXECUTION-TARGET ARTIFACT** |
| effort | XS |
| confidence | high |

**Root cause**

The assert is `assertEquals(['ROOT',^TDSNull(),^TDSNull()], $result.values.rows->at(0).values)` on a query with no ORDER BY (testForced.pure:53). legend-lite's rows are right — the observed [Federation, Firm X, ROOT] is verbatim the engine's expected row 5 (testForced.pure:58) — but DuckDB returns them in a different order than H2. Mechanism: DuckDB's `build_side_probe_side` optimizer swaps build/probe for the LEFT joins in `orgTable root LEFT JOIN orgTable o1 ON root.parentId=o1.id LEFT JOIN orgTable o2 ON o1.parentId=o2.id`, so output order follows the (build-hashed) inner relation and hash-bucket chain order rather than the root scan. Working the swap through by hand for the second join puts the o1.parentId=1 bucket first ({Securities,Banking,Federation}) with in-bucket chain order reversed → Federation at index 0, exactly what was observed. legend-lite itself does not reorder: `Executor.tabular` (exec/Executor.java:511-521) appends rows in ResultSet order with no sort, and the harness never permutes before `->at(k)`. The already-present determinism settings (`SET threads=1`, `SET TimeZone='UTC'`) do not cover join side selection.

**Fix**

Pin the execution target's join-side choice in the workspace bootstrap, next to the two determinism settings that are already there: in core/src/test/java/com/legend/rcorpus/DuckWorkspaces.java, inside `open()`'s root-connection block right after `st.execute("SET threads=1")` (line 82), add `st.execute("SET disabled_optimizers='build_side_probe_side'")`. That keeps build=RHS / probe=LHS for LEFT joins, so output follows the driver-table scan order and unmatched rows stay in place — which is H2's incidental order, i.e. the order every engine expectation encodes. Nothing about the comparator, the SQL, or the semantics changes, and no assert is weakened. Update the class javadoc's settings list (DuckWorkspaces.java:59-62, the 'TimeZone is SESSION-scoped / threads is global' bullet) to name the third setting and why. Do NOT instead loosen the comparator to let `rows->at(k)` compare against the whole result as a pool: that would be exactly the tenet-2 sin and would hide genuine wrong-row defects. If the probe shows the setting is insufficient, the tenet-clean fallback is a ledger entry in docs/NOT_IMPLEMENTABLE.md classifying these as order-dependent-vacuous (the same class docs/audit-20b-wrongrows.md:70-88 already names as F2), NOT a comparator change.

**How legend-engine does it** — The order is not specified by legend-engine either — the golden SQL it asserts (/Users/neemsandv/legend/legend-engine/.../tests/advanced/testForced.pure:38) has no ORDER BY clause; the expectation is H2's incidental order, which is why this is a target difference and not a semantics difference.

**Risk** — `SET disabled_optimizers` is a DuckDB debug-facing setting; disabling the swap can slow large joins. Irrelevant at corpus scale but worth a comment. Second-order risk: some currently-PASSING tests are passing on the CURRENT (swapped) order by luck and would flip to failing under the restored order — the setting must be validated with a full-corpus sweep, not a single-test run. Tenet-2 trap to avoid: the tempting alternative fix (make `->at(k)` compare against the row pool) is harness compensation and must not be taken.

**Also unblocks** — testFilterMappingWithProjectionOverlappForcedOnClause, testSelfJoinPropertyMappingOverlap, testSelfJoinPropertyMappingWithDynaFunction (all in this unit); outside the unit: testFilterMappingWithProjectionOverlapp [tests/mapping/filter] (docs/RELATIONAL_CORPUS.md:1355, byte-identical failure text) and very likely testChainedTwoHops [tests/mapping/modelJoin] (docs/RELATIONAL_CORPUS.md:1361)

**Falsifier** — Run the engine's own golden SQL for this query verbatim on a DuckDB session with `SET threads=1` (the exact text is at testForced.pure:38) and look at row 0. If it returns 'ROOT' first, DuckDB is not reordering and legend-lite must be emitting different SQL — then this is a REAL_DEFECT in the lowering, not a target artifact. Second, cheaper: rerun the test with `SET disabled_optimizers='build_side_probe_side'`; if the order does not change, the swap is not the mechanism.

<details><summary>Evidence read (7 citations)</summary>

- /Users/neemsandv/legend/legend-engine/legend-engine-xts-relationalStore/legend-engine-xt-relationalStore-generation/legend-engine-xt-relationalStore-pure/legend-engine-xt-relationalStore-core-pure/src/main/resources/core_relational/relational/tests/advanced/testForced.pure:53 — `assertEquals(['ROOT',^TDSNull(),^TDSNull()], $result.values.rows->at(0).values);` index-addressed, and testForced.pure:58 is `['Federation','Firm X','ROOT']` — the exact tuple we got at index 0
- /Users/neemsandv/legend/legend-engine/.../tests/advanced/testForced.pure:52 — `assertEquals(6, $result.values.rows->size())` precedes the failing assert, so the row COUNT verified before the index assert failed
- core/src/main/java/com/legend/exec/Executor.java:511-521 — `tabular(...)`: `while (rs.next()) rows.addAll(shapeRow(...))`, no sort, no dedup; row order is exactly the JDBC order
- core/src/test/java/com/legend/rcorpus/DuckWorkspaces.java:82 — `st.execute("SET threads=1")` is the only determinism knob set on the root connection; DuckWorkspaces.java:97-98 adds only `USE <ws>` and `SET TimeZone='UTC'`
- core/src/main/java/com/legend/harness/EngineTestExecutor.java:2836-2848 — the documented ORDER POLICY multiset fallback fires only on whole-result compares; an `->at(0)` actual is a 3-value tuple, so the policy compares [ROOT,TDSNull,TDSNull] against [Federation,Firm X,ROOT] and correctly says false
- shell (read-only): `unzip` of ~/.m2/repository/org/duckdb/duckdb_jdbc/1.5.0.0/duckdb_jdbc-1.5.0.0.jar then `strings libduckdb_java.so_osx_universal | grep -i build_side_probe_side` → emits `build_side_probe_side`, `BUILD_SIDE_PROBE_SIDE`, `OPTIMIZER_BUILD_SIDE_PROBE_SIDE`, `disabled_optimizers` — the swap is a named, disableable optimizer in the exact DuckDB build this project runs
- docs/RELATIONAL_CORPUS.md:1361 — testChainedTwoHops: same multiset, the single unmatched (null) pair relocated inside its group; the RIGHT-join outer-flush signature

</details>

---

## `testFilterMappingWithProjectionOverlappForcedOnClause`

| | |
|---|---|
| family | `tests/advanced` |
| sweep status | FAIL |
| **verdict** | **EXECUTION-TARGET ARTIFACT** |
| effort | XS |
| confidence | high |

**Root cause**

Identical to testFilterMappingWithProjectionOverlappForcedCorrelated — the two tests call the SAME helper `testFilterMappingWithProjectionOverlapp(isolation)` (testForced.pure:46-63) and both fail on the helper's first index-addressed row assert at testForced.pure:53, before the isolation-specific `assertSameSQL` in the caller is ever reached. The failure text is byte-identical. Root cause is DuckDB's build/probe swap for LEFT joins reordering an ORDER-BY-less result; the value tuple we got at index 0 is the engine's expected row 5. Note separately (does NOT cause this failure): the `forcedIsolation` value on `^RelationalDebugContext` is structurally accepted but semantically IGNORED by the platform — `grep -rn forcedIsolation core/src/main` returns nothing but a comment in the test runner (Runner.java:747-749), so legend-lite emits the same SQL for both isolation strategies. That will make the caller's `assertSameSQL` diverge for at least one of the pair, but golden SQL is advisory when a test verifies rows too, so both tests would score sqldiff-PASS once the order is fixed.

**Fix**

Same single change as test 1: `st.execute("SET disabled_optimizers='build_side_probe_side'")` in core/src/test/java/com/legend/rcorpus/DuckWorkspaces.java immediately after line 82. No per-test work. Do NOT try to 'fix' this by implementing forcedIsolation — that is a separate, genuinely-absent surface (there is no reader of the 5th execute argument's forcedIsolation field anywhere in core/src/main) and it does not affect the rows this test asserts; implement it only if the goal is to clear the advisory sqlDiff, and file it separately alongside testForcedIsolationFilterOnTop (docs/RELATIONAL_CORPUS.md:1336).

**How legend-engine does it** — /Users/neemsandv/legend/legend-engine/.../tests/advanced/testForced.pure:43 — the MoveFilterInOnClause golden `... left outer join orgTable as "orgtable_1" on ("root".parentId = "orgtable_1".id and "orgtable_1".filterVal <= 4) ...`: also no ORDER BY, confirming the engine never pins row order here either.

**Risk** — Same as test 1. Extra: do not let the ignored-forcedIsolation observation become a reason to hard-code either golden into the emitter — the two strategies must produce different SQL from a real isolation implementation, or neither.

**Also unblocks** — Covered by the same one-line change as tests 1, 5 and 6

**Falsifier** — Same as test 1. Additionally: if after the DuckDB setting this test still FAILS while its Correlated sibling PASSES, the residual is the isolation-strategy SQL surface, not row order — check whether the runner is scoring the sqlDiff as fatal because no other assert verified.

<details><summary>Evidence read (6 citations)</summary>

- /Users/neemsandv/legend/legend-engine/.../tests/advanced/testForced.pure:41-44 — the OnClause test's body is `testFilterMappingWithProjectionOverlapp(IsolationStrategy.MoveFilterInOnClause)` then `assertSameSQL(...)`; the row asserts live in the shared helper
- /Users/neemsandv/legend/legend-engine/.../tests/advanced/testForced.pure:46-63 — the shared helper: `assertEquals(6, ...rows->size())` then six `rows->at(k).values` asserts; at(5) is `['Federation','Firm X','ROOT']`
- core/src/main/java/com/legend/exec/Executor.java:511-521 — no reordering on our side
- core/src/test/java/com/legend/rcorpus/DuckWorkspaces.java:82 — only `SET threads=1`; join-side choice unpinned
- core/src/test/java/com/legend/rcorpus/Runner.java:747-760 — `isDebugContextNew` only DETECTS `^RelationalDebugContext(forcedIsolation=...)` for let-expansion gating; grep for `forcedIsolation` across core/src/main returns zero consumers, so the strategy is not honoured
- core/src/main/java/com/legend/harness/EngineTestExecutor.java:1839-1848 — a golden-SQL assert routes to `sqlTextVerify` / advisory sqlDiffs rather than a hard row failure, so the later assertSameSQL will not block a PASS on a row-verifying test

</details>

---

## `testSimpleTypeMappingNulls` — ✅ NOW PASSES (fixed upstream; diagnosis retained for the record)

| | |
|---|---|
| family | `tests/datatype` |
| sweep status | FAIL |
| **verdict** | **REAL DEFECT** |
| effort | XS |
| confidence | high |

**Root cause**

A [0..1] property read off a to-one object (`$nullRow.tinyInt`) is resolved by StoreResolver.scalarReadAsProject → scalarMapAsProject into a single-column TypedProject aliased `u_map__<property>`. That is a RelationType root, so ResultShape.of() classifies it TABULAR and Executor.tabular() materialises the SQL NULL cell as a Java null in the row. The COLLECTION arm of the same executor already drops null cells with the correct rule ('a NULL cell is a pure EMPTY, and no pure collection holds empties'), but the TABULAR arm has no such rule, so EngineTestExecutor.Eval.values() yielded [null] where pure says [] — rendering 'got null' against 'expected []'. NOTE: this is ALREADY FIXED in the worktree under diagnosis. Commit d0f3a356 added a null-strip to Eval.values()'s Tabular branch scoped to the single `u_map__*` column channel, and the corpus doc it regenerated moved tests/datatype from 3 to 4 passing and deleted this row from the FAIL list. The brief's sweep predates that commit.

**Fix**

No further change is needed to make the test pass — d0f3a356 already does. The remaining work is a TENET-2 CORRECTION, and it is worth doing: the empty-vs-null rule currently lives in the harness (EngineTestExecutor.java:2516-2519) even though the platform owns it, and the platform already states the rule in the sibling arm (Executor.java:274-278). Move it: in `Executor.tabular(...)` (core/src/main/java/com/legend/exec/Executor.java:511-522), when the resolved schema is exactly one column whose name starts with `SqlSelect.SYNTH_MAP_COL`, drop rows whose single cell is null before building the Tabular — or, better, have `StoreResolver.scalarMapAsProject` mark that projection so `ResultShape` classifies a to-one synthetic scalar read as SCALAR/COLLECTION (which already has the rule) instead of TABULAR. Then delete the harness strip. Keep the scoping to the `u_map__*` channel exactly as d0f3a356 found empirically: a first attempt scoped by `!flatCells` regressed tds/tests by 13 and tree by 3 because genuine TDS cell reads legitimately carry TDSNulls.

**Risk** — The relocation must not widen the rule. TDS CELL reads (`$tds.rows->at(i).values`) must keep carrying TDSNull/null — the corpus asserts `[^TDSNull(),^TDSNull()]` for exactly that shape (tests/datatype/testDataTypeMapping.pure:155). d0f3a356's commit message records the regression numbers from getting this scope wrong once already.

**Also unblocks** — testMapping [tests/mapping/enumeration] and testSameTableNameDifferentSchema1 [tests/mapping/join] were fixed by the same commit (both were 'expected [], got null'-family rows removed in d0f3a356's doc hunk).

**Falsifier** — Re-run tests/datatype at worktree HEAD. If testSimpleTypeMappingNulls still reports 'expected [], got null', the d0f3a356 strip does not cover this channel and my mechanism (single `u_map__<prop>` column) is wrong — in that case dump `t.columns()` for the failing read to see the real column name/arity.

<details><summary>Evidence read (8 citations)</summary>

- /Users/neemsandv/legend/legend-engine/.../core_relational/relational/tests/datatype/testDataTypeMapping.pure:115-140 — the test body: `let nullRow = $result.values->filter(...)->toOne();` then 16 × `assertEquals([], $nullRow.<prop>)`, all properties [0..1]
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/resolver/StoreResolver.java:396-398 — `case TypedPropertyAccess pa when objectSpace(pa.source()) && !(pa.info().type() instanceof Type.ClassType) -> scalarReadAsProject(pa, context)`
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/resolver/StoreResolver.java:956-976 — `scalarMapAsProject` names the column `SqlSelect.SYNTH_MAP_COL + property` (= `u_map__tinyInt`) and returns a `TypedProject` typed `ExprType(RelationType, valueMult)`
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/exec/ResultShape.java:37-56 — a RelationType root is TABULAR unconditionally
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/exec/Executor.java:267-280 — the COLLECTION arm skips null cells with the comment 'a NULL cell is a pure EMPTY, and no pure collection holds empties'; the TABULAR arm (Executor.java:511-522, `tabular(...)`) has no equivalent
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/harness/EngineTestExecutor.java:2507-2521 — the CURRENT worktree text of Eval.values()'s Tabular branch, including the `t.columns().size() == 1 && name.startsWith("u_map__")` null-strip added by d0f3a356
- git show d0f3a356 (worktree branch) — 'Goal #18: empty-vs-null — property-read null cells are pure empty (scoped by channel)'; its docs/RELATIONAL_CORPUS.md hunk changes `| tests/datatype | 5 | 3 |` to `| 5 | 4 |` and deletes the line '- FAIL testSimpleTypeMappingNulls [tests/datatype]: assertEquals: expected [], got null'
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/docs/RELATIONAL_CORPUS.md:58 and :1339 — at worktree HEAD (regenerated at 9d1f2cd0) tests/datatype reads 5/4 passing and only testSimpleTypeMappingProjectNulls remains listed; `grep -c testSimpleTypeMappingNulls` on that file returns 0

</details>

---

## `testIsEmpty`

| | |
|---|---|
| family | `tests/mapping/embedded` |
| sweep status | FAIL |
| **verdict** | **GOLDEN TEXT ONLY** |
| effort | XS |
| confidence | high |

**Root cause**

The rows are RIGHT — both engine and legend-lite return ZERO rows (no seed row has FIRM_LEGALNAME null: the three PERSON_FIRM_DENORM inserts all carry 'Firm X'/'Firm X'/'Firm A'). The divergence is purely how a ZERO-ROW TDS renders to CSV. Engine's helper is `header + '\n' + rows->map(...)->joinStrings('', '\n', '\n')`; joinStrings emits prefix and suffix even for an empty collection, so zero rows render as 'name,firm\n' + '\n' = 'name,firm\n\n'. legend-lite's re-implementation in EngineTestExecutor.csvEquals builds `header + "\n" + lines.stream().map(l -> l + "\n").reduce("", String::concat)`, which for zero lines gives 'name,firm\n' — it models the separator+terminator per line but not joinStrings' unconditional SUFFIX. The exact compare then fails, and the multiset fallback also fails because splitting 'name,firm\n\n' on '\n' yields ["name,firm", "", ""] and the trailing-empty skip at csvEquals:3196 only forgives the LAST element, so the middle "" is treated as a data line and `pool.remove("")` fails. The failure message renders the actual as '[]' because Eval.render() of a zero-row Tabular is the empty value list.

**Fix**

In `EngineTestExecutor.csvEquals` (core/src/main/java/com/legend/harness/EngineTestExecutor.java:3160-3181) reproduce joinStrings' suffix-on-empty: change the render to `String rendered = header + "\n" + (lines.isEmpty() ? "\n" : lines.stream().map(l -> l + "\n").reduce("", String::concat));`. For N>=1 rows this is byte-identical to today (separator '\n' between + suffix '\n' at the end == one '\n' per line), so only the zero-row case changes. Do NOT loosen the multiset fallback's trailing-empty rule at :3196 to swallow interior blank lines — that would make a genuine empty data row compare equal to nothing. Optionally apply the same suffix rule to the CSVJOIN path (csvJoinedEquals) if it shares the renderer.

**How legend-engine does it** — /Users/neemsandv/legend/legend-engine/legend-engine-xts-relationalStore/legend-engine-xt-relationalStore-generation/legend-engine-xt-relationalStore-pure/legend-engine-xt-relationalStore-core-pure/src/main/resources/core_relational/relational/helperFunctions/helperFunctions.pure:208-212 — the toCSV definition whose `joinStrings('', '\n', '\n')` suffix is the whole divergence

**Risk** — Tenet-2 check: this IS harness code, but it is not harness compensation — csvEquals is legend-lite's own model of a corpus HELPER function (`meta::relational::tests::csv::toCSV`), and the fix makes that model match the helper's published definition rather than papering over a platform shape. The neighbouring sibling `testIsEmptyType` expects 'name,firm\n' for a zero-row result, which contradicts the helper; it is <<test.ToFix>> in the corpus, so do not use it as a counter-example or try to satisfy both.

**Also unblocks** — Any other zero-row `->toCSV()` golden in the corpus. None is confirmed in the 276-test failing set; do not claim a count without checking.

**Falsifier** — Print the actual Tabular's columns and rowCount for this test. If rowCount != 0, or the column names are not exactly ['name','firm'], the rows are NOT right and this is a real semantic defect in the embedded `$p.firm.legalName->isEmpty()` filter, not a rendering one.

<details><summary>Evidence read (7 citations)</summary>

- /Users/neemsandv/legend/legend-engine/.../core_relational/relational/tests/mapping/embedded/testEmbeddedMapping.pure:195-204 — the real failing test (the brief's source pointer functions/tests/testExists.pure:303 is a same-name collision; the dossier lists both fqns): `assertEquals('name,firm\n\n', $result.values->toOne()->toCSV());`
- /Users/neemsandv/legend/legend-engine/.../core_relational/relational/tests/mapping/embedded/testEmbeddedMapping.pure:335-346 — `createTablesAndFillDb` inserts exactly three rows, all with a non-null FIRM_LEGALNAME, so `where FIRM_LEGALNAME is null` yields zero rows on both targets
- /Users/neemsandv/legend/legend-engine/.../core_relational/relational/helperFunctions/helperFunctions.pure:208-212 — `toCSV`: `...->joinStrings(',') + '\n' + $t.rows->map(...)->joinStrings('', '\n', '\n')`
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/builtin/Pure.java:1408 — the 4-arg signature confirms the argument order is (strings, prefix, separator, suffix), so '' / '\n' / '\n'
- /Users/neemsandv/legend/legend-pure/legend-pure-core/legend-pure-m3-core/src/main/resources/platform/pure/anonymousCollections.pure:38 — `$this.values->map(...)->joinStrings('[', ', ', ']')` is how an EMPTY List prints '[]', i.e. prefix+suffix are emitted for an empty collection
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/harness/EngineTestExecutor.java:3160-3211 — csvEquals: `String rendered = header + "\n" + lines.stream().map(l -> l + "\n").reduce("", String::concat);` and the trailing-empty skip `if (el[i].isEmpty() && i == el.length - 1)` at :3196
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/harness/EngineTestExecutor.java:2603-2616 and :2810-2815 — the toCSV tail strips to the Tabular with csvTail=true, and compare() routes a String expected + csvTail actual into csvEquals

</details>

---

## `testProjectWithIfWhereBothSidesUseTheSameEnumMapping`

| | |
|---|---|
| family | `tests/mapping/enumeration` |
| sweep status | FAIL |
| **verdict** | **EXECUTION-TARGET ARTIFACT** |
| effort | XS |
| confidence | high |

**Root cause**

The query is `Product.all()->project([p|$p.description, p|if($p.description=='My Product 2',|$p.synonyms.type,|$p.synonyms.type)])`. legend-engine's own execution-plan golden for THIS EXACT query and mapping is `select "root".prod_desc as "description", case when "root".prod_desc = 'My Product 2' then "product_synonym_0".type else "product_synonym_0".type end as "type" from Product as "root" left outer join Product_Synonym as "product_synonym_0" on ("root".id = "product_synonym_0".product_id)` — no ORDER BY. legend-lite emits the same SQL (that golden is asserted byte-exact by the corpus test `tdsWithEnumReturn`, which PASSES in the DuckDB baseline, and PlanText renders from the same SqlQuery tree the executor runs). The three-row result is therefore order-undefined in SQL; H2 returns it product-major (11, 13, 12), DuckDB returns it synonym-major (11, 12, 13). Row index 1 is consequently (My Product 2, CUS→CUSIP) on DuckDB instead of (My Product, GSN→GS_NUMBER) on H2, which is precisely the reported diff. The multiset {(My Product,CUSIP),(My Product,GS_NUMBER),(My Product 2,CUSIP)} is identical on both engines. Nothing in legend-lite's typer, resolver, lowering, or enum decode is wrong here.

**Fix**

DO NOT FIX — keep it ledgered. The correct action is: leave legend-lite's SQL exactly as it is (it byte-matches the engine golden), leave the harness's ordered assertEquals as it is, and keep the existing entry in /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/docs/NOT_IMPLEMENTABLE.md ('Enumeration projection trio — H2 scan order over the synonym join'), strengthening its evidence line with the reproducible duckdb command rather than a prose claim. Explicitly rejected candidate 'fixes': (a) injecting an ORDER BY into the generated SQL — it would fabricate semantics the engine does not have and would immediately break the passing byte-exact plan golden `tdsWithEnumReturn` (executionPlanTest.pure:1668); (b) tuning DuckDB session settings — I probed `SET disabled_optimizers='build_side_probe_side'` and it yields a THIRD order ((My Product,GSN),(My Product 2,CUS),(My Product,CUS)), still not H2's, so it is not even a workaround; (c) special-casing these test names in the runner to downgrade FAIL→known-artifact — that is textbook harness compensation and must not be done.

**How legend-engine does it** — legend-engine's own execution-plan golden for this exact query and mapping: /Users/neemsandv/legend/legend-engine/legend-engine-xts-relationalStore/legend-engine-xt-relationalStore-generation/legend-engine-xt-relationalStore-pure/legend-engine-xt-relationalStore-core-pure/src/main/resources/core_relational/relational/executionPlan/tests/executionPlanTest.pure:1668 — `... from Product as "root" left outer join Product_Synonym as "product_synonym_0" on ("root".id = "product_synonym_0".product_id)`, with no sort. The engine's relational corpus runs only on H2 (TestDatabaseConnection(type = "H2") in the same golden, line 1671).

**Risk** — The tenet-2 trap here is severe and specifically tempting: three tests differ only by row index, so a name-keyed skip, a lenient at(N) comparator, or an unconditional ORDER BY in the emitter would all 'fix' them while destroying real signal. An unconditional ORDER BY would also regress every assertSameSQL/plan golden in the corpus. A DuckDB session-setting change would have corpus-wide blast radius and (probed) does not even produce H2's order.

**Also unblocks** — testProjectWithIfWhereOneSideIsEnumLiteral, testProjectionWithEnumThroughAssociation, and testProjectWithIfWhereOneSideIsEnumLiteral2 (the <<test.ExcludeAlloy>> twin, outside the 26-test scoreboard but listed at docs/RELATIONAL_CORPUS_ALL.md:1471) — all four are the same join, same seeds, same missing ORDER BY.

**Falsifier** — Show that the SQL legend-lite actually SENDS to DuckDB for this test differs structurally from executionPlanTest.pure:1668 — e.g. dump the executed statement and find Product_Synonym in the FROM/root position, an extra join, a subselect wrapper, or a missing left-outer. If the executed text is that golden, the diagnosis holds, because that text over these seeds provably returns the observed order on DuckDB. Secondary falsifier: run H2 2.1.214 on the same golden + seeds and find it does NOT return product-major order (11,13,12) — that would mean the expected order comes from something other than the join's scan order.

<details><summary>Evidence read (12 citations)</summary>

- /Users/neemsandv/legend/legend-engine/.../core_relational/relational/tests/mapping/enumeration/testEnumerationMapping.pure:330 — the test; asserts `$tds.rows->at(0/1/2).values` positionally after `assertSize($tds.rows, 3)`; the reported failure is the at(1) assert, so size and at(0) passed
- /Users/neemsandv/legend/legend-engine/.../tests/mapping/enumeration/testEnumerationMapping.pure:64-68 — seeds: Product(1,'My Product'),(2,'My Product 2'); Product_Synonym(11,1,'CUS'),(12,2,'CUS'),(13,1,'GSN')
- /Users/neemsandv/legend/legend-engine/.../tests/mapping/enumeration/testEnumerationMappingDomain.pure:196-213 — productDB: Table Product, Table Product_Synonym, `Join Product_ProductSynonym(Product.id = Product_Synonym.product_id)`
- /Users/neemsandv/legend/legend-engine/.../tests/mapping/enumeration/testEnumerationMappingDomain.pure:426-458 — productMapping: `ProductSynonymType: EnumerationMapping synonym { CUSIP: ['CUS'], GS_NUMBER: ['GSN'] }`, Product mapped to default.Product with `synonyms: [productDB]@Product_ProductSynonym`; no filter, no sort
- /Users/neemsandv/legend/legend-engine/.../core_relational/relational/executionPlan/tests/executionPlanTest.pure:1668 — golden SQL for this exact query/mapping (`meta::pure::executionPlan::tests::tdsWithEnumReturn`), containing `from Product as "root" left outer join Product_Synonym as "product_synonym_0" ...` and no ORDER BY
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/docs/RELATIONAL_CORPUS.md:1352 — the reported FAIL line; and `tdsWithEnumReturn` appears NOWHERE in the non-pass list of RELATIONAL_CORPUS.md → legend-lite matches that golden SQL byte-exactly
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/harness/PlanAsserts.java:174 — `if (EngineTestExecutor.compare(pe, pa, true))` — plan-text goldens are compared STRICTLY (walls become SHAPE, which would have shown in the scoreboard), so tdsWithEnumReturn's pass is a real byte match
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/plan/PlanText.java:318-332 — PlanText names `tdsWithEnumReturn` explicitly and takes the already-built `SqlQuery plan` + rendered `sql`, i.e. the plan text and the executed statement come from one tree
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/sql/dialect/EngineStyleH2.java:343 and :378-379 — the leftmost source of a Join gets alias "root"; `case SqlSource.Join j -> planSource(j.left(), leftmost); planSource(j.right(), false)`. Root class Product is the left/root table, matching the engine golden (root = Product, not Product_Synonym)
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/harness/EngineTestExecutor.java:1858 — `boolean equal = compare(e, a, /* ordered */ true)` — assertEquals is order-sensitive, faithfully mirroring the Pure assert
- LIVE PROBE (duckdb 1.4.4 CLI, /tmp/u36probe.sql): the engine's exact golden SQL over the exact seeds returns, in order: (My Product, CUS), (My Product 2, CUS), (My Product, GSN). Row 1 = (My Product 2, CUSIP) = the reported 'got' exactly
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/docs/NOT_IMPLEMENTABLE.md:31-56 — this trio is already ledgered as an H2-scan-order artifact; my independent probe reproduces the claimed DuckDB order, so the ledger entry is sound rather than relayed

</details>

---

## `testProjectWithIfWhereOneSideIsEnumLiteral`

| | |
|---|---|
| family | `tests/mapping/enumeration` |
| sweep status | FAIL |
| **verdict** | **EXECUTION-TARGET ARTIFACT** |
| effort | XS |
| confidence | high |

**Root cause**

Same join, same seeds, same missing ORDER BY as the sibling above; only the projection differs (`if($p.description == 'My Product 2', |ProductSynonymType.GS_NUMBER, |$p.synonyms.type)`). Pushing the DuckDB row order I measured — (My Product, CUS), (My Product 2, CUS), (My Product, GSN) — through that projection gives (My Product, CUSIP), (My Product 2, GS_NUMBER) [literal branch taken], (My Product, GS_NUMBER). Index 1 is therefore (My Product 2, GS_NUMBER), which is verbatim the reported 'got', while H2's product-major order puts (My Product, GS_NUMBER) there. The multiset is identical on both engines, and the CASE/if lowering plus enum decode are both correct — note the 'got' second component GS_NUMBER at index 1 is the LITERAL branch firing correctly for 'My Product 2'.

**Fix**

DO NOT FIX — same disposition as the sibling: no code change in parser/typer/resolver/lowering/dialect, no harness change. Keep the docs/NOT_IMPLEMENTABLE.md entry (it already names this test at line 33) and record the reproducible duckdb probe as its evidence. If the project ever wants these green, the only honest route is running this family against the H2 backend (`-Drcorpus.backend=h2`, Runner.java:45-52 already implements the sweep) and reporting it as a portability result — never by altering the emitted SQL or the comparator.

**How legend-engine does it** — /Users/neemsandv/legend/legend-engine/.../executionPlan/tests/executionPlanTest.pure:1668 — engine's golden for the sibling query proves the engine emits one flat left-outer join with no sort and executes it on H2 (line 1671, `connection = TestDatabaseConnection(type = "H2")`).

**Risk** — Same tenet-2 trap: the 'got' value here happens to contain GS_NUMBER, which can be misread as an enum-decode bug and 'fixed' by inverting a decode. That would be a genuine regression — the decode is correct; only the row at index 1 differs.

**Also unblocks** — testProjectWithIfWhereBothSidesUseTheSameEnumMapping, testProjectionWithEnumThroughAssociation, testProjectWithIfWhereOneSideIsEnumLiteral2

**Falsifier** — Dump the SQL legend-lite executes for this test. If it is the flat `Product as "root" left outer join Product_Synonym` with a CASE and no ORDER BY, the diagnosis holds. If instead it contains a subselect, a second join alias, an inner join, or a Product_Synonym root, then the order difference is legend-lite's own and this is a REAL_DEFECT.

<details><summary>Evidence read (10 citations)</summary>

- /Users/neemsandv/legend/legend-engine/.../tests/mapping/enumeration/testEnumerationMapping.pure:305-315 — the test body and the three positional at(i) asserts
- /Users/neemsandv/legend/legend-engine/.../tests/mapping/enumeration/testEnumerationMapping.pure:64-68 — the five seed rows that fix the join fan-out at 3 rows
- /Users/neemsandv/legend/legend-engine/.../tests/mapping/enumeration/testEnumerationMappingDomain.pure:426-458 — productMapping; the `synonym` EnumerationMapping ('CUS'→CUSIP, 'GSN'→GS_NUMBER) is the one bound to ProductSynonym.type, so the decodes seen in 'got' are the correct ones
- /Users/neemsandv/legend/legend-engine/.../executionPlan/tests/executionPlanTest.pure:1668 — the sibling query's golden SQL, same single left-outer join, no ORDER BY
- LIVE PROBE (duckdb 1.4.4): base join returns (My Product,CUS),(My Product 2,CUS),(My Product,GSN); applying this test's if() gives (My Product,CUSIP),(My Product 2,GS_NUMBER),(My Product,GS_NUMBER) — index 1 = the reported 'got' exactly
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/docs/RELATIONAL_CORPUS.md:1353 — the reported FAIL line
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/harness/EngineTestExecutor.java:1858 — assertEquals compares ordered, so a per-row positional mismatch surfaces as this message
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/docs/RELATIONAL_CORPUS.md:66 — family row `tests/mapping/enumeration | 26 | 19 | 3 | 1 | 3 | 0` (26 total, 19 pass, 3 fail, 1 error, 3 shape)
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/docs/burndown-2026-08-14/h2-backend-sweep.txt:57 — `h2-backend tests/mapping/enumeration: 21/26 pass`, i.e. +2 net over DuckDB's 19
- /Users/neemsandv/legend/legend-engine/.../tests/mapping/enumeration/testTransposeEnumrationMapping.pure:93 — `skills: EnumerationMapping skillsEnum : transpose(skills, ',')`; the H2 sweep header (h2-backend-sweep.txt:11-16) lists LIST_FILTER/UNNEST capability walls, which accounts for the one DuckDB-passing test that walls on H2 — so 19 − 1 + 3 = 21 reconciles exactly with all three order tests passing on H2

</details>

---

## `testProjectionWithEnumThroughAssociation`

| | |
|---|---|
| family | `tests/mapping/enumeration` |
| sweep status | FAIL |
| **verdict** | **EXECUTION-TARGET ARTIFACT** |
| effort | XS |
| confidence | high |

**Root cause**

Same `Product LEFT OUTER JOIN Product_Synonym`, same seeds, no ORDER BY. The three projected columns (`#/Product/synonyms/type#`, `p|$p.synonyms.type`, `p|$p.synonyms->map(s|$s.type == ProductSynonymType.CUSIP)`) all ride the SAME join instance — proven by the fact that `assertSize($tds.rows, 3)` and the at(0) assert both passed (a duplicated join would have fanned the result out beyond 3 rows). Pushing the measured DuckDB order through the projection gives row0 = synonym 11 (CUS) → (CUSIP, CUSIP, true) [passes], row1 = synonym 12 (CUS) → (CUSIP, CUSIP, true) [reported 'got'], row2 = synonym 13 (GSN) → (GS_NUMBER, GS_NUMBER, false). H2's product-major order puts synonym 13 at index 1, hence the expected (GS_NUMBER, GS_NUMBER, false). Multiset is identical: 2×(CUSIP,CUSIP,true) + 1×(GS_NUMBER,GS_NUMBER,false). The enum comparison `$s.type == ProductSynonymType.CUSIP` is also correct — it returns true exactly on the CUS rows, i.e. the literal is being encoded to the source value 'CUS' via the `synonym` EnumerationMapping, not compared against a decoded name.

**Fix**

DO NOT FIX — ledger it. No change to resolver/AssociationJoins, lowering, or dialect: the join shape, the fan-out (3 rows), the decode, and the enum-literal encoding in the `==` are all already correct and the multiset matches. Keep the docs/NOT_IMPLEMENTABLE.md 'Enumeration projection trio' entry (it names this test at line 32) and attach the reproducible duckdb probe. Note for whoever triages the master classification: this test is currently bucketed '6. WRONG ROWS / WRONG VALUE (real semantic defect)' at docs/burndown-2026-08-14/master-classification.csv:193 and the note at docs/CORPUS_BURNDOWN_INDEX.md:'the enum mapping is applied from the wrong side' is a misreading — CUSIP vs GS_NUMBER here is a different ROW, not a different decode. That CSV bucket should be corrected to the order-artifact class.

**How legend-engine does it** — /Users/neemsandv/legend/legend-engine/.../executionPlan/tests/executionPlanTest.pure:1668 — one flat `left outer join Product_Synonym as "product_synonym_0"`, single alias, no ORDER BY, H2 connection.

**Risk** — Tenet-2 trap: the 'got' here reads like an enum-decode inversion (CUSIP where GS_NUMBER expected) and invites a decode 'fix' that would break the many enumeration tests that currently pass (19/26 in this family, including testProjectionWithEnum, testProjectionWithInheritedEnum, testProjectionWithEnumQualifierParameter).

**Also unblocks** — testProjectWithIfWhereOneSideIsEnumLiteral, testProjectWithIfWhereBothSidesUseTheSameEnumMapping, testProjectWithIfWhereOneSideIsEnumLiteral2

**Falsifier** — If the executed SQL contains more than one Product_Synonym alias (one per projected column) or an inner rather than left-outer join, the ordering could be legend-lite's own doing and this becomes a REAL_DEFECT. Cheapest check: dump the executed statement and count Product_Synonym aliases — the engine golden has exactly one (`product_synonym_0`). A second, independent falsifier: if row0's assert had also failed, the multiset story would collapse; it did not.

<details><summary>Evidence read (8 citations)</summary>

- /Users/neemsandv/legend/legend-engine/.../tests/mapping/enumeration/testEnumerationMapping.pure:265-278 — the test: `assertSize($tds.rows, 3)` then three positional `$tds.rows->at(i).values` asserts; the reported failure is at(1)
- /Users/neemsandv/legend/legend-engine/.../tests/mapping/enumeration/testEnumerationMapping.pure:64-68 — seeds; product 1 has synonyms 11(CUS) and 13(GSN), product 2 has 12(CUS)
- /Users/neemsandv/legend/legend-engine/.../tests/mapping/enumeration/testEnumerationMappingDomain.pure:212 — `Join Product_ProductSynonym(Product.id = Product_Synonym.product_id)` — the single join all three columns share
- /Users/neemsandv/legend/legend-engine/.../tests/mapping/enumeration/testEnumerationMappingDomain.pure:430-448 — `ProductSynonymType: EnumerationMapping synonym { CUSIP: ['CUS'], GS_NUMBER: ['GSN'] }` and `ProductSynonym.type: EnumerationMapping synonym : type`
- LIVE PROBE (duckdb 1.4.4): the engine golden's join over these seeds returns synonym-scan order 11, 12, 13 → (CUSIP,CUSIP,true),(CUSIP,CUSIP,true),(GS_NUMBER,GS_NUMBER,false); index 1 = (CUSIP, CUSIP, true) = the reported 'got' exactly
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/docs/RELATIONAL_CORPUS.md:1354 — the reported FAIL line
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/harness/EngineTestExecutor.java:1858 — ordered assertEquals
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/sql/dialect/EngineStyleH2.java:378-379 — Join renders left then right; the root class's table is the left/root source, so legend-lite does not invert the join direction

</details>

---

## `testFilterMappingWithProjectionOverlapp`

| | |
|---|---|
| family | `tests/mapping/filter` |
| sweep status | FAIL |
| **verdict** | **EXECUTION-TARGET ARTIFACT** |
| effort | XS |
| confidence | medium |

**Root cause**

The query `Org.all()->project([#/Org/name#, #/Org/parent/name!p_name#, #/Org/parent/parent/name!p_p_name#])` carries NO sort, and the test then asserts row CONTENT by POSITION: `$result.values.rows->at(0).values` through `->at(5).values`. The preceding `assertEquals(6, $result.values.rows->size())` passed (asserts are scored in order and the reported failure is the at(0) one), so we return the right number of rows, and the observed got value [Federation, Firm X, ROOT] is verbatim the engine's expected row 5 — so the CONTENT is a permutation, not a computation error. The order divergence comes from the target: the golden plan is a chain of two LEFT OUTER JOINs (one over a filtered subselect); H2 evaluates these as order-preserving nested loops over the probe side, so rows come back in orgTable insertion order, while DuckDB uses hash joins whose output order is unspecified. legend-lite's `at(k)` over a relation lowers to `TypedSlice(k, k+1)` (LIMIT 1 OFFSET k), which faithfully picks the k-th row of whatever order DuckDB produced. Nothing in legend-lite computes a wrong value; the test's contract is unsatisfiable without an ORDER BY the query does not have.

**Fix**

DO NOT FIX in the platform — ledger it as an order artifact. Concretely: (1) do NOT add a synthetic ORDER BY to unsorted relation queries; that would change the semantics of every corpus query and mask real ordering bugs. (2) Do NOT extend the harness's order policy to positional row picks (e.g. by pooling the six `rows->at(k).values` asserts of a test and multiset-matching them) — that is textbook tenet-2 harness compensation and it would let six DIFFERENT expected tuples all match the same actual row. (3) The only defensible action is classification: record the test in the order-artifact ledger with the note 'positional row assert over an unsorted multi-join projection'. If a future project wants these green, the honest route is a DuckDB-side determinism setting evaluated on its own merits (e.g. forcing single-threaded, insertion-order-preserving joins for the corpus connection), which must be justified as a test-environment pin and measured against the whole corpus, not bolted on for three tests.

**Risk** — Do not 'fix' by weakening compare()/the order policy. Tenet-2 trap: the harness already grants one deliberate order leniency (multiset for unsorted chains) and extending it to positional indexing would silently downgrade six exact asserts into one existence check.

**Also unblocks** — testFilterMappingWithProjectionOverlappForcedCorrelated and testFilterMappingWithProjectionOverlappForcedOnClause [tests/advanced] carry the identical failure text and the identical cause; whatever is decided here applies to all three.

**Falsifier** — Dump all six actual rows (not just row 0). If the six actual tuples are NOT a permutation of the six expected tuples — e.g. if a p_name or p_p_name is wrong for some org, or a row is duplicated/missing — then this is a REAL_DEFECT in the filtered-mapping join, not an order artifact, and the whole verdict flips.

<details><summary>Evidence read (5 citations)</summary>

- /Users/neemsandv/legend/legend-engine/.../core_relational/relational/tests/mapping/filter/testFilterMappingTree.pure:49-59 — the test: no sort anywhere, `assertEquals(6, $result.values.rows->size())` then six positional `rows->at(k).values` asserts, and the golden SQL with two LEFT OUTER JOINs and no ORDER BY
- /Users/neemsandv/legend/legend-engine/.../core_relational/relational/tests/mapping/filter/testFilterMappingTree.pure:131-140 — the seed: orgTable rows inserted id 1..7 in the order ROOT, Firm X, Firm A, Securities, Banking, Federation (+ ShouldNotBeDisplayed filtered by filterVal<=4), i.e. the engine's expected order IS insertion order
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/lowering/Lowerer.java:291-310 — `at(n)` over a relation becomes `TypedSlice(n, n+1)`, a positional row pick with no ordering guarantee
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/harness/EngineTestExecutor.java:2836-2848 — the harness's documented ORDER POLICY only relaxes to a multiset when the whole collection is compared and the chain has no sort; a positional pick cannot reach it
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/docs/RELATIONAL_CORPUS.md:1331-1332 and :1355 — the two tests/advanced siblings (ForcedCorrelated, ForcedOnClause) report the byte-identical 'expected [ROOT, TDSNull, TDSNull], got [Federation, Firm X, ROOT]', which is what a shared incidental row order looks like and not what three independent computation bugs look like

</details>

---

## `testMultipleJoinsInPropertyMappingWithDatesInClass`

| | |
|---|---|
| family | `tests/mapping/join` |
| sweep status | FAIL |
| **verdict** | **REAL DEFECT** |
| effort | L |
| confidence | high |

**Root cause**

`$result.values` is not materialized. The corpus binds `let result = execute(|TypeBuiltOutOfMultipleJoinsWithDates.all(), advancedRelationalMapping3, ...)`; in real Pure `Result.values` is a stored property holding 6 already-built objects, and `$result.values.tableProperty` is an in-memory collect over those 6. legend-lite instead SPLICES the frame's chain back into a fresh query — `StatementExecutor.spliceValuesRead` returns `f.chain()` (StatementExecutor.java:2703), so the statement re-enters the resolver as `TypedPropertyAccess(TypedFrom(TypeBuiltOutOfMultipleJoinsWithDates.all()), 'tableProperty')` and is re-planned from scratch. In that re-plan only `tableProperty` is demanded, and `Pipelines.walkJoinSlot` CANCELS every join slot no column reads: `if (!demanded.contains(js.alias())) { stripped.add(js.alias()); return left; }` (Pipelines.java:405-407). The row-multiplying TypeTableB join (2 rows per TypeTable row — the un-date-filtered variant, which is exactly what distinguishes this test from its passing sibling testMultipleJoinsInPropertyMappingWithDateInJoin) is therefore stripped, and the projection collapses from 6 rows to 3. Cardinality of an already-observed object extent is being recomputed from a narrower demand set. That the extent itself is right is proved by the preceding assert: `assertSize($result.values, 6)` PASSED (it evaluates the class-rooted chain, which is GRAPH-shaped and demands all mapped properties, so the join survives) — the very next assert on `.tableProperty` returned 3.

**Fix**

The correct fix is to make a downstream read of `$result.values` observe the MATERIALIZED extent instead of re-planning. Concretely, in core/src/main/java/com/legend/StatementExecutor.java: (a) `ExecFrame` already carries `result()` from the eager run (record at :2053-2055, populated at :2278). Extend `spliceValuesRead` (:2699-2704) so that when the enclosing node is a property/collect read over a frame whose eager result is an `ExecutionResult.Graph`, the read is answered HOST-SIDE from that JSON (one value per object, in object order, empties dropped per Pure) rather than by returning `f.chain()` for re-planning. That needs a small host-side reader beside `com.legend.exec.HostEval` (a `GraphValuesRead` that parses `g.json()` and projects a property path), plus the `.values`-read recognizer in `spliceHook` (:2600-2612) routing to it. (b) If (a) is judged too large for now, the scoped alternative is: mark the spliced `TypedFrom` produced by an ExecFrame splice with an 'extent cardinality pinned' flag, and have `Pipelines.materialize` seed `demanded` with the FULL mapped-property slot set of the root class mapping when that flag is set — so join cancellation at :405-407 cannot change the extent's row count. (b) is cheaper but leaves mechanisms 3-5 of docs/CORPUS_STUDY_2026_08.md §2 alive; (a) retires the whole class. Do NOT fix this by disabling join cancellation generally — pruning is correct for genuine `project()` queries and is what makes the golden SQL match everywhere else.

**How legend-engine does it** — /Users/neemsandv/legend/legend-pure/.../platform_dsl_mapping/result.pure:17-21 defines `values:T[m]` as a stored property of a Result instance — reading it cannot re-plan a query. The engine's own golden for this test (testMappingAssociationToAdvancedJoin.pure:132) shows the extent SQL retains the row-multiplying TypeTableB join.

**Risk** — Option (a) changes how every `$r.values.<prop>` assert in the corpus is answered — a large blast radius that must be swept, not spot-checked; GRAPH JSON must carry every mapped property (it does, since the extent is a full class read) or reads will silently miss properties. Option (b) will INCREASE row counts on other fused reads that currently pass by accident on the pruned cardinality; those passes were wrong, but the sweep will look like a regression until each is inspected. Tenet-2 trap: do not 'fix' this by teaching the harness to duplicate rows or to compare `.tableProperty` as a set — the cardinality is platform-owned.

**Also unblocks** — Option (a) is the single fix for the whole 'execute() is fused, not materialized' family named in docs/CORPUS_STUDY_2026_08.md §2 (five mechanisms, including the connection post-processor table-replace loss and the stale chainContext mapping dispatch); it also subsumes the harness-side null-drop discussed under testSameTableNameDifferentSchema1.

**Falsifier** — Run the test with `LL_TMP_SQL=1` (StatementExecutor.java:3215-3218 dumps every executed statement). If the SQL emitted for the `$result.values.tableProperty` statement DOES contain the `TypeTableB` join and still returns 3 rows, the join-cancellation diagnosis is wrong and the loss is downstream (dedup or a distinct). Conversely if it is a bare `select ... from TypeTable`, the diagnosis holds. Second falsifier: if `assertSize($result.values, 6)` is actually being scored UNSUPPORTED rather than PASS, then the extent may also be 3 and the defect is one stage earlier — check the runner's per-assert counters.

<details><summary>Evidence read (9 citations)</summary>

- /Users/neemsandv/legend/legend-engine/.../tests/mapping/join/testMappingAssociationToAdvancedJoin.pure:125-127 — `assertSize($result.values, 6);` then `assertSameElements(['Row1','Row2','Row3','Row1','Row2','Row3'], $result.values.tableProperty);` — the size assert precedes the failing one, so the 6-object extent verified
- /Users/neemsandv/legend/legend-engine/.../tests/mapping/join/testMappingAssociationToAdvancedJoin.pure:132 — the engine golden keeps the un-filtered TypeTableB join: `... left outer join TypeTableB as "typetableb_0" on ("root".ID = "typetableb_0".ID)` with IN_Z/OUT_Z selected as columns, i.e. 6 rows by construction
- core/src/main/java/com/legend/resolver/Pipelines.java:405-407 — `if (!demanded.contains(js.alias())) { stripped.add(js.alias()); return left; }  // JOIN CANCELLED: nothing reads through it`
- core/src/main/java/com/legend/StatementExecutor.java:2699-2704 — `spliceValuesRead`: `ExecFrame f = valuesFrame(n, execFrames); if (f != null) { return f.chain(); }` — the `.values` read is replaced by the raw typed chain, not by the eagerly-computed result
- core/src/main/java/com/legend/StatementExecutor.java:2274-2280 — buildFrame's eager arm already RAN the frame (`run = executeTyped(body, env)`) and stores it in `ExecFrame.result`, but the splice path at 2703 returns `chain()` and discards `result()`
- core/src/main/java/com/legend/StatementExecutor.java:335-348 — the spliced statement is re-resolved (`new StoreResolver(...).resolve(body, ...)`) and re-executed; there is no path that reads the stored ExecFrame result for a `.values.<prop>` read
- core/src/main/java/com/legend/harness/EngineTestExecutor.java:158-176 — `carrierSizeCheck` for `assertSize($r.values, n)` counts `av.size()`, which for the GRAPH-shaped extent parses the JSON array (Eval.size(), EngineTestExecutor.java:2492-2495) — so the 6 is a real observation of the object extent
- /Users/neemsandv/legend/legend-pure/legend-pure-dsl/legend-pure-dsl-mapping/legend-pure-m2-dsl-mapping-pure/src/main/resources/platform_dsl_mapping/result.pure:17-21 — `Class meta::pure::mapping::Result<T|m> { values:T[m]; activities:Activity[*]; }` — `values` is a STORED property on a materialized instance, not a re-runnable query handle
- docs/CORPUS_STUDY_2026_08.md:270 — the prior study names this same mechanism and the same test; I re-derived and confirmed it against Pipelines.java rather than relaying it

</details>

---

## `testSameTableNameDifferentSchema1` — ✅ NOW PASSES (fixed upstream; diagnosis retained for the record)

| | |
|---|---|
| family | `tests/mapping/join` |
| sweep status | FAIL |
| **verdict** | **REAL DEFECT** |
| effort | S |
| confidence | high |

**Root cause**

A Pure collection holds no empties, so `$result.values.extraInformation` over 7 Persons of which 2 have no schema-B match must yield 5 values. legend-lite yielded 7 with two nulls. Mechanism: the read is spliced (StatementExecutor.java:2703) into `TypedPropertyAccess(TypedFrom(Person.all()), 'extraInformation')`, the resolver's scalar-read arm (StoreResolver.java:396-398 → scalarReadAsProject :631-661 → scalarMapAsProject :961-976) turns it into a ONE-COLUMN relation projection, and so the resolved root's type is a `Type.RelationType`. `ResultShape.of` therefore classifies it TABULAR (ResultShape.java:38-40), and the null-drop that encodes the Pure rule lives ONLY in the COLLECTION arm (`if (v != null) values.add(v)` with the comment 'a NULL cell is a pure EMPTY, and no pure collection holds empties', Executor.java:270-281). The guard that exists precisely for this case — `collectionDeclared`, StatementExecutor.java:3209-3225 — is dead here because `declaredInfo` is only captured while peeling a TypedFrom at the ROOT (StatementExecutor.java:2999-3005), and the splice puts the TypedFrom IN-CHAIN under the property access, so `declaredInfo` stays null. IMPORTANT STATUS CORRECTION: this test already PASSES at HEAD. The brief's failure text is byte-identical to the scoreboard at commit 651b2c6d and the fix landed one commit later in d0f3a356 ('empty-vs-null — property-read null cells are pure empty (scoped by channel)'), which added `out.removeIf(Objects::isNull)` to the harness Eval Tabular arm scoped to a single `u_map__*` column (EngineTestExecutor.java:2513-2519). `git show 9d1f2cd0:docs/RELATIONAL_CORPUS.md | grep testSameTableNameDifferentSchema1` returns nothing, and the tests/mapping/join row reads 28/25 pass/1 fail/2 error. So the brief's sweep predates the fix.

**Fix**

Do not add more code to make this test pass — it already passes. The work is to MOVE the rule out of the harness and into the platform, because the current fix is a scoped duplicate of a platform-owned invariant (Executor.java:270-281 already states the rule) living in EngineTestExecutor.Eval.values(). Concretely, in core/src/main/java/com/legend/StatementExecutor.java: capture the pre-resolution root type where it is already in hand — `preRoot` at :195-199 (and `chain` at :2270 in buildFrame) — and thread it into `executeTyped` as an explicit `declaredInfo` parameter (an overload; the existing root-TypedFrom peel at :2999-3005 becomes the fallback when the caller passes none). The `collectionDeclared` test at :3209-3216 then fires for the spliced `TypedPropertyAccess(TypedFrom(...), prop)` shape exactly as it already does for the `...->from(mapping)`-at-root shape, ResultShape.COLLECTION is selected, and Executor's null-drop runs in the platform. Then DELETE the `u_map__` special case at EngineTestExecutor.java:2513-2519 and re-sweep: if the tds/tests and tree families stay at 248 and 10 (the numbers d0f3a356's message records), the harness compensation is gone with no loss. Note this becomes unnecessary if the materialization fix under testMultipleJoinsInPropertyMappingWithDatesInClass (option a) lands first — a host-side read of the materialized extent applies Pure's empty-drop directly.

**How legend-engine does it** — /Users/neemsandv/legend/legend-pure/.../support/CompiledSupport.java:183-189 — `toPureCollection(T object)` maps null to the empty collection, which is the exact rule ('no Pure collection holds empties') that the TABULAR path bypasses. /Users/neemsandv/legend/legend-pure/.../platform_dsl_mapping/result.pure:17-21 — `values:T[m]` is materialized, so the read is an in-memory flatten, never a projection.

**Risk** — Widening `collectionDeclared` changes the result SHAPE for every spliced primitive-many read; anything currently relying on the TABULAR presentation of such a read (e.g. a `$r.values.prop` compared against a TDS-shaped expectation) will change. docs/audit-20b-wrongrows.md:88-100 (F3) lists three probe rows for this exact channel — use them as the regression fence. Tenet-2: the CURRENT state is itself the trap — a platform invariant re-implemented in the harness and keyed on a synthetic column-name marker. Do not extend that marker to more shapes; move it.

**Also unblocks** — docs/audit-20b-wrongrows.md F3's whole auto-map class (`Firm.all().employees.name`, `Person.all().nick`, and the `!=`-null compounding case); and testSameTableNameDifferentSchema2 shares the identical shape

**Falsifier** — Run this single test at HEAD. If it FAILS with the 7-value text, then d0f3a356's `u_map__` scoping does not reach this chain (e.g. the projection is not single-column, or the column is not named `u_map__extraInformation`) and the mechanism above is wrong at the last step — dump the Tabular column names to see. Also re-check that the harness `Eval` receives a Tabular at all and not a Collection.

<details><summary>Evidence read (14 citations)</summary>

- /Users/neemsandv/legend/legend-engine/.../tests/mapping/join/testMappingAssociationToAdvancedJoin.pure:279-280 — `assertEquals($result.values.firstName, [7 names]); assertEquals($result.values.extraInformation, ['Peter B','John B','John B','Anthony B','Oliver B']);` — 7 persons, 5 extraInformation values, i.e. two empties dropped
- core/src/main/java/com/legend/harness/EngineTestExecutor.java:1850-1858 — `Eval e = eval(args.get(0), ...)` is the EXPECTED side, so the 7-with-nulls list in the failure text is OUR value and the 5-element list is the corpus literal (the failure message reads backwards relative to the Pure source, which passes actual-first)
- core/src/main/java/com/legend/exec/Executor.java:268-281 — the COLLECTION arm: `if (v != null) { values.add(v); }` under the comment 'a NULL cell is a pure EMPTY, and no pure collection holds empties'
- core/src/main/java/com/legend/exec/Executor.java:250-251 — `case TABULAR -> tabular(rs, plan, rootType, dialect)`; Executor.java:511-521 shows the tabular arm has no null filtering
- core/src/main/java/com/legend/exec/ResultShape.java:37-40 — `if (root.type() instanceof Type.RelationType) return TABULAR;`
- core/src/main/java/com/legend/StatementExecutor.java:2999-3005 — `while (root instanceof TypedFrom fr) { if (declaredInfo == null) declaredInfo = fr.info(); ... }` — captures the pre-resolution declared type ONLY from a root-position TypedFrom
- core/src/main/java/com/legend/StatementExecutor.java:3209-3225 — `collectionDeclared` = declaredInfo is Primitive && many && resolved root is RelationType → forces ResultShape.COLLECTION; unreachable when declaredInfo is null
- core/src/main/java/com/legend/StatementExecutor.java:2703 — `return f.chain();` — the splice returns the frame's TypedFrom-wrapped chain, so any read stacked on top leaves TypedFrom in-chain
- core/src/main/java/com/legend/resolver/StoreResolver.java:396-398 — `case TypedPropertyAccess pa when objectSpace(pa.source()) && !(pa.info().type() instanceof Type.ClassType) -> scalarReadAsProject(pa, context);` and Anchors.java:97 `case TypedFrom fr -> spaceOf(fr.source()) == Space.OBJECT` confirms the arm fires through the spliced TypedFrom
- core/src/main/java/com/legend/resolver/StoreResolver.java:961-976 — scalarMapAsProject builds a one-column `TypedProject` named `SqlSelect.SYNTH_MAP_COL + property`, i.e. `u_map__extraInformation` (SqlSelect.java:37)
- core/src/main/java/com/legend/harness/EngineTestExecutor.java:2508-2519 — the CURRENT harness workaround: `if (t.columns().size() == 1 && t.columns().get(0).name().startsWith("u_map__")) out.removeIf(Objects::isNull);`
- git show d0f3a356 (message + stat) — 'The Eval Tabular branch now drops null cells for the map-binder property-read channel ONLY (single u_map__* column)'; touches only EngineTestExecutor.java and docs/RELATIONAL_CORPUS.md
- git show 651b2c6d:docs/RELATIONAL_CORPUS.md — contains the brief's exact line `FAIL testSameTableNameDifferentSchema1 ... expected [Peter B, John B, John B, Anthony B, Oliver B, null, null], got [...]`; git show 9d1f2cd0:docs/RELATIONAL_CORPUS.md contains no such line and shows tests/mapping/join as 28/25/1/2
- /Users/neemsandv/legend/legend-pure/legend-pure-runtime/legend-pure-runtime-java-engine-compiled/src/main/java/org/finos/legend/pure/runtime/java/compiled/generation/processors/support/CompiledSupport.java:183-189 — `toPureCollection(T object) { if (object == null) return Lists.immutable.empty(); ... }` — the ground-truth rule: a null [0..1] value contributes NOTHING when flattened into a collection

</details>

---

## `testChainedTwoHops`

| | |
|---|---|
| family | `tests/mapping/modelJoin` |
| sweep status | FAIL |
| **verdict** | **REAL DEFECT** |
| effort | M |
| confidence | medium |

**Root cause**

NULL PLACEMENT in a DESCENDING sort. The expected and actual row MULTISETS are identical — (Apple,null), (Apple,ProjectY), (Apple,ProjectX), (Google,ProjectZ) — and the test makes the order deterministic with `->sort([~'Legal Name'->ascending(), ~'Project Name'->descending()])`, so the only difference is where the null lands within the Apple group: engine puts it FIRST, legend-lite puts it LAST. Engine's reference semantics for this sort is the in-memory relation sort (the sort is applied AFTER execute(), to a materialised TabularDataSet): TestTDS.sortOneLevel sorts ascending with `Comparators.safeNullsHigh(...)` — nulls compare HIGH — and then, for DESC, simply REVERSES the list, so nulls-high ascending becomes nulls-FIRST descending. legend-lite instead splices the post-execute sort back onto the query chain and lowers it to a SQL ORDER BY with NO nulls clause (`Fold.sortNulls` returns null by policy), so DuckDB's `default_null_order` applies — NULLS LAST in BOTH directions. ASC therefore agrees (both nulls-last) and DESC diverges. legend-lite's own H2 dialect comment records exactly this: DuckDB is nulls-last in both directions.

**Fix**

Scope a nulls-HIGH pin to POST-EXECUTE relation sorts only. (1) Add a field to `TypedSort` (core/src/main/java/com/legend/compiler/spec/typed/TypedSort.java) — e.g. `boolean hostNullOrder` defaulting false — threaded through its `withChildren`. (2) In `StatementExecutor.spliceHook` (core/src/main/java/com/legend/StatementExecutor.java:2431-2613), when the hook rewrites a node whose source resolves through an ExecFrame `.values` splice (the same condition `spliceValuesRead` already detects at :2698-2724), rebuild any enclosing `TypedSort` with `hostNullOrder = true`: that flag records the fact 'the engine evaluates this in memory, we are pushing it into SQL, so we owe the in-memory comparator's semantics'. (3) In `Lowerer.sort` (:1596) and `Lowerer.sortOnto` (:1633), use `s.hostNullOrder() ? (k.ascending() ? SqlSelect.SortKey.NullOrder.NULLS_LAST : SqlSelect.SortKey.NullOrder.NULLS_FIRST) : Fold.sortNulls(k.ascending())`. Leave `Fold.sortNulls` returning null for every in-query sort — that policy is correct and was arrived at empirically. Do NOT key the scope on the `ascending`/`descending` spelling instead: CoreFn.ASC/DESC already collapse asc↔ascending (CoreFn.java:52-55), and even if that signal were recovered in SortChecker.sortInfo it would also change relation sorts INSIDE execute(), which engine pushes to SQL and which the H2.java note says regressed when DESC-FIRST was pinned there.

**How legend-engine does it** — /Users/neemsandv/legend/legend-engine/legend-engine-core/legend-engine-core-pure/legend-engine-pure-code-functions-relation/legend-engine-pure-runtime-java-extension-shared-functions-relation/src/main/java/org/finos/legend/pure/runtime/java/extension/external/relation/shared/TestTDS.java:695-699 — `Comparators.safeNullsHigh` + `list.reverseThis()` for DESC is the mechanism that puts nulls first in a descending relation sort

**Risk** — A GLOBAL pin is known-bad: H2.java:267-273 records that pinning the window convention's DESC-FIRST for ordinary sorts made DESC-sorted tds/groupBy chains lead with the null group, and Fold.java:334-343 records that an earlier ASC→FIRST pin broke five engine sort/groupBy pins. Keep the flag strictly on the post-execute splice. Also check the golden-SQL surface: EngineStyleH2.sortKey (:1222-1242) suppresses a placement equal to H2's default, so a DESC→NULLS_FIRST key would now render ' nulls first' in engine-style text — verify no `$result->sql()` golden covers a post-execute sort (it should not, since the golden is captured from the execute() chain).

**Also unblocks** — Unknown. Any corpus test that applies a DESC relation sort to an already-executed TDS over a column containing TDSNull would be unblocked; none other is confirmed in the failing set, so do not claim a count.

**Falsifier** — Dump the executed SQL for this test's final assert. If it already contains an explicit `NULLS FIRST`/`NULLS LAST` on the 'Project Name' key, the diagnosis is wrong (the placement is being set somewhere I did not find). Conversely, running the same ORDER BY with `NULLS FIRST` appended on the DESC key must produce Apple/TDSNull, Apple/ProjectY, Apple/ProjectX, Google/ProjectZ — if it does not, the null is not the only ordering difference.

<details><summary>Evidence read (8 citations)</summary>

- /Users/neemsandv/legend/legend-engine/.../core_relational/relational/tests/mapping/modelJoin/testModelJoinAdvanced.pure:188-254 — testChainedTwoHops; the assert sorts `[~'Legal Name'->ascending(), ~'Project Name'->descending()]` and pins the order 'Apple, TDSNull' / 'Apple, ProjectY' / 'Apple, ProjectX' / 'Google, ProjectZ' — null FIRST inside the descending group
- /Users/neemsandv/legend/legend-engine/legend-engine-core/legend-engine-core-pure/legend-engine-pure-code-functions-relation/legend-engine-pure-runtime-java-extension-shared-functions-relation/src/main/java/org/finos/legend/pure/runtime/java/extension/external/relation/shared/TestTDS.java:687-700 — `sortOneLevel`: `list.sortThis(Comparators.bySecondOfPair(Comparators.safeNullsHigh(...)))` then `if (sortInfo.direction == SortDirection.DESC) { list.reverseThis(); }` — nulls high, DESC by reversal ⇒ ASC nulls last, DESC nulls first
- /Users/neemsandv/legend/legend-engine/legend-engine-core/.../relation/compiled/RelationNativeImplementation.java:550-555 — the compiled `sort` native routes straight to `TestTDSCompiled.sort(...)`, i.e. this comparator is what a post-execute relation sort actually runs
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/lowering/Fold.java:334-346 — `sortNulls(boolean ascending)` returns null unconditionally; the comment records that a global pin was tried, bought nothing, and broke five engine sort/groupBy pins
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/lowering/Lowerer.java:1584-1600 and :1625-1636 — `sort`/`sortOnto` build every SortKey with `Fold.sortNulls(k.ascending())`, i.e. no NULLS clause
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/sql/dialect/H2.java:267-281 — 'H2 sorts null SMALLEST (NULLS FIRST ascending), the reference target NULLS LAST in BOTH directions (DuckDB default_null_order — witnessed…)' — the authority for DuckDB's default, and the record that pinning the WINDOW convention's DESC-FIRST globally broke DESC-sorted tds/groupBy chains
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/lowering/Lowerer.java:1991-2004 — `lowerOver` already pins ASC→NULLS_LAST / DESC→NULLS_FIRST for WINDOW order, so the exact convention and the IR plumbing (SqlSelect.SortKey.NullOrder) already exist
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/compiler/spec/SortChecker.java:34-41 and CoreFn.java:52-55 — both the modern relation sort and the legacy TDS string sort desugar into the SAME TypedSort node, and CoreFn conflates asc/ascending, so the node carries no signal for scoping today

</details>

---

## `testDateTimeInclusiveRangeQuery`

| | |
|---|---|
| family | `tests/mapping/relation` |
| sweep status | FAIL |
| **verdict** | **HARNESS GAP** |
| effort | S |
| confidence | high |

**Root cause**

Two facts combine. (1) Both engines return ONE row here, not two. The filter is `%2014-12-04T15:22:23.123456789 <= $i.settlementDateTime && $i.settlementDateTime < %2014-12-04T23:59:59.999999999`. In this corpus's fixture, trade 7 is seeded as '2014-12-04 15:22:23.123' — MILLISECONDS, unlike the classic relationalSetUp.pure which seeds '…123456789'. legend-engine renders a DateTime literal at FULL sub-second precision (`convertDateToSqlString` → `format('%t{[GMT]yyyy-MM-dd HH:mm:ss.SSSSSS}')`, and legend-pure's DateFormat appends the WHOLE subsecond string once the S-run length is ≥3), so the predicate is `'2014-12-04 15:22:23.123456789' <= …` and trade 7 (.123) fails it. Only trade 6 (21:00:00) survives → 1 row, the same as legend-lite. The golden's two rows were copied from the millisecond-precision `dates.pure` analogue and are dead text. (2) The reason it is dead text: the engine's `assertTdsEquivalent` is `$oneCol->size()==$twoCol->size() && $oneCol.name==$twoCol.name && $one->size()==$two->size() && if(<per-column compare>, |true, |fail(...))`. A ROW-COUNT mismatch short-circuits the `&&` to `false` BEFORE `fail(...)` is ever reached, so nothing is thrown — and the engine's test runners (`PureTestBuilder.runTest` and `PureTestHelperFramework.PureTestCase.runTest`) merely INVOKE the function and discard its return value, failing only on a thrown exception. A `<<test.Test>>` function returning `false` therefore PASSES upstream. legend-lite's `TdsEquivalence.compare` instead returns the failure string "assertTdsEquivalent: expected N cells, got M" on a size mismatch, which is strictly stronger than the assert the corpus actually makes. legend-lite is NOT computing wrong rows here; its harness is over-asserting.

**Fix**

Make `core/src/main/java/com/legend/harness/TdsEquivalence.java` reproduce the engine's Pure definition exactly instead of a flat cell compare. Restructure `compare` to take the two TDS shapes (column names + rows) rather than a flattened cell list, and return NO failure (null) when column count, column NAMES, or ROW COUNT differ — that is the engine's non-throwing `false` branch. Only when all three agree does the per-column comparison run, and only a per-cell mismatch produces a failure string (the current cell logic, which is correct). To avoid silently swallowing genuine row-count regressions, have the harness emit a non-fatal note (e.g. a `PASS(assert-vacuous: N vs M rows)` annotation on the result record) so the divergence stays visible on the scoreboard without inventing a failure the corpus does not assert. Do NOT change the DateTime literal rendering to millisecond precision — legend-lite already agrees with the engine's SQL here, and truncating would break the `dates.pure` family.

**How legend-engine does it** — /Users/neemsandv/legend/legend-engine/legend-engine-core/legend-engine-core-testable/legend-engine-test-framework/src/main/java/org/finos/legend/engine/test/shared/framework/PureTestHelperFramework.java:341-362 — the runner only fails on a thrown exception, so `assertTdsEquivalent`'s `false` return is a pass.

**Risk** — This is the tenet-2 danger zone: relaxing an assert in the harness looks exactly like harness compensation. The distinction that makes it legitimate is that `assertTdsEquivalent` is a Pure PLATFORM function whose body legend-lite's harness is implementing, and the current implementation is stricter than the function it claims to implement — the corpus never asserts row-count equality here. Do NOT generalise the relaxation to `assertEquals`, `assertSameElements`, or `assertSize`, all of which DO throw. Keep the divergence visible via the non-fatal note so this cannot mask a future row-count regression.

**Also unblocks** — Nothing else inside core_relational — `assertTdsEquivalent` appears exactly twice in the whole core_relational tree, at tests.pure:1220 and tests.pure:1240. Outside core_relational the same fix would apply to PCT/relation-function suites that use it heavily.

**Falsifier** — Run the engine's `Test_Relational_UsingPureClientTestSuite` (it collects `meta::relational::tests::mapping::relation`) for this one test with the generated SQL logged. If the engine's SQL contains `TIMESTAMP'2014-12-04 15:22:23.123'` rather than `…123456789`, then the engine really does return 2 rows, the golden is live, and legend-lite has a literal-precision defect instead of a harness gap.

<details><summary>Evidence read (11 citations)</summary>

- /Users/neemsandv/legend/legend-engine/.../core_relational/relational/tests/mapping/relation/tests.pure:1220-1237 — the query and the two-row golden with `assertTdsEquivalent(…, 0, 1)`.
- /Users/neemsandv/legend/legend-engine/.../core_relational/relational/tests/mapping/relation/relationMappingSetup.pure:1336-1350 — trade 6 = '2014-12-04 21:00:00', trade 7 = '2014-12-04 15:22:23.123' (three fractional digits only).
- /Users/neemsandv/legend/legend-engine/.../core_relational/relational/tests/relationalSetUp.pure:1301 — the CLASSIC fixture seeds the same trade as '2014-12-04 15:22:23.123456789'; the relation fixture deliberately differs, which is what makes the copied golden unreachable.
- /Users/neemsandv/legend/legend-engine/.../core_relational/relational/sqlQueryToString/extensionDefaults.pure:144-156 — `convertDateToSqlString` uses `yyyy-MM-dd HH:mm:ss.SSSSSS` for any date with a subsecond.
- /Users/neemsandv/legend/legend-engine/.../core_relational/relational/sqlQueryToString/dbSpecific/h2/h2Extension2_1_214.pure:153-155 — H2 reuses the same `convertDateToSqlString` transform (`TIMESTAMP'%s'`), so no dialect truncation happens.
- /Users/neemsandv/legend/legend-pure/legend-pure-core/legend-pure-m4/src/main/java/org/finos/legend/pure/m4/coreinstance/primitive/date/DateFormat.java:216-244 and 671-679 — for an S-run of length ≥3 the formatter appends `date.getSubsecond()` VERBATIM (all nine digits); `getCharCountFrom` counts the characters after the first, so `SSSSSS` gives count 5 ≥ 3.
- /Users/neemsandv/legend/legend-engine/legend-engine-core/legend-engine-core-pure/legend-engine-pure-code-functions-relation/legend-engine-pure-functions-relation-pure/src/main/resources/core_functions_relation/relation/functions/tdsEquivalent.pure — `assertTdsEquivalent/4`: `… && $one->size() == $two->size() && if(<fold>, |true, |fail(...))`; `fail` is unreachable when the sizes differ.
- /Users/neemsandv/legend/legend-pure/legend-pure-core/legend-pure-m3-core/src/main/java/org/finos/legend/pure/m3/execution/test/PureTestBuilder.java:108-122 — `runTest()` calls `this.executor.value(this.coreInstance, …)` and ignores the result.
- /Users/neemsandv/legend/legend-engine/legend-engine-core/legend-engine-core-testable/legend-engine-test-framework/src/main/java/org/finos/legend/engine/test/shared/framework/PureTestHelperFramework.java:341-362 — the Alloy runner's `runTest()` does `method.invoke(null, this.executionSupport)` and only rethrows an InvocationTargetException; the returned Boolean is never inspected.
- core/src/main/java/com/legend/harness/TdsEquivalence.java:47-52 — `if (expected.size() != got.size()) return "assertTdsEquivalent: expected " + … + " cells, got " + …;` — the over-assertion.
- core/src/main/java/com/legend/harness/EngineTestExecutor.java:1980-1985 — the `assertTdsEquivalent` arm IS wired for arity 3 and 4, so the ledger line at docs/CORPUS_BURNDOWN_INDEX.md:221 ("not in the assert vocabulary") is stale.

</details>

---

## `testDateTimeRetrieveWithTimeZone`

| | |
|---|---|
| family | `tests/mapping/relation` |
| sweep status | FAIL |
| **verdict** | **REAL DEFECT** |
| effort | S (revised up from XS by adversarial review) |
| confidence | high |
| **adversarial review** | **CONFIRMED** |

**Root cause**

`TdsChecker.annotatedType` maps the explicit TDS column annotation `Date` to `Type.Primitive.STRICT_DATE` (`case "Date", "StrictDate" -> Type.Primitive.STRICT_DATE;`). In Pure, `Date` is the ABSTRACT supertype of StrictDate and DateTime — a `Date`-typed TDS column legitimately holds full date-times. The test's expected literal header is `id:Integer[1], settlementDateTime:Date[1]` with cells like `2016-02-05T21:00:00.123+0000`. Because the column types as STRICT_DATE, `Scalars.tdsCell` takes the `type == Type.Primitive.STRICT_DATE` arm and emits `SqlExpr.DateLit(cell)` → `DATE '2016-02-05T21:00:00.123+0000'`; DuckDB's lenient date parse keeps only the date part, so the expected cell becomes 2016-02-05T00:00. The actual value from the trade row is 2016-02-05T21:00:00.123456789. `TdsEquivalence.compare` then takes the temporal arm, epochSeconds differ by 75600s, and the tolerance is 1s → FAIL. The same file already treats an INFERRED zone-suffixed timestamp correctly — `TdsChecker.inferredType` line 208-209 returns `Type.Primitive.DATE` for `\d{4}-\d{2}-\d{2}T…([+-]\d{4}|Z)` — so the annotated path directly contradicts the inferred path, and only the annotated path is broken. `Scalars.tdsCell` already has a correct `Type.Primitive.DATE` arm (TimestampLit with +0000/Z stripped and sub-second truncated to 6 digits), so the type mapping is the whole defect.

**Fix**

In `core/src/main/java/com/legend/compiler/spec/TdsChecker.java:148`, split the case: `case "StrictDate" -> Type.Primitive.STRICT_DATE;` and `case "Date" -> Type.Primitive.DATE;` (leave `"DateTime" -> Type.Primitive.DATE_TIME`). Companion change required for safety: in `core/src/main/java/com/legend/lowering/Scalars.java:2942-2955`, make the `Type.Primitive.DATE` arm value-polymorphic the way Pure's `Date` is — if the (%-stripped) cell matches `\d{4}-\d{2}-\d{2}` with no time component, emit `SqlExpr.DateLit`; otherwise the existing `TimestampLit` path. Without that companion change a `col:Date[1]` column holding date-only cells would start rendering `2014-12-04 00:00:00` in `->toString()` comparisons. No harness change is needed: `TdsEquivalence.epochSeconds` already handles LocalDateTime.

**How legend-engine does it** — /Users/neemsandv/legend/legend-pure/legend-pure-dsl/legend-pure-dsl-tds/legend-pure-m2-dsl-tds-grammar/src/main/java/org/finos/legend/pure/m2/inlinedsl/tds/TDSExtension.java:255-266 — a declared column type is copied verbatim (`GenericType.copyGenericType`); only an ABSENT declared type falls through to `convertType`, whose DATETIME_AS_LONG arm returns `M3Paths.Date` (line 309-312). `Date` and `StrictDate` are distinct M3 types.

<details><summary>Adversarial review notes (CONFIRMED)</summary>

Every link checked and held. TdsChecker.java:148 is verbatim `case "Date", "StrictDate" -> Type.Primitive.STRICT_DATE;`, and TdsChecker.java:68 routes every annotated header cell through it (with the `[1]` multiplicity suffix stripped), so settlementDateTime:Date[1] types STRICT_DATE. Scalars.tdsCell then takes the STRICT_DATE arm (`new SqlExpr.DateLit(cell…)`) and AnsiSqlRenderer.dateLit renders `DATE '2016-02-05T21:00:00.123+0000'` (no DuckDb override of dateLit exists — only EngineStyleH2 overrides it). Lowerer.tdsLiteral:1638-1658 confirms the expected TDS really is materialised as a SQL VALUES relation, so the expected cell comes back from the DB as a date — matching the observed 'cell 1 expected 2016-02-05, got 2016-02-05 21:00:00.123456789' (the got side's toString is java.sql.Timestamp's). TdsEquivalence.compare's temporal arm and epochSeconds are as cited, tolerance 1s, so a date-vs-21:00 gap fails. The inferred path (TdsChecker ~208-209) does return Type.Primitive.DATE for the identical cell shape, so the two paths genuinely disagree, and TDSExtension.java:249-266/295-330 confirms a DECLARED column type is copied verbatim (convertType/DATETIME_AS_LONG→Date applies only when no type is declared) — a declared `Date` must stay abstract Date, never StrictDate. The fix lands: with Type.Primitive.DATE the existing arm strips `+0000` and emits TIMESTAMP '2016-02-05T21:00:00.123', which DuckDB returns as a timestamp; compared against the actual 2016-02-05T21:00:00.123456789 the epoch gap is 0.0005s, inside the 1s tolerance — and this holds regardless of which zone convention each JDBC carrier uses, since both sides then use the same carrier. Blast radius is the one thing I could not fully clear: annotatedType is shared by every #TDS literal, so all `col:Date[1]` columns change from DATE to TIMESTAMP literals. Within the relational corpus the only other consumer is testDateTimeInclusiveRangeQuery (tests.pure:1230), already failing for an unrelated reason ('expected 2 cells, got 1'); outside it, PCT composition.pure:1979/2020/2041/2070 and tdsEquivalent.pure:88-111 use `:Date[1]` with zone-suffixed cells, which the change also converts. That is a re-sweep, not a redesign.

</details>

**Citation issues found in review** — Minor line drift only: the STRICT_DATE arm in Scalars.java is at 2940-2942, cited as 2939-2941 (2939 is the closing brace of the BOOLEAN arm); the DATE/DATE_TIME arm is 2943-2955, cited as 2942-2955. Content at both is exactly as described. No substantive citation is wrong.

**Risk** — Any TDS literal in the corpus annotated `col:Date[1]` whose cells are date-only would change from a DATE literal to a TIMESTAMP literal — hence the companion guard in `Scalars.tdsCell`. Risk is small in core_relational: a grep of the whole core_relational tree for `:Date[` in TDS headers finds exactly the two headers in tests.pure:1230 and tests.pure:1250, i.e. only these two tests. PCT/relation-function suites use `ts:Date[1]` with zone-suffixed cells and would be helped, not hurt. Note the stale ledger line at docs/CORPUS_BURNDOWN_INDEX.md:221 claiming this also needs `[m]`-suffix stripping — that stripping already exists at TdsChecker.java:68-69.

**Also unblocks** — Any test whose TDS literal declares `col:Date[1]` (or `Date[0..1]`) with a time-bearing cell — inside core_relational that is only these two tests, but the engine's own relation-function PCT fixtures (tdsEquivalent.pure, composition.pure) use the same shape.

**Falsifier** — If DuckDB rejects rather than truncates `DATE '2016-02-05T21:00:00.123+0000'`, the test would have ERRORed rather than reported `expected 2016-02-05`; the observed message therefore already confirms the truncation. The remaining falsifier: if the expected cell is actually produced by a different path (not `Scalars.tdsCell`), the printed expected value would not be exactly the date part — grep the produced VALUES clause for `DATE '2016-02-05T…'`.

<details><summary>Evidence read (9 citations)</summary>

- /Users/neemsandv/legend/legend-engine/.../core_relational/relational/tests/mapping/relation/tests.pure:1240-1260 — `assertTdsEquivalent(#TDS id:Integer[1], settlementDateTime:Date[1] / 12, 2016-02-05T21:00:00.123+0000 / … #, $result…->sort(~id->ascending()), 0, 1)`.
- core/src/main/java/com/legend/compiler/spec/TdsChecker.java:148 — `case "Date", "StrictDate" -> Type.Primitive.STRICT_DATE;` — the defect.
- core/src/main/java/com/legend/compiler/spec/TdsChecker.java:199-210 — the INFERRED path for the identical cell shape returns `Type.Primitive.DATE` with the comment "the mapped M3 type is Date, not DateTime (TDSExtension.convertType)" — the two paths disagree.
- core/src/main/java/com/legend/lowering/Scalars.java:2939-2941 — STRICT_DATE arm: `return new SqlExpr.DateLit(cell…)`, i.e. the full `2016-02-05T21:00:00.123+0000` text is handed to a DATE literal.
- core/src/main/java/com/legend/sql/dialect/AnsiSqlRenderer.java:730-732 — `dateLit` renders `DATE '<iso>'`; DuckDB's non-strict date parse yields 2016-02-05, matching the observed "expected 2016-02-05".
- core/src/main/java/com/legend/lowering/Scalars.java:2942-2955 — the DATE / DATE_TIME arm already does the right thing (strip `+0000|Z`, truncate 7-9 fractional digits to 6, emit `TimestampLit`).
- core/src/main/java/com/legend/harness/TdsEquivalence.java:64-76 and 81-92 — the temporal arm compares `epochSeconds` within `timeDeltaSeconds` (=1 here); a LocalDate 2016-02-05 vs a 21:00 LocalDateTime is 75600s apart.
- /Users/neemsandv/legend/legend-pure/legend-pure-dsl/legend-pure-dsl-tds/legend-pure-m2-dsl-tds-grammar/src/main/java/org/finos/legend/pure/m2/inlinedsl/tds/TDSExtension.java:249-266 and 295-330 — `convertType` (DATETIME_AS_LONG → M3Paths.Date) is used ONLY when the header column carries no declared type; a declared type is copied verbatim, so a declared `Date` stays the abstract `Date`, never `StrictDate`.
- /Users/neemsandv/legend/legend-engine/legend-engine-core/legend-engine-core-pure/legend-engine-pure-code-functions-relation/legend-engine-pure-functions-relation-pure/src/main/resources/core_functions_relation/relation/functions/tdsEquivalent.pure — the engine's own `assertTdsEquivalent` unit tests (`testAssertTdsEquivalentWithTimeDelta`, `testAssertTdsEquivalentMixedCols`) declare `ts:Date[1]` with `2026-01-07T00:00:00.000+0000` cells and expect millisecond-level comparison, confirming a declared `Date` column carries time.

</details>

---

## `testMappingWithWindowColumn`

| | |
|---|---|
| family | `tests/mapping/relation` |
| sweep status | FAIL |
| **verdict** | **REAL DEFECT** |
| effort | M |
| confidence | high |

**Root cause**

The mapping's `~func` pipeline (`personFunctionWithJoinAndWindowColumn` = personTable ⋈ groupMembershipTable then `extend(over(~GROUPID, ~SALARY->ascending()), ~[RANK:{p,w,r|$p->rank($w,$r)}])`) lowers into ONE SqlSelect: `Lowerer.extendWindow` folds the window projections onto the join select because `Fold.windowFolds(src)` is true (no distinct/limit/offset/groupBy). The query's own `filter(x|$x.age > 25)` is then composed onto that SAME select: `Lowerer.filter` computes `Fold.filterSlot(src, windowRef=false)` — the predicate reads AGE, not the RANK window column, so `windowRef` is false; `src` has no limit/offset/distinct/groupBy, so the slot is WHERE. SQL evaluates WHERE strictly before window functions in the same SELECT, so RANK() is computed over the AGE>25 rows. Peter (age 23, salary 14.34, GROUPID 1) disappears before ranking, so John (72.40) becomes rank 1 instead of 2. Groups 3 and 4 are unaffected because the row dropped there (Anthony, 64.90) is the LAST in the ordering, which is exactly why only John's cell differs. The `Fold.containsWindow` doc comment (Fold.java:243-252) already states the correct doctrine — an ordinary predicate SHOULD fold to WHERE over a window-carrying select in plain relation composition, and the isolation is required only at the MAPPING seam where the engine treats the mapped relation as a non-mergeable view, "that isolation is the RESOLVER's decision (windowed ~func pipelines), never a fold rule". Nothing in `core/src/main/java/com/legend/resolver/` implements it: a grep for isolate/barrier/non-mergeable across the resolver returns only two unrelated comment hits. The doctrine is written and unimplemented.

**Fix**

Implement the mapping-seam barrier the doctrine already names. (a) Add a unary marker node `TypedIsolate(TypedSpec source, ExprType info)` under `core/src/main/java/com/legend/compiler/spec/typed/`, and add it to the `permits` list in `TypedSpec.java` (the sealed interface at lines 17-83); `children()` = List.of(source), `withChildren` rebuilds. (b) In `ClassSources.java` at the seam (lines 807-814, immediately before `return new ClassSource(...)`): walk the resolved `pipeline`'s relation spine and, if it contains a `TypedExtendWindow` or `TypedExtendAgg` that is not already below a materialising boundary (`TypedLimit`/`TypedDrop`/`TypedSlice`/`TypedDistinct`/`TypedGroupBy`), wrap `pipeline = new TypedIsolate(pipeline, pipeline.info())`. (c) In `Lowerer`'s relation dispatch add `case TypedIsolate i -> isolate(relation(i.source()))` — reusing the existing `isolate(SqlSelect)` at Lowerer.java:3466. (d) Add pass-through arms wherever the generic relation-op switches enumerate unary relation nodes: `StoreResolver.resolveNode` (the `case Typed… when anchored(x.source()) -> structural(x, context)` block around lines 430-452) and `Substitution`'s relation arm. Do NOT touch `Fold.filterSlot` — the FoldTest pin and testExtendFilterOutNull both require the current WHERE fold. Equivalent alternative placement for the trigger (b): the resolver site that composes a user `TypedFilter` onto a class extent, which is where the seam is crossed; the marker node in (a)/(c) is required either way.

**How legend-engine does it** — The corrected behaviour matches the engine's ordinary treatment of a class extent as a nested select: e.g. /Users/neemsandv/legend/legend-engine/.../tests/mapping/classMappingFilterWithInnerJoin/testClassMappingFilterWithInnerJoin.pure:76-78, whose golden SQL is `select ... from (select ... from personTable as "root" inner join firmTable ... ) as "root" where "root".AGE is not null and "root".AGE > 20` — the mapped extent is a subselect and the query filter sits in the OUTER where.

**Risk** — Adding a subselect changes generated SQL text, so any `assertSameSQL`/`assertEquals`-on-SQL golden touching a windowed `~func` mapping would shift; within core_relational only WindowColumnMapping uses this shape and its only test is a row assert. Tenet-2 trap: do NOT make `Fold.filterSlot` isolate over any window-carrying select — that is the already-reverted 63a68804 fix and it regresses PCT testExtendFilterOutNull and FoldTest.java:48-58. Also do not "fix" this by pre-sorting or re-ranking in the harness.

**Falsifier** — Dump the generated SQL for `ExtendedPerson.all()->filter(x|$x.age>25)->project(...)` under WindowColumnMapping. If the RANK() OVER (...) projection and the `AGE > 25` predicate are NOT in the same SELECT (i.e. the mapped relation already renders as its own subselect), this diagnosis is wrong and the rank difference has another cause — most likely the ordering key SALARY resolving to NULL.

<details><summary>Evidence read (14 citations)</summary>

- /Users/neemsandv/legend/legend-engine/legend-engine-xts-relationalStore/legend-engine-xt-relationalStore-generation/legend-engine-xt-relationalStore-pure/legend-engine-xt-relationalStore-core-pure/src/main/resources/core_relational/relational/tests/mapping/relation/tests.pure:200-217 — `ExtendedPerson.all()->filter(x|$x.age > 25)->project(~[name,groupName,rank])` against WindowColumnMapping; golden `John, Group A, 2`.
- /Users/neemsandv/legend/legend-engine/.../core_relational/relational/tests/mapping/relation/relationMappingSetup.pure:1045-1050 — `personFunctionWithJoinAndWindowColumn()` = personTable ->join(groupMembershipTable, INNER, ID==PERSONID) ->extend(over(~GROUPID, ~SALARY->ascending()), ~[RANK:{p,w,r|$p->rank($w,$r)}]).
- /Users/neemsandv/legend/legend-engine/.../relationMappingSetup.pure:1211-1235 — seed rows: Peter(1,23,salary 14.34) and John(2,30,72.40) are both GROUPID 1, so rank(John)=2 only if Peter survives to the window.
- /Users/neemsandv/legend/legend-engine/.../relationMappingSetup.pure:545-555 — `WindowColumnMapping ... *ExtendedPerson[person]: Relation { ~func personFunctionWithJoinAndWindowColumn ... rank: RANK }`.
- core/src/main/java/com/legend/lowering/Fold.java:227-238 — `filterSlot` returns WHERE whenever there is no limit/offset/distinct, no window REFERENCE in the predicate, and no groupBy; it never inspects the select's own projections for a WindowCall.
- core/src/main/java/com/legend/lowering/Fold.java:243-252 — the doctrine comment: "...the MAPPING seam, where the engine treats the mapped relation as a non-mergeable view — that isolation is the RESOLVER's decision (windowed ~func pipelines), never a fold rule."
- core/src/main/java/com/legend/lowering/Fold.java:330-332 — `windowFolds` allows the window to fold onto the join select (only distinct/limit/offset/groupBy block), producing the single fused SELECT.
- core/src/main/java/com/legend/lowering/Lowerer.java:1225-1247 — `filter` calls `Fold.filterSlot(src, windowRef)` and, for WHERE, does `src.withWhere(mergeWhere(...))` on the very select that carries the window projections.
- core/src/main/java/com/legend/lowering/Lowerer.java:1942-1948 — `extendWindow`: `SqlSelect base = Fold.windowFolds(src) ? src : isolate(src);` then folds the window columns in.
- core/src/main/java/com/legend/resolver/ClassSources.java:801-819 — the mapping seam: the extracted `~func` pipeline is (optionally) re-resolved and handed straight into `new ClassSource(..., pipeline, ...)` with no isolation marker; `anchoredInFlow` is the only hook here.
- core/src/main/java/com/legend/normalizer/MappingNormalizer.java:952-971 — `synthRelationFunction` emits `<fn body> -> map(row|^Class(...))`, i.e. the mapping pipeline and the query ops end up in one composed chain with nothing marking the seam.
- /Users/neemsandv/legend/legend-engine/legend-engine-core/legend-engine-core-pure/legend-engine-pure-code-functions-relation/legend-engine-pure-functions-relation-pure/src/main/resources/core_functions_relation/relation/tests/composition.pure:1115-1157 — PCT `testExtendFilterOutNull`: `extend(over(~p), ~newCol:{p,w,r|$r.i}:y|$y->plus())->filter(x|$x.o->isNotEmpty())`; golden newCol for p=100 is 60 = 10+20+30 (the two null-o rows' i values, 20 and 30, are excluded), proving the engine DOES fold the filter under the window in plain relation composition. A blanket window guard in `filterSlot` would regress this.
- core/src/test/java/com/legend/lowering/FoldTest.java:48-58 — the existing unit test PINS `FilterSlot.WHERE` over a window-carrying select, citing testExtendFilterOutNull; a blanket fix goes red here.
- docs/CORPUS_TAXONOMY.md:291-293 — "Do not re-attempt C1.1 inside `Fold.filterSlot`. The `63a68804` revert was correct... fixing it in `filterSlot` regresses PCT `testExtendFilterOutNull`. It belongs at `resolver/ClassSources.java` `anchoredInFlow`."

</details>

---

## `testMixedMappingWithFilterInProject`

| | |
|---|---|
| family | `tests/mapping/relation` |
| sweep status | FAIL |
| **verdict** | **TESTS ENGINE INTERNALS** |
| effort | XS |
| confidence | high |

**Root cause**

Same mechanism and same numbers as testSimpleMappingQueryWithFilterInProject — the two are the identical query run against a different mapping, and both produce the identical expected/actual pair in the sweep. MixedMapping maps Person via `~func personFunctionWithProject` (`PersonWithFirmId.all()->filter(x|$x.age > 25)->project(~['FIRST NAME',AGE,FIRMID])`, where PersonWithFirmId is a plain Relational set over personTable), Firm via a plain Relational set over firmTable, and joins them with the SAME XStore `Person_Firm` self-association. The AGE>25 filter yields the same four persons (John 30/firm1, Fabrice 45/firm4, Oliver 26/firm4, David 52/firm5), so the correct isolated-filter answer is again David/TDSNull, Fabrice/Oliver, John/John, Oliver/Oliver — legend-lite's output — while the golden is reproduced exactly by evaluating `age < 35` against the ROOT person inside the employees join ON. That the golden is byte-identical across the two mappings (Relation+Relation vs Relation+Relational) confirms the divergence rides on the shared XStore self-association, not on the class-mapping kind. The engine's own acknowledgement of the mechanism is testMergeRules.pure:52-53 (JoinTreeNodes that do not isolate merge, leaking the filter criteria onto the projected to-many), on a function that carries `test.ToFix`.

**Fix**

Do not fix; ledger with its sibling as one scoreboard exception. Any change that would satisfy this golden requires replicating legend-engine's JoinTreeNode merge alias collision — an invented defect. See the sibling entry for the (rejected) shape such a change would take.

**How legend-engine does it** — /Users/neemsandv/legend/legend-engine/.../core_relational/relational/pureToSQLQuery/tests/testMergeRules.pure:52-59.

**Risk** — Same as the sibling: do not relax the row assert in the harness and do not mis-bind predicates in the resolver to chase this golden.

**Also unblocks** — testSimpleMappingQueryWithFilterInProject (same root cause).

**Falsifier** — Same probe as the sibling, against MixedMapping: `toSQLString(|Person.all()->project(~[name1:x|$x.firstName, name2:x|$x.firm.employees->filter(e|$e.age < 35).firstName]), MixedMapping, DatabaseType.H2, relationalExtensions())`. An isolated employees subselect carrying `age < 35` falsifies the diagnosis.

<details><summary>Evidence read (7 citations)</summary>

- /Users/neemsandv/legend/legend-engine/.../core_relational/relational/tests/mapping/relation/tests.pure:179-197 — the MixedMapping test: identical query, identical five-row golden as the SimpleMapping sibling.
- /Users/neemsandv/legend/legend-engine/.../relationMappingSetup.pure:515-544 — `MixedMapping`: Person is `Relation { ~func personFunctionWithProject }`, PersonWithFirmId and Firm are `Relational`, and `Person_Firm: XStore { employees[firm, person]: $this.id == $that.firmId, firm[person, firm]: $this.firmId == $that.id }` — the same self-association as SimpleMapping.
- /Users/neemsandv/legend/legend-engine/.../relationMappingSetup.pure:1020-1031 — `personFunctionWithProject()` = `PersonWithFirmId.all()->filter(x|$x.age > 25)->project(~['FIRST NAME':…, AGE:…, FIRMID:…])`, giving the same four-person extent as `personFunction()`.
- /Users/neemsandv/legend/legend-engine/.../relationMappingSetup.pure:1211-1225 — the seed rows behind the arithmetic above.
- /Users/neemsandv/legend/legend-engine/.../core_relational/relational/pureToSQLQuery/tests/testMergeRules.pure:52-59 — the engine's own description of the non-isolating JoinTreeNode merge, marked `test.ToFix`.
- /Users/neemsandv/legend/legend-engine/.../core_relational/relational/tests/mapping/classMappingFilterWithInnerJoin/testClassMappingFilterWithInnerJoin.pure:82-85 — the engine's correct, isolated form of the same construct, which is what legend-lite produces.
- /Users/neemsandv/.claude/jobs/5671074c/tmp/briefs/U33.md:15 and :23 — the two tests report byte-identical expected and actual strings, confirming one shared mechanism.

</details>

---

## `testSimpleMappingQueryWithFilterInProject`

| | |
|---|---|
| family | `tests/mapping/relation` |
| sweep status | FAIL |
| **verdict** | **TESTS ENGINE INTERNALS** |
| effort | XS |
| confidence | high |

**Root cause**

The golden is not derivable from the Pure semantics of `filter`; it is reproducible ONLY by binding the inner predicate to the OUTER (root) person. Data: `personFunction()` = personTable filtered AGE>25 limit 5 → John(30, firm 1 'Firm X'), Fabrice(45, firm 4 'Firm C'), Oliver(26, firm 4), David(52, firm 5 'Firm D'). Correct semantics for `x|$x.firm.employees->filter(e|$e.age < 35).firstName`: John→{John}; Fabrice→firm C employees {Fabrice 45, Oliver 26} filtered → {Oliver}; Oliver→{Oliver}; David→{} → TDSNull. That is EXACTLY legend-lite's output [David,null, Fabrice,Oliver, John,John, Oliver,Oliver]. Now evaluate the alternative "the `age < 35` predicate sits in the employees LEFT JOIN's ON clause but references the ROOT person's AGE": John(30) passes → John; Fabrice(45) fails → no ON match → TDSNull; Oliver(26) passes → both firm-C employees {Fabrice, Oliver}; David(52) fails → TDSNull. Sorted: David/null, Fabrice/null, John/John, Oliver/Fabrice, Oliver/Oliver — the golden, cell for cell, including the extra fifth row. legend-engine documents exactly this class of defect: testMergeRules.pure:52-53 says "neither the filter or project Expression isolate and so their JoinTreeNodes' maintain their original JoinName, subsequently they will Merge during the final Merge step and so the employees filter criteria applied by the filter is also applied to the project employees which is not correct" — and that test carries `test.ToFix`. Here the merge collides the root Person extent with the `employees` extent because, under a Relation (`~func`) class mapping plus an XStore self-association (Person_Firm: employees[firm,person] / firm[person,firm]), both ends are the SAME mapped relation. Independent corroboration that the engine's INTENDED behaviour is the isolated one: testClassMappingFilterWithInnerJoin.pure:82-85, whose golden SQL for the same construct puts the predicate inside a nested joined subselect (`… where "unionalias_3"."…lastName…" like 'Sc%') as "unionalias_2" on (…)`), i.e. the filter applies to the employees, not the root. legend-lite computes the isolated/correct rows. Matching this golden would require replicating an engine JoinTreeNode-merge alias collision.

**Fix**

Do not fix. Ledger it as a scoreboard exception: the golden encodes legend-engine's JoinTreeNode merge defect (acknowledged with `test.ToFix` on its sibling in testMergeRules.pure but NOT marked on this one), and legend-lite's rows are the semantically correct isolated-filter result that the engine itself produces on the non-Relation path. If the project decides bug-compatibility is required, the change would be in the resolver's association-navigation emission — deliberately failing to isolate a filtered to-many navigation when both association ends resolve to the same mapped relation, and rebinding the predicate's row variable to the outer extent — which is an invented defect and should be rejected under the tenets.

**How legend-engine does it** — /Users/neemsandv/legend/legend-engine/.../core_relational/relational/pureToSQLQuery/tests/testMergeRules.pure:52-59 — the engine's own comment naming the merge defect, and the `test.ToFix` stereotype on the function that exhibits it.

**Risk** — The trap here is 'fixing' this in the harness by loosening the row comparison, or in the resolver by deliberately mis-binding a predicate. Both are worse than the failing row. Leave the wall loud.

**Also unblocks** — testMixedMappingWithFilterInProject (identical query, identical golden, same XStore self-association).

**Falsifier** — Run legend-engine's own `toSQLString(|Person.all()->project(~[name1:x|$x.firstName, name2:x|$x.firm.employees->filter(e|$e.age < 35).firstName]), SimpleMapping, DatabaseType.H2, relationalExtensions())`. If the emitted `age < 35` predicate is inside an isolated employees subselect (not on the root alias / in the employees join ON), then the golden is NOT an alias collision and legend-lite has a genuine row defect.

<details><summary>Evidence read (7 citations)</summary>

- /Users/neemsandv/legend/legend-engine/.../core_relational/relational/tests/mapping/relation/tests.pure:91-108 — the query and golden (David/TDSNull, Fabrice/TDSNull, John/John, Oliver/Fabrice, Oliver/Oliver).
- /Users/neemsandv/legend/legend-engine/.../relationMappingSetup.pure:1006-1011 — `personFunction()` = testDB.personTable ->filter(x|$x.AGE > 25) ->limit(5).
- /Users/neemsandv/legend/legend-engine/.../relationMappingSetup.pure:427-448 — `SimpleMapping`: Person and Firm are both `Relation` sets, joined by `Person_Firm: XStore { employees[firm, person]: $this.id == $that.firmId, firm[person, firm]: $this.firmId == $that.id }` — a self-association through one mapped relation.
- /Users/neemsandv/legend/legend-engine/.../relationMappingSetup.pure:1211-1225 — seed rows giving John 30/firm1, Fabrice 45/firm4, Oliver 26/firm4, David 52/firm5 and firms 1='Firm X', 4='Firm C', 5='Firm D'.
- /Users/neemsandv/legend/legend-engine/.../core_relational/relational/pureToSQLQuery/tests/testMergeRules.pure:52-59 — the engine's own acknowledgement of the defect class, on a `<<test.Test, test.ToFix>>` function: JoinTreeNodes that do not isolate MERGE and the filter criteria leaks onto the projected to-many.
- /Users/neemsandv/legend/legend-engine/.../core_relational/relational/tests/mapping/classMappingFilterWithInnerJoin/testClassMappingFilterWithInnerJoin.pure:82-85 — the CORRECT engine form for `$p.firm.employees->filter(...)` in a project: the predicate is inside an isolated joined subselect, producing TDSNull for non-matching roots.
- docs/CORPUS_STUDY_2026_08_ALL.md:188-190 — legend-lite's own prior study reached the same reconstruction independently: "the golden is reproduced exactly by applying the filter to the outer person: the classic self-association alias collision. Carries no ToFix marker — needs a scoreboard exception, not a classification."

</details>

---

## `testSelfJoinPropertyMappingOverlap`

| | |
|---|---|
| family | `tests/mapping/selfJoin` |
| sweep status | FAIL |
| **verdict** | **EXECUTION-TARGET ARTIFACT** |
| effort | XS |
| confidence | high |

**Root cause**

Same as tests 1 and 2. The assert is `assertEquals(['ROOT',^TDSNull(),^TDSNull()], $result.values.rows->at(0).values)` (selfJoin.pure:52) on `Org.all()->project([#/Org/name#, #/Org/parent/name!p_name#, #/Org/parent/parent/name!p_p_name#])`, whose golden SQL (selfJoin.pure:62) is a plain chained LEFT JOIN with no ORDER BY. The tuple we returned at index 0, [Federation, Firm X, ROOT], is verbatim the engine's expected row 5 (selfJoin.pure:57). Both preceding asserts passed — `assertSameElements(['name','p_name','p_p_name'], $tds.columns.name)` (:50) and `assertEquals(9, $tds.rows->size())` (:51) — so column identity and cardinality are correct and only the permutation differs. Decisive corroboration that the JOIN SEMANTICS are right: the sibling `testSelfJoinPropertyMapping` (selfJoin.pure:27-44) runs the same two-level self-join over the same 9 rows and PASSES — because it asserts with `assertSameElements` over pairs, which is order-insensitive. docs/RELATIONAL_CORPUS.md:82 records tests/mapping/selfJoin as 3 tests / 1 pass / 2 fail, and the two failures are exactly the two index-addressed ones. Mechanism of the permutation is DuckDB's build_side_probe_side swap, worked through above.

**Fix**

Covered entirely by the one-line change in core/src/test/java/com/legend/rcorpus/DuckWorkspaces.java after line 82: `st.execute("SET disabled_optimizers='build_side_probe_side'")`. No platform change is warranted: the rows, the column names, the count and the join semantics are all already correct, proven by the passing order-insensitive sibling. Explicitly do NOT emit an ORDER BY to stabilise the order — the golden SQL asserted at selfJoin.pure:62 has none, and adding one would break the SQL contract to satisfy an incidental one.

**How legend-engine does it** — /Users/neemsandv/legend/legend-engine/.../tests/mapping/selfJoin/selfJoin.pure:62 — the engine's own asserted SQL contains no ORDER BY, so legend-engine does not define this order either; the expectation is H2's scan order.

**Risk** — Same as test 1: currently-passing tests that pass on the swapped order would flip; the setting needs a full-corpus sweep. Tenet-2 trap: do not relax the comparator for `rows->at(k)`.

**Also unblocks** — Same one-line change also covers tests 1, 2 and 6 in this unit

**Falsifier** — Execute the golden SQL at selfJoin.pure:62 verbatim against DuckDB with `SET threads=1` and inspect row 0. 'ROOT' first falsifies the target-artifact reading and points at our emitter; 'Federation' first confirms it. Cheaper still: rerun with the disabled_optimizers setting and see whether index 0 becomes ROOT.

<details><summary>Evidence read (7 citations)</summary>

- /Users/neemsandv/legend/legend-engine/.../tests/mapping/selfJoin/selfJoin.pure:50-57 — `assertSameElements(['name','p_name','p_p_name'], $tds.columns.name); assertEquals(9, $tds.rows->size());` precede the failing `rows->at(0)` assert; :57 is `assertEquals(['Federation','Firm X','ROOT'], $result.values.rows->at(5).values)` — the exact tuple we got at index 0
- /Users/neemsandv/legend/legend-engine/.../tests/mapping/selfJoin/selfJoin.pure:62 — golden SQL: `... from orgTable as "root" left outer join orgTable as "orgtable_1" on ("root".parentId = "orgtable_1".id) left outer join orgTable as "orgtable_2" on ("orgtable_1".parentId = "orgtable_2".id)` — no ORDER BY anywhere
- /Users/neemsandv/legend/legend-engine/.../tests/mapping/selfJoin/selfJoin.pure:27-44 — testSelfJoinPropertyMapping, same store/mapping/data, asserts with assertSameElements; docs/RELATIONAL_CORPUS.md:82 shows selfJoin 3/1 pass and docs/RELATIONAL_CORPUS.md:1373-1374 names the two failures as exactly the index-addressed pair — so the passing one is this order-insensitive sibling
- /Users/neemsandv/legend/legend-engine/.../tests/mapping/selfJoin/selfJoin.pure:110-125 — the seed inserts ids 1..9 in name order ROOT, Firm X, Firm A, Securities, Banking, Federation, Banking_c1, Banking_c2, Banking_c1_c1; the expectation is exactly that insertion order, i.e. H2's incidental scan order
- core/src/main/java/com/legend/exec/Executor.java:511-521 — no reordering on our side
- core/src/test/java/com/legend/rcorpus/DuckWorkspaces.java:82 — determinism knobs stop at `SET threads=1`
- shell (read-only) strings of the shipped duckdb_jdbc 1.5.0.0 native lib — `build_side_probe_side` / `disabled_optimizers` present

</details>

---

## `testSelfJoinPropertyMappingWithDynaFunction`

| | |
|---|---|
| family | `tests/mapping/selfJoin` |
| sweep status | FAIL |
| **verdict** | **EXECUTION-TARGET ARTIFACT** |
| effort | XS |
| confidence | high |

**Root cause**

Same mechanism as test 5, on a four-deep self-join. The assert is `assertEquals(['ROOT', ^TDSNull(), ^TDSNull(), true], $result.values.rows->at(0).values)` (selfJoin.pure:81). What we returned at index 0 — [Banking_c1_c1, Firm X, ROOT, false] — is character-for-character the engine's expected row 8 (selfJoin.pure:89: `assertEquals(['Banking_c1_c1','Firm X','ROOT', false], ...rows->at(8).values)`). That is a strong extra signal beyond ordering: it means the three-hop and four-hop parent navigations AND the dyna-function boolean `$o.parent.parent.parent.parent.name->isEmpty() && $o.parent.parent.parent.name->isEmpty()` all evaluate correctly, including the SQL-null-to-isEmpty mapping (false for the deepest node). The two asserts before it also passed: the golden-SQL assert at :80 (routed advisory) and `assertSameElements(['orgName','3rd parent','4th parent','dyna operation'], $tds.columns.name)` / `assertEquals(9, $tds.rows->size())` at :82-83. Only the row permutation differs, caused by DuckDB's build_side_probe_side swap across the four chained LEFT joins.

**Fix**

Covered by the same one-line change in core/src/test/java/com/legend/rcorpus/DuckWorkspaces.java after line 82: `st.execute("SET disabled_optimizers='build_side_probe_side'")`. No platform change: the four-hop navigation and the dyna boolean are demonstrably correct on the one row we can see. If after the setting this test still fails on a DIFFERENT row index, that residual is a genuine per-row defect and must be re-diagnosed from the new failure text — do not assume it is still order.

**How legend-engine does it** — /Users/neemsandv/legend/legend-engine/.../tests/mapping/selfJoin/selfJoin.pure:80 — engine's own asserted SQL, no ORDER BY; the row order is H2 incidental.

**Risk** — Same as test 1. Additional honesty note: because only ONE row of this test's nine has been observed, a residual per-row defect (rows 6/7) cannot be excluded by static analysis; the order fix may reveal it rather than close the test.

**Also unblocks** — Same one-line change as tests 1, 2 and 5

**Falsifier** — Run the golden SQL at selfJoin.pure:80 on DuckDB with `SET threads=1` and check row 0. If it returns ROOT first, our emitter is producing different SQL and this becomes a REAL_DEFECT. Also worth one extra check unique to this test: after the order is stabilised, verify rows 6 and 7 (`['Banking_c1','ROOT',TDSNull,false]`, `['Banking_c2','ROOT',TDSNull,false]`) — those are the only tuples whose correctness we have NOT observed.

<details><summary>Evidence read (7 citations)</summary>

- /Users/neemsandv/legend/legend-engine/.../tests/mapping/selfJoin/selfJoin.pure:81 — `assertEquals(['ROOT', ^TDSNull(),^TDSNull(), true], $result.values.rows->at(0).values);`
- /Users/neemsandv/legend/legend-engine/.../tests/mapping/selfJoin/selfJoin.pure:89 — `assertEquals(['Banking_c1_c1','Firm X', 'ROOT', false], $result.values.rows->at(8).values);` — identical to the tuple we produced at index 0, so every projected value including the boolean is correct
- /Users/neemsandv/legend/legend-engine/.../tests/mapping/selfJoin/selfJoin.pure:80 — golden SQL: four chained `left outer join orgTable as "orgtable_N"` and `"orgtable_4".name is null and "orgtable_3".name is null as "dyna operation"`; no ORDER BY
- /Users/neemsandv/legend/legend-engine/.../tests/mapping/selfJoin/selfJoin.pure:82-83 — the column-name and row-count asserts precede the failing one and passed
- core/src/main/java/com/legend/harness/EngineTestExecutor.java:1839-1848 — the golden-SQL assert at :80 routes through `containsSqlText` → `sqlTextVerify` (advisory), which is why execution reached the row assert at :81
- core/src/main/java/com/legend/exec/Executor.java:511-521 — rows appended in ResultSet order, unsorted
- core/src/test/java/com/legend/rcorpus/DuckWorkspaces.java:82 — only `SET threads=1`

</details>

---

## `testJoinIsolationDeeperTwoIsolations_LeftOuterLeftOuterThenInner`

| | |
|---|---|
| family | `tests/mapping/tree` |
| sweep status | FAIL |
| **verdict** | **REAL DEFECT** |
| effort | M |
| confidence | low |

**Root cause**

Two different filtered navigations off the SAME `orgs` nav slot are materialized asymmetrically. `Person.orgs` is a chained join-chain mapping `@personTableToOrgTreeOptimizationTable > (INNER) @orgTreeOptimizationTableToOrgTable` (tree.pure:432), and the test projects `trades.trader.team.name` (= orgByName('TEAM')) and `trades.trader.orgByName('BUSINESS UNIT').name` — two filter-lifted synthetic heads (`orgs#fN`) on one physical nav step. In NavMaterializer, `midByAlias.putIfAbsent(a2, tail.get(0))` makes the FIRST head (team) the primary identity; every other head goes into `extraSubHeads`/`extraSubTails` (NavMaterializer.java:196-232). The primary identity gets the #70 composite treatment: `corrSubs.compositeChainTarget(...)` pulls the sibling `orgTreeOptimizationTable` joinslot INTO the sub-target and `rewriteNavPredicate` replaces the step's condition with hop-1's oriented condition on `pipelineForMat` (NavMaterializer.java:237-283) — which is exactly the engine's isolated subselect `(select node, name from orgTreeOptimizationTable inner join orgTable on (ancestor=id) where type='TEAM')` joined on `persontable_0.id = …node`. `foldExtraSubIdentities` (NavMaterializer.java:599-660) does NONE of that: it takes `tNavSteps.get(alias)` — the map built from the ORIGINAL `t.pipeline()`, not the composite-rewritten `pipelineForMat` — joins the plain `Org` target (`xg.classFqn()`), and uses `step.predicate()` verbatim as the ON clause. That predicate reads the sibling joinslot on the parent row, and that slot is deliberately NOT demanded at parent level (the explicit comment at NavMaterializer.java:186-193: demanding it emits LEFT where the mapping says INNER, 'row-count wrong: JoinIsolationDeeper expected 4, got 11'), so it is stripped. The second identity's LEFT join therefore never matches and `bu` is NULL for every row — team is right, bu is null, exactly the observed output. (This test additionally carries the `number` String-cast defect from testJoinIsolationDeeper_…, currently invisible because the bu diff renders first.)

**Fix**

Give the extra identities the same composite treatment as the primary. In `NavMaterializer.foldExtraSubIdentities` (core/src/main/java/com/legend/resolver/NavMaterializer.java:599-660), after building `xPipe` (the per-identity filtered sub pipeline) and before constructing the TypedJoin, call `CorrelatedSubselects.CompositeChain xcc = corrSubs.compositeChainTarget(t, step.predicate(), xPipe);`. When `xcc != null`, use `xcc.pipeline()` as the join target (recompute `xRow`/`xCols` from `xcc.pipeline().info().type()`) and `xcc.orientedCond()` as the TypedJoin condition instead of `step.predicate()`; when null, keep today's flat form. This is the same call the primary path already makes at NavMaterializer.java:264-266, so no new machinery is needed — only per-identity invocation. Note that the composite MUST be rebuilt per identity (not reused from `compositeByAlias`) because that identity's filter has to live inside its own isolated subselect. Also apply the `number` cast fix from testJoinIsolationDeeper_LeftOuterLeftOuterThenInner; this test needs both to go green.

**How legend-engine does it** — /Users/neemsandv/legend/legend-engine/…/core_relational/relational/tests/mapping/tree/tree.pure:224 — the engine's own pinned SQL for this test shows two INDEPENDENT isolated subselects, each carrying its own `where "orgtable_N".type = '…'` and each LEFT-joined on `"persontable_0".id = "orgtreeoptimizationtable_N".node`; i.e. the engine builds the composite once per filtered identity, not once per physical property.

**Risk** — `compositeChainTarget` throws NotImplementedException on several shapes (multi-slot disjuncts, mixed slot/parent reads, multi-statement conditions, deep chains — CorrelatedSubselects.java:1330-1370, 1394-1422). Calling it on the extra-identity path will surface those walls on shapes that currently pass silently through the flat form; some chained-union tests (testUnionWithChainedJoinsAcross*) are explicitly documented at CorrelatedSubselects.java:1379-1387 as relying on the flat degradation. Keep the null-return fallback so only genuinely composable shapes change, and expect new loud walls where NotImplementedException fires.

**Also unblocks** — testReplaceTablesPostProcessorJoinIsolation (tests/mapping/tree) exercises the same two-isolation shape; any other corpus test with two differently-filtered navigations off one join-chain property mapping.

**Falsifier** — Dump the SQL legend-lite emits for this test (single-test run with the SQL trace). If the `bu` column's join target is a bare `orgTable` join (or a join whose ON references a stripped `orgTreeOptimizationTable` alias), the diagnosis holds. If instead the emitted SQL already contains a second composite subselect `(… orgTreeOptimizationTable inner join orgTable … where type='BUSINESS UNIT')` and the NULL comes from a wrong ON key or a wrong prefix in the SubNav registration, the fix site moves — either into `compositeChainTarget`'s prefixing or into the `subTree.put(prop, new SubNav(xPrefix, …))` registration at NavMaterializer.java:655-658. This falsifier matters: I could not statically prove that a stripped-slot read in an un-rewritten join condition lowers to NULL rather than raising the loud 'undemanded navigation' error at Pipelines.java:1208-1212, and that is the one link in the chain I did not verify.

<details><summary>Evidence read (10 citations)</summary>

- /Users/neemsandv/legend/legend-engine/…/tests/mapping/tree/tree.pure:210-224 — the test projects both `trades.trader.team.name` and `trades.trader.orgByName('BUSINESS UNIT').name` and pins the golden SQL with TWO isolated subselects, `orgtreeoptimizationtable_0` (type='TEAM') and `orgtreeoptimizationtable_2` (type='BUSINESS UNIT')
- /Users/neemsandv/legend/legend-engine/…/tests/mapping/tree/tree.pure:432 — `orgs : [myDB] @personTableToOrgTreeOptimizationTable > (INNER) [myDB] @orgTreeOptimizationTableToOrgTable`
- /Users/neemsandv/legend/legend-engine/…/tests/mapping/tree/tree.pure:281-283 — `orgByName(type){$this.orgs->filter(o|$o.type == $type)->toOne()}` and `team(){ $this.orgByName('TEAM')}`
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/resolver/NavMaterializer.java:196-232 — `extraSubHeads`/`extraSubTails`: 'the slot materializes once for the FIRST identity; every other identity emits its OWN prefixed join from the same nav step'
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/resolver/NavMaterializer.java:237-283 — the #70 composite path, comment names 'the JoinIsolationDeeper family'; builds `compositeByAlias` and installs `cc.orientedCond()` into `pipelineForMat`
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/resolver/NavMaterializer.java:599-660 — `foldExtraSubIdentities` joins `xPipe` (plain target class pipeline) with `step.predicate()` and never consults `compositeByAlias` or the oriented condition
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/resolver/NavMaterializer.java:186-193 — 'a demanded nav step's JOIN PREDICATE reading other joinslot sub-rows … is NOT demanded here on purpose … (row-count wrong: JoinIsolationDeeper expected 4, got 11)'
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/resolver/CorrelatedSubselects.java:1304-1308 — `record CompositeChain(TypedSpec pipeline, TypedLambda orientedCond)` / `compositeChainTarget(ClassSource cs, TypedLambda navCond, TypedSpec targetPipe)`
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/resolver/Pipelines.java:404-407 — `walkJoinSlot` cancels an undemanded slot and records it in `stripped`
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/resolver/SyntheticHeads.java:148-160,171-178 — `allPreds`/`applyToPipe` are per-head, so each identity really does get only its own predicate (rules out 'both filters ANDed' as the cause)

</details>

---

## `testJoinIsolationDeeper_LeftOuterLeftOuterThenInner`

| | |
|---|---|
| family | `tests/mapping/tree` |
| sweep status | FAIL |
| **verdict** | **REAL DEFECT** |
| effort | S |
| confidence | high |
| **adversarial review** | **CONFIRMED** |

**Root cause**

The `number` column is not a String in the engine. `Account.number` is declared `String[1]` (tree.pure:260) and mapped to `accountTable.id`, which is `INT` (tree.pure:407-410, mapping at tree.pure:449). legend-lite's `MappingNormalizer.coerceColumnToDeclared` computes declared="String", colKind="Integer" (RelationalKinds.pureKindOf maps `Integer_` -> "Integer"), they differ, and the `"String".equals(declared) || "Boolean".equals(declared)` branch wraps the column read in `castAsDeclared(read, String)` — an actual SQL cast at execution. So the TDS cell arrives as the Java String "11". legend-engine performs no such conversion: `SetImplTransformers.buildTransformer` returns a transformer ONLY for "Boolean" (toBoolean) and "StrictDate"/"DateTime"/"Date" (fromDate); every other declared type falls through to `Functions.identity()`, so the raw JDBC Integer reaches the TDS. That is why the corpus writes `assertEquals([11, 'OrgName3'], …)` with an *Integer* 11. Both sides render "11", which is why the harness reports 'renders equal, comparison differs: expected types=[Long, String]; got types=[String, String]' (docs/RELATIONAL_CORPUS.md:1378) — the values are string-identical but the cell kind is wrong.

**Fix**

In `MappingNormalizer.coerceColumnToDeclared` (core/src/main/java/com/legend/normalizer/MappingNormalizer.java:2448-2452) drop `"String".equals(declared)` from the `CAST_AS_DECLARED` branch, leaving `if ("Boolean".equals(declared)) { return castAsDeclared(read, Boolean); }`. A String-declared property over a non-String physical column must then take the SAME treatment the numeric-mismatch case already takes two lines below: emit `Pure.Lite.TYPE_AS_DECLARED` (a type-only assertion, no SQL cast), so the result type says String while the database delivers the raw value — exactly legend-engine's `Functions.identity()` transformer. Concretely, restructure to: Boolean -> castAsDeclared; String -> typeAsDeclared; DateTime-over-StrictDate -> cast; numeric<->numeric -> typeAsDeclared; numeric-over-VARCHAR -> parseInteger/parseFloat/parseDecimal (unchanged); everything else uncast. Update the block comment at :2444-2449, which currently asserts the opposite of what SetImplTransformers does.

**How legend-engine does it** — /Users/neemsandv/legend/legend-engine/legend-engine-core/legend-engine-core-base/legend-engine-core-executionPlan-execution/legend-engine-executionPlan-execution/src/main/java/org/finos/legend/engine/plan/execution/result/transformer/SetImplTransformers.java:86-107 — only Boolean and Date-family get a transformer; all other declared types are `Functions.identity()`.

<details><summary>Adversarial review notes (CONFIRMED)</summary>

Mechanism verified at both ends. MappingNormalizer.coerceColumnToDeclared (2423-2437 computes declared/colKind; 2450-2453 is the CAST branch) does fire for `number : [myDB] accountTable.id`: declared 'String' from Account.number:String[1], colKind 'Integer' from RelationalKinds.pureKindOf(Integer_) (line 29), and the `"String".equals(declared) || "Boolean".equals(declared)` branch emits Pure.Lite.CAST_AS_DECLARED. Typer.java:1131-1145 turns that into a WIRE-flagged TypedCast (not the pass-through), and Scalars.java:460-466 comments confirm castAsDeclared never reaches the pass-through rule (only typeAsDeclared does: `RULES.put(f, (n,args) -> args.get(0))`). Executor.fetch uses rs.getObject, so the cell kind follows the SQL type — a real CAST to VARCHAR really does make the cell a Java String, and removing it really does restore a Long. On the other side, wireEquals (EngineTestExecutor.java:3325-3336) returns false when exactly one side is an integral type, which is precisely the [Long,String] vs [String,String] failure. The engine citation is exact: SetImplTransformers.buildTransformer switches only on Boolean / StrictDate / DateTime / Date and otherwise `return Functions.identity()`. And the golden itself is the strongest evidence — tree.pure:203 writes `assertEquals([11, 'OrgName3'], …)` with an unquoted 11. Independent corroboration: docs/CORPUS_STUDY_2026_08.md:357 already lists 'castAsDeclared applies a wire-level coercion as a SQL cast' with the same "11" vs 11 consequence. Two caveats. (1) The brief's 'arity=2 … arity=1' is NOT part of the defect: Eval.size() is values().size() for a Collection but rows().size() for a Tabular (EngineTestExecutor.java:2485-2496), so a 1-row 2-col actual legitimately reports arity 1; only the cell kind differs. (2) The fix removes a branch that was added deliberately in f72c9b20 ('conformance-cast provenance seam'), whose message banks corpus wins (projection +2, union +1) — those were mixed Boolean/numeric/dynafunc cases, but a full re-sweep is mandatory, and dropping the SQL cast means any query that applies a string operator to a String-declared numeric column will now hand DuckDB an INT where it previously got VARCHAR (H2, whose goldens the corpus encodes, is lenient there; DuckDB is not). The direction is nonetheless right and makes the Column path consistent with the Expression path, which already never casts String (coerceToDeclaredNumeric at 2325-2338 lists only Float/Integer/Decimal/Number/DateTime/StrictDate/Date/Boolean).

</details>

**Citation issues found in review** — Two cited lines resolve to something else. 'tree.pure:407-410 — Table accountTable ( id INT PRIMARY KEY )' is wrong: 405-409 is the body of `Join accountToTrade ( accountTable.id = tradeTable.accountId )`; the table declaration is at 394-397 (the claim itself is true, at the other line). 'tree.pure:449 — Account: Relational { number : [myDB] accountTable.id … }' is wrong: 447-451 is inside the Trade mapping (`ref`/`trader`/`account`); the Account mapping is at 454-459 with `number : [myDB] accountTable.id` at 456. 'tree.pure:192-200 — the test asserts assertEquals([11, 'OrgName3'] …)' understates the range: the function starts at 192 but that assert is at 203. MappingNormalizer 2448-2452 is really 2450-2453. All four are drift, not substance — every claim is true at the corrected line.

**Risk** — Broad blast radius: this is the mapping-wide wire-coercion rule, so every String[1] property over a non-VARCHAR column changes cell kind. Tests that currently pass because the value got stringified will flip. Run the whole corpus, not just tests/mapping/tree. Tenet-2 trap to avoid: do NOT 'fix' this by teaching the harness's `wireEquals` to compare Long 11 with String "11" — the platform owns the TDS cell kind, and a lenient comparator would hide every other kind defect.

**Also unblocks** — testJoinIsolationDeeperTwoIsolations_LeftOuterLeftOuterThenInner (same `number` column, currently masked by the louder OrgName2/null diff); likely other tests/mapping/tree and multigrain cases where a String[1] property sits over an INT/DATE column.

**Falsifier** — Find one corpus test that asserts a *quoted string* value for a property declared String[1] whose mapped physical column is numeric/date (e.g. `assertEquals('11', …)` rather than `assertEquals(11, …)`). If such a golden exists and currently passes, the engine does convert somewhere else on the wire and this diagnosis is wrong.

<details><summary>Evidence read (10 citations)</summary>

- /Users/neemsandv/legend/legend-engine/…/core_relational/relational/tests/mapping/tree/tree.pure:260 — `Class …::Account { number:String[1]; }`
- /Users/neemsandv/legend/legend-engine/…/tests/mapping/tree/tree.pure:407-410 — `Table accountTable ( id INT PRIMARY KEY )`
- /Users/neemsandv/legend/legend-engine/…/tests/mapping/tree/tree.pure:449 — `Account: Relational { number : [myDB] accountTable.id, … }`
- /Users/neemsandv/legend/legend-engine/…/tests/mapping/tree/tree.pure:192-200 — the test asserts `assertEquals([11, 'OrgName3'], $tds.rows->at(0).values)`; 11 is an Integer literal, not '11'
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/normalizer/MappingNormalizer.java:2448-2452 — `if ("String".equals(declared) || "Boolean".equals(declared)) { return new AppliedFunction(Pure.Lite.CAST_AS_DECLARED, …); }`
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/normalizer/MappingNormalizer.java:2423-2436 — `coerceColumnToDeclared` computes declared/colKind and returns `read` unchanged only when they are equal
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/normalizer/RelationalKinds.java:29 — `case RelationalDataType.Integer_ i -> "Integer";`
- /Users/neemsandv/legend/legend-engine/legend-engine-core/legend-engine-core-base/legend-engine-core-executionPlan-execution/legend-engine-executionPlan-execution/src/main/java/org/finos/legend/engine/plan/execution/result/transformer/SetImplTransformers.java:86-107 — `buildTransformer` switches only on "Boolean" and "StrictDate"/"DateTime"/"Date"; the fall-through is `return Functions.identity();`
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/harness/EngineTestExecutor.java:1882-1896 — the 'renders equal, comparison differs' branch that produced the brief's message
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/docs/RELATIONAL_CORPUS.md:1378 — sweep line: 'expected types=[Long, String] arity=2; got types=[String, String] arity=1'

</details>

---

## `testUnionTwoRelationMappings_ManyColumnProject`

| | |
|---|---|
| family | `tests/mapping/union/relation` |
| sweep status | FAIL |
| **verdict** | **NEEDS PROBE** |
| effort | S |
| confidence | medium |

**Root cause**

The only difference is the representation of the firstName cells: the expected side (the `#TDS` literal) yields NULL, the actual side (the DuckDB result) yields the empty string. Both sides are, individually, faithful to legend-engine. Expected: legend-pure's TDS inline DSL reads the literal body with a deephaven CSV reader configured `nullValueLiterals(Arrays.asList("", "null"))`, so a blank cell IS null — and legend-lite reproduces that exactly in `Scalars.tdsCell`, which returns `SqlExpr.NullLit()` for an empty cell. Actual: the fixture inserts literal `''` into `PersonSet1.firstName_s1` / `PersonSet2.firstName_s2`, both legs map `firstName` to those columns, so `''` is the correct value and DuckDB returns `''`. What I could NOT establish statically is how the engine's own assertion passes: the test compares `#TDS…#->toString()` against `$result…->toString()`, and engine relation `toString` renders an empty (multiplicity-zero) cell as the literal text `null` (s.pure:22-37) while rendering `''` as nothing — so under my reading the engine's two strings differ too. Something on the engine's actual side must be turning `''` into empty/TDSNull (or the literal's blank cell into `''`), and I could not find it. legend-lite's harness makes the mismatch visible earlier by comparing the two TDS grids STRUCTURALLY (`gridEquals`) instead of rendering, but rendering faithfully would not make it pass either.

**Fix**

Do not change anything until the probe settles which side is wrong; a blind edit here is a coin flip between two engine-faithful behaviours. PROBE: evaluate, in a real legend-engine/legend-pure runtime, `assertEquals('#TDS\n   a,b\n   x,\n#', #TDS a, b\n x, \n#->toString())`. (a) If the blank cell renders as the text `null`, then legend-lite's `#TDS` side is right, the DuckDB `''` side is right, and the corpus test cannot pass as written under engine semantics — record it in the ledger as an upstream-inconsistent test rather than fixing it. (b) If the blank cell renders as nothing, then legend-pure's runtime TDS treats a blank String cell as `''` despite the CSV null-literal config, and the fix is in `Scalars.tdsCell` (core/src/main/java/com/legend/lowering/Scalars.java:2915-2926): for a STRING-typed column an EMPTY (but present) cell must lower to `SqlExpr.StringLit("")`, with SQL NULL reserved for the explicit `null` / `TDSNull` spellings — the existing `cell.isEmpty()` disjunct would move under a non-String type guard. Either way, do NOT make the harness's `gridEquals`/`wireEquals` treat null and "" as equal: that is harness compensation and would mask every genuine null-vs-empty defect in the corpus.

**How legend-engine does it** — /Users/neemsandv/legend/legend-pure/legend-pure-dsl/legend-pure-dsl-tds/legend-pure-m2-dsl-tds-grammar/src/main/java/org/finos/legend/pure/m2/inlinedsl/tds/TDSExtension.java:342 (blank cell = null literal) read together with /Users/neemsandv/legend/legend-engine/…/core_functions_relation/relation/functions/s.pure:22-37 (empty renders 'null'). These two are what make the corpus expectation unreachable under my reading, and they are the pair the probe must reconcile.

**Risk** — Option (b) changes TDS-literal semantics globally — every `#TDS` fixture with a blank String cell flips from NULL to ''. That would move many tests in both directions. Do not apply it on inference.

**Also unblocks** — testUnionTwoRelationMappings_ManyColumnProjectGeneratesSingleUnion — byte-identical query, mapping and expectation.

**Falsifier** — The probe above is itself the falsifier. A cheaper partial one: find any other corpus test whose `#TDS` literal has a blank cell AND whose actual side is known to be SQL NULL (not ''). If such a test passes today in legend-lite, `tdsCell`'s empty->NULL rule is load-bearing and option (b) would break it.

<details><summary>Evidence read (8 citations)</summary>

- /Users/neemsandv/legend/legend-engine/…/tests/mapping/union/relation/testRelationUnion.pure:359-389 — the test body; expected `#TDS` rows are `Anand, , Anand, , …` and the assert is `<literal>->toString()` vs `$result.values->at(0)->sort([~c0->ascending()])->toString()`
- /Users/neemsandv/legend/legend-engine/…/tests/mapping/union/relation/relationUnionSetup.pure:300-320 — `unionOfTwoRelationMappingsFirstAndLast` maps `firstName: $src.firstName_s1->toOne()` (leg 1) and `$src.firstName_s2->toOne()` (leg 2); firstName IS mapped in both legs
- /Users/neemsandv/legend/legend-engine/…/tests/mapping/union/testUnion.pure:426-428 and :432-433 — `insert into PersonSet1 (…, firstName_s1, …) values (1, '', 'Scott', 1, 1)` etc.; the stored value is the empty string, not NULL
- /Users/neemsandv/legend/legend-pure/legend-pure-dsl/legend-pure-dsl-tds/legend-pure-m2-dsl-tds-grammar/src/main/java/org/finos/legend/pure/m2/inlinedsl/tds/TDSExtension.java:337-344 — `makePureCsvSpecs()` … `.nullValueLiterals(Arrays.asList("", "null"))`; the `#TDS` literal's blank cell is NULL
- /Users/neemsandv/legend/legend-engine/legend-engine-core/legend-engine-core-pure/legend-engine-pure-code-functions-relation/legend-engine-pure-functions-relation-pure/src/main/resources/core_functions_relation/relation/functions/s.pure:22-37 — `s()`: empty renders as the text 'null'; a String renders as itself (so `''` renders as nothing)
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/lowering/Scalars.java:2915-2926 — `tdsCell` returns `new SqlExpr.NullLit()` when `cell == null || cell.isEmpty() || …` — engine-faithful
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/harness/EngineTestExecutor.java:2792-2796 — `compare` routes two Tabular sides to `gridEquals`, i.e. structural cell comparison, never rendering through `s()`
- /Users/neemsandv/legend/legend-engine/…/tests/mapping/union/relation/testRelationUnion.pure:357 — the test's own comment ('Functional / OOM canary') and git commit c4d23018c61 confirm it was added recently as a regression canary, so the expected block was very likely pasted from an observed engine run

</details>

---

## `testUnionTwoRelationMappings_ManyColumnProjectGeneratesSingleUnion`

| | |
|---|---|
| family | `tests/mapping/union/relation` |
| sweep status | FAIL |
| **verdict** | **NEEDS PROBE** |
| effort | XS |
| confidence | medium |

**Root cause**

Identical to testUnionTwoRelationMappings_ManyColumnProject in every respect: same 12-column projection, same `unionOfTwoRelationMappingsFirstAndLast` mapping, same `#TDS` expectation, same `->distinct()->sort([~c0->ascending()])->toString()` assert. The two functions differ only in their doc comment (one is the OOM canary, this one the single-`union all` bounded-growth canary), and neither actually inspects the SQL — both only assert rows. So the cause is the same `#TDS`-blank-cell-NULL vs DB-empty-string divergence: `Scalars.tdsCell` lowers the blank cell to SQL NULL (engine-faithful per TDSExtension's `nullValueLiterals("", "null")`), while the projected `firstName_s1`/`firstName_s2` columns genuinely hold `''` in the fixture.

**Fix**

Same as testUnionTwoRelationMappings_ManyColumnProject — run that probe first, then either ledger both tests as upstream-inconsistent or change `Scalars.tdsCell` (core/src/main/java/com/legend/lowering/Scalars.java:2915-2926) so an empty-but-present cell on a String-typed column lowers to `SqlExpr.StringLit("")`. Whatever is done must be done once, in the platform, for both tests. Note separately: this test's stated intent (exactly one `union all` fragment, no per-column set-list growth) is never actually asserted in the .pure body, so passing it proves nothing about union-fragment count.

**How legend-engine does it** — /Users/neemsandv/legend/legend-pure/…/inlinedsl/tds/TDSExtension.java:342 and /Users/neemsandv/legend/legend-engine/…/core_functions_relation/relation/functions/s.pure:22-37 — as above.

**Risk** — Same global TDS-literal blast radius. No independent risk.

**Also unblocks** — testUnionTwoRelationMappings_ManyColumnProject.

**Falsifier** — Same probe. Additionally: if a fix makes testUnionTwoRelationMappings_ManyColumnProject pass but not this one, my 'same cause' claim is wrong and the difference must be in the query plan, not the cell representation.

<details><summary>Evidence read (5 citations)</summary>

- /Users/neemsandv/legend/legend-engine/…/tests/mapping/union/relation/testRelationUnion.pure:395-426 — the full body; identical to :359-389 apart from the comment
- /Users/neemsandv/legend/legend-engine/…/tests/mapping/union/testUnion.pure:426-428,432-433 — the `''` inserts for firstName_s1 / firstName_s2
- /Users/neemsandv/legend/legend-pure/legend-pure-dsl/legend-pure-dsl-tds/legend-pure-m2-dsl-tds-grammar/src/main/java/org/finos/legend/pure/m2/inlinedsl/tds/TDSExtension.java:337-344 — blank cell parses to null
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/lowering/Scalars.java:2915-2926 — `tdsCell` empty -> NullLit
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/docs/RELATIONAL_CORPUS.md:1389-1390 — both tests' sweep lines are character-for-character identical, confirming one cause

</details>

---
