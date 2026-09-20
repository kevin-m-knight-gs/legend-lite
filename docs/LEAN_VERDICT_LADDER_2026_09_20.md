# The lean verdict ladder (2026-09-20)

## North star (user, 2026-09-20)

One statement per test body. The product SQL of every `let` appears in it **exactly once**,
unchanged from what the platform emits for a user. Around it, the **thinnest assert wrapper**
that can produce a verdict row. Lean is measured by counting — statements sent, bytes, CTEs,
subqueries, copies of each product query — never by intuition.

## Method

One rung at a time, simplest first. For each rung: pick one real corpus test; dump the exact
statement(s) sent in database mode (`LEGEND_LITE_DUMP_SQL=1 … -Dlegend.judge.mode=database
-Drcorpus.test=<fqn>`); write the leanest statement a careful person would write by hand beside
it; count the difference; fix the shape until they match; only then climb. Every fix keeps the
four lanes exact and the differential at zero.

Facts that shape the work (docs/DATABASE_MODE_HOMEWORK §4ac):

- DuckDB's prepare is planning: ≈ 65 µs per plan operator (EXPLAIN line), **not** per byte.
  The afternoon's verdict-row reshape cut bytes 11% and made prepare *slower* (more operators);
  it was reverted. Bytes are a symptom; operators are the cost.
- DuckDB 1.4 computes a multi-referenced CTE once; `AS MATERIALIZED` changes neither the plan
  nor the prepare time. The lever is fewer subqueries and fewer references per assert.
- The lane: 2,577 fused statements = 42 s of the 82 s DuckDB database lane (prepare 23 s,
  execute 19 s); `assertEquals` is 76% of the text.

## Rungs

| rung | test | what enters | status |
|---|---|---|---|
| 1 | (corpus) `mapping::relation::testAutoInferPKEmptyForPivot` | one `assertEquals([], pkOfFunc(...))` — the product query is a metamodel read | superseded by the Java ladder below; the wrapper part is landed |
| 2 | `mapping::boolean::testQuery` | one `let` (a class query) + `assertSize` | queued |
| 3 | `tds::distinct::testSimpleDistinctWithTake` | one `let` (a relation) + `assertSize` over rows | queued |
| 4 | `mapping::boolean::testGet` | one `let`, three asserts incl. a positional read | queued |
| 5 | `mapping::filter::testFilterMappingWithProjectionOverlapp` | one `let`, seven asserts, positional rows and a sql-text assert | queued |
| 6 | `mapping::dates::strictdate::testQuery` | two `let`s | queued |
| 7 | (each of the above) | the envelope: typed NULL casts, VARCHAR canon casts — EXPLAIN on both dialects | queued |

(per-rung records follow, newest last)

## Rung 1 — `assertEquals([], pkOfFunc(pivotFunction))` (2026-09-20)

Not a host constant after all: `pkOfFunc` is a metamodel read (the function's expression
tree as VALUES relations), so this rung has a real product query and one assert whose answer
is `[]` against `[]`. Files: the dumped statement and the hand-leanest one, measured with the
same DuckDB (`DuckPrep2`), identical verdict row `[true | [] | [] | null]`.

| | current | hand-leanest |
|---|---|---|
| chars | 11,796 | 3,934 |
| CTEs | 4 (`__e`, `__a`, `__ec`, `__ac`) | 3 (`__a`, `__sa`, `__ra`) |
| scalar subqueries | 29 | 0 |
| copies of the product query | **2** | 1 |
| plan operators (EXPLAIN lines) | 264 | 77 |
| prepare | 3.57 ms | 1.00 ms |
| execute | 0.86 ms | 0.19 ms |

Where the fat is, read off the statement:

1. **The product query appears twice.** The equality form hands the same rows to the
   leniency as "cells" (`statement(er, ar, …, er, ar)`), and the builder defines `__ec`/`__ac`
   as literal copies of `__e`/`__a`.
2. **Every frame is spelled three times** (verdict, text column, lenient column) and each frame
   is three scalar subqueries over its side; with the null/tree checks and the leniency's two
   counts and EXISTS, 29 subqueries for one assert. The leanest form computes ONE aggregate row
   per side — `COUNT`, first cell, `STRING_AGG`, null count, tree count — and the verdict row
   reads columns of two one-row CTEs: zero subqueries (DuckDB probe: 117 → 53 plan lines,
   0.35 → 0.18 ms for the same four reads).
3. **The 2-ULP leniency is emitted for sides that carry no Float** — both `__v` columns are
   `CAST(NULL AS DOUBLE)`, the clause is statically FALSE — and it is emitted twice, each time
   spelling `Double.MAX_VALUE` as a 309-digit decimal (`1797693134862315700…0.0`, 2.5 KB of the
   11.8 KB). The builder knows each side's declared kind; no Float on either side = no leniency
   clause and no `__v` column. When present: exponent spelling, one aggregate over the
   positional join.
4. **Two wrapper levels per side where one does** (`… FROM (SELECT value, __canon0, __canon1
   FROM (<product>) AS side) AS w`), and `__canon1` (the quoted-string canon) is computed for
   every row and never read in this form.
5. **Product side, noted, not the wrapper's:** the metamodel relation is inlined as the same
   4-row VALUES table five times inside one query (three UNION ALL legs + the join), ten times in
   the statement. The system-database design says constants ride the query; riding it once per
   statement (a named CTE) is the lean form. Separate item.

**Rung-1 fix (the wrapper).** In `VerdictSql`: (a) the equality form's cells ARE its rows — no
copies; (b) one aggregate CTE per side, frames and null/tree checks as column reads, verdict
row over the two one-row CTEs; (c) the leniency clause only when a side is declared Float, then
as one aggregate over the positional join, with `Double.MAX_VALUE` spelled `1.7976931348623157E308`;
(d) one wrapper level per side, the unused canon column dropped. Target for this rung: the
hand-leanest counts above. Then the same test re-dumped, the micro-benchmark re-run, the four
lanes exact, and rung 2.

### Rung 1, the product side (2026-09-20) — user: "let's also fix the product SQL"

The product query of `pkOfFunc(pivotFunction)` alone, measured in DuckDB:

| | current | A: each system relation once, dead scaffolding dropped | C: A + the three same-table union legs folded |
|---|---|---|---|
| chars | 3,099 | 1,657 | 1,072 |
| VALUES tables spelled | 5 | 2 | 2 |
| union legs | 3 | 3 | 1 |
| selects | 7 | 6 | 3 |
| prepare | 0.88 ms | 0.54 ms | 0.35 ms |
| plan operators | 8 | 47 | 8 |

(DuckDB constant-folds the whole read to an empty result — 8 operators — from the inline
VALUES; naming the VALUES as plain CTEs (A) defeats that folding and the plan grows, though
parse/bind still drops. The lean form is C.)

The hand-written C:

```sql
WITH nodes(id, function_id, ordinal, kind, parent_id, depth, mult_lower, mult_upper, var_name) AS (VALUES ('fn:26f3edb3:5211/0', 'fn:26f3edb3:5211', 0, 'FunctionExpression', NULL, 0, 1, 1, NULL), ('fn:26f3edb3:5211/0/0', 'fn:26f3edb3:5211', 0, 'FunctionExpression', 'fn:26f3edb3:5211/0', 1, 1, 1, NULL), ('fn:26f3edb3:5211/0/1', 'fn:26f3edb3:5211', 1, 'InstanceValue', 'fn:26f3edb3:5211/0', 1, 1, 1, NULL), ('fn:26f3edb3:5211/0/2', 'fn:26f3edb3:5211', 2, 'InstanceValue', 'fn:26f3edb3:5211/0', 1, 1, 1, NULL)),
fn(id, name) AS (VALUES ('fn:26f3edb3:5211', NULL)),
names(node_id, ordinal, name) AS (SELECT NULL, NULL, NULL WHERE FALSE)
SELECT t3.name AS u_map__name
FROM (
  SELECT CASE WHEN t1.kind = 'FunctionExpression' THEN t1.id END AS es
  FROM fn AS t0
  LEFT OUTER JOIN nodes AS t1 ON t0.id = t1.function_id AND t1.depth = 0
    AND t1.kind IN ('FunctionExpression', 'InstanceValue', 'VariableExpression')
  WHERE t0.id = 'fn:26f3edb3:5211'
  LIMIT 1
) AS t2
LEFT OUTER JOIN names AS t3 ON t2.es = t3.node_id
WHERE t3.name IS NOT NULL
ORDER BY t3.ordinal NULLS LAST
```

What C implies for the lowering, three separate changes:

1. **A system relation rides the statement once.** Today the metamodel VALUES relation is
   pasted at every reference (five times in this query, ten in the verdict statement). Once,
   named, per statement. For DuckDB specifically a VALUES CTE stops constant folding, so this
   should be measured with the union fold (3) in place, not alone.
2. **Dead scaffolding.** `SELECT * FROM (VALUES …) AS t WHERE t.id = <const>` is the table
   itself; an empty system relation (`VALUES (NULL, NULL, NULL) WHERE 1 = 0`) is `WHERE FALSE`
   with no row spelled.
3. **Union legs over the SAME source table with disjoint kind filters fold into one read**
   with kind-conditional columns — the inheritance union of the metamodel classes
   (`FunctionExpression | InstanceValue | VariableExpression` over one nodes table), and the
   same shape in user union mappings whose legs share a table. This is the union-mapping
   lowering (the F5 ladder), the biggest of the three and the one that pays: 3 scans → 1.

## The Java ladder — `core/src/test/java/com/legend/ladder/LeanSqlLadderTest.java` (2026-09-20)

User: "create a new java test that starts with most simple and continues to build on it in a
controlled fashion so we can really go step by step and be in control of our own destiny."
Eleven rungs over one three-row table, each a `<<test.Test>>` adding ONE construct; run through
the real runner in database judge mode on in-memory DuckDB; every statement captured by a
recording JDBC proxy and pinned byte-for-byte (`ladder/<rung>.current.sql`), the hand-written
lean target beside it (`<rung>.lean.sql`); a rung is CLOSED when they match; `-Dladder.record=1`
re-records after a deliberate shape change.

**Rungs 1–3 CLOSED — the general shape (user: "try not to special case … so each can build on
the previous").** One shape serves every equality assert:

- each side is a rows relation `(__c, __rn[, __v])`, folded to ONE facts row (`__se`/`__sa`:
  framed text, null count, tree count — `COUNT`, `MIN(CASE WHEN __rn = 1 …)`, `STRING_AGG`,
  two filtered counts); the verdict row reads the two one-row CTEs — zero scalar subqueries;
- **the side's shape follows its declared multiplicity:** declared exactly one → the facts row
  spelled straight over the plan (a one-row seed `LEFT JOIN … ON TRUE` so an empty plan still
  frames `[]`); optional or many → rows CTE + aggregate;
- **the side is spliced at one level only when its wrap is a plain projection** (no aggregate or
  window in a projection, no where/group/distinct/limit): a JSON-document side's value is an
  aggregate over the plan and must stay a layer — trimmed to its value and the one canon read.
  Learned the hard way: the splice put `to_json(list(…))` into `WHERE … IS NOT NULL` (159 DuckDB
  tests, "WHERE clause cannot contain aggregates");
- **the value column belongs to the pair, not the side:** the 2-ULP leniency exists only when a
  side is declared Float (a compile-time fact); then BOTH sides carry `__v` (a typed NULL where
  there is no Float). Learned: `assertEquals([], $row.float)` — the empty literal side had no
  `__v` and the join over it failed;
- the equality form's cells ARE its rows: the `__ec`/`__ac` copies are gone.

| rung | before | after | lean | status |
|---|---|---|---|---|
| r01 `assertEquals(1, 1)` | 4,876 chars, 20 subqueries | 1,034 / 0 | = | CLOSED |
| r02 `assertEquals(3, [1,2,3]->size())` | 4,876 / 20 | 1,034 / 0 | = | CLOSED |
| r03 `assertEquals('a', 'a')` | 4,996 / 20 | 1,038 / 0 | = | CLOSED |
| r04 `assertSize(execute(…).values, 3)` | 787 / 5 | 612 / 5 | 232 | OPEN — the size form computes the count twice |
| r05 one let + `assertSize` | 2 statements | 2 statements | 1 | OPEN — a class-rooted let runs as a JSON document, the count re-derived from the store |
| r06 `assertSameElements([...], $r.values.name)` | 6,940 / 38 | 1,966 / 4 | | OPEN — the product query is re-lowered for the read |
| r08 positional cell | 5,524 / 23 | 1,451 / 4 | | OPEN |
| r09 two asserts, one let | 7,171 / 29 | 2,923 / 10 | | OPEN |
| r11 float multiset | 40,800 / 38 | 11,885 / 7 | | OPEN — the leniency block |

**Corpus effect of rungs 1–3:** four lanes exact (DuckDB host 108 / H2 host 412; DuckDB
database lost 0 / gained 0; H2 database lost 64 / gained 72 — the registers), differential
agree 5,848 · disagree 0 · unjudged 0; DuckDB database lane 82 s → 72 s, SQL text sent 96.3 MB →
74.1 MB.

**Next rungs.** r04: the one-line families (`size`, `empty`, `contains`, `condition`,
`tolerance`, `subset`) spell each operand twice through `predicate`; the same facts pattern (one
`__p` CTE, operands as columns, declared-one operands inline). r05/r06: a class-rooted `let` as a
frame CTE like a relation-rooted one, so the product query rides once and reads derive from it.
r07–r09: the grid forms (`gridRowCanons`, the per-column cell pool) over one CTE. r11: the
leniency block. Then the corpus rungs 5–7 as validation.

**The judge mode is a run option (2026-09-20).** The chain exposed `AssertVerdicts.JUDGE_MODE` as a
once-per-JVM static: in the shared core JVM the ladder ran in whichever mode loaded first and pinned
the referee's probes. A run-level fact belongs to the run: `ExecuteOptions.JudgeMode`, set on the
runner's constructor and merged into every test's options; the executor and the verdict arms read
`env.options().judgeMode()`; the static is deleted. The corpus lane still passes `-Dlegend.judge.mode`
to `MinimalCorpusTest`, which hands it to its runner once — the product never reads a property. No
pom execution, no skip.

## Rungs 4–11 CLOSED on the general shape (2026-09-20)

Every rung is now ONE statement (statements=1 across the ladder), every let's product SQL rides
it once, and the wrapper is the general shape with no scalar subqueries except the derived
tables that hold a side. Landed on top of rungs 1–3:

- **One statement per body (rungs 5–11).** A let's frame is no longer RUN at the let under a
  verdict batch: its readers derive from it inside the body's statement (a MATERIALIZED CTE for
  a planned relation frame; the chain pasted for a class-rooted one) and a broken pipeline
  surfaces at the flush. A value-position execute (its result IS the value asked for) still
  runs. Corpus: the frame runs were 1,111 of the DuckDB lane's prepared statements.
- **The ladder counts executed statements** (the recording proxy records on execute, not on
  prepare): the wire-type probe prepares a plan to read its reported columns and never runs it.
- **Rung 4, the one-line families** (`size`, `sizeOfGraph`, `empty`, `emptyOfGraph`, `contains`,
  `condition`, `tolerance`, `subset`, `renderedText`, `jsonText`): operands as ONE-ROW relations
  cross-joined once into the `__p` facts row, the verdict row reads its columns — every operand
  computed once. The multiplicity rule again: a declared-one operand (the size literal, a
  condition, a contained value, the tolerance's three) is a scalar row straight over its plan
  (`VerdictSql.scalarRow`), never a rows CTE plus a limited read. `assertSize(…, 3)`: 787 chars
  and 5 subqueries → 607 and 1 (the count's own derived table).
- **Rung 8, a correctness fix, not only leanness.** A frame reference now re-states the frame's
  ORDER BY over its own columns (`FrameRefs.reference`): a positional read over
  `->sort('id')->at(0)` reads `… FROM frame_r … ORDER BY frame_r_t0.id NULLS LAST LIMIT 1`;
  before, it read the CTE unordered and relied on scan order.
- **Rung 11, the leniency block:** the pair facts count only the bad pairs; the two side counts
  come from the facts rows (`__n`); `Double.MAX_VALUE` spells `1.7976931348623157E308`
  (`plainFloat`: exponent form for magnitudes ≥ 1e15 or < 1e-6). 40,800 chars / 38 subqueries
  → 11,061 / 5 (the per-element float canon is the canon).

| rung | statements | chars | subqueries | status |
|---|---|---|---|---|
| r01–r03 | 1 | 1,142 | 0 (+2 derived tables) | CLOSED |
| r04 `assertSize(execute(…).values, 3)` | 1 | 607 | 1 | CLOSED |
| r05 one let + assertSize | 1 (was 2) | 607 | 1 | CLOSED |
| r06 assertSameElements over a class let | 1 (was 3) | 1,748 | 0 | CLOSED |
| r07 project + sort, count rows | 1 (was 3) | 1,631 | 3 | CLOSED |
| r08 positional cell | 1 (was 3) | 1,437 | 4 | CLOSED |
| r09 two asserts, one let | 1 (was 3) | 2,932 | 6 | CLOSED |
| r10 two lets | 1 (was 3) | 1,246 | 2 | CLOSED |
| r11 float multiset | 1 (was 2) | 11,061 | 5 | CLOSED |

"CLOSED" = the pinned emission IS the reviewed lean target for the general shape. Residuals
recorded, each a separate small leg, none a shape problem:

1. A count over a TDS-wrapped side reads the canon wrap (`__rowcanon`, cells) it does not need
   (r07, r09): `countRows` over the wrap's own source.
2. A positional cell read (`->at(0).getString('name')`) lowers as a scalar subquery over the
   limited reference, then the declared-one seed join wraps it once more (r08): two layers where
   one does — the `at` lowering.
3. A class-rooted let with SEVERAL readers pastes its chain per reader (the corpus: 1,743 class
   frames; `testGet`-shaped bodies) — the class frame as a CTE (its row relation, readers
   derived) is the remaining "exactly once" leg for class lets; single-reader class lets
   (r05, r06) already ride once.
4. `gridTolerance` (assertEqWithinTolerance, 11 asserts) still spells its operands through the
   old `predicate`.

5. **Rung 11 is not a hand floor** (user question, 2026-09-20): of its 11,061 chars the float canon
   expression is 4,107 × 2 sides and the 2-ULP predicate 403; the shape itself is 2,444. A human
   writes the shape and CALLS a canon the session already knows: DuckDB `CREATE MACRO` at session
   start (where `DuckDb.initSession` applies the session contract) — H2 has no SQL macro and keeps
   the inline form. "Define the canon once per session" is the residual; ≈ 3k is the floor.

## Rung 12 — a class-rooted let with several readers (2026-09-20)

`let r = execute(|Thing.all(), M, RT, [])` read three ways: `assertSize($r.values, 3)`,
`assertSameElements([...], $r.values.name)`, `assertEquals(3, $r.values->filter(t | $t.amount >
0.0)->size())`. Before: the class chain was PASTED per reader — three scans of `T`, the let's
product SQL three times (residual 3 above). Now the let is planned ONCE as a CTE of its root
rows and every reader ranges over that extent:

```
WITH frame_r AS MATERIALIZED (SELECT frame_r__t0.*
  FROM T AS frame_r__t0), ...
    SELECT COUNT(*) AS __n FROM frame_r AS frame_r_t0                       -- assertSize
    SELECT frame_r_t0.NAME AS u_map__name FROM frame_r AS frame_r_t0 ...    -- .name
    (SELECT COUNT(1) FROM frame_r AS frame_r_t0 WHERE frame_r_t0.AMOUNT > 0.0)  -- filter + size
```

**The rule (narrow on purpose — each rung builds on the last).** A let is planned as a class
frame only when BOTH hold:

1. the chain is a PLAIN extent — `Class.all()` under filters only (no map, project,
   graphFetch, sort, cap, or milestoning argument), so the chain's values ARE the root class's
   instances (`ResultEnvelopeSplice.plainExtentRoot`);
2. the class is bound by a PLAIN pipeline — mapping filters over ONE table, no join step, no
   union, no view root (`ClassSources.plainPipeline`, asked by the executor through
   `StoreResolver.plainClassPipeline` in the chain's own context).

Everything else keeps the pasted chain it had. The census on the DuckDB database lane:
`frames[cte=1401 pasted=511 class=1690 class-cte=53]` — 53 of 1,743 class frames take the CTE
under this rule; the remaining 1,690 are the next rungs (join-stepped pipelines as a pre-joined
frame; sorted / capped extents; milestoned extents), each to be climbed the same way.

**The mechanism.** The executor plans the let's graph fold and takes its ROOT ROWS
(`VerdictSql.classExtentRows`: the fold's own from / where / caps projecting `root.*` — the
root table's PHYSICAL columns, never the store's declared list, which a seeded table can
exceed) as the frame's CTE; the fold itself stays the frame's plan of record (the activity
SQL, the SQL-text referee's rows leg). The splice rewrites a `.values` read of such a frame into
the class's bare `getAll` inside a from-envelope that names the frame (`TypedFrom.extentFrame`);
the resolver carries the name (`Context.extentFrame`) to the one site where the chain acquires
its class source and makes the pipeline the frame reference alone (`ClassSources.withRootFrame`:
the frame's rows already passed the mapping filters, so nothing is re-applied and nothing joins
twice); the lowerer emits a table reference carrying a frame as a CTE read (`SqlSource.Cte`,
reader alias `<frame>_t<n>`). One rule for a count, a property read, a filter.

**What the first cut broke, and why (2026-09-20, the DuckDB database lane: lost 59; H2:
lost 221).** The first cut fired on EVERY class-rooted let and swapped only the root table.
Four mechanisms, all scoping bugs, none in the verdict shape: (1) `map` / `graphFetch` /
`serialize` chains were planned as root rows, so readers ranged over the wrong values (51 of
59); (2) the reader's pipeline re-applied the mapping's filter joins over already-fanned rows
(4 → 8, 6 → 12); (3) the extent rows replaced the fold as the frame's plan of record, so the
SQL-text referee executed a projection of every declared column, some absent from the seeded
table; (4) five from-envelope rebuilds copied the envelope through a constructor that dropped
the frame — the property and filter readers silently lost the query's filter (six orgs instead
of three). The constructor is deleted (`TypedFrom.withSource` keeps both facts); the rule above
is the fix for 1–3. Process lesson, recorded: the judge was six hand-picked witnesses, all
plain `Class.all()` lets — exactly the case the mechanism handles. The DuckDB database lane
runs BEFORE a rung is reported closed, never after.

| rung | statements | chars | subqueries | status |
|---|---|---|---|---|
| r05 one let + assertSize | 1 | 693 (was 607: the CTE definition) | 2 | CLOSED |
| r06 assertSameElements over a class let | 1 | 1,850 (was 1,748) | 5 | CLOSED |
| r10 two lets | 1 | 1,425 (was 1,246) | 4 | CLOSED |
| r11 float multiset | 1 | 11,163 (was 11,061) | 6 | CLOSED (residual 5 stands) |
| r12 class let, three readers | 1 (was 3 scans of T) | 3,698 | 9 | CLOSED |

Single-reader class lets (r05, r06, r10, r11) ride the same CTE — one rule, no reader-count
special case, as the relation lets of r07–r09 already did. Lean targets re-pinned.

Residuals added:

6. The SQL-text referee derives ROWS for a text assert by executing our plan and the golden
   SQL outside the body's statement — one more statement per text assert (272 in the corpus).
   Under the north star both sides ride the fused statement; a separate leg.
7. The 1,690 class frames outside the rule (join-stepped pipelines, sorted / capped /
   milestoned extents) still paste their chain per reader — the next rungs.
