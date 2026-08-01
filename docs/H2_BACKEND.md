# H2 as a real backend — design

> **Verification addendum (2026-07-31, main import — probed against real h2-2.4.240.jar).**
> The latest H2 (2.4.240, 2025-09-22) was probed for the D1 wall on user request:
> - `LATERAL` is STILL not a keyword (`Function "LATERAL" not found`, Parser.readTableFunction).
> - Correlated table-function ARGUMENTS still fail everywhere (`t, UNNEST(t.arr)`,
>   `CROSS JOIN UNNEST(t.arr)`, `SYSTEM_RANGE(1, t.n)`, and even
>   `(SELECT SUM(c) FROM UNNEST(t.arr) v(c))` inside a scalar subquery — all
>   `Column "T.ARR" not found`). **D1 stands at 2.4.240**; the collection-carrier
>   deferral (§4.1) remains correct.
> - BOUNDARY: correlation IS legal in the WHERE/SELECT over an uncorrelated table
>   function (`SELECT MAX(x) FROM SYSTEM_RANGE(1,3) r(x) WHERE x < t.n` works) —
>   bounded-explosion rewrites are available where a static bound exists.
> - `QUALIFY` confirmed native on 2.4.240. JSON navigation SETTLED by syntax battery:
>   ARRAY indexing works (`(JSON '[10,20,30]')[2]` -> 20, 1-based) but OBJECT FIELD
>   access exists in NO syntax (`json['a']` = data conversion error; `(json).a` = NULL) —
>   the engine itself uses its legend_h2_extension_json_navigate Java UDF for this
>   (the §4.2 route this doc bans). Step 6's rationale is therefore WEAKENED: the bump
>   buys array indexing only, not VARIANT_GET. core/pom stays at the engine's 2.1.214
>   until the declared-gap registry quantifies what array indexing alone unlocks.
> - Step 6's bump target is 2.4.240, but the GOLDEN-replay side stays 2.1.214: the
>   engine's goldens ran on a FORKED 2.1.214 (§7 — charPadding + boolean-comparison
>   patches).

> **Question asked:** add H2 as a real execution backend, and make the SQL compatible with real
> legend-engine's corpus so the H2 tests pass. Those are two different projects with different
> answers, and this doc separates them.
>
> **Companions:** `ARCHITECTURE_REMEDIATION.md` (D1: N backends is a hard requirement; T3.2:
> rewrite-then-render, **complete**), `TENET_REMEDIATION.md` (tenet #1 conformance),
> `AUDIT_PROGRAM.md` (method).

**Evidence standard.** The capability map was produced by **executing ~200 probe statements against
a real `h2-2.1.214.jar`** with the engine's own connection settings, cross-checked against the
version-exact function catalog in `org/h2/res/help.csv` inside that jar. The golden-text gap was
produced by **instrumenting and running the full corpus sweep twice**. Where documentation and
execution disagreed, execution won. Claims sourced any other way are marked.

---

## 1. Verdict

**Achievable, and the blocker is not what anyone expected.**

- **SQL-side graph emission is *not* the obstacle.** It was predicted to be, twice, and refuted both
  times — the second time by running legend-lite's exact envelope shape on H2 and getting correct
  nested JSON in one statement. Graph fetch is a **rendering override plus one small rewrite pass**.
- **The wall is `LATERAL`**, which no H2 version has — not 2.1.214, not 2.3.232.
- **The wall is really about the collection carrier, not the graph.** See §4.
- **Minimum viable scope is 206 of 256 constructs — 80%** — and it includes the *complete* nested
  object-graph path.
- **Golden-text compatibility is a separate and much weaker proposition.** 25.7% of golden asserts
  already match byte-for-byte; of the divergences, **66% are query *shape*, not spelling**, and the
  total achievable by normalization is ~6%. Recommendation in §6: binding for a defined subset only.
- **Something is already broken.** `EngineStyleH2` emits SQL that H2 cannot parse, today, in a
  shipped surface (§5).

---

## 2. The capability map — 256 constructs

Buckets: **A** native · **B** MIR rewrite pass · **C** rendering override only · **D** structurally
impossible on H2.

| | SqlFn | SqlExpr | Sources/joins | Types | Aggregates | **Total** | |
|---|---|---|---|---|---|---|---|
| **A** native | 74 | 18 | 8 | 8 | 30 | **138** | 54% |
| **C** rendering only | 46 | 8 | 0 | 5 | 6 | **65** | 25% |
| **B** rewrite pass | 0 | 0 | 3 | 0 | 0 | **3** | 1% |
| **D** impossible | 43 | 2 | 3 | 1 | 0 | **49** | 19% |
| | 163 | 29 | 14 | 14 | 36 | **256** | |

> *Correction to an earlier estimate: `SqlFn` has **163** constants, not ~34 — 77 spelling data rows
> in `Spellings.java`, 11 infix, 75 coded arms. A first grep caught one declaration form.*

**Notable A's, verified by execution:** correlated scalar subqueries nested ≥3 deep including inside
`JSON_OBJECT`; all window frame kinds including `RANGE BETWEEN INTERVAL '1' DAY PRECEDING`; **native
`QUALIFY`** (H2 has it — `QualifyToSubselect` becomes a free fallback rather than a requirement); all
30 aggregate/window functions with `DISTINCT` and in-aggregate `ORDER BY`.

**Notable C's:** H2 has **no bitwise operators at all** (`1 << 2` is a syntax error) — `BITAND`/`BITOR`/
`LSHIFT` etc. instead; 12 temporal functions map through `EXTRACT`/`DATEDIFF`/`DATEADD`/`FORMATDATETIME`;
`ROUND_EVEN` has no H2 equivalent (H2's `ROUND` is HALF_UP — `2.5→3`, `3.5→4`, measured).

---

## 3. The graph layer is not the blocker — verified by execution

This shape, taken from `Lowerer.java:627` and `:737`, **ran on H2 2.1.214 and returned correct
nested JSON in one statement, no round trips**:

```sql
SELECT COALESCE(JSON_ARRAYAGG(JSON_OBJECT(
  'name': s.name,
  'dept':     (SELECT JSON_ARRAYAGG(JSON_OBJECT('name': d.name)) FROM dept d WHERE d.id = s.dept_id),
  'projects': (SELECT COALESCE(JSON_ARRAYAGG(JSON_OBJECT('name': p.name)), JSON '[]')
               FROM proj p WHERE p.emp_id = s.id)
) ORDER BY s.id), JSON '[]') AS result FROM emp s;
```

Also verified: three-level nesting; nested JSON stays JSON rather than becoming a quoted string;
ordered aggregation with `DESC` and `NULLS LAST`; date/timestamp/decimal/boolean leaves.

**Three deltas, all rendering:**

| | DuckDB | H2 |
|---|---|---|
| Object constructor | `json_object('k', v, …)` — positional pairs | `JSON_OBJECT('k': v, …)` — **the positional form is a syntax error** |
| Array aggregate | `coalesce(json_group_array(x),'[]')` | `COALESCE(JSON_ARRAYAGG(x … NULL ON NULL), JSON '[]')` — **H2 defaults to `ABSENT ON NULL`; DuckDB keeps nulls.** Must be explicit |
| Read-back | driver returns a JSON node | H2 maps `JSON` to **`byte[]`**, so `Executor.java:118-119`'s `String.valueOf(rs.getObject(1))` yields `[B@1f3a` |

**And `CheckedEnvelope` turns out to be a rewrite, not a wall.** `CheckedEnvelope.java:59-65` builds
`TO_VARIANT(LIST_FILTER(ARRAY[case1, case2, …], x -> x IS NOT NULL))` over a **statically sized**
array — one `CASE` per constraint. H2's `JSON_ARRAY(… ABSENT ON NULL)` drops NULL elements, which is
that `list_filter` exactly, with no lambda:

```sql
SELECT JSON_ARRAY(CASE WHEN 1=0 THEN JSON_OBJECT('id':'c1') END,
                  JSON_OBJECT('id':'c2'), ABSENT ON NULL)   -->  [{"id":"c2"}]
```

One `SqlRewriter` pass keyed on `LIST_FILTER(ArrayLit, Lambda(x, IS_NOT_NULL(x)))`. **Bucket B.**

### 3.1 And legend-lite's approach is *less* demanding of the backend than the engine's

Worth recording, because it inverts the intuition that SQL-side assembly costs portability.

legend-lite's graph fetch needs **read-only `SELECT`**. The engine's needs DDL rights at query time,
a session temp namespace, connection-scoped drop hooks, a JVM hash map holding every parent object of
the batch — and for H2 specifically it **spools parent primary keys out of the JVM to a CSV file on
disk, then `INSERT INTO temp SELECT * FROM CSVREAD('<path>')`**
(`§/graphFetch/relationalGraphFetch.pure:283-289`).

That is why the engine gates temp-table graph fetch to **2 of 21 backends** (`:108-114`) and ships
`throw new UnsupportedOperationException("not yet implemented")` for BigQuery, Databricks and Trino.

**The engine's graph fetch is the largest violation of legend-lite's tenet #1 in the entire reference
codebase** — `addChildToParent(Object, Object, IExecutionNodeContext)` over a `DoubleStrategyHashMap`
keyed by both Java objects and live `ResultSet` cursor positions. Do **not** adopt it as an escape
hatch. Borrow only its *taxonomy*: graph fetch has three legitimate shapes (SQL-assembled,
temp-table-joined, IN-list-joined) and a backend's capabilities select one.

**Record the real cost honestly:** N nested correlated scalar subqueries have a depth-scaling planner
cost that N flat queries plus a Java join do not. That is a **performance** ceiling to measure, not a
correctness objection.

---

## 4. The real wall — and what it is actually about

### D1 — no `LATERAL`, no correlated table function. Unfixable.

`LATERAL` is not a keyword in H2 2.1.214 **or 2.3.232** — the parser reports
`Function "LATERAL" not found`, and the upstream issue is still open. Every correlated FROM form
fails: `t, UNNEST(t.a)`, `CROSS JOIN UNNEST`, `JOIN UNNEST … ON TRUE`, `t, TABLE(v INT = t.a)`,
`t, SYSTEM_RANGE(1, t.n)`, even `t, (SELECT t.a)`.

**Kills:** `SqlFn.UNNEST` × 11 emission sites (`Lowerer.java:234, 255, 387, 2920, 3115`;
`Scalars.java:853, 855, 1728, 1730, 2836, 2838`), `CROSS_LATERAL` (`Lowerer.java:351`),
`LEFT_LATERAL` (`:2930, :2958`), the whole 16-member `LIST_*` value family, `LIST_FLATTEN`,
`RANGE_FN`, `VARIANT_ELEMENTS`.

**Real carve-out:** where the array is **not row-correlated** — a literal collection, a FROM-less
scalar root at `Lowerer.java:255` — select-list `UNNEST` rewrites to FROM-clause `UNNEST`, which H2
has. `FROM UNNEST(ARRAY[1,2], ARRAY['a','b']) AS t(c1,c2)` even zips multiple arrays, matching the
parallel select-list unnest at `Scalars.java:853-856`. **So D1 is *correlated* explosion, not all
explosion.**

### The other D's, ranked

| | Gap | Scope | Note |
|---|---|---|---|
| **D2** | No JSON navigation | `VARIANT_GET` ×4, `VARIANT_ELEMENTS` ×4, `JSON_TYPE`, `JSON_MERGE_PATCH` (declared, zero construction sites — dead vocabulary) | **Mostly fixable by version bump** — H2 2.2.220+ adds `(json).field` / `json[i]`. `JSON_TABLE` never exists, so `VARIANT_ELEMENTS` stays D |
| **D3** | No lambdas, any version | 27 `SqlExpr.Lambda` sites, 4 `FoldCall` strategies, `LIST_FILTER` ×9 | Standard lambda-free encoding needs `UNNEST` + re-`ARRAY_AGG` → blocked by D1 |
| **D4** | `SourceUrl` | 1 site (`Lowerer.java:305`) | `data:` needs JSON→rows (D2); `file:` needs a JSON reader (H2 has CSVREAD only) |
| **D5** | No `MAP` type | 7 SqlFns, 3 type sites | Re-encoding as parallel arrays or JSON re-enters D1/D2 |
| **D6** | No `TYPEOF` | `Fold.jsonDateWrap:534-538`, **fires on every Pure `Date` graph leaf** | **Soft — downgradeable to C.** The dispatch is runtime because "setup DDL can diverge from the store declaration", but on H2 the column type is statically known from `INFORMATION_SCHEMA` |
| **D7** | Dynamic `PIVOT` | empty-`in` form | Static `in`-pinned form is **B** (conditional aggregation). `Executor` already has a `hasPivot` dynamic-column path |
| **D8** | Missing functions | `SPLIT`, `REVERSE_STRING`, `LEVENSHTEIN`, `JARO_WINKLER`, `REGEXP_EXTRACT_ALL`, base64 ×2 | Absent from every H2 version; pure-SQL constructions need correlated `SYSTEM_RANGE` (D1) |

### 4.1 The redesign, if you want the last 19%: it is the collection carrier

**Not the graph layer.** Today a Pure `List<T>` becomes `SqlType.Array` (`PureSql.java:98`) and every
operation on it is a DuckDB list lambda. Of legend-lite's **122 `SqlFn.LIST_*` sites, 110 are in
`Scalars.java`** — the PCT collection-function surface. Graph fetch touches `LIST_FILTER` at exactly
one place.

Reaching H2 means lowering collections to **relations** — a correlated derived table joined on a
synthetic key, or an ordinal-keyed side table — so that `filter`/`map`/`fold`/`exists` become
`WHERE`/`SELECT`/window/`EXISTS` over rows rather than lambdas over an array value. That is a
Phase-H/I redesign of `Scalars.java` + `Fold.java` + `ListShapes.java`, not a dialect exercise.

**And it is the right redesign for N backends generally**, since Postgres, Snowflake and BigQuery
each need a *different* one of unnest-lateral / array functions / lambdas. H2 is simply the backend
that forces the question.

### 4.2 Do not take the `CREATE ALIAS` exit

H2's `CREATE ALIAS` is **enabled by default** in 2.1.214 (verified — both a `FOR "java.lang.Math.abs"`
alias and an inline-source alias were created and called). It would close every remaining D — by
running Java **inside the database process**, requiring DDL rights on the target, and opening
remote-code-execution surface (`h2.allowedClasses` defaults to `*`).

That is tenet #1 inverted, not satisfied. The engine does exactly this — 15 UDFs, §7 — and roughly a
dozen of its H2 function spellings *are* those UDFs rather than H2 SQL.

---

## 5. Already broken today — fix regardless of this project

### H5.1 — `EngineStyleH2` emits SQL that H2 cannot parse

All three probed against real H2 2.1.214 with the engine's connection settings.

| Defect | Evidence | Scope |
|---|---|---|
| Constructed with **`Spellings.DUCKDB`** (`EngineStyleH2.java:49`), so it emits `starts_with`, `ends_with`, `strpos`, `regexp_full_match`, `to_base64`, `from_base64`, `to_days` | all `[90022] Function not found` | 35 records / 12 tests |
| `SqlExpr.Star` renders `ident(s.table()) + ".*"` (`AnsiSqlRenderer.java:282`) and `EngineStyleH2` never routes it through `rename()` (`:253-254`) — the source renders `as "root"` while the star still says `t1.*` | `[42102] Table "T1" not found` | 34 records / 33 tests |
| `AND` rendered as a flat `String.join(" and ", …)` **ignoring `parentPrec`** (`EngineStyleH2.java:647-655`), dropping the parens `AnsiSqlRenderer.java:384-386` requests | `!(A && B)` becomes `NOT A and B`, i.e. `(NOT A) AND B` — **wrong semantics** | `testSelectNotEqualNotAnd` |

**Common root: 1,062 lines of renderer that nothing ever executes.** Nothing can catch a bug in it
except byte-comparison against a golden — the comparison currently declared advisory. The execution
path is unaffected (`AnsiSqlRenderer` honours precedence correctly).

### H5.2 — `INT_DIVIDE` renders `(a // b)`, and `//` is a line comment in H2

`SELECT 7 // 2` returns **7**, silently, no error. Must render `a / b`. Same family, all measured:
H2's `GREATEST`/`LEAST` skip NULLs; `ROUND` is HALF_UP not banker's; `JSON` equality is textual and
key-order-sensitive.

### H5.3 — `Compiler.dialectOf:320-322` is `case H2 -> distinct.add("DuckDB")`

An H2-typed connection executes on the DuckDB renderer today. There is no H2 execution path at all,
not even a broken one.

### H5.4 — dialect and connection are never reconciled

`Compiler.execute(…, Connection)` (`:354`, `:383`, `:445`) takes the dialect from runtime metadata and
the connection from the caller, and **nothing asserts they agree**. Silent corruption, present today,
and a prerequisite to fix before a second backend exists.

### H5.5 — `Ddl.createTableStatementText` emits invalid DDL

`Ddl.java:79-104` quotes only H2 reserved words and ignores the parser's `quoted()` flag. Against
`relationalSetUp.pure:117`'s `tableWithQuotedColumns` it emits
`FIRST NAME VARCHAR(200) NOT NULL, … 1columnStartsWithNumber VARCHAR(200) NULL, PRIMARY KEY(ID,FIRST NAME,LAST NAME)`
— invalid on every dialect. Invisible for the same reason as H5.1: a text-only surface
(`StatementExecutor.java:2835`) that never executes.

---

## 6. Golden text — measured, and the recommendation

### 6.1 The assertion mechanism is exact string equality

`assertSameSQL` (`tests/testAssert.pure:18`) is a bare `assertEquals` after
`sqlRemoveFormatting` (`helperFunctions.pure:58`), which is
`$sql->replace('\n','')->replace('\t','')` — it **deletes** newlines and tabs. It does not collapse
runs of spaces, trim, case-fold, or touch quoting. **Spaces are significant.** Nothing anywhere in
either repo parses two SQL strings and compares structurally.

So "make the H2 goldens pass" is well-posed — and extremely tight.

**Denominator:** 2,687 `test.Test` functions in the engine's relational tree; **1,581 (58.8%)
contain at least one golden-SQL assertion**, of which ~1,329 are H2 and the rest indeterminate (in
practice H2). The golden corpus is essentially all H2.

### 6.2 Where legend-lite stands — instrumented census, 1,454 golden asserts reached

| Outcome | Asserts | Share |
|---|---|---|
| **TEXT_MATCH — byte-exact reproduction of the engine's H2 golden** | **373** | **25.7%** |
| TEXT_DIFF, golden-on-H2 rows == our rows | 533 | 36.7% |
| TEXT_DIFF, row replay unverifiable | 382 | 26.3% |
| TEXT_DIFF, rows also differ | 3 | 0.2% |
| RENDER_THREW (`EngineStyleH2` threw) | 130 | 8.9% |
| NO_EXEC_CALL | 33 | 2.3% |

**373 pass (25.7%) · 918 fail (63.1%) · 163 not attempted (11.2%).**

### 6.3 The 317 divergences: two thirds are query shape, not spelling

| | Records | Tests |
|---|---|---|
| **Render-only** — same shape, different text | **129 (34.7%)** | **108 (34.1%)** |
| **Structural** — different query shape | **243 (65.3%)** | **209 (65.9%)** |

Structural causes: subselect nesting depth 101 · semi-join strategy (engine
`left join (select distinct …) … is not null` vs our correlated `EXISTS`) 55 · union frame 46 · join
count 17 · null-guard count 16 · CASE-chain vs folded constant 7 · group-by 1.

Render causes: function spelling 33 · misc spelling 31 · output-alias presence 29 · projection order
24 · unrenamed `tN` alias 11 · casing 1.

### 6.4 Normalization is a dead end

Normalizing **both** sides for casing, quoting and alias identity makes **1 of 372** records equal.
Additionally erasing every output alias reaches **23 of 372 (6.2%)** — and that is semantically
destructive, since aliases are the TDS column names.

**There is no normalization that buys more than ~6% of the gap.**

### 6.5 Three reasons not to make golden text binding corpus-wide

**Matching would make execution worse.** The largest structural class is the semi-join: the golden
re-scans and re-joins the root table *inside* a subquery purely to compute emptiness, then
materializes a `DISTINCT` over it. That is strictly more work than a correlated anti-join on both
DuckDB and H2 — 55 records / 49 tests.

**58.7% of diverging tests compare against a host-assembly query.** 213 of 372 records (186 of 317
tests) are class-root goldens: a flat `select "root".ID as "pk_0", …` rectangle that the engine's
Java then assembles into objects. `RelationalRootForm.java:27-38` says exactly this in its own
header. Converging means abandoning tenet #1 or shipping two lowerings.

**At least one golden is provably wrong.** `testExistsWithAttributesFromLeftInAndCondition`
(`functions/tests/testExists.pure:410-417`): the pure source reads `$p.name` from the **outer**
person; the engine's decorrelation into `select distinct` rebound it to the **inner** employee.
legend-lite is correct. Rows coincide on the seed data so both pass — but *making the golden pass
would require reproducing an engine bug*. `…InOrCondition` at `:419` is the same shape. **Any binding
contract must be able to say "this golden is wrong," which is what binding contracts are bad at.**

### 6.6 Recommendation

1. **Pin the 373 that already match**, so they cannot silently regress. Today a golden that stops
   matching merely increments a column. Cheapest real gain available; costs nothing in lowering.
2. **Split the 317 ledger into two columns** — 108 spelling, 209 shape. The first is a burndown list;
   the second is an architecture decision that should be made explicitly rather than accumulated.
   Conflating them is the `CORRECTNESS_REMEDIATION` §1 failure mode.
3. **Do not make golden text binding corpus-wide.** Row equality stays the contract.
4. **Do the render-only fixes anyway** (§6.3's 129 records) — they are cheap, and three of them are
   correctness fixes rather than cosmetics (H5.1).

---

## 7. Connection and configuration — "H2 compatible" does not mean stock H2

**The engine forks H2's source.** Its `h2-execution-2.1.214` module shades the upstream jar while
*excluding* two classes it then supplies itself, each carrying `@legend-fix` comments:

- `org/h2/engine/Mode.java:509-510` — `mode.charPadding = CharPadding.NEVER`
- `org/h2/value/TypeInfo.java:1000, 1011` — allow numeric↔boolean and varchar↔boolean comparisons

Without the first, `CHAR(n)` pads and string comparisons diverge from the goldens. Without the
second, the corpus's numeric↔boolean comparisons throw. **Decide this explicitly — either shade a
patched H2 at test scope, or accept a named exclusion set — but do not discover it as mysterious row
divergence.**

**Plus `MODE=LEGACY` and a 26-word `NON_KEYWORDS` list**, and **15 Java UDFs installed on every
connection** (`CREATE ALIAS legend_h2_extension_*`: `json_navigate`, base64, `hash_md5/sha1/sha256`,
`flatten_array`, `split_part`, `edit_distance`, `jaro_winkler_similarity`, `convertTimeZone`,
`lpad`/`rpad`). Roughly a dozen of the engine's H2 function spellings are those UDFs.

**Three concrete deltas for legend-lite:**

- **Add `OVER` to `NON_KEYWORDS`.** The corpus path (legend-pure `TestDatabaseConnect.java:107-126`)
  omits it; the *execution* path (`H2Manager.java:69`) includes it; and `over` is a reserved word
  requiring quoting. `H2Verify.java:65-75` currently mirrors the corpus list.
- **Pin `timeZone = 'GMT'`.** `relationalSetUp.pure:1242-1248` hard-codes it and every temporal
  expectation in the corpus depends on it.
- **Adopt quote-if-needed.** The engine leaves `DATABASE_TO_UPPER` at its default (true);
  `H2Verify.java:65-75` must set `DATABASE_TO_UPPER=false;CASE_INSENSITIVE_IDENTIFIERS=TRUE` because
  `Ddl.createTable:47-51` quotes **every** column. Driving quoting from the parser's
  `ColumnDefinition.quoted()` flag — already recorded at
  `RelationalGrammarParser.java:217-227`, consulted by only `StoreCompiler.java:93` and
  `TestDataGenerator.java:1098` — would let legend-lite run the engine's exact connection string.
  **Watch the collision:** `RawSqlBoundary.quoteCreateColumns:135` quotes for the *opposite* reason on
  DuckDB. Two backends want opposite policies from one boundary.

---

## 8. The one architectural change: widen the dialect seam

`SqlDialect.render(SqlQuery) → String` **structurally cannot express "and also run these statements
first."** That is why the three existing MIR passes are query-shaped and stop where they do.

The engine's most valuable portability mechanism is not a spelling table — it is
`PostProcessorResult{query, executionNodes, postExecutionNodes, finallyExecutionNodes}`. Every hard
capability gap it survives is a dialect **adding statements to the plan**: oversized `IN` lists become
a temp table plus an `AllocationExecutionNode`; Snowflake gets session pre/finally SQL.

**Recommended:**

```
Lowered lower(SqlQuery) → { String sql, List<Statement> before, List<Statement> after }
```

plus a **capability budget** record mirroring the engine's, so the existing passes become
budget-driven rather than dialect-name-keyed:

| Budget | H2's value |
|---|---|
| `aliasLimit` | 256 (was 1000 in 1.4.200) |
| `collectionThresholdLimit` | not set — H2 never gets the IN→temp-table rewrite |
| `supportsFullOuterJoin` | false |
| `supportsBooleanLiteral` | true |
| `limitStyle` | `top N` for take; `offset N rows fetch next M rows only` for slice — **never a LIMIT/OFFSET pair** |
| `nullOrdering` | explicit |

**Tenet #1, stated plainly:** `before`/`after` are **sequencing**, which is exactly what tenet #1
grants Java. This is not a breach. **The line to write into the seam's contract:** a `before`
statement may create, populate or drop — it may **never compute a result the query then consumes as a
Java object**. Without that sentence written down, this erodes into the engine's
`PlatformRoutingStrategy`, which is the fallback sink for everything its SQL cannot do.

Note also that `T3.2` is **already complete** (`ARCHITECTURE_REMEDIATION.md:27`) — `Lexicon`,
`TypeNames`, `Spellings`, `SqlRewriter` and the `passes()` slot all exist. Its `ValueCodec` step was
deliberately deferred with the note that it *"materializes with the first real backend codec row."*
**This is that row:** `SqlDialect.normalize` has zero overrides today and H2 needs them for `BOOLEAN`
(H2 BIT→Integer vs DuckDB Boolean), `DECIMAL` scale, JSON (`byte[]`), and arrays/structs.

---

## 9. The declared-gap registry — copy this

The engine solved a problem legend-lite is about to have. `SqlDialect.expectedSqlDialectTestErrors:
Map<String,String>` (`sqlDialect.pure:38`, H2's populated at `h2SqlDialect.pure:74-118`) plus JSON
manifests `pct-manifests/relational-<db>/<Scope>_manifest.json` of shape
`{"test":…, "expectedError":…}`. **H2 has 512 entries**; 6,717 across all dialects.

The semantics are what matter:

> A named error the manifest **expects** is a **passing** test. A named error whose **text changed**
> is a **failure**. An **unexpected pass** is *also* a failure.

That is the difference between a burndown ledger and a skip list, and it is the only way ~50 honest
capability refusals (§4) stay legible across a multi-backend matrix. It is tenet #3 given a
machine-readable home.

---

## 10. Test matrix

- **`SHAPE` stays one-dimensional.** It is a property of the test body, not the backend; giving it a
  backend axis doubles the denominator to 304 dishonestly — the `CORRECTNESS_REMEDIATION` §1 failure
  mode exactly.
- **Two distinct new outcomes, not one soft bucket.** `UNSUPPORTED` = the renderer threw a capability
  wall (honest, expected, §9-registered). `FAIL` = H2 executed and produced wrong rows. A test that
  passes on DuckDB and fails on H2 is a **FAIL in the H2 row**, and must not touch the DuckDB
  baseline.
- **Backend declaration already exists** — `ConnectionDefinition.DatabaseType` (`:47-54`) already
  lists H2, Postgres, Snowflake, BigQuery. Don't add an annotation; add a portability sweep
  (`-Drcorpus.backend=h2`) that overrides dialect *and* connection together, which is precisely the
  reconciliation missing at H5.4.
- **Fix the positional-parse trap in the same change.** `RelationalCorpusRunner.java:208-231` parses
  the pass column **positionally as `cells[3]`**. Insert any column before position 3 and the gate
  silently reads the wrong number and degrades to green. Convert `readBaseline` to parse by header
  **name**.

---

## 11. Dependency structure

Incoherent today, independently of this project:

- `core/pom.xml:25-32` declares duckdb + sqlite at **compile** scope, **no H2**.
- `core/src/main/java/com/legend/exec/DbMetaData.java` — **production** code — requires
  `org.h2.Driver` at runtime, and opens `jdbc:h2:mem:` at `:68` and `:92` with *different* settings
  than `H2Verify`. Core already runs H2 in production with two divergent session configs.
- H2 exists only at `engine/pom.xml:42-46`, **test** scope, which is not transitive.
- Therefore `fetchDb*MetaData` works **only** inside `engine`'s test classpath and fails for core's
  own tests, `pct`, `nlq`, and every real consumer.

**Target:** H2 moves to `core/pom.xml` at compile scope alongside duckdb and sqlite, on the same
stated rationale. Required by `DbMetaData` today regardless, and it removes the `Class.forName`
reflection dance. `H2Verify` then relocates out of `core/src/main/harness` into the test-matrix
harness (which also serves `TENET_REMEDIATION` V3.1). Orthogonal to `engine/`'s deletion — don't wait
on it.

---

## 12. Sequencing

**Milestone 1 — connect two halves that already exist. Refactors nothing.**
> **MEASURED (2026-07-31, landed):** `h2-exec (our SQL on H2): 289 verified, 0 diverged,
> 135 unverifiable` — corpus-wide, first sweep. Unverifiable census (both replay paths):
> 445 non-tabular frames (the F2 layer gap — class/graph carriers), 18 enum-decoded-column
> arm (re-verify: c31 moved decode into SQL, the arm may be stale), 6 tempTableForIn_N
> (engine-RUNTIME temp tables inside the golden — registry entries), 9 'Duplicate column
> name' (the engine's forked H2 tolerates duplicate columns in derived tables where stock
> 2.1.214 rejects — §7's fork, registry candidates), 1 legend_h2_extension_base64 UDF
> golden (registry). The step-2 CsvSeed fix RETIRED the entire ~39-row
> 'Table already exists' class. Zero divergences: every byte-matched
> rendering executes on real H2 2.1.214 with rows identical to DuckDB.

> For every corpus test that today produces a **golden-matching `toSQLString(H2)`** and a **tabular**
> result, execute *that text* on the H2 already stood up from the recorded statements, and assert row
> equality against the DuckDB rows. Report the count as a new scoreboard column.

That is ~373 asserts of real H2 execution of **legend-lite's own SQL** — not the engine's, which is
what `H2Verify` runs today (`h2Upgrade` passes the corpus test's expected string as `goldenSql`,
`TestBody.java:1066`, so it proves nothing about our rendering). It would have caught all three H5.1
defects immediately.

**Ordered work:**

1. **H5.2, H5.1, H5.5** — the silent wrong-answer and invalid-SQL fixes. Independent of everything.
2. **`CsvSeed.java:65`** — emit `DROP TABLE IF EXISTS` + `CREATE TABLE` instead of `CREATE OR REPLACE
   TABLE`. Recorded root cause of ~39 H2 replay declines and on the critical path.
3. **Fix `H2Verify.norm`** — drop the blanket `MathContext(10)`; compare by declared SQL type, not
   `toString`. Measured: collapse begins at **11** significant digits and applies to *both* sides, so
   two different values agreeing in their first 10 digits compare **equal** — a silent false PASS, with
   epoch-millis inside the blast radius. Without this, row equality is not an oracle.
4. **Instrument the two silent `ADVISORY_MARKER` declines** (`TestBody.java:1041`, `:1058`) so the
   recorded 414 becomes a true total.
5. **Milestone 1.** Measure N.
6. **Bump H2 off 2.1.214** to 2.2.220+. Single highest-leverage change in this analysis — converts
   most of D2 from impossible to a rendering override.
7. **Move H2 to `core/pom.xml`**, compile scope (§11).
8. **Author `Lexicon.H2` / `TypeNames.H2` / `Spellings.H2`** — the H2 reserved-word list already
   exists at `Ddl.java:59-71`. Add an **`H2` execution dialect extending `AnsiSqlRenderer`** — *not*
   `EngineStyleH2`, whose formatting is byte-pinned to goldens and whose quarantine (ArchUnit
   Invariant 4d) should survive untouched.
9. **Widen the seam** (§8) and add the capability budget.
10. **Flip `Compiler.java:322`**; introduce the session type binding dialect + connection (H5.4).
11. **Populate `SqlDialect.normalize`** — the first real codec rows.
12. **`h2ToDuckDb` becomes identity for the H2 target.** Once (2) lands, corpus raw statements need no
    translation on H2; `RawSqlBoundary`'s rewriting is DuckDB-target-only. `RawSqlBoundary:105-107`'s
    schema-idempotency rewrite exists solely because DuckDB's catalog persists across a family while
    H2 is per-connection ephemeral — unnecessary for a fresh-per-test H2.
13. **The declared-gap registry** (§9), then the portability sweep (§10).

**Deferred, deliberately:** the collection-carrier redesign (§4.1). It is the right work and it is not
first — it should be entered with H2 already executing 80% of the corpus, so the remaining walls are
measured rather than predicted.

**No ordering constraint against T4.1 (Split F).** `ARCHITECTURE_REMEDIATION.md:437` already states
the dialect track can run in parallel.

---

## 13. What NOT to do

- **Don't adopt the engine's temp-table graph fetch** as a fallback. Its prerequisites are strictly
  harder than `SELECT json_object(...)`, which is why the engine ships
  `UnsupportedOperationException` for BigQuery, Databricks and Trino.
- **Don't take the `CREATE ALIAS` exit** (§4.2). It closes every remaining gap by running Java inside
  the database.
- **Don't make golden text binding corpus-wide** (§6.5), and don't reach for normalization — the
  ceiling is ~6%.
- **Don't make `EngineStyleH2` the execution dialect.** Its output is byte-pinned to goldens; build a
  sibling and keep Invariant 4d.
- **Don't create a soft `h2-divergence` bucket** (§10).

---

## Step 10 LANDED (2026-07-31, c43) — the portability sweep executes

`-Drcorpus.backend=h2` opens a FRESH in-memory H2 per test
(`H2Verify.SETTINGS`, engine H2Manager parity) via `Runner.openSession()`
— ONE session factory, dialect and connection bound together:

- **H5.4 reconciliation is structural**: `Compiler.dialectOf(ctx,
  runtimeFqn, connection)` reads the LIVE connection's product name at
  the one execution seam (`executeResolved`); an H2 session selects the
  H2 execution dialect and LOUDLY rejects non-H2 declared connection
  types. Non-H2 sessions resolve exactly as before — the DuckDB
  reference path is unchanged (verified: full sweep byte-identical,
  2180 + h2-exec 289/0/135 + PCT 1109 + core 1573).
- The synthesized driver connection `rcorpus::Conn` now declares the
  ACTUAL session type (H2 under the flag) — the declaration honesty the
  reconciliation depends on.
- **Step 12 fell out**: `SqlDialect.rawH2IsNative()` (H2 dialect: true)
  gates every `RawSqlBoundary.h2ToDuckDb` call site — corpus raw H2 and
  module DDL execute VERBATIM on the H2 session, translated only for
  the DuckDB target.
- The h2 sweep NEVER writes the scoreboard and skips the DuckDB
  baseline gate (§10 rule: an H2 FAIL must not touch the DuckDB row);
  it prints its own `[rcorpus] h2-backend` family lines.

**First measured number: 476/2538 pass (18.8%), 0 failed seeds.**
Already clean: sqlDialectTranslation 21/21, postprocessor 7/7,
debugPrint 9/9, modelToModelToRelational 5/5, inClause 3/4,
lineage/scanRelations 40/49, distinct 11/18. Big honest walls (scoped
probes): UNNEST collection-carrier (§4.1, deferred — dominates
calendarAggregation 1/92 and sub-aggregation), LIST_AGG list encoding,
`rowid` ordering DuckDB-ism inside STRING_AGG (H2 spells `_ROWID_`),
enum decode (enumeration 0/26), milestoning 9/224 (family-wide cause
unmeasured — next census target). Next: per-family wall census drives
step 9 (capability budget) and step 11 (codec rows).

## Step 10 census + first burn (2026-08-01, c44) — 476 → 679/2538

CENSUS (scoped probes, reasons bucketed by count): the corpus-wide H2
walls rank (1) UNNEST collection-carrier (§4.1, deferred: 72 milestoning
+ 134 functions + 116 tds + 12 enumeration in the four probes alone),
(2) the JSON GRAPH ENVELOPE — DuckDB spellings executing raw on H2,
(3) array-literal/LIST_AGG/LIST_GET/TYPEOF list encodings (carrier
family), (4) small singles (`rowid` order key, parseJSON/alloyConfig
platform fns, executeLegendQuery overloads).

LANDED (everything probed on the real 2.1.214 jar first):
- `SqlExpr.RowOrder` — the physical row-order pseudo-column is now an
  IR NODE spelled per dialect (DuckDB `rowid`, H2 `_ROWID_`), not a
  DuckDB string baked into the Lowerer's STRING_AGG determinism key.
- `AnsiSqlRenderer.jsonObject/jsonArrayAgg` became dialect hooks; H2
  overrides with the probed SQL-standard forms `JSON_OBJECT('k': v)`
  and `COALESCE(JSON_ARRAYAGG(v ORDER BY …), JSON '[]')`.
- Step 11 codec row #1: H2 hands JSON back as `byte[]` —
  `H2.normalize` canonicalizes to the UTF-8 string; `Executor`'s GRAPH
  envelope read now routes through `dialect.normalize` (the raw
  `String.valueOf(byte[])` produced `[B@…`, which the Json parser
  surfaced as `For input string: ""` across 110 graph tests).
- EngineStyleH2 renders RowOrder through its alias plan (golden text
  unchanged).

RESULT: h2-backend 679/2538. Movers: graphFetch 2→95/143 (+union
0→12/15, domain 1/1), functions 11→45, query 0→13, advanced 12→24,
milestoning 9→19, extends 4→10. No family regressed. DuckDB reference
path: byte-identical counts (2180, h2-exec 289/0/135, core 1573, PCT
1109); one SHAPE wall-diagnosis TEXT flapped on testResultToJsonStream
(assembly-order detail on an already-walled test, count unchanged).
