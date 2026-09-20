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
